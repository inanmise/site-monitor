import { describe, it, expect, vi } from 'vitest'
import { unzipSync, strFromU8 } from 'fflate'
import { TR } from '../i18n/tr.js'
import { createImageToolStore } from '../components/page/resourcescan/imagetool/imageToolStore.js'
import { runImageBulk } from '../components/page/resourcescan/imagetool/imageBulk.js'
import { createZipWriter, bytesOfBlob } from '../components/page/resourcescan/imagetool/imageZip.js'
import { isImageWorkBusy, registerImageStore, resetImageWorkForTests } from '../components/page/resourcescan/imagetool/imageToolBusy.js'
import { DEFAULT_SETTINGS, TOOL_MAX_FILES, optimizeSettings, resizeSettings } from '../components/page/resourcescan/imagetool/imageToolModel.js'

const tr = (key, ...args) => String(TR[key] ?? key).replace(/\{(\d+)\}/g, (_, i) => String(args[Number(i)] ?? ''))
const blobOf = (n, type = 'image/jpeg') => new Blob([new Uint8Array(n).fill(7)], { type })
const fileOf = (name, n = 4000) => new File([new Uint8Array(n).fill(3)], name, { type: 'image/png' })
const engineError = (code, params = {}) => Object.assign(new Error(code), { name: 'EngineError', code, params })
const unzip = async (blob) => unzipSync(new Uint8Array(await blob.arrayBuffer()))
const deferred = () => { let resolve; const promise = new Promise((r) => { resolve = r }); return { promise, resolve } }
const img = (name, bytes = 600 * 1024, over = {}) => ({ category: 'IMAGE', url: `https://ornek.example.com/img/${name}`, bytes, status: 200, ok: true, verdict: 'ok', width: 3000, height: 2000, ...over })
const SCAN = 12

/** Sahte motor: baytı dörtte bire indirir; ölçeklemede istenen genişliği verir. */
function fakeEngine(over = {}) {
  const calls = []
  return {
    calls,
    engineLimits: async () => ({ maxInputBytes: 25 * 1024 * 1024, maxPixels: 50_000_000, maxSide: 30_000 }),
    engineSupport: async () => ({ worker: true, webp: true }),
    inspectImage: async (blob) => ({ type: 'png', width: 3000, height: 2000, bytes: blob.size, hasAlpha: false, animated: false }),
    processImage: async (blob, options, extra) => {
      calls.push({ size: blob.size, options })
      extra?.onProgress?.('encode', 0.5)
      const resized = options.resize.method !== 'none'
      const width = resized ? (options.resize.width ?? 1500) : 3000
      const type = options.format === 'keep' || options.format === 'smallest' ? 'jpeg' : options.format
      const bytes = Math.max(1, Math.floor(blob.size / 4))
      return {
        blob: blobOf(bytes, `image/${type}`), type, mime: `image/${type}`, width, height: Math.round((width * 2) / 3), bytes,
        original: { type: 'jpeg', width: 3000, height: 2000, bytes: blob.size, hasAlpha: false, animated: false, metadata: { copyright: false, creation: false, location: false } },
        savingPct: 75, quality: 80, colors: null, similarity: 0.99, resized, cropped: false, kept: false, notes: resized ? [] : ['ORIENTATION_APPLIED'],
      }
    },
    decodeForPreview: async () => ({ width: 10, height: 10, close() {} }),
    ...over,
  }
}
const okFetch = () => vi.fn(async (_scan, url) => ({ success: true, blob: blobOf(4000), name: `${url.split('/').pop().split('.')[0]}.jpg`, type: 'jpeg', width: 3000, height: 2000 }))
function make(over = {}) {
  const download = vi.fn(() => true)
  const fetchImage = over.fetchImage || okFetch()
  const engine = over.engine || fakeEngine()
  const store = createImageToolStore({ fetchImage, engine, download, sleep: over.sleep })
  return { store, download, fetchImage, engine }
}

