import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../core/services/auth.service';
import { NoticeService } from '../../core/services/notice.service';
import { HelpCard } from '../../shared/components/help-card';
import { InfoTip } from '../../shared/components/info-tip';
import { Avatar } from '../../shared/components/avatar';
import { TroveSelect, SelectOption } from '../../shared/components/select';
import { DateTimePipe } from '../../shared/pipes/datetime.pipe';
import { AdminUser } from '../../core/models/models';

/**
 * The account / profile screen reached from the top-bar avatar. One place for a user to
 * manage their identity (photo, name, email), their password, and two-factor. The
 * destructive "delete account" section is admin-only, since a delete wipes an entire vault
 * and Trove optimises for zero data loss.
 */
@Component({
  selector: 'app-account',
  imports: [FormsModule, HelpCard, InfoTip, Avatar, TroveSelect, DateTimePipe],
  template: `
    <div class="card">
      <h1>Your profile</h1>
      <div class="profile-head">
        <trove-avatar [name]="acct()?.displayName || ''" [url]="acct()?.avatarUrl ?? null" [size]="88"></trove-avatar>
        <div class="ph-actions">
          <label class="button sm">
            {{ uploadingPhoto() ? 'Uploading…' : (acct()?.avatarUrl ? 'Change photo' : 'Add photo') }}
            <input type="file" accept="image/*" (change)="onPhoto($event)" hidden />
          </label>
          @if (acct()?.avatarUrl) {
            <button type="button" class="btn-ghost sm" (click)="removePhoto()">Remove</button>
          }
          <p class="hint">A square image under 2 MB works best. No photo shows your initials.</p>
        </div>
      </div>

      <label class="field">Display name
        <div class="inline">
          <input name="name" [(ngModel)]="name" maxlength="60" placeholder="Your name" />
          <button type="button" (click)="saveName()" [disabled]="savingName() || !name.trim() || name.trim() === acct()?.displayName">
            {{ savingName() ? 'Saving…' : 'Save' }}
          </button>
        </div>
      </label>
    </div>

    <div class="card">
      <h2>Email</h2>
      <p class="muted">Your sign-in email is <b>{{ acct()?.email }}</b>. We send verification codes,
        password resets and reminders here, so it must be one you can receive mail at.</p>
      @if (acct()?.pendingEmail) {
        <p class="pending">A change to <b>{{ acct()?.pendingEmail }}</b> is awaiting the code we sent there.</p>
      }
      @if (emailStep() === 'idle') {
        <button type="button" class="btn-ghost" (click)="emailStep.set('form')">Change email</button>
      } @else if (emailStep() === 'form') {
        <div class="stack">
          <label class="field">New email
            <input name="newEmail" type="email" [(ngModel)]="newEmail" placeholder="you@example.com" />
          </label>
          <label class="field">Current password
            <trove-info-tip text="We re-check your password so a change can't be made from a session left open on a shared computer."></trove-info-tip>
            <input name="emailPw" type="password" [(ngModel)]="emailPw" autocomplete="current-password" />
          </label>
          <div class="inline">
            <button type="button" (click)="startEmailChange()" [disabled]="savingEmail() || !newEmail.trim() || !emailPw">
              {{ savingEmail() ? 'Sending code…' : 'Send code to new email' }}
            </button>
            <button type="button" class="btn-ghost" (click)="cancelEmail()">Cancel</button>
          </div>
        </div>
      } @else {
        <div class="stack">
          <p class="muted">Enter the 6-digit code we sent to <b>{{ newEmail }}</b>.</p>
          <div class="inline">
            <input name="emailCode" inputmode="numeric" maxlength="6" [(ngModel)]="emailCode" placeholder="6-digit code" class="code" />
            <button type="button" (click)="verifyEmailChange()" [disabled]="savingEmail() || emailCode.trim().length < 6">
              {{ savingEmail() ? 'Confirming…' : 'Confirm new email' }}
            </button>
            <button type="button" class="btn-ghost" (click)="cancelEmail()">Cancel</button>
          </div>
        </div>
      }
    </div>

    <div class="card">
      <h2>Password</h2>
      <div class="stack">
        <label class="field">Current password
          <input name="curPw" type="password" [(ngModel)]="curPw" autocomplete="current-password" />
        </label>
        <label class="field">New password
          <input name="newPw" type="password" [(ngModel)]="newPw" autocomplete="new-password" placeholder="At least 8 characters" />
        </label>
        <label class="field">Confirm new password
          <input name="confirmPw" type="password" [(ngModel)]="confirmPw" autocomplete="new-password" />
        </label>
        @if (pwError()) { <p class="error">{{ pwError() }}</p> }
        <div>
          <button type="button" (click)="changePassword()" [disabled]="savingPw()">
            {{ savingPw() ? 'Updating…' : 'Update password' }}
          </button>
        </div>
      </div>
    </div>

    <div class="card">
      <h2>Two-factor authentication</h2>
      <trove-help-card title="About two-factor" [open]="false"
        user="Two-factor adds a second step at sign-in: a 6-digit code from an authenticator app (Google Authenticator, Authy, and similar) that changes every 30 seconds. Even if someone learns your password, they can't sign in without your phone."
        dev="TOTP (RFC 6238): a shared secret produces a time-based code (HMAC-SHA1 over the 30s step). It runs fully offline in the app, so it needs no SMS and costs nothing. The secret is AES-GCM encrypted at rest; enabling requires proving one valid code so a bad setup can't lock you out.">
      </trove-help-card>
      @if (twoFaEnabled()) {
        <p class="status on">Two-factor is <b>on</b> for your account.</p>
        <div class="inline">
          <input name="offcode" [(ngModel)]="offCode" inputmode="numeric" maxlength="6" placeholder="6-digit code" class="code" />
          <button type="button" class="btn-danger" (click)="disable2fa()" [disabled]="busy2fa()">Turn off</button>
        </div>
      } @else if (setup2fa()) {
        <p class="muted">1. In your authenticator app, add an account and enter this key (or open the setup link):</p>
        <div class="secret">{{ setup2fa()!.secret }}</div>
        <p class="muted small"><a [href]="setup2fa()!.otpauthUri">Open in an authenticator app</a> (on this device)</p>
        <p class="muted">2. Enter the 6-digit code it shows to finish:</p>
        <div class="inline">
          <input name="oncode" [(ngModel)]="onCode" inputmode="numeric" maxlength="6" placeholder="6-digit code" class="code" />
          <button type="button" (click)="enable2fa()" [disabled]="busy2fa()">Verify &amp; turn on</button>
        </div>
        @if (twoFaError()) { <p class="error">{{ twoFaError() }}</p> }
      } @else {
        <p class="status off">Two-factor is <b>off</b>.</p>
        <button type="button" (click)="start2fa()" [disabled]="busy2fa()">Set up two-factor</button>
      }
    </div>

    <div class="card">
      <h2>Session</h2>
      <p class="muted">Signed in as <b>{{ acct()?.email }}</b>@if (acct()?.admin) { <span class="admin-chip">admin</span> }.
        @if (acct()?.createdAt) { Member since {{ acct()!.createdAt | prettyDate }}. }</p>
      <p class="muted small">Sessions are stateless tokens that expire on their own, so signing out here clears
        this device. Sign out from every device by changing your password.</p>
      <button type="button" class="btn-ghost" (click)="signOut()">Sign out</button>
    </div>

    @if (acct()?.admin) {
      <div class="card danger-card">
        <h2>Delete an account</h2>
        <trove-help-card title="What deleting an account does" [open]="false"
          user="This permanently removes a person's account and everything in it: their documents (from live storage and Google Drive), spaces they own, reminders, and history. It cannot be undone. Only admins can do this. As a safeguard, you must type the account's email to confirm."
          dev="Runs AccountDeletionService: every document in the user's owned spaces is purged (R2 + Drive + index rows), then the remaining per-space and per-user rows are cleared in FK-safe order and the account row is removed. The independent B2 mirror is append-only and keeps an archival copy by design. Guards: you cannot delete yourself or another admin, and the server re-checks the typed email.">
        </trove-help-card>

        @if (users().length) {
          <label class="field">Account to delete
            <trove-select [options]="userOptions()" [ngModel]="deleteId()" (ngModelChange)="deleteId.set($event)"
              name="deleteId" placeholder="Choose an account…" ariaLabel="Account to delete"></trove-select>
          </label>
          @if (deleteTarget()) {
            <p class="confirm-line">To confirm, type <b>{{ deleteTarget()!.email }}</b> below.</p>
            <div class="inline">
              <input name="confirmEmail" [(ngModel)]="confirmEmail" placeholder="Type the email to confirm" class="grow" />
              <button type="button" class="btn-danger" (click)="deleteAccount()"
                [disabled]="deleting() || confirmEmail.trim().toLowerCase() !== deleteTarget()!.email.toLowerCase()">
                {{ deleting() ? 'Deleting…' : 'Delete this account' }}
              </button>
            </div>
          }
        } @else {
          <p class="muted">No other accounts to manage.</p>
        }
      </div>
    }
  `,
  styles: [
    `
      h2 { margin: 0 0 10px; font-size: 1.05rem; }
      .profile-head { display: flex; gap: 18px; align-items: center; margin: 6px 0 18px; flex-wrap: wrap; }
      .ph-actions { display: flex; flex-direction: column; gap: 8px; align-items: flex-start; }
      .ph-actions .button, .ph-actions .btn-ghost { margin: 0; }
      .hint { margin: 2px 0 0; font-size: 12px; color: var(--muted); }
      .field { display: block; margin: 0 0 4px; font-size: 0.9rem; color: var(--muted); }
      .field input { margin-top: 4px; }
      .stack { display: flex; flex-direction: column; gap: 10px; max-width: 420px; }
      .inline { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
      .inline input { margin: 0; }
      .inline button { margin: 0; }
      .inline .grow { flex: 1; min-width: 200px; }
      input.code { width: 150px; font-family: monospace; letter-spacing: 0.15em; }
      .button.sm, .btn-ghost.sm { padding: 6px 14px; font-size: 13px; }
      .button.sm { display: inline-block; cursor: pointer; }
      .btn-ghost {
        border: 1px solid var(--line); background: transparent; color: var(--muted);
        border-radius: 8px; padding: 0.55rem 1.1rem; font-weight: 600; cursor: pointer;
      }
      .btn-ghost:hover { background: var(--hover); color: var(--accent); }
      .btn-danger { background: var(--danger); color: #fff; border: 0; }
      .status { font-size: 15px; }
      .status.on { color: var(--accent); }
      .secret {
        font-family: monospace; font-size: 18px; letter-spacing: 0.12em; user-select: all;
        background: var(--code-bg); border: 1px solid var(--line); border-radius: 8px;
        padding: 10px 14px; display: inline-block; margin: 6px 0;
      }
      .small { font-size: 12px; }
      .pending { font-size: 13px; color: var(--warn); }
      .admin-chip {
        display: inline-block; margin-left: 6px; font-size: 10px; font-weight: 700; text-transform: uppercase;
        letter-spacing: 0.03em; color: var(--accent); background: var(--accent-soft);
        border: 1px solid var(--accent-line); border-radius: 5px; padding: 1px 6px;
      }
      .danger-card { border: 1px solid var(--danger-line); }
      .danger-card h2 { color: var(--danger); }
      .confirm-line { font-size: 13px; margin: 10px 0 6px; }
    `,
  ],
})
export class Account {
  private auth = inject(AuthService);
  private notices = inject(NoticeService);

