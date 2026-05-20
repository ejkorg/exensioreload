import { Component, Input, forwardRef, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ControlValueAccessor, NG_VALUE_ACCESSOR, FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-glass-datepicker',
  standalone: true,
  imports: [CommonModule, MatIconModule, FormsModule],
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => GlassDatepickerComponent),
      multi: true
    }
  ],
  template: `
    <div class="glass-datepicker-container">
      <label *ngIf="label" class="floating-label">{{ label }}</label>

      <div class="date-input-group">
        <input
          type="date"
          class="date-input"
          [value]="getDateInputValue()"
          (change)="onDateChange($event)"
          [disabled]="disabled"
        />
        <mat-icon class="calendar-icon">calendar_today</mat-icon>
      </div>

      <div class="time-input-group" *ngIf="includeTime">
        <input
          type="time"
          class="time-input"
          [value]="getTimeInputValue()"
          (change)="onTimeChange($event)"
          [disabled]="disabled"
        />
        <mat-icon class="time-icon">schedule</mat-icon>
      </div>

      <p class="helper-text" *ngIf="helperText">{{ helperText }}</p>
    </div>
  `,
  styles: [`
    :host {
      display: block;
      width: 100%;
    }

    .glass-datepicker-container {
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
    }

    .floating-label {
      font-size: 0.75rem;
      font-weight: 600;
      color: var(--text-muted);
      letter-spacing: 0.02em;
      text-transform: uppercase;
      margin-bottom: 2px;
    }

    .date-input-group,
    .time-input-group {
      position: relative;
      display: flex;
      align-items: center;
    }

    .date-input,
    .time-input {
      width: 100%;
      padding: 0.75rem 1rem 0.75rem 2.5rem;
      background: rgba(255, 255, 255, 0.03);
      border: 1px solid rgba(255, 255, 255, 0.08);
      border-radius: 14px;
      backdrop-filter: blur(16px);
      -webkit-backdrop-filter: blur(16px);
      color: #fff;
      font-size: 1rem;
      outline: none;
      transition: all 0.3s ease;
      height: 52px;
      box-sizing: border-box;
    }

    .date-input:hover,
    .time-input:hover {
      background: rgba(255, 255, 255, 0.05);
      border-color: rgba(255, 255, 255, 0.15);
    }

    .date-input:focus,
    .time-input:focus {
      border-color: var(--accent-color);
      box-shadow: 0 0 20px rgba(129, 140, 248, 0.15);
      background: rgba(255, 255, 255, 0.05);
    }

    .date-input:disabled,
    .time-input:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }

    /* Style the calendar and time picker inputs */
    .date-input::-webkit-calendar-picker-indicator,
    .time-input::-webkit-calendar-picker-indicator {
      display: none;
    }

    .calendar-icon,
    .time-icon {
      position: absolute;
      left: 0.75rem;
      color: var(--text-muted);
      font-size: 1.25rem;
      width: 1.25rem;
      height: 1.25rem;
      pointer-events: none;
    }

    .helper-text {
      font-size: 0.75rem;
      color: var(--text-muted);
      margin: 0;
      margin-top: 0.25rem;
    }

    /* Webkit specific styling for calendar input */
    .date-input {
      color-scheme: dark;
    }

    .time-input {
      color-scheme: dark;
    }
  `]
})
export class GlassDatepickerComponent implements ControlValueAccessor {
  @Input() label: string = '';
  @Input() helperText: string = '';
  @Input() includeTime: boolean = true;

  value = signal<string | null>(null);
  disabled: boolean = false;

  onChange: any = () => { };
  onTouched: any = () => { };

  onDateChange(event: Event) {
    const input = event.target as HTMLInputElement;
    const dateStr = input.value; // YYYY-MM-DD

    if (this.includeTime) {
      const timeStr = this.getTimeInputValue();
      const fullValue = timeStr ? `${dateStr} ${timeStr}` : `${dateStr} 00:00`;
      this.value.set(fullValue);
    } else {
      this.value.set(dateStr);
    }

    this.onChange(this.value());
    this.onTouched();
  }

  onTimeChange(event: Event) {
    const input = event.target as HTMLInputElement;
    const timeStr = input.value; // HH:mm

    if (timeStr) {
      const dateStr = this.getDateInputValue();
      const fullValue = dateStr ? `${dateStr} ${timeStr}` : timeStr;
      this.value.set(fullValue);
    }

    this.onChange(this.value());
    this.onTouched();
  }

  getDateInputValue(): string {
    const current = this.value();
    if (!current) return '';
    return current.split(' ')[0]; // Extract YYYY-MM-DD
  }

  getTimeInputValue(): string {
    const current = this.value();
    if (!current || !this.includeTime) return '';
    const parts = current.split(' ');
    return parts.length > 1 ? parts[1] : '00:00'; // Extract HH:mm
  }

  // ControlValueAccessor
  writeValue(val: any): void {
    this.value.set(val);
  }

  registerOnChange(fn: any): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: any): void {
    this.onTouched = fn;
  }

  setDisabledState?(isDisabled: boolean): void {
    this.disabled = isDisabled;
  }
}
