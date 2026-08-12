# Dead Code Removal Checklist for refactor_postgre Branch

## Overview
This document lists all identified dead code, deprecated methods, and temporary artifacts to remove during the refactor_postgre cleanup pass. Follow the checklist systematically to ensure safe removal with zero references to live code.

---

## BACKEND DEAD CODE

### Priority 1: Remove Immediately (No Active Usages)

#### 1. Stray Build Artifact
- **File:** [backend/src/main/java/tree_output.txt](backend/src/main/java/tree_output.txt)
- **Action:** Delete entire file (folder tree dump from prior debug session)
- **Status:** ⬜ Not Started
- **Notes:** This is a debug artifact with no code dependency.

---

### Priority 2: Deprecated Accessor Methods (Can Be Removed Safely)

These methods are marked `@Deprecated` and provide legacy aliases to current naming. They are **not actively used in the API layer** but exist for backward compatibility with old test/client code. Safe to remove if no external clients depend on them.

#### 2. StageStatus Legacy Accessors
- **File:** [backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/stage/StageStatus.java](backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/stage/StageStatus.java)
- **Lines:** 57–83
- **Deprecated Methods to Remove:**
  - `ready()` (aliases `stagedToRefdb()`)
  - `queued()` (aliases `queuedForCp()`)
  - `enriching()` (aliases `elasticsearchMonitoring()`)
  - `enrichmentTimeout()` (aliases `cpTimeout()`)
  - `exensioLoading()` (aliases `exensioMonitoring()`)
  - `exensioTimeout()` (aliases `completedManualVerification()`)
  - `failed()` (aliases `totalFailed()`)
  - `done()` (aliases `completed()`)
  - `staged()` (compound alias)
- **Action:** Remove all `@Deprecated` methods lines 57–83
- **Status:** ⬜ Not Started
- **Validation:** Check for any test imports or usage: `grep -r "\.ready()\|\.queued()\|\.enriching()" backend/src/test`

#### 3. StageUserStatus Legacy Accessors
- **File:** [backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/stage/StageUserStatus.java](backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/stage/StageUserStatus.java)
- **Lines:** 14–19
- **Deprecated Methods to Remove:**
  - `stagedToRefdb()` → use `ready()`
  - `queuedForCp()` → use `enqueued()`
  - `cpFailed()` → use `failed()`
- **Action:** Remove all `@Deprecated` methods lines 14–19
- **Status:** ⬜ Not Started

#### 4. RefDbService Legacy Accessors (Nested Classes)
- **File:** [backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/RefDbService.java](backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/RefDbService.java)
- **Lines:** 3127–3150 (nested inner classes)
- **Deprecated Methods:** Same as StageUserStatus (stagedToRefdb, queuedForCp, cpFailed, completedOld)
- **Action:** Remove duplicate deprecated accessors in both inner classes
- **Status:** ⬜ Not Started

#### 5. RefDbService Deprecated Queueing Method
- **File:** [backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/RefDbService.java](backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/RefDbService.java)
- **Lines:** 537–541
- **Method Name:** `markQueued()` or similar
- **Reason:** Queueing is now a dispatch-time state, not manually called
- **Action:** Remove `@Deprecated` method
- **Status:** ⬜ Not Started

#### 6. RefDbService Deprecated Timeout Method
- **File:** [backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/RefDbService.java](backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/RefDbService.java)
- **Lines:** 930–932
- **Method:** `markEnrichmentTimeout()` (use `markCpTimeout()` instead)
- **Action:** Remove deprecated method
- **Status:** ⬜ Not Started

#### 7. ExensioPreCheckService Deprecated SQL Builder
- **File:** [backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioPreCheckService.java](backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/ExensioPreCheckService.java)
- **Lines:** 887–895 (approx)
- **Method:** `buildSql(List, List, List, String)` (deprecated in favor of 5-arg version)
- **Action:** Remove old overload, keep new one
- **Status:** ⬜ Not Started

