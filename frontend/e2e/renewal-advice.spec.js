// Sertifika yenileme önerileri → yenileme planı uçtan uca (yayın öncesi kapı, 2026-11-15).
//
// Plan kaydet: kartın "Plan renewal" eylemi → pencere (tarih seçilmeden kayıt kapalı) → hızlı seçim + not → POST
// /api/forecast/{alan}/plan gövdesi {date, note}; sunucu listesi henüz eski (plan yok) dönse de kartın "Planned for …" rozeti
// kalır (oturum içi geçersiz kılma). Plan kaldır: onay → DELETE; sunucu listesi bayat planı döndürmeyi sürdürse de rozet
// GİZLİ kalır (null geçersiz kılma); liste yakalayınca (plan yok) geçersiz kılma düşer ve SONRA başkasının eklediği plan
// görünür (`reconcilePlanOverrides`). Kapsamlı yönetici: yalnız yönettiği takımın satırında plan düğmesi (`canPlanRow`),
// izin yoksa hiçbirinde. Telefon (390, dokunmatik), tablet (768, dokunmatik), dizüstü (1280): taşma yok, metin kesilmez,
// dokunma ≥ 40 px.
import { test, expect } from '@playwright/test'
import { mockApi, RENEWAL_ADVICE } from './support/monitorMocks.js'
import { GATE_VIEWPORTS, expectLayout, expectInViewport, json } from './support/gateChecks.js'

/**
 * ÜRÜN HATALARI, raporlandı (yayın öncesi kapı 2026-11-15) — dokunmatikte 40 px altı, kapıdan dışarıda tutulur:
 * grup / etiket süzgeç çipleri 32 px (`renewal/AdviceCard.jsx` `pointer-coarse:h-8`), kart başlığındaki alan adı düğmesi
 * 24 px (`rn-domain`, `h-auto p-0`; aynı eylem kartın "Sertifikayı aç" düğmesinde 40 px), tablette süzgeç tetikleri 32 px
 * (`ui/FacetedFilter.jsx`, renewal araç çubuğu).
 */
const KNOWN_SMALL = [/^button\[button\] "Filter by [^"]*" \d+x32$/, /^button\[rn-domain\] "[^"]*" \d+x24$/,
  /^button\[popover-trigger\] "(Reason|Team|Group|Tag)" \d+x32$/]

const PERMS = { 'inventory.list': { view: true }, 'inventory.crud': { view: true, edit: true } }
const PLAN_DOMAIN = 'auth.example.com'      // takım 1, 28 gün — hızlı seçimler bitişten önce
const CLEAR_DOMAIN = 'm.example.com'        // takım 1, 33 gün — sunucuda kayıtlı plan
const dayKey = (days) => { const d = new Date(Date.now() + days * 86_400_000); return d.toISOString().slice(0, 10) }

/**
 * `/api/renewal-advice` durumlu: `server` = sunucunun döndürdüğü plan (alan → {at, note}); kayıt / silme istekleri
 * `sink`'e yazılır, liste ise test hangi anı isterse onu döner (bayat önbellek benzetimi).
 */
async function mockRenewal(page, { me = null, perms = PERMS } = {}) {
  const sink = { posts: [], deletes: [], server: { [CLEAR_DOMAIN]: { at: dayKey(10), note: 'Önceki plan' } }, listCalls: 0 }
  await mockApi(page, { role: 'ADMIN', globalAdmin: me ? me.global_admin : true, perms })
  if (me) {
    await page.route((u) => new URL(u).pathname === '/api/me', (route) => route.fulfill(json({
      success: true, username: 'demo', system_role: 'ADMIN', global_admin: false, weekly_reports_visible: true,
      team_id: 1, team_name: 'Takım A', team_ids: [1], team_names: ['Takım A'], ...me })))
  }
  await page.route((u) => {
    const p = new URL(u).pathname
    return p === '/api/renewal-advice' || p.startsWith('/api/forecast/')
  }, async (route) => {
    const req = route.request()
    const p = new URL(req.url()).pathname
    const m = req.method()
    if (p === '/api/renewal-advice') {
      sink.listCalls += 1
      const rows = RENEWAL_ADVICE.map((a) => {
        const s = sink.server[a.domain]
        return s ? { ...a, renewal_planned_at: s.at, renewal_planned_note: s.note } : a
      })
      return route.fulfill(json({ success: true, data: rows, count: rows.length, timestamp: new Date().toISOString() }))
    }
    const mt = p.match(/^\/api\/forecast\/([^/]+)\/plan$/)
    if (mt && m === 'POST') {
      const body = JSON.parse(req.postData() || '{}')
      sink.posts.push({ domain: decodeURIComponent(mt[1]), ...body })
      return route.fulfill(json({ success: true, data: { renewal_planned_at: body.date, renewal_planned_note: body.note || '' } }))
    }
    if (mt && m === 'DELETE') {
      sink.deletes.push(decodeURIComponent(mt[1]))
      return route.fulfill(json({ success: true, data: { renewal_planned_at: null } }))
    }
    return route.fallback()
  })
  return sink
}

