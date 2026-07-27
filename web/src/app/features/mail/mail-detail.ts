import { Component, inject, signal } from '@angular/core';
import { Location } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { SpaceContext } from '../../core/services/space.context';
import { NoticeService } from '../../core/services/notice.service';
import { ConfirmService } from '../../core/services/confirm.service';
import { DocumentResponse } from '../../core/models/models';

/**
 * Mail detail - an email as one thing, not a stray receipt. Shows every screenshot in
 * the bundle (each openable), and edits the email's own fields (account, subject, date,
 * notes) rather than the generic bill form. Save applies to all screenshots in the
 * bundle; Delete removes the whole email.
 */
@Component({
  selector: 'app-mail-detail',
  imports: [FormsModule],
  templateUrl: './mail-detail.html',
  styleUrl: './mail-detail.scss',
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
