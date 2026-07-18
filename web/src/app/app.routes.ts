import { Routes } from '@angular/router';
import { authGuard } from './core/auth.guard';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'documents' },
  { path: 'login', loadComponent: () => import('./features/auth/login').then((m) => m.Login) },
  { path: 'register', loadComponent: () => import('./features/auth/register').then((m) => m.Register) },
  {
    path: 'upload',
    canActivate: [authGuard],
    loadComponent: () => import('./features/documents/upload').then((m) => m.Upload),
  },
  {
    path: 'documents',
    canActivate: [authGuard],
    loadComponent: () => import('./features/documents/doc-list').then((m) => m.DocList),
  },
  {
    path: 'documents/:id/review',
    canActivate: [authGuard],
    loadComponent: () => import('./features/documents/review').then((m) => m.Review),
  },
  { path: '**', redirectTo: 'documents' },
];
