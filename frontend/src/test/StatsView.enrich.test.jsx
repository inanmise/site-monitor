import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'

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
      getExecutiveStats: vi.fn(), getRenewalTimeliness: vi.fn(), getHealthSeries: vi.fn(),
      monitoring: { getOverview: vi.fn() },
      admin: { getAlertNoise: vi.fn(), getAlertTeamStats: vi.fn() },
      dataQuality: { summary: vi.fn() },
    }),
  }
})
const exportMod = vi.hoisted(() => ({ xlsx: vi.fn(() => true), pdf: vi.fn(async () => true) }))
vi.mock('../components/stats/statsExport.js', () => ({ exportStatsXlsx: exportMod.xlsx, exportStatsPdf: exportMod.pdf }))
import { api } from '../api/client'
import StatsView from '../components/StatsView.jsx'

/**
 * İstatistikler zenginleştirmesi (2026-11-13) — sayfa düzeyi: izne bağlı kaynaklar (izin yoksa istek GİTMEZ), güvenlik /
 * sağlayıcı / hafta süzgeçleri (URL `st_sec` / `st_iss` / `st_wk`), takımsız satır, karne bilinmeyen "—", satır eylemleri
 * Pano kapılarıyla, Yenile (App dalgası + taze kaynaklar), dışa aktarım, sertifikasız kullanıcı da özeti görür, matris
 * yükleniyor / okunamadı ayrımı, "yaklaşan bitiş yok" asla yeşil değil (dolmuş varken).
 */
const DAY = 86_400_000
const iso = (ms) => new Date(Date.now() + ms).toISOString().slice(0, 19)
const esc = (s) => String(s).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
const fmt = (s, args) => args.reduce((acc, a, i) => acc.split(`{${i}}`).join(String(a)), s)
const both = (key, ...args) => new RegExp(`^(${esc(fmt(TR[key], args))}|${esc(fmt(EN[key], args))})$`)
const has = (key, ...args) => new RegExp(`(${esc(fmt(TR[key], args))}|${esc(fmt(EN[key], args))})`)

function cert(domain, days, extra = {}) {
  const status = extra.status ?? 'valid'
  return {
    domain, days_remaining: days, not_after: days == null ? null : iso(days * DAY), checked_at: iso(-60_000), status,
    alert_level: status === 'error' ? 'error' : days < 0 ? 'expired' : days <= 30 ? 'warning' : 'valid',
    issuer_cn: 'R3', tier: 1, team_id: 1, team_name: 'Takım A', tls_grade: 'A', ...extra,
  }
}
const LONG = 'Kurumsal Dijital Kanallar ve Ödeme Sistemleri Uygulama Geliştirme Müdürlüğü (Bölge 3 / Ankara)'
const CERTS = [
  cert('ok.example.com', 120),
  cert('weak.example.com', 90, { tls_grade: 'F', public_key_algorithm: 'RSA', public_key_size: 1024, issuer_cn: 'DigiCert' }),
  cert('soon.example.com', 3, { tls_grade: undefined }),
  cert('orphan.example.com', 200, { team_id: null, team_name: null }),
]
const TEAMS = { mode: 'domains', teams: [{ team_id: 1, team_name: LONG, sy_domains: ['ok.example.com', 'weak.example.com', 'soon.example.com'], ug_domains: [] }] }
const EXEC = { certs: { total: 4, ok: 3, evaluated: 4, unchecked: 0, health_pct: 75 }, alerts: { visible: true, open: 1, critical: 0 },
  sla: { target_pct: 99.9, monitors: 2, breaches: 1, worst: [], by_team: [{ team_id: 1, monitors: 2, breaches: 1 }] }, teams: [], window_days: 30 }

const rows = () => [...document.querySelectorAll('[data-slot="stats-row"]')].map((r) => r.getAttribute('data-domain'))
const param = (k) => new URLSearchParams(window.location.search).get(k)

