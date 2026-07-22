import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { Category, ConfirmRequest, DocumentResponse } from '../../core/models';
import { NoticeService } from '../../core/notice/notice.service';

@Component({
  selector: 'app-review',
  imports: [FormsModule],
  template: `
    @if (!doc()) {
      <div class="card"><p class="muted">Loading…</p></div>
    } @else {
      <div class="card">
        <div class="row-between">
          <h1>Review &amp; confirm</h1>
          <span class="badge" [class.confirmed]="doc()!.status === 'confirmed'">{{ doc()!.status }}</span>
        </div>

        @if (reading()) {
          <p class="muted">Reading the document — the fields below will fill in automatically…</p>
        } @else if (failedRead()) {
          <div class="ai-note failed">
            <b>We couldn't read this one automatically.</b> No worries — just fill in the
            details below (it takes a few seconds). The fields are blank on purpose so
            there's nothing wrong to delete.
            @if (readReason()) { <span class="muted"> · {{ readReason() }}</span> }
          </div>
        } @else {
          <div class="ai-note">
            <b>Please double-check these.</b> The details below were read from your document
            automatically and can be wrong — a misread amount, date or name happens. Confirm
            each value; you can edit anything now, or change it later.
            @if (confidencePct() !== '—') {
              <span class="muted"> · read confidence {{ confidencePct() }}</span>
            }
          </div>
        }
        @if (anomaly()) {
          <p class="warn">This looks higher than usual for its category — worth a second look.</p>
        }

        <button class="view-file" type="button" (click)="openFile()">View original file</button>

        <form (ngSubmit)="confirm()">
          <label>Category
            <select name="category" [(ngModel)]="form.category">
              <option value="" disabled>Choose a category…</option>
              @for (c of categories(); track c.code) { <option [value]="c.code">{{ c.label }}</option> }
            </select>
            <small class="help">Pick the category that fits — it drives spend tracking &amp; reminders.</small>
          </label>
          <label>Merchant
            <input name="merchant" [(ngModel)]="form.merchant" placeholder="e.g. Reliance Fresh, Airtel, Acko" />
          </label>
          <div class="row">
            <label>Amount
              <input type="number" step="0.01" name="amount" [(ngModel)]="form.amount" placeholder="0.00" />
            </label>
            <label>Currency
              <input name="currency" [(ngModel)]="form.currency" placeholder="INR" />
            </label>
          </div>
          <div class="row">
            <label>Document date <input type="date" name="docDate" [(ngModel)]="form.docDate" /></label>
            <label>Due date <input type="date" name="dueDate" [(ngModel)]="form.dueDate" /></label>
          </div>
          <label class="checkbox">
            <input type="checkbox" name="vital" [(ngModel)]="form.vital" />
            Vital / sensitive (encrypt at rest)
          </label>
          @if (error()) { <p class="error">{{ error() }}</p> }
          <button type="submit" [disabled]="saving()">
            {{ saving() ? 'Saving…' : confirmLabel() }}
          </button>
        </form>
      </div>
    }
  `,
  styles: [
    `
      .ai-note {
        background: rgba(184, 134, 11, 0.1);
        border-left: 3px solid #b8860b;
        border-radius: 8px;
        padding: 10px 12px;
        margin: 4px 0 12px;
        font-size: 14px;
        line-height: 1.45;
      }
      .warn { color: #8a5a00; }
      .ai-note.failed { background: rgba(192, 57, 43, 0.08); border-left-color: #c0392b; }
      .help { display: block; margin-top: 4px; color: #8a8a8a; font-size: 12px; }
      .view-file {
        display: inline-flex; align-items: center; gap: 6px; margin: 2px 0 16px;
        border: 1px solid rgba(47, 111, 106, 0.4); background: transparent; color: #2f6f6a;
        border-radius: 8px; padding: 7px 14px; font-size: 13px; font-weight: 600; cursor: pointer;
      }
      .view-file:hover { background: rgba(47, 111, 106, 0.08); }
    `,
  ],
})
export class Review {
  private api = inject(ApiService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private notices = inject(NoticeService);

  private id = '';
  doc = signal<DocumentResponse | null>(null);
  categories = signal<Category[]>([]);
  reading = signal(false);
  saving = signal(false);
  error = signal<string | null>(null);

  form = {
    category: '',
    merchant: '',
    amount: null as number | null,
    currency: 'INR',
    docDate: '',
    dueDate: '',
    vital: false,
  };

  ngOnInit(): void {
    this.id = this.route.snapshot.paramMap.get('id') ?? '';
    this.api.listCategories().subscribe((c) => this.categories.set(c));
    this.loadAndPoll(0);
  }

  /** "Confirm" the first time; "Save changes" when re-editing an already-confirmed doc. */
  confirmLabel(): string {
    return this.doc()?.status === 'confirmed' ? 'Save changes' : 'Confirm';
  }

  confidencePct(): string {
    const c = this.doc()?.extractionConfidence;
    return c != null ? `${Math.round(c * 100)}%` : '—';
  }

  private extractionMeta(): Record<string, unknown> {
    return (this.doc()?.extra?.['extractionMeta'] as Record<string, unknown>) ?? {};
  }

  /** True when nothing was really read — fell back to the stub, or confidence 0. */
  failedRead(): boolean {
    return this.extractionMeta()['fellBack'] === true || this.doc()?.extractionConfidence === 0;
  }

  /** A short, human reason for a failed read (from the extraction notice), if any. */
  readReason(): string {
    const notice = this.extractionMeta()['notice'] as { devNote?: string } | undefined;
    return notice?.devNote ?? '';
  }

  anomaly(): boolean {
    const a = this.doc()?.extra?.['anomaly'] as { anomaly?: boolean } | undefined;
    return !!a?.anomaly;
  }

  /**
   * Opens the original file. Non-vital docs have a presigned URL (no auth needed) and
   * open directly; vital (encrypted) docs are fetched from /content with the auth
   * header, then shown from an in-memory blob URL.
   */
  openFile(): void {
    const d = this.doc();
    if (!d) return;
    if (d.encrypted) {
      this.api.getContent(d.id).subscribe((blob) => {
        const url = URL.createObjectURL(blob);
        window.open(url, '_blank');
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
      });
    } else {
      const url = this.api.fileUrl(d);
      if (url) window.open(url, '_blank');
    }
  }

  private loadAndPoll(attempt: number): void {
    this.api.getDocument(this.id).subscribe((doc) => {
      this.doc.set(doc);
      if (doc.extractionConfidence == null && doc.status === 'needs_review' && attempt < 12) {
        this.reading.set(true);
        setTimeout(() => this.loadAndPoll(attempt + 1), 2000);
      } else {
        this.reading.set(false);
        this.fillForm(doc);
      }
    });
  }

  private fillForm(doc: DocumentResponse): void {
    this.form = {
      category: doc.category ?? '',
      merchant: doc.merchant ?? '',
      amount: doc.amount,
      currency: doc.currency ?? 'INR',
      docDate: doc.docDate ?? '',
      dueDate: doc.dueDate ?? '',
      vital: doc.vital,
    };
  }

  confirm(): void {
    this.saving.set(true);
    this.error.set(null);
    const body: ConfirmRequest = {
      category: this.form.category || undefined,
      merchant: this.form.merchant || undefined,
      amount: this.form.amount ?? undefined,
      currency: this.form.currency || undefined,
      docDate: this.form.docDate || undefined,
      dueDate: this.form.dueDate || undefined,
      vital: this.form.vital,
    };
    this.api.confirmDocument(this.id, body).subscribe({
      next: () => {
        this.notices.show({
          level: 'success',
          code: 'CONFIRMED',
          userMessage: 'Saved to your vault.',
        });
        this.router.navigate(['/documents']);
      },
      error: (e) => {
        this.error.set(e?.error?.message ?? 'Confirm failed');
        this.saving.set(false);
      },
    });
  }
}
