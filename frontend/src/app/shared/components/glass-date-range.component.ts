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
    <div class="glass-date-range-container" [class.inline-layout]="inline">
      <h4 *ngIf="label && !inline" class="range-label">{{ label }}</h4>
      <label *ngIf="label && inline" class="input-label range-inline-label">{{ label }}</label>

      <div class="range-inputs-row" [class.with-time]="includeTime">
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

        <ng-container *ngIf="includeTime">
          <div class="separator"><span>→</span></div>

          <div class="time-input-wrapper">
            <label class="input-label">From Time</label>
            <input type="time" class="time-input" [value]="startTime()" (change)="onStartTimeChange($event)" [disabled]="disabled" />
          </div>

          <div class="separator"><span>→</span></div>

          <div class="time-input-wrapper">
            <label class="input-label">To Time</label>
            <input type="time" class="time-input" [value]="endTime()" (change)="onEndTimeChange($event)" [disabled]="disabled" />
          </div>
        </ng-container>
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

    .glass-date-range-container.inline-layout {
      gap: 0.375rem;
    }

    .range-label {
      font-size: 0.875rem;
      font-weight: 600;
      color: var(--accent-color);
      margin: 0;
    }

    .range-inline-label {
      display: block;
      margin-bottom: 0.125rem;
    }

    .range-inputs-row {
      display: flex;
      gap: 0.75rem;
      align-items: flex-end;
      flex-wrap: wrap;
    }

    .inline-layout .range-inputs-row {
      flex-wrap: nowrap;
      gap: 0.5rem;
    }

    .date-input-wrapper,
    .time-input-wrapper {
      display: flex;
      flex-direction: column;
      gap: 0.375rem;
      flex: 1;
      min-width: 140px;
    }

    .inline-layout .date-input-wrapper {
      flex: 1 1 130px;
      min-width: 120px;
    }

    .inline-layout .time-input-wrapper {
      flex: 0 1 110px;
      min-width: 100px;
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
      flex-shrink: 0;
    }

    .inline-layout .separator {
      margin-top: 1.25rem;
    }

    @media (max-width: 900px) {
      .inline-layout .range-inputs-row {
        flex-wrap: wrap;
      }
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
  @Input() inline: boolean = false;
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

  /**
   * Parse an HTML time input value (HH:mm or HH:mm:ss) into hour/minute parts.
   */
  private parseTimeParts(time: string): { hours: string; minutes: string } {
    const [rawHours = '00', rawMinutes = '00'] = time.split(':');
    return {
      hours: rawHours.padStart(2, '0'),
      minutes: rawMinutes.padStart(2, '0'),
    };
  }

  /**
   * Build a UTC ISO 8601 string from the date picker's local date plus the given time.
   * Takes the DATE COMPONENTS from the local Date object and treats them as UTC,
   * so "June 24" always maps to 2024-06-24T00:00:00Z regardless of browser timezone.
   */
  private toUtcIsoString(date: Date, hours: string, minutes: string, seconds: string): string {
    const y = date.getFullYear();
    const mo = String(date.getMonth() + 1).padStart(2, '0');
    const d = String(date.getDate()).padStart(2, '0');
    return `${y}-${mo}-${d}T${hours}:${minutes}:${seconds}Z`;
  }

  private emitChange() {
    const start = this.startCtrl.value;
    const end = this.endCtrl.value;
    const startParts = this.includeTime ? this.parseTimeParts(this.startTime()) : { hours: '00', minutes: '00' };
    const endParts = this.includeTime ? this.parseTimeParts(this.endTime()) : { hours: '23', minutes: '59' };
    const value: DateRange = {
      start: start ? this.toUtcIsoString(start, startParts.hours, startParts.minutes, '00') : null,
      end: end ? this.toUtcIsoString(end, endParts.hours, endParts.minutes, '59') : null,
    };
    this.onChange(value);
    this.onTouched();
  }

  // ControlValueAccessor
  writeValue(val: any): void {
    if (val && typeof val === 'object') {
      if (val.start) {
        const utc = new Date(val.start);
        if (!isNaN(utc.getTime())) {
          // Extract UTC date components and create a local-midnight Date so the picker
          // displays the same calendar date that was originally selected.
          const local = new Date(utc.getUTCFullYear(), utc.getUTCMonth(), utc.getUTCDate());
          this.startCtrl.setValue(local, { emitEvent: false });
        }
        this.startTime.set(this.extractTime(val.start, '00:00'));
      }
      if (val.end) {
        const utc = new Date(val.end);
        if (!isNaN(utc.getTime())) {
          const local = new Date(utc.getUTCFullYear(), utc.getUTCMonth(), utc.getUTCDate());
          this.endCtrl.setValue(local, { emitEvent: false });
        }
        this.endTime.set(this.extractTime(val.end, '23:59'));
      }
    } else {
      this.startCtrl.setValue(null, { emitEvent: false });
      this.endCtrl.setValue(null, { emitEvent: false });
    }
  }

  /** Extract HH:mm from a UTC ISO string like "2024-06-24T07:30:00Z". */
  private extractTime(iso: string, fallback: string): string {
    const match = iso.match(/T(\d{2}):(\d{2})/);
    return match ? `${match[1]}:${match[2]}` : fallback;
  }

  registerOnChange(fn: any): void { this.onChange = fn; }
  registerOnTouched(fn: any): void { this.onTouched = fn; }
  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    isDisabled ? this.startCtrl.disable() : this.startCtrl.enable();
    isDisabled ? this.endCtrl.disable() : this.endCtrl.enable();
  }
}
