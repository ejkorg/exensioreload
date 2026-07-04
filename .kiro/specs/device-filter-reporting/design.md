# Design Document

## Overview

This design adds device filtering capabilities across all reporting interfaces (Analytics, My Sessions, Dashboard) by persisting device information in the `load_session_payload` staging table. The solution extends the existing discovery-to-staging pipeline to capture device identifiers and provides filtering APIs and UI components for device-based data analysis.

## Architecture

### High-Level Flow

```
Discovery Phase → Device Captured → Staging Phase → Device Persisted → Reporting Phase → Device Filtered
```

### Component Layers

1. **Data Layer**: Add device column to `load_session_payload` table
2. **Repository Layer**: Extend queries to support device filtering and retrieval
3. **Service Layer**: Update staging service to persist device; add device filtering logic
4. **API Layer**: Extend endpoints to accept device filters and return device lists
5. **UI Layer**: Add device filter components to Analytics, My Sessions, and Dashboard pages

## Components and Interfaces

### 1. Database Schema Changes

#### New Column: `load_session_payload.device`

```sql
ALTER TABLE load_session_payload ADD COLUMN device VARCHAR(100);
CREATE INDEX idx_load_session_payload_device ON load_session_payload(device);
```

**Properties:**

- Type: `VARCHAR(100)` - accommodates typical device identifiers
- Nullable: `YES` - allows for legacy records and missing device data
- Indexed: `YES` - optimizes filter queries

### 2. Entity Updates

#### LoadSessionPayload Entity

Add device field:

```java
@Entity
@Table(name = "load_session_payload")
public class LoadSessionPayload {
    // ... existing fields ...

    @Column(name = "device", length = 100)
    private String device;

    public String getDevice() { return device; }
    public void setDevice(String device) { this.device = device; }
}
```

### 3. DTO Updates

#### StageRecordView DTO

Add device to record view:

```java
public record StageRecordView(
    long id,
    String site,
    int senderId,
    String senderName,
    String metadataId,
    String dataId,
    String lot,
    String wafer,
    String device,  // NEW
    String filename,
    // ... other existing fields ...
) {}
```

### 4. Repository Layer

#### LoadSessionPayloadRepository Extensions

Add methods for device filtering:

```java
public interface LoadSessionPayloadRepositoryCustom {
    // Find distinct devices across all payloads
    List<String> findDistinctDevices();

    // Find distinct devices for specific session
    List<String> findDistinctDevicesBySessionId(Long sessionId);

    // Find payloads filtered by devices
    Page<LoadSessionPayload> findByDeviceIn(List<String> devices, Pageable pageable);
}
```

### 5. Service Layer Updates

#### StagingService / SessionPushService

Update staging logic to capture device:

```java
public void stagePayload(MetadataRow metadata, LoadSession session) {
    LoadSessionPayload payload = new LoadSessionPayload(session, metadata.getId() + "," + metadata.getIdData());
    payload.setDevice(metadata.getDevice());  // NEW: Capture device
    // ... rest of staging logic ...
}
```

### 6. API Endpoints

#### New Endpoint: GET /api/sessions/devices

Returns distinct device values for filter dropdowns:

```java
@GetMapping("/sessions/devices")
public ResponseEntity<List<String>> getDistinctDevices(
    @RequestParam(required = false) Long sessionId
) {
    List<String> devices = sessionId != null
        ? stagingService.getDevicesForSession(sessionId)
        : stagingService.getAllDistinctDevices();
    return ResponseEntity.ok(devices);
}
```

#### Updated Endpoints

**GET /api/sessions** - Add device filter parameter:

```java
@GetMapping("/sessions")
public ResponseEntity<Page<SessionSummary>> getSessions(
    @RequestParam(required = false) List<String> devices,  // NEW
    @RequestParam(required = false) String site,
    // ... other existing parameters ...
    Pageable pageable
) {
    return ResponseEntity.ok(sessionService.findSessions(devices, site, ..., pageable));
}
```

