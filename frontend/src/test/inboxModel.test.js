import { describe, it, expect } from 'vitest'
import { dayGroup, groupByDay, itemTime, relativeTime, kindMeta, toMs } from '../components/inbox/inboxModel.js'

/** Saf model (v3, 2026-09-26): güne göre gruplama YEREL takvimle, göreli zaman Intl ile; `now` açık verilir (saat dilimi/CI bağımsız). */
const t = (k) => ({ 'inbox.justNow': 'az önce' })[k] || k
const NOW = new Date(2026, 8, 26, 12, 0, 0).getTime()   // yerel öğlen — gece yarısı sınırı yok
const HOUR = 3_600_000, DAY = 86_400_000
const localIso = (ms) => new Date(ms).toISOString().slice(0, 19)   // sunucu biçimi: çıplak UTC, 'Z' yok

describe('inboxModel', () => {
  it('toMs: çıplak UTC dizeye Z ekler; itemTime: at → started_at → ended_at', () => {
    expect(toMs('2026-09-26T09:00:00')).toBe(Date.UTC(2026, 8, 26, 9, 0, 0))
    expect(toMs('2026-09-26T09:00:00Z')).toBe(Date.UTC(2026, 8, 26, 9, 0, 0))
    expect(Number.isNaN(toMs(null))).toBe(true)
    expect(itemTime({ at: 'a', started_at: 'b' })).toBe('a')
    expect(itemTime({ started_at: 'b', ended_at: 'c' })).toBe('b')
    expect(itemTime({})).toBeNull()
  })

  it('dayGroup: bugün / dün / daha önce / yaklaşan (gelecek aynı gün dâhil)', () => {
    expect(dayGroup(localIso(NOW - 10 * 60e3), NOW)).toBe('today')
    expect(dayGroup(localIso(NOW - 11 * HOUR), NOW)).toBe('today')          // 01:00 bugün
    expect(dayGroup(localIso(NOW - 13 * HOUR), NOW)).toBe('yesterday')      // 23:00 dün
    expect(dayGroup(localIso(NOW - DAY), NOW)).toBe('yesterday')
    expect(dayGroup(localIso(NOW - 2 * DAY), NOW)).toBe('earlier')
    expect(dayGroup(localIso(NOW - 40 * DAY), NOW)).toBe('earlier')
    expect(dayGroup(localIso(NOW + 3 * HOUR), NOW)).toBe('upcoming')        // bugün 15:00
    expect(dayGroup(localIso(NOW + DAY), NOW)).toBe('upcoming')
    expect(dayGroup(null, NOW)).toBe('earlier')
  })

  it('groupByDay: sıra Yaklaşan → Bugün → Dün → Daha önce, yalnız dolu gruplar, grup içinde gelen sıra korunur', () => {
    const items = [
      { key: 'a', at: localIso(NOW - HOUR) }, { key: 'b', at: localIso(NOW - 3 * DAY) },
      { key: 'c', at: localIso(NOW - 2 * HOUR) }, { key: 'd', at: localIso(NOW + 2 * HOUR) },
    ]
    expect(groupByDay(items, NOW).map((g) => [g.key, g.items.map((i) => i.key)])).toEqual([
      ['upcoming', ['d']], ['today', ['a', 'c']], ['earlier', ['b']],
    ])
    expect(groupByDay([], NOW)).toEqual([])
  })

  it('relativeTime: az önce / dakika / saat / gün / gelecek; geçersizde boş', () => {
    expect(relativeTime(localIso(NOW - 20e3), t, 'tr-TR', NOW)).toBe('az önce')
    expect(relativeTime(localIso(NOW - 12 * 60e3), t, 'tr-TR', NOW)).toBe('12 dakika önce')
    expect(relativeTime(localIso(NOW - 3 * HOUR), t, 'en-GB', NOW)).toBe('3 hours ago')
    expect(relativeTime(localIso(NOW - 2 * DAY), t, 'en-GB', NOW)).toBe('2 days ago')
    expect(relativeTime(localIso(NOW + 5 * HOUR), t, 'en-GB', NOW)).toBe('in 5 hours')
    expect(relativeTime(localIso(NOW - 45 * DAY), t, 'en-GB', NOW)).toBe('2 months ago')
    expect(relativeTime(null, t, 'en-GB', NOW)).toBe('')
  })

  it('kindMeta: her tür ikon + kutucuk tonu; bilinmeyen tür varsayılana düşer (zil)', () => {
    for (const k of ['alert_open', 'alert_resolved', 'maintenance_active', 'maintenance_soon', 'weekly_due', 'exception_expired']) {
      expect(kindMeta(k).icon).toBeTypeOf('object')
      expect(kindMeta(k).tile).toMatch(/^bg-/)
    }
    expect(kindMeta('yeni_tur').tile).toContain('bg-muted')
    expect(kindMeta('alert_open').tile).not.toMatch(/border-l|inset/)   // sol şerit yok (kalıcı karar)
  })
})
