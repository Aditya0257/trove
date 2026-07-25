import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { AuthSteps } from '../../core/auth-steps';

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
      <h1>Verify your email</h1>
      <trove-auth-steps [active]="done() ? 3 : 2"></trove-auth-steps>
      @if (done()) {
        <p>✅ Email verified. Step 3 of 3: your request has been sent to the admin for approval.
          You'll be able to sign in once it's approved (we'll email you then).</p>
        <p class="muted"><a routerLink="/login">Back to sign in</a></p>
      } @else {
        <p class="muted">We sent a 6-digit code to <b>{{ email || 'your email' }}</b>. Enter it below to
          confirm this address is yours.</p>
        <div class="why">
          <b>How sign-up works:</b> after you enter this code, your account goes to the admin for a
          quick approval, and you can sign in once approved. We verify your email first because Trove
          sends password resets and reminder nudges to it, so it must be real and reachable. This step
          is required, not optional. If the code has not arrived in a minute, check spam or resend below.
        </div>
        <form (ngSubmit)="submit()">
          <label>6-digit code
            <input type="text" inputmode="numeric" maxlength="6" name="code" [(ngModel)]="code"
              autocomplete="one-time-code" required />
          </label>
          @if (error()) { <p class="error">{{ error() }}</p> }
          <button type="submit" [disabled]="loading() || code.trim().length < 6">
            {{ loading() ? 'Verifying…' : 'Verify email' }}
          </button>
        </form>
        <p class="muted">
          Didn't get it?
          <button type="button" class="link" (click)="resend()" [disabled]="resending()">
            {{ resending() ? 'Sending…' : 'Resend code' }}
          </button>
          @if (resent()) { <span class="ok"> Sent, check your inbox and spam.</span> }
        </p>
        <p class="muted"><a routerLink="/login">Back to sign in</a></p>
      }
    </div>
  `,
  styles: [
    `
      .why {
        background: var(--accent-soft); border: 1px solid var(--accent-line); border-radius: 10px;
        padding: 10px 12px; font-size: 13px; line-height: 1.5; margin: 6px 0 14px;
      }
      .link { background: none; border: 0; color: var(--accent); cursor: pointer; padding: 0; font: inherit; text-decoration: underline; }
      .ok { color: var(--good, #2e7d5b); }
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