---

### Priority 3: Abstract Stub Methods (UnsupportedOperationException)

These are abstract methods in base classes that throw `UnsupportedOperationException`. They indicate unimplemented features for non-JDBC implementations. Consider replacing with abstract method declarations or removing if no subclasses exist.

#### 8. MetadataImporterService Stubs
- **File:** [backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/MetadataImporterService.java](backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/service/MetadataImporterService.java)
- **Lines:** 141, 156, 209, 217, 224, 231, 238, 257, 265
- **Methods:** All throw `UnsupportedOperationException("...only supported by JDBC implementation")`
- **Examples:**
  - Line 141: `getSenderList()` 
  - Line 156: `getSenderByHistoricalId()`
  - Line 209–265: Various metadata queries (locations, data types, testers, etc.)
- **Action:** Either:
  - Make these abstract and force implementations, OR
  - Remove if no non-JDBC backend is planned
- **Status:** ⬜ Not Started
- **Investigation Needed:** Are there non-JDBC implementations of MetadataImporterService?

#### 9. ExternalMetadataRepository Stub
- **File:** [backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/repository/ExternalMetadataRepository.java](backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/repository/ExternalMetadataRepository.java)
- **Line:** 84
- **Method:** `getSenderByHistoricalId()` throws `UnsupportedOperationException("Historical sender lookup not implemented")`
- **Action:** Evaluate if feature is ever used; if not, remove method or replace with abstract
- **Status:** ⬜ Not Started

---

### Priority 4: Review & Decide (May Be Intentional Stubs)

The following are helper methods that exist but may be intentional design patterns:

#### 10. AuthController Legacy Signatures
- **File:** [backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/controller/AuthController.java](backend/src/main/java/com/onsemi/cim/apps/exensio/exensioreload/controller/AuthController.java)
- **Lines:** 134, 148 (comments reference old test signatures)
- **Note:** "Added to preserve older test signatures"
- **Action:** If these overloads are only for tests, move them to test helper class or remove
- **Status:** ⬜ Not Started

---

## FRONTEND DEAD CODE

### Priority 1: Remove Immediately (No Active Usages)

#### 11. Temp Stylesheet Artifact
- **File:** [frontend/src/styles.scss-629mod](frontend/src/styles.scss-629mod)
- **Action:** Delete entire file (appears to be a temp build artifact)
- **Status:** ⬜ Not Started
- **Notes:** This is a build/IDE temp file.

---

### Priority 2: Deprecated API Method Aliases

#### 12. BackendService Deprecated Accessor Methods
- **File:** [frontend/src/app/api/backend.service.ts](frontend/src/app/api/backend.service.ts)
- **Line 25:** Comment: `// Aliases for code still using old field names`
- **Line 695:** `@deprecated Use listSitesForEnvironment() instead for better performance.`
- **Line 777:** Comment: `// DEPRECATED: Use getExternalSenders() instead with proper params`
- **Action:** Search for usages of old method names, then remove once refactored
- **Status:** ⬜ Not Started
- **Validation:** `grep -r "listSites\|getExternalSenders" frontend/src --include="*.ts"`

---

### Priority 3: Deprecated/Backward Compatibility Fields

#### 13. MonitoringService Deprecated Field
- **File:** [frontend/src/app/shared/services/monitoring.service.ts](frontend/src/app/shared/services/monitoring.service.ts)
- **Line 14:** `processing: number; // deprecated: kept for backward compatibility, equals enriching + enrichmentTimeout + exensioLoading + exensioTimeout`
- **Action:** Remove field if no component references it
- **Status:** ⬜ Not Started
- **Validation:** `grep -r "\.processing" frontend/src --include="*.ts"`

---

### Priority 4: Placeholder / TODO Comments

