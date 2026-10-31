// Sentetik izleme (k6) — koşum ayrıntısı + kartlar + şablon kartı (2026-10-31, k6r fe2: D4 + B4 fe2 maddeleri). Üç boyutta
// (390 telefon + dokunmatik, 768 tablet + dokunmatik, 1280 dizüstü) gerçek tarayıcıda:
//  - kart: 60+ karakterlik AD takım adı ve uzun grup adı KESİLMEZ, kart dışına taşmaz (O8 — dokuz türün ortak meta satırı);
//    yarıda kalan koşum sayacı nötr (O5); duraklatılmış kart sonuç paneli nötr (D6); p95 alt satırda;
//  - ayrıntı: geçmişte tıklanan satırın HEMEN ALTINDA satır içi ayrıntı (adımlar, eşikler, bağlam çipleri, TLS, hata sınıfı);
//    taşma / kırpma yok, dokunma hedefleri ≥ 40 px; "bu sürüm bozdu" bandı → önceki sürüm editöre yüklenir;
//  - yoğunluk şeridinde hatalı saat KIRMIZI tonda (O7); Grafik sekmesinde adım trendi;
//  - şablon kartı: uzun köken / takım rozeti kartın içinde sarılır (O8), görünür "Bu şablonla başla".
// API tamamen mock'lu (yalnız /api/ yolu). Değerler uydurmadır.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'
const LONG_GROUP = 'Bireysel Bankacılık Giriş Akışları — Mobil ve İnternet Şubesi Ortak Senaryoları'
const pad = (n) => String(n).padStart(2, '0')
/** Sunucu biçimi: UTC, `Z`siz ISO (formatDateSec yerele çevirir). */
const iso = (msAgo) => new Date(Date.now() - msAgo).toISOString().slice(0, 19)
const MIN = 60_000
const HOUR = 3_600_000

const SCRIPT = "import http from 'k6/http';\nimport { check, group } from 'k6';\nexport default function () {\n  group('Giriş', () => { check(http.get(__ENV.BASE_URL), { 'durum 200': (r) => r.status === 200 }) })\n}\n"
const STEPS = [
  { name: 'Giriş sayfası', duration_ms: 812, requests: 2, failed_requests: 0, p95_ms: 410, checks_passed: 2, checks_failed: 0, error_code: null },
  { name: '', duration_ms: null, requests: 1, failed_requests: 0, p95_ms: 90, checks_passed: 0, checks_failed: 0, error_code: null },
  { name: 'Giriş sayfası › Kurumsal OIDC token alma ve tek kullanımlık doğrulama kodu (OTP) adımı — mobil bankacılık', duration_ms: 3000,
    requests: 1, failed_requests: 1, p95_ms: 3000, checks_passed: 0, checks_failed: 1, error_code: 1210 },
]
const RUN_DETAIL = {
  steps_json: JSON.stringify(STEPS), steps_truncated: false,
  thresholds_json: JSON.stringify([
    { metric: 'http_req_duration', expr: 'p(95)<500', ok: false }, { metric: 'checks', expr: 'rate>0.99', ok: false },
    { metric: 'http_req_failed', expr: 'rate<0.01', ok: true }]),
  tls_json: JSON.stringify([
    { host: 'ibank.uzun-alt-alan-adi-ornegi.example-bankasi.com.tr', tls_version: 'tls1.3', requests: 3, cert_state: 'tracked',
      cert: { domain: 'ibank.uzun-alt-alan-adi-ornegi.example-bankasi.com.tr', days_remaining: 12, status: 'valid' } },
    { host: 'cdn.example.net', tls_version: 'tls1.2', requests: 1, cert_state: 'untracked' },
    { host: 'gizli.example.org', tls_version: 'tls1.3', requests: 1, cert_state: 'unknown' }]),
  context_json: JSON.stringify({ process_budget_s: 60, request_timeout_s: 20, egress: 'proxy', corporate_ca: true, inverted_budget: false }),
  failure_reason: 'CONNECT_REFUSED',
  failure_detail: JSON.stringify({ step: 'Giriş sayfası › Kurumsal OIDC token alma', error_code: 1210, threshold: 'http_req_duration p(95)<500' }),
}

