import { test, expect } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { unzipSync, strFromU8 } from 'fflate'
import { mockApi } from './support/monitorMocks.js'

// 2026-10-17 (kullanıcı: "Ayarlar içindeki Teknik hata raporu sayfasını yeniden shadcn ile tasarla, mweb responsive, mevcut
// fonksiyonları koru"): Ayarlar → Platform → Teknik hata raporu telefon (390, dokunmatik), tablet (768, dokunmatik) ve
// dizüstünde (1280): görünürlük seçici + KPI kartları + araç çubuğu + kanal özeti sığar, yatay taşma yok, metin kırpılmaz,
// 60+ karakterlik kanal ve takım adları sarar, dokunmatikte hedefler ≥ 40 px; geniş kapta TABLO, telefonda KART; KPI kartı
// süzgeçtir; arama Türkçe katlar. Kapsamlı müdür salt okunur görür; okuma hatasında tehlike bloğu + Tekrar dene.
// Yerleşim KABA göre (TES_TABLE_MIN_PX 540): 768 px tablette uygulama kenar çubuğu sabit sütun olduğu için liste kabı ≈ 350 px →
// kart; 1280 dizüstünde ≈ 575 px → tablo; telefonda kart.
const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true, layout: 'cards' },
  { name: 'tablet', width: 768, height: 1024, touch: true, layout: 'cards' },
  { name: 'laptop', width: 1280, height: 800, touch: false, layout: 'table' },
]

const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'
const LONG_CHANNEL = 'Bireysel İnternet Şubesi ve Mobil Bankacılık Ortak Kanalı (Dijital Kanallar / Bireysel Bankacılık)'

const CHANNELS = [
  { id: 10, name: LONG_CHANNEL, active: true, team_id: 1, team_name: LONG_TEAM, team_visible: true, executive_count: 2, approver_count: 1,
    team_email_set: true, period_report: { id: 100, status: 'draft', last_send_status: null, approval: { state: 'pending' } } },
  { id: 20, name: 'POS', active: false, team_id: 2, team_name: 'Ödeme Sistemleri', team_visible: false, executive_count: 0, approver_count: 0,
    team_email_set: false, period_report: null },
  { id: 30, name: 'İnternet Şubesi', active: true, team_id: 2, team_name: 'Ödeme Sistemleri', team_visible: false, executive_count: 0,
    approver_count: 0, team_email_set: false, period_report: null },
  { id: 40, name: 'ATM', active: true, team_id: 1, team_name: LONG_TEAM, team_visible: true, executive_count: 1, approver_count: 2,
    team_email_set: true, period_report: { id: 101, status: 'sent', last_send_status: 'sent', approval: { state: 'approved' } } },
]
const TEAMS = [{ id: 1, name: LONG_TEAM, active: true }, { id: 2, name: 'Ödeme Sistemleri', active: true }]

const json = (body, status = 200) => (route) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

function settingsBody(extra = {}) {
  return { success: true, data: { team_ids: [], read_only: false, period: { year: 2026, month: 9 }, channels: CHANNELS, ...extra } }
}

/**
 * 12. sürüm (2026-10-17): hatırlatma + posta sınırı bloğu — AYRI uç `/settings/reminders` (inceleme 2026-10-18). Verilmezse
 * uç 404 döner (eski pod) ve kart "henüz desteklemiyor" notunu gösterir.
 */
const REMINDERS = {
  team_ids: [1], reminder_day: 5, reminder_repeat_days: 3, approval_reminder_days: 3, max_mail_mb: 15, mail_inline_mb: 6,
  reminder_push: true,
  limits: {
    reminder_day: { min: 1, max: 28, default: 5 }, reminder_repeat_days: { min: 0, max: 14, default: 3 },
    approval_reminder_days: { min: 1, max: 14, default: 3 }, max_mail_mb: { min: 5, max: 50, default: 15 },
    mail_inline_mb: { min: 0, max: 20, default: 6 },
  },
}

