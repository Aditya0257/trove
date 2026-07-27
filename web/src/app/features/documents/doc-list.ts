import { Component, computed, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { forkJoin, of, catchError } from 'rxjs';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { SpaceContext } from '../../core/services/space.context';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { DateTimePipe } from '../../shared/pipes/datetime.pipe';
import { NoticeService } from '../../core/services/notice.service';
import { ConfirmService } from '../../core/services/confirm.service';
import { HelpCard } from '../../shared/components/help-card';
import { InfoTip } from '../../shared/components/info-tip';
import { Category, DocumentResponse, PendingUser, ReminderResponse } from '../../core/models/models';
import { TroveSelect, SelectOption } from '../../shared/components/select';
import { AuthService } from '../../core/services/auth.service';

@Component({
  selector: 'app-doc-list',
  imports: [RouterLink, MoneyPipe, DateTimePipe, FormsModule, TroveSelect, HelpCard, InfoTip],
  templateUrl: './doc-list.html',
  styleUrl: './doc-list.scss',
})
export class DocList {
  private api = inject(ApiService);
  private spaceCtx = inject(SpaceContext);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private auth = inject(AuthService);

  categories = signal<Category[]>([]);
  docs = signal<DocumentResponse[]>([]); // the current page of documents (server-side slice)
  total = signal(0);                     // total matches across all pages (from X-Total-Count)
  category = '';
  loading = signal(false);

  /** Sign-ups awaiting approval - populated only for the admin, drives the blue nudge strip. */
  adminPending = signal<PendingUser[]>([]);

  // ── Upcoming reminders nudge ───────────────────────────────────────────────
  reminders = signal<ReminderResponse[]>([]);
  private readonly today = new Date().toISOString().slice(0, 10);
  /** 30 days out, ISO date - the horizon for "coming up". */
  private readonly horizon = new Date(Date.now() + 30 * 86_400_000).toISOString().slice(0, 10);

  /** Reminders that still need attention: still open (pending or notified-but-not-handled) and
   *  due within the next 30 days (or overdue), soonest first. Done/dismissed ones are left out. */
  upcomingReminders = computed(() =>
    this.reminders()
      .filter((r) => (r.status === 'pending' || r.status === 'sent') && r.remindOn <= this.horizon)
      .sort((a, b) => a.remindOn.localeCompare(b.remindOn)),
  );
  /** True if any upcoming reminder is already on/before today - shown in red. */
  hasOverdue = computed(() => this.upcomingReminders().some((r) => r.remindOn <= this.today));

  /** The nearest few reminders as one line: "Payment due 2026-08-01, Renewal 2026-08-15, +2 more". */
  reminderSummary = computed(() => {
    const up = this.upcomingReminders();
    const parts = up.slice(0, 3).map((r) => `${this.rType(r.type)} ${r.remindOn}`);
    return parts.join(', ') + (up.length > 3 ? `, +${up.length - 3} more` : '');
  });

  /** True when this document was flagged higher-than-usual for its category at confirm. */
  isAnomalous(d: DocumentResponse): boolean {
    return !!(d.extra?.['anomaly'] as { anomaly?: boolean } | undefined)?.anomaly;
  }
  /** The overshoot as a rounded percentage for the list flag, e.g. "42%". */
  anomalyPct(d: DocumentResponse): string {
    const p = (d.extra?.['anomaly'] as { deltaPct?: number } | undefined)?.deltaPct;
    return p != null ? `${Math.round(p * 100)}%` : '';
  }

  /** Reader-friendly reminder type label. */
  protected rType(t: string): string {
    return t === 'due' ? 'Payment due'
      : t === 'renewal' ? 'Renewal'
      : t === 'warranty_expiry' ? 'Warranty expiry'
      : t;
  }

  protected trashHelpUser =
    'Deleting never erases anything straight away. A deleted document moves to Trash and is hidden from your ' +
    'lists, spend and search - but it stays fully recoverable for 30 days: press Restore and it comes right ' +
    'back. After 30 days it is removed for good. "Delete forever" skips the wait and removes it now. To act on ' +
    'several at once, tick the rows and use Restore selected or Delete selected, or Delete all to empty the ' +
    'Trash. Your other backup copies mean an accidental delete is never the end of the world.';
  protected trashHelpDev =
    'Soft delete: the document row is tombstoned and its file MOVED (not erased) to a _trash/ prefix in R2, with ' +
    'the Drive copy moved to Trove/_Deleted/ via a lifecycle event. The row then drops out of every query (lists, ' +
    'Spend, Search, dedupe). Example - on delete the row changes from ' +
    '{ "status":"confirmed", "storageKey":"electricity/2026-01/reliance-a1b2.jpg", "trashKey":null, ' +
    '"deletedAt":null, "deletedBy":null } to { "status":"deleted", ' +
    '"storageKey":"electricity/2026-01/reliance-a1b2.jpg", "trashKey":"_trash/<space>/<doc>/reliance-a1b2.jpg", ' +
    '"deletedAt":"2026-07-24T08:15:00Z", "deletedBy":"<userId>" } and the R2 object is copied from storageKey to ' +
    'trashKey then deleted at the old key. Restore reverses it exactly: status back to confirmed, trashKey to null, ' +
    'deletedAt/deletedBy cleared, object moved back to storageKey. Purge (the daily 30-day job, or "Delete forever") ' +
    'deletes the trashed object from R2, deletes it from Drive, and removes the DB row (line items + drive-sync ' +
    'cascade). The independent B2 mirror is append-only and keeps an archival copy by design, so "cleared ' +
    'everywhere" means the live R2 + Drive + DB.';

  /** Emails have their own home in the Mail section, so they're kept out of Documents
   *  entirely - no "Email" filter chip (and the server leaves them out of the list). */
  visibleCategories = computed(() => this.categories().filter((c) => c.code !== 'email'));

  /** Page size (0 = show All, so browser find works on the full list). Paging is server-side:
   *  the list holds one page and `total` is the full match count from the response header. */
  pageSize = signal(10); // fetch a small page by default; the user can raise it
  page = signal(0);
  protected pageSizeStr = computed(() => String(this.pageSize()));
  protected pageSizeOptions: SelectOption[] = [
    { value: '10', label: '10 per page' },
    { value: '25', label: '25 per page' },
    { value: '50', label: '50 per page' },
    { value: '100', label: '100 per page' },
    { value: '0', label: 'All (for Ctrl/⌘+F)' },
  ];

  totalPages = computed(() => {
    const size = this.pageSize();
    return size === 0 ? 1 : Math.max(1, Math.ceil(this.total() / size));
  });

  private notices = inject(NoticeService);
  private confirm = inject(ConfirmService);

  setPageSize(value: string): void {
    this.pageSize.set(Number(value));
    this.page.set(0);
    this.load();
  }

  setCategory(code: string): void {
    this.category = code;
    this.page.set(0);
    this.load();
  }

  /** Move to a page (clamped) and fetch it from the server. */
  goToPage(p: number): void {
    const last = this.totalPages() - 1;
    this.page.set(Math.min(Math.max(0, p), Math.max(0, last)));
    this.load();
  }

  remove(d: DocumentResponse): void {
    const name = d.merchant || d.originalFilename || 'this document';
    this.confirm.ask({
      title: 'Move to Trash?',
      message: `"${name}" stays recoverable for 30 days before it's permanently removed.`,
      confirmLabel: 'Move to Trash', busyLabel: 'Moving...', danger: true,
    }).then((ok) => {
      if (!ok) return;
      this.api.deleteDocument(d.id).subscribe({
        next: () => {
          this.confirm.close();
          this.notices.show({ level: 'success', code: 'DELETED', userMessage: 'Moved to Trash - recoverable for 30 days.' });
          this.load(); // refetch the page so the total and pager stay correct
        },
        error: (e) => { this.confirm.close(); this.notices.show({ level: 'error', code: 'DELETE_FAIL', userMessage: e?.error?.message ?? 'Could not delete.' }); },
      });
    });
  }

  // ── Trash ────────────────────────────────────────────────────────────────
  showTrash = signal(false);
  trash = signal<DocumentResponse[]>([]);
  trashLoading = signal(false);
  restoringId = signal<string | null>(null);

  /** Trash is a URL state (?view=trash) so the top-nav "Documents" link resets it too,
   *  not just the in-page back button. */
  toggleTrash(): void {
    const goingToTrash = !this.showTrash();
    this.router.navigate([], { queryParams: { view: goingToTrash ? 'trash' : null }, queryParamsHandling: 'merge' });
  }

  // ── Trash multi-select ─────────────────────────────────────────────────────
  selected = signal<Set<string>>(new Set());
  bulkBusy = signal(false);
  allSelected = computed(() => this.trash().length > 0 && this.selected().size === this.trash().length);

  toggleSelect(id: string): void {
    this.selected.update((s) => {
      const next = new Set(s);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  }
  toggleSelectAll(): void {
    this.selected.set(this.allSelected() ? new Set() : new Set(this.trash().map((d) => d.id)));
  }

  restoreSelected(): void {
    const ids = [...this.selected()];
    if (!ids.length || this.bulkBusy()) return;
    this.bulkBusy.set(true);
    forkJoin(ids.map((id) => this.api.restoreDocument(id).pipe(catchError(() => of(null))))).subscribe(() => {
      this.bulkBusy.set(false);
      this.selected.set(new Set());
      this.loadTrash();
      this.notices.show({ level: 'success', code: 'RESTORED_MANY', userMessage: `Restored ${ids.length} document${ids.length > 1 ? 's' : ''}.` });
    });
  }
  purgeSelected(): void {
    const ids = [...this.selected()];
    if (!ids.length || this.bulkBusy()) return;
    this.confirm.ask({
      title: `Delete ${ids.length} forever?`,
      message: `${ids.length} document${ids.length > 1 ? 's' : ''} will be cleared from live storage. This can't be undone.`,
      confirmLabel: 'Delete forever', busyLabel: 'Deleting...', danger: true,
    }).then((ok) => { if (ok) this.bulkPurge(ids); });
  }
  purgeAll(): void {
    const ids = this.trash().map((d) => d.id);
    if (!ids.length || this.bulkBusy()) return;
    this.confirm.ask({
      title: 'Empty Trash?',
      message: `All ${ids.length} document${ids.length > 1 ? 's' : ''} in Trash will be permanently deleted. This can't be undone.`,
      confirmLabel: 'Delete all', busyLabel: 'Deleting...', danger: true,
    }).then((ok) => { if (ok) this.bulkPurge(ids); });
  }
  private bulkPurge(ids: string[]): void {
    this.bulkBusy.set(true);
    forkJoin(ids.map((id) => this.api.purgeDocument(id).pipe(catchError(() => of(null))))).subscribe(() => {
      this.confirm.close();
      this.bulkBusy.set(false);
      this.selected.set(new Set());
      this.loadTrash();
      this.notices.show({ level: 'success', code: 'PURGED_MANY', userMessage: `Deleted ${ids.length} document${ids.length > 1 ? 's' : ''}.` });
    });
  }

  loadTrash(): void {
    this.trashLoading.set(true);
    this.selected.set(new Set());
    this.api.listTrash(this.spaceCtx.currentSpaceId()).subscribe({
      next: (d) => { this.trash.set(d); this.trashLoading.set(false); },
      error: () => this.trashLoading.set(false),
    });
  }

  restore(d: DocumentResponse): void {
    this.restoringId.set(d.id);
    this.api.restoreDocument(d.id).subscribe({
      next: () => {
        this.restoringId.set(null);
        this.trash.update((list) => list.filter((x) => x.id !== d.id));
        this.notices.show({ level: 'success', code: 'RESTORED', userMessage: 'Document restored.' });
      },
      error: (e) => { this.restoringId.set(null); this.notices.show({ level: 'error', code: 'RESTORE_FAIL', userMessage: e?.error?.message ?? 'Could not restore.' }); },
    });
  }

  purge(d: DocumentResponse): void {
    const name = d.merchant || d.originalFilename || 'this document';
    this.confirm.ask({
      title: 'Delete forever?',
      message: `"${name}" will be cleared from live storage. This can't be undone.`,
      confirmLabel: 'Delete forever', busyLabel: 'Deleting...', danger: true,
    }).then((ok) => {
      if (!ok) return;
      this.purgeConfirmed(d);
    });
  }

  private purgeConfirmed(d: DocumentResponse): void {
    this.api.purgeDocument(d.id).subscribe({
      next: () => {
        this.confirm.close();
        this.trash.update((list) => list.filter((x) => x.id !== d.id));
        this.notices.show({ level: 'success', code: 'PURGED', userMessage: 'Permanently deleted.' });
      },
      error: (e) => { this.confirm.close(); this.notices.show({ level: 'error', code: 'PURGE_FAIL', userMessage: e?.error?.message ?? 'Could not delete.' }); },
    });
  }

  constructor() {
    // The admin sees a nudge on this default page when sign-ups are waiting, mirroring the
    // reminders strip. A non-admin's call would 403, so it's gated and failures stay silent.
    if (this.auth.user()?.admin) {
      this.auth.adminPending().subscribe({
        next: (u) => this.adminPending.set(u),
        error: () => this.adminPending.set([]),
      });
    }
    // Reload documents (and categories) whenever the selected space changes.
    effect(() => {
      const sid = this.spaceCtx.currentSpaceId();
      this.api.listCategories(sid).subscribe((c) => this.categories.set(c));
      this.api.listReminders(sid).subscribe({ next: (r) => this.reminders.set(r), error: () => this.reminders.set([]) });
      this.load();
    });
    // Trash view is driven by ?view=trash, so the top-nav "Documents" link (which clears
    // the query) switches back to the list, not just the in-page button.
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((q) => {
      const trash = q.get('view') === 'trash';
      this.showTrash.set(trash);
      if (trash) this.loadTrash();
    });
  }

  /** Tracks the space the list was last loaded for, so switching spaces resets to page 1. */
  private lastSpaceId: string | undefined;

  load(): void {
    const sid = this.spaceCtx.currentSpaceId();
    if (sid !== this.lastSpaceId) {
      this.lastSpaceId = sid;
      this.page.set(0);
    }
    this.loading.set(true);
    this.api.listDocumentsPage(sid, this.category || undefined, this.page(), this.pageSize()).subscribe({
      next: (r) => {
        // Deleting the last row on the last page can leave us past the end; step back once.
        if (r.items.length === 0 && r.total > 0 && this.page() > 0) {
          this.page.set(this.page() - 1);
          this.load();
          return;
        }
        this.docs.set(r.items);
        this.total.set(r.total);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }
}
