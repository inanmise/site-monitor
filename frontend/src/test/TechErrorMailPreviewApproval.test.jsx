import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within, fireEvent, waitFor } from './test-utils.jsx'
import { channel, overview, reportResponse, openAllSections } from './helpers/techErrorFixtures.js'

// E-posta önizlemesinden onay (A2, 2026-11-20): onay akışlı kanalda önizleme penceresinin altında "Onaya gönder"
// (düzenleyicinin kuralıyla — önizleme kapanır, istek sorusu düzenleyicide) ve PO için "Onayla" / "Reddet" (onay kartının
// karar penceresi önizlemenin ÜSTÜNDE; ret gerekçe ister; karar kaydedilince önizleme kapanır, rapor yeniden okunur;
// vazgeçince önizleme açık kalır). Akışsız kanalda bu düğmeler yok.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), reports: vi.fn(), template: vi.fn(), report: vi.fn(), saveReport: vi.fn(), send: vi.fn(),
      mailPreview: vi.fn(), mailPreviewTest: vi.fn(), requestApproval: vi.fn(), decideApproval: vi.fn(), deleteImage: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
    },
  }),
}))
vi.mock('../hooks/useElementHeight.js', () => ({ useElementHeight: () => [() => {}, 0] }))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'
import { clearMailPreviewShares } from '../components/techerror/mailPreviewFetch.js'

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'
const EXEC_CHANNEL = () => channel(10, 'Mobil', { approval_required: true, executive_count: 2, executive_emails: 'ceo@example.com, cto@example.com' })

function detail(extra = {}) {
  return {
    required: true, executive_count: 2, executive_emails: ['ceo@example.com', 'cto@example.com'],
    approvers: [{ name: 'Mehmet Kaya', has_email: true }], requested_by_name: 'Ayşe Yılmaz', decided_by_name: null,
    can_request: false, is_approver: false, can_decide: false, can_send_executive: false, ...extra,
  }
}
function withApproval({ approval = { state: 'none' }, det = detail() } = {}) {
  const base = reportResponse()
  base.data.channel = EXEC_CHANNEL()
  base.data.report = { ...base.data.report, approval }
  base.data.approval_detail = det
  return base
}
function preview() {
  return {
    success: true,
    data: {
      subject: TITLE, delivery: 'EXECUTIVE', to: ['ceo@example.com', 'cto@example.com'], cc: ['dijital@example.com'],
      to_count: 2, cc_count: 1, recipients_hidden: false, problem: null, file_name: 'mobil.pdf', images_embedded: 0, images_skipped: 0,
      provenance: null, pdf_bytes: 1000, max_mail_mb: 15, html: '<!doctype html><html><body>x</body></html>', text: 'x',
    },
  }
}

async function openEditor(resp) {
  te().report.mockResolvedValue(resp)
  render(<TechErrorReportsPage />)
  await screen.findByRole('heading', { name: TITLE })
}
async function openPreview() {
  fireEvent.click(screen.getByRole('button', { name: 'E-postayı önizle' }))
  const dlg = await screen.findByRole('dialog', { name: 'E-posta önizlemesi' })
  await waitFor(() => expect(dlg.querySelector('[data-slot="te-mail-body"] > [role="status"]')).toBeNull())
  return dlg
}
const decideDialog = () => document.querySelector('[data-slot="te-approval-dialog"]')

beforeEach(() => {
  vi.clearAllMocks()
  clearMailPreviewShares()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  openAllSections()
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  te().overview.mockResolvedValue(overview([EXEC_CHANNEL()]))
  te().reports.mockResolvedValue({ success: true, data: { channel: EXEC_CHANNEL(), reports: [], default_period: { year: 2026, month: 9 } } })
  te().mailPreview.mockResolvedValue(preview())
})

