// Yönetim → Erişim paylaşımı (2026-10-12, kullanıcı: "bir ekip kendi izlemelerini başka bir takıma ya da kişiye açabilsin …
// shadcn, mweb responsive, profesyonel"). Üç boyutta (390 telefon + dokunmatik, 768 tablet + dokunmatik, 1280 dizüstü)
// gerçek tarayıcıda ölçülür: sayfa yatay taşmaz; dar kapta kartlar, geniş kapta tablo; uzun takım / kişi adları KESİLMEZ;
// dokunma hedefleri ≥ 40 px; KPI süzgeci listeyi daraltır; İşlemler menüsü ve yeni paylaşım penceresi ekrana sığar; yeni
// paylaşım doğru gövdeyle gider. API tamamen mock'lu (yalnız /api/ yolu). Adlar uydurmadır (gerçek uzunlukta AD adları).
import { test, expect } from '@playwright/test'

const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'
const LONG_PERSON = 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin (Teknoloji Servis Yönetimi Bölüm Başkanı)'
const OWNER = 'Ödeme Sistemleri ve Kart Operasyonları Uygulama Geliştirme Ekibi'

const GRANTS = [
  { id: 1, owner_team_id: 10, owner_team_name: OWNER, grantee_type: 'TEAM', grantee_team_id: 20, grantee_name: LONG_TEAM,
    level: 'VIEW', expires_on: null, expires_at: null, expired: false, note: 'Gece vardiyası boyunca alarm ve sertifikaları izlemeleri için',
    created_by: 'yonetici', created_at: '2026-10-12T08:00:00', can_edit: true },
  { id: 2, owner_team_id: 10, owner_team_name: OWNER, grantee_type: 'USER', grantee_user_id: 'u-1', grantee_name: LONG_PERSON,
    grantee_active: true, level: 'DELETE', expires_on: '2099-01-01', expires_at: '2099-01-01T21:00:00', expired: false,
    created_by: 'yonetici', created_at: '2026-10-11T08:00:00', can_edit: true },
  { id: 3, owner_team_id: 30, owner_team_name: 'Kart', grantee_type: 'USER', grantee_user_id: 'u-2', grantee_name: 'Can Demir',
    grantee_active: false, level: 'EDIT', created_by: 'baska', created_at: '2026-10-01T08:00:00', can_edit: false },
]

async function mock(page, posted) {
  await page.addInitScript(() => {
    try { localStorage.setItem('sm.tour', JSON.stringify({ status: 'dismissed', version: 99 })) } catch { /* yoksay */ }
  })
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const req = route.request()
    const p = new URL(req.url()).pathname
    let body = { success: true, data: [] }
    if (p === '/api/me') {
      body = { success: true, username: 'demo', system_role: 'ADMIN', global_admin: true, team_id: 10, team_name: OWNER,
        team_ids: [10], team_names: [OWNER], tour: { status: 'dismissed', version: 99 } }
    } else if (p === '/api/admin/team-access' && req.method() === 'POST') {
      posted.push(JSON.parse(req.postData() || '{}'))
      body = { success: true, data: { id: 9 } }
    } else if (p === '/api/admin/team-access') {
      body = { success: true, data: { grants: GRANTS, manageable_teams: [{ id: 10, name: OWNER }], max_per_team: 200 } }
    } else if (p === '/api/me/shared-with-me') {
      body = { success: true, data: { grants: [{ owner_team_id: 40, owner_team_name: 'Hazine ve Fon Yönetimi Platform Ekibi', via: 'TEAM',
        via_team_name: OWNER, level: 'EXECUTE', expires_on: '2026-12-31' }] } }
    } else if (p === '/api/teams/directory') {
      body = { success: true, data: [{ id: 10, name: OWNER, active: true }, { id: 20, name: LONG_TEAM, active: true },
        { id: 50, name: 'Arşiv Takımı', active: false }] }
    } else if (p === '/api/users/directory') {
      body = { success: true, data: [{ id: 'u-1', username: 'KULLANICI_A', display_name: LONG_PERSON, active: true }] }
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

async function pageOverflow(page) {
  return page.evaluate(() => {
    const main = document.querySelector('.app-main') || document.body
    return Math.max(document.documentElement.scrollWidth - document.documentElement.clientWidth, main.scrollWidth - main.clientWidth)
  })
}

/** 40 px'ten kısa görünür denetimler (radyo düğmesi için saran etiketli kart ölçülür). */
function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button, a[href], select, input:not([type="hidden"]), textarea')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0) continue
    const cs = getComputedStyle(b)
    if (cs.visibility === 'hidden' || cs.display === 'none') continue
    let h = (b.closest('[data-slot="input-group"]')?.getBoundingClientRect() ?? r).height
    const role = b.getAttribute('role')
    if (role === 'radio' || role === 'checkbox') h = Math.max(h, b.closest('label')?.getBoundingClientRect().height ?? 0)
    if (h < 39.5) out.push(`${b.tagName.toLowerCase()}[${b.getAttribute('data-slot') || role || ''}] "${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)}" ${Math.round(h)}`)
  }
  return out
}

