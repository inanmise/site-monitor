import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'

/**
 * 2026-09-27 yayın öncesi regresyon taraması — Haftalık Raporlar yarışları (release-fixes.md):
 *  ORTA  save() yanıtı açık olan HERHANGİ bir rapora yazılıyordu → B, A'nın sürümünü alıyor, sonraki kayıt sahte
 *        VERSION_CONFLICT; ◀/▶ kaydedilmemiş değişiklik onayını ve kilit bırakmayı atlıyordu.
 *  DÜŞÜK loadList / loadYears sıra korumasızdı → geç gelen eski yanıt yeni süzgecin üstüne yazılıyordu.
 * Denetimli promise'lerle belirlenimci; mock kabuğu diğer sayfa testleriyle aynı.
 */
const confirmMock = vi.fn(() => Promise.resolve(true))
const toastMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
vi.mock('@uiw/react-md-editor/nohighlight', () => ({ default: ({ value }) => <textarea readOnly value={value ?? ''} />, commands: { bold: {}, italic: {}, group: () => ({}) } }))
vi.mock('react-markdown', () => ({ default: ({ children }) => <div>{children}</div> }))
vi.mock('remark-gfm', () => ({ default: () => {} }))
vi.mock('../components/WeeklyKpiStrip.jsx', () => ({ default: () => <div data-testid="kpi" /> }))
vi.mock('../components/WeeklySummaryBrief.jsx', () => ({ default: () => <div data-testid="brief" /> }))
vi.mock('../components/WeeklyMonitoringStrip.jsx', () => ({ default: () => <div data-testid="mon" /> }))
vi.mock('../components/WeeklyCompletionBoard.jsx', () => ({ default: () => <div data-testid="board" /> }))
vi.mock('../components/ui/Dialog.jsx', () => ({ useDialog: () => ({ showConfirm: confirmMock }), DialogProvider: ({ children }) => children }))
vi.mock('../components/ui/Toast.jsx', () => ({ useToast: () => toastMock, ToastProvider: ({ children }) => children }))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  api: withApiFallback({
    weeklyReports: { years: vi.fn(), list: vi.fn(), get: vi.fn(), lock: vi.fn(), unlock: vi.fn(), save: vi.fn(), previous: vi.fn(), thisWeek: vi.fn() },
    admin: { getTeams: vi.fn() },
  }),
}))
import { api } from '../api/client'
import WeeklyReportsPage from '../components/WeeklyReportsPage.jsx'

const content = JSON.stringify({ version: 1, item1: { total: 1, urgent: 1, status_counts: { working: 1 } }, item2: {}, item3: { notes_md: '' }, item4: { channels: [] } })
const base = (o) => ({ team_id: 5, report_year: 2026, status: 'DRAFT', content_json: content, updated_at: '2026-09-10T10:00:00', ...o })
const A = base({ id: 11, week_no: 36, week_label: '2026-W36', version: 4 })
const B = base({ id: 15, week_no: 37, week_label: '2026-W37', version: 7 })
const ALL = [A, B]
const deferred = () => { let resolve; const p = new Promise((r) => { resolve = r }); return { p, resolve } }
const renderPage = (role = 'ADMIN') => render(<LangProvider><WeeklyReportsPage systemRole={role} teamId={5} teamName="Takım A" /></LangProvider>)
const flush = () => act(() => new Promise((r) => setTimeout(r, 0)))

