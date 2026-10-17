import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, within } from './test-utils.jsx'

// Ayarlar → Teknik hata raporu, dar kapta (telefon; kap < 600 px): KART düzeni — tablo yok, kartta takım durumu / onay akışı /
// dönem raporu satırları ve "Kanalı aç" düğmesi. Genişlik kancası 400 px'e sabitlenir (TechErrorChannelsList.mobile ile aynı).
vi.mock('../hooks/useElementWidth.js', () => ({ useElementWidth: () => [() => {}, 400], useElementWidthState: () => [400, () => {}] }))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    admin: { techErrorSettings: vi.fn(), saveTechErrorSettings: vi.fn() },
    teams: { directory: vi.fn() },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportSettings from '../components/admin/TechErrorReportSettings.jsx'

const LONG = 'Bireysel İnternet Şubesi ve Mobil Bankacılık Ortak Kanalı (Dijital Kanallar / Bireysel Bankacılık)'
const settings = () => ({ success: true, data: {
  team_ids: [], read_only: false, period: { year: 2026, month: 9 },
  channels: [
    { id: 10, name: LONG, active: true, team_id: 1, team_name: 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi',
      team_visible: true, executive_count: 2, approver_count: 1, team_email_set: true,
      period_report: { id: 100, status: 'sent', last_send_status: 'sent', approval: { state: 'approved' } } },
    { id: 20, name: 'POS', active: true, team_id: 2, team_name: 'Ödeme Sistemleri', team_visible: false, executive_count: 0, approver_count: 0,
      team_email_set: false, period_report: null },
  ],
} })

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  api.teams.directory.mockResolvedValue({ success: true, data: [{ id: 1, name: 'Dijital Kanallar', active: true }] })
})

describe('TechErrorReportSettings — dar kap (kartlar)', () => {
  it('kartlar çizilir (tablo yok): uzun ad tam, takım rozeti sarar, dönem etiketi rozette, "Kanalı aç" düğmesi kanal sayfasına gider', async () => {
    api.admin.techErrorSettings.mockResolvedValue(settings())
    const seen = []
    const onNav = (e) => seen.push(e.detail)
    window.addEventListener('sm:navigate', onNav)
    try {
      render(<TechErrorReportSettings />)
      await screen.findByText(LONG)
      expect(document.querySelector('[data-slot="tes-list"]')).toHaveAttribute('data-layout', 'cards')
      expect(document.querySelector('[data-slot="tes-table"]')).toBeNull()
      const cards = document.querySelectorAll('[data-slot="tes-channel"]')
      expect(cards).toHaveLength(2)
      expect(cards[0].tagName).not.toBe('TR')
      const first = within(cards[0])
      expect(first.getByText('Eylül 2026 raporu')).toBeInTheDocument()   // kart satır başlığı
      expect(first.getByText('Gönderildi')).toBeInTheDocument()
      expect(first.getByText('Onaylandı')).toBeInTheDocument()
      expect(first.getByText('Yönetici alıcı: 2')).toBeInTheDocument()
      expect(first.getByText('Takım durumu')).toBeInTheDocument()
      expect(first.getByText('Onay akışı')).toBeInTheDocument()
      const badge = cards[0].querySelector('[data-slot="team-badge"]')
      expect(badge.className).toContain('overflow-visible')
      expect(badge.className).toContain('rounded-md')
      expect(badge.querySelector('.truncate')).toBeNull()
      const second = within(cards[1])
      expect(second.getByText('Modül bu takıma kapalı')).toBeInTheDocument()
      expect(second.getByText('Eksik — rapor gönderilemez')).toBeInTheDocument()
      expect(second.getByText('Dönem raporu yok')).toBeInTheDocument()
      fireEvent.click(second.getByRole('button', { name: 'Kanalı aç' }))
      expect(seen).toEqual([{ tab: 'channelreports', params: { te_view: 'channel', te_ch: '20' } }])
      // Kanal adı da bağlantı
      fireEvent.click(first.getByRole('button', { name: `Kanalı aç — ${LONG}` }))
      expect(seen).toHaveLength(2)
      expect(seen[1].params.te_ch).toBe('10')
    } finally {
      window.removeEventListener('sm:navigate', onNav)
    }
  })
})
