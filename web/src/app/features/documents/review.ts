import { Component, computed, inject, signal } from '@angular/core';
import { Location } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { Category, ConfirmRequest, DocumentResponse } from '../../core/models';
import { NoticeService } from '../../core/notice/notice.service';
import { TroveSelect, SelectOption } from '../../core/select';

@Component({
  selector: 'app-review',
  imports: [FormsModule, TroveSelect],
  template: `
    @if (!doc()) {
      <div class="card"><p class="muted">Loading…</p></div>
    } @else {
      <div class="card">
        <button class="back" type="button" (click)="back()">← Back</button>
        <div class="row-between">
          <h1>Review &amp; confirm</h1>
          <span class="badge" [class.confirmed]="doc()!.status === 'confirmed'">{{ doc()!.status }}</span>
        </div>

        @if (reading()) {
          <p class="muted">Reading the document. The fields below will fill in automatically…</p>
        } @else if (needsReview()) {
          @if (extractionSkipped()) {
            <div class="ai-note skipped">
              <b>AI reading was off for this upload.</b> Enter the details below and Confirm.
              You can turn AI reading back on any time from the Upload screen.
            </div>
          } @else if (failedRead()) {
            <div class="ai-note failed">
              <b>We couldn't read this one automatically.</b> No worries: just fill in the
              details below (it takes a few seconds). The fields are blank on purpose, so
              there's nothing wrong to delete.
              @if (readReason()) { <span class="muted"> · {{ readReason() }}</span> }
            </div>
          } @else {
            <div class="ai-note">
              <b>Please double-check these.</b> The details below were read from your document
              automatically and can be wrong (a misread amount, date or name happens). Confirm
              each value; you can edit anything now, or change it later.
              @if (confidencePct() !== '-') {
                <span class="muted"> · read confidence {{ confidencePct() }}</span>
              }
            </div>
          }
        } @else {
          <p class="muted">You've reviewed and saved this document. Edit any field and Save changes to update it.</p>
        }
        @if (anomaly()) {
          <p class="warn">This looks higher than usual for its category. Worth a second look.</p>
        }
        @if (uploadedOn()) { <p class="uploaded">Added to Trove on {{ uploadedOn() }}</p> }

        <button class="view-file" type="button" (click)="openFile()">View original file</button>

        <form (ngSubmit)="confirm()">
          <label>
            <span class="lbl">Category <span class="tip" tabindex="0">i<span class="bubble">{{ tips.category }}</span></span></span>
            <trove-select name="category" [(ngModel)]="form.category" [options]="categoryOptions()"
              placeholder="Choose a category…" ariaLabel="Category"></trove-select>
          </label>
          <label>
            <span class="lbl">Merchant <span class="tip" tabindex="0">i<span class="bubble">{{ tips.merchant }}</span></span></span>
            <input name="merchant" [(ngModel)]="form.merchant" placeholder="e.g. Reliance Fresh, Airtel, Acko" />
          </label>
          <div class="row">
            <label>
              <span class="lbl">Amount <span class="tip" tabindex="0">i<span class="bubble">{{ tips.amount }}</span></span></span>
              <input type="number" step="0.01" name="amount" [(ngModel)]="form.amount" placeholder="0.00" />
            </label>
            <label>
              <span class="lbl">Currency <span class="tip" tabindex="0">i<span class="bubble">{{ tips.currency }}</span></span></span>
              <input name="currency" [(ngModel)]="form.currency" placeholder="INR" />
            </label>
          </div>
          <div class="row">
            <label>
              <span class="lbl">Document date <span class="tip" tabindex="0">i<span class="bubble">{{ tips.docDate }}</span></span></span>
              <input type="date" name="docDate" [(ngModel)]="form.docDate" />
            </label>
            <label>
              <span class="lbl">Due date <span class="tip" tabindex="0">i<span class="bubble">{{ tips.dueDate }}</span></span></span>
              <input type="date" name="dueDate" [(ngModel)]="form.dueDate" />
            </label>
          </div>
          <label>
            <span class="lbl">Notes (optional) <span class="tip" tabindex="0">i<span class="bubble">{{ tips.notes }}</span></span></span>
            <textarea name="notes" [(ngModel)]="form.notes" rows="2"
              placeholder="Anything you want to remember or find this by later, e.g. Bhopal Indore highway toll"></textarea>
          </label>
          <label class="checkbox">
            <input type="checkbox" name="vital" [(ngModel)]="form.vital" />
            Vital / sensitive (encrypt at rest)
          </label>
          @if (error()) { <p class="error">{{ error() }}</p> }
          <div class="actions">
            <button type="submit" [disabled]="saving()">
              {{ saving() ? 'Saving…' : confirmLabel() }}
            </button>
            <button type="button" class="btn-del" (click)="remove()">Delete</button>
          </div>
        </form>

        @if (lineItems().length || extraEntries().length || rawText()) {
          <details class="extracted">
            <summary>Everything the AI read from this document</summary>
            @if (lineItems().length) {
              <div class="ex-title">Line items</div>
              <table class="li">
                <thead><tr><th>Item</th><th>Qty</th><th>Amount</th></tr></thead>
                <tbody>
                  @for (li of lineItems(); track $index) {
                    <tr><td>{{ li.description || '-' }}</td><td>{{ li.quantity ?? '-' }}</td><td>{{ li.amount ?? '-' }}</td></tr>
                  }
                </tbody>
              </table>
            }
            @if (extraEntries().length) {
              <div class="ex-title">Other details found</div>
              <div class="ex-fields">
                @for (e of extraEntries(); track e[0]) {
                  <div class="ex-kv"><span>{{ e[0] }}</span><div>{{ e[1] }}</div></div>
                }
              </div>
            }
            @if (rawText()) {
              <div class="ex-title">Full text read</div>
              <pre class="rawtext">{{ rawText() }}</pre>
            }
          </details>
        }
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
      .warn { color: var(--warn); }
      .ai-note.failed { background: var(--danger-soft); border-left-color: var(--danger); }
      .ai-note.skipped { background: var(--accent-soft); border-left-color: var(--accent); }
      .extracted { margin-top: 16px; border-top: 1px solid var(--line); padding-top: 12px; }
      .extracted summary { cursor: pointer; font-weight: 600; font-size: 13px; color: var(--accent); }
      .ex-title { font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.03em; color: var(--muted); margin: 12px 0 4px; }
      .li { width: 100%; font-size: 13px; }
      .li th { text-align: left; color: var(--muted); font-weight: 600; }
      .li td, .li th { padding: 4px 6px; border-bottom: 1px solid var(--line); }
      .ex-fields { display: flex; flex-direction: column; gap: 4px; }
      .ex-kv { display: flex; gap: 10px; font-size: 13px; }
      .ex-kv span { flex: none; min-width: 140px; color: var(--muted); font-weight: 600; text-transform: capitalize; }
      .rawtext {
        background: var(--code-bg); color: var(--ink); border-radius: 8px; padding: 10px; font-size: 12px;
        line-height: 1.5; white-space: pre-wrap; word-break: break-word; max-height: 320px; overflow-y: auto; margin: 4px 0 0;
      }
      .help { display: block; margin-top: 4px; color: var(--muted); font-size: 12px; }
      .view-file {
        display: inline-flex; align-items: center; gap: 6px; margin: 2px 0 16px;
        border: 1px solid var(--accent-line); background: transparent; color: var(--accent);
        border-radius: 8px; padding: 7px 14px; font-size: 13px; font-weight: 600; cursor: pointer;
      }
      .view-file:hover { background: var(--accent-soft); }
      .uploaded { color: var(--muted); font-size: 12px; margin: 2px 0 14px; }
      .lbl { display: inline-flex; align-items: center; }
      .tip {
        display: inline-flex; align-items: center; justify-content: center;
        width: 16px; height: 16px; margin-left: 6px; border-radius: 50%;
        background: var(--tip-bg); color: var(--accent); font-size: 11px; font-weight: 700;
        font-style: normal; cursor: help; position: relative; outline: none;
      }
      .tip .bubble {
        visibility: hidden; opacity: 0; position: absolute; bottom: 150%; left: 50%;
        transform: translateX(-50%); width: 230px; background: #222; color: #fff;
        padding: 8px 10px; border-radius: 8px; font-size: 12px; font-weight: 400;
        line-height: 1.4; z-index: 20; transition: opacity 120ms;
        box-shadow: 0 6px 20px rgba(0, 0, 0, 0.25); pointer-events: none;
      }
      .tip:hover .bubble, .tip:focus .bubble { visibility: visible; opacity: 1; }
      textarea { width: 100%; box-sizing: border-box; resize: vertical; font-family: inherit; padding: 8px; }
      .back {
        border: 0; background: transparent; color: var(--accent); cursor: pointer;
        font-size: 13px; padding: 0; margin-bottom: 10px;
      }
      .back:hover { text-decoration: underline; }
      .actions { display: flex; gap: 12px; align-items: center; margin-top: 10px; }
      .btn-del {
        border: 1px solid var(--danger-line); background: transparent; color: var(--danger);
        border-radius: 8px; padding: 9px 18px; font-size: 14px; cursor: pointer;
      }
      .btn-del:hover { background: var(--danger-soft); }
    `,
  ],
})
export class Review {
  private api = inject(ApiService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private notices = inject(NoticeService);
  private location = inject(Location);

  /** Still awaiting the human's first confirmation (drives the read notices). */
  needsReview(): boolean {
    return this.doc()?.status === 'needs_review';
  }

  /** Go back to wherever we came from (the document list or Mail). */
  back(): void {
    this.location.back();
  }

  private id = '';
  doc = signal<DocumentResponse | null>(null);
  categories = signal<Category[]>([]);
  protected categoryOptions = computed<SelectOption[]>(() =>
    this.categories().map((c) => ({ value: c.code, label: c.label })),
  );
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
    notes: '',
    vital: false,
  };

