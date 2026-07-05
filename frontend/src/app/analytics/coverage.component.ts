import {
    ChangeDetectionStrategy, Component, ElementRef, OnDestroy, OnInit,
    ViewChild, computed, effect, signal
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterModule } from '@angular/router';
import { BackendService, CoveragePoint, DashboardSnapshot, DashboardSiteSnapshot } from '../api/backend.service';
import { AuthService } from '../auth/auth.service';
import { SiteNamePipe, formatSiteName } from '../shared/pipes/site-name.pipe';
import { GlassDeviceFilterComponent } from '../shared/components/glass-device-filter.component';
import { GlassSelectComponent, GlassOption } from '../shared/components/glass-select.component';
import { GlassDatepickerComponent } from '../shared/components/glass-datepicker.component';
import { GlassButtonComponent } from '../shared/components/glass-button.component';
import * as echarts from 'echarts';
import type { ECharts } from 'echarts';

type Granularity = 'day' | 'week' | 'month';

interface SenderOption { id: number; label: string; }

@Component({
    selector: 'app-coverage',
    standalone: true,
    imports: [
        CommonModule, FormsModule, RouterModule, SiteNamePipe,
        GlassDeviceFilterComponent, GlassSelectComponent,
        GlassDatepickerComponent, GlassButtonComponent
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
<div class="cov-shell">
  <!-- Sub-nav shared with analytics -->
  <nav class="analytics-subnav">
    <a routerLink="/analytics" routerLinkActive="active" [routerLinkActiveOptions]="{exact:true}" class="subnav-link">Live Analytics</a>
    <a routerLink="/analytics/coverage" routerLinkActive="active" [routerLinkActiveOptions]="{exact:true}" class="subnav-link">Data Coverage</a>
  </nav>

  <header class="cov-header">
    <div>
      <h1>Data <span class="accent">Coverage</span></h1>
      <p class="subtitle">Files resent per sender, grouped by data end-time — across all sessions</p>
    </div>
  </header>

  <!-- Filters -->
  <section class="cov-filters glass-panel">
    <div class="filter-grid">
      <!-- Environment (admin only) -->
      <app-glass-select
        *ngIf="authService.isAdminSignal()"
        label="Environment"
        prefixIcon="public"
        [options]="['PROD', 'QA']"
        [ngModel]="selectedEnv()"
        (ngModelChange)="onEnvChange($event)"
      ></app-glass-select>

      <!-- Site -->
      <app-glass-select
        label="Site"
        prefixIcon="location_on"
        placeholder="Select site"
        [options]="siteOptions()"
        [(ngModel)]="selectedSite"
        (ngModelChange)="onSiteChange($event)"
      ></app-glass-select>

      <!-- Sender -->
      <app-glass-select
        label="Sender"
        prefixIcon="send"
        placeholder="All senders"
        [options]="glassSenderOptions()"
        [(ngModel)]="selectedSenderId"
      ></app-glass-select>

      <!-- Device -->
      <app-glass-device-filter
        label="Device"
        placeholder="All devices"
        (deviceChange)="onDeviceFilterChange($event)"
      ></app-glass-device-filter>

      <!-- Granularity -->
      <app-glass-select
        label="Granularity"
        prefixIcon="timeline"
        [options]="[
          { value: 'day', label: 'Day' },
          { value: 'week', label: 'Week' },
          { value: 'month', label: 'Month' }
        ]"
        [(ngModel)]="granularity"
      ></app-glass-select>

      <!-- End-Time From -->
      <app-glass-datepicker
        label="End-Time From"
        [includeTime]="false"
        [(ngModel)]="endTimeFrom"
      ></app-glass-datepicker>

      <!-- End-Time To -->
      <app-glass-datepicker
        label="End-Time To"
        [includeTime]="false"
        [(ngModel)]="endTimeTo"
      ></app-glass-datepicker>
    </div>

    <!-- Actions -->
    <div class="filter-actions">
      <app-glass-button variant="secondary" (clicked)="reset()">Reset</app-glass-button>
      <app-glass-button
        variant="secondary"
        [disabled]="points().length === 0"
        (clicked)="exportCsv()"
      >Export CSV</app-glass-button>
      <app-glass-button
        variant="primary"
        [disabled]="!selectedSite"
        [loading]="loading()"
        (clicked)="load()"
      >Run Report</app-glass-button>
    </div>
  </section>

  <!-- KPI strip -->
  <section class="kpi-strip" *ngIf="points().length > 0">
    <div class="kpi-card">
      <span class="kpi-label">Total Files</span>
      <span class="kpi-value">{{ kpis().total | number }}</span>
    </div>
    <div class="kpi-card kpi-done">
      <span class="kpi-label">Done</span>
      <span class="kpi-value">{{ kpis().done | number }}</span>
    </div>
    <div class="kpi-card kpi-enqueued">
      <span class="kpi-label">Enqueued</span>
      <span class="kpi-value">{{ kpis().enqueued | number }}</span>
    </div>
    <div class="kpi-card kpi-staged">
      <span class="kpi-label">Staged</span>
      <span class="kpi-value">{{ kpis().staged | number }}</span>
    </div>
    <div class="kpi-card" [class.kpi-failed]="kpis().failed > 0">
      <span class="kpi-label">Failed</span>
      <span class="kpi-value">{{ kpis().failed | number }}</span>
    </div>
    <div class="kpi-card kpi-rate">
      <span class="kpi-label">Completion Rate</span>
      <span class="kpi-value">{{ kpis().rate }}%</span>
    </div>
  </section>

  <!-- Chart -->
  <section class="chart-card glass-panel" *ngIf="points().length > 0">
    <div class="chart-header">
      <h2>Files by End-Time {{ granularity | titlecase }}</h2>
      <span>Stacked by status across {{ buckets().length }} {{ granularity }} bucket{{ buckets().length !== 1 ? 's' : '' }}</span>
    </div>
    <div #chartRef class="chart-host"></div>
  </section>

  <!-- Table -->
  <section class="table-card glass-panel" *ngIf="points().length > 0">
    <div class="table-header-row">
      <h3>Detail Table</h3>
      <label class="filter-label inline">
        <input class="cov-input cov-input--search" type="text" placeholder="Filter bucket or sender…" [(ngModel)]="tableSearch" />
      </label>
    </div>
    <div class="table-scroll">
      <table class="cov-table">
        <thead>
          <tr>
            <th (click)="sortBy('bucket')" class="sortable">End-Time Bucket <span class="sort-icon">{{ sortIcon('bucket') }}</span></th>
            <th (click)="sortBy('senderId')" class="sortable">Sender <span class="sort-icon">{{ sortIcon('senderId') }}</span></th>
            <th (click)="sortBy('site')" class="sortable">Site <span class="sort-icon">{{ sortIcon('site') }}</span></th>
            <th (click)="sortBy('total')" class="sortable num">Total <span class="sort-icon">{{ sortIcon('total') }}</span></th>
            <th (click)="sortBy('done')" class="sortable num">Done <span class="sort-icon">{{ sortIcon('done') }}</span></th>
            <th (click)="sortBy('enqueued')" class="sortable num">Enqueued <span class="sort-icon">{{ sortIcon('enqueued') }}</span></th>
            <th (click)="sortBy('staged')" class="sortable num">Staged <span class="sort-icon">{{ sortIcon('staged') }}</span></th>
            <th (click)="sortBy('failed')" class="sortable num">Failed <span class="sort-icon">{{ sortIcon('failed') }}</span></th>
            <th class="num">Rate</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let row of tableRows()">
            <td class="mono">{{ row.bucket }}</td>
            <td>{{ row.senderId }}</td>
            <td>{{ row.site | siteName }}</td>
            <td class="num">{{ row.total | number }}</td>
            <td class="num done-cell">{{ row.done | number }}</td>
            <td class="num enq-cell">{{ row.enqueued | number }}</td>
            <td class="num stg-cell">{{ row.staged | number }}</td>
            <td class="num" [class.fail-cell]="row.failed > 0">{{ row.failed | number }}</td>
            <td class="num">
              <span class="rate-pill" [class.rate-full]="row.done === row.total" [class.rate-partial]="row.done > 0 && row.done < row.total" [class.rate-zero]="row.done === 0">
                {{ row.total > 0 ? ((row.done / row.total) * 100 | number:'1.0-0') : 0 }}%
              </span>
            </td>
          </tr>
          <tr *ngIf="tableRows().length === 0">
            <td colspan="9" class="empty-cell">No data matches your filter.</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>

  <!-- Empty / error states -->
  <section class="empty-state glass-panel" *ngIf="!loading() && points().length === 0 && hasQueried()">
    <span class="material-icons">bar_chart</span>
    <p>No coverage data found for the selected filters.</p>
  </section>

  <section class="empty-state glass-panel" *ngIf="!loading() && !hasQueried()">
    <span class="material-icons">insights</span>
    <p>Select a site and click <strong>Run Report</strong> to view data coverage.</p>
  </section>

  <section class="error-state glass-panel" *ngIf="error()">
    <span class="material-icons">error_outline</span>
    <p>{{ error() }}</p>
    <button class="cov-btn cov-btn--secondary" (click)="load()">Retry</button>
  </section>
</div>
    `,
    styles: [`
    .cov-shell { display:flex; flex-direction:column; gap:1.1rem; }
    .analytics-subnav { display:flex; gap:0.25rem; border-bottom:1px solid rgba(167,139,250,0.18); padding-bottom:0.6rem; }
    .subnav-link { padding:0.35rem 0.85rem; border-radius:8px; font-size:0.84rem; font-weight:600; color:rgba(203,213,225,0.7); text-decoration:none; transition:all 0.18s ease; }
    .subnav-link:hover { color:rgba(226,232,255,0.95); background:rgba(129,140,248,0.1); }
    .subnav-link.active { color:#a5b4fc; background:rgba(129,140,248,0.15); }
    .cov-header { display:flex; justify-content:space-between; align-items:flex-start; }
    .cov-header h1 { margin:0; font-size:1.1rem; font-weight:700; letter-spacing:-0.01em; }
    .accent { color:#a78bfa; }
    .subtitle { margin:0.15rem 0 0; color:var(--text-muted); font-size:0.82rem; }
    .cov-filters { padding:1.25rem; border-radius:14px; }
    .filter-grid {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      gap: 0.75rem;
      align-items: end;
    }
    @media (max-width: 1200px) {
      .filter-grid { grid-template-columns: repeat(3, 1fr); }
    }
    @media (max-width: 860px) {
      .filter-grid { grid-template-columns: repeat(2, 1fr); }
    }
    @media (max-width: 560px) {
      .filter-grid { grid-template-columns: 1fr; }
    }
    .filter-grid app-glass-select,
    .filter-grid app-glass-datepicker,
    .filter-grid app-glass-device-filter { width: 100%; }
    .filter-actions {
      display: flex;
      justify-content: flex-end;
      align-items: center;
      gap: 0.75rem;
      margin-top: 1.25rem;
      padding-top: 1.25rem;
      border-top: 1px solid rgba(167, 139, 250, 0.15);
    }
    .filter-label { display:flex; flex-direction:column; gap:0.3rem; font-size:0.74rem; font-weight:600; color:rgba(167,139,250,0.85); text-transform:uppercase; letter-spacing:0.05em; }
    .filter-label.inline { flex-direction:row; align-items:center; gap:0.5rem; text-transform:none; font-size:0.82rem; }
    .cov-select, .cov-input { height:34px; border-radius:9px; border:1px solid rgba(167,139,250,0.26); background:rgba(20,16,44,0.65) url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='10' height='6' viewBox='0 0 10 6'%3E%3Cpath d='M1 1l4 4 4-4' stroke='%23a78bfa' stroke-width='1.5' fill='none' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E") no-repeat right 0.55rem center; color:rgba(226,232,255,0.94); padding:0 2rem 0 0.65rem; font-size:0.82rem; appearance:none; -webkit-appearance:none; min-width:130px; }
    .cov-input { background-image:none; padding-right:0.65rem; cursor:text; }
    .cov-input--search { min-width:220px; }
    .cov-select:focus, .cov-input:focus { outline:none; border-color:rgba(167,139,250,0.55); box-shadow:0 0 0 2px rgba(129,140,248,0.15); }
    .cov-select option { background:#1a1240; }

    .cov-btn { height:34px; padding:0 1rem; border-radius:9px; border:1px solid transparent; font-size:0.82rem; font-weight:600; cursor:pointer; display:inline-flex; align-items:center; gap:0.4rem; transition:all 0.18s ease; }
    .cov-btn--primary { background:linear-gradient(135deg,rgba(99,102,241,0.85),rgba(139,92,246,0.85)); border-color:rgba(167,139,250,0.4); color:#fff; }
    .cov-btn--primary:hover:not(:disabled) { background:linear-gradient(135deg,rgba(99,102,241,1),rgba(139,92,246,1)); }
    .cov-btn--primary:disabled { opacity:0.45; cursor:not-allowed; }
    .cov-btn--secondary { background:rgba(67,56,132,0.4); border-color:rgba(167,139,250,0.26); color:rgba(226,232,255,0.9); }
    .cov-btn--secondary:hover:not(:disabled) { background:rgba(99,102,241,0.3); border-color:rgba(167,139,250,0.48); }
    .cov-btn--secondary:disabled { opacity:0.35; cursor:not-allowed; }
    .mini-spinner { width:14px; height:14px; border:2px solid rgba(255,255,255,0.3); border-top-color:#fff; border-radius:50%; animation:spin 0.7s linear infinite; }
    @keyframes spin { to { transform:rotate(360deg); } }
    .kpi-strip { display:grid; grid-template-columns:repeat(6,1fr); gap:0.65rem; }
    @media(max-width:900px) { .kpi-strip { grid-template-columns:repeat(3,1fr); } }
    .kpi-card { padding:0.75rem 1rem; border-radius:12px; border:1px solid rgba(167,139,250,0.18); background:rgba(22,16,52,0.55); display:flex; flex-direction:column; gap:0.25rem; }
    .kpi-label { font-size:0.7rem; font-weight:700; text-transform:uppercase; letter-spacing:0.06em; color:rgba(167,139,250,0.75); }
    .kpi-value { font-size:1.35rem; font-weight:800; color:rgba(226,232,255,0.95); }
    .kpi-done { border-color:rgba(16,185,129,0.28); } .kpi-done .kpi-value { color:#34d399; }
    .kpi-enqueued { border-color:rgba(245,158,11,0.28); } .kpi-enqueued .kpi-value { color:#fbbf24; }
    .kpi-staged { border-color:rgba(129,140,248,0.28); } .kpi-staged .kpi-value { color:#a5b4fc; }
    .kpi-failed { border-color:rgba(239,68,68,0.3); } .kpi-failed .kpi-value { color:#f87171; }
    .kpi-rate .kpi-value { color:#38bdf8; }
    .chart-card { border-radius:14px; border:1px solid rgba(167,139,250,0.18); background:rgba(15,23,42,0.58); padding:1rem; }
    .chart-header { display:flex; flex-direction:column; gap:0.15rem; margin-bottom:0.6rem; }
    .chart-header h2 { margin:0; font-size:1rem; font-weight:700; }
    .chart-header span { color:var(--text-muted); font-size:0.84rem; }
    .chart-host { width:100%; height:320px; }
    .table-card { border-radius:14px; border:1px solid rgba(167,139,250,0.18); background:rgba(15,23,42,0.55); padding:1rem; }
    .table-header-row { display:flex; align-items:center; justify-content:space-between; margin-bottom:0.75rem; flex-wrap:wrap; gap:0.5rem; }
    .table-header-row h3 { margin:0; font-size:0.95rem; font-weight:700; }
    .table-scroll { overflow-x:auto; }
    .cov-table { width:100%; border-collapse:collapse; font-size:0.82rem; }
    .cov-table th { padding:0.55rem 0.75rem; text-align:left; font-size:0.68rem; font-weight:700; text-transform:uppercase; letter-spacing:0.07em; color:rgba(167,139,250,0.8); background:rgba(35,25,75,0.9); border-bottom:1px solid rgba(167,139,250,0.18); white-space:nowrap; }
    .cov-table th.sortable { cursor:pointer; user-select:none; }
    .cov-table th.sortable:hover { color:#a5b4fc; }
    .sort-icon { font-size:0.7rem; opacity:0.6; }
    .cov-table td { padding:0.5rem 0.75rem; border-bottom:1px solid rgba(255,255,255,0.04); vertical-align:middle; }
    .cov-table tbody tr:hover { background:rgba(129,140,248,0.07); }
    .cov-table .num { text-align:right; }
    .cov-table .mono { font-family:'JetBrains Mono','Fira Code',monospace; font-size:0.78rem; color:rgba(165,180,252,0.9); }
    .done-cell { color:#34d399; font-weight:600; }
    .enq-cell { color:#fbbf24; }
    .stg-cell { color:#a5b4fc; }
    .fail-cell { color:#f87171; font-weight:600; }
    .rate-pill { padding:0.15rem 0.5rem; border-radius:999px; font-size:0.72rem; font-weight:700; }
    .rate-full { background:rgba(16,185,129,0.2); color:#34d399; }
    .rate-partial { background:rgba(245,158,11,0.18); color:#fbbf24; }
    .rate-zero { background:rgba(100,116,139,0.2); color:rgba(203,213,225,0.5); }
    .empty-cell { text-align:center; padding:2rem; color:rgba(203,213,225,0.45); }
    .empty-state, .error-state { display:flex; flex-direction:column; align-items:center; gap:0.75rem; padding:2.5rem; border-radius:14px; color:rgba(203,213,225,0.6); font-size:0.9rem; text-align:center; }
    .empty-state .material-icons, .error-state .material-icons { font-size:2.5rem; opacity:0.4; }
    .error-state { border-color:rgba(239,68,68,0.3) !important; }
    .error-state p { color:#f87171; }
    `]
})
export class CoverageComponent implements OnInit, OnDestroy {
    @ViewChild('chartRef') chartRef?: ElementRef<HTMLDivElement>;
    @ViewChild(GlassDeviceFilterComponent) deviceFilterComponent?: GlassDeviceFilterComponent;

    // filter state
    selectedSite = '';
    selectedSenderId: number | null = null;
    selectedDevices = signal<string[]>([]);
    granularity: Granularity = 'day';
    endTimeFrom = '';
    endTimeTo = '';
    tableSearch = '';
    sortCol = 'bucket';
    sortDir: 'asc' | 'desc' = 'asc';

    // environment selector (admin only — defaults to PROD)
    selectedEnv = signal<'PROD' | 'QA'>('PROD');

    // data state
    points = signal<CoveragePoint[]>([]);
    private allSites = signal<string[]>([]);
    senderOptions = signal<SenderOption[]>([]);
    loading = signal(false);
    error = signal<string | null>(null);
    hasQueried = signal(false);

    // sites filtered by selected environment
    sites = computed(() => {
        const env = this.selectedEnv();
        const suffix = env === 'PROD' ? '-PROD' : '-QA';
        return this.allSites().filter((s: string) => s.endsWith(suffix));
    });

    siteOptions = computed((): GlassOption[] => {
        const isAdmin = this.authService.isAdminSignal();
        return this.sites().map(site => ({
            value: site,
            label: formatSiteName(site, isAdmin)
        }));
    });

    glassSenderOptions = computed((): GlassOption[] => {
        const base = this.senderOptions().map(s => ({
            value: s.id,
            label: `${s.id}${s.label ? ' · ' + s.label : ''}`
        }));
        return [{ value: null, label: 'All Senders' }, ...base];
    });

    private chart?: ECharts;

    readonly buckets = computed(() => [...new Set(this.points().map((p: CoveragePoint) => p.bucket))].sort());

    readonly kpis = computed(() => {
        const pts = this.points();
        const total = pts.reduce((s: number, p: CoveragePoint) => s + p.total, 0);
        const done = pts.reduce((s: number, p: CoveragePoint) => s + p.done, 0);
        const enqueued = pts.reduce((s: number, p: CoveragePoint) => s + p.enqueued, 0);
        const staged = pts.reduce((s: number, p: CoveragePoint) => s + p.staged, 0);
        const failed = pts.reduce((s: number, p: CoveragePoint) => s + p.failed, 0);
        const rate = total > 0 ? Math.round((done / total) * 1000) / 10 : 0;
        return { total, done, enqueued, staged, failed, rate };
    });

    readonly tableRows = computed(() => {
        const q = this.tableSearch.toLowerCase();
        let rows = this.points().filter((p: CoveragePoint) =>
            !q || p.bucket.toLowerCase().includes(q) || String(p.senderId).includes(q) || p.site.toLowerCase().includes(q)
        );
        const col = this.sortCol as keyof CoveragePoint;
        rows = [...rows].sort((a, b) => {
            const av = a[col] ?? '';
            const bv = b[col] ?? '';
            const cmp = av < bv ? -1 : av > bv ? 1 : 0;
            return this.sortDir === 'asc' ? cmp : -cmp;
        });
        return rows;
    });

    constructor(private backend: BackendService, protected authService: AuthService) {
        // Re-load sites whenever user role resolves (handles async /me response)
        effect(() => {
            const user = this.authService.currentUser();
            if (user !== null) {
                this.loadSites();
            }
        });
    }

    ngOnInit(): void {
        // Initial load — covers the case where user is already resolved from stored token
        this.loadSites();
    }

    ngOnDestroy(): void {
        this.chart?.dispose();
    }

    private loadSites(): void {
        this.backend.listAllSites().subscribe({
            next: (s: string[]) => {
                const all = s || [];
                // Non-admins only ever see PROD sites
                const allowed = this.authService.isAdminSignal()
                    ? all
                    : all.filter((site: string) => site.endsWith('-PROD'));
                this.allSites.set(allowed);
            },
            error: () => {}
        });
    }

    onEnvChange(env: 'PROD' | 'QA'): void {
        this.selectedEnv.set(env);
        // Reset site and sender selection when environment changes
        this.selectedSite = '';
        this.selectedSenderId = null;
        this.senderOptions.set([]);
        this.points.set([]);
        this.hasQueried.set(false);
    }

    onSiteChange(site: string): void {
        this.selectedSenderId = null;
        this.senderOptions.set([]);
        if (!site) return;
        const env = this.selectedEnv();
        // Derive sender options from dashboard snapshot, strictly filtered to selected env
        this.backend.getDashboardSnapshot().subscribe({
            next: (snap: DashboardSnapshot) => {
                const siteSnap: DashboardSiteSnapshot | undefined = snap?.sites?.find((s: DashboardSiteSnapshot) => s.site === site);
                const oppositeSuffix = env === 'PROD' ? '_QA' : '_PROD';
                const opts: SenderOption[] = (siteSnap?.senders || [])
                    .filter(s => {
                        const name = (s.senderName || s.senderLabel || '').toUpperCase();
                        // Exclude senders whose name ends with the opposite env suffix
                        return !name.endsWith(oppositeSuffix);
                    })
                    .map(s => ({
                        id: s.senderId,
                        label: s.senderName || s.senderLabel || ''
                    }));
                this.senderOptions.set(opts);
            },
            error: () => {}
        });
    }

    onDeviceFilterChange(devices: string[]): void {
        this.selectedDevices.set(devices);
    }

    /** Convert a local date string (YYYY-MM-DD) to a UTC ISO instant (YYYY-MM-DDT00:00:00Z). */
    private toUtcIso(dateStr: string): string {
        if (!dateStr) return '';
        // Treat the date components as UTC so the same calendar date always maps to the same UTC range.
        return `${dateStr}T00:00:00Z`;
    }

    load(): void {
        if (!this.selectedSite || this.loading()) return;
        this.loading.set(true);
        this.error.set(null);
        this.hasQueried.set(true);
        this.backend.getCoverage({
            site: this.selectedSite,
            senderId: this.selectedSenderId ?? undefined,
            granularity: this.granularity,
            endTimeFrom: this.toUtcIso(this.endTimeFrom) || undefined,
            endTimeTo: this.toUtcIso(this.endTimeTo) || undefined,
            devices: this.selectedDevices().length > 0 ? this.selectedDevices() : undefined
        }).subscribe({
            next: (pts: CoveragePoint[]) => {
                this.points.set(pts || []);
                this.loading.set(false);
                setTimeout(() => this.renderChart(), 50);
            },
            error: (err: any) => {
                this.loading.set(false);
                this.error.set(err?.error?.message || err?.statusText || 'Failed to load coverage data');
            }
        });
    }

    reset(): void {
        this.selectedSite = '';
        this.selectedSenderId = null;
        this.selectedDevices.set([]);
        this.deviceFilterComponent?.clearSelection();
        this.granularity = 'day';
        this.endTimeFrom = '';
        this.endTimeTo = '';
        this.tableSearch = '';
        this.selectedEnv.set('PROD');
        this.points.set([]);
        this.senderOptions.set([]);
        this.hasQueried.set(false);
        this.error.set(null);
        this.chart?.dispose();
        this.chart = undefined;
    }

    sortBy(col: string): void {
        if (this.sortCol === col) {
            this.sortDir = this.sortDir === 'asc' ? 'desc' : 'asc';
        } else {
            this.sortCol = col;
            this.sortDir = col === 'bucket' || col === 'site' ? 'asc' : 'desc';
        }
    }

    sortIcon(col: string): string {
        if (this.sortCol !== col) return '↕';
        return this.sortDir === 'asc' ? '↑' : '↓';
    }

    exportCsv(): void {
        const rows = this.tableRows();
        if (!rows.length) return;
        const header = 'bucket,senderId,site,total,done,enqueued,staged,failed,rate\n';
        const body = rows.map((r: CoveragePoint) => {
            const rate = r.total > 0 ? ((r.done / r.total) * 100).toFixed(1) : '0';
            return `${r.bucket},${r.senderId},${r.site},${r.total},${r.done},${r.enqueued},${r.staged},${r.failed},${rate}%`;
        }).join('\n');
        const blob = new Blob([header + body], { type: 'text/csv' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `coverage-${this.selectedSite}-${Date.now()}.csv`;
        a.click();
        URL.revokeObjectURL(url);
    }

    private renderChart(): void {
        if (!this.chartRef?.nativeElement) return;
        if (this.chart) { this.chart.dispose(); }
        this.chart = echarts.init(this.chartRef.nativeElement, 'dark');

        const pts = this.points();
        const buckets = this.buckets();

        // Aggregate per bucket (sum across senders if multiple)
        const agg = new Map<string, { done: number; enqueued: number; staged: number; failed: number }>();
        for (const p of pts) {
            const cur = agg.get(p.bucket) ?? { done: 0, enqueued: 0, staged: 0, failed: 0 };
            agg.set(p.bucket, {
                done: cur.done + p.done,
                enqueued: cur.enqueued + p.enqueued,
                staged: cur.staged + p.staged,
                failed: cur.failed + p.failed
            });
        }

        const done = buckets.map((b: string) => agg.get(b)?.done ?? 0);
        const enqueued = buckets.map((b: string) => agg.get(b)?.enqueued ?? 0);
        const staged = buckets.map((b: string) => agg.get(b)?.staged ?? 0);
        const failed = buckets.map((b: string) => agg.get(b)?.failed ?? 0);

        this.chart.setOption({
            backgroundColor: 'transparent',
            tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
            legend: { data: ['Done', 'Enqueued', 'Staged', 'Failed'], textStyle: { color: 'rgba(203,213,225,0.85)' }, bottom: 0 },
            grid: { left: '3%', right: '3%', top: '8%', bottom: '14%', containLabel: true },
            xAxis: {
                type: 'category', data: buckets,
                axisLabel: { color: 'rgba(203,213,225,0.7)', fontSize: 11, rotate: buckets.length > 20 ? 45 : 0 },
                axisLine: { lineStyle: { color: 'rgba(167,139,250,0.2)' } }
            },
            yAxis: {
                type: 'value',
                axisLabel: { color: 'rgba(203,213,225,0.7)', fontSize: 11 },
                splitLine: { lineStyle: { color: 'rgba(167,139,250,0.1)' } }
            },
            series: [
                { name: 'Done', type: 'bar', stack: 'total', data: done, itemStyle: { color: '#10b981' } },
                { name: 'Enqueued', type: 'bar', stack: 'total', data: enqueued, itemStyle: { color: '#f59e0b' } },
                { name: 'Staged', type: 'bar', stack: 'total', data: staged, itemStyle: { color: '#818cf8' } },
                { name: 'Failed', type: 'bar', stack: 'total', data: failed, itemStyle: { color: '#ef4444' } }
            ]
        });
    }
}
