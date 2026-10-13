import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { channel, templateResponse } from './helpers/techErrorFixtures.js'

// Kanal görünümü (2026-10-13): rapor listesi tablo ↔ kart (kap genişliği), rapor aç / sil (409 → yenile), yeni rapor
// penceresi (boş ilk ay seçili, açılmış ay "mevcut raporu aç", eşzamanlı açılış 409 → kazanan rapor), yükleme hataları
// (bulunamadı / yeniden dene / yenileme bandı), şablon sekmesinde kaydedilmemiş değişiklikle ayrılma onayı.
const width = vi.hoisted(() => ({ v: 0 }))
vi.mock('../hooks/useElementWidth.js', () => ({ useElementWidth: () => [() => {}, width.v] }))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      reports: vi.fn(), createReport: vi.fn(), deleteReport: vi.fn(), template: vi.fn(), saveTemplate: vi.fn(),
      defaultTemplate: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import ChannelView from '../components/techerror/ChannelView.jsx'

const te = () => api.techErrors
const ROW = {
  id: 100, year: 2026, month: 9, status: 'draft', title: 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026',
  updated_at: '2026-10-02T07:00:00Z', updated_by_name: 'Ayşegül Nur Karaosmanoğlu', can_delete: true, image_count: 0,
}

function reportsResponse(reports = [ROW], extra = {}) {
  return { success: true, data: { channel: channel(10, 'Mobil'), reports, default_period: { year: 2026, month: 9 }, ...extra } }
}

function renderView(props = {}) {
  const handlers = { onTabChange: vi.fn(), onBack: vi.fn(), onOpenReport: vi.fn() }
  const r = render(<ChannelView channelId="10" tab="reports" {...handlers} {...props} />)
  return { ...r, ...handlers }
}

beforeEach(() => {
  vi.clearAllMocks()
  width.v = 0
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  te().reports.mockResolvedValue(reportsResponse())
  te().template.mockResolvedValue(templateResponse(channel(10, 'Mobil')))
})

