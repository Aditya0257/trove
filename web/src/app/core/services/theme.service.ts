import { Injectable, signal } from '@angular/core';

export type Theme = 'light' | 'dark';

/**
 * Owns the light/dark theme. The choice is stamped onto <html data-theme="…">, which
 * flips every CSS token in styles.scss at once. Persisted to localStorage so it sticks
 * across reloads; when the user hasn't chosen, we follow the OS preference and keep
 * following it live until they pick explicitly.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private static readonly KEY = 'trove-theme';

  readonly theme = signal<Theme>('light');

  constructor() {
    const saved = localStorage.getItem(ThemeService.KEY) as Theme | null;
    const os: Theme = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    this.apply(saved ?? os);

    // No explicit choice yet → track the OS as it changes (e.g. macOS auto night mode).
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', (e) => {
      if (!localStorage.getItem(ThemeService.KEY)) {
        this.apply(e.matches ? 'dark' : 'light');
      }
    });
  }

  toggle(): void {
    const next: Theme = this.theme() === 'dark' ? 'light' : 'dark';
    localStorage.setItem(ThemeService.KEY, next); // now an explicit choice
    this.apply(next);
  }

  private apply(theme: Theme): void {
    this.theme.set(theme);
    document.documentElement.setAttribute('data-theme', theme);
  }
}
