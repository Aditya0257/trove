import { Component, computed, effect, inject, signal } from '@angular/core';
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
          <div class="bars">
            @for (c of s.byCategory; track c.category) {
              <div class="bar-row">
                <span class="bar-label" [title]="c.label">{{ c.label }}</span>
                <div class="bar-track">
                  <div class="bar-fill" [style.width.%]="pctOfMax(c.total, maxCategory())"></div>
                </div>
                <span class="bar-val">{{ c.total | money: currency() }}</span>
              </div>
            }
          </div>
          <details class="detail-table">
            <summary>Show as table</summary>
            <table>
              <thead><tr><th>Category</th><th>Total</th><th>Count</th></tr></thead>
              <tbody>
                @for (c of s.byCategory; track c.category) {
                  <tr><td>{{ c.label }}</td><td>{{ c.total | money: currency() }}</td><td>{{ c.count }}</td></tr>
                }
              </tbody>
            </table>
          </details>
        } @else { <p class="muted">No confirmed spend yet.</p> }
      }

      <h3>By month</h3>
      @if (byMonth().length) {
        <div class="trend">
          @for (m of byMonth(); track m.period) {
            <div class="trend-col" [title]="m.period">
              <div class="trend-bar-wrap">
                <div class="trend-bar" [style.height.%]="pctOfMax(m.total, maxMonth())"></div>
              </div>
              <span class="trend-val">{{ compact(m.total) }}</span>
              <span class="trend-label">{{ shortMonth(m.period) }}</span>
            </div>
          }
        </div>
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

      /* Category breakdown — horizontal bars. */
      .bars { display: flex; flex-direction: column; gap: 8px; margin: 8px 0 6px; }
      .bar-row { display: flex; align-items: center; gap: 10px; }
      .bar-label { flex: 0 0 130px; font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
      .bar-track { flex: 1; height: 18px; background: var(--code-bg); border-radius: 6px; overflow: hidden; }
      .bar-fill { height: 100%; background: linear-gradient(90deg, var(--accent), var(--brand)); border-radius: 6px; transition: width 320ms; }
      .bar-val { flex: 0 0 96px; font-size: 13px; font-weight: 600; text-align: right; }
      .detail-table { margin: 4px 0 8px; }
      .detail-table summary { cursor: pointer; font-size: 12px; color: var(--muted); }

      /* Monthly trend — vertical bars. */
      .trend { display: flex; align-items: flex-end; gap: 10px; margin: 10px 0; overflow-x: auto; padding-bottom: 4px; }
      .trend-col { display: flex; flex-direction: column; align-items: center; gap: 4px; flex: 1; min-width: 46px; }
      .trend-bar-wrap { display: flex; align-items: flex-end; height: 120px; width: 100%; }
      .trend-bar { width: 60%; margin: 0 auto; background: linear-gradient(180deg, var(--accent), var(--brand)); border-radius: 6px 6px 0 0; min-height: 3px; transition: height 320ms; }
      .trend-val { font-size: 11px; font-weight: 600; }
      .trend-label { font-size: 11px; color: var(--muted); white-space: nowrap; }
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

  // ── chart helpers ────────────────────────────────────────────────────────
  protected maxCategory = computed(() =>
    Math.max(1, ...(this.summary()?.byCategory ?? []).map((c) => c.total)),
  );
  protected maxMonth = computed(() => Math.max(1, ...this.byMonth().map((m) => m.total)));

  /** Bar length as a % of the largest value (min 2% so tiny bars stay visible). */
  protected pctOfMax(value: number, max: number): number {
    return max > 0 ? Math.max(2, (value / max) * 100) : 0;
  }

  /** Compact money for bar labels: 9661 → 9.7k, 1200000 → 1.2M. */
  protected compact(v: number): string {
    if (v >= 1_000_000) return (v / 1_000_000).toFixed(1).replace(/\.0$/, '') + 'M';
    if (v >= 1_000) return (v / 1_000).toFixed(1).replace(/\.0$/, '') + 'k';
    return String(Math.round(v));
  }

  /** "2026-07" → "Jul 26". */
  protected shortMonth(period: string): string {
    const [y, m] = period.split('-');
    const abbr = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    const i = Number(m) - 1;
    return i >= 0 && i < 12 ? `${abbr[i]} ${y.slice(2)}` : period;
  }

  deltaPct(d: DocumentResponse): string {
    const a = d.extra?.['anomaly'] as { deltaPct?: number } | undefined;
    return a?.deltaPct != null ? `+${Math.round(a.deltaPct * 100)}%` : '';
  }
}