  /** Field help — shown on hover/focus of the info icon (Salesforce-style). */
  readonly tips = {
    category: 'The kind of document (electricity, shopping, insurance, and so on). It drives spend tracking, reminders and search.',
    merchant: 'Who issued it: the store, biller or company printed on the document.',
    amount: 'The total amount on the document. Digits only, no currency symbol.',
    currency: 'Currency code, for example INR or USD.',
    docDate: 'The date printed on the document itself (the invoice, bill or receipt date).',
    dueDate: 'When a payment or renewal is due, if any. Reminders fire a few days before this date.',
    notes: 'Anything extra you want to remember or find this by later, in your own words.',
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
    return c != null ? `${Math.round(c * 100)}%` : '-';
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

  /** True when this upload was stored with AI reading turned off. */
  extractionSkipped(): boolean {
    return this.doc()?.extra?.['extractionSkipped'] === true;
  }

  /** Internal `extra` keys that are plumbing, not document data — hidden from the trail. */
  private static readonly INTERNAL_EXTRA = new Set([
    'extractionMeta', 'extractionProvider', 'extractionModel', 'extractionAccepted',
    'aiTokens', 'aiNeurons', 'notes', 'extractionSkipped', 'anomaly',
    'mailAccount', 'mailAddress', 'mailTopic', 'mailSubject', 'mailDate', 'mailBundleId',
  ]);

  lineItems() {
    return this.doc()?.lineItems ?? [];
  }
  rawText(): string {
    return this.doc()?.rawText ?? '';
  }
  /** The model's type-specific extra fields (account no., invoice no., tax…) — the extra
   *  value the AI read that doesn't map to a core field. Plumbing keys are filtered out. */
  extraEntries(): [string, string][] {
    const ex = this.doc()?.extra ?? {};
    return Object.entries(ex)
      .filter(([k, v]) => !Review.INTERNAL_EXTRA.has(k) && v != null && typeof v !== 'object')
      .map(([k, v]) => [k, String(v)] as [string, string]);
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
      // Emails live in the Mail section with their own fields (account, subject, topic).
      // If we land here for one (e.g. via search or a stale link), send it to the Mail
      // detail view rather than showing the generic bill form.
      if (doc.category === 'email') {
        const bundleId = (doc.extra?.['mailBundleId'] as string) || doc.id;
        this.router.navigate(['/mail', bundleId], { replaceUrl: true });
        return;
      }
      this.doc.set(doc);
      // Don't wait for a read that will never come: skip polling when AI reading was off.
      const skipped = doc.extra?.['extractionSkipped'] === true;
      if (!skipped && doc.extractionConfidence == null && doc.status === 'needs_review' && attempt < 12) {
        this.reading.set(true);
        setTimeout(() => this.loadAndPoll(attempt + 1), 2000);
      } else {
        this.reading.set(false);
        this.fillForm(doc);
      }
    });
  }

  /** When the document was added to Trove (the upload timestamp, stored automatically). */
  uploadedOn(): string {
    const at = this.doc()?.createdAt;
    return at ? new Date(at).toLocaleString('en-GB', { hour12: false }) : '';
  }

  private fillForm(doc: DocumentResponse): void {
    this.form = {
      category: doc.category ?? '',
      merchant: doc.merchant ?? '',
      amount: doc.amount,
      currency: doc.currency ?? 'INR',
      docDate: doc.docDate ?? '',
      dueDate: doc.dueDate ?? '',
      notes: (doc.extra?.['notes'] as string) ?? '',
      vital: doc.vital,
    };
  }

  remove(): void {
    const d = this.doc();
    if (!d) return;
    const name = d.merchant || d.originalFilename || 'this document';
    if (!confirm(`Delete "${name}"? This removes it from your vault.`)) {
      return;
    }
    this.api.deleteDocument(d.id).subscribe({
      next: () => {
        this.notices.show({ level: 'success', code: 'DELETED', userMessage: 'Document deleted.' });
        this.router.navigate(['/documents']);
      },
    });
  }

  confirm(): void {
    this.saving.set(true);
    this.error.set(null);
    // Preserve existing extra (extraction trail, anomaly) and add the user's note.
    const extra = { ...(this.doc()?.extra ?? {}), notes: this.form.notes || undefined };
    const body: ConfirmRequest = {
      category: this.form.category || undefined,
      merchant: this.form.merchant || undefined,
      amount: this.form.amount ?? undefined,
      currency: this.form.currency || undefined,
      docDate: this.form.docDate || undefined,
      dueDate: this.form.dueDate || undefined,
      vital: this.form.vital,
      extra,
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
