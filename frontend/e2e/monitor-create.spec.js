// İzleme formları — oluştur (POST) ve düzenle (PUT) gövdeleri uçtan uca (yayın öncesi kapı, 2026-11-15).
//
// Beş tür: HTTP (gelişmiş istek: POST gövdesi + içerik türü, JSON yolu + beklenen değer, yavaş yanıt eşiği), Port (HTTP
// denetim türü + yol + beklenen durum), DNS (kayıt türü + beklenen değer), Alan adı (sunucunun alan hatası ALANIN ALTINDA)
// ve Sayfa bütünlüğü (site taraması + sınır dışı "en az kırık kaynak" ALANIN ALTINDA). Her türde: zorunlu alanlar
// doldurulmadan istek gitmez, hatalar alanın altında; kayıtta 7/24 kapalıysa onay sorulur ("Evet" → nocNotify:true,
// "Hayır" → false); düzenlemede PUT gövdesi satırın grup / etiket / takım değerlerini taşır. Telefon (390, dokunmatik),
// tablet (768, dokunmatik), dizüstü (1280): taşma yok, metin kesilmez, dokunma hedefleri ≥ 40 px. API mock.
import { test, expect } from '@playwright/test'
import { mockApi, MONITORS } from './support/monitorMocks.js'
import { GATE_VIEWPORTS, expectLayout, expectInViewport, json } from './support/gateChecks.js'

const TEAMS = [{ id: 1, name: 'Takım A', active: true }, { id: 2, name: 'Takım B', active: true }]
const NOC_DIALOG = '[role="dialog"]:has([data-slot="noc-save-confirm"])'
const FORM = '[role="dialog"]:has([data-slot="noc-notify-field"])'
const API = { http: 'http', port: 'port', dns: 'dns', domain: 'domain', page: 'page' }

async function mockMonitors(page, type, sink, { serverFieldError } = {}) {
  await mockApi(page)
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const req = route.request()
    const p = new URL(req.url()).pathname
    const m = req.method()
    if (p === '/api/admin/teams' && m === 'GET') return route.fulfill(json({ success: true, data: TEAMS }))
    if (p === '/api/noc/groups/options') {
      return route.fulfill(json({ success: true, data: { groups: [{ id: 1, name: '7/24 Ana Grup', is_default: true, active: true }],
        disabled_types: [], has_active_group: true, min_level: 'CRITICAL' } }))
    }
    if (p === '/api/monitoring/groups') return route.fulfill(json({ success: true, data: [{ id: 3, name: 'Kurumsal Web' }, { id: 4, name: 'Ödeme Sistemleri' }] }))
    if (p === '/api/monitoring/tags') return route.fulfill(json({ success: true, data: [] }))
    if (p === `/api/monitoring/${API[type]}` && m === 'POST') {
      const body = JSON.parse(req.postData() || '{}')
      sink.posts.push(body)
      if (serverFieldError && sink.posts.length === 1) return route.fulfill(json({ success: false, ...serverFieldError }, 400))
      return route.fulfill(json({ success: true, data: { id: 990, ...body } }))
    }
    const mm = p.match(new RegExp(`^/api/monitoring/${API[type]}/(\\d+)$`))
    if (mm && m === 'PUT') {
      const body = JSON.parse(req.postData() || '{}')
      sink.puts.push({ id: Number(mm[1]), body })
      return route.fulfill(json({ success: true, data: { id: Number(mm[1]), ...body } }))
    }
    if (/\/check$/.test(p) && m === 'POST') return route.fulfill(json({ success: true, data: {} }))
    return route.fallback()
  })
}

async function pick(scope, label, option) {
  await scope.getByRole('combobox', { name: label }).click()
  await scope.page().getByRole('option', { name: option, exact: typeof option === 'string' }).click()
}

async function tag(form, value) {
  const input = form.getByPlaceholder(/^(type and press Enter to add…|add tag…)$/)
  await input.scrollIntoViewIfNeeded()
  await input.fill(value)
  await input.press('Enter')
}

/** Kayıt: Kaydet → 7/24 onayı → yanıt (yes | no). */
async function saveAnswering(page, form, answer, vp) {
  await form.getByRole('button', { name: 'Save', exact: true }).click()
  const dlg = page.locator(NOC_DIALOG)
  await expect(dlg).toBeVisible()
  await expectInViewport(dlg, vp.width, '7/24 onayı')
  await dlg.locator(`[data-slot="noc-confirm-${answer}"]`).click()
}

