import { Pipe, PipeTransform } from '@angular/core';

/**
 * Formats an amount as currency with Indian grouping (₹6,487.00) via Intl. Falls back
 * to "<code> <value>" for unknown currencies, and "—" for null.
 */
@Pipe({ name: 'money', standalone: true })
export class MoneyPipe implements PipeTransform {
  transform(value: number | null | undefined, currency: string | null | undefined = 'INR'): string {
    if (value == null) {
      return '-';
    }
    const code = currency && currency.trim() ? currency : 'INR';
    try {
      return new Intl.NumberFormat('en-IN', {
        style: 'currency',
        currency: code,
        maximumFractionDigits: 2,
      }).format(value);
    } catch {
      return `${code} ${value}`;
    }
  }
}
