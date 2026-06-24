import { computed, inject, Injectable, NgZone, signal } from '@angular/core';
import { interval, Subscription } from 'rxjs';
import { distinctUntilChanged, filter, skip } from 'rxjs/operators';
import { environment } from '../../../environments/environment';
import {
  BackendService,
  CreateSessionRequest,
  LotWaferProgress,
  StageRecordView,
  StagingSessionDetail,
} from '../../api/backend.service';
import { AuthService } from '../../auth/auth.service';
import { ActivityEvent } from '../components/activity-feed.component';
import { ToastService } from './toast.service';

export interface SessionActivityEvent {
  id: string;
  timestamp: Date;
  type: 'file' | 'lot' | 'session';
  message: string;
  icon: string;
  color: 'primary' | 'success' | 'warning' | 'error' | 'muted' | 'default';
}

export type SessionStreamStatus = 'idle' | 'connecting' | 'live' | 'polling' | 'error';

@Injectable({ providedIn: 'root' })
export class StagingSessionService {
  currentSession = signal<StagingSessionDetail | null>(null);
  sessionFiles = signal<StageRecordView[]>([]);
  lotProgress = signal<LotWaferProgress[]>([]);
  activities = signal<SessionActivityEvent[]>([]);
  isConnected = signal(false);
  streamStatus = signal<SessionStreamStatus>('idle');

  progress = computed(() => this.currentSession()?.progress ?? 0);
  isComplete = computed(() => {
    const status = this.currentSession()?.status;
    return status === 'COMPLETED' || status === 'PARTIALLY_FAILED' || status === 'CANCELLED';
  });

  private eventSource: EventSource | null = null;
  private pollingSub?: Subscription;
  private connectTimeoutHandle?: ReturnType<typeof setTimeout>;
  private readonly sseConnectTimeoutMs = Math.max(1000, environment.monitoring.sseConnectTimeoutMs);
  private readonly pollingIntervalMs = Math.max(1000, environment.monitoring.pollingIntervalMs);
  private readonly initialLoadDelayMs = Math.max(0, environment.monitoring.initialLoadDelayMs);
  private readonly pollingStartDelayMs = Math.max(0, environment.monitoring.pollingStartDelayMs);
  private readonly sseReconnectIntervalMs = Math.max(5000, environment.monitoring.sseReconnectIntervalMs);
  private currentSessionId: string | null = null;
  private nextSseRetryAt = 0;
  private _liveSnapshotTick = 0;
  private postConnectRefreshHandles: ReturnType<typeof setTimeout>[] = [];
  private rowUpdateRefreshHandle?: ReturnType<typeof setTimeout>;
  private tokenSubscription?: Subscription;
  /** Tracks file IDs that have already generated a terminal activity event, so that
   *  initial/polling loads don't re-push the same event. */
  private notifiedTerminalFileIds = new Set<number>();
  private authService = inject(AuthService);
  private toast = inject(ToastService) as ToastService;
  private readonly debugLogsEnabled = false;
  private limitsResolved = false;
  private monitorPageSize = environment.monitoring.monitorPageSize;
  private monitorMaxRows = environment.monitoring.monitorMaxRows;
  private fullFilesHydrationInFlight = false;
  private hydratedSessionId: string | null = null;

  constructor(
    private backend: BackendService,
    private zone: NgZone,
  ) {
    this.subscribeToTokenChanges();
    this.backend.getLimits().subscribe({
      next: (limits) => {
        this.monitorPageSize = limits.stagePageSizeCap;
        this.monitorMaxRows = limits.stageMaxRowsCap;
        this.limitsResolved = true;
      },
      error: () => {
        console.warn('[StagingSession] Failed to resolve backend limits; using environment fallback values.');
        this.limitsResolved = true;
      },
    });
  }

  private debugLog(...args: unknown[]): void {
    if (this.debugLogsEnabled) {
      console.log(...args);
    }
  }

  ngOnDestroy(): void {
    if (this.tokenSubscription) {
      this.tokenSubscription.unsubscribe();
    }
    this.disconnectSession();
  }

  createSession(site: string, senderId: number, senderName?: string | null, environment?: string | null) {
    const body: CreateSessionRequest = { site, senderId, senderName, environment };
    return this.backend.createStagingSession(body);
  }