const common = {
  team_id: 1, team_name: LONG_TEAM, group_name: LONG_GROUP, tags: 'prod,giris', interval_seconds: 300, timeout_seconds: 60,
  confirm_attempts: 3, recovery_checks: 3, slow_response_enabled: true, slow_threshold_ms: 5000, use_proxy: 'AUTO', alert_level: 'HIGH',
  can_check: true, can_edit: true, can_delete: true, active: true, script: SCRIPT,
  env: [{ name: 'BASE_URL', secret: false, value: 'https://ibank.example.com' }], script_version: '2.0.1', run_script_version: '2.0.1',
}
const MONS = [
  { ...common, id: 301, name: 'Ödeme akışı — sepete ekle, adres, kart doğrulama ve onay adımları', status: 'FAIL', checked_at: iso(4 * MIN),
    duration_ms: 3900, http_req_avg_ms: 950, last_http_req_p95_ms: 1240, checks_passed: 2, checks_failed: 1, exit_code: 99,
    checks_json: JSON.stringify([{ name: 'durum 200', passed: true }, { name: 'gövde', passed: true }, { name: 'OTP', passed: false }]),
    error: "k6 eşikleri aşıldı (çıkış 99):\nthresholds crossed\nsüreç bütçesi=60 sn, script istek timeout'u=20s", active_alarm: true, alarm_level: 'HIGH' },
  { ...common, id: 302, name: 'Kurumsal Keycloak OIDC girişi', status: 'ERROR', checked_at: iso(6 * MIN), duration_ms: 1300,
    http_req_avg_ms: 210, checks_passed: 3, checks_failed: 0, exit_code: 107, error: 'Script çalışma-zamanı hatası (çıkış 107):\nTypeError: x is undefined',
    // Zamanlamalı izleme (k6r D2 alanları): kartın sıklık çipi uzun zamanlama özetini SARAR
    schedule_mode: 'CRON', cron_expression: '0 9 * * 1-5', windows: [{ days: [1, 2, 3, 4, 5], from: '09:00', to: '18:00' }],
    blackouts: [{ from: '12:00', to: '13:00' }], skip_holidays: true, skip_dates: ['2026-11-02'], next_run_at: iso(-HOUR),
    effective_interval_seconds: 86400 },
  { ...common, id: 303, name: 'Duraklatılmış — eski mobil API', active: false, status: 'PASS', checked_at: iso(3 * 24 * HOUR),
    duration_ms: 1500, checks_passed: 4, checks_failed: 0 },
]
const VERSIONS = [
  { id: 33, version: '2.0.1', sequence_no: 3, event_type: 'EDIT', created_at: iso(30 * MIN), created_by: 'demo', current: true, run_count: 6, fail_count: 6 },
  { id: 32, version: '2.0.0', sequence_no: 2, event_type: 'EDIT', created_at: iso(2 * 24 * HOUR), created_by: 'demo', run_count: 512, fail_count: 3 },
]
const OLD_SCRIPT = "// v2.0.0 — eski sürüm\nexport default function () {}\n"
const TEMPLATES = [
  { id: 40, select_token: 40, builtin: false, scope: 'team', team_id: 1, team_name: LONG_TEAM, category: 'journey',
    name: 'Mobil şube uçtan uca yolculuk (giriş → hesap → çıkış)', description: 'Takımımızın standart yolculuk senaryosu.',
    tags: ['mobil', 'yolculuk'], env: [{ name: 'BASE_URL', secret: false }], current_version: '3.2.0', can_edit: true, can_delete: true,
    updated_by_name: 'Erdi İnanmış (Teknoloji / Kanal Yönetimi Direktörlüğü)', updated_at: iso(3 * 24 * HOUR), active: true },
  { id: 41, select_token: 41, builtin: false, scope: 'general', team_id: null, source_team_name: LONG_TEAM, category: 'security',
    name: 'Güvenlik başlıkları denetimi', description: 'HSTS, CSP ve X-Frame-Options başlıklarının varlığını doğrular.', tags: ['güvenlik'],
    env: [{ name: 'BASE_URL', secret: false }], current_version: '1.0.0', updated_by_name: 'Ayşe Örnek', updated_at: iso(9 * 24 * HOUR), active: true },
]

