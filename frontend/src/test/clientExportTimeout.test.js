import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  api, DEFAULT_READ_TIMEOUT_MS, EXPORT_FETCH_TIMEOUT_MS, LONG_JOB_TIMEOUT_MS, REPORT_TIMEOUT_MS, SQL_EXPORT_TIMEOUT_MS,
} from '../api/client.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'

/**
 * Büyük dışa aktarım ve tek dosya indirmelerinin süre sınırı (2026-11-15, kullanıcı kuralı "zaman aşımı olmayan istek
 * yok"): alarm akışı / aktivite / olaylar CSV'si, SQL Playground dışa aktarımı, aktivite / olaylar JSON'u, teknik hata
 * PDF'i (tek ve arşiv), yönetici özeti PDF'i, haftalık kesinti PDF'i, Grafana panosu JSON'u ve şablon önizleme PDF'i
 * eskiden ham `fetch` (ya da `timeoutMs: 0`) ile SONSUZA dek bekleyebiliyordu. Artık istek VE gövde okuması tek son
 * tarihle sınırlı: süre dolunca çevrilmiş "zaman aşımı" yükü (`code: REQUEST_TIMEOUT`) döner, meşgul durum kalkar.
 * Çağıranın iptali (SQL "Durdur", yıl arşivi) yine iptaldir.
 */

/** Sinyali HİÇ dinlemeyen, hiç yanıt vermeyen bağlantı (en kötü durum). */
const hangingFetch = () => vi.fn(() => new Promise(() => {}))
/** Başlıklar gelir, gövde hiç bitmez (başlıklardan sonra duran yarı açık soket). */
const bodyHangsFetch = () => vi.fn(() => Promise.resolve({
  ok: true, status: 200, headers: { get: () => null },
  blob: () => new Promise(() => {}), json: () => new Promise(() => {}), text: () => new Promise(() => {}),
}))

async function settlesAfter(promise, ms) {
  let settled = false
  promise.then(() => { settled = true }, () => { settled = true })
  await vi.advanceTimersByTimeAsync(ms - 1000)
  expect(settled).toBe(false)
  await vi.advanceTimersByTimeAsync(1500)
  expect(settled).toBe(true)
  return promise
}

const CASES = [
  ['alarm akışı CSV', () => api.alarmFeed.exportRequests({}), EXPORT_FETCH_TIMEOUT_MS],
  ['aktivite CSV', () => api.exportActivity({}, 'csv'), EXPORT_FETCH_TIMEOUT_MS],
  ['aktivite JSON', () => api.exportActivity({}, 'json'), EXPORT_FETCH_TIMEOUT_MS],
  ['olaylar CSV', () => api.monitoring.incidents.exportCsv({}), EXPORT_FETCH_TIMEOUT_MS],
  ['olaylar JSON', () => api.monitoring.incidents.exportJson({}), EXPORT_FETCH_TIMEOUT_MS],
  ['SQL dışa aktarım (CSV)', () => api.admin.sqlExport('select 1', { format: 'csv' }), SQL_EXPORT_TIMEOUT_MS],
  ['SQL dışa aktarım (JSON)', () => api.admin.sqlExport('select 1', { format: 'json' }), SQL_EXPORT_TIMEOUT_MS],
  ['teknik hata PDF indir', () => api.techErrors.downloadPdf(5), REPORT_TIMEOUT_MS],
  ['teknik hata PDF (arşiv) — sinyalsiz', () => api.techErrors.pdfBlob(5), REPORT_TIMEOUT_MS],
  ['yönetici özeti PDF', () => api.executiveSummary.downloadPdf({ month: '2026-10' }), REPORT_TIMEOUT_MS],
  ['yönetici özeti PDF (canlı)', () => api.executiveSummary.downloadPdf({ month: '2026-10', live: true }), LONG_JOB_TIMEOUT_MS],
  // Sunucu `live` olmadan da gönderilmemiş ayı canlı hesaplar → okuma da DAİMA 5 dk (inceleme 2026-11-15)
  ['yönetici özeti okuma (live yok)', () => api.executiveSummary.get({ month: '2026-10' }), REPORT_TIMEOUT_MS],
  ['yönetici özeti okuma (canlı)', () => api.executiveSummary.get({ month: '2026-10', live: true }), REPORT_TIMEOUT_MS],
  ['haftalık kesinti PDF', () => api.admin.downloadWeeklyOutagePdf(3, 0), REPORT_TIMEOUT_MS],
  ['Grafana panosu JSON', () => api.admin.downloadGrafanaDashboard(), DEFAULT_READ_TIMEOUT_MS],
  ['şablon önizleme PDF', () => api.techErrors.templatePreviewPdf(1, { sections: [] }), REPORT_TIMEOUT_MS],
]

