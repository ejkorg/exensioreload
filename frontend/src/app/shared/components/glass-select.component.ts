import { Component, Input, forwardRef, signal, computed, ElementRef, ViewChild, ViewChildren, QueryList } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { OverlayModule } from '@angular/cdk/overlay';

export interface GlassOption {
  value: any;
  label: string;
}

@Component({
  selector: 'app-glass-select',
  standalone: true,
  imports: [CommonModule, MatIconModule, OverlayModule],
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => GlassSelectComponent),
      multi: true
    }
  ],
  template: `
    <div class="glass-select-container" [class.is-open]="isOpen()" [class.has-value]="hasValue()" [class.disabled]="disabled" (keydown)="onContainerKeydown($event)">
      <label *ngIf="label" class="floating-label">{{ label }}</label>

      <div
        class="select-trigger"
        [class.editable-trigger]="editable"
        (click)="!editable && toggleDropdown()"
        #trigger
        [attr.tabindex]="editable ? null : 0"
        (keydown)="!editable && onTriggerKeydown($event)"
      >
        <mat-icon *ngIf="prefixIcon" class="prefix-icon">{{ prefixIcon }}</mat-icon>

        <div class="selected-content" *ngIf="!editable">
          <span class="selected-label" *ngIf="getSelectedLabel()">{{ getSelectedLabel() }}</span>
          <span class="placeholder" *ngIf="!getSelectedLabel()">{{ placeholder }}</span>
        </div>

        <input
          *ngIf="editable"
          #editableInput
          type="text"
          class="editable-input"
          [placeholder]="placeholder"
          [value]="inputText()"
          [disabled]="disabled"
          (input)="onEditableInput($event)"
          (focus)="onEditableFocus()"
          (blur)="onEditableBlur()"
          (keydown)="onEditableKeydown($event)"
          (click)="$event.stopPropagation()"
        />

        <mat-icon class="chevron-icon" (click)="toggleDropdown(); $event.stopPropagation()">expand_more</mat-icon>
      </div>

      <ng-template
        cdkConnectedOverlay
        [cdkConnectedOverlayOrigin]="triggerOrigin"
        [cdkConnectedOverlayOpen]="isOpen()"
        [cdkConnectedOverlayWidth]="triggerWidth"
        (overlayOutsideClick)="onOverlayOutsideClick()"
      >
        <div class="glass-dropdown-panel glass-panel" [class.light-theme]="isLightTheme()" role="listbox">
          <div
            *ngIf="editable && inputText().trim() && !hasExactMatch()"
            class="glass-option custom-option"
            [class.is-highlighted]="isCustomOptionHighlighted()"
            (mousedown)="preventBlur($event)"
            (click)="commitEditableValue()"
          >
            Use "{{ inputText().trim() }}"
          </div>

          <div
            #optionElement
            *ngFor="let option of dropdownOptions(); let i = index"
            class="glass-option"
            [class.is-selected]="isSelected(option)"
            [class.is-highlighted]="i === highlightedIndex()"
            (mousedown)="preventBlur($event)"
            (click)="selectOption(option)"
            (keydown.enter)="selectOption(option)"
            (keydown.space)="selectOption(option)"
            tabindex="0"
            role="option"
            [attr.aria-selected]="isSelected(option)"
          >
            {{ getOptionLabel(option) }}
            <mat-icon *ngIf="isSelected(option)" class="check-icon">check</mat-icon>
          </div>

          <div *ngIf="dropdownOptions().length === 0 && !(editable && inputText().trim())" class="no-options">
            No options available
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

    .glass-select-container {
      position: relative;
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
    }

    .floating-label {
      font-size: 0.6875rem; // 11px - matches input component
      font-weight: 600;
      color: var(--text-muted);
      letter-spacing: 0.05em; // Increased for better readability
      text-transform: uppercase;
      margin-bottom: 4px; // Increased from 2px
    }

    .select-trigger {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      padding: 0.75rem 1rem; // 12px 16px
      background: rgba(255, 255, 255, 0.03);
      border: 1px solid rgba(255, 255, 255, 0.08);
      border-radius: 14px;
      backdrop-filter: blur(16px);
      -webkit-backdrop-filter: blur(16px);
      cursor: pointer;
      transition: all 0.3s ease;
      min-height: 56px; // Matches input component
      box-sizing: border-box;
    }

    /* Light theme support */
    :host-context(body.light-theme) .select-trigger {
      background: rgba(255, 255, 255, 0.9);
      border: 1px solid rgba(0, 0, 0, 0.15);
    }

    .select-trigger:hover {
      background: rgba(255, 255, 255, 0.05);
      border-color: rgba(255, 255, 255, 0.15);
    }

    :host-context(body.light-theme) .select-trigger:hover {
      background: rgba(255, 255, 255, 1);
      border-color: rgba(0, 0, 0, 0.25);
    }

    .is-open .select-trigger {
      border-color: var(--accent-color);
      box-shadow: 0 0 20px rgba(129, 140, 248, 0.15);
    }

    :host-context(body.light-theme) .is-open .select-trigger {
      box-shadow: 0 0 0 3px rgba(79, 70, 229, 0.1);
    }

    .selected-content {
      flex: 1;
      display: flex;
      flex-wrap: wrap;
      gap: 4px;
      overflow: hidden;
    }

    .selected-label {
      color: #fff;
      font-size: 0.9375rem; // 15px - matches input font size
      font-weight: 500;
      line-height: 1.5; // Comfortable line height
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    :host-context(body.light-theme) .selected-label {
      color: var(--text-main);
    }

    .placeholder {
      color: var(--text-muted);
      font-size: 0.875rem; // 14px - slightly smaller for placeholder
      font-weight: 400;
    }

    :host-context(body.light-theme) .placeholder {
      color: rgba(0, 0, 0, 0.4);
    }

    .prefix-icon, .chevron-icon {
      color: var(--text-muted);
      font-size: 1.25rem;
      width: 1.25rem;
      height: 1.25rem;
    }

    .is-open .chevron-icon {
      transform: rotate(180deg);
      color: var(--accent-color);
    }

    .editable-trigger {
      cursor: text;
    }

    .editable-input {
      flex: 1;
      min-width: 0;
      border: none;
      outline: none;
      background: transparent;
      color: #fff;
      font-size: 0.9375rem;
      font-weight: 500;
      line-height: 1.5;
      font-family: inherit;
      padding: 0;
    }

    :host-context(body.light-theme) .editable-input {
      color: var(--text-main);
    }

    .editable-input::placeholder {
      color: var(--text-muted);
      font-weight: 400;
    }

    :host-context(body.light-theme) .editable-input::placeholder {
      color: rgba(0, 0, 0, 0.4);
    }

    .custom-option {
      font-style: italic;
      border-bottom: 1px solid rgba(255, 255, 255, 0.08);
      margin-bottom: 0.25rem;
    }

    .glass-dropdown-panel.light-theme .custom-option {
      border-bottom-color: rgba(0, 0, 0, 0.08);
    }

    /* Dropdown Panel */
    .glass-dropdown-panel {
      margin-top: 8px;
      max-height: 300px;
      overflow-y: auto;
      padding: 0.5rem;
      background: rgba(15, 23, 42, 0.95);
      border: 1px solid rgba(255, 255, 255, 0.1);
      border-radius: 14px;
      box-shadow: 0 10px 40px rgba(0, 0, 0, 0.4);
      animation: fadeIn 0.2s ease-out;
      backdrop-filter: blur(20px);
      -webkit-backdrop-filter: blur(20px);
    }

    .glass-dropdown-panel.light-theme {
      background: #ffffff !important;
      border: 1px solid rgba(0, 0, 0, 0.12) !important;
      box-shadow: 0 10px 40px rgba(0, 0, 0, 0.12), 0 4px 12px rgba(0, 0, 0, 0.08) !important;
    }

    @keyframes fadeIn {
      from { opacity: 0; transform: translateY(-10px); }
      to { opacity: 1; transform: translateY(0); }
    }

    .glass-option {
      padding: 0.75rem 1rem;
      border-radius: 8px;
      color: var(--text-muted);
      font-size: 0.95rem;
      cursor: pointer;
      display: flex;
      justify-content: space-between;
      align-items: center;
      transition: all 0.2s ease;
      outline: none;
      position: relative;
      overflow: hidden;
    }

    .glass-option::before {
      content: '';
      position: absolute;
      top: 0;
      left: -100%;
      width: 100%;
      height: 100%;
      background: rgba(255, 255, 255, 0.06);
      transition: left 0.3s ease-out;
      pointer-events: none;
      z-index: 0;
    }

    .glass-option > * {
      position: relative;
      z-index: 1;
    }

    .glass-dropdown-panel.light-theme .glass-option {
      color: var(--text-main);
    }

    .glass-option:hover,
    .is-highlighted {
      background: rgba(255, 255, 255, 0.08);
      color: #fff;

      &::before {
        left: 100%;
      }
    }

    .glass-dropdown-panel.light-theme .glass-option:hover,
    .glass-dropdown-panel.light-theme .is-highlighted {
      background: rgba(79, 70, 229, 0.08);
      color: var(--text-main);

      &::before {
        left: 100%;
      }
    }

    .is-selected {
      background: rgba(129, 140, 248, 0.15) !important;
      color: var(--accent-color) !important;
      font-weight: 600;
    }

    .glass-dropdown-panel.light-theme .is-selected {
      background: rgba(79, 70, 229, 0.12) !important;
      color: var(--accent-color) !important;
    }

    .check-icon {
        font-size: 1.1rem;
        width: 1.1rem;
        height: 1.1rem;
    }

    .no-options {
        padding: 1rem;
        color: var(--text-muted);
        text-align: center;
        font-style: italic;
    }

    .disabled {
      opacity: 0.6;
      cursor: not-allowed;
      .select-trigger { pointer-events: none; }
    }
  `]
})
export class GlassSelectComponent implements ControlValueAccessor {
  @Input() label: string = '';
  @Input() placeholder: string = 'Select option';
  @Input() prefixIcon: string = '';
  @Input() multiple: boolean = false;
  @Input() editable: boolean = false;
  @Input() error: string | null = null;

