import { Component, HostListener, inject } from '@angular/core';
import { ConfirmService } from './confirm.service';

/**
 * The single confirm dialog, mounted once at the app root. Renders whatever the
 * ConfirmService is currently asking, and reports the user's choice back through it.
 * Themed like the rest of the app - no more "localhost says" browser box.
 */
@Component({
  selector: 'trove-confirm-dialog',
  standalone: true,
  template: `
    @if (svc.current(); as c) {
      <div class="scrim" (click)="svc.respond(false)"></div>
      <div class="modal" role="dialog" aria-modal="true">
        <h3>{{ c.title || 'Please confirm' }}</h3>
        <p>{{ c.message }}</p>
        <div class="actions">
          <button type="button" class="cancel" (click)="svc.respond(false)">{{ c.cancelLabel || 'Cancel' }}</button>
          <button type="button" class="ok" [class.danger]="c.danger" (click)="svc.respond(true)">
            {{ c.confirmLabel || 'Confirm' }}
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
      .cancel { background: transparent; border: 1px solid var(--line); color: var(--muted); }
      .cancel:hover { background: var(--hover); }
      .ok { background: var(--brand); color: var(--brand-ink); border: 0; }
      .ok:hover { filter: brightness(1.05); }
      .ok.danger { background: var(--danger, #b4402f); }
    `,
  ],
})
export class ConfirmDialog {
  protected svc = inject(ConfirmService);

  /** Enter confirms, Escape cancels - only while a dialog is open. */
  @HostListener('document:keydown', ['$event'])
  onKey(e: KeyboardEvent): void {
    if (!this.svc.current()) {
      return;
    }
    if (e.key === 'Escape') {
      this.svc.respond(false);
    } else if (e.key === 'Enter') {
      this.svc.respond(true);
    }
  }
}
