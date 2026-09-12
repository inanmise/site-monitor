import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import {
  EMPTY_FILTERS, filtersToParams, paramsToFilters, hasActiveFilter, rowMatches, TAB_LABEL_KEYS, tabLabel, unusedTabs, idleBand,
  loginStatus, relTime, splitDuration, failedTone, failedRatio, sparkFrom, deltaVsAvg, isOffHourCell, splitFlags, sortRows,
  sessionsCsv, anomaliesCsv, usageCsv, teamBars,
} from '../components/admin/useractivity/uactModel.js'

/** Kullanıcı / Oturum paneli saf modeli (2026-09-13) — React'siz. */
const t = (k, ...a) => k + (a.length ? ':' + a.join(',') : '')

describe('süzgeç ↔ URL', () => {
  it('varsayılan süzgeç param üretmez; 7g penceresi ve takım u_* ile gidip gelir', () => {
    expect(filtersToParams(EMPTY_FILTERS)).toEqual({ u_team: null, u_role: null, u_range: null })
    const f = { team: '5', role: 'ADMIN', range: '7d' }
    const p = filtersToParams(f)
    expect(paramsToFilters((k, d) => p[k] ?? d)).toEqual(f)
    expect(paramsToFilters((k, d) => ({ u_range: 'bogus' }[k] ?? d)).range).toBe('24h')
    expect(hasActiveFilter(EMPTY_FILTERS)).toBe(false)
    expect(hasActiveFilter({ ...EMPTY_FILTERS, range: '7d' })).toBe(true)
  })
  it('rowMatches: takım id ya da adıyla, rol alanıyla; boş süzgeç hepsini geçirir', () => {
    expect(rowMatches({ team_id: 5, team_name: 'Takım A', system_role: 'USER' }, { team: '5', role: '' })).toBe(true)
    expect(rowMatches({ team_name: 'Takım A' }, { team: 'Takım A', role: '' })).toBe(true)
    expect(rowMatches({ team_id: 9 }, { team: '5', role: '' })).toBe(false)
    expect(rowMatches({ role: 'ADMIN' }, { team: '', role: 'ADMIN' })).toBe(true)
    expect(rowMatches({ system_role: 'USER' }, { team: '', role: 'ADMIN' })).toBe(false)
    expect(rowMatches(null, EMPTY_FILTERS)).toBe(false)
  })
})

