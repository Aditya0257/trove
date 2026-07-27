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
  templateUrl: './account.html',
  styleUrl: './account.scss',
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