**GET /api/analytics/summary** - Add device filter parameter:

```java
@GetMapping("/analytics/summary")
public ResponseEntity<AnalyticsSummary> getAnalyticsSummary(
    @RequestParam(required = false) List<String> devices,  // NEW
    @RequestParam(required = false) String startDate,
    @RequestParam(required = false) String endDate,
    // ... other existing parameters ...
) {
    return ResponseEntity.ok(analyticsService.getSummary(devices, startDate, endDate, ...));
}
```

### 7. Frontend Components

#### Glass Device Filter Component

Reusable device filter component:

```typescript
@Component({
  selector: 'app-glass-device-filter',
  template: `
    <div class="glass-device-filter">
      <app-glass-select
        [options]="deviceOptions()"
        [multiple]="true"
        [placeholder]="'Filter by device'"
        [(value)]="selectedDevices"
        (valueChange)="onDeviceChange($event)"
      />
    </div>
  `,
})
export class GlassDeviceFilterComponent {
  deviceOptions = signal<string[]>([]);
  selectedDevices = model<string[]>([]);

  constructor(private sessionService: StagingSessionService) {
    this.loadDevices();
  }

  private loadDevices() {
    this.sessionService.getDistinctDevices().subscribe((devices) => {
      this.deviceOptions.set(devices);
    });
  }

  onDeviceChange(devices: string[]) {
    this.selectedDevices.set(devices);
  }
}
```

#### Service Extension

```typescript
export class StagingSessionService {
  // ... existing methods ...

  getDistinctDevices(sessionId?: number): Observable<string[]> {
    const params = sessionId ? { sessionId: sessionId.toString() } : {};
    return this.http.get<string[]>('/api/sessions/devices', { params });
  }

  getSessions(filters: {
    devices?: string[];
    site?: string;
    // ... other filters ...
  }): Observable<Page<SessionSummary>> {
    let params = new HttpParams();
    if (filters.devices?.length) {
      filters.devices.forEach((d) => (params = params.append('devices', d)));
    }
    // ... add other params ...
    return this.http.get<Page<SessionSummary>>('/api/sessions', { params });
  }
}
```

## Data Models

### LoadSessionPayload (Updated)

| Field      | Type             | Description                              |
| ---------- | ---------------- | ---------------------------------------- |
| id         | BIGINT           | Primary key                              |
| session_id | BIGINT           | Foreign key to load_session              |
| payload_id | VARCHAR          | Composite identifier (metadataId,dataId) |
| **device** | **VARCHAR(100)** | **Device identifier (NEW)**              |
| status     | VARCHAR          | Payload processing status                |
| error      | TEXT             | Error message if failed                  |
| created_at | TIMESTAMP        | Creation timestamp                       |
| updated_at | TIMESTAMP        | Last update timestamp                    |

### DiscoveryPreviewRow (Discovery Preview Display Model)

| Field            | Type       | Description                                        |
| ---------------- | ---------- | -------------------------------------------------- |
| **metadataId**   | **String** | **Metadata identifier (NEW - display in preview)** |
| **dataId**       | **String** | **Data identifier (NEW - display in preview)**     |
| device           | String     | Device identifier from metadata source             |
| lot              | String     | Lot identifier                                     |
| wafer            | String     | Wafer identifier                                   |
| originalFileName | String     | Original source file name                          |
| endTime          | String     | Processing end time                                |

### Filter Request Model

```typescript
interface SessionFilters {
  devices?: string[];
  site?: string;
  environment?: string;
  senderId?: number;
  startDate?: string;
  endDate?: string;
  status?: string;
}
```

## Correctness Properties

_A property is a characteristic or behavior that should hold true across all valid executions of a system—essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees._

### Property 1: Device Persistence Round-Trip

_For any_ payload with device information from metadata discovery, staging that payload then querying it from the staging table should return the same device identifier.

