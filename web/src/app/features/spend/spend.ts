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
        <div class="row-between section-head">
          <h3>By category</h3>
          <div class="ccy">
            <button type="button" class="chip sm" [class.on]="chartType() === 'bar'" (click)="chartType.set('bar')">Bars</button>
            <button type="button" class="chip sm" [class.on]="chartType() === 'donut'" (click)="chartType.set('donut')">Donut</button>
          </div>
        </div>
        @if (s.byCategory.length) {
          @if (chartType() === 'bar') {
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
          } @else {
            <div class="donut-wrap">
              <svg viewBox="0 0 170 170" class="donut" aria-hidden="true">
                <g transform="rotate(-90 85 85)">
                  @for (seg of donut(); track seg.label) {
                    <circle cx="85" cy="85" r="60" fill="none" [attr.stroke]="seg.color" stroke-width="24"
                      [attr.stroke-dasharray]="seg.dash" [attr.stroke-dashoffset]="seg.offset" />
                  }
                </g>
                <text x="85" y="80" class="donut-c1">{{ s.count }}</text>
                <text x="85" y="98" class="donut-c2">docs</text>
              </svg>
              <div class="legend">
                @for (seg of donut(); track seg.label) {
                  <div class="leg">
                    <span class="sw" [style.background]="seg.color"></span>
                    <span class="leg-label">{{ seg.label }}</span>
                    <span class="leg-val">{{ seg.value | money: currency() }} · {{ seg.pct }}%</span>
                  </div>
                }
              </div>
            </div>
          }
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

      <div class="row-between section-head">
        <h3>Over time</h3>
        <!-- Chart-type toggle: same teal chips as the category Bars/Donut, so "how to draw it"
             looks identical in both sections. -->
        <div class="ccy">
          <button type="button" class="chip sm" [class.on]="trendType() === 'bar'" (click)="trendType.set('bar')">Bars</button>
          <button type="button" class="chip sm" [class.on]="trendType() === 'wave'" (click)="trendType.set('wave')">Wave</button>
        </div>
      </div>
      <!-- Granularity is a different kind of choice (timeframe, not chart style), so it sits on
           its own row with a label and a distinct indigo accent. -->
      <div class="gran-row">
        <span class="gran-label">View by</span>
        <div class="ccy">
          <button type="button" class="chip sm gran" [class.on]="trendGran() === 'day'" (click)="trendGran.set('day')">Day</button>
          <button type="button" class="chip sm gran" [class.on]="trendGran() === 'week'" (click)="trendGran.set('week')">Week</button>
          <button type="button" class="chip sm gran" [class.on]="trendGran() === 'month'" (click)="trendGran.set('month')">Month</button>
        </div>
      </div>
      @if (byMonth().length) {
        @if (trendType() === 'bar') {
          <div class="trend">
            @for (m of byMonth(); track m.period) {
              <div class="trend-col" [title]="m.period">
                <div class="trend-bar-wrap">
                  <div class="trend-bar" [style.height.%]="pctOfMax(m.total, maxMonth())"></div>
                </div>
                <span class="trend-val">{{ compact(m.total) }}</span>
                <span class="trend-label">{{ periodLabel(m.period) }}</span>
              </div>
            }
          </div>
        } @else {
          <div class="wave-scroll">
            <svg class="wave" [attr.viewBox]="'0 0 ' + wave().w + ' ' + wave().h"
                 [attr.width]="wave().w" [attr.height]="wave().h">
              <defs>
                <linearGradient id="waveGrad" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stop-color="var(--accent)" stop-opacity="0.32" />
                  <stop offset="100%" stop-color="var(--accent)" stop-opacity="0.02" />
                </linearGradient>
              </defs>
              @if (wave().area) { <path [attr.d]="wave().area" fill="url(#waveGrad)" /> }
              <path [attr.d]="wave().line" fill="none" stroke="var(--accent)" stroke-width="2.5"
                    stroke-linejoin="round" stroke-linecap="round" />
              @for (p of wave().pts; track p.period) {
                <circle [attr.cx]="p.x" [attr.cy]="p.y" r="3.5" fill="var(--brand)" />
                <text [attr.x]="p.x" [attr.y]="p.y - 9" class="wave-val">{{ compact(p.val) }}</text>
                <text [attr.x]="p.x" [attr.y]="wave().h - 6" class="wave-lbl">{{ periodLabel(p.period) }}</text>
              }
            </svg>
          </div>
        }
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

      /* Category breakdown - horizontal bars. */
      .bars { display: flex; flex-direction: column; gap: 8px; margin: 8px 0 6px; }
      .bar-row { display: flex; align-items: center; gap: 10px; }
      .bar-label { flex: 0 0 130px; font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
      .bar-track { flex: 1; height: 18px; background: var(--code-bg); border-radius: 6px; overflow: hidden; }
      .bar-fill { height: 100%; background: linear-gradient(90deg, var(--accent), var(--brand)); border-radius: 6px; transition: width 320ms; }
      .bar-val { flex: 0 0 96px; font-size: 13px; font-weight: 600; text-align: right; }
      .detail-table { margin: 4px 0 8px; }
      .detail-table summary { cursor: pointer; font-size: 12px; color: var(--muted); }

      /* Monthly trend - vertical bars. */
      .trend { display: flex; align-items: flex-end; gap: 14px; margin: 10px 0; overflow-x: auto; padding-bottom: 4px; }
      .trend-col { display: flex; flex-direction: column; align-items: center; gap: 4px; flex: 0 0 60px; }
      .trend-bar-wrap { display: flex; align-items: flex-end; height: 120px; width: 44px; }
      .trend-bar { width: 100%; background: linear-gradient(180deg, var(--accent), var(--brand)); border-radius: 7px 7px 0 0; min-height: 3px; transition: height 320ms; }
      .trend-val { font-size: 11px; font-weight: 600; }
      .trend-label { font-size: 11px; color: var(--muted); white-space: nowrap; }

      /* Granularity row - its own line, labelled, with a distinct indigo accent so it
         reads as "timeframe" rather than "chart style" (which stays teal, up by the title). */
      .gran-row { display: flex; align-items: center; gap: 10px; margin: 2px 0 4px; flex-wrap: wrap; }
      .gran-label { font-size: 11px; text-transform: uppercase; letter-spacing: 0.05em; color: var(--muted); }
      .chip.gran { border-color: var(--accent-2-line); color: var(--accent-2); }
      .chip.gran.on { background: var(--accent-2); color: #fff; border-color: var(--accent-2); }

      /* Wave (smooth area) chart. */
      .wave-scroll { overflow-x: auto; margin: 10px 0; }
      .wave { display: block; }
      .wave-val { text-anchor: middle; font-size: 10px; font-weight: 600; fill: var(--ink); }
      .wave-lbl { text-anchor: middle; font-size: 10px; fill: var(--muted); }

      /* Donut (SVG) + legend. */
      .section-head { margin-top: 10px; }
      .chip.sm { padding: 2px 10px; font-size: 12px; }
      .donut-wrap { display: flex; align-items: center; gap: 22px; flex-wrap: wrap; margin: 10px 0 6px; }
      .donut { width: 150px; height: 150px; flex: none; }
      .donut-c1 { text-anchor: middle; font-size: 26px; font-weight: 700; fill: var(--ink); }
      .donut-c2 { text-anchor: middle; font-size: 11px; fill: var(--muted); }
      .legend { display: flex; flex-direction: column; gap: 7px; min-width: 220px; flex: 1; }
      .leg { display: flex; align-items: center; gap: 9px; font-size: 13px; }
      .sw { width: 12px; height: 12px; border-radius: 3px; flex: none; }
      .leg-label { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
      .leg-val { color: var(--muted); font-size: 12px; white-space: nowrap; }
    `,
  ],
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
