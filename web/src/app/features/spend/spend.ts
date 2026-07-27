import { Component, computed, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { SpaceContext } from '../../core/services/space.context';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { DateTimePipe } from '../../shared/pipes/datetime.pipe';
import { CURRENCIES } from '../../core/config/currencies';
import { DocumentResponse, MonthlySpend, SpendSummary } from '../../core/models/models';

@Component({
  selector: 'app-spend',
  imports: [RouterLink, MoneyPipe, DateTimePipe],
  templateUrl: './spend.html',
  styleUrl: './spend.scss',
})
export class Spend {
  private api = inject(ApiService);
  private spaceCtx = inject(SpaceContext);

  protected readonly currencies = CURRENCIES;
  protected currency = signal<string>('INR');
  protected chartType = signal<'bar' | 'donut'>('bar');
  protected trendGran = signal<'day' | 'week' | 'month'>('month');
  protected trendType = signal<'bar' | 'wave'>('bar');
  private static readonly ABBR = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

  /** Smooth area/line ("wave") chart geometry for the over-time series. */
  protected wave = computed(() => {
    const data = this.byMonth();
    const max = this.maxMonth();
    const h = 156;
    const padTop = 24;
    const padBot = 24;
    const padX = 28;
    const base = h - padBot;
    const usable = base - padTop;
    const n = data.length;
    const w = Math.max(280, n * 72);
    const pts = data.map((m, i) => ({
      x: n === 1 ? w / 2 : padX + (i * (w - 2 * padX)) / (n - 1),
      y: base - (max > 0 ? (m.total / max) * usable : 0),
      val: m.total,
      period: m.period,
    }));
    const line = this.smoothPath(pts);
    const area = pts.length >= 2
      ? `${line} L ${pts[pts.length - 1].x.toFixed(1)} ${base} L ${pts[0].x.toFixed(1)} ${base} Z`
      : '';
    return { w, h, base, line, area, pts };
  });

  /** Catmull-Rom → cubic-bezier smoothing so the line reads as a wave. */
  private smoothPath(pts: { x: number; y: number }[]): string {
    if (!pts.length) return '';
    if (pts.length === 1) return `M ${pts[0].x.toFixed(1)} ${pts[0].y.toFixed(1)}`;
    let d = `M ${pts[0].x.toFixed(1)} ${pts[0].y.toFixed(1)}`;
    for (let i = 0; i < pts.length - 1; i++) {
      const p0 = pts[i - 1] ?? pts[i];
      const p1 = pts[i];
      const p2 = pts[i + 1];
      const p3 = pts[i + 2] ?? pts[i + 1];
      const c1x = p1.x + (p2.x - p0.x) / 6;
      const c1y = p1.y + (p2.y - p0.y) / 6;
      const c2x = p2.x - (p3.x - p1.x) / 6;
      const c2y = p2.y - (p3.y - p1.y) / 6;
      d += ` C ${c1x.toFixed(1)} ${c1y.toFixed(1)} ${c2x.toFixed(1)} ${c2y.toFixed(1)} ${p2.x.toFixed(1)} ${p2.y.toFixed(1)}`;
    }
    return d;
  }

  /** Distinct, theme-neutral segment colours for the donut/legend. */
  private static readonly PALETTE = [
    '#2f9e78', '#3b7ddd', '#e0a04d', '#c0576b', '#7a5bd0',
    '#2bb3b3', '#d98a3d', '#5a8f3c', '#b8567a', '#4d7cc7',
  ];
  private static readonly DONUT_R = 60;

  /** Category slices as SVG stroke-dasharray arcs (drawn on one <circle> each). */
  protected donut = computed(() => {
    const cats = this.summary()?.byCategory ?? [];
    const total = cats.reduce((s, c) => s + c.total, 0) || 1;
    const circ = 2 * Math.PI * Spend.DONUT_R;
    let cumulative = 0;
    return cats.map((c, i) => {
      const frac = c.total / total;
      const len = frac * circ;
      const seg = {
        color: Spend.PALETTE[i % Spend.PALETTE.length],
        label: c.label,
        value: c.total,
        pct: Math.round(frac * 100),
        dash: `${len} ${circ - len}`,
        offset: -cumulative,
      };
      cumulative += len;
      return seg;
    });
  });
  summary = signal<SpendSummary | null>(null);
  byMonth = signal<MonthlySpend[]>([]);
  anomalies = signal<DocumentResponse[]>([]);

  constructor() {
    // Re-fetch when the space OR the display currency changes.
    effect(() => {
      const sid = this.spaceCtx.currentSpaceId();
      const ccy = this.currency();
      const gran = this.trendGran();
      this.api.spendSummary(sid, ccy).subscribe((s) => this.summary.set(s));
      this.api.spendByMonth(sid, ccy, gran).subscribe((m) => this.byMonth.set(m));
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
    const i = Number(m) - 1;
    return i >= 0 && i < 12 ? `${Spend.ABBR[i]} ${y.slice(2)}` : period;
  }

  /** Labels a period string according to the current granularity. */
  protected periodLabel(period: string): string {
    const g = this.trendGran();
    if (g === 'day') {
      const [, m, d] = period.split('-');
      const i = Number(m) - 1;
      return i >= 0 && i < 12 ? `${Number(d)} ${Spend.ABBR[i]}` : period;
    }
    if (g === 'week') {
      const [y, w] = period.split('-W');
      return w ? `W${w} ${y.slice(2)}` : period;
    }
    return this.shortMonth(period);
  }

  deltaPct(d: DocumentResponse): string {
    const a = d.extra?.['anomaly'] as { deltaPct?: number } | undefined;
    return a?.deltaPct != null ? `+${Math.round(a.deltaPct * 100)}%` : '';
  }
}
