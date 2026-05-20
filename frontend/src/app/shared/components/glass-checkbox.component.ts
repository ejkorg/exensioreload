import { Component, Input, Output, EventEmitter, forwardRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';

@Component({
  selector: 'app-glass-checkbox',
  standalone: true,
  imports: [CommonModule],
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => GlassCheckboxComponent),
      multi: true
    }
  ],
  template: `
    <label class="glass-checkbox" [class.disabled]="disabled" [class.checked]="checked" [class.indeterminate]="indeterminate">
      <input
        type="checkbox"
        [checked]="checked"
        [disabled]="disabled"
        [indeterminate]="indeterminate"
        (change)="onCheckboxChange($event)"
        class="checkbox-input"
      />
      <span class="checkbox-box">
        <svg *ngIf="checked && !indeterminate" class="check-icon" viewBox="0 0 24 24" width="16" height="16">
          <path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z" fill="currentColor"/>
        </svg>
        <svg *ngIf="indeterminate" class="indeterminate-icon" viewBox="0 0 24 24" width="16" height="16">
          <path d="M19 13H5v-2h14v2z" fill="currentColor"/>
        </svg>
      </span>
      <span class="checkbox-label" *ngIf="label">{{ label }}</span>
    </label>
  `,
  styles: [`
    .glass-checkbox {
      display: inline-flex;
      align-items: center;
      gap: 0.5rem;
      cursor: pointer;
      user-select: none;
      position: relative;
    }

    .glass-checkbox.disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }

    .checkbox-input {
      position: absolute;
      opacity: 0;
      width: 0;
      height: 0;
    }

    .checkbox-box {
      width: 20px;
      height: 20px;
      border-radius: 6px;
      background: rgba(255, 255, 255, 0.05);
      border: 2px solid rgba(255, 255, 255, 0.15);
      display: flex;
      align-items: center;
      justify-content: center;
      transition: all 0.2s ease;
      flex-shrink: 0;
    }

    :host-context(body.light-theme) .checkbox-box {
      background: rgba(255, 255, 255, 0.9);
      border: 2px solid rgba(0, 0, 0, 0.2);
    }

    .glass-checkbox:hover:not(.disabled) .checkbox-box {
      background: rgba(255, 255, 255, 0.08);
      border-color: rgba(255, 255, 255, 0.25);
      transform: scale(1.05);
    }

    :host-context(body.light-theme) .glass-checkbox:hover:not(.disabled) .checkbox-box {
      background: rgba(255, 255, 255, 1);
      border-color: rgba(0, 0, 0, 0.3);
    }

    .glass-checkbox.checked .checkbox-box {
      background: linear-gradient(135deg, rgba(129, 140, 248, 0.3), rgba(99, 102, 241, 0.2));
      border-color: var(--accent-color);
      box-shadow: 0 0 12px rgba(129, 140, 248, 0.3);
    }

    :host-context(body.light-theme) .glass-checkbox.checked .checkbox-box {
      background: linear-gradient(135deg, rgba(79, 70, 229, 0.9), rgba(79, 70, 229, 0.8));
      border-color: var(--accent-color);
      box-shadow: 0 0 8px rgba(79, 70, 229, 0.2);
    }

    .glass-checkbox.indeterminate .checkbox-box {
      background: linear-gradient(135deg, rgba(129, 140, 248, 0.25), rgba(99, 102, 241, 0.15));
      border-color: var(--accent-color);
    }

    :host-context(body.light-theme) .glass-checkbox.indeterminate .checkbox-box {
      background: linear-gradient(135deg, rgba(79, 70, 229, 0.8), rgba(79, 70, 229, 0.7));
      border-color: var(--accent-color);
    }

    .check-icon,
    .indeterminate-icon {
      color: #fff;
      animation: checkIn 0.2s ease-out;
    }

    @keyframes checkIn {
      from {
        opacity: 0;
        transform: scale(0.5);
      }
      to {
        opacity: 1;
        transform: scale(1);
      }
    }

    .checkbox-label {
      font-size: 0.875rem;
      color: var(--text-main);
      font-weight: 500;
    }

    .glass-checkbox:focus-within .checkbox-box {
      box-shadow: 0 0 0 3px rgba(129, 140, 248, 0.15);
    }
  `]
})
export class GlassCheckboxComponent implements ControlValueAccessor {
  @Input() label: string = '';
  @Input() checked: boolean = false;
  @Input() indeterminate: boolean = false;
  @Input() disabled: boolean = false;
  @Output() change = new EventEmitter<boolean>();
  @Output() checkedChange = new EventEmitter<boolean>(); // Add for Angular convention

  onChange: (value: boolean) => void = () => {};
  onTouched: () => void = () => {};

  onCheckboxChange(event: Event) {
    if (this.disabled) return;

    const input = event.target as HTMLInputElement;
    this.checked = input.checked;
    this.indeterminate = false;

    this.onChange(this.checked);
    this.onTouched();
    this.change.emit(this.checked);
    this.checkedChange.emit(this.checked); // Emit both events
  }

  writeValue(value: boolean): void {
    this.checked = value;
  }

  registerOnChange(fn: any): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: any): void {
    this.onTouched = fn;
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
  }
}
