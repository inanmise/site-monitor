// Olay & Hata Geçmişi — "Hızlı Giriş" paket 2 (2026-11-20). Gerçek tarayıcıda 390 (dokunmatik) / 768 (dokunmatik) / 1280:
//  1) "Hızlı kayıt" beş alanlık sade form; kayıt tam gövdeyle gider,
//  2) satırdaki durum rozeti tek tıkla durumu kaydeder (Çözüldü'de çözülme zamanı şimdi), gövde = değişenler + sürüm,
//  3) liste `fields=summary` ister; ayrıntı tam kaydı okur (markdown önce "yükleniyor"), runbook bağlantısı yeni sekmede,
//     görsel tıklanınca büyür, olmayan görsel "artık yok",
//  4) Çoğalt tam formu yeni kayıt olarak açar (kopya bandı, görseller çıkarılmış),
//  5) markdown alanına YAPIŞTIRILAN görsel imlece yer tutucuyla girer, yükleme bitince gerçek bağlantıya döner,
//  6) N kısayolu yeni olay formunu açar.
// Bant / açılır pencere / ayrıntı ekrana sığar, metin kırpılmaz, dokunmatik hedefler ≥ 40 px, sayfa yatay kaymaz.
import { test, expect } from '@playwright/test'

const NOW = new Date('2026-11-20T09:00:00Z')
const TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi'
const PNG_1PX = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=='
const BASE = Object.freeze({
  id: 9, version: 1, title: 'Ödeme servisinde gecikme — kart işlemleri yavaş yanıt veriyor',
  occurred_at: '2026-11-19T07:00:00', detected_at: '2026-11-19T07:10:00', resolved_at: null,
  severity: 'HIGH', status: 'OPEN', category: 'APPLICATION', team_id: 5, team_name: TEAM,
  error_code: 'E-500', function_code: '', channel_code: '', service: '', channel: '', affected_services: 'Kart ödemeleri',
  problem_types: '', affected_app: '', affected_systems: '', affected_customers: null, affected_transactions: null,
  sla_breached: false, error_budget_burn_pct: null, duration_minutes: null,
  runbook_url: 'https://confluence.example.com/display/SRE/Odeme+Servisi+Gecikme+Runbook+Uzun+Bir+Sayfa+Adi',
  tags: '', tech_error_count: null, outage_related: null, outage_id: null,
  created_by: 'demo', created_at: '2026-11-19T07:20:00', updated_by: 'demo', updated_at: '2026-11-19T07:20:00', can_edit: true,
})
const MARKDOWN = Object.freeze({
  rca_summary: 'Bağlantı havuzu tükendi.',
  description: 'Kart işlemleri yavaşladı.\n\n**Grafik**\n![Grafik](/api/incidents/images/3)\n\n![Silinmiş](/api/incidents/images/404)',
  resolution_steps: 'Havuz büyütüldü.',
  business_impact: 'Ödemeler gecikti.',
})

let server = null          // GET /api/incidents/9 (tam kayıt)
let listRow = null         // listenin döndürdüğü ÖZET satır (markdown yok)
let puts = []
let posts = []
let listQueries = []
let uploads = 0
let holdUpload = null      // yükleme yanıtını bekletmek için (yer tutucu görünür kalsın)

function summaryOf(rec) {
  const out = { ...rec, markdown_omitted: true }
  for (const k of Object.keys(MARKDOWN)) delete out[k]
  return out
}

