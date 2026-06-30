import {
  Component,
  Input,
  forwardRef,
  signal,
  computed,
  ElementRef,
  ViewChild,
  AfterViewInit,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { OverlayModule } from '@angular/cdk/overlay';

@Component({
  selector: 'app-glass-device-filter',
  standalone: true,
  imports: [CommonModule, MatIconModule, OverlayModule],
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => GlassDeviceFilterComponent),
      multi: true,
    },
  ],
  template: `
    <div
      class="device-filter"
      [class.is-open]="panelOpen()"
      [class.is-disabled]="disabled"
    >
      <label *ngIf="label" class="field-label">{{ label }}</label>

      <div class="field-row" #trigger>
        <input
          #textInput
          type="text"
          class="device-input"
          [placeholder]="placeholder"
          [disabled]="disabled"
          (input)="onInput($event)"
          (focus)="onFocus()"
          (blur)="onBlur()"
          (keydown)="onInputKeydown($event)"
        />

        <button
          type="button"
          class="browse-btn"
          [disabled]="disabled || optionsLoading || !canBrowseOptions"
          [title]="browseTooltip"
          (mousedown)="preventBlur($event)"
          (click)="togglePanel($event)"
          aria-label="Browse devices"
        >
          <span class="btn-spinner" *ngIf="optionsLoading"></span>
          <mat-icon *ngIf="!optionsLoading">manage_search</mat-icon>
        </button>
      </div>

      <ng-template
        cdkConnectedOverlay
        [cdkConnectedOverlayOrigin]="triggerOrigin"
        [cdkConnectedOverlayOpen]="panelOpen()"
        [cdkConnectedOverlayWidth]="triggerWidth"
        [cdkConnectedOverlayOffsetY]="6"
        (overlayOutsideClick)="closePanel()"
      >
        <div class="device-panel" [class.light-theme]="isLightTheme()" role="listbox">
          <div class="panel-header">{{ panelHeader }}</div>

          <div class="panel-options">
            <button
              type="button"
              class="panel-option"
              *ngFor="let device of filteredOptions()"
              (mousedown)="preventBlur($event)"
              (click)="selectDevice(device)"
            >
              {{ device }}
            </button>

            <div *ngIf="filteredOptions().length === 0" class="panel-empty">
              {{ options.length === 0 ? 'No devices found for current filters.' : 'No devices match your text.' }}
            </div>
          </div>
        </div>
      </ng-template>
    </div>
  `,
  styles: [`
    :host {
      display: block;
      width: 100%;
    }

    .device-filter {
      display: flex;
      flex-direction: column;
      gap: 0.375rem;
    }

    .field-label {
      font-size: 0.6875rem;
      font-weight: 600;
      color: var(--text-muted);
      letter-spacing: 0.05em;
      text-transform: uppercase;
    }

    .field-row {
      display: flex;
      align-items: stretch;
      gap: 0.375rem;
      min-height: 44px;
    }

    .device-input {
      flex: 1;
      min-width: 0;
      padding: 0.625rem 0.875rem;
      background: rgba(255, 255, 255, 0.03);
      border: 1px solid rgba(255, 255, 255, 0.08);
      border-radius: 12px;
      color: #fff;
      font-size: 0.875rem;
      outline: none;
      transition: border-color 0.2s, box-shadow 0.2s;
      box-sizing: border-box;
    }

    :host-context(body.light-theme) .device-input {
      background: rgba(255, 255, 255, 0.9);
      border-color: rgba(0, 0, 0, 0.15);
      color: var(--text-main);
    }

    .device-input::placeholder {
      color: rgba(255, 255, 255, 0.35);
    }

    :host-context(body.light-theme) .device-input::placeholder {
      color: rgba(0, 0, 0, 0.4);
    }

    .device-input:focus {
      border-color: var(--accent-color);
      box-shadow: 0 0 0 2px rgba(129, 140, 248, 0.15);
    }

    .device-input:disabled {
      opacity: 0.55;
      cursor: not-allowed;
    }

    .is-open .device-input {
      border-color: var(--accent-color);
    }

    .browse-btn {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 44px;
      min-width: 44px;
      border: 1px solid rgba(255, 255, 255, 0.08);
      border-radius: 12px;
      background: rgba(255, 255, 255, 0.03);
      color: var(--text-muted);
      cursor: pointer;
      transition: border-color 0.2s, color 0.2s, background 0.2s;
    }

    :host-context(body.light-theme) .browse-btn {
      background: rgba(255, 255, 255, 0.9);
      border-color: rgba(0, 0, 0, 0.15);
    }

    .browse-btn:hover:not(:disabled) {
      color: var(--accent-color);
      border-color: rgba(129, 140, 248, 0.45);
      background: rgba(129, 140, 248, 0.08);
    }

    .browse-btn:disabled {
      opacity: 0.45;
      cursor: not-allowed;
    }

    .browse-btn mat-icon {
      font-size: 1.25rem;
      width: 1.25rem;
      height: 1.25rem;
    }

    .btn-spinner {
      width: 14px;
      height: 14px;
      border: 2px solid rgba(129, 140, 248, 0.25);
      border-top-color: var(--accent-color);
      border-radius: 50%;
      animation: spin 0.6s linear infinite;
    }

    @keyframes spin {
      to { transform: rotate(360deg); }
    }

    .device-panel {
      max-height: 280px;
      overflow: hidden;
      display: flex;
      flex-direction: column;
      background: rgba(15, 23, 42, 0.98);
      border: 1px solid rgba(255, 255, 255, 0.1);
      border-radius: 12px;
      box-shadow: 0 16px 40px rgba(0, 0, 0, 0.45);
      backdrop-filter: blur(20px);
    }

    .device-panel.light-theme {
      background: #fff;
      border-color: rgba(0, 0, 0, 0.12);
      box-shadow: 0 10px 32px rgba(0, 0, 0, 0.12);
    }

    .panel-header {
      padding: 0.625rem 0.875rem;
      font-size: 0.6875rem;
      font-weight: 600;
      letter-spacing: 0.04em;
      text-transform: uppercase;
      color: var(--text-muted);
      border-bottom: 1px solid rgba(255, 255, 255, 0.08);
    }

    .device-panel.light-theme .panel-header {
      border-bottom-color: rgba(0, 0, 0, 0.08);
    }

    .panel-options {
      overflow-y: auto;
      max-height: 240px;
      padding: 0.375rem;
    }

    .panel-option {
      display: block;
      width: 100%;
      text-align: left;
      padding: 0.625rem 0.75rem;
      border: none;
      border-radius: 8px;
      background: transparent;
      color: #fff;
      font-size: 0.875rem;
      cursor: pointer;
      transition: background 0.15s;
    }

    .device-panel.light-theme .panel-option {
      color: var(--text-main);
    }

    .panel-option:hover {
      background: rgba(129, 140, 248, 0.15);
    }

    .panel-empty {
      padding: 1rem 0.75rem;
      color: var(--text-muted);
      font-size: 0.8125rem;
      font-style: italic;
      text-align: center;
    }
  `],
})
export class GlassDeviceFilterComponent implements ControlValueAccessor, AfterViewInit {
  @Input() label = '';
  @Input() placeholder = 'Enter device or pattern (e.g. FNB7*)';
  @Input() browseTooltip = 'Browse devices for current site, data type, and tester type';
  @Input() panelHeader = 'Devices for current filters';
  @Input() options: string[] = [];
  @Input() optionsLoading = false;
  @Input() canBrowseOptions = true;
  @Input() disabled = false;