describe('görsel deposu — tarama görseli: özgün / optimize indirme, önbellek, hata, iptal', () => {
  it('özgün dosya: sunucudan bir kez alınır, sunucunun verdiği adla iner; ikinci tık yeniden istemez', async () => {
    const { store, download, fetchImage } = make()
    const item = img('hero.jpg')
    expect(await store.downloadOriginal(SCAN, item)).toEqual({ ok: true })
    expect(fetchImage).toHaveBeenCalledTimes(1)
    expect(fetchImage.mock.calls[0].slice(0, 2)).toEqual([SCAN, item.url])
    expect(download).toHaveBeenCalledTimes(1)
    expect(download.mock.calls[0][0]).toBe('hero.jpg')
    expect(download.mock.calls[0][1].size).toBe(4000)
    expect(store.scanEntry(SCAN, item.url)).toMatchObject({ state: 'idle', problem: null, original: { bytes: 4000, type: 'jpeg', name: 'hero.jpg' } })
    await store.downloadOriginal(SCAN, item)
    expect(fetchImage).toHaveBeenCalledTimes(1)       // bellekten
    expect(download).toHaveBeenCalledTimes(2)
  })

  it('optimize: gerçek önce → sonra özeti yazılır, "-opt" adıyla iner; AYNI ayara ikinci tık yeniden işlemez, anında indirir', async () => {
    const { store, download, fetchImage, engine } = make()
    const item = img('hero.jpg')
    const seen = []
    store.on(`scan:${SCAN}\n${item.url}`, () => seen.push(store.scanEntry(SCAN, item.url).state))
    expect(await store.optimizeScanImage(SCAN, item, optimizeSettings(), { mode: 'optimized' })).toEqual({ ok: true })
    const e = store.scanEntry(SCAN, item.url)
    expect(e.state).toBe('done')
    expect(e.result).toMatchObject({ bytes: 1000, type: 'jpeg', savingPct: 75, name: 'hero-opt.jpg', original: { bytes: 4000 } })
    expect(seen).toContain('fetching')
    expect(seen).toContain('processing')
    expect(download.mock.calls[0][0]).toBe('hero-opt.jpg')
    expect(download.mock.calls[0][1].size).toBe(1000)
    expect(engine.calls).toHaveLength(1)
    // ikinci tık: önbellek
    expect(await store.optimizeScanImage(SCAN, item, optimizeSettings(), { mode: 'optimized' })).toEqual({ ok: true, cached: true })
    expect(engine.calls).toHaveLength(1)
    expect(fetchImage).toHaveBeenCalledTimes(1)
    expect(download).toHaveBeenCalledTimes(2)
    // başka ayar: yeniden işlenir ama özgün yeniden İSTENMEZ; ad genişliği taşır
    await store.optimizeScanImage(SCAN, item, resizeSettings(1200), { mode: 'width' })
    expect(engine.calls).toHaveLength(2)
    expect(fetchImage).toHaveBeenCalledTimes(1)
    expect(download.mock.calls[2][0]).toBe('hero-1200w.jpg')
    expect(store.scanEntry(SCAN, item.url).result).toMatchObject({ width: 1200, resized: true })
    expect(store.processedFor(SCAN)).toHaveLength(1)
    expect(store.processedFor(99)).toEqual([])
    expect(store.scanBlobs(SCAN, item.url)).toMatchObject({ summary: { width: 1200 } })
  })

  it('sunucu hatası o satıra yazılır (kod + yanıt), indirme başlamaz; öteki görsel etkilenmez', async () => {
    const fetchImage = vi.fn(async (_s, url) => (url.endsWith('kirik.jpg')
      ? { success: false, status: 502, code: 'RESOURCE_IMAGE_FETCH_FAILED', http_status: 404, error: 'x' }
      : { success: true, blob: blobOf(4000), name: 'iyi.jpg', type: 'jpeg', width: 3000, height: 2000 }))
    const { store, download } = make({ fetchImage })
    expect(await store.optimizeScanImage(SCAN, img('kirik.jpg'), optimizeSettings())).toEqual({ ok: false })
    const bad = store.scanEntry(SCAN, img('kirik.jpg').url)
    expect(bad.state).toBe('failed')
    expect(bad.problem).toMatchObject({ kind: 'server', res: { code: 'RESOURCE_IMAGE_FETCH_FAILED', http_status: 404 } })
    expect(bad.result).toBe(null)
    expect(download).not.toHaveBeenCalled()
    expect(await store.optimizeScanImage(SCAN, img('iyi.jpg'), optimizeSettings())).toEqual({ ok: true })
    expect(store.scanEntry(SCAN, img('iyi.jpg').url).state).toBe('done')
  })

  it('motor hatası kendi koduyla yazılır; tanınmayan fırlatma "other"; geçersiz ayar motoru hiç çağırmaz', async () => {
    const engine = fakeEngine({ processImage: vi.fn(async () => { throw engineError('DECODE_FAILED') }) })
    const { store, download } = make({ engine })
    await store.optimizeScanImage(SCAN, img('a.jpg'), optimizeSettings())
    expect(store.scanEntry(SCAN, img('a.jpg').url)).toMatchObject({ state: 'failed', problem: { kind: 'engine', code: 'DECODE_FAILED' } })
    engine.processImage.mockImplementationOnce(async () => { throw new TypeError('beklenmeyen') })
    await store.optimizeScanImage(SCAN, img('b.jpg'), optimizeSettings())
    expect(store.scanEntry(SCAN, img('b.jpg').url).problem).toMatchObject({ kind: 'engine', code: 'other' })
    engine.processImage.mockClear()
    await store.optimizeScanImage(SCAN, img('c.jpg'), { ...DEFAULT_SETTINGS, method: 'scale', width: '' })
    expect(store.scanEntry(SCAN, img('c.jpg').url).problem).toMatchObject({ kind: 'engine', code: 'INVALID_OPTIONS' })
    expect(engine.processImage).not.toHaveBeenCalled()
    expect(download).not.toHaveBeenCalled()
  })

  it('tarayıcı indirmeyi başlatamazsa başarı SAYILMAZ: sonuç bellekte kalır, satır "indirme başlatılamadı" der', async () => {
    const { store, download } = make()
    download.mockReturnValue(false)
    expect(await store.optimizeScanImage(SCAN, img('a.jpg'), optimizeSettings())).toEqual({ ok: false })
    const e = store.scanEntry(SCAN, img('a.jpg').url)
    expect(e.state).toBe('done')
    expect(e.result).not.toBe(null)
    expect(e.problem).toEqual({ kind: 'client', code: 'download' })
  })

  it('iptal: süren istek kesilir, satır başa döner; GEÇ gelen yanıt hiçbir şey yazmaz / indirmez', async () => {
    const gate = deferred()
    let signalSeen = null
    const fetchImage = vi.fn(async (_s, _u, signal) => { signalSeen = signal; return gate.promise })
    const { store, download } = make({ fetchImage })
    const item = img('yavas.jpg')
    const run = store.optimizeScanImage(SCAN, item, optimizeSettings())
    await vi.waitFor(() => expect(fetchImage).toHaveBeenCalledTimes(1))
    expect(store.scanEntry(SCAN, item.url).state).toBe('fetching')
    store.cancelScan(SCAN, item.url)
    expect(signalSeen.aborted).toBe(true)
    expect(store.scanEntry(SCAN, item.url)).toMatchObject({ state: 'idle', result: null })
    gate.resolve({ success: true, blob: blobOf(4000), name: 'yavas.jpg', type: 'jpeg' })
    expect(await run).toMatchObject({ ok: false, aborted: true })
    expect(store.scanEntry(SCAN, item.url).state).toBe('idle')
    expect(download).not.toHaveBeenCalled()
  })

  it('sunucudan aynı anda en çok 2 görsel istenir (kişi başına sunucu sınırı)', async () => {
    const gates = []
    let inFlight = 0
    let peak = 0
    const fetchImage = vi.fn(async () => {
      inFlight += 1
      peak = Math.max(peak, inFlight)
      const g = deferred()
      gates.push(g)
      const res = await g.promise
      inFlight -= 1
      return res
    })
    const { store } = make({ fetchImage })
    const runs = ['a.jpg', 'b.jpg', 'c.jpg', 'd.jpg'].map((n) => store.downloadOriginal(SCAN, img(n)))
    await vi.waitFor(() => expect(fetchImage).toHaveBeenCalledTimes(2))
    await Promise.resolve()
    expect(fetchImage).toHaveBeenCalledTimes(2)        // üçüncü bekliyor
    const ok = { success: true, blob: blobOf(10), name: 'x.jpg', type: 'jpeg' }
    gates[0].resolve(ok)
    await vi.waitFor(() => expect(fetchImage).toHaveBeenCalledTimes(3))
    gates[1].resolve(ok)
    await vi.waitFor(() => expect(fetchImage).toHaveBeenCalledTimes(4))
    gates[2].resolve(ok)
    gates[3].resolve(ok)
    await Promise.all(runs)
    expect(peak).toBe(2)
  })

  it('dispose: süren işler kesilir, depo boşalır; sonradan gelen yanıt yazılmaz', async () => {
    const gate = deferred()
    const { store, download } = make({ fetchImage: vi.fn(async () => gate.promise) })
    const run = store.optimizeScanImage(SCAN, img('a.jpg'), optimizeSettings())
    await Promise.resolve()
    store.dispose()
    expect(store.isDisposed()).toBe(true)
    gate.resolve({ success: true, blob: blobOf(10), name: 'a.jpg', type: 'jpeg' })
    await run
    expect(download).not.toHaveBeenCalled()
    expect(store.queue()).toEqual([])
  })
})

