import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'

/**
 * 2026-09-27 regresyon taraması (release-fixes.md FRONTEND A #4): izleme sayfalarında `checkNow` / `refreshModal`
 * await SONRASI bayat `selected` kapanışını okuyordu (`if (selected?.id === m.id) setSelected(res.data)`):
 * A'nın penceresinden "Şimdi kontrol et"e basılıp pencere kapatılır ve B açılırsa, A'nın geç sonucu B'nin penceresini
 * A ile DEĞİŞTİRİYORDU (Sayfa/PageSpeed'de ayrıca A'nın sorunları/kaynakları B'nin penceresine yükleniyordu).
 * Düzeltme: işlevsel güncelleme (`prev?.id === m.id`) + yan etkiler için `selectedIdRef`. Denetimli promise'lerle
 * belirlenimci: A'nın isteği B açıldıktan SONRA çözülür. Scripted ayrı dosyada (kendi harness'ı).
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      listGroups: vi.fn(() => Promise.resolve({ success: true, data: [] })),
      monitorDefaults: vi.fn(() => Promise.resolve({ success: true, data: {} })),
      getCheckHistory: vi.fn(), getCheckHistoryCsvUrl: vi.fn(() => '#'),
      getHttpMonitors: vi.fn(), triggerHttpCheck: vi.fn(),
      getPingMonitors: vi.fn(), triggerPingCheck: vi.fn(),
      getPortMonitors: vi.fn(), triggerPortCheck: vi.fn(),
      getKeywordMonitors: vi.fn(), triggerKeywordCheck: vi.fn(),
      getDomainMonitors: vi.fn(), triggerDomainCheck: vi.fn(),
      getPageMonitors: vi.fn(), triggerPageCheck: vi.fn(), getPageIssues: vi.fn(), getConfirmations: vi.fn(),
      getPageSpeedMonitors: vi.fn(), triggerPageSpeedCheck: vi.fn(), getPageSpeedResources: vi.fn(),
    },
    admin: { getTeams: vi.fn(), getAlerts: vi.fn() },
  }),
}))
import { api } from '../api/client'
import HttpMonitorPage from '../components/HttpMonitorPage.jsx'
import PingMonitorPage from '../components/PingMonitorPage.jsx'
import PortMonitorPage from '../components/PortMonitorPage.jsx'
import KeywordMonitorPage from '../components/KeywordMonitorPage.jsx'
import DomainMonitorPage from '../components/DomainMonitorPage.jsx'
import PageMonitorPage from '../components/PageMonitorPage.jsx'
import PageSpeedMonitorPage from '../components/PageSpeedMonitorPage.jsx'

const mk = (id, tag) => ({
  id, name: `${tag} mon`, url: `https://${tag}.example.com/`, host: `${tag}.example.com`, domain: `${tag}.example.com`,
  port: 443, protocol: 'TCP', method: 'GET', keyword: 'ok', operator: 'GTE', match_count: 1, mode: 'SINGLE_PAGE',
  team_id: 5, team_name: 'SY-A', group_name: 'G', tags: 'prod', status: 'up', ok: true, active: true,
  http_status: 200, response_ms: 12, rtt_ms: 3, occurrences: 5, days_remaining: 120, expiry_date: '2027-08-13', source: 'RDAP',
  broken_resources: 0, mixed_content_count: 0, total_resources: 3, interval_seconds: 600, timeout_ms: 7000,
  checked_at: '2026-06-24T00:00:00', last_check: '2026-06-24T00:00:00', breached_metrics: [], custom_header_names: [],
})
const A = mk(1, 'alpha')
const B = mk(2, 'bravo')
const deferred = () => { let resolve; const p = new Promise((r) => { resolve = r }); return { p, resolve } }
const flush = () => act(() => new Promise((r) => setTimeout(r, 0)))

const PAGES = [
  { label: 'Http', Comp: HttpMonitorPage, list: 'getHttpMonitors', trigger: 'triggerHttpCheck', check: /^(Şimdi kontrol et|Check now)$/i },
  { label: 'Ping', Comp: PingMonitorPage, list: 'getPingMonitors', trigger: 'triggerPingCheck', check: /^(Kontrol Et|Check now)$/i },
  { label: 'Port', Comp: PortMonitorPage, list: 'getPortMonitors', trigger: 'triggerPortCheck', check: /^(Kontrol Et|Check now)$/i },
  { label: 'Keyword', Comp: KeywordMonitorPage, list: 'getKeywordMonitors', trigger: 'triggerKeywordCheck', check: /^(Kontrol Et|Check now)$/i },
  { label: 'Domain', Comp: DomainMonitorPage, list: 'getDomainMonitors', trigger: 'triggerDomainCheck', check: /^(Şimdi kontrol et|Check now)$/i },
  { label: 'Page', Comp: PageMonitorPage, list: 'getPageMonitors', trigger: 'triggerPageCheck', check: /^(Kontrol|Check)$/i,
    sideLoad: 'getPageIssues' },
  { label: 'PageSpeed', Comp: PageSpeedMonitorPage, list: 'getPageSpeedMonitors', trigger: 'triggerPageSpeedCheck', check: /^(Şimdi ölç|Measure now)$/i,
    sideLoad: 'getPageSpeedResources' },
]

/** Açık detay penceresi (MonitorDetailModal) — başlığı izlemenin url/host/domain'i; alpha|bravo ayırt eder. */
const detail = () => screen.queryByRole('dialog')
const detailName = () => detail()?.getAttribute('aria-labelledby')
  ? document.getElementById(detail().getAttribute('aria-labelledby'))?.textContent || ''
  : (detail()?.textContent || '')
