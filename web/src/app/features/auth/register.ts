import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { PasswordInput } from '../../core/password-input';
import { AuthSteps } from '../../core/auth-steps';

@Component({
  selector: 'app-register',
  imports: [FormsModule, RouterLink, PasswordInput, AuthSteps],
  template: `
    <div class="card auth-card">
      <h1>Create your Trove</h1>
      <trove-auth-steps [active]="1"></trove-auth-steps>
      <p class="muted flow">Three quick steps: enter your details, verify your email with a code we send you,
        then a short admin approval. You'll sign in once you're approved.</p>
      <form (ngSubmit)="submit()">
        <label>Display name
          <input type="text" name="displayName" [(ngModel)]="displayName" required />
        </label>
        <label>Email
          <input type="email" name="email" [(ngModel)]="email" required autocomplete="email" />
        </label>
        <label>Password (min 8 chars)
          <trove-password name="password" autocomplete="new-password" [minlength]="8" [(ngModel)]="password"></trove-password>
        </label>
        @if (error()) { <p class="error">{{ error() }}</p> }
        <button type="submit" [disabled]="loading()">{{ loading() ? 'Creating…' : 'Create account' }}</button>
      </form>
      <p class="muted">Already have an account? <a routerLink="/login">Sign in</a></p>
    </div>
  `,
  styles: [`.flow { font-size: 13px; line-height: 1.5; margin: -4px 0 16px; }`],
})
export class Register {
  private auth = inject(AuthService);
  private router = inject(Router);

  displayName = '';
  email = '';
  password = '';
  error = signal<string | null>(null);
  loading = signal(false);

  submit(): void {
    this.loading.set(true);
    this.error.set(null);
    this.auth.register(this.email, this.displayName, this.password).subscribe({
      next: (r) => {
        if (r.token) {
          this.router.navigate(['/documents']);   // open registration or the admin's own account
        } else {
          // New account is 'unverified': go enter the emailed code.
          this.router.navigate(['/verify'], { queryParams: { email: this.email } });
        }
      },
      error: (e) => {
        this.error.set(e?.error?.message ?? 'Registration failed');
        this.loading.set(false);
      },
    });
  }
}
