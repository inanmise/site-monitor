import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

// E-posta önizlemesi isteğinin paylaşımı (inceleme 2026-10-18, 3. tur): sunucu kişi başına aynı anda TEK önizleme hazırlar
// (ikinci istek 429 RATE_LIMITED). Aynı kullanıcı × rapor için süren istek varsa yenisi ATILMAZ; çağıranın iptali yalnız
// kendi sonucunu bırakır; son çağıran ayrılınca ağ isteği MAIL_PREVIEW_KEEP_MS sonra kesilir (bu arada yeniden açılış
// katılır); başka kullanıcı öncekinin isteğine katılmaz; biten yanıt tutulmaz.
vi.mock('../api/client', () => ({ api: { techErrors: { mailPreview: vi.fn() } } }))
import { api } from '../api/client'
import {
  MAIL_PREVIEW_BUSY_RETRIES, MAIL_PREVIEW_BUSY_RETRY_TOTAL_S, MAIL_PREVIEW_KEEP_MS, bumpMailPreviewContent, busyRetryWaitMs,
  clearMailPreviewShares, fetchMailPreview, mailPreviewContentRev, mailPreviewShareCount,
} from '../components/techerror/mailPreviewFetch.js'
import { OWNER_KEY } from '../utils/personalStorage.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

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

