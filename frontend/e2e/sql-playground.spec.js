// SQL Playground zenginleştirme (2026-11-13, kullanıcı 16 önerinin hepsini onayladı). Gerçek tarayıcıda 390 (dokunmatik) /
// 768 (dokunmatik) / 1280: sekmeler, parametre formu, otomatik tamamlama listesi, maskeli sonuç, grafik, plan, karşılaştırma,
// durdur, geçmiş ve kayıtlı sorgu panelleri sığar; yatay taşma yok, metin kırpılmaz, dokunma hedefleri ≥ 40 px. Arka uç
// mock'lu (`/api/admin/sql/*` mockApi'nin üstüne yeni uçlar).
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

const LONG_TEAM = 'Altyapı ve Ağ Operasyonları Takımı (Kurumsal Uygulamalar)'
const LONG_TITLE = 'Açık kritik alarmların takım ve izleme türüne göre dağılımı (son 30 gün, yalnız üretim ortamı)'
const ROWS_A = Array.from({ length: 24 }, (_, i) => ({
  id: i + 1, day: `2026-11-${String(i + 1).padStart(2, '0')}T00:00:00`, team: i % 3 ? 'Takım A' : LONG_TEAM,
  alerts: ((i * 7) % 23) + 1, password_hash: '••••',
}))
const ROWS_B = ROWS_A.slice(2).map((r, i) => (i % 5 === 0 ? { ...r, alerts: r.alerts + 3 } : r)).concat([{ id: 99, day: '2026-11-30T00:00:00', team: 'Takım C', alerts: 4, password_hash: '••••' }])

const PLAN = [{
  Plan: {
    'Node Type': 'Hash Join', 'Join Type': 'Inner', 'Startup Cost': 1.5, 'Total Cost': 1832.4, 'Plan Rows': 4120,
    Plans: [
      { 'Node Type': 'Seq Scan', 'Relation Name': 'alerts', Alias: 'a', 'Startup Cost': 0, 'Total Cost': 1500.2, 'Plan Rows': 98000,
        Filter: '(resolved = false)' },
      { 'Node Type': 'Hash', 'Startup Cost': 1, 'Total Cost': 1, 'Plan Rows': 12,
        Plans: [{ 'Node Type': 'Index Scan', 'Relation Name': 'teams_with_a_rather_long_table_name_for_layout', 'Index Name': 'teams_with_a_rather_long_table_name_for_layout_pkey',
          'Startup Cost': 0, 'Total Cost': 1, 'Plan Rows': 12 }] },
    ],
  },
  'Planning Time': 0.4,
}]

const SAVED = [
  { id: 41, title: LONG_TITLE, description: 'Haftalık ekip toplantısında bakılan özet; değerler canlı veriden.', tags: ['alarm', 'haftalık', 'üretim'],
    sql_text: 'SELECT team_id, count(*) FROM alerts WHERE resolved = false GROUP BY team_id', shared: true, owner_name: 'Ad Soyad (Platform / Kıdemli Uzman)',
    mine: false, can_edit: false, created_at: '2026-11-01T09:00:00', updated_at: '2026-11-12T16:30:00', version: 2 },
  { id: 42, title: 'Takım listesi', description: '', tags: [], sql_text: 'SELECT * FROM teams', shared: false, owner_name: 'demo',
    mine: true, can_edit: true, created_at: '2026-11-10T09:00:00', updated_at: '2026-11-10T09:00:00', version: 0 },
]

