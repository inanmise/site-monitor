import { test, expect } from '@playwright/test'

/**
 * Bildirim Grupları → TEMİZLİK (2026-11-12, zenginleştirme #7) — mweb. İzole harness
 * (e2e/harness/notify-groups-cleanup.html) gerçek NotificationGroups'u sabit veriyle (ağ yok) mount eder. Üç boyutta:
 * bölüm/sayfa yatay TAŞMAZ; KPI şeridi çizilir ve "Kullanılmayan" kartı doğru sayıyı (2) gösterir; karta tıklayınca
 * liste yalnız 0 kullanımlı aktif gruplara süzülür (kullanımdaki grup gizlenir); satır kullanım rozetleri doğru
 * (data-usage=3 / 0); dokunmatik boyutlarda hedefler ≥ 40 px; uzun takım adı kesilmez.
 */
const VIEWPORTS = [
  { name: 'phone',  width: 390,  height: 844,  touch: true },
  { name: 'tablet', width: 768,  height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800,  touch: false },
]

async function overflow(page) {
  return page.evaluate(() => {
    const el = document.documentElement
    const sec = document.querySelector('[data-slot="notify-groups"]')
    const s = sec ? sec.scrollWidth - sec.clientWidth : 0
    return Math.max(el.scrollWidth - el.clientWidth, s)
  })
}

/** Dokunmatikte 40 px'ten kısa görünür kontroller (RESPONSIVE.md §4; pointer-coarse sınıfları uygulanır). */
async function smallTargets(root) {
  return root.evaluate((el) => {
    const out = []
    for (const b of el.querySelectorAll('button, a[href], input:not([type="hidden"]), [role="button"]')) {
      const r = b.getBoundingClientRect()
      if (r.width === 0 || r.height === 0) continue
      const cs = getComputedStyle(b)
      if (cs.visibility === 'hidden' || cs.display === 'none') continue
      if (b.closest('[hidden], [aria-hidden="true"]')) continue
      // Seçim kutusu (shadcn Checkbox = button[role=checkbox]) 40 px'lik <label> içinde: dokunma alanı etiketin kendisi
      // (toplu seçim, 2026-11-13 — admin-bulk.spec ile aynı kural)
      const h = b.getAttribute('role') === 'checkbox' ? Math.max(r.height, b.closest('label')?.getBoundingClientRect().height ?? 0) : r.height
      if (h < 39.5) out.push(`${b.tagName.toLowerCase()}:${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 24)} ${Math.round(r.height)}`)
    }
    return out
  })
}

for (const vp of VIEWPORTS) {
  test(`gruplar temizliği (${vp.name} ${vp.width}): taşma yok, KPI + süzgeç + rozet`, async ({ browser }) => {
    const context = await browser.newContext({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch })
    const page = await context.newPage()
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await page.goto('/e2e/harness/notify-groups-cleanup.html')

    const section = page.locator('[data-slot="notify-groups"]')
    await expect(section).toBeVisible()
    await expect(page.getByText('SY Nöbet Listesi')).toBeVisible()   // liste yüklendi

    // KPI şeridi: "Kullanılmayan" kartı 2 (g11, g12 = 0 kullanım); kart değeri bilinen (— değil)
    const unused = section.locator('[data-slot="stat-item"][data-key="unused"]')
    await expect(unused).toBeVisible()
    await expect(unused.locator('[data-slot="stat-value"]')).toHaveText('2')

    // Satır kullanım rozetleri: kullanımda → sayı(3), kullanılmayan → 0 (en az 2 tane)
    await expect(section.locator('[data-slot="ng-usage"][data-usage="3"]')).toHaveCount(1)
    expect(await section.locator('[data-slot="ng-usage"][data-usage="0"]').count()).toBeGreaterThanOrEqual(2)

    // Yatay taşma yok
    expect(await overflow(page), 'yatay taşma').toBeLessThanOrEqual(1)

    // Alıcı önizlemesi (2026-11-13): "Pasif alıcılı" = 2 (g14 tümü, g15 kısmi); satır rozetleri; sessiz kayıp bandı g14'ü adlandırır
    await expect(section.locator('[data-slot="stat-item"][data-key="passive"] [data-slot="stat-value"]')).toHaveText('2')
    await expect(section.locator('[data-slot="ng-recipients"][data-state="none"]')).toHaveCount(1)
    await expect(section.locator('[data-slot="ng-recipients"][data-state="partial"]')).toHaveCount(1)
    const dead = section.locator('[data-slot="ng-dead-banner"]')
    await expect(dead).toContainText('UG Varsayılan Liste — Tümü Pasif Hesaplar')
    await expect(dead).not.toContainText('Kısmi Pasif Dağıtım')
    // Kısmi grubun adres listesi: teslim edilen + düşen ayrı; açılır kutu ekrana sığar, uzun adres sarar
    const row = section.locator('tr', { hasText: 'Kısmi Pasif Dağıtım' })
    await row.locator('[data-slot="ng-mailchip"]').click()
    const preview = page.locator('[data-slot="ng-recipients-preview"]')
    await expect(preview).toBeVisible()
    await expect(preview).toContainText('aktif.kisi@example.com')
    await expect(preview.locator('[data-slot="ng-mail-dropped"]')).toHaveText(['ayrilan.kisi.cok.uzun.adres.ornegi@example.com.tr'])
    const box = await preview.boundingBox()
    expect(box.x, 'açılır kutu solda taşmaz').toBeGreaterThanOrEqual(0)
    expect(box.x + box.width, 'açılır kutu sağda taşmaz').toBeLessThanOrEqual(vp.width + 1)
    await page.keyboard.press('Escape')
    await expect(preview).toHaveCount(0)
    expect(await overflow(page), 'önizleme sonrası taşma').toBeLessThanOrEqual(1)

    // "Kullanılmayan" kartı → liste yalnız 0 kullanımlı aktif gruplar; kullanımdaki (g10) gizlenir
    await unused.click()
    await expect(page.getByText('SY Nöbet Listesi')).toHaveCount(0)
    await expect(page.getByText('Eski Ekip Dağıtımı')).toBeVisible()
    await expect(page.getByText('Adressiz Grup')).toBeVisible()
    expect(await overflow(page), 'süzme sonrası taşma').toBeLessThanOrEqual(1)

    // Dokunmatik boyutlarda hedefler ≥ 40 px
    if (vp.touch) {
      const small = await smallTargets(section)
      expect(small, `küçük dokunma hedefleri:\n${small.join('\n')}`).toEqual([])
    }

    await context.close()
  })
}
