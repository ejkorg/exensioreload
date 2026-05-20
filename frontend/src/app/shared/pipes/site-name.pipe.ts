import { Pipe, PipeTransform, inject } from '@angular/core';
import { AuthService } from '../../auth/auth.service';

/**
 * Formats a site name for display.
 * - Non-admin users: strips the -PROD suffix (they only ever see PROD sites).
 * - Admin users: shows the full name including -PROD / -QA suffix so they can
 *   distinguish environments.
 *
 * Usage in template: {{ site.site | siteName }}
 * Usage in code:     formatSiteName(site, isAdmin)
 */
@Pipe({ name: 'siteName', standalone: true, pure: false })
export class SiteNamePipe implements PipeTransform {
    private auth = inject(AuthService);

    transform(value: string | null | undefined): string {
        return formatSiteName(value, this.auth.isAdminSignal());
    }
}

/**
 * Standalone helper — use this in component .ts files where a pipe isn't available.
 */
export function formatSiteName(site: string | null | undefined, isAdmin: boolean): string {
    if (!site) return '';
    // Strip -PROD and -QA suffixes for everyone — admins see the environment
    // via the separate env selector, non-admins only ever see PROD sites.
    return site.replace(/-(PROD|QA)$/i, '');
}
