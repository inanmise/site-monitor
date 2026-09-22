import { describe, it, expect } from 'vitest'
import {
  applyFilters, sortItems, detectOverlaps, parseCsv, mapCsv, importTemplateCsv, filtersToParams, paramsToFilters,
  hasActiveFilter, EMPTY_FILTERS, INVENTORY_COLUMNS, defaultCols, certRank, IMPORT_COLUMNS, columnFilterOptions,
  restoreCols, readCols, writeCols, LEGACY_KNOWN_COLS, colKeys, VIEW_KEY,
} from '../components/inventory/inventoryModel.js'

/** Envanter saf modeli (2026-09-12): filtre/sıralama/çakışma/CSV — React'siz. */
const rows = [
  { id: 1, domain: 'a.example.com', team_id: 5, team_name: 'Takım A', tier: 1, active: true, cert_status: 'valid', cert_days_remaining: 120, svc_mgmt_contact: 'x@example.com', netscaler: true, group_name: 'core', tags: 'pci,web', domain_expiry: '2026-10-01' },
  { id: 2, domain: 'b.example.com', team_id: 9, team_name: 'Takım B', tier: null, active: true, cert_status: 'error', cert_days_remaining: null, waf_enabled: true },
  { id: 3, domain: 'www.a.example.com', team_id: 5, team_name: 'Takım A', tier: 2, active: false, cert_status: 'warning', cert_days_remaining: 12, svc_mgmt_contact: 'x', app_dev_contact: 'y', iis_admin_contact: 'z', waf_admin_contact: 'w' },
  { id: 4, domain: '*.example.com', team_id: 5, team_name: 'Takım A', tier: 3, active: true },
  { id: 5, domain: 'old.example.com', team_id: 5, team_name: 'Takım A', tier: 1, active: false, deleted_at: '2026-09-01T00:00:00' },
]

