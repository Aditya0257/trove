import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/services/auth.service';

/** Request a password-reset email. Always shows the same confirmation (no account-existence leak). */
@Component({
  selector: 'app-forgot',
  imports: [FormsModule, RouterLink],
  templateUrl: './forgot.html',
  styleUrl: './forgot.scss',
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
