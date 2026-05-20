# Implementation Plan: Session Expiry UX

## Overview

Implement industry-standard session expiry UX for `exensioreload`. The work is broken into incremental steps: service layer first, then modals, then wiring existing files, then the return-URL flow.

## Tasks

- [x] 1. Create `SessionExpiryService` with warning scheduling and expired event emission
  - Create `src/app/auth/session-expiry.service.ts`
  - Implement `scheduleWarning(token: string)` — parse JWT exp, schedule `timer()` at `exp - 120s`, emit `warning$` with seconds remaining
  - Implement `cancelWarning()` — unsubscribe timer
  - Implement `notifyExpired()` — emit `expired$`
  - Implement singleton guard: `private modalOpen = false` flag; `openWarningModal()` and `openExpiredModal()` methods that check the flag before calling `GlassDialogService.open()`
  - Implement `formatCountdown(seconds: number): string` as a static/pure helper returning `MM:SS`
  - Inject `GlassDialogService`, `AuthService`, `Router`
  - _Requirements: 1.1, 2.1, 4.1, 4.2, 4.3_

- [ ]* 1.1 Write property test for `formatCountdown` (Property 2)
  - Use fast-check: `fc.integer({ min: 0, max: 7199 })` → assert result matches `/^\d{2}:\d{2}$/` and values are mathematically correct
  - **Property 2: MM:SS formatter correctness**
  - **Validates: Requirements 1.3**

- [ ]* 1.2 Write property test for singleton modal guard (Property 4)
  - Use fast-check: generate N (1–50) repeated calls to `notifyExpired()` while `modalOpen = true`; assert `GlassDialogService.open` spy called exactly once
  - **Property 4: Modal singleton — no stacking on repeated events**
  - **Validates: Requirements 2.7, 4.2**

- [x] 2. Create `SessionWarningModalComponent`
  - Create `src/app/auth/session-warning-modal.component.ts`
  - Inject `GLASS_DIALOG_DATA` to receive `secondsRemaining: number`
  - Inject `GlassDialogRef`, `AuthService`, `SessionExpiryService`, `ToastService`
  - Run `setInterval` every 1000ms to decrement counter; call `formatCountdown()` for display
  - Subscribe to `AuthService.token$` — if a new non-null token arrives, close modal automatically
  - "Stay Logged In" button: call `AuthService.refresh()`, on success close modal + `ToastService.success('Session extended')`, on failure call `SessionExpiryService.notifyExpired()`
  - "Log Out" button: call `AuthService.logout()`
  - When counter hits 0: close self, call `SessionExpiryService.notifyExpired()`
  - Use `glass-panel` CSS class; match existing modal styling
  - _Requirements: 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 5.1, 5.3, 5.5_

- [x] 3. Create `SessionExpiredModalComponent`
  - Create `src/app/auth/session-expired-modal.component.ts`
  - Inject `GlassDialogRef`, `AuthService`, `Router`, `ActivatedRoute` (or use `window.location.pathname`)
  - Display message: "Your session has expired. Please log in again to continue."
  - "Log In Again" button: call `AuthService.clearSession()`, navigate to `/login?returnUrl=<currentPath>`
  - Use `glass-panel` CSS class; match existing modal styling
  - _Requirements: 2.2, 2.3, 2.4, 2.5, 5.2, 5.4, 5.5_

- [x] 4. Wire `SessionExpiryService` into `AuthService`
  - In `AuthService.setSession(token)`: call `this.sessionExpiryService.scheduleWarning(token)` when token is non-null
  - In `AuthService.setSession(null)`: call `this.sessionExpiryService.cancelWarning()`
  - Add `clearSession()` public method that calls `setSession(null)` without navigating (for use by expired modal)
  - Subscribe to `SessionExpiryService.warning$` in `AuthService` constructor to trigger `openWarningModal()`
  - Subscribe to `SessionExpiryService.expired$` in `AuthService` constructor to trigger `openExpiredModal()`
  - _Requirements: 1.1, 2.1, 4.1_

- [x] 5. Update `AuthInterceptor` to use `SessionExpiryService`
  - Inject `SessionExpiryService` in `auth.interceptor.ts`
  - Replace `auth.logout()` call on 401 with `sessionExpiryService.notifyExpired()`
  - _Requirements: 2.1_

- [ ]* 5.1 Write property test for interceptor 401 handling (Property 3)
  - Use fast-check: generate random HTTP status codes; assert only status 401 triggers `notifyExpired()` and never calls `auth.logout()` directly
  - **Property 3: 401 responses always emit expired event**
  - **Validates: Requirements 2.1**

- [x] 6. Checkpoint — Ensure all tests pass, ask the user if questions arise.

- [x] 7. Update `LoginComponent` to handle `returnUrl`
  - Inject `ActivatedRoute` in `login.component.ts`
  - After successful login, read `returnUrl` from `this.route.snapshot.queryParamMap.get('returnUrl')`
  - Safety check: accept only strings starting with `/` and not containing `://`
  - Navigate to safe `returnUrl` or fall back to `/exensioreload`
  - _Requirements: 3.1, 3.2, 3.3_

- [ ]* 7.1 Write property test for `returnUrl` safety validation (Property 5)
  - Use fast-check: generate arbitrary strings; assert only `/`-prefixed strings without `://` are accepted as valid returnUrls
  - **Property 5: returnUrl safety — only relative internal paths accepted**
  - **Validates: Requirements 3.2, 3.3**

- [x] 8. Final checkpoint — Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP
- PBT library: **fast-check** (`npm install --save-dev fast-check`)
- Each property test should run a minimum of 100 iterations
- `SessionExpiryService` subscribes to its own `warning$` and `expired$` to open modals — this keeps modal logic centralized
- `disableClose: true` must be passed to `GlassDialogService.open()` for both modals
- The `formatCountdown` helper should be exported as a standalone pure function for easy unit/property testing
