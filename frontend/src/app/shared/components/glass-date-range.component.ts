import { Component, Input, forwardRef, signal, WritableSignal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ControlValueAccessor, NG_VALUE_ACCESSOR, ReactiveFormsModule, FormControl } from '@angular/forms';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatNativeDateModule } from '@angular/material/core';
import { MatInputModule } from '@angular/material/input';
import { MatFormFieldModule } from '@angular/material/form-field';

export interface DateRange {
  start: string | null;
  end: string | null;
}

@Component({
  selector: 'app-glass-date-range',
  standalone: true,
  imports: [
    CommonModule,
    ReactiveFormsModule,
    MatDatepickerModule,
    MatNativeDateModule,
    MatInputModule,
    MatFormFieldModule,
  ],
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => GlassDateRangeComponent),
      multi: true
    }
  ],
  template: `
    <div class="glass-date-range-container">
      <h4 *ngIf="label" class="range-label">{{ label }}</h4>

      <div class="date-range-inputs">

        <!-- Start date -->
        <div class="date-input-wrapper">
          <label class="input-label">From</label>
          <div class="date-input-group">
            <input
              class="date-input"
              placeholder="Select start date"
              [matDatepicker]="startPicker"
              [formControl]="startCtrl"
              (dateChange)="onStartChange($event)"
              readonly
            />
            <button class="cal-icon-btn" (click)="startPicker.open()" type="button" [disabled]="disabled">
              <svg viewBox="0 0 24 24" width="18" height="18" fill="none">
                <rect x="3" y="5" width="18" height="16" rx="2" stroke="currentColor" stroke-width="1.5"/>
                <path d="M16 3v4M8 3v4M3 9h18" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
              </svg>
            </button>
            <mat-datepicker #startPicker></mat-datepicker>
          </div>
        </div>

        <div class="separator"><span>→</span></div>

        <!-- End date -->
        <div class="date-input-wrapper">
          <label class="input-label">To</label>
          <div class="date-input-group">
            <input
              class="date-input"
              placeholder="Select end date"
              [matDatepicker]="endPicker"
              [formControl]="endCtrl"
              (dateChange)="onEndChange($event)"
              readonly
            />
            <button class="cal-icon-btn" (click)="endPicker.open()" type="button" [disabled]="disabled">
              <svg viewBox="0 0 24 24" width="18" height="18" fill="none">
                <rect x="3" y="5" width="18" height="16" rx="2" stroke="currentColor" stroke-width="1.5"/>
                <path d="M16 3v4M8 3v4M3 9h18" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
              </svg>
            </button>
            <mat-datepicker #endPicker></mat-datepicker>
          </div>
        </div>

      </div>

      <!-- Time inputs (optional) -->
      <div class="time-range-inputs" *ngIf="includeTime">
        <div class="time-input-wrapper">
          <label class="input-label">From Time</label>
          <input type="time" class="time-input" [value]="startTime()" (change)="onStartTimeChange($event)" [disabled]="disabled" />
        </div>
        <div class="separator"><span>→</span></div>
        <div class="time-input-wrapper">
          <label class="input-label">To Time</label>
          <input type="time" class="time-input" [value]="endTime()" (change)="onEndTimeChange($event)" [disabled]="disabled" />
        </div>
      </div>
    </div>
  `,
  styles: [`
    :host { display: block; width: 100%; }

    .glass-date-range-container {
      display: flex;
      flex-direction: column;
      gap: 1rem;
    }

    .range-label {
      font-size: 0.875rem;
      font-weight: 600;
      color: var(--accent-color);
      margin: 0;
    }

    .date-range-inputs,
    .time-range-inputs {
      display: flex;
      gap: 0.75rem;
      align-items: flex-end;
      flex-wrap: wrap;
    }

    .date-input-wrapper,
    .time-input-wrapper {
      display: flex;
      flex-direction: column;
      gap: 0.375rem;
      flex: 1;
      min-width: 140px;
    }

    .input-label {
      font-size: 0.6875rem;
      font-weight: 600;
      color: var(--text-muted);
      letter-spacing: 0.05em;
      text-transform: uppercase;
    }

    .date-input-group {
      position: relative;
      display: flex;
      align-items: center;
    }

    .date-input,
    .time-input {
      width: 100%;
      padding: 0.625rem 2.5rem 0.625rem 0.875rem;
      background: rgba(255, 255, 255, 0.03);
      border: 1px solid rgba(255, 255, 255, 0.08);
      border-radius: 12px;
      color: #fff;
      font-size: 0.875rem;
      outline: none;
      transition: border-color 0.2s;
      height: 44px;
      box-sizing: border-box;
      cursor: pointer;
    }

    :host-context(body.light-theme) .date-input,
    :host-context(body.light-theme) .time-input {
      background: rgba(255,255,255,0.9);
      border-color: rgba(0,0,0,0.15);
      color: var(--text-main);
    }

    .date-input::placeholder { color: rgba(255,255,255,0.3); }
    :host-context(body.light-theme) .date-input::placeholder { color: rgba(0,0,0,0.4); }

    .date-input:focus,
    .time-input:focus {
      border-color: var(--accent-color);
      box-shadow: 0 0 0 2px rgba(129,140,248,0.15);
    }

    .date-input:disabled,
    .time-input:disabled { opacity: 0.5; cursor: not-allowed; }

    /* Hide Material's default underline/outline — we style the input directly */
    ::ng-deep .mat-mdc-form-field { display: none !important; }

    .cal-icon-btn {
      position: absolute;
      right: 0.625rem;
      background: none;
      border: none;
      padding: 0;
      cursor: pointer;
      color: var(--text-muted);
      display: flex;
      align-items: center;
      line-height: 1;
    }

    .cal-icon-btn:disabled { opacity: 0.4; cursor: not-allowed; }

    .separator {
      display: flex;
      align-items: center;
      justify-content: center;
      color: var(--text-muted);
      margin-top: 1.25rem;
      opacity: 0.6;
    }

    /* Material datepicker popup — dark theme override */
    ::ng-deep .mat-datepicker-content {
      background: rgba(15, 23, 42, 0.98) !important;
      border: 1px solid rgba(255,255,255,0.1) !important;
      border-radius: 16px !important;
      box-shadow: 0 20px 60px rgba(0,0,0,0.5) !important;
      backdrop-filter: blur(20px);
    }

    ::ng-deep .mat-calendar-body-cell-content,
    ::ng-deep .mat-calendar-body-label,
    ::ng-deep .mat-calendar-period-button,
    ::ng-deep .mat-calendar-arrow,
    ::ng-deep .mat-calendar-next-button,
    ::ng-deep .mat-calendar-previous-button {
      color: #fff !important;
    }

    ::ng-deep .mat-calendar-body-selected {
      background-color: var(--accent-color) !important;
      color: #fff !important;
    }

    ::ng-deep .mat-calendar-body-today:not(.mat-calendar-body-selected) {
      border-color: var(--accent-color) !important;
    }

    ::ng-deep .mat-calendar-body-cell:hover .mat-calendar-body-cell-content {
      background: rgba(129,140,248,0.15) !important;
    }

    :host-context(body.light-theme) ::ng-deep .mat-datepicker-content {
      background: #fff !important;
      border-color: rgba(0,0,0,0.12) !important;
      box-shadow: 0 8px 32px rgba(0,0,0,0.15) !important;
    }

    :host-context(body.light-theme) ::ng-deep .mat-calendar-body-cell-content,
    :host-context(body.light-theme) ::ng-deep .mat-calendar-body-label,
    :host-context(body.light-theme) ::ng-deep .mat-calendar-period-button {
      color: var(--text-main) !important;
    }
  `]
})
export class GlassDateRangeComponent implements ControlValueAccessor {
  @Input() label: string = '';
  @Input() includeTime: boolean = true;
  @Input() disabled: boolean = false;

