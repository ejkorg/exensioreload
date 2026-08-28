# Exensio Authentication Modes — exensioreload

This document provides a comprehensive comparison of all authentication modes for the **exensioreload** Spring Boot application's integration with the Exensio Big Data Analytics API.

## Overview

The exensioreload application supports three authentication mechanisms:

| Mode | Status | Description |
|---|---|---|
| **SESSION** | ✅ Production (current) | Username/password → session token |
| **OAUTH** | ⚠️ Implementation complete, pending Exensio confirmation | Azure AD OAuth 2.0 / OIDC |
| **SAML** | ✅ Implementation complete | Azure AD SAML SSO with multi-strategy fallback |

## Authentication Flow Comparison

### SESSION Mode (Current)

```java
@Service
@ConditionalOnProperty(name="exensio.auth-mode", havingValue="SESSION", matchIfMissing=true)
public class ExensioAuthService implements ExensioTokenProvider {
    
    // POST /v1/session/login {username, password, dbname, dbschema}
    // → Exensio Bearer token
    // → Cache per schema
    // → POST /v1/session/logout on @PreDestroy
}
```

**Pros**:
- ✅ Simple, proven implementation
- ✅ Currently production-ready
- ✅ Works with all Exensio deployments

**Cons**:
- ❌ Requires username/password storage
- ❌ No centralized identity management
- ❌ Manual credential rotation

### OAUTH Mode (Implemented)

```java
@Service
@ConditionalOnProperty(name="exensio.auth-mode", havingValue="OAUTH")
public class ExensioOAuthAuthService implements ExensioTokenProvider {
    
    // POST https://login.microsoftonline.com/{tenantId}/oauth2/v2.0/token
    // → {grant_type: client_credentials, client_id, client_secret, scope}
    // → OIDC Bearer token (JWT)
    // → Cache with 60s expiry buffer
    // → No logout (stateless)
}
```

**Pros**:
- ✅ No user passwords (service principal secrets)
- ✅ Centralized Azure AD identity management
- ✅ Stateless tokens (self-expiring)
- ✅ Simpler than SAML for service accounts

**Cons**:
- ⚠️ Requires Exensio API to validate OIDC tokens (unconfirmed)
- ❌ No user identity in tokens (service principal only)

### SAML Mode (Implemented)

```java
@Service
@ConditionalOnProperty(name="exensio.auth-mode", havingValue="SAML")
public class ExensioSamlAuthService implements ExensioTokenProvider {
    
    // Strategy 1: Direct form-POST to Azure AD
    // Strategy 2: WS-Federation headless SAML
    // Strategy 3: Selenium headless Chrome automation
    
    // → SAML Assertion from Azure AD
    // → POST /v1/saml/consumer + predefined_connection to Exensio
    // → Exensio validates + LDAP group check
    // → Cache Session_Token per schema
    // → POST /v1/session/logout on @PreDestroy
}
```

**Multi-Strategy Fallback**:

1. **FormPostSamlStrategy**: POST service account credentials directly to Azure AD login endpoint — fastest, works for most deployments
2. **WsFederationSamlStrategy**: WS-Federation headless SAML — used if form-POST returns MFA challenge; some tenants support this without browser
3. **SeleniumSamlStrategy**: Headless Chrome browser automation with Selenium — last resort, handles any Azure AD flow including complex MFA

Strategies are attempted in order; first to succeed is used. All failures aggregate into single `ExensioAuthException` with all three error messages.

**Pros**:
- ✅ Officially documented by Exensio
- ✅ User identity preserved (sAMAccountName in logs)
- ✅ LDAP group validation built-in on Exensio side
- ✅ Supports Azure AD Conditional Access and advanced security policies
- ✅ Multi-strategy fallback handles complex MFA/CAPTCHA scenarios
- ✅ Credentials cached once — Secrets Manager called exactly once per process

**Cons**:
- ❌ Most complex implementation (SAML libraries, browser automation)
- ❌ Requires Azure AD Enterprise Application configuration
- ❌ Longer initial setup and QA timeline

## Configuration Comparison

### application.yml Configuration

**SESSION Mode**:
```yaml
exensio:
  enabled: true
  auth-mode: SESSION  # or omit (default)
  env: PROD
  qa-url: https://exensio-qa.example.com
  prod-url: https://exensio-prod.example.com
  username: ${EXENSIO_USERNAME}
  password: ${EXENSIO_PASSWORD}
  dbname: ${EXENSIO_DBNAME}
```

**OAUTH Mode**:
```yaml
exensio:
  enabled: true
  auth-mode: OAUTH
  env: PROD
  qa-url: https://exensio-qa.example.com
  prod-url: https://exensio-prod.example.com
  oauth-secret-name: exensio/oauth-credentials-prod
```

