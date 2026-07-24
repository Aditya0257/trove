import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { SpaceContext } from '../../core/space.context';
import { NoticeService } from '../../core/notice/notice.service';
import { DocumentResponse, ReminderResponse } from '../../core/models';
import { TroveSelect, SelectOption } from '../../core/select';
import { HelpCard } from '../../core/help-card';

/** The lifecycle buckets a reminder can sit in, as shown by the tabs. */
type TabKey = 'due' | 'upcoming' | 'done' | 'dismissed';

/** The editable fields of a reminder, shared by the create form and the edit dialog. */
interface ReminderForm {
  title: string;
  type: string;
  documentId: string;
  recurrence: string;
  remindOn: string;
}

@Component({
  selector: 'app-reminders',
  imports: [FormsModule, RouterLink, TroveSelect, HelpCard],
  template: `
    <div class="card">
      <h1>Reminders</h1>
      <trove-help-card title="How reminders work" [open]="false" [user]="helpUser" [dev]="helpDev"></trove-help-card>

      @if (dueCount() > 0) {
        <p class="warn">🔔 {{ dueCount() }} reminder{{ dueCount() > 1 ? 's' : '' }} {{ dueCount() > 1 ? 'need' : 'needs' }} your attention (due on or before today).</p>
      }

      <!-- Create a new reminder. Editing an existing one happens in the dialog below. -->
      <form (ngSubmit)="add()" class="reminder-form">
        <div class="form-grid">
          <label class="wide">Title (optional)
            <input name="title" [(ngModel)]="form.title" placeholder="e.g. Rent - pay landlord" />
          </label>
          <label>Type
            <trove-select name="type" [(ngModel)]="form.type" [options]="typeOptions" ariaLabel="Reminder type"></trove-select>
          </label>
          <label>Repeat
            <trove-select name="recurrence" [(ngModel)]="form.recurrence" [options]="recurrenceOptions" ariaLabel="Repeat"></trove-select>
          </label>
          <label>For document (optional)
            <trove-select name="documentId" [(ngModel)]="form.documentId" [options]="docOptions()" ariaLabel="For document"></trove-select>
          </label>
          <label>Remind on <input type="date" name="remindOn" [(ngModel)]="form.remindOn" required /></label>
        </div>
        <div class="form-actions">
          <button type="submit" [disabled]="!form.remindOn || saving()">{{ saving() ? 'Saving…' : 'Add reminder' }}</button>
        </div>
      </form>
      @if (error()) { <p class="error">{{ error() }}</p> }

      <!-- Lifecycle tabs. -->
      <div class="tabs">
        @for (t of tabs; track t.key) {
          <button type="button" class="tab" [class.on]="tab() === t.key" (click)="tab.set(t.key)">
            {{ t.label }} <span class="count">{{ countFor(t.key) }}</span>
          </button>
        }
      </div>

      @if (loading()) {
        <p class="muted">Loading…</p>
      } @else if (shownReminders().length === 0) {
        <p class="muted">{{ emptyMsg() }}</p>
      } @else {
        <div class="table-scroll">
          <table>
            <thead><tr><th>Reminder</th><th>When</th><th>Repeat</th><th>Status</th><th class="act-col"></th></tr></thead>
            <tbody>
              @for (r of shownReminders(); track r.id) {
                <tr [class.due]="isDue(r)">
                  <td>
                    <div class="r-title">{{ r.title || rType(r.type) }}</div>
                    @if (r.documentId) {
                      <a class="r-doc" [routerLink]="['/documents', r.documentId, 'review']">{{ docName(r.documentId) }}</a>
                    } @else if (r.title) {
                      <div class="r-sub">{{ rType(r.type) }}</div>
                    }
                  </td>
                  <td>{{ r.remindOn }}</td>
                  <td>{{ r.recurrence === 'none' ? '-' : rRecur(r.recurrence) }}</td>
                  <td>
                    @if (isDue(r)) { <span class="badge due">due now</span> }
                    @else { <span class="badge" [class.ok]="r.status === 'done'">{{ statusLabel(r) }}</span> }
                  </td>
                  <td class="r-actions">
                    @if (isActive(r)) {
                      @if (snoozeOpen() === r.id) {
                        <!-- Inline snooze options: in normal flow, so nothing gets clipped. -->
                        <span class="snooze-inline">
                          <span class="snooze-label">Snooze:</span>
                          <button type="button" class="mini ghost" (click)="snooze(r, 1)">1 day</button>
                          <button type="button" class="mini ghost" (click)="snooze(r, 3)">3 days</button>
                          <button type="button" class="mini ghost" (click)="snooze(r, 7)">1 week</button>
                          <button type="button" class="mini ghost" (click)="toggleSnooze(r.id)" aria-label="Cancel snooze">✕</button>
                        </span>
                      } @else {
                        <button type="button" class="mini" (click)="markDone(r)">Done</button>
                        <button type="button" class="mini ghost" (click)="toggleSnooze(r.id)">Snooze</button>
                        <button type="button" class="mini ghost" (click)="edit(r)">Edit</button>
                        <button type="button" class="mini ghost danger" (click)="dismiss(r)">Dismiss</button>
                      }
                    } @else {
                      <button type="button" class="mini ghost" (click)="snooze(r, 0)">Reopen</button>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </div>

    <!-- Edit dialog: a focused overlay so it is clear you are editing THIS reminder. -->
    @if (editing()) {
      <div class="scrim" (click)="cancelEdit()"></div>
      <div class="modal" role="dialog" aria-modal="true" aria-label="Edit reminder">
        <h3>Edit reminder</h3>
        <label>Title (optional)
          <input name="etitle" [(ngModel)]="editForm.title" placeholder="e.g. Rent - pay landlord" />
        </label>
        <div class="modal-grid">
          <label>Type
            <trove-select name="etype" [(ngModel)]="editForm.type" [options]="typeOptions" ariaLabel="Reminder type"></trove-select>
          </label>
          <label>Repeat
            <trove-select name="erecurrence" [(ngModel)]="editForm.recurrence" [options]="recurrenceOptions" ariaLabel="Repeat"></trove-select>
          </label>
          <label>For document (optional)
            <trove-select name="edocumentId" [(ngModel)]="editForm.documentId" [options]="docOptions()" ariaLabel="For document"></trove-select>
          </label>
          <label>Remind on <input type="date" name="eremindOn" [(ngModel)]="editForm.remindOn" required /></label>
        </div>
        <div class="modal-actions">
          <button type="button" class="btn-ghost" (click)="cancelEdit()">Cancel</button>
          <button type="button" (click)="saveEdit()" [disabled]="!editForm.remindOn || saving()">
            {{ saving() ? 'Saving…' : 'Save changes' }}
          </button>
        </div>
      </div>
    }
  `,
  styles: [
    `
      .warn { color: var(--danger); font-weight: 600; }
      .reminder-form { margin: 8px 0 4px; }
      .form-grid { display: flex; flex-wrap: wrap; gap: 12px 16px; align-items: end; }
      .form-grid label { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: var(--muted); }
      .form-grid label.wide { flex: 1 1 220px; }
      .form-grid input, .form-grid trove-select { min-width: 150px; }
      .form-actions { display: flex; gap: 10px; align-items: center; margin-top: 12px; }
      .form-actions button { margin: 0; }
      .btn-ghost { background: transparent; color: var(--muted); border: 1px solid var(--line); border-radius: 8px; padding: 0.6rem 1.1rem; cursor: pointer; }
      .btn-ghost:hover { background: var(--hover); }

      .tabs { display: flex; flex-wrap: wrap; gap: 8px; margin: 18px 0 12px; border-bottom: 1px solid var(--line); }
      .tab {
        margin: 0; border: 0; background: transparent; color: var(--muted); cursor: pointer;
        padding: 8px 12px; font-size: 13px; font-weight: 600; border-bottom: 2px solid transparent;
      }
      .tab:hover { color: var(--ink); }
      .tab.on { color: var(--accent); border-bottom-color: var(--accent); }
      .tab .count {
        display: inline-block; min-width: 18px; text-align: center; font-size: 11px; margin-left: 2px;
        background: var(--hover); border-radius: 999px; padding: 0 6px; color: var(--muted);
      }
      .tab.on .count { background: var(--accent-soft); color: var(--accent); }

      .table-scroll { overflow-x: auto; max-width: 100%; }
      .table-scroll table { min-width: 640px; }
      .act-col { width: 1%; }
      tr.due td { background: var(--danger-soft); }
      .r-title { font-weight: 600; }
      .r-doc { font-size: 12px; color: var(--accent); text-decoration: none; }
      .r-doc:hover { text-decoration: underline; }
      .r-sub { font-size: 12px; color: var(--muted); }
      .badge.ok { background: var(--accent-soft); color: var(--accent); }

      .r-actions { white-space: nowrap; }
      .r-actions .mini {
        margin: 0 4px 0 0; border: 1px solid var(--accent); background: var(--accent); color: var(--brand-ink);
        border-radius: 6px; padding: 4px 10px; font-size: 12px; font-weight: 600; cursor: pointer;
      }
      .r-actions .mini.ghost { background: transparent; color: var(--muted); border-color: var(--line); }
      .r-actions .mini.ghost:hover { background: var(--hover); color: var(--ink); }
      .r-actions .mini.ghost.danger:hover { color: var(--danger); border-color: var(--danger-line); }
      /* Snooze options revealed inline (in normal flow) so the table's overflow never clips them. */
      .snooze-inline { display: inline-flex; align-items: center; gap: 4px; }
      .snooze-label { font-size: 12px; color: var(--muted); margin-right: 2px; }
      .error { color: var(--danger); }

      /* Edit dialog. */
      .scrim { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.4); z-index: 1100; }
      .modal {
        position: fixed; z-index: 1101; top: 50%; left: 50%; transform: translate(-50%, -50%);
        width: min(560px, 94vw); background: var(--card); border: 1px solid var(--line);
        border-radius: 12px; padding: 1.25rem 1.4rem; box-shadow: 0 20px 60px var(--shadow);
      }
      .modal h3 { margin: 0 0 0.9rem; }
      .modal label { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: var(--muted); margin-bottom: 12px; }
      .modal-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 16px; }
      .modal-actions { display: flex; gap: 10px; justify-content: flex-end; margin-top: 1rem; }
      .modal-actions button { margin: 0; }
      @media (max-width: 480px) { .modal-grid { grid-template-columns: 1fr; } }
    `,
  ],
})
export class Reminders {
  private api = inject(ApiService);
  private spaceCtx = inject(SpaceContext);
  private notices = inject(NoticeService);

