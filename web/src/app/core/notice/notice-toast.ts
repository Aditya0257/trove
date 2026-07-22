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
        <span class="dot"></span>
        <div class="body">
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
      </div>
    }
  `,
  styles: [
    `
      :host {
        position: fixed;
        top: 18px;
        right: 18px;
        z-index: 1000;
        max-width: min(440px, calc(100vw - 36px));
      }
      .toast {
        display: flex;
        gap: 11px;
        align-items: flex-start;
        background: var(--surface, #fff);
        color: var(--text, #1a1a1a);
        border: 1px solid rgba(0, 0, 0, 0.06);
        border-radius: 14px;
        box-shadow: 0 12px 34px rgba(0, 0, 0, 0.16);
        padding: 14px 14px 14px 16px;
        animation: slide-in 200ms cubic-bezier(0.2, 0.8, 0.2, 1);
      }
      .toast[data-level='success'] { --accent: #2e7d5b; }
      .toast[data-level='warning'] { --accent: #b8860b; }
      .toast[data-level='error'] { --accent: #c0392b; }
      .toast[data-level='info'] { --accent: #2f6f6a; }
      .dot {
        flex: none; width: 10px; height: 10px; border-radius: 50%;
        background: var(--accent); margin-top: 5px;
        box-shadow: 0 0 0 4px rgba(0, 0, 0, 0.04);
      }
      .body { flex: 1; min-width: 0; }
      .row { display: flex; align-items: flex-start; gap: 8px; }
      .msg { margin: 0; flex: 1; font-size: 14px; line-height: 1.45; font-weight: 500; }
      .x {
        border: 0; background: transparent; cursor: pointer;
        font-size: 18px; line-height: 1; color: #9a9a9a; padding: 0 2px;
      }
      .x:hover { color: #555; }
      .dev-toggle {
        margin-top: 8px; border: 0; background: transparent; cursor: pointer;
        color: var(--accent); font-size: 12px; font-weight: 600; padding: 0;
        display: inline-flex; align-items: center; gap: 6px;
      }
      .chev { font-size: 10px; }
      .code {
        font-family: monospace; font-size: 11px; font-weight: 400;
        color: #8a8a8a; margin-left: 4px;
      }
      .dev-note {
        margin: 8px 0 0; font-family: monospace; font-size: 12px; line-height: 1.4;
        color: #4a4a4a; white-space: pre-wrap; word-break: break-word;
        background: rgba(0, 0, 0, 0.03); padding: 8px 10px; border-radius: 8px;
      }
      @keyframes slide-in {
        from { opacity: 0; transform: translateY(-10px) scale(0.98); }
        to { opacity: 1; transform: translateY(0) scale(1); }
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
