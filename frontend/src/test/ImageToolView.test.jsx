import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { unzipSync, strFromU8 } from 'fflate'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'

// Görsel aracı görünümü (yükle → ayarla → indir), önce / sonra karşılaştırma penceresi ve duyarlı set penceresi.
// Depo gerçek (sahte motorla); pencereler motor bağdaştırıcısını okur — o taklit edilir (motor paketi ayrı gelir).
const eng = vi.hoisted(() => ({
  FALLBACK_LIMITS: { maxInputBytes: 25 * 1024 * 1024, maxPixels: 50_000_000, maxSide: 30_000 },
  loadEngine: vi.fn(), engineLimits: vi.fn(), engineSupport: vi.fn(), inspectImage: vi.fn(), processImage: vi.fn(), decodeForPreview: vi.fn(),
  isEngineError: (e) => e?.name === 'EngineError' && typeof e?.code === 'string',
}))
vi.mock('../components/page/resourcescan/imagetool/engineClient.js', () => eng)

import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { ImageToolProvider } from '../components/page/resourcescan/imagetool/ImageToolContext.jsx'
import { createImageToolStore } from '../components/page/resourcescan/imagetool/imageToolStore.js'
import ImageToolView from '../components/page/resourcescan/imagetool/ImageToolView.jsx'
import ImageCompareDialog, { PREVIEW_MAX_PIXELS, PREVIEW_MAX_SIDE, dividerKey, previewSize } from '../components/page/resourcescan/imagetool/ImageCompareDialog.jsx'
import ResponsiveSetDialog from '../components/page/resourcescan/imagetool/ResponsiveSetDialog.jsx'
import { TOOL_MAX_FILES, resultSummary } from '../components/page/resourcescan/imagetool/imageToolModel.js'

const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)
const slots = (name, root = document) => [...root.querySelectorAll(`[data-slot="${name}"]`)]
const blobOf = (n, type = 'image/jpeg') => new Blob([new Uint8Array(n).fill(7)], { type })
const fileOf = (name, n = 400 * 1024) => new File([new Uint8Array(n).fill(3)], name, { type: 'image/png' })
const engineError = (code, params = {}) => Object.assign(new Error(code), { name: 'EngineError', code, params })
const deferred = () => { let resolve; const promise = new Promise((r) => { resolve = r }); return { promise, resolve } }

/** Sahte motor sonucu: bayt dörtte bir; ölçeklemede istenen genişlik (doğal genişlikten büyük olamaz). */
function resultOf(blob, options, { natural = 3000, metadata = {} } = {}) {
  const want = options.resize.method !== 'none' ? (options.resize.width ?? 1500) : natural
  const width = Math.min(want, natural)
  const resized = width !== natural
  const type = options.format === 'keep' || options.format === 'smallest' ? 'jpeg' : options.format
  const bytes = Math.max(1, Math.floor(blob.size / 4))
  return {
    blob: blobOf(bytes, `image/${type}`), type, mime: `image/${type}`, width, height: Math.round((width * 2) / 3), bytes,
    original: { type: 'png', width: natural, height: Math.round((natural * 2) / 3), bytes: blob.size, hasAlpha: false, animated: false,
      metadata: { copyright: false, creation: false, location: false, ...metadata } },
    savingPct: 75, quality: 80, colors: null, similarity: 0.994, resized, cropped: false, kept: false, notes: [],
  }
}
function fakeEngine(over = {}) {
  const calls = []
  return {
    calls,
    engineLimits: async () => eng.FALLBACK_LIMITS,
    engineSupport: async () => ({ worker: true, webp: true }),
    inspectImage: async (blob) => ({ type: 'png', width: 3000, height: 2000, bytes: blob.size, hasAlpha: false, animated: false }),
    processImage: vi.fn(async (blob, options, extra) => { calls.push(options); extra?.onProgress?.('encode', 0.5); return resultOf(blob, options) }),
    ...over,
  }
}

function setup({ engine, onBack, canRun = true } = {}) {
  const download = vi.fn(() => true)
  const fake = engine || fakeEngine()
  const store = createImageToolStore({ fetchImage: vi.fn(), engine: fake, download })
  const view = render(
    <ImageToolProvider store={store} monitorId={100} scanId={12} canRun={canRun} itemsKept monitorName="Kurumsal Ana Sayfa" onOpenTool={() => {}}>
      <ImageToolView onBack={onBack} />
    </ImageToolProvider>,
  )
  return { store, download, engine: fake, ...view }
}
const addViaInput = (files) => fireEvent.change(slot('prs-it-file-input'), { target: { files } })
const entries = () => slots('prs-it-entry')
const allSettled = () => waitFor(() => {
  expect(entries().length).toBeGreaterThan(0)
  expect(entries().every((e) => ['done', 'failed'].includes(e.getAttribute('data-state')))).toBe(true)
})