describe('inceleme 2026-10-18 (4. tur): anahtar raporun SÜRÜMÜNÜ ve isteğin dilini taşır; eşzamanlılık reddinde bir kez yeniden dener', () => {
  it('kapat → düzenle (kayıt, sürüm 4 → 5) → yeniden aç: kayıttan ÖNCE başlamış isteğe KATILMAZ; kimsenin beklemediği eski istek hemen kesilir', async () => {
    const d = deferred()
    mp().mockReturnValueOnce(d.promise).mockResolvedValueOnce({ success: true, data: { subject: 'yeni içerik' } })
    const c1 = new AbortController()
    const old = fetchMailPreview('100', { signal: c1.signal, version: 4 })
    const oldNet = mp().mock.calls[0][1].signal
    c1.abort()                                                        // pencere kapandı
    expect(await old).toMatchObject({ aborted: true })
    const fresh = await fetchMailPreview('100', { version: 5 })
    expect(mp()).toHaveBeenCalledTimes(2)
    expect(fresh).toEqual({ success: true, data: { subject: 'yeni içerik' } })
    expect(oldNet.aborted).toBe(true)
    d.resolve({ success: true, data: { subject: 'eski içerik' } })
  })

  it('aynı sürüm hâlâ katılır; başka sürümü BEKLEYEN çağıran varsa onun isteği kesilmez', async () => {
    const d4 = deferred()
    const d5 = deferred()
    mp().mockReturnValueOnce(d4.promise).mockReturnValueOnce(d5.promise)
    const a = fetchMailPreview('100', { version: 4 })
    const b = fetchMailPreview('100', { version: 4 })
    expect(mp()).toHaveBeenCalledTimes(1)
    const c = fetchMailPreview('100', { version: 5 })
    expect(mp()).toHaveBeenCalledTimes(2)
    expect(mp().mock.calls[0][1].signal.aborted).toBe(false)
    d4.resolve({ success: true, data: { v: 4 } })
    d5.resolve({ success: true, data: { v: 5 } })
    expect((await a).data.v).toBe(4)
    expect((await b).data.v).toBe(4)
    expect((await c).data.v).toBe(5)
  })

  it('arayüz dili anahtarda: dil değişince eski dilin isteğine katılınmaz (X-Lang farklı, metinler başka dilde)', async () => {
    mp().mockImplementation(() => new Promise(() => {}))
    localStorage.setItem(LANG_STORAGE_KEY, 'tr')
    fetchMailPreview('100', { version: 4 })
    fetchMailPreview('100', { version: 4 })
    expect(mp()).toHaveBeenCalledTimes(1)
    localStorage.setItem(LANG_STORAGE_KEY, 'en')
    fetchMailPreview('100', { version: 4 })
    expect(mp()).toHaveBeenCalledTimes(2)
  })

  it('yeni istek sunucunun eşzamanlılık reddini (429 RATE_LIMITED + Retry-After) alırsa önerilen süre beklenip yeniden denenir', async () => {
    vi.useFakeTimers()
    const busy = { success: false, status: 429, code: 'RATE_LIMITED', retry_after_seconds: 5, error: 'Sizin için başka bir PDF hazırlanıyor.' }
    mp().mockResolvedValueOnce(busy).mockResolvedValueOnce({ success: true, data: { subject: 'ikinci deneme' } })
    const p = fetchMailPreview('100', { version: 5 })
    await vi.advanceTimersByTimeAsync(4900)
    expect(mp()).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(200)
    expect(mp()).toHaveBeenCalledTimes(2)
    expect(await p).toEqual({ success: true, data: { subject: 'ikinci deneme' } })
  })

  it('5. tur: önceki hazırlık uzun sürerse İKİNCİ yeniden deneme (5 + 5 sn) yeni önizlemeyi getirir — eskiden tek denemeden sonra "okunamadı"', async () => {
    vi.useFakeTimers()
    const busy = { success: false, status: 429, code: 'RATE_LIMITED', retry_after_seconds: 5, error: 'meşgul' }
    mp().mockResolvedValueOnce(busy).mockResolvedValueOnce(busy).mockResolvedValueOnce({ success: true, data: { subject: 'üçüncü' } })
    const p = fetchMailPreview('100', { version: 5 })
    await vi.advanceTimersByTimeAsync(5000)
    expect(mp()).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(4900)
    expect(mp()).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(200)
    expect(mp()).toHaveBeenCalledTimes(3)
    expect(await p).toEqual({ success: true, data: { subject: 'üçüncü' } })
  })

  it('5. tur: en çok İKİ yeniden deneme, beklemeler toplamı ≤ 15 sn; sonra sunucunun yanıtı (iletisi) olduğu gibi döner', async () => {
    vi.useFakeTimers()
    const busy = { success: false, status: 429, code: 'RATE_LIMITED', retry_after_seconds: 5, error: 'meşgul' }
    mp().mockResolvedValue(busy)
    const p = fetchMailPreview('100', { version: 5 })
    await vi.advanceTimersByTimeAsync(60_000)
    expect(await p).toEqual(busy)
    expect(mp()).toHaveBeenCalledTimes(1 + MAIL_PREVIEW_BUSY_RETRIES)
    // Bütçe: 8 sn'lik ilk öneri denenir, ikinci 8 sn kalan 7 sn'ye sığmaz → ikinci ret döner
    mp().mockReset()
    const eight = { success: false, status: 429, code: 'RATE_LIMITED', retry_after_seconds: 8, error: 'meşgul (8 sn)' }
    mp().mockResolvedValue(eight)
    const q = fetchMailPreview('100', { version: 6 })
    await vi.advanceTimersByTimeAsync(60_000)
    expect(await q).toEqual(eight)
    expect(mp()).toHaveBeenCalledTimes(2)
    // Dakikalık sınırın uzun beklemesi bütçeyi aşar: denenmez
    mp().mockReset()
    const minute = { success: false, status: 429, code: 'RATE_LIMITED', retry_after_seconds: 42, error: 'dakikalık sınır' }
    mp().mockResolvedValue(minute)
    expect(await fetchMailPreview('100', { version: 7 })).toEqual(minute)
    expect(mp()).toHaveBeenCalledTimes(1)
  })

  it('busyRetryWaitMs: yalnız 429 RATE_LIMITED + Retry-After, kalan bütçeye sığıyorsa', () => {
    expect(busyRetryWaitMs({ success: false, status: 429, code: 'RATE_LIMITED' })).toBeNull()
    expect(busyRetryWaitMs({ success: false, status: 429, code: 'PDF_BUSY', retry_after_seconds: 3 })).toBeNull()
    expect(busyRetryWaitMs({ success: false, status: 503, code: 'RATE_LIMITED', retry_after_seconds: 3 })).toBeNull()
    expect(busyRetryWaitMs({ success: false, status: 429, code: 'RATE_LIMITED', retryAfter: 2 })).toBe(2000)
    expect(busyRetryWaitMs({ success: false, errorInfo: { status: 429 }, code: 'RATE_LIMITED', retry_after_seconds: '5' })).toBe(5000)
    expect(busyRetryWaitMs({ success: false, status: 429, code: 'RATE_LIMITED', retry_after_seconds: 15 })).toBe(15_000)
    expect(busyRetryWaitMs({ success: false, status: 429, code: 'RATE_LIMITED', retry_after_seconds: 16 })).toBeNull()
    expect(busyRetryWaitMs({ success: false, status: 429, code: 'RATE_LIMITED', retry_after_seconds: 6 }, 5)).toBeNull()
    expect(busyRetryWaitMs({ success: true })).toBeNull()
  })

  it('bekleme bütçesi sunucunun eşzamanlılık önerisinin (RENDER_BUSY_RETRY_SECONDS) iki denemesini taşır', () => {
    const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
    const ctrl = fs.readFileSync(path.join(root, 'backend/src/main/java/com/sitemonitor/controller/TechErrorReportController.java'), 'utf8')
    const secs = Number(/RENDER_BUSY_RETRY_SECONDS\s*=\s*(\d+)L/.exec(ctrl)[1])
    expect(MAIL_PREVIEW_BUSY_RETRIES).toBe(2)
    expect(MAIL_PREVIEW_BUSY_RETRY_TOTAL_S).toBe(15)
    expect(MAIL_PREVIEW_BUSY_RETRIES * secs).toBeLessThanOrEqual(MAIL_PREVIEW_BUSY_RETRY_TOTAL_S)
  })
})

