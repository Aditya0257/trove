import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { tap } from 'rxjs';
import { API_BASE } from './config';
import { AuthResponse, PendingUser } from './models';

const TOKEN_KEY = 'trove_token';
const USER_KEY = 'trove_user';

/**
 * Holds the JWT + current user, persisted in localStorage so a refresh keeps you
 * logged in. Login/register hit the public /api/auth endpoints.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private http = inject(HttpClient);

  readonly token = signal<string | null>(localStorage.getItem(TOKEN_KEY));
  readonly user = signal<AuthResponse | null>(readUser());
  readonly isLoggedIn = computed(() => !!this.token());

  login(email: string, password: string, code?: string) {
    return this.http
      .post<AuthResponse>(`${API_BASE}/api/auth/login`, { email, password, code })
      // Only persist a real session; a twoFactorRequired response carries no token.
      .pipe(tap((r) => { if (r.token) this.store(r); }));
  }

  // --- password reset ---
  forgotPassword(email: string) {
    return this.http.post<void>(`${API_BASE}/api/auth/forgot-password`, { email });
  }
  resetPassword(token: string, newPassword: string) {
    return this.http.post<void>(`${API_BASE}/api/auth/reset-password`, { token, newPassword });
  }

  // --- TOTP 2FA (authenticated) ---
  twoFactorSetup() {
    return this.http.post<{ secret: string; otpauthUri: string }>(`${API_BASE}/api/account/2fa/setup`, {});
  }
  twoFactorEnable(code: string) {
    return this.http.post<void>(`${API_BASE}/api/account/2fa/enable`, { code });
  }
  twoFactorDisable(code: string) {
    return this.http.post<void>(`${API_BASE}/api/account/2fa/disable`, { code });
  }
  twoFactorStatus() {
    return this.http.get<{ enabled: boolean }>(`${API_BASE}/api/account/2fa/status`);
  }

  register(email: string, displayName: string, password: string) {
    return this.http
      .post<AuthResponse>(`${API_BASE}/api/auth/register`, { email, displayName, password })
      // A new sign-up is always 'unverified' with no token; the client sends them to verify.
      .pipe(tap((r) => { if (r.token) this.store(r); }));
  }

  // --- email verification (OTP) ---
  verifyEmail(email: string, code: string) {
    return this.http
      .post<AuthResponse>(`${API_BASE}/api/auth/verify-email`, { email, code })
      // Verifying may return a token (open registration/admin) or a pending status.
      .pipe(tap((r) => { if (r.token) this.store(r); }));
  }
  resendVerification(email: string) {
    return this.http.post<void>(`${API_BASE}/api/auth/resend-verification`, { email });
  }

  // --- admin: approve/decline new signups ---
  adminPending() {
    return this.http.get<PendingUser[]>(`${API_BASE}/api/admin/pending`);
  }
  adminApprove(id: string) {
    return this.http.post<void>(`${API_BASE}/api/admin/users/${id}/approve`, {});
  }
  adminReject(id: string) {
    return this.http.post<void>(`${API_BASE}/api/admin/users/${id}/reject`, {});
  }

  logout(): void {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    this.token.set(null);
    this.user.set(null);
  }

  private store(r: AuthResponse): void {
    localStorage.setItem(TOKEN_KEY, r.token!);
    localStorage.setItem(USER_KEY, JSON.stringify(r));
    this.token.set(r.token);
    this.user.set(r);
  }
}

function readUser(): AuthResponse | null {
  const raw = localStorage.getItem(USER_KEY);
  return raw ? (JSON.parse(raw) as AuthResponse) : null;
}
