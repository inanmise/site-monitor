import { describe, it, expect } from 'vitest'
import {
  TABLE_COLUMNS, defaultCols, normalizeCols, moveCol, savePreset, PRESET_MAX, activeFilterChips, countActiveFilters,
  toQuery, toUrlMapping, filtersFromUrl, EMPTY_FILTERS, levelOf, trustOf, lifetimePct, isStale, relTime, shortFp,
  buildSelectionCsv, csvColumnsFor,
} from '../components/certtable/certTableModel.js'

// Tüm Sertifikalar modeli (2026-09-13): bileşenden bağımsız saf kurallar.
const t = (k, ...a) => (a.length ? `${k}:${a.join(',')}` : k)

describe('certTableModel — sütunlar', () => {
  it('varsayılan: Konu KAPALI, Güven AÇIK; domain daima ilk; sabitler atılamaz; bilinmeyen anahtar elenir', () => {
    const d = defaultCols()
    expect(d).not.toContain('subject')
    expect(d).toContain('trust')
    expect(d[0]).toBe('domain')
    expect(normalizeCols(['issuer', 'bogus', 'issuer'])).toEqual(['domain', 'issuer', 'status'])
    expect(normalizeCols(['status', 'domain', 'team'])).toEqual(['domain', 'status', 'team'])
  })
  it('moveCol sürükle-bırak sırasını korur ama domain başa döner', () => {
    expect(moveCol(['domain', 'issuer', 'expiry', 'status'], 3, 1)).toEqual(['domain', 'status', 'issuer', 'expiry'])
    expect(moveCol(['domain', 'issuer', 'status'], 0, 2)[0]).toBe('domain')
  })
  it('CSV sütun eşlemesi her katalog sütununu kapsar (sunucu anahtarı)', () => {
    expect(csvColumnsFor(TABLE_COLUMNS.map((c) => c.key))).toHaveLength(TABLE_COLUMNS.length)
    expect(csvColumnsFor(['notBefore', 'bogus'])).toEqual(['not_before'])
  })
})

describe('certTableModel — ön ayarlar', () => {
  it('aynı ad üstüne yazar; tavan PRESET_MAX (en eski düşer); boş ad yok sayılır', () => {
    let list = []
    for (let i = 0; i < PRESET_MAX + 2; i++) list = savePreset(list, { name: `p${i}`, filters: {} })
    expect(list).toHaveLength(PRESET_MAX)
    expect(list[0].name).toBe('p2')
    list = savePreset(list, { name: 'p5', filters: { status: 'valid' } })
    expect(list.filter((p) => p.name === 'p5')).toHaveLength(1)
    expect(list.find((p) => p.name === 'p5').filters.status).toBe('valid')
    expect(savePreset(list, { name: '   ' })).toBe(list)
  })
})

describe('certTableModel — süzgeç ↔ istek ↔ URL', () => {
  it('yalnız varsayılan-dışı süzgeçler çip olur ve isteğe yazılır', () => {
    const f = { ...EMPTY_FILTERS, status: 'critical', insecure: true, team: '__none__' }
    expect(activeFilterChips(f).map((c) => c.key)).toEqual(['status', 'team', 'insecure'])
    expect(countActiveFilters(EMPTY_FILTERS)).toBe(0)
    const q = toQuery(f, { page: 2, perPage: 25, sortBy: 'team|desc' })
    expect(q).toEqual({ page: 2, per_page: 25, sort_by: 'team', sort_dir: 'desc', filter_status: 'critical', filter_team: '__none__', filter_insecure: 'true' })
  })
  it('URL eşlemesi gidiş-dönüş: c_* paramları yazılır, mount okuması aynı süzgeçleri kurar; bozuk değer yok sayılır', () => {
    const f = { ...EMPTY_FILTERS, domain: 'a.example.com', status: 'expired', window: '30', insecure: true, tier: '2', port: 'nonstd', fp: 'AB' }
    const m = toUrlMapping(f, { page: 3, perPage: 100, sortBy: 'days_remaining|asc', defaultPerPage: 50 })
    expect(m).toMatchObject({ c_q: 'a.example.com', c_st: 'expired', c_win: '30', c_sec: '1', c_tier: '2', c_port: 'nonstd', c_fp: 'AB', c_sort: 'days_remaining|asc', c_page: 3, c_ps: 100 })
    expect(m.c_iss).toBeNull(); expect(m.c_team).toBeNull()
    // Varsayılanlar URL'e yazılmaz
    const d = toUrlMapping(EMPTY_FILTERS, { page: 1, perPage: 50, sortBy: 'priority|asc', defaultPerPage: 50 })
    expect(Object.values(d).every((v) => v == null)).toBe(true)
    const read = (k) => ({ c_q: 'a.example.com', c_st: 'expired', c_win: '30', c_sec: '1', c_tier: '2', c_port: 'nonstd', c_fp: 'AB' })[k] ?? null
    expect(filtersFromUrl(read)).toEqual(f)
    const bad = (k) => ({ c_st: 'bogus', c_win: '15', c_tier: '9', c_port: 'abc', c_sec: 'yes' })[k] ?? null
    expect(filtersFromUrl(bad)).toEqual(EMPTY_FILTERS)
  })
})

