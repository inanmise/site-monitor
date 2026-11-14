import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from './test-utils.jsx'

// Görsel aracı — bağımsız inceleme düzeltmeleri (2026-11-14), ekran parçaları: karşılaştırma penceresi (çözme tavanı,
// allSettled + kapanış, zarflanmış özgün), "Karşılaştır" yalnız iki görsel bellekteyken, durum canlı bölgesi + kaydırıcı
// dokunma alanı, duyarlı sette açık biçim, "boyutu fazla" önerisinin toplu düğmesi. Motor bağdaştırıcısı taklit.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({ monitoring: { resourceScanImage: vi.fn(), resourceScanImageProjection: vi.fn() } }),
}))
const eng = vi.hoisted(() => ({
  FALLBACK_LIMITS: { maxInputBytes: 25 * 1024 * 1024, maxPixels: 50_000_000, maxSide: 30_000 },
  loadEngine: vi.fn(), engineLimits: vi.fn(), engineSupport: vi.fn(), inspectImage: vi.fn(), processImage: vi.fn(), decodeForPreview: vi.fn(),
  isEngineError: (e) => e?.name === 'EngineError' && typeof e?.code === 'string',
}))
vi.mock('../components/page/resourcescan/imagetool/engineClient.js', () => eng)

import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { TR } from '../i18n/tr.js'
import { ImageToolProvider } from '../components/page/resourcescan/imagetool/ImageToolContext.jsx'
import { createImageToolStore } from '../components/page/resourcescan/imagetool/imageToolStore.js'
import ImageActions from '../components/page/resourcescan/imagetool/ImageActions.jsx'
import GradeProjectionCard from '../components/page/resourcescan/imagetool/GradeProjectionCard.jsx'
import { api } from '../api/client'
import { ImageBulkButton } from '../components/page/resourcescan/imagetool/ImageBulk.jsx'
import ImageCompareDialog, { PREVIEW_DECODE_MAX_SIDE, fitRect } from '../components/page/resourcescan/imagetool/ImageCompareDialog.jsx'
import ImageSettingsFields from '../components/page/resourcescan/imagetool/ImageSettingsFields.jsx'
import ImageToolView from '../components/page/resourcescan/imagetool/ImageToolView.jsx'
import { ResultLine } from '../components/page/resourcescan/imagetool/ImageActions.jsx'
import ResponsiveSetDialog, { SET_FORMATS, defaultSetFormat } from '../components/page/resourcescan/imagetool/ResponsiveSetDialog.jsx'
import { DEFAULT_SETTINGS, resultSummary } from '../components/page/resourcescan/imagetool/imageToolModel.js'

const SCAN = 12
const tr = (key, ...args) => String(TR[key] ?? key).replace(/\{(\d+)\}/g, (_, i) => String(args[Number(i)] ?? ''))
const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)
const blobOf = (n, type = 'image/jpeg') => new Blob([new Uint8Array(n).fill(7)], { type })
const img = (name, bytes = 600 * 1024, over = {}) => ({ category: 'IMAGE', url: `https://ornek.example.com/img/${name}`, bytes, status: 200, ok: true, verdict: 'ok', width: 3000, height: 2000, ...over })
const engineError = (code, params = {}) => Object.assign(new Error(code), { name: 'EngineError', code, params })
const deferred = () => { let resolve; const promise = new Promise((r) => { resolve = r }); return { promise, resolve } }

