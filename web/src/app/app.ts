import { Component, effect, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth.service';
import { SpaceContext } from './core/space.context';
import { NoticeToast } from './core/notice/notice-toast';
import { DevDrawer } from './core/notice/dev-drawer';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, FormsModule, NoticeToast, DevDrawer],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  protected auth = inject(AuthService);
  protected spaceCtx = inject(SpaceContext);
  private router = inject(Router);

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
