import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, within, waitFor, act } from './test-utils.jsx'
import RenewalPlanModal from '../components/RenewalPlanModal.jsx'
import { quickPicks, planState, offDaySuggestion, timelineModel } from '../components/renewalPlanModel.js'

/**
 * Yenileme planı penceresi (2026-09-27 shadcn + mweb yeniden tasarımı). Tarih seçici GERÇEK ui/DateTimeField: seçilen
 * gün tetik düğmesinin metninden (dd.MM.yyyy) okunur. "Bugün" Pazartesi 28.09.2026'ya SABİTLENİR (yalnız Date taklit
 * edilir — zamanlayıcılar gerçek) → kayan pencere / sabit tarih zaman bombası yok. Gerçek kimlik yok (example.com).
 *
 * Fikstür takvimi (2026): renew-by Cum 09.10 · bitiş Cum 23.10 · Cmt 10.10 hafta sonu · Per 29.10 resmî tatil.
 */
vi.mock('../api/client', async () => {
  const real = await vi.importActual('../api/client')
  return { ...real, api: { forecastPlan: vi.fn(), forecastUnplan: vi.fn() } }
})
import { api } from '../api/client'

const ROW = {
  domain: 'www.example.com', renewal_planned_at: '', renewal_planned_note: '',
  renew_by_key: '2026-10-09', expiry_key: '2026-10-23',
}

function setup(rowExtra = {}, props = {}) {
  const handlers = { onClose: vi.fn(), onSaved: vi.fn(), onCleared: vi.fn() }
  const utils = render(<RenewalPlanModal row={{ ...ROW, ...rowExtra }} {...handlers} {...props} />)
  const dlg = screen.getByRole('dialog', { name: /Renewal plan — www\.example\.com/ })
  return { ...utils, ...handlers, dlg }
}
const trigger = () => document.querySelector('[data-slot="date-picker-trigger"]')
const saveBtn = (dlg) => within(dlg).getByRole('button', { name: /^Save plan/ })
const warning = (kind) => document.querySelector(`[data-slot="plan-warning"][data-kind="${kind}"]`)

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.setItem('site-monitor-lang', 'en') } catch { /* yoksay */ }
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date(2026, 8, 28, 10, 0, 0))   // yerel Pzt 28.09.2026 10:00
})
afterEach(() => { vi.useRealTimers() })

describe('renewalPlanModel — saf kurallar', () => {
  it('hızlı seçimler iş gününe düşer, tarih sıralı, bitişten sonrasını ve geçmişi önermez', () => {
    expect(quickPicks({ today: '2026-09-28', renewBy: '2026-10-10', expiry: '2026-10-23' }))
      .toEqual([{ key: 'week', date: '2026-10-05' }, { key: 'renewBy', date: '2026-10-09' }, { key: 'twoWeeks', date: '2026-10-12' }])
    // renew-by geçmiş → çapa yerine "sonraki iş günü"; bitiş 6 gün sonra → 2 hafta önerilmez
    expect(quickPicks({ today: '2026-09-28', renewBy: '2026-09-20', expiry: '2026-10-04' }))
      .toEqual([{ key: 'nextWorkingDay', date: '2026-09-29' }])
    // renew-by yok (alan adı) → bitişten 2 hafta önce
    expect(quickPicks({ today: '2026-09-28', renewBy: null, expiry: '2026-11-13' })[2]).toEqual({ key: 'beforeExpiry', date: '2026-10-30' })
  })

  it('planState / offDaySuggestion / timelineModel', () => {
    const base = { today: '2026-09-28', renewBy: '2026-10-09', expiry: '2026-10-23' }
    expect(planState({ ...base, planned: '2026-09-21' })).toBe('past')
    expect(planState({ ...base, planned: '2026-10-09' })).toBe('ok')
    expect(planState({ ...base, planned: '2026-10-14' })).toBe('afterRenewBy')
    expect(planState({ ...base, planned: '2026-10-23' })).toBe('onExpiry')
    expect(planState({ ...base, planned: '2026-10-26' })).toBe('afterExpiry')
    expect(offDaySuggestion('2026-10-10', '2026-09-28')).toEqual({ date: '2026-10-09', direction: 'before' })
    // önceki iş günü geçmişte kalırsa sonraki iş günü
    expect(offDaySuggestion('2026-10-04', '2026-10-03')).toEqual({ date: '2026-10-05', direction: 'after' })
    expect(offDaySuggestion('2026-10-09', '2026-09-28')).toBeNull()
    expect(timelineModel({ ...base, planned: '2026-10-26' })).toMatchObject({ planned: 100, state: 'afterExpiry' })
    expect(timelineModel({ ...base, expiry: '2026-09-20', planned: null })).toBeNull()
  })
})

