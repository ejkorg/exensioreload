# Monitor Table Empty Fields Fix

## Problem

The monitor table was displaying empty values (shown as "-") for the following columns:

- DEVICE
- STEP
- TESTER
- RECIPE (test_program)

These fields were being extracted during discovery but not displayed in the monitor UI.

## Root Causes

1. **Missing Database Columns**: The `step`, `tester_id`, and `test_program` columns didn't exist in the SENDER_STAGE table
2. **Missing DTO Fields**: The `StageRecordView` record was missing the `step`, `testerId`, and `testProgram` fields
3. **Mapper Not Including Fields**: The `StageRecordMapper.toView()` method wasn't populating these fields

## Solution

### 1. Database Schema Changes

Created a new Liquibase migration: `db.changelog-9.17-add-step-tester-test-program.xml`

Added three new columns to SENDER_STAGE table:

- `step` (VARCHAR(255), nullable)
- `tester_id` (VARCHAR(100), nullable)
- `test_program` (VARCHAR(255), nullable)

Created an index on `step` and `tester_id` columns for efficient filtering.

### 2. API Response DTO Update

Updated `StageRecordView` record in:
`backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/dto/StageRecordView.java`

Added three new fields:

- `String step` (position 10, after device)
- `String testerId` (position 11)
- `String testProgram` (position 12)

### 3. Mapper Update

Updated `StageRecordMapper` in:
`backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/controller/StageRecordMapper.java`

Modified the `toView()` method to:

- Extract `record.step()`, `record.testerId()`, and `record.testProgram()` from StageRecord
- Normalize them using `normalizeDisplayValue()` with "-" as fallback for null/empty values
- Pass them to the StageRecordView constructor in the correct positions

Updated both the null record case and the normal record case to include the new fields.

### 4. Changelog Registration

Added the new migration to the master changelog:
`backend/src/main/resources/db/changelog/db.changelog-1.0.xml`

Inserted the include directive:

```xml
<include file="db.changelog-9.17-add-step-tester-test-program.xml" relativeToChangelogFile="true" />
```

## Data Flow

1. **Discovery Phase**: Metadata repository queries extract device, step, tester_id, test_program from metadata views
2. **Payload Creation**: MetadataImporterService creates PayloadCandidate objects with all fields populated
3. **Database Insert**: RefDbService inserts records into SENDER_STAGE with all columns including the new ones
4. **API Response**: StageSessionService.getSessionFiles() calls RefDbService.listRecords() to fetch records
5. **Mapping**: StageRecordMapper converts StageRecord to StageRecordView, including the new fields
6. **Frontend Display**: UI receives complete data and displays device, step, tester, recipe columns

## Files Modified

1. `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/dto/StageRecordView.java`
   - Added 3 new fields to record

2. `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/controller/StageRecordMapper.java`
   - Updated toView() method to populate the 3 new fields
   - Updated null record case to initialize the 3 new fields

3. `backend/src/main/resources/db/changelog/db.changelog-9.17-add-step-tester-test-program.xml`
   - Created new Liquibase migration with 3 addColumn changesets and 1 index createIndex changeset

4. `backend/src/main/resources/db/changelog/db.changelog-1.0.xml`
   - Added include directive for the new migration

## Deployment Steps

1. Commit all code changes
2. Push to remote repository
3. On remote build node:
   ```bash
   cd backend
   mvn clean package -DskipTests
   ```
4. Deploy the new JAR
5. Restart the service (Liquibase will apply the migration automatically)
6. Verify in monitor UI that device, step, tester, recipe columns now show data instead of "-"

## Testing

After deployment, verify:

1. Discover and stage new files
2. Open monitor and click on a session
3. Confirm that DEVICE, STEP, TESTER, RECIPE columns display actual values
4. Check that existing records without these fields show "-" as fallback

## Backward Compatibility

- New columns are nullable, so existing data is preserved
- Existing records without step/tester_id/test_program will display "-" in UI
- No breaking changes to API or UI
