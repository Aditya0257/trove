import { Component, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../core/api.service';
import { SpaceContext } from '../../core/space.context';
import { AuthService } from '../../core/auth.service';
import { NoticeService } from '../../core/notice/notice.service';
import { DriveStatus, IngestAddress, Invitation, Member } from '../../core/models';
import { HelpCard } from '../../core/help-card';
import { DateTimePipe } from '../../core/datetime.pipe';
import { TERMS } from '../../core/terms';
import { TroveSelect, SelectOption } from '../../core/select';

@Component({
  selector: 'app-spaces',
  imports: [FormsModule, HelpCard, DateTimePipe, TroveSelect],
  template: `
    @if (invitations().length) {
      <div class="card invites">
        <h3>Invitations</h3>
        <p class="muted">You've been invited to these spaces. Accept to join, or decline.</p>
        @for (inv of invitations(); track inv.spaceId) {
          <div class="invite">
            <div class="invite-info">
              <b>{{ inv.spaceName }}</b> <span class="tag">{{ inv.role }}</span>
              <span class="muted">invited by {{ inv.invitedByName || inv.invitedByEmail || 'someone' }}</span>
            </div>
            <div class="invite-actions">
              <button type="button" (click)="accept(inv)" [disabled]="busy()">Accept</button>
              <button type="button" class="btn-ghost" (click)="decline(inv)" [disabled]="busy()">Decline</button>
            </div>
          </div>
        }
      </div>
    }

    <div class="card">
      <h1>Spaces</h1>
      <form (ngSubmit)="createSpace()" class="inline-form">
        <label>New shared space <input name="newName" [(ngModel)]="newName" placeholder="e.g. Household" /></label>
        <button type="submit" [disabled]="!newName.trim()">Create</button>
      </form>
      <p class="muted">Current space: <b>{{ spaceCtx.current()?.name || '-' }}</b>
        ({{ spaceCtx.current()?.kind }}). Switch spaces from the top-right selector.</p>
      @if (spaceCtx.current()?.description) {
        <p class="space-bio">{{ spaceCtx.current()?.description }}</p>
      }
    </div>

    @if (isOwner()) {
      <div class="card">
        <h3>Space settings</h3>
        <label>Space name <input name="spaceName" [(ngModel)]="editName" placeholder="e.g. Household" /></label>
        <label>Description / bio (optional)
          <textarea name="spaceDesc" [(ngModel)]="editDescription" rows="2"
            placeholder="What this space is for, or who it's shared with"></textarea>
        </label>
        <button (click)="saveSpace()" [disabled]="savingSpace() || !editName.trim()">
          {{ savingSpace() ? 'Saving…' : 'Save changes' }}
        </button>

        @if (spaceCtx.current()?.kind === 'shared') {
          <div class="danger">
            <h4>Danger zone</h4>
            <p class="muted">Deleting removes this space and <b>all its documents, for everyone</b>. It can't be undone from the app.</p>
            <button type="button" class="btn-danger" (click)="askDelete()">Delete this space</button>
          </div>
        }
      </div>
    }

    @if (deleting()) {
      <div class="scrim" (click)="cancelDelete()"></div>
      <div class="modal" role="dialog" aria-modal="true">
        <h3>Delete "{{ spaceCtx.current()?.name }}"?</h3>
        <p class="muted">This permanently removes the space and every document in it for all members.
          To confirm, type the space name below.</p>
        <input name="delConfirm" [(ngModel)]="deleteConfirm" [placeholder]="spaceCtx.current()?.name || ''" autocomplete="off" />
        <div class="modal-actions">
          <button type="button" class="btn-ghost" (click)="cancelDelete()">Cancel</button>
          <button type="button" class="btn-danger"
            [disabled]="deleteConfirm !== spaceCtx.current()?.name || deleteBusy()" (click)="confirmDelete()">
            {{ deleteBusy() ? 'Deleting…' : 'Delete space' }}
          </button>
        </div>
      </div>
    }

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
                <td>
                  @if (m.status === 'active') {
                    {{ m.role }}
                  } @else if (m.status === 'pending') {
                    <span class="badge">invite sent · {{ m.role }}</span>
                  } @else {
                    <span class="badge due">declined</span>
                    <button type="button" class="dismiss" title="Dismiss this row" (click)="dismiss(m)">✕</button>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
        <form (ngSubmit)="addMember()" class="inline-form invite-form">
          <label>Invite by email <input name="email" [(ngModel)]="memberEmail" /></label>
          <label>Role
            <trove-select name="role" [(ngModel)]="memberRole" [options]="roleOptions" ariaLabel="Role"></trove-select>
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
      /* Breathing room + a divider between the members table and the invite row. */
      .invite-form { margin-top: 1.25rem; padding-top: 1rem; border-top: 1px solid var(--line); }
      .invites { border-left: 3px solid var(--accent); }
      .invite { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 0; border-top: 1px solid var(--line); flex-wrap: wrap; }
      .invite-info { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
      .invite-actions { display: flex; gap: 8px; }
      .invite-actions button { margin: 0; }
      .btn-ghost { background: transparent; color: var(--muted); border: 1px solid var(--line); }
      .btn-ghost:hover { background: var(--hover); }
      .tag { background: var(--accent-soft); color: var(--accent); border-radius: 999px; padding: 2px 10px; font-size: 12px; }
      .dismiss { margin: 0 0 0 8px; padding: 2px 8px; background: transparent; border: 1px solid var(--line); color: var(--muted); border-radius: 6px; cursor: pointer; font-size: 12px; }
      .dismiss:hover { color: var(--danger); border-color: var(--danger-line); }
      .space-bio { margin: 6px 0 0; }
      textarea { width: 100%; box-sizing: border-box; resize: vertical; font-family: inherit; }
      .danger { margin-top: 1.5rem; padding-top: 1rem; border-top: 1px solid var(--danger-line); }
      .danger h4 { margin: 0 0 4px; color: var(--danger); font-size: 0.95rem; }
      .btn-danger { margin: 0; background: var(--danger); color: #fff; border: 0; }
      .btn-danger:hover:not(:disabled) { filter: brightness(0.94); }
      .scrim { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.4); z-index: 1100; }
      .modal {
        position: fixed; z-index: 1101; top: 50%; left: 50%; transform: translate(-50%, -50%);
        width: min(460px, 92vw); background: var(--card); border: 1px solid var(--line);
        border-radius: 12px; padding: 1.25rem 1.4rem; box-shadow: 0 20px 60px var(--shadow);
      }
      .modal h3 { margin: 0 0 0.5rem; }
      .modal-actions { display: flex; gap: 10px; justify-content: flex-end; margin-top: 1rem; }
      .modal-actions button { margin: 0; }
    `,
  ],
})
export class Spaces {
  protected spaceCtx = inject(SpaceContext);
  private api = inject(ApiService);
  private auth = inject(AuthService);
  private notices = inject(NoticeService);

