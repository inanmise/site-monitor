import { describe, it, expect } from 'vitest'
import {
  applyFilters, sortItems, detectOverlaps, parseCsv, mapCsv, importTemplateCsv, filtersToParams, paramsToFilters,
  hasActiveFilter, EMPTY_FILTERS, INVENTORY_COLUMNS, defaultCols, certRank, IMPORT_COLUMNS,
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
