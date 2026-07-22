import { Component, inject, signal } from '@angular/core';
import { DevLogService, DevLogEntry } from './dev-log.service';

/**
 * The in-app "inspect" surface: a slide-over listing recent API calls with method,
 * path, status, client round-trip, the server request-id, the notice, and — for
 * document reads — the extraction chain trail. Complements the styled console for
 * when the console isn't handy (e.g. on a phone-sized browser). Toggled by a small
 * corner pill. Mounted once at the app root.
 */
@Component({
  selector: 'trove-dev-drawer',
  standalone: true,
  template: `
    <button class="pill" (click)="open.set(!open())" title="Developer">
      dev<span class="count">{{ entries().length }}</span>
    </button>

    @if (open()) {
      <div class="scrim" (click)="open.set(false)"></div>
      <aside class="panel">
        <header>
          <strong>Developer</strong>
          <span class="sub">request trail</span>
          <span class="grow"></span>
          <button class="link" [class.active]="errorsOnly()" (click)="errorsOnly.set(!errorsOnly())">
            {{ errorsOnly() ? 'Show all' : 'Errors only' }}
          </button>
          <button class="link" (click)="log.clear()">Clear</button>
          <button class="link" (click)="open.set(false)">Close</button>
        </header>

        @if (!shown().length) {
          <p class="empty">{{ errorsOnly() ? 'No errors — all good.' : 'No requests yet.' }}</p>
        }

        @for (e of shown(); track e.at) {
          <details class="entry" [class.err]="!ok(e)">
            <summary>
              <div class="line1">
                <span class="time">{{ time(e.at) }}</span>
                <span class="method">{{ e.method }}</span>
                <span class="grow"></span>
                @if (fellBack(e)) { <span class="fallback">fell back</span> }
                <span class="status" [attr.data-ok]="ok(e)">{{ e.status || 'ERR' }}</span>
                <span class="ms">{{ e.durationMs }}ms</span>
              </div>
              <div class="line2">
                <span class="desc">{{ meaning(e).label }}</span>
                <span class="path">{{ path(e) }}</span>
              </div>
            </summary>
            <div class="detail">
              <div class="kv"><span>you</span><div>{{ meaning(e).user }}</div></div>
              <div class="kv"><span>dev</span><div>{{ meaning(e).dev }}</div></div>
              <div class="kv"><span>biz</span><div>{{ meaning(e).business }}</div></div>
              @if (e.requestId) { <div class="kv"><span>req</span><code>{{ e.requestId }}</code></div> }
              @if (e.notice) {
                <div class="kv"><span>notice</span><code>{{ e.notice.code }} · {{ e.notice.level }}</code></div>
                @if (e.notice.devNote) { <div class="kv"><span>why</span><div>{{ e.notice.devNote }}</div></div> }
              }
              @if (attempts(e).length) {
                <div class="trail-title">extraction chain</div>
                @for (a of attempts(e); track $index) {
                  <div class="trail" [attr.data-status]="a['status']">{{ a['provider'] }} · {{ a['status'] }}<!--
                    -->{{ a['confidencePct'] != null ? ' · ' + a['confidencePct'] + '%' : '' }} · {{ a['latencyMs'] }}ms<!--
                    -->{{ a['reason'] ? ' — ' + a['reason'] : '' }}</div>
                }
              }
            </div>
          </details>
        }
      </aside>
    }
  `,
  styles: [
    `
      .pill {
        position: fixed; bottom: 16px; right: 16px; z-index: 900;
        background: #2f6f6a; color: #fff; border: 0; border-radius: 999px;
        padding: 8px 14px; font: 600 12px/1 monospace; cursor: pointer;
        box-shadow: 0 4px 14px rgba(0, 0, 0, 0.25);
      }
      .count {
        margin-left: 6px; background: rgba(255, 255, 255, 0.22);
        border-radius: 999px; padding: 1px 6px;
      }
      .scrim { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.28); z-index: 950; }
      .panel {
        position: fixed; top: 0; right: 0; bottom: 0; z-index: 951;
        width: min(560px, 96vw); background: var(--surface, #fff); color: var(--text, #1a1a1a);
        box-shadow: -8px 0 28px rgba(0, 0, 0, 0.2); overflow-y: auto; padding: 12px 14px;
      }
      header { display: flex; align-items: baseline; gap: 8px; margin-bottom: 8px; }
      header .sub { color: #8a8a8a; font-size: 13px; }
      header .grow { flex: 1; }
      .link { border: 0; background: transparent; color: #2f6f6a; cursor: pointer; font-size: 13px; padding: 2px 6px; }
      .link.active { background: rgba(192, 57, 43, 0.12); color: #c0392b; border-radius: 6px; font-weight: 600; }
      .empty { color: #8a8a8a; padding: 8px 0; }
      .entry.err { background: rgba(192, 57, 43, 0.05); }
      .fallback {
        background: rgba(184, 134, 11, 0.16); color: #8a5a00; border-radius: 5px;
        padding: 1px 7px; font-size: 11px; font-weight: 700; font-family: monospace;
      }
      .trail[data-status='ACCEPTED'] { color: #2e7d5b; }
      .trail[data-status='QUOTA'], .trail[data-status='TRANSIENT'], .trail[data-status='ERROR'] { color: #b8860b; }
      .entry { border-top: 1px solid rgba(0, 0, 0, 0.08); padding: 12px 2px; }
      summary { display: flex; flex-direction: column; gap: 4px; cursor: pointer; list-style: none; }
      summary::-webkit-details-marker { display: none; }
      .line1 { display: flex; align-items: center; gap: 10px; font-family: monospace; font-size: 13px; }
      .line2 { display: flex; align-items: baseline; gap: 10px; }
      .time { color: #8a8a8a; font-family: monospace; font-size: 12px; }
      .method { font-weight: 700; color: #2f6f6a; }
      .desc { font-weight: 600; font-size: 13px; }
      .path {
        flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
        color: #8a8a8a; font-family: monospace; font-size: 12px;
      }
      .status { font-weight: 700; color: #b8860b; font-family: monospace; font-size: 13px; }
      .status[data-ok='true'] { color: #2e7d5b; }
      .ms { color: #8a8a8a; font-family: monospace; font-size: 12px; }
      .detail { padding: 6px 0 4px 4px; font-size: 12px; }
      .kv { display: flex; gap: 8px; margin: 2px 0; }
      .kv > span:first-child { width: 46px; color: #8a8a8a; font-weight: 700; }
      .kv code { font-family: monospace; }
      .trail-title { color: #8a8a8a; font-weight: 700; margin: 6px 0 2px; }
      .trail { font-family: monospace; font-size: 12px; }
    `,
  ],
})
export class DevDrawer {
  protected log = inject(DevLogService);
  protected entries = this.log.entries;
  protected open = signal(false);

