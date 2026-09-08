import { CommonModule } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, signal } from '@angular/core';
import { DashboardStateService } from '../services/dashboard-state.service';

/**
 * LayoutCustomizerComponent — hide/show, reorder and reset dashboard sections
 * (Requirement 12.1-12.5). Backed by DashboardStateService, which persists the
 * layout to localStorage (`exensioreload.dashboard.layout`).
 *
 * DOM note: section visibility is applied by the host via `layout-hidden`
 * bindings; ordering is persisted via DashboardStateService.moveSection.
 */
@Component({
  selector: 'app-layout-customizer',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="layout-customizer">
      <button type="button" class="lc-trigger" (click)="open.set(!open())" [attr.aria-expanded]="open()">
        <span class="lc-glyph" aria-hidden="true">▦</span>
        Layout
        @if (hiddenCount() > 0) {
          <span class="lc-badge">{{ hiddenCount() }}</span>
        }
      </button>

      @if (open()) {
        <div class="lc-dropdown" (click)="$event.stopPropagation()">
          <div class="lc-head">Customize layout</div>

          <div class="lc-sections">
            @for (section of sections(); track section.id) {
              <div class="lc-row" [class.hidden]="!section.visible">
                <span class="lc-title">{{ section.title }}</span>
                <div class="lc-actions">
                  <button
                    type="button"
                    class="lc-btn"
                    (click)="state.showSection(section.id)"
                    [disabled]="section.visible"
                    aria-label="Show {{ section.title }}"
                  >👁</button>
                  <button
                    type="button"
                    class="lc-btn"
                    (click)="state.hideSection(section.id)"
                    [disabled]="!section.visible"
                    aria-label="Hide {{ section.title }}"
                  >✕</button>
                  <button
                    type="button"
                    class="lc-btn"
                    (click)="state.moveSection(section.id, 'up')"
                    [disabled]="$first"
                    aria-label="Move {{ section.title }} up"
                  >↑</button>
                  <button
                    type="button"
                    class="lc-btn"
                    (click)="state.moveSection(section.id, 'down')"
                    [disabled]="$last"
                    aria-label="Move {{ section.title }} down"
                  >↓</button>
                </div>
              </div>
            }
          </div>

          <div class="lc-footer">
            <button type="button" class="lc-reset" (click)="state.resetLayout()">Reset layout</button>
          </div>
        </div>
      }
    </div>
  `,
  styles: [
    `
      .layout-customizer {
        position: relative;
        display: inline-block;
      }
      .lc-trigger {
        display: inline-flex;
        align-items: center;
        gap: 0.4rem;
        height: 36px;
        padding: 0 0.85rem;
        border-radius: 10px;
        border: 1px solid rgba(167, 139, 250, 0.22);
        background: rgba(67, 56, 132, 0.35);
        color: rgba(226, 232, 255, 0.92);
        font-size: 0.8rem;
        font-weight: 600;
        cursor: pointer;
      }
      .lc-trigger:hover {
        background: rgba(99, 102, 241, 0.28);
      }
      .lc-glyph {
        font-size: 0.95rem;
      }
      .lc-badge {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        min-width: 18px;
        height: 18px;
        padding: 0 4px;
        border-radius: 999px;
        background: rgba(129, 140, 248, 0.25);
        color: #c7d2fe;
        font-size: 0.68rem;
        font-weight: 800;
      }
      .lc-dropdown {
        position: absolute;
        right: 0;
        top: calc(100% + 6px);
        width: 300px;
        padding: 0.4rem;
        border-radius: 12px;
        border: 1px solid rgba(167, 139, 250, 0.2);
        background: rgba(24, 17, 52, 0.97);
        backdrop-filter: blur(12px);
        box-shadow: 0 12px 32px rgba(2, 8, 23, 0.55);
        z-index: 70;
      }
      .lc-head {
        padding: 0.4rem 0.6rem 0.5rem;
        font-size: 0.7rem;
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.06em;
        color: rgba(203, 213, 225, 0.55);
      }
      .lc-sections {
        max-height: 320px;
        overflow-y: auto;
        display: flex;
        flex-direction: column;
        gap: 2px;
      }
      .lc-row {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 0.5rem;
        padding: 0.35rem 0.6rem;
        border-radius: 8px;
      }
      .lc-row:hover {
        background: rgba(255, 255, 255, 0.03);
      }
      .lc-row.hidden .lc-title {
        opacity: 0.45;
        text-decoration: line-through;
      }
      .lc-title {
        font-size: 0.8rem;
        color: rgba(226, 232, 255, 0.9);
      }
      .lc-actions {
        display: flex;
        gap: 0.2rem;
      }
      .lc-btn {
        width: 24px;
        height: 24px;
        border-radius: 6px;
        border: 1px solid rgba(255, 255, 255, 0.08);
        background: transparent;
        color: rgba(203, 213, 225, 0.8);
        font-size: 0.72rem;
        line-height: 1;
        cursor: pointer;
      }
      .lc-btn:hover:not(:disabled) {
        background: rgba(129, 140, 248, 0.2);
      }
      .lc-btn:disabled {
        opacity: 0.3;
        cursor: default;
      }
      .lc-footer {
        padding-top: 0.4rem;
        margin-top: 0.3rem;
        border-top: 1px solid rgba(167, 139, 250, 0.14);
      }
      .lc-reset {
        width: 100%;
        height: 30px;
        border-radius: 8px;
        border: 1px solid rgba(167, 139, 250, 0.3);
        background: rgba(129, 140, 248, 0.12);
        color: #c7d2fe;
        font-size: 0.76rem;
        font-weight: 700;
        cursor: pointer;
      }
      .lc-reset:hover {
        background: rgba(129, 140, 248, 0.25);
      }
    `,
  ],
})
export class LayoutCustomizerComponent {
  readonly open = signal(false);

  readonly sections = computed(() => {
    const order = this.state.layout().customOrder;
    const byId = new Map(this.state.layout().sections.map((s) => [s.id, s]));
    return order
      .map((id) => byId.get(id))
      .filter((s): s is NonNullable<typeof s> => !!s)
      .map((s) => ({ ...s }));
  });

  readonly hiddenCount = computed(() => this.state.layout().hiddenSections.length);

  constructor(readonly state: DashboardStateService) {}
}
