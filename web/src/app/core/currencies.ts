import { SelectOption } from './select';

/** Currencies Trove supports today (kept short; matches the backend's list). */
export const CURRENCIES = ['INR', 'USD', 'EUR'] as const;

/** Ready-made options for a <trove-select>. */
export const CURRENCY_OPTIONS: SelectOption[] = CURRENCIES.map((c) => ({ value: c, label: c }));
