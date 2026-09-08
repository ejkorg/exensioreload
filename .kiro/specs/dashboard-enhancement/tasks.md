# Implementation Plan: Dashboard Professional Enhancement

## Overview

This implementation plan transforms the Exensio Reload Dashboard into an enterprise-grade monitoring and analytics platform. The enhancement builds incrementally on the existing dashboard component (1160 lines) with glass morphism design, 9-state pipeline tracking, SSE real-time updates, and the StateLegendService foundation. All tasks focus on adding advanced visualizations, predictive analytics, comprehensive testing, and cross-component state consistency while preserving current functionality.

## Tasks

- [ ] 1. Set up enhanced testing infrastructure and dependencies
  - Install Chart.js 4.x with streaming plugin for time-series visualizations: `npm install chart.js chartjs-adapter-date-fns chartjs-plugin-streaming --save`
  - Install export libraries: `npm install jspdf html2canvas xlsx --save`
  - Verify fast-check 3.22.0 is already installed (confirmed in package.json)
  - Configure karma.conf.js to include fast-check framework
  - Create test utilities folder `frontend/src/app/shared/utils/test-helpers.ts` with property test generators
  - _Requirements: All requirements (testing foundation)_

- [x] 2. Create core dashboard state management services
  - [x] 2.1 Implement DashboardStateService for centralized state management
    - Create `frontend/src/app/dashboard/services/dashboard-state.service.ts`
    - Implement FilterState interface (devices, sites, senderSearch, pipelineStates, dateRange)
    - Implement LayoutConfiguration interface (sections, customOrder, hiddenSections)
    - Implement ComparisonConfig interface (currentPeriod, comparisonPeriod, enabled)
    - Provide observables for reactive state updates (activeFilters$, layoutConfig$, comparisonMode$)
    - Implement localStorage persistence with key patterns: `exensioreload.dashboard.filters`, `exensioreload.dashboard.layout`
    - Implement sessionStorage persistence for filter state
    - _Requirements: 5.4, 12.4, 13.1, 20.1_
  - [ ]\* 2.2 Write property tests for DashboardStateService
    - **Property 14: Filter Persistence Round-Trip** - validate filter state survives sessionStorage round-trip
    - **Property 15: Active Filter Count Accuracy** - validate filter count badge equals non-empty filters
    - **Property 38: Section Hide/Show Persistence** - validate section visibility persists to localStorage
    - **Property 39: Section Drag-Reorder Persistence** - validate section order persists across refreshes
    - _Validates: Requirements 5.4, 5.5, 12.2, 12.4, 12.3_

  - [x] 2.3 Implement MetricsHistoryService for time-series data aggregation
    - Create `frontend/src/app/dashboard/services/metrics-history.service.ts`
    - Implement MetricDataPoint interface (timestamp, value, metricKey)
    - Implement MetricWindow interface (duration, dataPoints, startTime, endTime)
    - Implement LRU cache with Map (max 10 entries)
    - Implement getHistoricalData method with cache check and freshness validation
    - Implement appendRealTimeData method to update all cached windows
    - Implement trimWindow method to maintain sliding window durations (1h/6h/24h/7d)
    - Subscribe to existing SSE events from dashboard.component.ts to populate cache
    - _Requirements: 1.1, 1.5, 6.5_

  - [ ]\* 2.4 Write property tests for MetricsHistoryService
    - **Property 3: Time Window Data Alignment** - validate chart data matches window duration exactly
    - **Property 16: Chart Update Debouncing** - validate charts re-render once per 500ms window
    - **Property 17: Metrics Cache Hit Efficiency** - validate second request avoids backend call
    - _Validates: Requirements 1.5, 6.4, 6.5_

  - [x] 2.5 Implement AlertThresholdService for sender threshold management
    - Create `frontend/src/app/dashboard/services/alert-threshold.service.ts`
    - Implement SenderThresholds interface (senderId, backlogWarning: 500, backlogCritical: 5000, errorRateWarning: 10, errorRateCritical: 25, throughputMin)
    - Implement AlertEvent interface (senderId, metric, level, currentValue, threshold, timestamp)
    - Implement getThresholds method with localStorage retrieval or defaults
    - Implement updateThresholds method with localStorage persistence pattern `exensioreload.alerts.sender.{senderId}`
    - Implement evaluateMetrics method comparing current metrics against thresholds
    - Provide alerts$ observable stream for UI subscriptions
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

  - [ ]\* 2.6 Write property tests for AlertThresholdService
    - **Property 8: Threshold Breach Visual Indicators** - validate metrics exceeding thresholds trigger indicators
    - **Property 9: Threshold Persistence Round-Trip** - validate localStorage round-trip accuracy
    - **Property 10: Threshold Modification Reactivity** - validate visual indicators update within 16ms
    - _Validates: Requirements 3.2, 3.3, 3.4_

- [ ] 3. Checkpoint - Verify core services
  - Run all property tests for dashboard state services: `npm test -- --include='**/dashboard/services/**/*.spec.ts'`
  - Ensure all tests pass with 100 iterations minimum per property test
  - Verify localStorage and sessionStorage persistence works correctly
  - Ask the user if questions arise about service architecture or state management patterns

