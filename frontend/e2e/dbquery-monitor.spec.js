// Veritabanı Sorgu İzleme (11. tür, 2026-10-31). Telefon (390, dokunmatik), tablet (768, dokunmatik) ve dizüstünde (1280):
//  1) liste — altı kart (sağlıklı, kritik eşik + alarm, duraklatılmış, hiç çalışmamış, uyarı eşiği + yavaş, bağlantı hatası),
//     kart durumu eşlemesi, uzun izleme / bağlantı adları TAM görünür, başlıkta Bağlantılar / Şablonlar;
//  2) form — yedi bölüm + bölüm atlama çubuğu, boş kayıtta hata ALANIN ALTINDA (tost değil);
//  3) ayrıntı — derin bağlantıyla Özet (amaç, satır numaralı sorgu), Kontrol geçmişi ("yalnız sorunlu" anahtarı).
// Her aşamada: ekran dışına taşan öğe yok, sayfa / pencere gövdesi yatay kaymaz, metin kırpılmaz, dokunmatikte hedefler
// ≥ 40 px. API tamamen taklit (support/monitorMocks.js). Yapı taşları (kural oluşturucu, zamanlama, sorgu düzenleyici …)
// ayrı paketin; burada yalnız sayfanın kendi parçaları ölçülür.
import { test, expect } from '@playwright/test'
import { mockApi, MONITORS } from './support/monitorMocks.js'

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]
const LONG = MONITORS.dbquery[1]
const LONG_HOLIDAY = 'Ramazan Bayramı Arifesi ve Bayramın Birinci Günü (arife yarım günü tam gün sayılır — kurum içi ek tatil)'
const NEXT_Y = new Date().getFullYear() + 1
const HOLIDAYS = [
  { day: `${NEXT_Y}-01-01`, name: 'Yılbaşı' },
  { day: `${NEXT_Y}-04-23`, name: 'Ulusal Egemenlik ve Çocuk Bayramı' },
  { day: `${NEXT_Y}-12-31`, name: LONG_HOLIDAY },
  { day: '2001-10-29', name: 'Cumhuriyet Bayramı' },
]
const json = (body) => (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })

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

/**
 * Yatayda kırpılan metin (yaprak düğümler, 12+ karakter): içerik kutusundan taşan = kesilmiş (üç nokta ancak taşınca
 * görünür — yalnız `text-overflow: ellipsis` stili olan ama sığan metin kesik sayılmaz).
 */
function clipped(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  return [...root.querySelectorAll('span, p, li, h2, h3, h4, label, button, dt, dd, code, td, th, a, strong')]
    .filter((n) => !n.closest('.sr-only') && !n.closest('select') && !n.closest('svg'))
    .filter((n) => n.childElementCount === 0 && n.textContent.trim().length > 12
      && n.clientWidth > 0 && n.scrollWidth > n.clientWidth + 1).map((n) => n.textContent.slice(0, 40))
}

/**
 * Dokunmatikte 40 px altındaki etkin hedefler (proje kuralı: vuruş alanı `::after` ile genişletilebilir — onay kutusu,
 * takım rozeti; kartın başlık düğmesi kartın TAMAMINI örten gerilmiş düğmedir, satır yüksekliği ölçülmez).
 * `only` verilirse yalnız o seçicilerin içindeki / kendisi olan öğeler ölçülür.
 */
function smallTargets(arg) {
  const { sel, only } = typeof arg === 'string' ? { sel: arg, only: null } : arg
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  const nodes = only
    ? only.flatMap((o) => [...root.querySelectorAll(o)].flatMap((el) => (el.matches('button, input, select, [role="combobox"]') ? [el] : [...el.querySelectorAll('button, select, input:not([type="hidden"]), [role="combobox"]')])))
    : [...root.querySelectorAll('button, select, input:not([type="hidden"]), [role="combobox"], a[href]')]
  for (const b of nodes) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0 || b.disabled) continue
    const cs = getComputedStyle(b)
    if (cs.visibility === 'hidden' || cs.display === 'none' || b.getAttribute('aria-hidden') === 'true') continue
    if (b.closest('[hidden]') || b.hasAttribute('data-monitor-open') || b.getAttribute('role') === 'checkbox') continue
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

