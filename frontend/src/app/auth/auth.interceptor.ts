import { inject } from '@angular/core';
import { HttpInterceptorFn, HttpErrorResponse, HttpRequest } from '@angular/common/http';
import { AuthService } from './auth.service';
import { SessionExpiryService } from './session-expiry.service';
import { catchError, switchMap, throwError } from 'rxjs';

const AUTH_SEGMENT = '/auth/';

function isAuthEndpoint(url: string): boolean {
  return url.includes(`${AUTH_SEGMENT}login`)
    || url.includes(`${AUTH_SEGMENT}refresh`)
    || url.includes(`${AUTH_SEGMENT}register`)
    || url.includes(`${AUTH_SEGMENT}logout`)
    || url.includes(`${AUTH_SEGMENT}config`)
    || url.includes(`${AUTH_SEGMENT}verify`)
    || url.includes(`${AUTH_SEGMENT}request-reset`)
    || url.includes(`${AUTH_SEGMENT}reset-password`);
}

function cloneWithToken(req: HttpRequest<unknown>, token: string | null): HttpRequest<unknown> {
  if (!token) {
    return req;
  }
  return req.clone({
    setHeaders: {
      Authorization: `Bearer ${token}`
    }
  });
}

function handleSessionExpired(
  auth: AuthService,
  sessionExpiryService: SessionExpiryService,
  error: HttpErrorResponse
) {
  auth.clearSession();
  if (!sessionExpiryService.isOnLoginRoute()) {
    sessionExpiryService.notifyExpired();
  }
  return throwError(() => error);
}

export const AuthInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const sessionExpiryService = inject(SessionExpiryService);
  const token = auth.getToken();

  const outbound = cloneWithToken(req, token);

  if (isAuthEndpoint(req.url)) {
    return next(outbound);
  }

  return next(outbound).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status !== 401) {
        return throwError(() => error);
      }

      // One silent refresh attempt, then retry the original request with the new access token.
      return auth.tryRefreshSession().pipe(
        switchMap((refreshed: boolean) => {
          if (!refreshed) {
            return handleSessionExpired(auth, sessionExpiryService, error);
          }
          const retry = cloneWithToken(req, auth.getToken());
          return next(retry);
        }),
        catchError((retryError: HttpErrorResponse) => {
          if (retryError.status === 401) {
            return handleSessionExpired(auth, sessionExpiryService, retryError);
          }
          return throwError(() => retryError);
        })
      );
    })
  );
};