let clickSpy
let downloads
beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  eng.engineLimits.mockResolvedValue(eng.FALLBACK_LIMITS)
  eng.engineSupport.mockResolvedValue({ worker: true, webp: true })
  eng.processImage.mockImplementation(async (blob, options, extra) => { extra?.onProgress?.('encode', 0.5); return resultOf(blob, options, { natural: 1000 }) })
  eng.decodeForPreview.mockImplementation(async () => ({ width: 1200, height: 800, close: vi.fn() }))
  HTMLCanvasElement.prototype.getContext = vi.fn(() => ({ clearRect: vi.fn(), drawImage: vi.fn() }))
  // Pencerelerin kendi ZIP indirmesi (utils/csvExport.downloadBlob): jsdom'da nesne adresi yok — taklit + tıklanan bağlantı kaydı
  downloads = []
  URL.createObjectURL = vi.fn((blob) => { downloads.push({ blob }); return 'blob:test' })
  URL.revokeObjectURL = vi.fn()
  clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function click() {
    if (this.download && downloads.length) downloads[downloads.length - 1].name = this.download
  })
})
afterEach(() => { clickSpy.mockRestore() })

describe('görsel aracı görünümü', () => {
  it('boş durum: bırakma alanı, sınırlar, dürüst gizlilik açıklaması, ayar paneli; indirme düğmeleri yok', async () => {
    setup()
    expect(slot('prs-it-view').getAttribute('data-layout')).toBe('wide')
    expect(slot('prs-it-drop')).toHaveTextContent('Görselleri buraya sürükleyip bırakın, yapıştırın (Ctrl+V) ya da seçin')
    expect(slot('prs-it-drop')).toHaveTextContent('En çok 30 görsel, her biri en çok 25 MB')
    expect(slot('prs-it-privacy')).toHaveTextContent('Yüklediğiniz dosyalar tarayıcınızdan çıkmaz')
    expect(slot('prs-it-privacy')).toHaveTextContent('Analizden gönderdiğiniz görselleri ise Site Monitor sunucusu izlenen siteden alır')
    expect(slot('prs-it-summary')).toHaveTextContent('Henüz görsel eklenmedi.')
    expect(slot('prs-it-zip')).toBeNull()
    expect(slot('prs-it-back')).toBeNull()
    const panel = slot('prs-it-settings-panel')
    await waitFor(() => expect([...slot('prs-it-format', panel).options].map((o) => o.value)).toEqual(['keep', 'jpeg', 'png', 'webp', 'smallest']))
    expect(slot('prs-it-method', panel).value).toBe('none')
    expect(slot('prs-it-width', panel)).toBeNull()                    // "boyutu değiştirme"de ölçü alanları yok
    expect(screen.getByRole('button', { name: 'Dosya seç' })).toBeEnabled()
  })

  it('dosya seçimi: görseller sırayla işlenir; satırda durum, önce → sonra boyut, ölçü, biçim; özet satırı toplamı yazar', async () => {
    const { engine } = setup()
    addViaInput([fileOf('kapak.png'), fileOf('logo.png', 800 * 1024)])
    await allSettled()
    const [a, b] = entries()
    expect(slot('prs-it-entry-name', a)).toHaveTextContent('kapak.png')
    expect(slot('prs-it-entry-state', a)).toHaveTextContent('Hazır')
    expect(slot('prs-it-result', a)).toHaveTextContent('400 KB → 100 KB')
    expect(slot('prs-it-result', a)).toHaveTextContent('PNG → JPEG')
    expect(slot('prs-it-result', b)).toHaveTextContent('800 KB → 200 KB')
    expect(slot('prs-it-summary')).toHaveTextContent('2 görsel · 2 hazır · 1,17 MB → 300 KB (−%75)')
    expect(engine.calls).toHaveLength(2)
    expect(engine.calls[0]).toMatchObject({ resize: { method: 'none' }, format: 'keep', quality: 'auto', targetBytes: null, preserve: { copyright: false, creation: false, location: false } })
    expect(slot('prs-it-zip')).toBeEnabled()
  })

  it('sürükle-bırak ve yapıştırma da kuyruğa ekler; metin yapıştırma yok sayılır', async () => {
    setup()
    fireEvent.dragOver(slot('prs-it-drop'), { dataTransfer: { files: [] } })
    expect(slot('prs-it-drop').getAttribute('data-dragging')).toBe('true')
    fireEvent.drop(slot('prs-it-drop'), { dataTransfer: { files: [fileOf('birakilan.png')] } })
    expect(slot('prs-it-drop').getAttribute('data-dragging')).toBeNull()
    await waitFor(() => expect(entries()).toHaveLength(1))
    fireEvent.paste(document, { clipboardData: { files: [], items: [{ kind: 'string', getAsFile: () => null }] } })
    expect(entries()).toHaveLength(1)
    fireEvent.paste(document, { clipboardData: { files: [], items: [{ kind: 'file', getAsFile: () => fileOf('yapistirilan.png') }] } })
    await waitFor(() => expect(entries()).toHaveLength(2))
    expect(entries().map((e) => slot('prs-it-entry-name', e).textContent)).toEqual(['birakilan.png', 'yapistirilan.png'])
  })

  it(`sınır: ${TOOL_MAX_FILES} görselden fazlası eklenmez ve KAÇ dosyanın eklenmediği söylenir; kuyruk dolunca dosya seçimi kapanır`, async () => {
    setup()
    addViaInput(Array.from({ length: TOOL_MAX_FILES + 2 }, (_, i) => fileOf(`g${i}.png`, 1000)))
    await waitFor(() => expect(entries()).toHaveLength(TOOL_MAX_FILES))
    expect(slot('prs-it-rejected')).toHaveTextContent('Kuyruk en çok 30 görsel alır: 2 dosya eklenmedi.')
    expect(slot('prs-it-pick')).toBeDisabled()
    expect(slot('prs-it-full')).toHaveTextContent('Kuyruk dolu (30 görsel)')
    fireEvent.click(within(slot('prs-it-rejected')).getByRole('button', { name: 'Kapat' }))
    expect(slot('prs-it-rejected')).toBeNull()
  })

  it('sınırdan büyük dosya işlenmez: satır nedenini söyler, "yeniden dene" yok; öteki dosyalar işlenir', async () => {
    const { engine } = setup()
    addViaInput([{ name: 'dev.png', size: 26 * 1024 * 1024 }, fileOf('ufak.png')])
    await allSettled()
    const [big, small] = entries()
    expect(big.getAttribute('data-state')).toBe('failed')
    expect(slot('prs-it-entry-state', big)).toHaveTextContent('İşlenemedi')
    expect(slot('prs-it-entry-problem', big)).toHaveTextContent('Dosya çok büyük: araç en çok 25 MB boyutundaki görselleri işler')
    expect(slot('prs-it-entry-retry', big)).toBeNull()
    expect(small.getAttribute('data-state')).toBe('done')
    expect(engine.calls).toHaveLength(1)
  })

  it('doğrulama: eksik ölçüde görseller BEKLER, hata alanın altında yazar; düzeltince işlenir (asla büyütülmez notu)', async () => {
    const { engine } = setup()
    const panel = slot('prs-it-settings-panel')
    fireEvent.change(slot('prs-it-method', panel), { target: { value: 'scale' } })
    expect(panel).toHaveTextContent('Görsel hiçbir zaman büyütülmez')
    expect(panel.querySelector('[data-field="width"]')).not.toHaveTextContent('Genişlik ya da yükseklik yazın.')   // dokunulmamış alan kırmızı değil
    addViaInput([fileOf('a.png')])
    await waitFor(() => expect(slot('prs-it-blocked')).not.toBeNull())
    expect(slot('prs-it-blocked')).toHaveTextContent('1 görsel bekliyor: ayarlardaki eksik ya da hatalı alan düzeltilince işlenecek.')
    expect(slot('prs-it-entry-state', entries()[0])).toHaveTextContent('Ayar bekliyor')
    expect(panel.querySelector('[data-field="width"]')).toHaveTextContent('Genişlik ya da yükseklik yazın.')
    expect(slot('prs-it-width', panel).getAttribute('aria-invalid')).toBe('true')
    expect(engine.calls).toHaveLength(0)
    fireEvent.change(slot('prs-it-width', panel), { target: { value: '99999' } })
    expect(panel.querySelector('[data-field="width"]')).toHaveTextContent('1 ile 30.000 arasında tam sayı yazın.')
    expect(engine.calls).toHaveLength(0)
    fireEvent.change(slot('prs-it-width', panel), { target: { value: '800' } })
    await allSettled()
    expect(slot('prs-it-blocked')).toBeNull()
    expect(engine.calls[0].resize).toEqual({ method: 'scale', width: 800 })
    expect(slot('prs-it-result', entries()[0])).toHaveTextContent('3000×2000 → 800×533')
    // sığdır: iki kenar da gerekli
    fireEvent.change(slot('prs-it-method', panel), { target: { value: 'fit' } })
    fireEvent.change(slot('prs-it-width', panel), { target: { value: '' } })
    expect(panel.querySelector('[data-field="width"]')).toHaveTextContent('Bu yöntem için gerekli')
    expect(slot('prs-it-reprocess')).toBeDisabled()                      // geçersiz ayarda "uygula" düğmesi kapalıdır
  })

  it('kalite kipleri: elle → kaydırıcı; hedef boyut → varsayılan 200 KB, geçersiz değer alanın altında; seçenekler motora doğru gider', async () => {
    const { engine } = setup()
    const panel = slot('prs-it-settings-panel')
    fireEvent.click(within(panel).getByRole('button', { name: 'Hedef boyut' }))
    const target = slot('prs-it-target', panel)
    expect(target.value).toBe('200')
    fireEvent.change(target, { target: { value: '0' } })
    expect(panel.querySelector('[data-field="targetKb"]')).toHaveTextContent('1 ile 25.600 KB arasında tam sayı yazın.')
    fireEvent.change(target, { target: { value: '150' } })
    addViaInput([fileOf('a.png')])
    await allSettled()
    expect(engine.calls[0]).toMatchObject({ quality: 'auto', targetBytes: 150 * 1024 })
    fireEvent.click(within(panel).getByRole('button', { name: 'Elle' }))
    expect(slot('prs-it-target', panel)).toBeNull()
    expect(within(panel).getByRole('slider')).toHaveAttribute('aria-valuenow', '80')
    fireEvent.change(slot('prs-it-format', panel), { target: { value: 'webp' } })
    fireEvent.click(slot('prs-it-reprocess'))
    await waitFor(() => expect(engine.calls).toHaveLength(2))
    expect(engine.calls[1]).toMatchObject({ quality: 80, targetBytes: null, format: 'webp' })
  })

  it('WebP bu tarayıcıda üretilemiyorsa seçenek kapalıdır ve nedeni yazılır', async () => {
    setup({ engine: fakeEngine({ engineSupport: async () => ({ worker: true, webp: false }) }) })
    const panel = slot('prs-it-settings-panel')
    await waitFor(() => expect(panel).toHaveTextContent('Bu tarayıcı WebP üretemiyor; WebP seçeneği kapalı.'))
    const webp = [...slot('prs-it-format', panel).options].find((o) => o.value === 'webp')
    expect(webp.disabled).toBe(true)
    expect(webp.textContent).toContain('bu tarayıcıda yok')
  })

  it('üst veri anahtarları varsayılan kapalı; yalnız görselde o veri varsa açılabilir', async () => {
    const engine = fakeEngine()
    engine.processImage.mockImplementation(async (blob, options) => { engine.calls.push(options); return resultOf(blob, options, { metadata: { copyright: true } }) })
    setup({ engine })
    const meta = slot('prs-it-meta')
    const sw = (kind) => meta.querySelector(`[data-meta="${kind}"] [role="switch"]`)
    for (const k of ['copyright', 'creation', 'location']) { expect(sw(k)).toBeDisabled(); expect(sw(k)).toHaveAttribute('aria-checked', 'false') }
    expect(meta).toHaveTextContent('Görselde olup olmadığı ilk işlemden sonra anlaşılır.')
    addViaInput([fileOf('a.png')])
    await allSettled()
    await waitFor(() => expect(sw('copyright')).not.toBeDisabled())
    expect(sw('creation')).toBeDisabled()
    expect(meta.querySelector('[data-meta="location"]')).toHaveTextContent('Kuyruktaki görsellerde bu bilgi yok.')
    fireEvent.click(sw('copyright'))
    expect(sw('copyright')).toHaveAttribute('aria-checked', 'true')
    expect(slot('prs-it-entry-state', entries()[0])).toHaveTextContent('Ayar değişti')
    fireEvent.click(slot('prs-it-reprocess'))
    await waitFor(() => expect(engine.calls).toHaveLength(2))
    expect(engine.calls[1].preserve).toEqual({ copyright: true, creation: false, location: false })
  })

  it('başarısız görsel nedenini söyler; "Yeniden dene" yalnız onu işler; "Kaldır" satırı siler; bir hata ötekileri durdurmaz', async () => {
    let fail = true
    const engine = fakeEngine()
    engine.processImage.mockImplementation(async (blob, options) => {
      if (blob.size === 5000 && fail) { fail = false; throw engineError('ENCODE_FAILED') }
      engine.calls.push(options)
      return resultOf(blob, options)
    })
    setup({ engine })
    addViaInput([fileOf('a.png'), fileOf('bozuk.png', 5000), fileOf('c.png')])
    await allSettled()
    expect(entries().map((e) => e.getAttribute('data-state'))).toEqual(['done', 'failed', 'done'])
    const bad = entries()[1]
    expect(slot('prs-it-entry-problem', bad)).toHaveTextContent('Görsel sıkıştırılamadı')
    expect(within(bad).getByRole('button', { name: 'Yeniden dene: bozuk.png' })).toBeInTheDocument()
    fireEvent.click(slot('prs-it-entry-retry', bad))
    await waitFor(() => expect(entries()[1].getAttribute('data-state')).toBe('done'))
    fireEvent.click(within(entries()[0]).getByRole('button', { name: 'Kaldır: a.png' }))
    expect(entries().map((e) => slot('prs-it-entry-name', e).textContent)).toEqual(['bozuk.png', 'c.png'])
  })

  it('tek dosya indirme ve "Tümünü indir (ZIP)": görseller + eşleşme listesi tek arşivde', async () => {
    const { download } = setup()
    addViaInput([fileOf('kapak.png'), fileOf('logo.png', 800 * 1024)])
    await allSettled()
    fireEvent.click(within(entries()[0]).getByRole('button', { name: 'İndir: kapak.jpg' }))
    expect(download).toHaveBeenCalledWith('kapak.jpg', expect.any(Blob))
    fireEvent.click(slot('prs-it-zip'))
    await screen.findByText('2 görsel ZIP olarak indirildi; içinde eşleşme listesi de var.')
    const [name, blob] = download.mock.calls[1]
    expect(name).toMatch(/^kurumsal-ana-sayfa-optimize-gorseller-\d{8}-\d{4}\.zip$/)
    const files = unzipSync(new Uint8Array(await blob.arrayBuffer()))
    expect(Object.keys(files).sort()).toEqual(['eslesme.csv', 'kapak.jpg', 'logo.jpg'])
    expect(strFromU8(files['eslesme.csv'])).toContain('kapak.png,kapak.jpg,409600,102400,')
  })

  it('tarayıcı indirmeyi başlatamazsa başarı tostu çıkmaz, neden söylenir', async () => {
    const { download } = setup()
    addViaInput([fileOf('kapak.png')])
    await allSettled()
    download.mockReturnValue(false)
    fireEvent.click(slot('prs-it-entry-download'))
    await screen.findByText(/Tarayıcı indirmeyi başlatamadı/)
    fireEvent.click(slot('prs-it-zip'))
    await waitFor(() => expect(download).toHaveBeenCalledTimes(2))
    expect(screen.queryByText(/ZIP olarak indirildi/)).toBeNull()
  })

  it('"Temizle": işlenmiş sonuç varken önce sorar; onaylanınca kuyruk boşalır', async () => {
    setup()
    addViaInput([fileOf('kapak.png')])
    await allSettled()
    fireEvent.click(slot('prs-it-clear'))
    const confirm = await screen.findByRole('button', { name: 'Kuyruğu temizle' })
    expect(entries()).toHaveLength(1)
    fireEvent.click(confirm)
    await waitFor(() => expect(entries()).toHaveLength(0))
    expect(slot('prs-it-summary')).toHaveTextContent('Henüz görsel eklenmedi.')
  })

  it('işlenirken satır aşamayı ve yüzdeyi yazar (yalnız o satır); biten satırda "Karşılaştır" ve "Duyarlı set" pencereleri açılır', async () => {
    const gate = deferred()
    const engine = fakeEngine()
    engine.processImage.mockImplementation(async (blob, options, extra) => { extra.onProgress('search', 0.42); await gate.promise; return resultOf(blob, options) })
    setup({ engine })
    addViaInput([fileOf('kapak.png')])
    await waitFor(() => expect(slot('prs-it-entry-progress')).not.toBeNull())
    expect(slot('prs-it-entry-progress')).toHaveTextContent('Tarayıcıda işleniyor — en iyi kalite aranıyor (%42)')
    expect(slot('prs-it-entry-state')).toHaveTextContent('İşleniyor')
    await act(async () => { gate.resolve(); await Promise.resolve() })
    await allSettled()
    fireEvent.click(slot('prs-it-entry-compare'))
    await waitFor(() => expect(slot('prs-it-compare-dialog')).not.toBeNull())
    await waitFor(() => expect(slot('prs-it-compare-dialog').getAttribute('data-status')).toBe('ready'))
    fireEvent.click(within(slot('prs-it-compare-dialog').closest('[role="dialog"]')).getAllByRole('button', { name: 'Kapat' })[0])
    await waitFor(() => expect(slot('prs-it-compare-dialog')).toBeNull())
    fireEvent.click(slot('prs-it-entry-responsive'))
    await waitFor(() => expect(slot('prs-it-rs')).not.toBeNull())
    expect(slot('prs-it-rs')).toHaveTextContent('kapak.png · 3000×2000')
  })

  it('geri düğmesi yalnız verildiğinde çizilir (analizsiz açılış)', () => {
    const onBack = vi.fn()
    setup({ onBack })
    fireEvent.click(slot('prs-it-back'))
    expect(onBack).toHaveBeenCalledTimes(1)
  })
})

