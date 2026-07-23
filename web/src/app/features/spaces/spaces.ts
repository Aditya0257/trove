import { Component, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api.service';
import { SpaceContext } from '../../core/space.context';
import { DriveStatus, IngestAddress, Member } from '../../core/models';
import { HelpCard } from '../../core/help-card';
import { DateTimePipe } from '../../core/datetime.pipe';
import { TERMS } from '../../core/terms';

@Component({
  selector: 'app-spaces',
  imports: [FormsModule, HelpCard, DateTimePipe],
  template: `
    <div class="card">
      <h1>Spaces</h1>
      <form (ngSubmit)="createSpace()" class="inline-form">
        <label>New shared space <input name="newName" [(ngModel)]="newName" placeholder="e.g. Household" /></label>
        <button type="submit" [disabled]="!newName.trim()">Create</button>
      </form>
      <p class="muted">Current space: <b>{{ spaceCtx.current()?.name || '-' }}</b>
        ({{ spaceCtx.current()?.kind }}). Switch spaces from the top-right selector.</p>
    </div>

    <div class="card">
      <h3>Members</h3>
      <trove-help-card
        title="About members"
        [open]="false"
        user="The people who can see and use this space. Roles: owner (full control, backup and billing), member (add and edit documents), viewer (read-only)."
        dev="Membership lives in the space_member table; every request checks the caller's role against the space before returning anything. A personal space always has exactly one owner: you.">
      </trove-help-card>
      @if (membersError()) { <p class="muted">{{ membersError() }}</p> }
      @else {
        <table>
          <thead><tr><th>User</th><th>Role</th></tr></thead>
          <tbody>
            @for (m of members(); track m.userId) {
              <tr>
                <td>
                  <div class="member-name">{{ m.displayName || 'Unknown user' }}</div>
                  <div class="member-sub">{{ m.email || m.userId }}</div>
                </td>
                <td>{{ m.role }}</td>
              </tr>
            }
          </tbody>
        </table>
        <form (ngSubmit)="addMember()" class="inline-form">
          <label>Invite by email <input name="email" [(ngModel)]="memberEmail" /></label>
          <label>Role
            <select name="role" [(ngModel)]="memberRole">
              <option value="member">member</option>
              <option value="viewer">viewer</option>
              <option value="owner">owner</option>
            </select>
          </label>
          <button type="submit" [disabled]="!memberEmail.trim()">Add</button>
        </form>
        @if (memberMsg()) { <p class="muted">{{ memberMsg() }}</p> }
      }
    </div>

    <div class="card">
      <h3>Forward-to-file address</h3>
      <trove-help-card
        title="What is this address?"
        [open]="false"
        user="This space has its own private email address (shown below). Put it in the To, CC or BCC line when you send or forward any document to it (say a bill sitting in your inbox), and Trove saves the attachment straight into this space and reads it just like a normal upload: it pulls out the merchant, amount, date and category, then leaves it in needs-review for you to confirm. It is for filing without opening the app: forward and forget. Treat the address like a password; if it ever leaks, press Rotate to swap it for a fresh one."
        dev="The address carries a per-space secret token. Your mail provider delivers the message to the ingest webhook, which matches the token to this space and runs each attachment through the same pipeline as an upload (stored, read by the AI, left in needs-review). It reads the attachment, not the email body; the sender is kept only for provenance. Real delivery needs the ingest domain's inbound mail routed to the app (a deployment step); until then the address is reserved but will not receive mail.">
      </trove-help-card>
      @if (ingest(); as a) {
        <p>Forward documents to: <code>{{ a.address }}</code></p>
        <button (click)="rotate()">Rotate</button>
      } @else { <p class="muted">{{ ingestError() || 'Loading…' }}</p> }
    </div>

    <div class="card">
      <h3>{{ terms.driveBackup }} backup</h3>
      <trove-help-card
        [title]="'About ' + terms.driveBackup + ' backup'"
        [open]="false"
        [user]="driveHelpUser"
        [dev]="driveHelpDev">
      </trove-help-card>
      @if (drive(); as d) {
        @if (d.connected) {
          <p>✅ Connected. Last sync: {{ d.lastSyncAt ? (d.lastSyncAt | prettyDate) : 'never' }}.</p>
          <button (click)="sync()" [disabled]="syncing()">{{ syncing() ? 'Syncing…' : 'Sync now' }}</button>
          @if (syncMsg()) { <span class="muted"> {{ syncMsg() }}</span> }
        } @else {
          <p class="muted">Not connected. Back this space up to the owner's {{ terms.driveBackup }}.</p>
          <button (click)="connect()">Connect {{ terms.driveBackup }}</button>
        }
      } @else { <p class="muted">{{ driveError() || 'Loading…' }}</p> }
    </div>

    <div class="card">
      <h3>Export</h3>
      <trove-help-card
        title="About export"
        [open]="false"
        user="Downloads this whole space as one ZIP containing manifest.json (every record, machine-readable), data.csv (open in Excel or Sheets), and files/ (your original images and PDFs). A large vault takes a little while to prepare, so give it a moment."
        dev="Streamed and zipped server-side. The manifest is the complete record set for a lossless re-import, the CSV is a flattened human view, and files/ are the originals pulled from object storage. Uploading this ZIP back fully restores the system: the ultimate 'no provider outage can wipe me' guarantee.">
      </trove-help-card>
      <button (click)="exportZip()" [disabled]="exporting()">{{ exporting() ? 'Preparing…' : 'Download export' }}</button>
    </div>
  `,
  styles: [
    `
      .member-name { font-weight: 600; }
      .member-sub { font-size: 12px; color: var(--muted); font-family: monospace; }
    `,
  ],
})
export class Spaces {
  protected spaceCtx = inject(SpaceContext);
  private api = inject(ApiService);

  /** Vendor-neutral labels (see core/terms.ts) so provider swaps are one-file changes. */
  protected terms = TERMS;
  protected driveHelpUser =
    `Keeps a human-browsable copy of this space's files in ${TERMS.driveBackup}, organised as ` +
    `Trove / space / category / month. If the app and ${TERMS.database} are ever gone, you can still ` +
    `open ${TERMS.driveBackup} and find every document.`;
  protected driveHelpDev =
    `Tier-3 of the backup design: ${TERMS.objectStorage} first, then ${TERMS.mirrorStorage}, then ` +
    `${TERMS.driveBackup}. A per-owner OAuth token lets a scheduled job mirror new files into the folder ` +
    `tree. ${TERMS.database} is a rebuildable index; these files are a source of truth, so nothing is lost ` +
    `if it is wiped.`;

  newName = '';
  members = signal<Member[]>([]);
  membersError = signal<string | null>(null);
  memberEmail = '';
  memberRole = 'member';
  memberMsg = signal<string | null>(null);
  ingest = signal<IngestAddress | null>(null);
  ingestError = signal<string | null>(null);
  drive = signal<DriveStatus | null>(null);
  driveError = signal<string | null>(null);
  syncing = signal(false);
  syncMsg = signal<string | null>(null);
  exporting = signal(false);

  constructor() {
    effect(() => {
      const sid = this.spaceCtx.currentSpaceId();
      if (sid) {
        this.loadSpace(sid);
      }
    });
  }

  private loadSpace(sid: string): void {
    this.membersError.set(null);
    this.ingestError.set(null);
    this.driveError.set(null);
    this.memberMsg.set(null);
    this.api.listMembers(sid).subscribe({
      next: (m) => this.members.set(m),
      error: (e) => this.membersError.set(e?.error?.message ?? 'Members are owner-only'),
    });
    this.api.ingestAddress(sid).subscribe({
      next: (a) => this.ingest.set(a),
      error: (e) => { this.ingest.set(null); this.ingestError.set(e?.error?.message ?? 'Owner only'); },
    });
    this.api.driveStatus(sid).subscribe({
      next: (d) => this.drive.set(d),
      error: (e) => { this.drive.set(null); this.driveError.set(e?.error?.message ?? '-'); },
    });
  }

  createSpace(): void {
    if (!this.newName.trim()) return;
    this.api.createSpace(this.newName.trim()).subscribe(() => {
      this.newName = '';
      this.spaceCtx.load();
    });
  }

  addMember(): void {
    const sid = this.spaceCtx.currentSpaceId();
    if (!sid || !this.memberEmail.trim()) return;
    this.api.addMember(sid, this.memberEmail.trim(), this.memberRole).subscribe({
      next: () => { this.memberMsg.set('Added.'); this.memberEmail = ''; this.loadSpace(sid); },
      error: (e) => this.memberMsg.set(e?.error?.message ?? 'Could not add member'),
    });
  }

  rotate(): void {
    const sid = this.spaceCtx.currentSpaceId();
    if (!sid) return;
    this.api.rotateIngestAddress(sid).subscribe((a) => this.ingest.set(a));
  }

  connect(): void {
    const sid = this.spaceCtx.currentSpaceId();
    if (!sid) return;
    this.api.driveAuthorizeUrl(sid).subscribe((r) => window.open(r.url, '_blank'));
  }

  sync(): void {
    const sid = this.spaceCtx.currentSpaceId();
    if (!sid) return;
    this.syncing.set(true);
    this.syncMsg.set(null);
    this.api.driveSync(sid).subscribe({
      next: (r) => { this.syncMsg.set(`synced ${r.synced}, skipped ${r.skipped}`); this.syncing.set(false); this.loadSpace(sid); },
      error: (e) => { this.syncMsg.set(e?.error?.message ?? 'Sync failed'); this.syncing.set(false); },
    });
  }

  exportZip(): void {
    this.exporting.set(true);
    this.api.exportZip(this.spaceCtx.currentSpaceId()).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'vault-export.zip';
        a.click();
        URL.revokeObjectURL(url);
        this.exporting.set(false);
      },
      error: () => this.exporting.set(false),
    });
  }
}
