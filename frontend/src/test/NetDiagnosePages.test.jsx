import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import PingMonitorPage from '../components/PingMonitorPage.jsx'
import PortMonitorPage from '../components/PortMonitorPage.jsx'
import DnsMonitorPage from '../components/DnsMonitorPage.jsx'
import {
  DNS_CHECKS, DNS_ROW, PING_CHECKS, PING_ROW, PORT_CHECKS, PORT_ROW, dnsStale, historyEnvelope, pingFiltered, portPathDiffers, stored,
} from './helpers/netDiagnoseFixtures.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate:     (s) => s ?? '',
  formatDateSec:  (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  getRecentFailures: () => [],
  api: withApiFallback({
    monitoring: {
      listGroups:      vi.fn(() => Promise.resolve({ success: true, data: [] })),
      monitorDefaults: vi.fn(() => Promise.resolve({ success: true, data: {} })),
      getCheckHistory: vi.fn(),
      getCheckHistoryCsvUrl: vi.fn(() => '#'),
      getPingMonitors: vi.fn(), getPortMonitors: vi.fn(), getDnsMonitors: vi.fn(),
      diagnosePing: vi.fn(), pingDiagnoseHistory: vi.fn(), pingDiagnoseRun: vi.fn(),
      diagnosePort: vi.fn(), portDiagnoseHistory: vi.fn(), portDiagnoseRun: vi.fn(),
      diagnoseDns: vi.fn(), dnsDiagnoseHistory: vi.fn(), dnsDiagnoseRun: vi.fn(),
    },
    admin: { getTeams: vi.fn(), getAlerts: vi.fn() },
  }),
}))
import { api } from '../api/client'

/**
 * Ping / Port / DNS sayfaları — uçtan uca tanılama GİRİŞ NOKTALARI (2026-10-05): detay başlığındaki "Uçtan uca tanıla"
 * (Stethoscope) ve kontrol geçmişi hata panelindeki "Bu kontrolü tanıla" YALNIZ satırın `can_diagnose` bayrağıyla çizilir;
 * pencere kendiliğinden koşmaz; derin bağlantı (`pgdx` / `ptdx` / `dndx`) KAYITLI çalıştırmayı açar ve URL'de kalır;
 * bayrak yoksa derin bağlantı pencere açmaz, kayıt istenmez.
 */
const PAGES = [
  { type: 'ping', Page: PingMonitorPage, row: PING_ROW, checks: PING_CHECKS, list: 'getPingMonitors', run: 'diagnosePing',
    getRun: 'pingDiagnoseRun', param: 'pgdx', runId: 501, data: pingFiltered, name: /ping tanılama|ping diagnosis/i },
  { type: 'port', Page: PortMonitorPage, row: PORT_ROW, checks: PORT_CHECKS, list: 'getPortMonitors', run: 'diagnosePort',
    getRun: 'portDiagnoseRun', param: 'ptdx', runId: 602, data: portPathDiffers, name: /port tanılama|port diagnosis/i },
  { type: 'dns', Page: DnsMonitorPage, row: DNS_ROW, checks: DNS_CHECKS, list: 'getDnsMonitors', run: 'diagnoseDns',
    getRun: 'dnsDiagnoseRun', param: 'dndx', runId: 703, data: dnsStale, name: /dns tanılama|dns diagnosis/i },
]

const openBtn = () => document.querySelector('[data-slot="ndx-open"]')