async function closeDetail() {
  fireEvent.click(within(detail()).getAllByRole('button', { name: /^(Close|Kapat)$/ })[0])
  await waitFor(() => expect(detail()).toBeNull())
}
async function openCard(tag) {
  const btn = await waitFor(() => {
    const b = [...document.querySelectorAll('[data-monitor-open]')].find((x) => (x.getAttribute('aria-label') || '').includes(tag))
    if (!b) throw new Error(`kart yok: ${tag}`)
    return b
  })
  fireEvent.click(btn)
  await waitFor(() => expect(detailName()).toMatch(tag))
}

describe.each(PAGES)('$label — detay penceresi bayat kapanış', ({ Comp, list, trigger, check, sideLoad }) => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    window.history.replaceState({}, '', '/?monitor=1')   // A'nın penceresi derin bağlantıyla açılır
    api.monitoring[list].mockResolvedValue({ success: true, data: [A, B] })
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [], counts: { total: 0, fail: 0 }, buckets: [], alerts: [],
      range: { from: '2026-01-01T00:00:00', to: '2026-01-02T00:00:00' }, total: 0, page: 0, size: 50 } })
    api.monitoring.getPageIssues.mockResolvedValue({ success: true, data: [] })
    api.monitoring.getConfirmations.mockResolvedValue({ success: true, data: [] })
    api.monitoring.getPageSpeedResources.mockResolvedValue({ success: true, data: { resources: [], breaches: [], total: 0 } })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
  })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('checkNow: A\'nın geç sonucu, o arada açılan B\'nin penceresini A ile DEĞİŞTİRMEZ', async () => {
    const run = deferred()
    api.monitoring[trigger].mockReturnValue(run.p)
    render(<Comp systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(detailName()).toMatch('alpha'))

    fireEvent.click(within(detail()).getAllByRole('button', { name: check })[0])
    await waitFor(() => expect(api.monitoring[trigger]).toHaveBeenCalledWith(1))
    await closeDetail()
    await openCard('bravo')
    if (sideLoad) await waitFor(() => expect(api.monitoring[sideLoad]).toHaveBeenLastCalledWith(2, expect.anything()))

    await act(async () => { run.resolve({ success: true, data: { ...A, response_ms: 99 } }) })
    await flush()
    expect(detail()).not.toBeNull()
    expect(detailName()).toMatch('bravo')
    expect(detailName()).not.toMatch('alpha')
    // A'nın sorunları/kaynakları B'nin penceresine yüklenmedi: son yan yükleme B için.
    if (sideLoad) expect(api.monitoring[sideLoad].mock.calls.at(-1)[0]).toBe(2)
  })

  it('checkNow: pencere kapatıldıktan sonra gelen A sonucu pencereyi YENİDEN AÇMAZ', async () => {
    const run = deferred()
    api.monitoring[trigger].mockReturnValue(run.p)
    render(<Comp systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(detailName()).toMatch('alpha'))
    fireEvent.click(within(detail()).getAllByRole('button', { name: check })[0])
    await waitFor(() => expect(api.monitoring[trigger]).toHaveBeenCalledWith(1))
    await closeDetail()

    await act(async () => { run.resolve({ success: true, data: { ...A } }) })
    await flush()
    expect(detail()).toBeNull()
  })

  if (sideLoad) {
    it('refreshModal (30 sn sessiz tazeleme): A için başlayan tazeleme B açıldıktan sonra dönerse B\'nin penceresine dokunmaz', async () => {
      render(<Comp systemRole="ADMIN" teamId={5} teamName="SY-A" />)
      await waitFor(() => expect(detailName()).toMatch('alpha'))
      const fresh = deferred()
      api.monitoring[list].mockReturnValue(fresh.p)
      // Görünürlük olayı useVisibleInterval'ı hemen tetikler (30 sn'lik tikle aynı yol) → refreshModal(A) uçuşta.
      act(() => { document.dispatchEvent(new Event('visibilitychange')) })
      await waitFor(() => expect(api.monitoring[list].mock.calls.length).toBeGreaterThanOrEqual(2))
      await closeDetail()
      await openCard('bravo')
      await waitFor(() => expect(api.monitoring[sideLoad]).toHaveBeenLastCalledWith(2, expect.anything()))

      await act(async () => { fresh.resolve({ success: true, data: [{ ...A, response_ms: 77 }, B] }) })
      await flush()
      expect(detailName()).toMatch('bravo')
      expect(detailName()).not.toMatch('alpha')
      expect(api.monitoring[sideLoad].mock.calls.at(-1)[0]).toBe(2)
    })
  }
})
