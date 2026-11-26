// H paketi (2026-11-26) — form düzeltmeleri gerçek tarayıcıda üç boyutta (390 / 768 dokunmatik, 1280):
//  - Envanter formu "Beklenen parmak izi": görünür, düzenlenir, "Temizle" ile boşaltılır; biçim (SHA-256, 64 onaltılık; iki
//    nokta / boşluk / tire serbest) alanın altında denetlenir. Düzenlemede DEĞİŞMEYEN alan saklı ham değer +
//    `expected_fingerprint_unchanged: true` ile gider (sürüm geçişinde eski pod anahtar yoksa siliyordu — inceleme bulgusu 1),
//    değişen alan bayraksız (temizlenen null, yeni değer büyük harf normalize); UG takımı da saklı değer + `ug_team_id_unchanged`.
//    Oluşturmada bayrak YOK; ekleme formunda da biçim normalize gider ve sunucunun 400 alan hatası alanın altında. UG takımı
//    salt okunur satırda adıyla (uzun AD adı sarar).
//  - Yenileme planı: sunucunun 400 `date` / `note` alan hatası alanın altında, alan odaklanır, alan değişince kalkar.
//  - Yönetici özeti ay seçicisi: sunucu listesi yoksa bu ay + son 24 ay (25 seçenek), sunucu listesi varsa o.
// API tümüyle mock — gerçek kişi / kurum adı yok.
import { test, expect } from '@playwright/test'
import { mockApi, INVENTORY, RENEWAL_ADVICE } from './support/monitorMocks.js'
import { PERMS, mockCertApi } from './support/certMocks.js'
import { GATE_VIEWPORTS, expectLayout, expectInViewport, json } from './support/gateChecks.js'
import { response, SETTINGS, TEAMS as EXEC_TEAMS, TEAM_DETAIL } from '../src/test/helpers/executiveFixtures.js'

const LONG_UG = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Kurumsal Ödeme Altyapısı)'
const TEAMS = [{ id: 1, name: 'Takım A', active: true }, { id: 2, name: LONG_UG, active: true }]
const DOMAIN = 'www.example.com'
const FP_HEX = 'AB'.repeat(32)
const FP_COLON = Array.from({ length: 32 }, () => 'cd').join(':')

/** Envanter formu: kart → Düzenle; `record` kaydı (by-domain) değiştirilebilir, PUT gövdeleri `sink.puts`'a, POST (ekleme)
 *  gövdeleri `sink.posts`'a; `putReply` / `postReply` cevabı seçer. */
async function mockInventoryForm(page, { record, putReply = null, postReply = null }) {
  const sink = { puts: [], posts: [] }
  await mockApi(page, { perms: PERMS })
  await mockCertApi(page)
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const req = route.request()
    const p = new URL(req.url()).pathname
    const m = req.method()
    if (p === '/api/admin/teams' && m === 'GET') return route.fulfill(json({ success: true, data: TEAMS }))
    if (p === '/api/noc/groups/options') return route.fulfill(json({ success: true, data: { groups: [], disabled_types: [], has_active_group: false } }))
    if (p === '/api/monitoring/groups') return route.fulfill(json({ success: true, data: [{ id: 3, name: 'Kurumsal Web' }] }))
    if (p === '/api/monitoring/tags') return route.fulfill(json({ success: true, data: [] }))
    if (p === '/api/admin/platforms') return route.fulfill(json({ success: true, data: [{ code: 'IIS', name: 'IIS' }] }))
    if (p === '/api/admin/inventory/by-domain') return route.fulfill(json({ success: true, data: record() }))
    if (p === '/api/admin/inventory' && m === 'POST') {
      const body = JSON.parse(req.postData() || '{}')
      sink.posts.push(body)
      const reply = postReply ? postReply(body, sink.posts.length) : null
      if (reply) return route.fulfill(reply)
      return route.fulfill(json({ success: true, data: { id: 901, ...body } }))
    }
    if (/^\/api\/admin\/inventory\/\d+$/.test(p) && m === 'PUT') {
      const body = JSON.parse(req.postData() || '{}')
      sink.puts.push(body)
      const reply = putReply ? putReply(body, sink.puts.length) : null
      if (reply) return route.fulfill(reply)
      return route.fulfill(json({ success: true, data: { ...record(), ...body } }))
    }
    if (/\/health\/refresh$/.test(p)) return route.fulfill(json({ success: true, data: {} }))
    return route.fallback()
  })
  return sink
}

