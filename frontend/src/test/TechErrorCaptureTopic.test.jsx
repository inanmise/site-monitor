import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { TEMPLATE, channel, overview, reportResponse, openAllSections } from './helpers/techErrorFixtures.js'

// Ekrandan görsel alma × anomali konu görselleri (2026-10-18, birleşik sürüm): konu görseli yuvası da genel yuva
// (ImageSlotTile) — yapıştırma ve "Ekrandan al" konunun yükleme yoluna varır (kaydedilmemiş konu ÖNCE kaydedilir), her
// değişiklik öteki pencerelere duyurulur, paylaşım çipi ve oturum kapsamı konu bölümünde de var (rapor yalnız konu görseli
// taşıyabilir).
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
import { IMAGES_CHANNEL, KIND_IMAGES, KIND_REPORT } from '../components/techerror/capture/imageSync.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'
const ISSUES = {
  id: 'issues', kind: 'issues', title: 'Anormal Artış Gösteren Hatalar', item_title: '{konu}: Hata Artışı',
  description_label: 'Ne oldu? (zaman, yer, etki)', customers_label: 'Etkilenen müşteri sayısı',
  errors_label: 'Bu konudan kaynaklı teknik hata adedi', allow_images: true, ratio_of: 'error-count',
}
const METRICS = { id: 'summary', kind: 'metrics', title: 'Veriler', fields: [
  { id: 'total-tx', label: 'Toplam işlem adedi', type: 'number' },
  { id: 'error-count', label: 'Ay içindeki teknik hata adedi', type: 'number' },
] }
const CHARTS = TEMPLATE.sections.find((s) => s.kind === 'images')
const topic = (extra = {}) => ({ id: 'k1', topic: 'Kart Limiti', title: '', title_auto: true, description: '', ...extra })
const respond = (sections, issues, extra = {}) => reportResponse({ template: { ...TEMPLATE, sections },
  values: { fields: { 'error-count': 5000 }, issues: issues ? { issues } : {}, texts: {} }, ...extra })
const saved = (values, version = 5) => ({ success: true, data: { version, changed: true, updated_at: '2026-10-18T09:00:00Z',
  updated_by_name: 'Ayşe Yılmaz', values } })
const card = () => document.querySelector('[data-slot="te-issue"]')
const png = (name = 'image.png') => new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], name, { type: 'image/png' })
const pasteInto = (el, file) => fireEvent.paste(el, { clipboardData: { items: [{ kind: 'file', type: 'image/png', getAsFile: () => file }], types: ['Files'] } })

function fakeStream(label = 'Qlik Sense — Kanal Raporu (Eylül)') {
  const listeners = {}
  const track = {
    label, kind: 'video', stop: vi.fn(),
    addEventListener: (ev, fn) => { (listeners[ev] ||= []).push(fn) },
    getSettings: () => ({ displaySurface: 'browser' }),
  }
  return { track, getTracks: () => [track], getVideoTracks: () => [track] }
}

let stream
let md
let other
beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
  te().uploadImage.mockImplementation(async (_r, slotId, file) => ({ success: true, data: { id: 700, slot_id: slotId,
    file_name: file.name, width: 400, height: 300, size_bytes: 4000 } }))
  te().saveReport.mockImplementation(async (_r, values) => saved(values))
  prepareImage.mockImplementation(async (file) => ({ file, info: {} }))
  stream = fakeStream()
  md = { getDisplayMedia: vi.fn(async () => stream) }
  Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: md })
  readVideoFrame.mockImplementation(async () => ({ width: 1600, height: 900, canvas: document.createElement('canvas'), uniform: false }))
  cropFrameToFile.mockImplementation(async (_f, rect, name) => new File([new Uint8Array(rect.w)], name, { type: 'image/png' }))
  other = new BroadcastChannel(IMAGES_CHANNEL)
})
afterEach(() => {
  other.close()
  captureSession.stop('test')
  delete navigator.mediaDevices
  window.innerWidth = 1024
})

