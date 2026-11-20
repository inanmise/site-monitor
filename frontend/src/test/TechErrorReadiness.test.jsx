import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { channel, overview, reportResponse, TEMPLATE, openAllSections as openAll } from './helpers/techErrorFixtures.js'

// Hazırlık rehberi (2026-11-20, B paketi — kullanıcı onayı): başlık çipi + panel + "Git" (B1), birim / toplam soruları (B2),
// onay sürecinde düzenleme kilidi (B3), şablon güncellemesinde silinecek adlar (B4). Eksikler yalnız uyarır.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    techErrors: {
      overview: vi.fn(), template: vi.fn(), reports: vi.fn(), report: vi.fn(), saveReport: vi.fn(), send: vi.fn(),
      requestApproval: vi.fn(), decideApproval: vi.fn(), refreshTemplate: vi.fn(), downloadPdf: vi.fn(),
      imageUrl: (id) => `/api/tech-error-reports/images/${id}`,
    },
  }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import TechErrorReportsPage from '../components/techerror/TechErrorReportsPage.jsx'
import { AUTOSAVE_MS, AUTOSAVE_TIMEOUT_MS } from '../components/techerror/ReportEditor.jsx'
import { readinessConfirmText, readinessSummaryText } from '../components/techerror/ReadinessParts.jsx'
import TemplateLossList, { lossFieldText, lossSlotText, hasLossLists } from '../components/techerror/TemplateLossList.jsx'

const te = () => api.techErrors
const TITLE = 'Mobil Kanalı Teknik Hata Mesajları - Eylül 2026'
const SUMMARY = 'Mobil Kanalı — Eylül 2026 Özeti'
const IMAGES = [
  { id: 1, slot_id: 'monthly-tx', file_name: 'a.png', content_type: 'image/png', size_bytes: 10, width: 10, height: 10 },
  { id: 2, slot_id: 'tech-errors', file_name: 'b.png', content_type: 'image/png', size_bytes: 10, width: 10, height: 10 },
  { id: 3, slot_id: 'daily-errors', file_name: 'c.png', content_type: 'image/png', size_bytes: 10, width: 10, height: 10 },
]
const FULL_FIELDS = { 'error-rate': 0.42, 'total-tx': 1000000, 'sla-above': true }

const chip = () => document.querySelector('[data-slot="te-ready-chip"]')
const sheet = () => document.querySelector('[data-slot="te-ready-sheet"]')
const status = () => document.querySelector('[data-slot="te-save-status"]')
const lock = () => document.querySelector('[data-slot="te-approval-lock"]')
const dialogEl = () => document.querySelector('[role="alertdialog"], [role="dialog"]')
const sectionCard = (id) => document.querySelector(`[data-slot="te-report"] [data-section="${id}"]`)
const sectionBadge = (id) => sectionCard(id)?.querySelector('[data-slot="te-section-ready"]') ?? null

const EXEC_CHANNEL = () => channel(10, 'Mobil', { approval_required: true, executive_count: 2,
  executive_emails: 'ceo@example.com, cto@example.com', can_manage: false })

function detail(extra = {}) {
  return {
    required: true, executive_count: 2, executive_emails: ['ceo@example.com', 'cto@example.com'],
    approvers: [{ name: 'Mehmet Kaya', has_email: true }, { name: 'Zeynep Ak', has_email: false }],
    requested_by_name: 'Ayşe Yılmaz', decided_by_name: null, can_request: false, is_approver: false, can_decide: false,
    can_send_executive: false, ...extra,
  }
}