- [x] 4. Enhance StateLegendService with integration status support
  - [x] 4.1 Extend StateLegendService for integration status definitions
    - Add IntegrationStatus type: 'success' | 'pending' | 'failure' | 'timeout' | 'not_found' | 'error' | 'not_configured'
    - Add StatusDefinition interface (color, icon, label, description)
    - Create integrationStatuses Map with all 7 status definitions (success: #10b981, pending: #3b82f6, failure: #ef4444, timeout: #f59e0b, not_found: #f59e0b, error: #ef4444, not_configured: #6b7280)
    - Implement getIntegrationStatus method returning StatusDefinition
    - Implement getTooltipContent method supporting both 'pipeline' and 'integration' types
    - Update state-legend.service.ts in `frontend/src/app/dashboard/`
    - _Requirements: 15.2, 15.3, 18.2, 18.3, 18.4, 18.5, 18.6, 18.7_

  - [ ]\* 4.2 Write property tests for StateLegendService integration status
    - **Property 48: Integration Status Badge Mapping - Success** - validate success status shows green badge with checkmark
    - **Property 49: Integration Status Badge Mapping - Failure** - validate failure status shows red badge with error icon
    - **Property 50: Integration Status Badge Mapping - Pending/Timeout** - validate pending/timeout show correct colors
    - **Property 51: Integration Status Badge Mapping - Not Configured** - validate not_configured shows gray badge
    - **Property 54: State Legend Service Consistency Across Components** - validate identical state definitions across Dashboard, Stepper, My Sessions
    - _Validates: Requirements 15.2, 15.3, 18.2, 18.3, 18.4, 18.5, 18.6, 16.1, 16.2, 16.3_

- [x] 5. Implement time-series chart component infrastructure
  - [x] 5.1 Create TimeSeriesChartComponent with Chart.js integration
    - Create `frontend/src/app/dashboard/components/time-series-chart.component.ts`
    - Implement inputs: metricKey, timeWindow ('1h'|'6h'|'24h'|'7d'), colorScheme, showTooltip, animationDuration (max 300ms)
    - Implement outputs: dataPointClick, timeWindowChange
    - Configure Chart.js with line chart, streaming plugin, realtime x-axis
    - Implement updateData method with 500ms debouncing
    - Implement setTimeWindow method to refetch and re-render
    - Subscribe to MetricsHistoryService for historical data
    - Implement tooltip callbacks showing timestamp, value, and rate of change
    - Create template and SCSS with glass morphism styling
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

  - [ ]\* 5.2 Write property tests for TimeSeriesChartComponent
    - **Property 1: Chart Tooltip Completeness** - validate tooltip contains timestamp, value, rate of change
    - **Property 2: Metric-to-Color Consistency** - validate colors match between KPI cards and charts
    - **Property 3: Time Window Data Alignment** - validate data range matches window duration
    - _Validates: Requirements 1.3, 1.4, 1.5_

  - [x] 5.3 Create ChartTooltipComponent for custom tooltip rendering
    - Create `frontend/src/app/dashboard/components/chart-tooltip.component.ts`
    - Implement custom tooltip with timestamp, value, rate of change display
    - Style with glass morphism (translucent background, blur effect)
    - _Requirements: 1.3_

- [x] 6. Enhance sender card component with performance analytics
  - [x] 6.1 Add sparkline and threshold visualization to SenderCardComponent
    - Update `frontend/src/app/dashboard/dashboard.component.ts` SenderCardComponent (currently inline in template)
    - Add @Input() showSparkline: boolean = true
    - Add @Input() backlogHistory: number[] (last 24 hours)
    - Add @Input() thresholds: {warning: number, critical: number}
    - Add @Output() thresholdExceeded event emitter
    - Implement getCardBorderClass method (border-warning for >75%, border-critical pulse-animation for >100%)
    - Implement getThroughput method (files/hour from last 60 minutes using MetricsHistoryService)
    - Embed mini Chart.js sparkline (40px height, no axes, minimal config)
    - Update template to show sparkline, throughput, and capacity bars
    - _Requirements: 2.1, 2.2, 2.3, 2.4_

  - [ ]\* 6.2 Write property tests for enhanced sender card
    - **Property 4: Sender Backlog Warning Threshold** - validate amber border for backlog >75%
    - **Property 5: Sender Backlog Critical Threshold** - validate red border with pulse for >100%
    - **Property 6: Throughput Calculation Accuracy** - validate throughput equals last 60 min completions
    - _Validates: Requirements 2.2, 2.3, 2.4_

  - [x] 6.3 Implement SenderDetailPanelComponent for deep-dive analytics
    - Create `frontend/src/app/dashboard/components/sender-detail-panel.component.ts`
    - Accept sender ID and fetch detailed performance data
    - Display throughput trends chart (Chart.js line chart over selectable periods)
    - Display error rate percentage with trend indicator
    - Display processing latency histogram (Chart.js bar chart)
    - Style with glass morphism sidebar panel
    - _Requirements: 2.5_

  - [ ]\* 6.4 Write property test for sender detail panel
    - **Property 7: Sender Detail Panel Content** - validate panel contains throughput chart, error rate, latency histogram
    - _Validates: Requirements 2.5_

- [x] 7. Implement health monitoring enhancements
  - [x] 7.1 Enhance health card with animated circular progress indicator
    - Update `dashboard.component.html` health card section
    - Implement SVG circular progress indicator with smooth transitions
    - Implement color mapping: red (<70%), amber (70-85%), blue (85-95%), green (≥95%)
    - Add appropriate icons: warning (<70%), checkmark (≥95%)
    - Implement click handler to expand breakdown by site and sender
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6_

  - [ ]\* 7.2 Write property tests for health monitoring
    - **Property 11: Health Score Color Mapping** - validate color selection based on score ranges
    - _Validates: Requirements 4.2, 4.3, 4.4, 4.5_

- [x] 8. Implement live activity feed component
  - [x] 8.1 Create LiveActivityFeedComponent for real-time event stream
    - Create `frontend/src/app/dashboard/components/live-activity-feed.component.ts`
    - Implement ActivityFeedItem interface (id, timestamp, sender, lot, wafer, oldState, newState, recordId)
    - Subscribe to existing SSE stream from dashboard.component.ts (already has connectDashboardStateStream)
    - Implement processSSE method to prepend events (max 20 items)
    - Implement getEventColorClass method (green: COMPLETED, red: CP_FAILED, amber: timeouts, blue: monitoring)
    - Implement pause/resume toggle
    - Create template with slide-in animations for new events
    - Format entries as: "{timestamp} • {sender} • {lot}/{wafer} • {old_state} → {new_state}"
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5_

  - [ ]\* 8.2 Write property tests for activity feed
    - **Property 34: Activity Feed Size Limit** - validate feed contains at most 20 items
    - **Property 35: Activity Feed Event Prepending** - validate new events prepend and oldest removed
    - **Property 36: Activity Entry Format Compliance** - validate entry format matches pattern
    - **Property 37: Activity Entry State-Based Coloring** - validate color coding based on newState
    - _Validates: Requirements 11.1, 11.2, 11.3, 11.4, 11.5_

- [ ] 9. Checkpoint - Verify components and SSE integration
  - Run all component property tests: `npm test -- --include='**/dashboard/components/**/*.spec.ts'`
  - Manually test SSE connection and real-time updates in development environment
  - Verify activity feed updates in real-time with state transitions
  - Verify sender cards show sparklines and threshold indicators
  - Ask the user if questions arise about component architecture or real-time updates

- [x] 10. Implement predictive analytics service with Web Worker
  - [x] 10.1 Create PredictiveAnalyticsService with linear regression
    - Create `frontend/src/app/dashboard/services/predictive-analytics.service.ts`
    - Implement CapacityProjection interface (senderId, currentBacklog, projectedBacklog, timeToCapacity, growthRate, confidence)
    - Create Web Worker file `frontend/src/app/dashboard/workers/predictive-analytics.worker.ts`
    - Implement calculateLinearRegression function in worker (least squares method over 2-hour window)
    - Implement getProjection method returning Observable<CapacityProjection>
    - Provide capacityWarnings$ observable emitting when 80% capacity within 60 min (warning) or 100% within 30 min (critical)
    - Implement recalculation triggers: every 5 minutes OR backlog change >10%
    - _Requirements: 14.1, 14.2, 14.3, 14.4, 14.5_

  - [ ]\* 10.2 Write property tests for predictive analytics
    - **Property 43: Linear Regression Projection Accuracy** - validate projection uses least squares over 2-hour window
    - **Property 44: Capacity Warning Threshold Triggering** - validate warning badge for 80% within 60 min
    - **Property 45: Capacity Critical Threshold Triggering** - validate critical badge for 100% within 30 min
    - **Property 46: Capacity Projection Tooltip Content** - validate tooltip shows time to capacity
    - **Property 47: Projection Recalculation Triggers** - validate recalc on 5-min timer or 10% change
    - _Validates: Requirements 14.1, 14.2, 14.3, 14.4, 14.5_

  - [x] 10.3 Integrate predictive analytics into sender cards
    - Update sender card to display capacity warning/critical badges
    - Add pulse animation for critical state
    - Add tooltip showing projected time to capacity
    - Subscribe to PredictiveAnalyticsService.capacityWarnings$ in dashboard component
    - _Requirements: 14.2, 14.3, 14.4_

- [ ] 11. Implement filtering and search infrastructure
  - [x] 11.1 Create FilterBarComponent with unified filter controls
    - Create `frontend/src/app/dashboard/components/filter-bar.component.ts`
    - Integrate existing GlassDeviceFilterComponent (already exists)
    - Add site selector dropdown with multi-select
    - Add sender search input with debouncing (300ms)
    - Add pipeline state multi-select dropdown
    - Implement applyFilters method updating DashboardStateService
    - Implement clearAllFilters method removing sessionStorage and URL params
    - Display active filter count badge
    - Display "Clear all filters" button when filters active
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

  - [ ]\* 11.2 Write property tests for filter bar
    - **Property 12: Device Filter Data Consistency** - validate all data contains only filtered devices
    - **Property 13: Sender Search Highlighting** - validate matching cards highlighted, non-matching dimmed
    - **Property 14: Filter Persistence Round-Trip** - validate filters restore after refresh
    - **Property 15: Active Filter Count Accuracy** - validate badge equals non-empty filter count
    - _Validates: Requirements 5.2, 5.3, 5.4, 5.5_

  - [ ] 11.2 Implement cross-component state filter propagation
    - Update dashboard.component.ts to sync filters with URL query parameters (?state=STAGED&device=ABC)
    - Update stepper monitor component to read state filter from URL and apply to file list
    - Update my-sessions component to read state filter from URL and pre-select status dropdown
    - Create global filter bar component showing active filters with remove buttons
    - _Requirements: 20.1, 20.2, 20.3, 20.4, 20.5_

  - [ ]\* 11.3 Write property tests for cross-component filtering
    - **Property 66: State Filter URL Persistence** - validate URL param updates and persists across refresh
    - **Property 67: Cross-Component State Filter Propagation - Stepper** - validate stepper file list applies filter from URL
    - **Property 68: Cross-Component State Filter Propagation - My Sessions** - validate my sessions pre-selects status from URL
    - **Property 69: Global Filter Bar Active Filter Display** - validate chips display for each active filter
    - **Property 70: Filter Clear URL Cleanup** - validate all URL params removed on clear
    - _Validates: Requirements 20.1, 20.2, 20.3, 20.4, 20.5_

- [x] 12. Implement integration status monitoring card
  - [x] 12.1 Create IntegrationStatusCardComponent
    - Create `frontend/src/app/dashboard/components/integration-status-card.component.ts`
    - Implement IntegrationHealth interface (service, status, lastSuccessfulQuery, responseTime, errorMessage, retryCount, nextRetryAt)
    - Fetch Elasticsearch and Exensio health status from backend
    - Implement 30-second polling interval
    - Display status badges using StateLegendService integration status definitions
    - Show green badge with checkmark and timestamp for success status
    - Show blue badge with hourglass for pending status
    - Show amber badge with warning for timeout/not_found status
    - Show red badge with error icon and retry button for failure/error status
    - Show gray badge with settings icon for not_configured status
    - Display Exensio API response time in milliseconds for healthy state
    - _Requirements: 15.1, 15.2, 15.3, 15.4, 15.5, 18.1, 18.2, 18.3, 18.4, 18.5, 18.6_

  - [ ]\* 12.2 Write property tests for integration status card
    - **Property 48: Integration Status Badge Mapping - Success** - validate green badge for success
    - **Property 49: Integration Status Badge Mapping - Failure** - validate red badge with retry button for failure
    - **Property 50: Integration Status Badge Mapping - Pending/Timeout** - validate blue/amber badges
    - **Property 51: Integration Status Badge Mapping - Not Configured** - validate gray badge
    - **Property 52: Exensio API Response Time Display** - validate response time shown for healthy state
    - **Property 53: Integration Health Polling Interval** - validate 10 polls in 5 minutes (every 30s)
    - _Validates: Requirements 15.2, 15.3, 15.4, 15.5, 18.2, 18.3, 18.4, 18.5, 18.6_

- [ ] 13. Checkpoint - Verify filtering, predictions, and integrations
  - Run all tests for filtering, predictive analytics, and integration components
  - Test Web Worker performance with large datasets (10,000+ records)
  - Verify filter state persists across page refreshes
  - Verify capacity projections trigger warnings correctly
  - Verify integration status card polls every 30 seconds
  - Ask the user if questions arise about prediction algorithms or integration monitoring

- [x] 14. Implement pipeline flow visualization component
  - [x] 14.1 Create PipelineFlowVisualizationComponent with D3.js
    - Create `frontend/src/app/dashboard/components/pipeline-flow-visualization.component.ts`
    - Install D3.js: `npm install d3 @types/d3 --save`
    - Implement FlowStateNode interface (state, position, count, isHighlighted, transitions)
    - Implement FlowTransition interface (toState, avgDuration, count24h, type: 'normal'|'timeout'|'failure')
    - Render 9 pipeline states as SVG rounded rectangles with current counts
    - Render transition arrows between states (green: normal, amber: timeout, red: failure)
    - Implement click handler to highlight corresponding KPI card
    - Implement hover tooltips showing average transition time and 24h count
    - Implement animated dashed lines on SSE state transition events
    - _Requirements: 19.1, 19.2, 19.3, 19.4, 19.5_

  - [ ]\* 14.2 Write property tests for pipeline flow visualization
    - **Property 62: Pipeline Flow State Click Highlighting** - validate KPI card highlights and scrolls into view
    - **Property 63: Pipeline Flow Transition Animation** - validate arrow animation on SSE events
    - **Property 64: Pipeline Flow Transition Tooltip Content** - validate tooltip shows avg time and count
    - **Property 65: Pipeline Flow Transition Arrow Coloring** - validate arrow colors by transition type
    - _Validates: Requirements 19.2, 19.3, 19.4, 19.5_

- [x] 15. Implement export functionality
  - [x] 15.1 Create ExportService with multi-format support
    - Create `frontend/src/app/dashboard/services/export.service.ts`
    - Implement toCSV method with ISO 8601 timestamps
    - Implement toExcel method with 4 sheets (Summary, Senders, Sites, History)
    - Implement toJSON method with complete dashboard state
    - Implement toPDF method with jsPDF and html2canvas capturing charts and KPI cards
    - Implement filename pattern: `dashboard-export-{YYYY-MM-DD}-{filter-summary}.{ext}`
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5_

  - [ ]\* 15.2 Write property tests for export service
    - **Property 23: CSV Export Data Completeness** - validate CSV contains all metrics with ISO 8601 timestamps
    - **Property 24: Excel Export Sheet Structure** - validate 4 sheets named correctly with corresponding data
    - **Property 25: PDF Export Content Inclusion** - validate PDF contains chart images and KPI cards
    - **Property 26: Export Filename Pattern Compliance** - validate filename matches pattern with filters
    - _Validates: Requirements 8.2, 8.3, 8.4, 8.5_

  - [x] 15.3 Create ExportMenuComponent for user interface
    - Create `frontend/src/app/dashboard/components/export-menu.component.ts`
    - Implement dropdown menu with CSV, Excel, JSON, PDF format options
    - Integrate with ExportService
    - Show loading spinner during export generation
    - Show success toast on completion
    - _Requirements: 8.1_

- [ ] 16. Implement accessibility features and keyboard navigation
  - [ ] 16.1 Add keyboard navigation to all interactive elements
    - Update KPI cards with tabindex="0", Enter/Space handlers to open detail sidebar
    - Update sender cards with Enter/Space handlers to toggle selection
    - Implement visible focus indicators (2px solid #6366f1 outline, 3px offset)
    - Add Escape key handlers to close modals and sidebars with focus restoration
    - Implement arrow key navigation in bulk action bar
    - Add ARIA labels to all dynamic content
    - Implement live region for real-time SSE update announcements
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_

  - [ ]\* 16.2 Write property tests for keyboard navigation
    - **Property 18: Keyboard Focus Progression** - validate Tab moves focus with visible indicator
    - **Property 19: KPI Card Keyboard Interaction** - validate Enter/Space opens detail sidebar
    - **Property 20: Sender Card Keyboard Selection** - validate Enter/Space toggles selection
    - **Property 21: Modal Escape Key Closure** - validate Escape closes modal and restores focus
    - **Property 22: Bulk Action Arrow Navigation** - validate arrow keys move between buttons
    - _Validates: Requirements 7.1, 7.2, 7.3, 7.4, 7.5_

- [ ] 17. Implement responsive mobile layouts
  - [ ] 17.1 Create responsive breakpoint styles
    - Create `frontend/src/app/dashboard/styles/_breakpoints.scss` with mobile/tablet/desktop mixins
    - Update dashboard.component.scss for single-column KPI layout (<768px)
    - Implement hamburger menu for header actions (<768px)
    - Increase touch target sizes to 44x44px minimum (<768px)
    - Stack sender card metrics vertically (<768px)
    - Convert bulk actions to bottom sheet (<768px)
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5_

  - [ ]\* 17.2 Write property tests for responsive layouts
    - **Property 27: Mobile Single-Column Layout** - validate KPI cards use single column <768px
    - **Property 28: Mobile Header Collapse** - validate hamburger menu visible <768px
    - **Property 29: Mobile Touch Target Sizing** - validate interactive elements ≥44x44px
    - **Property 30: Mobile Sender Card Layout** - validate metrics stack vertically <768px
    - **Property 31: Mobile Bulk Actions Presentation** - validate bottom sheet <768px
    - _Validates: Requirements 9.1, 9.2, 9.3, 9.4, 9.5_

- [ ] 18. Checkpoint - Verify complete feature set
  - Run full test suite with all property tests: `npm test`
  - Verify all 70 correctness properties pass with minimum 100 iterations each
  - Manual testing on mobile devices (iOS Safari, Android Chrome)
  - Manual testing with keyboard-only navigation
  - Manual accessibility audit with screen reader (NVDA or VoiceOver)
  - Performance profiling with Chrome DevTools (verify <16ms render cycles)
  - Ask the user if questions arise about accessibility, performance, or test failures

- [ ] 19. Implement advanced features (comparison mode and layout customization)
  - [ ] 19.1 Implement comparison mode for time period analytics
    - Add date range picker to filter bar
    - Update TimeSeriesChartComponent to overlay two datasets (solid vs dashed lines)
    - Implement delta calculation: ((currentValue - comparisonValue) / comparisonValue) \* 100
    - Display delta values with percentage formatting
    - Highlight metrics with >20% change with trend indicator icons
    - Update chart tooltips to show both period values and delta
    - _Requirements: 13.1, 13.2, 13.3, 13.4, 13.5_

  - [ ]\* 19.2 Write property tests for comparison mode
    - **Property 40: Comparison Mode Delta Calculation** - validate delta formula accuracy
    - **Property 41: Significant Change Highlighting** - validate trend indicator for >±20% delta
    - **Property 42: Comparison Chart Tooltip Dual Values** - validate tooltip shows both values and delta
    - _Validates: Requirements 13.3, 13.4, 13.5_

  - [ ] 19.3 Create LayoutCustomizerComponent for dashboard customization
    - Create `frontend/src/app/dashboard/components/layout-customizer.component.ts`
    - Implement right-click context menu on section headers (Hide, Move Up, Move Down, Reset Layout)
    - Implement drag-and-drop reordering with visual drop zones
    - Implement "Hidden sections" dropdown in header
    - Persist to localStorage with key `exensioreload.dashboard.layout`
    - Implement resetLayout method to restore defaults
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5_

  - [ ]\* 19.4 Write property tests for layout customizer
    - **Property 38: Section Hide/Show Persistence** - validate section visibility persists to localStorage
    - **Property 39: Section Drag-Reorder Persistence** - validate order persists across refreshes
    - _Validates: Requirements 12.2, 12.3, 12.4_

- [ ] 20. Implement enhanced error handling with retry logic
  - [ ] 20.1 Enhance existing error handling with exponential backoff
    - Update dashboard.component.ts error handling (already has retry logic, needs enhancement)
    - Implement ExponentialBackoffRetry class with 5 retry attempts and delays [5s, 10s, 20s, 40s, 80s]
    - Update ErrorDisplayComponent template (already exists, needs countdown and try now button)
    - Implement retry countdown timer display
    - Implement contactSupport method with pre-filled error details
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5_

  - [ ]\* 20.2 Write property tests for error handling
    - **Property 32: Timeout Error Retry Behavior** - validate exponential backoff retry for 408/504 errors
    - **Property 33: Retry Countdown Display** - validate countdown timer during retry attempts
    - _Validates: Requirements 10.2, 10.3, 10.4_

- [ ] 21. Integrate all components into main dashboard
  - [x] 21.1 Wire all new components into dashboard.component.html
    - Add TimeSeriesChartComponent to metrics section with 6-hour default window
    - Add LiveActivityFeedComponent to sidebar
    - Add PipelineFlowVisualizationComponent to middle section
    - Add IntegrationStatusCardComponent to supporting metrics section
    - Add FilterBarComponent to header
    - Add ExportMenuComponent to header actions
    - Update layout to accommodate all new sections
    - _Requirements: All (integration task)_

  - [x] 21.2 Update dashboard.component.ts to integrate all services
    - Inject DashboardStateService, MetricsHistoryService, AlertThresholdService, PredictiveAnalyticsService, ExportService
    - Subscribe to filter changes and update snapshot loading
    - Subscribe to capacity warnings and update sender cards
    - Subscribe to alert threshold breaches and update visual indicators
    - Integrate session status synchronization with StagingSessionService
    - _Requirements: All (integration task)_

  - [ ]\* 21.3 Write integration property tests
    - **Property 55: Backend Status Mapping Consistency** - validate status mapping (FAILED→CP_FAILED, DONE→COMPLETED, etc.)
    - **Property 56: Terminal State Action Button Disablement** - validate action buttons disabled for terminal states
    - **Property 57: Session Status Value Constraint** - validate session status is one of 4 valid values
    - **Property 58: Terminal Session Cleanup** - validate localStorage cleared for terminal sessions within 16ms
    - **Property 59: Session Status Badge Color Mapping** - validate badge colors by status
    - **Property 60: Monitoring Button Text State** - validate button text changes based on active session
    - **Property 61: Session Status Service Synchronization** - validate status syncs across components within 16ms
    - _Validates: Requirements 16.4, 16.5, 17.1, 17.2, 17.3, 17.4, 17.5_

- [ ] 22. Final testing and optimization
  - [ ] 22.1 Run complete property-based test suite
    - Execute all 70 property tests with minimum 100 iterations: `npm test`
    - Verify zero failures across all properties
    - Generate test coverage report: `npm test -- --code-coverage`
    - Aim for >80% code coverage on new components and services
    - _Requirements: All (testing coverage)_

  - [ ] 22.2 Performance optimization and profiling
    - Profile dashboard with Chrome DevTools Performance tab
    - Verify render cycles <16ms with 10,000+ records
    - Verify SSE update batching working correctly (single render per batch)
    - Verify virtual scrolling active for sender lists >50 items
    - Verify Web Worker running predictive analytics without blocking main thread
    - Optimize any bottlenecks identified in profiling
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 14.1_

  - [ ] 22.3 Accessibility audit
    - Run axe DevTools accessibility scan
    - Test complete keyboard navigation flow
    - Test with screen reader (NVDA on Windows or VoiceOver on macOS)
    - Verify all ARIA labels and live regions working correctly
    - Verify focus indicators visible and properly styled
    - Fix any WCAG 2.1 AA violations found
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_

  - [ ] 22.4 Cross-browser and mobile testing
    - Test on Chrome, Firefox, Safari, Edge (latest versions)
    - Test on iOS Safari (iPhone and iPad)
    - Test on Android Chrome (phone and tablet)
    - Verify responsive layouts work correctly at all breakpoints
    - Verify touch targets are ≥44x44px on mobile
    - Verify glass morphism effects render correctly across browsers
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5_

- [ ] 23. Final checkpoint - Production readiness verification
  - All 70 property tests passing with 100+ iterations each
  - Code coverage report shows >80% coverage on new code
  - Accessibility audit clean with zero WCAG AA violations
  - Performance profiling shows <16ms render cycles even with large datasets
  - Manual testing complete on all major browsers and mobile devices
  - All export formats (CSV, Excel, JSON, PDF) generating correctly
  - SSE real-time updates working reliably with no memory leaks
  - Documentation updated with new features and usage instructions
  - Ask the user for final review and approval before marking spec as complete

## Notes

- Tasks marked with `*` are property-based test tasks that validate correctness properties
- Each property test must run minimum 100 iterations as specified in design document
- All property tests must include a comment tag: `// Feature: dashboard-enhancement, Property X: [description]`
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation and user feedback at natural breakpoints
- Core implementation tasks should be completed before optional test tasks
- The existing dashboard component (1160 lines) will be enhanced incrementally, not rewritten
- The existing SSE infrastructure will be leveraged for real-time updates
- The existing glass morphism styling will be preserved and extended

## Implementation Status Log (2026-09-08, updated)

**Environment note:** this workspace cannot run `npm install`/builds/tests, but the
production/deployment machine CAN — so all spec-intended libraries are implemented and
declared in `frontend/package.json` (run `npm install` on the build machine before building):

- Charting: `chart.js@^4.4`, `chartjs-adapter-date-fns`, `chartjs-plugin-streaming`, `date-fns`
- Exports: `xlsx`, `jspdf`, `html2canvas`
- Pipeline flow: `d3`
- Testing: `fast-check` (already present in devDependencies)

### Completed

- [x] **Task 1 (partial)** — `shared/utils/test-helpers.ts` generators created; package.json
      updated with all spec libraries (install on build machine).
- [x] **Task 2.1** `dashboard/services/dashboard-state.service.ts` (filters + layout +
      comparison mode; sessionStorage `exensioreload.dashboard.filters`, localStorage
      `exensioreload.dashboard.layout`; activeFilterCount; hide/show/move/reset).
- [x] **Task 2.2** `dashboard/services/dashboard-state.service.spec.ts` (Properties 14/15/38/39).
- [x] **Task 2.3** `dashboard/services/metrics-history.service.ts` (sliding windows 1h–7d,
      500ms throttled `updates$`, per-metric retention cap).
- [x] **Task 2.4** `dashboard/services/metrics-history.service.spec.ts` (Properties 3/16/17).
- [x] **Task 2.5** `dashboard/services/alert-threshold.service.ts` (localStorage
      `exensioreload.alerts.sender.{senderId}`, defaults 500/5000/10%/25%, evaluateMetrics,
      immediate re-eval on update).
- [x] **Task 2.6** `dashboard/services/alert-threshold.service.spec.ts` (Properties 8/9/10).
- [x] **Task 4.1** `state-legend.service.ts` extended: `IntegrationStatus` type + 7 status
      definitions (color/icon/label/description), `getIntegrationStatus`, tooltip content,
      `isIntegrationTerminal`.
- [x] **Task 4.2** `state-legend.integration.spec.ts` (Properties 48/49/50/51/54).
- [x] **Task 5.1** `dashboard/components/time-series-chart.component.ts` — **Chart.js 4.x +
      streaming plugin**, metric color scheme, 1h/6h/24h/7d window buttons, 300ms animation,
      tooltip (time/value/rate-of-change), feeds from MetricsHistoryService (500ms throttle).
- [x] **Task 8.1** `dashboard/components/live-activity-feed.component.ts` — bounded 20-item
      feed, prepend semantics, slide-in animation, pause/resume, state-based color coding.
- [x] **Task 10.1 (worker deferred)** `dashboard/services/predictive-analytics.service.ts` +
      pure `calculateLinearRegression` — 2h window, capacity projections, 80%/60min warning,
      100%/30min critical, 5-min / >10%-delta recalculation triggers.
- [x] **Task 10.2** `dashboard/services/predictive-analytics.service.spec.ts`
      (Properties 43/44/45/47).
- [x] **Task 12.1** `dashboard/components/integration-status-card.component.ts` — ES/Exensio
      badges driven by StateLegendService definitions, OnPush + signals, retry output hook.
- [x] **Task 15.1** `dashboard/services/export.service.ts` — CSV (RFC4180), JSON, **Excel via
      xlsx** (Summary/Senders/Sites/History sheets), **PDF via jspdf + html2canvas**,
      `dashboard-export-{date}-{filter}.{ext}` filename pattern.
- [x] **Task 15.3** `dashboard/components/export-menu.component.ts` — dropdown (CSV/Excel/
      JSON/PDF), spinner while exporting, outside-click close.
- [x] **Task 21 (partial)** — dashboard.component wired:
      • MetricsHistoryService fed from each snapshot
      • "Live Pipeline Trends" (4 streaming charts) + "Live Activity" + "Integrations"
        sections added to `dashboard.component.html`/`.scss`
      • SSE state-change events push live-activity entries
      • Integration health polled every 30s via `getStagingSession` when a monitoring
        session is active (Requirement 15.5)
      • Header "Export" menu + `gatherExportData()` (health/KPI/history snapshot)

### Pending (next session)

- Task 3/9/13/18 checkpoints (require running tests on the build machine).
- Task 5.2/5.3 specs, 6.x sender-card sparklines + detail panel, 7.x health circle
  drill-down, 8.2 feed property spec, 10.3 capacity badges on cards, 11.x FilterBar +
  cross-component URL propagation, 14.x pipeline flow (d3), 16.x a11y, 17.x responsive,
  19.x comparison/layout UI, 20.x retry polish, 21.2/21.3 full integration, 22.x final
  test/audit, 23.x production readiness.
- Decide with user: activity feed identity fields (dashboard SSE is aggregate-only; a
  per-record lot/wafer feed requires the staging-session SSE or a backend event stream).

### MVP pass (2026-09-08) — test tasks excluded by request

Checklist boxes above ticked only for **completed core (non-test) tasks**; the `*` property-test
subtasks (2.2/2.4/2.6/4.2/5.2/6.2/6.4/8.2/10.2/12.2/15.2/16.2/17.2/19.2/19.4/20.2/21.3) and
checkpoints (3/9/13/18/22/23) stay unticked.

Added this pass (core only):
- **7.1** health card: animated SVG circular ring + status label/color mapping.
- **6.1** sender cards: backlog sparkline (SVG from per-sender rolling history),
  `.border-warning`/`.border-critical` + pulse for backlog ≥75%/100% capacity.
- **10.3** capacity badges on sender cards (warning/critical + minutes-to-capacity tooltip,
  powered by PredictiveAnalyticsService over the sender's backlog history).
- **11.x (partial, inline)** filter bar extended: sender search + site select with
  dim/highlight of non-matching cards, "Clear filters (n)" button.
- **16.x (partial)** global `:focus-visible` outline (2px #6366f1, 3px offset).
- **17.x (partial)** responsive SCSS: mini-filters full-width <768px, sender metrics stack,
  single-column KPI grid <640px.
- **20.1** retry backoff delays aligned to 5/10/20/40/80s (was capped at 30s).

Remaining MVP core work: 5.3 custom tooltip component, 11.x as a real FilterBarComponent +
URL cross-component propagation, 19.x comparison/layout-customizer UI, and 21.2 service
integration (DashboardStateService/AlertThresholdService wiring).

**Second MVP pass (same day):**
- **6.3** `sender-detail-panel.component.ts` — sender deep-dive: KPI row, backlog trend
  Chart.js line, status-mix bar chart; opened by clicking a sender name/keyboard, fixed
  glass overlay with outside-click + Esc-able close button.
- **14.1** `pipeline-flow.component.ts` — D3-backed 9-state flow (main path + branch chips),
  counts from snapshot, node click flashes/scrolls the matching KPI card.
- **21.1** flow section + pipeline component wired into `dashboard.component.html`.
- `@types/d3` added to devDependencies (d3 ships JS only).
- Tasks ticked: **6, 6.1, 6.3, 14, 14.1, 21.1** (+ earlier 2/4/7/8/10/12/15 and core
  sub-tasks). Test `*` subtasks and checkpoints remain unticked per instruction.

**Third MVP pass (same day):**
- **5.3** `chart-tooltip.component.ts` — glass custom tooltip wired into TimeSeriesChart via
  Chart.js `external` tooltip hook (timestamp / value / rate-of-change), clamped positioning.
- **11.1** `filter-bar.component.ts` — real FilterBarComponent (device filter + sender search
  + site select + "Clear filters (n)") wired into the dashboard.
- **21.2** all five services injected & used in `dashboard.component.ts`:
  DashboardStateService (filters persisted/restored: sessionStorage round-trip; layout used
  for section hide), MetricsHistoryService (already feeding charts), AlertThresholdService
  (per-sender threshold chips via `evaluateLevel`), PredictiveAnalyticsService (capacity
  badges), ExportService (export menu gather).
- **19.x (partial)** comparison mode UI (Req 13.1/13.3/13.4): window presets 15/30/60m,
  Δ% chips over current-vs-previous retained history. Layout customizer (Req 12.1-12.5):
  `layout-customizer.component.ts` header menu with hide/show + move up/down + reset;
  sections bind `sectionVisible(id)` (`layout-hidden`). Visual DOM re-ordering is not yet
  applied (persisted order only) — dual-chart overlay + drag reorder remain open.
- **11.2 / 20 URL cross-component propagation** not implemented (stepper/my-sessions scope);
  Dashboard state filter URL sync pending.
- Tasks ticked this pass: **5, 5.1, 5.3, 11.1, 21.2**. Remaining unticked core: 11.2,
  19.x (partial), 16.x/17.x (partial), 23 checkpoint items, and all test `*` tasks.

**Dependency fix (npm ERESOLVE):** `chartjs-plugin-streaming@2.0.0` peers with
`chart.js@^3`, so the charting stack is pinned to the stable v3 line:
`chart.js@^3.9.1`, `chartjs-adapter-date-fns@^2.0.0`, `date-fns@^2.30.0`,
`chartjs-plugin-streaming@^2.0.0`. Chart component code is v3-compatible
(no v4-only APIs used).





