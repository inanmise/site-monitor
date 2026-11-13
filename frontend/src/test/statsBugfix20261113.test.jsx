import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from './test-utils.jsx'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import { javaStringTable, readJavaTest } from './helpers/javaTable.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const perm = vi.hoisted(() => ({ value: {}, scope: null }))
vi.mock('../contexts/PermissionsProvider.jsx', async (orig) => {
  const real = await orig()
  return {
    ...real,
    usePermissions: () => ({
      perms: perm.value, scope: perm.scope, canView: (r) => !!perm.value[r], canEdit: () => false, canExecute: () => false,
      refresh: () => {}, loaded: true, failed: false,
    }),
  }
})
vi.mock('../api/client', async (orig) => {
  const real = await orig()
  return {
    ...real,
    api: withApiFallback({
      getExecutiveStats: vi.fn(), getRenewalTimeliness: vi.fn(), getHealthSeries: vi.fn(), getRecentChanges: vi.fn(),
      monitoring: { getOverview: vi.fn() },
      admin: { getAlertNoise: vi.fn(), getAlertTeamStats: vi.fn() },
      dataQuality: { summary: vi.fn() },
    }),
  }
})
vi.mock('../components/stats/statsExport.js', () => ({ exportStatsXlsx: vi.fn(() => true), exportStatsPdf: vi.fn(async () => true) }))
import { api } from '../api/client'
import StatsView from '../components/StatsView.jsx'
import ExecutiveSummary from '../components/ExecutiveSummary.jsx'
import MonitorTypesSummary from '../components/stats/MonitorTypesSummary.jsx'
import RecentChangesLine from '../components/RecentChangesLine.jsx'
import { HealthSeriesCard } from '../components/stats/TrendCards.jsx'
import { fillHealthDays, healthPctOf, teamScorecard } from '../components/stats/statsInsights.js'

/**
 * İstatistikler — 2026-11-13 hata taraması düzeltmeleri: bölüm "Tekrar dene" yalnız o kaynağı `fresh` ile okur; 30 günlük
 * alarm eğilimi 5 dk yoklamasına girmez (son okuma başarılıysa); sağlık yüzdesi aşağı kesilir (sunucuyla aynı tablo);
 * eksik gün grafikte boşluk; sertifikasız takımda ayrı ileti; bilinmeyen başarı oranı / ölçülmemiş SLA yeşil değil;
 * "ne değişti" satırında alarm izni yoksa alarm parçası yok, okunamayan sayı "değişiklik yok" demez.
 */
const esc = (s) => String(s).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
const has = (key) => new RegExp(`(${esc(TR[key])}|${esc(EN[key])})`)
const ok = (data) => ({ status: 'ok', data })

const EXEC = { certs: { total: 2, ok: 2, evaluated: 2, unchecked: 0, health_pct: 100 }, alerts: { visible: true, open: 1, critical: 0 },
  sla: { target_pct: 99.9, monitors: 2, breaches: 0, worst: [], by_team: [] }, teams: [], window_days: 30 }
const CERTS = [{ domain: 'ok.example.com', days_remaining: 120, status: 'valid', alert_level: 'valid', team_id: 1, team_name: 'A', tier: 1 }]
const TEAMS = { mode: 'domains', teams: [{ team_id: 1, team_name: 'A', sy_domains: ['ok.example.com'], ug_domains: [] }] }

beforeEach(() => {
  vi.clearAllMocks()
  perm.value = { 'monitoring.read': true, 'alerts.read': true }
  perm.scope = null
  window.history.replaceState(null, '', '/?tab=stats')
  api.getExecutiveStats.mockResolvedValue({ success: true, data: EXEC })
  api.getRenewalTimeliness.mockResolvedValue({ success: true, data: { windows: [] } })
  api.getHealthSeries.mockResolvedValue({ success: true, data: { scope: 'org', org_allowed: true, teams: [], points: [], collecting_since: null } })
  api.monitoring.getOverview.mockResolvedValue({ success: true, data: { types: [] } })
  api.admin.getAlertNoise.mockResolvedValue({ success: true, data: { series: [], total: 0 } })
  api.admin.getAlertTeamStats.mockResolvedValue({ success: true, data: { teams: [] } })
  api.dataQuality.summary.mockResolvedValue({ success: true, data: { teams: [] } })
})
afterEach(() => { window.history.replaceState(null, '', '/') })

