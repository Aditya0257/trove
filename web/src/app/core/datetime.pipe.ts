import { Pipe, PipeTransform } from '@angular/core';

/**
 * Formats an ISO timestamp into a readable, fully-detailed local string —
 * e.g. "23 Jul 2026, 1:25:58 PM IST" — instead of the raw machine form. Keeps the
 * full detail (date, seconds, timezone) the raw value had, just legible.
 */
@Pipe({ name: 'prettyDate', standalone: true })
export class DateTimePipe implements PipeTransform {
  transform(value: string | number | Date | null | undefined): string {
    if (!value) return '';
    const d = new Date(value);
    if (isNaN(d.getTime())) return String(value);
    return new Intl.DateTimeFormat('en-GB', {
      day: '2-digit',
      month: 'short',
      year: 'numeric',
      hour: 'numeric',
      minute: '2-digit',
      second: '2-digit',
      hour12: true,
      timeZoneName: 'short',
    }).format(d);
  }
}
