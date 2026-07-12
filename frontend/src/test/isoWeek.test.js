import { describe, it, expect } from 'vitest'
import { isoWeekInfo, isoWeekRange, formatWeekRange, isEditableWeek, monthGrid } from '../utils/isoWeek'

describe('isoWeekInfo', () => {
  it('computes the ISO week of a mid-year date', () => {
    expect(isoWeekInfo(new Date(2026, 5, 12))).toEqual({ year: 2026, week: 24 })
  })

  it('assigns year-boundary days to the ISO week-based year', () => {
    // 29 Aralık 2025 Pazartesi → 2026-W01
    expect(isoWeekInfo(new Date(2025, 11, 29))).toEqual({ year: 2026, week: 1 })
    // 1 Ocak 2027 Cuma → 2026-W53 (2026 Perşembe başlar, 53 haftalık ISO yılı)
    expect(isoWeekInfo(new Date(2027, 0, 1))).toEqual({ year: 2026, week: 53 })
  })
})

describe('isoWeekRange', () => {
  it('returns Monday–Sunday of the ISO week (full week)', () => {
    const { monday, sunday } = isoWeekRange(2026, 24)
    expect(monday.toISOString().slice(0, 10)).toBe('2026-06-08')
    expect(sunday.toISOString().slice(0, 10)).toBe('2026-06-14')
  })
})

describe('formatWeekRange', () => {
  it('formats a same-month week (TR backend computeWeekLabel parity)', () => {
    expect(formatWeekRange(2026, 24, 'tr')).toBe('8–14 Haziran 2026')
  })

  it('formats a month-crossing week', () => {
    expect(formatWeekRange(2026, 27, 'tr')).toBe('29 Haziran – 5 Temmuz 2026')
  })

  it('formats a year-crossing week with both years', () => {
    expect(formatWeekRange(2026, 1, 'tr')).toBe('29 Aralık 2025 – 4 Ocak 2026')
  })

  it('uses English month names for lang=en', () => {
    expect(formatWeekRange(2026, 24, 'en')).toBe('8–14 June 2026')
  })

  it('returns em dash for invalid input', () => {
    expect(formatWeekRange(2026, 0, 'tr')).toBe('—')
    expect(formatWeekRange(2026, 54, 'tr')).toBe('—')
    expect(formatWeekRange(null, 24, 'tr')).toBe('—')
  })
})

describe('monthGrid', () => {
  it('builds Monday-first rows with correct ISO weeks (June 2026)', () => {
    const rows = monthGrid(2026, 5) // Haziran 2026 — 1'i Pazartesi, 5 satır
    expect(rows).toHaveLength(5)
    expect(rows[0].days[0].toISOString().slice(0, 10)).toBe('2026-06-01')
    expect(rows[0].week).toEqual({ year: 2026, week: 23 })
    expect(rows[1].week).toEqual({ year: 2026, week: 24 })
    expect(rows[4].days[6].toISOString().slice(0, 10)).toBe('2026-07-05')
  })

  it('pads the first row back to Monday for mid-week month starts', () => {
    const rows = monthGrid(2026, 0) // Ocak 2026 — 1'i Perşembe
    expect(rows[0].days[0].toISOString().slice(0, 10)).toBe('2025-12-29')
    expect(rows[0].week).toEqual({ year: 2026, week: 1 })
  })
})

describe('isEditableWeek', () => {
  it('accepts current and previous ISO week, rejects older', () => {
    const cur = isoWeekInfo()
    const prev = isoWeekInfo(new Date(Date.now() - 7 * 86400000))
    const old = isoWeekInfo(new Date(Date.now() - 14 * 86400000))
    expect(isEditableWeek({ report_year: cur.year, week_no: cur.week })).toBe(true)
    expect(isEditableWeek({ report_year: prev.year, week_no: prev.week })).toBe(true)
    expect(isEditableWeek({ report_year: old.year, week_no: old.week })).toBe(false)
  })
})