describe('bölüm "Tekrar dene" yalnız o kaynağı fresh ile okur (bulgu 1)', () => {
  it('yönetici özetinde okunamayan blok → "Tekrar dene" getExecutiveStats(true); diğer kaynaklar yeniden istenmez', async () => {
    api.getExecutiveStats.mockResolvedValueOnce({ success: true, data: { ...EXEC, alerts: { visible: true, error_msg: 'X' } } })
    render(<StatsView certs={CERTS} teamStats={TEAMS} teamStatsStatus="ok" onRowClick={vi.fn()} />)
    const band = await waitFor(() => {
      const b = document.querySelector('[data-slot="exs-partial"]')
      if (!b) throw new Error('yok')
      return b
    })
    expect(api.getExecutiveStats).toHaveBeenCalledTimes(1)
    expect(api.getExecutiveStats).toHaveBeenLastCalledWith(false)
    const renewalCalls = api.getRenewalTimeliness.mock.calls.length
    fireEvent.click(band.querySelector('button'))
    await waitFor(() => expect(api.getExecutiveStats).toHaveBeenCalledTimes(2))
    expect(api.getExecutiveStats).toHaveBeenLastCalledWith(true)
    await waitFor(() => expect(document.querySelector('[data-slot="exs-partial"]')).toBeNull())
    expect(api.getRenewalTimeliness).toHaveBeenCalledTimes(renewalCalls)
    expect(api.admin.getAlertNoise).toHaveBeenCalledTimes(1)
  })

  it('izleme türleri okunamadı → "Tekrar dene" getOverview(24, true)', async () => {
    api.monitoring.getOverview.mockResolvedValueOnce({ success: false, error: 'x' })
    render(<StatsView certs={CERTS} teamStats={TEAMS} teamStatsStatus="ok" onRowClick={vi.fn()} />)
    const failed = await waitFor(() => {
      const c = document.querySelector('[data-slot="stats-mtypes"] [data-slot="stats-src-failed"]')
      if (!c?.querySelector('button')) throw new Error('yok')
      return c
    })
    fireEvent.click(failed.querySelector('button'))
    await waitFor(() => expect(api.monitoring.getOverview).toHaveBeenCalledTimes(2))
    expect(api.monitoring.getOverview).toHaveBeenLastCalledWith(24, true)
  })
})

describe('5 dk yoklaması 30 günlük alarm eğilimini yeniden okumaz (bulgu 3)', () => {
  it('başarılı okunduysa noise atlanır, takım alarmları yine okunur; başarısızsa yoklama noise\'u yeniden dener', async () => {
    render(<StatsView certs={CERTS} teamStats={TEAMS} teamStatsStatus="ok" onRowClick={vi.fn()} />)
    await waitFor(() => expect(api.admin.getAlertNoise).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(document.querySelector('[data-slot="stats-trend-none"]')).not.toBeNull())
    // Görünür sekmede "visibilitychange" = yoklama turu (useVisibleInterval test kancası)
    await act(async () => { document.dispatchEvent(new Event('visibilitychange')) })
    await waitFor(() => expect(api.admin.getAlertTeamStats).toHaveBeenCalledTimes(2))
    expect(api.getExecutiveStats).toHaveBeenCalledTimes(2)
    expect(api.admin.getAlertNoise).toHaveBeenCalledTimes(1)
  })

  it('noise ilk okumada başarısız → yoklama yeniden dener', async () => {
    api.admin.getAlertNoise.mockResolvedValueOnce({ success: false, error: 'x' })
    render(<StatsView certs={CERTS} teamStats={TEAMS} teamStatsStatus="ok" onRowClick={vi.fn()} />)
    await waitFor(() => expect(api.admin.getAlertNoise).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(document.querySelector('[data-slot="stats-alarm-trend"] [data-slot="stats-src-failed"]')).not.toBeNull())
    await act(async () => { document.dispatchEvent(new Event('visibilitychange')) })
    await waitFor(() => expect(api.admin.getAlertNoise).toHaveBeenCalledTimes(2))
  })
})

