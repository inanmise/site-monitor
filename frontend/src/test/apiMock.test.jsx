import { describe, it, expect, vi } from 'vitest'
import { withApiFallback } from './apiMock.js'

/**
 * Yedek mock katmanının sözleşmesi. Bu katman, 2026-08-19'da CI'ı kırmızıya çeviren
 * "elle sayılan mock listesi eksik kaldı" sürüklenmesini kapatmak için var — o yüzden
 * davranışı burada açıkça pinleniyor.
 */
describe('withApiFallback', () => {
  it('elle tanımlanmış uçları DEĞİŞTİRMEZ', async () => {
    const own = vi.fn(() => Promise.resolve({ success: true, data: ['x'] }))
    const api = withApiFallback({ monitoring: { getX: own } })

    const res = await api.monitoring.getX()

    expect(api.monitoring.getX).toBe(own)
    expect(res.data).toEqual(['x'])
  })

  it('bilinmeyen ucu otomatik üretir ve başarılı boş yanıt döndürür', async () => {
    const api = withApiFallback({ admin: {} })

    const res = await api.admin.hicTanimlanmamisUc()

    expect(res).toEqual({ success: true, data: [] })
  })

  it('otomatik üretilen uç KARARLIDIR — çağrı iddiaları ve mockResolvedValue çalışır', async () => {
    const api = withApiFallback({ admin: {} })

    // Aynı örnek dönmeli, yoksa toHaveBeenCalled hep boş kalırdı.
    expect(api.admin.getLoginSeries).toBe(api.admin.getLoginSeries)

    api.admin.getLoginSeries.mockResolvedValue({ success: true, data: [1, 2] })
    const res = await api.admin.getLoginSeries('a', 'b')

    expect(api.admin.getLoginSeries).toHaveBeenCalledWith('a', 'b')
    expect(res.data).toEqual([1, 2])
  })

  it('hiç tanımlanmamış ad alanını da üretir (api.yeniAlan.metot)', async () => {
    const api = withApiFallback({})
    await expect(api.yeniAlan.metot()).resolves.toEqual({ success: true, data: [] })
  })

  it('thenable sanılmaz — await edilince sonsuz zincire girmez', async () => {
    const api = withApiFallback({ admin: {} })
    expect(api.then).toBeUndefined()
    expect(api.admin.then).toBeUndefined()
    await expect(Promise.resolve(api.admin)).resolves.toBeDefined()
  })

  it('gerçek olayı yeniden üretir: eksik getLoginSeries artık PATLAMAZ', async () => {
    // Eskiden: `api.admin.getLoginSeries is not a function` → efekt içinde reddedilen
    // promise → CI'da "1 unhandled rejection" (testlerin hepsi geçtiği hâlde).
    const api = withApiFallback({
      admin: { getSystemHealth: vi.fn(() => Promise.resolve({ success: true, data: {} })) },
    })
    await expect(api.admin.getLoginSeries('a', 'b', 'c')).resolves.toBeDefined()
  })
})
