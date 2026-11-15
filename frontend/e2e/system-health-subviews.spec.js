// Sistem Sağlığı alt görünümleri (2026-11-15, prod kapısı e2e paketi) — gerçek uygulama yolu (`?tab=health&sec=…` /
// `view=smtp`), API mock'lu. Üç boyutta (390 dokunmatik, 768 dokunmatik, 1280) işlevsel sonuç + yerleşim:
//   - sec=releases: kayıtlar görünümünde ek pod satırı "Extra pod" rozetiyle (nötr, data-replica); elle kayıt ekleme ve
//     MANUAL kaydı silme YALNIZ global yöneticide (silme DELETE isteği atar); kapsamlı müdürde (izin olsa da) gizli;
//   - sec=db: pg_stat_statements açıkken kaynak rozeti + DB geneli kaynak; kapalıyken yedek bandı + "nasıl açılır" adımları;
//     pencere değişimi `days=30` ile yeniden ister;
//   - sec=http: özet kutucukları + en yavaş / hatalı uç listeleri; satır İstek Gezgini'ni o uca SÜZÜLÜ açar (`endpoint=`);
//   - view=smtp: KPI süzgeci `status=FAILED` ile yeniden arar; yeniden gönderim RECIPIENTS_TRUNCATED /
//     SUMMARY_NOT_RESENDABLE metinleriyle reddedilir; kapsamlı müdürde yeniden gönder düğmesi yok, "yalnız yönetici" notu var.
// Yerleşim: sayfa yatay kaymaz, öğe ekran dışına çıkmaz, metin kırpılmaz, dokunmatikte hedefler ≥ 40 px (layoutAudit).
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'
import { VIEWPORTS, json, pathIs, checkLayout, recordRequests } from './support/layoutAudit.js'

const NOW = Date.now()
const fmt = (ms) => new Date(ms).toISOString().slice(0, 19)
const DAY = 86_400_000
const ago = (days) => `${new Date(NOW - days * DAY).toISOString().slice(0, 19)}Z`
const POD = 'site-monitor-production-deployment-7f9c8d6b5a-xk2lp'

/** Sistem Sağlığı ana yükü — bölümler çizilebilsin diye yeterince dolu (küme kartı testinden kısaltılmış). */
const HEALTH = {
  served_by: `${POD}-1a2b3c4d`, served_by_pod: POD,
  scheduler: { running: false, running_here: false, last_run: fmt(NOW - 40 * 60_000), next_run: fmt(NOW + 20 * 60_000), active_domains: 412, status_source: 'cluster' },
  build: { version: '20.208.0', commit: 'a1b2c3d', environment: 'prod', image_version: '20.208.0', helm_revision: 88, started_at: fmt(NOW - 3 * DAY), uptime_seconds: 3 * 86_400 },
  lock: { held: false },
  pool: { active: 3, idle: 7, total: 10, waiting: 0, max_size: 25 },
  executor_pool: { queue_size: 12, queue_capacity: 5000, active_count: 4, pool_size: 20, core_pool_size: 20, max_pool_size: 50, completed_tasks: 84213, caller_runs: 0, saturated: false },
  memory: { used_mb: 812, free_mb: 236, total_mb: 1048, max_mb: 2048, used_pct: 39 },
  scan: { last_run: fmt(NOW - 40 * 60_000), last_run_kind: 'hourly', duration_ms: 42_150, total: 412, warnings: 3, errors: 1, source: 'cluster' },
  scan_alarm: false,
  db_ms: 14,
  smtp: { total: 310, attempted: 310, sent: 300, rate: 96.8, alarm: false, periods: { '7d': { total: 310, attempted: 310, sent: 300, rate: 96.8, alarm: false } } },
  heartbeat: { last_heartbeat: fmt(NOW - 30_000), minutes_since: 0, alarm: false, recent: [fmt(NOW - 30_000)] },
  network: { alarm: false, last_error_rate: 0.01, last_network_errors: 1, last_total: 412, threshold: 0.5, min_errors: 3 },
}

