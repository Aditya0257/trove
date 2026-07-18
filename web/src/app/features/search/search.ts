import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { SpaceContext } from '../../core/space.context';
import { MoneyPipe } from '../../core/money.pipe';
import { SearchResult } from '../../core/models';

@Component({
  selector: 'app-search',
  imports: [FormsModule, RouterLink, MoneyPipe],
  template: `
    <div class="card">
      <h1>Search</h1>
      <p class="muted">Try: "my last water bill", "all Nike purchases", "electricity from June".</p>
      <form (ngSubmit)="run()">
        <div class="row">
          <input name="q" [(ngModel)]="q" placeholder="Search your documents…" style="flex:1" />
          <button type="submit" [disabled]="loading()">Search</button>
        </div>
      </form>

      @if (result(); as r) {
        <p class="muted">
          Interpreted → category: <b>{{ interpreted(r, 'categoryCode') }}</b>,
          range: {{ interpreted(r, 'dateFrom') }} … {{ interpreted(r, 'dateTo') }},
          text: <b>{{ interpreted(r, 'text') }}</b> — {{ r.count }} result(s)
        </p>
        @if (r.results.length) {
          <table>
            <thead><tr><th>File</th><th>Category</th><th>Merchant</th><th>Amount</th><th>Date</th></tr></thead>
            <tbody>
              @for (d of r.results; track d.id) {
                <tr>
                  <td><a [routerLink]="['/documents', d.id, 'review']">{{ d.originalFilename || d.id }}</a></td>
                  <td>{{ d.category || '—' }}</td>
                  <td>{{ d.merchant || '—' }}</td>
                  <td>{{ d.amount | money: d.currency }}</td>
                  <td>{{ d.docDate || '—' }}</td>
                </tr>
              }
            </tbody>
          </table>
        } @else { <p class="muted">No matches.</p> }
      }
    </div>
  `,
})
export class Search {
  private api = inject(ApiService);
  private spaceCtx = inject(SpaceContext);

  q = '';
  result = signal<SearchResult | null>(null);
  loading = signal(false);

  run(): void {
    if (!this.q.trim()) return;
    this.loading.set(true);
    this.api.search(this.q, this.spaceCtx.currentSpaceId()).subscribe({
      next: (r) => {
        this.result.set(r);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  interpreted(r: SearchResult, key: string): string {
    const v = r.interpreted?.[key];
    return v == null || v === '' ? '—' : String(v);
  }
}
