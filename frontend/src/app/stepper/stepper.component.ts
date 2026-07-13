import { CommonModule } from '@angular/common';
import { Component, OnDestroy, OnInit, computed, effect, signal } from '@angular/core';
import { FormControl, FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { Subject, Subscription, firstValueFrom, of } from 'rxjs';
import { catchError, debounceTime, distinctUntilChanged, map, switchMap } from 'rxjs/operators';
import {
  BackendService,
  CreateSessionResponse,
  DiscoveryPreviewRequest,
  DiscoveryPreviewRow,
  ReloadFilterOptions,
  SenderOption,
  StagePayloadRequestBody,
  StageRecordView,
} from '../api/backend.service';
import { AuthService } from '../auth/auth.service';
import { ActivityFeedComponent } from '../shared/components/activity-feed.component';
import { GlassButtonComponent } from '../shared/components/glass-button.component';
import { GlassCheckboxComponent } from '../shared/components/glass-checkbox.component';
import { DateRange, GlassDateRangeComponent } from '../shared/components/glass-date-range.component';
import { GlassIconComponent } from '../shared/components/glass-icon.component';
import { GlassInputComponent } from '../shared/components/glass-input.component';
import { GlassLoadingOverlayComponent } from '../shared/components/glass-loading-overlay.component';
import { GlassPaginationComponent, PaginationEvent } from '../shared/components/glass-pagination.component';
import { GlassOption, GlassSelectComponent } from '../shared/components/glass-select.component';
import { GlassSenderSelectorComponent } from '../shared/components/glass-sender-selector.component';
import { GlassStep, GlassStepperComponent } from '../shared/components/glass-stepper.component';
import { LotWaferProgressComponent } from '../shared/components/lot-wafer-progress.component';
import { MonitoringFileListComponent } from '../shared/components/monitoring-file-list.component';
import { MonitoringStatsComponent } from '../shared/components/monitoring-stats.component';
import { GlassTooltipDirective } from '../shared/directives/glass-tooltip.directive';
import { SiteNamePipe, formatSiteName } from '../shared/pipes/site-name.pipe';
import { GlassDialogService } from '../shared/services/glass-dialog.service';
import { MonitoringFile, MonitoringService } from '../shared/services/monitoring.service';
import {
  SessionActivityEvent,
  SessionStreamStatus,
  StagingSessionService,
} from '../shared/services/staging-session.service';
import { ToastService } from '../shared/services/toast.service';
import {
  BulkLotInputDialogComponent,
  BulkLotInputDialogData,
  BulkLotInputDialogResult,
} from './bulk-lot-input-dialog.component';
import { ConfirmStageAllDialogComponent, ConfirmStageAllDialogData } from './confirm-stage-all-dialog.component';
import {
  DuplicatePayloadInfo,
  DuplicateWarningDialogComponent,
  DuplicateWarningDialogData,
} from './duplicate-warning-dialog.component';
import {
  LotVerificationDialogComponent,
  LotVerificationDialogData,
  LotVerificationDialogResult,
} from './lot-verification-dialog.component';

interface WaferMonitoringRow {
  lot: string;
  wafer: string;
  filename: string;
  status: MonitoringFile['status'];
}

interface DiscoveryFiltersSnapshot {
  site: string;
  environment: string;
  startDate?: string;
  endDate?: string;
  lots: any;
  wafers: any;
  pairs: any;
  testerType?: string;
  dataType?: string;
  dataTypeExt?: string;
  testPhase?: string;
  location?: string;
  historicalMode: boolean;
  devices?: string[];
}

interface DuplicateStageContext {
  sessionId: string;
  site: string;
  senderId: number;
  stagedCount: number;
  duplicateCount: number;
  totalAvailable?: number;
  mode: 'selected' | 'all';
  filters?: DiscoveryFiltersSnapshot;
}

@Component({
  selector: 'app-stepper',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterModule,
    GlassInputComponent,
    GlassSelectComponent,
    GlassDateRangeComponent,
    GlassPaginationComponent,
    GlassStepperComponent,
    GlassCheckboxComponent,
    GlassButtonComponent,
    GlassIconComponent,
    GlassTooltipDirective,
    MonitoringStatsComponent,
    MonitoringFileListComponent,
    LotWaferProgressComponent,
    ActivityFeedComponent,
    GlassSenderSelectorComponent,
    GlassLoadingOverlayComponent,
    SiteNamePipe,
  ],
  templateUrl: './stepper.component.html',
  styleUrls: ['./stepper.component.scss'],
})
export class StepperComponent implements OnInit, OnDestroy {
  private readonly monitoringResumeStorageKey = 'exensioreload.activeMonitoringSessionId';
  private readonly ftAllowedTestPhases = new Set(['FT', 'QA', 'RG', 'CRSS']);
  private stagedPreviewRows = signal<DiscoveryPreviewRow[]>([]);

  private getPreviewFilename(row: any): string {
    const value =
      row?.filename ?? row?.originalFileName ?? row?.originalFilename ?? row?.original_file_name ?? row?.fileName ?? '';
    return String(value || '').trim();
  }

  private normalizePreviewWafer(wafer: string | null | undefined): string {
    const trimmed = String(wafer ?? '').trim();
    if (!trimmed || trimmed === '-') {
      return '';
    }
    return trimmed;
  }

  private getPreviewDisplayKey(row: DiscoveryPreviewRow): string {
    const lot = String(row.lot || '').trim();
    const wafer = this.normalizePreviewWafer(row.wafer);
    const normalizedType = String(this.selectedDataType() || '')
      .trim()
      .toUpperCase();
    if (normalizedType === 'PCM') {
      return `${lot}::${wafer}`;
    }
    const filename = this.getPreviewFilename(row);
    return `${lot}::${wafer}::${filename}`;
  }

  private isNewerPreviewRow(candidate: DiscoveryPreviewRow, existing: DiscoveryPreviewRow): boolean {
    const candidateEnd = candidate.endTime ? String(candidate.endTime) : '';
    const existingEnd = existing.endTime ? String(existing.endTime) : '';
    if (candidateEnd && existingEnd) {
      const cmp = candidateEnd.localeCompare(existingEnd);
      if (cmp !== 0) {
        return cmp > 0;
      }
    } else if (candidateEnd) {
      return true;
    } else if (existingEnd) {
      return false;
    }

    const candidateId = String(candidate.metadataId || '');
    const existingId = String(existing.metadataId || '');
    return candidateId.localeCompare(existingId) > 0;
  }

  private deduplicatePreviewRows(rows: DiscoveryPreviewRow[]): DiscoveryPreviewRow[] {
    if (!rows || rows.length <= 1) {
      return rows || [];
    }

    const best = new Map<string, DiscoveryPreviewRow>();
    for (const row of rows) {
      const key = this.getPreviewDisplayKey(row);
      const existing = best.get(key);
      if (!existing || this.isNewerPreviewRow(row, existing)) {
        best.set(key, row);
      }
    }

    return best.size === rows.length ? rows : Array.from(best.values());
  }

  private monitoringStopped = signal(false);
  showActivityFeed = signal(true);

  monitorUiState = computed<'no-session' | 'connecting' | 'waiting' | 'live' | 'polling' | 'completed' | 'stopped'>(
    () => {
      if (this.monitoringStopped()) {
        return 'stopped';
      }
      if (!this.requestId()) {
        return 'no-session';
      }
      const sessionStatus = (this.stagingSession.currentSession()?.status || '').toUpperCase();
      if (sessionStatus === 'COMPLETED' || sessionStatus === 'PARTIALLY_FAILED' || sessionStatus === 'CANCELLED') {
        return 'completed';
      }
      const streamStatus: SessionStreamStatus = this.stagingSession.streamStatus();
      if (streamStatus === 'live') {
        return 'live';
      }
      if (streamStatus === 'polling') {
        return 'polling';
      }
      if (this.hasMonitoringData()) {
        return 'live';
      }
      return 'connecting';
    },
  );

  // Session summary computed (always returns object with safe defaults to avoid template nulls)
  sessionSummary = computed(() => {
    const session = this.stagingSession.currentSession();
    const total = session?.totalFiles ?? 0;
    const staged = session?.filesStaged ?? 0;
    const enqueued = session?.filesEnqueued ?? 0;
    const completed = session?.filesDone ?? 0;
    const failed = session?.filesFailed ?? 0;
    const senderId = session?.senderId ?? this.selectedSenderId() ?? null;
    const senderName = session?.senderName ?? this.getSelectedSenderName() ?? null;
    const startedAt = (session as any)?.createdAt ?? null;
    const finishedAt = (session as any)?.completedAt ?? null;
    const username = session?.username ?? null;
    return {
      total,
      staged,
      enqueued,
      completed,
      failed,
      status: session?.status ?? null,
      startedAt,
      finishedAt,
      senderId,
      senderName,
      sender: senderName,
      site: session?.site ?? null,
      environment: session?.environment ?? null,
      username,
    };
  });

  monitorTargetSender = computed(() => {
    const summary = this.sessionSummary();
    return {
      id: summary.senderId,
      name: summary.senderName,
    };
  });

  currentStep = signal(0);

  // Stepper configuration
  steps: GlassStep[] = [
    { label: 'Configuration', completed: false, editable: true },
    { label: 'Discovery', completed: false, editable: false },
    { label: 'Monitor', completed: false, editable: false },
  ];

  // Selection State
  selectedEnv = signal<'PROD' | 'QA' | null>(null);

  // Role-based environment options: driven by user$ subscription to guarantee reactivity
  // computed() on currentUser signal had a timing issue — subscription is explicit and reliable
  readonly envOptions = signal<('PROD' | 'QA')[]>(['PROD']);

  private updateEnvOptions(roles: string[]): void {
    const isAdmin = roles.includes('ADMIN') || roles.includes('SUPER_ADMIN');
    const hasUserOnly = roles.includes('USER') && !isAdmin;
    console.log(
      '[StepperComponent.updateEnvOptions] roles:',
      JSON.stringify(roles),
      'isAdmin:',
      isAdmin,
      'hasUserOnly:',
      hasUserOnly,
    );
    if (hasUserOnly || (!isAdmin && roles.length > 0)) {
      this.envOptions.set(['PROD']);
    } else if (isAdmin) {
      this.envOptions.set(['PROD', 'QA']);
    } else {
      // roles empty (not yet loaded) — default to PROD only (safe default)
      this.envOptions.set(['PROD']);
    }
  }

  selectedSite = signal<string | null>(null);
  historicalMode = signal(false);

  // Helper to create unique row key (metadataId + dataId for uniqueness)
  private getRowKey(row: DiscoveryPreviewRow): string {
    // IMPORTANT: metadataId/dataId can repeat across multiple wafers (PCM).
    // Include lot/wafer/filename so selection and staging can target every row.
    return [
      row.metadataId || 'null',
      row.dataId || 'null',
      row.lot || '',
      row.wafer || '',
      this.getPreviewFilename(row),
    ].join('::');
  }

  private getRowKeyFromIds(metadataId: string, dataId?: string | null): string {
    return `${metadataId}:${dataId || 'null'}`;
  }

  private getRowFamilyId(row: DiscoveryPreviewRow): string {
    const lot = String(row.lot || '').trim();
    const filename = String(this.getPreviewFilename(row) || '').trim();
    if (lot && filename) {
      return `${lot}::${filename}`;
    }
    return this.getRowKey(row);
  }

  private getRowFamilyKeys(row: DiscoveryPreviewRow): Set<string> {
    const lot = String(row.lot || '').trim();
    const filename = String(this.getPreviewFilename(row) || '').trim();
    if (!lot || !filename) {
      return new Set([this.getRowKey(row)]);
    }

    const familyKeys = this.previewRows()
      .filter(
        (r: DiscoveryPreviewRow) =>
          String(r.lot || '').trim() === lot && String(this.getPreviewFilename(r) || '').trim() === filename,
      )
      .map((r: DiscoveryPreviewRow) => this.getRowKey(r));

    return new Set(familyKeys.length > 0 ? familyKeys : [this.getRowKey(row)]);
  }

  private setsEqual<T>(a: Set<T>, b: Set<T>): boolean {
    if (a.size !== b.size) {
      return false;
    }
    for (const value of a) {
      if (!b.has(value)) {
        return false;
      }
    }
    return true;
  }

  private normalizeTesterType(value: string | null | undefined): string | null {
    const trimmed = value == null ? '' : String(value).trim();
    if (!trimmed) {
      return null;
    }

    const token = trimmed.toUpperCase();
    if (
      token === 'N/A' ||
      token === 'NA' ||
      token === 'ANY' ||
      token === 'ALL' ||
      token === 'NONE' ||
      token === 'NULL'
    ) {
      return null;
    }

    return trimmed;
  }

  private normalizeTestPhases(phases: string[], dataType: string | null, testerType: string | null): string[] {
    const normalized = (phases || [])
      .map((phase) => (phase ?? '').toString().trim().toUpperCase())
      .filter((phase) => phase.length > 0);

    const unique = Array.from(new Set(normalized));
    const normalizedDataType = (dataType || '').trim().toUpperCase();
    const hasTesterType = !!this.normalizeTesterType(testerType);

    if (normalizedDataType === 'FT' && !hasTesterType) {
      return unique.filter((phase) => this.ftAllowedTestPhases.has(phase));
    }

    return unique;
  }

  // Loaded filter options for the selected site
  filterOptions = signal<ReloadFilterOptions | null>(null);
  locations = signal<string[]>([]);
  dataTypes = signal<string[]>([]);
  testerTypes = signal<string[]>([]);
  dataTypeExt = signal<string[]>([]);
  testPhases = signal<string[]>([]);

  // Loading states for each filter level
  locationsLoading = signal(false);
  dataTypesLoading = signal(false);
  testerTypesLoading = signal(false);
  dataTypeExtLoading = signal(false);
  testPhasesLoading = signal(false);

  selectedLocation = signal<string | null>(null);
  selectedDataType = signal<string | null>(null);
  selectedTesterType = signal<string | null>(null);
  selectedDataTypeExt = signal<string | null>(null);
  selectedTestPhase = signal<string | null>(null);

  // Date range for historical mode
  dateRange = signal<DateRange | null>(null);

  // Device filter (admin only) — exact value, glob wildcard (*), or comma/newline separated list
  deviceFilter = signal<string>('');
  deviceOptions = signal<string[]>([]);
  devicesLoading = signal(false);
  deviceFilterControl = new FormControl('');

  // Site / DTP instance options for the selected environment (external instances)
  siteOptions = signal<GlassOption[]>([]);

  // Sender management (auto-resolution support)
  senderOptions = signal<SenderOption[]>([]);
  selectedSenderId = signal<number | null>(null);
  senderAutoResolved = signal(false);
  senderLookupLoading = signal(false);
  senderFallback = signal(false);
  senderLookupQuery = signal<string | null>(null);

  lotWaferPairs = signal<Array<{ lot: string; wafer: string }>>([{ lot: '', wafer: '' }]);

  // Discovery Preview State
  previewLoading = signal(false);
  previewRows = signal<DiscoveryPreviewRow[]>([]);
  /** Full result set when all rows fit in a single fetch — used for client-side pagination */
  allPreviewRows = signal<DiscoveryPreviewRow[]>([]);

  /** True when at least one preview row has a non-null, non-empty wafer value */
  showWaferColumn = computed(() =>
    this.filteredPreviewRows().some(
      (r: DiscoveryPreviewRow) => r.wafer != null && r.wafer.trim() !== '' && r.wafer !== '-',
    ),
  );

