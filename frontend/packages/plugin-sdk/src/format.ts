/**
 * Dates and numbers as people in Portugal write them (pt-PT, Lisbon time). Use these instead of
 * `toLocaleString()`, which follows the browser's language and time zone, so every screen shows the same format.
 */

export const LOCALE = 'pt-PT'
export const TIME_ZONE = 'Europe/Lisbon'

/** Shown when there is no value (null, undefined, an invalid date or NaN). */
export const EMPTY_VALUE = '—'

/** A date as the API sends it: an ISO string (`2026-11-19` or `2026-11-19T14:30:00Z`), a timestamp or a Date. */
export type DateInput = Date | string | number | null | undefined

/** `2026-11-19`: a calendar day, not an instant. Formatted in UTC so it never moves to the day before or after. */
const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/

function toDate(value: DateInput): { date: Date; dateOnly: boolean } | null {
  if (value === null || value === undefined || value === '') return null
  const dateOnly = typeof value === 'string' && DATE_ONLY.test(value)
  const date = value instanceof Date ? value : new Date(dateOnly ? `${value}T00:00:00Z` : value)
  return Number.isNaN(date.getTime()) ? null : { date, dateOnly }
}

const dateFormats = {
  /** 19/11/2026 */
  short: { day: '2-digit', month: '2-digit', year: 'numeric' },
  /** 19 de novembro de 2026 */
  long: { day: 'numeric', month: 'long', year: 'numeric' },
} satisfies Record<string, Intl.DateTimeFormatOptions>

/** `19/11/2026` (short, the default) or `19 de novembro de 2026` (long). */
export function formatDate(value: DateInput, style: keyof typeof dateFormats = 'short'): string {
  const parsed = toDate(value)
  if (!parsed) return EMPTY_VALUE
  return new Intl.DateTimeFormat(LOCALE, {
    ...dateFormats[style],
    timeZone: parsed.dateOnly ? 'UTC' : TIME_ZONE,
  }).format(parsed.date)
}

/** `19/11/2026, 14:30`, in Lisbon time. */
export function formatDateTime(value: DateInput): string {
  const parsed = toDate(value)
  if (!parsed) return EMPTY_VALUE
  return new Intl.DateTimeFormat(LOCALE, {
    ...dateFormats.short,
    hour: '2-digit',
    minute: '2-digit',
    timeZone: parsed.dateOnly ? 'UTC' : TIME_ZONE,
  }).format(parsed.date)
}

const RELATIVE_UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ['year', 365 * 24 * 3600],
  ['month', 30 * 24 * 3600],
  ['week', 7 * 24 * 3600],
  ['day', 24 * 3600],
  ['hour', 3600],
  ['minute', 60],
]

/** `há 3 dias`, `ontem`, `dentro de 2 horas`, `agora`. For "last updated" and similar; use [formatDate] for deadlines. */
export function formatRelativeTime(value: DateInput, now: Date = new Date()): string {
  const parsed = toDate(value)
  if (!parsed) return EMPTY_VALUE
  const seconds = (parsed.date.getTime() - now.getTime()) / 1000
  const format = new Intl.RelativeTimeFormat(LOCALE, { numeric: 'auto' })
  for (const [unit, size] of RELATIVE_UNITS) {
    if (Math.abs(seconds) >= size) return format.format(Math.trunc(seconds / size), unit)
  }
  return 'agora'
}

const isMissing = (value: number | null | undefined): value is null | undefined =>
  value === null || value === undefined || Number.isNaN(value)

/** `1234,5`, `12 345`. Decimal comma; thousands grouped from five digits, as pt-PT does. */
export function formatNumber(value: number | null | undefined, options?: Intl.NumberFormatOptions): string {
  return isMissing(value) ? EMPTY_VALUE : new Intl.NumberFormat(LOCALE, options).format(value)
}

/** `42%` from `0.42`. Pass `fractionDigits` for `42,5%`. */
export function formatPercent(ratio: number | null | undefined, fractionDigits = 0): string {
  return formatNumber(ratio, { style: 'percent', maximumFractionDigits: fractionDigits })
}

/** `1234,50 €` */
export function formatCurrency(value: number | null | undefined, currency = 'EUR'): string {
  return formatNumber(value, { style: 'currency', currency })
}
