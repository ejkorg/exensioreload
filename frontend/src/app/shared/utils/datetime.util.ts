/** End-time values from the API are UTC wall-clock instants (see MetadataImporterService). */
export const END_TIME_TIMEZONE = 'UTC';

export function parseInstant(value: unknown): Date | null {
  if (value === null || value === undefined || value === '') return null;
  const d = value instanceof Date ? value : new Date(String(value));
  return Number.isNaN(d.getTime()) ? null : d;
}

/** Calendar date (YYYY-MM-DD) in UTC — matches DB end_time day bucketing. */
export function toUtcDayKey(value?: string | null): string | null {
  const d = parseInstant(value ?? null);
  if (!d) return null;
  return d.toLocaleDateString('en-CA', { timeZone: END_TIME_TIMEZONE });
}

export function formatUtcDate(
  value: unknown,
  options: Intl.DateTimeFormatOptions = { year: 'numeric', month: 'numeric', day: 'numeric' }
): string {
  const d = parseInstant(value);
  if (!d) return '-';
  return new Intl.DateTimeFormat(undefined, { ...options, timeZone: END_TIME_TIMEZONE }).format(d);
}

export function formatUtcDateLabel(value: unknown): string {
  return formatUtcDate(value, { month: 'short', day: 'numeric', year: 'numeric' });
}
