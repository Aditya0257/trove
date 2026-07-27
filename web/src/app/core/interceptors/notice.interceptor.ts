import { HttpErrorResponse, HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, tap, throwError } from 'rxjs';
import { DevLogService } from '../services/dev-log.service';
import { Notice, noticeFrom } from '../models/notice.model';
import { NoticeService } from '../services/notice.service';

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

  // The gauge polls /api/usage; it IS logged (the trail shows every call), but a poll
  // must not raise an error toast (background noise), and - critically - the drawer's
  // refetch-on-new-call is guarded against usage entries so a poll can't retrigger a
  // poll (that was the old infinite loop). Match the legacy /api/ai-usage path too.
  const isUsagePoll = req.url.includes('/api/usage') || req.url.includes('/api/ai-usage');
  const reqQuery = redact(queryFromUrl(req.urlWithParams)) as Record<string, string> | null;
  const reqBody = capBody(reqBodyOf(req));

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
          reqQuery,
          reqBody,
          body: capBody(event.body),
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
        reqQuery,
        reqBody,
        body: capBody(err.error),
      });
      if (!isUsagePoll) notices.show(notice);
      return throwError(() => err);
    }),
  );
};

/** Request query params parsed from the final URL (covers both HttpParams and query
 *  strings built into the URL directly, as most of the app does). Null if none. */
function queryFromUrl(url: string): Record<string, string> | null {
  const i = url.indexOf('?');
  if (i < 0) return null;
  const out: Record<string, string> = {};
  new URLSearchParams(url.slice(i + 1)).forEach((v, k) => { out[k] = v; });
  return Object.keys(out).length ? out : null;
}

/** The request body for the drawer: JSON as-is, FormData as field names (files summarised),
 *  with sensitive fields masked. Never dumps binary or secrets. */
function reqBodyOf(req: { body: unknown }): unknown {
  const b = req.body;
  if (b === null || b === undefined) return null;
  if (typeof FormData !== 'undefined' && b instanceof FormData) {
    const out: Record<string, string> = {};
    b.forEach((v, k) => {
      out[k] = v instanceof File ? `[file: ${v.name}, ${v.size} B]` : String(v);
    });
    return redact(out);
  }
  return redact(b);
}

const SENSITIVE = /pass(word)?|token|secret|otp|code|api[-_]?key|authorization/i;

/** Deep-clone masking values under sensitive-looking keys (password, token, otp, ...). */
function redact(v: unknown): unknown {
  if (Array.isArray(v)) return v.map(redact);
  if (v && typeof v === 'object') {
    const out: Record<string, unknown> = {};
    for (const [k, val] of Object.entries(v as Record<string, unknown>)) {
      out[k] = SENSITIVE.test(k) ? '••••' : redact(val);
    }
    return out;
  }
  return v;
}

/**
 * The response body for the drawer, size-capped so the bounded log never holds a huge blob.
 * Small bodies are kept as-is (rendered as pretty JSON); an oversized one becomes a truncated
 * string preview. This is a developer-only surface, so it shows what actually came back.
 */
function capBody(body: unknown): unknown {
  if (body === null || body === undefined) return null;
  try {
    const compact = JSON.stringify(body);
    // Small bodies are kept as an object so the drawer pretty-prints them (nested, indented).
    if (compact.length <= 20000) return body;
    // Large ones are indented first, THEN truncated, so what shows is still readable nested
    // JSON (not one giant compact line) - the drawer renders a string as-is.
    return `${JSON.stringify(body, null, 2).slice(0, 20000)}\n… (truncated, ${compact.length} chars total)`;
  } catch {
    return null; // non-serialisable (e.g. a Blob) - skip
  }
}

/** The key stored fields of a document response - the "JSON in the DB" for the drawer. */
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
