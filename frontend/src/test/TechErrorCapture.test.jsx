import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { channel, overview, reportResponse, openAllSections } from './helpers/techErrorFixtures.js'

// Ekrandan görsel alma (2026-10-18, kullanıcı isteği: "görsel yükle butonuna ek olarak snipping benzeri bir fonksiyon;
// ekrandan direkt alınabilsin; daha hızlı yöntem varsa onu da yapalım"). Her yol yuvanın KENDİ yükleme yoluna varır
// (prepareImage → uploadImage): yapıştırma (Ctrl/⌘+V), "Panodan yapıştır", "Ekrandan al" (paylaşım oturumu + kırpma).
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), reports: vi.fn(), report: vi.fn(), saveReport: vi.fn(), uploadImage: vi.fn(), deleteImage: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
    },
  }),
}))
vi.mock('../components/techerror/imagePrep.js', async (importOriginal) => {
  const mod = await importOriginal()
  return { ...mod, prepareImage: vi.fn() }
})
vi.mock('../components/techerror/capture/frameReader.js', async (importOriginal) => {
  const mod = await importOriginal()
  return {
    ...mod,
    attachVideo: vi.fn(async () => ({ pause: () => {}, srcObject: null })),
    readVideoFrame: vi.fn(),
    cropFrameToFile: vi.fn(),
    releaseFrame: vi.fn(),
    startLivePaint: vi.fn(() => () => {}),
    frameUniform: vi.fn(() => false),
  }
})
import { api } from '../api/client'
import { prepareImage } from '../components/techerror/imagePrep.js'
import { cropFrameToFile, frameUniform, readVideoFrame, releaseFrame, startLivePaint } from '../components/techerror/capture/frameReader.js'
import { captureSession } from '../components/techerror/capture/captureSession.js'
import { SLOT_ACTION_CLASS } from '../components/techerror/capture/SlotCapture.jsx'
import { emitSessionReset } from '../utils/sessionResetBus.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'
import { ImagePrepError } from '../components/techerror/imagePrep.js'

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'
const LABEL = 'Mobil Kanalı Ay Bazlı Toplam İşlem Adetleri'
const png = (name = 'image.png') => new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], name, { type: 'image/png' })
const IMG = { id: 7, slot_id: 'monthly-tx', file_name: 'aylik.png', width: 1600, height: 700, size_bytes: 90_000 }

function fakeStream(label = 'Qlik Sense — Kanal Raporu (Eylül)') {
  const listeners = {}
  const track = {
    label, kind: 'video', stop: vi.fn(),
    addEventListener: (ev, fn) => { (listeners[ev] ||= []).push(fn) },
    getSettings: () => ({ displaySurface: 'browser' }),
    fire: (ev) => (listeners[ev] || []).forEach((fn) => fn()),
  }
  return { track, getTracks: () => [track], getVideoTracks: () => [track] }
}

let stream
let md
beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
  te().report.mockResolvedValue(reportResponse())
  te().uploadImage.mockImplementation(async (_r, slotId, file) => ({ success: true, data: { id: 700, slot_id: slotId,
    file_name: file.name, width: 400, height: 300, size_bytes: 4000 } }))
  prepareImage.mockImplementation(async (file) => ({ file, info: {} }))
  stream = fakeStream()
  md = { getDisplayMedia: vi.fn(async () => stream) }
  Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: md })
  readVideoFrame.mockImplementation(async () => ({ width: 1600, height: 900, canvas: document.createElement('canvas'), uniform: false }))
  cropFrameToFile.mockImplementation(async (_f, rect, name) => new File([new Uint8Array(rect.w)], name, { type: 'image/png' }))
})
afterEach(() => {
  captureSession.stop('test')
  delete navigator.mediaDevices
  delete navigator.clipboard.read
  window.innerWidth = 1024
})

async function openEditor() {
  const view = render(<TechErrorReportsPage />)
  await screen.findByRole('heading', { name: TITLE })
  const slot = (id = 'monthly-tx') => view.container.querySelector(`[data-slot-id="${id}"]`)
  return { view, slot }
}

/** Yuvanın ANA eylem düğmelerinin görünen adları, DOM sırasıyla (yuva adı ekran okuyucu ekidir, atılır). */
function mainActions(slotEl) {
  const row = slotEl.querySelector('[data-slot="te-image-main-actions"]')
  return [...row.querySelectorAll('button')].map((b) => b.textContent.split(':')[0].trim())
}

