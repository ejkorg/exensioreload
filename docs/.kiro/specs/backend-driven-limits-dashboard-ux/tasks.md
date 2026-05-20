# Implementation Plan: Backend-Driven Limits + Operator-First Dashboard UX

## Overview

Implement backend-authoritative limit resolution, demote environment values to fallbacks, and improve operator-facing dashboard UX. Tasks are ordered so each step is immediately testable.

## Tasks

- [x] 1. Update application.yml and ConfigController defaults
  - Add `app.preview.max-rows-cap: 20000`, `app.preview.fetch-cap: 20000`, `app.stage.page-size-cap: 20000`, `app.stage.max-rows-cap: 100000`, `app.stage.default-max-rows: 20000` under the `app:` key in `application.yml`
  - Update `@Value` default literals in `ConfigController.java` to match the new values
  - _Requirements: 7.1, 7.2, 7.3_

- [ ]* 1.1 Write example test for ConfigController /api/config/limits response
  - Use Spring MockMvc to call `GET /api/config/limits` and assert all five fields are present with numeric values
  - **Feature: backend-driven-limits-dashboard-ux, Property example: ConfigController returns all five limit fields**
  - _Requirements: 7.3_

- [x] 2. Add getLimits() to BackendService
  - [x] 2.1 Implement `getLimits()` in `backend.service.ts`
    - Call `GET /api/config/limits` with `timeout(5000)`
    - On error, catch and return `of(environmentFallbackLimits)` where fallback is built from `environment.monitoring`
    - Never re-throw; always emit a valid `LimitsConfig`
    - _Requirements: 1.1, 1.2, 1.3_

  - [ ]* 2.2 Write property test for getLimits() error resilience
    - **Property 1: getLimits() never errors**
    - For any simulated HTTP error (network, 4xx, 5xx, timeout), verify the observable emits a `LimitsConfig` and completes without error
    - Use fast-check to generate error scenarios
    - **Feature: backend-driven-limits-dashboard-ux, Property 1: getLimits() never errors**
    - _Requirements: 1.2_

- [x] 3. Wire runtime limits into StagingSessionService
  - [x] 3.1 Replace hardcoded environment reads with limits resolution in `staging-session.service.ts`
    - Remove the `Math.min/Math.max` clamping constants
    - Initialize `monitorPageSize` and `monitorMaxRows` from environment as defaults
    - In constructor, call `backend.getLimits()` and assign `monitorPageSize = stagePageSizeCap`, `monitorMaxRows = stageMaxRowsCap`
    - Set `limitsResolved = true` after assignment; defer `connectToSession()` hydration until resolved
    - Log a warning if the API call fails and fallback values are used
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

  - [ ]* 3.2 Write property test for limits assignment
    - **Property 2: Limits assignment from backend response**
    - For any valid `LimitsConfig`, verify `monitorPageSize === stagePageSizeCap` and `monitorMaxRows === stageMaxRowsCap` with no extra transformation
    - **Feature: backend-driven-limits-dashboard-ux, Property 2: Limits assignment from backend response**
    - _Requirements: 2.2, 2.3_

  - [ ]* 3.3 Write property test for fallback value passthrough
    - **Property 3: Fallback values are not further clamped**
    - For any environment fallback config, verify assigned values equal environment values exactly
    - **Feature: backend-driven-limits-dashboard-ux, Property 3: Fallback values are not further clamped**
    - _Requirements: 3.3_

- [x] 4. Checkpoint — Ensure all tests pass, ask the user if questions arise. - read workspace_steering.md file for workspace tools context

- [x] 5. Update DashboardComponent with resolved limits and backlog capacity
  - [x] 5.1 Add `resolvedLimits` and `limitsError` signals; load limits in `ngOnInit()` in `dashboard.component.ts`
    - Call `backend.getLimits()` in `ngOnInit()`; set `resolvedLimits` on success, `limitsError = true` on error
    - Update `getBacklogCapacity()` to return `resolvedLimits()?.stageMaxRowsCap ?? environment.monitoring.monitorMaxRows`
    - Guard against zero: if resolved cap is 0, fall back to environment value
    - _Requirements: 5.1, 5.2, 5.3_

  - [ ]* 5.2 Write property test for backlog capacity resolution
    - **Property 4: Backlog capacity reflects resolved limit**
    - For any `stageMaxRowsCap` value, verify `getBacklogCapacity()` returns it
    - **Feature: backend-driven-limits-dashboard-ux, Property 4: Backlog capacity reflects resolved limit**
    - _Requirements: 5.1_

  - [x] 5.3 Update `getBacklogTooltip()` to use format `"X / Y backlog (cap: Z)"` in `dashboard.component.ts`
    - _Requirements: 5.4_

  - [ ]* 5.4 Write property test for tooltip format
    - **Property 5: Backlog tooltip format**
    - For any (backlog, capacity) pair, verify tooltip contains both values and the substring `"(cap:"`
    - **Feature: backend-driven-limits-dashboard-ux, Property 5: Backlog tooltip format**
    - _Requirements: 5.4_

