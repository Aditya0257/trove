import { Component, inject, signal } from '@angular/core';
import { Location } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { SpaceContext } from '../../core/space.context';
import { NoticeService } from '../../core/notice/notice.service';
import { ConfirmService } from '../../core/confirm.service';
import { DocumentResponse } from '../../core/models';

/**
 * Mail detail - an email as one thing, not a stray receipt. Shows every screenshot in
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

        @if (hasReadText()) {
          <details class="read">
            <summary>Text read by AI (makes this email searchable)</summary>
            @for (d of docs(); track d.id) {
              @if (d.rawText) { <pre class="rawtext">{{ d.rawText }}</pre> }
            }
          </details>
        }

        <form (ngSubmit)="save()">
          <label>
            <span class="lbl">Account <span class="tip" tabindex="0">i<span class="bubble">{{ tips.account }}</span></span></span>
            <input name="account" [(ngModel)]="form.account" list="mailAccounts" placeholder="Personal / Office" />
            <datalist id="mailAccounts"><option value="Personal"></option><option value="Office"></option></datalist>
          </label>
          <label>
            <span class="lbl">Email address (inbox) <span class="tip" tabindex="0">i<span class="bubble">{{ tips.address }}</span></span></span>
            <input name="address" type="email" [(ngModel)]="form.address" placeholder="e.g. you@work.com" />
          </label>
          <label>
            <span class="lbl">Topic / sender <span class="tip" tabindex="0">i<span class="bubble">{{ tips.topic }}</span></span></span>
            <input name="topic" [(ngModel)]="form.topic" placeholder="e.g. Plum Insurance, HDFC Bank, Amazon" />
          </label>
          <label>
            <span class="lbl">Subject <span class="tip" tabindex="0">i<span class="bubble">{{ tips.subject }}</span></span></span>
            <input name="subject" [(ngModel)]="form.subject" placeholder="Exact subject line of the email" />
          </label>
          <label>
            <span class="lbl">Email date <span class="tip" tabindex="0">i<span class="bubble">{{ tips.date }}</span></span></span>
            <input type="date" name="mdate" [(ngModel)]="form.date" />
          </label>
          <label>
            <span class="lbl">Notes / description (optional) <span class="tip" tabindex="0">i<span class="bubble">{{ tips.notes }}</span></span></span>
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
      .back { border: 0; background: transparent; color: var(--accent); cursor: pointer; font-size: 13px; padding: 0; margin-bottom: 10px; }
      .back:hover { text-decoration: underline; }
      .shots { display: flex; flex-wrap: wrap; gap: 10px; margin: 8px 0 4px; }
      .shot { padding: 0; border: 1px solid var(--line); border-radius: 8px; background: transparent; cursor: pointer; overflow: hidden; }
      .shot img { display: block; width: 120px; height: 120px; object-fit: cover; }
      .shot:hover { border-color: var(--accent); }
      .small { font-size: 12px; }
      .read { margin: 6px 0 14px; }
      .read summary { cursor: pointer; font-weight: 600; font-size: 13px; color: var(--accent); }
      .read .rawtext {
        background: var(--code-bg); color: var(--ink); border-radius: 8px; padding: 10px; font-size: 12px;
        line-height: 1.5; white-space: pre-wrap; word-break: break-word; max-height: 280px; overflow-y: auto; margin: 8px 0 0;
      }
      .lbl { display: inline-flex; align-items: center; margin-bottom: 2px; }
      /* Salesforce-style field help: a round "i" that reveals a bubble on hover/focus. */
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
      textarea { width: 100%; box-sizing: border-box; resize: vertical; font-family: inherit; padding: 8px; }
      .actions { display: flex; gap: 12px; align-items: center; margin-top: 10px; }
      .btn-del {
        border: 1px solid var(--danger-line); background: transparent; color: var(--danger);
        border-radius: 8px; padding: 9px 18px; font-size: 14px; cursor: pointer;
      }
      .btn-del:hover { background: var(--danger-soft); }
    `,
  ],
})
export class MailDetail {
  private api = inject(ApiService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private spaceCtx = inject(SpaceContext);
  private notices = inject(NoticeService);
  private confirm = inject(ConfirmService);
  private location = inject(Location);

  private bundleId = '';
  docs = signal<DocumentResponse[]>([]);
  loading = signal(true);
  saving = signal(false);
  form = { account: '', address: '', topic: '', subject: '', date: '', notes: '' };

  /** Field help - shown on hover/focus of the info icon (Salesforce-style). */
  readonly tips = {
    account: 'A short label to group your inboxes, like Personal or Office.',
    address: "The email address whose inbox this is in, e.g. you@work.com, so you know exactly which inbox to open and search later.",
    topic: 'The stable thing this is about (e.g. Plum Insurance). Groups emails together even when their subject lines change over time.',
    subject: "The exact subject line, copied as-is. Prefer the exact text, so you can paste it straight into that inbox's search to find the original email later.",
    date: 'The date the email arrived (as shown in your inbox).',
    notes: 'Anything extra you want to remember or find this by later, in your own words.',
  };

  ngOnInit(): void {
    this.bundleId = this.route.snapshot.paramMap.get('bundleId') ?? '';
    this.api.mailBundle(this.spaceCtx.currentSpaceId(), this.bundleId).subscribe({
      next: (mine) => {
        this.docs.set(mine);
        const first = mine[0];
        if (first) {
          const e = first.extra ?? {};
          this.form = {
            account: (e['mailAccount'] as string) ?? '',
            address: (e['mailAddress'] as string) ?? '',
            topic: (e['mailTopic'] as string) ?? '',
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

  /** True if AI read any of these screenshots (so there's captured text to show). */
  hasReadText(): boolean {
    return this.docs().some((d) => !!d.rawText);
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
        mailAddress: this.form.address,
        mailTopic: this.form.topic,
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
    this.confirm.ask({
      title: 'Delete this email?',
      message: `This moves the email and its ${this.docs().length} screenshot(s) to Trash.`,
      confirmLabel: 'Delete', busyLabel: 'Deleting...', danger: true,
    }).then((ok) => {
      if (!ok) return;
      let done = 0;
      const list = this.docs();
      for (const d of list) {
        this.api.deleteDocument(d.id).subscribe({
          next: () => {
            if (++done === list.length) {
              this.confirm.close();
              this.notices.show({ level: 'success', code: 'DELETED', userMessage: 'Email deleted.' });
              this.router.navigate(['/mail']);
            }
          },
          error: (e) => { this.confirm.close(); this.notices.show({ level: 'error', code: 'DELETE_FAIL', userMessage: e?.error?.message ?? 'Could not delete.' }); },
        });
      }
    });
  }
}