describe('yapıştırma', () => {
  it('yuva odaklanabilir; Ctrl+V ile yapıştırılan görsel yuvanın yükleme yolundan yüklenir (pano adıyla)', async () => {
    const { slot } = await openEditor()
    expect(slot()).toHaveAttribute('tabindex', '0')
    expect(screen.getByRole('group', { name: `${LABEL} görsel yuvası — Ctrl+V ile görsel yapıştırabilirsiniz` })).toBe(slot())
    expect(within(slot()).getByText('ya da yuvayı seçip Ctrl+V ile yapıştırın')).toBeInTheDocument()
    const file = png()
    fireEvent.paste(slot(), { clipboardData: { items: [{ kind: 'file', type: 'image/png', getAsFile: () => file }], types: ['Files'] } })
    await waitFor(() => expect(te().uploadImage).toHaveBeenCalledTimes(1))
    const sent = prepareImage.mock.calls[0][0]
    expect(sent.type).toBe('image/png')
    expect(sent.name).toMatch(/^monthly-tx-pano-\d{8}-\d{6}\.png$/)
    expect(te().uploadImage).toHaveBeenCalledWith('100', 'monthly-tx', sent)
    await waitFor(() => expect(slot().querySelector('img')).not.toBeNull())
  })

  it('yuvadaki bir düğme odaktayken de yapıştırma çalışır (olay yuvaya kabarcıklanır)', async () => {
    const { slot } = await openEditor()
    const btn = within(slot()).getByRole('button', { name: /Görsel yükle/ })
    const file = png()
    fireEvent.paste(btn, { clipboardData: { items: [{ kind: 'file', type: 'image/png', getAsFile: () => file }] } })
    await waitFor(() => expect(te().uploadImage).toHaveBeenCalledTimes(1))
  })

  it('metin yapıştırması yok sayılır, kısa ipucu; yükleme sürerken ikinci yapıştırma yüklenmez', async () => {
    const { slot } = await openEditor()
    fireEvent.paste(slot(), { clipboardData: { items: [{ kind: 'string', type: 'text/plain' }], types: ['text/plain'] } })
    expect(await within(slot()).findByText(/Yapıştırılan içerikte görsel yok/)).toBeInTheDocument()
    expect(prepareImage).not.toHaveBeenCalled()
    let finish
    prepareImage.mockImplementationOnce(() => new Promise((r) => { finish = r }))
    const file = png()
    const item = { kind: 'file', type: 'image/png', getAsFile: () => file }
    fireEvent.paste(slot(), { clipboardData: { items: [item] } })
    await within(slot()).findByRole('button', { name: /Hazırlanıyor…/ })
    fireEvent.paste(slot(), { clipboardData: { items: [item] } })
    expect(await within(slot()).findByText('Bu yuvaya yükleme sürüyor; bitince yeniden deneyin.')).toBeInTheDocument()
    finish({ file, info: {} })
    await waitFor(() => expect(te().uploadImage).toHaveBeenCalledTimes(1))
    expect(prepareImage).toHaveBeenCalledTimes(1)
  })

  it('"Panodan yapıştır": yalnız clipboard.read varken; görsel yüklenir; izin yoksa "Ctrl+V ile yapıştırın" (ham hata yok)', async () => {
    const first = await openEditor()
    expect(within(first.slot()).queryByRole('button', { name: /Panodan yapıştır/ })).toBeNull()
    first.view.unmount()
    navigator.clipboard.read = vi.fn(async () => [{ types: ['text/html', 'image/png'], getType: async () => new Blob([new Uint8Array([1, 2])], { type: 'image/png' }) }])
    const { slot } = await openEditor()
    // Yarım yuvada da düğme (2026-11-20: her yuvada aynı eylemler — "Diğer işlemler" menüsü kalktı)
    fireEvent.click(within(slot()).getByRole('button', { name: `Panodan yapıştır: ${LABEL}` }))
    await waitFor(() => expect(te().uploadImage).toHaveBeenCalledTimes(1))
    expect(prepareImage.mock.calls[0][0].name).toMatch(/^monthly-tx-pano-\d{8}-\d{6}\.png$/)
    navigator.clipboard.read = vi.fn(async () => { throw Object.assign(new Error('denied'), { name: 'NotAllowedError' }) })
    fireEvent.click(within(slot('tech-errors')).getByRole('button', { name: /Panodan yapıştır/ }))
    expect(await within(slot('tech-errors')).findByText('Tarayıcı panoyu okumaya izin vermedi. Yuvayı seçip Ctrl+V ile yapıştırın.'))
      .toBeInTheDocument()
    navigator.clipboard.read = vi.fn(async () => [{ types: ['text/plain'], getType: async () => new Blob(['x']) }])
    fireEvent.click(within(slot('daily-errors')).getByRole('button', { name: /Panodan yapıştır/ }))
    expect(await within(slot('daily-errors')).findByText(/Panoda görsel yok/)).toBeInTheDocument()
    expect(te().uploadImage).toHaveBeenCalledTimes(1)
  })

  it('DOLU yuvaya Ctrl+V önce sorar (geri alma yok): Vazgeç yüklemez, görsel kalır; Değiştir yükler', async () => {
    te().report.mockResolvedValue(reportResponse({ images: [IMG] }))
    const { slot } = await openEditor()
    const file = png()
    const paste = () => fireEvent.paste(slot(), { clipboardData: { items: [{ kind: 'file', type: 'image/png', getAsFile: () => file }] } })
    paste()
    let dlg = await screen.findByRole('dialog', { name: 'Görsel değiştirilsin mi?' })
    expect(dlg).toHaveTextContent(`“${LABEL}” yuvasındaki görsel yapıştırdığınız görselle değiştirilecek`)
    fireEvent.click(within(dlg).getByRole('button', { name: 'Vazgeç' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(prepareImage).not.toHaveBeenCalled()
    expect(slot().querySelector('img')).toHaveAttribute('src', '/api/tech-error-reports/images/7')
    paste()
    dlg = await screen.findByRole('dialog', { name: 'Görsel değiştirilsin mi?' })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Değiştir' }))
    await waitFor(() => expect(te().uploadImage).toHaveBeenCalledTimes(1))
    expect(prepareImage.mock.calls[0][0].name).toMatch(/^monthly-tx-pano-/)
    // Boş yuvaya yapıştırma sormaz
    fireEvent.paste(slot('tech-errors'), { clipboardData: { items: [{ kind: 'file', type: 'image/png', getAsFile: () => png() }] } })
    await waitFor(() => expect(te().uploadImage).toHaveBeenCalledTimes(2))
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('görsel olmayan dosya yapıştırılırsa (dosya yöneticisinden PDF) türünü söyleyen hata yazar; yüklenmez (boş yuvada sorulmaz)', async () => {
    const { slot } = await openEditor()
    const pdf = new File([new Uint8Array([0x25, 0x50, 0x44, 0x46])], 'rapor.pdf', { type: 'application/pdf' })
    prepareImage.mockImplementationOnce(async () => { throw new ImagePrepError('te.img.errPdf', []) })
    fireEvent.paste(slot(), { clipboardData: { items: [{ kind: 'file', type: 'application/pdf', getAsFile: () => pdf }], files: [pdf], types: ['Files'] } })
    expect(await within(slot()).findByRole('alert')).toHaveTextContent('PDF dosyası görsel yuvasına yüklenemez')
    expect(prepareImage.mock.calls[0][0]).toBe(pdf)   // adı değişmeden (pano adı yalnız görsel türlüye)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(te().uploadImage).not.toHaveBeenCalled()
  })

  it('DOLU yuvada türüne bakılmadan sorulur: türsüz dosya (baytları PNG) da görseli değiştirebilir (2. inceleme turu)', async () => {
    te().report.mockResolvedValue(reportResponse({ images: [IMG] }))
    const { slot } = await openEditor()
    const typeless = new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], 'ekran', { type: '' })
    const paste = () => fireEvent.paste(slot(), { clipboardData: { items: [{ kind: 'file', type: '', getAsFile: () => typeless }], types: ['Files'] } })
    paste()
    let dlg = await screen.findByRole('dialog', { name: 'Görsel değiştirilsin mi?' })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Vazgeç' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(prepareImage).not.toHaveBeenCalled()
    expect(slot().querySelector('img')).toHaveAttribute('src', '/api/tech-error-reports/images/7')
    paste()
    dlg = await screen.findByRole('dialog', { name: 'Görsel değiştirilsin mi?' })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Değiştir' }))
    await waitFor(() => expect(te().uploadImage).toHaveBeenCalledTimes(1))
    expect(prepareImage.mock.calls[0][0]).toBe(typeless)
    // PDF de dolu yuvada önce sorar; onaylanınca türünü söyleyen hata, görsel kalır
    const pdf = new File([new Uint8Array([0x25, 0x50, 0x44, 0x46])], 'rapor.pdf', { type: 'application/pdf' })
    prepareImage.mockImplementationOnce(async () => { throw new ImagePrepError('te.img.errPdf', []) })
    fireEvent.paste(slot('daily-errors'), { clipboardData: { items: [{ kind: 'file', type: 'application/pdf', getAsFile: () => pdf }] } })
    expect(screen.queryByRole('dialog')).toBeNull()   // daily-errors boş: sorulmaz
    expect(await within(slot('daily-errors')).findByRole('alert')).toHaveTextContent('PDF dosyası görsel yuvasına yüklenemez')
  })

  it('HER yuvada aynı eylemler, aynı sırada: Görsel yükle → Ekrandan al → Panodan yapıştır (yarım = tam yuva); menü yok', async () => {
    navigator.clipboard.read = vi.fn()
    const { slot } = await openEditor()
    const half = slot()
    const full = slot('daily-errors')
    expect(mainActions(half)).toEqual(['Görsel yükle', 'Ekrandan al', 'Panodan yapıştır'])
    expect(mainActions(full)).toEqual(mainActions(half))
    for (const el of [half, full]) {
      const actions = el.querySelector('[data-slot="te-image-actions"]')
      expect(actions).toHaveAttribute('data-count', '3')
      // Tek satır, eşit sütun: yarım ve tam yuvada AYNI ızgara — üç eylem + "Kaldır" için AYRILMIŞ dördüncü sütun (boş
      // yuvada o sütun boş durur; görsel yüklenince düğmeler kaymaz / daralmaz)
      expect(actions.querySelector('[data-slot="te-image-main-actions"]').className).toContain('grid-cols-4')
      expect(within(el).queryByRole('button', { name: /^Diğer işlemler/ })).toBeNull()
      expect(within(el).queryByRole('button', { name: /^Kaldır/ })).toBeNull()   // boş yuvada "Kaldır" yok
    }
    // Üç düğme de aynı ortak biçim sınıflarını taşır (biri diğerinden farklı çizilmez): hücreyi doldurur, etiket sarar
    for (const b of [...half.querySelectorAll('[data-slot="te-image-main-actions"] button'),
      ...full.querySelectorAll('[data-slot="te-image-main-actions"] button')]) {
      for (const token of SLOT_ACTION_CLASS.split(' ')) expect(b.className.split(' ')).toContain(token)
    }
  })

  it('DOLU yuvada da sıra aynı (ilk düğme "Değiştir"); "Kaldır" AYNI ızgaranın son düğmesi — öteki düğmelerle aynı hizada, aynı biçimde', async () => {
    navigator.clipboard.read = vi.fn()
    te().report.mockResolvedValue(reportResponse({ images: [IMG, { ...IMG, id: 8, slot_id: 'daily-errors' }] }))
    te().deleteImage.mockResolvedValue({ success: true })
    const { slot } = await openEditor()
    // Kullanıcı bildirimi (2026-11-20): "Kaldır alta kayıyor, diğer düğmelerle aynı hizada kalmıyor" → aynı ızgarada
    expect(mainActions(slot())).toEqual(['Değiştir', 'Ekrandan al', 'Panodan yapıştır', 'Kaldır'])
    expect(mainActions(slot('daily-errors'))).toEqual(mainActions(slot()))
    for (const id of ['monthly-tx', 'daily-errors']) {
      const remove = within(slot(id)).getByRole('button', { name: /^Kaldır: / })
      const grid = remove.closest('[data-slot="te-image-main-actions"]')
      expect(grid).not.toBeNull()                       // ayrı satırda değil: ana ızgaranın içinde
      expect(grid.lastElementChild).toBe(remove)        // son hücre
      expect(grid.className).toContain('grid-cols-4')   // dolu ve boş yuvada AYNI ızgara (düğmeler daralmaz)
      for (const token of SLOT_ACTION_CLASS.split(' ')) expect(remove.className.split(' ')).toContain(token)
    }
    fireEvent.click(within(slot()).getByRole('button', { name: `Kaldır: ${LABEL}` }))
    const dlg = await screen.findByRole('dialog', { name: 'Görsel kaldırılsın mı?' })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Kaldır' }))
    await waitFor(() => expect(te().deleteImage).toHaveBeenCalledWith('100', 7))
    await waitFor(() => expect(slot().querySelector('img')).toBeNull())
    expect(mainActions(slot())).toEqual(['Görsel yükle', 'Ekrandan al', 'Panodan yapıştır'])
    expect(within(slot()).queryByRole('button', { name: /^Kaldır/ })).toBeNull()
  })

  it('pano arayüzü yoksa "Panodan yapıştır" hiçbir yuvada çizilmez: iki eşit eylem, sıra yine aynı', async () => {
    const { slot } = await openEditor()
    for (const id of ['monthly-tx', 'daily-errors']) {
      expect(mainActions(slot(id))).toEqual(['Görsel yükle', 'Ekrandan al'])
      const actions = slot(id).querySelector('[data-slot="te-image-actions"]')
      expect(actions).toHaveAttribute('data-count', '2')
      // iki eylem + "Kaldır" için ayrılmış üçüncü sütun
      expect(actions.querySelector('[data-slot="te-image-main-actions"]').className).toContain('grid-cols-3')
    }
  })

  it('yükleme bitince odak sayfanın gövdesine düşmüşse yuvaya döner; kullanıcı başka yere geçtiyse dokunulmaz', async () => {
    const { slot } = await openEditor()
    const pick = (el) => fireEvent.change(el.querySelector('input[type="file"]'), { target: { files: [png()] } })
    let finish
    prepareImage.mockImplementationOnce((file) => new Promise((r) => { finish = () => r({ file, info: {} }) }))
    pick(slot())
    await within(slot()).findByRole('button', { name: /Hazırlanıyor/ })
    document.activeElement?.blur?.()
    expect(document.activeElement).toBe(document.body)
    act(() => finish())
    await waitFor(() => expect(te().uploadImage).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(document.activeElement).toBe(slot()))
    // İkinci yüklemede kullanıcı bir alana geçti: odak orada kalır
    prepareImage.mockImplementationOnce((file) => new Promise((r) => { finish = () => r({ file, info: {} }) }))
    pick(slot('tech-errors'))
    await within(slot('tech-errors')).findByRole('button', { name: /Hazırlanıyor/ })
    const field = screen.getByLabelText('Toplam işlem adedi', { exact: true })
    field.focus()
    act(() => finish())
    await waitFor(() => expect(te().uploadImage).toHaveBeenCalledTimes(2))
    await new Promise((r) => setTimeout(r, 20))
    expect(document.activeElement).toBe(field)
  })

  it('ipucu satırı işletim sistemine göre kısayolu söyler; Görsel paneli düğmesi masaüstünde', async () => {
    await openEditor()
    const hint = document.querySelector('[data-slot="te-cap-hint-paste"]')
    expect(hint.textContent).toMatch(/Hızlı yol: .+ ile .*yapıştırın\./)
    expect(screen.getByRole('button', { name: /Görsel paneli/ })).toBeInTheDocument()
    expect(document.querySelector('[data-slot="te-cap-hint-snip"]')).toHaveTextContent(/yalnız seçtiğiniz alan yüklenir/)
  })
})

describe('ekrandan alma', () => {
  it('ilk "Ekrandan al" paylaşımı başlatır, kırpma penceresi açılır; seçilen alan PNG olarak yuvaya yüklenir, odak düğmeye döner', async () => {
    const { slot } = await openEditor()
    const snip = within(slot()).getByRole('button', { name: `Ekrandan al: ${LABEL}` })
    fireEvent.click(snip)
    const dlg = await screen.findByRole('dialog', { name: `Ekrandan al: ${LABEL}` })
    expect(md.getDisplayMedia).toHaveBeenCalledTimes(1)
    expect(md.getDisplayMedia.mock.calls[0][0]).toMatchObject({ audio: false, surfaceSwitching: 'include' })
    // Paylaşım açık çipi (her görsel bölümünün başında) + Durdur
    expect(screen.getAllByText('Ekran paylaşımı açık: Qlik Sense — Kanal Raporu (Eylül)').length).toBeGreaterThan(0)
    const stage = dlg.querySelector('[data-slot="te-cap-stage"]')
    // Sekme paylaşımı canlı açılır: canlıyken oklar seçimi oynatmaz (2026-11-20) — klavyeyle seçim için önce "Dondur"
    fireEvent.click(await within(dlg).findByRole('button', { name: /Dondur/ }))
    fireEvent.keyDown(stage, { key: 'ArrowLeft', altKey: true, shiftKey: true })
    fireEvent.keyDown(stage, { key: 'ArrowRight', shiftKey: true })
    fireEvent.click(within(dlg).getByRole('button', { name: /Bu alanı yükle/ }))
    await waitFor(() => expect(te().uploadImage).toHaveBeenCalledTimes(1))
    expect(cropFrameToFile).toHaveBeenCalledWith(expect.objectContaining({ width: 1600, height: 900 }), { x: 10, y: 0, w: 1590, h: 900 },
      expect.stringMatching(/^monthly-tx-ekran-\d{8}-\d{6}\.png$/))
    const sent = prepareImage.mock.calls[0][0]
    expect(sent.name).toMatch(/^monthly-tx-ekran-/)
    expect(te().uploadImage).toHaveBeenCalledWith('100', 'monthly-tx', sent)
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    // Yükleme bittiyse "Ekrandan al" yine açık: odak ona döner
    await waitFor(() => expect(document.activeElement).toBe(within(slot()).getByRole('button', { name: `Ekrandan al: ${LABEL}` })))
  })

  it('kırpmadan sonra yükleme sürerken "Ekrandan al" kapalı: odak yuvanın kendisine döner, gövdeye düşmez; yükleme bitince orada kalır', async () => {
    const { slot } = await openEditor()
    let finish
    prepareImage.mockImplementationOnce((file) => new Promise((r) => { finish = () => r({ file, info: {} }) }))
    fireEvent.click(within(slot()).getByRole('button', { name: `Ekrandan al: ${LABEL}` }))
    const dlg = await screen.findByRole('dialog', { name: `Ekrandan al: ${LABEL}` })
    fireEvent.click(within(dlg).getByRole('button', { name: /Bu alanı yükle/ }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(within(slot()).getByRole('button', { name: `Ekrandan al: ${LABEL}` })).toBeDisabled()
    await waitFor(() => expect(document.activeElement).toBe(slot()))
    act(() => finish())
    await waitFor(() => expect(te().uploadImage).toHaveBeenCalledTimes(1))
    await new Promise((r) => setTimeout(r, 20))
    expect(document.activeElement).toBe(slot())
  })

  it('ikinci yuva seçici AÇMADAN o anki kareyi alır; Vazgeç yüklemez; Durdur paylaşımı kapatır', async () => {
    // Pencere paylaşımı: görüntü donuk kare olarak gelir, "Yeniden çek" yeni kare alır (sekme paylaşımı canlıdır —
    // aşağıdaki "canlı görüntü" testleri)
    stream.track.getSettings = () => ({ displaySurface: 'window' })
    const { slot } = await openEditor()
    fireEvent.click(within(slot()).getByRole('button', { name: /Ekrandan al/ }))
    let dlg = await screen.findByRole('dialog')
    fireEvent.click(within(dlg).getByRole('button', { name: 'Vazgeç' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    fireEvent.click(within(slot('daily-errors')).getByRole('button', { name: /Ekrandan al/ }))
    dlg = await screen.findByRole('dialog')
    expect(md.getDisplayMedia).toHaveBeenCalledTimes(1)
    expect(readVideoFrame).toHaveBeenCalledTimes(2)
    // Pencere / ekran buradan kaydırılamaz: pencere nedenini söyler, canlı çizim başlamaz
    expect(dlg.querySelector('[data-slot="te-cap-stage"]')).not.toHaveAttribute('data-live')
    expect(dlg.querySelector('[data-slot="te-cap-live-status"]')).toHaveTextContent(
      'Pencere ya da tüm ekran paylaşımında görüntü buradan kaydırılamaz (tarayıcı kısıtı).')
    expect(startLivePaint).not.toHaveBeenCalled()
    expect(within(dlg).queryByRole('button', { name: /Canlıya dön|Dondur/ })).toBeNull()
    // Yeniden çek: aynı akıştan yeni kare
    fireEvent.click(within(dlg).getByRole('button', { name: /Yeniden çek/ }))
    await waitFor(() => expect(readVideoFrame).toHaveBeenCalledTimes(3))
    fireEvent.click(within(dlg).getByRole('button', { name: 'Vazgeç' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(te().uploadImage).not.toHaveBeenCalled()
    fireEvent.click(screen.getAllByRole('button', { name: /Durdur/ })[0])
    expect(stream.track.stop).toHaveBeenCalled()
    await waitFor(() => expect(document.querySelector('[data-slot="te-cap-chip"]')).toBeNull())
  })

  it('tarayıcıdan "Paylaşımı durdur" → çip kalkar, tek bildirim; kullanıcı seçiciden vazgeçince sessiz not (hata yok)', async () => {
    const { slot } = await openEditor()
    fireEvent.click(within(slot()).getByRole('button', { name: /Ekrandan al/ }))
    const dlg = await screen.findByRole('dialog')
    fireEvent.click(within(dlg).getByRole('button', { name: 'Vazgeç' }))
    act(() => stream.track.fire('ended'))
    expect(await screen.findByText('Ekran paylaşımı tarayıcıdan durduruldu.')).toBeInTheDocument()
    await waitFor(() => expect(document.querySelector('[data-slot="te-cap-chip"]')).toBeNull())
    md.getDisplayMedia.mockRejectedValueOnce(Object.assign(new Error('x'), { name: 'NotAllowedError' }))
    fireEvent.click(within(slot()).getByRole('button', { name: /Ekrandan al/ }))
    const note = await within(slot()).findByText(/Ekran paylaşımı başlatılmadı/)
    expect(note).toHaveAttribute('data-tone', 'muted')
    expect(within(slot()).queryByRole('alert')).toBeNull()
  })

  it('başlatılamazsa (izin yok / donanım) ne yapılacağını söyler; boş kare uyarısı kırpmayı engellemez', async () => {
    const { slot } = await openEditor()
    md.getDisplayMedia.mockRejectedValueOnce(Object.assign(new Error('x'), { name: 'NotReadableError' }))
    fireEvent.click(within(slot()).getByRole('button', { name: /Ekrandan al/ }))
    expect(await within(slot()).findByRole('alert')).toHaveTextContent(/Ekran paylaşımı başlatılamadı: .+ ekran görüntüsünü .+ ile alıp Ctrl\+V ile yapıştırın\./)
    // Pencere paylaşımı (donuk kare): boş kare uyarısı hemen görünür
    stream.track.getSettings = () => ({ displaySurface: 'window' })
    readVideoFrame.mockResolvedValueOnce({ width: 800, height: 600, canvas: document.createElement('canvas'), uniform: true })
    fireEvent.click(within(slot()).getByRole('button', { name: /Ekrandan al/ }))
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByText('Görüntü boş geldi')).toBeInTheDocument()
    expect(within(dlg).getByText(/Pencereyi açıp “Yeniden çek”e basın/)).toBeInTheDocument()
    fireEvent.click(within(dlg).getByRole('button', { name: /Bu alanı yükle/ }))
    await waitFor(() => expect(te().uploadImage).toHaveBeenCalledTimes(1))
  })

  it('10 dk kullanılmayınca kapanan paylaşım bir kez söylenir', async () => {
    const { slot } = await openEditor()
    fireEvent.click(within(slot()).getByRole('button', { name: /Ekrandan al/ }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Vazgeç' }))
    act(() => captureSession.stop('idle'))
    expect(await screen.findByText(/Ekran paylaşımı 10 dakika kullanılmadığı için durduruldu/)).toBeInTheDocument()
    expect(screen.getAllByText(/10 dakika kullanılmadığı/)).toHaveLength(1)
    expect(stream.track.stop).toHaveBeenCalled()
  })

  it('rapordan ayrılınca paylaşım durur', async () => {
    const { slot, view } = await openEditor()
    fireEvent.click(within(slot()).getByRole('button', { name: /Ekrandan al/ }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Vazgeç' }))
    view.unmount()
    await waitFor(() => expect(stream.track.stop).toHaveBeenCalled())
    expect(captureSession.getState()).toMatchObject({ status: 'off', stopReason: 'left' })
  })

  it('çıkış / oturum sıfırlama paylaşımı kapatır', async () => {
    const { slot } = await openEditor()
    fireEvent.click(within(slot()).getByRole('button', { name: /Ekrandan al/ }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Vazgeç' }))
    act(() => emitSessionReset())
    expect(stream.track.stop).toHaveBeenCalled()
    expect(captureSession.getState().status).toBe('off')
    // Çıkış kullanıcıya ayrıca "paylaşım durdu" diye söylenmez (yalnız tarayıcıdan durdurma / 10 dk kullanılmama)
    await new Promise((r) => setTimeout(r, 20))
    expect(screen.queryByText(/Ekran paylaşımı .*durduruldu/)).toBeNull()
  })
})

// CANLI GÖRÜNTÜ + KAYDIRMA (2026-11-19, kullanıcı isteği: "paylaşılan ekranda aşağı yukarı gidebilmeliyim, istediğim
// noktayı seçip alabilmeliyim"). Sekme paylaşımında kırpma penceresi canlı açılır; tarayıcı destekliyorsa (Chrome / Edge
// 136+: CaptureController.forwardWheel) fare tekerleği paylaşılan sekmeyi buradan kaydırır. İzin sorusu yalnız kullanıcının
// tıklamasıyla açtırılır. Seçim başlayınca / yüklemeden önce görüntü donar: yüklenen piksel, görülen pikseldir.
describe('canlı görüntü ve kaydırma (sekme paylaşımı)', () => {
  let fw
  let stopPaint
  let permState
  const status = (dlg) => dlg.querySelector('[data-slot="te-cap-live-status"]')
  const pad = (dlg) => dlg.querySelector('[data-slot="te-cap-pad"]')
  const stage = (dlg) => dlg.querySelector('[data-slot="te-cap-stage"]')
  const bar = (dlg) => dlg.querySelector('[data-slot="te-cap-live-bar"]')
  const elementCalls = () => fw.mock.calls.filter((c) => c[0] != null)

  beforeEach(() => {
    fw = vi.fn(async () => {})
    stopPaint = vi.fn()
    permState = 'granted'
    startLivePaint.mockImplementation(() => stopPaint)
    frameUniform.mockImplementation(() => false)
    globalThis.CaptureController = class {
      setFocusBehavior() {}
      forwardWheel(el) { return fw(el) }
    }
    Object.defineProperty(navigator, 'permissions', {
      configurable: true, value: { query: vi.fn(async () => ({ state: permState })) },
    })
  })
  afterEach(() => {
    startLivePaint.mockImplementation(() => () => {})
    frameUniform.mockImplementation(() => false)
    delete globalThis.CaptureController
    delete navigator.permissions
  })

  async function openLive() {
    const ed = await openEditor()
    fireEvent.click(within(ed.slot()).getByRole('button', { name: `Ekrandan al: ${LABEL}` }))
    const dlg = await screen.findByRole('dialog', { name: `Ekrandan al: ${LABEL}` })
    return { ...ed, dlg }
  }

  it('pencere CANLI açılır: paylaşılan sekme karenin kendi tuvaline çizilir; izin verilmişse tekerlek iletimi sessizce açılır', async () => {
    const { dlg } = await openLive()
    expect(stage(dlg)).toHaveAttribute('data-live', 'true')
    expect(dlg.querySelector('[data-slot="te-cap-live-badge"]')).toHaveTextContent('Canlı')
    await waitFor(() => expect(startLivePaint).toHaveBeenCalledTimes(1))
    const [, canvas, opts] = startLivePaint.mock.calls[0]
    expect(canvas).toBe(dlg.querySelector('[data-slot="te-cap-frame"]'))
    expect(typeof opts.onResize).toBe('function')
    await waitFor(() => expect(status(dlg)).toHaveTextContent('Fare tekerleği ya da dokunmatik yüzey (iki parmakla kaydırma) paylaşılan sekmeyi buradan kaydırır.'))
    expect(bar(dlg)).toHaveAttribute('data-scroll', 'on')
    expect(navigator.permissions.query).toHaveBeenCalledWith({ name: 'captured-surface-control' })
    expect(elementCalls()).toEqual([[pad(dlg)]])
    // Sekme paylaşımında "Yeniden çek" yok (canlı görüntü aynı işi görür); izin düğmesi de gerekmez
    expect(within(dlg).queryByRole('button', { name: /Yeniden çek|Kaydırmayı etkinleştir/ })).toBeNull()
    expect(within(dlg).getByRole('button', { name: /Dondur/ })).toBeInTheDocument()
  })

  it('paylaşılan sekmenin ölçüsü değişirse pencere yeni ölçüye geçer', async () => {
    const { dlg } = await openLive()
    await waitFor(() => expect(startLivePaint).toHaveBeenCalledTimes(1))
    act(() => startLivePaint.mock.calls[0][2].onResize(1280, 720))
    await waitFor(() => expect(stage(dlg)).toHaveAttribute('data-source', '1280x720'))
    expect(stage(dlg)).toHaveAttribute('data-rect', '0,0,1280,720')
    // Aynı tuval: çizim yeniden başlatılmaz
    expect(startLivePaint).toHaveBeenCalledTimes(1)
  })

  it('izin henüz sorulmamışsa tarayıcıya çağrı YAPILMAZ; "Kaydırmayı etkinleştir" tıklamasıyla sorulur, izin verilince açılır', async () => {
    permState = 'prompt'
    let allow
    fw.mockImplementation((el) => (el == null ? Promise.resolve() : new Promise((r) => { allow = r })))
    const { dlg } = await openLive()
    await waitFor(() => expect(status(dlg)).toHaveTextContent('Paylaşılan sekmeyi buradan kaydırmak için tarayıcı bir kez izin ister.'))
    expect(bar(dlg)).toHaveAttribute('data-scroll', 'ask')
    expect(elementCalls()).toHaveLength(0)
    fireEvent.click(within(dlg).getByRole('button', { name: /Kaydırmayı etkinleştir/ }))
    await waitFor(() => expect(elementCalls()).toEqual([[pad(dlg)]]))
    expect(status(dlg)).toHaveTextContent('Kaydırma izni bekleniyor: tarayıcının sorusuna “İzin ver” deyin.')
    expect(within(dlg).queryByRole('button', { name: /Kaydırmayı etkinleştir/ })).toBeNull()
    await act(async () => { allow() })
    await waitFor(() => expect(status(dlg)).toHaveTextContent('Fare tekerleği ya da dokunmatik yüzey (iki parmakla kaydırma) paylaşılan sekmeyi buradan kaydırır.'))
  })

  it('izin sorusu yanıtlanmadan kapatılırsa yeniden denenebilir; izin engellenmişse site ayarları söylenir (düğme yok)', async () => {
    permState = 'prompt'
    const notAllowed = () => Promise.reject(Object.assign(new Error('No permission.'), { name: 'NotAllowedError' }))
    fw.mockImplementation((el) => (el == null ? Promise.resolve() : notAllowed()))
    const { dlg } = await openLive()
    fireEvent.click(await within(dlg).findByRole('button', { name: /Kaydırmayı etkinleştir/ }))
    // Soru kapatıldı: izin durumu hâlâ "sorulacak" → düğme kalır
    await waitFor(() => expect(status(dlg)).toHaveTextContent('Kaydırma açılamadı. “Kaydırmayı etkinleştir”e yeniden basın'))
    expect(bar(dlg)).toHaveAttribute('data-scroll', 'failed')
    // Bu kez kullanıcı "Engelle" dedi
    permState = 'denied'
    fireEvent.click(within(dlg).getByRole('button', { name: /Kaydırmayı etkinleştir/ }))
    await waitFor(() => expect(status(dlg)).toHaveTextContent('Kaydırma izni verilmedi.'))
    expect(status(dlg)).toHaveTextContent('İzni adres çubuğundaki site ayarlarından açabilirsiniz.')
    expect(within(dlg).queryByRole('button', { name: /Kaydırmayı etkinleştir/ })).toBeNull()
    // Canlı görüntü sürer: kullanıcı paylaşılan sekmede kaydırıp buradan seçebilir
    expect(stage(dlg)).toHaveAttribute('data-live', 'true')
  })

  it('izin önceden engellenmişse pencere açılırken tarayıcıya hiç çağrı yapılmaz', async () => {
    permState = 'denied'
    const { dlg } = await openLive()
    await waitFor(() => expect(bar(dlg)).toHaveAttribute('data-scroll', 'denied'))
    expect(elementCalls()).toHaveLength(0)
    expect(within(dlg).queryByRole('button', { name: /Kaydırmayı etkinleştir/ })).toBeNull()
  })

  it('izin durumu sorgulanamayan tarayıcıda çağrı denenir; hareket gerektiği yanıtı gelirse düğme önerilir', async () => {
    delete navigator.permissions
    fw.mockImplementation((el) => (el == null ? Promise.resolve()
      : Promise.reject(Object.assign(new Error('x'), { name: 'InvalidStateError' }))))
    const { dlg } = await openLive()
    await waitFor(() => expect(bar(dlg)).toHaveAttribute('data-scroll', 'ask'))
    expect(elementCalls()).toHaveLength(1)
    expect(within(dlg).getByRole('button', { name: /Kaydırmayı etkinleştir/ })).toBeInTheDocument()
  })

  it('tarayıcı desteklemiyorsa (CaptureController yok) canlı görüntü yine vardır; pencere kaydırılamadığını söyler', async () => {
    delete globalThis.CaptureController
    const { dlg } = await openLive()
    expect(stage(dlg)).toHaveAttribute('data-live', 'true')
    await waitFor(() => expect(bar(dlg)).toHaveAttribute('data-scroll', 'unsupported'))
    expect(status(dlg)).toHaveTextContent('Bu tarayıcı paylaşılan sekmeyi buradan kaydıramıyor (Chrome ya da Edge 136 ve sonrası gerekir).')
    expect(fw).not.toHaveBeenCalled()
    expect(navigator.permissions.query).not.toHaveBeenCalled()
  })

  it('seçime başlayınca görüntü donar: çizim durur, tekerlek iletimi kesilir; "Canlıya dön" yeniden canlıya alır', async () => {
    const { dlg } = await openLive()
    await waitFor(() => expect(bar(dlg)).toHaveAttribute('data-scroll', 'on'))
    const firstPad = pad(dlg)
    fireEvent.pointerDown(firstPad, { clientX: 10, clientY: 10, pointerId: 1, pointerType: 'mouse', button: 0 })
    fireEvent.pointerUp(stage(dlg), { clientX: 10, clientY: 10, pointerId: 1, pointerType: 'mouse', button: 0 })
    expect(stopPaint).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(dlg.querySelector('[data-slot="te-cap-live-badge"]')).toHaveTextContent('Donduruldu'))
    expect(stage(dlg)).not.toHaveAttribute('data-live')
    expect(pad(dlg)).toBeNull()
    expect(status(dlg)).toHaveTextContent('Görüntü donduruldu; alanı seçip yükleyin.')
    await waitFor(() => expect(fw).toHaveBeenLastCalledWith(null))
    // Tek renk denetimi DONUK kareye yapılır
    expect(frameUniform).toHaveBeenCalledTimes(1)
    // Canlıya dön: çizim yeniden başlar, iletim yeni katmana bağlanır (izin zaten var — soru yok)
    fireEvent.click(within(dlg).getByRole('button', { name: /Canlıya dön/ }))
    await waitFor(() => expect(startLivePaint).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(bar(dlg)).toHaveAttribute('data-scroll', 'on'))
    expect(pad(dlg)).not.toBeNull()
    expect(fw).toHaveBeenLastCalledWith(pad(dlg))
    expect(stage(dlg)).toHaveAttribute('data-rect', '0,0,1600,900')
  })

  it('donan kare boşsa (tek renk) uyarı sekme paylaşımına özgü metinle gösterilir', async () => {
    frameUniform.mockImplementation(() => true)
    const { dlg } = await openLive()
    await waitFor(() => expect(startLivePaint).toHaveBeenCalledTimes(1))
    expect(within(dlg).queryByText('Görüntü boş geldi')).toBeNull()
    fireEvent.click(within(dlg).getByRole('button', { name: /Dondur/ }))
    expect(await within(dlg).findByText('Görüntü boş geldi')).toBeInTheDocument()
    expect(within(dlg).getByText(/“Canlıya dön”e basıp yeniden deneyin/)).toBeInTheDocument()
  })

  it('canlıyken "Bu alanı yükle": ÖNCE dondurur, sonra görülen kareyi keser ve yükler', async () => {
    const { dlg } = await openLive()
    await waitFor(() => expect(bar(dlg)).toHaveAttribute('data-scroll', 'on'))
    fireEvent.click(within(dlg).getByRole('button', { name: /Bu alanı yükle/ }))
    await waitFor(() => expect(te().uploadImage).toHaveBeenCalledTimes(1))
    expect(stopPaint).toHaveBeenCalledTimes(1)
    expect(stopPaint.mock.invocationCallOrder[0]).toBeLessThan(cropFrameToFile.mock.invocationCallOrder[0])
    expect(cropFrameToFile).toHaveBeenCalledWith(expect.objectContaining({ width: 1600, height: 900 }), { x: 0, y: 0, w: 1600, h: 900 },
      expect.stringMatching(/^monthly-tx-ekran-\d{8}-\d{6}\.png$/))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(fw).toHaveBeenLastCalledWith(null))
  })

  it('"Vazgeç": çizim durur, iletim kesilir, kare bırakılır; yükleme yok', async () => {
    const { dlg } = await openLive()
    await waitFor(() => expect(bar(dlg)).toHaveAttribute('data-scroll', 'on'))
    fireEvent.click(within(dlg).getByRole('button', { name: 'Vazgeç' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(stopPaint).toHaveBeenCalledTimes(1)
    expect(releaseFrame).toHaveBeenCalled()
    await waitFor(() => expect(fw).toHaveBeenLastCalledWith(null))
    expect(te().uploadImage).not.toHaveBeenCalled()
    // Çizim durduktan SONRA kare bırakılır (bırakılan tuval yeniden boyanmaz)
    expect(stopPaint.mock.invocationCallOrder[0]).toBeLessThan(releaseFrame.mock.invocationCallOrder.at(-1))
  })

  it('paylaşım pencere açıkken biterse son görüntü donar ve pencere bunu söyler; alan yine yüklenebilir', async () => {
    const { dlg } = await openLive()
    await waitFor(() => expect(bar(dlg)).toHaveAttribute('data-scroll', 'on'))
    act(() => stream.track.fire('ended'))
    await waitFor(() => expect(status(dlg)).toHaveTextContent('Ekran paylaşımı sona erdi; son görüntü donduruldu.'))
    expect(stopPaint).toHaveBeenCalledTimes(1)
    expect(stage(dlg)).not.toHaveAttribute('data-live')
    expect(within(dlg).queryByRole('button', { name: /Canlıya dön|Dondur/ })).toBeNull()
    fireEvent.click(within(dlg).getByRole('button', { name: /Bu alanı yükle/ }))
    await waitFor(() => expect(te().uploadImage).toHaveBeenCalledTimes(1))
  })

  it('rapordan ayrılınca (pencere canlıyken) çizim durur ve paylaşım kapanır', async () => {
    const { dlg, view } = await openLive()
    await waitFor(() => expect(bar(dlg)).toHaveAttribute('data-scroll', 'on'))
    view.unmount()
    expect(stopPaint).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(stream.track.stop).toHaveBeenCalled())
  })

  // ── Bağımsız inceleme (2026-11-19) ──────────────────────────────────────────────────────────────────────────────────
  it('izin sorulmadıysa tarayıcıya "iletimi durdur" çağrısı da GİTMEZ: sor durumunda dondurma ve Vazgeç hiçbir çağrı yapmaz', async () => {
    permState = 'prompt'
    const { dlg } = await openLive()
    await waitFor(() => expect(bar(dlg)).toHaveAttribute('data-scroll', 'ask'))
    fireEvent.click(within(dlg).getByRole('button', { name: /Dondur/ }))
    await waitFor(() => expect(stage(dlg)).not.toHaveAttribute('data-live'))
    fireEvent.click(within(dlg).getByRole('button', { name: /Canlıya dön/ }))
    await waitFor(() => expect(bar(dlg)).toHaveAttribute('data-scroll', 'ask'))
    fireEvent.click(within(dlg).getByRole('button', { name: 'Vazgeç' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    // Sıradaki "durdur" isteklerinin koşması için bir tur beklenir
    await act(async () => { await new Promise((r) => setTimeout(r, 20)) })
    // Hareketsiz bir çağrı (null dahil) izin sorusunu kendiliğinden açtırabilirdi
    expect(fw).not.toHaveBeenCalled()
  })

  it('eski izin çağrısı geç yanıtlanırsa yeni durumu EZMEZ: dondur → canlıya dön sonrası "sor" kalır, eski iletim durdurulur', async () => {
    permState = 'prompt'
    let allow
    fw.mockImplementation((el) => (el == null ? Promise.resolve() : new Promise((r) => { allow = r })))
    const { dlg } = await openLive()
    fireEvent.click(await within(dlg).findByRole('button', { name: /Kaydırmayı etkinleştir/ }))
    await waitFor(() => expect(bar(dlg)).toHaveAttribute('data-scroll', 'asking'))
    const oldPad = pad(dlg)
    await waitFor(() => expect(elementCalls()).toEqual([[oldPad]]))
    // İzin sorusu açıkken kullanıcı dondurup canlıya döner: yeni katman, yeni tur
    fireEvent.click(within(dlg).getByRole('button', { name: /Dondur/ }))
    await waitFor(() => expect(stage(dlg)).not.toHaveAttribute('data-live'))
    fireEvent.click(within(dlg).getByRole('button', { name: /Canlıya dön/ }))
    await waitFor(() => expect(bar(dlg)).toHaveAttribute('data-scroll', 'ask'))
    expect(pad(dlg)).not.toBe(oldPad)
    // Eski soru ŞİMDİ yanıtlandı: pencere "açık" demez (tekerlek yeni katmana bağlı değil) …
    await act(async () => { allow() })
    await waitFor(() => expect(fw).toHaveBeenLastCalledWith(null))
    expect(bar(dlg)).toHaveAttribute('data-scroll', 'ask')
    expect(status(dlg)).not.toHaveTextContent('Fare tekerleği ya da dokunmatik yüzey (iki parmakla kaydırma) paylaşılan sekmeyi buradan kaydırır.')
    // … yeni katman için çağrı yalnız kullanıcının tıklamasıyla yapılır
    expect(elementCalls()).toHaveLength(1)
    expect(within(dlg).getByRole('button', { name: /Kaydırmayı etkinleştir/ })).toBeInTheDocument()
  })

  it('dondurma EŞZAMANLIDIR: çizim, olay işlenirken (pencere yeniden çizilmeden) durur — görülen kare = kesilen kare', async () => {
    const { dlg } = await openLive()
    await waitFor(() => expect(bar(dlg)).toHaveAttribute('data-scroll', 'on'))
    let padAtStop = null
    stopPaint.mockImplementation(() => { padAtStop = !!document.querySelector('[data-slot="te-cap-pad"]') })
    fireEvent.click(within(dlg).getByRole('button', { name: /Dondur/ }))
    // Durdurma bir etki temizliğine (yeniden çizimden SONRA) bırakılsaydı katman çoktan kalkmış olurdu
    expect(padAtStop).toBe(true)
    expect(stopPaint).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(pad(dlg)).toBeNull())
    expect(stopPaint).toHaveBeenCalledTimes(1)
  })

  it('canlı pencerede kullanıcı hareketi paylaşımın boşta sayacını yeniler (5 sn aralıkla); dondurma da yeniler', async () => {
    const keep = vi.spyOn(captureSession, 'keepAlive')
    try {
      const { dlg } = await openLive()
      await waitFor(() => expect(bar(dlg)).toHaveAttribute('data-scroll', 'on'))
      expect(keep).not.toHaveBeenCalled()
      fireEvent.wheel(pad(dlg), { deltaY: 120 })
      expect(keep).toHaveBeenCalledTimes(1)
      // Her tekerlek tıkında değil: aralık dolmadan yeniden çağrılmaz
      fireEvent.wheel(pad(dlg), { deltaY: 120 })
      fireEvent.wheel(pad(dlg), { deltaY: 120 })
      expect(keep).toHaveBeenCalledTimes(1)
      const real = Date.now()
      const now = vi.spyOn(Date, 'now').mockReturnValue(real + 5001)
      try {
        fireEvent.wheel(pad(dlg), { deltaY: 120 })
      } finally {
        now.mockRestore()
      }
      expect(keep).toHaveBeenCalledTimes(2)
      fireEvent.click(within(dlg).getByRole('button', { name: /Dondur/ }))
      expect(keep).toHaveBeenCalledTimes(3)
    } finally {
      keep.mockRestore()
    }
  })

  it('paylaşım görüntü DONUKKEN biterse de pencere söyler: "Canlıya dön" kalkar, "Yeniden çek" gelir', async () => {
    const { dlg } = await openLive()
    await waitFor(() => expect(bar(dlg)).toHaveAttribute('data-scroll', 'on'))
    fireEvent.click(within(dlg).getByRole('button', { name: /Dondur/ }))
    await waitFor(() => expect(status(dlg)).toHaveTextContent('Görüntü donduruldu; alanı seçip yükleyin.'))
    expect(within(dlg).getByRole('button', { name: /Canlıya dön/ })).toBeInTheDocument()
    act(() => stream.track.fire('ended'))
    await waitFor(() => expect(status(dlg)).toHaveTextContent('Ekran paylaşımı sona erdi; son görüntü donduruldu.'))
    expect(within(dlg).queryByRole('button', { name: /Canlıya dön|Dondur|Kaydırmayı etkinleştir/ })).toBeNull()
    expect(within(dlg).getByRole('button', { name: /Yeniden çek/ })).toBeInTheDocument()
    // Çizim yalnız bir kez durduruldu (dondurma); bitiş yeniden durdurmaz
    expect(stopPaint).toHaveBeenCalledTimes(1)
  })

  it('paylaşım canlıyken biterse tek renk denetimi DONAN kareye yapılır (boş kare uyarısı kaçmaz)', async () => {
    const { dlg } = await openLive()
    await waitFor(() => expect(bar(dlg)).toHaveAttribute('data-scroll', 'on'))
    expect(frameUniform).not.toHaveBeenCalled()
    frameUniform.mockImplementation(() => true)
    act(() => stream.track.fire('ended'))
    expect(await within(dlg).findByText('Görüntü boş geldi')).toBeInTheDocument()
    expect(frameUniform).toHaveBeenCalledTimes(1)
    // Paylaşım bitti: "Canlıya dön" önerilmez
    expect(within(dlg).queryByText(/“Canlıya dön”e basıp yeniden deneyin/)).toBeNull()
  })

  it('canlıyken paylaşılan yüzey pencereye çevrilirse (tarayıcıdan kaynak değişimi) görüntü donar ve NEDENİ söylenir', async () => {
    const { dlg } = await openLive()
    await waitFor(() => expect(bar(dlg)).toHaveAttribute('data-scroll', 'on'))
    stream.track.getSettings = () => ({ displaySurface: 'window' })
    act(() => stream.track.fire('configurationchange'))
    await waitFor(() => expect(stage(dlg)).not.toHaveAttribute('data-live'))
    expect(stopPaint).toHaveBeenCalledTimes(1)
    expect(status(dlg)).toHaveTextContent('Pencere ya da tüm ekran paylaşımında görüntü buradan kaydırılamaz (tarayıcı kısıtı).')
    // "Tarayıcınız desteklemiyor" denmez; paylaşım sürüyor: "Yeniden çek" var, "Canlıya dön" yok
    expect(status(dlg)).not.toHaveTextContent('Bu tarayıcı paylaşılan sekmeyi buradan kaydıramıyor')
    expect(status(dlg)).not.toHaveTextContent('Ekran paylaşımı sona erdi')
    expect(within(dlg).getByRole('button', { name: /Yeniden çek/ })).toBeInTheDocument()
    expect(within(dlg).queryByRole('button', { name: /Canlıya dön|Dondur/ })).toBeNull()
    await waitFor(() => expect(fw).toHaveBeenLastCalledWith(null))
  })

  it('"Yeniden çek" sürerken pencere kapatılırsa geç gelen kare pencereyi yeniden AÇMAZ; kare bırakılır', async () => {
    stream.track.getSettings = () => ({ displaySurface: 'window' })
    const { dlg } = await openLive()
    expect(stage(dlg)).not.toHaveAttribute('data-live')
    let deliver
    const late = { width: 800, height: 600, canvas: document.createElement('canvas'), uniform: false }
    readVideoFrame.mockImplementationOnce(() => new Promise((r) => { deliver = () => r(late) }))
    fireEvent.click(within(dlg).getByRole('button', { name: /Yeniden çek/ }))
    await waitFor(() => expect(deliver).toBeTypeOf('function'))
    fireEvent.click(within(dlg).getByRole('button', { name: 'Vazgeç' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await act(async () => { deliver() })
    await waitFor(() => expect(releaseFrame).toHaveBeenCalledWith(late))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(startLivePaint).not.toHaveBeenCalled()
    expect(te().uploadImage).not.toHaveBeenCalled()
  })
})

describe('görünürlük', () => {
  it('salt okunur raporda yapıştırma / ekrandan alma / panel yok; yuva odaklanmaz, yapıştırma yüklemez', async () => {
    navigator.clipboard.read = vi.fn()
    te().report.mockResolvedValue(reportResponse({ can_edit: false, can_delete: false }))
    const { slot } = await openEditor()
    expect(slot()).not.toHaveAttribute('tabindex')
    expect(within(slot()).queryByRole('button', { name: /Ekrandan al|Panodan yapıştır/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /Görsel paneli/ })).toBeNull()
    const file = png()
    fireEvent.paste(slot(), { clipboardData: { items: [{ kind: 'file', type: 'image/png', getAsFile: () => file }] } })
    await new Promise((r) => setTimeout(r, 20))
    expect(te().uploadImage).not.toHaveBeenCalled()
  })

  it('telefonda "Ekrandan al" ve panel düğmesi yok; pano arayüzü varsa "Panodan yapıştır" var', async () => {
    window.innerWidth = 390
    navigator.clipboard.read = vi.fn()
    const { slot } = await openEditor()
    expect(within(slot()).queryByRole('button', { name: /Ekrandan al/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /Görsel paneli/ })).toBeNull()
    expect(document.querySelector('[data-slot="te-cap-hint"]')).toBeNull()
    expect(mainActions(slot())).toEqual(['Görsel yükle', 'Panodan yapıştır'])
  })

  it('Görsel paneli düğmesi ilk görsel bölümünün başlığında: bölüm kapalıyken de görünür', async () => {
    localStorage.clear()
    localStorage.setItem(LANG_STORAGE_KEY, 'tr')
    const { view } = await openEditor()
    const section = view.container.querySelector('[data-slot="te-images"]')
    expect(section).toHaveAttribute('data-open', 'false')
    expect(within(section).getByRole('button', { name: /Görsel paneli/ })).toBeVisible()
    expect(screen.getAllByRole('button', { name: /Görsel paneli/ })).toHaveLength(1)
  })

  it('ekran paylaşımı olmayan tarayıcıda "Ekrandan al" hiç çizilmez (bozuk düğme yok)', async () => {
    delete navigator.mediaDevices
    const { slot } = await openEditor()
    expect(within(slot()).queryByRole('button', { name: /Ekrandan al/ })).toBeNull()
    expect(document.querySelector('[data-slot="te-cap-hint-snip"]')).toBeNull()
  })

  it('Görsel paneli: adla açılır; engellenirse "yeni sekmede aç" bağlantılı bildirim', async () => {
    const popup = { location: { href: 'about:blank', search: '', replace: vi.fn() }, focus: vi.fn() }
    const open = vi.spyOn(window, 'open').mockReturnValue(popup)
    await openEditor()
    fireEvent.click(screen.getByRole('button', { name: /Görsel paneli/ }))
    expect(open).toHaveBeenCalledWith('', 'sm-te-images-100', 'popup,width=520,height=900')
    expect(popup.location.replace).toHaveBeenCalledWith('/?tab=channelreports&te_view=images&te_rep=100')
    open.mockReturnValue(null)
    fireEvent.click(screen.getByRole('button', { name: /Görsel paneli/ }))
    const link = await screen.findByRole('link', { name: 'Görsel panelini yeni sekmede aç' })
    expect(link).toHaveAttribute('href', '/?tab=channelreports&te_view=images&te_rep=100')
    expect(link).toHaveAttribute('target', '_blank')
    open.mockRestore()
  })
})