const json = (body, status = 200) => (route) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

function historyItems() {
  const items = []
  for (let k = 0; k < 6; k++) {
    const fail = k % 2 === 0
    items.push(fail
      ? { id: 1000 + k, checked_at: iso((k + 1) * 5 * MIN), status: 'FAIL', ok: false, duration_ms: 3900, exit_code: 99, script_version: '2.0.1',
        error: "k6 eşikleri aşıldı (çıkış 99):\nthresholds crossed\nsüreç bütçesi=60 sn, script istek timeout'u=20s", checks_passed: 2, checks_failed: 1,
        req_blocked_ms: 2, req_connecting_ms: 18, req_tls_ms: 41, req_sending_ms: 1, req_waiting_ms: 290, req_receiving_ms: 6, data_sent: 3100, data_received: 48000,
        ...RUN_DETAIL }
      : { id: 1000 + k, checked_at: iso((k + 1) * 5 * MIN), status: 'PASS', ok: true, duration_ms: 2300, checks_passed: 3, checks_failed: 0, script_version: '2.0.0' })
  }
  return items
}

async function routes(page) {
  await mockApi(page, { role: 'ADMIN', globalAdmin: true, perms: {
    'monitoring.scripted_templates': { view: true, edit: true }, 'inventory.crud': { view: true, edit: true } } })
  await page.route((u) => new URL(u).pathname === '/api/monitoring/scripted', json({ success: true, data: {
    monitors: MONS, k6_available: true, k6_version: 'v2.3.0', can_manage: true, proxy_configured: true, no_proxy: '.example.com' } }))
  await page.route((u) => /^\/api\/monitoring\/scripted\/\d+\/history$/.test(new URL(u).pathname), json({ success: true, data: {
    items: historyItems(), counts: { total: 288, fail: 10 },
    // son üç saat 4/12 hatalı — eski renk karışımında "koyu zeytin yeşili" görünüyordu (O7)
    buckets: Array.from({ length: 24 }, (_, h) => ({ key: iso((24 - h) * HOUR).slice(0, 13), total: 12, fail: h > 20 ? 4 : 0 })),
    alerts: [], range: { from: iso(24 * HOUR), to: iso(0) }, total: 6, page: 0, size: 50 } }))
  await page.route((u) => /^\/api\/monitoring\/scripted\/\d+\/versions$/.test(new URL(u).pathname), json({ success: true, data: { versions: VERSIONS } }))
  await page.route((u) => /^\/api\/monitoring\/scripted\/\d+\/versions\/\d+$/.test(new URL(u).pathname),
    json({ success: true, data: { id: 32, version: '2.0.0', script: OLD_SCRIPT, env: [] } }))
  await page.route((u) => /^\/api\/monitoring\/scripted\/\d+\/step-series$/.test(new URL(u).pathname), json({ success: true, data: {
    steps: STEPS.map((s, i) => ({ name: s.name, points: Array.from({ length: 12 }, (_, k) => ({
      ts: iso((12 - k) * HOUR), duration_ms: 300 + i * 200 + k * 20, failed: i === 2 && k % 4 === 0 })) })),
    truncated: false, from: iso(7 * 24 * HOUR), to: iso(0) } }))
  await page.route((u) => new URL(u).pathname === '/api/monitoring/scripted/templates', json({ success: true, data: {
    templates: TEMPLATES, can_create_general: true, can_view_trash: false, writable_team_ids: [1], k6_version: 'v2.3.0' } }))
  await page.route((u) => new URL(u).pathname === '/api/monitoring/scripted/drafts', json({ success: true, data: { drafts: [] } }))
}

async function pageOverflow(page) {
  return page.evaluate(() => {
    const main = document.querySelector('.app-main') || document.body
    return Math.max(document.documentElement.scrollWidth - document.documentElement.clientWidth, main.scrollWidth - main.clientWidth)
  })
}

