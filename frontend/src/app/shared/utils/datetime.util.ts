/** End-time values from the API are UTC wall-clock instants (see MetadataImporterService). */
export const END_TIME_TIMEZONE = 'UTC';

/**
 * Detects whether a date string explicitly indicates UTC.
 */
export function isExplicitUtc(value: unknown): boolean {
  if (!value || typeof value !== 'string') return false;
  const s = value.trim();
  return /Z$/i.test(s) || /[+-]00(?::?00)?$/.test(s) || / (UTC|GMT)$/i.test(s);
}

/**
 * Detects whether a date string contains an explicit timezone offset or specifier.
 */
export function hasTimezoneOffset(value: unknown): boolean {
  if (!value || typeof value !== 'string') return false;
  const s = value.trim();
  return /Z$/i.test(s) || /[+-]\d{2}(?::?\d{2})?$/.test(s) || / [A-Z]{2,4}$/i.test(s);
}

/**
 * Safely parses any datetime value from the DB or API into a Date object.
 *
 * Checks whether the DB value is in UTC or has a timezone offset:
 * 1. If Date instance or epoch number: parsed directly as absolute UTC instant.
 * 2. If already explicit UTC ('Z', '+00:00', 'UTC'): parsed as UTC instant.
 * 3. If explicit timezone offset ('+08:00', '-05:00'): parsed and normalized to UTC instant.
 * 4. If bare datetime without timezone (e.g. '2026-09-07 06:29:44' or '2026-09-07T06:29:44'
 *    from DB TIMESTAMP): Exensio and backend DBs store timestamps in UTC. To prevent
 *    the browser from mistakenly interpreting bare strings as browser local time,
 *    it is normalized with 'Z' so it correctly parses as UTC wall-clock time.
 */
export function parseInstant(value: unknown): Date | null {
  if (value === null || value === undefined || value === '') return null;
  if (value instanceof Date) {
    return Number.isNaN(value.getTime()) ? null : value;
  }
  if (typeof value === 'number') {
    const d = new Date(value);
    return Number.isNaN(d.getTime()) ? null : d;
  }

  let s = String(value).trim();
  if (!s) return null;

  // If ending with ' UTC' or ' GMT', normalize to 'Z'
  if (/ (UTC|GMT)$/i.test(s)) {
    s = s.replace(/ (UTC|GMT)$/i, 'Z').replace(' ', 'T');
  }

  // Check if string matches bare ISO / SQL date-time pattern without timezone:
  // e.g. "YYYY-MM-DD HH:MM:SS", "YYYY-MM-DDTHH:MM:SS", or with fractional seconds
  const bareDateTimeRegex = /^(\d{4}[-/]\d{2}[-/]\d{2})[T ](\d{2}:\d{2}:\d{2}(?:\.\d+)?)$/;
  const match = bareDateTimeRegex.exec(s);
  if (match) {
    const datePart = match[1].replace(/\//g, '-');
    const timePart = match[2];
    // DB timestamp stored in UTC without timezone offset: enforce UTC 'Z'
    s = `${datePart}T${timePart}Z`;
  }

  const d = new Date(s);
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

// ──────────────────────────────────────────────
// Dual-timestamp helpers (UTC bold + local muted)
// ──────────────────────────────────────────────

function pad(n: number): string {
  return String(n).padStart(2, '0');
}

/** Format as `YYYY-MM-DD HH:MM:SS UTC` in UTC */
export function formatUtcString(d: Date, includeSuffix = true): string {
  const base = `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}`;
  return includeSuffix ? `${base} UTC` : base;
}

/** Format as `YYYY-MM-DD HH:MM:SS LOCAL` in browser local timezone */
export function formatLocalString(d: Date, includeSuffix = true): string {
  const yyyy = d.getFullYear();
  const mm = pad(d.getMonth() + 1);
  const dd = pad(d.getDate());
  const hh = pad(d.getHours());
  const mi = pad(d.getMinutes());
  const ss = pad(d.getSeconds());
  const base = `${yyyy}-${mm}-${dd} ${hh}:${mi}:${ss}`;
  return includeSuffix ? `${base} LOCAL` : base;
}

export interface DualTimestamp {
  utc: string;   // bold line
  local: string; // muted line
}

/**
 * Returns both UTC and local time strings for display.
 * If value is falsy or unparseable, returns null.
 */
export function formatDualTimestamp(value: unknown): DualTimestamp | null {
  const d = parseInstant(value);
  if (!d) return null;
  return { utc: formatUtcString(d), local: formatLocalString(d) };
}

/**
 * Human-readable relative time (e.g. "2m ago", "3h ago").
 * Falls back to UTC string when > 24h old.
 */
export function formatTimeAgo(value: unknown): string {
  const d = parseInstant(value);
  if (!d) return '-';
  const seconds = Math.floor((Date.now() - d.getTime()) / 1000);
  if (seconds < 0) return formatUtcString(d);
  if (seconds < 60) return 'now';
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
  return `${Math.floor(seconds / 86400)}d ago`;
}
