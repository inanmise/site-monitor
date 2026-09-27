import { describe, it, expect } from 'vitest'
import {
  occurrences, currentOccurrence, nextOccurrence, computeStatus, compressDays, humanMinutes,
  zonedToInstant, wallClock, parseIso, toIso, DAY_MS, buildAgenda, sortWindows, TILE_PRED,
} from '../components/maintenance/maintenanceSchedule.js'

/**
 * Bakım penceresi oluşum motoru (istemci) — sunucunun MaintenanceService kurallarıyla aynı:
 * çapa duvar saati pencerenin diliminde, DST-güvenli; DAILY/WEEKLY/MONTHLY; duraklatılmış hiç tetiklenmez.
 * Zaman SABİT değil: her senaryo `now`u kendi kurar (kayan pencere yok → zaman bombası yok).
 */
const NOW = Date.parse('2026-09-26T09:30:00Z')   // Cumartesi 12:30 Europe/Istanbul
const w = (o) => ({ timezone: 'Europe/Istanbul', duration_minutes: 60, recurrence: 'NONE', active: true, ...o })

describe('maintenanceSchedule — saat dilimi', () => {
  it('duvar saati → an: Europe/Istanbul UTC+3, DST yok', () => {
    expect(zonedToInstant(2026, 9, 28, 22, 0, 'Europe/Istanbul')).toBe(Date.parse('2026-09-28T19:00:00Z'))
    expect(wallClock(Date.parse('2026-09-28T19:00:00Z'), 'Europe/Istanbul')).toMatchObject({ y: 2026, m: 9, d: 28, h: 22, mi: 0, dow: 1 })
  })
  it('DST kenarı (Europe/London, 25 Ekim 2026): 09:00 duvar saati yaz saatinde 08:00Z, kıştan sonra 09:00Z', () => {
    expect(zonedToInstant(2026, 10, 24, 9, 0, 'Europe/London')).toBe(Date.parse('2026-10-24T08:00:00Z'))
    expect(zonedToInstant(2026, 10, 26, 9, 0, 'Europe/London')).toBe(Date.parse('2026-10-26T09:00:00Z'))
  })
  it('geçersiz dilim UTC\'ye düşer; ISO ayrıştırma Z\'siz dizeyi UTC sayar', () => {
    expect(zonedToInstant(2026, 1, 1, 12, 0, 'Not/AZone')).toBe(Date.parse('2026-01-01T12:00:00Z'))
    expect(parseIso('2026-09-26T09:30:00')).toBe(NOW)
    expect(parseIso('2026-09-26')).toBe(Date.parse('2026-09-26T00:00:00Z'))
    expect(parseIso('')).toBeNull()
    expect(toIso(NOW)).toBe('2026-09-26T09:30:00')
  })
})

describe('maintenanceSchedule — oluşumlar', () => {
  it('tek seferlik: aralıkla kesişiyorsa tek oluşum, süren pencere currentOccurrence ile bulunur', () => {
    const win = w({ start_at: toIso(NOW - 20 * 60_000), duration_minutes: 60 })
    expect(occurrences(win, NOW, NOW + DAY_MS)).toEqual([{ start: NOW - 20 * 60_000, end: NOW + 40 * 60_000 }])
    expect(currentOccurrence(win, NOW)).toEqual({ start: NOW - 20 * 60_000, end: NOW + 40 * 60_000 })
    expect(occurrences(win, NOW + 2 * DAY_MS, NOW + 3 * DAY_MS)).toEqual([])
    expect(computeStatus(win, NOW)).toBe('active')
    expect(computeStatus(win, NOW + 2 * DAY_MS)).toBe('completed')
    expect(computeStatus(win, NOW - DAY_MS)).toBe('upcoming')
  })
  it('haftalık Pzt–Cum 22:00 İstanbul: Cumartesi\'den sonraki ilk oluşum Pazartesi 19:00Z; 7 günde 5 oluşum', () => {
    const win = w({ start_at: '2026-08-31T19:00:00', recurrence: 'WEEKLY', days_of_week: '1,2,3,4,5' })
    const next = nextOccurrence(win, NOW)
    expect(next).toEqual({ start: Date.parse('2026-09-28T19:00:00Z'), end: Date.parse('2026-09-28T20:00:00Z') })
    const week = occurrences(win, NOW, NOW + 7 * DAY_MS)
    expect(week.map((o) => wallClock(o.start, 'Europe/Istanbul').dow)).toEqual([1, 2, 3, 4, 5])
    expect(computeStatus(win, NOW)).toBe('upcoming')
    expect(computeStatus(win, Date.parse('2026-09-28T19:30:00Z'))).toBe('active')
  })
  it('çapa gününden ÖNCEKİ günler sayılmaz; gece yarısını aşan oluşum dünden bugüne taşar', () => {
    const win = w({ start_at: '2026-09-30T20:30:00', recurrence: 'DAILY', duration_minutes: 120 })   // 23:30 İstanbul, 2 sa
    expect(occurrences(win, NOW, NOW + 3 * DAY_MS)).toEqual([])   // çapa 30 Eylül, 26–29 arası yok
    const at = Date.parse('2026-10-01T21:00:00Z')   // 2 Ekim 00:00 İstanbul — 1 Ekim 23:30'da başlayan oluşum sürüyor
    expect(currentOccurrence(win, at)).toEqual({ start: Date.parse('2026-10-01T20:30:00Z'), end: Date.parse('2026-10-01T22:30:00Z') })
  })
  it('aylık 31. gün kısa ayda ayın son gününe düşer', () => {
    const win = w({ start_at: '2026-01-31T07:00:00', recurrence: 'MONTHLY', day_of_month: 31 })
    const list = occurrences(win, Date.parse('2026-04-01T00:00:00Z'), Date.parse('2026-05-01T00:00:00Z'))
    expect(list).toHaveLength(1)
    expect(wallClock(list[0].start, 'Europe/Istanbul')).toMatchObject({ m: 4, d: 30, h: 10 })
  })
  it('duraklatılmış pencere hiç tetiklenmez (includePaused ile önizlenebilir); gün seçilmemiş haftalık boş', () => {
    const paused = w({ start_at: toIso(NOW - 10 * 60_000), active: false })
    expect(occurrences(paused, NOW, NOW + DAY_MS)).toEqual([])
    expect(occurrences(paused, NOW, NOW + DAY_MS, { includePaused: true })).toHaveLength(1)
    expect(computeStatus(paused, NOW)).toBe('paused')
    expect(occurrences(w({ start_at: '2026-01-01T10:00:00', recurrence: 'WEEKLY', days_of_week: '' }), NOW, NOW + 30 * DAY_MS)).toEqual([])
  })
})