async function openEdit(page, vp) {
  await page.goto('/?tab=dashboard')
  const card = page.locator(`[data-slot="cert-grid"] [data-slot="card"][data-domain="${DOMAIN}"]`)
  await card.waitFor({ timeout: 60_000 })
  await card.scrollIntoViewIfNeeded()
  const direct = card.getByRole('button', { name: `${DOMAIN} — Edit`, exact: true })
  if (await direct.isVisible()) await direct.click()
  else {
    await card.getByRole('button', { name: `${DOMAIN} — More actions` }).click()
    await page.getByRole('menuitem', { name: 'Edit', exact: true }).click()
  }
  const dlg = page.getByRole('dialog', { name: /Edit Domain/ })
  await expect(dlg).toBeVisible({ timeout: 15_000 })
  // Pencere önce kayıt okunurken açılır, kayıt gelince FORM penceresi yerine geçer (yeniden bağlanır): ölçüm form çizilince
  await expect(dlg.locator('[data-slot="inv-form-fp"]')).toBeAttached({ timeout: 15_000 })
  await expect.poll(async () => (await dlg.boundingBox()) != null, { timeout: 10_000 }).toBe(true)
  await expectInViewport(dlg, vp.width, 'düzenleme penceresi')
  return dlg
}

const baseRecord = () => ({ ...INVENTORY.find((r) => r.domain === DOMAIN) })
const NEW_DOMAIN = 'yeni-odeme-servisi.example.com'

/** SearchableSelect (shadcn combobox): tetiğe bas, seçeneği seç. */
async function pick(dlg, label, option) {
  await dlg.getByRole('combobox', { name: label }).click()
  await dlg.page().getByRole('option', { name: option }).click()
}

/** Genel Bakış "Domain Ekle" → ekleme formu; zorunlu alanlar (alan adı, takım, grup, etiket) doldurulmuş. */
async function openCreate(page, vp) {
  await page.goto('/?tab=dashboard')
  const add = page.locator('[data-slot="dash-add-domain"]')
  await add.waitFor({ timeout: 60_000 })
  await add.click()
  const dlg = page.getByRole('dialog', { name: /Add Domain/ })
  await expect(dlg).toBeVisible({ timeout: 15_000 })
  await expectInViewport(dlg, vp.width, 'ekleme penceresi')
  await dlg.getByRole('textbox', { name: /^Domain/ }).fill(NEW_DOMAIN)
  await pick(dlg, /^Team/, 'Takım A')
  await pick(dlg, /^Group/, 'Kurumsal Web')
  const tags = dlg.getByPlaceholder('type and press Enter to add…')
  await tags.scrollIntoViewIfNeeded()
  await tags.fill('prod')
  await tags.press('Enter')
  return dlg
}

