import { CommonModule } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { GlassIconComponent } from '../shared/components/glass-icon.component';
import { GlassInputComponent } from '../shared/components/glass-input.component';
import { GlassSelectComponent } from '../shared/components/glass-select.component';
import { GLASS_DIALOG_DATA, GlassDialogRef } from '../shared/services/glass-dialog.service';
import { ToastService } from '../shared/services/toast.service';
import { ConfigEtlServer, ConfigurationService } from './configuration.service';

interface DialogData {
  mode: 'create' | 'edit';
  server?: ConfigEtlServer;
}

@Component({
  selector: 'app-etl-server-form-dialog',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, GlassIconComponent, GlassSelectComponent, GlassInputComponent],
  templateUrl: './etl-server-form-dialog.component.html',
  styleUrls: ['./etl-server-form-dialog.component.scss'],
})
export class EtlServerFormDialogComponent implements OnInit {
  private formBuilder = inject(FormBuilder);
  private configService = inject(ConfigurationService);
  private dialogRef = inject(GlassDialogRef);
  private toast = inject(ToastService);
  private data = inject(GLASS_DIALOG_DATA) as DialogData;

  mode = this.data.mode;
  form!: FormGroup;
  loading = signal(false);
  submitting = signal(false);
  showPassword = signal(false);
  testingConnection = signal(false);

  ngOnInit(): void {
    this.createForm();

    if (this.mode === 'edit' && this.data.server) {
      this.populateForm(this.data.server);
    }
  }

  private createForm(): void {
    this.form = this.formBuilder.group({
      serverKey: ['', [Validators.required, Validators.minLength(3), Validators.maxLength(100)]],
      host: ['', [Validators.required, Validators.minLength(3)]],
      sshPort: [22, [Validators.required, Validators.min(1), Validators.max(65535)]],
      socketPort: [9000, [Validators.required, Validators.min(1), Validators.max(65535)]],
      user: ['', [Validators.required, Validators.minLength(1), Validators.maxLength(100)]],
      encryptedPassword: ['', [Validators.required, Validators.minLength(4)]],
      timeoutMs: [30000, [Validators.required, Validators.min(1000), Validators.max(600000)]],
      environment: ['PROD', Validators.required],
      enabled: [true],
      isHistoricalSender: [false],
    });

    // In edit mode, disable serverKey since it's the unique identifier
    if (this.mode === 'edit') {
      this.form.get('serverKey')?.disable();
    }
  }

  private populateForm(server: ConfigEtlServer): void {
    this.form.patchValue({
      serverKey: server.serverKey,
      host: server.host,
      sshPort: server.sshPort,
      socketPort: server.socketPort,
      user: server.user,
      encryptedPassword: '', // Don't populate password for security
      timeoutMs: server.timeoutMs,
      environment: server.environment,
      enabled: server.enabled,
      isHistoricalSender: server.isHistoricalSender,
    });
  }

  toggleShowPassword(): void {
    this.showPassword.set(!this.showPassword());
  }

  getPasswordFieldType(): string {
    return this.showPassword() ? 'text' : 'password';
  }

