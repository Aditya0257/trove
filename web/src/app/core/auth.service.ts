import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { tap } from 'rxjs';
import { API_BASE } from './config';
import { AuthResponse } from './models';

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

  login(email: string, password: string) {
    return this.http
      .post<AuthResponse>(`${API_BASE}/api/auth/login`, { email, password })
      .pipe(tap((r) => this.store(r)));
  }

  register(email: string, displayName: string, password: string) {
    return this.http
      .post<AuthResponse>(`${API_BASE}/api/auth/register`, { email, displayName, password })
      .pipe(tap((r) => this.store(r)));
  }

  logout(): void {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    this.token.set(null);
    this.user.set(null);
  }

  private store(r: AuthResponse): void {
    localStorage.setItem(TOKEN_KEY, r.token);
    localStorage.setItem(USER_KEY, JSON.stringify(r));
    this.token.set(r.token);
    this.user.set(r);
  }
}

function readUser(): AuthResponse | null {
  const raw = localStorage.getItem(USER_KEY);
  return raw ? (JSON.parse(raw) as AuthResponse) : null;
}
