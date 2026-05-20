# Implementation Plan: onsemi SSO Login

## Overview

Implements OIDC-based SSO for onsemi (Azure AD / Entra ID) on top of the existing Spring Boot JWT + Angular AuthService stack. No new Maven dependencies or DB schema changes are required. Tasks are ordered so each step is independently runnable and testable.

## Tasks

- [x] 1. Backend — SSO configuration and properties
  - Create `SsoProperties.java` (`@ConfigurationProperties(prefix = "reloader.sso")`) with fields: `enabled`, `clientId`, `clientSecret`, `tenantId`, `defaultRole`, `roleMappings` (Map<String, String>).
  - Add `application.yml` stubs for all SSO properties using environment variable placeholders.
  - _Requirements: 4.4, 7.5_

- [x] 2. Backend — JIT user provisioning service
  - [x] 2.1 Implement `SsoUserProvisioningService.provisionOrLoad(String email, Set<String> roles)`
    - Lookup user by email via `AppUserRepository`.
    - If not found: create `AppUser` with `username=email`, `passwordHash="{noop}SSO_USER_NO_PASSWORD"`, `enabled=true`, `status=ACTIVE`, assign roles (or `defaultRole` if empty).
    - If found: update roles if they have changed, save, return.
    - _Requirements: 3.1, 3.2, 3.3, 3.4_

  - [ ]* 2.2 Write property test for JIT provisioning idempotence
    - **Property 1: JIT provisioning idempotence**
    - Generate random email strings; call `provisionOrLoad` twice; assert same user ID returned and no duplicate created.
    - **Validates: Requirements 3.3**

  - [ ]* 2.3 Write unit tests for `SsoUserProvisioningService`
    - Test: new user created with correct fields and default role.
    - Test: existing user returned unchanged when roles match.
    - Test: existing user roles updated when IdP groups change.
    - Test: DB error propagates as exception (Requirement 3.5).
    - _Requirements: 3.1, 3.2, 3.4, 3.5_

- [x] 3. Backend — Role mapping utility
  - [x] 3.1 Implement `SsoRoleMapper` (or method in `SsoUserProvisioningService`) that takes a set of IdP group claim strings and `SsoProperties.roleMappings` and returns a `Set<String>` of local roles, always including `defaultRole`.
    - _Requirements: 4.1, 4.2, 4.3_

  - [ ]* 3.2 Write property test for role mapping completeness
    - **Property 2: Role mapping completeness**
    - Generate random sets of IdP group names; assert result always contains `USER` and that configured mappings are applied.
    - **Validates: Requirements 4.1, 4.2**

- [x] 4. Backend — SSO authentication success handler
  - [x] 4.1 Implement `SsoAuthenticationSuccessHandler` (implements `AuthenticationSuccessHandler`)
    - Extract `email` claim from `OidcUser`.
    - Extract group claims (claim name configurable via `SsoProperties`).
    - Call `SsoRoleMapper` to get local roles.
    - Call `SsoUserProvisioningService.provisionOrLoad`.
    - Issue JWT via `JwtUtil.generateToken(username, roles)`.
    - Create and persist `RefreshToken`; set HTTP-only cookie (reuse cookie-building logic from `AuthController`).
    - Retrieve `returnUrl` from HTTP session; sanitize it (same logic as `LoginComponent.getSafeReturnUrl`).
    - Redirect to `/sso-callback?token=<JWT>&returnUrl=<safeUrl>`.
    - _Requirements: 2.1, 2.2, 2.4, 2.5, 7.6_

  - [ ]* 4.2 Write property test for open redirect prevention
    - **Property 4: Open redirect prevention**
    - Generate arbitrary strings as `returnUrl`; assert only relative paths without `://` or `//` pass through; all others map to `/exensioreload`.
    - **Validates: Requirements 7.6**

  - [ ]* 4.3 Write unit tests for `SsoAuthenticationSuccessHandler`
    - Test: JWT issued and cookie set on valid `OidcUser`.
    - Test: redirect URL contains token and sanitized returnUrl.
    - Test: provisioning failure redirects to `/login?reason=sso-error`.
    - _Requirements: 2.4, 2.5, 3.5_

- [x] 5. Backend — SSO failure handler
  - Implement `SsoAuthenticationFailureHandler` (implements `AuthenticationFailureHandler`)
    - If `OAuth2Error` reason is `login_required` or `interaction_required` → redirect to `/login` (silent SSO fallback, no error shown).
    - All other failures → redirect to `/login?reason=sso-error`.
    - _Requirements: 6.4, 8.3_

  - [ ]* 5.1 Write property test for silent SSO fallback
    - **Property 6: Silent SSO fallback**
    - For `login_required` and `interaction_required` error codes, assert redirect goes to `/login` without `reason` param.
    - **Validates: Requirements 8.3**