/** mockApi'den SONRA kaydedilen yollar önceliklidir. Arayüz dili TR (varsayılan EN — metin iddiaları Türkçe). */
async function routes(page, { readOnly = false, fail = () => false, reminders = null } = {}) {
  await page.addInitScript(() => { try { localStorage.setItem('site-monitor-lang', 'tr') } catch { /* yok */ } })
  await mockApi(page, { role: 'ADMIN', globalAdmin: !readOnly })
  await page.route((u) => new URL(u).pathname === '/api/teams/directory', json({ success: true, data: TEAMS }))
  // `saves`: görünürlük PUT'ları (`/settings`, 11. sürüm sözleşmesi); `saves.reminders`: hatırlatma PUT'ları (ayrı uç)
  const saves = []
  saves.reminders = []
  await page.route((u) => new URL(u).pathname === '/api/admin/tech-error-reports/settings', (route) => {
    if (route.request().method() === 'PUT') {
      const body = JSON.parse(route.request().postData() || '{}')
      saves.push(body)
      return json({ success: true, data: { team_ids: body.team_ids || [] } })(route)
    }
    if (fail()) return json({ success: false, code: 'DB_UNAVAILABLE', error: 'Veritabanına şu an ulaşılamıyor. Biraz bekleyip yeniden deneyin.' }, 503)(route)
    return json(settingsBody({ read_only: readOnly }))(route)
  })
  let current = reminders ? { ...reminders } : null
  await page.route((u) => new URL(u).pathname === '/api/admin/tech-error-reports/settings/reminders', (route) => {
    if (!current) {
      const method = route.request().method()
      return json({ success: false, code: method === 'GET' ? 'NOT_FOUND' : 'METHOD_NOT_ALLOWED', error: 'Kaynak bulunamadı' },
        method === 'GET' ? 404 : 405)(route)
    }
    if (route.request().method() === 'PUT') {
      const body = JSON.parse(route.request().postData() || '{}')
      saves.reminders.push(body)
      current = { ...current, ...pickReminders(body) }
      return json({ success: true, data: { ...current, read_only: false } })(route)
    }
    return json({ success: true, data: { ...current, read_only: readOnly } })(route)
  })
  return saves
}

/** PUT gövdesindeki hatırlatma alanları → yanıt bloğu (sunucu gibi: gönderilmeyen alan aynı kalır). */
function pickReminders(body) {
  const out = {}
  for (const k of ['reminder_day', 'reminder_repeat_days', 'approval_reminder_days', 'max_mail_mb', 'mail_inline_mb', 'reminder_push']) {
    if (k in body) out[k] = body[k]
  }
  if ('reminder_team_ids' in body) out.team_ids = body.reminder_team_ids
  return out
}

function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button, select, input:not([type="hidden"]), textarea')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0 || b.disabled || b.getAttribute('role') === 'checkbox') continue
    const cs = getComputedStyle(b)
    if (cs.visibility === 'hidden' || cs.display === 'none' || b.getAttribute('aria-hidden') === 'true') continue
    let h = r.height
    // Takım rozeti gibi küçük görünen hedefler dokunmatikte ::after ile dikeyde büyür (TEAM_BADGE_TOUCH, ~42 px) — o alan sayılır
    const af = getComputedStyle(b, '::after')
    if (af.position === 'absolute' && af.content !== 'none') {
      const top = parseFloat(af.top) || 0, bottom = parseFloat(af.bottom) || 0
      h = Math.max(h, r.height - Math.min(0, top) - Math.min(0, bottom))
    }
    if (h < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || b.id || '').trim().slice(0, 30)}" ${Math.round(h)}`)
  }
  return out
}

function clipped(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  return [...root.querySelectorAll('span, p, li, h3, h4, label, button, dt, dd, th')].filter((n) => !n.closest('[role="combobox"]'))
    .filter((n) => !n.closest('.sr-only'))
    .filter((n) => n.childElementCount === 0 && n.textContent.trim().length > 12
      && (n.scrollWidth > n.clientWidth + 1 || getComputedStyle(n).textOverflow === 'ellipsis')).map((n) => n.textContent.slice(0, 30))
}

