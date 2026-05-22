# SSO Onboarding Information — Exensio Reload

This document contains the technical details required by the **Windows/Azure AD Team** (or Entra ID) to register the Exensio Reload application for SSO using OpenID Connect (OIDC).

---

## 1. Application Registration Details

| Field | Value |
| :--- | :--- |
| **Application Name** | Exensio Reload |
| **Authentication Protocol** | OpenID Connect (OIDC) |
| **Authentication Flow** | Authorization Code Flow |
| **Identifier (Entity ID)** | `http://usaz15ls088:8080/exensio-reload/` |
| **Sign-on URL** | `http://usaz15ls088:8080/exensio-reload/` |
| **Reply / Redirect URI** | `http://usaz15ls088:8080/exensio-reload/login/oauth2/code/onsemi` |
| **Logout URL** | `http://usaz15ls088:8080/exensio-reload/api/auth/logout` |

> **Note on Redirect URI:** The path segment `/onsemi` at the end corresponds to the `registrationId` configured in the Spring Boot backend (`OAuth2ClientConfig.java`).

---

## 2. Permissions & Scopes

The application requires the following scopes to function correctly:

- **Standard Scopes:**
  - `openid`
  - `profile`
  - `email`
- **Application Roles / API Permissions:**
  - `GroupMember.Read.All` (Microsoft Graph)
  - *Reason:* Required to read the user's group memberships to map them to internal application roles (ADMIN, SUPER_ADMIN, USER).

---

## 3. Required Output from SSO Team

Once the application is registered, the following credentials must be provided to the application owner to be set as environment variables (e.g., in `.env` or the production `.yml` file):

1. **Client ID** (maps to `ONSEMI_SSO_CLIENT_ID`)
2. **Client Secret** (maps to `ONSEMI_SSO_CLIENT_SECRET`)
3. **Tenant ID** (maps to `ONSEMI_SSO_TENANT_ID`)

---

## 4. Role Mapping Configuration

The application is currently configured to map the following Entra ID (Azure AD) groups to internal roles:

| Entra ID Group Name (Example) | Application Role |
| :--- | :--- |
| `onsemi-exensioreload-admins` | `ADMIN` |
| `onsemi-exensioreload-superadmins` | `SUPER_ADMIN` |
| *Any authenticated user* | `USER` |

---

## 5. Technical Reference
- **Backend Configuration:** `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/config/OAuth2ClientConfig.java`
- **Properties:** `reloader.sso` prefix in `application.yml`