- [x] 6. Backend — `SecurityConfig` updates
  - Conditionally register `oauth2Login()` when `reloader.sso.enabled=true`:
    - Set `successHandler` to `SsoAuthenticationSuccessHandler`.
    - Set `failureHandler` to `SsoAuthenticationFailureHandler`.
  - Add `permitAll()` rules for `/login/oauth2/**`, `/api/auth/sso/**`, `/sso-callback`.
  - Keep existing `JwtAuthenticationFilter` and local login path unchanged.
  - _Requirements: 2.1, 6.1, 6.2_

- [x] 7. Backend — `SsoController` and `AuthConfigController`
  - [x] 7.1 Implement `SsoController`
    - `GET /api/auth/sso/initiate?returnUrl=...` — sanitize returnUrl, store in session, redirect to `/oauth2/authorization/onsemi`.
    - `GET /api/auth/sso/silent?returnUrl=...` — same but appends `&prompt=none` to the authorization URL via a custom `OAuth2AuthorizationRequestResolver`.
    - Return 404 when `reloader.sso.enabled=false`.
    - _Requirements: 1.2, 1.3, 7.3, 8.1, 8.4_

  - [x] 7.2 Implement `AuthConfigController`
    - `GET /api/auth/config` → `{ "ssoEnabled": true/false }` (reads from `SsoProperties.enabled`).
    - _Requirements: 6.3_

- [x] 8. Backend — Checkpoint
  - Ensure all backend tests pass. Verify `SecurityConfig` loads without errors when `reloader.sso.enabled=false` (SSO disabled path). Ask the user if questions arise.

- [x] 9. Frontend — `SsoCallbackComponent`
  - Create `SsoCallbackComponent` at `new_frontend/src/app/auth/sso-callback.component.ts`.
  - On init: read `token` and `returnUrl` from query params.
  - Call `AuthService.handleSsoCallback(token)`.
  - On success: navigate to sanitized `returnUrl` (or `/exensioreload`).
  - On error: navigate to `/login?reason=sso-error`.
  - Register route `/sso-callback` in the app router.
  - _Requirements: 2.5, 7.6_

- [x] 10. Frontend — `AuthService` updates
  - [x] 10.1 Add `handleSsoCallback(token: string): Observable<void>`
    - Calls `setSession(token)` then `loadMe()`.
    - _Requirements: 5.4_

  - [x] 10.2 Add `trySilentSso(returnUrl: string): void`
    - When `ssoEnabled` is true and no local session exists, redirect `window.location.href` to `/api/auth/sso/silent?returnUrl=<returnUrl>`.
    - _Requirements: 8.1, 8.4_

  - [ ]* 10.3 Write property test for SSO session token equivalence
    - **Property 3: SSO session token equivalence**
    - Generate random usernames and role sets; issue JWT via `JwtUtil`; assert `extractUsername` and `extractRoles` return the same values regardless of whether the token was issued via SSO or local path.
    - **Validates: Requirements 5.4**

- [x] 11. Frontend — `LoginComponent` updates
  - Fetch `GET /api/auth/config` on init; store `ssoEnabled` flag.
  - Add "Sign in with onsemi SSO" button, shown only when `ssoEnabled=true`.
  - On click: navigate to `/api/auth/sso/initiate?returnUrl=<currentReturnUrl>` via `window.location.href`.
  - Handle `reason=sso-error` query param: show "SSO sign-in failed. Please try again or use local login." error message.
  - _Requirements: 1.1, 1.2, 1.3, 1.4, 6.3, 6.4_

- [x] 12. Frontend — Silent SSO on app startup
  - In `AuthService` constructor (or an `APP_INITIALIZER`): after the existing `refresh()` attempt fails (no refresh cookie), check if `ssoEnabled` is true and call `trySilentSso(currentPath)`.
  - Ensure this only runs once and does not loop.
  - _Requirements: 8.1, 8.2, 8.4, 8.5_

- [x] 13. Final checkpoint — Ensure all tests pass - check workspace_steering.md doc (java and node can be used in this server so testing/running is impossible )
  - Run full test suite (backend + frontend).
  - Verify SSO disabled path: SSO button hidden, silent check skipped, local login works normally.
  - Verify SSO enabled path (with mock IdP or test credentials): silent check, interactive login, JIT provisioning, role mapping, logout.
  - Ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP.
- All SSO secrets must be provided via environment variables — never hardcoded.
- The `prompt=none` silent flow requires the app to be registered in Azure AD with the correct redirect URI.
- jqwik is the recommended PBT library for Java property tests (`net.jqwik:jqwik`).
