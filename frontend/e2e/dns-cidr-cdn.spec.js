// DNS beklenen değerde ağ aralığı (CIDR) + CDN rotasyonu (2026-11-18, kullanıcı raporu: Cloudflare arkasındaki bir alan adı
// her sorguda farklı adres çifti döndürüyor — 188.114.96.3/97.3 → 96.5/97.5 — ve her seferinde "DNS değişti" alarmı açılıyordu).
//
// Uçtan uca: (1) kart CDN rotasyon çipi sağlayıcıyı adlandırır, metni kesilmez; (2) yeni izleme formunda A kaydı için aralık
// ipucu görünür, çok geniş aralık KAYDETMEDEN alanın altında hata verir (istek gitmez, 7/24 onayı açılmaz), düzeltince
// aralık sunucuya aynen gider; (3) sunucunun `expectedValue` alan hatası bildirim değil alanın altında gösterilir;
// (4) MX kaydında aralık ipucu yok. Telefon (390, dokunmatik), tablet (768, dokunmatik), dizüstü (1280): taşma yok,
// metin kesilmez, dokunma hedefleri ≥ 40 px. API mock.
import { test, expect } from '@playwright/test'
import { mockApi, MONITORS } from './support/monitorMocks.js'
import { GATE_VIEWPORTS, expectLayout, expectInViewport, json } from './support/gateChecks.js'

const TEAMS = [{ id: 1, name: 'Takım A', active: true }]
const NOC_DIALOG = '[role="dialog"]:has([data-slot="noc-save-confirm"])'
const FORM = '[role="dialog"]:has([data-slot="noc-notify-field"])'
const CDN_NAME = 'Kurumsal internet şubesi (Cloudflare arkasındaki ön yüz) — bireysel ve kurumsal müşteri girişleri'

function dnsRows() {
  return MONITORS.dns.map((r, i) => (i === 5
    ? { ...r, name: CDN_NAME, record_type: 'A', value: '188.114.96.5\n188.114.97.5', changed: false, rotated: true,
        rotation_cdn: 'Cloudflare', response_ms: 18 }
    : r))
}

async function mockDns(page, sink, { serverFieldError } = {}) {
  await mockApi(page, { monitors: { ...MONITORS, dns: dnsRows() } })
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const req = route.request()
    const p = new URL(req.url()).pathname
    const m = req.method()
    if (p === '/api/admin/teams' && m === 'GET') return route.fulfill(json({ success: true, data: TEAMS }))
    if (p === '/api/noc/groups/options') {
      return route.fulfill(json({ success: true, data: { groups: [{ id: 1, name: '7/24 Ana Grup', is_default: true, active: true }],
        disabled_types: [], has_active_group: true, min_level: 'CRITICAL' } }))
    }
    if (p === '/api/monitoring/groups') return route.fulfill(json({ success: true, data: [{ id: 3, name: 'Kurumsal Web' }] }))
    if (p === '/api/monitoring/tags') return route.fulfill(json({ success: true, data: [] }))
    if (p === '/api/monitoring/dns' && m === 'POST') {
      const body = JSON.parse(req.postData() || '{}')
      sink.posts.push(body)
      if (serverFieldError) return route.fulfill(json({ success: false, ...serverFieldError }, 400))
      return route.fulfill(json({ success: true, data: { id: 990, ...body } }))
    }
    return route.fallback()
  })
}

async function pick(scope, label, option) {
  await scope.getByRole('combobox', { name: label }).click()
  await scope.page().getByRole('option', { name: option, exact: typeof option === 'string' }).click()
}

async function openNew(page) {
  await page.goto('/?tab=dns')
  const add = page.locator('[data-tour="mon-new"]').filter({ visible: true }).first()
  await add.waitFor({ timeout: 60_000 })
  await add.click()
  const form = page.getByRole('dialog', { name: /^New DNS Monitor/ })
  await expect(form).toBeVisible()
  return form
}

async function fillRequired(form, recordType) {
  await form.getByRole('textbox', { name: /^Domain/ }).fill('www.kurumsal-ornek.com.tr')
  await pick(form, /^Team/, 'Takım A')
  await pick(form, /^Group/, 'Kurumsal Web')
  if (recordType !== 'A') await pick(form, /^Record Type/, recordType)
  const tagInput = form.getByPlaceholder(/^(type and press Enter to add…|add tag…)$/)
  await tagInput.scrollIntoViewIfNeeded()
  await tagInput.fill('cdn')
  await tagInput.press('Enter')
}