// ── Sürüm & Dağıtım ───────────────────────────────────────────────────────────────────────────────────────────────
const ROW = (id, over = {}) => ({
  id, startedAt: ago(2), recordedAt: ago(2), readyAt: null, lastSeenAt: null, endedAt: null, endReason: null,
  environment: 'prod', version: '20.208.0', previousVersion: '20.207.0', kind: 'UPGRADE', source: 'STARTUP',
  commit: 'abcdef0123456789', commitShort: 'abcdef01', helm: { release: 'site-monitor', revision: 88 },
  instanceId: 'i1', hostname: 'h1', pod: `${POD}-${id}`, node: 'aks-nodepool-cok-uzun-dugum-adi-vmss000002', createdBy: null, note: null,
  current: false, releasedAt: ago(2.1), leadTimeSeconds: 7200, ...over,
})
const RECORDS = [
  ROW(3, { current: true }),
  ROW(9, { kind: 'RESTART', previousVersion: '20.208.0', startedAt: ago(1.5), replica: true }),
  ROW(8, { kind: 'RESTART', previousVersion: '20.208.0', startedAt: ago(1.8) }),
  ROW(2, { source: 'MANUAL', kind: 'UNKNOWN', version: '20.150.0', previousVersion: null, startedAt: ago(30), pod: null, hostname: null,
    note: 'Bakım penceresinde elle kurulum — değişiklik kaydı CHG-2026-0042', createdBy: 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin' }),
]
const TIMELINE = {
  environment: 'prod',
  current: { version: '20.208.0', since: ago(2), kind: 'UPGRADE', previousVersion: '20.207.0', restartsSince: 1 },
  transitions: [ROW(3, { current: true }), ROW(1, { version: '20.207.0', previousVersion: null, kind: 'FIRST_SEEN', startedAt: ago(20) })],
  restartCount: 1, unknownCount: 0, total: 4,
  summary: { deploymentsLast30d: 2, restartsLast7d: 1, rollbacks: 0, avgReleaseLagSeconds: 5400, skippedReleases: 3 },
  environments: ['prod', 'staging'], backfillCandidates: 0,
}
const VERSION = { version: '20.208.0', commit: 'abcdef0123456789', commitShort: 'abcdef01', environment: 'prod', mismatch: false,
  imageRef: 'registry.example.com/site-monitor:20.208.0', helm: { release: 'site-monitor', revision: 88 }, instance: { pod: POD, node: 'node-1' },
  uptimeSeconds: 600, live: TIMELINE.current, release: { version: '20.208.0', releasedAt: ago(2.1), bump: 'minor', breaking: false, highlights: [] },
  releaseLagSeconds: 7200, releaseIndex: { loaded: true } }

// ── Veritabanı analitiği ──────────────────────────────────────────────────────────────────────────────────────────
const SQL_A = 'SELECT id, domain FROM certificate_inventory WHERE active = true ORDER BY expiry_date'
const SQL_B = 'UPDATE http_monitor SET last_status = $1 WHERE id = $2'
const TABLES = Array.from({ length: 12 }, (_, i) => ({
  table_name: i === 2 ? 'monitor_check_hourly_rollup_with_a_very_long_table_name' : `table_${String(i).padStart(2, '0')}`,
  schema_name: 'public', row_count: 100_000 - i * 1000, table_size: `${300 - i} MB`, total_size: `${400 - i} MB`,
  table_size_bytes: (300 - i) * 1_048_576, total_size_bytes: (400 - i) * 1_048_576, dead_rows: 10, dead_pct: 0.1,
  index_size: '10 MB', index_size_bytes: 10_485_760, seq_scan: 5, idx_scan: 95, idx_scan_pct: 95, reads: 100 + i, writes: 3,
  last_vacuum: '2026-11-10T02:00:00', last_analyze: '2026-11-10T02:00:00', mod_since_analyze: 12,
}))
const dbData = (pgss) => ({
  truncated: false, row_limit: 50000, generated_at: fmt(NOW - 60_000),
  summary: { queries: 1284, failed: 7, success_rate: 99.5, avg_ms: 42, max_ms: 3810, p95_ms: 180, user_count: 4, active_connections: 14,
    db_size: '1843 MB', table_count: TABLES.length, pgss, days: 7 },
  connections: { active: 16, max: 100, db_size: '1843 MB', response_ms: 3,
    states: [{ state: 'idle', count: 10 }, { state: 'active', count: 3 }, { state: 'idle in transaction', count: 2 }],
    idle_in_tx: 2, long_queries: 1, lock_waits: 0, longest_query_s: 75, longest_xact_s: 400, long_threshold_s: 60 },
  db_stats: { cache_hit_pct: 97.5, blks_hit: 975, blks_read: 25, xact_commit: 95, xact_rollback: 5, rollback_pct: 5, deadlocks: 0,
    temp_files: 2, temp_bytes: 2048, temp_size: '2048 bytes', stats_reset: '2026-11-01T00:00:00' },
  table_sizes: TABLES,
  top_tables: [{ table_name: 'audit_log', reads: 5_000, writes: 900, row_count: 930 }],
  top_sql: [{ sql: SQL_A, calls: 98000, avg_ms: 1.2, max_ms: 40, total_ms: 118000, rows: 5000 }, { sql: SQL_B, calls: 84000, avg_ms: 4.3, max_ms: 160, total_ms: 99000, rows: 800 }],
  slowest_sql: [{ sql: SQL_B, calls: 40, avg_ms: 3810, max_ms: 5200, total_ms: 150000, rows: 12 }],
  recent_queries: [{ time: fmt(NOW - 3_600_000), username: 'admin', sql: SQL_A, duration_ms: 12, success: true, rows: 3 }],
  failed: [{ time: fmt(NOW - 7_200_000), username: 'ops.user', sql: SQL_B, error: 'ERROR: canceling statement due to statement timeout' }],
  top_users: [{ username: 'admin', queries: 400, avg_ms: 30, failed: 0, last: fmt(NOW - 3_600_000) }],
  series: [
    { ts: fmt(NOW - 2 * DAY), count: 0, avg_ms: 0, failed: 0 },
    { ts: fmt(NOW - DAY), count: 120, avg_ms: 38, failed: 1 },
    { ts: fmt(NOW), count: 60, avg_ms: 420, failed: 6 },
  ],
})

// ── HTTP istekleri ────────────────────────────────────────────────────────────────────────────────────────────────
const SLOW_EP = 'GET /api/monitoring/pagespeed/{id}/resources/export'
const ERR_EP = 'POST /api/auth/login'
const EP = (endpoint, extra = {}) => ({
  endpoint, method: endpoint.split(' ')[0], path: endpoint.split(' ')[1], count: 100, errors: 0, error_rate_pct: 0,
  avg_ms: 40, max_ms: 200, p50_ms: 30, p95_ms: 90, p99_ms: 150, status_2xx: 100, status_3xx: 0, status_4xx: 0, status_5xx: 0,
  status_other: 0, unclassified: 0, last_seen: fmt(NOW - 60_000), ...extra,
})
const HTTP = {
  summary: { total_requests: 184210, total_errors: 1473, error_rate_pct: 0.8, avg_ms: 142, max_ms: 6120, p95_ms: 480, p99_ms: 2100, req_per_min: 128, peak_req_per_min: 410 },
  served_by: `${POD}-1a2b3c4d`, served_by_pod: POD,
  history: Array.from({ length: 24 }, (_, i) => ({ ts: fmt(NOW - (24 - i) * 3_600_000), count: 500 + i * 10, avg_ms: 100 + i, p95_ms: 300 + i, errors: i % 5 === 0 ? 3 : 0 })),
  top_endpoints: {
    slowest: [EP(SLOW_EP, { p95_ms: 5200, avg_ms: 2400, max_ms: 6120 }), EP('GET /api/certificates/list', { p95_ms: 900 })],
    errors: [EP(ERR_EP, { count: 118, errors: 13, error_rate_pct: 11.0, status_4xx: 10, status_5xx: 3 })],
  },
}
const OVERVIEW_POINTS = Array.from({ length: 5 }, (_, i) => ({
  ts: fmt(NOW - (5 - i) * 60_000), t: NOW - (5 - i) * 60_000, count: 300, errors: 3, avg_ms: 60, p50_ms: 40, p95_ms: 1500, p99_ms: 2500,
  max_ms: 4100, status_2xx: 290, status_3xx: 0, status_4xx: 7, status_5xx: 3, status_other: 0, unclassified: 0,
}))
const HTTP_OVERVIEW = {
  from: fmt(NOW - DAY), to: fmt(NOW), clamped: false, capped: false, granularity: 'hour',
  summary: { total: 1500, errors: 92, error_rate_pct: 6.1, avg_ms: 60, max_ms: 4100, min_ms: 2, p50_ms: 40, p95_ms: 1500, p99_ms: 2500,
    req_per_min: 25, status_2xx: 1408, status_3xx: 0, status_4xx: 70, status_5xx: 22, status_other: 0, unclassified: 0 },
  status_codes: [{ code: 200, count: 1400 }, { code: 401, count: 70 }, { code: 500, count: 22 }],
  data: OVERVIEW_POINTS, endpoints: [EP(SLOW_EP, { p95_ms: 5200 }), EP(ERR_EP)], endpoints_total: 2, endpoints_truncated: false,
}

// ── SMTP gönderim logu ────────────────────────────────────────────────────────────────────────────────────────────
const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi'
const SMTP_SUMMARY = {
  from: fmt(NOW - 7 * DAY), to: null, granularity: 'day',
  kpi: { total: 6, sent: 2, failed: 3, skipped: 1, queued: 0, success_rate: 40.0, last_sent_at: fmt(NOW - 3_600_000), last_failed_at: fmt(NOW - 1_800_000), failed_recipients: 3 },
  timeline: [{ bucket: fmt(NOW - DAY).slice(0, 10), sent: 1, failed: 0, skipped: 0 }, { bucket: fmt(NOW).slice(0, 10), sent: 1, failed: 3, skipped: 1 }],
  teams: [{ team_id: 2, team_name: LONG_TEAM, total: 3, sent: 1, failed: 1, skipped: 1, success_rate: 50.0 }, { team_id: 1, team_name: 'Takım A', total: 2, sent: 1, failed: 1, skipped: 0, success_rate: 50.0 }],
  error_classes: [{ error_class: 'RECIPIENT', count: 1 }, { error_class: 'TIMEOUT', count: 1 }, { error_class: 'AUTH', count: 1 }],
  triggers: [{ trigger: 'INITIAL', count: 2 }, { trigger: 'TECH_ERROR_REMINDER', count: 1 }],
  top_recipients: [{ recipient_email: 'ops@example.com', recipient_name: 'Operasyon Ekibi Ortak Posta Kutusu', total: 2, failed: 1, last_at: fmt(NOW - 3_600_000), last_failed_at: fmt(NOW - 3_600_000) }],
  top_domains: [{ domain: 'odeme-servisleri-yedek-bolge-2.example.com', team_id: 1, team_name: 'Takım A', total: 2, failed: 1, last_failed_at: fmt(NOW - 3_600_000) }],
}
const SMTP_ROWS = [
  { id: 21, sent_at: fmt(NOW - 1_800_000), recipient_name: 'Takım listesi', recipient_email: 'a@example.com, b@example.com … +14', subject: '[CRITICAL] odeme.example.com erişilemiyor',
    kind: 'FAILED', error: '550 5.1.1 mailbox unavailable', error_class: 'RECIPIENT', trigger: 'INITIAL', team_id: 1, team_name: 'Takım A', domain: 'odeme.example.com' },
  { id: 22, sent_at: fmt(NOW - 2_400_000), recipient_name: 'Dijital Kanallar', recipient_email: 'dijital@example.com', subject: 'Teknik hata raporu hatırlatması — Ekim 2026',
    kind: 'FAILED', error: 'Read timed out', error_class: 'TIMEOUT', trigger: 'TECH_ERROR_REMINDER', team_id: 2, team_name: LONG_TEAM, domain: null },
  { id: 23, sent_at: fmt(NOW - 3_600_000), recipient_name: 'Ops', recipient_email: 'ops@example.com', subject: '[CRITICAL] a.example.com down',
    kind: 'SENT', error: '', error_class: null, trigger: 'INITIAL', team_id: 1, team_name: 'Takım A', domain: 'a.example.com' },
]

const PERMS_ADMIN = {
  'release_history.read': { view: true }, 'release_history.edit': { view: true, edit: true },
  'system_health.read': { view: true }, 'system_health.actions': { view: true, execute: true },
}

/**
 * mockApi + bu dosyanın yolları (mockApi'den SONRA kaydedilen yollar önceliklidir). `who`: 'global' | 'scoped'.
 * Dönüş: istek kaydı + tek tek sayaçlar.
 */
async function mock(page, { who = 'global', pgss = true } = {}) {
  await mockApi(page, { role: 'ADMIN', globalAdmin: who === 'global', perms: PERMS_ADMIN })
  const calls = { deletes: [], resends: [] }
  await page.route(pathIs('/api/admin/system'), json({ success: true, data: HEALTH }))
  await page.route(pathIs('/api/admin/system/metrics'), json({ success: true, data: [], served_by_pod: POD }))
  await page.route(pathIs('/api/admin/system/http-metrics'), json({ success: true, data: HTTP }))
  await page.route(pathIs('/api/admin/system/http-metrics/overview'), json({ success: true, data: HTTP_OVERVIEW }))
  await page.route(pathIs('/api/admin/system/db-analytics'), json({ success: true, data: dbData(pgss) }))
  await page.route(pathIs('/api/system/version'), json({ success: true, data: VERSION }))
  await page.route(pathIs('/api/admin/deployments/timeline'), json({ success: true, data: TIMELINE }))
  await page.route(pathIs('/api/admin/deployments'), json({ success: true, data: RECORDS, total: RECORDS.length, page: 1, size: 25, total_pages: 1, environments: ['prod', 'staging'] }))
  await page.route((u) => /^\/api\/admin\/deployments\/\d+$/.test(new URL(u).pathname), (route) => {
    if (route.request().method() === 'DELETE') calls.deletes.push(new URL(route.request().url()).pathname)
    return json({ success: true })(route)
  })
  await page.route(pathIs('/api/admin/smtp-log/summary'), json({ success: true, data: SMTP_SUMMARY }))
  await page.route(pathIs('/api/admin/smtp-log/search'), (route) => {
    const status = new URL(route.request().url()).searchParams.get('status')
    const rows = status ? SMTP_ROWS.filter((r) => r.kind === status) : SMTP_ROWS
    return json({ success: true, data: rows, total: rows.length, page: 0, size: 25 })(route)
  })
  await page.route((u) => /^\/api\/admin\/smtp-log\/\d+\/resend$/.test(new URL(u).pathname), (route) => {
    const id = Number(new URL(route.request().url()).pathname.split('/')[4])
    calls.resends.push(id)
    // Sunucu sözleşmesi (SmtpLogController.resend): reddedilen yeniden gönderim 409 + `error` = kod
    const error = id === 21 ? 'RECIPIENTS_TRUNCATED' : 'SUMMARY_NOT_RESENDABLE'
    return json({ success: false, error }, 409)(route)
  })
  return calls
}

/**
 * Yerleşim denetiminin BİLİNÇLİ istisnaları (tasarım; liste yalnız küçülür) — 2026-11-15 prod kapısı raporundaki ürün
 * hataları (pod adı, kısa not, HTTP kutucukları, SMTP takım rozeti ve dokunma hedefleri, İstek Gezgini göstergesi / uç
 * seçicisi / süzgeç çipi) düzeltildi, artık ölçülür:
 *  - SMTP tablosu: konu / alan adı hücresi tek satır (`truncate` + `title`), tam metin satır ayrıntısında.
 *  - SMTP tablosu alıcı adresi `max-w-[160px] truncate` + `title` (tam liste satır ayrıntısında).
 */
const KNOWN_SMTP_CLIPS = [/^td\[table-cell\][^@]*@table-container>table>table-body>table-row>table-cell \[own box\]/,
  /div\.max-w-\[160px\]\.truncate/]

/** Sistem Sağlığı yüklendi (bölüm kabuğu çizildi). */
async function ready(page) {
  await expect(page.locator('[data-slot="system-health"]')).toBeVisible({ timeout: 30_000 })
}

for (const vp of VIEWPORTS) {
  test.describe(`System Health sub-views — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('releases: extra pod badge, manual add + delete (global admin) sends DELETE; layout', async ({ page }) => {
      test.setTimeout(120_000)
      const calls = await mock(page, { who: 'global' })
      await page.goto('/?tab=health&sec=releases&d_view=table')
      await ready(page)
      const sec = page.locator('[data-section="releases"]')
      const panel = sec.locator('[data-slot="deploy-panel"]')
      await expect(panel).toBeVisible({ timeout: 20_000 })
      // Kayıtlar görünümü: dar kapta kart, geniş kapta tablo
      if (vp.width <= 390) await expect(panel.locator('[data-slot="deploy-record-cards"]')).toBeVisible({ timeout: 15_000 })
      if (vp.width >= 1280) await expect(panel.locator('[data-testid="deploy-table"]')).toBeVisible({ timeout: 15_000 })
      // Ek pod rozeti: nötr "Extra pod", data-replica; aynı sürümün sıradan yeniden başlatması "Restart"
      const replica = panel.locator('[data-slot="badge"][data-replica="true"]')
      await expect(replica).toHaveCount(1)
      await expect(replica).toHaveText(/Extra pod|Ek pod/)
      await expect(replica).toHaveAttribute('data-kind', 'RESTART')
      await expect(panel.locator('[data-slot="badge"][data-kind="RESTART"]:not([data-replica])')).toHaveText(/^\s*Restart\s*$|Yeniden başlatma/)
      // Yazma eylemleri: global yöneticide elle kayıt + yalnız MANUAL satırda silme
      await expect(panel.getByRole('button', { name: /Add manual record|Elle kayıt ekle/ })).toBeVisible()
      await expect(panel.locator('[data-action="delete"]')).toHaveCount(1)
      await page.waitForTimeout(300)
      await checkLayout(page, `releases@${vp.name}`, '[data-section="releases"]')

      await panel.locator('[data-action="delete"]').click()
      const dlg = page.getByRole('dialog')
      await expect(dlg).toBeVisible()
      await expect(dlg).toContainText('20.150.0')
      await dlg.getByRole('button', { name: /^(Delete|Sil)$/ }).click()
      await expect.poll(() => calls.deletes).toEqual(['/api/admin/deployments/2'])
      await expect(page.getByText(/Record deleted|Kayıt silindi/)).toBeVisible()
    })

    test('releases: scoped admin with release_history.edit sees no manual add / delete', async ({ page }) => {
      test.setTimeout(90_000)
      await mock(page, { who: 'scoped' })
      await page.goto('/?tab=health&sec=releases&d_view=table')
      await ready(page)
      const panel = page.locator('[data-section="releases"] [data-slot="deploy-panel"]')
      await expect(panel.locator('[data-slot="badge"][data-replica="true"]')).toHaveCount(1, { timeout: 20_000 })
      await expect(panel.getByRole('button', { name: /Add manual record|Elle kayıt ekle/ })).toHaveCount(0)
      await expect(panel.getByRole('button', { name: /Backfill|geri doldur/i })).toHaveCount(0)
      await expect(panel.locator('[data-action="delete"]')).toHaveCount(0)
    })

    test('db: pg_stat_statements on → source badge + DB-wide source; window change asks days=30; layout', async ({ page }) => {
      test.setTimeout(120_000)
      const log = recordRequests(page)
      await mock(page, { pgss: true })
      await page.goto('/?tab=health&sec=db')
      await ready(page)
      const sec = page.locator('[data-section="db"]')
      await expect(sec.locator('[data-testid="db-source"]')).toHaveAttribute('data-pgss', 'true', { timeout: 20_000 })
      await expect(sec.locator('[data-testid="db-source"]')).toContainText(/pg_stat_statements on|pg_stat_statements açık/)
      await expect(sec.getByText(/pg_stat_statements isn't enabled|pg_stat_statements etkin değil/)).toHaveCount(0)
      await expect.poll(() => log.filter((r) => r.path === '/api/admin/system/db-analytics').map((r) => r.search)).toContain('?days=7')
      await page.waitForTimeout(300)
      await checkLayout(page, `db pgss@${vp.name}`, '[data-section="db"]')
      await sec.locator('[data-slot="db-header"]').getByRole('button', { name: /^(30 Days|30 Gün)$/ }).click()
      await expect.poll(() => log.filter((r) => r.path === '/api/admin/system/db-analytics').map((r) => r.search)).toContain('?days=30')
    })

    test('db: pg_stat_statements off → fallback band + steps to turn it on; layout', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page, { pgss: false })
      await page.goto('/?tab=health&sec=db')
      await ready(page)
      const sec = page.locator('[data-section="db"]')
      await expect(sec.locator('[data-testid="db-source"]')).toHaveAttribute('data-pgss', 'false', { timeout: 20_000 })
      await expect(sec.locator('[data-testid="db-source"]')).toContainText(/Playground history only|Yalnız Playground/)
      await sec.getByRole('button', { name: /How do I turn it on\?|Nasıl açılır/ }).click()
      const steps = sec.locator('[data-testid="db-pgss-steps"]')
      await expect(steps).toBeVisible()
      await expect(steps).toContainText("shared_preload_libraries = 'pg_stat_statements'")
      await expect(steps).toContainText('CREATE EXTENSION IF NOT EXISTS pg_stat_statements;')
      await page.waitForTimeout(300)
      await checkLayout(page, `db fallback@${vp.name}`, '[data-section="db"]')
    })

    test('http: tiles + top lists; slowest row opens the explorer filtered to that endpoint; layout', async ({ page }) => {
      test.setTimeout(120_000)
      const log = recordRequests(page)
      await mock(page)
      await page.goto('/?tab=health&sec=http')
      await ready(page)
      const sec = page.locator('[data-section="http"]')
      await expect(sec.locator('[data-slot="hreq-tiles"] [data-slot="hreq-tile"]')).toHaveCount(6, { timeout: 20_000 })
      await expect(sec.locator('[data-slot="hreq-top-errors"] [data-slot="hreq-top-row"]')).toHaveCount(1)
      await expect(sec.locator('[data-slot="hreq-top-slowest"] [data-slot="hreq-top-row"]')).toHaveCount(2)
      await page.waitForTimeout(300)
      await checkLayout(page, `http@${vp.name}`, '[data-section="http"]')

      await sec.locator(`[data-slot="hreq-top-slowest"] [data-slot="hreq-top-row"][data-endpoint="${SLOW_EP}"]`).click()
      const dlg = page.getByRole('dialog', { name: /Request Explorer|İstek Gezgini/ })
      await expect(dlg).toBeVisible({ timeout: 15_000 })
      await expect.poll(() => log.filter((r) => r.path === '/api/admin/system/http-metrics/overview')
        .map((r) => new URLSearchParams(r.search).get('endpoint'))).toContain(SLOW_EP)
      await expect(dlg.locator('[data-slot="hreq-tile"]').first()).toBeVisible({ timeout: 15_000 })
      await page.waitForTimeout(400)
      await checkLayout(page, `http explorer@${vp.name}`, '[role="dialog"]')
    })

    test('smtp: KPI filter re-searches with status=FAILED; resend refused with RECIPIENTS_TRUNCATED / SUMMARY_NOT_RESENDABLE text; layout', async ({ page }) => {
      test.setTimeout(120_000)
      const log = recordRequests(page)
      const calls = await mock(page, { who: 'global' })
      await page.goto('/?tab=health&view=smtp')
      await ready(page)
      const view = page.locator('[data-slot="system-health"]')
      await expect(view.locator('[data-testid="sml-table"] tbody tr')).toHaveCount(3, { timeout: 20_000 })
      await page.waitForTimeout(300)
      await checkLayout(page, `smtp@${vp.name}`, '[data-slot="system-health"]', { allowClip: KNOWN_SMTP_CLIPS })

      // KPI "Failed" kutucuğu = durum süzgeci → status=FAILED ile yeniden arama, 2 satır
      await view.getByRole('button', { name: /^3\s*(Failed|Başarısız)$/ }).click()
      await expect.poll(() => log.filter((r) => r.path === '/api/admin/smtp-log/search')
        .map((r) => new URLSearchParams(r.search).get('status'))).toContain('FAILED')
      await expect(view.locator('[data-testid="sml-table"] tbody tr')).toHaveCount(2)

      // Yeniden gönderim: kırpılmış alıcı listesi → 409 RECIPIENTS_TRUNCATED, kullanıcıya açıklayıcı metin
      const resendBtns = view.locator('[data-testid="sml-table"] [data-action="resend"]')
      await expect(resendBtns).toHaveCount(2)
      await resendBtns.nth(0).click()
      await page.getByRole('dialog').getByRole('button', { name: /^(Resend|Yeniden gönder)$/ }).click()
      await expect(page.getByText(/recipient list was shortened to fit the record|Alıcı listesi kayda sığmadığı için kısaltılmış/)).toBeVisible()
      await resendBtns.nth(1).click()
      await page.getByRole('dialog').getByRole('button', { name: /^(Resend|Yeniden gönder)$/ }).click()
      await expect(page.getByText(/holds a reminder's summary, not the mail body|hatırlatmanın özetini taşıyor/)).toBeVisible()
      expect(calls.resends).toEqual([21, 22])
    })

    test('smtp: scoped admin gets no resend button, only the admin-only note', async ({ page }) => {
      test.setTimeout(90_000)
      const calls = await mock(page, { who: 'scoped' })
      await page.goto('/?tab=health&view=smtp')
      await ready(page)
      const view = page.locator('[data-slot="system-health"]')
      await expect(view.locator('[data-testid="sml-table"] tbody tr')).toHaveCount(3, { timeout: 20_000 })
      await expect(view.locator('[data-action="resend"]')).toHaveCount(0)
      await expect(view.getByText(/Resending is available to administrators only|Yeniden gönderim yalnız/)).toBeVisible()
      expect(calls.resends).toEqual([])
    })
  })
}
