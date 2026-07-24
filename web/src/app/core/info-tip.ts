import { Component, Input } from '@angular/core';

/**
 * A small round "?" that reveals a short explanation on hover/focus - for telling the
 * user what a button/metric does BEFORE they click. Lighter than the collapsible
 * help-card; use it inline next to a control or a label.
 *
 * Usage: <trove-info-tip text="Checks every copy exists." align="right" />
 * align="left" (default) grows the bubble rightward; "right" grows it leftward (use near
 * the right edge of the screen).
 */
@Component({
  selector: 'trove-info-tip',
  standalone: true,
  template: `
    <span class="tip" tabindex="0" role="img" [attr.aria-label]="text">
      ?
      <span class="bubble" [class.right]="align === 'right'">{{ text }}</span>
    </span>
  `,
  styles: [
    `
      .tip {
        display: inline-flex; align-items: center; justify-content: center;
        width: 15px; height: 15px; border-radius: 50%; cursor: help; position: relative;
        font-size: 10px; font-weight: 700; vertical-align: middle;
        color: var(--muted); background: var(--hover); border: 1px solid var(--line);
        outline: none;
      }
      .tip:hover, .tip:focus { color: var(--accent); border-color: var(--accent-line); }
      .bubble {
        visibility: hidden; opacity: 0; position: absolute; top: calc(100% + 7px); left: 0;
        width: max-content; max-width: 240px; z-index: 50;
        background: #222; color: #fff; padding: 7px 10px; border-radius: 8px;
        font-size: 11px; font-weight: 400; line-height: 1.45; text-align: left;
        box-shadow: 0 6px 20px rgba(0, 0, 0, 0.3); pointer-events: none;
        transition: opacity 120ms;
      }
      .bubble.right { left: auto; right: 0; }
      .tip:hover .bubble, .tip:focus .bubble { visibility: visible; opacity: 1; }
    `,
  ],
})
export class InfoTip {
  @Input() text = '';
  @Input() align: 'left' | 'right' = 'left';
}
