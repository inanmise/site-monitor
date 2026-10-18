// Sentetik izleme → gizli env değerini GÖSTER (2026-10-19, kullanıcı isteği: "sentetik izlemede ortam değişkenleri gizli
// yapıldığında admin tarafından da görülemiyor. Admin kullanıcısı gizli bilgileri review edebilmeli"). Üç boyutta (390
// telefon + dokunmatik, 768 tablet + dokunmatik, 1280 dizüstü) gerçek tarayıcıda: düğme yalnız `can_reveal` satırının
// KAYITLI gizli değişkeninde; tek bir POST gider; değer salt okunur eş aralıklı kutuda, Kopyala + Gizle ile; sayfa ve
// pencere yatay taşmaz; uzun ad sararak tam görünür; dokunma hedefleri ≥ 40 px; Gizle kaldırır; sunucu hatası satır içinde.
// API tamamen mock'lu (yalnız /api/ yolu). Değerler uydurmadır.
import { test, expect } from '@playwright/test'
import { mockApi, MONITORS } from './support/monitorMocks.js'

const LONG_NAME = 'ODEME_SERVISI_ISTEMCI_GIZLI_ANAHTARI_URETIM_ORTAMI_BOLGE_2'
const SECRET = 'eyJhbGciOiJIUzI1NiJ9.' + 'Q2FyZE9kZW1lU2VydmlzaUdpemxpRGVnZXJp'.repeat(6) + '.Pz9-Gizli-7781'

const MON = {
  ...MONITORS.scripted[0],
  id: 100, name: 'Ödeme akışı — sepete ekle, adres ve kart doğrulama', active: true, status: 'PASS',
  script: "import http from 'k6/http';\nexport default function () { http.get(__ENV.BASE_URL) }\n",
  can_check: true, can_edit: true, can_delete: true, can_reveal: true,
  env: [
    { name: 'BASE_URL', secret: false, value: 'https://odeme.example.com' },
    { name: LONG_NAME, secret: true, value_set: true },
    { name: 'BOS_GIZLI', secret: true, value_set: false },
  ],
}

async function setup(page, { status = 200, body = null } = {}) {
  await mockApi(page, { monitors: { ...MONITORS, scripted: [MON] } })
  const reveals = []
  await page.route((u) => /\/api\/monitoring\/scripted\/\d+\/env\/[^/]+\/reveal$/.test(new URL(u).pathname), async (route) => {
    reveals.push({ method: route.request().method(), path: new URL(route.request().url()).pathname })
    const name = decodeURIComponent(new URL(route.request().url()).pathname.split('/').at(-2))
    await route.fulfill({ status, contentType: 'application/json', headers: { 'Cache-Control': 'no-store' },
      body: JSON.stringify(body ?? { success: true, data: { name, value: SECRET } }) })
  })
  return reveals
}

async function openEdit(page) {
  const card = page.locator('.upt-grid [data-slot="card"]').filter({ hasText: MON.name }).first()
  await expect(card).toBeVisible({ timeout: 20_000 })
  const edit = card.getByRole('button', { name: new RegExp(`— (Düzenle|Edit)$`) })
  if (await edit.isVisible()) await edit.click()
  else {
    await card.locator('[data-slot="monitor-card-more"] button').first().click()
    await page.getByRole('menuitem', { name: /^(Düzenle|Edit)$/ }).click()
  }
  const dlg = page.getByRole('dialog').last()
  await expect(dlg).toBeVisible()
  return dlg
}

async function pageOverflow(page) {
  return page.evaluate(() => {
    const main = document.querySelector('.app-main') || document.body
    return Math.max(document.documentElement.scrollWidth - document.documentElement.clientWidth, main.scrollWidth - main.clientWidth)
  })
}

/** 40 px'ten kısa görünür denetimler (yalnız bu özelliğin denetimleri). */
function smallTargets() {
  const out = []
  const sel = '[data-slot="env-secret-reveal-toggle"], [data-slot="env-secret-reveal"] button, [data-slot="env-secret-value"]'
  for (const b of document.querySelectorAll(sel)) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0) continue
    if (r.height < 39.5 || (b.tagName === 'BUTTON' && r.width < 39.5)) out.push(`${b.getAttribute('data-slot') || b.getAttribute('aria-label') || b.textContent} ${Math.round(r.width)}×${Math.round(r.height)}`)
  }
  return out
}

