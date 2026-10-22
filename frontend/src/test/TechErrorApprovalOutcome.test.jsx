import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import { channel, overview, reportResponse, openAllSections as openAll } from './helpers/techErrorFixtures.js'

// PKG-8b (2026-10-22): UIA-E1 onay sonucu cümleleri, UIA-E2 paylaşımla okuyana onaylayıcı sayısı, UIA-E3 onay isteği
// e-posta sorunu cümleleri, UIA-E4 onay akışlı kanalda "takım e-postası yok" uyarısı yok, UIA-E7 pasif kanal boş liste.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), template: vi.fn(), reports: vi.fn(), report: vi.fn(), saveReport: vi.fn(), send: vi.fn(),
      requestApproval: vi.fn(), decideApproval: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'
import ChannelView from '../components/techerror/ChannelView.jsx'
import ApprovalCard, { approvalOutcome, approversLine } from '../components/techerror/ApprovalCard.jsx'
import { requestNoMailText } from '../components/techerror/ReportEditor.jsx'

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'
const tOf = (dict) => (key, ...args) => {
  let s = dict[key] ?? key
  args.forEach((a, i) => { s = s.split(`{${i}}`).join(String(a ?? '')) })
  return s
}
const t = tOf(TR)
const CODE = /\b[A-Z][A-Z0-9]*_[A-Z0-9_]+\b/

function detail(extra = {}) {
  return {
    required: true, executive_count: 2, executive_emails: ['ceo@example.com'],
    approvers: [{ name: 'Mehmet Kaya', has_email: true }], can_request: false, is_approver: true, can_decide: true, can_send_executive: false,
    ...extra,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr'); openAll() } catch { /* yok */ }
})
afterEach(() => { window.history.replaceState({}, '', '/') })

describe('UIA-E1 — onay sonrası gönderim sonucu', () => {
  const CASES = [
    [{ send_state: 'SENT', sent_to: ['ceo@example.com'] }, 'success', TR['te.appr.approved'].replace('{0}', 'ceo@example.com')],
    [{ send_state: 'SENT', sent_to: [] }, 'success', TR['te.appr.outcome.ccOnly']],
    [{ send_state: 'QUEUED' }, 'info', TR['te.appr.approvedQueued']],
    [{ send_state: 'SKIPPED' }, 'info', TR['te.appr.outcome.SKIPPED']],
    [{ send_state: null, send_error: 'APPROVAL_STALE' }, 'error', TR['te.appr.outcome.APPROVAL_STALE']],
    [{ send_state: null, send_error: 'SEND_IN_PROGRESS' }, 'info', TR['te.appr.outcome.SEND_IN_PROGRESS']],
    [{ send_state: null, send_error: 'SEND_QUEUED' }, 'info', TR['te.appr.outcome.SEND_QUEUED']],   // çok pod R2a (MP-41)
    [{ send_state: null, send_error: 'PDF_BUSY' }, 'error', TR['te.appr.outcome.PDF_BUSY']],
    [{ send_state: null, send_error: 'PDF_TOO_LARGE' }, 'error', TR['te.appr.outcome.PDF_TOO_LARGE']],
    [{ send_state: 'FAILED' }, 'error', TR['te.appr.outcome.other']],
    [{ send_state: null, send_error: 'MAIL_FAILED' }, 'error', TR['te.appr.outcome.other']],
    [{}, 'error', TR['te.appr.outcome.other']],
  ]
  it.each(CASES)('%j → %s', (d, tone, text) => {
    expect(approvalOutcome(d, t)).toEqual({ tone, text })
    expect(text).not.toMatch(CODE)                       // ham kod basılmaz
  })

  it('onaydan sonra değişen rapor "Yöneticilere gönder" ile yeniden denemeye ÇAĞIRMAZ', () => {
    for (const dict of [TR, EN]) {
      const txt = approvalOutcome({ send_error: 'APPROVAL_STALE' }, tOf(dict)).text
      expect(txt).not.toContain(dict['te.appr.sendExec'])
      expect(approvalOutcome({ send_error: 'SEND_IN_PROGRESS' }, tOf(dict)).text).not.toContain(dict['te.appr.sendExec'])
    }
  })

  it('PO onayı APPROVAL_STALE döndüğünde ekranda ham kod yok, doğru sonraki adım', async () => {
    te().decideApproval.mockResolvedValue({ success: true, data: { state: 'approved', send_state: null, send_error: 'APPROVAL_STALE', sent_to: [] } })
    render(<ApprovalCard reportId={100} uiLang="tr" approval={{ state: 'pending', requested_at: '2026-10-13T09:00:00Z' }} detail={detail()} />)
    fireEvent.click(screen.getByRole('button', { name: 'Onayla' }))
    const dlg = document.querySelector('[data-slot="te-approval-dialog"]')
    fireEvent.click(within(dlg).getByRole('button', { name: 'Onayla' }))
    await screen.findByText(TR['te.appr.outcome.APPROVAL_STALE'])
    expect(document.body.textContent).not.toContain('APPROVAL_STALE')
  })
})