async function openNew(page, type, title) {
  await page.goto(`/?tab=${type}`)
  const add = page.locator('[data-tour="mon-new"]').filter({ visible: true }).first()
  await add.waitFor({ timeout: 60_000 })
  await add.click()
  const form = page.getByRole('dialog', { name: title })
  await expect(form).toBeVisible()
  return form
}

/** Düzenleme: ilk kartın Düzenle eylemi; satır kimliği PUT yolundan okunur. */
async function editFirst(page, type, title, vp) {
  await page.goto(`/?tab=${type}`)
  const card = page.locator('.upt-grid > [data-slot="card"]').first()
  await expect(card).toBeVisible({ timeout: 60_000 })
  const edit = card.getByRole('button', { name: /— Edit$/ })
  if (await edit.isVisible()) await edit.click()
  else {
    await card.getByRole('button', { name: /— (More actions|Actions)$/ }).first().click()
    await page.getByRole('menuitem', { name: 'Edit', exact: true }).click()
  }
  const form = page.getByRole('dialog', { name: title })
  await expect(form).toBeVisible()
  await expectInViewport(form, vp.width, `${type} düzenleme formu`)
  return form
}

/** PUT gövdesi: değişen ad + satırın grup / etiket değerleri (setter tuzağı yok). */
async function expectPutKeeps(sink, type, newName) {
  await expect.poll(() => sink.puts.length, { timeout: 15_000 }).toBe(1)
  const { id, body } = sink.puts[0]
  const row = MONITORS[type].find((r) => r.id === id)
  expect(row, `PUT ${type}/${id} bilinen bir satır`).toBeTruthy()
  expect(body.name).toBe(newName)
  expect(body.groupName).toBe(row.group_name)
  expect(body.tags).toBe(row.tags)
  expect(body.nocNotify).toBe(false)
  expect(body.active).toBe(row.active)
}

