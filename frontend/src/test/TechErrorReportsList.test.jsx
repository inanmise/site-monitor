import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { channel } from './helpers/techErrorFixtures.js'

// Kanalın rapor listesi (2026-10-17, 8. sürüm): başlıkta sayım, arama (dönem / başlık, Türkçe katlama), durum ve yıl
// süzgeçleri, sayfalama (panel ön ayarı 25; URL te_rpage), sayfadaki satırlar yıllara göre başlıklı; kanaldan çıkınca
// rapor süzgeçleri URL'den silinir, liste süzgeçleri kalır.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), reports: vi.fn(), template: vi.fn(), createReport: vi.fn(), report: vi.fn(), deleteReport: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors
const MONTHS = ['', 'Ocak', 'Şubat', 'Mart', 'Nisan', 'Mayıs', 'Haziran', 'Temmuz', 'Ağustos', 'Eylül', 'Ekim', 'Kasım', 'Aralık']

/** 30 rapor: 2026-09 geriye doğru 30 ay (2024-04'e kadar); 2026 raporları taslak / değişen, 2025 gönderildi, 2024 kuyrukta. */
function manyReports() {
  const out = []
  let y = 2026, m = 9
  for (let i = 0; i < 30; i++) {
    const status = y === 2026 ? (m % 2 ? 'draft' : 'changed') : y === 2025 ? 'sent' : 'queued'
    out.push({ id: 900 - i, year: y, month: m, status, title: `Mobil Kanalı Teknik Hata Mesajları - ${MONTHS[m]} ${y}`,
      updated_at: `${y}-${String(m).padStart(2, '0')}-20T07:00:00Z`, updated_by_name: 'Ayşe Yılmaz', can_delete: status !== 'sent',
      last_sent_at: status === 'sent' ? `${y}-${String(m).padStart(2, '0')}-25T08:00:00Z` : null })
    m--
    if (m === 0) { m = 12; y-- }
  }
  return out
}

const rows = () => document.querySelectorAll('[data-slot="te-report-row"]')

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=channel&te_ch=10')
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  te().reports.mockResolvedValue({ success: true, data: { channel: channel(10, 'Mobil', { approval_required: true, executive_count: 2 }),
    reports: manyReports(), default_period: { year: 2026, month: 9 } } })
  te().overview.mockResolvedValue({ success: true, data: { period: { year: 2026, month: 9 }, teams: [], channels: [channel(10, 'Mobil')], can_create: true } })
})