**Validates: Requirements 1.1, 1.4, 5.3**

### Property 2: NULL Device Handling

_For any_ payload lacking device information, the system should store NULL in the device field and handle all subsequent operations (queries, filters, displays) without errors.

**Validates: Requirements 1.2, 5.4, 8.1, 8.4**

### Property 3: Device Filter Correctness

_For any_ set of device identifiers used as a filter in any reporting interface (Analytics, My Sessions, Dashboard, API), all returned results should contain only payloads whose device field matches one of the selected devices.

**Validates: Requirements 2.2, 3.2, 4.2, 7.2**

### Property 4: Unfiltered Default Behavior

_For any_ reporting interface, when no device filter is applied, the system should return the same results as if the feature did not exist (backward compatibility).

**Validates: Requirements 2.3, 3.4, 4.4, 8.2, 8.3**

### Property 5: Filter State Persistence

_For any_ UI session where device filters are applied, navigating away from the page and returning (or refreshing) should restore the same device filter selections.

**Validates: Requirements 2.4, 4.5**

### Property 6: Distinct Devices Accuracy

_For any_ request for distinct device values, the returned list should exactly match the set of unique non-NULL device values present in the staging table at query time.

**Validates: Requirements 2.5, 7.3**

### Property 7: Session Detail Device Display

_For any_ session detail view, every payload in that session should display its associated device value, or an appropriate placeholder for NULL values.

**Validates: Requirements 3.3**

### Property 8: Real-Time Filter Application

_For any_ active device filter on the dashboard, new payloads arriving via real-time updates should only appear in metrics if their device matches the active filter.

**Validates: Requirements 4.3**

### Property 9: Discovery Device Retrieval

_For any_ metadata query during discovery, if the external data source contains device information, it should be retrieved and displayed in the preview results.

**Validates: Requirements 5.1, 5.2**

### Property 10: Preview Filter Accuracy

_For any_ device filter applied during discovery preview, all displayed rows should have device values matching the filter.

**Validates: Requirements 5.5**

### Property 11: Paginated Filter Consistency

_For any_ paginated API response with device filters, both the page results and the total count should reflect only the filtered subset, and summing all pages should equal the total count.

**Validates: Requirements 7.4**

### Property 12: Multi-Filter Composition

_For any_ combination of device filters with other filters (site, date range, status), results should satisfy all filter conditions using AND logic.

**Validates: Requirements 3.5, 7.5**

### Property 13: API Parameter Optionality

_For any_ API endpoint that accepts device filters, requests that omit the device parameter should be processed identically to requests in the pre-feature system.

**Validates: Requirements 7.1, 8.3**

### Property 14: Discovery Metadata Display

_For any_ metadata row displayed in the discovery preview, both metadataId and dataId should be present and visible in the preview table, allowing users to identify data sources.

**Validates: Requirements 5.2, 5.3**

### Property 15: Discovery Metadata Preservation

_For any_ metadata row containing device, metadataId, and dataId information, all three fields should be preserved through the staging process and available for querying in the staging table.

**Validates: Requirements 5.3, 5.4**

## Error Handling

### Database Errors

- **Migration Failures**: If the Liquibase migration fails, the transaction should roll back, leaving the schema unchanged. The system logs the error and prevents application startup until the migration succeeds.
- **NULL Device Queries**: When querying payloads with NULL device values, the system treats NULL as a valid state and includes these records in unfiltered queries. Device filters exclude NULL values unless explicitly specified.

### API Errors

- **Invalid Device Filter**: If a client provides a device filter value that doesn't exist in the staging table, the API returns an empty result set (not an error), maintaining consistency with other filter behaviors.

- **Malformed Parameters**: If device parameters are malformed (e.g., wrong data type), the API returns 400 Bad Request with a descriptive error message.

### UI Errors

- **Failed Device Load**: If loading distinct devices for the filter dropdown fails, the UI displays an error message and provides a retry button. The page remains functional with device filtering disabled.

