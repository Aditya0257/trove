import { Injectable, signal } from '@angular/core';

export type CategoryChart = 'bar' | 'donut';
export type TrendChart = 'bar' | 'wave';

/**
 * Small user-controllable settings, persisted to localStorage so they survive a
 * reload and a re-login on the same browser (they live under their own keys, which
 * sign-out does not clear). Two kinds so far: the AI-reading toggle - when off,
 * uploads skip the vision model, so the document is stored and left for manual entry
 * with no wait and no credits spent - and the chart-view choices on the Spend screen
 * (Bars vs Donut for categories, Bars vs Wave for the over-time trend), remembered so
 * the user's preferred view is what they see next time.
 */
@Injectable({ providedIn: 'root' })
export class SettingsService {
  private static readonly AI_KEY = 'trove-ai-reading';
  private static readonly CATEGORY_CHART_KEY = 'trove-chart-category';
  private static readonly TREND_CHART_KEY = 'trove-chart-trend';

  /** Whether uploads are read by the AI (default on). */
  readonly aiReading = signal<boolean>(localStorage.getItem(SettingsService.AI_KEY) !== 'off');

  /** Preferred category chart: bars or donut (default bars). */
  readonly categoryChart = signal<CategoryChart>(
    localStorage.getItem(SettingsService.CATEGORY_CHART_KEY) === 'donut' ? 'donut' : 'bar',
  );

  /** Preferred over-time trend chart: bars or wave (default bars). */
  readonly trendChart = signal<TrendChart>(
    localStorage.getItem(SettingsService.TREND_CHART_KEY) === 'wave' ? 'wave' : 'bar',
  );

  toggleAiReading(): void {
    const next = !this.aiReading();
    this.aiReading.set(next);
    localStorage.setItem(SettingsService.AI_KEY, next ? 'on' : 'off');
  }

  setCategoryChart(value: CategoryChart): void {
    this.categoryChart.set(value);
    localStorage.setItem(SettingsService.CATEGORY_CHART_KEY, value);
  }

  setTrendChart(value: TrendChart): void {
    this.trendChart.set(value);
    localStorage.setItem(SettingsService.TREND_CHART_KEY, value);
  }
}