**SAML Mode**:
```yaml
exensio:
  enabled: true
  auth-mode: SAML
  env: PROD
  qa-url: https://exensio-qa.example.com
  prod-url: https://exensio-prod.example.com
  saml-secret-name: exensio/saml-credentials-prod
```

### Secrets Manager Secret Formats

**SESSION**:
```json
{
  "username": "exensio_user",
  "password": "secure_password",
  "dbname": "exensio_db"
}
```

**OAUTH**:
```json
{
  "tenant_id": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
  "client_id": "yyyyyyyy-yyyy-yyyy-yyyy-yyyyyyyyyyyy",
  "client_secret": "zzzzzzzz~secret~",
  "scope": "api://exensio-big-data-api/.default"
}
```

**SAML**:
```json
{
  "idp_sso_url": "https://login.microsoftonline.com/.../saml2",
  "idp_entity_id": "https://sts.windows.net/.../",
  "idp_certificate": "-----BEGIN CERTIFICATE-----\n...",
  "sp_entity_id": "https://exensio-prod.example.com/api/v1/saml/metadata",
  "acs_url": "https://exensio-prod.example.com/api/v1/saml/consumer",
  "sign_requests": true,
  "sp_private_key": "-----BEGIN RSA PRIVATE KEY-----\n...",
  "sp_certificate": "-----BEGIN CERTIFICATE-----\n...",
  "service_account_username": "exensio-svc@domain.com",
  "service_account_password": "...",
  "predefined_connection": "PRODUCTION_DB"
}
```

## Spring Bean Selection

The correct auth service is injected automatically via `@ConditionalOnProperty`:

```java
// Only ONE of these beans is created based on exensio.auth-mode

@Service
@ConditionalOnProperty(name="exensio.auth-mode", havingValue="SESSION", matchIfMissing=true)
public class ExensioAuthService implements ExensioTokenProvider {
    // Login: POST /v1/session/login {username, password, dbname, dbschema}
    // Logout: POST /v1/session/logout per schema
    // Token cache: ConcurrentHashMap<schema, token>
}

@Service
@ConditionalOnProperty(name="exensio.auth-mode", havingValue="OAUTH")
public class ExensioOAuthAuthService implements ExensioTokenProvider {
    // Token endpoint: POST https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token
    // Token cache: volatile CachedToken(value, expiresAt)
    // No logout (stateless OIDC tokens)
}

@Service
@ConditionalOnProperty(name="exensio.auth-mode", havingValue="SAML")
public class ExensioSamlAuthService implements ExensioTokenProvider {
    // Build AuthnRequest → Azure AD → SAML Assertion
    // Exchange at POST /v1/saml/consumer with predefined_connection
    // Token cache: volatile String + ReentrantLock double-checked locking
    // Logout: POST /v1/session/logout
    // Multi-strategy fallback: FormPost → WS-Federation → Selenium
}
```

All calling code (ExensioClient, ExensioRawSqlService, etc.) depends on the `ExensioTokenProvider` interface, so they work identically regardless of auth mode.

## SAML Architecture — Three-Strategy Fallback

The `ExensioSamlAuthService` uses a tiered fallback system to acquire SAML assertions from Azure AD. This handles diverse Azure AD configurations, including those with MFA enforcement or special network/browser requirements.

### Strategy Ordering

1. **FormPostSamlStrategy** (default, fastest)
   - POST service account credentials directly to Azure AD login endpoint
   - Parses SAMLResponse from returned HTML
   - Throws if MFA challenge detected → falls through to Strategy 2

2. **WsFederationSamlStrategy** (fallback, medium complexity)
   - Uses WS-Federation endpoint if Azure AD supports it
   - Does not require browser interaction
   - Throws `UnsupportedOperationException` if WS-Federation disabled → falls through to Strategy 3

3. **SeleniumSamlStrategy** (last resort, handles all flows)
   - Launches headless Chromium browser with Selenium WebDriver
   - Automates full Azure AD login, including MFA and CAPTCHA
   - Throws `UnsupportedOperationException` if Selenium/Chromium unavailable → gracefully skipped

### Fallback Behavior

```
ExensioSamlAuthService.getToken()
  └─ SamlAuthenticationFacade.acquireSamlAssertion()
      ├─ Strategy 1 (FormPost)
      │   ├─ success → return assertion, log at DEBUG, exit
      │   ├─ UnsupportedOperationException → skip to Strategy 2
      │   └─ other error → log warning, collect error message, try Strategy 2
      ├─ Strategy 2 (WS-Federation)
      │   ├─ success → return assertion, log at DEBUG, exit
      │   ├─ UnsupportedOperationException → skip to Strategy 3
      │   └─ other error → log warning, collect error message, try Strategy 3
      └─ Strategy 3 (Selenium)
          ├─ success → return assertion, log at DEBUG, exit
          ├─ UnsupportedOperationException → no-op (all strategies exhausted)
          └─ other error → log warning, collect error message, throw with all errors
```

