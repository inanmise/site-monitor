import { describe, it, expect } from 'vitest'
import { matchesProxy, monitorUrlState } from '../utils/monitorFilters.js'
import { applyFilters, EMPTY_FILTERS, filtersToParams, paramsToFilters, hasActiveFilter } from '../components/inventory/inventoryModel.js'

/** Vekil süzgeçleri (2026-09-22): izleme sayfaları `proxy_effective` (gerçek yol) ile, envanter `use_proxy` bayrağıyla süzer. */
describe('matchesProxy — izleme sayfaları', () => {
  const viaProxy = { proxy_effective: 'proxy' }
  const direct = { proxy_effective: 'direct' }
  const legacy = {}   // alan yok (eski satır / tel biçimi eksik) → doğrudan sayılır

  it("'all' herkesi geçirir", () => {
    expect([viaProxy, direct, legacy].every(m => matchesProxy(m, 'all'))).toBe(true)
    expect(matchesProxy(viaProxy, undefined)).toBe(true)
  })

  it("'proxy' yalnız gerçekte vekilden çıkanlar; 'direct' geri kalanı (alan yoksa doğrudan)", () => {
    expect(matchesProxy(viaProxy, 'proxy')).toBe(true)
    expect(matchesProxy(direct, 'proxy')).toBe(false)
    expect(matchesProxy(legacy, 'proxy')).toBe(false)
    expect(matchesProxy(direct, 'direct')).toBe(true)
    expect(matchesProxy(legacy, 'direct')).toBe(true)
    expect(matchesProxy(viaProxy, 'direct')).toBe(false)
  })

  it('URL durumu: all → param yok; proxy/direct → via', () => {
    const base = { teamFilter: 'all', groupFilter: 'all', tagFilter: 'all', search: '', statFilter: null, pager: { page: 1, pageSize: 50 } }
    expect(monitorUrlState({ ...base, proxyFilter: 'all' }).via).toBeNull()
    expect(monitorUrlState({ ...base, proxyFilter: 'proxy' }).via).toBe('proxy')
    expect(monitorUrlState(base).via).toBeNull()   // parametre verilmezse eski çağıranlar kırılmaz
  })
})

describe('envanter vekil süzgeci', () => {
  const rows = [
    { id: 1, domain: 'a.example.com', use_proxy: true },
    { id: 2, domain: 'b.example.com', use_proxy: false },
    { id: 3, domain: 'c.example.com' },   // eski satır: null = Hayır
  ]

  it("'' süzmez; 'on' yalnız Evet; 'off' Hayır + null", () => {
    expect(applyFilters(rows, { ...EMPTY_FILTERS }).map(r => r.id)).toEqual([1, 2, 3])
    expect(applyFilters(rows, { ...EMPTY_FILTERS, proxy: 'on' }).map(r => r.id)).toEqual([1])
    expect(applyFilters(rows, { ...EMPTY_FILTERS, proxy: 'off' }).map(r => r.id)).toEqual([2, 3])
  })

  it('URL gidiş-dönüş (i_proxy) ve aktif-süzgeç sayımı', () => {
    const f = { ...EMPTY_FILTERS, proxy: 'on' }
    const params = filtersToParams(f)
    expect(params.i_proxy).toBe('on')
    expect(filtersToParams(EMPTY_FILTERS).i_proxy).toBeNull()
    const back = paramsToFilters((k, d) => (k === 'i_proxy' ? 'on' : d))
    expect(back.proxy).toBe('on')
    expect(hasActiveFilter(f)).toBe(true)
    expect(hasActiveFilter(EMPTY_FILTERS)).toBe(false)
  })
})
