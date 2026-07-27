import { Component, ElementRef, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { AuthService } from '../../core/services/auth.service';
import { SpaceContext } from '../../core/services/space.context';
import { NoticeService } from '../../core/services/notice.service';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { ChatAnswer, ChatCitation } from '../../core/models/models';

interface Turn {
  question: string;
  answer: ChatAnswer;
}


/**
 * Floating "Ask your vault" assistant - a launcher pinned bottom-right that opens a chat
 * panel over any page. Grounded RAG answers with citations back to source documents.
 * Mounted once in the app shell (like the notice toast + dev drawer).
 */
@Component({
  selector: 'trove-assistant',
  imports: [FormsModule, RouterLink, MoneyPipe],
  templateUrl: './assistant.html',
  styleUrl: './assistant.scss',
})
export class AssistantWidget {
  private api = inject(ApiService);
  protected auth = inject(AuthService);
  private spaceCtx = inject(SpaceContext);
  private notices = inject(NoticeService);

  private bodyRef = viewChild<ElementRef<HTMLElement>>('body');

  protected open = signal(false);
  protected helpOpen = signal(false);
  private expanded = signal<Set<number>>(new Set());
  protected q = '';

  protected helpUser =
    "Ask about your documents in plain English - \"when does my insurance renew?\", \"my last water bill\", " +
    '"the fridge warranty". Answers are built only from your own files and cite the exact documents used, so ' +
    "you can check them. If something isn't in your vault, it says so instead of guessing. It searches the " +
    "space you're currently in. If you just added or edited a document and it is not turning up in answers " +
    "yet, press the refresh button (top right) to re-read and re-index anything new.";
  protected helpDev =
    'Retrieval-augmented generation. Each document is embedded (Cloudflare bge-base, 768-dim) into pgvector on ' +
    'Postgres; your question is embedded and matched by cosine similarity within the current space, then an LLM ' +
    'writes a grounded, cited answer from only those documents. A tiny 1b classifier routes each question: simple ' +
    'lookups go to a cheap model (llama-3.2-3b, ~3.6x cheaper), reasoning/comparison questions to a stronger one ' +
    '(llama-3.1-8b). When the SHARED daily budget crosses 75% it drops everything to the light model so the free ' +
    'tier stretches across more users, and it degrades to retrieval-only (sources, no written answer) once the ' +
    'budget is fully spent. The refresh (re-index) button embeds any documents missing a current-model vector on ' +
    'demand; normally confirm-time indexing plus an hourly sweep keep the index current, so you rarely need it.';
  protected loading = signal(false);
  protected indexing = signal(false);
  protected error = signal<string | null>(null);
  protected turns = signal<Turn[]>([]);

  protected examples = [
    'when does my passport expire?',
    'my last electricity bill?',
    'find my fridge warranty',
  ];

  /** Which document indices the answer actually cites (a set of [n] found in the text). */
  private citedIndices(answer: string): Set<number> {
    const cited = new Set<number>();
    for (const m of answer.matchAll(/\[(\d+)\]/g)) {
      cited.add(Number(m[1]));
    }
    return cited;
  }

  /** The answer as clean prose: citation markers stripped, spacing tidied. The sources
   *  are shown as cards below, so the sentence itself stays talkable and marker-free. */
  cleanAnswer(answer: string): string {
    return answer
      .replace(/\s*\[\d+\]/g, '')        // drop [1], [2] markers (and any leading space)
      .replace(/\s+([.,;:!?])/g, '$1')   // fix space left before punctuation
      .replace(/[ \t]{2,}/g, ' ')        // collapse runs of spaces
      .trim();
  }

  citedSources(t: Turn): ChatCitation[] {
    const cited = this.citedIndices(t.answer.answer);
    return t.answer.sources.filter((s) => cited.has(s.index));
  }
  otherSources(t: Turn): ChatCitation[] {
    const cited = this.citedIndices(t.answer.answer);
    return t.answer.sources.filter((s) => !cited.has(s.index));
  }

  toggleOthers(turnIndex: number): void {
    this.expanded.update((set) => {
      const next = new Set(set);
      next.has(turnIndex) ? next.delete(turnIndex) : next.add(turnIndex);
      return next;
    });
  }
  isExpanded(turnIndex: number): boolean {
    return this.expanded().has(turnIndex);
  }

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
          userMessage: r.indexed > 0 ? `Indexed ${r.indexed} document(s) - ask away.` : 'Everything is already indexed.',
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
