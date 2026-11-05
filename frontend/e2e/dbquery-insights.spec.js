// Veritabanı Sorgu İzleme — deneyim ve akıllı kurallar (dbq2, 2026-11-05). Telefon (390, dokunmatik), tablet (768, dokunmatik)
// ve dizüstünde (1280):
//  1) liste — kartta değer eğilimi (mini grafik + yön + yüzde), "Şu an ihlalde" hızlı süzgeci (URL dq=breach) ve bağlantıya
//     göre gruplu liste (URL sort=conn; başlıkta uzun bağlantı adı TAM, durum özeti, bağlantı sağlığı);
//  2) ayrıntı grafiği — alarm dönemleri + numaralı değişiklik işaretleri, olay çipleri dokununca açıklama;
//  3) kural oluşturucu — "Olağan dışı değer" / "Değer donmuş" parametreleri + açıklama, "Eşik öner" (son 7 gün) uygulanmadan
//     gösterilir, Uygula eşikleri yazar;
//  4) Kişisel Panom "Veri kuralları" kutusu — sayılar, tıklama sayfayı "Şu an ihlalde" süzgeciyle açar.
// Her aşamada: ekran dışına taşan öğe yok, sayfa yatay kaymaz, metin kırpılmaz, dokunmatikte hedefler ≥ 40 px. API taklit.
import { test, expect } from '@playwright/test'
import { mockApi, MONITORS } from './support/monitorMocks.js'

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]
const HOUR = 3_600_000
const at = (h) => new Date(Date.now() - h * HOUR).toISOString().slice(0, 19)
const json = (body) => (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })

// Liste satırları: mock'un altı dbquery izlemesi + değer eğilimi (eski sunucu biçimindeki satır alansız kalır)
const TREND = (dir, delta, base) => ({ points: Array.from({ length: 24 }, (_, h) => (h === 7 ? null : base + Math.round(base * 0.15 * Math.sin(h / 3)) + (dir === 'up' ? h : -h))), delta_pct: delta, direction: dir })
const ROWS = MONITORS.dbquery.map((m, i) => (i === 0 ? { ...m, value_trend: TREND('up', 12.5, 40) }
  : i === 1 ? { ...m, value_trend: TREND('down', -38.2, 120) }
  : i === 4 ? { ...m, value_trend: TREND('flat', 0.4, 30) } : m))
const BREACH_NAMES = ROWS.filter((m) => m.active !== false && (m.status === 'BREACH' || m.status === 'FAIL' || m.active_alarm)).map((m) => m.name)
const LONG_CONN = ROWS[1].profile.name

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

// `only` verilirse yalnız o seçicilerin kendisi / içi ölçülür (paylaşılan başlık, süzgeç çubuğu ve form çerçevesi kendi
// e2e'lerinde ölçülür — burada bu sürümün yüzeyleri). page.evaluate yalnız işlevi taşır: yardımcı her işlevin İÇİNDE.
function clipped(arg) {
  const scoped = (r, o, q) => (!o ? [...r.querySelectorAll(q)]
    : o.flatMap((x) => [...r.querySelectorAll(x)].flatMap((el) => [...(el.matches(q) ? [el] : []), ...el.querySelectorAll(q)])))
  const { sel, only } = typeof arg === 'string' ? { sel: arg, only: null } : arg
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  if (only && !only.some((o) => root.querySelector(o))) return ['NO_ONLY']
  return scoped(root, only, 'span, p, li, h2, h3, h4, label, button, dt, dd, code, td, th, a, strong')
    .filter((n) => !n.closest('.sr-only') && !n.closest('select') && !n.closest('svg'))
    .filter((n) => n.childElementCount === 0 && n.textContent.trim().length > 12
      && n.clientWidth > 0 && n.scrollWidth > n.clientWidth + 1).map((n) => n.textContent.slice(0, 40))
}

