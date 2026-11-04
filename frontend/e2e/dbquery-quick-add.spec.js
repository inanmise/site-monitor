import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

// dbq2 (2026-11-05) — Veritabanı Sorgu İzleme hızlı bağlantı + hızlı ekleme, telefon (390, dokunmatik), tablet (768,
// dokunmatik) ve dizüstünde (1280):
//  1) "Hızlı ekle" sihirbazı baştan sona: bağlantı → DBA şablonu (hızlı başlangıç) → dene → kural → zamanlama → özet → kaydet
//     (POST gövdesi), her adımda yatay taşma / kırpılan metin / küçük dokunma hedefi yok, alt eylem çubuğu ekranda;
//  2) güvenli düzeltme: SQL Server sorgusunda NOLOCK reddi → "Düzelt" → önizleme farkı → Uygula;
//  3) bağlantılar: dizeden doldurma (parola alınmaz, kutu temizlenir), adım adım teşhis + çare, son 24 saat sağlık kartı,
//     "Bu bağlantıdan yeni" (parola boş).
// API tamamen taklit (support/monitorMocks.js + bu dosyanın rotaları); gerçek satır verisi hiçbir yerde yok.
const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]
const LONG_CONN = 'ODEME_RAPORLAMA_SALT_OKUNUR — Kurumsal Ödeme Sistemleri Raporlama Kümesi (İkincil Düğüm, Okuma Kopyası)'
const CONNS = [
  { id: 41, team_id: 1, name: LONG_CONN, db_type: 'MSSQL', host: 'odeme-rapor-mssql-scan-01.dc1.example.com.tr', port: 1433,
    database_name: 'OdemeRapor', mssql_instance: 'RPT', username: 'sm_reader', password_set: true, mssql_encrypt: true, connect_timeout_ms: 10000,
    can_edit: true, health: { state: 'ok', last_test_ok: true, last_test_at: '2026-11-05T09:00:00' },
    used_by: [{ id: 2, name: 'Başarısız ödeme işlemleri — son 15 dakika (Kurumsal ve Bireysel Bankacılık Ödeme Altyapısı)', active: true }], tables_used: [] },
  { id: 42, team_id: 1, name: 'Sipariş DB (PostgreSQL üretim)', db_type: 'POSTGRESQL', host: 'db.example.com', port: 5432, database_name: 'orders',
    username: 'ro', password_set: false, ssl_mode: 'require', connect_timeout_ms: 10000, can_edit: true, health: { state: 'untested' }, used_by: [], tables_used: [] },
]
const HEALTH = {
  monitors: [{ id: 2, name: CONNS[0].used_by[0].name, active: true, status: 'BREACH' }], monitor_count: 1, runs_24h: 288, failed_24h: 4,
  success_pct: 98.6, avg_duration_ms: 812, last_ok_at: '2026-11-05T10:00:00',
  last_error: { at: '2026-11-05T03:12:00', code: 'DB_TIMEOUT', message: 'Bağlanma süresi doldu (10 sn) — sunucu yanıt vermedi' }, unused: false,
}
const json = (body, status = 200) => (route) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

