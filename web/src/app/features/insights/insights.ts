import { Component, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { SpaceContext } from '../../core/services/space.context';
import { ExpiringItem, RecurringGroup } from '../../core/models/models';
import { HelpCard } from '../../shared/components/help-card';
import { TroveSelect, SelectOption } from '../../shared/components/select';
import { MoneyPipe } from '../../shared/pipes/money.pipe';

/**
 * Insights — document intelligence over confirmed documents: what is expiring soon
 * (bills due, warranties ending, renewals) and what recurs (subscriptions). Read-only;
 * both lists come straight from the /api/insights endpoints.
 */
@Component({
  selector: 'app-insights',
  imports: [FormsModule, RouterLink, HelpCard, TroveSelect, MoneyPipe],
  template: `
    <div class="card">
      <div class="row-between">
        <h1>Insights</h1>
      </div>
      <trove-help-card title="What Insights shows" [open]="false" [user]="helpUser" [dev]="helpDev"></trove-help-card>

      <!-- ── Expiring soon ─────────────────────────────────────────────── -->
      <div class="row-between section-head">
        <h2>Expiring soon</h2>
        <label class="win">Within
          <trove-select name="win" [ngModel]="windowDays()" (ngModelChange)="windowDays.set($event)"
                        [options]="windowOptions" ariaLabel="Time window"></trove-select>
        </label>
      </div>

      @if (loadingExp()) {
        <p class="muted">Loading…</p>
      } @else if (expiring().length === 0) {
        <p class="muted">Nothing coming up in this window. You are all clear.</p>
      } @else {
        <ul class="items">
          @for (e of expiring(); track e.documentId + e.kind + e.date) {
            <li [class.overdue]="e.daysLeft < 0">
              <a class="item-main" [routerLink]="['/documents', e.documentId, 'review']">
                <span class="kind" [class]="'kind-' + e.kind">{{ kindLabel(e.kind) }}</span>
                <span class="title">{{ e.title }}</span>
                @if (e.amount !== null) { <span class="amt">{{ e.amount | money: e.currency || 'INR' }}</span> }
              </a>
              <div class="when">
                <span class="date">{{ e.date }}</span>
                <span class="rel" [class.danger]="e.daysLeft < 0" [class.soon]="e.daysLeft >= 0 && e.daysLeft <= 7">{{ rel(e.daysLeft) }}</span>
              </div>
            </li>
          }
        </ul>
      }
    </div>

    <!-- ── Recurring & subscriptions ────────────────────────────────────── -->
    <div class="card">
      <h2>Recurring &amp; subscriptions</h2>
      <p class="muted sub">Merchants that bill you on a regular rhythm, with the next expected date.</p>

      @if (loadingRec()) {
        <p class="muted">Loading…</p>
      } @else if (recurring().length === 0) {
        <p class="muted">No recurring patterns detected yet. They appear once a merchant has billed you a few times on a steady cadence.</p>
      } @else {
        <div class="table-scroll">
          <table>
            <thead><tr><th>Merchant</th><th>Category</th><th>Every</th><th>Typical</th><th>Times</th><th>Next expected</th></tr></thead>
            <tbody>
              @for (r of recurring(); track r.merchant + '|' + r.category) {
                <tr>
                  <td class="m-name">{{ r.merchant || '(unknown)' }}</td>
                  <td>{{ r.categoryLabel || r.category || '-' }}</td>
                  <td><span class="cadence">{{ cadenceLabel(r.cadence) }}</span></td>
                  <td>{{ r.averageAmount !== null ? (r.averageAmount | money: r.currency || 'INR') : '-' }}</td>
                  <td>{{ r.occurrences }}</td>
                  <td>{{ r.nextExpected || '-' }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </div>
  `,
  styles: [
    `
      h2 { font-size: 1.05rem; margin: 0; }
      .section-head { margin-top: 4px; }
      .sub { margin: 2px 0 12px; }
      .win { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--muted); }
      .win trove-select { min-width: 130px; }

      .items { list-style: none; margin: 8px 0 0; padding: 0; }
      .items li {
        display: flex; align-items: center; justify-content: space-between; gap: 12px;
        padding: 10px 12px; border: 1px solid var(--line); border-radius: 10px; margin-bottom: 8px; flex-wrap: wrap;
      }
      .items li.overdue { border-color: var(--danger-line); background: var(--danger-soft); }
      .item-main { display: flex; align-items: center; gap: 10px; text-decoration: none; color: var(--ink); min-width: 0; flex: 1 1 auto; }
      .item-main:hover .title { text-decoration: underline; }
      .title { font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
      .amt { color: var(--muted); font-size: 13px; white-space: nowrap; }
      .kind { font-size: 10px; font-weight: 800; text-transform: uppercase; letter-spacing: 0.04em; border-radius: 6px; padding: 2px 7px; white-space: nowrap; }
      .kind-due { background: var(--accent-soft); color: var(--accent); }
      .kind-renewal { background: color-mix(in srgb, var(--brand) 16%, transparent); color: var(--brand); }
      .kind-warranty { background: var(--hover); color: var(--muted); }
      .when { display: flex; align-items: baseline; gap: 10px; white-space: nowrap; }
      .date { font-variant-numeric: tabular-nums; color: var(--muted); font-size: 13px; }
      .rel { font-size: 12px; font-weight: 600; color: var(--muted); }
      .rel.soon { color: var(--brand); }
      .rel.danger { color: var(--danger); }

      .table-scroll { overflow-x: auto; max-width: 100%; }
      .table-scroll table { min-width: 640px; }
      .m-name { font-weight: 600; }
      .cadence { text-transform: capitalize; }
    `,
  ],
})
export class Insights {
  private api = inject(ApiService);
  private spaceCtx = inject(SpaceContext);

  expiring = signal<ExpiringItem[]>([]);
  recurring = signal<RecurringGroup[]>([]);
  loadingExp = signal(true);
  loadingRec = signal(true);
  windowDays = signal('90'); // a signal so the expiring effect re-runs when it changes

  protected windowOptions: SelectOption[] = [
    { value: '30', label: '30 days' },
    { value: '90', label: '90 days' },
    { value: '180', label: '6 months' },
    { value: '365', label: '1 year' },
  ];

  protected helpUser =
    'Insights reads your confirmed documents and surfaces what matters day-to-day. "Expiring soon" gathers ' +
    'every upcoming date in one place: a bill due, an insurance or subscription renewing, a warranty about to ' +
    'run out (and anything that lapsed in the last month, so a just-expired ID is not hidden). "Recurring" spots ' +
    'merchants that bill you on a steady rhythm and predicts the next date, so a subscription never surprises you. ' +
    'Anything you have already handled in Reminders (marked Done or Dismissed) drops off "Expiring soon" - ' +
    'Reminders stays your action list; this is the read-only overview of what is still outstanding. ' +
    'Nothing here is stored separately - it is computed live from what you have confirmed, so it is always current.';
  protected helpDev =
    'Reads GET /api/insights/expiring (due dates + extra.warrantyUntil within the window, kind = due/renewal/' +
    'warranty) and GET /api/insights/recurring (confirmed docs grouped by merchant+category, cadence inferred from ' +
    'the gaps between doc dates using the same tolerance bands as the reminder detector, next date via ' +
    'ReminderRecurrence.next). Expiring excludes any document whose reminder is Done or Dismissed, so it stays ' +
    'the read-only overview of what is outstanding while Reminders remains the action inbox. Confirmed documents ' +
    'only; no extra storage and no AI cost.';

  constructor() {
    // Recurring depends only on the space; refetch on space switch.
    effect(() => {
      const sid = this.spaceCtx.currentSpaceId();
      this.loadingRec.set(true);
      this.api.insightsRecurring(sid).subscribe({
        next: (r) => { this.recurring.set(r); this.loadingRec.set(false); },
        error: () => this.loadingRec.set(false),
      });
    });
    // Expiring depends on the space AND the chosen window.
    effect(() => {
      const sid = this.spaceCtx.currentSpaceId();
      const win = Number(this.windowDays());
      this.loadingExp.set(true);
      this.api.insightsExpiring(sid, win).subscribe({
        next: (e) => { this.expiring.set(e); this.loadingExp.set(false); },
        error: () => this.loadingExp.set(false),
      });
    });
  }

  kindLabel(k: string): string {
    return k === 'due' ? 'Due' : k === 'renewal' ? 'Renewal' : 'Warranty';
  }
  cadenceLabel(c: string): string {
    return c === 'weekly' ? 'Week' : c === 'monthly' ? 'Month' : c === 'quarterly' ? 'Quarter' : 'Year';
  }
  rel(daysLeft: number): string {
    if (daysLeft < 0) {
      const n = -daysLeft;
      return `overdue by ${n} day${n === 1 ? '' : 's'}`;
    }
    if (daysLeft === 0) return 'today';
    if (daysLeft === 1) return 'tomorrow';
    return `in ${daysLeft} days`;
  }
}
