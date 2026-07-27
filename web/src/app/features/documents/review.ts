import { Component, computed, inject, signal } from '@angular/core';
import { Location } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { Category, ConfirmRequest, DocumentResponse } from '../../core/models/models';
import { NoticeService } from '../../core/services/notice.service';
import { ConfirmService } from '../../core/services/confirm.service';
import { TroveSelect, SelectOption } from '../../shared/components/select';
import { MoneyPipe } from '../../shared/pipes/money.pipe';
import { CURRENCY_OPTIONS } from '../../core/config/currencies';

@Component({
  selector: 'app-review',
  imports: [FormsModule, RouterLink, TroveSelect, MoneyPipe],
  templateUrl: './review.html',
  styleUrl: './review.scss',
})
export class Review {
  private api = inject(ApiService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private notices = inject(NoticeService);
  private dialog = inject(ConfirmService);
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
  related = signal<DocumentResponse[]>([]);
  categories = signal<Category[]>([]);
  protected categoryOptions = computed<SelectOption[]>(() =>
    this.categories().map((c) => ({ value: c.code, label: c.label })),
  );
  protected currencyOptions = CURRENCY_OPTIONS;
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
    warrantyUntil: '',
    notes: '',
    vital: false,
  };

  /** Field help - shown on hover/focus of the info icon (Salesforce-style). */
  readonly tips = {
    category: 'The kind of document (electricity, shopping, insurance, and so on). It drives spend tracking, reminders and search.',
    merchant: 'Who issued it: the store, biller or company printed on the document.',
    amount: 'The total amount on the document. Digits only, no currency symbol.',
    currency: 'Currency code, for example INR or USD.',
    docDate: 'The date printed on the document itself (the invoice, bill or receipt date).',
    dueDate: 'When a payment or renewal is due, if any. Reminders fire a few days before this date.',
    warranty: 'If this is a purchase with a warranty, set when cover ends (or tap +1 year from the document date). Trove reminds you about two weeks before it expires.',
    notes: 'Anything extra you want to remember or find this by later, in your own words.',
  };

  ngOnInit(): void {
    this.id = this.route.snapshot.paramMap.get('id') ?? '';
    this.api.listCategories().subscribe((c) => this.categories.set(c));
    // Related is loaded once the read settles (see loadAndPoll), not here: on a fresh
    // upload the merchant/category are not known yet, so an eager fetch would return
    // pre-read "uncategorized" siblings.
    this.loadAndPoll(0);
  }