  /**
   * PCM data type files contain ALL wafers for a lot in a single file.
   * When the user selects individual wafers (e.g. 1-4 out of 1-10), the
   * reality is the entire file gets reloaded — every wafer in that lot.
   * This computed signal detects that mismatch and drives a truth banner.
   */
  pcmFileFullReloadWarning = computed(() => {
    const dataType = (this.selectedDataType() || '').trim().toUpperCase();
    if (dataType !== 'PCM') return null;

    // Only relevant for staged-selected (not stage-all or no staging)
    if (!this.stagedPreviewRows() || this.stagedPreviewRows().length === 0) return null;

    const staged = this.stagedPreviewRows();
    const allRows = this.allPreviewRows().length > 0 ? this.allPreviewRows() : this.previewRows();

    let filesWithMoreWafers = 0;
    let totalExtraWafers = 0;
    const filesReloadingAll: Array<{ lot: string; filename: string; selected: number; total: number }> = [];

    // Group all rows by lot+filename to count total wafers per file
    const fileWaferCounts = new Map<string, number>();
    allRows.forEach((r: DiscoveryPreviewRow) => {
      const lot = String(r.lot || '').trim();
      const filename = String(this.getPreviewFilename(r) || '').trim();
      if (!lot || !filename) return;
      const key = `${lot}::${filename}`;
      fileWaferCounts.set(key, (fileWaferCounts.get(key) || 0) + 1);
    });

    // Count how many wafers the user selected per file
    const selectedPerFile = new Map<string, number>();
    staged.forEach((r: DiscoveryPreviewRow) => {
      const lot = String(r.lot || '').trim();
      const filename = String(this.getPreviewFilename(r) || '').trim();
      if (!lot || !filename) return;
      const key = `${lot}::${filename}`;
      selectedPerFile.set(key, (selectedPerFile.get(key) || 0) + 1);
    });

    // Compare selected vs total per file
    selectedPerFile.forEach((selected, key) => {
      const total = fileWaferCounts.get(key) || selected;
      if (total > selected) {
        const [lot, filename] = key.split('::');
        filesWithMoreWafers++;
        totalExtraWafers += total - selected;
        filesReloadingAll.push({ lot, filename, selected, total });
      }
    });

    if (filesWithMoreWafers === 0) return null;

    return {
      fileCount: filesWithMoreWafers,
      extraWafers: totalExtraWafers,
      files: filesReloadingAll,
    };
  });
  filterText = signal('');
  selectedFileType = signal('ALL');
  previewDeviceFilter = signal<string[]>([]);

  fileTypeOptions = computed((): GlassOption[] => {
    const types = new Set<string>();
    this.previewRows().forEach((row: DiscoveryPreviewRow & { originalFileName?: string | null }) => {
      const filename = row.filename ?? row.originalFileName ?? this.getPreviewFilename(row);
      const type = this.getFileType(filename);
      if (type) {
        types.add(type);
      }
    });

    const sortedTypes = Array.from(types).sort((a: string, b: string) => a.localeCompare(b));
    return [{ value: 'ALL', label: 'All Types' }, ...sortedTypes.map((type: string) => ({ value: type, label: type }))];
  });

  previewDeviceOptions = computed((): GlassOption[] => {
    const devices = new Set<string>();
    this.previewRows().forEach((row: DiscoveryPreviewRow) => {
      if (row.device && row.device.trim() !== '') {
        devices.add(row.device);
      }
    });

    const sortedDevices = Array.from(devices).sort((a: string, b: string) => a.localeCompare(b));
    return sortedDevices.map((device: string) => ({ value: device, label: device }));
  });

  // Staging state
  staging = signal(false);
  stageAllMode = signal(false);
  stageExecutionMode = signal<'selected' | 'all'>('selected');
  /** Set when all staged records already existed (re-queue scenario) — shown as info banner in monitoring */
  restagedCount = signal(0);
  /** Count of files skipped due to cross-user duplicate detection — shown as persistent banner in monitoring */
  skippedDuplicatesCount = signal(0);
  /** Set to true when ALL selected/discovered files were duplicates and user chose to skip — shows dedicated UI */
  allDuplicatesSkipped = signal(false);

  canStageAll = computed(() => {
    return this.previewTotal() > 0 && !!this.getEffectiveDiscoveryFilters() && !this.staging();
  });

  primaryStageDisabled = computed(() => {
    if (this.staging()) {
      return true;
    }
    if (this.stageExecutionMode() === 'all') {
      return !this.canStageAll();
    }
    return this.selectedRows().size === 0;
  });

  primaryStageLabel = computed(() => {
    if (this.stageExecutionMode() === 'all') {
      return `Stage All ${this.previewTotal().toLocaleString()} Files`;
    }
    const count = this.selectedRows().size;
    return `Stage ${count} Payload${count === 1 ? '' : 's'}`;
  });

  primaryStageTooltip = computed(() => {
    if (this.stageExecutionMode() === 'all') {
      if (!this.lastDiscoveryFilters()) {
        return 'Run discovery preview first to enable stage-all';
      }
      return `Stage all ${this.previewTotal()} matching files from this query`;
    }
    return this.stageSelectionTooltip();
  });

  // Store discovery query filters for "Stage All" re-execution
  // These are captured when loadPreview() runs successfully
  lastDiscoveryFilters = signal<DiscoveryFiltersSnapshot | null>(null);
  discoveryToken = signal<string | null>(null);

  // Verification summary shown after verification completes
  // Stores: choice ('all' | 'not-found'), totalLots, foundCount, notFoundCount
  // Initialize as null (no verification performed yet)
  // Set after user makes choice in dialog
  verificationSummary = signal<{
    choice: 'all' | 'not-found';
    totalLots: number;
    foundCount: number;
    notFoundCount: number;
  } | null>(null);

  // Pagination state
  pageSize = signal(25);
  pageIndex = signal(0);
  pageSizeOptions = signal([25, 50, 100]); // Start with 25 to match default pageSize

  filteredPreviewRows = computed(() => {
    const text = (this.filterText() || '').toString().toLowerCase();
    const selectedType = this.selectedFileType();
    const selectedDevices = this.previewDeviceFilter();

    const normalize = (value: unknown): string => {
      if (value === undefined || value === null) {
        return '';
      }
      return String(value).toLowerCase();
    };

    // Use the full loaded set when available, otherwise the current server page
    const source = this.allPreviewRows().length > 0 ? this.allPreviewRows() : this.previewRows();

    return source
      .map((row: DiscoveryPreviewRow & { originalFileName?: string | null }) => {
        const filename = row.filename ?? row.originalFileName ?? '';
        return { ...row, filename };
      })
      .filter((row: DiscoveryPreviewRow & { originalFileName?: string | null }) => {
        const lot = normalize(row.lot);
        const wafer = normalize(row.wafer);
        const filename = normalize(row.filename);
        const fileType = this.getFileType(row.filename);

        if (selectedType !== 'ALL' && fileType !== selectedType) {
          return false;
        }

        // Filter by device if selected
        if (selectedDevices.length > 0) {
          const rowDevice = row.device ? row.device.trim() : '';
          if (!rowDevice || !selectedDevices.includes(rowDevice)) {
            return false;
          }
        }

        if (!text) {
          return true;
        }
        return lot.includes(text) || wafer.includes(text) || filename.includes(text);
      });
  });

  // Paginated rows for display — sliced client-side when all rows are loaded
  paginatedPreviewRows = computed(() => {
    const filtered = this.filteredPreviewRows();
    // If we have all rows locally, slice the current page ourselves
    if (this.allPreviewRows().length > 0) {
      const start = this.pageIndex() * this.pageSize();
      return filtered.slice(start, start + this.pageSize());
    }
    // Otherwise the backend already returned the correct page
    return filtered;
  });

  previewTotal = signal(0);
  selectedRows = signal<Set<string>>(new Set());
  selectedRowLookup = signal<Map<string, DiscoveryPreviewRow>>(new Map());

  selectedFamilyCount = computed(() => {
    const selected = Array.from(this.selectedRowLookup().values()) as DiscoveryPreviewRow[];
    if (selected.length === 0) {
      return 0;
    }

    const families = new Set<string>();
    selected.forEach((row: DiscoveryPreviewRow) => {
      families.add(this.getRowFamilyId(row));
    });

    return families.size;
  });

  stageSelectionTooltip = computed(() => {
    const wafers = this.selectedRows().size;
    const families = this.selectedFamilyCount();

    if (wafers === 0) {
      return 'Select rows to stage';
    }

    const waferText = `${wafers} wafer${wafers === 1 ? '' : 's'}`;
    const familyText = `${families} file famil${families === 1 ? 'y' : 'ies'}`;
    return `${waferText} selected across ${familyText}`;
  });

  // Checkbox states for current page
  allCurrentPageSelected = computed(() => {
    const currentPage = this.paginatedPreviewRows();
    if (currentPage.length === 0) return false;
    return currentPage.every((r: DiscoveryPreviewRow) => this.selectedRows().has(this.getRowKey(r)));
  });

  someCurrentPageSelected = computed(() => {
    const currentPage = this.paginatedPreviewRows();
    return (
      currentPage.some((r: DiscoveryPreviewRow) => this.selectedRows().has(this.getRowKey(r))) &&
      !this.allCurrentPageSelected()
    );
  });

  isSiteEnabled = computed(() => !!this.selectedEnv());
  areFiltersEnabled = computed(() => !!this.selectedSite());
  senderPrerequisitesMet = computed(() => {
    return !!this.selectedSite() && !!this.selectedLocation() && !!this.selectedDataType();
  });

  hasMonitoringData = computed(() => {
    const session = this.stagingSession.currentSession();
    if ((session?.totalFiles || 0) > 0) {
      return true;
    }
    if (this.stagingSession.sessionFiles().length > 0) {
      return true;
    }
    return this.stagingSession.activities().length > 0;
  });

  // Map stagingSession data to MonitoringStats format
  monitoringStats = computed(() => {
    const session = this.stagingSession.currentSession();
    const waferRows = this.monitoringWaferRows();
    const sessionTotal = session?.totalFiles || 0;
    const filesFallbackTotal = this.stagingSession.sessionFiles().length;
    const waferFallbackTotal = waferRows.length;
    const total = Math.max(sessionTotal, filesFallbackTotal, waferFallbackTotal);

    const useWaferFallback = waferFallbackTotal > sessionTotal;

    const ready = useWaferFallback
      ? waferRows.filter((w: WaferMonitoringRow) => w.status === 'READY').length
      : session?.filesStaged || 0;

    const enqueued = useWaferFallback
      ? waferRows.filter((w: WaferMonitoringRow) => w.status === 'QUEUED_FOR_CP').length
      : session?.filesEnqueued || 0;

    const completed = useWaferFallback
      ? waferRows.filter((w: WaferMonitoringRow) => w.status === 'COMPLETED').length
      : session?.filesDone || 0;

    const failed = useWaferFallback
      ? waferRows.filter((w: WaferMonitoringRow) => w.status === 'ERROR').length
      : session?.filesFailed || 0;

    // Break down in-flight records from the file list when available
    const files = this.stagingSession.sessionFiles();
    const hasFileBreakdown = files.length > 0;
    const inQueueCount = hasFileBreakdown
      ? files.filter((f) => f.status === 'QUEUED_FOR_CP').length
      : useWaferFallback
        ? waferRows.filter((w: WaferMonitoringRow) => w.status === 'QUEUED_FOR_CP').length
        : session?.filesEnqueued || 0;
    const enrichmentCount = hasFileBreakdown ? files.filter((f) => f.status === 'ELASTICSEARCH_MONITORING').length : 0;
    const enrichmentTimeoutCount = hasFileBreakdown ? files.filter((f) => f.status === 'CP_TIMEOUT').length : 0;
    const exensioLoadingCount = hasFileBreakdown ? files.filter((f) => f.status === 'EXENSIO_MONITORING').length : 0;
    const exensioTimeoutCount = hasFileBreakdown ? files.filter((f) => f.status === 'COMPLETED_MANUAL_VERIFICATION_REQUIRED').length : 0;

    const processing = enrichmentCount + enrichmentTimeoutCount + exensioLoadingCount + exensioTimeoutCount;

    const processed = completed + failed;
    const progress = total > 0 ? Math.round((processed / total) * 100) : 0;
    const successRate = processed > 0 ? Math.round((completed / processed) * 100) : 0;

    // Calculate throughput and ETA
    const startTime = session?.createdAt ? new Date(session.createdAt) : null;
    const elapsedMs = startTime ? Date.now() - startTime.getTime() : 0;
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

    return {
      total,
      ready,
      enqueued: inQueueCount,
      enriching: enrichmentCount,
      enrichmentTimeout: enrichmentTimeoutCount,
      exensioLoading: exensioLoadingCount,
      exensioTimeout: exensioTimeoutCount,
      processing,
      completed,
      failed,
      cancelled: 0, // Note: cancelled records are computed separately; would need additional tracking
      progress,
      throughput,
      eta,
      successRate,
      startTime,
      elapsedTime,
    };
  });

  private monitoringWaferRows = computed<WaferMonitoringRow[]>(() => {
    const files = this.stagingSession.sessionFiles();
    const previewRows = this.stagedPreviewRows();

    const byLotWafer = new Map<string, StageRecordView>();
    const byLotFilename = new Map<string, StageRecordView>();
    const firstByLot = new Map<string, StageRecordView>();

    files.forEach((file: StageRecordView) => {
      const lot = String(file.lot || '').trim();
      const wafer = String(file.wafer || '').trim();
      const filename = String(file.filename || '').trim();

      if (lot) {
        if (!firstByLot.has(lot)) {
          firstByLot.set(lot, file);
        }

        if (wafer) {
          byLotWafer.set(`${lot}::${wafer}`, file);
        }

        if (filename) {
          byLotFilename.set(`${lot}::${filename}`, file);
        }
      }
    });

    if (previewRows.length === 0) {
      return files.map(
        (file: StageRecordView): WaferMonitoringRow => ({
          lot: file.lot || '',
          wafer: file.wafer || '',
          filename: file.filename || '',
          status: this.mapBackendStatus(file.status),
        }),
      );
    }

    const rows = previewRows.map((row: DiscoveryPreviewRow): WaferMonitoringRow => {
      const lot = String(row.lot || '').trim();
      const wafer = String(row.wafer || '').trim();
      const filename = String(this.getPreviewFilename(row) || '').trim();

      const exact = byLotWafer.get(`${lot}::${wafer}`);
      const byFile = filename ? byLotFilename.get(`${lot}::${filename}`) : undefined;
      const fallbackLot = firstByLot.get(lot);
      const matched = exact || byFile || fallbackLot;

      return {
        lot,
        wafer,
        filename,
        status: matched ? this.mapBackendStatus(matched.status) : ('READY' as MonitoringFile['status']),
      };
    });

    // Ensure unique lot+wafer rows in case preview contains duplicates
    const unique = new Map<string, WaferMonitoringRow>();
    rows.forEach((row: WaferMonitoringRow) => {
      const key = `${row.lot}::${row.wafer || row.filename}`;
      if (!unique.has(key)) {
        unique.set(key, row);
      }
    });

    return Array.from(unique.values());
  });

  // Map stagingSession files to MonitoringFile format
  monitoringFiles = computed(() => {
    return this.monitoringWaferRows().map(
      (row: WaferMonitoringRow, index: number) =>
        ({
          id: `${row.lot}::${row.wafer}::${row.filename}::${index}`,
          filename: row.filename || '',
          lot: row.lot || '',
          wafer: row.wafer || '',
          status: row.status,
          message: row.status,
          errorMessage: undefined,
          updatedAt: undefined,
        }) as MonitoringFile,
    );
  });

  // Convert SessionActivityEvent to ActivityEvent for the activity feed
  activityFeedEvents = computed(() => {
    return this.stagingSession.activities().map((event: SessionActivityEvent) => ({
      id: event.id,
      timestamp: event.timestamp.toISOString(),
      type: event.type,
      message: event.message,
      icon: event.icon,
      color: event.color,
    }));
  });

