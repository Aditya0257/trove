import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { SpaceContext } from '../../core/space.context';
import { DocumentResponse, ReminderResponse } from '../../core/models';

@Component({
  selector: 'app-reminders',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="card">
      <h1>Reminders</h1>
      @if (dueCount() > 0) {
        <p class="warn">🔔 {{ dueCount() }} reminder(s) due now (on or before today).</p>
      }

      <form (ngSubmit)="create()" class="inline-form">
        <label>Type
          <select name="type" [(ngModel)]="type">
            <option value="due">due</option>
            <option value="renewal">renewal</option>
            <option value="warranty_expiry">warranty_expiry</option>
          </select>
        </label>
        <label>For document (optional)
          <select name="documentId" [(ngModel)]="documentId">
            <option value="">— none —</option>
            @for (d of documents(); track d.id) {
              <option [value]="d.id">{{ d.originalFilename || d.id }}{{ d.merchant ? ' · ' + d.merchant : '' }}</option>
            }
          </select>
        </label>
        <label>Remind on <input type="date" name="remindOn" [(ngModel)]="remindOn" required /></label>
        <button type="submit" [disabled]="!remindOn">Add reminder</button>
      </form>
      @if (error()) { <p class="error">{{ error() }}</p> }

      @if (reminders().length) {
        <table>
          <thead><tr><th>Type</th><th>Document</th><th>Remind on</th><th>Status</th><th></th></tr></thead>
          <tbody>
            @for (r of reminders(); track r.id) {
              <tr [class.due]="isDue(r)">
                <td>{{ r.type }}</td>
                <td>
                  @if (r.documentId) {
                    <a [routerLink]="['/documents', r.documentId, 'review']">{{ docName(r.documentId) }}</a>
                  } @else { — }
                </td>
                <td>{{ r.remindOn }}</td>
                <td>
                  @if (isDue(r)) { <span class="badge due">due now</span> }
                  @else { <span class="badge" [class.confirmed]="r.status !== 'pending'">{{ r.status }}</span> }
                </td>
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
  documents = signal<DocumentResponse[]>([]);
  type = 'due';
  documentId = '';
  remindOn = '';
  error = signal<string | null>(null);

  private readonly today = new Date().toISOString().slice(0, 10);
  dueCount = computed(() => this.reminders().filter((r) => this.isDue(r)).length);

  /** Pending and on/before today = the user should act on it now. */
  isDue(r: ReminderResponse): boolean {
    return r.status === 'pending' && r.remindOn <= this.today;
  }

  constructor() {
    effect(() => {
      const sid = this.spaceCtx.currentSpaceId();
      this.reload(sid);
      this.api.listDocuments(sid).subscribe((d) => this.documents.set(d));
    });
  }

  docName(id: string): string {
    const d = this.documents().find((x) => x.id === id);
    return d ? d.originalFilename || d.id : id;
  }

  private reload(spaceId?: string): void {
    this.api.listReminders(spaceId).subscribe((r) => this.reminders.set(r));
  }

  create(): void {
    if (!this.remindOn) return;
    this.error.set(null);
    const body: { type: string; remindOn: string; documentId?: string } = {
      type: this.type,
      remindOn: this.remindOn,
    };
    if (this.documentId) body.documentId = this.documentId;
    this.api.createReminder(body, this.spaceCtx.currentSpaceId()).subscribe({
      next: () => {
        this.remindOn = '';
        this.documentId = '';
        this.reload(this.spaceCtx.currentSpaceId());
      },
      error: (e) => this.error.set(e?.error?.message ?? 'Could not create reminder'),
    });
  }

  dismiss(id: string): void {
    this.api.dismissReminder(id).subscribe(() => this.reload(this.spaceCtx.currentSpaceId()));
  }
}
