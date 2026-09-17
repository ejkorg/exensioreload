import { CommonModule } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatDialogModule } from '@angular/material/dialog';
import { debounceTime, distinctUntilChanged } from 'rxjs';
import { DualTimestampComponent } from '../shared/components/dual-timestamp.component';
import { GlassIconComponent } from '../shared/components/glass-icon.component';
import { GlassPaginationComponent, PaginationEvent } from '../shared/components/glass-pagination.component';
import { GlassSelectComponent } from '../shared/components/glass-select.component';
import { ConfirmDialogComponent } from '../shared/confirm-dialog.component';
import { GlassTooltipDirective } from '../shared/directives/glass-tooltip.directive';
import { GlassDialogService } from '../shared/services/glass-dialog.service';
import { ToastService } from '../shared/services/toast.service';
import { ConfigEtlServer, ConfigurationService } from './configuration.service';
import { EtlServerFormDialogComponent } from './etl-server-form-dialog.component';

@Component({
  selector: 'app-admin-etl-server-config',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatDialogModule,
    GlassSelectComponent,
    GlassIconComponent,
    GlassTooltipDirective,
    GlassPaginationComponent,
    DualTimestampComponent,
  ],
  templateUrl: './admin-etl-server-config.component.html',
  styleUrls: ['./admin-etl-server-config.component.scss'],
})
export class AdminEtlServerConfigComponent implements OnInit {
  private configService = inject(ConfigurationService);
  private dialogService = inject(GlassDialogService);
  private toast = inject(ToastService);

  dataSource = { data: [] as ConfigEtlServer[] };

  loading = signal(false);
  totalElements = signal(0);
  pageSize = 20;
  pageIndex = 0;
  sortBy = '';
  sortDir: 'asc' | 'desc' = 'asc';

  searchControl = new FormControl('');
  environmentFilter = new FormControl('ALL');
  historicalOnlyFilter = new FormControl(false);

  ngOnInit(): void {
    this.loadEtlServers();

    // Setup reactive filters
    this.searchControl.valueChanges.pipe(debounceTime(300), distinctUntilChanged()).subscribe(() => this.reload());

    this.environmentFilter.valueChanges.subscribe(() => this.reload());

    this.historicalOnlyFilter.valueChanges.subscribe(() => this.reload());
  }

  loadEtlServers(): void {
    this.loading.set(true);
    this.configService
      .getEtlServersPaged({
        page: this.pageIndex,
        size: this.pageSize,
        search: this.searchControl.value || undefined,
        environment: this.environmentFilter.value || undefined,
        historicalOnly: this.historicalOnlyFilter.value || undefined,
        sortBy: this.sortBy || undefined,
        sortDir: this.sortDir,
      })
      .subscribe({
        next: (res) => {
          this.dataSource.data = res.content;
          this.totalElements.set(res.totalElements);
          this.loading.set(false);
        },
        error: (err) => {
          this.loading.set(false);
          const errorMessage = this.extractErrorMessage(err);
          this.toast.error(`Failed to load ETL servers: ${errorMessage}`, 7000);
        },
      });
  }

  setSort(field: string): void {
    if (this.sortBy === field) {
      this.sortDir = this.sortDir === 'asc' ? 'desc' : 'asc';
    } else {
      this.sortBy = field;
      this.sortDir = 'asc';
    }
    this.reload();
  }

  sortIcon(field: string): string {
    if (this.sortBy !== field) return 'unfold_more';
    return this.sortDir === 'asc' ? 'arrow_upward' : 'arrow_downward';
  }

  trackByServerId = (_: number, server: ConfigEtlServer): string | number => server.id || server.serverKey;

  private reload(): void {
    this.pageIndex = 0;
    this.loadEtlServers();
  }

  onPage(event: PaginationEvent): void {
    this.pageIndex = event.pageIndex;
    this.pageSize = event.pageSize;
    this.loadEtlServers();
  }

  createServer(): void {
    this.openDialog('create');
  }

  editServer(server: ConfigEtlServer): void {
    this.openDialog('edit', server);
  }

  private openDialog(mode: 'create' | 'edit', server?: ConfigEtlServer): void {
    const ref = this.dialogService.open(EtlServerFormDialogComponent, {
      width: '700px',
      data: { mode, server },
    });

    ref.afterClosed().then((res) => {
      if (res) {
        this.loadEtlServers();
        this.toast.success(
          mode === 'create' ? 'ETL server created successfully' : 'ETL server updated successfully',
          3000,
        );
      }
    });
  }

  deleteServer(server: ConfigEtlServer): void {
    const ref = this.dialogService.open(ConfirmDialogComponent, {
      width: '400px',
      data: {
        title: 'Delete ETL Server',
        message: `Are you sure you want to delete server "${server.serverKey}"? This action is permanent.`,
        confirmText: 'Delete',
        isDestructive: true,
      },
    });

    ref.afterClosed().then((res) => {
      if (res) {
        this.configService.deleteEtlServer(server.serverKey).subscribe({
          next: () => {
            this.loadEtlServers();
            this.toast.success('ETL server deleted successfully', 3000);
          },
          error: (err) => {
            const errorMessage = this.extractErrorMessage(err);
            this.toast.error(`Failed to delete ETL server: ${errorMessage}`, 7000);
          },
        });
      }
    });
  }

  private extractErrorMessage(err: any): string {
    if (err?.error?.message) {
      return err.error.message;
    }
    if (err?.error?.detail) {
      return err.error.detail;
    }
    if (err?.error?.error) {
      return err.error.error;
    }
    if (err?.statusText) {
      return err.statusText;
    }
    if (err?.message) {
      return err.message;
    }
    if (err?.status === 404) {
      return 'ETL server not found';
    }
    if (err?.status === 403) {
      return 'You do not have permission to perform this action';
    }
    if (err?.status === 500) {
      return 'Server error. Please try again later.';
    }
    if (err?.status === 409) {
      return 'Server key already exists or conflict detected';
    }
    if (err?.status === 400) {
      return 'Invalid ETL server configuration';
    }
    return 'An unexpected error occurred';
  }
}
