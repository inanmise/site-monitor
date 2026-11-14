import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { unzipSync } from 'fflate'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import { api, IMAGE_FETCH_TIMEOUT_MS } from '../api/client.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { clearErrorRegistry } from '../utils/errorMessages.js'
import { emitSessionReset } from '../utils/sessionResetBus.js'
import { createImageToolStore } from '../components/page/resourcescan/imagetool/imageToolStore.js'
import { isImageWorkBusy, resetImageWorkForTests } from '../components/page/resourcescan/imagetool/imageToolBusy.js'
import {
  bulkPlan, encodePathPrefix, mappingRows, maskMetadata, metadataAvailable, nameKey, optimizeSettings, outputName, pictureSnippet,
  resizeSettings, responsiveWidths, safeFileBase, serverProblemCode, serverProblemText, uniqueName,
} from '../components/page/resourcescan/imagetool/imageToolModel.js'

// Görsel aracı — bağımsız inceleme düzeltmeleri (2026-11-14): her madde, düzeltme geri alınınca DÜŞEN bir testle kilitli.
const tr = (key, ...args) => String(TR[key] ?? key).replace(/\{(\d+)\}/g, (_, i) => String(args[Number(i)] ?? ''))
const blobOf = (n, type = 'image/jpeg') => new Blob([new Uint8Array(n).fill(7)], { type })
const fileOf = (name, n = 4000) => new File([new Uint8Array(n).fill(3)], name, { type: 'image/jpeg' })
const unzip = async (blob) => unzipSync(new Uint8Array(await blob.arrayBuffer()))
const deferred = () => { let resolve; const promise = new Promise((r) => { resolve = r }); return { promise, resolve } }
const img = (name, bytes = 600 * 1024, over = {}) => ({ category: 'IMAGE', url: `https://ornek.example.com/img/${name}`, bytes, status: 200, ok: true, verdict: 'ok', width: 3000, height: 2000, ...over })
const SCAN = 12
const until = async (cond, ms = 2000) => {
  const end = Date.now() + ms
  while (!cond()) {
    if (Date.now() > end) throw new Error('koşul gerçekleşmedi')
    await new Promise((r) => setTimeout(r, 5))
  }
}

function fakeEngine({ metadata = {}, resized = null } = {}) {
  const calls = []
  return {
    calls,
    engineLimits: async () => ({ maxInputBytes: 25 * 1024 * 1024, maxPixels: 50_000_000, maxSide: 30_000 }),
    engineSupport: async () => ({ worker: true, webp: true }),
    inspectImage: async (blob) => ({ type: 'jpeg', width: 3000, height: 2000, bytes: blob.size, hasAlpha: false, animated: false }),
    processImage: async (blob, options) => {
      calls.push({ options })
      const isResized = resized ?? options.resize.method !== 'none'
      const width = isResized ? (options.resize.width ?? 1500) : 3000
      const type = options.format === 'keep' || options.format === 'smallest' ? 'jpeg' : options.format
      const bytes = Math.max(1, Math.floor(blob.size / 4))
      return {
        blob: blobOf(bytes, `image/${type}`), type, mime: `image/${type}`, width, height: Math.round((width * 2) / 3), bytes,
        original: { type: 'jpeg', width: 3000, height: 2000, bytes: blob.size, hasAlpha: false, animated: false,
          metadata: { copyright: false, creation: false, location: false, ...metadata } },
        savingPct: 75, quality: 80, colors: null, similarity: 0.99, resized: isResized, cropped: false, kept: false, notes: [],
      }
    },
    decodeForPreview: async () => ({ width: 10, height: 10, close() {} }),
  }
}
const okFetch = () => vi.fn(async (_scan, url) => ({ success: true, blob: blobOf(4000), name: `${url.split('/').pop().split('.')[0]}.jpg`, type: 'jpeg', width: 3000, height: 2000 }))
function make(over = {}) {
  const download = vi.fn(() => true)
  const fetchImage = over.fetchImage || okFetch()
  const engine = over.engine || fakeEngine()
  const store = createImageToolStore({ fetchImage, engine, download, sleep: async () => true })
  return { store, download, fetchImage, engine }
}

