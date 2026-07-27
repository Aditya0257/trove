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
  templateUrl: './verify.html',
  styleUrl: './verify.scss',
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
