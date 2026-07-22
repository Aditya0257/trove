import { Component, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { SpaceContext } from '../../core/space.context';
import { MoneyPipe } from '../../core/money.pipe';
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
            <tr><th>File</th><th>Category</th><th>Merchant</th><th>Amount</th><th>Date</th><th>Status</th></tr>
          </thead>
          <tbody>
            @for (d of docs(); track d.id) {
              <tr>
                <td><a [routerLink]="['/documents', d.id, 'review']">{{ d.originalFilename || d.id }}</a></td>
                <td>{{ d.category || '—' }}</td>
                <td>{{ d.merchant || '—' }}</td>
                <td>{{ d.amount | money: d.currency }}</td>
                <td>{{ d.docDate || '—' }}</td>
                <td><span class="badge" [class.confirmed]="d.status === 'confirmed'">{{ d.status }}</span></td>
              </tr>
            }
          </tbody>
        </table>
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

  setCategory(code: string): void {
    this.category = code;
    this.load();
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
