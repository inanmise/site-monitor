import { describe, it, expect, vi } from 'vitest'
import { domainDaysUntil, liveDays, liveRow, withLiveDays } from '../components/domain/domainCardModel.js'
import { javaStringTable, readJavaTest } from './helpers/javaTable.js'
import {
  DEFAULT_DOMAIN_FILTERS, NO_REGISTRAR, domainFacets, expiryKind, hasDomainFilters, matchesDomainFilters,
  domainFilterParam, normalizeDomainFilters, registrarKey, sourceKind,
} from '../components/domain/domainFilterModel.js'
import { domainExpiring, domainRowState } from '../components/mydash/domainExpiringModel.js'
import { bulkPlanPreview, DOMAIN_PLAN_BULK_MAX } from '../components/domain/DomainBulkPlanDialog.jsx'

/** Sabit "şimdi": 2026-11-15 12:00 İstanbul (09:00 UTC) — kalan gün İstanbul takvimiyle sayılır, tarayıcı diliminden bağımsız. */
const NOW = Date.UTC(2026, 10, 15, 9, 0, 0)

describe('LOW 2 — domainDaysUntil = DomainCheckerService.daysUntil (DomainDaysUntilTableTest.DAYS_TABLE)', () => {
  const rows = javaStringTable(readJavaTest('service/DomainDaysUntilTableTest.java'), 'DAYS_TABLE')

  it('tablo okundu ve her satır sunucuyla aynı', () => {
    expect(rows.length).toBeGreaterThan(40)
    for (const [iso, now, expected] of rows) {
      expect(domainDaysUntil(iso, Date.parse(now)), `domainDaysUntil(${iso}, ${now})`)
        .toBe(expected == null ? null : Number(expected))
    }
  })

  it('tarayıcı saat dilimi sonucu değiştirmez (gece yarısına yakın; New York / Tokyo / UTC)', () => {
    const prev = process.env.TZ
    try {
      for (const tz of ['America/New_York', 'Asia/Tokyo', 'UTC', 'Europe/Istanbul']) {
        process.env.TZ = tz
        // İstanbul 2 Ekim 00:30 = 1 Ekim 21:30 UTC: bitişi 2 Ekim olan alan adı BUGÜN biter (0) — UTC/New York tarayıcısı
        // yerel takvimle 1 derdi; ofsetsiz damga da İstanbul günü sayılır (UTC sayılsaydı 1).
        expect(domainDaysUntil('2026-10-02', Date.parse('2026-10-01T21:30:00Z')), tz).toBe(0)
        expect(domainDaysUntil('2026-10-02T23:30:00', Date.parse('2026-10-01T21:30:00Z')), tz).toBe(0)
        expect(domainDaysUntil('2026-10-02', Date.parse('2026-10-01T20:59:00Z')), tz).toBe(1)
        expect(liveDays({ expiry_date: '2026-10-02', days_remaining: 5 }, Date.parse('2026-10-01T21:30:00Z')), tz).toBe(0)
      }
    } finally {
      if (prev === undefined) delete process.env.TZ
      else process.env.TZ = prev
    }
  })
})

