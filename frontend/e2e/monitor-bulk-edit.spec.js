// İzleme sayfası GENİŞLETİLMİŞ toplu düzenleme (2026-11-19) — gerçek tarayıcıda.
//
// HTTP sayfasında kartlar seçilir, çubuktaki "Bulk edit" penceresi tembel açılır: kontrol aralığı seçilince önizleme her
// izlemeyi "değişecek / atlanacak (neden)" diye listeler (uzun ad sarar), "Bildirim kanalları" ve "Etiketler" alanları
// sığar; uygula TEK istek gönderir (`POST /api/monitoring/bulk-edit`, gövde sözleşmesi), sonuç satır satır gösterilir;
// kapatınca seçim temizlenir. Telefon (390, dokunmatik), tablet (768, dokunmatik), dizüstü (1280): taşma yok, metin kesilmez,
// dokunma hedefleri ≥ 40 px.
import { test, expect } from '@playwright/test'
import { mockApi, MONITORS } from './support/monitorMocks.js'
import { GATE_VIEWPORTS, expectLayout, pageOverflow, json } from './support/gateChecks.js'

const LONG_NAME = 'Ödeme servisleri yedek bölge 2 — kurumsal ödeme giriş uç noktası (Kurumsal ve Bireysel Bankacılık Ağ Operasyonları / Takım A)'

async function setup(page) {
  const seen = []
  const http = MONITORS.http.map((m, i) => (i === 0 ? { ...m, name: LONG_NAME } : m))
  await mockApi(page, { role: 'ADMIN', globalAdmin: true, teamIds: [1], monitors: { ...MONITORS, http } })
  await page.route((u) => new URL(u).pathname === '/api/monitoring/bulk-edit', async (route) => {
    const body = JSON.parse(route.request().postData() || '{}')
    seen.push(body)
    const results = body.ids.map((id, i) => (i === 0
      ? { id, name: LONG_NAME, team_id: 1, team_name: 'Takım A', status: 'done', reason: 'done' }
      : { id, name: `izleme ${id}`, team_id: 1, team_name: 'Takım A', status: 'skipped', reason: i % 2 ? 'unchanged' : 'routing_locked' }))
    const count = (s) => results.filter((r) => r.status === s).length
    return route.fulfill(json({ success: true, data: { type: body.type, action: body.action, done: count('done'),
      skipped: count('skipped'), failed: 0, results } }))
  })
  return seen
}

for (const vp of GATE_VIEWPORTS) {
  test.describe(`izleme toplu düzenleme — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('seç → Bulk edit → aralık önizlemesi → kanallar / etiketler sığar → uygula tek istek → sonuç → seçim temizlenir', async ({ page }) => {
      test.setTimeout(150_000)
      const seen = await setup(page)
      await page.goto('/?tab=http')
      const first = page.getByRole('checkbox', { name: /for bulk action/ }).first()
      await expect(first).toBeVisible({ timeout: 60_000 })
      await first.click()
      const bar = page.locator('[data-slot="bulk-action-bar"]')
      await expect(bar).toBeVisible()
      await bar.getByRole('button', { name: 'Select all on this page' }).click()
      const open = bar.locator('[data-slot="bulk-edit-open"]')
      await expect(open).toHaveText(/Bulk edit/)
      await open.click()

      const dlg = page.getByRole('dialog')
      await expect(dlg.locator('[data-slot="bulk-dialog"]')).toBeVisible({ timeout: 20_000 })
      await expect(dlg.locator('[data-slot="bulk-confirm"]')).toBeDisabled()
      await dlg.locator('[data-slot="mbe-interval"]').selectOption('600')
      const rows = dlg.locator('[data-slot="bulk-preview-row"]')
      await expect(rows.first()).toBeVisible()
      // uzun ad tam görünür (sarar, kesilmez)
      await expect(dlg.locator('[data-slot="bulk-preview-row"]', { hasText: LONG_NAME })).toBeVisible()
      await expect(dlg.locator('[data-slot="mbe-row-change"]').first()).toContainText('→')
      await expectLayout(page, '[role="dialog"]', { touch: vp.touch, label: `toplu düzenle aralık @${vp.name}` })

      await dlg.locator('[data-slot="mbe-action"]').getByRole('button', { name: 'Notification channels' }).click()
      await dlg.locator('[data-slot="mbe-email"]').getByRole('button', { name: 'Off' }).click()
      await expect(dlg.locator('[data-slot="mbe-group-mode"]').getByRole('button', { name: 'Specific group' })).toBeEnabled()
      await expectLayout(page, '[role="dialog"]', { touch: vp.touch, label: `toplu düzenle kanallar @${vp.name}` })

      await dlg.locator('[data-slot="mbe-action"]').getByRole('button', { name: 'Tags' }).click()
      const addTag = dlg.locator('[data-slot="mbe-tags"] input').first()
      await addTag.fill('kritik-servis')
      await addTag.press('Enter')
      await expect(dlg.locator('[data-slot="bulk-preview-row"][data-state="change"]').first()).toBeVisible()
      await expectLayout(page, '[role="dialog"]', { touch: vp.touch, label: `toplu düzenle etiketler @${vp.name}` })

      await dlg.locator('[data-slot="mbe-action"]').getByRole('button', { name: 'Check interval' }).click()
      await dlg.locator('[data-slot="mbe-interval"]').selectOption('600')
      await dlg.locator('[data-slot="bulk-confirm"]').click()
      await expect(dlg.locator('[data-slot="bulk-dialog"]')).toHaveAttribute('data-phase', 'result', { timeout: 20_000 })
      expect(seen).toHaveLength(1)
      expect(seen[0]).toMatchObject({ type: 'http', action: 'interval', interval_seconds: 600 })
      expect(seen[0].ids.length).toBeGreaterThan(1)
      await expect(dlg.locator('[data-slot="bulk-result-row"][data-reason="routing_locked"]').first()).toContainText(/access sharing/)
      await expectLayout(page, '[role="dialog"]', { touch: vp.touch, label: `toplu düzenle sonuç @${vp.name}` })
      expect(await pageOverflow(page)).toBeLessThanOrEqual(1)

      await dlg.locator('[data-slot="bulk-close"]').click()
      await expect(page.locator('[data-slot="bulk-action-bar"]')).toHaveCount(0)
    })
  })
}