  // Map backend LotWaferProgress to frontend LotProgress interface
  lotProgressMapped = computed(() => {
    const waferRows = this.monitoringWaferRows();
    const lotMap = new Map<string, { totalFiles: number; doneFiles: number; failedFiles: number; wafers: any[] }>();

    waferRows.forEach((item: WaferMonitoringRow) => {
      const lot = item.lot || '-';
      if (!lotMap.has(lot)) {
        lotMap.set(lot, { totalFiles: 0, doneFiles: 0, failedFiles: 0, wafers: [] });
      }

      const lotData = lotMap.get(lot)!;
      lotData.totalFiles += 1;

      if (item.status === 'COMPLETED') {
        lotData.doneFiles += 1;
      }
      if (item.status === 'ERROR') {
        lotData.failedFiles += 1;
      }

      lotData.wafers.push({
        wafer: item.wafer || '-',
        filename: item.filename || '',
        status: item.status,
        displayStatus: this.mapDisplayStatus(item.status),
      });
    });

    return Array.from(lotMap.entries()).map(([lot, data]) => ({
      lot,
      totalWafers: data.totalFiles,
      completedWafers: data.doneFiles,
      failedWafers: data.failedFiles,
      progress: data.totalFiles > 0 ? (data.doneFiles / data.totalFiles) * 100 : 0,
      expanded: false,
      wafers: data.wafers,
    }));
  });

  private mapDisplayStatus(status: string): string {
    const normalized = (status || '').toUpperCase();
    if (normalized === 'DONE' || normalized === 'COMPLETED') return 'Completed';
    if (normalized === 'FAILED' || normalized === 'ERROR') return 'Failed';
    if (normalized === 'QUEUED_FOR_CP') return 'Queued for Enrichment';
    if (normalized === 'ELASTICSEARCH_MONITORING' || normalized === 'PROCESSING') return 'Enrichment Processing';
    if (normalized === 'CP_TIMEOUT') return 'Enrichment Monitoring Timeout';
    if (normalized === 'EXENSIO_MONITORING') return 'Exensio Monitoring';
    if (normalized === 'COMPLETED_MANUAL_VERIFICATION_REQUIRED') return 'Completed — Verify in Exensio';
    if (normalized === 'STAGED') return 'Staged';
    return 'Unknown';
  }

  isMonitoringWaiting = computed(() => {
    const state = this.monitorUiState();
    return this.currentStep() === 2 && (state === 'connecting' || state === 'waiting' || state === 'polling');
  });

  monitoringStatusText = computed(() => {
    const state = this.monitorUiState();
    const streamStatus: SessionStreamStatus = this.stagingSession.streamStatus();
    if (state === 'stopped') {
      return 'Monitoring stopped. Click Reconnect Live to resume real-time updates.';
    }
    if (state === 'no-session') {
      return 'Monitoring session is not initialized yet.';
    }
    if (state === 'completed') {
      return 'Session completed. You can return to hub or open My Sessions for history.';
    }
    if (streamStatus === 'live') {
      return 'Connected. Receiving live updates.';
    }
    if (streamStatus === 'polling') {
      return 'Live stream unavailable. Showing polling updates.';
    }
    if (streamStatus === 'error') {
      return 'Connection error detected. Retrying live stream while polling continues.';
    }
    if (streamStatus === 'connecting') {
      return 'Connecting to monitoring stream...';
    }
    if (state === 'live') {
      return 'Connected. Receiving live updates.';
    }
    return 'Connecting to monitoring stream...';
  });

  streamStatusTooltip = computed(() => {
    const status = this.stagingSession.streamStatus();
    const statusMessages: Record<string, string> = {
      live: 'Real-time SSE connection active',
      polling: 'Updates via 5-second polling',
      connecting: 'Establishing connection...',
      error: 'Connection error - retrying',
      idle: 'Monitoring not active',
    };
    return statusMessages[status] || 'Unknown status';
  });

  streamStatusLabel = computed(() => {
    const status = this.stagingSession.streamStatus();
    const statusLabels: Record<string, string> = {
      live: 'Live',
      polling: 'Polling',
      connecting: 'Connecting...',
      error: 'Connection Error',
      idle: 'Idle',
    };
    return statusLabels[status] || 'Unknown';
  });

  // Show date range panel if user is admin OR historical mode (historical mode toggle is admin-only)
  showDateRangePanel = computed(() => {
    return this.isAdminUser() || this.historicalMode();
  });

  // Device filter — admin only, populated from external DB filtered by dataType + testerType
  showDeviceFilter = computed(() => {
    return this.isAdminUser() && !!this.selectedDataType();
  });

  private hasActiveDateRange = computed(() => {
    const range = this.dateRange();
    return !!range?.start && !!range?.end;
  });

  isHighVolumeDiscoveryQuery = computed(() => {
    const hasRange = this.hasActiveDateRange();
    return this.historicalMode() || (this.isAdminUser() && hasRange);
  });

  discoveryLoadingSubtext = computed(() => {
    if (this.isHighVolumeDiscoveryQuery()) {
      return 'Using date range/historical filters. Discovery may take longer while scanning more records.';
    }
    return 'Searching external database for matching files';
  });

  // Debounced sender lookup subject
  private senderLookupSubject = new Subject<void>();
  private senderLookupSubscription?: Subscription;
  private userRolesSubscription?: Subscription;
  warmupTimeoutReached = signal(false);
  showLotWaferFilters = signal(true);
  showLotProgressPanel = signal(true);
  private warmupTimeoutHandle: ReturnType<typeof setTimeout> | null = null;

  constructor(
    private backend: BackendService,
    private router: Router,
    private route: ActivatedRoute,
    private authService: AuthService,
    private toast: ToastService,
    private dialog: GlassDialogService,
    public monitoring: MonitoringService,
    public stagingSession: StagingSessionService,
  ) {
    // Setup auto sender lookup when filters change
    this.setupSenderAutoLookup();

    // Setup reactive sender lookup based on filter state
    this.setupReactiveSenderLookup();

    // Subscribe to user$ to update envOptions whenever user/roles change.
    // Using observable subscription instead of computed() to guarantee reactivity.
    this.userRolesSubscription = this.authService.user$.subscribe(
      (user: { username: string; roles: string[] } | null) => {
        const roles = user?.roles || [];
        this.updateEnvOptions(roles);
        // If non-admin, auto-select PROD and load sites
        const isAdmin = roles.includes('ADMIN') || roles.includes('SUPER_ADMIN');
        if (user !== null && !isAdmin) {
          const currentEnv = this.selectedEnv();
          if (currentEnv !== 'PROD') {
            // Call onEnvChange to trigger site loading
            this.onEnvChange('PROD');
          }
        }
      },
    );

    // Clear verification summary when lot/wafer pairs change (detect changes with effect())
    effect(
      () => {
        this.lotWaferPairs();
        // Whenever lots/wafers change, clear the verification summary banner
        this.verificationSummary.set(null);
      },
      { allowSignalWrites: true },
    );

    effect(
      () => {
        const sessionStatus = (this.stagingSession.currentSession()?.status || '').toUpperCase();
        if (sessionStatus === 'COMPLETED' || sessionStatus === 'PARTIALLY_FAILED' || sessionStatus === 'CANCELLED') {
          this.completeStep(2);
          this.clearPersistedMonitoringSession();
        }
      },
      { allowSignalWrites: true },
    );

    // Notify once when session completes
    effect(
      () => {
        const session = this.stagingSession.currentSession();
        if (!session) return;
        const status = (session.status || '').toUpperCase();
        if (
          (status === 'COMPLETED' || status === 'PARTIALLY_FAILED' || status === 'CANCELLED') &&
          !this.sessionCompletedNotified()
        ) {
          const total = session.totalFiles || 0;
          const completed = session.filesDone || 0;
          const failed = session.filesFailed || 0;
          this.toast.success(
            `Session ${session.sessionId} completed: ${completed}/${total} succeeded, ${failed} failed`,
          );
          this.sessionCompletedNotified.set(true);
        }
      },
      { allowSignalWrites: true },
    );

    effect(
      () => {
        const isWarmup = this.monitorUiState() === 'live' && !this.hasMonitoringData();
        if (isWarmup) {
          if (!this.warmupTimeoutHandle && !this.warmupTimeoutReached()) {
            this.warmupTimeoutHandle = setTimeout(() => {
              this.warmupTimeoutReached.set(true);
              this.warmupTimeoutHandle = null;
            }, 10000);
          }
          return;
        }
        this.clearWarmupTimeout();
        if (this.warmupTimeoutReached()) {
          this.warmupTimeoutReached.set(false);
        }
      },
      { allowSignalWrites: true },
    );

    // Auto-select PROD for non-admin users once user data resolves.
    // This effect re-runs whenever currentUser changes (e.g. after async /me call).
    // Also resets QA to PROD if a non-admin somehow has QA selected.
    effect(
      () => {
        const user = this.authService.currentUser();
        const roles = user?.roles || [];
        const isAdmin = roles.includes('ADMIN') || roles.includes('SUPER_ADMIN');
        const currentEnv = this.selectedEnv();

        if (user !== null && !isAdmin) {
          // USER role: always force PROD, never allow QA
          if (currentEnv === 'QA' || currentEnv === null) {
            this.onEnvChange('PROD');
          }
        }
      },
      { allowSignalWrites: true },
    );
  }

  /**
   * Reactive sender lookup: Automatically triggers when relevant filters change.
   * This is cleaner than manually calling senderLookupSubject.next() everywhere.
   */
  private setupReactiveSenderLookup() {
    effect(() => {
      const site = this.selectedSite();
      const location = this.selectedLocation();
      const dataType = this.selectedDataType();
      const testerType = this.normalizeTesterType(this.selectedTesterType());
      const testPhase = this.selectedTestPhase();
      const historical = this.historicalMode();
      const testerTypes = this.testerTypes();
      const testerTypesLoading = this.testerTypesLoading();

      // Determine if we should trigger sender lookup
      const shouldTrigger = this.shouldTriggerSenderLookup(
        site,
        location,
        dataType,
        testerType,
        testPhase,
        historical,
        testerTypes,
        testerTypesLoading,
      );

      if (shouldTrigger) {
        // Use setTimeout to avoid triggering during change detection
        setTimeout(() => {
          this.senderLookupSubject.next();
        }, 0);
      }
    });
  }

  /**
   * Centralized logic to determine when sender lookup should trigger.
   * This makes the business rules explicit and easy to understand/test.
   *
   * CRITICAL FIX: Must check if testerTypes are currently loading to avoid
   * premature triggering before the tester types have been fetched from backend.
   */
  private shouldTriggerSenderLookup(
    site: string | null,
    location: string | null,
    dataType: string | null,
    testerType: string | null,
    testPhase: string | null,
    historical: boolean,
    testerTypes: string[],
    testerTypesLoading: boolean,
  ): boolean {
    // Must have minimum required filters
    if (!site || !location || !dataType) {
      return false;
    }

    // CRITICAL: If tester types are currently loading, wait for them to finish
    // This prevents premature sender lookup before we know if tester types exist
    if (testerTypesLoading) {
      return false;
    }

    // Historical mode: trigger as soon as we have site + location + dataType
    if (historical) {
      return true;
    }

    // Normal mode: Different rules based on tester types availability

    // If no tester types exist (after loading completed), trigger immediately
    if (testerTypes.length === 0) {
      return true;
    }

    // If tester types exist, wait for user to select one
    if (testerType && testerType.trim().length > 0) {
      return true;
    }

    // If test phase is selected, also trigger (refinement)
    if (testPhase && testPhase.trim().length > 0) {
      return true;
    }

    return false;
  }

  ngOnInit() {
    // Auto-select PROD for regular users (non-admins) and trigger site loading.
    // Note: envOptions is reactive via isAdminSignal; the effect in the constructor
    // handles auto-selecting PROD once user data resolves asynchronously.
    this.tryRestoreMonitoringSession();

    // Debounced device filter sync (400ms, matching admin page search pattern)
    this.deviceFilterControl.valueChanges
      .pipe(debounceTime(400), distinctUntilChanged())
      .subscribe((val: string | null) => {
        this.deviceFilter.set(val ?? '');
      });
  }

  /**
   * Setup debounced auto sender lookup when filters change.
   * This provides a modern UX where senders are automatically resolved
   * as the user selects filters, without requiring manual "Find Sender" clicks.
   */
  private setupSenderAutoLookup() {
    this.senderLookupSubscription = this.senderLookupSubject
      .pipe(
        debounceTime(500), // Wait 500ms after last filter change
        switchMap(() => {
          // Check if we have minimum required filters
          if (!this.selectedSite() || !this.selectedLocation() || !this.selectedDataType()) {
            return of(null);
          }

          // Choose lookup strategy based on historical mode
          if (this.historicalMode()) {
            return this.performHistoricalSenderLookup();
          } else {
            return this.performNormalSenderLookup();
          }
        }),
        catchError((err: any) => {
          console.error('Sender lookup error:', err);
          this.toast.error('Failed to lookup sender. Please try again.', 7000);
          return of(null);
        }),
      )
      .subscribe();
  }

  /**
   * Admins include both ADMIN and SUPER_ADMIN roles.
   * Reactive computed signal — safe to use in computed() and templates.
   */
  readonly isAdminUser = computed(() => this.authService.isAdminSignal());

  /**
   * Super admin users can apply date filters outside historical mode.
   */
  private isSuperAdminUser(): boolean {
    return this.authService.isSuperAdmin();
  }

  onEnvChange(env: 'PROD' | 'QA' | null) {
    // Guard: non-admin users cannot select QA — force PROD
    const roles = this.authService.currentUser()?.roles || [];
    const isAdmin = roles.includes('ADMIN') || roles.includes('SUPER_ADMIN');
    const safeEnv = !isAdmin && env === 'QA' ? 'PROD' : env;
    if (safeEnv !== env) {
      console.warn('[StepperComponent] Non-admin attempted to select QA — forcing PROD');
    }
    console.log('Environment changed to:', safeEnv);
    this.selectedEnv.set(safeEnv);

    // Reset site and downstream filters when environment changes
    this.selectedSite.set(null);
    this.selectedLocation.set(null);
    this.selectedDataType.set(null);
    this.selectedTesterType.set(null);
    this.selectedDataTypeExt.set(null);
    this.selectedTestPhase.set(null);
    this.locations.set([]);
    this.dataTypes.set([]);
    this.testerTypes.set([]);
    this.dataTypeExt.set([]);
    this.testPhases.set([]);

    if (!safeEnv) {
      this.siteOptions.set([]);
      return;
    }

    // Load sites for the selected environment from backend
    console.log('Loading sites for environment:', safeEnv);
    this.backend.listSitesForEnvironment(safeEnv).subscribe({
      next: (sites: string[]) => {
        console.log(`Received ${sites.length} sites for ${safeEnv}:`, sites);
        const options: GlassOption[] = sites.map((site) => ({
          value: site,
          label: formatSiteName(site, false), // strip -PROD/-QA suffix for display
        }));
        this.siteOptions.set(options);
      },
      error: (err: any) => {
        console.error(`Failed to load sites for ${safeEnv}:`, err);
        this.toast.error(`Failed to load sites for ${safeEnv}. Please try again.`, 7000);
        this.siteOptions.set([]);
      },
    });
  }

  onSiteChange(site: string | null) {
    this.selectedSite.set(site);
    this.selectedLocation.set(null);
    this.selectedDataType.set(null);
    this.selectedTesterType.set(null);
    this.selectedDataTypeExt.set(null);
    this.selectedTestPhase.set(null);

    // Reset sender state when site changes
    this.resetSenderState();

    if (!site) {
      this.locations.set([]);
      this.dataTypes.set([]);
      this.testerTypes.set([]);
      this.dataTypeExt.set([]);
      this.testPhases.set([]);
      return;
    }

    // Cascading: Start by loading only locations for the selected site
    this.loadLocationsOnly(site);
  }

  onLocationChange(location: string | null) {
    this.selectedLocation.set(location);
    // Reset downstream selections
    this.selectedDataType.set(null);
    this.selectedTesterType.set(null);
    this.selectedDataTypeExt.set(null);
    this.selectedTestPhase.set(null);
    this.dataTypes.set([]);
    this.testerTypes.set([]);
    this.dataTypeExt.set([]);
    this.testPhases.set([]);

    if (location) {
      // Load dataTypes filtered by selected location
      this.loadDataTypesForLocation();
    }
  }

