import { CommonModule } from '@angular/common';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { GlassIconComponent } from '../shared/components/glass-icon.component';
import { GLASS_DIALOG_DATA, GlassDialogRef } from '../shared/services/glass-dialog.service';
import { ToastService } from '../shared/services/toast.service';
import { ConfigPipeline, ConfigPipelineStage, ConfigurationService } from './configuration.service';

interface DialogData {
  mode: 'create' | 'edit';
  pipeline?: ConfigPipeline;
}

@Component({
  selector: 'app-pipeline-form-dialog',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, GlassIconComponent],
  providers: [ConfigurationService],
  templateUrl: './pipeline-form-dialog.component.html',
  styleUrls: ['./pipeline-form-dialog.component.scss'],
})
export class PipelineFormDialogComponent implements OnInit {
  private formBuilder = inject(FormBuilder);
  private configService = inject(ConfigurationService);
  private dialogRef = inject(GlassDialogRef);
  private toast = inject(ToastService);
  private data: DialogData = inject(GLASS_DIALOG_DATA as any);

  mode = this.data.mode;
  form!: FormGroup;
  loading = signal(false);
  submitting = signal(false);

  sites = signal<string[]>([]);
  servers = signal<any[]>([]);
  dbConnections = signal<any[]>([]);
  stageTypes = ['CP', 'PPLOG', 'EXENSIO'];

  ngOnInit(): void {
    this.createForm();
    this.loadMetadata();

    if (this.mode === 'edit' && this.data.pipeline) {
      this.populateForm(this.data.pipeline);
    }
  }

  private createForm(): void {
    this.form = this.formBuilder.group({
      pipelineKey: ['', [Validators.required, Validators.minLength(3), Validators.maxLength(100)]],
      site: ['', Validators.required],
      server: ['', Validators.required],
      socketPort: [8000, [Validators.required, Validators.min(1), Validators.max(65535)]],
      configName: ['', [Validators.maxLength(255)]],
      senderId: [0, [Validators.required, Validators.min(1)]],
      rerunPeriodMinutes: [60, [Validators.required, Validators.min(1)]],
      environment: ['PROD', Validators.required],
      enabled: [true],
      historicalModeEnabled: [false],
      stages: this.formBuilder.array([]),
    });

    // In edit mode, disable pipelineKey since it's the unique identifier
    if (this.mode === 'edit') {
      this.form.get('pipelineKey')?.disable();
    }
  }

  private populateForm(pipeline: ConfigPipeline): void {
    this.form.patchValue({
      pipelineKey: pipeline.pipelineKey,
      site: pipeline.site,
      server: pipeline.server,
      socketPort: pipeline.socketPort,
      configName: pipeline.configName,
      senderId: pipeline.senderId,
      rerunPeriodMinutes: pipeline.rerunPeriodMinutes,
      environment: pipeline.environment,
      enabled: pipeline.enabled,
      historicalModeEnabled: pipeline.historicalModeEnabled,
    });

    // Populate stages
    const stagesArray = this.form.get('stages') as FormArray;
    pipeline.stages.forEach((stage) => {
      stagesArray.push(this.createStageFormGroup(stage));
    });
  }

  private createStageFormGroup(stage?: ConfigPipelineStage): FormGroup {
    return this.formBuilder.group({
      name: [stage?.name || '', [Validators.required, Validators.minLength(2)]],
      type: [stage?.type || 'CP', Validators.required],
      timeoutMinutes: [stage?.timeoutMinutes || 30, [Validators.required, Validators.min(1)]],
      dependsOn: [stage?.dependsOn || []],
      executionOrder: [stage?.executionOrder || 0],
    });
  }

  private loadMetadata(): void {
    this.loading.set(true);
    const environment = this.form.get('environment')?.value || 'PROD';

    // Load sites
    this.configService.getSites(environment).subscribe({
      next: (sites) => {
        this.sites.set(sites);
      },
      error: (err) => {
        console.error('Failed to load sites:', err);
        this.toast.error('Failed to load sites', 5000);
      },
    });

    // Load ETL servers
    this.configService.getEtlServers(environment).subscribe({
      next: (servers) => {
        this.servers.set(servers);
      },
      error: (err) => {
        console.error('Failed to load ETL servers:', err);
        this.toast.error('Failed to load ETL servers', 5000);
      },
    });

    // Load DB connections
    this.configService.getDbConnections(environment).subscribe({
      next: (connections) => {
        this.dbConnections.set(connections);
        this.loading.set(false);
      },
      error: (err) => {
        console.error('Failed to load DB connections:', err);
        this.toast.error('Failed to load DB connections', 5000);
        this.loading.set(false);
      },
    });
  }

  get stagesArray(): FormArray {
    return this.form.get('stages') as FormArray;
  }

  addStage(): void {
    const stage = this.createStageFormGroup();
    this.stagesArray.push(stage);
  }

  removeStage(index: number): void {
    this.stagesArray.removeAt(index);
  }

  getAvailableStageNames(excludeIndex: number): string[] {
    return this.stagesArray.controls
      .map((control, index) => (index !== excludeIndex ? control.get('name')?.value : null))
      .filter((name) => name);
  }

  onEnvironmentChange(): void {
    this.loadMetadata();
  }

  getPortError(): string {
    const control = this.form.get('socketPort');
    if (control?.hasError('required')) {
      return 'Socket port is required';
    }
    if (control?.hasError('min')) {
      return 'Port must be at least 1';
    }
    if (control?.hasError('max')) {
      return 'Port must be at most 65535';
    }
    return '';
  }

  getSenderIdError(): string {
    const control = this.form.get('senderId');
    if (control?.hasError('required')) {
      return 'Sender ID is required';
    }
    if (control?.hasError('min')) {
      return 'Sender ID must be at least 1';
    }
    return '';
  }

  getPipelineKeyError(): string {
    const control = this.form.get('pipelineKey');
    if (control?.hasError('required')) {
      return 'Pipeline key is required';
    }
    if (control?.hasError('minlength')) {
      return 'Pipeline key must be at least 3 characters';
    }
    if (control?.hasError('maxlength')) {
      return 'Pipeline key cannot exceed 100 characters';
    }
    return '';
  }

  submit(): void {
    if (this.form.invalid) {
      // Mark all fields as touched to show validation errors
      Object.keys(this.form.controls).forEach((key) => {
        this.form.get(key)?.markAsTouched();
      });

      // Mark stages fields as touched
      this.stagesArray.controls.forEach((control) => {
        if (control instanceof FormGroup) {
          Object.keys(control.controls).forEach((key) => {
            control.get(key)?.markAsTouched();
          });
        }
      });

      this.toast.error('Please correct the form errors', 5000);
      return;
    }

    this.submitting.set(true);
    const pipeline = this.form.getRawValue() as ConfigPipeline;

    const operation =
      this.mode === 'create'
        ? this.configService.createPipeline(pipeline)
        : this.configService.updatePipeline(pipeline.pipelineKey, pipeline);

    operation.subscribe({
      next: (result) => {
        this.submitting.set(false);
        this.dialogRef.close(result);
      },
      error: (err) => {
        this.submitting.set(false);
        const errorMessage = this.extractErrorMessage(err);
        this.toast.error(`Failed to save pipeline: ${errorMessage}`, 7000);
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
      return 'Invalid pipeline configuration';
    }
    if (err?.status === 409) {
      return 'Pipeline key already exists';
    }
    if (err?.status === 403) {
      return 'You do not have permission to save pipelines';
    }
    return 'An unexpected error occurred';
  }
}
