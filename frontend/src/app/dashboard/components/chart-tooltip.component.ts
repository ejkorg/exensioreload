import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, ElementRef } from '@angular/core';

/**
 * ChartTooltipComponent — reusable glass tooltip element for Chart.js external
 * tooltips (Requirement 1.3). Content is provided imperatively by the chart that
 * owns it, so it stays fully in sync with hovered data points.
 */
@Component({
  selector: 'app-chart-tooltip',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="chart-tooltip" #tooltipEl [class.visible]="visible">
      <div class="tt-title" *ngIf="title">{{ title }}</div>
      <div class="tt-line" *ngFor="let line of lines">{{ line }}</div>
    </div>
  `,
  styles: [
    `
      .chart-tooltip {
        position: absolute;
        z-index: 30;
        pointer-events: none;
        opacity: 0;
        transform: translateY(4px);
        transition: opacity 0.12s ease, transform 0.12s ease;
        min-width: 110px;
        max-width: 220px;
        padding: 0.5rem 0.65rem;
        border-radius: 10px;
        border: 1px solid rgba(167, 139, 250, 0.4);
        background: rgba(24, 17, 52, 0.97);
        backdrop-filter: blur(10px);
        box-shadow: 0 8px 24px rgba(2, 8, 23, 0.5);
        font-size: 0.72rem;
        color: rgba(226, 232, 255, 0.95);
      }
      .chart-tooltip.visible {
        opacity: 1;
        transform: translateY(0);
      }
      .tt-title {
        font-weight: 800;
        margin-bottom: 0.2rem;
        color: #e2e8f0;
      }
      .tt-line {
        font-variant-numeric: tabular-nums;
        color: rgba(203, 213, 225, 0.85);
        line-height: 1.45;
      }
    `,
  ],
})
export class ChartTooltipComponent {
  visible = false;
  title = '';
  lines: string[] = [];

  constructor(private readonly elementRef: ElementRef) {}

  /** The positioned element host charts position within their container. */
  get host(): HTMLElement {
    return this.elementRef.nativeElement as HTMLElement;
  }

  setContent(title: string, lines: string[]): void {
    this.title = title;
    this.lines = lines;
    this.visible = true;
  }

  /** Position relative to the chart container and clamp within it. */
  place(x: number, y: number, container: HTMLElement): void {
    const el = this.elementRef.nativeElement as HTMLElement;
    const cw = container.clientWidth;
    const ch = container.clientHeight;
    const ew = el.offsetWidth || 110;
    const eh = el.offsetHeight || 40;

    const left = Math.min(Math.max(8, x + 12), cw - ew - 8);
    const top = Math.min(Math.max(8, y - eh - 10), ch - eh - 8);
    el.style.left = `${left}px`;
    el.style.top = `${Math.max(8, top)}px`;
  }

  hide(): void {
    this.visible = false;
  }
}
