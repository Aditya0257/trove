import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { NoticeService } from '../../core/notice/notice.service';
import { DateTimePipe } from '../../core/datetime.pipe';
import { PendingUser } from '../../core/models';

/** Admin-only: approve or decline the people awaiting access to Trove. */
@Component({
  selector: 'app-admin',
  imports: [RouterLink, DateTimePipe],
  template: `
    <div class="card">
      <h1>Access requests</h1>
      @if (!auth.user()?.admin) {
        <p class="muted">This page is for the admin. <a routerLink="/documents">Back to documents</a>.</p>
      } @else {
        <p class="muted">People who signed up and are waiting for your approval before they can sign in.</p>
        @if (loading()) { <p class="muted">Loading…</p> }
        @else if (pending().length === 0) { <p class="muted">No pending requests right now.</p> }
        @else {
          <div class="reqs">
            @for (u of pending(); track u.id) {
              <div class="req">
                <div class="who">
                  <span class="name">{{ u.displayName }}</span>
                  <span class="muted email">{{ u.email }}</span>
                  <span class="muted small">requested {{ u.requestedAt | prettyDate }}</span>
                </div>
                <div class="actions">
                  <button type="button" (click)="approve(u)" [disabled]="busy()">Approve</button>
                  <button type="button" class="btn-ghost" (click)="reject(u)" [disabled]="busy()">Decline</button>
                </div>
              </div>
            }
          </div>
        }
      }
    </div>
  `,
  styles: [
    `
      .reqs { display: flex; flex-direction: column; gap: 8px; margin-top: 8px; }
      .req {
        display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap;
        border: 1px solid var(--line); border-radius: 10px; padding: 12px 14px;
      }
      .who { display: flex; flex-direction: column; gap: 2px; }
      .name { font-weight: 600; }
      .email { font-family: monospace; font-size: 13px; }
      .small { font-size: 12px; }
      .actions { display: flex; gap: 8px; }
      .actions button { margin: 0; }
      .btn-ghost { background: transparent; color: var(--muted); border: 1px solid var(--line); }
      .btn-ghost:hover { background: var(--hover); }
    `,
  ],
})
export class Admin {
  protected auth = inject(AuthService);
  private notices = inject(NoticeService);

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
    if (!confirm(`Decline access for ${u.email}?`)) return;
    this.busy.set(true);
    this.auth.adminReject(u.id).subscribe({
      next: () => {
        this.pending.update((list) => list.filter((x) => x.id !== u.id));
        this.busy.set(false);
        this.notices.show({ level: 'info', code: 'REJECTED', userMessage: `Declined ${u.email}.` });
      },
      error: (e) => { this.busy.set(false); this.notices.show({ level: 'error', code: 'REJECT_FAIL', userMessage: e?.error?.message ?? 'Could not decline.' }); },
    });
  }
}
