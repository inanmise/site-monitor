import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'
import { response, SETTINGS, TEAMS, TEAM_DETAIL } from '../src/test/helpers/executiveFixtures.js'

// Aylık Yönetici Özeti (2026-10-10, kullanıcı: "mweb responsive yapıda olsun", "tasarımlar shadcn ile", "en üst düzey ui",
// "takım bazlı yönetici ayarlaması"): sayfa telefon (390), tablet (768) ve dizüstünde (1280) API mock'lu açılır.
// Rapor görünümü: sayfa düzeyinde yatay taşma YOK, görünür hiçbir öğe görünüm alanının sağına taşmaz (kendi kaydırma
// kabındaki tablo/çip satırı hariç), telefonda/tablette dokunma hedefleri ≥ 40 px, tablolar telefonda kart listesi,
// ≥ 768 tablo, ≥ 1024 yapışkan içindekiler (dar ekranda çip satırı). Alıcılar ve gönderim görünümü: dar ekranda liste →
// ayrıntı (geri düğmesi), geniş ekranda yan yana; takım ayrıntısı taşmaz, dokunma hedefleri ≥ 40 px.
const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

const json = (body) => ({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })

async function mockExecutive(page) {
  await page.route((u) => new URL(u).pathname === '/api/executive-summary', (route) => route.fulfill(json(response())))
  await page.route((u) => new URL(u).pathname === '/api/executive-summary/settings',
    (route) => route.fulfill(json({ success: true, data: SETTINGS })))
  await page.route((u) => new URL(u).pathname === '/api/executive-summary/teams',
    (route) => route.fulfill(json({ success: true, data: TEAMS })))
  await page.route((u) => /^\/api\/executive-summary\/teams\/\d+$/.test(new URL(u).pathname),
    (route) => route.fulfill(json({ success: true, data: TEAM_DETAIL })))
}

/** Görünüm alanının sağına taşan, kendi kaydırma kabında OLMAYAN görünür öğeler. */
function offenders(rootSel) {
  const vw = document.documentElement.clientWidth
  const root = document.querySelector(rootSel) || document.body
  const out = []
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    if (r.width <= 1 || r.height <= 1 || r.right <= vw + 1) continue
    const cs = getComputedStyle(el)
    if (cs.visibility === 'hidden' || cs.display === 'none') continue
    let contained = false
    for (let p = el.parentElement; p && p !== root; p = p.parentElement) {
      if (getComputedStyle(p).overflowX !== 'visible' && p.getBoundingClientRect().right <= vw + 1) { contained = true; break }
    }
    if (!contained) out.push(`${el.tagName.toLowerCase()}[${el.getAttribute('data-slot') || ''}] ${Math.round(r.right)}`)
    if (out.length > 6) break
  }
  return out
}

/** Görünür dokunma hedeflerinden 40 px'ten alçak olanlar (kendi kaydırma kabı dışında kalanlar dahil). */
function smallTargets(rootSel) {
  const out = []
  for (const el of document.querySelectorAll(`${rootSel} :is(button, a[data-slot="ex-jump"], select, [role="combobox"], [role="tab"])`)) {
    const r = el.getBoundingClientRect()
    if (r.width <= 1 || r.height <= 1) continue
    if (getComputedStyle(el).visibility === 'hidden') continue
    if (el.closest('[hidden]')) continue
    // Onay kutusu ve anahtar küçük çizilir; dokunma hedefi bağlı ETİKETTİR (üye satırı ≥ 48 px, ToggleRow `touch` ≥ 40 px)
    if (el.getAttribute('role') === 'checkbox' || el.getAttribute('role') === 'switch') continue
    if (r.height < 39.5) out.push(`${el.tagName.toLowerCase()} "${(el.textContent || el.getAttribute('aria-label') || '').trim().slice(0, 30)}" ${Math.round(r.height)}px`)
  }
  return out
}

async function pageOverflow(page) {
  return page.evaluate(() => {
    const els = [document.documentElement, document.body, document.querySelector('.app-main')].filter(Boolean)
    return Math.max(...els.map((el) => el.scrollWidth - el.clientWidth))
  })
}

