import { Component, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api.service';
import { SpaceContext } from '../../core/space.context';
import { DriveStatus, IngestAddress, Member } from '../../core/models';

@Component({
  selector: 'app-spaces',
  imports: [FormsModule],
  template: `
    <div class="card">
      <h1>Spaces</h1>
      <form (ngSubmit)="createSpace()" class="inline-form">
        <label>New shared space <input name="newName" [(ngModel)]="newName" placeholder="e.g. Household" /></label>
        <button type="submit" [disabled]="!newName.trim()">Create</button>
      </form>
      <p class="muted">Current space: <b>{{ spaceCtx.current()?.name || '—' }}</b>
        ({{ spaceCtx.current()?.kind }}). Switch spaces from the top-right selector.</p>
    </div>

    <div class="card">
      <h3>Members</h3>
      @if (membersError()) { <p class="muted">{{ membersError() }}</p> }
      @else {
        <table>
          <thead><tr><th>User</th><th>Role</th></tr></thead>
          <tbody>
            @for (m of members(); track m.userId) { <tr><td>{{ m.userId }}</td><td>{{ m.role }}</td></tr> }
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
      @if (ingest(); as a) {
        <p>Forward documents to: <code>{{ a.address }}</code></p>
        <button (click)="rotate()">Rotate</button>
      } @else { <p class="muted">{{ ingestError() || 'Loading…' }}</p> }
    </div>

    <div class="card">
      <h3>Google Drive backup</h3>
      @if (drive(); as d) {
        @if (d.connected) {
          <p>✅ Connected. Last sync: {{ d.lastSyncAt || 'never' }}.</p>
          <button (click)="sync()" [disabled]="syncing()">{{ syncing() ? 'Syncing…' : 'Sync now' }}</button>
          @if (syncMsg()) { <span class="muted"> {{ syncMsg() }}</span> }
        } @else {
          <p class="muted">Not connected — back this space up to the owner's Google Drive.</p>
          <button (click)="connect()">Connect Google Drive</button>
        }
      } @else { <p class="muted">{{ driveError() || 'Loading…' }}</p> }
    </div>

    <div class="card">
      <h3>Export</h3>
      <p class="muted">Download everything (manifest.json + data.csv + files/) as a ZIP.</p>
      <button (click)="exportZip()" [disabled]="exporting()">{{ exporting() ? 'Preparing…' : 'Download export' }}</button>
    </div>
  `,
})
export class Spaces {
  protected spaceCtx = inject(SpaceContext);
  private api = inject(ApiService);

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
      error: (e) => { this.drive.set(null); this.driveError.set(e?.error?.message ?? '—'); },
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