describe('A1 — kalan gün bitiş tarihinden CANLI', () => {
  it('yalnız tarih (WHOIS): yerel takvim günü farkı; kayıtlı (bayat) değer yok sayılır', () => {
    expect(liveDays({ expiry_date: '2026-11-20', days_remaining: 9 }, NOW)).toBe(5)
    expect(liveDays({ expiry_date: '2026-11-15', days_remaining: 1 }, NOW)).toBe(0)
    expect(liveDays({ expiry_date: '2026-11-12', days_remaining: 0 }, NOW)).toBe(-3)
  })
  it('zaman damgası (RDAP): tam gün (floor), sunucunun kuralı', () => {
    const at = new Date(NOW + 2.5 * 86_400_000).toISOString()
    expect(liveDays({ expiry_date: at, days_remaining: 99 }, NOW)).toBe(2)
  })
  it('tarih yok / okunamıyor → kayıtlı değer (yedek); o da yoksa null', () => {
    expect(liveDays({ expiry_date: null, days_remaining: 12 }, NOW)).toBe(12)
    expect(liveDays({ expiry_date: 'bozuk', days_remaining: '7' }, NOW)).toBe(7)
    expect(liveDays({ expiry_date: '2026-13-40', days_remaining: null }, NOW)).toBeNull()
    expect(liveDays({}, NOW)).toBeNull()
  })
  it('liveRow değişmeyen satırda AYNI nesne, değişende kopya + kayıtlı değer; withLiveDays değişiklik yoksa AYNI dizi', () => {
    const same = { id: 1, expiry_date: '2026-11-20', days_remaining: 5 }
    expect(liveRow(same, NOW)).toBe(same)
    const stale = { id: 2, expiry_date: '2026-11-20', days_remaining: 6 }
    const r = liveRow(stale, NOW)
    expect(r).not.toBe(stale)
    expect(r).toMatchObject({ days_remaining: 5, days_remaining_stored: 6 })
    const list = [same]
    expect(withLiveDays(list, NOW)).toBe(list)
    expect(withLiveDays([same, stale], NOW)[1].days_remaining).toBe(5)
  })
})

describe('C1 — alan adı ek süzgeçleri', () => {
  const rows = [
    { id: 1, registrar: 'GoDaddy.com, LLC', source: 'RDAP', expiry_date: '2026-11-10' },
    { id: 2, registrar: 'GODADDY.COM, LLC', source: 'RDAP_REGISTRY', expiry_date: '2027-01-01' },
    { id: 3, registrar: 'İsimtescil', source: 'WHOIS', expiry_date: null, days_remaining: null },
    { id: 4, registrar: '', source: 'NONE', expiry_date: '2026-12-01' },
  ]
  it('registrar Türkçe katlanır (büyük/küçük, İ); boş → bilinmiyor anahtarı', () => {
    expect(registrarKey(rows[0])).toBe(registrarKey(rows[1]))
    expect(registrarKey({ registrar: 'İSİMTESCİL' })).toBe(registrarKey(rows[2]))
    expect(registrarKey(rows[3])).toBe(NO_REGISTRAR)
  })
  it('kaynak: rdap ailesi / whois / bilinmiyor (NONE dahil); süre durumu canlı günden', () => {
    expect(rows.map(sourceKind)).toEqual(['rdap', 'rdap', 'whois', 'unknown'])
    expect(rows.map((m) => expiryKind(m, NOW))).toEqual(['expired', 'valid', 'unknown', 'valid'])
  })
  it('facet sayımları dürüst: her seçenek öteki ek süzgeçler uygulanmış listeden; bilinmeyen ayrı sayılır', () => {
    const f = { ...DEFAULT_DOMAIN_FILTERS, source: ['rdap'] }
    const facets = domainFacets(rows, f, NOW)
    expect(facets.source).toEqual({ all: 4, rdap: 2, whois: 1, unknown: 1 })   // kendi süzgeci sayıma girmez
    expect(facets.expiry).toEqual({ all: 2, expired: 1, valid: 1, unknown: 0 })   // kaynak = rdap uygulanmış
    expect(facets.registrarAll).toBe(2)
    expect(facets.registrars).toHaveLength(1)
    expect(facets.registrars[0].count).toBe(2)
    const all = domainFacets(rows, DEFAULT_DOMAIN_FILTERS, NOW)
    expect(all.registrars.at(-1).key).toBe(NO_REGISTRAR)   // bilinmeyen en sonda
  })
  it('seçili registrar listede yoksa 0 sayımla görünür kalır; normalize bilinmeyen değeri düşürür, eski tek değerli bağlantıyı okur', () => {
    const f = normalizeDomainFilters({ registrar: 'yok kuruluş', source: 'ftp', expiry: 'expired' })
    expect(f).toEqual({ registrar: ['yok kuruluş'], source: [], expiry: ['expired'] })
    expect(domainFacets(rows, f, NOW).registrars.find((r) => r.key === 'yok kuruluş').count).toBe(0)
    expect(hasDomainFilters(DEFAULT_DOMAIN_FILTERS)).toBe(false)
    expect(hasDomainFilters(f)).toBe(true)
    expect(rows.filter((m) => matchesDomainFilters(m, { registrar: [], source: [], expiry: ['unknown'] }, null, NOW)).map((m) => m.id)).toEqual([3])
    // eski bağlantı: "all" = seçim yok; virgüllü registrar adı TEK değer (ayraç `|`)
    expect(normalizeDomainFilters({ registrar: 'all', source: 'all', expiry: 'all' })).toEqual({ registrar: [], source: [], expiry: [] })
    expect(normalizeDomainFilters({ registrar: registrarKey(rows[0]) }).registrar).toEqual([registrarKey(rows[0])])
  })

  it('çoklu seçim (2026-11-19): bir süzgeçte birden çok değer VEYA; süzgeçler arası VE; URL `|` ile, tekrar yok, sınırlı', () => {
    const f = normalizeDomainFilters({ source: 'rdap|whois|rdap|ftp', expiry: ['valid', 'unknown'] })
    expect(f.source).toEqual(['rdap', 'whois'])
    expect(rows.filter((m) => matchesDomainFilters(m, f, null, NOW)).map((m) => m.id)).toEqual([2, 3])
    expect(domainFilterParam(f.source)).toBe('rdap|whois')
    expect(domainFilterParam([])).toBeNull()
    expect(normalizeDomainFilters({ registrar: Array.from({ length: 80 }, (_, i) => 'r' + i) }).registrar).toHaveLength(50)
    const reg = normalizeDomainFilters({ registrar: [registrarKey(rows[0]), NO_REGISTRAR] })
    expect(rows.filter((m) => matchesDomainFilters(m, reg, null, NOW)).map((m) => m.id)).toEqual([1, 2, 4])
    // facet: seçili iki registrar, biri listede yok → 0 sayımla görünür
    const missing = normalizeDomainFilters({ registrar: ['yok 1', 'yok 2'] })
    expect(domainFacets(rows, missing, NOW).registrars.filter((r) => r.key.startsWith('yok')).map((r) => r.count)).toEqual([0, 0])
  })
})

