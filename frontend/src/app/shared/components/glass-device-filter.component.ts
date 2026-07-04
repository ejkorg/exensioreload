import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output, inject, signal } from '@angular/core';
import { StagingSessionService } from '../services/staging-session.service';
import { GlassSelectComponent } from './glass-select.component';

/**
 * Multi-select device filter component.
 *
 * Provides a reusable device filter interface using the glass-select component
 * with multi-select support. Automatically loads available devices from the
 * staging session service.
 *
 * Validates: Requirements 2.1, 3.1, 4.1 - Device filter UI
 */
@Component({
  selector: 'app-glass-device-filter',
  standalone: true,
  imports: [CommonModule, GlassSelectComponent],
  template: `
    <app-glass-select
      [label]="label"
      [placeholder]="placeholder"
      [multiple]="true"
      [options]="deviceOptions()"
      [value]="selectedDevices()"
      (valueChange)="onDeviceChange($event)"
      [disabled]="disabled"
      prefixIcon="devices"
    />
  `,
})
export class GlassDeviceFilterComponent {
  @Input() label = 'Device';
  @Input() placeholder = 'Filter by device...';
  @Input() disabled = false;
  @Input() sessionId?: number;

  @Output() deviceChange = new EventEmitter<string[]>();

  private sessionService = inject(StagingSessionService);

  // Signal for available device options
  readonly deviceOptions = signal<string[]>([]);

  // Signal for selected devices
  readonly selectedDevices = signal<string[]>([]);

  constructor() {
    // Load devices when component initializes
    this.loadDevices();
  }

  /**
   * Load available devices from the session service.
   * If sessionId is provided, load devices for that specific session.
   * Otherwise, load all distinct devices.
   */
  private loadDevices(): void {
    this.sessionService.getDistinctDevices(this.sessionId).subscribe({
      next: (devices: string[]) => {
        this.deviceOptions.set(devices || []);
      },
      error: (err) => {
        console.error('Failed to load device options:', err);
        this.deviceOptions.set([]);
      },
    });
  }

  /**
   * Handle device selection change.
   * Emits deviceChange event with the selected device array.
   *
   * @param devices Array of selected device identifiers
   */
  onDeviceChange(devices: string[] | string): void {
    const deviceArray = Array.isArray(devices) ? devices : devices ? [devices] : [];
    this.selectedDevices.set(deviceArray);
    this.deviceChange.emit(deviceArray);
  }

  /**
   * Get the currently selected devices.
   *
   * @returns Array of selected device identifiers
   */
  getSelectedDevices(): string[] {
    return this.selectedDevices();
  }

  /**
   * Clear all selected devices.
   */
  clearSelection(): void {
    this.selectedDevices.set([]);
    this.deviceChange.emit([]);
  }

  /**
   * Set the selected devices programmatically.
   *
   * @param devices Array of device identifiers to select
   */
  setSelectedDevices(devices: string[]): void {
    this.selectedDevices.set(devices || []);
    this.deviceChange.emit(devices || []);
  }
}
