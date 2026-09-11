# Pipeline Orchestration Database INSERT Fix

## Problem

When the pipeline orchestration feature was added, new columns were added to the SENDER_STAGE table via Liquibase migration:

- `current_pipeline_stage`
- `completed_pipeline_stages`
- `stage_metadata`
- `pipeline_started_at`
- `last_stage_check_at`

However, the INSERT statements in `RefDbService.stagePayloads()` and `RefDbService.processBatchFallback()` were updated to include these column names but with incorrect parameter handling, causing:

```
No value specified for parameter 19
```

## Root Cause

The INSERT statements included the 5 new pipeline columns in the column list but only set 18 parameters (matching the original 26 columns). The mismatch occurred because:

1. The column list was extended to include the 5 new columns (now 31 total columns)
2. The VALUES clause was updated to include placeholders for all columns
3. But the parameter-setting code (ps.setXxx() calls) still only set 18 parameters
4. The new pipeline columns were intended to be NULL on initial insert

## Solution

Changed both INSERT statements to set the 5 new pipeline columns to NULL literals instead of parameter placeholders:

### File: `backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/RefDbService.java`

**Method 1: `stagePayloads()` (line ~237-239)**

Changed the VALUES clause from:

```sql
VALUES (..., ?, NULL, NULL, NULL, NULL, NULL)
```

To:

```sql
VALUES (..., NULL, NULL, NULL, NULL, NULL)
```

The last 5 columns are now hardcoded to NULL instead of placeholders, because:

- Pipeline orchestration sets these values later via `PipelineStatusTracker.markStageComplete()`
- Initial record insertion should not set pipeline state (it starts as NULL)
- This maintains exactly 18 parameters matching the ps.setXxx() calls in the loop

**Method 2: `processBatchFallback()` (line ~407-408)**

Updated the fallback INSERT to match the main one (it previously had fewer columns in the column list).

## Testing

These changes should be tested on the remote build node:

```bash
mvn clean package -DskipTests
```

Then run integration tests:

```bash
mvn test -Dtest=RefDbServiceTest
```

## Deployment

1. Commit the code changes
2. Push to remote repository
3. Build on remote node: `mvn clean package -DskipTests`
4. Deploy the new JAR
5. Verify via logs that records are being inserted successfully

The Liquibase migration (db.changelog-13.0-pipeline-orchestration.xml) will automatically apply the schema changes if needed.

## Verification

After deployment, verify the fix by checking:

1. Records appear in SENDER_STAGE table
2. No SQL parameter errors in application logs
3. Pipeline orchestration correctly sets pipeline state during processing
