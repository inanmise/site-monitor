import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { api } from '../api/client.js'

/**
 * Yönetici özeti ve haftalık kesinti PDF indirmesi (2026-10-22, UIM-27): ortak `downloadBlob` ile — bağlantı tıklanırken
 * DOM'da, adres hemen iptal edilmez (Safari / iPad); tarayıcı indirmeyi başlatamazsa başarı DEĞİL (`DOWNLOAD_FAILED`).
 * Ham fetch 401 alırsa oturum yolu çalışır (tek yoklama `request()`'in 401 dalından geçer).
 */
const pdfResponse = (name = 'rapor.pdf') => ({
  ok: true, status: 200,
  headers: { get: (h) => (h.toLowerCase() === 'content-disposition' ? `attachment; filename="${name}"` : null) },
  blob: () => Promise.resolve(new Blob(['%PDF-1.4'], { type: 'application/pdf' })),
})

let originalFetch, createUrl, revokeUrl
beforeEach(() => {
  originalFetch = global.fetch
  createUrl = URL.createObjectURL
  revokeUrl = URL.revokeObjectURL
  URL.createObjectURL = vi.fn(() => 'blob:pdf')
  URL.revokeObjectURL = vi.fn()
})
afterEach(() => {
  vi.restoreAllMocks()
  global.fetch = originalFetch
  URL.createObjectURL = createUrl
  URL.revokeObjectURL = revokeUrl
})

const cases = [
  ['yönetici özeti', () => api.executiveSummary.downloadPdf({ month: '2026-09' }), 'ozet.pdf'],
  ['haftalık kesinti', () => api.admin.downloadWeeklyOutagePdf(5, 0), 'kesinti.pdf'],
]

describe.each(cases)('%s PDF', (_name, call, file) => {
  it('bağlantı DOM\'da tıklanır, sunucunun dosya adıyla; adres hemen iptal edilmez', async () => {
    global.fetch = vi.fn(() => Promise.resolve(pdfResponse(file)))
    let seen = null
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function () { seen = { inDom: document.body.contains(this), name: this.download } })
    const res = await call()
    expect(res.success).toBe(true)
    expect(seen).toEqual({ inDom: true, name: file })
    expect(URL.revokeObjectURL).not.toHaveBeenCalled()
  })

  it('tarayıcı indirmeyi başlatamazsa başarı değil: DOWNLOAD_FAILED', async () => {
    global.fetch = vi.fn(() => Promise.resolve(pdfResponse(file)))
    URL.createObjectURL = vi.fn(() => { throw new Error('blocked') })
    const res = await call()
    expect(res.success).toBe(false)
    expect(res.code).toBe('DOWNLOAD_FAILED')
  })

  it('401 → oturum yoklaması (request() 401 yolu); sonuç başarısız', async () => {
    global.fetch = vi.fn((url) => Promise.resolve(String(url).includes('/session/ping')
      ? { ok: false, status: 401, headers: { get: () => null }, json: () => Promise.resolve({ success: false }) }
      : { ok: false, status: 401, headers: { get: () => null }, json: () => Promise.resolve({}) }))
    const res = await call()
    expect(res.success).toBe(false)
    await vi.waitFor(() => expect(global.fetch.mock.calls.some(([u]) => String(u).includes('/session/ping'))).toBe(true))
  })
})
