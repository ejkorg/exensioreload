# Quick Fix Reference - Systemd Service Configuration

## The One-Line Fix

Change this line in your systemd service file:

```diff
- Environment="CP_ES_URL=https://elastic-mosdata-prod-uswest2.es.privatelink.westus2.azure.elastic-cloud.com/logs*dataport*/_search"
+ Environment="CP_ES_URL=https://elastic-mosdata-prod-uswest2.es.privatelink.westus2.azure.elastic-cloud.com"
```

**That's it!** Remove `/logs*dataport*/_search` from the URL.

## Step-by-Step

1. **Locate your service file:**

   ```bash
   # Find the service file
   systemctl cat exensio-reload | head -20
   # Usually at: /etc/systemd/system/exensio-reload.service
   ```

2. **Edit the file:**

   ```bash
   sudo nano /etc/systemd/system/exensio-reload.service
   ```

3. **Find the `CP_ES_URL` line and edit it:**

   Line 18 in your file should be:

   ```ini
   Environment="CP_ES_URL=https://elastic-mosdata-prod-uswest2.es.privatelink.westus2.azure.elastic-cloud.com"
   ```

4. **Reload and restart:**

   ```bash
   sudo systemctl daemon-reload
   sudo systemctl restart exensio-reload
   ```

5. **Verify it worked:**

   ```bash
   # Check logs
   sudo journalctl -u exensio-reload -f

   # You should see:
   # Elasticsearch Configuration: url=https://elastic-mosdata-prod-uswest2.es.privatelink.westus2.azure.elastic-cloud.com...
   # Elasticsearch configuration validated successfully
   ```

## What Was Wrong

The Elasticsearch URL included the full path:

```
https://elastic-mosdata-prod-uswest2.es.privatelink.westus2.azure.elastic-cloud.com/logs*dataport*/_search
                                                                                    ^^^^^^^^^^^^^^^^^^^^^^
                                                                             This part should NOT be here
```

The backend automatically appends these based on configuration:

- Index pattern: `logs*dataport*` (from `cp.elasticsearch.index-pattern` in application.yml)
- Search endpoint: `/_search`

So the full URL becomes: `{base-url}/{index-pattern}/_search`

## What's Correct

The service file now has the right format:

```ini
# ✅ CORRECT - Base URL only
Environment="CP_ES_URL=https://elastic-mosdata-prod-uswest2.es.privatelink.westus2.azure.elastic-cloud.com"

# ✅ CORRECT - Exensio base URL only
Environment="EXENSIO_PROD_URL=https://api-prod.canyon.aws.pdf.com/api"
```

## After the Fix

The dashboard will now show:

- **Integrations** card displays actual connection status
- **Elasticsearch**: 🟢 Connected (or 🔴 with error details)
- **Exensio**: 🟢 Connected (or 🔴 with error details)