function renderView(props = {}) {
  return render(<StatsView certs={CERTS} teamStats={TEAMS} teamStatsStatus="ok" onRowClick={vi.fn()} {...props} />)
}

beforeEach(() => {
  vi.clearAllMocks()
  perm.value = {}
  perm.scope = null
  window.history.replaceState(null, '', '/?tab=stats')
  api.getExecutiveStats.mockResolvedValue({ success: true, data: EXEC })
  api.getRenewalTimeliness.mockResolvedValue({ success: true, data: { windows: [{ months: 3, on_time_pct: 90, renewals: 9, before_expiry: 9, after_expiry: 0, last_minute: 1, lapses: 1 }] } })
  api.getHealthSeries.mockResolvedValue({ success: true, data: { scope: 'org', org_allowed: true, teams: [], points: [], collecting_since: '2026-11-13' } })
  api.monitoring.getOverview.mockResolvedValue({ success: true, data: { types: [{ type: 'http', active: 3, paused: 0, down: 1, stale: 0, open_alerts: 1, success_rate_window: 97.5 }] } })
  api.admin.getAlertNoise.mockResolvedValue({ success: true, data: { series: [{ date: '2026-11-12', count: 3 }], total: 3, critical: 1, mttr_minutes: 45, resolved_pct: 66.7 } })
  api.admin.getAlertTeamStats.mockResolvedValue({ success: true, data: { teams: [{ team_id: 1, team_name: LONG, open: 2, last30: 7 }] } })
  api.dataQuality.summary.mockResolvedValue({ success: true, data: { teams: [{ id: 1, name: LONG, score: 82 }] } })
})
afterEach(() => { window.history.replaceState(null, '', '/') })

describe('StatsView zenginleştirme — izne bağlı kaynaklar', () => {
  it('izin yok → izleme panosu / alarm / veri kalitesi İSTENMEZ, bölümler çizilmez; karne alarm "—"', async () => {
    renderView()
    await waitFor(() => expect(api.getExecutiveStats).toHaveBeenCalled())
    expect(api.monitoring.getOverview).not.toHaveBeenCalled()
    expect(api.admin.getAlertNoise).not.toHaveBeenCalled()
    expect(api.admin.getAlertTeamStats).not.toHaveBeenCalled()
    expect(api.dataQuality.summary).not.toHaveBeenCalled()
    expect(document.querySelector('[data-slot="stats-mtypes"]')).toBeNull()
    expect(document.querySelector('[data-slot="stats-alarm-trend"]')).toBeNull()
    const row = await waitFor(() => {
      const r = document.querySelector('[data-slot="stats-score-row"][data-team="1"]')
      if (!r) throw new Error('yok')
      return r
    })
    // Alarm izni yok → alarm hücresi bilinmiyor; SLA yönetici özetinden bilinir
    const cells = row.querySelectorAll('td')
    expect(cells[4].textContent).toBe('—')
    expect(cells[5].textContent).toMatch(/1/)
  })

  it('izinli → izleme türleri, alarm eğilimi ve karne alarm / veri kalitesi dolar; tür kartı İzleme Panosunu süzer', async () => {
    perm.value = { 'monitoring.read': true, 'alerts.read': true, 'data_quality.view': true }
    const nav = vi.fn()
    window.addEventListener('sm:navigate', nav)
    renderView()
    const card = await screen.findByRole('button', { name: has('stx.mtOpen', TR['nav.http']) })
    fireEvent.click(card)
    window.removeEventListener('sm:navigate', nav)
    expect(nav.mock.calls[0][0].detail).toMatchObject({ tab: 'monitoring', params: { mo_type: 'http' } })
    await waitFor(() => expect(document.querySelector('[data-slot="stats-trend-stat"][data-stat="mttr"]')?.textContent).toMatch(/45/))
    const row = document.querySelector('[data-slot="stats-score-row"][data-team="1"]')
    await waitFor(() => expect(row.querySelectorAll('td')[4].textContent).toMatch(/^2/))
    expect(row.querySelectorAll('td')[6].textContent).toBe('82')
    // Uzun AD adı kırpılmaz (TeamBadge wrap)
    expect(row.textContent).toContain(LONG)
  })
})

