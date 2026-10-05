import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import UptimePage from '../components/UptimePage.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
// İzin anlık görüntüsü — testler `perm.run`'ı değiştirir (diagnostics.run / execute).
const perm = vi.hoisted(() => ({ run: true }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({
    canView: () => true, canEdit: () => false, canExecute: (r) => r === 'diagnostics.run' && perm.run, perms: {}, refresh: () => {},
  }),
}))

vi.mock('../api/client', () => ({
  formatDate:     (s) => s ?? '',
  formatDateSec:  (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  formatTime:     (s) => s ?? '',
  api: withApiFallback({
    monitoring: { getUptimeOverview: vi.fn(), getCheckHistory: vi.fn(), getCheckHistoryCsvUrl: vi.fn(() => '#') },
    admin: { runDiagnostics: vi.fn() },
  }),
}))
import { api } from '../api/client'

/**
 * Durum (Uptime) sayfası — "Tanıla" kapısı (2026-10-05): rol (ADMIN) değil `diagnostics.run` (execute) izni + kendi kartı
 * (`can_manage !== false`). Kartta, detay penceresinin başlığında ve HTTP geçmişi hata panelinde ("Bu kontrolü tanıla")
 * aynı kapı; üçü de envanterle ortak DiagnosticsModal'ı o alan adı + port için açar. İzin yoksa ya da başka takımın
 * kartıysa hiçbiri çizilmez (detay kabuğun kendi X'iyle).
 */
const own = { domain: 'own.example.test', port: 443, status: 'down', http_ok: false, uptime_7d: 98, uptime_30d: 99, team_id: 5,
  team_name: 'Takım A', can_manage: true, uptime_checked_at: '2026-10-05T08:00:00', incidents_1d: 1, incidents_7d: 1, incidents_30d: 1 }
const foreign = { ...own, domain: 'foreign.example.test', team_id: 9, team_name: 'Takım B', can_manage: false }

const HTTP_ROWS = [
  { id: 2, checked_at: '2026-10-05T08:00:00', status: 'down', response_ms: null, error: 'connect timed out',
    failure_reason: 'CONNECT_TIMEOUT', failure_detail: JSON.stringify({ phase: 'CONNECT', target: 'own.example.test:443', via: 'direct', timeout_ms: 10000 }) },
  { id: 1, checked_at: '2026-10-05T07:55:00', status: 'up', response_ms: 120 },
]
const envelope = (items) => ({ success: true, data: { items, counts: { total: items.length, fail: 1 }, buckets: [], alerts: [],
  range: { from: '2026-10-05T00:00:00', to: '2026-10-05T09:00:00' }, total: items.length, page: 0, size: 50 } })

const card = (domain) => screen.getAllByRole('button', { name: new RegExp(domain) })
  .find((b) => b.hasAttribute('data-monitor-open')).closest('[data-slot="card"]')
const detail = () => screen.getAllByRole('dialog').find((d) => d.textContent.includes('own.example.test'))

async function openOwnDetail() {
  fireEvent.click(screen.getAllByRole('button', { name: /own\.example\.test/ }).find((b) => b.hasAttribute('data-monitor-open')))
  await waitFor(() => expect(detail()).toBeTruthy())
  return detail()
}

describe('UptimePage — Tanıla izin kapısı', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    perm.run = true
    api.monitoring.getUptimeOverview.mockResolvedValue({ success: true, data: [own, foreign], scope: 'all', visible_to_all: true })
    api.monitoring.getCheckHistory.mockImplementation((kind) => Promise.resolve(envelope(kind === 'uptime-http' ? HTTP_ROWS : [])))
    api.admin.runDiagnostics.mockResolvedValue({ success: false, error: 'tanı ucu yanıt vermedi' })
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('izin VAR: kendi kartında Tanıla var, başka takımın kartında yok; karttan DiagnosticsModal alan adı + portla açılır', async () => {
    render(<UptimePage systemRole="USER" />)
    await screen.findByText('foreign.example.test')
    expect(card('foreign.example.test').querySelector('[data-slot="uptime-diagnose"]')).toBeNull()
    const btn = card('own.example.test').querySelector('[data-slot="uptime-diagnose"]')
    expect(btn).not.toBeNull()
    fireEvent.click(btn)
    await waitFor(() => expect(api.admin.runDiagnostics).toHaveBeenCalledWith('own.example.test', 443))
  })

  it('izin YOK (rol ADMIN olsa da): hiçbir kartta Tanıla yok; detayda başlık düğmesi yok', async () => {
    perm.run = false
    render(<UptimePage systemRole="ADMIN" />)
    await screen.findByText('own.example.test')
    expect(document.querySelector('[data-slot="uptime-diagnose"]')).toBeNull()
    const d = await openOwnDetail()
    expect(d.querySelector('[data-slot="uptime-diagnose-open"]')).toBeNull()
    expect(d.querySelector('[data-slot="monitor-modal-actions"]')).toBeNull()   // eski görünüm: kabuğun kendi X'i
  })

  it('izin VAR: detay başlığındaki Tanıla düğmesi DiagnosticsModal\'ı detayın İÇİNDE açar', async () => {
    render(<UptimePage systemRole="USER" />)
    await screen.findByText('own.example.test')
    const d = await openOwnDetail()
    const head = d.querySelector('[data-slot="uptime-diagnose-open"]')
    expect(head).not.toBeNull()
    expect(head).toHaveAccessibleName(/tanıla|diagnos/i)
    fireEvent.click(head)
    await waitFor(() => expect(api.admin.runDiagnostics).toHaveBeenCalledWith('own.example.test', 443))
    await waitFor(() => expect(screen.getAllByRole('dialog').length).toBeGreaterThanOrEqual(2))
  })

  it('izin VAR: HTTP geçmişi hata panelinde "Bu kontrolü tanıla" → DiagnosticsModal', async () => {
    render(<UptimePage systemRole="USER" />)
    await screen.findByText('own.example.test')
    const d = await openOwnDetail()
    const cell = await waitFor(() => { const c = d.querySelector('[data-slot="chkfail-cell"][data-type="uptime"]'); if (!c) throw new Error('hücre yok'); return c })
    fireEvent.click(cell.querySelector('[data-slot="chkfail-toggle"]'))
    const panel = await waitFor(() => { const p = d.querySelector('[data-slot="chkfail-panel"]'); if (!p) throw new Error('panel yok'); return p })
    fireEvent.click(within(panel).getByRole('button', { name: /(Diagnose this check|Bu kontrolü tanıla)/ }))
    await waitFor(() => expect(api.admin.runDiagnostics).toHaveBeenCalledWith('own.example.test', 443))
  })

  it('izin YOK: HTTP geçmişi hata panelinde tanıla düğmesi yok', async () => {
    perm.run = false
    render(<UptimePage systemRole="ADMIN" />)
    await screen.findByText('own.example.test')
    const d = await openOwnDetail()
    const cell = await waitFor(() => { const c = d.querySelector('[data-slot="chkfail-cell"][data-type="uptime"]'); if (!c) throw new Error('hücre yok'); return c })
    fireEvent.click(cell.querySelector('[data-slot="chkfail-toggle"]'))
    const panel = await waitFor(() => { const p = d.querySelector('[data-slot="chkfail-panel"]'); if (!p) throw new Error('panel yok'); return p })
    expect(within(panel).queryByRole('button', { name: /(Diagnose this check|Bu kontrolü tanıla)/ })).toBeNull()
  })
})
