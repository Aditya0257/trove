import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { PasswordInput } from '../../shared/components/password-input';

@Component({
  selector: 'app-login',
  imports: [FormsModule, RouterLink, PasswordInput],
  templateUrl: './login.html',
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
