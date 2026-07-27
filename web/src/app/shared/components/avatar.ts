import { Component, Input, computed, signal } from '@angular/core';

/**
 * A round profile avatar: the user's photo when one is set, otherwise their initials on a
 * colour derived deterministically from the name (so the same person always gets the same
 * tint). Used in the top-bar and on the account screen. Purely presentational.
 *
 * Usage: <trove-avatar [name]="user.displayName" [url]="user.avatarUrl" [size]="30" />
 */
@Component({
  selector: 'trove-avatar',
  standalone: true,
  templateUrl: './avatar.html',
  styleUrl: './avatar.scss',
})
export class Avatar {
  @Input() name = '';
  @Input() size = 30;
  @Input() set url(v: string | null) {
    this.src.set(v ?? null);
  }
  protected src = signal<string | null>(null);

  protected initials = computed(() => {
    const parts = (this.name || '').trim().split(/\s+/).filter(Boolean);
    if (!parts.length) return '?';
    if (parts.length === 1) return parts[0].slice(0, 2);
    return (parts[0][0] + parts[parts.length - 1][0]);
  });

  /** A stable, pleasant background from the name (fixed saturation/lightness, hashed hue). */
  protected bg = computed(() => {
    let h = 0;
    const s = this.name || '?';
    for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 360;
    return `hsl(${h}, 45%, 45%)`;
  });
}