describe('C2 — Yakında bitecek alan adları kutusu', () => {
  it('satır durumu izlemenin kendi eşiklerinden; eşik yoksa 7 / 30; dolmuş < 0', () => {
    expect(domainRowState(null, 30, 7)).toBe('unknown')
    expect(domainRowState(-1, 30, 7)).toBe('expired')
    expect(domainRowState(7, 30, 7)).toBe('critical')
    expect(domainRowState(8, 30, 7)).toBe('warning')
    expect(domainRowState(31, 30, 7)).toBe('ok')
    expect(domainRowState(40, 30, 45)).toBe('critical')   // kritik > uyarı: sunucu max(uyarı, kritik)
    expect(domainRowState(25, null, null)).toBe('warning')
  })
  it('en az gün önce; yeşil yalnız HEPSİNİN bitişi biliniyor ve eşikte değilken', () => {
    const ok = domainExpiring([{ id: 1, domain: 'a.com', expiry_date: '2027-06-01' }, { id: 2, domain: 'b.com', expiry_date: '2027-05-01' }], 5, NOW)
    expect(ok.tone).toBe('success')
    expect(ok.rows.map((r) => r.domain)).toEqual(['b.com', 'a.com'])
    const withUnknown = domainExpiring([{ id: 1, domain: 'a.com', expiry_date: '2027-06-01' }, { id: 2, domain: 'x.com' }], 5, NOW)
    expect(withUnknown.tone).toBe('muted')
    expect(withUnknown.unknown).toBe(1)
    const bad = domainExpiring([
      { id: 1, domain: 'late.com', expiry_date: '2026-11-10' },
      { id: 2, domain: 'soon.com', expiry_date: '2026-11-25' },
      { id: 3, domain: 'paused.com', expiry_date: '2026-11-01', active: false },
    ], 1, NOW)
    expect(bad).toMatchObject({ tone: 'danger', expired: 1, warning: 1, active: 2, more: 1 })
    expect(bad.rows[0]).toMatchObject({ domain: 'late.com', days: -5, state: 'expired' })
    expect(domainExpiring([], 5, NOW).tone).toBe('muted')
    expect(domainExpiring({ visible: false }, 5, NOW)).toMatchObject({ hidden: true, tone: 'muted' })
  })
})

