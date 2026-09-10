# AI-Powered Exensio Data Integration Support Assistant

## Project Proposal | Juniffer Allan Garcia

---

## 1. How We Do the Task Today

The Exensio Data Integration team supports manufacturing data flows across source systems, Dataport, Exensio, and enterprise databases. **Different data types follow different integration paths** — wafer maps, parametric data, datalogs, and CP processing each have their own flow through the pipeline.

### Current Daily Activities

Engineers perform two types of activities daily:

**A. Status Queries (60% of work)**
- "Is lot ABC12345 loaded into Exensio?"
- "Which processing stage is wafer W07 in?"
- "Which schema was this data loaded to?"
- "Has this data been processed by CP yet?"

**B. Incident Investigation (40% of work)**
- "Why is this lot/wafer missing?"
- "Where did the pipeline fail?"
- "What caused this error?"

### Primary Investigation Sources
1. **CP Processing Logs (Elasticsearch)** — CP enrichment outcomes, errors, output paths (for CPs streaming to ES)
2. **CommandProcessor Physical Log Files** — Physical log files on remote servers for CPs **NOT** streaming to ES (different servers, require SSH/file access)
3. **ETA Processing History (refdb.pp_log)** — Extract, Transform, Augment processing records in Oracle
4. **Exensio API (Raw SQL Endpoint)** — Lot metadata, wafer keys, schema information, load verification

### Current Investigation Tools
- **Manual ES queries** — Engineers write Kibana queries with lot/wafer/timestamp filters (only covers CPs streaming to ES)
- **SSH to remote servers** — Manually access CommandProcessor physical log files on different servers for non-streaming CPs
- **Database lookups** — SQL against Oracle (pp_log), PostgreSQL, Snowflake for processing history
- **Exensio raw-sql queries** — Manual queries to verify load status, schema, wafer keys
- **Some existing scripts** — A few automation scripts for common checks, but limited coverage
- **Tribal knowledge** — Investigation quality depends heavily on individual engineer experience knowing which CP is where

### The Problem
- Process is **time-consuming** — 2-4 hours per incident, 15-30 min per status query
- **Inconsistent** — Results depend on which engineer investigates
- **Disconnected** — No single view across ES, physical logs, pp_log, and Exensio
- **Knowledge loss** — When experienced engineers leave, investigation expertise leaves with them
- **Server sprawl** — Engineers must know which CP logs are in ES vs. which require SSH to remote servers
- **No reporting** — Engineers manually compile investigation findings for stakeholders
- **No visualization** — Processing timelines manually constructed from disparate sources

---

## 2. How AI Can Make It Better and Faster

Develop a **read-only AI support assistant** that augments Data Integration engineer judgment and accelerates both **status queries** and **incident investigation** without making production changes.

### 2.1 Dual-Mode Operation

#### Mode 1: Quick Status Query
For simple "where is my data?" questions:

| Query Type | Example | Sources Consulted |
|------------|---------|-------------------|
| Load Status | "Is lot ABC123 loaded?" | Exensio raw-sql |
| Processing Stage | "Where is wafer W07?" | ES/Physical logs + pp_log |
| Schema Verification | "Which schema was lot XYZ loaded to?" | Exensio raw-sql (dbschema) |
| Wafer Key Lookup | "What's the wafer key for W07?" | Exensio raw-sql |
| CP Processing Status | "Did CP finish processing?" | ES/Physical logs |
| ETA History | "When was this lot processed?" | pp_log |

#### Mode 2: Full Investigation
For complex failure analysis (detailed in Section 2.2)

### 2.2 Automated Multi-Source Search

The assistant performs a **read-only sweep** across all four primary sources in parallel:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        INVESTIGATION INPUT                                   │
│         Lot | Wafer | Tester | Data Type | Filename | Time Range            │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                      AI INVESTIGATION ORCHESTRATOR                          │
│                                                                              │
│  ┌───────────────────┐  ┌───────────────────┐  ┌───────────────────┐        │
│  │   SEARCH ES       │  │  SEARCH PHYSICAL  │  │  SEARCH pp_log    │        │
│  │   (CP Logs)       │  │  LOG FILES        │  │  (ETA History)    │        │
│  │                   │  │  (Remote Servers) │  │                   │        │
│  │ • idData          │  │                   │  │ • lot             │        │
│  │ • mLot            │  │ • SSH/file access │  │ • idFile          │        │
│  │ • filename        │  │ • lot/wafer       │  │ • filename        │        │
│  │ • @timestamp      │  │ • timestamp       │  │ • process_code    │        │
│  │ • log.level       │  │ • status          │  │ • output_dir      │        │
│  │ • message         │  │ • output path     │  │ • log_message     │        │
│  │ • cpConfig        │  │ • error messages  │  │ • updatedAt       │        │
│  └───────────────────┘  └───────────────────┘  └───────────────────┘        │
│           │                      │                      │                   │
│           └──────────────────────┼──────────────────────┘                   │
│                                  ▼                                          │
│                    ┌─────────────────────────┐                              │
│                    │   SEARCH EXENSIO        │                              │
│                    │   (Raw SQL API)         │                              │
│                    │                         │                              │
│                    │ • lot_id, wafer_id      │                              │
│                    │ • wafer_key, pg_key     │                              │
│                    │ • dbschema (PROD/SANDBX)│                              │
│                    │ • end_time, ppid        │                              │
│                    └─────────────────────────┘                              │
│                                  │                                          │
│                                  ▼                                          │
│                    ┌─────────────────────────┐                              │
│                    │   AI CORRELATION ENGINE  │                              │
│                    │   & TIMELINE BUILDER     │                              │
│                    └─────────────────────────┘                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.3 Data Source Query Strategy

