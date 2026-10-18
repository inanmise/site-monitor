import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { TEMPLATE, channel, overview, reportResponse, openAllSections } from './helpers/techErrorFixtures.js'

// Görsel paneli (2026-10-18): ayrı pencerede raporun YALNIZ görsel bölümleri; eşitleme iki yönlü (BroadcastChannel) —
// öteki pencerenin değişikliği paneli, panelin değişikliği düzenleyicinin YALNIZ görsel listesini yeniler (taslak, otomatik
// kayıt ve sürüm değişmez).
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
import { api } from '../api/client'
import { prepareImage } from '../components/techerror/imagePrep.js'
import { IMAGES_CHANNEL, KIND_IMAGES, KIND_REPORT } from '../components/techerror/capture/imageSync.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import ImagesPanelPage from '../components/techerror/capture/ImagesPanelPage.jsx'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors
const IMG = { id: 7, slot_id: 'monthly-tx', file_name: 'aylik.png', width: 1600, height: 700, size_bytes: 90_000 }

let other
beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
  te().report.mockResolvedValue(reportResponse())
  prepareImage.mockImplementation(async (file) => ({ file, info: {} }))
  te().uploadImage.mockImplementation(async (_r, slotId, file) => ({ success: true, data: { ...IMG, id: 9, slot_id: slotId, file_name: file.name } }))
  other = new BroadcastChannel(IMAGES_CHANNEL)
})
afterEach(() => { other.close() })

const pick = (slot, file) => fireEvent.change(slot.querySelector('input[type="file"]'), { target: { files: [file] } })
const png = (n) => new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], n, { type: 'image/png' })