describe('inceleme 2026-10-18 (5. tur): görsel değişikliği sürümü değiştirmez — anahtar görsel revizyonunu da taşır', () => {
  it('görsel yüklendi / silindi (bumpMailPreviewContent) → 5 sn içinde yeniden açılış ESKİ isteğe katılmaz; kimsenin beklemediği eski istek kesilir', async () => {
    const d = deferred()
    mp().mockReturnValueOnce(d.promise).mockResolvedValueOnce({ success: true, data: { subject: 'yeni görseller' } })
    const c1 = new AbortController()
    const old = fetchMailPreview('100', { signal: c1.signal, version: 4 })
    const oldNet = mp().mock.calls[0][1].signal
    c1.abort()
    expect(await old).toMatchObject({ aborted: true })
    expect(mailPreviewContentRev('100')).toBe(0)
    expect(bumpMailPreviewContent('100')).toBe(1)
    expect(mailPreviewContentRev(100)).toBe(1)
    const fresh = await fetchMailPreview('100', { version: 4 })
    expect(mp()).toHaveBeenCalledTimes(2)
    expect(fresh).toEqual({ success: true, data: { subject: 'yeni görseller' } })
    expect(oldNet.aborted).toBe(true)
    d.resolve({ success: true, data: { subject: 'eski görseller' } })
  })

  it('görsel değişmediyse (aynı sürüm, aynı revizyon) hâlâ katılır; başka rapor etkilenmez; çağıranın bildirdiği daha yeni revizyon da katılmayı keser', async () => {
    mp().mockImplementation(() => new Promise(() => {}))
    bumpMailPreviewContent('101')
    fetchMailPreview('100', { version: 4 })
    fetchMailPreview('100', { version: 4 })
    expect(mp()).toHaveBeenCalledTimes(1)
    fetchMailPreview('100', { version: 4, contentRev: 3 })
    expect(mp()).toHaveBeenCalledTimes(2)
    // Modülün sayacı daha büyükse o geçerli (eski bir revizyon bildiren çağıran eski isteğe katılmaz)
    bumpMailPreviewContent('100')
    bumpMailPreviewContent('100')
    bumpMailPreviewContent('100')
    bumpMailPreviewContent('100')
    fetchMailPreview('100', { version: 4, contentRev: 3 })
    expect(mp()).toHaveBeenCalledTimes(3)
    fetchMailPreview('100', { version: 4 })
    expect(mp()).toHaveBeenCalledTimes(3)
  })

  it('test kancası sayaçları da sıfırlar', () => {
    bumpMailPreviewContent('100')
    clearMailPreviewShares()
    expect(mailPreviewContentRev('100')).toBe(0)
  })
})
