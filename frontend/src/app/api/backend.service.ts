import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { catchError, map, timeout } from 'rxjs/operators';
import { environment } from '../../environments/environment';

// ============================================================================
// Dashboard Interfaces
// ============================================================================
export interface DashboardMetricTotals {
  total: number;
  stagedToRefdb: number;
  queuedForCp: number;
  elasticsearchMonitoring: number;
  cpTimeout: number;
  exensioMonitoring: number;
  completedManualVerification: number;
  cpFailed: number;
  loadFailed: number;
  completed: number;
  cancelled: number;
  backlog: number;
  activeSenders: number;
  activeUsers?: number;

  // Backward-compatible accessors for old field names
  /** @deprecated Use stagedToRefdb */
  ready?: number;
  /** @deprecated Use queuedForCp */
  queued?: number;
  /** @deprecated Use elasticsearchMonitoring */
  enriching?: number;
  /** @deprecated Use cpTimeout */
  enrichmentTimeout?: number;
  /** @deprecated Use exensioMonitoring */
  exensioLoading?: number;
  /** @deprecated Use completedManualVerification */
  exensioTimeout?: number;
  /** @deprecated Use cpFailed + loadFailed */
  failed?: number;
  /** @deprecated Use queuedForCp + elasticsearchMonitoring + exensioMonitoring */
  enqueued?: number;
}
  completed: number;
  backlog: number;
  activeSenders: number;
  activeUsers?: number;
}

/**
 * Represents an SSE state change event for timeout state transitions.
 * Emitted by backend when a record transitions to ENRICHMENT_TIMEOUT or EXENSIO_TIMEOUT.
 * Validates: Requirements 7.1, 7.2
 */
export interface StateChangeEvent {
  timestamp: string;
  requestId: string;
  beforeState: string; // e.g., "ENRICHMENT" or "EXENSIO_LOADING"
  afterState: string; // e.g., "ENRICHMENT_TIMEOUT" or "EXENSIO_TIMEOUT"
  count: number; // Number of records transitioning in this batch
  recordIds?: string[];
}

export interface DashboardSenderSnapshot {
  senderId: number;
  senderLabel: string;
  senderName?: string;
  metrics: DashboardMetricTotals;
  alert?: boolean;
}

export interface DashboardSiteSnapshot {
  site: string;
  metrics: DashboardMetricTotals;
  senders: DashboardSenderSnapshot[];
}

export interface DashboardSnapshot {
  global: DashboardMetricTotals;
  sites: DashboardSiteSnapshot[];
  generatedAt?: string;
}

// ============================================================================
// Discovery/Preview Interfaces
// ============================================================================
export interface DiscoveryPreviewRequest {
  site: string;
  environment?: string | null;
  startDate?: string | null;
  endDate?: string | null;
  lots?: string[] | null;
  wafers?: string[] | null;
  pairs?: Array<{ lot?: string | null; wafer?: string | null }> | null;
  devices?: string[] | null;
  testerType?: string | null;
  dataType?: string | null;
  dataTypeExt?: string | null;
  testPhase?: string | null;
  location?: string | null;
  locationId?: number | null;
  page: number;
  size: number;
  bypassCap?: boolean | null;
  historicalMode?: boolean | null;
  requestId?: string | null;
}

export interface DiscoveryPreviewRow {
  metadataId: string;
  dataId?: string | null;
  device?: string | null;
  lot: string;
  wafer: string;
  filename: string;
  originalFileName?: string;
  endTime?: string;
}

export interface DiscoveryPreviewResponse {
  total: number;
  rows: DiscoveryPreviewRow[];
  returned?: number;
  page?: number;
  size?: number;
  debugSql?: string;
  capped?: boolean;
  bypass?: boolean;
  message?: string | null;
}

export interface HistoricalPreviewSummary {
  total: number;
  oldestEndTime: string | null;
  latestEndTime: string | null;
  message?: string | null;
}

export interface DuplicatePayloadInfo {
  metadataId: string;
  dataId: string;
  previousStatus: string | null;
  processedAt: string | null;
  stagedBy: string | null;
  stagedAt: string | null;
  lastRequestedBy: string | null;
  lastRequestedAt: string | null;
  requiresConfirmation?: boolean;
  wafer?: string | null;
}

export interface DiscoveryPreviewWithDuplicatesResponse extends DiscoveryPreviewResponse {
  duplicates?: { [key: string]: DuplicatePayloadInfo };
  previewDurationMs?: number;
  duplicateDurationMs?: number;
  discoveryToken?: string | null;
}

// ============================================================================
// Lot Verification Interfaces
// ============================================================================
export interface LotVerificationRequest {
  lots: string[];
  site: string;
  environment: string;
  blocks?: Array<{ year: number; month: number }> | null;
}

export interface LotVerificationResponse {
  lotExists: Map<string, boolean> | Record<string, boolean>;
  error?: string | null;
}

// ============================================================================
// Staging Interfaces
// ============================================================================
export interface StagePayloadRequestItem {
  metadataId: string;
  dataId: string;
  lot?: string | null;
  wafer?: string | null;
  filename?: string | null;
  endTime?: string | null;
}

