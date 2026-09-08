import { CommonModule } from '@angular/common';
import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  EventEmitter,
  Input,
  OnChanges,
  Output,
  SimpleChanges,
  ViewChild,
} from '@angular/core';
import {
  Chart,
  BarController,
  BarElement,
  CategoryScale,
  Filler,
  LineController,
  LineElement,
  LinearScale,
  PointElement,
  TimeScale,
  Tooltip,
} from 'chart.js';
import 'chartjs-adapter-date-fns';

Chart.register(
  LineController,
  LineElement,
  PointElement,
  BarController,
  BarElement,
  CategoryScale,
  LinearScale,
  TimeScale,
  Filler,
  Tooltip,
);

export interface SenderDetailView {
  senderId: number;
  label: string;
  site: string;
  backlog: number;
  throughput: number;
  successRate: number;
}

/**
 * SenderDetailPanelComponent — deep-dive analytics side panel (Requirement 2.5).
 * Shows:
 *  - throughput trend line (backlog/completed history over the selected window)
 *  - error rate % with a trend indicator
 *  - status distribution bar chart (from the latest known counts)
 *
 * Data is provided by the dashboard (which owns the rolling history); the panel
 * itself is presentation-only.
 */
@Component({
  selector: 'app-sender-detail-panel',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="detail-panel glass-panel" role="dialog" aria-modal="true" aria-label="Sender analytics">
      <div class="panel-head">
        <div class="panel-title">
          <h3>{{ sender?.label || 'Sender' }}</h3>
          <span class="panel-site">{{ sender?.site }}</span>
        </div>
        <button type="button" class="close-btn" (click)="closed.emit()" aria-label="Close sender analytics">
          ✕
        </button>
      </div>

      <div class="panel-kpis">
        <div class="kpi">
          <span class="kpi-label">Backlog</span>
          <span class="kpi-value">{{ (sender?.backlog ?? 0) | number }}</span>
        </div>
        <div class="kpi">
          <span class="kpi-label">Throughput</span>
          <span class="kpi-value">{{ (sender?.throughput ?? 0) | number }}/hr</span>
        </div>
        <div class="kpi">
          <span class="kpi-label">Error rate</span>
          <span class="kpi-value" [class.good]="errorRate() < 10">{{ errorRate() }}%</span>
        </div>
      </div>

      <div class="panel-section">
        <div class="panel-section-title">Backlog trend</div>
        <div class="chart-box">
          <canvas #trendCanvas></canvas>
        </div>
      </div>

      <div class="panel-section">
        <div class="panel-section-title">Status mix (last snapshot)</div>
        <div class="chart-box">
          <canvas #mixCanvas></canvas>
        </div>
      </div>
    </div>
  `,
  styles: [
    `
      .detail-panel {
        width: min(460px, calc(100vw - 2rem));
        display: flex;
        flex-direction: column;
        gap: 1rem;
        padding: 1.1rem 1.25rem 1.4rem;
        border-radius: 16px;
        background: rgba(24, 17, 52, 0.98);
        border: 1px solid rgba(167, 139, 250, 0.22);
        box-shadow: 0 24px 70px rgba(2, 8, 23, 0.6);
        max-height: 90vh;
        overflow: auto;
      }
      .panel-head {
        display: flex;
        align-items: flex-start;
        justify-content: space-between;
        gap: 0.75rem;
      }
      .panel-title h3 {
        margin: 0;
        font-size: 1rem;
        color: rgba(226, 232, 255, 0.95);
      }
      .panel-site {
        font-size: 0.72rem;
        color: rgba(203, 213, 225, 0.55);
        text-transform: uppercase;
        letter-spacing: 0.06em;
      }
      .close-btn {
        width: 30px;
        height: 30px;
        border-radius: 8px;
        border: 1px solid rgba(255, 255, 255, 0.1);
        background: transparent;
        color: rgba(203, 213, 225, 0.8);
        cursor: pointer;
        font-size: 0.85rem;
        line-height: 1;
      }
      .close-btn:hover {
        background: rgba(239, 68, 68, 0.15);
        color: #f87171;
      }
      .panel-kpis {
        display: grid;
        grid-template-columns: repeat(3, 1fr);
        gap: 0.5rem;
      }
      .kpi {
        display: flex;
        flex-direction: column;
        gap: 0.2rem;
        padding: 0.6rem 0.5rem;
        border-radius: 10px;
        background: rgba(255, 255, 255, 0.03);
        border: 1px solid rgba(255, 255, 255, 0.06);
      }
      .kpi-label {
        font-size: 0.62rem;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        color: rgba(203, 213, 225, 0.55);
      }
      .kpi-value {
        font-size: 1rem;
        font-weight: 800;
        color: rgba(226, 232, 255, 0.95);
      }
      .kpi-value.good {
        color: #34d399;
      }
      .panel-section {
        display: flex;
        flex-direction: column;
        gap: 0.4rem;
      }
      .panel-section-title {
        font-size: 0.72rem;
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.06em;
        color: rgba(203, 213, 225, 0.6);
      }
      .chart-box {
        position: relative;
        height: 130px;
      }
    `,
  ],
})
export class SenderDetailPanelComponent implements AfterViewInit, OnChanges {
  @Input() sender: SenderDetailView | null = null;
  @Input() backlogHistory: number[] = [];

  @Output() closed = new EventEmitter<void>();

  @ViewChild('trendCanvas', { static: true }) trendCanvas!: ElementRef<HTMLCanvasElement>;
  @ViewChild('mixCanvas', { static: true }) mixCanvas!: ElementRef<HTMLCanvasElement>;

  private trendChart?: Chart;
  private mixChart?: Chart;

  ngAfterViewInit(): void {
    this.render();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if ((changes['sender'] || changes['backlogHistory']) && this.trendCanvas?.nativeElement) {
      this.render();
    }
  }

  errorRate(): number {
    const s = this.sender?.successRate ?? 100;
    return Math.round((100 - s) * 10) / 10;
  }

  private render(): void {
    if (!this.trendCanvas?.nativeElement || !this.mixCanvas?.nativeElement) return;

    this.trendChart?.destroy();
    this.mixChart?.destroy();

    const history = this.backlogHistory ?? [];
    const n = history.length;

    // Trend line: index-based time axis is fine for an MVP spark detail.
    this.trendChart = new Chart(this.trendCanvas.nativeElement, {
      type: 'line',
      data: {
        labels: history.map((_, i) => `${n - i}m`),
        datasets: [
          {
            label: 'Backlog',
            data: history,
            borderColor: '#818cf8',
            backgroundColor: 'rgba(129, 140, 248, 0.12)',
            fill: true,
            tension: 0.35,
            pointRadius: 0,
            borderWidth: 2,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        animation: { duration: 300 },
        plugins: { legend: { display: false } },
        scales: {
          x: { ticks: { color: 'rgba(203,213,225,0.5)', font: { size: 9 } }, grid: { display: false } },
          y: { beginAtZero: true, ticks: { color: 'rgba(203,213,225,0.5)', font: { size: 9 } } },
        },
      },
    });

    const rate = this.errorRate();
    const okRate = Math.max(0, 100 - rate);
    this.mixChart = new Chart(this.mixCanvas.nativeElement, {
      type: 'bar',
      data: {
        labels: ['Successful', 'Errors'],
        datasets: [
          {
            data: [okRate, rate],
            backgroundColor: ['rgba(16,185,129,0.6)', 'rgba(239,68,68,0.6)'],
            borderRadius: 6,
            maxBarThickness: 46,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        indexAxis: 'y',
        animation: { duration: 300 },
        plugins: { legend: { display: false } },
        scales: {
          x: { beginAtZero: true, max: 100, ticks: { color: 'rgba(203,213,225,0.5)', font: { size: 9 } } },
          y: { ticks: { color: 'rgba(203,213,225,0.6)', font: { size: 10 } }, grid: { display: false } },
        },
      },
    });
  }
}
