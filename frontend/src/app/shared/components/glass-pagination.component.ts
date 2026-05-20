import { Component, Input, Output, EventEmitter, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatIconModule } from '@angular/material/icon';

export interface PaginationEvent {
  pageIndex: number;
  pageSize: number;
  previousPageIndex: number;
}

@Component({
  selector: 'app-glass-pagination',
  standalone: true,
  imports: [CommonModule, MatIconModule],
  template: `
    <div class="glass-pagination">
      <div class="pagination-info">
        <span class="info-text">
          {{ startIndex() }}-{{ endIndex() }} of {{ totalItems() }}
        </span>
      </div>

      <div class="pagination-controls">
        <div class="page-size-selector">
          <span class="selector-label">Rows per page:</span>
          <select
            [value]="currentPageSize()"
            (change)="onPageSizeChange($event)"
            class="glass-select">
            <option *ngFor="let size of currentPageSizeOptions()" [value]="size">
              {{ size }}
            </option>
          </select>
        </div>

        <div class="page-navigation">
          <button
            class="nav-button"
            [disabled]="!hasPrevious()"
            (click)="firstPage()"
            [attr.aria-label]="'First page'">
            <mat-icon>first_page</mat-icon>
          </button>

          <button
            class="nav-button"
            [disabled]="!hasPrevious()"
            (click)="previousPage()"
            [attr.aria-label]="'Previous page'">
            <mat-icon>chevron_left</mat-icon>
          </button>

          <span class="page-indicator">
            Page {{ currentPage() }} of {{ totalPages() }}
          </span>

          <button
            class="nav-button"
            [disabled]="!hasNext()"
            (click)="nextPage()"
            [attr.aria-label]="'Next page'">
            <mat-icon>chevron_right</mat-icon>
          </button>

          <button
            class="nav-button"
            [disabled]="!hasNext()"
            (click)="lastPage()"
            [attr.aria-label]="'Last page'">
            <mat-icon>last_page</mat-icon>
          </button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .glass-pagination {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 0.5rem 1rem;
      background: linear-gradient(135deg, rgba(255, 255, 255, 0.08) 0%, rgba(255, 255, 255, 0.03) 100%);
      border: 1px solid rgba(255, 255, 255, 0.1);
      border-radius: 10px;
      gap: 1rem;
      flex-wrap: wrap;
      margin-top: 0.5rem;
    }

    .pagination-info {
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }

    .info-text {
      font-size: 0.72rem;
      color: var(--text-muted);
      font-weight: 600;
      letter-spacing: 0.3px;
    }

    .pagination-controls {
      display: flex;
      align-items: center;
      gap: 1rem;
    }

    .page-size-selector {
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }

    .selector-label {
      font-size: 0.72rem;
      color: var(--text-muted);
      font-weight: 600;
      letter-spacing: 0.3px;
    }

    .glass-select {
      background: linear-gradient(135deg, rgba(255, 255, 255, 0.1) 0%, rgba(255, 255, 255, 0.05) 100%);
      border: 1px solid rgba(255, 255, 255, 0.15);
      border-radius: 6px;
      padding: 0.25rem 0.5rem;
      color: var(--text-main);
      font-size: 0.72rem;
      font-weight: 600;
      cursor: pointer;
      transition: all 0.2s ease;
      outline: none;

      &:hover {
        background: linear-gradient(135deg, rgba(255, 255, 255, 0.15) 0%, rgba(255, 255, 255, 0.08) 100%);
        border-color: rgba(129, 140, 248, 0.3);
      }

      &:focus {
        border-color: rgba(129, 140, 248, 0.5);
        box-shadow: 0 0 0 2px rgba(129, 140, 248, 0.15);
      }

      option {
        background: var(--bg-color);
        color: var(--text-main);
      }
    }

    .page-navigation {
      display: flex;
      align-items: center;
      gap: 0.35rem;
    }

    .page-indicator {
      font-size: 0.72rem;
      color: var(--text-main);
      font-weight: 600;
      padding: 0 0.5rem;
      min-width: 90px;
      text-align: center;
    }

    .nav-button {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 28px;
      height: 28px;
      background: rgba(255, 255, 255, 0.06);
      border: 1px solid rgba(255, 255, 255, 0.1);
      border-radius: 6px;
      color: var(--text-main);
      cursor: pointer;
      transition: all 0.2s ease;
      outline: none;

      mat-icon {
        font-size: 1rem;
        width: 1rem;
        height: 1rem;
      }

      &:hover:not(:disabled) {
        background: rgba(129, 140, 248, 0.15);
        border-color: rgba(129, 140, 248, 0.3);
        color: var(--accent-color);
      }

      &:disabled {
        opacity: 0.3;
        cursor: not-allowed;
      }
    }

    @media (max-width: 768px) {
      .glass-pagination {
        flex-direction: column;
        gap: 0.75rem;
        padding: 0.5rem 0.75rem;
      }

      .pagination-info,
      .pagination-controls {
        width: 100%;
        justify-content: center;
      }

      .pagination-controls {
        flex-direction: column;
        gap: 0.75rem;
      }

      .page-indicator { min-width: auto; padding: 0 0.25rem; }
      .page-size-selector, .page-navigation { width: 100%; justify-content: center; }
    }
  `]
})
export class GlassPaginationComponent {
  @Input() set length(value: number) {
    this.totalItems.set(value);
  }

