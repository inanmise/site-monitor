import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'
import { isoWeekInfo } from '../utils/isoWeek'

/**
 * Haftalık Raporlar yeniden tasarımı (2026-09-27): sayfa başlığı + çipler, "bu hafta" kutucukları (süzgeç), liste
 * araması + süzgeç çipleri, "Yeni Hafta Raporu" ekranı (hafta hızlı seçimi → oluştur), düzenleyici (yapışkan eylem
 * çubuğu, kayıt durumu, gönderim kontrol listesi + onay penceresi, takip bağlantısı çipleri), yetki (AUDIT onaylamaz).
 * Mock kabuğu zenginleştirme testiyle aynı (editör/markdown/şeritler mock).
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
      approve: vi.fn(), thisWeek: vi.fn(), previous: vi.fn(), suggestions: vi.fn(), create: vi.fn(), deadline: vi.fn(),
    },
    admin: { getTeams: vi.fn() },
  }),
}))
import { api } from '../api/client'
import WeeklyReportsPage from '../components/WeeklyReportsPage.jsx'

const URL_A = 'https://jira.example.com/browse/SY-120'
const base = (o) => ({ team_id: 5, report_year: 2026, week_no: 37, week_label: '2026-W37', version: 4, content_json: '{}', updated_at: '2026-09-10T10:00:00', created_by: 'Ekip Üyesi', ...o })
const CONTENT = { version: 1, item1: { total: 3, urgent: 1, high: 2, medium: 0, low: 0, status_counts: { working: 2, planned: 1, on_hold: 0, done: 0 }, notes_md: '', tracking_url: URL_A }, item2: { open_incidents: 2, problem_records: 0, postmortems: 0, notes_md: '', incidents_url: 'jira.example.com/pm' }, item3: { notes_md: '' }, item4: { channels: [] } }
const PENDING = base({ id: 10, status: 'PENDING_APPROVAL', score: 84 })
const DRAFT = base({ id: 11, status: 'DRAFT', week_no: 36, week_label: '2026-W36', content_json: JSON.stringify(CONTENT) })
const SENT = base({ id: 12, status: 'APPROVED', sent_at: '2026-09-01T10:00:00', approved_by: 'PO Kullanıcısı', week_no: 35, week_label: '2026-W35', score: 61 })
const THIS_WEEK = { year: 2026, week: 37, due_at: '2000-01-01T12:00:00', deadline_day: 'FRI', deadline_time: '15:00',
  teams: [{ team_id: 5, team_name: 'SY-A', status: 'PENDING_APPROVAL', report_id: 10 }, { team_id: 6, team_name: 'SY-B', status: 'MISSING', report_id: null }, { team_id: 7, team_name: 'SY-C', status: 'DRAFT', report_id: 30 }] }

const renderPage = (role = 'ADMIN', teamId = 5) => render(<LangProvider><WeeklyReportsPage systemRole={role} teamId={teamId} teamName="SY-A" /></LangProvider>)
const openEditor = async (id) => {
  window.history.replaceState({}, '', `/?tab=weeklyreports&w_id=${id}`)
  renderPage()
  await waitFor(() => expect(api.weeklyReports.get).toHaveBeenCalledWith(id))
}

describe('WeeklyReportsPage — yeniden tasarım (2026-09-27)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    try { localStorage.clear() } catch { /* yoksay */ }
    confirmMock.mockResolvedValue(true)
    window.history.replaceState({}, '', '/?tab=weeklyreports')
    api.weeklyReports.years.mockResolvedValue({ success: true, data: [2026] })
    api.weeklyReports.list.mockResolvedValue({ success: true, data: [PENDING, DRAFT, SENT] })
    api.weeklyReports.thisWeek.mockResolvedValue({ success: true, data: THIS_WEEK })
    api.weeklyReports.get.mockImplementation(async (id) => { const r = [PENDING, DRAFT, SENT].find((x) => x.id === id); return r ? { success: true, data: { report: r, images: [] } } : { success: false, error: 'yok' } })
    api.weeklyReports.lock.mockResolvedValue({ success: true, data: { acquired: true } })
    api.weeklyReports.unlock.mockResolvedValue({ success: true })
    api.weeklyReports.save.mockResolvedValue({ success: true, data: { status: 'DRAFT', version: 5 } })
    api.weeklyReports.submit.mockResolvedValue({ success: true })
    api.weeklyReports.previous.mockResolvedValue({ success: true, data: null })
    api.weeklyReports.suggestions.mockResolvedValue({ success: true, data: null })
    api.weeklyReports.create.mockResolvedValue({ success: true, data: { id: 11 } })
    api.weeklyReports.deadline.mockResolvedValue({ success: true, data: { day: 'FRI', time: '15:00', day_tr: 'Cuma', day_en: 'Friday' } })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }, { id: 6, name: 'SY-B' }, { id: 7, name: 'SY-C' }] })
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('başlık: PageHeader + bu haftanın çipleri (gönderilen, geciken, onayımı bekleyen) + Dışa aktar + birincil "Yeni Hafta Raporu"; AUDIT yeni rapor açamaz', async () => {
    const { unmount } = renderPage()
    const header = await screen.findByRole('heading', { level: 2, name: /Haftalık Raporlar|Weekly Reports/ })
    const hdr = header.closest('[data-slot="page-header"]')
    await waitFor(() => expect(hdr.querySelector('[data-slot="wr-meta-submitted"]')).not.toBeNull())
    expect(hdr.querySelector('[data-slot="wr-meta-submitted"]').textContent).toMatch(/1 \/ 3/)
    expect(hdr.querySelector('[data-slot="wr-meta-overdue"]').textContent).toMatch(/2/)
    expect(within(hdr).getByRole('button', { name: /Onayımı bekleyen: 1|Awaiting my approval: 1/ })).toBeInTheDocument()
    const actions = hdr.querySelector('[data-slot="page-actions"]')
    const names = within(actions).getAllByRole('button').map((b) => b.getAttribute('aria-label') || b.textContent.trim())
    // Sıra: en az → en çok önemli; birincil EN SAĞDA
    expect(names[names.length - 1]).toMatch(/Yeni Hafta Raporu|New Weekly Report/)
    expect(names.some((n) => /Dışa aktar|Export/.test(n))).toBe(true)
    unmount()
    renderPage('AUDIT')
    await screen.findByRole('heading', { level: 2, name: /Haftalık Raporlar|Weekly Reports/ })
    expect(screen.queryByRole('button', { name: /Yeni Hafta Raporu|New Weekly Report/ })).toBeNull()
  })

  it('"Bu hafta" kutucukları süzgeçtir: "Rapor yok" yalnız eksik takımı gösterir (liste açılır), tekrar basınca kalkar', async () => {
    renderPage()
    const region = await screen.findByRole('region', { name: /^(Bu hafta|This week)$/ })
    const tile = region.querySelector('[data-slot="stat-item"][data-key="missing"]')
    expect(tile.querySelector('[data-slot="stat-value"]').textContent).toBe('1')
    fireEvent.click(tile)
    expect(tile).toHaveAttribute('aria-pressed', 'true')
    await waitFor(() => expect(region.querySelectorAll('li[data-status]').length).toBe(1))
    expect(region.querySelector('li[data-status]')).toHaveAttribute('data-status', 'MISSING')
    // Gecikti: son giriş geçti → eksik + süren (2)
    expect(region.querySelector('[data-slot="stat-item"][data-key="overdue"] [data-slot="stat-value"]').textContent).toBe('2')
    fireEvent.click(tile)
    await waitFor(() => expect(region.querySelectorAll('li[data-status]').length).toBe(3))
  })

  it('liste araması + süzgeç çipi: "W35" tek satır bırakır; çip adıyla kaldırılır; "Filtreleri temizle"; URL w_q', async () => {
    renderPage()
    await waitFor(() => expect(document.querySelectorAll('[data-testid="wr-table"] tbody tr')).toHaveLength(3))
    fireEvent.change(screen.getByRole('searchbox', { name: /Hafta, takım, kişi ara|Search weeks, teams, people/ }), { target: { value: 'W35' } })
    await waitFor(() => expect(document.querySelectorAll('[data-testid="wr-table"] tbody tr')).toHaveLength(1))
    await waitFor(() => expect(window.location.search).toContain('w_q=W35'))
    expect(document.querySelector('[data-slot="wr-count"]').textContent).toMatch(/1/)
    const chip = document.querySelector('[data-slot="wr-chip"][data-chip="q"]')
    expect(chip).toHaveAccessibleName(/W35/)
    fireEvent.click(chip)
    await waitFor(() => expect(document.querySelectorAll('[data-testid="wr-table"] tbody tr')).toHaveLength(3))
    // Eşleşme yoksa: süzgeç durumuna özgü boş durum + temizle
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'hiçbiri' } })
    await screen.findByText(/Süzgeçlerle eşleşen rapor yok|No reports match these filters/)
    fireEvent.click(screen.getAllByRole('button', { name: /Filtreleri temizle|Clear filters/ })[0])
    await waitFor(() => expect(document.querySelectorAll('[data-testid="wr-table"] tbody tr')).toHaveLength(3))
  })

  it('"Yeni Hafta Raporu" ekranı: "Geçen hafta" hızlı seçimi → oluştur doğru yıl/hafta ile; sonra düzenleyici açılır', async () => {
    const prev = isoWeekInfo(new Date(Date.now() - 7 * 86400000))
    renderPage('USER')
    fireEvent.click(await screen.findByRole('button', { name: /Yeni Hafta Raporu|New Weekly Report/ }))
    await screen.findByRole('heading', { level: 2, name: /Yeni Hafta Raporu|New Weekly Report/ })
    const thisWeekBtn = screen.getByRole('button', { name: /^(Bu hafta|This week)/ })
    expect(thisWeekBtn).toHaveAttribute('aria-pressed', 'true')
    const lastWeekBtn = screen.getByRole('button', { name: /^(Geçen hafta|Last week)/ })
    fireEvent.click(lastWeekBtn)
    expect(lastWeekBtn).toHaveAttribute('aria-pressed', 'true')
    const summary = document.querySelector('[data-slot="wr-new-week"]')
    expect(within(summary).getByText(`${prev.year}-W${String(prev.week).padStart(2, '0')}`)).toBeInTheDocument()
    const cta = screen.getByRole('button', { name: /^(Raporu oluştur|Create report)$/ })
    await waitFor(() => expect(cta).toBeEnabled())
    fireEvent.click(cta)
    await waitFor(() => expect(api.weeklyReports.create).toHaveBeenCalledWith({ team_id: 5, year: prev.year, week_no: prev.week, carry_notes: false }))
    await waitFor(() => expect(api.weeklyReports.get).toHaveBeenCalledWith(11))
    expect(await screen.findByText('2026-W36')).toBeInTheDocument()
  })

  it('yeni rapor ekranı: takımın o hafta raporu varsa oluştur KAPALI (DUPLICATE_WEEK beklenmez), "Raporu aç" mevcut raporu açar', async () => {
    const now = isoWeekInfo()
    // Bu haftanın raporu takımda zaten var (tarih testin koştuğu güne göre — sabit hafta zaman bombası olurdu)
    api.weeklyReports.list.mockResolvedValue({ success: true, data: [PENDING, DRAFT, SENT,
      base({ id: 77, status: 'DRAFT', report_year: now.year, week_no: now.week })] })
    renderPage('USER')
    fireEvent.click(await screen.findByRole('button', { name: /Yeni Hafta Raporu|New Weekly Report/ }))
    await screen.findByRole('heading', { level: 2, name: /Yeni Hafta Raporu|New Weekly Report/ })
    const summary = document.querySelector('[data-slot="wr-new-week"]')
    await waitFor(() => expect(summary.textContent).toMatch(/zaten var|already has a report/))
    expect(screen.getByRole('button', { name: /^(Raporu oluştur|Create report)$/ })).toBeDisabled()
    fireEvent.click(within(summary).getByRole('button', { name: /Raporu aç|Open the report/ }))
    await waitFor(() => expect(api.weeklyReports.get).toHaveBeenCalledWith(77))
    expect(api.weeklyReports.create).not.toHaveBeenCalled()
  })

  it('düzenleyici: yapışkan eylem çubuğu (toolbar) + kayıt durumu; değişiklik → "Kaydedilmemiş", Taslağı kaydet → save(id, içerik, sürüm)', async () => {
    await openEditor(11)
    await screen.findByText('2026-W36')
    const bar = document.querySelector('[data-slot="wr-actions"]')
    expect(bar).toHaveAttribute('role', 'toolbar')
    expect(bar.className).toMatch(/(^|\s)sticky(\s|$)/)
    expect(bar.className).toMatch(/bottom-0/)
    expect(within(bar).getByRole('status')).toHaveAttribute('data-state', 'saved')
    fireEvent.change(screen.getByRole('textbox', { name: /Acil|Urgent/ }), { target: { value: '4' } })
    await waitFor(() => expect(within(bar).getByRole('status')).toHaveAttribute('data-state', 'dirty'))
    fireEvent.click(within(bar).getByRole('button', { name: /Taslağı kaydet|Save draft/ }))
    await waitFor(() => expect(api.weeklyReports.save).toHaveBeenCalledWith(11, expect.stringContaining('"urgent":4'), 4))
    await waitFor(() => expect(within(bar).getByRole('status')).toHaveAttribute('data-state', 'saved'))
  })

  it('gönderim: kontrol listesi açık maddeleri sayar (boş bölümler + geçersiz bağlantı); Onaya Gönder onay penceresi ister, uyarıyla', async () => {
    await openEditor(11)
    await screen.findByText('2026-W36')
    const bar = document.querySelector('[data-slot="wr-actions"]')
    const check = within(bar).getByRole('button', { name: /Kontrol edilecek: 3|To check: 3/ })
    fireEvent.click(check)
    expect(await screen.findByText(/Boş bölüm: Weekly Engagements|Boş bölüm: Haftalık|Empty section: Weekly Engagements/)).toBeInTheDocument()
    expect(screen.getByText(/Geçersiz bağlantı|Invalid link/)).toBeInTheDocument()
    confirmMock.mockResolvedValueOnce(false)
    fireEvent.click(within(bar).getByRole('button', { name: /^(Onaya Gönder|Submit for Approval)$/ }))
    await waitFor(() => expect(confirmMock).toHaveBeenCalledWith(expect.objectContaining({ message: expect.stringMatching(/3/) })))
    expect(api.weeklyReports.submit).not.toHaveBeenCalled()
    fireEvent.click(within(bar).getByRole('button', { name: /^(Onaya Gönder|Submit for Approval)$/ }))
    await waitFor(() => expect(api.weeklyReports.submit).toHaveBeenCalledWith(11))
  })

  it('takip bağlantıları çip: etiket türetilir, ham URL satır içinde yok; eski şemasız değer uyarı çipi', async () => {
    await openEditor(11)
    await screen.findByText('2026-W36')
    const main = document.querySelector('[data-section="item1"]')
    const link = within(main).getByRole('link', { name: /SY-120/ })
    expect(link).toHaveAttribute('href', URL_A)
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    expect(main.textContent).not.toContain('jira.example.com/browse')
    const sec2 = document.querySelector('[data-section="item2"]')
    expect(sec2.querySelector('[data-slot="wr-link"][data-valid="false"]')).not.toBeNull()
  })

  it('Madde 1 durum dağılımı: tekil "Durum" seçimi yok, dört sayı + toplam; tutarsızlık uyarısı (kontrol listesinde de); kayıt yükü status_counts', async () => {
    api.weeklyReports.previous.mockResolvedValue({ success: true, data: { ...SENT, content_json: JSON.stringify({ item1: { total: 2, status_counts: { working: 1, planned: 1, on_hold: 0, done: 0 } } }) } })
    await openEditor(11)
    await screen.findByText('2026-W36')
    const box = document.querySelector('[data-slot="wr-status-breakdown"]')
    // Eski tekil "Durum" seçicisi kalktı
    expect(screen.queryByRole('combobox', { name: /^(Durum|Status)$/ })).toBeNull()
    const done = within(box).getByRole('textbox', { name: /Tamamlandı|Done/ })
    expect(within(box).getByRole('textbox', { name: /Çalışılıyor|In progress/ })).toHaveValue('2')
    expect(box.querySelector('[data-slot="wr-status-sum"]').textContent).toMatch(/3/)
    expect(box.querySelector('[data-slot="wr-status-mismatch"]')).toBeNull()
    // Geçen haftaya fark: nötr rozet (iyi/kötü yok) — Çalışılıyor 1 → 2
    await waitFor(() => expect(box.querySelector('[data-slot="wr-delta"]')).not.toBeNull())
    expect(box.querySelector('[data-slot="wr-delta"]').textContent.trim()).toBe('▲ 1')
    expect(box.querySelector('[data-slot="wr-delta"]').hasAttribute('data-good')).toBe(false)
    fireEvent.change(done, { target: { value: '2' } })
    await waitFor(() => expect(box.querySelector('[data-slot="wr-status-mismatch"]')).not.toBeNull())
    expect(box.querySelector('[data-slot="wr-status-mismatch"]').textContent).toMatch(/5.*3/)
    const bar = document.querySelector('[data-slot="wr-actions"]')
    expect(within(bar).getByRole('button', { name: /Kontrol edilecek: 4|To check: 4/ })).toBeInTheDocument()
    fireEvent.click(within(bar).getByRole('button', { name: /Taslağı kaydet|Save draft/ }))
    await waitFor(() => expect(api.weeklyReports.save).toHaveBeenCalled())
    const saved = JSON.parse(api.weeklyReports.save.mock.calls[0][1])
    expect(saved.item1.status_counts).toEqual({ working: 2, planned: 1, on_hold: 0, done: 2 })
  })

  it('eski rapor (status_text, dağılım yok): salt okunurda "Önceki tekil durum" gösterilir, sahte tutarsızlık uyarısı yok', async () => {
    const LEGACY = base({ id: 13, status: 'APPROVED', week_no: 30, week_label: '2026-W30',
      content_json: JSON.stringify({ item1: { total: 4, urgent: 4, status_text: 'Planlandı' }, item2: {}, item3: { notes_md: '' }, item4: { channels: [] } }) })
    api.weeklyReports.get.mockResolvedValue({ success: true, data: { report: LEGACY, images: [] } })
    api.weeklyReports.list.mockResolvedValue({ success: true, data: [PENDING, DRAFT, SENT, LEGACY] })   // listede yoksa sayfa listeye döner
    window.history.replaceState({}, '', '/?tab=weeklyreports&w_id=13')
    renderPage('AUDIT')
    await screen.findByText('2026-W30')
    const box = document.querySelector('[data-slot="wr-status-breakdown"]')
    expect(box.querySelector('[data-slot="wr-legacy-status"]').textContent).toMatch(/Planlandı|Planned/)
    expect(box.querySelector('[data-slot="wr-status-mismatch"]')).toBeNull()
    expect(within(box).getByRole('textbox', { name: /Çalışılıyor|In progress/ })).toBeDisabled()
  })

  it('yetki: AUDIT onay bekleyen raporda Onayla / İade Et görmez, eylem çubuğunda yalnız okur', async () => {
    window.history.replaceState({}, '', '/?tab=weeklyreports&w_id=10')
    renderPage('AUDIT')
    await waitFor(() => expect(api.weeklyReports.get).toHaveBeenCalledWith(10))
    await screen.findByText('2026-W37')
    const bar = document.querySelector('[data-slot="wr-actions"]')
    expect(within(bar).queryByRole('button', { name: /^(Onayla|Approve)$/ })).toBeNull()
    expect(within(bar).queryByRole('button', { name: /^(İade Et|Return)$/ })).toBeNull()
    expect(within(bar).getByRole('status')).toHaveAttribute('data-state', 'readonly')
    expect(api.weeklyReports.lock).not.toHaveBeenCalled()
  })
})
