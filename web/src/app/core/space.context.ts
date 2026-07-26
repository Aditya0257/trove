import { Injectable, computed, inject, signal } from '@angular/core';
import { retry, timer } from 'rxjs';
import { ApiService } from './api.service';
import { SpaceSummary } from './models';

/**
 * Holds the list of spaces the user belongs to and the "current" space id (driven by
 * the nav switcher). Screens read `currentSpaceId()` and reload when it changes.
 * Until spaces load, currentSpaceId is undefined → API calls omit spaceId → the
 * backend uses the personal space (graceful default).
 *
 * Resilience note: this load must survive a transient hiccup on the very first call
 * (e.g. right after login, when the network or backend is briefly slow). If it fails
 * silently, `loaded` never flips and nothing re-triggers it - so the Spaces page would
 * sit on "-" with an empty members table until a full page reload. We therefore retry
 * with backoff, guard against overlapping calls, and expose `loading` so screens can
 * show a proper "Loading…" state and re-request if spaces still aren't in.
 */
@Injectable({ providedIn: 'root' })
export class SpaceContext {
  private api = inject(ApiService);

  /** Remembers the last-selected space across reloads and sign-ins (per browser). */
  private static readonly LAST_KEY = 'trove-space-id';

  readonly spaces = signal<SpaceSummary[]>([]);
  readonly currentSpaceId = signal<string | undefined>(undefined);
  readonly loaded = signal(false);
  readonly loading = signal(false);
  readonly current = computed(() =>
    this.spaces().find((s) => s.id === this.currentSpaceId())
  );

  load(): void {
    // Don't stack a second request on top of one already in flight.
    if (this.loading()) return;
    this.loading.set(true);
    this.api
      .listSpaces()
      // Ride out a brief blip on first load: 3 tries, ~400ms then ~800ms apart.
      .pipe(retry({ count: 3, delay: (_e, n) => timer(n * 400) }))
      .subscribe({
        next: (spaces) => {
          this.spaces.set(spaces);
          if (!this.currentSpaceId()) {
            // Reopen the space the user last had selected (if they still belong to it),
            // otherwise fall back to their personal space. This is the web equivalent of
            // "remember my default space" - so a reload or new sign-in lands where they left.
            const saved = localStorage.getItem(SpaceContext.LAST_KEY);
            const remembered = saved ? spaces.find((s) => s.id === saved) : undefined;
            const personal = spaces.find((s) => s.kind === 'personal') ?? spaces[0];
            this.currentSpaceId.set((remembered ?? personal)?.id);
          }
          this.loaded.set(true);
          this.loading.set(false);
        },
        // Leave `loaded` false so a screen (or the next nav) can trigger a fresh
        // attempt; just clear the in-flight guard so that retry can actually run.
        error: () => this.loading.set(false),
      });
  }

  setCurrent(id: string): void {
    this.currentSpaceId.set(id);
    // Persist so the choice sticks across reloads / sign-ins on this browser.
    try {
      localStorage.setItem(SpaceContext.LAST_KEY, id);
    } catch {
      // localStorage can be unavailable (private mode); remembering is best-effort.
    }
  }

  reset(): void {
    this.spaces.set([]);
    this.currentSpaceId.set(undefined);
    this.loaded.set(false);
    this.loading.set(false);
  }
}
