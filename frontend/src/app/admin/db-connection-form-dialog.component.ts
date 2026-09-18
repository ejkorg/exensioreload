import { CommonModule } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { GlassIconComponent } from '../shared/components/glass-icon.component';
import { GlassInputComponent } from '../shared/components/glass-input.component';
import { GlassSelectComponent } from '../shared/components/glass-select.component';
import { GLASS_DIALOG_DATA, GlassDialogRef } from '../shared/services/glass-dialog.service';
import { ToastService } from '../shared/services/toast.service';
import { ConfigDbConnection, ConfigurationService } from './configuration.service';

interface DialogData {
  mode: 'create' | 'edit';
  connection?: ConfigDbConnection;
}

@Component({
  selector: 'app-db-connection-form-dialog',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, GlassIconComponent, GlassInputComponent, GlassSelectComponent],
  templateUrl: './db-connection-form-dialog.component.html',
  styleUrls: ['./db-connection-form-dialog.component.scss'],
})
export class DbConnectionFormDialogComponent implements OnInit {
  private formBuilder = inject(FormBuilder);
  private configService = inject(ConfigurationService);
  private dialogRef = inject(GlassDialogRef);
  private toast = inject(ToastService);
  private data: DialogData = inject(GLASS_DIALOG_DATA as any);

  mode = this.data.mode;
  form!: FormGroup;
  loading = signal(false);
  submitting = signal(false);
  testingConnection = signal(false);
  showPassword = signal(false);

  dbTypes = ['ORACLE', 'POSTGRESQL'];
  environments = ['PROD', 'QA'];

  ngOnInit(): void {
    this.createForm();

    if (this.mode === 'edit' && this.data.connection) {
      this.populateForm(this.data.connection);
    }
  }

  private createForm(): void {
    this.form = this.formBuilder.group({
      connectionKey: [
        '',
        [
          Validators.required,
          Validators.minLength(3),
          Validators.maxLength(100),
          Validators.pattern(/^[a-zA-Z0-9_-]+$/),
        ],
      ],
      dbType: ['ORACLE', Validators.required],
      schema: ['', [Validators.required, Validators.minLength(1), Validators.maxLength(100)]],
      host: ['', [Validators.required, Validators.minLength(3), Validators.maxLength(255)]],
      user: ['', [Validators.required, Validators.minLength(1), Validators.maxLength(100)]],
      encryptedPassword: ['', [Validators.required, Validators.minLength(4), Validators.maxLength(255)]],
      connectionTimeoutMs: [30000, [Validators.required, Validators.min(100), Validators.max(120000)]],
      maximumPoolSize: [20, [Validators.required, Validators.min(1), Validators.max(100)]],
      minimumIdle: [5, [Validators.required, Validators.min(1), Validators.max(100)]],
      downloadUrl: ['', [Validators.maxLength(500)]],
      environment: ['PROD', Validators.required],
      enabled: [true],
    });

    // In edit mode, disable connectionKey since it's the unique identifier
    if (this.mode === 'edit') {
      this.form.get('connectionKey')?.disable();
    }

    // Add custom validator: maximumPoolSize must be >= minimumIdle
    this.form.setValidators(this.poolSizeValidator());
  }

  private poolSizeValidator() {
    return () => {
      const maxSize = this.form.get('maximumPoolSize')?.value;
      const minIdle = this.form.get('minimumIdle')?.value;

      if (maxSize !== null && minIdle !== null && maxSize < minIdle) {
        return { poolSizeInvalid: true };
      }

      return null;
    };
  }

  private populateForm(connection: ConfigDbConnection): void {
    this.form.patchValue({
      connectionKey: connection.connectionKey,
      dbType: connection.dbType,
      schema: connection.schema,
      host: connection.host,
      user: connection.user,
      encryptedPassword: '***', // Don't show actual password
      connectionTimeoutMs: connection.connectionTimeoutMs,
      maximumPoolSize: connection.maximumPoolSize,
      minimumIdle: connection.minimumIdle,
      downloadUrl: connection.downloadUrl,
      environment: connection.environment,
      enabled: connection.enabled,
    });
  }