describe('UIA-E2 — paylaşımla okuyana onaylayıcı sayısı', () => {
  it('adsız satırlar + approver_count > 0 → "N PO onaylayıcı", "PO yok" DEĞİL', () => {
    const d = { approvers: [{ name: null, has_email: true }, { name: null, has_email: false }], approver_count: 2 }
    expect(approversLine(d, t)).toBe(TR['te.appr.approversHidden'].replace('{0}', '2'))
    expect(approversLine({ approvers: [], approver_count: 0 }, t)).toBe(TR['te.appr.noApprovers'])
    expect(approversLine({ approvers: [{ name: 'Ali' }] }, t)).toBe(TR['te.appr.approvers'].replace('{0}', 'Ali'))
    // Eski sunucu (sayı yok) — adsız satır sayılır
    expect(approversLine({ approvers: [{ name: null }] }, t)).toBe(TR['te.appr.approversHidden'].replace('{0}', '1'))
  })

  it('kartta paylaşımla okuyan "takımda PO yok" görmez', () => {
    render(<ApprovalCard reportId={1} uiLang="tr" approval={{ state: 'pending', requested_at: '2026-10-13T09:00:00Z' }}
      detail={detail({ approvers: [{ name: null, has_email: true }], approver_count: 1, can_decide: false, is_approver: false })} />)
    const line = document.querySelector('[data-slot="te-approval-approvers"]')
    expect(line).toHaveTextContent(TR['te.appr.approversHidden'].replace('{0}', '1'))
    expect(document.body.textContent).not.toContain(TR['te.appr.noApprovers'])
  })
})

describe('UIA-E3 — onay isteğinde e-posta gitmediyse neden', () => {
  it.each([['NO_APPROVER_EMAIL'], ['PDF_BUSY'], ['MAIL_FAILED'], [null], ['YENI']])('%s → koda göre cümle, ham kod yok', (code) => {
    const txt = requestNoMailText(code, t)
    const key = ['NO_APPROVER_EMAIL', 'PDF_BUSY', 'MAIL_FAILED'].includes(code) ? `te.appr.noMail.${code}` : 'te.appr.noMail.other'
    expect(txt).toBe(TR[key])
    expect(txt).not.toMatch(CODE)
    expect(txt).not.toContain('(—)')
  })
})

describe('UIA-E4 / E7 — kanal ekranları', () => {
  const EXEC_CHANNEL = () => channel(10, 'Mobil', { approval_required: true, executive_count: 2, can_manage: false })

  it('E4: onay akışlı kanalda takım e-postası yokken "rapor gönderilemez" uyarısı ÇİZİLMEZ', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
    const base = reportResponse({ team_email_set: false })
    base.data.channel = EXEC_CHANNEL()
    base.data.report = { ...base.data.report, approval: { state: 'none', raw_state: null, stale: false } }
    base.data.approval_detail = { ...detail(), can_request: true, can_decide: false, is_approver: false }
    te().report.mockResolvedValue(base)
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(screen.queryByText(TR['te.noTeamEmailTitle'])).toBeNull()
  })

  it('E4: akışsız kanalda uyarı bugünkü gibi', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
    te().report.mockResolvedValue(reportResponse({ team_email_set: false }))
    render(<TechErrorReportsPage />)
    expect(await screen.findByText(TR['te.noTeamEmailTitle'])).toBeInTheDocument()
  })

  it('E4: kanal listesinde yalnız akışlı kanalı olan e-postasız takım "gönderilemez" listesinde değil', async () => {
    window.history.replaceState({}, '', '/?tab=channelreports')
    const ov = overview([EXEC_CHANNEL()])
    ov.data.teams = ov.data.teams.map((tm, i) => (i === 0 ? { ...tm, can_edit: true, has_email: false } : tm))
    te().overview.mockResolvedValue(ov)
    render(<TechErrorReportsPage />)
    await screen.findAllByText('Mobil')
    expect(screen.queryByText(TR['te.noEmailTitle'])).toBeNull()
  })

  it('E7: pasif kanalın boş listesi gizli düğmeye yönlendirmez', async () => {
    te().reports.mockResolvedValue({ success: true, data: { channel: channel(10, 'Mobil', { active: false, report_count: 0 }), reports: [], default_period: { year: 2026, month: 9 } } })
    render(<ChannelView channelId="10" tab="reports" onBack={() => {}} onOpenReport={() => {}} onTabChange={() => {}} />)
    expect(await screen.findByText(TR['te.noReportsBodyInactive'])).toBeInTheDocument()
    expect(screen.queryByText(TR['te.noReportsBodyEdit'])).toBeNull()
  })
})
