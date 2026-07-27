import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { SpaceContext } from '../../core/services/space.context';
import { NoticeService } from '../../core/services/notice.service';
import { DocumentResponse, ReminderResponse } from '../../core/models/models';
import { TroveSelect, SelectOption } from '../../shared/components/select';
import { HelpCard } from '../../shared/components/help-card';

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
  templateUrl: './reminders.html',
  styleUrl: './reminders.scss',
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
    'yourself, or let Trove set one up when you confirm a document: a due date becomes a reminder, an insurance or ' +
    'subscription date becomes a renewal, and a purchase with a warranty gets a heads-up before cover ends. If the ' +
    'same merchant keeps billing on a regular rhythm (say monthly), Trove even spots the pattern and schedules a ' +
    'repeating renewal on its own - you do not set that up, and if it is not one you care to track, just Dismiss it. ' +
    'Set Repeat to have any reminder come back on its own (say monthly rent). When you ' +
    'have handled one, press Done - if it repeats, the next one is scheduled automatically. Snooze pushes it out a ' +
    'little, Dismiss clears it for good, and Reopen brings a done or dismissed one back.';
  protected helpDev =
    'Reminders are space-scoped rows (reminder table). Status is pending -> sent (an hourly scheduler dispatches ' +
    'those whose date has arrived and emails the space) -> done or dismissed. "Due now" means active (pending or ' +
    'sent) and dated on or before today. Recurrence (weekly/monthly/quarterly/yearly) rolls forward only when a ' +
    'reminder is marked Done, so the series advances exactly once and never double-schedules. Snooze re-dates it ' +
    'from today and returns it to pending; Reopen is snooze with zero days. On confirm, auto-creation is ' +
    'type-aware (insurance/subscription categories become renewals, everything else a due), and a merchant with a ' +
    'steady monthly/quarterly/yearly cadence across 3+ documents seeds a recurring renewal for the next date.';

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
  constructor() {
    effect(() => {
      const sid = this.spaceCtx.currentSpaceId();
      this.docsLoaded = false; // a new space needs its own document list for the picker
      this.documents.set([]);
      this.reload(sid);
    });
  }

  private reload(spaceId?: string): void {
    this.loading.set(true);
    // Only the reminders - each already carries its linked file name (documentFilename), so
    // the page no longer pulls the whole document list just to label rows. The document list
    // is loaded lazily (ensureDocsLoaded) only when the "For document" picker is opened.
    this.api.listReminders(spaceId).subscribe({
      next: (reminders) => {
        this.reminders.set(reminders);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  /** Loads the space's documents once, on demand, to populate the "For document" picker -
   *  so merely viewing reminders costs no document fetch. */
  private docsLoaded = false;
  ensureDocsLoaded(): void {
    if (this.docsLoaded) return;
    this.docsLoaded = true;
    this.api.listDocuments(this.spaceCtx.currentSpaceId()).subscribe({
      next: (docs) => this.documents.set(docs),
      error: () => { this.docsLoaded = false; }, // let a later open retry
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
    this.ensureDocsLoaded(); // so the picker can show the currently linked document by name
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