describe('Görsel paneli', () => {
  it('yalnız görsel bölümleri: başlık + dönem + kanal; göstergeler / konular / metin yok; rapor toplamı ve sınır', async () => {
    render(<ImagesPanelPage reportId="100" />)
    expect(await screen.findByRole('heading', { level: 1, name: 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026' })).toBeInTheDocument()
    expect(document.querySelector('[data-slot="te-panel-period"]')).toHaveTextContent('Eylül 2026 · Mobil')
    expect(screen.getByRole('heading', { name: 'Grafikler' })).toBeInTheDocument()
    expect(document.querySelectorAll('[data-slot="te-image-slot"]')).toHaveLength(3)
    expect(screen.queryByLabelText('Teknik hata oranı')).toBeNull()
    expect(screen.queryByText('Teknik hataların anomali olarak arttığı konular')).toBeNull()
    expect(document.querySelector('[data-slot="te-img-total"]')).toHaveTextContent(/Toplam .+ \/ 40 MB/)
    // Panelin içinde panel düğmesi ve bölüm başı paylaşım çipi yok; ana pencereye dönüş var
    expect(screen.queryByRole('button', { name: /Görsel paneli/ })).toBeNull()
    expect(screen.getByRole('button', { name: /Ana pencerede aç/ })).toBeInTheDocument()
    // Açan pencere yoksa (bağlantıyla açıldı) "Kapat" çizilmez — tarayıcı kapatmazdı
    expect(screen.queryByRole('button', { name: /^Kapat$/ })).toBeNull()
  })

  it('panelde yükleme öteki pencerelere duyurulur (rapor kimliği + tür)', async () => {
    const got = []
    other.onmessage = (e) => got.push(e.data)
    const view = render(<ImagesPanelPage reportId="100" />)
    await screen.findByRole('heading', { level: 1 })
    pick(view.container.querySelector('[data-slot-id="monthly-tx"]'), png('a.png'))
    await waitFor(() => expect(te().uploadImage).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(got).toEqual([expect.objectContaining({ reportId: '100', kind: KIND_IMAGES })]))
  })

  it('öteki pencerenin iletisi (görsel ya da rapor) paneli yeniden okur; başka raporun iletisi okumaz', async () => {
    const view = render(<ImagesPanelPage reportId="100" />)
    await screen.findByRole('heading', { level: 1 })
    expect(te().report).toHaveBeenCalledTimes(1)
    te().report.mockResolvedValue(reportResponse({ images: [IMG] }))
    other.postMessage({ reportId: '200', kind: KIND_IMAGES, from: 'w-main' })
    other.postMessage({ reportId: '100', kind: KIND_IMAGES, from: 'w-main' })
    await waitFor(() => expect(view.container.querySelector('[data-slot-id="monthly-tx"] img')).not.toBeNull())
    expect(te().report).toHaveBeenCalledTimes(2)
    // Şablon uygulandı (yuva listesi değişti): bölüm / yuvalar yenilenir
    te().report.mockResolvedValue(reportResponse({ template: { ...TEMPLATE, sections: [
      { id: 'sla-shots', kind: 'images', title: 'SLA Ekran Görüntüleri', slots: [{ id: 'sla-1', label: 'SLA tablosu', width: 'full' }] },
    ] } }))
    other.postMessage({ reportId: '100', kind: KIND_REPORT, from: 'w-main' })
    expect(await screen.findByRole('heading', { name: 'SLA Ekran Görüntüleri' })).toBeInTheDocument()
    expect(document.querySelectorAll('[data-slot="te-image-slot"]')).toHaveLength(1)
  })

  it('salt okunur: yalnız görseller (yükleme / silme / yapıştırma yok), açıklama', async () => {
    te().report.mockResolvedValue(reportResponse({ can_edit: false, images: [IMG] }))
    const view = render(<ImagesPanelPage reportId="100" />)
    await screen.findByRole('heading', { level: 1 })
    expect(screen.getByText(/yalnız görüntüleyebilirsiniz; yükleme ve silme/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Görsel yükle|Değiştir|Kaldır|Ekrandan al|Panodan/ })).toBeNull()
    expect(view.container.querySelector('[data-slot-id="monthly-tx"] img')).not.toBeNull()
    expect(view.container.querySelector('[data-slot-id="monthly-tx"]')).not.toHaveAttribute('tabindex')
  })

  it('başka takımın / silinmiş rapor: modülün "bulunamadı / yetki yok" durumu; ağ hatası yeniden dene', async () => {
    te().report.mockResolvedValue({ success: false, status: 404, error: 'Rapor bulunamadı.' })
    const a = render(<ImagesPanelPage reportId="100" />)
    expect(await screen.findByText('Rapor bulunamadı ya da görme yetkiniz yok')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Tekrar dene' })).toBeNull()
    a.unmount()
    te().report.mockResolvedValue(reportResponse())
    te().report.mockRejectedValueOnce(new Error('Sunucuya ulaşılamadı; bağlantınızı kontrol edin.'))
    render(<ImagesPanelPage reportId="100" />)
    expect(await screen.findByText('Sunucuya ulaşılamadı; bağlantınızı kontrol edin.')).toBeInTheDocument()
    fireEvent.click(await screen.findByRole('button', { name: 'Tekrar dene' }))
    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument()
  })

  it('görsel bölümü olmayan şablon: boş durum (neden + ne yapılacağı)', async () => {
    te().report.mockResolvedValue(reportResponse({ template: { ...TEMPLATE, sections: TEMPLATE.sections.filter((s) => s.kind !== 'images') } }))
    render(<ImagesPanelPage reportId="100" />)
    expect(await screen.findByText('Bu raporda görsel bölümü yok')).toBeInTheDocument()
  })

  it('açan pencere varken "Kapat" pencereyi kapatır; "Ana pencerede aç" açan pencereyi rapora götürür', async () => {
    const opener = { closed: false, focus: vi.fn(), location: { origin: window.location.origin, search: '?tab=dashboard', assign: vi.fn() } }
    Object.defineProperty(window, 'opener', { configurable: true, value: opener })
    const close = vi.spyOn(window, 'close').mockImplementation(() => {})
    render(<ImagesPanelPage reportId="100" />)
    await screen.findByRole('heading', { level: 1 })
    fireEvent.click(screen.getByRole('button', { name: /Ana pencerede aç/ }))
    expect(opener.location.assign).toHaveBeenCalledWith('/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
    fireEvent.click(screen.getByRole('button', { name: /^Kapat$/ }))
    expect(close).toHaveBeenCalled()
    close.mockRestore()
    delete window.opener
  })
})

describe('düzenleyici ↔ panel eşitlemesi', () => {
  it('panelin iletisi düzenleyicide YALNIZ görselleri yeniler: yazılmış taslak kalır, kayıt gitmez', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
    const view = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026' })
    const total = screen.getByLabelText('Toplam işlem adedi', { exact: true })
    fireEvent.change(total, { target: { value: '1.250.000' } })
    te().report.mockResolvedValue(reportResponse({ images: [IMG], values: { fields: { 'error-rate': 0.9 }, issues: {}, texts: {} } }))
    other.postMessage({ reportId: '100', kind: KIND_IMAGES, from: 'w-panel' })
    await waitFor(() => expect(view.container.querySelector('[data-slot-id="monthly-tx"] img')).not.toBeNull())
    expect(screen.getByLabelText('Toplam işlem adedi', { exact: true })).toHaveValue('1.250.000')
    expect(screen.getByLabelText('Teknik hata oranı', { exact: true })).not.toHaveValue('0,9')
    expect(te().saveReport).not.toHaveBeenCalled()
    expect(within(document.querySelector('[data-slot="te-report-actions"]')).getByText(/Kaydedilmemiş|kaydedilmedi/i)).toBeInTheDocument()
    // Rapor türü ileti (şablon uygulandı) düzenleyiciyi tetiklemez (kendi yüklemesini yapar)
    const n = te().report.mock.calls.length
    other.postMessage({ reportId: '100', kind: KIND_REPORT, from: 'w-panel' })
    await new Promise((r) => setTimeout(r, 30))
    expect(te().report.mock.calls.length).toBe(n)
  })

  it('panel bu pencereden açıldıysa pencereye dönünce görseller okunur (panel yükleme sürerken kapandıysa ileti kaçmış olabilir)', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=101')
    te().report.mockResolvedValue(reportResponse({ report: { ...reportResponse().data.report, id: 101 } }))
    const open = vi.spyOn(window, 'open').mockReturnValue({ location: { href: 'about:blank', search: '', replace: vi.fn() }, focus: vi.fn() })
    const view = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026' })
    const before = te().report.mock.calls.length
    // Panel açılmadan pencere odağı okuma yapmaz
    fireEvent.focus(window)
    await new Promise((r) => setTimeout(r, 20))
    expect(te().report.mock.calls.length).toBe(before)
    fireEvent.click(screen.getByRole('button', { name: /Görsel paneli/ }))
    te().report.mockResolvedValue(reportResponse({ images: [IMG] }))
    fireEvent.focus(window)
    await waitFor(() => expect(view.container.querySelector('[data-slot-id="monthly-tx"] img')).not.toBeNull())
    open.mockRestore()
  })
})
