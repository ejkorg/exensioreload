import { CommonModule } from '@angular/common';
import { Component, Input } from '@angular/core';

@Component({
  selector: 'app-sparkline',
  standalone: true,
  imports: [CommonModule],
  template: `
    <svg
      *ngIf="dataPoints.length > 1"
      class="sparkline"
      [attr.viewBox]="'0 0 ' + width + ' ' + height"
      preserveAspectRatio="none"
      aria-hidden="true">
      <defs>
        <linearGradient [attr.id]="gradientId" x1="0%" y1="0%" x2="0%" y2="100%">
          <stop offset="0%" [attr.stop-color]="color" stop-opacity="0.35"></stop>
          <stop offset="100%" [attr.stop-color]="color" stop-opacity="0.05"></stop>
        </linearGradient>
      </defs>

      <path [attr.d]="areaPath" [attr.fill]="'url(#' + gradientId + ')'" stroke="none"></path>
      <polyline
        [attr.points]="linePoints"
        fill="none"
        [attr.stroke]="color"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"></polyline>
    </svg>
  `,
  styles: [`
    :host {
      display: block;
      width: 100%;
      height: 100%;
    }

    .sparkline {
      width: 100%;
      height: 100%;
      overflow: visible;
    }
  `]
})
export class SparklineComponent {
  @Input() data: number[] = [];
  @Input() color = '#10b981';
  @Input() height = 28;

  readonly width = 120;
  readonly gradientId = `sparkline-${Math.random().toString(36).slice(2)}`;

  get dataPoints(): Array<{ x: number; y: number }> {
    if (!this.data.length) {
      return [];
    }

    if (this.data.length === 1) {
      return [{ x: 0, y: this.height / 2 }];
    }

    const max = Math.max(...this.data);
    const min = Math.min(...this.data);
    const range = max - min || 1;

    return this.data.map((value, index) => {
      const x = (index / (this.data.length - 1)) * this.width;
      const normalized = (value - min) / range;
      const y = this.height - (normalized * this.height * 0.8) - this.height * 0.1;
      return { x, y };
    });
  }

  get linePoints(): string {
    return this.dataPoints.map((point) => `${point.x},${point.y}`).join(' ');
  }

  get areaPath(): string {
    if (this.dataPoints.length < 2) {
      return '';
    }

    const points = this.dataPoints;
    const firstPoint = points[0];
    const lastPoint = points[points.length - 1];
    const linePath = points.map((point, index) => `${index === 0 ? 'M' : 'L'} ${point.x} ${point.y}`).join(' ');
    return `${linePath} L ${lastPoint.x} ${this.height} L ${firstPoint.x} ${this.height} Z`;
  }
}