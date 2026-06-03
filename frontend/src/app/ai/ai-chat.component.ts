import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnDestroy,
  OnInit,
  ViewChild,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AiService } from './ai.service';
import { ChatMessage, SuggestedAction } from './ai.types';

@Component({
  selector: 'app-ai-chat',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatInputModule,
    MatFormFieldModule,
    MatTooltipModule,
    MatProgressSpinnerModule,
  ],
  templateUrl: './ai-chat.component.html',
  styleUrls: ['./ai-chat.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AiChatComponent implements OnInit, OnDestroy {
  readonly aiService = inject(AiService);

  @ViewChild('messageContainer') messageContainer!: ElementRef;
  @ViewChild('messageInput') messageInput!: ElementRef;

  // State signals
  isOpen = signal(false);
  isLoading = signal(false);
  inputMessage = signal('');
  error = signal<string | null>(null);

  // Computed values
  messages = computed(() => this.aiService.messages());
  isAvailable = computed(() => this.aiService.isAvailable());
  isEnabled = computed(() => this.aiService.isEnabled());

  private destroy$?: () => void;

  ngOnInit(): void {
    // Subscribe to service state changes
    this.aiService.checkStatus().subscribe({
      next: () => {},
      error: () => this.error.set('Failed to connect to AI service'),
    });
  }

  ngOnDestroy(): void {
    this.destroy$?.();
  }

  /**
   * Toggle chat panel visibility.
   */
  toggleChat(): void {
    this.isOpen.update((open) => !open);
    if (this.isOpen()) {
      setTimeout(() => this.messageInput?.nativeElement?.focus(), 100);
    }
  }

  /**
   * Close chat panel.
   */
  closeChat(): void {
    this.isOpen.set(false);
  }

  /**
   * Send a message to the AI.
   */
  sendMessage(): void {
    const message = this.inputMessage().trim();
    if (!message || this.isLoading()) return;

    this.isLoading.set(true);
    this.error.set(null);

    // Add user message to UI
    this.aiService.addUserMessage(message);
    this.inputMessage.set('');
    this.scrollToBottom();

    // Send to backend
    this.aiService.chat(message).subscribe({
      next: () => {
        this.isLoading.set(false);
        this.scrollToBottom();
      },
      error: (err) => {
        this.isLoading.set(false);
        this.error.set('Failed to get response. Please try again.');
        console.error('AI chat error:', err);
      },
    });
  }

  /**
   * Handle Enter key in input.
   */
  onKeyDown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.sendMessage();
    }
  }

  /**
   * Insert an example prompt.
   */
  insertExamplePrompt(prompt: string): void {
    this.inputMessage.set(prompt);
    this.messageInput?.nativeElement?.focus();
  }

  /**
   * Execute a suggested action.
   */
  executeAction(action: SuggestedAction): void {
    console.log('Executing action:', action);
    // Actions will be handled by the dashboard or appropriate component
    // For now, just log it - can be extended to navigate or trigger operations
  }

  /**
   * Clear conversation.
   */
  clearConversation(): void {
    this.aiService.clearConversation();
  }

  /**
   * Scroll to bottom of message container.
   */
  private scrollToBottom(): void {
    setTimeout(() => {
      if (this.messageContainer?.nativeElement) {
        const el = this.messageContainer.nativeElement;
        el.scrollTop = el.scrollHeight;
      }
    }, 50);
  }

  /**
   * Get message class based on role.
   */
  getMessageClass(role: 'user' | 'assistant'): string {
    return role === 'user' ? 'user-message' : 'assistant-message';
  }

  /**
   * Format timestamp for display.
   */
  formatTime(date: Date): string {
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }

  /**
   * Track message by ID for *ngFor.
   */
  trackByMessage(index: number, message: ChatMessage): string {
    return message.id;
  }
}
