# Wafer-Level Preflight Check - Test Scenarios

## Test Scenario 1: Class 1 (Probe) - Lot Only

**Input:**

- Data Type: Probe (Class 1, pgc_key=1)
- Lot: `LOT12345`
- Wafer: (empty)
- Preflight Check: Enabled

**Expected Behavior:**

- Backend detects wafer-level class with no wafer filter
- SQL query returns all wafers for LOT12345 from schema
- Response includes:
  ```json
  {
    "lots": {
      "LOT12345": {
        "found": true,
        "schema": "PRODUCTION",
        "wafers": ["W01", "W02", "W03", "W04", "W05"]
      }
    }
  }
  ```
- UI displays:
  - ✓ Lot: LOT12345
  - 📊 Production
  - 🧠 5 wafer(s): W01, W02, W03, W04, W05

## Test Scenario 2: Class 4 (Map) - Lot Only

**Input:**

- Data Type: Map (Class 4, pgc_key=4)
- Lot: `LOTABC99`
- Wafer: (empty)
- Preflight Check: Enabled

**Expected Behavior:**

- Backend detects wafer-level class with no wafer filter
- SQL query returns all wafers for LOTABC99
- Response includes wafer list
- UI displays wafer information

## Test Scenario 3: Class 14 (Defect) - Lot Only

**Input:**

- Data Type: Defect (Class 14, pgc_key=14)
- Lot: `DEFECT_LOT_001`
- Wafer: (empty)
- Preflight Check: Enabled

**Expected Behavior:**

- Backend detects wafer-level class with no wafer filter
- SQL query returns all wafers for DEFECT_LOT_001
- Response includes wafer list
- UI displays wafer information

## Test Scenario 4: Class 2 (FT) - Lot Only (Control)

**Input:**

- Data Type: FT (Class 2, pgc_key=2)
- Lot: `FT_LOT_001`
- Wafer: (empty)
- Preflight Check: Enabled

**Expected Behavior:**

- Backend detects lot-level class
- SQL query does NOT return wafer information
- Response:
  ```json
  {
    "lots": {
      "FT_LOT_001": {
        "found": true,
        "schema": "PRODUCTION",
        "wafers": []
      }
    }
  }
  ```
- UI displays:
  - ✓ Lot: FT_LOT_001
  - 📊 Production
  - (no wafer info shown)

## Test Scenario 5: Class 1 with Specific Wafers

**Input:**

- Data Type: Probe (Class 1, pgc_key=1)
- Lot: `LOT12345`
- Wafer: `W01, W03`
- Preflight Check: Enabled

**Expected Behavior:**

- Backend detects wafer-level class WITH wafer filter
- SQL query filters by specified wafers only
- Response includes only filtered wafers that exist:
  ```json
  {
    "lots": {
      "LOT12345": {
        "found": true,
        "schema": "PRODUCTION",
        "wafers": ["W01", "W03"]
      }
    }
  }
  ```
- UI displays only the requested wafers that were found

## Test Scenario 6: Multiple Lots - Class 1

**Input:**

- Data Type: Probe (Class 1, pgc_key=1)
- Lots: `LOT001, LOT002, LOT003`
- Wafer: (empty)
- Preflight Check: Enabled

**Expected Behavior:**

- Backend queries all lots
- Each lot returns its own wafer list
- Response:
  ```json
  {
    "lots": {
      "LOT001": {
        "found": true,
        "schema": "PRODUCTION",
        "wafers": ["W01", "W02"]
      },
      "LOT002": {
        "found": true,
        "schema": "SANDBOX",
        "wafers": ["W10", "W11", "W12"]
      },
      "LOT003": {
        "found": false,
        "schema": null,
        "wafers": []
      }
    }
  }
  ```
- UI displays each lot with its wafer list separately

## Test Scenario 7: Not Found Lot - Class 1

**Input:**

- Data Type: Probe (Class 1, pgc_key=1)
- Lot: `NONEXISTENT_LOT`
- Wafer: (empty)
- Preflight Check: Enabled

**Expected Behavior:**

- Backend queries for lot
- Lot not found in any schema
- Response:
  ```json
  {
    "lots": {
      "NONEXISTENT_LOT": {
        "found": false,
        "schema": null,
        "wafers": []
      }
    }
  }
  ```
- UI displays lot in "Not Found" section with no wafer info

## Test Scenario 8: CSV Export with Wafers

**Input:**

- Perform preflight check with Class 1 data
- LOT001 found with wafers W01, W02
- LOT002 not found
- Click "Export CSV"

**Expected CSV Output:**

```csv
Lot ID,Status,Schema,Wafers,Verified At
"LOT001","Found in Exensio","PRODUCTION","W01; W02","2026-07-21T10:30:00.000Z"
"LOT002","Not Found in Exensio","","","2026-07-21T10:30:00.000Z"
```

## Test Scenario 9: Snowflake vs HTTP Fallback

**Input:**

- Data Type: Probe (Class 1)
- Lot: `LOT999`
- Preflight Check: Enabled
- Snowflake unavailable → HTTP fallback

**Expected Behavior:**

- Primary Snowflake query fails
- Falls back to HTTP Oracle query
- Oracle query returns WAFER_ID column
- Response includes wafers from HTTP:
  ```json
  {
    "lots": {
      "LOT999": {
        "found": true,
        "schema": "FOUND",
        "wafers": ["W20", "W21"]
      }
    }
  }
  ```
- UI displays with "✓ HTTP" badge and wafer list

## SQL Verification Queries

### Snowflake - Check waferFilterMode Parameter

```sql
-- Mode 0: Lot-level (no wafer filtering)
-- Mode 1: Wafer-level with filter
-- Mode 2: Wafer-level return all wafers

-- Example for Mode 2 (return all wafers for lot)
SELECT LOT_ID, WAFER_ID, SCHEMANAME
FROM ANALYTICSPRD.MFG.EXENSIO_PROD_OPLOG_METADATA
WHERE PGC_KEY = 1
  AND LOT_ID = 'LOT12345'
  AND (2 = 0 OR 2 = 2 OR WAFER_ID IN (...))
-- The condition (2 = 2) is always true, so returns all wafers
```

### Oracle - Check WAFER_ID in result

```sql
-- Should return WAFER_ID column in results
SELECT lot_id, end_time, ppid, wafer_id
FROM (
  SELECT
    l.lot_id AS lot_id,
    w.wf_id AS wafer_id,
    ...
  FROM op_log ol
  JOIN lot l ON l.lot_key = ol.lot_key
  LEFT JOIN wf_log wfl ON wfl.lg_key = ol.lg_key
  LEFT JOIN wafer w ON w.wf_key = wfl.wf_key
  WHERE ol.pgc_key = 1
    AND UPPER(TRIM(l.lot_id)) = 'LOT12345'
)
```

## Manual Testing Checklist

- [ ] Test Class 1 (Probe) with lot only → displays wafers
- [ ] Test Class 4 (Map) with lot only → displays wafers
- [ ] Test Class 14 (Defect) with lot only → displays wafers
- [ ] Test Class 2 (FT) with lot only → no wafers shown
- [ ] Test with specific wafers → only those wafers returned
- [ ] Test multiple lots → each shows its own wafer list
- [ ] Test not found lot → wafer list empty
- [ ] Test CSV export includes wafer column
- [ ] Test UI styling for wafer info badge
- [ ] Test HTTP fallback includes wafers
- [ ] Test Snowflake primary path includes wafers
- [ ] Verify no breaking changes for existing functionality
