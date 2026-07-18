import { Component, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api.service';
import { SpaceContext } from '../../core/space.context';
import { ReminderResponse } from '../../core/models';

@Component({
  selector: 'app-reminders',
  imports: [FormsModule],
  template: `
    <div class="card">
      <h1>Reminders</h1>

      <form (ngSubmit)="create()" class="inline-form">
        <label>Type
          <select name="type" [(ngModel)]="type">
            <option value="due">due</option>
            <option value="renewal">renewal</option>
            <option value="warranty_expiry">warranty_expiry</option>
          </select>
        </label>
        <label>Remind on <input type="date" name="remindOn" [(ngModel)]="remindOn" required /></label>
        <button type="submit" [disabled]="!remindOn">Add reminder</button>
      </form>
      @if (error()) { <p class="error">{{ error() }}</p> }

      @if (reminders().length) {
        <table>
          <thead><tr><th>Type</th><th>Remind on</th><th>Status</th><th></th></tr></thead>
          <tbody>
            @for (r of reminders(); track r.id) {
              <tr>
                <td>{{ r.type }}</td>
                <td>{{ r.remindOn }}</td>
                <td><span class="badge" [class.confirmed]="r.status !== 'pending'">{{ r.status }}</span></td>
                <td>
                  @if (r.status !== 'dismissed') {
                    <button class="link" (click)="dismiss(r.id)">Dismiss</button>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
      } @else { <p class="muted">No reminders. A "due" reminder is created automatically
        when you confirm a document that has a due date.</p> }
    </div>
  `,
})
export class Reminders {
  private api = inject(ApiService);
  private spaceCtx = inject(SpaceContext);

  reminders = signal<ReminderResponse[]>([]);
  type = 'due';
  remindOn = '';
  error = signal<string | null>(null);

  constructor() {
    effect(() => {
      const sid = this.spaceCtx.currentSpaceId();
      this.reload(sid);
    });
  }

  private reload(spaceId?: string): void {
    this.api.listReminders(spaceId).subscribe((r) => this.reminders.set(r));
  }

  create(): void {
    if (!this.remindOn) return;
    this.error.set(null);
    this.api.createReminder({ type: this.type, remindOn: this.remindOn }, this.spaceCtx.currentSpaceId()).subscribe({
      next: () => {
        this.remindOn = '';
        this.reload(this.spaceCtx.currentSpaceId());
      },
      error: (e) => this.error.set(e?.error?.message ?? 'Could not create reminder'),
    });
  }

  dismiss(id: string): void {
    this.api.dismissReminder(id).subscribe(() => this.reload(this.spaceCtx.currentSpaceId()));
  }
}