function smallTargets(arg) {
  const scoped = (r, o, q) => (!o ? [...r.querySelectorAll(q)]
    : o.flatMap((x) => [...r.querySelectorAll(x)].flatMap((el) => [...(el.matches(q) ? [el] : []), ...el.querySelectorAll(q)])))
  const { sel, only } = typeof arg === 'string' ? { sel: arg, only: null } : arg
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  if (only && !only.some((o) => root.querySelector(o))) return ['NO_ONLY']
  const out = []
  for (const b of scoped(root, only, 'button, select, input:not([type="hidden"]), [role="combobox"], a[href]')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0 || b.disabled) continue
    const cs = getComputedStyle(b)
    if (cs.visibility === 'hidden' || cs.display === 'none' || b.getAttribute('aria-hidden') === 'true') continue
    if (b.closest('[hidden]') || b.hasAttribute('data-monitor-open') || b.getAttribute('role') === 'checkbox') continue
    let h = r.height
    const af = getComputedStyle(b, '::after')
    if (af.position === 'absolute' && af.content !== 'none') {
      h = Math.max(h, r.height - Math.min(0, parseFloat(af.top) || 0) - Math.min(0, parseFloat(af.bottom) || 0))
    }
    if (b.getAttribute('role') === 'radio' || b.getAttribute('role') === 'switch') h = Math.max(h, b.parentElement.getBoundingClientRect().height)
    if (h < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)}" ${Math.round(h)}`)
  }
  return out
}

async function routes(page, opts = {}) {
  await page.addInitScript(() => { try { localStorage.setItem('site-monitor-lang', 'tr') } catch { /* yok */ } })
  await mockApi(page, { role: 'ADMIN', globalAdmin: true, monitors: { ...MONITORS, dbquery: ROWS }, ...opts })
  await page.route((u) => new URL(u).pathname === '/api/monitoring/dbquery/connections', json({ success: true, data: [
    { id: 40, name: ROWS[0].profile.name, health: { state: 'ok' } },
    { id: 41, name: ROWS[1].profile.name, health: { state: 'failing' } },
    { id: 42, name: ROWS[2].profile.name, health: { state: 'untested' } },
  ] }))
  await page.route((u) => /^\/api\/monitoring\/dbquery\/\d+\/response-series$/.test(new URL(u).pathname), json({ success: true, data: {
    bucket: 'hour', unit: 'value', from: at(24), to: at(0), total: 24, down_total: 0, capped: false, metric: 'value',
    series: Array.from({ length: 24 }, (_, h) => {
      const v = 30 + Math.round(10 * Math.sin(h / 3)) + (h > 18 ? 40 : 0)
      return { ts: at(24 - h), avg: v, min: v - 3, max: v + 4, p95: v + 3, count: 12, down: 0, metric_value: v, row_count: 1, status: h > 18 ? 'BREACH' : 'OK' }
    }),
    thresholds: [{ rule_id: 'r1', label: 'Bekleyen sayısı', level: 'WARNING', value: 20 }, { rule_id: 'r1', label: 'Bekleyen sayısı', level: 'CRITICAL', value: 50 }],
    alarms: [
      { from: at(20), to: at(17), level: 'WARNING', type: 'DBQUERY_THRESHOLD' },
      { from: at(5), to: null, level: 'CRITICAL', type: 'DBQUERY_THRESHOLD' },
    ],
    changes: [{ at: at(12), event: 'UPDATE', fields: ['sqlText', 'rulesJson', 'intervalSeconds'] }, { at: at(3), event: 'UPDATE', fields: ['rulesJson'] }],
  } }))
}