// Canlı denemedeki gibi çok sayıda örnek (42, 6 kategori) — 390 px'te seçicinin üstü ekran dışına taşıyordu (inceleme Ek B)
const SAMPLE_CATS = ['alerts', 'teams', 'system', 'certs', 'monitors', 'users']
const SAMPLES = [
  { id: 'open-alerts', category: 'alerts', label: 'Açık alarmlar', description: 'Şu an açık olan alarmlar; en yenisi en üstte.', sql: 'SELECT * FROM alerts WHERE resolved = false' },
  { id: 'teams', category: 'teams', label: 'Takımlar', description: 'Takım listesi ve yöneticileri.', sql: 'SELECT * FROM teams' },
  { id: 'sched', category: 'system', label: 'Zamanlayıcı kilitleri', description: 'Kimin hangi kilidi tuttuğu.', sql: 'SELECT * FROM scheduler_lock' },
  ...Array.from({ length: 39 }, (_, i) => ({
    id: `extra-${i + 1}`, category: SAMPLE_CATS[i % SAMPLE_CATS.length],
    label: `Örnek sorgu ${i + 1} — uzun bir başlık ki satır sarsın`, description: 'Kısa açıklama: ne döndürdüğünü anlatır.', sql: `SELECT ${i + 1}`,
  })),
]

const HISTORY_PAGE = {
  success: true, total: 31, page: 0, size: 25, slow_ms: 5000, data: [
    { id: 90, sql_text: 'SELECT pg_sleep(6), count(*) FROM alerts', executed_by: 'Ad Soyad (Platform / Kıdemli Uzman)', row_count: 1, duration_ms: 6123, success: true, executed_at: '2026-11-13T09:00:00' },
    { id: 89, sql_text: 'SELECT * FROM missing_table_with_a_really_long_name_for_layout_testing', executed_by: 'demo', duration_ms: 3, success: false, error_message: 'ERROR: relation "missing_table_with_a_really_long_name_for_layout_testing" does not exist', executed_at: '2026-11-13T08:00:00' },
    { id: 88, sql_text: 'SELECT 1', executed_by: 'demo', duration_ms: 2, success: false, cancelled: true, error_message: 'Sorgu durduruldu', executed_at: '2026-11-13T07:00:00' },
  ],
}

async function mock(page, state) {
  await page.addInitScript(() => { try { localStorage.setItem('site-monitor-lang', 'tr') } catch { /* yoksay */ } })
  await mockApi(page, { perms: { 'monitoring.crud': { view: true, edit: true } } })
  const json = (body, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) })
  await page.route((u) => new URL(u).pathname.startsWith('/api/admin/sql/'), async (route) => {
    const req = route.request()
    const u = new URL(req.url())
    const p = u.pathname
    let body = {}
    try { body = req.postDataJSON() || {} } catch { body = {} }
    if (p === '/api/admin/sql/execute') {
      state.executes.push(body)
      if (/pg_sleep/.test(body.sql || '')) {
        await new Promise((resolve) => { state.release = resolve; setTimeout(resolve, 15_000) })
        return route.fulfill(json({ success: true, ok: false, cancelled: true, error: 'Sorgu durduruldu', rows: [], rowCount: 0, run_id: body.run_id }))
      }
      const rows = state.executes.length > 1 ? ROWS_B : ROWS_A
      return route.fulfill(json({ success: true, ok: true, rows, rowCount: rows.length, durationMs: 41, run_id: body.run_id,
        masked_columns: ['password_hash'], executedSql: body.sql }))
    }
    if (p === '/api/admin/sql/cancel') {
      state.cancels.push(body)
      state.release?.()
      return route.fulfill(json({ success: true, cancelled: true }))
    }
    if (p === '/api/admin/sql/explain') {
      return route.fulfill(json({ success: true, ok: true, analyzed: false, plan: PLAN, summary: {
        total_cost: 1832.4, plan_rows: 4120, nodes: 4, seq_scans: [{ table: 'alerts', rows: 98000 }],
        top_nodes: [{ node_type: 'Seq Scan', relation: 'alerts', cost: 1500.2, share: 0.82 }] } }))
    }
    if (p === '/api/admin/sql/history' && u.search) return route.fulfill(json(HISTORY_PAGE))
    if (p === '/api/admin/sql/samples') return route.fulfill(json({ success: true, data: SAMPLES }))
    if (p === '/api/admin/sql/dictionary') {
      return route.fulfill(json({ success: true, tables: { alerts: { description: 'Alarm kayıtları — her açılan alarm bir satır; kapanınca çözüm alanları dolar.', columns: { id: 'Alarm numarası' } } } }))
    }
    if (p === '/api/admin/sql/saved' && req.method() === 'GET') return route.fulfill(json({ success: true, total: SAVED.length, page: 0, size: 20, data: SAVED }))
    if (p === '/api/admin/sql/saved' && req.method() === 'POST') {
      state.saved.push(body)
      return route.fulfill(json({ success: true, data: { id: 77, ...body, mine: true, can_edit: true, version: 0 } }))
    }
    const m = p.match(/^\/api\/admin\/sql\/saved\/(\d+)$/)
    if (m) return route.fulfill(json({ success: true, data: SAVED.find((s) => String(s.id) === m[1]) ?? SAVED[1] }))
    return route.fallback()
  })
}