async function routes(page) {
  await page.addInitScript(() => { try { localStorage.setItem('site-monitor-lang', 'tr') } catch { /* yok */ } })
  await mockApi(page, { role: 'USER', globalAdmin: false })
  const calls = { creates: [], fixes: [], tests: 0 }
  const path = (p) => (u) => new URL(u).pathname === p
  await page.route(path('/api/monitoring/dbquery/connections'), json({ success: true, data: CONNS }))
  await page.route((u) => /\/api\/monitoring\/dbquery\/connections\/\d+\/health$/.test(new URL(u).pathname), json({ success: true, data: HEALTH }))
  await page.route((u) => /\/api\/monitoring\/dbquery\/connections\/\d+\/test$/.test(new URL(u).pathname), json({ success: true, data: {
    ok: false, duration_ms: 10012, error: 'Sunucuya ulaşılamadı (10 sn içinde yanıt yok)', failure_reason: 'DB_UNREACHABLE',
    steps: [
      { key: 'dns', status: 'ok', ms: 4 },
      { key: 'tcp', status: 'fail', ms: 10003, code: 'TCP_TIMEOUT', detail: '10.20.30.40:1433 — bağlantı 10 sn içinde kurulamadı (güvenlik duvarı ya da yönlendirme)' },
      { key: 'tls', status: 'skip' }, { key: 'login', status: 'skip' }, { key: 'query', status: 'skip' }, { key: 'privileges', status: 'skip' },
    ] } }))
  await page.route(path('/api/monitoring/dbquery/sql-check'), (route) => {
    const body = JSON.parse(route.request().postData() || '{}')
    const sql = String(body.sql || '')
    if (body.db_type === 'MSSQL' && /FROM\s+dbo\.Orders(?!\s+WITH)/i.test(sql)) {
      return json({ success: true, data: { ok: false, tables: [], violations: [{ code: 'SQL_MSSQL_NOLOCK_REQUIRED', token: 'dbo.Orders', line: 2, column: 6,
        message: "SQL Server'da her tabloya WITH (NOLOCK) eklenmeli: dbo.Orders" }] } })(route)
    }
    return json({ success: true, data: { ok: true, violations: [], tables: ['sys.dm_exec_requests'] } })(route)
  })
  await page.route(path('/api/monitoring/dbquery/sql-fix'), (route) => {
    const body = JSON.parse(route.request().postData() || '{}')
    calls.fixes.push(body)
    const sql = String(body.sql || '').replace(/FROM\s+dbo\.Orders/i, 'FROM dbo.Orders WITH (NOLOCK)')
    return json({ success: true, data: { fixes: [{ code: 'ADD_NOLOCK', sql, changed: ['dbo.Orders'], note: null }] } })(route)
  })
  await page.route(path('/api/monitoring/dbquery/test'), (route) => {
    calls.tests += 1
    return json({ success: true, data: { status: 'OK', duration_ms: 84, row_count: 1, row_count_capped: false,
      columns: [{ name: 'blocked_sessions', type: 'int' }], rule_results: [], warnings: [] } })(route)
  })
  await page.route(path('/api/monitoring/dbquery/schedule-preview'), json({ success: true, data: { runs: [], interval_seconds: 300 } }))
  await page.route(path('/api/monitoring/dbquery/templates'), json({ success: true, data: [] }))
  await page.route((u) => new URL(u).pathname === '/api/monitoring/groups', json({ success: true, data: [{ name: 'Kurumsal Ödeme Sistemleri — Raporlama' }] }))
  await page.route(path('/api/monitoring/dbquery'), (route) => {
    if (route.request().method() === 'POST') {
      const body = JSON.parse(route.request().postData() || '{}')
      calls.creates.push(body)
      return json({ success: true, data: { id: 900 + calls.creates.length, ...body } })(route)
    }
    return route.fallback()
  })
  return calls
}

/** Görünüm alanının dışına taşan (kendi kaydırma kabında olmayan) öğeler + sayfa düzeyi yatay taşma. */
function measure(sel) {
  const vw = document.documentElement.clientWidth
  const root = (sel && document.querySelector(sel)) || document.body
  const pageOverflow = Math.max(document.documentElement.scrollWidth - document.documentElement.clientWidth,
    document.body.scrollWidth - document.body.clientWidth)
  const clips = (el) => getComputedStyle(el).overflowX !== 'visible'
  const out = []
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    const cs = getComputedStyle(el)
    if (r.width <= 1 || r.height <= 1 || cs.visibility === 'hidden' || cs.display === 'none') continue
    if (r.right <= vw + 1 && r.left >= -1) continue
    let contained = false
    for (let p = el.parentElement; p && p !== root; p = p.parentElement) {
      if (clips(p) && p.getBoundingClientRect().right <= vw + 1) { contained = true; break }
    }
    if (!contained) out.push(`${el.tagName.toLowerCase()}[${el.getAttribute('data-slot') || ''}] ${Math.round(r.left)}..${Math.round(r.right)}`)
    if (out.length >= 8) break
  }
  return { pageOverflow, offenders: out }
}

