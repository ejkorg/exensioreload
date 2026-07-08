import { computed, effect, inject, Injectable, OnDestroy, signal } from '@angular/core';
import { StageRecordView } from '../../api/backend.service';
import { SessionStreamStatus, StagingSessionService } from './staging-session.service';

export interface PaginatedMonitoringState {
  totalCount: number;
  currentPage: number;
  pageSize: number;
  items: MonitoringFileItem[];
  isLoading: boolean;
  hasMore: boolean;
  streamStatus: SessionStreamStatus;
}

export interface MonitoringFileItem {
  id: number | string;
  metadataId?: string | null;
  dataId?: string | null;
  filename: string;
  lot: string;
  wafer?: string;
  status: 'READY' | 'QUEUED_FOR_CP' | 'ELASTICSEARCH_MONITORING' | 'CP_TIMEOUT' | 'EXENSIO_MONITORING' | 'COMPLETED_MANUAL_VERIFICATION_REQUIRED' | 'COMPLETED' | 'ERROR' | 'CP_FAILED' | 'LOAD_FAILED' | 'CANCELLED';
  message: string;
  errorMessage?: string | null;
  updatedAt?: string;
  cpOutputPath?: string | null;
  cpOutputTarget?: string | null;
  // Per-file integration status
  cpIntegrationStatus?: string | null;
  cpIntegrationMessage?: string | null;
  exensioIntegrationStatus?: string | null;
  exensioIntegrationMessage?: string | null;
  /** Tracks if this file was updated in the current real-time update cycle (for UI indicators) */
  isRecentlyUpdated?: boolean;
}

/**
 * MonitoringPaginationService manages paginated file monitoring for large datasets.
 *
 * Features:
 * - Lazy-loads files as user scrolls (pagination)
 * - Real-time per-file status updates via SSE (updates any file, any page)
 * - Maintains update cache for off-page files (applies updates lazily when user navigates)
 * - Smart "recently updated" UI indicator for newly changed files
 * - Configurable page size and cache strategy
 *
 * Strategy for handling 10k+ files:
 * 1. Load first page (100 files) immediately
 * 2. As virtual scroll viewport shows new rows, request next page
 * 3. SSE updates apply in real-time to current page; off-page updates cached
 * 4. When user navigates pages, cached updates are merged
 * 5. "Recently updated" badge shows for ~2 seconds after update received
 */
@Injectable({
  providedIn: 'root',
})
export class MonitoringPaginationService implements OnDestroy {
  private stagingSession = inject(StagingSessionService);
  private activeSessionId: string | null = null;

  /** Pagination config */
  private readonly pageSize = 100;
  private readonly recentlyUpdatedDurationMs = 2000;

  /** Signals: Pagination state */
  currentPage = signal(0);
  totalCount = signal(0);
  items = signal<MonitoringFileItem[]>([]);
  isLoading = signal(false);

  /** Signals: Real-time update tracking */
  streamStatus = computed(() => this.stagingSession.streamStatus());

  /** Computed: Derived state */
  hasMore = computed(() => {
    const total = this.totalCount();
    const loaded = (this.currentPage() + 1) * this.pageSize;
    return loaded < total;
  });

  paginationState = computed<PaginatedMonitoringState>(() => ({
    totalCount: this.totalCount(),
    currentPage: this.currentPage(),
    pageSize: this.pageSize,
    items: this.items(),
    isLoading: this.isLoading(),
    hasMore: this.hasMore(),
    streamStatus: this.streamStatus(),
  }));

  /** Internal: Track pending updates for files not yet loaded/visible */
  private pendingUpdates = new Map<number | string, MonitoringFileItem>();
  private recentlyUpdatedIds = new Set<number | string>();
  private recentlyUpdatedCleanupHandles = new Map<number | string, ReturnType<typeof setTimeout>>();

  constructor() {
    this.setupSessionBoundaryTracking();
    this.setupFileUpdates();
    this.setupTotalCountTracking();
  }

  ngOnDestroy(): void {
    this.recentlyUpdatedCleanupHandles.forEach((handle) => clearTimeout(handle));
  }

  /**
   * Load a specific page of files. Called when user reaches end of current page.
   * In production, this would paginate from backend; for now uses in-memory sessionFiles.
   */
  async loadPage(page: number): Promise<void> {
    if (page < 0 || this.isLoading()) {
      return;
    }

    this.isLoading.set(true);
    try {
      const allFiles = this.stagingSession.sessionFiles();
      const start = page * this.pageSize;
      const end = start + this.pageSize;

      // Simulate network latency (in production, this is actual API call)
      await new Promise((resolve) => setTimeout(resolve, 100));

      const pageFiles = allFiles.slice(start, end).map((file: StageRecordView) => this.mapToMonitoringFile(file));

      // Merge with pending updates for files on this page
      const mergedFiles = pageFiles.map((file: MonitoringFileItem) => {
        const pending = this.pendingUpdates.get(file.id);
        return pending ? { ...file, ...pending } : file;
      });

      this.items.set(mergedFiles);
      this.currentPage.set(page);

      // Clean up applied pending updates (they're now on the visible page)
      mergedFiles.forEach((file: MonitoringFileItem) => {
        this.pendingUpdates.delete(file.id);
      });
    } finally {
      this.isLoading.set(false);
    }
  }