  reminders = signal<ReminderResponse[]>([]);
  documents = signal<DocumentResponse[]>([]);
  loading = signal(true);
  saving = signal(false);
  error = signal<string | null>(null);
  editing = signal<string | null>(null); // id being edited (drives the dialog)
  snoozeOpen = signal<string | null>(null);
  tab = signal<TabKey>('due');

  form: ReminderForm = this.blankForm();
  editForm: ReminderForm = this.blankForm();

  private readonly today = new Date().toISOString().slice(0, 10);

  protected helpUser =
    'Reminders nudge you before something matters: a bill due, a policy renewing, a warranty running out. Add one ' +
    'yourself, or Trove creates a due reminder for you when you confirm a document that has a due date. Set Repeat ' +
    'to have it come back on its own (say monthly rent). When you have handled one, press Done - if it repeats, the ' +
    'next one is scheduled automatically. Snooze pushes it out a little, Dismiss clears it for good, and Reopen ' +
    'brings a done or dismissed one back.';
  protected helpDev =
    'Reminders are space-scoped rows (reminder table). Status is pending -> sent (an hourly scheduler dispatches ' +
    'those whose date has arrived and emails the space) -> done or dismissed. "Due now" means active (pending or ' +
    'sent) and dated on or before today. Recurrence (weekly/monthly/quarterly/yearly) rolls forward only when a ' +
    'reminder is marked Done, so the series advances exactly once and never double-schedules. Snooze re-dates it ' +
    'from today and returns it to pending; Reopen is snooze with zero days.';

