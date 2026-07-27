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
  templateUrl: './notice-toast.html',
  styleUrl: './notice-toast.scss',
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