function withApproval({ approval, det = detail(), report = {}, data = {} } = {}) {
  const base = reportResponse(data)
  base.data.channel = EXEC_CHANNEL()
  base.data.report = { ...base.data.report, approval, ...report }
  base.data.approval_detail = det
  return base
}

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
  window.history.replaceState({}, '', '/?tab=channelreports&te_view=report&te_ch=10&te_rep=100')
  te().overview.mockResolvedValue(overview())
  te().reports.mockImplementation(async (id) => ({ success: true, data: { channel: channel(Number(id), 'Mobil'), reports: [],
    default_period: { year: 2026, month: 9 } } }))
  te().report.mockResolvedValue(reportResponse())
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('metin yardımcıları (saf)', () => {
  const t = (k, ...a) => `${k}(${a.join(',')})`
  const r = (extra) => ({ known: true, items: [], empty: 0, emptySlots: 0, warnings: 0, images: null, ready: false, ...extra })

  it('çip metni: görsel oranı, boş alan, uyarı; kalem yoksa hazır ya da denetlenemedi', () => {
    expect(readinessSummaryText(r({ images: { filled: 2, total: 3 }, empty: 2, emptySlots: 1, warnings: 1, items: [1, 2, 3, 4] }), t))
      .toBe('te.ready.images(2,3) · te.ready.empty(2) · te.ready.warnings.one(1)')
    expect(readinessSummaryText(r({ images: { filled: 3, total: 3 }, ready: true }), t)).toBe('te.ready.images(3,3) · te.ready.ok()')
    expect(readinessSummaryText(r({ images: { filled: null, total: 3 } }), t)).toBe('te.ready.imagesUnknown(3) · te.ready.unknown()')
    expect(readinessSummaryText(null, t)).toBe('te.ready.unknown()')
  })

  it('onay metni: kalem yoksa null (bugünkü metin aynen); varsa sayı + parçalar, gönderim / onay ayrı', () => {
    expect(readinessConfirmText(r(), t, 'send')).toBeNull()
    expect(readinessConfirmText(null, t, 'send')).toBeNull()
    const x = r({ empty: 1, emptySlots: 2, warnings: 1, items: [1, 2, 3, 4] })
    expect(readinessConfirmText(x, t, 'send'))
      .toBe('te.ready.confirmSend(4,te.ready.empty.one(1), te.ready.emptySlots(2), te.ready.warnings.one(1))')
    expect(readinessConfirmText(r({ warnings: 1, items: [1] }), t, 'approval')).toBe('te.ready.confirmApproval.one(te.ready.warnings.one(1))')
  })

  it('B4 liste metinleri: tür, sayı, adsız; eski sunucu listesizse liste yok', () => {
    expect(lossFieldText({ label: 'Toplam işlem', kind: 'field', count: 1 }, t)).toBe('Toplam işlem')
    expect(lossFieldText({ label: 'SLA', kind: 'target' }, t)).toBe('SLA — te.tplLoss.kind.target()')
    expect(lossFieldText({ label: 'Kart', kind: 'topic', count: 3 }, t)).toBe('Kart — te.tplLoss.kind.topic(3)')
    expect(lossFieldText({ label: 'Kart', kind: 'topic', count: 1 }, t)).toBe('Kart — te.tplLoss.kind.topic.one(1)')
    expect(lossFieldText({ label: 'T', kind: 'table', count: 4 }, t)).toBe('T — te.tplLoss.kind.cells(4)')
    expect(lossFieldText({ label: '  ', kind: 'field' }, t)).toBe('te.tplLoss.unnamed()')
    expect(lossSlotText({ label: 'Kart: Hata Artışı', kind: 'topic_image', image_no: 2 }, t)).toBe('te.tplLoss.topicImage(Kart: Hata Artışı,2)')
    expect(lossSlotText({ label: 'Grafik', kind: 'slot' }, t)).toBe('Grafik')
    expect(hasLossLists({ lost_values: 2 })).toBe(false)
    expect(hasLossLists({ lost_fields: [], lost_slots: [{ id: 'a' }] })).toBe(true)
  })
})