  /**
   * Load first page. Called on session start.
   */
  loadFirstPage(): Promise<void> {
    return this.loadPage(0);
  }

  /**
   * Load next page. Called when user scrolls near bottom.
   */
  async loadNextPage(): Promise<void> {
    if (this.hasMore()) {
      await this.loadPage(this.currentPage() + 1);
    }
  }

  /**
   * Apply a real-time file update from SSE.
   * If file is on current page, update in-place.
   * If file is off-page, cache for later.
   */
  applyRealTimeUpdate(file: StageRecordView): void {
    const updatedFile = this.mapToMonitoringFile(file);

    // Check if file is on current page
    const currentItems = this.items();
    const index = currentItems.findIndex((f: MonitoringFileItem) => f.id === updatedFile.id);

    if (index >= 0) {
      // On-page: Update immediately with visual indicator
      const newItems = [...currentItems];
      newItems[index] = { ...updatedFile, isRecentlyUpdated: true };
      this.items.set(newItems);
      this.markRecentlyUpdated(updatedFile.id);
    } else {
      // Off-page: Cache for later
      this.pendingUpdates.set(updatedFile.id, updatedFile);
    }
  }

  /**
   * Reload current page without losing scroll position.
   * Useful for manual refresh or when navigating back to monitor view.
   */
  async refreshCurrentPage(): Promise<void> {
    await this.loadPage(this.currentPage());
  }

  /**
   * Reset pagination state and reload from beginning.
   */
  async reset(): Promise<void> {
    this.currentPage.set(0);
    this.totalCount.set(0);
    this.items.set([]);
    this.pendingUpdates.clear();
    this.recentlyUpdatedIds.clear();
    this.recentlyUpdatedCleanupHandles.forEach((h) => clearTimeout(h));
    this.recentlyUpdatedCleanupHandles.clear();

    await this.loadFirstPage();
  }

  /**
   * Check if a file ID has a "recently updated" badge.
   */
  isRecentlyUpdated(fileId: number | string): boolean {
    return this.recentlyUpdatedIds.has(fileId);
  }

  /**
   * Private: Track total file count from session; auto-load first page on change.
   */
  private setupTotalCountTracking(): void {
    effect(() => {
      const session = this.stagingSession.currentSession();
      const sessionTotal = session?.totalFiles || 0;
      const filesTotal = this.stagingSession.sessionFiles().length;
      const newTotal = Math.max(sessionTotal, filesTotal);
      const currentTotal = this.totalCount();

      if (newTotal !== currentTotal) {
        this.totalCount.set(newTotal);

        // Auto-load first page when total changes
        if (newTotal > 0 && this.items().length === 0) {
          this.loadFirstPage();
        }
      }
    });
  }

  /**
   * Private: React to file updates from staging session.
   * Called when SSE broadcasts a file change.
   */
  private setupFileUpdates(): void {
    effect(() => {
      // Watch for changes to sessionFiles and apply updates intelligently
      const allFiles = this.stagingSession.sessionFiles();
      const currentItems = this.items();

      if (allFiles.length === 0) {
        return;
      }

      // If current page is empty but files are available, hydrate immediately.
      if (currentItems.length === 0) {
        const start = this.currentPage() * this.pageSize;
        const end = start + this.pageSize;
        const pageItems = allFiles.slice(start, end).map((file: StageRecordView) => this.mapToMonitoringFile(file));
        if (pageItems.length > 0) {
          this.items.set(pageItems);
        }
        return;
      }

      // Check if any files on current page have been updated
      const updatedCurrentItems = currentItems.map((currentFile: MonitoringFileItem): MonitoringFileItem => {
        const updatedSource = allFiles.find((f: StageRecordView) => f.id === currentFile.id);
        if (updatedSource && updatedSource.updated !== currentFile.updatedAt) {
          // File was updated, apply changes
          return {
            ...currentFile,
            status: this.mapBackendStatus(updatedSource.status),
            updatedAt: updatedSource.updated,
            errorMessage: updatedSource.errorMessage,
            isRecentlyUpdated: true,
          };
        }
        return currentFile;
      });

      // Check if any items changed
      const hasChanges = updatedCurrentItems.some((item: MonitoringFileItem, idx: number) => {
        const original = currentItems[idx];
        return (
          item.status !== original.status ||
          item.updatedAt !== original.updatedAt ||
          (item.isRecentlyUpdated && !original.isRecentlyUpdated)
        );
      });

      if (hasChanges) {
        this.items.set(updatedCurrentItems);
        updatedCurrentItems.forEach((item: MonitoringFileItem) => {
          if (item.isRecentlyUpdated) {
            this.markRecentlyUpdated(item.id);
          }
        });
      }
    });
  }