for (const vp of GATE_VIEWPORTS) {
  test.describe(`izleme formları — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('HTTP: gelişmiş istek (POST gövdesi, JSON yolu, yavaş yanıt) + alan hataları + 7/24 "Evet" · düzenleme PUT', async ({ page }) => {
      test.setTimeout(150_000)
      const sink = { posts: [], puts: [] }
      await mockMonitors(page, 'http', sink)
      const form = await openNew(page, 'http', /^New HTTP Monitor/)
      const save = form.getByRole('button', { name: 'Save', exact: true })
      await expect(save, 'URL + takım yokken Kaydet kilitli').toBeDisabled()
      await form.getByRole('textbox', { name: /^URL to monitor/ }).fill('https://odeme-servisi.example.com/api/health')
      await pick(form, /^Method/, 'POST')
      await pick(form, /^Team/, 'Takım A')
      // Grup + etiket boş → alanların altında hata, istek yok
      await save.click()
      const invalid = form.locator('[data-slot="field"][data-invalid="true"]')
      await expect(invalid.filter({ hasText: 'A group is required; the monitor cannot be saved.' })).toHaveCount(1)
      await expect(form.getByText('At least one tag is required; the monitor cannot be saved.')).toBeVisible()
      expect(sink.posts).toHaveLength(0)
      await expectInViewport(form, vp.width, 'HTTP formu')
      await expectLayout(page, FORM, { touch: vp.touch, label: `HTTP formu hatalar @${vp.name}` })

      // Gelişmiş istek: geçersiz JSON yolu ALANIN ALTINDA
      const adv = form.locator('[data-slot="http-advanced"]')
      const trigger = adv.getByRole('button', { name: /Advanced request/ })
      if ((await trigger.getAttribute('aria-expanded')) !== 'true') await trigger.click()
      await adv.getByRole('textbox', { name: /^Request body/ }).fill('{"ping": true}')
      await adv.getByRole('textbox', { name: /^Content type/ }).fill('application/json')
      await adv.getByRole('textbox', { name: /^JSON path/ }).fill('$.items[')
      await adv.getByRole('textbox', { name: /^Expected value/ }).fill('UP')
      await adv.getByRole('checkbox', { name: /^Slow response alert/ }).click()
      await adv.getByRole('spinbutton', { name: /^Slowness threshold/ }).fill('1500')
      await pick(form, /^Group/, 'Kurumsal Web')
      await tag(form, 'prod')
      await save.click()
      await expect(invalid.filter({ hasText: 'Invalid JSON path. Example: $.status or $.items[0].id' })).toHaveCount(1)
      expect(sink.posts).toHaveLength(0)
      await expectLayout(page, FORM, { touch: vp.touch, label: `HTTP gelişmiş @${vp.name}` })
      await adv.getByRole('textbox', { name: /^JSON path/ }).fill('$.status')

      // Kayıt → 7/24 onayı "Evet"
      await saveAnswering(page, form, 'yes', vp)
      await expect.poll(() => sink.posts.length, { timeout: 15_000 }).toBe(1)
      expect(sink.posts[0]).toMatchObject({
        url: 'https://odeme-servisi.example.com/api/health', method: 'POST', teamId: 1, groupName: 'Kurumsal Web', tags: 'prod',
        nocNotify: true, requestBody: '{"ping": true}', requestContentType: 'application/json', jsonPath: '$.status', jsonExpected: 'UP',
        slowResponseEnabled: true, slowThresholdMs: 1500, active: true,
      })
      await expect(page.getByText('HTTP monitor saved')).toBeVisible()
      await expect(form).toHaveCount(0)

      // Düzenleme
      const edit = await editFirst(page, 'http', /^Edit HTTP Monitor/, vp)
      await expectLayout(page, FORM, { touch: vp.touch, label: `HTTP düzenleme @${vp.name}` })
      await edit.getByRole('textbox', { name: 'Name', exact: true }).fill('Ödeme servisi sağlık ucu')
      await saveAnswering(page, edit, 'no', vp)
      await expectPutKeeps(sink, 'http', 'Ödeme servisi sağlık ucu')
    })

    test('Port: HTTP denetim türü + yol + beklenen durum · düzenleme PUT', async ({ page }) => {
      test.setTimeout(120_000)
      const sink = { posts: [], puts: [] }
      await mockMonitors(page, 'port', sink)
      const form = await openNew(page, 'port', /^New Port Monitor/)
      await form.getByRole('textbox', { name: /^Host/ }).fill('db.example.com')
      await form.getByRole('spinbutton', { name: /^Port/ }).fill('8443')
      await pick(form, /^Check Type/, 'HTTP(S) — Status code')
      await form.getByRole('textbox', { name: /^Request path/ }).fill('/health')
      await form.getByRole('textbox', { name: /^Expected status code/ }).fill('200')
      await pick(form, /^Team/, 'Takım A')
      await pick(form, /^Group/, 'Ödeme Sistemleri')
      await tag(form, 'prod')
      await expectLayout(page, FORM, { touch: vp.touch, label: `Port formu @${vp.name}` })
      await saveAnswering(page, form, 'no', vp)
      await expect.poll(() => sink.posts.length, { timeout: 15_000 }).toBe(1)
      expect(sink.posts[0]).toMatchObject({ host: 'db.example.com', port: 8443, protocol: 'HTTP', sendData: '/health', expect: '200',
        teamId: 1, groupName: 'Ödeme Sistemleri', tags: 'prod', nocNotify: false })

      const edit = await editFirst(page, 'port', /^Edit Port Monitor/, vp)
      await edit.getByRole('textbox', { name: 'Name', exact: true }).fill('Ödeme veritabanı portu')
      await saveAnswering(page, edit, 'no', vp)
      await expectPutKeeps(sink, 'port', 'Ödeme veritabanı portu')
      expect(sink.puts[0].body.host).toBe(MONITORS.port.find((r) => r.id === sink.puts[0].id).host)
    })

    test('DNS: kayıt türü + beklenen değer · düzenleme PUT', async ({ page }) => {
      test.setTimeout(120_000)
      const sink = { posts: [], puts: [] }
      await mockMonitors(page, 'dns', sink)
      const form = await openNew(page, 'dns', /^New DNS Monitor/)
      await form.getByRole('textbox', { name: /^Domain/ }).fill('odeme.example.com')
      await pick(form, /^Team/, 'Takım A')
      await pick(form, /^Group/, 'Kurumsal Web')
      await pick(form, /^Record Type/, 'MX')
      await form.getByRole('textbox', { name: /^Expected value/ }).fill('mx1.example.com')
      await tag(form, 'dns')
      await expectLayout(page, FORM, { touch: vp.touch, label: `DNS formu @${vp.name}` })
      await saveAnswering(page, form, 'no', vp)
      await expect.poll(() => sink.posts.length, { timeout: 15_000 }).toBe(1)
      expect(sink.posts[0]).toMatchObject({ domain: 'odeme.example.com', recordType: 'MX', expectedValue: 'mx1.example.com', teamId: 1,
        groupName: 'Kurumsal Web', tags: 'dns', nocNotify: false })

      const edit = await editFirst(page, 'dns', /^Edit DNS Monitor/, vp)
      await edit.getByRole('textbox', { name: 'Name', exact: true }).fill('Ödeme MX kaydı')
      await saveAnswering(page, edit, 'no', vp)
      await expectPutKeeps(sink, 'dns', 'Ödeme MX kaydı')
      expect(sink.puts[0].body.recordType).toBe(MONITORS.dns.find((r) => r.id === sink.puts[0].id).record_type)
    })

    test('Alan adı: sunucunun alan hatası ALANIN ALTINDA, düzeltip kayıt · düzenleme PUT', async ({ page }) => {
      test.setTimeout(120_000)
      const sink = { posts: [], puts: [] }
      await mockMonitors(page, 'domain', sink, { serverFieldError: { field: 'domain', code: 'VALIDATION_FAILED',
        error: 'This domain is already monitored by your team (ornek-alanadi.com.tr).' } })
      const form = await openNew(page, 'domain', /^New Domain Monitor/)
      const domainInput = form.getByRole('textbox', { name: /^Domain/ })
      await domainInput.fill('ornek-alanadi.com.tr')
      await pick(form, /^Team/, 'Takım A')
      await pick(form, /^Group/, 'Kurumsal Web')
      await tag(form, 'alanadi')
      await saveAnswering(page, form, 'no', vp)
      await expect.poll(() => sink.posts.length, { timeout: 15_000 }).toBe(1)
      const invalid = form.locator('[data-slot="field"][data-invalid="true"]')
      await expect(invalid.filter({ hasText: 'This domain is already monitored by your team' })).toHaveCount(1)
      await expect(form).toBeVisible()
      await expectLayout(page, FORM, { touch: vp.touch, label: `Alan adı sunucu hatası @${vp.name}` })
      await domainInput.fill('yeni-alanadi.com.tr')
      await expect(invalid).toHaveCount(0)
      await saveAnswering(page, form, 'no', vp)
      await expect.poll(() => sink.posts.length, { timeout: 15_000 }).toBe(2)
      expect(sink.posts[1]).toMatchObject({ domain: 'yeni-alanadi.com.tr', teamId: 1, groupName: 'Kurumsal Web', tags: 'alanadi', nocNotify: false })
      expect(sink.posts[1].warningDays).toBeGreaterThan(0)
      expect(sink.posts[1].criticalDays).toBeGreaterThan(0)
      await expect(form).toHaveCount(0)

      const edit = await editFirst(page, 'domain', /^Edit Domain Monitor/, vp)
      await edit.getByRole('textbox', { name: 'Name', exact: true }).fill('Kurumsal alan adı')
      await saveAnswering(page, edit, 'no', vp)
      await expectPutKeeps(sink, 'domain', 'Kurumsal alan adı')
    })

    test('Sayfa bütünlüğü: site taraması + sınır dışı eşik ALANIN ALTINDA · düzenleme PUT', async ({ page }) => {
      test.setTimeout(120_000)
      const sink = { posts: [], puts: [] }
      await mockMonitors(page, 'page', sink)
      const form = await openNew(page, 'page', /^New Page Monitor/)
      await form.getByRole('textbox', { name: /^URL/ }).fill('https://www.example.com/kampanya')
      await pick(form, /^Team/, 'Takım A')
      await pick(form, /^Group/, 'Kurumsal Web')
      await pick(form, /^Mode/, 'Site Crawl')
      await tag(form, 'web')
      const minBroken = form.getByRole('spinbutton', { name: /^Minimum broken resources to alarm/ })
      await minBroken.fill('0')
      await form.getByRole('button', { name: 'Save', exact: true }).click()
      const invalid = form.locator('[data-slot="field"][data-invalid="true"]')
      await expect(invalid.filter({ hasText: 'Minimum broken resources for an alert must be a whole number between 1 and 100.' })).toHaveCount(1)
      await expect(page.locator(NOC_DIALOG)).toHaveCount(0)
      expect(sink.posts).toHaveLength(0)
      await expectLayout(page, FORM, { touch: vp.touch, label: `Sayfa formu @${vp.name}` })
      await minBroken.fill('3')
      await saveAnswering(page, form, 'no', vp)
      await expect.poll(() => sink.posts.length, { timeout: 15_000 }).toBe(1)
      expect(sink.posts[0]).toMatchObject({ url: 'https://www.example.com/kampanya', mode: 'SITE_CRAWL', minBrokenResources: 3, teamId: 1,
        groupName: 'Kurumsal Web', tags: 'web', nocNotify: false })

      const edit = await editFirst(page, 'page', /^Edit Page Monitor/, vp)
      await edit.getByRole('textbox', { name: 'Name', exact: true }).fill('Kampanya sayfası')
      await saveAnswering(page, edit, 'no', vp)
      await expectPutKeeps(sink, 'page', 'Kampanya sayfası')
    })
  })
}