  protected tabs: { key: TabKey; label: string }[] = [
    { key: 'due', label: 'Due now' },
    { key: 'upcoming', label: 'Upcoming' },
    { key: 'done', label: 'Done' },
    { key: 'dismissed', label: 'Dismissed' },
  ];

  protected typeOptions: SelectOption[] = [
    { value: 'due', label: 'Due' },
    { value: 'renewal', label: 'Renewal' },
    { value: 'warranty_expiry', label: 'Warranty expiry' },
  ];
  protected recurrenceOptions: SelectOption[] = [
    { value: 'none', label: 'Does not repeat' },
    { value: 'weekly', label: 'Weekly' },
    { value: 'monthly', label: 'Monthly' },
    { value: 'quarterly', label: 'Quarterly' },
    { value: 'yearly', label: 'Yearly' },
  ];
  protected docOptions = computed<SelectOption[]>(() => [
    { value: '', label: '(none)' },
    ...this.documents().map((d) => ({ value: d.id, label: d.originalFilename || d.id, sub: d.merchant || undefined })),
  ]);

  // ── lifecycle predicates ───────────────────────────────────────────────────
  /** Active = still open (waiting or notified but not yet handled/cleared). */
  isActive(r: ReminderResponse): boolean {
    return r.status === 'pending' || r.status === 'sent';
  }
  /** Due now = active and dated on or before today (needs action). */
  isDue(r: ReminderResponse): boolean {
    return this.isActive(r) && r.remindOn <= this.today;
  }
  private inTab(r: ReminderResponse, key: TabKey): boolean {
    switch (key) {
      case 'due': return this.isDue(r);
      case 'upcoming': return this.isActive(r) && r.remindOn > this.today;
      case 'done': return r.status === 'done';
      case 'dismissed': return r.status === 'dismissed';
    }
  }