function engineResult(blob, options) {
  const resized = options.resize.method !== 'none'
  const width = resized ? (options.resize.width ?? 1500) : 3000
  const type = options.format === 'keep' || options.format === 'smallest' ? 'jpeg' : options.format
  const bytes = Math.max(1, Math.floor(blob.size / 4))
  return {
    blob: blobOf(bytes, `image/${type}`), type, mime: `image/${type}`, width, height: Math.round((width * 2) / 3), bytes,
    original: { type: 'jpeg', width: 3000, height: 2000, bytes: blob.size, hasAlpha: false, animated: false, metadata: {} },
    savingPct: 75, quality: 80, colors: null, similarity: 0.99, resized, cropped: false, kept: false, notes: [],
  }
}
function fakeEngine(over = {}) {
  return {
    engineLimits: async () => eng.FALLBACK_LIMITS,
    engineSupport: async () => ({ worker: true, webp: true }),
    inspectImage: async (blob) => ({ type: 'jpeg', width: 3000, height: 2000, bytes: blob.size, hasAlpha: false, animated: false }),
    processImage: vi.fn(async (blob, options) => engineResult(blob, options)),
    ...over,
  }
}
function setup({ ui, fetchImage, engine } = {}) {
  const download = vi.fn(() => true)
  const fetch = fetchImage || vi.fn(async (_s, url) => ({ success: true, blob: blobOf(400 * 1024), name: `${url.split('/').pop().split('.')[0]}.jpg`, type: 'jpeg', width: 3000, height: 2000 }))
  const store = createImageToolStore({ fetchImage: fetch, engine: engine || fakeEngine(), download, sleep: async () => true })
  const view = render(
    <ImageToolProvider store={store} monitorId={100} scanId={SCAN} canRun itemsKept monitorName="Kurumsal Ana Sayfa" onOpenTool={() => {}}>{ui}</ImageToolProvider>,
  )
  return { store, download, fetch, ...view }
}

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  eng.engineLimits.mockResolvedValue(eng.FALLBACK_LIMITS)
  eng.engineSupport.mockResolvedValue({ worker: true, webp: true })
  eng.processImage.mockImplementation(async (blob, options) => engineResult(blob, options))
  eng.decodeForPreview.mockImplementation(async () => ({ width: 1200, height: 800, close: vi.fn() }))
  HTMLCanvasElement.prototype.getContext = vi.fn(() => ({ clearRect: vi.fn(), drawImage: vi.fn() }))
})

describe('1 / 5 — karşılaştırma penceresi', () => {
  const original = blobOf(600)
  const result = blobOf(150)
  const cover = resultSummary({
    blob: {}, type: 'jpeg', bytes: 150_000, width: 1200, height: 1200, savingPct: 75, kept: false, resized: true, cropped: true, quality: 78, similarity: null, notes: [],
    original: { type: 'jpeg', bytes: 600_000, width: 3000, height: 2000 },
  })
  const open = (summary = cover) => render(<ImageCompareDialog title="hero.jpg" original={original} result={result} summary={summary} onClose={() => {}} />)

  it('fitRect: kutuya kendi oranıyla, ortalanmış (yatay ve dikey zarf; aynı oranda tam kutu)', () => {
    expect(fitRect(3000, 2000, 1200, 1200)).toEqual({ x: 0, y: 200, w: 1200, h: 800 })
    expect(fitRect(1000, 3000, 900, 600)).toEqual({ x: 350, y: 0, w: 200, h: 600 })
    expect(fitRect(1200, 800, 1200, 800)).toEqual({ x: 0, y: 0, w: 1200, h: 800 })
    expect(fitRect(0, 0, 0, 0)).toEqual({ x: 0, y: 0, w: 1, h: 1 })
  })

  it('kapla (kare çıktı): özgün GERİLMEZ — çıktının kutusuna kendi oranıyla, zarflanarak çizilir; çıktı tam kutuya', async () => {
    const draws = new Map()
    HTMLCanvasElement.prototype.getContext = vi.fn(function getContext() {
      const canvas = this
      return { clearRect: vi.fn(), drawImage: vi.fn((...a) => { draws.set(canvas, a) }) }
    })
    eng.decodeForPreview.mockImplementation(async (blob) => (blob === original
      ? { width: 2560, height: 1707, close: vi.fn() }
      : { width: 1200, height: 1200, close: vi.fn() }))
    open()
    await waitFor(() => expect(slot('prs-it-compare-dialog').getAttribute('data-status')).toBe('ready'))
    const before = slot('prs-it-cmp-before')
    const after = slot('prs-it-cmp-after')
    expect(before.getAttribute('data-rect')).toBe('0,200,1200,800')
    expect([before.width, before.height]).toEqual([1200, 1200])
    expect(draws.get(before).slice(1)).toEqual([0, 200, 1200, 800])
    expect(draws.get(after).slice(1)).toEqual([0, 0, 1200, 1200])
  })

  it('çözme önizleme tavanıyla istenir; biri başarısız olursa ÖTEKİNİN bit eşlemi kapatılır, neden yazılır', async () => {
    const good = { width: 1200, height: 800, close: vi.fn() }
    eng.decodeForPreview.mockImplementation(async (blob) => { if (blob === result) throw engineError('DECODE_FAILED'); return good })
    open()
    await waitFor(() => expect(slot('prs-it-cmp-failed')).not.toBeNull())
    expect(PREVIEW_DECODE_MAX_SIDE).toBe(2560)
    expect(eng.decodeForPreview.mock.calls.map((c) => c[1].maxSide)).toEqual([2560, 2560])
    expect(good.close).toHaveBeenCalledTimes(1)
    expect(slot('prs-it-cmp-failed')).toHaveTextContent('Önizleme hazırlanamadı')
  })

  it('pencere çözme sürerken kapanır, biri iptalle düşer, öteki GEÇ gelir: geç gelen de kapatılır', async () => {
    const gate = deferred()
    const late = { width: 1200, height: 800, close: vi.fn() }
    eng.decodeForPreview.mockImplementation((blob, { signal }) => (blob === original
      ? gate.promise.then(() => late)
      : new Promise((_, reject) => signal.addEventListener('abort', () => reject(engineError('CANCELLED'))))))
    const view = open()
    view.unmount()
    await act(async () => { gate.resolve(); for (let i = 0; i < 5; i += 1) await Promise.resolve() })
    expect(late.close).toHaveBeenCalledTimes(1)
  })
})

