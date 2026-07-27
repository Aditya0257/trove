import { Component, computed, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { SpaceContext } from '../../core/services/space.context';
import { DateTimePipe } from '../../shared/pipes/datetime.pipe';
import { HelpCard } from '../../shared/components/help-card';
import { InfoTip } from '../../shared/components/info-tip';
import { BackupRun, IntegrityReport } from '../../core/models/models';

/**
 * Backups & integrity dashboard - verifies the "three copies, zero data loss" promise
 * rather than assuming it: per-tier coverage for the current space, the documents with
 * gaps, global object-store stats, and the recent backup/verification history.
 */
@Component({
  selector: 'app-backups',
  imports: [RouterLink, DateTimePipe, HelpCard, InfoTip],
  templateUrl: './backups.html',
  styleUrl: './backups.scss',
})
export class Backups {
  private api = inject(ApiService);
  private spaceCtx = inject(SpaceContext);

  protected report = signal<IntegrityReport | null>(null);
  protected history = signal<BackupRun[]>([]);
  protected loading = signal(false);
  protected error = signal<string | null>(null);
  protected openTier = signal<string | null>(null);

  private readonly TIER_NAME: Record<string, string> = {
    live: 'Live copy (R2)', sidecar: 'Sidecar JSON', mirror: 'Mirror (B2)', drive: 'Drive (Tier-3)',
  };
  private readonly TIER_DETAIL: Record<string, string> = {
    live:
      'The primary object store (Cloudflare R2, S3-compatible). Every upload is written here first, and the ' +
      'app reads and serves files from it. Example: uploading reliance-jan.jpg stores the bytes at ' +
      'electricity/2026-01/reliance-jan-a1b2.jpg, and that is the copy the review screen and the Ask assistant ' +
      'load. "31/31" means all 31 files are present and servable here. If a file were missing, that document ' +
      'would be flagged critical.',
    sidecar:
      'Next to every file sits a .json sidecar holding that document\'s metadata (category, merchant, date, ' +
      'amount, raw text, owner). It makes the bucket self-describing: even if the database were wiped, scanning ' +
      'the sidecars rebuilds every row. Example: electricity/2026-01/reliance-jan-a1b2.json contains ' +
      '{"category":"electricity","merchant":"Reliance","amount":1840.50,"docDate":"2026-01-05"}. "31/31" means ' +
      'each file has its sidecar.',
    mirror:
      'An independent second copy on Backblaze B2, a different provider, synced about hourly. It is append-only, ' +
      'so it keeps history even after deletes - a provider outage or account loss on R2 cannot wipe the vault. ' +
      'Example: the same electricity/2026-01/reliance-jan-a1b2.jpg key also exists in the B2 bucket. It can hold ' +
      'MORE objects than R2 (e.g. 114 vs 80) because it retains purged and older copies.',
    drive:
      'A human-browsable copy in a member\'s Google Drive, organised as Trove / space / category / month, synced ' +
      'about hourly. If the app and both clouds were gone, you open Google Drive and find the document - no app ' +
      'needed. Example: reliance-jan.jpg appears under Trove / Dev Personal / electricity / 2026-01/. "31/31" ' +
      'means all files are synced to at least one linked Drive (shows 0 until a Drive is connected to the space).',
  };

  tierName(k: string): string { return this.TIER_NAME[k] ?? ''; }
  tierDetail(k: string): string { return this.TIER_DETAIL[k] ?? ''; }
  toggleTier(k: string): void { this.openTier.update((v) => (v === k ? null : k)); }

  protected helpUser =
    'Your documents are kept in three independent places - the live store, a second-cloud mirror, and ' +
    'Google Drive - so no single outage can lose them. This page checks that every document is actually ' +
    'present in each place and flags anything that has drifted, so "nothing is lost" is something you can see, ' +
    'not just trust.';
  protected helpDev =
    'Verification, not assumption. It lists R2 (and B2) once and membership-checks every document\'s object + ' +
    'sidecar keys; Drive coverage comes from per-document sync records. Orphans (objects with no DB row) show ' +
    'the database is a rebuildable index - orphaned sidecars are exactly what a rebuild reads back. A daily job ' +
    'runs the same check vault-wide and records it as a backup_run (FAILED if any live object is missing), so ' +
    'drift is caught and visible over time.';

  constructor() {
    effect(() => {
      this.spaceCtx.currentSpaceId();   // re-verify when the space changes
      this.load();
    });
    this.api.integrityHistory().subscribe((h) => this.history.set(h));
  }

  pct(ok: number, total: number): number {
    return total <= 0 ? 100 : Math.round((ok / total) * 100);
  }

  verify(): void {
    this.load();
    this.api.integrityHistory().subscribe((h) => this.history.set(h));
  }

  private load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.integrityReport(this.spaceCtx.currentSpaceId()).subscribe({
      next: (r) => { this.report.set(r); this.loading.set(false); },
      error: (e) => { this.error.set(e?.error?.message ?? 'Could not load integrity report.'); this.loading.set(false); },
    });
  }
}