describe('kanal görünümü — rapor listesi', () => {
  it('başlıkta sayım ve onay çipi; 30 rapor 25 + 5 sayfalanır, sayfadaki satırlar yıl başlıklarıyla; URL te_rpage=2', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: 'Mobil' })
    expect(screen.getByText('30 rapor · 12 gönderildi')).toBeInTheDocument()
    expect(screen.getByText('PO onayı · 2 yönetici alıcı')).toBeInTheDocument()
    expect(screen.getByText('30 / 30 rapor gösteriliyor')).toBeInTheDocument()
    expect(rows()).toHaveLength(25)
    const years = [...document.querySelectorAll('[data-slot="te-report-year"]')].map((y) => y.getAttribute('data-year'))
    expect(years).toEqual(['2026', '2025', '2024'])
    expect(within(document.querySelector('[data-slot="te-report-year"][data-year="2026"]')).getByText('9 rapor')).toBeInTheDocument()
    // 2024'ün 9 raporundan 4'ü bu sayfada: başlık yılın TÜM sayısını söyler (sayfadaki dilimi değil — inceleme 2026-10-17)
    const y2024 = document.querySelector('[data-slot="te-report-year"][data-year="2024"]')
    expect(within(y2024).getByText('9 rapor')).toBeInTheDocument()
    expect(y2024.querySelectorAll('[data-slot="te-report-row"]')).toHaveLength(4)
    fireEvent.click(screen.getByRole('button', { name: 'Sonraki' }))
    await waitFor(() => expect(rows()).toHaveLength(5))
    expect([...document.querySelectorAll('[data-slot="te-report-year"]')].map((y) => y.getAttribute('data-year'))).toEqual(['2024'])
    expect(within(document.querySelector('[data-slot="te-report-year"][data-year="2024"]')).getByText('9 rapor')).toBeInTheDocument()
    await waitFor(() => expect(window.location.search).toContain('te_rpage=2'))
  })

  it('durum süzgeci "Gönderildi" 12 rapor (URL te_rst=sent); yıl süzgeci 2024 → 9; arama "nisan 2024" → 1; temizle hepsini sıfırlar', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByText('30 / 30 rapor gösteriliyor')
    fireEvent.change(screen.getByLabelText('Durum'), { target: { value: 'sent' } })
    await screen.findByText('12 / 30 rapor gösteriliyor')
    await waitFor(() => expect(window.location.search).toContain('te_rst=sent'))
    expect(rows()).toHaveLength(12)
    fireEvent.change(screen.getByLabelText('Durum'), { target: { value: 'all' } })
    fireEvent.change(screen.getByLabelText('Yıl'), { target: { value: '2024' } })
    await screen.findByText('9 / 30 rapor gösteriliyor')
    await waitFor(() => expect(window.location.search).toContain('te_ry=2024'))
    const search = screen.getByRole('searchbox', { name: 'Dönem ya da başlık ara' })
    fireEvent.change(search, { target: { value: 'NİSAN 2024' } })
    await screen.findByText('1 / 30 rapor gösteriliyor')
    expect(within(rows()[0]).getByText('Nisan 2024')).toBeInTheDocument()
    fireEvent.change(search, { target: { value: 'yok' } })
    expect(await screen.findByText('Süzgeçlerle eşleşen rapor yok')).toBeInTheDocument()
    fireEvent.click(screen.getAllByRole('button', { name: 'Süzgeçleri temizle' })[0])
    await screen.findByText('30 / 30 rapor gösteriliyor')
    expect(screen.getByLabelText('Yıl')).toHaveValue('all')
    await waitFor(() => expect(window.location.search).not.toContain('te_ry='))
  })

  it('bağlantıyla açılan süzgeç (te_rst=queued) uygulanır; "Kanallara dön" rapor süzgeçlerini URL\'den siler', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=channel&te_ch=10&te_rst=queued&te_q=mobil')
    render(<TechErrorReportsPage />)
    await screen.findByText('9 / 30 rapor gösteriliyor')
    expect(screen.getByLabelText('Durum')).toHaveValue('queued')
    fireEvent.click(screen.getByRole('button', { name: 'Kanallara dön' }))
    await screen.findByRole('searchbox', { name: 'Kanal, takım ya da açıklama ara' })
    await waitFor(() => expect(window.location.search).not.toContain('te_rst='))
    expect(window.location.search).toContain('te_q=mobil')          // kanal listesi süzgeci korunur
  })

  it('rapor silme onay ister; başarıda liste yenilenir; satırdaki "Aç" raporu açar', async () => {
    te().deleteReport.mockResolvedValue({ success: true })
    te().report.mockResolvedValue({ success: false, status: 404, error: 'yok' })
    render(<TechErrorReportsPage />)
    await screen.findByText('30 / 30 rapor gösteriliyor')
    const first = rows()[0]
    fireEvent.click(within(first).getByRole('button', { name: 'Eylül 2026 raporunu sil' }))
    const dialog = await screen.findByRole('dialog', { name: 'Rapor kalıcı olarak silinsin mi?' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Raporu sil' }))
    await waitFor(() => expect(te().deleteReport).toHaveBeenCalledWith(900))
    await waitFor(() => expect(te().reports).toHaveBeenCalledTimes(2))
    fireEvent.click(within(rows()[0]).getByRole('button', { name: 'Aç' }))
    await waitFor(() => expect(window.location.search).toContain('te_rep=900'))
  })
})
