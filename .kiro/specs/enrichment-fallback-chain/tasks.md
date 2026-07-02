# Implementation Plan: Enrichment Fallback Chain

## Overview

Targeted changes to three classes — `PpLogDbProperties`, `StagePipelinePolicy`, and `CpLogMonitor` — to ensure enrichment records are resolved at sites that have only `pp_log`, only ES, both, or neither. No schema changes. No new dependencies.

## Tasks

- [x] 1. Add `enabled` flag to `PpLogDbProperties`
  - Add `private boolean enabled = true` field with getter/setter
  - Add `isPpLogAvailable()` helper: returns `enabled && isConfigured()`
  - _Requirements: 1.1, 1.4_

- [ ]\* 1.1 Write unit tests for PpLogDbProperties
  - Test default `enabled=true`
  - Test `isPpLogAvailable()` is false when enabled=false even if host is set
  - Test `isPpLogAvailable()` is false when host is blank even if enabled=true
  - **Property 4: PpLogDbProperties.isEnabled() defaults to true**
  - **Validates: Requirements 1.1, 1.4**

- [x] 2. Update `StagePipelinePolicy` to use pp_log availability in routing
  - Inject `PpLogDbProperties` via constructor
  - Update `afterCpQueueConsumption()`: return `ELASTICSEARCH` when `esProperties.isConfigured() || ppLogDbProperties.isPpLogAvailable()`
  - Add `isPpLogEnabled()` method delegating to `ppLogDbProperties.isPpLogAvailable()`
  - _Requirements: 3.1, 3.2, 3.3, 3.4_

- [ ]\* 2.1 Write property test for StagePipelinePolicy routing
  - **Property 1: StagePipelinePolicy routes to CpLogMonitor when pp_log is the only source**
  - **Property 2: StagePipelinePolicy never routes to CpLogMonitor when both ES and pp_log are unavailable**
  - **Validates: Requirements 3.1, 3.2, 3.3**

- [x] 3. Update `CpLogMonitor` to guard on any-source availability
  - Inject `PpLogDbProperties` via constructor
  - Replace `if (!props.isConfigured()) return` guard with `if (!hasEs && !hasPpLog) return`
  - Short-circuit ES future to `CpLogResult.NotFound("es-not-configured")` when ES not configured
  - Short-circuit pp_log future to `PpLogResult.NotFound()` when `isPpLogAvailable()` is false
  - Add startup `@PostConstruct` log at INFO level listing active sources
  - _Requirements: 1.2, 1.3, 2.1, 2.2, 2.3, 2.4, 4.1, 4.2, 4.3_

- [ ]\* 3.1 Write unit/property tests for CpLogMonitor source selection
  - **Property 3: CpLogMonitor does not query pp_log when disabled**
  - Test: poll cycle proceeds when only pp_log available (ES blank)
  - Test: poll cycle skips when both sources unavailable
  - **Validates: Requirements 1.2, 2.1, 2.2, 2.4**

- [ ] 4. Checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.
