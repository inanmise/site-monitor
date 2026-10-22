import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'
import { EN } from '../i18n/en.js'
import { isoWeekInfo } from '../utils/isoWeek'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

/**
 * Haftalık Raporlar — 2026-10-22 hata avı (PKG-8b):
 *  WR-2 / UIA-G1  kapsamlı müdür (ADMIN rolü, global DEĞİL) arayüzde global yönetici sayılıyordu (takım süzgeci, takım
 *                 seçici, aktarım, hatırlatma); seçtiği takım sunucuda sessizce kendi takımına dönüyordu.
 *  UIA-3          kayıt uçuştayken yazılanlar "kaydedildi" sayılıp kayboluyordu.
 *  UIA-G2         onay / tekrar gönderim her durumda "müdüre mail gönderildi" diyordu (mail_status okunmuyordu).
 *  UIA-G5         "SKIPPED: pasif kullanıcı" gönderim geçmişinde kırmızı "Hata" rozetiydi.
 *  K8             yabancı team_id → 400 VALIDATION_FAILED field=team_id: sunucunun metni gösterilir.
 */
const confirmMock = vi.fn(() => Promise.resolve(true))
const toastMock = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
const permsMock = { scope: null }
vi.mock('@uiw/react-md-editor/nohighlight', () => ({ default: ({ value }) => <textarea readOnly value={value ?? ''} />, commands: { bold: {}, italic: {}, group: () => ({}) } }))
vi.mock('react-markdown', () => ({ default: ({ children }) => <div>{children}</div> }))
vi.mock('remark-gfm', () => ({ default: () => {} }))
vi.mock('../components/WeeklyKpiStrip.jsx', () => ({ default: () => <div data-testid="kpi" /> }))
vi.mock('../components/WeeklySummaryBrief.jsx', () => ({ default: () => <div data-testid="brief" /> }))
vi.mock('../components/WeeklyMonitoringStrip.jsx', () => ({ default: () => <div data-testid="mon" /> }))
vi.mock('../components/WeeklyCompletionBoard.jsx', () => ({ default: () => <div data-testid="board" /> }))
vi.mock('../components/ui/Dialog.jsx', () => ({ useDialog: () => ({ showConfirm: confirmMock }), DialogProvider: ({ children }) => children }))
vi.mock('../components/ui/Toast.jsx', () => ({ useToast: () => toastMock, ToastProvider: ({ children }) => children }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({ usePermissions: () => permsMock }))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  api: withApiFallback({
    weeklyReports: {
      years: vi.fn(), list: vi.fn(), get: vi.fn(), lock: vi.fn(), unlock: vi.fn(), save: vi.fn(), submit: vi.fn(),
      approve: vi.fn(), resend: vi.fn(), previous: vi.fn(), thisWeek: vi.fn(), mails: vi.fn(), create: vi.fn(),
      kpis: vi.fn(), monitoringStats: vi.fn(), comments: vi.fn(),
    },
    admin: { getTeams: vi.fn() },
  }),
}))
import { api } from '../api/client'
import WeeklyReportsPage from '../components/WeeklyReportsPage.jsx'
import WeeklyComments from '../components/weekly/WeeklyComments.jsx'

const now = isoWeekInfo()
const content = JSON.stringify({ version: 1, item1: { total: 1, urgent: 1, status_counts: { working: 1 } }, item2: {}, item3: { notes_md: '' }, item4: { channels: [] } })
const base = (o) => ({ team_id: 5, report_year: now.year, week_no: now.week, week_label: `${now.year}-W${now.week}`, status: 'DRAFT', version: 4, content_json: content, updated_at: '2026-10-20T10:00:00', ...o })
const DRAFT = base({ id: 11 })
const PENDING = base({ id: 12, status: 'PENDING_APPROVAL' })
const APPROVED = base({ id: 13, status: 'APPROVED', sent_at: '2026-10-20T11:00:00' })
const deferred = () => { let resolve; const p = new Promise((r) => { resolve = r }); return { p, resolve } }
const flush = () => act(() => new Promise((r) => setTimeout(r, 0)))
const renderPage = (props = {}) => render(<LangProvider><WeeklyReportsPage systemRole="ADMIN" teamId={5} teamName="Takım A" {...props} /></LangProvider>)

