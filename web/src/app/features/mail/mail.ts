import { Component, HostListener, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { SpaceContext } from '../../core/space.context';
import { NoticeService } from '../../core/notice/notice.service';
import { ConfirmRequest, DocumentResponse } from '../../core/models';

/** One email = one or more screenshots sharing a bundle id, plus its metadata. */
interface MailEntry {
  bundleId: string;
  subject: string;
  account: string;
  date: string;
  docs: DocumentResponse[];
}

/**
 * Mail — file the important emails you screenshot (tax paid, subscription renewed) so
 * you can actually find them later. Paste/drop the screenshots, tag them with which
 * account (personal/office), a subject and the email's date; Trove stores them under
 * the "email" category, grouped as one entry, and lists them by account/date.
 *
 * Reuses the whole document pipeline — each screenshot is a normal document with
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
            <label>Account
              <input name="account" [(ngModel)]="account" list="mailAccounts" placeholder="Personal / Office" />
              <datalist id="mailAccounts">
                <option value="Personal"></option>
                <option value="Office"></option>
                @for (a of knownAccounts(); track a) { <option [value]="a"></option> }
              </datalist>
            </label>
            <label>Email date <input type="date" name="mdate" [(ngModel)]="emailDate" /></label>
          </div>
          <label>Subject / what it's about
            <input name="subject" [(ngModel)]="subject" placeholder="e.g. Income tax paid, FY 2025-26" />
          </label>
          <label class="checkbox">
            <input type="checkbox" name="vital" [(ngModel)]="vital" /> Sensitive: encrypt at rest
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
            <a class="entry" [routerLink]="['/documents', e.docs[0].id, 'review']">
              <div class="entry-thumbs">
                @for (d of e.docs; track d.id) {
                  <img [src]="thumb(d)" alt="screenshot" />
                }
              </div>
              <div class="entry-meta">
                <b>{{ e.subject || 'Email' }}</b>
                <span class="tag">{{ e.account || '-' }}</span>
                <span class="muted">{{ e.date || '' }} · {{ e.docs.length }} screenshot(s)</span>
              </div>
            </a>
          }
        </div>
      }
    </div>
  `,
  styles: [
    `
      .add { border: 1px solid #e4e4e4; border-radius: 12px; padding: 14px; margin: 8px 0 16px; }
      .dropzone {
        display: flex; align-items: center; justify-content: space-between; gap: 12px;
        border: 2px dashed rgba(47, 111, 106, 0.4); border-radius: 12px; padding: 18px; margin-bottom: 10px;
      }
      .dropzone.drag { border-color: #2f6f6a; background: rgba(47, 111, 106, 0.06); }
      .filebtn { cursor: pointer; background: rgba(47, 111, 106, 0.1); color: #2f6f6a; border-radius: 8px; padding: 8px 14px; font-weight: 600; }
      .thumbs { display: flex; flex-wrap: wrap; gap: 10px; margin: 10px 0; }
      .thumb { position: relative; width: 76px; height: 76px; }
      .thumb img { width: 76px; height: 76px; object-fit: cover; border-radius: 8px; border: 1px solid #e2e2e2; }
      .thumb .rm {
        position: absolute; top: -7px; right: -7px; box-sizing: border-box; width: 20px; height: 20px;
        min-width: 0; padding: 0; display: inline-flex; align-items: center; justify-content: center;
        border: 2px solid #fff; border-radius: 50%; background: #c0392b; color: #fff; font-size: 12px;
        line-height: 1; cursor: pointer; appearance: none;
      }
      .entries { display: flex; flex-direction: column; gap: 12px; margin-top: 12px; }
      .entry {
        display: flex; gap: 14px; padding: 12px; border: 1px solid #eee; border-radius: 12px;
        text-decoration: none; color: inherit; cursor: pointer; transition: background 120ms, border-color 120ms;
      }
      .entry:hover { background: rgba(47, 111, 106, 0.05); border-color: rgba(47, 111, 106, 0.35); }
      .entry-thumbs { display: flex; gap: 6px; }
      .entry-thumbs img { width: 56px; height: 56px; object-fit: cover; border-radius: 6px; border: 1px solid #e2e2e2; }
      .entry-meta { display: flex; flex-direction: column; gap: 4px; }
      .tag { align-self: flex-start; background: rgba(47, 111, 106, 0.12); color: #2f6f6a; border-radius: 999px; padding: 2px 10px; font-size: 12px; }
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
  subject = '';
  emailDate = '';
  vital = false;
  saving = signal(false);
  done = signal(0);
  total = signal(0);

  docs = signal<DocumentResponse[]>([]);
  loading = signal(false);

  /** Group the space's email documents into one entry per shared bundle id. */
  entries = computed<MailEntry[]>(() => {
    const groups = new Map<string, MailEntry>();
    for (const d of this.docs()) {
      const extra = d.extra ?? {};
      const bundleId = (extra['mailBundleId'] as string) || d.id;
      const entry = groups.get(bundleId) ?? {
        bundleId,
        subject: (extra['mailSubject'] as string) ?? '',
        account: (extra['mailAccount'] as string) ?? '',
        date: (extra['mailDate'] as string) ?? d.docDate ?? '',
        docs: [],
      };
      entry.docs.push(d);
      groups.set(bundleId, entry);
    }
    return [...groups.values()].sort((a, b) => (b.date ?? '').localeCompare(a.date ?? ''));
  });

  knownAccounts = computed<string[]>(() =>
    [...new Set(this.entries().map((e) => e.account).filter((a) => !!a))],
  );

  constructor() {
    effect(() => {
      this.spaceCtx.currentSpaceId(); // re-run on space change
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
        const uploaded = await firstValueFrom(this.api.uploadDocument(item.file, this.vital, spaceId));
        // Wait for the async extractor to finish BEFORE confirming — otherwise its
        // delayed write races the confirm and clobbers the email category + metadata.
        const doc = await this.waitExtracted(uploaded.id);
        const body: ConfirmRequest = {
          category: 'email',
          docDate: this.emailDate || undefined,
          vital: this.vital,
          extra: {
            ...(doc.extra ?? {}),
            mailAccount: this.account,
            mailSubject: this.subject,
            mailDate: this.emailDate,
            mailBundleId: bundleId,
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
    this.subject = '';
    this.emailDate = '';
    this.vital = false;
    this.saving.set(false);
    this.showAdd.set(false);
    this.notices.show({ level: 'success', code: 'MAIL_SAVED', userMessage: 'Email filed to your vault.' });
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
    this.api.listDocuments(this.spaceCtx.currentSpaceId(), 'email').subscribe({
      next: (d) => {
        this.docs.set(d);
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
