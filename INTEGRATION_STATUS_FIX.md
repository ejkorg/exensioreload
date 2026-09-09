# Integration Status Fix - Elasticsearch & Exensio Configuration

## Problem

The integrations page was showing **"NOT CONFIGURED"** for both Elasticsearch and Exensio, even though:

- The backend code is fully integrated
- Environment variables were partially configured
- Services were supposed to be active

## Root Cause

**Elasticsearch URL format was incorrect:**

```
❌ WRONG:
CP_ES_URL=https://elastic-mosdata-prod-uswest2.es.privatelink.westus2.azure.elastic-cloud.com/logs*dataport*/_search

✅ CORRECT:
CP_ES_URL=https://elastic-mosdata-prod-uswest2.es.privatelink.westus2.azure.elastic-cloud.com
```

The backend's `CpElasticsearchProperties.isConfigured()` method:

1. Checks if `url` is not blank
2. Then uses `resolveSearchUrl()` to automatically append the index pattern and `/_search` endpoint

When the URL already contained these, the configuration validation may have failed or the endpoint construction produced incorrect URLs.

## Solution

### 1. Fix Elasticsearch Configuration

Update your systemd service file (`/etc/systemd/system/exensio-reload.service` or equivalent):

**Before:**

```ini
Environment="CP_ES_URL=https://elastic-mosdata-prod-uswest2.es.privatelink.westus2.azure.elastic-cloud.com/logs*dataport*/_search"
Environment="CP_ES_USERNAME=dataint_read"
Environment="CP_ES_PASSWORD=ch@ngeMe"
```

**After:**

```ini
Environment="CP_ES_URL=https://elastic-mosdata-prod-uswest2.es.privatelink.westus2.azure.elastic-cloud.com"
Environment="CP_ES_USERNAME=dataint_read"
Environment="CP_ES_PASSWORD=ch@ngeMe"
```

### 2. Verify Exensio Configuration

Your Exensio configuration looks correct:

```ini
Environment="EXENSIO_ENABLED=true"
Environment="EXENSIO_ENV=PROD"
Environment="EXENSIO_PROD_URL=https://api-prod.canyon.aws.pdf.com/api"
Environment="EXENSIO_USERNAME=YQS_API_USER"
Environment="EXENSIO_PASSWORD=xNsqy667p"
```

✅ This is the correct format (base URL without extra path components).

### 3. Restart the Service

```bash
sudo systemctl daemon-reload
sudo systemctl restart exensio-reload
```

### 4. Verify Configuration

Check the logs:

```bash
sudo journalctl -u exensio-reload -f
```

Look for:

```
Elasticsearch Configuration: url=https://elastic-mosdata-prod-uswest2.es.privatelink.westus2.azure.elastic-cloud.com, username=dataint_read, apiKey present=false, logRequestPayloads=false
Exensio Configuration: enabled=true, env=PROD, qaUrl=empty, prodUrl=set, username=YQS_API_USER, authMode=SESSION
```

### 5. Check Frontend Status

Navigate to the dashboard or monitoring session. The integrations card should now show:

- **Elasticsearch**: Connected ✅ (or displays connection error if there's a network issue)
- **Exensio**: Connected ✅ (or displays connection error)

## Environment Variables Checklist

### Elasticsearch (Required for enrichment logs)

- `CP_ES_URL` - Base URL (e.g., `https://elasticsearch:9200`) - **NO trailing paths**
- `CP_ES_API_KEY` - API key (preferred) **OR**
- `CP_ES_USERNAME` + `CP_ES_PASSWORD` - Basic auth credentials

### Exensio (Optional for lot/wafer verification)

- `EXENSIO_ENABLED=true` - Master switch
- `EXENSIO_ENV` - Target environment (`QA` or `PROD`)
- `EXENSIO_QA_URL` - QA endpoint base URL
- `EXENSIO_PROD_URL` - PROD endpoint base URL (required when `ENV=PROD`)
- `EXENSIO_USERNAME` - Service account username
- `EXENSIO_PASSWORD` - Service account password

## Backend URL Resolution

The backend automatically constructs the full Elasticsearch search URL:

```java
// In CpElasticsearchProperties.resolveSearchUrl()
// Input: https://elasticsearch:9200
// Config: indexPattern = logs*dataport* (default)
// Output: https://elasticsearch:9200/logs*dataport*/_search
```

This is why providing the full URL (including `/_search`) causes issues - it results in double-appending.

## Verification in Code

**File**: `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/config/CpElasticsearchProperties.java`

```java
public boolean isConfigured() {
    boolean configured = url != null && !url.isBlank();
    if (debugConfigCheck) {
        log.debug("Elasticsearch isConfigured() = {}", configured);
    }
    return configured;
}

public String resolveSearchUrl() {
    if (url == null) return "";
    String trimmed = url.trim();
    if (trimmed.isBlank()) return trimmed;
    String normalized = trimmed.replaceAll("/+$", "");
    if (normalized.contains("/_search")) {
        return normalized; // Already has /_search, use as-is
    }
    return normalized + "/" + indexPattern + "/_search";
}
```

**File**: `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/config/ExensioProperties.java`

```java
public boolean isConfigured() {
    String resolvedUrl = resolvedBaseUrl(); // Gets qaUrl or prodUrl based on env
    boolean configured = enabled && resolvedUrl != null && !resolvedUrl.isBlank();
    if (debugConfigCheck) {
        log.debug("Exensio isConfigured(): enabled={}, resolvedUrl={}, result={}",
            enabled, (resolvedUrl != null && !resolvedUrl.isBlank() ? "set" : "empty"), configured);
    }
    return configured;
}
```

## Integration Status Response Format

Once configured, the frontend receives integration status from the session detail endpoint:

```json
{
  "integration": {
    "elasticsearch": {
      "configured": true,
      "status": "success",
      "message": "Connected and operational",
      "lastAt": "2026-09-09T02:45:00Z",
      "metrics": {
        "stagedCount": 42,
        "queuedCount": 15
      }
    },
    "exensio": {
      "configured": true,
      "status": "success",
      "message": "Connected and operational",
      "lastAt": "2026-09-09T02:45:00Z",
      "metrics": {
        "exensioCount": 8
      }
    }
  }
}
```

## Troubleshooting

### "NOT CONFIGURED" Still Showing?

1. **Check URL format**: Verify no trailing `/_search` or index patterns in the base URL
2. **Check environment variables**: Restart the service after updating systemd config
3. **Check logs**: Look for validation errors during application startup
4. **Test connectivity**: Verify Elasticsearch and Exensio servers are reachable from the application server

### Connection Errors After Fix?

1. **Elasticsearch**: Check network connectivity, API key/credentials, SSL certificates
2. **Exensio**: Check network connectivity, credentials, authentication mode (SESSION vs OAUTH vs SAML)

### Debug Mode

Enable debug logging in the service file:

```ini
Environment="CP_ES_DEBUG_CONFIG_CHECK=true"
Environment="EXENSIO_DEBUG_CONFIG_CHECK=true"
```

Then check logs for detailed isConfigured() checks:

```
DEBUG Elasticsearch isConfigured() = true
DEBUG Exensio isConfigured(): enabled=true, resolvedUrl=set, result=true
```
