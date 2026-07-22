import { Injectable, signal } from '@angular/core';
import { Notice } from './notice.model';

/** One recorded API round-trip, for the Developer drawer + console. */
export interface DevLogEntry {
  at: number;
  method: string;
  url: string;
  status: number; // 0 = never reached the server
  durationMs: number;
  requestId?: string | null;
  notice?: Notice | null;
  extractionMeta?: Record<string, unknown> | null;
  /** The key fields extracted + stored for a document response (the "JSON in the DB"). */
  extracted?: Record<string, unknown> | null;
}

/**
 * Records every request and mirrors it to the browser console in a clean, grouped,
 * monospace format (no emoji) — the "inspect" experience for a developer, plus a
 * live feed for the in-app Developer drawer. Bounded to the last 100 calls.
 *
 * Nothing sensitive is stored or printed: method, path, status, timing, request-id,
 * and the notice/extraction trail only — never headers or tokens.
 */
@Injectable({ providedIn: 'root' })
export class DevLogService {
  private static readonly CAPACITY = 100;
  /** Soft daily token budget shown in the gauge. Cloudflare's free tier is billed in
   *  neurons, not tokens, so this is a display budget to visualise consumption, not a
   *  hard limit. Tune to taste. */
  readonly tokenBudget = 100_000;

  private readonly _entries = signal<DevLogEntry[]>([]);
  private readonly _tokensToday = signal(0);

  readonly entries = this._entries.asReadonly();
  /** AI tokens observed on this device today (accumulates across reloads, resets daily). */
  readonly tokensToday = this._tokensToday.asReadonly();

  constructor() {
    this._tokensToday.set(Number(localStorage.getItem(this.tokenKey()) ?? 0));
  }

  add(entry: DevLogEntry): void {
    this._entries.update((list) => [entry, ...list].slice(0, DevLogService.CAPACITY));
    this.accrueTokens(entry);
    this.toConsole(entry);
  }

  clear(): void {
    this._entries.set([]);
  }

  private tokenKey(): string {
    return 'trove.tokens.' + new Date().toISOString().slice(0, 10);
  }

  /** Sum an entry's AI tokens and roll them into today's running total. */
  private accrueTokens(entry: DevLogEntry): void {
    const attempts = entry.extractionMeta?.['attempts'];
    if (!Array.isArray(attempts)) return;
    let tok = 0;
    for (const a of attempts) {
      const t = (a as Record<string, unknown>)['tokens'];
      if (typeof t === 'number') tok += t;
    }
    if (tok <= 0) return;
    const total = this._tokensToday() + tok;
    this._tokensToday.set(total);
    localStorage.setItem(this.tokenKey(), String(total));
  }

  /** Grouped, styled console output — legible at a glance, expandable for detail. */
  private toConsole(e: DevLogEntry): void {
    const ok = e.status >= 200 && e.status < 300;
    const statusColor = e.status === 0 ? '#c0392b' : ok ? '#2e7d5b' : '#b8860b';
    const path = e.url.replace(/^https?:\/\/[^/]+/, '');

    console.groupCollapsed(
      `%cTrove%c ${e.method} ${path} %c${e.status || 'ERR'}%c ${e.durationMs}ms`,
      'background:#2f6f6a;color:#fff;padding:1px 6px;border-radius:4px;font-weight:600',
      'color:inherit;font-weight:600',
      `color:${statusColor};font-weight:700`,
      'color:#8a8a8a',
    );
    if (e.requestId) {
      console.log('%crequest-id%c %s', 'color:#8a8a8a', 'font-family:monospace', e.requestId);
    }
    if (e.notice) {
      console.log(
        '%c%s%c %s',
        `color:${statusColor};font-weight:700`,
        e.notice.code,
        'color:inherit',
        e.notice.userMessage,
      );
      if (e.notice.devNote) {
        console.log('%cdev%c %s', 'color:#8a8a8a', 'color:inherit', e.notice.devNote);
      }
    }
    const attempts = e.extractionMeta?.['attempts'];
    if (Array.isArray(attempts) && attempts.length) {
      console.table(attempts);
    }
    console.groupEnd();
  }
}