describe.each(PAGES)('$type sayfası — uçtan uca tanılama', ({ type, Page, row, checks, list, run, getRun, param, runId, data, name }) => {
  const diagDialog = () => screen.queryByRole('dialog', { name })
  const failCell = () => document.querySelector(`[data-slot="chkfail-cell"][data-type="${type}"]`)

  async function openDetail(r = row, extra = '') {
    api.monitoring[list].mockResolvedValue({ success: true, data: [r] })
    window.history.replaceState({}, '', `/?tab=${type}&monitor=${r.id}${extra}`)
    render(<Page systemRole="ADMIN" teamId={5} teamName="Takım A" />)
    await waitFor(() => { if (!failCell()) throw new Error('geçmiş yok') }, { timeout: 3000 })
  }

  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getCheckHistory.mockResolvedValue(historyEnvelope(checks))
    api.monitoring.listGroups.mockResolvedValue({ success: true, data: [] })
    api.monitoring.monitorDefaults.mockResolvedValue({ success: true, data: {} })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'Takım A' }] })
    api.admin.getAlerts.mockResolvedValue({ success: true, data: [] })
    api.monitoring[getRun].mockResolvedValue({ success: true, data: stored(data()) })
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('can_diagnose YOKSA: başlıkta tanıla düğmesi yok, hata panelinde "Bu kontrolü tanıla" yok', async () => {
    await openDetail()
    expect(openBtn()).toBeNull()
    fireEvent.click(failCell().querySelector('[data-slot="chkfail-toggle"]'))
    const panel = await waitFor(() => { const p = document.querySelector('[data-slot="chkfail-panel"]'); if (!p) throw new Error('panel yok'); return p })
    expect(within(panel).queryByRole('button', { name: /(Diagnose this check|Bu kontrolü tanıla)/ })).toBeNull()
  })

  it('can_diagnose VARSA: başlıktaki "Uçtan uca tanıla" pencereyi başlangıç ekranıyla açar; API çağrılmaz; kapanır', async () => {
    await openDetail({ ...row, can_diagnose: true })
    const btn = openBtn()
    expect(btn).not.toBeNull()
    expect(btn).toHaveAccessibleName(/uçtan uca tanıla|diagnose end to end/i)
    fireEvent.click(btn)
    const dlg = await waitFor(() => { const d = diagDialog(); if (!d) throw new Error('pencere yok'); return d })
    expect(dlg.querySelector('[data-slot="ndx-body"]')).toHaveAttribute('data-type', type)
    expect(dlg.querySelector('[data-slot="ndx-start"]')).not.toBeNull()
    await act(async () => { await new Promise((r) => setTimeout(r, 20)) })
    expect(api.monitoring[run]).not.toHaveBeenCalled()
    fireEvent.click(within(dlg).getAllByRole('button', { name: /^(kapat|close)$/i })[0])
    await waitFor(() => expect(diagDialog()).toBeNull())
  })

  it('can_diagnose VARSA: hata panelinden "Bu kontrolü tanıla" pencereyi açar (koşu başlamaz)', async () => {
    await openDetail({ ...row, can_diagnose: true })
    fireEvent.click(failCell().querySelector('[data-slot="chkfail-toggle"]'))
    const panel = await waitFor(() => { const p = document.querySelector('[data-slot="chkfail-panel"]'); if (!p) throw new Error('panel yok'); return p })
    fireEvent.click(within(panel).getByRole('button', { name: /(Diagnose this check|Bu kontrolü tanıla)/ }))
    const dlg = await waitFor(() => { const d = diagDialog(); if (!d) throw new Error('pencere yok'); return d })
    expect(dlg.querySelector('[data-slot="ndx-start"]')).not.toBeNull()
    expect(api.monitoring[run]).not.toHaveBeenCalled()
  })

  it(`derin bağlantı ?monitor=…&${param}=… → KAYITLI çalıştırma açılır, canlı tanılama başlamaz; ${param} URL'de kalır`, async () => {
    api.monitoring[list].mockResolvedValue({ success: true, data: [{ ...row, can_diagnose: true }] })
    window.history.replaceState({}, '', `/?tab=${type}&monitor=${row.id}&${param}=${runId}`)
    render(<Page systemRole="ADMIN" teamId={5} teamName="Takım A" />)
    await waitFor(() => expect(api.monitoring[getRun]).toHaveBeenCalledWith(row.id, runId), { timeout: 3000 })
    const dlg = await waitFor(() => { const d = diagDialog(); if (!d) throw new Error('pencere yok'); return d })
    await waitFor(() => expect(dlg.querySelector('[data-slot="ndx-result"][data-stored="true"]')).not.toBeNull())
    expect(dlg.querySelector('[data-slot="ndx-verdict"]')).not.toBeNull()
    expect(api.monitoring[run]).not.toHaveBeenCalled()
    await waitFor(() => expect(new URLSearchParams(window.location.search).get(param)).toBe(String(runId)), { timeout: 2000 })
  })

  it(`derin bağlantı ${param} ama can_diagnose yok → pencere açılmaz, kayıt istenmez`, async () => {
    await openDetail(row, `&${param}=${runId}`)
    expect(diagDialog()).toBeNull()
    expect(api.monitoring[getRun]).not.toHaveBeenCalled()
  })
})