async function mock(page) {
  server = { ...BASE, ...MARKDOWN }
  listRow = summaryOf(server)
  puts = []; posts = []; listQueries = []; uploads = 0; holdUpload = null
  await page.addInitScript(() => {
    try {
      localStorage.setItem('sm.tour', JSON.stringify({ status: 'dismissed', version: 99 }))
      localStorage.setItem('site-monitor-lang', 'tr')
    } catch { /* yoksay */ }
  })
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const req = route.request()
    const url = new URL(req.url())
    const p = url.pathname
    let status = 200
    let body = { success: true, data: [] }
    if (p === '/api/incidents/images/3') {
      await route.fulfill({ status: 200, contentType: 'image/png', body: Buffer.from(PNG_1PX, 'base64') })
      return
    }
    if (p === '/api/incidents/images/404') {
      await route.fulfill({ status: 404, contentType: 'text/plain', body: 'yok' })
      return
    }
    if (p === '/api/me') body = { success: true, username: 'demo', system_role: 'ADMIN', global_admin: true, team_id: 5, team_ids: [5], tour: { status: 'dismissed', version: 99 } }
    else if (p === '/api/me/permissions') body = { success: true, data: { 'incidents.view': { view: true }, 'incidents.manage': { edit: true } } }
    else if (p === '/api/admin/teams') body = { success: true, data: [{ id: 5, name: TEAM }] }
    else if (p === '/api/incidents' && req.method() === 'GET') {
      listQueries.push(url.searchParams.get('fields'))
      body = { success: true, data: [listRow], total: 1, page: 0, size: 20 }
    } else if (p === '/api/incidents' && req.method() === 'POST') {
      const sent = JSON.parse(req.postData() || '{}')
      posts.push(sent)
      body = { success: true, data: { ...BASE, ...sent, id: 10, version: 0 } }
    } else if (p === '/api/incidents/images' && req.method() === 'POST') {
      uploads += 1
      if (holdUpload) await holdUpload
      body = { success: true, data: { id: 77 } }
    } else if (p === '/api/incidents/trends') body = { success: true, data: { summary: { total: 1, open: 1, resolved: 0 }, by_severity: {}, by_status: {}, daily: [] } }
    else if (p === '/api/incidents/9' && req.method() === 'GET') body = { success: true, data: server }
    else if (p === '/api/incidents/9' && req.method() === 'PUT') {
      const sent = JSON.parse(req.postData() || '{}')
      puts.push(sent)
      if (sent.version != null && sent.version !== server.version) {
        status = 409
        body = { success: false, code: 'CONCURRENT_MODIFICATION', error: 'Kayıt değişti.', current_version: server.version, updated_by: 'Ayşe Yılmaz', updated_at: '2026-11-20T08:45:00' }
      } else {
        const { id, version, send_notification, ...fields } = sent   // eslint-disable-line no-unused-vars
        server = { ...server, ...fields, version: server.version + 1, updated_by: 'demo', updated_at: '2026-11-20T09:00:00' }
        listRow = summaryOf(server)
        body = { success: true, data: server }
      }
    }
    await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

/** Görünür etkileşimli öğelerden 40 px'in altında kalanlar (dokunmatik): yükseklik, ya da ::after vuruş alanı. */
function smallTargets(root) {
  const out = []
  for (const b of root.querySelectorAll('button, select, input, a[href]')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0 || b.disabled || b.type === 'hidden') continue
    let h = r.height
    const after = getComputedStyle(b, '::after')
    if (after.position === 'absolute' && after.height && after.height !== 'auto') h = Math.max(h, parseFloat(after.height) || 0)
    if (h < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)}" ${Math.round(h)}`)
  }
  return out
}

/** Kırpılan / taşan metin (tek düğümlü yaprak öğeler); görünmez öğeler (≤ 2 px) sayılmaz. */
function clipped(root) {
  return [...root.querySelectorAll('span, p, label, button, div, a')]
    .filter((n) => { const r = n.getBoundingClientRect(); return r.width > 2 && r.height > 2 })
    .filter((n) => n.childElementCount === 0 && n.textContent.trim().length > 3
      && (n.scrollWidth > n.clientWidth + 1 || getComputedStyle(n).textOverflow === 'ellipsis'))
    .map((n) => n.textContent.slice(0, 40))
}

/** Öğenin içinden sağa taşan alt öğeler. */
function overflow(el) {
  const lim = el.getBoundingClientRect().right + 1
  if (el.scrollWidth <= el.clientWidth + 1) return []
  return [...el.querySelectorAll('*')].filter((n) => n.getBoundingClientRect().right > lim)
    .map((n) => `${n.tagName}.${(n.getAttribute('data-slot') || n.className || '').toString().slice(0, 60)} ${Math.round(n.getBoundingClientRect().right)}>${Math.round(lim)}`).slice(0, 8)
}

/** Açılış / kayma animasyonları bitene dek bekler (ölçek / öteleme sürerken ölçü yanlış olur); sonsuz animasyonlar hariç, ≤ 3 sn. */
async function settle(page) {
  await page.evaluate(() => Promise.race([
    Promise.all(document.getAnimations()
      .filter((a) => a.effect?.getComputedTiming?.().iterations !== Infinity)
      .map((a) => a.finished.catch(() => {}))),
    new Promise((r) => setTimeout(r, 3000)),
  ]))
}

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

