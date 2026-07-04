import { CommonModule } from '@angular/common';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { BackendService, StageRecordView } from '../api/backend.service';
import { ToastService } from '../shared/services/toast.service';

interface MetricCardDetailData {
  state: string;
  label: string;
  site: string;
  senderId: number;
  senderLabel: string;
}

@Component({
  selector: 'app-metric-card-detail-sidebar',
  standalone: true,
  imports: [
    CommonModule,
    MatButtonModule,
    MatIconModule,
    MatTooltipModule,
    MatProgressBarModule,
    MatTableModule,
    MatPaginatorModule,
    MatDialogModule,
  ],
  templateUrl: './metric-card-detail-sidebar.component.html',
  styleUrls: ['./metric-card-detail-sidebar.component.scss'],
})
export class MetricCardDetailSidebarComponent implements OnInit {
  private backend = inject(BackendService);
  private toast = inject(ToastService);
  data = inject<MetricCardDetailData>(MAT_DIALOG_DATA);

  records = signal<StageRecordView[]>([]);
  loading = signal(false);
  error = signal<string | null>(null);
  totalRecords = signal(0);
  currentPage = signal(0);
  pageSize = signal(20);

  displayColumns = ['status', 'filename', 'lot', 'wafer', 'updated'];

  displayedRecords = computed(() => {
    const recs = this.records();
    const start = this.currentPage() * this.pageSize();
    const end = start + this.pageSize();
    return recs.slice(start, end);
  });

  constructor() {}

  ngOnInit() {
    this.loadRecords();
  }

  private loadRecords() {
    this.loading.set(true);
    this.error.set(null);

    this.backend
      .getStageRecordsList(
        this.data.site,
        this.data.senderId,
        this.data.state,
        0,
        100, // Fetch up to 100 records at once
      )
      .subscribe({
        next: (response: any) => {
          this.records.set(response.items.slice(0, 20)); // Show top 20 sorted by created_at DESC
          this.totalRecords.set(Math.min(response.total, 20));
          this.loading.set(false);
        },
        error: (err: any) => {
          console.error('Failed to load records for state', this.data.state, err);
          this.error.set('Failed to load records. Please try again.');
          this.toast.error('Failed to load records', 5000);
          this.loading.set(false);
        },
      });
  }

  onPageChange(event: PageEvent) {
    this.currentPage.set(event.pageIndex);
    this.pageSize.set(event.pageSize);
  }

  formatDate(date: string | null | undefined): string {
    if (!date) return '-';
    try {
      const d = new Date(date);
      return isNaN(d.getTime())
        ? '-'
        : d.toLocaleString([], {
            year: 'numeric',
            month: 'short',
            day: 'numeric',
            hour: '2-digit',
            minute: '2-digit',
          });
    } catch {
      return '-';
    }
  }

  getStatusColor(status: string): string {
    switch (status?.toUpperCase()) {
      case 'DONE':
        return '#10b981';
      case 'FAILED':
        return '#ef4444';
      case 'CANCELLED':
        return '#f97316';
      case 'ENRICHMENT':
        return '#818cf8';
      case 'EXENSIO_LOADING':
        return '#06b6d4';
      case 'ENQUEUED':
        return '#3b82f6';
      default:
        return '#6b7280';
    }
  }
}