  onDataTypeChange(dataType: string | null) {
    this.selectedDataType.set(dataType);
    // Reset downstream selections
    this.selectedTesterType.set(null);
    this.selectedDataTypeExt.set(null);
    this.selectedTestPhase.set(null);
    this.testerTypes.set([]);
    this.dataTypeExt.set([]);
    this.testPhases.set([]);
    // Reset device filter and options whenever data type changes
    this.deviceFilter.set('');
    this.deviceFilterControl.setValue('', { emitEvent: false });
    this.deviceOptions.set([]);

    if (dataType) {
      // Load both testerTypes and dataTypeExt filtered by location + dataType
      this.loadTesterTypesForDataType();
      this.loadDataTypeExtForDataType();
      this.loadTestPhasesForFilters();
      this.loadDevicesForDataType();
      // Sender lookup will trigger automatically via reactive effect
    }
  }

  onTesterTypeChange(testerType: string | null) {
    this.selectedTesterType.set(testerType);
    this.selectedTestPhase.set(null);
    this.testPhases.set([]);
    // Reset device selection — device list is filtered by tester type
    this.deviceFilter.set('');
    this.deviceFilterControl.setValue('', { emitEvent: false });
    this.deviceOptions.set([]);

    if (this.selectedDataType()) {
      this.loadTestPhasesForFilters();
      // Re-fetch devices filtered by the newly selected tester type
      this.loadDevicesForDataType();
      // Sender lookup will trigger automatically via reactive effect
    }
  }

  onDataTypeExtChange(dataTypeExt: string | null) {
    this.selectedDataTypeExt.set(dataTypeExt);
    if (this.selectedDataType()) {
      this.selectedTestPhase.set(null);
      this.testPhases.set([]);
      this.loadTestPhasesForFilters();
    }
  }

  onTestPhaseChange(testPhase: string | null) {
    this.selectedTestPhase.set(testPhase);
    // Sender lookup will trigger automatically via reactive effect
  }

  // Helper to normalize lot/wafer pairs (matching old frontend lines 2792-2805)
  private normalizedLotWaferPairs(): Array<{ lot: string | null; wafer: string | null }> {
    return (this.lotWaferPairs() || [])
      .map((pair: { lot?: string | null; wafer?: string | null }) => {
        const lotRaw = pair?.lot == null ? null : String(pair.lot).trim();
        const waferRaw = pair?.wafer == null ? null : String(pair.wafer).trim();
        const lot = lotRaw && lotRaw.length ? lotRaw : null;
        const wafer = waferRaw && waferRaw.length ? waferRaw : null;
        return { lot, wafer };
      })
      .filter((pair: { lot: string | null; wafer: string | null }) => pair.lot != null || pair.wafer != null);
  }

  private parseDeviceList(raw: string): string[] {
    if (!raw?.trim()) return [];
    return raw
      .split(/[\n,]+/)
      .map((s) => s.trim())
      .filter((s) => s.length > 0);
  }

  /**
   * Build discovery preview/stage query params from the current form state.
   * Device filter is included whenever an admin has entered or selected a device value.
   */
  private buildDiscoveryPreviewParams(options: { page?: number; size?: number } = {}): {
    params: DiscoveryPreviewRequest;
    snapshot: DiscoveryFiltersSnapshot;
  } | null {
    const site = this.selectedSite();
    if (!site) {
      return null;
    }

    const normalizedPairs = this.normalizedLotWaferPairs();
    const range = this.dateRange();
    const hasRange = !!range?.start && !!range?.end;
    const useDateFilters = this.isAdminUser() && hasRange;
    const startDate = useDateFilters ? (range?.start ?? undefined) : undefined;
    const endDate = useDateFilters ? (range?.end ?? undefined) : undefined;

    const deviceList = this.parseDeviceList(this.deviceFilter());
    const useDeviceFilter = this.isAdminUser() && deviceList.length > 0;

    const useLargePreviewWindow = this.historicalMode() || useDateFilters;
    const defaultSize = useLargePreviewWindow ? 10000 : 1000;
    const page = options.page ?? 0;
    const size = options.size ?? defaultSize;

    const params: DiscoveryPreviewRequest = {
      site,
      environment: this.selectedEnv() ? this.selectedEnv()!.toLowerCase() : 'qa',
      startDate: startDate ?? null,
      endDate: endDate ?? null,
      lots: null,
      wafers: null,
      pairs: normalizedPairs.length ? normalizedPairs : null,
      devices: useDeviceFilter ? deviceList : null,
      testerType: this.selectedTesterType() || null,
      dataType: this.selectedDataType() || null,
      dataTypeExt: this.selectedDataTypeExt() || null,
      testPhase: this.selectedTestPhase() || null,
      location: this.selectedLocation() || null,
      page,
      size,
      bypassCap: useLargePreviewWindow,
      historicalMode: this.historicalMode(),
    };

    const snapshot: DiscoveryFiltersSnapshot = {
      site: params.site,
      environment: params.environment ?? 'qa',
      startDate: params.startDate ?? undefined,
      endDate: params.endDate ?? undefined,
      lots: params.lots,
      wafers: params.wafers,
      pairs: params.pairs,
      devices: useDeviceFilter ? deviceList : undefined,
      testerType: params.testerType ?? undefined,
      dataType: params.dataType ?? undefined,
      dataTypeExt: params.dataTypeExt ?? undefined,
      testPhase: params.testPhase ?? undefined,
      location: params.location ?? undefined,
      historicalMode: !!params.historicalMode,
    };

    return { params, snapshot };
  }

  private buildDiscoveryFiltersFromCurrentSelection(): DiscoveryFiltersSnapshot | null {
    return this.buildDiscoveryPreviewParams()?.snapshot ?? null;
  }

  private getEffectiveDiscoveryFilters(): DiscoveryFiltersSnapshot | null {
    const saved = this.lastDiscoveryFilters();
    const current = this.buildDiscoveryFiltersFromCurrentSelection();
    if (!saved) {
      return current;
    }
    if (!current) {
      return saved;
    }
    // Always apply the live device filter from the form to preview/stage re-queries.
    return {
      ...saved,
      devices: current.devices,
    };
  }

  // Cascading filter loading methods (progressive, not parallel)
  // Reference: old frontend stepper.component.ts lines 1094-1350

  private loadLocationsOnly(site: string) {
    this.locationsLoading.set(true);
    this.backend
      .getDistinctLocations({ connectionKey: site, environment: (this.selectedEnv() || 'QA').toLowerCase() })
      .subscribe({
        next: (locs: string[]) => {
          this.locations.set(locs || []);
          this.locationsLoading.set(false);
        },
        error: (err: any) => {
          console.error('Failed to load locations:', err);
          this.toast.error('Failed to load locations. Please try again.', 7000);
          this.locations.set([]);
          this.locationsLoading.set(false);
        },
      });
  }

  private loadDataTypesForLocation() {
    if (!this.selectedSite() || !this.selectedLocation()) return;

    this.dataTypesLoading.set(true);
    const params: Record<string, string> = {
      connectionKey: this.selectedSite()!,
      environment: (this.selectedEnv() || 'QA').toLowerCase(),
      location: this.selectedLocation()!,
    };

    this.backend.getDistinctDataTypes(params).subscribe({
      next: (dts: string[]) => {
        this.dataTypes.set(dts || []);
        this.dataTypesLoading.set(false);
      },
      error: (err: any) => {
        console.error('Failed to load data types:', err);
        this.toast.error('Failed to load data types. Please try again.', 7000);
        this.dataTypes.set([]);
        this.dataTypesLoading.set(false);
      },
    });
  }

  private loadTesterTypesForDataType() {
    if (!this.selectedSite() || !this.selectedLocation() || !this.selectedDataType()) return;

    this.testerTypesLoading.set(true);
    const params: Record<string, string> = {
      connectionKey: this.selectedSite()!,
      environment: (this.selectedEnv() || 'QA').toLowerCase(),
      location: this.selectedLocation()!,
      dataType: this.selectedDataType()!,
    };

    this.backend.getDistinctTesterTypes(params).subscribe({
      next: (tts: string[]) => {
        const normalized = (tts || []).map((t) => (t ?? '').toString().trim()).filter((t) => t.length > 0);
        const unique = Array.from(new Set(normalized));
        // Non-admin users should not see QA tester types
        const filtered = this.isAdminUser() ? unique : unique.filter((t) => t.toUpperCase() !== 'QA');
        this.testerTypes.set(filtered);
        this.testerTypesLoading.set(false);

        // Reactive effect will automatically trigger sender lookup based on testerTypes state
        // No manual trigger needed here
      },
      error: (err: any) => {
        console.error('Failed to load tester types:', err);
        this.toast.error('Failed to load tester types. Please try again.', 7000);
        this.testerTypes.set([]);
        this.testerTypesLoading.set(false);
      },
    });
  }

  private loadDataTypeExtForDataType() {
    if (!this.selectedSite() || !this.selectedLocation() || !this.selectedDataType()) return;

    this.dataTypeExtLoading.set(true);
    const params: Record<string, string> = {
      connectionKey: this.selectedSite()!,
      environment: (this.selectedEnv() || 'QA').toLowerCase(),
      location: this.selectedLocation()!,
      dataType: this.selectedDataType()!,
    };

    this.backend.getDistinctDataTypeExts(params).subscribe({
      next: (exts: string[]) => {
        this.dataTypeExt.set(exts || []);
        this.dataTypeExtLoading.set(false);
      },
      error: (err: any) => {
        console.error('Failed to load data type extensions:', err);
        this.toast.error('Failed to load data type extensions. Please try again.', 7000);
        this.dataTypeExt.set([]);
        this.dataTypeExtLoading.set(false);
      },
    });
  }

  private loadTestPhasesForFilters() {
    if (!this.selectedSite() || !this.selectedLocation() || !this.selectedDataType()) {
      this.testPhases.set([]);
      return;
    }

    this.testPhasesLoading.set(true);

    const params: Record<string, any> = {
      connectionKey: this.selectedSite()!,
      environment: (this.selectedEnv() || 'QA').toLowerCase(),
      location: this.selectedLocation()!,
      dataType: this.selectedDataType()!,
    };

    const testerType = this.normalizeTesterType(this.selectedTesterType());
    const dataTypeExt = this.selectedDataTypeExt();

    if (testerType) {
      params['testerType'] = testerType;
      // Request strict tester_type matching so test phases are specific to
      // the selected tester type + data type (no NULL tester fallback rows).
      params['exactTesterType'] = true;
    }

    if (dataTypeExt && dataTypeExt.trim().length > 0) {
      params['dataTypeExt'] = dataTypeExt.trim();
    }

    this.backend.getDistinctTestPhases(params).subscribe({
      next: (phases: string[]) => {
        const filtered = this.normalizeTestPhases(
          phases || [],
          this.selectedDataType(),
          this.normalizeTesterType(this.selectedTesterType()),
        );
        this.testPhases.set(filtered);
        this.testPhasesLoading.set(false);
      },
      error: (err: any) => {
        console.error('Failed to load test phases:', err);
        this.toast.error('Failed to load test phases. Please try again.', 7000);
        this.testPhases.set([]);
        this.testPhasesLoading.set(false);
      },
    });
  }

  private loadDevicesForDataType() {
    if (!this.isAdminUser() || !this.selectedSite() || !this.selectedDataType()) {
      this.deviceOptions.set([]);
      return;
    }

    this.devicesLoading.set(true);
    const params: Record<string, any> = {
      connectionKey: this.selectedSite()!,
      environment: (this.selectedEnv() || 'QA').toLowerCase(),
      dataType: this.selectedDataType()!,
    };
    const testerType = this.normalizeTesterType(this.selectedTesterType());
    if (testerType) {
      params['testerType'] = testerType;
    }

    this.backend.getDistinctDevices(params).subscribe({
      next: (devs: string[]) => {
        this.deviceOptions.set(devs || []);
        this.devicesLoading.set(false);
      },
      error: (err: any) => {
        console.error('Failed to load devices:', err);
        this.deviceOptions.set([]);
        this.devicesLoading.set(false);
      },
    });
  }

  onHistoricalModeChange(checked: boolean) {
    this.historicalMode.set(!!checked);
  }

  onFileTypeChange(fileType: string | null) {
    this.selectedFileType.set(fileType || 'ALL');
    this.pageIndex.set(0);
  }

  onPreviewDeviceFilterChange(devices: string[] | null) {
    this.previewDeviceFilter.set(devices || []);
    this.pageIndex.set(0);
  }

  toggleLotWaferFilters() {
    this.showLotWaferFilters.set(!this.showLotWaferFilters());
  }

  toggleLotProgressPanel() {
    this.showLotProgressPanel.set(!this.showLotProgressPanel());
  }

  addPair() {
    this.lotWaferPairs.update((pairs: Array<{ lot: string; wafer: string }>) => [...pairs, { lot: '', wafer: '' }]);
  }

  removePair(index: number) {
    this.lotWaferPairs.update((pairs: Array<{ lot: string; wafer: string }>) =>
      pairs.filter((_: { lot: string; wafer: string }, i: number) => i !== index),
    );
  }

  // Update lot/wafer values and trigger signal update
  updateLot(index: number, value: string) {
    this.lotWaferPairs.update((pairs: Array<{ lot: string; wafer: string }>) => {
      const updated = [...pairs];
      updated[index] = { ...updated[index], lot: value };
      return updated;
    });
  }

  updateWafer(index: number, value: string) {
    this.lotWaferPairs.update((pairs: Array<{ lot: string; wafer: string }>) => {
      const updated = [...pairs];
      updated[index] = { ...updated[index], wafer: value };
      return updated;
    });
  }

  onBulkAddLotsClick(): void {
    const existingLots = this.lotWaferPairs()
      .map((p) => p.lot)
      .filter((l) => !!l.trim());

    const dialogRef = this.dialog.open<
      BulkLotInputDialogComponent,
      BulkLotInputDialogData,
      BulkLotInputDialogResult | undefined
    >(BulkLotInputDialogComponent, { data: { existingLots } });

    dialogRef.afterClosed().then((result) => {
      if (result && result.lots.length > 0) {
        this.addBulkLots(result.lots);
      }
    });
  }

  private addBulkLots(lots: string[]): void {
    const newPairs = lots.map((lot) => ({ lot, wafer: '' }));
    this.lotWaferPairs.update((existing) => [...existing, ...newPairs]);

    this.toast.success(`Added ${lots.length} lot${lots.length === 1 ? '' : 's'}`);

    if (!this.showLotWaferFilters()) {
      this.showLotWaferFilters.set(true);
    }
  }

  canProceedToPreview = computed(() => {
    // Reference: old frontend canSearch() lines 2527-2561
    // Required: site, location, dataType
    const hasRequired = !!this.selectedSite() && !!this.selectedLocation() && !!this.selectedDataType();
    if (!hasRequired) return false;

    // Check for at least one filter beyond the required ones
    const hasLotOnly = this.lotWaferPairs().some((p: { lot: string; wafer: string }) => {
      const lotVal = p.lot ? String(p.lot).trim() : '';
      return !!lotVal;
    });
    const hasTesterType = !!this.normalizeTesterType(this.selectedTesterType());
    const range = this.dateRange();
    const hasDateRange = !!(range?.start && range?.end);
    const deviceList = this.parseDeviceList(this.deviceFilter());
    const hasDeviceFilter = deviceList.length > 0;

    // Historical mode: require date range or tester type (admins only)
    if (this.historicalMode()) {
      return this.isAdminUser() && (hasDateRange || hasTesterType || hasLotOnly || hasDeviceFilter);
    }

    // Admin can also search by device
    if (this.isAdminUser() && hasDeviceFilter) {
      return true;
    }

    // Normal mode: require at least lot/wafer or (for super admins) date range
    if (this.isSuperAdminUser() && hasDateRange) {
      return true;
    }

    return hasLotOnly;
  });