  dueCount = computed(() => this.reminders().filter((r) => this.isDue(r)).length);
  countFor(key: TabKey): number {
    return this.reminders().filter((r) => this.inTab(r, key)).length;
  }
  /** Reminders for the active tab: soonest-first for open ones, most-recent-first for history. */
  shownReminders = computed(() => {
    const key = this.tab();
    const list = this.reminders().filter((r) => this.inTab(r, key));
    const dir = key === 'done' || key === 'dismissed' ? -1 : 1;
    return list.sort((a, b) => dir * a.remindOn.localeCompare(b.remindOn));
  });
  emptyMsg(): string {
    switch (this.tab()) {
      case 'due': return 'Nothing needs attention right now.';
      case 'upcoming': return 'No upcoming reminders. Add one above, or confirm a document with a due date.';
      case 'done': return 'Nothing marked done yet.';
      case 'dismissed': return 'Nothing dismissed.';
    }
  }

  // ── labels ─────────────────────────────────────────────────────────────────
  rType(t: string): string {
    return t === 'due' ? 'Payment due' : t === 'renewal' ? 'Renewal' : t === 'warranty_expiry' ? 'Warranty expiry' : t;
  }
  rRecur(r: string): string {
    return r.charAt(0).toUpperCase() + r.slice(1);
  }
  statusLabel(r: ReminderResponse): string {
    return r.status === 'pending' ? 'scheduled' : r.status === 'sent' ? 'notified' : r.status;
  }
  docName(id: string): string {
    const d = this.documents().find((x) => x.id === id);
    return d ? d.originalFilename || d.id : id;
  }

  constructor() {
    effect(() => {
      const sid = this.spaceCtx.currentSpaceId();
      this.reload(sid);
    });
  }

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

