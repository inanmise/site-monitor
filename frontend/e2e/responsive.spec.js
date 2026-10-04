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
import { mockApi, MONITORS } from './support/monitorMocks.js'
import { pathDiffers } from '../src/test/helpers/httpDiagnoseFixtures.js'

/** App.jsx VALID_TABS ile aynı (sertifika, izleme, yönetim sekmelerinin HEPSİ). */
const TABS = [
  'dashboard', 'all', 'domains', 'forecast', 'renewal', 'renewal-guide',
  'warnings', 'incidents', 'maintenance', 'alerthistory', 'noc', 'stats', 'weakalgo', 'weeklyreports', 'incident-history',
  'health', 'uptime', 'monitoring', 'status', 'storms', 'http', 'domain', 'port', 'dns', 'keyword', 'ping', 'page', 'pagespeed', 'scripted',
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
  // Ayarlar → Alarm Fırtınası (2026-09-30): eşik/pencere/sessiz pencere alanları + canlı takım durum paneli.
  // 2026-10-03: "Push bildirimleri fırtınaya devredilmesin" anahtarı (uzun etiket telefonda sarar, satır dokunmatik hedefi).
  { key: 'settings/storm', url: '/?tab=settings&sec=storm', ready: '[data-testid="storm-settings"]',
    visible: '[data-slot="storm-push-individual"]', touchRow: '[data-slot="storm-push-individual"] [role="switch"]' },
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
        if (sub.visible) {
          const el = page.locator(sub.visible)
          await el.scrollIntoViewIfNeeded()
          await expect(el, `${key}: ${sub.visible} görünür`).toBeVisible()
          const box = await el.boundingBox()
          expect(box.x, `${key}: sol kenar ekranda`).toBeGreaterThanOrEqual(0)
          expect(box.x + box.width, `${key}: sağ kenar ekranda`).toBeLessThanOrEqual(vp.width + 1)
        }
        if (sub.touchRow && vp.width < 640) {
          // Anahtar satırı (Switch + etiket) telefonda ≥ 40 px dokunma hedefi (ToggleRow `touch`)
          const row = await page.locator(sub.touchRow).locator('..').boundingBox()
          expect(row.height, `${key}: anahtar satırı yüksekliği`).toBeGreaterThanOrEqual(40)
        }
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

// HTTP izleme formu, Gelişmiş istek bölümü (2026-10-01, onaylı öneri 9): telefonda ve tablette form penceresi bölüm
// AÇIKKEN de ekrana sığar; başlık, kimlik doğrulama, gövde, JSON ve yavaşlık alanları taşmaz.
for (const vp of VIEWPORTS) {
  test(`http formu gelişmiş istek bölümü @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page)
    await page.goto('/?tab=http')
    await page.getByRole('button', { name: /^(Yeni Monitör|New Monitor)$/ }).first().click({ timeout: 20_000 })
    const section = page.locator('[data-slot="http-advanced"]')
    await section.waitFor()
    await section.getByRole('button').first().click()
    await page.waitForTimeout(500)
    const m = await page.evaluate(measure, '[role="dialog"]')
    expect(m.offenders, `http formu @${vp.name}: pencerede taşan öğe`).toEqual([])
    const box = await page.getByRole('dialog').last().boundingBox()
    expect(box.x).toBeGreaterThanOrEqual(-1)
    expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
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

// Kişisel tercih yüzeyleri (2026-10-02, öneri 23): "Görünümler" düğmesi (4 liste + 9 izleme başlığı), kart/başlık favori
// yıldızı, İzleme Panosu'nun "Favoriler" hızlı görünümü ve Etkinliklerim'deki "Açılış sekmesi" — DOLU tercihlerle
// (support/monitorMocks PREFS: favoriler + kayıtlı görünümler → "Görünümler (N)" en geniş hâli). Sayfa taşması yukarıdaki
// sekme döngüsünde ölçülüyor; burada yeni öğelerin GERÇEKTEN çizildiği ve görünüm alanında kaldığı doğrulanır (çizilmeseler
// döngü boşuna yeşil kalırdı). Telefonda "Görünümler" menüsü açılınca da ekrana sığmalı.
const PREF_SURFACES = [
  { tab: 'http', sel: '[data-slot="saved-views-trigger"]' },
  { tab: 'http', sel: '[data-slot="favorite-toggle"]' },
  { tab: 'ping', sel: '[data-slot="saved-views-trigger"]' },
  { tab: 'monitoring', sel: '[data-slot="saved-views-trigger"]' },
  { tab: 'monitoring', sel: '[data-slot="mo-views"] [data-view="favorites"]', scroller: true },
  { tab: 'alerthistory', sel: '[data-slot="saved-views-trigger"]' },
  // Olay & Hata Geçmişi izin ister (izinsiz "erişiminiz yok" çizilir) — başlık + Görünümler izinle ölçülür
  { tab: 'incident-history', sel: '[data-slot="saved-views-trigger"]', opts: { perms: { 'incidents.view': { view: true }, 'incidents.manage': { edit: true } } } },
  { tab: 'myactivity', sel: '[data-slot="landing-tab"]' },
]
for (const vp of VIEWPORTS) {
  // Yüzey başına AYRI test: tek testte sekiz sayfa açılışı 30 sn'lik test süresini aşıyordu.
  for (const s of PREF_SURFACES) {
    test(`kişisel tercih yüzeyi ${s.tab} ${s.sel} @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
      await page.setViewportSize({ width: vp.width, height: vp.height })
      await mockApi(page, s.opts)
      await page.goto(`/?tab=${s.tab}`)
      const el = page.locator(s.sel).first()
      await el.waitFor({ state: 'attached', timeout: 20_000 })
      await el.scrollIntoViewIfNeeded()
      await expect(el, `${s.tab} ${s.sel} @${vp.name}`).toBeVisible()
      const box = await el.boundingBox()
      expect(box.x, `${s.tab} ${s.sel} @${vp.name}: solda taşıyor`).toBeGreaterThanOrEqual(-1)
      expect(box.x + box.width, `${s.tab} ${s.sel} @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
      const m = await page.evaluate(measure)
      expect(m.pageOverflow, `${s.tab}@${vp.name}: sayfa düzeyinde yatay taşma (px)`).toBeLessThanOrEqual(1)
      expect(m.offenders, `${s.tab}@${vp.name}: görünür öğe ekran dışına çıkıyor`).toEqual([])
    })
  }
  // "Görünümler" menüsü açıkken (kayıtlı görünümler + kaydet + yeniden adlandır / sil) ekrana sığar
  test(`Görünümler menüsü @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page)
    await page.goto('/?tab=http')
    const trigger = page.locator('[data-slot="saved-views-trigger"]').first()
    await trigger.waitFor({ timeout: 20_000 })
    await trigger.click()
    const menu = page.locator('[data-slot="saved-views-menu"]')
    await expect(menu).toBeVisible()
    const mb = await menu.boundingBox()
    expect(mb.x).toBeGreaterThanOrEqual(-1)
    expect(mb.x + mb.width, `Görünümler menüsü @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
    expect(mb.y + mb.height, `Görünümler menüsü @${vp.name}: altta taşıyor`).toBeLessThanOrEqual(vp.height + 1)
  })
}

// HTTP uçtan uca tanılama penceresi (2026-10-02): detay → "Uçtan uca tanıla" → başlangıç ekranı → (mock) PATH_DIFFERS
// sonucu (vekil yolu yanıt alamadı, doğrudan yol 24 ms'de 401). Telefonda tam ekran, tablette ortalı; başlangıç, sonuç
// (izlemenin yolu sekmesi, takılan hop açık) ve öteki yol sekmesinde ilk hop genişletilmişken pencerenin içindeki hiçbir
// öğe ekran dışına taşmaz, gövde kendi içinde YATAY kaymaz, pencere görünüm alanına sığar.
const HTTPDX_DIALOG = '[role="dialog"]:has([data-slot="httpdx-body"])'
for (const vp of VIEWPORTS) {
  test(`http tanılama penceresi @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page, { monitors: { ...MONITORS, http: MONITORS.http.map((m) => ({ ...m, can_diagnose: true })) } })
    await page.route((u) => /^\/api\/monitoring\/http\/\d+\/diagnose$/.test(new URL(u).pathname),
      (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: pathDiffers() }) }))
    await page.goto('/?tab=http')
    await page.locator('.upt-grid [data-slot="card"] [data-monitor-open]').first().click({ timeout: 20_000 })
    await page.locator('[data-slot="httpdx-open"]').click()
    const dlg = page.locator(HTTPDX_DIALOG)
    await dlg.locator('[data-slot="httpdx-start"]').waitFor()

    const check = async (stage) => {
      await page.waitForTimeout(500)
      const m = await page.evaluate(measure, HTTPDX_DIALOG)
      expect(m.offenders, `tanılama ${stage} @${vp.name}: pencerede ekran dışına taşan öğe`).toEqual([])
      const box = await dlg.boundingBox()
      expect(box.x, `tanılama ${stage} @${vp.name}: solda taşıyor`).toBeGreaterThanOrEqual(-1)
      expect(box.x + box.width, `tanılama ${stage} @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
      expect(box.y + box.height, `tanılama ${stage} @${vp.name}: altta taşıyor`).toBeLessThanOrEqual(vp.height + 1)
      const hScroll = await dlg.locator('[data-slot="modal-shell-body"]').evaluate((el) => el.scrollWidth - el.clientWidth)
      expect(hScroll, `tanılama ${stage} @${vp.name}: gövde yatay kayıyor (px)`).toBeLessThanOrEqual(1)
    }
    await check('başlangıç')
    if (vp.name === 'phone') {
      // telefonda tam ekran (kenar boşluğu yok)
      const box = await dlg.boundingBox()
      expect(Math.round(box.width)).toBe(vp.width)
    }

    await dlg.getByRole('button', { name: /^(Tanılamayı başlat|Start diagnosis)$/ }).click()
    await dlg.locator('[data-slot="httpdx-result"]').waitFor()
    await expect(dlg.locator('[data-slot="httpdx-path-card"]')).toHaveCount(2)
    await check('sonuç')

    await dlg.getByRole('tab', { name: /Öteki yol|Other route/ }).click()
    const hop = dlg.locator('[data-slot="httpdx-path-detail"][data-path="alternate"] [data-slot="httpdx-hop"][data-hop="0"]')
    await hop.locator('[data-slot="httpdx-hop-trigger"]').click()
    await expect(hop).toHaveAttribute('data-state', 'open')
    await hop.locator('[data-slot="httpdx-redirect"]').waitFor()
    await check('öteki yol + hop açık')
    // başlık eylemleri ve altlık düğmeleri görünür ve ekranda
    for (const sel of ['[data-slot="httpdx-actions"]', '[data-slot="httpdx-run"]']) {
      const b = await dlg.locator(sel).boundingBox()
      expect(b.x + b.width, `${sel} @${vp.name}`).toBeLessThanOrEqual(vp.width + 1)
    }
  })
}

// Pasif hesap (2026-10-02, kullanıcı kararı): oturum açıkken sunucu 401 ACCOUNT_INACTIVE dönünce açılan BLOKLAYAN
// "Hesabınız pasife alındı" penceresi (büyük geri sayım + "Şimdi çıkış yap") ve pasif üyeleri belirgin gösteren takım
// üyeleri listesi telefonda / tablette taşmaz; pencere görünüm alanına sığar, çıkış düğmesi ≥ 40 px dokunma hedefi.
const INACTIVE_401 = {
  success: false, code: 'ACCOUNT_INACTIVE', error_code: 'ACCOUNT_INACTIVE',
  error: 'Your account is inactive; sign-in is not allowed. Contact your administrator.',
}
const json = (body, status = 200) => (r) => r.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
const inactiveMember = (id, name, active, extra = {}) => ({
  id, username: `KULLANICI${id}`, display_name: name, first_name: name.split(' ')[0], last_name: name.split(' ').slice(1).join(' '),
  email: `kullanici-${id}.uzun-adres@kurumsal-alan-adi.example.com`, title: 'Kıdemli Yazılım Geliştirme Uzmanı', department: 'Dijital Kanallar',
  mudurluk_name: 'Uygulama Geliştirme Müdürlüğü', org_role: 'TECH', company_level: '6', system_role: 'USER', has_photo: false,
  team_id: 1, team_ids: [1], active, ...extra,
})
const INACTIVE_TEAM_MEMBERS = [
  inactiveMember(11, 'Kişi Ayşe Uzunsoyadlıoğulları', true, { org_role: 'PO' }),
  inactiveMember(12, 'Kişi Bora', true),
  inactiveMember(13, 'Kişi Cemre Pasifhesapoğlu', false, { org_role: 'MANAGER' }),
  inactiveMember(14, 'Kişi Deniz', false),
]
for (const vp of VIEWPORTS) {
  test(`pasif hesap penceresi @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page)
    await page.route((u) => new URL(u).pathname === '/api/session/ping', json(INACTIVE_401, 401))
    await page.goto('/?tab=dashboard')
    await page.locator('.app-main').waitFor({ timeout: 20_000 })
    // SPA oturum yoklaması pencereye dönüşte hemen koşar (≈15 sn beklemeden)
    await page.evaluate(() => window.dispatchEvent(new Event('focus')))
    const dlg = page.locator('[data-slot="account-inactive-dialog"]')
    await dlg.waitFor({ timeout: 10_000 })
    await expect(page.locator('[data-slot="account-inactive-countdown"]')).toBeVisible()
    await page.waitForTimeout(400)
    const m = await page.evaluate(measure, '[data-slot="account-inactive-dialog"]')
    expect(m.offenders, `pasif penceresi @${vp.name}: taşan öğe`).toEqual([])
    const box = await dlg.boundingBox()
    expect(box.x, `pasif penceresi @${vp.name}: solda taşıyor`).toBeGreaterThanOrEqual(-1)
    expect(box.x + box.width, `pasif penceresi @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
    expect(box.y + box.height, `pasif penceresi @${vp.name}: altta taşıyor`).toBeLessThanOrEqual(vp.height + 1)
    const btn = await page.locator('[data-slot="account-inactive-logout"]').boundingBox()
    expect(btn.height, `çıkış düğmesi @${vp.name}: dokunma hedefi (px)`).toBeGreaterThanOrEqual(39)
    expect(btn.x + btn.width).toBeLessThanOrEqual(vp.width + 1)
  })

  test(`pasif üyeli takım listesi @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page)
    await page.route((u) => new URL(u).pathname === '/api/admin/teams', json({ success: true, data: [
      { id: 1, name: 'Takım A', active: true, email: 'takim-a@example.com', leader_id: 11 },
    ] }))
    await page.route((u) => new URL(u).pathname === '/api/admin/teams/stats',
      json({ success: true, data: { 1: { members: 4, members_inactive: 2, domains: 3, monitors: 5, open_alerts: 0, contacts: 1, groups: 0 } } }))
    await page.route((u) => new URL(u).pathname === '/api/admin/teams/1/users', json({ success: true, data: INACTIVE_TEAM_MEMBERS }))
    await page.route((u) => new URL(u).pathname === '/api/teams/1/members', json({ success: true, data: {
      team: { id: 1, name: 'Takım A', email: 'takim-a@example.com', active: true, leader_id: 11, leader_display_name: 'Kişi Ayşe Uzunsoyadlıoğulları' },
      members: INACTIVE_TEAM_MEMBERS, escalation_contacts: [],
    } }))
    await page.goto('/?tab=admin&g_tab=teams')
    await page.locator('[data-slot="team-badge"]').first().click({ timeout: 20_000 })
    await page.locator('[data-slot="team-member-inactive"]').first().waitFor()
    await expect(page.locator('[data-slot="team-member-inactive"]')).toHaveCount(2)
    await page.waitForTimeout(500)
    const m = await page.evaluate(measure, '[role="dialog"]')
    expect(m.offenders, `takım üyeleri (pasifli) @${vp.name}: pencerede taşan öğe`).toEqual([])
    const box = await page.getByRole('dialog').last().boundingBox()
    expect(box.x).toBeGreaterThanOrEqual(-1)
    expect(box.x + box.width, `takım üyeleri @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
    // pasif rozeti satırda görünür ve ekranda
    const badge = await page.locator('[data-slot="team-member-inactive"]').first().boundingBox()
    expect(badge.x + badge.width).toBeLessThanOrEqual(vp.width + 1)
  })
}

// Toplu pasife al sihirbazı (2026-10-02, kullanıcı kararı): Yönetim → Kullanıcılar → "Toplu pasife al". Ölçüt, önizleme
// (uzun ad/e-posta/takım adlı UZUN liste + sayfalama) ve onay adımı telefonda/tablette yatay taşmaz; uygula düğmesi ekranda
// ve dokunulabilir. Liste tablo DEĞİL satır listesi — dar ekranda sarar.
const BULK_TEAMS = [
  { id: 1, name: 'Dijital Kanallar ve Mobil Bankacılık Platform Takımı' },
  { id: 2, name: 'Kartlar ve Ödeme Sistemleri Operasyon Ekibi' },
]
const BULK_TARGETS = Array.from({ length: 37 }, (_, i) => ({
  id: 100 + i, username: `KULLANICI.UZUNADLI.HESAP.${i}`, display_name: `Kişi ${i} Uzunsoyadlıoğulları-Çağlayangil`,
  email: `kisi.${i}.cok-uzun-bir-e-posta-adresi@ornek-kurum-alan-adi.com.tr`, employee_id: `S${1000 + i}`,
  system_role: ['USER', 'TEAM_ADMIN', 'AUDIT'][i % 3], auth_source: i % 2 ? 'LDAP' : 'LOCAL',
  team_ids: i % 4 === 0 ? [] : [1, 2], last_login_at: i % 3 === 0 ? null : '2026-01-15T08:00:00',
}))
const BULK_PREVIEW = { success: true, data: {
  total: BULK_TARGETS.length, max: 5000, over_limit: false, targets: BULK_TARGETS, targets_truncated: false,
  excluded: { admins: 3, self: 1, already_inactive: 12 }, criteria: { scope: 'all', auth_source: 'ALL' }, inactive_cutoff: null,
} }
const BULK_HISTORY = { success: true, data: { operations: [
  { id: 7, status: 'DONE', created_at: '2026-09-30T10:00:00Z', actor: 'GLOBAL.YONETICI.HESABI', ok_count: 120,
    criteria: { scope: 'teams', team_ids: [1, 2], inactive_days: 180, auth_source: 'LDAP' },
    note: 'Yıllık erişim gözden geçirmesi — ayrılan personel ve uzun süredir giriş yapmayan hesaplar', can_undo: true },
  { id: 6, status: 'DONE', created_at: '2026-08-01T10:00:00Z', actor: 'admin', ok_count: 4, criteria: { scope: 'all' },
    undone_at: '2026-08-02T10:00:00Z', can_undo: false },
] } }
for (const vp of VIEWPORTS) {
  test(`toplu pasife al sihirbazı @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page)
    await page.route((u) => new URL(u).pathname === '/api/admin/teams', json({ success: true, data: BULK_TEAMS }))
    await page.route((u) => new URL(u).pathname === '/api/admin/users/search',
      json({ success: true, data: [], total: 0, page: 0, total_pages: 0, active_admin_count: 2 }))
    await page.route((u) => new URL(u).pathname === '/api/admin/users/bulk-operations', json(BULK_HISTORY))
    await page.route((u) => new URL(u).pathname === '/api/admin/users/bulk-deactivate/preview', json(BULK_PREVIEW))
    await page.goto('/?tab=admin&g_tab=users')
    await page.locator('[data-slot="um-bulk-deactivate"]').waitFor({ timeout: 20_000 })
    // Kullanıcılar başlığı yeni düğmeyle de taşmaz (eylemler sarar)
    const pm = await page.evaluate(measure)
    expect(pm.offenders, `kullanıcılar başlığı @${vp.name}: taşan öğe`).toEqual([])
    await page.locator('[data-slot="um-bulk-deactivate"]').click()
    const dlg = page.getByRole('dialog').last()
    await dlg.locator('[data-slot="ubd-history-row"]').first().waitFor()

    const inViewport = async (label) => {
      await page.waitForTimeout(400)
      const m = await page.evaluate(measure, '[role="dialog"]')
      expect(m.offenders, `toplu pasife al (${label}) @${vp.name}: pencerede taşan öğe`).toEqual([])
      const box = await dlg.boundingBox()
      expect(box.x, `${label} @${vp.name}: solda taşıyor`).toBeGreaterThanOrEqual(-1)
      expect(box.x + box.width, `${label} @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
      expect(box.y + box.height, `${label} @${vp.name}: altta taşıyor`).toBeLessThanOrEqual(vp.height + 1)
    }

    // 1) Ölçüt (+ son işlemler listesi, uzun not)
    await inViewport('ölçüt')
    // 2) Önizleme — uzun liste, sayfalama, arama
    await dlg.locator('[data-slot="ubd-preview-btn"]').click()
    await dlg.locator('[data-slot="ubd-row"]').first().waitFor()
    await expect(dlg.locator('[data-slot="ubd-total"]')).toBeVisible()
    await inViewport('önizleme')
    const row = await dlg.locator('[data-slot="ubd-row"]').first().boundingBox()
    expect(row.x + row.width, `önizleme satırı @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
    // 3) Onay — not + sayı yazma; uygula düğmesi ekranda ve dokunulabilir
    await dlg.getByRole('button', { name: /^(Devam|Continue)$/ }).click()
    await dlg.locator('input[name="ubd-confirm"]').fill(String(BULK_TARGETS.length))
    await expect(dlg.locator('[data-slot="ubd-apply"]')).toBeEnabled()
    await inViewport('onay')
    const apply = await dlg.locator('[data-slot="ubd-apply"]').boundingBox()
    expect(apply.x + apply.width, `uygula düğmesi @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
    expect(apply.y + apply.height, `uygula düğmesi @${vp.name}: ekranın altında`).toBeLessThanOrEqual(vp.height + 1)
    if (vp.name === 'phone') expect(apply.height, `uygula düğmesi @${vp.name}: dokunma hedefi (px)`).toBeGreaterThanOrEqual(39)
  })
}

// Kullanıcı düzenleyici (2026-10-02, kullanıcı isteği: "Kullanıcı Düzenle ekranını shadcn ile yeniden… mweb responsive"):
// paylaşılan düzenleyici hem Kullanıcılar sayfasından (kart menüsü → Düzenle) hem takım üye kartının kaleminden açılır.
// Uzun ad / e-posta / takım adı + AD kilitleri + kalıcı kilit + şifre değişimi bekleyen hesapla: telefonda TAM EKRAN,
// tabletde ortalı; dört sekmenin her birinde pencere içinde yatay taşma yok, sekme çubuğu kendi içinde kayar, altlık
// (Kaydet) ekranda ve dokunulabilir, sekme hedefleri ≥ 40 px.
const UED_TEAMS = [
  { id: 1, name: 'Dijital Kanallar ve Mobil Bankacılık Platform Takımı', active: true, email: 'dijital@example.com' },
  { id: 2, name: 'Kartlar ve Ödeme Sistemleri Operasyon Ekibi', active: true, email: 'kartlar@example.com' },
]
const UED_USER = {
  id: 501, username: 'KULLANICI.UZUNADLI.HESAP.ORNEK', display_name: 'Kişi Ayşe Uzunsoyadlıoğulları-Çağlayangil',
  first_name: 'Kişi Ayşe', last_name: 'Uzunsoyadlıoğulları-Çağlayangil',
  email: 'kisi.ayse.cok-uzun-bir-e-posta-adresi@ornek-kurum-alan-adi.com.tr', employee_id: 'S100501', system_role: 'TEAM_ADMIN',
  org_role: 'MANAGER', team_ids: [1, 2], team_id: 1, active: true, auth_source: 'LDAP', locked_field_keys: ['email', 'title'],
  role_locked: true, team_locked: true, permanent_lock: true, must_change_password: true, title: 'Kıdemli Yazılım Geliştirme Uzmanı',
  department: 'Dijital Kanallar', mudurluk_name: 'Uygulama Geliştirme ve Dijital Dönüşüm Müdürlüğü', company_level: '6',
  manager_sicil: 'S9000', phone: '+90 555 000 00 00', last_login_at: '2026-09-30T08:00:00', created_at: '2025-01-15T08:00:00',
}
async function uedCheck(page, vp, where) {
  const dlg = page.locator('[role="dialog"]:has([data-slot="user-editor"])')
  await dlg.locator('[data-slot="ued-header"]').waitFor({ timeout: 10_000 })
  for (const key of ['account', 'teams', 'profile', 'security']) {
    await dlg.locator(`[data-tab-key="${key}"]`).click()
    await expect(dlg.locator(`[data-tab-key="${key}"]`)).toHaveAttribute('data-state', 'active')
    await page.waitForTimeout(300)
    const m = await page.evaluate(measure, '[role="dialog"]:has([data-slot="user-editor"])')
    expect(m.offenders, `kullanıcı düzenleyici (${where}, ${key}) @${vp.name}: pencerede taşan öğe`).toEqual([])
    const box = await dlg.boundingBox()
    expect(box.x, `düzenleyici (${where}) @${vp.name}: solda taşıyor`).toBeGreaterThanOrEqual(-1)
    expect(box.x + box.width, `düzenleyici (${where}, ${key}) @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
    expect(box.y + box.height, `düzenleyici (${where}, ${key}) @${vp.name}: altta taşıyor`).toBeLessThanOrEqual(vp.height + 1)
    if (vp.name === 'phone') {
      // Telefonda tam ekran (kenar boşluğu yok)
      expect(box.width, `düzenleyici @phone: tam ekran genişlik`).toBeGreaterThanOrEqual(vp.width - 1)
      const tabBox = await dlg.locator(`[data-tab-key="${key}"]`).boundingBox()
      expect(tabBox.height, `sekme "${key}" @phone: dokunma hedefi (px)`).toBeGreaterThanOrEqual(40)
    }
  }
  // Altlık: Kaydet + İptal ekranda; telefonda dokunulabilir
  const save = await dlg.locator('[data-slot="ued-save"]').boundingBox()
  expect(save.x + save.width, `Kaydet (${where}) @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
  expect(save.y + save.height, `Kaydet (${where}) @${vp.name}: ekranın altında`).toBeLessThanOrEqual(vp.height + 1)
  if (vp.name === 'phone') expect(save.height, `Kaydet (${where}) @phone: dokunma hedefi (px)`).toBeGreaterThanOrEqual(39)
  // Değişiklik sayacı: alan değişince altlıkta görünür ve ekranda kalır
  await dlg.locator('[data-tab-key="profile"]').click()
  await dlg.locator('[data-field="department"] input').fill('Dijital Kanallar ve Mobil Bankacılık Uygulama Geliştirme Bölümü')
  const dirty = dlg.locator('[data-slot="ued-dirty"]')
  await expect(dirty).toHaveAttribute('data-count', '1')
  const db = await dirty.boundingBox()
  expect(db.x + db.width, `değişiklik sayacı (${where}) @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
  expect(db.y + db.height, `değişiklik sayacı (${where}) @${vp.name}: ekranın altında`).toBeLessThanOrEqual(vp.height + 1)
}
for (const vp of VIEWPORTS) {
  test(`kullanıcı düzenleyici (Kullanıcılar) @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page)
    await page.route((u) => new URL(u).pathname === '/api/admin/teams', json({ success: true, data: UED_TEAMS }))
    await page.route((u) => new URL(u).pathname === '/api/admin/users/search',
      json({ success: true, data: [UED_USER], total: 1, page: 0, total_pages: 1, active_admin_count: 2 }))
    await page.goto('/?tab=admin&g_tab=users')
    // < 1024 px: kart listesi — kartın "İşlemler" menüsü → Düzenle
    const card = page.locator('[data-user-card="501"]')
    await card.waitFor({ timeout: 20_000 })
    await card.getByRole('button', { name: /(İşlemler|İşlem|Actions)$/ }).click()
    await page.getByRole('menuitem', { name: /^(Düzenle|Edit)$/ }).click()
    await uedCheck(page, vp, 'kullanıcılar')
  })

  test(`kullanıcı düzenleyici (takım üyesi) @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page)
    await page.route((u) => new URL(u).pathname === '/api/admin/teams', json({ success: true, data: [UED_TEAMS[0]] }))
    await page.route((u) => new URL(u).pathname === '/api/admin/teams/stats',
      json({ success: true, data: { 1: { members: 1, domains: 0, monitors: 0, open_alerts: 0, contacts: 0, groups: 0 } } }))
    await page.route((u) => new URL(u).pathname === '/api/admin/teams/1/users', json({ success: true, data: [UED_USER] }))
    await page.route((u) => new URL(u).pathname === '/api/teams/1/members', json({ success: true, data: {
      team: { id: 1, name: UED_TEAMS[0].name, email: 'dijital@example.com', active: true }, members: [UED_USER], escalation_contacts: [],
    } }))
    await page.goto('/?tab=admin&g_tab=teams')
    await page.locator('[data-slot="team-badge"]').first().click({ timeout: 20_000 })
    await page.locator('[data-slot="team-member-open"]').first().click()
    await uedCheck(page, vp, 'takım üyesi')
  })
}

// Sistem Bakım Modu (2026-10-02, kullanıcı kararı): Ayarlar → Sistem Bakımı (durum kartı + eylemler + önizleme + geçmiş,
// uzun takım/mesaj metinleriyle), bakım başlayınca kapatılamayan geri sayım penceresi ve giriş ekranının bakım kartı
// telefonda / tablette taşmaz; eylem düğmeleri dokunma hedefi (≥ 40 px), pencere ekrana sığar.
const SM_ISO = (ms) => new Date(Date.now() + ms).toISOString().replace(/\.\d{3}Z$/, 'Z')
const SM_ACTIVE = {
  id: 12, phase: 'active', start_at: SM_ISO(-15 * 60_000), end_at: SM_ISO(75 * 60_000), planned_start_at: SM_ISO(-15 * 60_000),
  planned_end_at: SM_ISO(45 * 60_000), warn_minutes: 10, announce_hours: 24, mute_notifications: true, immediate: false, revision: 2,
  message_tr: 'Veritabanı sunucusu sürüm yükseltmesi ve depolama alanı genişletmesi — tüm servisler etkilenebilir',
  message_en: 'Database server version upgrade and storage expansion — all services may be affected',
  contact: 'BT Destek Masası · dahili 1234 · destek-ekibi@kurumsal-alan-adi.example.com', email_team_ids: [1],
  extended_count: 1, sessions_ended: 37, logins_blocked: 12, notifications_suppressed: 64,
}
const SM_OVERVIEW = { success: true, data: {
  server_now: SM_ISO(0), current: SM_ACTIVE,
  windows: [SM_ACTIVE, { ...SM_ACTIVE, id: 13, phase: 'planned', start_at: SM_ISO(3 * 86_400_000), end_at: SM_ISO(3 * 86_400_000 + 7_200_000) }],
  impact: { live_sessions: 148, affected_sessions: 141, admin_sessions: 7 },
  options: { warn_minutes: [5, 10, 15, 30], announce_hours: [0, 1, 6, 24, 48], countdown_minutes: [0, 1, 2, 5, 10, 15, 30],
    duration_minutes: [15, 30, 60, 90, 120, 240], extend_minutes: [15, 30, 60], default_warn_minutes: 10, default_announce_hours: 24, max_duration_hours: 72 },
  recipients: { active_users_with_email: 812, teams: [{ id: 1, name: 'Dijital Kanallar ve Mobil Bankacılık Platform Takımı', email: 'dijital@example.com' }] },
} }
const SM_HISTORY = { success: true, data: { total: 3, page: 1, size: 25, items: [1, 2, 3].map((i) => ({
  ...SM_ACTIVE, id: 20 + i, phase: i === 3 ? 'cancelled' : 'ended', start_at: SM_ISO(-i * 7 * 86_400_000), end_at: SM_ISO(-i * 7 * 86_400_000 + 3_600_000),
  created_by: 'GLOBAL.YONETICI.UZUN.KULLANICI.ADI', announce_mail_count: 812,
})) } }
const SM_401 = { success: false, code: 'MAINTENANCE', error_code: 'MAINTENANCE', error: 'Planned maintenance is in progress',
  maintenance: { state: 'active', start_at: SM_ACTIVE.start_at, end_at: SM_ACTIVE.end_at, message_tr: SM_ACTIVE.message_tr,
    message_en: SM_ACTIVE.message_en, contact: SM_ACTIVE.contact } }
for (const vp of VIEWPORTS) {
  test(`sistem bakımı ayarları @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page)
    await page.route((u) => new URL(u).pathname === '/api/admin/system-maintenance', json(SM_OVERVIEW))
    await page.route((u) => new URL(u).pathname === '/api/admin/system-maintenance/history', json(SM_HISTORY))
    await page.goto('/?tab=settings&sec=sysmaint')
    await page.locator('[data-slot="sysmaint-status"]').waitFor({ timeout: 20_000 })
    await page.locator('[data-slot="sysmaint-history-row"]').first().waitFor()
    await page.waitForTimeout(600)
    const m = await page.evaluate(measure)
    expect(m.offenders, `sistem bakımı @${vp.name}: taşan öğe`).toEqual([])
    expect(m.pageOverflow, `sistem bakımı @${vp.name}: sayfa taşması (px)`).toBeLessThanOrEqual(1)
    for (const sel of ['[data-slot="sysmaint-extend"]', '[data-slot="sysmaint-end-now"]']) {
      const b = await page.locator(sel).first().boundingBox()
      expect(b.x + b.width, `${sel} @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
      expect(b.height, `${sel} @${vp.name}: dokunma hedefi (px)`).toBeGreaterThanOrEqual(39)
    }
    // Önizleme → geri sayım penceresi ve "bakım tamamlandı" (bitiş) şeridi sekmeleri de taşmaz
    const preview = page.locator('[data-slot="sysmaint-preview"]')
    await preview.getByRole('tab', { name: /Geri sayım penceresi|Countdown dialog/ }).click()
    await page.waitForTimeout(300)
    const m2 = await page.evaluate(measure)
    expect(m2.offenders, `önizleme (pencere) @${vp.name}: taşan öğe`).toEqual([])
    await preview.getByRole('tab', { name: /Bitiş şeridi|Completion banner/ }).click()
    await preview.locator('[data-slot="maint-ended"]').waitFor()
    await page.waitForTimeout(300)
    const m3 = await page.evaluate(measure)
    expect(m3.offenders, `önizleme (bitiş şeridi) @${vp.name}: taşan öğe`).toEqual([])
  })

  test(`sistem bakımı geri sayım penceresi @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page, { role: 'USER', globalAdmin: false })
    await page.route((u) => new URL(u).pathname === '/api/session/ping', json(SM_401, 401))
    await page.goto('/?tab=dashboard')
    await page.locator('.app-main').waitFor({ timeout: 20_000 })
    await page.evaluate(() => window.dispatchEvent(new Event('focus')))
    const dlg = page.locator('[data-slot="maint-dialog"]')
    await dlg.waitFor({ timeout: 10_000 })
    await expect(page.locator('[data-slot="maint-dialog-countdown"]')).toBeVisible()
    await page.waitForTimeout(400)
    const m = await page.evaluate(measure, '[data-slot="maint-dialog"]')
    expect(m.offenders, `bakım penceresi @${vp.name}: taşan öğe`).toEqual([])
    const box = await dlg.boundingBox()
    expect(box.x).toBeGreaterThanOrEqual(-1)
    expect(box.x + box.width, `bakım penceresi @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
    expect(box.y + box.height, `bakım penceresi @${vp.name}: altta taşıyor`).toBeLessThanOrEqual(vp.height + 1)
    const btn = await page.locator('[data-slot="maint-dialog-logout"]').boundingBox()
    expect(btn.height, `çıkış düğmesi @${vp.name}: dokunma hedefi (px)`).toBeGreaterThanOrEqual(39)
  })

  test(`giriş ekranı bakım kartı @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page)
    await page.route((u) => new URL(u).pathname === '/api/me', json({ success: false, error: 'Unauthorized' }, 401))
    await page.route((u) => new URL(u).pathname === '/api/public/system-maintenance',
      json({ success: true, data: { ...SM_401.maintenance, server_now: SM_ISO(0) } }))
    await page.goto('/?session=maintenance')
    const card = page.locator('[data-slot="login-maintenance"]')
    await card.waitFor({ timeout: 20_000 })
    await expect(card).toHaveAttribute('data-state', 'ended')
    await page.waitForTimeout(400)
    const m = await page.evaluate(measure, 'body')
    expect(m.offenders, `giriş bakım kartı @${vp.name}: taşan öğe`).toEqual([])
    expect(m.pageOverflow, `giriş bakım kartı @${vp.name}: sayfa taşması (px)`).toBeLessThanOrEqual(1)
    const box = await card.boundingBox()
    expect(box.x + box.width, `giriş bakım kartı @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
  })

  // "Bakım tamamlandı" şeridi (2026-10-02, kullanıcı isteği): bakım bittikten sonra sunucunun bildirim süresince
  // (`state: 'ended'`) oturum yoklaması ve /api/me bloğu → kapatılabilir başarı şeridi; uzun mesaj + gün aşımı penceresi
  // telefonda / tablette taşmaz, kapatma düğmesi dokunma hedefi ≥ 40 px.
  test(`sistem bakımı tamamlandı şeridi @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page, { role: 'USER', globalAdmin: false })
    const ended = { state: 'ended', id: 12, revision: 2, start_at: SM_ISO(-150 * 60_000), end_at: SM_ISO(-10 * 60_000),
      planned_end_at: SM_ISO(-30 * 60_000), message_tr: SM_ACTIVE.message_tr, message_en: SM_ACTIVE.message_en, contact: SM_ACTIVE.contact }
    await page.route((u) => new URL(u).pathname === '/api/session/ping',
      json({ success: true, maintenance: ended, server_now: SM_ISO(0) }))
    await page.route((u) => new URL(u).pathname === '/api/me', json({
      success: true, username: 'demo', system_role: 'USER', global_admin: false, weekly_reports_visible: true,
      team_id: 1, team_name: 'Takım A', team_ids: [1], team_names: ['Takım A'], tour: { status: 'dismissed', version: 99 },
      maintenance: ended, server_now: SM_ISO(0),
    }))
    await page.goto('/?tab=dashboard')
    await page.locator('.app-main').waitFor({ timeout: 20_000 })
    const strip = page.locator('[data-slot="maint-ended"]')
    await strip.waitFor({ timeout: 10_000 })
    await expect(strip).toHaveAttribute('data-tone', 'success')
    await page.waitForTimeout(400)
    const m = await page.evaluate(measure)
    expect(m.offenders, `bitiş şeridi @${vp.name}: taşan öğe`).toEqual([])
    expect(m.pageOverflow, `bitiş şeridi @${vp.name}: sayfa taşması (px)`).toBeLessThanOrEqual(1)
    const box = await strip.boundingBox()
    expect(box.x + box.width, `bitiş şeridi @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
    const close = await strip.locator('[data-slot="maint-dismiss"]').boundingBox()
    expect(close.x + close.width, `kapatma düğmesi @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
    if (vp.name === 'phone') expect(close.height, `kapatma düğmesi @${vp.name}: dokunma hedefi (px)`).toBeGreaterThanOrEqual(39)
    // pencere/sayaç YOK; kapatınca kalkar
    await expect(page.locator('[data-slot="maint-dialog"]')).toHaveCount(0)
    await strip.locator('[data-slot="maint-dismiss"]').click()
    await expect(page.locator('[data-slot="maint-ended"]')).toHaveCount(0)
  })
}

// Çevrimiçi kullanıcı göstergesi (2026-10-02): telefonda üst çubukta, tablet/masaüstünde kenar çubuğu başlığında
// görünür; tıklanınca takım dağılımı paneli ekrana sığar (uzun takım adı taşmaz), gösterge dokunma hedefi ≥ 40 px (telefon).
const PRESENCE = {
  success: true,
  data: {
    total: 23, no_team: 2, window_seconds: 120, generated_at: '2026-10-02T12:00:00Z',
    teams: [
      { team_id: 1, team_name: 'SY-Takım A Uygulama Geliştirme ve Operasyon Uzun Adlı Takımı', count: 9 },
      { team_id: 2, team_name: 'SY-Takım B', count: 7 },
      { team_id: 3, team_name: 'SY-Takım C', count: 5 },
    ],
  },
}
for (const vp of [...VIEWPORTS, { name: 'laptop', width: 1280, height: 800 }]) {
  test(`çevrimiçi kullanıcı göstergesi @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page)
    await page.route((u) => new URL(u).pathname === '/api/presence/online', json(PRESENCE))
    await page.goto('/?tab=dashboard')
    await page.locator('.app-main').waitFor({ timeout: 20_000 })
    const trigger = page.locator('[data-slot="online-users"]:visible').first()
    await expect(trigger).toBeVisible({ timeout: 10_000 })
    await expect(trigger.locator('[data-slot="online-users-count"]')).toHaveText('23')
    const tb = await trigger.boundingBox()
    if (vp.width < 768) expect(tb.height, `gösterge @${vp.name}: dokunma hedefi (px)`).toBeGreaterThanOrEqual(39)
    await trigger.click()
    const panel = page.locator('[data-slot="online-users-panel"]')
    await panel.waitFor({ timeout: 5_000 })
    await expect(panel.locator('[data-slot="online-users-team"]')).toHaveCount(4)   // 3 takım + Takımsız
    await page.waitForTimeout(300)
    const m = await page.evaluate(measure, '[data-slot="online-users-panel"]')
    expect(m.offenders, `çevrimiçi paneli @${vp.name}: taşan öğe`).toEqual([])
    const box = await panel.boundingBox()
    expect(box.x, `çevrimiçi paneli @${vp.name}: solda taşıyor`).toBeGreaterThanOrEqual(-1)
    expect(box.x + box.width, `çevrimiçi paneli @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
    expect(box.y + box.height, `çevrimiçi paneli @${vp.name}: altta taşıyor`).toBeLessThanOrEqual(vp.height + 1)
  })
}

// Kodla giriş (push / e-posta tek kullanımlık kod, 2026-10-02): giriş ekranında "veya" + yöntem düğmeleri, istek adımı ve
// kod adımı (6 kutu + geri sayım + yeniden gönder) telefonda / tablette taşmaz; dokunma hedefleri ≥ 40 px. Ayarlar →
// Giriş Yöntemleri sayfası (yöntem kartları, kurallar, TR/EN önizleme, son etkinlik) de taşmaz.
// 2026-10-03: istek adımında kişi bilgisi alanı (push → "Kayıtlı cep telefonu", e-posta → "Kayıtlı e-posta adresi") ve
// ayar sayfasında "telefon / e-posta da sorulsun" anahtarları + kapsam ipucu + eşleşmeyen deneme sınırı da taşmaz.
const OTP_METHODS = { success: true, ldap: true, otp_push: true, otp_email: true, push_ttl: 45, email_ttl: 45, resend_cooldown: 30,
  push_requires_phone: true, email_requires_email: true }
const LM_ADMIN = { success: true, data: {
  settings: { ldap_enabled: false, push_enabled: true, email_enabled: true, push_ttl_seconds: 45, email_ttl_seconds: 120,
    max_attempts: 3, resend_cooldown_seconds: 30, max_requests_per_user: 5, max_requests_per_ip: 20, max_failed_verifications: 5,
    allow_global_admins: false, push_require_phone: true, email_require_email: true, max_contact_mismatches: 5 },
  limits: { ttl: [30, 300], max_attempts: [1, 10], resend_cooldown: [10, 300], max_requests_per_user: [1, 20],
    max_requests_per_ip: [1, 500], max_failed_verifications: [1, 20], max_contact_mismatches: [1, 20], window_minutes: 15 },
  status: { push_gateway_configured: true, smtp: { host: 'smtp-relay.very-long-corporate-domain.internal.example.com', configured: true,
    alarm_mail_enabled: false }, ldap_integration_enabled: true, secret_key_ephemeral: true },
  public: { ldap: false, otp_push: true, otp_email: true, push_ttl: 45, email_ttl: 120, resend_cooldown: 30,
    push_requires_phone: true, email_requires_email: true },
  coverage: { active_users: 12480, with_phone: 9150, with_email: 12466 },
  activity: Array.from({ length: 8 }, (_, i) => ({ id: i + 1, event_type: ['LOGIN_OTP_REQUESTED', 'LOGIN_OTP_VERIFY_FAILED', 'LOGIN', 'LOGIN_OTP_LOCKED'][i % 4],
    event_time: '2026-10-02T10:0' + i + ':00', actor: 'GLOBAL.YONETICI.UZUN.KULLANICI.ADI.' + i, outcome: ['SUCCESS', 'FAILURE', 'SUCCESS', 'BLOCKED'][i % 4],
    ip_address: '2001:db8:85a3::8a2e:370:733' + i, failure_reason: i % 4 === 1 ? 'OTP_INVALID: yanlış kod' : null,
    detail: '{"channel":"EMAIL","result":"SUPPRESSED","reason":"USER_RATE_LIMITED","challenge":"1a2b3c4d"}' })),
  activity_types: ['LOGIN_OTP_REQUESTED', 'LOGIN_OTP_DELIVERY_FAILED', 'LOGIN_OTP_VERIFY_FAILED', 'LOGIN_OTP_EXPIRED', 'LOGIN_OTP_LOCKED'],
} }
for (const vp of VIEWPORTS) {
  test(`giriş ekranı kodla giriş akışı @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page)
    await page.route((u) => new URL(u).pathname === '/api/me', json({ success: false, error: 'Unauthorized' }, 401))
    await page.route((u) => new URL(u).pathname === '/api/public/login-methods', json(OTP_METHODS))
    await page.route((u) => new URL(u).pathname === '/api/login/otp/request', json({ success: true,
      challenge_id: '11111111-2222-3333-4444-555555555555', channel: 'push', expires_in: 45, resend_in: 30 }))
    await page.route((u) => new URL(u).pathname === '/api/login/otp/verify',
      json({ success: false, code: 'OTP_INVALID', error_code: 'OTP_INVALID', error: 'x', attempts_left: 2 }, 401))
    await page.goto('/')
    const methods = page.locator('[data-slot="login-otp-methods"]')
    await methods.waitFor({ timeout: 20_000 })
    await page.waitForTimeout(300)
    let m = await page.evaluate(measure, 'body')
    expect(m.offenders, `giriş (yöntem düğmeleri) @${vp.name}: taşan öğe`).toEqual([])
    expect(m.pageOverflow, `giriş @${vp.name}: sayfa taşması (px)`).toBeLessThanOrEqual(1)
    for (const b of await methods.locator('button').all()) {
      const box = await b.boundingBox()
      expect(box.height, `yöntem düğmesi @${vp.name}: dokunma hedefi (px)`).toBeGreaterThanOrEqual(39)
      expect(box.x + box.width, `yöntem düğmesi @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
    }
    await methods.locator('[data-channel="push"]').click()
    const flow = page.locator('[data-slot="otp-flow"]')
    await flow.waitFor()
    await flow.locator('input[autocomplete="username"]').fill('kullanici.adi.uzun')
    m = await page.evaluate(measure, 'body')
    expect(m.offenders, `kod isteği adımı @${vp.name}: taşan öğe`).toEqual([])
    // Kişi bilgisi alanı (2026-10-03): push → kayıtlı cep telefonu — görünür, tam genişlik, ≥ 40 px, taşmaz
    const phone = flow.locator('[data-slot="otp-contact"][data-kind="phone"] input')
    await expect(phone).toBeVisible()
    await expect(phone).toHaveAttribute('type', 'tel')
    const user = await flow.locator('input[autocomplete="username"]').boundingBox()
    let cbox = await flow.locator('[data-slot="otp-contact"][data-kind="phone"]').boundingBox()
    expect(cbox.height, `telefon alanı @${vp.name}: dokunma hedefi (px)`).toBeGreaterThanOrEqual(39)
    expect(cbox.x + cbox.width, `telefon alanı @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
    expect(cbox.y, `telefon alanı @${vp.name}: kullanıcı adının ALTINDA`).toBeGreaterThan(user.y)
    expect(Math.abs(cbox.width - user.width), `telefon alanı @${vp.name}: kullanıcı adıyla aynı genişlik`).toBeLessThanOrEqual(2)
    await phone.fill('+90 (500) 000-00-00 / dahili 1234')   // uzun değer de kutuda kalır
    await phone.fill('05000000000')
    await expect(phone).toHaveValue('0500 000 00 00')
    // Boş gönderim: hata metni alanın altında, taşmaz
    await phone.fill('')
    await flow.locator('[data-slot="otp-send"]').click()
    await expect(flow.locator('[data-field="phone"]')).toHaveAttribute('data-invalid', 'true')
    m = await page.evaluate(measure, 'body')
    expect(m.offenders, `telefon hatası @${vp.name}: taşan öğe`).toEqual([])
    // Kanal seçici (telefonda 40 px) → e-posta alanı
    const seg = flow.getByRole('button', { name: /^Email$|^E-posta$/ })
    const segBox = await seg.boundingBox()
    if (vp.width < 640) expect(segBox.height, `kanal seçici @${vp.name}: dokunma hedefi (px)`).toBeGreaterThanOrEqual(39)
    await seg.click()
    const mail = flow.locator('[data-slot="otp-contact"][data-kind="email"] input')
    await expect(mail).toBeVisible()
    await expect(mail).toHaveAttribute('type', 'email')
    await mail.fill('cok.uzun.bir.kullanici.adi.soyadi@kurumsal-alan-adi.example.com')
    cbox = await flow.locator('[data-slot="otp-contact"][data-kind="email"]').boundingBox()
    expect(cbox.x + cbox.width, `e-posta alanı @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
    m = await page.evaluate(measure, 'body')
    expect(m.offenders, `e-posta alanı @${vp.name}: taşan öğe`).toEqual([])
    expect(m.pageOverflow, `e-posta alanı @${vp.name}: sayfa taşması (px)`).toBeLessThanOrEqual(1)
    await flow.getByRole('button', { name: /^Push notification$|^Push bildirimi$/ }).click()
    await phone.fill('0500 000 00 00')
    await flow.locator('[data-slot="otp-send"]').click()
    await expect(flow).toHaveAttribute('data-step', 'code')
    await page.locator('[data-slot="otp-countdown"]').waitFor()
    await page.waitForTimeout(300)
    m = await page.evaluate(measure, 'body')
    expect(m.offenders, `kod adımı @${vp.name}: taşan öğe`).toEqual([])
    expect(m.pageOverflow, `kod adımı @${vp.name}: sayfa taşması (px)`).toBeLessThanOrEqual(1)
    const otp = await page.locator('[data-slot="otp-input"]').boundingBox()
    expect(otp.height, `kod kutuları @${vp.name}: dokunma hedefi (px)`).toBeGreaterThanOrEqual(40)
    expect(otp.x + otp.width, `kod kutuları @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
    for (const sel of ['[data-slot="otp-verify"]', '[data-slot="otp-resend"]', '[data-slot="otp-back"]', '[data-slot="otp-change-user"]']) {
      const b = await page.locator(sel).first().boundingBox()
      expect(b.height, `${sel} @${vp.name}: dokunma hedefi (px)`).toBeGreaterThanOrEqual(39)
      expect(b.x + b.width, `${sel} @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
    }
    // Yanlış kod: hata satırı da taşmaz
    await page.locator('[data-slot="otp-input-field"]').fill('123456')
    await page.locator('[data-slot="otp-error"][data-kind="invalid"]').waitFor({ timeout: 5_000 })
    m = await page.evaluate(measure, 'body')
    expect(m.offenders, `kod hatası @${vp.name}: taşan öğe`).toEqual([])
  })

  test(`giriş yöntemleri ayarları @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page)
    await page.route((u) => new URL(u).pathname === '/api/admin/login-methods', json(LM_ADMIN))
    await page.goto('/?tab=settings&sec=loginmethods')
    await page.locator('[data-testid="loginmethods-settings"] [data-slot="lm-method"]').first().waitFor({ timeout: 20_000 })
    await page.locator('[data-slot="lm-activity-row"]').first().waitFor()
    await page.waitForTimeout(500)
    const m = await page.evaluate(measure)
    expect(m.offenders, `giriş yöntemleri @${vp.name}: taşan öğe`).toEqual([])
    expect(m.pageOverflow, `giriş yöntemleri @${vp.name}: sayfa taşması (px)`).toBeLessThanOrEqual(1)
    for (const sw of await page.locator('[data-slot="lm-method"] [role="switch"]').all()) {
      const row = await sw.locator('xpath=..').boundingBox()
      if (vp.width < 768) expect(row.height, `anahtar satırı @${vp.name}: dokunma hedefi (px)`).toBeGreaterThanOrEqual(39)
    }
    const link = await page.locator('[data-slot="lm-activity-audit-link"]').boundingBox()
    expect(link.height, `denetim bağlantısı @${vp.name}: dokunma hedefi (px)`).toBeGreaterThanOrEqual(39)
    // 2026-10-03: "da sorulsun" anahtarları + kapsam ipuçları (telefon %73 → uyarı tonu) + önizlemedeki kişi bilgisi alanı
    await expect(page.locator('[data-slot="lm-require"]')).toHaveCount(2)
    const cov = page.locator('[data-slot="lm-coverage"][data-kind="phone"]')
    await expect(cov).toHaveAttribute('data-tone', 'warn')
    for (const c of await page.locator('[data-slot="lm-coverage"]').all()) {
      const b = await c.boundingBox()
      expect(b.x + b.width, `kapsam ipucu @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
    }
    await expect(page.locator('[data-slot="lm-preview-request"] [data-slot="otp-contact"][data-kind="phone"]')).toBeVisible()
    await expect(page.locator('[data-field="max_contact_mismatches"]')).toBeVisible()
    // Önizleme EN'e geçince de taşmaz
    await page.locator('[data-slot="lm-preview"]').getByRole('button', { name: 'EN' }).click()
    await expect(page.locator('[data-slot="lm-preview"]')).toHaveAttribute('data-lang', 'en')
    await page.waitForTimeout(300)
    const m2 = await page.evaluate(measure)
    expect(m2.offenders, `önizleme (EN) @${vp.name}: taşan öğe`).toEqual([])
  })
}

// ── Giriş Yöntemleri: push metni düzenleyicisi + İstatistikler sekmesi + kullanıcı Sheet'i (2026-10-03) ──────────────
// Telefon, tablet VE masaüstü (1280): sayfa taşmaz, görünür öğe sağdan taşmaz; telefonda dokunma hedefleri ≥ 40 px.
const VIEWPORTS_3 = [...VIEWPORTS, { name: 'desktop', width: 1280, height: 800 }]
const LONG_USER = 'GLOBAL.YONETICI.UZUN.KULLANICI.ADI'
const LM_ADMIN_PUSH = { success: true, data: {
  ...LM_ADMIN.data,
  settings: { ...LM_ADMIN.data.settings,
    push_title_tr: 'SiteMonitor giriş kodu — kurum içi çok uzun bir başlık denemesi',
    push_title_en: 'SiteMonitor sign-in code',
    push_message_tr: 'SiteMonitor giriş kodunuz: {kod} — {sure} sn geçerli ({saat}). Bu isteği siz yapmadıysanız dikkate almayın ✓ 😀',
    push_message_en: 'Your SiteMonitor sign-in code: {kod} - valid for {sure} s. If you did not request it, ignore this message.' },
  push_template: {
    defaults: {
      tr: { title: 'SiteMonitor giriş kodu', message: 'SiteMonitor giriş kodunuz: {kod} - {sure} sn geçerli. Bu isteği siz yapmadıysanız dikkate almayın.' },
      en: { title: 'SiteMonitor sign-in code', message: 'Your SiteMonitor sign-in code: {kod} - valid for {sure} s. If you did not request it, ignore this message.' },
    },
    placeholders: [{ key: 'kod', token: '{kod}', required: true, sample: '123456', worst_len: 6 },
      { key: 'sure', token: '{sure}', required: false, sample: '45', worst_len: 3 },
      { key: 'saat', token: '{saat}', required: false, sample: '12:30', worst_len: 5 }],
    title_max: 60, message_max: 200, charset: 'ISO-8859-9', stored_invalid: { tr: false, en: true },
  },
} }
const LM_CH = (channel, success, failed, otp) => ({ channel, success, failed, attempts: success + failed,
  success_rate: success + failed ? success / (success + failed) : null, unique_users: Math.min(success, 812), share: success / 25_000,
  estimated: channel === 'LDAP' ? 1204 : 0, ...(otp ? { otp } : {}) })
const LM_FUNNEL = { requested: 12_480, sent: 9_150, verified: 8_870, suppressed: 3_200, rate_limited: 130, delivery_failed: 45,
  wrong_code: 610, expired: 210, locked: 33, conversion: 0.9694,
  suppressed_reasons: [{ reason: 'CONTACT_MISMATCH', count: 1400 }, { reason: 'GLOBAL_ADMIN_NOT_ALLOWED', count: 900 }, { reason: 'USER_RATE_LIMITED', count: 400 }] }
const LM_STATS = { success: true, data: {
  days: 90, granularity: 'day', from: '2026-07-05T21:00:00', to: '2026-10-03T09:30:00', generated_at: SM_ISO(-90_000),
  truncated: true, row_count: 250_000, row_cap: 250_000, estimated: 1204,
  totals: { attempts: 1_284_310, success: 1_120_400, failed: 163_910, success_rate: 0.8724, unique_users: 12_480,
    unknown_user_failures: 41_230, unattributed_success: 0, delivery_failures: 45 },
  previous: { attempts: 1_100_000, success: 980_000, failed: 120_000, success_rate: 0.8909, unique_users: 12_100 },
  channels: [LM_CH('LDAP', 820_400, 100_210), LM_CH('LOCAL', 1_200, 300), LM_CH('OTP_PUSH', 8_870, 853, LM_FUNNEL),
    LM_CH('OTP_EMAIL', 2_100, 70, { ...LM_FUNNEL, delivery_failed: 0 }), LM_CH('REMEMBER_ME', 287_830, 1_247)],
  series: Array.from({ length: 90 }, (_, i) => ({ ts: SM_ISO((i - 90) * 86_400_000).slice(0, 19), LDAP: 9000 + i, LOCAL: 13, OTP_PUSH: 98,
    OTP_EMAIL: 23, REMEMBER_ME: 3198, OTHER: 0, failed: 1800 })),
  failure_reasons: [
    { reason: 'BAD_PASSWORD', count: 90_000, channels: { LDAP: 89_000, LOCAL: 1_000 } },
    { reason: 'UNKNOWN_USER', count: 41_230, channels: { UNKNOWN: 41_230 } },
    { reason: 'LDAP_LOGIN_DISABLED_WITH_A_VERY_LONG_UNKNOWN_REASON_CODE', count: 300, channels: { LDAP: 300 } },
    { reason: 'OTP_INVALID', count: 610, channels: { OTP_PUSH: 600, OTP_EMAIL: 10 } },
  ],
} }
const LM_USER_ROW = (i) => ({ username: `${LONG_USER}.${i}`, display_name: `Çok Uzun Görünen Ad Soyad Kullanıcı ${i}`, team_id: 1,
  team_name: 'Takım A — Çok Uzun Platform ve Uygulama Geliştirme Takımı Adı', source: i % 2 ? 'LDAP' : 'LOCAL', active: i % 5 !== 0,
  success: { LDAP: 1200 + i, LOCAL: 0, OTP_PUSH: 30, OTP_EMAIL: 4, REMEMBER_ME: 880 }, failed_by: { LDAP: 12 }, success_total: 2114 + i,
  failed: 12, attempts: 2126 + i, success_rate: 0.9943, estimated: 3,
  last_success: { at: SM_ISO(-3_600_000).slice(0, 19), channel: 'REMEMBER_ME' }, last_failure: { at: SM_ISO(-86_400_000).slice(0, 19), reason: 'BAD_PASSWORD' } })
const LM_USERS = { success: true, data: { items: Array.from({ length: 12 }, (_, i) => LM_USER_ROW(i + 1)), total: 12_480, page: 1, size: 25, total_pages: 500 } }
const LM_USER_DETAIL = { success: true, data: {
  user: { username: `${LONG_USER}.1`, display_name: 'Çok Uzun Görünen Ad Soyad Kullanıcı 1', team_id: 1,
    team_name: 'Takım A — Çok Uzun Platform ve Uygulama Geliştirme Takımı Adı', source: 'LDAP', active: false },
  found: true, days: 90, granularity: 'day', truncated: false, estimated: 3, identity_masked: false,
  totals: { attempts: 2126, success: 2114, failed: 12, success_rate: 0.9943, unique_users: 1 },
  channels: [LM_CH('LDAP', 1201, 12), LM_CH('OTP_PUSH', 30, 0), LM_CH('REMEMBER_ME', 880, 0)],
  failure_reasons: [{ reason: 'BAD_PASSWORD', count: 12, channels: { LDAP: 12 } }],
  series: Array.from({ length: 90 }, (_, i) => ({ ts: SM_ISO((i - 90) * 86_400_000).slice(0, 19), success: 23, failed: i % 7 ? 0 : 1 })),
  recent: Array.from({ length: 30 }, (_, i) => ({ id: 1000 - i, time: SM_ISO(-i * 3_600_000).slice(0, 19),
    event: ['LOGIN', 'LOGIN_FAILED', 'LOGIN_OTP_REQUESTED', 'LOGIN_OTP_VERIFY_FAILED'][i % 4], actor: `${LONG_USER}.1`,
    channel: ['REMEMBER_ME', 'LDAP', 'OTP_PUSH', 'OTP_PUSH'][i % 4], channel_estimated: i % 9 === 0,
    outcome: ['SUCCESS', 'FAILURE', 'BLOCKED', 'FAILURE'][i % 4], reason: ['', 'BAD_PASSWORD', 'CONTACT_MISMATCH', 'OTP_INVALID'][i % 4] || null,
    ip: '2001:db8:85a3::8a2e:370:7334', city: 'Belgeleme Şehri', country: 'Belgeland', ua_summary: 'Chrome 130 · Windows 10 · Masaüstü',
    flags: i % 6 === 0 ? 'OFF_HOURS,UNUSUAL_IP' : null })),
} }

async function lmStatsRoutes(page) {
  await page.route((u) => new URL(u).pathname === '/api/admin/login-methods', json(LM_ADMIN_PUSH))
  await page.route((u) => new URL(u).pathname === '/api/admin/login-methods/stats', json(LM_STATS))
  await page.route((u) => new URL(u).pathname === '/api/admin/login-methods/stats/users', json(LM_USERS))
  await page.route((u) => new URL(u).pathname.startsWith('/api/admin/login-methods/stats/users/'), json(LM_USER_DETAIL))
}

for (const vp of VIEWPORTS_3) {
  test(`giriş istatistikleri sekmesi @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page)
    await lmStatsRoutes(page)
    await page.goto('/?tab=settings&sec=loginmethods&lm_tab=stats&lm_p=90')
    await page.locator('[data-slot="lm-kpi"]').first().waitFor({ timeout: 20_000 })
    await page.locator('[data-slot="lm-user-row"]').first().waitFor()
    await page.waitForTimeout(600)
    const m = await page.evaluate(measure)
    expect(m.offenders, `istatistikler @${vp.name}: taşan öğe`).toEqual([])
    expect(m.pageOverflow, `istatistikler @${vp.name}: sayfa taşması (px)`).toBeLessThanOrEqual(1)
    await expect(page.locator('[data-slot="lm-kpi"]')).toHaveCount(5)
    await expect(page.locator('[data-slot="lm-channel"]')).toHaveCount(5)
    await expect(page.locator('[data-slot="lm-stats-truncated"]')).toBeVisible()
    const touch = ['[data-slot="lm-stats-refresh"]', '[data-slot="lm-users-csv"]', '[data-slot="lm-channel-filter"]',
      '[data-slot="lm-tabs"] [role="tab"]', '[data-slot="lm-user-open"]']
    for (const sel of touch) {
      const b = await page.locator(sel).first().boundingBox()
      expect(b.x + b.width, `${sel} @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
      if (vp.width < 640) expect(b.height, `${sel} @${vp.name}: dokunma hedefi (px)`).toBeGreaterThanOrEqual(39)
    }
    // Liste KABININ genişliğine göre (görünüm alanı değil — ayar menüsü + kenar çubuğu içeriği daraltır): ≥ 760 px tablo,
    // altı kart; ikisinde de taşma yok
    const tableRows = await page.locator('[data-slot="lm-users"] table [data-slot="lm-user-row"]').count()
    const listWidth = await page.evaluate(() => document.querySelector('[data-slot="lm-users"]').clientWidth)
    if (listWidth >= 760) expect(tableRows, `geniş kapta (${listWidth}px) tablo görünümü`).toBeGreaterThan(0)
    else expect(tableRows, `dar kapta (${listWidth}px) kart görünümü`).toBe(0)
    // KPI ızgarası kaba göre: dar kapta 2, orta kapta 3 sütun — kartlar okunur genişlikte kalır
    const kpiW = await page.locator('[data-slot="lm-kpi"]').first().boundingBox()
    expect(kpiW.width, `KPI kartı @${vp.name}: okunur genişlik (px)`).toBeGreaterThanOrEqual(140)
    // Kanal kartı süzgeci: dokununca kart vurgulanır, tablo süzgeci o kanalı gösterir; yine taşma yok
    await page.locator('[data-slot="lm-channel"][data-channel="OTP_PUSH"] [data-slot="lm-channel-filter"]').click()
    await expect(page.locator('[data-slot="lm-users-channel"]')).toHaveValue('OTP_PUSH')
    await page.waitForTimeout(300)
    const m2 = await page.evaluate(measure)
    expect(m2.offenders, `kanal süzgeci @${vp.name}: taşan öğe`).toEqual([])
  })

  test(`giriş istatistikleri kullanıcı ayrıntısı @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page)
    await lmStatsRoutes(page)
    await page.goto(`/?tab=settings&sec=loginmethods&lm_tab=stats&lm_user=${encodeURIComponent(`${LONG_USER}.1`)}`)
    const sheet = page.locator('[data-slot="lm-user-sheet"]')
    await sheet.waitFor({ timeout: 20_000 })
    await page.locator('[data-slot="lm-user-event"]').first().waitFor()
    await page.waitForTimeout(600)
    const m = await page.evaluate(measure, '[data-slot="lm-user-sheet"]')
    expect(m.offenders, `kullanıcı ayrıntısı @${vp.name}: taşan öğe`).toEqual([])
    const box = await sheet.boundingBox()
    expect(box.x + box.width, `Sheet @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
    expect(box.width, `Sheet @${vp.name}: ekrana sığar`).toBeLessThanOrEqual(vp.width)
    const close = await sheet.locator('[data-slot="sheet-close"]').boundingBox()
    expect(close.x + close.width, `kapat @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
    await expect(sheet.locator('[data-slot="login-channel"]').first()).toBeVisible()
    // Gövde kayar (30 olay): son olay kaydırılarak görünür olur
    await sheet.locator('[data-slot="lm-user-event"]').last().scrollIntoViewIfNeeded()
    await expect(sheet.locator('[data-slot="lm-user-event"]').last()).toBeInViewport()
  })

  test(`push metni düzenleyicisi @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page)
    await lmStatsRoutes(page)
    await page.goto('/?tab=settings&sec=loginmethods')
    const editor = page.locator('[data-slot="lm-push-template"]')
    await editor.waitFor({ timeout: 20_000 })
    await editor.scrollIntoViewIfNeeded()
    await page.waitForTimeout(500)
    const m = await page.evaluate(measure)
    expect(m.offenders, `push düzenleyicisi @${vp.name}: taşan öğe`).toEqual([])
    expect(m.pageOverflow, `push düzenleyicisi @${vp.name}: sayfa taşması (px)`).toBeLessThanOrEqual(1)
    await expect(editor.locator('[data-slot="lm-push-chars"]').first()).toBeVisible()           // — ✓ 😀 dönüşür / düşer
    await expect(page.locator('[data-slot="lm-push-stored-invalid"]')).toBeVisible()
    const preview = editor.locator('[data-slot="lm-push-preview"][data-lang="tr"]')
    const pb = await preview.boundingBox()
    expect(pb.x + pb.width, `önizleme @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
    await expect(preview.locator('[data-slot="lm-push-preview-message"]')).toContainText('123456 - ')
    for (const sel of ['[data-slot="lm-push-chip"]', '[data-slot="lm-push-test"]', '[data-slot="lm-push-reset"]']) {
      const b = await editor.locator(sel).first().boundingBox()
      expect(b.x + b.width, `${sel} @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
      if (vp.width < 640) expect(b.height, `${sel} @${vp.name}: dokunma hedefi (px)`).toBeGreaterThanOrEqual(39)
    }
    // EN sekmesi de taşmaz
    await editor.getByRole('tab', { name: /English|İngilizce/ }).click()
    await expect(editor).toHaveAttribute('data-lang', 'en')
    await page.waitForTimeout(300)
    const m2 = await page.evaluate(measure)
    expect(m2.offenders, `push düzenleyicisi (EN) @${vp.name}: taşan öğe`).toEqual([])
  })
}

// ── Kişisel push (2026-10-04, onaylı öneriler 2–6): Etkinliklerim'deki "Bildirim tercihlerim" kartı + "Push bildirimlerim"
// bölümü ve Ayarlar → Webhook Bildirimleri'ndeki saat tavanı özeti / kritik muafiyeti / EN şablon sekmesi. Telefon, tablet
// ve dizüstü (1280) boyunda: sayfa taşmaz, kart/liste ekrana sığar, dokunma hedefleri (telefonda) ≥ 40 px.
const PUSH_VIEWPORTS = [...VIEWPORTS, { name: 'laptop', width: 1280, height: 800 }]
for (const vp of PUSH_VIEWPORTS) {
  test(`bildirim tercihlerim + push geçmişim @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await mockApi(page)
    await page.goto('/?tab=myactivity')
    const prefs = page.locator('[data-slot="push-prefs"]')
    await prefs.locator('[data-slot="push-snooze"]').waitFor({ timeout: 20_000 })
    await prefs.scrollIntoViewIfNeeded()
    await expect(prefs.locator('[data-slot="push-snooze-state"]')).toBeVisible()
    const pb = await prefs.boundingBox()
    expect(pb.x + pb.width, `tercih kartı @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
    const targets = [
      ...await prefs.locator('[data-preset]').all(),
      ...await prefs.locator('[data-family]').all(),
      prefs.getByRole('button', { name: /Send me a test push|Kendime test/ }),
    ]
    for (const loc of targets) {
      const b = await loc.boundingBox()
      expect(b.x + b.width, `tercih kartı denetimi @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
      if (vp.width < 640) expect(b.height, `tercih kartı dokunma hedefi @${vp.name} (px)`).toBeGreaterThanOrEqual(39)
    }

    const hist = page.locator('[data-slot="push-history"]')
    await hist.locator('[data-slot="ph-row"]').first().waitFor({ timeout: 20_000 })
    await hist.scrollIntoViewIfNeeded()
    // Dar kapta kart, geniş kapta tablo — telefonda her durumda kart
    if (vp.width < 640) await expect(hist.getByTestId('ph-cards')).toBeVisible()
    else if (vp.width >= 1280) await expect(hist.getByTestId('ph-table')).toBeVisible()
    for (const loc of await hist.locator('[data-slot="ph-row"]').all()) {
      const b = await loc.boundingBox()
      expect(b.x + b.width, `push geçmişi satırı @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
    }
    if (vp.width < 640) {
      for (const loc of await hist.getByRole('group').getByRole('button').all()) {
        const b = await loc.boundingBox()
        expect(b.height, `push geçmişi süzgeç/dönem düğmesi @${vp.name} (px)`).toBeGreaterThanOrEqual(39)
      }
    }
    const m = await page.evaluate(measure)
    expect(m.pageOverflow, `myactivity push @${vp.name}: sayfa düzeyinde yatay taşma (px)`).toBeLessThanOrEqual(1)
    expect(m.offenders, `myactivity push @${vp.name}: görünür öğe ekran dışına çıkıyor`).toEqual([])
  })

  test(`webhook bildirimleri ayarı: saat tavanı özeti + EN şablon sekmesi @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await page.addInitScript(() => {
      try { localStorage.setItem('sm.userpush.sections', JSON.stringify({ conn: true, quiet: true, templates: true, test: true })) } catch { /* yoksay */ }
    })
    await mockApi(page)
    await page.goto('/?tab=settings&sec=userpush')
    const overflow = page.locator('[data-slot="userpush-overflow"]')
    await overflow.waitFor({ timeout: 20_000 })
    await overflow.scrollIntoViewIfNeeded()
    const ob = await overflow.boundingBox()
    expect(ob.x + ob.width, `saat tavanı bloğu @${vp.name}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
    const tabs = page.locator('[data-slot="userpush-tpl-tabs"]')
    await tabs.scrollIntoViewIfNeeded()
    await tabs.getByRole('tab', { name: /English|İngilizce/ }).click()
    await page.locator('[data-slot="userpush-tpl-en"]').waitFor({ timeout: 10_000 })
    for (const loc of await tabs.getByRole('tab').all()) {
      const b = await loc.boundingBox()
      if (vp.width < 640) expect(b.height, `şablon dil sekmesi @${vp.name} (px)`).toBeGreaterThanOrEqual(39)
    }
    await page.waitForTimeout(300)
    const m = await page.evaluate(measure)
    expect(m.pageOverflow, `webhook ayarı @${vp.name}: sayfa düzeyinde yatay taşma (px)`).toBeLessThanOrEqual(1)
    expect(m.offenders, `webhook ayarı @${vp.name}: görünür öğe ekran dışına çıkıyor`).toEqual([])
  })
}
