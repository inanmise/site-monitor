// Kayıtta 7/24 onayı (2026-11-15, kullanıcı isteği: "7/24 ekibine alarmlar gitsin denilmemişse kaydederken pencere çıkar,
// isteyip istemediğini sor; evet derse 7/24'ü açıp kaydet"; karar: her kayıtta sor + "bu izleme için bir daha sorma").
// Telefon (390, dokunmatik), tablet (768, dokunmatik), dizüstü (1280): Ping izlemesi düzenlenir → Kaydet → pencere;
//  1) "Evet" → PUT gövdesi nocNotify:true;
//  2) "Vazgeç" → istek yok, form açık; sonra "Hayır" + "bir daha sorma" → nocNotify:false, sonraki kayıtta pencere YOK.
// Her aşamada: pencere ekrana sığar, metin kırpılmaz, dokunmatikte düğmeler ve onay satırı ≥ 40 px. API taklit.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]
const LONG_GROUP = 'Kurumsal 7/24 Operasyon Merkezi Nöbet Listesi (Ağ, Uygulama ve Veritabanı Ekipleri)'
const json = (body, status = 200) => (route) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
const DIALOG = '[role="dialog"]:has([data-slot="noc-save-confirm"])'

async function routes(page) {
  await mockApi(page)
  await page.route((u) => new URL(u).pathname === '/api/noc/groups/options', json({ success: true, data: {
    groups: [{ id: 1, name: LONG_GROUP, is_default: true, active: true }, { id: 2, name: 'Hafta Sonu', is_default: false, active: true }],
    disabled_types: [], has_active_group: true, min_level: 'CRITICAL',
  } }))
  const puts = []
  await page.route((u) => /^\/api\/monitoring\/ping\/\d+$/.test(new URL(u).pathname), (route) => {
    if (route.request().method() !== 'PUT') return route.fallback()
    const body = JSON.parse(route.request().postData() || '{}')
    puts.push(body)
    const id = Number(new URL(route.request().url()).pathname.split('/').pop())
    return json({ success: true, data: { id, ...body, noc_notify: !!body.nocNotify } })(route)
  })
  await page.route((u) => /^\/api\/monitoring\/ping\/\d+\/check$/.test(new URL(u).pathname), json({ success: true, data: {} }))
  return puts
}

/** Pencere içeriği: ekran dışına taşan öğe, kırpılan metin, dokunmatikte 40 px altı hedef. */
async function layout(page, touch) {
  return page.evaluate(({ sel, touch }) => {
    const box = document.querySelector(sel)
    const vw = document.documentElement.clientWidth
    const vh = document.documentElement.clientHeight
    const out = []
    const r = box.getBoundingClientRect()
    if (r.left < -1 || r.right > vw + 1 || r.top < -1 || r.bottom > vh + 1) out.push(`kutu ekran dışı ${Math.round(r.left)}..${Math.round(r.right)} / ${Math.round(r.top)}..${Math.round(r.bottom)}`)
    for (const el of box.querySelectorAll('p, label, button')) {
      if (el.scrollWidth > el.clientWidth + 1 && getComputedStyle(el).overflowX !== 'visible') out.push(`kırpık: ${el.textContent.slice(0, 40)}`)
      const er = el.getBoundingClientRect()
      if (er.right > r.right + 1 || er.left < r.left - 1) out.push(`taşan: ${el.textContent.slice(0, 40)}`)
    }
    if (touch) {
      for (const el of box.querySelectorAll('[data-slot="noc-confirm-actions"] button, [data-slot="noc-confirm-noask-row"]')) {
        const h = el.getBoundingClientRect().height
        if (h < 40 - 0.5) out.push(`küçük hedef ${Math.round(h)} px: ${el.textContent.slice(0, 30)}`)
      }
    }
    const pageOverflow = document.documentElement.scrollWidth - document.documentElement.clientWidth
    if (pageOverflow > 1) out.push(`sayfa yatay taşıyor ${pageOverflow}px`)
    return out
  }, { sel: DIALOG, touch })
}

for (const vp of VIEWPORTS) {
  test.describe(`Kayıtta 7/24 onayı — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('kapalı izleme kaydedilirken sorulur; "Evet" → 7/24 açık kaydedilir', async ({ page }) => {
      const puts = await routes(page)
      await page.goto('/?tab=ping')
      const card = page.locator('.upt-grid > [data-slot="card"]').first()
      await expect(card).toBeVisible({ timeout: 20_000 })
      await card.getByRole('button', { name: /— (Edit|Düzenle)$/ }).click()
      const form = page.locator('[role="dialog"]:has([data-slot="noc-notify-field"])')
      await expect(form).toBeVisible()
      await form.getByRole('button', { name: /^(Save|Kaydet)$/ }).click()
      const dlg = page.locator(DIALOG)
      await expect(dlg).toBeVisible()
      await expect(dlg.locator('[data-slot="noc-confirm-groups"]')).toContainText(LONG_GROUP)
      expect(puts).toHaveLength(0)
      expect(await layout(page, vp.touch), `@${vp.name}`).toEqual([])
      await dlg.locator('[data-slot="noc-confirm-yes"]').click()
      await expect.poll(() => puts.length).toBe(1)
      expect(puts[0].nocNotify).toBe(true)
      await expect(page.locator(DIALOG)).toHaveCount(0)
    })

    test('"Vazgeç" istek göndermez; "Hayır" + "bir daha sorma" → kapalı kaydedilir, sonraki kayıtta sorulmaz', async ({ page }) => {
      const puts = await routes(page)
      await page.goto('/?tab=ping')
      const card = page.locator('.upt-grid > [data-slot="card"]').first()
      await expect(card).toBeVisible({ timeout: 20_000 })
      const form = page.locator('[role="dialog"]:has([data-slot="noc-notify-field"])')
      const save = () => form.getByRole('button', { name: /^(Save|Kaydet)$/ }).click()
      await card.getByRole('button', { name: /— (Edit|Düzenle)$/ }).click()
      await save()
      await page.locator(DIALOG).locator('[data-slot="noc-confirm-cancel"]').click()
      await expect(page.locator(DIALOG)).toHaveCount(0)
      await expect(form).toBeVisible()
      expect(puts).toHaveLength(0)

      await save()
      const dlg = page.locator(DIALOG)
      await dlg.locator('[data-slot="noc-confirm-noask-row"] label').click()
      await expect(dlg.locator('[data-slot="noc-confirm-noask"]')).toHaveAttribute('data-state', 'checked')
      await dlg.locator('[data-slot="noc-confirm-no"]').click()
      await expect.poll(() => puts.length).toBe(1)
      expect(puts[0].nocNotify).toBe(false)
      await expect(form).toHaveCount(0)
      const stored = await page.evaluate(() => JSON.parse(localStorage.getItem('sm.noc.noAsk') || '[]'))
      expect(stored.length).toBe(1)

      // Aynı izleme yeniden kaydedilir → pencere yok, istek doğrudan gider
      await page.locator('.upt-grid > [data-slot="card"]').first().getByRole('button', { name: /— (Edit|Düzenle)$/ }).click()
      await save()
      await expect.poll(() => puts.length).toBe(2)
      await expect(page.locator(DIALOG)).toHaveCount(0)
    })
  })
}
