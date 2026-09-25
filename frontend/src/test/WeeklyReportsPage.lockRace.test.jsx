import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'

/**
 * R11 (2026-09-25) — rapor AÇILIŞ yarışı düzenleme kilidini sızdırıyordu.
 *
 * Rapor yüklenirken kullanıcı listeye dönerse lockHeld henüz false olduğu için backToList kilidi
 * bırakmıyordu; geç gelen yanıt raporu state'e yazıp kilidi alıyor, 45 sn'lik kalp atışı da kullanıcı
 * liste ekranındayken kilidi tazeliyordu (diğer editörler "X düzenliyor" görüyordu). Sözleşme:
 *  - bayat `get` yanıtı kilit İSTEMEZ,
 *  - bayat yükleme sırasında alınmış kilit BIRAKILIR (listeye dönüş ya da sayfanın sökülmesi).
 *
 * Mock düzeni WeeklyReportsPage.approval.test.jsx ile aynı: hedef görsel değil AKIŞ.
 */
const confirmMock = vi.fn(() => Promise.resolve(true))
const toastMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }

vi.mock('@uiw/react-md-editor', () => ({
  default: ({ value }) => <textarea readOnly value={value ?? ''} />,
  commands: { bold: {}, italic: {}, group: () => ({}) },
}))
vi.mock('react-markdown', () => ({ default: ({ children }) => <div>{children}</div> }))
vi.mock('remark-gfm', () => ({ default: () => {} }))
vi.mock('../components/WeeklyKpiStrip.jsx', () => ({ default: () => <div data-testid="kpi" /> }))
vi.mock('../components/WeeklySummaryBrief.jsx', () => ({ default: () => <div data-testid="brief" /> }))
vi.mock('../components/WeeklyMonitoringStrip.jsx', () => ({ default: () => <div data-testid="mon" /> }))
vi.mock('../components/ui/Dialog.jsx', () => ({
  useDialog: () => ({ showConfirm: confirmMock }),
  DialogProvider: ({ children }) => children,
}))
vi.mock('../components/ui/Toast.jsx', () => ({
  useToast: () => toastMock,
  ToastProvider: ({ children }) => children,
}))

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  api: withApiFallback({
    weeklyReports: {
      years: vi.fn(), list: vi.fn(), get: vi.fn(), lock: vi.fn(), unlock: vi.fn(),
      save: vi.fn(), submit: vi.fn(), approve: vi.fn(), reject: vi.fn(), reopen: vi.fn(),
      resend: vi.fn(), remove: vi.fn(), kpis: vi.fn(), monitoringStats: vi.fn(),
      mails: vi.fn(), uploadImage: vi.fn(), transfer: vi.fn(), deadline: vi.fn(),
    },
    admin: { getTeams: vi.fn() },
  }),
}))

import { api } from '../api/client'
import WeeklyReportsPage from '../components/WeeklyReportsPage.jsx'

const REPORT = {
  id: 10, team_id: 5, team_name: 'Takım A', report_year: 2026, week_no: 32,
  week_label: '2026-W32', status: 'DRAFT', version: 3,
  content_json: '{}', updated_at: '2026-08-10T10:00:00',
}
const GET_OK = { success: true, data: { report: REPORT, images: [] } }

const renderPage = () =>
  render(<LangProvider><WeeklyReportsPage systemRole="ADMIN" teamId={5} teamName="Takım A" /></LangProvider>)

/** Liste satırına tıklar ve detay isteğinin çıktığını bekler (yanıt test tarafından tutulur). */
async function clickFirstRow() {
  await waitFor(() => expect(api.weeklyReports.list).toHaveBeenCalled())
  const row = await waitFor(() => {
    const tr = document.querySelector('tbody tr')
    if (!tr) throw new Error('rapor satırı henüz yok')
    return tr
  })
  fireEvent.click(row)
  await waitFor(() => expect(api.weeklyReports.get).toHaveBeenCalledWith(10))
}

