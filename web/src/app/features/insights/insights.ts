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
  templateUrl: './insights.html',
  styleUrl: './insights.scss',
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
