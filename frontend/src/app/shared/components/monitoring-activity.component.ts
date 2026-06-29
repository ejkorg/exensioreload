import { Component, Input, signal, effect, ElementRef, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivityEvent } from '../services/monitoring.service';
import { GlassIconComponent } from './glass-icon.component';

@Component({
  selector: 'app-monitoring-activity',
  standalone: true,
  imports: [CommonModule, GlassIconComponent],
  template: `
    <div class="activity-feed glass-panel">
      <div class="activity-header">
        <span class="activity-title">Recent Activity</span>
        <span class="activity-count">{{ activitiesSignal().length }} events</span>
      </div>

      <div class="activity-list" #activityList>
        <div class="activity-item"
             *ngFor="let activity of activitiesSignal(); trackBy: trackByTimestamp"
             [class]="'activity-' + activity.type.toLowerCase()">
          <div class="activity-icon">
            <app-glass-icon
              [name]="getActivityIcon(activity.type)"
              [size]="18"
              [color]="getActivityColor(activity.type)">
            </app-glass-icon>
          </div>
          <div class="activity-content">
            <div class="activity-message">{{ activity.message }}</div>
            <div class="activity-time">{{ formatTime(activity.timestamp) }}</div>
          </div>
        </div>

        <div class="empty-state" *ngIf="activitiesSignal().length === 0">
          <app-glass-icon name="history" [size]="32" color="muted"></app-glass-icon>
          <p>No activity yet</p>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .activity-feed {
      display: flex;
      flex-direction: column;
      height: 300px;
      overflow: hidden;
    }

    .activity-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 1rem;
      border-bottom: 1px solid rgba(255, 255, 255, 0.05);
    }

    .activity-title {
      font-size: 0.875rem;
      font-weight: 600;
      color: var(--text-main);
    }

    .activity-count {
      font-size: 0.75rem;
      color: var(--text-muted);
      padding: 0.25rem 0.5rem;
      background: rgba(255, 255, 255, 0.05);
      border-radius: 6px;
    }

    .activity-list {
      flex: 1;
      overflow-y: auto;
      padding: 0.5rem;
    }

    .activity-item {
      display: flex;
      gap: 0.75rem;
      padding: 0.75rem;
      border-radius: 8px;
      margin-bottom: 0.5rem;
      transition: background 0.15s ease;
      animation: slideIn 0.3s ease;
    }

    @keyframes slideIn {
      from {
        opacity: 0;
        transform: translateX(-10px);
      }
      to {
        opacity: 1;
        transform: translateX(0);
      }
    }

    .activity-item:hover {
      background: rgba(255, 255, 255, 0.03);
    }

    .activity-item.activity-file_completed {
      background: rgba(16, 185, 129, 0.05);
    }

    .activity-item.activity-file_failed {
      background: rgba(239, 68, 68, 0.05);
    }

    .activity-icon {
      width: 36px;
      height: 36px;
      border-radius: 8px;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
      background: rgba(255, 255, 255, 0.05);
    }

    .activity-file_started .activity-icon {
      background: rgba(129, 140, 248, 0.15);
    }

    .activity-file_completed .activity-icon {
      background: rgba(16, 185, 129, 0.15);
    }

    .activity-file_failed .activity-icon {
      background: rgba(239, 68, 68, 0.15);
    }

    .activity-content {
      flex: 1;
      min-width: 0;
    }

    .activity-message {
      font-size: 0.875rem;
      color: var(--text-main);
      line-height: 1.4;
      margin-bottom: 0.25rem;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .activity-time {
      font-size: 0.75rem;
      color: var(--text-muted);
    }

    .empty-state {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      padding: 2rem;
      color: var(--text-muted);
      height: 100%;
    }

    .empty-state p {
      margin-top: 0.5rem;
      font-size: 0.875rem;
    }

    /* Custom scrollbar */
    .activity-list::-webkit-scrollbar {
      width: 6px;
    }

    .activity-list::-webkit-scrollbar-track {
      background: rgba(255, 255, 255, 0.02);
      border-radius: 3px;
    }

    .activity-list::-webkit-scrollbar-thumb {
      background: rgba(255, 255, 255, 0.1);
      border-radius: 3px;
    }

    .activity-list::-webkit-scrollbar-thumb:hover {
      background: rgba(255, 255, 255, 0.15);
    }
  `]
})
export class MonitoringActivityComponent {
  @ViewChild('activityList') activityList?: ElementRef;

  private _activities = signal<ActivityEvent[]>([]);

  @Input() set activities(value: ActivityEvent[]) {
    this._activities.set(value);
  }

  // Expose as signal for template
  activitiesSignal = this._activities.asReadonly();

  private shouldAutoScroll = true;

  constructor() {
    // Auto-scroll to bottom when new activities arrive
    effect(() => {
      const activities = this._activities();
      if (activities.length > 0 && this.shouldAutoScroll) {
        setTimeout(() => this.scrollToBottom(), 100);
      }
    });
  }

  getActivityIcon(type: ActivityEvent['type']): string {
    switch (type) {
      case 'FILE_STARTED': return 'upload';
      case 'FILE_COMPLETED': return 'check_circle';
      case 'FILE_FAILED': return 'error';
      case 'STATUS_CHANGE': return 'refresh';
      case 'BATCH_COMPLETE': return 'done_all';
      default: return 'info';
    }
  }

  getActivityColor(type: ActivityEvent['type']): 'default' | 'primary' | 'success' | 'warning' | 'error' | 'muted' {
    switch (type) {
      case 'FILE_STARTED': return 'primary';
      case 'FILE_COMPLETED': return 'success';
      case 'FILE_FAILED': return 'error';
      case 'STATUS_CHANGE': return 'warning';
      case 'BATCH_COMPLETE': return 'success';
      default: return 'default';
    }
  }

  formatTime(timestamp: Date): string {
    const now = new Date();
    const diff = now.getTime() - timestamp.getTime();
    const seconds = Math.floor(diff / 1000);

    if (seconds < 60) return `${seconds}s ago`;
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `${minutes}m ago`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours}h ago`;

    return timestamp.toLocaleTimeString([], { timeZone: 'UTC' });
  }

  trackByTimestamp(_index: number, item: ActivityEvent): number {
    return item.timestamp.getTime();
  }

  private scrollToBottom() {
    if (this.activityList) {
      const element = this.activityList.nativeElement;
      element.scrollTop = element.scrollHeight;
    }
  }
}
