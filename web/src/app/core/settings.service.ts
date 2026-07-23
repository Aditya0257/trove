import { Injectable, signal } from '@angular/core';

/**
 * Small user-controllable settings, persisted to localStorage. Currently just the
 * AI-reading toggle: when off, uploads skip the vision model — the document is stored
 * and left for manual entry, with no wait and no credits spent. Handy when you'd
 * rather type the details yourself, or to stop consuming the shared daily budget.
 */
@Injectable({ providedIn: 'root' })
export class SettingsService {
  private static readonly AI_KEY = 'trove-ai-reading';

  /** Whether uploads are read by the AI (default on). */
  readonly aiReading = signal<boolean>(localStorage.getItem(SettingsService.AI_KEY) !== 'off');

  toggleAiReading(): void {
    const next = !this.aiReading();
    this.aiReading.set(next);
    localStorage.setItem(SettingsService.AI_KEY, next ? 'on' : 'off');
  }
}