describe('WeeklyReportsPage — yarışlar (2026-09-27 regresyon taraması)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    try { localStorage.clear() } catch { /* yoksay */ }
    confirmMock.mockResolvedValue(true)
    window.history.replaceState({}, '', '/?tab=weeklyreports')
    api.weeklyReports.years.mockResolvedValue({ success: true, data: [2026] })
    api.weeklyReports.list.mockResolvedValue({ success: true, data: ALL })
    api.weeklyReports.get.mockImplementation(async (id) => ({ success: true, data: { report: ALL.find((r) => r.id === id), images: [] } }))
    api.weeklyReports.lock.mockResolvedValue({ success: true, data: { acquired: true } })
    api.weeklyReports.unlock.mockResolvedValue({ success: true })
    api.weeklyReports.previous.mockResolvedValue({ success: true, data: null })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'Takım A' }, { id: 6, name: 'Takım B' }] })
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('A kaydederken B açılırsa A\'nın yanıtı B\'ye yazılmaz: B\'nin kaydı KENDİ sürümüyle gider (sahte VERSION_CONFLICT yok)', async () => {
    window.history.replaceState({}, '', '/?tab=weeklyreports&w_id=11')
    renderPage()
    await screen.findByText('2026-W36')
    const saveA = deferred()
    api.weeklyReports.save.mockImplementationOnce(() => saveA.p)
    fireEvent.change(screen.getByRole('textbox', { name: /^(Acil|Urgent)$/ }), { target: { value: '3' } })
    fireEvent.click(screen.getByRole('button', { name: /Taslağı kaydet|Save draft/ }))
    await waitFor(() => expect(api.weeklyReports.save).toHaveBeenCalledWith(11, expect.any(String), 4))
    // Kayıt sürerken listeye dön (kirli → onay) ve B'yi aç
    fireEvent.click(screen.getByRole('button', { name: /^(Listeye Dön|Back to List)$/ }))
    await waitFor(() => expect(document.querySelectorAll('[data-testid="wr-table"] tbody tr').length).toBe(2))
    fireEvent.click(document.querySelectorAll('[data-testid="wr-table"] tbody tr')[0])   // hafta desc: 37 (B)
    await screen.findByText('2026-W37')
    // A'nın kaydı ŞİMDİ döner — yeni sürüm 9
    await act(async () => { saveA.resolve({ success: true, data: { status: 'DRAFT', version: 9 } }) })
    await flush()
    api.weeklyReports.save.mockResolvedValue({ success: true, data: { status: 'DRAFT', version: 8 } })
    fireEvent.change(screen.getByRole('textbox', { name: /^(Acil|Urgent)$/ }), { target: { value: '5' } })
    fireEvent.click(screen.getByRole('button', { name: /Taslağı kaydet|Save draft/ }))
    await waitFor(() => expect(api.weeklyReports.save).toHaveBeenLastCalledWith(15, expect.any(String), 7))
  })

  it('◀/▶: kaydedilmemiş değişiklik ONAY ister (vazgeçilirse kalır); onaylanınca A\'nın kilidi bırakılır ve B açılır', async () => {
    window.history.replaceState({}, '', '/?tab=weeklyreports&w_id=11')
    renderPage()
    await screen.findByText('2026-W36')
    await waitFor(() => expect(api.weeklyReports.lock).toHaveBeenCalledWith(11))
    fireEvent.change(screen.getByRole('textbox', { name: /^(Acil|Urgent)$/ }), { target: { value: '2' } })
    confirmMock.mockResolvedValueOnce(false)
    fireEvent.click(screen.getByRole('button', { name: /^(Sonraki hafta|Next week)$/ }))
    await waitFor(() => expect(confirmMock).toHaveBeenCalledTimes(1))
    await flush()
    expect(api.weeklyReports.get).not.toHaveBeenCalledWith(15)
    expect(api.weeklyReports.unlock).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: /^(Sonraki hafta|Next week)$/ }))
    await waitFor(() => expect(api.weeklyReports.unlock).toHaveBeenCalledWith(11))
    await waitFor(() => expect(api.weeklyReports.get).toHaveBeenCalledWith(15))
    await screen.findByText('2026-W37')
  })

  it('kayıt sürerken ◀/▶ kapalı', async () => {
    window.history.replaceState({}, '', '/?tab=weeklyreports&w_id=11')
    renderPage()
    await screen.findByText('2026-W36')
    const pending = deferred()
    api.weeklyReports.save.mockImplementationOnce(() => pending.p)
    fireEvent.change(screen.getByRole('textbox', { name: /^(Acil|Urgent)$/ }), { target: { value: '3' } })
    fireEvent.click(screen.getByRole('button', { name: /Taslağı kaydet|Save draft/ }))
    await waitFor(() => expect(screen.getByRole('button', { name: /^(Sonraki hafta|Next week)$/ })).toBeDisabled())
    await act(async () => { pending.resolve({ success: true, data: { status: 'DRAFT', version: 5 } }) })
    await waitFor(() => expect(screen.getByRole('button', { name: /^(Sonraki hafta|Next week)$/ })).toBeEnabled())
  })

  it('liste: geç gelen ESKİ yanıt yeni yanıtın üstüne yazılmaz (sıra koruması)', async () => {
    renderPage()
    await waitFor(() => expect(document.querySelectorAll('[data-testid="wr-table"] tbody tr').length).toBe(2))
    const older = deferred(), newer = deferred()
    api.weeklyReports.list.mockImplementationOnce(() => older.p).mockImplementationOnce(() => newer.p)
    const refresh = screen.getByRole('button', { name: /^(Yenile|Refresh)$/ })
    fireEvent.click(refresh)   // eski istek
    fireEvent.click(refresh)   // yeni istek
    await act(async () => { newer.resolve({ success: true, data: [B] }) })
    await waitFor(() => expect(document.querySelectorAll('[data-testid="wr-table"] tbody tr').length).toBe(1))
    await act(async () => { older.resolve({ success: true, data: ALL }) })
    await flush()
    expect(document.querySelectorAll('[data-testid="wr-table"] tbody tr').length).toBe(1)
  })

  it('yıl listesi: takım değişince geç gelen eski takımın yılları yenisinin üstüne yazılmaz', async () => {
    const first = deferred(), second = deferred()
    api.weeklyReports.years.mockImplementationOnce(() => first.p).mockImplementationOnce(() => second.p)
    renderPage()
    await waitFor(() => expect(api.weeklyReports.years).toHaveBeenCalledTimes(1))
    // Takım seçici (SearchableSelect — mousedown sözleşmesi): Takım B
    const teamPicker = screen.getByRole('combobox', { name: /^(Takım|Team)$/ })
    fireEvent.mouseDown(teamPicker)
    const optB = [...document.querySelectorAll('[role="listbox"] [role="option"]')].find((o) => o.textContent.trim() === 'Takım B')
    fireEvent.mouseDown(optB)
    await waitFor(() => expect(api.weeklyReports.years).toHaveBeenCalledTimes(2))
    expect(api.weeklyReports.years).toHaveBeenLastCalledWith(6)
    await act(async () => { second.resolve({ success: true, data: [2024] }) })
    await act(async () => { first.resolve({ success: true, data: [2019] }) })
    await flush()
    fireEvent.mouseDown(screen.getByRole('combobox', { name: /^(Yıl|Year)$/ }))
    const years = [...document.querySelectorAll('[role="listbox"] [role="option"]')].map((o) => o.textContent.trim())
    expect(years).toContain('2024')
    expect(years).not.toContain('2019')
  })
})
