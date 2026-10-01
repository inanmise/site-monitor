import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'

/**
 * Haftalık Raporlar takım görünürlüğü ayarı (2026-09-16): varsayılan kapalı olduğu için liste
 * "hiç açık yok" uyarısıyla gelir; anahtar açınca uç çağrılır, raporu olan takımı kapatırken onay sorulur.
 */
const confirmMock = vi.fn(() => Promise.resolve(true))
const toastMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
vi.mock('../components/ui/Dialog.jsx', () => ({ useDialog: () => ({ showConfirm: confirmMock }), DialogProvider: ({ children }) => children }))
vi.mock('../components/ui/Toast.jsx', () => ({ useToast: () => toastMock, ToastProvider: ({ children }) => children }))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  api: withApiFallback({ weeklyReports: { accessTeams: vi.fn(), setAccess: vi.fn() } }),
}))
import { api } from '../api/client'
import WeeklyReportAccessSettings from '../components/admin/WeeklyReportAccessSettings.jsx'

const wrap = () => render(<LangProvider><WeeklyReportAccessSettings /></LangProvider>)
const TEAMS = [
  { team_id: 5, team_name: 'Takım A', active: true, enabled: false, reminder: true, report_count: 3,
    po_users: [{ user_id: 11, display_name: 'Kişi B', email: 'b@example.com' }, { user_id: 12, display_name: 'Kişi C', email: null }],
    manager_user_id: 91, manager_display_name: 'Elle E', manager_manual: true, manager_email: 'e@example.com',
    last_report: { id: 100, report_year: 2026, week_no: 39, iso_week: '2026-W39', week_label: '21–27 Eylül 2026', status: 'APPROVED',
      submitted_at: '2026-09-25T10:00:00', approved_at: '2026-09-26T08:00:00', sent_at: null, updated_at: '2026-09-26T08:00:00' } },
  { team_id: 9, team_name: 'Takım B', active: false, enabled: false, reminder: false, report_count: 0,
    po_users: [], manager_user_id: 90, manager_display_name: 'Müdür M', manager_manual: false, manager_email: null, last_report: null },
]