for (const vp of GATE_VIEWPORTS) {
  test.describe(`H paketi formlar — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('envanter: beklenen parmak izi — geçersiz alan altında + gönderilmez; iki noktalı biçim normalize gider (bayraksız); UG saklı + bayrak', async ({ page }) => {
      test.setTimeout(150_000)
      const sink = await mockInventoryForm(page, { record: baseRecord })
      const dlg = await openEdit(page, vp)
      const fp = dlg.locator('[data-slot="inv-form-fp"]')
      await fp.scrollIntoViewIfNeeded()
      await expect(fp).toHaveValue('')
      await expect(dlg.locator('[data-slot="inv-form-fp-clear"]')).toHaveCount(0)

      // Geçersiz: ayrılınca alanın altında, kutu aria-invalid; Kaydet istek göndermez
      await fp.fill('abc-123')
      await fp.blur()
      const field = dlg.locator('[data-field="expected_fingerprint"]')
      await expect(field).toHaveAttribute('data-invalid', 'true')
      await expect(field).toContainText(/SHA-256: 64/)
      await expect(fp).toHaveAttribute('aria-invalid', 'true')
      await expectLayout(page, '[role="dialog"]', { touch: vp.touch, label: `parmak izi hatası @${vp.name}` })
      await dlg.getByRole('button', { name: 'Save', exact: true }).click()
      await page.waitForTimeout(400)
      expect(sink.puts, 'geçersiz parmak iziyle istek gitmez').toHaveLength(0)

      // Geçerli (iki noktalı, küçük harf) → hata kalkar; gövdede normalize büyük harf, DEĞİŞTİĞİ için bayrak YOK;
      // UG takımı formda değişmez → saklı değer (null) + ug_team_id_unchanged
      await fp.fill(FP_COLON)
      await expect(field).not.toHaveAttribute('data-invalid', 'true')
      await expect(dlg.locator('[data-slot="inv-form-fp-clear"]')).toBeVisible()
      await dlg.getByRole('button', { name: 'Save', exact: true }).click()
      await expect.poll(() => sink.puts.length, { timeout: 15_000 }).toBe(1)
      expect(sink.puts[0].expected_fingerprint).toBe('CD'.repeat(32))
      expect('expected_fingerprint_unchanged' in sink.puts[0], 'değişen alan bayraksız').toBe(false)
      expect(sink.puts[0]).toHaveProperty('ug_team_id', null)
      expect(sink.puts[0]).toHaveProperty('ug_team_id_unchanged', true)
      await expect(dlg).toHaveCount(0, { timeout: 15_000 })
    })

    test('envanter: kayıtlı parmak izi + UG — UG salt okunur satır; değişmeyen parmak izi saklı değer + bayrakla; Temizle → null (bayraksız)', async ({ page }) => {
      test.setTimeout(150_000)
      const rec = () => ({ ...baseRecord(), ug_team_id: 2, ug_team_name: LONG_UG, expected_fingerprint: FP_HEX })
      const sink = await mockInventoryForm(page, { record: rec })
      let dlg = await openEdit(page, vp)

      const ug = dlg.locator('[data-slot="inv-form-ug"]')
      await ug.scrollIntoViewIfNeeded()
      await expect(ug).toContainText(LONG_UG)
      await expect(ug).toContainText(/CSV/)
      await expectLayout(page, '[role="dialog"]', { touch: vp.touch, label: `UG satırı + parmak izi @${vp.name}` })

      const fp = dlg.locator('[data-slot="inv-form-fp"]')
      await expect(fp).toHaveValue(FP_HEX)
      // Başka bir alan değişir, parmak izi değişmez → saklı HAM değer + bayrak (yeni sunucu değeri yok sayar; sürüm geçişinde
      // eski pod aynısını yazar — anahtar yokken siliyordu); UG de saklı değer + bayrak
      await dlg.getByRole('textbox', { name: 'Description', exact: true }).fill('Yenileme NetScaler üzerinde')
      await dlg.getByRole('button', { name: 'Save', exact: true }).click()
      await expect.poll(() => sink.puts.length, { timeout: 15_000 }).toBe(1)
      expect(sink.puts[0]).toHaveProperty('expected_fingerprint', FP_HEX)
      expect(sink.puts[0]).toHaveProperty('expected_fingerprint_unchanged', true)
      expect(sink.puts[0]).toHaveProperty('ug_team_id', 2)
      expect(sink.puts[0]).toHaveProperty('ug_team_id_unchanged', true)
      await expect(dlg).toHaveCount(0, { timeout: 15_000 })

      // Yeniden aç → Temizle → null gider
      dlg = await openEdit(page, vp)
      const clear = dlg.locator('[data-slot="inv-form-fp-clear"]')
      await clear.scrollIntoViewIfNeeded()
      await clear.click()
      await expect(dlg.locator('[data-slot="inv-form-fp"]')).toHaveValue('')
      await dlg.getByRole('button', { name: 'Save', exact: true }).click()
      await expect.poll(() => sink.puts.length, { timeout: 15_000 }).toBe(2)
      expect(sink.puts[1]).toHaveProperty('expected_fingerprint', null)
      expect('expected_fingerprint_unchanged' in sink.puts[1], 'temizlenen alan bayraksız').toBe(false)
    })

    test('envanter EKLEME: parmak izi biçimi normalize gider, bayrak yok; sunucunun 400 alan hatası alanın altında', async ({ page }) => {
      test.setTimeout(150_000)
      const sink = await mockInventoryForm(page, {
        record: baseRecord,
        postReply: (_body, n) => (n === 1 ? null : json({ success: false, code: 'VALIDATION_FAILED', field: 'expected_fingerprint',
          error: 'Sunucu: beklenen parmak izi 64 onaltılık karakter olmalı (SHA-256).' }, 400)),
      })
      let dlg = await openCreate(page, vp)
      const fp = dlg.locator('[data-slot="inv-form-fp"]')
      await fp.scrollIntoViewIfNeeded()
      // Yardım metni: kontrol adın arkasındaki TEK düğümü görür, çok düğümde her biri ayrıca doğrulanır (inceleme bulgusu 10)
      await expect(dlg.locator('[data-field="expected_fingerprint"]')).toContainText(/ONE node/)
      await expect(dlg.locator('[data-field="expected_fingerprint"]')).toContainText(/verify each node separately/)
      await fp.fill(FP_COLON)
      await expectLayout(page, '[role="dialog"]', { touch: vp.touch, label: `ekleme formu parmak izi @${vp.name}` })
      await dlg.getByRole('button', { name: 'Save', exact: true }).click()
      await expect.poll(() => sink.posts.length, { timeout: 15_000 }).toBe(1)
      expect(sink.posts[0]).toMatchObject({ domain: NEW_DOMAIN, team_id: 1, expected_fingerprint: 'CD'.repeat(32) })
      expect('expected_fingerprint_unchanged' in sink.posts[0], 'oluşturmada bayrak yok').toBe(false)
      expect('ug_team_id' in sink.posts[0], 'oluşturmada UG anahtarı yok').toBe(false)
      expect('ug_team_id_unchanged' in sink.posts[0]).toBe(false)
      await expect(dlg).toHaveCount(0, { timeout: 15_000 })

      // İkinci ekleme: sunucu 400 expected_fingerprint → ALANIN ALTINDA, form açık, odak alanda
      dlg = await openCreate(page, vp)
      const fp2 = dlg.locator('[data-slot="inv-form-fp"]')
      await fp2.scrollIntoViewIfNeeded()
      await fp2.fill(FP_HEX)
      await dlg.getByRole('button', { name: 'Save', exact: true }).click()
      await expect.poll(() => sink.posts.length, { timeout: 15_000 }).toBe(2)
      const field = dlg.locator('[data-field="expected_fingerprint"]')
      await expect(field).toHaveAttribute('data-invalid', 'true')
      await expect(field).toContainText('Sunucu: beklenen parmak izi 64 onaltılık')
      await expect(fp2).toHaveAttribute('aria-invalid', 'true')
      await expect(dlg).toBeVisible()
      await expectLayout(page, '[role="dialog"]', { touch: vp.touch, label: `ekleme sunucu alan hatası @${vp.name}` })
    })

    test('envanter: sunucunun 400 expected_fingerprint hatası alanın altında, form açık kalır', async ({ page }) => {
      test.setTimeout(150_000)
      const sink = await mockInventoryForm(page, {
        record: baseRecord,
        putReply: () => json({ success: false, code: 'VALIDATION_FAILED', field: 'expected_fingerprint',
          error: 'Sunucu: beklenen parmak izi 64 onaltılık karakter olmalı (SHA-256).' }, 400),
      })
      const dlg = await openEdit(page, vp)
      const fp = dlg.locator('[data-slot="inv-form-fp"]')
      await fp.scrollIntoViewIfNeeded()
      await fp.fill(FP_HEX)
      await dlg.getByRole('button', { name: 'Save', exact: true }).click()
      await expect.poll(() => sink.puts.length, { timeout: 15_000 }).toBe(1)
      const field = dlg.locator('[data-field="expected_fingerprint"]')
      await expect(field).toHaveAttribute('data-invalid', 'true')
      await expect(field).toContainText('Sunucu: beklenen parmak izi 64 onaltılık')
      await expect(dlg).toBeVisible()
      await expectLayout(page, '[role="dialog"]', { touch: vp.touch, label: `sunucu alan hatası @${vp.name}` })
    })

    test('yenileme planı: sunucunun 400 date / note hatası alanın altında, alan odaklanır, alan değişince kalkar', async ({ page }) => {
      test.setTimeout(150_000)
      const PLAN_DOMAIN = 'auth.example.com'
      const posts = []
      await mockApi(page, { role: 'ADMIN', globalAdmin: true, perms: { 'inventory.list': { view: true }, 'inventory.crud': { view: true, edit: true } } })
      await page.route((u) => {
        const p = new URL(u).pathname
        return p === '/api/renewal-advice' || p.startsWith('/api/forecast/')
      }, async (route) => {
        const req = route.request()
        const p = new URL(req.url()).pathname
        if (p === '/api/renewal-advice') return route.fulfill(json({ success: true, data: RENEWAL_ADVICE, count: RENEWAL_ADVICE.length, timestamp: new Date().toISOString() }))
        if (/\/plan$/.test(p) && req.method() === 'POST') {
          const body = JSON.parse(req.postData() || '{}')
          posts.push(body)
          if (posts.length === 1) return route.fulfill(json({ success: false, code: 'VALIDATION_FAILED', field: 'date', error: 'Tarih gerçek bir gün olmalı (yyyy-MM-dd).' }, 400))
          if (posts.length === 2) return route.fulfill(json({ success: false, code: 'VALIDATION_FAILED', field: 'note', error: 'Not en çok 500 karakter olabilir.' }, 400))
          return route.fulfill(json({ success: true, data: { renewal_planned_at: body.date, renewal_planned_note: body.note || '' } }))
        }
        return route.fallback()
      })
      await page.goto('/?tab=renewal')
      const card = page.locator('[data-slot="renewal-card"]')
        .filter({ has: page.locator('[data-slot="rn-domain"]', { hasText: /^auth\.example\.com$/ }) })
      await expect(card).toHaveCount(1, { timeout: 60_000 })
      await card.getByRole('button', { name: `${PLAN_DOMAIN} — Plan renewal`, exact: true }).click()
      const dlg = page.getByRole('dialog', { name: `Renewal plan — ${PLAN_DOMAIN}` })
      await expect(dlg).toBeVisible()
      await dlg.locator('[data-slot="plan-quick"] [data-pick="week"]').click()
      const save = dlg.getByRole('button', { name: 'Save plan', exact: true })

      // 1) date hatası: alanın altında, tarih seçicisi aria-describedby ile bağlı ve odakta
      await save.click()
      const dateErr = dlg.locator('[data-slot="plan-date-error"]')
      await expect(dateErr).toHaveText('Tarih gerçek bir gün olmalı (yyyy-MM-dd).')
      await expect(dlg.locator('[data-field="date"]')).toHaveAttribute('data-invalid', 'true')
      await expect.poll(() => page.evaluate(() => !!document.activeElement?.closest('[data-field="date"]'))).toBe(true)
      await expectInViewport(dlg, vp.width, 'plan penceresi')
      await expectLayout(page, '[role="dialog"]', { touch: vp.touch, label: `plan tarih hatası @${vp.name}` })
      // Başka hızlı seçim → hata kalkar
      await dlg.locator('[data-slot="plan-quick"] [data-pick="twoWeeks"]').click()
      await expect(dateErr).toHaveCount(0)

      // 2) note hatası: not alanının altında, kutu aria-invalid ve odakta; yazınca kalkar
      await dlg.getByRole('textbox', { name: /^Note/ }).fill('Değişiklik kaydı açılacak')
      await save.click()
      const noteField = dlg.locator('[data-field="note"]')
      await expect(noteField).toHaveAttribute('data-invalid', 'true')
      await expect(noteField).toContainText('Not en çok 500 karakter olabilir.')
      const note = dlg.getByRole('textbox', { name: /^Note/ })
      await expect(note).toHaveAttribute('aria-invalid', 'true')
      await expect(note).toBeFocused()
      await expectLayout(page, '[role="dialog"]', { touch: vp.touch, label: `plan not hatası @${vp.name}` })
      await note.fill('Değişiklik kaydı açıldı')
      await expect(noteField).not.toHaveAttribute('data-invalid', 'true')

      // 3) başarı
      await save.click()
      await expect.poll(() => posts.length, { timeout: 15_000 }).toBe(3)
      await expect(page.getByText(`Plan saved for ${PLAN_DOMAIN}`)).toBeVisible()
    })

    test('yenileme planı: kaldırma `changed: false` → "arada değişmiş" bilgisi, liste yeniden okunur, GÜNCEL plan rozeti kalır', async ({ page }) => {
      test.setTimeout(120_000)
      const PLAN_DOMAIN = 'auth.example.com'
      const NEW_NOTE = 'Başka bir yönetici planı taşıdı — değişiklik kaydı yeniden açıldı'
      let plan = { renewal_planned_at: '2027-01-15', renewal_planned_note: 'İlk plan' }
      let adviceReads = 0
      const deletes = []
      await mockApi(page, { role: 'ADMIN', globalAdmin: true, perms: { 'inventory.list': { view: true }, 'inventory.crud': { view: true, edit: true } } })
      await page.route((u) => {
        const p = new URL(u).pathname
        return p === '/api/renewal-advice' || p.startsWith('/api/forecast/')
      }, async (route) => {
        const req = route.request()
        const p = new URL(req.url()).pathname
        if (p === '/api/renewal-advice') {
          adviceReads++
          const data = RENEWAL_ADVICE.map((r) => (r.domain === PLAN_DOMAIN ? { ...r, ...plan } : r))
          return route.fulfill(json({ success: true, data, count: data.length, timestamp: new Date().toISOString() }))
        }
        if (/\/plan$/.test(p) && req.method() === 'DELETE') {
          deletes.push(p)
          // Başka bir yönetici planı arada taşıdı: sunucu hiçbir şey yazmaz, GÜNCEL durumu döndürür
          plan = { renewal_planned_at: '2027-01-22', renewal_planned_note: NEW_NOTE }
          return route.fulfill(json({ success: true, data: { domain: PLAN_DOMAIN, ...plan, renewal_planned_by: 'Başka Yönetici', changed: false } }))
        }
        return route.fallback()
      })
      await page.goto('/?tab=renewal')
      const card = page.locator('[data-slot="renewal-card"]')
        .filter({ has: page.locator('[data-slot="rn-domain"]', { hasText: /^auth\.example\.com$/ }) })
      await expect(card).toHaveCount(1, { timeout: 60_000 })
      await expect(card.locator('[data-slot="rn-planned"]')).toHaveAttribute('title', 'İlk plan')
      const readsBefore = adviceReads
      await card.getByRole('button', { name: `${PLAN_DOMAIN} — Plan renewal`, exact: true }).click()
      const dlg = page.getByRole('dialog', { name: `Renewal plan — ${PLAN_DOMAIN}` })
      await expect(dlg).toBeVisible()
      await dlg.getByRole('button', { name: 'Remove plan', exact: true }).click()
      const confirm = page.getByRole('dialog', { name: 'Remove this plan?' })
      await expect(confirm).toBeVisible()
      await expectLayout(page, '[role="dialog"]', { touch: vp.touch, label: `plan kaldırma onayı @${vp.name}` })
      await confirm.getByRole('button', { name: 'Remove plan', exact: true }).click()
      await expect.poll(() => deletes.length, { timeout: 15_000 }).toBe(1)
      // "kaldırıldı" DEĞİL — arada değişmiş bilgisi
      await expect(page.getByText(/the plan changed in the meantime; the current plan is shown/)).toBeVisible()
      await expect(page.getByText(`Plan removed for ${PLAN_DOMAIN}`)).toHaveCount(0)
      await expect(dlg).toHaveCount(0)
      // Liste yeniden okundu, rozet GÜNCEL planı gösterir (silinmiş gibi kaybolmaz)
      await expect.poll(() => adviceReads, { timeout: 15_000 }).toBeGreaterThan(readsBefore)
      await expect(card.locator('[data-slot="rn-planned"]')).toHaveAttribute('title', NEW_NOTE)
    })

    for (const serverList of [false, true]) {
      test(`yönetici özeti ay seçicisi: ${serverList ? 'sunucu listesi (25 ay)' : 'sunucu listesi yok → bu ay + son 24 ay'}`, async ({ page }) => {
        test.setTimeout(90_000)
        await mockApi(page)
        const months = Array.from({ length: 25 }, (_, i) => {
          const d = new Date(Date.UTC(2026, 10 - i, 1))
          return { month: `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`, current: i === 0 }
        })
        await page.route((u) => new URL(u).pathname === '/api/executive-summary',
          (r) => r.fulfill(json({ ...response(), months: serverList ? months : [] })))
        await page.route((u) => new URL(u).pathname === '/api/executive-summary/settings', (r) => r.fulfill(json({ success: true, data: SETTINGS })))
        await page.route((u) => new URL(u).pathname === '/api/executive-summary/teams', (r) => r.fulfill(json({ success: true, data: EXEC_TEAMS })))
        await page.route((u) => /^\/api\/executive-summary\/teams\/\d+$/.test(new URL(u).pathname), (r) => r.fulfill(json({ success: true, data: TEAM_DETAIL })))
        await page.goto('/?tab=executive')
        const sel = page.locator('[data-slot="ex-month"]')
        await expect(sel).toBeVisible({ timeout: 30_000 })
        await expect(sel.locator('option')).toHaveCount(25)
        if (serverList) await expect(sel.locator('option').last()).toHaveAttribute('value', '2024-11')
        await expectInViewport(sel, vp.width, 'ay seçici')
        if (vp.touch) expect((await sel.boundingBox()).height, 'ay seçici dokunma yüksekliği').toBeGreaterThanOrEqual(39.5)
      })
    }
  })
}