/** Yatayda kırpılan metin (yaprak düğümler, 12+ karakter). Kendi kabında yatay kayan kod blokları hariç. */
function clipped(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  return [...root.querySelectorAll('span, p, li, h2, h3, h4, label, button, dt, dd, code, td, th, a, strong')]
    .filter((n) => !n.closest('.sr-only') && !n.closest('select') && !n.closest('svg') && !n.closest('pre') && !n.closest('textarea')
      && !n.closest('[data-slot="dbq-sql-editor"]'))
    .filter((n) => n.childElementCount === 0 && n.textContent.trim().length > 12
      && n.clientWidth > 0 && n.scrollWidth > n.clientWidth + 1).map((n) => n.textContent.slice(0, 40))
}

/** Dokunmatikte 40 px altındaki etkin hedefler (onay kutusu `::after` / satır yüksekliğiyle; anahtar satırıyla). */
function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button, select, input:not([type="hidden"]), [role="combobox"], a[href]')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0 || b.disabled) continue
    const cs = getComputedStyle(b)
    if (cs.visibility === 'hidden' || cs.display === 'none' || b.getAttribute('aria-hidden') === 'true') continue
    if (b.closest('[hidden]') || b.getAttribute('role') === 'checkbox' || b.closest('[data-slot="dbq-sql-editor"] [data-slot="dbq-sql-rules"]')) continue
    let h = r.height
    const role = b.getAttribute('role')
    if (role === 'switch') h = Math.max(h, b.parentElement.getBoundingClientRect().height)
    const af = getComputedStyle(b, '::after')
    if (af.position === 'absolute' && af.content !== 'none') {
      const top = parseFloat(af.top) || 0
      const bottom = parseFloat(af.bottom) || 0
      h = Math.max(h, r.height - Math.min(0, top) - Math.min(0, bottom))
    }
    if (h < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)}" ${Math.round(h)}`)
  }
  return out
}

