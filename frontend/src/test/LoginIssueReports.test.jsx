import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils'

// Sorun Bildirimleri — YÖNETİCİ kitlesi (2026-09-27 yeniden tasarım): sunucu süzgeçleri + sayfalama, referans kodu
// araması, görünüm anahtarı, ayrıntı Sheet'inde durum eylemleri (not paneli), konuşma (iç not), mail geçmişi,
// kalıcı silme (ayrı yetki + onay), imza gruplaması, derin bağlantılar. İzin yoksa kullanıcı görünümü.

const sampleRow = {
  id: 5, refCode: 'LIR-2026-000005', username: 'N12345', messageSummary: 'Cannot login',
  ipAddress: '1.2.3.4', reportedAt: '2026-07-23T10:00:00', resolvedAt: '2026-07-23T12:00:00',
  status: 'RESOLVED', imageCount: 2, source: 'LOGIN', lastActivityAt: '2026-07-23T12:00:00', signature: 'http <n> locked',
}
const sampleDetail = {
  id: 5, refCode: 'LIR-2026-000005', username: 'N12345', reporterEmail: 'n12345@example.com', errorText: 'HTTP 423',
  message: 'Cannot login at all', ipAddress: '1.2.3.4', userAgent: 'curl/8', status: 'OPEN', source: 'LOGIN',
  reportedAt: '2026-07-23T10:00:00', resolvedBy: null, resolvedAt: null, resolutionNote: null, lastActivityAt: '2026-07-23T11:30:00',
  imageCount: 2, images: ['data:image/png;base64,AAAA', 'data:image/png;base64,BBBB'],
  mailHistory: [
    { mailType: 'REPORT_ADMIN', from: 'noreply@example.com', to: 'admin@example.com', cc: null, subject: 'Konu R', body: "<p>rapor govdesi</p><img src='cid:shot0'>", status: 'SENT', error: null, forced: true, sentAt: '2026-07-23T10:00:05' },
    { mailType: 'REPORTER_ACK', from: 'noreply@example.com', to: 'user@example.com', cc: null, subject: 'Konu A', body: '<p>onay govdesi</p>', status: 'FAILED: 550', error: 'FAILED: 550 mailbox unavailable', forced: false, sentAt: '2026-07-23T10:00:06' },
  ],
  timeline: [{ status: 'OPEN', at: '2026-07-23T10:00:00', by: 'N12345', byReporter: true }],
  comments: [
    { id: 1, author: 'someadmin', byReporter: false, body: 'internal thought', createdAt: '2026-07-23T11:00:00', internal: true, authorRole: 'ADMIN' },
    { id: 2, author: 'N12345', byReporter: true, body: 'user says more', createdAt: '2026-07-23T11:30:00', internal: false, authorRole: 'USER' },
  ],
}

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({
    getMe: vi.fn(async () => ({ success: true, username: 'someadmin', email: 'someadmin@example.com' })),
    admin: {
      getLoginIssues: vi.fn(),
      getLoginIssue: vi.fn(async () => ({ success: true, data: sampleDetail })),
      updateLoginIssueStatus: vi.fn(async (id, dto) => ({
        success: true, data: { ...sampleDetail, status: dto.status, resolutionNote: dto.resolutionNote ?? null, resolvedBy: dto.status === 'RESOLVED' ? 'someadmin' : null,
          timeline: [...sampleDetail.timeline, { status: dto.status, at: '2026-07-23T12:00:00', by: 'someadmin', byReporter: false }] },
      })),
      purgeLoginIssue: vi.fn(async () => ({ success: true })),
      addLoginIssueComment: vi.fn(async (id, dto) => ({
        success: true,
        data: { id: 9, author: 'someadmin', byReporter: false, body: dto.body, createdAt: '2026-07-23T12:00:00', internal: !!dto.internal, authorRole: 'ADMIN' },
      })),
    },
    issueReports: {
      mine: vi.fn(async () => ({ success: true, data: [], total: 3, counts: { OPEN: 0, IN_PROGRESS: 0, RESOLVED: 0 } })),
      mineDetail: vi.fn(async () => ({ success: true, data: { ...sampleDetail, comments: [] } })),
      addComment: vi.fn(async () => ({ success: true, data: {} })),
    },
  }),
  getRecentFailures: () => [],
}))

// Kalıcı silme AYRI yetki (issues.login-reports.purge / execute) — bayrağı ayrı, yoksa "yetkisi olmayan silme
// düğmesini görmez" iddiası sınanamazdı.
const perm = vi.hoisted(() => ({ allow: true, purge: true }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({
    canView: () => perm.allow, canEdit: () => perm.allow,
    canExecute: (r) => (r === 'issues.login-reports.purge' ? perm.purge : perm.allow),
    perms: {}, refresh: () => {},
  }),
}))
const mobile = vi.hoisted(() => ({ on: false }))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.on }))

