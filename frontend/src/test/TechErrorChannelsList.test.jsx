import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { channel, overview } from './helpers/techErrorFixtures.js'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

// Kanal listesi yeniden tasarımı (2026-10-17, 8. sürüm): tıklanabilir dönem özeti kartları (süzgeç), arama (Türkçe
// katlama), sıralama, "süzgeçleri temizle", sayfalama (URL te_page / te_ps), süzgeçler URL'de (te_q / te_st / te_sort).
// jsdom'da kap genişliği 0 → TABLO düzeni (satırlar `te-channel-row`); kart düzeni ayrı dosyada (genişlik kancası mock'lu).
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), createChannel: vi.fn(), updateChannel: vi.fn(), deleteChannel: vi.fn(), template: vi.fn(),
      saveTemplate: vi.fn(), defaultTemplate: vi.fn(), reports: vi.fn(), createReport: vi.fn(), report: vi.fn(),
      saveReport: vi.fn(), refreshTemplate: vi.fn(), deleteReport: vi.fn(), uploadImage: vi.fn(), deleteImage: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`, send: vi.fn(), downloadPdf: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors

/** 60 kanal: 1–20 gönderildi, 21–40 açık (taslak), 41–55 başlanmamış, 56–60 pasif; 50 + 10 sayfalanır (page ön ayarı 50). */
function manyChannels() {
  const out = []
  for (let i = 1; i <= 60; i++) {
    const name = `Kanal ${String(i).padStart(2, '0')}`
    const extra = i <= 20 ? { period_report: { id: 1000 + i, status: 'sent' } }
      : i <= 40 ? { period_report: { id: 1000 + i, status: 'draft' } }
        : i <= 55 ? {}
          : { active: false }
    if (i === 7) extra.description = 'İnternet Şubesi müşterileri'
    if (i === 60) extra.team_id = 2
    if (i === 60) extra.team_name = 'Kurumsal Kanallar'
    out.push(channel(i, name, extra))
  }
  return out
}

const rows = () => document.querySelectorAll('[data-slot="te-channel-row"]')

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState({}, '', '/?tab=channelreports')
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  te().overview.mockResolvedValue(overview(manyChannels()))
})

describe('kanal listesi — tablo, süzgeçler, sayfalama', () => {
  it('60 kanal: tablo düzeninde ilk sayfa 50 satır, çubukla ikinci sayfa 10; sayım ve URL te_page=2', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByText('60 / 60 kanal gösteriliyor')
    expect(document.querySelector('[data-slot="te-channel-list"]')).toHaveAttribute('data-layout', 'table')
    expect(rows()).toHaveLength(50)
    expect(screen.getByText('1–50 / 60 kayıt')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Sonraki' }))
    await waitFor(() => expect(rows()).toHaveLength(10))
    await waitFor(() => expect(window.location.search).toContain('te_page=2'))
    // Pasifler listenin sonunda (ad sırası, aktifler önce): son sayfada pasif satırlar
    expect(document.querySelectorAll('[data-slot="te-channel-row"][data-active="false"]')).toHaveLength(5)
  })

  it('dönem özeti kartı süzgeçtir: "Raporu başlanmamış" → yalnız 15 kanal, aria-pressed, URL te_st=missing; yeniden tıklayınca kalkar', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByText('60 / 60 kanal gösteriliyor')
    const missing = screen.getByRole('button', { name: /Raporu başlanmamış/ })
    expect(within(missing).getByText('15')).toBeInTheDocument()
    fireEvent.click(missing)
    await screen.findByText('15 / 60 kanal gösteriliyor')
    expect(missing).toHaveAttribute('aria-pressed', 'true')
    expect(rows()).toHaveLength(15)
    await waitFor(() => expect(window.location.search).toContain('te_st=missing'))
    expect(screen.getByRole('button', { name: 'Süzgeçleri temizle' })).toBeInTheDocument()
    fireEvent.click(missing)
    await screen.findByText('60 / 60 kanal gösteriliyor')
    expect(missing).toHaveAttribute('aria-pressed', 'false')
    // Süzgeç yokken "Aktif kanal" kartı etkin (diğer sayfalarla aynı: tıklanması süzgeci kaldırır)
    expect(document.querySelector('[data-slot="stat-item"][data-key="all"]')).toHaveAttribute('aria-pressed', 'true')
    await waitFor(() => expect(window.location.search).not.toContain('te_st='))
    // Pasif kartı: pasif kanallar
    fireEvent.click(screen.getByRole('button', { name: /Pasif kanal/ }))
    await screen.findByText('5 / 60 kanal gösteriliyor')
    expect(document.querySelectorAll('[data-slot="te-channel-row"][data-active="false"]')).toHaveLength(5)
  })

  it('arama Türkçe katlar ("internet" açıklamadaki İnternet Şubesi); eşleşme yoksa boş durum + temizle; URL te_q', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByText('60 / 60 kanal gösteriliyor')
    const search = screen.getByRole('searchbox', { name: 'Kanal, takım ya da açıklama ara' })
    expect(search).toHaveAttribute('data-page-search')
    fireEvent.change(search, { target: { value: 'internet' } })
    await screen.findByText('1 / 60 kanal gösteriliyor')
    expect(rows()).toHaveLength(1)
    expect(within(rows()[0]).getByRole('button', { name: 'Kanal 07' })).toBeInTheDocument()
    await waitFor(() => expect(window.location.search).toContain('te_q=internet'))
    fireEvent.change(search, { target: { value: 'yok böyle kanal' } })
    expect(await screen.findByText('Süzgeçlerle eşleşen kanal yok')).toBeInTheDocument()
    expect(rows()).toHaveLength(0)
    fireEvent.click(screen.getAllByRole('button', { name: 'Süzgeçleri temizle' })[0])
    await screen.findByText('60 / 60 kanal gösteriliyor')
    expect(search).toHaveValue('')
  })

  it('sıralama: "duruma göre" eksik raporlar önce, pasifler sonda; URL te_sort=status; "takıma göre" diğer takım adı sırasına girer', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByText('60 / 60 kanal gösteriliyor')
    const sort = screen.getByLabelText('Sıralama')
    fireEvent.change(sort, { target: { value: 'status' } })
    await waitFor(() => expect(within(rows()[0]).getByRole('button', { name: 'Kanal 41' })).toBeInTheDocument())
    expect(rows()[15].getAttribute('data-channel')).toBe('21')        // 15 eksikten sonra açıklar
    await waitFor(() => expect(window.location.search).toContain('te_sort=status'))
    fireEvent.change(sort, { target: { value: 'team' } })
    // "Dijital Kanallar …" < "Kurumsal Kanallar": 60 numaralı (Kurumsal) en sonda, pasif olduğu için zaten sonda — ad sırası korunur
    await waitFor(() => expect(window.location.search).toContain('te_sort=team'))
    fireEvent.click(screen.getByRole('button', { name: 'Sonraki' }))
    await waitFor(() => expect(rows()).toHaveLength(10))
    expect(rows()[9].getAttribute('data-channel')).toBe('60')
  })

  it('bağlantıyla açılır: te_st=sent&te_sort=status&te_page=1 ilk yüklemede uygulanır; tablo satırında "Şablonu aç" ve etiketli işlem menüsü', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_st=sent&te_sort=status')
    render(<TechErrorReportsPage />)
    await screen.findByText('20 / 60 kanal gösteriliyor')
    // Etkin kartın erişilebilir adı "süzgeci kaldır" ipucudur (MonitorStatsBar) — kart anahtarıyla bulunur
    expect(document.querySelector('[data-slot="stat-item"][data-key="sent"]')).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByLabelText('Sıralama')).toHaveValue('status')
    const first = rows()[0]
    expect(within(first).getByRole('button', { name: 'Şablonu aç' })).toBeInTheDocument()
    expect(within(first).getByRole('button', { name: 'Eylül 2026 raporunu aç' })).toBeInTheDocument()
    pressMenuTrigger(within(first).getByRole('button', { name: 'Kanal 01 — İşlemler' }))
    expect(await screen.findByRole('menuitem', { name: 'Tüm raporlar' })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: 'Kanalı düzenle' })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: 'Pasife al' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: 'Kanalı sil' })).toBeNull()        // can_delete yok
  })

  it('"Eylül 2026 raporunu hazırla" rapor açar ve rapora geçer; süzgeçler kanaldan dönünce korunur', async () => {
    te().createReport.mockResolvedValue({ success: true, data: { id: 777 } })
    te().report.mockResolvedValue({ success: false, status: 404, error: 'yok' })
    window.history.replaceState({}, '', '/?tab=channelreports&te_st=missing')
    render(<TechErrorReportsPage />)
    await screen.findByText('15 / 60 kanal gösteriliyor')
    fireEvent.click(within(rows()[0]).getByRole('button', { name: 'Eylül 2026 raporunu hazırla' }))
    await waitFor(() => expect(te().createReport).toHaveBeenCalledWith(41, 2026, 9))
    await waitFor(() => expect(window.location.search).toContain('te_rep=777'))
    expect(window.location.search).toContain('te_st=missing')      // liste süzgeci URL'de kalır → dönüşte aynı liste
  })
})
