import { Component, computed, effect, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth.service';
import { SpaceContext } from './core/space.context';
import { ThemeService } from './core/theme.service';
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
  private router = inject(Router);

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
    });
  }

  onSpaceChange(id: string): void {
    this.spaceCtx.setCurrent(id);
  }

  logout(): void {
    this.auth.logout();
    this.spaceCtx.reset();
    this.router.navigate(['/login']);
  }
}
