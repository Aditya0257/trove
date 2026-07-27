import {
  Component, ElementRef, HostListener, Input, computed, forwardRef, inject, signal,
} from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';

/** One choice in a TroveSelect. `sub` shows a smaller second line (e.g. an email). */
export interface SelectOption {
  value: string;
  label: string;
  sub?: string;
}

/**
 * A fully themed dropdown that replaces the native <select> - because a native
 * option list is drawn by the OS and can't be styled to match the app. This renders
 * its own button + popup list, so it honours the light/dark tokens, supports a
 * secondary line per option (name + email), and is keyboard accessible (Up/Down,
 * Enter, Esc, Home/End). Works with [(ngModel)] / reactive forms via CVA.
 */
@Component({
  selector: 'trove-select',
  standalone: true,
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: forwardRef(() => TroveSelect), multi: true }],
  templateUrl: './select.html',
  styleUrl: './select.scss',
})
export class TroveSelect implements ControlValueAccessor {
  // Signal-backed so the displayed label reacts when the options change (e.g. a space
  // is renamed) - a plain @Input wouldn't re-run the `selected` computed.
  private _options = signal<SelectOption[]>([]);
  @Input() set options(v: SelectOption[]) {
    this._options.set(v ?? []);
  }
  protected opts = this._options.asReadonly();

  @Input() placeholder = 'Select…';
  @Input() ariaLabel = '';

  protected value = signal<string>('');
  protected open = signal(false);
  protected disabled = signal(false);
  protected highlight = signal(-1);
  protected selected = computed(() => this._options().find((o) => o.value === this.value()));

  private host = inject(ElementRef);
  private onChange: (v: string) => void = () => {};
  private onTouched: () => void = () => {};

  writeValue(v: string): void {
    this.value.set(v ?? '');
  }
  registerOnChange(fn: (v: string) => void): void {
    this.onChange = fn;
  }
  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }
  setDisabledState(d: boolean): void {
    this.disabled.set(d);
  }

  protected toggle(): void {
    if (this.disabled()) return;
    this.open.update((o) => !o);
    if (this.open()) {
      this.highlight.set(Math.max(0, this._options().findIndex((o) => o.value === this.value())));
    }
  }

  protected pick(v: string): void {
    this.value.set(v);
    this.onChange(v);
    this.onTouched();
    this.open.set(false);
  }

  protected onKey(e: KeyboardEvent): void {
    if (this.disabled()) return;
    const n = this._options().length;
    if (!this.open()) {
      if (e.key === 'Enter' || e.key === ' ' || e.key === 'ArrowDown') {
        e.preventDefault();
        this.toggle();
      }
      return;
    }
    switch (e.key) {
      case 'ArrowDown': e.preventDefault(); this.highlight.update((i) => Math.min(n - 1, i + 1)); break;
      case 'ArrowUp': e.preventDefault(); this.highlight.update((i) => Math.max(0, i - 1)); break;
      case 'Home': e.preventDefault(); this.highlight.set(0); break;
      case 'End': e.preventDefault(); this.highlight.set(n - 1); break;
      case 'Enter': case ' ': {
        e.preventDefault();
        const o = this._options()[this.highlight()];
        if (o) this.pick(o.value);
        break;
      }
      case 'Escape': e.preventDefault(); this.open.set(false); break;
    }
  }

  @HostListener('document:click', ['$event'])
  protected onDocClick(e: MouseEvent): void {
    if (!this.host.nativeElement.contains(e.target)) {
      this.open.set(false);
    }
  }
}
