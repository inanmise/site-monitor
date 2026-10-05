import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import DomainMonitorPage from '../components/DomainMonitorPage.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      listGroups: vi.fn(() => Promise.resolve({ success: true, data: [] })),
      monitorDefaults: vi.fn(() => Promise.resolve({ success: true, data: {} })),
      getDomainMonitors: vi.fn(), getCheckHistory: vi.fn(), getCheckHistoryCsvUrl: vi.fn(() => '#'),
    },
    admin: { getTeams: vi.fn(), runDomainExpiryDiagnostics: vi.fn() },
  }),
}))
import { api } from '../api/client'

/**
 * Alan Adı → "Sorun Tanıla" kapısı (2026-10-05): rol (ADMIN) değil satırın `can_diagnose` bayrağı — tanılama ucu artık
 * kullanıcının işletebildiği takımın alan adı izlemesini de kabul ediyor. Detayın Kontrol Geçmişi sekmesindeki düğme ve
 * hata panelinin "Bu kontrolü tanıla" düğmesi aynı alan adı süre bitişi tanılama penceresini açar. Bayrak yoksa (ADMIN
 * olsa da) ikisi de yok; formun (kaydedilmemiş alan adı) Sorun Tanıla'sı bilinçli olarak yalnız admin.
 */
const row = {
  id: 7, name: 'own', domain: 'own.example.test', team_id: 5, team_name: 'Takım A', group_name: 'G', tags: 'prod', status: 'UNKNOWN',
  source: 'NONE', days_remaining: null, expiry_date: null, active: true, interval_seconds: 86400, checked_at: '2026-10-05T08:00:00',
  error: 'rdap http 404',
}
const CHECKS = { success: true, data: {
  items: [
    { id: 51, monitor_id: 7, status: 'UNKNOWN', source: 'NONE', registrar: null, error: 'rdap http 404', checked_at: '2026-10-05T08:00:00',
      failure_reason: 'RDAP_NOT_FOUND', failure_detail: JSON.stringify({ phase: 'REGISTRY', http_status: 404, message: 'rdap http 404',
        registry_rdap: true, target: 'own.example.test', source: 'NONE' }) },
  ], counts: { total: 1, fail: 1 }, buckets: [], alerts: [],
  range: { from: '2026-09-05T00:00:00', to: '2026-10-05T23:59:59' }, total: 1, page: 0, size: 50 } }

const detail = () => screen.getAllByRole('dialog').find((d) => (d.textContent || '').includes('own.example.test'))
async function openDetail(r, role = 'USER') {
  api.monitoring.getDomainMonitors.mockResolvedValue({ success: true, data: [r] })
  render(<DomainMonitorPage systemRole={role} teamId={5} teamName="Takım A" />)
  fireEvent.click(await screen.findByRole('button', { name: /own[.]example[.]test — (detayları aç|open details)/i }))
  await waitFor(() => expect(detail()).toBeTruthy())
  await waitFor(() => { if (!detail().querySelector('[data-slot="chkfail-block"]')) throw new Error('geçmiş yok') })
  return detail()
}

describe('DomainMonitorPage — Sorun Tanıla izin kapısı (can_diagnose)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    api.monitoring.getCheckHistory.mockResolvedValue(CHECKS)
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'Takım A' }] })
    api.admin.runDomainExpiryDiagnostics.mockResolvedValue({ success: false, error: 'tanı ucu yanıt vermedi' })
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('USER + kendi satırı (can_diagnose): geçmiş sekmesinde Sorun Tanıla var → tanılama penceresi açılır', async () => {
    const d = await openDetail({ ...row, can_diagnose: true })
    const btn = d.querySelector('[data-slot="dexp-open"]')
    expect(btn).not.toBeNull()
    fireEvent.click(btn)
    expect(await screen.findByText('tanı ucu yanıt vermedi')).toBeInTheDocument()
    expect(api.admin.runDomainExpiryDiagnostics).toHaveBeenCalledWith('own.example.test')
  })

  it('USER + can_diagnose: hata panelinin "Bu kontrolü tanıla" düğmesi aynı pencereyi açar', async () => {
    const d = await openDetail({ ...row, can_diagnose: true })
    fireEvent.click(d.querySelector('[data-slot="chkfail-block"] [data-slot="chkfail-toggle"]'))
    const panel = await waitFor(() => { const p = d.querySelector('[data-slot="chkfail-panel"]'); if (!p) throw new Error('panel yok'); return p })
    fireEvent.click(within(panel).getByRole('button', { name: /(Diagnose this check|Bu kontrolü tanıla)/ }))
    await waitFor(() => expect(api.admin.runDomainExpiryDiagnostics).toHaveBeenCalledWith('own.example.test'))
  })

  it('bayrak YOK (ADMIN olsa da): geçmiş sekmesinde ve hata panelinde tanıla yok', async () => {
    const d = await openDetail(row, 'ADMIN')
    expect(d.querySelector('[data-slot="dexp-open"]')).toBeNull()
    fireEvent.click(d.querySelector('[data-slot="chkfail-block"] [data-slot="chkfail-toggle"]'))
    const panel = await waitFor(() => { const p = d.querySelector('[data-slot="chkfail-panel"]'); if (!p) throw new Error('panel yok'); return p })
    expect(within(panel).queryByRole('button', { name: /(Diagnose this check|Bu kontrolü tanıla)/ })).toBeNull()
    expect(api.admin.runDomainExpiryDiagnostics).not.toHaveBeenCalled()
  })

  it('başka takımın satırı (USER, bayrak yok): tanıla yok', async () => {
    const d = await openDetail({ ...row, team_id: 9, team_name: 'Takım B', can_diagnose: false })
    expect(d.querySelector('[data-slot="dexp-open"]')).toBeNull()
  })
})
