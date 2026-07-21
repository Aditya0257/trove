import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';

import { routes } from './app.routes';
import { authInterceptor } from './core/auth.interceptor';
import { noticeInterceptor } from './core/notice/notice.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    // noticeInterceptor is listed first so it wraps authInterceptor and observes the
    // final response/error (timing, request-id, notice) for the Notice System (D23).
    provideHttpClient(withInterceptors([noticeInterceptor, authInterceptor])),
  ],
};
