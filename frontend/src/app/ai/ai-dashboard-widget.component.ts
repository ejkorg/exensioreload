import { 
  Component, 
  OnInit, 
  OnDestroy, 
  signal, 
  computed,
  ChangeDetectionStrategy,
  inject
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AiService } from './ai.service';
import { AiSummarizeResponse } from './ai.types';

@Component({
  selector: 'app-ai-dashboard-widget',
  standalone: true,
  imports: [
    CommonModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTooltipModule
  ],
  templateUrl: './ai-dashboard-widget.component.html',
  styleUrls: ['./ai-dashboard-widget.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class AiDashboardWidgetComponent implements OnInit, OnDestroy {
  private readonly aiService = inject(AiService);
  
  // State
  isExpanded = signal(false);
  isLoading = signal(false);
  summary = signal<AiSummarizeResponse | null>(null);
  error = signal<string | null>(null);
  
  // Computed
  isAvailable = computed(() => this.aiService.isAvailable());
  isEnabled = computed(() => this.aiService.isEnabled());

  ngOnInit(): void {
    if (this.isAvailable()) {
      this.loadSummary();
    }
  }

  ngOnDestroy(): void {
    // Cleanup if needed
  }

  /**
   * Toggle widget expansion.
   */
  toggleExpand(): void {
    this.isExpanded.update(v => !v);
  }

  /**
   * Load AI summary.
   */
  loadSummary(): void {
    if (!this.isAvailable() || this.isLoading()) return;
    
    this.isLoading.set(true);
    this.error.set(null);
    
    // For demo, we'll call with sample alert data
    // In production, this would fetch real alerts from the backend
    const sampleAlerts = this.getSampleAlerts();
    
    this.aiService.summarizeAlerts(sampleAlerts).subscribe({
      next: (response) => {
        this.summary.set(response);
        this.isLoading.set(false);
      },
      error: (err) => {
        this.error.set('Failed to load AI summary');
        this.isLoading.set(false);
        console.error('AI summary error:', err);
      }
    });
  }

  /**
   * Refresh summary.
   */
  refresh(): void {
    this.loadSummary();
  }

  /**
   * Get sample alerts for demo.
   */
  private getSampleAlerts(): { sender: string; error: string; timestamp: string; severity: string; lotId?: string }[] {
    return [
      { sender: 'SENDER_A', error: 'Connection timeout', timestamp: new Date().toISOString(), severity: 'HIGH' },
      { sender: 'SENDER_B', error: 'Auth failure', timestamp: new Date().toISOString(), severity: 'MEDIUM' },
    ];
  }

  /**
   * Get priority color.
   */
  getPriorityColor(priority: string): string {
    switch (priority?.toUpperCase()) {
      case 'CRITICAL': return 'var(--color-error, #ef4444)';
      case 'HIGH': return 'var(--color-warning, #f59e0b)';
      case 'MEDIUM': return 'var(--color-info, #3b82f6)';
      default: return 'var(--color-muted, #64748b)';
    }
  }

  /**
   * Get priority icon.
   */
  getPriorityIcon(priority: string): string {
    switch (priority?.toUpperCase()) {
      case 'CRITICAL': return 'error';
      case 'HIGH': return 'warning';
      case 'MEDIUM': return 'info';
      default: return 'check_circle';
    }
  }
}