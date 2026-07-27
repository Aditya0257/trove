import { Component, Input } from '@angular/core';

/**
 * A small three-step indicator for the sign-up journey: Details -> Verify email ->
 * Approval. Shown on the register and verify screens so a new user always sees the
 * whole flow and where they currently are. `active` is 1-based (1, 2, or 3).
 */
@Component({
  selector: 'trove-auth-steps',
  standalone: true,
  template: `
    <ol class="steps" aria-label="Sign-up progress">
      @for (label of labels; track label; let i = $index) {
        <li class="step" [class.active]="i + 1 === active" [class.done]="i + 1 < active"
            [attr.aria-current]="i + 1 === active ? 'step' : null">
          <span class="dot">@if (i + 1 < active) {
            <svg class="tick" viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor"
              stroke-width="3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M5 12.5l4 4L19 7" />
            </svg>
          } @else { {{ i + 1 }} }</span>
          <span class="label">{{ label }}</span>
        </li>
      }
    </ol>
  `,
  styles: [
    `
      .steps {
        display: flex; align-items: flex-start; gap: 0; list-style: none;
        margin: 0 0 20px; padding: 0;
      }
      .step {
        flex: 1; display: flex; flex-direction: column; align-items: center; gap: 6px;
        position: relative; text-align: center;
      }
      /* connector line between steps */
      .step::before {
        content: ''; position: absolute; top: 13px; left: -50%; width: 100%; height: 2px;
        background: var(--line); z-index: 0;
      }
      .step:first-child::before { display: none; }
      .step.active::before, .step.done::before { background: var(--accent); }
      .dot {
        position: relative; z-index: 1; width: 28px; height: 28px; border-radius: 50%;
        display: inline-flex; align-items: center; justify-content: center;
        font-size: 13px; font-weight: 700; background: var(--surface-2, #eee);
        color: var(--muted); border: 2px solid var(--line);
        transition: background 200ms ease, box-shadow 200ms ease;
      }
      .dot .tick { display: block; }
      .step.active .dot,
      .step.done .dot { background: var(--accent); color: var(--brand-ink, #fff); border-color: var(--accent); }
      /* soft halo on the step you're on, for a modern focus cue */
      .step.active .dot { box-shadow: 0 0 0 5px color-mix(in srgb, var(--accent) 22%, transparent); }
      @media (prefers-reduced-motion: reduce) { .dot { transition: none; } }
      .label { font-size: 12px; color: var(--muted); line-height: 1.3; max-width: 84px; }
      .step.active .label { color: var(--ink); font-weight: 600; }
    `,
  ],
})
export class AuthSteps {
  @Input() active = 1;
  readonly labels = ['Your details', 'Verify email', 'Admin approval'];
}