export interface StagePayloadRequestBody {
  site: string;
  environment?: string | null;
  senderId?: number | null;
  senderName?: string | null;
  payloads: StagePayloadRequestItem[];
  triggerDispatch: boolean;
  forceDuplicates?: boolean;
  requestId?: string | null;
  dataType?: string | null;
  testPhase?: string | null;
}

export interface StageAllRequestBody {
  site: string;
  environment?: string | null;
  senderId?: number | null;
  senderName?: string | null;
  startDate?: string | null;
  endDate?: string | null;
  lots?: string[] | null;
  wafers?: string[] | null;
  pairs?: Array<{ lot?: string | null; wafer?: string | null }> | null;
  testerType?: string | null;
  dataType?: string | null;
  dataTypeExt?: string | null;
  testPhase?: string | null;
  location?: string | null;
  locationId?: number | null;
  startPage?: number | null;
  pageSize?: number | null;
  maxRows?: number | null;
  historicalMode?: boolean | null;
  bypassCap?: boolean | null;
  triggerDispatch: boolean;
  forceDuplicates?: boolean;
  requestId?: string | null;
}

export interface StagePayloadResponseBody {
  staged: number;
  duplicates: number;
  duplicatePayloads?: DuplicatePayloadInfo[];
  dispatched: number;
  requiresConfirmation?: boolean;
  message?: string | null;
  /** Records that already existed and were re-queued rather than freshly inserted */
  requeued?: number;
}

export interface StageRecordView {
  id?: number;
  lot: string;
  wafer: string;
  device?: string | null;
  filename: string;
  status: string;
  updated?: string | null;
  updatedAt?: string | null;
  metadataId?: string | null;
  dataId?: string | null;
  site?: string;
  senderId?: number;
  errorMessage?: string | null;
  createdAt?: string | null;
  processedAt?: string | null;
  endTime?: string | null;
  stagedBy?: string | null;
  requestId?: string | null;
  cpOutputPath?: string | null;
  cpOutputTarget?: string | null;
  // Per-file integration status
  cpIntegrationStatus?: string | null;
  cpIntegrationMessage?: string | null;
  exensioIntegrationStatus?: string | null;
  exensioIntegrationMessage?: string | null;
}

export interface StageRecordPage {
  items: StageRecordView[];
  total: number;
  page: number;
  size: number;
}

export interface StageStatus {
  site: string;
  senderId: number;
  senderName: string | null;
  total: number;
  ready: number;
  enqueued: number;
  failed: number;
  completed: number;
  users?: any[];
}

// ============================================================================
// Filter & Configuration Interfaces
// ============================================================================
export interface ReloadFilterOptions {
  locations: string[];
  dataTypes: string[];
  testerTypes: string[];
  dataTypeExt?: string[];
}

export interface SenderOption {
  idSender?: number | null;
  id?: number | null;
  name: string;
}

export interface LimitsConfig {
  previewMaxRowsCap: number;
  previewFetchCap: number;
  stagePageSizeCap: number;
  stageMaxRowsCap: number;
  stageDefaultMaxRows: number;
}

export interface DownloadUrlTemplate {
  template: string;
  direct?: boolean;
}

// ============================================================================
// Environment & Location Interfaces
// ============================================================================
export interface ExternalEnvironment {
  id?: number;
  name: string;
  label?: string;
  description?: string;
}

export interface ExternalInstance {
  key: string;
  label: string;
  environment: string;
}

export interface ExternalLocationSummary {
  id?: number;
  site?: string;
  name: string;
  label?: string;
  description?: string;
  dbConnectionName?: string;
  environmentId?: number;
  details?: string;
}

// ============================================================================
// Dispatch & Queue Interfaces
// ============================================================================
export interface DispatchRequest {
  site: string;
  senderId?: number | null;
  limit?: number | null;
}

export interface DispatchResponse {
  site: string;
  senderId: number;
  dispatched: number;
}

export interface EnqueueRequest {
  senderId: number | null;
  payloadIds: string[];
  source?: string;
}

export interface EnqueueResponse {
  enqueuedCount?: number;
  enqueued?: number;
  skippedPayloads?: string[];
  skipped?: string[];
}

// ============================================================================
// Session Interfaces
// ============================================================================
export interface ActiveStagingSession {
  requestId: string;
  site: string;
  senderId: number;
  senderName: string;
  status: 'READY' | 'STAGING' | 'COMPLETED' | 'ERROR';
  totalFiles: number;
  completedFiles: number;
  failedFiles: number;
  progress: number;
  startTime: string;
  lastActivity: string;
  estimatedTimeRemaining?: string;
  historicalMode: boolean;
  username: string;
}

export interface SessionActivity {
  timestamp: string;
  type: 'FILE_STARTED' | 'FILE_COMPLETED' | 'FILE_FAILED' | 'STATUS_CHANGE';
  filename?: string;
  message: string;
  details?: any;
}

export interface SessionPerformance {
  filesPerSecond: number;
  averageProcessingTime: number;
  successRate: number;
  errorRate: number;
  throughputTrend: number[];
}