/** Kök dışına taşan görünür öğeler (settings-database.spec `measure` ile aynı kural). */
function offenders(sel) {
  const vw = document.documentElement.clientWidth
  const root = document.querySelector(sel) || document.body
  const out = []
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    const cs = getComputedStyle(el)
    if (r.width <= 1 || r.height <= 1 || cs.visibility === 'hidden' || cs.display === 'none') continue
    if (r.right <= vw + 1 && r.left >= -1) continue
    let contained = false
    for (let p = el.parentElement; p && p !== root; p = p.parentElement) {
      if (getComputedStyle(p).overflowX !== 'visible' && p.getBoundingClientRect().right <= vw + 1) { contained = true; break }
    }
    if (!contained) out.push(`${el.tagName.toLowerCase()}[${el.getAttribute('data-slot') || ''}] ${Math.round(r.left)}..${Math.round(r.right)}`)
    if (out.length >= 8) break
  }
  return out
}

const overflowX = () => document.documentElement.scrollWidth - document.documentElement.clientWidth
const ROOT = '[data-slot="tes-root"]'

for (const vp of VIEWPORTS) {
  test.describe(`Ayarlar → Teknik hata raporu — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('sığar, kırpılmaz, uzun adlar sarar; geniş kapta tablo / telefonda kart; KPI süzgeci, arama, kanalı aç', async ({ page }) => {
      test.setTimeout(120_000)
      await routes(page)
      await page.goto('/?tab=settings&sec=techerror')
      const root = page.locator(ROOT)
      const rows = root.locator('[data-slot="tes-channel"]')
      await expect(rows).toHaveCount(4, { timeout: 20_000 })
      await expect(root.locator('[data-slot="tes-list"]')).toHaveAttribute('data-layout', vp.layout)
      await expect(root.locator('[data-slot="stat-item"]')).toHaveCount(7)
      await expect(root.locator('[data-slot="tes-count"]')).toHaveText(/4 .*4/)
      await page.waitForTimeout(400)

      // Yerleşim: sayfa yatay kaymaz, kök dışına taşan öğe yok, metin kırpılmaz
      expect(await page.evaluate(overflowX), `@${vp.name}: yatay taşma`).toBeLessThanOrEqual(1)
      expect(await page.evaluate(offenders, ROOT), `@${vp.name}: ekran dışına taşan öğe`).toEqual([])
      expect(await page.evaluate(clipped, ROOT), `@${vp.name}: kırpılan metin`).toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, ROOT), `@${vp.name}: küçük dokunma hedefi`).toEqual([])

      // Varsayılan sıra: aktifler önce, ad A→Z (ATM, Bireysel…, İnternet Şubesi, POS) — satırlar kanal kimliğiyle bulunur
      await expect(rows.first()).toContainText('ATM')
      await expect(rows.nth(3)).toContainText('POS')
      // Uzun kanal adı ve uzun takım adı tam görünür ve sarar (tek satıra sığmaz → yüksekliği bir satırdan fazla)
      const first = rows.nth(1)
      await expect(first).toHaveAttribute('data-channel', '10')
      await expect(first).toContainText(LONG_CHANNEL)
      await expect(first).toContainText(LONG_TEAM)
      const nameEl = first.locator('[data-slot="tes-channel-name"]')
      const nameBox = await nameEl.boundingBox()
      const rowBox = await first.boundingBox()
      expect(nameBox.height, `@${vp.name}: uzun kanal adı sarar`).toBeGreaterThan(30)
      expect(nameBox.x + nameBox.width, `@${vp.name}: kanal adı satırın sağından taşmaz`).toBeLessThanOrEqual(rowBox.x + rowBox.width + 1)
      const teamEl = first.locator('[data-slot="team-badge"]')
      const teamBox = await teamEl.boundingBox()
      expect(teamBox.height, `@${vp.name}: uzun takım adı sarar`).toBeGreaterThan(30)
      expect(await teamEl.evaluate((el) => el.scrollWidth - el.clientWidth), `@${vp.name}: takım rozeti içinde yatay taşma`).toBeLessThanOrEqual(1)
      expect(await nameEl.evaluate((el) => el.scrollWidth - el.clientWidth), `@${vp.name}: kanal adı içinde yatay taşma`).toBeLessThanOrEqual(1)

      // Dönem / onay / takım durumu rozetleri
      await expect(first.locator('[data-slot="tes-exec"]')).toHaveText('Yönetici alıcı: 2')
      await expect(first.locator('[data-slot="tes-approval"]')).toHaveText('Onay bekliyor')
      const pos = root.locator('[data-slot="tes-channel"][data-channel="20"]')
      await expect(pos.locator('[data-slot="tes-visibility"]')).toHaveAttribute('data-state', 'hidden')
      await expect(pos.locator('[data-slot="tes-email"]')).toHaveAttribute('data-state', 'missing')
      await expect(pos).toHaveAttribute('data-status', 'inactive')

      await page.screenshot({ path: `test-results/settings-techerror-${vp.name}.png`, fullPage: false })

      // KPI kartı süzgeç: "Raporu başlanmamış" → yalnız İnternet Şubesi; ikinci tık kaldırır
      const missing = root.locator('[data-slot="stat-item"][data-key="missing"]')
      await missing.scrollIntoViewIfNeeded()
      await missing.click()
      await expect(missing).toHaveAttribute('aria-pressed', 'true')
      await expect(rows).toHaveCount(1)
      await expect(rows.first()).toContainText('İnternet Şubesi')
      await expect(root.locator('[data-slot="tes-count"]')).toHaveText(/1 .*4/)
      await missing.click()
      await expect(rows).toHaveCount(4)

      // Arama Türkçe katlar: "internet" → İnternet Şubesi (+ uzun kanal "İnternet Şubesi" de içerir → 2)
      const search = root.locator('[data-slot="tes-search"]')
      await search.scrollIntoViewIfNeeded()
      await search.fill('internet')
      await expect(rows).toHaveCount(2)
      await search.fill('odeme')
      await expect(rows).toHaveCount(2)
      await expect(rows.first()).toContainText('İnternet Şubesi')
      await root.locator('[data-slot="tes-clear"]').click()
      await expect(rows).toHaveCount(4)
      await page.waitForTimeout(200)
      expect(await page.evaluate(overflowX), `@${vp.name}: yatay taşma (süzgeç sonrası)`).toBeLessThanOrEqual(1)

      // "Kanalı aç" (kanal adı) kanal raporları sekmesini o kanalla açar
      await first.locator('[data-slot="tes-open"]').click()
      await expect.poll(() => new URL(page.url()).searchParams.get('tab')).toBe('channelreports')
      await expect.poll(() => new URL(page.url()).searchParams.get('te_ch')).toBe('10')
      await expect.poll(() => new URL(page.url()).searchParams.get('te_view')).toBe('channel')
    })

    test('dışa aktar (2026-10-17, E22): menü sığar, dokunma hedefleri; CSV ve Excel süzülmüş listeyi dönem adlı dosyaya yazar', async ({ page }) => {
      test.setTimeout(120_000)
      await routes(page)
      await page.goto('/?tab=settings&sec=techerror')
      const root = page.locator(ROOT)
      await expect(root.locator('[data-slot="tes-channel"]')).toHaveCount(4, { timeout: 20_000 })
      const trigger = root.locator('[data-slot="tes-export"]')
      await trigger.scrollIntoViewIfNeeded()
      await expect(trigger).toBeVisible()
      await expect(trigger).toBeEnabled()
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="tes-toolbar"]'), `@${vp.name}: küçük hedef (araç çubuğu)`).toEqual([])
      expect(await page.evaluate(overflowX), `@${vp.name}: yatay taşma (dışa aktar)`).toBeLessThanOrEqual(1)
      expect(await page.evaluate(offenders, '[data-slot="tes-toolbar"]'), `@${vp.name}: araç çubuğu dışına taşan`).toEqual([])

      await trigger.click()
      const menu = page.locator('[data-slot="tes-export-menu"]')
      await expect(menu).toBeVisible()
      // Radix menü açılırken zoom-in-95 animasyonu oynar: ölçüm animasyon bitince (ölçek 1) alınır
      await menu.evaluate((el) => Promise.all(el.getAnimations({ subtree: true }).map((a) => a.finished.catch(() => null))))
      const mb = await menu.boundingBox()
      expect(mb.x, `@${vp.name}: menü solda sığar`).toBeGreaterThanOrEqual(-1)
      expect(mb.x + mb.width, `@${vp.name}: menü sağda sığar`).toBeLessThanOrEqual(vp.width + 1)
      if (vp.touch) {
        for (const h of await menu.locator('[role="menuitem"]').evaluateAll((els) => els.map((e) => e.getBoundingClientRect().height))) {
          expect(h, `@${vp.name}: menü öğesi dokunma hedefi`).toBeGreaterThanOrEqual(39.5)
        }
      }
      // CSV: tüm liste (4 kanal + başlık), Türkçe metinler, BOM
      const [csv] = await Promise.all([page.waitForEvent('download'), menu.locator('[data-slot="tes-export-csv"]').click()])
      expect(csv.suggestedFilename()).toBe('teknik-hata-raporu-kanallar-2026-09.csv')
      const text = readFileSync(await csv.path(), 'utf8')
      expect(text.charCodeAt(0), 'BOM').toBe(0xfeff)
      const lines = text.slice(1).trim().split('\r\n')
      expect(lines).toHaveLength(5)
      expect(lines[0]).toBe('Kanal,Takım,Aktif,Modül takıma açık,Takım e-postası,Yönetici alıcı sayısı,PO onaylayıcı sayısı,Dönem raporu durumu,Onay durumu')
      expect(lines.some((l) => l.includes(LONG_CHANNEL))).toBe(true)

      // Süzgeçten sonra Excel: yalnız süzülenler (arama "odeme" → 2 kanal)
      await root.locator('[data-slot="tes-search"]').fill('odeme')
      await expect(root.locator('[data-slot="tes-channel"]')).toHaveCount(2)
      await trigger.click()
      const [xl] = await Promise.all([page.waitForEvent('download'), page.locator('[data-slot="tes-export-xlsx"]').click()])
      expect(xl.suggestedFilename()).toBe('teknik-hata-raporu-kanallar-2026-09.xlsx')
      const files = unzipSync(new Uint8Array(readFileSync(await xl.path())))
      const sheet = strFromU8(files['xl/worksheets/sheet1.xml'])
      expect(sheet).toContain('İnternet Şubesi')
      expect(sheet).toContain('POS')
      expect(sheet).not.toContain('ATM')
    })
  })
}

test.describe('Ayarlar → Teknik hata raporu — tablet 768 (dokunmatik)', () => {
  test.use({ viewport: { width: 768, height: 1024 }, hasTouch: true, isMobile: true })

  test('kapsamlı müdür salt okunur — seçici kilitli, kayıt çubuğu yok, kanal özeti görünür', async ({ page }) => {
  test.setTimeout(120_000)
  const saves = await routes(page, { readOnly: true })
  await page.goto('/?tab=settings&sec=techerror')
  const root = page.locator(ROOT)
  await expect(root.locator('[data-slot="tes-channel"]')).toHaveCount(4, { timeout: 20_000 })
  await expect(root.locator('[data-slot="tes-readonly"]')).toContainText('yalnız global yöneticiler')
  // Hatırlatma ucu yok (eski pod, 404): kart "henüz desteklemiyor" der, alan / kayıt çubuğu yok (inceleme 2026-10-18)
  await expect(root.locator('[data-slot="tes-rem-unsupported"]')).toContainText('hiçbir değişiklik kaydedilmedi')
  await expect(root.getByRole('combobox', { name: 'Modülü gören takımlar' })).toBeDisabled()
  await expect(root.locator('[data-slot="settings-save-bar"]')).toHaveCount(0)
  expect([...saves]).toEqual([])
  expect(await page.evaluate(overflowX)).toBeLessThanOrEqual(1)
  expect(await page.evaluate(clipped, ROOT)).toEqual([])
  expect(await page.evaluate(smallTargets, ROOT), 'küçük dokunma hedefi (salt okunur)').toEqual([])
  })
})

test.describe('Ayarlar → Teknik hata raporu — telefon 390 (dokunmatik)', () => {
  test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true })

  test('görünürlük kaydı — takım seç, Kaydet → PUT team_ids; okuma hatası + Tekrar dene', async ({ page }) => {
  test.setTimeout(120_000)
  let failing = true
  const saves = await routes(page, { fail: () => failing })
  await page.goto('/?tab=settings&sec=techerror')
  const root = page.locator(ROOT)
  const block = root.locator('[data-slot="empty"][data-tone="danger"]')
  await expect(block).toBeVisible({ timeout: 20_000 })
  await expect(block).toContainText('Veritabanına şu an ulaşılamıyor')
  await expect(root.locator('[data-slot="tes-channel"]')).toHaveCount(0)   // hata "kanal yok" DEĞİL
  await expect(root.locator('[data-slot="tes-empty"]')).toHaveCount(0)
  const retry = block.locator('[data-slot="tes-retry"]')
  const rb = await retry.boundingBox()
  expect(rb.height).toBeGreaterThanOrEqual(40)
  expect(await page.evaluate(overflowX)).toBeLessThanOrEqual(1)
  failing = false
  await retry.click()
  await expect(root.locator('[data-slot="tes-channel"]')).toHaveCount(4, { timeout: 20_000 })
  await expect(block).toHaveCount(0)

  // Görünürlük: takım seç → kayıt çubuğu kirli → Kaydet → PUT { team_ids: [2] }; "herkes görür" notu kalkar
  await expect(root.locator('[data-slot="tes-everyone"]')).toBeVisible()
  const picker = root.getByRole('combobox', { name: 'Modülü gören takımlar' })
  const pb = await picker.boundingBox()
  expect(pb.height, 'seçici dokunma hedefi').toBeGreaterThanOrEqual(40)
  await picker.dispatchEvent('mousedown')
  await page.getByRole('option', { name: /Ödeme Sistemleri/ }).dispatchEvent('mousedown')
  await page.keyboard.press('Escape')
  await expect(root.locator('[data-slot="tes-everyone"]')).toHaveCount(0)
  const bar = root.locator('[data-slot="settings-save-bar"]')
  await expect(bar).toHaveAttribute('data-dirty', 'true')
  await bar.getByRole('button', { name: /Kaydet/ }).click()
  await expect.poll(() => saves.length).toBe(1)
  expect(saves[0]).toEqual({ team_ids: [2] })
  await expect(bar).not.toHaveAttribute('data-dirty', 'true')
  await page.waitForTimeout(300)
  expect(await page.evaluate(overflowX)).toBeLessThanOrEqual(1)
  expect(await page.evaluate(clipped, ROOT)).toEqual([])
  expect(await page.evaluate(smallTargets, ROOT), 'küçük dokunma hedefi (kayıt sonrası)').toEqual([])
  await page.screenshot({ path: 'test-results/settings-techerror-phone-saved.png', fullPage: false })
  })
})

// 12. sürüm (2026-10-17, C9 / C10 / E21): "Hatırlatmalar ve posta sınırları" kartı telefon (390, dokunmatik), tablet (768,
// dokunmatik) ve dizüstünde (1280): sığar, kırpılmaz, dokunmatikte hedefler ≥ 40 px; yerleşim KABA göre (container query):
// dizüstünde gün + tekrar yan yana, telefonda / tablette (Ayarlar içerik sütunu ≈ 350 px) alt alta; aralık dışı değer alanın
// altında hata verir ve istek gitmez; düzeltip Kaydet YALNIZ değişen alanı gönderir.
const REM_ROOT = '[data-slot="tes-reminders"]'
for (const vp of VIEWPORTS) {
  test.describe(`Ayarlar → Teknik hata raporu — hatırlatmalar ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('kart sığar ve kırpılmaz; alan hatası alanın altında; Kaydet yalnız değişen alanı gönderir', async ({ page }) => {
      test.setTimeout(120_000)
      const saves = await routes(page, { reminders: REMINDERS })
      await page.goto('/?tab=settings&sec=techerror')
      const card = page.locator(REM_ROOT)
      await expect(card).toBeVisible({ timeout: 20_000 })
      const day = card.getByLabel('Hatırlatma günü (ayın kaçıncı günü)')
      await expect(day).toHaveValue('5')
      await expect(card.getByLabel('E-posta boyut sınırı (MB)')).toHaveValue('15')
      await expect(card.getByRole('checkbox', { name: 'Takım üyelerine push bildirimi de gönder' })).toBeChecked()
      await card.scrollIntoViewIfNeeded()
      await page.waitForTimeout(300)

      expect(await page.evaluate(overflowX), `@${vp.name}: yatay taşma`).toBeLessThanOrEqual(1)
      expect(await page.evaluate(offenders, REM_ROOT), `@${vp.name}: ekran dışına taşan öğe`).toEqual([])
      expect(await page.evaluate(clipped, REM_ROOT), `@${vp.name}: kırpılan metin`).toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, REM_ROOT), `@${vp.name}: küçük dokunma hedefi`).toEqual([])
      const picker = card.getByRole('combobox', { name: 'Hatırlatma alan takımlar' })
      if (vp.touch) expect((await picker.boundingBox()).height, `@${vp.name}: seçici dokunma hedefi`).toBeGreaterThanOrEqual(40)

      // Yerleşim: dizüstünde iki sütun (aynı satır), dar kapta tek sütun
      const dayBox = await card.locator('[data-field="reminder_day"]').boundingBox()
      const repBox = await card.locator('[data-field="reminder_repeat_days"]').boundingBox()
      if (vp.name === 'laptop') {
        expect(Math.abs(dayBox.y - repBox.y), '@laptop: gün ve tekrar aynı satırda').toBeLessThan(4)
      } else {
        expect(repBox.y, `@${vp.name}: tek sütun`).toBeGreaterThan(dayBox.y + 20)
      }
      // Alanlar kartın içinde kalır (kapsayıcının sağından taşmaz)
      const cardBox = await card.boundingBox()
      expect(repBox.x + repBox.width, `@${vp.name}: alan kart içinde`).toBeLessThanOrEqual(cardBox.x + cardBox.width + 1)

      // Aralık dışı değer: alanın altında ileti, istek gitmez
      const bar = card.locator('[data-slot="tes-rem-save"] [data-slot="settings-save-bar"]')
      await day.fill('29')
      await expect(bar).toHaveAttribute('data-dirty', 'true')
      await bar.getByRole('button', { name: /Kaydet/ }).click()
      await expect(card.locator('[data-field="reminder_day"]')).toContainText('Bir tam sayı girin: 1 ile 28 arasında olmalı.')
      expect(saves.reminders).toEqual([])
      expect([...saves]).toEqual([])
      await page.waitForTimeout(200)
      expect(await page.evaluate(clipped, REM_ROOT), `@${vp.name}: kırpılan metin (hata iletisi)`).toEqual([])
      expect(await page.evaluate(overflowX), `@${vp.name}: yatay taşma (hata iletisi)`).toBeLessThanOrEqual(1)

      // Düzelt → Kaydet: yalnız değişen alan
      await day.fill('7')
      await expect(card.locator('[data-field="reminder_day"]')).not.toContainText('Bir tam sayı girin')
      await bar.getByRole('button', { name: /Kaydet/ }).click()
      await expect.poll(() => saves.reminders.length).toBe(1)
      expect(saves.reminders[0]).toEqual({ reminder_day: 7 })
      expect([...saves], 'görünürlük PUT\'u gitmez (eski pod onu eksik team_ids ile boş okurdu)').toEqual([])
      await expect(bar).not.toHaveAttribute('data-dirty', 'true')
      await expect(day).toHaveValue('7')
      if (vp.touch) expect(await page.evaluate(smallTargets, REM_ROOT), `@${vp.name}: küçük dokunma hedefi (kayıt sonrası)`).toEqual([])
      await page.screenshot({ path: `test-results/settings-techerror-reminders-${vp.name}.png`, fullPage: false })
    })
  })
}