describe('önce / sonra karşılaştırması', () => {
  const summary = resultSummary({
    blob: {}, type: 'webp', bytes: 150_000, width: 1200, height: 800, savingPct: 75, kept: false, resized: true, cropped: false, quality: 78, similarity: 0.992, notes: [],
    original: { type: 'jpeg', bytes: 600_000, width: 3000, height: 2000 },
  })
  const open = (over = {}) => render(<ImageCompareDialog title="hero.jpg" original={blobOf(600)} result={blobOf(150)} summary={summary} onClose={() => {}} {...over} />)

  it('bölücü klavyeyle kayar (oklar ±2, Shift ±10, Home / End); değer ekran okuyucuya okunur', async () => {
    open()
    await waitFor(() => expect(slot('prs-it-compare-dialog').getAttribute('data-status')).toBe('ready'))
    const divider = screen.getByRole('slider', { name: 'Önce / sonra bölücüsü' })
    expect(divider).toHaveAttribute('aria-valuenow', '50')
    expect(divider).toHaveAttribute('aria-valuetext', 'Görüntünün %50 kadarı önceki hâli')
    fireEvent.keyDown(divider, { key: 'ArrowRight' })
    expect(divider).toHaveAttribute('aria-valuenow', '52')
    fireEvent.keyDown(divider, { key: 'ArrowLeft', shiftKey: true })
    expect(divider).toHaveAttribute('aria-valuenow', '42')
    fireEvent.keyDown(divider, { key: 'Home' })
    expect(divider).toHaveAttribute('aria-valuenow', '0')
    fireEvent.keyDown(divider, { key: 'End' })
    expect(divider).toHaveAttribute('aria-valuenow', '100')
    fireEvent.keyDown(divider, { key: 'ArrowRight' })
    expect(divider).toHaveAttribute('aria-valuenow', '100')              // sınırda kalır
    fireEvent.keyDown(divider, { key: 'a' })
    expect(divider).toHaveAttribute('aria-valuenow', '100')
    expect(divider.style.left).toBe('100%')
  })

  it('iki boyut, kazanç ve benzerlik yazılır; yakınlaştırma sığdır / %100 / %200; görseller tuvale çizilir (img yok)', async () => {
    open()
    const dlg = await waitFor(() => { const d = slot('prs-it-compare-dialog'); expect(d.getAttribute('data-status')).toBe('ready'); return d })
    expect(slot('prs-it-cmp-sizes', dlg)).toHaveTextContent('Önce586 KB')
    expect(slot('prs-it-cmp-sizes', dlg)).toHaveTextContent('Sonra146 KB')
    expect(slot('prs-it-cmp-sizes', dlg)).toHaveTextContent('Kazanç%75')
    expect(slot('prs-it-cmp-similarity', dlg)).toHaveTextContent('%99,2')
    expect(dlg.getAttribute('data-zoom')).toBe('fit')
    fireEvent.click(within(slot('prs-it-cmp-zoom', dlg)).getByRole('radio', { name: '%200' }))
    expect(dlg.getAttribute('data-zoom')).toBe('200')
    expect(slot('prs-it-cmp-stage', dlg).style.width).toBe('2400px')
    fireEvent.click(within(slot('prs-it-cmp-zoom', dlg)).getByRole('radio', { name: '%100' }))
    expect(slot('prs-it-cmp-stage', dlg).style.width).toBe('1200px')
    expect(dlg.querySelectorAll('canvas')).toHaveLength(2)
    expect(dlg.querySelector('img')).toBeNull()
    expect(HTMLCanvasElement.prototype.getContext).toHaveBeenCalled()
    expect(slot('prs-it-cmp-resized', dlg)).toHaveTextContent('Görsel küçültüldü')
  })

  it('benzerlik ölçülmediyse "ölçülmedi" (uydurulmaz); pencere kapanınca bit eşlemler kapatılır', async () => {
    const closers = []
    eng.decodeForPreview.mockImplementation(async () => { const b = { width: 1200, height: 800, close: vi.fn() }; closers.push(b.close); return b })
    const { unmount } = open({ summary: { ...summary, similarity: null } })
    await waitFor(() => expect(slot('prs-it-compare-dialog').getAttribute('data-status')).toBe('ready'))
    expect(slot('prs-it-cmp-similarity').getAttribute('data-known')).toBe('false')
    expect(slot('prs-it-cmp-similarity')).toHaveTextContent('ölçülmedi')
    unmount()
    expect(closers).toHaveLength(2)
    for (const c of closers) expect(c).toHaveBeenCalledTimes(1)
  })

  it('önizleme hazırlanamazsa nedeni yazılır (boş tuval gösterilmez); geç gelen çözme sonucu kapatılır', async () => {
    eng.decodeForPreview.mockRejectedValue(engineError('DECODE_FAILED'))
    const first = open()
    await waitFor(() => expect(slot('prs-it-cmp-failed')).not.toBeNull())
    expect(slot('prs-it-cmp-failed')).toHaveTextContent('Önizleme hazırlanamadı')
    expect(slot('prs-it-cmp-failed')).toHaveTextContent('Görsel açılamadı')
    expect(slot('prs-it-cmp-stage')).toBeNull()
    first.unmount()
    const gate = deferred()
    const late = { width: 10, height: 10, close: vi.fn() }
    eng.decodeForPreview.mockImplementation(async () => { await gate.promise; return late })
    const second = open()
    second.unmount()
    await act(async () => { gate.resolve(); await Promise.resolve(); await Promise.resolve() })
    expect(late.close).toHaveBeenCalled()
  })

  it('saf yardımcılar: bölücü tuşları ve önizleme tuvali tavanı', () => {
    expect(dividerKey('ArrowRight', 50)).toBe(52)
    expect(dividerKey('ArrowUp', 99)).toBe(100)
    expect(dividerKey('ArrowLeft', 1)).toBe(0)
    expect(dividerKey('ArrowDown', 50, true)).toBe(40)
    expect(dividerKey('PageUp', 50)).toBe(60)
    expect(dividerKey('PageDown', 5)).toBe(0)
    expect(dividerKey('Home', 77)).toBe(0)
    expect(dividerKey('End', 3)).toBe(100)
    expect(dividerKey('Enter', 50)).toBe(null)
    expect(previewSize(1200, 800)).toEqual({ width: 1200, height: 800, scaled: false })
    const big = previewSize(12000, 8000)
    expect(big.scaled).toBe(true)
    expect(big.width).toBeLessThanOrEqual(PREVIEW_MAX_SIDE)
    expect(big.width * big.height).toBeLessThanOrEqual(PREVIEW_MAX_PIXELS)
    expect(Math.abs(big.width / big.height - 1.5)).toBeLessThan(0.01)
    expect(previewSize(0, 0)).toEqual({ width: 1, height: 1, scaled: false })
  })
})