  readonly startCtrl = new FormControl<Date | null>(null);
  readonly endCtrl = new FormControl<Date | null>(null);
  readonly startTime: WritableSignal<string> = signal('00:00');
  readonly endTime: WritableSignal<string> = signal('23:59');

  onChange: (value: DateRange | null) => void = () => {};
  onTouched: () => void = () => {};

  onStartChange(event: any) {
    this.emitChange();
  }

  onEndChange(event: any) {
    this.emitChange();
  }

  onStartTimeChange(event: Event) {
    this.startTime.set((event.target as HTMLInputElement).value);
    this.emitChange();
  }

  onEndTimeChange(event: Event) {
    this.endTime.set((event.target as HTMLInputElement).value);
    this.emitChange();
  }

  private formatISO(date: Date | null): string | null {
    if (!date) return null;
    const mm = String(date.getMonth() + 1).padStart(2, '0');
    const dd = String(date.getDate()).padStart(2, '0');
    return `${date.getFullYear()}-${mm}-${dd}`;
  }

  private emitChange() {
    const start = this.formatISO(this.startCtrl.value);
    const end = this.formatISO(this.endCtrl.value);
    const value: DateRange = {
      start: start ? `${start} ${this.includeTime ? this.startTime() : '00:00'}:00` : null,
      end: end ? `${end} ${this.includeTime ? this.endTime() : '23:59'}:59` : null,
    };
    this.onChange(value);
    this.onTouched();
  }

  // ControlValueAccessor
  writeValue(val: any): void {
    if (val && typeof val === 'object') {
      if (val.start) {
        const [datePart, timePart] = val.start.split(' ');
        const d = new Date(datePart);
        if (!isNaN(d.getTime())) this.startCtrl.setValue(d, { emitEvent: false });
        if (timePart) this.startTime.set(timePart.substring(0, 5));
      }
      if (val.end) {
        const [datePart, timePart] = val.end.split(' ');
        const d = new Date(datePart);
        if (!isNaN(d.getTime())) this.endCtrl.setValue(d, { emitEvent: false });
        if (timePart) this.endTime.set(timePart.substring(0, 5));
      }
    } else {
      this.startCtrl.setValue(null, { emitEvent: false });
      this.endCtrl.setValue(null, { emitEvent: false });
    }
  }

  registerOnChange(fn: any): void { this.onChange = fn; }
  registerOnTouched(fn: any): void { this.onTouched = fn; }
  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    isDisabled ? this.startCtrl.disable() : this.startCtrl.enable();
    isDisabled ? this.endCtrl.disable() : this.endCtrl.enable();
  }
}
