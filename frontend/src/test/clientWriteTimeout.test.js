import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  api, DEFAULT_WRITE_TIMEOUT_MS, LIVE_CHECK_TIMEOUT_MS, LONG_JOB_TIMEOUT_MS, REPORT_TIMEOUT_MS, UPLOAD_TIMEOUT_MS,
} from '../api/client.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'

/**
 * Yazma istekleri de süre sınırlı (2026-11-15, kullanıcı kuralı "zaman aşımı olmayan istek yok" — 2026-10-08'deki
 * "yazmalar sınırsız" kuralının yerini alır). Varsayılan 120 sn; yükleme (FormData) 5 dk; sunucuda meşru olarak uzun süren
 * uçlar AÇIK ve sunucu bütçesinin üstünde bir değer taşır. Süre dolması "sonuç bilinmiyor" anlamındadır: metin "işlem arka
 * planda tamamlanmış olabilir — sayfayı yenileyip kontrol edin" der (kör yeniden tıklamaya çağırmaz).
 */

/** Sinyali dinlemeyen, hiç yanıt vermeyen bağlantı. */
const hangingFetch = () => vi.fn(() => new Promise(() => {}))

async function settlesAfter(promise, ms) {
  let settled = false
  promise.then(() => { settled = true }, () => { settled = true })
  await vi.advanceTimersByTimeAsync(ms - 1000)
  expect(settled).toBe(false)
  await vi.advanceTimersByTimeAsync(1500)
  expect(settled).toBe(true)
  return promise
}

describe('yazma isteklerinin süre sınırı', () => {
  const realFetch = global.fetch
  beforeEach(() => {
    vi.useFakeTimers()
    try { localStorage.setItem(LANG_STORAGE_KEY, 'en') } catch { /* yok */ }
  })
  afterEach(() => {
    vi.useRealTimers()
    global.fetch = realFetch
    try { localStorage.removeItem(LANG_STORAGE_KEY) } catch { /* yok */ }
  })

  it('asılı POST varsayılan 120 sn sonra "sonuç bilinmiyor — kontrol edin" yüküyle döner', async () => {
    global.fetch = hangingFetch()
    const res = await settlesAfter(api.runScheduler(), DEFAULT_WRITE_TIMEOUT_MS)
    expect(res).toEqual(expect.objectContaining({ success: false, status: 0, code: 'REQUEST_TIMEOUT' }))
    expect(res.error).toMatch(/may still have completed/)
    expect(global.fetch.mock.calls[0][1].signal).toBeInstanceOf(AbortSignal)
  })

  it('PUT / DELETE de aynı varsayılanı alır', async () => {
    global.fetch = hangingFetch()
    const put = await settlesAfter(api.techErrors.updateChannel(2, { name: 'x' }), DEFAULT_WRITE_TIMEOUT_MS)
    expect(put.code).toBe('REQUEST_TIMEOUT')
    const del = await settlesAfter(api.techErrors.deleteChannel(2), DEFAULT_WRITE_TIMEOUT_MS)
    expect(del.code).toBe('REQUEST_TIMEOUT')
  })

  it('FormData yüklemesi 5 dk alır', async () => {
    global.fetch = hangingFetch()
    const file = new Blob(['x'], { type: 'image/png' })
    const res = await settlesAfter(api.techErrors.uploadImage(4, 'sla-1', file), UPLOAD_TIMEOUT_MS)
    expect(res.code).toBe('REQUEST_TIMEOUT')
  })

  it.each([
    ['HTTP "Şimdi kontrol et"', () => api.monitoring.triggerHttpCheck(3), LIVE_CHECK_TIMEOUT_MS],
    ['port denemesi', () => api.monitoring.testPortMonitor({}), LIVE_CHECK_TIMEOUT_MS],
    ['veritabanı sorgusu denemesi', () => api.monitoring.testDbQuery({}), LIVE_CHECK_TIMEOUT_MS],
    ['canlı sertifika kontrolü', () => api.checkDomain('a.example.com'), LIVE_CHECK_TIMEOUT_MS],
    ['sertifika hiyerarşisi', () => api.certHierarchy('a.example.com'), LIVE_CHECK_TIMEOUT_MS],
    ['teknik hata raporu gönderimi', () => api.techErrors.send(9), REPORT_TIMEOUT_MS],
    ['PO onay isteği', () => api.techErrors.requestApproval(9), REPORT_TIMEOUT_MS],
    ['yönetici özeti gönderimi', () => api.executiveSummary.runNow('2026-10'), LONG_JOB_TIMEOUT_MS],
    ['elle saklama çalıştırma', () => api.admin.retentionRunNow(), LONG_JOB_TIMEOUT_MS],
    ['gizli değer yeniden şifreleme', () => api.admin.reencryptSecrets(), LONG_JOB_TIMEOUT_MS],
    ['toplu pasifleştirme', () => api.admin.bulkDeactivate({}), LONG_JOB_TIMEOUT_MS],
  ])('%s: sunucu bütçesinin üstünde AÇIK, SONLU süre', async (_n, call, ms) => {
    global.fetch = hangingFetch()
    const res = await settlesAfter(call(), ms)
    expect(res).toEqual(expect.objectContaining({ success: false, code: 'REQUEST_TIMEOUT' }))
  })

  it('XMLHttpRequest yüklemesi (manuel sertifika, ilerleme yüzdeli) de son tarihte kesilir', async () => {
    const realXhr = globalThis.XMLHttpRequest
    const made = []
    class StuckXHR {
      constructor() { this.upload = {}; this.aborted = false; made.push(this) }
      open() {}
      setRequestHeader() {}
      getResponseHeader() { return null }
      send() {}
      abort() { this.aborted = true; this.onabort?.() }
    }
    globalThis.XMLHttpRequest = StuckXHR
    try {
      const fd = new FormData()
      fd.append('extracted', '{}')
      const res = await settlesAfter(api.manualCerts.analyze(fd, { onProgress: () => {} }), UPLOAD_TIMEOUT_MS)
      expect(res).toEqual(expect.objectContaining({ success: false, code: 'REQUEST_TIMEOUT' }))
      expect(made[0].aborted).toBe(true)
    } finally {
      globalThis.XMLHttpRequest = realXhr
    }
  })
})
