// Anahtar kelime izleme — çoklu kural, içerik sekmesi ve sentetik köprüsü (kw fe2 Aşama 2, 2026-11-05). Telefon (390, dokunmatik),
// tablet (768, dokunmatik) ve dizüstünde (1280):
//  1) form: kural ekle / sırala (yukarı), VE / VEYA, düzenli ifade yapı hatası ALANIN ALTINDA, CSS kapsamı, iki seviyeli eşik,
//     boyut + içerik değişimi; kayıtta sunucunun `rules[N].pattern` hatası ilgili alanın altında; gövde `rules` + `ruleLogic`;
//  2) kart: "2 kural · VE" rozeti + kural satırları; detay "İçerik" sekmesi: son kural sonuçları, grafik (boş kova boşluk),
//     temel bilgisi + "Yeni temel al" (onaylı) + fark, canlı eşleşmeler;
//  3) "Sentetik izlemeye dönüştür" → taslak `new` anahtarına yazılır → Sentetik İzleme `sc_open=draft` ile açılır, form
//     dönüştürülen betikle gelir, parametre URL'den silinir.
// Her aşamada: ekran dışına taşan öğe yok, sayfa yatay kaymaz, bu paketin öğelerinde metin kırpılmaz, dokunmatikte düğmeler
// ≥ 40 px. Ölçümler animasyonlar bittikten sonra (document.getAnimations — sonlu olanlar). API tamamen taklit (sözleşme tel biçimi).
import { test, expect } from '@playwright/test'
import { mockApi, MONITORS } from './support/monitorMocks.js'

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]
const LONG_LABEL = 'Bireysel internet şubesi giriş sayfasındaki karşılama metni (oturum açma formu)'
const json = (body, status = 200) => (route) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
const iso = (msAgo) => new Date(Date.now() - msAgo).toISOString().slice(0, 19)

/** Görünüm dışına taşan (kendi kaydırma kabında olmayan) öğeler + sayfa düzeyi yatay taşma. */
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

/** Yatayda kırpılan metin (yaprak düğümler, 12+ karakter; bilinçli iki satır kırpması `line-clamp` hariç). */
function clipped(sels) {
  const out = []
  for (const sel of sels) {
    for (const root of document.querySelectorAll(sel)) {
      for (const n of root.querySelectorAll('span, p, li, label, button, a, strong, code, div, h4, dd, dt')) {
        if (n.closest('.sr-only') || n.closest('svg') || n.childElementCount !== 0) continue
        if (n.textContent.trim().length > 12 && n.clientWidth > 0 && n.scrollWidth > n.clientWidth + 1) out.push(n.textContent.slice(0, 40))
      }
    }
  }
  return out
}

/** Dokunmatikte 40 px altındaki düğme / bağlantılar (anahtar ve radyo: satırı / kartı; onay kutusu kendi `::after` alanıyla). */
function smallTargets(sels) {
  const out = []
  for (const sel of sels) {
    for (const root of document.querySelectorAll(sel)) {
      for (const b of root.querySelectorAll('button, a[href]')) {
        const r = b.getBoundingClientRect()
        if (r.width === 0 || r.height === 0 || b.disabled) continue
        const cs = getComputedStyle(b)
        if (cs.visibility === 'hidden' || cs.display === 'none' || b.closest('[hidden]')) continue
        const role = b.getAttribute('role')
        if (role === 'checkbox') continue
        let h = r.height
        if (role === 'radio' || role === 'switch') {
          const card = b.closest('label')
          if (card) h = Math.max(h, card.getBoundingClientRect().height)
        }
        if (h < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)}" ${Math.round(h)}`)
      }
    }
  }
  return out
}

/** Sonlu animasyonlar (Radix aç / kapa, açılır içerik) bitene dek bekler — ölçüm yarım karede alınmasın. */
async function settle(page) {
  await page.waitForFunction(() => document.getAnimations().every((a) => {
    const end = a.effect?.getComputedTiming?.().endTime
    return a.playState !== 'running' || !Number.isFinite(end)
  }), null, { timeout: 5_000 })
}

const RULES = [
  { id: 'r1', kind: 'TEXT', pattern: 'Hoş geldiniz', case_sensitive: false, operator: 'GTE', count: 1, critical: null, scope: null, label: LONG_LABEL },
  { id: 'r-ab12', kind: 'REGEX', pattern: 'Hata\\s+\\d{3}', case_sensitive: false, operator: 'LT', count: 5, critical: { operator: 'LT', count: 20 }, scope: { type: 'CSS', value: '#durum > .mesaj' }, label: null },
]