describe('applyFilters', () => {
  it('metin araması domain/etiket/sorumlu/takım üstünde; takım/tier/bayrak/sertifika/sorumlu süzgeçleri', () => {
    expect(applyFilters(rows, { ...EMPTY_FILTERS, q: 'pci' }).map((r) => r.id)).toEqual([1])
    expect(applyFilters(rows, { ...EMPTY_FILTERS, q: 'takım b' }).map((r) => r.id)).toEqual([2])
    expect(applyFilters(rows, { ...EMPTY_FILTERS, team: '9' }).map((r) => r.id)).toEqual([2])
    expect(applyFilters(rows, { ...EMPTY_FILTERS, tier: 'none' }).map((r) => r.id)).toEqual([2])
    expect(applyFilters(rows, { ...EMPTY_FILTERS, tier: '1' }).map((r) => r.id)).toEqual([1, 5])
    expect(applyFilters(rows, { ...EMPTY_FILTERS, flags: ['netscaler'] }).map((r) => r.id)).toEqual([1])
    expect(applyFilters(rows, { ...EMPTY_FILTERS, cert: 'problem' }).map((r) => r.id)).toEqual([2, 3])
    expect(applyFilters(rows, { ...EMPTY_FILTERS, cert: 'never' }).map((r) => r.id)).toEqual([4, 5])
    expect(applyFilters(rows, { ...EMPTY_FILTERS, contacts: 'none' }).map((r) => r.id)).toEqual([2, 4, 5])
    expect(applyFilters(rows, { ...EMPTY_FILTERS, contacts: 'full' }).map((r) => r.id)).toEqual([3])
    expect(applyFilters(rows, { ...EMPTY_FILTERS, group: 'none' }).map((r) => r.id)).toEqual([2, 3, 4, 5])
    expect(applyFilters(rows, { ...EMPTY_FILTERS, domainExp: 'unknown' }).map((r) => r.id)).toEqual([2, 3, 4, 5])
  })
  it('hijyen süzgeci domain → kod kümesinden okur; süzgeç yokken hepsi geçer', () => {
    const hy = { 'b.example.com': new Set(['no_tier', 'error']) }
    expect(applyFilters(rows, { ...EMPTY_FILTERS, hygiene: 'no_tier' }, hy).map((r) => r.id)).toEqual([2])
    expect(applyFilters(rows, EMPTY_FILTERS)).toHaveLength(5)
    expect(hasActiveFilter(EMPTY_FILTERS)).toBe(false)
    expect(hasActiveFilter({ ...EMPTY_FILTERS, flags: ['waf_enabled'] })).toBe(true)
  })
  it('kolon süzgeçleri (2026-09-22): domain metni, port, kalan gün, son kontrol, tek bayrak, aralık, etiket, güncelleme, aktif', () => {
    const now = Date.now()
    const iso = (h) => new Date(now - h * 3600000).toISOString().replace(/\.\d{3}Z$/, '')
    const rs = [
      { ...rows[0], port: 8443, cert_checked_at: iso(2), check_interval_hours: 6, updated_at: iso(100) },
      { ...rows[1], port: 443, cert_checked_at: iso(200), check_interval_hours: null, updated_at: iso(2) },
      { ...rows[2], port: 443, cert_days_remaining: -3, tags: 'PCI' },
    ]
    const ids = (f) => applyFilters(rs, { ...EMPTY_FILTERS, ...f }).map((r) => r.id)
    expect(ids({ domain: 'WWW' })).toEqual([3])
    expect(ids({ port: '8443' })).toEqual([1])
    expect(ids({ days: '90' })).toEqual([])            // 120 gün > 90; null ve dolmuş dışarıda
    expect(ids({ days: '180' })).toEqual([1])
    expect(ids({ days: 'expired' })).toEqual([3])
    expect(ids({ days: 'unknown' })).toEqual([2])
    expect(ids({ checked: '24' })).toEqual([1])
    expect(ids({ checked: 'never' })).toEqual([3])
    expect(ids({ flag: 'waf_enabled' })).toEqual([2])
    expect(ids({ interval: '6' })).toEqual([1])
    expect(ids({ interval: 'global' })).toEqual([2, 3])
    expect(ids({ tag: 'pci' })).toEqual([1, 3])        // büyük/küçük harf duyarsız, tam etiket
    expect(ids({ updated: '24' })).toEqual([2])
    expect(ids({ active: 'no' })).toEqual([3])
    expect(hasActiveFilter({ ...EMPTY_FILTERS, tag: 'x' })).toBe(true)
    const p = filtersToParams({ ...EMPTY_FILTERS, domain: 'a', days: '30', tag: 'pci', active: 'yes' })
    expect(p).toMatchObject({ i_dom: 'a', i_days: '30', i_tag: 'pci', i_act: 'yes', i_port: null })
    expect(paramsToFilters((k, d) => p[k] ?? d)).toMatchObject({ domain: 'a', days: '30', tag: 'pci', active: 'yes' })
  })
  it('columnFilterOptions: satırlardan tekil port/takım/grup/aralık/etiket; etiket büyük/küçük harf birleşik, sıralı', () => {
    const o = columnFilterOptions([
      { port: 8443, team_id: 5, team_name: 'Takım A', group_name: 'core', check_interval_hours: 6, tags: 'web, PCI' },
      { port: 443, team_id: 9, team_name: 'Takım B', tags: 'pci' },
      { team_id: 5, team_name: 'Takım A', group_name: 'core', check_interval_hours: 24 },
    ])
    expect(o.ports).toEqual(['443', '8443'])
    expect(o.teams).toEqual([{ value: '5', label: 'Takım A' }, { value: '9', label: 'Takım B' }])
    expect(o.groups).toEqual(['core'])
    expect(o.intervals).toEqual(['6', '24'])
    expect(o.tags).toEqual(['PCI', 'web'])
  })
  it('URL param gidiş-dönüş kayıpsız', () => {
    const f = { ...EMPTY_FILTERS, q: 'x', team: '5', flags: ['netscaler', 'waf_enabled'], cert: 'problem' }
    const p = filtersToParams(f)
    expect(p.i_flags).toBe('netscaler,waf_enabled')
    expect(p.i_tier).toBeNull()
    expect(paramsToFilters((k, d) => p[k] ?? d)).toEqual(f)
  })
})

describe('sortItems / columns', () => {
  it('sertifika durumuna göre hata en üstte; kalan gün asc; bilinmeyen anahtar girdi sırası', () => {
    expect(sortItems(rows, 'cert|asc').map((r) => r.id)).toEqual([2, 3, 4, 5, 1])
    expect(sortItems(rows, 'days|asc').map((r) => r.id).slice(0, 2)).toEqual([3, 1])
    expect(sortItems(rows, 'nope|asc').map((r) => r.id)).toEqual([1, 2, 3, 4, 5])
    expect(certRank({ cert_status: 'error' })).toBeLessThan(certRank({ cert_status: 'valid' }))
    expect(defaultCols()).toContain('domain')
    expect(defaultCols()).not.toContain('ug_team')
    expect(new Set(INVENTORY_COLUMNS.map((c) => c.key)).size).toBe(INVENTORY_COLUMNS.length)
  })
})

