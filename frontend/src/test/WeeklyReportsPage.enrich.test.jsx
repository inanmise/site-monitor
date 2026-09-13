import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'

/**
 * Haftalık Raporlar zenginleştirmesi (2026-09-13): bu-hafta şeridi, durum çipleri + onay kuyruğu, listeden
 * onay/iade, skor sütunu + sıralama, URL derin bağlantı (w_*), sistemden öneri "Uygula", Δ rozetleri,
 * geçen haftanın notu, komşu hafta gezintisi, "geçen haftadan devam" ile oluşturma, CSV.
 * Onay akışı testiyle aynı mock kabuğu (editör/markdown/şeritler mock).
 */
const confirmMock = vi.fn(() => Promise.resolve(true))
const toastMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
vi.mock('@uiw/react-md-editor', () => ({ default: ({ value }) => <textarea readOnly value={value ?? ''} />, commands: { bold: {}, italic: {}, group: () => ({}) } }))
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
    weeklyReports: {
      years: vi.fn(), list: vi.fn(), get: vi.fn(), lock: vi.fn(), unlock: vi.fn(), save: vi.fn(), submit: vi.fn(),
      approve: vi.fn(), reject: vi.fn(), reopen: vi.fn(), resend: vi.fn(), remove: vi.fn(), kpis: vi.fn(), monitoringStats: vi.fn(),
      mails: vi.fn(), uploadImage: vi.fn(), transfer: vi.fn(), deadline: vi.fn(), thisWeek: vi.fn(), suggestions: vi.fn(), previous: vi.fn(), create: vi.fn(),
    },
    admin: { getTeams: vi.fn() },
  }),
}))
import { api } from '../api/client'
import WeeklyReportsPage from '../components/WeeklyReportsPage.jsx'

const base = (o) => ({ team_id: 5, report_year: 2026, week_no: 37, week_label: '2026-W37', version: 1, content_json: '{}', updated_at: '2026-09-10T10:00:00', created_by: 'Ekip Üyesi', ...o })
const PENDING = base({ id: 10, status: 'PENDING_APPROVAL', score: 84, submitted_by: 'Ekip Üyesi' })
const DRAFT = base({ id: 11, status: 'DRAFT', week_no: 36, week_label: '2026-W36', content_json: JSON.stringify({ version: 1, item1: { total: 3, urgent: 1, high: 2, medium: 0, low: 0, notes_md: '' }, item2: { open_incidents: 2, problem_records: 0, postmortems: 0, notes_md: '' }, item3: { notes_md: '' }, item4: { channels: [] } }) })
const SENT = base({ id: 12, status: 'APPROVED', sent_at: '2026-09-01T10:00:00', week_no: 35, week_label: '2026-W35', score: 61, reject_note: true })
const THIS_WEEK = { year: 2026, week: 37, week_label: '2026-W37', due_at: '2099-01-01T12:00:00', is_past: false, deadline_day: 'FRI', deadline_time: '15:00',
  teams: [{ team_id: 5, team_name: 'SY-A', status: 'PENDING_APPROVAL', report_id: 10 }, { team_id: 6, team_name: 'SY-B', status: 'MISSING', report_id: null }] }

const renderPage = (role = 'ADMIN') => render(<LangProvider><WeeklyReportsPage systemRole={role} teamId={5} teamName="SY-A" /></LangProvider>)

