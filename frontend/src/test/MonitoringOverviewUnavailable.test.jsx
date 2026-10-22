import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import MonitoringOverviewPage from '../components/MonitoringOverviewPage.jsx'
import { overviewGaps, verdict, teamsDigest } from '../components/monitoring/overviewModel.js'
import { typeTone } from '../components/monitoring/OverviewTypeCard.jsx'

/**
 * İzleme Panosu — sunucunun OKUMA HATASI işaretleri (2026-10-22, UIM-5). `MonitoringOverviewService` bir okuma düşünce
 * `alerts_unavailable` / `types[].unavailable | checks_unavailable | stats_unavailable` yazar; pano bunları okumuyor ve
 * okunamayan parçayı "0" ve yeşil ("Sorun yok", "Tüm türler sağlıklı") gösteriyordu. Bilinmeyen asla yeşil değildir.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({ monitoring: { getOverview: vi.fn() } }),
}))
import { api } from '../api/client'

const TYPE = (type, extra = {}) => ({ type, total: 0, active: 0, paused: 0, deleted: 0, down: 0, stale: 0, unknown: 0,
  checks_window: 0, failed_window: 0, success_rate_window: null, open_alerts: 0, open_critical: 0, resolved_window: 0, last_checked_at: null, ...extra })
const ROW = (type, id, team, extra = {}) => ({ type, id, name: `${type}-${id}`, target: `t${id}`, team_id: team, team_name: `Takım ${team}`, active: true,
  deleted: false, status: 'up', last_checked_at: '2026-10-22T08:00:00', last_ok: true, interval_seconds: 300, open_alerts: 0,
  checks_window: 10, failed_window: 0, success_rate_window: 100, ...extra })

/** Sağlıklı filo: iki tür, iki takım, hepsi up — işaret yokken her şey yeşil (bugünkü davranış). */
const healthy = (patch = {}) => ({
  success: true,
  data: {
    generated_at: '2026-10-22T08:00:00', window_hours: 24,
    totals: { total: 3, active: 3, paused: 0, deleted: 0, down: 0, stale: 0, unknown: 0, checks_window: 30, failed_window: 0,
      open_alerts: 0, open_critical: 0, resolved_window: 0, success_rate_window: 100, last_checked_at: '2026-10-22T08:00:00' },
    types: [TYPE('http', { total: 2, active: 2, checks_window: 20, success_rate_window: 100 }),
      TYPE('ping', { total: 1, active: 1, checks_window: 10, success_rate_window: 100 })],
    monitors: [ROW('http', 1, 1), ROW('http', 2, 2), ROW('ping', 3, 1)],
    ...patch,
  },
})

const expandAll = async () => fireEvent.click(await screen.findByRole('button', { name: /^(expand all|tümünü aç)$/i }))
const summaryOf = (c, key) => c.querySelector(`[data-slot="mo-section"][data-section="${key}"] [data-slot="mo-section-summary"]`)?.textContent ?? ''
const kpi = (c, key) => c.querySelector(`[data-slot="stat-item"][data-key="${key}"] [data-slot="stat-value"]`)?.textContent