describe('detectOverlaps', () => {
  it('wildcard kapsaması, www/apex çifti ve aynı host farklı port; silinmişler sayılmaz', () => {
    const o = detectOverlaps([
      ...rows,
      { id: 6, domain: 'a.example.com', port: 8443, active: true },
      { id: 7, domain: 'x.example.com', port: 443, active: true },
    ])
    expect(o.wildcard.map((x) => x.domain)).toEqual(expect.arrayContaining(['a.example.com', 'b.example.com', 'x.example.com']))
    expect(o.wildcard.map((x) => x.domain)).not.toContain('www.a.example.com')   // iki etiket derinde — wildcard kapsamaz
    expect(o.www).toEqual([{ domain: 'www.a.example.com', by: 'a.example.com' }])
    expect(o.port).toEqual([{ domain: 'a.example.com', by: 'a.example.com:443' }])
    expect(o.total).toBe(o.wildcard.length + o.www.length + o.port.length)
  })
})

describe('CSV import', () => {
  it('tırnaklı hücre, noktalı virgül ayırıcı, BOM, CRLF; snake_case ve yerel başlık eşlemesi; bilinmeyen sütun raporu', () => {
    const csv = '﻿Domain;Port;Takım;Tier;netscaler;Bilinmeyen\r\n"www.example.com";443;"Takım A";T1;evet;x\r\n"b.example.com";;"Takım ""B""";2;;y\r\n'
    const parsed = parseCsv(csv)
    expect(parsed).toHaveLength(3)
    expect(parsed[2][2]).toBe('Takım "B"')
    const m = mapCsv(parsed, { Domain: 'domain', Port: 'port', 'Takım': 'team', Tier: 'tier' })
    expect(m.rows).toEqual([
      { domain: 'www.example.com', port: '443', team: 'Takım A', tier: 'T1', netscaler: 'evet' },
      { domain: 'b.example.com', team: 'Takım "B"', tier: '2' },
    ])
    expect(m.unknown).toEqual(['Bilinmeyen'])
    expect(m.columns).toEqual(['domain', 'port', 'team', 'tier', 'netscaler'])
  })
  it('şablon başlığı sunucu sütunlarıyla birebir', () => {
    const head = importTemplateCsv().split('\r\n')[0].split(',')
    expect(head).toEqual(IMPORT_COLUMNS)
    expect(head).toContain('waf_enabled')
    expect(mapCsv([]).rows).toEqual([])
  })
})

describe('sütun tercihi × sonradan eklenen sütun (2026-09-22 QA)', () => {
  it('kullanıcının hiç görmediği yeni varsayılan sütun katalog sırasındaki yerine eklenir', () => {
    // colsKnown YOK → görünüm "platform" katalogda yokken yazılmış sayılır
    const saved = ['domain', 'port', 'team', 'tags', 'active']
    const out = restoreCols(saved, undefined)
    expect(out).toContain('platform')
    expect(out.indexOf('platform')).toBe(out.indexOf('tags') + 1)   // katalogda tags'ten hemen sonra
    expect(out.filter((k) => k !== 'platform')).toEqual(saved)      // kalanların sırası bozulmaz
  })
  it('kullanıcının BİLEREK kapattığı sütun geri gelmez; boş liste varsayılana düşer', () => {
    expect(restoreCols(['domain', 'port', 'active'], colKeys())).toEqual(['domain', 'port', 'active'])
    expect(restoreCols([], colKeys())).toEqual(defaultCols())
    expect(restoreCols(null)).toEqual(defaultCols())
  })
  it('LEGACY_KNOWN_COLS dondurulmuş: yeni sütunları İÇERMEZ, eskilerin hepsini içerir', () => {
    expect(LEGACY_KNOWN_COLS).not.toContain('platform')
    const legacy = new Set(LEGACY_KNOWN_COLS)
    for (const k of colKeys()) if (k !== 'platform') expect(legacy.has(k)).toBe(true)
  })
  it('writeCols o anki kataloğu da saklar → aynı sütun ikinci kez eklenmez', () => {
    localStorage.setItem(VIEW_KEY, JSON.stringify({ cols: ['domain', 'port', 'active'] }))
    expect(readCols()).toContain('platform')          // eski kayıt: yeni sütun gelir
    writeCols(['domain', 'port', 'active'])           // kullanıcı onu kapatıyor
    expect(readCols()).toEqual(['domain', 'port', 'active'])
    localStorage.clear()
  })
})
