import { Component, signal, inject, effect } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule, Router, NavigationStart, NavigationEnd } from '@angular/router';
import { AuthService } from './auth/auth.service';
import { ThemeService } from './core/theme.service';
import { ToastContainerComponent } from './shared/components/toast-container.component';
import { GlassButtonComponent } from './shared/components/glass-button.component';
import { GlassIconComponent } from './shared/components/glass-icon.component';

@Component({
    selector: 'app-root',
    standalone: true,
    imports: [CommonModule, RouterModule, ToastContainerComponent, GlassButtonComponent, GlassIconComponent],
    templateUrl: './app.html',
    styleUrls: ['./app.scss']
})
export class App {
    private router = inject(Router);
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

    constructor() {
        // Listen for navigation start/end to show loading state on nav item
        this.router.events.subscribe(event => {
            if (event instanceof NavigationStart) {
                this.loadingNavPath.set(event.url);
            } else if (event instanceof NavigationEnd) {
                this.loadingNavPath.set(null);
            }
        });
    }

    logout() {
        this.auth.logout();
    }
}