#### Elasticsearch (CP Logs) — CPs Streaming to ES
```
Query by: idData, mLot, filename, @timestamp range, log.level
Returns: CP enrichment status, errors, output paths, processing timestamps
Pattern: Same as exensio-reload ElasticsearchLogService.findCpLog()
Note: Only covers CPs configured to stream logs to ES
```

#### CommandProcessor Physical Log Files — CPs NOT Streaming to ES
```
Access: SSH to remote servers or shared file mounts
Log locations: Different paths per server/CP instance
Format: Physical log files (text-based, varies by CP version)
Query by: lot, wafer, filename, timestamp (grep/parse log entries)
Returns: CP processing status, errors, output paths, timestamps
Challenge: Engineers must know which server has which CP logs
```

**Key Value Add**: The AI assistant maintains a **CP Server Registry** that maps:
- CP Instance → Server IP/hostname → Log file path → Log format
- Eliminates tribal knowledge of "which CP is on which server"

#### refdb.pp_log (ETA Processing History)
```
Query by: lot, idFile, filename, updatedAt range
Returns: process_code (0=success), output_directory, log_message
Database: Oracle PRODUCTION (separate from QA refdb)
```

#### Exensio API (Raw SQL)
```
Query by: lot_id, wafer_id, end_time, pgc_key (data type)
Returns: lot metadata, wafer_key, pg_key, ppid, dbschema
Endpoint: POST /v1/key/raw-sql

Schema Verification:
  - PRODUCTION schema: Data loaded to production environment
  - SANDBOX schema: Data loaded to sandbox/test environment
```

### 2.4 Processing Stage Determination

The assistant determines the current processing stage by correlating across sources:

| Stage | ES/Physical Log | pp_log | Exensio | Status |
|-------|-----------------|--------|---------|--------|
| Not yet received | No entry | No entry | No entry | Waiting for data |
| CP Processing | Entry found, no completion | No entry | No entry | In Progress |
| CP Complete | Success log | No entry | No entry | CP Done |
| ETA Processing | Success log | Entry, process_code pending | No entry | ETA Processing |
| ETA Complete | Success log | process_code=0 | No entry | ETA Done |
| Exensio Loaded | Success log | process_code=0 | wafer_key found | **COMPLETE** |
| Failed | ERROR log | process_code!=0 | No entry | **FAILED** |

### 2.5 Schema Verification

The assistant confirms which Exensio schema data was loaded to:

```sql
-- Exensio raw-sql query for schema verification
SELECT l.lot_id, ol.dbschema, ol.end_time, w.wafer_id, w.wafer_key, p.pg_key
FROM op_log ol
JOIN lot l ON l.lot_key = ol.lot_key
JOIN program p ON p.pg_key = ol.pg_key
LEFT JOIN wf_log wfl ON wfl.lg_key = ol.lg_key
LEFT JOIN wafer w ON w.wf_key = wfl.wf_key
WHERE ol.pgc_key = ?
  AND UPPER(TRIM(l.lot_id)) = ?
  AND UPPER(TRIM(w.wf_id)) = ?
```

**Schema Values:**
- `PRODUCTION` — Data loaded to production environment
- `SANDBOX` — Data loaded to sandbox/test environment
- `UNKNOWN` — Schema not determined

### 2.6 Correlation & Timeline Builder

The assistant correlates events across all sources to build an end-to-end processing timeline:

| Timestamp | Source | Event | Evidence |
|-----------|--------|-------|----------|
| 08:23:15 | Exensio | Lot ABC12345/W07 test end | `end_time` from Exensio raw-sql |
| 08:24:00 | pp_log | ETA processing started | `updatedAt`, `process_code` pending |
| 08:26:30 | ES (CP) | CP enrichment complete | `log.level=INFO`, `output path=PRODUCTION` |
| 08:27:00 | pp_log | ETA processing complete | `process_code=0`, `output_directory` set |
| 08:32:00 | Exensio | Wafer loaded (verified) | `wafer_key`, `pg_key` confirmed present |

### 2.7 Failure Point Detection

When correlation reveals gaps or errors, the assistant identifies the failure point:

| Scenario | Detection Logic | Failure Point |
|----------|----------------|---------------|
| ES shows ERROR, pp_log has no entry | ES: `log.level=ERROR`, pp_log: `lot` not found | CP enrichment failed before ETA |
| Physical log shows ERROR, pp_log has no entry | Remote log: error pattern, pp_log: `lot` not found | CP enrichment failed (non-streaming CP) |
| pp_log shows process_code != 0 | pp_log: `process_code=1`, `log_message` contains error | ETA processing failed |
| ES success, pp_log success, Exensio missing | ES: success, pp_log: `process_code=0`, Exensio: `wafer_key` not found | Exensio load failed |
| Physical log success, pp_log success, Exensio missing | Remote log: success, pp_log: `process_code=0`, Exensio: missing | Exensio load failed (non-streaming CP) |
| All sources show no data | No records in any system for lot/wafer/time | Data never entered pipeline |
| ES timeout (no log after X minutes) | No ES entry within expected window | CP enrichment delayed or stuck |
| Physical log timeout (no entry after X minutes) | No remote log entry within expected window | CP enrichment delayed (non-streaming CP) |

### 2.8 CP Server Registry

A critical component that maps CommandProcessor instances to their physical locations:

