import { Component, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { SpaceContext } from '../../core/space.context';
import { DocumentResponse, MonthlySpend, SpendSummary } from '../../core/models';

@Component({
  selector: 'app-spend',
  imports: [RouterLink],
  template: `
    <div class="card">
      <h1>Spend</h1>
      <p class="muted">Totals over <b>confirmed</b> documents only.</p>

      @if (summary(); as s) {
        <p><b>Total: {{ s.total }}</b> across {{ s.count }} document(s).</p>
        <h3>By category</h3>
        @if (s.byCategory.length) {
          <table>
            <thead><tr><th>Category</th><th>Total</th><th>Count</th></tr></thead>
            <tbody>
              @for (c of s.byCategory; track c.category) {
                <tr><td>{{ c.label }}</td><td>{{ c.total }}</td><td>{{ c.count }}</td></tr>
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
              <tr><td>{{ m.period }}</td><td>{{ m.total }}</td><td>{{ m.count }}</td></tr>
            }
          </tbody>
        </table>
      } @else { <p class="muted">—</p> }

      <h3>Flagged as unusual</h3>
      @if (anomalies().length) {
        <table>
          <thead><tr><th>File</th><th>Category</th><th>Amount</th><th>vs usual</th></tr></thead>
          <tbody>
            @for (d of anomalies(); track d.id) {
              <tr>
                <td><a [routerLink]="['/documents', d.id, 'review']">{{ d.originalFilename || d.id }}</a></td>
                <td>{{ d.category }}</td>
                <td>{{ d.amount }}</td>
                <td class="warn">{{ deltaPct(d) }}</td>
              </tr>
            }
          </tbody>
        </table>
      } @else { <p class="muted">Nothing unusual.</p> }
    </div>
  `,
})
export class Spend {
  private api = inject(ApiService);
  private spaceCtx = inject(SpaceContext);

  summary = signal<SpendSummary | null>(null);
  byMonth = signal<MonthlySpend[]>([]);
  anomalies = signal<DocumentResponse[]>([]);

  constructor() {
    effect(() => {
      const sid = this.spaceCtx.currentSpaceId();
      this.api.spendSummary(sid).subscribe((s) => this.summary.set(s));
      this.api.spendByMonth(sid).subscribe((m) => this.byMonth.set(m));
      this.api.listAnomalies(sid).subscribe((a) => this.anomalies.set(a));
    });
  }

  deltaPct(d: DocumentResponse): string {
    const a = d.extra?.['anomaly'] as { deltaPct?: number } | undefined;
    return a?.deltaPct != null ? `+${Math.round(a.deltaPct * 100)}%` : '';
  }
}