  protected path = (e: DevLogEntry) => e.url.replace(/^https?:\/\/[^/]+/, '');
  protected ok = (e: DevLogEntry) => e.status >= 200 && e.status < 300;

  /** Local wall-clock (24-hour) — shows in the viewer's timezone, e.g. IST. */
  protected time = (at: number) =>
    new Date(at).toLocaleTimeString('en-GB', { hour12: false });

  /**
   * Three-lens meaning for a call — what it means to the user, the developer, and the
   * business. Short by design; the goal is to read the drawer and understand the flow.
   */
  protected meaning(e: DevLogEntry): { label: string; user: string; dev: string; business: string } {
    const p = this.path(e).split('?')[0];
    const m = e.method.toUpperCase();
    const M = (label: string, user: string, dev: string, business: string) => ({ label, user, dev, business });

    if (p === '/api/auth/login') return M('Sign in', 'Signing you in', 'verify credentials → mint a JWT', 'gate to a private vault');
    if (p === '/api/auth/register') return M('Create account', 'Creating your account', 'create user + provision a personal space', 'a new owner joins');
    if (p === '/api/spaces') return M('Your spaces', 'Loading your spaces', 'personal + shared spaces you belong to', 'who can see which documents');
    if (p === '/api/categories') return M('Categories', 'Loading categories', 'global + space category taxonomy', 'how the vault is organised');
    if (p === '/api/search') return M('Search', 'Finding your documents', 'NL query → LLM/rule parse → filtered query', 'plain-English retrieval');
    if (p === '/api/documents' && m === 'POST') return M('Upload a document', 'Saving your document', 'multipart → object storage + sidecar JSON; async extraction queued', 'an item enters the source-of-truth vault');
    if (p === '/api/documents' && m === 'GET') return M('List documents', 'Loading your documents', 'reads the rebuildable DB index', 'browse the vault');
    if (/^\/api\/documents\/[^/]+\/confirm$/.test(p)) return M('Confirm a document', 'Saving your reviewed details', 'human-review → status=confirmed; fires reminders + anomaly check', 'nothing is trusted until a human confirms');
    if (/^\/api\/documents\/[^/]+\/content$/.test(p)) return M('Open a vital file', 'Opening your file', 'decrypt-stream the encrypted bytes (no presigned URL)', 'sensitive PII stays encrypted at rest');
    if (/^\/api\/documents\/[^/]+$/.test(p)) return M('Fetch a document', 'Loading a document', 'reads the index row + a presigned view URL', 'reads the rebuildable index');
    if (p === '/api/reminders' && m === 'GET') return M('Reminders', 'Loading reminders', 'pending reminders for the space, soonest first', 'never miss a due date / warranty');
    if (/^\/api\/reminders\/[^/]+\/dismiss$/.test(p)) return M('Dismiss reminder', 'Dismissing a reminder', 'mark reminder dismissed', 'user acknowledged it');
    if (p.startsWith('/api/spend')) return M('Spend analytics', 'Loading your spend', 'aggregate confirmed documents by category/month', 'understand where money goes');
    if (p.startsWith('/api/integrations/google-drive')) return M('Google Drive', 'Talking to Google Drive', 'per-owner OAuth backup / sync', 'human-navigable third copy of the data');
    return M('API request', 'Working…', `${m} ${p}`, '—');
  }

  /** Entries honoring the errors-only filter. */
  protected shown(): DevLogEntry[] {
    const all = this.entries();
    return this.errorsOnly() ? all.filter((e) => !this.ok(e)) : all;
  }

  protected errorsOnly = signal(false);

  protected attempts(e: DevLogEntry): Record<string, unknown>[] {
    const a = e.extractionMeta?.['attempts'];
    return Array.isArray(a) ? (a as Record<string, unknown>[]) : [];
  }

  /** True when this response's document fell back to the stub (AI read failed). */
  protected fellBack(e: DevLogEntry): boolean {
    return e.extractionMeta?.['fellBack'] === true;
  }
}