### Example Error Message (All Strategies Fail)

```
ExensioAuthException: All SAML authentication strategies failed — 
  FormPostSamlStrategy: HTTP 403 MFA required; 
  WsFederationSamlStrategy: UnsupportedOperationException: WS-Federation disabled for tenant; 
  SeleniumSamlStrategy: SeleniumException: Chromium binary not found
```

### Configuration for SAML Multi-Strategy

The SAML secret in Secrets Manager controls which strategies are attempted:

| Secret Field | Effect |
|---|---|
| `sign_requests: true` | AuthnRequest XML is digitally signed (requires `sp_private_key`) |
| `sign_requests: false` | AuthnRequest sent unsigned |
| Selenium on classpath | Strategy 3 enabled; if not on classpath, silently skipped |
| `service_account_username` + `service_account_password` | Used by Strategies 1 and 2; Strategy 3 fills login form automatically |

**Selenium is optional** (`<optional>true</optional>` in pom.xml). If not included in the build, Strategy 3 is skipped gracefully and logged at DEBUG level.

### Caching and Lifecycle

- On first `getToken()` call: SAML secret loaded once from Secrets Manager → cached for process lifetime
- Subsequent calls return cached token if valid
- On HTTP 401: token invalidated → next call re-runs full SAML flow
- On shutdown: `POST /v1/session/logout` called with cached token, then cleared
- Token caching uses `volatile` field + `ReentrantLock` double-checked locking to prevent concurrent authentication storms

## Health Indicator Behavior

The `ExensioHealthIndicator` tests token acquisition for the active auth mode:

```java
@Component
public class ExensioHealthIndicator implements HealthIndicator {
    
    @Override
    public Health health() {
        try {
            String token = authService.getToken("PRODUCTION");
            return Health.up()
                .withDetail("auth_mode", getAuthMode())
                .withDetail("message", "Token acquired successfully")
                .build();
        } catch (Exception e) {
            return Health.down()
                .withDetail("auth_mode", getAuthMode())
                .withDetail("error", e.getMessage())
                .build();
        }
    }
}
```

### Health Check Examples

**SESSION Mode**:
```json
{
  "status": "UP",
  "components": {
    "exensio": {
      "status": "UP",
      "details": {
        "auth_mode": "SESSION",
        "message": "Token acquired successfully"
      }
    }
  }
}
```

**OAUTH Mode**:
```json
{
  "status": "UP",
  "components": {
    "exensio": {
      "status": "UP",
      "details": {
        "auth_mode": "OAUTH",
        "message": "Token acquired successfully (expires_in: 3599s)"
      }
    }
  }
}
```

## Migration Decision Matrix

### When to Use SESSION Mode

✅ **Use SESSION if**:
- Current production is working without issues
- No compliance requirement for centralized identity
- Simplicity is priority over advanced features
- Exensio does not support OAuth or SAML

### When to Use OAUTH Mode

✅ **Use OAUTH if**:
- Exensio confirms OAuth 2.0 / OIDC support
- Need centralized Azure AD identity management
- Service-to-service authentication is the use case
- Want stateless, self-expiring tokens
- Prefer simpler implementation than SAML

### When to Use SAML Mode

✅ **Use SAML if**:
- Exensio only supports SAML SSO (confirmed in documentation)
- Need user identity preserved in audit logs
- Require LDAP group validation
- Using Exensio cloud deployment with LDAP integration

## Migration Paths

### Path 1: SESSION → OAUTH

**Prerequisites**:
- Exensio confirms OAuth support
- Azure AD app registration complete
- OAuth secret in Secrets Manager

**Steps**:
1. Update `application.yml`: `exensio.auth-mode: OAUTH`
2. Set `EXENSIO_OAUTH_SECRET_NAME` environment variable
3. Restart application
4. Verify health check passes
5. Monitor for 1 week
6. (Optional) Remove session credentials

**Rollback**: Set `auth-mode: SESSION` and restart

**Timeline**: 1-2 weeks

**Documentation**: [OAUTH_MIGRATION.md](OAUTH_MIGRATION.md)

### Path 2: SESSION → SAML

**Prerequisites**:
- Exensio confirms SAML configuration
- Azure AD Enterprise Application configured
- SAML secret in Secrets Manager
- ExensioSamlAuthService implemented ✅

