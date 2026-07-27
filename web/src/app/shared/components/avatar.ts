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
  template: `
    <span class="av" [style.width.px]="size" [style.height.px]="size"
          [style.background]="src() ? 'transparent' : bg()" [style.font-size.px]="size * 0.4">
      @if (src(); as u) {
        <img [src]="u" [width]="size" [height]="size" alt="" (error)="src.set(null)" />
      } @else {
        {{ initials() }}
      }
    </span>
  `,
  styles: [
    `
      .av {
        display: inline-flex; align-items: center; justify-content: center; flex: none;
        border-radius: 50%; overflow: hidden; color: #fff; font-weight: 700; line-height: 1;
        user-select: none; text-transform: uppercase;
      }
      .av img { width: 100%; height: 100%; object-fit: cover; display: block; }
    `,
  ],
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
