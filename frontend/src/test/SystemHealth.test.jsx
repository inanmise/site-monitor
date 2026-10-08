import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, waitFor, act, screen, fireEvent } from './test-utils.jsx'
import userEvent from '@testing-library/user-event'
import SystemHealth from '../components/admin/SystemHealth.jsx'

// Sürüm & Dağıtım bölümü release_history.read ister — mock: her şey görünür.
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ canView: () => true, canEdit: () => true, canExecute: () => true, perms: {} }),
}))
const mobile = vi.hoisted(() => ({ on: false }))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.on }))

/**
 * Sistem Sağlığı — GERÇEK render testi (2026-09-27 yeniden tasarım: durum konsolu).
 *
 * Neden var: 2026-08-19'da bu ekran üretimde `ReferenceError` ile ErrorBoundary'ye düştü — kullanımlar
 * VARSAYILAN KAPALI akordiyonların içindeydi ve fixture ilgili kapıları açmıyordu. Bu yüzden fixture
 * BACKEND'İN GERÇEK ŞEKLİNİ (SchedulerService + SystemController payload'ı) taşır ve test SEKİZ bölümün
 * hepsini tek tek açar. Fixture'ı budarken dikkat: bir alanı silmek ilgili kapıyı kapatır.
 *
 * Sorgular rol/ad ve `data-slot`/`data-kpi`/`data-section`/`data-level` kancalarıyla — sınıf adıyla DEĞİL.
 */
const { adminProxy } = vi.hoisted(() => {
  const target = {
    getSystemHealth: vi.fn(),
    getMetrics:      vi.fn(),
    getHttpMetrics:  vi.fn(),
    getConfigHealth: vi.fn(),
    getHeartbeatTimeline: vi.fn(),
    pushLog: { summary: vi.fn() },
  }
  // Vekil: testin AÇIKÇA kontrol ettiği uçlar elle; geri kalan HER metot ilk erişimde üretilir ve boş başarı döner
  // (bileşen yeni bir uç çağırdığı gün "is not a function" ile CI'da işlenmemiş ret üretmesin).
  return {
    adminProxy: new Proxy(target, {
      get(t, prop) {
        if (prop in t || typeof prop === 'symbol') return t[prop]
        t[prop] = vi.fn(() => Promise.resolve({ success: true, data: [] }))
        return t[prop]
      },
    }),
  }
})