const backToList = () => screen.getByRole('button', { name: /^Listeye Dön$|^Back to List$/ })
const flush = () => act(() => new Promise((r) => setTimeout(r, 0)))

describe('WeeklyReportsPage — açılış yarışı kilidi sızdırmaz (R11)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    confirmMock.mockResolvedValue(true)
    api.weeklyReports.years.mockResolvedValue({ success: true, data: [2026] })
    api.weeklyReports.list.mockResolvedValue({ success: true, data: [REPORT] })
    api.weeklyReports.get.mockResolvedValue(GET_OK)
    api.weeklyReports.lock.mockResolvedValue({ success: true, data: { acquired: true } })
    api.weeklyReports.unlock.mockResolvedValue({ success: true })
    api.weeklyReports.kpis.mockResolvedValue({ success: true, data: null })
    api.weeklyReports.monitoringStats.mockResolvedValue({ success: true, data: null })
    api.weeklyReports.mails.mockResolvedValue({ success: true, data: [] })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'Takım A' }] })
  })

  it('rapor YÜKLENİRKEN listeye dönülürse geç gelen yanıt kilit İSTEMEZ ve raporu açmaz', async () => {
    let releaseGet
    api.weeklyReports.get.mockImplementationOnce(() => new Promise((r) => { releaseGet = () => r(GET_OK) }))
    renderPage()
    await clickFirstRow()

    fireEvent.click(backToList())            // yanıt gelmeden listeye dön
    await waitFor(() => expect(document.querySelector('tbody tr')).not.toBeNull())
    await act(async () => { releaseGet() })
    await flush()

    expect(api.weeklyReports.lock).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: /^Listeye Dön$|^Back to List$/ })).toBeNull()   // hâlâ liste ekranı
  })

  it('kilit isteği UÇUŞTAYKEN listeye dönülürse, sonradan alınan kilit BIRAKILIR', async () => {
    let releaseLock
    api.weeklyReports.lock.mockImplementationOnce(() => new Promise((r) => { releaseLock = () => r({ success: true, data: { acquired: true } }) }))
    renderPage()
    await clickFirstRow()
    await waitFor(() => expect(api.weeklyReports.lock).toHaveBeenCalledWith(10))

    fireEvent.click(backToList())            // lockHeld henüz false → backToList bırakamaz
    await flush()
    expect(api.weeklyReports.unlock).not.toHaveBeenCalled()
    await act(async () => { releaseLock() })
    await flush()

    expect(api.weeklyReports.unlock).toHaveBeenCalledWith(10)
  })

  it('kilit isteği uçuştayken sayfa SÖKÜLÜRSE de alınan kilit bırakılır', async () => {
    let releaseLock
    api.weeklyReports.lock.mockImplementationOnce(() => new Promise((r) => { releaseLock = () => r({ success: true, data: { acquired: true } }) }))
    const { unmount } = renderPage()
    await clickFirstRow()
    await waitFor(() => expect(api.weeklyReports.lock).toHaveBeenCalledWith(10))

    unmount()
    await act(async () => { releaseLock() })
    await flush()

    expect(api.weeklyReports.unlock).toHaveBeenCalledWith(10)
  })

  it('normal açılış: kilit alınır ve ALINMIŞKEN bırakılmaz (koruma mutlu yolu bozmaz)', async () => {
    renderPage()
    await clickFirstRow()
    await waitFor(() => expect(api.weeklyReports.lock).toHaveBeenCalledWith(10))
    await flush()
    expect(backToList()).toBeInTheDocument()
    expect(api.weeklyReports.unlock).not.toHaveBeenCalled()

    fireEvent.click(backToList())            // kilit bizde → listeye dönüş bırakır (mevcut davranış)
    await waitFor(() => expect(api.weeklyReports.unlock).toHaveBeenCalledWith(10))
  })
})
