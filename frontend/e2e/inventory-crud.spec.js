// Sertifika envanteri — uçtan uca ekle / düzenle / sil (yayın öncesi kapı, 2026-11-15).
//
// Genel Bakış'tan "Domain Ekle" → Envanter sekmesi + ekleme formu: boş kayıtta alan hataları ALANIN ALTINDA
// (alan adı, takım, grup, etiket); doldurulunca POST /api/admin/inventory gövdesi alan adı, port, kademe, takım ve vekil
// anahtarını taşır. Düzenleme (kart → Düzenle): PUT gövdesi 13 operasyonel bayrağı kayıttaki değerleriyle TAŞIR (setter
// tuzağı — bayrak gövdede yoksa sunucu üstüne null yazardı), değiştirilen bayrak yeni değeriyle gider. Silme (kart →
// Sil): onay KALICI olduğunu söyler ve kaydı ADIYLA anar; DELETE sonrası kart Genel Bakış'tan kalkar. Telefon (390,
// dokunmatik), tablet (768, dokunmatik) ve dizüstünde (1280): taşma yok, metin kesilmez, dokunma hedefleri ≥ 40 px.
// API tümüyle mock — gerçek kişi / kurum adı yok.
import { test, expect } from '@playwright/test'
import { mockApi, INVENTORY } from './support/monitorMocks.js'
import { PERMS, mockCertApi } from './support/certMocks.js'
import { GATE_VIEWPORTS, expectLayout, expectInViewport, json } from './support/gateChecks.js'
import { INVENTORY_FLAGS } from '../src/utils/inventoryFlags.js'

const TEAMS = [
  { id: 1, name: 'Takım A', active: true },
  { id: 2, name: 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi', active: true },
]
const NEW_DOMAIN = 'yeni-odeme-servisi.example.com'

async function mockInventory(page, sink) {
  await mockApi(page, { perms: PERMS })
  await mockCertApi(page)
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const req = route.request()
    const p = new URL(req.url()).pathname
    const m = req.method()
    if (p === '/api/admin/teams' && m === 'GET') return route.fulfill(json({ success: true, data: TEAMS }))
    // 7/24 kullanılamaz (aktif grup yok) → kayıtta 7/24 onayı sorulmaz; o akış monitor-create / noc-save-confirm'de
    if (p === '/api/noc/groups/options') return route.fulfill(json({ success: true, data: { groups: [], disabled_types: [], has_active_group: false } }))
    if (p === '/api/monitoring/groups') return route.fulfill(json({ success: true, data: [{ id: 3, name: 'Kurumsal Web' }, { id: 4, name: 'Ödeme Sistemleri' }] }))
    if (p === '/api/monitoring/tags') return route.fulfill(json({ success: true, data: [] }))
    if (p === '/api/admin/platforms') return route.fulfill(json({ success: true, data: [{ code: 'IIS', name: 'IIS' }] }))
    if (p === '/api/admin/inventory' && m === 'POST') {
      sink.post = JSON.parse(req.postData() || '{}')
      return route.fulfill(json({ success: true, data: { id: 901, ...sink.post } }))
    }
    if (/^\/api\/admin\/inventory\/\d+$/.test(p) && m === 'PUT') {
      sink.put = { id: Number(p.split('/').pop()), body: JSON.parse(req.postData() || '{}') }
      return route.fulfill(json({ success: true, data: { id: sink.put.id, ...sink.put.body } }))
    }
    if (/^\/api\/admin\/inventory\/\d+$/.test(p) && m === 'DELETE') {
      sink.deleted = Number(p.split('/').pop())
      return route.fulfill(json({ success: true, message: 'deleted' }))
    }
    if (/\/health\/refresh$/.test(p) && m === 'POST') {
      sink.refreshed = (sink.refreshed || 0) + 1
      return route.fulfill(json({ success: true, data: {} }))
    }
    return route.fallback()
  })
}

/** SearchableSelect (shadcn combobox): tetiğe bas, seçeneği seç. */
async function pick(dlg, label, option) {
  await dlg.getByRole('combobox', { name: label }).click()
  await dlg.page().getByRole('option', { name: option }).click()
}

