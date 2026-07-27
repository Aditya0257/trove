import { Component, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiService } from '../../core/services/api.service';
import { SpaceContext } from '../../core/services/space.context';
import { AuthService } from '../../core/services/auth.service';
import { NoticeService } from '../../core/services/notice.service';
import { ConfirmService } from '../../core/services/confirm.service';
import { DriveConnectionView, DriveStatus, IngestAddress, Invitation, Member } from '../../core/models/models';
import { HelpCard } from '../../shared/components/help-card';
import { InfoTip } from '../../shared/components/info-tip';
import { DateTimePipe } from '../../shared/pipes/datetime.pipe';
import { TERMS } from '../../core/config/terms';
import { TroveSelect, SelectOption } from '../../shared/components/select';

@Component({
  selector: 'app-spaces',
  imports: [FormsModule, HelpCard, InfoTip, DateTimePipe, TroveSelect],
  templateUrl: './spaces.html',
  styleUrl: './spaces.scss',
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
    // Self-heal: if we landed here before the user's spaces finished loading (a slow
    // or briefly-failed first load after login), ask for them now. load() is guarded
    // against overlapping calls, so this is safe to call whenever spaces aren't in yet.
    if (!this.spaceCtx.loaded()) this.spaceCtx.load();

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
      confirmLabel: 'Unlink', busyLabel: 'Unlinking...', danger: true,
    }).then((ok) => {
      if (!ok) return;
      this.api.driveDisconnect(sid, connectionId).subscribe({
        next: () => { this.confirm.close(); this.notices.show({ level: 'info', code: 'DRIVE_DISCONNECTED', userMessage: 'Drive unlinked from this space.' }); this.reloadDrive(sid); },
        // Always refresh - even on error the card must reflect reality, so a stale row can't linger.
        error: (e) => { this.confirm.close(); this.notices.show({ level: 'error', code: 'DRIVE_DISCONNECT', userMessage: e?.error?.message ?? 'Could not unlink this Drive.' }); this.reloadDrive(sid); },
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
