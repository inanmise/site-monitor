import { describe, it, expect } from 'vitest'
import {
  FILTER_DEFAULTS, PRESETS, RANGE_ACTIVE, RANGE_RESOLVED, RANGE_OPENED, filtersFromUrl, filtersToUrl, listParams, activeAlertFilters,
  resolveRange, dateKind, dateKindOptions, rangeForKind, rangeChip, sortParts, sortValue, toggleSort, isSortValue, widerPreset,
  groupByDay, quickRange, dayStart, defaultSortKey,
} from '../components/admin/alerts/alertHistoryModel.js'

/**
 * Alarm Geçmişi — hızlı dönemler, tarih alanı kipi ve sütun sıralaması (2026-10-01, kullanıcı isteği: "son 1 saatte
 * açılanlar", "belirli tarihlerde açılan / kapanan", "açılış-kapanış tarihine göre hızlı sıralama"). SAF model: URL
 * sözleşmesi ↔ sunucu parametreleri; bileşen yok.
 */
const read = (obj) => (k, d = '') => (obj[k] != null ? obj[k] : d)
const F = (o = {}) => ({ ...FILTER_DEFAULTS, ...o })
const ISO = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/
const params = (tab, o, nowMs) => listParams({ tab, filters: F(o), page: 0, pageSize: 20, domain: null, typesParam: null, nowMs })

describe('URL → süzgeç (filtersFromUrl)', () => {
  it('sort yalnız beyaz liste: <anahtar> ya da <anahtar>_asc; başka sayfanın sort değeri düşer', () => {
    expect(filtersFromUrl(read({ sort: 'level_asc' })).sort).toBe('level_asc')
    expect(filtersFromUrl(read({ sort: 'opened' })).sort).toBe('opened')
    expect(filtersFromUrl(read({ sort: 'opened_desc' })).sort).toBe('')     // yön yalnız _asc ekiyle
    expect(filtersFromUrl(read({ sort: 'name' })).sort).toBe('')            // Tüm Sertifikalar'ın sort'u
    expect(filtersFromUrl(read({ sort: 'e.domain;DROP' })).sort).toBe('')
    expect(isSortValue('team_asc')).toBe(true)
    expect(isSortValue('team_desc')).toBe(false)
  })

  it('range: active | resolved | opened geçerli, diğer sayfaların değeri (7, custom) düşer', () => {
    expect(filtersFromUrl(read({ range: 'active' })).range).toBe(RANGE_ACTIVE)
    expect(filtersFromUrl(read({ range: 'resolved' })).range).toBe(RANGE_RESOLVED)
    expect(filtersFromUrl(read({ range: 'opened' })).range).toBe(RANGE_OPENED)
    expect(filtersFromUrl(read({ range: '7' })).range).toBe('')
    expect(filtersFromUrl(read({ range: 'custom' })).range).toBe('')
  })

  it('from hızlı dönem belirteci taşıyabilir (1h/24h/7d/30d); belirteçte to sıfırlanır; gün/damga olduğu gibi kalır', () => {
    expect(PRESETS).toEqual(['1h', '24h', '7d', '30d'])
    const f = filtersFromUrl(read({ from: '7d', to: '2026-09-27' }))
    expect(f.from).toBe('7d'); expect(f.to).toBe('')
    const g = filtersFromUrl(read({ from: '2026-09-21', to: '2026-09-27' }))
    expect(g.from).toBe('2026-09-21'); expect(g.to).toBe('2026-09-27')
  })

  it('filtersToUrl: sort ve range varsayılanken URL\'e yazılmaz (null)', () => {
    const u = filtersToUrl(F(), 'open')
    expect(u.sort).toBeNull(); expect(u.range).toBeNull(); expect(u.from).toBeNull(); expect(u.view).toBeNull()
    const v = filtersToUrl(F({ sort: 'level', from: '1h', range: RANGE_ACTIVE }), 'all')
    expect(v).toMatchObject({ sort: 'level', from: '1h', range: 'active', view: 'all' })
  })
})