for (const vp of VIEWPORTS) {
  test.describe(`Olay kaydı hızlı giriş — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch, timezoneId: 'Europe/Istanbul' })

    async function checkLayout(page, loc, label, { targets = true } = {}) {
      await expect(loc, label).toBeVisible()
      await settle(page)
      expect(await loc.evaluate(overflow), label).toEqual([])
      expect(await loc.evaluate(clipped), label).toEqual([])
      if (vp.touch && targets) expect(await loc.evaluate(smallTargets), label).toEqual([])
      const b = await loc.boundingBox()
      expect(b.x, label).toBeGreaterThanOrEqual(-0.5)
      expect(b.x + b.width, label).toBeLessThanOrEqual(vp.width + 0.5)
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1), label).toBe(true)
    }

    test('hızlı kayıt, tek tıkla durum, ayrıntı (özet satır), çoğaltma, N kısayolu', async ({ page }) => {
      test.setTimeout(150_000)
      await page.clock.setFixedTime(NOW)
      await mock(page)
      await page.goto('/?tab=incident-history')
      await expect(page.getByText(BASE.title).first()).toBeVisible()
      expect(listQueries[0]).toBe('summary')

      // 1) Hızlı kayıt
      await page.locator('[data-action="quick"]').click()
      const quick = page.getByRole('dialog', { name: /Hızlı olay kaydı/ })
      await expect(quick.locator('[data-slot="incident-form"]')).toHaveAttribute('data-view', 'quick')
      await checkLayout(page, quick.locator('[data-slot="ih-quick"]'), 'hızlı kayıt formu')
      await quick.getByRole('textbox', { name: /^Başlık/ }).fill('Mobil şubede giriş hatası')
      await quick.locator('[data-action="save"]').click()
      await expect(page.getByRole('dialog', { name: /Hızlı olay kaydı/ })).toHaveCount(0)
      expect(posts).toHaveLength(1)
      expect(posts[0]).toMatchObject({ title: 'Mobil şubede giriş hatası', status: 'OPEN', occurred_at: '2026-11-20T09:00:00' })
      expect(Number(posts[0].team_id)).toBe(5)

      // 2) Tek tıkla durum: satır rozeti → Çözüldü → çözülme zamanı (şimdi) → kaydet
      const trigger = page.locator(`[data-incident-id="9"] [data-slot="ih-qs-trigger"]`).first()
      await trigger.click()
      const pop = page.locator('[data-slot="ih-qs"]')
      await expect(pop.locator('button[data-status="INVESTIGATING"]')).toHaveAttribute('data-next', 'true')
      await checkLayout(page, pop, 'durum açılır penceresi')
      await pop.locator('button[data-status="RESOLVED"]').click()
      await expect(pop.locator('[data-slot="ih-qs-resolve"]')).toBeVisible()
      await checkLayout(page, pop, 'çözüldü adımı')
      await pop.locator('[data-action="qs-save"]').click()
      await expect(page.locator('[data-slot="ih-qs"]')).toHaveCount(0)
      expect(puts).toHaveLength(1)
      expect(puts[0]).toEqual({ id: 9, version: 1, status: 'RESOLVED', resolved_at: '2026-11-20T09:00:00' })
      await expect(page.locator('[data-slot="ih-detail"]')).toHaveCount(0)           // satır tıklaması ayrıntıyı açmadı

      // 3) Ayrıntı: özet satırdan açılır, markdown tam kayıttan gelir; runbook bağlantısı; görsel büyütme / artık yok
      await page.getByText(server.title).first().click()
      const sheet = page.locator('[data-slot="ih-detail"]')
      await expect(sheet.getByText('Bağlantı havuzu tükendi.')).toBeVisible()
      const runbook = sheet.locator('[data-slot="ih-runbook-link"]')
      await expect(runbook).toHaveAttribute('target', '_blank')
      await expect(runbook).toHaveAttribute('rel', /noopener/)
      await expect(sheet.locator('[data-slot="md-image-gone"]')).toContainText('Görsel artık yok: Silinmiş')
      await checkLayout(page, sheet.locator('[data-slot="ih-detail-actions"]'), 'ayrıntı eylemleri')
      await checkLayout(page, sheet.locator('[data-fact="runbook"]'), 'runbook bağlantısı')
      await sheet.locator('[data-slot="md-image"]').click()
      const lightbox = page.locator('[data-slot="te-image-lightbox"]')
      await expect(lightbox).toBeVisible()
      // büyük görünüm ayrıntı penceresinin ÜSTÜNDE (katman): ortasındaki öğe onun içinde
      expect(await lightbox.evaluate((el) => {
        const r = el.getBoundingClientRect()
        return el.contains(document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2))
      })).toBe(true)
      await page.keyboard.press('Escape')
      await expect(lightbox).toHaveCount(0)
      await expect(sheet).toBeVisible()

      // 4) Çoğalt → tam form, kopya bandı, görseller çıkarılmış
      await sheet.locator('[data-action="duplicate"]').click()
      const copy = page.getByRole('dialog', { name: /Olay kaydını çoğalt/ })
      const note = copy.locator('[data-slot="ih-copy-note"]')
      await expect(note).toContainText('#9 kaydından çoğaltıldı')
      await expect(note.locator('[data-slot="ih-copy-images"]')).toContainText('2 görsel')
      await expect(copy.getByRole('textbox', { name: /^Başlık/ })).toHaveValue(server.title)
      await checkLayout(page, note, 'kopya bandı')
      await copy.getByRole('button', { name: /^Vazgeç$/ }).click()
      const discard = page.getByRole('button', { name: 'Değişiklikleri at' })
      await expect.poll(async () => (await discard.count()) > 0 || (await copy.count()) === 0).toBe(true)
      if (await discard.count()) await discard.click()
      await expect(page.getByRole('dialog', { name: /Olay kaydını çoğalt/ })).toHaveCount(0)
      await sheet.getByRole('button', { name: /^Kapat$/ }).click()
      await expect(sheet).toHaveCount(0)

      // 5) N kısayolu yeni olay formunu açar
      await page.evaluate(() => { if (document.activeElement && document.activeElement !== document.body) document.activeElement.blur() })
      await page.keyboard.press('n')
      await expect(page.getByRole('dialog', { name: /Yeni Olay/i })).toBeVisible()
      await expect(page.locator('[data-slot="incident-form"]')).toHaveAttribute('data-view', 'full')
    })

    test('markdown alanına yapıştırılan görsel yer tutucuyla girer, yükleme bitince bağlantıya döner', async ({ page }) => {
      test.setTimeout(120_000)
      await page.clock.setFixedTime(NOW)
      await mock(page)
      await page.goto('/?tab=incident-history')
      await expect(page.getByText(BASE.title).first()).toBeVisible()
      await page.locator('[data-action="new"]').click()
      const dlg = page.getByRole('dialog', { name: /Yeni Olay/i })
      await dlg.locator('[data-section="rca"]').click()
      const area = dlg.locator('[data-field="description"] textarea')
      await expect(area).toBeVisible()
      await area.fill('Önce yazılan metin.')
      let release
      holdUpload = new Promise((r) => { release = r })
      await area.evaluate(async (ta) => {
        const c = document.createElement('canvas')
        c.width = 320; c.height = 160
        const ctx = c.getContext('2d')
        ctx.fillStyle = '#336699'; ctx.fillRect(0, 0, 320, 160)
        const blob = await new Promise((r) => c.toBlob(r, 'image/png'))
        const dt = new DataTransfer()
        dt.items.add(new File([blob], 'image.png', { type: 'image/png' }))
        ta.focus()
        ta.setSelectionRange(ta.value.length, ta.value.length)
        ta.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }))
      })
      await expect(area).toHaveValue(/!\[gorsel-\d{8}-\d{6}\.png yükleniyor…\]\(sm-upload:[a-z0-9]+\)/)
      await expect(dlg.locator('[data-slot="md-uploading"]')).toBeVisible()
      await expect.poll(() => uploads).toBe(1)
      // yükleme sürerken yazılan metin kaybolmaz
      await area.evaluate((ta) => { ta.focus(); ta.setSelectionRange(ta.value.length, ta.value.length) })
      await page.keyboard.type(' Ek not.')
      // yükleme sürerken başka bölüme geçilir: bölümler takılı kalır (gizli), editör sökülmez, yükleme biter
      await dlg.locator('[data-section="summary"]').click()
      await expect(dlg.locator('[data-section="summary"]')).toHaveAttribute('aria-selected', 'true')
      await expect(area).toBeHidden()
      release()
      await expect(area).toHaveValue(/\/api\/incidents\/images\/77\)/)
      await dlg.locator('[data-section="rca"]').click()
      await expect(area).toBeVisible()
      await expect(area).toHaveValue(/!\[gorsel-\d{8}-\d{6}\.png\]\(\/api\/incidents\/images\/77\)/)
      await expect(area).not.toHaveValue(/sm-upload:/)
      await expect(area).toHaveValue(/^Önce yazılan metin\./)
      await expect(area).toHaveValue(/Ek not\.$/)
      await expect(dlg.locator('[data-slot="md-uploading"]')).toHaveCount(0)
      // araç çubuğu (üçüncü taraf düzenleyici) dokunmatik ölçüme girmez; taşma / kırpma denetlenir
      await checkLayout(page, dlg.locator('[data-field="description"]'), 'markdown alanı', { targets: false })
    })
  })
}