describe('D1 — toplu yenileme planı önizlemesi', () => {
  const rows = [
    { id: 1, domain: 'a.com', expiry_date: '2027-01-01' },
    { id: 2, domain: 'b.com', expiry_date: null },
    { id: 3, domain: 'c.com', expiry_date: '2027-01-01', renewal_planned_at: '2026-12-20', renewal_planned_note: 'kart' },
  ]
  it('set: bitişi bilinmeyen no_expiry, aynı tarih + not unchanged; clear: planı olmayan unchanged', () => {
    const set = bulkPlanPreview(rows, 'set', '2026-12-20', ' kart ')
    expect(set.rows.map((r) => [r.id, r.state, r.reason || null])).toEqual([[1, 'change', null], [2, 'skip', 'no_expiry'], [3, 'skip', 'unchanged']])
    expect(set).toMatchObject({ change: 1, skip: 2, check: 0 })
    const clear = bulkPlanPreview(rows, 'clear')
    expect(clear.rows.map((r) => r.state)).toEqual(['skip', 'skip', 'change'])
  })
  it('LOW 4: en son kontrol kaynaksızsa (NONE) daha eski kontrolün bitişi olabilir → "sunucu denetleyecek", atlandı sayılmaz', () => {
    const p = bulkPlanPreview([
      { id: 4, domain: 'd.com', expiry_date: null, source: 'NONE' },
      { id: 5, domain: 'e.com', expiry_date: null, source: 'RDAP' },
      { id: 6, domain: 'f.com', expiry_date: null, source: null },
      { id: 7, domain: 'g.com', expiry_date: null, source: 'NONE', renewal_planned_at: '2026-12-20', renewal_planned_note: null },
    ], 'set', '2026-12-20')
    expect(p.rows.map((r) => [r.id, r.state, r.reason || null])).toEqual([[4, 'check', null], [5, 'skip', 'no_expiry'], [6, 'skip', 'no_expiry'], [7, 'check', null]])
    expect(p).toMatchObject({ change: 0, skip: 2, check: 2 })
  })
  it('sunucu tavanı kadar satır gönderilir', () => {
    const many = Array.from({ length: DOMAIN_PLAN_BULK_MAX + 5 }, (_, i) => ({ id: i + 1, domain: `d${i}.com`, expiry_date: '2027-01-01' }))
    expect(bulkPlanPreview(many, 'set', '2027-01-01').rows).toHaveLength(DOMAIN_PLAN_BULK_MAX)
  })
})

describe('C3 — takvim dışa aktarımı', () => {
  it('downloadBlob false → ok false (çağıran hata der); bitişi bilinen yoksa indirme yok', async () => {
    vi.resetModules()
    const dl = vi.fn(() => false)
    vi.doMock('../utils/csvExport.js', () => ({ downloadBlob: dl }))
    const { exportDomainsIcs } = await import('../utils/exportDomainsIcs.js')
    const t = (k, ...a) => `${k}|${a.join('|')}`
    expect(exportDomainsIcs([{ id: 1, domain: 'a.com', expiry_date: '2027-01-01' }], t)).toEqual({ ok: false, count: 1, skipped: 0 })
    expect(dl).toHaveBeenCalledTimes(1)
    expect(exportDomainsIcs([{ id: 2, domain: 'b.com' }], t)).toEqual({ ok: false, count: 0, skipped: 1 })
    expect(dl).toHaveBeenCalledTimes(1)
    vi.doUnmock('../utils/csvExport.js')
  })
})