  getPasswordStrength(): string {
    const password = this.form.get('encryptedPassword')?.value || '';
    if (!password) return '';
    if (password.length < 8) return 'weak';
    if (password.length < 12) return 'medium';
    if (/^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[@$!%*?&])/.test(password)) return 'strong';
    return 'medium';
  }

  getPasswordStrengthColor(): string {
    const strength = this.getPasswordStrength();
    if (strength === 'weak') return '#ef4444';
    if (strength === 'medium') return '#f59e0b';
    if (strength === 'strong') return '#22c55e';
    return 'transparent';
  }

  testConnection(): void {
    if (this.form.invalid) {
      this.toast.error('Please fill in all required fields first', 5000);
      return;
    }

    this.testingConnection.set(true);
    const server = this.form.getRawValue() as ConfigEtlServer;

    this.configService.testEtlServerConnection(server).subscribe({
      next: (result) => {
        this.testingConnection.set(false);
        if (result.success) {
          this.toast.success(`SSH connection successful: ${result.message}`, 5000);
        } else {
          this.toast.error(`SSH connection failed: ${result.message}`, 5000);
        }
      },
      error: (err) => {
        this.testingConnection.set(false);
        const errorMessage = this.extractErrorMessage(err);
        this.toast.error(`SSH test failed: ${errorMessage}`, 5000);
      },
    });
  }

  getServerKeyError(): string {
    const control = this.form.get('serverKey');
    if (control?.hasError('required')) {
      return 'Server key is required';
    }
    if (control?.hasError('minlength')) {
      return 'Server key must be at least 3 characters';
    }
    if (control?.hasError('maxlength')) {
      return 'Server key cannot exceed 100 characters';
    }
    return '';
  }

  getHostError(): string {
    const control = this.form.get('host');
    if (control?.hasError('required')) {
      return 'Host is required';
    }
    if (control?.hasError('minlength')) {
      return 'Host must be at least 3 characters';
    }
    return '';
  }

  getUserError(): string {
    const control = this.form.get('user');
    if (control?.hasError('required')) {
      return 'User is required';
    }
    if (control?.hasError('minlength')) {
      return 'User must be at least 1 character';
    }
    return '';
  }

  getPasswordError(): string {
    const control = this.form.get('encryptedPassword');
    if (control?.hasError('required')) {
      return 'Password is required';
    }
    if (control?.hasError('minlength')) {
      return 'Password must be at least 4 characters';
    }
    return '';
  }

  getSshPortError(): string {
    const control = this.form.get('sshPort');
    if (control?.hasError('required')) {
      return 'SSH port is required';
    }
    if (control?.hasError('min')) {
      return 'SSH port must be at least 1';
    }
    if (control?.hasError('max')) {
      return 'SSH port must be at most 65535';
    }
    return '';
  }

  getSocketPortError(): string {
    const control = this.form.get('socketPort');
    if (control?.hasError('required')) {
      return 'Socket port is required';
    }
    if (control?.hasError('min')) {
      return 'Socket port must be at least 1';
    }
    if (control?.hasError('max')) {
      return 'Socket port must be at most 65535';
    }
    return '';
  }

  getTimeoutError(): string {
    const control = this.form.get('timeoutMs');
    if (control?.hasError('required')) {
      return 'Timeout is required';
    }
    if (control?.hasError('min')) {
      return 'Timeout must be at least 1000 milliseconds';
    }
    if (control?.hasError('max')) {
      return 'Timeout cannot exceed 600000 milliseconds';
    }
    return '';
  }

  submit(): void {
    if (this.form.invalid) {
      // Mark all fields as touched to show validation errors
      Object.keys(this.form.controls).forEach((key) => {
        this.form.get(key)?.markAsTouched();
      });

      this.toast.error('Please correct the form errors', 5000);
      return;
    }

    this.submitting.set(true);
    const server = this.form.getRawValue() as ConfigEtlServer;

    const operation =
      this.mode === 'create'
        ? this.configService.createEtlServer(server)
        : this.configService.updateEtlServer(server.serverKey, server);

    operation.subscribe({
      next: (result) => {
        this.submitting.set(false);
        this.dialogRef.close(result);
      },
      error: (err) => {
        this.submitting.set(false);
        const errorMessage = this.extractErrorMessage(err);
        this.toast.error(`Failed to save ETL server: ${errorMessage}`, 7000);
      },
    });
  }

  cancel(): void {
    this.dialogRef.close();
  }

  private extractErrorMessage(err: any): string {
    if (err?.error?.message) {
      return err.error.message;
    }
    if (err?.error?.detail) {
      return err.error.detail;
    }
    if (err?.error?.error) {
      return err.error.error;
    }
    if (err?.status === 400) {
      return 'Invalid ETL server configuration';
    }
    if (err?.status === 409) {
      return 'Server key already exists';
    }
    if (err?.status === 403) {
      return 'You do not have permission to save servers';
    }
    return 'An unexpected error occurred';
  }
}