describe('9 / 12 — "İndir" satırı: karşılaştırma, durum bölgesi; elle kalite kaydırıcısı', () => {
  it('toplu indirmenin işlediği görselde "Karşılaştır" çizilmez (Blob tutulmaz); tek tek optimize edilende çizilir', async () => {
    const a = img('a.jpg')
    const { store } = setup({ ui: <ImageActions item={a} /> })
    await act(async () => { await store.startBulk({ scanId: SCAN, items: [a], canRun: true, itemsKept: true, t: tr, locale: 'tr-TR', baseName: 'site' }) })
    await waitFor(() => expect(slot('prs-it-result')).not.toBeNull())
    expect(slot('prs-it-compare')).toBeNull()
    await act(async () => { await store.optimizeScanImage(SCAN, a, { ...DEFAULT_SETTINGS, format: 'webp' }, { mode: 'optimized', silent: true }) })
    await waitFor(() => expect(slot('prs-it-compare')).not.toBeNull())
  })

  it('durum canlı bölgesi boşken de DOM\'da durur (sr-only — ilk ileti okunur), gizlenmez', () => {
    setup({ ui: <ImageActions item={img('hero.jpg')} /> })
    const status = slot('prs-it-status')
    expect(status).not.toBeNull()
    expect(status.getAttribute('role')).toBe('status')
    expect(status.className).toContain('empty:sr-only')
    expect(status.className).not.toContain('empty:hidden')
  })

  it('elle kalite kaydırıcısının başparmağı dokunmatikte 40 px dokunma alanı taşır', () => {
    render(<ImageSettingsFields value={{ ...DEFAULT_SETTINGS, qualityMode: 'manual', quality: 70 }} onChange={() => {}} support={{ state: 'ready', webp: true }} limits={eng.FALLBACK_LIMITS} />)
    const thumb = screen.getByRole('slider')
    expect(thumb.className).toContain('pointer-coarse:after:size-10')
    expect(thumb.className).toContain('pointer-coarse:after:absolute')
    expect(thumb.className).toMatch(/(^|\s)relative(\s|$)/)
  })
})