  // Internal signal — CDK overlay portal is a detached EmbeddedViewRef that doesn't
  // receive zone-based change detection from the host. Using a signal ensures the
  // *ngFor inside the portal reacts to options changes via Angular's signal graph.
  readonly optionsSignal = signal<(GlassOption | string)[]>([]);

  @Input() set options(val: (GlassOption | string)[]) {
    this._options = val || [];
    this.optionsSignal.set(this._options);
    // Close dropdown if it's open and options changed — prevents stale list
    if (this.isOpen()) {
      this.isOpen.set(false);
    }
  }
  get options(): (GlassOption | string)[] { return this._options; }
  private _options: (GlassOption | string)[] = [];

  @ViewChild('trigger') triggerElement!: ElementRef;
  @ViewChild('editableInput') editableInputElement?: ElementRef<HTMLInputElement>;
  @ViewChildren('optionElement') optionElements!: QueryList<ElementRef>;

  value = signal<any>(null);
  inputText = signal('');
  isOpen = signal(false);
  @Input() disabled = false;
  highlightedIndex = signal<number>(-1);

  readonly dropdownOptions = computed(() => {
    const opts = this.optionsSignal();
    if (!this.editable) return opts;
    const query = this.inputText().trim().toLowerCase();
    if (!query) return opts;
    return opts.filter((o) => this.getOptionLabel(o).toLowerCase().includes(query));
  });