  protected invitations = signal<Invitation[]>([]);
  protected busy = signal(false);

  // Space settings (rename + description) and delete.
  protected editName = '';
  protected editDescription = '';
  protected savingSpace = signal(false);
  protected deleting = signal(false);
  protected deleteBusy = signal(false);
  protected deleteConfirm = '';

  /** True when the signed-in user is the owner of the current space. */
  protected isOwner(): boolean {
    const myId = this.auth.user()?.userId;
    return !!myId && this.members().some((m) => m.userId === myId && m.role === 'owner' && m.status === 'active');
  }

  /** Vendor-neutral labels (see core/terms.ts) so provider swaps are one-file changes. */
  protected terms = TERMS;
  protected roleOptions: SelectOption[] = [
    { value: 'member', label: 'member' },
    { value: 'viewer', label: 'viewer' },
    { value: 'owner', label: 'owner' },
  ];
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
      // Keep the settings form in sync with whichever space is selected.
      const c = this.spaceCtx.current();
      this.editName = c?.name ?? '';
      this.editDescription = c?.description ?? '';
    });
    this.loadInvitations();
  }

  /** Rename / set description of the current space (owner only). */
  saveSpace(): void {
    const sid = this.spaceCtx.currentSpaceId();
    if (!sid || !this.editName.trim()) return;
    this.savingSpace.set(true);
    this.api.updateSpace(sid, this.editName.trim(), this.editDescription.trim()).subscribe({
      next: () => {
        this.spaceCtx.load();
        this.notices.show({ level: 'success', code: 'SPACE_SAVED', userMessage: 'Space updated.' });
        this.savingSpace.set(false);
      },
      error: () => this.savingSpace.set(false),
    });
  }

  askDelete(): void {
    this.deleteConfirm = '';
    this.deleting.set(true);
  }
  cancelDelete(): void {
    this.deleting.set(false);
  }

  confirmDelete(): void {
    const sid = this.spaceCtx.currentSpaceId();
    const name = this.spaceCtx.current()?.name;
    if (!sid || this.deleteConfirm !== name) return;
    this.deleteBusy.set(true);
    this.api.deleteSpace(sid).subscribe({
      next: () => {
        // Move off the deleted space (back to the personal one) before reloading.
        const personal = this.spaceCtx.spaces().find((s) => s.kind === 'personal');
        if (personal) this.spaceCtx.setCurrent(personal.id);
        this.spaceCtx.load();
        this.deleteBusy.set(false);
        this.deleting.set(false);
        this.notices.show({ level: 'success', code: 'SPACE_DELETED', userMessage: `Deleted "${name}".` });
      },
      error: () => this.deleteBusy.set(false),
    });
  }

  private loadInvitations(): void {
    this.api.listInvitations().subscribe({ next: (i) => this.invitations.set(i), error: () => {} });
  }

  /** Accept an invite: the space becomes active and shows up in the switcher for you. */
  accept(inv: Invitation): void {
    this.busy.set(true);
    this.api.acceptInvite(inv.spaceId).subscribe({
      next: () => {
        this.notices.show({ level: 'success', code: 'JOINED', userMessage: `Joined "${inv.spaceName}".` });
        this.loadInvitations();
        this.spaceCtx.load();
        this.busy.set(false);
      },
      error: () => this.busy.set(false),
    });
  }

  decline(inv: Invitation): void {
    this.busy.set(true);
    this.api.declineInvite(inv.spaceId).subscribe({
      next: () => {
        this.notices.show({ level: 'info', code: 'DECLINED', userMessage: `Declined "${inv.spaceName}".` });
        this.loadInvitations();
        this.busy.set(false);
      },
      error: () => this.busy.set(false),
    });
  }

  /** Owner removes a member, or dismisses a declined-invite row so it stops showing. */
  dismiss(m: Member): void {
    const sid = this.spaceCtx.currentSpaceId();
    if (!sid) return;
    this.api.removeMember(sid, m.userId).subscribe({ next: () => this.loadSpace(sid) });
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
      next: () => { this.memberMsg.set('Invitation sent — waiting for them to accept.'); this.memberEmail = ''; this.loadSpace(sid); },
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