describe('WeeklyReportAccessSettings', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    confirmMock.mockResolvedValue(true)
    api.weeklyReports.accessTeams.mockResolvedValue({ success: true, data: { teams: TEAMS, enabled_count: 0 } })
    api.weeklyReports.setAccess.mockResolvedValue({ success: true, data: { enabled: true } })
  })

  it('hiç açık takım yoksa uyarı; anahtar açınca setAccess(id, true) çağrılır ve satır güncellenir', async () => {
    wrap()
    await screen.findByText(/Şu an hiçbir takımda açık değil|Not switched on for any team yet/)
    expect(screen.getByText(/0 takımda açık|on for 0 teams/)).toBeInTheDocument()
    const sw = screen.getByRole('switch', { name: /Takım A — modülü aç|Takım A — switch the module on/ })
    expect(sw.getAttribute('aria-checked')).toBe('false')
    fireEvent.click(sw)
    await waitFor(() => expect(api.weeklyReports.setAccess).toHaveBeenCalledWith(5, true))
    await waitFor(() => expect(screen.getByRole('switch', { name: /Takım A/ }).getAttribute('aria-checked')).toBe('true'))
    expect(confirmMock).not.toHaveBeenCalled()   // AÇMA onay sormaz
    expect(toastMock.success).toHaveBeenCalled()
  })

  it('raporu olan takımı kapatırken onay sorulur; iptal edilirse uç çağrılmaz', async () => {
    api.weeklyReports.accessTeams.mockResolvedValue({ success: true, data: { teams: [{ ...TEAMS[0], enabled: true }], enabled_count: 1 } })
    confirmMock.mockResolvedValue(false)
    wrap()
    const sw = await screen.findByRole('switch', { name: /Takım A/ })
    fireEvent.click(sw)
    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    expect(confirmMock.mock.calls[0][0].message).toMatch(/3/)   // rapor sayısı onayda görünür
    expect(api.weeklyReports.setAccess).not.toHaveBeenCalled()
    expect(sw.getAttribute('aria-checked')).toBe('true')
  })

  it('satır bilgisi (2026-09-30): PO adları mailto ile, müdür kaynak rozetiyle (elle / AD), son rapor ISO hafta + durum + onay zamanı; rapor yoksa "Hiç gönderilmedi"', async () => {
    wrap()
    await screen.findByText('Takım A')
    const rowA = screen.getByText('Takım A').closest('tr')
    const rowB = screen.getByText('Takım B').closest('tr')
    // PO: e-postası olan ad mailto bağlantısı, e-postasız ad düz metin (sütun + telefon dl'si aynı içeriği taşır)
    const poCell = rowA.querySelector('[data-slot="wracc-po"]')
    const link = poCell.querySelector('a[href="mailto:b@example.com"]')
    expect(link).not.toBeNull()
    expect(link.textContent).toBe('Kişi B')
    expect(link.getAttribute('aria-label')).toMatch(/Kişi B — (e-posta gönder|send email)/)
    expect(poCell.textContent).toContain('Kişi C')
    expect(poCell.querySelectorAll('a')).toHaveLength(1)
    expect(rowB.querySelector('[data-slot="wracc-po"]').textContent).toMatch(/PO yok|No PO/)
    // Müdür: elle atanmış → "elle"/"manual" rozeti + mailto; türetilmiş → "AD" rozeti, e-postasız düz ad
    const mgrA = rowA.querySelector('[data-slot="wracc-manager"]')
    expect(mgrA.querySelector('a[href="mailto:e@example.com"]').textContent).toBe('Elle E')
    expect(mgrA.querySelector('[data-slot="wracc-manager-source"]').getAttribute('data-manual')).toBe('true')
    expect(mgrA.querySelector('[data-slot="wracc-manager-source"]').textContent).toMatch(/^(elle|manual)$/)
    const mgrB = rowB.querySelector('[data-slot="wracc-manager"]')
    expect(mgrB.textContent).toContain('Müdür M')
    expect(mgrB.querySelector('a')).toBeNull()
    expect(mgrB.querySelector('[data-slot="wracc-manager-source"]').textContent).toBe('AD')
    // Son rapor: ISO hafta + durum rozeti + onay zamanı; hiç rapor yoksa "Hiç gönderilmedi"
    const lastA = rowA.querySelector('[data-slot="wracc-last"]')
    expect(lastA.textContent).toContain('2026-W39')
    expect(lastA.querySelector('[data-status="APPROVED"]').textContent).toMatch(/Onaylandı|Approved/)
    expect(lastA.textContent).toMatch(/(Onay|Approved): 2026-09-26T08:00:00/)
    expect(rowB.querySelector('[data-slot="wracc-last"]').textContent).toMatch(/Hiç gönderilmedi|Never submitted/)
    // Telefon yerleşimi: aynı üç bilgi takım hücresinin altında etiket:değer olarak (md'de gizli, jsdom'da DOM'da)
    const dl = rowA.querySelector('[data-slot="wracc-mobile"]')
    expect(dl.querySelectorAll('dt')).toHaveLength(3)
    expect(dl.textContent).toContain('Kişi B')
    expect(dl.textContent).toContain('2026-W39')
  })

  it('arama takımı süzer; pasif takım işaretlenir; uç hatasında toast.error', async () => {
    api.weeklyReports.setAccess.mockResolvedValue({ success: false, error: 'yetkisiz' })
    wrap()
    await screen.findByText('Takım A')
    expect(screen.getByText(/pasif takım|inactive team/)).toBeInTheDocument()
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'takım b' } })
    expect(screen.queryByText('Takım A')).toBeNull()
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'yok böyle' } })
    expect(screen.getByText(/Eşleşen takım yok|No team matches/)).toBeInTheDocument()
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '' } })
    fireEvent.click(screen.getByRole('switch', { name: /Takım A/ }))
    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith('yetkisiz'))
  })
})