describe('e-posta önizlemesinden onay (A2)', () => {
  it('onaya gönderebilen kullanıcı: "Onaya gönder" önizlemeyi kapatır, düzenleyicinin sorusunu açar, istek gider', async () => {
    te().requestApproval.mockResolvedValue({ success: true, data: { state: 'pending', mailed: 1, failed: 0, mail_problem: null } })
    await openEditor(withApproval({ det: detail({ can_request: true }) }))
    const dlg = await openPreview()
    expect(dlg.querySelector('[data-slot="te-mail-approve"]')).toBeNull()
    expect(dlg.querySelector('[data-slot="te-mail-reject"]')).toBeNull()
    const req = dlg.querySelector('[data-slot="te-mail-request-approval"]')
    expect(req).toHaveTextContent('Onaya gönder')
    fireEvent.click(req)
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'E-posta önizlemesi' })).toBeNull())
    const confirm = await screen.findByRole('dialog', { name: 'Onaya gönder' })
    fireEvent.click(within(confirm).getByRole('button', { name: 'Onaya gönder' }))
    await waitFor(() => expect(te().requestApproval).toHaveBeenCalledWith('100'))
    await waitFor(() => expect(te().report.mock.calls.length).toBeGreaterThanOrEqual(2))
  })

  it('PO: "Reddet" karar penceresini önizlemenin üstünde açar; gerekçesiz gitmez; kayıttan sonra önizleme kapanır, rapor yeniden okunur', async () => {
    te().decideApproval.mockResolvedValue({ success: true, data: { state: 'rejected', send_state: null, sent_to: [] } })
    await openEditor(withApproval({ approval: { state: 'pending', requested_at: '2026-10-13T09:00:00Z' },
      det: detail({ is_approver: true, can_decide: true }) }))
    const before = te().report.mock.calls.length
    const dlg = await openPreview()
    expect(dlg.querySelector('[data-slot="te-mail-request-approval"]')).toBeNull()
    fireEvent.click(dlg.querySelector('[data-slot="te-mail-reject"]'))
    const decide = await waitFor(() => { const d = decideDialog(); if (!d) throw new Error('karar penceresi'); return d })
    expect(within(decide).getByRole('heading', { name: 'Raporu reddet' })).toBeInTheDocument()
    fireEvent.click(within(decide).getByRole('button', { name: 'Reddet' }))
    expect(within(decide).getByText('Reddetmek için gerekçe yazın.')).toBeInTheDocument()
    expect(te().decideApproval).not.toHaveBeenCalled()
    fireEvent.change(within(decide).getByRole('textbox'), { target: { value: 'Rakamlar eksik' } })
    fireEvent.click(within(decide).getByRole('button', { name: 'Reddet' }))
    await waitFor(() => expect(te().decideApproval).toHaveBeenCalledWith('100', { decision: 'reject', note: 'Rakamlar eksik' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'E-posta önizlemesi' })).toBeNull())
    await waitFor(() => expect(te().report.mock.calls.length).toBeGreaterThan(before))
    expect(decideDialog()).toBeNull()
  })

  it('PO: karar penceresinde vazgeçmek önizlemeyi açık bırakır; onay notsuz gider', async () => {
    te().decideApproval.mockResolvedValue({ success: true, data: { state: 'approved', send_state: 'SENT', sent_to: ['ceo@example.com'] } })
    await openEditor(withApproval({ approval: { state: 'pending', requested_at: '2026-10-13T09:00:00Z' },
      det: detail({ is_approver: true, can_decide: true }) }))
    const dlg = await openPreview()
    fireEvent.click(dlg.querySelector('[data-slot="te-mail-approve"]'))
    let decide = await waitFor(() => { const d = decideDialog(); if (!d) throw new Error('karar penceresi'); return d })
    fireEvent.click(within(decide).getByRole('button', { name: 'Vazgeç' }))
    await waitFor(() => expect(decideDialog()).toBeNull())
    expect(screen.getByRole('dialog', { name: 'E-posta önizlemesi' })).toBeInTheDocument()
    expect(te().decideApproval).not.toHaveBeenCalled()

    fireEvent.click(dlg.querySelector('[data-slot="te-mail-approve"]'))
    decide = await waitFor(() => { const d = decideDialog(); if (!d) throw new Error('karar penceresi'); return d })
    expect(within(decide).getByRole('textbox')).toHaveValue('')
    fireEvent.click(within(decide).getByRole('button', { name: 'Onayla' }))
    await waitFor(() => expect(te().decideApproval).toHaveBeenCalledWith('100', { decision: 'approve', note: null }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'E-posta önizlemesi' })).toBeNull())
  })

  it('karar kaydedilemezse (alan dışı hata) önizleme açık kalır, rapor yine yeniden okunur', async () => {
    te().decideApproval.mockResolvedValue({ success: false, code: 'APPROVAL_STALE', error: 'Rapor istekten sonra değişti; yeniden onaya gönderilmeli.' })
    await openEditor(withApproval({ approval: { state: 'pending', requested_at: '2026-10-13T09:00:00Z' },
      det: detail({ is_approver: true, can_decide: true }) }))
    const before = te().report.mock.calls.length
    const dlg = await openPreview()
    fireEvent.click(dlg.querySelector('[data-slot="te-mail-approve"]'))
    const decide = await waitFor(() => { const d = decideDialog(); if (!d) throw new Error('karar penceresi'); return d })
    fireEvent.click(within(decide).getByRole('button', { name: 'Onayla' }))
    await waitFor(() => expect(te().decideApproval).toHaveBeenCalled())
    await waitFor(() => expect(decideDialog()).toBeNull())
    await waitFor(() => expect(te().report.mock.calls.length).toBeGreaterThan(before))
    expect(screen.getByRole('dialog', { name: 'E-posta önizlemesi' })).toBeInTheDocument()
  })

  it('akışsız kanal: önizlemede onay düğmesi yok; onaylayıcı olmayan izleyici karar düğmesi görmez', async () => {
    await openEditor(reportResponse())
    const dlg = await openPreview()
    for (const s of ['te-mail-approve', 'te-mail-reject', 'te-mail-request-approval']) expect(dlg.querySelector(`[data-slot="${s}"]`), s).toBeNull()
  })

  it('onay bekleyen raporu PO olmayan biri önizlerse karar ve istek düğmesi yok', async () => {
    await openEditor(withApproval({ approval: { state: 'pending', requested_at: '2026-10-13T09:00:00Z' }, det: detail() }))
    const dlg = await openPreview()
    for (const s of ['te-mail-approve', 'te-mail-reject', 'te-mail-request-approval']) expect(dlg.querySelector(`[data-slot="${s}"]`), s).toBeNull()
  })
})
