import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

/**
 * 7/24 Konsolu (2026-10-04): KPI kartları (süzgeç), "7/24'e gidenler" ve diğer süzgeçler sunucuya gider, satırda 7/24 iletimi +
 * son arama, "Ara" penceresi (arama kartı TELEFONLA + arama kaydı formu), izinsiz kullanıcıda salt okunur durum, dar kapta
 * kartlar (genişlik kancası taklit), 30 sn'lik görünür-sekme yoklaması.
 */
let WIDTH = 1400
vi.mock('../hooks/useElementWidth.js', () => ({
  useElementWidthState: () => [WIDTH, () => {}],
  useElementWidth: () => [{ current: null }, WIDTH],
}))
const intervalSpy = vi.hoisted(() => vi.fn())
vi.mock('../hooks/useVisibleInterval.js', () => ({ useVisibleInterval: (fn, ms) => intervalSpy(fn, ms) }))

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  api: withApiFallback({
    noc: { console: vi.fn(), callSheet: vi.fn() },
    nocCalls: { list: vi.fn(), create: vi.fn(), remove: vi.fn(), contacts: vi.fn() },
  }),
}))
import { api } from '../api/client'
import NocConsole from '../components/noc/console/NocConsole.jsx'
import { toIso } from '../components/admin/alerts/nocCallModel.js'
import { REFRESH_MS } from '../components/noc/console/nocConsoleModel.js'

const ago = (m) => toIso(Date.now() - m * 60_000)
const ROW_NEEDS = {
  id: 10, alert_type: 'PING_DOWN', family: 'ping', level: 'CRITICAL', domain: 'a.example.com', created_at: ago(30), resolved: false,
  team_id: 1, team_name: 'Takım A', monitor: { type: 'PING', tab: 'ping', id: 77, name: 'A ping' },
  channels: { email_sent: 3, email_failed: 1, webhook_sent: 2, push_sent: 5, push_failed: 0, noc: true },
  noc: { sent_at: ago(25), via_storm: false, groups: 'NOC Ana' }, call_count: 0, last_call: null, can_call: true,
}
const ROW_CALLED = {
  ...ROW_NEEDS, id: 11, domain: 'b.example.com', team_id: 2, team_name: 'Takım B', monitor: { type: 'HTTP', tab: 'http', id: 88, name: 'B site' },
  call_count: 2, last_call: { contacted_name: 'Kişi B', outcome: 'NO_ANSWER', contacted_at: ago(5), channel: 'PHONE', created_by_name: 'Operatör A' },
}
const ROW_NOT_SENT = { ...ROW_NEEDS, id: 12, domain: 'c.example.com', level: 'WARNING', channels: { ...ROW_NEEDS.channels, noc: false }, noc: null }

function stub({ items = [ROW_NEEDS, ROW_CALLED, ROW_NOT_SENT], canWrite = true, total } = {}) {
  api.noc.console.mockImplementation(async () => ({ success: true, data: {
    window: '24h', generated_at: '2026-10-04T12:00:00',
    kpis: { open: 7, open_critical: 4, noc_sent: 5, not_called: 2, called_last_hour: 3 },
    facets: { teams: [{ id: 1, name: 'Takım A', count: 2 }, { id: 2, name: 'Takım B', count: 1 }], types: { ping: 2, http: 1 }, levels: { CRITICAL: 2, HIGH: 0, WARNING: 1 } },
    items: items.map((r) => ({ ...r, can_call: canWrite })), total: total ?? items.length, page: 0, size: 25, truncated: false, max_rows: 1000, can_write: canWrite,
  } }))
}
const lastParams = () => api.noc.console.mock.calls.at(-1)[0]
const row = (id) => document.querySelector(`[data-slot="noc-con-row"][data-alert-id="${id}"]`)

