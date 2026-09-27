import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils'

// Sorun Bildirimleri — KULLANICI kitlesi (2026-09-27 yeniden tasarım): kendi kayıtları, istemci süzgeçleri, ayrıntı
// Sheet'i (adımlar, galeri, konuşma), yazıcı (Ctrl+Enter, yeniden açma), derin bağlantı, telefon kartları + süzgeç Sheet'i.

const rows = [
  { id: 5, refCode: 'LIR-2026-000005', status: 'IN_PROGRESS', source: 'USER_REPORT', category: 'BLOCKER',
    reportedAt: '2026-09-20T10:00:00', messageSummary: 'Export fails', imageCount: 2,
    lastActivityAt: '2026-09-25T10:00:00', commentCount: 1, unread: true },
  { id: 6, refCode: 'LIR-2026-000006', status: 'RESOLVED', source: 'CLIENT_ERROR', category: null,
    reportedAt: '2026-09-22T10:00:00', resolvedAt: '2026-09-23T10:00:00', messageSummary: 'Crash on dashboard', imageCount: 0,
    lastActivityAt: '2026-09-23T10:00:00', commentCount: 0, unread: false },
]
const detail5 = {
  id: 5, refCode: 'LIR-2026-000005', status: 'IN_PROGRESS', source: 'USER_REPORT', category: 'BLOCKER',
  reportedAt: '2026-09-20T10:00:00', lastActivityAt: '2026-09-25T10:00:00',
  message: 'Export fails when the report has Turkish characters', errorText: 'Error: export failed (500)',
  images: ['data:image/png;base64,AAAA', 'data:image/png;base64,BBBB'], imageCount: 2,
  timeline: [
    { status: 'OPEN', at: '2026-09-20T10:00:00', by: 'kullanici.x', byReporter: true },
    { status: 'IN_PROGRESS', at: '2026-09-21T10:00:00', by: 'someadmin', byReporter: false },
  ],
  comments: [{ id: 1, author: 'someadmin', byReporter: false, body: 'We are looking into it', createdAt: '2026-09-21T10:05:00' }],
}
const detail6 = {
  id: 6, refCode: 'LIR-2026-000006', status: 'RESOLVED', source: 'CLIENT_ERROR', category: null,
  reportedAt: '2026-09-22T10:00:00', lastActivityAt: '2026-09-23T10:00:00', message: 'Crash on dashboard', errorText: null,
  images: [], imageCount: 0, resolutionNote: 'Fixed in 20.86.1', resolvedAt: '2026-09-23T10:00:00', resolvedBy: 'someadmin',
  timeline: [
    { status: 'OPEN', at: '2026-09-22T10:00:00', by: 'kullanici.x', byReporter: true },
    { status: 'RESOLVED', at: '2026-09-23T10:00:00', by: 'someadmin', byReporter: false },
  ],
  comments: [],
}

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({
    getMe: vi.fn(async () => ({ success: true, username: 'kullanici.x', email: 'kullanici.x@example.com' })),
    issueReports: {
      mine: vi.fn(),
      mineDetail: vi.fn(async (id) => ({ success: true, data: id === 6 ? detail6 : detail5 })),
      addComment: vi.fn(async (id, body) => ({
        success: true,
        data: { comment: { id: 9, author: 'kullanici.x', byReporter: true, body, createdAt: '2026-09-26T09:00:00' }, reopened: id === 6, status: 'IN_PROGRESS' },
      })),
    },
  }),
  getRecentFailures: () => [],
}))
const mobile = vi.hoisted(() => ({ on: false }))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.on }))

import { api } from '../api/client'
import MyIssueReports from '../components/MyIssueReports.jsx'

const ok = (data = rows, extra = {}) => ({ success: true, data, total: data.length, counts: { OPEN: 0, IN_PROGRESS: 1, RESOLVED: 1 }, ...extra })
function setUrl(qs) { window.history.replaceState({}, '', `/${qs ? '?' + qs : ''}`) }
const rowOf = (ref) => screen.getByText(ref).closest('[data-slot="issue-row"]')
async function openRow(ref) {
  fireEvent.click(await screen.findByText(ref))
  const dlg = await waitFor(() => { const d = document.querySelector('[data-slot="issue-detail"]'); expect(d).not.toBeNull(); return d })
  await within(dlg).findByRole('heading', { name: ref })
  return dlg
}