describe('görsel deposu — araç kuyruğu', () => {
  const allDone = (store) => store.queue().every((e) => e.state === 'done' || e.state === 'failed')

  it('yüklenen dosyalar sırayla işlenir; sonuç özeti ve yerine koymaya hazır ad; tek dosya indirme', async () => {
    const { store, download, engine } = make()
    expect(store.addFiles([fileOf('kapak.png'), fileOf('logo.png', 8000)])).toEqual({ added: 2, rejected: 0 })
    expect(store.queue().map((e) => e.state)).toEqual(['waiting', 'waiting'])
    await vi.waitFor(() => expect(allDone(store)).toBe(true))
    const [a, b] = store.queue()
    expect(a).toMatchObject({ source: 'upload', name: 'kapak.png', base: 'kapak', bytes: 4000, state: 'done', result: { bytes: 1000, name: 'kapak.jpg' }, info: { width: 3000, height: 2000, type: 'png' } })
    expect(b.result).toMatchObject({ bytes: 2000, name: 'logo.jpg' })
    expect(engine.calls.map((c) => c.size)).toEqual([4000, 8000])     // sıra korunur
    expect(store.downloadEntry(a.id)).toBe(true)
    expect(download).toHaveBeenCalledWith('kapak.jpg', expect.any(Blob))
    expect(store.entryBlobs(a.id).result.size).toBe(1000)
    expect(store.downloadEntry(9999)).toBe(false)
  })

  it(`kuyruk en çok ${TOOL_MAX_FILES} dosya alır: fazlası EKLENMEZ ve sayısı döner; motor sınırından büyük dosya nedeniyle "başarısız" girer`, async () => {
    const { store, engine } = make()
    const many = Array.from({ length: TOOL_MAX_FILES + 2 }, (_, i) => fileOf(`g${i}.png`, 100))
    expect(store.addFiles(many)).toEqual({ added: TOOL_MAX_FILES, rejected: 2 })
    expect(store.addFiles([fileOf('bir-daha.png')])).toEqual({ added: 0, rejected: 1 })
    await vi.waitFor(() => expect(allDone(store)).toBe(true))
    expect(store.queue()).toHaveLength(TOOL_MAX_FILES)
    expect(engine.calls).toHaveLength(TOOL_MAX_FILES)
    store.clearQueue()
    expect(store.queue()).toEqual([])
    // 25 MB üstü: işlenmez, nedeni yazılır (gerçek bayt ayırmadan — yalnız boyut alanı okunur)
    engine.calls.length = 0
    expect(store.addFiles([{ name: 'dev.png', size: 26 * 1024 * 1024 }, fileOf('ufak.png')])).toEqual({ added: 2, rejected: 0 })
    await vi.waitFor(() => expect(allDone(store)).toBe(true))
    const [big, small] = store.queue()
    expect(big).toMatchObject({ state: 'failed', problem: { kind: 'client', code: 'tooLargeFile', params: { maxBytes: 25 * 1024 * 1024 } } })
    expect(small.state).toBe('done')
    expect(engine.calls).toHaveLength(1)
  })

  it('bir görselin hatası ötekileri durdurmaz; "yeniden dene" yalnız o görseli işler; kaldırılan satır gider', async () => {
    let failOnce = true
    const base = fakeEngine()
    const engine = fakeEngine({
      processImage: vi.fn(async (blob, options, extra) => {
        if (blob.size === 5000 && failOnce) { failOnce = false; throw engineError('ENCODE_FAILED') }
        return base.processImage(blob, options, extra)
      }),
    })
    const { store } = make({ engine })
    store.addFiles([fileOf('a.png'), fileOf('bozuk.png', 5000), fileOf('c.png')])
    await vi.waitFor(() => expect(allDone(store)).toBe(true))
    expect(store.queue().map((e) => e.state)).toEqual(['done', 'failed', 'done'])
    const bad = store.queue()[1]
    expect(bad.problem).toMatchObject({ kind: 'engine', code: 'ENCODE_FAILED' })
    store.retryEntry(bad.id)
    await vi.waitFor(() => expect(store.queue()[1].state).toBe('done'))
    expect(store.queue()[1].problem).toBe(null)
    store.removeEntry(store.queue()[0].id)
    expect(store.queue().map((e) => e.name)).toEqual(['bozuk.png', 'c.png'])
    expect(store.entryBlobs(9999)).toBe(null)
  })

  it('başlık okunamayan (desteklenmeyen) dosya kendi nedeniyle başarısız olur', async () => {
    const engine = fakeEngine({ inspectImage: vi.fn(async () => { throw engineError('UNSUPPORTED_FORMAT') }) })
    const { store } = make({ engine })
    store.addFiles([fileOf('belge.pdf')])
    await vi.waitFor(() => expect(store.queue()[0].state).toBe('failed'))
    expect(store.queue()[0].problem).toMatchObject({ kind: 'engine', code: 'UNSUPPORTED_FORMAT' })
  })

  it('geçersiz ayarda görseller BEKLER (işlenmez); ayar düzelince işlenir; ayar değişince sonuç "bayat" sayılır ve yeniden işlenebilir', async () => {
    const { store, engine } = make()
    await store.ensureSupport()
    store.setSettings({ method: 'scale', width: '' })
    store.addFiles([fileOf('a.png')])
    await new Promise((r) => { setTimeout(r, 20) })
    expect(store.queue()[0].state).toBe('waiting')
    expect(engine.calls).toHaveLength(0)
    expect(store.currentKey(store.queue()[0])).toBe(null)
    store.setSettings({ width: '800' })
    await vi.waitFor(() => expect(store.queue()[0].state).toBe('done'))
    expect(store.queue()[0].result).toMatchObject({ width: 800, name: 'a-800w.jpg' })
    expect(store.isStale(store.queue()[0])).toBe(false)
    store.setSettings({ format: 'webp' })
    expect(store.isStale(store.queue()[0])).toBe(true)
    expect(engine.calls).toHaveLength(1)                 // kendiliğinden yeniden işlenmez
    store.reprocess()
    await vi.waitFor(() => expect(store.queue()[0].result?.type).toBe('webp'))
    expect(store.isStale(store.queue()[0])).toBe(false)
    expect(engine.calls).toHaveLength(2)
  })

  it('"Tümünü indir (ZIP)": görseller + eşleşme listesi; aynı ad ikinci kez "-2" alır; indirilemezse başarı sayılmaz', async () => {
    const { store, download } = make()
    store.addFiles([fileOf('kapak.png'), fileOf('kapak.png', 8000)])
    await vi.waitFor(() => expect(allDone(store)).toBe(true))
    const res = await store.zipQueue({ t: tr, name: 'gorseller.zip' })
    expect(res).toMatchObject({ ok: true, files: 2, downloadFailed: false })
    expect(download).toHaveBeenCalledTimes(1)
    expect(download.mock.calls[0][0]).toBe('gorseller.zip')
    const files = await unzip(download.mock.calls[0][1])
    expect(Object.keys(files).sort()).toEqual(['eslesme.csv', 'kapak-2.jpg', 'kapak.jpg'])
    expect(files['kapak.jpg'].length).toBe(1000)
    expect(files['kapak-2.jpg'].length).toBe(2000)
    const csv = strFromU8(files['eslesme.csv'])
    expect(csv).toContain('kapak.png,kapak.jpg,4000,1000,')
    expect(csv).toContain('kapak.png,kapak-2.jpg,8000,2000,')
    expect(store.zip()).toBe(null)
    expect(store.isBusy()).toBe(false)
    download.mockReturnValue(false)
    expect(await store.zipQueue({ t: tr, name: 'gorseller.zip' })).toMatchObject({ ok: false, downloadFailed: true })
    store.clearQueue()
    expect(await store.zipQueue({ t: tr, name: 'x.zip' })).toEqual({ ok: false, empty: true })
  })

  it('tarama görseli araca gönderilir: kendi ayarıyla işlenir, sonuç analizin "bu sekmede işlenen" kayıtlarına da yazılır', async () => {
    const { store, fetchImage } = make()
    const item = img('hero.jpg')
    const res = await store.sendToTool(SCAN, item, resizeSettings(1200))
    expect(res).toMatchObject({ ok: true })
    await vi.waitFor(() => expect(store.queue()[0]?.state).toBe('done'))
    expect(store.queue()[0]).toMatchObject({ source: 'scan', name: 'hero.jpg', scan: { scanId: SCAN, url: item.url }, result: { width: 1200 } })
    expect(fetchImage).toHaveBeenCalledTimes(1)
    expect(store.processedFor(SCAN)).toEqual([{ url: item.url, summary: expect.objectContaining({ width: 1200, bytes: 1000 }) }])
    // satırın kendi ayarı kaldırılınca panel ayarı geçerli olur → bayat
    store.setOwnSettings(store.queue()[0].id, null)
    expect(store.isStale(store.queue()[0])).toBe(true)
    // sunucu görseli vermezse kuyruğa girmez, neden satıra yazılır
    const bad = make({ fetchImage: vi.fn(async () => ({ success: false, status: 409, code: 'RESOURCE_IMAGE_URL_MASKED' })) })
    expect(await bad.store.sendToTool(SCAN, item)).toEqual({ ok: false })
    expect(bad.store.queue()).toEqual([])
    expect(bad.store.scanEntry(SCAN, item.url).problem.res.code).toBe('RESOURCE_IMAGE_URL_MASKED')
  })

  it('kuyruk doluyken tarama görseli gönderilmez (dolu bilgisi döner, sunucudan istenmez)', async () => {
    const { store, fetchImage } = make()
    store.addFiles(Array.from({ length: TOOL_MAX_FILES }, (_, i) => fileOf(`g${i}.png`, 50)))
    expect(await store.sendToTool(SCAN, img('hero.jpg'))).toEqual({ ok: false, full: true })
    expect(fetchImage).not.toHaveBeenCalled()
  })

  it('ilerleme yalnız o satırın konusuna yayılır (kuyruk konusu her yüzde değişiminde tetiklenmez)', async () => {
    const gate = deferred()
    let queueEvents = 0
    let rowEvents = 0
    let queueEventsDuringProgress = null
    const engine = fakeEngine({
      processImage: vi.fn(async (blob, options, extra) => {
        const q0 = queueEvents
        for (let i = 1; i <= 20; i += 1) extra.onProgress('encode', i / 20)
        queueEventsDuringProgress = queueEvents - q0
        await gate.promise
        return fakeEngine().processImage(blob, options, {})
      }),
    })
    const { store } = make({ engine })
    store.on('queue', () => { queueEvents += 1 })
    store.addFiles([fileOf('a.png')])
    const id = store.queue()[0].id
    store.on(`qp:${id}`, () => { rowEvents += 1 })
    await vi.waitFor(() => expect(engine.processImage).toHaveBeenCalled())
    expect(rowEvents).toBeGreaterThanOrEqual(20)
    expect(store.entryProgress(id)).toEqual({ phase: 'encode', fraction: 1 })
    expect(queueEventsDuringProgress).toBe(0)            // 20 ilerleme adımı kuyruğu yeniden çizdirmedi
    gate.resolve()
    await vi.waitFor(() => expect(store.queue()[0].state).toBe('done'))
    expect(store.entryProgress(id)).toEqual({ phase: null, fraction: null })
  })
})

