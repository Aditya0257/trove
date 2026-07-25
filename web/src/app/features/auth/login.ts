import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { PasswordInput } from '../../core/password-input';

@Component({
  selector: 'app-login',
  imports: [FormsModule, RouterLink, PasswordInput],
  template: `
    <div class="card auth-card">
      <h1>Sign in to Trove</h1>
      <form (ngSubmit)="submit()">
        <label>Email
          <input type="email" name="email" [(ngModel)]="email" required autocomplete="email" />
        </label>
        <label>Password
          <trove-password name="password" autocomplete="current-password" [(ngModel)]="password"></trove-password>
        </label>
        @if (twoFactor()) {
          <label>Authenticator code
            <input type="text" name="code" [(ngModel)]="code" inputmode="numeric" autocomplete="one-time-code"
                   placeholder="6-digit code" maxlength="6" />
          </label>
        }
        @if (error()) { <p class="error">{{ error() }}</p> }
        <button type="submit" [disabled]="loading()">{{ loading() ? 'Signing in…' : 'Sign in' }}</button>
      </form>
      <p class="muted"><a routerLink="/forgot">Forgot password?</a></p>
      <p class="muted">No account? <a routerLink="/register">Create one</a></p>
    </div>
  `,
})
export class Login {
  private auth = inject(AuthService);
  private router = inject(Router);

  email = '';
  password = '';
  code = '';
  twoFactor = signal(false);
  error = signal<string | null>(null);
  loading = signal(false);

  submit(): void {
    this.loading.set(true);
    this.error.set(null);
    this.auth.login(this.email, this.password, this.code || undefined).subscribe({
      next: (r) => {
        if (r.status === 'unverified') {
          // Email never confirmed: send them to enter the code (resendable there).
          this.router.navigate(['/verify'], { queryParams: { email: this.email } });
          return;
        }
        if (r.status && r.status !== 'active') {
          this.error.set(r.status === 'rejected'
            ? 'This account request was declined.'
            : 'Your account is awaiting admin approval. You can sign in once it is approved.');
          this.loading.set(false);
          return;
        }
        if (r.twoFactorRequired) {
          // Password verified; now ask for the authenticator code and resubmit.
          this.twoFactor.set(true);
          this.loading.set(false);
          return;
        }
        this.router.navigate(['/documents']);
      },
      error: (e) => {
        this.error.set(e?.error?.message ?? 'Login failed');
        this.loading.set(false);
      },
    });
  }
}
