import { CommonModule } from '@angular/common';
import { Component, signal } from '@angular/core';

export interface ActivityFeedItem {
  id: string;
  timestamp: Date;
  label: string; // sender or context
  lot?: string;
  wafer?: string;
  oldState: string;
  newState: string;
}

const MAX_ITEMS = 20; // Requirement 11.1 / 11.3

/**
 * LiveActivityFeedComponent shows a bounded, real-time feed of pipeline state
 * transitions. The dashboard feeds it with STATE_AGGREGATION events via
 * {@link pushAggregation}; entries are color-coded by the destination state
 * (Requirement 11.5) and capped at 20 with the newest on top.
 */
@Component({
  selector: 'app-live-activity-feed',
  standalone: true,
  imports: [CommonModule],
  template: `
    <section class="activity-feed glass-panel">
      <div class="feed-header">
        <h3 class="feed-title">Live Activity</h3>
        <button type="button" class="feed-toggle" (click)="togglePause()" [attr.aria-pressed]="paused()">
          {{ paused() ? 'Resume' : 'Pause' }}
        </button>
      </div>
      <div class="feed-items" aria-live="polite">
        @for (item of items(); track item.id) {
          <div class="feed-item slide-in" [class]="eventColorClass(item.newState)">
            <span class="fi-time">{{ item.timestamp | date: 'HH:mm:ss' }}</span>
            <span class="fi-sep">•</span>
            <span class="fi-label">{{ item.label }}</span>
            @if (item.lot) {
              <span class="fi-sep">•</span>
              <span class="fi-lot">{{ item.lot }}/{{ item.wafer || '-' }}</span>
            }
            <span class="fi-sep">•</span>
            <span class="fi-transition">
              <span class="fi-old">{{ shortState(item.oldState) }}</span>
              <span class="fi-arrow">→</span>
              <span class="fi-new">{{ shortState(item.newState) }}</span>
            </span>
          </div>
        } @empty {
          <div class="feed-empty">Waiting for state transitions…</div>
        }
      </div>
    </section>
  `,
  styles: [
    `
      .activity-feed {
        display: flex;
        flex-direction: column;
        min-height: 120px;
        max-height: 360px;
      }
      .feed-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 0.6rem 0.875rem;
        border-bottom: 1px solid rgba(167, 139, 250, 0.14);
      }
      .feed-title {
        margin: 0;
        font-size: 0.78rem;
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.06em;
        color: rgba(226, 232, 255, 0.92);
      }
      .feed-toggle {
        height: 24px;
        padding: 0 0.6rem;
        border-radius: 999px;
        border: 1px solid rgba(167, 139, 250, 0.24);
        background: rgba(67, 56, 132, 0.35);
        color: rgba(226, 232, 255, 0.85);
        font-size: 0.68rem;
        font-weight: 600;
        cursor: pointer;
      }
      .feed-toggle:hover {
        background: rgba(99, 102, 241, 0.3);
      }
      .feed-items {
        display: flex;
        flex-direction: column;
        overflow-y: auto;
        padding: 0.375rem 0.5rem;
        gap: 0.15rem;
      }
      .feed-item {
        display: flex;
        align-items: center;
        gap: 0.35rem;
        padding: 0.3rem 0.5rem;
        border-radius: 8px;
        font-size: 0.72rem;
        white-space: nowrap;
        overflow: hidden;
      }
      .feed-item:hover {
        background: rgba(255, 255, 255, 0.03);
      }
      .feed-item.event-success {
        color: #34d399;
        border-left: 2px solid rgba(16, 185, 129, 0.6);
      }
      .feed-item.event-danger {
        color: #f87171;
        border-left: 2px solid rgba(239, 68, 68, 0.6);
      }
      .feed-item.event-warning {
        color: #fbbf24;
        border-left: 2px solid rgba(245, 158, 11, 0.6);
      }
      .feed-item.event-info {
        color: #93c5fd;
        border-left: 2px solid rgba(59, 130, 246, 0.6);
      }
      .fi-time {
        color: rgba(203, 213, 225, 0.55);
        font-family: 'JetBrains Mono', 'Fira Code', monospace;
        font-size: 0.65rem;
        flex-shrink: 0;
      }
      .fi-sep {
        color: rgba(203, 213, 225, 0.3);
        flex-shrink: 0;
      }
      .fi-label {
        font-weight: 600;
        overflow: hidden;
        text-overflow: ellipsis;
        flex-shrink: 0;
        max-width: 160px;
      }
      .fi-lot {
        font-family: 'JetBrains Mono', monospace;
        font-size: 0.68rem;
      }
      .fi-transition {
        display: inline-flex;
        align-items: center;
        gap: 0.25rem;
        font-weight: 600;
        margin-left: auto;
        flex-shrink: 0;
      }
      .fi-old {
        opacity: 0.6;
        text-decoration: line-through;
      }
      .fi-arrow {
        opacity: 0.5;
      }
      .fi-new {
        font-weight: 700;
      }
      .feed-empty {
        color: rgba(203, 213, 225, 0.45);
        font-size: 0.78rem;
        text-align: center;
        padding: 1.5rem 0;
      }
      .slide-in {
        animation: feed-slide-in 0.25s ease-out;
      }
      @keyframes feed-slide-in {
        from {
          opacity: 0;
          transform: translateY(-8px);
        }
        to {
          opacity: 1;
          transform: translateY(0);
        }
      }
    `,
  ],
})
export class LiveActivityFeedComponent {
  readonly items = signal<ActivityFeedItem[]>([]);
  readonly paused = signal(false);

