import { Injectable, ComponentRef, ApplicationRef, createComponent, EnvironmentInjector, Type, Injector, signal } from '@angular/core';

export interface GlassDialogConfig<T = any> {
  data?: T;
  disableClose?: boolean;
  panelClass?: string | string[];
  backdropClass?: string | string[];
  width?: string;
  maxWidth?: string;
  height?: string;
  maxHeight?: string;
}

export class GlassDialogRef<T = any, R = any> {
  private _afterClosedSignal = signal<R | undefined>(undefined);
  afterClosed$ = this._afterClosedSignal.asReadonly();

  constructor(
    private componentRef: ComponentRef<any>,
    private backdropElement: HTMLElement,
    private containerElement: HTMLElement
  ) {}

  close(result?: R): void {
    // Trigger close animation
    this.containerElement.classList.add('closing');
    this.backdropElement.classList.add('closing');

    // Wait for animation to complete
    setTimeout(() => {
      this._afterClosedSignal.set(result);
      this.componentRef.destroy();
      this.backdropElement.remove();
      this.containerElement.remove();
    }, 200); // Match animation duration
  }

  afterClosed(): Promise<R | undefined> {
    return new Promise((resolve) => {
      const checkClosed = () => {
        const value = this._afterClosedSignal();
        if (value !== undefined || !document.body.contains(this.containerElement)) {
          resolve(value);
        } else {
          setTimeout(checkClosed, 50);
        }
      };
      checkClosed();
    });
  }
}

@Injectable({
  providedIn: 'root'
})
export class GlassDialogService {
  constructor(
    private appRef: ApplicationRef,
    private injector: EnvironmentInjector
  ) {}

  open<T, D = any, R = any>(
    component: Type<T>,
    config?: GlassDialogConfig<D>
  ): GlassDialogRef<T, R> {
    // Create backdrop
    const backdrop = document.createElement('div');
    backdrop.className = 'glass-dialog-backdrop';
    if (config?.backdropClass) {
      const classes = Array.isArray(config.backdropClass)
        ? config.backdropClass
        : [config.backdropClass];
      backdrop.classList.add(...classes);
    }

    // Create container
    const container = document.createElement('div');
    container.className = 'glass-dialog-container';
    if (config?.panelClass) {
      const classes = Array.isArray(config.panelClass)
        ? config.panelClass
        : [config.panelClass];
      container.classList.add(...classes);
    }

    // Apply size styles
    if (config?.width) container.style.width = config.width;
    if (config?.maxWidth) container.style.maxWidth = config.maxWidth;
    if (config?.height) container.style.height = config.height;
    if (config?.maxHeight) container.style.maxHeight = config.maxHeight;

    // Create dialog ref
    const dialogRef = new GlassDialogRef<T, R>(
      null as any, // Will be set after component creation
      backdrop,
      container
    );

    // Create custom injector with dialog data and ref
    const customInjector = Injector.create({
      parent: this.injector,
      providers: [
        { provide: GLASS_DIALOG_DATA, useValue: config?.data },
        { provide: GlassDialogRef, useValue: dialogRef }
      ]
    });

    // Create component
    const componentRef = createComponent(component, {
      environmentInjector: this.injector,
      elementInjector: customInjector
    });

    // Set component ref in dialog ref
    (dialogRef as any).componentRef = componentRef;

    // Attach component to container
    container.appendChild(componentRef.location.nativeElement);

    // Attach to DOM
    document.body.appendChild(backdrop);
    document.body.appendChild(container);

    // Attach to Angular's change detection
    this.appRef.attachView(componentRef.hostView);

    // Trigger enter animation
    requestAnimationFrame(() => {
      backdrop.classList.add('visible');
      container.classList.add('visible');
    });

    // Handle backdrop click
    if (!config?.disableClose) {
      backdrop.addEventListener('click', () => {
        dialogRef.close();
      });
    }

    // Handle ESC key
    const escapeHandler = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !config?.disableClose) {
        dialogRef.close();
        document.removeEventListener('keydown', escapeHandler);
      }
    };
    document.addEventListener('keydown', escapeHandler);

    return dialogRef;
  }
}

// Injection tokens
export const GLASS_DIALOG_DATA = Symbol('GLASS_DIALOG_DATA');
