import { downloadBlob } from '../../../../utils/csvExport.js'
import * as defaultEngine from './engineClient.js'
import { runImageBulk } from './imageBulk.js'
import { bytesOfBlob, createZipWriter } from './imageZip.js'
import {
  DEFAULT_SETTINGS, MISSING_FILE, SERVER_CONCURRENCY, TOOL_MAX_FILES, baseNameOfUrl, buildOptions, bulkPlan, mappingCsv, missingText,
  optimizeSettings, optionsKey, outputName, problemLine, resizeSettings, resultSummary, splitName, suggestedWidth, uniqueName,
  validateSettings, zipFileName,
} from './imageToolModel.js'

/**
 * GÖRSEL ARACI DEPOSU (2026-11-14) — "Kaynak analizi" sekmesi başına bir tane; React dışı, konu (topic) bazlı abonelik.
 *
 * Neden depo: (1) işlenen görsellerin sonuçları AÇIK SEKME boyunca bellekte kalır (kalıcı değil) — aynı ayarla ikinci tık
 * anında indirir; (2) ilerleme (aşama / yüzde) yalnız O SATIRIN konusuna yayılır — sekmenin tamamı saniyede birçok kez
 * yeniden çizilmez; (3) araç kuyruğu ve toplu indirme, kullanıcı bölüm değiştirse de (görünüm sökülse de) sürer.
 *
 * Konular: `scan:<analiz>\n<adres>` (tarama görseli satırı), `results` (bu sekmede işlenen tarama görselleri değişti),
 * `queue` (araç kuyruğunun yapısı / durumu), `qp:<id>` (bir kuyruk satırının ilerlemesi), `settings`, `support`, `bulk`,
 * `zip`. Anlık görüntüler DEĞİŞMEZ nesnelerdir (değişince yenisi konur) — `useSyncExternalStore` ile okunur.
 *
 * Kurallar: sunucudan görsel isteği aynı anda en çok {@link SERVER_CONCURRENCY}; her iş iptal edilebilir (yeni iş aynı
 * anahtarın eskisini keser, `dispose` hepsini); geç gelen sonuç yeni işin üstüne yazmaz (sıra numarası); bellekteki
 * Blob'lar bayt tavanlıdır ({@link CACHE_MAX_BYTES}, en eski önce düşer); bir görselin hatası ötekileri durdurmaz.
 */

/** Bellekte tutulan özgün + sonuç Blob'larının toplam tavanı. */
export const CACHE_MAX_BYTES = 256 * 1024 * 1024
const IDLE = Object.freeze({ state: 'idle', phase: null, fraction: null, original: null, result: null, problem: null, action: null })
const NO_PROGRESS = Object.freeze({ phase: null, fraction: null })
const SUPPORT_LOADING = Object.freeze({ state: 'loading', webp: false, worker: false, limits: defaultEngine.FALLBACK_LIMITS })

const sizeOf = (blob) => (blob && Number.isFinite(blob.size) ? blob.size : 0)

/** İptal edilebilir sayaçlı kilit: `acquire` bir bırakma işlevi döner (iptalde null). */
function semaphore(max) {
  let active = 0
  const waiters = []
  const release = () => {
    active -= 1
    const next = waiters.shift()
    if (next) next()
  }
  return {
    acquire(signal) {
      return new Promise((resolve) => {
        if (signal?.aborted) { resolve(null); return }
        const grant = () => {
          signal?.removeEventListener?.('abort', onAbort)
          active += 1
          let released = false
          resolve(() => { if (!released) { released = true; release() } })
        }
        const onAbort = () => {
          const i = waiters.indexOf(grant)
          if (i >= 0) waiters.splice(i, 1)
          resolve(null)
        }
        if (active < max) { grant(); return }
        waiters.push(grant)
        signal?.addEventListener?.('abort', onAbort, { once: true })
      })
    },
  }
}

/**
 * @param {{ fetchImage: (scanId, url, signal) => Promise<object>, engine?: object, download?: Function }} deps
 *   `fetchImage` = `api.monitoring.resourceScanImage` sarmalayıcısı; `engine` = `engineClient` (test taklidi);
 *   `download` = `downloadBlob` (başlatılamazsa false döner); `sleep` = toplu indirmenin 429 beklemesi (test kancası).
 */
