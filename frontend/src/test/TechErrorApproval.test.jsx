import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { channel, overview, reportResponse, openAllSections as openAll } from './helpers/techErrorFixtures.js'

// PO onayı (2026-10-17, S6): yönetici alıcısı tanımlı kanalda "Takıma gönder" yerine "Onaya gönder"; onay kartı durum /
// kişi / not; PO onaylar ya da gerekçeyle reddeder; onaylı rapor "Yöneticilere gönder"; akışsız kanal eski düğme.
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
import ApprovalCard, { approvalStateOf, currentContentSent, flowRemovedHintKey } from '../components/techerror/ApprovalCard.jsx'

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'

const EXEC_CHANNEL = () => channel(10, 'Mobil', { approval_required: true, executive_count: 2, executive_emails: 'ceo@example.com, cto@example.com', can_manage: false })

function detail(extra = {}) {
  return {
    required: true, executive_count: 2, executive_emails: ['ceo@example.com', 'cto@example.com'],
    approvers: [{ name: 'Mehmet Kaya', has_email: true }, { name: 'Zeynep Ak', has_email: false }],
    requested_by_name: null, decided_by_name: null, can_request: true, is_approver: false, can_decide: false, can_send_executive: false,
    ...extra,
  }
}

function withApproval({ approval = { state: 'none', raw_state: null, stale: false }, det = detail(), report = {} } = {}) {
  const base = reportResponse()
  base.data.channel = EXEC_CHANNEL()
  base.data.report = { ...base.data.report, approval, ...report }
  base.data.approval_detail = det
  return base
}

const dialogEl = () => document.querySelector('[role="alertdialog"], [role="dialog"]')

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr'); openAll() } catch { /* yok */ }
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  te().overview.mockResolvedValue(overview([EXEC_CHANNEL()]))
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: EXEC_CHANNEL(), reports: [], default_period: { year: 2026, month: 9 } } }))
})

afterEach(() => { vi.restoreAllMocks() })

