import { CommonModule } from '@angular/common';
import { Component, Inject, OnInit, signal } from '@angular/core';
import { AbstractControl, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { GlassInputComponent } from '../shared/components/glass-input.component';
import { GlassSelectComponent } from '../shared/components/glass-select.component';
import { User, UserService } from './user.service';

export interface UserFormDialogData {
  mode: 'create' | 'edit';
  user?: User;
}

@Component({
  selector: 'app-user-form-dialog',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatDialogModule,
    MatIconModule,
    MatSlideToggleModule,
    GlassInputComponent,
    GlassSelectComponent,
  ],
  template: `
    <div class="form-dialog glass-panel">
      <div class="dialog-header">
        <mat-icon class="header-icon">{{ data.mode === 'create' ? 'person_add' : 'edit' }}</mat-icon>
        <h2>{{ data.mode === 'create' ? 'Create New User' : 'Edit User Profile' }}</h2>
      </div>

      <div class="dialog-body">
        <form [formGroup]="userForm" class="user-form">
          <div class="form-grid">
            <app-glass-input
              formControlName="username"
              label="Username"
              placeholder="Enter unique username"
              prefixIcon="person_outline"
              [error]="getControlError('username')"
            ></app-glass-input>

            <app-glass-input
              formControlName="email"
              label="Email Address"
              placeholder="user@example.com"
              prefixIcon="mail_outline"
              [error]="getControlError('email')"
            ></app-glass-input>

            <ng-container *ngIf="data.mode === 'create'">
              <app-glass-input
                formControlName="password"
                label="Password"
                type="password"
                placeholder="Secure password"
                prefixIcon="lock_outline"
                [suffixIcon]="hidePassword() ? 'visibility_off' : 'visibility'"
                (suffixAction)="hidePassword.set(!hidePassword())"
                [error]="getControlError('password')"
              ></app-glass-input>

              <app-glass-input
                formControlName="confirmPassword"
                label="Confirm Password"
                type="password"
                placeholder="Repeat password"
                prefixIcon="lock_reset"
                [suffixIcon]="hideConfirmPassword() ? 'visibility_off' : 'visibility'"
                (suffixAction)="hideConfirmPassword.set(!hideConfirmPassword())"
                [error]="getMismatchError()"
              ></app-glass-input>
            </ng-container>

            <app-glass-select
              formControlName="roles"
              label="Assigned Roles"
              placeholder="Select roles"
              prefixIcon="admin_panel_settings"
              [options]="availableRoles()"
              [multiple]="true"
              [error]="getControlError('roles')"
            ></app-glass-select>

            <app-glass-select
              formControlName="status"
              label="Account Status"
              placeholder="Select status"
              prefixIcon="info_outline"
              [options]="['ACTIVE', 'INACTIVE', 'LOCKED']"
            ></app-glass-select>
          </div>

          <div class="options-row">
            <div class="toggle-group">
              <mat-slide-toggle formControlName="enabled" color="primary">Enable Account</mat-slide-toggle>
              <span class="toggle-help">Allows the user to authenticate through the portal.</span>
            </div>
          </div>
        </form>
      </div>

      <div class="dialog-footer">
        <button class="cancel-btn" (click)="onCancel()">Cancel</button>
        <button
          class="save-btn"
          [disabled]="userForm.invalid || loading()"
          (click)="onSubmit()"
          [class.is-loading]="loading()"
        >
          <span *ngIf="!loading()">{{ data.mode === 'create' ? 'Create User' : 'Save Changes' }}</span>
          <span *ngIf="loading()">Saving...</span>
          <mat-icon *ngIf="!loading()">{{ data.mode === 'create' ? 'add' : 'check' }}</mat-icon>
        </button>
      </div>
    </div>
  `,
  styles: [
    `
      .form-dialog {
        padding: 0;
        border-radius: 20px;
        overflow: hidden;
        width: 100%;
        max-width: 650px;
      }

      .dialog-header {
        padding: 2rem;
        background: rgba(255, 255, 255, 0.02);
        border-bottom: 1px solid rgba(255, 255, 255, 0.05);
        display: flex;
        align-items: center;
        gap: 1rem;
      }
      .header-icon {
        font-size: 2.5rem;
        width: 2.5rem;
        height: 2.5rem;
        color: var(--accent-color);
      }
      .dialog-header h2 {
        margin: 0;
        font-size: 1.5rem;
        color: white;
      }

      .dialog-body {
        padding: 2rem;
        max-height: 60vh;
        overflow-y: auto;
      }

      .form-grid {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 1.5rem;
      }

      .options-row {
        margin-top: 2rem;
        padding-top: 1.5rem;
        border-top: 1px solid rgba(255, 255, 255, 0.05);
      }

      .toggle-group {
        display: flex;
        flex-direction: column;
        gap: 0.25rem;
      }
      .toggle-help {
        font-size: 0.8rem;
        color: var(--text-muted);
      }

      .dialog-footer {
        padding: 1.5rem 2rem;
        background: rgba(255, 255, 255, 0.02);
        border-top: 1px solid rgba(255, 255, 255, 0.05);
        display: flex;
        justify-content: flex-end;
        gap: 1rem;
      }

      .cancel-btn {
        padding: 0.75rem 1.5rem;
        background: transparent;
        color: var(--text-muted);
        border: 1px solid rgba(255, 255, 255, 0.1);
        border-radius: 12px;
        font-weight: 500;
        cursor: pointer;
        transition: all 0.3s ease;
        &:hover {
          background: rgba(255, 255, 255, 0.05);
          color: white;
        }
      }

      .save-btn {
        padding: 0.75rem 1.75rem;
        background: var(--accent-color);
        color: white;
        border: none;
        border-radius: 12px;
        font-weight: 600;
        cursor: pointer;
        display: flex;
        align-items: center;
        gap: 0.5rem;
        transition: all 0.3s ease;
        box-shadow: 0 4px 15px rgba(129, 140, 248, 0.2);
        &:hover:not(:disabled) {
          transform: translateY(-2px);
          box-shadow: 0 6px 20px rgba(129, 140, 248, 0.3);
        }
        &:disabled {
          opacity: 0.6;
          cursor: not-allowed;
        }
      }

      @media (max-width: 600px) {
        .form-grid {
          grid-template-columns: 1fr;
        }
      }
    `,
  ],
})
export class UserFormDialogComponent implements OnInit {
  userForm: FormGroup;
  loading = signal(false);
  hidePassword = signal(true);
  hideConfirmPassword = signal(true);
  availableRoles = signal<string[]>(['SUPER_ADMIN', 'ADMIN', 'REGULAR_USER']);