describe('Issue Reports — kullanıcı görünümü', () => {
  beforeEach(() => { vi.clearAllMocks(); mobile.on = false; setUrl('tab=login-issues'); api.issueReports.mine.mockResolvedValue(ok()) })
  afterEach(() => { setUrl('') })

  it('PageHeader (başlık, amaç, meta çipleri, Yenile + birincil "Report a problem") ve liste; okunmamış satır işaretli', async () => {
    render(<MyIssueReports />)
    expect(await screen.findByText('LIR-2026-000005')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Issue Reports', level: 2 })).toBeInTheDocument()
    const header = document.querySelector('[data-slot="page-header"]')
    expect(within(header).getByText('New replies: 1')).toBeInTheDocument()
    expect(within(header).getByText('1 unresolved')).toBeInTheDocument()
    const actions = header.querySelector('[data-slot="page-actions"]')
    const btns = within(actions).getAllByRole('button')
    expect(btns.map((b) => b.textContent)).toEqual(['Refresh', 'Report a problem'])   // birincil EN SAĞDA
    expect(api.issueReports.mine).toHaveBeenCalledWith({ page: 0, size: 200 })
    expect(rowOf('LIR-2026-000005')).toHaveAttribute('data-unread', 'true')
    expect(within(rowOf('LIR-2026-000005')).getByText('New reply')).toBeInTheDocument()   // renk tek başına bilgi taşımaz
    expect(rowOf('LIR-2026-000006')).not.toHaveAttribute('data-unread')
    expect(screen.getByRole('table')).toBeInTheDocument()
  })

  it('"New replies" kartı süzgeçtir (aria-pressed) — yalnız okunmamışlar; çip × ile kalkar', async () => {
    render(<MyIssueReports />)
    await screen.findByText('LIR-2026-000006')
    const tile = document.querySelector('[data-slot="stat-item"][data-key="awaiting"]')
    expect(tile).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(tile)
    await waitFor(() => expect(screen.queryByText('LIR-2026-000006')).toBeNull())
    expect(tile).toHaveAttribute('aria-pressed', 'true')
    const chips = document.querySelector('[data-slot="active-filters"]')
    fireEvent.click(within(chips).getByRole('button', { name: 'Remove filter: New replies' }))
    expect(await screen.findByText('LIR-2026-000006')).toBeInTheDocument()
  })

  it('arama referans koduyla ve metinle (istemci tarafı, kesin); sonuç sayısı görünür; boş sonuçta "Clear all"', async () => {
    render(<MyIssueReports />)
    await screen.findByText('LIR-2026-000005')
    const search = screen.getByRole('searchbox', { name: /Search by reference or text/ })
    fireEvent.change(search, { target: { value: 'LIR-2026-000006' } })
    fireEvent.keyDown(search, { key: 'Enter' })
    await waitFor(() => expect(screen.queryByText('LIR-2026-000005')).toBeNull())
    expect(screen.getByText('LIR-2026-000006')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="issues-count"]').textContent).toBe('Results: 1')
    fireEvent.change(search, { target: { value: 'zzz yok' } })
    fireEvent.keyDown(search, { key: 'Enter' })
    expect(await screen.findByText('No reports match these filters')).toBeInTheDocument()
    fireEvent.click(within(document.querySelector('[data-slot="empty"]')).getByRole('button', { name: /Clear all/ }))
    expect(await screen.findByText('LIR-2026-000005')).toBeInTheDocument()
  })

  it('kaynak süzgeci + sıralama (bildirim tarihi) istemcide uygulanır', async () => {
    render(<MyIssueReports />)
    await screen.findByText('LIR-2026-000005')
    const order = () => [...document.querySelectorAll('[data-slot="issue-row"]')].map((r) => r.getAttribute('data-issue-id'))
    expect(order()).toEqual(['5', '6'])   // son etkinlik
    fireEvent.change(screen.getByRole('combobox', { name: 'Sort by' }), { target: { value: 'reported' } })
    await waitFor(() => expect(order()).toEqual(['6', '5']))
    fireEvent.change(screen.getByRole('combobox', { name: 'Source' }), { target: { value: 'CLIENT_ERROR' } })
    await waitFor(() => expect(order()).toEqual(['6']))
  })

  it('satır → ayrıntı Sheet: adımlar, açıklama, hata metni (kopyala), galeri → ışık kutusu (←/→), konuşma (iç not YOK)', async () => {
    render(<MyIssueReports />)
    const dlg = await openRow('LIR-2026-000005')
    expect(api.issueReports.mineDetail).toHaveBeenCalledWith(5)
    const steps = dlg.querySelectorAll('[data-slot="issue-stepper"] [data-step]')
    expect([...steps].map((s) => s.getAttribute('data-state'))).toEqual(['done', 'current', 'todo'])
    expect(within(dlg).getByText('Export fails when the report has Turkish characters')).toBeInTheDocument()
    expect(dlg.querySelector('[data-slot="issue-error-text"]').textContent).toBe('Error: export failed (500)')
    expect(within(dlg).getByRole('button', { name: 'Copy error text' })).toBeInTheDocument()
    // Konuşma: yönetici yanıtı solda, "iç not" rozeti hiç yok; açılış sistem satırı
    const conv = dlg.querySelector('[data-slot="issue-conversation"]')
    expect(within(conv).getByText('We are looking into it')).toBeInTheDocument()
    expect(within(conv).getByText(/Report opened by you/)).toBeInTheDocument()
    expect(dlg.querySelector('[data-slot="internal-badge"]')).toBeNull()
    // Galeri → ışık kutusu, ← / → ile gezinme
    const thumbs = within(dlg.querySelector('[data-slot="issue-gallery"]')).getAllByRole('button')
    expect(thumbs).toHaveLength(2)
    fireEvent.click(thumbs[0])
    const box = await waitFor(() => { const b = document.querySelector('[data-slot="issue-lightbox"]'); expect(b).not.toBeNull(); return b })
    expect(box).toHaveAttribute('data-index', '0')
    fireEvent.keyDown(window, { key: 'ArrowRight' })
    await waitFor(() => expect(document.querySelector('[data-slot="issue-lightbox"]')).toHaveAttribute('data-index', '1'))
    // Açılış "görüldü" damgası vurur → liste tazelenir
    await waitFor(() => expect(api.issueReports.mine.mock.calls.length).toBeGreaterThanOrEqual(2))
  })

  it('yazıcı: boşken Gönder kapalı, 4000 sayaç, Ctrl+Enter gönderir; yorum sağda ("You") belirir', async () => {
    render(<MyIssueReports />)
    const dlg = await openRow('LIR-2026-000005')
    const form = dlg.querySelector('[data-slot="issue-comment-form"]')
    const send = within(form).getByRole('button', { name: 'Send' })
    expect(send).toBeDisabled()
    const ta = within(form).getByRole('textbox', { name: 'Your comment' })
    expect(ta).toHaveAttribute('maxlength', '4000')
    fireEvent.change(ta, { target: { value: 'Still failing' } })
    expect(within(form).getByText('13 / 4000')).toBeInTheDocument()
    fireEvent.keyDown(ta, { key: 'Enter', ctrlKey: true })
    await waitFor(() => expect(api.issueReports.addComment).toHaveBeenCalledWith(5, 'Still failing'))
    const mine = await waitFor(() => { const m = dlg.querySelector('[data-slot="issue-comment"][data-by-reporter="true"]'); expect(m).not.toBeNull(); return m })
    expect(within(mine).getByText('Still failing')).toBeInTheDocument()
    expect(within(mine).getByText('You')).toBeInTheDocument()
    expect(ta.value).toBe('')
  })

  it('çözülmüş kayıt: çözüm notu + "gönderince yeniden açılır" uyarısı; "Send and reopen" → durum In Progress + "Reopened by you"', async () => {
    render(<MyIssueReports />)
    const dlg = await openRow('LIR-2026-000006')
    expect(within(dlg).getByText('Fixed in 20.86.1')).toBeInTheDocument()
    expect(within(dlg).getByText(/the report will be reopened/i)).toBeInTheDocument()
    const form = dlg.querySelector('[data-slot="issue-comment-form"]')
    fireEvent.change(within(form).getByRole('textbox'), { target: { value: 'It broke again' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Send and reopen' }))
    await waitFor(() => expect(api.issueReports.addComment).toHaveBeenCalledWith(6, 'It broke again'))
    await waitFor(() => expect(dlg.querySelector('[data-slot="issue-status"]')).toHaveAttribute('data-status', 'IN_PROGRESS'))
    expect(within(dlg.querySelector('[data-slot="issue-stepper"]')).getByText(/Reopened by you/)).toBeInTheDocument()
    expect(await screen.findByText(/reopened and the team notified/i)).toBeInTheDocument()
  })

  it('derin bağlantı: ?ir_id=6 açılışta; sekme açıkken sm:tab-params başka kaydı açar', async () => {
    setUrl('tab=login-issues&ir_id=6')
    render(<MyIssueReports />)
    await waitFor(() => expect(api.issueReports.mineDetail).toHaveBeenCalledWith(6))
    window.dispatchEvent(new CustomEvent('sm:tab-params', { detail: { ir_id: 5 } }))
    await waitFor(() => expect(api.issueReports.mineDetail).toHaveBeenCalledWith(5))
  })

  it('telefon: kart yığını (stretched button) + "Filters (n)" alt Sheet; karttan ayrıntı açılır', async () => {
    mobile.on = true
    render(<MyIssueReports />)
    await screen.findByText('LIR-2026-000005')
    expect(screen.queryByRole('table')).toBeNull()
    const card = screen.getByRole('button', { name: 'Open report LIR-2026-000005' })
    expect(card.closest('[data-slot="issue-card"]')).toHaveAttribute('data-unread', 'true')
    fireEvent.click(screen.getByRole('button', { name: /^Filters/ }))
    const sheet = await waitFor(() => { const s = document.querySelector('[data-slot="issue-filters-sheet"]'); expect(s).not.toBeNull(); return s })
    fireEvent.change(within(sheet).getByRole('combobox', { name: 'Status' }), { target: { value: 'RESOLVED' } })
    expect(within(sheet).getByRole('button', { name: 'Show results (1)' })).toBeInTheDocument()
    fireEvent.click(within(sheet).getByRole('button', { name: 'Show results (1)' }))
    await waitFor(() => expect(screen.queryByText('LIR-2026-000005')).toBeNull())
    expect(screen.getByRole('button', { name: /^Filters/ }).querySelector('[data-slot="filter-count"]').textContent).toBe('1')
    fireEvent.click(screen.getByRole('button', { name: 'Open report LIR-2026-000006' }))
    await waitFor(() => expect(api.issueReports.mineDetail).toHaveBeenCalledWith(6))
  })

  it('boş: "You have not reported any issues yet." + "Report a problem" düğmesi rapor penceresini açar', async () => {
    api.issueReports.mine.mockResolvedValue(ok([]))
    render(<MyIssueReports />)
    const empty = await waitFor(() => { const e = document.querySelector('[data-slot="empty"]'); expect(e).not.toBeNull(); return e })
    expect(within(empty).getByText('You have not reported any issues yet.')).toBeInTheDocument()
    fireEvent.click(within(empty).getByRole('button', { name: /Report a problem/ }))
    expect(await screen.findByRole('dialog', { name: /Report a Problem/ })).toBeInTheDocument()
  })

  it('yükleme hatası: uyarı + "Try again" yeniden yükler', async () => {
    api.issueReports.mine.mockRejectedValueOnce(new Error('network down'))
    render(<MyIssueReports />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('network down')
    fireEvent.click(within(alert).getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('LIR-2026-000005')).toBeInTheDocument()
  })

  it('istemci sayfalama: 60 kayıt → standart çubuk; sonraki sayfa kalan kayıtları gösterir', async () => {
    const many = Array.from({ length: 60 }, (_, i) => ({ ...rows[1], id: 100 + i, refCode: `LIR-2026-${String(100 + i).padStart(6, '0')}`,
      lastActivityAt: `2026-09-${String(1 + (i % 28)).padStart(2, '0')}T10:00:00`, unread: false }))
    api.issueReports.mine.mockResolvedValue(ok(many))
    render(<MyIssueReports />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="issue-row"]').length).toBe(50))
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => expect(document.querySelectorAll('[data-slot="issue-row"]').length).toBe(10))
  })
})