describe('8 — duyarlı set: çıktı biçimi açık seçilir, set tek türdür', () => {
  const open = ({ format = 'keep', type = 'gif', webp = true } = {}) => {
    const store = createImageToolStore({ fetchImage: vi.fn(), engine: fakeEngine({ engineSupport: async () => ({ worker: true, webp }) }), download: vi.fn(() => true) })
    eng.engineSupport.mockResolvedValue({ worker: true, webp })
    return render(<ResponsiveSetDialog name="Animasyon.gif" base="Animasyon" blob={blobOf(400 * 1024)} format={format}
      info={{ width: 1000, height: 600, type, hasAlpha: false }} store={store} onClose={() => {}} />)
  }

  it('"aynı biçim" / "en küçük" yolu yok; panel "aynı biçim"deyse varsayılan WebP; her genişlik o biçimde üretilir', async () => {
    expect(SET_FORMATS).toEqual(['webp', 'jpeg', 'png'])
    open()
    await waitFor(() => expect([...slot('prs-it-rs-format').options].map((o) => o.value)).toEqual(['webp', 'jpeg', 'png']))
    expect(slot('prs-it-rs-format').value).toBe('webp')
    fireEvent.click(slot('prs-it-rs-go'))
    await waitFor(() => expect(eng.processImage).toHaveBeenCalledTimes(3))
    expect(eng.processImage.mock.calls.map((c) => c[1].format)).toEqual(['webp', 'webp', 'webp'])
  })

  it('kullanıcı biçim seçer → o biçim; WebP üretemeyen tarayıcıda GIF / saydam → PNG, öteki → JPEG', () => {
    expect(defaultSetFormat('keep', { type: 'gif' }, true)).toBe('webp')
    expect(defaultSetFormat('smallest', { type: 'jpeg' }, null)).toBe('webp')
    expect(defaultSetFormat('jpeg', { type: 'png' }, true)).toBe('jpeg')
    expect(defaultSetFormat('webp', { type: 'png' }, false)).toBe('png')
    expect(defaultSetFormat('keep', { type: 'gif' }, false)).toBe('png')
    expect(defaultSetFormat('keep', { type: 'jpeg', hasAlpha: true }, false)).toBe('png')
    expect(defaultSetFormat('keep', { type: 'jpeg' }, false)).toBe('jpeg')
  })
})

describe('10 — "boyutu fazla" önerisinin toplu düğmesi kendi kümesini indirir', () => {
  const big = img('agir.jpg', 900 * 1024, { width: 1200, findings: ['LARGE_IMAGES'] })
  const wide = img('genis.png', 150 * 1024, { width: 3200, findings: ['OVERSIZED_IMAGE_DIMENSIONS'] })

  it('etiket, başlık ve sayım öneriye göre; başlatınca yalnız ölçüsü büyük görsel istenir', async () => {
    const { fetch } = setup({ ui: <ImageBulkButton items={[big, wide]} finding="OVERSIZED_IMAGE_DIMENSIONS" /> })
    const open = slot('prs-it-bulk-open')
    expect(open).toHaveTextContent('Ölçüsü büyük görselleri küçültüp indir (ZIP)')
    fireEvent.click(open)
    const dlg = await waitFor(() => { const d = slot('prs-it-bulk-dialog'); expect(d).not.toBeNull(); return d })
    expect(dlg.getAttribute('data-finding')).toBe('OVERSIZED_IMAGE_DIMENSIONS')
    expect(dlg.getAttribute('data-count')).toBe('1')
    expect(dlg).toHaveTextContent('1 ölçüsü büyük görsel')
    expect(screen.getByRole('dialog')).toHaveTextContent('Ölçüsü büyük görselleri küçültüp indir')
    fireEvent.click(slot('prs-it-bulk-start'))
    await waitFor(() => expect(fetch).toHaveBeenCalled())
    expect(fetch.mock.calls.map((c) => c[1])).toEqual([wide.url])
  })

  it('büyük görsel önerisinin düğmesi değişmedi (aynı etiket, LARGE_IMAGES kümesi)', async () => {
    setup({ ui: <ImageBulkButton items={[big, wide]} /> })
    expect(slot('prs-it-bulk-open')).toHaveTextContent('Büyük görselleri optimize edip indir (ZIP)')
    fireEvent.click(slot('prs-it-bulk-open'))
    const dlg = await waitFor(() => { const d = slot('prs-it-bulk-dialog'); expect(d).not.toBeNull(); return d })
    expect(dlg.getAttribute('data-finding')).toBe('LARGE_IMAGES')
    expect(dlg.getAttribute('data-count')).toBe('1')
  })
})