beforeEach(() => { resetImageWorkForTests() })

describe('2 — ZIP adları dil-bağımsız tekilleşir', () => {
  it('"IMG_01.jpg" ile "img_01.jpg" aynı ad sayılır (Türkçe küçültme "ımg" / "img" diye ayırırdı → Windows\'ta üzerine yazılırdı)', () => {
    const used = new Set()
    expect(uniqueName('IMG_01.jpg', used)).toBe('IMG_01.jpg')
    expect(uniqueName('img_01.jpg', used)).toBe('img_01-2.jpg')
    expect(uniqueName('Img_01.JPG', used)).toMatch(/^Img_01-3\.jpg$/i)
    expect(nameKey('image.png')).toBe(nameKey('IMAGE.PNG'))
  })

  it('aracın ZIP\'i: büyük / küçük harfi farklı iki dosya ayrı adla girer', async () => {
    const { store, download } = make()
    store.addFiles([fileOf('IMG_01.jpg'), fileOf('img_01.jpg')])
    await until(() => store.queue().every((e) => e.state === 'done'))
    expect((await store.zipQueue({ t: tr, name: 'arac.zip' })).ok).toBe(true)
    const names = Object.keys(await unzip(download.mock.calls[0][1]))
    expect(names).toContain('IMG_01.jpg')
    expect(names).toContain('img_01-2.jpg')
    expect(names).not.toContain('img_01.jpg')
  })
})

describe('3 — üst veri: seçenekler kullanıcının gördüğünden kurulur', () => {
  it('maskMetadata: o tür hiçbir işlenmiş görselde yoksa "koru" seçeneğe girmez; varsa girer', () => {
    const s = optimizeSettings({ keepLocation: true, keepCopyright: true })
    expect(maskMetadata(s, { copyright: false, creation: false, location: false })).toMatchObject({ keepLocation: false, keepCopyright: false })
    expect(maskMetadata(s, { copyright: true, creation: false, location: true })).toBe(s)
    expect(metadataAvailable([{ state: 'done', result: { original: { metadata: { location: true } } } }, { state: 'processing' }]))
      .toEqual({ copyright: false, creation: false, location: true })
  })

  it('"Temizle"den sonra yeni GPS\'li fotoğraf: anahtar görünmezken konum KORUNMAZ; görününce satır bayatlar, yeniden işlemede korunur', async () => {
    const { store, engine } = make({ engine: fakeEngine({ metadata: { location: true } }) })
    store.setSettings({ keepLocation: true })                 // önceki oturumdan kalan ayar (ekranda kapalı / devre dışı)
    store.addFiles([fileOf('gps.jpg')])
    await until(() => store.queue()[0]?.state === 'done')
    expect(engine.calls[0].options.preserve.location).toBe(false)
    expect(store.isStale(store.queue()[0])).toBe(true)        // artık anahtar görünür ve açık → "yeniden işle" önerilir
    store.reprocess()
    await until(() => engine.calls.length === 2 && store.queue()[0]?.state === 'done')
    expect(engine.calls[1].options.preserve.location).toBe(true)
  })
})

