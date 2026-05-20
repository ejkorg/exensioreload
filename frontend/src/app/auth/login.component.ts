import { Component, inject, signal, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { AuthService } from './auth.service';
import { GlassInputComponent } from '../shared/components/glass-input.component';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    RouterModule,
    MatIconModule,
    GlassInputComponent
  ],  template: `
    <div class="login-viewport">
      <div class="login-box glass-panel">
        <div class="login-header">
          <div class="logo-area">
            <mat-icon class="logo-icon">account_tree</mat-icon>
            <div class="logo-text">
              <span class="accent">ExensioReload</span>
              <span class="v-tag">1.0</span>
            </div>
          </div>
          <p class="welcome-text">Payload Staging & Orchestration</p>
          <p class="subtitle">Manage resend requests and sender queues.</p>
        </div>

        <form [formGroup]="loginForm" (ngSubmit)="onLogin()" class="login-form">
          <app-glass-input
            formControlName="username"
            label="Username"
            placeholder="Enter your username"
            prefixIcon="person_outline"
            autocomplete="username"
            [error]="getControlError('username')"
          ></app-glass-input>

          <app-glass-input
            formControlName="password"
            label="Password"
            type="password"
            placeholder="Enter your password"
            prefixIcon="lock_outline"
            autocomplete="current-password"
            [error]="getControlError('password')"
          ></app-glass-input>

          <div class="form-options">
            <a routerLink="/request-reset" class="accent-link small">Forgot Password?</a>
          </div>

          <div class="auth-error" *ngIf="error()">{{ error() }}</div>
          <div class="auth-success" *ngIf="success()">{{ success() }}</div>

          <div class="login-actions">
            <button class="submit-btn" type="submit" [disabled]="loginForm.invalid || loading()" [class.is-loading]="loading()">
              <span *ngIf="!loading()">Authenticate</span>
              <span *ngIf="loading()">Signing in...</span>
              <mat-icon *ngIf="!loading()">login</mat-icon>
            </button>
          </div>
        </form>

        <div class="sso-section" *ngIf="ssoEnabled()">
          <div class="sso-divider">
            <span>or</span>
          </div>
          <button class="sso-btn" type="button" (click)="onSsoLogin()" [disabled]="ssoLoading()">
            <span *ngIf="!ssoLoading()">
              <mat-icon>business</mat-icon>
              Sign in with onsemi SSO
            </span>
            <span *ngIf="ssoLoading()">Redirecting to SSO...</span>
          </button>
        </div>

        <div class="login-footer">
          <div class="footer-links">
            <span>&copy; 2026 Exensio Data Integration</span>
            <div class="register-link">
              New user? <a routerLink="/register" class="accent-link">Create Account</a>
            </div>
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .login-viewport {
      display: flex;
      align-items: center;
      justify-content: center;
      min-height: 100vh;
      background: linear-gradient(135deg, var(--bg-gradient-start) 0%, var(--bg-gradient-end) 100%);
      position: relative;
      overflow: hidden;
    }

    :host-context(body.light-theme) .login-viewport {
      background: linear-gradient(135deg, #f1f5f9 0%, #e2e8f0 100%);
    }

    .login-box {
      width: 100%;
      max-width: 480px;
      padding: 3.5rem;
      z-index: 10;
      display: flex;
      flex-direction: column;
      gap: 2rem;
      box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.5);
    }

    :host-context(body.light-theme) .login-box {
      background: rgba(255, 255, 255, 0.95);
      border: 1px solid rgba(0, 0, 0, 0.08);
      box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.15);
    }
    .login-header {
      text-align: center;
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
    }
    .logo-area {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 0.2rem;
    }
    .logo-icon {
        font-size: 3.5rem;
        width: 3.5rem;
        height: 3.5rem;
        color: var(--accent-color);
        margin-bottom: 0.5rem;
        font-family: 'Material Icons' !important;
        font-weight: normal;
        font-style: normal;
        line-height: 1;
        letter-spacing: normal;
        text-transform: none;
        display: inline-block;
        white-space: nowrap;
        word-wrap: normal;
        direction: ltr;
        -webkit-font-smoothing: antialiased;
        text-rendering: optimizeLegibility;
        -moz-osx-font-smoothing: grayscale;
        font-feature-settings: 'liga';
    }
    .logo-text {
        font-size: 1.8rem;
        font-weight: 800;
        letter-spacing: -0.05em;
        color: var(--text-main);
        .accent { color: var(--accent-color); }
        .v-tag {
            font-size: 0.8rem;
            vertical-align: super;
            background: rgba(129, 140, 248, 0.15);
            padding: 2px 6px;
            border-radius: 4px;
            margin-left: 4px;
        }
    }

    :host-context(body.light-theme) .logo-text .v-tag {
        background: rgba(79, 70, 229, 0.12);
    }

    .welcome-text {
      color: var(--text-main);
      font-size: 1.1rem;
      font-weight: 600;
      margin-top: 0.5rem;
    }
    .subtitle {
      color: var(--text-muted);
      font-size: 0.9rem;
    }
    .login-form {
      display: flex;
      flex-direction: column;
      gap: 1.5rem;
    }
    .form-options {
        display: flex;
        justify-content: flex-end;
        margin-top: -0.5rem;
    }
    .accent-link {
        color: var(--accent-color);
        text-decoration: none;
        font-weight: 600;
        transition: opacity 0.2s;
        &:hover { text-decoration: underline; opacity: 0.9; }
        &.small { font-size: 0.85rem; opacity: 0.8; }
    }
    .auth-error, .auth-success {
        padding: 0.75rem;
        border-radius: 10px;
        font-size: 0.85rem;
        text-align: center;
        border: 1px solid transparent;
        animation: slideDown 0.3s ease-out;
    }
    .auth-error {
        background: rgba(239, 68, 68, 0.1);
        color: #f87171;
        border-color: rgba(239, 68, 68, 0.2);
    }
    .auth-success {
        background: rgba(16, 185, 129, 0.1);
        color: #34d399;
        border-color: rgba(16, 185, 129, 0.2);
    }
    @keyframes slideDown {
        from { opacity: 0; transform: translateY(-10px); }
        to { opacity: 1; transform: translateY(0); }
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
      &:hover:not(:disabled) { transform: translateY(-2px); box-shadow: 0 10px 25px rgba(129, 140, 248, 0.4); }
      &:active:not(:disabled) { transform: translateY(0); }
      &:disabled { opacity: 0.6; cursor: not-allowed; }

      mat-icon {
        font-family: 'Material Icons' !important;
      }
    }

    :host-context(body.light-theme) .submit-btn {
      box-shadow: 0 8px 20px rgba(79, 70, 229, 0.25);

      &:hover:not(:disabled) {
        box-shadow: 0 10px 25px rgba(79, 70, 229, 0.35);
      }
    }
    .login-footer {
        text-align: center;
        font-size: 0.85rem;
        color: var(--text-muted);
        margin-top: 0.5rem;
    }
    .footer-links {
        display: flex;
        flex-direction: column;
        gap: 1rem;
    }
    .register-link {
        padding-top: 1rem;
        border-top: 1px solid rgba(255,255,255,0.05);
    }

    :host-context(body.light-theme) .register-link {
        border-top: 1px solid rgba(0, 0, 0, 0.08);
    }
    .sso-section {
        display: flex;
        flex-direction: column;
        gap: 1rem;
    }
    .sso-divider {
        display: flex;
        align-items: center;
        gap: 1rem;
        color: var(--text-muted);
        font-size: 0.85rem;
        &::before, &::after {
            content: '';
            flex: 1;
            height: 1px;
            background: rgba(255,255,255,0.1);
        }
    }
    :host-context(body.light-theme) .sso-divider::before,
    :host-context(body.light-theme) .sso-divider::after {
        background: rgba(0,0,0,0.1);
    }
    .sso-btn {
        height: 52px;
        width: 100%;
        background: transparent;
        color: var(--text-main);
        border: 1px solid rgba(255,255,255,0.15);
        font-size: 1rem;
        font-weight: 600;
        border-radius: 14px;
        cursor: pointer;
        display: flex;
        align-items: center;
        justify-content: center;
        gap: 0.5rem;
        transition: all 0.3s ease;
        span { display: flex; align-items: center; gap: 0.5rem; }
        mat-icon { font-family: 'Material Icons' !important; font-size: 1.2rem; }
        &:hover:not(:disabled) {
            background: rgba(255,255,255,0.05);
            border-color: var(--accent-color);
            color: var(--accent-color);
        }
        &:disabled { opacity: 0.6; cursor: not-allowed; }
    }
    :host-context(body.light-theme) .sso-btn {
        border-color: rgba(0,0,0,0.15);
        &:hover:not(:disabled) {
            background: rgba(79, 70, 229, 0.05);
        }
    }
  `]
})
export class LoginComponent implements OnInit {
  private fb = inject(FormBuilder);
  private auth = inject(AuthService);
  private router = inject(Router);
  private route = inject(ActivatedRoute);

