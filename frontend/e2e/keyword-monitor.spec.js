// Anahtar kelime (Keyword) izleme formu — zenginleştirme (kw fe1, 2026-11-05). Telefon (390, dokunmatik), tablet (768,
// dokunmatik) ve dizüstünde (1280):
//  1) yeni izleme: "Eşleşme ayarları" ve "Gelişmiş istek" bölümleri açılır; gövde yalnız POST'ta; durum kodu ön ayarları
//     (kapalı varsayılan, "Önerilen" rozeti, özel kutu); tarayıcı benzeri UA → kurum politikası uyarısı; posta konusu
//     önizlemesi; URL yazılınca "bu URL'yi başka izleme de izliyor" uyarısı (uzun takım adları TAM, bağlantı yeni sekmede);
//  2) düzenleme (global admin): değişiklik yoksa PUT gitmez; başlık kaldırma YALNIZ `customHeaders: null`; ad değişikliği
//     YALNIZ `name` (yalnız değişen alanlar — B14).
// Her aşamada: ekran dışına taşan öğe yok, pencere yatay kaymaz, bu paketin öğelerinde metin kırpılmaz, dokunmatikte düğme ve
// bağlantılar ≥ 40 px (seçim kartının radyo düğmesi kartın tamamıyla, anahtar satırıyla ölçülür). API tamamen taklit.
import { test, expect } from '@playwright/test'
import { mockApi, MONITORS } from './support/monitorMocks.js'

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]
const LONG_TEAM = 'Dijital Kanallar ve Müşteri Deneyimi Uygulama Geliştirme Müdürlüğü (Bireysel Bankacılık / İnternet Şubesi)'
const LONG_NAME = 'Bireysel internet şubesi giriş sayfası — oturum açma formu ve kampanya alanı denetimi'
const OWN = ['[data-slot="kw-match"]', '[data-slot="kw-request"]', '[data-slot="kw-same-url"]', '[data-slot="kw-subject"]']
const json = (body, status = 200) => (route) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

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

/** Yatayda kırpılan metin (yaprak düğümler, 12+ karakter) — verilen köklerin içinde. */
function clipped(sels) {
  const out = []
  for (const sel of sels) {
    for (const root of document.querySelectorAll(sel)) {
      for (const n of root.querySelectorAll('span, p, li, label, button, a, strong, code, div')) {
        if (n.closest('.sr-only') || n.closest('svg') || n.childElementCount !== 0) continue
        if (n.textContent.trim().length > 12 && n.clientWidth > 0 && n.scrollWidth > n.clientWidth + 1) out.push(n.textContent.slice(0, 40))
      }
    }
  }
  return out
}

/**
 * Dokunmatikte 40 px altındaki DÜĞME / BAĞLANTILAR (metin kutuları tasarım sisteminin 36 px'idir, ayrı ölçülmez). Seçim
 * kartının radyo düğmesi kartın (etiket) yüksekliğiyle, anahtar satırın yüksekliğiyle, onay kutusu kendi `::after`
 * alanıyla ölçülmez (proje kuralı: vuruş alanı satır / kart).
 */
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

/** Keyword satırlarına yeni alanlar (kayıtlı parola + başlık, düzenleme bayrakları). */
function keywordRows() {
  return MONITORS.keyword.map((m) => ({
    ...m, can_edit: true, can_check: true, can_delete: true, operator: 'GTE', match_count: 1, use_proxy: 'AUTO', timeout_ms: 10000,
    alert_level: 'WARNING', notify_email: true, notify_webhook: true, noc_notify: false, noc_group_ids: [],
    match_scope: 'RAW', normalize_ws: false, method: 'GET', basic_auth_user: 'izleme', has_basic_auth_pass: true,
    user_agent_mode: 'DEFAULT', expected_status: null, expected_final_url: null, follow_redirects: true, mail_subject_template: null,
    has_custom_headers: true, custom_header_names: ['X-Api-Key'], decoded_charset: 'windows-1254',
  }))
}

