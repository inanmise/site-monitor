import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, within } from './test-utils.jsx'
import { channel, overview } from './helpers/techErrorFixtures.js'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

// Kanal listesi dar kapta (telefon / tablet, < 960 px): KART düzeni — tablo yok, kartta "Şablonu aç", dönem raporu
// düğmesi, ikonlu işlem menüsü; sayfalama çubuğu kartların altında. Genişlik kancası 400 px'e sabitlenir.
vi.mock('../hooks/useElementWidth.js', () => ({ useElementWidth: () => [() => {}, 400], useElementWidthState: () => [400, () => {}] }))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), createChannel: vi.fn(), updateChannel: vi.fn(), deleteChannel: vi.fn(), template: vi.fn(),
      reports: vi.fn(), createReport: vi.fn(), report: vi.fn(), imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
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
  const list = []
  for (let i = 1; i <= 52; i++) list.push(channel(i, `Kanal ${String(i).padStart(2, '0')}`, i <= 2 ? { period_report: { id: 500 + i, status: 'sent' }, can_delete: true } : {}))
  te().overview.mockResolvedValue(overview(list))
})

describe('kanal listesi — dar kap (kartlar)', () => {
  it('kartlar çizilir (tablo yok), 50 + 2 sayfalanır; kartta şablon / dönem raporu düğmeleri ve ikonlu menü (sil dâhil)', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByText('52 / 52 kanal gösteriliyor')
    const list = document.querySelector('[data-slot="te-channel-list"]')
    expect(list).toHaveAttribute('data-layout', 'cards')
    expect(document.querySelectorAll('[data-slot="te-channel-card"]')).toHaveLength(50)
    expect(document.querySelector('[data-slot="te-channel-table"]')).toBeNull()
    const first = document.querySelector('[data-slot="te-channel-card"][data-channel="1"]')
    expect(within(first).getByRole('button', { name: 'Şablonu aç' })).toBeInTheDocument()
    expect(within(first).getByRole('button', { name: 'Eylül 2026 raporunu aç' })).toBeInTheDocument()
    expect(within(first).getByText(/Eylül 2026 · Gönderildi/)).toBeInTheDocument()
    pressMenuTrigger(within(first).getByRole('button', { name: /Kanal işlemleri/ }))
    expect(await screen.findByRole('menuitem', { name: 'Kanalı sil' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: 'Tüm raporlar' })).toBeNull()     // kartta ayrı düğme olarak var
    fireEvent.keyDown(document.activeElement, { key: 'Escape' })
    fireEvent.click(screen.getByRole('button', { name: 'Sonraki' }))
    expect(await screen.findByText('51–52 / 52 kayıt')).toBeInTheDocument()
    expect(document.querySelectorAll('[data-slot="te-channel-card"]')).toHaveLength(2)
  })

  it('kart adı kanalı açar; kanal görünümünden "Kanallara dön" listeye döner ve sayfa korunur', async () => {
    te().reports.mockResolvedValue({ success: true, data: { channel: channel(51, 'Kanal 51'), reports: [], default_period: { year: 2026, month: 9 } } })
    window.history.replaceState({}, '', '/?tab=channelreports&te_page=2')
    render(<TechErrorReportsPage />)
    await screen.findByText('51–52 / 52 kayıt')
    fireEvent.click(screen.getByRole('button', { name: 'Kanal 51' }))
    await screen.findByRole('heading', { name: 'Kanal 51' })
    expect(te().reports).toHaveBeenCalledWith('51')
    fireEvent.click(screen.getByRole('button', { name: 'Kanallara dön' }))
    expect(await screen.findByText('51–52 / 52 kayıt')).toBeInTheDocument()
  })
})