  loading = signal(false);
  error = signal('');
  success = signal('');
  ssoEnabled = signal(false);
  ssoLoading = signal(false);

  loginForm = this.fb.group({
    username: ['', [Validators.required, Validators.minLength(3)]],
    password: ['', [Validators.required, Validators.minLength(8)]]
  });

  ngOnInit(): void {
    const reason = this.route.snapshot.queryParamMap.get('reason');
    if (reason === 'logout') {
      this.success.set('You have been signed out successfully.');
    } else if (reason === 'expired') {
      this.error.set('Your session expired. Please sign in again.');
    } else if (reason === 'sso-error') {
      this.error.set('SSO sign-in failed. Please try again or use local login.');
    }

    // ssoEnabled is already populated by APP_INITIALIZER before this page renders
    this.ssoEnabled.set(this.auth.ssoEnabled);
  }

  onSsoLogin(): void {
    if (this.ssoLoading()) return;
    this.ssoLoading.set(true);
    const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl') || '/';
    const safeUrl = this.getSafeReturnUrl(returnUrl);
    // Full page redirect — not Angular router — so Spring Security handles the OAuth2 initiation
    window.location.href = `/api/auth/sso/initiate?returnUrl=${encodeURIComponent(safeUrl)}`;
  }

  onLogin() {
    if (this.loginForm.invalid || this.loading()) return;

    this.loading.set(true);
    this.error.set('');
    this.success.set('');

    const credentials = this.loginForm.getRawValue() as { username: string, password: string };

    this.auth.login(credentials).subscribe({
      next: () => {
        this.success.set('Authenticated successfully. Redirecting...');
        const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl');
        const safeUrl = this.getSafeReturnUrl(returnUrl);
        setTimeout(() => this.router.navigateByUrl(safeUrl), 800);
      },
      error: (err: any) => {
        const message = err?.error?.error || err?.message || 'Invalid credentials or connection error.';
        this.error.set(message);
        this.loading.set(false);
        setTimeout(() => this.error.set(''), 5000);
      }
    });
  }

  private getSafeReturnUrl(returnUrl: string | null): string {
    if (!returnUrl) return '/';

    let decoded = returnUrl;
    try {
      decoded = decodeURIComponent(returnUrl);
    } catch {
      return '/';
    }

    const isInternalPath = decoded.startsWith('/');
    const isProtocolRelative = decoded.startsWith('//');
    const hasAbsoluteProtocol = decoded.includes('://');

    if (!isInternalPath || isProtocolRelative || hasAbsoluteProtocol) {
      return '/';
    }

    return decoded;
  }

  getControlError(controlName: string): any {
    const control = this.loginForm.get(controlName);
    if (control?.touched && control?.invalid) {
      if (control.hasError('required')) return `${controlName.charAt(0).toUpperCase() + controlName.slice(1)} is required`;
      if (control.hasError('minlength')) {
        const requiredLength = control.getError('minlength').requiredLength;
        return `Minimum ${requiredLength} characters required`;
      }
    }
    return null;
  }
}
