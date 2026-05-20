import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { AuthService } from './auth.service';

@Component({
    selector: 'app-verify',
    standalone: true,
    imports: [
        CommonModule,
        RouterModule,
        MatCardModule,
        MatButtonModule,
        MatIconModule,
        MatProgressSpinnerModule,
        MatSnackBarModule
    ],
    template: `
    <div class="auth-viewport">
      <div class="auth-box glass-panel text-center">
        <div class="auth-header">
           <mat-icon class="logo-icon" [class.success]="status === 'success'" [class.error]="status === 'error'">
             {{ status === 'loading' ? 'hourglass_empty' : status === 'success' ? 'verified_user' : 'error_outline' }}
           </mat-icon>
           <h1>Account <span class="accent">Verification</span></h1>
           <p class="subtitle" *ngIf="status === 'loading'">Validating your orchestration credentials...</p>
           <p class="subtitle success-text" *ngIf="status === 'success'">Your account has been verified successfully!</p>
           <p class="subtitle error-text" *ngIf="status === 'error'">Verification failed. The link may be expired or invalid.</p>
        </div>

        <div class="verify-actions">
          <mat-spinner *ngIf="status === 'loading'" diameter="40" class="mx-auto"></mat-spinner>

          <button mat-flat-button color="primary" *ngIf="status === 'success'" routerLink="/login" class="full-width">
            Go to Login
          </button>

          <div *ngIf="status === 'error'" class="error-actions">
            <button mat-stroked-button color="warn" routerLink="/register" class="full-width">
              Try Registering Again
            </button>
            <a routerLink="/login" class="accent-link block mt-4">Back to Login</a>
          </div>
        </div>
      </div>
    </div>
  `,
    styles: [`
    .auth-viewport {
      display: flex;
      align-items: center;
      justify-content: center;
      min-height: 100vh;
      background: linear-gradient(135deg, #0f172a 0%, #1e1b4b 100%);
    }
    .auth-box {
      width: 100%;
      max-width: 400px;
      padding: 3rem;
      display: flex;
      flex-direction: column;
      gap: 2rem;
      text-align: center;
    }
    .logo-icon { font-size: 4rem; width: 4rem; height: 4rem; color: var(--text-muted); margin-bottom: 1rem; }
    .logo-icon.success { color: #00ff96; }
    .logo-icon.error { color: #ff3232; }
    .full-width { width: 100%; height: 52px; border-radius: 12px; font-weight: 600; }
    .success-text { color: #00ff96 !important; }
    .error-text { color: #ff3232 !important; }
    .error-actions { display: flex; flex-direction: column; gap: 1rem; }
    .block { display: block; }
    .mt-4 { margin-top: 1rem; }
  `]
})
export class VerifyComponent implements OnInit {
    private route = inject(ActivatedRoute);
    private auth = inject(AuthService);
    private snackBar = inject(MatSnackBar);

    status: 'loading' | 'success' | 'error' = 'loading';

    ngOnInit(): void {
        const token = this.route.snapshot.queryParamMap.get('token');
        if (!token) {
            this.status = 'error';
            return;
        }

        this.auth.verify(token).subscribe({
            next: () => {
                this.status = 'success';
                this.snackBar.open('Account verified!', 'Close', { duration: 3000 });
            },
            error: () => {
                this.status = 'error';
            }
        });
    }
}
