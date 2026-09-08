import { CommonModule } from '@angular/common';
import { Component, ElementRef, Input, OnDestroy, OnInit, ViewChild, signal } from '@angular/core';
import {
  Chart,
  Filler,
  LineController,
  LineElement,
  LinearScale,
  PointElement,
  TimeScale,
  Tooltip,
} from 'chart.js';
import 'chartjs-adapter-date-fns';
import StreamingPlugin from 'chartjs-plugin-streaming';
import { ChartTooltipComponent } from './chart-tooltip.component';
import { MetricsHistoryService, MetricTimeWindow } from '../services/metrics-history.service';

// Register Chart.js pieces used by every streaming line chart.
Chart.register(LineController, LineElement, PointElement, LinearScale, TimeScale, Tooltip, Filler, StreamingPlugin);

export const METRIC_COLORS: Record<string, string> = {
  backlog: '#f59e0b', // warning
  ready: '#818cf8', // primary
  enqueued: '#60a5fa', // info
  completed: '#10b981', // success
  failed: '#ef4444', // danger
  cancelled: '#6b7280',
};

const WINDOW_DURATION_MS: Record<MetricTimeWindow, number> = {
  '1h': 60 * 60 * 1000,
  '6h': 6 * 60 * 60 * 1000,
  '24h': 24 * 60 * 60 * 1000,
  '7d': 7 * 24 * 60 * 60 * 1000,
};

interface StreamPoint {
  x: number;
  y: number;
}

/**
 * TimeSeriesChartComponent renders a live streaming time-series line chart
 * using Chart.js 4.x with the chartjs-plugin-streaming plugin (design doc §1).
 *
 * - Feed window: 1h / 6h / 24h / 7d (Requirement 1.5)
 * - 300ms animation (Requirement 1.2)
 * - Tooltip with timestamp, value and rate-of-change (Requirement 1.3)
 * - Colors match KPI card scheme (Requirement 1.4)
 * - History updates are debounced to one render per 500ms (Requirement 6.4)
 */
@Component({
  selector: 'app-time-series-chart',
  standalone: true,
  imports: [CommonModule, ChartTooltipComponent],
  template: `
    <div class="time-series-card">
      <div class="ts-header">
        <span class="ts-title" [style.color]="colorScheme()">{{ displayLabel || metricKey }}</span>
        <div class="ts-window" role="group" aria-label="Time window">
          @for (w of windows; track w) {
            <button
              type="button"
              class="ts-window-btn"
              [class.active]="window() === w"
              (click)="setWindow(w)"
              [attr.aria-pressed]="window() === w"
            >
              {{ w }}
            </button>
          }
        </div>
      </div>
      <div class="ts-chart-wrap" #wrapEl>
        <canvas #chartEl></canvas>
        <app-chart-tooltip #tip></app-chart-tooltip>
      </div>
    </div>
  `,
  styles: [
    `
      .time-series-card {
        display: flex;
        flex-direction: column;
        gap: 0.35rem;
        padding: 0.75rem;
        border-radius: 12px;
        border: 1px solid rgba(167, 139, 250, 0.18);
        background: rgba(22, 16, 52, 0.5);
      }
      .ts-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 0.5rem;
      }
      .ts-title {
        font-size: 0.75rem;
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.05em;
      }
      .ts-window {
        display: flex;
        gap: 0.15rem;
      }
      .ts-window-btn {
        height: 22px;
        padding: 0 0.45rem;
        border-radius: 6px;
        border: 1px solid transparent;
        background: transparent;
        color: rgba(203, 213, 225, 0.7);
        font-size: 0.65rem;
        font-weight: 600;
        cursor: pointer;
        transition: all 0.15s ease;
      }
      .ts-window-btn:hover {
        background: rgba(129, 140, 248, 0.12);
        color: #a5b4fc;
      }
      .ts-window-btn.active {
        background: rgba(129, 140, 248, 0.18);
        color: #a5b4fc;
        border-color: rgba(129, 140, 248, 0.35);
      }
      .ts-chart-wrap {
        position: relative;
        height: 150px;
        width: 100%;
      }
    `,
  ],
})
export class TimeSeriesChartComponent implements OnInit, OnDestroy {
  @Input() metricKey = 'backlog';
  @Input() displayLabel = '';
  @Input({ transform: (v: string) => v as MetricTimeWindow }) windowInput: MetricTimeWindow = '6h';
  @Input() colorInput = '';
  @Input() heightInput: string | number = 150;

