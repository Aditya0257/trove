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
  {
    path: 'mail',
    canActivate: [authGuard],
    loadComponent: () => import('./features/mail/mail').then((m) => m.Mail),
  },
  {
    path: 'mail/:bundleId',
    canActivate: [authGuard],
    loadComponent: () => import('./features/mail/mail-detail').then((m) => m.MailDetail),
  },
  {
    path: 'search',
    canActivate: [authGuard],
    loadComponent: () => import('./features/search/search').then((m) => m.Search),
  },
  {
    path: 'spend',
    canActivate: [authGuard],
    loadComponent: () => import('./features/spend/spend').then((m) => m.Spend),
  },
  {
    path: 'reminders',
    canActivate: [authGuard],
    loadComponent: () => import('./features/reminders/reminders').then((m) => m.Reminders),
  },
  {
    path: 'backups',
    canActivate: [authGuard],
    loadComponent: () => import('./features/backups/backups').then((m) => m.Backups),
  },
  {
    path: 'spaces',
    canActivate: [authGuard],
    loadComponent: () => import('./features/spaces/spaces').then((m) => m.Spaces),
  },
  { path: '**', redirectTo: 'documents' },
];