- **Filter Application Timeout**: If applying a device filter takes longer than 5 seconds, the UI shows a warning and allows the user to cancel the operation or wait for completion.

## Testing Strategy

This feature requires both unit tests and property-based tests to ensure comprehensive coverage.

### Unit Testing

Unit tests focus on specific examples, edge cases, and integration points:

- **Example: Schema migration succeeds** - Verify the Liquibase changelog adds the device column and index
- **Example: NULL device display** - Verify that payloads with NULL device show "N/A" in the UI
- **Example: Device filter dropdown population** - Verify the UI component correctly fetches and displays device options
- **Edge case: Empty device list** - Test behavior when no devices exist in the staging table
- **Edge case: Single device filter** - Test filtering with exactly one device selected
- **Integration: Discovery to staging pipeline** - Verify device flows correctly from metadata query through staging

### Property-Based Testing

Property tests verify universal properties across all inputs using randomized test data. The implementation will use **JUnit 5** with **jqwik** for Java backend tests and **Jest** with **fast-check** for TypeScript frontend tests. Each test should run a minimum of **100 iterations** to ensure thorough coverage.

#### Backend Property Tests (Java + jqwik)

- **Property 1: Device Persistence Round-Trip**
  - Generate random metadata with device values
  - Stage the payloads
  - Query the staging table
  - Assert retrieved device equals original device
  - Tag: **Feature: device-filter-reporting, Property 1: Device persistence round-trip**

- **Property 2: NULL Device Handling**
  - Generate random payloads with NULL devices
  - Perform staging, querying, and filtering operations
  - Assert no errors occur and operations complete successfully
  - Tag: **Feature: device-filter-reporting, Property 2: NULL device handling**

- **Property 3: Device Filter Correctness**
  - Generate random payloads with various devices
  - Generate random device filter sets
  - Apply filters via API
  - Assert all results have devices in the filter set
  - Tag: **Feature: device-filter-reporting, Property 3: Device filter correctness**

- **Property 11: Paginated Filter Consistency**
  - Generate random large dataset of payloads
  - Apply random device filter
  - Fetch all pages
  - Assert sum of page counts equals total count
  - Assert no duplicate records across pages
  - Tag: **Feature: device-filter-reporting, Property 11: Paginated filter consistency**

- **Property 12: Multi-Filter Composition**
  - Generate random payloads with multiple attributes (device, site, date, status)
  - Generate random combinations of filters
  - Apply filters via API
  - Assert all results satisfy all filter conditions
  - Tag: **Feature: device-filter-reporting, Property 12: Multi-filter composition**

#### Frontend Property Tests (TypeScript + fast-check)

- **Property 5: Filter State Persistence**
  - Generate random device filter selections
  - Apply filters in UI
  - Simulate navigation away and back
  - Assert filter state is restored
  - Tag: **Feature: device-filter-reporting, Property 5: Filter state persistence**

- **Property 7: Session Detail Device Display**
  - Generate random sessions with payloads (mix of NULL and non-NULL devices)
  - Render session detail view
  - Assert all payloads show device or "N/A"
  - Tag: **Feature: device-filter-reporting, Property 7: Session detail device display**

### Integration Testing

- **Discovery to Analytics Flow**: Test complete flow from discovery preview → staging → analytics filtering
- **Multi-Interface Consistency**: Verify same device filter produces consistent results across Analytics, My Sessions, and Dashboard
- **Real-Time Updates**: Test that SSE updates respect active device filters in Dashboard

### Performance Testing

- **Device Filter Query Performance**: Measure query time with device filters on datasets of 10K, 100K, and 1M records
- **Index Effectiveness**: Verify query plans use the device index
- **Distinct Devices Query**: Measure time to retrieve distinct devices from large datasets
- **Concurrent Filter Requests**: Test system behavior under concurrent device filter requests from multiple users
