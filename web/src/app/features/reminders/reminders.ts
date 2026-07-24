import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { SpaceContext } from '../../core/space.context';
import { DocumentResponse, ReminderResponse } from '../../core/models';
import { TroveSelect, SelectOption } from '../../core/select';

@Component({
  selector: 'app-reminders',
  imports: [FormsModule, RouterLink, TroveSelect],
  template: `
    <div class="card">
      <h1>Reminders</h1>
      @if (dueCount() > 0) {
        <p class="warn">🔔 {{ dueCount() }} reminder(s) due now (on or before today).</p>
      }

      <form (ngSubmit)="create()" class="inline-form">
        <label>Type
          <trove-select name="type" [(ngModel)]="type" [options]="typeOptions" ariaLabel="Reminder type"></trove-select>
        </label>
        <label>For document (optional)
          <trove-select name="documentId" [(ngModel)]="documentId" [options]="docOptions()" ariaLabel="For document"></trove-select>
        </label>
        <label>Remind on <input type="date" name="remindOn" [(ngModel)]="remindOn" required /></label>
        <button type="submit" [disabled]="!remindOn">Add reminder</button>
      </form>
      @if (error()) { <p class="error">{{ error() }}</p> }

      @if (loading()) {
        <p class="muted">Loading…</p>
      } @else if (reminders().length) {
        <table>
          <thead><tr><th>Type</th><th>Document</th><th>Remind on</th><th>Status</th><th></th></tr></thead>
          <tbody>
            @for (r of reminders(); track r.id) {
              <tr [class.due]="isDue(r)">
                <td>{{ r.type }}</td>
                <td>
                  @if (r.documentId) {
                    <a [routerLink]="['/documents', r.documentId, 'review']">{{ docName(r.documentId) }}</a>
                  } @else { <span class="muted">-</span> }
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
  loading = signal(true);
  type = 'due';
  documentId = '';
  remindOn = '';
  error = signal<string | null>(null);

  private readonly today = new Date().toISOString().slice(0, 10);
  dueCount = computed(() => this.reminders().filter((r) => this.isDue(r)).length);

  protected typeOptions: SelectOption[] = [
    { value: 'due', label: 'Due' },
    { value: 'renewal', label: 'Renewal' },
    { value: 'warranty_expiry', label: 'Warranty expiry' },
  ];
  /** "(none)" plus each document (filename + merchant as the second line). */
  protected docOptions = computed<SelectOption[]>(() => [
    { value: '', label: '(none)' },
    ...this.documents().map((d) => ({
      value: d.id,
      label: d.originalFilename || d.id,
      sub: d.merchant || undefined,
    })),
  ]);

  /** Pending and on/before today = the user should act on it now. */
  isDue(r: ReminderResponse): boolean {
    return r.status === 'pending' && r.remindOn <= this.today;
  }

  constructor() {
    effect(() => {
      const sid = this.spaceCtx.currentSpaceId();
      this.reload(sid);
    });
  }

  docName(id: string): string {
    const d = this.documents().find((x) => x.id === id);
    return d ? d.originalFilename || d.id : id;
  }

  /** Loads reminders AND documents together so the Document column shows filenames on
   *  the first paint - never a flash of raw UUIDs while the names catch up. */
  private reload(spaceId?: string): void {
    this.loading.set(true);
    forkJoin({
      reminders: this.api.listReminders(spaceId),
      documents: this.api.listDocuments(spaceId),
    }).subscribe({
      next: ({ reminders, documents }) => {
        this.documents.set(documents);
        this.reminders.set(reminders);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
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
