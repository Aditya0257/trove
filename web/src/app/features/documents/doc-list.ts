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
  template: `
    <div class="card">
      <div class="row-between">
        <h1>{{ showTrash() ? 'Trash' : 'Documents' }}</h1>
        <div class="head-actions">
          <button type="button" class="btn-ghost" (click)="toggleTrash()">
            {{ showTrash() ? '← Documents' : '🗑 Trash' }}
          </button>
          @if (!showTrash()) { <a routerLink="/upload" class="button">＋ Upload</a> }
        </div>
      </div>

      @if (showTrash()) {
        <trove-help-card title="How deleting & restoring works" [open]="false"
          [user]="trashHelpUser" [dev]="trashHelpDev"></trove-help-card>
        <p class="muted">Deleted documents stay here for 30 days, then they're permanently removed from
          storage. Restore anything before then.</p>
        @if (trashLoading()) { <p class="muted">Loading…</p> }
        @else if (trash().length === 0) { <p class="muted">Trash is empty.</p> }
        @else {
          <div class="list-help">
            <span>Restore
              <trove-info-tip text="Puts the file back in your live vault and out of the Trove/_Deleted folder in Drive - it reappears in Documents."></trove-info-tip>
            </span>
            <span>Delete forever
              <trove-info-tip text="Erases the file now from the live store (R2) and from Google Drive, and removes the database row - permanently, no undo. The independent B2 mirror keeps an archival copy by design."></trove-info-tip>
            </span>
          </div>
          <div class="bulk-bar">
            <button type="button" class="btn-ghost sm" (click)="restoreSelected()"
              [disabled]="selected().size === 0 || bulkBusy()">Restore selected ({{ selected().size }})</button>
            <button type="button" class="del sm" (click)="purgeSelected()"
              [disabled]="selected().size === 0 || bulkBusy()">Delete selected ({{ selected().size }})</button>
            <span class="spacer"></span>
            <button type="button" class="del sm" (click)="purgeAll()" [disabled]="bulkBusy()">Delete all</button>
          </div>
          <div class="table-scroll">
          <table>
            <thead>
              <tr>
                <th class="chk"><input type="checkbox" [checked]="allSelected()" (change)="toggleSelectAll()"
                  [attr.aria-label]="allSelected() ? 'Deselect all' : 'Select all'" /></th>
                <th>File</th><th>Category</th><th>Merchant</th><th>Amount</th><th>Deleted</th><th></th>
              </tr>
            </thead>
            <tbody>
              @for (d of trash(); track d.id) {
                <tr [class.sel]="selected().has(d.id)">
                  <td class="chk"><input type="checkbox" [checked]="selected().has(d.id)"
                    (change)="toggleSelect(d.id)" [attr.aria-label]="'Select ' + (d.originalFilename || d.id)" /></td>
                  <td>{{ d.originalFilename || d.id }}</td>
                  <td>{{ d.category || '-' }}</td>
                  <td>{{ d.merchant || '-' }}</td>
                  <td>{{ d.amount | money: d.currency }}</td>
                  <td>{{ d.deletedAt ? (d.deletedAt | prettyDate) : '-' }}</td>
                  <td class="row-actions">
                    <button type="button" class="btn-ghost sm" (click)="restore(d)" [disabled]="restoringId() === d.id">
                      {{ restoringId() === d.id ? 'Restoring...' : 'Restore' }}
                    </button>
                    <button type="button" class="del" (click)="purge(d)">Delete forever</button>
                  </td>
                </tr>
              }
            </tbody>
          </table>
          </div>
          <p class="muted total">{{ trash().length }} in trash</p>
        }
      } @else {

      @if (adminPending().length) {
        <a routerLink="/admin" class="admin-strip">
          <span class="rs-icon">👤</span>
          <span class="rs-text">
            <b>{{ adminPending().length }}</b> account{{ adminPending().length > 1 ? 's are' : ' is' }}
            waiting for your approval to join Trove.
          </span>
          <span class="rs-cta">Review →</span>
        </a>
      }

      @if (upcomingReminders().length) {
        <a routerLink="/reminders" class="reminder-strip" [class.overdue]="hasOverdue()">
          <span class="rs-icon">🔔</span>
          <span class="rs-text">
            <b>{{ upcomingReminders().length }}</b> reminder{{ upcomingReminders().length > 1 ? 's' : '' }} to act on -
            {{ reminderSummary() }}
          </span>
          <span class="rs-cta">View all →</span>
        </a>
      }

      <div class="cats">
        <button type="button" class="chip" [class.on]="category === ''" (click)="setCategory('')">All</button>
        @for (c of visibleCategories(); track c.code) {
          <button type="button" class="chip" [class.on]="category === c.code" (click)="setCategory(c.code)">
            {{ c.label }}
          </button>
        }
      </div>

      @if (loading()) { <p class="muted">Loading…</p> }
      @else if (total() === 0) {
        <p class="muted">
          No documents{{ category ? ' in this category' : '' }} yet.
          <a routerLink="/upload">Snap or paste your first</a>.
        </p>
      }
      @else {
        <div class="list-help">
          <span>Status
            <trove-info-tip text="needs_review = the AI read this and it's waiting for you to confirm the details. confirmed = you've verified them (only confirmed documents count toward Spend)."></trove-info-tip>
          </span>
          <span>Delete
            <trove-info-tip text="Moves a document to Trash - recoverable for 30 days, not a permanent erase. It's hidden from lists, Spend and Search, and its Drive copy moves to Trove/_Deleted. Open Trash to restore or delete for good."></trove-info-tip>
          </span>
        </div>
        <div class="table-scroll">
        <table>
          <thead>
            <tr><th>File</th><th>Category</th><th>Merchant</th><th>Amount</th><th>Date</th><th>Status</th><th></th></tr>
          </thead>
          <tbody>
            @for (d of docs(); track d.id) {
              <tr>
                <td><a [routerLink]="['/documents', d.id, 'review']">{{ d.originalFilename || d.id }}</a></td>
                <td>{{ d.category || '-' }}</td>
                <td>{{ d.merchant || '-' }}</td>
                <td>
                  {{ d.amount | money: d.currency }}
                  @if (isAnomalous(d)) { <span class="anom" title="Higher than usual for this category - worth a second look">↑ {{ anomalyPct(d) }}</span> }
                </td>
                <td>{{ d.docDate || '-' }}</td>
                <td><span class="badge" [class.confirmed]="d.status === 'confirmed'">{{ d.status }}</span></td>
                <td><button class="del" type="button" (click)="remove(d)">Delete</button></td>
              </tr>
            }
          </tbody>
        </table>
        </div>

        <div class="pager">
          <trove-select class="page-size" [ngModel]="pageSizeStr()" (ngModelChange)="setPageSize($event)"
            [options]="pageSizeOptions" ariaLabel="Page size"></trove-select>
          @if (pageSize() !== 0 && totalPages() > 1) {
            <div class="pages">
              <button type="button" [disabled]="page() === 0" (click)="goToPage(page() - 1)">‹ Prev</button>
              <span>Page {{ page() + 1 }} of {{ totalPages() }}</span>
              <button type="button" [disabled]="page() >= totalPages() - 1" (click)="goToPage(page() + 1)">Next ›</button>
            </div>
          }
          <span class="muted total">{{ total() }} document(s)</span>
        </div>
      }
      }
    </div>
  `,
  styles: [
    `
      /* Column-level help legend, above the table so its tooltips never clip in the scroll box. */
      .list-help { display: flex; gap: 18px; margin: 4px 0 10px; font-size: 12px; color: var(--muted); }
      .list-help > span { display: inline-flex; align-items: center; gap: 5px; }
      /* Let a wide table scroll inside the card instead of the page scrolling sideways. */
      .table-scroll { overflow-x: auto; max-width: 100%; }
      .table-scroll table { min-width: 560px; }
      /* A calm nudge above the list: reminders that need attention, linking to the page. */
      .reminder-strip {
        display: flex; align-items: center; gap: 10px; margin: 4px 0 14px; padding: 10px 14px;
        border: 1px solid var(--accent-line); background: var(--accent-soft); border-radius: 10px;
        text-decoration: none; color: var(--ink); font-size: 13px;
      }
      .reminder-strip:hover { filter: brightness(1.02); border-color: var(--accent); }
      .reminder-strip.overdue { border-color: var(--danger-line); background: var(--danger-soft); }
      .rs-icon { font-size: 15px; flex: none; }
      .rs-text { flex: 1; line-height: 1.4; }
      .rs-cta { flex: none; color: var(--accent); font-weight: 600; white-space: nowrap; }
      .reminder-strip.overdue .rs-cta { color: var(--danger); }
      /* Admin-only nudge: sign-ups awaiting approval. Uses the indigo secondary hue so it
         reads as informational and stays distinct from the teal reminders strip and the red
         overdue state. Tokens recolour with the theme, so it is correct in light and dark. */
      .admin-strip {
        display: flex; align-items: center; gap: 10px; margin: 4px 0 14px; padding: 10px 14px;
        border: 1px solid var(--accent-2-line); background: var(--accent-2-soft); border-radius: 10px;
        text-decoration: none; color: var(--ink); font-size: 13px;
      }
      .admin-strip:hover { filter: brightness(1.02); border-color: var(--accent-2); }
      .admin-strip .rs-cta { color: var(--accent-2); }
      .cats { display: flex; flex-wrap: wrap; gap: 8px; margin: 10px 0 14px; }
      .chip {
        border: 1px solid var(--accent-line); background: transparent; color: var(--accent);
        border-radius: 999px; padding: 5px 12px; font-size: 13px; cursor: pointer;
      }
      .chip.on { background: var(--accent); color: var(--brand-ink); border-color: var(--accent); }
      td { vertical-align: middle; }
      /* Subtle "higher than usual" flag next to an anomalous amount. */
      .anom {
        margin-left: 6px; font-size: 11px; font-weight: 700; white-space: nowrap;
        color: var(--warn, #b8860b); cursor: help;
      }
      .del {
        margin: 0; border: 1px solid var(--danger-line); background: transparent; color: var(--danger);
        border-radius: 6px; padding: 4px 12px; font-size: 12px; cursor: pointer; white-space: nowrap;
      }
      .del:hover { background: var(--danger-soft); }
      .del.sm { padding: 4px 12px; }
      /* Trash bulk-action bar + selection column. */
      .bulk-bar { display: flex; align-items: center; gap: 10px; margin: 4px 0 10px; flex-wrap: wrap; }
      .bulk-bar .spacer { flex: 1 1 auto; }
      .bulk-bar button:disabled { opacity: 0.5; cursor: default; }
      th.chk, td.chk { width: 1%; text-align: center; padding-right: 4px; }
      th.chk input, td.chk input { cursor: pointer; }
      tr.sel td { background: var(--accent-soft); }
      /* Trash + Upload are a matched pair on one line - neutralise the global button's
         top margin and give the ghost the same box so they align and read as siblings. */
      .head-actions { display: flex; align-items: center; gap: 10px; }
      .head-actions > * { margin: 0; }
      .btn-ghost {
        margin: 0; border: 1px solid var(--line); background: transparent; color: var(--muted);
        border-radius: 8px; padding: 0.6rem 1.1rem; font-size: 0.95rem; font-weight: 600;
        cursor: pointer; line-height: 1.2;
      }
      .btn-ghost:hover { background: var(--hover); color: var(--accent); }
      .btn-ghost.sm { padding: 4px 12px; font-size: 12px; line-height: 1.4; }
      /* Keep this a normal table cell (NOT display:flex, which drops the cell out of the
         row and leaves a detached white block on selection). Buttons lay out inline. */
      .row-actions { white-space: nowrap; text-align: right; }
      .row-actions button + button { margin-left: 8px; }
      .pager { display: flex; align-items: center; gap: 14px; margin-top: 14px; flex-wrap: wrap; }
      .page-size { display: inline-block; width: 200px; }
      .pages { display: flex; align-items: center; gap: 10px; }
      /* Override the global brand button (light text on brand fill): these are neutral
         nav buttons on a white row, so give them a dark label + no stray top margin. */
      .pages button {
        margin: 0; border: 1px solid var(--line); background: var(--card); color: var(--accent);
        border-radius: 8px; padding: 5px 12px; cursor: pointer; font-size: 13px; font-weight: 600;
      }
      .pages button:hover:not(:disabled) { background: var(--accent-soft); }
      .pages button:disabled { opacity: 0.4; cursor: default; }
      .pager .total { margin-left: auto; font-size: 13px; }
    `,
  ],
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