  connectToSession(sessionId: string): void {
    this.debugLog('[StagingSession] connectToSession called with sessionId:', sessionId);

    this.disconnectSession();
    this.currentSessionId = sessionId;
    this.streamStatus.set('connecting');

    const startHydration = () => {
      this.debugLog('[StagingSession] Loading initial data...');
      this.loadSnapshot(sessionId);
      this.loadFiles(sessionId, 0, this.monitorPageSize);
      this.hydrateAllSessionFiles(sessionId);
      this.loadLotProgress(sessionId);
      this.debugLog('[StagingSession] Initial data load triggered');
    };

    if (this.limitsResolved) {
      setTimeout(startHydration, this.initialLoadDelayMs);
    } else {
      // Defer until limits are resolved (poll at 50ms intervals, max ~2s)
      let waited = 0;
      const waitInterval = 50;
      const maxWait = 2000;
      const waitForLimits = setInterval(() => {
        waited += waitInterval;
        if (this.limitsResolved || waited >= maxWait) {
          clearInterval(waitForLimits);
          setTimeout(startHydration, this.initialLoadDelayMs);
        }
      }, waitInterval);
    }

    this.connectSse(sessionId);

    setTimeout(() => {
      this.debugLog('[StagingSession] Starting polling with interval:', this.pollingIntervalMs);
      this.pollingSub = interval(this.pollingIntervalMs).subscribe(() => {
        if (this.isComplete()) {
          return;
        }

        const status = this.streamStatus();
        const hasData = !this.needsBootstrapData();

        if (status === 'live' && hasData) {
          // SSE is live and we have data — SSE handles real-time updates.
          // Do a lightweight snapshot refresh every 3 cycles (~9s) as a safety net
          // in case any STATS events were missed (e.g. during rapid bursts).
          this._liveSnapshotTick = ((this._liveSnapshotTick || 0) + 1) % 3;
          if (this._liveSnapshotTick === 0) {
            this.loadSnapshot(sessionId);
          }
        } else {
          // SSE is not live or we're missing bootstrap data — polling is the primary
          // update mechanism. Do a full refresh.
          this.debugLog('[StagingSession] Polling fallback update (status=%s, hasData=%s)...', status, hasData);
          this.loadSnapshot(sessionId);
          this.loadFiles(sessionId, 0, this.monitorPageSize);
          this.hydrateAllSessionFiles(sessionId);
          this.loadLotProgress(sessionId);
          this.tryReconnectSse(sessionId);
        }
      });
    }, this.pollingStartDelayMs);
  }

  refreshSession(sessionId: string): void {
    this.backend.refreshStagingSession(sessionId).subscribe({
      next: (detail: StagingSessionDetail) => {
        this.currentSession.set(detail);
        this.pushActivity('session', `Session refreshed (${detail.status})`, 'refresh', 'primary');
      },
    });
  }

  refreshSessionFiles(sessionId: string): void {
    this.loadFiles(sessionId, 0, this.monitorPageSize);
    this.hydrateAllSessionFiles(sessionId);
  }

  cancelSession(sessionId: string): void {
    this.backend.cancelStagingSession(sessionId).subscribe({
      next: () => {
        const current = this.currentSession();
        if (current) {
          this.currentSession.set({ ...current, status: 'CANCELLED' });
        }
        this.pushActivity('session', 'Session cancelled', 'cancel', 'warning');
      },
    });
  }

  disconnectSession(): void {
    this.postConnectRefreshHandles.forEach((handle) => clearTimeout(handle));
    this.postConnectRefreshHandles = [];
    if (this.rowUpdateRefreshHandle) {
      clearTimeout(this.rowUpdateRefreshHandle);
      this.rowUpdateRefreshHandle = undefined;
    }

    if (this.connectTimeoutHandle) {
      clearTimeout(this.connectTimeoutHandle);
      this.connectTimeoutHandle = undefined;
    }
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
    if (this.pollingSub) {
      this.pollingSub.unsubscribe();
      this.pollingSub = undefined;
    }
    this.currentSessionId = null;
    this.hydratedSessionId = null;
    this.fullFilesHydrationInFlight = false;
    this.nextSseRetryAt = 0;
    this._liveSnapshotTick = 0;
    this.isConnected.set(false);
    this.notifiedTerminalFileIds.clear();
    this.streamStatus.set('idle');
  }