export function createImageToolStore({ fetchImage, engine = defaultEngine, download = downloadBlob, sleep = null } = {}) {
  const topics = new Map()
  let disposed = false
  const on = (topic, fn) => {
    let set = topics.get(topic)
    if (!set) { set = new Set(); topics.set(topic, set) }
    set.add(fn)
    return () => { set.delete(fn); if (!set.size) topics.delete(topic) }
  }
  const emit = (topic) => {
    const set = topics.get(topic)
    if (!set) return
    for (const fn of [...set]) { try { fn() } catch { /* dinleyici hatası depoyu durdurmaz */ } }
  }

  // ── Blob önbelleği (bayt tavanlı, en eski önce) ───────────────────────────
  const blobs = new Map()
  let blobBytes = 0
  const putBlob = (key, blob) => {
    const old = blobs.get(key)
    if (old) { blobBytes -= sizeOf(old); blobs.delete(key) }
    blobs.set(key, blob)
    blobBytes += sizeOf(blob)
    for (const [k, b] of blobs) {
      if (blobBytes <= CACHE_MAX_BYTES || blobs.size <= 1) break
      if (k === key) continue
      blobs.delete(k)
      blobBytes -= sizeOf(b)
    }
  }
  const dropBlob = (key) => {
    const b = blobs.get(key)
    if (!b) return
    blobs.delete(key)
    blobBytes -= sizeOf(b)
  }
  const getBlob = (key) => {
    const b = blobs.get(key)
    if (!b) return null
    blobs.delete(key)
    blobs.set(key, b)   // tazelik: en son kullanılan sona
    return b
  }

  // ── Motor desteği / sınırlar ──────────────────────────────────────────────
  let support = SUPPORT_LOADING
  let supportPromise = null
  const ensureSupport = () => {
    if (!supportPromise) {
      supportPromise = (async () => {
        let limits = defaultEngine.FALLBACK_LIMITS
        let s = { worker: false, webp: false }
        let ok = true
        try { limits = await engine.engineLimits() } catch { ok = false }
        try { s = await engine.engineSupport() } catch { ok = false }
        if (disposed) return support
        support = Object.freeze({ state: ok ? 'ready' : 'unknown', webp: s?.webp === true, worker: s?.worker === true, limits: limits || defaultEngine.FALLBACK_LIMITS })
        emit('support')
        return support
      })()
    }
    return supportPromise
  }

  // ── Tarama görselleri ─────────────────────────────────────────────────────
  const scan = new Map()
  const scanCtl = new Map()
  const scanSeq = new Map()
  let resultsVersion = 0
  const fetchGate = semaphore(SERVER_CONCURRENCY)
  const keyOf = (scanId, url) => `${scanId}\n${url}`
  const origKey = (key) => `o\n${key}`
  const resKey = (key, optKey) => `r\n${key}\n${optKey}`
  const scanEntry = (scanId, url) => scan.get(keyOf(scanId, url)) || IDLE
  const setScan = (key, patch) => {
    if (disposed) return
    scan.set(key, Object.freeze({ ...(scan.get(key) || IDLE), ...patch }))
    emit(`scan:${key}`)
  }
  const bumpResults = () => { resultsVersion += 1; emit('results') }

  /** Yeni iş: aynı anahtarın süren işini keser; dönen `live()` geç gelen sonucu eler. */
  const begin = (key) => {
    scanCtl.get(key)?.abort()
    const ctl = new AbortController()
    scanCtl.set(key, ctl)
    const seq = (scanSeq.get(key) || 0) + 1
    scanSeq.set(key, seq)
    return { signal: ctl.signal, live: () => !disposed && scanSeq.get(key) === seq && !ctl.signal.aborted, end: () => { if (scanCtl.get(key) === ctl) scanCtl.delete(key) } }
  }

  /** Özgün baytlar: önbellekten ya da sunucudan (aynı anda ≤ 2 istek). Dönüş: `{ ok, blob, meta }` | `{ ok: false, res }` | `{ aborted }`. */
  async function loadOriginal(scanId, item, signal) {
    const key = keyOf(scanId, item.url)
    const cached = getBlob(origKey(key))
    const known = scan.get(key)?.original
    if (cached && known) return { ok: true, blob: cached, meta: known }
    const release = await fetchGate.acquire(signal)
    if (!release) return { aborted: true }
    let res
    try {
      res = await fetchImage(scanId, item.url, signal)
    } catch (e) {
      res = { success: false, status: 0, error: e?.message || null, aborted: e?.name === 'AbortError' }
    } finally {
      release()
    }
    if (signal?.aborted || res?.aborted) return { aborted: true }
    if (!res?.success || !res.blob) return { ok: false, res: res || { success: false, status: 0 } }
    // Ad: sunucunun verdiği (Content-Disposition — uzantı algılanan türden); yoksa adresin son parçası + türün uzantısı
    const base = res.name ? splitName(res.name).base : baseNameOfUrl(item.url)
    const meta = Object.freeze({
      bytes: sizeOf(res.blob), type: res.type || null, width: res.width ?? item.width ?? null, height: res.height ?? item.height ?? null,
      name: res.name || outputName(base, res.type),
      base,
    })
    putBlob(origKey(key), res.blob)
    return { ok: true, blob: res.blob, meta }
  }

  /** Özgün dosyayı indirir. Dönüş: `{ ok }` — sorun satırın kendi kaydına yazılır. */
  async function downloadOriginal(scanId, item) {
    const key = keyOf(scanId, item.url)
    const op = begin(key)
    setScan(key, { state: 'fetching', action: 'original', problem: null, phase: null, fraction: null })
    try {
      const got = await loadOriginal(scanId, item, op.signal)
      if (!op.live() || got.aborted) return { ok: false, aborted: true }
      if (!got.ok) { setScan(key, { state: 'failed', problem: { kind: 'server', res: got.res } }); return { ok: false } }
      const started = download(got.meta.name, got.blob) !== false
      setScan(key, { state: scan.get(key)?.result ? 'done' : 'idle', original: got.meta, problem: started ? null : { kind: 'client', code: 'download' } })
      return { ok: started }
    } finally {
      op.end()
    }
  }

  /** Motoru çağırır; `EngineError` sorun kaydına çevrilir. Dönüş: `{ ok, result }` | `{ ok: false, problem }` | `{ aborted }`. */
  async function runEngine(blob, options, signal, onProgress) {
    try {
      const result = await engine.processImage(blob, options, { signal, onProgress })
      if (!result || !result.blob) return { ok: false, problem: { kind: 'engine', code: 'other', params: {} } }
      return { ok: true, result }
    } catch (e) {
      if (signal?.aborted || e?.code === 'CANCELLED' || e?.name === 'AbortError') return { aborted: true }
      if (defaultEngine.isEngineError(e)) return { ok: false, problem: { kind: 'engine', code: e.code, params: e.params || {} } }
      return { ok: false, problem: { kind: 'engine', code: 'other', params: {}, technical: e?.message || null } }
    }
  }

  /**
   * Tarama görselini verilen ayarla optimize eder ve indirir. Aynı ayarın sonucu bellekteyse yeniden işlenmez (anında indirir).
   * `mode`: çıktı adının biçimi (`optimized` | `width`). `silent`: indirme başlatılmaz (karşılaştırma için hazırlık).
   */
  async function optimizeScanImage(scanId, item, settings, { mode = 'optimized', silent = false } = {}) {
    const key = keyOf(scanId, item.url)
    await ensureSupport()
    const options = buildOptions(settings, support.limits)
    if (!options) { setScan(key, { state: 'failed', problem: { kind: 'engine', code: 'INVALID_OPTIONS', params: {} } }); return { ok: false } }
    const optKey = optionsKey(options)
    const prior = scan.get(key)
    const cachedBlob = prior?.result?.optKey === optKey ? getBlob(resKey(key, optKey)) : null
    if (cachedBlob && prior?.result) {
      const started = silent || download(prior.result.name, cachedBlob) !== false
      setScan(key, { state: 'done', problem: started ? null : { kind: 'client', code: 'download' } })
      return { ok: started, cached: true }
    }
    const op = begin(key)
    setScan(key, { state: 'fetching', action: 'optimize', problem: null, phase: null, fraction: null })
    try {
      const got = await loadOriginal(scanId, item, op.signal)
      if (!op.live() || got.aborted) return { ok: false, aborted: true }
      if (!got.ok) { setScan(key, { state: 'failed', problem: { kind: 'server', res: got.res } }); return { ok: false } }
      setScan(key, { state: 'processing', original: got.meta, phase: null, fraction: null })
      const out = await runEngine(got.blob, options, op.signal, (phase, fraction) => { if (op.live()) setScan(key, { phase, fraction }) })
      if (!op.live() || out.aborted) return { ok: false, aborted: true }
      if (!out.ok) { setScan(key, { state: 'failed', problem: out.problem, phase: null, fraction: null }); return { ok: false } }
      const summary = resultSummary(out.result)
      const name = outputName(got.meta.base, summary.type, { mode, width: summary.resized ? summary.width : null })
      putBlob(resKey(key, optKey), out.result.blob)
      const started = silent || download(name, out.result.blob) !== false
      setScan(key, {
        state: 'done', phase: null, fraction: null, original: got.meta,
        result: Object.freeze({ ...summary, name, optKey }), problem: started ? null : { kind: 'client', code: 'download' },
      })
      bumpResults()
      return { ok: started }
    } finally {
      op.end()
    }
  }

  const cancelScan = (scanId, url) => {
    const key = keyOf(scanId, url)
    const ctl = scanCtl.get(key)
    if (!ctl) return
    ctl.abort()
    scanCtl.delete(key)
    const cur = scan.get(key)
    setScan(key, { state: cur?.result ? 'done' : 'idle', phase: null, fraction: null, action: null })
  }

  /** Bir analizde bu sekmede işlenen görseller: `[{ url, summary }]` (dışa aktarım, projeksiyon). */
  const processedFor = (scanId) => {
    const prefix = `${scanId}\n`
    const out = []
    for (const [key, e] of scan) {
      if (key.startsWith(prefix) && e.result) out.push({ url: key.slice(prefix.length), summary: e.result })
    }
    return out
  }

  /** Karşılaştırma için bellekteki Blob'lar (düşmüşse null — çağıran düğmeyi göstermez). */
  const scanBlobs = (scanId, url) => {
    const key = keyOf(scanId, url)
    const e = scan.get(key)
    if (!e?.result) return null
    const original = getBlob(origKey(key))
    const result = getBlob(resKey(key, e.result.optKey))
    return original && result ? { original, result, summary: e.result, name: e.original?.name || '' } : null
  }

  // ── Araç kuyruğu ──────────────────────────────────────────────────────────
  let queue = Object.freeze([])
  let settings = DEFAULT_SETTINGS
  let nextId = 1
  let pumping = false
  const entryCtl = new Map()
  const entryFiles = new Map()      // id → { source: Blob, result: Blob|null }
  const progress = new Map()
  const setQueue = (next) => { if (disposed) return; queue = Object.freeze(next); emit('queue') }
  const patchEntry = (id, patch) => {
    if (!queue.some((e) => e.id === id)) return
    setQueue(queue.map((e) => (e.id === id ? Object.freeze({ ...e, ...patch }) : e)))
  }
  const setProgress = (id, phase, fraction) => {
    if (disposed) return
    progress.set(id, Object.freeze({ phase, fraction }))
    emit(`qp:${id}`)
  }
  const entryProgress = (id) => progress.get(id) || NO_PROGRESS
  const settingsOf = (entry) => entry.own || settings
  /** Bir satırın geçerli ayar anahtarı (ayar geçersizse null). */
  const currentKey = (entry) => {
    const o = buildOptions(settingsOf(entry), support.limits)
    return o ? optionsKey(o) : null
  }
  const isStale = (entry) => entry.state === 'done' && entry.usedKey !== currentKey(entry)

  async function runEntry(id) {
    const entry = queue.find((e) => e.id === id)
    const files = entryFiles.get(id)
    if (!entry || !files) return
    const options = buildOptions(settingsOf(entry), support.limits)
    if (!options) return
    const ctl = new AbortController()
    entryCtl.set(id, ctl)
    const live = () => !disposed && entryCtl.get(id) === ctl && !ctl.signal.aborted
    patchEntry(id, { state: 'processing', problem: null })
    setProgress(id, null, null)
    try {
      let info = entry.info
      if (!info) {
        try {
          info = await engine.inspectImage(files.source)
        } catch (e) {
          if (!live()) return
          const problem = defaultEngine.isEngineError(e) ? { kind: 'engine', code: e.code, params: e.params || {} } : { kind: 'engine', code: 'other', params: {} }
          patchEntry(id, { state: 'failed', problem })
          return
        }
        if (!live()) return
        patchEntry(id, { info: Object.freeze({ type: info?.type || null, width: info?.width ?? null, height: info?.height ?? null,
          bytes: info?.bytes ?? sizeOf(files.source), hasAlpha: info?.hasAlpha ?? null, animated: info?.animated === true }) })
      }
      const out = await runEngine(files.source, options, ctl.signal, (phase, fraction) => { if (live()) setProgress(id, phase, fraction) })
      if (!live() || out.aborted) return
      if (!out.ok) { patchEntry(id, { state: 'failed', problem: out.problem }); return }
      const summary = resultSummary(out.result)
      files.result = out.result.blob
      const name = outputName(entry.base, summary.type, { mode: summary.resized ? 'width' : 'replace', width: summary.resized ? summary.width : null })
      patchEntry(id, { state: 'done', result: Object.freeze({ ...summary, name }), usedKey: optionsKey(options), problem: null })
      // Taramadan gönderilen görsel: sonuç analizin "bu sekmede işlenen" kayıtlarına da yazılır (dışa aktarım, projeksiyon)
      if (entry.scan) {
        const key = keyOf(entry.scan.scanId, entry.scan.url)
        const optKey = optionsKey(options)
        putBlob(resKey(key, optKey), out.result.blob)
        setScan(key, { state: 'done', result: Object.freeze({ ...summary, name, optKey }), problem: null })
        bumpResults()
      }
    } finally {
      if (entryCtl.get(id) === ctl) entryCtl.delete(id)
      progress.delete(id)
    }
  }

  /** Bekleyenleri SIRAYLA işler (tek seferde bir görsel — işlem yoğun). Sınırlı döngü. */
  async function pump() {
    if (pumping || disposed) return
    pumping = true
    try {
      await ensureSupport()
      for (let guard = 0; guard < 5000 && !disposed; guard += 1) {
        const next = queue.find((e) => e.state === 'waiting' && buildOptions(settingsOf(e), support.limits))
        if (!next) break
        await runEntry(next.id)
      }
    } finally {
      pumping = false
    }
  }

  const addEntry = ({ source, name, blob, own = null, scanRef = null, info = null }) => {
    const id = nextId
    nextId += 1
    const base = splitName(name).base || 'image'
    entryFiles.set(id, { source: blob, result: null })
    return Object.freeze({ id, source, name, base, bytes: sizeOf(blob), info, state: 'waiting', result: null, problem: null, own, usedKey: null, scan: scanRef })
  }

  /**
   * Yüklenen dosyaları kuyruğa ekler. Sınır: kuyrukta en çok {@link TOOL_MAX_FILES} dosya — fazlası EKLENMEZ ve sayısı
   * döner (sessizce atılmaz); motor sınırından büyük dosya kuyruğa "başarısız + neden" olarak girer.
   * @returns {{ added: number, rejected: number }}
   */
  function addFiles(fileList) {
    const files = [...(fileList || [])].filter((f) => f && typeof f.size === 'number')
    const room = Math.max(0, TOOL_MAX_FILES - queue.length)
    const take = files.slice(0, room)
    const maxBytes = support.limits?.maxInputBytes ?? defaultEngine.FALLBACK_LIMITS.maxInputBytes
    const added = take.map((f) => {
      const e = addEntry({ source: 'upload', name: f.name || 'image', blob: f })
      return f.size > maxBytes ? Object.freeze({ ...e, state: 'failed', problem: { kind: 'client', code: 'tooLargeFile', params: { maxBytes } } }) : e
    })
    if (added.length) setQueue([...queue, ...added])
    pump()
    return { added: added.length, rejected: files.length - take.length }
  }

  /** Tarama görselini (özgün baytlarıyla) araca gönderir; `own` = o görselin kendi ayarı (ör. önerilen genişlik). */
  async function sendToTool(scanId, item, own = null) {
    const key = keyOf(scanId, item.url)
    if (queue.length >= TOOL_MAX_FILES) return { ok: false, full: true }
    const op = begin(key)
    setScan(key, { state: 'fetching', action: 'tool', problem: null, phase: null, fraction: null })
    try {
      const got = await loadOriginal(scanId, item, op.signal)
      if (!op.live() || got.aborted) return { ok: false, aborted: true }
      if (!got.ok) { setScan(key, { state: 'failed', problem: { kind: 'server', res: got.res } }); return { ok: false } }
      setScan(key, { state: scan.get(key)?.result ? 'done' : 'idle', original: got.meta, action: null })
      if (queue.length >= TOOL_MAX_FILES) return { ok: false, full: true }
      const e = addEntry({ source: 'scan', name: got.meta.name, blob: got.blob, own, scanRef: Object.freeze({ scanId, url: item.url }) })
      setQueue([...queue, e])
      pump()
      return { ok: true, id: e.id }
    } finally {
      op.end()
    }
  }

  const dropEntry = (id) => {
    entryCtl.get(id)?.abort()
    entryCtl.delete(id)
    entryFiles.delete(id)
    progress.delete(id)
  }
  const removeEntry = (id) => { dropEntry(id); setQueue(queue.filter((e) => e.id !== id)) }
  const clearQueue = () => { for (const e of queue) dropEntry(e.id); setQueue([]) }
  const retryEntry = (id) => { patchEntry(id, { state: 'waiting', problem: null }); pump() }
  /** Bir satırın kendi ayarını kaldırır (panel ayarına döner) ya da değiştirir. */
  const setOwnSettings = (id, own) => { patchEntry(id, { own: own || null }); if (queue.find((e) => e.id === id)?.state === 'waiting') pump() }
  /** Ayarı değişen (bayat) ve başarısız satırları yeniden kuyruğa alır. */
  function reprocess() {
    let changed = false
    const next = queue.map((e) => {
      if (isStale(e) || (e.state === 'failed' && entryFiles.has(e.id) && e.problem?.code !== 'tooLargeFile')) { changed = true; return Object.freeze({ ...e, state: 'waiting', problem: null }) }
      return e
    })
    if (changed) setQueue(next)
    pump()
  }
  const setSettings = (patch) => {
    settings = Object.freeze({ ...settings, ...patch })
    emit('settings')
    if (!validateSettings(settings, support.limits)) pump()
  }
  const entryBlobs = (id) => {
    const f = entryFiles.get(id)
    return f ? { original: f.source, result: f.result } : null
  }
  const downloadEntry = (id) => {
    const e = queue.find((x) => x.id === id)
    const f = entryFiles.get(id)
    if (!e?.result || !f?.result) return false
    return download(e.result.name, f.result) !== false
  }

  // ── ZIP (araç kuyruğu) ────────────────────────────────────────────────────
  let zipState = null
  const setZip = (v) => { if (disposed) return; zipState = v ? Object.freeze(v) : null; emit('zip') }
  /** Tamamlanan (bayat olmayan) kuyruk sonuçlarını tek ZIP + eşleşme listesi olarak indirir. */
  async function zipQueue({ t, name }) {
    if (zipState?.running) return { ok: false, busy: true }
    const done = queue.filter((e) => e.state === 'done' && e.result && entryFiles.get(e.id)?.result)
    if (!done.length) return { ok: false, empty: true }
    setZip({ running: true, done: 0, total: done.length })
    let writer = null
    try {
      writer = await createZipWriter()
      const used = new Set([MISSING_FILE, t('prs.it.zip.mappingFile').toLocaleLowerCase('tr')])
      const rows = []
      for (let i = 0; i < done.length; i += 1) {
        if (disposed) return { ok: false, aborted: true }
        const e = done[i]
        const f = entryFiles.get(e.id)
        if (!f?.result) continue
        const entryName = uniqueName(e.result.name, used)
        writer.add(entryName, await bytesOfBlob(f.result))
        rows.push({ source: e.scan?.url || e.name, name: entryName, summary: e.result })
        setZip({ running: true, done: i + 1, total: done.length })
      }
      if (!rows.length) return { ok: false, empty: true }
      writer.addText(t('prs.it.zip.mappingFile'), mappingCsv(rows, t))
      const blob = writer.finish()
      writer = null
      if (disposed) return { ok: false, aborted: true }
      const started = download(name, blob) !== false
      return { ok: started, files: rows.length, downloadFailed: !started }
    } finally {
      writer?.abort()
      setZip(null)
    }
  }

  // ── Toplu indirme (analizin büyük görselleri) ─────────────────────────────
  let bulk = null
  let bulkCtl = null
  const setBulk = (v) => { if (disposed) return; bulk = v ? Object.freeze(v) : null; emit('bulk') }
  const isBulkRunning = () => bulkCtl != null && !bulkCtl.signal.aborted

  /**
   * Analizin büyük görsellerini sırayla alır (aynı anda ≤ 2), tarayıcıda optimize eder ve tek ZIP olarak indirir:
   * görseller + eşleşme listesi (CSV) + alınamayanlar için `missing.txt`. İptalde HİÇBİR ŞEY indirilmez.
   * @param {{ scanId, items: object[], canRun: boolean, itemsKept: boolean, resize: boolean, format: string, t: Function,
   *           locale: string, baseName: string }} p
   */
  async function startBulk({ scanId, items, canRun, itemsKept, resize = true, format = 'keep', t, locale, baseName }) {
    if (bulkCtl) return { ok: false, busy: true }
    const { plan, skipped, truncated } = bulkPlan(items, { canRun, itemsKept })
    if (!plan.length) return { ok: false, empty: true, skipped: skipped.length }
    const ctl = new AbortController()
    bulkCtl = ctl
    setBulk({ state: 'running', scanId, done: 0, total: plan.length, waiting: false, waitSeconds: null, cancelling: false, packing: false })
    let writer = null
    const finishWith = (result) => { setBulk({ state: 'finished', scanId, result: Object.freeze(result) }); return { ok: result.kind === 'done' || result.kind === 'partial', result } }
    try {
      await ensureSupport()
      writer = await createZipWriter()
      const zip = writer
      const used = new Set([MISSING_FILE, t('prs.it.zip.mappingFile').toLocaleLowerCase('tr')])
      const settingsFor = (item) => {
        const s = resize ? suggestedWidth(item) : null
        return s ? resizeSettings(s.width, { format }) : optimizeSettings({ format })
      }
      const res = await runImageBulk(plan, {
        signal: ctl.signal,
        ...(sleep ? { sleep } : {}),
        fetchOriginal: async (item, signal) => {
          const got = await loadOriginal(scanId, item, signal)
          if (got.aborted) return { success: false, status: 0, aborted: true }
          if (!got.ok) return got.res
          return { success: true, blob: got.blob, meta: got.meta }
        },
        processOne: async (item, original, signal) => {
          const options = buildOptions(settingsFor(item), support.limits)
          if (!options) return { ok: false, problem: { kind: 'engine', code: 'INVALID_OPTIONS', params: {} } }
          const out = await runEngine(original.blob, options, signal, () => {})
          if (out.aborted) return { ok: false, aborted: true }
          if (!out.ok) return out
          return { ok: true, result: { engine: out.result, options } }
        },
        onFile: async ({ item, original, result }) => {
          const summary = resultSummary(result.engine)
          const entryName = uniqueName(outputName(original.meta.base, summary.type, { mode: 'replace' }), used)
          zip.add(entryName, await bytesOfBlob(result.engine.blob))
          // Özet "bu sekmede işlenen" kayıtlarına yazılır; Blob'lar bellekte TUTULMAZ (toplu işte bellek birikmesin)
          const key = keyOf(scanId, item.url)
          dropBlob(origKey(key))
          setScan(key, { state: 'done', original: original.meta, result: Object.freeze({ ...summary, name: entryName, optKey: optionsKey(result.options) }), problem: null })
          return { name: entryName, summary }
        },
        onProgress: (p) => {
          if (ctl.signal.aborted) return
          setBulk({ ...(bulk || {}), state: 'running', scanId, done: p.done, total: p.total, waiting: p.waiting === true,
            waitSeconds: p.waitMs ? Math.max(1, Math.ceil(p.waitMs / 1000)) : (p.waiting ? bulk?.waitSeconds ?? null : null), cancelling: false, packing: false })
        },
      })
      bumpResults()
      if (res.aborted || ctl.signal.aborted) return finishWith({ kind: 'cancelled' })
      const files = []
      const missing = skipped.map((s) => ({ source: s.item.url, text: problemLine({ kind: 'unavailable', reason: s.reason }, t, locale) }))
      for (const o of res.outcomes) {
        if (o.ok) files.push({ source: o.item.url, name: o.entry.name, summary: o.entry.summary })
        else if (o.reason === 'skipped') missing.push({ source: o.item.url, text: t(o.status === 401 ? 'prs.it.zip.skippedSession' : 'prs.it.zip.skippedForbidden') })
        else missing.push({ source: o.item.url, text: problemLine(o.reason === 'server' ? { kind: 'server', res: o.res } : o.problem, t, locale) })
      }
      if (!files.length) return finishWith({ kind: 'none', missing: missing.length, stopped: res.stopped, first: missing[0]?.text || null })
      setBulk({ ...(bulk || {}), state: 'running', packing: true, waiting: false })
      zip.addText(t('prs.it.zip.mappingFile'), mappingCsv(files, t))
      if (missing.length) zip.addText(MISSING_FILE, missingText(missing, t))
      const blob = zip.finish()
      writer = null
      if (ctl.signal.aborted) return finishWith({ kind: 'cancelled' })
      if (download(zipFileName(baseName, t('prs.it.zip.suffix')), blob) === false) return finishWith({ kind: 'downloadFailed' })
      const saved = files.reduce((a, f) => a + Math.max(0, (f.summary.original?.bytes ?? 0) - (f.summary.bytes ?? 0)), 0)
      return finishWith({ kind: missing.length ? 'partial' : 'done', files: files.length, missing: missing.length, truncated, stopped: res.stopped, savedBytes: saved })
    } catch (e) {
      return finishWith({ kind: 'crashed', technical: e?.message || null, error: e })
    } finally {
      writer?.abort()
      if (bulkCtl === ctl) bulkCtl = null
    }
  }
  const cancelBulk = () => {
    if (!bulkCtl) return
    bulkCtl.abort()
    if (bulk?.state === 'running') setBulk({ ...bulk, cancelling: true })
  }
  const dismissBulk = () => { if (!bulkCtl) setBulk(null) }

  function dispose() {
    disposed = true
    for (const c of scanCtl.values()) c.abort()
    for (const c of entryCtl.values()) c.abort()
    bulkCtl?.abort()
    scanCtl.clear(); entryCtl.clear(); entryFiles.clear(); progress.clear(); blobs.clear(); scan.clear(); topics.clear()
    blobBytes = 0
    queue = Object.freeze([])
  }

  return {
    on,
    // anlık görüntüler
    scanEntry, queue: () => queue, settings: () => settings, support: () => support, bulk: () => bulk, zip: () => zipState,
    entryProgress, resultsVersion: () => resultsVersion,
    // tarama görselleri
    downloadOriginal, optimizeScanImage, sendToTool, cancelScan, processedFor, scanBlobs,
    // araç
    ensureSupport, addFiles, removeEntry, clearQueue, retryEntry, reprocess, setSettings, setOwnSettings, entryBlobs, downloadEntry, zipQueue,
    isStale, currentKey,
    // toplu
    startBulk, cancelBulk, dismissBulk, isBulkRunning,
    isBusy: () => isBulkRunning() || zipState?.running === true,
    dispose,
    isDisposed: () => disposed,
  }
}
