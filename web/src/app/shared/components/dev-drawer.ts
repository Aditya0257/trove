import { Component, effect, inject, signal } from '@angular/core';
import { DevLogService, DevLogEntry } from '../../core/services/dev-log.service';
import { ApiService } from '../../core/services/api.service';
import { AiUsage } from '../../core/models/models';
import { TERMS } from '../../core/config/terms';
import { SettingsService } from '../../core/services/settings.service';

/**
 * The in-app "inspect" surface: a slide-over listing recent API calls with method,
 * path, status, client round-trip, the server request-id, the notice, and - for
 * document reads - the extraction chain trail. Complements the styled console for
 * when the console isn't handy (e.g. on a phone-sized browser). Toggled by a small
 * corner pill. Mounted once at the app root.
 */
@Component({
  selector: 'trove-dev-drawer',
  standalone: true,
  templateUrl: './dev-drawer.html',
  styleUrl: './dev-drawer.scss',
})
export class DevDrawer {
  protected log = inject(DevLogService);
  protected api = inject(ApiService);
  protected entries = this.log.entries;
  protected open = signal(false);
  protected usage = signal<AiUsage | null>(null);
  /** Vendor-neutral labels (see core/terms.ts). */
  protected terms = TERMS;
  protected settings = inject(SettingsService);

  /** How often the open drawer re-polls for the app-wide (global) figure. */
  private static readonly POLL_MS = 60_000;

  constructor() {
    // Your own usage updates the instant you trigger AI: refresh on every real logged
    // call (the interceptor deliberately doesn't log the usage poll itself, so this
    // can't feed back into a loop).
    effect(() => {
      const n = this.entries().length; // track: a new call should refresh the gauge
      if (this.open()) {
        void n;
        this.fetchUsage();
      }
    });

    // The global bar moves when OTHER users consume AI, which no local event can tell
    // us about - so poll lightly, but ONLY while the drawer is open (the poll stops the
    // moment it closes). Two indexed SELECTs at 60s is negligible even for all users at
    // once; no websocket needed at this scale.
    effect((onCleanup) => {
      if (!this.open()) return;
      const id = setInterval(() => this.fetchUsage(), DevDrawer.POLL_MS);
      onCleanup(() => clearInterval(id));
    });
  }

  protected fetchUsage(): void {
    this.api.aiUsage().subscribe({ next: (u) => this.usage.set(u), error: () => {} });
  }

  protected path = (e: DevLogEntry) => e.url.replace(/^https?:\/\/[^/]+/, '');
  protected ok = (e: DevLogEntry) => e.status >= 200 && e.status < 300;

  /** Local wall-clock (24-hour) - shows in the viewer's timezone, e.g. IST. */
  protected time = (at: number) =>
    new Date(at).toLocaleTimeString('en-GB', { hour12: false });

