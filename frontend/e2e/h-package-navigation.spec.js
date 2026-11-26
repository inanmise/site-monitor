// H paketi (2026-11-26) — gezinme düzeltmeleri, gerçek tarayıcıda üç boyutta (390 / 768 dokunmatik, 1280):
//  - e-posta / derin bağlantı: izleme sayfası `monitor=<id>` ile detayı açar (Durum İzleme dâhil — satır kimliği `id`, yoksa
//    `inventory_id`); `q=<hedef>` arama kutusunu doldurur; eski `domain=` aynen okunur ve adres `q`'ya çevrilir; kimlikli
//    listede eşleşme yoksa "bulunamadı", kimliksiz listede (eski sunucu) sessiz — bilinmeyen ≠ yok;
//  - kalan dokuz izleme sayfasında da `monitor=` / `q=` / eski `domain=`; HTTP'de `q=<host>` (alan adı süresi alarmı HTTP
//    izlemesinden geldiğinde e-posta bağlantısı); Durum İzleme `monitor=` + `mal=open` → Alarmlar değil, detay penceresi;
//  - GERÇEK sekme değişiminde sayfa başa kayar; aynı sekmede param değişimi kaydırmaz; içindekiler çapası (`#ex-sec-…`)
//    yeni sekmenin adresine TAŞINMAZ ve başa dönmeyi engellemez; tarayıcı Geri / İleri'de uygulama başa kaydırmaz;
//  - komut paleti favorisi KİMLİKLE açılır (yeniden adlandırılmış izleme de), İzleme Panosu satırı Radar ikonlu; kart
//    çizilince favorinin saklı adı güncel adla eşitlenir (PUT); silinmiş izlemenin favorisi "bulunamadı" der;
//  - Mac'te kısayol ipucu ⌘K, diğerlerinde Ctrl K.
// API tümüyle mock — gerçek kişi / kurum adı yok.
import { test, expect } from '@playwright/test'
import { mockApi, MONITORS, UPTIME, PREFS } from './support/monitorMocks.js'
import { GATE_VIEWPORTS, expectLayout, expectInViewport, pageOverflow, json } from './support/gateChecks.js'
import { response as execResponse, SETTINGS as EXEC_SETTINGS, TEAMS as EXEC_TEAMS, TEAM_DETAIL as EXEC_TEAM_DETAIL } from '../src/test/helpers/executiveFixtures.js'

/**
 * Bilinçli tek satırlık hata özetleri (kontrol geçmişi satırı) — kırpma denetiminin gerekçeli istisnası (mweb-sweep.spec.js
 * ALLOW_CLIP ile aynı): `chkfail-oneline` / `kwfail-oneline` ve Sentetik geçmişinin `.upt-rt-error` hücresi (tam metin
 * `title`'da, satıra dokununca ayrıntı açılır). gateChecks kırpma tanımı sınıf adı taşımadığından son madde, sahte geçmişin
 * (monitorMocks) bu hücreye düşen hata metniyle eşlenir.
 */
const ONE_LINE_SUMMARIES = [/\[chkfail-oneline\]/, /\[kwfail-oneline\]/, /^span\[\] "Connection timed out after 10000 ms whil"/]

/** Kalan dokuz izleme sayfası: `monitor=101` detay metni (kimlik 101 = HOSTS[1]), `q` araması ve beklenen kart metni. */
const DEEP_PAGES = [
  { tab: 'keyword', detail: 'api.example.com', q: 'api.example.com' },
  { tab: 'page', detail: 'api.example.com', q: 'api.example.com' },
  { tab: 'pagespeed', detail: 'api.example.com', q: 'api.example.com' },
  { tab: 'ping', detail: 'api.example.com', q: 'api.example.com' },
  { tab: 'port', detail: 'api.example.com', q: 'api.example.com' },
  { tab: 'dns', detail: 'api.example.com', q: 'api.example.com' },
  { tab: 'domain', detail: 'api.example.com', q: 'api.example.com' },
  { tab: 'scripted', detail: 'Giriş akışı api.example.com', q: 'Giriş akışı api.example.com' },
  { tab: 'dbquery', detail: 'Başarısız ödeme işlemleri', q: 'Sipariş kuyruğu bekleyenleri' },
]