  private setupSessionBoundaryTracking(): void {
    effect(() => {
      const sessionId = this.stagingSession.currentSession()?.sessionId || null;
      if (sessionId === this.activeSessionId) {
        return;
      }

      this.activeSessionId = sessionId;
      this.currentPage.set(0);
      this.totalCount.set(0);
      this.items.set([]);
      this.pendingUpdates.clear();
      this.recentlyUpdatedIds.clear();
      this.recentlyUpdatedCleanupHandles.forEach((h) => clearTimeout(h));
      this.recentlyUpdatedCleanupHandles.clear();

      if (sessionId) {
        void this.loadFirstPage();
      }
    });
  }

  /**
   * Private: Mark a file as "recently updated" for UI indicator, auto-clear after 2s.
   */
  private markRecentlyUpdated(fileId: number | string): void {
    this.recentlyUpdatedIds.add(fileId);

    // Clear any existing cleanup handle to avoid double-clear
    const existingHandle = this.recentlyUpdatedCleanupHandles.get(fileId);
    if (existingHandle) {
      clearTimeout(existingHandle);
    }

    // Schedule cleanup
    const handle = setTimeout(() => {
      this.recentlyUpdatedIds.delete(fileId);
      this.recentlyUpdatedCleanupHandles.delete(fileId);

      // Update items to remove isRecentlyUpdated flag
      const currentItems = this.items();
      const index = currentItems.findIndex((f: MonitoringFileItem) => f.id === fileId);
      if (index >= 0) {
        const updated = [...currentItems];
        updated[index] = { ...updated[index], isRecentlyUpdated: false };
        this.items.set(updated);
      }
    }, this.recentlyUpdatedDurationMs);

    this.recentlyUpdatedCleanupHandles.set(fileId, handle);
  }

  /**
   * Private: Map backend StageRecordView to MonitoringFileItem.
   */
  private mapToMonitoringFile(file: StageRecordView): MonitoringFileItem {
    return {
      id: file.id || 0,
      metadataId: file.metadataId,
      dataId: file.dataId,
      filename: file.filename || '',
      lot: file.lot || '',
      wafer: file.wafer,
      status: this.mapBackendStatus(file.status),
      message: file.status || '',
      errorMessage: file.errorMessage,
      updatedAt: file.updated,
      cpOutputPath: file.cpOutputPath,
      cpOutputTarget: file.cpOutputTarget,
      // Per-file integration status
      cpIntegrationStatus: file.cpIntegrationStatus,
      cpIntegrationMessage: file.cpIntegrationMessage,
      exensioIntegrationStatus: file.exensioIntegrationStatus,
      exensioIntegrationMessage: file.exensioIntegrationMessage,
      isRecentlyUpdated: false,
    };
  }

  /**
   * Private: Map backend status string to MonitoringFileItem status enum.
   */
  private mapBackendStatus(
    status: string,
  ): 'READY' | 'QUEUED_FOR_CP' | 'ELASTICSEARCH_MONITORING' | 'CP_TIMEOUT' | 'EXENSIO_MONITORING' | 'COMPLETED_MANUAL_VERIFICATION_REQUIRED' | 'COMPLETED' | 'ERROR' | 'CP_FAILED' | 'LOAD_FAILED' | 'CANCELLED' {
    const normalized = (status || '').toUpperCase();
    if (normalized === 'COMPLETED' || normalized === 'DONE') return 'COMPLETED';
    if (normalized === 'ELASTICSEARCH_MONITORING' || normalized === 'DISPATCHING') return 'ELASTICSEARCH_MONITORING';
    if (normalized === 'CP_TIMEOUT') return 'CP_TIMEOUT';
    if (normalized === 'EXENSIO_MONITORING') return 'EXENSIO_MONITORING';
    if (normalized === 'COMPLETED_MANUAL_VERIFICATION_REQUIRED') return 'COMPLETED_MANUAL_VERIFICATION_REQUIRED';
    if (normalized === 'PROCESSING') return 'ELASTICSEARCH_MONITORING'; // legacy compat
    if (normalized === 'CP_FAILED' || normalized === 'FAILED') return 'CP_FAILED';
    if (normalized === 'LOAD_FAILED') return 'LOAD_FAILED';
    if (normalized === 'ERROR') return 'ERROR';
    if (normalized === 'CANCELLED') return 'CANCELLED';
    if (normalized === 'QUEUED_FOR_CP' || normalized === 'QUEUED' || normalized === 'ENQUEUED') return 'QUEUED_FOR_CP';
    if (normalized === 'STAGED' || normalized === 'PENDING' || normalized === 'READY') return 'READY';
    return 'READY';
  }
}
