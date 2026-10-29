import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

// 2026-10-29 (k6 0.49 → 2.3 geçişi): Ayarlar → Platform → k6 motoru telefon (390, dokunmatik), tablet (768, dokunmatik) ve
// dizüstünde (1280): sayfa yatay kaymaz, öğe ekran dışına taşmaz, metin (uzun ikili yolu, AD takım adı, izleme adı, hata
// metni) kırpılmaz, dokunmatikte hedefler ≥ 40 px; sonuçlar 1280'de tablo, 390'da kart; "Adayı etkin yap" onay penceresi
// ekrana sığar ve hazırlık özetini (gerileme) gösterir; TLS kaçışı yalnız değişen alanı kaydeder.
const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'
const LONG_MONITOR = 'Bireysel İnternet Şubesi ve Mobil Bankacılık Ortak Giriş Senaryosu (Dijital Kanallar / Bireysel Bankacılık)'
const LONG_BIN = '/opt/sitemonitor/k6/releases/v2.3.0/linux-amd64/k6-with-a-very-long-directory-name-for-wrapping'
const ACTIVE = { bin: '/usr/local/bin/k6', available: true, version: 'v0.49.0', modern_syntax: false, api_address: true, probed_at: '2026-10-29T20:15:03' }
const CANDIDATE = { bin: LONG_BIN, available: true, version: 'v2.3.0', modern_syntax: true, api_address: false, probed_at: '2026-10-29T20:15:04' }
const GODEBUG_KEYS = ['tlsrsakex', 'tls3des', 'tlssha1', 'x509negativeserial', 'rsa1024min', 'tlsmlkem', 'tlssecpmlkem']
  .map((key) => ({ key, description: `${key} açıklaması` }))

const summary = (extra = {}) => ({
  id: 7, state: 'DONE', started_at: '2026-10-29T18:00:00', finished_at: '2026-10-29T18:05:00', heartbeat_at: '2026-10-29T18:05:00',
  started_by_name: 'Erdi İnanmış (Teknoloji / Kanal Yönetimi Direktörlüğü)', owner_pod: 'site-monitor-7c9d6f8b5-xk2lq',
  active_bin: ACTIVE.bin, active_version: 'v0.49.0', candidate_bin: LONG_BIN, candidate_version: 'v2.3.0', scope: 'ALL',
  total: 4, done: 4, regressions: 1, improvements: 1, unchanged: 1, no_baseline: 1, failure: null, ...extra,
})
const RESULTS = [
  { monitor_id: 11, monitor_name: LONG_MONITOR, team_id: 1, team_name: LONG_TEAM, diff: 'REGRESSION', status: 'FAIL', exit_code: 99,
    duration_ms: 3400, error_class: 'TLS', archive: 'OK', retried: true, via_proxy: true,
    error: 'tls: handshake failure — remote error: tls: insufficient security level; the server offered only TLS_RSA_WITH_AES_128_CBC_SHA which Go 1.26 no longer enables by default',
    baseline_status: 'PASS', baseline_k6_version: 'v0.49.0', baseline_checked_at: '2026-10-29T17:50:00', baseline_duration_ms: 900 },
  { monitor_id: 12, monitor_name: 'Ödeme API Senaryosu', team_id: 2, team_name: 'Ödeme Sistemleri', diff: 'IMPROVED', status: 'PASS', exit_code: 0,
    duration_ms: 1200, error_class: null, archive: 'OK', retried: false, baseline_status: 'FAIL', baseline_k6_version: 'v0.49.0',
    baseline_checked_at: '2026-10-29T17:51:00', baseline_duration_ms: 1500 },
  { monitor_id: 13, monitor_name: 'Kampanya Sayfası', team_id: null, team_name: null, diff: 'NO_BASELINE', status: 'PASS', exit_code: 0,
    duration_ms: 800, archive: 'OK', baseline_status: null },
  { monitor_id: 14, monitor_name: 'Eski Sözdizimli Betik', team_id: 2, team_name: 'Ödeme Sistemleri', diff: 'SAME_PASS', status: 'PASS', exit_code: 0,
    duration_ms: 65_000, archive: 'OK', baseline_status: 'PASS', baseline_k6_version: 'v0.49.0', baseline_checked_at: '2026-10-29T17:52:00', baseline_duration_ms: 64_000 },
]
const COUNTS = { REGRESSION: 1, FLAKY: 0, CHANGED: 0, SLOWER: 0, IMPROVED: 1, SAME_FAIL: 0, SAME_PASS: 1, NO_BASELINE: 1, NOT_RUN: 0 }

function engineData(settings) {
  return {
    pod: 'site-monitor-7c9d6f8b5-xk2lq', engines: { active: ACTIVE, rollback: null, candidate: CANDIDATE }, syntax_version: 'v0.49.0',
    settings, godebug_keys: GODEBUG_KEYS, scripted_enabled: true, can_switch: true, can_rollback: false,
    monitors: [
      { id: 11, name: LONG_MONITOR, team_id: 1, team_name: LONG_TEAM, active: true, excluded: false },
      { id: 12, name: 'Ödeme API Senaryosu', team_id: 2, team_name: 'Ödeme Sistemleri', active: true, excluded: false },
      { id: 13, name: 'Kampanya Sayfası', team_id: null, team_name: null, active: true, excluded: false },
      { id: 14, name: 'Eski Sözdizimli Betik', team_id: 2, team_name: 'Ödeme Sistemleri', active: true, excluded: false },
    ],
    scan: summary(),
  }
}