import { api } from '../api/client'
import LoginIssueReports from '../components/admin/LoginIssueReports.jsx'

const listOk = (extra = {}) => ({ success: true, data: [sampleRow], total: 1, counts: { OPEN: 1, IN_PROGRESS: 0, RESOLVED: 1 }, ...extra })
function setUrl(qs) { window.history.replaceState({}, '', `/${qs ? '?' + qs : ''}`) }
async function openDetail() {
  fireEvent.click(await screen.findByText('LIR-2026-000005'))
  const dlg = await waitFor(() => { const d = document.querySelector('[data-slot="issue-detail"]'); expect(d).not.toBeNull(); return d })
  await within(dlg).findByText('Cannot login at all')
  return dlg
}

describe('Issue Reports — yönetici görünümü', () => {
  beforeEach(() => {
    vi.clearAllMocks(); perm.allow = true; perm.purge = true; mobile.on = false
    setUrl('tab=login-issues')
    api.admin.getLoginIssues.mockResolvedValue(listOk())
  })
  afterEach(() => setUrl(''))

  it('yönetici izni yoksa: kullanıcı görünümü (mine ucu), yönetici listesi çağrılmaz, görünüm anahtarı yok', async () => {
    perm.allow = false
    render(<LoginIssueReports />)
    expect(await screen.findByRole('heading', { name: 'Issue Reports', level: 2 })).toBeInTheDocument()
    await waitFor(() => expect(api.issueReports.mine).toHaveBeenCalled())
    expect(api.admin.getLoginIssues).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: 'All reports' })).toBeNull()
    expect(document.querySelector('[data-slot="issue-reports"]')).toHaveAttribute('data-audience', 'user')
  })

  it('liste: sunucu sayfası + sayaç kartları; bildiren sütunu; çözülme zamanı (Istanbul) durum altında', async () => {
    render(<LoginIssueReports />)
    expect(await screen.findByText('LIR-2026-000005')).toBeInTheDocument()
    expect(api.admin.getLoginIssues).toHaveBeenCalledWith(expect.objectContaining({ page: 0 }))
    const open = document.querySelector('[data-slot="stat-item"][data-key="OPEN"]')
    expect(open.textContent).toMatch(/1/)
    expect(screen.getByRole('columnheader', { name: 'Reporter' })).toBeInTheDocument()
    // 12:00 UTC → Europe/Istanbul 15:00
    expect(screen.getByText('2026-07-23 15:00')).toBeInTheDocument()
  })

  it('durum kartı sunucu süzgecidir (aria-pressed); "My reports" kartı görünümü değiştirir', async () => {
    render(<LoginIssueReports />)
    await screen.findByText('LIR-2026-000005')
    fireEvent.click(document.querySelector('[data-slot="stat-item"][data-key="OPEN"]'))
    await waitFor(() => expect(api.admin.getLoginIssues).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'OPEN', page: 0 })))
    expect(document.querySelector('[data-slot="stat-item"][data-key="OPEN"]')).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(document.querySelector('[data-slot="stat-item"][data-key="mine"]'))
    await waitFor(() => expect(document.querySelector('[data-slot="issue-reports"]')).toHaveAttribute('data-source', 'mine'))
    expect(screen.getByRole('button', { name: 'My reports' })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(screen.getByRole('button', { name: 'All reports' }))
    expect(await screen.findByText('LIR-2026-000005')).toBeInTheDocument()
  })

  it('arama: metin sunucuya q olarak gider; referans kodu kaydı doğrudan getirir (q gönderilmez)', async () => {
    render(<LoginIssueReports />)
    await screen.findByText('LIR-2026-000005')
    const search = screen.getByRole('searchbox', { name: /Search by reference or text/ })
    fireEvent.change(search, { target: { value: 'locked' } })
    fireEvent.keyDown(search, { key: 'Enter' })
    await waitFor(() => expect(api.admin.getLoginIssues).toHaveBeenLastCalledWith(expect.objectContaining({ q: 'locked' })))
    api.admin.getLoginIssues.mockClear()
    fireEvent.change(search, { target: { value: 'LIR-2026-000005' } })
    fireEvent.keyDown(search, { key: 'Enter' })
    await waitFor(() => expect(api.admin.getLoginIssue).toHaveBeenCalledWith(5))
    expect(api.admin.getLoginIssues).not.toHaveBeenCalled()
    expect(await screen.findByText('LIR-2026-000005')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="issues-count"]').textContent).toMatch(/Results: 1/)
  })

  it('sayfalama: total > boyut → sonraki sayfa page:1 ile istenir', async () => {
    api.admin.getLoginIssues.mockResolvedValue(listOk({ total: 245 }))
    render(<LoginIssueReports />)
    await screen.findByText('LIR-2026-000005')
    fireEvent.click(await screen.findByRole('button', { name: 'Next' }))
    await waitFor(() => expect(api.admin.getLoginIssues).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 })))
  })

  it('ayrıntı: bildiren + e-posta başlıkta; iç not "Internal" rozetli; bildirenin yorumu adıyla; açılış satırı bildiren adıyla', async () => {
    render(<LoginIssueReports />)
    const dlg = await openDetail()
    expect(api.admin.getLoginIssue).toHaveBeenCalledWith(5)
    const internal = dlg.querySelector('[data-slot="issue-comment"][data-internal="true"]')
    expect(within(internal).getByText('internal thought')).toBeInTheDocument()
    expect(within(internal).getByText('Internal')).toHaveAttribute('data-slot', 'internal-badge')
    const byUser = dlg.querySelector('[data-slot="issue-comment"][data-by-reporter="true"]')
    expect(within(byUser).getByText('N12345')).toBeInTheDocument()
    expect(within(dlg.querySelector('[data-slot="issue-conversation"]')).getByText(/Report opened by N12345/)).toBeInTheDocument()
  })

  it('çözüm notu zorunlu: boş notla "Confirm — Resolve" API çağırmaz, satır içi hata; notla doğru gövde', async () => {
    render(<LoginIssueReports />)
    const dlg = await openDetail()
    const actions = dlg.querySelector('[data-slot="issue-admin-actions"]')
    fireEvent.click(within(actions).getByRole('button', { name: 'Resolve' }))
    const panel = dlg.querySelector('[data-slot="issue-status-panel"]')
    fireEvent.click(within(panel).getByRole('button', { name: 'Confirm — Resolve' }))
    expect(await within(panel).findByText('A resolution note is required.')).toBeInTheDocument()
    expect(api.admin.updateLoginIssueStatus).not.toHaveBeenCalled()
    fireEvent.change(within(panel).getByRole('textbox', { name: /Resolution Note/ }), { target: { value: 'reset the account' } })
    fireEvent.click(within(panel).getByRole('button', { name: 'Confirm — Resolve' }))
    await waitFor(() => expect(api.admin.updateLoginIssueStatus).toHaveBeenCalledWith(5, { status: 'RESOLVED', resolutionNote: 'reset the account' }))
    await waitFor(() => expect(dlg.querySelector('[data-slot="issue-status"]')).toHaveAttribute('data-status', 'RESOLVED'))
    expect(within(dlg.querySelector('[data-slot="issue-admin-actions"]')).getByRole('button', { name: 'Reopen' })).toBeInTheDocument()
  })

  it('"Take In Progress": mevcut not önyüklenir ve gövdeyle gider (kaybolmaz)', async () => {
    api.admin.getLoginIssue.mockResolvedValueOnce({ success: true, data: { ...sampleDetail, resolutionNote: 'mevcut çalışma notu' } })
    render(<LoginIssueReports />)
    const dlg = await openDetail()
    fireEvent.click(within(dlg.querySelector('[data-slot="issue-admin-actions"]')).getByRole('button', { name: 'Take In Progress' }))
    const panel = dlg.querySelector('[data-slot="issue-status-panel"]')
    const ta = within(panel).getByRole('textbox')
    expect(ta.value).toBe('mevcut çalışma notu')
    fireEvent.change(ta, { target: { value: 'kullanıcıyla iletişime geçildi' } })
    fireEvent.click(within(panel).getByRole('button', { name: 'Confirm — Take In Progress' }))
    await waitFor(() => expect(api.admin.updateLoginIssueStatus).toHaveBeenCalledWith(5, { status: 'IN_PROGRESS', resolutionNote: 'kullanıcıyla iletişime geçildi' }))
  })

  it('yanıt: herkese açık (internal:false); "Internal note" anahtarıyla iç not (internal:true) — düğme adı değişir', async () => {
    render(<LoginIssueReports />)
    const dlg = await openDetail()
    const form = dlg.querySelector('[data-slot="issue-comment-form"]')
    const ta = within(form).getByRole('textbox', { name: 'Reply / note' })
    fireEvent.change(ta, { target: { value: 'We have unlocked the account' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Send reply' }))
    await waitFor(() => expect(api.admin.addLoginIssueComment).toHaveBeenCalledWith(5, { body: 'We have unlocked the account', internal: false }))
    expect(await within(dlg).findByText('We have unlocked the account')).toBeInTheDocument()
    fireEvent.click(within(form).getByRole('switch', { name: 'Internal note' }))
    fireEvent.change(ta, { target: { value: 'checking AD lockout policy' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Add internal note' }))
    await waitFor(() => expect(api.admin.addLoginIssueComment).toHaveBeenCalledWith(5, { body: 'checking AD lockout policy', internal: true }))
  })

  it('mail geçmişi (katlanır): tür + durum; satır açılınca gönderen/konu + gövde (cid → data-URL); zaman Istanbul', async () => {
    render(<LoginIssueReports />)
    const dlg = await openDetail()
    fireEvent.click(within(dlg).getByRole('button', { name: /Sent Emails/ }))
    expect(await within(dlg).findByText(/Admin notification/)).toBeInTheDocument()
    expect(within(dlg).getByText('Sent')).toBeInTheDocument()
    expect(within(dlg).getByText('Failed')).toBeInTheDocument()
    expect(within(dlg).getByText(/550 mailbox unavailable/)).toBeInTheDocument()
    expect(within(dlg).getAllByText('2026-07-23 13:00').length).toBeGreaterThan(0)
    fireEvent.click(within(dlg).getByText(/Admin notification/))
    expect(await within(dlg).findByText('Konu R')).toBeInTheDocument()
    const srcdoc = document.querySelector('iframe[title="mail-0"]').getAttribute('srcdoc')
    expect(srcdoc).toContain('rapor govdesi')
    expect(srcdoc).toContain('data:image/png;base64,AAAA')
    expect(srcdoc).not.toContain('cid:shot0')
  })

  it('kalıcı silme: yetkisi olmayana düğme ÇİZİLMEZ', async () => {
    perm.purge = false
    render(<LoginIssueReports />)
    const dlg = await openDetail()
    expect(within(dlg).queryByRole('button', { name: 'Delete permanently' })).toBeNull()
  })

  it('kalıcı silme ONAY ister (metin mailleri de söyler); onayda silinir, iptalde hiçbir şey silinmez', async () => {
    render(<LoginIssueReports />)
    const dlg = await openDetail()
    const confirmBox = () => waitFor(() => { const c = document.querySelector('[data-slot="alert-dialog-content"]'); expect(c).not.toBeNull(); return c })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Delete permanently' }))
    expect(api.admin.purgeLoginIssue).not.toHaveBeenCalled()
    let confirm = await confirmBox()
    expect(confirm.textContent).toMatch(/email/i)
    fireEvent.click(within(confirm).getByRole('button', { name: /^Cancel$/ }))
    await waitFor(() => expect(document.querySelector('[data-slot="alert-dialog-content"]')).toBeNull())
    expect(api.admin.purgeLoginIssue).not.toHaveBeenCalled()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Delete permanently' }))
    confirm = await confirmBox()
    fireEvent.click(within(confirm).getByRole('button', { name: /^Delete permanently$/ }))
    await waitFor(() => expect(api.admin.purgeLoginIssue).toHaveBeenCalledWith(5))
    await waitFor(() => expect(document.querySelector('[data-slot="issue-detail"]')).toBeNull())
  })

  it('imza gruplaması (triyaj): anahtar açılınca grup tablosu; satır en yeni kaydı açar', async () => {
    render(<LoginIssueReports />)
    await screen.findByText('LIR-2026-000005')
    fireEvent.click(screen.getByRole('button', { name: 'Group by Signature' }))
    const groups = await waitFor(() => { const g = document.querySelector('[data-slot="issue-groups"]'); expect(g).not.toBeNull(); return g })
    expect(within(groups).getByText('http <n> locked')).toBeInTheDocument()
    fireEvent.click(within(groups).getByText('http <n> locked'))
    await waitFor(() => expect(api.admin.getLoginIssue).toHaveBeenCalledWith(5))
  })

  it('derin bağlantı: ?ir_view=all&ir_id=5 yönetici ayrıntısını açar; yalnız ?ir_id=5 → "My reports" + kendi kaydı', async () => {
    setUrl('tab=login-issues&ir_view=all&ir_id=5')
    const { unmount } = render(<LoginIssueReports />)
    await waitFor(() => expect(api.admin.getLoginIssue).toHaveBeenCalledWith(5))
    unmount()
    vi.clearAllMocks()
    api.admin.getLoginIssues.mockResolvedValue(listOk())
    setUrl('tab=login-issues&ir_id=5')
    render(<LoginIssueReports />)
    await waitFor(() => expect(api.issueReports.mineDetail).toHaveBeenCalledWith(5))
    expect(screen.getByRole('button', { name: 'My reports' })).toHaveAttribute('aria-pressed', 'true')
    expect(api.admin.getLoginIssue).not.toHaveBeenCalled()
  })
})
