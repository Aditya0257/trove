import { Component, Input, forwardRef, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';

/**
 * A password input with a show/hide eye toggle. Implements ControlValueAccessor so it
 * drops into any form with [(ngModel)] exactly like a plain input. Used on login,
 * register and reset-password.
 */
@Component({
  selector: 'trove-password',
  standalone: true,
  imports: [FormsModule],
  template: `
    <div class="pw">
      <input [type]="show() ? 'text' : 'password'" [attr.name]="name" [attr.placeholder]="placeholder"
             [attr.autocomplete]="autocomplete" [attr.minlength]="minlength || null" [required]="required"
             [ngModel]="value" (ngModelChange)="update($event)" (blur)="onTouched()" [disabled]="disabled" />
      <button type="button" class="eye" (click)="show.set(!show())"
              [attr.aria-label]="show() ? 'Hide password' : 'Show password'" tabindex="-1">
        @if (show()) {
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
               stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20C5 20 1 12 1 12a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19"/>
            <line x1="1" y1="1" x2="23" y2="23"/>
          </svg>
        } @else {
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
               stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
            <circle cx="12" cy="12" r="3"/>
          </svg>
        }
      </button>
    </div>
  `,
  styles: [
    `
      .pw { position: relative; }
      .pw input { width: 100%; box-sizing: border-box; padding-right: 42px; }
      .eye {
        position: absolute; top: 50%; right: 6px; transform: translateY(-50%);
        margin: 0; padding: 5px; background: transparent; border: 0; cursor: pointer;
        color: var(--muted); display: inline-flex; line-height: 0;
      }
      .eye:hover { color: var(--accent); }
    `,
  ],
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => PasswordInput), multi: true }],
})
export class PasswordInput implements ControlValueAccessor {
  @Input() name = 'password';
  @Input() placeholder = '';
  @Input() autocomplete = '';
  @Input() minlength?: number;
  @Input() required = false;

  protected show = signal(false);
  protected value = '';
  protected disabled = false;
  private onChange: (v: string) => void = () => {};
  protected onTouched: () => void = () => {};

  update(v: string): void {
    this.value = v;
    this.onChange(v);
  }
  writeValue(v: string): void { this.value = v ?? ''; }
  registerOnChange(fn: (v: string) => void): void { this.onChange = fn; }
  registerOnTouched(fn: () => void): void { this.onTouched = fn; }
  setDisabledState(d: boolean): void { this.disabled = d; }
}
