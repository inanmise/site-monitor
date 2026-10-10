import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

// Veri Kalitesi (2026-10-10, kullanıcı: "tasarımlar shadcn ile yapılsın … mweb responsive yapıda olsun"): kurum puanı +
// takım sıralaması + düzeltme listesi. Üç boyutta (390 telefon, 768 tablet, 1280 dizüstü) ölçülür: sayfa ve pencere yatay
// taşmaz; telefonda takım KARTLARI (tablo yok); dokunma hedefleri ≥ 40 px; "Düzelt" kaydın kendi ekranını açar.
// Veri: e2e/support/dataQualityMocks.js (uzun takım adları, uzun alan adları/URL'ler, beş bant, Sahipsiz kovası, 7/24 notu).
const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'laptop', width: 1280, height: 800 },
]

/** Görünür düğme / bağlantı / alan içinde 40 px'ten kısa olanlar (dokunmatik kuralı — RESPONSIVE.md §4). */
async function smallTargets(root) {
  return root.evaluate((el) => {
    const out = []
    for (const b of el.querySelectorAll('button, a[href], select, input:not([type="hidden"])')) {
      const r = b.getBoundingClientRect()
      if (r.width === 0 || r.height === 0) continue
      const cs = getComputedStyle(b)
      if (cs.visibility === 'hidden' || cs.display === 'none') continue
      // Arama kutusu InputGroup içinde: ölçülen dış kap (alanın kendisi kabı doldurur)
      const box = b.closest('[data-slot="input-group"]')?.getBoundingClientRect() ?? r
      if (box.height < 39.5) out.push(`${b.tagName.toLowerCase()}:${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)} ${Math.round(box.height)}`)
    }
    return out
  })
}

async function pageOverflow(page) {
  return page.evaluate(() => {
    const main = document.querySelector('.app-main') || document.body
    return Math.max(document.documentElement.scrollWidth - document.documentElement.clientWidth, main.scrollWidth - main.clientWidth)
  })
}

for (const vp of VIEWPORTS) {
  test(`veri kalitesi (${vp.name} ${vp.width}): taşma yok, ${vp.width < 768 ? 'kartlar' : 'sıralama'}, dokunma hedefleri, düzeltme listesi`, async ({ page }) => {
    test.setTimeout(120_000)
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await mockApi(page, { role: 'ADMIN', globalAdmin: true })
    await page.goto('/?tab=dataquality')

    const pageRoot = page.locator('[data-slot="dq-page"]')
    await expect(pageRoot.locator('[data-slot="dq-org"]')).toBeVisible({ timeout: 20_000 })
    await expect(page.locator('[data-slot="dq-score-ring"]').first()).toBeVisible()

    // Telefonda kartlar, geniş kapta tablo (liste kabının genişliğine göre)
    if (vp.width < 768) {
      await expect(page.locator('[data-slot="dq-team-card"]')).toHaveCount(6)
      await expect(page.locator('[data-slot="dq-team-table"]')).toHaveCount(0)
    } else {
      const either = page.locator('[data-slot="dq-team-card"], [data-slot="dq-team-row"]')
      await expect(either).toHaveCount(6)
    }

    // Sayfa taşmaz
    expect(await pageOverflow(page), 'sayfa yatay taşması').toBeLessThanOrEqual(1)
    await page.screenshot({ path: `test-results/dq-page-${vp.name}.png`, fullPage: true })

    // Telefonda sayfanın dokunma hedefleri (kartlar + araç çubuğu + şeritler) ≥ 40 px
    if (vp.width < 768) {
      const bad = await smallTargets(pageRoot)
      expect(bad, `40 px altı hedefler: ${bad.join(', ')}`).toEqual([])
    }

    // Düzeltme listesi: en düşük puanlı takım (sıralamanın başı)
    await page.locator('[data-action="dq-team-open"]').first().click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()
    await expect(dialog.locator('[data-slot="dq-rule"]')).toHaveCount(6, { timeout: 10_000 })
    // Pencere ekrana sığar (telefonda tam ekran) ve yatay kaymaz
    const box = await dialog.boundingBox()
    expect(box.x).toBeGreaterThanOrEqual(-1)
    expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
    const dlgOverflow = await dialog.evaluate((el) => {
      let worst = 0
      for (const n of el.querySelectorAll('*')) {
        const r = n.getBoundingClientRect()
        if (r.width > 0) worst = Math.max(worst, r.right - window.innerWidth)
      }
      return worst
    })
    expect(dlgOverflow, 'pencere içeriği görünüm alanını aşıyor').toBeLessThanOrEqual(1)
    if (vp.width < 768) {
      const items = dialog.locator('[data-slot="dq-item"] [data-action="dq-open"]')
      const first = await items.first().boundingBox()
      expect(first.height).toBeGreaterThanOrEqual(39.5)
    }
    await page.screenshot({ path: `test-results/dq-detail-${vp.name}.png` })

    // En çok puan kaybettiren bölüm (eskalasyon) açık gelir; 7/24 bölümünü açıp "Düzelt" → Pano sertifika penceresinin 7/24 alanı
    await expect(dialog.locator('[data-slot="dq-rule"]').first()).toHaveAttribute('data-code', 'TEAM_NO_ESCALATION')
    const noc = dialog.locator('[data-slot="dq-rule"][data-code="NOC_CRITICAL_UNCOVERED"]')
    await noc.locator('[data-slot="accordion-trigger"]').click()
    await noc.locator('[data-action="dq-open"]').first().click()
    await expect(page).toHaveURL(/tab=dashboard/)
    await expect(page).toHaveURL(/open=noc/)
    await expect(page).toHaveURL(/domain=raporlama-ve-analitik-platformu/)
    // Geri: düzeltme listesine dönülür (dq_team adreste kaldı)
    await page.goBack()
    await expect(page).toHaveURL(/tab=dataquality/)
    await expect(page).toHaveURL(/dq_team=1/)
    await expect(page.getByRole('dialog')).toBeVisible()
  })
}

test('veri kalitesi: kapsamlı kullanıcıda Sahipsiz şeridi yok (yalnız tüm izlemeyi görenler)', async ({ page }) => {
  test.setTimeout(60_000)
  await page.setViewportSize({ width: 1280, height: 800 })
  await mockApi(page, { role: 'USER', globalAdmin: false })
  await page.goto('/?tab=dataquality')
  await expect(page.locator('[data-slot="dq-org"]')).toBeVisible({ timeout: 20_000 })
  await expect(page.locator('[data-action="dq-unassigned-open"]')).toHaveCount(0)
})
