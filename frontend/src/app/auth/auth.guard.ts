import { inject } from '@angular/core';
import { Router, CanActivateFn } from '@angular/router';
import { AuthService } from './auth.service';
import { map } from 'rxjs';

export const AuthGuard: CanActivateFn = () => {
    const authService = inject(AuthService);
    const router = inject(Router);

    // Fast path: user already loaded (same tab, or APP_INITIALIZER already ran)
    if (authService.isAuthenticated()) {
        return true;
    }

    // Async path: try to restore session via refresh cookie (new tab, page reload)
    return authService.restoreSession().pipe(
        map(authenticated => authenticated ? true : router.parseUrl('/login'))
    );
};
