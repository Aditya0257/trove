import { Injectable, signal } from '@angular/core';

/** A pending confirmation the dialog renders; resolve() settles the caller's Promise. */
export interface ConfirmRequest {
  title?: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  danger?: boolean;
  resolve: (ok: boolean) => void;
}

/**
 * In-app confirm dialog (replaces the native window.confirm). Call ask(...) and await the
 * boolean; a single <trove-confirm-dialog> mounted at the app root renders the request.
 */
@Injectable({ providedIn: 'root' })
export class ConfirmService {
  readonly current = signal<ConfirmRequest | null>(null);

  ask(opts: {
    message: string;
    title?: string;
    confirmLabel?: string;
    cancelLabel?: string;
    danger?: boolean;
  }): Promise<boolean> {
    return new Promise<boolean>((resolve) => this.current.set({ ...opts, resolve }));
  }

  respond(ok: boolean): void {
    const c = this.current();
    if (c) {
      this.current.set(null);
      c.resolve(ok);
    }
  }
}
