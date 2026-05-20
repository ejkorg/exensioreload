import { Component, Input, Output, EventEmitter, OnChanges, SimpleChanges, signal, WritableSignal, ElementRef, inject } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-glass-calendar',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="calendar-popup" role="dialog" aria-modal="true">
      <div class="cal-header">
        <button class="cal-nav" type="button" (click)="prevMonth()" aria-label="Previous month">‹</button>
        <div class="cal-month">{{ formatMonth(visibleMonth()) }}</div>
        <button class="cal-nav" type="button" (click)="nextMonth()" aria-label="Next month">›</button>
      </div>
      <div class="cal-weekdays" role="row">
        <div *ngFor="let wd of weekDays" class="wd" role="columnheader">{{ wd }}</div>
      </div>
      <div class="cal-days" role="grid" aria-live="polite">
        <div *ngFor="let day of getCalendarDays(visibleMonth()); let i = index"
             class="day"
             [class.other]="day.getMonth() !== visibleMonth().getMonth()"
             [class.focused]="i === focusedIndex()"
             [class.in-range]="isInRange(day)"
             [class.selected-start]="isStart(day)"
             [class.selected-end]="isEnd(day)"
             role="gridcell"
             [attr.aria-selected]="isStart(day) || isEnd(day) || isInRange(day)"
             [attr.aria-label]="formatIso(day)"
             tabindex="0"
             (click)="selectCalendarDate(day)"
             (keydown)="onDayKeydown($event, i)">
          {{ day.getDate() }}
        </div>
      </div>
      <div class="cal-footer">
        <button class="btn" type="button" (click)="selectToday()">Today</button>
        <button class="btn" type="button" (click)="requestClose()">Close</button>
      </div>
    </div>
  `,
  styles: [`
    .calendar-popup {
      position: absolute;
      top: 58px;
      left: 0;
      background: rgba(15,23,42,0.95);
      border: 1px solid rgba(255,255,255,0.08);
      padding: 0.5rem;
      border-radius: 10px;
      z-index: 20;
      animation: pop-in 0.18s ease-out;
    }

    @keyframes pop-in {
      from {
        opacity: 0;
        transform: scale(0.96);
      }
      to {
        opacity: 1;
        transform: scale(1);
      }
    }

    @media (prefers-reduced-motion: reduce) {
      .calendar-popup {
        animation: none;
      }
    }

    .cal-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.5rem;
      margin-bottom: 0.5rem;
    }

    .cal-nav {
      background: transparent;
      border: none;
      color: #fff;
      font-size: 1.25rem;
      cursor: pointer;
      padding: 0.25rem 0.5rem;
    }

    .cal-month {
      font-weight: 600;
      color: var(--accent-color);
    }

    .cal-weekdays {
      display: grid;
      grid-template-columns: repeat(7, 1fr);
      gap: 0.25rem;
      margin-bottom: 0.25rem;
      color: var(--text-muted);
      font-size: 0.75rem;
      text-align: center;
    }

    .cal-days {
      display: grid;
      grid-template-columns: repeat(7, 1fr);
      gap: 0.25rem;
      max-width: 320px;
    }

    .day {
      width: 36px;
      height: 36px;
      display: flex;
      align-items: center;
      justify-content: center;
      border-radius: 6px;
      cursor: pointer;
      color: #fff;
    }

    .day.focused {
      box-shadow: 0 0 0 2px rgba(129,140,248,0.12);
      background: rgba(129,140,248,0.08);
    }

    .day.selected-start {
      background: linear-gradient(135deg, rgba(129,140,248,0.18), rgba(99,102,241,0.12));
      border: 1px solid rgba(129,140,248,0.25);
    }

    .day.selected-end {
      background: linear-gradient(135deg, rgba(16,185,129,0.18), rgba(34,197,94,0.12));
      border: 1px solid rgba(16,185,129,0.25);
    }

    .day.in-range {
      background: linear-gradient(90deg, rgba(129,140,248,0.06), rgba(16,185,129,0.04));
      color: #fff;
    }

    .day.other {
      color: rgba(255,255,255,0.35);
    }

    .day:hover {
      background: rgba(255,255,255,0.06);
    }

    .cal-footer {
      display: flex;
      gap: 0.5rem;
      justify-content: flex-end;
      margin-top: 0.5rem;
    }

    .btn {
      background: rgba(255,255,255,0.03);
      border: 1px solid rgba(255,255,255,0.06);
      padding: 0.35rem 0.6rem;
      border-radius: 8px;
      color: #fff;
      cursor: pointer;
    }
  `]
})
export class GlassCalendarComponent implements OnChanges {
  @Input() month: Date | null = null;
  @Input() focusedDate: string | null = null;
  @Input() selectedStart: string | null = null;
  @Input() selectedEnd: string | null = null;

  @Output() dateSelected = new EventEmitter<Date>();
  @Output() close = new EventEmitter<void>();

  readonly monthSignal: WritableSignal<Date> = signal(new Date());
  readonly focusedIndex: WritableSignal<number> = signal(0);
  readonly weekDays = ['Su', 'Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa'];
  private readonly host = inject(ElementRef<HTMLElement>);

  ngOnChanges(changes: SimpleChanges) {
    if (changes['month']) {
      this.currentMonth.set(this.month ?? new Date());
    }
    if (changes['month'] || changes['focusedDate']) {
      this.syncFocusedIndex();
    }
  }

  formatMonth(date: Date): string {
    const opts: Intl.DateTimeFormatOptions = { month: 'long', year: 'numeric' };
    return date.toLocaleDateString(undefined, opts);
  }

  visibleMonth(): Date {
    return this.monthSignal();
  }

  private startOfMonth(date: Date) {
    return new Date(date.getFullYear(), date.getMonth(), 1);
  }

  private addMonths(date: Date, count: number) {
    return new Date(date.getFullYear(), date.getMonth() + count, 1);
  }

  prevMonth() {
    this.monthSignal.set(this.addMonths(this.monthSignal(), -1));
    this.syncFocusedIndex();
  }

  nextMonth() {
    this.monthSignal.set(this.addMonths(this.monthSignal(), 1));
    this.syncFocusedIndex();
  }

  getCalendarDays(month: Date): Date[] {
    const first = this.startOfMonth(new Date(month));
    const startDay = new Date(first);
    startDay.setDate(first.getDate() - first.getDay());
    const days: Date[] = [];
    for (let i = 0; i < 42; i++) {
      const day = new Date(startDay);
      day.setDate(startDay.getDate() + i);
      days.push(day);
    }
    return days;
  }

  private formatIso(date: Date): string {
    const mm = String(date.getMonth() + 1).padStart(2, '0');
    const dd = String(date.getDate()).padStart(2, '0');
    return `${date.getFullYear()}-${mm}-${dd}`;
  }

  isStart(day: Date): boolean {
    return !!this.selectedStart && this.formatIso(day) === this.selectedStart;
  }

  isEnd(day: Date): boolean {
    return !!this.selectedEnd && this.formatIso(day) === this.selectedEnd;
  }

  isInRange(day: Date): boolean {
    if (!this.selectedStart || !this.selectedEnd) {
      return false;
    }
    const start = this.parseDate(this.selectedStart)?.setHours(0,0,0,0) ?? 0;
    const end = this.parseDate(this.selectedEnd)?.setHours(0,0,0,0) ?? 0;
    const target = this.parseDate(this.formatIso(day))?.setHours(0,0,0,0) ?? 0;
    if (start <= end) {
      return start <= target && target <= end;
    }
    return end <= target && target <= start;
  }

  onDayKeydown(event: KeyboardEvent, index: number) {
    const key = event.key;
    if (key === 'ArrowLeft') { this.moveFocus(-1); event.preventDefault(); }
    if (key === 'ArrowRight') { this.moveFocus(1); event.preventDefault(); }
    if (key === 'ArrowUp') { this.moveFocus(-7); event.preventDefault(); }
    if (key === 'ArrowDown') { this.moveFocus(7); event.preventDefault(); }
    if (key === 'Enter' || key === ' ') {
      const days = this.getCalendarDays(this.visibleMonth());
      this.selectCalendarDate(days[index]);
      event.preventDefault();
    }
  }

  moveFocus(delta: number) {
    const days = this.getCalendarDays(this.visibleMonth());
    let idx = this.focusedIndex();
    let next = idx + delta;
    if (next < 0) {
      this.prevMonth();
      const newDays = this.getCalendarDays(this.visibleMonth());
      next = newDays.length + next;
    } else if (next >= days.length) {
      this.nextMonth();
      next -= days.length;
    }
    this.focusedIndex.set(next);
    setTimeout(() => this.focusDayElement(next));
  }

  private focusDayElement(idx: number) {
    const hostEl = this.host.nativeElement;
    const dayEls = hostEl.querySelectorAll('.calendar-popup .day');
    const el = dayEls[idx] as HTMLElement | undefined;
    if (el) {
      el.focus();
    }
  }

  selectCalendarDate(day: Date) {
    this.dateSelected.emit(day);
  }

  selectToday() {
    const today = new Date();
    this.currentMonth.set(this.startOfMonth(today));
    this.focusedIndex.set(this.indexOfDateInCalendar(today, this.currentMonth()));
    this.dateSelected.emit(today);
  }

  requestClose() {
    this.close.emit();
  }

  onCalendarKeydown(event: KeyboardEvent) {
    const key = event.key;
    if (key === 'ArrowLeft') this.currentMonth.set(this.addMonths(this.currentMonth(), -1));
    if (key === 'ArrowRight') this.currentMonth.set(this.addMonths(this.currentMonth(), 1));
    if (key === 'Enter') {
      const days = this.getCalendarDays(this.currentMonth());
      this.selectCalendarDate(days[0]);
    }
    this.syncFocusedIndex();
  }

  private parseDate(value: string | null): Date | null {
    return value ? new Date(value) : null;
  }

  private indexOfDateInCalendar(target: Date | null, month: Date): number {
    if (!target) return 0;
    const days = this.getCalendarDays(month);
    const iso = this.formatIso(target);
    for (let i = 0; i < days.length; i++) {
      if (this.formatIso(days[i]) === iso) {
        return i;
      }
    }
    return 0;
  }

  private syncFocusedIndex() {
    const target = this.parseDate(this.focusedDate);
    const idx = this.indexOfDateInCalendar(target, this.currentMonth());
    this.focusedIndex.set(idx);
    setTimeout(() => this.focusDayElement(idx));
  }
}
