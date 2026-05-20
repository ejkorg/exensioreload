import { Directive, Input, ElementRef, HostListener, Renderer2, OnDestroy } from '@angular/core';

@Directive({
  selector: '[glassTooltip]',
  standalone: true
})
export class GlassTooltipDirective implements OnDestroy {
  @Input() glassTooltip: string = '';
  @Input() tooltipPosition: 'top' | 'bottom' | 'left' | 'right' = 'top';
  @Input() tooltipDelay: number = 300;

  private tooltipElement: HTMLElement | null = null;
  private showTimeout: any;

  constructor(
    private el: ElementRef,
    private renderer: Renderer2
  ) {}

  @HostListener('mouseenter')
  onMouseEnter() {
    if (!this.glassTooltip) return;

    this.showTimeout = setTimeout(() => {
      this.show();
    }, this.tooltipDelay);
  }

  @HostListener('mouseleave')
  onMouseLeave() {
    if (this.showTimeout) {
      clearTimeout(this.showTimeout);
    }
    this.hide();
  }

  private show() {
    if (this.tooltipElement) return;

    // Create tooltip element
    this.tooltipElement = this.renderer.createElement('div');
    this.renderer.addClass(this.tooltipElement, 'glass-tooltip');
    this.renderer.addClass(this.tooltipElement, `glass-tooltip-${this.tooltipPosition}`);

    const text = this.renderer.createText(this.glassTooltip);
    this.renderer.appendChild(this.tooltipElement, text);

    // Append to body
    this.renderer.appendChild(document.body, this.tooltipElement);

    // Position tooltip
    this.positionTooltip();

    // Animate in
    setTimeout(() => {
      if (this.tooltipElement) {
        this.renderer.addClass(this.tooltipElement, 'glass-tooltip-visible');
      }
    }, 10);
  }

  private hide() {
    if (!this.tooltipElement) return;

    this.renderer.removeClass(this.tooltipElement, 'glass-tooltip-visible');

    setTimeout(() => {
      if (this.tooltipElement) {
        this.renderer.removeChild(document.body, this.tooltipElement);
        this.tooltipElement = null;
      }
    }, 200);
  }

  private positionTooltip() {
    if (!this.tooltipElement) return;

    const hostPos = this.el.nativeElement.getBoundingClientRect();
    const tooltipPos = this.tooltipElement.getBoundingClientRect();
    const scrollPos = window.pageYOffset || document.documentElement.scrollTop || document.body.scrollTop || 0;
    const scrollPosX = window.pageXOffset || document.documentElement.scrollLeft || document.body.scrollLeft || 0;

    let top = 0;
    let left = 0;

    switch (this.tooltipPosition) {
      case 'top':
        top = hostPos.top + scrollPos - tooltipPos.height - 8;
        left = hostPos.left + scrollPosX + (hostPos.width - tooltipPos.width) / 2;
        break;
      case 'bottom':
        top = hostPos.bottom + scrollPos + 8;
        left = hostPos.left + scrollPosX + (hostPos.width - tooltipPos.width) / 2;
        break;
      case 'left':
        top = hostPos.top + scrollPos + (hostPos.height - tooltipPos.height) / 2;
        left = hostPos.left + scrollPosX - tooltipPos.width - 8;
        break;
      case 'right':
        top = hostPos.top + scrollPos + (hostPos.height - tooltipPos.height) / 2;
        left = hostPos.right + scrollPosX + 8;
        break;
    }

    this.renderer.setStyle(this.tooltipElement, 'top', `${top}px`);
    this.renderer.setStyle(this.tooltipElement, 'left', `${left}px`);
  }

  ngOnDestroy() {
    if (this.showTimeout) {
      clearTimeout(this.showTimeout);
    }
    this.hide();
  }
}