  private seq = 0;

  /**
   * Feed a state-count aggregation event (from dashboard SSE).
   * Prepends one entry per state change, capped at MAX_ITEMS (Req 11.1-11.3).
   */
  pushAggregation(changes: { state: string; previousCount: number; newCount: number }[], senderLabel = 'pipeline'): void {
    if (this.paused()) return;

    const entries: ActivityFeedItem[] = changes.map((c) => ({
      id: `${Date.now()}-${++this.seq}`,
      timestamp: new Date(),
      label: senderLabel,
      oldState: c.previousCount > 0 ? `${c.state} (${c.previousCount})` : '—',
      newState: `${c.state} (${c.newCount})`,
    }));

    this.items.update((current) => [...entries.reverse(), ...current].slice(0, MAX_ITEMS));
  }

  /** Feed an individual per-file transition when the data source provides it. */
  pushFileTransition(item: Omit<ActivityFeedItem, 'id' | 'timestamp'>): void {
    if (this.paused()) return;
    const entry: ActivityFeedItem = { ...item, id: `${Date.now()}-${++this.seq}`, timestamp: new Date() };
    this.items.update((current) => [entry, ...current].slice(0, MAX_ITEMS));
  }

  togglePause(): void {
    this.paused.update((p) => !p);
  }

  eventColorClass(newState: string): string {
    const s = newState.toUpperCase();
    // Verification/timeout states win over generic COMPLETED matches.
    if (s.includes('TIMEOUT') || s.includes('VERIFICATION') || s.includes('VERIFY')) return 'event-warning';
    if (s.includes('FAILED') || s.includes('ERROR') || s.includes('CANCELLED')) return 'event-danger';
    if (s.includes('COMPLETED') || s.includes('DONE')) return 'event-success';
    return 'event-info';
  }

  /** Shorten verbose state names for feed display. */
  shortState(state: string): string {
    const label = state.includes('(') ? state.substring(0, state.indexOf('(')).trim() : state;
    switch (label) {
      case 'ELASTICSEARCH_MONITORING':
        return 'ENRICHMENT';
      case 'COMPLETED_MANUAL_VERIFICATION_REQUIRED':
        return 'VERIFY';
      case 'QUEUED_FOR_CP':
        return 'QUEUED';
      default:
        return label;
    }
  }
}
