import { Injectable, NgZone, signal } from '@angular/core';
import { Observable, Subject, interval } from 'rxjs';
import { switchMap, takeWhile } from 'rxjs/operators';
import { environment } from '../../../environments/environment';

export interface MonitoringStats {
  total: number;
  ready: number;
  enqueued: number;
  enriching: number;
  enrichmentTimeout: number;
  exensioLoading: number;
  exensioTimeout: number;
  completed: number;
  failed: number;
  cancelled: number;
  processing?: number;
  progress: number; // 0-100
  throughput: number; // files per minute
  eta: string;
  successRate: number;
  startTime: Date | null;
  elapsedTime: string;
}

export interface MonitoringFile {
  id: string | number;
  metadataId?: string;
  dataId?: string;
  filename: string;
  lot: string;
  wafer: string;
  status:
    | 'READY'
    | 'QUEUED_FOR_CP'
    | 'ELASTICSEARCH_MONITORING'
    | 'CP_TIMEOUT'
    | 'EXENSIO_MONITORING'
    | 'COMPLETED_MANUAL_VERIFICATION_REQUIRED'
    | 'COMPLETED'
    | 'ERROR'
    | 'CP_FAILED'
    | 'LOAD_FAILED'
    | 'CANCELLED';
  message: string;
  errorMessage?: string;
  startTime?: Date;
  endTime?: Date;
  duration?: string;
  updatedAt?: string;
  cpOutputPath?: string | null;
  cpOutputTarget?: string | null;
  // Integration status fields for detail line display
  cpIntegrationStatus?: string; // "success" | "pending" | "failure" | "timeout" | "not_found" | "error" | "not_configured"
  cpIntegrationMessage?: string;
  exensioIntegrationStatus?: string; // "success" | "pending" | "failure" | "not_found" | "error" | "not_configured"
  exensioIntegrationMessage?: string;
}

export interface ActivityEvent {
  timestamp: Date;
  type: 'FILE_STARTED' | 'FILE_COMPLETED' | 'FILE_FAILED' | 'STATUS_CHANGE' | 'BATCH_COMPLETE';
  filename?: string;
  message: string;
  details?: any;
}

export interface MonitorEvent {
  type: 'STATS' | 'ROW_UPDATE' | 'COMPLETE' | 'ERROR';
  payload: any;
}

@Injectable({
  providedIn: 'root',
})
export class MonitoringService {
  private eventSource: EventSource | null = null;
  private pollingSubscription: any = null;

  // Signals for reactive state
  stats = signal<MonitoringStats>({
    total: 0,
    ready: 0,
    enqueued: 0,
    enriching: 0,
    enrichmentTimeout: 0,
    exensioLoading: 0,
    exensioTimeout: 0,
    processing: 0,
    completed: 0,
    failed: 0,
    cancelled: 0,
    progress: 0,
    throughput: 0,
    eta: 'Calculating...',
    successRate: 100,
    startTime: null,
    elapsedTime: '0s',
  });

  files = signal<MonitoringFile[]>([]);
  activities = signal<ActivityEvent[]>([]);
  isConnected = signal(false);
  lastUpdate = signal<Date | null>(null);

  private activitySubject = new Subject<ActivityEvent>();
  activity$ = this.activitySubject.asObservable();

  constructor(private zone: NgZone) {}

  /**
   * Connect to SSE endpoint for real-time monitoring
   */
  connectSSE(requestId: string, token: string): Observable<MonitorEvent> {
    return new Observable<MonitorEvent>((observer) => {
      const url = `${environment.apiUrl}/stage/monitor?requestId=${requestId}`;

      this.eventSource = new EventSource(url);

      this.eventSource.onopen = () => {
        this.zone.run(() => {
          this.isConnected.set(true);
          console.log('SSE connection established');
        });
      };

      this.eventSource.addEventListener('STATS', (event: any) => {
        this.zone.run(() => {
          try {
            const data = JSON.parse(event.data);
            observer.next({ type: 'STATS', payload: data });
          } catch (e) {
            console.error('Failed to parse STATS event:', e);
          }
        });
      });

      this.eventSource.addEventListener('ROW_UPDATE', (event: any) => {
        this.zone.run(() => {
          try {
            const data = JSON.parse(event.data);
            observer.next({ type: 'ROW_UPDATE', payload: data });
          } catch (e) {
            console.error('Failed to parse ROW_UPDATE event:', e);
          }
        });
      });

      this.eventSource.addEventListener('COMPLETE', (event: any) => {
        this.zone.run(() => {
          try {
            const data = JSON.parse(event.data);
            observer.next({ type: 'COMPLETE', payload: data });
          } catch (e) {
            console.error('Failed to parse COMPLETE event:', e);
          }
        });
      });

      this.eventSource.addEventListener('ERROR', (event: any) => {
        this.zone.run(() => {
          try {
            const data = JSON.parse(event.data);
            observer.next({ type: 'ERROR', payload: data });
          } catch (e) {
            console.error('Failed to parse ERROR event:', e);
          }
        });
      });

      this.eventSource.onerror = (error) => {
        this.zone.run(() => {
          console.error('SSE connection error:', error);
          this.isConnected.set(false);
          observer.error(error);
        });
      };

      // Cleanup function
      return () => {
        if (this.eventSource) {
          this.eventSource.close();
          this.eventSource = null;
          this.isConnected.set(false);
        }
      };
    });
  }