  protected acct = this.auth.account;

  // Profile
  name = '';
  savingName = signal(false);
  uploadingPhoto = signal(false);

  // Email change
  emailStep = signal<'idle' | 'form' | 'code'>('idle');
  newEmail = '';
  emailPw = '';
  emailCode = '';
  savingEmail = signal(false);

  // Password
  curPw = '';
  newPw = '';
  confirmPw = '';
  savingPw = signal(false);
  pwError = signal<string | null>(null);

  // Two-factor
  twoFaEnabled = signal(false);
  setup2fa = signal<{ secret: string; otpauthUri: string } | null>(null);
  onCode = '';
  offCode = '';
  busy2fa = signal(false);
  twoFaError = signal<string | null>(null);

  // Admin: delete account
  users = signal<AdminUser[]>([]);
  deleteId = signal(''); // a signal so the deleteTarget computed reacts when a pick is made
  confirmEmail = '';
  deleting = signal(false);
  protected userOptions = computed<SelectOption[]>(() =>
    this.users().map((u) => ({ value: u.id, label: u.displayName + (u.admin ? ' (admin)' : ''), sub: u.email })),
  );
  protected deleteTarget = computed(() => this.users().find((u) => u.id === this.deleteId()) ?? null);

  private usersLoaded = false;

  constructor() {
    // Make sure the profile is loaded (covers a hard refresh straight onto /account).
    if (!this.auth.account()) {
      this.auth.loadAccount().subscribe({ error: () => {} });
    }
    this.auth.twoFactorStatus().subscribe((s) => this.twoFaEnabled.set(s.enabled));

    // Seed the editable name field from the profile, and (for an admin) load the user list
    // for the delete picker, once the profile arrives - it may load after this constructor.
    effect(() => {
      const a = this.auth.account();
      if (a && !this.name) this.name = a.displayName;
      if (a?.admin && !this.usersLoaded) {
        this.usersLoaded = true;
        this.loadUsers();
      }
    });
  }

