import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

// E-posta önizlemesi isteğinin paylaşımı (inceleme 2026-10-18, 3. tur): sunucu kişi başına aynı anda TEK önizleme hazırlar
// (ikinci istek 429 RATE_LIMITED). Aynı kullanıcı × rapor için süren istek varsa yenisi ATILMAZ; çağıranın iptali yalnız
// kendi sonucunu bırakır; son çağıran ayrılınca ağ isteği MAIL_PREVIEW_KEEP_MS sonra kesilir (bu arada yeniden açılış
// katılır); başka kullanıcı öncekinin isteğine katılmaz; biten yanıt tutulmaz.
vi.mock('../api/client', () => ({ api: { techErrors: { mailPreview: vi.fn() } } }))
import { api } from '../api/client'
import {
  MAIL_PREVIEW_KEEP_MS, clearMailPreviewShares, fetchMailPreview, mailPreviewShareCount,
} from '../components/techerror/mailPreviewFetch.js'
import { OWNER_KEY } from '../utils/personalStorage.js'

const mp = () => api.techErrors.mailPreview

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

beforeEach(() => {
  vi.clearAllMocks()
  clearMailPreviewShares()
  try { localStorage.clear() } catch { /* yok */ }
})
afterEach(() => {
  vi.useRealTimers()
  clearMailPreviewShares()
})

describe('e-posta önizlemesi isteğinin paylaşımı', () => {
  it('süren istek varken ikinci çağıran YENİ istek atmaz, aynı yanıtı alır; biten yanıt tutulmaz (sonraki açılış taze okur)', async () => {
    const d = deferred()
    mp().mockReturnValueOnce(d.promise).mockResolvedValueOnce({ success: true, data: { subject: 'ikinci' } })
    const a = fetchMailPreview('100')
    const b = fetchMailPreview('100')
    expect(mp()).toHaveBeenCalledTimes(1)
    expect(mp().mock.calls[0][0]).toBe('100')
    expect(mp().mock.calls[0][1].signal).toBeInstanceOf(AbortSignal)
    d.resolve({ success: true, data: { subject: 'ilk' } })
    expect(await a).toEqual({ success: true, data: { subject: 'ilk' } })
    expect(await b).toEqual({ success: true, data: { subject: 'ilk' } })
    expect(mailPreviewShareCount()).toBe(0)
    expect(await fetchMailPreview('100')).toEqual({ success: true, data: { subject: 'ikinci' } })
    expect(mp()).toHaveBeenCalledTimes(2)
  })

  it('başka rapor ayrı istektir', async () => {
    mp().mockImplementation((id) => Promise.resolve({ success: true, data: { id } }))
    const [a, b] = await Promise.all([fetchMailPreview('100'), fetchMailPreview('101')])
    expect(a.data.id).toBe('100')
    expect(b.data.id).toBe('101')
    expect(mp()).toHaveBeenCalledTimes(2)
  })

  it('StrictMode deseni: ilk çağıran iptal eder (sonucu { aborted } — yok sayılır), hemen gelen ikinci çağıran AYNI isteğe katılır; ağ isteği kesilmez', async () => {
    const d = deferred()
    mp().mockReturnValueOnce(d.promise)
    const c1 = new AbortController()
    const first = fetchMailPreview('100', { signal: c1.signal })
    c1.abort()
    expect(await first).toMatchObject({ success: false, aborted: true })
    const second = fetchMailPreview('100', { signal: new AbortController().signal })
    expect(mp()).toHaveBeenCalledTimes(1)
    const net = mp().mock.calls[0][1].signal
    expect(net.aborted).toBe(false)
    d.resolve({ success: true, data: { subject: 'tek' } })
    expect(await second).toEqual({ success: true, data: { subject: 'tek' } })
    expect(net.aborted).toBe(false)
  })

  it('son çağıran ayrılınca ağ isteği MAIL_PREVIEW_KEEP_MS sonra kesilir; bu sürede yeniden açılış katılır, sonra açılış yeni istek atar', async () => {
    vi.useFakeTimers()
    mp().mockImplementation(() => new Promise(() => {}))      // sunucu uzun hazırlıyor
    const c1 = new AbortController()
    fetchMailPreview('100', { signal: c1.signal })
    const net = mp().mock.calls[0][1].signal
    c1.abort()
    await vi.advanceTimersByTimeAsync(MAIL_PREVIEW_KEEP_MS - 100)
    expect(net.aborted).toBe(false)
    // Kapatılıp hemen yeniden açıldı: katılır, kesme iptal edilir
    const c2 = new AbortController()
    fetchMailPreview('100', { signal: c2.signal })
    expect(mp()).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(MAIL_PREVIEW_KEEP_MS * 2)
    expect(net.aborted).toBe(false)
    // Bu da ayrılınca süre dolar → ağ isteği kesilir, paylaşım unutulur; sonraki açılış yeni istek atar
    c2.abort()
    await vi.advanceTimersByTimeAsync(MAIL_PREVIEW_KEEP_MS)
    expect(net.aborted).toBe(true)
    expect(mailPreviewShareCount()).toBe(0)
    fetchMailPreview('100')
    expect(mp()).toHaveBeenCalledTimes(2)
    expect(MAIL_PREVIEW_KEEP_MS).toBeGreaterThan(0)
    expect(MAIL_PREVIEW_KEEP_MS).toBeLessThanOrEqual(10_000)
  })

  it('baştan iptal edilmiş sinyal istek atmaz; hata da paylaşılır ve paylaşım unutulur', async () => {
    const c = new AbortController()
    c.abort()
    expect(await fetchMailPreview('100', { signal: c.signal })).toMatchObject({ aborted: true })
    expect(mp()).not.toHaveBeenCalled()
    const boom = new Error('kopuk')
    mp().mockRejectedValueOnce(boom)
    const a = fetchMailPreview('100')
    const b = fetchMailPreview('100')
    await expect(a).rejects.toBe(boom)
    await expect(b).rejects.toBe(boom)
    expect(mailPreviewShareCount()).toBe(0)
  })

  it('anahtar kullanıcıyı taşır: çıkıştan sonra giren başka kullanıcı öncekinin süren isteğine KATILMAZ', async () => {
    mp().mockImplementation(() => new Promise(() => {}))
    localStorage.setItem(OWNER_KEY, 'ayse')
    fetchMailPreview('100')
    localStorage.setItem(OWNER_KEY, 'ali')
    fetchMailPreview('100')
    expect(mp()).toHaveBeenCalledTimes(2)
  })
})