const json = (body, status = 200) => (route) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

/** mockApi'den SONRA kaydedilen yollar önceliklidir. Arayüz dili TR. */
async function routes(page) {
  await page.addInitScript(() => { try { localStorage.setItem('site-monitor-lang', 'tr') } catch { /* yok */ } })
  await mockApi(page, { role: 'ADMIN', globalAdmin: true })
  const calls = { puts: [], switches: 0 }
  let settings = { rollback_bin: '', candidate_bin: LONG_BIN, godebug: '', user_agent: '', excluded_ids: [] }
  await page.route((u) => new URL(u).pathname === '/api/admin/k6-engine', json({ success: true, data: engineData(settings) }))
  await page.route((u) => new URL(u).pathname === '/api/admin/k6-engine/settings', (route) => {
    const body = JSON.parse(route.request().postData() || '{}')
    calls.puts.push(body)
    settings = { ...settings, ...body }
    return json({ success: true, message: 'k6 motoru ayarları kaydedildi (yeniden başlatma gerekmez).', data: engineData(settings) })(route)
  })
  await page.route((u) => new URL(u).pathname === '/api/admin/k6-engine/scans', json({ success: true, data: { scans: [summary(), summary({ id: 6, candidate_version: 'v2.2.0', regressions: 3 })] } }))
  await page.route((u) => /\/api\/admin\/k6-engine\/scans\/\d+$/.test(new URL(u).pathname),
    json({ success: true, data: { ...summary(), counts: COUNTS, results: RESULTS } }))
  await page.route((u) => new URL(u).pathname === '/api/admin/k6-engine/switch', (route) => {
    calls.switches++
    return json({ success: true, message: 'Aday k6 etkin yapıldı (v2.3.0).', data: engineData(settings) })(route)
  })
  return calls
}

function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button, select, input:not([type="hidden"]), textarea, [role="combobox"]')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0 || b.getAttribute('role') === 'checkbox' || b.getAttribute('role') === 'switch') continue
    const cs = getComputedStyle(b)
    if (cs.visibility === 'hidden' || cs.display === 'none' || b.getAttribute('aria-hidden') === 'true') continue
    if (r.height < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || b.id || '').trim().slice(0, 30)}" ${Math.round(r.height)}`)
  }
  return out
}

function clipped(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  return [...root.querySelectorAll('span, p, li, h3, h4, label, button, dt, dd, code, td, th')].filter((n) => !n.closest('[role="combobox"]'))
    .filter((n) => !n.closest('.sr-only') && !n.closest('pre') && !n.closest('select'))
    .filter((n) => n.childElementCount === 0 && n.textContent.trim().length > 12
      && (n.scrollWidth > n.clientWidth + 1 || getComputedStyle(n).textOverflow === 'ellipsis')).map((n) => n.textContent.slice(0, 40))
}

function offenders(sel) {
  const vw = document.documentElement.clientWidth
  const root = document.querySelector(sel) || document.body
  const out = []
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    const cs = getComputedStyle(el)
    if (r.width <= 1 || r.height <= 1 || cs.visibility === 'hidden' || cs.display === 'none') continue
    if (r.right <= vw + 1 && r.left >= -1) continue
    let contained = false
    for (let p = el.parentElement; p && p !== root; p = p.parentElement) {
      if (getComputedStyle(p).overflowX !== 'visible' && p.getBoundingClientRect().right <= vw + 1) { contained = true; break }
    }
    if (!contained) out.push(`${el.tagName.toLowerCase()}[${el.getAttribute('data-slot') || ''}] ${Math.round(r.left)}..${Math.round(r.right)}`)
    if (out.length >= 8) break
  }
  return out
}

const overflowX = () => document.documentElement.scrollWidth - document.documentElement.clientWidth
const ROOT = '[data-slot="k6e-root"]'

