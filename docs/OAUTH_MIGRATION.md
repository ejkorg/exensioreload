# OAuth 2.0 Migration Guide — exensioreload

This document covers the OAuth 2.0 migration for the **exensioreload** Spring Boot backend application. For the **Snowflake-to-Exensio Bridge Lambda** migration, see [docs/OAUTH_MIGRATION.md](../../snowflake-exensio-bridge/docs/OAUTH_MIGRATION.md).

## Table of Contents

1. [Overview](#overview)
2. [Configuration Properties](#configuration-properties)
3. [Migration Steps](#migration-steps)
4. [OAuth Service Architecture](#oauth-service-architecture)
5. [Health Indicator Integration](#health-indicator-integration)
6. [Rollback Procedure](#rollback-procedure)
7. [Client Secret Rotation](#client-secret-rotation)
8. [Troubleshooting](#troubleshooting)

## Overview

The exensioreload application currently authenticates with the Exensio API using **session tokens** obtained via `/v1/session/login` with username/password. The new OAuth mode replaces this with **Azure AD client credentials flow**, which provides more secure, centrally-managed authentication.

### Key Changes

- **Configuration-driven**: Switch between SESSION and OAUTH modes via `exensio.auth-mode` property
- **No calling code changes**: ExensioClient, ExensioRawSqlService, etc. remain unchanged
- **Transparent token management**: Auth service handles caching and refresh automatically
- **Spring Bean lifecycle**: Correct auth service implementation injected automatically via `@ConditionalOnProperty`

## Configuration Properties

### Basic Properties

Add to `application.yml` or override via environment variables:

```yaml
exensio:
  enabled: true
  auth-mode: SESSION  # or OAUTH
  env: PROD           # QA or PROD
  qa-url: https://exensio-qa.example.com
  prod-url: https://exensio-prod.example.com
  dbname: exensio_db
```

### OAuth-Specific Properties

When `auth-mode=OAUTH`, add:

```yaml
exensio:
  oauth-secret-name: exensio/oauth-credentials-prod
```

This tells the application which AWS Secrets Manager secret contains the OAuth credentials.

### Full Configuration Example

```yaml
# application.yml
exensio:
  enabled: true
  auth-mode: OAUTH            # SESSION or OAUTH
  env: PROD                   # QA or PROD
  qa-url: ${EXENSIO_QA_URL}   # https://exensio-qa.example.com
  prod-url: ${EXENSIO_PROD_URL} # https://exensio-prod.example.com
  dbname: ${EXENSIO_DBNAME}   # exensio_db
  username: ${EXENSIO_USERNAME} # (ignored in OAUTH mode)
  password: ${EXENSIO_PASSWORD} # (ignored in OAUTH mode)
  oauth-secret-name: ${EXENSIO_OAUTH_SECRET_NAME}
  
  # Optional: polling and timeouts
  poll-interval-ms: 60000
  timeout-minutes: 60
  batch-size: 50
  thread-pool-size: 5
  max-concurrent-requests: 10
  retry-max-attempts: 3
  retry-base-delay-ms: 1000
  circuit-breaker-threshold: 5
  circuit-breaker-reset-ms: 60000
```

### Environment Variables

All properties can be overridden via environment variables:

```bash
# Docker or Kubernetes
export EXENSIO_AUTH_MODE=OAUTH
export EXENSIO_ENV=PROD
export EXENSIO_QA_URL=https://exensio-qa.example.com
export EXENSIO_PROD_URL=https://exensio-prod.example.com
export EXENSIO_DBNAME=exensio_db
export EXENSIO_OAUTH_SECRET_NAME=exensio/oauth-credentials-prod

java -jar exensioreload.jar
```

## Migration Steps

### Step 1: Prepare OAuth Credentials in Azure AD

1. Sign in to Azure Portal
2. Navigate to Azure AD → App registrations
3. Create or locate your Exensio service principal
4. Copy **Application (client) ID** and **Directory (tenant) ID**
5. Click **Certificates & secrets** → **New client secret**
6. Copy the secret value immediately

### Step 2: Create AWS Secrets Manager Secret

```bash
aws secretsmanager create-secret \
  --region us-east-1 \
  --name exensio/oauth-credentials-prod \
  --description "Azure AD OAuth credentials for Exensio API (production)" \
  --secret-string '{
    "tenant_id": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
    "client_id": "yyyyyyyy-yyyy-yyyy-yyyy-yyyyyyyyyyyy",
    "client_secret": "zzzzzzzz~very-long-secret-value~",
    "scope": "api://exensio-big-data-api/.default"
  }' \
  --tags Key=Environment,Value=prod Key=Application,Value=exensio-bridge
```

### Step 3: Update Application Configuration

Update `application.yml` or deployment environment variables:

```yaml
exensio:
  auth-mode: OAUTH
  oauth-secret-name: exensio/oauth-credentials-prod
```

### Step 4: Update IAM Role (if running on EC2/ECS)

If exensioreload runs on AWS infrastructure, ensure the IAM role has Secrets Manager access:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "secretsmanager:GetSecretValue",
      "Resource": "arn:aws:secretsmanager:us-east-1:ACCOUNT_ID:secret:exensio/oauth-credentials*"
    }
  ]
}
```

### Step 5: Restart Application

```bash
# Docker
docker stop exensioreload
docker run --env EXENSIO_AUTH_MODE=OAUTH ... exensioreload:latest

# Kubernetes
kubectl set env deployment/exensioreload EXENSIO_AUTH_MODE=OAUTH
kubectl rollout restart deployment/exensioreload

# Local (if running locally)
mvn spring-boot:run -Dexensio.auth-mode=OAUTH
```

### Step 6: Verify OAuth Mode Active

Check logs for OAuth initialization:

```bash
# Kubernetes logs
kubectl logs -f deployment/exensioreload | grep -i "auth"

# Docker logs
docker logs exensioreload | grep -i "auth"

# Expected output:
# [INFO] ... Auth mode: OAUTH (Azure AD client credentials)
# [INFO] ... OAuth credentials loaded from Secrets Manager
# [INFO] ... OAuth token acquired (expires_in: 3599 seconds)
```

Check Spring Boot Actuator health endpoint:

```bash
curl http://localhost:8080/actuator/health
```

Expected response:

```json
{
  "status": "UP",
  "components": {
    "exensio": {
      "status": "UP",
      "details": {
        "auth_mode": "OAUTH",
        "message": "Exensio API is reachable"
      }
    }
  }
}
```

## OAuth Service Architecture

### Service Selection

The Spring DI container automatically selects the correct auth service based on `exensio.auth-mode`:

```
@ConditionalOnProperty(name="exensio.auth-mode", havingValue="SESSION")
public class ExensioAuthService implements ExensioTokenProvider { ... }

@ConditionalOnProperty(name="exensio.auth-mode", havingValue="OAUTH")
public class ExensioOAuthAuthService implements ExensioTokenProvider { ... }
```

### Token Lifecycle

Both implementations provide the same interface:

```java
public interface ExensioTokenProvider {
    String getToken(String schema);
    void invalidateToken(String schema);
    void shutdown();
}
```

**Session Mode** (`ExensioAuthService`):
1. POST `/v1/session/login` with username/password
2. Cache token per schema
3. On HTTP 401: invalidate and re-login
4. On shutdown: POST `/v1/session/logout`

**OAuth Mode** (`ExensioOAuthAuthService`):
1. POST to Azure AD token endpoint with client credentials
2. Cache token with expiry timer
3. Proactively refresh at 60 seconds before expiry
4. On HTTP 401: invalidate and re-acquire
5. On shutdown: no-op (OIDC tokens are stateless)

### Configuration Bean

```java
@Configuration
@EnableConfigurationProperties(ExensioProperties.class)
public class ExensioConfig {
    
    @Bean
    public ExensioTokenProvider exensioAuthService(
            ExensioProperties props,
            SecretsManagerClient secretsManager) {
        if ("OAUTH".equalsIgnoreCase(props.getAuthMode())) {
            return new ExensioOAuthAuthService(props, secretsManager);
        }
        return new ExensioAuthService(props);
    }
}
```

### Thread Safety

Both implementations use **double-checked locking** for thread-safe token caching:

```java
private volatile String cachedToken;
private final ReentrantLock lock = new ReentrantLock();

public String getToken(String schema) {
    String token = this.cachedToken;
    if (token != null && isValid(token)) {
        return token;
    }
    lock.lock();
    try {
        token = this.cachedToken;
        if (token != null && isValid(token)) {
            return token;
        }
        return acquireNewToken();
    } finally {
        lock.unlock();
    }
}
```

## Health Indicator Integration

The `ExensioHealthIndicator` automatically tests token acquisition and reports status via Spring Boot Actuator.

### Health Endpoint

```bash
curl http://localhost:8080/actuator/health
```

### Response Examples

**Healthy (SESSION mode):**
```json
{
  "status": "UP",
  "components": {
    "exensio": {
      "status": "UP",
      "details": {
        "auth_mode": "SESSION",
        "message": "Session token acquired successfully"
      }
    }
  }
}
```

**Healthy (OAUTH mode):**
```json
{
  "status": "UP",
  "components": {
    "exensio": {
      "status": "UP",
      "details": {
        "auth_mode": "OAUTH",
        "message": "OAuth token acquired successfully (expires_in: 3599 seconds)"
      }
    }
  }
}
```

**Unhealthy (OAuth credentials invalid):**
```json
{
  "status": "DOWN",
  "components": {
    "exensio": {
      "status": "DOWN",
      "details": {
        "auth_mode": "OAUTH",
        "error": "AADSTS700016: Application with identifier '<client_id>' was not found in the directory"
      }
    }
  }
}
```

### Monitoring

Use Spring Boot Actuator metrics to monitor auth performance:

```bash
# Get all metrics
curl http://localhost:8080/actuator/metrics

# Get exensio-specific metrics (if enabled)
curl http://localhost:8080/actuator/metrics/exensio.auth.token.acquisitions
curl http://localhost:8080/actuator/metrics/exensio.auth.token.cache.hits
```

## Rollback Procedure

If issues arise after switching to OAuth, rollback is straightforward:

### Option 1: Configuration Change (Fastest)

```yaml
# In application.yml
exensio:
  auth-mode: SESSION
```

Or via environment variable:

```bash
export EXENSIO_AUTH_MODE=SESSION
kubectl set env deployment/exensioreload EXENSIO_AUTH_MODE=SESSION
kubectl rollout restart deployment/exensioreload
```

Wait for pod restart and verify:

```bash
kubectl logs -f deployment/exensioreload | grep -i "auth"
# Expected: "Auth mode: SESSION (username/password)"
```

### Option 2: Full Rollback with Code

If configuration change doesn't work, revert to previous Docker image:

```bash
# Kubernetes
kubectl set image deployment/exensioreload \
  exensioreload=exensioreload:v1.4.0  # Previous version

kubectl rollout restart deployment/exensioreload
```

### Validation

After rollback, verify:

1. Application starts without errors:
   ```bash
   kubectl logs -f deployment/exensioreload | grep -E "ERROR|Exception"
   ```

2. Health check passes:
   ```bash
   curl http://localhost:8080/actuator/health
   # Expect status: UP
   ```

3. Exensio lookups work:
   ```bash
   # Check application logs for successful lookups
   kubectl logs deployment/exensioreload | grep -i "lot"
   ```

## Client Secret Rotation

Azure AD client secrets should be rotated periodically (recommended: annually or after security incident).

### Rotation Process

#### 1. Generate New Secret in Azure Portal

1. Sign in to Azure Portal
2. Go to Azure AD → App registrations → your Exensio app
3. Click **Certificates & secrets**
4. Under **Client secrets**, click **New client secret**
5. Enter description (e.g., "rotation-2026")
6. Select expiry (12 months recommended)
7. Click **Add**
8. **Copy the secret immediately** (not retrievable later)

#### 2. Update AWS Secrets Manager

```bash
aws secretsmanager update-secret \
  --region us-east-1 \
  --secret-id exensio/oauth-credentials-prod \
  --secret-string '{
    "tenant_id": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
    "client_id": "yyyyyyyy-yyyy-yyyy-yyyy-yyyyyyyyyyyy",
    "client_secret": "<NEW_SECRET_VALUE>",
    "scope": "api://exensio-big-data-api/.default"
  }'
```

#### 3. Restart exensioreload

```bash
# Kubernetes: force pod restart to reload secret
kubectl rollout restart deployment/exensioreload

# Docker: restart container
docker restart exensioreload
```

#### 4. Monitor Startup Logs

```bash
kubectl logs -f deployment/exensioreload | grep -i "oauth\|credentials"
# Expected: "OAuth token acquired" with new credentials
```

#### 5. Delete Old Secret in Azure Portal

Once new pods are running successfully (5-10 minutes):

1. Go to Azure Portal → Azure AD → App registrations → your app
2. Click **Certificates & secrets**
3. Find the old client secret
4. Click the three dots → **Delete**
5. Confirm

## Troubleshooting

### Issue: Application fails to start with OAUTH mode

**Error:**
```
ERROR - Failed to initialize OAuth credentials: Secret not found
ExensioAuthException: Secrets Manager secret 'exensio/oauth-credentials-prod' not found
```

**Solution:**

1. Verify secret exists:
   ```bash
   aws secretsmanager describe-secret \
     --region us-east-1 \
     --secret-id exensio/oauth-credentials-prod
   ```

2. Verify IAM role has permission:
   ```bash
   aws iam get-role-policy \
     --role-name exensioreload-role \
     --policy-name SecretsManagerAccess
   ```

3. Verify configuration property is set:
   ```bash
   kubectl get deployment exensioreload -o yaml | grep EXENSIO_OAUTH_SECRET_NAME
   ```

### Issue: Health check shows DOWN with OAuth mode

**Error:**
```
"status": "DOWN"
"error": "AADSTS700016: Application with identifier was not found"
```

**Solution:**

1. Verify OAuth credentials in secret:
   ```bash
   aws secretsmanager get-secret-value \
     --region us-east-1 \
     --secret-id exensio/oauth-credentials-prod
   ```

2. Verify service principal exists in Azure AD:
   - Portal → Azure AD → App registrations
   - Search for your client_id
   - If not found, create or recreate

3. Verify Azure AD tenant ID:
   ```bash
   az account show --query tenantId
   ```

4. Test locally:
   ```bash
   mvn spring-boot:run \
     -Dexensio.auth-mode=OAUTH \
     -Dexensio.oauth-secret-name=exensio/oauth-credentials-prod
   
   # Check logs for specific error
   ```

### Issue: Token acquisition very slow (>10 seconds)

**Symptom:**
```
WARN - OAuth token acquisition took 12345 ms (slow)
```

**Causes:**
- High latency to Azure AD
- Network issues
- Azure AD load

**Solution:**

1. Check Azure AD service status:
   https://status.azure.com

2. Monitor token acquisition time:
   ```bash
   kubectl logs deployment/exensioreload | grep "token acquisition"
   ```

3. If consistently slow, consider:
   - Increasing cache TTL (default: 60 min)
   - Using Azure AD regional endpoint

### Issue: Rollback stuck in OAuth mode

**Problem:**
```
Changed EXENSIO_AUTH_MODE=SESSION but logs still show OAUTH
```

**Solution:**

1. Force pod restart:
   ```bash
   kubectl rollout restart deployment/exensioreload
   kubectl rollout status deployment/exensioreload
   ```

2. Verify environment variable updated:
   ```bash
   kubectl get pods -o jsonpath='{.items[0].spec.containers[0].env[?(@.name=="EXENSIO_AUTH_MODE")].value}'
   ```

3. Check logs:
   ```bash
   kubectl logs -f deployment/exensioreload | head -50
   # Should show "Auth mode: SESSION" near start
   ```

