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
      <div class="filter-controls">
        <app-glass-device-filter (deviceChange)="deviceChange.emit($event)"></app-glass-device-filter>

        <label class="mini-filter search-filter">
          <mat-icon aria-hidden="true">search</mat-icon>
          <input
            type="text"
            placeholder="Search senders…"
            [value]="senderSearch"
            (input)="senderSearchChange.emit($any($event.target).value)"
            aria-label="Search senders by name or ID"
          />
        </label>

         <app-glass-select
           label="Site"
           placeholder="All sites"
           [options]="siteOptions"
           [ngModel]="siteFilter"
           (ngModelChange)="siteFilterChange.emit($event)"
        ></app-glass-select>

        <button type="button" class="clear-filters-btn" *ngIf="activeCount > 0" (click)="clearFilters.emit()">
          Clear filters ({{ activeCount }})
        </button>
      </div>
    </div>
  `,
  styles: [
    `
      .dashboard-filter-bar {
        padding: 0.6rem 0.85rem;
        border-radius: 14px;
        border: 1px solid rgba(167, 139, 250, 0.16);
        background: rgba(30, 22, 68, 0.4);
      }
      .filter-controls {
        display: flex;
        align-items: center;
        flex-wrap: wrap;
        gap: 0.6rem;
      }
      .mini-filter {
        display: inline-flex;
        align-items: center;
        gap: 0.4rem;
        height: 40px;
        padding: 0 0.75rem;
        border-radius: 10px;
        border: 1px solid rgba(255, 255, 255, 0.08);
        background: rgba(255, 255, 255, 0.03);

        mat-icon {
          color: rgba(203, 213, 225, 0.6);
          font-size: 1.1rem;
          width: 1.1rem;
          height: 1.1rem;
        }

        input,
        select {
          border: none;
          outline: none;
          background: transparent;
          color: rgba(226, 232, 255, 0.92);
          font-size: 0.82rem;
          min-width: 130px;
        }

        select {
          cursor: pointer;
          option {
            background: #1a1240;
            color: rgba(226, 232, 255, 0.92);
          }
        }

        &:focus-within {
          border-color: rgba(167, 139, 250, 0.55);
          box-shadow: 0 0 0 2px rgba(129, 140, 248, 0.15);
        }
      }
      .clear-filters-btn {
        height: 32px;
        padding: 0 0.7rem;
        border-radius: 8px;
        border: 1px solid rgba(239, 68, 68, 0.35);
        background: rgba(239, 68, 68, 0.1);
        color: #f87171;
        font-size: 0.75rem;
        font-weight: 600;
        cursor: pointer;
        transition: background 0.15s ease;
      }
      .clear-filters-btn:hover {
        background: rgba(239, 68, 68, 0.2);
      }

      /* Match the device & site glass-selects in both height and width (Req dashboard polish). */
      app-glass-select,
      app-glass-device-filter {
        min-width: 150px;
      }

      @media (max-width: 768px) {
        .mini-filter {
          flex: 1 1 100%;
          input,
          select {
            flex: 1;
          }
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
