# Implementation Plan: Device Filter Reporting

## Overview

This implementation adds device filtering capabilities across all reporting interfaces by:

1. Adding a device column to the staging table via database migration
2. Updating backend entities, repositories, and services to capture and query device data
3. Extending API endpoints to accept device filter parameters
4. Creating reusable frontend components for device filtering
5. Integrating device filters into Analytics, My Sessions, and Dashboard pages

## Tasks

- [x] 1. Database schema migration
  - Create Liquibase changelog to add device column to load_session_payload table
  - Add VARCHAR(100) nullable column named 'device'
  - Create index idx_load_session_payload_device on device column
  - Include rollback instructions
  - _Requirements: 1.3, 6.1, 6.2, 6.4, 6.5_

- [x] 2. Update backend entity and DTOs
  - [x] 2.1 Add device field to LoadSessionPayload entity
    - Add @Column(name = "device", length = 100) private String device
    - Add getter and setter methods
    - _Requirements: 1.1, 1.2_

  - [x] 2.2 Add device to StageRecordView DTO
    - Add device parameter to record constructor
    - Update all usages to include device field
    - _Requirements: 1.4_

  - [x] 2.3 Update DiscoveryPreviewRow DTO (if not already present)
    - Verify device field exists in DiscoveryPreviewRow
    - _Requirements: 5.2_

- [x] 3. Extend repository layer for device queries
  - [x] 3.1 Add device query methods to LoadSessionPayloadRepository
    - Add findDistinctDevices() method
    - Add findDistinctDevicesBySessionId(Long sessionId) method
    - Add device filter support in custom query methods
    - _Requirements: 2.5, 7.3_

  - [ ]\* 3.2 Write property test for distinct device retrieval
    - **Property 6: Distinct Devices Accuracy**
    - **Validates: Requirements 2.5, 7.3**
    - Generate random payloads with various devices
    - Verify findDistinctDevices returns exact set of unique non-NULL devices
    - Tag: **Feature: device-filter-reporting, Property 6: Distinct devices accuracy**

  - [x] 3.3 Update LoadSessionPayloadRepositoryImpl for device filtering
    - Add device parameter to buildQuery methods
    - Add SQL WHERE clause for device IN filtering
    - Handle NULL device values appropriately
    - _Requirements: 7.2, 8.1_

  - [ ]\* 3.4 Write property test for device filter queries
    - **Property 3: Device Filter Correctness**
    - **Validates: Requirements 2.2, 3.2, 4.2, 7.2**
    - Generate random payloads with various devices
    - Apply random device filters
    - Verify all results have devices in filter set
    - Tag: **Feature: device-filter-reporting, Property 3: Device filter correctness**

- [x] 4. Update service layer to capture and filter by device
  - [x] 4.1 Update staging service to persist device
    - Modify stagePayload/stagingFromMetadata methods
    - Extract device from MetadataRow and set on LoadSessionPayload
    - Handle NULL device gracefully
    - _Requirements: 1.1, 1.2, 5.3_

  - [ ]\* 4.2 Write property test for device persistence
    - **Property 1: Device Persistence Round-Trip**
    - **Validates: Requirements 1.1, 1.4, 5.3**
    - Generate random metadata with device values
    - Stage payloads
    - Query staging table
    - Assert retrieved device equals original
    - Tag: **Feature: device-filter-reporting, Property 1: Device persistence round-trip**

  - [ ]\* 4.3 Write property test for NULL device handling
    - **Property 2: NULL Device Handling**
    - **Validates: Requirements 1.2, 5.4, 8.1, 8.4**
    - Generate random payloads with NULL devices
    - Perform staging, querying, filtering
    - Assert no errors and operations complete successfully
    - Tag: **Feature: device-filter-reporting, Property 2: NULL device handling**

  - [x] 4.4 Add device filtering to session query service
    - Add devices parameter to findSessions methods
    - Apply device filter in query building
    - _Requirements: 3.2, 7.2_

  - [x] 4.5 Add device filtering to analytics service
    - Add devices parameter to analytics methods
    - Filter analytics calculations by device
    - _Requirements: 2.2_

  - [ ]\* 4.6 Write property test for multi-filter composition
    - **Property 12: Multi-Filter Composition**
    - **Validates: Requirements 3.5, 7.5**
    - Generate random payloads with multiple attributes
    - Apply random combinations of filters (device + site + date + status)
    - Verify all results satisfy all conditions (AND logic)
    - Tag: **Feature: device-filter-reporting, Property 12: Multi-filter composition**

- [ ] 5. Checkpoint - Ensure backend tests pass
  - Run all backend unit tests and property tests
  - Verify no regressions in existing functionality
  - Ask the user if questions arise

