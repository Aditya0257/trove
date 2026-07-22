import { Component, OnDestroy, inject, signal } from '@angular/core';
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
      <p class="muted">Search in plain English. Tap an example or type your own:</p>
      <div class="examples">
        @for (ex of examples; track ex) {
          <button type="button" class="chip" (click)="runExample(ex)" [disabled]="loading()">{{ ex }}</button>
        }
      </div>
      <form (ngSubmit)="run()">
        <div class="row">
          <input name="q" [(ngModel)]="q" placeholder="Search your documents…" style="flex:1" />
          <button type="submit" [disabled]="loading()">Search</button>
        </div>
      </form>

      @if (loading()) {
        <p class="muted searching">{{ status() }}<span class="dots"></span></p>
      } @else if (result(); as r) {
        <p class="muted">
          Interpreted → category: <b>{{ interpreted(r, 'categoryCode') }}</b>,
          sort: {{ interpreted(r, 'sortBy') }} {{ interpreted(r, 'sortDir') }},
          range: {{ interpreted(r, 'dateFrom') }} … {{ interpreted(r, 'dateTo') }},
          text: <b>{{ interpreted(r, 'text') }}</b> · {{ r.count }} result(s)
        </p>
        @if (r.results.length) {
          <table>
            <thead><tr><th>File</th><th>Category</th><th>Merchant</th><th>Amount</th><th>Date</th></tr></thead>
            <tbody>
              @for (d of r.results; track d.id) {
                <tr>
                  <td><a [routerLink]="['/documents', d.id, 'review']">{{ d.originalFilename || d.id }}</a></td>
                  <td>{{ d.category || '-' }}</td>
                  <td>{{ d.merchant || '-' }}</td>
                  <td>{{ d.amount | money: d.currency }}</td>
                  <td>{{ d.docDate || '-' }}</td>
                </tr>
              }
            </tbody>
          </table>
        } @else { <p class="muted">No matches.</p> }
      }
    </div>
  `,
  styles: [
    `
      .examples { display: flex; flex-wrap: wrap; gap: 8px; margin: 8px 0 12px; }
      .chip {
        border: 1px solid rgba(47, 111, 106, 0.35); background: rgba(47, 111, 106, 0.06);
        color: #2f6f6a; border-radius: 999px; padding: 6px 12px; font-size: 13px; cursor: pointer;
      }
      .chip:hover { background: rgba(47, 111, 106, 0.12); }
      .chip:disabled { opacity: 0.5; cursor: default; }
    `,
  ],
})
export class Search implements OnDestroy {
  private api = inject(ApiService);
  private spaceCtx = inject(SpaceContext);

  readonly examples = [
    'my last water bill',
    'most expensive shopping',
    'all Nike purchases',
    'electricity from July',
  ];

  q = '';
  result = signal<SearchResult | null>(null);
  loading = signal(false);
  status = signal('');
  private timer: ReturnType<typeof setInterval> | null = null;

  private readonly STAGES = [
    'Understanding your question…',
    'Scanning your documents…',
    'Ranking the best matches…',
    'Almost there…',
  ];

  runExample(example: string): void {
    this.q = example;
    this.run();
  }

  run(): void {
    if (!this.q.trim()) return;
    this.loading.set(true);
    this.result.set(null);
    this.startStatus();
    this.api.search(this.q, this.spaceCtx.currentSpaceId()).subscribe({
      next: (r) => {
        this.result.set(r);
        this.stop();
      },
      error: () => this.stop(),
    });
  }

  private startStatus(): void {
    let i = 0;
    this.status.set(this.STAGES[0]);
    this.timer = setInterval(() => {
      i = Math.min(i + 1, this.STAGES.length - 1);
      this.status.set(this.STAGES[i]);
    }, 1200);
  }

  private stop(): void {
    this.loading.set(false);
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  ngOnDestroy(): void {
    this.stop();
  }

  interpreted(r: SearchResult, key: string): string {
    const v = r.interpreted?.[key];
    return v == null || v === '' ? '-' : String(v);
  }
}
