import { Component, effect, inject, signal } from '@angular/core';
import { NoticeService } from '../../core/services/notice.service';

/**
 * The on-screen toast: a calm one-liner for everyone, with the developer note one
 * click away. Colour-coded by level, self-dismissing (handled by NoticeService),
 * fixed to the bottom-centre so it never covers the nav/actions. Mounted once at the app root.
 */
@Component({
  selector: 'trove-notice-toast',
  standalone: true,
  template: `
    @if (notice(); as n) {
      <div class="toast" [attr.data-level]="n.level">
        <button class="x" (click)="notices.dismiss()" aria-label="Dismiss">×</button>
        <span class="dot"></span>
        <div class="body">
          <p class="msg">{{ n.userMessage }}</p>
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
        /* Bottom-centre: clear of the top nav/actions and of the two corner launchers
           (dev pill bottom-left, Ask bottom-right), so a toast never covers a control. */
        position: fixed;
        bottom: 22px;
        left: 50%;
        transform: translateX(-50%);
        z-index: 1000;
        width: max-content;
        max-width: min(460px, calc(100vw - 32px));
      }
      .toast {
        position: relative;
        display: flex;
        gap: 11px;
        align-items: flex-start;
        background: var(--card);
        color: var(--ink);
        border: 1px solid rgba(0, 0, 0, 0.06);
        border-radius: 14px;
        box-shadow: 0 12px 34px rgba(0, 0, 0, 0.16);
        padding: 14px 40px 14px 16px;   /* room on the right for the corner close button */
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
      .msg { margin: 0; font-size: 14px; line-height: 1.45; font-weight: 500; }
      /* Close pinned to the top-right corner of the card, aligned with the first text line. */
      .x {
        position: absolute; top: 8px; right: 8px; margin: 0;
        width: 24px; height: 24px; border: 0; border-radius: 7px; background: transparent;
        cursor: pointer; font-size: 17px; line-height: 1; color: var(--muted);
        display: inline-flex; align-items: center; justify-content: center;
      }
      .x:hover { color: var(--ink); background: var(--hover); }
      .dev-toggle {
        margin-top: 8px; border: 0; background: transparent; cursor: pointer;
        color: var(--accent); font-size: 12px; font-weight: 600; padding: 0;
        display: inline-flex; align-items: center; gap: 6px;
      }
      .chev { font-size: 10px; }
      .code {
        font-family: monospace; font-size: 11px; font-weight: 400;
        color: var(--muted); margin-left: 4px;
      }
      .dev-note {
        margin: 8px 0 0; font-family: monospace; font-size: 12px; line-height: 1.4;
        color: var(--muted); white-space: pre-wrap; word-break: break-word;
        background: var(--code-bg); padding: 8px 10px; border-radius: 8px;
      }
      @keyframes slide-in {
        from { opacity: 0; transform: translateY(12px) scale(0.98); }
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