/** Kartın Düzenle / Sil eylemi: geniş ekranda simge düğmesi, telefonda "Diğer işlemler" menüsü. */
async function cardAction(page, card, domain, action) {
  const direct = card.getByRole('button', { name: `${domain} — ${action}`, exact: true })
  if (await direct.isVisible()) { await direct.click(); return }
  await card.getByRole('button', { name: `${domain} — More actions` }).click()
  await page.getByRole('menuitem', { name: action, exact: true }).click()
}

for (const vp of GATE_VIEWPORTS) {
  test.describe(`envanter CRUD — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('Genel Bakış "Domain Ekle" → alan hataları alanın altında → POST gövdesi', async ({ page }) => {
      test.setTimeout(150_000)
      const sink = {}
      await mockInventory(page, sink)
      await page.goto('/?tab=dashboard')
      const add = page.locator('[data-slot="dash-add-domain"]')
      await add.waitFor({ timeout: 60_000 })
      await add.click()
      await expect(page).toHaveURL(/tab=domains/)
      const dlg = page.getByRole('dialog', { name: /Add Domain/ })
      await expect(dlg).toBeVisible({ timeout: 15_000 })

      // Alan adı + takım boşken Kaydet kilitli (istek gidemez)
      const saveBtn = dlg.getByRole('button', { name: 'Save', exact: true })
      await expect(saveBtn).toBeDisabled()
      const domainInput = dlg.getByRole('textbox', { name: /^Domain/ })
      const invalid = dlg.locator('[data-slot="field"][data-invalid="true"]')

      // Biçim hatası: şema / yol içeren alan adı — ayrılınca ALANIN ALTINDA ileti, kutu aria-invalid
      await domainInput.fill('https://yanlis.example.com/yol')
      await domainInput.blur()
      await expect(domainInput).toHaveAttribute('aria-invalid', 'true')
      await expect(invalid.filter({ hasText: 'Enter a valid domain (for example www.example.com)' })).toBeVisible()

      // Alan adı + takım dolu, grup ve etiket boş → Kaydet: grup ve etiket hataları ALANLARIN ALTINDA, istek gitmez
      await domainInput.fill(NEW_DOMAIN)
      await expect(invalid).toHaveCount(0)
      await pick(dlg, /^Team/, 'Takım A')
      await saveBtn.click()
      await expect(invalid.filter({ hasText: 'A group is required' })).toHaveCount(1)
      await expect(dlg.getByText('At least one tag is required; the record cannot be saved.')).toHaveCount(1)
      expect(sink.post, 'eksik alanla istek gitmez').toBeUndefined()
      await expectInViewport(dlg, vp.width, 'form penceresi')
      await expectLayout(page, '[role="dialog"]', { touch: vp.touch, label: `form hataları @${vp.name}` })

      // Doldur: port, kademe, grup, etiket, vekil anahtarı
      await dlg.getByRole('spinbutton', { name: 'Port' }).fill('8443')
      await pick(dlg, /^Criticality Tier/, /^Tier 2/)
      await pick(dlg, /^Group/, 'Kurumsal Web')
      const tags = dlg.getByPlaceholder('type and press Enter to add…')
      await tags.scrollIntoViewIfNeeded()
      await tags.fill('prod')
      await tags.press('Enter')
      const proxy = dlg.getByRole('switch', { name: /^Check via Proxy/ })
      await proxy.scrollIntoViewIfNeeded()
      await proxy.click()
      await expect(proxy).toHaveAttribute('aria-checked', 'true')
      await expect(dlg.locator('[data-slot="field"][data-invalid="true"]')).toHaveCount(0)

      await dlg.getByRole('button', { name: 'Save', exact: true }).click()
      await expect.poll(() => sink.post, { timeout: 15_000 }).toBeTruthy()
      expect(sink.post).toMatchObject({ domain: NEW_DOMAIN, port: 8443, tier: 2, team_id: 1, use_proxy: true,
        group_name: 'Kurumsal Web', tags: 'prod', active: true, noc_notify: false })
      // 13 operasyonel bayrağın hepsi gövdede (setter tuzağı): yeni kayıtta vekil dışı hepsi false
      for (const { key } of INVENTORY_FLAGS) expect(sink.post, `bayrak ${key}`).toHaveProperty(key, key === 'use_proxy')
      // Kayıt sonrası ilk kontrol koşar, form kapanır
      await expect.poll(() => sink.refreshed || 0).toBeGreaterThanOrEqual(1)
      await expect(dlg).toHaveCount(0, { timeout: 15_000 })
    })

    test('kart → Düzenle: PUT bayrakları korur · kart → Sil: kalıcı onay adıyla, kart kalkar', async ({ page }) => {
      test.setTimeout(150_000)
      const sink = {}
      await mockInventory(page, sink)
      const domain = 'www.example.com'
      const record = INVENTORY.find((r) => r.domain === domain)
      await page.goto('/?tab=dashboard')
      const card = page.locator(`[data-slot="cert-grid"] [data-slot="card"][data-domain="${domain}"]`)
      await card.waitFor({ timeout: 60_000 })
      await card.scrollIntoViewIfNeeded()

      // ── Düzenle ──
      await cardAction(page, card, domain, 'Edit')
      const dlg = page.getByRole('dialog', { name: /Edit Domain/ })
      await expect(dlg).toBeVisible({ timeout: 15_000 })
      await expect(dlg.getByRole('textbox', { name: /^Domain/ })).toHaveValue(domain)
      await expectInViewport(dlg, vp.width, 'düzenleme penceresi')
      await expectLayout(page, '[role="dialog"]', { touch: vp.touch, label: `düzenleme formu @${vp.name}` })
      const desc = dlg.getByRole('textbox', { name: 'Description', exact: true })
      await desc.fill('Kurumsal web uygulaması — yenileme NetScaler üzerinde')
      const ev = dlg.locator('[data-slot="inv-flag"]').filter({ hasText: 'EV Certificate' }).getByRole('switch')
      await ev.scrollIntoViewIfNeeded()
      await ev.click()
      await dlg.getByRole('button', { name: 'Save', exact: true }).click()
      await expect.poll(() => sink.put, { timeout: 15_000 }).toBeTruthy()
      expect(sink.put.id).toBe(record.id)
      const body = sink.put.body
      expect(body).toMatchObject({ domain, port: 443, team_id: 1, tier: 1, description: 'Kurumsal web uygulaması — yenileme NetScaler üzerinde',
        group_name: record.group_name, tags: record.tags })
      for (const { key } of INVENTORY_FLAGS) {
        const want = key === 'ev_certificate' ? true : record[key]
        expect(body, `bayrak ${key} (setter tuzağı)`).toHaveProperty(key, want)
      }
      expect(body.netscaler, 'kayıttaki açık bayrak korunur').toBe(true)
      expect(body.waf_enabled).toBe(true)
      await expect(dlg).toHaveCount(0, { timeout: 15_000 })

      // ── Sil ──
      await card.scrollIntoViewIfNeeded()
      await cardAction(page, card, domain, 'Delete')
      const confirm = page.getByRole('dialog', { name: 'Delete permanently' })
      await expect(confirm).toBeVisible()
      await expect(confirm).toContainText(`"${domain}" will be permanently deleted`)
      await expect(confirm).toContainText(/can't be undone/)
      await expectInViewport(confirm, vp.width, 'silme onayı')
      await expectLayout(page, '[role="dialog"]', { touch: vp.touch, label: `silme onayı @${vp.name}` })
      expect(sink.deleted, 'onaydan önce silme yok').toBeUndefined()
      await confirm.getByRole('button', { name: 'Delete permanently' }).click()
      await expect.poll(() => sink.deleted, { timeout: 15_000 }).toBe(record.id)
      await expect(card).toHaveCount(0, { timeout: 15_000 })
      await expect(page.getByText(`"${domain}" permanently deleted`)).toBeVisible()
      // Diğer kartlar yerinde
      await expect(page.locator('[data-slot="cert-grid"] [data-slot="card"]').first()).toBeVisible()
      await expectLayout(page, '.app-main', { touch: false, label: `pano silme sonrası @${vp.name}` })
    })
  })
}
