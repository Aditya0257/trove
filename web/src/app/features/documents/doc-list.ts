import { Component, computed, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { SpaceContext } from '../../core/space.context';
import { MoneyPipe } from '../../core/money.pipe';
import { NoticeService } from '../../core/notice/notice.service';
import { Category, DocumentResponse } from '../../core/models';

@Component({
  selector: 'app-doc-list',
  imports: [RouterLink, MoneyPipe],
  template: `
    <div class="card">
      <div class="row-between">
        <h1>Documents</h1>
        <a routerLink="/upload" class="button">＋ Upload</a>
      </div>

      <div class="cats">
        <button type="button" class="chip" [class.on]="category === ''" (click)="setCategory('')">All</button>
        @for (c of categories(); track c.code) {
          <button type="button" class="chip" [class.on]="category === c.code" (click)="setCategory(c.code)">
            {{ c.label }}
          </button>
        }
      </div>

      @if (loading()) { <p class="muted">Loading…</p> }
      @else if (docs().length === 0) {
        <p class="muted">
          No documents{{ category ? ' in this category' : '' }} yet.
          <a routerLink="/upload">Snap or paste your first</a>.
        </p>
      }
      @else {
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

        <div class="pager">
          <select (change)="setPageSize($any($event.target).value)">
            <option value="25" [selected]="pageSize() === 25">25 per page</option>
            <option value="50" [selected]="pageSize() === 50">50 per page</option>
            <option value="100" [selected]="pageSize() === 100">100 per page</option>
            <option value="0" [selected]="pageSize() === 0">All (for Ctrl/⌘+F)</option>
          </select>
          @if (pageSize() !== 0 && totalPages() > 1) {
            <div class="pages">
              <button type="button" [disabled]="page() === 0" (click)="page.set(page() - 1)">‹ Prev</button>
              <span>Page {{ page() + 1 }} of {{ totalPages() }}</span>
              <button type="button" [disabled]="page() >= totalPages() - 1" (click)="page.set(page() + 1)">Next ›</button>
            </div>
          }
          <span class="muted total">{{ docs().length }} document(s)</span>
        </div>
      }
    </div>
  `,
  styles: [
    `
      .cats { display: flex; flex-wrap: wrap; gap: 8px; margin: 10px 0 14px; }
      .chip {
        border: 1px solid rgba(47, 111, 106, 0.35); background: transparent; color: #2f6f6a;
        border-radius: 999px; padding: 5px 12px; font-size: 13px; cursor: pointer;
      }
      .chip.on { background: #2f6f6a; color: #fff; border-color: #2f6f6a; }
      td { vertical-align: middle; }
      .del {
        border: 1px solid rgba(192, 57, 43, 0.4); background: transparent; color: #c0392b;
        border-radius: 6px; padding: 4px 12px; font-size: 12px; cursor: pointer; white-space: nowrap;
      }
      .del:hover { background: rgba(192, 57, 43, 0.08); }
      .pager { display: flex; align-items: center; gap: 14px; margin-top: 14px; flex-wrap: wrap; }
      .pager select { padding: 6px 8px; border-radius: 8px; }
      .pages { display: flex; align-items: center; gap: 10px; }
      /* Override the global brand button (light text on brand fill): these are neutral
         nav buttons on a white row, so give them a dark label + no stray top margin. */
      .pages button {
        margin: 0; border: 1px solid var(--line, #ccc); background: #fff; color: #2f6f6a;
        border-radius: 8px; padding: 5px 12px; cursor: pointer; font-size: 13px; font-weight: 600;
      }
      .pages button:hover:not(:disabled) { background: rgba(47, 111, 106, 0.08); }
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

  /** Page size (0 = show All, so browser find works on the full list). */
  pageSize = signal(25);
  page = signal(0);

  /** The slice of documents shown on the current page. */
  pagedDocs = computed(() => {
    const size = this.pageSize();
    const all = this.docs();
    if (size === 0) return all;
    const start = this.page() * size;
    return all.slice(start, start + size);
  });

  totalPages = computed(() => {
    const size = this.pageSize();
    return size === 0 ? 1 : Math.max(1, Math.ceil(this.docs().length / size));
  });

  private notices = inject(NoticeService);

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
    if (!confirm(`Delete "${name}"? This removes it from your vault.`)) {
      return;
    }
    this.api.deleteDocument(d.id).subscribe({
      next: () => {
        this.docs.update((list) => list.filter((x) => x.id !== d.id));
        this.notices.show({ level: 'success', code: 'DELETED', userMessage: 'Document deleted.' });
      },
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