  // ── Profile ────────────────────────────────────────────────────────────────
  saveName(): void {
    this.savingName.set(true);
    this.auth.updateDisplayName(this.name.trim()).subscribe({
      next: () => { this.savingName.set(false); this.toast('success', 'NAME_SAVED', 'Your name has been updated.'); },
      error: (e) => { this.savingName.set(false); this.toast('error', 'NAME_FAIL', this.msg(e, 'Could not save your name.')); },
    });
  }

  onPhoto(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    this.uploadingPhoto.set(true);
    this.auth.uploadPhoto(file).subscribe({
      next: () => { this.uploadingPhoto.set(false); this.toast('success', 'PHOTO_SET', 'Profile photo updated.'); input.value = ''; },
      error: (e) => { this.uploadingPhoto.set(false); this.toast('error', 'PHOTO_FAIL', this.msg(e, 'Could not upload that image.')); input.value = ''; },
    });
  }

  removePhoto(): void {
    this.auth.deletePhoto().subscribe({
      next: () => this.toast('info', 'PHOTO_OFF', 'Profile photo removed.'),
      error: (e) => this.toast('error', 'PHOTO_FAIL', this.msg(e, 'Could not remove the photo.')),
    });
  }

  // ── Email change ─────────────────────────────────────────────────────────────
  startEmailChange(): void {
    this.savingEmail.set(true);
    this.auth.startEmailChange(this.newEmail.trim(), this.emailPw).subscribe({
      next: () => { this.savingEmail.set(false); this.emailStep.set('code'); this.emailPw = ''; this.toast('info', 'EMAIL_CODE', 'We sent a code to the new address.'); },
      error: (e) => { this.savingEmail.set(false); this.toast('error', 'EMAIL_FAIL', this.msg(e, 'Could not start the email change.')); },
    });
  }