  /**
   * Three-lens meaning for a call - what it means to the user, the developer, and the
   * business. Short by design; the goal is to read the drawer and understand the flow.
   */
  protected meaning(e: DevLogEntry): { label: string; user: string; dev: string; business: string; flow: string } {
    const p = this.path(e).split('?')[0];
    const m = e.method.toUpperCase();
    const A = 'Angular';
    const M = (label: string, user: string, dev: string, business: string, flow: string) =>
      ({ label, user, dev, business, flow });

    if (p === '/api/auth/login') return M('Sign in', 'Signing you in', 'verify credentials → mint a JWT', 'gate to a private vault', `${A} → AuthController.login() → UserService.verifyCredentials() → JwtService.issue()`);
    if (p === '/api/auth/register') return M('Create account', 'Creating your account', 'create user + provision a personal space', 'a new owner joins', `${A} → AuthController.register() → UserService.register() → SpaceService.createPersonalSpace()`);
    if (p === '/api/account/me') return M('Your profile', 'Loading your profile', 'profile + 2FA + avatar summary', 'account self-service', `${A} → AccountController.me() → UserRepository`);
    if (p === '/api/account/password') return M('Change password', 'Updating your password', 're-check current → BCrypt the new one', 'account security', `${A} → AccountController.changePassword() → UserService.changePassword()`);
    if (p.startsWith('/api/admin')) return M('Admin', 'Working…', 'admin-only user management', 'closed registration + account control', `${A} → AdminController → UserService / AccountDeletionService`);
    if (p === '/api/spaces') return M('Your spaces', 'Loading your spaces', 'personal + shared spaces you belong to', 'who can see which documents', `${A} → SpaceController.mine() → SpaceService`);
    if (p === '/api/categories') return M('Categories', 'Loading categories', 'global + space category taxonomy', 'how the vault is organised', `${A} → CategoryController.list() → CategoryService`);
    if (p === '/api/search') return M('Search', 'Finding your documents', 'NL query → LLM/rule parse → filtered query', 'plain-English retrieval', `${A} → SearchController.search() → SearchService`);
    if (p === '/api/chat/ask') return M('Ask your vault', 'Answering from your documents', 'normalize query → embed → retrieve → grounded LLM answer', 'ask questions in plain language', `${A} → ChatController.ask() → VaultChatService.ask() → QueryNormalizer + EmbeddingService + CloudflareChatClient`);
    if (p === '/api/mail') return M('Mail', 'Loading your filed emails', 'one page of email threads grouped in the DB by bundle id, plus autocomplete facets', 'file important emails as threads', `${A} → MailController.list() → MailService.bundles() → DocumentRepository`);
    if (p === '/api/documents/mail-bundle') return M('Mail thread', 'Opening this email thread', "one bundle's emails, oldest first", 'read a filed email thread', `${A} → DocumentController.mailBundle() → DocumentService.listMailBundle()`);
    if (p === '/api/documents' && m === 'POST') return M('Upload a document', 'Saving your document', `multipart → ${TERMS.objectStorage} object + sidecar JSON; async extraction queued (${TERMS.mirrorStorage} runs about hourly as a separate job)`, 'an item enters the source-of-truth vault', `${A} → DocumentController.upload() → DocumentService.upload() → StorageService + ExtractionProvider`);
    if (p === '/api/documents' && m === 'GET') return M('List documents', 'Loading your documents', 'one page of the rebuildable DB index (X-Total-Count header)', 'browse the vault', `${A} → DocumentController.list() → DocumentService.listPaged() → DocumentRepository`);
    if (/^\/api\/documents\/[^/]+\/confirm$/.test(p)) return M('Confirm a document', 'Saving your reviewed details', 'human-review → status=confirmed; fires reminders + anomaly check', 'nothing is trusted until a human confirms', `${A} → DocumentController.confirm() → DocumentService.confirm()`);
    if (/^\/api\/documents\/[^/]+\/content$/.test(p)) return M('Open a vital file', 'Opening your file', 'decrypt-stream the encrypted bytes (no presigned URL)', 'sensitive PII stays encrypted at rest', `${A} → DocumentController.content() → DocumentService.content() → EncryptionService`);
    if (/^\/api\/documents\/[^/]+\/reextract$/.test(p)) return M('Read again with AI', 'Re-reading this document', 'reset confidence + re-dispatch extraction', 'retry a read that timed out', `${A} → DocumentController.reextract() → DocumentService.reextract()`);
    if (/^\/api\/documents\/[^/]+\/related$/.test(p)) return M('Related documents', 'Loading related documents', 'same merchant, else same category, newest first', 'auto-linking related docs', `${A} → DocumentController.related() → DocumentService.related()`);
    if (/^\/api\/documents\/[^/]+$/.test(p)) return M('Fetch a document', 'Loading a document', 'reads the index row + a presigned view URL', 'reads the rebuildable index', `${A} → DocumentController.get() → DocumentService.get()`);
    if (p === '/api/reminders' && m === 'GET') return M('Reminders', 'Loading reminders', 'pending reminders for the space, soonest first', 'never miss a due date / warranty', `${A} → ReminderController.list() → ReminderService.list()`);
    if (/^\/api\/reminders\/[^/]+\/dismiss$/.test(p)) return M('Dismiss reminder', 'Dismissing a reminder', 'mark reminder dismissed', 'user acknowledged it', `${A} → ReminderController.dismiss() → ReminderService`);
    if (p.startsWith('/api/spend')) return M('Spend analytics', 'Loading your spend', 'aggregate confirmed documents by category/month', 'understand where money goes', `${A} → SpendController → SpendService`);
    if (p === '/api/insights/expiring') return M('Expiring soon', 'Loading what is coming up', 'due dates + warranties in the window, minus ones handled in Reminders', 'act before something lapses', `${A} → InsightsController.expiring() → InsightsService`);
    if (p === '/api/insights/recurring') return M('Recurring', 'Finding your subscriptions', 'group confirmed docs by merchant+category; infer cadence + predict next', 'spot what recurs', `${A} → InsightsController.recurring() → InsightsService`);
    if (p.startsWith('/api/integrations/google-drive')) return M(TERMS.driveBackup, `Talking to ${TERMS.driveBackup}`, 'per-owner OAuth backup / sync', 'human-navigable third copy of the data', `${A} → DriveController → DriveService`);
    return M('API request', 'Working…', `${m} ${p}`, '-', `${A} → ${m} ${p}`);
  }

  /** Entries honoring the errors-only filter. */
  protected shown(): DevLogEntry[] {
    const all = this.entries();
    return this.errorsOnly() ? all.filter((e) => !this.ok(e)) : all;
  }

  protected errorsOnly = signal(false);

  protected attempts(e: DevLogEntry): Record<string, unknown>[] {
    const a = e.extractionMeta?.['attempts'];
    return Array.isArray(a) ? (a as Record<string, unknown>[]) : [];
  }

  /** True when this response's document fell back to the stub (AI read failed). */
  protected fellBack(e: DevLogEntry): boolean {
    return e.extractionMeta?.['fellBack'] === true;
  }

  /** True when AI was consumed on this call (it carries an extraction trail). */
  protected isAi(e: DevLogEntry): boolean {
    return !!e.extractionMeta;
  }

  /** Total AI tokens billed across this call's extraction attempts, or null if none. */
  protected aiTokens(e: DevLogEntry): number | null {
    let sum = 0;
    let seen = false;
    for (const a of this.attempts(e)) {
      const t = a['tokens'];
      if (typeof t === 'number') {
        sum += t;
        seen = true;
      }
    }
    return seen ? sum : null;
  }

  protected fmt = (n: number) => Math.round(n).toLocaleString('en-US');
  protected pct = (used: number, limit: number) => Math.min(100, Math.round((used / limit) * 100));
  protected left = (used: number, limit: number) => Math.max(0, limit - used);
  protected pretty = (o: unknown) => (typeof o === 'string' ? o : JSON.stringify(o, null, 2));
}
