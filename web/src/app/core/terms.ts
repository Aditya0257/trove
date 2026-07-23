/**
 * Provider / infrastructure vocabulary — the ONE place vendor names live.
 *
 * The rest of the app never hardcodes "Cloudflare R2", "Backblaze B2", "Workers AI",
 * "Neon", etc. It uses these vendor-neutral labels instead, so end users see plain
 * language and — if a provider is ever swapped — only this file changes.
 *
 * Keep the values generic. The single real product name we intentionally keep is the
 * user's cloud drive, because they literally connect their own account to it; even that
 * is centralised here so it's a one-line change if the drive provider ever changes.
 */
export const TERMS = {
  /** Tier-1 hot store where originals + sidecars live. (was: Cloudflare R2) */
  objectStorage: 'object storage',
  /** Tier-2 independent copy on a second cloud. (was: Backblaze B2) */
  mirrorStorage: 'a mirror copy on a second cloud',
  /** The model that reads documents. (was: Cloudflare Workers AI vision) */
  aiReader: 'the AI reader',
  /** The shared AI account the whole app draws on. (was: Cloudflare Workers AI) */
  aiService: 'the shared AI service',
  /** The billed AI unit shown on the usage gauge. (was: neurons) */
  aiCredits: 'AI credits',
  /** The rebuildable metadata index. (was: Postgres / Neon) */
  database: 'the database',
  /** Tier-3 human-browsable backup the user connects their own account to. */
  driveBackup: 'Google Drive',
} as const;