- [ ] 6. Extend API endpoints for device filtering
  - [x] 6.1 Add GET /api/sessions/devices endpoint
    - Accept optional sessionId parameter
    - Return List<String> of distinct devices
    - Handle empty results gracefully
    - _Requirements: 7.3_

  - [x] 6.2 Update GET /api/sessions endpoint
    - Add @RequestParam(required = false) List<String> devices parameter
    - Pass devices to service layer
    - Update API documentation
    - _Requirements: 3.2, 7.1, 7.2_

  - [x] 6.3 Update GET /api/analytics/summary endpoint
    - Add @RequestParam(required = false) List<String> devices parameter
    - Pass devices to analytics service
    - _Requirements: 2.2, 7.1, 7.2_

  - [x] 6.4 Update GET /api/dashboard metrics endpoints
    - Add device filter parameter to all dashboard metric endpoints
    - Apply device filtering in metric calculations
    - _Requirements: 4.2, 7.1, 7.2_

  - [ ]\* 6.5 Write property test for API device filtering
    - **Property 3: Device Filter Correctness** (API variant)
    - Generate random payloads
    - Call API endpoints with device filters
    - Verify response contains only matching devices
    - Tag: **Feature: device-filter-reporting, Property 3: Device filter correctness (API)**

  - [ ]\* 6.6 Write property test for paginated filter consistency
    - **Property 11: Paginated Filter Consistency**
    - **Validates: Requirements 7.4**
    - Generate large random dataset
    - Apply device filter with pagination
    - Fetch all pages
    - Assert sum equals total count, no duplicates
    - Tag: **Feature: device-filter-reporting, Property 11: Paginated filter consistency**

  - [ ]\* 6.7 Write unit test for API parameter optionality
    - **Property 13: API Parameter Optionality**
    - **Validates: Requirements 7.1, 8.3**
    - Test requests without device parameter
    - Verify identical behavior to legacy system
    - Tag: **Feature: device-filter-reporting, Property 13: API parameter optionality**

- [ ] 7. Checkpoint - Verify API contract changes
  - Test API endpoints manually using curl/Postman
  - Verify backward compatibility (requests without device param work)
  - Ask the user if questions arise

- [x] 8. Create reusable device filter frontend component
  - [x] 8.1 Create GlassDeviceFilterComponent
    - Implement Angular standalone component
    - Use app-glass-select with multi-select support
    - Add deviceOptions signal and selectedDevices model
    - Emit deviceChange events
    - _Requirements: 2.1, 3.1, 4.1_

  - [x] 8.2 Add getDistinctDevices method to StagingSessionService
    - Implement HTTP GET to /api/sessions/devices
    - Support optional sessionId parameter
    - Return Observable<string[]>
    - _Requirements: 2.5, 7.3_

  - [ ]\* 8.3 Write unit test for device filter component rendering
    - Test component initializes correctly
    - Test device options populate from service
    - Test selection changes emit events
    - Tag: **Feature: device-filter-reporting, Device filter component**

- [x] 9. Integrate device filter into Analytics page
  - [x] 9.1 Add device filter component to analytics.component.html
    - Place device filter in filter bar
    - Bind to devices signal
    - _Requirements: 2.1_

  - [x] 9.2 Update analytics.component.ts to handle device filter
    - Add devices signal
    - Update query methods to include devices parameter
    - Apply filter to analytics API calls
    - _Requirements: 2.2, 2.3_

  - [ ]\* 9.3 Write property test for analytics filter state persistence
    - **Property 5: Filter State Persistence** (Analytics variant)
    - **Validates: Requirements 2.4**
    - Generate random device selections
    - Apply filters
    - Simulate navigation and return
    - Assert state restored
    - Tag: **Feature: device-filter-reporting, Property 5: Filter state persistence (Analytics)**

  - [ ]\* 9.4 Write unit test for default unfiltered behavior
    - **Property 4: Unfiltered Default Behavior** (Analytics)
    - **Validates: Requirements 2.3, 8.2**
    - Load analytics without device filter
    - Verify results match complete dataset
    - Tag: **Feature: device-filter-reporting, Property 4: Unfiltered default behavior (Analytics)**

- [x] 10. Integrate device filter into My Sessions page
  - [x] 10.1 Add device filter component to my-sessions.component.ts
    - Add device filter to template
    - Bind to devices signal
    - _Requirements: 3.1_

  - [x] 10.2 Update my-sessions component to apply device filter
    - Add devices parameter to getSessions calls
    - Filter session list by device
    - _Requirements: 3.2, 3.4_

  - [x] 10.3 Update session detail view to display device
    - Add device column to payload table
    - Show "N/A" for NULL devices
    - _Requirements: 3.3_

  - [ ]\* 10.4 Write property test for session detail device display
    - **Property 7: Session Detail Device Display**
    - **Validates: Requirements 3.3**
    - Generate random sessions with mixed NULL/non-NULL devices
    - Render detail view
    - Assert all payloads show device or "N/A"
    - Tag: **Feature: device-filter-reporting, Property 7: Session detail device display**

  - [ ]\* 10.5 Write unit test for multi-device filtering
    - Test selecting multiple devices simultaneously
    - Verify results include sessions matching any selected device (OR logic for same field)
    - Tag: **Feature: device-filter-reporting, Multi-device filtering**