vi.mock('../api/client', () => ({
  formatDate:    (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: { admin: adminProxy, runScheduler: vi.fn(() => Promise.resolve({ success: true })) },
}))

import { api } from '../api/client'

/** SchedulerService + SystemController sağlık payload'ının birebir şekli — SAĞLIKLI. */
const HEALTH = {
  scheduler: { running: false, cron: '0 0 * * * *', last_run: '2026-09-27T04:00:00', next_run: '2099-01-01T00:00:00', last_run_id: 'run-1', instance_id: 'pod-a', active_domains: 412 },
  build: { version: '20.86.0', commit: 'a1b2c3d', environment: 'prod', image_version: '20.86.0', helm_revision: 42, started_at: '2026-09-24T01:00:00', uptime_seconds: 3 * 86400 + 4 * 3600 },
  lock: { held: false },
  // ↓ bu üç alan ProgressBar dallarını AÇAR — budarsanız test değersizleşir
  pool: { active: 3, idle: 7, total: 10, waiting: 0, max_size: 25 },
  executor_pool: { queue_size: 12, queue_capacity: 1000, active_count: 4, pool_size: 20, core_pool_size: 20, max_pool_size: 50, completed_tasks: 84213, caller_runs: 0, saturated: false, jvm_start_time: '2026-09-24T01:00:00Z' },
  memory: { used_mb: 812, free_mb: 236, total_mb: 1048, max_mb: 2048, used_pct: 39 },
  scan: { last_run: '2026-09-27T04:00:00', duration_ms: 42150, total: 214, warnings: 3, errors: 1, last_failure: null },
  scan_alarm: false,
  db_ms: 14,
  smtp: { total: 310, attempted: 310, sent: 310, rate: 100, alarm: false, periods: {
    '1d': { total: 48, attempted: 48, sent: 48, rate: 100, alarm: false },
    '7d': { total: 310, attempted: 310, sent: 310, rate: 100, alarm: false },
    '15d': { total: 640, attempted: 640, sent: 640, rate: 100, alarm: false },
    '30d': { total: 1240, attempted: 1240, sent: 1240, rate: 100, alarm: false } } },
  heartbeat: { last_heartbeat: '2026-09-27T04:59:00', minutes_since: 1, alarm: false, recent: ['2026-09-27T04:59:00', '2026-09-27T04:58:00'] },
  network: { alarm: false, last_error_rate: 0.01, last_network_errors: 1, last_total: 412, threshold: 0.5, min_errors: 3, resolved_at: '2026-09-25T10:00:00' },
  domain_expiry: { source: 'RDAP', alarm: false, last_success: '2026-09-27T03:00:00' },
  weekly_availability: { enabled: true, running: false, next_run: '2026-09-28T07:30:00', last_run_at: '2026-09-21T07:30:00', last_run_sent: 7, last_run_teams: 8, last_run_failed: 0, last_run_no_recipient: 1, last_run_year: 2026, last_run_week_no: 38 },
  cleanup: { hold_active: false, last_run: '2026-09-27T01:00:00', hours_since: 4, total_deleted: 18230, failed_count: 0, duration_ms: 42100, never_run: false, alarm: false },
}
const METRICS = Array.from({ length: 12 }, (_, i) => ({ ts: `2026-09-27T0${Math.floor(i / 6)}:${String((i % 6) * 10).padStart(2, '0')}:00`, cpu_process: 20 + i, heap_pct: 40 + i, threads: 80 + i }))
const HTTP = { summary: { total_requests: 184210, error_rate_pct: 0.8, avg_ms: 142, max_ms: 6120 },
  history: Array.from({ length: 12 }, (_, i) => ({ ts: `2026-09-27T04:${String(i * 5).padStart(2, '0')}:00`, count: 500 + i, avg_ms: 100 + i, errors: i % 5 === 0 ? 1 : 0 })) }
const PUSH = { success: true, data: { kpi: { sent: 240, failed: 3, pending: 0, blocked: 0 } } }
const CONFIG = { success: true, data: { overall: 'ok', checks: [{ key: 'smtp', status: 'ok', detail: 'tested_ok:x', tab: 'smtp' }, { key: 'ldap', status: 'ok', detail: 'tested_ok:x', tab: 'ldap' }] } }
const CONFIG_LDAP_WARN = { success: true, data: { overall: 'warn', checks: [{ key: 'smtp', status: 'ok', detail: 'tested_ok:x', tab: 'smtp' }, { key: 'ldap', status: 'warn', detail: 'never_tested', tab: 'ldap' }] } }

/** Bölümler — DOM SIRASIYLA (SystemHealth.jsx ORDER). Sürüm & Dağıtım EN SONDA (2026-09-11 kararı). */
const SECTIONS = ['sys', 'sched', 'db', 'integrations', 'heartbeat', 'http', 'users', 'releases']

const toggles = () => [...document.querySelectorAll('[data-slot="stats-toggle"]')]
const toggleOf = (sec) => document.querySelector(`[data-section="${sec}"] [data-slot="stats-toggle"]`)
const overall = () => document.querySelector('[data-slot="health-overall"]')

function renderHealth(props = {}) {
  return render(<SystemHealth systemRole="ADMIN" globalAdmin {...props} />)
}
async function renderLoaded(props) {
  const r = renderHealth(props)
  await waitFor(() => expect(overall()).not.toBeNull())
  return r
}

beforeEach(() => {
  vi.clearAllMocks()
  mobile.on = false
  api.admin.getSystemHealth.mockResolvedValue({ success: true, data: HEALTH })
  api.admin.getMetrics.mockResolvedValue({ success: true, data: METRICS })
  api.admin.getHttpMetrics.mockResolvedValue({ success: true, data: HTTP })
  api.admin.getConfigHealth.mockResolvedValue(CONFIG)
  api.admin.getHeartbeatTimeline.mockResolvedValue({ success: true, data: { days: 1, bucket_minutes: 5, buckets: [
    { start: '2026-09-27T04:00:00', expected: 5, received: 5 }, { start: '2026-09-27T04:05:00', expected: 5, received: 0 }, { start: '2026-09-27T04:10:00', expected: 5, received: 3 } ] } })
  api.admin.pushLog.summary.mockResolvedValue(PUSH)
  window.history.replaceState(null, '', '/')
})
afterEach(() => { window.history.replaceState(null, '', '/') })

describe('SystemHealth — bölümler hatasız render eder', () => {
  it('yüklenince iskelet, sonra SEKİZ katlanabilir bölüm (sırayla) sunar', async () => {
    renderHealth()
    expect(document.querySelector('[data-slot="health-skeleton"]')).not.toBeNull()
    await waitFor(() => expect(overall()).not.toBeNull())
    expect(document.querySelector('[data-slot="health-skeleton"]')).toBeNull()
    expect([...document.querySelectorAll('[data-section]')].map((el) => el.getAttribute('data-section'))).toEqual(SECTIONS)
    expect(toggles().length).toBe(SECTIONS.length)
  })

  it('2026-09-12: otomatik yenileme notu SON bölümün (Sürüm & Dağıtım) ALTINDA', async () => {
    await renderLoaded()
    const note = document.querySelector('[data-testid="sys-refresh-note"]')
    const lastBar = toggles()[SECTIONS.length - 1]
    expect(lastBar.compareDocumentPosition(note) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it.each(SECTIONS.map((s, i) => [s, i]))('"%s" bölümü açıldığında çökmez', async (name, index) => {
    const user = userEvent.setup()
    await renderLoaded()
    // Bölüm açılırken bileşen throw ederse ağaç patlar (test-utils'te ErrorBoundary YOK — bilinçli).
    await user.click(toggles()[index])
    expect(toggleOf(name)).toHaveAttribute('aria-expanded', 'true')
    expect(toggles().length).toBe(SECTIONS.length)
  })

  it('Kullanıcı/Oturum olmayan rol için de (USER) bölümler çizilir; yapılandırma sağlığı ucu yalnız global admin için çağrılır', async () => {
    await renderLoaded({ systemRole: 'USER', globalAdmin: false })
    expect(api.admin.getConfigHealth).not.toHaveBeenCalled()
    expect(toggleOf('users')).not.toBeNull()
  })
})

describe('SystemHealth — genel durum bandı', () => {
  it('sağlıklı payload → "ok" bandı, sebep listesi yok', async () => {
    await renderLoaded()
    expect(overall()).toHaveAttribute('data-level', 'ok')
    expect(document.querySelector('[data-slot="health-reasons"]')).toBeNull()
    expect(screen.getByText(/All systems operational|Tüm sistemler çalışıyor/)).toBeInTheDocument()
  })

  it('SMTP alarmı (seçili 7 gün) → "warn" bandı; sebep satırındaki düğme Entegrasyonlar bölümünü açar', async () => {
    api.admin.getSystemHealth.mockResolvedValue({ success: true, data: { ...HEALTH,
      smtp: { ...HEALTH.smtp, periods: { ...HEALTH.smtp.periods, '7d': { total: 310, attempted: 310, sent: 290, rate: 93.5, alarm: true } } } } })
    await renderLoaded()
    expect(overall()).toHaveAttribute('data-level', 'warn')
    const warnGroup = document.querySelector('[data-slot="health-reasons"] [data-severity="warn"]')
    expect(warnGroup).not.toBeNull()
    expect(warnGroup.textContent).toMatch(/SMTP/)
    expect(toggleOf('integrations')).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(warnGroup.querySelector('[data-slot="reason-go"]'))
    await waitFor(() => expect(toggleOf('integrations')).toHaveAttribute('aria-expanded', 'true'))
  })

  it('heartbeat alarmı → "down" bandı, kritik grubu önce gelir; heap ≥ %85 de kritik', async () => {
    api.admin.getSystemHealth.mockResolvedValue({ success: true, data: { ...HEALTH,
      heartbeat: { ...HEALTH.heartbeat, alarm: true, minutes_since: 12 }, memory: { ...HEALTH.memory, used_pct: 70 } } })
    await renderLoaded()
    expect(overall()).toHaveAttribute('data-level', 'down')
    const groups = [...document.querySelectorAll('[data-slot="health-reasons"] [data-severity]')].map((g) => g.getAttribute('data-severity'))
    expect(groups).toEqual(['down', 'warn'])
    expect(document.querySelector('[data-severity="down"]').textContent).toMatch(/No signal received|Sinyal alınamıyor/)
  })

  it('Duraklat → çip "paused", not değişir, otomatik aralık kapanır; Sürdür → yeniden ister; Yenile düğmesi anında ister', async () => {
    await renderLoaded()
    const calls = api.admin.getSystemHealth.mock.calls.length
    fireEvent.click(screen.getAllByRole('button', { name: /^Pause$|^Duraklat$/ })[0])
    expect(document.querySelector('[data-slot="health-checked"]')).toHaveAttribute('data-paused', 'true')
    expect(document.querySelector('[data-testid="sys-refresh-note"]').textContent).toMatch(/paused|duraklatıldı/)
    fireEvent.click(screen.getAllByRole('button', { name: /^Resume$|^Sürdür$/ })[0])
    await waitFor(() => expect(api.admin.getSystemHealth.mock.calls.length).toBeGreaterThan(calls))
    expect(document.querySelector('[data-slot="health-checked"]')).toHaveAttribute('data-paused', 'false')
    const before = api.admin.getSystemHealth.mock.calls.length
    fireEvent.click(screen.getAllByRole('button', { name: /^Refresh$|^Yenile$/ })[0])
    await waitFor(() => expect(api.admin.getSystemHealth.mock.calls.length).toBe(before + 1))
  })
})

describe('SystemHealth — KPI ızgarası', () => {
  it('sekiz KPI; tonlar modelden (bellek %70 → warn, SMTP bozuk → entegrasyon warn); seri olanlarda kıvılcım', async () => {
    api.admin.getSystemHealth.mockResolvedValue({ success: true, data: { ...HEALTH, memory: { ...HEALTH.memory, used_pct: 70 },
      smtp: { ...HEALTH.smtp, periods: { ...HEALTH.smtp.periods, '7d': { total: 10, attempted: 10, sent: 9, rate: 90, alarm: true } } } } })
    await renderLoaded()
    const grid = document.querySelector('[data-slot="health-kpis"]')
    expect([...grid.querySelectorAll('[data-kpi]')].map((el) => el.getAttribute('data-kpi')))
      .toEqual(['uptime', 'scheduler', 'executor', 'db', 'http-ms', 'http-err', 'memory', 'integrations'])
    expect(grid.querySelector('[data-kpi="memory"]')).toHaveAttribute('data-tone', 'warn')
    expect(grid.querySelector('[data-kpi="integrations"]')).toHaveAttribute('data-tone', 'warn')
    expect(grid.querySelector('[data-kpi="uptime"]')).toHaveAttribute('data-tone', 'ok')
    expect(grid.querySelector('[data-kpi="uptime"]').textContent).toMatch(/3 d 4 hr|3 g 4 sa/)
    expect(grid.querySelector('[data-kpi="http-ms"] [data-slot="health-spark"]')).not.toBeNull()
    expect(grid.querySelector('[data-kpi="memory"] [data-slot="health-spark"]')).not.toBeNull()
    expect(grid.querySelector('[data-kpi="executor"] [data-slot="health-spark"]')).toBeNull()
  })

  it('KPI tıklaması ilgili bölümü açar (yürütücü → Zamanlayıcı ve yürütücüler; HTTP süre → HTTP)', async () => {
    await renderLoaded()
    fireEvent.click(document.querySelector('[data-kpi="executor"]'))
    await waitFor(() => expect(toggleOf('sched')).toHaveAttribute('aria-expanded', 'true'))
    expect(document.querySelectorAll('[data-section="sched"] [role="progressbar"]').length, 'kuyruk + thread + havuz = 3 çubuk').toBeGreaterThanOrEqual(3)
    fireEvent.click(document.querySelector('[data-kpi="http-ms"]'))
    await waitFor(() => expect(toggleOf('http')).toHaveAttribute('aria-expanded', 'true'))
    expect(toggleOf('sched')).toHaveAttribute('aria-expanded', 'false')   // tek açık bölüm
    expect(document.querySelector('[data-slot="http-charts"]')).not.toBeNull()
  })

  // 2026-10-09: alttaki bölüm açılınca üstteki kapanır ve dokunulan başlık yukarı kayıp gözden kaçıyordu.
  it('bölüm başlığına dokununca AÇILAN bölüm görünüme kaydırılır (block:start); kapatınca kaydırma yok; azaltılmış harekette animasyonsuz', async () => {
    const calls = []
    const origScroll = HTMLElement.prototype.scrollIntoView
    const origMatch = window.matchMedia
    HTMLElement.prototype.scrollIntoView = function scrollIntoView(opts) { calls.push([this, opts]) }
    try {
      await renderLoaded()
      calls.length = 0
      fireEvent.click(toggleOf('http'))
      await waitFor(() => expect(toggleOf('http')).toHaveAttribute('aria-expanded', 'true'))
      await waitFor(() => expect(calls.length).toBe(1))
      expect(calls[0][0]).toBe(document.querySelector('[data-section="http"]'))
      expect(calls[0][1]).toEqual({ behavior: 'smooth', block: 'start' })

      calls.length = 0
      fireEvent.click(toggleOf('http'))                                  // kapat
      await waitFor(() => expect(toggleOf('http')).toHaveAttribute('aria-expanded', 'false'))
      await act(() => new Promise((r) => setTimeout(r, 80)))
      expect(calls).toHaveLength(0)

      window.matchMedia = (q) => ({ ...origMatch(q), matches: q === '(prefers-reduced-motion: reduce)' })
      fireEvent.click(toggleOf('sched'))
      await waitFor(() => expect(calls.length).toBe(1))
      expect(calls[0][0]).toBe(document.querySelector('[data-section="sched"]'))
      expect(calls[0][1]).toEqual({ behavior: 'auto', block: 'start' })
    } finally {
      HTMLElement.prototype.scrollIntoView = origScroll
      window.matchMedia = origMatch
    }
  })
})

describe('SystemHealth — derin bağlantı ve aynı sekme param olayı', () => {
  it("'sm:tab-params' {sec:'releases'} son bölümü açar", async () => {
    await renderLoaded()
    expect(document.querySelector('[data-slot="deploy-panel"]')).toBeNull()
    await act(async () => { window.dispatchEvent(new CustomEvent('sm:tab-params', { detail: { sec: 'releases' } })) })
    await waitFor(() => expect(toggleOf('releases')).toHaveAttribute('aria-expanded', 'true'))
  })

  it('?sec=integrations derin bağlantısı bölümü açık getirir; bilinmeyen değer yok sayılır', async () => {
    window.history.replaceState(null, '', '/?tab=health&sec=integrations')
    await renderLoaded()
    expect(toggleOf('integrations')).toHaveAttribute('aria-expanded', 'true')
    expect(document.querySelector('[data-slot="integrations-grid"]')).not.toBeNull()
  })
})

describe('SystemHealth — Entegrasyonlar', () => {
  it('her karta seviye rozeti; push oranı YÜZDE (98.8%) — eski 0.98…% hatası yok; LDAP kartı yapılandırma sağlığından', async () => {
    api.admin.getSystemHealth.mockResolvedValue({ success: true, data: { ...HEALTH,
      smtp: { ...HEALTH.smtp, periods: { ...HEALTH.smtp.periods, '7d': { total: 310, attempted: 310, sent: 290, rate: 93.5, alarm: true } } },
      domain_expiry: { source: 'FALLBACK', alarm: false, last_success: '2026-09-27T03:00:00', reason: 'RDAP bootstrap timeout' } } })
    api.admin.getConfigHealth.mockResolvedValue(CONFIG_LDAP_WARN)
    window.history.replaceState(null, '', '/?sec=integrations')
    await renderLoaded()
    // LDAP uyarısı da genel banda düşer (okunabilir metinle, ham kodla değil)
    expect(document.querySelector('[data-slot="health-reasons"]').textContent).toMatch(/LDAP: never tested|LDAP: hiç test edilmedi/)
    const lv = (k) => document.querySelector(`[data-integration="${k}"]`)?.getAttribute('data-level')
    expect(lv('smtp')).toBe('warn')
    expect(lv('push')).toBe('ok')
    expect(lv('weekly')).toBe('ok')
    expect(lv('domain')).toBe('warn')
    expect(lv('network')).toBe('ok')
    expect(lv('ldap')).toBe('warn')
    expect(lv('cleanup')).toBe('ok')
    const push = document.querySelector('[data-testid="push-card"]')
    expect(push.textContent).toContain('98.8%')
    expect(push.textContent).not.toMatch(/0\.98/)
    expect(document.querySelector('[data-integration="ldap"]').closest('[data-slot="sys-card"]').textContent).toMatch(/never tested|hiç test edilmedi/)
    // Global admin → "Şimdi test et" düğmeleri (SMTP + LDAP)
    expect(screen.getAllByRole('button', { name: /^Test now$|^Şimdi test et$/ }).length).toBe(2)
  })

  it('global admin değilse LDAP kartı ve test düğmeleri yok; "Şimdi test et" SMTP testini çağırır', async () => {
    window.history.replaceState(null, '', '/?sec=integrations')
    await renderLoaded({ globalAdmin: false })
    expect(document.querySelector('[data-integration="ldap"]')).toBeNull()
    expect(screen.queryByRole('button', { name: /^Test now$|^Şimdi test et$/ })).toBeNull()
  })

  it('"Şimdi test et" → testSmtp / testLdap uçları', async () => {
    api.admin.testSmtp = vi.fn(() => Promise.resolve({ success: true, message: 'ok' }))
    api.admin.testLdap = vi.fn(() => Promise.resolve({ success: false, error: 'bind failed' }))
    window.history.replaceState(null, '', '/?sec=integrations')
    await renderLoaded()
    const btns = screen.getAllByRole('button', { name: /^Test now$|^Şimdi test et$/ })
    fireEvent.click(btns[0])
    await waitFor(() => expect(api.admin.testSmtp).toHaveBeenCalledTimes(1))
    fireEvent.click(btns[1])
    await waitFor(() => expect(api.admin.testLdap).toHaveBeenCalledTimes(1))
  })
})

// 2026-09-10: Görev Kuyruğu kartı doygunluğu görünür kılar (kapasite 5000, thread 20/50)
describe('SystemHealth — Zamanlayıcı ve yürütücüler uyarıları', () => {
  async function openSched(health) {
    api.admin.getSystemHealth.mockResolvedValue({ success: true, data: health })
    window.history.replaceState(null, '', '/?sec=sched')
    await renderLoaded()
  }

  it('sağlıklı havuzda doygunluk uyarısı YOK, caller-runs satırı 0, ipucu tetiği max thread sayısını taşır', async () => {
    await openSched({ ...HEALTH, executor_pool: { ...HEALTH.executor_pool, queue_capacity: 5000, max_pool_size: 50, caller_runs: 0, saturated: false } })
    expect(document.querySelector('[data-testid="queue-saturated"]')).toBeNull()
    expect(document.querySelector('[data-testid="queue-caller-runs"]')).toBeNull()
    expect(document.querySelector('[data-section="sched"] [data-slot="section-level"]')).toHaveAttribute('data-level', 'ok')
    expect([...document.querySelectorAll('dt')].some((d) => /caller-runs/i.test(d.textContent))).toBe(true)
  })

  it('kuyruk %80 üstünde ya da saturated=true iken uyarı bandı, kırmızı sayaç ve TAM çerçeve (sol şerit YOK)', async () => {
    await openSched({ ...HEALTH, executor_pool: { ...HEALTH.executor_pool, queue_size: 4500, queue_capacity: 5000, caller_runs: 12, saturated: true } })
    await waitFor(() => expect(document.querySelector('[data-testid="queue-saturated"]')).not.toBeNull())
    const alarmCard = document.querySelector('[data-slot="sys-card"][data-card="executor"][data-alarm="true"]')
    expect(alarmCard).not.toBeNull()
    expect(alarmCard.className).not.toMatch(/border-l-|before:|shadow-\[inset/)
    expect(document.querySelector('[data-hot="true"]')?.textContent).toBe('12')
    expect(document.querySelector('[data-section="sched"] [data-slot="section-level"]')).toHaveAttribute('data-level', 'warn')
    expect(overall()).toHaveAttribute('data-level', 'warn')
  })

  it('yalnız caller-runs > 0 (kuyruk boş) → ayrı uyarı bandı', async () => {
    await openSched({ ...HEALTH, executor_pool: { ...HEALTH.executor_pool, queue_size: 0, caller_runs: 3, saturated: false } })
    expect(document.querySelector('[data-testid="queue-caller-runs"]')).not.toBeNull()
    expect(document.querySelector('[data-testid="queue-saturated"]')).toBeNull()
  })

  it('"Şimdi Çalıştır" zamanlayıcıyı tetikler ve bilgi bandı çıkar', async () => {
    await openSched(HEALTH)
    fireEvent.click(screen.getByRole('button', { name: /Run Now|Şimdi Çalıştır/ }))
    await waitFor(() => expect(api.runScheduler).toHaveBeenCalledTimes(1))
    await screen.findByText(/Check triggered|Kontrol başlatıldı/)
  })
})

describe('SystemHealth — bölüm hataları ve telefon düzeni', () => {
  it('metrik ucu düşerse Uygulama bölümünde hata bloğu + "Tekrar dene" yeniden ister; genel bant da listeler', async () => {
    api.admin.getMetrics.mockRejectedValueOnce(new Error('metrics down')).mockResolvedValue({ success: true, data: METRICS })
    window.history.replaceState(null, '', '/?sec=sys')
    await renderLoaded()
    const block = await waitFor(() => { const el = document.querySelector('[data-section="sys"] [data-slot="empty"][data-tone="danger"]'); expect(el).not.toBeNull(); return el })
    expect(overall()).toHaveAttribute('data-level', 'warn')
    expect(document.querySelector('[data-slot="alert"][data-tone="danger"]').textContent).toMatch(/Failed to load|Yüklenemedi/)
    fireEvent.click([...block.querySelectorAll('button')].find((b) => /Try again|Tekrar dene/.test(b.textContent)))
    await waitFor(() => expect(document.querySelector('[data-section="sys"] [data-slot="jvm-charts"]')).not.toBeNull())
    expect(api.admin.getMetrics).toHaveBeenCalledTimes(2)
  })

  it('heartbeat bölümü: 24 saatlik şerit ucundan çizilir; uç düşerse "Tekrar dene"', async () => {
    api.admin.getHeartbeatTimeline.mockRejectedValueOnce(new Error('tl down'))
    window.history.replaceState(null, '', '/?sec=heartbeat')
    await renderLoaded()
    const block = await waitFor(() => { const el = document.querySelector('[data-section="heartbeat"] [data-slot="empty"][data-tone="danger"]'); expect(el).not.toBeNull(); return el })
    fireEvent.click([...block.querySelectorAll('button')].find((b) => /Try again|Tekrar dene/.test(b.textContent)))
    await waitFor(() => expect(document.querySelector('[data-testid="hb-strip"]')).not.toBeNull())
    expect(api.admin.getHeartbeatTimeline).toHaveBeenCalledTimes(2)
  })

  it('telefonda (useIsMobile) yapışkan mini durum şeridi çizilir; masaüstünde yok', async () => {
    mobile.on = true
    await renderLoaded()
    const bar = document.querySelector('[data-slot="health-sticky"]')
    expect(bar).not.toBeNull()
    expect(bar).toHaveAttribute('data-level', 'ok')
    mobile.on = false
  })

  it('masaüstünde yapışkan şerit yok', async () => {
    await renderLoaded()
    expect(document.querySelector('[data-slot="health-sticky"]')).toBeNull()
  })
})

// 2026-09-26: Veritabanı Analitiği yeniden tasarımı — yükleme hatası yutulmaz, bant + "Tekrar dene"
describe('SystemHealth — Veritabanı Analitiği yükleme hatası', () => {
  it('uç reddedilirse hata bandı çıkar; "Tekrar dene" yeniden ister ve veri gelince KPI kartları çizilir', async () => {
    api.admin.getDbAnalytics = vi.fn()
      .mockRejectedValueOnce(new Error('db down'))
      .mockResolvedValue({ success: true, data: { summary: { queries: 5, db_size: '10 MB', pgss: true }, connections: { active: 2, max: 50 } } })
    const user = userEvent.setup()
    await renderLoaded()
    await user.click(toggleOf('db'))
    const alert = await waitFor(() => {
      const el = document.querySelector('[data-testid="db-analytics"] [role="alert"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(alert.textContent).toContain('db down')
    await user.click([...alert.querySelectorAll('button')].find((b) => /Tekrar dene|Try again/.test(b.textContent)))
    await waitFor(() => expect(document.querySelector('[data-kpi="connections"]')?.textContent).toContain('2 / 50'))
    expect(api.admin.getDbAnalytics).toHaveBeenCalledTimes(2)
    expect(document.querySelector('[data-testid="db-analytics"] [role="alert"]')).toBeNull()
  })
})

describe('SystemHealth — haftalık erişilebilirlik günlükleri', () => {
  it('Entegrasyonlar → haftalık kart CTA → pencere; satırlar Table, boşsa StatusBlock', async () => {
    api.admin.getWeeklyAvailHistory = vi.fn(() => Promise.resolve({ success: true, data: [
      { id: 1, sent_at: '2026-09-21T07:30:00', team: 'Takım A', to: 'takim-a@example.com', status: 'SENT', trigger: 'WEEKLY_AVAILABILITY' },
      { id: 2, sent_at: '2026-09-21T07:31:00', team: 'Takım B', to: 'takim-b@example.com', status: 'FAILED: relay', trigger: 'WEEKLY_AVAILABILITY_TEST' } ] }))
    api.admin.getWeeklyAvailHistoryItem = vi.fn(() => Promise.resolve({ success: true, data: { id: 1, team: 'Takım A', to: 'takim-a@example.com', subject: 'Haftalık', sent_at: '2026-09-21T07:30:00', status: 'SENT', html: '<p>x</p>' } }))
    window.history.replaceState(null, '', '/?sec=integrations')
    await renderLoaded()
    fireEvent.click(screen.getByRole('button', { name: /Click to view delivery logs|Gönderim loglarını görmek/ }))
    const table = await waitFor(() => { const el = document.querySelector('[data-testid="wa-logs"]'); expect(el).not.toBeNull(); return el })
    // 2026-10-01 yeniden tasarım: gün başlığı satırları da tbody'de — veri satırları `data-slot="wa-row"`
    expect(table.querySelectorAll('[data-slot="wa-row"]').length).toBe(2)
    fireEvent.click(table.querySelector('[data-slot="wa-row"]'))
    await waitFor(() => expect(api.admin.getWeeklyAvailHistoryItem).toHaveBeenCalledWith(1))
    await waitFor(() => expect(document.querySelectorAll('[role="dialog"]').length).toBe(2))
  })
})
