import { CommonModule } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { GlassInputComponent } from '../shared/components/glass-input.component';
import { ToastService } from '../shared/services/toast.service';
import { AuthService } from './auth.service';

@Component({
  selector: 'app-reset-password',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, RouterModule, MatIconModule, GlassInputComponent],
  template: `
    <div class="auth-viewport">
      <div class="auth-box glass-panel">
        <div class="auth-header">
          <mat-icon class="logo-icon">lock_open</mat-icon>
          <h1>Set New <span class="accent">Password</span></h1>
          <p class="subtitle">Complete your orbital recovery.</p>
        </div>

        <form [formGroup]="resetForm" (ngSubmit)="onSubmit()" class="auth-form" *ngIf="token()">
          <app-glass-input
            formControlName="password"
            label="New Password"
            type="password"
            placeholder="Min 8 characters"
            prefixIcon="lock_outline"
            [suffixIcon]="hidePassword() ? 'visibility_off' : 'visibility'"
            (suffixAction)="togglePassword()"
            [error]="getControlError('password')"
          ></app-glass-input>

          <app-glass-input
            formControlName="confirmPassword"
            label="Confirm New Password"
            type="password"
            placeholder="Repeat new password"
            prefixIcon="lock_reset"
            [suffixIcon]="hideConfirmPassword() ? 'visibility_off' : 'visibility'"
            (suffixAction)="toggleConfirmPassword()"
            [error]="getControlError('confirmPassword') || getMismatchError()"
          ></app-glass-input>

          <div class="auth-error" *ngIf="error()">{{ error() }}</div>

          <button
            class="submit-btn"
            type="submit"
            [disabled]="resetForm.invalid || loading()"
            [class.is-loading]="loading()"
          >
            <span *ngIf="!loading()">Reset Password</span>
            <span *ngIf="loading()">Processing...</span>
            <mat-icon *ngIf="!loading()">check_circle_outline</mat-icon>
          </button>
        </form>

        <div class="error-message glass-panel" *ngIf="!token()">
          <mat-icon class="error-icon">error_outline</mat-icon>
          <h3>Invalid Token</h3>
          <p>Your password reset link is invalid or has expired.</p>
          <button class="submit-btn mt-4" routerLink="/request-reset">Request New Link</button>
        </div>

        <div class="auth-footer">
          <a routerLink="/login" class="accent-link">Back to Login</a>
        </div>
      </div>
    </div>
  `,
  styles: [
    `
      .auth-viewport {
        display: flex;
        align-items: center;
        justify-content: center;
        min-height: 100vh;
        background: linear-gradient(135deg, #0f172a 0%, #1e1b4b 100%);
      }
      .auth-box {
        width: 100%;
        max-width: 440px;
        padding: 3.5rem;
        display: flex;
        flex-direction: column;
        gap: 2rem;
      }
      .auth-header {
        text-align: center;
      }
      .logo-icon {
        font-size: 3.5rem;
        width: 3.5rem;
        height: 3.5rem;
        color: var(--accent-color);
        margin-bottom: 1rem;
      }
      .auth-header h1 {
        font-size: 1.8rem;
        margin: 0;
        color: white;
      }
      .subtitle {
        color: var(--text-muted);
        font-size: 0.9rem;
        margin-top: 0.5rem;
      }
      .auth-form {
        display: flex;
        flex-direction: column;
        gap: 1.5rem;
      }

      .auth-error {
        padding: 0.75rem;
        border-radius: 10px;
        font-size: 0.85rem;
        text-align: center;
        background: rgba(239, 68, 68, 0.1);
        color: #f87171;
        border: 1px solid rgba(239, 68, 68, 0.2);
      }

      .submit-btn {
        height: 56px;
        width: 100%;
        background: var(--accent-color);
        color: white;
        border: none;
        font-size: 1.1rem;
        font-weight: 600;
        border-radius: 14px;
        box-shadow: 0 8px 20px rgba(129, 140, 248, 0.3);
        cursor: pointer;
        display: flex;
        align-items: center;
        justify-content: center;
        gap: 0.5rem;
        transition: all 0.3s ease;
        &:hover:not(:disabled) {
          transform: translateY(-2px);
          box-shadow: 0 10px 25px rgba(129, 140, 248, 0.4);
        }
        &:disabled {
          opacity: 0.6;
          cursor: not-allowed;
        }
      }

      .error-message {
        text-align: center;
        padding: 2rem;
        background: rgba(239, 68, 68, 0.05);
        border: 1px solid rgba(239, 68, 68, 0.1);
      }
      .error-icon {
        font-size: 4rem;
        width: 4rem;
        height: 4rem;
        color: #f87171;
        margin-bottom: 1rem;
      }
      .error-message h3 {
        color: white;
        margin-bottom: 0.5rem;
      }
      .error-message p {
        color: var(--text-muted);
        font-size: 0.9rem;
      }

      .auth-footer {
        text-align: center;
        padding-top: 1rem;
        border-top: 1px solid rgba(255, 255, 255, 0.05);
      }
      .accent-link {
        color: var(--accent-color);
        text-decoration: none;
        font-weight: 600;
      }
      .accent-link:hover {
        text-decoration: underline;
      }
      .mt-4 {
        margin-top: 1rem;
      }
    `,
  ],
})
export class ResetPasswordComponent implements OnInit {
  private fb = inject(FormBuilder);
  private auth = inject(AuthService);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private toast = inject(ToastService);

  loading = signal(false);
  hidePassword = signal(true);
  hideConfirmPassword = signal(true);
  token = signal<string | null>(null);
  error = signal('');

  resetForm = this.fb.group(
    {
      password: ['', [Validators.required, Validators.minLength(8)]],
      confirmPassword: ['', [Validators.required]],
    },
    { validators: this.passwordMatchValidator },
  );

  ngOnInit() {
    this.token.set(this.route.snapshot.queryParamMap.get('token'));
  }

  private passwordMatchValidator(control: AbstractControl) {
    const password = control.get('password');
    const confirm = control.get('confirmPassword');
    return password && confirm && password.value !== confirm.value ? { passwordMismatch: true } : null;
  }

  togglePassword() {
    this.hidePassword.set(!this.hidePassword());
  }
  toggleConfirmPassword() {
    this.hideConfirmPassword.set(!this.hideConfirmPassword());
  }

  getControlError(controlName: string): any {
    const control = this.resetForm.get(controlName);
    if (control?.touched && control?.invalid) {
      if (control.hasError('required'))
        return `${controlName === 'password' ? 'Password' : 'Confirmation'} is required`;
      if (control.hasError('minlength')) return 'Minimum 8 characters required';
    }
    return null;
  }

  getMismatchError(): any {
    const control = this.resetForm.get('confirmPassword');
    if (control?.touched && this.resetForm.hasError('passwordMismatch')) {
      return 'Passwords do not match';
    }
    return null;
  }

  onSubmit() {
    if (this.resetForm.invalid || !this.token() || this.loading()) return;

    this.loading.set(true);
    this.error.set('');

    this.auth.resetPassword(this.token()!, this.resetForm.value.password!).subscribe({
      next: () => {
        this.loading.set(false);
        this.toast.success('Password reset successful!', 3000);
        this.router.navigate(['/login']);
      },
      error: (err) => {
        this.loading.set(false);
        const message = err.error?.message || 'Reset failed. Your link may have expired.';
        this.error.set(message);
      },
    });
  }
}