describe('StatsView zenginleştirme — yeni süzgeçler', () => {
  it('güvenlik çipi tabloyu süzer (st_sec) ve çip çıkar; aynı çip kaldırır', async () => {
    renderView()
    const chip = document.querySelector('[data-slot="stats-sec-chip"][data-sec="weakkey"]')
    fireEvent.click(chip)
    expect(rows()).toEqual(['weak.example.com'])
    await waitFor(() => expect(param('st_sec')).toBe('weakkey'))
    fireEvent.click(document.querySelector('[data-slot="stats-sec-chip"][data-sec="g:none"]'))
    expect(rows()).toEqual(['soon.example.com'])
    fireEvent.click(document.querySelector('[data-slot="stats-sec-chip"][data-sec="g:none"]'))
    expect(rows()).toHaveLength(4)
  })

  it('sağlayıcı satırı sağlayıcı süzgecini ayarlar; katman satırı katman süzgecini', () => {
    renderView()
    fireEvent.click(document.querySelector('[data-slot="stats-ca-row"][data-ca="DigiCert"]'))
    expect(rows()).toEqual(['weak.example.com'])
    fireEvent.click(document.querySelector('[data-slot="stats-ca-row"][data-ca="DigiCert"]'))
    expect(rows()).toHaveLength(4)
  })

  it('bu haftanın çubuğu yalnız o hafta dolanları gösterir (st_wk)', async () => {
    renderView()
    const bars = [...document.querySelectorAll('[data-slot="stats-week"]')]
    expect(bars).toHaveLength(12)
    const withCount = bars.find((b) => !b.disabled)
    fireEvent.click(withCount)
    expect(rows()).toEqual(['soon.example.com'])
    await waitFor(() => expect(param('st_wk')).toBe(withCount.getAttribute('data-week')))
  })

  it('takımsız sertifika matriste ayrı satır; karnedeki takımsız satır tabloyu ona süzer (st_team=none)', async () => {
    renderView()
    fireEvent.click(screen.getByRole('button', { name: both('stv.mxShow') }))
    expect(document.querySelector('[data-slot="stats-matrix-teamless"]')).not.toBeNull()
    fireEvent.click(document.querySelector('[data-slot="stats-score-row"][data-team="none"]'))
    expect(rows()).toEqual(['orphan.example.com'])
    await waitFor(() => expect(param('st_team')).toBe('none'))
  })
})

describe('StatsView zenginleştirme — eylemler', () => {
  it('satır eylemleri Pano kapılarıyla: kontrol yalnız onCheckNow varken, plan yalnız canPlan true iken', async () => {
    const check = vi.fn()
    const onPlan = vi.fn()
    renderView({
      cardActions: (c) => ({ onCheckNow: c.domain === 'ok.example.com' ? check : undefined, checking: false }),
      canPlan: (c) => c.domain !== 'soon.example.com', onPlanRenewal: onPlan, cardExtras: {},
    })
    const cell = (d) => document.querySelector(`[data-slot="stats-row"][data-domain="${d}"] [data-slot="stats-row-actions"]`)
    // soon: kontrol yok, plan yok → menü yok
    expect(cell('soon.example.com').querySelector('button')).toBeNull()
    expect(cell('ok.example.com').querySelector('button')).not.toBeNull()
  })

  it('Yenile: App dalgası + kaynaklar taze (fresh) okunur; son güncelleme yazılır', async () => {
    const onRefresh = vi.fn(async () => {})
    renderView({ onRefresh, lastUpdate: new Date().toISOString() })
    await waitFor(() => expect(api.getExecutiveStats).toHaveBeenCalledWith(false))
    await act(async () => { fireEvent.click(document.querySelector('[data-slot="stats-refresh"]')) })
    expect(onRefresh).toHaveBeenCalled()
    await waitFor(() => expect(api.getExecutiveStats).toHaveBeenCalledWith(true))
    await waitFor(() => expect(document.querySelector('[data-slot="stats-updated"]').textContent).toMatch(has('stx.updatedAt', '')))
  })

  it('dışa aktarım menüsü Excel modülünü çağırır (dört sayfa)', async () => {
    const user = (await import('@testing-library/user-event')).default.setup()
    renderView()
    await user.click(document.querySelector('[data-slot="stats-export-menu"]'))
    await user.click(await screen.findByRole('menuitem', { name: has('stx.exportXlsx') }))
    await waitFor(() => expect(exportMod.xlsx).toHaveBeenCalled())
    const sheets = exportMod.xlsx.mock.calls[0][0]
    expect(Object.keys(sheets)).toEqual(['summary', 'matrix', 'table', 'scorecard'])
    expect(sheets.table).toHaveLength(5)
  })
})

