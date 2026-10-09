import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'

/**
 * Haftalık Raporlar — gezinti ve yeniden yükleme (2026-10-09):
 *  • Satır eylemlerinden sonraki liste tazelemesi satırları YÜKLEME BLOĞUYLA değiştiriyordu → sayfa boyu çöküyor,
 *    kaydırma zıplıyordu. Artık satırlar yerinde kalır (`aria-busy`), blok yalnız ilk yüklemede.
 *  • Özet akordeonundaki izleme göstergeleri ◀/▶ sonrası hiç yüklenmiyordu; yükleme sürerken ▶'de "…"da takılıyordu.
 *  • ◀/▶ yıl sınırında her ISO yılını 53 hafta sayıyordu (2026-W01 ◀ → olmayan 2025-W53).
 *  • A raporunda yazılan yorum taslağı B'ye taşınıp orada gönderilebiliyordu.
 * Mock kabuğu yarış testleriyle (WeeklyReportsPage.races) aynı; izleme şeridi aldığı veriyi/yüklemeyi yazar.
 */
const confirmMock = vi.fn(() => Promise.resolve(true))
const toastMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
vi.mock('@uiw/react-md-editor', () => ({ default: ({ value }) => <textarea readOnly value={value ?? ''} />, commands: { bold: {}, italic: {}, group: () => ({}) } }))
vi.mock('react-markdown', () => ({ default: ({ children }) => <div>{children}</div> }))
vi.mock('remark-gfm', () => ({ default: () => {} }))
vi.mock('../components/WeeklyKpiStrip.jsx', () => ({ default: () => <div data-testid="kpi" /> }))
vi.mock('../components/WeeklySummaryBrief.jsx', () => ({ default: () => <div data-testid="brief" /> }))
vi.mock('../components/WeeklyMonitoringStrip.jsx', () => ({
  default: ({ stats, loading }) => <div data-testid="mon" data-loading={loading ? 'true' : 'false'}>{stats?.tag ?? 'none'}</div>,
}))
vi.mock('../components/WeeklyCompletionBoard.jsx', () => ({ default: () => <div data-testid="board" /> }))
vi.mock('../components/ui/Dialog.jsx', () => ({ useDialog: () => ({ showConfirm: confirmMock }), DialogProvider: ({ children }) => children }))
vi.mock('../components/ui/Toast.jsx', () => ({ useToast: () => toastMock, ToastProvider: ({ children }) => children }))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  api: withApiFallback({
    weeklyReports: {
      years: vi.fn(), list: vi.fn(), get: vi.fn(), lock: vi.fn(), unlock: vi.fn(), save: vi.fn(), previous: vi.fn(), thisWeek: vi.fn(),
      monitoringStats: vi.fn(), comments: vi.fn(), addComment: vi.fn(),
    },
    admin: { getTeams: vi.fn() },
  }),
}))
import { api } from '../api/client'
import WeeklyReportsPage from '../components/WeeklyReportsPage.jsx'
import WeeklyComments from '../components/weekly/WeeklyComments.jsx'

const content = JSON.stringify({ version: 1, item1: { total: 1, urgent: 1, status_counts: { working: 1 } }, item2: {}, item3: { notes_md: '' }, item4: { channels: [] } })
const base = (o) => ({ team_id: 5, report_year: 2026, status: 'DRAFT', content_json: content, updated_at: '2026-09-10T10:00:00', version: 1, ...o })
const A = base({ id: 11, week_no: 36, week_label: '2026-W36' })
const B = base({ id: 15, week_no: 37, week_label: '2026-W37' })
let ALL = [A, B]
const deferred = () => { let resolve; const p = new Promise((r) => { resolve = r }); return { p, resolve } }
const renderPage = (role = 'ADMIN') => render(<LangProvider><WeeklyReportsPage systemRole={role} teamId={5} teamName="Takım A" /></LangProvider>)
const flush = () => act(() => new Promise((r) => setTimeout(r, 0)))
const rows = () => document.querySelectorAll('[data-testid="wr-table"] tbody tr')
const nextWeek = () => screen.getByRole('button', { name: /^(Sonraki hafta|Next week)$/ })
const prevWeek = () => screen.getByRole('button', { name: /^(Önceki hafta|Previous week)$/ })
const summary = () => screen.getByRole('button', { name: /Haftalık Özet|Weekly Summary/ })
const mon = () => screen.getByTestId('mon')
const listLoading = () => document.querySelector('section[aria-labelledby="wr-list-title"] [data-slot="loading-block"]')