- [x] 11. Integrate device filter into Dashboard
  - [x] 11.1 Add device filter component to dashboard.component.html
    - Place in dashboard filter bar
    - Bind to devices signal
    - _Requirements: 4.1_

  - [x] 11.2 Update dashboard.component.ts to apply device filter
    - Add devices parameter to metric API calls
    - Update all metric cards to respect device filter
    - _Requirements: 4.2, 4.4_

  - [x] 11.3 Apply device filter to real-time SSE updates
    - Filter incoming SSE events by active device filter
    - Only update metrics for matching devices
    - _Requirements: 4.3_

  - [ ]\* 11.4 Write property test for real-time filter application
    - **Property 8: Real-Time Filter Application**
    - **Validates: Requirements 4.3**
    - Generate random SSE updates
    - Apply device filter
    - Verify only matching updates appear in metrics
    - Tag: **Feature: device-filter-reporting, Property 8: Real-time filter application**

  - [ ]\* 11.5 Write property test for dashboard filter state persistence
    - **Property 5: Filter State Persistence** (Dashboard variant)
    - **Validates: Requirements 4.5**
    - Apply device filter
    - Refresh page
    - Assert filter state restored
    - Tag: **Feature: device-filter-reporting, Property 5: Filter state persistence (Dashboard)**

- [x] 12. Update discovery preview to support device filtering
  - [x] 12.1 Verify device column appears in preview table
    - Check DiscoveryPreviewRow includes device
    - Verify preview table displays device column
    - _Requirements: 5.2_

  - [x] 12.1a Verify metadata columns appear in preview table
    - Check DiscoveryPreviewRow includes metadataId and dataId
    - Verify preview table displays metadataId column
    - Verify preview table displays dataId column
    - Show "N/A" for NULL metadata values
    - _Requirements: 5.2, 5.3_

  - [x] 12.2 Add device filter to preview controls
    - Add device filter to discovery preview interface
    - Apply filter to preview results
    - _Requirements: 5.5_

  - [ ]\* 12.3 Write property test for preview filter accuracy
    - **Property 10: Preview Filter Accuracy**
    - **Validates: Requirements 5.5**
    - Generate random metadata with devices
    - Apply device filter in preview
    - Verify all displayed rows match filter
    - Tag: **Feature: device-filter-reporting, Property 10: Preview filter accuracy**

  - [ ]\* 12.4 Write property test for discovery device retrieval
    - **Property 9: Discovery Device Retrieval**
    - **Validates: Requirements 5.1, 5.2**
    - Query metadata from external source with devices
    - Verify device information is retrieved and displayed
    - Tag: **Feature: device-filter-reporting, Property 9: Discovery device retrieval**

  - [ ]\* 12.5 Write property test for discovery metadata display
    - **Property 14: Discovery Metadata Display**
    - **Validates: Requirements 5.2, 5.3**
    - Generate random metadata with device, metadataId, dataId
    - Render discovery preview
    - Assert metadataId and dataId are visible in preview table
    - Tag: **Feature: device-filter-reporting, Property 14: Discovery metadata display**

  - [ ]\* 12.6 Write property test for discovery metadata preservation
    - **Property 15: Discovery Metadata Preservation**
    - **Validates: Requirements 5.3, 5.4**
    - Generate random metadata with device, metadataId, dataId
    - Stage payloads from preview
    - Query staging table
    - Assert all three fields (device, metadataId, dataId) preserved
    - Tag: **Feature: device-filter-reporting, Property 15: Discovery metadata preservation**

- [x] 13. Final integration and testing
  - [x] 13.1 Run full test suite (unit + property tests)
    - Execute all backend tests
    - Execute all frontend tests
    - Verify minimum 100 iterations per property test
    - _Requirements: All_

  - [x] 13.2 Manual end-to-end testing
    - Test discovery → staging → analytics flow with device filtering
    - Verify metadata (metadataId, dataId) visible in preview
    - Verify metadata persisted through staging
    - Test My Sessions device filtering
    - Test Dashboard device filtering with real-time updates
    - Test backward compatibility (legacy data with NULL devices)
    - _Requirements: All_

  - [x] 13.3 Update API documentation
    - Document new /api/sessions/devices endpoint
    - Document device parameter for existing endpoints
    - Provide curl examples
    - _Requirements: 7.1, 7.2, 7.3_

- [x] 14. Final checkpoint - Deployment readiness
  - All tests passing
  - No regressions detected
  - Documentation updated
  - Ask the user if ready to deploy

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties across many inputs
- Unit tests validate specific examples and edge cases
- Both testing approaches are complementary and necessary for comprehensive coverage
- The database migration must be tested in a dev environment before production deployment
- Device filtering should be tested with both NULL and non-NULL device values to ensure backward compatibility
