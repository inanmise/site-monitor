import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import HttpMonitorPage from '../components/HttpMonitorPage.jsx'
import { pathDiffers } from './helpers/httpDiagnoseFixtures.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate:    (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  getRecentFailures: () => [],
  api: withApiFallback({
    monitoring: {
      listGroups:        vi.fn(() => Promise.resolve({ success: true, data: [] })),
      monitorDefaults:   vi.fn(() => Promise.resolve({ success: true, data: { http: {} } })),
      getHttpMonitors:   vi.fn(),
      getCheckHistory:   vi.fn(),
      getCheckHistoryCsvUrl: vi.fn(() => '#'),
      triggerHttpCheck:  vi.fn(),
      diagnoseHttp:      vi.fn(),
      httpDiagnoseHistory: vi.fn(),
      httpDiagnoseRun:   vi.fn(),
    },
    admin: { getTeams: vi.fn(), getAlerts: vi.fn() },
  }),
}))
import { api } from '../api/client'

/**
 * HTTP sayfasının uçtan uca tanılama GİRİŞ NOKTALARI (2026-10-02): detay başlığındaki "Uçtan uca tanıla" düğmesi ve
 * "Hata tanısı" penceresindeki "Canlı tanılama çalıştır" YALNIZ sunucunun `can_diagnose` bayrağıyla çizilir; bayrak
 * yoksa ikisi de yok (mevcut görünüm aynen). Pencere açılınca canlı tanılama KENDİLİĞİNDEN koşmaz; derin bağlantı
 * `hdx` kayıtlı çalıştırmayı açar.
 */
const monitor = {
  id: 1, name: 'Example', url: 'https://www.example.com/', method: 'GET', expected_status: '200',
  group_name: 'X', tags: 'prod', team_id: 5, team_name: 'SY-A', status: 'down', http_status: null,
  interval_seconds: 600, timeout_ms: 7000, active: true, checked_at: '2026-10-02T10:00:00',
  proxy_effective: 'proxy', proxy_source: 'monitor', use_proxy: 'ON', can_check: true,
}
const failedHistory = { success: true, data: {
  items: [{ id: 9, checked_at: '2026-10-02T10:00:00', ok: false, http_status: null, response_ms: 10001, error: 'request timed out',
    error_detail: JSON.stringify({ kind: 'READ_TIMEOUT', phase: 'RESPONSE', scheme: 'https', method: 'GET', url: 'https://www.example.com/' }) }],
  counts: { total: 1, fail: 1 }, buckets: [], alerts: [], range: {}, total: 1, page: 0, size: 50,
} }
const diagBtn = () => screen.queryByRole('button', { name: /^(Uçtan uca tanıla|Diagnose end to end)$/ })
const diagDialog = () => screen.queryByRole('dialog', { name: /http tanılama|http diagnosis/i })

describe('HttpMonitorPage — uçtan uca tanılama giriş noktaları', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getCheckHistory.mockResolvedValue(failedHistory)
    api.monitoring.listGroups.mockResolvedValue({ success: true, data: [] })
    api.monitoring.monitorDefaults.mockResolvedValue({ success: true, data: { http: {} } })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
    api.monitoring.httpDiagnoseHistory.mockResolvedValue({ success: true, data: [] })
    api.monitoring.httpDiagnoseRun.mockResolvedValue({ success: true, data: pathDiffers() })
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('can_diagnose YOKSA: detay başlığında tanıla düğmesi yok, hata tanısı penceresinde altlık yok', async () => {
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: [monitor] })
    window.history.replaceState({}, '', '/?tab=http&monitor=1')
    render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    const open = await screen.findByRole('button', { name: /ayrı pencerede aç|own window/i })
    expect(diagBtn()).toBeNull()
    expect(document.querySelector('[data-slot="httpdx-open"]')).toBeNull()
    fireEvent.click(open)
    const failure = await screen.findByRole('dialog', { name: /hata tanısı|failure diagnosis/i })
    expect(within(failure).queryByRole('button', { name: /canlı tanılama çalıştır|run live diagnosis/i })).toBeNull()
    expect(failure.querySelector('[data-slot="dialog-footer"]')).toBeNull()
  })

  it('can_diagnose VARSA: başlıkta düğme; tıklayınca pencere başlangıç ekranıyla açılır, API çağrılmaz', async () => {
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: [{ ...monitor, can_diagnose: true }] })
    window.history.replaceState({}, '', '/?tab=http&monitor=1')
    render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    const btn = await waitFor(() => { const b = diagBtn(); if (!b) throw new Error('düğme yok'); return b })
    expect(btn.getAttribute('data-slot')).toBe('httpdx-open')
    fireEvent.click(btn)
    const dlg = await waitFor(() => { const d = diagDialog(); if (!d) throw new Error('pencere yok'); return d })
    expect(dlg.querySelector('[data-slot="httpdx-start"]')).not.toBeNull()
    expect(dlg.querySelector('[data-slot="httpdx-url"]').textContent).toBe('https://www.example.com/')
    await act(async () => { await new Promise((r) => setTimeout(r, 20)) })
    expect(api.monitoring.diagnoseHttp).not.toHaveBeenCalled()
    // kapat → detay yerinde kalır
    fireEvent.click(within(dlg).getAllByRole('button', { name: /^(kapat|close)$/i })[0])
    await waitFor(() => expect(diagDialog()).toBeNull())
    expect(diagBtn()).not.toBeNull()
  })

  it('Hata tanısı penceresinden "Canlı tanılama çalıştır" → hata penceresi kapanır, tanılama penceresi açılır (koşmadan)', async () => {
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: [{ ...monitor, can_diagnose: true }] })
    window.history.replaceState({}, '', '/?tab=http&monitor=1')
    render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByRole('button', { name: /ayrı pencerede aç|own window/i }))
    const failure = await screen.findByRole('dialog', { name: /hata tanısı|failure diagnosis/i })
    fireEvent.click(within(failure).getByRole('button', { name: /canlı tanılama çalıştır|run live diagnosis/i }))
    await waitFor(() => expect(diagDialog()).not.toBeNull())
    expect(screen.queryByRole('dialog', { name: /hata tanısı|failure diagnosis/i })).toBeNull()
    expect(api.monitoring.diagnoseHttp).not.toHaveBeenCalled()
  })

  it('derin bağlantı ?monitor=1&hdx=123 → KAYITLI çalıştırma açılır, canlı tanılama başlamaz', async () => {
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: [{ ...monitor, can_diagnose: true }] })
    window.history.replaceState({}, '', '/?tab=http&monitor=1&hdx=123')
    render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(document.querySelector('[data-slot="httpdx-result"][data-stored="true"]')).not.toBeNull())
    expect(api.monitoring.httpDiagnoseRun).toHaveBeenCalledWith(1, 123)
    expect(api.monitoring.diagnoseHttp).not.toHaveBeenCalled()
    await waitFor(() => expect(new URLSearchParams(window.location.search).get('hdx')).toBe('123'), { timeout: 2000 })
  })

  it('derin bağlantı hdx ama can_diagnose yok → pencere açılmaz, kayıt istenmez', async () => {
    api.monitoring.getHttpMonitors.mockResolvedValue({ success: true, data: [monitor] })
    window.history.replaceState({}, '', '/?tab=http&monitor=1&hdx=123')
    render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await screen.findByRole('button', { name: /ayrı pencerede aç|own window/i })
    await act(async () => { await new Promise((r) => setTimeout(r, 30)) })
    expect(diagDialog()).toBeNull()
    expect(api.monitoring.httpDiagnoseRun).not.toHaveBeenCalled()
  })
})
