import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { PasswordInput } from '../../shared/components/password-input';
import { AuthSteps } from '../../shared/components/auth-steps';

@Component({
  selector: 'app-register',
  imports: [FormsModule, RouterLink, PasswordInput, AuthSteps],
  templateUrl: './register.html',
  styleUrl: './register.scss',
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