  private blankForm(): ReminderForm {
    return { title: '', type: 'due', documentId: '', recurrence: 'none', remindOn: '' };
  }
  private toBody(f: ReminderForm) {
    return {
      type: f.type,
      title: f.title.trim() || undefined,
      remindOn: f.remindOn,
      recurrence: f.recurrence,
      documentId: f.documentId || undefined,
    };
  }

  // ── create ─────────────────────────────────────────────────────────────────
  add(): void {
    if (!this.form.remindOn) return;
    this.error.set(null);
    this.saving.set(true);
    this.api.createReminder(this.toBody(this.form), this.spaceCtx.currentSpaceId()).subscribe({
      next: () => {
        this.notices.show({ level: 'success', code: 'REMINDER_ADDED', userMessage: 'Reminder added.' });
        this.form = this.blankForm();
        this.saving.set(false);
        this.reload(this.spaceCtx.currentSpaceId());
      },
      error: (e) => { this.saving.set(false); this.error.set(e?.error?.message ?? 'Could not save reminder'); },
    });
  }

  // ── edit (dialog) ────────────────────────────────────────────────────────
  edit(r: ReminderResponse): void {
    this.editForm = {
      title: r.title ?? '',
      type: r.type,
      documentId: r.documentId ?? '',
      recurrence: r.recurrence,
      remindOn: r.remindOn,
    };
    this.editing.set(r.id);
  }
  cancelEdit(): void {
    this.editing.set(null);
  }
  saveEdit(): void {
    const id = this.editing();
    if (!id || !this.editForm.remindOn) return;
    this.saving.set(true);
    this.api.updateReminder(id, this.toBody(this.editForm)).subscribe({
      next: () => {
        this.notices.show({ level: 'success', code: 'REMINDER_SAVED', userMessage: 'Reminder updated.' });
        this.saving.set(false);
        this.editing.set(null);
        this.reload(this.spaceCtx.currentSpaceId());
      },
      error: (e) => { this.saving.set(false); this.notices.show({ level: 'error', code: 'SAVE_FAIL', userMessage: e?.error?.message ?? 'Could not save.' }); },
    });
  }

  // ── lifecycle actions ──────────────────────────────────────────────────────
  markDone(r: ReminderResponse): void {
    this.api.doneReminder(r.id).subscribe({
      next: () => {
        const msg = r.recurrence !== 'none' ? 'Done - next one scheduled.' : 'Marked done.';
        this.notices.show({ level: 'success', code: 'REMINDER_DONE', userMessage: msg });
        this.reload(this.spaceCtx.currentSpaceId());
      },
      error: (e) => this.notices.show({ level: 'error', code: 'DONE_FAIL', userMessage: e?.error?.message ?? 'Could not update.' }),
    });
  }

  toggleSnooze(id: string): void {
    this.snoozeOpen.update((cur) => (cur === id ? null : id));
  }
  snooze(r: ReminderResponse, days: number): void {
    this.snoozeOpen.set(null);
    this.api.snoozeReminder(r.id, days).subscribe({
      next: () => {
        this.notices.show({ level: 'success', code: 'REMINDER_SNOOZED', userMessage: days === 0 ? 'Reminder reopened.' : `Snoozed ${days} day${days > 1 ? 's' : ''}.` });
        this.reload(this.spaceCtx.currentSpaceId());
      },
      error: (e) => this.notices.show({ level: 'error', code: 'SNOOZE_FAIL', userMessage: e?.error?.message ?? 'Could not snooze.' }),
    });
  }
  dismiss(r: ReminderResponse): void {
    this.api.dismissReminder(r.id).subscribe({
      next: () => {
        this.notices.show({ level: 'info', code: 'REMINDER_DISMISSED', userMessage: 'Dismissed. Find it under Dismissed to reopen.' });
        this.reload(this.spaceCtx.currentSpaceId());
      },
      error: (e) => this.notices.show({ level: 'error', code: 'DISMISS_FAIL', userMessage: e?.error?.message ?? 'Could not dismiss.' }),
    });
  }
}