describe('StatsView zenginleştirme — boş / hata durumları', () => {
  it('sertifikası olmayan kullanıcı da operasyon özetini görür', async () => {
    renderView({ certs: [] })
    expect(await screen.findByText(both('stv.emptyTitle'))).toBeInTheDocument()
    await waitFor(() => expect(document.querySelector('[data-slot="exs-kpi"][data-kpi="health"]')).not.toBeNull())
  })

  it('matris: takım listesi yükleniyor ≠ okunamadı ≠ boş', () => {
    const { unmount } = renderView({ teamStats: null, teamStatsStatus: 'loading' })
    fireEvent.click(screen.getByRole('button', { name: both('stv.mxShow') }))
    expect(document.querySelector('[data-slot="stats-matrix-loading"]')).not.toBeNull()
    unmount()
    renderView({ teamStats: null, teamStatsStatus: 'failed' })
    fireEvent.click(screen.getByRole('button', { name: both('stv.mxShow') }))
    expect(document.querySelector('[data-slot="stats-matrix-failed"]')).not.toBeNull()
  })

  it('yaklaşan bitiş yok ama dolmuş varsa yeşil "sorun yok" DEĞİL', () => {
    renderView({ certs: [cert('gone.example.com', -3), cert('err.example.com', null, { status: 'error' })] })
    const card = document.querySelector('[data-slot="stats-upcoming"]')
    expect(within(card).queryByText(both('stv.upEmpty'))).toBeNull()
    expect(card.querySelector('[data-slot="stats-upcoming-attention"]')).not.toBeNull()
  })

  it('yeni uç eski sunucuda yok (404) → "bu sürümde yok", asla boş seri', async () => {
    api.getRenewalTimeliness.mockResolvedValue(Object.defineProperty({ success: false }, 'errorInfo', { value: { status: 404 } }))
    renderView()
    await waitFor(() => expect(document.querySelector('[data-slot="stats-renewal"] [data-slot="stats-src-unsupported"]')).not.toBeNull())
  })
})

