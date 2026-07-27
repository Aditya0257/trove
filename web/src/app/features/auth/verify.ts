import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { AuthSteps } from '../../shared/components/auth-steps';

/**
 * Email verification: a new sign-up confirms the 6-digit code we emailed before the
 * account reaches the admin for approval. Reached from register and from a login attempt
 * on an unverified account. Verification is required (an unreachable email can never
 * receive resets or reminders), so there is no skip.
 */
@Component({
  selector: 'app-verify',
  imports: [FormsModule, RouterLink, AuthSteps],
  template: `
    <div class="card auth-card">
      @if (done()) {
        <div class="success">
          <span class="badge">
            <svg viewBox="0 0 24 24" width="34" height="34" fill="none" stroke="currentColor"
              stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M5 12.5l4 4L19 7" />
            </svg>
          </span>
          <h1>You're verified</h1>
          <p class="lead">Your request has gone to the admin for a quick approval.</p>
          <p class="muted">We'll email you the moment it's approved, and then you can sign in.</p>
          <a routerLink="/login" class="btn">Back to sign in</a>
        </div>
      } @else {
        <h1>Verify your email</h1>
        <trove-auth-steps [active]="2"></trove-auth-steps>
        <p class="sent">Enter the 6-digit code we sent to <b>{{ email || 'your email' }}</b>.</p>
        <form (ngSubmit)="submit()">
          <input class="otp" type="text" inputmode="numeric" maxlength="6" name="code" [(ngModel)]="code"
            autocomplete="one-time-code" placeholder="- - - - - -" aria-label="6-digit code" required />
          @if (error()) { <p class="error">{{ error() }}</p> }
          <button type="submit" [disabled]="loading() || code.trim().length < 6">
            {{ loading() ? 'Verifying…' : 'Verify email' }}
          </button>
        </form>
        <p class="resend">
          Didn't get it?
          <button type="button" class="link" (click)="resend()" [disabled]="resending()">
            {{ resending() ? 'Sending…' : 'Resend code' }}
          </button>
          @if (resent()) { <span class="ok">Sent. Check your inbox and spam.</span> }
        </p>
        <p class="why muted">You verify your email first because Trove sends password resets and reminders
          to it. Next comes a quick admin approval, then you're in.</p>
        <p class="muted back"><a routerLink="/login">Back to sign in</a></p>
      }
    </div>
  `,
  styles: [
    `
      h1 { margin-bottom: 6px; }
      .sent { color: var(--muted); font-size: 14px; margin: 0 0 18px; line-height: 1.5; }
      /* Big, spaced one-time-code input. */
      .otp {
        width: 100%; box-sizing: border-box; text-align: center; font-size: 26px; font-weight: 700;
        letter-spacing: 10px; text-indent: 10px; font-family: ui-monospace, "SF Mono", Menlo, monospace;
        padding: 14px 0; border-radius: 12px;
      }
      .resend { font-size: 13px; color: var(--muted); margin: 14px 0 0; }
      .link { background: none; border: 0; color: var(--accent); cursor: pointer; padding: 0; font: inherit; text-decoration: underline; }
      .ok { color: var(--good, #2e7d5b); margin-left: 4px; }
      .why { font-size: 12.5px; line-height: 1.55; margin: 18px 0 10px; padding-top: 12px; border-top: 1px solid var(--line); }
      .back { margin: 0; }

      /* Success state: centered badge + tight, well-spaced copy. */
      .success { text-align: center; padding: 8px 0 4px; }
      .success .badge {
        display: inline-flex; align-items: center; justify-content: center; width: 68px; height: 68px;
        border-radius: 50%; background: var(--accent); color: var(--brand-ink, #fff); margin: 8px 0 14px;
        box-shadow: 0 0 0 8px color-mix(in srgb, var(--accent) 16%, transparent);
      }
      .success h1 { margin: 0 0 8px; }
      .success .lead { font-size: 16px; line-height: 1.5; margin: 0 0 6px; }
      .success .muted { font-size: 14px; line-height: 1.5; margin: 0 auto 20px; max-width: 42ch; }
      .success .btn {
        display: inline-block; text-decoration: none; background: var(--accent); color: var(--brand-ink, #fff);
        padding: 10px 22px; border-radius: 10px; font-weight: 600; font-size: 14px;
      }
      .success .btn:hover { filter: brightness(1.05); }
    `,
  ],
})
export class Verify {
  private auth = inject(AuthService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);

  email = this.route.snapshot.queryParamMap.get('email') ?? '';
  code = '';
  error = signal<string | null>(null);
  loading = signal(false);
  resending = signal(false);
  resent = signal(false);
  done = signal(false);

  submit(): void {
    if (!this.email) {
      this.error.set('We lost track of your email. Please sign up or sign in again.');
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    this.auth.verifyEmail(this.email, this.code.trim()).subscribe({
      next: (r) => {
        if (r.token) {
          this.router.navigate(['/documents']); // open registration or the admin's own account
        } else {
          this.done.set(true); // verified, now awaiting admin approval
          this.loading.set(false);
        }
      },
      error: () => {
        this.error.set('That code is incorrect or expired. Check it, or resend a new one.');
        this.loading.set(false);
      },
    });
  }

  resend(): void {
    if (!this.email) return;
    this.resending.set(true);
    this.resent.set(false);
    // Always report sent (the backend never reveals whether an email is registered).
    this.auth.resendVerification(this.email).subscribe({
      next: () => { this.resending.set(false); this.resent.set(true); },
      error: () => { this.resending.set(false); this.resent.set(true); },
    });
  }
}