/** Pencere gövdesi ya da panel yatay taşıyor mu; panel penceresinin dışına çıkıyor mu. */
function panelFits() {
  const panel = document.querySelector('[data-slot="env-secret-reveal"]')
  const dlg = panel?.closest('[role="dialog"]')
  if (!panel || !dlg) return ['NO_PANEL']
  const out = []
  const p = panel.getBoundingClientRect()
  const d = dlg.getBoundingClientRect()
  if (p.left < d.left - 1 || p.right > d.right + 1) out.push(`panel ${Math.round(p.left)}–${Math.round(p.right)} > pencere ${Math.round(d.left)}–${Math.round(d.right)}`)
  if (panel.scrollWidth > panel.clientWidth + 1) out.push(`panel taşıyor ${panel.scrollWidth}/${panel.clientWidth}`)
  // Panelin içinde durduğu kaydırılan pencere gövdesi yatay taşmamalı (kod düzenleyicisinin KENDİ yatay kaydırması sayılmaz)
  for (let el = panel.parentElement; el && el !== dlg.parentElement; el = el.parentElement) {
    const cs = getComputedStyle(el)
    if (cs.overflowY === 'auto' || cs.overflowY === 'scroll') {
      if (el.scrollWidth > el.clientWidth + 1) out.push(`gövde yatay taşıyor ${el.scrollWidth}/${el.clientWidth}`)
      break
    }
  }
  const label = panel.querySelector('label')
  if (label && label.scrollWidth > label.clientWidth + 1) out.push('etiket kesiliyor')
  return out
}

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

for (const vp of VIEWPORTS) {
  test.describe(`Sentetik izleme gizli değer — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('yalnız kayıtlı gizli satırda göster; tek POST; değer + Kopyala + Gizle sığar; dokunma hedefleri; Gizle kaldırır', async ({ page }) => {
      test.setTimeout(120_000)
      const reveals = await setup(page)
      await page.goto('/?tab=scripted')
      const dlg = await openEdit(page)
      expect(await page.evaluate(() => matchMedia('(pointer: coarse)').matches)).toBe(vp.touch)

      const toggles = dlg.locator('[data-slot="env-secret-reveal-toggle"]')
      await expect(toggles).toHaveCount(1)
      const toggle = dlg.getByRole('button', { name: new RegExp(`^(Gizli değeri göster|Show secret value): ${LONG_NAME}$`) })
      await expect(toggle).toBeVisible()
      await expect(dlg.locator('[data-slot="env-reveal-hint"]')).toBeVisible()
      await toggle.scrollIntoViewIfNeeded()
      await toggle.click()

      const box = dlg.locator('[data-slot="env-secret-value"]')
      await expect(box).toHaveValue(SECRET)
      await expect(box).toHaveAttribute('readonly', '')
      expect(await box.evaluate((el) => getComputedStyle(el).fontFamily)).toMatch(/mono|courier|consolas/i)
      expect(reveals).toEqual([{ method: 'POST', path: `/api/monitoring/scripted/100/env/${LONG_NAME}/reveal` }])
      await expect(dlg.locator('[data-slot="env-secret-reveal"]')).toContainText(LONG_NAME)

      await page.waitForTimeout(250)
      expect(await pageOverflow(page), 'sayfa yatay taşması').toBeLessThanOrEqual(1)
      expect(await page.evaluate(panelFits), 'panel sığmıyor').toEqual([])
      if (vp.touch) {
        const bad = await page.evaluate(smallTargets)
        expect(bad, `40 px altı hedefler: ${bad.join(', ')}`).toEqual([])
      }
      // satırın kendi değer kutusu boş kalır — göstermek değeri forma yazmaz
      const rowInputs = dlg.locator('[data-slot="env-row"]').nth(1).locator('input[data-slot="input"]')
      await expect(rowInputs.nth(1)).toHaveValue('')

      await dlg.locator('[data-slot="env-secret-hide"]').click()
      await expect(dlg.locator('[data-slot="env-secret-reveal"]')).toHaveCount(0)
      await expect(page.locator('body')).not.toContainText('Pz9-Gizli-7781')
    })

    test('sunucu reddi (429) iletisi satır içinde; değer görünmez', async ({ page }) => {
      test.setTimeout(120_000)
      const msg = 'Çok sık gizli değer gösterimi — kullanıcı başına dakikada en çok 10, saatte en çok 30 kez. 42 sn sonra yeniden deneyin.'
      await setup(page, { status: 429, body: { success: false, code: 'RATE_LIMITED', error: msg } })
      await page.goto('/?tab=scripted')
      const dlg = await openEdit(page)
      const toggle = dlg.locator('[data-slot="env-secret-reveal-toggle"]')
      await toggle.scrollIntoViewIfNeeded()
      await toggle.click()
      const err = dlg.locator('[data-slot="env-secret-reveal"][data-state="error"]')
      await expect(err).toContainText('42 sn sonra yeniden deneyin')
      await expect(dlg.locator('[data-slot="env-secret-value"]')).toHaveCount(0)
      expect(await pageOverflow(page), 'sayfa yatay taşması').toBeLessThanOrEqual(1)
    })
  })
}

test('can_reveal olmayan satırda göster düğmesi yok', async ({ page }) => {
  test.setTimeout(120_000)
  await mockApi(page, { monitors: { ...MONITORS, scripted: [{ ...MON, can_reveal: false }] } })
  await page.goto('/?tab=scripted')
  const dlg = await openEdit(page)
  await expect(dlg.locator('[data-slot="env-row"]')).toHaveCount(3)
  await expect(dlg.locator('[data-slot="env-secret-reveal-toggle"]')).toHaveCount(0)
  await expect(dlg.locator('[data-slot="env-reveal-hint"]')).toHaveCount(0)
})