describe('sunucunun yeni sonuçları — satır ve not kartı', () => {
  const pickMenu = async (action) => {
    const { pressMenuTrigger } = await import('./helpers/dropdownMenu.js')
    pressMenuTrigger(slot('prs-it-menu'))
    const item = await waitFor(() => { const n = document.querySelector(`[data-slot="prs-it-menu-content"] [data-action="${action}"]`); expect(n).not.toBeNull(); return n })
    fireEvent.click(item)
  }
  const fetchWith = (tlsVerified) => vi.fn(async (_s, url) => ({ success: true, blob: blobOf(400 * 1024), name: url.split('/').pop(), type: 'jpeg', width: 3000, height: 2000, tlsVerified }))

  it('aktarımı doğrulanamayan özgün: işlenen satırda UYARI (gizlenmez), indirme yine olur; doğrulanmışta / bilinmeyende uyarı yok', async () => {
    const a = setup({ fetchImage: fetchWith(false), ui: <ImageActions item={img('hero.jpg')} /> })
    await pickMenu('optimize')
    await waitFor(() => expect(slot('prs-it-result')).not.toBeNull())
    expect(a.download).toHaveBeenCalledTimes(1)
    expect(slot('prs-it-tls-unverified')).toHaveTextContent('sertifikası doğrulanamadı')
    a.unmount()
    for (const v of [true, null]) {
      const b = setup({ fetchImage: fetchWith(v), ui: <ImageActions item={img('hero.jpg')} /> })
      await pickMenu('optimize')
      await waitFor(() => expect(slot('prs-it-result')).not.toBeNull())
      expect(slot('prs-it-tls-unverified')).toBeNull()
      b.unmount()
    }
  })

  it('site başına sınır ve https → http satırda kendi açık metniyle', async () => {
    const a = setup({ fetchImage: vi.fn(async () => ({ success: false, status: 429, code: 'RESOURCE_IMAGE_HOST_LIMITED', retryAfter: 23 })), ui: <ImageActions item={img('hero.jpg')} /> })
    await pickMenu('optimize')
    await waitFor(() => expect(slot('prs-it-problem')).not.toBeNull())
    expect(slot('prs-it-problem')).toHaveTextContent('bu siteden görsel indirmeyi yavaşlatıyor. Yaklaşık 23 saniye')
    a.unmount()
    setup({ fetchImage: vi.fn(async () => ({ success: false, status: 502, code: 'RESOURCE_IMAGE_FETCH_FAILED', reason: 'TLS_DOWNGRADE' })), ui: <ImageActions item={img('hero.jpg')} /> })
    await pickMenu('optimize')
    await waitFor(() => expect(slot('prs-it-problem')).not.toBeNull())
    expect(slot('prs-it-problem')).toHaveTextContent('şifresiz (http) bir adrese yönlendirdi')
    expect(slot('prs-it-problem')).not.toHaveTextContent('TLS_DOWNGRADE')
  })

  it('not kartı: 429 RESOURCE_PROJECTION_BUSY nötr bilgi (hata bandı değil), yeniden hesapla düğmesi kalır', async () => {
    api.monitoring.resourceScanImageProjection.mockResolvedValueOnce({ success: false, status: 429, code: 'RESOURCE_PROJECTION_BUSY', retry_after_seconds: 2, retryAfter: 2 })
    setup({ ui: <GradeProjectionCard scan={{ id: SCAN, status: 'DONE', grade: 61 }} analysis={{ images: { count: 12, bytes: 3_800_000 } }} /> })
    fireEvent.click(slot('prs-it-proj-run'))
    await waitFor(() => expect(slot('prs-it-proj-failed')).not.toBeNull())
    expect(slot('prs-it-proj-failed').getAttribute('data-code')).toBe('RESOURCE_PROJECTION_BUSY')
    expect(slot('prs-it-proj-failed').querySelector('[data-slot="alert"]').getAttribute('data-tone')).toBe('info')
    expect(slot('prs-it-proj-failed')).toHaveTextContent('başka not hesaplarını işliyor. Yaklaşık 2 saniye')
    expect(slot('prs-it-proj-run')).not.toBeNull()
  })
})

