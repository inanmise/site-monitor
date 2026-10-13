import { describe, it, expect, vi, beforeEach } from 'vitest'
import { clearSharedFetches, sharedFetch } from '../utils/sharedFetch.js'

/**
 * Paylaşılan istek (2026-10-13, Kişisel Panom renkli panelleri): aynı getiricinin eşzamanlı çağrıları tek istek; biten yanıt
 * bellekte kalmaz; Yenile (`fresh`) asılı isteğe katılmaz; çıkışta temizlenir (oturumlar arası sızmaz); hata herkese ulaşır.
 */
const deferred = () => { let resolve, reject; const p = new Promise((res, rej) => { resolve = res; reject = rej }); return { p, resolve, reject } }

beforeEach(() => clearSharedFetches())

describe('sharedFetch', () => {
  it('eşzamanlı çağrılar tek istek paylaşır; bittikten sonraki çağrı yeni istek yapar (yanıt önbelleklenmez)', async () => {
    const d = deferred()
    const f = vi.fn(() => d.p)
    const a = sharedFetch(f), b = sharedFetch(f)
    expect(f).toHaveBeenCalledTimes(1)
    d.resolve({ success: true, data: 1 })
    expect(await a).toEqual({ success: true, data: 1 })
    expect(await b).toEqual({ success: true, data: 1 })
    f.mockResolvedValueOnce({ success: true, data: 2 })
    expect(await sharedFetch(f)).toEqual({ success: true, data: 2 })
    expect(f).toHaveBeenCalledTimes(2)
  })

  it('Yenile (fresh) asılı kalmış isteğe katılmaz, yenisini başlatır', async () => {
    const hung = deferred()
    const f = vi.fn(() => hung.p)
    sharedFetch(f)
    f.mockResolvedValueOnce({ success: true, data: 'taze' })
    expect(await sharedFetch(f, { fresh: true })).toEqual({ success: true, data: 'taze' })
    expect(f).toHaveBeenCalledTimes(2)
  })

  it('çıkışta temizlenir: önceki oturumun süren isteğine yeni çağrı katılmaz', async () => {
    const hung = deferred()
    const f = vi.fn(() => hung.p)
    sharedFetch(f)
    clearSharedFetches()
    f.mockResolvedValueOnce({ success: true, data: 'yeni kullanıcı' })
    expect(await sharedFetch(f)).toEqual({ success: true, data: 'yeni kullanıcı' })
  })

  it('reddedilen ya da atan getirici her çağırana hata olarak ulaşır ve kayıt silinir', async () => {
    const f = vi.fn().mockRejectedValueOnce(new Error('ağ'))
    await expect(sharedFetch(f)).rejects.toThrow('ağ')
    const g = vi.fn(() => { throw new Error('senkron') })
    await expect(sharedFetch(g)).rejects.toThrow('senkron')
    f.mockResolvedValueOnce({ success: true })
    expect(await sharedFetch(f)).toEqual({ success: true })
  })
})