describe('WeeklyReportsPage — gezinti ve yeniden yükleme (2026-10-09)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    try { localStorage.clear() } catch { /* yoksay */ }
    ALL = [A, B]
    confirmMock.mockResolvedValue(true)
    window.history.replaceState({}, '', '/?tab=weeklyreports')
    api.weeklyReports.years.mockResolvedValue({ success: true, data: [2026] })
    api.weeklyReports.list.mockImplementation(async () => ({ success: true, data: ALL }))
    api.weeklyReports.get.mockImplementation(async (id) => ({ success: true, data: { report: ALL.find((r) => r.id === id), images: [] } }))
    api.weeklyReports.lock.mockResolvedValue({ success: true, data: { acquired: true } })
    api.weeklyReports.unlock.mockResolvedValue({ success: true })
    api.weeklyReports.previous.mockResolvedValue({ success: true, data: null })
    api.weeklyReports.monitoringStats.mockImplementation(async (id) => ({ success: true, data: { tag: `stats-${id}` } }))
    api.weeklyReports.comments.mockResolvedValue({ success: true, data: [] })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'Takım A' }] })
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('liste tazelenirken satırlar YERİNDE kalır (aria-busy + soluk), yükleme bloğu yalnız ilk yüklemede', async () => {
    const first = deferred()
    api.weeklyReports.list.mockImplementationOnce(() => first.p)
    renderPage()
    // İlk yükleme: gösterilecek satır yok → blok
    expect(listLoading()).not.toBeNull()
    await act(async () => { first.resolve({ success: true, data: ALL }) })
    await waitFor(() => expect(rows().length).toBe(2))
    const body = document.querySelector('[data-slot="wr-list-body"]')
    expect(body).not.toHaveAttribute('aria-busy')
    // Satır eyleminden sonraki tazeleme (Yenile ile aynı yol: loadList)
    const again = deferred()
    api.weeklyReports.list.mockImplementationOnce(() => again.p)
    fireEvent.click(screen.getByRole('button', { name: /^(Yenile|Refresh)$/ }))
    await waitFor(() => expect(document.querySelector('[data-slot="wr-list-body"]')).toHaveAttribute('aria-busy', 'true'))
    expect(rows().length).toBe(2)                                             // liste çökmez
    expect(listLoading()).toBeNull()
    expect(document.querySelector('[data-slot="wr-list-body"]')).toHaveClass('opacity-60')
    await act(async () => { again.resolve({ success: true, data: [B] }) })
    await waitFor(() => expect(rows().length).toBe(1))
    expect(document.querySelector('[data-slot="wr-list-body"]')).not.toHaveAttribute('aria-busy')
  })

  it('izleme göstergeleri: A açıkken ▶ ile B\'ye geçince B\'ninkiler çekilir ve gösterilir (A\'nınki kalmaz)', async () => {
    window.history.replaceState({}, '', '/?tab=weeklyreports&w_id=11')
    renderPage()
    await screen.findByText('2026-W36')
    fireEvent.click(summary())
    await waitFor(() => expect(mon().textContent).toBe('stats-11'))
    fireEvent.click(nextWeek())
    await screen.findByText('2026-W37')
    await waitFor(() => expect(api.weeklyReports.monitoringStats).toHaveBeenCalledWith(15))
    await waitFor(() => expect(mon().textContent).toBe('stats-15'))
    expect(mon()).toHaveAttribute('data-loading', 'false')
  })

  it('izleme göstergeleri: A YÜKLENİRKEN ▶ basılırsa "…"da takılmaz — B yüklenir, A\'nın geç yanıtı yazılmaz', async () => {
    window.history.replaceState({}, '', '/?tab=weeklyreports&w_id=11')
    const slowA = deferred()
    api.weeklyReports.monitoringStats.mockImplementationOnce(() => slowA.p)
    renderPage()
    await screen.findByText('2026-W36')
    fireEvent.click(summary())
    await waitFor(() => expect(mon()).toHaveAttribute('data-loading', 'true'))
    fireEvent.click(nextWeek())
    await screen.findByText('2026-W37')
    await waitFor(() => expect(api.weeklyReports.monitoringStats).toHaveBeenCalledWith(15))
    await waitFor(() => expect(mon().textContent).toBe('stats-15'))
    expect(mon()).toHaveAttribute('data-loading', 'false')
    await act(async () => { slowA.resolve({ success: true, data: { tag: 'stats-11' } }) })
    await flush()
    expect(mon().textContent).toBe('stats-15')
    expect(mon()).toHaveAttribute('data-loading', 'false')
  })

  it('◀ yıl sınırı: 2026-W01\'den 2025-W52\'ye (52 haftalık yıl; olmayan W53\'e değil)', async () => {
    const W01 = base({ id: 21, report_year: 2026, week_no: 1, week_label: '2026-W01' })
    const W52 = base({ id: 20, report_year: 2025, week_no: 52, week_label: '2025-W52' })
    ALL = [W01, W52]
    window.history.replaceState({}, '', '/?tab=weeklyreports&w_id=21')
    renderPage()
    await screen.findByText('2026-W01')
    fireEvent.click(prevWeek())
    await waitFor(() => expect(api.weeklyReports.get).toHaveBeenCalledWith(20))
    await screen.findByText('2025-W52')
  })

  it('▶ yıl sınırı: 2027-W52\'den 2028-W01\'e (olmayan 2027-W53\'e değil)', async () => {
    const W52 = base({ id: 30, report_year: 2027, week_no: 52, week_label: '2027-W52' })
    const W01 = base({ id: 31, report_year: 2028, week_no: 1, week_label: '2028-W01' })
    ALL = [W52, W01]
    window.history.replaceState({}, '', '/?tab=weeklyreports&w_id=30')
    renderPage()
    await screen.findByText('2027-W52')
    fireEvent.click(nextWeek())
    await waitFor(() => expect(api.weeklyReports.get).toHaveBeenCalledWith(31))
    await screen.findByText('2028-W01')
  })

  it('yorum taslağı rapora bağlı: A\'da yazılan metin ▶ ile B\'ye TAŞINMAZ', async () => {
    window.history.replaceState({}, '', '/?tab=weeklyreports&w_id=11')
    renderPage()
    await screen.findByText('2026-W36')
    const input = await waitFor(() => { const el = document.querySelector('[data-slot="wr-comment-input"]'); expect(el).not.toBeNull(); return el })
    fireEvent.change(input, { target: { value: 'A için not' } })
    expect(input.value).toBe('A için not')
    fireEvent.click(nextWeek())
    await screen.findByText('2026-W37')
    await waitFor(() => expect(document.querySelector('[data-slot="wr-comment-input"]').value).toBe(''))
    expect(api.weeklyReports.addComment).not.toHaveBeenCalled()
  })
})

