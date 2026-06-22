# Monitoring Lookup Queries

## 1. Elasticsearch Query (Primary — CP Enrichment Logs)

The `ElasticsearchLogService` polls Elasticsearch for CP enrichment logs. Records in `SENDER_STAGE` transition to `ENRICHMENT` status, then `CpLogMonitor` queries ES and `pp_log` **in parallel** for matching log entries.

### Pipeline Priority (from `StagePipelinePolicy`)

```
CP queue consumed
  → ES configured? → ELASTICSEARCH (poll CpLogMonitor)
  → Exensio configured? → EXENSIO_API (direct lookup)
  → neither → DONE immediately
```

### Elasticsearch Query JSON

Built by `ElasticsearchLogService.buildQuery()`. Sent as `POST /<index-pattern>/_search`.

```json
{
  "query": {
    "bool": {
      "must": [
        {
          "wildcard": {
            "cpConfig": {
              "value": "<cpConfigFilter>",
              "case_insensitive": true
            }
          }
        },
        {
          "term": {
            "<serviceCountryField>": "<serviceCountryFilter>"
          }
        },
        {
          "term": {
            "idData": "<dataId>"
          }
        },
        {
          "wildcard": {
            "inputFileName": {
              "value": "*<filename>*",
              "case_insensitive": true
            }
          }
        },
        {
          "term": {
            "mLot": "<lot>"
          }
        },
        {
          "range": {
            "@timestamp": {
              "gte": "<enrichmentStartTime - 2min>"
            }
          }
        }
      ],
      "should": [
        {
          "wildcard": {
            "message": {
              "value": "*output path*PRODUCTION*",
              "case_insensitive": true,
              "boost": 4
            }
          }
        },
        {
          "wildcard": {
            "message": {
              "value": "*SANDBOX*",
              "case_insensitive": true,
              "boost": 3
            }
          }
        },
        {
          "bool": {
            "must_not": [
              {
                "term": {
                  "log.level": "ERROR"
                }
              }
            ],
            "boost": 3
          }
        },
        {
          "term": {
            "log.level": {
              "value": "ERROR",
              "boost": 1
            }
          }
        }
      ],
      "minimum_should_match": 1
    }
  },
  "sort": [
    {
      "@timestamp": {
        "order": "desc"
      }
    }
  ],
  "size": 100,
  "_source": [
    "@timestamp",
    "cpConfig",
    "idData",
    "inputFileName",
    "message",
    "log.level"
  ]
}
```

### Query Filters (must clauses)

| Filter | Condition | Notes |
|---|---|---|
| `cpConfig` wildcard | Default `*sender*` | Retries with `*sender*` fallback if configured filter yields no hits |
| `service.country` term | Configurable per site | Omitted when blank. Field name varies by site |
| `idData` term | Always included | From the stage record's `data_id` |
| `inputFileName` wildcard | Optional | Only when a filename is available |
| `mLot` term | Optional | Only when `requireLot=true` AND lot is non-blank |
| `@timestamp` range | `>= enrichmentStart - 2min` | 2-minute buffer for clock skew |

### Hit Evaluation Priority (in `parseResponse`)

1. **Skip ERROR logs** in first pass
2. **"Commands flow executed successfully"** in message → `Success(PRODUCTION)`
3. **"output path = "** in message → `Success(PRODUCTION)`
4. **"PRODUCTION"** in message → `Success(PRODUCTION)`
5. **"SANDBOX"** in message → `Success(SANDBOX)`
6. **"executed successfully"** (no env keyword) → `Success(PP_LOG)` — pp_log is checked in parallel by CpLogMonitor
7. **ERROR log level** (second pass) → `Failure`
8. **No match** → `NotFound` (retry on next poll cycle)

---

## 2. CpLogMonitor: Parallel Consolidation + Exensio Fallback

`CpLogMonitor.processRecord()` runs **ES + pp_log in parallel** via `CompletableFuture`, then consolidates:

### Consolidation Priority

1. **ES Success** → transition to `EXENSIO_LOADING`
2. **pp_log Success** → transition to `EXENSIO_LOADING` 
3. **ES Failure** → mark `FAILED`
4. **pp_log Failure** → mark `FAILED`
5. **Both NotFound**:
   - **Within timeout** → retry next poll cycle
   - **Timed out (15 min)** → try **Exensio direct lookup** via `lotWaferLookup()`

### Exensio Direct Lookup (on ES + pp_log timeout)

Called from `tryExensioDirectLookup()`:

| Exensio Result | Action |
|---|---|
| `Found` (wafer_key, pg_key) | Mark `DONE` with keys via `markDoneFromExensio()` |
| `NotFound` | Mark `DONE` via `markDoneManualVerify()` with message "Manual verification required in Exensio" |
| `Error` | Mark `DONE` via `markDoneManualVerify()` with error details |
| Exensio not configured | Mark `DONE` via `markDoneManualVerify()` with message "Exensio not configured" |

### Rationale

When ES + pp_log return no info after timeout, the record is marked `DONE` (not `FAILED`) because the file may have been enriched successfully outside the CP pipeline. The `error_message` column stores "Manual verification required in Exensio" so operators can verify.

---

## 3. Exensio Raw SQL Query (Secondary — Lot/Wafer Key Resolution)

See [exensio-raw-sql-priority.md](./exensio-raw-sql-priority.md).

---

## Pipeline Flow

```
NEW → CP queue → ENRICHMENT
    → CpLogMonitor polls ES + pp_log (parallel)
      → ES Success or pp_log Success → EXENSIO_LOADING → ExensioLoadMonitor → DONE
      → ES Failure or pp_log Failure → FAILED
      → Both NotFound + timeout → tryExensioDirectLookup
        → Exensio Found → DONE (with keys)
        → Exensio NotFound → DONE (manual verify)
        → Exensio Error → DONE (manual verify)
      → Both NotFound (within timeout) → retry next cycle
```
