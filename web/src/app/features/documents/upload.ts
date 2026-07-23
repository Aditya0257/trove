import { Component, HostListener, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../core/api.service';
import { SpaceContext } from '../../core/space.context';
import { NoticeService } from '../../core/notice/notice.service';
import { noticeFrom } from '../../core/notice/notice.model';

/** A queued image awaiting upload, with a preview URL to revoke later. */
interface Queued {
  file: File;
  url: string;
}

/**
 * Upload screen. Three ways in — paste a screenshot (Cmd/Ctrl+V), drag images onto
 * the drop zone, or the file picker — and several at once. Each queued image shows a
 * thumbnail; uploading pushes them through the read pipeline one by one. A single
 * upload goes straight to its review; a batch lands on the documents list where each
 * needs-review item is one tap away.
 */
@Component({
  selector: 'app-upload',
  imports: [FormsModule],
  template: `
    <div class="card">
      <h1>Add documents</h1>
      <p class="muted">
        Paste a screenshot ({{ pasteHint }}), drop images, or choose files: a bill,
        receipt, policy or ID. <b>Images are read automatically</b> and you just confirm the
        details next. PDFs and other non-image files are stored safely, but aren't auto-read
        yet, so you'll fill in their details yourself.
      </p>

      <div
        class="dropzone"
        [class.drag]="dragging()"
        (dragover)="onDragOver($event)"
        (dragleave)="dragging.set(false)"
        (drop)="onDrop($event)"
        tabindex="0"
      >
        <span class="hint">Paste ({{ pasteHint }}) or drop images</span>
        <label class="filebtn">
          Choose files
          <input type="file" (change)="onPick($event)" accept="image/*,application/pdf" multiple hidden />
        </label>
      </div>

      @if (queue().length) {
        <div class="thumbs">
          @for (item of queue(); track item.url) {
            <div class="thumb">
              @if (isImage(item)) {
                <img [src]="item.url" alt="pending upload" />
              } @else {
                <!-- PDFs and other non-images can't render as an <img>; show a file card
                     instead of a broken-image icon. -->
                <div class="filecard" [title]="item.file.name">
                  <span class="ext">{{ ext(item) }}</span>
                  <span class="fname">{{ item.file.name }}</span>
                </div>
              }
              <button class="rm" (click)="remove(item)" [disabled]="loading()" aria-label="Remove">×</button>
            </div>
          }
        </div>
        @if (hasNonImage()) {
          <p class="note">ℹ Non-image files (PDF, etc.) are stored but not auto-read, so you'll
            enter their details manually on the review screen.</p>
        }
      }

      <label class="checkbox">
        <input type="checkbox" name="vital" [(ngModel)]="vital" [disabled]="loading()" />
        These are vital/sensitive (passport, ID, policy). Encrypt at rest
      </label>

      @if (loading()) {
        <p class="muted">Uploading {{ done() + 1 }} of {{ total() }}…</p>
      }
      @if (error()) { <p class="error">{{ error() }}</p> }

      <button (click)="upload()" [disabled]="!queue().length || loading()">
        {{ uploadLabel() }}
      </button>
    </div>
  `,
  styles: [
    `
      .dropzone {
        display: flex; align-items: center; justify-content: space-between; gap: 12px;
        border: 2px dashed var(--accent-line); border-radius: 12px;
        padding: 22px 18px; margin: 8px 0 4px; transition: background 120ms, border-color 120ms;
      }
      .dropzone.drag { border-color: var(--accent); background: var(--accent-soft); }
      .dropzone .hint { color: var(--muted); }
      .filebtn {
        cursor: pointer; background: var(--accent-soft); color: var(--accent);
        border-radius: 8px; padding: 8px 14px; font-weight: 600; white-space: nowrap;
      }
      .thumbs { display: flex; flex-wrap: wrap; gap: 10px; margin: 12px 0; }
      .thumb { position: relative; width: 84px; height: 84px; }
      .thumb img { width: 84px; height: 84px; object-fit: cover; border-radius: 8px; border: 1px solid var(--line); }
      .thumb .filecard {
        box-sizing: border-box; width: 84px; height: 84px; border-radius: 8px; border: 1px solid var(--line);
        background: var(--code-bg); display: flex; flex-direction: column; align-items: center; justify-content: center;
        gap: 5px; padding: 6px; text-align: center;
      }
      .filecard .ext {
        font: 700 12px/1 monospace; color: var(--danger); background: var(--card);
        border: 1px solid var(--line); border-radius: 4px; padding: 3px 7px;
      }
      .filecard .fname {
        max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
        font-size: 9.5px; color: var(--muted);
      }
      .thumb .rm {
        position: absolute; top: -7px; right: -7px;
        box-sizing: border-box; width: 20px; height: 20px; min-width: 0; padding: 0;
        display: inline-flex; align-items: center; justify-content: center;
        border: 2px solid #fff; border-radius: 50%; background: var(--danger); color: #fff;
        font-size: 12px; line-height: 1; cursor: pointer; -webkit-appearance: none; appearance: none;
        box-shadow: 0 1px 3px rgba(0, 0, 0, 0.3);
      }
      .thumb .rm:hover { background: #a53125; }
      .note {
        margin: 4px 0 12px; padding: 8px 12px; border-radius: 8px; font-size: 12.5px;
        color: var(--warn); background: rgba(184, 134, 11, 0.1); border: 1px solid rgba(184, 134, 11, 0.25);
      }
    `,
  ],
})
export class Upload {
  private api = inject(ApiService);
  private router = inject(Router);
  private spaceCtx = inject(SpaceContext);
  private notices = inject(NoticeService);

  vital = false;
  queue = signal<Queued[]>([]);
  loading = signal(false);
  done = signal(0);
  total = signal(0);
  error = signal<string | null>(null);

  /** Show the right modifier per OS (⌘ on Mac, Ctrl elsewhere). */
  readonly pasteHint = /mac|iphone|ipad/i.test(navigator.userAgent) ? '⌘V' : 'Ctrl+V';

  uploadLabel(): string {
    if (this.loading()) return 'Uploading…';
    const n = this.queue().length;
    return n > 1 ? `Upload ${n} documents` : 'Upload';
  }

  // --- intake: paste / drop / pick ---------------------------------------

  @HostListener('document:paste', ['$event'])
  onPaste(e: ClipboardEvent): void {
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
  dragging = signal(false);

  onDrop(e: DragEvent): void {
    e.preventDefault();
    this.dragging.set(false);
    const files = Array.from(e.dataTransfer?.files ?? []).filter(
      (f) => f.type.startsWith('image/') || f.type === 'application/pdf',
    );
    this.add(files);
  }

  onPick(e: Event): void {
    const input = e.target as HTMLInputElement;
    this.add(Array.from(input.files ?? []));
    input.value = ''; // allow re-picking the same file
  }

  /** Only images can be previewed as an <img>; everything else gets a file card. */
  isImage(item: Queued): boolean {
    return item.file.type.startsWith('image/');
  }

  /** True if the queue holds any non-image (PDF, etc.) that won't be auto-read. */
  hasNonImage(): boolean {
    return this.queue().some((item) => !this.isImage(item));
  }

  /** Short type badge for the file card (PDF, or the extension, else FILE). */
  ext(item: Queued): string {
    if (item.file.type === 'application/pdf') return 'PDF';
    const dot = item.file.name.lastIndexOf('.');
    return dot >= 0 ? item.file.name.slice(dot + 1).toUpperCase().slice(0, 4) : 'FILE';
  }

  private add(files: File[]): void {
    if (!files.length) return;
    const additions = files.map((file) => ({ file, url: URL.createObjectURL(file) }));
    this.queue.update((q) => [...q, ...additions]);
  }

  remove(item: Queued): void {
    URL.revokeObjectURL(item.url);
    this.queue.update((q) => q.filter((x) => x !== item));
  }

  // --- upload ------------------------------------------------------------

  async upload(): Promise<void> {
    const items = this.queue();
    if (!items.length) return;
    this.loading.set(true);
    this.error.set(null);
    this.done.set(0);
    this.total.set(items.length);

    const spaceId = this.spaceCtx.currentSpaceId();
    const ids: string[] = [];
    for (const item of items) {
      try {
        const doc = await firstValueFrom(this.api.uploadDocument(item.file, this.vital, spaceId));
        const meta = doc.extra?.['extractionMeta'] as Record<string, unknown> | undefined;
        const notice = noticeFrom(meta?.['notice']);
        if (notice) {
          this.notices.show(notice);
        }
        ids.push(doc.id);
      } catch (e: unknown) {
        // A duplicate (409) means this exact file is already in the vault — open the
        // existing document rather than dead-ending. Its id rides in the error body.
        const err = e as {
          status?: number;
          error?: { details?: Record<string, unknown>; notice?: { meta?: Record<string, unknown> } };
        };
        const existing = (err?.error?.details?.['existingDocumentId'] ??
          err?.error?.notice?.meta?.['existingDocumentId']) as string | undefined;
        if (err?.status === 409 && existing) {
          ids.push(existing);
        }
        // other failures are already surfaced as a toast by the notice interceptor
      }
      this.done.update((d) => d + 1);
    }

    items.forEach((i) => URL.revokeObjectURL(i.url));
    this.queue.set([]);
    this.loading.set(false);

    if (ids.length === 1) {
      this.router.navigate(['/documents', ids[0], 'review']);
    } else if (ids.length > 1) {
      this.notices.show({
        level: 'success',
        code: 'UPLOADED',
        userMessage: `${ids.length} documents uploaded. Review each one below.`,
      });
      this.router.navigate(['/documents']);
    } else {
      this.error.set('Nothing uploaded. Please try again.');
    }
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
