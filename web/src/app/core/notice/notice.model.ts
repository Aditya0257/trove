/**
 * Notice — the client twin of the backend ApiNotice (D23).
 *
 * The two-channel feedback unit: a calm `userMessage` for everyone and a precise
 * `devNote` for the curious/developer, plus a `level`, machine `code`, and free-form
 * `meta`. Every toast and every Developer-console entry is a Notice. Identical shape
 * to the Flutter client, so the two platforms behave the same.
 */
export type NoticeLevel = 'info' | 'success' | 'warning' | 'error';

export interface Notice {
  level: NoticeLevel;
  code: string;
  userMessage: string;
  devNote?: string | null;
  meta?: Record<string, unknown> | null;
}

/** Coerce an unknown server payload into a Notice, tolerating missing fields. */
export function noticeFrom(obj: unknown): Notice | null {
  if (!obj || typeof obj !== 'object') return null;
  const o = obj as Record<string, unknown>;
  if (typeof o['userMessage'] !== 'string') return null;
  const level = o['level'];
  return {
    level: level === 'success' || level === 'warning' || level === 'error' ? level : 'info',
    code: typeof o['code'] === 'string' ? (o['code'] as string) : 'UNKNOWN',
    userMessage: o['userMessage'] as string,
    devNote: typeof o['devNote'] === 'string' ? (o['devNote'] as string) : null,
    meta: (o['meta'] as Record<string, unknown>) ?? null,
  };
}