describe('toplu indirme koşucusu (saf) — sınırlar', () => {
  const plan = (n) => Array.from({ length: n }, (_, i) => img(`b${i}.jpg`))
  const okRes = { success: true, blob: blobOf(100) }
  const processOne = async () => ({ ok: true, result: { x: 1 } })

  it('aynı anda en çok 2 istek; sıra korunur; her dosya gelir gelmez onFile\'a verilir', async () => {
    let inFlight = 0
    let peak = 0
    const order = []
    const res = await runImageBulk(plan(6), {
      fetchOriginal: async (item) => { inFlight += 1; peak = Math.max(peak, inFlight); await new Promise((r) => { setTimeout(r, 2) }); inFlight -= 1; return { ...okRes, item } },
      processOne,
      onFile: ({ index }) => { order.push(index); return { name: `f${index}` } },
    })
    expect(peak).toBe(2)
    expect(res.aborted).toBe(false)
    expect(res.outcomes.map((o) => o.ok)).toEqual([true, true, true, true, true, true])
    expect(res.outcomes.map((o) => o.entry.name)).toEqual(['f0', 'f1', 'f2', 'f3', 'f4', 'f5'])
    expect(order.slice().sort()).toEqual([0, 1, 2, 3, 4, 5])
  })

  it('429: sunucunun Retry-After önerisine uyulur (yoksa 2 sn); deneme sayısı ve TOPLAM bekleme sınırlı — sonsuz bekleme yok', async () => {
    const sleep = vi.fn(async () => true)
    const seq = [{ success: false, status: 429, code: 'RATE_LIMITED', retryAfter: 7 }, { success: false, status: 429, code: 'RESOURCE_IMAGE_BUSY' }, okRes]
    const fetchOriginal = vi.fn(async () => seq.shift())
    const progress = []
    const res = await runImageBulk(plan(1), { fetchOriginal, processOne, sleep, onProgress: (p) => progress.push(p) })
    expect(sleep.mock.calls.map((c) => c[0])).toEqual([7000, 2000])
    expect(res.outcomes[0].ok).toBe(true)
    expect(progress.some((p) => p.waiting && p.waitMs === 7000)).toBe(true)
    // deneme tavanı: hep 429 → maxRetries kez beklenir, sonra görsel eksik sayılır
    sleep.mockClear()
    const always = vi.fn(async () => ({ success: false, status: 429, code: 'RATE_LIMITED', retryAfter: 1 }))
    const gaveUp = await runImageBulk(plan(1), { fetchOriginal: always, processOne, sleep, maxRetries: 3 })
    expect(always).toHaveBeenCalledTimes(4)
    expect(sleep).toHaveBeenCalledTimes(3)
    expect(gaveUp.outcomes[0]).toMatchObject({ ok: false, reason: 'server', res: { status: 429 } })
    // toplam bekleme bütçesi: 60 sn öneri, bütçe 100 sn → yalnız 1 bekleme sığar
    sleep.mockClear()
    const slow = vi.fn(async () => ({ success: false, status: 429, code: 'RATE_LIMITED', retryAfter: 60 }))
    await runImageBulk(plan(1), { fetchOriginal: slow, processOne, sleep, waitBudgetMs: 100_000 })
    expect(sleep.mock.calls.map((c) => c[0])).toEqual([60_000])
    // tek bekleme tavanı
    sleep.mockClear()
    const huge = [{ success: false, status: 429, retryAfter: 9999 }, okRes]
    await runImageBulk(plan(1), { fetchOriginal: async () => huge.shift(), processOne, sleep })
    expect(sleep.mock.calls[0][0]).toBe(60_000)
  })

  it('401 / 403: durulur — başlanmamış görseller İSTENMEZ (skipped)', async () => {
    const fetchOriginal = vi.fn(async (item) => (item.url.endsWith('b1.jpg') ? { success: false, status: 403, code: 'FORBIDDEN' } : okRes))
    const res = await runImageBulk(plan(5), { fetchOriginal, processOne, concurrency: 1 })
    expect(res.stopped).toBe(403)
    expect(fetchOriginal).toHaveBeenCalledTimes(2)
    expect(res.outcomes.map((o) => (o.ok ? 'ok' : o.reason))).toEqual(['ok', 'server', 'skipped', 'skipped', 'skipped'])
    expect(res.outcomes[2].status).toBe(403)
  })

  it('bir görselin alınamaması / işlenememesi ötekileri durdurmaz', async () => {
    const res = await runImageBulk(plan(4), {
      fetchOriginal: async (item) => (item.url.endsWith('b1.jpg') ? { success: false, status: 502, code: 'RESOURCE_IMAGE_FETCH_FAILED' } : okRes),
      processOne: async (item) => (item.url.endsWith('b2.jpg') ? { ok: false, problem: { kind: 'engine', code: 'DECODE_FAILED' } } : (item.url.endsWith('b3.jpg') ? Promise.reject(new Error('patladı')) : { ok: true, result: {} })),
    })
    expect(res.outcomes.map((o) => (o.ok ? 'ok' : o.reason))).toEqual(['ok', 'server', 'process', 'process'])
    expect(res.outcomes[2].problem.code).toBe('DECODE_FAILED')
    expect(res.outcomes[3].problem.code).toBe('other')
  })

  it('iptal: süren iş kesilir, { aborted } döner (çağıran hiçbir şey indirmez); bekleme sırasında iptal de aynı', async () => {
    const ctl = new AbortController()
    const onFile = vi.fn()
    const p = runImageBulk(plan(4), {
      signal: ctl.signal, onFile,
      fetchOriginal: async () => { await new Promise((r) => { setTimeout(r, 5) }); return okRes },
      processOne,
    })
    ctl.abort()
    expect(await p).toEqual({ aborted: true, stopped: null, outcomes: [] })
    const ctl2 = new AbortController()
    const p2 = runImageBulk(plan(1), {
      signal: ctl2.signal, fetchOriginal: async () => ({ success: false, status: 429, retryAfter: 30 }), processOne,
      sleep: async (_ms, signal) => new Promise((r) => { signal.addEventListener('abort', () => r(false)) }),
    })
    await new Promise((r) => { setTimeout(r, 5) })
    ctl2.abort()
    expect((await p2).aborted).toBe(true)
  })

  it('arşive yazılamazsa (onFile fırlatır) koşu hata ile biter — yarım ZIP başarı sayılmaz', async () => {
    await expect(runImageBulk(plan(2), { fetchOriginal: async () => okRes, processOne, onFile: () => { throw new Error('ZIP yazılamadı') } })).rejects.toThrow('ZIP yazılamadı')
  })

  it('boş plan: hiçbir istek yok', async () => {
    const fetchOriginal = vi.fn()
    expect(await runImageBulk([], { fetchOriginal, processOne })).toEqual({ aborted: false, stopped: null, outcomes: [] })
    expect(fetchOriginal).not.toHaveBeenCalled()
  })
})

