# SAML 2.0 Integration & Migration Guide — Exensio API

This document details how the **exensioreload** backend connects to the Exensio API, how **SAML 2.0 authentication** is implemented, the requirements on the Exensio server side, and configuration instructions for migrating to SAML mode.

---

## Table of Contents

1. [Exensio API Connection Architecture](#1-exensio-api-connection-architecture)
2. [Exensio REST Endpoints](#2-exensio-rest-endpoints)
3. [SAML 2.0 Integration Overview](#3-saml-20-integration-overview)
4. [Detailed SAML Authentication Flow](#4-detailed-saml-authentication-flow)
   - [4.1 Configuration & Secrets Manager Loading](#41-configuration--secrets-manager-loading)
   - [4.2 SAML AuthnRequest Generation](#42-saml-authnrequest-generation)
   - [4.3 Three-Tier Acquisition Strategy Fallback](#43-three-tier-acquisition-strategy-fallback)
   - [4.4 Assertion Signature Validation & NameID Extraction](#44-assertion-signature-validation--nameid-extraction)
   - [4.5 Token Exchange with Exensio (/v1/saml/consumer)](#45-token-exchange-with-exensio-v1samlconsumer)
   - [4.6 Token Caching, Concurrency & Invalidation](#46-token-caching-concurrency--invalidation)
   - [4.7 Session Termination & Cleanup](#47-session-termination--cleanup)
5. [Requirements on the Exensio API & Server Side](#5-requirements-on-the-exensio-api--server-side)
6. [Configuration Reference](#6-configuration-reference)
   - [6.1 application.yml](#61-applicationyml)
   - [6.2 Secrets Manager Secret Format](#62-secrets-manager-secret-format)
7. [Implementation Dependency Notice (Code Wiring)](#7-implementation-dependency-notice-code-wiring)

---

## 1. Exensio API Connection Architecture

The application acts as an intermediary between data senders and the **Exensio** data warehouse. It communicates via HTTP REST calls to query metadata, verify lot/wafer presence, and track loading status.

### Transport & Client Setup

- **Base URL Resolution**: Resolved dynamically from [`ExensioProperties`](../backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/config/ExensioProperties.java):
  - When `exensio.env: PROD` &rarr; `exensio.prod-url` (`EXENSIO_PROD_URL`)
  - When `exensio.env: QA` &rarr; `exensio.qa-url` (`EXENSIO_QA_URL`)
- **HTTP Client**: Built by [`ExensioHttpClientFactory`](../backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/config/ExensioHttpClientFactory.java):
  - **Redirect Policy**: `HttpClient.Redirect.NEVER` (prevents silent redirects on bad routes or proxy login screens).
  - **Connect Timeout**: 10 seconds.
- **Authorization Header**: All data API calls attach:
  ```http
  Authorization: Bearer <token>
  Content-Type: application/json
  ```

---

## 2. Exensio REST Endpoints

The backend interfaces with five Exensio REST API endpoints:

| Endpoint | HTTP Method | Content-Type | Purpose | Caller Class |
|---|---|---|---|---|
| `/v1/session/login` | `POST` | `application/json` | Acquire session token via username/password (SESSION mode) | [`ExensioAuthService`](../backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioAuthService.java) |
| `/v1/saml/consumer` | `POST` | `application/x-www-form-urlencoded` | Exchange Azure AD SAML assertion for Bearer token (SAML mode) | [`ExensioSamlTokenExchanger`](../backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/saml/ExensioSamlTokenExchanger.java) |
| `/v1/session/logout` | `POST` | `application/json` | Invalidate session token on application shutdown (`@PreDestroy`) | [`ExensioAuthService`](../backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioAuthService.java), [`ExensioSamlAuthService`](../backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioSamlAuthService.java) |
| `/v1/key/lot-wafer-lookup` | `POST` | `application/json` | Verify whether a lot or wafer exists in Exensio | [`ExensioClient`](../backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioClient.java) |
| `/v1/key/raw-sql` | `POST` | `application/json` | Execute read-only SQL queries against Exensio database | [`ExensioRawSqlService`](../backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioRawSqlService.java) |

---

## 3. SAML 2.0 Integration Overview

SAML 2.0 authentication is implemented using **OpenSAML 4** (`opensaml-saml-impl` and `opensaml-xmlsec-impl` v4.3.0).

Instead of sending static database service account credentials directly to Exensio, SAML mode:
1. Authenticates against corporate **Azure Active Directory (IdP)**.
2. Acquires a signed SAML 2.0 Assertion.
3. Validates the assertion signature locally with the IdP public certificate.
4. Exchanges the assertion with Exensio's Assertion Consumer Service (`/v1/saml/consumer`) for an Exensio Bearer token.
5. Employs a three-tier fallback mechanism to navigate MFA and conditional access policies.

---

## 4. Detailed SAML Authentication Flow

```mermaid
sequenceDiagram
    autonumber
    participant App as ExensioSamlAuthService
    participant Secrets as AWS Secrets Manager
    participant IdP as Azure AD (IdP)
    participant Exensio as Exensio API (/v1/saml/consumer)
    participant DataApi as Exensio API (/v1/key/...)

    App->>Secrets: Fetch SAML config JSON (cached for process lifetime)
    Note over App: SamlAuthnRequestBuilder builds SAML AuthnRequest XML
    App->>IdP: Acquire SAML Assertion (FormPost -> WsFed -> Selenium)
    IdP-->>App: SAMLResponse (Base64-encoded XML)
    Note over App: SamlAssertionValidator verifies signature & extracts NameID
    App->>Exensio: POST /v1/saml/consumer (SAMLResponse + predefined-connection)
    Exensio-->>App: { "token": "...", "expiry": 1756992000 }
    Note over App: Cache Bearer token in memory (ReentrantLock)
    App->>DataApi: POST /v1/key/lot-wafer-lookup (Authorization: Bearer <token>)
    DataApi-->>App: Lot/Wafer status response
```

### 4.1 Configuration & Secrets Manager Loading
- Activated when `exensio.auth-mode=SAML`.
- [`ExensioSamlProperties`](../backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/config/ExensioSamlProperties.java) eagerly contacts AWS Secrets Manager at startup (`@PostConstruct`) using `exensio.saml-secret-name`.
- Validates all required JSON fields and caches credentials in memory for the process lifetime (avoids repeated Secrets Manager calls).

### 4.2 SAML AuthnRequest Generation
- [`SamlAuthnRequestBuilder`](../backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/saml/SamlAuthnRequestBuilder.java) generates a compliant SAML 2.0 `AuthnRequest`:
  - **Issuer**: Set to `sp_entity_id`.
  - **AssertionConsumerServiceURL**: Set to `acs_url`.
  - **NameIDPolicy**: Format `urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified`.
  - **XML Signature**: Digitally signed using `sp_private_key` and `sp_certificate` if `sign_requests: true`.

### 4.3 Three-Tier Acquisition Strategy Fallback
[`SamlAuthenticationFacade`](../backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/saml/SamlAuthenticationFacade.java) coordinates three strategies in sequence:

1. **`FormPostSamlStrategy`** (Default & Fastest):
   - POSTs service account credentials directly to the Azure AD IdP endpoint.
   - Extracts `SAMLResponse` from the returned HTML form.
   - If an MFA challenge (`mfa`, `totp`, `sms`) or CAPTCHA is detected, throws an exception to trigger Strategy 2.
2. **`WsFederationSamlStrategy`** (Headless WS-Fed):
   - Contacts Azure AD's WS-Federation endpoint without browser involvement.
   - Parses the `wresult` token and converts it to SAMLResponse format.
   - If WS-Fed is disabled on the tenant (HTTP 404), throws `UnsupportedOperationException` to trigger Strategy 3.
3. **`SeleniumSamlStrategy`** (Full Headless Browser Automation):
   - Uses headless Chromium via Selenium WebDriver.
   - Automates interactive Azure AD login forms, handling advanced MFA and Conditional Access prompts.
   - Optional dependency: gracefully skipped if Selenium is not present on the classpath.

### 4.4 Assertion Signature Validation & NameID Extraction
- [`SamlAssertionValidator`](../backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/saml/SamlAssertionValidator.java):
  - Decodes the base64 assertion and unmarshals the XML via OpenSAML 4.
  - Cryptographically verifies the signature against Azure AD's public X.509 certificate (`idp_certificate`).
  - Extracts the authenticated user's `NameID` (e.g. `sAMAccountName`) and logs at DEBUG.

### 4.5 Token Exchange with Exensio (`/v1/saml/consumer`)
- [`ExensioSamlTokenExchanger`](../backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/saml/ExensioSamlTokenExchanger.java):
  - Sends an HTTP POST to `{baseUrl}/v1/saml/consumer`:
    ```http
    POST https://exensio-prod.example.com/v1/saml/consumer
    Content-Type: application/x-www-form-urlencoded

    SAMLResponse=<url_encoded_assertion>&predefined-connection=PRODUCTION_DB
    ```
  - Parses JSON response:
    ```json
    {
      "token": "eyJhbGciOi...",
      "expiry": 1756992000
    }
    ```
  - If `expiry` is omitted by Exensio, defaults to 3600 seconds (1 hour).

### 4.6 Token Caching, Concurrency & Invalidation
- [`ExensioSamlAuthService`](../backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioSamlAuthService.java):
  - **Caching**: Stored in `volatile String cachedToken` with `volatile Instant cachedTokenExpiry`.
  - **Thread-Safety**: Uses `ReentrantLock` double-checked locking to prevent authentication storms when multiple threads request tokens concurrently.
  - **401 Invalidation**: When any API call receives HTTP 401, `invalidateToken()` clears the token and triggers full re-authentication on the next request.

### 4.7 Session Termination & Cleanup
- During application shutdown (`@PreDestroy`), `shutdown()` calls:
  ```http
  POST {baseUrl}/v1/session/logout
  Authorization: Bearer <cachedToken>
  ```
  and clears the local memory cache.

---

## 5. Requirements on the Exensio API & Server Side

For SAML authentication to succeed, the **Exensio API and server** must have the following configured:

### 1. Consumer Endpoint Available
- Exensio must expose the `POST /v1/saml/consumer` endpoint.
- It must accept `Content-Type: application/x-www-form-urlencoded` containing:
  - `SAMLResponse`: Base64-encoded SAML 2.0 XML assertion.
  - `predefined-connection`: Target database connection identifier.
- It must return HTTP 200 with `{ "token": "<bearer_token>", "expiry": <epoch_seconds> }`.

### 2. Service Provider (SP) Registration in Azure AD
Azure AD Enterprise Application must be configured with Exensio's SP metadata:
- **Identifier (Entity ID)**: Matches `sp_entity_id` (e.g., `https://exensio-prod.example.com/api/v1/saml/metadata`).
- **Reply URL (ACS URL)**: Matches `acs_url` (e.g., `https://exensio-prod.example.com/api/v1/saml/consumer`).

### 3. Identity Provider (IdP) Configuration in Exensio
Exensio must be configured with Azure AD's federation settings:
- **IdP Entity ID**: Matches `idp_entity_id` (e.g., `https://sts.windows.net/{tenant-id}/`).
- **IdP Certificate**: Azure AD's public signing certificate (`idp_certificate`) installed in Exensio truststore so Exensio can verify the assertion's digital signature.

### 4. Service Account Provisioning & LDAP / Group Claims
- The service account (e.g. `exensio-svc@onsemi.com`) must exist in the directory recognized by Exensio.
- Exensio verifies LDAP group membership upon receiving the assertion. If the account lacks the required group/role, Exensio returns **HTTP 401**.

### 5. Predefined Connection Authorization
- The database connection identifier passed in `predefined-connection` (e.g. `PRODUCTION_DB` or `SANDBOX_DB`) must be defined in Exensio.
- The service account must have permissions granted to use that connection. If unauthorized, Exensio returns **HTTP 403**.

### 6. Bearer Token Acceptance on Downstream Endpoints
- Exensio's API Gateway must validate the token in the `Authorization: Bearer <token>` header for:
  - `POST /v1/key/lot-wafer-lookup`
  - `POST /v1/key/raw-sql`
  - `POST /v1/session/logout`

---

## 6. Configuration Reference

### 6.1 application.yml

```yaml
exensio:
  enabled: true
  auth-mode: SAML
  env: PROD
  qa-url: https://exensio-qa.example.com
  prod-url: https://exensio-prod.example.com
  saml-secret-name: exensio/saml-credentials-prod
```

### 6.2 Secrets Manager Secret Format

Stored under the secret name defined in `exensio.saml-secret-name`:

```json
{
  "idp_sso_url": "https://login.microsoftonline.com/xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx/saml2",
  "idp_entity_id": "https://sts.windows.net/xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx/",
  "idp_certificate": "-----BEGIN CERTIFICATE-----\nMIIE...\n-----END CERTIFICATE-----",
  "sp_entity_id": "https://exensio-prod.example.com/api/v1/saml/metadata",
  "acs_url": "https://exensio-prod.example.com/api/v1/saml/consumer",
  "sign_requests": true,
  "sp_private_key": "-----BEGIN RSA PRIVATE KEY-----\nMIIE...\n-----END RSA PRIVATE KEY-----",
  "sp_certificate": "-----BEGIN CERTIFICATE-----\nMIIE...\n-----END CERTIFICATE-----",
  "service_account_username": "exensio-svc@onsemi.com",
  "service_account_password": "AccountPassword123!",
  "predefined_connection": "PRODUCTION_DB"
}
```

---

## 7. Implementation Dependency Notice (Code Wiring)

When enabling `exensio.auth-mode: SAML`, ensure caller services depend on the interface [`ExensioTokenProvider`](../backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioTokenProvider.java) rather than concrete [`ExensioAuthService`](../backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioAuthService.java):

```diff
- private final ExensioAuthService authService;
+ private final ExensioTokenProvider authService;
```

This applies to:
- [`ExensioClient`](../backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioClient.java)
- [`ExensioRawSqlService`](../backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioRawSqlService.java)
- [`ExensioPreCheckService`](../backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioPreCheckService.java)
