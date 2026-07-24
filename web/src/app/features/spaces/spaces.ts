import { Component, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { SpaceContext } from '../../core/space.context';
import { AuthService } from '../../core/auth.service';
import { NoticeService } from '../../core/notice/notice.service';
import { ConfirmService } from '../../core/confirm.service';
import { DriveConnectionView, DriveStatus, IngestAddress, Invitation, Member } from '../../core/models';
import { HelpCard } from '../../core/help-card';
import { InfoTip } from '../../core/info-tip';
import { DateTimePipe } from '../../core/datetime.pipe';
import { TERMS } from '../../core/terms';
import { TroveSelect, SelectOption } from '../../core/select';

@Component({
  selector: 'app-spaces',
  imports: [FormsModule, HelpCard, InfoTip, DateTimePipe, TroveSelect],
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
                  } @else if (m.status === 'pending' && m.selfRequested) {
                    <span class="badge">wants to join</span>
                    <button type="button" class="approve" (click)="approveMember(m)">Approve</button>
                    <button type="button" class="dismiss" title="Decline" (click)="dismiss(m)">✕</button>
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

        <div class="join-link">
          <div class="join-head">
            <span>Or share a join link</span>
            <trove-info-tip text="Send this link to someone; opening it lets them REQUEST to join, and you approve them above. It never auto-adds anyone. Rotate to invalidate the old link, or Revoke to turn it off."></trove-info-tip>
          </div>
          @if (joinUrl()) {
            <div class="join-row">
              <input readonly [value]="joinUrl()" (focus)="selectAll($event)" />
              <button type="button" class="btn-ghost sm" (click)="copyJoinLink()">Copy</button>
              <button type="button" class="btn-ghost sm" (click)="rotateJoinLink()">Rotate</button>
              <button type="button" class="btn-ghost sm" (click)="revokeJoinLink()">Revoke</button>
            </div>
          } @else {
            <button type="button" class="btn-ghost" (click)="getJoinLink()">Get join link</button>
          }
        </div>
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
      <trove-help-card
        title="Pooling several Drives & deleted files"
        [open]="false"
        [user]="drivePoolHelpUser"
        [dev]="drivePoolHelpDev">
      </trove-help-card>
      @if (drive(); as d) {
        @if (d.connected) {
          <!-- How several Drives are used together. -->
          <div class="drive-mode">
            <span class="drive-mode-label">With several Drives</span>
            <div class="ccy">
              <button type="button" class="chip sm" [class.on]="d.mode === 'rotate'" (click)="setMode('rotate')">Rotate</button>
              <button type="button" class="chip sm" [class.on]="d.mode === 'mirror'" (click)="setMode('mirror')">Mirror</button>
            </div>
          </div>
          <p class="muted small mode-hint">
            @if (d.mode === 'mirror') {
              Every document is copied into <b>all</b> linked Drives - a redundant, independent backup.
            } @else {
              Documents fill the <b>active</b> Drive, then roll to the next when it is full - pooled capacity.
            }
          </p>

          @for (c of d.connections; track c.id) {
            <div class="drive-conn" [class.is-active]="c.active && d.mode === 'rotate'">
              <div class="drive-conn-head">
                <span class="drive-account-email">{{ c.googleEmail || 'Google Drive' }}</span>
                @if (c.googleAccountName) { <span class="muted">({{ c.googleAccountName }})</span> }
                @if (d.mode === 'rotate' && c.active) { <span class="badge badge-active">Active</span> }
                @if (c.status === 'full') { <span class="badge badge-full">Full</span> }
              </div>
              <div class="drive-conn-sub muted">
                @if (c.connectedByName) { linked by {{ c.connectedByName }} · }
                last sync {{ c.lastSyncAt ? (c.lastSyncAt | prettyDate) : 'never' }}
              </div>
              @if (c.storageLimitBytes) {
                <div class="storage">
                  <div class="storage-bar" [title]="connTitle(c)">
                    <span class="seg seg-trove" [style.width.%]="pctOf(c.troveBytes, c.storageLimitBytes)"></span>
                    <span class="seg seg-other" [style.width.%]="pctOtherC(c)"></span>
                  </div>
                  <p class="storage-text muted">
                    <span class="dot dot-trove"></span> Trove {{ fmtBytes(c.troveBytes) }}
                    <span class="dot dot-other"></span> other {{ fmtBytes((c.storageUsageBytes ?? 0) - (c.troveBytes ?? 0)) }}
                    · {{ fmtBytes(c.storageUsageBytes) }} of {{ fmtBytes(c.storageLimitBytes) }} used
                  </p>
                </div>
              } @else if (c.troveBytes) {
                <p class="storage-text muted">Trove {{ fmtBytes(c.troveBytes) }} stored · account storage is unlimited</p>
              }
              <div class="drive-conn-actions">
                @if (d.mode === 'rotate' && !c.active) {
                  <button type="button" class="btn-ghost sm" (click)="activate(c.id)">Make active</button>
                  <trove-info-tip text="Make this the Drive new documents sync to (rotate mode). The others stay as-is."></trove-info-tip>
                }
                <button type="button" class="btn-ghost sm" (click)="disconnect(c.id)">Disconnect</button>
                <trove-info-tip text="Unlink this Drive from the space. Files already backed up STAY in the Drive; Trove just stops syncing to it. You can reconnect later."></trove-info-tip>
              </div>
            </div>
          }

          <div class="drive-foot">
            <button (click)="sync()" [disabled]="syncing()">{{ syncing() ? 'Syncing…' : 'Sync now' }}</button>
            <trove-info-tip text="Copies any documents not yet backed up into the space's Drive(s) right now, instead of waiting for the hourly auto-sync. Safe to run anytime."></trove-info-tip>
            <button type="button" class="btn-ghost" (click)="connect()">+ Connect another Drive</button>
            <trove-info-tip text="Link one more Google Drive to pool its free space. Opens Google's consent screen; you choose the account."></trove-info-tip>
            @if (syncMsg()) { <span class="muted"> {{ syncMsg() }}</span> }
          </div>
        } @else {
          <p class="muted">Not connected. Back this space up into a member's {{ terms.driveBackup }} - anyone in the space can link their own.</p>
          <div class="drive-foot">
            <button (click)="connect()">Connect {{ terms.driveBackup }}</button>
            <trove-info-tip text="Opens Google's consent screen to link a Drive. Trove only touches files it creates (drive.file scope) and backs this space's documents into it."></trove-info-tip>
          </div>
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
      .drive-account-email { font-weight: 600; font-family: monospace; }

      /* Rotate/Mirror mode toggle. */
      .drive-mode { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin: 2px 0 4px; }
      .drive-mode-label { font-size: 11px; text-transform: uppercase; letter-spacing: 0.05em; color: var(--muted); }
      .mode-hint { margin: 0 0 12px; }
      .ccy { display: flex; align-items: center; gap: 6px; }
      .chip {
        margin: 0; border: 1px solid var(--accent-line); background: transparent; color: var(--accent);
        border-radius: 999px; padding: 3px 12px; font-size: 13px; font-weight: 600; cursor: pointer;
      }
      .chip.sm { padding: 2px 10px; font-size: 12px; }
      .chip.on { background: var(--accent); color: var(--brand-ink); border-color: var(--accent); }

      /* One linked Drive. The active one (rotate mode) gets an accent frame. */
      .drive-conn { border: 1px solid var(--line); border-radius: 10px; padding: 12px 14px; margin: 8px 0; }
      .drive-conn.is-active { border-color: var(--accent-line); background: var(--accent-soft); }
      .drive-conn-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
      .drive-conn-sub { font-size: 0.8rem; margin: 3px 0 0; }
      .drive-conn-actions { display: flex; align-items: center; gap: 8px; margin-top: 8px; }
      .badge { font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.04em; border-radius: 6px; padding: 1px 7px; }
      .badge-active { background: var(--accent); color: var(--brand-ink); }
      .badge-full { background: var(--danger, #b4402f); color: #fff; }
      .btn-ghost.sm { padding: 3px 10px; font-size: 12px; margin: 0; border-radius: 8px; cursor: pointer; }
      .drive-foot { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-top: 12px; }
      /* Storage bar: Trove's share (accent) + other files (muted grey) over the free track. */
      .storage { margin: 6px 0 12px; }
      .storage-bar {
        display: flex; height: 8px; width: 100%; border-radius: 999px; overflow: hidden;
        background: var(--hover); border: 1px solid var(--line);
      }
      .storage-bar .seg { height: 100%; }
      .seg-trove { background: var(--accent); }
      .seg-other { background: var(--muted); opacity: 0.45; }
      .storage-text { margin: 5px 0 0; font-size: 0.82rem; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
      .dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; }
      .dot-trove { background: var(--accent); }
      .dot-other { background: var(--muted); opacity: 0.45; }
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
      .approve { margin: 0 0 0 8px; padding: 3px 12px; background: var(--accent); color: var(--brand-ink); border: 0; border-radius: 6px; cursor: pointer; font-size: 12px; font-weight: 600; }
      .approve:hover { filter: brightness(1.05); }
      .join-link { margin-top: 1.25rem; padding-top: 1rem; border-top: 1px solid var(--line); }
      .join-head { display: flex; align-items: center; gap: 6px; font-size: 0.9rem; font-weight: 600; margin-bottom: 8px; }
      .join-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
      .join-row input { flex: 1; min-width: 220px; margin: 0; font-family: monospace; font-size: 12px; }
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
  private confirm = inject(ConfirmService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);

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
    `Trove / space / category / month. It syncs automatically about once an hour, and "Sync now" copies ` +
    `everything right away. If the app and ${TERMS.database} are ever gone, you can still open ` +
    `${TERMS.driveBackup} and find every document.`;
  protected driveHelpDev =
    `Tier-3 of the backup design: ${TERMS.objectStorage} first, then ${TERMS.mirrorStorage} (a separate ` +
    `job, also roughly hourly), then ${TERMS.driveBackup}. A per-owner OAuth token lets a scheduled job ` +
    `copy new files into the folder tree about once an hour (a fixed one-hour gap between runs); "Sync now" ` +
    `runs it on demand. ${TERMS.database} is a rebuildable index; these files are a source of truth, so ` +
    `nothing is lost if it is wiped.`;

  protected drivePoolHelpUser =
    `A space can back up into more than one ${TERMS.driveBackup} at once - anyone in the space can link ` +
    `their own, so you pool everyone's free 15 GB. Two modes: Rotate fills the active Drive and rolls to ` +
    `the next when it is nearly full (more total room); Mirror copies every document into all linked Drives ` +
    `(a second, independent backup of everything). Each Drive shows how much of its space Trove is using; ` +
    `the owner picks the active Drive, switches the mode, or disconnects any, while anyone can remove the ` +
    `one they linked. Deleting a document moves it to a "Trove / _Deleted" folder in the Drive for 30 days, ` +
    `so you can still recover it straight from ${TERMS.driveBackup}, after which it is removed for good.`;
  protected drivePoolHelpDev =
    `Pooling: the Drive connection is many-per-space; the folder-id cache and per-document sync state are ` +
    `keyed per connection (each Drive has its own Trove tree and its own copy of a file). Rotate syncs to ` +
    `the active connection and rolls to the next once account usage crosses 98% of quota; Mirror syncs every ` +
    `document into every connection. Scope stays drive.file only - account identity and storage quota come ` +
    `from about.get (no extra consent). Members link via write access; activating a Drive and changing the ` +
    `mode are owner-only. Soft-delete, restore and the 30-day purge reflect into Drive through document ` +
    `lifecycle events: the file moves to _Deleted on delete, back to its category/month folder on restore, ` +
    `and is hard-deleted on purge.`;

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
    this.handleDriveCallback();
  }

  /** Turns the ?drive=… flag from the OAuth callback into a toast, then strips it. */
  private handleDriveCallback(): void {
    const status = this.route.snapshot.queryParamMap.get('drive');
    if (!status) return;
    if (status === 'connected') {
      this.notices.show({ level: 'success', code: 'DRIVE_CONNECTED', userMessage: `${TERMS.driveBackup} connected for this space.` });
    } else if (status === 'noRefresh') {
      this.notices.show({
        level: 'warning', code: 'DRIVE_NO_REFRESH',
        userMessage: `${TERMS.driveBackup} didn't return access. Remove Trove at myaccount.google.com/permissions, then connect again.`,
      });
    } else {
      this.notices.show({ level: 'error', code: 'DRIVE_ERROR', userMessage: `Couldn't connect ${TERMS.driveBackup}. Please try again.` });
    }
    // Drop the query param so a refresh doesn't re-toast, and refresh the connection status.
    this.router.navigate([], { queryParams: {}, replaceUrl: true });
    const sid = this.spaceCtx.currentSpaceId();
    if (sid) this.loadSpace(sid);
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

  approveMember(m: Member): void {
    const sid = this.spaceCtx.currentSpaceId();
    if (!sid) return;
    this.api.approveMember(sid, m.userId).subscribe({
      next: () => {
        this.notices.show({ level: 'success', code: 'MEMBER_APPROVED', userMessage: `${m.email || 'Member'} approved.` });
        this.loadSpace(sid);
      },
      error: (e) => this.notices.show({ level: 'error', code: 'APPROVE_FAIL', userMessage: e?.error?.message ?? 'Could not approve.' }),
    });
  }

  // ── join link (owner) ──────────────────────────────────────────────────────
  joinUrl = signal<string | null>(null);

  getJoinLink(): void {
    const sid = this.spaceCtx.currentSpaceId();
    if (!sid) return;
    this.api.spaceJoinLink(sid).subscribe({ next: (r) => this.joinUrl.set(r.url) });
  }
  rotateJoinLink(): void {
    const sid = this.spaceCtx.currentSpaceId();
    if (!sid) return;
    this.api.rotateSpaceJoinLink(sid).subscribe({
      next: (r) => { this.joinUrl.set(r.url); this.notices.show({ level: 'info', code: 'LINK_ROTATED', userMessage: 'Old link invalidated; new one ready.' }); },
    });
  }
  revokeJoinLink(): void {
    const sid = this.spaceCtx.currentSpaceId();
    if (!sid) return;
    this.api.revokeSpaceJoinLink(sid).subscribe({
      next: () => { this.joinUrl.set(null); this.notices.show({ level: 'info', code: 'LINK_REVOKED', userMessage: 'Join link turned off.' }); },
    });
  }
  copyJoinLink(): void {
    const url = this.joinUrl();
    if (url) {
      navigator.clipboard?.writeText(url);
      this.notices.show({ level: 'success', code: 'LINK_COPIED', userMessage: 'Join link copied.' });
    }
  }
  selectAll(e: Event): void {
    (e.target as HTMLInputElement)?.select();
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
      next: () => { this.memberMsg.set('Invitation sent - waiting for them to accept.'); this.memberEmail = ''; this.loadSpace(sid); },
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

  // ── Drive storage bar helpers ──────────────────────────────────────────────
  /** Percentage of `limit` taken by `part`, clamped 0-100 (0 when limit unknown). */
  pctOf(part: number | null, limit: number | null): number {
    if (!limit || !part) return 0;
    return Math.min(100, Math.max(0, (part / limit) * 100));
  }
  /** Width of the "used by other apps/files" segment (total usage minus Trove's share). */
  pctOtherC(c: DriveConnectionView): number {
    const other = (c.storageUsageBytes ?? 0) - (c.troveBytes ?? 0);
    return this.pctOf(other, c.storageLimitBytes);
  }
  /** Human-readable bytes: 1.2 GB, 940 MB, 512 KB. */
  fmtBytes(n: number | null): string {
    const b = n ?? 0;
    if (b <= 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.min(units.length - 1, Math.floor(Math.log(b) / Math.log(1024)));
    const v = b / Math.pow(1024, i);
    return `${v >= 100 || i === 0 ? Math.round(v) : v.toFixed(1)} ${units[i]}`;
  }
  connTitle(c: DriveConnectionView): string {
    return `Trove ${this.fmtBytes(c.troveBytes)} of ${this.fmtBytes(c.storageLimitBytes)} total`;
  }

  /** Reloads just the Drive status (lighter than loadSpace, which also hits owner-only endpoints). */
  private reloadDrive(sid: string): void {
    this.api.driveStatus(sid).subscribe({
      next: (d) => this.drive.set(d),
      error: (e) => this.driveError.set(e?.error?.message ?? '-'),
    });
  }

  setMode(mode: string): void {
    const sid = this.spaceCtx.currentSpaceId();
    if (!sid || this.drive()?.mode === mode) return;
    this.api.driveSetMode(sid, mode).subscribe({
      next: () => this.reloadDrive(sid),
      error: (e) => this.notices.show({ level: 'error', code: 'DRIVE_MODE', userMessage: e?.error?.message ?? 'Only the owner can change the backup mode.' }),
    });
  }

  activate(connectionId: string): void {
    const sid = this.spaceCtx.currentSpaceId();
    if (!sid) return;
    this.api.driveActivate(sid, connectionId).subscribe({
      next: () => this.reloadDrive(sid),
      error: (e) => this.notices.show({ level: 'error', code: 'DRIVE_ACTIVATE', userMessage: e?.error?.message ?? 'Only the owner can switch the active Drive.' }),
    });
  }

  disconnect(connectionId: string): void {
    const sid = this.spaceCtx.currentSpaceId();
    if (!sid) return;
    this.confirm.ask({
      title: 'Unlink this Drive?',
      message: 'Files already backed up stay in the Drive - Trove just stops syncing to it. '
        + 'Use "+ Connect another Drive" afterwards to link a different one.',
      confirmLabel: 'Unlink',
    }).then((ok) => {
      if (!ok) return;
      this.api.driveDisconnect(sid, connectionId).subscribe({
        next: () => { this.notices.show({ level: 'info', code: 'DRIVE_DISCONNECTED', userMessage: 'Drive unlinked from this space.' }); this.reloadDrive(sid); },
        // Always refresh - even on error the card must reflect reality, so a stale row can't linger.
        error: (e) => { this.notices.show({ level: 'error', code: 'DRIVE_DISCONNECT', userMessage: e?.error?.message ?? 'Could not unlink this Drive.' }); this.reloadDrive(sid); },
      });
    });
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