describe('WeeklyComments — rapor değişince taslak sıfırlanır (bileşen düzeyi)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.weeklyReports.comments.mockResolvedValue({ success: true, data: [] })
  })

  it('reportId değişince yazılan metin silinir; durum geçişi (nonce) taslağa dokunmaz', async () => {
    const wrap = (ui) => <LangProvider>{ui}</LangProvider>
    const { rerender } = render(wrap(<WeeklyComments reportId={1} canWrite nonce="DRAFT" />))
    await screen.findByText(/Henüz yorum yok|No comments yet/)
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'taslak' } })
    rerender(wrap(<WeeklyComments reportId={1} canWrite nonce="PENDING_APPROVAL" />))
    await screen.findByText(/Henüz yorum yok|No comments yet/)
    expect(screen.getByRole('textbox').value).toBe('taslak')
    rerender(wrap(<WeeklyComments reportId={2} canWrite nonce="PENDING_APPROVAL" />))
    await waitFor(() => expect(screen.getByRole('textbox').value).toBe(''))
  })

  it('gönderim sürerken rapor değişirse yanıt yeni raporun dizisine EKLENMEZ', async () => {
    const pending = deferred()
    api.weeklyReports.addComment.mockImplementationOnce(() => pending.p)
    const wrap = (ui) => <LangProvider>{ui}</LangProvider>
    const { rerender } = render(wrap(<WeeklyComments reportId={1} canWrite />))
    await screen.findByText(/Henüz yorum yok|No comments yet/)
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'A notu' } })
    fireEvent.click(screen.getByRole('button', { name: /Gönder|Send/ }))
    await waitFor(() => expect(api.weeklyReports.addComment).toHaveBeenCalledWith(1, 'A notu'))
    rerender(wrap(<WeeklyComments reportId={2} canWrite />))
    await screen.findByText(/Henüz yorum yok|No comments yet/)
    await act(async () => { pending.resolve({ success: true, data: { id: 9, kind: 'COMMENT', author: 'x', created_at: '2026-09-10T12:00:00', text: 'A notu' } }) })
    await flush()
    expect(screen.queryByText('A notu')).toBeNull()
    expect(document.querySelectorAll('[data-slot="wr-comment"]').length).toBe(0)
  })
})