/** Keyword satırları: ilki çok kurallı (be2 tel biçimi), diğerleri eski tek kural (sunucunun türettiği r1). */
function keywordRows() {
  return MONITORS.keyword.map((m, i) => {
    const common = {
      ...m, can_edit: true, can_check: true, can_delete: true, operator: 'GTE', match_count: 1, use_proxy: 'AUTO', timeout_ms: 10000,
      alert_level: 'HIGH', notify_email: true, notify_webhook: true, noc_notify: false, noc_group_ids: [], match_scope: 'RAW',
      normalize_ws: false, method: 'GET', follow_redirects: true, case_sensitive: false, min_bytes: null, max_bytes: null,
      change_detect: false, change_mask: [], change_selector: null, rule_logic: 'AND',
    }
    if (i !== 0) return { ...common, rules: [{ id: 'r1', kind: 'TEXT', pattern: m.keyword, case_sensitive: false, operator: 'GTE', count: 1, critical: null, scope: null, label: null }], rules_custom: false }
    return {
      ...common, name: 'Portal (çok kurallı)', status: 'down', keyword: RULES[0].pattern, rules: RULES, rules_custom: true,
      rule_results: [{ id: 'r1', kind: 'TEXT', pattern_preview: 'Hoş geldiniz', count: 2, ok: true, level: 'NONE' },
        { id: 'r-ab12', kind: 'REGEX', pattern_preview: 'Hata\\s+\\d{3}', count: 21, ok: false, level: 'CRITICAL', note: 'SCAN_TRUNCATED' }],
      breach_level: 'CRITICAL', min_bytes: 1000, max_bytes: 2_000_000, body_bytes: 48_000, change_detect: true, change_mask: ['DIGITS'],
      change_selector: 'main', content_changed: true,
      change_baseline: { taken_at: iso(86_400_000), taken_by_name: 'Ayşe Yılmaz (Dijital Kanallar / Uzman)', source: 'MANUAL', bytes: 47_500 },
    }
  })
}