  loadFiles(sessionId: string, page: number, size: number, status?: string, q?: string): void {
    this.debugLog('[StagingSession] loadFiles called:', { sessionId, page, size, status, q });
    this.backend.getStagingSessionFiles(sessionId, page, size, status, q).subscribe({
      next: (filePage: any) => {
        const rawItems: any[] = Array.isArray(filePage)
          ? filePage
          : Array.isArray(filePage?.items)
            ? filePage.items
            : Array.isArray(filePage?.content)
              ? filePage.content
              : [];
        const items: StageRecordView[] = rawItems.map((item: any) => this.normalizeStageRecord(item));

        this.debugLog('[StagingSession] Files loaded:', items.length, 'files');
        this.sessionFiles.set(items);

        // Push terminal activity events for files that reached COMPLETED/ERROR but haven't
        // been notified yet. This covers the initial page-load case where files finished
        // before the SSE connection was established, and polling refreshes that discover
        // newly-terminal files without going through the SSE FILE_UPDATE path.
        for (const file of items) {
          if (file.id != null && (file.status === 'COMPLETED' || file.status === 'ERROR')) {
            if (!this.notifiedTerminalFileIds.has(file.id)) {
              this.notifiedTerminalFileIds.add(file.id);
              this.buildAndPushTerminalActivityMessage(file);
            }
          }
        }

        const current = this.currentSession();
        if (current && (current.totalFiles || 0) === 0 && items.length > 0) {
          this.currentSession.set({
            ...current,
            totalFiles: items.length,
          });
        }
      },
      error: (err: unknown) => {
        console.error('[StagingSession] Failed to load files:', err);
        this.toast.error('Failed to load session files. Retrying automatically...', 7000);
      },
    });
  }

  loadLotProgress(sessionId: string): void {
    this.debugLog('[StagingSession] loadLotProgress called:', sessionId);
    this.backend.getStagingSessionLots(sessionId).subscribe({
      next: (lots: LotWaferProgress[]) => {
        this.debugLog('[StagingSession] Lot progress loaded:', lots?.length || 0, 'lots');
        this.lotProgress.set(lots || []);
      },
      error: (err: unknown) => {
        console.error('[StagingSession] Failed to load lot progress:', err);
        this.toast.error('Failed to load lot progress data', 7000);
      },
    });
  }

  private loadSnapshot(sessionId: string): void {
    this.debugLog('[StagingSession] loadSnapshot called:', sessionId);
    this.backend.getStagingSession(sessionId).subscribe({
      next: (detail: StagingSessionDetail) => {
        this.debugLog('[StagingSession] Session snapshot loaded:', detail);
        this.currentSession.set(detail);
      },
      error: (err: unknown) => {
        console.error('[StagingSession] Failed to load snapshot:', err);
        this.toast.error('Failed to refresh session status', 7000);
      },
    });
  }

  private connectSse(sessionId: string): void {
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }

    if (this.connectTimeoutHandle) {
      clearTimeout(this.connectTimeoutHandle);
      this.connectTimeoutHandle = undefined;
    }

    // Store session ID for token refresh reconnection
    this.currentSessionId = sessionId;

    // EventSource doesn't support custom headers, so we need to pass the token as a query parameter
    // Use AuthService to get the current token
    const token = this.authService.getToken() || '';
    const url = `/api/stage/sessions/${encodeURIComponent(sessionId)}/monitor?token=${encodeURIComponent(token)}`;

    this.debugLog('[StagingSession] Connecting SSE to:', url);
    this.debugLog('[StagingSession] Token length:', token.length);
    this.debugLog('[StagingSession] Creating EventSource...');

    try {
      this.eventSource = new EventSource(url);
      this.debugLog('[StagingSession] EventSource created, initial readyState:', this.eventSource.readyState);
    } catch (error) {
      console.error('[StagingSession] Failed to create EventSource:', error);
      this.toast.error('Failed to establish live session stream. Will use polling updates.', 7000);
      this.streamStatus.set('error');
      return;
    }

    this.connectTimeoutHandle = setTimeout(() => {
      if (!this.isConnected() && this.streamStatus() === 'connecting') {
        console.warn('[StagingSession] SSE connection timeout, falling back to polling');
        console.warn('[StagingSession] Final EventSource readyState:', this.eventSource?.readyState);
        this.streamStatus.set('polling');
        this.pushActivity(
          'session',
          'Live stream unavailable. Falling back to polling updates.',
          'wifi_off',
          'warning',
        );
      }
    }, this.sseConnectTimeoutMs);

