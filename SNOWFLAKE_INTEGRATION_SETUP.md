# Snowflake Integration Setup for exensioreload

## Overview

Added ODBC-style Snowflake connection support to exensioreload, mirroring the proven setup from xfcs-reloader. This enables the lot pre-flight verification feature to use fast Snowflake queries for lot existence checking.

## Changes Made

### 1. Backend Dependencies (pom.xml)

Added Snowflake JDBC driver with Maven unpacking configuration to handle native libraries:

```xml
<!-- Snowflake JDBC for lot pre-check verification -->
<dependency>
    <groupId>net.snowflake</groupId>
    <artifactId>snowflake-jdbc</artifactId>
    <version>3.27.1</version>
</dependency>
```

Added build plugin configuration to unpack native libraries:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
            <configuration>
                <!-- ... existing config ... -->
                <!-- Snowflake JDBC requires unpacking to avoid native library issues -->
                <requiresUnpack>
                    <dependency>
                        <groupId>net.snowflake</groupId>
                        <artifactId>snowflake-jdbc</artifactId>
                    </dependency>
                </requiresUnpack>
            </configuration>
        </plugin>
    </plugins>
</build>
```

**Rationale**: The Snowflake JDBC driver contains native libraries that must be unpacked during the build process. Without this configuration, the driver may fail to load at runtime.

### 2. Configuration (application.yml)

Added Snowflake configuration section using environment variables:

```yaml
# Snowflake JDBC connection for lot pre-flight verification
# Used by ExensioPreCheckService for fast lot existence validation
# Credentials provided via environment variables (ODBC-style configuration)
snowflake:
  url: ${SNOW_URL:}
  username: ${SNOW_USER:}
  password: ${SNOW_PASS:}
  driver-class-name: net.snowflake.client.jdbc.SnowflakeDriver
  precheck-row-limit: ${SNOW_PRECHECK_ROW_LIMIT:10000}
```

**Configuration Parameters:**

- `SNOW_URL`: Snowflake connection URL (e.g., `jdbc:snowflake://account.region.snowflakecomputing.com`)
- `SNOW_USER`: Snowflake username
- `SNOW_PASS`: Snowflake password
- `SNOW_PRECHECK_ROW_LIMIT`: Maximum rows to fetch per query (default: 10000)

## Comparison: exensioreload vs xfcs-reloader

| Aspect                         | exensioreload              | xfcs-reloader              |
| ------------------------------ | -------------------------- | -------------------------- |
| **Snowflake Driver**           | Added (3.27.1)             | Already present (3.27.1)   |
| **Configuration Method**       | YAML environment variables | YAML environment variables |
| **Connection Type**            | Primary path (fast)        | Primary path (fast)        |
| **Fallback Path**              | Exensio HTTP API           | Exensio HTTP API           |
| **Default PRECHECK_ROW_LIMIT** | 10000                      | 10000                      |
| **Build Configuration**        | Added `requiresUnpack`     | Already configured         |

## How It Works

When `ExensioPreCheckService` is called (from lot verification feature):

1. **Try Snowflake Path (Fast)**:
   - Use JDBC connection to Snowflake database
   - Execute parameterized SQL query to check lot existence
   - Return results directly (< 1 second for typical queries)

2. **Fallback to HTTP Path (Reliable)**:
   - If Snowflake unavailable or query fails
   - Call Exensio HTTP raw-SQL API
   - Handle authentication with token refresh
   - Return results via HTTP

3. **Error Handling**:
   - Both paths support soft-error pattern (return error field instead of throwing)
   - If both paths fail, return safe default (empty lists)
   - Comprehensive logging at each step

## Environment Variable Setup

Required environment variables for production:

```bash
# Snowflake connection (for lot pre-check verification)
SNOW_URL=jdbc:snowflake://account.region.snowflakecomputing.com
SNOW_USER=exensio_user
SNOW_PASS=secure_password_here
SNOW_PRECHECK_ROW_LIMIT=10000

# Exensio fallback (HTTP API)
EXENSIO_ENABLED=true
EXENSIO_ENV=PROD
EXENSIO_QA_URL=https://exnqa.onsemi.com/api
EXENSIO_PROD_URL=https://api-prod.canyon.aws.pdf.com/api
EXENSIO_USERNAME=exensio_api_user
EXENSIO_PASSWORD=exensio_api_password
```

## ODBC-Style Configuration

This setup follows an ODBC-style pattern where:

1. **DSN-like Configuration**: Environment variables act as a "data source name" defining the connection
2. **Secrets Management**: Credentials stored in environment, not in code
3. **Easy Switching**: Change `SNOW_URL` to point to different Snowflake accounts/environments
4. **Fallback Strategy**: Secondary connection path (HTTP) ensures reliability

## Integration with Lot Verification Feature

The Snowflake connection is used by:

- **ExensioPreCheckService**: Core verification service
- **LotVerificationDialogComponent**: Displays results
- **StepperComponent**: Integrates verification into discovery workflow
- **SenderController**: Endpoint that wires frontend to backend

## Testing the Setup

1. **Verify Dependencies**: Check Maven resolves Snowflake JDBC correctly
2. **Verify Build**: Ensure `requiresUnpack` configuration handles native libraries
3. **Runtime Test**: Set environment variables and start backend
4. **Integration Test**: Run lot verification from frontend to confirm Snowflake path works
5. **Fallback Test**: Stop/block Snowflake to confirm HTTP fallback works

## Next Steps

1. Set `SNOW_URL`, `SNOW_USER`, `SNOW_PASS` in your deployment environment
2. Implement `ExensioPreCheckService` in Java (copy from xfcs-reloader with adaptations)
3. Add `SenderController` endpoint for `/api/senders/{id}/verify-lots`
4. Test lot verification workflow end-to-end
5. Monitor Snowflake query performance and adjust `SNOW_PRECHECK_ROW_LIMIT` if needed

## Files Modified

- `exensioreload/backend/pom.xml` - Added Snowflake JDBC dependency and build config
- `exensioreload/backend/src/main/resources/application.yml` - Added snowflake configuration section

## References

- xfcs-reloader implementation: `xfcs-reloader/backend/pom.xml` and `application.yml`
- Lot verification feature: `exensioreload/.kiro/specs/lot-existence-verification/`
- Backend service location: `exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/`
