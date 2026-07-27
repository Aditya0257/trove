import { Component, Input } from '@angular/core';

/**
 * A feature-level explainer - the two-channel Notice philosophy (D23) applied to help:
 * a plain "What this is" for everyone, and a "How it works" for anyone who wants the
 * technical/system-design detail. It's a collapsible card with a compact header, so a
 * repeat user isn't forced to re-read a paragraph every visit; set [open]="false" for
 * dense/often-seen spots (e.g. Search) and leave it open elsewhere. Visually distinct
 * from the small round-i field tooltips, so features and fields read differently.
 *
 * Usage: <trove-help-card title="How search works" user="…" dev="…" [open]="false" />
 */
@Component({
  selector: 'trove-help-card',
  standalone: true,
  templateUrl: './help-card.html',
  styleUrl: './help-card.scss',
})
export class HelpCard {
  @Input() title = 'About this';
  @Input() user = '';
  @Input() dev = '';
  @Input() open = true;
}
