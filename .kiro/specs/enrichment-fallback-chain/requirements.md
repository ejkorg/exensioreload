# Requirements Document

## Introduction

Not all production sites/locations have Elasticsearch deployed. Not all CP processes insert enrichment logs into `refdb.pp_log`. The current pipeline short-circuits to a no-op when ES is not configured, meaning sites that only have `pp_log` (but no ES) never get their enrichment records resolved automatically. This feature adds per-source availability guards so each source (ES, pp_log) can be independently enabled/disabled, and the `CpLogMonitor` runs whenever *any* enrichment source is available.

## Glossary

- **CpLogMonitor**: Scheduled service that polls enrichment sources and drives `SENDER_STAGE` status transitions for records in `ENRICHMENT` status.
- **StagePipelinePolicy**: Capability-based router that decides which monitor to hand a record to after CP queue consumption.
- **pp_log**: Oracle table written by certain CP processes containing enrichment outcome per lot/file.
- **PpLogDbProperties**: Spring configuration properties bound from `refdb.pplog.*`.
- **CpElasticsearchProperties**: Spring configuration properties bound from `cp.elasticsearch.*`.
- **ENRICHMENT**: `SENDER_STAGE` status indicating CP has consumed the payload and enrichment outcome is being awaited.
- **StageCompletionMonitor**: Enum (`ELASTICSEARCH`, `EXENSIO_API`, `NONE`) returned by `StagePipelinePolicy` to route records after CP queue consumption.

## Requirements

### Requirement 1: pp_log Availability Guard

**User Story:** As a system operator at a site without `pp_log`, I want to disable `pp_log` queries explicitly, so that the system does not emit misleading warnings or attempt unnecessary DB connections.

#### Acceptance Criteria

1. THE `PpLogDbProperties` SHALL expose an `enabled` flag (default `true`) that operators can set to `false` in their profile YAML.
2. WHEN `refdb.pplog.enabled` is `false`, THE `CpLogMonitor` SHALL skip the `pp_log` parallel query entirely and treat it as `NotFound`.
3. WHEN `refdb.pplog.enabled` is `false`, THE `CpLogMonitor` SHALL NOT emit a warning log about a failed `pp_log` query.
4. THE `PpLogDbProperties.isEnabled()` method SHALL return `true` by default when the property is not set.

### Requirement 2: CpLogMonitor Runs When Any Source Is Available

**User Story:** As a system operator at a site with only `pp_log` (no Elasticsearch), I want enrichment records to be resolved automatically, so that records do not remain stuck in `ENRICHMENT` status indefinitely.

#### Acceptance Criteria

1. WHEN `cp.elasticsearch.url` is blank AND `refdb.pplog.enabled` is `true`, THE `CpLogMonitor` SHALL still execute its poll cycle for `ENRICHMENT` records.
2. WHEN `cp.elasticsearch.url` is blank AND `refdb.pplog.enabled` is `false`, THE `CpLogMonitor` SHALL skip its poll cycle (nothing to check).
3. WHEN `cp.elasticsearch.url` is configured AND `refdb.pplog.enabled` is `true`, THE `CpLogMonitor` SHALL query both sources in parallel (existing behaviour, unchanged).
4. WHEN `cp.elasticsearch.url` is configured AND `refdb.pplog.enabled` is `false`, THE `CpLogMonitor` SHALL query ES only and skip the `pp_log` future.

### Requirement 3: StagePipelinePolicy Routes to ENRICHMENT When pp_log Is Available

**User Story:** As a system operator at a site with only `pp_log`, I want records to be routed to `ENRICHMENT` status after CP queue consumption, so that `CpLogMonitor` can pick them up and resolve them.

#### Acceptance Criteria

1. WHEN `cp.elasticsearch.url` is blank AND `refdb.pplog.enabled` is `true`, THE `StagePipelinePolicy.afterCpQueueConsumption()` SHALL return `StageCompletionMonitor.ELASTICSEARCH` (which routes records through `CpLogMonitor`).
2. WHEN `cp.elasticsearch.url` is blank AND `refdb.pplog.enabled` is `false` AND `exensio.enabled` is `true`, THE `StagePipelinePolicy.afterCpQueueConsumption()` SHALL return `StageCompletionMonitor.EXENSIO_API`.
3. WHEN `cp.elasticsearch.url` is blank AND `refdb.pplog.enabled` is `false` AND `exensio.enabled` is `false`, THE `StagePipelinePolicy.afterCpQueueConsumption()` SHALL return `StageCompletionMonitor.NONE`.
4. THE `StagePipelinePolicy` SHALL expose an `isPpLogEnabled()` method for use in routing decisions and diagnostics.

### Requirement 4: Diagnostic Logging

**User Story:** As a developer debugging a site, I want the startup and poll-cycle logs to clearly state which enrichment sources are active, so that I can quickly understand the configured fallback chain.

#### Acceptance Criteria

1. WHEN the application starts, THE `CpLogMonitor` SHALL log which enrichment sources are active (ES, pp_log, or neither) at INFO level.
2. WHEN `CpLogMonitor` skips the poll cycle because no sources are configured, THE `CpLogMonitor` SHALL log a DEBUG message stating why.
3. WHEN `CpLogMonitor` runs with only pp_log active (no ES), THE `CpLogMonitor` SHALL log a DEBUG message per record indicating ES was skipped.