export interface SessionDetailsResponse {
  session: ActiveStagingSession;
  recentActivity?: SessionActivity[];
  performance?: SessionPerformance;
}

export interface CreateSessionRequest {
  site: string;
  senderId: number;
  senderName?: string | null;
  environment?: string | null;
}

export interface CreateSessionResponse {
  sessionId: string;
  requestId?: string | null;
  status?: string | null;
  message?: string | null;
}

export interface StagingSessionDetail {
  sessionId: string;
  username: string;
  site: string;
  senderId: number;
  senderName?: string | null;
  environment?: string | null;
  status: 'STAGING' | 'DISPATCHING' | 'MONITORING' | 'COMPLETED' | 'PARTIALLY_FAILED' | 'CANCELLED';
  totalFiles: number;
  filesStaged: number;
  filesEnqueued: number;
  filesDone: number;
  filesFailed: number;
  createdAt?: string | null;
  updatedAt?: string | null;
  completedAt?: string | null;
  lastCheckedAt?: string | null;
  progress: number;
  integration?: IntegrationStatusSnapshot;
}

export interface IntegrationStatusEntry {
  configured: boolean;
  status: string;
  message: string;
  lastAt?: string | null;
}

export interface IntegrationStatusSnapshot {
  elasticsearch: IntegrationStatusEntry;
  exensio: IntegrationStatusEntry;
}

export interface StagingSessionSummary {
  sessionId: string;
  username: string;
  site: string;
  senderId: number;
  senderName?: string | null;
  environment?: string | null;
  status: string;
  totalFiles: number;
  filesStaged: number;
  filesEnqueued: number;
  filesDone: number;
  filesFailed: number;
  createdAt?: string | null;
  updatedAt?: string | null;
  completedAt?: string | null;
  progress: number;
}

export interface StagingSessionPage {
  items: StagingSessionSummary[];
  total: number;
  page: number;
  size: number;
}

export interface LotWaferProgress {
  lot: string;
  wafer: string;
  totalFiles: number;
  doneFiles: number;
  failedFiles: number;
  status: string;
}

export interface SessionDailyStatusPoint {
  day: string;
  total: number;
  done: number;
  enqueued: number;
  failed: number;
  cancelled: number;
  staged: number;
}

export interface SessionLotWaferPairTotal {
  lot: string;
  wafer: string;
  total: number;
}

export interface SessionLotWaferDailyPoint {
  day: string;
  lot: string;
  wafer: string;
  count: number;
}

export interface SessionAnalyticsResponse {
  sessionId: string;
  dailyStatus: SessionDailyStatusPoint[];
  topLotWaferPairs: SessionLotWaferPairTotal[];
  lotWaferHeatmap: SessionLotWaferDailyPoint[];
}

// ============================================================================
// Alert & Threshold Interfaces (Phase 4.4)
// ============================================================================
export interface AlertThreshold {
  senderId: number;
  backlogThreshold: number; // Alert when backlog exceeds this
  failureRateThreshold: number; // Alert when failure rate exceeds this %
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface SenderAlert {
  alertId: string;
  senderId: number;
  senderName: string;
  alertType: 'BACKLOG_EXCEEDED' | 'FAILURE_RATE_EXCEEDED' | 'CONNECTION_LOST';
  threshold: number;
  currentValue: number;
  severity: 'INFO' | 'WARNING' | 'CRITICAL';
  triggered_at: string;
  acknowledged: boolean;
  acknowledged_by?: string;
  acknowledged_at?: string;
}

export interface AlertNotification {
  type: 'EMAIL' | 'WEBHOOK' | 'SLACK';
  enabled: boolean;
  config: Record<string, any>;
}

export interface AlertConfiguration {
  emailNotifications?: { enabled: boolean; recipients: string[] };
  webhookNotifications?: { enabled: boolean; url: string };
  slackNotifications?: { enabled: boolean; webhookUrl: string };
  defaultSeverity?: 'WARNING' | 'CRITICAL';
  retentionDays?: number;
}

// ============================================================================
// Scheduled Report Interfaces (Phase 4.5)
// ============================================================================
export interface ScheduledReport {
  reportId?: string;
  name: string;
  description?: string;
  frequency: 'DAILY' | 'WEEKLY' | 'MONTHLY';
  dayOfWeek?: 'MON' | 'TUE' | 'WED' | 'THU' | 'FRI' | 'SAT' | 'SUN'; // for weekly reports
  dayOfMonth?: number; // for monthly reports
  time: string; // HH:mm format
  format: 'CSV' | 'EXCEL' | 'PDF';
  includeMetrics: string[]; // e.g., ['backlog', 'completed', 'throughput']
  recipients: string[]; // email addresses
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
  lastRun?: string;
}

// ============================================================================
// Coverage Report
// ============================================================================
export interface CoveragePoint {
  bucket: string;
  senderId: number;
  site: string;
  total: number;
  done: number;
  enqueued: number;
  staged: number;
  failed: number;
}

@Injectable({
  providedIn: 'root',
})
export class BackendService {
  private apiUrl = environment.apiUrl;

  constructor(private http: HttpClient) {}