  @ViewChild('chartEl', { static: true }) chartEl!: ElementRef<HTMLCanvasElement>;
  @ViewChild('wrapEl', { static: true }) wrapEl!: ElementRef<HTMLDivElement>;
  @ViewChild('tip', { static: true }) tip?: ChartTooltipComponent;

  readonly windows: MetricTimeWindow[] = ['1h', '6h', '24h', '7d'];
  readonly window = signal<MetricTimeWindow>('6h');
  readonly colorScheme = signal<string>('#818cf8');
  readonly height = signal(150);

  get chartTitle(): string {
    return this.displayLabel || this.metricKey;
  }

  private chart: Chart<'line', StreamPoint[]> | null = null;
  private points: StreamPoint[] = [];
  private resizeObserver?: ResizeObserver;
  private readonly resizeDisabled = typeof ResizeObserver === 'undefined';

  constructor(private readonly history: MetricsHistoryService) {}

  ngOnInit(): void {
    this.window.set(this.windowInput || '6h');
    this.colorScheme.set(this.colorInput || METRIC_COLORS[this.metricKey] || '#818cf8');
    const h = Number(this.heightInput);
    this.height.set(Number.isFinite(h) && h > 0 ? h : 150);

    // Seed from retained history so the chart is never empty on load.
    this.points = this.history
      .getSeries(this.metricKey, this.window())
      .map((p) => ({ x: p.timestamp, y: p.value }));

    this.initChart();

    // MetricsHistoryService throttles updates$ to one emission per 500ms,
    // so rapid SSE bursts cause exactly one re-render (Requirement 6.4).
    this.history.updates$.subscribe(() => this.pushLatest());

    if (!this.resizeDisabled) {
      this.resizeObserver = new ResizeObserver(() => this.chart?.resize());
      this.resizeObserver.observe(this.chartEl.nativeElement);
    }
  }

  ngOnDestroy(): void {
    this.resizeObserver?.disconnect();
    if (this.chart) {
      this.chart.destroy();
      this.chart = null;
    }
  }

  setWindow(w: MetricTimeWindow): void {
    this.window.set(w);
    // Repaint the whole series for the newly selected window.
    this.points = this.history
      .getSeries(this.metricKey, w)
      .map((p) => ({ x: p.timestamp, y: p.value }));
    this.render();
  }

  /** Append the latest retained point (if any) and repaint the visible window. */
  private pushLatest(): void {
    const latest = this.history.getSeries(this.metricKey, this.window());
    if (latest.length === 0) return;
    const point = latest[latest.length - 1];
    this.points.push({ x: point.timestamp, y: point.value });

    const cutoff = Date.now() - WINDOW_DURATION_MS[this.window()];
    while (this.points.length > 0 && this.points[0].x < cutoff) {
      this.points.shift();
    }
    this.render();
  }