describe('RenewalPlanModal — varsayılan tarih, özet, odak', () => {
  it('kayıtlı plan varsa onun günü; özet kayıtlı plan + renew-by + bitiş satırlarını ve kalan gün rozetlerini gösterir', () => {
    const { dlg } = setup({ renewal_planned_at: '2026-10-05', renewal_planned_note: 'CSR ready', renewal_planned_by: 'Takım A' })
    expect(trigger()).toHaveTextContent('05.10.2026')
    const rows = [...dlg.querySelectorAll('[data-slot="plan-date-row"]')].map((r) => r.getAttribute('data-kind'))
    expect(rows).toEqual(['saved', 'renewBy', 'expiry'])
    const saved = dlg.querySelector('[data-slot="plan-date-row"][data-kind="saved"]')
    expect(saved).toHaveTextContent(/Saved plan\s*Mon, 5 Oct 2026\s*set by Takım A\s*in 7 days/)
    expect(dlg.querySelector('[data-slot="plan-date-row"][data-kind="expiry"]')).toHaveTextContent(/Expires\s*Fri, 23 Oct 2026\s*in 25 days/)
    expect(within(dlg).getByLabelText(/^Note$/)).toHaveValue('CSR ready')
    // değişiklik yokken kayıtlı planı yeniden kaydetmek anlamsız
    expect(saveBtn(dlg)).toBeDisabled()
  })

  it('plan yoksa renew-by (iş gününe çekilir: Cmt 10.10 → Cum 09.10); açılışta odak tarih tetiğinde', () => {
    const { dlg } = setup({ renew_by_key: '2026-10-10' })
    expect(trigger()).toHaveTextContent('09.10.2026')
    expect(document.activeElement).toBe(trigger())
    expect(saveBtn(dlg)).toBeEnabled()
    expect(within(dlg).getByRole('radio', { name: /Renew-by date/ })).toHaveAttribute('aria-checked', 'true')
  })

  it('plan ve renew-by yoksa boş, Kaydet kapalı; renew-by geçmişteyse de boş (geçmiş gün önerilmez)', () => {
    const { dlg, unmount } = setup({ renew_by_key: null })
    expect(trigger()).toHaveTextContent(/Select|Seç|—/)
    expect(saveBtn(dlg)).toBeDisabled()
    unmount()
    const again = setup({ renew_by_key: '2026-09-20' })
    expect(saveBtn(again.dlg)).toBeDisabled()
    expect(within(again.dlg).getByRole('radio', { name: /Next working day/ })).toBeInTheDocument()
  })

  it('seçilen günün göreli açıklaması tetiğe aria-describedby ile bağlı', () => {
    setup()
    const rel = document.querySelector('[data-slot="plan-relation"]')
    expect(rel).toHaveTextContent('Friday · in 11 days · on the renew-by date')
    expect(trigger().getAttribute('aria-describedby')).toContain(rel.id)
  })
})

describe('RenewalPlanModal — hızlı seçim ve uyarılar', () => {
  it('hızlı seçim günü kurar ve seçili görünür', () => {
    const { dlg } = setup()
    fireEvent.click(within(dlg).getByRole('radio', { name: /In a week/ }))
    expect(trigger()).toHaveTextContent('05.10.2026')
    expect(within(dlg).getByRole('radio', { name: /In a week/ })).toHaveAttribute('aria-checked', 'true')
    fireEvent.click(within(dlg).getByRole('radio', { name: /In two weeks/ }))
    expect(trigger()).toHaveTextContent('12.10.2026')
    expect(document.querySelector('[data-slot="plan-relation"]')).toHaveTextContent('3 days after the renew-by date')
  })

  it('hafta sonu uyarısı + "taşı" eylemi son iş gününe çeker; uyarı tetiğe bağlı', () => {
    const { dlg } = setup({ renewal_planned_at: '2026-10-10' })
    const w = warning('offday')
    expect(w).toHaveTextContent(/Sat, 10 Oct 2026 falls on a weekend\. The last working day before it is Fri, 9 Oct 2026\./)
    expect(trigger().getAttribute('aria-describedby')).toContain(w.id)
    fireEvent.click(within(dlg).getByRole('button', { name: /Move to Fri 9 Oct/ }))
    expect(trigger()).toHaveTextContent('09.10.2026')
    expect(warning('offday')).toBeNull()
    expect(trigger().getAttribute('aria-describedby')).not.toContain(w.id)
  })

  it('resmî tatil uyarısı', () => {
    setup({ renewal_planned_at: '2026-10-29', expiry_key: '2026-11-13' })
    expect(warning('offday')).toHaveTextContent(/Thu, 29 Oct 2026 is a public holiday\./)
  })

  it('renew-by sonrası uyarı (kaydet açık); bitiş sonrası TEHLİKE uyarısı; bitiş günü; geçmiş gün', () => {
    const { dlg, unmount } = setup({ renewal_planned_at: '2026-10-14' })
    expect(warning('afterRenewBy')).toHaveTextContent(/after the renew-by date \(Fri, 9 Oct 2026\)/)
    expect(warning('afterRenewBy').closest('[data-slot="alert"]')).toHaveAttribute('data-tone', 'warning')
    fireEvent.change(within(dlg).getByLabelText(/^Note$/), { target: { value: 'x' } })
    expect(saveBtn(dlg)).toBeEnabled()
    unmount()

    const after = setup({ renewal_planned_at: '2026-10-26' })
    expect(warning('afterExpiry')).toHaveTextContent(/The certificate expires on Fri, 23 Oct 2026, before this date/)
    expect(warning('afterExpiry').closest('[data-slot="alert"]')).toHaveAttribute('data-tone', 'danger')
    expect(document.querySelector('[data-slot="plan-timeline"]')).toHaveAttribute('data-state', 'afterExpiry')
    after.unmount()

    const on = setup({ renewal_planned_at: '2026-10-23' })
    expect(warning('onExpiry')).toBeInTheDocument()
    on.unmount()

    setup({ renewal_planned_at: '2026-09-21' })
    expect(warning('past')).toHaveTextContent(/already passed/)
    expect(warning('offday')).toBeNull()   // geçmiş gün için "taşı" önerilmez
  })
})