function setup(rows) {
  api.weeklyReports.years.mockResolvedValue({ success: true, data: [now.year] })
  api.weeklyReports.list.mockResolvedValue({ success: true, data: rows })
  api.weeklyReports.get.mockImplementation(async (id) => {
    const report = rows.find((r) => r.id === id)
    return report ? { success: true, data: { report, images: [] } } : { success: false, status: 404, error: 'yok' }
  })
  api.weeklyReports.lock.mockResolvedValue({ success: true, data: { acquired: true } })
  api.weeklyReports.unlock.mockResolvedValue({ success: true })
  api.weeklyReports.previous.mockResolvedValue({ success: true, data: null })
  api.weeklyReports.thisWeek.mockResolvedValue({ success: true, data: null })
  api.weeklyReports.kpis.mockResolvedValue({ success: true, data: null })
  api.weeklyReports.comments.mockResolvedValue({ success: true, data: [] })
  api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'Takım A' }, { id: 6, name: 'Takım B' }] })
}

describe('WeeklyReportsPage — PKG-8b hata düzeltmeleri', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    permsMock.scope = null
    try { localStorage.clear() } catch { /* yoksay */ }
    confirmMock.mockResolvedValue(true)
    window.history.replaceState({}, '', '/?tab=weeklyreports')
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('WR-2: kapsamlı müdür (ADMIN, globalAdmin=false) global yönetici DEĞİLDİR — takım listesi / süzgeç / hatırlatma yok, oluşturma kendi takımına', async () => {
    setup([APPROVED])
    api.weeklyReports.create.mockResolvedValue({ success: true, data: { id: 99 } })
    renderPage({ globalAdmin: false })
    await waitFor(() => expect(api.weeklyReports.list).toHaveBeenCalled())
    await flush()
    // Takım dizini hiç istenmez; takım süzgeci ve hatırlatma düğmesi çizilmez; liste kendi takımıyla istenir
    expect(api.admin.getTeams).not.toHaveBeenCalled()
    expect(api.weeklyReports.list).toHaveBeenCalledWith(expect.objectContaining({ teamId: 5 }))
    expect(screen.queryByRole('combobox', { name: EN['wr.team'] })).toBeNull()
    expect(screen.queryByRole('button', { name: new RegExp(EN['wr.sendReminderNow']) })).toBeNull()
    // Yeni rapor: takım seçici yok (kendi takımı rozeti), gövdede team_id GÖNDERİLMEZ
    fireEvent.click(screen.getAllByRole('button', { name: new RegExp(EN['wr.newReport']) })[0])
    await screen.findByText(EN['wr.new.stepTeamOwn'])
    // Seçili hafta için "boş" denetimi biter
    fireEvent.click(screen.getByRole('button', { name: new RegExp(EN['wr.new.lastWeek']) }))
    const cta = document.querySelector('[data-slot="wr-create-submit"]')
    await waitFor(() => expect(cta).not.toBeDisabled())
    fireEvent.click(cta)
    await waitFor(() => expect(api.weeklyReports.create).toHaveBeenCalled())
    expect(api.weeklyReports.create.mock.calls[0][0].team_id).toBeUndefined()
  })

  it('WR-2: prop yoksa izin anlık görüntüsünün scope.global_admin değeri kullanılır (K1); bilinmiyorsa global değil', async () => {
    setup([APPROVED])
    permsMock.scope = { global_admin: true }
    const { unmount } = renderPage()
    await waitFor(() => expect(api.admin.getTeams).toHaveBeenCalled())
    unmount()
    vi.clearAllMocks()
    setup([APPROVED])
    permsMock.scope = null
    renderPage()
    await waitFor(() => expect(api.weeklyReports.list).toHaveBeenCalled())
    await flush()
    expect(api.admin.getTeams).not.toHaveBeenCalled()
  })

  it('WR-2: global yönetici (globalAdmin) takım listesini alır ve takım süzgecini görür (bugünkü davranış)', async () => {
    setup([APPROVED])
    renderPage({ globalAdmin: true })
    await waitFor(() => expect(api.admin.getTeams).toHaveBeenCalled())
  })

  it('UIA-3: kayıt sürerken yazılan metin kirli KALIR, ikinci kayıt onu YENİ sürümle gönderir; yerel yedek silinmez', async () => {
    setup([DRAFT])
    window.history.replaceState({}, '', '/?tab=weeklyreports&w_id=11')
    renderPage({ globalAdmin: true })
    const urgent = await screen.findByRole('textbox', { name: /^(Acil|Urgent)$/ })
    const first = deferred()
    api.weeklyReports.save.mockImplementationOnce(() => first.p)
    fireEvent.change(urgent, { target: { value: '3' } })
    fireEvent.click(screen.getByRole('button', { name: new RegExp(EN['wr.saveDraft']) }))
    await waitFor(() => expect(api.weeklyReports.save).toHaveBeenCalledTimes(1))
    expect(JSON.parse(api.weeklyReports.save.mock.calls[0][1]).item1.urgent).toBe(3)
    // Kayıt uçuştayken yazılır
    fireEvent.change(urgent, { target: { value: '7' } })
    await act(async () => { first.resolve({ success: true, data: { status: 'DRAFT', version: 5 } }) })
    await flush()
    // Kirli kaldı → Kaydet düğmesi açık; yerel yedek duruyor
    const saveBtn = screen.getByRole('button', { name: new RegExp(EN['wr.saveDraft']) })
    expect(saveBtn).not.toBeDisabled()
    await act(async () => { await new Promise((r) => setTimeout(r, 1700)) })   // 1.5 sn yedek debounce'u
    expect(localStorage.getItem('wr.draft.11')).not.toBeNull()
    api.weeklyReports.save.mockResolvedValueOnce({ success: true, data: { status: 'DRAFT', version: 6 } })
    fireEvent.click(saveBtn)
    await waitFor(() => expect(api.weeklyReports.save).toHaveBeenCalledTimes(2))
    const [, body, version] = api.weeklyReports.save.mock.calls[1]
    expect(JSON.parse(body).item1.urgent).toBe(7)
    expect(version).toBe(5)
    await flush()
    await waitFor(() => expect(screen.getByRole('button', { name: new RegExp(EN['wr.saveDraft']) })).toBeDisabled())
    expect(localStorage.getItem('wr.draft.11')).toBeNull()
  })

  it('UIA-3: Onaya gönder, kayıt sürerken yazılanları ÖNCE kaydeder (iki kayıt, sonra gönderim)', async () => {
    setup([DRAFT])
    window.history.replaceState({}, '', '/?tab=weeklyreports&w_id=11')
    api.weeklyReports.submit.mockResolvedValue({ success: true })
    renderPage({ globalAdmin: true })
    const urgent = await screen.findByRole('textbox', { name: /^(Acil|Urgent)$/ })
    fireEvent.change(urgent, { target: { value: '2' } })
    const first = deferred()
    api.weeklyReports.save.mockImplementationOnce(() => first.p)
    api.weeklyReports.save.mockResolvedValueOnce({ success: true, data: { status: 'DRAFT', version: 6 } })
    fireEvent.click(screen.getByRole('button', { name: new RegExp(`^${EN['wr.submit']}`) }))
    await waitFor(() => expect(api.weeklyReports.save).toHaveBeenCalledTimes(1))
    fireEvent.change(urgent, { target: { value: '9' } })
    await act(async () => { first.resolve({ success: true, data: { status: 'DRAFT', version: 5 } }) })
    await waitFor(() => expect(api.weeklyReports.submit).toHaveBeenCalledWith(11))
    expect(api.weeklyReports.save).toHaveBeenCalledTimes(2)
    expect(JSON.parse(api.weeklyReports.save.mock.calls[1][1]).item1.urgent).toBe(9)
    expect(api.weeklyReports.save.mock.calls[1][2]).toBe(5)
  })

  it.each([
    ['SENT', 'success', 'wr.approveOk'],
    [undefined, 'success', 'wr.approveOk'],
    ['QUEUED_RETRY 1/3', 'info', 'wr.mail.approvedQueued'],
    ['SKIPPED_DISABLED', 'info', 'wr.mail.approvedSkippedOff'],
    ['SKIPPED: pasif kullanıcı', 'info', 'wr.mail.approvedSkippedRecipients'],
    ['FAILED: Connection refused', 'error', 'wr.mail.approvedFailed'],
  ])('UIA-G2: onay mail_status=%s → %s (%s)', async (status, tone, key) => {
    setup([PENDING])
    window.history.replaceState({}, '', '/?tab=weeklyreports&w_id=12')
    api.weeklyReports.approve.mockResolvedValue({ success: true, ...(status ? { mail_status: status } : {}) })
    renderPage({ globalAdmin: true })
    fireEvent.click((await screen.findAllByRole('button', { name: /^(Approve|Onayla)$/ }))[0])
    await waitFor(() => expect(api.weeklyReports.approve).toHaveBeenCalledWith(12))
    await waitFor(() => expect(toastMock[tone]).toHaveBeenCalled())
    expect(toastMock[tone].mock.calls[0][0]).toBe(EN[key])
    if (tone !== 'success') expect(toastMock.success).not.toHaveBeenCalled()
  })

  it('UIA-G2: tekrar gönderimde FAILED → "gönderildi" DENMEZ', async () => {
    setup([APPROVED])
    window.history.replaceState({}, '', '/?tab=weeklyreports&w_id=13')
    api.weeklyReports.resend.mockResolvedValue({ success: true, mail_status: 'FAILED: x' })
    renderPage({ globalAdmin: true })
    fireEvent.click((await screen.findAllByRole('button', { name: /^(Resend|Tekrar Gönder)$/ }))[0])
    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith(EN['wr.mail.resendFailed'], expect.anything()))
    expect(toastMock.success).not.toHaveBeenCalled()
  })

  it('UIA-8: onay isteği ağda düşerse ileti gösterilir (yakalanmamış söz reddi yok)', async () => {
    setup([PENDING])
    window.history.replaceState({}, '', '/?tab=weeklyreports&w_id=12')
    api.weeklyReports.approve.mockRejectedValue(Object.assign(new Error('Sunucuya ulaşılamadı'), { status: 0 }))
    renderPage({ globalAdmin: true })
    fireEvent.click((await screen.findAllByRole('button', { name: /^(Approve|Onayla)$/ }))[0])
    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith('Sunucuya ulaşılamadı'))
  })

  it('UIA-G5: gönderim geçmişinde "SKIPPED: pasif kullanıcı" HATA değil "atlandı" rozetidir', async () => {
    setup([APPROVED])
    window.history.replaceState({}, '', '/?tab=weeklyreports&w_id=13')
    api.weeklyReports.mails.mockResolvedValue({ success: true, data: [
      { id: 1, mail_type: 'APPROVE_MANAGER', status: 'SKIPPED: pasif kullanıcı', subject: 's', created_at: 'x', created_by: 'u' },
    ] })
    renderPage({ globalAdmin: true })
    pressMenuTrigger(await screen.findByRole('button', { name: EN['wr.ed.more'] }))
    fireEvent.click(await screen.findByRole('menuitem', { name: new RegExp(EN['wr.history']) }))
    await screen.findByText(EN['wr.mailStatusSkipped'])
    expect(screen.queryByText(EN['wr.mailStatusFailed'])).toBeNull()
  })

  it('K8: yabancı takım reddi (400 VALIDATION_FAILED field=team_id) sunucunun metniyle gösterilir', async () => {
    setup([])
    api.weeklyReports.create.mockResolvedValue({ success: false, status: 400, code: 'VALIDATION_FAILED', field: 'team_id', error: 'Başka takım için rapor açamazsınız.' })
    renderPage({ globalAdmin: false })
    await waitFor(() => expect(api.weeklyReports.list).toHaveBeenCalled())
    fireEvent.click(screen.getAllByRole('button', { name: new RegExp(EN['wr.newReport']) })[0])
    fireEvent.click(await screen.findByRole('button', { name: new RegExp(EN['wr.new.lastWeek']) }))
    const cta = document.querySelector('[data-slot="wr-create-submit"]')
    await waitFor(() => expect(cta).not.toBeDisabled())
    fireEvent.click(cta)
    await waitFor(() => expect(toastMock.error).toHaveBeenCalledWith('Başka takım için rapor açamazsınız.'))
  })
})

describe('WeeklyComments — okuma hatası (UIA-G9)', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('başarısız okuma "yorum yok" DEMEZ; Tekrar dene yeniden okur', async () => {
    api.weeklyReports.comments.mockResolvedValueOnce({ success: false, status: 500 })
    api.weeklyReports.comments.mockResolvedValueOnce({ success: true, data: [{ id: 1, kind: 'COMMENT', author: 'ali', text: 'İade notu', created_at: 'x' }] })
    render(<LangProvider><WeeklyComments reportId={7} canWrite /></LangProvider>)
    await screen.findByText(EN['wr.cm.loadFailed'])
    expect(screen.queryByText(EN['wr.cm.empty'])).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: EN['wr.cm.retry'] }))
    await screen.findByText('İade notu')
    expect(api.weeklyReports.comments).toHaveBeenCalledTimes(2)
  })

  it('ağ hatası da "okunamadı" (boş dizi değil)', async () => {
    api.weeklyReports.comments.mockRejectedValueOnce(new Error('net'))
    render(<LangProvider><WeeklyComments reportId={8} canWrite={false} /></LangProvider>)
    await screen.findByText(EN['wr.cm.loadFailed'])
  })
})
