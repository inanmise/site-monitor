// MOBİL WEB KAPISI (docs/RESPONSIVE.md §5) — her sekme API mock'lu olarak telefon (390×844) ve tablet (768×1024)
// boyunda açılır; ölçülen iki şey:
//   1) Sayfa düzeyinde yatay taşma: uygulamanın kaydırma kabı (`.app-main`) ya da belge görünüm alanından geniş mi?
//   2) Görünür bir öğe görünüm alanının sağına taşıyor mu? (Kendi kaydırma kabının — tablo kabı, sekme listesi,
//      geçmiş listesi — içinde kalan öğe taşma sayılmaz: kullanıcı onu o kabın içinde kaydırır.)
//
// jsdom yerleşim yapmaz; bu kapı tarayıcıda ölçer. Kullanıcı kararı (2026-09-26): SiteMonitor telefonda ve tablette
// eksiksiz kullanılabilir. YENİ bir sekme/ekran eklediğinde TABS listesine ekle.
//
// KNOWN_OVERFLOW YALNIZ KÜÇÜLÜR: bilinen taşma düzeltildiğinde test KIRMIZI olur ("artık taşmıyor — listeden çıkarın"),
// böylece liste gerçeği yansıtır; listeye yeni kayıt eklemek kural dışıdır (önce taşmayı düzelt).
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

/** App.jsx VALID_TABS ile aynı (sertifika, izleme, yönetim sekmelerinin HEPSİ). */
const TABS = [
  'dashboard', 'all', 'domains', 'forecast', 'renewal', 'renewal-guide',
  'warnings', 'incidents', 'maintenance', 'alerthistory', 'noc', 'stats', 'weakalgo', 'weeklyreports', 'incident-history',
  'health', 'uptime', 'monitoring', 'storms', 'http', 'domain', 'port', 'dns', 'keyword', 'ping', 'page', 'pagespeed', 'scripted',
  'activity', 'myactivity', 'system', 'monitorchanges',
  'admin', 'permissions', 'sqlplayground', 'login-issues', 'help', 'settings',
]

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844 },
  { name: 'tablet', width: 768, height: 1024 },
]

/**
 * Bilinen taşmalar — `sekme@boy` → tek satırlık sebep. YALNIZ KÜÇÜLÜR (bkz. dosya başı).
 * 2026-09-26 ilk ölçüm; sahipleri kendi ekranlarını taşırken listeden düşer.
 */
const KNOWN_OVERFLOW = new Map([
])

/** İzleme sekmeleri — ilk kartın DETAY penceresi de telefonda ölçülür (sekme listesi, başlık eylemleri, geçmiş ızgarası). */
const DETAIL_TABS = ['http', 'keyword', 'page', 'pagespeed', 'domain', 'ping', 'port', 'dns', 'scripted', 'uptime']

/** Tarayıcıda: sayfa taşması + görünüm alanı dışına taşan en dıştaki görünür öğeler. `sel`: ölçülecek kök (varsayılan sayfa). */
function measure(sel) {
  const vw = document.documentElement.clientWidth
  const root = (sel && document.querySelector(sel)) || document.querySelector('.app-main') || document.body
  const scrollers = [document.documentElement, document.body, root]
  const pageOverflow = Math.max(...scrollers.map((el) => el.scrollWidth - el.clientWidth))
  const clips = (el) => {
    const ox = getComputedStyle(el).overflowX
    return ox !== 'visible'
  }
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
    // Kendi kaydırma/kırpma kabının içinde kalıyorsa (kap görünüm alanında) taşma sayılmaz.
    let contained = false
    for (let p = el.parentElement; p && p !== root; p = p.parentElement) {
      if (clips(p) && p.getBoundingClientRect().right <= vw + 1) { contained = true; break }
    }
    if (contained) continue
    flagged.add(el)
    if (el.parentElement && flagged.has(el.parentElement)) continue   // yalnız en dıştaki
    const slot = el.getAttribute('data-slot')
    offenders.push({
      el: `${el.tagName.toLowerCase()}${slot ? `[data-slot=${slot}]` : ''}${el.className && typeof el.className === 'string' ? '.' + el.className.trim().split(/\s+/).slice(0, 3).join('.') : ''}`,
      right: Math.round(r.right), text: (el.textContent || '').trim().slice(0, 40),
    })
    if (offenders.length >= 8) break
  }
  return { vw, pageOverflow, offenders }
}

