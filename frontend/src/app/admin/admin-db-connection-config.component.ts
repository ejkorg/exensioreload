import { CommonModule } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatDialogModule } from '@angular/material/dialog';
import { debounceTime, distinctUntilChanged } from 'rxjs';
import { GlassIconComponent } from '../shared/components/glass-icon.component';
import { GlassPaginationComponent, PaginationEvent } from '../shared/components/glass-pagination.component';
import { GlassSelectComponent } from '../shared/components/glass-select.component';
import { ConfirmDialogComponent } from '../shared/confirm-dialog.component';
import { GlassTooltipDirective } from '../shared/directives/glass-tooltip.directive';
import { GlassDialogService } from '../shared/services/glass-dialog.service';
import { ToastService } from '../shared/services/toast.service';
import { ConfigDbConnection, ConfigurationService } from './configuration.service';
import { DbConnectionFormDialogComponent } from './db-connection-form-dialog.component';

@Component({
  selector: 'app-admin-db-connection-config',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatDialogModule,
    GlassSelectComponent,
    GlassIconComponent,
    GlassTooltipDirective,
    GlassPaginationComponent,
  ],
  providers: [ConfigurationService],
  templateUrl: './admin-db-connection-config.component.html',
  styleUrls: ['./admin-db-connection-config.component.scss'],
})
export class AdminDbConnectionConfigComponent implements OnInit {
  private configService = inject(ConfigurationService);
  private dialogService = inject(GlassDialogService);
  private toast = inject(ToastService);

  dataSource = { data: [] as ConfigDbConnection[] };

  loading = signal(false);
  totalElements = signal(0);
  pageSize = 20;
  pageIndex = 0;
  sortBy = '';
  sortDir: 'asc' | 'desc' = 'asc';

  searchControl = new FormControl('');
  environmentFilter = new FormControl('ALL');

  ngOnInit(): void {
    this.loadConnections();

    // Setup reactive filters
    this.searchControl.valueChanges.pipe(debounceTime(300), distinctUntilChanged()).subscribe(() => this.reload());

    this.environmentFilter.valueChanges.subscribe(() => this.reload());
  }

  loadConnections(): void {
    this.loading.set(true);
    this.configService
      .getDbConnectionsPaged({
        page: this.pageIndex,
        size: this.pageSize,
        search: this.searchControl.value || undefined,
        environment: this.environmentFilter.value || undefined,
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
          this.toast.error(`Failed to load database connections: ${errorMessage}`, 7000);
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

  trackByConnectionId = (_: number, connection: ConfigDbConnection): string | number =>
    connection.id || connection.connectionKey;

  private reload(): void {
    this.pageIndex = 0;
    this.loadConnections();
  }

  onPage(event: PaginationEvent): void {
    this.pageIndex = event.pageIndex;
    this.pageSize = event.pageSize;
    this.loadConnections();
  }

  createConnection(): void {
    this.openDialog('create');
  }

  editConnection(connection: ConfigDbConnection): void {
    this.openDialog('edit', connection);
  }

  private openDialog(mode: 'create' | 'edit', connection?: ConfigDbConnection): void {
    const ref = this.dialogService.open(DbConnectionFormDialogComponent, {
      width: '700px',
      data: { mode, connection },
    });

    ref.afterClosed().then((res) => {
      if (res) {
        this.loadConnections();
        this.toast.success(
          mode === 'create' ? 'Database connection created successfully' : 'Database connection updated successfully',
          3000,
        );
      }
    });
  }

  deleteConnection(connection: ConfigDbConnection): void {
    const ref = this.dialogService.open(ConfirmDialogComponent, {
      width: '400px',
      data: {
        title: 'Delete Database Connection',
        message: `Are you sure you want to delete database connection "${connection.connectionKey}"? This action is permanent.`,
        confirmText: 'Delete',
        isDestructive: true,
      },
    });

    ref.afterClosed().then((res) => {
      if (res) {
        this.configService.deleteDbConnection(connection.connectionKey).subscribe({
          next: () => {
            this.loadConnections();
            this.toast.success('Database connection deleted successfully', 3000);
          },
          error: (err) => {
            const errorMessage = this.extractErrorMessage(err);
            this.toast.error(`Failed to delete database connection: ${errorMessage}`, 7000);
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
      return 'Database connection not found';
    }
    if (err?.status === 403) {
      return 'You do not have permission to perform this action';
    }
    if (err?.status === 500) {
      return 'Server error. Please try again later.';
    }
    if (err?.status === 409) {
      return 'Connection key already exists or conflict detected';
    }
    if (err?.status === 400) {
      return 'Invalid database connection configuration';
    }
    return 'An unexpected error occurred';
  }
}
