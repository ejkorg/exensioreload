import { CommonModule } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { Router } from '@angular/router';
import { debounceTime, distinctUntilChanged } from 'rxjs';
import { GlassIconComponent } from '../shared/components/glass-icon.component';
import { GlassPaginationComponent, PaginationEvent } from '../shared/components/glass-pagination.component';
import { GlassSelectComponent } from '../shared/components/glass-select.component';
import { ConfirmDialogComponent } from '../shared/confirm-dialog.component';
import { GlassTooltipDirective } from '../shared/directives/glass-tooltip.directive';
import { UserFormDialogComponent } from './user-form-dialog.component';
import { User, UserService, UserStatistics } from './user.service';

@Component({
  selector: 'app-user-list',
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
  templateUrl: './user-list.component.html',
  styleUrls: ['./user-list.component.scss'],
})
export class UserListComponent implements OnInit {
  private userService = inject(UserService);
  private dialog = inject(MatDialog);
  private toast = inject(ToastService);
  private router = inject(Router);

  dataSource = { data: [] as User[] };

  loading = signal(false);
  stats = signal<UserStatistics | null>(null);
  roles = signal<string[]>([]);
  totalElements = signal(0);
  pageSize = 20;
  pageIndex = 0;
  sortBy = '';
  sortDir: 'asc' | 'desc' = 'asc';

  searchControl = new FormControl('');
  roleFilter = new FormControl('');
  statusFilter = new FormControl('');

  ngOnInit(): void {
    this.loadUsers();
    this.loadMetadata();

    // Setup reactive filters
    this.searchControl.valueChanges.pipe(debounceTime(400), distinctUntilChanged()).subscribe(() => this.reload());
    this.roleFilter.valueChanges.subscribe(() => this.reload());
    this.statusFilter.valueChanges.subscribe(() => this.reload());
  }

  loadUsers(): void {
    this.loading.set(true);
    this.userService
      .getUsers({
        page: this.pageIndex,
        size: this.pageSize,
        search: this.searchControl.value || undefined,
        role: this.roleFilter.value || undefined,
        status: this.statusFilter.value || undefined,
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
          this.toast.error(`Failed to load users: ${errorMessage}`, 7000);
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

  trackByUserId = (_: number, user: User): string | number => user.id;

  private loadMetadata(): void {
    this.userService.getStatistics().subscribe((s) => this.stats.set(s));
    this.userService.getAvailableRoles().subscribe((r) => this.roles.set(r));
  }

  private reload(): void {
    this.pageIndex = 0;
    this.loadUsers();
  }

  onPage(event: PaginationEvent): void {
    this.pageIndex = event.pageIndex;
    this.pageSize = event.pageSize;
    this.loadUsers();
  }

  createUser(): void {
    this.openDialog('create');
  }

  editUser(user: User): void {
    this.openDialog('edit', user);
  }

  private openDialog(mode: 'create' | 'edit', user?: User): void {
    const ref = this.dialog.open(UserFormDialogComponent, {
      width: '600px',
      data: { mode, user },
    });
    ref.afterClosed().subscribe((res) => {
      if (res) {
        this.loadUsers();
        this.loadMetadata();
      }
    });
  }

  deleteUser(user: User): void {
    const ref = this.dialog.open(ConfirmDialogComponent, {
      width: '400px',
      data: {
        title: 'Delete User',
        message: `Are you sure you want to delete ${user.username}? This action is permanent.`,
        confirmText: 'Delete',
        isDestructive: true,
      },
    });
    ref.afterClosed().subscribe((res) => {
      if (res) {
        this.userService.deleteUser(user.id).subscribe({
          next: () => {
            this.loadUsers();
            this.loadMetadata();
            this.toast.success('User deleted successfully', 3000);
          },
          error: (err) => {
            const errorMessage = this.extractErrorMessage(err);
            this.toast.error(`Failed to delete user: ${errorMessage}`, 7000);
          },
        });
      }
    });
  }

  toggleEnabled(user: User): void {
    this.userService.updateUser(user.id, { enabled: !user.enabled }).subscribe({
      next: () => {
        this.loadUsers();
        this.loadMetadata();
        this.toast.success(`User ${user.enabled ? 'disabled' : 'enabled'} successfully`, 3000);
      },
      error: (err) => {
        const errorMessage = this.extractErrorMessage(err);
        this.toast.error(`Failed to update user status: ${errorMessage}`, 7000);
      },
    });
  }

  private extractErrorMessage(err: any): string {
    // Try multiple error message sources in priority order
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
    // Generic fallback based on HTTP status
    if (err?.status === 404) {
      return 'User not found';
    }
    if (err?.status === 403) {
      return 'You do not have permission to perform this action';
    }
    if (err?.status === 500) {
      return 'Server error. Please try again later.';
    }
    if (err?.status === 409) {
      return 'User is already in that state or conflict detected';
    }
    return 'An unexpected error occurred';
  }

  goToAudit(): void {
    this.router.navigate(['/admin/audit']);
  }
}