describe('son entegrasyon — ekran: üst veri ayıklama, bayat paket', () => {
  const staleError = () => Object.assign(new Error('BROWSER_UNSUPPORTED'), { name: 'EngineError', code: 'BROWSER_UNSUPPORTED', params: { reason: 'stale_bundle' } })
  const openMenu = async () => {
    const { pressMenuTrigger } = await import('./helpers/dropdownMenu.js')
    pressMenuTrigger(slot('prs-it-menu'))
    return waitFor(() => { const m = slot('prs-it-menu-content'); expect(m).not.toBeNull(); return m })
  }

  it('özgün korunup üst verisi ayıklanan sonuç: dürüst satır, "aynen bırakıldı" notu yok', () => {
    const sum = resultSummary({ blob: {}, type: 'jpeg', bytes: 380_000, width: 3000, height: 2000, savingPct: 5, kept: true, similarity: 1,
      notes: ['KEPT_ORIGINAL', 'METADATA_STRIPPED'], original: { type: 'jpeg', bytes: 400_000, width: 3000, height: 2000 } })
    render(<ResultLine result={sum} t={tr} locale="tr-TR" />)
    expect(slot('prs-it-result')).toHaveTextContent('Görüntü aynı, yalnız üst veri kaldırıldı: 391 KB → 371 KB (−%5)')
    expect(slot('prs-it-result').querySelector('[data-note="KEPT_ORIGINAL"]')).toBeNull()
    expect(slot('prs-it-result').querySelector('[data-note="METADATA_STRIPPED"]')).not.toBeNull()
  })

  it('bayat paket: satır "Sayfa güncellendi — yenileyin." + "Sayfayı yenile"; menü yenilemeyi ve yalnız özgün indirmeyi sunar', async () => {
    const engine = fakeEngine({ processImage: vi.fn(async () => { throw staleError() }) })
    const { store } = setup({ engine, ui: <ImageActions item={img('hero.jpg')} /> })
    await act(async () => { await store.optimizeScanImage(SCAN, img('hero.jpg'), { ...DEFAULT_SETTINGS }, { mode: 'optimized' }) })
    await waitFor(() => expect(slot('prs-it-problem')).not.toBeNull())
    expect(slot('prs-it-problem')).toHaveTextContent('Sayfa güncellendi — yenileyin.')
    expect(slot('prs-it-problem')).not.toHaveTextContent('desteklemiyor')
    expect(slot('prs-it-reload')).toHaveTextContent('Sayfayı yenile')
    const menu = await openMenu()
    expect(slot('prs-it-stale', menu)).toHaveTextContent('Sayfa güncellendi')
    expect(menu.querySelector('[data-action="reload"]')).not.toBeNull()
    expect(menu.querySelector('[data-action="original"]')).not.toBeNull()
    expect(menu.querySelector('[data-action="optimize"]')).toBeNull()
    expect(menu.querySelector('[data-action="tool"]')).toBeNull()
  })

  it('araç görünümü: motor desteği "bayat paket" ise yenileme bandı (tarayıcı desteklemiyor değil); WebP "üretemiyor" denmez', async () => {
    const engine = fakeEngine({ engineSupport: async () => ({ worker: false, webp: false, staleBundle: true }) })
    setup({ engine, ui: <ImageToolView onBack={() => {}} /> })
    const banner = await waitFor(() => { const b = slot('prs-it-stale-banner'); expect(b).not.toBeNull(); return b })
    expect(banner).toHaveTextContent('Sayfa güncellendi — yenileyin.')
    expect(slot('prs-it-reload', banner)).not.toBeNull()
    expect(document.body.textContent).not.toContain(TR['prs.it.set.webpOff'])
  })
})
