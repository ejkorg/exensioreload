# Plan: Admin Device Filter for Lot Discovery

## Overview

Add a "Device" text filter to the stepper's Configuration step, hidden from non-admin users (same pattern as the existing date range feature). The device filter narrows discovery results from the external `dtp_*_view` tables and feeds into the existing preview → stage → monitor pipeline.

## Pattern to Follow

The **admin-only date range** feature is the reference pattern. It's gated at three layers:
1. **UI**: `*ngIf="isAdminUser()"` hides the component
2. **Frontend logic**: `this.isAdminUser() && hasRange` prevents sending the param
3. **Backend controller**: `normalizePreviewFilters()` nullifies the param for non-admins

The device filter should follow this exact same layered defense.

## Files to Modify

### Backend — Data Model Layer

#### 1. `MetadataRow.java` (`backend/.../repository/MetadataRow.java`)
- Add `private final String device;` field
- Add constructor parameter + getter

#### 2. `DiscoveryPreviewRow.java` (`backend/.../dto/DiscoveryPreviewRow.java`)
- Add `String device` to the record definition (after `wafer`)

#### 3. `DiscoveryPreviewRequest.java` (`backend/.../dto/DiscoveryPreviewRequest.java`)
- Add `List<String> devices` field (after `wafers`)

#### 4. `StageAllRequest.java` (`backend/.../dto/StageAllRequest.java`)
- Add `List<String> devices` field (after `wafers`)

### Backend — Repository Layer

#### 5. `JdbcExternalMetadataRepository.java`

**SELECT clause changes** — Add `device` to every query against `dtp_*_view`:
- `findMetadataPageWithCount()` — add `device` after `wafer` in the SELECT
- `findMetadata()` / `findMetadataPage()` — add `device` column
- `countMetadata()` — add device to WHERE when filtering
- `summarizeMetadata()` — add device to WHERE predicates
- `streamMetadata()` — add device column
- `describePreviewQuery()` — add device column
- Deduped preview queries using `ROW_NUMBER()` — add `device` to the SELECT

**`mapMetadataRow(ResultSet)`** — Add `rs.getString("device")` call (after the wafer read, around line 1457-1461)

**SQL builders** — Add device predicate to:
- `buildMetadataQueryInternal()` — add `List<String> devices` param, build `AND device IN (?...)` / `AND UPPER(device) IN (?...)` clause (cap at 1000, same pattern as lots/wafers but with 1000 cap instead of 100)
- `buildMetadataQuery()` — forward `devices` to internal method
- `buildOptimizedMetadataQuery()` — same
- `buildPreviewDedupedPageQuery()` — forward `devices`
- `buildPreviewDedupedCountQuery()` — same
- `buildOptimizedSummaryQuery()` — same

#### 6. `ExternalMetadataRepository.java` (`backend/.../repository/ExternalMetadataRepository.java`)
- Add `List<String> devices` parameter to all methods:
  - `findMetadata()`, `findMetadataPage()`, `findMetadataPageWithCount()`
  - `countMetadata()`, `summarizeMetadata()`
  - `streamMetadata()`, `streamMetadataWithConnection()`
  - `describePreviewQuery()`

### Backend — Service Layer

#### 7. `MetadataImporterService.java`

- `previewMetadata()` — add `List<String> devices` param, pass through to repository calls
- `validatePreviewRequest()` — accept and validate `devices` (trimmable, capped at 1000)
- `discoverAndEnqueue()` — thread `devices` through
- `deduplicatePreviewRows()` — preserve `device` field through deduplication
- `previewDedupKey()` — no change needed (device is not part of dedup key)

### Backend — Controller Layer

#### 8. `SenderController.java`

- `normalizePreviewFilters()` — add `List<String> devices` extraction from request; **add admin-only gate**: nullify devices for non-admin users (same pattern as date range)
- `PreviewFilters` record — add `devices` field
- All preview endpoints — thread `devices` through to `MetadataImporterService.previewMetadata()`
- CSV preview endpoint — add `device` column to CSV output
- Stage-all endpoint — thread `devices` from `StageAllRequest` through `normalizeDateAndMode` + validate
- `currentUserIsAdmin()` — already exists, no change needed

### Frontend — API Layer

#### 9. `backend.service.ts` (`frontend/src/app/api/backend.service.ts`)

- `DiscoveryPreviewRequest` interface — add `devices?: string[] | null;`

### Frontend — Stepper Component

#### 10. `stepper.component.ts`

Add the device filter input behavior:
- Signal: `deviceFilter = signal<string>('')` — raw text input (like lot inputs, comma-or-newline delimited)
- FormControl: `deviceFilterControl = new FormControl('')` — for reactive form binding
- Computed: `showDeviceFilter = computed(() => this.isAdminUser()` — admin-only visibility
- In `canProceedToPreview()` — allow device-only as a valid filter for admin (similar to date range pattern)
- In `buildDiscoveryFiltersFromCurrentSelection()` — include device list when `isAdminUser()` is true
- In `loadPreview()` — include device list in params when admin
- In `resetAllConfig()` / initialization — clear device filter

**Device list parsing** (reuse pattern from lot/wafer inputs):
```typescript
private parseDeviceList(raw: string): string[] {
  if (!raw?.trim()) return [];
  return raw.split(/[\n,]+/).map(s => s.trim()).filter(s => s.length > 0);
}
```

#### 11. `DiscoveryFiltersSnapshot` interface (in `stepper.component.ts`)
- Add `devices?: string[]` field

#### 12. `stepper.component.html`

Add device filter input in the Configuration step, after lot/wafer pairs and before the date range section:
```html
<div class="form-row" *ngIf="showDeviceFilter()">
  <app-glass-input
    label="Device Filter"
    [formControl]="deviceFilterControl"
    placeholder="Enter device IDs, comma or newline separated"
  >
  </app-glass-input>
</div>
```

Use debounced valueChanges (400ms) on `deviceFilterControl` that syncs to the `deviceFilter` signal (matching admin page search debounce pattern).

#### 13. `stepper.component.scss`
- Minor style additions if needed for device input spacing (likely no changes needed — `form-row` class and `app-glass-input` handle styling)

## Verification

1. **Non-admin user**: Device filter input should not appear. `devices` param should not appear in network requests. Backend should reject/nullify device filters.

2. **Admin user**: Device filter input visible. Entering device IDs filters results. Combined with date range + lot filters — results AND-ed.

3. **Stage All**: Device filter captured in `lastDiscoveryFilters` and re-sent during Stage All.

4. **CSV export**: Device column included in CSV output.

5. **Resume session**: Device filter persisted in filter snapshot.

6. **Edge cases**: Empty input, whitespace-only, 1000+ devices (capped), special characters.

## Key Design Decisions

- **Device is a text input, not a select** — external DBs have too many device IDs for a dropdown. Same pattern as lot/wafer inputs.
- **Admin-only three-layer defense** — UI hide, frontend gate, backend nullification. Exactly copies the date range pattern.
- **Device in SELECT and WHERE** — column flows to DiscoveryPreviewRow for display, also used as WHERE filter.
- **1000 device cap** — higher than lots/wafers (100) since device lists can be larger. Truncate silently in backend.
- **No changes to stage/monitor pipeline** — device is purely a discovery filter. Staged records already capture lot+wafer granularity.
