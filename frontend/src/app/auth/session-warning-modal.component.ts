import { Component, OnInit, OnDestroy, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription } from 'rxjs';
import { filter } from 'rxjs/operators';
import { GlassDialogRef, GLASS_DIALOG_DATA } from '../shared/services/glass-dialog.service';
import { AuthService } from './auth.service';
import { SessionExpiryService, formatCountdown } from './session-expiry.service';
import { ToastService } from '../shared/services/toast.service';

@Component({
  selector: 'app-session-warning-modal',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="glass-panel session-warning-modal">
      <div class="modal-icon">⚠</div>
      <h2 class="modal-title">Session Expiring Soon</h2>

      <p class="modal-body">Your session will expire in</p>
      <div class="countdown">{{ countdownDisplay }}</div>
      <p class="modal-hint">Stay active to keep working, or log out now.</p>

      <div class="modal-actions">
        <button class="btn btn-secondary" (click)="onLogOut()">Log Out</button>
        <button class="btn btn-primary" (click)="onStayLoggedIn()" [disabled]="refreshing">
          {{ refreshing ? 'Extending...' : 'Stay Logged In' }}
        </button>
      </div>
    </div>
  `,
  styles: [`
    .session-warning-modal {
      padding: 2.5rem;
      max-width: 420px;
      width: 100%;
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 1rem;
      text-align: center;
    }

    .modal-icon {
      font-size: 2.5rem;
      line-height: 1;
    }

    .modal-title {
      font-size: 1.4rem;
      font-weight: 700;
      color: var(--text-main);
      margin: 0;
    }

    .modal-body {
      color: var(--text-muted);
      font-size: 0.95rem;
      margin: 0;
    }

    .countdown {
      font-size: 3rem;
      font-weight: 800;
      font-variant-numeric: tabular-nums;
      color: var(--accent-color);
      letter-spacing: 0.05em;
      line-height: 1;
    }

    .modal-hint {
      color: var(--text-muted);
      font-size: 0.85rem;
      margin: 0;
    }

    .modal-actions {
      display: flex;
      gap: 1rem;
      margin-top: 0.5rem;
      width: 100%;
      justify-content: center;
    }

    .btn {
      padding: 0.65rem 1.5rem;
      border-radius: 10px;
      font-size: 0.95rem;
      font-weight: 600;
      cursor: pointer;
      border: none;
      transition: all 0.2s ease;
      flex: 1;
    }

    .btn:disabled {
      opacity: 0.6;
      cursor: not-allowed;
    }

    .btn-secondary {
      background: rgba(255, 255, 255, 0.08);
      color: var(--text-main);
      border: 1px solid rgba(255, 255, 255, 0.12);
    }

    .btn-secondary:hover:not(:disabled) {
      background: rgba(255, 255, 255, 0.14);
    }

    .btn-primary {
      background: var(--accent-color);
      color: white;
      box-shadow: 0 4px 12px rgba(129, 140, 248, 0.3);
    }

    .btn-primary:hover:not(:disabled) {
      transform: translateY(-1px);
      box-shadow: 0 6px 16px rgba(129, 140, 248, 0.4);
    }

    @media (max-width: 768px) {
      .session-warning-modal {
        padding: 2rem 1.5rem;
        max-width: 100%;
      }

      .modal-actions {
        flex-direction: column;
      }
    }
  `]
})
export class SessionWarningModalComponent implements OnInit, OnDestroy {
  private readonly dialogRef = inject(GlassDialogRef);
  private readonly data: { secondsRemaining: number } = inject(GLASS_DIALOG_DATA as any);
  private readonly authService = inject(AuthService);
  private readonly sessionExpiryService = inject(SessionExpiryService);
  private readonly toastService = inject(ToastService);

  secondsRemaining: number = 0;
  countdownDisplay: string = '00:00';
  refreshing = false;

  private intervalId: ReturnType<typeof setInterval> | null = null;
  private tokenSub: Subscription | null = null;
  private initialToken: string | null = null;

  ngOnInit(): void {
    this.secondsRemaining = this.data?.secondsRemaining ?? 120;
    this.countdownDisplay = formatCountdown(this.secondsRemaining);
    this.initialToken = this.authService.getToken();

    // Decrement counter every second
    this.intervalId = setInterval(() => {
      this.secondsRemaining = Math.max(0, this.secondsRemaining - 1);
      this.countdownDisplay = formatCountdown(this.secondsRemaining);

      if (this.secondsRemaining === 0) {
        this.clearInterval();
        this.dialogRef.close();
        this.sessionExpiryService.notifyExpired();
      }
    }, 1000);

    // Auto-close if a new token arrives (background refresh succeeded)
    this.tokenSub = this.authService.token$
      .pipe(filter((token: string | null): token is string => token !== null))
      .subscribe((token: string) => {
        if (token === this.initialToken) return;
        this.clearInterval();
        this.dialogRef.close();
      });
  }

  ngOnDestroy(): void {
    this.clearInterval();
    this.tokenSub?.unsubscribe();
  }

  onStayLoggedIn(): void {
    if (this.refreshing) return;
    this.refreshing = true;

    this.authService.refresh().subscribe({
      next: (success: boolean) => {
        this.refreshing = false;
        if (success) {
          this.clearInterval();
          this.dialogRef.close();
          this.toastService.success('Session extended');
        } else {
          this.clearInterval();
          this.dialogRef.close();
          this.sessionExpiryService.notifyExpired();
        }
      },
      error: () => {
        this.refreshing = false;
        this.clearInterval();
        this.dialogRef.close();
        this.sessionExpiryService.notifyExpired();
      }
    });
  }

  loggingOut = false;

  onLogOut(): void {
    if (this.loggingOut) return;
    this.loggingOut = true;
    this.clearInterval();
    // Close the modal immediately so the user isn't staring at it during the POST
    this.dialogRef.close();
    this.authService.logout('logout');
  }

  private clearInterval(): void {
    if (this.intervalId !== null) {
      clearInterval(this.intervalId);
      this.intervalId = null;
    }
  }
}