/** Formun TÜRE ÖZGÜ denetimleri — ortak izleme formu parçaları (ad / takım / grup alanı, bildirim kanalları, alt çubuk)
 * on türde aynıdır ve kendi kapılarında ölçülür. */
const FORM_OWN = ['[data-slot="dbq-form-nav"]', '[data-slot="dbq-test-run"]', '[data-slot="dbq-plan-run"]',
  '[data-slot="dbq-save-template"]', '[data-slot="dbq-subject-placeholders"]', '[data-slot="dbq-confirm-toggle"]']

for (const vp of VIEWPORTS) {
  test.describe(`Veritabanı sorgu izleme — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    const check = async (page, stage, sel, root = sel, only = null) => {
      await page.waitForTimeout(250)
      const m = await page.evaluate(measure, sel)
      expect(m.offenders, `${stage} @${vp.name}: ekran dışına taşan öğe`).toEqual([])
      expect(m.pageOverflow, `${stage} @${vp.name}: sayfa yatay kayıyor`).toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, root), `${stage} @${vp.name}: kırpılan metin`).toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, { sel: root, only }), `${stage} @${vp.name}: küçük dokunma hedefi`).toEqual([])
    }

    test('liste: kart durumları, uzun adlar tam, başlık eylemleri; sığar, kırpılmaz', async ({ page }) => {
      test.setTimeout(120_000)
      await mockApi(page)
      await page.goto('/?tab=dbquery')
      const cards = page.locator('.upt-grid > [data-slot="card"]')
      await expect(cards).toHaveCount(6, { timeout: 20_000 })
      const card = (name) => cards.filter({ has: page.locator('[data-monitor-open]', { hasText: name }) })
      // Durum eşlemesi: kritik eşik → kırmızı, uyarı eşiği → sarı, bağlantı hatası → kırmızı, hiç çalışmamış → bilinmiyor
      await expect(card(LONG.name)).toHaveAttribute('data-status', 'down')
      await expect(card('Kart başvuru onay süresi')).toHaveAttribute('data-status', 'warn')
      await expect(card('Raporlama veri ambarı yükleme gecikmesi')).toHaveAttribute('data-status', 'down')
      await expect(card('Yeni eklenen müşteri kontrolü')).toHaveAttribute('data-status', 'unknown')
      await expect(card('Gece mutabakat dosyası')).toHaveAttribute('data-inactive', 'true')
      // Uzun izleme adı ve uzun bağlantı adı TAM metin (kesilmez — sarar)
      await expect(card(LONG.name).locator('[data-monitor-open]')).toHaveText(LONG.name)
      await expect(card(LONG.name).locator('[data-slot="dbq-connection"]')).toContainText(LONG.profile.name)
      await expect(card(LONG.name).locator('[data-slot="dbq-level"]')).toHaveAttribute('data-level', 'CRITICAL')
      await expect(card(LONG.name).locator('[data-slot="dbq-rows-value"]')).toHaveText(/^≥ 1[.,]?000$/)
      await expect(card('Raporlama veri ambarı yükleme gecikmesi').locator('[data-slot="dbq-error"]')).toContainText('ORA-12541')
      // Başlık: Bağlantılar + Şablonlar
      await expect(page.locator('[data-slot="dbq-open-connections"]')).toBeVisible()
      await expect(page.locator('[data-slot="dbq-open-templates"]')).toBeVisible()
      await check(page, 'liste', '.upt-page', '.upt-grid')
      // Toplu işlem: takım değiştir yerine bilgi satırı (O6 — bağlantı seçimi gerekir); çubuk sığar, not kırpılmaz
      await card(LONG.name).getByRole('checkbox').first().click()
      const bulk = page.locator('[data-slot="bulk-action-bar"]')
      await expect(bulk).toBeVisible()
      await expect(bulk.locator('[data-slot="bulk-team-note"]')).toContainText(/Takım toplu değiştirilemez|can.t be changed in bulk/)
      await expect(bulk.getByRole('combobox', { name: /^(Takım|Team)$/ })).toHaveCount(0)
      await check(page, 'liste (toplu çubuk)', '.upt-page', '[data-slot="bulk-action-bar"]')
      await bulk.getByRole('button', { name: /Seçimi temizle|Clear selection/ }).click()
      if (vp.touch) {
        expect(await page.evaluate(smallTargets, { sel: '.upt-page', only: ['[data-slot="dbq-open-connections"]', '[data-slot="dbq-open-templates"]'] }),
          `başlık @${vp.name}: küçük dokunma hedefi`).toEqual([])
      }
      // Kompakt yoğunlukta da sığar
      const compact = page.getByRole('radio', { name: /Compact|Kompakt/ })
      if (await compact.count()) {
        await compact.first().click()
        await expect(page.locator('[data-slot="dbq-compact"]').first()).toBeVisible()
        await check(page, 'liste (kompakt)', '.upt-page', '.upt-grid')
      }
    })

    test('form: yedi bölüm + atlama çubuğu, boş kayıtta hata alanın altında; sığar, kırpılmaz', async ({ page }) => {
      test.setTimeout(120_000)
      // Takım üyesi: takım alanı sabit, Kaydet etkin (yönetici için takım seçilene dek kapalı)
      await mockApi(page, { role: 'USER', globalAdmin: false })
      await page.route((u) => new URL(u).pathname === '/api/admin/holidays', json({ success: true, data: { days: HOLIDAYS, can_edit: false } }))
      await page.goto('/?tab=dbquery')
      await expect(page.locator('.upt-grid > [data-slot="card"]')).toHaveCount(6, { timeout: 20_000 })
      await page.getByRole('button', { name: /^(New Monitor|Yeni İzleme)$/ }).first().click()
      const dlg = page.locator('[role="dialog"]:has([data-slot="dbq-form-nav"])')
      await expect(dlg).toBeVisible()
      await expect(dlg.locator('[data-dbq-section]')).toHaveCount(7)
      await expect(dlg.locator('[data-slot="dbq-form-nav"] button')).toHaveCount(7)
      const dsel = '[role="dialog"]:has([data-slot="dbq-form-nav"])'
      await check(page, 'form', dsel, dsel, FORM_OWN)
      // Atlama çubuğu: "Zamanlama" düğmesi bölümü görünür kılar
      await dlg.locator('[data-slot="dbq-form-nav"] button').nth(4).click()
      await expect(dlg.locator('[data-dbq-section="schedule"]')).toBeInViewport()
      // Boş kayıt → ad alanının ALTINDA hata; istek gitmez
      let posted = 0
      await page.route((u) => new URL(u).pathname === '/api/monitoring/dbquery', (route) => {
        if (route.request().method() === 'POST') posted += 1
        return route.fallback()
      })
      await dlg.getByRole('button', { name: /^(Save|Kaydet)$/ }).click()
      await expect(dlg.locator('[data-field="name"]')).toContainText(/This field is required|Bu alan zorunlu/)
      await expect(dlg.locator('[data-field="purpose"]')).toContainText(/5–2000/)
      await expect(dlg.locator('[data-field="name"] input')).toBeFocused()
      expect(posted).toBe(0)
      await check(page, 'form (hatalar)', dsel, dsel, FORM_OWN)
      // Tatil günleri (O4): yönetici olmayana ayar bağlantısı YOK ("Erişim yok" açardı); günler form içinde salt okunur
      const hol = dlg.locator('[data-slot="dbq-skip-holidays"]')
      await hol.scrollIntoViewIfNeeded()
      await expect(hol.locator('[data-slot="dbq-holidays-settings-link"]')).toHaveCount(0)
      await hol.locator('[data-slot="dbq-holidays-view"]').click()
      const pop = page.locator('[data-slot="dbq-holidays-popover"][data-load="ok"]')
      await expect(pop).toBeVisible()
      await expect(pop.locator('[data-slot="dbq-holidays-list"] li')).toHaveCount(4)
      await expect(pop.locator('[data-slot="dbq-holidays-list"] li').first()).toContainText('Yılbaşı')
      await expect(pop).toContainText(LONG_HOLIDAY)
      const pb = await pop.boundingBox()
      expect(pb.x, `tatil penceresi @${vp.name}: soldan taşıyor`).toBeGreaterThanOrEqual(0)
      expect(pb.x + pb.width, `tatil penceresi @${vp.name}: sağdan taşıyor`).toBeLessThanOrEqual(vp.width + 1)
      expect(await page.evaluate(clipped, '[data-slot="dbq-holidays-popover"]'), `tatil penceresi @${vp.name}: kırpılan metin`).toEqual([])
      if (vp.touch) {
        expect(await page.evaluate(smallTargets, { sel: dsel, only: ['[data-slot="dbq-holidays-view"]'] }), `tatil @${vp.name}: küçük dokunma hedefi`).toEqual([])
      }
      await page.keyboard.press('Escape')
      await expect(pop).toHaveCount(0)
      // Escape yalnız pencereyi kapattı; form AÇIK (izleme formu Escape ile kapanmaz)
      await expect(dlg).toBeVisible()
      if (vp.name === 'phone') {
        const box = await dlg.boundingBox()
        // Ortak izleme formu telefonda kenarlarda küçük boşlukla ekranı kaplar — ekran dışına taşmaz, dar kalmaz
        expect(box.x, 'telefonda form sol kenardan taşıyor').toBeGreaterThanOrEqual(0)
        expect(box.x + box.width, 'telefonda form sağ kenardan taşıyor').toBeLessThanOrEqual(vp.width + 1)
        expect(Math.round(box.width), 'telefonda form dar').toBeGreaterThanOrEqual(vp.width - 24)
      }
    })

    test('paylaşımla düzenleme (K2): kilit notları sığar; deneme / maliyet / şablon düğmeleri yok', async ({ page }) => {
      test.setTimeout(120_000)
      await mockApi(page, { role: 'USER', globalAdmin: false })
      const shared = MONITORS.dbquery.map((m, i) => (i === 1 ? { ...m, routing_locked: true, shared_access: true, can_edit: true, can_delete: false } : m))
      await page.route((u) => new URL(u).pathname === '/api/monitoring/dbquery', (route) => (route.request().method() === 'GET'
        ? json({ success: true, data: shared })(route) : route.fallback()))
      await page.goto('/?tab=dbquery')
      await expect(page.locator('.upt-grid > [data-slot="card"]')).toHaveCount(6, { timeout: 20_000 })
      const sharedCard = page.locator('.upt-grid > [data-slot="card"]').filter({ has: page.locator('[data-monitor-open]', { hasText: LONG.name }) })
      await sharedCard.getByRole('button', { name: /— (edit|düzenle)$/i }).first().click()
      const dsel = '[role="dialog"]:has([data-slot="dbq-form-nav"])'
      const dlg = page.locator(dsel)
      await expect(dlg).toBeVisible()
      await expect(dlg.locator('[data-slot="dbq-grant-locked-banner"]')).toBeVisible()
      await expect(dlg.locator('[data-slot="dbq-grant-locked"]')).toHaveCount(4)
      await expect(dlg.locator('[data-slot="dbq-connection-locked"]')).toBeVisible()
      await expect(dlg.locator('[data-slot="dbq-test-run"]')).toHaveCount(0)
      await expect(dlg.locator('[data-slot="dbq-plan-run"]')).toHaveCount(0)
      await expect(dlg.locator('[data-slot="dbq-save-template"]')).toHaveCount(0)
      await expect(dlg.locator('[data-slot="dbq-sql-editor"] textarea')).toBeDisabled()
      await expect(dlg.locator('[data-field="query_timeout_seconds"] input')).toBeDisabled()
      await check(page, 'form (paylaşım)', dsel, dsel, ['[data-slot="dbq-form-nav"]', '[data-slot="dbq-subject-placeholders"]'])
      await page.screenshot({ path: test.info().outputPath(`dbquery-grant-form-${vp.name}.png`), fullPage: false })
    })

    test('ayrıntı: derin bağlantı → Özet (amaç, satır numaralı sorgu) → Kontrol geçmişi; sığar, kırpılmaz', async ({ page }) => {
      test.setTimeout(120_000)
      await mockApi(page)
      await page.goto(`/?tab=dbquery&monitor=${LONG.id}`)
      const dsel = '[role="dialog"]:has([data-slot="dbq-detail-title"])'
      const dlg = page.locator(dsel)
      await expect(dlg).toBeVisible({ timeout: 20_000 })
      await expect(dlg.locator('[data-slot="dbq-detail-purpose"]')).toContainText(LONG.purpose)
      await expect(dlg.locator('[data-slot="dbq-sql-line"]')).toHaveCount(3)
      await expect(dlg.locator('[data-slot="dbq-detail-connection"]')).toContainText(LONG.profile.name)
      // Bağlantı takımın listesinde yok (taklit boş liste) → "bulunamadı", asla yeşil değil
      await expect(dlg.locator('[data-slot="dbq-health"]')).not.toHaveAttribute('data-state', 'ok')
      await check(page, 'ayrıntı (özet)', dsel)
      const tabs = dlg.locator('[role="tablist"]').first().locator('[role="tab"]')
      await expect(tabs).toHaveCount(6)
      await tabs.nth(1).click()
      await expect(dlg.locator('[data-slot="dbq-hist-problem"]')).toBeVisible({ timeout: 10_000 })
      // Geçmiş ızgarası / 24 saatlik şerit ortak bileşen (on tür, kendi kapısı) — burada türün kendi denetimleri ölçülür
      await check(page, 'ayrıntı (geçmiş)', dsel, dsel, ['[data-slot="dbq-hist-problem"]', '[data-slot="dbq-hist-toggle"]'])
      await page.screenshot({ path: test.info().outputPath(`dbquery-detail-${vp.name}.png`), fullPage: false })
    })
  })
}

// ── r177 (2026-10-31): değerlendirilemedi kartı, deneme kolonundan kural / maske, COUNT tuzağı, geriye dönük deneme, satır
// sarma, bölüm çubuğu hata sayısı, yerleşik şablonlar, bağlantı formunun salt-okur hesap betiği / TCPS / zayıf SSL / hesap
// yetkisi. Her aşamada sığma + kırpılma + (dokunmatikte) türe özgü hedefler ölçülür.
const R177_OWN = [...FORM_OWN, '[data-slot="dbq-sql-wrap"]', '[data-slot="dbq-col"]', '[data-slot="dbq-rule-convert"]',
  '[data-slot="dbq-backtest-run"]', '[data-slot="dbq-suggested-masks"]', '[data-slot="dbq-test-warnings"]']
const R177_ROWS = MONITORS.dbquery.map((m, i) => {
  if (i === 0) {
    return { ...m, sql_text: 'SELECT count(*) AS bekleyen, max(musteri_tckn) AS musteri_tckn, max(son_islem) AS son_islem\nFROM orders\nWHERE status = 0',
      rules: [{ id: 'r1', label: 'Bekleyen sayısı', kind: 'ROW_COUNT', operator: 'GT', warning: 10, critical: 50 }] }
  }
  if (i === 3) {
    return { ...m, status: 'UNKNOWN', unknown_runs: 3, last_check: new Date(Date.now() - 120_000).toISOString().slice(0, 19), breach_level: 'NONE',
      rule_results: [{ id: 'r1', label: 'Bekleyen sayısı', kind: 'COLUMN_VALUE', evaluated: false, note: 'column_missing', level: 'NONE', passed: null }] }
  }
  return m
})
const TEST_RESULT = {
  status: 'OK', ok: true, duration_ms: 182, row_count: 1, row_count_capped: false,
  columns: [{ name: 'bekleyen', type: 'int8' }, { name: 'musteri_tckn', type: 'varchar' }, { name: 'son_islem', type: 'timestamptz' }],
  rows: [['12', '•••', '2026-10-31 09:58:00']], rule_results: [],
  warnings: [{ code: 'ROW_COUNT_ON_AGGREGATE', rule_id: 'r1' }, { code: 'SENSITIVE_UNMASKED', columns: ['musteri_tckn'] }],
  suggested_masks: ['musteri_tckn'],
}

for (const vp of VIEWPORTS) {
  test.describe(`Veritabanı sorgu izleme r177 — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    const check = async (page, stage, sel, root = sel, only = null) => {
      await page.waitForTimeout(250)
      const m = await page.evaluate(measure, sel)
      expect(m.offenders, `${stage} @${vp.name}: ekran dışına taşan öğe`).toEqual([])
      expect(m.pageOverflow, `${stage} @${vp.name}: sayfa yatay kayıyor`).toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, root), `${stage} @${vp.name}: kırpılan metin`).toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, { sel: root, only }), `${stage} @${vp.name}: küçük dokunma hedefi`).toEqual([])
    }
    const listRoute = async (page) => {
      await page.route((u) => new URL(u).pathname === '/api/monitoring/dbquery', (route) => (route.request().method() === 'GET'
        ? json({ success: true, data: R177_ROWS })(route) : route.fallback()))
    }

    test('düzenleme: değerlendirilemedi kartı · deneme kolonundan kural / maske · COUNT tuzağı · geriye dönük deneme · sarma · hata sayısı', async ({ page }) => {
      test.setTimeout(150_000)
      await mockApi(page, { role: 'USER', globalAdmin: false })
      await listRoute(page)
      await page.route((u) => new URL(u).pathname === '/api/monitoring/dbquery/sql-check', json({ success: true, data: { ok: true, violations: [] } }))
      let tested = 0
      await page.route((u) => new URL(u).pathname === '/api/monitoring/dbquery/test', (route) => { tested += 1; return json({ success: true, data: TEST_RESULT })(route) })
      const backtests = []
      await page.route((u) => /^\/api\/monitoring\/dbquery\/\d+\/backtest$/.test(new URL(u).pathname), (route) => {
        backtests.push(JSON.parse(route.request().postData() || '{}'))
        return json({ success: true, data: { days: 30, runs: 8640, evaluated: 8600, would_breach: { warning: 12, critical: 2 }, would_open_alarms: 3,
          first_at: '2026-10-02T09:00:00', last_at: '2026-10-30T18:00:00', per_rule: [{ id: 'r1', evaluated: 8600, warning: 12, critical: 2 }] } })(route)
      })
      await page.goto('/?tab=dbquery')
      const cards = page.locator('.upt-grid > [data-slot="card"]')
      await expect(cards).toHaveCount(6, { timeout: 20_000 })
      // UNKNOWN: nötr kart (yeşil değil) + kısa açıklama; sığar, kırpılmaz
      const unk = cards.filter({ has: page.locator('[data-monitor-open]', { hasText: 'Yeni eklenen müşteri kontrolü' }) })
      await expect(unk).toHaveAttribute('data-status', 'unknown')
      await expect(unk.locator('[data-slot="dbq-unevaluated"]')).toBeVisible()
      await check(page, 'liste (değerlendirilemedi)', '.upt-page', '.upt-grid', ['[data-slot="dbq-unevaluated"]'])

      // Düzenleme formu
      const first = cards.filter({ has: page.locator('[data-monitor-open]', { hasText: 'Sipariş kuyruğu bekleyenleri' }) })
      await first.getByRole('button', { name: /— (edit|düzenle)$/i }).first().click()
      const dsel = '[role="dialog"]:has([data-slot="dbq-form-nav"])'
      const dlg = page.locator(dsel)
      await expect(dlg).toBeVisible()
      // Satır sarma: telefonda varsayılan açık (yatay kaydırma yok), geniş ekranda kapalı; düğme çevirir
      const wrap = dlg.locator('[data-slot="dbq-sql-wrap"]')
      const ta = dlg.locator('[data-slot="dbq-sql-editor"] textarea')
      await expect(wrap).toHaveAttribute('aria-pressed', vp.width < 768 ? 'true' : 'false')
      await wrap.click()
      await expect(wrap).toHaveAttribute('aria-pressed', vp.width < 768 ? 'false' : 'true')
      if (vp.width < 768) await expect(ta).not.toHaveAttribute('data-wrap', 'true')
      else await expect(ta).toHaveAttribute('data-wrap', 'true')
      await wrap.click()

      // Deneme → uyarılar + kolon çipleri; sunucu uyarısı ROW_COUNT_ON_AGGREGATE satır sayısı kuralında tuzak bandı açar
      await dlg.locator('[data-slot="dbq-test-run"]').click()
      await expect(dlg.locator('[data-slot="dbq-test"]')).toBeVisible()
      expect(tested).toBe(1)
      await expect(dlg.locator('[data-slot="dbq-test-warning"][data-code="SENSITIVE_UNMASKED"]')).toBeVisible()
      await expect(dlg.locator('[data-slot="dbq-col"]')).toHaveCount(3)
      await expect(dlg.locator('[data-slot="dbq-rule-count-trap"]')).toBeVisible()
      await check(page, 'form (deneme sonucu)', dsel, dsel, R177_OWN)

      // "Kolon değeri kuralına çevir": tuzak kapanır
      await dlg.locator('[data-slot="dbq-rule-convert"]').click()
      await expect(dlg.locator('[data-slot="dbq-rule-count-trap"]')).toHaveCount(0)

      // Geriye dönük deneme: formdaki kurallarla istek; özet + açılacak alarm sayısı
      const bt = dlg.locator('[data-slot="dbq-backtest"]')
      await bt.scrollIntoViewIfNeeded()
      await bt.locator('[data-slot="dbq-backtest-run"]').click()
      await expect(bt).toHaveAttribute('data-state', 'ok')
      await expect(bt.locator('[data-slot="dbq-backtest-alarms"]')).toContainText('3')
      expect(backtests).toHaveLength(1)
      expect(backtests[0].days).toBe(30)
      expect(backtests[0].rules[0]).toMatchObject({ kind: 'COLUMN_VALUE', column: 'bekleyen' })
      await check(page, 'form (geriye dönük deneme)', dsel, dsel, R177_OWN)

      // Kolon menüsü → tazelik kuralı eklenir; geriye dönük sonuç "eski" işaretlenir
      await dlg.locator('[data-slot="dbq-col"][data-kind="time"]').click()
      await page.locator('[role="menuitem"][data-action="rule:FRESHNESS"]').click()
      await expect(dlg.locator('[data-slot="dbq-rule-card"]')).toHaveCount(2)
      await expect(dlg.locator('[data-slot="dbq-rule-freshness"]')).toBeVisible()
      await expect(bt.locator('[data-slot="dbq-backtest-stale"]')).toBeVisible()

      // Hassas kolon önerisi tek tıkla maskeye; öneri bandı kapanır, kolon çipinde "maskeli"
      const sugg = dlg.locator('[data-slot="dbq-suggested-masks"]')
      await sugg.scrollIntoViewIfNeeded()
      await sugg.locator('[data-slot="dbq-suggested-mask"]').first().click()
      await expect(sugg).toHaveCount(0)
      await expect(dlg.locator('[data-slot="dbq-col"] [data-slot="dbq-col-masked"]')).toHaveCount(1)

      // Bölüm çubuğu: boş ad → kaydet → "Temel" düğmesinde hata sayısı; çubuk kaydırınca da görünür (yapışkan)
      await dlg.locator('[data-field="name"] input').fill('')
      await dlg.getByRole('button', { name: /^(Save|Kaydet)$/ }).click()
      const nav = dlg.locator('[data-slot="dbq-form-nav"]')
      await expect(nav.locator('[data-section="basic"]')).toHaveAttribute('data-errors', '1')
      await expect(nav.locator('[data-section="basic"] [data-slot="dbq-form-nav-errors"]')).toHaveText('1')
      await dlg.locator('[data-dbq-section="alerting"]').scrollIntoViewIfNeeded()
      await expect(nav).toBeInViewport()
      await check(page, 'form (hata sayısı)', dsel, dsel, R177_OWN)
      await page.screenshot({ path: test.info().outputPath(`dbquery-r177-form-${vp.name}.png`), fullPage: false })
    })

    test('şablon yöneticisi: yerleşik şablonlar (DB türüne göre) → "Bu şablonla izleme oluştur"; sığar, kırpılmaz', async ({ page }) => {
      test.setTimeout(120_000)
      await mockApi(page, { role: 'USER', globalAdmin: false })
      await page.goto('/?tab=dbquery')
      await expect(page.locator('.upt-grid > [data-slot="card"]')).toHaveCount(6, { timeout: 20_000 })
      await page.locator('[data-slot="dbq-open-templates"]').click()
      const msel = '[role="dialog"]:has([data-slot="dbq-tpl-manager"])'
      const mgr = page.locator(msel)
      const builtins = mgr.locator('[data-slot="dbq-tpl-builtins"]')
      await expect(builtins).toBeVisible({ timeout: 10_000 })
      await expect(builtins.locator('[data-slot="dbq-tpl-builtin-row"]')).toHaveCount(4)
      await builtins.getByRole('button', { name: 'SQL Server', exact: true }).click()
      await check(page, 'şablon yöneticisi', msel, msel, ['[data-slot="dbq-tpl-builtins"]', '[data-slot="dbq-tpl-new"]'])
      await builtins.locator('[data-slot="dbq-tpl-create-monitor"]').first().click()
      const dsel = '[role="dialog"]:has([data-slot="dbq-form-nav"])'
      const dlg = page.locator(dsel)
      await expect(dlg).toBeVisible()
      await expect(mgr).toHaveCount(0)
      await expect(dlg.locator('[data-slot="dbq-sql-editor"] textarea')).toHaveValue(/WITH \(NOLOCK\)/)
      await expect(dlg.locator('[data-slot="dbq-rule-card"]')).toHaveCount(1)
      await check(page, 'form (şablondan)', dsel, dsel, FORM_OWN)
    })

    test('bağlantı formu: salt-okur hesap betiği · Oracle TCPS · zayıf SSL uyarısı · deneme hesap yetkisi; sığar, kırpılmaz', async ({ page }) => {
      test.setTimeout(120_000)
      await mockApi(page)
      const drafts = []
      await page.route((u) => new URL(u).pathname === '/api/monitoring/dbquery/connections/test', (route) => {
        drafts.push(JSON.parse(route.request().postData() || '{}'))
        return json({ success: true, data: { ok: true, duration_ms: 42, server_version: 'PostgreSQL 16.4',
          privileges: { checked: true, read_only: false, findings: [{ code: 'CAN_WRITE_TABLES' }, { code: 'CAN_CREATE' }] } } })(route)
      })
      await page.goto('/?tab=dbquery')
      await expect(page.locator('.upt-grid > [data-slot="card"]')).toHaveCount(6, { timeout: 20_000 })
      await page.locator('[data-slot="dbq-open-connections"]').click()
      const msel = '[role="dialog"]:has([data-slot="dbq-conn-manager"])'
      const mgr = page.locator(msel)
      await mgr.locator('[data-slot="dbq-conn-new"]').click()
      const form = mgr.locator('[data-slot="dbq-conn-form"]')
      await expect(form).toBeVisible()
      // Betik kapalı başlar; açılınca kendi kabında kayar (sayfa kaymaz)
      const dba = form.locator('[data-slot="dbq-conn-dba"]')
      await dba.getByRole('button', { name: /Read-only account script|Salt-okur hesap betiği/ }).click()
      const script = dba.locator('[data-slot="dbq-conn-dba-script"]')
      await expect(script).toContainText('CREATE ROLE')
      expect(await script.evaluate((el) => getComputedStyle(el).overflowX)).toBe('auto')
      // Oracle: TCPS açılınca port 2484
      await form.getByRole('button', { name: 'Oracle', exact: true }).click()
      const tcps = form.locator('[data-slot="dbq-conn-tcps"]')
      await expect(tcps).toBeVisible()
      await tcps.getByRole('switch').click()
      await expect(form.locator('[data-field="port"] input')).toHaveValue('2484')
      await check(page, 'bağlantı formu (Oracle)', msel, msel, ['[data-slot="dbq-conn-tcps"]', '[data-slot="dbq-conn-dba"]'])
      // PostgreSQL + prefer → zayıf SSL uyarısı
      await form.getByRole('button', { name: 'PostgreSQL', exact: true }).click()
      await form.locator('[data-field="ssl_mode"] select').selectOption('prefer')
      await expect(form.locator('[data-slot="dbq-conn-ssl-weak"][data-mode="prefer"]')).toBeVisible()
      // Taslak deneme: yazabilen hesap uyarısı + bulgular
      await form.locator('[data-field="name"] input').fill('Sipariş DB — salt okunur raporlama hesabı (PostgreSQL üretim)')
      await form.locator('[data-field="host"] input').fill('db.example.com')
      await form.locator('[data-field="database_name"] input').fill('orders')
      await form.locator('[data-field="username"] input').fill('sitemonitor_ro')
      await mgr.locator('[data-slot="dbq-conn-test-draft"]').click()
      await expect.poll(() => drafts.length).toBe(1)
      const priv = mgr.locator('[data-slot="dbq-conn-priv"]')
      await expect(priv).toHaveAttribute('data-state', 'writable')
      await expect(priv.locator('[data-slot="dbq-conn-priv-findings"] li')).toHaveCount(2)
      await check(page, 'bağlantı formu (yetki)', msel, msel, ['[data-slot="dbq-conn-dba"]', '[data-slot="dbq-conn-test-draft"]'])
      await page.screenshot({ path: test.info().outputPath(`dbquery-r177-conn-${vp.name}.png`), fullPage: false })
    })
  })
}
