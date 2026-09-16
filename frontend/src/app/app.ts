import { CommonModule } from '@angular/common';
import { Component, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { NavigationEnd, NavigationStart, Router, RouterModule } from '@angular/router';
import { Subscription } from 'rxjs';
import { AiChatComponent } from './ai/ai-chat.component';
import { BackendService } from './api/backend.service';
import { AuthService } from './auth/auth.service';
import { ThemeService } from './core/theme.service';
import { GlassIconComponent } from './shared/components/glass-icon.component';
import { ToastContainerComponent } from './shared/components/toast-container.component';

const ACTIVE_STATUSES = new Set(['STAGING', 'IN_PROGRESS', 'QUEUED', 'PROCESSING']);

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, RouterModule, ToastContainerComponent, GlassIconComponent, AiChatComponent],
  templateUrl: './app.html',
  styleUrls: ['./app.scss'],
})
export class App implements OnInit, OnDestroy {
  private router = inject(Router);
  private backend = inject(BackendService);
  public auth = inject(AuthService);
  public themeService = inject(ThemeService);

  navItems = [
    { label: 'Dashboard', icon: 'dashboard', path: '/', exact: true },
    { label: 'Analytics', icon: 'insights', path: '/analytics', exact: false },
    { label: 'My Sessions', icon: 'history', path: '/my-sessions', exact: false },
    { label: 'Users', icon: 'people', path: '/admin/users', admin: true, exact: false },
  ];

  isNavExpanded = false;
  loadingNavPath = signal<string | null>(null);
  /** Live count of active (non-terminal) sessions for the navbar badge. */
  activeSessionCount = signal<number>(0);

  private routerSub?: Subscription;
  private sessionPollInterval?: ReturnType<typeof setInterval>;

  constructor() {
    this.routerSub = this.router.events.subscribe((event) => {
      if (event instanceof NavigationStart) {
        this.loadingNavPath.set(event.url);
      } else if (event instanceof NavigationEnd) {
        this.loadingNavPath.set(null);
        // Refresh count after any navigation so badge stays current
        this.refreshSessionCount();
      }
    });
  }

  ngOnInit(): void {
    this.refreshSessionCount();
    // Poll every 30s — lightweight, just counts active sessions
    this.sessionPollInterval = setInterval(() => this.refreshSessionCount(), 30_000);
  }

  ngOnDestroy(): void {
    this.routerSub?.unsubscribe();
    if (this.sessionPollInterval) clearInterval(this.sessionPollInterval);
  }

  private refreshSessionCount(): void {
    // Only poll when a user is logged in
    if (!this.auth.isInitialized()) return;
    this.backend.getStagingSessions(0, 200, {}).subscribe({
      next: (page) => {
        const count = (page.items || []).filter((s) => ACTIVE_STATUSES.has((s.status || '').toUpperCase())).length;
        this.activeSessionCount.set(count);
      },
      error: () => {
        /* silently ignore — badge simply stays at last known value */
      },
    });
  }

  logout() {
    this.auth.logout();
  }
}