for (const vp of VIEWPORTS) {
  test.describe(`Keyword izleme formu — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    const check = async (page, stage, dsel) => {
      await page.waitForTimeout(250)
      const m = await page.evaluate(measure, dsel)
      expect(m.offenders, `${stage} @${vp.name}: ekran dışına taşan öğe`).toEqual([])
      expect(m.pageOverflow, `${stage} @${vp.name}: sayfa yatay kayıyor`).toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, OWN), `${stage} @${vp.name}: kırpılan metin`).toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, OWN), `${stage} @${vp.name}: küçük dokunma hedefi`).toEqual([])
    }

    test('yeni izleme: bölümler, POST gövdesi, durum ön ayarları, UA uyarısı, konu önizlemesi, aynı URL uyarısı', async ({ page }) => {
      test.setTimeout(120_000)
      await mockApi(page, { role: 'USER', globalAdmin: false })
      const sameUrlCalls = []
      await page.route((u) => new URL(u).pathname === '/api/monitoring/keyword/same-url', (route) => {
        sameUrlCalls.push(new URL(route.request().url()).searchParams.get('url'))
        return json({ success: true, data: { url: 'https://portal.example.com/', hidden_count: 3, monitors: [
          { id: 101, name: LONG_NAME, team_name: LONG_TEAM, keyword: 'Hoş geldiniz — oturum açın', active: true },
          { id: 102, name: 'Portal kampanya', team_name: 'Takım B', keyword: 'Kampanya', active: false },
        ] } })(route)
      })
      await page.goto('/?tab=keyword')
      await expect(page.locator('.upt-grid > [data-slot="card"]').first()).toBeVisible({ timeout: 20_000 })
      await page.getByRole('button', { name: /^(New Monitor|Yeni Monitör)$/ }).first().click()
      const dsel = '[role="dialog"]:has([data-slot="kw-request"])'
      const dlg = page.locator(dsel)
      await expect(dlg).toBeVisible()

      // Aynı URL uyarısı: URL yazılınca gecikmeli sorgu; uzun ad / takım TAM, bağlantı yeni sekmede
      await dlg.getByPlaceholder('https://example.com').fill('portal.example.com')
      const notice = dlg.locator('[data-slot="kw-same-url"][data-state="shared"]')
      await expect(notice).toBeVisible({ timeout: 5_000 })
      expect(sameUrlCalls.at(-1)).toBe('https://portal.example.com')
      await expect(notice).toContainText(LONG_TEAM)
      await expect(notice.getByRole('link', { name: new RegExp(LONG_NAME.slice(0, 20)) })).toHaveAttribute('target', '_blank')
      await expect(notice.locator('[data-slot="kw-same-url-hidden"]')).toContainText('3')

      // Eşleşme ayarları
      await dlg.getByRole('button', { name: /^(Match settings|Eşleşme ayarları)/ }).click()
      await dlg.locator('[data-slot="kw-match-scope"]').getByRole('radio', { name: /^(Visible text|Görünür metin)/ }).click()
      await expect(dlg.locator('[data-slot="kw-normalize"]').getByRole('switch')).toBeDisabled()
      await expect(dlg.locator('[data-slot="kw-turkish-note"]')).toBeVisible()

      // Gelişmiş istek: gövde yalnız POST'ta
      await dlg.getByRole('button', { name: /^(Advanced request|Gelişmiş istek)/ }).click()
      const req = dlg.locator('[data-slot="kw-request"]')
      await expect(req.getByRole('textbox', { name: /^(Request body|İstek gövdesi)$/ })).toHaveCount(0)
      await req.locator('[data-slot="kw-req-method"]').getByRole('button', { name: 'POST' }).click()
      await expect(req.getByRole('textbox', { name: /^(Request body|İstek gövdesi)$/ })).toBeVisible()
      // Durum kodu: varsayılan kapalı; önerilen rozeti; özel kutu
      const presets = req.locator('[data-slot="kw-status-presets"]')
      await expect(presets.getByRole('radio', { name: /^(Off \(current behaviour\)|Kapalı \(bugünkü\))/ })).toHaveAttribute('data-state', 'checked')
      await expect(presets.locator('[data-slot="kw-status-recommended"]')).toBeVisible()
      await presets.getByRole('radio', { name: /^2xx–3xx/ }).click()
      await presets.getByRole('radio', { name: /^(Custom|Özel)/ }).click()
      await expect(req.getByRole('textbox', { name: /^(Status codes|Durum kodları)$/ })).toHaveValue('2xx,3xx')
      // UA: tarayıcı benzeri → kurum politikası uyarısı
      await req.locator('[data-slot="kw-ua-mode"]').getByRole('button', { name: /^(Browser-like|Tarayıcı benzeri)$/ }).click()
      await expect(req.locator('[data-slot="kw-ua-browser-warn"]')).toBeVisible()
      // Posta konusu önizlemesi
      const subject = dlg.locator('[data-slot="kw-subject"]')
      await subject.getByRole('textbox').fill('[Alarm] ')
      await subject.locator('[data-slot="kw-subject-placeholders"] button').first().click()
      await expect(subject.locator('[data-slot="kw-subject-preview-text"]')).toContainText('[Alarm]')

      await check(page, 'yeni izleme (bölümler açık)', dsel)
      if (vp.name === 'phone') await page.screenshot({ path: test.info().outputPath('keyword-form-phone.png'), fullPage: false })
    })

    test('düzenleme (global admin): değişiklik yoksa istek yok; başlık kaldırma ve ad değişikliği yalnız kendi alanını gönderir', async ({ page }) => {
      test.setTimeout(120_000)
      const rows = keywordRows()
      await mockApi(page, { monitors: { ...MONITORS, keyword: rows } })
      const puts = []
      await page.route((u) => /^\/api\/monitoring\/keyword\/\d+$/.test(new URL(u).pathname), (route) => {
        if (route.request().method() !== 'PUT') return route.fallback()
        const body = JSON.parse(route.request().postData() || '{}')
        puts.push(body)
        const id = Number(new URL(route.request().url()).pathname.split('/').pop())
        return json({ success: true, data: { ...rows.find((r) => r.id === id), ...body } })(route)
      })
      await page.route((u) => /^\/api\/monitoring\/keyword\/\d+\/check$/.test(new URL(u).pathname), json({ success: true, data: rows[0] }))
      await page.goto('/?tab=keyword')
      const first = page.locator('.upt-grid > [data-slot="card"]').first()
      await expect(first).toBeVisible({ timeout: 20_000 })
      const editBtn = () => page.locator('.upt-grid > [data-slot="card"]').first().getByRole('button', { name: /— (Edit|Düzenle)$/ })
      const dsel = '[role="dialog"]:has([data-slot="kw-request"])'
      const saveBtn = () => page.locator(dsel).getByRole('button', { name: /^(Save|Kaydet)$/ })

      // 1) Değişiklik yok → PUT yok, "değişiklik yok" bildirimi, form kapanır
      await editBtn().click()
      await expect(page.locator(dsel)).toBeVisible()
      await saveBtn().click()
      await expect(page.getByText(/No changes|Değişiklik yok/).first()).toBeVisible()
      await expect(page.locator(dsel)).toHaveCount(0)
      expect(puts).toHaveLength(0)

      // 2) Başlık kaldırma → yalnız customHeaders:null
      await editBtn().click()
      const dlg = page.locator(dsel)
      await dlg.getByRole('button', { name: /^(Advanced request|Gelişmiş istek)/ }).click()
      await expect(dlg.locator('[data-slot="kw-pass-stored"]')).toBeVisible()
      await dlg.getByRole('checkbox', { name: /Remove stored headers|Kayıtlı başlıkları kaldır/ }).click()
      await check(page, 'düzenleme (istek bölümü)', dsel)
      await saveBtn().click()
      await expect.poll(() => puts.length).toBe(1)
      expect(puts[0]).toEqual({ customHeaders: null })
      await expect(page.locator(dsel)).toHaveCount(0)

      // 3) Ad değişikliği → yalnız name
      await editBtn().click()
      // Ad alanının yer tutucusu formdaki URL'dir (kartın sırası varsayılan sıralamaya bağlı — URL formdan okunur)
      const url = await page.locator(dsel).getByPlaceholder('https://example.com').inputValue()
      await page.locator(dsel).getByPlaceholder(url, { exact: true }).fill('Portal (yeni ad)')
      await saveBtn().click()
      await expect.poll(() => puts.length).toBe(2)
      expect(puts[1]).toEqual({ name: 'Portal (yeni ad)' })
    })
  })
}
