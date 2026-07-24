import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';

/** Request a password-reset email. Always shows the same confirmation (no account-existence leak). */
@Component({
  selector: 'app-forgot',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="card auth-card">
      <h1>Reset your password</h1>
      @if (sent()) {
        <p>If an account exists for <b>{{ email }}</b>, we've emailed a reset link. It expires in 30 minutes and works once.</p>
        <p class="muted">Didn't get it? Check spam, or <a (click)="sent.set(false)" class="link-inline">try again</a>.</p>
        <p class="muted"><a routerLink="/login">Back to sign in</a></p>
      } @else {
        <p class="muted">Enter your email and we'll send a link to set a new password. Your email (username) stays the same.</p>
        <form (ngSubmit)="submit()">
          <label>Email
            <input type="email" name="email" [(ngModel)]="email" required autocomplete="email" />
          </label>
          @if (error()) { <p class="error">{{ error() }}</p> }
          <button type="submit" [disabled]="loading() || !email">{{ loading() ? 'Sending…' : 'Send reset link' }}</button>
        </form>
        <p class="muted"><a routerLink="/login">Back to sign in</a></p>
      }
    </div>
  `,
  styles: [`.link-inline { cursor: pointer; color: var(--accent); }`],
})
export class Forgot {
  private auth = inject(AuthService);

  email = '';
  sent = signal(false);
  loading = signal(false);
  error = signal<string | null>(null);

  submit(): void {
    if (!this.email) return;
    this.loading.set(true);
    this.error.set(null);
    this.auth.forgotPassword(this.email).subscribe({
      next: () => { this.sent.set(true); this.loading.set(false); },
      error: () => { this.error.set('Could not send the email. Please try again.'); this.loading.set(false); },
    });
  }
}
