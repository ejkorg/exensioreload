import { inject } from '@angular/core';
import { Router, Routes } from '@angular/router';
import { AuthGuard } from './auth/auth.guard';
import { AuthService } from './auth/auth.service';

export const routes: Routes = [
  {
    path: '',
    canActivate: [AuthGuard],
    children: [
      { path: '', loadComponent: () => import('./dashboard/dashboard.component').then((m) => m.DashboardComponent) },
      {
        path: 'site/:siteId/dashboard',
        loadComponent: () => import('./dashboard/site-dashboard.component').then((m) => m.SiteDashboardComponent),
      },
      { path: 'alerts', loadComponent: () => import('./alerts/alerts.component').then((m) => m.AlertsComponent) },
      {
        path: 'analytics',
        loadComponent: () => import('./analytics/analytics.component').then((m) => m.AnalyticsComponent),
      },
      {
        path: 'analytics/coverage',
        loadComponent: () => import('./analytics/coverage.component').then((m) => m.CoverageComponent),
      },
      { path: 'new', loadComponent: () => import('./stepper/stepper.component').then((m) => m.StepperComponent) },
      { path: 'edit/:id', loadComponent: () => import('./stepper/stepper.component').then((m) => m.StepperComponent) },
    ],
  },
  {
    path: 'my-sessions',
    canActivate: [AuthGuard],
    loadComponent: () => import('./my-sessions/my-sessions.component').then((m) => m.MySessionsComponent),
  },
  {
    path: 'admin/users',
    canActivate: [AuthGuard, () => inject(AuthService).isSuperAdmin()],
    loadComponent: () => import('./admin/user-list.component').then((m) => m.UserListComponent),
  },
  {
    path: 'admin/audit',
    canActivate: [AuthGuard, () => inject(AuthService).isAdmin()],
    loadComponent: () => import('./admin/audit-log-table.component').then((m) => m.AuditLogTableComponent),
  },
  {
    path: 'login',
    canActivate: [
      () => {
        const auth = inject(AuthService);
        const router = inject(Router);
        if (auth.isAuthenticated()) {
          return router.parseUrl('/');
        }
        return true;
      },
    ],
    loadComponent: () => import('./auth/login.component').then((m) => m.LoginComponent),
  },
  {
    path: 'register',
    loadComponent: () => import('./auth/register.component').then((m) => m.RegisterComponent),
  },
  {
    path: 'verify',
    loadComponent: () => import('./auth/verify.component').then((m) => m.VerifyComponent),
  },
  {
    path: 'request-reset',
    loadComponent: () => import('./auth/request-reset.component').then((m) => m.RequestResetComponent),
  },
  {
    path: 'reset-password',
    loadComponent: () => import('./auth/reset-password.component').then((m) => m.ResetPasswordComponent),
  },
  {
    path: 'sso-callback',
    loadComponent: () => import('./auth/sso-callback.component').then((m) => m.SsoCallbackComponent),
  },
  {
    path: 'oauth2',
    redirectTo: '/login',
  },
  {
    path: 'login/oauth2',
    redirectTo: '/login',
  },
  {
    path: '**',
    redirectTo: '/login',
  },
];