  async loadPreview() {
    // Reference: old frontend buildPreviewRequest() lines 2810-2856 and doPreview() lines 2563-2695

    if (!this.canProceedToPreview()) {
      this.toast.warning(
        'Please select all required filters (Site, Location, Data Type) and at least one additional filter',
      );
      return;
    }

    // Validate sender is selected
    const senderId = this.getSenderIdForRequest();
    if (!senderId) {
      this.toast.error('Sender is required for preview. Please wait for auto-resolution or select manually.');
      return;
    }

    // === NEW: Task 6.3 - Pre-flight lot verification ===
    try {
      const lotsToDiscover = await this.verifyLotsBeforeDiscovery();
      if (lotsToDiscover === null) {
        // User cancelled or verification failed and chose not to proceed
        return;
      }

      // Update lot/wafer pairs with filtered lots if verification returned non-empty list
      if (lotsToDiscover.length > 0 && lotsToDiscover.length < this.lotWaferPairs().length) {
        const filteredPairs = this.lotWaferPairs().filter((pair) => lotsToDiscover.includes(pair.lot?.trim() || ''));
        this.lotWaferPairs.set(filteredPairs);
      }
    } catch (error) {
      console.error('Verification process failed unexpectedly:', error);
      this.toast.error('Verification process failed. Please try again.');
      return;
    }
    // === END: Pre-flight lot verification ===

    this.previewLoading.set(true);
    const built = this.buildDiscoveryPreviewParams();
    if (!built) {
      this.previewLoading.set(false);
      this.toast.error('Site is required for preview request');
      return;
    }

    const { params, snapshot } = built;

    console.log('Sending preview request (matching old frontend pattern):', params);
    console.log('Using sender ID:', senderId);
    console.log('Sender auto-resolved:', this.senderAutoResolved());
    console.log('Normalized pairs:', params.pairs);

    if (params.bypassCap) {
      this.toast.info('Running high-volume discovery query (no preview cap). This may take longer to complete.', 5000);
    }

    this.backend.getDiscoveryPreview(senderId, params).subscribe({
      next: (res: any) => {
        // Handle both old response format (items) and new format (rows)
        const rawRows = res.rows || res.items || [];
        const mappedRows = (rawRows || []).map((row: any) => ({
          ...row,
          filename: this.getPreviewFilename(row),
          originalFileName:
            row?.originalFileName ?? row?.originalFilename ?? row?.original_file_name ?? row?.filename ?? '',
        }));
        const rows = this.deduplicatePreviewRows(mappedRows);
        this.previewRows.set(rows);
        const serverTotal = Number(res.total || 0);
        const totalFound =
          mappedRows.length > rows.length
            ? Math.max(rows.length, serverTotal - (mappedRows.length - rows.length))
            : serverTotal || rows.length;
        this.previewTotal.set(totalFound);
        // If all rows came back in one shot, store them for client-side pagination
        // so subsequent page changes don't re-query the backend.
        if (rows.length >= totalFound && totalFound > 0) {
          this.allPreviewRows.set(rows);
        } else {
          this.allPreviewRows.set([]);
        }
        this.stageExecutionMode.set('selected');
        this.selectedFileType.set('ALL');
        this.filterText.set('');
        this.pageIndex.set(0);
        this.previewLoading.set(false);
        // Don't auto-select - let user select rows manually (single or multi-select)
        this.selectedRows.set(new Set());
        this.selectedRowLookup.set(new Map());
        console.log(`Preview loaded: ${rows.length} rows, ${totalFound} total`);
        this.toast.success(`Found ${totalFound} payload${totalFound === 1 ? '' : 's'}`);

        if (totalFound > rows.length) {
          this.toast.info(
            `Showing ${rows.length} loaded row${rows.length === 1 ? '' : 's'} in preview (of ${totalFound} found). ` +
              `Set File Type to ALL and clear search to view the full loaded set.`,
            7000,
          );
        }

        // Capture discovery query filters for "Stage All Matching" re-execution
        this.lastDiscoveryFilters.set(snapshot);
        this.discoveryToken.set(res.discoveryToken || null);

        // Move to preview step after successful load
        this.currentStep.set(1);
        this.steps[0].completed = true;
      },
      error: (err: any) => {
        this.previewLoading.set(false);
        const errorMsg = err?.error?.message || err?.statusText || 'Failed to load preview';
        console.error('Preview error:', errorMsg, err);
        this.toast.error(`Preview failed: ${errorMsg}`);
      },
    });
  }

  private fetchPreviewPage(page: number, size: number) {
    // If all rows are already loaded locally, just update the page index — no backend call needed
    if (this.allPreviewRows().length > 0) {
      this.pageIndex.set(page);
      this.pageSize.set(size);
      return;
    }

    const filters = this.getEffectiveDiscoveryFilters();
    const senderId = this.getSenderIdForRequest();

    if (!filters || !senderId) {
      this.toast.warning('Run discovery preview first before changing pages.');
      return;
    }

    const hasDateRange = !!filters.startDate && !!filters.endDate;
    const useBypassCap = !!filters.historicalMode || hasDateRange;
    const deviceList = filters.devices && filters.devices.length > 0 ? filters.devices : null;

    const params: DiscoveryPreviewRequest = {
      site: filters.site,
      environment: filters.environment,
      startDate: filters.startDate ?? null,
      endDate: filters.endDate ?? null,
      lots: filters.lots,
      wafers: filters.wafers,
      pairs: filters.pairs,
      devices: deviceList,
      testerType: filters.testerType ?? null,
      dataType: filters.dataType ?? null,
      dataTypeExt: filters.dataTypeExt ?? null,
      testPhase: filters.testPhase ?? null,
      location: filters.location ?? null,
      page,
      size,
      bypassCap: useBypassCap,
      historicalMode: filters.historicalMode,
    };

    this.previewLoading.set(true);
    this.backend.getDiscoveryPreview(senderId, params).subscribe({
      next: (res: any) => {
        const rawRows = res.rows || res.items || [];
        const mappedRows = (rawRows || []).map((row: any) => ({
          ...row,
          filename: this.getPreviewFilename(row),
          originalFileName:
            row?.originalFileName ?? row?.originalFilename ?? row?.original_file_name ?? row?.filename ?? '',
        }));
        const rows = this.deduplicatePreviewRows(mappedRows);

        this.previewRows.set(rows);
        const adjustedTotal =
          rows.length < mappedRows.length
            ? Math.max(0, Number(res.total || 0) - (mappedRows.length - rows.length))
            : Number(res.total || rows.length);
        this.previewTotal.set(adjustedTotal);
        this.pageIndex.set(page);
        this.pageSize.set(size);
        this.previewLoading.set(false);
      },
      error: (err: any) => {
        this.previewLoading.set(false);
        const errorMsg = err?.error?.message || err?.statusText || 'Failed to load preview page';
        console.error('Preview pagination error:', errorMsg, err);
        this.toast.error(`Preview paging failed: ${errorMsg}`);
      },
    });
  }

  private getFileType(filename: string | null | undefined): string {
    const raw = String(filename || '').trim();
    if (!raw) {
      return 'UNKNOWN';
    }

    const upper = raw.toUpperCase();
    const withoutGz = upper.endsWith('.GZ') ? upper.slice(0, -3) : upper;
    const lastDotIndex = withoutGz.lastIndexOf('.');

    if (lastDotIndex >= 0 && lastDotIndex < withoutGz.length - 1) {
      return withoutGz.slice(lastDotIndex + 1);
    }

    return 'UNKNOWN';
  }

  toggleSelection(row: DiscoveryPreviewRow, eventOrChecked?: MouseEvent | KeyboardEvent | boolean) {
    const id = this.getRowKey(row);
    // Determine if this is multi-select mode
    let isMultiSelect = false;

    if (typeof eventOrChecked === 'boolean') {
      // Checkbox was clicked - always use multi-select for checkboxes
      isMultiSelect = true;
    } else if (eventOrChecked && typeof eventOrChecked === 'object') {
      // Row was clicked - check for Ctrl/Cmd key (works for MouseEvent, PointerEvent, KeyboardEvent)
      const evt = eventOrChecked as any;
      isMultiSelect = evt.ctrlKey === true || evt.metaKey === true;
    }

    const next = new Set(this.selectedRows());
    const lookup = new Map(this.selectedRowLookup());

    if (isMultiSelect) {
      // Multi-select mode: toggle the clicked item
      if (next.has(id)) {
        next.delete(id);
        lookup.delete(id);
      } else {
        next.add(id);
        lookup.set(id, row);
      }
    } else {
      // Single-select mode (default): clear all and select only this item
      if (next.has(id) && next.size === 1) {
        // If clicking the only selected item, deselect it
        next.clear();
        lookup.clear();
      } else {
        // Clear all and select only this item
        next.clear();
        next.add(id);
        lookup.clear();
        lookup.set(id, row);
      }
    }

    this.selectedRows.set(next);
    this.selectedRowLookup.set(lookup);
  }

  onRowClick(row: DiscoveryPreviewRow, event: MouseEvent) {
    // Prevent default browser behavior for Ctrl+Click (which might open in new tab)
    if (event.ctrlKey || event.metaKey) {
      event.preventDefault();
      this.toggleSelection(row, event);
      return;
    }

    this.toggleSelectionByRow(row);
  }

  onCheckboxCellClick(row: DiscoveryPreviewRow, event: MouseEvent) {
    // Stop propagation to prevent row click from firing
    event.stopPropagation();

    // Checkbox clicks always use multi-select mode (toggle)
    this.toggleSelection(row, true);
  }

  onCheckboxToggle(row: DiscoveryPreviewRow, checked: boolean) {
    const id = this.getRowKey(row);
    const next = new Set(this.selectedRows());
    const lookup = new Map(this.selectedRowLookup());

    if (checked) {
      next.add(id);
      lookup.set(id, row);
    } else {
      next.delete(id);
      lookup.delete(id);
    }

    this.selectedRows.set(next);
    this.selectedRowLookup.set(lookup);
  }

  toggleSelectionByRow(row: DiscoveryPreviewRow) {
    const familyKeys = this.getRowFamilyKeys(row);
    const current = this.selectedRows();

    // Toggle behavior: if same family already selected, clear it.
    if (this.setsEqual(current, familyKeys)) {
      this.selectedRows.set(new Set());
      this.selectedRowLookup.set(new Map());
      return;
    }

    // Default single-click behavior selects entire lot+filename family (PCM-friendly).
    this.selectedRows.set(new Set(familyKeys));
    const lookup = new Map<string, DiscoveryPreviewRow>();
    this.previewRows().forEach((candidate: DiscoveryPreviewRow) => {
      const key = this.getRowKey(candidate);
      if (familyKeys.has(key)) {
        lookup.set(key, candidate);
      }
    });
    this.selectedRowLookup.set(lookup);
  }

  isRowSelected(row: DiscoveryPreviewRow): boolean {
    const rowKey = this.getRowKey(row);
    const selected = this.selectedRows();
    const result = selected.has(rowKey);
    return result;
  }

  toggleAll() {
    const currentPageRows = this.paginatedPreviewRows();
    const allCurrentSelected = currentPageRows.every((r: DiscoveryPreviewRow) =>
      this.selectedRows().has(this.getRowKey(r)),
    );

    const next = new Set(this.selectedRows());
    const lookup = new Map(this.selectedRowLookup());

    if (allCurrentSelected) {
      // Deselect all on current page
      currentPageRows.forEach((r: DiscoveryPreviewRow) => {
        const key = this.getRowKey(r);
        next.delete(key);
        lookup.delete(key);
      });
    } else {
      // Select all on current page
      currentPageRows.forEach((r: DiscoveryPreviewRow) => {
        const key = this.getRowKey(r);
        next.add(key);
        lookup.set(key, r);
      });
    }

    this.selectedRows.set(next);
    this.selectedRowLookup.set(lookup);
  }

  selectAllPages() {
    this.setStageExecutionMode('all');
    this.toast.info('Use Stage All Matching to include every discovered file across all pages.', 5000);
  }

  deselectAll() {
    this.selectedRows.set(new Set());
    this.selectedRowLookup.set(new Map());
  }

  // Navigation methods
  totalPages = computed(() => {
    // When paginating client-side, base page count on filtered rows
    const total = this.allPreviewRows().length > 0 ? this.filteredPreviewRows().length : this.previewTotal();
    return Math.max(1, Math.ceil(total / this.pageSize()));
  });
  hasMultiplePreviewPages = computed(() => this.previewTotal() > this.pageSize());

  backToConfig() {
    // Clear all loaded preview data so a fresh discovery runs next time
    this.allPreviewRows.set([]);
    this.previewRows.set([]);
    this.skippedDuplicatesCount.set(0);
    this.restagedCount.set(0);
    this.allDuplicatesSkipped.set(false);
    // Task 8.3: Clear verification summary when returning to config (Requirement 6.5)
    this.verificationSummary.set(null);
    this.currentStep.set(0);
  }

  /**
   * Reset Step 1 configuration to initial state.
   * Clears environment, site, all filters, sender, lot/wafer pairs, and preview data.
   * User can start fresh without leaving the page.
   */
  resetStep1() {
    // Clear all selections
    this.selectedEnv.set(null);
    this.selectedSite.set(null);
    this.selectedLocation.set(null);
    this.selectedDataType.set(null);
    this.selectedTesterType.set(null);
    this.selectedDataTypeExt.set(null);
    this.selectedTestPhase.set(null);
    this.dateRange.set(null);
    this.deviceFilter.set('');
    this.deviceFilterControl.setValue('', { emitEvent: false });
    this.historicalMode.set(false);

    // Clear all filter options
    this.siteOptions.set([]);
    this.locations.set([]);
    this.dataTypes.set([]);
    this.testerTypes.set([]);
    this.dataTypeExt.set([]);
    this.testPhases.set([]);
    this.deviceOptions.set([]);

    // Clear sender state
    this.resetSenderState();

    // Reset lot/wafer pairs to single empty pair
    this.lotWaferPairs.set([{ lot: '', wafer: '' }]);

    // Clear all preview data
    this.previewRows.set([]);
    this.allPreviewRows.set([]);
    this.previewTotal.set(0);
    this.selectedRows.set(new Set());
    this.selectedRowLookup.set(new Map());
    this.filterText.set('');
    this.selectedFileType.set('ALL');
    this.pageIndex.set(0);
    this.pageSize.set(25);

    // Task 8.3: Clear verification summary when resetting config (Requirement 6.5)
    this.verificationSummary.set(null);

    // Show success feedback
    this.toast.info('Configuration reset. Ready to start a new request.', 4000);
  }

  goToFirstPage() {
    if (this.pageIndex() !== 0) {
      this.fetchPreviewPage(0, this.pageSize());
    }
  }

  goToPreviousPage() {
    if (this.pageIndex() > 0) {
      this.fetchPreviewPage(this.pageIndex() - 1, this.pageSize());
    }
  }

  goToNextPage() {
    if (this.pageIndex() < this.totalPages() - 1) {
      this.fetchPreviewPage(this.pageIndex() + 1, this.pageSize());
    }
  }

  goToLastPage() {
    const last = this.totalPages() - 1;
    if (this.pageIndex() !== last) {
      this.fetchPreviewPage(last, this.pageSize());
    }
  }

  onPageChange(event: PaginationEvent) {
    this.fetchPreviewPage(event.pageIndex, event.pageSize);
  }

  setStageExecutionMode(mode: 'selected' | 'all') {
    this.stageExecutionMode.set(mode);
  }

  runPrimaryStageAction() {
    if (this.stageExecutionMode() === 'all') {
      this.stageAllMatching();
      return;
    }
    this.stageSelected();
  }

