import { Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';
import { Subject, Subscription, timer } from 'rxjs';
import { GlassDialogService, GlassDialogRef } from '../shared/services/glass-dialog.service';

/**
 * Pure helper — exported for easy unit/property testing.
 * Converts a number of seconds into a zero-padded MM:SS string.
 * Valid input range: 0 ≤ seconds ≤ 7199 (up to 1h 59m 59s).
 */
export function formatCountdown(seconds: number): string {
  const mm = Math.floor(seconds / 60);
  const ss = seconds % 60;
  return `${String(mm).padStart(2, '0')}:${String(ss).padStart(2, '0')}`;
}

/** Idle inactivity threshold before showing the warning modal (25 minutes) */
const IDLE_WARNING_MS = 25 * 60 * 1000;

/** Idle inactivity threshold before expiring the session (30 minutes) */
const IDLE_EXPIRE_MS = 30 * 60 * 1000;

/** Seconds shown in the warning modal countdown (5-minute window) */
const WARNING_WINDOW_SECONDS = 300;

@Injectable({ providedIn: 'root' })
export class SessionExpiryService {
  private readonly dialogService = inject(GlassDialogService);
  private readonly router = inject(Router);

  // Emits seconds remaining when warning threshold is crossed
  private readonly warningSubject = new Subject<number>();
  readonly warning$ = this.warningSubject.asObservable();

  // Emits void when session is confirmed expired
  private readonly expiredSubject = new Subject<void>();
  readonly expired$ = this.expiredSubject.asObservable();

  private idleWarningSub: Subscription | null = null;
  private idleExpirySub: Subscription | null = null;
  private activityHandler: (() => void) | null = null;

  private modalOpen = false;
  private activeModalType: 'warning' | 'expired' | null = null;
  private activeDialogRef: GlassDialogRef | null = null;

  /**
   * Start idle tracking: register activity listeners and schedule idle timers.
   * Called by AuthService.setSession when a new token is set.
   * Requirements: 1.1, 1.4, 6.3
   */
  startIdleTracking(): void {
    this.stopIdleTracking();
    this.scheduleIdleTimers();
    if (typeof document !== 'undefined') {
      this.activityHandler = () => this.onActivity();
      document.addEventListener('mousemove', this.activityHandler);
      document.addEventListener('mousedown', this.activityHandler);
      document.addEventListener('keydown', this.activityHandler);
      document.addEventListener('touchstart', this.activityHandler);
    }
  }

  /**
   * Stop idle tracking: remove activity listeners and cancel idle timers.
   * Called by AuthService.setSession(null) on logout.
   * Requirements: 1.4, 6.1, 6.2
   */
  stopIdleTracking(): void {
    this.cancelIdleTimers();
    if (typeof document !== 'undefined' && this.activityHandler) {
      document.removeEventListener('mousemove', this.activityHandler);
      document.removeEventListener('mousedown', this.activityHandler);
      document.removeEventListener('keydown', this.activityHandler);
      document.removeEventListener('touchstart', this.activityHandler);
      this.activityHandler = null;
    }
  }

  /**
   * Schedule (or reschedule) the idle warning and expiry timers.
   * Requirements: 2.1, 3.1
   */
  private scheduleIdleTimers(): void {
    this.cancelIdleTimers();

    this.idleWarningSub = timer(IDLE_WARNING_MS).subscribe(() => {
      this.warningSubject.next(WARNING_WINDOW_SECONDS);
      this.openWarningModal(WARNING_WINDOW_SECONDS);
    });

    this.idleExpirySub = timer(IDLE_EXPIRE_MS).subscribe(() => {
      this.expiredSubject.next();
      this.openExpiredModal();
    });
  }

  /**
   * Cancel both idle timers.
   * Requirements: 6.2
   */
  private cancelIdleTimers(): void {
    this.idleWarningSub?.unsubscribe();
    this.idleWarningSub = null;
    this.idleExpirySub?.unsubscribe();
    this.idleExpirySub = null;
  }

  /**
   * Handle a user activity event.
   * If the warning modal is open, close it and silently refresh the token.
   * Always reset the idle timers.
   * Requirements: 1.2, 1.3, 4.1, 4.2, 4.3
   */
  private onActivity(): void {
    if (this.modalOpen && this.activeModalType === 'warning') {
      this.activeDialogRef?.close();
      this.activeDialogRef = null;
      this.modalOpen = false;
      this.activeModalType = null;
      this._refreshCallback?.();
    }
    this.scheduleIdleTimers();
  }

  /**
   * Optional callback set by AuthService so onActivity can trigger a silent refresh
   * without a circular import.
   */
  private _refreshCallback: (() => void) | null = null;

  /**
   * Register a callback to be invoked when activity dismisses the warning modal.
   * Called once by AuthService during construction.
   */
  setRefreshCallback(cb: () => void): void {
    this._refreshCallback = cb;
  }

  /**
   * Emit a session-expired event.
   * Called by AuthInterceptor on 401 responses.
   */
  notifyExpired(): void {
    this.expiredSubject.next();
  }

  /** True when the user is already on the login (or SSO callback) route. */
  isOnLoginRoute(): boolean {
    const path = this.router.url.split('?')[0];
    return path === '/login'
      || path.startsWith('/login/')
      || path === '/sso-callback'
      || path.startsWith('/sso-callback/');
  }

  /**
   * Closes any open session modals and cancels idle timers.
   * Called after a successful login so a prior expiry does not block navigation.
   */
  closeAllModals(): void {
    this.cancelIdleTimers();
    if (this.activeDialogRef) {
      this.activeDialogRef.close();
      this.activeDialogRef = null;
    }
    this.modalOpen = false;
    this.activeModalType = null;
  }

  /**
   * Open the session warning modal if no modal is currently open.
   * Uses lazy import to avoid circular dependency with the modal component.
   */
  openWarningModal(secondsRemaining: number): void {
    if (this.modalOpen) return;
    this.modalOpen = true;
    this.activeModalType = 'warning';

    // Lazy-load the component to avoid circular dependency
    import('./session-warning-modal.component').then(m => {
      try {
        const ref = this.dialogService.open(m.SessionWarningModalComponent, {
          data: { secondsRemaining },
          disableClose: true
        });
        this.activeDialogRef = ref;

        // Reset flag when modal closes
        ref.afterClosed().then(() => {
          this.modalOpen = false;
          this.activeModalType = null;
          this.activeDialogRef = null;
        });
      } catch (err) {
        // Fallback: reset flag and force logout via router
        this.modalOpen = false;
        this.activeModalType = null;
        this.activeDialogRef = null;
        this.router.navigate(['/login']);
      }
    });
  }

  /**
   * Close any currently open modal and open the session expired modal.
   * If an expired modal is already open, the duplicate event is ignored.
   */
  openExpiredModal(): void {
    // If expired modal is already open, ignore duplicate expired events.
    if (this.modalOpen && this.activeModalType === 'expired') {
      return;
    }

    // Close any existing warning modal first (e.g. warning → expired transition)
    if (this.activeDialogRef && this.activeModalType === 'warning') {
      this.activeDialogRef.close();
      this.activeDialogRef = null;
      // Reset the flag so the expired modal can open immediately
      this.modalOpen = false;
      this.activeModalType = null;
    }

    if (this.modalOpen) return;
    this.modalOpen = true;
    this.activeModalType = 'expired';

    import('./session-expired-modal.component').then(m => {
      try {
        const ref = this.dialogService.open(m.SessionExpiredModalComponent, {
          disableClose: true
        });
        this.activeDialogRef = ref;

        ref.afterClosed().then(() => {
          this.modalOpen = false;
          this.activeModalType = null;
          this.activeDialogRef = null;
        });
      } catch (err) {
        this.modalOpen = false;
        this.activeModalType = null;
        this.activeDialogRef = null;
        this.router.navigate(['/login']);
      }
    });
  }
}
