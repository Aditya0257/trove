import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

/** Blocks routes when there's no token, redirecting to /login. */
export const authGuard: CanActivateFn = () => {
  const router = inject(Router);
  if (localStorage.getItem('trove_token')) {
    return true;
  }
  router.navigate(['/login']);
  return false;
};
