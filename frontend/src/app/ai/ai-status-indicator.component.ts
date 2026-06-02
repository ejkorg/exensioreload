import { 
  Component, 
  OnInit, 
  OnDestroy,
  signal,
  inject,
  ChangeDetectionStrategy
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Subscription } from 'rxjs';
import { AiService } from './ai.service';

@Component({
  selector: 'app-ai-status-indicator',
  standalone: true,
  imports: [CommonModule, MatTooltipModule],
  template: `
    @if (isEnabled()) {
      <button 
        class="ai-status-btn"
        [class.online]="isOnline()"
        [class.offline]="!isOnline()"
        [class.loading]="isLoading()"
        [matTooltip]="getTooltip()"
        (click)="openChat()">
        <svg class="ai-icon" width="16" height="16" viewBox="0 0 24 24" fill="none">
          <path d="M12 2C6.48 2 2 6.48 2 12C2 17.52 6.48 22 12 22C17.52 22 22 17.52 22 12C22 6.48 17.52 2 12 2Z" 
                stroke="currentColor" stroke-width="2"/>
          <path d="M8 10C8.5 8.5 10 7 12 7C14 7 15.5 8.5 16 10" 
                stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
          <circle cx="9" cy="11.5" r="1" fill="currentColor"/>
          <circle cx="15" cy="11.5" r="1" fill="currentColor"/>
          <path d="M9 15H15" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
        </svg>
        <span class="status-text">AI</span>
      </button>
    }
  `,
  styles: [`
    .ai-status-btn {
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 6px 12px;
      border: 1px solid var(--border-color, #e2e8f0);
      border-radius: 6px;
      background: var(--surface-elevated, #ffffff);
      cursor: pointer;
      transition: all 0.15s ease;
      font-size: 12px;
      font-weight: 500;

      &:hover {
        background: var(--surface-hover, #f1f5f9);
        border-color: var(--color-primary, #6366f1);
      }

      &.online {
        .ai-icon {
          color: var(--color-success, #22c55e);
        }
      }

      &.offline {
        .ai-icon {
          color: var(--color-muted, #94a3b8);
        }
        
        .status-text {
          color: var(--text-secondary, #64748b);
        }
      }

      &.loading {
        .ai-icon {
          animation: pulse 1.5s ease-in-out infinite;
        }
      }
    }

    @keyframes pulse {
      0%, 100% { opacity: 1; }
      50% { opacity: 0.5; }
    }
  `],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class AiStatusIndicatorComponent implements OnInit, OnDestroy {
  private readonly aiService = inject(AiService);
  private subscription?: Subscription;
  private pollInterval?: ReturnType<typeof setInterval>;

  isOnline = signal(false);
  isLoading = signal(true);

  ngOnInit(): void {
    this.checkStatus();
    
    // Poll status every 30 seconds
    this.pollInterval = setInterval(() => this.checkStatus(), 30000);
  }

  ngOnDestroy(): void {
    this.subscription?.unsubscribe();
    if (this.pollInterval) {
      clearInterval(this.pollInterval);
    }
  }

  isEnabled(): boolean {
    return this.aiService.isEnabled();
  }

  checkStatus(): void {
    this.isLoading.set(true);
    
    this.subscription = this.aiService.getStatus().subscribe({
      next: (status) => {
        this.isOnline.set(status.enabled && status.available);
        this.isLoading.set(false);
      },
      error: () => {
        this.isOnline.set(false);
        this.isLoading.set(false);
      }
    });
  }

  getTooltip(): string {
    if (this.isLoading()) return 'Checking AI status...';
    if (this.isOnline()) return 'AI Assistant is online - Click to chat';
    return 'AI Assistant is unavailable';
  }

  openChat(): void {
    // Emit event to open chat panel
    // This will be handled by the parent component or a shared service
    const event = new CustomEvent('open-ai-chat');
    window.dispatchEvent(event);
  }
}