describe('sağlık yüzdesi aşağı kesilir — sunucuyla aynı tablo (bulgu 8)', () => {
  const table = javaStringTable(readJavaTest('service/CertHealthCountsTest.java'), 'HEALTH_TABLE')
  it('Java HEALTH_TABLE satırları', () => {
    expect(table.length).toBeGreaterThan(5)
    for (const [k, ev, want] of table) {
      expect(healthPctOf(Number(k), Number(ev)), `${k}/${ev}`).toBe(want == null ? null : Number(want))
    }
  })
  it('takım karnesi 1999 / 2000 → 99,9 (yuvarlama %100 yazıyordu)', () => {
    const certs = Array.from({ length: 2000 }, (_, i) => ({ domain: `d${i}.example.com`, days_remaining: i === 0 ? -1 : 200, status: 'valid', alert_level: i === 0 ? 'expired' : 'valid' }))
    const rows = teamScorecard({ teams: [{ id: 1, name: 'A', domains: certs.map((c) => c.domain) }], certs })
    expect(rows[0].certs.health).toBe(99.9)
  })
})

describe('sağlık serisi: eksik gün boşluk, sertifikasız takım (bulgu 4 + 8)', () => {
  it('fillHealthDays: aradaki ve bugüne kadarki eksik gün null; sıra ve etiket', () => {
    const rows = fillHealthDays([
      { day: '2026-11-08', health_pct: 90 }, { day: '2026-11-10', health_pct: 91 }, { day: '2026-11-11', health_pct: null },
    ], '2026-11-13')
    expect(rows.map((r) => r.day)).toEqual(['2026-11-08', '2026-11-09', '2026-11-10', '2026-11-11', '2026-11-12', '2026-11-13'])
    expect(rows.map((r) => r.health)).toEqual([90, null, 91, null, null, null])
    expect(rows[0].label).toBe('11-08')
    expect(fillHealthDays([], '2026-11-13')).toEqual([])
    // Ay ve yıl sınırı
    expect(fillHealthDays([{ day: '2026-12-31', health_pct: 1 }], '2027-01-02').map((r) => r.day)).toEqual(['2026-12-31', '2027-01-01', '2027-01-02'])
  })

  it('no_certificates → "bu takımda izlenen sertifika yok" (asla "bir saat içinde yazılır")', () => {
    render(<HealthSeriesCard source={ok({ scope: 'team', team_id: 7, org_allowed: false, teams: [{ id: 7, name: 'T' }], points: [], collecting_since: null, no_certificates: true })}
      onRetry={vi.fn()} team={null} onTeam={vi.fn()} />)
    expect(document.querySelector('[data-slot="stats-health-nocerts"]')?.textContent).toMatch(has('stx.hsNoCerts'))
    expect(document.querySelector('[data-slot="stats-health-collecting"]')).toBeNull()
  })

  it('no_certificates yoksa eski "toplanıyor" iletisi', () => {
    render(<HealthSeriesCard source={ok({ scope: 'org', org_allowed: true, teams: [], points: [], collecting_since: null })}
      onRetry={vi.fn()} team={null} onTeam={vi.fn()} />)
    expect(document.querySelector('[data-slot="stats-health-collecting"]')?.textContent).toMatch(has('stx.hsCollectingSoon'))
  })
})

