import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { Category, DocumentResponse } from '../../core/models';

@Component({
  selector: 'app-doc-list',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="card">
      <div class="row-between">
        <h1>Documents</h1>
        <a routerLink="/upload" class="button">＋ Upload</a>
      </div>

      <label>Filter by category
        <select [(ngModel)]="category" (ngModelChange)="load()">
          <option value="">All</option>
          @for (c of categories(); track c.code) { <option [value]="c.code">{{ c.label }}</option> }
        </select>
      </label>

      @if (loading()) { <p class="muted">Loading…</p> }
      @else if (docs().length === 0) { <p class="muted">No documents yet.</p> }
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
                <td>{{ d.amount != null ? (d.currency || '') + ' ' + d.amount : '—' }}</td>
                <td>{{ d.docDate || '—' }}</td>
                <td><span class="badge" [class.confirmed]="d.status === 'confirmed'">{{ d.status }}</span></td>
              </tr>
            }
          </tbody>
        </table>
      }
    </div>
  `,
})
export class DocList {
  private api = inject(ApiService);

  categories = signal<Category[]>([]);
  docs = signal<DocumentResponse[]>([]);
  category = '';
  loading = signal(false);

  ngOnInit(): void {
    this.api.listCategories().subscribe((c) => this.categories.set(c));
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.api.listDocuments(this.category || undefined).subscribe({
      next: (d) => {
        this.docs.set(d);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }
}
