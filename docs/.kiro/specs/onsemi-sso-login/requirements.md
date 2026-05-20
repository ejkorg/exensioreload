# Requirements Document

## Introduction

This feature adds Single Sign-On (SSO) login support for onsemi.com corporate users. The system currently uses a local username/password authentication flow backed by JWT access tokens and HTTP-only refresh cookies. SSO will allow onsemi employees to authenticate using their corporate identity provider (IdP) — via SAML 2.0 or OIDC — without needing a separate local account. The existing local login path must remain functional for non-SSO users and service accounts.

## Glossary

- **SSO**: Single Sign-On — a session/user authentication scheme that allows a user to log in once and gain access to multiple systems.
- **IdP**: Identity Provider — the onsemi corporate identity system (e.g., Azure AD / Entra ID, Okta, or ADFS) that authenticates users.
- **SP**: Service Provider — this application (Resender), which relies on the IdP to authenticate users.
- **SAML 2.0**: Security Assertion Markup Language — an XML-based open standard for exchanging authentication and authorization data between an IdP and SP.
- **OIDC**: OpenID Connect — an identity layer on top of OAuth 2.0 that allows the SP to verify the identity of a user via the IdP.
- **Access Token**: A short-lived JWT issued by the Resender backend after successful authentication (local or SSO).
- **Refresh Token**: A long-lived opaque token stored in an HTTP-only cookie used to silently renew the Access Token.
- **JIT Provisioning**: Just-In-Time user provisioning — automatically creating a local user record on first SSO login.
- **AuthService**: The Angular frontend service responsible for managing authentication state.
- **AuthController**: The Spring Boot REST controller handling authentication endpoints.
- **SecurityConfig**: The Spring Boot security configuration class.
- **AppUser**: The local user entity stored in the application database.

## Requirements

### Requirement 1: SSO Login Entry Point

**User Story:** As an onsemi employee, I want to click a "Sign in with onsemi SSO" button on the login page, so that I can authenticate using my corporate credentials without entering a separate username and password.

#### Acceptance Criteria

1. THE Login_Page SHALL display a clearly labeled "Sign in with onsemi SSO" button alongside the existing username/password form.
2. WHEN a user clicks the SSO button, THE Login_Page SHALL redirect the user's browser to the IdP-initiated login URL.
3. WHEN the SSO button is clicked, THE Login_Page SHALL preserve any `returnUrl` query parameter so the user is redirected to the correct page after authentication.
4. WHILE the SSO redirect is in progress, THE Login_Page SHALL display a loading indicator to prevent duplicate clicks.

---

### Requirement 2: Backend SSO Callback and Token Exchange

**User Story:** As the system, I want to handle the IdP callback after a successful SSO authentication, so that I can issue a local JWT access token and refresh token to the authenticated user.

#### Acceptance Criteria

1. WHEN the IdP redirects back to the SP callback URL with a valid assertion or authorization code, THE AuthController SHALL validate the assertion or code with the IdP.
2. WHEN the IdP assertion is valid, THE AuthController SHALL extract the user's corporate email address and display name from the IdP claims.
3. WHEN the IdP assertion is invalid or expired, THE AuthController SHALL return an HTTP 401 response with a descriptive error message.
4. WHEN a valid SSO callback is processed, THE AuthController SHALL issue a JWT access token and set an HTTP-only refresh token cookie, using the same mechanism as local login.
5. WHEN a valid SSO callback is processed, THE AuthController SHALL redirect the browser to the frontend with the access token so the Angular AuthService can initialize the session.

---

### Requirement 3: Just-In-Time User Provisioning

**User Story:** As an onsemi employee logging in via SSO for the first time, I want my account to be created automatically, so that I do not need to register separately.

#### Acceptance Criteria

1. WHEN a valid SSO assertion is received for a user whose email does not exist in the AppUser table, THE AuthController SHALL create a new AppUser record with the corporate email as the username.
2. WHEN a new AppUser is created via JIT provisioning, THE System SHALL assign the default role `USER` to the new account.
3. WHEN a valid SSO assertion is received for a user who already exists in the AppUser table, THE AuthController SHALL use the existing account without modifying it.
4. WHEN a JIT-provisioned user account is created, THE System SHALL mark the account as enabled and set a null or unusable password to prevent local password login.
5. IF JIT provisioning fails due to a database error, THEN THE AuthController SHALL return an HTTP 500 response and log the error with sufficient detail for diagnosis.