describe('tarih alanı kipi (dateKind / range)', () => {
  it('açık: her zaman açılış, seçenek yok; kapalı: varsayılan KAPANIŞ, range=opened ile açılış; tümü: varsayılan açılış', () => {
    expect(dateKind(F(), 'open')).toBe('opened')
    expect(dateKind(F({ range: RANGE_RESOLVED }), 'open')).toBe('opened')
    expect(dateKindOptions('open')).toEqual([])
    expect(dateKind(F(), 'closed')).toBe('resolved')
    expect(dateKind(F({ range: RANGE_OPENED }), 'closed')).toBe('opened')
    expect(dateKind(F({ range: RANGE_ACTIVE }), 'closed')).toBe('resolved')   // active yalnız Tümü'nde
    expect(dateKindOptions('closed')).toEqual(['resolved', 'opened'])
    expect(dateKind(F(), 'all')).toBe('opened')
    expect(dateKind(F({ range: RANGE_ACTIVE }), 'all')).toBe('active')
    expect(dateKind(F({ range: RANGE_RESOLVED }), 'all')).toBe('resolved')
    expect(dateKindOptions('all')).toEqual(['opened', 'resolved', 'active'])
  })

  it('rangeForKind: seçim → range yaması (görünümün varsayılanı boş); rangeChip: yalnız varsayılan dışı', () => {
    expect(rangeForKind('resolved', 'closed')).toBe('')
    expect(rangeForKind('opened', 'closed')).toBe(RANGE_OPENED)
    expect(rangeForKind('opened', 'all')).toBe('')
    expect(rangeForKind('resolved', 'all')).toBe(RANGE_RESOLVED)
    expect(rangeForKind('active', 'all')).toBe(RANGE_ACTIVE)
    expect(rangeChip(F({ range: RANGE_OPENED }), 'closed')).toBe(RANGE_OPENED)
    expect(rangeChip(F({ range: RANGE_ACTIVE }), 'closed')).toBeNull()
    expect(rangeChip(F({ range: RANGE_ACTIVE }), 'all')).toBe(RANGE_ACTIVE)
    expect(rangeChip(F({ range: RANGE_RESOLVED }), 'all')).toBe(RANGE_RESOLVED)
    expect(rangeChip(F({ range: RANGE_OPENED }), 'all')).toBeNull()
    expect(rangeChip(F({ range: RANGE_RESOLVED }), 'open')).toBeNull()
  })
})

