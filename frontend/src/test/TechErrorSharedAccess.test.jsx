import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within, fireEvent, waitFor } from './test-utils.jsx'
import { channel, overview, reportResponse, openAllSections } from './helpers/techErrorFixtures.js'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

// Erişim paylaşımıyla görülen kanal raporları (2026-10-17, 9. sürüm): satırlar `shared_access` / `can_report` / `can_send`
// taşır — düzenleme paylaşımı rapor İÇERİĞİNİ yazdırır (rapor açma, değerler, görseller); kanal / şablon / onay /
// gönderim takımın kendisinde kalır. Eski sunucu (bayraklar yok) eski davranışı korur (can_edit).
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), reports: vi.fn(), template: vi.fn(), report: vi.fn(), approval: vi.fn(), createReport: vi.fn(),
      saveReport: vi.fn(), imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState({}, '', '/?tab=channelreports')
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  te().approval.mockResolvedValue({ success: true, data: { required: false, approval: { state: 'none' } } })
})

describe('erişim paylaşımı — kanal listesi', () => {
  it('yalnız VIEW paylaşımı: "Paylaşımla erişim" rozeti, rapor başlatma yok, kanal işlemleri yok, şablon salt okunur açılır', async () => {
    te().overview.mockResolvedValue(overview([
      channel(10, 'Mobil', { team_id: 9, team_name: 'Paylaşan Takım', can_edit: false, can_report: false, can_delete: false, shared_access: true }),
    ]))
    te().template.mockResolvedValue({ success: true, data: { channel: channel(10, 'Mobil', { can_edit: false, shared_access: true }), template: { v: 1, lang: 'tr', title: 'X', sections: [] }, version: 1 } })
    render(<TechErrorReportsPage />)
    const row = await screen.findByText('Paylaşımla erişim')
    expect(row.closest('[data-slot="te-channel-row"], [data-slot="te-channel-card"]')).not.toBeNull()
    expect(screen.queryByRole('button', { name: /raporunu hazırla/ })).toBeNull()
    // Satır menüsünde yalnız gezinme ("Tüm raporlar"); kanal işlemleri (düzenle / pasife al / sil) yok
    pressMenuTrigger(screen.getByRole('button', { name: 'Mobil — İşlemler' }))
    expect(await screen.findByRole('menuitem', { name: 'Tüm raporlar' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: 'Kanalı düzenle' })).toBeNull()
    expect(screen.queryByRole('menuitem', { name: 'Pasife al' })).toBeNull()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.getByRole('button', { name: 'Şablonu aç' })).toBeInTheDocument()
  })

  it('EDIT paylaşımı: rapor başlatılabilir (can_report) ama kanal düzenlenemez (can_edit yok); eski sunucu (bayrak yok) can_edit ile çalışır', async () => {
    te().overview.mockResolvedValue(overview([
      channel(10, 'Mobil', { can_edit: false, can_report: true, shared_access: true }),
      channel(20, 'POS'),                                                            // eski şekil: can_edit true, can_report yok
    ]))
    render(<TechErrorReportsPage />)
    const buttons = await screen.findAllByRole('button', { name: 'Eylül 2026 raporunu hazırla' })
    expect(buttons).toHaveLength(2)
    const shared = document.querySelector('[data-channel="10"]')
    expect(within(shared).getByText('Paylaşımla erişim')).toBeInTheDocument()
    pressMenuTrigger(within(shared).getByRole('button', { name: 'Mobil — İşlemler' }))
    expect(await screen.findByRole('menuitem', { name: 'Tüm raporlar' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: 'Kanalı düzenle' })).toBeNull()
    fireEvent.keyDown(document, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
    const own = document.querySelector('[data-channel="20"]')
    expect(within(own).queryByText('Paylaşımla erişim')).toBeNull()
    pressMenuTrigger(within(own).getByRole('button', { name: 'POS — İşlemler' }))
    await waitFor(() => expect(screen.getByRole('menuitem', { name: 'Kanalı düzenle' })).toBeInTheDocument())
  })
})

describe('erişim paylaşımı — rapor düzenleyici', () => {
  it('can_send:false → "Takıma gönder" ve takım e-postası uyarısı yok, kaydet var; can_send:true → gönder var', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_rep=100&te_ch=10')
    te().report.mockResolvedValue(reportResponse({ can_edit: true, can_send: false, can_delete: false, team_email_set: false, shared_access: true }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026' })
    expect(screen.queryByRole('button', { name: 'Takıma gönder' })).toBeNull()
    expect(document.querySelector('[data-slot="te-send"]')).toBeNull()
    expect(screen.getByRole('button', { name: 'Kaydet' })).toBeInTheDocument()
  })

  it('can_send:true (ya da eski sunucu) → gönder düğmesi var', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_rep=100&te_ch=10')
    te().report.mockResolvedValue(reportResponse({ can_edit: true }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026' })
    expect(document.querySelector('[data-slot="te-send"]')).not.toBeNull()
  })
})
