// Sayfa Hızı bağımsız inceleme (2026-11-17) — eksik uçtan uca senaryolar: şelale sekmesinde ölçüm seçici + "Şimdi ölç" sonrası
// son ölçümü izleme (arada "daha eski" denmez, okunamayınca veri kalır + uyarı), ölçülemeyen TTFB'de not "eksik" (yeşil değil);
// şelale görsel satırından özgün görseli indirme + sunucu hata durumları (bulunamadı / çok büyük / HTTP 403 / site yavaşlatma);
// karara bağlanamayan ölçümün (UNDECIDED) kartta ve geçmişte nötr görünmesi; zamanlama kipini aralık ↔ cron arasında değiştirip
// sunucunun 400 alan hatalarını alanın altında görmek; genişletilmiş ölçüm işaretleri (geçmiş rozeti, ayrıntıdaki kip satırı,
// kırılımdaki kip değişimi notu). Telefon / tablet (dokunmatik) / dizüstünde ekrana sığar, yatay kayma yok, ham anahtar yok,
// yeni denetimler dokunmatikte ≥ 40 px. jsdom yerleşim hesaplamaz → gerçek tarayıcı. API tamamen mock'lu.
import { test, expect } from '@playwright/test'
import { MONITORS, mockApi } from './support/monitorMocks.js'

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'desktop', width: 1280, height: 800 },
]
const json = (body, status = 200, headers = {}) => (r) => r.fulfill({ status, contentType: 'application/json', headers, body: JSON.stringify(body) })
const iso = (agoMs) => new Date(Date.now() - agoMs).toISOString().slice(0, 19)
const LONG_NAME = 'Kurumsal İnternet Bankacılığı Ödeme Onay Sayfası (Bireysel ve Ticari Müşteriler — Canlı Ortam)'
// Sabit anlar: liste satırının last_check'i ile anlık görüntünün checked_at'i AYNI metin olmalı (saniye sınırı titrekliği yok)
const T1 = iso(30 * 60_000)
const T2 = iso(60_000)
const T3 = iso(20_000)
const BREACH_AT = iso(26 * 3_600_000)
const IMG = (n) => `https://cdn.example.net/gorseller/kampanya-${n}-cok-uzun-adli-banner-gorseli-2026-kasim-sezonu.png`
const PNG_1PX = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64')

const BASE_ROW = {
  ...MONITORS.pagespeed[0], name: LONG_NAME, active: true, ok: true, can_check: true, can_edit: true,
  response_ms: 1400, ttfb_ms: 180, total_bytes: 1_250_000, request_count: 46, html_ms: 300,
}

function snapshot(checkId, at, { ttfb = 160, extra = [] } = {}) {
  return { success: true, data: {
    check_id: checkId, has_start: true, truncated: false, total: 5,
    breaches: [{ check_id: 55, checked_at: BREACH_AT }],
    check: { checked_at: at, ttfb_ms: ttfb, html_ms: 300, response_ms: 1400, total_bytes: 1_250_000, html_bytes: 40_000,
      request_count: 6, failed_count: 0, dns_ms: 10, connect_ms: 20, tls_ms: 30, server_ms: 90, download_ms: 40, time_truncated: false },
    resources: [
      { url: IMG('tamam'), type: 'IMG', start_ms: 320, duration_ms: 400, bytes: 180_000, http_status: 200, check_id: checkId },
      { url: IMG('silinmis'), type: 'IMG', start_ms: 330, duration_ms: 300, bytes: 150_000, http_status: 200, check_id: checkId },
      // Ölçülen boyut 25 MiB'ın ALTINDA (istemci indirmeyi kendisi reddetmesin); sunucu gerçek boyutu 413 ile söyler
      { url: IMG('dev-boyut'), type: 'IMG', start_ms: 340, duration_ms: 900, bytes: 20_000_000, http_status: 200, check_id: checkId },
      { url: IMG('yasakli'), type: 'IMG', start_ms: 350, duration_ms: 200, bytes: 90_000, http_status: 200, check_id: checkId },
      { url: IMG('yavaslatilan'), type: 'IMG', start_ms: 360, duration_ms: 250, bytes: 70_000, http_status: 200, check_id: checkId },
      ...extra,
    ],
  } }
}