describe('overviewModel — okuma hatası işaretleri', () => {
  it('overviewGaps: işaret yoksa any=false (normal yanıt bugünkü gibi); işaretler türe göre toplanır', () => {
    expect(overviewGaps(healthy().data).any).toBe(false)
    const g = overviewGaps({ alerts_unavailable: true, types: [TYPE('http', { unavailable: true }), TYPE('ping', { checks_unavailable: true, stats_unavailable: true })] })
    expect(g).toMatchObject({ alerts: true, types: ['http'], checks: ['ping'], stats: ['ping'], any: true, allStats: false })
    expect(overviewGaps(null).any).toBe(false)
  })

  it('verdict: eksik veri varken "Tüm izlemeler sağlıklı" DEĞİL; sorunlu hükmü gerçek bulgu olarak kalır', () => {
    const totals = healthy().data.totals
    expect(verdict(totals).key).toBe('mo.health.ok')
    expect(verdict(totals, { any: true })).toEqual({ tone: 'neutral', key: 'mo.health.partial', n: 0 })
    expect(verdict({ ...totals, down: 1 }, { any: true }).key).toBe('mo.health.down')
  })

  it('typeTone: okunamayan / hepsi bilinmeyen tür ve okunamayan alarmlar → nötr (yeşil değil)', () => {
    expect(typeTone(TYPE('http', { active: 2 }))).toBe('ok')
    expect(typeTone(TYPE('http', { active: 2, unavailable: true }))).toBe('neutral')
    expect(typeTone(TYPE('http', { active: 2, checks_unavailable: true }))).toBe('neutral')
    expect(typeTone(TYPE('http', { active: 3, unknown: 3 }))).toBe('neutral')
    expect(typeTone(TYPE('http', { active: 3, unknown: 1 }))).toBe('ok')
    expect(typeTone(TYPE('http', { active: 2 }), { alertsUnknown: true })).toBe('neutral')
    expect(typeTone(TYPE('http', { active: 2, down: 1 }), { alertsUnknown: true })).toBe('bad')
  })

  it('teamsDigest: aktif izlemelerinin hepsi bilinmeyen takım nötr; alarmlar okunamadıysa nötr', () => {
    const ok = { total: 2, paused: 0, unknown: 0, down: 0, stale: 0, openAlerts: 0 }
    expect(teamsDigest([ok, ok]).tone).toBe('ok')
    expect(teamsDigest([ok, { ...ok, unknown: 2 }])).toMatchObject({ tone: 'neutral', unknown: 1, issues: 0 })
    expect(teamsDigest([ok, ok], { alertsUnknown: true }).tone).toBe('neutral')
    expect(teamsDigest([{ ...ok, down: 1 }, { ...ok, unknown: 2 }]).tone).toBe('bad')
  })
})

