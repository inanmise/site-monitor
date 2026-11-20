import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { channel } from './helpers/techErrorFixtures.js'

// Onay görünürlüğü (A1, 2026-11-20): kanal satırında ve rapor satırında dönem durumunun YANINDA ikinci rozet (onay
// bekliyor / yeniden onay gerekir / reddedildi / onaylandı · gönderilmedi); kanal listesinde "Onay bekleyen" ve (yalnız
// onaylayıcı olunan takım varsa) "Onayımı bekleyen" kutucukları süzgeçtir; kanal görünümünde "Onay bekleyen" durum
// seçeneği yalnız onay akışlı kanalda. Bilinmeyen onay verisi rozet üretmez; "Açık" kutucuğunun anlamı değişmez.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), reports: vi.fn(), template: vi.fn(), createReport: vi.fn(), report: vi.fn(), deleteReport: vi.fn(),
      reorderChannels: vi.fn(), updateChannel: vi.fn(), deleteChannel: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'

const te = () => api.techErrors
const FLOW = { approval_required: true, executive_count: 2 }
const pr = (id, status, state) => ({ id, year: 2026, month: 9, status, approval: { state } })

const TEAMS = [
  { id: 1, name: 'Dijital Kanallar', active: true, has_email: true, can_edit: true, can_manage: false, can_approve: true },
  { id: 2, name: 'Kurumsal Kanallar', active: true, has_email: true, can_edit: true, can_manage: false, can_approve: false },
]
function channels() {
  const t2 = { team_id: 2, team_name: 'Kurumsal Kanallar' }
  return [
    channel(1, 'Mobil', { ...FLOW, period_report: pr(101, 'draft', 'pending') }),
    channel(2, 'POS', { ...FLOW, ...t2, period_report: pr(102, 'draft', 'pending') }),
    channel(3, 'ATM', { ...FLOW, period_report: pr(103, 'changed', 'stale') }),
    channel(4, 'Çağrı Merkezi', { ...FLOW, period_report: pr(104, 'draft', 'rejected') }),
    channel(5, 'İnternet Şubesi', { ...FLOW, period_report: pr(105, 'draft', 'approved') }),
    channel(6, 'Kiosk', { ...FLOW, period_report: pr(106, 'sent', 'approved') }),
    channel(7, 'Video', { period_report: pr(107, 'draft', 'pending') }),            // akışsız: eski durum yok sayılır
    channel(8, 'WhatsApp', { ...FLOW, period_report: { id: 108, status: 'draft' } }), // onay verisi yok (eski sunucu)
  ]
}
const row = (id) => document.querySelector(`[data-slot="te-channel-row"][data-channel="${id}"]`)
const badgeOf = (el) => el?.querySelector('[data-slot="te-approval-badge"]')
const rows = () => document.querySelectorAll('[data-slot="te-channel-row"]')
const stat = (key) => document.querySelector(`[data-slot="stat-item"][data-key="${key}"]`)
const statValue = (key) => stat(key)?.querySelector('[data-slot="stat-value"]')?.textContent

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState({}, '', '/?tab=channelreports')
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  te().overview.mockResolvedValue({ success: true, data: { period: { year: 2026, month: 9 }, teams: TEAMS, channels: channels(), can_create: true } })
})