  verifyEmailChange(): void {
    this.savingEmail.set(true);
    this.auth.verifyEmailChange(this.emailCode.trim()).subscribe({
      next: () => { this.savingEmail.set(false); this.emailStep.set('idle'); this.newEmail = ''; this.emailCode = ''; this.toast('success', 'EMAIL_SET', 'Your sign-in email has been updated.'); },
      error: (e) => { this.savingEmail.set(false); this.toast('error', 'EMAIL_FAIL', this.msg(e, 'That code did not match.')); },
    });
  }

  cancelEmail(): void {
    this.emailStep.set('idle');
    this.newEmail = ''; this.emailPw = ''; this.emailCode = '';
  }

  // ── Password ─────────────────────────────────────────────────────────────────
  changePassword(): void {
    this.pwError.set(null);
    if (this.newPw.length < 8) { this.pwError.set('The new password must be at least 8 characters.'); return; }
    if (this.newPw !== this.confirmPw) { this.pwError.set('The new passwords do not match.'); return; }
    this.savingPw.set(true);
    this.auth.changePassword(this.curPw, this.newPw).subscribe({
      next: () => {
        this.savingPw.set(false); this.curPw = ''; this.newPw = ''; this.confirmPw = '';
        this.toast('success', 'PW_CHANGED', 'Your password has been changed.');
      },
      error: (e) => { this.savingPw.set(false); this.pwError.set(this.msg(e, 'Could not change your password.')); },
    });
  }

