# Implementation Plan: Session Idle Timeout

## Overview

Replace the JWT-expiry-driven session warning with an inactivity-based idle timer. Changes are primarily in `SessionExpiryService`, with small wiring updates in `AuthService`. The existing modals and modal coordination logic are reused unchanged.

## Tasks

- [x] 1. Refactor SessionExpiryService to use idle-based tracking
  - In `session-expiry.service.ts`, add constants: `IDLE_WARNING_MS = 25 * 60 * 1000` and `IDLE_EXPIRE_MS = 30 * 60 * 1000`
  - Add private fields: `idleWarningSub`, `idleExpirySub`, `activityHandler`
  - Implement `startIdleTracking()`: register `mousemove`, `mousedown`, `keydown`, `touchstart` listeners on `document`; call `scheduleIdleTimers()`
  - Implement `stopIdleTracking()`: remove all 4 listeners; call `cancelIdleTimers()`
  - Implement private `scheduleIdleTimers()`: cancel existing timers, start new `timer(IDLE_WARNING_MS)` that emits `warning$` with 300 and opens warning modal; start new `timer(IDLE_EXPIRE_MS)` that emits `expired$` and opens expired modal
  - Implement private `cancelIdleTimers()`: unsubscribe both timer subs
  - Implement private `onActivity()`: if warning modal is open, close it and call `authService.refresh()`; always call `scheduleIdleTimers()` to reset
  - Remove `scheduleWarning(token)` and `cancelWarning()` methods (replaced by `startIdleTracking` / `stopIdleTracking`)
  - Guard `document.addEventListener` calls with `typeof document !== 'undefined'` for SSR safety
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 2.1, 3.1, 4.1, 4.2, 4.3, 6.1, 6.2, 6.3_

- [ ]* 1.1 Write property test: activity always resets idle timer (Property 1)
  - Use fast-check with fake timers: generate random sequences of activity events at random elapsed times; assert idle timer is always reset to 25 min after each event
  - **Property 1: Activity always resets the idle timer**
  - **Validates: Requirements 1.2, 5.2**

- [ ]* 1.2 Write property test: activity while modal open closes modal (Property 2)
  - Use fast-check: generate activity events while `modalOpen = true` and `activeModalType = 'warning'`; assert modal is closed and timer is reset
  - **Property 2: Activity while warning modal is open closes the modal**
  - **Validates: Requirements 1.3, 4.1, 4.2**

- [ ]* 1.3 Write property test: warning fires only after idle threshold (Property 3)
  - Use fast-check with fake timers: generate activity sequences with varying gaps; assert `warning$` only emits after 25 min of continuous inactivity
  - **Property 3: Warning fires only after idle threshold**
  - **Validates: Requirements 2.1**

- [ ]* 1.4 Write property test: expiry fires only after expiry threshold (Property 4)
  - Use fast-check with fake timers: generate inactivity periods of varying lengths; assert `expired$` only emits at/after 30 min
  - **Property 4: Expiry fires only after expiry threshold**
  - **Validates: Requirements 3.1**

- [x] 2. Update AuthService to use new idle tracking API
  - In `auth.service.ts`, replace `this.sessionExpiryService.scheduleWarning(token)` with `this.sessionExpiryService.startIdleTracking()`
  - Replace `this.sessionExpiryService.cancelWarning()` with `this.sessionExpiryService.stopIdleTracking()`
  - Remove the `warning$` subscription in the constructor (warning is now opened directly inside `SessionExpiryService.scheduleIdleTimers`)
  - Keep the `expired$` subscription as-is (still needed for 401 responses from `AuthInterceptor`)
  - _Requirements: 1.4, 5.1, 5.2, 6.1, 6.3_

- [x] 3. Checkpoint — Ensure all tests pass, ask the user if questions arise. - - ## Execution Constraints - Do not rely on `node`, `npm`, `java`, `JDK`, or `JRE` being available in this workspace. - The environment does not have the privilege to install those runtimes. - Do not run build, test, or install commands that depend on those runtimes after code changes from

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP
- The existing `scheduleRefreshForToken(token)` at `exp - 30s` in `AuthService` is unchanged — it handles silent background refresh; calling `setSession(newToken)` on success will call `startIdleTracking()` again, resetting the idle timer
- The `SessionWarningModalComponent` receives `secondsRemaining = 300` (5 min window) — no changes needed to the modal
- The `AuthInterceptor` 401 → `notifyExpired()` path is unchanged
- The `formatCountdown` helper and all existing modal logic remain intact
