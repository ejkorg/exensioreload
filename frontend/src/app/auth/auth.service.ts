import { HttpClient } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';
import {
  BehaviorSubject,
  catchError,
  finalize,
  map,
  Observable,
  of,
  shareReplay,
  Subscription,
  switchMap,
  throwError,
  timer,
} from 'rxjs';
import { environment } from '../../environments/environment';
import { SessionExpiryService } from './session-expiry.service';

interface AuthConfig {
  ssoEnabled: boolean;
}

export interface UserInfo {
  username: string;
  roles: string[];
}

interface LoginResponse {
  accessToken: string;
}

interface RefreshResponse {
  accessToken?: string;
}

@Injectable({
  providedIn: 'root',
})
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly sessionExpiryService = inject(SessionExpiryService);
  private readonly baseUrl = `${environment.apiUrl}/auth`;

  private userSubject = new BehaviorSubject<UserInfo | null>(null);
  user$ = this.userSubject.asObservable();

  // Token change observable for SSE reconnection
  private tokenSubject = new BehaviorSubject<string | null>(null);
  token$ = this.tokenSubject.asObservable();

  // Modern Signal state
  currentUser = signal<UserInfo | null>(null);

  private refreshTimerSub: Subscription | null = null;
  private accessToken: string | null = null;
  private refreshInFlight: Observable<boolean> | null = null;

  constructor() {
    // Don't initialize SSO flow if we're currently in an OAuth2 callback
    if (this.isOnOAuth2Route()) {
      return;
    }

    // Wire up the refresh callback so SessionExpiryService can trigger a silent
    // token refresh when activity dismisses the warning modal (avoids circular import).
    this.sessionExpiryService.setRefreshCallback(() => {
      this.refresh().subscribe();
    });

    // Restore only non-expired access tokens (expired tokens cause 401 loops after re-login).
    const stored = sessionStorage.getItem('accessToken') || localStorage.getItem('auth_token');
    if (stored && !this.isTokenExpired(stored)) {
      this.setSession(stored);
    } else if (stored) {
      sessionStorage.removeItem('accessToken');
      localStorage.removeItem('auth_token');
      localStorage.removeItem('auth_user');
    }

    // Open expired modal when session has fully expired
    // (warning modal is now opened directly inside SessionExpiryService.scheduleIdleTimers)
    this.sessionExpiryService.expired$.subscribe(() => {
      this.sessionExpiryService.openExpiredModal();
    });
  }

  login(credentials: { username: string; password: string }): Observable<void> {
    return this.http.post<LoginResponse>(`${this.baseUrl}/login`, credentials, { withCredentials: true }).pipe(
      switchMap((res) => {
        if (!res?.accessToken) {
          throw new Error('No access token in response');
        }
        this.sessionExpiryService.closeAllModals();
        this.setSession(res.accessToken);
        return this.loadMe();
      }),
      map((userInfo) => {
        if (!userInfo) {
          throw new Error('Failed to fetch user info after login');
        }
        return void 0;
      }),
      catchError((err) => {
        this.setSession(null);
        return throwError(() => err);
      }),
    );
  }

  logout(reason?: string) {
    this.http
      .post(`${this.baseUrl}/logout`, {}, { withCredentials: true })
      .pipe(
        catchError(() => of(null)),
        finalize(() => {
          this.setSession(null);
          const extras = reason ? { queryParams: { reason } } : undefined;
          this.router.navigate(['/login'], extras);
        }),
      )
      .subscribe();
  }

  private loadMe(): Observable<UserInfo | null> {
    return this.http.get<any>(`${this.baseUrl}/me`, { withCredentials: true }).pipe(
      map((res) => {
        if (res && res.username) {
          const info = { username: res.username, roles: this.normalizeRoles(res.roles) };
          console.log('[AuthService.loadMe] raw roles from /me:', res.roles, '→ normalized:', info.roles);
          this.userSubject.next(info);
          this.currentUser.set(info);
          return info;
        }
        return null;
      }),
      catchError(() => {
        this.setSession(null);
        return of(null);
      }),
    );
  }

  refresh(): Observable<boolean> {
    return this.http.post<RefreshResponse>(`${this.baseUrl}/refresh`, null, { withCredentials: true }).pipe(
      map((res) => {
        const token = res && res.accessToken ? res.accessToken : null;
        if (token) {
          this.setSession(token);
          return true;
        }
        this.setSession(null);
        return false;
      }),
      catchError(() => {
        this.setSession(null);
        return of(false);
      }),
    );
  }

  /**
   * Coalesced refresh used by the HTTP interceptor on 401 (avoids refresh storms).
   */
  tryRefreshSession(): Observable<boolean> {
    if (!this.refreshInFlight) {
      this.refreshInFlight = this.refresh().pipe(
        finalize(() => {
          this.refreshInFlight = null;
        }),
        shareReplay(1),
      );
    }
    return this.refreshInFlight;
  }

  private setSession(token: string | null) {
    this.cancelScheduledRefresh();
    this.accessToken = token;

    if (token) {
      sessionStorage.setItem('accessToken', token);
      localStorage.setItem('auth_token', token);
      this.tokenSubject.next(token); // Emit token change
      this.scheduleRefreshForToken(token);
      this.sessionExpiryService.startIdleTracking();
    } else {
      sessionStorage.removeItem('accessToken');
      localStorage.removeItem('auth_token');
      localStorage.removeItem('auth_user');
      this.tokenSubject.next(null); // Emit token cleared
      this.userSubject.next(null);
      this.currentUser.set(null);
      this.sessionExpiryService.stopIdleTracking();
    }
  }

  private scheduleRefreshForToken(token: string) {
    const exp = this.parseExpiry(token);
    if (!exp) return;
    const now = Math.floor(Date.now() / 1000);
    let refreshAt = Math.max(exp - 30, now + 1);
    const millis = (refreshAt - now) * 1000;
    this.refreshTimerSub = timer(millis).subscribe(() => {
      this.refresh().subscribe();
    });
  }

  private cancelScheduledRefresh() {
    if (this.refreshTimerSub) {
      this.refreshTimerSub.unsubscribe();
      this.refreshTimerSub = null;
    }
  }

  private parseExpiry(token: string): number | null {
    try {
      const payload = JSON.parse(atob(token.split('.')[1]));
      return payload?.exp || null;
    } catch {
      return null;
    }
  }

  private isTokenExpired(token: string): boolean {
    const exp = this.parseExpiry(token);
    if (!exp) {
      return true;
    }
    const now = Math.floor(Date.now() / 1000);
    return exp <= now;
  }

  private normalizeRoles(roles: any): string[] {
    if (!roles) return [];
    const arr = Array.isArray(roles) ? roles : [roles];
    return arr.map((r) => (String(r).startsWith('ROLE_') ? r.substring(5) : r));
  }

  /**
   * Clear the current session without navigating.
   * Used by SessionExpiredModalComponent so the modal can handle navigation itself.
   */
  clearSession(): void {
    this.setSession(null);
  }

  isAuthenticated(): boolean {
    return !!this.userSubject.value;
  }

  getToken(): string | null {
    return this.accessToken || localStorage.getItem('auth_token') || sessionStorage.getItem('accessToken');
  }

  isAdmin(): boolean {
    const roles = this.userSubject.value?.roles || [];
    return roles.includes('ADMIN') || roles.includes('SUPER_ADMIN');
  }

  /** Signal-based admin check — reactive, safe to use inside computed() */
  readonly isAdminSignal = computed(() => {
    const roles = this.currentUser()?.roles || [];
    return roles.includes('ADMIN') || roles.includes('SUPER_ADMIN');
  });

  isSuperAdmin(): boolean {
    return this.userSubject.value?.roles.includes('SUPER_ADMIN') ?? false;
  }

  register(username: string, email: string | null, password: string): Observable<any> {
    return this.http.post(`${this.baseUrl}/register`, { username, email, password });
  }

  verify(token: string): Observable<any> {
    return this.http.post(`${this.baseUrl}/verify`, { token });
  }

  requestPasswordReset(identifier: string): Observable<any> {
    // identifier can be username or email depending on backend implementation
    return this.http.post(`${this.baseUrl}/request-reset`, { username: identifier });
  }

  resetPassword(token: string, password: string): Observable<any> {
    return this.http.post(`${this.baseUrl}/reset-password`, { token, password });
  }

  handleSsoCallback(token: string): Observable<void> {
    this.sessionExpiryService.closeAllModals();
    this.setSession(token);
    return this.loadMe().pipe(
      map((userInfo) => {
        if (!userInfo) throw new Error('Failed to load user after SSO callback');
        return void 0;
      }),
    );
  }

  /**
   * Attempts to restore a session using the refresh cookie, then loads user info.
   * Returns true if a valid session was established, false otherwise.
   * Used by AuthGuard to handle the new-tab scenario without redirecting to login.
   */
  restoreSession(): Observable<boolean> {
    const token = this.getToken();
    if (this.isAuthenticated() && token && !this.isTokenExpired(token)) {
      return of(true);
    }
    if (token && this.isTokenExpired(token)) {
      this.setSession(null);
    }
    return this.refresh().pipe(
      switchMap((refreshed: boolean) => {
        if (!refreshed) return of(false);
        return this.loadMe().pipe(map((user) => !!user));
      }),
      catchError(() => of(false)),
    );
  }
  private _ssoEnabled = false;

  get ssoEnabled(): boolean {
    return this._ssoEnabled;
  }

  setSsoEnabled(value: boolean): void {
    this._ssoEnabled = value;
  }

  /**
   * Called by APP_INITIALIZER on startup.
   * 1. Attempts a silent token refresh using the HTTP-only refresh cookie.
   *    If successful the user is already authenticated — no login needed.
   * 2. Fetches /api/auth/config to populate ssoEnabled.
   * 3. If SSO is enabled and still no session, attempts a silent OIDC check.
   *
   * Awaiting this promise in APP_INITIALIZER means the router (and AuthGuard)
   * only run after auth state is fully resolved, so a new tab with a valid
   * refresh cookie lands directly on the requested page instead of /login.
   *
   * Requirements: 8.1, 8.2, 8.4, 8.5
   */
  initOnStartup(): Promise<void> {
    return new Promise<void>((resolve) => {
      // Step 1: try to restore session from refresh cookie
      this.refresh()
        .pipe(
          switchMap((refreshed: boolean) => {
            if (refreshed) {
              // Cookie was valid — load user info then fetch config
              return this.loadMe().pipe(
                switchMap(() =>
                  this.http
                    .get<AuthConfig>(`${this.baseUrl}/config`)
                    .pipe(catchError(() => of({ ssoEnabled: false } as AuthConfig))),
                ),
              );
            }
            // No valid cookie — just fetch config
            return this.http
              .get<AuthConfig>(`${this.baseUrl}/config`)
              .pipe(catchError(() => of({ ssoEnabled: false } as AuthConfig)));
          }),
          catchError(() => of({ ssoEnabled: false } as AuthConfig)),
        )
        .subscribe((config: AuthConfig) => {
          this._ssoEnabled = config?.ssoEnabled ?? false;
          // Step 3: silent SSO only if still not authenticated
          if (this._ssoEnabled && !this.isAuthenticated() && !this.isOnSsoCallbackRoute()) {
            const returnUrl = window.location.pathname + window.location.search;
            this.trySilentSso(returnUrl);
          }
          resolve();
        });
    });
  }

  /**
   * Attempt a silent OIDC authentication using the existing Azure AD browser session.
   * Only redirects when SSO is enabled and no local session is active.
   * Requirements: 8.1, 8.4
   */
  trySilentSso(returnUrl: string): void {
    if (!this._ssoEnabled || this.isAuthenticated()) {
      return;
    }
    const encoded = encodeURIComponent(returnUrl);
    window.location.href = `${environment.apiUrl}/auth/sso/silent?returnUrl=${encoded}`;
  }

  /**
   * Fetch SSO config then, if enabled, trigger a silent OIDC check.
   * Called once from the constructor after a failed refresh attempt.
   * Requirements: 8.1, 8.2, 8.4, 8.5
   */
  private initSilentSso(): void {
    this.http
      .get<AuthConfig>(`${this.baseUrl}/config`)
      .pipe(catchError(() => of({ ssoEnabled: false } as AuthConfig)))
      .subscribe((config: AuthConfig) => {
        this._ssoEnabled = config?.ssoEnabled ?? false;
        if (this._ssoEnabled && !this.isAuthenticated()) {
          const returnUrl = window.location.pathname + window.location.search;
          this.trySilentSso(returnUrl);
        }
      });
  }

  /**
   * Returns true when the current page is the SSO callback route.
   * Used to prevent triggering a silent SSO redirect on the callback page itself,
   * which would cause an infinite redirect loop.
   */
  private isOnSsoCallbackRoute(): boolean {
    return window.location.pathname.startsWith('/sso-callback');
  }

  /**
   * Checks if we're currently in an OAuth2 authorization flow.
   * Returns true if the current URL contains OAuth2 endpoints to prevent
   * token refresh attempts during the OAuth2 callback sequence.
   */
  private isOnOAuth2Route(): boolean {
    const path = window.location.pathname;
    return path.includes('/oauth2/authorization/') || path.includes('/login/oauth2/code/');
  }
}
