import { APP_INITIALIZER, ApplicationConfig, inject, provideZoneChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';

import { routes } from './app.routes';
import { AuthInterceptor } from './auth/auth.interceptor';
import { AuthService } from './auth/auth.service';

/**
 * APP_INITIALIZER: fetch /api/auth/config on startup so ssoEnabled is populated
 * before the login page renders, and silent SSO is attempted if appropriate.
 */
function authInitializerFactory(): () => Promise<void> {
    const auth = inject(AuthService);
    return () => auth.initOnStartup();
}

export const appConfig: ApplicationConfig = {
    providers: [
        provideZoneChangeDetection({ eventCoalescing: true }),
        provideRouter(routes),
        provideAnimationsAsync(),
        provideHttpClient(
            withFetch(),
            withInterceptors([AuthInterceptor])
        ),
        {
            provide: APP_INITIALIZER,
            useFactory: authInitializerFactory,
            multi: true
        }
    ]
};
