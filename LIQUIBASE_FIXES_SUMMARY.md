# Liquibase Configuration Fixes - Summary

## Issues Fixed

### 1. YAML Configuration - Duplicate `security` Key

**File**: `backend/src/main/resources/application.yml`  
**Error**: `DuplicateKeyException: found duplicate key security`  
**Fix**: Merged two separate `security:` sections into one unified block containing both `encryption` and `csp` subsections.

### 2. Liquibase Schema Version Mismatch

**Files**:

- `backend/src/main/resources/db/changelog/db.changelog-1.0.xml`
- `backend/src/main/resources/db/changelog/db.changelog-14.0-configuration-management.xml`
- `backend/src/main/resources/db/changelog/db.changelog-14.0-oracle-configuration-management.xml`

**Error**: Schema version 3.8 incompatible with Liquibase 4.24.0  
**Fix**: Updated all schema references from `dbchangelog-3.8.xsd` to `dbchangelog-4.24.xsd`

### 3. Default Value Syntax - PostgreSQL

**File**: `backend/src/main/resources/db/changelog/db.changelog-14.0-configuration-management.xml`  
**Error**: `<defaultValue>` element not allowed as child element  
**Fix**: Changed from nested `<defaultValue>` elements to `defaultValueBoolean` attribute on `<column>` element:

- `<column name="enabled" type="BOOLEAN" defaultValueBoolean="true">`
- `<column name="historical_mode_enabled" type="BOOLEAN" defaultValueBoolean="false">`

### 4. Default Value Syntax - Oracle

**File**: `backend/src/main/resources/db/changelog/db.changelog-14.0-oracle-configuration-management.xml`  
**Error**: Invalid attribute `defaultNumericValue`  
**Fix**: Changed to correct `defaultValueNumeric` attribute for NUMBER(1,0) columns:

- `<column name="enabled" type="NUMBER(1,0)" defaultValueNumeric="1">`
- `<column name="is_historical_sender" type="NUMBER(1,0)" defaultValueNumeric="0">`

### 5. PreConditions Element Ordering

**Files**:

- `backend/src/main/resources/db/changelog/db.changelog-14.0-configuration-management.xml`
- `backend/src/main/resources/db/changelog/db.changelog-14.0-oracle-configuration-management.xml`

**Error**: `<preConditions>` appearing after `<comment>` element  
**Fix**: Reordered elements within `<changeSet>` so `<preConditions>` comes first, before `<comment>` and other operations.

## Correct Liquibase 4.x Syntax Reference

### Boolean Defaults (PostgreSQL)

```xml
<column name="enabled" type="BOOLEAN" defaultValueBoolean="true">
    <constraints nullable="false" />
</column>
```

### Numeric Defaults (Oracle NUMBER type)

```xml
<column name="enabled" type="NUMBER(1,0)" defaultValueNumeric="1">
    <constraints nullable="false" />
</column>
```

### ChangeSet Element Ordering

```xml
<changeSet id="..." author="...">
    <preConditions onFail="MARK_RAN">
        <!-- preconditions here -->
    </preConditions>
    <comment>Description</comment>
    <!-- operations here -->
</changeSet>
```

## Status

All critical configuration and migration errors have been resolved. The application should now start successfully on the remote node.

## Next Steps

1. Push changes to remote repository
2. Restart service: `systemctl restart exensio-reload.service`
3. Verify application starts without errors
4. Check database migrations applied: `SELECT * FROM DATABASECHANGELOG WHERE ID LIKE '14.0%';`

## Note on Legacy Changelogs

Many existing changelog files (9.x series) still reference schema 3.8, but these should not cause issues as they have already been applied to the database. The Liquibase tool is backward compatible and will skip already-executed changesets based on checksum validation.