describe('B1 — hazırlık çipi, bölüm rozeti, panel ve "Git"', () => {
  it('çip görsel oranını ve boş alanları söyler; kapalı bölümün başlığında rozet; panel gruplu; Git bölümü açıp alana odaklanır', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    // Fikstür: error-rate dolu; total-tx ve sla-above boş; 3 görsel yuvası boş (çip onları oranla söyler)
    expect(chip()).toHaveTextContent('Görsel 0/3 · 2 boş alan')
    expect(chip()).toHaveAttribute('data-tone', 'warn')
    expect(chip()).toHaveAccessibleName('Rapor hazırlığı: Görsel 0/3 · 2 boş alan — ayrıntıları aç')
    // Bölümler kapalı: rozet görünür (kalem sayısı), kalemsiz bölümde rozet yok
    expect(sectionBadge('summary')).toHaveTextContent('2 eksik')
    expect(sectionBadge('charts')).toHaveTextContent('3 eksik')
    expect(sectionBadge('issues')).toBeNull()

    fireEvent.click(chip())
    await waitFor(() => expect(sheet()).not.toBeNull())
    const groups = [...sheet().querySelectorAll('[data-slot="te-ready-group"]')]
    expect(groups.map((g) => g.getAttribute('data-section'))).toEqual(['summary', 'charts'])
    expect(within(groups[0]).getByText('Toplam işlem adedi boş')).toBeInTheDocument()
    expect(within(groups[0]).getByText('SLA hedefin üstünde mi? boş')).toBeInTheDocument()
    expect(within(groups[1]).getByText('Mobil Kanalı Teknik Hata (Günlük) — görsel yüklenmedi')).toBeInTheDocument()

    fireEvent.click(within(sheet()).getByRole('button', { name: 'Git: Toplam işlem adedi boş' }))
    await waitFor(() => expect(sheet()).toBeNull())
    expect(screen.getByRole('button', { name: SUMMARY })).toHaveAttribute('aria-expanded', 'true')
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('textbox', { name: 'Toplam işlem adedi' })))
    // Açık bölümde rozet yok (kalemler zaten görünür)
    expect(sectionBadge('summary')).toBeNull()

    // Yazınca çip güncellenir (geçen ay 1.000.000 → aynı büyüklükte, birim sorusu yok)
    fireEvent.change(screen.getByRole('textbox', { name: 'Toplam işlem adedi' }), { target: { value: '1200000' } })
    expect(chip()).toHaveTextContent('Görsel 0/3 · 1 boş alan')
  })

  it('görsel yuvasına "Git": görsel bölümü açılır, yuva görünür', async () => {
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(chip())
    await waitFor(() => expect(sheet()).not.toBeNull())
    fireEvent.click(within(sheet()).getByRole('button', { name: /^Git: .*Teknik Hata \(Günlük\) — görsel yüklenmedi$/ }))
    await waitFor(() => expect(sheet()).toBeNull())
    expect(screen.getByRole('button', { name: 'Grafikler' })).toHaveAttribute('aria-expanded', 'true')
    const slot = document.querySelector('[data-slot="te-image-slot"][data-slot-id="daily-errors"]')
    expect(slot).not.toBeNull()
    expect(slot.closest('[hidden]')).toBeNull()
  })

  it('her şey dolu ve biliniyor → yeşil "Rapor hazır"; alıcı bilgisi bilinmiyorsa asla yeşil değil', async () => {
    te().report.mockResolvedValue(reportResponse({ values: { fields: FULL_FIELDS, issues: {}, texts: {} }, images: IMAGES }))
    const { unmount } = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(chip()).toHaveTextContent('Görsel 3/3 · Rapor hazır')
    expect(chip()).toHaveAttribute('data-tone', 'ok')
    fireEvent.click(chip())
    await waitFor(() => expect(sheet()?.querySelector('[data-slot="te-ready-ok"]')).not.toBeNull())
    unmount()

    // Takım adresi bilgisi gelmedi (eski sunucu / okunamadı): kalem yok ama "hazır" da değil
    const res = reportResponse({ values: { fields: FULL_FIELDS, issues: {}, texts: {} }, images: IMAGES })
    delete res.data.team_email_set
    te().report.mockResolvedValue(res)
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(chip()).toHaveTextContent('Görsel 3/3 · Hazırlık denetlenemedi')
    expect(chip()).toHaveAttribute('data-tone', 'unknown')
  })

  it('takım adresi yok → gönderim uyarısı (panelin "Gönderim" grubu, en sonda)', async () => {
    te().report.mockResolvedValue(reportResponse({ values: { fields: FULL_FIELDS, issues: {}, texts: {} }, images: IMAGES,
      team_email_set: false }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(chip()).toHaveTextContent('Görsel 3/3 · 1 uyarı')
    fireEvent.click(chip())
    await waitFor(() => expect(sheet()).not.toBeNull())
    const groups = [...sheet().querySelectorAll('[data-slot="te-ready-group"]')]
    expect(groups.map((g) => g.getAttribute('data-section'))).toEqual([''])
    expect(within(groups[0]).getByText('Takımın e-posta adresi tanımlı değil — rapor gönderilemez')).toBeInTheDocument()
  })

  it('yeni (boş) rapor: "Sonraki adım" ipucu ilk eksik bölüme götürür; dolu raporda ipucu yok', async () => {
    te().report.mockResolvedValue(reportResponse({ values: { fields: {}, issues: {}, texts: {} } }))
    const { unmount } = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const hint = document.querySelector('[data-slot="te-next-step"]')
    expect(hint).toHaveTextContent('Rapor henüz boş. “Mobil Kanalı — Eylül 2026 Özeti” bölümünden başlayın')
    fireEvent.click(within(hint).getByRole('button', { name: 'Başla' }))
    await waitFor(() => expect(screen.getByRole('button', { name: SUMMARY })).toHaveAttribute('aria-expanded', 'true'))
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('textbox', { name: 'Teknik hata oranı' })))
    unmount()

    te().report.mockResolvedValue(reportResponse())
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(document.querySelector('[data-slot="te-next-step"]')).toBeNull()
  })
})