  triggerOrigin: any;
  triggerWidth: number = 0;

  constructor() {}

  onChange: any = () => { };
  onTouched: any = () => { };

  isLightTheme(): boolean {
    return document.body.classList.contains('light-theme');
  }

  toggleDropdown() {
    if (this.disabled) return;
    this.triggerOrigin = this.triggerElement.nativeElement;
    this.triggerWidth = this.triggerElement.nativeElement.offsetWidth;

    if (this.isOpen()) {
      if (this.editable) {
        this.commitEditableValue();
      } else {
        this.closeDropdown();
      }
      return;
    }

    this.isOpen.set(true);
    this.highlightedIndex.set(-1);
    if (this.editable) {
      this.inputText.set(this.getCommittedDisplayValue());
      this.editableInputElement?.nativeElement.focus();
    }
  }

  openDropdown() {
    this.triggerOrigin = this.triggerElement.nativeElement;
    this.triggerWidth = this.triggerElement.nativeElement.offsetWidth;
    if (!this.isOpen()) {
      this.isOpen.set(true);
      this.highlightedIndex.set(-1);
    }
  }

  closeDropdown() {
    this.isOpen.set(false);
    this.highlightedIndex.set(-1);
  }

  onOverlayOutsideClick() {
    if (this.editable) {
      this.commitEditableValue();
    } else {
      this.closeDropdown();
    }
  }

