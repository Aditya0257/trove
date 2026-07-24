import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { PasswordInput } from '../../core/password-input';

/** Set a new password from an emailed reset token (?token=...). */
@Component({
  selector: 'app-reset',
  imports: [FormsModule, RouterLink, PasswordInput],
  template: `
    <div class="card auth-card">
      <h1>Choose a new password</h1>
      @if (!token) {
        <p class="error">This reset link is missing its token. Request a new one.</p>
        <p class="muted"><a routerLink="/forgot">Reset password</a></p>
      } @else if (done()) {
        <p>✅ Your password has been reset. You can sign in with it now.</p>
        <p class="muted"><a routerLink="/login">Go to sign in</a></p>
      } @else {
        <form (ngSubmit)="submit()">
          <label>New password (min 8 chars)
            <trove-password name="newPassword" autocomplete="new-password" [minlength]="8" [(ngModel)]="password"></trove-password>
          </label>
          <label>Confirm new password
            <trove-password name="confirm" autocomplete="new-password" [(ngModel)]="confirm"></trove-password>
          </label>
          @if (error()) { <p class="error">{{ error() }}</p> }
          <button type="submit" [disabled]="loading()">{{ loading() ? 'Saving…' : 'Set new password' }}</button>
        </form>
      }
    </div>
  `,
})
export class Reset {
  private auth = inject(AuthService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);

  token = this.route.snapshot.queryParamMap.get('token') ?? '';
  password = '';
  confirm = '';
  done = signal(false);
  loading = signal(false);
  error = signal<string | null>(null);

  submit(): void {
    this.error.set(null);
    if (this.password.length < 8) { this.error.set('Password must be at least 8 characters.'); return; }
    if (this.password !== this.confirm) { this.error.set('The two passwords do not match.'); return; }
    this.loading.set(true);
    this.auth.resetPassword(this.token, this.password).subscribe({
      next: () => { this.done.set(true); this.loading.set(false); },
      error: (e) => { this.error.set(e?.error?.message ?? 'This reset link is invalid or has expired.'); this.loading.set(false); },
    });
  }
}