  /** Other documents from the same merchant/category (the auto-link view). */
  private loadRelated(): void {
    if (!this.id) return;
    this.api.relatedDocuments(this.id).subscribe({
      next: (r) => this.related.set(r),
      error: () => {}, // a missing related list is non-fatal; just show nothing
    });
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

  /** True when nothing was really read - fell back to the stub, or confidence 0. */
  failedRead(): boolean {
    return this.extractionMeta()['fellBack'] === true || this.doc()?.extractionConfidence === 0;
  }

  /** A short, human reason for a failed read (from the extraction notice), if any. */
  readReason(): string {
    const notice = this.extractionMeta()['notice'] as { devNote?: string } | undefined;
    return notice?.devNote ?? '';
  }

  private anomalyData(): { anomaly?: boolean; deltaPct?: number; average?: number } | undefined {
    return this.doc()?.extra?.['anomaly'] as { anomaly?: boolean; deltaPct?: number; average?: number } | undefined;
  }
  anomaly(): boolean {
    return !!this.anomalyData()?.anomaly;
  }
  /** The overshoot as a rounded percentage, e.g. "42%". */
  anomalyPct(): string {
    const d = this.anomalyData()?.deltaPct;
    return d != null ? `${Math.round(d * 100)}%` : '';
  }
  /** The trailing average for the category (what you usually pay), or null. */
  anomalyAvg(): number | null {
    return this.anomalyData()?.average ?? null;
  }

  /** True when this upload was stored with AI reading turned off. */
  extractionSkipped(): boolean {
    return this.doc()?.extra?.['extractionSkipped'] === true;
  }

  /** Internal `extra` keys that are plumbing, not document data - hidden from the trail. */
  private static readonly INTERNAL_EXTRA = new Set([
    'extractionMeta', 'extractionProvider', 'extractionModel', 'extractionAccepted',
    'aiTokens', 'aiNeurons', 'notes', 'extractionSkipped', 'anomaly', 'warrantyUntil',
    'mailAccount', 'mailAddress', 'mailTopic', 'mailSubject', 'mailDate', 'mailBundleId',
  ]);

  lineItems() {
    return this.doc()?.lineItems ?? [];
  }
  rawText(): string {
    return this.doc()?.rawText ?? '';
  }
  /** The model's type-specific extra fields (account no., invoice no., tax…) - the extra
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

  /** Re-run AI reading (after a read that timed out and left the fields blank). */
  readAgain(): void {
    if (this.reading()) return;
    this.preFill = { ...this.form }; // preserve anything typed while the re-read runs
    this.reading.set(true);
    this.api.reextractDocument(this.id).subscribe({
      next: () => this.loadAndPoll(0),
      error: () => this.reading.set(false),
    });
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
        // Snapshot the form the moment we start reading, so we can preserve anything the
        // user types while they wait.
        if (!this.reading()) this.preFill = { ...this.form };
        this.reading.set(true);
        setTimeout(() => this.loadAndPoll(attempt + 1), 2000);
      } else {
        const wasReading = this.reading();
        this.reading.set(false);
        this.fillForm(doc, wasReading);
        this.preFill = null;
        // Now that the read has settled (merchant/category are known), load related docs
        // off the FINAL values - not the pre-read "uncategorized" state.
        this.loadRelated();
      }
    });
  }

  /** When the document was added to Trove (the upload timestamp, stored automatically). */
  uploadedOn(): string {
    const at = this.doc()?.createdAt;
    return at ? new Date(at).toLocaleString('en-GB', { hour12: false }) : '';
  }

  // The form as it was when the AI read STARTED. Used to tell apart fields the user has
  // since typed (which we must not overwrite) from untouched ones (which the read fills).
  private preFill: typeof this.form | null = null;

  /**
   * Fill the form from a document. When [preserve] is set (the AI read landed while the
   * user may have been typing), any field the user changed since the read started is kept
   * and only the blanks are filled from the read - so a late extraction never clobbers what
   * they entered. Otherwise every field is set from the document (initial load / re-edit).
   */
  private fillForm(doc: DocumentResponse, preserve = false): void {
    const cur = this.form;
    const base = this.preFill;
    // A field counts as "user-edited" when it differs from its value at read-start.
    const kept = <K extends keyof typeof cur>(k: K, incoming: (typeof cur)[K]): (typeof cur)[K] =>
      preserve && base != null && cur[k] !== base[k] ? cur[k] : incoming;
    this.form = {
      category: kept('category', doc.category ?? ''),
      merchant: kept('merchant', doc.merchant ?? ''),
      amount: kept('amount', doc.amount),
      currency: kept('currency', doc.currency ?? 'INR'),
      docDate: kept('docDate', doc.docDate ?? ''),
      dueDate: kept('dueDate', doc.dueDate ?? ''),
      warrantyUntil: kept('warrantyUntil', (doc.extra?.['warrantyUntil'] as string) ?? ''),
      notes: kept('notes', (doc.extra?.['notes'] as string) ?? ''),
      vital: kept('vital', doc.vital),
    };
  }

  /** Set the warranty end date to N years from the document date (or today if none set). */
  setWarranty(years: number): void {
    const base = this.form.docDate ? new Date(this.form.docDate) : new Date();
    base.setFullYear(base.getFullYear() + years);
    this.form.warrantyUntil = base.toISOString().slice(0, 10);
  }

  remove(): void {
    const d = this.doc();
    if (!d) return;
    const name = d.merchant || d.originalFilename || 'this document';
    this.dialog.ask({
      title: 'Move to Trash?',
      message: `"${name}" stays recoverable in Trash for 30 days.`,
      confirmLabel: 'Move to Trash', busyLabel: 'Moving...', danger: true,
    }).then((ok) => {
      if (!ok) return;
      this.api.deleteDocument(d.id).subscribe({
        next: () => {
          this.dialog.close();
          this.notices.show({ level: 'success', code: 'DELETED', userMessage: 'Moved to Trash.' });
          this.router.navigate(['/documents']);
        },
        error: (e) => { this.dialog.close(); this.notices.show({ level: 'error', code: 'DELETE_FAIL', userMessage: e?.error?.message ?? 'Could not delete.' }); },
      });
    });
  }

  confirm(): void {
    this.saving.set(true);
    this.error.set(null);
    // Preserve existing extra (extraction trail, anomaly) and add the user's note + warranty date.
    const extra = {
      ...(this.doc()?.extra ?? {}),
      notes: this.form.notes || undefined,
      warrantyUntil: this.form.warrantyUntil || undefined,
    };
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
