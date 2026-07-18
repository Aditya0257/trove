import { Injectable, computed, inject, signal } from '@angular/core';
import { ApiService } from './api.service';
import { SpaceSummary } from './models';

/**
 * Holds the list of spaces the user belongs to and the "current" space id (driven by
 * the nav switcher). Screens read `currentSpaceId()` and reload when it changes.
 * Until spaces load, currentSpaceId is undefined → API calls omit spaceId → the
 * backend uses the personal space (graceful default).
 */
@Injectable({ providedIn: 'root' })
export class SpaceContext {
  private api = inject(ApiService);

  readonly spaces = signal<SpaceSummary[]>([]);
  readonly currentSpaceId = signal<string | undefined>(undefined);
  readonly loaded = signal(false);
  readonly current = computed(() =>
    this.spaces().find((s) => s.id === this.currentSpaceId())
  );

  load(): void {
    this.api.listSpaces().subscribe((spaces) => {
      this.spaces.set(spaces);
      if (!this.currentSpaceId()) {
        const personal = spaces.find((s) => s.kind === 'personal') ?? spaces[0];
        this.currentSpaceId.set(personal?.id);
      }
      this.loaded.set(true);
    });
  }

  setCurrent(id: string): void {
    this.currentSpaceId.set(id);
  }

  reset(): void {
    this.spaces.set([]);
    this.currentSpaceId.set(undefined);
    this.loaded.set(false);
  }
}
