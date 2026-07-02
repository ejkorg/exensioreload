# Design Document: Enrichment Fallback Chain

## Overview

Adds per-source availability guards so `CpLogMonitor` and `StagePipelinePolicy` function correctly whether a site has Elasticsearch, `pp_log`, both, or neither. The core insight is that `StageCompletionMonitor.ELASTICSEARCH` is a misnomer in practice — it really means "hand to `CpLogMonitor`", which already queries both ES and pp_log in parallel. So we only need to fix two things:

1. `StagePipelinePolicy` must route to `CpLogMonitor` whenever *any* enrichment source is available (not just when ES is configured).
2. `CpLogMonitor` must run its poll cycle when *any* enrichment source is available, and must skip individual source futures when that source is disabled.

No new infrastructure, no new state machines — just targeted guard conditions.

## Architecture

The existing 3-tier fallback chain is preserved and extended:

```
CP Queue Consumed
      │
      ▼
StagePipelinePolicy.afterCpQueueConsumption()
      │
      ├─ ES configured OR pp_log enabled  ──► ENRICHMENT status → CpLogMonitor poll
      │                                            │
      │                                     ┌─────┴──────┐
      │                                     ▼            ▼
      │                                  ES query    pp_log query  (parallel, each skipped if disabled)
      │                                     └─────┬──────┘
      │                                           │ consolidate (pp_log wins on success)
      │                                           │ on timeout → Exensio direct lookup
      │
      ├─ Neither ES nor pp_log, Exensio enabled  ──► EXENSIO_LOADING status
      │
      └─ Nothing configured  ──► DONE immediately
```

## Components and Interfaces

### PpLogDbProperties (modified)

Add `enabled` boolean field:

```java
@ConfigurationProperties(prefix = "refdb.pplog")
public class PpLogDbProperties {
    private boolean enabled = true;  // NEW

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    // isConfigured() unchanged — still checks host != blank
    // new helper: isPpLogAvailable() = isEnabled() && isConfigured()
    public boolean isPpLogAvailable() {
        return enabled && isConfigured();
    }
}
```

`isConfigured()` remains as-is (host != blank). `isPpLogAvailable()` = enabled AND host configured. The new flag lets operators disable `pp_log` at sites where the table doesn't exist, without needing to leave `host` blank (they may still want the same DB for other queries).

### StagePipelinePolicy (modified)

Inject `PpLogDbProperties` and update the routing decision:

```java
public StageCompletionMonitor afterCpQueueConsumption() {
    // Route to CpLogMonitor if any enrichment source can resolve the record
    if (esProperties.isConfigured() || ppLogDbProperties.isPpLogAvailable()) {
        return StageCompletionMonitor.ELASTICSEARCH;
    }
    if (exensioProperties.isConfigured()) {
        return StageCompletionMonitor.EXENSIO_API;
    }
    return StageCompletionMonitor.NONE;
}

public boolean isPpLogEnabled() {
    return ppLogDbProperties.isPpLogAvailable();
}
```

### CpLogMonitor (modified)

Inject `PpLogDbProperties`. Update the poll-cycle guard and the per-record source selection:

```java
// Guard: run if either source available
boolean hasEs = props.isConfigured();
boolean hasPpLog = ppLogDbProperties.isPpLogAvailable();
if (!hasEs && !hasPpLog) {
    log.debug("Neither ES nor pp_log available — CP log polling disabled");
    return;
}

// Per-record: skip ES future when not configured
CompletableFuture<CpLogResult> esFuture = hasEs
    ? CompletableFuture.supplyAsync(() -> elasticsearchLogService.findCpLog(...))
    : CompletableFuture.completedFuture(new CpLogResult.NotFound("es-not-configured"));

// Per-record: skip pp_log future when not available
CompletableFuture<PpLogResult> ppLogFuture = hasPpLog
    ? CompletableFuture.supplyAsync(() -> { /* existing query */ })
    : CompletableFuture.completedFuture(new PpLogResult.NotFound());
```

## Data Models

No schema changes. No new tables. The `SENDER_STAGE.status` lifecycle is unchanged — `ENRICHMENT` still feeds `CpLogMonitor`.

Configuration additions in `application.yml`:

```yaml
refdb:
  pplog:
    enabled: true  # set to false at sites without pp_log table
```

## Correctness Properties

A property is a characteristic or behavior that should hold true across all valid executions — a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.

### Property 1: StagePipelinePolicy routes to CpLogMonitor when pp_log is the only source

*For any* configuration where ES is not configured (`url` blank) and pp_log is available (`enabled=true`, `host` set), `afterCpQueueConsumption()` must return `StageCompletionMonitor.ELASTICSEARCH`.

**Validates: Requirements 3.1**

### Property 2: StagePipelinePolicy never routes to CpLogMonitor when both ES and pp_log are unavailable

*For any* configuration where `esConfigured=false` and `ppLogAvailable=false`, `afterCpQueueConsumption()` must NOT return `StageCompletionMonitor.ELASTICSEARCH`.

**Validates: Requirements 3.2, 3.3**

### Property 3: CpLogMonitor does not query pp_log when disabled

*For any* `StageRecord`, when `refdb.pplog.enabled=false`, the pp_log query path must not be invoked — the future must complete immediately with `PpLogResult.NotFound`.

**Validates: Requirements 1.2, 2.4**

### Property 4: PpLogDbProperties.isEnabled() defaults to true

For a freshly constructed `PpLogDbProperties` with no properties set, `isEnabled()` returns `true`.

**Validates: Requirements 1.1, 1.4**

## Error Handling

- `queryPpLogSuccess` / `queryPpLogError` already catch `SQLException` and return `null` (treated as NotFound). No change.
- When `isPpLogAvailable()` is false, the query is never attempted — no DB call, no exception, no warning log.
- When ES is not configured and the ES future is short-circuited to `NotFound`, the existing consolidation logic proceeds normally — pp_log result drives the outcome.

## Testing Strategy

**Unit tests (examples)**:
- `PpLogDbProperties`: default `enabled=true`, `isEnabled()` returns false when set to false, `isPpLogAvailable()` only true when both enabled and host set.
- `StagePipelinePolicy`: all four combinations of (esConfigured, ppLogAvailable) vs expected `StageCompletionMonitor` return value.
- `CpLogMonitor.monitorEnrichmentRecords()`: verify returns immediately when both ES and pp_log unavailable; verify proceeds when only pp_log available.

**Property-based tests**:
- Property 1 and 2 in `StagePipelinePolicyTest`: generate random valid/invalid ES URLs and pp_log host strings, assert routing outcome matches expected invariant.
- Property 3 in `CpLogMonitorTest`: generate random `StageRecord` instances, assert pp_log future is never invoked when disabled.

**Framework**: JUnit 5 + Mockito (already used in the project). No new test dependencies needed.

Each property test must run minimum 100 iterations.
Tag format: `Feature: enrichment-fallback-chain, Property {N}: {property_text}`
