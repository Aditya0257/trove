import { Component, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { SpaceContext } from '../../core/space.context';
import { MoneyPipe } from '../../core/money.pipe';
import { DateTimePipe } from '../../core/datetime.pipe';
import { CURRENCIES } from '../../core/currencies';
import { DocumentResponse, MonthlySpend, SpendSummary } from '../../core/models';

@Component({
  selector: 'app-spend',
  imports: [RouterLink, MoneyPipe, DateTimePipe],
  template: `
    <div class="card">
      <div class="row-between">
        <h1>Spend</h1>
        <div class="ccy">
          <span class="muted">Show in</span>
          @for (c of currencies; track c) {
            <button type="button" class="chip" [class.on]="currency() === c" (click)="setCurrency(c)">{{ c }}</button>
          }
        </div>
      </div>
      <p class="muted">Totals over <b>confirmed</b> documents only, converted to {{ currency() }}.</p>

      @if (summary(); as s) {
        <p><b>Total: {{ s.total | money: currency() }}</b> across {{ s.count }} document(s).</p>
        @if (s.ratesAsOf) {
          <p class="muted small">Amounts in other currencies converted at rates from {{ s.ratesAsOf | prettyDate }}.</p>
        }
        <h3>By category</h3>
        @if (s.byCategory.length) {
          <table>
            <thead><tr><th>Category</th><th>Total</th><th>Count</th></tr></thead>
            <tbody>
              @for (c of s.byCategory; track c.category) {
                <tr><td>{{ c.label }}</td><td>{{ c.total | money: currency() }}</td><td>{{ c.count }}</td></tr>
              }
            </tbody>
          </table>
        } @else { <p class="muted">No confirmed spend yet.</p> }
      }

      <h3>By month</h3>
      @if (byMonth().length) {
        <table>
          <thead><tr><th>Month</th><th>Total</th><th>Count</th></tr></thead>
          <tbody>
            @for (m of byMonth(); track m.period) {
              <tr><td>{{ m.period }}</td><td>{{ m.total | money: currency() }}</td><td>{{ m.count }}</td></tr>
            }
          </tbody>
        </table>
      } @else { <p class="muted">Nothing to show yet.</p> }

      <h3>Flagged as unusual</h3>
      @if (anomalies().length) {
        <table>
          <thead><tr><th>File</th><th>Category</th><th>Amount</th><th>vs usual</th></tr></thead>
          <tbody>
            @for (d of anomalies(); track d.id) {
              <tr>
                <td><a [routerLink]="['/documents', d.id, 'review']">{{ d.originalFilename || d.id }}</a></td>
                <td>{{ d.category }}</td>
                <td>{{ d.amount | money: d.currency }}</td>
                <td class="warn">{{ deltaPct(d) }}</td>
              </tr>
            }
          </tbody>
        </table>
      } @else { <p class="muted">Nothing unusual.</p> }
    </div>
  `,
  styles: [
    `
      .ccy { display: flex; align-items: center; gap: 6px; }
      .chip {
        margin: 0; border: 1px solid var(--accent-line); background: transparent; color: var(--accent);
        border-radius: 999px; padding: 3px 12px; font-size: 13px; font-weight: 600; cursor: pointer;
      }
      .chip.on { background: var(--accent); color: var(--brand-ink); border-color: var(--accent); }
      .small { font-size: 12px; }
    `,
  ],
})
export class Spend {
  private api = inject(ApiService);
  private spaceCtx = inject(SpaceContext);

  protected readonly currencies = CURRENCIES;
  protected currency = signal<string>('INR');
  summary = signal<SpendSummary | null>(null);
  byMonth = signal<MonthlySpend[]>([]);
  anomalies = signal<DocumentResponse[]>([]);

  constructor() {
    // Re-fetch when the space OR the display currency changes.
    effect(() => {
      const sid = this.spaceCtx.currentSpaceId();
      const ccy = this.currency();
      this.api.spendSummary(sid, ccy).subscribe((s) => this.summary.set(s));
      this.api.spendByMonth(sid, ccy).subscribe((m) => this.byMonth.set(m));
      this.api.listAnomalies(sid).subscribe((a) => this.anomalies.set(a));
    });
  }

  setCurrency(c: string): void {
    this.currency.set(c);
  }

  deltaPct(d: DocumentResponse): string {
    const a = d.extra?.['anomaly'] as { deltaPct?: number } | undefined;
    return a?.deltaPct != null ? `+${Math.round(a.deltaPct * 100)}%` : '';
  }
}