  stageSelected(forceDuplicates: boolean = false) {
    // Prevent concurrent staging
    if (this.staging()) {
      this.toast.warning('Staging operation already in progress');
      return;
    }

    // Collect selected payloads
    const selectedKeys = Array.from(this.selectedRows());
    if (selectedKeys.length === 0) {
      this.toast.warning('Please select at least one payload to stage');
      return;
    }

    // Validate sender is selected
    const senderId = this.getSenderIdForRequest();
    if (!senderId) {
      this.toast.error('Sender is required for staging. Please wait for auto-resolution or select manually.');
      return;
    }

    // Map selected row keys back to payload items
    const payloads: any[] = [];
    const allRows = this.previewRows();
    const selectedPreviewRows: DiscoveryPreviewRow[] = [];
    const selectedLookup = this.selectedRowLookup();

    for (const rowKey of selectedKeys) {
      const row = selectedLookup.get(rowKey) || allRows.find((r: DiscoveryPreviewRow) => this.getRowKey(r) === rowKey);
      if (row && row.metadataId && row.dataId) {
        selectedPreviewRows.push(row);
        payloads.push({
          metadataId: row.metadataId,
          dataId: row.dataId,
          lot: row.lot || null,
          wafer: row.wafer || null,
          filename: this.getPreviewFilename(row) || null,
          endTime: row.endTime ? String(row.endTime) : null,
        });
      }
    }

    // Keep wafer-level projection for Step 3.
    // If a single selected file represents many wafers (PCM), expand by lot+filename
    // so monitoring mirrors Discovery Preview wafer rows.
    this.stagedPreviewRows.set(this.expandMonitoringRows(selectedPreviewRows, allRows));

    if (payloads.length === 0) {
      this.toast.error('No valid payloads found to stage');
      return;
    }

    const selectedSite = this.selectedSite();

    if (!selectedSite) {
      this.toast.error('Site is required for staging');
      return;
    }

    const resolvedEnvironment = this.selectedEnv() ? this.selectedEnv()!.toLowerCase() : 'qa';
    this.staging.set(true);

    console.log('[STAGING] Creating session...', {
      site: selectedSite,
      senderId,
      senderName: this.getSelectedSenderName(),
      environment: resolvedEnvironment,
    });

    this.stagingSession
      .createSession(selectedSite, senderId, this.getSelectedSenderName(), resolvedEnvironment)
      .subscribe({
        next: (session: CreateSessionResponse) => {
          console.log('[STAGING] Session created:', session.sessionId);
          this.requestId.set(session.sessionId);

          // Show toast notification based on ETL trigger status
          if (session.status === 'success') {
            this.toast.success('SSH trigger sent. Audit logged.', 5000);
          } else if (session.status === 'failure') {
            this.toast.error(`SSH trigger failed. See audit for details.`, 7000);
          } else if (session.status === 'not_configured') {
            this.toast.info('SSH trigger not configured.', 5000);
          }

          const body: any = {
            site: selectedSite,
            environment: resolvedEnvironment,
            senderId: senderId,
            senderName: this.getSelectedSenderName(),
            payloads: payloads,
            triggerDispatch: true,
            forceDuplicates: forceDuplicates,
            requestId: session.sessionId,
            dataType: this.selectedDataType() || null,
            testPhase: this.selectedTestPhase() || null,
          };

          console.log('[STAGING] Staging payloads...', { count: payloads.length, sessionId: session.sessionId });

          this.backend.stagePayloads(senderId, body).subscribe({
            next: (response: any) => {
              console.log('[STAGING] Staging response received:', response);
              if (response?.requiresConfirmation && !forceDuplicates) {
                this.staging.set(false);
                this.showDuplicateConfirmation(
                  response,
                  payloads.length,
                  {
                    sessionId: session.sessionId,
                    site: selectedSite,
                    senderId,
                    stagedCount: response?.staged ?? 0,
                    duplicateCount: response?.duplicates ?? 0,
                    mode: 'selected',
                  },
                  payloads,
                );
                return;
              }

              const stagedCount = response?.staged ?? payloads.length;
              const duplicateCount = response?.duplicates ?? 0;

              if (duplicateCount > 0) {
                this.toast.success(
                  `Staged ${stagedCount} payload${stagedCount === 1 ? '' : 's'} (${duplicateCount} duplicate${duplicateCount === 1 ? '' : 's'} skipped)`,
                  6000,
                );
              } else {
                this.toast.success(`Successfully staged ${stagedCount} payload${stagedCount === 1 ? '' : 's'}`, 5000);
              }

              if (stagedCount <= 0) {
                this.staging.set(false);
                // Files are already in the queue — proceed to monitoring so the user
                // can see their current status rather than hitting a dead end.
                this.restagedCount.set(payloads.length);
                this.toast.info(
                  `These ${payloads.length} file${payloads.length === 1 ? ' is' : 's are'} already staged and queued. Opening monitoring to track progress.`,
                  6000,
                );
                this.completeStep(1);
                this.currentStep.set(2);
                this.startMonitoring();
                return;
              }
              this.restagedCount.set(response?.requeued ?? 0);

              this.completeStep(1);
              this.currentStep.set(2);
              this.staging.set(false);

              // Start monitoring after moving to step 3
              console.log('[STAGING] Moving to step 3 and starting monitoring');
              this.startMonitoring();
            },
            error: (err: any) => {
              console.error('[STAGING] Staging payloads failed:', err);
              this.staging.set(false);
              const errorMsg = err?.error?.message || err?.statusText || 'Failed to stage payloads';
              this.toast.error(`Staging failed: ${errorMsg}`, 7000);
            },
          });
        },
        error: (err: any) => {
          console.error('[STAGING] Session creation failed:', err);
          this.staging.set(false);
          const errorMsg = err?.error?.message || err?.statusText || 'Failed to create staging session';
          this.toast.error(`Session creation failed: ${errorMsg}`, 7000);
        },
      });
  }

  stageAllMatching(forceDuplicates: boolean = false) {
    // Prevent concurrent staging
    if (this.staging()) {
      this.toast.warning('Staging operation already in progress');
      return;
    }

    const filters = this.getEffectiveDiscoveryFilters();
    if (!filters) {
      this.toast.error('No discovery query filters available. Please run discovery first.');
      return;
    }

    // Validate sender is selected
    const senderId = this.getSenderIdForRequest();
    if (!senderId) {
      this.toast.error('Sender is required for staging. Please wait for auto-resolution or select manually.');
      return;
    }

    const selectedSite = this.selectedSite();
    if (!selectedSite) {
      this.toast.error('Site is required for staging');
      return;
    }

    // Show confirmation dialog with query details
    const dialogRef = this.dialog.open(ConfirmStageAllDialogComponent, {
      data: {
        queryFilters: filters,
        totalDiscovered: this.previewTotal(),
        selectedCount: this.selectedRows().size,
      } as ConfirmStageAllDialogData,
      disableClose: false,
      panelClass: 'glass-dialog',
      backdropClass: 'glass-backdrop',
    });

    dialogRef.afterClosed().then((result: any) => {
      if (!result || !result.confirmed) {
        return;
      }
      // forceDuplicates comes from the duplicate policy radio in the confirm dialog
      const forceDuplicates = result.forceDuplicates === true;

      const resolvedEnvironment = this.selectedEnv() ? this.selectedEnv()!.toLowerCase() : 'qa';
      this.staging.set(true);
      this.stageAllMode.set(true);

      console.log('[STAGING] Creating session for stage-all...', {
        site: selectedSite,
        senderId,
        senderName: this.getSelectedSenderName(),
        environment: resolvedEnvironment,
      });

      this.stagingSession
        .createSession(selectedSite, senderId, this.getSelectedSenderName(), resolvedEnvironment)
        .subscribe({
          next: (session: CreateSessionResponse) => {
            console.log('[STAGING] Session created for stage-all:', session.sessionId);
            this.requestId.set(session.sessionId);

            // Show toast notification based on ETL trigger status
            if (session.status === 'success') {
              this.toast.success('SSH trigger sent. Audit logged.', 5000);
            } else if (session.status === 'failure') {
              this.toast.error(`SSH trigger failed. See audit for details.`, 7000);
            } else if (session.status === 'not_configured') {
              this.toast.info('SSH trigger not configured.', 5000);
            }

            // Move to monitor immediately — don't wait for staging to complete.
            // The backend stages all files and incrementally enqueues up to the queue
            // capacity limit; the monitor will reflect progress via SSE/polling.
            this.completeStep(1);
            this.currentStep.set(2);
            this.staging.set(false);
            // Keep stageAllMode=true so the monitor banner shows incremental enqueue info
            this.selectedRows.set(new Set());
            this.selectedRowLookup.set(new Map());
            this.stagedPreviewRows.set([]);
            this.toast.info(
              `Staging ${this.previewTotal()} files in the background. Monitor will update as files are staged and enqueued.`,
              6000,
            );
            this.startMonitoring();

            const hasDateRange = !!filters.startDate && !!filters.endDate;
            const useBypassCap = !!filters.historicalMode || hasDateRange;

            // If all rows are already loaded locally, build payloads directly — no re-query
            const localRows = this.allPreviewRows();
            if (localRows.length > 0 && localRows.length >= this.previewTotal()) {
              const payloads = localRows
                .filter((r: DiscoveryPreviewRow) => r.metadataId && r.dataId)
                .map((r: DiscoveryPreviewRow) => ({
                  metadataId: r.metadataId!,
                  dataId: r.dataId!,
                  lot: r.lot || null,
                  wafer: r.wafer || null,
                  filename: this.getPreviewFilename(r) || null,
                  endTime: r.endTime ? String(r.endTime) : null,
                }));

              const body: StagePayloadRequestBody = {
                site: selectedSite,
                environment: resolvedEnvironment,
                senderId,
                senderName: this.getSelectedSenderName(),
                payloads,
                triggerDispatch: true,
                forceDuplicates: forceDuplicates, // set by user's duplicate policy choice in confirm dialog
                requestId: session.sessionId,
                dataType: filters.dataType || null,
                testPhase: filters.testPhase || null,
              };

              this.backend.stagePayloads(senderId, body).subscribe({
                next: (response: any) => this.handleStageAllBackgroundResponse(response),
                error: (err: any) => {
                  console.error('[STAGING] Stage-all (local) background failed:', err);
                  this.toast.error(
                    `Background staging error: ${err?.error?.message || err?.statusText || 'Unknown error'}`,
                    7000,
                  );
                },
              });
              return;
            }

            // Fallback: re-query via stage-all endpoint (large result sets / cache miss)
            const body: any = {
              ...filters,
              senderId: senderId,
              senderName: this.getSelectedSenderName(),
              triggerDispatch: true,
              forceDuplicates: forceDuplicates, // set by user's duplicate policy choice in confirm dialog
              requestId: session.sessionId,
              maxRows: 100000,
              bypassCap: true,
              discoveryToken: this.discoveryToken(),
            };

            this.backend.stageAll(senderId, body).subscribe({
              next: (response: any) => this.handleStageAllBackgroundResponse(response),
              error: (err: any) => {
                console.error('[STAGING] Stage-all background failed:', err);
                this.toast.error(
                  `Background staging error: ${err?.error?.message || err?.statusText || 'Failed to stage all payloads'}`,
                  7000,
                );
              },
            });
          },
          error: (err: any) => {
            console.error('[STAGING] Session creation failed:', err);
            this.staging.set(false);
            this.stageAllMode.set(false);
            const errorMsg = err?.error?.message || err?.statusText || 'Failed to create staging session';
            this.toast.error(`Session creation failed: ${errorMsg}`, 7000);
          },
        });
    });
  }

  private showDuplicateConfirmation(
    response: any,
    totalSelected: number,
    context: DuplicateStageContext,
    selectedPayloads: Array<{
      metadataId: string;
      dataId: string;
      lot?: string | null;
      wafer?: string | null;
      filename?: string | null;
    }>,
  ) {
    const rawDuplicates = response?.duplicatePayloads || [];
    const duplicateSourceByKey = new Map<
      string,
      { lot?: string | null; wafer?: string | null; filename?: string | null }
    >();
    selectedPayloads.forEach((p) => {
      const key = this.getRowKeyFromIds(p.metadataId, p.dataId);
      if (!duplicateSourceByKey.has(key)) {
        duplicateSourceByKey.set(key, {
          lot: p.lot,
          wafer: p.wafer,
          filename: p.filename,
        });
      }
    });

    const duplicates: DuplicatePayloadInfo[] = rawDuplicates.map((dup: any) => {
      const key = this.getRowKeyFromIds(dup?.metadataId || '', dup?.dataId || null);
      const source = duplicateSourceByKey.get(key);
      const lot = String(dup?.lot ?? source?.lot ?? '').trim();
      const wafer = String(dup?.wafer ?? source?.wafer ?? '').trim();
      const filename = String(dup?.filename ?? source?.filename ?? '').trim();

      return {
        ...dup,
        lot,
        wafer,
        filename,
      } as DuplicatePayloadInfo;
    });

    const dialogRef = this.dialog.open(DuplicateWarningDialogComponent, {
      data: {
        duplicates: duplicates,
        totalSelected: totalSelected,
      } as DuplicateWarningDialogData,
      disableClose: false,
      panelClass: 'glass-dialog',
      backdropClass: 'glass-backdrop',
    });

    dialogRef.afterClosed().then((result: any) => {
      const confirmed = result?.confirmed;
      const selectedKeys: Set<string> | undefined = result?.selectedKeys;

      if (confirmed === true) {
        if (context.mode === 'all') {
          this.forceStageAllWithDuplicates(context);
          return;
        }

        // Filter duplicates by selection if user chose specific files
        const targetDuplicates = selectedKeys
          ? duplicates.filter(
              (dup: DuplicatePayloadInfo) => !!dup.metadataId && selectedKeys.has(`${dup.metadataId}::${dup.dataId}`),
            )
          : duplicates.filter((dup: DuplicatePayloadInfo) => !!dup.metadataId);

        const duplicatePayloads = targetDuplicates.map((dup: DuplicatePayloadInfo) => ({
          metadataId: dup.metadataId,
          dataId: dup.dataId || null,
          lot: dup.lot || null,
          wafer: dup.wafer || null,
          filename: dup.filename || null,
          endTime: null,
        }));

        this.forceStageDuplicatePayloads(context, duplicatePayloads);
        return;
      }

      if (result === undefined || confirmed === undefined) {
        this.staging.set(false);
        this.stageAllMode.set(false);
        this.toast.info('Duplicate confirmation dismissed. No staging changes were applied.', 5000);
        return;
      }

      const stagedCount = context.stagedCount || 0;
      const duplicateCount = context.duplicateCount || 0;

      if (stagedCount <= 0) {
        if (context.mode === 'all') {
          // All matches were duplicates — move to step 3 to show the dedicated "all skipped" UI.
          this.staging.set(false);
          this.stageAllMode.set(false);
          this.allDuplicatesSkipped.set(true);
          this.skippedDuplicatesCount.set(duplicateCount);
          this.completeStep(1);
          this.currentStep.set(2);
          return;
        }
        // Stage-selected: all selected were duplicates — show dedicated UI on step 3
        this.staging.set(false);
        this.allDuplicatesSkipped.set(true);
        this.skippedDuplicatesCount.set(duplicateCount);
        this.completeStep(1);
        this.currentStep.set(2);
        return;
      }

      // Default behavior: skip duplicates and continue with newly staged payloads
      this.backend.dispatch({ site: context.site, senderId: context.senderId, limit: null }).subscribe({
        next: () => {
          this.completeStep(1);
          this.currentStep.set(2);
          this.toast.info(
            `Skipped ${duplicateCount} duplicate payload${duplicateCount === 1 ? '' : 's'}. Continuing with ${stagedCount} staged payload${stagedCount === 1 ? '' : 's'}.`,
            6000,
          );
          this.startMonitoring();
        },
        error: (err: any) => {
          console.error('[STAGING] Dispatch after duplicate skip failed:', err);
          this.completeStep(1);
          this.currentStep.set(2);
          this.toast.warning(
            `Duplicates were skipped. ${stagedCount} payload${stagedCount === 1 ? '' : 's'} staged, but dispatch failed. Please retry dispatch.`,
            7000,
          );
          this.startMonitoring();
        },
      });
    });
  }