for (const vp of GATE_VIEWPORTS) {
  test.describe(`DNS aralık + CDN rotasyonu — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('kart: CDN rotasyon çipi sağlayıcıyı adlandırır, açıklaması alarm açılmadığını söyler', async ({ page }) => {
      test.setTimeout(120_000)
      await mockDns(page, { posts: [] })
      await page.goto('/?tab=dns')
      const card = page.locator('.upt-grid > [data-slot="card"]').filter({ hasText: CDN_NAME })
      await expect(card).toBeVisible({ timeout: 60_000 })
      const chip = card.locator('[data-slot="dns-rotation"][data-cdn="Cloudflare"]')
      await expect(chip).toHaveText('CDN rotation · Cloudflare')
      await chip.scrollIntoViewIfNeeded()
      await chip.locator('xpath=ancestor::button[1]').click()
      await expect(page.getByRole('tooltip')).toContainText('same CDN provider (Cloudflare)')
      await expect(page.getByRole('tooltip')).toContainText('no "DNS Change" alarm is raised')
      await page.keyboard.press('Escape')
      await expectLayout(page, '.upt-grid', { touch: vp.touch, label: `DNS kartları @${vp.name}` })
    })

    test('form: aralık ipucu, geniş aralık alanın altında hata (istek yok), düzeltince aralık aynen gider', async ({ page }) => {
      test.setTimeout(150_000)
      const sink = { posts: [] }
      await mockDns(page, sink)
      const form = await openNew(page)
      await fillRequired(form, 'A')
      await expect(form.locator('[data-slot="dns-expected-range-hint"]')).toBeVisible()
      const expected = form.getByRole('textbox', { name: /^Expected value/ })
      await expected.fill('188.114.96.3\n10.0.0.0/4')
      await form.getByRole('button', { name: 'Save', exact: true }).click()
      const error = form.locator('[data-slot="dns-expected-error"]')
      await expect(error).toBeVisible()
      await expect(error).toContainText('10.0.0.0/4')
      await expect(error).toContainText('/8')
      await expect(expected).toHaveAttribute('aria-invalid', 'true')
      await expect(page.locator(NOC_DIALOG), 'istemci hatasında 7/24 onayı açılmaz').toHaveCount(0)
      expect(sink.posts).toHaveLength(0)
      await expectInViewport(error, vp.width, 'aralık hatası')
      await expectLayout(page, FORM, { touch: vp.touch, label: `DNS formu aralık hatası @${vp.name}` })

      await expected.fill('188.114.96.0/20\n2a06:98c0::/29')
      await expect(error, 'yazınca hata kalkar').toHaveCount(0)
      await form.getByRole('button', { name: 'Save', exact: true }).click()
      await expect(form.locator('[data-slot="dns-expected-error"]'), 'AAAA aralığı A kaydında reddedilir').toContainText('2a06:98c0::/29')
      expect(sink.posts).toHaveLength(0)

      await expected.fill('188.114.96.0/20')
      await form.getByRole('button', { name: 'Save', exact: true }).click()
      const dlg = page.locator(NOC_DIALOG)
      await expect(dlg).toBeVisible()
      await dlg.locator('[data-slot="noc-confirm-no"]').click()
      await expect.poll(() => sink.posts.length, { timeout: 15_000 }).toBe(1)
      expect(sink.posts[0]).toMatchObject({ recordType: 'A', expectedValue: '188.114.96.0/20' })
    })

    test('form: sunucunun expectedValue alan hatası alanın altında; MX kaydında aralık ipucu yok', async ({ page }) => {
      test.setTimeout(150_000)
      const sink = { posts: [] }
      await mockDns(page, sink, { serverFieldError: { code: 'VALIDATION_FAILED', field: 'expectedValue',
        error: '"203.0.113.0/24" is a network range; ranges work only for A and AAAA records.' } })
      const form = await openNew(page)
      await fillRequired(form, 'MX')
      await expect(form.locator('[data-slot="dns-expected-range-hint"]')).toHaveCount(0)
      await form.getByRole('textbox', { name: /^Expected value/ }).fill('10 mx1.kurumsal-ornek.com.tr.')
      await form.getByRole('button', { name: 'Save', exact: true }).click()
      const dlg = page.locator(NOC_DIALOG)
      await expect(dlg).toBeVisible()
      await dlg.locator('[data-slot="noc-confirm-no"]').click()
      await expect.poll(() => sink.posts.length, { timeout: 15_000 }).toBe(1)
      const error = form.locator('[data-slot="dns-expected-error"]')
      await expect(error).toContainText('ranges work only for A and AAAA records')
      await expect(form, 'form açık kalır').toBeVisible()
      await expectLayout(page, FORM, { touch: vp.touch, label: `DNS formu sunucu hatası @${vp.name}` })
    })
  })
}