describe('maintenanceSchedule — metin yardımcıları', () => {
  it('compressDays ardışık günleri aralığa sıkıştırır', () => {
    expect(compressDays([1, 2, 3, 4, 5])).toEqual([[1, 5]])
    expect(compressDays([5, 1, 3, 3])).toEqual([[1, 1], [3, 3], [5, 5]])
    expect(compressDays([6, 7, 1])).toEqual([[1, 1], [6, 7]])
  })
  it('humanMinutes saat + dakika', () => {
    const f = { hour: (h) => `${h} h`, minute: (m) => `${m} min` }
    expect(humanMinutes(90, f)).toBe('1 h 30 min')
    expect(humanMinutes(120, f)).toBe('2 h')
    expect(humanMinutes(45, f)).toBe('45 min')
    expect(humanMinutes(0, f)).toBe('0 min')
  })
})

describe('MaintenanceWindowsPage — türetilmiş liste', () => {
  const rows = [
    { w: { id: 5, name: 'Eski', status: 'completed', start_at: '2026-09-24T09:00:00', recurrence: 'NONE' }, next: null },
    { w: { id: 4, name: 'Duraklı', status: 'paused', start_at: '2026-09-29T09:00:00', recurrence: 'NONE' }, next: null },
    { w: { id: 2, name: 'Haftalık', status: 'upcoming', recurrence: 'WEEKLY' }, next: { start: NOW + 2 * DAY_MS } },
    { w: { id: 3, name: 'Günlük', status: 'upcoming', recurrence: 'DAILY' }, next: { start: NOW + 2 * 3_600_000 } },
    { w: { id: 1, name: 'Aktif', status: 'active', recurrence: 'NONE' }, next: null },
    { w: { id: 6, name: 'Daha eski', status: 'completed', start_at: '2026-09-10T09:00:00', recurrence: 'NONE' }, next: null },
  ]
  it('sıralama: süren → yaklaşan (sıradakine göre) → duraklatılmış → bitmiş (yeni önce)', () => {
    expect(sortWindows(rows).map((x) => x.w.id)).toEqual([1, 3, 2, 4, 5, 6])
  })
  it('özet kartı süzgeçleri liste verisinden sayar', () => {
    const count = (k) => rows.filter((x) => TILE_PRED[k](x, NOW)).length
    expect({ active: count('active'), next24h: count('next24h'), next7d: count('next7d'), recurring: count('recurring'), paused: count('paused'), past: count('past') })
      .toEqual({ active: 1, next24h: 1, next7d: 2, recurring: 2, paused: 1, past: 2 })
  })
  it('ajanda: 7 gün, tekrarlayan pencere her güne açılır, süren oluşum bugüne yazılır', () => {
    const daily = w({ id: 3, start_at: '2026-09-01T12:00:00', recurrence: 'DAILY', duration_minutes: 45 })   // 15:00 İstanbul
    const running = w({ id: 1, start_at: toIso(NOW - 20 * 60_000), duration_minutes: 60 })
    const paused = w({ id: 4, start_at: toIso(NOW + 3_600_000), active: false })
    const groups = buildAgenda([daily, running, paused], NOW)
    expect(groups).toHaveLength(7)
    expect(groups.flatMap((g) => g.items).filter((i) => i.w.id === 3)).toHaveLength(7)
    expect(groups[0].items.map((i) => [i.w.id, i.running])).toEqual([[1, true], [3, false]])
    expect(groups.flatMap((g) => g.items).some((i) => i.w.id === 4)).toBe(false)
  })
})
