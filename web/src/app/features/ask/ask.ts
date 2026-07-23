import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { SpaceContext } from '../../core/space.context';
import { NoticeService } from '../../core/notice/notice.service';
import { MoneyPipe } from '../../core/money.pipe';
import { HelpCard } from '../../core/help-card';
import { ChatAnswer } from '../../core/models';

interface Turn {
  question: string;
  answer: ChatAnswer;
}

/**
 * "Ask your vault" — grounded RAG chat over the caller's documents. Each answer is built
 * only from retrieved documents and links back to them, so nothing is unverifiable.
 */
@Component({
  selector: 'app-ask',
  imports: [FormsModule, RouterLink, MoneyPipe, HelpCard],
  template: `
    <div class="card">
      <div class="row-between">
        <h1>Ask your vault</h1>
        <button type="button" class="btn-ghost" (click)="reindex()" [disabled]="indexing()">
          {{ indexing() ? 'Indexing…' : 'Index documents' }}
        </button>
      </div>
      <trove-help-card title="About Ask" [open]="false" [user]="helpUser" [dev]="helpDev"></trove-help-card>

      <p class="muted">Ask about your documents in plain English. Answers are grounded in your files and cite them.</p>
      <div class="examples">
        @for (ex of examples; track ex) {
          <button type="button" class="chip" (click)="ask(ex)" [disabled]="loading()">{{ ex }}</button>
        }
      </div>

      <form (submit)="ask(); $event.preventDefault()">
        <div class="row">
          <input [(ngModel)]="q" name="q" autocomplete="off"
                 placeholder="e.g. when does my passport expire?" [disabled]="loading()" />
          <button type="submit" [disabled]="loading() || !q.trim()">{{ loading() ? 'Thinking…' : 'Ask' }}</button>
        </div>
      </form>

      @if (error()) { <p class="error">{{ error() }}</p> }

      @for (t of turns(); track $index) {
        <div class="turn">
          <p class="q">{{ t.question }}</p>
          <p class="a">{{ t.answer.answer }}</p>
          @if (!t.answer.aiUsed && t.answer.sources.length) {
            <p class="muted small">Showing the most relevant documents (AI summary paused or off).</p>
          }
          @if (t.answer.sources.length) {
            <div class="sources">
              <p class="src-head muted small">Sources</p>
              @for (s of t.answer.sources; track s.documentId) {
                <a class="source" [routerLink]="['/documents', s.documentId, 'review']">
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
    </div>
  `,
  styles: [
    `
      .btn-ghost {
        margin: 0; border: 1px solid var(--line); background: transparent; color: var(--muted);
        border-radius: 8px; padding: 6px 12px; font-size: 13px; font-weight: 600; cursor: pointer;
      }
      .btn-ghost:hover:not(:disabled) { background: var(--hover); color: var(--accent); }
      .examples { display: flex; flex-wrap: wrap; gap: 8px; margin: 10px 0 14px; }
      .chip {
        border: 1px solid var(--accent-line); background: transparent; color: var(--accent);
        border-radius: 999px; padding: 5px 12px; font-size: 13px; cursor: pointer;
      }
      .chip:hover:not(:disabled) { background: var(--accent-soft); }
      .row { display: flex; gap: 8px; }
      .row input { flex: 1; margin: 0; }
      .row button { margin: 0; flex: none; }
      .turn { border-top: 1px solid var(--line); margin-top: 16px; padding-top: 14px; }
      .turn .q { font-weight: 600; margin: 0 0 6px; }
      .turn .a { margin: 0 0 10px; white-space: pre-wrap; line-height: 1.55; }
      .small { font-size: 12px; }
      .sources { display: flex; flex-direction: column; gap: 6px; }
      .src-head { margin: 4px 0 2px; text-transform: uppercase; letter-spacing: 0.04em; }
      .source {
        display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; text-decoration: none;
        border: 1px solid var(--line); border-radius: 8px; padding: 7px 10px; color: var(--ink);
      }
      .source:hover { background: var(--hover); border-color: var(--accent-line); }
      .src-idx { color: var(--accent); font-weight: 700; font-size: 12px; }
      .src-title { font-weight: 600; }
      .src-meta {
        font-size: 12px; color: var(--muted); background: var(--accent-soft);
        border-radius: 6px; padding: 1px 8px;
      }
    `,
  ],
})
export class Ask {
  private api = inject(ApiService);
  private spaceCtx = inject(SpaceContext);
  private notices = inject(NoticeService);

  protected q = '';
  protected loading = signal(false);
  protected indexing = signal(false);
  protected error = signal<string | null>(null);
  protected turns = signal<Turn[]>([]);

  protected examples = [
    'when does my passport expire?',
    'how much was my last electricity bill?',
    'find my fridge warranty',
    'what did I spend on shopping?',
  ];

  protected helpUser =
    'Ask questions in plain English about your own documents — "when does my insurance renew?", ' +
    '"my last water bill", "the fridge warranty". Every answer is built only from your files and links ' +
    'back to the exact documents it used, so you can check it. If something is not in your vault, it says so ' +
    'rather than guessing.';
  protected helpDev =
    'Retrieval-augmented generation. Each document is embedded (Cloudflare bge-base, 768-dim) into pgvector ' +
    'on Postgres; your question is embedded and matched by cosine similarity within the current space, then a ' +
    'small LLM (llama-3.1-8b) writes a grounded, cited answer from only those documents. It bills through the ' +
    'shared daily AI budget and degrades to retrieval-only when that is spent, so it stays inside the free tier.';

  ask(text?: string): void {
    const question = (text ?? this.q).trim();
    if (!question || this.loading()) return;
    this.loading.set(true);
    this.error.set(null);
    this.api.chatAsk(question, this.spaceCtx.currentSpaceId()).subscribe({
      next: (answer) => {
        this.turns.update((list) => [{ question, answer }, ...list]);
        this.q = '';
        this.loading.set(false);
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
}
