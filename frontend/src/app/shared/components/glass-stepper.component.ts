import { Component, Input, Output, EventEmitter, signal, WritableSignal } from '@angular/core';
import { CommonModule } from '@angular/common';

export interface GlassStep {
  label: string;
  completed: boolean;
  editable: boolean;
}

@Component({
  selector: 'app-glass-stepper',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="glass-stepper">
      <div class="stepper-header">
        <div class="title-group" *ngIf="title">
          <h3>{{ title }}</h3>
          <p class="subtitle" *ngIf="subtitle">{{ subtitle }}</p>
        </div>
        <div class="stepper-progress">
          <ng-container *ngFor="let step of steps; let i = index">
            <div class="step-indicator"
                 [class.active]="i === _selectedIndex()"
                 [class.completed]="step.completed && i !== _selectedIndex()"
                 [class.clickable]="step.editable || step.completed"
                 (click)="onStepClick(i)">
              <span class="step-num">
                <svg *ngIf="step.completed && i !== _selectedIndex()" viewBox="0 0 24 24" width="14" height="14">
                  <path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z" fill="currentColor"/>
                </svg>
                <span *ngIf="!step.completed || i === _selectedIndex()">{{ i + 1 }}</span>
              </span>
              <span class="step-label">{{ step.label }}</span>
            </div>
            <div class="step-connector" *ngIf="i < steps.length - 1" [class.done]="step.completed"></div>
          </ng-container>
        </div>
      </div>

      <div class="stepper-content">
        <ng-content></ng-content>
      </div>
    </div>
  `,
  styles: [`
    .glass-stepper {
      display: flex;
      flex-direction: column;
      gap: 1.5rem;
    }

    .stepper-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 2rem;
      margin-bottom: 0.5rem;
    }

    .title-group h3 {
      margin: 0;
      font-size: 1rem;
      font-weight: 700;
      letter-spacing: -0.01em;
    }

    .subtitle {
      margin: 0.15rem 0 0 0;
      color: var(--text-muted);
      font-size: 0.78rem;
    }

    .stepper-progress {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      flex-shrink: 0;
    }

    .step-indicator {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 0.4rem;
      opacity: 0.4;
      transition: all 0.3s;
    }

    .step-indicator.active  { opacity: 1; }
    .step-indicator.completed { color: var(--success, #10b981); opacity: 0.85; }
    .step-indicator.clickable { cursor: pointer; }

    .step-num {
      width: 28px;
      height: 28px;
      border-radius: 50%;
      border: 2px solid currentColor;
      display: grid;
      place-items: center;
      font-size: 0.75rem;
      font-weight: 700;
      transition: all 0.3s;
    }

    .step-indicator.active .step-num {
      border-color: var(--accent-color);
      color: var(--accent-color);
    }

    .step-indicator.completed .step-num {
      background: var(--success, #10b981);
      color: #fff;
      border-color: var(--success, #10b981);
    }

    .step-label {
      font-size: 0.65rem;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      white-space: nowrap;
    }

    .step-indicator.active .step-label  { color: var(--accent-color); }
    .step-indicator.completed .step-label { color: var(--success, #10b981); }

    .step-connector {
      width: 40px;
      height: 2px;
      background: rgba(255, 255, 255, 0.1);
      margin-top: -12px;
      flex-shrink: 0;
      transition: background 0.3s;
    }

    .step-connector.done {
      background: rgba(16, 185, 129, 0.4);
    }

    .stepper-content {
      min-height: 400px;
    }

    @media (max-width: 768px) {
      .stepper-header { flex-direction: column; align-items: flex-start; }
      .step-connector { width: 24px; }
    }

    @media (max-width: 480px) {
      .step-label { display: none; }
    }
  `]
})
export class GlassStepperComponent {
  @Input() steps: GlassStep[] = [];
  @Input() linear: boolean = true;
  @Input() title: string = '';
  @Input() subtitle: string = '';
  @Input() set selectedIndex(val: number) {
    this._selectedIndex.set(val ?? 0);
  }
  @Output() selectionChange = new EventEmitter<number>();

  _selectedIndex: WritableSignal<number> = signal(0);

  onStepClick(index: number) {
    const step = this.steps[index];
    if (this.linear) {
      const canNavigate = step.completed || step.editable || index === this._selectedIndex() + 1;
      if (!canNavigate) return;
    }
    this._selectedIndex.set(index);
    this.selectionChange.emit(index);
  }

  next() {
    const nextIndex = this._selectedIndex() + 1;
    if (nextIndex < this.steps.length) {
      this._selectedIndex.set(nextIndex);
      this.selectionChange.emit(nextIndex);
    }
  }

  previous() {
    const prevIndex = this._selectedIndex() - 1;
    if (prevIndex >= 0) {
      this._selectedIndex.set(prevIndex);
      this.selectionChange.emit(prevIndex);
    }
  }

  goTo(index: number) {
    if (index >= 0 && index < this.steps.length) {
      this.onStepClick(index);
    }
  }
}
