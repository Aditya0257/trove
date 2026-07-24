import { Component, computed, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { SpaceContext } from '../../core/space.context';
import { DateTimePipe } from '../../core/datetime.pipe';
import { HelpCard } from '../../core/help-card';
import { InfoTip } from '../../core/info-tip';
import { BackupRun, IntegrityReport } from '../../core/models';

/**
 * Backups & integrity dashboard - verifies the "three copies, zero data loss" promise
 * rather than assuming it: per-tier coverage for the current space, the documents with
 * gaps, global object-store stats, and the recent backup/verification history.
 */
@Component({
  selector: 'app-backups',
  imports: [RouterLink, DateTimePipe, HelpCard, InfoTip],
  template: `
    <div class="card">
      <div class="row-between">
        <h1>Backups &amp; integrity</h1>
        <div class="head-actions">
          <trove-info-tip align="right"
            text="Re-checks every document right now: confirms the live file, its sidecar, the mirror and Drive copy all exist. Read-only - it changes nothing.">
          </trove-info-tip>
          <button type="button" class="btn-ghost" (click)="verify()" [disabled]="loading()">
            {{ loading() ? 'Verifying…' : 'Verify now' }}
          </button>
        </div>
      </div>
      <trove-help-card title="About backup integrity" [open]="false" [user]="helpUser" [dev]="helpDev"></trove-help-card>

      @if (report(); as r) {
        <!-- Headline health -->
        @if (r.criticalCount > 0) {
          <div class="banner bad">
            ⚠ {{ r.criticalCount }} document(s) are missing their live copy - investigate below.
          </div>
        } @else if (r.documents === 0) {
          <div class="banner ok">No documents in this space yet.</div>
        } @else {
          <div class="banner ok">✅ All {{ r.documents }} document(s) are safely stored. Verified {{ r.checkedAt | prettyDate }}.</div>
        }

        <!-- Per-tier coverage -->
        <div class="tiers">
          <div class="tier" [class.warn]="r.primaryOk < r.documents">
            <div class="tier-h">Live copy <span class="tier-tag">R2</span></div>
            <div class="tier-desc">The working store - live reads &amp; writes.</div>
            <div class="tier-n">{{ r.primaryOk }}/{{ r.documents }}</div>
            <div class="bar"><span [style.width.%]="pct(r.primaryOk, r.documents)"></span></div>
          </div>
          <div class="tier" [class.warn]="r.sidecarOk < r.documents">
            <div class="tier-h">Sidecar <span class="tier-tag">JSON</span></div>
            <div class="tier-desc">Self-describing metadata beside each file.</div>
            <div class="tier-n">{{ r.sidecarOk }}/{{ r.documents }}</div>
            <div class="bar"><span [style.width.%]="pct(r.sidecarOk, r.documents)"></span></div>
          </div>
          <div class="tier" [class.warn]="r.mirrorOk !== null && r.mirrorOk < r.documents">
            <div class="tier-h">Mirror <span class="tier-tag">B2</span></div>
            <div class="tier-desc">Independent second-cloud copy.</div>
            @if (r.mirrorOk === null) {
              <div class="tier-n muted">Off</div>
              <div class="bar off"></div>
            } @else {
              <div class="tier-n">{{ r.mirrorOk }}/{{ r.documents }}</div>
              <div class="bar"><span [style.width.%]="pct(r.mirrorOk, r.documents)"></span></div>
            }
          </div>
          <div class="tier">
            <div class="tier-h">Drive <span class="tier-tag">Tier-3</span></div>
            <div class="tier-desc">Human-browsable copy in Google Drive.</div>
            <div class="tier-n">{{ r.driveOk }}/{{ r.documents }}</div>
            <div class="bar"><span [style.width.%]="pct(r.driveOk, r.documents)"></span></div>
          </div>
        </div>

        <!-- Global object-store integrity -->
        <h3>Object store</h3>
        <div class="stats">
          <div><span class="k">Objects in R2</span><span class="v">{{ r.storage.r2Objects }}</span></div>
          <div><span class="k">Referenced by the index</span><span class="v">{{ r.storage.indexedKeys }}</span></div>
          <div>
            <span class="k">Orphan files
              <trove-info-tip text="Files in R2 with no database row - usually left after a delete or a DB rebuild. Harmless: they don't affect your documents."></trove-info-tip>
            </span>
            <span class="v">{{ r.storage.orphanObjects }}</span>
          </div>
          <div>
            <span class="k">Rebuildable from sidecar
              <trove-info-tip text="Of those orphans, how many carry a sidecar JSON - enough to recreate their database row. Proof the DB is a rebuildable index, not the source of truth."></trove-info-tip>
            </span>
            <span class="v">{{ r.storage.rebuildableOrphans }}</span>
          </div>
          @if (r.storage.mirrorEnabled) {
            <div><span class="k">Objects in B2 mirror</span><span class="v">{{ r.storage.mirrorObjects }}</span></div>
          }
        </div>

        <!-- Documents with gaps -->
        @if (r.issues.length) {
          <h3>Gaps ({{ r.issues.length }})</h3>
          <div class="issues">
            @for (i of r.issues; track $index) {
              <a class="issue" [routerLink]="['/documents', i.documentId, 'review']">
                <span class="sev" [class]="i.severity">{{ i.severity }}</span>
                <span class="issue-title">{{ i.title }}</span>
                <span class="issue-problem muted">{{ i.problem }}</span>
              </a>
            }
          </div>
        }
      } @else if (error()) {
        <p class="error">{{ error() }}</p>
      } @else {
        <p class="muted">Loading…</p>
      }
    </div>

    <div class="card">
      <h3>Recent backup &amp; verification runs</h3>
      @if (history().length === 0) {
        <p class="muted">No runs recorded yet.</p>
      } @else {
        <div class="runs">
          @for (h of history(); track $index) {
            <div class="run">
              <span class="run-status" [class]="h.status">{{ h.status }}</span>
              <span class="run-kind">{{ h.kind }}</span>
              <span class="run-when muted">{{ (h.finishedAt || h.startedAt) | prettyDate }}</span>
              <span class="run-detail muted">{{ h.detail }}</span>
            </div>
          }
        </div>
      }
    </div>
  `,
  styles: [
    `
      .btn-ghost {
        margin: 0; border: 1px solid var(--line); background: transparent; color: var(--muted);
        border-radius: 8px; padding: 6px 14px; font-size: 13px; font-weight: 600; cursor: pointer;
      }
      .btn-ghost:hover:not(:disabled) { background: var(--hover); color: var(--accent); }
      .head-actions { display: flex; align-items: center; gap: 8px; }
      .stats .k { display: inline-flex; align-items: center; gap: 6px; }
      .banner { border-radius: 10px; padding: 10px 14px; margin: 12px 0 4px; font-weight: 600; font-size: 14px; }
      .banner.ok { background: var(--accent-soft); color: var(--accent); }
      .banner.bad { background: var(--danger-soft, rgba(180,64,47,0.12)); color: var(--danger, #b4402f); }
      .tiers { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; margin: 16px 0; }
      .tier { border: 1px solid var(--line); border-radius: 10px; padding: 12px 14px; }
      .tier.warn { border-color: var(--danger-line, rgba(180,64,47,0.4)); }
      /* Every card has the same anatomy - one-line title, a fixed-height description,
         then the number + bar - so all four numbers/bars sit on the same baseline. */
      .tier-h { font-size: 13px; font-weight: 600; display: flex; gap: 6px; align-items: baseline; white-space: nowrap; }
      .tier-tag {
        font-size: 10px; font-weight: 700; letter-spacing: 0.03em; color: var(--muted);
        background: var(--hover); border-radius: 5px; padding: 1px 6px;
      }
      /* Mini in-card help - a distinct indigo tint (not the usual grey), two lines reserved. */
      .tier-desc {
        color: var(--accent-2); font-size: 11px; line-height: 1.35; margin: 5px 0 0;
        min-height: 2.7em; opacity: 0.9;
      }
      .tier-n { font-size: 22px; font-weight: 700; margin: 4px 0 6px; }
      .bar.off { opacity: 0.35; }
      .bar { height: 6px; background: var(--hover); border-radius: 999px; overflow: hidden; }
      .bar span { display: block; height: 100%; background: var(--accent); }
      .tier.warn .bar span { background: var(--danger, #b4402f); }
      .stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 8px 20px; margin: 6px 0 4px; }
      .stats > div { display: flex; justify-content: space-between; border-bottom: 1px solid var(--line); padding: 6px 0; font-size: 13px; }
      .stats .v { font-weight: 700; font-variant-numeric: tabular-nums; }
      .issues, .runs { display: flex; flex-direction: column; gap: 6px; margin-top: 6px; }
      .issue { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; text-decoration: none;
               border: 1px solid var(--line); border-radius: 8px; padding: 8px 10px; color: var(--ink); }
      .issue:hover { background: var(--hover); }
      .sev, .run-status {
        font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.04em;
        border-radius: 6px; padding: 1px 7px;
      }
      .sev.critical, .run-status.failed { background: var(--danger, #b4402f); color: #fff; }
      .sev.warning { background: #b9770622; color: #b97706; }
      .sev.info { background: var(--accent-soft); color: var(--accent); }
      .run-status.success { background: var(--accent); color: var(--brand-ink); }
      .run-status.running { background: var(--accent-soft); color: var(--accent); }
      .issue-title, .run-kind { font-weight: 600; }
      .run { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; border-bottom: 1px solid var(--line); padding: 7px 0; font-size: 13px; }
      .run-detail { font-family: monospace; font-size: 11.5px; }
    `,
  ],
})
export class Backups {
  private api = inject(ApiService);
  private spaceCtx = inject(SpaceContext);

  protected report = signal<IntegrityReport | null>(null);
  protected history = signal<BackupRun[]>([]);
  protected loading = signal(false);
  protected error = signal<string | null>(null);

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