for (const vp of VIEWPORTS) {
  test.describe(`mobil web — ${vp.name} ${vp.width}×${vp.height}`, () => {
    for (const tab of TABS) {
      test(`${tab}`, async ({ page }) => {
        await page.setViewportSize({ width: vp.width, height: vp.height })
        await mockApi(page)
        await page.goto(`/?tab=${tab}`)
        await page.locator('.app-main').waitFor({ timeout: 20_000 })
        // Tembel yüklenen sekmeler + ilk veri çizimi
        await page.waitForTimeout(1200)
        const m = await page.evaluate(measure)
        const key = `${tab}@${vp.name}`
        const overflows = m.pageOverflow > 1 || m.offenders.length > 0
        if (KNOWN_OVERFLOW.has(key)) {
          test.info().annotations.push({ type: 'known-overflow', description: `${key}: ${KNOWN_OVERFLOW.get(key)}` })
          expect(overflows, `${key} artık taşmıyor — KNOWN_OVERFLOW listesinden çıkarın (liste yalnız küçülür)`).toBe(true)
          return
        }
        expect({ pageOverflow: m.pageOverflow, offenders: m.offenders },
          `${key}: sayfa yatay taşıyor ya da görünür öğe ekran dışına çıkıyor`).toEqual({ pageOverflow: expect.any(Number), offenders: [] })
        expect(m.pageOverflow, `${key}: sayfa düzeyinde yatay taşma (px)`).toBeLessThanOrEqual(1)
      })
    }
  })
}

// Yönetim Paneli alt sekmeleri (`g_tab`): üstteki `tab` döngüsü yalnız varsayılan alt sekmeyi açar, bunlara ULAŞMAZ.
// Sonuçlu (dolu) hâlleriyle ölçülür — boş ekran taşmayı kanıtlamaz. Yeni alt sekme ekranı → buraya ekle.
const SIM_RESULT = { success: true, data: {
  team_id: 1, team_name: 'Takım A', level: 'HIGH', standalone_monitor: false, managers_included: true,
  contacts_fallback_global: false, email_total: 2,
  team_emails: [{ email: 'takim-a-nobetci-listesi@example.com', team: 'Takım A', source: 'Grup: Nöbetçi', kind: 'TEAM' }],
  contacts: [{ id: 1, name: 'Kişi A', email: 'kisi-a@example.com', role: 'PO', min_level: 'WARNING', team_id: 1, email_duplicate: false }],
  webhooks: [{ id: 1, name: 'Kişi A', type: 'TEAMS', target: 'example.webhook.office.com/…abc' }],
  push: [
    { username: 'kisia', display_name: 'Kişi A', org_role: 'PO', group: 'po', min_level: 'WARNING', decision: 'RECIPIENT' },
    { username: 'kisib', display_name: 'Kişi B', org_role: null, group: null, min_level: null, decision: 'NO_ORG_ROLE' },
    // 2026-09-28: almayanlar da satır + sade gerekçe + sonraki adım düğmesi (uzun metin telefonda sarmalı)
    { username: 'kisic', display_name: 'Kişi C', org_role: 'MANAGER', group: 'yonetici', min_level: 'CRITICAL', decision: 'BELOW_MIN_LEVEL' },
    { username: 'kisid', display_name: 'Kişi D', org_role: 'TECH', group: null, min_level: null, decision: 'MISSING_MEMBERSHIP' },
  ],
  push_access: 'FULL', push_access_reason: 'GLOBAL_ADMIN', push_settings: 'FULL', push_viewer: 'demo',
  push_channel: { enabled: true, configured: true, team_enabled: true, types: ['cert'], disabled_types: [],
    quiet_start: '22:00', quiet_end: '07:00', quiet_min_level: 'CRITICAL', quiet_active: false, quiet_blocks_level: true, block_reason: null },
} }
const NOC_COVERAGE = { success: true, data: { items: [], summary: { total: 3, covered: 1, not_covered: 2, paused: 0,
  by_type: { SSL: { total: 3, covered: 1 } }, active_groups: 1, disabled_types: [], min_level: 'CRITICAL' } } }