describe('kanal listesi — onay rozeti ve süzgeçleri', () => {
  it('rozetler: bekliyor / yeniden onay / reddedildi / onaylandı · gönderilmedi; gönderilmiş, akışsız ve verisiz kanalda rozet yok', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByText('8 / 8 kanal gösteriliyor')
    expect(row(1)).not.toBeNull()
    expect(badgeOf(row(1))).toHaveTextContent('Onay bekliyor')
    expect(badgeOf(row(1))).toHaveAttribute('data-approval', 'pending')
    expect(badgeOf(row(3))).toHaveTextContent('Yeniden onay gerekir')
    expect(badgeOf(row(4))).toHaveTextContent('Onay reddedildi')
    expect(badgeOf(row(5))).toHaveTextContent('Onaylandı · gönderilmedi')
    expect(badgeOf(row(6))).toBeNull()
    expect(badgeOf(row(7))).toBeNull()
    expect(badgeOf(row(8))).toBeNull()
    // Rozet kesilmez, sarar
    for (const b of document.querySelectorAll('[data-slot="te-approval-badge"]')) {
      expect(b.className).toContain('whitespace-normal')
      expect(b.className).toContain('overflow-visible')
      expect(b.className).not.toMatch(/\btruncate\b/)
    }
  })

  it('"Onay bekleyen" kutucuğu süzgeçtir (URL te_st=approval_pending); "Açık" kutucuğunun sayısı değişmez; durum ekran okuyucuya söylenir', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByText('8 / 8 kanal gösteriliyor')
    const openCount = statValue('open')
    const pending = stat('approval_pending')
    expect(pending).toHaveAccessibleName(/^Onay bekleyen/)
    expect(statValue('approval_pending')).toBe('2')
    fireEvent.click(pending)
    await screen.findByText('2 / 8 kanal gösteriliyor')
    expect([...rows()].map((r) => r.getAttribute('data-channel')).sort()).toEqual(['1', '2'])
    await waitFor(() => expect(window.location.search).toContain('te_st=approval_pending'))
    expect(document.querySelector('[data-slot="te-order-status"]')).toHaveTextContent('2 kanal gösteriliyor')
    expect(pending).toHaveAttribute('aria-pressed', 'true')
    expect(statValue('open')).toBe(openCount)
    fireEvent.click(pending)
    await screen.findByText('8 / 8 kanal gösteriliyor')
  })

  it('"Onayımı bekleyen" yalnız onaylayıcı olunan takımın bekleyen kanallarını listeler', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByText('8 / 8 kanal gösteriliyor')
    const mine = stat('my_approval')
    expect(mine).toHaveAccessibleName(/^Onayımı bekleyen/)
    expect(statValue('my_approval')).toBe('1')
    fireEvent.click(mine)
    await screen.findByText('1 / 8 kanal gösteriliyor')
    expect([...rows()].map((r) => r.getAttribute('data-channel'))).toEqual(['1'])
    await waitFor(() => expect(window.location.search).toContain('te_st=my_approval'))
  })

  it('onaylayıcı olunan takım yoksa (can_approve bilinmiyor / false) "Onayımı bekleyen" sunulmaz; bağlantıdaki değer tümüne döner', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_st=my_approval')
    te().overview.mockResolvedValue({ success: true, data: { period: { year: 2026, month: 9 },
      teams: TEAMS.map((tm) => ({ ...tm, can_approve: null })), channels: channels(), can_create: true } })
    render(<TechErrorReportsPage />)
    await screen.findByText('8 / 8 kanal gösteriliyor')
    expect(stat('my_approval')).toBeNull()
    expect(stat('approval_pending')).not.toBeNull()
    // Bağlantıdaki değerin "tümü"ne düşmesi kullanıcının değişikliği değildir — söylenmez (inceleme 2026-11-20)
    await new Promise((r) => setTimeout(r, 20))
    expect(document.querySelector('[data-slot="te-order-status"]')).toHaveTextContent('')
  })

  it('onay akışlı kanal yoksa onay kutucukları hiç çizilmez', async () => {
    te().overview.mockResolvedValue({ success: true, data: { period: { year: 2026, month: 9 }, teams: TEAMS,
      channels: [channel(1, 'Mobil'), channel(2, 'POS', { period_report: { id: 5, status: 'draft' } })], can_create: true } })
    window.history.replaceState({}, '', '/?tab=channelreports&te_st=approval_pending')
    render(<TechErrorReportsPage />)
    await screen.findByText('2 / 2 kanal gösteriliyor')
    expect(stat('approval_pending')).toBeNull()
    expect(stat('my_approval')).toBeNull()
  })
})