describe('4 — uygulama içi gezinme toplu indirmeyi kesmez; çıkış keser', () => {
  it('release(): süren toplu indirme SÜRER, ZIP iner, sonra depo kendini kapatır; "süren iş" kaydı o ana dek dolu', async () => {
    const gate = deferred()
    const fetchImage = vi.fn(async (_s, url) => { await gate.promise; return { success: true, blob: blobOf(4000), name: `${url.split('/').pop()}`, type: 'jpeg', width: 3000, height: 2000 } })
    const { store, download } = make({ fetchImage })
    const run = store.startBulk({ scanId: SCAN, items: [img('a.jpg'), img('b.jpg')], canRun: true, itemsKept: true, t: tr, locale: 'tr-TR', baseName: 'site' })
    await until(() => store.isBulkRunning())
    expect(isImageWorkBusy()).toBe(true)
    store.release()
    expect(store.isReleased()).toBe(true)
    expect(store.isDisposed()).toBe(false)
    expect(isImageWorkBusy()).toBe(true)
    gate.resolve()
    const out = await run
    expect(out.ok).toBe(true)
    expect(download).toHaveBeenCalledTimes(1)
    expect(download.mock.calls[0][0]).toMatch(/\.zip$/)
    expect(store.isDisposed()).toBe(true)
    expect(isImageWorkBusy()).toBe(false)
  })

  it('release(): iş yoksa hemen kapanır', () => {
    const { store } = make()
    store.release()
    expect(store.isDisposed()).toBe(true)
  })

  it('oturum sıfırlanınca (çıkış) depo hemen kapanır: toplu iş kesilir, hiçbir şey inmez', async () => {
    const gate = deferred()
    const fetchImage = vi.fn(async () => { await gate.promise; return { success: true, blob: blobOf(4000), name: 'a.jpg', type: 'jpeg', width: 3000, height: 2000 } })
    const { store, download } = make({ fetchImage })
    const run = store.startBulk({ scanId: SCAN, items: [img('a.jpg')], canRun: true, itemsKept: true, t: tr, locale: 'tr-TR', baseName: 'site' })
    await until(() => store.isBulkRunning())
    store.release()                                             // sekme sökülmüş, iş sürüyor
    emitSessionReset()
    expect(store.isDisposed()).toBe(true)
    expect(isImageWorkBusy()).toBe(false)
    gate.resolve()
    await run
    expect(download).not.toHaveBeenCalled()
  })
})

describe('9 — karşılaştırma yalnız iki görsel de bellekteyken', () => {
  it('tek görsel optimize → var; toplu indirme sonucu (Blob tutulmaz) → yok', async () => {
    const { store } = make()
    const one = img('one.jpg')
    await store.optimizeScanImage(SCAN, one, optimizeSettings(), { mode: 'optimized', silent: true })
    expect(store.hasScanBlobs(SCAN, one.url)).toBe(true)
    const many = [img('a.jpg'), img('b.jpg')]
    await store.startBulk({ scanId: SCAN, items: many, canRun: true, itemsKept: true, t: tr, locale: 'tr-TR', baseName: 'site' })
    expect(store.scanEntry(SCAN, many[0].url).result).not.toBeNull()
    expect(store.hasScanBlobs(SCAN, many[0].url)).toBe(false)
  })
})

describe('10 — "boyutu fazla" önerisinin toplu düğmesi KENDİ kümesini alır', () => {
  const big = img('agir.jpg', 900 * 1024, { width: 1200, findings: ['LARGE_IMAGES'] })
  const wide = img('genis.png', 150 * 1024, { width: 3200, findings: ['OVERSIZED_IMAGE_DIMENSIONS'] })
  const ctx = { canRun: true, itemsKept: true }

  it('bulkPlan: LARGE_IMAGES ile OVERSIZED_IMAGE_DIMENSIONS ayrı kümeler; bulgu listesi olmayan eski analizde önerinin kuralı', () => {
    expect(bulkPlan([big, wide], ctx, 'LARGE_IMAGES').plan.map((i) => i.url)).toEqual([big.url])
    expect(bulkPlan([big, wide], ctx, 'OVERSIZED_IMAGE_DIMENSIONS').plan.map((i) => i.url)).toEqual([wide.url])
    const oldBig = img('eski-agir.jpg', 900 * 1024, { width: 1200 })
    const oldWide = img('eski-genis.png', 150 * 1024, { width: 3200 })
    expect(bulkPlan([oldBig, oldWide], ctx, 'OVERSIZED_IMAGE_DIMENSIONS').plan.map((i) => i.url)).toEqual([oldWide.url])
  })

  it('startBulk(finding): yalnız o kümenin görselleri sunucudan istenir', async () => {
    const { store, fetchImage } = make()
    await store.startBulk({ scanId: SCAN, items: [big, wide], canRun: true, itemsKept: true, t: tr, locale: 'tr-TR', baseName: 'site', finding: 'OVERSIZED_IMAGE_DIMENSIONS' })
    expect(fetchImage.mock.calls.map((c) => c[1])).toEqual([wide.url])
  })
})

