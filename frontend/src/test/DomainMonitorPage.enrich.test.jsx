import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const dl = vi.hoisted(() => ({ ok: true, calls: [] }))
vi.mock('../utils/csvExport.js', async (orig) => ({
  ...(await orig()),
  downloadBlob: (name, blob) => { dl.calls.push({ name, blob }); return dl.ok },
}))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      listGroups:          vi.fn(() => Promise.resolve({ success: true, data: [] })),
      getDomainMonitors:   vi.fn(),
      getCheckHistory:     vi.fn(() => Promise.resolve({ success: true, data: { items: [], counts: { total: 0, fail: 0 }, buckets: [], alerts: [],
        range: { from: '2026-01-01T00:00:00', to: '2026-01-02T00:00:00' }, total: 0, page: 0, size: 50 } })),
      getCheckHistoryCsvUrl: vi.fn(() => '#'),
      monitorDefaults:     vi.fn(() => Promise.resolve({ success: true, data: { domain: {} } })),
      domainRenewalPlanBulk: vi.fn(),
    },
    admin: { getTeams: vi.fn(() => Promise.resolve({ success: true, data: [] })) },
  }),
}))
import { api } from '../api/client'
import DomainMonitorPage from '../components/DomainMonitorPage.jsx'

const day = (offset) => { const d = new Date(); d.setDate(d.getDate() + offset); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` }
const base = { team_name: 'SY-A', group_name: 'Kurumsal', tags: 'prod', status: 'OK', active: true, can_edit: true, can_check: true,
  warning_days: 30, critical_days: 7, checked_at: '2026-11-01T00:00:00' }
const rows = [
  { ...base, id: 1, name: 'godaddy-1', domain: 'godaddy-1.com', registrar: 'GoDaddy.com, LLC', source: 'RDAP', expiry_date: day(-2), days_remaining: 9 },
  { ...base, id: 2, name: 'godaddy-2', domain: 'godaddy-2.com', registrar: 'GODADDY.COM, LLC', source: 'RDAP_REGISTRY', expiry_date: day(90), days_remaining: 90 },
  { ...base, id: 3, name: 'whois-tr', domain: 'whois-tr.com.tr', registrar: 'İsimtescil', source: 'WHOIS', expiry_date: day(40), days_remaining: 40,
    renewal_planned_at: '2099-01-01' },
  { ...base, id: 4, name: 'unknown', domain: 'unknown.com', registrar: null, source: 'NONE', expiry_date: null, days_remaining: null },
]
const cards = () => [...document.querySelectorAll('.upt-grid [data-monitor-open]')].map((b) => b.textContent.trim()).sort()
const combo = (name) => screen.getByRole('combobox', { name })
function pick(name, optionText) {
  fireEvent.mouseDown(combo(name))
  const opt = [...document.querySelectorAll('[role="listbox"] [role="option"]')].find((o) => optionText.test(o.textContent))
  if (!opt) throw new Error(`seçenek yok: ${optionText}`)
  fireEvent.mouseDown(opt)
}

beforeEach(() => {
  vi.clearAllMocks()
  dl.ok = true
  dl.calls = []
  api.monitoring.getDomainMonitors.mockResolvedValue({ success: true, data: rows })
  window.history.replaceState({}, '', '/?tab=domain')
})
afterEach(() => window.history.replaceState({}, '', '/'))

describe('DomainMonitorPage — C1 ek süzgeçler', () => {
  it('kalan gün canlı: kayıtlı 9 gün dese de bitişi geçmiş satır "dolmuş" sayılır ve en başta', async () => {
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('godaddy-1.com')
    const first = document.querySelector('.upt-grid [data-monitor-open]')
    expect(first).toHaveTextContent('godaddy-1.com')
  })

  it('registrar Türkçe katlanır ve sayılır; kaynak RDAP ailesi; süre durumu; Süzgeçleri temizle hepsini sıfırlar', async () => {
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('godaddy-1.com')
    fireEvent.mouseDown(combo('Registrar'))
    const labels = [...document.querySelectorAll('[role="listbox"] [role="option"]')].map((o) => o.textContent)
    expect(labels).toContain('All registrars (4)')
    expect(labels.some((l) => /GoDaddy\.com, LLC \(2\)/i.test(l))).toBe(true)
    expect(labels.at(-1)).toBe('Registrar unknown (1)')
    fireEvent.mouseDown([...document.querySelectorAll('[role="listbox"] [role="option"]')].find((o) => /GoDaddy/i.test(o.textContent)))
    await waitFor(() => expect(cards()).toEqual(['godaddy-1.com', 'godaddy-2.com']))
    pick('Expiry state', /^Expired \(1\)$/)
    await waitFor(() => expect(cards()).toEqual(['godaddy-1.com']))
    // kaynak sayımı öteki iki ek süzgeç uygulanmış listeden
    fireEvent.mouseDown(combo('Data source'))
    const src = [...document.querySelectorAll('[role="listbox"] [role="option"]')].map((o) => o.textContent)
    expect(src).toEqual(['All sources (1)', 'Source: RDAP (1)', 'Source: WHOIS (0)', 'Source unknown (0)'])
    fireEvent.keyDown(document.activeElement, { key: 'Escape' })
    fireEvent.click(document.querySelector('[data-slot="domain-filter-clear"]'))
    await waitFor(() => expect(cards()).toHaveLength(4))
    expect(document.querySelector('[data-slot="domain-filter-clear"]')).toBeNull()
  })

  it('URL\'den okunur (kayıtlı görünüm / paylaşılan bağlantı): ?dx=unknown&ds=unknown', async () => {
    window.history.replaceState({}, '', '/?tab=domain&dx=unknown&ds=unknown')
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(cards()).toEqual(['unknown.com']))
  })
})

describe('DomainMonitorPage — C3 takvim (.ics)', () => {
  it('görünen listeyi indirir; bitişi bilinmeyen atlanır ve bildirimde sayılır', async () => {
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('godaddy-1.com')
    pressMenuTrigger(screen.getByRole('button', { name: /^(dışa aktar|export)$/i }))
    const ics = await screen.findByRole('menuitem', { name: /calendar \(\.ics\)/i })
    fireEvent.click(ics)
    await waitFor(() => expect(dl.calls).toHaveLength(1))
    expect(dl.calls[0].name).toMatch(/\.ics$/)
    expect(await screen.findByText(/3 expiry dates were exported.*1 monitors with an unknown expiry were skipped/i)).toBeInTheDocument()
  })

  it('tarayıcı indirmeyi başlatamazsa HATA (asla "aktarıldı" değil)', async () => {
    dl.ok = false
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByText('godaddy-1.com')
    pressMenuTrigger(screen.getByRole('button', { name: /^(dışa aktar|export)$/i }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /calendar \(\.ics\)/i }))
    expect(await screen.findByText(/could not download the calendar file/i)).toBeInTheDocument()
    expect(screen.queryByText(/were exported to the calendar file/i)).toBeNull()
  })
})

describe('DomainMonitorPage — D1 toplu yenileme planı', () => {
  const selectAll = async () => {
    await screen.findByText('godaddy-1.com')
    for (const cb of document.querySelectorAll('.upt-grid [role="checkbox"]')) fireEvent.click(cb)
    return await waitFor(() => { const b = document.querySelector('[data-slot="domain-bulk-plan"]'); expect(b).not.toBeNull(); return b })
  }

  it('önizleme → tek istek → satır satır sonuç; tarih zorunlu (alanın altında); görünmeyen satırın adı gizli', async () => {
    api.monitoring.domainRenewalPlanBulk.mockResolvedValue({ success: true, data: { action: 'set', done: 2, skipped: 2, failed: 0, results: [
      { id: 1, name: 'godaddy-1', status: 'done', reason: 'done' },
      { id: 2, name: 'godaddy-2', status: 'done', reason: 'done' },
      { id: 3, name: 'whois-tr', status: 'skipped', reason: 'unchanged' },
      { id: 4, name: null, status: 'skipped', reason: 'not_found' },
    ] } })
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await selectAll())
    const dlg = await screen.findByRole('dialog')
    // bitişi bilinmeyen önizlemede atlanır
    expect(within(dlg).getAllByText('Expiry date unknown — no plan can be set').length).toBeGreaterThan(0)
    fireEvent.click(within(dlg).getByRole('button', { name: 'Apply plan' }))
    expect(await within(dlg).findByText(/a bulk plan needs a date/i)).toBeInTheDocument()
    expect(api.monitoring.domainRenewalPlanBulk).not.toHaveBeenCalled()
    fireEvent.change(within(dlg).getByLabelText(/Plan date/), { target: { value: '2026-12-20' } })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Apply plan' }))
    await waitFor(() => expect(api.monitoring.domainRenewalPlanBulk).toHaveBeenCalledTimes(1))
    const body = api.monitoring.domainRenewalPlanBulk.mock.calls[0][0]
    expect({ ...body, ids: [...body.ids].sort() }).toEqual({ action: 'set', ids: [1, 2, 3, 4], date: '2026-12-20', note: null })
    await waitFor(() => expect(dlg.querySelector('[data-slot="bulk-dialog"]')).toHaveAttribute('data-phase', 'result'))
    const result = [...dlg.querySelectorAll('[data-slot="bulk-result-row"]')].map((r) => [r.dataset.status, r.dataset.reason])
    expect(result).toEqual(expect.arrayContaining([['done', 'done'], ['skipped', 'unchanged'], ['skipped', 'not_found']]))
  })

  it('kaldır: planı olmayan "zaten bu durumda" önizlenir; sunucu alan hatası alanın altında', async () => {
    api.monitoring.domainRenewalPlanBulk.mockResolvedValue({ success: false, field: 'ids', error: 'Select at least one monitor.' })
    render(<DomainMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await selectAll())
    const dlg = await screen.findByRole('dialog')
    fireEvent.click(within(dlg).getByRole('radio', { name: 'Remove plan' }))
    const preview = [...dlg.querySelectorAll('[data-slot="bulk-preview-row"]')]
    expect(preview.filter((r) => r.dataset.state === 'change')).toHaveLength(1)
    fireEvent.click(within(dlg).getByRole('button', { name: 'Remove plans' }))
    expect(await within(dlg).findByText('Select at least one monitor.')).toBeInTheDocument()
    expect(dlg.querySelector('[data-slot="bulk-dialog"]')).toHaveAttribute('data-phase', 'preview')
  })
})

describe('DomainRegistrationTab — A4 canlı sorgu sınırı', () => {
  it('429 → sunucunun metni, veri korunur, Yenile bekleme bitince yeniden kullanılabilir', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      const { default: DomainRegistrationTab } = await import('../components/DomainRegistrationTab.jsx')
      const reg = { ...rows[1], checked_at: '2026-11-15T08:00:00' }
      api.monitoring.getDomainRegistration = vi.fn((id, { live } = {}) => Promise.resolve(live
        ? { success: false, code: 'RATE_LIMITED', retry_after_seconds: 30, error: 'Too many live lookups: at most 6 per minute. Try again in 30 s.' }
        : { success: true, data: reg }))
      api.monitoring.getDomainReminders = vi.fn(() => Promise.resolve({ success: true, data: { thresholds: [], items: [] } }))
      render(<DomainRegistrationTab monitor={{ id: 2, can_check: true }} />)
      const refresh = await waitFor(() => { const b = document.querySelector('[data-slot="domain-reg-refresh"]'); expect(b).not.toBeNull(); return b })
      fireEvent.click(refresh)
      expect(await screen.findByText(/Too many live lookups: at most 6 per minute/)).toBeInTheDocument()
      expect(document.querySelector('[data-slot="domain-registration"]')).not.toBeNull()   // veri korunur
      expect(document.querySelector('[data-slot="domain-reg-refresh"]')).toBeDisabled()
      await act(async () => { vi.advanceTimersByTime(31_000) })
      await waitFor(() => expect(document.querySelector('[data-slot="domain-reg-refresh"]')).not.toBeDisabled())
    } finally {
      vi.useRealTimers()
    }
  })
})

