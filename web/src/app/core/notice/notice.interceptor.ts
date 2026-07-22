import { HttpErrorResponse, HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, tap, throwError } from 'rxjs';
import { DevLogService } from './dev-log.service';
import { Notice, noticeFrom } from './notice.model';
import { NoticeService } from './notice.service';

/**
 * Makes every request legible (D23): times the round-trip, reads the server's
 * X-Trove-Request-Id, records the call in the Developer log/console, and on failure
 * turns the server's notice envelope (or a synthesized offline/timeout notice) into
 * a toast. Sits OUTSIDE authInterceptor so it observes the final response/error.
 */
export const noticeInterceptor: HttpInterceptorFn = (req, next) => {
  const notices = inject(NoticeService);
  const devlog = inject(DevLogService);
  const start = performance.now();

  return next(req).pipe(
    tap((event) => {
      if (event instanceof HttpResponse) {
        devlog.add({
          at: Date.now(),
          method: req.method,
          url: req.urlWithParams,
          status: event.status,
          durationMs: Math.round(performance.now() - start),
          requestId: event.headers.get('X-Trove-Request-Id'),
          extractionMeta: extractionMetaOf(event.body),
          extracted: extractedOf(event.body),
        });
      }
    }),
    catchError((err: HttpErrorResponse) => {
      const notice = noticeFromError(err);
      devlog.add({
        at: Date.now(),
        method: req.method,
        url: req.urlWithParams,
        status: err.status,
        durationMs: Math.round(performance.now() - start),
        requestId: err.headers?.get('X-Trove-Request-Id'),
        notice,
      });
      notices.show(notice);
      return throwError(() => err);
    }),
  );
};

/** The key stored fields of a document response — the "JSON in the DB" for the drawer. */
function extractedOf(body: unknown): Record<string, unknown> | null {
  if (!body || typeof body !== 'object') return null;
  const b = body as Record<string, unknown>;
  // Only document responses (they carry a status + extraction fields).
  if (!('status' in b) || !('category' in b)) return null;
  const raw = typeof b['rawText'] === 'string' ? (b['rawText'] as string) : null;
  const extra = (b['extra'] as Record<string, unknown>) ?? {};
  return {
    category: b['category'] ?? null,
    merchant: b['merchant'] ?? null,
    amount: b['amount'] ?? null,
    currency: b['currency'] ?? null,
    docDate: b['docDate'] ?? null,
    dueDate: b['dueDate'] ?? null,
    notes: extra['notes'] ?? null,
    confidence: b['extractionConfidence'] ?? null,
    rawText: raw && raw.length > 400 ? raw.slice(0, 400) + '…' : raw,
  };
}

/** Pulls extra.extractionMeta out of a document response for the drawer/console. */
function extractionMetaOf(body: unknown): Record<string, unknown> | null {
  if (body && typeof body === 'object') {
    const extra = (body as Record<string, unknown>)['extra'];
    if (extra && typeof extra === 'object') {
      const meta = (extra as Record<string, unknown>)['extractionMeta'];
      if (meta && typeof meta === 'object') return meta as Record<string, unknown>;
    }
  }
  return null;
}

/** Server notice envelope if present, else a synthesized client-side notice. */
function noticeFromError(err: HttpErrorResponse): Notice {
  const fromServer = noticeFrom((err.error as Record<string, unknown>)?.['notice']);
  if (fromServer) return fromServer;

  if (err.status === 0) {
    return {
      level: 'warning',
      code: 'OFFLINE',
      userMessage: "Can't reach Trove right now. Check your connection and try again.",
      devNote: `Network error / CORS / server unreachable (status 0) for ${err.url ?? 'request'}.`,
    };
  }
  const serverMessage =
    err.error && typeof err.error === 'object'
      ? (err.error as Record<string, unknown>)['message']
      : undefined;
  return {
    level: 'error',
    code: `HTTP_${err.status}`,
    userMessage: typeof serverMessage === 'string' ? serverMessage : 'Something went wrong. Please try again.',
    devNote: `HTTP ${err.status} ${err.statusText} for ${err.url ?? 'request'}.`,
  };
}