  /**
   * Start polling-based monitoring (fallback if SSE not available)
   */
  startPolling(getStats: () => Observable<any>, getFiles: () => Observable<any>, intervalMs: number = 3000) {
    this.pollingSubscription = interval(intervalMs)
      .pipe(
        takeWhile(() => !this.isComplete()),
        switchMap(() => getStats()),
      )
      .subscribe({
        next: (stats) => {
          this.updateStats(stats);
          this.lastUpdate.set(new Date());
        },
        error: (err) => {
          console.error('Polling error:', err);
        },
      });

    // Initial fetch
    getStats().subscribe((stats) => this.updateStats(stats));
    getFiles().subscribe((files) => this.updateFiles(files));
  }

  /**
   * Stop monitoring
   */
  stopMonitoring() {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }

    if (this.pollingSubscription) {
      this.pollingSubscription.unsubscribe();
      this.pollingSubscription = null;
    }

    this.isConnected.set(false);
  }

  /**
   * Update stats from backend data
   */
  updateStats(data: any) {
    const total = data.total || 0;
    const ready = data.ready || 0;
    const enqueued = data.enqueued || 0;
    const enriching = data.enriching || 0;
    const enrichmentTimeout = data.enrichmentTimeout || 0;
    const exensioLoading = data.exensioLoading || 0;
    const exensioTimeout = data.exensioTimeout || 0;
    const processing = enriching + enrichmentTimeout + exensioLoading + exensioTimeout;
    const completed = data.completed || 0;
    const failed = data.failed || 0;
    const cancelled = data.cancelled || 0;

    const processed = completed + failed;
    const progress = total > 0 ? Math.round((processed / total) * 100) : 0;
    const successRate = processed > 0 ? Math.round((completed / processed) * 100) : 0;

    // Calculate throughput and ETA
    const currentStats = this.stats();
    const startTime = currentStats.startTime || new Date();
    const elapsedMs = Date.now() - startTime.getTime();
    const elapsedMinutes = elapsedMs / 60000;
    const throughput = elapsedMinutes > 0 ? Math.round(processed / elapsedMinutes) : 0;

    const remaining = total - processed;
    const eta =
      throughput > 0 && remaining > 0
        ? this.formatETA(remaining / throughput)
        : remaining > 0
          ? 'Calculating...'
          : 'Complete';

    const elapsedTime = this.formatDuration(elapsedMs);

    this.stats.set({
      total,
      ready,
      enqueued,
      enriching,
      enrichmentTimeout,
      exensioLoading,
      exensioTimeout,
      processing,
      completed,
      failed,
      cancelled,
      progress,
      throughput,
      eta,
      successRate,
      startTime,
      elapsedTime,
    });
  }

  /**
   * Update file list
   */
  updateFiles(files: MonitoringFile[]) {
    this.files.set(files);
  }

  /**
   * Export current files as CSV and trigger download in browser.
   * Returns true if export started, false if there were no files.
   */
  exportFilesAsCsv(sessionId?: string, requestedBy?: string): boolean {
    const files = this.files() || [];
    if (!files || files.length === 0) return false;

    const headers = [
      'id',
      'metadataId',
      'dataId',
      'filename',
      'lot',
      'wafer',
      'status',
      'errorMessage',
      'updatedAt',
      'requestedBy',
    ];
    const rows: string[][] = files.map((f: MonitoringFile) => [
      String(f.id ?? ''),
      String(f.metadataId ?? ''),
      String(f.dataId ?? ''),
      String((f.filename || '').replace(/\r?\n|,/g, ' ')),
      String(f.lot ?? ''),
      String(f.wafer ?? ''),
      String(f.status ?? ''),
      String((f.errorMessage || '').replace(/\r?\n|,/g, ' ')),
      String(f.updatedAt ?? ''),
      String(requestedBy ?? ''),
    ]);

    const csv = [
      headers.join(','),
      ...rows.map((r: string[]) => r.map((cell: string) => `"${cell.replace(/"/g, '""')}"`).join(',')),
    ].join('\n');

    const blob = new Blob([csv], { type: 'text/csv' });
    const a = document.createElement('a');
    const sid = sessionId || 'session';
    a.href = URL.createObjectURL(blob);
    a.download = `session-${sid}-files-${new Date().toISOString()}.csv`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(a.href);
    return true;
  }

  /**
   * Update single file status
   */
  updateFile(file: MonitoringFile) {
    const currentFiles = this.files();
    const index = currentFiles.findIndex((f) => f.id === file.id || f.metadataId === file.metadataId);

    if (index >= 0) {
      const updated = [...currentFiles];
      updated[index] = { ...updated[index], ...file };
      this.files.set(updated);

      // Add activity event
      this.addActivity({
        timestamp: new Date(),
        type: this.getActivityType(file.status),
        filename: file.filename,
        message: this.getActivityMessage(file),
        details: file,
      });
    }
  }

  /**
   * Add activity event
   */
  addActivity(event: ActivityEvent) {
    const current = this.activities();
    const updated = [event, ...current].slice(0, 100); // Keep last 100 events
    this.activities.set(updated);
    this.activitySubject.next(event);
  }

  /**
   * Check if monitoring is complete
   */
  isComplete(): boolean {
    const stats = this.stats();
    return stats.total > 0 && stats.completed + stats.failed >= stats.total;
  }

  /**
   * Reset monitoring state
   */
  reset() {
    this.stats.set({
      total: 0,
      ready: 0,
      enqueued: 0,
      enriching: 0,
      enrichmentTimeout: 0,
      exensioLoading: 0,
      exensioTimeout: 0,
      processing: 0,
      completed: 0,
      failed: 0,
      cancelled: 0,
      progress: 0,
      throughput: 0,
      eta: 'Calculating...',
      successRate: 100,
      startTime: null,
      elapsedTime: '0s',
    });
    this.files.set([]);
    this.activities.set([]);
    this.lastUpdate.set(null);
  }

  /**
   * Format ETA in minutes
   */
  private formatETA(minutes: number): string {
    if (minutes < 1) return '< 1 minute';
    if (minutes < 60) return `${Math.round(minutes)} minutes`;
    const hours = Math.floor(minutes / 60);
    const mins = Math.round(minutes % 60);
    return `${hours}h ${mins}m`;
  }

  /**
   * Format duration
   */
  private formatDuration(ms: number): string {
    const seconds = Math.floor(ms / 1000);
    if (seconds < 60) return `${seconds}s`;
    const minutes = Math.floor(seconds / 60);
    const secs = seconds % 60;
    if (minutes < 60) return `${minutes}m ${secs}s`;
    const hours = Math.floor(minutes / 60);
    const mins = minutes % 60;
    return `${hours}h ${mins}m`;
  }

  /**
   * Get activity type from status
   */
  private getActivityType(status: string): ActivityEvent['type'] {
    switch (status) {
      case 'ELASTICSEARCH_MONITORING':
      case 'EXENSIO_MONITORING':
        return 'FILE_STARTED';
      case 'COMPLETED':
        return 'FILE_COMPLETED';
      case 'ERROR':
        return 'FILE_FAILED';
      default:
        return 'STATUS_CHANGE';
    }
  }

  /**
   * Get activity message
   */
  private getActivityMessage(file: MonitoringFile): string {
    switch (file.status) {
      case 'READY':
        return `File staged: ${file.filename}`;
      case 'QUEUED_FOR_CP':
        return `Queued for Enrichment: ${file.filename}`;
      case 'ELASTICSEARCH_MONITORING':
        return `Enrichment Processing: ${file.filename}`;
      case 'CP_TIMEOUT':
        return `Enrichment Monitoring Timeout: ${file.filename}`;
      case 'EXENSIO_MONITORING':
        return `Exensio Monitoring: ${file.filename}`;
      case 'COMPLETED_MANUAL_VERIFICATION_REQUIRED':
        return `Completed — Verify in Exensio: ${file.filename}`;
      case 'COMPLETED':
        return `Completed: ${file.filename}`;
      case 'ERROR':
        return `Failed: ${file.filename} - ${file.errorMessage || 'Unknown error'}`;
      default:
        return `Status changed: ${file.filename}`;
    }
  }
}
