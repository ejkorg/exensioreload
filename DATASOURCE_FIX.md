# DataSource Configuration Fix

**Date**: July 3, 2026  
**Issue**: Application using Snowflake as primary datasource instead of Oracle  
**Status**: ✅ FIXED

---

## Problem

After adding Snowflake JDBC integration for lot verification, the application started using Snowflake as the primary datasource for JPA/Hibernate instead of Oracle. This caused errors:

```
SQL compilation error: Object 'SENDER_QUEUE' does not exist or not authorized.
```

The error showed that:

- Hibernate was trying to query application tables (SENDER_QUEUE, REFRESH_TOKENS)
- But using Snowflake JDBC driver instead of Oracle driver
- Snowflake read-only account doesn't have these tables

---

## Root Cause

Spring Boot's datasource auto-configuration was ambiguous when multiple datasource beans existed:

1. **Snowflake DataSource**: Created by `SnowflakeDataSourceConfig`
   - Bean name: `snowflakeDataSource`
   - Not marked as `@Primary`
   - Intended for secondary use only (lot verification)

2. **Oracle DataSource**: Auto-configured from `spring.datasource.*` properties
   - Should be primary datasource for JPA/Hibernate
   - **BUT**: Not explicitly marked as `@Primary`

Without an explicit `@Primary` annotation, Spring Boot could not determine which datasource to use for JPA/Hibernate, and incorrectly chose Snowflake.

---

## Solution

Created `DataSourceConfig.java` to explicitly mark Oracle as the primary datasource:

```java
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource")
    public DataSourceProperties primaryDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    public DataSource primaryDataSource(DataSourceProperties primaryDataSourceProperties) {
        return primaryDataSourceProperties.initializeDataSourceBuilder().build();
    }
}
```

This configuration:

- Reads `spring.datasource.*` properties from YAML (Oracle connection)
- Marks the Oracle datasource as `@Primary`
- Ensures JPA/Hibernate/Liquibase use Oracle, not Snowflake
- Allows `snowflakeDataSource` bean to exist as a secondary datasource

---

## Files Modified

### Created

- `exensioreload/backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/config/DataSourceConfig.java`

### Updated (comments only)

- `exensioreload/backend/src/main/resources/application.yml`

---

## Configuration Architecture

### Primary DataSource (Oracle)

- **Purpose**: Application tables (REFRESH_TOKENS, SENDER_QUEUE, users, etc.)
- **Configuration**: `spring.datasource.*` in `application-onsemi-oracle.yml`
- **Managed by**: Spring Boot auto-configuration + `DataSourceConfig` (marked @Primary)
- **Used by**: JPA/Hibernate, Spring Data repositories, Liquibase

### Secondary DataSource (Snowflake)

- **Purpose**: Lot pre-flight verification queries (read-only)
- **Configuration**: `snowflake.*` in `application.yml`
- **Managed by**: `SnowflakeDataSourceConfig` (conditional, not primary)
- **Used by**: `ExensioPreCheckService` only (via @Qualifier)

### Tertiary Connection (Exensio HTTP)

- **Purpose**: Fallback for lot verification when Snowflake unavailable
- **Configuration**: `exensio.*` in YAML
- **Managed by**: `ExensioAuthService` + `ExensioPreCheckService`
- **Used by**: `ExensioPreCheckService` fallback path

---

## Testing Verification

After this fix, verify:

1. **Application starts successfully**

   ```bash
   sudo systemctl restart exensio-reload
   sudo systemctl status exensio-reload
   ```

2. **Oracle datasource is primary**
   - Check logs for Oracle connection messages
   - No more "SENDER_QUEUE does not exist" errors
   - Application tables (REFRESH_TOKENS) are accessible

3. **Snowflake datasource is secondary**
   - Check logs for "Creating secondary Snowflake datasource" message
   - Snowflake connection created but NOT used by Hibernate
   - Lot verification uses Snowflake correctly

4. **Scheduled tasks work**
   - `CompletionNotificationService` queries work (uses Oracle)
   - `SenderService.scheduledRun()` queries work (uses Oracle)
   - No transaction rollback errors

---

## Comparison with xfcs-reloader

| Aspect                  | xfcs-reloader         | exensioreload (before) | exensioreload (after) |
| ----------------------- | --------------------- | ---------------------- | --------------------- |
| Primary DataSource      | Oracle (H2 in dev)    | ❌ Ambiguous           | ✅ Oracle             |
| @Primary annotation     | ✅ Yes                | ❌ No                  | ✅ Yes                |
| Snowflake DataSource    | Secondary (qualified) | Secondary (qualified)  | Secondary (qualified) |
| DataSourceConfig exists | ✅ Yes                | ❌ No                  | ✅ Yes                |

---

## Key Lessons

1. **Always mark primary datasource explicitly** when multiple datasources exist
2. **Use @Qualifier** for secondary datasources to prevent ambiguity
3. **Use custom property namespaces** (like `snowflake.*`) to prevent auto-configuration conflicts
4. **Test with all scheduled tasks** to ensure datasource routing is correct
5. **Match proven patterns** from similar applications (xfcs-reloader)

---

## References

- Spring Boot DataSource Configuration: https://docs.spring.io/spring-boot/reference/data/sql.html
- Multiple DataSources: https://docs.spring.io/spring-boot/how-to/data-access.html#howto.data-access.configure-two-datasources
- xfcs-reloader implementation: `xfcs-reloader/backend/src/main/java/com/onsemi/cim/apps/exensio/xfcsreloader/config/DataSourceConfig.java`

---

## Next Steps

1. ✅ Restart application
2. ✅ Verify Oracle connection works
3. ✅ Verify Snowflake secondary connection works
4. ✅ Test lot verification feature end-to-end
5. ✅ Monitor scheduled tasks (completion check, sender dispatch)