const ADMIN_SUBTABS = [
  // "Kim bilgilendirilir?" (2026-09-27): senaryo formu + özet kutucukları + kanal kartları
  { key: 'admin/whoNotified', url: '/?tab=admin&g_tab=whoNotified&g_team=1', ready: '[data-slot="wn-result"]' },
  // Ayarlar → Alarm Fırtınası (2026-09-30): eşik/pencere/sessiz pencere alanları + canlı takım durum paneli
  { key: 'settings/storm', url: '/?tab=settings&sec=storm', ready: '[data-testid="storm-settings"]' },
]
for (const vp of VIEWPORTS) {
  test.describe(`mobil web — yönetim alt sekmeleri ${vp.name} ${vp.width}×${vp.height}`, () => {
    for (const sub of ADMIN_SUBTABS) {
      test(sub.key, async ({ page }) => {
        await page.setViewportSize({ width: vp.width, height: vp.height })
        await mockApi(page)
        // mockApi'den SONRA kaydedilen yollar önceliklidir (Playwright son kaydedileni önce dener).
        await page.route((u) => new URL(u).pathname === '/api/admin/teams',
          (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: [{ id: 1, name: 'Takım A' }] }) }))
        await page.route((u) => new URL(u).pathname === '/api/admin/recipients/simulate',
          (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(SIM_RESULT) }))
        await page.route((u) => new URL(u).pathname === '/api/noc/coverage',
          (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(NOC_COVERAGE) }))
        await page.goto(sub.url)
        await page.locator(sub.ready).waitFor({ timeout: 20_000 })
        await page.waitForTimeout(800)
        const m = await page.evaluate(measure)
        const key = `${sub.key}@${vp.name}`
        expect(m.offenders, `${key}: görünür öğe ekran dışına çıkıyor`).toEqual([])
        expect(m.pageOverflow, `${key}: sayfa düzeyinde yatay taşma (px)`).toBeLessThanOrEqual(1)
      })
    }
  })
}

// Detay pencereleri (ModalShell / Dialog) telefonda: pencerenin içindeki hiçbir görünür öğe ekran dışına çıkmaz —
// uzun sekme listesi kendi içinde kayar, başlık eylemleri sarar, geçmiş ızgarası listenin içinde kayar.
test.describe('mobil web — detay pencereleri (telefon 390×844)', () => {
  for (const tab of DETAIL_TABS) {
    test(`${tab} detay`, async ({ page }) => {
      await page.setViewportSize({ width: 390, height: 844 })
      await mockApi(page)
      await page.goto(`/?tab=${tab}`)
      await page.locator('.upt-grid [data-slot="card"] [data-monitor-open]').first().click({ timeout: 20_000 })
      const dlg = page.getByRole('dialog').first()
      await dlg.waitFor()
      await page.waitForTimeout(800)
      const m = await page.evaluate(measure, '[role="dialog"]')
      expect(m.offenders, `${tab} detay penceresi: ekran dışına taşan öğe`).toEqual([])
      // Pencere ekrana sığar (kenarları görünüm alanında)
      const box = await dlg.boundingBox()
      expect(box.x).toBeGreaterThanOrEqual(0)
      expect(box.x + box.width).toBeLessThanOrEqual(391)
    })
  }
})

// İzleme Panosu KPI özet pencereleri (2026-10-01, kullanıcı isteği): Sorunlu / Kontrolü gecikmiş / Açık alarm /
// Duraklatılmış kutuları ModalShell açar — telefonda tam ekran, tablette ortalı; içerideki hiçbir öğe (özet kutucukları,
// dağılım listeleri, izleme satırları, envanter-dışı alt grubu ve ikiz bağlantısı, altlık düğmeleri) ekran dışına taşmaz.
// Ayrıca pano masaüstü genişliklerinde (1280 / 1440 — tablo görünümü, kap sorgulu sütunlar) sayfa düzeyinde taşmaz.
const MO_KPI_DIALOGS = ['down', 'stale', 'alerts', 'paused']
for (const vp of VIEWPORTS) {
  test.describe(`mobil web — monitoring KPI pencereleri ${vp.name} ${vp.width}×${vp.height}`, () => {
    for (const kind of MO_KPI_DIALOGS) {
      test(`monitoring ${kind} penceresi`, async ({ page }) => {
        await page.setViewportSize({ width: vp.width, height: vp.height })
        await mockApi(page)
        await page.goto('/?tab=monitoring')
        await page.locator(`[data-slot="stat-item"][data-key="${kind}"]`).click({ timeout: 20_000 })
        await page.locator(`[data-slot="mo-kpi-dialog"][data-kind="${kind}"]`).waitFor()
        const dlg = page.getByRole('dialog').first()
        await page.waitForTimeout(600)
        const m = await page.evaluate(measure, '[role="dialog"]')
        expect(m.offenders, `monitoring ${kind} penceresi @${vp.name}: ekran dışına taşan öğe`).toEqual([])
        const box = await dlg.boundingBox()
        expect(box.x).toBeGreaterThanOrEqual(0)
        expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
        expect(box.y + box.height).toBeLessThanOrEqual(vp.height + 1)
        // Altlık düğmesi ("Listede süz") görünür ve dokunma hedefi ≥ 40 px (dokunmatik taklidi yok → yalnız görünürlük)
        await expect(page.locator('[data-slot="mo-dlg-filter"]')).toBeVisible()
      })
    }
  })
}