describe('RenewalPlanModal — not, kaydet, kaldır, meşgul', () => {
  it('not sayacı canlı; üst sınır 500', () => {
    const { dlg } = setup()
    const note = within(dlg).getByLabelText(/^Note$/)
    expect(note).toHaveAttribute('maxLength', '500')
    fireEvent.change(note, { target: { value: 'CSR is ready' } })
    const counter = dlg.querySelector('[data-slot="plan-note-counter"]')
    expect(counter).toHaveTextContent('12/500')
    expect(counter).toHaveTextContent('12 of 500 characters used')
    expect(note.getAttribute('aria-describedby')).toBeTruthy()
  })

  it('`plan` verilince O çağrılır (api değil) ve onSaved API verisini alır', async () => {
    const data = { domain: 'www.example.com', renewal_planned_at: '2026-10-05' }
    const plan = vi.fn().mockResolvedValue({ success: true, data })
    const { dlg, onSaved } = setup({}, { plan })
    fireEvent.click(within(dlg).getByRole('radio', { name: /In a week/ }))
    fireEvent.change(within(dlg).getByLabelText(/^Note$/), { target: { value: 'ticket 42' } })
    fireEvent.click(saveBtn(dlg))
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(data))
    expect(plan).toHaveBeenCalledWith('2026-10-05', 'ticket 42')
    expect(api.forecastPlan).not.toHaveBeenCalled()
  })

  it('`plan` yoksa api.forecastPlan; Ctrl+Enter kaydeder ve çift basış TEK istek', async () => {
    let resolve
    api.forecastPlan.mockImplementation(() => new Promise((r) => { resolve = r }))
    const { dlg, onSaved } = setup()
    const note = within(dlg).getByLabelText(/^Note$/)
    fireEvent.keyDown(note, { key: 'Enter', ctrlKey: true })
    fireEvent.keyDown(note, { key: 'Enter', ctrlKey: true })
    expect(api.forecastPlan).toHaveBeenCalledTimes(1)
    expect(api.forecastPlan).toHaveBeenCalledWith('www.example.com', '2026-10-09', '')
    await act(async () => { resolve({ success: true, data: { ok: 1 } }) })
    expect(onSaved).toHaveBeenCalledWith({ ok: 1 })
  })

  it('istek sürerken tüm düğmeler kapalı, Kaydet aria-busy; bitince açılır (hata → toast, pencere açık)', async () => {
    let resolve
    const plan = vi.fn(() => new Promise((r) => { resolve = r }))
    const { dlg, onSaved } = setup({ renewal_planned_at: '2026-10-05' }, { plan })
    fireEvent.click(within(dlg).getByRole('radio', { name: /In two weeks/ }))
    fireEvent.click(saveBtn(dlg))
    expect(saveBtn(dlg)).toBeDisabled()
    expect(saveBtn(dlg)).toHaveAttribute('aria-busy', 'true')
    expect(within(dlg).getByRole('button', { name: /^Cancel$/ })).toBeDisabled()
    expect(within(dlg).getByRole('button', { name: /^Remove plan$/ })).toBeDisabled()
    expect(within(dlg).getByRole('button', { name: /Close|Kapat/ })).toBeDisabled()
    await act(async () => { resolve({ success: false, error: 'Nope' }) })
    expect(onSaved).not.toHaveBeenCalled()
    expect(saveBtn(dlg)).toBeEnabled()
    expect(saveBtn(dlg)).not.toHaveAttribute('aria-busy')
  })

  it('kaldır ONAY ister: "Keep plan" hiçbir şey yapmaz; onayda `unplan` çağrılır', async () => {
    const unplan = vi.fn().mockResolvedValue({ success: true, data: { domain: 'www.example.com', renewal_planned_at: null } })
    const { dlg, onCleared } = setup({ renewal_planned_at: '2026-10-05' }, { unplan })
    fireEvent.click(within(dlg).getByRole('button', { name: /^Remove plan$/ }))
    let confirm = await screen.findByRole('dialog', { name: 'Remove this plan?' })
    expect(confirm).toHaveTextContent(/www\.example\.com on Mon, 5 Oct 2026/)
    fireEvent.click(within(confirm).getByRole('button', { name: 'Keep plan' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Remove this plan?' })).toBeNull())
    expect(unplan).not.toHaveBeenCalled()
    fireEvent.click(within(dlg).getByRole('button', { name: /^Remove plan$/ }))
    confirm = await screen.findByRole('dialog', { name: 'Remove this plan?' })
    fireEvent.click(within(confirm).getByRole('button', { name: 'Remove plan' }))
    await waitFor(() => expect(onCleared).toHaveBeenCalledWith({ domain: 'www.example.com', renewal_planned_at: null }))
    expect(api.forecastUnplan).not.toHaveBeenCalled()
  })

  it('`unplan` yoksa api.forecastUnplan; plan yoksa Kaldır düğmesi yok', async () => {
    api.forecastUnplan.mockResolvedValue({ success: true, data: {} })
    const { dlg, onCleared, unmount } = setup({ renewal_planned_at: '2026-10-05' })
    fireEvent.click(within(dlg).getByRole('button', { name: /^Remove plan$/ }))
    const confirm = await screen.findByRole('dialog', { name: 'Remove this plan?' })
    fireEvent.click(within(confirm).getByRole('button', { name: 'Remove plan' }))
    await waitFor(() => expect(api.forecastUnplan).toHaveBeenCalledWith('www.example.com'))
    expect(onCleared).toHaveBeenCalledWith({})
    unmount()
    const fresh = setup()
    expect(within(fresh.dlg).queryByRole('button', { name: /Remove plan/ })).toBeNull()
  })
})

describe('RenewalPlanModal — kapatma koruması ve alan adı (hint) kipi', () => {
  it('değişiklik yoksa İptal doğrudan kapatır', () => {
    const { dlg, onClose } = setup()
    fireEvent.click(within(dlg).getByRole('button', { name: /^Cancel$/ }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('değişiklik varsa İptal / Escape sorar: "Keep editing" açık bırakır, "Discard" kapatır', async () => {
    const { dlg, onClose } = setup()
    fireEvent.change(within(dlg).getByLabelText(/^Note$/), { target: { value: 'draft' } })
    fireEvent.click(within(dlg).getByRole('button', { name: /^Cancel$/ }))
    let ask = await screen.findByRole('dialog', { name: 'Discard your changes?' })
    fireEvent.click(within(ask).getByRole('button', { name: 'Keep editing' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Discard your changes?' })).toBeNull())
    expect(onClose).not.toHaveBeenCalled()
    expect(within(dlg).getByLabelText(/^Note$/)).toHaveValue('draft')
    fireEvent.keyDown(document.activeElement || dlg, { key: 'Escape' })
    ask = await screen.findByRole('dialog', { name: 'Discard your changes?' })
    fireEvent.click(within(ask).getByRole('button', { name: 'Discard' }))
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1))
  })

  it('hint kipinde (alan adı kaydı) sertifikaya özgü dil ve renew-by görselleri YOK', () => {
    const hint = 'Registration expires 13.11.2026. The planned date shows as a badge on the card.'
    const { dlg } = setup({ renew_by_key: '2026-10-09', expiry_key: '2026-11-13', renewal_planned_at: '2026-11-20' }, { hint })
    expect(within(dlg).getByText(hint)).toBeInTheDocument()
    expect(dlg.textContent).not.toMatch(/renew[- ]by/i)
    expect(dlg.textContent).not.toMatch(/certificate/i)
    expect(dlg.querySelector('[data-slot="plan-date-row"][data-kind="renewBy"]')).toBeNull()
    expect(dlg.querySelector('[data-slot="plan-timeline-renewby"]')).toBeNull()
    expect(within(dlg).getByRole('radio', { name: /2 weeks before expiry/ })).toBeInTheDocument()
    expect(warning('afterExpiry')).toHaveTextContent(/It expires on Fri, 13 Nov 2026, before this date/)
  })
})
