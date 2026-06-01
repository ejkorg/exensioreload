# SSO Onboarding Information — ExensioReload

This document contains the technical details required by the **Windows / Active Directory Level 2 Team**
to register the ExensioReload application for SSO using **OpenID Connect (OIDC)** via Microsoft Entra ID.

> **Protocol note:** This application uses **OpenID Connect (OIDC) / OAuth 2.0 Authorization Code Flow**,
> not SAML. All SAML fields on the registration form should be left blank or marked "N/A".

---

## Customer Information

| Field                        | Value                                 |
| :--------------------------- | :------------------------------------ |
| **Company Name**             | onsemi                                |
| **Technical contact name**   | _(to be filled by application owner)_ |
| **Technical contact phone**  | _(to be filled by application owner)_ |
| **Technical contact e-mail** | _(to be filled by application owner)_ |

---

## Application Information

| Field                                     | Value                                                                                                                                                                                                                                                                                                               |
| :---------------------------------------- | :------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **onsemi Application Owner**              | _(to be filled by application owner)_                                                                                                                                                                                                                                                                               |
| **Application Name**                      | ExensioReload                                                                                                                                                                                                                                                                                                       |
| **Application Description**               | Internal semiconductor test-data resend application. Allows engineers to discover metadata from manufacturing site Oracle databases, stage payloads, dispatch them to sender queues, and monitor completion. Supports local JWT authentication plus optional Microsoft Entra OIDC SSO for corporate identity login. |
| **On-prem / Cloud**                       | On-premises — internal enterprise deployment                                                                                                                                                                                                                                                                        |
| **Requirements for external user access** | Not intended for external users. Access must remain limited to onsemi corporate identities and assigned application roles.                                                                                                                                                                                          |
| **User onboarding process**               | Users are provisioned automatically (JIT) on first successful Entra login. Entra group membership is mapped to internal application roles. Local username/password login remains available for non-SSO users if enabled.                                                                                            |
| **Custom Attributes and Claims**          | `email`, `sub`, `groups` (default Entra groups claim). Group names map to roles — see Role Mapping section below.                                                                                                                                                                                                   |

---

## Provide App Integration Instructions — SAML

> **Not applicable.** This application uses OpenID Connect, not SAML.
> Leave all SAML fields blank or mark as N/A.

| Field                      | Value |
| :------------------------- | :---- |
| Identifier (Entity ID) URL | N/A   |
| Reply URL                  | N/A   |
| Sign on URL                | N/A   |
| Relay State                | N/A   |
| Logout URL                 | N/A   |

---

## Provide App Integration Instructions — OpenID Connect

| Field                            | Value                                                             |
| :------------------------------- | :---------------------------------------------------------------- |
| **Redirect URL (Reply URL)**     | `http://usaz15ls088:8080/exensio-reload/login/oauth2/code/onsemi` |
| **Sign-on URL**                  | `http://usaz15ls088:8080/exensio-reload/`                         |
| **Logout URL**                   | `http://usaz15ls088:8080/exensio-reload/api/auth/logout`          |
| **Authorization Grant Type**     | Authorization Code                                                |
| **Client Authentication Method** | `client_secret_basic`                                             |
| **Scopes requested**             | `openid`, `profile`, `email`, `GroupMember.Read.All`              |
| **Token endpoint auth method**   | Client Secret (Basic)                                             |

> **Note on Redirect URL:** The `/onsemi` suffix is the Spring Security `registrationId` configured
> in `OAuth2ClientConfig.java`. It must match exactly — including case.

---

## OIDC Endpoints (Microsoft Entra v2.0)

These are constructed automatically by the backend from the Tenant ID once it is provided.
Listed here for reference and verification.

| Endpoint                  | URL pattern                                                          |
| :------------------------ | :------------------------------------------------------------------- |
| **Authorization**         | `https://login.microsoftonline.com/{tenantId}/oauth2/v2.0/authorize` |
| **Token**                 | `https://login.microsoftonline.com/{tenantId}/oauth2/v2.0/token`     |
| **JWK Set (public keys)** | `https://login.microsoftonline.com/{tenantId}/discovery/v2.0/keys`   |
| **UserInfo**              | `https://graph.microsoft.com/oidc/userinfo`                          |

---

## Required Output from the SSO / AD Team

Once the application is registered in Entra ID, the following values must be provided to the
application owner to be set as environment variables on the production server:

| Environment Variable       | Description                                             |
| :------------------------- | :------------------------------------------------------ |
| `ONSEMI_SSO_CLIENT_ID`     | Application (client) ID from the Entra app registration |
| `ONSEMI_SSO_CLIENT_SECRET` | Client secret generated in the Entra app registration   |
| `ONSEMI_SSO_TENANT_ID`     | Directory (tenant) ID of the onsemi Entra tenant        |

These are set alongside `ONSEMI_SSO_ENABLED=true` to activate SSO at runtime.

---

## Role Mapping Configuration

The application maps Entra ID group memberships to internal roles using the `groups` claim.
The following group names must exist in Entra ID and be assigned to the appropriate users:

| Entra ID Group Name                | Application Role | Permissions                           |
| :--------------------------------- | :--------------- | :------------------------------------ |
| `onsemi-exensioreload-superadmins` | `SUPER_ADMIN`    | Full access including user management |
| `onsemi-exensioreload-admins`      | `ADMIN`          | Elevated access, no user management   |
| _(any authenticated user)_         | `USER`           | Standard access                       |

> Group names are configurable via `reloader.sso.role-mappings` in `application.yml`.
> The claim name is configurable via `reloader.sso.group-claim-name` (default: `groups`).

---

## API Permissions Required (Microsoft Graph)

| Permission             | Type      | Reason                                             |
| :--------------------- | :-------- | :------------------------------------------------- |
| `openid`               | Delegated | Required for OIDC login                            |
| `profile`              | Delegated | Read basic profile (name, sub)                     |
| `email`                | Delegated | Read user email — used as the application username |
| `GroupMember.Read.All` | Delegated | Read group memberships to map to application roles |

---

## SSO Flow Summary (for the AD team's reference)

1. User clicks "Sign in with onsemi SSO" in the application.
2. Browser is redirected to `https://login.microsoftonline.com/{tenantId}/oauth2/v2.0/authorize`.
3. User authenticates with their onsemi corporate credentials.
4. Entra redirects back to `http://usaz15ls088:8080/exensio-reload/login/oauth2/code/onsemi` with an authorization code.
5. Backend exchanges the code for tokens at the token endpoint.
6. Backend reads `email` and `groups` claims from the ID token.
7. User is provisioned (JIT) or loaded from the local database.
8. Backend issues its own JWT + sets an HTTP-only refresh cookie.
9. Browser is redirected to `/sso-callback` and the user lands in the application.

---

## Technical Reference

| Item                               | Location                                                      |
| :--------------------------------- | :------------------------------------------------------------ |
| OAuth2 client registration         | `backend/.../config/OAuth2ClientConfig.java`                  |
| SSO properties                     | `reloader.sso.*` in `application.yml`                         |
| Success handler (JIT provisioning) | `SsoAuthenticationSuccessHandler.java`                        |
| Role mapper                        | `SsoRoleMapper.java`                                          |
| Silent SSO endpoint                | `GET /exensio-reload/api/auth/sso/silent?returnUrl=...`       |
| Interactive SSO endpoint           | `GET /exensio-reload/api/auth/sso/initiate?returnUrl=...`     |
| Angular SSO callback route         | `/sso-callback`                                               |
| Production host                    | `usaz15ls088:8080` (nginx reverse proxy → backend on `:8004`) |
