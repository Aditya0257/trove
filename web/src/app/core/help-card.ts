import { Component, Input } from '@angular/core';

/**
 * A feature-level explainer — the two-channel Notice philosophy (D23) applied to help:
 * a plain "What this is" for everyone, and a collapsible "How it works" for anyone who
 * wants the technical/system-design detail. Visually distinct from the small round-i
 * field tooltips (this is a card), so features and fields read differently.
 *
 * Usage: <trove-help-card user="…" dev="…" [title]="'Optional heading'"></trove-help-card>
 */
@Component({
  selector: 'trove-help-card',
  standalone: true,
  template: `
    <div class="help-card">
      @if (title) { <div class="hc-title">{{ title }}</div> }
      <div class="hc-user">
        <span class="hc-tag">What this is</span>
        <p>{{ user }}</p>
      </div>
      @if (dev) {
        <details class="hc-dev">
          <summary>How it works<span class="chev">▾</span></summary>
          <p>{{ dev }}</p>
        </details>
      }
    </div>
  `,
  styles: [
    `
      .help-card {
        border: 1px solid var(--accent-line); border-left: 3px solid var(--accent);
        background: var(--accent-soft); border-radius: 10px; padding: 10px 12px; margin: 6px 0 14px;
      }
      .hc-title { font-weight: 700; font-size: 13px; color: var(--ink); margin-bottom: 4px; }
      .hc-user { display: flex; gap: 8px; align-items: baseline; }
      .hc-user p { margin: 0; font-size: 12.5px; line-height: 1.5; color: var(--ink); }
      .hc-tag {
        flex: none; font-size: 10px; font-weight: 700; letter-spacing: 0.03em; text-transform: uppercase;
        color: var(--accent); background: var(--card); border: 1px solid var(--accent-line);
        border-radius: 5px; padding: 2px 6px; margin-top: 1px;
      }
      .hc-dev { margin-top: 8px; }
      .hc-dev summary {
        cursor: pointer; font-size: 11.5px; font-weight: 600; color: var(--muted);
        list-style: none; display: inline-flex; align-items: center; gap: 4px; user-select: none;
      }
      .hc-dev summary::-webkit-details-marker { display: none; }
      .hc-dev .chev { transition: transform 150ms; }
      .hc-dev[open] .chev { transform: rotate(180deg); }
      .hc-dev p {
        margin: 6px 0 0; font-size: 11.5px; line-height: 1.5; color: var(--muted);
        border-top: 1px dashed var(--accent-line); padding-top: 6px;
      }
    `,
  ],
})
export class HelpCard {
  @Input() user = '';
  @Input() dev = '';
  @Input() title = '';
}