---

### Requirement 4: Role Mapping from IdP Claims

**User Story:** As a system administrator, I want SSO users' roles to be derived from IdP group claims, so that access control is consistent with corporate group membership.

#### Acceptance Criteria

1. WHEN the IdP assertion contains group or role claims, THE AuthController SHALL map configured IdP group names to local application roles (e.g., `onsemi-resender-admins` → `ADMIN`).
2. WHEN no matching group claim is found in the IdP assertion, THE AuthController SHALL assign the default role `USER` to the authenticated user.
3. WHEN a role mapping is configured, THE System SHALL apply it at every SSO login, updating the user's roles if IdP group membership has changed.
4. THE System SHALL support configuring IdP-to-role mappings via application properties without requiring code changes.

---

### Requirement 5: Session Continuity and Token Lifecycle

**User Story:** As an authenticated SSO user, I want my session to behave identically to a local login session, so that token refresh, expiry warnings, and logout work consistently.

#### Acceptance Criteria

1. WHEN an SSO user's access token is within 30 seconds of expiry, THE AuthService SHALL silently refresh it using the existing `/auth/refresh` endpoint.
2. WHEN an SSO user's refresh token expires, THE System SHALL display the session expiry warning modal and redirect to the login page on expiry.
3. WHEN an SSO user clicks logout, THE AuthController SHALL revoke the refresh token and clear the HTTP-only cookie, identical to local logout behavior.
4. WHILE an SSO session is active, THE AuthService SHALL expose the user's username and roles through the same `currentUser` signal used by local login sessions.

---

### Requirement 8: Automatic / Silent SSO

**User Story:** As an onsemi employee who is already signed into my corporate account on this computer, I want the application to authenticate me automatically when I open it in the browser, so that I do not need to click any login button.

#### Acceptance Criteria

1. WHEN the application loads and no local session exists, THE AuthService SHALL attempt a silent OIDC authentication using the existing Azure AD browser session.
2. WHEN the silent OIDC check succeeds, THE System SHALL establish a full session (JWT + refresh cookie) and navigate the user directly to the application without displaying the login page.
3. WHEN the silent OIDC check fails because no Azure AD session exists, THE System SHALL display the login page without showing an error.
4. WHEN the silent OIDC check does not complete within 5 seconds, THE System SHALL cancel the attempt and display the login page.
5. WHERE SSO is disabled via configuration, THE AuthService SHALL skip the silent SSO attempt entirely.

---

### Requirement 6: Fallback to Local Login

**User Story:** As a service account or non-onsemi user, I want the existing username/password login to remain available, so that automated processes and non-SSO users are not disrupted.

#### Acceptance Criteria

1. THE Login_Page SHALL continue to display the username/password form alongside the SSO button.
2. WHEN a user submits the username/password form, THE AuthController SHALL authenticate using the existing local credential flow, unaffected by the SSO configuration.
3. WHERE SSO is disabled via configuration, THE Login_Page SHALL hide the SSO button and display only the local login form.
4. IF the SSO IdP is unreachable, THEN THE System SHALL display an error message on the login page and allow the user to fall back to local login.

---

### Requirement 7: Security and Configuration

**User Story:** As a security engineer, I want the SSO integration to follow security best practices, so that the application is not vulnerable to assertion replay, open redirects, or token leakage.

#### Acceptance Criteria

1. THE System SHALL validate the IdP assertion signature using the IdP's public certificate or JWKS endpoint before trusting any claims.
2. THE System SHALL enforce a maximum assertion age of 5 minutes to prevent replay attacks.
3. WHEN processing the SSO callback, THE AuthController SHALL validate that the `RelayState` or `state` parameter matches the value generated at login initiation to prevent CSRF.
4. THE System SHALL not expose the raw IdP assertion or authorization code to the frontend.
5. THE System SHALL configure all SSO-related settings (IdP metadata URL, client ID, client secret, certificate) exclusively via environment variables or application properties, with no hardcoded values.
6. WHEN a `returnUrl` is provided after SSO login, THE AuthController SHALL validate that it is a relative path within the application before redirecting, to prevent open redirect attacks.