describe('onay kartı (birim)', () => {
  it('akışsız kanal ve onaysız rapor: kart çizilmez; durum eşleme bilinmeyeni "none" sayar', () => {
    const { container } = render(<ApprovalCard reportId={1} detail={{ required: false }} approval={{ state: 'none' }} uiLang="tr" />)
    expect(container.querySelector('[data-slot="te-approval"]')).toBeNull()
    expect(approvalStateOf({ state: 'weird' })).toBe('none')
    expect(approvalStateOf(null)).toBe('none')
    expect(approvalStateOf({ state: 'stale' })).toBe('stale')
  })

  it('inceleme 2026-10-18: yönetici alıcıları kaldırılmış kanalda bekleyen istek "onay beklemiyor, takım adresine doğrudan gönderin" der; karar düğmesi yok', () => {
    render(<ApprovalCard reportId={1} uiLang="tr" approval={{ state: 'pending', requested_at: '2026-10-13T09:00:00Z' }}
      detail={{ required: false, approvers: [], can_decide: false, can_request: false }} />)
    const card = document.querySelector('[data-slot="te-approval"]')
    expect(card).not.toBeNull()
    expect(card).toHaveTextContent('Kanalın yönetici alıcıları kaldırıldı; bu onay isteği için karar verilemez')
    expect(card).toHaveTextContent('Yönetici alıcıları yeniden eklenirse istek yeniden geçerli olur.')
    expect(card).not.toHaveTextContent('PO kararı bekleniyor')
    // Rozet gerçek durumu söyler: "Onay akışı kaldırıldı" (eski "Onay bekliyor" yanıltıyordu); önceki durum yalnız bilgi satırında
    const badge = card.querySelector('[data-slot="te-approval-state"]')
    expect(badge).toHaveTextContent('Onay akışı kaldırıldı')
    expect(badge).toHaveAttribute('data-flow', 'removed')
    expect(badge).not.toHaveTextContent('Onay bekliyor')
    expect(card.querySelector('[data-slot="te-approval-prev"]')).toHaveTextContent('Önceki onay durumu: Onay bekliyor')
    expect(screen.queryByRole('button', { name: /Onayla|Reddet/ })).toBeNull()
  })

  it('inceleme 2026-10-18: akışı kaldırılmış kanalda ipucu duruma göre — reddedilmiş / eskimiş / onaylanıp gönderilmemiş rapor "bekleyen istek" demez; gönderilmiş rapor "gönderildi" der ("doğrudan gönderebilirsiniz" değil)', () => {
    const removed = { required: false, approvers: [], can_decide: false, can_request: false }
    const cases = [
      { state: 'rejected', prev: 'Reddedildi' },
      { state: 'stale', prev: 'İstekten sonra değişti' },
      { state: 'approved', prev: 'Onaylandı' },
    ]
    for (const c of cases) {
      const { unmount } = render(<ApprovalCard reportId={1} uiLang="tr" approval={{ state: c.state, requested_at: '2026-10-13T09:00:00Z' }} detail={removed} />)
      const card = document.querySelector('[data-slot="te-approval"]')
      expect(card.querySelector('[data-slot="te-approval-state"]'), c.state).toHaveTextContent('Onay akışı kaldırıldı')
      expect(card.querySelector('[data-slot="te-approval-prev"]'), c.state).toHaveTextContent(`Önceki onay durumu: ${c.prev}`)
      const hint = card.querySelector('[data-slot="te-approval-hint"]')
      expect(hint, c.state).toHaveTextContent('rapor artık onay gerektirmiyor ve takım adresine doğrudan gönderilebilir')
      expect(hint.textContent, c.state).not.toMatch(/bekleyen|karar verilebilir|yeniden onaya gönder/i)
      expect(card, c.state).not.toHaveTextContent('henüz yöneticilere gönderilmedi')
      unmount()
    }
    // Bekleyen istek ama rapor takım adresine zaten gönderilmiş: "gönderildi" + son gönderim satırı
    render(<ApprovalCard reportId={1} uiLang="tr" approval={{ state: 'pending', requested_at: '2026-10-13T09:00:00Z' }} detail={removed}
      sentAt="2026-10-14T09:00:00Z" sentTo="dijital@example.com" />)
    const card = document.querySelector('[data-slot="te-approval"]')
    const hint = card.querySelector('[data-slot="te-approval-hint"]')
    expect(hint).toHaveTextContent('Rapor gönderildi — son gönderim aşağıda.')
    expect(hint).not.toHaveTextContent('doğrudan gönderilebilir')
    expect(card).toHaveTextContent('dijital@example.com')
    expect(flowRemovedHintKey('pending', null)).toBe('te.appr.flowRemovedPendingHint')
    expect(flowRemovedHintKey('rejected', null)).toBe('te.appr.flowRemovedHint')
    expect(flowRemovedHintKey('approved', '2026-10-14T09:00:00Z')).toBe('te.appr.flowRemovedSentHint')
  })

  it('inceleme 2026-10-18 (2. tur): eski bir gönderim (last_sent_at) ŞU ANKİ içeriğin gönderildiği demek değil — gönderilip değiştirilen, yeniden onaya sunulan rapor akış kaldırılınca "karar verilemez" der, "gönderildi" demez', () => {
    const removed = { required: false, approvers: [], can_decide: false, can_request: false }
    const pending = { state: 'pending', requested_at: '2026-10-15T09:00:00Z' }
    // Rapor 14'ünde gönderildi, sonra değişti ("changed"), 15'inde yeniden onaya sunuldu; yönetici alıcıları kaldırıldı
    const { unmount } = render(<ApprovalCard reportId={1} uiLang="tr" approval={pending} detail={removed}
      sentAt="2026-10-14T09:00:00Z" sentTo="dijital@example.com" reportStatus="changed" />)
    let hint = document.querySelector('[data-slot="te-approval-hint"]')
    expect(hint).toHaveTextContent('bu onay isteği için karar verilemez')
    expect(hint).not.toHaveTextContent('Rapor gönderildi')
    unmount()
    // Durum gelmezse zaman kuralı: son gönderim istekten ÖNCE → gönderilmemiş içerik
    const second = render(<ApprovalCard reportId={1} uiLang="tr" approval={pending} detail={removed}
      sentAt="2026-10-14T09:00:00Z" sentTo="dijital@example.com" />)
    hint = document.querySelector('[data-slot="te-approval-hint"]')
    expect(hint).toHaveTextContent('bu onay isteği için karar verilemez')
    expect(hint).not.toHaveTextContent('Rapor gönderildi')
    second.unmount()
    // Şu anki içerik gönderildi (durum "sent") → "gönderildi"
    render(<ApprovalCard reportId={1} uiLang="tr" approval={pending} detail={removed}
      sentAt="2026-10-14T09:00:00Z" sentTo="dijital@example.com" reportStatus="sent" />)
    expect(document.querySelector('[data-slot="te-approval-hint"]')).toHaveTextContent('Rapor gönderildi — son gönderim aşağıda.')

    // Saf kural: durum önce; durum yoksa zaman (istekten sonra, eskimemiş); onaylıda gönderim karardan sonra olmalı
    const appr = { requested_at: '2026-10-13T09:00:00Z', decided_at: '2026-10-14T09:00:00Z' }
    expect(currentContentSent({ reportStatus: 'changed', sentAt: '2026-10-16T09:00:00Z', approval: pending, state: 'pending' })).toBe(false)
    expect(currentContentSent({ reportStatus: 'sent', sentAt: null, approval: pending, state: 'pending' })).toBe(false)
    expect(currentContentSent({ sentAt: '2026-10-16T09:00:00Z', approval: pending, state: 'pending' })).toBe(true)
    expect(currentContentSent({ sentAt: '2026-10-16T09:00:00Z', approval: pending, state: 'stale' })).toBe(false)
    expect(currentContentSent({ reportStatus: 'sent', sentAt: '2026-10-12T09:00:00Z', approval: appr, state: 'approved' })).toBe(false)
    expect(currentContentSent({ reportStatus: 'sent', sentAt: '2026-10-14T09:05:00Z', approval: appr, state: 'approved' })).toBe(true)
  })

  it('inceleme 2026-10-18 (2. tur): onaylı raporda akış eklenmeden ÖNCEKİ takım gönderimi "yöneticilere gönderildi" demez', () => {
    const approval = { state: 'approved', requested_at: '2026-10-13T09:00:00Z', decided_at: '2026-10-14T09:00:00Z' }
    const { unmount } = render(<ApprovalCard reportId={1} uiLang="tr" approval={approval} detail={detail({ can_request: false })}
      sentAt="2026-10-10T09:00:00Z" sentTo="dijital@example.com" reportStatus="sent" />)
    expect(document.querySelector('[data-slot="te-approval-hint"]')).toHaveTextContent('henüz yöneticilere gönderilmedi')
    unmount()
    render(<ApprovalCard reportId={1} uiLang="tr" approval={approval} detail={detail({ can_request: false })}
      sentAt="2026-10-14T09:01:00Z" sentTo="ceo@example.com" reportStatus="sent" />)
    expect(document.querySelector('[data-slot="te-approval-hint"]')).toHaveTextContent('Onaylı rapor yöneticilere gönderildi.')
  })

  it('bekleyen istek: durum, isteyen, onaylayıcılar; PO için Onayla / Reddet — red gerekçesiz reddedilmez, gerekçeyle karar gönderilir', async () => {
    te().decideApproval.mockResolvedValue({ success: true, data: { state: 'rejected', send_state: null, sent_to: [] } })
    const onChanged = vi.fn()
    render(<ApprovalCard reportId={100} uiLang="tr" approval={{ state: 'pending', requested_at: '2026-10-13T09:00:00Z' }}
      detail={detail({ requested_by_name: 'Ayşe Yılmaz', can_request: false, is_approver: true, can_decide: true })} onChanged={onChanged} />)
    const card = document.querySelector('[data-slot="te-approval"]')
    expect(card).toHaveAttribute('data-state', 'pending')
    expect(within(card).getByText('Onay bekliyor')).toBeInTheDocument()
    expect(within(card).getByText(/Ayşe Yılmaz tarafından/)).toBeInTheDocument()
    expect(within(card).getByText(/Mehmet Kaya, Zeynep Ak/)).toBeInTheDocument()
    expect(within(card).getByText('Bu raporun onaylayıcısısınız.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Reddet' }))
    const dlg = document.querySelector('[data-slot="te-approval-dialog"]')
    expect(within(dlg).getByRole('heading', { name: 'Raporu reddet' })).toBeInTheDocument()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Reddet' }))
    expect(within(dlg).getByText('Reddetmek için gerekçe yazın.')).toBeInTheDocument()
    expect(te().decideApproval).not.toHaveBeenCalled()
    fireEvent.change(within(dlg).getByRole('textbox'), { target: { value: '  Rakamlar eksik ' } })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Reddet' }))
    await waitFor(() => expect(te().decideApproval).toHaveBeenCalledWith(100, { decision: 'reject', note: 'Rakamlar eksik' }))
    await waitFor(() => expect(onChanged).toHaveBeenCalled())
    await waitFor(() => expect(document.querySelector('[data-slot="te-approval-dialog"]')).toBeNull())
  })

  it('onay: not isteğe bağlı; sunucu "not" alan hatası diyaloğun içinde kalır; reddedilmiş rapor notu ve karar vereni gösterir', async () => {
    te().decideApproval.mockResolvedValueOnce({ success: false, field: 'note', error: 'Not çok uzun' })
      .mockResolvedValueOnce({ success: true, data: { state: 'approved', send_state: 'SENT', sent_to: ['ceo@example.com'] } })
    render(<ApprovalCard reportId={100} uiLang="tr" approval={{ state: 'pending', requested_at: '2026-10-13T09:00:00Z' }}
      detail={detail({ can_request: false, is_approver: true, can_decide: true })} />)
    fireEvent.click(screen.getByRole('button', { name: 'Onayla' }))
    const dlg = document.querySelector('[data-slot="te-approval-dialog"]')
    expect(within(dlg).getByText(/2 yönetici alıcısına hemen gönderilir/)).toBeInTheDocument()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Onayla' }))
    await waitFor(() => expect(te().decideApproval).toHaveBeenCalledWith(100, { decision: 'approve', note: null }))
    expect(await within(dlg).findByText('Not çok uzun')).toBeInTheDocument()     // diyalog açık kaldı
    fireEvent.click(within(dlg).getByRole('button', { name: 'Onayla' }))
    await waitFor(() => expect(te().decideApproval).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(document.querySelector('[data-slot="te-approval-dialog"]')).toBeNull())

    render(<ApprovalCard reportId={101} uiLang="tr" approval={{ state: 'rejected', requested_at: '2026-10-13T09:00:00Z', decided_at: '2026-10-14T09:00:00Z', note: 'Rakamlar eksik' }}
      detail={detail({ requested_by_name: 'Ayşe', decided_by_name: 'Mehmet Kaya', can_request: true })} />)
    const rejected = document.querySelector('[data-slot="te-approval"][data-state="rejected"]')
    expect(within(rejected).getByText(/Mehmet Kaya tarafından/)).toBeInTheDocument()
    expect(within(rejected).getByText(/Rakamlar eksik/)).toBeInTheDocument()
    expect(within(rejected).getByText('Raporu nota göre düzeltip yeniden onaya gönderin.')).toBeInTheDocument()
    expect(within(rejected).getByRole('button', { name: 'Onaya gönder' })).toBeInTheDocument()
  })
})

