import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';
import { PasswordInput } from '../../shared/components/password-input';

/** Set a new password from an emailed reset token (?token=...). */
@Component({
  selector: 'app-reset',
  imports: [FormsModule, RouterLink, PasswordInput],
  templateUrl: './reset.html',
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