for (const vp of VIEWPORTS) {
  test.describe(`Veritabanı sorgu — hızlı ekleme ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    const check = async (page, stage, sel) => {
      await page.waitForTimeout(250)
      const m = await page.evaluate(measure, sel)
      expect(m.offenders, `${stage} @${vp.name}: ekran dışına taşan öğe`).toEqual([])
      expect(m.pageOverflow, `${stage} @${vp.name}: sayfa yatay kayıyor`).toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, sel), `${stage} @${vp.name}: kırpılan metin`).toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, sel), `${stage} @${vp.name}: küçük dokunma hedefi`).toEqual([])
    }

    test('sihirbaz baştan sona: bağlantı → DBA şablonu → dene → kural → zamanlama → özet → kaydet', async ({ page }) => {
      test.setTimeout(180_000)
      const calls = await routes(page)
      await page.goto('/?tab=dbquery')
      await expect(page.locator('.upt-grid > [data-slot="card"]').first()).toBeVisible({ timeout: 20_000 })
      await page.locator('[data-slot="dbq-quick-add"]').click()
      const wiz = page.locator('[data-slot="dbq-wiz"]')
      await expect(wiz).toBeVisible()
      const dsel = '[role="dialog"]:has([data-slot="dbq-wiz"])'
      const footer = page.locator('[data-slot="dbq-wiz-footer"]')
      const footerVisible = async (stage) => {
        const box = await footer.boundingBox()
        expect(box, `${stage}: alt çubuk yok`).not.toBeNull()
        expect(box.y + box.height, `${stage} @${vp.name}: alt eylem çubuğu ekranın altında`).toBeLessThanOrEqual(vp.height + 1)
      }
      await check(page, 'sihirbaz: bağlantı', dsel)
      await footerVisible('bağlantı')
      // İleri: bağlantı seçilmeden ilerlemez, hata alanın altında
      await page.locator('[data-slot="dbq-wiz-next"]').click()
      await expect(wiz).toHaveAttribute('data-step', 'connection')
      await expect(wiz.locator('[data-field="profile_id"]')).toContainText('Bir bağlantı seçin')
      await wiz.getByRole('combobox', { name: 'Veritabanı bağlantısı' }).click()
      await page.getByRole('option', { name: new RegExp(LONG_CONN.slice(0, 20)) }).click()
      await expect(wiz.locator('[data-slot="dbq-conn-selected"]')).toContainText(LONG_CONN)
      await check(page, 'sihirbaz: bağlantı seçili', dsel)
      await page.locator('[data-slot="dbq-wiz-next"]').click()
      await expect(wiz).toHaveAttribute('data-step', 'query')
      await check(page, 'sihirbaz: hızlı başlangıç', dsel)
      await wiz.locator('[data-slot="dbq-wiz-qs-item"][data-key="blocked_sessions"]').click()
      await expect(wiz.locator('[data-slot="dbq-sql-editor"] textarea')).toHaveValue(/sys\.dm_exec_requests WITH \(NOLOCK\)/)
      await expect(wiz.locator('[data-slot="dbq-sql-guard"]')).toHaveAttribute('data-status', 'ok', { timeout: 5000 })
      await check(page, 'sihirbaz: sorgu', dsel)
      await page.locator('[data-slot="dbq-wiz-next"]').click()
      await expect(wiz).toHaveAttribute('data-step', 'test')
      await wiz.locator('[data-slot="dbq-wiz-test"]').click()
      await expect(wiz.locator('[data-slot="dbq-test"]')).toHaveAttribute('data-status', 'OK')
      expect(calls.tests).toBe(1)
      await check(page, 'sihirbaz: deneme', dsel)
      await page.locator('[data-slot="dbq-wiz-next"]').click()
      await expect(wiz).toHaveAttribute('data-step', 'rules')
      await check(page, 'sihirbaz: kural', dsel)
      await page.locator('[data-slot="dbq-wiz-next"]').click()
      await expect(wiz).toHaveAttribute('data-step', 'schedule')
      await check(page, 'sihirbaz: zamanlama', dsel)
      await page.locator('[data-slot="dbq-wiz-next"]').click()
      await expect(wiz).toHaveAttribute('data-step', 'summary')
      await expect(wiz.getByLabel(/^Ad/)).toHaveValue('Bloklanan / kilit bekleyen oturumlar')
      // grup + etiket
      await wiz.locator('[data-field="group_name"] [role="combobox"]').click()
      await page.getByRole('option', { name: 'Kurumsal Ödeme Sistemleri — Raporlama' }).click()
      const tag = wiz.locator('[data-field="tags"] input').first()
      await tag.fill('dba')
      await tag.press('Enter')
      await check(page, 'sihirbaz: özet', dsel)
      await footerVisible('özet')
      await page.screenshot({ path: test.info().outputPath(`dbq-wizard-summary-${vp.name}.png`), fullPage: false })
      await page.locator('[data-slot="dbq-wiz-save"]').click()
      await expect(wiz).toHaveCount(0)
      expect(calls.creates).toHaveLength(1)
      expect(calls.creates[0]).toMatchObject({ name: 'Bloklanan / kilit bekleyen oturumlar', profile_id: 41, interval_seconds: 300, tags: 'dba',
        groupName: 'Kurumsal Ödeme Sistemleri — Raporlama' })
      expect(calls.creates[0].sql_text).toContain('WITH (NOLOCK)')
    })

    test('güvenli düzeltme: NOLOCK reddi → Düzelt → önizleme → Uygula', async ({ page }) => {
      test.setTimeout(120_000)
      const calls = await routes(page)
      await page.goto('/?tab=dbquery')
      await expect(page.locator('.upt-grid > [data-slot="card"]').first()).toBeVisible({ timeout: 20_000 })
      await page.locator('[data-slot="dbq-quick-add"]').click()
      const wiz = page.locator('[data-slot="dbq-wiz"]')
      await wiz.getByRole('combobox', { name: 'Veritabanı bağlantısı' }).click()
      await page.getByRole('option', { name: new RegExp(LONG_CONN.slice(0, 20)) }).click()
      await page.locator('[data-slot="dbq-wiz-next"]').click()
      const ta = wiz.locator('[data-slot="dbq-sql-editor"] textarea')
      await ta.fill('SELECT COUNT(*) AS bekleyen\nFROM dbo.Orders\nWHERE status = 0')
      await expect(wiz.locator('[data-slot="dbq-sql-guard"]')).toHaveAttribute('data-status', 'rejected', { timeout: 5000 })
      await wiz.locator('[data-slot="dbq-sql-fix"]').click()
      const fix = page.locator('[data-slot="dbq-fix"]')
      await expect(fix.locator('[data-slot="dbq-fix-item"][data-code="ADD_NOLOCK"]')).toBeVisible()
      expect(calls.fixes[0]).toMatchObject({ db_type: 'MSSQL', row_limit: 1000 })
      const fsel = '[role="dialog"]:has([data-slot="dbq-fix"])'
      await check(page, 'düzeltme önizlemesi', fsel)
      await fix.locator('[data-slot="dbq-fix-apply"]').click()
      await expect(fix).toHaveCount(0)
      await expect(ta).toHaveValue(/FROM dbo\.Orders WITH \(NOLOCK\)/)
      await expect(wiz.locator('[data-slot="dbq-sql-guard"]')).toHaveAttribute('data-status', 'ok', { timeout: 5000 })
    })

    test('bağlantılar: dizeden doldurma, adım adım teşhis, sağlık kartı, "Bu bağlantıdan yeni"', async ({ page }) => {
      test.setTimeout(120_000)
      await routes(page)
      await page.goto('/?tab=dbquery')
      await expect(page.locator('.upt-grid > [data-slot="card"]').first()).toBeVisible({ timeout: 20_000 })
      await page.locator('[data-slot="dbq-open-connections"]').click()
      const mgr = page.locator('[data-slot="dbq-conn-manager"]')
      await expect(mgr).toBeVisible()
      const msel = '[role="dialog"]:has([data-slot="dbq-conn-manager"])'
      await expect(mgr.locator('[data-slot="dbq-conn-unused"]').first()).toBeVisible()
      await check(page, 'bağlantı listesi', msel)
      // ayrıntı: sağlık kartı + teşhis
      const row = mgr.locator('[data-slot="dbq-conn-row"][data-id="41"], [data-slot="dbq-conn-card"][data-id="41"]').first()
      const asTable = (await mgr.locator('[data-slot="dbq-conn-table"]').count()) > 0
      await row.getByRole('button', { name: asTable ? LONG_CONN : 'Ayrıntı', exact: true }).click()
      await expect(mgr.locator('[data-slot="dbq-conn-health24"][data-state="ok"]')).toBeVisible()
      await mgr.getByRole('button', { name: 'Bağlantıyı dene' }).click()
      const steps = mgr.locator('[data-slot="dbq-conn-steps"]')
      await expect(steps).toHaveAttribute('data-state', 'fail')
      await expect(steps.locator('[data-key="tcp"] [data-slot="dbq-conn-step-fix"]')).toContainText('güvenlik duvarı')
      await check(page, 'bağlantı ayrıntısı + teşhis', msel)
      await page.screenshot({ path: test.info().outputPath(`dbq-conn-detail-${vp.name}.png`), fullPage: false })
      // Bu bağlantıdan yeni: parola boş
      await mgr.locator('[data-slot="dbq-conn-copy"]').click()
      const form = mgr.locator('[data-slot="dbq-conn-form"]')
      await expect(form.getByLabel(/^Bağlantı adı/)).toHaveValue(`${LONG_CONN} (kopya)`)
      await expect(form.getByLabel(/^Parola/)).toHaveValue('')
      await check(page, 'bağlantı kopyası', msel)
      // dizeden doldurma: parola alınmaz, kutu temizlenir
      const box = form.getByLabel('Bağlantı dizesinden doldur')
      await box.fill('jdbc:postgresql://pg-rapor-02.example.com:6543/raporlar?sslmode=verify-full&user=rapor_ro&password=CokGizli42')
      await form.locator('[data-slot="dbq-conn-paste-apply"]').click()
      await expect(form).toHaveAttribute('data-type', 'POSTGRESQL')
      await expect(form.getByLabel(/^Sunucu/)).toHaveValue('pg-rapor-02.example.com')
      await expect(form.getByLabel(/^Port/)).toHaveValue('6543')
      await expect(box).toHaveValue('')
      await expect(form.locator('[data-slot="dbq-conn-paste-password"]')).toBeVisible()
      await expect(form.getByLabel(/^Parola/)).toHaveValue('')
      expect(await page.content()).not.toContain('CokGizli42')
      await check(page, 'dizeden doldurma', msel)
    })
  })
}
