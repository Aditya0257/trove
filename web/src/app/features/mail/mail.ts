import { Component, HostListener, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { SpaceContext } from '../../core/space.context';
import { NoticeService } from '../../core/notice/notice.service';
import { ConfirmRequest, DocumentResponse, MailBundleView } from '../../core/models';

/**
 * Mail - file the important emails you screenshot (tax paid, subscription renewed) so
 * you can actually find them later. Paste/drop the screenshots, tag them with which
 * account (personal/office), a subject and the email's date; Trove stores them under
 * the "email" category, grouped as one entry, and lists them by account/date.
 *
 * Reuses the whole document pipeline - each screenshot is a normal document with
 * category "email" and the mail metadata in `extra`; a shared `mailBundleId` groups
 * the screenshots of one email. Confirming right after upload is safe: the extractor
 * never overwrites a confirmed document.
 */
@Component({
  selector: 'app-mail',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="card">
      <div class="row-between">
        <h1>Mail</h1>
        <button type="button" class="button" (click)="showAdd.set(!showAdd())">
          {{ showAdd() ? 'Close' : '＋ Add email screenshots' }}
        </button>
      </div>
      <p class="muted">Screenshot the important bits of an email and keep them findable
        by account, subject and date.</p>

      @if (showAdd()) {
        <div class="add">
          <div
            class="dropzone"
            [class.drag]="dragging()"
            (dragover)="onDragOver($event)"
            (dragleave)="dragging.set(false)"
            (drop)="onDrop($event)"
          >
            <span class="hint">Paste ({{ pasteHint }}) or drop screenshots</span>
            <label class="filebtn">Choose files
              <input type="file" (change)="onPick($event)" accept="image/*" multiple hidden />
            </label>
          </div>

          @if (queue().length) {
            <div class="thumbs">
              @for (item of queue(); track item.url) {
                <div class="thumb">
                  <img [src]="item.url" alt="email screenshot" />
                  <button class="rm" (click)="remove(item)" [disabled]="saving()">×</button>
                </div>
              }
            </div>
          }

          <div class="row">
            <label>
              <span class="lbl">Account <span class="tip" tabindex="0">i<span class="bubble">{{ tips.account }}</span></span></span>
              <input name="account" [(ngModel)]="account" list="mailAccounts" placeholder="Personal / Office" />
              <datalist id="mailAccounts">
                <option value="Personal"></option>
                <option value="Office"></option>
                @for (a of knownAccounts(); track a) { <option [value]="a"></option> }
              </datalist>
            </label>
            <label>
              <span class="lbl">Email date <span class="tip" tabindex="0">i<span class="bubble">{{ tips.date }}</span></span></span>
              <input type="date" name="mdate" [(ngModel)]="emailDate" />
            </label>
          </div>
          <label>
            <span class="lbl">Email address (inbox) <span class="tip" tabindex="0">i<span class="bubble">{{ tips.address }}</span></span></span>
            <input name="address" type="email" [(ngModel)]="address" list="mailAddresses" placeholder="e.g. you@work.com" />
            <datalist id="mailAddresses">
              @for (a of knownAddresses(); track a) { <option [value]="a"></option> }
            </datalist>
          </label>
          <label>
            <span class="lbl">Topic / sender <span class="tip" tabindex="0">i<span class="bubble">{{ tips.topic }}</span></span></span>
            <input name="topic" [(ngModel)]="topic" list="mailTopics"
              placeholder="e.g. Plum Insurance, HDFC Bank, Amazon" />
            <datalist id="mailTopics">
              @for (t of knownTopics(); track t) { <option [value]="t"></option> }
            </datalist>
          </label>
          <label>
            <span class="lbl">Subject <span class="tip" tabindex="0">i<span class="bubble">{{ tips.subject }}</span></span></span>
            <input name="subject" [(ngModel)]="subject" placeholder="Copy the exact subject line from the email" />
          </label>
          <label>
            <span class="lbl">Notes / description (optional) <span class="tip" tabindex="0">i<span class="bubble">{{ tips.notes }}</span></span></span>
            <textarea name="desc" [(ngModel)]="description" rows="2"
              placeholder="Anything to remember or find this by later"></textarea>
          </label>
          <label class="checkbox">
            <input type="checkbox" name="vital" [(ngModel)]="vital" /> Sensitive: encrypt at rest
          </label>
          <label class="checkbox">
            <input type="checkbox" name="aiRead" [(ngModel)]="aiRead" />
            Also read the text with AI (optional - makes the email body searchable; uses AI credits)
          </label>

          @if (saving()) { <p class="muted">Reading &amp; filing {{ done() + 1 }} of {{ total() }}…</p> }
          <button type="button" (click)="save()" [disabled]="!canSave()">
            {{ saving() ? 'Saving…' : 'Save email' }}
          </button>
        </div>
      }

      @if (loading()) { <p class="muted">Loading…</p> }
      @else if (entries().length === 0) { <p class="muted">No emails filed yet.</p> }
      @else {
        <div class="entries">
          @for (e of entries(); track e.bundleId) {
            <a class="entry" [routerLink]="['/mail', e.bundleId]">
              <div class="entry-thumbs">
                @for (d of e.docs; track d.id) {
                  <img [src]="thumb(d)" alt="screenshot" />
                }
              </div>
              <div class="entry-meta">
                <b>{{ e.topic || e.subject || 'Email' }}</b>
                @if (e.topic && e.subject) { <span class="subj">{{ e.subject }}</span> }
                <span class="tag">{{ e.account || '-' }}</span>
                <span class="muted">{{ e.address ? e.address + ' · ' : '' }}{{ e.date || '' }} · {{ e.docs.length }} screenshot(s)</span>
              </div>
            </a>
          }
        </div>
        @if (totalPages() > 1) {
          <div class="pager">
            <button type="button" [disabled]="page() === 0" (click)="goToPage(page() - 1)">‹ Prev</button>
            <span>Page {{ page() + 1 }} of {{ totalPages() }}</span>
            <button type="button" [disabled]="page() >= totalPages() - 1" (click)="goToPage(page() + 1)">Next ›</button>
            <span class="muted total">{{ totalBundles() }} thread(s)</span>
          </div>
        }
      }
    </div>
  `,
  styles: [
    `
      .add { border: 1px solid var(--line); border-radius: 12px; padding: 14px; margin: 8px 0 16px; }
      .dropzone {
        display: flex; align-items: center; justify-content: space-between; gap: 12px;
        border: 2px dashed var(--accent-line); border-radius: 12px; padding: 18px; margin-bottom: 10px;
      }
      .dropzone.drag { border-color: var(--accent); background: var(--accent-soft); }
      .filebtn { cursor: pointer; background: var(--accent-soft); color: var(--accent); border-radius: 8px; padding: 8px 14px; font-weight: 600; }
      .thumbs { display: flex; flex-wrap: wrap; gap: 10px; margin: 10px 0; }
      .thumb { position: relative; width: 76px; height: 76px; }
      .thumb img { width: 76px; height: 76px; object-fit: cover; border-radius: 8px; border: 1px solid var(--line); }
      .thumb .rm {
        position: absolute; top: -7px; right: -7px; box-sizing: border-box; width: 20px; height: 20px;
        min-width: 0; padding: 0; display: inline-flex; align-items: center; justify-content: center;
        border: 2px solid #fff; border-radius: 50%; background: var(--danger); color: #fff; font-size: 12px;
        line-height: 1; cursor: pointer; appearance: none;
      }
      .entries { display: flex; flex-direction: column; gap: 12px; margin-top: 12px; }
      .entry {
        display: flex; gap: 14px; padding: 12px; border: 1px solid var(--line); border-radius: 12px;
        text-decoration: none; color: inherit; cursor: pointer; transition: background 120ms, border-color 120ms;
      }
      .entry:hover { background: var(--accent-soft); border-color: var(--accent-line); }
      .entry-thumbs { display: flex; gap: 6px; }
      .entry-thumbs img { width: 56px; height: 56px; object-fit: cover; border-radius: 6px; border: 1px solid var(--line); }
      .entry-meta { display: flex; flex-direction: column; gap: 4px; }
      .subj { color: var(--muted); font-size: 13px; }
      .tag { align-self: flex-start; background: var(--accent-soft); color: var(--accent); border-radius: 999px; padding: 2px 10px; font-size: 12px; }
      /* Salesforce-style field help: a round "i" that reveals a bubble on hover/focus. */
      .lbl { display: inline-flex; align-items: center; }
      .tip {
        display: inline-flex; align-items: center; justify-content: center;
        width: 16px; height: 16px; margin-left: 6px; border-radius: 50%;
        background: var(--tip-bg); color: var(--accent); font-size: 11px; font-weight: 700;
        font-style: normal; cursor: help; position: relative; outline: none;
      }
      .tip .bubble {
        visibility: hidden; opacity: 0; position: absolute; bottom: 150%; left: 50%;
        transform: translateX(-50%); width: 240px; background: #222; color: #fff;
        padding: 8px 10px; border-radius: 8px; font-size: 12px; font-weight: 400;
        line-height: 1.4; z-index: 20; transition: opacity 120ms;
        box-shadow: 0 6px 20px rgba(0, 0, 0, 0.25); pointer-events: none;
      }
      .tip:hover .bubble, .tip:focus .bubble { visibility: visible; opacity: 1; }
      textarea {
        display: block; width: 100%; box-sizing: border-box; resize: vertical;
        font-family: inherit; padding: 8px; margin-top: 2px;
      }
      .pager { display: flex; align-items: center; gap: 12px; margin-top: 16px; flex-wrap: wrap; }
      .pager button {
        margin: 0; border: 1px solid var(--line); background: var(--card); color: var(--accent);
        border-radius: 8px; padding: 5px 12px; cursor: pointer; font-size: 13px; font-weight: 600;
      }
      .pager button:hover:not(:disabled) { background: var(--accent-soft); }
      .pager button:disabled { opacity: 0.4; cursor: default; }
      .pager .total { margin-left: auto; font-size: 13px; }
    `,
  ],
})
export class Mail {
  private api = inject(ApiService);
  private spaceCtx = inject(SpaceContext);
  private notices = inject(NoticeService);

  readonly pasteHint = /mac|iphone|ipad/i.test(navigator.userAgent) ? '⌘V' : 'Ctrl+V';

  showAdd = signal(false);
  dragging = signal(false);
  queue = signal<{ file: File; url: string }[]>([]);
  account = '';
  address = '';
  topic = '';
  subject = '';
  emailDate = '';
  description = '';
  vital = false;
  aiRead = false; // email screenshots aren't read by AI unless the user opts in
  saving = signal(false);
  done = signal(0);
  total = signal(0);

  loading = signal(false);

  // The Mail list is paged server-side: `entries` holds one page of threads (already grouped
  // by the backend), `totalBundles` is the full thread count, and the known* facets come from
  // the server so the add-form autocomplete stays complete even though we only load a page.
  entries = signal<MailBundleView[]>([]);
  totalBundles = signal(0);
  page = signal(0);
  pageSize = signal(10);
  knownAccounts = signal<string[]>([]);
  knownTopics = signal<string[]>([]);
  knownAddresses = signal<string[]>([]);

  protected totalPages = computed(() =>
    Math.max(1, Math.ceil(this.totalBundles() / this.pageSize())),
  );

  /** Move to a page (clamped) and fetch it. */
  goToPage(p: number): void {
    this.page.set(Math.min(Math.max(0, p), this.totalPages() - 1));
    this.load();
  }

  /** Field help - shown on hover/focus of the info icon (Salesforce-style). */
  readonly tips = {
    account: 'A short label to group your inboxes, like Personal or Office.',
    address: "The email address whose inbox this is in, e.g. you@work.com, so you know exactly which inbox to open and search later.",
    topic: 'The stable thing this is about (e.g. Plum Insurance). Groups emails together even when their subject lines change over time.',
    subject: "The exact subject line, copied as-is. Prefer the exact text, so you can paste it straight into that inbox's search to find the original email later.",
    date: 'The date the email arrived (as shown in your inbox).',
    notes: 'Anything extra you want to remember or find this by later, in your own words.',
  };

  constructor() {
    effect(() => {
      this.spaceCtx.currentSpaceId(); // re-run on space change
      this.page.set(0);               // a new space starts at the first page
      this.load();
    });
  }

  canSave(): boolean {
    return this.queue().length > 0 && !this.saving();
  }

  thumb(d: DocumentResponse): string {
    return this.api.fileUrl(d) ?? '';
  }

  // --- intake ------------------------------------------------------------

  @HostListener('document:paste', ['$event'])
  onPaste(e: ClipboardEvent): void {
    if (!this.showAdd()) return;
    const imgs = imagesFrom(e.clipboardData?.items);
    if (imgs.length) {
      e.preventDefault();
      this.add(imgs);
    }
  }

  onDragOver(e: DragEvent): void {
    e.preventDefault();
    this.dragging.set(true);
  }

  onDrop(e: DragEvent): void {
    e.preventDefault();
    this.dragging.set(false);
    this.add(Array.from(e.dataTransfer?.files ?? []).filter((f) => f.type.startsWith('image/')));
  }

  onPick(e: Event): void {
    const input = e.target as HTMLInputElement;
    this.add(Array.from(input.files ?? []));
    input.value = '';
  }

  private add(files: File[]): void {
    if (!files.length) return;
    this.queue.update((q) => [...q, ...files.map((file) => ({ file, url: URL.createObjectURL(file) }))]);
  }

  remove(item: { file: File; url: string }): void {
    URL.revokeObjectURL(item.url);
    this.queue.update((q) => q.filter((x) => x !== item));
  }

  // --- save + load -------------------------------------------------------

  async save(): Promise<void> {
    const items = this.queue();
    if (!items.length) return;
    this.saving.set(true);
    this.done.set(0);
    this.total.set(items.length);
    const spaceId = this.spaceCtx.currentSpaceId();
    const bundleId = crypto.randomUUID();

    for (const item of items) {
      try {
        const uploaded = await firstValueFrom(
          this.api.uploadDocument(item.file, this.vital, spaceId, this.aiRead));
        // Only when AI reading is on do we wait for the async extractor to finish before
        // confirming (so its delayed write can't clobber the email category + metadata).
        // With AI off there's no extractor to race, so confirm the upload straight away.
        const doc = this.aiRead ? await this.waitExtracted(uploaded.id) : uploaded;
        const body: ConfirmRequest = {
          category: 'email',
          docDate: this.emailDate || undefined,
          vital: this.vital,
          extra: {
            ...(doc.extra ?? {}),
            mailAccount: this.account,
            mailAddress: this.address,
            mailTopic: this.topic,
            mailSubject: this.subject,
            mailDate: this.emailDate,
            mailBundleId: bundleId,
            notes: this.description || undefined,
          },
        };
        await firstValueFrom(this.api.confirmDocument(doc.id, body));
      } catch {
        // surfaced by the notice interceptor
      }
      this.done.update((d) => d + 1);
    }

    items.forEach((i) => URL.revokeObjectURL(i.url));
    this.queue.set([]);
    this.account = '';
    this.address = '';
    this.topic = '';
    this.subject = '';
    this.emailDate = '';
    this.description = '';
    this.vital = false;
    this.aiRead = false;
    this.saving.set(false);
    this.showAdd.set(false);
    this.notices.show({ level: 'success', code: 'MAIL_SAVED', userMessage: 'Email filed to your vault.' });
    this.page.set(0); // the new thread sorts to the top
    this.load();
  }

  /** Polls a document until its extraction has settled (confidence set), so a later
   *  confirm can't be overwritten by the async extractor. Gives up after ~30s. */
  private async waitExtracted(id: string) {
    for (let i = 0; i < 20; i++) {
      const doc = await firstValueFrom(this.api.getDocument(id));
      if (doc.extractionConfidence != null) return doc;
      await new Promise((r) => setTimeout(r, 1500));
    }
    return firstValueFrom(this.api.getDocument(id));
  }

  private load(): void {
    this.loading.set(true);
    this.api.mailBundles(this.spaceCtx.currentSpaceId(), this.page(), this.pageSize()).subscribe({
      next: (p) => {
        this.entries.set(p.bundles);
        this.totalBundles.set(p.total);
        this.knownAccounts.set(p.accounts);
        this.knownTopics.set(p.topics);
        this.knownAddresses.set(p.addresses);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }
}

/** Extracts image files from a paste's DataTransferItemList. */
function imagesFrom(items: DataTransferItemList | undefined): File[] {
  const out: File[] = [];
  for (const item of Array.from(items ?? [])) {
    if (item.kind === 'file' && item.type.startsWith('image/')) {
      const f = item.getAsFile();
      if (f) out.push(f);
    }
  }
  return out;
}