describe('hızlı dönemler → sunucu damgaları (resolveRange / listParams)', () => {
  const now = Date.UTC(2026, 8, 30, 12, 30, 0)   // 2026-09-30T12:30:00Z

  it('1h ve 24h ŞİMDİYE göre göreli damga, bitiş yok', () => {
    expect(resolveRange(F({ from: '1h' }), now)).toEqual({ since: '2026-09-30T11:30:00', until: '' })
    expect(resolveRange(F({ from: '24h' }), now)).toEqual({ since: '2026-09-29T12:30:00', until: '' })
  })

  it('7d / 30d yerel gün sınırından (Europe/Istanbul\'da 21:00Z), gün seçiciyle aynı sınır; bitiş yok', () => {
    const r7 = resolveRange(F({ from: '7d' }), now)
    expect(r7.until).toBe('')
    expect(r7.since).toBe(dayStart(quickRange(7, new Date(now)).from))
    const localMidnight = (() => { const d = new Date(now - 7 * 86_400_000); return new Date(d.getFullYear(), d.getMonth(), d.getDate()).toISOString().slice(0, 19) })()
    expect(r7.since).toBe(localMidnight)
    expect(resolveRange(F({ from: '30d' }), now).since).toBe(dayStart(quickRange(30, new Date(now)).from))
  })

  it('gün süzgeci gün sınırlarına açılır, tam damga olduğu gibi geçer', () => {
    const r = resolveRange(F({ from: '2026-09-21', to: '2026-09-27' }), now)
    expect(r.since).toBe(dayStart('2026-09-21')); expect(r.until).toMatch(/^2026-09-2[78]T/)
    expect(resolveRange(F({ from: '2026-09-21T10:00:00' }), now).since).toBe('2026-09-21T10:00:00')
  })

  it('AÇIK görünümde tarih aralığı SUNUCUYA GİDER (since/until) — "son 1 saatte açılanlar"', () => {
    const p = params('open', { from: '1h' }, now)
    expect(p).toMatchObject({ resolved: 'false', since: '2026-09-30T11:30:00' })
    expect(p.until).toBeUndefined(); expect(p.resolvedSince).toBeUndefined(); expect(p.range).toBeUndefined()
    const q = params('open', { from: '2026-09-21', to: '2026-09-27' }, now)
    expect(q.since).toMatch(ISO); expect(q.until).toMatch(ISO)
  })

  it('KAPALI: varsayılan kapanış anı (resolvedSince/Until); range=opened → since/until', () => {
    const p = params('closed', { from: '24h' }, now)
    expect(p).toMatchObject({ resolved: 'true', resolvedSince: '2026-09-29T12:30:00' })
    expect(p.since).toBeUndefined()
    const q = params('closed', { from: '2026-09-21', to: '2026-09-27', range: RANGE_OPENED }, now)
    expect(q.since).toMatch(ISO); expect(q.until).toMatch(ISO); expect(q.resolvedSince).toBeUndefined(); expect(q.range).toBeUndefined()
  })

  it('TÜMÜ: varsayılan açılış; range=resolved → kapanış; range=active → since + range=active (B3 korunur)', () => {
    expect(params('all', { from: '7d' }, now)).not.toHaveProperty('resolved')
    expect(params('all', { from: '7d' }, now).since).toMatch(ISO)
    const r = params('all', { from: '2026-09-21', to: '2026-09-27', range: RANGE_RESOLVED }, now)
    expect(r.resolvedSince).toMatch(ISO); expect(r.resolvedUntil).toMatch(ISO); expect(r.since).toBeUndefined()
    const a = params('all', { from: '2026-09-21', to: '2026-09-27', range: RANGE_ACTIVE }, now)
    expect(a.range).toBe('active'); expect(a.since).toMatch(ISO); expect(a.until).toMatch(ISO)
  })
})

describe('sıralama (sortParts / sortValue / toggleSort / listParams)', () => {
  it('varsayılan: açık ve tümü açılış, kapalı kapanış — hep en yeni önce; open görünümünde resolved anahtarı varsayılana düşer', () => {
    expect(defaultSortKey('open')).toBe('opened'); expect(defaultSortKey('closed')).toBe('resolved'); expect(defaultSortKey('all')).toBe('opened')
    expect(sortParts('', 'open')).toEqual({ key: 'opened', dir: 'desc' })
    expect(sortParts('', 'closed')).toEqual({ key: 'resolved', dir: 'desc' })
    expect(sortParts('level_asc', 'all')).toEqual({ key: 'level', dir: 'asc' })
    expect(sortParts('resolved', 'open')).toEqual({ key: 'opened', dir: 'desc' })
    expect(sortParts('bogus', 'all')).toEqual({ key: 'opened', dir: 'desc' })
  })

  it('sortValue: görünümün varsayılanı "" (URL\'e yazılmaz); aksi hâlde anahtar[_asc]', () => {
    expect(sortValue('opened', 'desc', 'open')).toBe('')
    expect(sortValue('opened', 'asc', 'open')).toBe('opened_asc')
    expect(sortValue('resolved', 'desc', 'closed')).toBe('')
    expect(sortValue('opened', 'desc', 'closed')).toBe('opened')
    expect(sortValue('level', 'desc', 'all')).toBe('level')
    expect(sortValue('nope', 'desc', 'all')).toBe('')
  })

  it('toggleSort: aynı sütun yön değiştirir; yeni sütun tarih/seviye için desc, metin için asc', () => {
    expect(toggleSort('', 'opened', 'open')).toBe('opened_asc')          // varsayılan opened desc → asc
    expect(toggleSort('opened_asc', 'opened', 'open')).toBe('')          // → desc = varsayılan
    expect(toggleSort('', 'level', 'open')).toBe('level')
    expect(toggleSort('level', 'level', 'open')).toBe('level_asc')
    expect(toggleSort('', 'domain', 'closed')).toBe('domain_asc')
    expect(toggleSort('domain_asc', 'domain', 'closed')).toBe('domain')
    expect(toggleSort('', 'team', 'all')).toBe('team_asc')
    expect(toggleSort('', 'resolved', 'all')).toBe('resolved')
  })

  it('listParams: varsayılan sıralama GÖNDERİLMEZ (sunucu varsayılanıyla aynı); aksi hâlde sort + dir', () => {
    expect(params('open', {})).not.toHaveProperty('sort')
    expect(params('closed', {})).not.toHaveProperty('sort')
    expect(params('open', { sort: 'level_asc' })).toMatchObject({ sort: 'level', dir: 'asc' })
    expect(params('closed', { sort: 'opened' })).toMatchObject({ sort: 'opened', dir: 'desc' })
    expect(params('all', { sort: 'team_asc' })).toMatchObject({ sort: 'team', dir: 'asc' })
  })
})