describe('kanal görünümü — rapor satırı onay rozeti ve "Onay bekleyen" süzgeci', () => {
  const REPORTS = [
    { id: 901, year: 2026, month: 9, status: 'draft', title: 'Eylül', approval: { state: 'pending' } },
    { id: 902, year: 2026, month: 8, status: 'sent', title: 'Ağustos', approval: { state: 'approved' } },
    { id: 903, year: 2026, month: 7, status: 'changed', title: 'Temmuz', approval: { state: 'stale' } },
    { id: 904, year: 2026, month: 6, status: 'draft', title: 'Haziran', approval: { state: 'none' } },
  ]
  const reportRow = (id) => document.querySelector(`[data-slot="te-report-row"][data-report="${id}"]`)

  function openChannel(ch) {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=channel&te_ch=10')
    te().reports.mockResolvedValue({ success: true, data: { channel: ch, reports: REPORTS, default_period: { year: 2026, month: 9 } } })
    render(<TechErrorReportsPage />)
  }

  it('onay akışlı kanal: satır rozetleri; durum seçeneğinde "Onay bekleyen" yalnız bekleyen raporu bırakır, sonuç söylenir', async () => {
    openChannel(channel(10, 'Mobil', FLOW))
    await screen.findByText('4 / 4 rapor gösteriliyor')
    expect(badgeOf(reportRow(901))).toHaveTextContent('Onay bekliyor')
    expect(badgeOf(reportRow(902))).toBeNull()
    expect(badgeOf(reportRow(903))).toHaveTextContent('Yeniden onay gerekir')
    expect(badgeOf(reportRow(904))).toBeNull()
    const select = screen.getByLabelText('Durum')
    expect([...select.querySelectorAll('option')].map((o) => o.value)).toContain('approval_pending')
    fireEvent.change(select, { target: { value: 'approval_pending' } })
    await screen.findByText('1 / 4 rapor gösteriliyor')
    expect(reportRow(901)).not.toBeNull()
    expect(document.querySelector('[data-slot="te-rfilter-status"]')).toHaveTextContent('1 rapor gösteriliyor')
    await waitFor(() => expect(window.location.search).toContain('te_rst=approval_pending'))
  })

  it('akışsız kanal: rozet yok, seçenek yok; bağlantıdaki approval_pending tümü sayılır', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=channel&te_ch=10&te_rst=approval_pending')
    te().reports.mockResolvedValue({ success: true, data: { channel: channel(10, 'Mobil'), reports: REPORTS, default_period: { year: 2026, month: 9 } } })
    render(<TechErrorReportsPage />)
    await screen.findByText('4 / 4 rapor gösteriliyor')
    expect(document.querySelectorAll('[data-slot="te-approval-badge"]')).toHaveLength(0)
    const select = screen.getByLabelText('Durum')
    expect([...select.querySelectorAll('option')].map((o) => o.value)).not.toContain('approval_pending')
    expect(select).toHaveValue('all')
    await new Promise((r) => setTimeout(r, 20))
    expect(document.querySelector('[data-slot="te-rfilter-status"]')).toHaveTextContent('')  // yüklemede / geri düşmede söylenmez
  })

  it('kullanıcı süzgeci değiştirince söylenir; açılışta (bağlantıdaki geçerli süzgeç) söylenmez', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=channel&te_ch=10&te_rst=draft')
    te().reports.mockResolvedValue({ success: true, data: { channel: channel(10, 'Mobil'), reports: REPORTS, default_period: { year: 2026, month: 9 } } })
    render(<TechErrorReportsPage />)
    await screen.findByText('2 / 4 rapor gösteriliyor')
    await new Promise((r) => setTimeout(r, 20))
    const region = () => document.querySelector('[data-slot="te-rfilter-status"]')
    expect(region()).toHaveTextContent('')
    fireEvent.change(screen.getByLabelText('Durum'), { target: { value: 'sent' } })
    await screen.findByText('1 / 4 rapor gösteriliyor')
    expect(region()).toHaveTextContent('1 rapor gösteriliyor')
  })
})
