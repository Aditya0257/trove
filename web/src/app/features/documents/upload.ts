import { Component, HostListener, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../../core/services/api.service';
import { SpaceContext } from '../../core/services/space.context';
import { NoticeService } from '../../core/services/notice.service';
import { noticeFrom } from '../../core/models/notice.model';
import { HelpCard } from '../../shared/components/help-card';
import { SettingsService } from '../../core/services/settings.service';

/** A queued image awaiting upload, with a preview URL to revoke later. */
interface Queued {
  file: File;
  url: string;
}

/**
 * Upload screen. Three ways in - paste a screenshot (Cmd/Ctrl+V), drag images onto
 * the drop zone, or the file picker - and several at once. Each queued image shows a
 * thumbnail; uploading pushes them through the read pipeline one by one. A single
 * upload goes straight to its review; a batch lands on the documents list where each
 * needs-review item is one tap away.
 */
@Component({
  selector: 'app-upload',
  imports: [FormsModule, HelpCard],
  templateUrl: './upload.html',
  styleUrl: './upload.scss',
})
export class Upload {
  private api = inject(ApiService);
  private router = inject(Router);
  private spaceCtx = inject(SpaceContext);
  private notices = inject(NoticeService);
  protected settings = inject(SettingsService);

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
    this.addSupported(Array.from(e.dataTransfer?.files ?? []));
  }

  onPick(e: Event): void {
    const input = e.target as HTMLInputElement;
    this.addSupported(Array.from(input.files ?? []));
    input.value = ''; // allow re-picking the same file
  }

  /** Accepted upload types: images and PDF. The OS picker only hints at this (macOS
   *  still lets you select anything), so we filter here and tell the user what was skipped. */
  private static readonly SUPPORTED = /\.(jpe?g|png|heic|heif|webp|gif|bmp|tiff?|pdf)$/i;
  private addSupported(files: File[]): void {
    const ok = files.filter(
      (f) => f.type.startsWith('image/') || f.type === 'application/pdf' || Upload.SUPPORTED.test(f.name),
    );
    const skipped = files.length - ok.length;
    if (skipped > 0) {
      this.notices.show({
        level: 'warning', code: 'UNSUPPORTED_FILE',
        userMessage: `Skipped ${skipped} file${skipped > 1 ? 's' : ''}: Trove accepts images (JPG, PNG, HEIC, WebP) and PDF only.`,
      });
    }
    this.add(ok);
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
        // AI reads images only, and only when the toggle is on.
        const useAi = this.settings.aiReading() && this.isImage(item);
        const doc = await firstValueFrom(
          this.api.uploadDocument(item.file, this.vital, spaceId, useAi));
        const meta = doc.extra?.['extractionMeta'] as Record<string, unknown> | undefined;
        const notice = noticeFrom(meta?.['notice']);
        if (notice) {
          this.notices.show(notice);
        }
        ids.push(doc.id);
      } catch (e: unknown) {
        // A duplicate (409) means this exact file is already in the vault - open the
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
