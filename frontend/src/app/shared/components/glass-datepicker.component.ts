import { Component, Input, forwardRef, signal, ViewChild, ElementRef } from '@angular/core';
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
          #dateInputEl
          type="date"
          class="date-input"
          [value]="getDateInputValue()"
          (change)="onDateChange($event)"
          [disabled]="disabled"
        />
        <mat-icon class="calendar-icon" (click)="openDatePicker()">calendar_today</mat-icon>
      </div>

      <div class="time-input-group" *ngIf="includeTime">
        <input
          #timeInputEl
          type="time"
          class="time-input"
          [value]="getTimeInputValue()"
          (change)="onTimeChange($event)"
          [disabled]="disabled"
        />
        <mat-icon class="time-icon" (click)="openTimePicker()">schedule</mat-icon>
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
      gap: 0.4rem;
    }

    .floating-label {
      font-size: 0.72rem;
      font-weight: 700;
      color: rgba(167,139,250,0.85);
      letter-spacing: 0.05em;
      text-transform: uppercase;
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
      padding: 0 1rem 0 2.75rem;
      background: rgba(15, 12, 35, 0.6);
      border: 1px solid rgba(167, 139, 250, 0.2);
      border-radius: 12px;
      backdrop-filter: blur(12px);
      -webkit-backdrop-filter: blur(12px);
      color: var(--text-main, #e2e8f0);
      font-size: 0.9rem;
      outline: none;
      transition: border-color 0.2s ease, box-shadow 0.2s ease;
      height: 52px;
      box-sizing: border-box;
      cursor: pointer;
    }

    .date-input:hover,
    .time-input:hover {
      border-color: rgba(167, 139, 250, 0.4);
    }

    .date-input:focus,
    .time-input:focus {
      border-color: rgba(167, 139, 250, 0.65);
      box-shadow: 0 0 0 3px rgba(167, 139, 250, 0.12);
    }

    .date-input:disabled,
    .time-input:disabled {
      opacity: 0.4;
      cursor: not-allowed;
    }

    /* Hide the default browser calendar icon — we supply our own */
    .date-input::-webkit-calendar-picker-indicator,
    .time-input::-webkit-calendar-picker-indicator {
      opacity: 0;
      position: absolute;
      right: 0;
      width: 100%;
      height: 100%;
      cursor: pointer;
    }

    .calendar-icon,
    .time-icon {
      position: absolute;
      left: 0.75rem;
      color: rgba(167, 139, 250, 0.7);
      font-size: 1.1rem;
      width: 1.1rem;
      height: 1.1rem;
      cursor: pointer;
      pointer-events: auto;
      transition: color 0.2s ease;
    }

    .calendar-icon:hover,
    .time-icon:hover {
      color: #a78bfa;
    }

    .helper-text {
      font-size: 0.75rem;
      color: var(--text-muted);
      margin: 0;
    }

    .date-input,
    .time-input {
      color-scheme: dark;
    }
  `]
})
export class GlassDatepickerComponent implements ControlValueAccessor {
  @Input() label: string = '';
  @Input() helperText: string = '';
  @Input() includeTime: boolean = true;

  @ViewChild('dateInputEl') dateInputEl?: ElementRef<HTMLInputElement>;
  @ViewChild('timeInputEl') timeInputEl?: ElementRef<HTMLInputElement>;

  value = signal<string | null>(null);
  disabled: boolean = false;

  onChange: any = () => { };
  onTouched: any = () => { };

  openDatePicker() {
    const el = this.dateInputEl?.nativeElement;
    if (el && typeof el.showPicker === 'function') {
      try { el.showPicker(); } catch (e) { /* fallback: el.click() */ el.click(); }
    }
  }

  openTimePicker() {
    const el = this.timeInputEl?.nativeElement;
    if (el && typeof el.showPicker === 'function') {
      try { el.showPicker(); } catch (e) { el.click(); }
    }
  }

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