  private initChart(): void {
    const color = this.colorScheme();
    // Config is intentionally loose where the streaming plugin augments types.
    const config: Record<string, unknown> = {
      type: 'line',
      data: {
        datasets: [
          {
            label: this.chartTitle,
            data: this.points,
            borderColor: color,
            backgroundColor: this.hexToRgba(color, 0.12),
            fill: true,
            tension: 0.35,
            pointRadius: 0,
            pointHoverRadius: 4,
            borderWidth: 2,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        animation: { duration: 300, easing: 'easeOutQuart' }, // Requirement 1.2
        interaction: { intersect: false, mode: 'index' },
        plugins: {
          legend: { display: false },
          tooltip: {
            enabled: false, // custom glass tooltip (Req 1.3) — see ChartTooltipComponent
            external: (context: {
              tooltip?: {
                opacity?: number;
                dataPoints?: Array<{ dataIndex?: number }>;
                caretX?: number;
                caretY?: number;
              };
            }) => this.renderExternalTooltip(context),
          },
        },
        scales: {
          x: {
            type: 'realtime', // provided by chartjs-plugin-streaming
            time: { unit: this.axisUnit(this.window()) },
            realtime: {
              duration: WINDOW_DURATION_MS[this.window()],
              delay: 1000,
              refresh: 5000,
              onRefresh: (chart: Chart) => {
                const data = chart.data.datasets[0]?.data as unknown as StreamPoint[];
                const latest = this.history.latestValue(this.metricKey);
                if (latest > 0 || data.length > 0) {
                  data.push({ x: Date.now(), y: latest });
                }
              },
            },
            grid: { display: false },
            ticks: { color: 'rgba(203, 213, 225, 0.5)', maxTicksLimit: 8, font: { size: 10 } },
          },
          y: {
            beginAtZero: true,
            grid: { color: 'rgba(255, 255, 255, 0.04)' },
            ticks: { color: 'rgba(203, 213, 225, 0.5)', font: { size: 10 } },
          },
        },
      },
    };

    this.chart = new Chart<'line', StreamPoint[]>(
      this.chartEl.nativeElement,
      config as never,
    );
  }

  /** Chart.js external-tooltip hook → glass ChartTooltipComponent (Req 1.3). */
  private renderExternalTooltip(context: {
    tooltip?: {
      opacity?: number;
      dataPoints?: Array<{ dataIndex?: number }>;
      caretX?: number;
      caretY?: number;
    };
  }): void {
    const tooltip = context.tooltip;
    if (!this.tip) return;

    if (!tooltip || (tooltip.opacity ?? 0) === 0) {
      this.tip.hide();
      return;
    }

    const index = tooltip.dataPoints?.[0]?.dataIndex ?? -1;
    const point = index >= 0 && index < this.points.length ? this.points[index] : undefined;
    const previous = index > 0 ? this.points[index - 1] : undefined;

    const title = point
      ? new Date(point.x).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
      : this.chartTitle;

    const lines: string[] = [];
    if (point) {
      lines.push(`Value: ${point.y.toLocaleString()}`);
      if (previous) {
        const delta = point.y - previous.y;
        const pct = previous.y !== 0 ? ` (${((delta / previous.y) * 100).toFixed(1)}%)` : '';
        lines.push(`Change: ${delta >= 0 ? '+' : ''}${delta.toLocaleString()}${pct}`);
      } else {
        lines.push('Change: —');
      }
    }

    this.tip.setContent(title, lines);
    if (tooltip.caretX != null && tooltip.caretY != null && this.wrapEl?.nativeElement) {
      this.tip.place(tooltip.caretX, tooltip.caretY, this.wrapEl.nativeElement);
    }
  }

  private render(): void {
    if (!this.chart) return;
    const color = this.colorScheme();
    this.chart.data.datasets[0].data = this.points;
    this.chart.data.datasets[0].borderColor = color;
    this.chart.data.datasets[0].backgroundColor = this.hexToRgba(color, 0.12);
    (this.chart.options as any).scales.x.time.unit = this.axisUnit(this.window());
    (this.chart.options as any).scales.x.realtime.duration = WINDOW_DURATION_MS[this.window()];
    this.chart.update('none');
  }

  private axisUnit(w: MetricTimeWindow): 'hour' | 'minute' | 'day' {
    switch (w) {
      case '1h':
        return 'minute';
      case '7d':
        return 'day';
      default:
        return 'hour';
    }
  }

  private hexToRgba(hex: string, alpha: number): string {
    const m = hex.replace('#', '');
    const full = m.length === 3 ? m.split('').map((c) => c + c).join('') : m;
    const num = Number.parseInt(full, 16);
    if (Number.isNaN(num)) return `rgba(129,140,248,${alpha})`;
    return `rgba(${(num >> 16) & 255},${(num >> 8) & 255},${num & 255},${alpha})`;
  }
}