/** Kesilen / kabından taşan ad metinleri (takım rozeti, alıcı adı, not, seçici içeriği). */
function clippedText(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const rootR = root.getBoundingClientRect()
  const out = []
  for (const el of root.querySelectorAll('[data-slot="team-badge"] span, [data-slot="access-grantee"] span, [data-slot="access-row"] p, [data-slot="access-fixed"] span, [role="combobox"] span, [data-slot="access-shared-with-me"] li span, [data-slot="access-level"], [data-slot="access-state"]')) {
    const r = el.getBoundingClientRect()
    if (r.width === 0) continue
    const cs = getComputedStyle(el)
    // Hap köşeleri çok satırlı metnin uçlarını kırpar: takım rozeti ve düzey / durum rozetleri
    const box = el.closest('[data-slot="team-badge"], [data-slot="access-level"], [data-slot="access-state"]')
    const pillClip = box && getComputedStyle(box).overflow !== 'visible' && box.getBoundingClientRect().height > 30
      && parseFloat(getComputedStyle(box).borderTopLeftRadius) > 8
    if (pillClip || cs.textOverflow === 'ellipsis' || el.scrollWidth > el.clientWidth + 1 || r.right > rootR.right + 1) {
      out.push(`${el.getAttribute('data-slot') || el.tagName.toLowerCase()} "${el.textContent.trim().slice(0, 30)}" ${el.scrollWidth}>${el.clientWidth}`)
    }
  }
  return out
}

async function inViewport(locator, vw) {
  const box = await locator.boundingBox()
  expect(box.x).toBeGreaterThanOrEqual(-1)
  expect(box.x + box.width).toBeLessThanOrEqual(vw + 1)
}

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

for (const vp of VIEWPORTS) {
  test.describe(`Erişim paylaşımı — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('liste: taşma yok, ad kesilmez, dokunma hedefleri, KPI süzgeci, İşlemler menüsü', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page, [])
      await page.goto('/?tab=admin&g_tab=sharing')
      const root = page.locator('[data-slot="team-access"]')
      const rows = root.locator('[data-slot="access-row"]')
      await expect(rows).toHaveCount(3, { timeout: 20_000 })
      expect(await page.evaluate(() => matchMedia('(pointer: coarse)').matches)).toBe(vp.touch)

      const list = root.locator('[data-slot="access-list"]')
      await expect(list).toHaveAttribute('data-layout', vp.width < 1000 ? 'cards' : 'table')
      await expect(root.locator('[data-slot="access-shared-with-me"]')).toContainText('Hazine ve Fon Yönetimi Platform Ekibi')
      await page.waitForTimeout(300)
      expect(await pageOverflow(page), 'sayfa yatay taşması').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clippedText, '[data-slot="team-access"]'), 'kesilen ad').toEqual([])
      if (vp.touch) {
        const bad = await page.evaluate(smallTargets, '[data-slot="team-access"]')
        expect(bad, `40 px altı hedefler: ${bad.join(', ')}`).toEqual([])
      }

      // KPI: "Kişilere" → yalnız kişi paylaşımları; "Toplam" süzgeci temizler
      await root.locator('[data-slot="stat-item"][data-key="USER"]').click()
      await expect(rows).toHaveCount(2)
      await expect(page).toHaveURL(/g_atype=USER/)
      await root.locator('[data-slot="stat-item"][data-key="total"]').click()
      await expect(rows).toHaveCount(3)

      // Salt okunur satırda menü yok; düzenlenebilir satırda görünür etiketli İşlemler menüsü ekrana sığar
      await expect(rows.filter({ hasText: 'Can Demir' }).locator('button[data-kebab="labeled"]')).toHaveCount(0)
      const trigger = rows.filter({ hasText: LONG_PERSON }).locator('button[data-kebab="labeled"]')
      await expect(trigger).toContainText(/İşlemler|Actions/)
      if (vp.touch) expect((await trigger.boundingBox()).height).toBeGreaterThanOrEqual(39.5)
      await trigger.click()
      const menu = page.getByRole('menu')
      await expect(menu).toBeVisible()
      await expect(menu.getByRole('menuitem')).toHaveCount(2)
      await inViewport(menu, vp.width)
      await page.keyboard.press('Escape')
      await page.screenshot({ path: `test-results/team-access-${vp.name}.png`, fullPage: true })
    })

    test('yeni paylaşım penceresi ekrana sığar; takım alıcı + düzey → doğru gövde', async ({ page }) => {
      test.setTimeout(120_000)
      const posted = []
      await mock(page, posted)
      await page.goto('/?tab=admin&g_tab=sharing')
      const newBtn = page.locator('[data-slot="access-new"]')
      await expect(newBtn).toBeVisible({ timeout: 20_000 })
      await newBtn.click()
      const form = page.locator('[data-slot="access-form"]')
      await expect(form).toBeVisible()
      const dialog = page.getByRole('dialog')
      await inViewport(dialog, vp.width)

      const teamPicker = form.getByRole('combobox', { name: /^(Team|Takım)/ })
      await expect(teamPicker).toBeEnabled()
      await teamPicker.click()
      const opt = page.getByRole('option', { name: LONG_TEAM })
      await expect(opt).toBeVisible()
      await expect(page.getByRole('option', { name: 'Arşiv Takımı' })).toHaveCount(0)
      await opt.click()
      await expect(teamPicker).toContainText(LONG_TEAM)
      await form.locator('[data-level="EXECUTE"]').click()
      await page.waitForTimeout(300)
      expect(await page.evaluate(clippedText, '[data-slot="access-form"]'), 'pencerede kesilen ad').toEqual([])
      if (vp.touch) {
        const bad = await page.evaluate(smallTargets, '[role="dialog"]')
        expect(bad, `pencerede 40 px altı hedefler: ${bad.join(', ')}`).toEqual([])
      }
      const scroll = await dialog.evaluate((el) => el.scrollWidth - el.clientWidth)
      expect(scroll, 'pencere yatay taşması').toBeLessThanOrEqual(1)
      await page.screenshot({ path: `test-results/team-access-dialog-${vp.name}.png` })

      await page.locator('[data-slot="access-submit"]').click()
      await expect(form).toHaveCount(0)
      expect(posted).toEqual([{ owner_team_id: 10, grantee_type: 'TEAM', level: 'EXECUTE', grantee_team_id: 20 }])
    })
  })
}
