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
  templateUrl: './password-input.html',
  styleUrl: './password-input.scss',
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