for (const vp of VIEWPORTS) {
  test(`yönetici özeti (${vp.name} ${vp.width}): rapor + alıcılar ve gönderim — taşma yok, dokunma hedefleri, tablo/kart`, async ({ browser }) => {
    test.setTimeout(150_000)
    const context = await browser.newContext({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch })
    const page = await context.newPage()
    await mockApi(page)
    await mockExecutive(page)
    await page.goto('/?tab=executive')

    // ── Rapor ──
    const sections = page.locator('[data-slot="ex-section"]')
    await expect(sections).toHaveCount(8, { timeout: 30_000 })
    await expect(page.locator('[data-slot="ex-headline"]')).toBeVisible()
    await expect(page.locator('[data-slot="ex-scopebar"]')).toBeVisible()
    await expect(page.locator('[data-slot="ex-health"]')).toBeVisible()
    await expect(page.locator('[data-slot="ex-trend-chart"], [data-testid="ex-trend-chart"]').first()).toBeVisible()
    for (const key of ['tls-grade', 'crypto-readiness', 'data-quality']) {
      await expect(page.locator(`[data-slot="ex-section"][data-key="${key}"]`)).toBeVisible()
    }
    await expect(page.locator('[data-slot="ex-tls-dist"]')).toBeVisible()
    await expect(page.locator('[data-slot="ex-crypto-dist"]')).toBeVisible()

    // İçindekiler: ≥ 1024 yapışkan kart, dar ekranda yatay kayan çip satırı
    if (vp.width >= 1024) {
      await expect(page.locator('[data-slot="ex-toc"]')).toBeVisible()
      await expect(page.locator('[data-slot="ex-jump-chips"]')).toBeHidden()
    } else {
      await expect(page.locator('[data-slot="ex-jump-chips"]')).toBeVisible()
      await expect(page.locator('[data-slot="ex-toc"]')).toBeHidden()
    }

    expect(await pageOverflow(page), 'yatay taşma (rapor)').toBeLessThanOrEqual(1)
    expect(await page.evaluate(offenders, '[data-slot="ex-page"]'), 'görünüm alanı dışına taşan öğeler (rapor)').toEqual([])

    // Telefon: tablolar kart listesi; ≥ 768 gerçek tablo
    const wide = page.locator('[data-slot="ex-table-wide"]').first()
    const cards = page.locator('[data-slot="ex-table-cards"]').first()
    if (vp.width < 768) {
      await expect(cards).toBeVisible()
      await expect(wide).toBeHidden()
    } else {
      await expect(wide).toBeVisible()
      await expect(cards).toBeHidden()
    }
    if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="ex-page"]'), 'küçük dokunma hedefleri (rapor)').toEqual([])

    await page.locator('[data-slot="ex-headline"]').screenshot({ path: `test-results/executive-headline-${vp.name}.png` })
    await page.locator('[data-slot="ex-section"][data-key="availability"]').screenshot({ path: `test-results/executive-availability-${vp.name}.png` })
    await page.locator('[data-slot="ex-section"][data-key="tls-grade"]').screenshot({ path: `test-results/executive-tls-grade-${vp.name}.png` })
    await page.screenshot({ path: `test-results/executive-${vp.name}.png`, fullPage: false })

    // ── Alıcılar ve gönderim ──
    await page.locator('[role="tab"][data-view="delivery"]').click()
    const delivery = page.locator('[data-slot="ex-delivery"]')
    await expect(delivery).toBeVisible({ timeout: 15_000 })
    const list = page.locator('[data-slot="ex-delivery-list"]')
    const detail = page.locator('[data-slot="ex-delivery-detail"]')
    await expect(list).toBeVisible()
    if (vp.width >= 1024) {
      await expect(detail.locator('[data-slot="ex-settings"][data-scope="org"]')).toBeVisible()   // varsayılan: kurum
    } else {
      await expect(detail).toBeHidden()                                                           // önce liste
    }
    expect(await pageOverflow(page), 'yatay taşma (liste)').toBeLessThanOrEqual(1)
    await page.locator('[data-slot="ex-delivery-item"][data-key="5"]').click()
    const team = detail.locator('[data-slot="ex-settings"][data-scope="team"]')
    await expect(team).toBeVisible({ timeout: 15_000 })
    await expect(team.locator('[data-slot="ex-team-member"]')).toHaveCount(3)
    // Yöneten müdürlerin ve takım müdürünün adı KESİLMEZ (2026-10-10 kullanıcı bildirimi): hepsi listelenir, her satır
    // kendi kutusuna sığar (uzun ad / adres alt satıra sarar), kırpma yok.
    await expect(team.locator('[data-slot="ex-team-admin"]')).toHaveCount(8)
    const clipped = await team.evaluate((root) => [...root.querySelectorAll('[data-slot="ex-team-admin"], [data-slot="ex-team-manager"], [data-slot="ex-team-preview-list"] li')]
      .filter((el) => el.scrollWidth > el.clientWidth + 1 || el.getBoundingClientRect().right > root.getBoundingClientRect().right + 1)
      .map((el) => `${el.getAttribute('data-slot') || 'li'} ${el.scrollWidth}>${el.clientWidth}`))
    expect(clipped, 'kesilen ad / adres').toEqual([])
    if (vp.width < 1024) {
      await expect(list).toBeHidden()
      await expect(page.locator('[data-slot="ex-delivery-back"]')).toBeVisible()
    }
    expect(await pageOverflow(page), 'yatay taşma (takım)').toBeLessThanOrEqual(1)
    expect(await page.evaluate(offenders, '[data-slot="ex-page"]'), 'görünüm alanı dışına taşan öğeler (takım)').toEqual([])
    if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="ex-delivery"]'), 'küçük dokunma hedefleri (takım)').toEqual([])
    await team.screenshot({ path: `test-results/executive-team-settings-${vp.name}.png` })

    // Telefon/tablet: geri düğmesi listeye döner
    if (vp.width < 1024) {
      await page.locator('[data-slot="ex-delivery-back"]').click()
      await expect(list).toBeVisible()
      await expect(detail).toBeHidden()
    }
    await context.close()
  })
}