  // ── Two-factor ─────────────────────────────────────────────────────────────
  start2fa(): void {
    this.busy2fa.set(true); this.twoFaError.set(null);
    this.auth.twoFactorSetup().subscribe({
      next: (s) => { this.setup2fa.set(s); this.busy2fa.set(false); },
      error: () => { this.busy2fa.set(false); this.twoFaError.set('Could not start setup. Please try again.'); },
    });
  }

  enable2fa(): void {
    this.busy2fa.set(true); this.twoFaError.set(null);
    this.auth.twoFactorEnable(this.onCode).subscribe({
      next: () => {
        this.twoFaEnabled.set(true); this.setup2fa.set(null); this.onCode = ''; this.busy2fa.set(false);
        this.toast('success', '2FA_ON', 'Two-factor is now on.');
      },
      error: (e) => { this.busy2fa.set(false); this.twoFaError.set(this.msg(e, 'That code did not match.')); },
    });
  }

  disable2fa(): void {
    this.busy2fa.set(true);
    this.auth.twoFactorDisable(this.offCode).subscribe({
      next: () => { this.twoFaEnabled.set(false); this.offCode = ''; this.busy2fa.set(false); this.toast('info', '2FA_OFF', 'Two-factor has been turned off.'); },
      error: (e) => { this.busy2fa.set(false); this.toast('error', '2FA_OFF_FAIL', this.msg(e, 'Could not turn off 2FA.')); },
    });
  }

  // ── Session ──────────────────────────────────────────────────────────────────
  signOut(): void {
    this.auth.logout();
    window.location.href = '/login';
  }

  // ── Admin: delete account ─────────────────────────────────────────────────────
  private loadUsers(): void {
    this.auth.adminUsers().subscribe({ next: (u) => this.users.set(u), error: () => this.users.set([]) });
  }

  deleteAccount(): void {
    const target = this.deleteTarget();
    if (!target) return;
    this.deleting.set(true);
    this.auth.adminDeleteUser(target.id, this.confirmEmail.trim()).subscribe({
      next: () => {
        this.deleting.set(false);
        this.users.update((list) => list.filter((u) => u.id !== target.id));
        this.deleteId.set(''); this.confirmEmail = '';
        this.toast('success', 'ACCT_DELETED', `${target.email} and all its data have been deleted.`);
      },
      error: (e) => { this.deleting.set(false); this.toast('error', 'ACCT_DEL_FAIL', this.msg(e, 'Could not delete that account.')); },
    });
  }

  private msg(e: unknown, fallback: string): string {
    return (e as { error?: { message?: string } })?.error?.message ?? fallback;
  }
  private toast(level: 'info' | 'success' | 'error', code: string, userMessage: string): void {
    this.notices.show({ level, code, userMessage });
  }
}
