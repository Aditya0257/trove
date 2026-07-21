import { Component, effect, inject, signal } from '@angular/core';
import { NoticeService } from './notice.service';

/**
 * The on-screen toast: a calm one-liner for everyone, with the developer note one
 * click away. Colour-coded by level, self-dismissing (handled by NoticeService),
 * fixed to the top-right. Mounted once at the app root.
 */
@Component({
  selector: 'trove-notice-toast',
  standalone: true,
  template: `
    @if (notice(); as n) {
      <div class="toast" [attr.data-level]="n.level">
        <div class="row">
          <p class="msg">{{ n.userMessage }}</p>
          <button class="x" (click)="notices.dismiss()" aria-label="Dismiss">×</button>
        </div>
        @if (n.devNote) {
          <button class="dev-toggle" (click)="expanded.set(!expanded())">
            <span class="chev">{{ expanded() ? '▾' : '▸' }}</span> Developer note
            <span class="code">{{ n.code }}</span>
          </button>
          @if (expanded()) {
            <pre class="dev-note">{{ n.devNote }}</pre>
          }
        }
      </div>
    }
  `,
  styles: [
    `
      :host {
        position: fixed;
        top: 16px;
        right: 16px;
        z-index: 1000;
        max-width: min(420px, calc(100vw - 32px));
      }
      .toast {
        background: var(--surface, #fff);
        color: var(--text, #1a1a1a);
        border-radius: 12px;
        border-left: 4px solid var(--accent);
        box-shadow: 0 8px 28px rgba(0, 0, 0, 0.18);
        padding: 12px 8px 12px 14px;
        animation: slide-in 160ms ease-out;
      }
      .toast[data-level='success'] { --accent: #2e7d5b; }
      .toast[data-level='warning'] { --accent: #b8860b; }
      .toast[data-level='error'] { --accent: #c0392b; }
      .toast[data-level='info'] { --accent: #2f6f6a; }
      .row { display: flex; align-items: flex-start; gap: 8px; }
      .msg { margin: 0; flex: 1; font-size: 14px; line-height: 1.4; }
      .x {
        border: 0; background: transparent; cursor: pointer;
        font-size: 18px; line-height: 1; color: #8a8a8a; padding: 0 4px;
      }
      .dev-toggle {
        margin-top: 6px; border: 0; background: transparent; cursor: pointer;
        color: var(--accent); font-size: 12px; font-weight: 600; padding: 0;
        display: inline-flex; align-items: center; gap: 6px;
      }
      .chev { font-size: 10px; }
      .code {
        font-family: monospace; font-size: 11px; font-weight: 400;
        color: #8a8a8a; margin-left: 4px;
      }
      .dev-note {
        margin: 6px 0 0; font-family: monospace; font-size: 12px; line-height: 1.35;
        color: #555; white-space: pre-wrap; word-break: break-word;
      }
      @keyframes slide-in {
        from { opacity: 0; transform: translateY(-8px); }
        to { opacity: 1; transform: translateY(0); }
      }
    `,
  ],
})
export class NoticeToast {
  protected notices = inject(NoticeService);
  protected notice = this.notices.current;
  protected expanded = signal(false);

  constructor() {
    // Collapse the developer note whenever a new notice appears.
    effect(() => {
      this.notice();
      this.expanded.set(false);
    });
  }
}
