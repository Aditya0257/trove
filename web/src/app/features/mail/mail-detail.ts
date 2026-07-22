import { Component, inject, signal } from '@angular/core';
import { Location } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { SpaceContext } from '../../core/space.context';
import { NoticeService } from '../../core/notice/notice.service';
import { DocumentResponse } from '../../core/models';

/**
 * Mail detail — an email as one thing, not a stray receipt. Shows every screenshot in
 * the bundle (each openable), and edits the email's own fields (account, subject, date,
 * notes) rather than the generic bill form. Save applies to all screenshots in the
 * bundle; Delete removes the whole email.
 */
@Component({
  selector: 'app-mail-detail',
  imports: [FormsModule],
  template: `
    <div class="card">
      <button class="back" type="button" (click)="back()">← Back to Mail</button>
      <h1>Email</h1>

      @if (loading()) {
        <p class="muted">Loading…</p>
      } @else if (!docs().length) {
        <p class="muted">This email couldn't be found.</p>
      } @else {
        <div class="shots">
          @for (d of docs(); track d.id) {
            <button type="button" class="shot" (click)="openShot(d)" title="Open full screenshot">
              <img [src]="thumb(d)" alt="screenshot" />
            </button>
          }
        </div>
        <p class="muted small">{{ docs().length }} screenshot(s). Click any to view it full-size.</p>

        <form (ngSubmit)="save()">
          <label>
            <span class="lbl">Account</span>
            <input name="account" [(ngModel)]="form.account" list="mailAccounts" placeholder="Personal / Office" />
            <datalist id="mailAccounts"><option value="Personal"></option><option value="Office"></option></datalist>
          </label>
          <label>
            <span class="lbl">Subject</span>
            <input name="subject" [(ngModel)]="form.subject" placeholder="What the email is about" />
          </label>
          <label>
            <span class="lbl">Email date</span>
            <input type="date" name="mdate" [(ngModel)]="form.date" />
          </label>
          <label>
            <span class="lbl">Notes / description (optional)</span>
            <textarea name="notes" [(ngModel)]="form.notes" rows="2"
              placeholder="Anything to remember or find this by later"></textarea>
          </label>
          <div class="actions">
            <button type="submit" [disabled]="saving()">{{ saving() ? 'Saving…' : 'Save changes' }}</button>
            <button type="button" class="btn-del" (click)="remove()">Delete email</button>
          </div>
        </form>
      }
    </div>
  `,
  styles: [
    `
      .back { border: 0; background: transparent; color: #2f6f6a; cursor: pointer; font-size: 13px; padding: 0; margin-bottom: 10px; }
      .back:hover { text-decoration: underline; }
      .shots { display: flex; flex-wrap: wrap; gap: 10px; margin: 8px 0 4px; }
      .shot { padding: 0; border: 1px solid #e2e2e2; border-radius: 8px; background: transparent; cursor: pointer; overflow: hidden; }
      .shot img { display: block; width: 120px; height: 120px; object-fit: cover; }
      .shot:hover { border-color: #2f6f6a; }
      .small { font-size: 12px; }
      .lbl { display: block; margin-bottom: 2px; }
      textarea { width: 100%; box-sizing: border-box; resize: vertical; font-family: inherit; padding: 8px; }
      .actions { display: flex; gap: 12px; align-items: center; margin-top: 10px; }
      .btn-del {
        border: 1px solid rgba(192, 57, 43, 0.5); background: transparent; color: #c0392b;
        border-radius: 8px; padding: 9px 18px; font-size: 14px; cursor: pointer;
      }
      .btn-del:hover { background: rgba(192, 57, 43, 0.08); }
    `,
  ],
})
export class MailDetail {
  private api = inject(ApiService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private spaceCtx = inject(SpaceContext);
  private notices = inject(NoticeService);
  private location = inject(Location);

  private bundleId = '';
  docs = signal<DocumentResponse[]>([]);
  loading = signal(true);
  saving = signal(false);
  form = { account: '', subject: '', date: '', notes: '' };

  ngOnInit(): void {
    this.bundleId = this.route.snapshot.paramMap.get('bundleId') ?? '';
    this.api.listDocuments(this.spaceCtx.currentSpaceId(), 'email').subscribe({
      next: (all) => {
        const mine = all.filter((d) => (d.extra?.['mailBundleId'] as string) === this.bundleId);
        this.docs.set(mine);
        const first = mine[0];
        if (first) {
          const e = first.extra ?? {};
          this.form = {
            account: (e['mailAccount'] as string) ?? '',
            subject: (e['mailSubject'] as string) ?? '',
            date: (e['mailDate'] as string) ?? first.docDate ?? '',
            notes: (e['notes'] as string) ?? '',
          };
        }
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  back(): void {
    this.location.back();
  }

  thumb(d: DocumentResponse): string {
    return this.api.fileUrl(d) ?? '';
  }

  openShot(d: DocumentResponse): void {
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

  save(): void {
    this.saving.set(true);
    let done = 0;
    const list = this.docs();
    for (const d of list) {
      const extra = {
        ...(d.extra ?? {}),
        mailAccount: this.form.account,
        mailSubject: this.form.subject,
        mailDate: this.form.date,
        notes: this.form.notes || undefined,
      };
      this.api
        .confirmDocument(d.id, { category: 'email', docDate: this.form.date || undefined, extra })
        .subscribe({
          next: () => {
            if (++done === list.length) {
              this.notices.show({ level: 'success', code: 'MAIL_SAVED', userMessage: 'Email updated.' });
              this.saving.set(false);
              this.router.navigate(['/mail']);
            }
          },
          error: () => this.saving.set(false),
        });
    }
  }

  remove(): void {
    if (!confirm(`Delete this email and its ${this.docs().length} screenshot(s)?`)) {
      return;
    }
    let done = 0;
    const list = this.docs();
    for (const d of list) {
      this.api.deleteDocument(d.id).subscribe({
        next: () => {
          if (++done === list.length) {
            this.notices.show({ level: 'success', code: 'DELETED', userMessage: 'Email deleted.' });
            this.router.navigate(['/mail']);
          }
        },
      });
    }
  }
}
