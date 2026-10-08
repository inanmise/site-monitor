import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

/**
 * Haftalık Raporlar zenginleştirmesi (2026-09-13): bu-hafta şeridi, durum çipleri + onay kuyruğu, listeden
 * onay/iade, skor sütunu + sıralama, URL derin bağlantı (w_*), sistemden öneri "Uygula", Δ rozetleri,
 * geçen haftanın notu, komşu hafta gezintisi, "geçen haftadan devam" ile oluşturma, CSV.
 * Onay akışı testiyle aynı mock kabuğu (editör/markdown/şeritler mock).
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

  it('"Bu hafta" bölümü: takım durumları, rapor yoksa Oluştur → "Yeni Hafta Raporu" ekranı o hafta + takım ile; geri sayım metni', async () => {
    renderPage()
    const strip = await screen.findByRole('region', { name: /^(Bu hafta|This week)$/ })
    // Varsayılan KAPALI: başlıkta özet (1 takım eksik), açınca takım satırları
    expect(strip.textContent).toMatch(/1 takımın raporu eksik|Still to submit: 1/)
    fireEvent.click(strip.querySelector('[data-slot="collapsible-trigger"]'))
    expect(strip.textContent).toMatch(/SY-A/); expect(strip.textContent).toMatch(/SY-B/)
    expect(strip.textContent).toMatch(/Son giriş|Deadline/)
    fireEvent.click(within(strip).getByText(/Oluştur|Create/))
    // 2026-09-27: küçük pencere yerine yönlendirmeli ekran — seçili hafta özetinde ISO etiket + tarih aralığı
    await screen.findByRole('heading', { name: /Yeni Hafta Raporu|New Weekly Report/ })
    const week = document.querySelector('[data-slot="wr-new-week"]')
    expect(within(week).getByText('2026-W37')).toBeInTheDocument()
    await waitFor(() => expect(week.textContent).toMatch(/henüz rapor yok|No report for this week yet/))
    // Geçen haftadan devam → create carry_notes:true
    fireEvent.click(screen.getByLabelText(/Geçen haftanın notlarıyla|last week's notes/))
    const cta = screen.getByRole('button', { name: /^(Raporu oluştur|Create report)$/ })
    await waitFor(() => expect(cta).toBeEnabled())
    fireEvent.click(cta)
    await waitFor(() => expect(api.weeklyReports.create).toHaveBeenCalledWith(expect.objectContaining({ team_id: 6, year: 2026, week_no: 37, carry_notes: true })))
  })

  it('durum çipleri facet sayaçlı; "Onayımı bekleyenler" yalnız PENDING satırı bırakır; URL w_st taşır', async () => {
    renderPage()
    await screen.findByRole('region', { name: /^(Bu hafta|This week)$/ })
    await waitFor(() => expect(document.querySelectorAll('[data-testid="wr-table"] tbody tr')).toHaveLength(3))
    const chips = document.querySelector('[data-slot="wr-status-chips"]')
    expect(chips.textContent).toMatch(/\(3\)/)
    fireEvent.click(within(chips).getByText(/Onayımı bekleyenler|Awaiting my approval/))
    await waitFor(() => expect(document.querySelectorAll('[data-testid="wr-table"] tbody tr')).toHaveLength(1))
    await waitFor(() => expect(window.location.search).toContain('w_st=MINE'))
  })

  it('skor sütunu + başlıktan skor sıralaması; listeden Onayla onay diyaloğu ister ve approve(id) çağırır; İade Et modalı id ile', async () => {
    renderPage()
    await waitFor(() => expect(document.querySelectorAll('[data-testid="wr-table"] tbody tr')).toHaveLength(3))
    expect([...document.querySelectorAll('[data-slot="wr-score"]')].map((s) => s.textContent)).toEqual(['84', '—', '61'])
    fireEvent.click(within(document.querySelector('[data-testid="wr-table"] thead')).getByRole('button', { name: /Skor|Score/ }))
    await waitFor(() => expect([...document.querySelectorAll('[data-slot="wr-score"]')].map((s) => s.textContent)).toEqual(['84', '61', '—']))
    // kebab → Onayla
    const row = document.querySelector('[data-testid="wr-table"] tbody tr')
    pressMenuTrigger(within(row).getByLabelText(/İşlemler|Actions/))   // D2: KebabMenu (Radix DropdownMenu)
    fireEvent.click(screen.getByText(/^Onayla$|^Approve$/))
    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    await waitFor(() => expect(api.weeklyReports.approve).toHaveBeenCalledWith(10))
    // kebab → İade Et → not → gönder
    pressMenuTrigger(within(document.querySelector('[data-testid="wr-table"] tbody tr')).getByLabelText(/İşlemler|Actions/))
    fireEvent.click(screen.getByText(/^İade Et$|^Return$|^Reject$/))
    const dlg = await screen.findByRole('dialog')
    fireEvent.change(within(dlg).getByRole('textbox'), { target: { value: 'eksik' } })
    fireEvent.click(within(dlg).getByText(/^İade Et$|^Return$|^Reject$/))
    await waitFor(() => expect(api.weeklyReports.reject).toHaveBeenCalledWith(10, 'eksik'))
  })

  it('URL derin bağlantı: w_id açık raporu mount\'ta yükler; detayda Δ rozetleri, öneri "Uygula", geçen haftanın notu, komşu hafta', async () => {
    window.history.replaceState({}, '', '/?tab=weeklyreports&w_id=11')
    renderPage()
    await waitFor(() => expect(api.weeklyReports.get).toHaveBeenCalledWith(11))
    await screen.findByText('2026-W36')
    await waitFor(() => expect(api.weeklyReports.previous).toHaveBeenCalledWith(11))
    // Δ: item1 total 3 vs 1 → ▲ 2 (kötü), item2 open 2 vs 5 → ▼ 3 (iyi)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="wr-delta"]').length).toBeGreaterThan(0))
    const deltas = [...document.querySelectorAll('[data-slot="wr-delta"]')].map((d) => d.textContent.trim())
    expect(deltas).toEqual(expect.arrayContaining(['▲ 2', '▼ 3']))
    // öneri: sistemde 4 açık olay, elle 2 → Uygula
    const sug = await screen.findByText(/Uygula|Apply/)
    fireEvent.click(sug)
    await waitFor(() => expect(document.querySelector('[data-slot="wr-suggest"][data-same]')).not.toBeNull())
    // geçen haftanın notu
    fireEvent.click(screen.getByText(/Geçen haftanın notunu göster|Show last week's note/))
    expect(screen.getByText('Geçen haftanın notu')).toBeInTheDocument()
    // komşu hafta: ◀ önceki → previous id (12)
    fireEvent.click(screen.getByLabelText(/Önceki hafta|Previous week/))
    await waitFor(() => expect(api.weeklyReports.get).toHaveBeenCalledWith(12))
  })

  it('AUDIT: şeritte Oluştur yok, çubukta onay kuyruğu çipi yok', async () => {
    renderPage('AUDIT')
    const strip = await screen.findByRole('region', { name: /^(Bu hafta|This week)$/ })
    fireEvent.click(strip.querySelector('[data-slot="collapsible-trigger"]'))
    expect(within(strip).queryByText(/Oluştur|Create/)).toBeNull()
    await waitFor(() => expect(document.querySelector('[data-slot="wr-status-chips"]')).not.toBeNull())
    expect(document.querySelector('[data-chip="MINE"]')).toBeNull()
  })

  it('ikinci tur: listede yorum rozeti; detayda yorum paneli + "Şablondan tamamla" takım şablonundaki eksik kanalları ekler; hatırlatma durumu satırı', async () => {
    api.weeklyReports.list.mockResolvedValue({ success: true, data: [{ ...PENDING, comment_count: 3 }, DRAFT, SENT] })
    api.weeklyReports.remindersStatus.mockResolvedValue({ success: true, data: { enabled: true, next_run_at: '2026-09-18T06:00:00', will_send: 1, already_done: 0, no_email: 0, opt_in_teams: 1 } })
    api.weeklyReports.comments.mockResolvedValue({ success: true, data: [{ id: 1, kind: 'SUBMIT', author: 'Ekip Üyesi', created_at: '2026-09-10T10:00:00' }] })
    api.weeklyReports.get.mockImplementation(async (id) => id === 11
      ? { success: true, data: { report: DRAFT, images: [], team_channels: ['Web Kanalı', 'Mobil'] } }
      : { success: true, data: { report: PENDING, images: [] } })
    renderPage()
    await waitFor(() => expect(document.querySelector('[data-comments]')).not.toBeNull())
    expect(document.querySelector('[data-comments]').textContent).toBe('3')
    const rem = await screen.findByRole('note')
    expect(rem.textContent).toMatch(/1 takıma gidecek|going to 1 teams/)
    // detay: DRAFT (11) düzenlenebilir → şablon düğmesi (2 eksik), tıklayınca 2 kanal sekmesi
    const rows = document.querySelectorAll('[data-testid="wr-table"] tbody tr')   // hafta desc: 37, 36, 35
    fireEvent.click(rows[1])
    await waitFor(() => expect(api.weeklyReports.get).toHaveBeenCalledWith(11))
    await screen.findByText('2026-W36')
    const fill = await screen.findByText(/Şablondan tamamla \(2\)|Fill in from template \(2\)/)
    fireEvent.click(fill)
    // 2026-09-27: alanlar satır içi ad alanlarıyla (sekme değil) — değer olarak aranır
    await waitFor(() => expect(screen.getByDisplayValue('Mobil')).toBeInTheDocument())
    expect(screen.getByDisplayValue('Web Kanalı')).toBeInTheDocument()
    expect(screen.queryByText(/Şablondan tamamla|Fill in from template/)).toBeNull()
    // yorum paneli: sistem satırı listelenir, form var (ADMIN yazabilir)
    await waitFor(() => expect(api.weeklyReports.comments).toHaveBeenCalledWith(11))
    await screen.findByText(/Onaya gönderildi|Submitted for approval/)
    expect(document.querySelector('[data-slot="wr-comment-input"]')).not.toBeNull()
  })

  // ── D2 (2026-09-26): shadcn — özet katlanır bölümü, kanal sekmeleri (Tabs), telefon kart listesi ──
  it('D2: "Haftalık Özet" katlanır — kapalıyken gövde DOM\'da gizli (yazdırmada açık: print:block), açınca izleme göstergeleri çekilir', async () => {
    window.history.replaceState({}, '', '/?tab=weeklyreports&w_id=11')
    renderPage()
    await screen.findByText('2026-W36')
    const trigger = screen.getByRole('button', { name: /Haftalık Özet|Weekly Summary/ })
    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    const body = document.getElementById(trigger.getAttribute('aria-controls'))
    expect(body).not.toBeNull()
    expect(body.className).toMatch(/(^|\s)hidden(\s|$)/)
    expect(body.className).toMatch(/print:block/)
    expect(api.weeklyReports.monitoringStats).not.toHaveBeenCalled()
    fireEvent.click(trigger)
    await waitFor(() => expect(api.weeklyReports.monitoringStats).toHaveBeenCalledWith(11))
    expect(trigger).toHaveAttribute('aria-expanded', 'true')
    expect(document.getElementById(trigger.getAttribute('aria-controls')).className).not.toMatch(/(^|\s)hidden(\s|$)/)
  })

  // 2026-09-27: Madde 4 sekmeler yerine alan tablosu — ad satır içi düzenlenir, güncelleme satırın altında açılır
  it('Madde 4: alanlar tabloda satır içi ad alanlarıyla; ilk satırın güncellemesi açık, başka satır açılınca o kanalın notu düzenlenir', async () => {
    const withCh = { ...DRAFT, content_json: JSON.stringify({ ...JSON.parse(DRAFT.content_json), item4: { channels: [{ id: 'c1', name: 'Web', notes_md: 'web notu' }, { id: 'c2', name: 'Mobil', notes_md: 'mobil notu' }] } }) }
    api.weeklyReports.get.mockResolvedValue({ success: true, data: { report: withCh, images: [] } })
    window.history.replaceState({}, '', '/?tab=weeklyreports&w_id=11')
    renderPage()
    await screen.findByText('2026-W36')
    const table = document.querySelector('[data-slot="wr-domain-table"]')
    expect(within(table).getByRole('textbox', { name: /Domain adı — 1\. satır|Domain name — row 1/ })).toHaveValue('Web')
    expect(within(table).getByRole('textbox', { name: /Domain adı — 2\. satır|Domain name — row 2/ })).toHaveValue('Mobil')
    expect(screen.getByDisplayValue('web notu')).toBeInTheDocument()   // ilk satırın düzenleyicisi açık
    expect(screen.queryByDisplayValue('mobil notu')).toBeNull()
    fireEvent.click(within(table).getByRole('button', { name: /Güncellemeyi yaz — Mobil|Write the update — Mobil/ }))
    await waitFor(() => expect(screen.getByDisplayValue('mobil notu')).toBeInTheDocument())
    expect(screen.queryByDisplayValue('web notu')).toBeNull()
  })

  it('D2: telefonda (390 px) liste kart görünümünde — tablo yok, kart başlığı raporu açar, işlemler menüsü satırı adıyla ayırır', async () => {
    const w = window.innerWidth
    window.innerWidth = 390
    try {
      renderPage()
      await screen.findByRole('region', { name: /^(Bu hafta|This week)$/ })
      await waitFor(() => expect(document.querySelectorAll('[data-tour="wr-table"] li[data-status]').length).toBe(3))
      expect(document.querySelector('[data-testid="wr-table"]')).toBeNull()
      const card = document.querySelector('[data-tour="wr-table"] li[data-status="DRAFT"]')
      expect(within(card).getByRole('button', { name: /— (İşlemler|Actions)$/ })).toBeInTheDocument()
      fireEvent.click(card.querySelector('button'))
      await waitFor(() => expect(api.weeklyReports.get).toHaveBeenCalledWith(11))
    } finally {
      window.innerWidth = w
    }
  })
})
