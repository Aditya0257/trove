import { Component, HostListener, inject } from '@angular/core';
import { ConfirmService } from './confirm.service';

/**
 * The single confirm dialog, mounted once at the app root. Renders whatever the
 * ConfirmService is asking, keeps itself open in a busy state while the confirmed action
 * runs (button shows "Deleting..."), and reports the choice back. Themed like the app.
 */
@Component({
  selector: 'trove-confirm-dialog',
  standalone: true,
  template: `
    @if (svc.current(); as c) {
      <div class="scrim" (click)="svc.cancel()"></div>
      <div class="modal" role="dialog" aria-modal="true">
        <h3>{{ c.title || 'Please confirm' }}</h3>
        <p>{{ c.message }}</p>
        <div class="actions">
          <button type="button" class="cancel" [disabled]="svc.running()" (click)="svc.cancel()">
            {{ c.cancelLabel || 'Cancel' }}
          </button>
          <button type="button" class="ok" [class.danger]="c.danger" [disabled]="svc.running()" (click)="svc.accept()">
            {{ svc.running() ? (c.busyLabel || 'Working...') : (c.confirmLabel || 'Confirm') }}
          </button>
        </div>
      </div>
    }
  `,
  styles: [
    `
      .scrim { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.45); z-index: 1200; }
      .modal {
        position: fixed; z-index: 1201; top: 50%; left: 50%; transform: translate(-50%, -50%);
        width: min(420px, 92vw); background: var(--card); color: var(--ink);
        border: 1px solid var(--line); border-radius: 12px; padding: 1.25rem 1.4rem;
        box-shadow: 0 20px 60px var(--shadow);
      }
      .modal h3 { margin: 0 0 0.5rem; font-size: 1.05rem; }
      .modal p { margin: 0; line-height: 1.55; color: var(--ink); }
      .actions { display: flex; gap: 10px; justify-content: flex-end; margin-top: 1.25rem; }
      .actions button { margin: 0; padding: 0.5rem 1.1rem; border-radius: 8px; cursor: pointer; font-weight: 600; }
      .actions button:disabled { opacity: 0.65; cursor: default; }
      .cancel { background: transparent; border: 1px solid var(--line); color: var(--muted); }
      .cancel:hover:not(:disabled) { background: var(--hover); }
      .ok { background: var(--brand); color: var(--brand-ink); border: 0; }
      .ok:hover:not(:disabled) { filter: brightness(1.05); }
      .ok.danger { background: var(--danger, #c0392b); color: #fff; }
    `,
  ],
})
export class ConfirmDialog {
  protected svc = inject(ConfirmService);

  /** Enter confirms, Escape cancels - ignored while the action is running. */
  @HostListener('document:keydown', ['$event'])
  onKey(e: KeyboardEvent): void {
    if (!this.svc.current() || this.svc.running()) {
      return;
    }
    if (e.key === 'Escape') {
      this.svc.cancel();
    } else if (e.key === 'Enter') {
      this.svc.accept();
    }
  }
}
