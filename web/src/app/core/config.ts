/**
 * API base URL, resolved at runtime from /config.json by main.ts (stored on
 * globalThis before the app loads). Change the deployed API host by editing
 * config.json — no rebuild required. Falls back to the local dev backend.
 */
export const API_BASE =
  ((globalThis as Record<string, unknown>)['__TROVE_API_BASE'] as string) ?? 'http://localhost:8080';