describe('görsel deposu — toplu indirme (büyük görseller → tek ZIP)', () => {
  const items = () => [img('a.jpg', 900 * 1024), img('b.jpg', 700 * 1024), img('c.jpg', 500 * 1024), img('ufak.jpg', 10 * 1024), img('logo.svg', 400 * 1024)]
  const args = (over = {}) => ({ scanId: SCAN, items: items(), canRun: true, itemsKept: true, resize: false, format: 'keep', t: tr, locale: 'tr-TR', baseName: 'Kurumsal Site', ...over })

  it('bir görsel alınamasa da ZIP iner: görseller + eslesme.csv + missing.txt (alınamayan ve indirilemeyenler nedeniyle)', async () => {
    const fetchImage = vi.fn(async (_s, url) => (url.endsWith('b.jpg')
      ? { success: false, status: 502, code: 'RESOURCE_IMAGE_FETCH_FAILED', http_status: 503 }
      : { success: true, blob: blobOf(4000), name: `${url.split('/').pop().split('.')[0]}.jpg`, type: 'jpeg', width: 3000, height: 2000 }))
    const { store, download } = make({ fetchImage })
    const snaps = []
    store.on('bulk', () => snaps.push(store.bulk()))
    const out = await store.startBulk(args())
    expect(out.ok).toBe(true)
    expect(out.result).toMatchObject({ kind: 'partial', files: 2, missing: 2, savedBytes: 6000 })
    expect(store.bulk()).toMatchObject({ state: 'finished', result: { kind: 'partial' } })
    expect(snaps.some((s) => s?.state === 'running' && s.total === 3)).toBe(true)
    expect(snaps.some((s) => s?.packing)).toBe(true)
    expect(download).toHaveBeenCalledTimes(1)
    expect(download.mock.calls[0][0]).toMatch(/^kurumsal-site-optimize-gorseller-\d{8}-\d{4}\.zip$/)
    const files = await unzip(download.mock.calls[0][1])
    expect(Object.keys(files).sort()).toEqual(['a.jpg', 'c.jpg', 'eslesme.csv', 'missing.txt'])
    const csv = strFromU8(files['eslesme.csv'])
    expect(csv).toContain('https://ornek.example.com/img/a.jpg,a.jpg,4000,1000,')
    const missing = strFromU8(files['missing.txt'])
    expect(missing).toContain('https://ornek.example.com/img/b.jpg — ')
    expect(missing).toContain('HTTP 503')
    expect(missing).toContain('https://ornek.example.com/img/logo.svg — ')       // SVG: baştan indirilemez, nedeniyle listelenir
    expect(missing).not.toContain('ufak.jpg')                                     // küçük görsel kapsam dışı (eksik değil)
    // işlenenler "bu sekmede işlenen" kayıtlarına yazılır (dışa aktarım, projeksiyon)
    expect(store.processedFor(SCAN).map((p) => p.url).sort()).toEqual(['https://ornek.example.com/img/a.jpg', 'https://ornek.example.com/img/c.jpg'])
    expect(store.isBusy()).toBe(false)
    store.dismissBulk()
    expect(store.bulk()).toBe(null)
  })

  it('hepsi başarılı: "done"; önerilen genişlik açıkken büyük görsel o genişliğe küçültülür', async () => {
    const { store, download, engine } = make()
    const out = await store.startBulk(args({ resize: true, items: [img('a.jpg', 900 * 1024, { suggested_width: 1200, suggested_reason: 'declared', declared_width: 600 }), img('b.jpg', 300 * 1024, { width: 1000 })] }))
    expect(out.result).toMatchObject({ kind: 'done', files: 2, missing: 0 })
    expect(engine.calls.map((c) => c.options.resize)).toEqual(expect.arrayContaining([{ method: 'scale', width: 1200 }, { method: 'none' }]))
    const files = await unzip(download.mock.calls[0][1])
    expect(Object.keys(files).sort()).toEqual(['a.jpg', 'b.jpg', 'eslesme.csv'])   // missing.txt yok
  })

  it('iptal: HİÇBİR ŞEY indirilmez; durum "cancelled"; yeni iş başlatılabilir', async () => {
    const gate = deferred()
    const { store, download } = make({ fetchImage: vi.fn(async () => gate.promise) })
    const run = store.startBulk(args())
    await vi.waitFor(() => expect(store.bulk()?.state).toBe('running'))
    expect(store.isBusy()).toBe(true)
    expect(await store.startBulk(args())).toEqual({ ok: false, busy: true })
    store.cancelBulk()
    expect(store.bulk().cancelling).toBe(true)
    gate.resolve({ success: true, blob: blobOf(4000), name: 'a.jpg', type: 'jpeg' })
    const out = await run
    expect(out).toMatchObject({ ok: false, result: { kind: 'cancelled' } })
    expect(download).not.toHaveBeenCalled()
    expect(store.isBusy()).toBe(false)
    expect(store.bulk()).toMatchObject({ state: 'finished', result: { kind: 'cancelled' } })
  })

  it('429 + Retry-After: önerilen süre beklenir ve ilerleme "bekleniyor: N sn" der; sonra sürer', async () => {
    const sleep = vi.fn(async () => true)
    let first = true
    const fetchImage = vi.fn(async (_s, url) => {
      if (first) { first = false; return { success: false, status: 429, code: 'RATE_LIMITED', retryAfter: 9 } }
      return { success: true, blob: blobOf(4000), name: `${url.split('/').pop().split('.')[0]}.jpg`, type: 'jpeg' }
    })
    const { store } = make({ fetchImage, sleep })
    const snaps = []
    store.on('bulk', () => snaps.push(store.bulk()))
    const out = await store.startBulk(args({ items: [img('a.jpg', 900 * 1024)] }))
    expect(sleep).toHaveBeenCalledTimes(1)
    expect(sleep.mock.calls[0][0]).toBe(9000)
    expect(snaps.some((s) => s?.waiting && s.waitSeconds === 9)).toBe(true)
    expect(out.result.kind).toBe('done')
    expect(fetchImage).toHaveBeenCalledTimes(2)
  })

  it('hiçbiri alınamazsa ZIP inmez ("none" + ilk neden); büyük görsel yoksa iş başlamaz; indirilemezse "downloadFailed"', async () => {
    const none = make({ fetchImage: vi.fn(async () => ({ success: false, status: 502, code: 'RESOURCE_IMAGE_FETCH_FAILED' })) })
    const out = await none.store.startBulk(args())
    expect(out).toMatchObject({ ok: false, result: { kind: 'none', missing: 4 } })
    expect(out.result.first).toBeTruthy()
    expect(none.download).not.toHaveBeenCalled()
    const empty = make()
    expect(await empty.store.startBulk(args({ items: [img('ufak.jpg', 10 * 1024)] }))).toMatchObject({ ok: false, empty: true })
    expect(await empty.store.startBulk(args({ canRun: false }))).toMatchObject({ ok: false, empty: true })
    expect(empty.store.bulk()).toBe(null)
    const blocked = make()
    blocked.download.mockReturnValue(false)
    expect((await blocked.store.startBulk(args())).result.kind).toBe('downloadFailed')
  })

  it('süren iş kaydı: pencereyi kapatan sayfa "sürüyor mu?" diye sorabilir', async () => {
    resetImageWorkForTests()
    const gate = deferred()
    const { store } = make({ fetchImage: vi.fn(async () => gate.promise) })
    const off = registerImageStore(store)
    expect(isImageWorkBusy()).toBe(false)
    const run = store.startBulk(args())
    await vi.waitFor(() => expect(isImageWorkBusy()).toBe(true))
    store.cancelBulk()
    gate.resolve({ success: false, status: 0 })
    await run
    expect(isImageWorkBusy()).toBe(false)
    off()
    expect(registerImageStore(null)()).toBeUndefined()
  })
})