    this.eventSource.onopen = (event) => {
      this.debugLog('[StagingSession] *** onopen fired! ***', event);
      this.zone.run(() => {
        this.debugLog('[StagingSession] SSE connection opened successfully (inside zone)');
        this.debugLog('[StagingSession] EventSource readyState:', this.eventSource?.readyState);
        this.isConnected.set(true);
        this.streamStatus.set('live');
        this.nextSseRetryAt = 0;
        if (this.connectTimeoutHandle) {
          clearTimeout(this.connectTimeoutHandle);
          this.connectTimeoutHandle = undefined;
        }

        this.schedulePostConnectRefresh(sessionId);
      });
    };

    this.eventSource.onerror = (error) => {
      console.error('[StagingSession] *** onerror fired! ***', error);
      this.zone.run(() => {
        console.error('[StagingSession] SSE connection error:', error);
        console.error('[StagingSession] EventSource readyState:', this.eventSource?.readyState);
        console.error('[StagingSession] EventSource URL:', this.eventSource?.url);

        // ReadyState: 0 = CONNECTING, 1 = OPEN, 2 = CLOSED
        const readyState = this.eventSource?.readyState;
        if (readyState === 2) {
          console.error('[StagingSession] Connection closed by server or failed to connect');
        } else if (readyState === 0) {
          console.error('[StagingSession] Still attempting to connect...');
        }

        this.isConnected.set(false);
        this.streamStatus.set(this.isComplete() ? 'idle' : 'polling');
      });
    };

    // Listen for HEARTBEAT events
    this.eventSource.addEventListener('HEARTBEAT', () => {
      // Heartbeat intentionally ignored to avoid high-frequency UI churn/log spam.
    });

    this.eventSource.addEventListener('STATS', (event: MessageEvent) => {
      this.zone.run(() => {
        try {
          const stats = JSON.parse(event.data);
          const current = this.currentSession();
          if (!current) {
            return;
          }
          this.currentSession.set({
            ...current,
            totalFiles: stats.total ?? current.totalFiles,
            filesStaged: stats.ready ?? current.filesStaged,
            // backend sends enqueued as "processing" — accept both field names
            filesEnqueued: stats.enqueued ?? stats.processing ?? current.filesEnqueued,
            filesDone: stats.completed ?? current.filesDone,
            filesFailed: stats.failed ?? current.filesFailed,
            progress: stats.progress ?? current.progress,
            integration: stats.integration ?? current.integration,
          });
        } catch {}
      });
    });

    // FILE_UPDATE: Single file status change — apply directly to file list
    this.eventSource.addEventListener('FILE_UPDATE', (event: MessageEvent) => {
      this.zone.run(() => {
        try {
          const fileUpdate = JSON.parse(event.data);
          this.updateFileInList(fileUpdate);
          this.pushActivity(
            'file',
            fileUpdate.msg || fileUpdate.message || `File ${fileUpdate.displayStatus || fileUpdate.status}`,
            'description',
            'primary',
          );
        } catch {}
      });
    });

    // FILE_UPDATES: Batched file status changes — apply directly to file list
    this.eventSource.addEventListener('FILE_UPDATES', (event: MessageEvent) => {
      this.zone.run(() => {
        try {
          const updates = JSON.parse(event.data);
          if (Array.isArray(updates)) {
            const appliedUpdates = this.updateFilesInListBatch(updates);
            if (appliedUpdates > 0) {
              this.pushActivity(
                'file',
                `${appliedUpdates} file${appliedUpdates === 1 ? '' : 's'} updated`,
                'description',
                'primary',
              );
            }
          }
        } catch {}
      });
    });

    // LOT_UPDATE: Lot-level progress change
    this.eventSource.addEventListener('LOT_UPDATE', (event: MessageEvent) => {
      this.zone.run(() => {
        try {
          const lotUpdate = JSON.parse(event.data);
          this.updateLotProgress(lotUpdate);
          const isLotDone = lotUpdate.progress >= 100;
          this.pushActivity(
            'lot',
            `Lot ${lotUpdate.lot}: ${lotUpdate.progress.toFixed(1)}% complete`,
            isLotDone ? 'check_circle' : 'layers',
            isLotDone ? 'success' : 'warning',
          );
        } catch {}
      });
    });

