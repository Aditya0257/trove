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
  template: `
    <div class="ts" [class.open]="open()">
      <button type="button" class="ts-btn" [disabled]="disabled()"
              (click)="toggle()" (keydown)="onKey($event)"
              [attr.aria-expanded]="open()" [attr.aria-label]="ariaLabel || placeholder">
        <span class="ts-label" [class.ph]="!selected()">{{ selected()?.label || placeholder }}</span>
        <span class="ts-chev">▾</span>
      </button>
      @if (open()) {
        <ul class="ts-list" role="listbox">
          @for (o of opts(); track o.value; let i = $index) {
            <li role="option" [attr.aria-selected]="o.value === value()"
                [class.sel]="o.value === value()" [class.hi]="i === highlight()"
                (click)="pick(o.value)" (mouseenter)="highlight.set(i)">
              <span class="ts-main">
                <span class="ts-opt">{{ o.label }}</span>
                @if (o.sub) { <span class="ts-sub">{{ o.sub }}</span> }
              </span>
              @if (o.value === value()) { <span class="ts-check">✓</span> }
            </li>
          }
        </ul>
      }
    </div>
  `,
  styles: [
    `
      .ts { position: relative; }
      .ts-btn {
        margin: 0; width: 100%; display: flex; align-items: center; gap: 8px;
        padding: 0.55rem 0.7rem; border: 1px solid var(--line); border-radius: 8px;
        background: var(--input-bg); color: var(--ink); font-size: 0.95rem; cursor: pointer; text-align: left;
      }
      .ts-btn:disabled { opacity: 0.55; cursor: default; }
      .ts.open .ts-btn, .ts-btn:focus { outline: none; border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }
      .ts-label { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
      .ts-label.ph { color: var(--muted); }
      .ts-chev { flex: none; color: var(--muted); transition: transform 150ms; }
      .ts.open .ts-chev { transform: rotate(180deg); }
      .ts-list {
        position: absolute; z-index: 40; top: calc(100% + 4px); left: 0; right: 0; margin: 0; padding: 4px;
        list-style: none; max-height: 260px; overflow-y: auto; background: var(--card); font-size: 0.9rem;
        border: 1px solid var(--line); border-radius: 10px; box-shadow: 0 10px 30px var(--shadow);
      }
      .ts-list li {
        display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-radius: 7px; cursor: pointer;
      }
      .ts-list li.hi { background: var(--accent-soft); }
      .ts-list li.sel .ts-opt { color: var(--accent); font-weight: 600; }
      .ts-main { flex: 1; display: flex; flex-direction: column; gap: 1px; min-width: 0; }
      .ts-opt { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
      .ts-sub { font-size: 11px; color: var(--muted); font-family: monospace; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
      .ts-check { flex: none; color: var(--accent); font-weight: 700; }
    `,
  ],
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
