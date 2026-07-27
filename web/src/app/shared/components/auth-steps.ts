import { Component, Input } from '@angular/core';

/**
 * A small three-step indicator for the sign-up journey: Details -> Verify email ->
 * Approval. Shown on the register and verify screens so a new user always sees the
 * whole flow and where they currently are. `active` is 1-based (1, 2, or 3).
 */
@Component({
  selector: 'trove-auth-steps',
  standalone: true,
  templateUrl: './auth-steps.html',
  styleUrl: './auth-steps.scss',
})
export class AuthSteps {
  @Input() active = 1;
  readonly labels = ['Your details', 'Verify email', 'Admin approval'];
}