describe('duyarlı görsel seti', () => {
  function open({ width = 1000, format = 'jpeg' } = {}) {
    const store = createImageToolStore({ fetchImage: vi.fn(), engine: fakeEngine(), download: vi.fn(() => true) })
    const view = render(<ResponsiveSetDialog name="Kapak Görseli.png" base="Kapak Görseli" blob={blobOf(400 * 1024)} format={format}
      info={width == null ? null : { width, height: Math.round((width * 2) / 3), type: 'png' }} store={store} onClose={() => {}} />)
    return { store, ...view }
  }
  const rows = () => slots('prs-it-rs-row')

  it('doğal genişliği aşmayan genişlikler + doğal genişlik üretilir (1000 → 480, 768, 1000); tek ZIP + hazır kod; dosya adları ZIP\'tekiyle aynı', async () => {
    open()
    expect(rows().map((r) => r.getAttribute('data-width'))).toEqual(['480', '768', '1000'])
    expect(slot('prs-it-rs')).toHaveTextContent('Bu görselden şu genişlikler üretilir (piksel): 480 · 768 · 1.000.')
    expect(slot('prs-it-rs-nocode')).not.toBeNull()
    expect(slot('prs-it-rs-zip')).toBeDisabled()
    fireEvent.click(slot('prs-it-rs-go'))
    await waitFor(() => expect(rows().every((r) => r.getAttribute('data-state') === 'done')).toBe(true))
    expect(eng.processImage).toHaveBeenCalledTimes(3)
    expect(eng.processImage.mock.calls.map((c) => c[1].resize)).toEqual([{ method: 'scale', width: 480 }, { method: 'scale', width: 768 }, { method: 'scale', width: 1000 }])
    expect(eng.processImage.mock.calls.map((c) => c[1].format)).toEqual(['jpeg', 'jpeg', 'jpeg'])
    expect(rows()[0]).toHaveTextContent('kapak-gorseli-480w.jpg')
    const code = slot('prs-it-rs-snippet').textContent
    expect(code).toBe('<img src="kapak-gorseli-1000w.jpg" srcset="kapak-gorseli-480w.jpg 480w, kapak-gorseli-768w.jpg 768w, kapak-gorseli-1000w.jpg 1000w" sizes="100vw" alt="" width="1000" height="667" loading="lazy" decoding="async">')
    fireEvent.click(slot('prs-it-rs-zip'))
    await screen.findByText('3 genişlik ve hazır kod ZIP olarak indirildi.')
    expect(downloads[0].name).toMatch(/^kapak-gorseli-duyarli-set-\d{8}-\d{4}\.zip$/)
    const files = unzipSync(new Uint8Array(await downloads[0].blob.arrayBuffer()))
    expect(Object.keys(files).sort()).toEqual(['kapak-gorseli-1000w.jpg', 'kapak-gorseli-480w.jpg', 'kapak-gorseli-768w.jpg', 'snippet.html'])
    expect(strFromU8(files['snippet.html']).trim()).toBe(code)
  })

  it('alt / sizes / önek kaçışlı yazılır; "picture" biçimi yedek img ile; kopyalama', async () => {
    open({ format: 'webp' })
    expect(slot('prs-it-rs-format').value).toBe('webp')
    fireEvent.click(slot('prs-it-rs-go'))
    await waitFor(() => expect(slot('prs-it-rs-snippet')).not.toBeNull())
    fireEvent.change(slot('prs-it-rs-alt'), { target: { value: 'Ürün "kapağı" <yeni>' } })
    fireEvent.change(slot('prs-it-rs-sizes'), { target: { value: '(max-width: 768px) 100vw, 50vw' } })
    fireEvent.change(slot('prs-it-rs-prefix'), { target: { value: '/img/' } })
    let code = slot('prs-it-rs-snippet').textContent
    expect(code).toContain('alt="Ürün &quot;kapağı&quot; &lt;yeni&gt;"')
    expect(code).toContain('sizes="(max-width: 768px) 100vw, 50vw"')
    expect(code).toContain('srcset="/img/kapak-gorseli-480w.webp 480w, /img/kapak-gorseli-768w.webp 768w, /img/kapak-gorseli-1000w.webp 1000w"')
    fireEvent.click(within(slot('prs-it-rs-kind')).getByRole('button', { name: 'picture' }))
    code = slot('prs-it-rs-snippet').textContent
    expect(code.split('\n')[0]).toBe('<picture>')
    expect(code).toContain('<source type="image/webp" srcset="/img/kapak-gorseli-480w.webp 480w, /img/kapak-gorseli-768w.webp 768w, /img/kapak-gorseli-1000w.webp 1000w"')
    expect(code).toContain('<img src="/img/Kapak%20G%C3%B6rseli.png"')
    const write = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue()
    fireEvent.click(slot('prs-it-rs-copy'))
    await waitFor(() => expect(slot('prs-it-rs-copy')).toHaveTextContent('Kopyalandı'))
    expect(write).toHaveBeenCalledWith(code)
    write.mockRestore()
  })

  it('bütün hedeflerden küçük görsel: yalnız doğal genişlik; genişlik bilinmiyorsa set oluşturulamaz (nedeni yazılır)', () => {
    const a = open({ width: 300 })
    expect(rows().map((r) => r.getAttribute('data-width'))).toEqual(['300'])
    a.unmount()
    open({ width: null })
    expect(slot('prs-it-rs-nowidth')).toHaveTextContent('Görselin genişliği bilinmediği için set oluşturulamıyor')
    expect(slot('prs-it-rs-go')).toBeDisabled()
  })

  it('bir genişlik üretilemezse o satır nedenini söyler, ötekiler üretilir; "Vazgeç" süren işi keser', async () => {
    eng.processImage.mockImplementationOnce(async () => { throw engineError('ENCODE_FAILED') })
    const first = open()
    fireEvent.click(slot('prs-it-rs-go'))
    await waitFor(() => expect(rows().map((r) => r.getAttribute('data-state'))).toEqual(['failed', 'done', 'done']))
    expect(rows()[0]).toHaveTextContent('Görsel sıkıştırılamadı')
    expect(rows()[0]).toHaveTextContent('Üretilemedi')
    expect(slot('prs-it-rs-snippet').textContent).toContain('srcset="kapak-gorseli-768w.jpg 768w, kapak-gorseli-1000w.jpg 1000w"')
    first.unmount()
    const gate = deferred()
    let seenSignal = null
    eng.processImage.mockImplementation(async (_b, _o, extra) => { seenSignal = extra.signal; await gate.promise; throw engineError('CANCELLED') })
    open()
    fireEvent.click(slot('prs-it-rs-go'))
    await waitFor(() => expect(slot('prs-it-rs-stop')).not.toBeNull())
    fireEvent.click(slot('prs-it-rs-stop'))
    expect(seenSignal.aborted).toBe(true)
    await waitFor(() => expect(slot('prs-it-rs-go')).not.toBeNull())
    expect(rows().every((r) => r.getAttribute('data-state') === 'idle')).toBe(true)
    await act(async () => { gate.resolve(); await Promise.resolve() })
    expect(rows().every((r) => r.getAttribute('data-state') === 'idle')).toBe(true)
  })
})
