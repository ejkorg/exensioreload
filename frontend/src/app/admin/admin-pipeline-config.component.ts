import { CommonModule } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { debounceTime, distinctUntilChanged } from 'rxjs';
import { GlassIconComponent } from '../shared/components/glass-icon.component';
import { GlassPaginationComponent, PaginationEvent } from '../shared/components/glass-pagination.component';
import { GlassSelectComponent } from '../shared/components/glass-select.component';
import { ConfirmDialogComponent } from '../shared/confirm-dialog.component';
import { GlassTooltipDirective } from '../shared/directives/glass-tooltip.directive';
import { GlassDialogService } from '../shared/services/glass-dialog.service';
import { ToastService } from '../shared/services/toast.service';
import { ConfigPipeline, ConfigurationService } from './configuration.service';
import { PipelineFormDialogComponent } from './pipeline-form-dialog.component';

@Component({
  selector: 'app-admin-pipeline-config',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    GlassSelectComponent,
    GlassIconComponent,
    GlassTooltipDirective,
    GlassPaginationComponent,
  ],
  templateUrl: './admin-pipeline-config.component.html',
  styleUrls: ['./admin-pipeline-config.component.scss'],
})
export class AdminPipelineConfigComponent implements OnInit {
  private configService = inject(ConfigurationService);
  private dialogService = inject(GlassDialogService);
  private toast = inject(ToastService);

  dataSource = { data: [] as ConfigPipeline[] };

  loading = signal(false);
  totalElements = signal(0);
  pageSize = 20;
  pageIndex = 0;
  sortBy = '';
  sortDir: 'asc' | 'desc' = 'asc';

  searchControl = new FormControl('');
  environmentFilter = new FormControl('ALL');

  ngOnInit(): void {
    this.loadPipelines();

    // Setup reactive filters
    this.searchControl.valueChanges.pipe(debounceTime(300), distinctUntilChanged()).subscribe(() => this.reload());

    this.environmentFilter.valueChanges.subscribe(() => this.reload());
  }

  loadPipelines(): void {
    this.loading.set(true);
    this.configService
      .getPipelines({
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
          this.toast.error(`Failed to load pipelines: ${errorMessage}`, 7000);
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

  trackByPipelineId = (_: number, pipeline: ConfigPipeline): string | number => pipeline.id || pipeline.pipelineKey;

  private reload(): void {
    this.pageIndex = 0;
    this.loadPipelines();
  }

  onPage(event: PaginationEvent): void {
    this.pageIndex = event.pageIndex;
    this.pageSize = event.pageSize;
    this.loadPipelines();
  }

  createPipeline(): void {
    this.openDialog('create');
  }

  editPipeline(pipeline: ConfigPipeline): void {
    this.openDialog('edit', pipeline);
  }

  private openDialog(mode: 'create' | 'edit', pipeline?: ConfigPipeline): void {
    const ref = this.dialogService.open(PipelineFormDialogComponent, {
      width: '700px',
      data: { mode, pipeline },
    });

    ref.afterClosed().then((res) => {
      if (res) {
        this.loadPipelines();
        this.toast.success(mode === 'create' ? 'Pipeline created successfully' : 'Pipeline updated successfully', 3000);
      }
    });
  }

  deletePipeline(pipeline: ConfigPipeline): void {
    const ref = this.dialogService.open(ConfirmDialogComponent, {
      width: '400px',
      data: {
        title: 'Delete Pipeline',
        message: `Are you sure you want to delete pipeline "${pipeline.pipelineKey}"? This action is permanent.`,
        confirmText: 'Delete',
        isDestructive: true,
      },
    });

    ref.afterClosed().then((res) => {
      if (res) {
        this.configService.deletePipeline(pipeline.pipelineKey).subscribe({
          next: () => {
            this.loadPipelines();
            this.toast.success('Pipeline deleted successfully', 3000);
          },
          error: (err) => {
            const errorMessage = this.extractErrorMessage(err);
            this.toast.error(`Failed to delete pipeline: ${errorMessage}`, 7000);
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
      return 'Pipeline not found';
    }
    if (err?.status === 403) {
      return 'You do not have permission to perform this action';
    }
    if (err?.status === 500) {
      return 'Server error. Please try again later.';
    }
    if (err?.status === 409) {
      return 'Pipeline key already exists or conflict detected';
    }
    if (err?.status === 400) {
      return 'Invalid pipeline configuration';
    }
    return 'An unexpected error occurred';
  }
}