function overflowIn(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const vw = document.documentElement.clientWidth
  const out = []
  for (const el of root.querySelectorAll('*')) {
    // Yatay kaydırılan kapların (sonuç tablosu, kod blokları) içi kapsam dışı — kap kendisi ölçülür
    if (el.parentElement?.closest('[data-slot="sql-result-scroll"], [data-slot="table-container"], pre')) continue
    const r = el.getBoundingClientRect()
    if (r.width > 1 && r.right > vw + 1) out.push(`${el.tagName.toLowerCase()}.${(el.getAttribute('data-slot') || '')} ${Math.round(r.right)}`)
    if (out.length > 5) break
  }
  return out
}

/** Kırpılan metin: taşan ve gizleyen metin kutuları — grafik SVG'si, ekran okuyucu metni ve kaydırılan sonuç tablosu hariç. */
function clippedIn(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const el of root.querySelectorAll('span, p, code, dd, dt, h3, h4, li, label')) {
    if (el.closest('svg') || el.classList.contains('sr-only') || el.closest('[data-slot="sql-result-scroll"]') || el.closest('pre')) continue
    const cs = getComputedStyle(el)
    if (!['hidden', 'clip'].includes(cs.overflowX)) continue
    if (el.scrollWidth > el.clientWidth + 1) out.push((el.textContent || '').trim().slice(0, 40))
    if (out.length > 5) break
  }
  return out
}

/** Sıkışan etiketler: metni sığmayan düğmeler ve genişliği 0'a düşen metin kutuları (inceleme Ek A — 390 px araç çubuğu). */
function squeezedLabels(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0) continue
    const name = (b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)
    if (b.scrollWidth > b.clientWidth + 1) out.push(`taşan düğme "${name}"`)
    for (const s of b.querySelectorAll('span')) {
      if (s.classList.contains('sr-only') || !(s.textContent || '').trim()) continue
      const sr = s.getBoundingClientRect()
      if (sr.width < 1 || s.scrollWidth > s.clientWidth + 1) out.push(`sıkışan etiket "${(s.textContent || '').trim().slice(0, 30)}" ${Math.round(sr.width)}`)
    }
    if (out.length > 5) break
  }
  return out
}

function smallTargets(sel) {
  const out = []
  for (const root of document.querySelectorAll(sel)) {
    for (const b of root.querySelectorAll('button, a[href], [role="option"]')) {
      const r = b.getBoundingClientRect()
      if (r.width === 0 || r.height === 0) continue
      if (r.height < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)}" ${Math.round(r.height)}`)
    }
  }
  return out
}

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

const editorTa = (page) => page.getByRole('textbox', { name: 'SQL sorgusu' })
async function toEditorTab(page, vp) {
  if (vp.name === 'phone') await page.getByRole('tab', { name: /^Düzenleyici/ }).click()
}
async function toResultsTab(page, vp) {
  if (vp.name === 'phone') await page.getByRole('tab', { name: /^Sonuçlar/ }).click()
}
async function openMore(page) {
  await page.locator('[data-slot="sql-editor-card"]').getByRole('button', { name: /^Diğer/ }).click()
  return page.getByRole('menu')
}