for (const vp of VIEWPORTS) {
  test.describe(`Keyword kuralları — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    const check = async (page, stage, dsel, own) => {
      await settle(page)
      const m = await page.evaluate(measure, dsel)
      expect(m.offenders, `${stage} @${vp.name}: ekran dışına taşan öğe`).toEqual([])
      expect(m.pageOverflow, `${stage} @${vp.name}: sayfa yatay kayıyor`).toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, own), `${stage} @${vp.name}: kırpılan metin`).toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, own), `${stage} @${vp.name}: küçük dokunma hedefi`).toEqual([])
    }

    test('form: kural ekle / sırala, VE-VEYA, düzenli ifade hatası, kapsam, iki eşik, boyut + değişim; sunucu `rules[N]` hatası', async ({ page }) => {
      test.setTimeout(150_000)
      const rows = keywordRows()
      await mockApi(page, { monitors: { ...MONITORS, keyword: rows } })
      const puts = []
      let failFirst = true
      await page.route((u) => /^\/api\/monitoring\/keyword\/\d+$/.test(new URL(u).pathname), (route) => {
        if (route.request().method() !== 'PUT') return route.fallback()
        const body = JSON.parse(route.request().postData() || '{}')
        puts.push(body)
        if (failFirst) {
          failFirst = false
          return json({ success: false, code: 'VALIDATION_FAILED', field: 'rules[1].pattern',
            error: 'Desen çok karmaşık (RE2 program sınırı aşıldı) — sadeleştirin.' }, 400)(route)
        }
        return json({ success: true, data: { ...rows[0], ...body } })(route)
      })
      await page.route((u) => /^\/api\/monitoring\/keyword\/\d+\/check$/.test(new URL(u).pathname), json({ success: true, data: rows[0] }))
      await page.goto('/?tab=keyword')
      const card = page.locator('.upt-grid > [data-slot="card"]').filter({ hasText: 'Portal (çok kurallı)' })
      await expect(card).toBeVisible({ timeout: 20_000 })
      // Kart: çok kural rozeti + kural satırları (ihlal seviyesi, uzun etiket iki satırda — tamamı balonda)
      await expect(card.locator('[data-slot="keyword-rules-badge"]')).toContainText(/2 (rules|kural) · (AND|VE)/)
      await expect(card.locator('[data-slot="keyword-rule-row"]')).toHaveCount(2)
      await expect(card.locator('[data-slot="keyword-breach"]')).toHaveAttribute('data-level', 'CRITICAL')
      await check(page, 'kart', null, ['.upt-grid [data-slot="keyword-panel"]'])

      await card.getByRole('button', { name: /— (Edit|Düzenle)$/ }).click()
      const dsel = '[role="dialog"]:has([data-slot="kw-rules-editor"])'
      const dlg = page.locator(dsel)
      await expect(dlg).toBeVisible()
      const cards = dlg.locator('[data-slot="kw-rule-card"]')
      await expect(cards).toHaveCount(2)
      // Kural ekle → odak yeni desende; yaz; VEYA
      await dlg.locator('[data-slot="kw-rule-add"]').click()
      await expect(cards).toHaveCount(3)
      await expect(cards.nth(2).locator('[data-slot="kw-rule-pattern"]')).toBeFocused()
      await cards.nth(2).locator('[data-slot="kw-rule-pattern"]').fill('Kampanya')
      await dlg.locator('[data-slot="kw-rule-logic"]').getByRole('button', { name: /^(OR — any|VEYA — en az biri)$/ }).click()
      // Düzenli ifade yapı hatası ALANIN ALTINDA (dokunulunca)
      await cards.nth(2).getByRole('button', { name: /^(Regular expression|Düzenli ifade)$/ }).click()
      const pat = cards.nth(2).locator('[data-slot="kw-rule-pattern"]')
      await pat.fill('(Kampanya')
      await pat.blur()
      await expect(dlg.locator('[data-field="rules[2].pattern"] [data-slot="field-error"]')).toBeVisible()
      await pat.fill('Kampanya \\d+')
      await expect(dlg.locator('[data-field="rules[2].pattern"] [data-slot="field-error"]')).toHaveCount(0)
      // Sırala: üçüncü kartı yukarı taşı → yeni sıra, odak taşınan kartın düğmesinde
      await cards.nth(2).locator('[data-move="up"]').click()
      await expect(cards.nth(1).locator('[data-slot="kw-rule-pattern"]')).toHaveValue('Kampanya \\d+')
      // CSS kapsamı + iki seviyeli eşik (ikinci karttaki — artık üçüncü sırada)
      await expect(cards.nth(2).locator('[data-slot="kw-rule-scope-value"]')).toHaveValue('#durum > .mesaj')
      await expect(cards.nth(2).locator('[data-slot="kw-rule-critical"]')).toBeVisible()
      // İçerik kontrolleri: boyut + değişim (maske çipleri, seçici)
      const content = dlg.locator('[data-slot="kw-content-checks"]')
      await expect(content.locator('[data-slot="kw-content-minBytes"]')).toHaveValue('1')
      await expect(content.locator('[data-slot="kw-content-masks"]')).toBeVisible()
      await check(page, 'form (kural düzenleyici + içerik)', dsel, [`${dsel} [data-slot="kw-rules-editor"]`, `${dsel} [data-slot="kw-content-checks"]`])
      if (vp.name === 'phone') await page.screenshot({ path: test.info().outputPath('keyword-rules-form-phone.png'), fullPage: false })

      // Kayıt: sunucunun `rules[1].pattern` hatası ilgili alanın altında; o kural değişince iletisi kalkar, ikinci kayıt geçer
      const saveBtn = dlg.getByRole('button', { name: /^(Save|Kaydet)$/ })
      await saveBtn.click()
      await expect.poll(() => puts.length).toBe(1)
      expect(puts[0].ruleLogic).toBe('OR')
      expect(puts[0].rules.map((r) => r.pattern)).toEqual(['Hoş geldiniz', 'Kampanya \\d+', 'Hata\\s+\\d{3}'])
      expect(puts[0].rules[1]).toMatchObject({ kind: 'REGEX', count: 1, operator: 'GTE' })
      expect('keyword' in puts[0]).toBe(false)
      const serverErr = dlg.locator('[data-field="rules[1].pattern"]')
      await expect(serverErr).toContainText('Desen çok karmaşık')
      await cards.nth(1).locator('[data-slot="kw-rule-pattern"]').fill('Kampanya \\d{2}')
      await expect(serverErr).not.toContainText('Desen çok karmaşık')
      await saveBtn.click()
      await expect.poll(() => puts.length).toBe(2)
      await expect(page.locator(dsel)).toHaveCount(0)
    })

    test('detay: İçerik sekmesi — kural sonuçları, grafik, temel al + fark, canlı eşleşmeler', async ({ page }) => {
      test.setTimeout(150_000)
      const rows = keywordRows()
      await mockApi(page, { monitors: { ...MONITORS, keyword: rows } })
      let baselineTaken = 0
      const change = () => ({
        enabled: true, can_edit: true, mask: ['DIGITS'], selector: 'main',
        baseline: { taken_at: iso(baselineTaken ? 0 : 86_400_000), taken_by_name: 'Ayşe Yılmaz (Dijital Kanallar / Uzman)', source: 'MANUAL',
          bytes: 47_500, line_count: 210, text: 'Hoş geldiniz\nKampanya 12 bitti', text_truncated: false },
        latest: baselineTaken ? { checked_at: iso(0), changed: false, summary: null }
          : { checked_at: iso(60_000), changed: true, summary: { lines_before: 210, lines_after: 212,
            removed: ['Kampanya 12 bitti'], added: ['Kampanya 13 başladı — ' + 'uzun satır '.repeat(12), 'Yeni duyuru'],
            removed_total: 1, added_total: 2, truncated: false } },
      })
      await page.route((u) => /^\/api\/monitoring\/keyword\/\d+\/change$/.test(new URL(u).pathname), (route) => json({ success: true, data: change() })(route))
      await page.route((u) => /^\/api\/monitoring\/keyword\/\d+\/change\/baseline$/.test(new URL(u).pathname), (route) => {
        baselineTaken++
        return json({ success: true, data: { baseline: {}, closed_alarm: true } })(route)
      })
      const HOUR = 3_600_000
      await page.route((u) => /^\/api\/monitoring\/keyword\/\d+\/content-series$/.test(new URL(u).pathname), json({ success: true, data: {
        bucket_seconds: 3600, capped: false, total: 30,
        points: Array.from({ length: 24 }, (_, h) => (h === 6 || h === 7
          ? { ts: iso((24 - h) * HOUR), checks: 0, failed: 0, occ_min: null, occ_avg: null, occ_max: null, bytes_avg: null, changed: false }
          : { ts: iso((24 - h) * HOUR), checks: 4, failed: h === 20 ? 1 : 0, occ_min: 1, occ_avg: 2, occ_max: 3, bytes_avg: 47_000 + h * 20, changed: h === 20 })),
      } }))
      await page.route((u) => /^\/api\/monitoring\/keyword\/\d+\/matches$/.test(new URL(u).pathname), json({ success: true, data: {
        fetched_at: iso(0), http_status: 200, final_url: 'https://www.example.com/', truncated: false,
        rules: [{ id: 'r1', pattern_preview: 'Hoş geldiniz', count: 2, contexts: [
          { before: 'Sayın müşterimiz, internet şubemize ', match: 'Hoş geldiniz', after: ' — işlemlerinize kaldığınız yerden devam edebilirsiniz', source: 'PAGE' }] },
        { id: 'r-ab12', pattern_preview: 'Hata\\s+\\d{3}', count: 0, contexts: [] }],
      } }))
      await page.goto('/?tab=keyword')
      const card = page.locator('.upt-grid > [data-slot="card"]').filter({ hasText: 'Portal (çok kurallı)' })
      await expect(card).toBeVisible({ timeout: 20_000 })
      await card.locator('[data-monitor-open]').click()
      const dsel = '[role="dialog"]:has([data-slot="kw-k6-convert-open"])'
      const dlg = page.locator(dsel)
      await expect(dlg).toBeVisible()
      await dlg.getByRole('tab', { name: /^(Content|İçerik)/ }).click()
      const tab = dlg.locator('[data-slot="kw-content-tab"]')
      await expect(tab).toBeVisible()
      // Son kural sonuçları: tarama bütçesi notunda sayı alt sınır ("en az")
      await expect(tab.locator('[data-slot="kw-rule-result"]')).toHaveCount(2)
      await expect(tab.locator('[data-slot="kw-rule-result-count"][data-at-least="true"]')).toBeVisible()
      // Grafik: çizim + ekran okuyucu özeti; boş kovalar 0 değil boşluk (komşulu değerler çizgi, özet kontrolsüz kovaları saymaz)
      await expect(tab.locator('[data-slot="kw-content-chart-plot"]')).toBeVisible()
      await expect(tab.locator('[data-slot="kw-content-chart-summary"]')).toContainText(/22/)
      await expect(tab.locator('[data-slot="kw-content-chart-change-dot"]').first()).toBeAttached()
      // Değişim: temel bilgisi + fark (uzun satır sarar)
      await expect(tab.locator('[data-slot="kw-change-baseline"]')).toContainText('Ayşe Yılmaz (Dijital Kanallar / Uzman)')
      await expect(tab.locator('[data-slot="kw-change-added"]')).toContainText('Yeni duyuru')
      // Canlı eşleşmeler
      await tab.locator('[data-slot="kw-matches-run"]').click()
      await expect(tab.locator('[data-slot="kw-matches-context"]')).toHaveCount(1)
      await expect(tab.locator('[data-slot="kw-matches-none"]')).toHaveCount(1)
      const own = [`${dsel} [data-slot="kw-content-tab"]`]
      await check(page, 'İçerik sekmesi', dsel, own)
      if (vp.name === 'phone') await page.screenshot({ path: test.info().outputPath('keyword-content-tab-phone.png'), fullPage: false })

      // Yeni temel al (onaylı) → bildirim + yeniden okuma (artık "aynı")
      await tab.locator('[data-slot="kw-change-take"]').click()
      await page.getByRole('button', { name: /^(Take baseline|Temel al)$/ }).click()
      await expect.poll(() => baselineTaken).toBe(1)
      await expect(tab.locator('[data-slot="kw-change-latest"]')).toHaveAttribute('data-state', 'same')
    })

    test('"Sentetik izlemeye dönüştür" → taslak yazılır, Sentetik İzleme sc_open=draft ile formu açar', async ({ page }) => {
      test.setTimeout(150_000)
      const rows = keywordRows()
      await mockApi(page, { monitors: { ...MONITORS, keyword: rows } })
      let draft = null
      await page.route((u) => new URL(u).pathname === '/api/monitoring/scripted/draft', (route) => {
        if (route.request().method() !== 'PUT') return route.fallback()
        draft = JSON.parse(route.request().postData() || '{}')
        return json({ success: true, data: {} })(route)
      })
      await page.route((u) => new URL(u).pathname === '/api/monitoring/scripted/drafts', (route) => json({ success: true, data: { drafts: draft
        ? [{ monitor_key: 'new', monitor_id: null, monitor_name: draft.monitorName, form_json: draft.formJson, updated_at: iso(0) }] : [] } })(route))
      await page.goto('/?tab=keyword')
      const card = page.locator('.upt-grid > [data-slot="card"]').filter({ hasText: 'Portal (çok kurallı)' })
      await expect(card).toBeVisible({ timeout: 20_000 })
      await card.locator('[data-monitor-open]').click()
      await page.locator('[data-slot="kw-k6-convert-open"]').click()
      const conv = page.locator('[role="dialog"]:has([data-slot="kw-k6-dialog"])')
      await expect(conv).toBeVisible()
      await expect(conv.locator('[data-slot="kw-k6-script"]')).toContainText("import http from 'k6/http'")
      await expect(conv.locator('[data-slot="kw-k6-note"]').first()).toBeVisible()
      await check(page, 'dönüştürme penceresi', '[role="dialog"]:has([data-slot="kw-k6-dialog"])', ['[data-slot="kw-k6-dialog"]'])
      await conv.locator('[data-slot="kw-k6-open"]').click()
      await expect.poll(() => draft?.monitorKey).toBe('new')
      const form = JSON.parse(draft.formJson)
      expect(form.script).toContain('http.request')
      expect(form.intervalSeconds).toBeGreaterThanOrEqual(60)
      // Sentetik İzleme açılır, form taslakla gelir, tek seferlik parametre silinir
      await expect(page).toHaveURL(/tab=scripted/)
      await expect.poll(() => new URL(page.url()).searchParams.get('sc_open')).toBeNull()
      await expect.poll(() => page.evaluate(() => [...document.querySelectorAll('[role="dialog"] input')]
        .some((i) => /\((synthetic|sentetik)\)/.test(i.value))), { timeout: 10_000 }).toBe(true)
    })
  })
}
