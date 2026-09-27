import { StrictMode } from 'react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from './test-utils'

/**
 * 2026-09-27 yayın öncesi regresyon taraması — Sorun Bildirimleri fetch yarışları (release-fixes.md FRONTEND A #1, #7).
 *  YÜKSEK  IssuesBoard.load seq korumasını yeniden tasarımda kaybetmişti (HEAD'deki LoginIssueReports `loadSeq`
 *          taşıyordu): geç dönen ESKİ süzgeç yanıtı yeni süzgecin satırlarını ezip, uçuştaki isteğin spinner'ını söndürüyordu.
 *  DÜŞÜK   `openRequest` pano `key={source}` ile yeniden kurulunca yeniden oynatılıyordu (kapatılan kayıt geri açılıyor).
 *  DÜŞÜK   IssueDetailSheet unmount sonrası dönen yükleme yanıtında onClose çağırıyordu (başka kaydın Sheet'ini kapatır).
 * Denetimli promise'lerle belirlenimci: eski istek YENİSİNDEN SONRA çözülür.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({
    getMe: vi.fn(async () => ({ success: true, username: 'someadmin' })),
    admin: { getLoginIssues: vi.fn(), getLoginIssue: vi.fn() },
    issueReports: { mine: vi.fn(), mineDetail: vi.fn() },
  }),
  getRecentFailures: () => [],
}))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ canView: () => true, canEdit: () => true, canExecute: () => true, perms: {}, refresh: () => {} }),
}))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => false }))

import { api } from '../api/client'
import IssueReportsPage from '../components/issues/IssueReportsPage.jsx'
import IssueDetailSheet from '../components/issues/IssueDetailSheet.jsx'

const row = (id, over = {}) => ({
  id, refCode: `LIR-2026-00000${id}`, username: 'N12345', messageSummary: `summary ${id}`, ipAddress: '1.2.3.4',
  reportedAt: '2026-07-23T10:00:00', status: 'OPEN', imageCount: 0, source: 'LOGIN', lastActivityAt: '2026-07-23T10:00:00', ...over,
})
const detail = (id) => ({ ...row(id), message: `message ${id}`, errorText: '', timeline: [], comments: [], mailHistory: [], images: [] })
const deferred = () => { let resolve, reject; const p = new Promise((res, rej) => { resolve = res; reject = rej }); return { p, resolve, reject } }
const flush = () => act(() => new Promise((r) => setTimeout(r, 0)))
const refreshBtn = () => screen.getByRole('button', { name: /^(Refresh|Yenile)$/ })
function setUrl(qs) { window.history.replaceState({}, '', `/${qs ? '?' + qs : ''}`) }

describe('IssueReportsPage — fetch yarışları', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setUrl('tab=login-issues')
    api.issueReports.mine.mockResolvedValue({ success: true, data: [], total: 0, counts: {} })
    api.admin.getLoginIssue.mockImplementation(async (id) => ({ success: true, data: detail(id) }))
    api.issueReports.mineDetail.mockImplementation(async (id) => ({ success: true, data: detail(id) }))
  })
  afterEach(() => setUrl(''))

  it('YÜKSEK: süzgeç değişince geç dönen ESKİ yanıt yeni süzgecin satırlarını ezmez; spinner uçuştaki isteği yansıtır', async () => {
    const all = deferred(), open = deferred()
    api.admin.getLoginIssues.mockImplementation((p) => (p?.status === 'OPEN' ? open.p : all.p))
    render(<IssueReportsPage adminAudience canEdit canPurge />)
    await waitFor(() => expect(api.admin.getLoginIssues).toHaveBeenCalled())

    // Kullanıcı ilk yanıt gelmeden "Open" kartına basar → yeni (dar) istek.
    fireEvent.click(document.querySelector('[data-slot="stat-item"][data-key="OPEN"]'))
    await waitFor(() => expect(api.admin.getLoginIssues).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'OPEN' })))

    // ESKİ (geniş) yanıt ÖNCE döner: yeni istek hâlâ uçuşta → spinner SÖNMEMELİ, eski satırlar çizilmemeli.
    await act(async () => { all.resolve({ success: true, data: [row(1), row(2, { status: 'RESOLVED' })], total: 2, counts: { OPEN: 1, RESOLVED: 1 } }) })
    await flush()
    expect(refreshBtn()).toHaveAttribute('aria-busy', 'true')
    expect(screen.queryByText('LIR-2026-000002')).toBeNull()

    // Yeni yanıt döner → yalnız onun satırları.
    await act(async () => { open.resolve({ success: true, data: [row(3)], total: 1, counts: { OPEN: 1 } }) })
    expect(await screen.findByText('LIR-2026-000003')).toBeInTheDocument()
    expect(screen.queryByText('LIR-2026-000001')).toBeNull()
    expect(refreshBtn()).not.toHaveAttribute('aria-busy')
  })

  it('YÜKSEK: yeni yanıt önce, ESKİ yanıt sonra dönerse ekranda yeni süzgecin sonucu KALIR', async () => {
    const all = deferred(), open = deferred()
    api.admin.getLoginIssues.mockImplementation((p) => (p?.status === 'OPEN' ? open.p : all.p))
    render(<IssueReportsPage adminAudience canEdit canPurge />)
    await waitFor(() => expect(api.admin.getLoginIssues).toHaveBeenCalled())
    fireEvent.click(document.querySelector('[data-slot="stat-item"][data-key="OPEN"]'))
    await waitFor(() => expect(api.admin.getLoginIssues).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'OPEN' })))

    await act(async () => { open.resolve({ success: true, data: [row(3)], total: 1, counts: { OPEN: 1 } }) })
    expect(await screen.findByText('LIR-2026-000003')).toBeInTheDocument()
    await act(async () => { all.resolve({ success: true, data: [row(1), row(2)], total: 2, counts: { OPEN: 2 } }) })
    await flush()
    expect(screen.getByText('LIR-2026-000003')).toBeInTheDocument()
    expect(screen.queryByText('LIR-2026-000001')).toBeNull()
    expect(document.querySelector('[data-slot="issues-count"]').textContent).toMatch(/1/)
  })

  it('DÜŞÜK: sekme-içi derin bağlantı TÜKETİLİR — görünüm değişince (pano yeniden kurulur) kapatılan kayıt yeniden açılmaz', async () => {
    api.admin.getLoginIssues.mockResolvedValue({ success: true, data: [row(5)], total: 1, counts: { OPEN: 1 } })
    render(<IssueReportsPage adminAudience canEdit canPurge />)
    await screen.findByText('LIR-2026-000005')

    act(() => { window.dispatchEvent(new CustomEvent('sm:tab-params', { detail: { ir_id: '5', ir_view: 'all' } })) })
    await waitFor(() => expect(document.querySelector('[data-slot="issue-detail"]')).not.toBeNull())
    // Kullanıcı kaydı kapatır.
    fireEvent.click(screen.getAllByRole('button', { name: /^(Close|Kapat)$/ })[0])
    await waitFor(() => expect(document.querySelector('[data-slot="issue-detail"]')).toBeNull())

    // "Bildirimlerim" görünümüne geçer → pano key={source} ile yeniden kurulur.
    fireEvent.click(screen.getByRole('button', { name: /^(My reports|Bildirimlerim)$/ }))
    await waitFor(() => expect(document.querySelector('[data-slot="issue-reports"]')).toHaveAttribute('data-source', 'mine'))
    await flush()
    expect(document.querySelector('[data-slot="issue-detail"]')).toBeNull()
    expect(api.issueReports.mineDetail).not.toHaveBeenCalled()
  })
})

describe('IssueDetailSheet — unmount sonrası yanıt', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('StrictMode (kur → temizle → kur): yaşam bayrağı KURULUMDA da true → yanıt uygulanır, pencere "ölü" kalmaz', async () => {
    api.admin.getLoginIssue.mockImplementation(async (id) => ({ success: true, data: detail(id) }))
    render(<StrictMode><IssueDetailSheet id={7} source="admin" onClose={vi.fn()} /></StrictMode>)
    expect(await screen.findByText('message 7')).toBeInTheDocument()
  })

  it('DÜŞÜK: kapandıktan sonra dönen HATA yanıtı onClose çağırmaz (başka kaydın penceresini kapatmaz)', async () => {
    const d = deferred()
    api.admin.getLoginIssue.mockReturnValue(d.p)
    const onClose = vi.fn(), onChanged = vi.fn()
    const { unmount } = render(<IssueDetailSheet id={7} source="admin" onClose={onClose} onChanged={onChanged} />)
    await waitFor(() => expect(api.admin.getLoginIssue).toHaveBeenCalledWith(7))
    unmount()
    await act(async () => { d.resolve({ success: false, error: 'not found' }) })
    await flush()
    expect(onClose).not.toHaveBeenCalled()
  })

  it('DÜŞÜK: kapandıktan sonra REDDEDİLEN yükleme onClose çağırmaz; "mine" başarı yanıtı da onChanged çağırmaz', async () => {
    const d = deferred()
    api.admin.getLoginIssue.mockReturnValue(d.p)
    const onClose = vi.fn()
    const first = render(<IssueDetailSheet id={7} source="admin" onClose={onClose} />)
    await waitFor(() => expect(api.admin.getLoginIssue).toHaveBeenCalledWith(7))
    first.unmount()
    await act(async () => { d.reject(new Error('Failed to fetch')) })
    await flush()
    expect(onClose).not.toHaveBeenCalled()

    const m = deferred()
    api.issueReports.mineDetail.mockReturnValue(m.p)
    const onChanged = vi.fn()
    const second = render(<IssueDetailSheet id={8} source="mine" onClose={onClose} onChanged={onChanged} />)
    await waitFor(() => expect(api.issueReports.mineDetail).toHaveBeenCalledWith(8))
    second.unmount()
    await act(async () => { m.resolve({ success: true, data: detail(8) }) })
    await flush()
    expect(onChanged).not.toHaveBeenCalled()
  })
})