  @ViewChild('trigger') triggerElement!: ElementRef<HTMLElement>;
  @ViewChild('textInput') textInputElement!: ElementRef<HTMLInputElement>;

  readonly panelOpen = signal(false);
  private readonly currentValue = signal('');

  readonly filteredOptions = computed(() => {
    const query = this.currentValue().trim();
    const opts = this.options || [];
    if (!query) return opts;
    if (query.includes('*')) {
      return opts.filter((device) => matchesGlobPattern(device, query));
    }
    const q = query.toLowerCase();
    return opts.filter((device) => device.toLowerCase().includes(q));
  });

  triggerOrigin: any;
  triggerWidth = 0;

  onChange: (value: string) => void = () => {};
  onTouched: () => void = () => {};

  ngAfterViewInit(): void {
    this.syncInputElement(this.currentValue());
  }

  isLightTheme(): boolean {
    return document.body.classList.contains('light-theme');
  }

  onInput(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.currentValue.set(value);
    this.onChange(value);
  }

  onFocus(): void {
    // no-op — typing should not auto-open the browse panel
  }

  onBlur(): void {
    this.onTouched();
  }

  onInputKeydown(event: KeyboardEvent): void {
    if (event.key === 'ArrowDown' && !this.panelOpen() && !this.disabled && this.canBrowseOptions && !this.optionsLoading) {
      event.preventDefault();
      this.openPanel();
    } else if (event.key === 'Escape' && this.panelOpen()) {
      event.preventDefault();
      this.closePanel();
    }
  }

  togglePanel(event: Event): void {
    event.stopPropagation();
    if (this.panelOpen()) {
      this.closePanel();
    } else {
      this.openPanel();
    }
  }

  openPanel(): void {
    if (this.disabled || this.optionsLoading || !this.canBrowseOptions) return;
    this.triggerOrigin = this.triggerElement.nativeElement;
    this.triggerWidth = this.triggerElement.nativeElement.offsetWidth;
    this.panelOpen.set(true);
  }

  closePanel(): void {
    this.panelOpen.set(false);
  }

  selectDevice(device: string): void {
    this.currentValue.set(device);
    this.syncInputElement(device);
    this.onChange(device);
    this.onTouched();
    this.closePanel();
    this.textInputElement?.nativeElement.focus();
  }

  preventBlur(event: Event): void {
    event.preventDefault();
  }

  writeValue(value: string | null): void {
    const next = value ?? '';
    this.currentValue.set(next);
    this.syncInputElement(next);
  }

  registerOnChange(fn: (value: string) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
    if (isDisabled) {
      this.closePanel();
    }
  }

  private syncInputElement(value: string): void {
    const input = this.textInputElement?.nativeElement;
    if (!input || document.activeElement === input) return;
    input.value = value;
  }
}

function matchesGlobPattern(value: string, pattern: string): boolean {
  const normalizedValue = value.trim().toUpperCase();
  const normalizedPattern = pattern.trim().toUpperCase();
  if (!normalizedPattern.includes('*')) {
    return normalizedValue.includes(normalizedPattern);
  }

  let regex = '^';
  for (const char of normalizedPattern) {
    if (char === '*') {
      regex += '.*';
    } else if ('\\.[]{}()+^$|?'.includes(char)) {
      regex += '\\' + char;
    } else {
      regex += char;
    }
  }
  regex += '$';
  return new RegExp(regex).test(normalizedValue);
}
