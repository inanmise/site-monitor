import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { api } from '../api/client.js'
import { NOC_COVERAGE_EVENT, isNocCoverageWrite } from '../utils/nocCoverageEvent.js'
import { loadNocFormOptions, resetNocFormOptionsCache } from '../components/noc/forms/useNocFormOptions.js'

/**
 * 7/24 kapsamı değişti olayı (2026-09-27): önbellekli yüzeyler (Pano şeridi, izleme formu grup seçenekleri) kapsamı
 * değiştiren BAŞARILI bir yazmadan sonra tazelenir. Olay tek yerden — `request()` — yayılır: yeni bir form/uç eklendiğinde
 * çağrı yeri hatırlanmak zorunda değil. Ayrıca `withStatus` (7/24 arama listesi 403 → salt okunur) isteğe bağlı kalır.
 */
function mockFetch(status = 200, body = { success: true, data: {} }) {
  global.fetch = vi.fn().mockImplementation(async () => ({
    ok: status >= 200 && status < 300, status,
    headers: { get: () => 'application/json' },
    json: async () => (typeof body === 'function' ? body() : body),
    text: async () => JSON.stringify(body),
  }))
  return global.fetch
}

describe('isNocCoverageWrite', () => {
  it('7/24 aç/kapa + toplu + grup/yapılandırma yazmaları; izleme/envanter gövdesinde 7/24 anahtarı', () => {
    expect(isNocCoverageWrite('/noc/monitors/PING/4', { method: 'PUT' })).toBe(true)
    expect(isNocCoverageWrite('/noc/monitors/bulk', { method: 'POST' })).toBe(true)
    expect(isNocCoverageWrite('/admin/noc/groups', { method: 'POST' })).toBe(true)
    expect(isNocCoverageWrite('/admin/noc/groups/3', { method: 'DELETE' })).toBe(true)
    expect(isNocCoverageWrite('/admin/noc/config', { method: 'PUT' })).toBe(true)
    expect(isNocCoverageWrite('/monitoring/http/1', { method: 'PUT', body: JSON.stringify({ name: 'a', nocNotify: true }) })).toBe(true)
    expect(isNocCoverageWrite('/admin/inventory/4', { method: 'PUT', body: JSON.stringify({ noc_notify: false }) })).toBe(true)
  })

  it('okuma, test e-postası, arama kaydı/arama listesi ve 7/24 anahtarı olmayan yazma DEĞİL', () => {
    expect(isNocCoverageWrite('/noc/coverage', {})).toBe(false)
    expect(isNocCoverageWrite('/noc/monitors/PING/4', { method: 'GET' })).toBe(false)
    expect(isNocCoverageWrite('/admin/noc/groups/3/test', { method: 'POST' })).toBe(false)
    expect(isNocCoverageWrite('/alerts/5/noc-calls', { method: 'POST', body: '{"outcome":"REACHED"}' })).toBe(false)
    expect(isNocCoverageWrite('/noc/teams/1/call-list', { method: 'PUT', body: '{"userIds":[1]}' })).toBe(false)
    expect(isNocCoverageWrite('/monitoring/http/1', { method: 'PUT', body: '{"name":"nocNotify değil"}' })).toBe(false)
  })
})

describe('request() → olay + withStatus', () => {
  const originalFetch = global.fetch
  let heard
  const on = () => { heard++ }
  beforeEach(() => { heard = 0; window.addEventListener(NOC_COVERAGE_EVENT, on) })
  afterEach(() => { global.fetch = originalFetch; window.removeEventListener(NOC_COVERAGE_EVENT, on) })

  it('başarılı kapsam yazması olayı yayar; başarısız yanıt ve okuma yaymaz', async () => {
    mockFetch()
    await api.noc.setMonitor('PING', 4, { enabled: true })
    expect(heard).toBe(1)
    await api.monitoring.updateHttpMonitor(1, { name: 'Example', nocNotify: true, nocGroupIds: null })
    expect(heard).toBe(2)
    await api.admin.noc.createGroup({ name: 'NOC', emails: ['noc@example.com'] })
    expect(heard).toBe(3)
    await api.noc.coverage()
    await api.admin.noc.testGroup(3)
    await api.nocCalls.create(5, { outcome: 'REACHED' })
    expect(heard).toBe(3)
    mockFetch(200, { success: false, error: 'x' })
    await api.noc.bulk([{ type: 'PING', id: 1 }], true)
    mockFetch(403, { success: false, error: 'yetki yok' })
    await api.noc.setMonitor('PING', 4, { enabled: true })
    expect(heard).toBe(3)
  })

  it('withStatus YALNIZ arama listesinde: 403 gövdesine durum eklenir; diğer uçların hata gövdesi değişmez', async () => {
    mockFetch(403, () => ({ success: false, error: 'Arama listesini yalnız takımın yöneticisi/müdürü düzenleyebilir' }))
    const saved = await api.noc.saveCallList(1, [2])
    expect(saved).toMatchObject({ success: false, status: 403 })
    const read = await api.noc.getCallList(1)
    expect(read).toMatchObject({ success: false, status: 403 })
    const other = await api.noc.coverage()
    expect(other).not.toHaveProperty('status')
  })
})

describe('form seçenekleri önbelleği olayla düşer (7/24 Ayarlar yazdı → formlar 60 sn beklemez)', () => {
  const originalFetch = global.fetch
  afterEach(() => { global.fetch = originalFetch; resetNocFormOptionsCache() })

  it('ikinci okuma önbellekten; olaydan sonra yeniden istenir', async () => {
    resetNocFormOptionsCache()
    const f = mockFetch(200, { success: true, data: { groups: [], disabled_types: [] } })
    await loadNocFormOptions()
    await loadNocFormOptions()
    const optionCalls = () => f.mock.calls.filter(([url]) => String(url).includes('/noc/groups/options')).length
    expect(optionCalls()).toBe(1)
    window.dispatchEvent(new CustomEvent(NOC_COVERAGE_EVENT))
    await loadNocFormOptions()
    expect(optionCalls()).toBe(2)
  })
})