  private toParams(params: Record<string, any>): HttpParams {
    let httpParams = new HttpParams();
    Object.entries(params || {}).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== '') {
        httpParams = httpParams.set(key, String(value));
      }
    });
    return httpParams;
  }

  // ========================================================================
  // Configuration & Limits
  // ========================================================================

  /**
   * Fetch runtime limits from the backend.
   * Falls back to environment values on any error or timeout — never throws.
   * Requirements: 1.1, 1.2, 1.3
   */
  getLimits(): Observable<LimitsConfig> {
    const environmentFallbackLimits: LimitsConfig = {
      previewMaxRowsCap: environment.monitoring.monitorMaxRows,
      previewFetchCap: environment.monitoring.monitorMaxRows,
      stagePageSizeCap: environment.monitoring.monitorPageSize,
      stageMaxRowsCap: environment.monitoring.monitorMaxRows,
      stageDefaultMaxRows: environment.monitoring.monitorMaxRows,
    };

    return this.http.get<LimitsConfig>(`${this.apiUrl}/config/limits`).pipe(
      timeout(5000),
      catchError(() => of(environmentFallbackLimits)),
    );
  }

  // ========================================================================
  // Dashboard
  // ========================================================================
  getDashboardSnapshot(devices?: string[]): Observable<DashboardSnapshot> {
    let params = new HttpParams();
    if (devices && devices.length > 0) {
      devices.forEach((device) => {
        params = params.append('devices', device);
      });
    }
    return this.http.get<DashboardSnapshot>(`${this.apiUrl}/dashboard/snapshot`, { params });
  }

  // ========================================================================
  // Environment & Instance Management
  // ========================================================================
  listEnvironments(): Observable<ExternalEnvironment[]> {
    return this.http.get<ExternalEnvironment[]>(`${this.apiUrl}/environments`);
  }

  /**
   * List all configured sites from dbconnections.yml.
   * Returns ALL sites (both PROD and QA variants).
   * Frontend should filter by environment suffix (-PROD or -QA).
   */
  listAllSites(): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiUrl}/environments/sites`);
  }

  /**
   * List sites for a specific environment.
   * Filters sites by suffix: PROD shows only -PROD sites, QA shows only -QA sites.
   * This matches the old frontend's _applyEnvFilterToSites() logic.
   * @param environment - 'PROD' or 'QA'
   */
  listSitesForEnvironment(environment: string): Observable<string[]> {
    return this.listAllSites().pipe(
      map((sites: string[]) => {
        console.log(`All sites from dbconnections.yml:`, sites);

        // Filter by environment suffix to ensure PROD doesn't show QA sites
        const suffix = environment === 'PROD' ? '-PROD' : '-QA';
        const filteredSites = sites.filter((site: string) => {
          if (typeof site !== 'string') return false;
          // Case-sensitive exact suffix match
          const matches = site.endsWith(suffix);
          return matches;
        });

        console.log(`Filtered ${filteredSites.length} sites for ${environment} (from ${sites.length} total)`);
        return filteredSites;
      }),
      catchError((err: any) => {
        console.error(`Failed to load sites for ${environment}:`, err);
        return of([]);
      }),
    );
  }

  /**
   * List all available sites/instances filtered by environment.
   * @deprecated Use listSitesForEnvironment() instead for better performance.
   */
  listInstances(environment: string): Observable<{ key: string; label: string; environment?: string }[]> {
    return this.http.get<string[]>(`${this.apiUrl}/sites`).pipe(
      map((sites: string[]) => {
        // Filter sites by environment suffix (-PROD or -QA)
        const suffix = environment === 'PROD' ? '-PROD' : '-QA';
        const filteredSites = (sites || []).filter((s) => {
          if (typeof s !== 'string') return false;
          // Case-sensitive exact suffix match
          return s.endsWith(suffix);
        });

        // Convert to expected format
        return filteredSites.map((site) => ({
          key: site,
          label: site,
          environment: environment,
        }));
      }),
    );
  }

  listLocations(environment: string): Observable<ExternalLocationSummary[]> {
    return this.http.get<ExternalLocationSummary[]>(
      `${this.apiUrl}/environments/${encodeURIComponent(environment)}/locations`,
    );
  }

  // ========================================================================
  // Sender Management
  // ========================================================================
  /**
   * Primary sender lookup endpoint with smart filtering and auto-resolution logic.
   * This endpoint tries to resolve to a single sender based on filters, and falls back
   * to returning multiple candidates if no unique match is found.
   *
   * Backend logic:
   * - If exactly 1 unique sender ID matches filters → Returns that sender (auto-resolve)
   * - If multiple senders match → Returns filtered candidates for dropdown
   * - If no matches → Returns all senders for the site
   *
   * This is the PREFERRED endpoint for sender lookup (not getExternalSenders).
   */
  lookupSenders(params: Record<string, any>): Observable<SenderOption[]> {
    return this.http.get<any[]>(`${this.apiUrl}/senders/lookup`, { params: this.toParams(params) }).pipe(
      map((response: any[]) => {
        // Backend returns array of {idSender, name, id, query}
        return (response || [])
          .map((item) => ({
            idSender: item.idSender ?? item.id_sender ?? item.id ?? null,
            id: item.id ?? item.idSender ?? item.id_sender ?? null,
            name: item.name ?? item.sender_name ?? '',
          }))
          .filter((s) => s.idSender != null);
      }),
    );
  }

  /**
   * Get external senders for a given location/connection.
   * Requires: connectionKey and environment in params
   *
   * NOTE: This endpoint returns ALL senders for the site without smart filtering.
   * Use lookupSenders() instead for auto-resolution logic.
   */
  getExternalSenders(params: Record<string, any>): Observable<SenderOption[]> {
    return this.http.get<SenderOption[]>(`${this.apiUrl}/senders/external/senders`, { params: this.toParams(params) });
  }

  /**
   * Get historical senders using HIST regex pattern matching.
   * Used in historical mode to auto-resolve senders based on data type and environment.
   * Requires: site, environment, dataType in params
   */
  getHistoricalSenders(params: Record<string, any>): Observable<SenderOption[]> {
    return this.http.get<SenderOption[]>(`${this.apiUrl}/senders/historical/senders`, {
      params: this.toParams(params),
    });
  }

  listSenders(): Observable<SenderOption[]> {
    // DEPRECATED: Use getExternalSenders() instead with proper params
    // Returns default fallback for backward compatibility
    return new Observable<SenderOption[]>(
      (observer: { next: (value: SenderOption[]) => void; complete: () => void }) => {
        observer.next([{ id: 1, name: 'default' }]);
        observer.complete();
      },
    );
  }

  // ========================================================================
  // Distinct Filter Values
  // ========================================================================
  getDistinctLocations(params: Record<string, any>): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiUrl}/senders/external/locations`, { params: this.toParams(params) });
  }

  getDistinctDataTypes(params: Record<string, any>): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiUrl}/senders/external/dataTypes`, { params: this.toParams(params) });
  }

  getDistinctTesterTypes(params: Record<string, any>): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiUrl}/senders/external/testerTypes`, { params: this.toParams(params) });
  }

  getDistinctTestPhases(params: Record<string, any>): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiUrl}/senders/external/testPhases`, { params: this.toParams(params) });
  }

  getDistinctDataTypeExts(params: Record<string, any>): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiUrl}/senders/external/dataTypeExts`, { params: this.toParams(params) });
  }

  getDistinctDevices(params: Record<string, any>): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiUrl}/senders/external/devices`, { params: this.toParams(params) });
  }

  // ========================================================================
  // Sender Queue Information
  // ========================================================================
  getSenderQueueCount(senderId: number, site?: string, env?: string): Observable<{ count: number }> {
    return this.http.get<{ count: number }>(`${this.apiUrl}/sender/${senderId}/queue-count`, {
      params: this.toParams({ site: site || '', env: env || '' }),
    });
  }

  getStageStatus(site: string, senderId: number): Observable<StageStatus> {
    return this.http.get<StageStatus>(`${this.apiUrl}/senders/${senderId}/stage-status`, {
      params: this.toParams({ site }),
    });
  }

  // ========================================================================
  // Discovery & Preview
  // ========================================================================
  getDiscoveryPreview(senderId: number, params: DiscoveryPreviewRequest): Observable<DiscoveryPreviewResponse> {
    return this.http.post<DiscoveryPreviewResponse>(`${this.apiUrl}/senders/${senderId}/discover/preview`, params);
  }

  getDiscoveryPreviewWithDuplicates(
    senderId: number,
    params: DiscoveryPreviewRequest,
  ): Observable<DiscoveryPreviewWithDuplicatesResponse> {
    return this.http.post<DiscoveryPreviewWithDuplicatesResponse>(
      `${this.apiUrl}/senders/${senderId}/discover/preview-with-duplicates`,
      params,
    );
  }

  getHistoricalSummary(senderId: number, params: DiscoveryPreviewRequest): Observable<HistoricalPreviewSummary> {
    return this.http.post<HistoricalPreviewSummary>(
      `${this.apiUrl}/senders/${senderId}/discover/historical-summary`,
      params,
    );
  }

  verifyLotsExistence(senderId: number, lots: string[]): Observable<LotVerificationResponse> {
    const request: LotVerificationRequest = {
      lots,
      site: 'default',
      environment: 'qa',
    };
    return this.http.post<LotVerificationResponse>(`${this.apiUrl}/senders/${senderId}/verify-lots`, request);
  }

  /**
   * Task 11: Verify lot existence with optional date range filtering.
   * When date range is provided (via PreCheckBlocks), verification filters lots by end_time.
   *
   * Requirements: 10.1, 10.2, 10.3, 10.5, 10.6
   */
  verifyLotsExistenceWithDateRange(
    senderId: number,
    lots: string[],
    blocks?: Array<{ year: number; month: number }> | null,
  ): Observable<LotVerificationResponse> {
    const request: LotVerificationRequest = {
      lots,
      site: 'default',
      environment: 'qa',
      blocks: blocks || null,
    };
    return this.http.post<LotVerificationResponse>(`${this.apiUrl}/senders/${senderId}/verify-lots`, request);
  }

  // ========================================================================
  // Staging
  // ========================================================================
  stagePayloads(senderId: number, body: StagePayloadRequestBody): Observable<StagePayloadResponseBody> {
    // Add 60 second timeout to prevent browser hang if backend is stuck
    return this.http.post<StagePayloadResponseBody>(`${this.apiUrl}/senders/${senderId}/stage`, body).pipe(
      timeout(60000),
      catchError((err: any) => {
        if (err.name === 'TimeoutError') {
          console.error('[API] stagePayloads timeout after 60s');
          throw new Error('Staging request timed out after 60 seconds. The backend may be overloaded or stuck.');
        }
        throw err;
      }),
    );
  }

  stageAll(senderId: number, body: StageAllRequestBody): Observable<StagePayloadResponseBody> {
    return this.http.post<StagePayloadResponseBody>(`${this.apiUrl}/senders/${senderId}/discover/stage-all`, body);
  }

  getStageRecords(params: any): Observable<StageRecordPage> {
    return this.http.get<StageRecordPage>(`${this.apiUrl}/staging/history`, { params: this.toParams(params) });
  }

  // ========================================================================
  // Dispatch & Queue Operations
  // ========================================================================
  dispatch(body: DispatchRequest): Observable<DispatchResponse> {
    return this.http.post<DispatchResponse>(`${this.apiUrl}/senders/${body.senderId}/dispatch`, body);
  }

  enqueue(body: EnqueueRequest): Observable<EnqueueResponse> {
    return this.http.post<EnqueueResponse>(`${this.apiUrl}/enqueue`, body);
  }

  // ========================================================================
  // Monitoring
  // ========================================================================
  getStageStats(requestId?: string): Observable<any> {
    const params = requestId ? { requestId } : {};
    return this.http.get<any>(`${this.apiUrl}/stage/stats`, { params: this.toParams(params) });
  }

  getStageRecordsList(
    site: string,
    senderId?: number,
    status?: string,
    page: number = 0,
    size: number = 50,
    requestId?: string,
  ): Observable<StageRecordPage> {
    const params: any = { site, page, size };
    if (senderId) params.senderId = senderId;
    if (status) params.status = status;
    if (requestId) params.requestId = requestId;
    return this.http.get<StageRecordPage>(`${this.apiUrl}/stage/records`, { params: this.toParams(params) });
  }

  createStagingSession(request: CreateSessionRequest): Observable<CreateSessionResponse> {
    return this.http.post<CreateSessionResponse>(`${this.apiUrl}/stage/sessions`, request);
  }

  getStagingSessions(
    page: number = 0,
    size: number = 20,
    filters?: {
      q?: string;
      senderId?: number | null;
      username?: string | null;
      sessionId?: string | null;
      site?: string | null;
      status?: string | null;
      devices?: string[];
    },
  ): Observable<StagingSessionPage> {
    const params: any = { page, size, ...(filters || {}) };
    return this.http.get<StagingSessionPage>(`${this.apiUrl}/stage/sessions`, {
      params: this.toParams(params),
    });
  }

  getStagingSession(sessionId: string): Observable<StagingSessionDetail> {
    return this.http.get<StagingSessionDetail>(`${this.apiUrl}/stage/sessions/${encodeURIComponent(sessionId)}`);
  }

  getStagingSessionFiles(
    sessionId: string,
    page: number = 0,
    size: number = 100,
    status?: string,
    q?: string,
  ): Observable<StageRecordPage> {
    const params: any = { page, size };
    if (status) params.status = status;
    if (q) params.q = q;
    return this.http.get<StageRecordPage>(`${this.apiUrl}/stage/sessions/${encodeURIComponent(sessionId)}/files`, {
      params: this.toParams(params),
    });
  }

  getStagingSessionLots(sessionId: string): Observable<LotWaferProgress[]> {
    return this.http.get<LotWaferProgress[]>(`${this.apiUrl}/stage/sessions/${encodeURIComponent(sessionId)}/lots`);
  }

  getStagingSessionAnalytics(
    sessionId: string,
    topPairs: number = 10,
    startDate?: string,
    endDate?: string,
  ): Observable<SessionAnalyticsResponse> {
    const params: any = { topPairs };
    if (startDate) params.startDate = startDate;
    if (endDate) params.endDate = endDate;
    return this.http.get<SessionAnalyticsResponse>(
      `${this.apiUrl}/stage/sessions/${encodeURIComponent(sessionId)}/analytics`,
      {
        params: this.toParams(params),
      },
    );
  }

  /**
   * Get distinct device values for filter dropdowns.
   *
   * Retrieves unique device identifiers from the staging table. Supports optional
   * filtering by session ID to get devices for a specific session.
   *
   * Validates: Requirements 2.5, 7.3 - Device filter options from API
   *
   * @param params Optional parameters including sessionId for filtering
   * @returns Observable<string[]> Array of unique device identifiers
   */
  getDistinctSessionDevices(params?: Record<string, any>): Observable<string[]> {
    return this.http.get<string[]>(`${this.apiUrl}/sessions/devices`, {
      params: this.toParams(params || {}),
    });
  }

  refreshStagingSession(sessionId: string): Observable<StagingSessionDetail> {
    return this.http.post<StagingSessionDetail>(
      `${this.apiUrl}/stage/sessions/${encodeURIComponent(sessionId)}/refresh`,
      {},
    );
  }

  cancelStagingSession(sessionId: string): Observable<any> {
    return this.http.post<any>(`${this.apiUrl}/stage/sessions/${encodeURIComponent(sessionId)}/cancel`, {});
  }

  getActiveStagingSessions(): Observable<ActiveStagingSession[]> {
    return this.getStagingSessions(0, 200).pipe(
      map((page: StagingSessionPage) =>
        (page.items || []).map(
          (item: StagingSessionSummary) =>
            ({
              requestId: item.sessionId,
              site: item.site,
              senderId: item.senderId,
              senderName: item.senderName || '',
              status: (item.status === 'PARTIALLY_FAILED'
                ? 'ERROR'
                : item.status === 'COMPLETED'
                  ? 'COMPLETED'
                  : 'STAGING') as any,
              totalFiles: item.totalFiles,
              completedFiles: item.filesDone,
              failedFiles: item.filesFailed,
              progress: item.progress,
              startTime: item.createdAt || '',
              lastActivity: item.updatedAt || '',
              estimatedTimeRemaining: undefined,
              historicalMode: false,
              username: item.username,
            }) as ActiveStagingSession,
        ),
      ),
    );
  }

  getSessionDetails(requestId: string): Observable<SessionDetailsResponse> {
    return this.getStagingSession(requestId).pipe(
      map((session: StagingSessionDetail) => ({
        session: {
          requestId: session.sessionId,
          site: session.site,
          senderId: session.senderId,
          senderName: session.senderName || '',
          status: (session.status === 'PARTIALLY_FAILED'
            ? 'ERROR'
            : session.status === 'COMPLETED'
              ? 'COMPLETED'
              : 'STAGING') as any,
          totalFiles: session.totalFiles,
          completedFiles: session.filesDone,
          failedFiles: session.filesFailed,
          progress: session.progress,
          startTime: session.createdAt || '',
          lastActivity: session.updatedAt || '',
          estimatedTimeRemaining: undefined,
          historicalMode: false,
          username: session.username,
        },
        recentActivity: [],
        performance: undefined,
      })),
    );
  }

  stopStagingSession(requestId: string): Observable<any> {
    return this.cancelStagingSession(requestId);
  }

  // ========================================================================
  // Bulk Operations (Phase 4.2)
  // ========================================================================
  /**
   * Resume monitoring for multiple senders
   * @param senderIds Array of sender IDs to resume
   * @returns Observable with operation status
   */
  bulkResumeMonitoring(senderIds: number[]): Observable<{ success: number; failed: number; message: string }> {
    return this.http.post<{ success: number; failed: number; message: string }>(
      `${this.apiUrl}/dashboard/bulk/resume`,
      { senderIds },
    );
  }

  /**
   * Pause monitoring for multiple senders
   * @param senderIds Array of sender IDs to pause
   * @returns Observable with operation status
   */
  bulkPauseMonitoring(senderIds: number[]): Observable<{ success: number; failed: number; message: string }> {
    return this.http.post<{ success: number; failed: number; message: string }>(`${this.apiUrl}/dashboard/bulk/pause`, {
      senderIds,
    });
  }

  /**
   * Export data for multiple senders as CSV
   * @param senderIds Array of sender IDs
   * @param format 'csv' or 'excel'
   * @returns Observable with download URL or blob
   */
  bulkExportData(senderIds: number[], format: 'csv' | 'excel' = 'csv'): Observable<Blob> {
    return this.http.post(`${this.apiUrl}/dashboard/bulk/export`, { senderIds, format }, { responseType: 'blob' });
  }

  /**
   * Delete/reset data for multiple senders
   * @param senderIds Array of sender IDs
   * @returns Observable with operation status
   */
  bulkDeleteData(senderIds: number[]): Observable<{ success: number; failed: number; message: string }> {
    return this.http.post<{ success: number; failed: number; message: string }>(
      `${this.apiUrl}/dashboard/bulk/delete`,
      { senderIds },
    );
  }

  // ========================================================================
  // Alert Management (Phase 4.4)
  // ========================================================================
  /**
   * Get alert thresholds for a sender
   */
  getAlertThresholds(senderId: number): Observable<AlertThreshold> {
    return this.http.get<AlertThreshold>(`${this.apiUrl}/alerts/sender/${senderId}/thresholds`).pipe(
      catchError(() =>
        of({
          senderId,
          backlogThreshold: 1000,
          failureRateThreshold: 10,
          enabled: true,
        } as AlertThreshold),
      ),
    );
  }

  /**
   * Update alert thresholds for a sender
   */
  updateAlertThresholds(senderId: number, thresholds: AlertThreshold): Observable<AlertThreshold> {
    return this.http.put<AlertThreshold>(`${this.apiUrl}/alerts/sender/${senderId}/thresholds`, thresholds);
  }

  /**
   * Get all alerts for a sender
   */
  getSenderAlerts(senderId: number): Observable<SenderAlert[]> {
    return this.http.get<SenderAlert[]>(`${this.apiUrl}/alerts/sender/${senderId}`).pipe(catchError(() => of([])));
  }

  /**
   * Get global alert configuration
   */
  getAlertConfiguration(): Observable<AlertConfiguration> {
    return this.http.get<AlertConfiguration>(`${this.apiUrl}/alerts/configuration`).pipe(
      catchError(() =>
        of({
          emailNotifications: { enabled: false, recipients: [] },
          webhookNotifications: { enabled: false, url: '' },
          slackNotifications: { enabled: false, webhookUrl: '' },
        } as AlertConfiguration),
      ),
    );
  }

  /**
   * Update global alert configuration
   */
  updateAlertConfiguration(config: AlertConfiguration): Observable<AlertConfiguration> {
    return this.http.put<AlertConfiguration>(`${this.apiUrl}/alerts/configuration`, config);
  }

  // ========================================================================
  // Export & Reporting (Phase 4.5)
  // ========================================================================
  /**
   * Export dashboard snapshot as CSV
   */
  exportDashboardCSV(): Observable<Blob> {
    return this.http.get(`${this.apiUrl}/dashboard/export/csv`, { responseType: 'blob' });
  }

  /**
   * Export dashboard snapshot as Excel
   */
  exportDashboardExcel(): Observable<Blob> {
    return this.http.get(`${this.apiUrl}/dashboard/export/excel`, { responseType: 'blob' });
  }

  /**
   * Create a scheduled report
   */
  createScheduledReport(report: ScheduledReport): Observable<ScheduledReport> {
    return this.http.post<ScheduledReport>(`${this.apiUrl}/reports/scheduled`, report);
  }

  /**
   * Get all scheduled reports
   */
  getScheduledReports(): Observable<ScheduledReport[]> {
    return this.http.get<ScheduledReport[]>(`${this.apiUrl}/reports/scheduled`).pipe(catchError(() => of([])));
  }

  /**
   * Update a scheduled report
   */
  updateScheduledReport(reportId: string, report: ScheduledReport): Observable<ScheduledReport> {
    return this.http.put<ScheduledReport>(`${this.apiUrl}/reports/scheduled/${reportId}`, report);
  }

  /**
   * Delete a scheduled report
   */
  deleteScheduledReport(reportId: string): Observable<any> {
    return this.http.delete(`${this.apiUrl}/reports/scheduled/${reportId}`);
  }

  // ========================================================================
  // Data Coverage Report
  // ========================================================================
  getCoverage(params: {
    site: string;
    senderId?: number | null;
    granularity?: 'day' | 'week' | 'month';
    endTimeFrom?: string | null;
    endTimeTo?: string | null;
    devices?: string[] | null;
  }): Observable<CoveragePoint[]> {
    let httpParams = this.toParams(params as any);
    if (params.devices && params.devices.length > 0) {
      params.devices.forEach((device) => {
        httpParams = httpParams.append('devices', device);
      });
    }
    return this.http.get<CoveragePoint[]>(`${this.apiUrl}/stage/records/coverage`, {
      params: httpParams,
    });
  }

  // ========================================================================
  // Dashboard State Stream (Real-time Timeout State Updates)
  // ========================================================================

  /**
   * Connect to the dashboard state stream for real-time timeout state updates.
   * Emits StateChangeEvent for ENRICHMENT_TIMEOUT and EXENSIO_TIMEOUT transitions.
   *
   * Validates: Requirements 7.1, 7.2, 7.3, 7.4
   * Property 9: Frontend SSE Event Handling
   *
   * @returns Observable that emits StateChangeEvent for timeout state transitions
   */
  connectDashboardStateStream(): Observable<StateChangeEvent> {
    return new Observable<StateChangeEvent>((observer) => {
      const url = `${this.apiUrl}/dashboard/states`;

      try {
        const eventSource = new EventSource(url);

        eventSource.addEventListener('CP_TIMEOUT', (event: any) => {
          try {
            const data: StateChangeEvent = JSON.parse(event.data);
            observer.next(data);
          } catch (e) {
            console.error('Failed to parse ENRICHMENT_TIMEOUT event:', e);
          }
        });

        eventSource.addEventListener('COMPLETED_MANUAL_VERIFICATION_REQUIRED', (event: any) => {
          try {
            const data: StateChangeEvent = JSON.parse(event.data);
            observer.next(data);
          } catch (e) {
            console.error('Failed to parse EXENSIO_TIMEOUT event:', e);
          }
        });

        eventSource.addEventListener('error', () => {
          console.error('Dashboard state stream error');
          eventSource.close();
          observer.error(new Error('Dashboard state stream closed'));
        });

        eventSource.onopen = () => {
          console.log('Dashboard state stream connected');
        };

        // Cleanup function
        return () => {
          eventSource.close();
        };
      } catch (error) {
        console.error('Failed to connect to dashboard state stream:', error);
        observer.error(error);
        return () => {};
      }
    });
  }
}