describe('bilinmeyen başarı oranı / ölçülmemiş SLA yeşil değil (bulgu 5 + 6)', () => {
  it('stats_unavailable ya da yalnız duraklatılmış tür → nötr; gerçek bulgu (sorunlu) kötü kalır; ölçülen iyi tür iyi', () => {
    const ov = { types: [
      { type: 'http', active: 3, paused: 0, down: 0, stale: 0, open_alerts: 0, stats_unavailable: true },
      { type: 'ping', active: 0, paused: 2, down: 0, stale: 0, open_alerts: 0 },
      { type: 'port', active: 2, paused: 0, down: 1, stale: 0, open_alerts: 1, stats_unavailable: true },
      { type: 'dns', active: 2, paused: 0, down: 0, stale: 0, open_alerts: 0, checks_window: 10, success_window: 10, success_rate_window: 100 },
    ] }
    render(<MonitorTypesSummary source={ok(ov)} onRetry={vi.fn()} />)
    const tone = (ty) => document.querySelector(`[data-slot="stats-mtype"][data-type="${ty}"]`)?.getAttribute('data-tone')
    expect(tone('http')).toBe('neutral')
    expect(tone('ping')).toBe('neutral')
    expect(tone('port')).toBe('bad')
    expect(tone('dns')).toBe('ok')
  })

  it('SLA: ölçülen izleme 0 → "—" ve nötr (yeşil 0 değil); ölçülen varsa 0 ihlal iyi', () => {
    const { unmount } = render(<ExecutiveSummary source={ok({ ...EXEC, sla: { target_pct: 99.9, monitors: 0, breaches: 0, worst: [] } })} />)
    const sla = document.querySelector('[data-kpi="sla"]')
    expect(sla.getAttribute('data-tone')).toBe('neutral')
    expect(sla.querySelector('b').textContent).toBe('—')
    unmount()
    render(<ExecutiveSummary source={ok(EXEC)} />)
    expect(document.querySelector('[data-kpi="sla"]').getAttribute('data-tone')).toBe('ok')
  })
})

describe('"ne değişti" satırı (bulgu 7)', () => {
  it('alarm izni yok (alerts_visible=false) → alarm parçası yok; diğerleri sıfırsa "değişiklik yok"', async () => {
    api.getRecentChanges.mockResolvedValue({ success: true, data: { days: 7, added: 0, removed: 0, renewed: 0, alerts_opened: null, alerts_resolved: null, alerts_visible: false } })
    render(<RecentChangesLine />)
    await screen.findByText(has('chg7.none'))
    expect(document.querySelector('[data-slot="chg7-unknown"]')).toBeNull()
  })

  it('okunamayan sayı (null) → "değişiklik yok" DENMEZ, "okunamadı" notu', async () => {
    api.getRecentChanges.mockResolvedValue({ success: true, data: { days: 7, added: 2, removed: null, renewed: null, alerts_opened: null, alerts_resolved: null, alerts_visible: true } })
    render(<RecentChangesLine />)
    await waitFor(() => expect(document.querySelector('[data-slot="chg7-unknown"]')).not.toBeNull())
    const line = document.querySelector('.recent-changes')
    expect(line.textContent).not.toMatch(has('chg7.none'))
    expect(line.textContent).toMatch(/\+2/)
  })

  it('yalnız okunamayan → yalnız not', async () => {
    api.getRecentChanges.mockResolvedValue({ success: true, data: { days: 7, added: 0, removed: 0, renewed: 0, alerts_opened: null, alerts_resolved: 0, alerts_visible: true } })
    render(<RecentChangesLine />)
    await waitFor(() => expect(document.querySelector('[data-slot="chg7-unknown"]')).not.toBeNull())
    expect(document.querySelector('.recent-changes').textContent).not.toMatch(has('chg7.none'))
  })

  it('eski sunucu (alerts_visible yok, sayılar) → eskisi gibi', async () => {
    api.getRecentChanges.mockResolvedValue({ success: true, data: { days: 7, added: 0, removed: 0, renewed: 0, alerts_opened: 3, alerts_resolved: 0 } })
    render(<RecentChangesLine />)
    await waitFor(() => expect(document.querySelector('.recent-changes')?.textContent).toMatch(/3/))
    expect(document.querySelector('[data-slot="chg7-unknown"]')).toBeNull()
  })
})