for (const vp of VIEWPORTS) {
  test.describe(`SQL Playground zenginleştirme — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('sekmeler, parametre formu, tamamlama listesi ve biçimlendir sığar', async ({ page }) => {
      test.setTimeout(90_000)
      const state = { executes: [], cancels: [], saved: [] }
      await mock(page, state)
      await page.goto('/?tab=sqlplayground')
      await expect(editorTa(page)).toBeVisible({ timeout: 30_000 })

      // Sekmeler: 4 sekme, biri uzun başlıklı kayıtlı sorgu (kayıtlı panelden)
      await page.locator('[data-slot="sql-saved-open"]').click()
      const panel = page.locator('[data-slot="sql-saved-panel"]')
      await expect.poll(() => panel.evaluate((el) => Math.round(el.getBoundingClientRect().right))).toBeLessThanOrEqual(vp.width)
      await expect(panel.locator('[data-slot="sql-saved-item"]')).toHaveCount(2)
      expect(await page.evaluate(clippedIn, '[data-slot="sql-saved-panel"]'), 'kayıtlı panelde kırpılan metin').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="sql-saved-panel"]'), 'kayıtlı panelde küçük hedef').toEqual([])
      await panel.locator('[data-slot="sql-saved-item"]').first().getByRole('button', { name: /^Aç$/ }).click()
      await expect(panel).toHaveCount(0)
      await expect(editorTa(page)).toHaveValue(SAVED[0].sql_text)
      await page.locator('[data-slot="sql-tab-add"]').click()
      await page.locator('[data-slot="sql-tab-add"]').click()
      await expect(page.locator('[data-slot="sql-tab"]')).toHaveCount(4)

      // Parametre formu
      await editorTa(page).fill('SELECT id, team FROM alerts WHERE created_at > :since AND team_id = :team_id AND resolved = :is_open')
      await editorTa(page).press('Escape')
      const params = page.locator('[data-slot="sql-params"]')
      await expect(params.locator('[data-param]')).toHaveCount(3)
      await params.locator('[data-param="team_id"] input').fill('7')

      // Tamamlama listesi: görünüm içinde, seçenekler dokunmatikte ≥ 40 px
      await editorTa(page).fill('SELECT * FROM ')
      await editorTa(page).click()
      await editorTa(page).press('End')
      await page.keyboard.type('ale')
      const lb = page.getByRole('listbox', { name: 'Öneriler' })
      await expect(lb).toBeVisible()
      const opt = lb.getByRole('option', { name: /^alerts/ })
      await expect(opt).toBeVisible()
      const box = await lb.boundingBox()
      expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="sql-completion"]'), 'öneri satırı küçük').toEqual([])
      await opt.click()
      await expect(editorTa(page)).toHaveValue('SELECT * FROM alerts')

      // Biçimlendir
      await editorTa(page).fill('select a, b from t where x between 1 and 5 and y = 2')
      await editorTa(page).press('Escape')
      await page.locator('[data-slot="sql-format"]').click()
      await expect(editorTa(page)).toHaveValue('SELECT a,\n  b\nFROM t\nWHERE x BETWEEN 1 AND 5\n  AND y = 2')

      expect(await page.evaluate(overflowIn, '[data-slot="sql-playground"]'), 'yatay taşma').toEqual([])
      expect(await page.evaluate(clippedIn, '[data-slot="sql-tabs"]'), 'sekmelerde kırpılan metin').toEqual([])
      // Düzenleyici araç çubuğu (inceleme Ek A): "Örnek Sorgular" / "Geçmiş" etiketleri sıkışmaz, kırpılmaz
      expect(await page.evaluate(clippedIn, '[data-slot="sql-editor-actions"]'), 'araç çubuğunda kırpılan metin').toEqual([])
      expect(await page.evaluate(squeezedLabels, '[data-slot="sql-editor-actions"]'), 'araç çubuğunda sıkışan etiket').toEqual([])
      expect(await page.evaluate(overflowIn, '[data-slot="sql-editor-actions"]'), 'araç çubuğunda taşma').toEqual([])
      if (vp.touch) {
        expect(await page.evaluate(smallTargets, '[data-slot="sql-tabs"]'), 'sekmelerde küçük hedef').toEqual([])
        expect(await page.evaluate(smallTargets, '[data-slot="sql-editor-card"] > div:first-child'), 'araç çubuğunda küçük hedef').toEqual([])
        expect(await page.evaluate(smallTargets, '[data-slot="sql-editor-actions"]'), 'düzenleyici düğmelerinde küçük hedef').toEqual([])
      }

      // Örnek seçici (inceleme Ek B): açılır pencerenin üstü ekranda, arama alanı görünür, alt kenar ekran içinde.
      // Tetikleyici ekranın ortasına getirilir (canlı denemedeki konum): ne üstte ne altta tam liste sığar — sınır
      // yoksa açılır pencere yukarı çevrilip üstü ekran dışına taşıyordu (y = -20).
      const samplesBtn = page.getByRole('button', { name: /^Örnek Sorgular \(42\)$/ })
      await page.evaluate(() => { document.body.style.paddingBottom = '1200px' })
      await samplesBtn.evaluate((el) => el.scrollIntoView({ block: 'center' }))
      const tb = await samplesBtn.boundingBox()
      expect(Math.abs(tb.y + tb.height / 2 - vp.height / 2), 'tetikleyici ekranın ortasına yakın').toBeLessThan(vp.height * 0.15)
      await samplesBtn.click()
      const pop = page.locator('[data-slot="sql-samples"]')
      await expect(pop).toBeVisible()
      await expect.poll(async () => Math.round((await pop.boundingBox())?.y ?? -1)).toBeGreaterThanOrEqual(0)
      const pbox = await pop.boundingBox()
      expect(pbox.y + pbox.height, 'seçicinin alt kenarı ekranda').toBeLessThanOrEqual(vp.height + 1)
      expect(pbox.x + pbox.width, 'seçicinin sağ kenarı ekranda').toBeLessThanOrEqual(vp.width + 1)
      await expect(page.getByPlaceholder('Örneklerde ara…')).toBeInViewport()
      await page.keyboard.press('Escape')
      await expect(pop).toHaveCount(0)
    })

    test('çalıştır → maskeli sonuç, grafik, plan, karşılaştırma; görünümler sığar', async ({ page }) => {
      test.setTimeout(120_000)
      const state = { executes: [], cancels: [], saved: [] }
      await mock(page, state)
      await page.goto('/?tab=sqlplayground')
      await expect(editorTa(page)).toBeVisible({ timeout: 30_000 })
      await editorTa(page).fill('SELECT id, day, team, alerts, password_hash FROM daily_alerts')
      await editorTa(page).press('Escape')
      await editorTa(page).press('Control+Enter')
      await expect.poll(() => state.executes.length).toBe(1)
      expect(state.executes[0].run_id).toMatch(/^[A-Za-z0-9-]{8,64}$/)
      const result = page.locator('[data-slot="sql-result"]')
      await expect(result.locator('[data-slot="sql-masked-badge"]')).toBeVisible()
      await expect(result.locator('[data-slot="sql-masked-note"]')).toContainText('password_hash')
      expect(await page.evaluate(overflowIn, '[data-slot="sql-result"]'), 'sonuçta taşma').toEqual([])

      // Grafik
      const view = result.getByRole('group', { name: 'Sonuç görünümü' })
      await view.getByRole('button', { name: 'Grafik' }).click()
      const chart = page.locator('[data-slot="sql-chart"]')
      await expect(chart).toBeVisible({ timeout: 20_000 })
      await expect(chart.locator('svg').first()).toBeVisible()
      expect(await page.evaluate(overflowIn, '[data-slot="sql-chart"]'), 'grafikte taşma').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="sql-result"] > div:first-child'), 'sonuç başlığında küçük hedef').toEqual([])

      // Plan (Diğer menüsü düzenleyici kartında)
      await toEditorTab(page, vp)
      const menu = await openMore(page)
      await menu.getByRole('menuitem', { name: 'Planı göster (EXPLAIN)' }).click()
      await toResultsTab(page, vp)
      const plan = page.locator('[data-slot="sql-plan"]')
      await expect(plan).toBeVisible()
      await expect(plan.locator('[data-slot="sql-plan-seq"]')).toContainText('alerts')
      await expect(plan.locator('[data-slot="sql-plan-tree"]')).toContainText('Index Scan')
      expect(await page.evaluate(overflowIn, '[data-slot="sql-plan"]'), 'planda taşma').toEqual([])
      expect(await page.evaluate(clippedIn, '[data-slot="sql-plan"]'), 'planda kırpılan metin').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="sql-plan"]'), 'planda küçük hedef').toEqual([])

      // İkinci çalıştırma → karşılaştırma
      await toEditorTab(page, vp)
      await editorTa(page).press('Control+Enter')
      await expect.poll(() => state.executes.length).toBe(2)
      await toResultsTab(page, vp)
      await view.getByRole('button', { name: 'Karşılaştır' }).click()
      const cmp = page.locator('[data-slot="sql-compare"]')
      await expect(cmp.locator('[data-slot="sql-compare-counts"]')).toBeVisible()
      await expect(cmp.locator('[data-slot="sql-compare-masked"]')).toContainText('password_hash')
      expect(await page.evaluate(overflowIn, '[data-slot="sql-compare"]'), 'karşılaştırmada taşma').toEqual([])
      expect(await page.evaluate(clippedIn, '[data-slot="sql-compare"]'), 'karşılaştırmada kırpılan metin').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="sql-compare"]'), 'karşılaştırmada küçük hedef').toEqual([])
    })

    test('durdur: çalışan sorgu run_id ile iptal edilir; geçmiş paneli ve örnek kategorileri sığar', async ({ page }) => {
      test.setTimeout(90_000)
      const state = { executes: [], cancels: [], saved: [] }
      await mock(page, state)
      await page.goto('/?tab=sqlplayground')
      await expect(editorTa(page)).toBeVisible({ timeout: 30_000 })
      await editorTa(page).fill('SELECT pg_sleep(30)')
      await editorTa(page).press('Escape')
      await editorTa(page).press('Control+Enter')
      const stop = page.locator('[data-slot="sql-stop"]')
      await expect(stop).toBeVisible()
      if (vp.touch) expect((await stop.boundingBox()).height).toBeGreaterThanOrEqual(39.5)
      await stop.click()
      await expect.poll(() => state.cancels.length).toBe(1)
      expect(state.cancels[0].run_id).toBe(state.executes[0].run_id)
      await toResultsTab(page, vp)
      await expect(page.locator('[data-slot="sql-result-cancelled"]')).toBeVisible()

      // Örnekler kategoriye göre
      await toEditorTab(page, vp)
      await page.getByRole('button', { name: /^Örnek Sorgular \(42\)$/ }).click()
      await expect(page.locator('[data-slot="sql-sample-group"]')).toHaveCount(6)
      await expect(page.locator('[data-slot="sql-sample-desc"]').first()).toBeVisible()
      await page.keyboard.press('Escape')

      // Geçmiş paneli
      const menu = await openMore(page)
      await menu.getByRole('menuitem', { name: /^Tüm geçmiş/ }).click()
      const hist = page.locator('[data-slot="sql-history-panel"]')
      await expect.poll(() => hist.evaluate((el) => Math.round(el.getBoundingClientRect().right))).toBeLessThanOrEqual(vp.width)
      await expect(hist.locator('[data-slot="sql-history-item"]')).toHaveCount(3)
      await expect(hist.locator('[data-slot="sql-history-item"][data-state="slow"]')).toHaveCount(1)
      await expect(hist.locator('[data-slot="sql-history-pager"]')).toContainText('31')
      expect(await page.evaluate(overflowIn, '[data-slot="sql-history-panel"]'), 'geçmişte taşma').toEqual([])
      expect(await page.evaluate(clippedIn, '[data-slot="sql-history-panel"]'), 'geçmişte kırpılan metin').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="sql-history-panel"]'), 'geçmişte küçük hedef').toEqual([])
      await page.screenshot({ path: `test-results/sql-playground-${vp.name}.png`, fullPage: true })
    })
  })
}
