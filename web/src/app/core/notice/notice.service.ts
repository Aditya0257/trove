import { Injectable, signal } from '@angular/core';
import { Notice } from './notice.model';

/**
 * Holds the single active toast. Anything (interceptor, a component, a guard) can
 * push a Notice here; the root renders whatever `current` holds and it self-clears
 * after a level-dependent delay. Kept deliberately dumb - pure transport, no styling.
 */
@Injectable({ providedIn: 'root' })
export class NoticeService {
  private readonly _current = signal<Notice | null>(null);
  private timer: ReturnType<typeof setTimeout> | undefined;

  /** The toast currently on screen, or null. */
  readonly current = this._current.asReadonly();

  show(notice: Notice): void {
    this._current.set(notice);
    clearTimeout(this.timer);
    const ms = notice.level === 'error' ? 8000 : notice.level === 'warning' ? 7000 : 4000;
    this.timer = setTimeout(() => this.dismiss(), ms);
  }

  dismiss(): void {
    clearTimeout(this.timer);
    this._current.set(null);
  }
}