describe('rapor düzenleyici', () => {
  it('onay akışlı kanal: eylem çubuğunda "Onaya gönder" (Takıma gönder yok); istek onay penceresinden sonra gider ve rapor yeniden okunur', async () => {
    te().report.mockResolvedValue(withApproval())
    te().requestApproval.mockResolvedValue({ success: true, data: { state: 'pending', approvers: [{ name: 'Mehmet Kaya', has_email: true }], mailed: 1, failed: 0, mail_problem: null } })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const bar = document.querySelector('[data-slot="te-report-actions"]')
    expect(bar.querySelector('[data-slot="te-send"]')).toBeNull()
    expect(bar.querySelector('[data-slot="te-send-exec"]')).toBeNull()
    const req = bar.querySelector('[data-slot="te-approval-request-bar"]')
    expect(req).not.toBeNull()
    expect(document.querySelector('[data-slot="te-approval"]')).toHaveAttribute('data-state', 'none')
    fireEvent.click(req)
    const dlg = await waitFor(() => { const d = dialogEl(); if (!d) throw new Error('dialog'); return d })
    expect(within(dlg).getByText(/2 onaylayıcıya e-postayla gider \(Mehmet Kaya, Zeynep Ak\)/)).toBeInTheDocument()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Onaya gönder' }))
    await waitFor(() => expect(te().requestApproval).toHaveBeenCalledWith('100'))
    await waitFor(() => expect(te().report.mock.calls.length).toBeGreaterThanOrEqual(2))
    expect(te().send).not.toHaveBeenCalled()
  })

  it('onaylı ve değişmemiş rapor: "Yöneticilere gönder" aynı gönderim ucunu çağırır; istek düğmesi yok; akışsız kanal eski "Takıma gönder"', async () => {
    te().report.mockResolvedValue(withApproval({
      approval: { state: 'approved', requested_at: '2026-10-13T09:00:00Z', decided_at: '2026-10-14T09:00:00Z' },
      det: detail({ requested_by_name: 'Ayşe', decided_by_name: 'Mehmet Kaya', can_request: false, can_send_executive: true }),
    }))
    te().send.mockResolvedValue({ success: true, data: { state: 'SENT', to: ['ceo@example.com'] } })
    const { unmount } = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const bar = document.querySelector('[data-slot="te-report-actions"]')
    expect(bar.querySelector('[data-slot="te-approval-request-bar"]')).toBeNull()
    expect(bar.querySelector('[data-slot="te-send"]')).toBeNull()
    const exec = bar.querySelector('[data-slot="te-send-exec"]')
    expect(exec).toHaveTextContent('Yöneticilere gönder')
    expect(document.querySelector('[data-slot="te-approval"]')).toHaveAttribute('data-state', 'approved')
    expect(screen.getByText(/henüz yöneticilere gönderilmedi/)).toBeInTheDocument()
    fireEvent.click(exec)
    const dlg = await waitFor(() => { const d = dialogEl(); if (!d) throw new Error('dialog'); return d })
    expect(within(dlg).getByText(/ceo@example.com, cto@example.com/)).toBeInTheDocument()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Gönder' }))
    await waitFor(() => expect(te().send).toHaveBeenCalledWith('100'))
    unmount()

    te().report.mockResolvedValue(reportResponse())
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const bar2 = document.querySelector('[data-slot="te-report-actions"]')
    expect(bar2.querySelector('[data-slot="te-send"]')).toHaveTextContent('Takıma gönder')
    expect(bar2.querySelector('[data-slot="te-approval-request-bar"]')).toBeNull()
    expect(document.querySelector('[data-slot="te-approval"]')).toBeNull()
  })

  it('eskimiş istek (içerik değişti): kart uyarır, yeniden "Onaya gönder"; salt okunur görüntüleyen düğme görmez', async () => {
    te().report.mockResolvedValue(withApproval({
      approval: { state: 'stale', raw_state: 'PENDING', stale: true, requested_at: '2026-10-13T09:00:00Z' },
      det: detail({ requested_by_name: 'Ayşe', can_request: true }),
    }))
    const { unmount } = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(document.querySelector('[data-slot="te-approval"]')).toHaveAttribute('data-state', 'stale')
    expect(screen.getAllByText(/yeniden onaya gönderilmeli/).length).toBeGreaterThan(0)
    expect(document.querySelector('[data-slot="te-report-actions"] [data-slot="te-approval-request-bar"]')).not.toBeNull()
    unmount()

    te().report.mockResolvedValue(withApproval({ det: detail({ can_request: false }), report: {} }))
    const ro = withApproval({ det: detail({ can_request: false }) })
    ro.data.can_edit = false
    te().report.mockResolvedValue(ro)
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(document.querySelector('[data-slot="te-report-actions"] [data-slot="te-approval-request-bar"]')).toBeNull()
    expect(document.querySelector('[data-slot="te-approval"]')).not.toBeNull()   // kart bilgi amaçlı görünür
  })

  it('inceleme 2026-10-18 (2. tur): gönderilip değiştirilen, yeniden onaya sunulan rapor — kanalın yönetici alıcıları kaldırılınca kart "karar verilemez" der, eski gönderimi "gönderildi" diye okumaz', async () => {
    const res = withApproval({
      approval: { state: 'pending', raw_state: 'PENDING', stale: false, requested_at: '2026-10-15T09:00:00Z' },
      det: detail({ required: false, executive_count: 0, executive_emails: [], approvers: [], can_request: false }),
      report: { status: 'changed', last_sent_at: '2026-10-14T09:00:00Z', last_sent_to: 'dijital@example.com', send_count: 1 },
    })
    res.data.channel = channel(10, 'Mobil', { approval_required: false, executive_count: 0, executive_emails: '', can_manage: false })
    te().report.mockResolvedValue(res)
    const { unmount } = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const card = document.querySelector('[data-slot="te-approval"]')
    expect(card.querySelector('[data-slot="te-approval-state"]')).toHaveAttribute('data-flow', 'removed')
    const hint = card.querySelector('[data-slot="te-approval-hint"]')
    expect(hint).toHaveTextContent('bu onay isteği için karar verilemez')
    expect(hint).not.toHaveTextContent('Rapor gönderildi')
    unmount()

    // Düzenleyici raporun durumunu karta verir: içerik son gönderimden beri değişmedi ("sent") → "gönderildi"
    // (istek gönderimden sonra yapılmış olsa da — akış sonradan eklenmişti)
    const sent = withApproval({
      approval: { state: 'pending', raw_state: 'PENDING', stale: false, requested_at: '2026-10-15T09:00:00Z' },
      det: detail({ required: false, executive_count: 0, executive_emails: [], approvers: [], can_request: false }),
      report: { status: 'sent', last_sent_at: '2026-10-14T09:00:00Z', last_sent_to: 'dijital@example.com', send_count: 1 },
    })
    sent.data.channel = res.data.channel
    te().report.mockResolvedValue(sent)
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(document.querySelector('[data-slot="te-approval-hint"]')).toHaveTextContent('Rapor gönderildi — son gönderim aşağıda.')
  })
})
