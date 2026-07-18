import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { Category, ConfirmRequest, DocumentResponse } from '../../core/models';

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
          <p class="muted">📖 Reading the document… fields will fill in automatically.</p>
        } @else {
          <p class="muted">
            Read by <b>{{ provider() }}</b>, confidence {{ confidencePct() }}. Check the
            values below — nothing is trusted until you confirm.
          </p>
        }
        @if (anomaly()) { <p class="warn">⚠️ This looks higher than usual for its category.</p> }

        <p><button class="link" type="button" (click)="openFile()">View original file →</button></p>

        <form (ngSubmit)="confirm()">
          <label>Category
            <select name="category" [(ngModel)]="form.category">
              @for (c of categories(); track c.code) { <option [value]="c.code">{{ c.label }}</option> }
            </select>
          </label>
          <label>Merchant <input name="merchant" [(ngModel)]="form.merchant" /></label>
          <div class="row">
            <label>Amount <input type="number" step="0.01" name="amount" [(ngModel)]="form.amount" /></label>
            <label>Currency <input name="currency" [(ngModel)]="form.currency" /></label>
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
            {{ saving() ? 'Confirming…' : 'Confirm' }}
          </button>
        </form>
      </div>
    }
  `,
})
export class Review {
  private api = inject(ApiService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);

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

  provider(): string {
    return (this.doc()?.extra?.['extractionProvider'] as string) ?? 'the extractor';
  }

  confidencePct(): string {
    const c = this.doc()?.extractionConfidence;
    return c != null ? `${Math.round(c * 100)}%` : '—';
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
      next: () => this.router.navigate(['/documents']),
      error: (e) => {
        this.error.set(e?.error?.message ?? 'Confirm failed');
        this.saving.set(false);
      },
    });
  }
}
