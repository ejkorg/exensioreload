import { inject } from '@angular/core';
import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { AuthService } from './auth.service';
import { SessionExpiryService } from './session-expiry.service';
import { catchError, throwError } from 'rxjs';

export const AuthInterceptor: HttpInterceptorFn = (req, next) => {
    const auth = inject(AuthService);
    const sessionExpiryService = inject(SessionExpiryService);
    const token = auth.getToken();

    let authReq = req;
    if (token) {
        authReq = req.clone({
            setHeaders: {
                Authorization: `Bearer ${token}`
            }
        });
    }

    return next(authReq).pipe(
        catchError((error: HttpErrorResponse) => {
            if (error.status === 401) {
                // Session expired or invalid - notify via SessionExpiryService to show modal
                sessionExpiryService.notifyExpired();
            }
            return throwError(() => error);
        })
    );
};
