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
  }
})
import { api } from '../api/client'
import { prepareImage } from '../components/techerror/imagePrep.js'
import { cropFrameToFile, readVideoFrame } from '../components/techerror/capture/frameReader.js'
import { captureSession } from '../components/techerror/capture/captureSession.js'
import { emitSessionReset } from '../utils/sessionResetBus.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'
const LABEL = 'Mobil Kanalı Ay Bazlı Toplam İşlem Adetleri'
const png = (name = 'image.png') => new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], name, { type: 'image/png' })

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
    await waitFor(() => expect(document.activeElement).toBe(within(slot()).getByRole('button', { name: `Ekrandan al: ${LABEL}` })))
  })

  it('ikinci yuva seçici AÇMADAN o anki kareyi alır; Vazgeç yüklemez; Durdur paylaşımı kapatır', async () => {
    const { slot } = await openEditor()
    fireEvent.click(within(slot()).getByRole('button', { name: /Ekrandan al/ }))
    let dlg = await screen.findByRole('dialog')
    fireEvent.click(within(dlg).getByRole('button', { name: 'Vazgeç' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    fireEvent.click(within(slot('daily-errors')).getByRole('button', { name: /Ekrandan al/ }))
    dlg = await screen.findByRole('dialog')
    expect(md.getDisplayMedia).toHaveBeenCalledTimes(1)
    expect(readVideoFrame).toHaveBeenCalledTimes(2)
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
    readVideoFrame.mockResolvedValueOnce({ width: 800, height: 600, canvas: document.createElement('canvas'), uniform: true })
    fireEvent.click(within(slot()).getByRole('button', { name: /Ekrandan al/ }))
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByText('Görüntü boş geldi')).toBeInTheDocument()
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
    expect(within(slot()).getByRole('button', { name: /Panodan yapıştır/ })).toBeInTheDocument()
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
