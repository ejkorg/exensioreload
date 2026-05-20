import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { GlassDialogRef } from '../shared/services/glass-dialog.service';
import { AuthService } from './auth.service';

@Component({
  selector: 'app-session-expired-modal',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="glass-panel session-expired-modal">
      <div class="modal-icon">🔒</div>
      <h2 class="modal-title">Session Expired</h2>

      <p class="modal-body">
        Your session has expired. Please log in again to continue.
      </p>

      <div class="modal-actions">
        <button class="btn btn-primary" (click)="onLogInAgain()">Log In Again</button>
      </div>
    </div>
  `,
  styles: [`
    .session-expired-modal {
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
      line-height: 1.5;
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
      max-width: 200px;
    }

    .btn-primary {
      background: var(--accent-color);
      color: white;
      box-shadow: 0 4px 12px rgba(129, 140, 248, 0.3);
    }

    .btn-primary:hover {
      transform: translateY(-1px);
      box-shadow: 0 6px 16px rgba(129, 140, 248, 0.4);
    }

    @media (max-width: 768px) {
      .session-expired-modal {
        padding: 2rem 1.5rem;
        max-width: 100%;
      }

      .btn {
        max-width: 100%;
      }
    }
  `]
})
export class SessionExpiredModalComponent {
  private readonly dialogRef = inject(GlassDialogRef);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);

  onLogInAgain(): void {
    const returnUrl = this.router.url;
    this.authService.clearSession();
    this.dialogRef.close();
    this.router.navigate(['/login'], { queryParams: { reason: 'expired', returnUrl } });
  }
}