#### 14. AlertConfigurationComponent Placeholder
- **File:** [frontend/src/app/shared/components/alert-configuration.component.ts](frontend/src/app/shared/components/alert-configuration.component.ts)
- **Line 463:** Comment: `// Placeholder until backend endpoint for template thresholds is available.`
- **Current Implementation:** Saves to localStorage only
- **Action:** Decide if real backend endpoint will ever exist; if not, document the limitation or remove the tab
- **Status:** ⬜ Not Started

---

### Priority 5: Review for Dead Routes / Unused Components

#### 15. Check for Unused Components
- **Scope:** frontend/src/app
- **Action:** Audit routing table and service imports for components that are never routed to or never used
- **Files to Check:**
  - [frontend/src/app/app.routes.ts](frontend/src/app/app.routes.ts) (routing definitions)
  - [frontend/src/app/app.config.ts](frontend/src/app/app.config.ts) (providers)
- **Command:** For each module, search for imports: `grep -r "import.*from.*component" frontend/src/app/*/`
- **Status:** ⬜ Not Started

---

## VALIDATION & BUILD STEPS

### Backend Validation
```bash
# 1. Compile
mvn clean compile

# 2. Run unit tests
mvn test

# 3. Run integration tests (if any)
mvn verify

# 4. Check for broken imports
grep -r "\.ready()\|\.queued()\|\.enriching()" backend/src
```

### Frontend Validation
```bash
# 1. Lint
ng lint

# 2. Build
ng build

# 3. Run tests
ng test

# 4. Check for broken imports
grep -r "import.*deprecated\|oldFieldName" frontend/src --include="*.ts"
```

---

## EXECUTION PLAN

1. **Switch to refactor_postgre branch** (Desktop Git)
2. **Backend Pass 1:** Remove tree_output.txt (Priority 1)
3. **Backend Pass 2:** Remove deprecated accessors in StageStatus, StageUserStatus, RefDbService (Priority 2)
4. **Backend Validation:** Compile and test
5. **Backend Pass 3:** Evaluate and remove UnsupportedOperationException stubs (Priority 3)
6. **Frontend Pass 1:** Delete temp stylesheet (Priority 1)
7. **Frontend Pass 2:** Remove deprecated API methods (after refactoring usages) (Priority 2)
8. **Frontend Validation:** Lint and build
9. **Final:** Run full test suite and create pull request

---

## SUMMARY OF ITEMS TO REMOVE

| #  | File | Type | Lines | Status |
|----|------|------|-------|--------|
| 1  | backend/src/main/java/tree_output.txt | Artifact | All | ⬜ |
| 2  | StageStatus.java | Deprecated Methods | 57–83 | ⬜ |
| 3  | StageUserStatus.java | Deprecated Methods | 14–19 | ⬜ |
| 4  | RefDbService.java | Deprecated Methods | 3127–3150, 537–541, 930–932 | ⬜ |
| 5  | ExensioPreCheckService.java | Deprecated Method | 887–895 | ⬜ |
| 6  | MetadataImporterService.java | Unimplemented Stubs | 141, 156, 209, 217, 224, 231, 238, 257, 265 | ⬜ |
| 7  | ExternalMetadataRepository.java | Unimplemented Stub | 84 | ⬜ |
| 8  | AuthController.java | Legacy Overloads | 134, 148 | ⬜ |
| 9  | frontend/src/styles.scss-629mod | Artifact | All | ⬜ |
| 10 | BackendService.java | Deprecated Methods | 25, 695, 777 | ⬜ |
| 11 | MonitoringService.ts | Deprecated Field | 14 | ⬜ |
| 12 | AlertConfigurationComponent.ts | Placeholder | 463 | ⬜ |
| 13 | app.routes.ts + imports | Dead Routes/Components | TBD | ⬜ |

---

## NOTES

- **Dead code can be easily restored** from git history if removal causes issues. The refactor_postgre branch is isolated.
- **Test coverage is your safety net**. Run full test suites after each major removal.
- **External API contracts**: Be careful with deprecated methods that might be called by external clients or tests.
- **Reuse the checklist** to track your progress as you work through each item on the refactor_postgre branch.