  private handleStageAllResponse(
    response: any,
    session: CreateSessionResponse,
    selectedSite: string,
    senderId: number,
    filters: DiscoveryFiltersSnapshot,
    forceDuplicates: boolean,
  ) {
    console.log('[STAGING] Stage-all response received:', response);

    // Show toast notification based on ETL trigger status
    if (session.status === 'success') {
      this.toast.success('SSH trigger sent. Audit logged.', 5000);
    } else if (session.status === 'failure') {
      this.toast.error(`SSH trigger failed. See audit for details.`, 7000);
    } else if (session.status === 'not_configured') {
      this.toast.info('SSH trigger not configured.', 5000);
    }

    if (response?.requiresConfirmation && !forceDuplicates) {
      this.staging.set(false);
      this.stageAllMode.set(false);
      const totalAvailable = Number(response?.totalAvailable ?? this.previewTotal() ?? 0);
      const context = {
        sessionId: session.sessionId,
        site: selectedSite,
        senderId,
        stagedCount: response?.staged ?? 0,
        duplicateCount: response?.duplicates ?? 0,
        totalAvailable,
        mode: 'all' as const,
        filters,
      };
      const dummyPayloads: any[] = response?.duplicatePayloads || [];
      this.showDuplicateConfirmation(response, totalAvailable, context, dummyPayloads);
      return;
    }

    const stagedCount = response?.staged ?? this.previewTotal();
    const duplicateCount = response?.duplicates ?? 0;

    if (duplicateCount > 0) {
      this.toast.success(
        `Staged ${stagedCount} payload${stagedCount === 1 ? '' : 's'} (${duplicateCount} duplicate${duplicateCount === 1 ? '' : 's'} skipped)`,
        6000,
      );
    } else {
      this.toast.success(`Successfully staged ${stagedCount} payload${stagedCount === 1 ? '' : 's'}`, 5000);
    }

    if (stagedCount <= 0) {
      this.staging.set(false);
      this.stageAllMode.set(false);
      const total = this.previewTotal();
      this.selectedRows.set(new Set());
      this.selectedRowLookup.set(new Map());
      this.stagedPreviewRows.set([]);
      // Nothing new was staged — all items were already in the queue or were duplicates.
      // Don't open an empty monitor; show a clear message and stay on step 2.
      const dupCount = response?.duplicates ?? 0;
      if (dupCount > 0) {
        this.allDuplicatesSkipped.set(true);
        this.skippedDuplicatesCount.set(dupCount);
        this.completeStep(1);
        this.currentStep.set(2);
      } else {
        this.toast.info(
          `These ${total} file${total === 1 ? ' is' : 's are'} already staged and queued. Opening monitoring to track progress.`,
          6000,
        );
        this.restagedCount.set(total);
        this.completeStep(1);
        this.currentStep.set(2);
        this.startMonitoring();
      }
      return;
    }
    this.restagedCount.set(response?.requeued ?? 0);
    this.completeStep(1);
    this.currentStep.set(2);
    this.staging.set(false);
    this.stageAllMode.set(false);
    this.selectedRows.set(new Set());
    this.selectedRowLookup.set(new Map());
    this.stagedPreviewRows.set([]);
    this.startMonitoring();
  }

  /**
   * Handles the background staging response when the UI has already moved to step 3.
   * Only shows a toast update — no navigation needed.
   */
  private handleStageAllBackgroundResponse(response: any) {
    const stagedCount = response?.staged ?? 0;
    const requeuedCount = response?.requeued ?? 0;
    const duplicateCount = response?.duplicates ?? 0;
    const requiresConfirmation = response?.requiresConfirmation ?? false;
    console.log('[STAGING] Background stage-all completed:', {
      stagedCount,
      requeuedCount,
      duplicateCount,
      requiresConfirmation,
    });

    // Persist skipped duplicate count so the monitor banner stays visible
    if (duplicateCount > 0) {
      this.skippedDuplicatesCount.set(duplicateCount);
    }

    // If backend still blocked on requiresConfirmation despite forceDuplicates=true,
    // trigger a manual dispatch to unblock — we're already on the monitor step.
    if (requiresConfirmation) {
      if (stagedCount <= 0 && duplicateCount > 0) {
        // All files were duplicates — nothing was staged. Stop monitoring and show dedicated UI.
        console.warn('[STAGING] All files are duplicates in background stage-all — nothing staged, stopping monitor');
        this.stageAllMode.set(false);
        this.allDuplicatesSkipped.set(true);
        this.skippedDuplicatesCount.set(duplicateCount);
        this.stopMonitoring(true);
        // Cancel the empty session on the backend so it doesn't linger as STAGING
        const sessionId = this.requestId();
        if (sessionId) {
          this.backend.cancelStagingSession(sessionId).subscribe({ error: () => {} });
        }
        return;
      }
      console.warn('[STAGING] requiresConfirmation=true in background response — triggering manual dispatch');
      const sessionId = this.requestId();
      const senderId = this.getSenderIdForRequest();
      const site = this.selectedSite();
      if (senderId && site) {
        this.backend.dispatch({ site, senderId }).subscribe({
          next: () => {
            this.toast.info('Dispatch triggered manually after duplicate resolution.', 5000);
            if (sessionId) {
              this.stagingSession.refreshSessionFiles(sessionId);
            }
          },
          error: () => {},
        });
      }
      return;
    }

    const effective = stagedCount + requeuedCount;
    if (effective > 0) {
      this.toast.success(
        `${effective} file${effective === 1 ? '' : 's'} staged/re-queued.` +
          (duplicateCount > 0 ? ` ${duplicateCount} duplicate${duplicateCount === 1 ? '' : 's'} skipped.` : '') +
          ` Enqueuing incrementally in the background.`,
        7000,
      );
    }

    const sessionId = this.requestId();
    if (sessionId) {
      this.stagingSession.refreshSessionFiles(sessionId);
    }
  }

  private forceStageAllWithDuplicates(context: DuplicateStageContext) {
    const filters = context.filters || this.getEffectiveDiscoveryFilters();
    if (!filters) {
      this.toast.error('Cannot force stage-all: discovery filters are missing. Please run preview again.');
      return;
    }

    this.staging.set(true);
    this.stageAllMode.set(true);

    const body: any = {
      ...filters,
      senderId: context.senderId,
      senderName: this.getSelectedSenderName(),
      triggerDispatch: true,
      forceDuplicates: true,
      requestId: context.sessionId,
      maxRows: 100000,
      bypassCap: true,
      discoveryToken: this.discoveryToken(),
    };

    this.backend.stageAll(context.senderId, body).subscribe({
      next: (response: any) => {
        const stagedCount = response?.staged ?? 0;
        const duplicateCount = response?.duplicates ?? 0;

        this.completeStep(1);
        this.currentStep.set(2);
        this.staging.set(false);
        this.stageAllMode.set(false);

        this.selectedRows.set(new Set());
        this.selectedRowLookup.set(new Map());
        this.stagedPreviewRows.set([]);

        this.toast.success(
          `Stage-all completed with force duplicates: ${stagedCount} staged, ${duplicateCount} duplicate record${duplicateCount === 1 ? '' : 's'} reported.`,
          7000,
        );

        this.startMonitoring();
      },
      error: (err: any) => {
        console.error('[STAGING] Force stage-all with duplicates failed:', err);
        this.staging.set(false);
        this.stageAllMode.set(false);
        const errorMsg = err?.error?.message || err?.statusText || 'Failed to include duplicates for stage-all';
        this.toast.error(`Stage-all force include failed: ${errorMsg}`, 7000);
      },
    });
  }

  private forceStageDuplicatePayloads(
    context: DuplicateStageContext,
    duplicatePayloads: Array<{
      metadataId: string;
      dataId?: string | null;
      lot?: string | null;
      wafer?: string | null;
      filename?: string | null;
      endTime?: string | null;
    }>,
  ) {
    if (duplicatePayloads.length === 0) {
      this.toast.warning('No duplicate payload details available to force include.');
      return;
    }

    this.staging.set(true);
    if (context.mode === 'all') {
      this.stageAllMode.set(true);
    }

    const resolvedEnvironment = this.selectedEnv() ? this.selectedEnv()!.toLowerCase() : 'qa';
    const body: any = {
      site: context.site,
      environment: resolvedEnvironment,
      senderId: context.senderId,
      senderName: this.getSelectedSenderName(),
      payloads: duplicatePayloads,
      triggerDispatch: true,
      forceDuplicates: true,
      requestId: context.sessionId,
      dataType: context.filters?.dataType || this.selectedDataType() || null,
      testPhase: context.filters?.testPhase || this.selectedTestPhase() || null,
    };

    this.backend.stagePayloads(context.senderId, body).subscribe({
      next: (response: any) => {
        const includedDuplicates = response?.staged ?? duplicatePayloads.length;
        const priorStaged = context.stagedCount || 0;
        const totalStaged = priorStaged + includedDuplicates;

        this.completeStep(1);
        this.currentStep.set(2);
        this.staging.set(false);
        this.stageAllMode.set(false);

        this.toast.success(
          `Included ${includedDuplicates} duplicate payload${includedDuplicates === 1 ? '' : 's'}. ` +
            `Total staged in this operation: ${totalStaged}.`,
          6000,
        );
        this.startMonitoring();
      },
      error: (err: any) => {
        console.error('[STAGING] Force include duplicates failed:', err);
        this.staging.set(false);
        this.stageAllMode.set(false);
        const errorMsg = err?.error?.message || err?.statusText || 'Failed to include duplicates';
        this.toast.error(`Include duplicates failed: ${errorMsg}`, 7000);
      },
    });
  }

  // Stepper navigation methods
  onStepChange(index: number) {
    this.currentStep.set(index);

    // Clear verification summary when navigating back to step 0 (Configuration)
    if (index === 0) {
      this.verificationSummary.set(null);
    }

    // Start monitoring when entering step 3
    if (index === 2) {
      this.startMonitoring();
    }
  }

  completeStep(index: number) {
    if (index >= 0 && index < this.steps.length) {
      this.steps[index].completed = true;
      if (index + 1 < this.steps.length) {
        this.steps[index + 1].editable = true;
      }
    }
  }

  // Monitoring methods
  requestId = signal<string | null>(null);
  private activeMonitoringSessionId = signal<string | null>(null);
  private monitoringStartPending = signal(false);
  // Prevent duplicate completion notifications
  private sessionCompletedNotified = signal(false);

  startMonitoring() {
    const sessionId = this.requestId();
    console.log('[STEPPER] startMonitoring called, sessionId:', sessionId);

    if (!sessionId) {
      console.warn('[STEPPER] No sessionId, cannot start monitoring');
      this.toast.warning('Cannot start monitoring: session is missing');
      return;
    }

    if (
      this.activeMonitoringSessionId() === sessionId &&
      (this.stagingSession.isConnected() || this.monitoringStartPending())
    ) {
      console.log('[STEPPER] Already monitoring this session');
      return;
    }

    console.log('[STEPPER] Starting monitoring for session:', sessionId);
    this.monitoringStopped.set(false);
    this.persistMonitoringSession(sessionId);
    this.activeMonitoringSessionId.set(sessionId);
    this.monitoringStartPending.set(true);

    // Use setTimeout to prevent blocking
    setTimeout(() => {
      this.monitoring.reset();
      this.stagingSession.connectToSession(sessionId);
      this.sessionCompletedNotified.set(false);
      this.monitoringStartPending.set(false);
    }, 0);
  }

  stopMonitoring(clearPersistedSession: boolean = false, markStopped: boolean = true) {
    this.clearWarmupTimeout();
    this.warmupTimeoutReached.set(false);
    if (markStopped) {
      this.monitoringStopped.set(true);
    }
    this.monitoring.stopMonitoring();
    this.stagingSession.disconnectSession();
    this.activeMonitoringSessionId.set(null);
    this.monitoringStartPending.set(false);
    if (clearPersistedSession) {
      this.clearPersistedMonitoringSession();
    }
  }

  reconnectMonitoring() {
    const sessionId = this.requestId();
    if (!sessionId) {
      this.toast.warning('No active session to reconnect');
      return;
    }
    this.monitoringStopped.set(false);
    this.toast.info('Attempting to reconnect to live stream...', 3000);
    this.stagingSession.connectToSession(sessionId);
  }

  refreshMonitoring() {
    const sessionId = this.requestId();
    if (!sessionId) {
      this.toast.warning('No active session to refresh');
      return;
    }
    this.toast.info('Refreshing session data...');
    this.stagingSession.refreshSession(sessionId);
    // Force a files refresh as well (size is centralized in service)
    this.stagingSession.refreshSessionFiles(sessionId);
  }

  onExportCsvClick() {
    const files = this.stagingSession.sessionFiles().map(
      (item: StageRecordView) =>
        ({
          id: item.id || 0,
          metadataId: item.metadataId ?? undefined,
          dataId: item.dataId ?? undefined,
          filename: item.filename || '',
          lot: item.lot || '',
          wafer: item.wafer || '',
          status: this.mapBackendStatus(item.status),
          message: item.status || '',
          errorMessage: item.errorMessage ?? undefined,
          updatedAt: item.updatedAt ?? item.updated ?? undefined,
        }) as MonitoringFile,
    );
    this.monitoring.updateFiles(files);

    const sid = this.requestId() || undefined;
    const started = this.monitoring.exportFilesAsCsv(sid, this.sessionSummary().username ?? undefined);
    if (!started) {
      this.toast.info('No files available to export');
      return;
    }
    this.toast.success('Session export started');
  }

  onFinishAndReturnToHub() {
    this.router.navigate(['/']);
    setTimeout(() => this.stopMonitoring(true, false), 0);
  }

  ngOnDestroy() {
    this.clearWarmupTimeout();
    this.stopMonitoring(false, false);
    this.stagingSession.disconnectSession();
    this.senderLookupSubscription?.unsubscribe();
    this.userRolesSubscription?.unsubscribe();
  }

  /** Holds a resumable session found on init — shown as a prompt in Step 1 */
  resumableSession = signal<{ sessionId: string; senderLabel: string; site: string } | null>(null);

  private tryRestoreMonitoringSession() {
    const persistedId = this.getPersistedMonitoringSession();
    if (!persistedId) {
      return;
    }

    // If navigated here with ?resume=1 (from dashboard "Resume Monitoring"), go straight to Step 3
    const autoResume = this.route.snapshot.queryParamMap.get('resume') === '1';

    this.backend.getStagingSession(persistedId).subscribe({
      next: (session: any) => {
        const status = (session?.status || '').toUpperCase();

        // Clear if terminal
        if (['COMPLETED', 'PARTIALLY_FAILED', 'CANCELLED'].includes(status)) {
          this.clearPersistedMonitoringSession();
          return;
        }

        // Clear if stale: >30 min old with 0 files — effectively abandoned
        const lastChecked = session?.lastCheckedAt ? new Date(session.lastCheckedAt).getTime() : 0;
        const ageMs = Date.now() - lastChecked;
        if (ageMs > 30 * 60 * 1000 && (session?.totalFiles ?? 0) === 0) {
          this.clearPersistedMonitoringSession();
          this.backend.cancelStagingSession(persistedId).subscribe({ error: () => {} });
          return;
        }

        if (autoResume) {
          // Dashboard "Resume Monitoring" clicked — go straight to Step 3
          this.requestId.set(persistedId);
          this.steps[0].completed = true;
          this.steps[1].completed = true;
          this.steps[2].editable = true;
          this.currentStep.set(2);
          setTimeout(() => this.startMonitoring(), 0);
        } else {
          // Show the resume banner in Step 1 — user decides
          this.resumableSession.set({
            sessionId: persistedId,
            senderLabel: session?.senderName || `Sender ${session?.senderId}`,
            site: session?.site || '',
          });
        }
      },
      error: () => {
        this.clearPersistedMonitoringSession();
      },
    });
  }

  resumePersistedSession() {
    const s = this.resumableSession();
    if (!s) return;
    this.resumableSession.set(null);
    this.requestId.set(s.sessionId);
    this.steps[0].completed = true;
    this.steps[1].completed = true;
    this.steps[2].editable = true;
    this.currentStep.set(2);
    setTimeout(() => this.startMonitoring(), 0);
  }

