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
  { team_id: 5, team_name: 'Takım A', active: true, enabled: false, reminder: true, report_count: 3 },
  { team_id: 9, team_name: 'Takım B', active: false, enabled: false, reminder: false, report_count: 0 },
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

  it('arama takımı süzer; pasif takım işaretlenir; uç hatasında toast.error', async () => {
    api.weeklyReports.setAccess.mockResolvedValue({ success: false, error: 'yetkisiz' })
    wrap()
    await screen.findByText('Takım A')
    expect(screen.getByText(/pasif takım|inactive team/)).toBeInTheDocument()
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'takım b' } })
    expect(screen.queryByText('Takım A')).toBeNull()
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'yok böyle' } })
    expect(screen.getByText(/Eşleşen takım yok|No team matches/)).toBeInTheDocument()
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '' } })
    fireEvent.click(screen.getByRole('switch', { name: /Takım A/ }))
    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith('yetkisiz'))
  })
})
