import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import PageMonitorPage from '../components/PageMonitorPage.jsx'
import PageSpeedMonitorPage from '../components/PageSpeedMonitorPage.jsx'
import { pageBroken, pageMonitor, speedMonitor, speedSlow } from './helpers/pageDiagnoseFixtures.js'

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
      getPageMonitors: vi.fn(), getPageIssues: vi.fn(), getConfirmations: vi.fn(),
      getPageSpeedMonitors: vi.fn(), getPageSpeedResources: vi.fn(),
      diagnosePage: vi.fn(), pageDiagnoseHistory: vi.fn(), pageDiagnoseRun: vi.fn(),
      diagnosePageSpeed: vi.fn(), pageSpeedDiagnoseHistory: vi.fn(), pageSpeedDiagnoseRun: vi.fn(),
    },
    admin: { getTeams: vi.fn(), getAlerts: vi.fn() },
  }),
}))
import { api } from '../api/client'

/**
 * Sayfa Bütünlüğü / Sayfa Hızı sayfaları — uçtan uca tanılama GİRİŞ NOKTALARI (2026-10-05): detay başlığındaki "Uçtan uca
 * tanıla" (Stethoscope) ve kontrol geçmişi hata panelindeki "Bu kontrolü tanıla" YALNIZ satırın `can_diagnose` bayrağıyla
 * çizilir; pencere kendiliğinden koşmaz; derin bağlantı (`pidx` / `psdx`) KAYITLI çalıştırmayı açar ve URL'de kalır;
 * bayrak yoksa derin bağlantı pencere açmaz, kayıt istenmez.
 */
const historyEnvelope = (items) => ({ success: true, data: {
  items, counts: { total: items.length, fail: items.filter((c) => c.ok === false).length }, buckets: [], alerts: [],
  range: { from: '2026-09-29T00:00:00', to: '2026-10-05T23:59:59' }, total: items.length, page: 0, size: 50 } })

const PAGE_CHECKS = [
  { id: 31, monitor_id: 9, ok: false, status: 'DOWN', http_status: 503, response_ms: 210, total_resources: 0, broken_resources: 0,
    timeout_count: 0, mixed_content_count: 0, error: 'ana sayfa HTTP 503', checked_at: '2026-10-05T09:00:00', failure_reason: 'HTTP_STATUS',
    failure_detail: JSON.stringify({ phase: 'RESPONSE', http_status: 503, target: 'shop.example.com', via: 'direct', timeout_ms: 4000 }) },
  { id: 29, monitor_id: 9, ok: true, status: 'OK', http_status: 200, total_resources: 12, broken_resources: 0, timeout_count: 0,
    mixed_content_count: 0, checked_at: '2026-10-05T07:00:00' },
]
const SPEED_CHECKS = [
  { id: 41, checked_at: '2026-10-05T09:00:00', ok: false, status_code: null, response_ms: 10000, ttfb_ms: 0, dns_ms: 12, connect_ms: 30,
    tls_ms: null, error_message: 'request timed out', failure_reason: 'READ_TIMEOUT',
    failure_detail: JSON.stringify({ phase: 'RESPONSE', target: 'shop.example.com', via: 'direct', timeout_ms: 10000 }) },
  { id: 39, checked_at: '2026-10-05T07:00:00', ok: true, response_ms: 4100, ttfb_ms: 300, total_bytes: 1024, request_count: 9 },
]

const PAGES = [
  { type: 'page', tab: 'page', Page: PageMonitorPage, row: pageMonitor, checks: PAGE_CHECKS, list: 'getPageMonitors',
    run: 'diagnosePage', getRun: 'pageDiagnoseRun', param: 'pidx', runId: 601, data: pageBroken, slot: 'pgdx-open',
    analysis: 'pgdx-analysis', name: /sayfa bütünlüğü tanılama|page integrity diagnosis/i },
  { type: 'pagespeed', tab: 'pagespeed', Page: PageSpeedMonitorPage, row: speedMonitor, checks: SPEED_CHECKS, list: 'getPageSpeedMonitors',
    run: 'diagnosePageSpeed', getRun: 'pageSpeedDiagnoseRun', param: 'psdx', runId: 701, data: speedSlow, slot: 'psdx-open',
    analysis: 'psdx-analysis', name: /sayfa hızı tanılama|page speed diagnosis/i },
]