  dismissResumePrompt() {
    const s = this.resumableSession();
    this.clearPersistedMonitoringSession();
    this.resumableSession.set(null);
    // Cancel the session in the DB so it doesn't stay stuck as STAGING forever
    if (s?.sessionId) {
      this.backend.cancelStagingSession(s.sessionId).subscribe({ error: () => {} });
    }
  }

  private persistMonitoringSession(sessionId: string) {
    try {
      localStorage.setItem(this.monitoringResumeStorageKey, sessionId);
    } catch {
      // no-op
    }
  }

  private getPersistedMonitoringSession(): string | null {
    try {
      return localStorage.getItem(this.monitoringResumeStorageKey);
    } catch {
      return null;
    }
  }

  private clearPersistedMonitoringSession() {
    try {
      localStorage.removeItem(this.monitoringResumeStorageKey);
    } catch {
      // no-op
    }
  }

  private clearWarmupTimeout(): void {
    if (this.warmupTimeoutHandle) {
      clearTimeout(this.warmupTimeoutHandle);
      this.warmupTimeoutHandle = null;
    }
  }

  private formatETA(minutes: number): string {
    if (minutes < 1) return '< 1 minute';
    if (minutes < 60) return `${Math.round(minutes)} minutes`;
    const hours = Math.floor(minutes / 60);
    const mins = Math.round(minutes % 60);
    return `${hours}h ${mins}m`;
  }

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

  private mapBackendStatus(status: string): MonitoringFile['status'] {
    const normalized = (status || '').toUpperCase();
    if (normalized === 'DONE' || normalized === 'COMPLETED') return 'COMPLETED';
    if (normalized === 'ELASTICSEARCH_MONITORING' || normalized === 'DISPATCHING') return 'ELASTICSEARCH_MONITORING';
    if (normalized === 'EXENSIO_MONITORING') return 'EXENSIO_MONITORING';
    if (normalized === 'PROCESSING' || normalized === 'QUEUED_FOR_CP') return 'ELASTICSEARCH_MONITORING'; // legacy compat
    if (normalized === 'FAILED' || normalized === 'ERROR') return 'ERROR';
    return 'READY';
  }

  private expandMonitoringRows(
    selectedRows: DiscoveryPreviewRow[],
    allRows: DiscoveryPreviewRow[],
  ): DiscoveryPreviewRow[] {
    if (selectedRows.length === 0) {
      return [];
    }

    const keys = new Set<string>();
    const expanded: DiscoveryPreviewRow[] = [];

    const pushUnique = (row: DiscoveryPreviewRow) => {
      const key = this.getRowKey(row);
      if (!keys.has(key)) {
        keys.add(key);
        expanded.push(row);
      }
    };

    // Always include the explicit user selection.
    selectedRows.forEach(pushUnique);

    // Expand to sibling wafers with same lot+filename (common PCM single-file shape).
    const selectedFamilies = new Set(
      selectedRows.map(
        (row) => `${String(row.lot || '').trim()}::${String(this.getPreviewFilename(row) || '').trim()}`,
      ),
    );

    allRows.forEach((row) => {
      const family = `${String(row.lot || '').trim()}::${String(this.getPreviewFilename(row) || '').trim()}`;
      if (selectedFamilies.has(family)) {
        pushUnique(row);
      }
    });

    return expanded;
  }

  /**
   * Reset sender state to initial values
   */
  private resetSenderState() {
    this.senderOptions.set([]);
    this.selectedSenderId.set(null);
    this.senderAutoResolved.set(false);
    this.senderFallback.set(false);
    this.senderLookupQuery.set(null);
  }

  /**
   * Manual trigger for sender lookup (called by "Find Sender" button)
   */
  onFindSenderClick() {
    if (!this.selectedSite() || !this.selectedLocation() || !this.selectedDataType()) {
      this.toast.warning('Please select Site, Location, and Data Type first');
      return;
    }

    // Trigger immediate lookup
    this.senderLookupSubject.next();
  }

  /**
   * Perform historical sender lookup using HIST regex pattern matching
   */
  private performHistoricalSenderLookup() {
    if (!this.selectedSite() || !this.selectedDataType()) {
      return of(null);
    }

    this.senderLookupLoading.set(true);
    this.senderAutoResolved.set(false);
    this.senderFallback.set(false);

    const params: Record<string, any> = {
      // historical sender endpoint requires connectionKey or locationId
      // keep site for backward compatibility and provide connectionKey explicitly
      site: this.selectedSite(),
      connectionKey: this.selectedSite(),
      environment: this.selectedEnv() ? this.selectedEnv()!.toLowerCase() : 'qa',
      dataType: this.selectedDataType(),
    };

    return this.backend.getHistoricalSenders(params).pipe(
      map((candidates: SenderOption[]) => {
        this.senderLookupLoading.set(false);

        // Enforce env boundary — filter out senders belonging to the opposite env
        const env = this.selectedEnv();
        const oppositeSuffix = env === 'PROD' ? '_QA' : '_PROD';
        const filtered = (candidates || []).filter(
          (s: SenderOption) => !(s.name || '').toUpperCase().endsWith(oppositeSuffix),
        );

        if (filtered.length === 1) {
          // Single match - auto-resolve
          this.senderOptions.set(filtered);
          this.selectedSenderId.set(filtered[0].idSender ?? null);
          this.senderAutoResolved.set(true);
          this.senderFallback.set(false);
          this.toast.success('Sender auto-resolved using historical pattern');
        } else if (filtered.length > 1) {
          // Multiple matches - show dropdown
          this.senderOptions.set(filtered);
          this.selectedSenderId.set(null);
          this.senderAutoResolved.set(false);
          this.senderFallback.set(true);
          this.toast.info(`Found ${filtered.length} matching senders - please select one`);
        } else {
          // No matches
          this.senderOptions.set([]);
          this.selectedSenderId.set(null);
          this.senderAutoResolved.set(false);
          this.senderFallback.set(true);
          this.toast.warning(
            'No historical sender matched. Verify a HIST sender exists in Dataport for this data type/env.',
          );
        }

        return null;
      }),
      catchError((err: any) => {
        console.error('Historical sender lookup failed:', err);
        this.senderLookupLoading.set(false);
        this.toast.error('Historical sender lookup failed');
        this.resetSenderState();
        return of(null);
      }),
    );
  }

  /**
   * Perform normal sender lookup using location-based filtering.
   * Uses the /senders/lookup endpoint which has smart auto-resolution logic.
   */
  private performNormalSenderLookup() {
    if (!this.selectedSite() || !this.selectedLocation() || !this.selectedDataType()) {
      return of(null);
    }

    this.senderLookupLoading.set(true);
    this.senderAutoResolved.set(false);
    this.senderFallback.set(false);

    const params: Record<string, any> = {
      connectionKey: this.selectedSite(),
      environment: this.selectedEnv() ? this.selectedEnv()!.toLowerCase() : 'qa',
      metadataLocation: this.selectedLocation(), // Use metadataLocation (preferred by backend)
      dataType: this.selectedDataType(),
    };

    // Add optional filters if selected
    const testerType = this.normalizeTesterType(this.selectedTesterType());
    if (testerType) {
      params['testerType'] = testerType;
    }
    if (this.selectedDataTypeExt()) {
      params['dataTypeExt'] = this.selectedDataTypeExt();
    }
    if (this.selectedTestPhase()) {
      params['testPhase'] = this.selectedTestPhase();
    }

    // Use lookupSenders() which has smart auto-resolution logic in the backend
    return this.backend.lookupSenders(params).pipe(
      map((candidates: SenderOption[]) => {
        this.senderLookupLoading.set(false);

        // Filter out invalid senders and enforce env boundary
        const env = this.selectedEnv();
        const oppositeSuffix = env === 'PROD' ? '_QA' : '_PROD';
        const validCandidates = (candidates || []).filter((s: SenderOption) => {
          if (!s || s.idSender == null) return false;
          return !(s.name || '').toUpperCase().endsWith(oppositeSuffix);
        });

        // Count unique sender IDs (backend may return multiple rows for same sender)
        const uniqueIds = new Set(validCandidates.map((s: SenderOption) => s.idSender));

        if (uniqueIds.size === 1) {
          // Single unique sender ID - auto-resolve
          // Backend already did the smart filtering, so this is the correct sender
          this.senderOptions.set(validCandidates);
          this.selectedSenderId.set(validCandidates[0].idSender ?? null);
          this.senderAutoResolved.set(true);
          this.senderFallback.set(false);
          this.toast.success('Sender auto-resolved');
        } else if (uniqueIds.size > 1) {
          // Multiple unique sender IDs - show dropdown
          // Backend returned multiple candidates because it couldn't uniquely resolve
          this.senderOptions.set(validCandidates);
          this.selectedSenderId.set(null);
          this.senderAutoResolved.set(false);
          this.senderFallback.set(true);
          this.toast.info(`Found ${uniqueIds.size} matching senders - please select one`);
        } else {
          // No matches - this shouldn't happen with lookupSenders (it falls back internally)
          // but handle it gracefully
          this.senderOptions.set([]);
          this.selectedSenderId.set(null);
          this.senderAutoResolved.set(false);
          this.senderFallback.set(true);
          this.toast.warning('No senders found for the selected filters');
        }

        return null;
      }),
      catchError((err: any) => {
        console.error('Sender lookup failed:', err);
        this.senderLookupLoading.set(false);
        this.toast.error('Sender lookup failed');
        this.resetSenderState();
        return of(null);
      }),
    );
  }

  /**
   * Handle manual sender selection from dropdown
   */
  onSenderSelected(senderId: number | null) {
    this.selectedSenderId.set(senderId);
    // Manual selection overrides auto-resolution
    this.senderAutoResolved.set(false);

    if (senderId) {
      const sender = this.senderOptions().find((s: SenderOption) => s.idSender === senderId);
      if (sender) {
        this.toast.success(`Selected sender: ${sender.name || senderId}`);
      }
    }
  }

  /**
   * Get the name of the currently selected sender
   */
  getSelectedSenderName(): string | null {
    const senderId = this.selectedSenderId();
    if (!senderId) return null;

    const sender = this.senderOptions().find((s: SenderOption) => s.idSender === senderId);
    return sender?.name || null;
  }

  /**
   * Helper to get senderId for API calls
   */
  private getSenderIdForRequest(): number | null {
    return this.selectedSenderId();
  }

  /**
   * Task 6.1: Pre-flight lot verification before discovery
   * Task 11: Date range filtering support
   *
   * Extracts unique lots from lotWaferPairs signal, verifies them against Exensio,
   * displays results in dialog, and returns filtered lots based on user action.
   *
   * When date range is provided (historical mode), extracts year and month from
   * dateRange signal and creates PreCheckBlock entries to filter lots by end_time.
   *
   * Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 9.1, 10.1, 10.2, 10.3, 10.5, 10.6
   */
  private async verifyLotsBeforeDiscovery(): Promise<string[] | null> {
    // Extract unique lots from lotWaferPairs
    const lots = Array.from(
      new Set(
        this.lotWaferPairs()
          .map((pair) => pair.lot?.trim())
          .filter((lot) => lot && lot.length > 0),
      ),
    );

    if (lots.length === 0) {
      // No lots to verify - proceed immediately (date range only query)
      // Requirements: 1.5 - If no lots are provided (date range only query), skip verification
      return [];
    }

    // Get sender ID for the API call
    const senderId = this.getSenderIdForRequest();
    if (!senderId) {
      this.toast.error('Sender is required for verification');
      return null;
    }

    // Task 9.1: Set previewLoading(true) before verification call
    // Display message: "Verifying lots in Exensio..."
    // Show spinner animation (existing loading overlay component)
    this.previewLoading.set(true);

    try {
      // Task 11: Extract date range if provided (historical mode with date range)
      const dateRangeData = this.dateRange();
      let appliedDateRange: { start: Date; end: Date } | null = null;
      const preCheckBlocks: Array<{ year: number; month: number }> = [];

      if (dateRangeData?.start && dateRangeData?.end) {
        const startDate = new Date(dateRangeData.start);
        const endDate = new Date(dateRangeData.end);
        appliedDateRange = { start: startDate, end: endDate };

        // Extract year and month from dateRange to create PreCheckBlock entries
        // Requirements: 10.3, 10.6 - Extract year and month from date range

        // Generate all month/year combinations between start and end dates (inclusive)
        const current = new Date(startDate);
        while (current <= endDate) {
          preCheckBlocks.push({
            year: current.getFullYear(),
            month: current.getMonth() + 1, // Month is 1-12 in PreCheckBlock format
          });
          current.setMonth(current.getMonth() + 1);
        }

        console.log('Task 11: Date range filtering - PreCheckBlocks:', preCheckBlocks);
      }

      // Call backend to verify lots with optional date range filtering
      // Requirements: 2.1, 2.2, 2.3, 2.4 - Use raw-SQL endpoint with PGC_KEY filter
      const result = await firstValueFrom(
        this.backend.verifyLotsExistenceWithDateRange(
          senderId,
          lots,
          this.selectedDataType() || 'ft',
          preCheckBlocks.length > 0 ? preCheckBlocks : null,
        ),
      );

      // Task 9.1: Set previewLoading(false) after verification completes
      this.previewLoading.set(false);

      // Requirements: 1.3 - Display verification dialog showing results
      // Open verification dialog with GlassDialogService
      const verificationMap = new Map<string, boolean>();
      if (result.lotExists instanceof Map) {
        result.lotExists.forEach((value: boolean, key: string) => {
          verificationMap.set(key, value);
        });
      } else {
        Object.entries(result.lotExists).forEach(([key, value]: [string, any]) => {
          verificationMap.set(key, Boolean(value));
        });
      }

      // Count found and not found lots for summary
      let foundCount = 0;
      let notFoundCount = 0;
      verificationMap.forEach((found: boolean) => {
        if (found) foundCount++;
        else notFoundCount++;
      });

      const dialogRef = this.dialog.open<
        LotVerificationDialogComponent,
        LotVerificationDialogData,
        LotVerificationDialogResult
      >(LotVerificationDialogComponent, {
        data: {
          lots,
          verificationResult: verificationMap,
          verifiedAt: new Date(),
          appliedDateRange,
        } as LotVerificationDialogData,
        disableClose: false,
        panelClass: 'glass-dialog',
        backdropClass: 'glass-backdrop',
      });

      const dialogResult = await dialogRef.afterClosed();

      if (!dialogResult || dialogResult.action === 'cancel') {
        // User cancelled
        return null;
      }

      if (dialogResult.action === 'all') {
        // Requirements: 1.4 - Execute discovery with all originally input lots
        // Task 6 & 8: Store verification summary for banner display
        this.verificationSummary.set({
          choice: 'all',
          totalLots: lots.length,
          foundCount,
          notFoundCount,
        });
        return lots;
      }

      if (dialogResult.action === 'not-found') {
        // Requirements: 1.4 - Execute discovery with only non-existent lots
        // Task 6 & 8: Store verification summary for banner display
        this.verificationSummary.set({
          choice: 'not-found',
          totalLots: lots.length,
          foundCount,
          notFoundCount,
        });
        return dialogResult.filteredLots || [];
      }

      return null;
    } catch (error) {
      // Task 9.1: Set previewLoading(false) after verification fails
      this.previewLoading.set(false);
      console.error('Lot verification failed:', error);

      // Show error with option to skip verification
      // Requirements: 8.3, 8.4 - Error handling with confirmation
      const proceed = await this.confirmSkipVerification(error);
      return proceed ? lots : null;
    }
  }

  /**
   * Task 8.3, 8.4, 8.5: Error handler for verification failures
   * Display error message and show confirm dialog to skip verification
   *
   * Requirements: 8.3, 8.4, 8.5
   */
  private async confirmSkipVerification(error: any): Promise<boolean> {
    const errorMsg = error?.error?.message || error?.statusText || 'Verification failed';
    const message = `Lot verification failed: ${errorMsg}\n\nWould you like to skip verification and continue with discovery?`;

    return window.confirm(message);
  }
}
