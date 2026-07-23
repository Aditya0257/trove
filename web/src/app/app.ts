import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth.service';
import { SpaceContext } from './core/space.context';
import { ThemeService } from './core/theme.service';
import { ApiService } from './core/api.service';
import { NoticeService } from './core/notice/notice.service';
import { TroveSelect, SelectOption } from './core/select';
import { NoticeToast } from './core/notice/notice-toast';
import { DevDrawer } from './core/notice/dev-drawer';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, FormsModule, TroveSelect, NoticeToast, DevDrawer],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  protected auth = inject(AuthService);
  protected spaceCtx = inject(SpaceContext);
  protected theme = inject(ThemeService);

  /** Mobile nav: the topbar collapses into a hamburger menu at narrow widths. */
  protected menuOpen = signal(false);
  private api = inject(ApiService);
  private notices = inject(NoticeService);
  private router = inject(Router);
  private invitesChecked = false;

  /** Spaces as dropdown options for the top-right switcher. */
  protected spaceOptions = computed<SelectOption[]>(() =>
    this.spaceCtx.spaces().map((s) => ({
      value: s.id,
      label: s.name + (s.kind === 'shared' ? ' (shared)' : ''),
    })),
  );

  constructor() {
    // Load the user's spaces once they're logged in (covers both fresh login and
    // a page refresh where the token is already present).
    effect(() => {
      if (this.auth.isLoggedIn() && !this.spaceCtx.loaded()) {
        this.spaceCtx.load();
      }
      // Once per login, surface any pending space invitations as a nudge toast.
      if (this.auth.isLoggedIn() && !this.invitesChecked) {
        this.invitesChecked = true;
        this.api.listInvitations().subscribe({
          next: (invs) => {
            if (invs.length) {
              this.notices.show({
                level: 'info',
                code: 'INVITES',
                userMessage: `You have ${invs.length} space invitation${invs.length > 1 ? 's' : ''} waiting — open Spaces to accept or decline.`,
              });
            }
          },
          error: () => {},
        });
      }
    });
  }

  onSpaceChange(id: string): void {
    this.spaceCtx.setCurrent(id);
  }

  logout(): void {
    this.auth.logout();
    this.spaceCtx.reset();
    this.invitesChecked = false;
    this.router.navigate(['/login']);
  }
}