describe('StatsView zenginleştirme — inceleme düzeltmeleri (2026-11-13)', () => {
  it('kapsamlı kullanıcı + listede olmayan UG alanı → "UG de dahil" notu; tümünü gören ya da kapsam bilinmeyen → not yok', () => {
    const lean = { mode: 'domains', teams: [{ team_id: 1, team_name: LONG, sy_domains: ['ok.example.com'], ug_domains: ['baska-takim.example.com'] }] }
    perm.scope = { monitoring_all: false }
    const { unmount } = renderView({ teamStats: lean })
    expect(document.querySelector('[data-slot="stats-ug-note"]')).not.toBeNull()
    unmount()
    perm.scope = { monitoring_all: true }
    const second = renderView({ teamStats: lean })
    expect(document.querySelector('[data-slot="stats-ug-note"]')).toBeNull()
    second.unmount()
    perm.scope = null
    renderView({ teamStats: lean })
    expect(document.querySelector('[data-slot="stats-ug-note"]')).toBeNull()
  })

  it('UG alanı listede zaten varsa (ayrışma yok) not çıkmaz', () => {
    perm.scope = { monitoring_all: false }
    renderView({ teamStats: { mode: 'domains', teams: [{ team_id: 1, team_name: 'A', sy_domains: [], ug_domains: ['ok.example.com'] }] } })
    expect(document.querySelector('[data-slot="stats-ug-note"]')).toBeNull()
  })

  it('karne satırsız: takım listesi yükleniyor → iskelet; okunamadı → hata + yeniden dene (Yenile); boş → nötr', async () => {
    const onRefresh = vi.fn(async () => {})
    // Başka kaynaktan da satır gelmesin (SLA takım kırılımı boş) — karne yalnız takım listesine bakar
    api.getExecutiveStats.mockResolvedValue({ success: true, data: { ...EXEC, sla: { ...EXEC.sla, by_team: [] } } })
    const a = renderView({ certs: [], teamStats: null, teamStatsStatus: 'loading' })
    await waitFor(() => expect(document.querySelector('[data-slot="stats-score-loading"]')).not.toBeNull())
    expect(screen.getByText(both('stx.scTitle'))).toBeInTheDocument()
    a.unmount()
    const b = renderView({ certs: [], teamStats: null, teamStatsStatus: 'failed', onRefresh })
    const failed = await waitFor(() => {
      const el = document.querySelector('[data-slot="stats-score-failed"]')
      if (!el) throw new Error('yok')
      return el
    })
    fireEvent.click(within(failed).getByRole('button', { name: both('stx.retry') }))
    await waitFor(() => expect(onRefresh).toHaveBeenCalled())
    b.unmount()
    renderView({ certs: [], teamStats: { mode: 'domains', teams: [] }, teamStatsStatus: 'ok' })
    await waitFor(() => expect(document.querySelector('[data-slot="stats-score-empty"]')).not.toBeNull())
  })

  it('yenileme penceresi saklama süresiyle kısaldıysa etiket açıkça söyler (asla sessizce "12 ay"); sağlık satırı son günün tarihi', async () => {
    api.getRenewalTimeliness.mockResolvedValue({ success: true, data: { retention_days: 180, windows: [
      { months: 3, clamped: false, effective_days: 92, on_time_pct: 90, renewals: 9, before_expiry: 9, after_expiry: 0, last_minute: 1, lapses: 1 },
      { months: 12, clamped: true, effective_days: 180, on_time_pct: 80, renewals: 8, before_expiry: 8, after_expiry: 0, last_minute: 0, lapses: 2 }] } })
    api.getHealthSeries.mockResolvedValue({ success: true, data: { scope: 'org', org_allowed: true, teams: [], collecting_since: '2026-11-01',
      points: [{ day: '2026-11-11', total: 4, evaluated: 4, ok: 3, health_pct: 75 }, { day: '2026-11-12', total: 4, evaluated: 4, ok: 2, health_pct: 50 }] } })
    renderView()
    const w12 = await waitFor(() => {
      const el = document.querySelector('[data-slot="stats-renewal-win"][data-months="12"]')
      if (!el) throw new Error('yok')
      return el
    })
    expect(w12.getAttribute('data-clamped')).toBe('true')
    expect(w12.textContent).toMatch(has('stx.renWindowClamped', 12, '180'))
    const w3 = document.querySelector('[data-slot="stats-renewal-win"][data-months="3"]')
    expect(w3.textContent).toMatch(has('stx.renWindow', 3))
    expect(w3.textContent).toMatch(/bitişe 7 günden az kala|less than 7 days before expiry/)
    const health = document.querySelector('[data-slot="stats-health"]')
    await waitFor(() => expect(health.textContent).toMatch(/12\/11\/2026|12\.11\.2026/))
    expect(health.textContent).not.toMatch(/Bugün|Today/)
  })
})