| CP Instance | Server | Log Path | Log Format | Streaming to ES |
|-------------|--------|----------|------------|-----------------|
| CP-SiteA-01 | 10.0.1.50 | /var/log/cp/siteA/*.log | Legacy format | No |
| CP-SiteA-02 | 10.0.1.51 | /opt/cp/logs/**/*.log | JSON format | Yes |
| CP-SiteB-01 | 10.0.2.50 | /var/log/commandproc/*.log | Standard format | No |
| CP-SiteC-01 | 10.0.3.50 | /data/cp/logs/*.log | JSON format | Yes |

**Benefits:**
- Engineers no longer need to remember which CP is where
- Assistant automatically routes queries to correct source (ES vs physical log)
- Reduces investigation time by eliminating manual server hunting
- Preserves critical infrastructure knowledge

---

## 3. Investigation Output & Reporting

### 3.1 Quick Status Response

For simple status queries, the assistant provides immediate answers:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        STATUS QUERY RESULT                                   │
│                                                                             │
│  Lot: ABC12345    Wafer: W07    Data Type: WAFER_MAP                        │
│                                                                             │
│  ✓ LOADED INTO EXENSIO                                                      │
│    Schema: PRODUCTION                                                       │
│    Wafer Key: 12345678                                                      │
│    PG Key: 87654321                                                         │
│    Load Time: 2024-01-15 08:32:00 UTC                                       │
│                                                                             │
│  Processing Timeline:                                                       │
│  ├── 08:23:15  Test complete (Exensio end_time)                             │
│  ├── 08:24:00  ETA processing started                                       │
│  ├── 08:26:30  CP enrichment complete (ES log)                              │
│  ├── 08:27:00  ETA processing complete (pp_log)                             │
│  └── 08:32:00  Loaded to Exensio PRODUCTION                                 │
│                                                                             │
│  Evidence:                                                                  │
│  • ES Query: [View in Kibana]                                               │
│  • pp_log: [View Record]                                                    │
│  • Exensio: [View Raw SQL Result]                                           │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Consolidated Processing Timeline (Visual)

For investigations, the assistant generates a visual timeline:

```
Lot ABC12345 / Wafer W07 Processing Timeline
═══════════════════════════════════════════════════════════════════════════════

08:20      08:25      08:30      08:35      08:40      08:45      08:50
  │          │          │          │          │          │          │
  ├──────────┼──────────┼──────────┼──────────┼──────────┼──────────┤
  │          │          │          │          │          │          │
  ▼          ▼          ▼          ▼          │          │          │
[Test]    [ETA       [CP        [Exensio     │          │          │
Complete  Start]     Complete]  Loaded]      │          │          │
  │          │          │          │          │          │          │
  └──────────┴──────────┴──────────┴──────────┘          │          │
              Processing Duration: ~10 min               │          │
                                                        │          │
  ═══════════════════════════════════════════════════════════════════
  Status: ✓ COMPLETE (Loaded to PRODUCTION schema)
```

### 3.3 Detected Errors & Missing Events
- **CP Errors**: `log.level=ERROR` messages from ES with full context
- **ETA Errors**: `process_code != 0` from pp_log with `log_message`
- **Exensio Errors**: Missing wafer_key/pg_key when expected
- **Missing Events**: Expected processing steps with no corresponding log entry

### 3.4 Probable Failure Points with Supporting Evidence
Each failure point includes:
- **Primary cause** with confidence level
- **Supporting evidence** (log entries, record IDs, timestamps)
- **Contributing factors** (network issues, config changes, data quality)

### 3.5 Similar Historical Incidents
- Pattern-matched against known error types
- Links to Jira incidents, SysAid tickets, Confluence docs
- Previous resolution steps that worked

### 3.6 Recommended Troubleshooting Steps
Prioritized actions based on:
- Known error pattern matches
- Similar past incidents
- Current system status

---

## 4. Reports & Visualization

### 4.1 Automated Report Generation

The assistant generates multiple report types:

#### Status Report
```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     DATA PROCESSING STATUS REPORT                           │
│                     Generated: 2024-01-15 10:30:00 UTC                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  SUMMARY                                                                    │
│  ─────────                                                                  │
│  Total Lots Queried: 25                                                     │
│  Loaded to Exensio: 20 (80%)                                                │
│  In Progress: 3 (12%)                                                       │
│  Failed: 2 (8%)                                                             │
│                                                                             │
│  SCHEMA DISTRIBUTION                                                        │
│  ───────────────────                                                        │
│  PRODUCTION: 18 (72%)                                                       │
│  SANDBOX: 2 (8%)                                                            │
│  Not Loaded: 5 (20%)                                                        │
│                                                                             │
│  PROCESSING STAGE BREAKDOWN                                                 │
│  ──────────────────────────                                                 │
│  ████████████████████████████████████████  Not Yet Received (5)             │
│  ██████████████  CP Processing (3)                                          │
│  ████████████████████████████████████████████████████████  Loaded (20)     │
│  ████████  Failed (2)                                                       │
│                                                                             │
│  FAILED LOTS                                                                │
│  ────────────                                                               │
│  • Lot XYZ789: CP enrichment timeout (no ES log after 30 min)               │
│  • Lot DEF456: ETA processing error (process_code=1, log_message="...")     │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Investigation Report
```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     INVESTIGATION REPORT                                     │
│                     Incident: INC-2024-001                                   │
│                     Generated: 2024-01-15 11:00:00 UTC                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  EXECUTIVE SUMMARY                                                          │
│  ────────────────                                                           │
│  Lot ABC12345 wafer W07 failed CP enrichment due to connection timeout      │
│  to ETL server at 08:26:30 UTC. Data was not loaded to Exensio.             │
│                                                                             │
│  TIMELINE                                                                   │
│  ────────                                                                   │
│  08:23:15  Test complete (Exensio end_time)                                 │
│  08:24:00  ETA processing started                                           │
│  08:26:30  CP enrichment timeout (no ES log, no physical log entry)         │
│            ── FAILURE POINT ──                                              │
│                                                                             │
│  ROOT CAUSE                                                                 │
│  ──────────                                                                 │
│  Connection timeout to ETL server (10.0.1.50:8080) during enrichment.       │
│  Network monitoring shows packet loss 08:25-08:30 UTC.                       │
│  Confidence: HIGH                                                           │
│                                                                             │
│  SIMILAR INCIDENTS                                                          │
│  ────────────────                                                           │
│  • INC-2024-001: Same ETL server timeout, resolved by network team          │
│  • INC-2024-042: Similar pattern, fixed by connection pool increase         │
│                                                                             │
│  RECOMMENDED ACTIONS                                                        │
│  ───────────────────                                                        │
│  1. Verify ETL server connectivity (5 min)                                   │
│  2. Check network interface status (10 min)                                  │
│  3. If persistent, engage network team with evidence above                  │
│                                                                             │
│  EVIDENCE                                                                   │
│  ────────                                                                   │
│  • ES Query: [View in Kibana]                                               │
│  • pp_log: No entry found                                                   │
│  • Exensio: wafer_key not found for lot ABC12345/W07                        │
│  • Physical Log: SSH 10.0.1.50, grep "ABC12345" → timeout error             │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 4.2 Visualization Dashboard

The assistant provides visual analytics:

#### Processing Pipeline Visualization
```
Data Flow Through Pipeline (Last 24 Hours)
═══════════════════════════════════════════════════════════════════════════════

Source Systems ──► CP Enrichment ──► ETA Processing ──► Exensio Load
     500              485                480               475
      │                │                  │                 │
      ▼                ▼                  ▼                 ▼
   ┌─────┐         ┌─────┐            ┌─────┐           ┌─────┐
   │ 500 │──────►│ 485 │──────────►│ 480 │─────────►│ 475 │
   └─────┘         └─────┘            └─────┘           └─────┘
      │                │                  │                 │
      │             15 dropped          5 dropped          5 not
      │             (3.0%)              (1.0%)            loaded
      │                │                  │                 │
      ▼                ▼                  ▼                 ▼
   ┌─────────────────────────────────────────────────────────────┐
   │  Success Rate: 95.0%  │  Avg Processing Time: 8.5 min       │
   └─────────────────────────────────────────────────────────────┘
```

#### Schema Distribution Chart
```
Exensio Load Distribution (Current Month)
═══════════════════════════════════════════════════════════════════════════════

PRODUCTION  ████████████████████████████████████████████████████  85% (1,200)
SANDBOX     ████████                                              12% (170)
NOT LOADED  █                                                      3% (42)

Total Lots: 1,412
```

#### Error Trend Analysis
```
Error Rate Trend (Last 7 Days)
═══════════════════════════════════════════════════════════════════════════════

5% │
   │
4% │      ●
   │     ╱ ╲
3% │    ╱   ●────●
   │   ╱         ╲
2% │  ●           ●
   │ ╱             ╲
1% │╱               ●──●
   │
0% └────────────────────────────────────────────────────────────────────────
     Mon   Tue   Wed   Thu   Fri   Sat   Sun

Peak: Wednesday (4.2%) - Network maintenance window
```

#### Site Comparison
```
Processing Volume by Site (Today)
═══════════════════════════════════════════════════════════════════════════════

Site A  ████████████████████████████████████████████████████████  320 lots
Site B  ██████████████████████████████████████████████            250 lots
Site C  ████████████████████████████████████                      180 lots
Site D  ████████████████████████████                              150 lots
Site E  ████████████████████                                      100 lots

Total: 1,000 lots
```

### 4.3 Export Capabilities

| Export Format | Use Case | Content |
|---------------|----------|---------|
| **PDF Report** | Stakeholder distribution | Full investigation report with timeline |
| **CSV** | Data analysis | Raw data from all sources |
| **Jira Ticket** | Incident tracking | Auto-populated investigation findings |
| **Email** | Status updates | Executive summary with key metrics |
| **Confluence** | Documentation | Investigation knowledge base entry |
| **PowerPoint** | Management review | Visual summary with charts |

### 4.4 Scheduled Reports

The assistant can generate recurring reports:

| Report | Frequency | Recipients | Content |
|--------|-----------|------------|---------|
| Daily Processing Summary | Daily | Team | Volume, success rate, failures |
| Weekly Trend Analysis | Weekly | Management | Error trends, site comparisons |
| Monthly SLA Report | Monthly | Stakeholders | SLA compliance, MTTR |
| Incident Digest | After each incident | On-call team | Investigation summary |

---

## 5. Initial MVP Scope

### 5.1 Core MVP Features

| Feature | Description | Data Sources |
|---------|-------------|--------------|
| **Quick Status Query** | Instant answers for "where is my data?" | Exensio raw-sql |
| **Processing Stage Detection** | Determine current stage in pipeline | All four |
| **Schema Verification** | Confirm PRODUCTION vs SANDBOX load | Exensio raw-sql |
| **Multi-source search** | Single query across all sources | All four |
| **Timeline builder** | Correlated end-to-end processing view | All four |
| **Failure point detection** | Identify where in pipeline failure occurred | All four |
| **Error pattern matching** | Match against known error patterns | ES + Physical Logs + pp_log |
| **Visual Timeline** | Graphical processing timeline | All four |
| **Status Report** | Automated status summary | All four |
| **Investigation Report** | Auto-generated draft report | All four |
| **CP Server Registry** | Auto-route to ES or physical logs | Infrastructure |

### 5.2 MVP Investigation Flow

```
Engineer Input: Lot ABC12345, Wafer W07, Date: 2024-01-15
        │
        ▼
┌─────────────────────────────────────────────────────────────┐
│  1. DETERMINE CP LOCATION                                   │
│     • Check CP Server Registry for lot/wafer/site            │
│     • Is CP streaming to ES?                                 │
│     • If yes → query ES                                      │
│     • If no → SSH to remote server for physical logs         │
└─────────────────────────────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────────────────────────────┐
│  2. PARALLEL SEARCH ACROSS ALL SOURCES                      │
│                                                             │
│     ┌─────────────────┐  ┌─────────────────┐               │
│     │  SEARCH ES      │  │  SEARCH PHYSICAL│               │
│     │  (if streaming) │  │  (if not stream)│               │
│     │                 │  │  SSH to server  │               │
│     │  idData, mLot   │  │  grep lot/wafer │               │
│     │  filename, time │  │  parse log fmt  │               │
│     └─────────────────┘  └─────────────────┘               │
│                                                             │
│     ┌─────────────────┐  ┌─────────────────┐               │
│     │  SEARCH pp_log  │  │  SEARCH EXENSIO │               │
│     │                 │  │                 │               │
│     │  lot, idFile    │  │  lot_id, wafer  │               │
│     │  process_code   │  │  wafer_key      │               │
│     │                 │  │  dbschema       │               │
│     └─────────────────┘  └─────────────────┘               │
└─────────────────────────────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────────────────────────────┐
│  3. CORRELATE & BUILD TIMELINE                              │
│     • Match records by lot, wafer, filename, timestamp       │
│     • Determine processing stage                             │
│     • Identify schema (PRODUCTION/SANDBOX)                   │
│     • Detect errors and gaps                                 │
└─────────────────────────────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────────────────────────────┐
│  4. GENERATE OUTPUT                                         │
│     • Visual processing timeline                             │
│     • Status confirmation (Loaded/In Progress/Failed)        │
│     • Schema verification result                             │
│     • Failure analysis (if applicable)                       │
│     • Recommended actions                                    │
│     • Exportable report                                      │
└─────────────────────────────────────────────────────────────┘
```

### 5.3 What MVP Does NOT Do (Future Phases)

| Feature | Phase | Description |
|---------|-------|-------------|
| Natural language queries | Phase 2 | "Why is lot ABC12345 failing?" |
| Predictive failure detection | Phase 3 | Identify issues before users report |
| Jira/SysAid auto-creation | Phase 3 | Auto-create tickets from findings |
| Confluence integration | Phase 2 | Search documentation automatically |
| Voice interface | Phase 4 | Voice-activated investigation |
| Real-time monitoring | Phase 3 | Proactive alerting on pipeline issues |
| Advanced analytics | Phase 3 | ML-based pattern recognition |
| Mobile app | Phase 4 | Status queries on mobile |

---

## 6. Expected Value

### 6.1 Time Savings

| Activity | Current | With AI Assistant | Improvement |
|----------|---------|-------------------|-------------|
| **Status Queries** | | | |
| Check if lot loaded | 5-10 min | 5-10 sec | **95%** |
| Verify schema | 5-10 min | 5-10 sec | **95%** |
| Find processing stage | 10-15 min | 10-15 sec | **95%** |
| **Investigation** | | | |
| Identify which CP/has logs | 5-15 min | Instant | **99%** |
| Search ES for CP logs | 15-30 min | 1-2 min | **95%** |
| SSH + grep physical logs | 20-40 min | 1-2 min | **95%** |
| Query pp_log for ETA history | 15-30 min | 1-2 min | **95%** |
| Query Exensio for metadata | 10-15 min | 1-2 min | **90%** |
| Correlate across sources | 30-60 min | 2-5 min | **95%** |
| Write investigation summary | 30-45 min | 2-5 min | **90%** |
| Generate report | 30-60 min | 1-2 min | **95%** |
| **Total per incident** | **2-4 hours** | **10-20 min** | **92%** |
| **Total per status query** | **15-30 min** | **15-30 sec** | **95%** |

### 6.2 Quality Improvements

- **Consistency**: Same investigation methodology regardless of engineer experience
- **Completeness**: Automated sweep across all sources — no missed systems
- **Knowledge Preservation**: Investigation patterns stored and reusable
- **Onboarding**: New engineers guided through systematic investigation
- **Audit Trail**: Complete investigation history with evidence links
- **Reporting**: Professional, consistent reports for stakeholders
- **Visualization**: Clear understanding of pipeline status and issues

### 6.3 Operational Benefits

- **Faster MTTR**: Mean Time to Resolve reduced through rapid diagnosis
- **Reduced Escalation**: Junior engineers can investigate with AI guidance
- **Stakeholder Communication**: Professional, consistent status updates
- **Pattern Recognition**: Identify recurring issues across lots/sites/systems
- **Proactive Detection**: Spot trends before they become incidents
- **Data-Driven Decisions**: Visual analytics for management

---

## 7. Technical Architecture

### 7.1 System Architecture

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                              USER INTERFACE                                    │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │                     QUERY INPUT                                          │ │
│  │   [Status Query] [Investigation] [Report] [Dashboard]                   │ │
│  │   Lot | Wafer | Tester | Data Type | Filename | Date Range | Submit     │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                      │                                       │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │                     RESULTS & VISUALIZATION                              │ │
│  │   ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  │ │
│  │   │  Timeline   │  │  Status     │  │  Reports    │  │  Analytics  │  │ │
│  │   │  Viewer     │  │  Summary    │  │  & Export   │  │  Dashboard  │  │ │
│  │   └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘  │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                           AI SUPPORT ASSISTANT                                │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │                      INVESTIGATION ORCHESTRATOR                          │ │
│  │                                                                          │ │
│  │  1. Parse query (status vs investigation)                                │ │
│  │  2. Build queries for each data source                                   │ │
│  │  3. Execute parallel searches                                            │ │
│  │  4. Correlate results by lot/wafer/timestamp                             │ │
│  │  5. Determine processing stage and schema                                │ │
│  │  6. Build unified processing timeline                                    │ │
│  │  7. Detect failures and gaps                                             │ │
│  │  8. Generate visualizations and reports                                  │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                       │                                       │
│         ┌─────────────────────────────┼─────────────────────────────┐        │
│         ▼                             ▼                             ▼        │
│  ┌──────────────┐           ┌──────────────┐           ┌──────────────┐      │
│  │  ES SEARCH   │           │  pp_log      │           │  EXENSIO     │      │
│  │  SERVICE     │           │  SERVICE     │           │  SERVICE     │      │
│  │              │           │              │           │              │      │
│  │ Query:       │           │ Query:       │           │ Query:       │      │
│  │ idData       │           │ lot          │           │ lot_id       │      │
│  │ mLot         │           │ idFile       │           │ wafer_id     │      │
│  │ filename     │           │ filename     │           │ dbschema     │      │
│  │ @timestamp   │           │ updatedAt    │           │ wafer_key    │      │
│  │ log.level    │           │ process_code │           │ pg_key       │      │
│  └──────────────┘           └──────────────┘           └──────────────┘      │
│         │                             │                             │        │
│         ▼                             ▼                             ▼        │
│  ┌──────────────┐           ┌──────────────┐           ┌──────────────┐      │
│  │Elasticsearch │           │Oracle        │           │Exensio API   │      │
│  │(CP Logs)     │           │(pp_log)      │           │(Raw SQL)     │      │
│  └──────────────┘           └──────────────┘           └──────────────┘      │
│                                                                              │
│  ┌──────────────┐           ┌──────────────┐           ┌──────────────┐      │
│  │  PHYSICAL    │           │  REPORT      │           │  VISUALIZATION│     │
│  │  LOG SERVICE │           │  GENERATOR   │           │  ENGINE      │      │
│  │              │           │              │           │              │      │
│  │ SSH to       │           │ PDF          │           │ Timeline     │      │
│  │ remote       │           │ CSV          │           │ Charts       │      │
│  │ servers      │           │ Jira         │           │ Dashboards   │      │
│  │ grep/parse   │           │ Email        │           │ Export       │      │
│  └──────────────┘           └──────────────┘           └──────────────┘      │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

### 7.2 Data Source Integration

#### Elasticsearch (CP Logs) — CPs Streaming to ES
```json
{
  "query": {
    "bool": {
      "must": [
        { "term": { "idData": "<data_id>" } },
        { "range": { "@timestamp": { "gte": "<start>", "lte": "<end>" } } }
      ],
      "should": [
        { "term": { "mLot": "<lot>" } },
        { "wildcard": { "inputFileName": "*<filename>*" } }
      ]
    }
  }
}
```

#### CommandProcessor Physical Log Files — CPs NOT Streaming to ES
```
Access Method: SSH (JSch) or shared file mount
Query Approach: 
  1. Determine CP instance from Server Registry
  2. SSH to target server
  3. grep/awk for lot/wafer/filename patterns in log files
  4. Parse log entries based on format (legacy/JSON/standard)

Example SSH grep:
  grep -h "ABC12345" /var/log/cp/siteA/*.log | grep "W07"
  grep -h "ABC12345" /var/log/cp/siteA/*.log | grep "filename.klarf"

Log Format Examples:
  Legacy:  2024-01-15 08:26:30 [INFO] Lot=ABC12345 Wafer=W07 Status=Success
  JSON:    {"timestamp":"2024-01-15T08:26:30Z","lot":"ABC12345","status":"success"}
  Standard: 08:26:30 INFO  CPProcessor - Lot ABC12345 processed successfully
```

**Key Challenge**: Log formats vary across CP instances and versions. The assistant must maintain a format parser per CP instance.

#### refdb.pp_log (Oracle)
```sql
SELECT lot, idFile, filename, process_code, output_directory, 
       log_message, updatedAt
FROM pp_log
WHERE lot = ?
  AND updatedAt BETWEEN ? AND ?
ORDER BY updatedAt
```

#### Exensio API (Raw SQL)
```sql
SELECT lot_id, end_time, ppid, wafer_id, wafer_key, pg_key, dbschema
FROM op_log ol
JOIN lot l ON l.lot_key = ol.lot_key
JOIN program p ON p.pg_key = ol.pg_key
LEFT JOIN wf_log wfl ON wfl.lg_key = ol.lg_key
LEFT JOIN wafer w ON w.wf_key = wfl.wf_key
WHERE ol.pgc_key = ?
  AND UPPER(TRIM(l.lot_id)) = ?
  AND UPPER(TRIM(w.wf_id)) = ?
```

### 7.3 AI/ML Components

| Component | Purpose | Technology |
|-----------|---------|------------|
| **Query Generator** | Convert input to source-specific queries | Rule-based + LLM |
| **Correlation Engine** | Match records across sources by lot/wafer/timestamp | Rule-based |
| **Stage Determiner** | Determine processing stage from correlated data | Rule-based |
| **Schema Verifier** | Confirm PRODUCTION vs SANDBOX load | Rule-based |
| **Timeline Builder** | Construct unified processing timeline | Rule-based |
| **Failure Detector** | Identify gaps, errors, anomalies in timeline | Rule-based + Pattern matching |
| **Root Cause Analyzer** | Determine probable cause from error patterns | LLM (Claude/GPT) |
| **Recommendation Engine** | Suggest troubleshooting steps based on patterns | LLM + Knowledge base |
| **Report Generator** | Create investigation/status report draft | LLM |
| **Visualization Engine** | Generate charts, timelines, dashboards | Chart.js/D3.js |

---

## 8. Technical Feasibility & Scaling Assessment

### 8.1 Existing Infrastructure Capacity

The exensio-reload application already has substantial infrastructure in place:

#### Backend (Spring Boot) - Existing Capabilities
| Component | Current State | Reuse Potential |
|-----------|---------------|-----------------|
| **AI Services Layer** | 25+ services (AiGatewayService, RootCauseAnalysisService, NL Search, etc.) | ✅ Extend existing services |
| **Elasticsearch Integration** | `ElasticsearchLogService` with circuit breaker | ✅ Direct reuse |
| **Exensio API Integration** | `ExensioRawSqlService` with OAuth/SAML auth | ✅ Direct reuse |
| **Database Connections** | Oracle, PostgreSQL, Snowflake, H2 datasources | ✅ Add new connection pools |
| **Security** | OAuth/SAML/LDAP authentication | ✅ No changes needed |
| **Real-Time Updates** | SSE (Server-Sent Events) | ✅ Extend for investigation status |
| **SSH Support** | JSch already in pom.xml | ✅ Ready for physical log access |
| **Email** | spring-boot-starter-mail in pom.xml | ✅ Ready for notifications |
| **Caching** | Caffeine cache | ✅ Ready for query caching |

#### Frontend (Angular) - Existing Capabilities
| Component | Current State | Reuse Potential |
|-----------|---------------|-----------------|
| **UI Framework** | Angular with component library | ✅ Extend with new components |
| **Real-Time Updates** | SSE integration | ✅ For live investigation progress |
| **Data Tables** | Existing table components | ✅ For results display |
| **Forms** | Reactive forms | ✅ For query input |

### 8.2 Feature Feasibility Matrix

| Feature | Feasibility | Effort | Dependencies |
|---------|-------------|--------|--------------|
| **Status Query API** | ✅ Easy | Low | Exensio raw-sql service |
| **Schema Verification** | ✅ Easy | Low | Exensio raw-sql service |
| **Processing Stage Detection** | ✅ Easy | Low | Correlation engine |
| **Investigation Orchestrator** | ✅ Easy | Medium | Parallel query execution |
| **CP Server Registry** | ✅ Easy | Low | New database table |
| **Physical Log File Access (SSH)** | ⚠️ Moderate | Medium | JSch service, SSH credentials |
| **Visual Timeline** | ⚠️ Moderate | Medium | Chart.js/D3.js library |
| **PDF/CSV Export** | ✅ Easy | Low | Apache POI / OpenPDF |
| **Scheduled Reports** | ⚠️ Moderate | Medium | Spring Scheduler / Quartz |
| **Email Notifications** | ✅ Easy | Low | spring-boot-starter-mail |
| **Jira/Confluence Integration** | ⚠️ Moderate | High | REST API clients, credentials |
| **Natural Language Queries** | ✅ Easy | Low | Extend existing NL Search service |
| **Advanced Analytics** | ⚠️ Moderate | High | ML pipeline, historical data |

### 8.3 Scaling Recommendations

#### MVP Scope (8-12 weeks) - Recommended
Features that can be delivered with **existing infrastructure**:

| Feature | Implementation |
|---------|----------------|
| Status Query | New API endpoints + Angular components |
| Schema Verification | Extend `ExensioRawSqlService` |
| Processing Stage Detection | New correlation service |
| Investigation Orchestrator | New orchestration service |
| Basic Timeline Visualization | New Angular component + Chart.js |
| Simple PDF/CSV Export | Add Apache POI dependency |
| CP Server Registry | New database table + CRUD API |

#### Phase 2 Scope (Additional 4-6 weeks)
Features requiring **moderate new infrastructure**:

| Feature | Implementation |
|---------|----------------|
| SSH Physical Log Access | New `PhysicalLogService` using JSch |
| Advanced Visualizations | Dashboard components + D3.js |
| Scheduled Reports | Spring Scheduler configuration |
| Email Notifications | Configure `JavaMailSender` |
| Investigation History | New database schema |

#### Phase 3 Scope (Additional 4-6 weeks)
Features requiring **external integrations**:

| Feature | Implementation |
|---------|----------------|
| Jira Integration | Jira REST API client |
| Confluence Integration | Confluence REST API client |
| Natural Language Queries | Enhance existing `NaturalLanguageSearchService` |
| Advanced Analytics | ML pipeline for pattern recognition |

### 8.4 Risk Assessment

| Risk | Impact | Mitigation |
|------|--------|------------|
| SSH access to production servers | High | Read-only access, audit logging, engineer approval |
| Log format variations | Medium | Configurable parsers per CP instance |
| External API rate limits | Medium | Caching, request throttling |
| Data sensitivity | High | Existing security model, no data retention |
| Performance with large log files | Medium | Streaming reads, pagination, time-boxed queries |

### 8.5 Conclusion

**The exensio-reload fullstack application can support the proposed AI Support Assistant functionality.** 

The existing infrastructure provides:
- ✅ **25+ AI services** ready for extension
- ✅ **Elasticsearch integration** with proven query patterns
- ✅ **Exensio API integration** with authentication
- ✅ **Multiple database connections** (Oracle, PostgreSQL, Snowflake)
- ✅ **SSH capability** (JSch already included)
- ✅ **Email capability** (spring-boot-starter-mail included)
- ✅ **Security framework** (OAuth/SAML/LDAP)
- ✅ **Real-time updates** (SSE)

**MVP delivery in 8-12 weeks is achievable** by leveraging existing services and adding targeted new components (correlation engine, SSH service, charting library). Advanced features (Jira/Confluence, ML analytics) can be added in subsequent phases without architectural changes.

---

## 9. Implementation Roadmap

### Phase 1: Foundation (Weeks 1-4)
- [ ] Build Investigation Orchestrator service
- [ ] Implement ES query service (based on exensio-reload `ElasticsearchLogService`)
- [ ] **Build CP Server Registry** (CP instance → server → log path → format)
- [ ] **Implement SSH-based physical log file query service** (JSch)
- [ ] Implement pp_log query service
- [ ] Implement Exensio raw-sql query service (based on exensio-reload `ExensioRawSqlService`)
- [ ] Build correlation engine
- [ ] Create processing stage determiner
- [ ] Create schema verifier
- [ ] Create timeline builder
- [ ] Basic UI for input and results display

### Phase 2: Intelligence & Reporting (Weeks 5-8)
- [ ] Implement failure point detection
- [ ] Build error pattern matching (known patterns from tribal knowledge)
- [ ] Integrate LLM for root cause analysis
- [ ] Build recommendation engine
- [ ] Create investigation summary generator
- [ ] Build status report generator
- [ ] Build visual timeline component
- [ ] Add evidence linking (ES record, pp_log record, Exensio record)
- [ ] Implement export capabilities (PDF, CSV, Jira)

### Phase 3: Visualization & Knowledge (Weeks 9-12)
- [ ] Build analytics dashboard
- [ ] Create processing pipeline visualization
- [ ] Create schema distribution charts
- [ ] Create error trend analysis
- [ ] Create site comparison views
- [ ] Build investigation history storage
- [ ] Implement similar incident search
- [ ] Add Jira/SysAid integration for incident lookup
- [ ] Create Confluence documentation search
- [ ] Build knowledge base from past investigations
- [ ] Implement scheduled reports

### Phase 4: Advanced (Weeks 13-16)
- [ ] Natural language query interface
- [ ] Predictive failure detection
- [ ] Cross-site pattern analysis
- [ ] Automated stakeholder notifications
- [ ] Advanced analytics and ML-based insights
- [ ] Mobile-responsive interface

---

## 9. Scope & Constraints

### 9.1 Read-Only Operation
The assistant will be **read-only** — it will NOT:
- Modify production data in any system
- Execute reloads or reprocessing
- Change system configurations
- Submit tickets or send communications without engineer approval

### 9.2 Engineer Review Required
All AI-generated findings will be clearly labeled:
- **AI-Generated Finding** — requires engineer validation
- **Evidence** — direct links to source records
- **Confidence Level** — HIGH / MEDIUM / LOW based on data quality

### 9.3 Data Governance
- Access controlled by existing security (OAuth/SAML/LDAP)
- Investigation queries logged for audit
- No sensitive data stored beyond investigation session
- Complies with existing data retention policies

---

## 10. Success Metrics

| Metric | Target | Measurement |
|--------|--------|-------------|
| Status query time reduction | 95% | Avg time before vs after |
| Investigation time reduction | 90% | Avg time before vs after |
| First-time diagnosis accuracy | 80% | Engineer agrees with AI finding |
| Coverage of data sources | 100% | All 4 sources searched automatically |
| Engineer adoption | 90% | % queries using assistant |
| Knowledge base growth | 50+/month | New patterns documented |
| Report generation time | 95% | Time to generate status/investigation report |
| Stakeholder satisfaction | 85% | Survey on report quality |

---

## 11. Conclusion

The AI-Powered Exensio Data Integration Support Assistant addresses the core pain points:

1. **Time**: Reduces 2-4 hour investigations to 10-20 minutes, 15-30 min status queries to seconds
2. **Consistency**: Systematic methodology regardless of engineer experience
3. **Completeness**: Automated sweep across ES, physical logs, pp_log, and Exensio
4. **Status Visibility**: Instant answers for "where is my data?" and "which schema?"
5. **Knowledge**: Preserves investigation expertise for the team
6. **Reporting**: Auto-generated professional reports for stakeholders
7. **Visualization**: Clear understanding of pipeline status and issues

By building on proven query patterns from the exensio-reload application and focusing on the four primary data sources (Elasticsearch, physical logs, pp_log, Exensio API), we can deliver a high-value tool that augments engineer judgment without making production changes.

---

*Prepared for Exensio Data Integration Team*
*Target MVP Delivery: 8-12 weeks*