/** Kök içinde kırpılan / taşan metin (yaprak öğeler; kod düzenleyici ve <pre> kendi kaydırmasında). */
function clippedIn(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT ' + sel]
  return [...root.querySelectorAll('span, p, li, h4, label, button, code, a')]
    .filter((n) => !n.closest('.sr-only') && !n.closest('pre') && !n.closest('[data-slot="error-text"][data-frame]'))
    .filter((n) => n.childElementCount === 0 && n.textContent.trim().length > 6)
    .filter((n) => { const r = n.getBoundingClientRect(); return r.width > 0 && r.height > 0 })
    .filter((n) => n.scrollWidth > n.clientWidth + 1)
    .map((n) => `${n.getAttribute('data-slot') || n.tagName.toLowerCase()}: ${n.textContent.trim().slice(0, 50)} (${n.scrollWidth}/${n.clientWidth})`)
    .slice(0, 20)
}

/** Öğe kabının dışına taşıyor mu (sol / sağ). page.evaluate tek argüman alır → `{ child, parent }`. */
function outside({ child: childSel, parent: parentSel }) {
  const out = []
  for (const c of document.querySelectorAll(childSel)) {
    const p = c.closest(parentSel)
    if (!p) continue
    const a = c.getBoundingClientRect(), b = p.getBoundingClientRect()
    if (a.width === 0) continue
    if (a.left < b.left - 1 || a.right > b.right + 1) out.push(`${c.getAttribute('data-slot') || c.tagName}: ${Math.round(a.left)}–${Math.round(a.right)} > ${Math.round(b.left)}–${Math.round(b.right)}`)
  }
  return out
}

/** 40 px'ten kısa görünür etkileşimli öğeler (absolute ::after genişletmesi sayılır). */
function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT ' + sel]
  const out = []
  for (const b of root.querySelectorAll('button, a[href], [role="combobox"]')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0 || b.closest('[aria-hidden="true"]')) continue
    if (b.getAttribute('role') === 'checkbox') continue
    const after = getComputedStyle(b, '::after')
    let h = r.height
    if (after.position === 'absolute' && after.content !== 'none') {
      const top = parseFloat(after.top) || 0, bottom = parseFloat(after.bottom) || 0
      h = Math.max(h, r.height - top - bottom)
    }
    if (h < 39.5) out.push(`${(b.getAttribute('aria-label') || b.textContent || b.getAttribute('data-slot') || b.tagName).trim().slice(0, 40)} ${Math.round(r.width)}×${Math.round(h)}`)
  }
  return out
}

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