const measure = (sel) => {
  const vw = document.documentElement.clientWidth
  const root = (sel && document.querySelector(sel)) || document.body
  const pageOverflow = Math.max(document.documentElement.scrollWidth - document.documentElement.clientWidth,
    document.body.scrollWidth - document.body.clientWidth)
  const clips = (el) => getComputedStyle(el).overflowX !== 'visible'
  const hidden = (el, r) => {
    if (r.width <= 1 || r.height <= 1) return true
    const cs = getComputedStyle(el)
    return cs.visibility === 'hidden' || cs.display === 'none' || Number(cs.opacity) === 0
  }
  const offenders = []
  const flagged = new Set()
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    if (r.right <= vw + 1 || hidden(el, r)) continue
    let contained = false
    for (let p = el.parentElement; p && p !== root; p = p.parentElement) {
      if (clips(p) && p.getBoundingClientRect().right <= vw + 1) { contained = true; break }
    }
    if (contained) continue
    flagged.add(el)
    if (el.parentElement && flagged.has(el.parentElement)) continue
    const slot = el.getAttribute('data-slot')
    offenders.push(`${el.tagName.toLowerCase()}${slot ? `[data-slot=${slot}]` : ''} r=${Math.round(r.right)}`)
    if (offenders.length >= 8) break
  }
  return { vw, pageOverflow, offenders }
}

// Metni GERÇEKTEN kesilen öğeler (kutusundan taşan metin; `truncate` sınıfı tek başına değil) — ad, adres, hata metni kesilmez
const clipped = (sel) => {
  const root = document.querySelector(sel)
  if (!root) return []
  const out = []
  for (const el of root.querySelectorAll('*')) {
    if (el.closest('.sr-only') || !el.childNodes.length) continue
    const hasText = [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim())
    if (!hasText || el.getBoundingClientRect().width <= 1) continue
    const cs = getComputedStyle(el)
    if (el.scrollWidth > el.clientWidth + 1 && (cs.textOverflow === 'ellipsis' || cs.overflowX !== 'visible')) {
      out.push(`${el.tagName.toLowerCase()} ${el.getAttribute('data-slot') || ''} "${el.textContent.slice(0, 40)}"`)
    }
  }
  return out.slice(0, 8)
}

const targetHeight = (loc) => loc.evaluate((el) => {
  const r = el.getBoundingClientRect()
  const af = getComputedStyle(el, '::after')
  if (af.position === 'absolute' && af.content !== 'none') {
    const h = r.height - (parseFloat(af.top) || 0) - (parseFloat(af.bottom) || 0)
    return Math.max(r.height, h)
  }
  return r.height
})

// click / tap öğeyi kendisi görünüme kaydırır ve ayrılan (yeniden çizilen) öğeyi yeniden bulur — ayrıca kaydırma yok
async function press(loc, touch) {
  if (touch) await loc.tap(); else await loc.click()
}

// Sayfa Hızı kartının başlığı ADRESTİR (ad değil) — kart, adresin metniyle bulunur
async function openDetail(page, touch, text) {
  const cards = page.locator('.upt-grid [data-slot="card"]')
  const card = (text ? cards.filter({ hasText: text }) : cards).first()
  await press(card.locator('[data-monitor-open]').first(), touch)
  const dialog = page.getByRole('dialog').first()
  await dialog.waitFor()
  return dialog
}

async function noOverflow(page, vp, sel, where) {
  await page.waitForTimeout(300)
  const m = await page.evaluate(measure, sel)
  expect(m.offenders, `@${vp.name}: ${where} ekran dışına taşan öğe`).toEqual([])
  expect(m.pageOverflow, `@${vp.name}: ${where} sayfa yatay kayıyor (px)`).toBeLessThanOrEqual(1)
}