for (const vp of VIEWPORTS) {
  test.describe(`Veritabanı sorgu deneyimi — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    // Taşma TÜM kökte; kırpma ve dokunma hedefi `only` verilirse yalnız bu sürümün yüzeylerinde
    const check = async (page, stage, sel, only = null) => {
      await page.waitForTimeout(250)
      const m = await page.evaluate(measure, sel)
      expect(m.offenders, `${stage} @${vp.name}: ekran dışına taşan öğe`).toEqual([])
      expect(m.pageOverflow, `${stage} @${vp.name}: sayfa yatay kayıyor`).toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, { sel, only }), `${stage} @${vp.name}: kırpılan metin`).toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, { sel, only }), `${stage} @${vp.name}: küçük dokunma hedefi`).toEqual([])
    }
    const LIST_OWN = ['[data-slot="dbq-focus-bar"]', '.upt-grid', '[data-slot="dbq-conn-group-head"]', '[data-slot="dbq-focus-empty"]']
    const RULE_OWN = ['[data-slot="dbq-rule-card"]', '[data-slot="dbq-rule-add"]']

    test('liste: değer eğilimi, "Şu an ihlalde" süzgeci, bağlantıya göre gruplar', async ({ page }) => {
      test.setTimeout(120_000)
      await routes(page)
      await page.goto('/?tab=dbquery')
      const cards = page.locator('.upt-grid > [data-slot="card"]')
      await expect(cards).toHaveCount(6, { timeout: 20_000 })
      const card = (name) => cards.filter({ has: page.locator('[data-monitor-open]', { hasText: name }) })
      const trend = card(ROWS[0].name).locator('[data-slot="dbq-value-trend"]')
      await expect(trend).toHaveAttribute('data-direction', 'up')
      await expect(trend).toHaveAttribute('aria-label', /artış, \+%12,5/)
      await expect(card(ROWS[1].name).locator('[data-slot="dbq-value-trend-delta"]')).toHaveText('−%38,2')
      await expect(card(ROWS[3].name).locator('[data-slot="dbq-value-trend"]')).toHaveCount(0)
      await check(page, 'liste (eğilim)', '.upt-page', LIST_OWN)

      // "Şu an ihlalde": sayaç + süzgeç + URL
      const focus = page.locator('[data-slot="dbq-focus-breach"]')
      await expect(focus.locator('[data-slot="dbq-focus-breach-count"]')).toHaveText(String(BREACH_NAMES.length))
      await focus.click()
      await expect(focus).toHaveAttribute('aria-pressed', 'true')
      await expect(cards).toHaveCount(BREACH_NAMES.length)
      await expect(page).toHaveURL(/dq=breach/)
      await check(page, 'liste (ihlalde)', '.upt-page', LIST_OWN)

      // Bağlantıya göre: gruplar, başlıkta uzun bağlantı adı tam; sağlık rozetleri
      await page.locator('[data-slot="dbq-group-conn"]').click()
      await expect(page).toHaveURL(/sort=conn/)
      const groups = page.locator('[data-slot="dbq-conn-group"]')
      await expect(groups.first()).toBeVisible()
      const longHead = page.locator('[data-slot="dbq-conn-group-head"]', { hasText: LONG_CONN })
      await expect(longHead).toBeVisible()
      await expect(longHead.locator('h3')).toContainText(LONG_CONN)
      await expect(page.locator('[data-slot="dbq-conn-group-health"]').first()).toHaveAttribute('data-state', /ok|failing|untested/)
      await check(page, 'liste (gruplu + ihlalde)', '.upt-page', LIST_OWN)
      // Süzgeç kalkınca tüm izlemeler gruplarda; adres çubuğu sayfa yeniden yüklense de aynı görünümü verir
      await focus.click()
      await expect(page.locator('[data-slot="dbq-conn-group"] [data-monitor-open]')).toHaveCount(6)
      await expect(page).not.toHaveURL(/dq=breach/)
      await page.reload()
      await expect(page.locator('[data-slot="dbq-group-conn"]')).toHaveAttribute('aria-pressed', 'true', { timeout: 20_000 })
      await expect(page.locator('[data-slot="dbq-conn-group"]')).toHaveCount(3)
      await check(page, 'liste (gruplu)', '.upt-page', LIST_OWN)
    })

    test('ayrıntı grafiği: alarm dönemleri + numaralı değişiklik işaretleri, dokununca açıklama', async ({ page }) => {
      test.setTimeout(120_000)
      await routes(page)
      await page.goto(`/?tab=dbquery&monitor=${ROWS[1].id}&mtab=chart`)
      const panel = page.locator('[data-slot="chart-panel"]')
      await expect(panel).toBeVisible({ timeout: 20_000 })
      const events = panel.locator('[data-slot="chart-events"]')
      await expect(events).toBeVisible()
      await expect(events.locator('[data-slot="chart-event"][data-kind="alarm"]')).toHaveCount(2)
      await expect(events.locator('[data-slot="chart-event"][data-kind="change"]')).toHaveCount(2)
      await expect(events.locator('[data-slot="chart-event"][data-kind="alarm"]').last()).toContainText('sürüyor')
      // Grafikte alarm dönemi gölgesi ve değişiklik çizgisi çizilir
      await expect(panel.locator('.recharts-reference-area').first()).toBeAttached()
      await expect(panel.locator('.recharts-reference-line').first()).toBeAttached()
      await events.locator('[data-slot="chart-event"][data-kind="change"]').first().click()
      const tip = page.getByRole('tooltip')
      await expect(tip).toBeVisible()
      await expect(tip).toContainText('Değişen alanlar:')
      await check(page, 'grafik', '[data-slot="response-time-chart"]')
      await page.keyboard.press('Escape')
    })

    test('kural oluşturucu: olağan dışı / donmuş + eşik önerisi', async ({ page }) => {
      test.setTimeout(150_000)
      await routes(page)
      await page.goto('/?tab=dbquery')
      const cards = page.locator('.upt-grid > [data-slot="card"]')
      await expect(cards).toHaveCount(6, { timeout: 20_000 })
      await cards.filter({ has: page.locator('[data-monitor-open]', { hasText: ROWS[0].name }) }).getByRole('button', { name: /— (edit|düzenle)$/i }).first().click()
      const dsel = '[role="dialog"]:has([data-slot="dbq-form-nav"])'
      const dlg = page.locator(dsel)
      await expect(dlg).toBeVisible()
      await dlg.locator('[data-slot="dbq-form-nav"] [data-section="evaluation"]').click()
      const ruleCard = dlg.locator('[data-slot="dbq-rule-card"]').first()
      await expect(ruleCard).toBeVisible()

      // Eşik öner: son 7 gün serisi → öneri UYGULANMADAN; Uygula eşikleri yazar
      await ruleCard.locator('[data-slot="dbq-suggest-open"]').click()
      const sug = ruleCard.locator('[data-slot="dbq-suggest"]')
      await expect(sug).toHaveAttribute('data-state', 'ok')
      await expect(sug.locator('[data-slot="dbq-suggest-summary"]')).toContainText('UYARI')
      await expect(sug.locator('[data-slot="dbq-suggest-basis"]')).toContainText('ölçüm noktası')
      await check(page, 'eşik önerisi', dsel, RULE_OWN)
      await sug.locator('[data-slot="dbq-suggest-apply"]').click()
      await expect(ruleCard.locator('[data-slot="dbq-suggest-applied"]')).toBeVisible()

      // Olağan dışı değer
      await ruleCard.getByLabel(/^(Kural türü|Rule type)$/).selectOption('ANOMALY')
      await expect(ruleCard).toHaveAttribute('data-kind', 'ANOMALY')
      await expect(ruleCard.locator('[data-slot="dbq-rule-smart-about"]')).toContainText('Örnek:')
      await expect(ruleCard.locator('[data-slot="dbq-rule-k-warning"]')).toHaveValue('2,5')
      await ruleCard.getByRole('button', { name: 'Yalnız yüksek' }).click()
      await ruleCard.locator('[data-slot="dbq-rule-season"]').selectOption('daily')
      await expect(ruleCard.locator('[data-slot="dbq-rule-summary"]')).toContainText('önceki 30 günün aynı saati')
      await check(page, 'olağan dışı değer', dsel, RULE_OWN)

      // Değer donmuş (ikinci kural)
      await dlg.locator('[data-slot="dbq-rule-add"]').click()
      const second = dlg.locator('[data-slot="dbq-rule-card"]').nth(1)
      await second.getByLabel(/^(Kural türü|Rule type)$/).selectOption('FROZEN')
      await expect(second.locator('[data-slot="dbq-rule-runs-warning"]')).toHaveValue('6')
      await second.locator('[data-slot="dbq-rule-runs-critical"]').fill('3')
      await expect(second.locator('[data-field="rules[1].runs_critical"]')).toContainText('uyarıdakinden büyük')
      await second.locator('[data-slot="dbq-rule-runs-critical"]').fill('12')
      await expect(second.locator('[data-slot="dbq-rule-summary"]')).toContainText('12 çalıştırma boyunca aynı kalırsa KRİTİK')
      await check(page, 'değer donmuş', dsel, RULE_OWN)
    })

    test('Kişisel Panom: Veri kuralları kutusu → "Şu an ihlalde" süzgeciyle sayfa', async ({ page }) => {
      test.setTimeout(120_000)
      await routes(page, {
        prefs: { dashboard: { v: 1, widgets: [{ id: 'data-rules', type: 'data_rules', size: 'md' }] } },
        perms: { 'monitoring.read': { view: true } },
      })
      await page.goto('/?tab=mydash')
      const w = page.locator('[data-slot="mydash-widget"][data-type="data_rules"]')
      await expect(w).toBeVisible({ timeout: 20_000 })
      await expect(w.locator('[data-slot="mydash-rules-list"] li')).toHaveCount(BREACH_NAMES.length + 1)
      await expect(w).toHaveAttribute('data-tone', 'danger')
      await expect(w.locator('[data-slot="mydash-rules-list"]')).toContainText(ROWS[1].name)
      await check(page, 'Panom kutusu', '[data-slot="mydash-widget"][data-type="data_rules"]')
      await w.locator('[data-slot="mydash-rules-total"]').click()
      await expect(page).toHaveURL(/tab=dbquery/)
      await expect(page).toHaveURL(/dq=breach/)
      await expect(page.locator('[data-slot="dbq-focus-breach"]')).toHaveAttribute('aria-pressed', 'true', { timeout: 20_000 })
      await expect(page.locator('.upt-grid > [data-slot="card"]')).toHaveCount(BREACH_NAMES.length)
    })
  })
}
