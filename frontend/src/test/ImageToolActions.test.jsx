import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

// Görsel aracı — tarama görselinin "İndir" menüsü, toplu indirme (ZIP) düğmesi + durum çubuğu ve "not ne olurdu?" kartı.
// Depo gerçek (sahte motor + sahte sunucu isteğiyle kurulur); motor bağdaştırıcısı taklit (önce / sonra penceresi onu okur).
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

import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { ImageToolProvider } from '../components/page/resourcescan/imagetool/ImageToolContext.jsx'
import { createImageToolStore } from '../components/page/resourcescan/imagetool/imageToolStore.js'
import ImageActions from '../components/page/resourcescan/imagetool/ImageActions.jsx'
import { ImageBulkButton, ImageBulkStatus } from '../components/page/resourcescan/imagetool/ImageBulk.jsx'
import GradeProjectionCard from '../components/page/resourcescan/imagetool/GradeProjectionCard.jsx'
import { TR } from '../i18n/tr.js'

const RENDER_BLOCKING_LABEL = TR['prs.score.render_blocking']

const SCAN = 12
const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)
const slots = (name, root = document) => [...root.querySelectorAll(`[data-slot="${name}"]`)]
const blobOf = (n, type = 'image/jpeg') => new Blob([new Uint8Array(n).fill(7)], { type })
const img = (name, bytes = 600 * 1024, over = {}) => ({ category: 'IMAGE', url: `https://ornek.example.com/img/${name}`, bytes, status: 200, ok: true, verdict: 'ok', width: 3000, height: 2000, ...over })
const deferred = () => { let resolve; const promise = new Promise((r) => { resolve = r }); return { promise, resolve } }

function fakeEngine(over = {}) {
  const calls = []
  return {
    calls,
    engineLimits: async () => eng.FALLBACK_LIMITS,
    engineSupport: async () => ({ worker: true, webp: true }),
    inspectImage: async (blob) => ({ type: 'jpeg', width: 3000, height: 2000, bytes: blob.size, hasAlpha: false, animated: false }),
    processImage: vi.fn(async (blob, options, extra) => {
      calls.push(options)
      extra?.onProgress?.('encode', 0.4)
      const resized = options.resize.method !== 'none'
      const width = resized ? (options.resize.width ?? 1500) : 3000
      const type = options.format === 'keep' || options.format === 'smallest' ? 'jpeg' : options.format
      const bytes = Math.floor(blob.size / 4)
      return {
        blob: blobOf(bytes), type, mime: `image/${type}`, width, height: Math.round((width * 2) / 3), bytes,
        original: { type: 'jpeg', width: 3000, height: 2000, bytes: blob.size, hasAlpha: false, animated: false, metadata: {} },
        savingPct: 75, quality: 80, colors: null, similarity: 0.991, resized, cropped: false, kept: false, notes: resized ? ['NOT_UPSCALED'] : [],
      }
    }),
    ...over,
  }
}
const okImage = (url) => ({ success: true, blob: blobOf(400 * 1024), name: `${url.split('/').pop().split('.')[0]}.jpg`, type: 'jpeg', width: 3000, height: 2000 })

function setup({ fetchImage, engine, canRun = true, itemsKept = true, ui, onOpenTool = vi.fn() } = {}) {
  const download = vi.fn(() => true)
  const fetch = fetchImage || vi.fn(async (_s, url) => okImage(url))
  const fake = engine || fakeEngine()
  const store = createImageToolStore({ fetchImage: fetch, engine: fake, download, sleep: async () => true })
  const view = render(
    <ImageToolProvider store={store} monitorId={100} scanId={SCAN} canRun={canRun} itemsKept={itemsKept} monitorName="Kurumsal Ana Sayfa" onOpenTool={onOpenTool}>
      {ui}
    </ImageToolProvider>,
  )
  return { store, download, fetch, engine: fake, onOpenTool, ...view }
}
const openMenu = async (root = document) => {
  pressMenuTrigger(slot('prs-it-menu', root))
  await waitFor(() => expect(slot('prs-it-menu-content')).not.toBeNull())
}
const pick = async (action) => {
  await openMenu()
  const item = document.querySelector(`[data-slot="prs-it-menu-content"] [data-action="${action}"]`)
  expect(item).not.toBeNull()
  fireEvent.click(item)
}

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  eng.engineLimits.mockResolvedValue(eng.FALLBACK_LIMITS)
  eng.engineSupport.mockResolvedValue({ worker: true, webp: true })
  eng.decodeForPreview.mockImplementation(async () => ({ width: 1200, height: 800, close: vi.fn() }))
  HTMLCanvasElement.prototype.getContext = vi.fn(() => ({ clearRect: vi.fn(), drawImage: vi.fn() }))
})