describe('İzleme Panosu — okunamayan parça "0 / sağlıklı" gösterilmez', () => {
  beforeEach(() => { vi.clearAllMocks(); window.history.replaceState({}, '', '/?tab=monitoring'); try { localStorage.clear() } catch { /* yok */ } })

  it('işaret yokken (normal yanıt) bugünkü yeşil özet ve uyarı bandı yok', async () => {
    api.monitoring.getOverview.mockResolvedValue(healthy())
    const { container } = render(<MonitoringOverviewPage />)
    await waitFor(() => expect(container.querySelector('[data-slot="mo-section"]')).not.toBeNull())
    expect(summaryOf(container, 'kpis')).toMatch(/sorun yok|no issues|all clear/i)
    expect(container.querySelector('[data-slot="mo-unavailable"]')).toBeNull()
    expect(kpi(container, 'alerts')).toBe('0')
  })

  it('alerts_unavailable → alarm KPI "—", uyarı bandı, "Sorun yok" / "Tüm türler sağlıklı" / "Tüm takımlar sağlıklı" YOK, hüküm nötr', async () => {
    api.monitoring.getOverview.mockResolvedValue(healthy({ alerts_unavailable: true }))
    const { container } = render(<MonitoringOverviewPage />)
    await waitFor(() => expect(container.querySelector('[data-slot="mo-unavailable"]')).not.toBeNull())
    expect(container.querySelector('[data-slot="mo-unavailable"] [data-gap="alerts"]')).not.toBeNull()
    expect(kpi(container, 'alerts')).toBe('—')
    expect(summaryOf(container, 'kpis')).not.toMatch(/sorun yok|no issues|all clear/i)
    expect(summaryOf(container, 'types')).not.toMatch(/tüm türler sağlıklı|all types (are )?healthy/i)
    expect(summaryOf(container, 'teams')).not.toMatch(/tüm takımlar sağlıklı|all teams (are )?healthy/i)
    expect(container.querySelector('[data-slot="mo-section"][data-section="types"]')).toHaveAttribute('data-tone', 'neutral')
    await expandAll()
    expect(container.querySelector('[data-slot="mo-health"]')).toHaveAttribute('data-tone', 'neutral')
    expect(container.querySelector('[data-slot="mo-attention-clear"]')).toBeNull()
    expect(container.querySelector('[data-slot="mo-type-card"][data-type="http"]')).toHaveAttribute('data-tone', 'neutral')
    // Takım satırı "Sorun yok" (yeşil) değil, "durum bilinmiyor"
    expect(container.querySelectorAll('[data-slot="mo-team-unknown"]').length).toBeGreaterThan(0)
    // Alarm KPI'sı tıklanınca "alarm yok" özet penceresi açılmaz — yeniden okur
    const calls = api.monitoring.getOverview.mock.calls.length
    fireEvent.click(container.querySelector('[data-slot="stat-item"][data-key="alerts"]'))
    await waitFor(() => expect(api.monitoring.getOverview.mock.calls.length).toBe(calls + 1))
    expect(document.querySelector('[data-slot="mo-kpi-dialog"]')).toBeNull()
  })

  it('types[].unavailable → kart nötr + "okunamadı", "Tüm türler sağlıklı" YOK, bant türü adlandırır', async () => {
    const res = healthy()
    res.data.types = [res.data.types[0], TYPE('ping', { unavailable: true })]
    res.data.monitors = res.data.monitors.filter((m) => m.type !== 'ping')
    api.monitoring.getOverview.mockResolvedValue(res)
    const { container } = render(<MonitoringOverviewPage />)
    await waitFor(() => expect(container.querySelector('[data-slot="mo-unavailable"] [data-gap="types"]')).not.toBeNull())
    expect(container.querySelector('[data-slot="mo-unavailable"] [data-gap="types"]').textContent).toMatch(/ping/i)
    expect(summaryOf(container, 'types')).not.toMatch(/tüm türler sağlıklı|all types (are )?healthy/i)
    expect(summaryOf(container, 'kpis')).not.toMatch(/sorun yok|no issues|all clear/i)
    await expandAll()
    const card = container.querySelector('[data-slot="mo-type-card"][data-type="ping"]')
    expect(card).toHaveAttribute('data-tone', 'neutral')
    expect(card.querySelector('[data-slot="mo-type-unavailable"]')).not.toBeNull()
    expect(card.textContent).not.toMatch(/0 aktif|0 active/i)
    expect(card.querySelector('[data-slot="mo-type-success"]').textContent).toBe('—')
  })

  it('checks_unavailable + stats_unavailable → kart nötr; koşum KPI alt satırı "okunamadı"; başarı oranı çipi yok', async () => {
    const res = healthy()
    res.data.types = [res.data.types[0], TYPE('ping', { total: 1, active: 1, unknown: 1, checks_unavailable: true, stats_unavailable: true })]
    res.data.totals = { ...res.data.totals, unknown: 1 }
    res.data.monitors = res.data.monitors.map((m) => (m.type === 'ping' ? { ...m, status: 'unknown' } : m))
    api.monitoring.getOverview.mockResolvedValue(res)
    const { container } = render(<MonitoringOverviewPage />)
    await waitFor(() => expect(container.querySelector('[data-slot="mo-unavailable"] [data-gap="checks"]')).not.toBeNull())
    expect(container.querySelector('[data-slot="mo-unavailable"] [data-gap="stats"]')).not.toBeNull()
    expect(container.querySelector('[data-slot="stat-item"][data-key="checks"]').textContent).toMatch(/okunamad|unreadable/i)
    expect(summaryOf(container, 'health')).not.toMatch(/%/)
    await expandAll()
    expect(container.querySelector('[data-slot="mo-type-card"][data-type="ping"]')).toHaveAttribute('data-tone', 'neutral')
    expect(container.querySelector('[data-slot="mo-type-card"][data-type="http"]')).toHaveAttribute('data-tone', 'ok')
  })

  it('aktif izlemelerinin hepsi bilinmeyen tür (işaret olmadan da) "Tüm türler sağlıklı" saymaz', async () => {
    const res = healthy()
    res.data.types = [res.data.types[0], TYPE('ping', { total: 1, active: 1, unknown: 1 })]
    res.data.totals = { ...res.data.totals, unknown: 1 }
    api.monitoring.getOverview.mockResolvedValue(res)
    const { container } = render(<MonitoringOverviewPage />)
    await waitFor(() => expect(container.querySelector('[data-slot="mo-section"][data-section="types"]')).not.toBeNull())
    expect(summaryOf(container, 'types')).not.toMatch(/tüm türler sağlıklı|all types (are )?healthy/i)
    expect(summaryOf(container, 'types')).toMatch(/bilinmiyor|unknown/i)
    expect(container.querySelector('[data-slot="mo-section"][data-section="types"]')).toHaveAttribute('data-tone', 'neutral')
  })
})