describe('WeeklyReportsPage — zenginleştirme (2026-09-13)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    confirmMock.mockResolvedValue(true)
    window.history.replaceState({}, '', '/?tab=weeklyreports')
    api.weeklyReports.years.mockResolvedValue({ success: true, data: [2026] })
    api.weeklyReports.list.mockResolvedValue({ success: true, data: [PENDING, DRAFT, SENT] })
    api.weeklyReports.thisWeek.mockResolvedValue({ success: true, data: THIS_WEEK })
    api.weeklyReports.get.mockImplementation(async (id) => { const r = [PENDING, DRAFT, SENT].find((x) => x.id === id); return r ? { success: true, data: { report: r, images: [] } } : { success: false, error: 'yok' } })
    api.weeklyReports.lock.mockResolvedValue({ success: true, data: { acquired: true } })
    api.weeklyReports.unlock.mockResolvedValue({ success: true })
    api.weeklyReports.kpis.mockResolvedValue({ success: true, data: null })
    api.weeklyReports.monitoringStats.mockResolvedValue({ success: true, data: null })
    api.weeklyReports.deadline.mockResolvedValue({ success: true, data: { day: 'FRI', time: '15:00', day_tr: 'Cuma', day_en: 'Friday' } })
    api.weeklyReports.approve.mockResolvedValue({ success: true })
    api.weeklyReports.reject.mockResolvedValue({ success: true })
    api.weeklyReports.suggestions.mockResolvedValue({ success: true, data: { open_incidents: 4, alarms_opened: 3, critical_certs: 0 } })
    api.weeklyReports.previous.mockResolvedValue({ success: true, data: { ...SENT, content_json: JSON.stringify({ item1: { total: 1, urgent: 0, high: 1, medium: 0, low: 0, notes_md: 'Geçen haftanın notu' }, item2: { open_incidents: 5, problem_records: 0, postmortems: 0, notes_md: '' }, item3: { notes_md: '' }, item4: { channels: [] } }) } })
    api.weeklyReports.create.mockResolvedValue({ success: true, data: { id: 99 } })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }, { id: 6, name: 'SY-B' }] })
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('"Bu hafta" şeridi: takım durumları, rapor yoksa Oluştur → modal o hafta ile; geri sayım metni', async () => {
    renderPage()
    const strip = await screen.findByLabelText(/Bu hafta|This week/)
    // Varsayılan KAPALI: başlıkta özet (1 takım eksik), açınca takım satırları
    expect(strip.textContent).toMatch(/1 takımın raporu eksik|1 teams have no report/)
    fireEvent.click(strip.querySelector('.wr-tw-head'))
    expect(strip.textContent).toMatch(/SY-A/); expect(strip.textContent).toMatch(/SY-B/)
    expect(strip.textContent).toMatch(/Son giriş|Deadline/)
    fireEvent.click(within(strip).getByText(/Oluştur|Create/))
    const modal = document.querySelector('.modal-box')
    expect(modal).not.toBeNull()
    expect(modal.querySelector('input[type=number][min]').value).toBe('37')
    // Geçen haftadan devam → create carry_notes:true
    fireEvent.click(within(modal).getByLabelText(/Geçen haftanın notlarıyla|last week's notes/))
    fireEvent.click(within(modal).getByText(/^Oluştur$|^Create$/))
    await waitFor(() => expect(api.weeklyReports.create).toHaveBeenCalledWith(expect.objectContaining({ team_id: 6, year: 2026, week_no: 37, carry_notes: true })))
  })

  it('durum çipleri facet sayaçlı; "Onayımı bekleyenler" yalnız PENDING satırı bırakır; URL w_st taşır', async () => {
    renderPage()
    await screen.findByLabelText(/Bu hafta|This week/)
    await waitFor(() => expect(document.querySelectorAll('.wr-table tbody tr')).toHaveLength(3))
    const chips = document.querySelector('.wr-chips')
    expect(chips.textContent).toMatch(/\(3\)/)
    fireEvent.click(within(chips).getByText(/Onayımı bekleyenler|Awaiting my approval/))
    await waitFor(() => expect(document.querySelectorAll('.wr-table tbody tr')).toHaveLength(1))
    await waitFor(() => expect(window.location.search).toContain('w_st=MINE'))
  })

  it('skor sütunu + başlıktan skor sıralaması; listeden Onayla onay diyaloğu ister ve approve(id) çağırır; İade Et modalı id ile', async () => {
    renderPage()
    await waitFor(() => expect(document.querySelectorAll('.wr-table tbody tr')).toHaveLength(3))
    expect([...document.querySelectorAll('.wr-score')].map((s) => s.textContent)).toEqual(['84', '—', '61'])
    fireEvent.click(within(document.querySelector('.wr-table thead')).getByRole('button', { name: /Skor|Score/ }))
    await waitFor(() => expect([...document.querySelectorAll('.wr-score')].map((s) => s.textContent)).toEqual(['84', '61', '—']))
    // kebab → Onayla
    const row = document.querySelector('.wr-table tbody tr')
    fireEvent.click(within(row).getByLabelText(/İşlemler|Actions/))
    fireEvent.click(screen.getByText(/^Onayla$|^Approve$/))
    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    await waitFor(() => expect(api.weeklyReports.approve).toHaveBeenCalledWith(10))
    // kebab → İade Et → not → gönder
    fireEvent.click(within(document.querySelector('.wr-table tbody tr')).getByLabelText(/İşlemler|Actions/))
    fireEvent.click(screen.getByText(/^İade Et$|^Return$|^Reject$/))
    fireEvent.change(document.querySelector('.modal-box textarea'), { target: { value: 'eksik' } })
    fireEvent.click(within(document.querySelector('.modal-box')).getByText(/^İade Et$|^Return$|^Reject$/))
    await waitFor(() => expect(api.weeklyReports.reject).toHaveBeenCalledWith(10, 'eksik'))
  })

  it('URL derin bağlantı: w_id açık raporu mount\'ta yükler; detayda Δ rozetleri, öneri "Uygula", geçen haftanın notu, komşu hafta', async () => {
    window.history.replaceState({}, '', '/?tab=weeklyreports&w_id=11')
    renderPage()
    await waitFor(() => expect(api.weeklyReports.get).toHaveBeenCalledWith(11))
    await screen.findByText('2026-W36')
    await waitFor(() => expect(api.weeklyReports.previous).toHaveBeenCalledWith(11))
    // Δ: item1 total 3 vs 1 → ▲ 2 (kötü), item2 open 2 vs 5 → ▼ 3 (iyi)
    await waitFor(() => expect(document.querySelectorAll('.wr-delta').length).toBeGreaterThan(0))
    const deltas = [...document.querySelectorAll('.wr-delta')].map((d) => d.textContent.trim())
    expect(deltas).toEqual(expect.arrayContaining(['▲ 2', '▼ 3']))
    // öneri: sistemde 4 açık olay, elle 2 → Uygula
    const sug = await screen.findByText(/Uygula|Apply/)
    fireEvent.click(sug)
    await waitFor(() => expect(document.querySelector('.wr-suggest.is-same')).not.toBeNull())
    // geçen haftanın notu
    fireEvent.click(screen.getByText(/Geçen haftanın notunu göster|Show last week's note/))
    expect(screen.getByText('Geçen haftanın notu')).toBeInTheDocument()
    // komşu hafta: ◀ önceki → previous id (12)
    fireEvent.click(screen.getByLabelText(/Önceki hafta|Previous week/))
    await waitFor(() => expect(api.weeklyReports.get).toHaveBeenCalledWith(12))
  })

  it('AUDIT: şeritte Oluştur yok, çubukta onay kuyruğu çipi yok', async () => {
    renderPage('AUDIT')
    const strip = await screen.findByLabelText(/Bu hafta|This week/)
    fireEvent.click(strip.querySelector('.wr-tw-head'))
    expect(within(strip).queryByText(/Oluştur|Create/)).toBeNull()
    await waitFor(() => expect(document.querySelector('.wr-chips')).not.toBeNull())
    expect(document.querySelector('.wr-chip-mine')).toBeNull()
  })

  it('ikinci tur: listede yorum rozeti; detayda yorum paneli + "Şablondan tamamla" takım şablonundaki eksik kanalları ekler; hatırlatma durumu satırı', async () => {
    api.weeklyReports.list.mockResolvedValue({ success: true, data: [{ ...PENDING, comment_count: 3 }, DRAFT, SENT] })
    api.weeklyReports.remindersStatus.mockResolvedValue({ success: true, data: { enabled: true, next_run_at: '2026-09-18T06:00:00', will_send: 1, already_done: 0, no_email: 0, opt_in_teams: 1 } })
    api.weeklyReports.comments.mockResolvedValue({ success: true, data: [{ id: 1, kind: 'SUBMIT', author: 'Ekip Üyesi', created_at: '2026-09-10T10:00:00' }] })
    api.weeklyReports.get.mockImplementation(async (id) => id === 11
      ? { success: true, data: { report: DRAFT, images: [], team_channels: ['Web Kanalı', 'Mobil'] } }
      : { success: true, data: { report: PENDING, images: [] } })
    renderPage()
    await waitFor(() => expect(document.querySelector('.wr-cm-badge')).not.toBeNull())
    expect(document.querySelector('.wr-cm-badge').textContent).toBe('3')
    const rem = await screen.findByRole('note')
    expect(rem.textContent).toMatch(/1 takıma gidecek|going to 1 teams/)
    // detay: DRAFT (11) düzenlenebilir → şablon düğmesi (2 eksik), tıklayınca 2 kanal sekmesi
    const rows = document.querySelectorAll('.wr-table tbody tr')   // hafta desc: 37, 36, 35
    fireEvent.click(rows[1])
    await waitFor(() => expect(api.weeklyReports.get).toHaveBeenCalledWith(11))
    await screen.findByText('2026-W36')
    const fill = await screen.findByText(/Şablondan tamamla \(2\)|Fill in from template \(2\)/)
    fireEvent.click(fill)
    await waitFor(() => expect(screen.getByText('Mobil')).toBeInTheDocument())
    expect(screen.queryByText(/Şablondan tamamla|Fill in from template/)).toBeNull()
    // yorum paneli: sistem satırı listelenir, form var (ADMIN yazabilir)
    await waitFor(() => expect(api.weeklyReports.comments).toHaveBeenCalledWith(11))
    await screen.findByText(/Onaya gönderildi|Submitted for approval/)
    expect(document.querySelector('.wr-cm-input')).not.toBeNull()
  })
})