for (const vp of VIEWPORTS) {
  test.describe(`Sentetik izleme ayrıntısı — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('kartlar: uzun takım / grup adı tam ve kartın içinde; yarıda kalan sayaç nötr; duraklatılmış panel nötr; p95', async ({ page }) => {
      test.setTimeout(120_000)
      await routes(page)
      await page.goto('/?tab=scripted')
      const card = page.locator('.upt-grid [data-slot="card"]').filter({ hasText: MONS[0].name }).first()
      await expect(card).toBeVisible({ timeout: 20_000 })
      await expect(card.locator('[data-slot="meta-team"]')).toContainText(LONG_TEAM)
      await expect(card.locator('[data-slot="meta-group"]')).toContainText(LONG_GROUP)
      expect(await page.evaluate(clippedIn, '.upt-grid'), 'kırpılan metin').toEqual([])
      expect(await page.evaluate(outside, { child: '[data-slot="meta-team"], [data-slot="meta-group"]', parent: '[data-slot="card"]' }), 'kart dışına taşma').toEqual([])
      await expect(card.locator('[data-slot="scripted-metric"][data-metric="request"] [data-slot="scripted-metric-sub"]')).toContainText('p95 1.2 s')

      const errCard = page.locator('.upt-grid [data-slot="card"]').filter({ hasText: MONS[1].name }).first()
      await expect(errCard.locator('[data-slot="scripted-checks"]')).toHaveAttribute('data-halted', 'true')
      const sched = errCard.locator('[data-chip="interval"]')
      await expect(sched).toHaveAttribute('data-schedule', 'cron')
      await expect(sched).toContainText(/09:00/)
      expect(await page.evaluate(outside, { child: '[data-chip="interval"]', parent: '[data-slot="card"]' }), 'zamanlama çipi taşıyor').toEqual([])
      const paused = page.locator('.upt-grid [data-slot="card"]').filter({ hasText: MONS[2].name }).first()
      await expect(paused.locator('[data-slot="scripted-result"]')).toHaveAttribute('data-tone', 'none')
      expect(await pageOverflow(page), 'sayfa yatay taşması').toBeLessThanOrEqual(1)
    })

    test('ayrıntı: satır içi koşum ayrıntısı tıklanan satırın altında; adımlar / eşikler / bağlam / TLS / hata sınıfı sığar; bant; trend', async ({ page }) => {
      test.setTimeout(150_000)
      await routes(page)
      await page.goto('/?tab=scripted')
      const card = page.locator('.upt-grid [data-slot="card"]').filter({ hasText: MONS[0].name }).first()
      await expect(card).toBeVisible({ timeout: 20_000 })
      await card.getByRole('button', { name: /— (detayları aç|open details)$/ }).first().click()
      const dlg = page.getByRole('dialog').last()
      await expect(dlg).toBeVisible()

      // O7: hatalı saat hücresi KIRMIZI tonda (yeşil kanal kırmızıdan baskın değil)
      const failCell = dlg.locator('[data-cell][data-fail]').first()
      await expect(failCell).toBeVisible()
      const rgb = await failCell.evaluate((el) => getComputedStyle(el).backgroundColor)
      const [r, g] = (rgb.match(/\d+(\.\d+)?/g) || []).map(Number)
      expect(r, `hatalı hücre rengi ${rgb}`).toBeGreaterThan(g)

      // "bu sürüm bozdu" bandı kontrol sekmesinde
      await expect(dlg.locator('[data-slot="version-regression"]')).toBeVisible()

      // Satır içi ayrıntı: ilk (hatalı) satırın zamanı
      const timeBtn = dlg.getByRole('button', { name: /— (koşum detayını aç|open the run details)$/ }).first()
      await timeBtn.scrollIntoViewIfNeeded()
      await timeBtn.click()
      const inline = dlg.locator('[data-slot="inline-run-detail"]')
      await expect(inline).toBeVisible()
      // satırın HEMEN altında: ayrıntının üstü tıklanan satırın altından itibaren, aralarında başka koşum satırı yok
      const geo = await page.evaluate(() => {
        const det = document.querySelector('[data-slot="inline-run-detail"]')
        const extra = det.closest('[data-slot="hist-row-extra"]')
        const btn = document.querySelector('button[aria-expanded="true"][aria-label*="—"]')
        const holder = extra?.tagName === 'TR' ? extra.previousElementSibling : det.closest('[data-slot="hist-card"]')
        return { ok: !!extra, holds: !!holder && holder.contains(btn), detTop: det.getBoundingClientRect().top, btnBottom: btn.getBoundingClientRect().bottom }
      })
      expect(geo.ok, 'ek satır').toBe(true)
      expect(geo.holds, 'ayrıntı tıklanan satıra bağlı').toBe(true)
      expect(geo.detTop).toBeGreaterThanOrEqual(geo.btnBottom - 1)
      expect(geo.detTop - geo.btnBottom, 'satırdan uzakta değil').toBeLessThan(200)

      await expect(inline.locator('[data-slot="step-row"]')).toHaveCount(3)
      await expect(inline.locator('[data-slot="threshold-row"][data-ok="false"]')).toHaveCount(2)
      await expect(inline.locator('[data-slot="tls-row"]')).toHaveCount(3)
      await expect(inline.locator('[data-ctx="egressProxy"]')).toBeVisible()
      await expect(inline.locator('[data-slot="chkfail-panel"]')).toBeVisible()
      await expect(inline.locator('[data-slot="error-text"]')).not.toContainText('süreç bütçesi=')
      await page.waitForTimeout(200)
      expect(await pageOverflow(page), 'sayfa yatay taşması').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clippedIn, '[data-slot="inline-run-detail"]'), 'ayrıntıda kırpılan metin').toEqual([])
      expect(await page.evaluate(outside, { child: '[data-slot="step-row"], [data-slot="tls-row"], [data-slot="threshold-row"]', parent: '[role="dialog"]' }), 'pencere dışına taşma').toEqual([])
      // pencerenin kaydırılan gövdesi yatay taşmaz (tablo ayrıntıyı asgari genişliğe katmaz)
      const scrollOver = await page.evaluate(() => {
        const det = document.querySelector('[data-slot="inline-run-detail"]')
        for (let el = det.parentElement; el; el = el.parentElement) {
          const cs = getComputedStyle(el)
          if (el.getAttribute('role') === 'dialog') return el.scrollWidth - el.clientWidth
          if ((cs.overflowX === 'auto' || cs.overflowX === 'scroll') && el.scrollWidth > el.clientWidth + 1) return el.scrollWidth - el.clientWidth
        }
        return 0
      })
      expect(scrollOver, 'ayrıntı tabloyu yatay taşırıyor').toBeLessThanOrEqual(1)
      if (vp.touch) {
        expect(await page.evaluate(smallTargets, '[data-slot="inline-run-detail"]'), '40 px altı hedef (ayrıntı)').toEqual([])
        const tb = await timeBtn.boundingBox()
        expect(tb.height, 'geçmiş satırı düğmesi').toBeGreaterThanOrEqual(39.5)
        expect(await page.evaluate(smallTargets, '[data-slot="version-regression"]'), '40 px altı hedef (bant)').toEqual([])
      }

      // Grafik sekmesi: adım trendi
      await dlg.getByRole('tab', { name: /süre grafiği|duration chart/i }).click()
      const trend = dlg.locator('[data-slot="step-trend"]')
      await expect(trend).toBeVisible({ timeout: 20_000 })
      await expect(trend.locator('[data-slot="step-trend-series"] [data-series]')).toHaveCount(3)
      await expect(trend.locator('[data-slot="step-trend-chart"]')).toBeVisible()
      expect(await page.evaluate(clippedIn, '[data-slot="step-trend"]'), 'trendde kırpılan metin').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="step-trend"]'), '40 px altı hedef (trend)').toEqual([])
      expect(await pageOverflow(page), 'sayfa yatay taşması').toBeLessThanOrEqual(1)

      // "önceki sürümü editöre yükle" → düzenleme formu eski betikle açılır (kaydetmez)
      await dlg.getByRole('tab', { name: /kontrol geçmişi|check history/i }).click()
      const load = page.locator('[data-slot="version-regression-load"]')
      await load.scrollIntoViewIfNeeded()
      await load.click()
      await expect(page.locator('#k6-script-editor')).toHaveValue(/v2\.0\.0 — eski sürüm/, { timeout: 10_000 })
      await page.screenshot({ path: test.info().outputPath(`scripted-detail-${vp.name}.png`), fullPage: false })
    })

    test('şablon kartı: uzun köken / takım rozeti kartın içinde sarılır; görünür "Bu şablonla başla"', async ({ page }) => {
      test.setTimeout(120_000)
      await routes(page)
      await page.goto('/?tab=scripted')
      await page.getByRole('button', { name: /^(Şablonlar|Templates)$/ }).click()
      for (const b of await page.locator('[data-branch] button[aria-expanded="false"]').all()) await b.click()
      const cards = page.locator('[data-template-card]')
      await expect(cards).toHaveCount(2)
      await expect(page.locator('[data-template-card] [data-slot="template-use"]')).toHaveCount(2)
      expect(await page.evaluate(outside, { child: '[data-template-card] [data-slot="badge"], [data-template-card] [data-slot="template-origin"], [data-template-card] [data-scope]', parent: '[data-template-card]' }),
        'rozet kart dışına taşıyor').toEqual([])
      expect(await page.evaluate(clippedIn, '.sc-templates'), 'kırpılan metin').toEqual([])
      if (vp.touch) {
        const use = await page.locator('[data-slot="template-use"]').first().boundingBox()
        expect(use.height).toBeGreaterThanOrEqual(39.5)
      }
      expect(await pageOverflow(page), 'sayfa yatay taşması').toBeLessThanOrEqual(1)
      // "Bu şablonla başla" → izleme formu açılır
      await page.locator('[data-slot="template-use"]').first().click()
      await expect(page.getByRole('dialog').last()).toBeVisible()
    })
  })
}
