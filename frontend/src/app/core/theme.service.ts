import { Injectable, signal, effect } from '@angular/core';

export type ThemeMode = 'light' | 'dark';

@Injectable({
    providedIn: 'root'
})
export class ThemeService {
    private readonly THEME_KEY = 'dtp-hub-theme';

    // Signal to track current theme
    theme = signal<ThemeMode>(this.getInitialTheme());

    constructor() {
        // Effect to apply theme class to body and save to localStorage on change
        effect(() => {
            const currentTheme = this.theme();
            this.applyTheme(currentTheme);
            localStorage.setItem(this.THEME_KEY, currentTheme);
        });
    }

    toggleTheme() {
        this.theme.update(t => t === 'light' ? 'dark' : 'light');
    }

    private getInitialTheme(): ThemeMode {
        const saved = localStorage.getItem(this.THEME_KEY) as ThemeMode;
        if (saved === 'light' || saved === 'dark') return saved;

        // Default to dark theme
        return 'dark';
    }

    private applyTheme(theme: ThemeMode) {
        const body = document.body;
        if (theme === 'light') {
            body.classList.add('light-theme');
            body.classList.remove('dark-theme');
        } else {
            body.classList.add('dark-theme');
            body.classList.remove('light-theme');
        }
    }
}
