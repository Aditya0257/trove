import { Component, HostListener, inject } from '@angular/core';
import { ConfirmService } from '../../core/services/confirm.service';

/**
 * The single confirm dialog, mounted once at the app root. Renders whatever the
 * ConfirmService is asking, keeps itself open in a busy state while the confirmed action
 * runs (button shows "Deleting..."), and reports the choice back. Themed like the app.
 */
@Component({
  selector: 'trove-confirm-dialog',
  standalone: true,
  templateUrl: './confirm-dialog.html',
  styleUrl: './confirm-dialog.scss',
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