describe('etkin çipler ve gün bölümleri', () => {
  it('hızlı dönem TEK çip (preset, × from+to sıfırlar); açık görünümde de tarih çipleri var; kip çipi görünüme göre', () => {
    expect(activeAlertFilters(F({ from: '7d' }), 'open')).toEqual([{ key: 'preset', value: '7d', patch: { from: '', to: '' } }])
    expect(activeAlertFilters(F({ from: '2026-09-21', to: '2026-09-27' }), 'open').map((c) => c.key)).toEqual(['from', 'to'])
    expect(activeAlertFilters(F({ range: RANGE_OPENED, from: '1h' }), 'closed').map((c) => c.key)).toEqual(['range', 'preset'])
    expect(activeAlertFilters(F({ range: RANGE_RESOLVED }), 'all')[0]).toEqual({ key: 'range', value: 'resolved', patch: { range: '' } })
    expect(activeAlertFilters(F({ range: RANGE_ACTIVE }), 'all')[0]).toEqual({ key: 'range', value: 'active', patch: { range: '' } })
    expect(activeAlertFilters(F({ range: RANGE_ACTIVE }), 'closed')).toEqual([])   // kapalıda active geçersiz → gizli süzgeç yok
  })

  it('widerPreset: 1h → 24h → 7d → 30d → "" (tüm zamanlar); belirteç değilse null', () => {
    expect(widerPreset('1h')).toBe('24h'); expect(widerPreset('24h')).toBe('7d'); expect(widerPreset('7d')).toBe('30d')
    expect(widerPreset('30d')).toBe(''); expect(widerPreset('2026-09-21')).toBeNull(); expect(widerPreset('')).toBeNull()
  })

  it('groupByDay: sıralama alanına göre gün; seviye/takım/alan adı sıralamasında tek başlıksız bölüm', () => {
    const now = new Date(2026, 8, 30, 12, 0, 0)
    const a = { id: 1, created_at: '2026-09-30T08:00:00', resolved_at: '2026-09-30T09:00:00' }
    const b = { id: 2, created_at: '2026-09-20T08:00:00', resolved_at: '2026-09-30T08:30:00' }
    expect(groupByDay([a, b], 'closed', now).map((g) => g.items.length)).toEqual([2])            // kapanış aynı gün
    expect(groupByDay([a, b], 'closed', now, 'opened').map((g) => g.items.length)).toEqual([1, 1])
    expect(groupByDay([a, b], 'all', now).map((g) => g.items.length)).toEqual([1, 1])            // açılış farklı gün
    const flat = groupByDay([a, b], 'all', now, 'level')
    expect(flat).toHaveLength(1); expect(flat[0].kind).toBe('none'); expect(flat[0].items).toEqual([a, b])
    expect(groupByDay([], 'all', now, 'team')).toEqual([])
  })
})