**Steps**:
1. Coordinate with Exensio team
2. Configure Azure AD Enterprise Application
3. Create `exensio/saml-credentials-{env}` secret in Secrets Manager with IdP metadata, private keys, service account credentials
4. Update `application.yml`: `exensio.auth-mode: SAML`
5. Set `EXENSIO_SAML_SECRET_NAME` environment variable
6. Restart application
7. Verify health check passes: `curl http://localhost:8080/actuator/health`
8. Monitor logs for `"Auth mode: SAML (Azure AD SSO)"` at INFO level
9. On each successful token acquisition, verify log: `"SAML token acquired (auth_mode=SAML, expiry=...)"`

**SAML Authentication Strategy Fallback**:
- If service account login requires complex MFA or CAPTCHA, the system automatically falls back through these strategies in order:
  1. FormPost (direct form POST to Azure AD)
  2. WS-Federation (if available for tenant)
  3. Selenium (headless Chrome browser automation)
- Each strategy failure is logged at WARN level
- First successful strategy returns immediately; remaining are not attempted
- If all fail, exception lists all three failure reasons

**Rollback**: Set `auth-mode: SESSION` and restart

**Timeline**: 4-6 weeks

**Documentation**: [SAML_MIGRATION.md](SAML_MIGRATION.md)

## Testing Strategy

### Unit Tests

All auth services implement the same interface, so unit tests focus on:

```java
@Test
void testTokenAcquisition() {
    String token = authService.getToken("PRODUCTION");
    assertNotNull(token);
    assertTrue(token.length() > 0);
}

@Test
void testTokenCaching() {
    String token1 = authService.getToken("PRODUCTION");
    String token2 = authService.getToken("PRODUCTION");
    assertEquals(token1, token2); // Should be cached
}

@Test
void testTokenInvalidation() {
    String token1 = authService.getToken("PRODUCTION");
    authService.invalidateToken("PRODUCTION");
    String token2 = authService.getToken("PRODUCTION");
    assertNotEquals(token1, token2); // Should be refreshed
}
```

### Integration Tests

Integration tests validate against real Exensio API:

```java
@SpringBootTest
@TestPropertySource(properties = "exensio.auth-mode=OAUTH")
class OAuthIntegrationTest {
    
    @Test
    void testOAuthTokenAcquisition() {
        // Acquire token from Azure AD
        // Call Exensio API
        // Verify response
    }
}
```

### Health Check Tests

```bash
# Check health endpoint
curl http://localhost:8080/actuator/health

# Expected: status UP with auth_mode details
```

## Troubleshooting

### Issue: Wrong auth service bean created

**Symptom**: Logs show unexpected auth mode

**Solution**:
1. Check `application.yml` or environment variable `EXENSIO_AUTH_MODE`
2. Verify Spring profile is correct
3. Check for typos (case-sensitive: `SESSION`, `OAUTH`, `SAML`)
4. Restart application

### Issue: Health check DOWN after mode switch

**Symptom**: Health endpoint returns DOWN status

**Solution**:
1. Check application logs for authentication errors
2. Verify secret exists in Secrets Manager
3. Verify IAM role has Secrets Manager access
4. Test credentials manually (Azure AD portal for OAuth/SAML)
5. Check Exensio API configuration matches auth mode

### Issue: Token acquisition fails

**Symptom**: `ExensioAuthException` during token acquisition

**Solution**:

**For SESSION**:
- Verify username/password/dbname in Secrets Manager
- Check Exensio API is reachable
- Verify credentials are correct

**For OAUTH**:
- Verify tenant_id, client_id, client_secret in Secrets Manager
- Check Azure AD service principal exists
- Verify Exensio API accepts OIDC tokens

**For SAML**:
- Verify Azure AD Enterprise Application configured
- Check SAML certificates are valid
- Verify Exensio SAML configuration matches Azure AD
- Check LDAP connectivity from Exensio to Azure AD

## Next Steps

1. **Confirm Exensio API support** with Exensio team
2. **Choose migration path** based on confirmation
3. **Plan timeline** and communicate with stakeholders
4. **Test in QA** before production migration
5. **Document rollback procedure** for team
6. **Monitor closely** for first week after migration

## Related Documentation

- Bridge Lambda authentication modes: [../../snowflake-exensio-bridge/docs/AUTHENTICATION_MODES.md](../../snowflake-exensio-bridge/docs/AUTHENTICATION_MODES.md)
- OAuth migration guide: [OAUTH_MIGRATION.md](OAUTH_MIGRATION.md)
- SAML migration guide: [SAML_MIGRATION.md](SAML_MIGRATION.md)
- Exensio API documentation: [EXENSIO_API_DOCUMENTATION.md](EXENSIO_API_DOCUMENTATION.md)
- Master authentication reference: [../../snowflake-exensio-bridge/docs/EXENSIO_AUTH_MASTER.md](../../snowflake-exensio-bridge/docs/EXENSIO_AUTH_MASTER.md)