describe('tarama görseli — "İndir" menüsü', () => {
  it('bağlam yokken ve görsel olmayan nesnede hiçbir şey çizilmez (eski ekranlar değişmez)', () => {
    render(<ImageActions item={img('hero.jpg')} />)
    expect(slot('prs-it-actions')).toBeNull()
    const other = setup({ ui: <ImageActions item={{ category: 'SCRIPT', url: 'https://ornek.example.com/app.js', bytes: 10 }} /> })
    expect(slot('prs-it-actions')).toBeNull()
    other.unmount()
    // Favicon sunucuda indirilemez (404 RESOURCE_IMAGE_NOT_FOUND) — menü hiç sunulmaz
    setup({ ui: <ImageActions item={img('favicon.png', 4 * 1024, { category: 'FAVICON', width: 32, height: 32 })} /> })
    expect(slot('prs-it-actions')).toBeNull()
  })

  it('menü: özgün · optimize · önerilen genişliğe küçült · özel boyut · görsel aracında aç; öneri gerekçesiyle yazılır', async () => {
    setup({ ui: <ImageActions item={img('hero.jpg', 600 * 1024, { suggested_width: 1200, suggested_reason: 'declared', declared_width: 600 })} /> })
    const trigger = slot('prs-it-menu')
    expect(trigger).toHaveAccessibleName('İndir: https://ornek.example.com/img/hero.jpg')
    expect(slot('prs-it-suggest')).toHaveTextContent('3.000 piksel geniş, sayfa 600 piksel bildiriyor → 1.200 piksel yeter')
    expect(slot('prs-it-suggest').getAttribute('data-reason')).toBe('declared')
    await openMenu()
    const menu = slot('prs-it-menu-content')
    expect([...menu.querySelectorAll('[data-action]')].map((n) => n.getAttribute('data-action'))).toEqual(['original', 'optimize', 'resize', 'custom', 'tool'])
    expect(within(menu).getByRole('menuitem', { name: 'Küçült + optimize et (1.200 piksel genişlik)' })).toBeInTheDocument()
    expect(menu).toHaveTextContent('Görseli sunucu izlenen siteden alır')
  })

  it('öneri yoksa (küçük görsel) "küçült" seçeneği çıkmaz; alan yokken 2000 pikselden geniş görselde istemci kuralı (1920)', async () => {
    const first = setup({ ui: <ImageActions item={img('ufak.jpg', 50 * 1024, { width: 800, height: 600 })} /> })
    expect(slot('prs-it-suggest')).toBeNull()
    await openMenu()
    expect(document.querySelector('[data-action="resize"]')).toBeNull()
    first.unmount()
    setup({ ui: <ImageActions item={img('genis.jpg')} /> })
    expect(slot('prs-it-suggest').getAttribute('data-source')).toBe('client')
    await openMenu()
    expect(document.querySelector('[data-action="resize"]').getAttribute('data-width')).toBe('1920')
  })

  it.each([
    ['yetki yok', { canRun: false }, {}, 'no_permission', /"Şimdi kontrol et" ile çalıştırabilen/],
    ['nesne ayrıntısı saklanmıyor', { itemsKept: false }, {}, 'items_gone', /nesne ayrıntısı saklanmıyor/],
    ['maskeli adres', {}, { url: 'https://ornek.example.com/a.jpg?sig=*****' }, 'masked', /maskelendiği için/],
    ['SVG', {}, { url: 'https://ornek.example.com/logo.svg' }, 'svg', /SVG vektör bir biçimdir/],
    ['analizde açılmamış', {}, { ok: false, status: 404, verdict: 'broken' }, 'broken', /analiz sırasında açılmadı/],
    ['sınırdan büyük', {}, { bytes: 26 * 1024 * 1024 }, 'too_large', /25 MB indirme sınırından büyük/],
  ])('indirilemeyen görsel (%s): menü açılır ama eylem yok, NEDENİ yazar', async (_n, ctx, over, reason, text) => {
    const { fetch } = setup({ ...ctx, ui: <ImageActions item={img('hero.jpg', 600 * 1024, over)} /> })
    expect(slot('prs-it-actions').getAttribute('data-unavailable')).toBe(reason)
    await openMenu()
    const na = slot('prs-it-unavailable')
    expect(na.getAttribute('data-reason')).toBe(reason)
    expect(na).toHaveTextContent(text)
    expect(document.querySelectorAll('[data-slot="prs-it-menu-content"] [data-action]')).toHaveLength(0)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('"Optimize edilmiş": gerçek önce → sonra boyutu ve kazanç yazılır, dosya iner; AYNI seçeneğe ikinci tık yeniden işlemeden indirir', async () => {
    const { download, fetch, engine } = setup({ ui: <ImageActions item={img('hero.jpg')} /> })
    await pick('optimize')
    await waitFor(() => expect(slot('prs-it-result')).not.toBeNull())
    expect(slot('prs-it-result')).toHaveTextContent('400 KB → 100 KB')
    expect(slot('prs-it-result')).toHaveTextContent('%75')
    expect(slot('prs-it-result')).toHaveTextContent('benzerlik %99,1')
    expect(download).toHaveBeenCalledTimes(1)
    expect(download.mock.calls[0][0]).toBe('hero-opt.jpg')
    expect(slot('prs-it-actions').getAttribute('data-state')).toBe('done')
    await pick('optimize')
    await waitFor(() => expect(download).toHaveBeenCalledTimes(2))
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(engine.processImage).toHaveBeenCalledTimes(1)
  })

  it('"Özgün dosya" sunucunun verdiği adla iner; "Küçült + optimize et" önerilen genişliği kullanır ve motorun notunu gösterir', async () => {
    const { download, engine } = setup({ ui: <ImageActions item={img('hero.jpg', 600 * 1024, { suggested_width: 1200, suggested_reason: 'declared', declared_width: 600 })} /> })
    await pick('original')
    await waitFor(() => expect(download).toHaveBeenCalledTimes(1))
    expect(download.mock.calls[0][0]).toBe('hero.jpg')
    expect(slot('prs-it-result')).toBeNull()                        // özgün indirme bir "sonuç" değildir
    await pick('resize')
    await waitFor(() => expect(slot('prs-it-result')).not.toBeNull())
    expect(engine.calls[0].resize).toEqual({ method: 'scale', width: 1200 })
    expect(download.mock.calls[1][0]).toBe('hero-1200w.jpg')
    expect(slot('prs-it-result')).toHaveTextContent('3000×2000 → 1200×800')
    expect(slot('prs-it-notes')).toHaveTextContent('Görsel büyütülmez')
  })

  it.each([
    ['413 sınırdan büyük', { success: false, status: 413, code: 'RESOURCE_IMAGE_TOO_LARGE', max_bytes: 25 * 1024 * 1024 }, /indirme sınırından \(25 MB\) büyük/],
    ['502 site vermedi', { success: false, status: 502, code: 'RESOURCE_IMAGE_FETCH_FAILED', http_status: 404 }, /Site görseli vermedi \(HTTP 404\)/],
    ['429 sınır', { success: false, status: 429, code: 'RATE_LIMITED', retryAfter: 41 }, /Yaklaşık 41 saniye sonra yeniden deneyin/],
    ['429 meşgul', { success: false, status: 429, code: 'RESOURCE_IMAGE_BUSY', retryAfter: 2 }, /başka görsel isteklerini işliyor\. Yaklaşık 2 saniye/],
    ['409 maskeli', { success: false, status: 409, code: 'RESOURCE_IMAGE_URL_MASKED' }, /gizli değer maskelendiği için sunucu bu görseli alamaz/],
    ['409 nesneler gitmiş', { success: false, status: 409, code: 'RESOURCE_SCAN_ITEMS_GONE' }, /nesne ayrıntısı kullanılamıyor: analiz henüz bitmedi ya da ayrıntı artık saklanmıyor/],
    ['415 desteklenmeyen', { success: false, status: 415, code: 'RESOURCE_IMAGE_UNSUPPORTED' }, /desteklenen bir görsel değil/],
    ['eski sunucu (404 NOT_FOUND)', { success: false, status: 404, code: 'NOT_FOUND' }, /bu sürümü görsel indirmeyi ve not hesabını desteklemiyor/],
    ['eski sunucu (405)', { success: false, status: 405 }, /bu sürümü görsel indirmeyi ve not hesabını desteklemiyor/],
    ['403', { success: false, status: 403, code: 'FORBIDDEN' }, /bu işlem için yetkiniz yok/],
    ['ağ', { success: false, status: 0 }, /Sunucuya ulaşılamadı/],
  ])('sunucu sorunu (%s) satırın altında kendi açık metniyle yazılır; hiçbir şey inmez', async (_n, res, text) => {
    const { download } = setup({ fetchImage: vi.fn(async () => res), ui: <ImageActions item={img('hero.jpg')} /> })
    await pick('optimize')
    await waitFor(() => expect(slot('prs-it-problem')).not.toBeNull())
    expect(slot('prs-it-problem')).toHaveTextContent(text)
    expect(slot('prs-it-result')).toBeNull()
    expect(download).not.toHaveBeenCalled()
    expect(slot('prs-it-actions').getAttribute('data-state')).toBe('failed')
  })

  it('motor hatası kendi metniyle yazılır (ör. görsel açılamadı)', async () => {
    const engine = fakeEngine({ processImage: vi.fn(async () => { throw Object.assign(new Error('x'), { name: 'EngineError', code: 'DECODE_FAILED', params: {} }) }) })
    setup({ engine, ui: <ImageActions item={img('hero.jpg')} /> })
    await pick('optimize')
    await waitFor(() => expect(slot('prs-it-problem')).not.toBeNull())
    expect(slot('prs-it-problem')).toHaveTextContent('Görsel açılamadı: dosya bozuk ya da yarım inmiş olabilir')
  })

  it('sürerken durum satırı + "Vazgeç"; vazgeçince satır başa döner, geç gelen yanıt indirmez', async () => {
    const gate = deferred()
    const { download } = setup({ fetchImage: vi.fn(async () => gate.promise), ui: <ImageActions item={img('hero.jpg')} /> })
    await pick('optimize')
    await waitFor(() => expect(slot('prs-it-cancel')).not.toBeNull())
    expect(slot('prs-it-status')).toHaveTextContent('Görsel izlenen siteden alınıyor…')
    expect(slot('prs-it-menu').getAttribute('aria-busy')).toBe('true')
    fireEvent.click(slot('prs-it-cancel'))
    await waitFor(() => expect(slot('prs-it-cancel')).toBeNull())
    expect(slot('prs-it-actions').getAttribute('data-state')).toBe('idle')
    await act(async () => { gate.resolve(okImage('https://ornek.example.com/img/hero.jpg')); await Promise.resolve() })
    expect(download).not.toHaveBeenCalled()
    expect(slot('prs-it-result')).toBeNull()
  })

  it('"Özel boyut": boş genişlikte hata ALANIN altında ve istek gitmez; geçerli değerle o genişliğe küçültülür', async () => {
    const { engine, download, fetch } = setup({ ui: <ImageActions item={img('ufak.jpg', 300 * 1024, { width: 1600, height: 900 })} /> })
    await pick('custom')
    const dlg = await waitFor(() => { const d = slot('prs-it-custom'); expect(d).not.toBeNull(); return d })
    fireEvent.click(slot('prs-it-custom-go'))
    await waitFor(() => expect(dlg.querySelector('[data-field="width"]')).toHaveTextContent('Genişlik ya da yükseklik yazın.'))
    expect(fetch).not.toHaveBeenCalled()
    fireEvent.change(slot('prs-it-width', dlg), { target: { value: 'abc' } })
    fireEvent.click(slot('prs-it-custom-go'))
    await waitFor(() => expect(dlg.querySelector('[data-field="width"]')).toHaveTextContent('1 ile 30.000 arasında tam sayı yazın.'))
    fireEvent.change(slot('prs-it-width', dlg), { target: { value: '640' } })
    expect(dlg.querySelector('[data-field="width"]')).not.toHaveTextContent('tam sayı yazın')   // düzenlenen alanın hatası kalkar
    fireEvent.click(slot('prs-it-custom-go'))
    await waitFor(() => expect(download).toHaveBeenCalledTimes(1))
    expect(engine.calls[0].resize).toEqual({ method: 'scale', width: 640 })
    expect(download.mock.calls[0][0]).toBe('ufak-640w.jpg')
    expect(slot('prs-it-custom')).toBeNull()
  })

  it('"Görsel aracında aç": görsel (önerilen genişlik ayarıyla) kuyruğa düşer ve araç görünümüne geçilir', async () => {
    const { store, onOpenTool } = setup({ ui: <ImageActions item={img('hero.jpg')} /> })
    await pick('tool')
    await waitFor(() => expect(onOpenTool).toHaveBeenCalledTimes(1))
    expect(store.queue()).toHaveLength(1)
    expect(store.queue()[0]).toMatchObject({ source: 'scan', name: 'hero.jpg', own: { method: 'scale', width: '1920' } })
  })

  it('"Karşılaştır" yalnız işlenmiş görselde; pencere iki boyutu ve benzerliği gösterir', async () => {
    setup({ ui: <ImageActions item={img('hero.jpg')} /> })
    expect(slot('prs-it-compare')).toBeNull()
    await pick('optimize')
    await waitFor(() => expect(slot('prs-it-compare')).not.toBeNull())
    fireEvent.click(slot('prs-it-compare'))
    const dlg = await waitFor(() => { const d = slot('prs-it-compare-dialog'); expect(d).not.toBeNull(); return d })
    await waitFor(() => expect(dlg.getAttribute('data-status')).toBe('ready'))
    expect(slot('prs-it-cmp-sizes', dlg)).toHaveTextContent('Önce400 KB')
    expect(slot('prs-it-cmp-sizes', dlg)).toHaveTextContent('Sonra100 KB')
    expect(slot('prs-it-cmp-similarity', dlg)).toHaveTextContent('%99,1')
    expect(eng.decodeForPreview).toHaveBeenCalledTimes(2)
  })
})

describe('toplu indirme — düğme, pencere, durum çubuğu', () => {
  const items = [img('a.jpg', 900 * 1024, { suggested_width: 1200, suggested_reason: 'declared', declared_width: 600 }), img('b.jpg', 700 * 1024, { width: 1000 }),
    img('c.jpg', 500 * 1024, { width: 1000 }), img('logo.svg', 400 * 1024), img('ufak.jpg', 20 * 1024)]
  const ui = <><ImageBulkStatus /><ImageBulkButton items={items} /></>

  it('pencere neyin ineceğini söyler (sayı, toplam boyut, indirilemeyenler nedeniyle, önerilen genişlik); "Başlat" → çubuk → kısmi sonuç', async () => {
    const fetchImage = vi.fn(async (_s, url) => (url.endsWith('b.jpg') ? { success: false, status: 502, code: 'RESOURCE_IMAGE_FETCH_FAILED', http_status: 500 } : okImage(url)))
    const { download, engine } = setup({ fetchImage, ui })
    fireEvent.click(slot('prs-it-bulk-open'))
    const dlg = await waitFor(() => { const d = slot('prs-it-bulk-dialog'); expect(d).not.toBeNull(); return d })
    expect(dlg.getAttribute('data-count')).toBe('3')
    expect(slot('prs-it-bulk-count', dlg)).toHaveTextContent('3 büyük görsel (2,05 MB)')
    expect(slot('prs-it-bulk-skipped', dlg)).toHaveTextContent('1 görsel indirilemeyecek')
    expect(dlg.querySelector('[data-reason="svg"]')).toHaveTextContent('1 × SVG vektör bir biçimdir')
    expect(dlg).toHaveTextContent('1 görselin önerilen genişliği var')
    fireEvent.click(slot('prs-it-bulk-start'))
    await waitFor(() => expect(slot('prs-it-bulk-bar')?.getAttribute('data-state')).toBe('finished'))
    expect(slot('prs-it-bulk-dialog')).toBeNull()
    const bar = slot('prs-it-bulk-bar')
    expect(bar.getAttribute('data-kind')).toBe('partial')
    expect(bar).toHaveTextContent('2 görsel ZIP olarak indirildi; 2 görsel alınamadı ya da işlenemedi')
    expect(download).toHaveBeenCalledTimes(1)
    expect(download.mock.calls[0][0]).toMatch(/^kurumsal-ana-sayfa-optimize-gorseller-\d{8}-\d{4}\.zip$/)
    expect(engine.calls.map((o) => o.resize)).toEqual(expect.arrayContaining([{ method: 'scale', width: 1200 }, { method: 'none' }]))
    fireEvent.click(within(bar).getByRole('button', { name: 'Kapat' }))
    await waitFor(() => expect(slot('prs-it-bulk-bar')).toBeNull())
  })

  it('sürerken çubuk ilerlemeyi yazar; "Vazgeç" → hiçbir şey inmez, "iptal edildi"', async () => {
    const gate = deferred()
    const { download } = setup({ fetchImage: vi.fn(async () => gate.promise), ui })
    fireEvent.click(slot('prs-it-bulk-open'))
    await waitFor(() => expect(slot('prs-it-bulk-start')).not.toBeNull())
    fireEvent.click(slot('prs-it-bulk-start'))
    await waitFor(() => expect(slot('prs-it-bulk-bar')?.getAttribute('data-state')).toBe('running'))
    expect(slot('prs-it-bulk-status')).toHaveTextContent('Görseller hazırlanıyor… 0 / 3')
    fireEvent.click(slot('prs-it-bulk-cancel'))
    await waitFor(() => expect(slot('prs-it-bulk-status')).toHaveTextContent('İptal ediliyor…'))
    await act(async () => { gate.resolve({ success: false, status: 0 }); await Promise.resolve() })
    await waitFor(() => expect(slot('prs-it-bulk-bar')?.getAttribute('data-kind')).toBe('cancelled'))
    expect(slot('prs-it-bulk-bar')).toHaveTextContent('Toplu indirme iptal edildi; hiçbir şey indirilmedi.')
    expect(download).not.toHaveBeenCalled()
  })

  it('429 + Retry-After: çubuk beklenen süreyi söyler, sonra tamamlanır', async () => {
    const hold = deferred()
    let first = true
    const fetchImage = vi.fn(async (_s, url) => {
      if (first) { first = false; return { success: false, status: 429, code: 'RATE_LIMITED', retryAfter: 12 } }
      return okImage(url)
    })
    const download = vi.fn(() => true)
    const store = createImageToolStore({ fetchImage, engine: fakeEngine(), download, sleep: async () => { await hold.promise; return true } })
    render(
      <ImageToolProvider store={store} monitorId={100} scanId={SCAN} canRun itemsKept monitorName="Site" onOpenTool={() => {}}>
        <ImageBulkStatus /><ImageBulkButton items={[img('a.jpg', 900 * 1024, { width: 1000 })]} />
      </ImageToolProvider>,
    )
    fireEvent.click(slot('prs-it-bulk-open'))
    await waitFor(() => expect(slot('prs-it-bulk-start')).not.toBeNull())
    fireEvent.click(slot('prs-it-bulk-start'))
    await waitFor(() => expect(slot('prs-it-bulk-status')).toHaveTextContent('sunucu sınırı: 12 sn bekleniyor'))
    await act(async () => { hold.resolve(); await Promise.resolve() })
    await waitFor(() => expect(slot('prs-it-bulk-bar')?.getAttribute('data-kind')).toBe('done'))
    expect(slot('prs-it-bulk-bar')).toHaveTextContent('1 görsel optimize edilip ZIP olarak indirildi (kazanç 300 KB).')
    expect(fetchImage).toHaveBeenCalledTimes(2)
  })

  it('yetki yokken pencere nedenini söyler ve "Başlat" kapalıdır; büyük görsel yoksa "yok" der', async () => {
    const a = setup({ canRun: false, ui })
    fireEvent.click(slot('prs-it-bulk-open'))
    await waitFor(() => expect(slot('prs-it-bulk-blocked')).not.toBeNull())
    expect(slot('prs-it-bulk-blocked').getAttribute('data-reason')).toBe('no_permission')
    expect(slot('prs-it-bulk-start')).toBeDisabled()
    a.unmount()
    setup({ ui: <ImageBulkButton items={[img('ufak.jpg', 20 * 1024)]} /> })
    fireEvent.click(slot('prs-it-bulk-open'))
    await waitFor(() => expect(slot('prs-it-bulk-none')).not.toBeNull())
    expect(slot('prs-it-bulk-none')).toHaveTextContent('Bu analizde büyük görsel yok.')
    expect(slot('prs-it-bulk-start')).toBeDisabled()
  })

  it('hiçbir görsel alınamazsa ZIP inmez; çubuk ilk nedeni söyler (başarı gibi görünmez)', async () => {
    const { download } = setup({ fetchImage: vi.fn(async () => ({ success: false, status: 502, code: 'RESOURCE_IMAGE_FETCH_FAILED' })), ui: <><ImageBulkStatus /><ImageBulkButton items={[img('a.jpg', 900 * 1024, { width: 1000 })]} /></> })
    fireEvent.click(slot('prs-it-bulk-open'))
    await waitFor(() => expect(slot('prs-it-bulk-start')).not.toBeNull())
    fireEvent.click(slot('prs-it-bulk-start'))
    await waitFor(() => expect(slot('prs-it-bulk-bar')?.getAttribute('data-kind')).toBe('none'))
    expect(slot('prs-it-bulk-bar')).toHaveTextContent('Görsellerin hiçbiri alınamadı; ZIP indirilmedi. İlk neden: Site görseli vermedi')
    expect(slot('prs-it-bulk-bar').querySelector('[data-slot="alert"]').getAttribute('data-tone')).toBe('danger')
    expect(download).not.toHaveBeenCalled()
  })
})

describe('"Görseller optimize edilse not ne olurdu?" kartı', () => {
  const scan = { id: SCAN, status: 'DONE', grade: 61 }
  const analysis = { images: { count: 12, bytes: 3_800_000 } }
  const data = (over = {}) => ({
    algorithm_version: 3, capped: false, applied: 4, ignored: 0,
    before: { grade: 61, letter: 'D', page_bytes: 5_242_880, image_bytes: 3_984_588, load_ms: 4200, large_images: 6,
      scores: { images: { score: 20, lost: 12 }, page_weight: { score: 55, lost: 9 }, caching: { score: 80, lost: 1 } } },
    after: { grade: 78, letter: 'C', page_bytes: 2_726_297, image_bytes: 1_468_006, load_ms: 3100, large_images: 0,
      scores: { images: { score: 92, lost: 1.2 }, page_weight: { score: 81, lost: 3.8 }, caching: { score: 80, lost: 1 } } },
    ...over,
  })
  const card = <GradeProjectionCard scan={scan} analysis={analysis} />

  it('tamamlanmamış analizde, görselsiz analizde ve bağlam yokken kart çizilmez', () => {
    render(card)
    expect(slot('prs-it-projection')).toBeNull()
    const a = setup({ ui: <GradeProjectionCard scan={{ id: SCAN, status: 'FAILED' }} analysis={analysis} /> })
    expect(slot('prs-it-projection')).toBeNull()
    a.unmount()
    setup({ ui: <GradeProjectionCard scan={scan} analysis={{ images: { count: 0 } }} /> })
    expect(slot('prs-it-projection')).toBeNull()
  })

  it('tek tıkla sunucunun ölçümüyle hesaplanır: not önce → sonra, fark, sayfa / görsel boyutu, bileşenler', async () => {
    api.monitoring.resourceScanImageProjection.mockResolvedValue({ success: true, data: data() })
    setup({ ui: card })
    const root = slot('prs-it-projection')
    expect(root.getAttribute('data-status')).toBe('idle')
    expect(slot('prs-it-proj-idle')).not.toBeNull()
    fireEvent.click(slot('prs-it-proj-run'))
    await waitFor(() => expect(root.getAttribute('data-status')).toBe('ok'))
    expect(api.monitoring.resourceScanImageProjection).toHaveBeenCalledWith(100, SCAN, { use_measured: true })
    expect(slot('prs-it-proj-before')).toHaveTextContent('61')
    expect(slot('prs-it-proj-after')).toHaveTextContent('78')
    expect(slot('prs-it-proj-after').getAttribute('data-tone')).not.toBe('muted')
    expect(slot('prs-it-proj-diff')).toHaveTextContent('+17 puan')
    const kpis = slots('prs-it-proj-kpi')
    // Yükleme tahmini projeksiyonda değişmez (ölçülen sürelerden gelir) — optimizasyonun sonucu gibi gösterilmez
    expect(kpis.map((k) => k.getAttribute('data-key'))).toEqual(['page', 'images', 'large'])
    expect(kpis[0]).toHaveTextContent('5 MB → 2,6 MB')
    expect(kpis[2]).toHaveTextContent('6 → 0')
    const rows = slots('prs-it-proj-row')
    expect(rows.map((r) => r.getAttribute('data-key'))).toEqual(['images', 'page_weight', 'caching'])
    expect(rows[0].getAttribute('data-changed')).toBe('true')
    expect(rows[0]).toHaveTextContent('20 → 92')
    expect(rows[2].getAttribute('data-changed')).toBe('false')
    expect(slot('prs-it-proj-applied')).toHaveTextContent('4 görselin boyutu değiştirilerek hesaplandı.')
    expect(slot('prs-it-proj-applied')).toHaveTextContent('Yüklenme tahmini ve puanı burada değişmez')
    expect(slot('prs-it-proj-applied')).not.toHaveTextContent('saklanan puanla')
    expect(slot('prs-it-proj-partial')).toBeNull()
  })

  it('sınırlı (kısmi) analiz: uyarı + soluk notlar, fark "en az … (kısmi)" — kesin sonuç gibi sunulmaz', async () => {
    api.monitoring.resourceScanImageProjection.mockResolvedValue({ success: true, data: data({ capped: true }) })
    setup({ ui: card })
    fireEvent.click(slot('prs-it-proj-run'))
    await waitFor(() => expect(slot('prs-it-proj-partial')).not.toBeNull())
    expect(slot('prs-it-projection').getAttribute('data-capped')).toBe('true')
    expect(slot('prs-it-proj-partial')).toHaveTextContent('Kısmi sonuç')
    expect(slot('prs-it-proj-before').getAttribute('data-tone')).toBe('muted')
    expect(slot('prs-it-proj-after').getAttribute('data-tone')).toBe('muted')
    expect(slot('prs-it-proj-diff')).toHaveTextContent('en az +17 puan (kısmi)')
  })

  it('bilinmeyen not "—" ve soluk (asla yeşil); fark "bilinmiyor" (0 uydurulmaz)', async () => {
    api.monitoring.resourceScanImageProjection.mockResolvedValue({ success: true, data: data({ after: { grade: null, page_bytes: null, scores: {} } }) })
    setup({ ui: card })
    fireEvent.click(slot('prs-it-proj-run'))
    await waitFor(() => expect(slot('prs-it-proj-result')).not.toBeNull())
    expect(slot('prs-it-proj-after')).toHaveTextContent('—')
    expect(slot('prs-it-proj-after').getAttribute('data-tone')).toBe('muted')
    expect(slot('prs-it-proj-diff').getAttribute('data-known')).toBe('false')
    expect(slot('prs-it-proj-diff')).toHaveTextContent('değişim bilinmiyor')
  })

  it.each([
    ['404 NOT_FOUND', { success: false, status: 404, code: 'NOT_FOUND' }],
    ['405', { success: false, status: 405 }],
  ])('eski sunucu (%s): "bu sürümde yok" der, hesapla düğmesi kalkar, hata gibi görünmez', async (_n, res) => {
    api.monitoring.resourceScanImageProjection.mockResolvedValue(res)
    setup({ ui: card })
    fireEvent.click(slot('prs-it-proj-run'))
    await waitFor(() => expect(slot('prs-it-proj-unsupported')).not.toBeNull())
    expect(slot('prs-it-proj-unsupported')).toHaveTextContent('desteklemiyor')
    expect(slot('prs-it-proj-failed')).toBeNull()
    expect(slot('prs-it-proj-run')).toBeNull()
  })

  it('hata (500 / okunamayan yanıt / tamamlanmamış analiz): "hesaplanamadı" + neden; yeniden denenebilir — sonuç uydurulmaz', async () => {
    api.monitoring.resourceScanImageProjection.mockResolvedValueOnce({ success: false, status: 500, code: 'INTERNAL_ERROR', error: 'Beklenmeyen bir sunucu hatası oluştu; birkaç dakika sonra yeniden deneyin.' })
    setup({ ui: card })
    fireEvent.click(slot('prs-it-proj-run'))
    await waitFor(() => expect(slot('prs-it-proj-failed')).not.toBeNull())
    expect(slot('prs-it-proj-failed')).toHaveTextContent('Not hesaplanamadı')
    expect(slot('prs-it-proj-failed')).toHaveTextContent('Beklenmeyen bir sunucu hatası oluştu')
    expect(slot('prs-it-proj-result')).toBeNull()
    api.monitoring.resourceScanImageProjection.mockResolvedValueOnce({ success: true, data: { tuhaf: true } })
    fireEvent.click(slot('prs-it-proj-run'))
    await waitFor(() => expect(slot('prs-it-proj-failed')).toHaveTextContent('Sunucunun yanıtı okunamadı'))
    api.monitoring.resourceScanImageProjection.mockResolvedValueOnce({ success: false, status: 409, code: 'RESOURCE_SCAN_NOT_DONE' })
    fireEvent.click(slot('prs-it-proj-run'))
    await waitFor(() => expect(slot('prs-it-proj-failed')).toHaveTextContent('analiz tamamlanmadığı için hesap yapılamıyor'))
    api.monitoring.resourceScanImageProjection.mockResolvedValueOnce({ success: true, data: data() })
    fireEvent.click(slot('prs-it-proj-run'))
    await waitFor(() => expect(slot('prs-it-proj-result')).not.toBeNull())
  })

  it.each([
    ['algorithm', 'eski bir analiz sürümüyle yapıldığı için'],
    ['baseline', 'saklanan verisi notunu yeniden üretmeye yetmediği için'],
  ])('yapılamayan projeksiyon (409 NOT_PROJECTABLE, %s): nötr durum + "Yeniden analiz et"; hata bandı ve "değişmez" yok', async (reason, text) => {
    api.monitoring.resourceScanImageProjection.mockResolvedValue({ success: false, status: 409, code: 'RESOURCE_SCAN_NOT_PROJECTABLE', reason, error: 'x' })
    const onStart = vi.fn()
    setup({ ui: <GradeProjectionCard scan={scan} analysis={analysis} onStart={onStart} /> })
    fireEvent.click(slot('prs-it-proj-run'))
    await waitFor(() => expect(slot('prs-it-proj-na')).not.toBeNull())
    expect(slot('prs-it-proj-na').getAttribute('data-reason')).toBe(reason)
    expect(slot('prs-it-proj-na')).toHaveTextContent(text)
    expect(slot('prs-it-proj-failed')).toBeNull()
    expect(slot('prs-it-proj-result')).toBeNull()
    expect(slot('prs-it-proj-diff')).toBeNull()
    expect(slot('prs-it-proj-run')).toBeNull()
    fireEvent.click(slot('prs-it-proj-new-scan'))
    expect(onStart).toHaveBeenCalledTimes(1)
  })

  it('yapılamayan projeksiyon: çalıştırma yetkisi yoksa ya da analiz sürüyorsa yeniden analiz düğmesi yok', async () => {
    api.monitoring.resourceScanImageProjection.mockResolvedValue({ success: false, status: 409, code: 'RESOURCE_SCAN_NOT_PROJECTABLE', reason: 'algorithm' })
    const a = setup({ canRun: false, ui: <GradeProjectionCard scan={scan} analysis={analysis} onStart={vi.fn()} /> })
    fireEvent.click(slot('prs-it-proj-run'))
    await waitFor(() => expect(slot('prs-it-proj-na')).not.toBeNull())
    expect(slot('prs-it-proj-new-scan')).toBeNull()
    a.unmount()
    setup({ ui: <GradeProjectionCard scan={scan} analysis={analysis} onStart={vi.fn()} running={{ id: 9 }} /> })
    fireEvent.click(slot('prs-it-proj-run'))
    await waitFor(() => expect(slot('prs-it-proj-na')).not.toBeNull())
    expect(slot('prs-it-proj-new-scan')).toBeNull()
  })

  it('sunucu "nesne ayrıntısı yok" derse (409 ITEMS_GONE) nötr durum; 429 bilgi tonunda bekleme süresiyle', async () => {
    api.monitoring.resourceScanImageProjection.mockResolvedValueOnce({ success: false, status: 409, code: 'RESOURCE_SCAN_ITEMS_GONE' })
    const a = setup({ ui: card })
    fireEvent.click(slot('prs-it-proj-run'))
    await waitFor(() => expect(slot('prs-it-proj-gone')).not.toBeNull())
    expect(slot('prs-it-proj-failed')).toBeNull()
    a.unmount()
    api.monitoring.resourceScanImageProjection.mockResolvedValueOnce({ success: false, status: 429, code: 'RATE_LIMITED', retryAfter: 20 })
    setup({ ui: card })
    fireEvent.click(slot('prs-it-proj-run'))
    await waitFor(() => expect(slot('prs-it-proj-failed')).not.toBeNull())
    expect(slot('prs-it-proj-failed').getAttribute('data-code')).toBe('RATE_LIMITED')
    expect(slot('prs-it-proj-failed').querySelector('[data-slot="alert"]').getAttribute('data-tone')).toBe('info')
    expect(slot('prs-it-proj-failed')).toHaveTextContent('20')
    expect(slot('prs-it-proj-run')).not.toBeNull()
  })

  it('reused_components: bileşen "yeniden hesaplanmadı" işaretlenir ve yöntem notunda adıyla geçer; yükleme satırı da sabittir', async () => {
    const both = (side) => ({ ...side, scores: { ...side.scores, render_blocking: { score: 64, lost: 3.6 }, load_time: { score: 70, lost: 6 } } })
    api.monitoring.resourceScanImageProjection.mockResolvedValue({ success: true, data: data({ reused_components: ['render_blocking'], before: both(data().before), after: both(data().after) }) })
    setup({ ui: card })
    fireEvent.click(slot('prs-it-proj-run'))
    await waitFor(() => expect(slot('prs-it-proj-result')).not.toBeNull())
    const row = (k) => slots('prs-it-proj-row').find((r) => r.getAttribute('data-key') === k)
    expect(row('render_blocking').getAttribute('data-fixed')).toBe('true')
    expect(row('render_blocking').getAttribute('data-changed')).toBe('false')
    expect(within(row('render_blocking')).getByText('yeniden hesaplanmadı')).not.toBeNull()
    expect(row('load_time').getAttribute('data-fixed')).toBe('true')
    expect(row('images').getAttribute('data-fixed')).toBeNull()
    expect(slot('prs-it-proj-applied')).toHaveTextContent('saklanan puanla hesaplandı')
    expect(slot('prs-it-proj-applied')).toHaveTextContent(RENDER_BLOCKING_LABEL)
  })

  it('nesne ayrıntısı saklanmayan analizde düğme yok, nedeni yazılır', () => {
    setup({ itemsKept: false, ui: card })
    expect(slot('prs-it-proj-gone')).toHaveTextContent('nesne ayrıntısı kullanılamıyor')
    expect(slot('prs-it-proj-run')).toBeNull()
  })

  it('"Bu sekmede işlediklerim": görsel işlenene dek kapalı; işlenince GERÇEK sonuçlar gönderilir; yeni görsel işlenince eski sonuç "eski" der', async () => {
    api.monitoring.resourceScanImageProjection.mockResolvedValue({ success: true, data: data({ applied: 1 }) })
    const { store } = setup({ ui: card })
    const mine = () => within(slot('prs-it-proj-basis')).getByRole('button', { name: /Bu sekmede işlediklerim|Bu sekmede işlediğim/ })
    expect(mine()).toBeDisabled()
    await act(async () => { await store.optimizeScanImage(SCAN, img('hero.jpg'), { format: 'keep', qualityMode: 'auto', quality: 80, targetKb: '200', method: 'none', width: '', height: '', background: '#ffffff' }, { silent: true }) })
    await waitFor(() => expect(mine()).not.toBeDisabled())
    expect(mine()).toHaveAccessibleName('Bu sekmede işlediğim (1)')
    fireEvent.click(mine())
    fireEvent.click(slot('prs-it-proj-run'))
    await waitFor(() => expect(slot('prs-it-proj-result')).not.toBeNull())
    expect(api.monitoring.resourceScanImageProjection).toHaveBeenCalledWith(100, SCAN, {
      use_measured: false, images: [{ url: 'https://ornek.example.com/img/hero.jpg', bytes: 100 * 1024, width: 3000, height: 2000, type: 'jpeg' }] })
    expect(slot('prs-it-proj-outdated')).toBeNull()
    await act(async () => { await store.optimizeScanImage(SCAN, img('ikinci.jpg'), { format: 'keep', qualityMode: 'auto', quality: 80, targetKb: '200', method: 'none', width: '', height: '', background: '#ffffff' }, { silent: true }) })
    await waitFor(() => expect(slot('prs-it-proj-outdated')).not.toBeNull())
    expect(slot('prs-it-proj-result').getAttribute('data-outdated')).toBe('true')
  })

  it('başka analize geçilince eski sonuç gösterilmez; geç gelen yanıt yeni analizin kartına yazılmaz', async () => {
    const gate = deferred()
    api.monitoring.resourceScanImageProjection.mockReturnValueOnce(gate.promise)
    const store = createImageToolStore({ fetchImage: vi.fn(), engine: fakeEngine(), download: vi.fn(() => true) })
    const tree = (s) => (
      <ImageToolProvider store={store} monitorId={100} scanId={s.id} canRun itemsKept monitorName="Site" onOpenTool={() => {}}>
        <GradeProjectionCard scan={s} analysis={analysis} />
      </ImageToolProvider>
    )
    const { rerender } = render(tree(scan))
    fireEvent.click(slot('prs-it-proj-run'))
    await waitFor(() => expect(slot('prs-it-projection').getAttribute('data-status')).toBe('loading'))
    rerender(tree({ id: 13, status: 'DONE', grade: 70 }))
    await waitFor(() => expect(slot('prs-it-projection').getAttribute('data-status')).toBe('idle'))
    await act(async () => { gate.resolve({ success: true, data: data() }); await Promise.resolve() })
    expect(slot('prs-it-projection').getAttribute('data-status')).toBe('idle')
    expect(slot('prs-it-proj-result')).toBeNull()
  })
})
