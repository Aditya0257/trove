import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { SpaceContext } from '../../core/space.context';
import { MoneyPipe } from '../../core/money.pipe';
import { DateTimePipe } from '../../core/datetime.pipe';
import { NoticeService } from '../../core/notice/notice.service';
import { ConfirmService } from '../../core/confirm.service';
import { Category, DocumentResponse } from '../../core/models';
import { TroveSelect, SelectOption } from '../../core/select';

@Component({
  selector: 'app-doc-list',
  imports: [RouterLink, MoneyPipe, DateTimePipe, FormsModule, TroveSelect],
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
        <p class="muted">Deleted documents stay here for 30 days, then they're permanently removed from
          storage. Restore anything before then.</p>
        @if (trashLoading()) { <p class="muted">Loading…</p> }
        @else if (trash().length === 0) { <p class="muted">Trash is empty.</p> }
        @else {
          <div class="table-scroll">
          <table>
            <thead>
              <tr><th>File</th><th>Category</th><th>Merchant</th><th>Amount</th><th>Deleted</th><th></th></tr>
            </thead>
            <tbody>
              @for (d of trash(); track d.id) {
                <tr>
                  <td>{{ d.originalFilename || d.id }}</td>
                  <td>{{ d.category || '-' }}</td>
                  <td>{{ d.merchant || '-' }}</td>
                  <td>{{ d.amount | money: d.currency }}</td>
                  <td>{{ d.deletedAt ? (d.deletedAt | prettyDate) : '-' }}</td>
                  <td class="row-actions">
                    <button type="button" class="btn-ghost sm" (click)="restore(d)">Restore</button>
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

      <div class="cats">
        <button type="button" class="chip" [class.on]="category === ''" (click)="setCategory('')">All</button>
        @for (c of visibleCategories(); track c.code) {
          <button type="button" class="chip" [class.on]="category === c.code" (click)="setCategory(c.code)">
            {{ c.label }}
          </button>
        }
      </div>

      @if (loading()) { <p class="muted">Loading…</p> }
      @else if (visibleDocs().length === 0) {
        <p class="muted">
          No documents{{ category ? ' in this category' : '' }} yet.
          <a routerLink="/upload">Snap or paste your first</a>.
        </p>
      }
      @else {
        <div class="table-scroll">
        <table>
          <thead>
            <tr><th>File</th><th>Category</th><th>Merchant</th><th>Amount</th><th>Date</th><th>Status</th><th></th></tr>
          </thead>
          <tbody>
            @for (d of pagedDocs(); track d.id) {
              <tr>
                <td><a [routerLink]="['/documents', d.id, 'review']">{{ d.originalFilename || d.id }}</a></td>
                <td>{{ d.category || '-' }}</td>
                <td>{{ d.merchant || '-' }}</td>
                <td>{{ d.amount | money: d.currency }}</td>
                <td>{{ d.docDate || '-' }}</td>
                <td><span class="badge" [class.confirmed]="d.status === 'confirmed'">{{ d.status }}</span></td>
                <td><button class="del" type="button" title="Delete" (click)="remove(d)">Delete</button></td>
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
              <button type="button" [disabled]="page() === 0" (click)="page.set(page() - 1)">‹ Prev</button>
              <span>Page {{ page() + 1 }} of {{ totalPages() }}</span>
              <button type="button" [disabled]="page() >= totalPages() - 1" (click)="page.set(page() + 1)">Next ›</button>
            </div>
          }
          <span class="muted total">{{ visibleDocs().length }} document(s)</span>
        </div>
      }
      }
    </div>
  `,
  styles: [
    `
      /* Let a wide table scroll inside the card instead of the page scrolling sideways. */
      .table-scroll { overflow-x: auto; max-width: 100%; }
      .table-scroll table { min-width: 560px; }
      .cats { display: flex; flex-wrap: wrap; gap: 8px; margin: 10px 0 14px; }
      .chip {
        border: 1px solid var(--accent-line); background: transparent; color: var(--accent);
        border-radius: 999px; padding: 5px 12px; font-size: 13px; cursor: pointer;
      }
      .chip.on { background: var(--accent); color: var(--brand-ink); border-color: var(--accent); }
      td { vertical-align: middle; }
      .del {
        margin: 0; border: 1px solid var(--danger-line); background: transparent; color: var(--danger);
        border-radius: 6px; padding: 4px 12px; font-size: 12px; cursor: pointer; white-space: nowrap;
      }
      .del:hover { background: var(--danger-soft); }
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
      .row-actions { display: flex; gap: 8px; white-space: nowrap; }
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

  categories = signal<Category[]>([]);
  docs = signal<DocumentResponse[]>([]);
  category = '';
  loading = signal(false);

  /** Emails have their own home in the Mail section, so they're kept out of Documents
   *  entirely - no "Email" filter chip, and never listed under "All". */
  visibleCategories = computed(() => this.categories().filter((c) => c.code !== 'email'));
  visibleDocs = computed(() => this.docs().filter((d) => d.category !== 'email'));

  /** Page size (0 = show All, so browser find works on the full list). */
  pageSize = signal(25);
  page = signal(0);
  protected pageSizeStr = computed(() => String(this.pageSize()));
  protected pageSizeOptions: SelectOption[] = [
    { value: '25', label: '25 per page' },
    { value: '50', label: '50 per page' },
    { value: '100', label: '100 per page' },
    { value: '0', label: 'All (for Ctrl/⌘+F)' },
  ];

  /** The slice of documents shown on the current page. */
  pagedDocs = computed(() => {
    const size = this.pageSize();
    const all = this.visibleDocs();
    if (size === 0) return all;
    const start = this.page() * size;
    return all.slice(start, start + size);
  });

  totalPages = computed(() => {
    const size = this.pageSize();
    return size === 0 ? 1 : Math.max(1, Math.ceil(this.visibleDocs().length / size));
  });

  private notices = inject(NoticeService);
  private confirm = inject(ConfirmService);

  setPageSize(value: string): void {
    this.pageSize.set(Number(value));
    this.page.set(0);
  }

  setCategory(code: string): void {
    this.category = code;
    this.page.set(0);
    this.load();
  }

  remove(d: DocumentResponse): void {
    const name = d.merchant || d.originalFilename || 'this document';
    this.confirm.ask({
      title: 'Move to Trash?',
      message: `"${name}" stays recoverable for 30 days before it's permanently removed.`,
      confirmLabel: 'Move to Trash',
    }).then((ok) => {
      if (!ok) return;
      this.api.deleteDocument(d.id).subscribe({
        next: () => {
          this.docs.update((list) => list.filter((x) => x.id !== d.id));
          this.notices.show({ level: 'success', code: 'DELETED', userMessage: 'Moved to Trash - recoverable for 30 days.' });
        },
      });
    });
  }

  // ── Trash ────────────────────────────────────────────────────────────────
  showTrash = signal(false);
  trash = signal<DocumentResponse[]>([]);
  trashLoading = signal(false);

  toggleTrash(): void {
    const next = !this.showTrash();
    this.showTrash.set(next);
    if (next) this.loadTrash();
  }

  loadTrash(): void {
    this.trashLoading.set(true);
    this.api.listTrash(this.spaceCtx.currentSpaceId()).subscribe({
      next: (d) => { this.trash.set(d); this.trashLoading.set(false); },
      error: () => this.trashLoading.set(false),
    });
  }

  restore(d: DocumentResponse): void {
    this.api.restoreDocument(d.id).subscribe({
      next: () => {
        this.trash.update((list) => list.filter((x) => x.id !== d.id));
        this.notices.show({ level: 'success', code: 'RESTORED', userMessage: 'Document restored.' });
      },
      error: (e) => this.notices.show({ level: 'error', code: 'RESTORE_FAIL', userMessage: e?.error?.message ?? 'Could not restore.' }),
    });
  }

  purge(d: DocumentResponse): void {
    const name = d.merchant || d.originalFilename || 'this document';
    this.confirm.ask({
      title: 'Delete forever?',
      message: `"${name}" will be cleared from live storage. This can't be undone.`,
      confirmLabel: 'Delete forever', danger: true,
    }).then((ok) => {
      if (!ok) return;
      this.purgeConfirmed(d);
    });
  }

  private purgeConfirmed(d: DocumentResponse): void {
    this.api.purgeDocument(d.id).subscribe({
      next: () => {
        this.trash.update((list) => list.filter((x) => x.id !== d.id));
        this.notices.show({ level: 'success', code: 'PURGED', userMessage: 'Permanently deleted.' });
      },
      error: (e) => this.notices.show({ level: 'error', code: 'PURGE_FAIL', userMessage: e?.error?.message ?? 'Could not delete.' }),
    });
  }

  constructor() {
    // Reload documents (and categories) whenever the selected space changes.
    effect(() => {
      const sid = this.spaceCtx.currentSpaceId();
      this.api.listCategories(sid).subscribe((c) => this.categories.set(c));
      this.load();
    });
  }

  load(): void {
    this.loading.set(true);
    this.api.listDocuments(this.spaceCtx.currentSpaceId(), this.category || undefined).subscribe({
      next: (d) => {
        this.docs.set(d);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }
}