describe.each(PAGES)('$type sayfası — uçtan uca tanılama', ({ type, tab, Page, row, checks, list, run, getRun, param, runId, data, slot, analysis, name }) => {
  const diagDialog = () => screen.queryByRole('dialog', { name })
  const failCell = () => document.querySelector(`[data-slot="chkfail-cell"][data-type="${type}"]`)
  const openBtn = () => document.querySelector(`[data-slot="${slot}"]`)

  async function openDetail(r = row, extra = '') {
    api.monitoring[list].mockResolvedValue({ success: true, data: [r] })
    window.history.replaceState({}, '', `/?tab=${tab}&monitor=${r.id}&mtab=control${extra}`)
    render(<Page systemRole="ADMIN" teamId={5} teamName="Takım A" />)
    await waitFor(() => { if (!failCell()) throw new Error('geçmiş yok') }, { timeout: 4000 })
  }

  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getCheckHistory.mockResolvedValue(historyEnvelope(checks))
    api.monitoring.listGroups.mockResolvedValue({ success: true, data: [] })
    api.monitoring.monitorDefaults.mockResolvedValue({ success: true, data: {} })
    api.monitoring.getPageIssues.mockResolvedValue({ success: true, data: [] })
    api.monitoring.getConfirmations.mockResolvedValue({ success: true, data: [] })
    api.monitoring.getPageSpeedResources.mockResolvedValue({ success: true, data: { resources: [], breaches: [] } })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'Takım A' }] })
    api.admin.getAlerts.mockResolvedValue({ success: true, data: [] })
    api.monitoring[getRun].mockResolvedValue({ success: true, data: data() })
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
    expect(dlg.querySelector('[data-slot="httpdx-body"]')).toHaveAttribute('data-kind', type)
    expect(dlg.querySelector('[data-slot="httpdx-start"]')).not.toBeNull()
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
    expect(dlg.querySelector('[data-slot="httpdx-start"]')).not.toBeNull()
    expect(api.monitoring[run]).not.toHaveBeenCalled()
  })

  it(`derin bağlantı ?monitor=…&${param}=… → KAYITLI çalıştırma açılır (çözümlemesiyle), canlı tanılama başlamaz; ${param} URL'de kalır`, async () => {
    api.monitoring[list].mockResolvedValue({ success: true, data: [{ ...row, can_diagnose: true }] })
    window.history.replaceState({}, '', `/?tab=${tab}&monitor=${row.id}&${param}=${runId}`)
    render(<Page systemRole="ADMIN" teamId={5} teamName="Takım A" />)
    await waitFor(() => expect(api.monitoring[getRun]).toHaveBeenCalledWith(row.id, runId), { timeout: 4000 })
    const dlg = await waitFor(() => { const d = diagDialog(); if (!d) throw new Error('pencere yok'); return d })
    await waitFor(() => expect(dlg.querySelector('[data-slot="httpdx-result"][data-stored="true"]')).not.toBeNull())
    expect(dlg.querySelector(`[data-slot="${analysis}"]`)).not.toBeNull()
    expect(api.monitoring[run]).not.toHaveBeenCalled()
    await waitFor(() => expect(new URLSearchParams(window.location.search).get(param)).toBe(String(runId)), { timeout: 2000 })
  })

  it(`derin bağlantı ${param} ama can_diagnose yok → pencere açılmaz, kayıt istenmez`, async () => {
    await openDetail(row, `&${param}=${runId}`)
    expect(diagDialog()).toBeNull()
    expect(api.monitoring[getRun]).not.toHaveBeenCalled()
  })
})
