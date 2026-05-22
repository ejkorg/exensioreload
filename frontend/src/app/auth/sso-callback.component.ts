import { Component, inject, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from './auth.service';

@Component({
  selector: 'app-sso-callback',
  standalone: true,
  template: `
    <div style="display:flex;align-items:center;justify-content:center;min-height:100vh;background:var(--bg-gradient-start,#0f172a)">
      <div style="color:var(--text-muted,#94a3b8);font-size:1rem;">Completing sign-in...</div>
    </div>
  `
})
export class SsoCallbackComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private auth = inject(AuthService);

  ngOnInit(): void {
    const token = this.route.snapshot.queryParamMap.get('token');
    const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl');

    if (!token) {
      this.router.navigate(['/login'], { queryParams: { reason: 'sso-error' } });
      return;
    }

    this.auth.handleSsoCallback(token).subscribe({
      next: () => this.router.navigateByUrl(this.getSafeReturnUrl(returnUrl)),
      error: () => this.router.navigate(['/login'], { queryParams: { reason: 'sso-error' } })
    });
  }

  private getSafeReturnUrl(returnUrl: string | null): string {
    if (!returnUrl) return '/';
    let decoded = returnUrl;
    try { decoded = decodeURIComponent(returnUrl); } catch { return '/'; }
    if (!decoded.startsWith('/') || decoded.startsWith('//') || decoded.includes('://')) {
      return '/';
    }
    return decoded;
  }
}