describe('akış ZIP yazıcısı', () => {
  it('dosyalar gelir gelmez yazılır; finish application/zip Blob\'u döner; metin UTF-8', async () => {
    const w = await createZipWriter()
    w.add('a.bin', new Uint8Array([1, 2, 3]))
    w.addText('not.txt', 'ğüşiöç İ')
    expect(w.count()).toBe(2)
    const blob = w.finish()
    expect(blob.type).toBe('application/zip')
    const files = await unzip(blob)
    expect([...files['a.bin']]).toEqual([1, 2, 3])
    expect(strFromU8(files['not.txt'])).toBe('ğüşiöç İ')
    expect(() => w.add('b', new Uint8Array(1))).toThrow()
    expect([...(await bytesOfBlob(new Blob([new Uint8Array([9, 8])])))]).toEqual([9, 8])
  })

  it('abort yarım arşivi bırakır (fırlatmaz); paketleyici yüklenemezse hiçbir şey yazılmadan fırlatır', async () => {
    const w = await createZipWriter()
    w.add('a.bin', new Uint8Array([1]))
    expect(() => w.abort()).not.toThrow()
    expect(() => w.abort()).not.toThrow()
    await expect(createZipWriter({ load: async () => { throw new Error('bayat paket') } })).rejects.toThrow('bayat paket')
  })
})
