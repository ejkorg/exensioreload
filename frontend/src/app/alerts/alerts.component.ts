import { Component, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { AlertConfigurationComponent } from '../shared/components/alert-configuration.component';

@Component({
    selector: 'app-alerts',
    standalone: true,
    imports: [CommonModule, RouterModule, MatButtonModule, MatIconModule, AlertConfigurationComponent],
    template: `
        <div class="alerts-container">
            <header class="alerts-header">
                <div class="title-block">
                    <h1>Alert Center</h1>
                    <p class="subtitle">Configure notifications, thresholds, and active alerts.</p>
                </div>
                <div class="header-actions">
                    <button mat-stroked-button routerLink="/resender">
                        <mat-icon>dashboard</mat-icon>
                        Back to Dashboard
                    </button>
                </div>
            </header>

            <app-alert-configuration></app-alert-configuration>
        </div>
    `,
    styles: [`
        .alerts-container {
            max-width: 1400px;
            margin: 0 auto;
            padding: 2rem;
            display: flex;
            flex-direction: column;
            gap: 1.5rem;
            color: var(--text-primary);
        }

        .alerts-header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            gap: 1rem;
            flex-wrap: wrap;
            border-bottom: 1px solid var(--border-color);
            padding-bottom: 1rem;
        }

        .title-block h1 {
            margin: 0;
            font-size: 1.6rem;
        }

        .subtitle {
            margin: 0.3rem 0 0 0;
            color: var(--text-secondary);
        }

        @media (max-width: 640px) {
            .alerts-container { padding: 1.5rem; }
        }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class AlertsComponent {}