describe('anomali konu görseli — ekrandan görsel alma', () => {
  it('kaydedilmemiş konunun yuvasına Ctrl+V: rapor ÖNCE kaydedilir, sonra görsel konunun yuvasına (dosya adında ":" yok) yüklenir ve öteki pencerelere duyurulur', async () => {
    te().report.mockResolvedValue(respond([METRICS, ISSUES, CHARTS], []))
    const got = []
    other.onmessage = (e) => got.push(e.data)
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(screen.getByRole('button', { name: 'Başlık ekle' }))
    fireEvent.change(screen.getByLabelText('Konu'), { target: { value: 'Kart' } })
    const id = card().getAttribute('data-issue')
    const slot = card().querySelector(`[data-slot-id="iss:issues:${id}:1"]`)
    // Yuva bağlamlı adla odaklanabilir yapıştırma alanı ("<konu> — Görsel 1")
    expect(slot).toHaveAttribute('role', 'group')
    expect(slot.getAttribute('aria-label')).toContain('Kart: Hata Artışı — Görsel 1')
    pasteInto(slot, png())
    await waitFor(() => expect(te().uploadImage).toHaveBeenCalledTimes(1))
    expect(te().saveReport).toHaveBeenCalledTimes(1)
    expect(te().saveReport.mock.invocationCallOrder[0]).toBeLessThan(te().uploadImage.mock.invocationCallOrder[0])
    expect(te().saveReport.mock.calls[0][1].issues.issues.map((i) => i.id)).toEqual([id])
    const [, slotId, sent] = te().uploadImage.mock.calls[0]
    expect(slotId).toBe(`iss:issues:${id}:1`)
    expect(sent.name).toMatch(new RegExp(`^iss-issues-${id}-1-pano-\\d{8}-\\d{6}\\.png$`))
    // Kayıt konu listesini değiştirdi (panel konuyu görsün) → report-changed; yükleme → images-changed
    await waitFor(() => expect(got.map((m) => m.kind)).toEqual([KIND_REPORT, KIND_IMAGES]))
    expect(got.every((m) => m.reportId === '100')).toBe(true)
  })

  it('konu listesini değiştirmeyen kayıt (yalnız gösterge değeri) panele ileti göndermez; konu silen kayıt gönderir', async () => {
    te().report.mockResolvedValue(respond([METRICS, ISSUES, CHARTS], [topic()]))
    const got = []
    other.onmessage = (e) => got.push(e.data)
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.change(screen.getByLabelText('Toplam işlem adedi'), { target: { value: '1000000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    await new Promise((r) => setTimeout(r, 30))
    expect(got).toEqual([])
    // Görselsiz konu sorulmadan kaldırılır
    fireEvent.click(within(card()).getByRole('button', { name: /başlığını kaldır/ }))
    await waitFor(() => expect(card()).toBeNull())
    fireEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(got.map((m) => m.kind)).toEqual([KIND_REPORT]))
  })

  it('DOLU konu yuvasına Ctrl+V önce sorar ve onay konunun adını söyler; Vazgeç yüklemez', async () => {
    const img = { id: 601, slot_id: 'iss:issues:k1:1', file_name: 'a.png', width: 800, height: 400, size_bytes: 2048 }
    te().report.mockResolvedValue(respond([METRICS, ISSUES, CHARTS], [topic()], { images: [img] }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    pasteInto(card().querySelector('[data-slot-id="iss:issues:k1:1"]'), png())
    const dlg = await screen.findByRole('dialog')
    expect(dlg.textContent).toContain('Kart Limiti: Hata Artışı — Görsel 1')
    fireEvent.click(within(dlg).getByRole('button', { name: 'Vazgeç' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(te().uploadImage).not.toHaveBeenCalled()
    expect(te().saveReport).not.toHaveBeenCalled()
  })

  it('yalnız konu görseli olan rapor (görsel bölümü yok): "Ekrandan al" kırpılan alanı konuya yükler; paylaşım çipi konu bölümünde, kendiliğinden kapanma bir kez söylenir; rapordan çıkınca paylaşım durur', async () => {
    te().report.mockResolvedValue(respond([METRICS, ISSUES], [topic()]))
    const view = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const slot = () => card().querySelector('[data-slot-id="iss:issues:k1:1"]')
    fireEvent.click(within(slot()).getByRole('button', { name: /^Ekrandan al: Kart Limiti: Hata Artışı — Görsel 1/ }))
    const dlg = await screen.findByRole('dialog')
    expect(md.getDisplayMedia).toHaveBeenCalledTimes(1)
    const section = document.querySelector('[data-slot="te-issues"]')
    await waitFor(() => expect(section.querySelector('[data-slot="te-cap-chip"]')).not.toBeNull())
    fireEvent.click(within(dlg).getByRole('button', { name: /Bu alanı yükle/ }))
    await waitFor(() => expect(te().uploadImage).toHaveBeenCalledTimes(1))
    expect(te().uploadImage.mock.calls[0][1]).toBe('iss:issues:k1:1')
    expect(cropFrameToFile.mock.calls[0][2]).toMatch(/^iss-issues-k1-1-ekran-\d{8}-\d{6}\.png$/)
    expect(te().saveReport).not.toHaveBeenCalled()      // konu kayıtlıydı
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    act(() => captureSession.stop('idle'))
    expect(await screen.findByText(/Ekran paylaşımı 10 dakika kullanılmadığı için durduruldu/)).toBeInTheDocument()
    expect(screen.getAllByText(/10 dakika kullanılmadığı/)).toHaveLength(1)
    // Yeniden paylaş → rapordan çık: kapsam konu bölümünde tutulduğu için paylaşım durur
    fireEvent.click(within(slot()).getByRole('button', { name: /^Ekrandan al/ }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Vazgeç' }))
    view.unmount()
    await waitFor(() => expect(captureSession.getState()).toMatchObject({ status: 'off', stopReason: 'left' }))
  })

  it('görsel alanı olmayan (eski düzen) konu bölümünde yuva / çip yok; görsel bölümü varken kapanma bildirimi o bölümde (iki kez söylenmez)', async () => {
    te().report.mockResolvedValue(respond([METRICS, { ...ISSUES, allow_images: false, ratio_of: null }, CHARTS], [topic()]))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(card().querySelector('[data-slot="te-image-slot"]')).toBeNull()
    fireEvent.click(within(document.querySelector('[data-slot-id="monthly-tx"]')).getByRole('button', { name: /^Ekrandan al/ }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Vazgeç' }))
    expect(document.querySelector('[data-slot="te-issues"] [data-slot="te-cap-chip"]')).toBeNull()
    act(() => captureSession.stop('idle'))
    expect(await screen.findByText(/10 dakika kullanılmadığı/)).toBeInTheDocument()
    expect(screen.getAllByText(/10 dakika kullanılmadığı/)).toHaveLength(1)
  })
})