- [x] 6. Add fallback banner to dashboard HTML
  - Add the `fallback-limits-banner` element in `dashboard.component.html` after the freshness banner
  - Add `dismissLimitsBanner()` method and `limitsBannerDismissed` signal in `dashboard.component.ts`
  - Banner is shown when `limitsError() && !limitsBannerDismissed()`
  - _Requirements: 4.1, 4.2, 4.3_

- [ ]* 6.1 Write example tests for fallback banner visibility
  - Verify banner renders when `limitsError = true` and is hidden when `limitsError = false`
  - Verify clicking dismiss hides the banner without page reload
  - **Feature: backend-driven-limits-dashboard-ux, Property example: fallback banner visibility**
  - _Requirements: 4.1, 4.2, 4.3_

- [x] 7. Add backlog status chip and Dispatch quick-action to sender cards
  - [x] 7.1 Add status chip and conditional Dispatch button to sender card in `dashboard.component.html`
    - Status chip uses `getBacklogStatus()` output with CSS classes `chip-normal`, `chip-warning`, `chip-critical`
    - Dispatch button visible only when status is `warning` or `critical`
    - Add `dispatchSender(sender: SenderPerformance)` method in `dashboard.component.ts` that calls `backend.dispatch()`
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5_

  - [ ]* 7.2 Write property test for backlog status tier classification
    - **Property 6: Backlog status tier classification**
    - For any (backlog, capacity) pair, verify `getBacklogStatus()` returns the correct tier based on ratio thresholds
    - **Feature: backend-driven-limits-dashboard-ux, Property 6: Backlog status tier classification**
    - _Requirements: 8.1, 8.2_

  - [ ]* 7.3 Write property test for dispatch button presence
    - **Property 8: Dispatch button presence matches status**
    - For any sender in `warning` or `critical` state, verify dispatch button is present; absent for `normal`
    - **Feature: backend-driven-limits-dashboard-ux, Property 8: Dispatch button presence matches status**
    - _Requirements: 8.4_

- [x] 8. Add getFileListLabel() helper and wire into dashboard
  - [x] 8.1 Implement `getFileListLabel(loaded, total, cap)` in `dashboard.component.ts`
    - Three cases: loaded >= total → no cap suffix; loaded >= cap → "cap reached"; otherwise → "cap: Z"
    - _Requirements: 6.1, 6.2, 6.3_

  - [ ]* 8.2 Write property test for file list label
    - **Property 7: File list label correctness**
    - For any (loaded, total, cap) triple, verify the label string matches the correct case
    - **Feature: backend-driven-limits-dashboard-ux, Property 7: File list label correctness**
    - _Requirements: 6.1, 6.2, 6.3_

  - [x] 8.3 Add the file list label to `dashboard.component.html` in the monitoring file list area
    - Use `getFileListLabel(sessionFiles().length, currentSession()?.totalFiles ?? 0, resolvedMonitorMaxRows())`
    - Add `resolvedMonitorMaxRows` computed signal to dashboard that reads from `resolvedLimits()?.stageMaxRowsCap`
    - _Requirements: 6.1, 6.2, 6.3_

- [x] 9. Add status chip and dispatch button styles to dashboard.component.scss
  - Add `.backlog-status-chip`, `.chip-normal`, `.chip-warning`, `.chip-critical` styles
  - Add `.dispatch-quick-action` styles — visible inline, no hover required
  - Add `.fallback-limits-banner` styles — non-blocking, dismissible, does not overlap content
  - _Requirements: 4.4, 8.3, 8.5_

- [x] 10. Final checkpoint — Ensure all tests pass, ask the user if questions arise. - read first the workspace_steering.md file for testing context.

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP
- Property tests use **fast-check** (frontend) with minimum 100 iterations each
- Backend test uses Spring MockMvc (JUnit 5)
- Each property test references its design document property number
- `dispatchSender()` calls the existing `backend.dispatch()` method — no new backend endpoint needed
