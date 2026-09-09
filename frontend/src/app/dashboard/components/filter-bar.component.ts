import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { GlassDeviceFilterComponent } from '../../shared/components/glass-device-filter.component';
import { GlassSelectComponent } from '../../shared/components/glass-select.component';

/**
 * FilterBarComponent — unified dashboard filter controls (Requirement 5.1).
 * Contains the device filter, a debounce-free sender search input, a site
 * selector, and a "Clear filters (n)" action when any are active (Req 5.5).
 * The dashboard owns the filter state signals and reacts to the outputs.
 */
@Component({
  selector: 'app-dashboard-filter-bar',
  standalone: true,
  imports: [CommonModule, FormsModule, MatIconModule, GlassDeviceFilterComponent, GlassSelectComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="dashboard-filter-bar glass-panel">
      <div class="filter-grid">
        <app-glass-device-filter
          label="Device"
          placeholder="All devices"
          (deviceChange)="deviceChange.emit($event)"
        ></app-glass-device-filter>

        <label class="search-filter-wrap">
          <span class="filter-label">
            <mat-icon aria-hidden="true">search</mat-icon>
            Sender
          </span>
          <input
            type="text"
            class="search-input"
            placeholder="Search senders…"
            [value]="senderSearch"
            (input)="senderSearchChange.emit($any($event.target).value)"
            aria-label="Search senders by name or ID"
          />
        </label>

        <app-glass-select
          label="Site"
          prefixIcon="location_on"
          placeholder="All sites"
          [options]="siteOptions"
          [ngModel]="siteFilter"
          (ngModelChange)="siteFilterChange.emit($event)"
        ></app-glass-select>
      </div>

      <div class="filter-actions" *ngIf="activeCount > 0">
        <button type="button" class="clear-filters-btn" (click)="clearFilters.emit()">
          <mat-icon>clear_all</mat-icon>
          Clear filters ({{ activeCount }})
        </button>
      </div>
    </div>
  `,
  styles: [
    `
      .dashboard-filter-bar {
        padding: 1rem;
        border-radius: 14px;
        border: 1px solid rgba(167, 139, 250, 0.16);
        background: rgba(30, 22, 68, 0.4);
        display: flex;
        flex-direction: column;
        gap: 0.75rem;
      }

      .filter-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
        gap: 0.875rem;
        align-items: end;
      }

      .search-filter-wrap {
        display: flex;
        flex-direction: column;
        gap: 0.375rem;
      }

      .filter-label {
        display: flex;
        align-items: center;
        gap: 0.375rem;
        font-size: 0.75rem;
        font-weight: 600;
        color: rgba(203, 213, 225, 0.8);
        text-transform: uppercase;
        letter-spacing: 0.03em;

        mat-icon {
          font-size: 0.875rem;
          width: 0.875rem;
          height: 0.875rem;
          color: rgba(167, 139, 250, 0.7);
        }
      }

      .search-input {
        height: 40px;
        padding: 0 0.875rem;
        border-radius: 10px;
        border: 1px solid rgba(255, 255, 255, 0.08);
        background: rgba(255, 255, 255, 0.03);
        color: rgba(226, 232, 255, 0.92);
        font-size: 0.84rem;
        outline: none;
        transition: all 0.2s ease;

        &::placeholder {
          color: rgba(203, 213, 225, 0.4);
        }

        &:focus {
          border-color: rgba(167, 139, 250, 0.55);
          box-shadow: 0 0 0 3px rgba(129, 140, 248, 0.12);
          background: rgba(255, 255, 255, 0.05);
        }
      }

      .filter-actions {
        display: flex;
        justify-content: flex-end;
        padding-top: 0.25rem;
      }

      .clear-filters-btn {
        display: inline-flex;
        align-items: center;
        gap: 0.375rem;
        height: 36px;
        padding: 0 0.875rem;
        border-radius: 8px;
        border: 1px solid rgba(239, 68, 68, 0.35);
        background: rgba(239, 68, 68, 0.1);
        color: #f87171;
        font-size: 0.78rem;
        font-weight: 600;
        cursor: pointer;
        transition: all 0.2s ease;

        mat-icon {
          font-size: 1rem;
          width: 1rem;
          height: 1rem;
        }

        &:hover {
          background: rgba(239, 68, 68, 0.2);
          border-color: rgba(239, 68, 68, 0.5);
          transform: translateY(-1px);
        }

        &:active {
          transform: translateY(0);
        }
      }

      app-glass-select,
      app-glass-device-filter {
        width: 100%;
      }

      @media (max-width: 1024px) {
        .filter-grid {
          grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
        }
      }

      @media (max-width: 768px) {
        .filter-grid {
          grid-template-columns: 1fr;
        }

        .filter-actions {
          justify-content: stretch;
        }

        .clear-filters-btn {
          width: 100%;
          justify-content: center;
        }
      }
    `,
  ],
})
export class DashboardFilterBarComponent {
  @Input() siteOptions: string[] = [];
  @Input() senderSearch = '';
  @Input() siteFilter = '';
  @Input() activeCount = 0;

  @Output() deviceChange = new EventEmitter<string[]>();
  @Output() senderSearchChange = new EventEmitter<string>();
  @Output() siteFilterChange = new EventEmitter<string>();
  @Output() clearFilters = new EventEmitter<void>();
}
