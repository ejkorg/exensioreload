import { CommonModule } from '@angular/common';
import { Component, ElementRef, Input, OnDestroy, Renderer2, ViewChild, computed } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { StateLegendService } from './state-legend.service';

/**
 * Accessible state legend tooltip component
 * Displays state information, description, and possible transitions
 * Supports hover and keyboard navigation (Tab, Enter, Space, Escape)
 */
@Component({
  selector: 'app-state-legend-tooltip',
  standalone: true,
  imports: [CommonModule, MatIconModule],
  template: `
    <div
      class="state-legend-wrapper"
      #tooltipContainer
      role="tooltip"
      [attr.aria-label]="'State information for ' + stateLabel()"
      tabindex="-1"
      (keydown)="onKeyDown($event)"
    >
      <button
        #tooltipTrigger
        type="button"
        class="legend-trigger-btn"
        [attr.aria-label]="'Show state legend for ' + stateLabel()"
        [attr.aria-describedby]="'state-legend-' + stateLabel().toLowerCase().replace(/\\s+/g, '-')"
        (click)="toggleTooltip()"
        (mouseenter)="onMouseEnter()"
        (mouseleave)="onMouseLeave()"
        tabindex="0"
      >
        <mat-icon class="legend-icon" aria-hidden="true">info</mat-icon>
      </button>

      <!-- Tooltip popup -->
      <div
        *ngIf="showTooltip()"
        class="state-legend-popup"
        [id]="'state-legend-' + stateLabel().toLowerCase().replace(/\\s+/g, '-')"
        role="presentation"
        (click)="$event.stopPropagation()"
        (mouseenter)="onPopupMouseEnter()"
        (mouseleave)="onPopupMouseLeave()"
      >
        <div class="legend-header">
          <mat-icon class="legend-state-icon" [ngClass]="stateColor()" aria-hidden="true">
            {{ stateIcon() }}
          </mat-icon>
          <h4 class="legend-title">{{ stateLabel() }}</h4>
          <button type="button" class="legend-close-btn" (click)="closeTooltip()" aria-label="Close state legend">
            <mat-icon aria-hidden="true">close</mat-icon>
          </button>
        </div>

        <div class="legend-content">
          <p class="legend-description">{{ stateDescription() }}</p>

          <!-- State details -->
          <div class="legend-details">
            <div class="detail-row">
              <span class="detail-label">Database Status:</span>
              <code class="detail-value">{{ stateStatusValue() }}</code>
            </div>

            <!-- Transitions -->
            <div *ngIf="nextStates().length > 0" class="detail-row">
              <span class="detail-label">Next States:</span>
              <span class="detail-value">
                {{ nextStates().join(', ') }}
              </span>
            </div>
            <div *ngIf="nextStates().length === 0" class="detail-row terminal-note">
              <mat-icon aria-hidden="true">check_circle</mat-icon>
              <span>Terminal state — no further transitions</span>
            </div>
          </div>

          <!-- Full tooltip from service -->
          <div class="legend-full-tooltip">
            <pre>{{ tooltipText() }}</pre>
          </div>

          <!-- Accessibility note -->
          <div class="legend-accessibility-note">
            <kbd>Tab</kbd> to navigate • <kbd>Enter</kbd> or <kbd>Space</kbd> to toggle • <kbd>Esc</kbd> to close
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [
    `
      .state-legend-wrapper {
        position: relative;
        display: inline-flex;
        align-items: center;
        gap: 4px;
      }

      .legend-trigger-btn {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 24px;
        height: 24px;
        padding: 0;
        background: transparent;
        border: 1px solid rgba(255, 255, 255, 0.3);
        border-radius: 50%;
        cursor: pointer;
        color: rgba(255, 255, 255, 0.7);
        transition: all 0.2s ease-out;
        font-size: 14px;

        &:hover {
          background: rgba(255, 255, 255, 0.1);
          border-color: rgba(255, 255, 255, 0.6);
          color: rgba(255, 255, 255, 1);
        }

        &:focus {
          outline: 2px solid rgba(255, 255, 255, 0.7);
          outline-offset: 2px;
        }

        &:active {
          transform: scale(0.95);
        }

        .legend-icon {
          font-size: 14px;
          width: 14px;
          height: 14px;
        }
      }

      .state-legend-popup {
        position: fixed;
        z-index: 10000;
        background: rgba(20, 20, 30, 0.98);
        backdrop-filter: blur(8px);
        border: 1px solid rgba(255, 255, 255, 0.15);
        border-radius: 12px;
        padding: 16px;
        box-shadow:
          0 20px 60px rgba(0, 0, 0, 0.4),
          0 0 40px rgba(124, 58, 255, 0.1);
        min-width: 300px;
        max-width: 450px;
        max-height: 80vh;
        overflow-y: auto;
        animation: slideInUp 0.3s ease-out;

        @media (max-width: 600px) {
          min-width: 280px;
          max-width: calc(100vw - 32px);
        }
      }

      @keyframes slideInUp {
        from {
          opacity: 0;
          transform: translateY(8px);
        }
        to {
          opacity: 1;
          transform: translateY(0);
        }
      }

      .legend-header {
        display: flex;
        align-items: center;
        gap: 12px;
        margin-bottom: 12px;
        border-bottom: 1px solid rgba(255, 255, 255, 0.1);
        padding-bottom: 12px;

        .legend-state-icon {
          font-size: 24px;
          width: 24px;
          height: 24px;

          &.color-primary {
            color: #818cf8;
          }

          &.color-secondary {
            color: #8b5cf6;
          }

          &.color-success {
            color: #10b981;
          }

          &.color-danger {
            color: #ef4444;
          }

          &.color-info {
            color: #3b82f6;
          }
        }

        .legend-title {
          flex: 1;
          margin: 0;
          font-size: 16px;
          font-weight: 600;
          color: rgba(255, 255, 255, 0.95);
        }

        .legend-close-btn {
          width: 24px;
          height: 24px;
          padding: 0;
          background: transparent;
          border: none;
          cursor: pointer;
          color: rgba(255, 255, 255, 0.5);
          transition: color 0.2s ease-out;
          border-radius: 4px;

          &:hover {
            color: rgba(255, 255, 255, 0.9);
            background: rgba(255, 255, 255, 0.05);
          }

          &:focus {
            outline: 2px solid rgba(255, 255, 255, 0.5);
            outline-offset: 2px;
          }

          mat-icon {
            font-size: 18px;
            width: 18px;
            height: 18px;
          }
        }
      }

      .legend-content {
        display: flex;
        flex-direction: column;
        gap: 12px;
      }

      .legend-description {
        margin: 0;
        font-size: 14px;
        color: rgba(255, 255, 255, 0.8);
        line-height: 1.5;
      }

      .legend-details {
        display: flex;
        flex-direction: column;
        gap: 8px;
        background: rgba(255, 255, 255, 0.05);
        border-radius: 8px;
        padding: 12px;
        border: 1px solid rgba(255, 255, 255, 0.1);

        .detail-row {
          display: flex;
          gap: 8px;
          font-size: 13px;
          color: rgba(255, 255, 255, 0.8);

          .detail-label {
            font-weight: 500;
            min-width: 110px;
            color: rgba(255, 255, 255, 0.6);
          }

          .detail-value {
            flex: 1;
            color: rgba(255, 255, 255, 0.9);
            word-break: break-word;
          }

          code {
            background: rgba(0, 0, 0, 0.3);
            padding: 2px 6px;
            border-radius: 4px;
            font-family: 'Monaco', 'Courier New', monospace;
            font-size: 12px;
          }

          &.terminal-note {
            gap: 8px;
            align-items: center;
            color: #10b981;

            mat-icon {
              font-size: 16px;
              width: 16px;
              height: 16px;
            }
          }
        }
      }

      .legend-full-tooltip {
        background: rgba(0, 0, 0, 0.3);
        border: 1px solid rgba(255, 255, 255, 0.08);
        border-radius: 8px;
        padding: 12px;
        font-size: 12px;
        color: rgba(255, 255, 255, 0.75);
        max-height: 180px;
        overflow-y: auto;

        pre {
          margin: 0;
          white-space: pre-wrap;
          word-wrap: break-word;
          font-family: 'Monaco', 'Courier New', monospace;
          line-height: 1.4;
        }
      }

      .legend-accessibility-note {
        font-size: 11px;
        color: rgba(255, 255, 255, 0.5);
        border-top: 1px solid rgba(255, 255, 255, 0.1);
        padding-top: 12px;
        margin-top: 4px;
        display: flex;
        gap: 4px;
        flex-wrap: wrap;

        kbd {
          background: rgba(255, 255, 255, 0.1);
          border: 1px solid rgba(255, 255, 255, 0.2);
          border-radius: 3px;
          padding: 2px 4px;
          font-family: monospace;
          font-size: 10px;
        }
      }
    `,
  ],
})
export class StateLegendTooltipComponent implements OnDestroy {
  @Input() stateLabel: () => string = () => '';
  @Input() triggerOnHover: boolean = true;

  @ViewChild('tooltipTrigger', { read: ElementRef }) triggerButton?: ElementRef;
  @ViewChild('tooltipContainer', { read: ElementRef }) container?: ElementRef;

  showTooltip = computed(() => this._showTooltip);
  private _showTooltip = false;

  private hideTimeout?: ReturnType<typeof setTimeout>;
  private isMouseOverTrigger = false;
  private isMouseOverPopup = false;

  constructor(
    private legendService: StateLegendService,
    private renderer: Renderer2,
  ) {}

  ngOnDestroy() {
    this.clearTimeouts();
  }

  stateDescription = (): string => {
    const definition = this.legendService.getStateByLabel(this.stateLabel());
    return definition?.description || '';
  };

  stateStatusValue = (): string => {
    const definition = this.legendService.getStateByLabel(this.stateLabel());
    return definition?.statusValue || '';
  };

  stateColor = (): string => {
    const definition = this.legendService.getStateByLabel(this.stateLabel());
    return `color-${definition?.color || 'primary'}`;
  };

  stateIcon = (): string => {
    const definition = this.legendService.getStateByLabel(this.stateLabel());
    return definition?.icon || 'info';
  };

  nextStates = (): string[] => {
    return this.legendService.getNextStates(this.stateLabel());
  };

  tooltipText = (): string => {
    return this.legendService.getTooltip(this.stateLabel());
  };

  toggleTooltip(): void {
    this._showTooltip ? this.closeTooltip() : this.openTooltip();
  }

  openTooltip(): void {
    this.clearTimeouts();
    this._showTooltip = true;
    this.positionPopup();
  }

  closeTooltip(): void {
    this._showTooltip = false;
  }

  onMouseEnter(): void {
    if (!this.triggerOnHover) return;
    this.isMouseOverTrigger = true;
    this.clearTimeouts();
    this.hideTimeout = setTimeout(() => {
      if (this.isMouseOverTrigger) {
        this.openTooltip();
      }
    }, 300);
  }

  onMouseLeave(): void {
    if (!this.triggerOnHover) return;
    this.isMouseOverTrigger = false;
    this.scheduleClose();
  }

  onPopupMouseEnter(): void {
    this.isMouseOverPopup = true;
    this.clearTimeouts();
  }

  onPopupMouseLeave(): void {
    this.isMouseOverPopup = false;
    this.scheduleClose();
  }

  onKeyDown(event: KeyboardEvent): void {
    switch (event.key) {
      case 'Enter':
      case ' ':
        event.preventDefault();
        this.toggleTooltip();
        break;
      case 'Escape':
        event.preventDefault();
        this.closeTooltip();
        break;
    }
  }

  private scheduleClose(): void {
    if (this._showTooltip && !this.isMouseOverTrigger && !this.isMouseOverPopup) {
      this.hideTimeout = setTimeout(() => {
        if (!this.isMouseOverTrigger && !this.isMouseOverPopup) {
          this.closeTooltip();
        }
      }, 100);
    }
  }

  private positionPopup(): void {
    // Positioning handled by CSS (fixed positioning from viewport)
    // In production, could add viewport-aware positioning
  }

  private clearTimeouts(): void {
    if (this.hideTimeout) {
      clearTimeout(this.hideTimeout);
    }
  }
}
