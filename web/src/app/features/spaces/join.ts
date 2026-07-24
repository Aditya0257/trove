import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';

/** Landing for a space join link (?token=...): requests to join, owner approves later. */
@Component({
  selector: 'app-join',
  imports: [RouterLink],
  template: `
    <div class="card auth-card">
      <h1>Join a space</h1>
      @if (result()) {
        <p>✅ Your request to join <b>{{ result()!.spaceName }}</b> has been sent. The owner will
          approve it, and then the space appears in your switcher at the top.</p>
        <p class="muted"><a routerLink="/spaces">Go to Spaces</a></p>
      } @else if (error()) {
        <p class="error">{{ error() }}</p>
        <p class="muted"><a routerLink="/documents">Back home</a></p>
      } @else {
        <p class="muted">Sending your request…</p>
      }
    </div>
  `,
})
export class Join {
  private api = inject(ApiService);
  private route = inject(ActivatedRoute);

  result = signal<{ spaceId: string; spaceName: string } | null>(null);
  error = signal<string | null>(null);

  constructor() {
    const token = this.route.snapshot.queryParamMap.get('token') ?? '';
    if (!token) {
      this.error.set('This join link is missing its token.');
      return;
    }
    this.api.requestJoinSpace(token).subscribe({
      next: (r) => this.result.set(r),
      error: (e) => this.error.set(e?.error?.message ?? 'This join link is invalid or has been revoked.'),
    });
  }
}
