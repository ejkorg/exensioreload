import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { NavigationEnd, NavigationStart, Router, RouterModule } from '@angular/router';
import { AiChatComponent } from './ai/ai-chat.component';
import { AuthService } from './auth/auth.service';
import { ThemeService } from './core/theme.service';
import { GlassIconComponent } from './shared/components/glass-icon.component';
import { ToastContainerComponent } from './shared/components/toast-container.component';

@Component({
    selector: 'app-root',
    standalone: true,
    imports: [CommonModule, RouterModule, ToastContainerComponent, GlassIconComponent, AiChatComponent],
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