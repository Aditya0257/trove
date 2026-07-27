import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';

/**
 * Attaches the JWT as a Bearer header on every request, and on a 401 clears the
 * session and bounces to /login. Reads the token from localStorage directly to avoid
 * a circular dependency with AuthService (which itself uses HttpClient).
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const router = inject(Router);
  const token = localStorage.getItem('trove_token');
  const authed = token
    ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : req;

  return next(authed).pipe(
    catchError((err: HttpErrorResponse) => {
      if (err.status === 401) {
        localStorage.removeItem('trove_token');
        localStorage.removeItem('trove_user');
        router.navigate(['/login']);
      }
      return throwError(() => err);
    })
  );
};