  getConnectionKeyError(): string {
    const control = this.form.get('connectionKey');
    if (control?.hasError('required')) {
      return 'Connection key is required';
    }
    if (control?.hasError('minlength')) {
      return 'Connection key must be at least 3 characters';
    }
    if (control?.hasError('maxlength')) {
      return 'Connection key cannot exceed 100 characters';
    }
    if (control?.hasError('pattern')) {
      return 'Connection key must contain only alphanumeric characters, hyphens, and underscores';
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
    if (control?.hasError('maxlength')) {
      return 'Host cannot exceed 255 characters';
    }
    return '';
  }

  getSchemaError(): string {
    const control = this.form.get('schema');
    if (control?.hasError('required')) {
      return 'Schema is required';
    }
    if (control?.hasError('maxlength')) {
      return 'Schema cannot exceed 100 characters';
    }
    return '';
  }

  getUserError(): string {
    const control = this.form.get('user');
    if (control?.hasError('required')) {
      return 'User is required';
    }
    if (control?.hasError('maxlength')) {
      return 'User cannot exceed 100 characters';
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
    if (control?.hasError('maxlength')) {
      return 'Password cannot exceed 255 characters';
    }
    return '';
  }

  getTimeoutError(): string {
    const control = this.form.get('connectionTimeoutMs');
    if (control?.hasError('required')) {
      return 'Connection timeout is required';
    }
    if (control?.hasError('min')) {
      return 'Connection timeout must be at least 100ms';
    }
    if (control?.hasError('max')) {
      return 'Connection timeout cannot exceed 120000ms (2 minutes)';
    }
    return '';
  }

  getMaxPoolSizeError(): string {
    const control = this.form.get('maximumPoolSize');
    if (control?.hasError('required')) {
      return 'Maximum pool size is required';
    }
    if (control?.hasError('min')) {
      return 'Maximum pool size must be at least 1';
    }
    if (control?.hasError('max')) {
      return 'Maximum pool size cannot exceed 100';
    }
    return '';
  }

  getMinIdleError(): string {
    const control = this.form.get('minimumIdle');
    if (control?.hasError('required')) {
      return 'Minimum idle is required';
    }
    if (control?.hasError('min')) {
      return 'Minimum idle must be at least 1';
    }
    if (control?.hasError('max')) {
      return 'Minimum idle cannot exceed 100';
    }
    return '';
  }

  getPoolSizeError(): string {
    const formError = this.form.errors?.['poolSizeInvalid'];
    if (formError) {
      return 'Maximum pool size must be greater than or equal to minimum idle';
    }
    return '';
  }

  testConnection(): void {
    if (this.form.invalid) {
      this.toast.error('Please fix form errors before testing connection', 5000);
      return;
    }

    this.testingConnection.set(true);
    const connection = this.form.getRawValue() as ConfigDbConnection;

    this.configService.testDbConnection(connection).subscribe({
      next: (result) => {
        this.testingConnection.set(false);
        if (result.success) {
          this.toast.success('Database connection test successful', 5000);
        } else {
          this.toast.error(`Connection test failed: ${result.message}`, 7000);
        }
      },
      error: (err) => {
        this.testingConnection.set(false);
        const errorMessage = this.extractErrorMessage(err);
        this.toast.error(`Connection test failed: ${errorMessage}`, 7000);
      },
    });
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
    const connection = this.form.getRawValue() as ConfigDbConnection;

    const operation =
      this.mode === 'create'
        ? this.configService.createDbConnection(connection)
        : this.configService.updateDbConnection(connection.connectionKey, connection);

    operation.subscribe({
      next: (result) => {
        this.submitting.set(false);
        this.dialogRef.close(result);
      },
      error: (err) => {
        this.submitting.set(false);
        const errorMessage = this.extractErrorMessage(err);
        this.toast.error(`Failed to save database connection: ${errorMessage}`, 7000);
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
      return 'Invalid database connection configuration';
    }
    if (err?.status === 409) {
      return 'Connection key already exists';
    }
    if (err?.status === 403) {
      return 'You do not have permission to save database connections';
    }
    if (err?.status === 503) {
      return 'Database connection test failed - check your connection details';
    }
    return 'An unexpected error occurred';
  }
}
