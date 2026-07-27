import { Component, Input } from '@angular/core';

/**
 * A small round "?" that reveals a short explanation on hover/focus - for telling the
 * user what a button/metric does BEFORE they click. Lighter than the collapsible
 * help-card; use it inline next to a control or a label.
 *
 * Usage: <trove-info-tip text="Checks every copy exists." align="right" />
 * align="left" (default) grows the bubble rightward; "right" grows it leftward (use near
 * the right edge of the screen).
 */
@Component({
  selector: 'trove-info-tip',
  standalone: true,
  templateUrl: './info-tip.html',
  styleUrl: './info-tip.scss',
})
export class InfoTip {
  @Input() text = '';
  @Input() align: 'left' | 'right' = 'left';
}
