import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { PasswordInput } from '../../core/password-input';

@Component({
  selector: 'app-register',
  imports: [FormsModule, RouterLink, PasswordInput],
  template: `
    <div class="card auth-card">
      <h1>Create your Trove</h1>
      @if (pending()) {
        <p>✅ Thanks, {{ displayName }}. Your request has been sent to the admin for approval.
          You'll be able to sign in once it's approved.</p>
        <p class="muted"><a routerLink="/login">Back to sign in</a></p>
      } @else {
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
      }
    </div>
  `,
})
export class Register {
  private auth = inject(AuthService);
  private router = inject(Router);

  displayName = '';
  email = '';
  password = '';
  error = signal<string | null>(null);
  loading = signal(false);
  pending = signal(false);

  submit(): void {
    this.loading.set(true);
    this.error.set(null);
    this.auth.register(this.email, this.displayName, this.password).subscribe({
      next: (r) => {
        if (r.token) {
          this.router.navigate(['/documents']);   // open registration or the admin's own account
        } else {
          this.pending.set(true);                  // awaiting admin approval
          this.loading.set(false);
        }
      },
      error: (e) => {
        this.error.set(e?.error?.message ?? 'Registration failed');
        this.loading.set(false);
      },
    });
  }
}