const card = (page, domain) => page.locator('[data-slot="renewal-card"]')
  .filter({ has: page.locator('[data-slot="rn-domain"]', { hasText: new RegExp(`^${domain.replace(/\./g, '\\.')}$`) }) })

for (const vp of GATE_VIEWPORTS) {
  test.describe(`yenileme planı — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('plan kaydet → POST; kaldır → DELETE + bayat liste gizli; liste yakalayınca başkasının planı görünür', async ({ page }) => {
      test.setTimeout(150_000)
      const sink = await mockRenewal(page)
      await page.goto('/?tab=renewal')
      const root = page.locator('[data-slot="renewal-advice"]')
      await expect(root.locator('[data-slot="rn-list"]')).toBeVisible({ timeout: 60_000 })
      await expectLayout(page, '[data-slot="renewal-advice"]', { touch: vp.touch, allowSmall: KNOWN_SMALL, label: `yenileme listesi @${vp.name}` })

      // ── Plan kaydet
      const planCard = card(page, PLAN_DOMAIN)
      await expect(planCard).toHaveCount(1)
      await expect(planCard.locator('[data-slot="rn-planned"]')).toHaveCount(0)
      await planCard.getByRole('button', { name: `${PLAN_DOMAIN} — Plan renewal`, exact: true }).click()
      let dlg = page.getByRole('dialog', { name: `Renewal plan — ${PLAN_DOMAIN}` })
      await expect(dlg).toBeVisible()
      await expectInViewport(dlg, vp.width, 'plan penceresi')
      const save = dlg.getByRole('button', { name: 'Save plan', exact: true })
      await expect(save).toBeDisabled()
      await dlg.locator('[data-slot="plan-quick"] [data-pick="week"]').click()
      await expect(save).toBeEnabled()
      await dlg.getByRole('textbox', { name: /^Note/ }).fill('Değişiklik penceresi CR-2026-118 ile; NetScaler + WAF birlikte.')
      await expectLayout(page, '[role="dialog"]', { touch: vp.touch, label: `plan penceresi @${vp.name}` })
      await save.click()
      await expect.poll(() => sink.posts.length, { timeout: 15_000 }).toBe(1)
      expect(sink.posts[0]).toMatchObject({ domain: PLAN_DOMAIN, note: 'Değişiklik penceresi CR-2026-118 ile; NetScaler + WAF birlikte.' })
      expect(sink.posts[0].date).toMatch(/^\d{4}-\d{2}-\d{2}$/)
      expect(sink.posts[0].date >= dayKey(0) && sink.posts[0].date <= dayKey(8)).toBe(true)
      await expect(page.getByText(`Plan saved for ${PLAN_DOMAIN}`)).toBeVisible()
      await expect(dlg).toHaveCount(0)
      // Sunucu listesi plansız dönse de (bayat) bu oturumda kaydedilen plan görünür
      await expect(planCard.locator('[data-slot="rn-planned"]')).toContainText('Planned for')

      // ── Plan kaldır: sunucu bayat planı döndürmeyi sürdürür → rozet yine gizli
      const clearCard = card(page, CLEAR_DOMAIN)
      await expect(clearCard.locator('[data-slot="rn-planned"]')).toContainText('Planned for')
      await clearCard.getByRole('button', { name: `${CLEAR_DOMAIN} — Plan renewal`, exact: true }).click()
      dlg = page.getByRole('dialog', { name: `Renewal plan — ${CLEAR_DOMAIN}` })
      await expect(dlg).toBeVisible()
      await dlg.getByRole('button', { name: 'Remove plan', exact: true }).click()
      const confirm = page.getByRole('dialog', { name: 'Remove this plan?' })
      await expect(confirm).toBeVisible()
      await expect(confirm).toContainText(CLEAR_DOMAIN)
      expect(sink.deletes).toHaveLength(0)
      const callsBefore = sink.listCalls
      await confirm.getByRole('button', { name: 'Remove plan', exact: true }).click()
      await expect.poll(() => sink.deletes).toEqual([CLEAR_DOMAIN])
      await expect(page.getByText(`Plan removed for ${CLEAR_DOMAIN}`)).toBeVisible()
      await expect.poll(() => sink.listCalls, { timeout: 15_000 }).toBeGreaterThan(callsBefore)   // silmeden sonra liste yeniden okunur
      await expect(clearCard.locator('[data-slot="rn-planned"]')).toHaveCount(0)

      // Liste yakalar (plan yok) → geçersiz kılma düşer; ardından başkası yeni plan ekler → görünür
      delete sink.server[CLEAR_DOMAIN]
      const refresh = root.getByRole('button', { name: 'Refresh', exact: true })
      let n = sink.listCalls
      await refresh.click()
      await expect.poll(() => sink.listCalls).toBeGreaterThan(n)
      await expect(refresh).toBeEnabled()
      await expect(clearCard.locator('[data-slot="rn-planned"]')).toHaveCount(0)
      sink.server[CLEAR_DOMAIN] = { at: dayKey(20), note: 'Başka ekip planladı' }
      n = sink.listCalls
      await refresh.click()
      await expect.poll(() => sink.listCalls).toBeGreaterThan(n)
      await expect(clearCard.locator('[data-slot="rn-planned"]')).toContainText('Planned for', { timeout: 15_000 })
      await expectLayout(page, '[data-slot="renewal-advice"]', { touch: vp.touch, allowSmall: KNOWN_SMALL, label: `yenileme listesi sonrası @${vp.name}` })
    })

    test('kapsamlı yönetici: plan yalnız yönettiği takımın satırında; izin yoksa hiçbirinde', async ({ page }) => {
      test.setTimeout(120_000)
      await mockRenewal(page, { me: { manage_team_ids: [1], view_team_ids: [1, 2, 3] } })
      await page.goto('/?tab=renewal')
      await expect(page.locator('[data-slot="rn-list"]')).toBeVisible({ timeout: 60_000 })
      // Takım 1 → plan düğmesi; takım 2 / takımsız → yok
      await expect(card(page, PLAN_DOMAIN).getByRole('button', { name: `${PLAN_DOMAIN} — Plan renewal`, exact: true })).toBeVisible()
      for (const d of ['status.example.org', 'portal.example.com']) {
        await expect(card(page, d)).toHaveCount(1)
        await expect(card(page, d).getByRole('button', { name: /Plan renewal/ })).toHaveCount(0)
      }
      // Kayıtlı plan rozeti yine görünür (okuma)
      await expect(card(page, CLEAR_DOMAIN).locator('[data-slot="rn-planned"]')).toContainText('Planned for')
    })

    test('envanter yazma izni yoksa hiçbir kartta plan düğmesi yok', async ({ page }) => {
      test.setTimeout(120_000)
      await mockRenewal(page, { perms: { 'inventory.list': { view: true } } })
      await page.goto('/?tab=renewal')
      await expect(page.locator('[data-slot="rn-list"]')).toBeVisible({ timeout: 60_000 })
      await expect(page.locator('[data-slot="renewal-card"]').first()).toBeVisible()
      await expect(page.locator('[data-slot="rn-card-actions"]').getByRole('button', { name: /Plan renewal/ })).toHaveCount(0)
    })
  })
}