describe('6 / 7 / 8 — duyarlı set: doğal genişlik, kodlanmış adres, tek tür', () => {
  it('doğal genişlik iki adımın arasındaysa sete girer (1919, 1000); 1920\'den genişte eklenmez', () => {
    expect(responsiveWidths(1919)).toEqual([480, 768, 1280, 1919])
    expect(responsiveWidths(1000)).toEqual([480, 768, 1000])
    expect(responsiveWidths(1280)).toEqual([480, 768, 1280])
    expect(responsiveWidths(3000)).toEqual([480, 768, 1280, 1920])
    expect(responsiveWidths(300)).toEqual([300])
  })

  it('önek ve dosya adı yol parçası parçası kodlanır (boşluk, virgül, "#", "?") — köken ve "/" korunur, kodlanmış önek iki kez kodlanmaz', () => {
    expect(encodePathPrefix('my images/')).toBe('my%20images/')
    expect(encodePathPrefix('https://cdn.example.com/a b/c#d?e/')).toBe('https://cdn.example.com/a%20b/c%23d%3Fe/')
    expect(encodePathPrefix('/assets/img%20x/')).toBe('/assets/img%20x/')
    const snip = pictureSnippet({ files: [{ name: 'a,b-480w.webp', width: 480, type: 'webp' }], prefix: 'my images/' })
    expect(snip).toContain('srcset="my%20images/a%2Cb-480w.webp 480w"')
  })

  it('karışık türler: her <source> yalnız kendi türünün dosyalarını listeler; <img srcset> tek tür', () => {
    const files = [{ name: 'x-480w.webp', width: 480, type: 'webp' }, { name: 'x-768w.webp', width: 768, type: 'webp' }, { name: 'x-1000w.gif', width: 1000, type: 'gif' }]
    const pic = pictureSnippet({ files, kind: 'picture', fallbackName: 'x.gif' })
    const sources = pic.split('\n').filter((l) => l.includes('<source'))
    expect(sources).toHaveLength(2)
    expect(sources[0]).toContain('type="image/webp" srcset="x-480w.webp 480w, x-768w.webp 768w"')
    expect(sources[1]).toContain('type="image/gif" srcset="x-1000w.gif 1000w"')
    const one = pictureSnippet({ files, kind: 'img' })
    expect(one).toContain('srcset="x-1000w.gif 1000w"')
    expect(one).not.toContain('webp')
  })
})