describe('certTableModel — satır türevleri', () => {
  it('levelOf sunucu hükmünü okur, yoksa süreden düşer', () => {
    expect(levelOf({ alert_level: 'high', days_remaining: -1 })).toBe('high')
    expect(levelOf({ status: 'error' })).toBe('error')
    expect(levelOf({ days_remaining: -1 })).toBe('expired')
    expect(levelOf({ days_remaining: 20, warning: true })).toBe('warning')
    expect(levelOf({ days_remaining: 200 })).toBe('valid')
  })
  it('trustOf: iptal/güvensiz/zincir → bad; üçü de OK → ok; kısmen → partial; hiç yok → unknown', () => {
    expect(trustOf({ chain_status: 'VALID', trust_status: 'TRUSTED', revocation_status: 'REVOKED' })).toEqual({ tone: 'bad', issues: ['revoked'] })
    expect(trustOf({ chain_status: 'INCOMPLETE', trust_status: 'UNTRUSTED' }).issues).toEqual(['chain', 'untrusted'])
    expect(trustOf({ chain_status: 'VALID', trust_status: 'TRUSTED', revocation_status: 'VALID' }).tone).toBe('ok')
    expect(trustOf({ chain_status: 'VALID', revocation_status: 'UNKNOWN' }).tone).toBe('partial')
    expect(trustOf({}).tone).toBe('unknown')
  })
  it('lifetimePct: ömrün tüketilen yüzdesi, 0..100 kırpılır, veri yoksa null', () => {
    const now = Date.parse('2026-06-01T00:00:00Z')
    expect(lifetimePct({ not_before: '2026-01-01T00:00:00', not_after: '2027-01-01T00:00:00' }, now)).toBe(41)
    expect(lifetimePct({ not_before: '2020-01-01T00:00:00', not_after: '2021-01-01T00:00:00' }, now)).toBe(100)
    expect(lifetimePct({ not_after: '2027-01-01T00:00:00' }, now)).toBeNull()
    expect(lifetimePct({ not_before: '2027-01-01T00:00:00', not_after: '2026-01-01T00:00:00' }, now)).toBeNull()
  })
  it('isStale: sıklığın iki katı + 30 dk eşiği (varsayılan saatlik süpürme)', () => {
    const now = Date.parse('2026-09-13T12:00:00Z')
    expect(isStale({ checked_at: '2026-09-13T10:00:00' }, now)).toBe(false)          // 2 sa < 2.5 sa
    expect(isStale({ checked_at: '2026-09-13T09:00:00' }, now)).toBe(true)           // 3 sa
    expect(isStale({ checked_at: '2026-09-13T00:00:00', check_interval_hours: 12 }, now)).toBe(false)   // 12 sa < 24.5
    expect(isStale({ checked_at: '2026-09-12T00:00:00', check_interval_hours: 12 }, now)).toBe(true)
    expect(isStale({}, now)).toBe(false)
  })
  it('relTime birim/sayı; shortFp kısaltır', () => {
    const now = Date.parse('2026-09-13T12:00:00Z')
    expect(relTime('2026-09-13T11:59:30', now)).toEqual({ unit: 'sec', n: 30 })
    expect(relTime('2026-09-13T11:15:00', now)).toEqual({ unit: 'min', n: 45 })
    expect(relTime('2026-09-13T06:00:00', now)).toEqual({ unit: 'hour', n: 6 })
    expect(relTime('2026-09-01T12:00:00', now)).toEqual({ unit: 'day', n: 12 })
    expect(relTime(null, now)).toBeNull()
    expect(shortFp('AA:BB:CC:DD:EE:FF:00:11:22:33')).toBe('AABBCC…112233')
    expect(shortFp('ABC')).toBe('ABC')
  })
})

describe('certTableModel — seçim CSV', () => {
  it('görünür sütun sırasıyla başlık + satır; formül başlangıcı ve virgül kaçırılır; paylaşım sayısı haritadan', () => {
    const rows = [
      { domain: 'a.example.com', issuer_cn: 'CA, Inc', days_remaining: 5, alert_level: 'critical', san: ['a', 'b'], fingerprint: 'F1' },
      { domain: '=b.example.com', issuer: 'Plain CA', days_remaining: 90, alert_level: 'valid', san: [] },
    ]
    const csv = buildSelectionCsv(rows, ['domain', 'issuer', 'days', 'status', 'san', 'shared'], { 'a.example.com': 3 }, t)
    const lines = csv.split('\r\n')
    expect(lines[0]).toBe('tbl.colDomain,tbl.colIssuer,tbl.colDays,tbl.colStatus,tbl.colSan,tbl.colShared')
    expect(lines[1]).toBe('a.example.com,"CA, Inc",5,critical,2,3')
    expect(lines[2]).toBe("'=b.example.com,Plain CA,90,valid,0,1")   // utils/csv.js sözleşmesi: formül öneki tek tırnakla nötrlenir
  })
})