for (const vp of VIEWPORTS) {
  test.describe(`Ayarlar → k6 motoru — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('sığar, kırpılmaz, dokunma hedefleri; motorlar + ayarlar + tarama + sonuçlar (1280 tablo, dar ekran kart)', async ({ page }) => {
      test.setTimeout(120_000)
      await routes(page)
      await page.goto('/?tab=settings&sec=k6engine')
      const root = page.locator(ROOT)
      await expect(root).toHaveAttribute('data-state', 'ok', { timeout: 20_000 })
      await expect(root.locator('[data-slot="k6e-engine"]')).toHaveCount(3)
      await expect(root.locator('[data-slot="k6e-gd"]')).toHaveCount(7)
      await expect(root.locator('[data-slot="k6e-detail"]')).toBeVisible({ timeout: 10_000 })
      await expect(root.locator('[data-slot="k6e-res-row"]')).toHaveCount(4)
      const list = root.locator('[data-slot="k6e-res-list"]')
      if (vp.width >= 1280) await expect(list).toHaveAttribute('data-layout', 'table')
      if (vp.width <= 390) await expect(list).toHaveAttribute('data-layout', 'cards')
      // Uzun hata metni açılır, kendi kabında sarar
      const reg = root.locator('[data-slot="k6e-res-row"][data-id="11"]')
      await reg.getByRole('button', { name: /Hata metnini göster/ }).click()
      await expect(reg.locator('[data-slot="k6e-problem-pre"]')).toBeVisible()
      await page.waitForTimeout(300)

      expect(await page.evaluate(overflowX), `@${vp.name}: yatay taşma`).toBeLessThanOrEqual(1)
      expect(await page.evaluate(offenders, ROOT), `@${vp.name}: ekran dışına taşan öğe`).toEqual([])
      expect(await page.evaluate(clipped, ROOT), `@${vp.name}: kırpılan metin`).toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, ROOT), `@${vp.name}: küçük dokunma hedefi`).toEqual([])
      await page.screenshot({ path: `test-results/settings-k6engine-${vp.name}.png`, fullPage: false })
      // Tam sayfa görüntüsü EN SONDA — Chromium'un dokunmatik öykünmesini sıfırlar (CLAUDE.md, e2e fullPage tuzağı)
      await page.screenshot({ path: `test-results/settings-k6engine-${vp.name}-full.png`, fullPage: true })
    })

    test('Adayı etkin yap: onay penceresi ekrana sığar, gerileme uyarısı görünür; onay POST eder', async ({ page }) => {
      test.setTimeout(120_000)
      const calls = await routes(page)
      await page.goto('/?tab=settings&sec=k6engine')
      const root = page.locator(ROOT)
      await expect(root).toHaveAttribute('data-state', 'ok', { timeout: 20_000 })
      await expect(root.locator('[data-slot="k6e-detail"]')).toBeVisible({ timeout: 10_000 })
      const btn = root.locator('[data-slot="k6e-switch"]')
      await btn.scrollIntoViewIfNeeded()
      if (vp.touch) expect((await btn.boundingBox()).height, `@${vp.name}: geçiş düğmesi`).toBeGreaterThanOrEqual(39.5)
      await btn.click()
      const dlg = page.locator('[data-slot="k6e-action-dialog"]')
      await expect(dlg).toBeVisible()
      await expect(dlg).toHaveAttribute('data-level', 'danger')
      await expect(dlg.locator('[data-slot="k6e-ready-item"][data-key="regressions"]')).toContainText('gerileme')
      await page.waitForTimeout(400)   // açılış saydamlık geçişi bitsin (ölçüm ve ekran görüntüsü son hâli görsün)
      const box = await dlg.boundingBox()
      expect(box.x, `@${vp.name}: pencere solda taşıyor`).toBeGreaterThanOrEqual(0)
      expect(box.y, `@${vp.name}: pencere üstte taşıyor`).toBeGreaterThanOrEqual(0)
      expect(box.x + box.width, `@${vp.name}: pencere sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
      expect(box.y + box.height, `@${vp.name}: pencere altta taşıyor`).toBeLessThanOrEqual(vp.height + 1)
      expect(await page.evaluate(clipped, '[data-slot="k6e-action-dialog"]'), `@${vp.name}: pencerede kırpılan metin`).toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="k6e-action-dialog"]'), `@${vp.name}: pencerede küçük hedef`).toEqual([])
      await page.screenshot({ path: `test-results/settings-k6engine-${vp.name}-dialog.png`, fullPage: false })
      await dlg.locator('[data-slot="k6e-dlg-confirm"]').click()
      await expect.poll(() => calls.switches).toBe(1)
      await expect(dlg).toHaveCount(0)
    })

    test('TLS kaçışı yalnız godebug alanını kaydeder; kayıt çubuğu temizlenir', async ({ page }) => {
      test.setTimeout(120_000)
      const calls = await routes(page)
      await page.goto('/?tab=settings&sec=k6engine')
      const root = page.locator(ROOT)
      await expect(root).toHaveAttribute('data-state', 'ok', { timeout: 20_000 })
      const sw = root.locator('[data-slot="k6e-gd"][data-key="tlsmlkem"] [role="switch"]')
      await sw.scrollIntoViewIfNeeded()
      await sw.click()
      await expect(root.locator('[data-slot="k6e-godebug-value"]')).toContainText('tlsmlkem=0')
      const bar = root.locator('[data-slot="k6e-settings"] [data-slot="settings-save-bar"]')
      await bar.getByRole('button', { name: /^Kaydet$/ }).click()
      await expect.poll(() => calls.puts.length).toBe(1)
      expect(calls.puts[0]).toEqual({ godebug: 'tlsmlkem=0' })
      await expect(bar).not.toHaveAttribute('data-dirty', 'true')
      expect(await page.evaluate(overflowX), `@${vp.name}: yatay taşma (kayıt sonrası)`).toBeLessThanOrEqual(1)
    })
  })
}
