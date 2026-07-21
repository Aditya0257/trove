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
          <button class="link" (click)="log.clear()">Clear</button>
          <button class="link" (click)="open.set(false)">Close</button>
        </header>

        @if (!entries().length) {
          <p class="empty">No requests yet.</p>
        }

        @for (e of entries(); track e.at) {
          <details class="entry">
            <summary>
              <span class="method">{{ e.method }}</span>
              <span class="path">{{ path(e) }}</span>
              <span class="status" [attr.data-ok]="ok(e)">{{ e.status || 'ERR' }}</span>
              <span class="ms">{{ e.durationMs }}ms</span>
            </summary>
            <div class="detail">
              @if (e.requestId) { <div class="kv"><span>req</span><code>{{ e.requestId }}</code></div> }
              @if (e.notice) {
                <div class="kv"><span>notice</span><code>{{ e.notice.code }} · {{ e.notice.level }}</code></div>
                <div class="kv"><span>user</span><div>{{ e.notice.userMessage }}</div></div>
                @if (e.notice.devNote) { <div class="kv"><span>dev</span><div>{{ e.notice.devNote }}</div></div> }
              }
              @if (attempts(e).length) {
                <div class="trail-title">extraction chain</div>
                @for (a of attempts(e); track $index) {
                  <div class="trail">{{ a['provider'] }} · {{ a['status'] }}<!--
                    -->{{ a['confidencePct'] != null ? ' · ' + a['confidencePct'] + '%' : '' }} · {{ a['latencyMs'] }}ms</div>
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
      .link { border: 0; background: transparent; color: #2f6f6a; cursor: pointer; font-size: 13px; }
      .empty { color: #8a8a8a; }
      .entry { border-top: 1px solid rgba(0, 0, 0, 0.08); padding: 6px 0; }
      summary { display: flex; align-items: center; gap: 8px; cursor: pointer; font-family: monospace; font-size: 13px; }
      summary::-webkit-details-marker { display: none; }
      .method { font-weight: 700; color: #2f6f6a; }
      .path { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
      .status { font-weight: 700; color: #b8860b; }
      .status[data-ok='true'] { color: #2e7d5b; }
      .ms { color: #8a8a8a; }
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

  protected attempts(e: DevLogEntry): Record<string, unknown>[] {
    const a = e.extractionMeta?.['attempts'];
    return Array.isArray(a) ? (a as Record<string, unknown>[]) : [];
  }
}
