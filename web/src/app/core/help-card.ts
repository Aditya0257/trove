import { Component, Input } from '@angular/core';

/**
 * A feature-level explainer — the two-channel Notice philosophy (D23) applied to help:
 * a plain "What this is" for everyone, and a "How it works" for anyone who wants the
 * technical/system-design detail. It's a collapsible card with a compact header, so a
 * repeat user isn't forced to re-read a paragraph every visit; set [open]="false" for
 * dense/often-seen spots (e.g. Search) and leave it open elsewhere. Visually distinct
 * from the small round-i field tooltips, so features and fields read differently.
 *
 * Usage: <trove-help-card title="How search works" user="…" dev="…" [open]="false" />
 */
@Component({
  selector: 'trove-help-card',
  standalone: true,
  template: `
    <div class="help-card" [class.open]="open">
      <button type="button" class="hc-head" (click)="open = !open" [attr.aria-expanded]="open">
        <span class="hc-tag">Help</span>
        <span class="hc-title">{{ title }}</span>
        <span class="chev">▾</span>
      </button>
      @if (open) {
        <div class="hc-body">
          <p class="hc-user">{{ user }}</p>
          @if (dev) {
            <div class="hc-dev">
              <div class="hc-dev-label">How it works</div>
              <p>{{ dev }}</p>
            </div>
          }
        </div>
      }
    </div>
  `,
  styles: [
    `
      /* A single accent touch (the left bar) reads as a callout without ringing the whole
         box in green; the rest is a neutral, card-like surface. */
      .help-card {
        border: 1px solid var(--line); border-left: 3px solid var(--accent);
        background: var(--accent-soft); border-radius: 0 10px 10px 0; margin: 6px 0 14px; overflow: hidden;
      }
      .hc-head {
        margin: 0; width: 100%; display: flex; align-items: center; gap: 8px;
        background: transparent; border: 0; padding: 9px 12px; cursor: pointer; text-align: left;
        color: var(--ink); font-size: 13px; font-weight: 600;
      }
      .hc-head:hover { background: var(--hover); }
      .hc-tag {
        flex: none; font-size: 10px; font-weight: 700; letter-spacing: 0.03em; text-transform: uppercase;
        color: var(--accent); background: var(--card); border: 1px solid var(--accent-line);
        border-radius: 5px; padding: 2px 6px;
      }
      .hc-title { flex: 1; }
      .chev { flex: none; color: var(--muted); transition: transform 150ms; }
      .help-card.open .chev { transform: rotate(180deg); }
      .hc-body { padding: 0 12px 11px; }
      .hc-user { margin: 0; font-size: 12.5px; line-height: 1.55; color: var(--ink); }
      .hc-dev { margin-top: 8px; border-top: 1px dashed var(--accent-line); padding-top: 8px; }
      .hc-dev-label {
        font-size: 10px; font-weight: 700; letter-spacing: 0.03em; text-transform: uppercase;
        color: var(--muted); margin-bottom: 3px;
      }
      .hc-dev p { margin: 0; font-size: 11.5px; line-height: 1.55; color: var(--muted); }
    `,
  ],
})
export class HelpCard {
  @Input() title = 'About this';
  @Input() user = '';
  @Input() dev = '';
  @Input() open = true;
}
