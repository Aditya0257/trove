import { Component, HostListener, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { SpaceContext } from '../../core/services/space.context';
import { NoticeService } from '../../core/services/notice.service';
import { ConfirmRequest, DocumentResponse, MailBundleView } from '../../core/models/models';

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
  templateUrl: './mail.html',
  styleUrl: './mail.scss',
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
