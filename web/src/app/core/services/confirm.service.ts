import { Injectable, signal } from '@angular/core';

/** A pending confirmation the dialog renders; resolve() settles the caller's Promise. */
export interface ConfirmRequest {
  title?: string;
  message: string;
  confirmLabel?: string;
  busyLabel?: string;      // shown on the confirm button while the action runs
  cancelLabel?: string;
  danger?: boolean;
  resolve: (ok: boolean) => void;
}

/**
 * In-app confirm dialog (replaces the native window.confirm). Call ask(...) and await the
 * boolean. On confirm the dialog STAYS OPEN in a busy state (button shows busyLabel) so the
 * user sees the 1-2s action running; the caller calls close() when it finishes.
 *
 * Pattern:
 *   if (!(await this.confirm.ask({ message, danger: true, busyLabel: 'Deleting...' }))) return;
 *   this.api.delete(id).subscribe({ next: () => this.confirm.close(), error: () => this.confirm.close() });
 */
@Injectable({ providedIn: 'root' })
export class ConfirmService {
  readonly current = signal<ConfirmRequest | null>(null);
  readonly running = signal(false);

  ask(opts: {
    message: string;
    title?: string;
    confirmLabel?: string;
    busyLabel?: string;
    cancelLabel?: string;
    danger?: boolean;
  }): Promise<boolean> {
    this.running.set(false);
    return new Promise<boolean>((resolve) => this.current.set({ ...opts, resolve }));
  }

  /** User pressed confirm: keep the dialog open in a busy state and resolve true. */
  accept(): void {
    const c = this.current();
    if (c && !this.running()) {
      this.running.set(true);
      c.resolve(true);
    }
  }

  /** User cancelled (or scrim/Esc): close and resolve false. */
  cancel(): void {
    const c = this.current();
    if (c && !this.running()) {
      this.current.set(null);
      c.resolve(false);
    }
  }

  /** Caller's action finished (success or error): dismiss the busy dialog. */
  close(): void {
    this.current.set(null);
    this.running.set(false);
  }
}
