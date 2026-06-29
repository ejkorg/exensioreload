import {
    AfterViewInit,
    ChangeDetectionStrategy,
    Component,
    ElementRef,
    HostListener,
    OnDestroy,
    OnInit,
    ViewChild,
    computed,
    signal
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterModule } from '@angular/router';
import { Subscription, timer } from 'rxjs';
import { BackendService, DashboardSnapshot, DashboardSenderSnapshot, DashboardSiteSnapshot } from '../api/backend.service';
import * as echarts from 'echarts';
import type { ECharts, EChartsOption } from 'echarts';
import { AuthService } from '../auth/auth.service';
import { formatSiteName } from '../shared/pipes/site-name.pipe';

interface AnalyticsSample {
    timestamp: number;
    backlog: number;
    ready: number;
    enqueued: number;
    completed: number;
    failed: number;
}

@Component({
    selector: 'app-analytics',
    standalone: true,
    imports: [CommonModule, MatButtonModule, MatIconModule, MatTooltipModule, RouterModule],
    templateUrl: './analytics.component.html',
    styleUrls: ['./analytics.component.scss'],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AnalyticsComponent implements OnInit, AfterViewInit, OnDestroy {
    @ViewChild('trendChartRef') trendChartRef?: ElementRef<HTMLDivElement>;
    @ViewChild('successRateChartRef') successRateChartRef?: ElementRef<HTMLDivElement>;
    @ViewChild('throughputChartRef') throughputChartRef?: ElementRef<HTMLDivElement>;
    @ViewChild('siteVolumeChartRef') siteVolumeChartRef?: ElementRef<HTMLDivElement>;
    @ViewChild('senderComparisonChartRef') senderComparisonChartRef?: ElementRef<HTMLDivElement>;

    snapshot = signal<DashboardSnapshot | null>(null);
    loading = signal(false);
    error = signal<string | null>(null);
    lastUpdated = signal<Date | null>(null);
    history = signal<AnalyticsSample[]>([]);
    timeRangeMinutes = signal(60);

    readonly rangeOptions = [
        { label: '1h', minutes: 60 },
        { label: '4h', minutes: 240 },
        { label: '12h', minutes: 720 },
        { label: '24h', minutes: 1440 }
    ];

    private readonly maxHistoryPoints = 480; // 4 hours at 30s polling
    private pollSub?: Subscription;
    private renderTimeout?: ReturnType<typeof setTimeout>;
    private isViewReady = false;

    private trendChart?: ECharts;
    private successRateChart?: ECharts;
    private throughputChart?: ECharts;
    private siteVolumeChart?: ECharts;
    private senderComparisonChart?: ECharts;

    readonly successRate = computed(() => {
        const snap = this.snapshot();
        if (!snap) return 100;
        const denom = snap.global.completed + snap.global.failed;
        if (denom <= 0) return 100;
        return Math.round((snap.global.completed / denom) * 1000) / 10;
    });

    readonly totalBacklog = computed(() => this.snapshot()?.global.backlog ?? 0);
    readonly activeSites = computed(() => this.snapshot()?.sites.length ?? 0);

    readonly filteredHistory = computed(() => {
        const minutes = this.timeRangeMinutes();
        const cutoff = Date.now() - minutes * 60 * 1000;
        return this.history().filter((point) => point.timestamp >= cutoff);
    });

    constructor(private backend: BackendService, private authService: AuthService) { }

    ngOnInit(): void {
        this.refresh();
        this.pollSub = timer(30000, 30000).subscribe(() => this.loadSnapshot(false));
    }

    ngAfterViewInit(): void {
        this.isViewReady = true;
        this.scheduleChartRender();
    }

    ngOnDestroy(): void {
        this.pollSub?.unsubscribe();
        if (this.renderTimeout) {
            clearTimeout(this.renderTimeout);
            this.renderTimeout = undefined;
        }
        this.disposeCharts();
    }

    refresh(): void {
        this.loadSnapshot(true);
    }

    setRange(minutes: number): void {
        this.timeRangeMinutes.set(minutes);
        this.scheduleChartRender();
    }

    exportHistory(): void {
        const data = this.filteredHistory();
        if (data.length === 0) return;
        const header = ['timestamp', 'backlog', 'ready', 'enqueued', 'completed', 'failed'];
        const rows = data.map((row) => [
            new Date(row.timestamp).toISOString(),
            row.backlog,
            row.ready,
            row.enqueued,
            row.completed,
            row.failed
        ]);
        const csv = [header.join(','), ...rows.map((r) => r.join(','))].join('\n');
        const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = `analytics-history-${Date.now()}.csv`;
        link.click();
        URL.revokeObjectURL(url);
    }

    @HostListener('window:resize')
    onResize(): void {
        this.trendChart?.resize();
        this.successRateChart?.resize();
        this.throughputChart?.resize();
        this.siteVolumeChart?.resize();
        this.senderComparisonChart?.resize();
    }

    formatTimeAgo(date: Date | null): string {
        if (!date) return 'Never';
        const seconds = Math.floor((Date.now() - date.getTime()) / 1000);
        if (seconds < 60) return 'Just now';
        if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
        return `${Math.floor(seconds / 3600)}h ago`;
    }

    hasData(): boolean {
        const snap = this.snapshot();
        return !!snap && snap.sites.length > 0;
    }

    private loadSnapshot(showLoading: boolean): void {
        if (showLoading) {
            this.loading.set(true);
        }

        this.backend.getDashboardSnapshot().subscribe({
            next: (snap: DashboardSnapshot) => {
                this.snapshot.set(snap);
                this.lastUpdated.set(new Date());
                this.error.set(null);
                this.loading.set(false);
                this.appendHistory(snap);
                this.scheduleChartRender();
            },
            error: () => {
                this.loading.set(false);
                this.error.set('Unable to load analytics data. Please retry.');
            }
        });
    }

    private appendHistory(snap: DashboardSnapshot): void {
        const next: AnalyticsSample = {
            timestamp: Date.now(),
            backlog: snap.global.backlog,
            ready: snap.global.ready,
            enqueued: snap.global.enqueued,
            completed: snap.global.completed,
            failed: snap.global.failed
        };

        this.history.update((current: AnalyticsSample[]) => {
            const updated = [...current, next];
            if (updated.length > this.maxHistoryPoints) {
                updated.shift();
            }
            return updated;
        });
    }

    private scheduleChartRender(): void {
        if (!this.isViewReady) return;

        if (this.renderTimeout) {
            clearTimeout(this.renderTimeout);
        }

        this.renderTimeout = setTimeout(() => this.renderCharts(), 0);
    }

    private renderCharts(): void {
        if (!this.hasData()) {
            return;
        }

        this.renderTrendChart();
        this.renderSuccessRateChart();
        this.renderThroughputChart();
        this.renderSiteVolumeChart();
        this.renderSenderComparisonChart();
    }

    private renderTrendChart(): void {
        if (!this.trendChartRef) return;

        this.trendChart = this.initChart(this.trendChart, this.trendChartRef);
        const history = this.filteredHistory();

        const xAxis = history.map((point: AnalyticsSample) => this.toShortTime(point.timestamp));
        const option: EChartsOption = {
            tooltip: { trigger: 'axis' },
            legend: { top: 0, textStyle: { color: '#9ca3af' } },
            grid: { left: 12, right: 12, top: 40, bottom: 28, containLabel: true },
            xAxis: { type: 'category', data: xAxis, axisLabel: { color: '#9ca3af' } },
            yAxis: { type: 'value', axisLabel: { color: '#9ca3af' }, splitLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.2)' } } },
            series: [
                { name: 'Backlog', type: 'line', smooth: true, data: history.map((p: AnalyticsSample) => p.backlog), lineStyle: { color: '#f59e0b' } },
                { name: 'Ready', type: 'line', smooth: true, data: history.map((p: AnalyticsSample) => p.ready), lineStyle: { color: '#818cf8' } },
                { name: 'Enqueued', type: 'line', smooth: true, data: history.map((p: AnalyticsSample) => p.enqueued), lineStyle: { color: '#3b82f6' } },
                { name: 'Completed', type: 'line', smooth: true, data: history.map((p: AnalyticsSample) => p.completed), lineStyle: { color: '#10b981' } }
            ]
        };

        this.trendChart.setOption(option, true);
    }

    private renderSuccessRateChart(): void {
        if (!this.successRateChartRef) return;

        this.successRateChart = this.initChart(this.successRateChart, this.successRateChartRef);
        const history = this.filteredHistory();

        const rateSeries = history.map((point: AnalyticsSample) => {
            const denom = point.completed + point.failed;
            if (denom <= 0) return 100;
            return Math.round((point.completed / denom) * 1000) / 10;
        });

        const option: EChartsOption = {
            tooltip: {
                trigger: 'axis',
                formatter: (params: any) => {
                    const p = Array.isArray(params) ? params[0] : params;
                    return `${p.name}<br/>${p.seriesName}: <b>${p.value}%</b>`;
                }
            },
            grid: { left: 12, right: 12, top: 24, bottom: 28, containLabel: true },
            xAxis: { type: 'category', data: history.map((point: AnalyticsSample) => this.toShortTime(point.timestamp)), axisLabel: { color: '#9ca3af' } },
            yAxis: {
                type: 'value',
                min: 0,
                max: 100,
                axisLabel: { color: '#9ca3af', formatter: '{value}%' },
                splitLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.2)' } }
            },
            series: [
                {
                    name: 'Success Rate',
                    type: 'line',
                    smooth: true,
                    areaStyle: { color: 'rgba(16, 185, 129, 0.12)' },
                    lineStyle: { color: '#10b981', width: 2 },
                    data: rateSeries
                }
            ]
        };

        this.successRateChart.setOption(option, true);
    }

    private renderThroughputChart(): void {
        if (!this.throughputChartRef) return;

        this.throughputChart = this.initChart(this.throughputChart, this.throughputChartRef);
        const history = this.filteredHistory();

        const throughputPerHour = history.map((point: AnalyticsSample, index: number) => {
            if (index === 0) return 0;
            const previous = history[index - 1];
            const deltaCompleted = Math.max(point.completed - previous.completed, 0);
            const deltaHours = (point.timestamp - previous.timestamp) / (1000 * 60 * 60);
            if (deltaHours <= 0) return 0;
            return Math.round(deltaCompleted / deltaHours);
        });

        const option: EChartsOption = {
            tooltip: { trigger: 'axis' },
            grid: { left: 12, right: 12, top: 24, bottom: 28, containLabel: true },
            xAxis: { type: 'category', data: history.map((point: AnalyticsSample) => this.toShortTime(point.timestamp)), axisLabel: { color: '#9ca3af' } },
            yAxis: { type: 'value', axisLabel: { color: '#9ca3af' }, splitLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.2)' } } },
            series: [
                {
                    name: 'Throughput / hour',
                    type: 'bar',
                    data: throughputPerHour,
                    itemStyle: { color: '#06b6d4', borderRadius: [4, 4, 0, 0] }
                }
            ]
        };

        this.throughputChart.setOption(option, true);
    }

    private renderSiteVolumeChart(): void {
        if (!this.siteVolumeChartRef) return;

        this.siteVolumeChart = this.initChart(this.siteVolumeChart, this.siteVolumeChartRef);
        const snap = this.snapshot();
        if (!snap) return;

        const chartData = snap.sites.map((site: DashboardSiteSnapshot) => ({
            name: formatSiteName(site.site, this.authService.isAdminSignal()),
            value: site.metrics.total > 0 ? site.metrics.total : (site.metrics.completed + site.metrics.ready + site.metrics.enqueued + site.metrics.backlog)
        }));

        const option: EChartsOption = {
            tooltip: { trigger: 'item' },
            legend: { orient: 'vertical', right: 0, top: 'middle', textStyle: { color: '#9ca3af' } },
            series: [
                {
                    name: 'Site Volume',
                    type: 'pie',
                    radius: ['45%', '72%'],
                    center: ['34%', '50%'],
                    avoidLabelOverlap: true,
                    label: { color: '#e2e8f0' },
                    data: chartData
                }
            ]
        };

        this.siteVolumeChart.setOption(option, true);
    }

    private renderSenderComparisonChart(): void {
        if (!this.senderComparisonChartRef) return;

        this.senderComparisonChart = this.initChart(this.senderComparisonChart, this.senderComparisonChartRef);
        const snap = this.snapshot();
        if (!snap) return;

        const senders = this.collectTopSenders(snap, 8);
        const labels = senders.map((sender) => sender.senderLabel);

        const option: EChartsOption = {
            tooltip: { trigger: 'axis' },
            legend: { top: 0, textStyle: { color: '#9ca3af' } },
            grid: { left: 12, right: 12, top: 40, bottom: 48, containLabel: true },
            xAxis: {
                type: 'category',
                data: labels,
                axisLabel: { color: '#9ca3af', rotate: 25 }
            },
            yAxis: [
                {
                    type: 'value',
                    name: 'Backlog',
                    axisLabel: { color: '#9ca3af' },
                    splitLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.2)' } }
                },
                {
                    type: 'value',
                    name: 'Success %',
                    min: 0,
                    max: 100,
                    axisLabel: { color: '#9ca3af', formatter: '{value}%' }
                }
            ],
            series: [
                {
                    name: 'Backlog',
                    type: 'bar',
                    yAxisIndex: 0,
                    data: senders.map((sender) => sender.metrics.backlog),
                    itemStyle: { color: '#f59e0b', borderRadius: [4, 4, 0, 0] }
                },
                {
                    name: 'Success Rate',
                    type: 'line',
                    smooth: true,
                    yAxisIndex: 1,
                    data: senders.map((sender) => this.getSenderSuccessRate(sender)),
                    lineStyle: { color: '#10b981', width: 2 }
                }
            ]
        };

        this.senderComparisonChart.setOption(option, true);
    }

    private initChart(existing: ECharts | undefined, ref: ElementRef<HTMLDivElement>): ECharts {
        if (existing) {
            return existing;
        }

        return echarts.getInstanceByDom(ref.nativeElement) ?? echarts.init(ref.nativeElement, undefined, { renderer: 'canvas' });
    }

    private collectTopSenders(snapshot: DashboardSnapshot, limit: number): DashboardSenderSnapshot[] {
        const allSenders = snapshot.sites.flatMap((site: DashboardSiteSnapshot) => site.senders);
        return allSenders
            .sort((a, b) => b.metrics.backlog - a.metrics.backlog)
            .slice(0, limit);
    }

    private getSenderSuccessRate(sender: DashboardSenderSnapshot): number {
        const denom = sender.metrics.completed + sender.metrics.failed;
        if (denom <= 0) return 100;
        return Math.round((sender.metrics.completed / denom) * 1000) / 10;
    }

    private toShortTime(timestamp: number): string {
        return new Date(timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', timeZone: 'UTC' });
    }

    private disposeCharts(): void {
        this.trendChart?.dispose();
        this.successRateChart?.dispose();
        this.throughputChart?.dispose();
        this.siteVolumeChart?.dispose();
        this.senderComparisonChart?.dispose();

        this.trendChart = undefined;
        this.successRateChart = undefined;
        this.throughputChart = undefined;
        this.siteVolumeChart = undefined;
        this.senderComparisonChart = undefined;
    }
}