  constructor(
    private fb: FormBuilder,
    private userService: UserService,
    private toast: ToastService,
    public dialogRef: MatDialogRef<UserFormDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: UserFormDialogData,
  ) {
    this.userForm = this.fb.group(
      {
        username: ['', [Validators.required, Validators.minLength(3)]],
        email: ['', [Validators.email]],
        password: [''],
        confirmPassword: [''],
        roles: [[], [Validators.required]],
        enabled: [true],
        status: ['ACTIVE', [Validators.required]],
      },
      { validators: this.passwordMatchValidator },
    );
  }

  ngOnInit(): void {
    this.userService.getAvailableRoles().subscribe((roles) => this.availableRoles.set(roles));

    if (this.data.mode === 'edit' && this.data.user) {
      this.userForm.patchValue(this.data.user);
    } else {
      this.userForm.get('password')?.setValidators([Validators.required, Validators.minLength(8)]);
    }
  }

  private passwordMatchValidator(control: AbstractControl) {
    const password = control.get('password');
    const confirm = control.get('confirmPassword');
    return password && confirm && password.value !== confirm.value ? { passwordMismatch: true } : null;
  }

  getControlError(controlName: string): any {
    const control = this.userForm.get(controlName);
    if (control?.touched && control?.invalid) {
      if (control.hasError('required')) return 'Required field';
      if (control.hasError('minlength')) return `Min ${control.errors?.['minlength'].requiredLength} chars`;
      if (control.hasError('email')) return 'Invalid email';
    }
    return null;
  }

  getMismatchError(): any {
    if (this.userForm.get('confirmPassword')?.touched && this.userForm.hasError('passwordMismatch')) {
      return 'Passwords do not match';
    }
    return null;
  }

  onSubmit(): void {
    if (this.userForm.invalid) return;
    this.loading.set(true);
    const obs =
      this.data.mode === 'create'
        ? this.userService.createUser(this.userForm.value)
        : this.userService.updateUser(this.data.user!.id, this.userForm.value);

    obs.subscribe({
      next: () => {
        this.loading.set(false);
        this.dialogRef.close(true);
        this.toast.success(`User ${this.data.mode === 'create' ? 'created' : 'updated'} successfully`, 3000);
      },
      error: (err) => {
        this.loading.set(false);
        const errorMessage = this.extractErrorMessage(err);
        this.toast.error(errorMessage, 7000);
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
      return `${err.statusText}${err.status ? ` (${err.status})` : ''}`;
    }
    if (err?.message) {
      return err.message;
    }
    // Generic fallback based on HTTP status
    if (err?.status === 409) {
      return 'User already exists with this username or email';
    }
    if (err?.status === 400) {
      return 'Invalid input data. Please check your entries.';
    }
    if (err?.status === 403) {
      return 'You do not have permission to perform this action';
    }
    if (err?.status === 500) {
      return 'Server error. Please try again later.';
    }
    return 'Failed to complete user operation';
  }

  onCancel(): void {
    this.dialogRef.close(false);
  }
}
