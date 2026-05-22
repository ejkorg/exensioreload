import { Component, ChangeDetectionStrategy, computed, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterModule, ParamMap } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { BackendService, DashboardSnapshot, DashboardSiteSnapshot, DashboardSenderSnapshot } from '../api/backend.service';
import { Subscription, timer } from 'rxjs';
import { environment } from '../../environments/environment';

@Component({
    selector: 'app-site-dashboard',
    standalone: true,
    imports: [CommonModule, RouterModule, MatButtonModule, MatIconModule, MatTooltipModule],
    template: `
        <div class="site-dashboard">
            <header class="site-header">
                <div class="header-left">
                    <button mat-stroked-button routerLink="/" class="back-btn">
                        <mat-icon>arrow_back</mat-icon>
                        Back to Dashboard
                    </button>
                    <div class="title-block">
                        <h2>{{ siteId() || 'Site Dashboard' }}</h2>
                        <p class="subtitle" *ngIf="site()">{{ site()?.senders?.length ?? 0 }} senders • Live snapshot</p>
                    </div>
                </div>
                <button mat-stroked-button (click)="refresh()" [disabled]="loading()" class="refresh-btn">
                    <mat-icon>{{ loading() ? 'autorenew' : 'refresh' }}</mat-icon>
                    Refresh
                </button>
            </header>

            <div class="status-banner" *ngIf="error()">
                <mat-icon>error_outline</mat-icon>
                <span>{{ error() }}</span>
            </div>

            <section class="metrics" *ngIf="site() as s">
                <div class="metric-card">
                    <span class="label">Backlog</span>
                    <span class="value">{{ s.metrics.backlog | number }}</span>
                </div>
                <div class="metric-card">
                    <span class="label">Ready</span>
                    <span class="value">{{ s.metrics.ready | number }}</span>
                </div>
                <div class="metric-card">
                    <span class="label">Enqueued</span>
                    <span class="value">{{ s.metrics.enqueued | number }}</span>
                </div>
                <div class="metric-card">
                    <span class="label">Completed</span>
                    <span class="value">{{ s.metrics.completed | number }}</span>
                </div>
            </section>

            <section class="senders" *ngIf="site() as s">
                <h3>Senders</h3>
                <div class="sender-list">
                    <div class="sender-row" *ngFor="let sender of s.senders">
                        <div class="sender-info">
                            <h4>{{ sender.senderLabel }}</h4>
                            <span>ID: {{ sender.senderId }}</span>
                        </div>
                        <div class="sender-metrics">
                            <div class="metric">
                                <span class="label">Backlog</span>
                                <span class="value">{{ sender.metrics.backlog | number }}</span>
                            </div>
                            <div class="metric">
                                <span class="label">Ready</span>
                                <span class="value">{{ sender.metrics.ready | number }}</span>
                            </div>
                            <div class="metric">
                                <span class="label">Completed</span>
                                <span class="value">{{ sender.metrics.completed | number }}</span>
                            </div>
                        </div>
                        <div class="capacity" [matTooltip]="getBacklogTooltip(sender)">
                            <div class="track">
                                <div class="fill" [style.width.%]="getBacklogFillPercentage(sender)"></div>
                            </div>
                            <span class="cap-label">{{ sender.metrics.backlog | number }} / {{ getBacklogCapacity() | number }}</span>
                        </div>
                    </div>
                </div>
            </section>

            <div class="empty" *ngIf="!loading() && !site() && !error()">
                <mat-icon>info</mat-icon>
                <p>No site data found for {{ siteId() }}</p>
                <button mat-stroked-button routerLink="/">Back to Dashboard</button>
            </div>
        </div>
    `,
    styles: [`
        .site-dashboard {
            max-width: 1400px;
            margin: 0 auto;
            padding: 2rem;
            display: flex;
            flex-direction: column;
            gap: 2rem;
            color: var(--text-primary);
        }

        .site-header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            gap: 1.5rem;
            flex-wrap: wrap;
            border-bottom: 1px solid var(--border-color);
            padding-bottom: 1.5rem;
        }

        .header-left {
            display: flex;
            align-items: center;
            gap: 1rem;
            flex-wrap: wrap;
        }

        .title-block h2 {
            margin: 0;
            font-size: 2rem;
        }

        .subtitle {
            margin: 0.35rem 0 0 0;
            color: var(--text-secondary);
        }

        .status-banner {
            display: flex;
            gap: 0.75rem;
            align-items: center;
            padding: 0.75rem 1rem;
            border-radius: 12px;
            background: rgba(239, 68, 68, 0.1);
            border: 1px solid rgba(239, 68, 68, 0.3);
        }

        .metrics {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
            gap: 1rem;
        }

        .metric-card {
            background: rgba(255, 255, 255, 0.05);
            border: 1px solid rgba(255, 255, 255, 0.08);
            border-radius: 14px;
            padding: 1rem 1.25rem;
            display: flex;
            flex-direction: column;
            gap: 0.5rem;
        }

        .metric-card .label {
            font-size: 0.8rem;
            text-transform: uppercase;
            letter-spacing: 0.08em;
            color: var(--text-secondary);
        }

        .metric-card .value {
            font-size: 1.6rem;
            font-weight: 700;
        }

        .senders h3 {
            margin: 0 0 1rem 0;
        }

        .sender-list {
            display: flex;
            flex-direction: column;
            gap: 1rem;
        }

        .sender-row {
            display: grid;
            grid-template-columns: 1.2fr 2fr 1.2fr;
            gap: 1rem;
            padding: 1rem 1.25rem;
            background: rgba(255, 255, 255, 0.04);
            border: 1px solid rgba(255, 255, 255, 0.08);
            border-radius: 14px;
            align-items: center;
        }

        .sender-info h4 {
            margin: 0;
            font-size: 1.1rem;
        }

        .sender-info span {
            color: var(--text-secondary);
            font-size: 0.85rem;
        }

        .sender-metrics {
            display: grid;
            grid-template-columns: repeat(3, 1fr);
            gap: 0.75rem;
        }

        .sender-metrics .label {
            font-size: 0.75rem;
            color: var(--text-secondary);
            text-transform: uppercase;
        }

        .sender-metrics .value {
            font-weight: 700;
        }

        .capacity {
            display: flex;
            flex-direction: column;
            gap: 0.5rem;
        }

        .track {
            height: 6px;
            border-radius: 999px;
            background: rgba(148, 163, 184, 0.2);
            overflow: hidden;
        }

        .fill {
            height: 100%;
            background: linear-gradient(90deg, #10b981 0%, #f59e0b 50%, #ef4444 100%);
        }

        .cap-label {
            font-size: 0.8rem;
            color: var(--text-secondary);
            text-align: right;
        }

        .empty {
            display: flex;
            flex-direction: column;
            gap: 0.75rem;
            align-items: center;
            padding: 3rem 1rem;
            border-radius: 16px;
            border: 1px dashed rgba(255, 255, 255, 0.12);
        }

        :host-context(body.light-theme) .metric-card,
        :host-context(body.light-theme) .sender-row {
            background: rgba(255, 255, 255, 0.95);
            border: 1px solid rgba(15, 23, 42, 0.12);
        }

        :host-context(body.light-theme) .sender-info span,
        :host-context(body.light-theme) .sender-metrics .label,
        :host-context(body.light-theme) .metric-card .label,
        :host-context(body.light-theme) .subtitle,
        :host-context(body.light-theme) .cap-label {
            color: rgba(51, 65, 85, 0.9);
        }

        :host-context(body.light-theme) .empty {
            border-color: rgba(15, 23, 42, 0.12);
        }

        @media (max-width: 1024px) {
            .sender-row {
                grid-template-columns: 1fr;
            }
            .sender-metrics {
                grid-template-columns: repeat(2, 1fr);
            }
        }

        @media (max-width: 600px) {
            .site-dashboard { padding: 1.5rem; }
            .sender-metrics { grid-template-columns: 1fr; }
        }
    `],
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class SiteDashboardComponent {
    siteId = signal<string | null>(null);
    site = signal<DashboardSiteSnapshot | null>(null);
    loading = signal(false);
    error = signal<string | null>(null);
    lastUpdated = signal<Date | null>(null);

    private pollSub?: Subscription;

    constructor(private backend: BackendService, route: ActivatedRoute) {
        route.paramMap.subscribe((params: ParamMap) => {
            this.siteId.set(params.get('siteId'));
            this.refresh();
        });
        this.pollSub = timer(20000, 20000).subscribe(() => this.loadSnapshot(false));
    }

    refresh(): void {
        this.loadSnapshot(true);
    }

    private loadSnapshot(showLoading: boolean): void {
        const siteId = this.siteId();
        if (!siteId) return;
        if (showLoading) this.loading.set(true);

        this.backend.getDashboardSnapshot().subscribe({
            next: (snap: DashboardSnapshot) => {
                const match = snap.sites.find((s: DashboardSiteSnapshot) => s.site === siteId) ?? null;
                this.site.set(match);
                this.error.set(null);
                this.loading.set(false);
                this.lastUpdated.set(new Date());
            },
            error: () => {
                this.loading.set(false);
                this.error.set('Unable to load site dashboard data.');
            }
        });
    }

    getBacklogCapacity(): number {
        return environment.monitoring.monitorMaxRows || 1000;
    }

    getBacklogFillPercentage(sender: DashboardSenderSnapshot): number {
        const cap = this.getBacklogCapacity();
        if (cap <= 0) return 0;
        return Math.min((sender.metrics.backlog / cap) * 100, 100);
    }

    getBacklogTooltip(sender: DashboardSenderSnapshot): string {
        const cap = this.getBacklogCapacity();
        return `${sender.metrics.backlog.toLocaleString()} / ${cap.toLocaleString()} backlog`;
    }
}