  @Input() set pageSize(value: number) {
    this.currentPageSize.set(value);
  }

  @Input() set pageIndex(value: number) {
    this.currentPageIndex.set(value);
  }

  @Input() set pageSizeOptions(value: number[]) {
    this.currentPageSizeOptions.set(value);
  }

  @Output() page = new EventEmitter<PaginationEvent>();

  totalItems = signal(0);
  currentPageSize = signal(10);
  currentPageIndex = signal(0);
  currentPageSizeOptions = signal([10, 25, 50, 100]);

  totalPages = computed(() => {
    const total = this.totalItems();
    const size = this.currentPageSize();
    return size > 0 ? Math.ceil(total / size) : 0;
  });

  currentPage = computed(() => this.currentPageIndex() + 1);

  startIndex = computed(() => {
    const total = this.totalItems();
    if (total === 0) return 0;
    return this.currentPageIndex() * this.currentPageSize() + 1;
  });

  endIndex = computed(() => {
    const total = this.totalItems();
    const end = (this.currentPageIndex() + 1) * this.currentPageSize();
    return Math.min(end, total);
  });

  hasPrevious = computed(() => this.currentPageIndex() > 0);
  hasNext = computed(() => this.currentPageIndex() < this.totalPages() - 1);

  private emitPageEvent(newPageIndex: number) {
    const previousPageIndex = this.currentPageIndex();
    this.currentPageIndex.set(newPageIndex);

    this.page.emit({
      pageIndex: newPageIndex,
      pageSize: this.currentPageSize(),
      previousPageIndex
    });
  }

  onPageSizeChange(event: Event) {
    const newSize = parseInt((event.target as HTMLSelectElement).value, 10);
    const previousPageIndex = this.currentPageIndex();

    this.currentPageSize.set(newSize);
    this.currentPageIndex.set(0); // Reset to first page when changing page size

    this.page.emit({
      pageIndex: 0,
      pageSize: newSize,
      previousPageIndex
    });
  }

  firstPage() {
    if (this.hasPrevious()) {
      this.emitPageEvent(0);
    }
  }

  previousPage() {
    if (this.hasPrevious()) {
      this.emitPageEvent(this.currentPageIndex() - 1);
    }
  }

  nextPage() {
    if (this.hasNext()) {
      this.emitPageEvent(this.currentPageIndex() + 1);
    }
  }

  lastPage() {
    if (this.hasNext()) {
      this.emitPageEvent(this.totalPages() - 1);
    }
  }
}