describe('Kanal görünümü — rapor listesi', () => {
  it('geniş kap: tablo; "Aç" raporu açar, sil onay ister ve listeyi yeniler', async () => {
    te().deleteReport.mockResolvedValue({ success: true, data: { deleted: true } })
    const v = renderView()
    const table = await screen.findByRole('table')
    expect(within(table).getByText(ROW.title)).toBeInTheDocument()
    expect(within(table).getByText(/Ayşegül Nur Karaosmanoğlu/)).toBeInTheDocument()
    fireEvent.click(within(table).getByRole('button', { name: 'Aç' }))
    expect(v.onOpenReport).toHaveBeenCalledWith(100, 10)
    fireEvent.click(within(table).getByRole('button', { name: 'Eylül 2026 raporunu sil' }))
    const dlg = await screen.findByRole('dialog')
    fireEvent.click(within(dlg).getByRole('button', { name: 'Raporu sil' }))
    await waitFor(() => expect(te().deleteReport).toHaveBeenCalledWith(100))
    expect(await screen.findByText('Rapor silindi.')).toBeInTheDocument()
    await waitFor(() => expect(te().reports).toHaveBeenCalledTimes(2))
  })

  it('silme 409 (gönderiliyor / gönderildi): hata söylenir ve liste yenilenir', async () => {
    te().deleteReport.mockResolvedValue({ success: false, code: 'REPORT_SENT_OR_SENDING',
      error: 'Rapor şu anda gönderiliyor ya da az önce gönderildi; silinmedi. Sayfayı yenileyin.' })
    renderView()
    fireEvent.click(await screen.findByRole('button', { name: 'Eylül 2026 raporunu sil' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Raporu sil' }))
    expect(await screen.findByText(/az önce gönderildi; silinmedi/)).toBeInTheDocument()
    await waitFor(() => expect(te().reports).toHaveBeenCalledTimes(2))
  })

  it('dar kap: kartlar; kart "Aç" ve "Sil" düğmeleri', async () => {
    width.v = 500
    const v = renderView()
    const cards = await screen.findAllByRole('listitem')
    expect(cards).toHaveLength(1)
    expect(screen.queryByRole('table')).toBeNull()
    fireEvent.click(within(cards[0]).getByRole('button', { name: 'Aç' }))
    expect(v.onOpenReport).toHaveBeenCalledWith(100, 10)
    expect(within(cards[0]).getByRole('button', { name: /Sil/ })).toBeInTheDocument()
  })

  it('rapor yoksa boş durum', async () => {
    te().reports.mockResolvedValue(reportsResponse([]))
    renderView()
    expect(await screen.findByText('Bu kanalın henüz raporu yok')).toBeInTheDocument()
  })
})

describe('Kanal görünümü — yeni rapor penceresi', () => {
  it('ilk boş ay seçili gelir ve açılır; açılmış ay "Mevcut raporu aç" olur', async () => {
    te().createReport.mockResolvedValue({ success: true, data: { id: 101 } })
    const v = renderView()
    fireEvent.click(await screen.findByRole('button', { name: 'Rapor hazırla' }))
    const dlg = await screen.findByRole('dialog')
    const select = within(dlg).getByRole('combobox')
    expect(select).toHaveValue('2026-08')
    fireEvent.click(within(dlg).getByRole('button', { name: 'Raporu aç' }))
    await waitFor(() => expect(te().createReport).toHaveBeenCalledWith(10, 2026, 8))
    await waitFor(() => expect(v.onOpenReport).toHaveBeenCalledWith(101, 10))
  })

  it('açılmış ay seçilince mevcut rapor açılır (yeni istek yok)', async () => {
    const v = renderView()
    fireEvent.click(await screen.findByRole('button', { name: 'Rapor hazırla' }))
    const dlg = await screen.findByRole('dialog')
    fireEvent.change(within(dlg).getByRole('combobox'), { target: { value: '2026-09' } })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Mevcut raporu aç' }))
    expect(v.onOpenReport).toHaveBeenCalledWith(100, 10)
    expect(te().createReport).not.toHaveBeenCalled()
  })

  it('eşzamanlı açılış 409 REPORT_EXISTS → kazanan rapor açılır; başka hata pencerede kalır', async () => {
    te().createReport.mockResolvedValueOnce({ success: false, code: 'REPORT_EXISTS', report_id: 222, error: 'x' })
    const v = renderView()
    fireEvent.click(await screen.findByRole('button', { name: 'Rapor hazırla' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Raporu aç' }))
    await waitFor(() => expect(v.onOpenReport).toHaveBeenCalledWith(222, 10))

    te().createReport.mockResolvedValueOnce({ success: false, code: 'CHANNEL_INACTIVE',
      error: 'Bu kanal pasif; yeni rapor açmak için önce kanalı etkinleştirin.' })
    fireEvent.click(await screen.findByRole('button', { name: 'Rapor hazırla' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Raporu aç' }))
    expect(await screen.findByText(/Bu kanal pasif/)).toBeInTheDocument()
  })
})

describe('Kanal görünümü — yükleme hataları ve şablon sekmesi', () => {
  it('404: kanal bulunamadı, düğme kanallara döner', async () => {
    te().reports.mockResolvedValue({ success: false, status: 404, error: 'Kanal bulunamadı; silinmiş olabilir.' })
    const v = renderView()
    expect(await screen.findByText('Kanal bulunamadı ya da görme yetkiniz yok')).toBeInTheDocument()
    fireEvent.click(screen.getAllByRole('button', { name: 'Kanallara dön' }).at(-1))
    expect(v.onBack).toHaveBeenCalled()
  })

  it('sunucu hatası: yeniden dene; sonraki yenileme hatası bandı son listeyi korur', async () => {
    te().reports
      .mockResolvedValueOnce({ success: false, status: 503, error: 'Sunucu şu anda yanıt vermiyor; birkaç dakika sonra deneyin.' })
      .mockResolvedValueOnce(reportsResponse())
      .mockResolvedValueOnce({ success: false, status: 503, error: 'Sunucu şu anda yanıt vermiyor; birkaç dakika sonra deneyin.' })
    te().deleteReport.mockResolvedValue({ success: true, data: { deleted: true } })
    renderView()
    expect(await screen.findByText('Veriler yüklenemedi; birkaç saniye sonra tekrar deneyin')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Tekrar dene' }))
    expect(await screen.findByText(ROW.title)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Eylül 2026 raporunu sil' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Raporu sil' }))
    expect(await screen.findByText('Liste yenilenemedi; son okunan hâli görüyorsunuz')).toBeInTheDocument()
    expect(screen.getByText(ROW.title)).toBeInTheDocument()
  })

  it('şablon sekmesinde kaydedilmemiş değişiklik: sekme değişimi onay ister', async () => {
    const v = renderView({ tab: 'template' })
    await screen.findByText('Rapor başlığı ve dili')
    fireEvent.change(screen.getByLabelText('Rapor başlığı'), { target: { value: 'Yeni başlık {kanal}' } })
    fireEvent.mouseDown(screen.getByRole('tab', { name: /Raporlar/ }), { button: 0 })
    const dlg = await screen.findByRole('dialog')
    expect(v.onTabChange).not.toHaveBeenCalled()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Kaydetmeden ayrıl' }))
    await waitFor(() => expect(v.onTabChange).toHaveBeenCalledWith('reports'))
  })
})