beforeEach(() => {
  vi.clearAllMocks()
  WIDTH = 1400
  window.history.replaceState({}, '', '/?tab=noc')
  api.noc.callSheet.mockResolvedValue({ success: true, data: {
    alert_id: 10, team_id: 1, team_name: 'Takım A', call_list_defined: true,
    call_list: [{ name: 'Kişi A', title: 'Uzman', phone: '0500 000 00 00', position: 1 }, { name: 'Kişi C', title: null, phone: null, position: 2 }],
    manager: { name: 'Müdür A', title: 'Müdür', phone: '+90 500 000 00 01' },
    escalation: [{ name: 'Kişi E', role: 'Teknik Sorumlu', email: 'e@example.com' }],
    call_instructions: 'Önce listeyi sırayla arayın.',
  } })
  api.nocCalls.list.mockResolvedValue({ success: true, data: [] })
  api.nocCalls.contacts.mockResolvedValue({ success: true, data: [
    { user_id: 11, display_name: 'Kişi A', title: 'Uzman', has_phone: true, source: 'CALL_LIST', is_manager: false },
  ] })
})
afterEach(() => window.history.replaceState({}, '', '/'))

describe('NocConsole', () => {
  it('KPI kartları + satırlar: 7/24 iletimi, kanallar, son arama; aranmamış satır öne çıkar; yoklama 30 sn', async () => {
    stub()
    render(<NocConsole />)
    await waitFor(() => expect(row(10)).not.toBeNull())
    const values = Object.fromEntries([...document.querySelectorAll('[data-slot="stat-item"]')].map((b) =>
      [b.getAttribute('data-key'), b.querySelector('[data-slot="stat-value"]').textContent]))
    expect(values).toEqual({ open: '7', noc_sent: '5', not_called: '2', called_last_hour: '3' })
    expect(row(10)).toHaveAttribute('data-urgency', 'needs_call')
    expect(row(10).querySelector('[data-slot="alert-noc-sent"]')).not.toBeNull()
    expect(within(row(10)).getByText('Not called yet')).toBeInTheDocument()
    expect(row(10).querySelector('[data-ch="email"]')).toHaveAttribute('data-sent', '3')
    expect(row(12).querySelector('[data-slot="noc-con-not-sent"]')).toHaveTextContent('Not sent to 24/7')
    const last = row(11).querySelector('[data-slot="noc-con-last-call"]')
    expect(last.textContent).toMatch(/Kişi B/)
    expect(last.textContent).toMatch(/No answer/)
    expect(last.textContent).toMatch(/logged by Operatör A/)
    expect(last.textContent).toMatch(/2 calls/)
    expect(document.querySelector('[data-slot="noc-con-table"]')).not.toBeNull()   // geniş kap → tablo
    expect(api.noc.console).toHaveBeenCalledWith(expect.objectContaining({ window: '24h', page: 0, size: 25 }))
    expect(intervalSpy).toHaveBeenCalledWith(expect.any(Function), REFRESH_MS)
  })

  it('süzgeçler sunucuya: "Aranmadı" kartı, "7/24\'e gidenler" anahtarı, dönem, seviye; çip × ile kalkar; URL n_c*', async () => {
    stub()
    render(<NocConsole />)
    await waitFor(() => expect(row(10)).not.toBeNull())
    fireEvent.click(document.querySelector('[data-slot="stat-item"][data-key="not_called"]'))
    await waitFor(() => expect(lastParams()).toMatchObject({ state: 'open', noc: 'sent', called: 'no' }))
    expect(document.querySelector('[data-slot="stat-item"][data-key="not_called"]')).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(document.querySelector('[data-slot="stat-item"][data-key="not_called"]'))   // toggle → kalkar
    await waitFor(() => expect(lastParams().noc).toBeUndefined())

    fireEvent.click(document.querySelector('[data-slot="noc-con-sent-toggle"]'))
    await waitFor(() => expect(lastParams()).toMatchObject({ noc: 'sent' }))
    fireEvent.click(screen.getByRole('button', { name: 'Last 7 days' }))
    await waitFor(() => expect(lastParams()).toMatchObject({ window: '7d', noc: 'sent' }))
    fireEvent.change(screen.getByLabelText('Level'), { target: { value: 'CRITICAL' } })
    await waitFor(() => expect(lastParams()).toMatchObject({ level: 'CRITICAL' }))
    const chip = document.querySelector('[data-slot="noc-con-chips"] [data-filter="noc"]')
    expect(chip).not.toBeNull()
    fireEvent.click(within(chip).getByRole('button', { name: /Remove filter/ }))
    await waitFor(() => expect(lastParams().noc).toBeUndefined())
    await waitFor(() => expect(window.location.search).toMatch(/n_cw=7d/))
    expect(window.location.search).toMatch(/n_clvl=CRITICAL/)
  })

  it('arama süzgeci (300 ms) ve takım seçimi sunucuya gider', async () => {
    stub()
    render(<NocConsole />)
    await waitFor(() => expect(row(10)).not.toBeNull())
    fireEvent.change(screen.getByRole('searchbox', { name: /Search monitor/ }), { target: { value: 'b site' } })
    await waitFor(() => expect(lastParams()).toMatchObject({ q: 'b site' }), { timeout: 2000 })
    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Team' }))
    fireEvent.mouseDown((await screen.findByText('Takım B (1)')).closest('[role="option"]'))
    await waitFor(() => expect(lastParams()).toMatchObject({ team_id: '2', q: 'b site' }))
  })

  it('"Call" penceresi: sahibi takımın arama kartı TELEFONLA (tel: bağlantısı), müdür, eskalasyon, talimat + arama kaydı formu', async () => {
    stub()
    render(<NocConsole />)
    await waitFor(() => expect(row(10)).not.toBeNull())
    fireEvent.click(within(row(10)).getByRole('button', { name: 'Call and log the call: A ping' }))
    const dialog = await screen.findByRole('dialog')
    await waitFor(() => expect(api.noc.callSheet).toHaveBeenCalledWith(10))
    await waitFor(() => expect(dialog.querySelector('[data-slot="noc-sheet-call-list"]')).not.toBeNull())
    const persons = [...dialog.querySelectorAll('[data-slot="noc-sheet-call-list"] [data-slot="noc-sheet-person"]')]
    expect(persons).toHaveLength(2)
    const tel = within(persons[0]).getByRole('link', { name: /Call Kişi A: 0500 000 00 00/ })
    expect(tel).toHaveAttribute('href', 'tel:05000000000')
    expect(within(persons[1]).getByText('No phone number on file')).toBeInTheDocument()
    expect(within(dialog.querySelector('[data-slot="noc-sheet-manager"]')).getByRole('link')).toHaveAttribute('href', 'tel:+905000000001')
    expect(dialog.textContent).toMatch(/Teknik Sorumlu/)
    expect(dialog.textContent).toMatch(/Önce listeyi sırayla arayın\./)
    await waitFor(() => expect(dialog.querySelector('[data-slot="noc-call-form"]')).not.toBeNull())
    expect(api.nocCalls.list).toHaveBeenCalledWith(10)
  })

  it('izinsiz (global görücü, arama kaydı yok): salt okunur bilgi şeridi; satırda "View only", "Call" düğmesi YOK', async () => {
    stub({ canWrite: false })
    render(<NocConsole />)
    await waitFor(() => expect(row(10)).not.toBeNull())
    expect(screen.getByText('Read-only view')).toBeInTheDocument()
    expect(within(row(10)).getByText('View only')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Call and log the call/ })).toBeNull()
  })

  it('dar kap (telefon / kenar çubuklu tablet): tablo yerine kartlar; eylem tam genişlik', async () => {
    WIDTH = 600
    stub()
    render(<NocConsole />)
    await waitFor(() => expect(row(10)).not.toBeNull())
    expect(document.querySelector('[data-slot="noc-con-cards"]')).not.toBeNull()
    expect(document.querySelector('[data-slot="noc-con-table"]')).toBeNull()
    expect(within(row(10)).getByRole('button', { name: 'Call and log the call: A ping' }).className).toMatch(/w-full/)
  })

  it('boş sonuç: süzgeç yoksa "No alerts in this period", süzgeç varsa temizle düğmesi; yükleme hatası yeniden dene', async () => {
    stub({ items: [] })
    render(<NocConsole />)
    expect(await screen.findByText('No alerts in this period')).toBeInTheDocument()
    fireEvent.click(document.querySelector('[data-slot="noc-con-sent-toggle"]'))
    expect(await screen.findByText('No alerts match the filters')).toBeInTheDocument()
    api.noc.console.mockResolvedValueOnce({ success: false, error: 'boom' })
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    expect(await screen.findByText(/boom/)).toBeInTheDocument()
  })
})