describe('dışa aktarım / indirme süre sınırı', () => {
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

  it.each(CASES)('%s: bağlantı yanıt vermezse süre dolunca çevrilmiş "zaman aşımı" yükü', async (_name, call, ms) => {
    global.fetch = hangingFetch()
    const res = await settlesAfter(call(), ms)
    expect(res).toEqual(expect.objectContaining({ success: false, code: 'REQUEST_TIMEOUT' }))
    expect(res.error).toMatch(/didn.t respond in time/)
    // İstek son tarih sinyaliyle gitti (gerçek tarayıcı bağlantıyı da keser)
    expect(global.fetch.mock.calls[0][1].signal).toBeInstanceOf(AbortSignal)
    expect(global.fetch.mock.calls[0][1].signal.aborted).toBe(true)
  })

  it.each(CASES)('%s: başlıklar gelip gövde hiç bitmezse de süre dolunca biter', async (_name, call, ms) => {
    global.fetch = bodyHangsFetch()
    const res = await settlesAfter(call(), ms)
    expect(res).toEqual(expect.objectContaining({ success: false, code: 'REQUEST_TIMEOUT' }))
  })

  it('SQL dışa aktarım: kullanıcının "Durdur"u yine iptaldir (ABORTED), zaman aşımı değil', async () => {
    global.fetch = vi.fn((url, init) => new Promise((_, reject) => {
      init.signal.addEventListener('abort', () => reject(init.signal.reason))
    }))
    const ctl = new AbortController()
    const p = api.admin.sqlExport('select 1', { format: 'csv', signal: ctl.signal })
    await vi.advanceTimersByTimeAsync(1000)
    ctl.abort()
    expect(await p).toEqual(expect.objectContaining({ success: false, code: 'ABORTED' }))
  })

  it('teknik hata PDF (yıl arşivi): arşivin iptali `aborted` döner; süre dolması ise `timedOut` + REQUEST_TIMEOUT', async () => {
    global.fetch = hangingFetch()
    const ctl = new AbortController()
    const p = api.techErrors.pdfBlob(5, { signal: ctl.signal })
    await vi.advanceTimersByTimeAsync(1000)
    ctl.abort()
    expect(await p).toEqual(expect.objectContaining({ success: false, aborted: true }))

    const q = api.techErrors.pdfBlob(6)
    const res = await settlesAfter(q, REPORT_TIMEOUT_MS)
    expect(res).toEqual(expect.objectContaining({ success: false, timedOut: true, code: 'REQUEST_TIMEOUT' }))
  })

  it('başarılı indirmede son tarih zamanlayıcısı söker (bekleyen zamanlayıcı kalmaz)', async () => {
    global.fetch = vi.fn(() => Promise.resolve({
      ok: true, status: 200, headers: { get: () => null }, json: () => Promise.resolve({ success: true, data: [] }),
    }))
    // Taban: beforeEach'teki localStorage yazımı jsdom'da sahte saate bir olay zamanlayıcısı koyar — istemciden gelmez
    const base = vi.getTimerCount()
    const res = await api.exportActivity({}, 'json')
    expect(res).toEqual({ success: true, data: [] })
    expect(vi.getTimerCount()).toBe(base)
  })
})