for (const vp of VIEWPORTS) {
  test.describe(`sayfa hızı inceleme senaryoları @${vp.name}`, () => {
    const touch = vp.width < 1024
    test.use({ hasTouch: touch, isMobile: touch })

    test(`şelale: ölçüm seçici + "Şimdi ölç" sonrası son ölçümü izler, eksik not yeşil değil @${vp.name}`, async ({ page }) => {
      test.setTimeout(150_000)
      await page.setViewportSize({ width: vp.width, height: vp.height })
      await mockApi(page)
      let row = { ...BASE_ROW, id: 100, status: 'OK', last_check: T1, checked_at: T1 }
      let latest = snapshot(91, T1)
      let failNext = false
      const wfCalls = []
      const resourceQueries = []
      await page.route((u) => new URL(u).pathname === '/api/monitoring/pagespeed', (r) => json({ success: true, data: [row] })(r))
      await page.route((u) => /^\/api\/monitoring\/pagespeed\/\d+\/resources$/.test(new URL(u).pathname), (r) => {
        resourceQueries.push(new URL(r.request().url()).searchParams.toString())
        return json({ success: true, data: { resources: [], total: 0, filtered_total: 0, page: 0, size: 50, facets: {}, breaches: [] } })(r)
      })
      await page.route((u) => /^\/api\/monitoring\/pagespeed\/\d+\/waterfall$/.test(new URL(u).pathname), (r) => {
        const cid = new URL(r.request().url()).searchParams.get('checkId')
        wfCalls.push(cid)
        if (cid === '55') return json(snapshot(55, BREACH_AT))(r)
        if (failNext) { failNext = false; return json({ success: false, code: 'DB_UNAVAILABLE', error: 'Veritabanına ulaşılamadı' }, 503)(r) }
        return json(latest)(r)
      })
      const checks = []
      await page.route((u) => new URL(u).pathname === '/api/monitoring/pagespeed/100/check', (r) => {
        checks.push(1)
        return json({ success: true, data: row })(r)
      })
      await page.goto('/?tab=pagespeed')
      const dialog = await openDetail(page, touch)
      await press(dialog.getByRole('tab', { name: /Şelale ve hız notu|Waterfall & speed grade/ }), touch)

      // 1) Son ölçüm: not hazır, "daha eski" DEĞİL; ihlal anları şelale yanıtından (ağır kaynak listesi ucu size=1 ile çağrılmaz)
      const grade = dialog.locator('[data-slot="pspd-grade"]')
      await expect(grade).toBeVisible({ timeout: 20_000 })
      await expect(grade).not.toHaveAttribute('data-older', 'true')
      const picker = dialog.locator('[data-slot="pspd-deep-snapshot"]')
      await expect(picker.locator('option')).toHaveCount(2)
      expect(resourceQueries.some((q) => /(^|&)size=1(&|$)/.test(q)), 'ihlal anları için kaynak listesi ucu çağrıldı').toBe(false)

      // 2) İhlal anını seç → o ölçüm; sonra son ölçüme dön
      await picker.selectOption('55')
      await expect.poll(() => wfCalls.includes('55'), { timeout: 10_000 }).toBe(true)
      await picker.selectOption('')
      await expect(grade).not.toHaveAttribute('data-older', 'true')

      // 3) "Şimdi ölç": yeni ölçüm (TTFB ölçülemedi) → sekme kendiliğinden yeni ölçümü okur; "daha eski" denmez, not EKSİK
      latest = snapshot(92, T2, { ttfb: null })
      row = { ...row, last_check: T2, checked_at: T2 }
      const before = wfCalls.length
      const check = dialog.locator('[data-slot="monitor-modal-actions"]').getByRole('button', { name: /^(Şimdi ölç|Measure now)$/ })
      await press(check, touch)
      await expect.poll(() => checks.length, { timeout: 10_000 }).toBe(1)
      await expect.poll(() => wfCalls.slice(before).includes(null), { timeout: 10_000 }).toBe(true)
      await expect(grade).toHaveAttribute('data-incomplete', 'true', { timeout: 10_000 })
      await expect(grade).not.toHaveAttribute('data-older', 'true')
      await expect(dialog.locator('[data-slot="pspd-grade-incomplete"]')).toContainText(/TTFB/)
      expect(await grade.locator('.text-success').count(), `@${vp.name}: eksik not yeşil boyanmış`).toBe(0)

      // 4) Bir sonraki ölçüm okunamazsa gösterilen veri KALIR + uyarı; yeniden dene okur
      failNext = true
      latest = snapshot(93, T3)
      row = { ...row, last_check: T3, checked_at: T3 }
      await press(check, touch)
      const banner = dialog.locator('[data-slot="pspd-deep-follow-failed"]')
      await expect(banner).toBeVisible({ timeout: 10_000 })
      await expect(grade).toBeVisible()
      await expect(dialog.locator('[data-slot="pspd-deep-failed"]')).toHaveCount(0)
      const retry = banner.getByRole('button')
      if (touch) expect(await targetHeight(retry), `@${vp.name}: yeniden dene dokunma hedefi`).toBeGreaterThanOrEqual(39.5)
      await noOverflow(page, vp, '[role="dialog"]', 'izleme uyarısında')
      expect(await page.evaluate(clipped, '[data-slot="pspd-deep-follow-failed"]'), `@${vp.name}: uyarı metni kesiliyor`).toEqual([])
      await press(retry, touch)
      await expect(banner).toHaveCount(0, { timeout: 10_000 })
      await expect(grade).not.toHaveAttribute('data-incomplete', 'true')
      await expect(grade).not.toHaveAttribute('data-older', 'true')
      expect(await dialog.locator('[data-slot="pspd-deep"]').innerText()).not.toMatch(/pspd\.|undefined|NaN/)
    })

    test(`şelale görsel satırı: özgün görsel indirilir, sunucu hataları satırın altında açıklanır @${vp.name}`, async ({ page }) => {
      test.setTimeout(150_000)
      await page.setViewportSize({ width: vp.width, height: vp.height })
      await mockApi(page)
      const row = { ...BASE_ROW, id: 100, status: 'OK', last_check: T1, checked_at: T1 }
      await page.route((u) => new URL(u).pathname === '/api/monitoring/pagespeed', json({ success: true, data: [row] }))
      await page.route((u) => /^\/api\/monitoring\/pagespeed\/\d+\/waterfall$/.test(new URL(u).pathname), json(snapshot(91, T1)))
      const imageCalls = []
      const imageCheckIds = []
      await page.route((u) => new URL(u).pathname === '/api/monitoring/pagespeed/100/image', (r) => {
        const sent = JSON.parse(r.request().postData() || '{}')
        const url = sent.url
        imageCalls.push(url)
        imageCheckIds.push(sent.check_id)
        if (url === IMG('tamam')) {
          return r.fulfill({ status: 200, contentType: 'image/png', body: PNG_1PX, headers: {
            'Content-Disposition': 'attachment; filename="kampanya-tamam.png"', 'X-Image-Type': 'png', 'X-Image-Width': '1', 'X-Image-Height': '1' } })
        }
        if (url === IMG('silinmis')) return json({ success: false, code: 'RESOURCE_IMAGE_NOT_FOUND', error: 'x' }, 404)(r)
        if (url === IMG('dev-boyut')) return json({ success: false, code: 'RESOURCE_IMAGE_TOO_LARGE', error: 'x', bytes: 40_000_000, max_bytes: 26_214_400 }, 413)(r)
        if (url === IMG('yasakli')) return json({ success: false, code: 'RESOURCE_IMAGE_FETCH_FAILED', error: 'x', http_status: 403 }, 502)(r)
        return json({ success: false, code: 'RESOURCE_IMAGE_HOST_LIMITED', error: 'x', retry_after_seconds: 40 }, 429, { 'Retry-After': '40' })(r)
      })
      await page.goto('/?tab=pagespeed')
      const dialog = await openDetail(page, touch)
      await press(dialog.getByRole('tab', { name: /Şelale ve hız notu|Waterfall & speed grade/ }), touch)
      await expect(dialog.locator('[data-slot="pspd-wf-row"]')).toHaveCount(6, { timeout: 20_000 })

      const openRow = async (key) => {
        const r = dialog.locator('[data-slot="pspd-wf-row"]').filter({ hasText: `kampanya-${key}-` })
        await press(r.locator('[data-slot="pspd-wf-toggle"]'), touch)
        await expect(r.locator('[data-slot="pspd-wf-detail"]')).toBeVisible()
        return r
      }
      const pickOriginal = async (r) => {
        const menu = r.locator('[data-slot="prs-it-menu"]')
        if (touch) expect(await targetHeight(menu), `@${vp.name}: indirme menüsü dokunma hedefi`).toBeGreaterThanOrEqual(39.5)
        await press(menu, touch)
        const item = page.locator('[data-slot="prs-it-menu-content"] [data-action="original"]')
        await expect(item).toBeVisible()
        // Menü açılış canlandırması (zoom-in-95) bitmeden ölçülürse yükseklik %95 okunur
        if (touch) {
          await page.waitForTimeout(350)
          expect(await targetHeight(item), `@${vp.name}: "özgün" menü öğesi dokunma hedefi`).toBeGreaterThanOrEqual(39.5)
        }
        return item
      }

      // Başarılı indirme: gerçek indirme, sunucunun dosya adı
      const ok = await openRow('tamam')
      const okItem = await pickOriginal(ok)
      const [download] = await Promise.all([page.waitForEvent('download', { timeout: 20_000 }), touch ? okItem.tap() : okItem.click()])
      expect(download.suggestedFilename()).toBe('kampanya-tamam.png')
      await expect(ok.locator('[data-slot="prs-it-problem"]')).toHaveCount(0)

      // Hata durumları: her biri kendi açıklayıcı metniyle, ham kod / anahtar yok
      const cases = [
        ['silinmis', /bulunamadı|was not found/],
        ['dev-boyut', /indirme sınırından|larger than the download limit/],
        ['yasakli', /HTTP 403/],
        ['yavaslatilan', /40 (saniye|seconds)/],
      ]
      for (const [key, re] of cases) {
        const r = await openRow(key)
        await press(await pickOriginal(r), touch)
        const problem = r.locator('[data-slot="prs-it-problem"]')
        await expect(problem, `@${vp.name}: ${key} hata metni`).toBeVisible({ timeout: 10_000 })
        await expect(problem).toContainText(re)
        expect(await problem.innerText()).not.toMatch(/RESOURCE_|prs\.it|undefined/)
        expect(await page.evaluate(clipped, '[data-slot="pspd-wf-detail"]'), `@${vp.name}: ${key} hata metni kesiliyor`).toEqual([])
        await noOverflow(page, vp, '[role="dialog"]', `${key} hatasında`)
      }
      expect(imageCalls).toEqual([IMG('tamam'), IMG('silinmis'), IMG('dev-boyut'), IMG('yasakli'), IMG('yavaslatilan')])
      // 2026-11-17 (performans): istek gösterilen ölçümün kimliğini taşır — sunucu adresi yalnız o ölçümde arar
      expect(imageCheckIds).toEqual(Array(imageCalls.length).fill(91))
    })

    test(`karara bağlanamayan ölçüm kartta ve geçmişte nötr; genişletilmiş ölçüm işaretleri görünür @${vp.name}`, async ({ page }) => {
      test.setTimeout(150_000)
      await page.setViewportSize({ width: vp.width, height: vp.height })
      await mockApi(page)
      const UND_NAME = `${LONG_NAME} — kısmi ölçüm`
      const UND_TEXT = 'www.example.com/kampanya'
      const OK_TEXT = 'kurumsal.example.com/tanitim'
      const undecided = { ...BASE_ROW, id: 110, name: UND_NAME, url: `https://${UND_TEXT}`, status: 'OK',
        breached_metrics: [], undecided_metrics: ['SIZE', 'GROWSIZE'], time_truncated: true, max_page_kb: 3000, growth_bytes_pct: 20,
        extended_measurement: true, extended: false, last_check: T1, checked_at: T1 }
      const healthy = { ...BASE_ROW, id: 111, name: 'Kurumsal tanıtım sayfası', url: `https://${OK_TEXT}`, status: 'OK',
        breached_metrics: [], undecided_metrics: [], last_check: T1, checked_at: T1 }
      await page.route((u) => new URL(u).pathname === '/api/monitoring/pagespeed', json({ success: true, data: [undecided, healthy] }))
      await page.route((u) => new URL(u).pathname === '/api/monitoring/pagespeed/110/history', json({ success: true, data: {
        items: [
          { id: 3, checked_at: T1, ok: true, response_ms: 9000, ttfb_ms: 200, total_bytes: 2_400_000, request_count: 80,
            time_truncated: true, undecided_metrics: 'SIZE,GROWSIZE', extended: true },
          { id: 2, checked_at: T2, ok: true, response_ms: 1300, ttfb_ms: 190, total_bytes: 1_200_000, request_count: 46,
            undecided_metrics: null, extended: false },
        ],
        counts: { total: 2, fail: 0 }, buckets: [], alerts: [], range: { from: iso(86_400_000), to: iso(0) }, total: 2, page: 0, size: 50,
      } }))
      await page.route((u) => new URL(u).pathname === '/api/monitoring/pagespeed/110/type-series', json({ success: true, data: {
        points: Array.from({ length: 8 }, (_, i) => ({ ts: iso((8 - i) * 3_600_000), n: 1, html: 40_000, js: 500_000 + i * 1000,
          css: 60_000, img: 900_000, font: 80_000, other: 10_000 })),
        bucket_len: 13, truncated: false, mode_changes: [{ at: iso(4 * 3_600_000), event: 'MODE' }],
      } }))
      await page.goto('/?tab=pagespeed')

      // Kart: nötr "karar verilemedi" (yeşil değil); sağlıklı kart yeşil — zengin ve kompakt yoğunlukta
      const undCard = page.locator('.upt-grid [data-slot="card"]').filter({ hasText: UND_TEXT }).first()
      const okCard = page.locator('.upt-grid [data-slot="card"]').filter({ hasText: OK_TEXT }).first()
      await undCard.waitFor({ timeout: 20_000 })
      const toggle = page.locator('[data-slot="card-density-toggle"] button')
      for (const i of [0, 1]) {
        await press(toggle.nth(i), touch)
        await expect(undCard).toHaveAttribute('data-status', 'unknown')
        await expect(undCard).toContainText(/Henüz karar verilemedi|Not decidable yet/)
        await expect(okCard).toHaveAttribute('data-status', 'up')
        const badge = undCard.locator('[data-slot="badge"][data-status="unknown"]').first()
        await expect(badge).toBeVisible()
        expect(await badge.evaluate((el) => el.scrollWidth <= el.clientWidth + 1), `@${vp.name}: durum rozeti kırpılıyor`).toBe(true)
        await noOverflow(page, vp, null, `kart yoğunluğu ${i}`)
      }

      // Ayrıntı: kip satırı (son ölçüm klasik, ayar genişletilmiş → "sonraki ölçümden itibaren")
      const dialog = await openDetail(page, touch, UND_TEXT)
      const mode = dialog.locator('[data-slot="pspd-detail-mode"]')
      await expect(mode).toBeVisible()
      await expect(dialog.locator('[data-slot="pspd-detail-mode-next"]')).toBeVisible()

      // Geçmiş: kararsız satır nötr metinle, genişletilmiş ölçüm rozeti
      await press(dialog.getByRole('tab', { name: /Kontrol Geçmişi|Check History/ }), touch)
      const ext = dialog.locator('[data-slot="pspd-hist-extended"]')
      await expect(ext).toHaveCount(1, { timeout: 20_000 })
      await expect(ext).toContainText(/Genişletilmiş ölçüm|Extended measurement/)
      const histPanel = dialog.locator('[role="tabpanel"][data-state="active"]')
      await expect(histPanel.getByText(/Henüz karar verilemedi|Not decidable yet/).first()).toBeVisible()
      expect(await page.evaluate(clipped, '[data-slot="pspd-hist-extra"]'), `@${vp.name}: geçmiş ek satırı kesiliyor`).toEqual([])
      await noOverflow(page, vp, '[role="dialog"]', 'geçmiş sekmesinde')

      // Kırılım: ölçüm kipi değişimi notu
      await press(dialog.getByRole('tab', { name: /Kırılım|Breakdown/ }), touch)
      const note = dialog.locator('[data-slot="pspd-type-mode-changes"]')
      await expect(note).toBeVisible({ timeout: 20_000 })
      await expect(note).toContainText(/Ölçüm kipi değişti|measurement mode changed/)
      expect(await page.evaluate(clipped, '[data-slot="pspd-type-mode-changes"]'), `@${vp.name}: kip notu kesiliyor`).toEqual([])
      await noOverflow(page, vp, '[role="dialog"]', 'kırılım sekmesinde')
      expect(await dialog.innerText()).not.toMatch(/pspd\.|undefined|NaN/)
    })

    test(`zamanlama: aralık ↔ cron geçişi, sunucu 400 alan hataları alanın altında @${vp.name}`, async ({ page }) => {
      test.setTimeout(150_000)
      await page.setViewportSize({ width: vp.width, height: vp.height })
      await mockApi(page)
      const row = { ...BASE_ROW, id: 100, status: 'OK', last_check: T1, checked_at: T1,
        interval_seconds: 1800, schedule_mode: 'INTERVAL', cron_expression: null, windows: [], blackouts: [],
        skip_holidays: false, skip_dates: [], next_run_at: null }
      const puts = []
      await page.route((u) => new URL(u).pathname === '/api/monitoring/pagespeed', json({ success: true, data: [row] }))
      await page.route((u) => new URL(u).pathname === '/api/monitoring/pagespeed/schedule-preview',
        json({ success: true, data: { valid: true, errors: [], effective_interval_seconds: 1800, next: [] } }))
      await page.route((u) => new URL(u).pathname === '/api/monitoring/pagespeed/100', async (r) => {
        if (r.request().method() !== 'PUT') return r.fallback()
        const body = JSON.parse(r.request().postData() || '{}')
        puts.push(body)
        if (puts.length === 1) {
          return json({ success: false, code: 'VALIDATION_FAILED', error: 'Cron ifadesi geçersiz: dakika alanı 0–59 olmalı',
            field: 'cron_expression', fields: { cron_expression: 'Cron ifadesi geçersiz: dakika alanı 0–59 olmalı' } }, 400)(r)
        }
        if (puts.length === 2) {
          return json({ success: false, code: 'VALIDATION_FAILED', error: 'Ölçüm aralığı en az 5 dakika olmalı (sunucu)',
            field: 'interval_seconds', fields: { interval_seconds: 'Ölçüm aralığı en az 5 dakika olmalı (sunucu)' } }, 400)(r)
        }
        return json({ success: true, data: { ...row, ...body } })(r)
      })
      await page.goto('/?tab=pagespeed')
      const card = page.locator('.upt-grid [data-slot="card"]').first()
      await card.waitFor({ timeout: 20_000 })
      const edit = card.getByRole('button', { name: /— (Düzenle|Edit)$/ })
      if (await edit.isVisible()) await press(edit, touch)
      else {
        await card.locator('[data-slot="monitor-card-more"] button').first().click()
        await page.getByRole('menuitem', { name: /^(Düzenle|Edit)$/ }).click()
      }
      const dlg = page.getByRole('dialog').last()
      await expect(dlg).toBeVisible()
      const editor = dlg.locator('[data-slot="dbq-schedule-editor"]')
      await editor.scrollIntoViewIfNeeded()
      await expect(editor).toHaveAttribute('data-mode', 'INTERVAL')
      const modes = editor.locator('[data-field="schedule_mode"] [data-slot="toggle-group-item"]')
      await expect(modes).toHaveCount(2)

      // Aralık → cron, ifade yaz, kaydet → sunucunun cron hatası CRON ALANININ altında, form açık kalır
      await press(modes.nth(1), touch)
      await expect(editor).toHaveAttribute('data-mode', 'CRON')
      const cron = editor.locator('[data-field="cron_expression"] input').first()
      await cron.fill('75 9 * * 1-5')
      const saveBtn = dlg.getByRole('button', { name: /^(Kaydet|Save)$/ })
      await press(saveBtn, touch)
      await expect.poll(() => puts.length, { timeout: 10_000 }).toBe(1)
      expect(puts[0]).toMatchObject({ schedule_mode: 'CRON', cron_expression: '75 9 * * 1-5' })
      const cronField = editor.locator('[data-field="cron_expression"]')
      await expect(cronField).toContainText('dakika alanı 0–59 olmalı', { timeout: 10_000 })
      await expect(dlg).toBeVisible()
      expect(await page.evaluate(clipped, '[data-slot="dbq-schedule-editor"]'), `@${vp.name}: cron hatası kesiliyor`).toEqual([])
      await noOverflow(page, vp, '[role="dialog"]', 'cron hatasında')

      // Cron → aralık, kaydet → sunucunun aralık hatası ARALIK ALANININ altında
      await press(modes.nth(0), touch)
      await expect(editor).toHaveAttribute('data-mode', 'INTERVAL')
      await press(saveBtn, touch)
      await expect.poll(() => puts.length, { timeout: 10_000 }).toBe(2)
      expect(puts[1].schedule_mode).toBe('INTERVAL')
      const ivField = editor.locator('[data-field="interval_seconds"]')
      await expect(ivField).toContainText('en az 5 dakika olmalı (sunucu)', { timeout: 10_000 })
      await expect(cronField).toHaveCount(0)
      if (touch) {
        for (let i = 0; i < 2; i++) {
          expect(await targetHeight(modes.nth(i)), `@${vp.name}: kip düğmesi ${i + 1} dokunma hedefi`).toBeGreaterThanOrEqual(39.5)
        }
      }
      await noOverflow(page, vp, '[role="dialog"]', 'aralık hatasında')

      // Üçüncü kayıt başarılı → form kapanır
      await press(saveBtn, touch)
      await expect.poll(() => puts.length, { timeout: 10_000 }).toBe(3)
      await expect(editor).toHaveCount(0, { timeout: 10_000 })
    })
  })
}
