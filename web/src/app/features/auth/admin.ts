import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { NoticeService } from '../../core/services/notice.service';
import { ConfirmService } from '../../core/services/confirm.service';
import { DateTimePipe } from '../../shared/pipes/datetime.pipe';
import { PendingUser } from '../../core/models/models';

/** Admin-only: approve or decline the people awaiting access to Trove. */
@Component({
  selector: 'app-admin',
  imports: [RouterLink, DateTimePipe],
  templateUrl: './admin.html',
  styleUrl: './admin.scss',
})
export class Admin {
  protected auth = inject(AuthService);
  private notices = inject(NoticeService);
  private confirm = inject(ConfirmService);

  pending = signal<PendingUser[]>([]);
  loading = signal(false);
  busy = signal(false);

  constructor() {
    if (this.auth.user()?.admin) this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.auth.adminPending().subscribe({
      next: (p) => { this.pending.set(p); this.loading.set(false); },
      error: () => this.loading.set(false),
    });
  }

  approve(u: PendingUser): void {
    this.busy.set(true);
    this.auth.adminApprove(u.id).subscribe({
      next: () => {
        this.pending.update((list) => list.filter((x) => x.id !== u.id));
        this.busy.set(false);
        this.notices.show({ level: 'success', code: 'APPROVED', userMessage: `Approved ${u.email}. They can sign in now.` });
      },
      error: (e) => { this.busy.set(false); this.notices.show({ level: 'error', code: 'APPROVE_FAIL', userMessage: e?.error?.message ?? 'Could not approve.' }); },
    });
  }

  reject(u: PendingUser): void {
    this.confirm.ask({ title: 'Decline access?', message: `Decline access for ${u.email}?`, confirmLabel: 'Decline', busyLabel: 'Declining...', danger: true })
      .then((ok) => { if (ok) this.rejectConfirmed(u); });
  }

  private rejectConfirmed(u: PendingUser): void {
    this.busy.set(true);
    this.auth.adminReject(u.id).subscribe({
      next: () => {
        this.confirm.close();
        this.pending.update((list) => list.filter((x) => x.id !== u.id));
        this.busy.set(false);
        this.notices.show({ level: 'info', code: 'REJECTED', userMessage: `Declined ${u.email}.` });
      },
      error: (e) => { this.confirm.close(); this.busy.set(false); this.notices.show({ level: 'error', code: 'REJECT_FAIL', userMessage: e?.error?.message ?? 'Could not decline.' }); },
    });
  }
}