describe('sekme etiketleri', () => {
  it('Nav.jsx\'teki her sekme id\'si haritada var (kaynak kapısı) ve etiket nav anahtarından gelir', () => {
    const src = fs.readFileSync(path.join(process.cwd(), 'src/components/Nav.jsx'), 'utf8')
    const ids = [...src.matchAll(/\{ id: '([a-z-]+)',\s+Icon/g)].map((m) => m[1])
    expect(ids.length).toBeGreaterThan(20)
    expect(ids.filter((id) => !TAB_LABEL_KEYS[id])).toEqual([])
    expect(tabLabel('forecast', t)).toBe('nav.forecast')
    expect(tabLabel('unknown-tab', t)).toBe('unknown-tab')
    expect(unusedTabs([{ tab: 'forecast' }, { tab: 'domains' }])).not.toContain('forecast')
    expect(unusedTabs([])).toContain('dashboard')
  })
})

describe('durum türetimleri', () => {
  it('idleBand: 5 dk canlı, 30 dk boşta, sonrası uzakta, -1 bilinmiyor', () => {
    expect(idleBand(10)).toBe('live'); expect(idleBand(299)).toBe('live'); expect(idleBand(300)).toBe('idle'); expect(idleBand(1800)).toBe('away'); expect(idleBand(-1)).toBe('unknown'); expect(idleBand(null)).toBe('unknown')
  })
  it('loginStatus: aktif > bugün > hafta > ay > atıl > hiç; büyük/küçük harf duyarsız', () => {
    const now = Date.parse('2026-09-13T10:00:00Z')
    const at = (d) => new Date(now - d * 86_400_000).toISOString().slice(0, 19)
    expect(loginStatus({ username: 'Admin', last_login_at: at(0.1) }, new Set(['admin']), now)).toBe('active')
    expect(loginStatus({ username: 'x', last_login_at: at(0.5) }, new Set(), now)).toBe('today')
    expect(loginStatus({ username: 'x', last_login_at: at(3) }, new Set(), now)).toBe('week')
    expect(loginStatus({ username: 'x', last_login_at: at(20) }, new Set(), now)).toBe('month')
    expect(loginStatus({ username: 'x', last_login_at: at(45) }, new Set(), now)).toBe('dormant')
    expect(loginStatus({ username: 'x', last_login_at: null }, new Set(), now)).toBe('never')
    expect(loginStatus({ username: 'x', last_login_at: 'garbage' }, new Set(), now)).toBe('never')
  })
  it('relTime birim seçer; splitDuration saat/dk/sn böler', () => {
    const now = Date.parse('2026-09-13T10:00:00Z')
    expect(relTime('2026-09-13T09:59:30', now)).toEqual({ unit: 'sec', n: 30 })
    expect(relTime('2026-09-13T09:30:00', now)).toEqual({ unit: 'min', n: 30 })
    expect(relTime('2026-09-13T04:00:00', now)).toEqual({ unit: 'hour', n: 6 })
    expect(relTime('2026-09-10T10:00:00', now)).toEqual({ unit: 'day', n: 3 })
    expect(relTime('2026-07-13T10:00:00', now)).toEqual({ unit: 'month', n: 2 })
    expect(relTime('2024-09-13T10:00:00', now)).toEqual({ unit: 'year', n: 2 })
    expect(relTime(null)).toBeNull()
    expect(splitDuration(3725)).toEqual({ h: 1, m: 2, s: 5 })
  })
  it('failedTone/Ratio: %20+ tehlike, %5+ uyarı; istek yoksa nötr', () => {
    expect(failedTone(0, 0)).toBe('neutral'); expect(failedTone(3, 300)).toBe('neutral'); expect(failedTone(6, 94)).toBe('warn'); expect(failedTone(3, 4)).toBe('danger')
    expect(failedRatio(3, 4)).toBe(43); expect(failedRatio(0, 0)).toBe(0)
  })
  it('sparkFrom/deltaVsAvg: günlük seriden; bugün 6 gün ortalamasına karşı yüzde; kısa seri null', () => {
    const series = { day: [1, 1, 1, 1, 1, 1, 2].map((v) => ({ success: v })) }
    expect(sparkFrom(series, 'success')).toEqual([1, 1, 1, 1, 1, 1, 2])
    expect(deltaVsAvg(series, 'success')).toBe(100)
    expect(deltaVsAvg({ day: [{ success: 1 }] }, 'success')).toBeNull()
    expect(deltaVsAvg({ day: [0, 0, 0].map((v) => ({ success: v })) }, 'success')).toBe(0)
  })
  it('isOffHourCell: hafta sonu tamamı, hafta içi mesai dışı saatler (bitiş hariç)', () => {
    const o = { start: 8, end: 20 }
    expect(isOffHourCell(5, 12, o)).toBe(true); expect(isOffHourCell(6, 12, o)).toBe(true)
    expect(isOffHourCell(0, 7, o)).toBe(true); expect(isOffHourCell(0, 8, o)).toBe(false); expect(isOffHourCell(0, 19, o)).toBe(false); expect(isOffHourCell(0, 20, o)).toBe(true)
    expect(isOffHourCell(0, 3)).toBe(true)
  })
  it('splitFlags / sortRows', () => {
    expect(splitFlags('OFF_HOURS, UNUSUAL_IP')).toEqual(['OFF_HOURS', 'UNUSUAL_IP']); expect(splitFlags(null)).toEqual([])
    expect(sortRows([{ a: 2 }, { a: 1 }], 'a', 'asc').map((r) => r.a)).toEqual([1, 2])
    expect(sortRows([{ a: 'b' }, { a: 'a' }], 'a', 'desc').map((r) => r.a)).toEqual(['b', 'a'])
  })
})

describe('CSV ve çubuklar', () => {
  it('CSV başlık + satır; virgüllü hücre tırnaklanır (utils/csv)', () => {
    const csv = sessionsCsv([{ username: 'u1', team_name: 'Takım, A', idle_sec: 5, last_tab: 'forecast' }], t)
    expect(csv.split('\r\n')).toHaveLength(2)
    expect(csv).toContain('"Takım, A"')
    expect(anomaliesCsv([{ time: 'x', actor: 'u', flags: 'OFF_HOURS', ack: { by: 'admin', at: '2026-09-13T00:00:00' } }], t)).toContain('admin 2026-09-13T00:00:00')
    expect(usageCsv([{ tab: 'forecast', minutes: 3, users: 1, share: 50 }], t)).toContain('nav.forecast')
  })
  it('teamBars: en büyük 100, diğerleri orantılı; boş liste güvenli', () => {
    expect(teamBars([{ count: 10 }, { count: 5 }]).map((r) => r.pct)).toEqual([100, 50])
    expect(teamBars([])).toEqual([])
  })
})
