import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { api } from '../api/client.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { clearErrorRegistry, networkMessage } from '../utils/errorMessages.js'

/**
 * Kanal teknik hata raporu PDF istemcisi (inceleme 2026-10-18):
 *  - "PDF indir" `utils/csvExport.downloadBlob` ile iner — bağlantı tıklanırken DOM'dadır ve adres hemen İPTAL EDİLMEZ
 *    (60 sn). DOM dışı bağlantı / tıklamanın hemen ardından `revokeObjectURL` Safari / iPad'de indirmeyi başlatmıyordu,
 *    başarı bildirimi yine görünürdü.
 *  - Ağ hatası tarayıcının ham "Failed to fetch"i ya da "NETWORK" değil, projenin çevrilmiş metnidir (`networkMessage`);
 *    künye `NETWORK_ERROR`. JSON olmayan hata yanıtı (proxy sayfası) da açıklayıcı metinle döner. Yıl arşivi (`pdfBlob`)
 *    aynı yoldan geçer.
 */
const pdfResponse = () => ({
  ok: true, status: 200,
  headers: { get: (h) => (h.toLowerCase() === 'content-disposition' ? "attachment; filename*=UTF-8''mobil-teknik-hata-2026-09.pdf" : null) },
  blob: () => Promise.resolve(new Blob(['%PDF-1.4'], { type: 'application/pdf' })),
})

let originalFetch
let createUrl
let revokeUrl
beforeEach(() => {
  originalFetch = global.fetch
  clearErrorRegistry()
  try { localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  createUrl = URL.createObjectURL
  revokeUrl = URL.revokeObjectURL
  URL.createObjectURL = vi.fn(() => 'blob:te-pdf')
  URL.revokeObjectURL = vi.fn()
})
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
  global.fetch = originalFetch
  URL.createObjectURL = createUrl
  URL.revokeObjectURL = revokeUrl
  try { localStorage.removeItem(LANG_STORAGE_KEY) } catch { /* yok */ }
})

describe('teknik hata raporu PDF istemcisi', () => {
  it('downloadPdf: bağlantı tıklanırken DOM içinde, sunucunun adıyla; adres hemen değil 60 sn sonra iptal edilir', async () => {
    vi.useFakeTimers()
    global.fetch = vi.fn().mockResolvedValue(pdfResponse())
    const seen = []
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function onClick() {
      seen.push({ inDom: document.body.contains(this), download: this.download, href: this.getAttribute('href') })
    })
    const res = await api.techErrors.downloadPdf(100)
    expect(res).toEqual({ success: true, name: 'mobil-teknik-hata-2026-09.pdf' })
    expect(click).toHaveBeenCalledTimes(1)
    expect(seen).toEqual([{ inDom: true, download: 'mobil-teknik-hata-2026-09.pdf', href: 'blob:te-pdf' }])
    expect(document.querySelector('a[href="blob:te-pdf"]')).toBeNull()               // tıklamadan sonra kaldırılır
    await vi.advanceTimersByTimeAsync(1000)
    expect(URL.revokeObjectURL).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:te-pdf')
  })

  it('ağ hatası: ham "Failed to fetch" / "NETWORK" değil çevrilmiş metin + NETWORK_ERROR künyesi (indirme ve yıl arşivi)', async () => {
    global.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))
    const expected = networkMessage({ lang: 'tr', kind: 'network', offline: false })
    for (const res of [await api.techErrors.downloadPdf(100), await api.techErrors.pdfBlob(100)]) {
      expect(res.success).toBe(false)
      expect(res.status).toBe(0)
      expect(res.code).toBe('NETWORK_ERROR')
      expect(res.error).toBe(expected)
      expect(res.error).not.toMatch(/Failed to fetch|^NETWORK$/)
      expect(res.errorInfo).toEqual({ status: 0, code: 'NETWORK_ERROR' })
    }
  })

  it('İngilizce arayüzde ağ hatası İngilizce', async () => {
    localStorage.setItem(LANG_STORAGE_KEY, 'en')
    global.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))
    const res = await api.techErrors.pdfBlob(100)
    expect(res.error).toBe(networkMessage({ lang: 'en', kind: 'network', offline: false }))
  })

  it('iptal hata değildir: { aborted: true }, metin yok', async () => {
    global.fetch = vi.fn().mockRejectedValue(Object.assign(new Error('aborted'), { name: 'AbortError' }))
    const res = await api.techErrors.pdfBlob(100, { signal: new AbortController().signal })
    expect(res).toMatchObject({ success: false, aborted: true, error: null })
  })

  it('JSON olmayan hata yanıtı (proxy sayfası, 502) açıklayıcı metinle; JSON hata gövdesi sunucunun metni + künye (429)', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce({ ok: false, status: 502, headers: { get: () => null }, json: () => Promise.reject(new SyntaxError('Unexpected token <')) })
      .mockResolvedValueOnce({ ok: false, status: 429, headers: { get: () => null },
        json: () => Promise.resolve({ success: false, code: 'RATE_LIMITED', error: 'PDF sınırına ulaşıldı.', request_id: 'r-1' }) })
    const html = await api.techErrors.downloadPdf(100)
    expect(html.success).toBe(false)
    expect(html.status).toBe(502)
    expect(html.error).toEqual(expect.any(String))
    expect(html.error).not.toMatch(/Unexpected token|<html/i)
    const limited = await api.techErrors.downloadPdf(100)
    expect(limited).toMatchObject({ success: false, status: 429, code: 'RATE_LIMITED', error: 'PDF sınırına ulaşıldı.' })
    expect(limited.errorInfo).toEqual({ status: 429, code: 'RATE_LIMITED', requestId: 'r-1' })
  })
})