const search = (page) => page.locator('[data-page-search]').first()
const cards = (page) => page.locator('.upt-grid [data-slot="card"]')
const param = (page, k) => new URL(page.url()).searchParams.get(k)

for (const vp of GATE_VIEWPORTS) {
  test.describe(`H paketi gezinme — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('HTTP: monitor=<id> detayı açar; q= arama kutusunu doldurur; eski domain= okunur ve q olur', async ({ page }) => {
      test.setTimeout(120_000)
      await mockApi(page)

      // monitor=<id>: e-postadaki "İzlemeyi aç" bağlantısı
      await page.goto('/?tab=http&monitor=101')
      const dlg = page.getByRole('dialog').filter({ hasText: 'api.example.com' })
      await expect(dlg).toBeVisible({ timeout: 30_000 })
      await expectInViewport(dlg, vp.width, 'detay penceresi')
      await expectLayout(page, '[role="dialog"]', { touch: vp.touch, label: `derin bağlantı detayı @${vp.name}`, allowClip: ONE_LINE_SUMMARIES })

      // q=<hedef>: kimlik yoksa hedefle arama — kutu dolu, yalnız eşleşen kart
      await page.goto('/?tab=http&q=api.example.com')
      await expect(search(page)).toHaveValue('api.example.com', { timeout: 30_000 })
      await expect(cards(page)).toHaveCount(1)
      expect(await pageOverflow(page)).toBeLessThanOrEqual(1)

      // Eski e-posta bağlantısı domain=: aynı arama, adres q'ya çevrilir (paylaşılan bağlantı tek anahtar taşır)
      await page.goto('/?tab=http&domain=api.example.com')
      await expect(search(page)).toHaveValue('api.example.com', { timeout: 30_000 })
      await expect(cards(page)).toHaveCount(1)
      await expect.poll(() => param(page, 'q'), { timeout: 5_000 }).toBe('api.example.com')
      expect(param(page, 'domain')).toBeNull()

      // İkisi birden: q kazanır
      await page.goto('/?tab=http&q=www.example.com&domain=api.example.com')
      await expect(search(page)).toHaveValue('www.example.com', { timeout: 30_000 })

      // Alarm e-postasının yeni bağlantısı (BE-A): izleme kimliği yoksa q = şema + host + yol (sorgu atılmış) — kartın
      // adresi sorgu taşısa da bulunur
      const fullUrl = 'https://cdn.example.net/giris/kullanici'
      await page.goto(`/?tab=http&q=${encodeURIComponent(fullUrl)}`)
      await expect(search(page)).toHaveValue(fullUrl, { timeout: 30_000 })
      await expect(cards(page)).toHaveCount(1)
      await expect(cards(page).first()).toContainText('cdn.example.net')
      expect(await pageOverflow(page)).toBeLessThanOrEqual(1)
    })

    for (const dp of DEEP_PAGES) {
      test(`${dp.tab}: monitor=<id> detayı açar, olmayan kimlik "bulunamadı"; q= aramayı doldurur; eski domain= q olur`, async ({ page }) => {
        test.setTimeout(150_000)
        await mockApi(page)
        await page.goto(`/?tab=${dp.tab}&monitor=101`)
        const dlg = page.getByRole('dialog').filter({ hasText: dp.detail })
        await expect(dlg).toBeVisible({ timeout: 30_000 })
        await expectInViewport(dlg, vp.width, `${dp.tab} derin bağlantı detayı`)
        // Geçmiş satırının TEK SATIRLIK hata özeti bilinçli kırpılır (satıra dokununca tam teşhis paneli açılır) —
        // mweb-sweep.spec.js ALLOW_CLIP ile aynı gerekçeli istisna
        await expectLayout(page, '[role="dialog"]', { touch: vp.touch, label: `${dp.tab} derin bağlantı @${vp.name}`,
          allowClip: ONE_LINE_SUMMARIES })

        // Kimlikli liste, kimlik yok (silinmiş / yetkisiz) → "bulunamadı", pencere yok
        await page.goto(`/?tab=${dp.tab}&monitor=9999`)
        await expect(page.getByText(/İzleme bulunamadı|Monitor not found/).first()).toBeVisible({ timeout: 30_000 })
        await expect(page.getByRole('dialog')).toHaveCount(0)

        // q=: arama kutusu dolu, eşleşen kart görünür
        await page.goto(`/?tab=${dp.tab}&q=${encodeURIComponent(dp.q)}`)
        await expect(search(page)).toHaveValue(dp.q, { timeout: 30_000 })
        await expect(page.locator('[data-slot="card"]').filter({ hasText: dp.q }).first()).toBeVisible()
        expect(await pageOverflow(page)).toBeLessThanOrEqual(1)

        // Eski e-posta bağlantısı domain=: aynı arama, adres q'ya çevrilir
        await page.goto(`/?tab=${dp.tab}&domain=${encodeURIComponent(dp.q)}`)
        await expect(search(page)).toHaveValue(dp.q, { timeout: 30_000 })
        await expect.poll(() => param(page, 'q'), { timeout: 5_000 }).toBe(dp.q)
        expect(param(page, 'domain')).toBeNull()
      })
    }

    test('HTTP: alan adı süresi alarmının bağlantısı ?tab=http&q=<host> — kutu dolu, adresi yol + sorgu taşıyan kart bulunur', async ({ page }) => {
      test.setTimeout(90_000)
      await mockApi(page)
      await page.goto('/?tab=http&q=cdn.example.net')
      await expect(search(page)).toHaveValue('cdn.example.net', { timeout: 30_000 })
      await expect(cards(page)).toHaveCount(1)
      await expect(cards(page).first()).toContainText('cdn.example.net')
      expect(param(page, 'q')).toBe('cdn.example.net')
      await expectLayout(page, '.app-main', { touch: vp.touch, label: `http q=host @${vp.name}` })
      expect(await pageOverflow(page)).toBeLessThanOrEqual(1)
    })

    test('Durum İzleme: monitor= + mal=open → Alarmlar değil İzlemeler, detay penceresi görünür; mal adresten silinir', async ({ page }) => {
      test.setTimeout(90_000)
      await mockApi(page)
      const rows = UPTIME.map((r, i) => ({ ...r, inventory_id: 700 + i }))
      await page.route((u) => new URL(u).pathname === '/api/monitoring/uptime/overview', (r) => r.fulfill(json({ success: true, data: rows })))
      await page.goto('/?tab=uptime&monitor=701&mal=open')
      const dlg = page.getByRole('dialog').filter({ hasText: UPTIME[1].domain })
      await expect(dlg).toBeVisible({ timeout: 30_000 })
      await expectInViewport(dlg, vp.width, 'erişilebilirlik detayı (mal=open)')
      await expect.poll(() => param(page, 'mal'), { timeout: 5_000 }).toBeNull()
      await expect(page.locator('[data-slot="monitor-alarms-loading"], [data-slot="mal-tile"]')).toHaveCount(0)
      await expectLayout(page, '[role="dialog"]', { touch: vp.touch, label: `uptime monitor+mal @${vp.name}`, allowClip: ONE_LINE_SUMMARIES })
      // Pencere kapanınca İzlemeler görünümü (anahtar "İzlemeler" basılı)
      await page.keyboard.press('Escape')
      await expect(dlg).toHaveCount(0)
      await expect(page.locator('[data-slot="monitor-view-switch"] button[aria-pressed="true"]')).not.toContainText(/Alarm/)
    })

    test('Durum İzleme: monitor=<envanter kimliği> detayı açar; eşleşmezse "bulunamadı"; kimliksiz listede sessiz', async ({ page }) => {
      test.setTimeout(120_000)
      await mockApi(page)
      let rows = UPTIME.map((r, i) => ({ ...r, id: 900 + i }))
      await page.route((u) => new URL(u).pathname === '/api/monitoring/uptime/overview', (r) => r.fulfill(json({ success: true, data: rows })))

      await page.goto('/?tab=uptime&monitor=901')
      const dlg = page.getByRole('dialog').filter({ hasText: 'api.example.com' })
      await expect(dlg).toBeVisible({ timeout: 30_000 })
      await expectInViewport(dlg, vp.width, 'erişilebilirlik detayı')
      await expectLayout(page, '[role="dialog"]', { touch: vp.touch, label: `erişilebilirlik detayı @${vp.name}`, allowClip: ONE_LINE_SUMMARIES })

      // Yalnız inventory_id taşıyan satır da açılır
      rows = UPTIME.map((r, i) => ({ ...r, inventory_id: 700 + i }))
      await page.goto('/?tab=uptime&monitor=702')
      await expect(page.getByRole('dialog').filter({ hasText: UPTIME[2].domain })).toBeVisible({ timeout: 30_000 })

      // Kimlikli liste, eşleşme yok → "bulunamadı" bildirimi, pencere yok
      await page.goto('/?tab=uptime&monitor=9999')
      await expect(page.getByText(/İzleme bulunamadı|Monitor not found/).first()).toBeVisible({ timeout: 30_000 })
      await expect(page.getByRole('dialog')).toHaveCount(0)

      // Eski sunucu (satırda kimlik yok) → bilinmiyor: bildirim YOK, pencere yok
      rows = UPTIME
      await page.goto('/?tab=uptime&monitor=901')
      await expect(page.locator('[data-page-search]').first()).toBeVisible({ timeout: 30_000 })
      await page.waitForTimeout(800)
      await expect(page.getByText(/İzleme bulunamadı|Monitor not found/)).toHaveCount(0)
      await expect(page.getByRole('dialog')).toHaveCount(0)

      // domain= Durum İzleme'de de arama kutusunu doldurur
      await page.goto('/?tab=uptime&domain=api.example.com')
      await expect(search(page)).toHaveValue('api.example.com', { timeout: 30_000 })

      // ACCESSIBILITY alarmı envanter kimliği taşımıyorsa e-posta bağlantısı ?tab=uptime&q=<host> (BE-A)
      await page.goto('/?tab=uptime&q=api.example.com')
      await expect(search(page)).toHaveValue('api.example.com', { timeout: 30_000 })
      expect(param(page, 'q')).toBe('api.example.com')
      expect(await pageOverflow(page)).toBeLessThanOrEqual(1)
    })

    test('Ping kartı: yalnız ölçülemeyen satırlar (ICMP yok, SLA n=0 / up_pct null) → erişilebilirlik gri "—" + neden', async ({ page }) => {
      test.setTimeout(90_000)
      const naPing = MONITORS.ping.map((m) => (m.id === 100
        ? { ...m, status: 'na', active: true, active_alarm: false, alarm_level: null, rtt_ms: null, packet_loss: null, error: 'ICMP bu ortamda kullanılamıyor (yetki/binary)' }
        : m))
      await mockApi(page, { monitors: { ...MONITORS, ping: naPing } })
      await page.route((u) => {
        const url = new URL(u)
        return url.pathname === '/api/monitoring/sla' && url.searchParams.get('type') === 'ping'
      }, (r) => r.fulfill(json({ success: true, target_pct: 99.9, days: 30,
        data: Object.fromEntries(naPing.map((m) => [String(m.id), m.id === 100
          ? { n: 0, fail: 0, up_pct: null, bad_hours: 0, windows: [1, 7, 15, 30].map((days) => ({ days, n: 0, fail: 0, up_pct: null, bad_hours: 0, slots: [] })) }
          : { n: 1000, fail: 0, up_pct: 100, bad_hours: 0 }])) })))
      await page.route((u) => {
        const url = new URL(u)
        return url.pathname === '/api/monitoring/sparklines' && url.searchParams.get('type') === 'ping'
      }, (r) => r.fulfill(json({ success: true, data: { 100: { n: 0, fail: 0, up_pct: null, buckets: [], last: [] } } })))
      await page.goto('/?tab=ping')
      const card = cards(page).filter({ has: page.locator('[data-slot="ping-reason"][data-reason="na"]') }).first()
      await expect(card).toBeVisible({ timeout: 30_000 })
      const line = card.locator('[data-slot="availability-unknown"]')
      await expect(line).toBeVisible()
      await expect(line).toHaveAttribute('data-verdict', 'unknown')
      await expect(line.locator('[data-slot="availability-pct"]')).toHaveText('—')
      await expect(line).toContainText(/ICMP/)
      await expect(card.locator('[data-slot="availability-trigger"]')).toHaveCount(0)
      // Neden uzun cümle: sarar, kart dışına taşmaz, kesilmez
      const fits = await line.evaluate((el) => {
        const c = el.closest('[data-slot="card"]').getBoundingClientRect()
        const r = el.getBoundingClientRect()
        return r.left >= c.left - 1 && r.right <= c.right + 1 && el.scrollWidth <= el.clientWidth + 1
      })
      expect(fits, 'ölçülemedi satırı kartın içinde, kesilmeden').toBe(true)
      expect(await pageOverflow(page)).toBeLessThanOrEqual(1)
    })

    test('gerçek sekme değişimi sayfayı başa kaydırır; aynı sekmede param değişimi kaydırmaz', async ({ page }) => {
      test.setTimeout(120_000)
      const many = (list, base) => Array.from({ length: 36 }, (_, i) => ({ ...list[i % list.length], id: base + i,
        ...(list[i % list.length].name ? { name: `${list[i % list.length].name} ${i}` } : {}) }))
      await mockApi(page, { monitors: { ...MONITORS, http: many(MONITORS.http, 300), ping: many(MONITORS.ping, 400) } })
      await page.goto('/?tab=http')
      await cards(page).first().waitFor({ timeout: 30_000 })
      await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
      const y = await page.evaluate(() => window.scrollY)
      expect(y, 'sayfa kaydırılabilir olmalı').toBeGreaterThan(200)

      // Aynı sekme + param (palet / bildirim "aynı sayfada aç") → konum korunur
      await page.evaluate(() => window.dispatchEvent(new CustomEvent('sm:navigate', { detail: { tab: 'http', params: { hl: '1' } } })))
      await page.waitForTimeout(300)
      expect(await page.evaluate(() => window.scrollY)).toBeGreaterThan(200)

      // Gerçek sekme değişimi (uygulama içi gezinme) → başa
      await page.evaluate(() => window.dispatchEvent(new CustomEvent('sm:navigate', { detail: { tab: 'ping' } })))
      await expect(page).toHaveURL(/tab=ping/)
      await cards(page).first().waitFor({ timeout: 30_000 })
      expect(await page.evaluate(() => window.scrollY)).toBe(0)

      // Dizüstünde kenar çubuğundan tıklama da başa getirir
      if (!vp.touch) {
        await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
        expect(await page.evaluate(() => window.scrollY)).toBeGreaterThan(200)
        await page.locator('[data-tour="nav-tab-http"]').first().click()
        await expect(page).toHaveURL(/tab=http/)
        await cards(page).first().waitFor({ timeout: 30_000 })
        expect(await page.evaluate(() => window.scrollY)).toBe(0)
      }
    })

    test('içindekiler çapası (#ex-sec-…) sekme değişiminde taşınmaz, yeni sekme başta; Geri / İleri\'de uygulama başa kaydırmaz', async ({ page }) => {
      test.setTimeout(150_000)
      // Uygulamanın başa kaydırma çağrıları sayılır (tarayıcının kendi geri yüklemesi bu sayaca girmez)
      await page.addInitScript(() => {
        const orig = window.scrollTo.bind(window)
        window.__topCalls = 0
        window.scrollTo = function (a, b) {
          const top = a && typeof a === 'object' ? a.top : b
          if (top === 0) window.__topCalls++
          return orig(a, b)
        }
      })
      const many = (list, base) => Array.from({ length: 36 }, (_, i) => ({ ...list[i % list.length], id: base + i,
        ...(list[i % list.length].name ? { name: `${list[i % list.length].name} ${i}` } : {}) }))
      await mockApi(page, { monitors: { ...MONITORS, http: many(MONITORS.http, 300), ping: many(MONITORS.ping, 400) } })
      await page.route((u) => new URL(u).pathname === '/api/executive-summary', (r) => r.fulfill(json(execResponse())))
      await page.route((u) => new URL(u).pathname === '/api/executive-summary/settings', (r) => r.fulfill(json({ success: true, data: EXEC_SETTINGS })))
      await page.route((u) => new URL(u).pathname === '/api/executive-summary/teams', (r) => r.fulfill(json({ success: true, data: EXEC_TEAMS })))
      await page.route((u) => /^\/api\/executive-summary\/teams\/\d+$/.test(new URL(u).pathname), (r) => r.fulfill(json({ success: true, data: EXEC_TEAM_DETAIL })))
      const scrollY = () => page.evaluate(() => window.scrollY)

      // 1) Yönetici özeti: içindekiler / bölüm çipi → çapa adreste, sayfa bölüme kaydı
      await page.goto('/?tab=executive')
      const jump = page.locator('[data-slot="ex-jump"]:visible').last()
      await expect(jump).toBeVisible({ timeout: 30_000 })
      if (vp.touch) expect((await jump.boundingBox()).height, 'bölüm bağlantısı dokunma yüksekliği').toBeGreaterThanOrEqual(39.5)
      await jump.click()
      await expect.poll(() => page.evaluate(() => window.location.hash), { timeout: 10_000 }).toMatch(/^#ex-sec-/)
      await expect.poll(scrollY, { timeout: 10_000 }).toBeGreaterThan(50)

      // 2) Gerçek sekme değişimi → çapa YENİ adrese taşınmaz, sayfa başta
      await page.evaluate(() => window.dispatchEvent(new CustomEvent('sm:navigate', { detail: { tab: 'http' } })))
      await expect(page).toHaveURL(/tab=http/)
      await cards(page).first().waitFor({ timeout: 30_000 })
      expect(await page.evaluate(() => window.location.hash)).toBe('')
      expect(await scrollY()).toBe(0)

      // 3) HTTP aşağı kaydırılır → Ping (gerçek sekme değişimi: uygulama başa kaydırır, sayaç +1) → Ping aşağı kaydırılır
      await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
      await expect.poll(scrollY).toBeGreaterThan(200)
      const beforeTab = await page.evaluate(() => window.__topCalls)
      await page.evaluate(() => window.dispatchEvent(new CustomEvent('sm:navigate', { detail: { tab: 'ping' } })))
      await expect(page).toHaveURL(/tab=ping/)
      await cards(page).first().waitFor({ timeout: 30_000 })
      expect(await scrollY(), 'gerçek sekme değişimi başa').toBe(0)
      expect(await page.evaluate(() => window.__topCalls), 'sekme değişimi başa kaydırma çağrısı').toBe(beforeTab + 1)
      await page.evaluate(() => window.scrollTo(0, 400))
      // 4) Geri / İleri: konum tarayıcının işidir — uygulama başa kaydırma çağrısı YAPMAZ (inceleme bulgusu 3: yarışıyordu)
      const before = await page.evaluate(() => window.__topCalls)
      await page.goBack()
      await expect(page).toHaveURL(/tab=http/)
      await cards(page).first().waitFor({ timeout: 30_000 })
      await page.waitForTimeout(600)
      expect(await page.evaluate(() => window.__topCalls), 'Geri\'de uygulamanın başa kaydırma çağrısı').toBe(before)
      await page.goForward()
      await expect(page).toHaveURL(/tab=ping/)
      await cards(page).first().waitFor({ timeout: 30_000 })
      await page.waitForTimeout(600)
      expect(await page.evaluate(() => window.__topCalls), 'İleri\'de uygulamanın başa kaydırma çağrısı').toBe(before)
      expect(await pageOverflow(page)).toBeLessThanOrEqual(1)
    })

    test('komut paleti: kart çizilince favorinin saklı adı güncel adla eşitlenir (PUT); palette güncel ad', async ({ page }) => {
      test.setTimeout(120_000)
      const OLD = 'Eski ad — yeniden adlandırılmadan önceki kayıt'
      const puts = []
      await mockApi(page, { prefs: { ...PREFS, favorites: [{ type: 'http', id: 101, name: OLD }] } })
      page.on('request', (req) => {
        if (new URL(req.url()).pathname === '/api/me/preferences' && req.method() === 'PUT') {
          try { puts.push(JSON.parse(req.postData() || '{}')) } catch { puts.push({}) }
        }
      })
      await page.goto('/?tab=http')
      await cards(page).first().waitFor({ timeout: 30_000 })
      await expect.poll(() => puts.find((p) => Array.isArray(p.favorites)), { timeout: 15_000 }).toBeTruthy()
      const fav = puts.find((p) => Array.isArray(p.favorites)).favorites.find((x) => x.type === 'http' && x.id === 101)
      expect(fav?.name, 'saklı ad güncel adla').toBe('api.example.com')
      // Aynı ad için ikinci yazım yok (ad değişmeyince PUT yok)
      const favPuts = () => puts.filter((p) => Array.isArray(p.favorites)).length
      const n = favPuts()
      await page.waitForTimeout(2_500)
      expect(favPuts(), 'eşitlenmiş ad için yeniden yazım').toBe(n)
      await page.evaluate(() => window.dispatchEvent(new CustomEvent('sm:palette')))
      const pal = page.getByRole('dialog', { name: /Hızlı arama|Quick search/ })
      await expect(pal).toBeVisible({ timeout: 30_000 })
      await expect(pal.getByRole('option').filter({ hasText: 'api.example.com' }).first()).toBeVisible()
      await expect(pal.getByRole('option').filter({ hasText: OLD })).toHaveCount(0)
      await expectLayout(page, '[role="dialog"]', { touch: vp.touch, label: `palet favori adı @${vp.name}` })
    })

    test('komut paleti: silinmiş izlemenin favorisi → detay yok, "bulunamadı" bildirimi', async ({ page }) => {
      test.setTimeout(90_000)
      const GONE = 'Silinmiş eski izleme — Kurumsal Ödeme API (artık yok)'
      await mockApi(page, { prefs: { ...PREFS, favorites: [{ type: 'http', id: 9999, name: GONE }] } })
      await page.goto('/?tab=dashboard')
      await page.locator('.app-main').waitFor({ timeout: 30_000 })
      await page.evaluate(() => window.dispatchEvent(new CustomEvent('sm:palette')))
      const pal = page.getByRole('dialog', { name: /Hızlı arama|Quick search/ })
      await expect(pal).toBeVisible({ timeout: 30_000 })
      const opt = pal.getByRole('option').filter({ hasText: GONE })
      await expect(opt).toBeVisible()
      await opt.click()
      await expect(page).toHaveURL(/tab=http/)
      await expect.poll(() => param(page, 'monitor'), { timeout: 10_000 }).toBe('9999')
      await expect(page.getByText(/İzleme bulunamadı|Monitor not found/).first()).toBeVisible({ timeout: 30_000 })
      await expect(page.getByRole('dialog')).toHaveCount(0)
      expect(await pageOverflow(page)).toBeLessThanOrEqual(1)
    })

    test('komut paleti: favori KİMLİKLE açılır (yeniden adlandırılmış izleme); İzleme Panosu satırı Radar ikonlu', async ({ page }) => {
      test.setTimeout(120_000)
      const OLD = 'Eski ad — Kurumsal Ödeme API izlemesi (yeniden adlandırıldı, kayıtlı ad eski kaldı)'
      await mockApi(page, { prefs: { ...PREFS, favorites: [{ type: 'http', id: 101, name: OLD }] } })
      await page.goto('/?tab=dashboard')
      await page.locator('.app-main').waitFor({ timeout: 30_000 })
      await page.evaluate(() => window.dispatchEvent(new CustomEvent('sm:palette')))
      const pal = page.getByRole('dialog', { name: /Hızlı arama|Quick search/ })
      await expect(pal).toBeVisible({ timeout: 30_000 })
      const fav = pal.getByRole('option').filter({ hasText: OLD })
      await expect(fav).toBeVisible()
      await expectInViewport(pal, vp.width, 'komut paleti')
      await expectLayout(page, '[role="dialog"]', { touch: vp.touch, label: `palet favoriler @${vp.name}` })
      await fav.click()
      // Ada göre arama değil: kimlikle detay (güncel ad api.example.com)
      await expect(page).toHaveURL(/tab=http/)
      await expect.poll(() => param(page, 'monitor'), { timeout: 10_000 }).toBe('101')
      await expect(page.getByRole('dialog').filter({ hasText: 'api.example.com' })).toBeVisible({ timeout: 30_000 })
      expect(param(page, 'q')).toBeNull()

      // İzleme Panosu sekmesi: kenar çubuğuyla aynı Radar ikonu
      await page.keyboard.press('Escape')
      await page.evaluate(() => window.dispatchEvent(new CustomEvent('sm:palette')))
      await expect(pal).toBeVisible({ timeout: 15_000 })
      await pal.getByRole('combobox').fill('Monitoring Overview')
      const row = pal.getByRole('option').filter({ hasText: /İzleme Panosu|Monitoring Overview/ }).first()
      await expect(row).toBeVisible()
      await expect(row.locator('svg.lucide-radar')).toHaveCount(1)
    })

    for (const mac of [true, false]) {
      test(`kısayol ipucu: ${mac ? 'Mac → ⌘K' : 'Mac dışı → Ctrl K'}`, async ({ page }) => {
        test.setTimeout(90_000)
        if (mac) {
          await page.addInitScript(() => {
            Object.defineProperty(Navigator.prototype, 'platform', { get: () => 'MacIntel', configurable: true })
            Object.defineProperty(Navigator.prototype, 'userAgentData', { get: () => ({ platform: 'macOS', mobile: false, brands: [] }), configurable: true })
          })
        } else {
          await page.addInitScript(() => {
            Object.defineProperty(Navigator.prototype, 'platform', { get: () => 'Win32', configurable: true })
            Object.defineProperty(Navigator.prototype, 'userAgentData', { get: () => ({ platform: 'Windows', mobile: false, brands: [] }), configurable: true })
          })
        }
        await mockApi(page)
        await page.goto('/?tab=dashboard')
        await page.locator('.app-main').waitFor({ timeout: 30_000 })
        if (vp.width < 768) {
          await page.locator('[data-sidebar="trigger"]').first().click()
          await expect(page.locator('[data-mobile="true"][data-slot="sidebar"]')).toBeVisible()
        }
        const hint = page.locator('[data-tour="nav-search"] [data-slot="kbd"]').first()
        await expect(hint).toHaveText(mac ? '⌘K' : 'Ctrl K')
        expect(await pageOverflow(page)).toBeLessThanOrEqual(1)
      })
    }
  })
}
