import { Component, ElementRef, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { SpaceContext } from '../../core/space.context';
import { NoticeService } from '../../core/notice/notice.service';
import { MoneyPipe } from '../../core/money.pipe';
import { ChatAnswer } from '../../core/models';

interface Turn {
  question: string;
  answer: ChatAnswer;
}

/**
 * Floating "Ask your vault" assistant — a launcher pinned bottom-right that opens a chat
 * panel over any page. Grounded RAG answers with citations back to source documents.
 * Mounted once in the app shell (like the notice toast + dev drawer).
 */
@Component({
  selector: 'trove-assistant',
  imports: [FormsModule, RouterLink, MoneyPipe],
  template: `
    @if (auth.isLoggedIn()) {
      @if (open()) {
        <div class="panel">
          <header>
            <span class="title">✦ Ask your vault</span>
            <button type="button" class="icon" title="How this works" [class.on]="helpOpen()" (click)="helpOpen.set(!helpOpen())">ⓘ</button>
            <button type="button" class="icon" title="Re-index documents" (click)="reindex()" [disabled]="indexing()">⟳</button>
            <button type="button" class="icon" title="Close" (click)="open.set(false)">✕</button>
          </header>

          <div class="body" #body>
            @if (helpOpen()) {
              <div class="help">
                <p class="help-user">{{ helpUser }}</p>
                <div class="help-dev">
                  <span class="help-dev-label">How it works</span>
                  <p>{{ helpDev }}</p>
                </div>
              </div>
            }
            @if (turns().length === 0) {
              <p class="hint">Ask about your documents — answers are built from your files and cite them.</p>
              <div class="examples">
                @for (ex of examples; track ex) {
                  <button type="button" class="chip" (click)="ask(ex)" [disabled]="loading()">{{ ex }}</button>
                }
              </div>
            }
            @for (t of turns(); track $index) {
              <div class="msg q">{{ t.question }}</div>
              <div class="msg a">
                <p class="a-text">{{ t.answer.answer }}</p>
                @if (!t.answer.aiUsed && t.answer.sources.length) {
                  <p class="muted xs">Most relevant documents (AI summary paused or off).</p>
                }
                @if (t.answer.sources.length) {
                  <div class="sources">
                    @for (s of t.answer.sources; track s.documentId) {
                      <a class="source" [routerLink]="['/documents', s.documentId, 'review']" (click)="open.set(false)">
                        <span class="src-idx">[{{ s.index }}]</span>
                        <span class="src-title">{{ s.title }}</span>
                        @if (s.category) { <span class="src-meta">{{ s.category }}</span> }
                        @if (s.docDate) { <span class="src-meta">{{ s.docDate }}</span> }
                        @if (s.amount != null) { <span class="src-meta">{{ s.amount | money: s.currency }}</span> }
                      </a>
                    }
                  </div>
                }
              </div>
            }
            @if (loading()) { <div class="msg a"><p class="a-text muted">Thinking…</p></div> }
            @if (error()) { <p class="error">{{ error() }}</p> }
          </div>

          <form class="composer" (submit)="ask(); $event.preventDefault()">
            <input [(ngModel)]="q" name="q" autocomplete="off"
                   placeholder="Ask anything about your vault…" [disabled]="loading()" />
            <button type="submit" [disabled]="loading() || !q.trim()">Ask</button>
          </form>
        </div>
      }

      <button type="button" class="launcher" [class.hidden]="open()" (click)="open.set(true)" aria-label="Ask your vault">
        ✦ Ask
      </button>
    }
  `,
  styles: [
    `
      .launcher {
        position: fixed; bottom: 16px; right: 16px; z-index: 900; margin: 0;
        border: 0; border-radius: 999px; padding: 10px 18px; cursor: pointer;
        background: var(--brand); color: var(--brand-ink); font-weight: 600; font-size: 14px;
        box-shadow: 0 6px 20px var(--shadow);
      }
      .launcher:hover { filter: brightness(1.05); }
      .launcher.hidden { display: none; }

      .panel {
        position: fixed; bottom: 16px; right: 16px; z-index: 951;
        width: min(400px, 94vw); height: min(560px, 76vh);
        display: flex; flex-direction: column; overflow: hidden;
        background: var(--card); border: 1px solid var(--line); border-radius: 14px;
        box-shadow: 0 20px 60px var(--shadow);
      }
      header {
        display: flex; align-items: center; gap: 8px; padding: 10px 12px;
        border-bottom: 1px solid var(--line);
      }
      header .title { flex: 1; font-weight: 700; font-size: 14px; }
      header .icon {
        margin: 0; padding: 3px 8px; background: transparent; border: 1px solid var(--line);
        border-radius: 8px; color: var(--muted); cursor: pointer; font-size: 13px; line-height: 1;
      }
      header .icon:hover:not(:disabled) { background: var(--hover); color: var(--ink); }
      header .icon.on { background: var(--accent); color: var(--brand-ink); border-color: var(--accent); }

      .body { flex: 1; overflow-y: auto; padding: 12px; }
      /* Compact in-panel help — the floating widget's own take, lighter than the page help card. */
      .help {
        border: 1px solid var(--accent-line); background: var(--accent-soft);
        border-radius: 10px; padding: 10px 11px; margin-bottom: 10px;
      }
      .help-user { margin: 0; font-size: 11.5px; line-height: 1.5; color: var(--ink); }
      .help-dev { margin-top: 7px; border-top: 1px dashed var(--accent-line); padding-top: 7px; }
      .help-dev-label {
        font-size: 9.5px; font-weight: 700; letter-spacing: 0.04em; text-transform: uppercase; color: var(--muted);
      }
      .help-dev p { margin: 2px 0 0; font-size: 10.5px; line-height: 1.5; color: var(--muted); }
      .hint { margin: 0 0 10px; color: var(--muted); font-size: 13px; }
      .examples { display: flex; flex-wrap: wrap; gap: 6px; }
      .chip {
        border: 1px solid var(--accent-line); background: transparent; color: var(--accent);
        border-radius: 999px; padding: 4px 10px; font-size: 12px; cursor: pointer;
      }
      .chip:hover:not(:disabled) { background: var(--accent-soft); }

      .msg { margin: 7px 0; padding: 7px 10px; border-radius: 12px; font-size: 12px; line-height: 1.5; }
      .msg.q { background: var(--accent-soft); color: var(--ink); margin-left: 24px; }
      .msg.a { background: var(--hover); }
      .a-text { margin: 0; white-space: pre-wrap; }
      .xs { font-size: 10.5px; margin: 6px 0 0; }
      .sources { display: flex; flex-direction: column; gap: 5px; margin-top: 8px; }
      .source {
        display: flex; align-items: baseline; gap: 6px; flex-wrap: wrap; text-decoration: none;
        border: 1px solid var(--line); border-radius: 8px; padding: 5px 8px; color: var(--ink); background: var(--card);
      }
      .source:hover { border-color: var(--accent-line); }
      .src-idx { color: var(--accent); font-weight: 700; font-size: 10.5px; }
      .src-title { font-weight: 600; font-size: 11.5px; }
      .src-meta { font-size: 10px; color: var(--muted); background: var(--accent-soft); border-radius: 6px; padding: 1px 6px; }

      .composer { display: flex; gap: 6px; padding: 10px; border-top: 1px solid var(--line); }
      .composer input { flex: 1; margin: 0; }
      .composer button { margin: 0; flex: none; }
      .error { color: var(--danger); font-size: 13px; }
    `,
  ],
})
export class AssistantWidget {
  private api = inject(ApiService);
  protected auth = inject(AuthService);
  private spaceCtx = inject(SpaceContext);
  private notices = inject(NoticeService);

  private bodyRef = viewChild<ElementRef<HTMLElement>>('body');

  protected open = signal(false);
  protected helpOpen = signal(false);
  protected q = '';

  protected helpUser =
    "Ask about your documents in plain English — \"when does my insurance renew?\", \"my last water bill\", " +
    '"the fridge warranty". Answers are built only from your own files and cite the exact documents used, so ' +
    "you can check them. If something isn't in your vault, it says so instead of guessing. It searches the " +
    'space you\'re currently in.';
  protected helpDev =
    'Retrieval-augmented generation. Each document is embedded (Cloudflare bge-base, 768-dim) into pgvector on ' +
    'Postgres; your question is embedded and matched by cosine similarity within the current space, then an LLM ' +
    'writes a grounded, cited answer from only those documents. A tiny 1b classifier routes each question: simple ' +
    'lookups go to a cheap model (llama-3.2-3b, ~3.6x cheaper), reasoning/comparison questions to a stronger one ' +
    '(llama-3.1-8b). When the SHARED daily budget crosses 75% it drops everything to the light model so the free ' +
    'tier stretches across more users, and it degrades to retrieval-only (sources, no written answer) once the ' +
    'budget is fully spent.';
  protected loading = signal(false);
  protected indexing = signal(false);
  protected error = signal<string | null>(null);
  protected turns = signal<Turn[]>([]);

  protected examples = [
    'when does my passport expire?',
    'my last electricity bill?',
    'find my fridge warranty',
  ];

  ask(text?: string): void {
    const question = (text ?? this.q).trim();
    if (!question || this.loading()) return;
    this.loading.set(true);
    this.error.set(null);
    this.q = '';
    this.scrollSoon();
    this.api.chatAsk(question, this.spaceCtx.currentSpaceId()).subscribe({
      next: (answer) => {
        this.turns.update((list) => [...list, { question, answer }]);
        this.loading.set(false);
        this.scrollSoon();
      },
      error: (e) => {
        this.error.set(e?.error?.message ?? 'Could not answer that. Please try again.');
        this.loading.set(false);
      },
    });
  }

  reindex(): void {
    this.indexing.set(true);
    this.api.chatReindex(this.spaceCtx.currentSpaceId()).subscribe({
      next: (r) => {
        this.notices.show({
          level: 'success', code: 'REINDEXED',
          userMessage: r.indexed > 0 ? `Indexed ${r.indexed} document(s) — ask away.` : 'Everything is already indexed.',
        });
        this.indexing.set(false);
      },
      error: (e) => {
        this.notices.show({ level: 'error', code: 'REINDEX_FAIL', userMessage: e?.error?.message ?? 'Could not index.' });
        this.indexing.set(false);
      },
    });
  }

  /** Scroll the message list to the newest entry after the view updates. */
  private scrollSoon(): void {
    setTimeout(() => {
      const el = this.bodyRef()?.nativeElement;
      if (el) el.scrollTop = el.scrollHeight;
    }, 0);
  }
}
