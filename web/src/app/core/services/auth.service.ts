import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { tap } from 'rxjs';
import { API_BASE } from '../config/config';
import { AccountResponse, AdminUser, AuthResponse, PendingUser } from '../models/models';

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

  /** The signed-in user's profile (name + photo), loaded after login so the nav avatar and
   *  account screen stay in sync. Null until fetched or when logged out. */
  readonly account = signal<AccountResponse | null>(null);

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

  // --- admin: full user list + delete account (wipes all data) ---
  adminUsers() {
    return this.http.get<AdminUser[]>(`${API_BASE}/api/admin/users`);
  }
  adminDeleteUser(id: string, confirmEmail: string) {
    return this.http.post<void>(`${API_BASE}/api/admin/users/${id}/delete`, { confirmEmail });
  }

  // --- profile / account (authenticated) ---
  /** Loads the profile and caches it in `account` for the nav avatar. */
  loadAccount() {
    return this.http.get<AccountResponse>(`${API_BASE}/api/account/me`)
      .pipe(tap((a) => this.account.set(a)));
  }
  updateDisplayName(displayName: string) {
    return this.http.post<AccountResponse>(`${API_BASE}/api/account/profile`, { displayName })
      .pipe(tap((a) => this.applyAccount(a)));
  }
  changePassword(currentPassword: string, newPassword: string) {
    return this.http.post<void>(`${API_BASE}/api/account/password`, { currentPassword, newPassword });
  }
  startEmailChange(newEmail: string, password: string) {
    return this.http.post<void>(`${API_BASE}/api/account/email`, { newEmail, password });
  }
  verifyEmailChange(code: string) {
    return this.http.post<AccountResponse>(`${API_BASE}/api/account/email/verify`, { code })
      .pipe(tap((a) => this.applyAccount(a)));
  }
  uploadPhoto(file: File) {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<{ avatarUrl: string }>(`${API_BASE}/api/account/photo`, form)
      .pipe(tap((r) => this.account.update((a) => (a ? { ...a, avatarUrl: r.avatarUrl } : a))));
  }
  deletePhoto() {
    return this.http.delete<void>(`${API_BASE}/api/account/photo`)
      .pipe(tap(() => this.account.update((a) => (a ? { ...a, avatarUrl: null } : a))));
  }

  /** Fold a fresh profile into both the cached account and the stored auth user (so the nav
   *  name/email and a page refresh stay consistent after a rename or email change). */
  private applyAccount(a: AccountResponse): void {
    this.account.set(a);
    const u = this.user();
    if (u) {
      const merged = { ...u, email: a.email, displayName: a.displayName };
      this.user.set(merged);
      localStorage.setItem(USER_KEY, JSON.stringify(merged));
    }
  }

  logout(): void {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    this.token.set(null);
    this.user.set(null);
    this.account.set(null);
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
