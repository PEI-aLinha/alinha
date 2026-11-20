import { describe, expect, it } from 'vitest'
import {
  EMPTY_VALUE,
  formatCurrency,
  formatDate,
  formatDateTime,
  formatNumber,
  formatPercent,
  formatRelativeTime,
} from './format'

// Intl separates thousands and the euro sign with a (narrow) no-break space; compare with plain spaces.
const plain = (text: string) => text.replace(/[\u00a0\u202f]/g, ' ')

describe('formatDate', () => {
  it('writes day/month/year with a four-digit year', () => {
    expect(formatDate('2026-11-19T10:00:00Z')).toBe('19/11/2026')
  })

  it('writes the month in words in the long style', () => {
    expect(formatDate('2026-11-19T10:00:00Z', 'long')).toBe('19 de novembro de 2026')
  })

  it('uses Lisbon time, so a late UTC instant in summer is already the next day', () => {
    expect(formatDate('2026-07-19T23:30:00Z')).toBe('20/07/2026')
  })

  it('keeps a date-only value on its own day', () => {
    expect(formatDate('2026-03-01')).toBe('01/03/2026')
  })

  it('accepts a Date and a timestamp', () => {
    const instant = new Date('2026-11-19T10:00:00Z')
    expect(formatDate(instant)).toBe('19/11/2026')
    expect(formatDate(instant.getTime())).toBe('19/11/2026')
  })

  it.each([null, undefined, '', 'not a date'])('shows a dash for %j', (value) => {
    expect(formatDate(value)).toBe(EMPTY_VALUE)
    expect(formatDateTime(value)).toBe(EMPTY_VALUE)
    expect(formatRelativeTime(value)).toBe(EMPTY_VALUE)
  })
})

describe('formatDateTime', () => {
  it('adds the time in Lisbon, 24-hour clock', () => {
    expect(formatDateTime('2026-11-19T14:30:00Z')).toBe('19/11/2026, 14:30')
    expect(formatDateTime('2026-07-19T14:30:00Z')).toBe('19/07/2026, 15:30')
  })

  it('shows only the date for a date-only value, which has no time', () => {
    expect(formatDateTime('2026-03-01')).toBe('01/03/2026')
  })
})

describe('formatRelativeTime', () => {
  const now = new Date('2026-11-19T12:00:00Z')

  it.each([
    ['2026-11-16T12:00:00Z', 'há 3 dias'],
    ['2026-11-18T11:00:00Z', 'ontem'],
    ['2026-11-19T14:00:00Z', 'dentro de 2 horas'],
    ['2026-11-19T13:58:00Z', 'dentro de 2 horas'],
    ['2026-11-16T13:00:00Z', 'há 3 dias'],
    ['2026-11-19T11:55:00Z', 'há 5 minutos'],
    ['2026-11-19T11:59:30Z', 'agora'],
    ['2025-11-01T12:00:00Z', 'ano passado'],
    // Rounding moves up a unit when the rounded value fills it.
    ['2026-11-19T11:00:20Z', 'há 1 hora'],
    ['2026-11-18T12:20:00Z', 'ontem'],
    ['2026-11-12T16:00:00Z', 'semana passada'],
    ['2025-11-20T12:00:00Z', 'ano passado'],
    // ...but never before half the larger unit.
    ['2026-11-19T00:00:00Z', 'há 12 horas'],
    ['2026-11-19T11:30:00Z', 'há 30 minutos'],
    // Past and future round the same way.
    ['2026-11-19T11:58:30Z', 'há 2 minutos'],
    ['2026-11-19T12:01:30Z', 'dentro de 2 minutos'],
  ])('%s is "%s"', (value, expected) => {
    expect(formatRelativeTime(value, now)).toBe(expected)
  })
})

describe('numbers', () => {
  it('uses a decimal comma and groups thousands from five digits', () => {
    expect(formatNumber(1234.5)).toBe('1234,5')
    expect(plain(formatNumber(12345.5))).toBe('12 345,5')
  })

  it('passes options through', () => {
    expect(formatNumber(2.456, { maximumFractionDigits: 1 })).toBe('2,5')
  })

  it('writes percentages from a ratio', () => {
    expect(formatPercent(0.42)).toBe('42%')
    expect(formatPercent(0.425, 1)).toBe('42,5%')
  })

  it('writes euros after the amount', () => {
    expect(plain(formatCurrency(1234.5))).toBe('1234,50 €')
  })

  it.each([null, undefined, Number.NaN])('shows a dash for %j', (value) => {
    expect(formatNumber(value)).toBe(EMPTY_VALUE)
    expect(formatPercent(value)).toBe(EMPTY_VALUE)
    expect(formatCurrency(value)).toBe(EMPTY_VALUE)
  })
})