describe('B2 — birim ve toplam soruları (engellemez)', () => {
  it('geçen aya göre 1/10 ya da daha az: alanın altında soru, çipte uyarı; aynı büyüklükte soru yok', async () => {
    try { openAll() } catch { /* yok */ }
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const total = screen.getByRole('textbox', { name: 'Toplam işlem adedi' })
    fireEvent.change(total, { target: { value: '1000' } })
    const field = total.closest('[data-field="values.total-tx"]')
    const warn = await waitFor(() => { const w = field.querySelector('[data-slot="te-scale-warn"]'); if (!w) throw new Error('warn'); return w })
    expect(warn).toHaveAttribute('data-dir', 'down')
    expect(warn).toHaveTextContent(/^Geçen ay 1\.000\.000 idi, bu ay 1\.000 — birim doğru mu\?$/)
    // Ekran okuyucu soruyu alanla birlikte duyar (inceleme 2026-11-20)
    expect(warn.id).toBeTruthy()
    expect(total.getAttribute('aria-describedby')?.split(' ')).toContain(warn.id)
    expect(chip()).toHaveTextContent('Görsel 0/3 · 1 boş alan · 1 uyarı')
    // Kaydet engellenmez
    expect(screen.getByRole('button', { name: 'Kaydet' })).toBeEnabled()
    fireEvent.change(total, { target: { value: '1100000' } })
    await waitFor(() => expect(field.querySelector('[data-slot="te-scale-warn"]')).toBeNull())
    expect(chip()).toHaveTextContent('Görsel 0/3 · 1 boş alan')
    expect(chip()).not.toHaveTextContent('uyarı')
  })

  it('konuların hata adetleri toplamı ayın toplamını aşınca anomali bölümünde uyarı', async () => {
    try { openAll() } catch { /* yok */ }
    const tpl = { ...TEMPLATE, sections: TEMPLATE.sections.map((s) => (s.kind === 'issues' ? { ...s, ratio_of: 'total-tx' } : s)) }
    te().report.mockResolvedValue(reportResponse({ template: tpl, values: { fields: { ...FULL_FIELDS, 'total-tx': 1000000 },
      issues: { issues: [{ id: 'k1', topic: 'Kart', errors: 800000 }, { id: 'k2', topic: 'POS', errors: 300000 }] }, texts: {} } }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const warn = document.querySelector('[data-slot="te-issue-sum-warn"][data-section="issues"]')
    expect(warn).toHaveTextContent('Konuların teknik hata adetleri toplamı (1.100.000) ayın toplam teknik hata adedini (1.000.000) aşıyor')
    expect(chip()).toHaveTextContent('1 uyarı')
    fireEvent.change(screen.getByRole('textbox', { name: 'Toplam işlem adedi' }), { target: { value: '1200000' } })
    await waitFor(() => expect(document.querySelector('[data-slot="te-issue-sum-warn"]')).toBeNull())
    expect(chip()).not.toHaveTextContent('uyarı')
  })
})

describe('B1 — gönderim ve onaya gönderme onayında özet (engellemez)', () => {
  it('takıma gönder penceresi eksikleri söyler, "Gönder" açık kalır; eksiksiz raporda bant yok', async () => {
    const { unmount } = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(document.querySelector('[data-slot="te-report-actions"] [data-slot="te-send"]'))
    const dlg = await waitFor(() => { const d = document.querySelector('[data-slot="te-send-dialog"]'); if (!d) throw new Error('dialog'); return d })
    expect(dlg.querySelector('[data-slot="te-send-readiness"]'))
      .toHaveTextContent('Raporda 5 eksik var (2 boş alan, 3 görsel yüklenmedi) — yine de gönderebilirsiniz.')
    expect(within(dlg).getByRole('button', { name: 'Gönder' })).toBeEnabled()
    unmount()

    te().report.mockResolvedValue(reportResponse({ values: { fields: FULL_FIELDS, issues: {}, texts: {} }, images: IMAGES }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(document.querySelector('[data-slot="te-report-actions"] [data-slot="te-send"]'))
    const dlg2 = await waitFor(() => { const d = document.querySelector('[data-slot="te-send-dialog"]'); if (!d) throw new Error('dialog'); return d })
    expect(dlg2.querySelector('[data-slot="te-send-readiness"]')).toBeNull()
  })

  it('onaya gönderme onayı eksikleri söyler ve yine de gönderir', async () => {
    te().report.mockResolvedValue(withApproval({ approval: { state: 'none', raw_state: null, stale: false },
      det: detail({ can_request: true, requested_by_name: null }) }))
    te().requestApproval.mockResolvedValue({ success: true, data: { state: 'pending', mailed: 1, failed: 0, mail_problem: null } })
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(lock()).toBeNull()                                               // onay istenmemiş: kilit yok
    fireEvent.click(document.querySelector('[data-slot="te-report-actions"] [data-slot="te-approval-request-bar"]'))
    const dlg = await waitFor(() => { const d = dialogEl(); if (!d) throw new Error('dialog'); return d })
    expect(dlg).toHaveTextContent('Raporda 5 eksik var (2 boş alan, 3 görsel yüklenmedi) — yine de onaya gönderebilirsiniz.')
    fireEvent.click(within(dlg).getByRole('button', { name: 'Onaya gönder' }))
    await waitFor(() => expect(te().requestApproval).toHaveBeenCalledWith('100'))
  })
})

describe('B3 — onay sürecinde düzenleme kilidi', () => {
  const PENDING = { state: 'pending', raw_state: 'PENDING', stale: false, requested_at: '2026-10-13T09:00:00Z' }

  it('bekleyen istek: kilitli açılır (alanlar kapalı, otomatik kayıt yok); kilit onayla açılır, sonra otomatik kayıt çalışır', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    try { openAll() } catch { /* yok */ }
    te().report.mockResolvedValue(withApproval({ approval: PENDING }))
    te().saveReport.mockImplementation(async (id, values, version) => ({ success: true, data: { version: version + 1, changed: true,
      updated_at: '2026-10-16T09:00:00Z', updated_by_name: 'Ayşe Yılmaz', values } }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(lock()).toHaveAttribute('data-state', 'pending')
    expect(lock()).toHaveTextContent('Rapor PO onayında — kilitli')
    expect(status()).toHaveTextContent('Onay sürecinde — kilitli')
    const total = screen.getByRole('textbox', { name: 'Toplam işlem adedi' })
    expect(total).toBeDisabled()
    expect(document.querySelector('[data-slot="te-next-step"]')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Kaydet' })).toBeNull()
    act(() => { vi.advanceTimersByTime(AUTOSAVE_MS * 2) })
    expect(te().saveReport).not.toHaveBeenCalled()

    // Vazgeç: kilit kalır
    fireEvent.click(within(lock()).getByRole('button', { name: 'Düzenlemeyi aç' }))
    let dlg = await waitFor(() => { const d = dialogEl(); if (!d) throw new Error('dialog'); return d })
    expect(dlg).toHaveTextContent('Düzenleme kilidi açılsın mı?')
    expect(dlg).toHaveTextContent('ilk değişiklik onay isteğini eskitir')
    expect(dlg).toHaveTextContent('30 saniye içinde')
    fireEvent.click(within(dlg).getByRole('button', { name: 'Vazgeç' }))
    await waitFor(() => expect(dialogEl()).toBeNull())
    expect(lock()).not.toBeNull()
    expect(total).toBeDisabled()

    // Onayla: kilit kalkar, düzenleme ve otomatik kayıt normal
    fireEvent.click(within(lock()).getByRole('button', { name: 'Düzenlemeyi aç' }))
    dlg = await waitFor(() => { const d = dialogEl(); if (!d) throw new Error('dialog'); return d })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Düzenlemeyi aç' }))
    await waitFor(() => expect(lock()).toBeNull())
    expect(screen.getByRole('textbox', { name: 'Toplam işlem adedi' })).toBeEnabled()
    fireEvent.change(screen.getByRole('textbox', { name: 'Toplam işlem adedi' }), { target: { value: '1100000' } })
    act(() => { vi.advanceTimersByTime(AUTOSAVE_MS) })
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    expect(te().saveReport.mock.calls[0][3]).toEqual({ auto: true, timeoutMs: AUTOSAVE_TIMEOUT_MS })
  })

  it('kilit düzenleyici başınadır: yeniden açılınca yine kilitli', async () => {
    te().report.mockResolvedValue(withApproval({ approval: PENDING }))
    const { unmount } = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(within(lock()).getByRole('button', { name: 'Düzenlemeyi aç' }))
    const dlg = await waitFor(() => { const d = dialogEl(); if (!d) throw new Error('dialog'); return d })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Düzenlemeyi aç' }))
    await waitFor(() => expect(lock()).toBeNull())
    unmount()
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(lock()).toHaveAttribute('data-state', 'pending')
  })

  it('onaylı (eskimemiş) rapor kilitli; "Yöneticilere gönder" yine çalışır', async () => {
    te().report.mockResolvedValue(withApproval({
      approval: { state: 'approved', requested_at: '2026-10-13T09:00:00Z', decided_at: '2026-10-14T09:00:00Z' },
      det: detail({ decided_by_name: 'Mehmet Kaya', can_send_executive: true }),
    }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(lock()).toHaveAttribute('data-state', 'approved')
    expect(lock()).toHaveTextContent('Rapor onaylandı — kilitli')
    expect(document.querySelector('[data-slot="te-report-actions"] [data-slot="te-send-exec"]')).toBeEnabled()
  })

  it('kilit yok: eskimiş istek, reddedilmiş istek, gönderilmiş rapor, akışı kaldırılmış kanal', async () => {
    const cases = [
      withApproval({ approval: { state: 'stale', raw_state: 'PENDING', stale: true, requested_at: '2026-10-13T09:00:00Z' } }),
      withApproval({ approval: { state: 'rejected', requested_at: '2026-10-13T09:00:00Z', decided_at: '2026-10-14T09:00:00Z', note: 'x' } }),
      withApproval({ approval: { state: 'approved', requested_at: '2026-10-13T09:00:00Z', decided_at: '2026-10-14T09:00:00Z' },
        report: { status: 'sent', last_sent_at: '2026-10-14T10:00:00Z', last_sent_to: 'ceo@example.com' } }),
      withApproval({ approval: PENDING, det: detail({ required: false, executive_count: 0, executive_emails: [], approvers: [] }) }),
    ]
    for (const res of cases) {
      te().report.mockResolvedValue(res)
      const { unmount } = render(<TechErrorReportsPage />)
      await screen.findByRole('heading', { name: TITLE })
      expect(lock()).toBeNull()
      unmount()
    }
  })

  it('düzenleme yetkisi olmayan görüntüleyen: bugünkü salt okunur görünüm, kilit bandı ve "Düzenlemeyi aç" yok', async () => {
    const res = withApproval({ approval: PENDING })
    res.data.can_edit = false
    te().report.mockResolvedValue(res)
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(lock()).toBeNull()
    expect(screen.queryByRole('button', { name: 'Düzenlemeyi aç' })).toBeNull()
    expect(status()).not.toHaveTextContent('Onay sürecinde')
  })
})

describe('B3 inceleme (2026-11-20) — yerinde yeniden kilit, kilitliyken kayıt / kısayol', () => {
  const PENDING = { state: 'pending', raw_state: 'PENDING', stale: false, requested_at: '2026-10-13T09:00:00Z' }
  const APPROVED = { state: 'approved', requested_at: '2026-10-13T09:00:00Z', decided_at: '2026-10-14T09:00:00Z' }
  const decider = (approval) => withApproval({ approval, det: detail({ can_decide: true, is_approver: true }) })

  async function unlock() {
    fireEvent.click(within(lock()).getByRole('button', { name: 'Düzenlemeyi aç' }))
    const dlg = await waitFor(() => { const d = dialogEl(); if (!d) throw new Error('dialog'); return d })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Düzenlemeyi aç' }))
    await waitFor(() => expect(lock()).toBeNull())
  }

  async function approveNow() {
    fireEvent.click(document.querySelector('[data-slot="te-approval-approve"]'))
    const confirm = await waitFor(() => { const b = document.querySelector('[data-slot="te-approval-confirm"]'); if (!b) throw new Error('dlg'); return b })
    fireEvent.click(confirm)
    await waitFor(() => expect(te().decideApproval).toHaveBeenCalledTimes(1))
  }

  it('kilit açıkken kaydedilmemiş taslak + karar: AYNI düzenleyicide yeniden kilitlenir, taslak kalır, otomatik kayıt (aralık ve sekme gizlenince) gitmez', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    try { openAll() } catch { /* yok */ }
    te().report.mockResolvedValue(decider(PENDING))
    te().decideApproval.mockResolvedValue({ success: true, data: { state: 'approved', send_state: 'PDF_BUSY' } })
    te().saveReport.mockImplementation(async (id, values, version) => ({ success: true, data: { version: version + 1, changed: true,
      updated_at: '2026-10-16T09:00:00Z', updated_by_name: 'Ayşe Yılmaz', values } }))
    render(<TechErrorReportsPage />)
    const heading = await screen.findByRole('heading', { name: TITLE })
    await unlock()
    const total = screen.getByRole('textbox', { name: 'Toplam işlem adedi' })
    fireEvent.change(total, { target: { value: '1100000' } })
    expect(te().saveReport).not.toHaveBeenCalled()

    // Karar verildi (yeniden okuma: onaylı, karar anı yeni) → parmak izi değişti → kilit yerinde yeniden uygulanır
    te().report.mockResolvedValue(decider(APPROVED))
    await approveNow()
    await waitFor(() => expect(lock()).toHaveAttribute('data-state', 'approved'))
    expect(lock()).toHaveAttribute('data-relocked', 'true')
    expect(lock().querySelector('[data-slot="te-lock-relocked"]')).toHaveTextContent(
      'Bu arada yeni bir onay isteği ya da kararı geldi; kilit yeniden uygulandı. Kaydedilmemiş değişiklikleriniz taslakta duruyor')
    expect(status()).toHaveTextContent('Onay sürecinde — kilitli · kaydedilmemiş değişiklikler taslakta')
    // Yeniden kurulmadı: aynı başlık ve giriş düğümü, yazılan değer duruyor, giriş kapalı
    expect(screen.getByRole('heading', { name: TITLE })).toBe(heading)
    const totalNow = screen.getByRole('textbox', { name: 'Toplam işlem adedi' })
    expect(totalNow).toBe(total)
    expect(totalNow).toHaveValue('1100000')
    expect(totalNow).toBeDisabled()

    // Kilitliyken otomatik kayıt YOK: aralık dolar, sekme gizlenir
    act(() => { vi.advanceTimersByTime(AUTOSAVE_MS * 3) })
    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true })
    act(() => { document.dispatchEvent(new Event('visibilitychange')) })
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
    await act(async () => { await Promise.resolve() })
    expect(te().saveReport).not.toHaveBeenCalled()

    // Kilitliyken PDF: kayıtlı içerik kullanılsın diye durur ve söyler (taslak sessizce atlanmaz)
    fireEvent.click(document.querySelector('[data-slot="te-pdf"]'))
    expect(await screen.findByText(/Rapor onay sürecinde kilitli ve taslakta kaydedilmemiş değişiklikler var/)).toBeInTheDocument()
    expect(te().downloadPdf).not.toHaveBeenCalled()

    // Kilit yeniden açılınca taslak otomatik kayıtla gider
    await unlock()
    act(() => { vi.advanceTimersByTime(AUTOSAVE_MS) })
    await waitFor(() => expect(te().saveReport).toHaveBeenCalledTimes(1))
    expect(te().saveReport.mock.calls[0][1].fields['total-tx']).toBe(1100000)
  })

  it('kilit açıkken YENİ onay isteği gelirse (istek anı değişti) yerinde yeniden kilitlenir; taslak temizse ayrıca not yok', async () => {
    try { openAll() } catch { /* yok */ }
    te().report.mockResolvedValue(decider(PENDING))
    te().decideApproval.mockResolvedValue({ success: true, data: { state: 'approved', send_state: 'PDF_BUSY' } })
    render(<TechErrorReportsPage />)
    const heading = await screen.findByRole('heading', { name: TITLE })
    await unlock()
    // Kararın ardından okuma başarısız; "Tekrar dene" yeni bir isteği getirir (başkası yeniden onaya gönderdi)
    te().report.mockResolvedValue({ success: false, error: 'Bağlantı hatası' })
    await approveNow()
    const banner = (await screen.findByText('Bağlantı hatası')).closest('[role="status"]')
    const retry = within(banner).getByRole('button', { name: 'Tekrar dene' })
    expect(lock()).toBeNull()                                        // okuma gelmedi: kilit hâlâ açık
    te().report.mockResolvedValue(decider({ ...PENDING, requested_at: '2026-10-15T08:00:00Z' }))
    fireEvent.click(retry)
    await waitFor(() => expect(lock()).toHaveAttribute('data-state', 'pending'))
    expect(lock()).toHaveAttribute('data-relocked', 'true')
    expect(lock().querySelector('[data-slot="te-lock-relocked"]'))
      .toHaveTextContent(/^Bu arada yeni bir onay isteği ya da kararı geldi; kilit yeniden uygulandı\.\s*$/)
    expect(status()).toHaveTextContent('Onay sürecinde — kilitli')
    expect(status()).not.toHaveTextContent('taslakta')
    expect(screen.getByRole('heading', { name: TITLE })).toBe(heading)
    expect(screen.getByRole('textbox', { name: 'Toplam işlem adedi' })).toBeDisabled()
  })

  it('"Şablonun son hâlini uygula": taze okuma raporu onay sürecinde (açılmamış parmak izi) gösterirse uygulanmaz, kilit iner', async () => {
    const upd = { differs: true, lost_values: 1, lost_images: 0 }
    let n = 0
    te().report.mockImplementation(async () => (n++ === 0
      ? reportResponse({ template_update: upd })
      : withApproval({ approval: PENDING, data: { template_update: upd } })))
    render(<TechErrorReportsPage />)
    fireEvent.click(await screen.findByRole('button', { name: 'Şablonun son hâlini uygula' }))
    expect(await screen.findByText('Rapor onay sürecinde kilitli. Değişiklik yapmak için önce "Düzenlemeyi aç" ile kilidi açın.'))
      .toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: 'Şablonun son hâli uygulansın mı?' })).toBeNull()
    expect(te().refreshTemplate).not.toHaveBeenCalled()
    expect(lock()).toHaveAttribute('data-state', 'pending')
  })

  it('kilitliyken Ctrl+S: tarayıcının kaydetmesi açılmaz, ne olduğu söylenir; kayıt gitmez', async () => {
    te().report.mockResolvedValue(withApproval({ approval: PENDING }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    const notPrevented = fireEvent.keyDown(window, { key: 's', code: 'KeyS', ctrlKey: true })
    expect(notPrevented).toBe(false)
    expect(await screen.findByText('Rapor onay sürecinde kilitli. Değişiklik yapmak için önce "Düzenlemeyi aç" ile kilidi açın.'))
      .toBeInTheDocument()
    expect(te().saveReport).not.toHaveBeenCalled()
  })

  it('düzenleme yetkisi olmayan görüntüleyende Ctrl+S tarayıcıya kalır (bugünkü davranış)', async () => {
    const res = withApproval({ approval: PENDING })
    res.data.can_edit = false
    te().report.mockResolvedValue(res)
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(fireEvent.keyDown(window, { key: 's', code: 'KeyS', ctrlKey: true })).toBe(true)
  })
})

describe('B1 inceleme (2026-11-20) — "Git" odağı, hatasız ay', () => {
  it('odaklanacak bir şeyi olmayan kalemde (takım adresi) "Git" odağı çipe döndürür — gövdeye düşmez', async () => {
    te().report.mockResolvedValue(reportResponse({ values: { fields: FULL_FIELDS, issues: {}, texts: {} }, images: IMAGES,
      team_email_set: false }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    fireEvent.click(chip())
    await waitFor(() => expect(sheet()).not.toBeNull())
    fireEvent.click(within(sheet()).getByRole('button', { name: /^Git: Takımın e-posta adresi tanımlı değil/ }))
    await waitFor(() => expect(sheet()).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(chip()))
  })

  const RATE_TPL = { ...TEMPLATE, sections: [
    { id: 'summary', kind: 'metrics', title: 'Veriler', fields: [
      { id: 'error-count', label: 'Ay içindeki teknik hata adedi', type: 'number' },
      { id: 'unmapped-count', label: 'Map yapılmamış hata adedi', type: 'number' },
      { id: 'unmapped-rate', label: 'Map yapılmamış hata oranı', type: 'percent',
        calc: { kind: 'ratio', num: 'unmapped-count', den: 'error-count' } },
    ] },
    TEMPLATE.sections.find((s) => s.id === 'charts'),
  ] }

  it('hatasız ay (0 ÷ 0): hesaplanan oran eksik sayılmaz; payı olan sıfır bölen (5 ÷ 0) sayılır', async () => {
    te().report.mockResolvedValue(reportResponse({ template: RATE_TPL, images: IMAGES,
      values: { fields: { 'error-count': 0, 'unmapped-count': 0 }, issues: {}, texts: {} } }))
    const { unmount } = render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(chip()).toHaveTextContent('Görsel 3/3 · Rapor hazır')
    expect(chip()).toHaveAttribute('data-tone', 'ok')
    unmount()

    te().report.mockResolvedValue(reportResponse({ template: RATE_TPL, images: IMAGES,
      values: { fields: { 'error-count': 0, 'unmapped-count': 5 }, issues: {}, texts: {} } }))
    render(<TechErrorReportsPage />)
    await screen.findByRole('heading', { name: TITLE })
    expect(chip()).toHaveTextContent('Görsel 3/3 · 1 boş alan')
  })
})

describe('B4 — şablon güncellemesinde silinecek adlar', () => {
  it('onay penceresi sayıların altında silinecek alan ve görsel adlarını listeler', async () => {
    te().report.mockResolvedValue(reportResponse({ template_update: { differs: true, lost_values: 5, lost_images: 3,
      lost_fields: [
        { id: 'sla-above', label: 'SLA hedefin üstünde mi?', section: 'Mobil Kanalı — Eylül 2026 Özeti', kind: 'field', count: 1 },
        { id: 'k1', label: 'Kart: Hata Artışı', section: 'Anomali', kind: 'topic', count: 4 },
      ],
      lost_slots: [{ id: 'daily-errors', label: 'Mobil Kanalı Teknik Hata (Günlük)', section: 'Grafikler', kind: 'slot' }],
      lost_fields_more: 0, lost_slots_more: 2 } }))
    render(<TechErrorReportsPage />)
    fireEvent.click(await screen.findByRole('button', { name: 'Şablonun son hâlini uygula' }))
    const dlg = await screen.findByRole('dialog', { name: 'Şablonun son hâli uygulansın mı?' })
    expect(within(dlg).getByText(/5 değer ve 3 görsel/)).toBeInTheDocument()
    const region = within(dlg).getByRole('region', { name: 'Silinecekler listesi' })
    const fields = [...region.querySelectorAll('[data-slot="te-tpl-loss-fields-item"]')].map((li) => li.textContent)
    expect(fields).toEqual([
      'SLA hedefin üstünde mi? · Mobil Kanalı — Eylül 2026 Özeti',
      'Kart: Hata Artışı — anomali konusu, 4 değer · Anomali',
    ])
    expect(region.querySelector('[data-slot="te-tpl-loss-slots-item"]')).toHaveTextContent('Mobil Kanalı Teknik Hata (Günlük) · Grafikler')
    expect(region.querySelector('[data-slot="te-tpl-loss-slots-more"]')).toHaveTextContent('ve 2 tane daha')
    expect(region.querySelector('[data-slot="te-tpl-loss-fields-more"]')).toBeNull()
    fireEvent.click(within(dlg).getByRole('button', { name: 'Vazgeç' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Şablonun son hâli uygulansın mı?' })).toBeNull())
    expect(te().refreshTemplate).not.toHaveBeenCalled()
  })

  it('tablo / metin bölümü: ad zaten bölümün adıysa bölüm ikinci kez yazılmaz (inceleme 2026-11-20)', () => {
    render(<TemplateLossList upd={{ lost_fields: [
      { id: 'tb', label: 'Mobil tablosu', section: 'Mobil tablosu', kind: 'table', count: 4 },
      { id: 'note', label: 'Grafikler hakkında', section: ' Grafikler hakkında ', kind: 'text', count: 1 },
      { id: 'emb', label: 'Gömülü adet', section: 'Yönetici Özeti', kind: 'field', count: 1 },
    ] }} />)
    const items = [...document.querySelectorAll('[data-slot="te-tpl-loss-fields-item"]')].map((li) => li.textContent)
    expect(items).toEqual(['Mobil tablosu — 4 tablo hücresi', 'Grafikler hakkında — elle yazılmış metin',
      'Gömülü adet · Yönetici Özeti'])
  })

  it('eski sunucu (liste yok): yalnız sayılar — liste çizilmez', async () => {
    te().report.mockResolvedValue(reportResponse({ template_update: { differs: true, lost_values: 2, lost_images: 1 } }))
    render(<TechErrorReportsPage />)
    fireEvent.click(await screen.findByRole('button', { name: 'Şablonun son hâlini uygula' }))
    const dlg = await screen.findByRole('dialog', { name: 'Şablonun son hâli uygulansın mı?' })
    expect(within(dlg).getByText(/2 değer ve 1 görsel/)).toBeInTheDocument()
    expect(dlg.querySelector('[data-slot="te-tpl-loss"]')).toBeNull()
  })
})
