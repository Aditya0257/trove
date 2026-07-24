import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../core/auth.service';
import { NoticeService } from '../../core/notice/notice.service';
import { HelpCard } from '../../core/help-card';

/** Account security: enrol / remove authenticator-app two-factor (TOTP). */
@Component({
  selector: 'app-security',
  imports: [FormsModule, HelpCard],
  template: `
    <div class="card">
      <h1>Security</h1>
      <trove-help-card title="About two-factor" [open]="false"
        user="Two-factor adds a second step at sign-in: a 6-digit code from an authenticator app (Google Authenticator, Authy, and similar) that changes every 30 seconds. Even if someone learns your password, they can't sign in without your phone."
        dev="TOTP (RFC 6238): a shared secret produces a time-based code (HMAC-SHA1 over the 30s step). It runs fully offline in the app, so it needs no SMS and costs nothing. The secret is AES-GCM encrypted at rest; enabling requires proving one valid code so a bad setup can't lock you out.">
      </trove-help-card>

      @if (enabled()) {
        <p class="status on">✅ Two-factor is <b>on</b> for your account.</p>
        <p class="muted">To turn it off, enter a current code from your authenticator app.</p>
        <form class="inline" (ngSubmit)="disable()">
          <input name="offcode" [(ngModel)]="offCode" inputmode="numeric" maxlength="6" placeholder="6-digit code" />
          <button type="submit" class="btn-danger" [disabled]="busy()">Turn off</button>
        </form>
      } @else if (setup()) {
        <p class="muted">1. In your authenticator app, add an account and enter this key (or the setup link):</p>
        <div class="secret">{{ setup()!.secret }}</div>
        <p class="muted small"><a [href]="setup()!.otpauthUri">Open in an authenticator app</a> (on this device)</p>
        <p class="muted">2. Enter the 6-digit code it shows to finish:</p>
        <form class="inline" (ngSubmit)="enable()">
          <input name="oncode" [(ngModel)]="onCode" inputmode="numeric" maxlength="6" placeholder="6-digit code" />
          <button type="submit" [disabled]="busy()">Verify &amp; turn on</button>
        </form>
        @if (error()) { <p class="error">{{ error() }}</p> }
      } @else {
        <p class="status off">Two-factor is <b>off</b>.</p>
        <button type="button" (click)="startSetup()" [disabled]="busy()">Set up two-factor</button>
      }
    </div>
  `,
  styles: [
    `
      .status { font-size: 15px; }
      .status.on { color: var(--accent); }
      .inline { display: flex; gap: 8px; align-items: center; margin-top: 8px; flex-wrap: wrap; }
      .inline input { margin: 0; width: 150px; font-family: monospace; letter-spacing: 0.15em; }
      .inline button { margin: 0; }
      .secret {
        font-family: monospace; font-size: 18px; letter-spacing: 0.12em; user-select: all;
        background: var(--code-bg); border: 1px solid var(--line); border-radius: 8px;
        padding: 10px 14px; display: inline-block; margin: 6px 0;
      }
      .small { font-size: 12px; }
      .btn-danger { background: var(--danger); color: #fff; border: 0; }
    `,
  ],
})
export class Security {
  private auth = inject(AuthService);
  private notices = inject(NoticeService);

  enabled = signal(false);
  setup = signal<{ secret: string; otpauthUri: string } | null>(null);
  onCode = '';
  offCode = '';
  busy = signal(false);
  error = signal<string | null>(null);

  constructor() {
    this.auth.twoFactorStatus().subscribe((s) => this.enabled.set(s.enabled));
  }

  startSetup(): void {
    this.busy.set(true);
    this.error.set(null);
    this.auth.twoFactorSetup().subscribe({
      next: (s) => { this.setup.set(s); this.busy.set(false); },
      error: () => { this.error.set('Could not start setup. Please try again.'); this.busy.set(false); },
    });
  }

  enable(): void {
    this.busy.set(true);
    this.error.set(null);
    this.auth.twoFactorEnable(this.onCode).subscribe({
      next: () => {
        this.enabled.set(true); this.setup.set(null); this.onCode = ''; this.busy.set(false);
        this.notices.show({ level: 'success', code: '2FA_ON', userMessage: 'Two-factor is now on.' });
      },
      error: (e) => { this.error.set(e?.error?.message ?? 'That code did not match.'); this.busy.set(false); },
    });
  }

  disable(): void {
    this.busy.set(true);
    this.auth.twoFactorDisable(this.offCode).subscribe({
      next: () => {
        this.enabled.set(false); this.offCode = ''; this.busy.set(false);
        this.notices.show({ level: 'info', code: '2FA_OFF', userMessage: 'Two-factor has been turned off.' });
      },
      error: (e) => {
        this.busy.set(false);
        this.notices.show({ level: 'error', code: '2FA_OFF_FAIL', userMessage: e?.error?.message ?? 'Could not turn off 2FA.' });
      },
    });
  }
}
