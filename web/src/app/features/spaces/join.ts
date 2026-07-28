import { Component, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';

/** Landing for a space join link (?token=...): requests to join, owner approves later. */
@Component({
  selector: 'app-join',
  imports: [RouterLink],
  templateUrl: './join.html',
})
export class Join {
  private api = inject(ApiService);
  private route = inject(ActivatedRoute);

  result = signal<{ spaceId: string; spaceName: string; status: string } | null>(null);
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