// İzleme Panosu bölümler akordiyonu (2026-10-01, kullanıcı isteği): varsayılan yalnız özet göstergeler açık; "Tümünü aç"
// sonrası dört bölüm (KPI, filo sağlığı, takım sağlığı, tür kartları) telefonda / tablette / dizüstünde taşmaz, her
// bölüm başlığı dokunmatik hedef (≥ 40 px) ve kapalı başlıkların özet çipleri ekrana sığar.
for (const vp of [...VIEWPORTS, { name: 'laptop', width: 1280, height: 800 }]) {
  test(`monitoring bölümler akordiyonu @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page)
    await page.goto('/?tab=monitoring')
    const sections = page.locator('[data-slot="mo-section"]')
    await page.locator('[data-slot="mo-sections"]').waitFor({ timeout: 20_000 })
    await expect(sections).toHaveCount(4)
    // Varsayılan: yalnız KPI açık; kapalı başlıkların özet çipleri ekrana sığar
    await expect(page.locator('[data-slot="mo-section"][data-state="open"]')).toHaveCount(1)
    await expect(page.locator('[data-slot="mo-section"][data-section="kpis"]')).toHaveAttribute('data-state', 'open')
    await page.waitForTimeout(400)
    let m = await page.evaluate(measure)
    expect(m.offenders, `akordiyon kapalı @${vp.name}: ekran dışına taşan öğe`).toEqual([])
    expect(m.pageOverflow, `akordiyon kapalı @${vp.name}: sayfa taşması (px)`).toBeLessThanOrEqual(1)
    const heights = await page.locator('[data-slot="mo-section-trigger"]').evaluateAll((els) => els.map((e) => Math.round(e.getBoundingClientRect().height)))
    for (const h of heights) expect(h, `bölüm başlığı dokunma hedefi @${vp.name}`).toBeGreaterThanOrEqual(40)
    // Tek düğme: hepsini aç → dört bölüm açık, taşma yok
    await page.locator('[data-slot="mo-sections-toggle"]').click()
    await expect(page.locator('[data-slot="mo-section"][data-state="open"]')).toHaveCount(4)
    await page.locator('[data-slot="mo-type-card"]').first().waitFor()
    await page.waitForTimeout(600)   // açılma animasyonu
    m = await page.evaluate(measure)
    expect(m.offenders, `akordiyon açık @${vp.name}: ekran dışına taşan öğe`).toEqual([])
    expect(m.pageOverflow, `akordiyon açık @${vp.name}: sayfa taşması (px)`).toBeLessThanOrEqual(1)
    // Hepsini kapat → hiçbiri açık değil
    await page.locator('[data-slot="mo-sections-toggle"]').click()
    await expect(page.locator('[data-slot="mo-section"][data-state="open"]')).toHaveCount(0)
  })
}

// Sistem Sağlığı gönderim logları (2026-10-01 shadcn yeniden tasarımı): Webhook push logu (tam sayfa) ve haftalık
// erişilebilirlik e-postası logu (pencere) telefon / tablet / dizüstünde taşmaz; dar kapta kart, geniş kapta tablo;
// ayrıntı paneli / ikinci pencere ekrana sığar.
for (const vp of [...VIEWPORTS, { name: 'laptop', width: 1280, height: 800 }]) {
  test(`webhook push logu @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page)
    await page.goto('/?tab=health&view=push')
    await page.locator('[data-slot="pl-view"]').waitFor({ timeout: 20_000 })
    await page.locator('[data-slot="pl-row"]').first().waitFor()
    await page.waitForTimeout(500)
    let m = await page.evaluate(measure)
    expect(m.offenders, `push logu @${vp.name}: ekran dışına taşan öğe`).toEqual([])
    expect(m.pageOverflow, `push logu @${vp.name}: sayfa taşması (px)`).toBeLessThanOrEqual(1)
    // dar kap → kart listesi, geniş → tablo
    if (vp.name === 'phone') await expect(page.locator('[data-testid="pl-cards"]')).toBeVisible()
    if (vp.name === 'laptop') await expect(page.getByTestId('sml-table')).toBeVisible()
    if (vp.name === 'laptop') {
      // sabit sütunlar: tablo kendi kabında yatay KAYMAZ (eylem sütunu görünür)
      const over = await page.getByTestId('sml-table').evaluate((el) => { const box = el.closest('[data-slot="table-container"]') || el.parentElement; return box.scrollWidth - box.clientWidth })
      expect(over, 'push tablosu yatay kayıyor (px)').toBeLessThanOrEqual(1)
    }
    // telefonda süzgeç seçicileri düğmenin arkasında; açılınca da taşmaz
    if (vp.name === 'phone') {
      await page.locator('[data-slot="pl-filters-toggle"]').click()
      await page.waitForTimeout(200)
      m = await page.evaluate(measure)
      expect(m.offenders, `push süzgeçleri açık @${vp.name}`).toEqual([])
    }
    // ayrıntı paneli
    if (vp.name === 'laptop') await page.locator('[data-slot="pl-row"]').nth(1).click()
    else await page.locator('[data-slot="pl-row"]').nth(1).getByRole('button').first().click()
    const dlg = page.locator('[data-slot="pl-detail"]')
    await dlg.waitFor()
    await page.locator('[data-slot="pl-flow"]').waitFor()
    await page.waitForTimeout(600)
    const dm = await page.evaluate(measure, '[data-slot="pl-detail"]')
    expect(dm.offenders, `push ayrıntısı @${vp.name}: taşan öğe`).toEqual([])
    const box = await dlg.boundingBox()
    expect(box.x).toBeGreaterThanOrEqual(-1)
    expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
  })

  test(`haftalık e-posta logu @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page)
    await page.goto('/?tab=health&sec=integrations')
    await page.getByRole('button', { name: /Gönderim loglarını görmek|Click to view delivery logs/ }).click({ timeout: 20_000 })
    await page.locator('[data-slot="wa-row"]').first().waitFor()
    await page.waitForTimeout(600)
    const m = await page.evaluate(measure, '[role="dialog"]')
    expect(m.offenders, `haftalık log @${vp.name}: pencerede taşan öğe`).toEqual([])
    const tagName = await page.locator('[data-testid="wa-logs"]').evaluate((el) => el.tagName)
    expect(tagName, `haftalık log @${vp.name}: dar kapta kart, genişte tablo`).toBe(vp.name === 'laptop' ? 'TABLE' : 'DIV')   // pencere içi liste kabı tablette de < 720 px
    if (vp.name === 'laptop') {
      const over = await page.locator('[data-testid="wa-logs"]').evaluate((el) => { const box = el.closest('[data-slot="table-container"]') || el.parentElement; return box.scrollWidth - box.clientWidth })
      expect(over, 'haftalık log tablosu yatay kayıyor (px)').toBeLessThanOrEqual(1)
    }
    // ayrıntı (ikinci pencere) — önizleme genişliği seçicisi ve altlık gezinmesi sığar
    await page.locator('[data-slot="wa-row"]').first().click()
    await page.locator('[data-slot="wa-detail"]').waitFor()
    await page.waitForTimeout(600)
    const dm = await page.evaluate(measure, '[data-slot="wa-detail"]')
    expect(dm.offenders, `haftalık ayrıntı @${vp.name}: taşan öğe`).toEqual([])
    const dlg = page.getByRole('dialog').last()
    const box = await dlg.boundingBox()
    expect(box.x).toBeGreaterThanOrEqual(-1)
    expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
    await expect(page.locator('[data-slot="wa-detail-nav"]')).toBeVisible()
  })
}

for (const vp of [{ name: 'laptop', width: 1280, height: 800 }, { name: 'desktop', width: 1440, height: 900 }]) {
  test(`monitoring @${vp.name} ${vp.width}×${vp.height}: tablo görünümü, sayfa taşmaz`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page)
    await page.goto('/?tab=monitoring')
    await page.locator('[data-slot="mo-table"]').waitFor({ timeout: 20_000 })
    await page.waitForTimeout(600)
    const m = await page.evaluate(measure)
    expect(m.offenders, `monitoring@${vp.name}: görünür öğe ekran dışına çıkıyor`).toEqual([])
    expect(m.pageOverflow, `monitoring@${vp.name}: sayfa düzeyinde yatay taşma (px)`).toBeLessThanOrEqual(1)
    // Kap sorgulu sütunlar: açık kenar çubuğuyla bile tablo kendi kabında yatay KAYDIRMA istemez (eylemler görünür)
    const tableOverflow = await page.locator('[data-slot="mo-table"]').evaluate((el) => {
      const box = el.closest('[data-slot="table-container"]') || el.parentElement
      return box.scrollWidth - box.clientWidth
    })
    expect(tableOverflow, `monitoring@${vp.name}: tablo yatay kayıyor (px)`).toBeLessThanOrEqual(1)
  })
}