describe('13 — dosya adları ve 404 kuralı', () => {
  it('Windows\'a ayrılmış adlar "_" alır (uzantılı da); yön (bidi) karakterleri atılır', () => {
    expect(safeFileBase('CON')).toBe('CON_')
    expect(safeFileBase('con.backup')).toBe('con_.backup')
    expect(safeFileBase('Lpt9')).toBe('Lpt9_')
    expect(safeFileBase('nul ')).toBe('nul_')
    expect(safeFileBase('console')).toBe('console')
    expect(safeFileBase('COM10')).toBe('COM10')
    expect(safeFileBase('fatura‮gpj.exe')).toBe('faturagpj.exe')
    expect(safeFileBase('⁦a⁩b‎‏‪')).toBe('ab')
    expect(outputName('AUX', 'jpeg')).toBe('AUX_.jpg')
  })

  it('özel boyut doğal genişlikte (büyütülmez) → özgünle aynı ad DEĞİL, "-opt"', async () => {
    expect(outputName('hero', 'jpeg', { mode: 'width', width: null })).toBe('hero-opt.jpg')
    const { store, download } = make({ engine: fakeEngine({ resized: false }) })
    await store.optimizeScanImage(SCAN, img('hero.jpg'), resizeSettings(5000), { mode: 'width' })
    expect(download.mock.calls[0][0]).toBe('hero-opt.jpg')
  })

  it('araçta işlenen tarama görseli: satır sonucu ve önbellekten ikinci indirme "-opt" adıyla (özgün adla değil)', async () => {
    const { store, download } = make()
    const item = img('hero.jpg')
    await store.sendToTool(SCAN, item)
    await until(() => store.queue()[0]?.state === 'done')
    expect(store.scanEntry(SCAN, item.url).result.name).toBe('hero-opt.jpg')
    expect(await store.optimizeScanImage(SCAN, item, optimizeSettings(), { mode: 'optimized' })).toMatchObject({ ok: true, cached: true })
    expect(download.mock.calls.at(-1)[0]).toBe('hero-opt.jpg')
  })

  it('404: kodsuz / 405 = eski sunucu; NOT_FOUND = izleme artık görünmüyor; kendi kodlu 404\'ler kendisi', () => {
    expect(serverProblemCode({ success: false, status: 404 })).toBe('unsupported')
    expect(serverProblemCode({ success: false, status: 405 })).toBe('unsupported')
    expect(serverProblemCode({ success: false, status: 404, code: 'NOT_FOUND' })).toBe('monitor_gone')
    expect(serverProblemCode({ success: false, status: 404, code: 'RESOURCE_SCAN_NOT_FOUND' })).toBe('RESOURCE_SCAN_NOT_FOUND')
    expect(serverProblemCode({ success: false, status: 404, code: 'RESOURCE_IMAGE_NOT_FOUND' })).toBe('RESOURCE_IMAGE_NOT_FOUND')
    expect(serverProblemText({ success: false, status: 404, code: 'NOT_FOUND' }, tr, 'tr-TR')).toMatch(/Bu izleme artık sizin için görünmüyor/)
    for (const k of ['prs.it.srv.monitor_gone', 'prs.it.srv.timeout', 'prs.it.bulk.continues', 'prs.it.bulk.countOversized', 'prs.it.bulk.countOversized.one']) {
      expect(TR[k], k).toBeTruthy()
      expect(EN[k], k).toBeTruthy()
    }
    expect(TR['prs.it.leave.title']).toBeUndefined()
  })
})