  preventBlur(event: MouseEvent) {
    event.preventDefault();
  }

  onEditableFocus() {
    if (this.disabled) return;
    this.inputText.set(this.getCommittedDisplayValue());
    this.openDropdown();
  }

  onEditableInput(event: Event) {
    const text = (event.target as HTMLInputElement).value;
    this.inputText.set(text);
    this.openDropdown();
    this.highlightedIndex.set(-1);
  }

  onEditableBlur() {
    setTimeout(() => {
      if (!this.isOpen()) {
        this.commitEditableValue();
      }
    }, 150);
  }

  onEditableKeydown(event: KeyboardEvent) {
    const options = this.dropdownOptions();
    const hasCustomOption = this.editable && !!this.inputText().trim() && !this.hasExactMatch();

    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        if (!this.isOpen()) {
          this.openDropdown();
        }
        this.moveHighlight(1, options.length, hasCustomOption);
        return;
      case 'ArrowUp':
        event.preventDefault();
        if (!this.isOpen()) {
          this.openDropdown();
        }
        this.moveHighlight(-1, options.length, hasCustomOption);
        return;
      case 'Escape':
        event.preventDefault();
        this.inputText.set(this.getCommittedDisplayValue());
        this.closeDropdown();
        return;
      case 'Enter':
        event.preventDefault();
        if (this.highlightedIndex() >= 0) {
          const opts = this.dropdownOptions();
          if (this.highlightedIndex() < opts.length) {
            this.selectOption(opts[this.highlightedIndex()]);
          }
        } else {
          this.commitEditableValue();
        }
        return;
      case 'Tab':
        this.commitEditableValue();
        this.closeDropdown();
        return;
    }
  }

  commitEditableValue() {
    const text = this.inputText().trim();
    this.value.set(text);
    this.inputText.set(text);
    this.onChange(text);
    this.onTouched();
    this.closeDropdown();
  }

  hasExactMatch(): boolean {
    const query = this.inputText().trim().toLowerCase();
    if (!query) return false;
    return this.optionsSignal().some((o) => this.getOptionLabel(o).toLowerCase() === query);
  }

  isCustomOptionHighlighted(): boolean {
    return this.editable && !!this.inputText().trim() && !this.hasExactMatch() && this.highlightedIndex() === -1;
  }

  private getCommittedDisplayValue(): string {
    const current = this.value();
    if (current === null || current === undefined) return '';
    return String(current);
  }

  onTriggerKeydown(event: KeyboardEvent) {
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        if (!this.isOpen()) {
          this.toggleDropdown();
        }
        this.moveHighlight(1);
        return;
      case 'ArrowUp':
        event.preventDefault();
        if (!this.isOpen()) {
          this.toggleDropdown();
        }
        this.moveHighlight(-1);
        return;
      case 'Enter':
      case ' ':
        event.preventDefault();
        this.toggleDropdown();
        return;
      default:
        if (event.key.length === 1 && /[a-zA-Z0-9]/.test(event.key) && this._options.length > 0) {
          event.preventDefault();
          if (!this.isOpen()) {
            this.toggleDropdown();
          }
          this.findAndHighlightByLetter(event.key.toLowerCase());
        }
    }
  }

  onContainerKeydown(event: KeyboardEvent) {
    if (!this.isOpen() || this.editable) return;

    const options = this.dropdownOptions();
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        this.moveHighlight(1, options.length);
        break;
      case 'ArrowUp':
        event.preventDefault();
        this.moveHighlight(-1, options.length);
        break;
      case 'Escape':
        event.preventDefault();
        this.closeDropdown();
        this.triggerElement?.nativeElement.focus();
        break;
      case 'Enter':
      case ' ':
        if (this.highlightedIndex() >= 0) {
          event.preventDefault();
          this.selectOption(options[this.highlightedIndex()]);
        }
        break;
      default:
        if (event.key.length === 1 && /[a-zA-Z0-9]/.test(event.key)) {
          event.preventDefault();
          this.findAndHighlightByLetter(event.key.toLowerCase(), options);
        }
        break;
    }
  }

  onArrowDown() {
    if (!this.isOpen()) {
      this.toggleDropdown();
    }
  }

  moveHighlight(direction: number, optionCount?: number, hasCustomOption = false) {
    const count = optionCount ?? this.dropdownOptions().length;
    const current = this.highlightedIndex();
    const minIndex = hasCustomOption ? -1 : 0;
    const next = current + direction;
    if (next >= minIndex && next < count) {
      this.highlightedIndex.set(next);
      setTimeout(() => {
        const optionElements = this.optionElements?.toArray();
        const elementIndex = hasCustomOption ? next : next;
        if (optionElements && elementIndex >= 0 && optionElements[elementIndex]) {
          optionElements[elementIndex].nativeElement.scrollIntoView({ block: 'nearest' });
        }
      });
    }
  }

  private findAndHighlightByLetter(letter: string, list: (GlassOption | string)[] = this._options) {
    if (list.length === 0) return;

    const current = this.highlightedIndex();
    let foundIndex = -1;

    for (let i = current + 1; i < list.length; i++) {
      if (this.getOptionLabel(list[i]).toLowerCase().startsWith(letter)) {
        foundIndex = i;
        break;
      }
    }

    if (foundIndex === -1) {
      for (let i = 0; i <= current && i < list.length; i++) {
        if (this.getOptionLabel(list[i]).toLowerCase().startsWith(letter)) {
          foundIndex = i;
          break;
        }
      }
    }

    if (foundIndex >= 0) {
      this.highlightedIndex.set(foundIndex);
      setTimeout(() => {
        const optionElements = this.optionElements?.toArray();
        if (optionElements && optionElements[foundIndex]) {
          optionElements[foundIndex].nativeElement.scrollIntoView({ block: 'nearest' });
        }
      });
    }
  }

  isSelected(option: GlassOption | string): boolean {
    const val = this.getOptionValue(option);
    const current = this.value();
    if (this.multiple) {
      return Array.isArray(current) && current.includes(val);
    }
    return current === val;
  }

  getOptionValue(option: GlassOption | string): any {
    return typeof option === 'string' ? option : option.value;
  }

  getOptionLabel(option: GlassOption | string): string {
    return typeof option === 'string' ? option : option.label;
  }

  selectOption(option: GlassOption | string) {
    const val = this.getOptionValue(option);
    let current = this.value();

    if (this.multiple) {
      if (!Array.isArray(current)) current = [];
      if (current.includes(val)) {
        current = current.filter((v: any) => v !== val);
      } else {
        current = [...current, val];
      }
    } else {
      current = val;
      if (this.editable) {
        this.inputText.set(this.getOptionLabel(option));
      }
      this.closeDropdown();
    }

    this.value.set(current);
    this.onChange(current);
    this.onTouched();
  }

  hasValue(): boolean {
    const val = this.value();
    if (this.multiple) {
      return Array.isArray(val) && val.length > 0;
    }
    return val !== null && val !== undefined && val !== '';
  }

  getSelectedLabel(): string {
    const current = this.value();
    if (!current) return '';

    if (this.multiple) {
      if (!Array.isArray(current) || current.length === 0) return '';
      const labels = current.map((v: any) => {
        const opt = (this._options as any[]).find(o => this.getOptionValue(o) === v);
        return opt ? this.getOptionLabel(opt) : v;
      });
      if (labels.length > 2) return `${labels.length} items selected`;
      return labels.join(', ');
    } else {
      const opt = (this._options as any[]).find(o => this.getOptionValue(o) === current);
      return opt ? this.getOptionLabel(opt) : (typeof current === 'string' ? current : '');
    }
  }

  // ControlValueAccessor
  writeValue(val: any): void {
    this.value.set(val);
    if (this.editable) {
      this.inputText.set(val ?? '');
    }
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