    // SESSION_STATUS: Session status transition
    this.eventSource.addEventListener('SESSION_STATUS', (event: MessageEvent) => {
      this.zone.run(() => {
        try {
          const statusUpdate = JSON.parse(event.data);
          const current = this.currentSession();
          if (current) {
            this.currentSession.set({ ...current, status: statusUpdate.status });
          }
          this.pushActivity('session', statusUpdate.message || `Session ${statusUpdate.status}`, 'info', 'success');
        } catch {}
      });
    });

    this.eventSource.addEventListener('ROW_UPDATE', (event: MessageEvent) => {
      this.zone.run(() => {
        try {
          const payload = JSON.parse(event.data);
          // Apply the status/CP fields directly to the file list immediately —
          // don't wait for the debounced backend refresh.
          if (payload?.id !== undefined && payload?.id !== null) {
            this.updateFileInList(payload);
          }
          // Schedule a debounced snapshot + files refresh to sync any other changes.
          this.scheduleRowUpdateRefresh(sessionId, payload?.msg);
        } catch {}
      });
    });

    this.eventSource.addEventListener('COMPLETE', () => {
      this.zone.run(() => {
        this.pushActivity('session', 'Session processing complete', 'check_circle', 'success');
        this.loadSnapshot(sessionId);
      });
    });
  }

  private schedulePostConnectRefresh(sessionId: string): void {
    this.postConnectRefreshHandles.forEach((handle) => clearTimeout(handle));
    this.postConnectRefreshHandles = [];

    const refreshDelays = [1200, 3500];
    refreshDelays.forEach((delayMs) => {
      const handle = setTimeout(() => {
        if (this.currentSessionId !== sessionId || this.isComplete()) {
          return;
        }

        this.debugLog(`[StagingSession] Post-connect refresh after ${delayMs}ms`);
        this.loadSnapshot(sessionId);
        this.loadFiles(sessionId, 0, this.monitorPageSize);
        this.hydrateAllSessionFiles(sessionId);
        this.loadLotProgress(sessionId);
      }, delayMs);

      this.postConnectRefreshHandles.push(handle);
    });
  }

  private updateFileInList(fileUpdate: any): void {
    const currentFiles = this.sessionFiles();
    const fileIndex = currentFiles.findIndex((f: any) => f.id === fileUpdate.id);

    if (fileIndex < 0) {
      return; // File not found, nothing to update
    }

    const updatedFiles = [...currentFiles];
    updatedFiles[fileIndex] = {
      ...updatedFiles[fileIndex],
      status: fileUpdate.status ?? updatedFiles[fileIndex].status,
      updated: fileUpdate.updatedAt ?? fileUpdate.updated ?? updatedFiles[fileIndex].updated,
      updatedAt: fileUpdate.updatedAt ?? fileUpdate.updated ?? updatedFiles[fileIndex].updatedAt,
      errorMessage: fileUpdate.errorMessage ?? fileUpdate.message ?? updatedFiles[fileIndex].errorMessage,
      cpOutputPath: fileUpdate.cpOutputPath ?? updatedFiles[fileIndex].cpOutputPath,
      cpOutputTarget: fileUpdate.cpOutputTarget ?? updatedFiles[fileIndex].cpOutputTarget,
      cpIntegrationStatus: fileUpdate.cpIntegrationStatus ?? updatedFiles[fileIndex].cpIntegrationStatus,
      cpIntegrationMessage: fileUpdate.cpIntegrationMessage ?? updatedFiles[fileIndex].cpIntegrationMessage,
      exensioIntegrationStatus: fileUpdate.exensioIntegrationStatus ?? updatedFiles[fileIndex].exensioIntegrationStatus,
      exensioIntegrationMessage:
        fileUpdate.exensioIntegrationMessage ?? updatedFiles[fileIndex].exensioIntegrationMessage,
    };

    this.sessionFiles.set(updatedFiles);

    // Push activity message for terminal files (COMPLETED or ERROR)
    const oldFile = currentFiles[fileIndex];
    const newFile = updatedFiles[fileIndex];
    if (newFile.status === 'COMPLETED' && oldFile.status !== 'COMPLETED') {
      this.buildAndPushTerminalActivityMessage(newFile);
    } else if (newFile.status === 'ERROR' && oldFile.status !== 'ERROR') {
      this.buildAndPushTerminalActivityMessage(newFile);
    }
  }

  private updateFilesInListBatch(updates: any[]): number {
    const currentFiles = this.sessionFiles();
    if (currentFiles.length === 0 || updates.length === 0) {
      return 0;
    }

    const updatesById = new Map<any, any>();
    updates.forEach((update: any) => {
      if (update?.id !== undefined && update?.id !== null) {
        updatesById.set(update.id, update);
      }
    });

    if (updatesById.size === 0) {
      return 0;
    }

    let appliedCount = 0;
    let hasChanges = false;

    // Track files that transitioned to terminal states for activity messages
    const terminalFilesToNotify: StageRecordView[] = [];

    const updatedFiles = currentFiles.map((file: StageRecordView) => {
      const update = updatesById.get(file.id);
      if (!update) {
        return file;
      }

      appliedCount += 1;
      hasChanges = true;

      const newFile = {
        ...file,
        status: update.status ?? file.status,
        updated: update.updatedAt ?? update.updated ?? file.updated,
        updatedAt: update.updatedAt ?? update.updated ?? file.updatedAt,
        errorMessage: update.errorMessage ?? update.message ?? file.errorMessage,
        cpOutputPath: update.cpOutputPath ?? file.cpOutputPath,
        cpOutputTarget: update.cpOutputTarget ?? file.cpOutputTarget,
        cpIntegrationStatus: update.cpIntegrationStatus ?? file.cpIntegrationStatus,
        cpIntegrationMessage: update.cpIntegrationMessage ?? file.cpIntegrationMessage,
        exensioIntegrationStatus: update.exensioIntegrationStatus ?? file.exensioIntegrationStatus,
        exensioIntegrationMessage: update.exensioIntegrationMessage ?? file.exensioIntegrationMessage,
      };

      // Check if file transitioned to COMPLETED or ERROR
      if (newFile.status === 'COMPLETED' && file.status !== 'COMPLETED') {
        terminalFilesToNotify.push(newFile);
      } else if (newFile.status === 'ERROR' && file.status !== 'ERROR') {
        terminalFilesToNotify.push(newFile);
      }

      return newFile;
    });

    if (hasChanges) {
      this.sessionFiles.set(updatedFiles);
    }

    // Push activity messages for all terminal files
    terminalFilesToNotify.forEach((file) => {
      this.buildAndPushTerminalActivityMessage(file);
    });

    return appliedCount;
  }

  private updateLotProgress(lotUpdate: any): void {
    const lots = this.lotProgress();
    const index = lots.findIndex((l: any) => l.lot === lotUpdate.lot);
    if (index >= 0) {
      const updated = [...lots];
      updated[index] = {
        ...updated[index],
        totalFiles: lotUpdate.totalWafers || lotUpdate.totalFiles,
        doneFiles: lotUpdate.completedWafers || lotUpdate.doneFiles,
        failedFiles: lotUpdate.failedWafers || lotUpdate.failedFiles,
      };
      this.lotProgress.set(updated);
    } else {
      // Add new lot if not found
      this.lotProgress.set([
        ...lots,
        {
          lot: lotUpdate.lot,
          wafer: '',
          totalFiles: lotUpdate.totalWafers || lotUpdate.totalFiles || 0,
          doneFiles: lotUpdate.completedWafers || lotUpdate.doneFiles || 0,
          failedFiles: lotUpdate.failedWafers || lotUpdate.failedFiles || 0,
          status: 'ENRICHMENT',
        },
      ]);
    }
  }

  /**
   * Builds and pushes an activity event for a file that has reached a terminal state (COMPLETED or ERROR).
   * The activity message includes a pipeline summary:
   * - For COMPLETED: "[filename] — Enrichment: Done · Exensio: Loaded · [target]"
   * - For ERROR: "[filename] — Failed: [error message truncated to 80 chars]"
   */
  private buildAndPushTerminalActivityMessage(file: StageRecordView): void {
    const filename = file.filename || 'unknown file';
    let message = '';

    if (file.status === 'COMPLETED') {
      // Build pipeline summary for completed file
      const enrichmentStatus = file.cpIntegrationStatus;
      const exensioStatus = file.exensioIntegrationStatus;
      const outputTarget = file.cpOutputTarget;

      let enrichmentPart = '';
      if (enrichmentStatus === 'not_configured' || enrichmentStatus == null) {
        enrichmentPart = '';
      } else if (enrichmentStatus === 'success') {
        enrichmentPart = 'Enrichment: Done';
      }

      let exensioPart = '';
      if (exensioStatus === 'not_configured' || exensioStatus == null) {
        exensioPart = '';
      } else if (exensioStatus === 'success') {
        exensioPart = 'Exensio: Loaded';
      }

      let targetPart = '';
      if (outputTarget === 'PRODUCTION') {
        targetPart = 'PRODUCTION';
      } else if (outputTarget === 'SANDBOX') {
        targetPart = 'SANDBOX';
      } else if (outputTarget === 'UNKNOWN') {
        targetPart = 'UNKNOWN';
      }

      // Build the message with proper formatting
      const parts: string[] = [];
      if (enrichmentPart) {
        parts.push(enrichmentPart);
      }
      if (exensioPart) {
        parts.push(exensioPart);
      }
      if (targetPart) {
        parts.push(targetPart);
      }

      if (parts.length === 0) {
        message = `${filename} — Completed`;
      } else {
        message = `${filename} — ${parts.join(' · ')}`;
      }
    } else if (file.status === 'ERROR') {
      // Get the error message and detect source, prioritizing errorMessage > cpIntegrationMessage > exensioIntegrationMessage
      let errorMessage = '';
      let source = '';

      if (file.errorMessage) {
        errorMessage = file.errorMessage;
        source = this.detectActivityErrorSource(file.errorMessage, file);
      } else if (file.cpIntegrationStatus === 'failure') {
        errorMessage = file.cpIntegrationMessage || '';
        source = 'CP';
      } else if (file.exensioIntegrationStatus === 'failure' || file.exensioIntegrationStatus === 'error') {
        errorMessage = file.exensioIntegrationMessage || '';
        source = 'Exensio';
      }

      // Build prefixed error label
      const prefix = source ? `${source} — ` : 'ERROR — ';
      const maxLen = 80;
      const available = maxLen - prefix.length;
      const truncatedError = errorMessage.length > available ? errorMessage.substring(0, available) + '...' : errorMessage;

      message = truncatedError ? `${filename} — Failed: ${prefix}${truncatedError}` : `${filename} — Failed`;
    }

    this.pushActivity(
      'file',
      message,
      file.status === 'COMPLETED' ? 'check_circle' : 'error',
      file.status === 'COMPLETED' ? 'success' : 'error',
    );
  }

  /**
   * Detect the error source from the error message content or integration status fields.
   * Returns "CP" for Elasticsearch/enrichment errors, "Exensio" for Exensio API errors, "" if unknown.
   */
  private detectActivityErrorSource(errorMessage: string, file: StageRecordView): string {
    const msg = errorMessage.toLowerCase();

    if (msg.startsWith('[cp ') || msg.includes('cp enrichment') || msg.includes('cp failure') ||
        msg.includes('cp timeout') || msg.includes('cp pp_log')) {
      return 'CP';
    }
    if (msg.startsWith('[exensio ') || msg.includes('exensio load') || msg.includes('exensio failure') ||
        msg.includes('exensio api') || msg.includes('dead letter queue')) {
      return 'Exensio';
    }

    if (file.cpIntegrationStatus === 'failure' || file.cpIntegrationStatus === 'timeout' ||
        file.cpIntegrationStatus === 'error') {
      return 'CP';
    }
    if (file.exensioIntegrationStatus === 'failure' || file.exensioIntegrationStatus === 'error') {
      return 'Exensio';
    }

    return '';
  }

  private pushActivity(
    type: ActivityEvent['type'],
    message: string,
    icon: string,
    color: ActivityEvent['color'],
  ): void {
    const next: SessionActivityEvent = {
      id: `${Date.now()}-${Math.random()}`,
      timestamp: new Date(),
      type,
      message,
      icon,
      color,
    };
    this.activities.set([next, ...this.activities()].slice(0, 100));
  }

  private subscribeToTokenChanges(): void {
    // Listen to token changes from AuthService
    this.tokenSubscription = this.authService.token$
      .pipe(
        skip(1), // Skip initial value
        filter((token: string | null) => token !== null), // Only react to new tokens (not logout)
        distinctUntilChanged(), // Only when token actually changes
      )
      .subscribe(() => {
        // If we have an active monitoring session, reconnect SSE with fresh token
        if (this.currentSessionId) {
          this.debugLog('[StagingSession] Token refreshed, reconnecting SSE...');
          this.streamStatus.set('connecting');
          this.connectSse(this.currentSessionId);
        }
      });
  }

  private tryReconnectSse(sessionId: string): void {
    if (this.eventSource || this.isComplete()) {
      return;
    }

    const now = Date.now();
    if (now < this.nextSseRetryAt) {
      return;
    }

    this.nextSseRetryAt = now + this.sseReconnectIntervalMs;
    this.streamStatus.set('connecting');
    this.connectSse(sessionId);
  }

  private needsBootstrapData(): boolean {
    const session = this.currentSession();
    if ((session?.totalFiles || 0) > 0) {
      return false;
    }
    if (this.sessionFiles().length > 0) {
      return false;
    }
    return true;
  }

  private scheduleRowUpdateRefresh(sessionId: string, message?: string): void {
    if (this.rowUpdateRefreshHandle) {
      return;
    }

    this.rowUpdateRefreshHandle = setTimeout(() => {
      this.rowUpdateRefreshHandle = undefined;
      if (this.currentSessionId !== sessionId) {
        return;
      }

      if (message) {
        this.pushActivity('file', message, 'description', 'primary');
      }

      this.loadSnapshot(sessionId);
      this.loadFiles(sessionId, 0, this.monitorPageSize);
      this.hydrateAllSessionFiles(sessionId);
      this.loadLotProgress(sessionId);
    }, 350);
  }

  private hydrateAllSessionFiles(sessionId: string): void {
    if (this.hydratedSessionId === sessionId || this.fullFilesHydrationInFlight) {
      return;
    }

    this.fullFilesHydrationInFlight = true;
    const pageSize = this.monitorPageSize;
    const maxRows = this.monitorMaxRows;
    const collected: StageRecordView[] = [];

    const fetchPage = (page: number, totalHint: number = 0): void => {
      this.backend.getStagingSessionFiles(sessionId, page, pageSize).subscribe({
        next: (filePage: any) => {
          const rawItems: any[] = Array.isArray(filePage)
            ? filePage
            : Array.isArray(filePage?.items)
              ? filePage.items
              : Array.isArray(filePage?.content)
                ? filePage.content
                : [];
          const items: StageRecordView[] = rawItems.map((item: any) => this.normalizeStageRecord(item));

          const total = Number(filePage?.total ?? totalHint ?? items.length ?? 0);
          collected.push(...items);

          const reachedLimit = collected.length >= maxRows;
          const reachedTotal = total > 0 && collected.length >= total;
          const reachedEnd = items.length < pageSize || items.length === 0;

          if (reachedLimit || reachedTotal || reachedEnd) {
            if (total <= 0 && collected.length === 0) {
              // Session may still be warming up; allow a later retry.
              this.fullFilesHydrationInFlight = false;
              return;
            }
            this.sessionFiles.set(collected.slice(0, maxRows));
            this.hydratedSessionId = sessionId;
            this.fullFilesHydrationInFlight = false;
            return;
          }

          fetchPage(page + 1, total);
        },
        error: (err: unknown) => {
          console.error('[StagingSession] Failed to hydrate full file list:', err);
          this.toast.error('Failed to load complete file list. Partial data may be available.', 7000);
          this.fullFilesHydrationInFlight = false;
        },
      });
    };

    fetchPage(0);
  }

  private normalizeStageRecord(raw: any): StageRecordView {
    const metadataId = this.valueOrNull(raw?.metadataId ?? raw?.metadata_id);
    const dataId = this.valueOrNull(raw?.dataId ?? raw?.data_id);

    const lot = this.valueOrNull(raw?.lot);
    const wafer = this.valueOrNull(raw?.wafer);

    const filename = this.valueOrFallback(
      raw?.filename ?? raw?.fileName ?? raw?.originalFileName ?? raw?.originalFilename ?? dataId ?? metadataId,
      'unknown',
    );

    const updatedAt = this.valueOrNull(raw?.updatedAt ?? raw?.updated_at ?? raw?.updated);

    return {
      ...raw,
      metadataId,
      dataId,
      lot,
      wafer,
      filename,
      updatedAt,
      updated: updatedAt,
      status: this.valueOrFallback(raw?.status, 'NEW'),
    } as StageRecordView;
  }

  private valueOrFallback(value: any, fallback: string): string {
    const normalized = this.valueOrNull(value);
    return normalized ?? fallback;
  }

  private valueOrNull(value: any): string | null {
    if (value === null || value === undefined) {
      return null;
    }
    const text = String(value).trim();
    return text.length > 0 ? text : null;
  }
}