describe('11 — görsel isteğinin süre sınırı', () => {
  let originalFetch
  beforeEach(() => {
    originalFetch = global.fetch
    clearErrorRegistry()
    try { localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  })
  afterEach(() => {
    global.fetch = originalFetch
    try { localStorage.removeItem(LANG_STORAGE_KEY) } catch { /* yok */ }
  })
  const hang = (init) => new Promise((_, reject) => {
    init.signal.addEventListener('abort', () => reject(init.signal.reason ?? Object.assign(new Error('aborted'), { name: 'AbortError' })))
  })

  it('yanıt gelmezse istek kesilir: REQUEST_TIMEOUT + timedOut (ağ hatası değil) → kendi metni; varsayılan 3 dakika', async () => {
    expect(IMAGE_FETCH_TIMEOUT_MS).toBe(180_000)
    global.fetch = vi.fn((_u, init) => hang(init))
    const res = await api.monitoring.resourceScanImage(100, 12, 'u', { timeoutMs: 20 })
    expect(res).toMatchObject({ success: false, status: 0, code: 'REQUEST_TIMEOUT', timedOut: true })
    expect(res.aborted).toBeUndefined()
    expect(serverProblemCode(res)).toBe('timeout')
    expect(serverProblemText(res, tr, 'tr-TR')).toMatch(/3 dakika içinde alınamadı/)
  })

  it('gövde okunurken süre dolarsa da zaman aşımı; çağıranın iptali ise "aborted" kalır (hata değil)', async () => {
    global.fetch = vi.fn(async (_u, init) => ({ ok: true, status: 200, headers: { get: () => null }, blob: () => hang(init) }))
    expect(await api.monitoring.resourceScanImage(100, 12, 'u', { timeoutMs: 20 })).toMatchObject({ code: 'REQUEST_TIMEOUT', timedOut: true })
    global.fetch = vi.fn((_u, init) => hang(init))
    const ctl = new AbortController()
    const p = api.monitoring.resourceScanImage(100, 12, 'u', { signal: ctl.signal, timeoutMs: 10_000 })
    ctl.abort()
    const out = await p
    expect(out).toMatchObject({ success: false, aborted: true })
    expect(out.timedOut).toBeUndefined()
  })
})

describe('sunucunun yeni sonuçları — site başına hız sınırı, https → http, aktarım doğrulaması, projeksiyon meşgul', () => {
  let originalFetch
  beforeEach(() => {
    originalFetch = global.fetch
    clearErrorRegistry()
    try { localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  })
  afterEach(() => {
    global.fetch = originalFetch
    try { localStorage.removeItem(LANG_STORAGE_KEY) } catch { /* yok */ }
  })
  const okResponse = (map = {}) => ({
    ok: true, status: 200, headers: { get: (h) => map[String(h).toLowerCase()] ?? null },
    blob: () => Promise.resolve(new Blob([new Uint8Array(10)], { type: 'image/jpeg' })),
  })

  it('istemci: X-Image-Tls-Verified true / false okunur; yok ya da tanınmayan değer → null (uydurulmaz)', async () => {
    global.fetch = vi.fn().mockResolvedValue(okResponse({ 'x-image-tls-verified': 'false' }))
    expect((await api.monitoring.resourceScanImage(100, 12, 'u')).tlsVerified).toBe(false)
    global.fetch = vi.fn().mockResolvedValue(okResponse({ 'x-image-tls-verified': 'TRUE' }))
    expect((await api.monitoring.resourceScanImage(100, 12, 'u')).tlsVerified).toBe(true)
    global.fetch = vi.fn().mockResolvedValue(okResponse({}))
    expect((await api.monitoring.resourceScanImage(100, 12, 'u')).tlsVerified).toBe(null)
    global.fetch = vi.fn().mockResolvedValue(okResponse({ 'x-image-tls-verified': 'belki' }))
    expect((await api.monitoring.resourceScanImage(100, 12, 'u')).tlsVerified).toBe(null)
  })

  it('metinler: site başına sınır "site yavaşlatılıyor" + bekleme; https → http kendi metni (chkfail değil); projeksiyon meşgul kendi kodu', () => {
    expect(serverProblemCode({ success: false, status: 429, code: 'RESOURCE_IMAGE_HOST_LIMITED', retryAfter: 17 })).toBe('RESOURCE_IMAGE_HOST_LIMITED')
    expect(serverProblemText({ success: false, status: 429, code: 'RESOURCE_IMAGE_HOST_LIMITED', retryAfter: 17 }, tr, 'tr-TR'))
      .toBe(tr('prs.it.srv.RESOURCE_IMAGE_HOST_LIMITED.wait', '17'))
    expect(serverProblemText({ success: false, status: 429, code: 'RESOURCE_IMAGE_HOST_LIMITED', retry_after_seconds: 31 }, tr, 'tr-TR')).toMatch(/yavaşlatıyor\. Yaklaşık 31 saniye/)
    const down = serverProblemText({ success: false, status: 502, code: 'RESOURCE_IMAGE_FETCH_FAILED', reason: 'TLS_DOWNGRADE' }, tr, 'tr-TR')
    expect(down).toBe(TR['prs.it.srv.RESOURCE_IMAGE_FETCH_FAILED.TLS_DOWNGRADE'])
    expect(down).not.toContain('TLS_DOWNGRADE')
    expect(serverProblemCode({ success: false, status: 429, code: 'RESOURCE_PROJECTION_BUSY', retry_after_seconds: 2 })).toBe('RESOURCE_PROJECTION_BUSY')
    expect(serverProblemText({ success: false, status: 429, code: 'RESOURCE_PROJECTION_BUSY', retryAfter: 2 }, tr, 'tr-TR')).toMatch(/başka not hesaplarını işliyor\. Yaklaşık 2 saniye/)
    // 413: sunucu Content-Length'ten de `bytes` yollayabilir — ölçülen boyut metinde
    expect(serverProblemText({ success: false, status: 413, code: 'RESOURCE_IMAGE_TOO_LARGE', max_bytes: 26214400, bytes: 41943040 }, tr, 'tr-TR')).toMatch(/40 MB/)
  })

  it('eşleşme listesi: aktarım doğrulaması sütunu evet / hayır / boş', () => {
    const sum = { bytes: 10, original: { bytes: 40 }, notes: [] }
    const rows = mappingRows([{ source: 'a', name: 'a.jpg', summary: sum, tlsVerified: true }, { source: 'b', name: 'b.jpg', summary: sum, tlsVerified: false },
      { source: 'c', name: 'c.jpg', summary: sum }], tr)
    expect(rows[0].at(-1)).toBe('Aktarım doğrulandı (TLS)')
    expect(rows.slice(1).map((r) => r.at(-1))).toEqual(['evet', 'hayır', ''])
  })

  it('depo: doğrulanamayan aktarım satırın özgün bilgisine, araca gönderilen kayda ve toplu / araç ZIP eşleşme listesine taşınır', async () => {
    const fetchImage = vi.fn(async (_s, url) => ({ success: true, blob: blobOf(4000), name: url.split('/').pop(), type: 'jpeg', width: 3000, height: 2000,
      tlsVerified: url.includes('guvensiz') ? false : url.includes('http-') ? null : true }))
    const { store, download } = make({ fetchImage })
    const bad = img('guvensiz.jpg')
    await store.downloadOriginal(SCAN, bad)
    expect(store.scanEntry(SCAN, bad.url).original.tlsVerified).toBe(false)
    await store.sendToTool(SCAN, img('guvensiz-2.jpg'))
    expect(store.queue()[0].tlsVerified).toBe(false)
    const out = await store.startBulk({ scanId: SCAN, items: [img('guvensiz-3.jpg'), img('iyi.jpg'), img('http-x.jpg')], canRun: true, itemsKept: true, t: tr, locale: 'tr-TR', baseName: 'site' })
    expect(out.ok).toBe(true)
    const zip = await unzip(download.mock.calls.at(-1)[1])
    const csvName = Object.keys(zip).find((n) => n.endsWith('.csv'))
    const csv = new TextDecoder().decode(zip[csvName])
    expect(csv).toContain('Aktarım doğrulandı (TLS)')
    const line = (frag) => csv.split('\r\n').find((l) => l.includes(frag))
    expect(line('guvensiz-3.jpg').endsWith(',hayır')).toBe(true)
    expect(line('iyi.jpg').endsWith(',evet')).toBe(true)
    expect(line('http-x.jpg').endsWith(',')).toBe(true)
  })

  it('toplu indirme: site başına sınır (429 RESOURCE_IMAGE_HOST_LIMITED) RATE_LIMITED gibi beklenir, sonra alınır', async () => {
    let first = true
    const fetchImage = vi.fn(async (_s, url) => {
      if (first) { first = false; return { success: false, status: 429, code: 'RESOURCE_IMAGE_HOST_LIMITED', retryAfter: 9, error: 'x' } }
      return { success: true, blob: blobOf(4000), name: url.split('/').pop(), type: 'jpeg', width: 3000, height: 2000 }
    })
    const waits = []
    const download = vi.fn(() => true)
    const store = createImageToolStore({ fetchImage, engine: fakeEngine(), download, sleep: async (ms) => { waits.push(ms); return true } })
    const out = await store.startBulk({ scanId: SCAN, items: [img('a.jpg')], canRun: true, itemsKept: true, t: tr, locale: 'tr-TR', baseName: 'site' })
    expect(out.ok).toBe(true)
    expect(waits).toEqual([9000])
    expect(fetchImage).toHaveBeenCalledTimes(2)
    expect(download).toHaveBeenCalledTimes(1)
  })
})
