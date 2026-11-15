// Kayıtta 7/24 onayı — dokuz izleme formunun DIŞINDAKİ kayıt yolları (2026-11-15, prod kapısı e2e paketi; model:
// noc-save-confirm.spec.js). CLAUDE.md "24/7 confirmation on save": onay envanter sertifika formunda (tür SSL), veritabanı
// sorgusu hızlı ekleme sihirbazında ve iki toplu oluşturmada (sihirbazın özet adımı + uzun formun "Birden çok bağlantı
// için…" penceresi) da sorulur. Telefon (390, dokunmatik), tablet (768, dokunmatik), dizüstü (1280):
//   1) envanter SSL: Kaydet → pencere (data-type=SSL); "Vazgeç" istek göndermez, form açık; "Evet" → PUT noc_notify:true;
//      "Hayır" + "bir daha sorma" → noc_notify:false, `sm.noc.noAsk` = SSL:<id>, sonraki kayıt sorulmadan gider;
//   2) sihirbaz tek kayıt: "Evet" → POST nocNotify:true;
//   3) sihirbaz toplu (2 bağlantı): "Hayır" + "bir daha sorma" → 2 POST nocNotify:false, iki kimlik de saklanır;
//   4) uzun formdan toplu oluşturma (kopya): onay pencereyi açmadan ÖNCE sorulur; "Evet" → her POST nocNotify:true.
// Her pencerede yerleşim: sayfa taşmaz, öğe ekran dışına çıkmaz, metin kırpılmaz, dokunmatikte hedefler ≥ 40 px. API taklit.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'
import { VIEWPORTS, json, pathIs, checkLayout, expectInViewport } from './support/layoutAudit.js'

const LONG_GROUP = 'Kurumsal 7/24 Operasyon Merkezi Nöbet Listesi (Ağ, Uygulama ve Veritabanı Ekipleri)'
const NOC_DIALOG = '[role="dialog"]:has([data-slot="noc-save-confirm"])'
const LONG_CONN = 'ODEME_RAPORLAMA_SALT_OKUNUR — Kurumsal Ödeme Sistemleri Raporlama Kümesi (İkincil Düğüm, Okuma Kopyası)'
const CONNS = [
  { id: 40, team_id: 1, name: 'Sipariş DB (PostgreSQL üretim)', db_type: 'POSTGRESQL', host: 'db.example.com', port: 5432, database_name: 'orders',
    username: 'ro', password_set: true, ssl_mode: 'require', connect_timeout_ms: 10000, can_edit: true, health: { state: 'ok', last_test_ok: true }, used_by: [], tables_used: [] },
  { id: 41, team_id: 1, name: LONG_CONN, db_type: 'MSSQL', host: 'odeme-rapor-mssql-scan-01.dc1.example.com.tr', port: 1433,
    database_name: 'OdemeRapor', mssql_instance: 'RPT', username: 'sm_reader', password_set: true, mssql_encrypt: true, connect_timeout_ms: 10000,
    can_edit: true, health: { state: 'ok', last_test_ok: true }, used_by: [], tables_used: [] },
  { id: 42, team_id: 1, name: 'Sipariş DB yedek okuma kopyası (PostgreSQL ikincil bölge)', db_type: 'POSTGRESQL', host: 'db-ro.example.com', port: 5432,
    database_name: 'orders', username: 'ro', password_set: true, ssl_mode: 'require', connect_timeout_ms: 10000, can_edit: true,
    health: { state: 'untested' }, used_by: [], tables_used: [] },
  { id: 43, team_id: 1, name: 'Mutabakat SQL Server (ikincil)', db_type: 'MSSQL', host: 'mssql02.example.com', port: 1433, database_name: 'Mutabakat',
    username: 'sm_reader', password_set: true, mssql_encrypt: true, connect_timeout_ms: 10000, can_edit: true, health: { state: 'untested' }, used_by: [], tables_used: [] },
]

/** mockApi + 7/24 seçenekleri (aktif grup var → sorulur) + yazma uçları kaydedilir. */
async function routes(page) {
  // USER + envanter yazma izni: kendi takımının kaydını düzenler (InventoryManager.canEditRow)
  await mockApi(page, { role: 'USER', globalAdmin: false, perms: { 'inventory.crud': { view: true, edit: true } } })
  await page.route(pathIs('/api/noc/groups/options'), json({ success: true, data: {
    groups: [{ id: 1, name: LONG_GROUP, is_default: true, active: true }, { id: 2, name: 'Hafta Sonu', is_default: false, active: true }],
    disabled_types: [], has_active_group: true, min_level: 'CRITICAL',
  } }))
  const calls = { inv: [], creates: [] }
  // Envanter düzenleme (PUT, SNAKE_CASE gövde) + kayıt sonrası otomatik ilk kontrol
  await page.route((u) => /^\/api\/admin\/inventory\/\d+$/.test(new URL(u).pathname), (route) => {
    if (route.request().method() !== 'PUT') return route.fallback()
    const body = JSON.parse(route.request().postData() || '{}')
    calls.inv.push(body)
    const id = Number(new URL(route.request().url()).pathname.split('/').pop())
    return json({ success: true, data: { id, ...body } })(route)
  })
  await page.route((u) => /\/health\/refresh$/.test(new URL(u).pathname), json({ success: true, data: {} }))
  // Veritabanı sorgusu: bağlantılar, SQL denetimi, deneme, şablonlar, gruplar, oluşturma
  await page.route(pathIs('/api/monitoring/dbquery/connections'), json({ success: true, data: CONNS }))
  await page.route(pathIs('/api/monitoring/dbquery/sql-check'), json({ success: true, data: { ok: true, violations: [], tables: ['sys.dm_exec_requests'] } }))
  await page.route(pathIs('/api/monitoring/dbquery/test'), json({ success: true, data: { status: 'OK', duration_ms: 84, row_count: 1, row_count_capped: false,
    columns: [{ name: 'blocked_sessions', type: 'int' }], rule_results: [], warnings: [] } }))
  await page.route(pathIs('/api/monitoring/dbquery/schedule-preview'), json({ success: true, data: { runs: [], interval_seconds: 300 } }))
  await page.route(pathIs('/api/monitoring/dbquery/templates'), json({ success: true, data: [] }))
  await page.route(pathIs('/api/monitoring/groups'), json({ success: true, data: [{ name: 'Kurumsal Ödeme Sistemleri — Raporlama' }, { name: 'Ödeme Sistemleri' }] }))
  await page.route(pathIs('/api/monitoring/dbquery'), (route) => {
    if (route.request().method() !== 'POST') return route.fallback()
    const body = JSON.parse(route.request().postData() || '{}')
    calls.creates.push(body)
    return json({ success: true, data: { id: 900 + calls.creates.length, ...body } })(route)
  })
  return calls
}

const noAsk = (page) => page.evaluate(() => JSON.parse(localStorage.getItem('sm.noc.noAsk') || '[]'))

/** Onay penceresi: görünür, ekrana sığar, kırpılmaz, dokunmatikte hedefler ≥ 40 px; türü ve grup adı doğru. */
async function expectConfirm(page, vp, type, label) {
  const dlg = page.locator(NOC_DIALOG)
  await expect(dlg).toBeVisible()
  await expect(dlg.locator('[data-slot="noc-save-confirm"]')).toHaveAttribute('data-type', type)
  await expect(dlg.locator('[data-slot="noc-confirm-groups"]')).toContainText(LONG_GROUP)
  await page.waitForTimeout(300)   // açılış saydamlık geçişi
  await expectInViewport(dlg, vp, `${label} @${vp.name}`)
  await checkLayout(page, `${label} @${vp.name}`, NOC_DIALOG)
  return dlg
}

/** Envanter listesinden bir kaydın Düzenle penceresi (geniş kapta tablo menüsü, dar kapta kart düğmesi). */
async function openInventoryEdit(page, domain) {
  const pageRoot = page.locator('[data-slot="inventory-page"]')
  await expect(pageRoot).toBeVisible({ timeout: 20_000 })
  await expect(page.getByText(domain, { exact: true }).first()).toBeVisible({ timeout: 20_000 })
  if ((await pageRoot.getAttribute('data-layout')) === 'cards') {
    await page.getByRole('button', { name: `${domain} — Edit`, exact: true }).click()
  } else {
    await page.getByRole('button', { name: `${domain} — Actions`, exact: true }).click()
    await page.getByRole('menuitem', { name: /^(Edit|Düzenle)$/ }).click()
  }
  const form = page.locator('[role="dialog"]:has([data-slot="inv-form-actions"])')
  await expect(form).toBeVisible()
  return form
}

/** Sihirbazı bağlantı → hızlı başlangıç → deneme → kural → zamanlama → özet adımına getirir (grup + etiket dolu). */
async function wizardToSummary(page) {
  await page.goto('/?tab=dbquery')
  await expect(page.locator('.upt-grid > [data-slot="card"]').first()).toBeVisible({ timeout: 20_000 })
  await page.locator('[data-slot="dbq-quick-add"]').click()
  const wiz = page.locator('[data-slot="dbq-wiz"]')
  await expect(wiz).toBeVisible()
  await wiz.getByRole('combobox', { name: /^(Database connection|Veritabanı bağlantısı)/ }).click()
  await page.getByRole('option', { name: new RegExp(LONG_CONN.slice(0, 20)) }).click()
  await page.locator('[data-slot="dbq-wiz-next"]').click()
  await expect(wiz).toHaveAttribute('data-step', 'query')
  await wiz.locator('[data-slot="dbq-wiz-qs-item"][data-key="blocked_sessions"]').click()
  await expect(wiz.locator('[data-slot="dbq-sql-guard"]')).toHaveAttribute('data-status', 'ok', { timeout: 5000 })
  await page.locator('[data-slot="dbq-wiz-next"]').click()
  await expect(wiz).toHaveAttribute('data-step', 'test')
  await wiz.locator('[data-slot="dbq-wiz-test"]').click()
  await expect(wiz.locator('[data-slot="dbq-test"]')).toHaveAttribute('data-status', 'OK')
  for (const step of ['rules', 'schedule', 'summary']) {
    await page.locator('[data-slot="dbq-wiz-next"]').click()
    await expect(wiz).toHaveAttribute('data-step', step)
  }
  await wiz.locator('[data-field="group_name"] [role="combobox"]').click()
  await page.getByRole('option', { name: 'Kurumsal Ödeme Sistemleri — Raporlama' }).click()
  const tag = wiz.locator('[data-field="tags"] input').first()
  await tag.fill('dba')
  await tag.press('Enter')
  return wiz
}

for (const vp of VIEWPORTS) {
  test.describe(`Kayıtta 7/24 onayı (diğer yollar) — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('envanter SSL formu: Vazgeç istek göndermez; Evet → noc_notify:true', async ({ page }) => {
      test.setTimeout(120_000)
      const calls = await routes(page)
      await page.goto('/?tab=domains')
      const form = await openInventoryEdit(page, 'www.example.com')
      const save = () => form.locator('[data-slot="inv-form-actions"]').getByRole('button', { name: /^(Save|Kaydet)$/ }).click()
      await save()
      const dlg = await expectConfirm(page, vp, 'SSL', 'envanter onayı')
      expect(calls.inv).toHaveLength(0)
      await dlg.locator('[data-slot="noc-confirm-cancel"]').click()
      await expect(page.locator(NOC_DIALOG)).toHaveCount(0)
      await expect(form).toBeVisible()
      expect(calls.inv).toHaveLength(0)

      await save()
      await page.locator(NOC_DIALOG).locator('[data-slot="noc-confirm-yes"]').click()
      await expect.poll(() => calls.inv.length).toBe(1)
      expect(calls.inv[0]).toMatchObject({ domain: 'www.example.com', noc_notify: true })
      // Evet "bir daha sorma" yazmaz
      expect(await noAsk(page)).toEqual([])
      await expect(page.locator(NOC_DIALOG)).toHaveCount(0)
    })

    test('envanter SSL formu: Hayır + bir daha sorma → noc_notify:false, sonraki kayıtta sorulmaz', async ({ page }) => {
      test.setTimeout(120_000)
      const calls = await routes(page)
      await page.goto('/?tab=domains')
      let form = await openInventoryEdit(page, 'www.example.com')
      await form.locator('[data-slot="inv-form-actions"]').getByRole('button', { name: /^(Save|Kaydet)$/ }).click()
      const dlg = await expectConfirm(page, vp, 'SSL', 'envanter onayı (Hayır)')
      await dlg.locator('[data-slot="noc-confirm-noask-row"] label').first().click()
      await expect(dlg.locator('[data-slot="noc-confirm-noask"]')).toHaveAttribute('data-state', 'checked')
      await dlg.locator('[data-slot="noc-confirm-no"]').click()
      await expect.poll(() => calls.inv.length).toBe(1)
      expect(calls.inv[0].noc_notify).toBe(false)
      await expect.poll(() => noAsk(page)).toEqual(['SSL:500'])
      await expect(form).toHaveCount(0, { timeout: 15_000 })

      form = await openInventoryEdit(page, 'www.example.com')
      await form.locator('[data-slot="inv-form-actions"]').getByRole('button', { name: /^(Save|Kaydet)$/ }).click()
      await expect.poll(() => calls.inv.length).toBe(2)
      expect(calls.inv[1].noc_notify).toBe(false)
      await expect(page.locator(NOC_DIALOG)).toHaveCount(0)
    })

    test('hızlı ekleme sihirbazı: Vazgeç sihirbazda kalır; Evet → POST nocNotify:true', async ({ page }) => {
      test.setTimeout(180_000)
      const calls = await routes(page)
      const wiz = await wizardToSummary(page)
      await page.locator('[data-slot="dbq-wiz-save"]').click()
      const dlg = await expectConfirm(page, vp, 'DBQUERY', 'sihirbaz onayı')
      await dlg.locator('[data-slot="noc-confirm-cancel"]').click()
      await expect(page.locator(NOC_DIALOG)).toHaveCount(0)
      await expect(wiz).toHaveAttribute('data-step', 'summary')
      expect(calls.creates).toHaveLength(0)

      await page.locator('[data-slot="dbq-wiz-save"]').click()
      await page.locator(NOC_DIALOG).locator('[data-slot="noc-confirm-yes"]').click()
      await expect.poll(() => calls.creates.length).toBe(1)
      expect(calls.creates[0]).toMatchObject({ profile_id: 41, nocNotify: true, groupName: 'Kurumsal Ödeme Sistemleri — Raporlama', tags: 'dba' })
      await expect(wiz).toHaveCount(0)
      expect(await noAsk(page)).toEqual([])
    })

    test('sihirbaz toplu oluşturma (2 bağlantı): Hayır + bir daha sorma → 2 POST nocNotify:false, iki kimlik saklanır', async ({ page }) => {
      test.setTimeout(180_000)
      const calls = await routes(page)
      const wiz = await wizardToSummary(page)
      await wiz.locator('#dbq-wiz-bulk').click()
      const bulk = wiz.locator('[data-slot="dbq-bulk"]')
      await expect(bulk.locator('[data-slot="dbq-bulk-connection"]')).toHaveCount(CONNS.length)
      await bulk.locator('[data-slot="dbq-bulk-connection"][data-id="43"]').getByRole('checkbox').click()
      await expect(bulk.locator('[data-slot="dbq-bulk-plan-row"]')).toHaveCount(2)
      await expect(page.locator('[data-slot="dbq-wiz-save"]')).toBeEnabled()
      await page.locator('[data-slot="dbq-wiz-save"]').click()
      const dlg = await expectConfirm(page, vp, 'DBQUERY', 'sihirbaz toplu onayı')
      expect(calls.creates).toHaveLength(0)
      await dlg.locator('[data-slot="noc-confirm-noask-row"] label').first().click()
      await dlg.locator('[data-slot="noc-confirm-no"]').click()
      await expect.poll(() => calls.creates.length).toBe(2)
      expect(calls.creates.map((b) => b.profile_id).sort()).toEqual([41, 43])
      for (const b of calls.creates) expect(b.nocNotify).toBe(false)
      await expect.poll(async () => (await noAsk(page)).sort()).toEqual(['DBQUERY:901', 'DBQUERY:902'])
      await expect(page.locator(NOC_DIALOG)).toHaveCount(0)
    })

    test('uzun formdan toplu oluşturma (kopya): onay pencereden önce sorulur; Evet → her POST nocNotify:true', async ({ page }) => {
      test.setTimeout(180_000)
      const calls = await routes(page)
      await page.goto('/?tab=dbquery')
      await expect(page.locator('.upt-grid > [data-slot="card"]').first()).toBeVisible({ timeout: 20_000 })
      await page.getByRole('button', { name: /^Sipariş kuyruğu bekleyenleri — (Duplicate|Kopyala)$/ }).click()
      const form = page.locator('[role="dialog"]:has([data-slot="dbq-bulk-open"])')
      await expect(form).toBeVisible()
      await form.locator('[data-slot="dbq-bulk-open"]').click()
      const dlg = await expectConfirm(page, vp, 'DBQUERY', 'uzun form toplu onayı')
      // Onay yanıtlanmadan toplu pencere açılmaz
      await expect(page.locator('[data-slot="dbq-bulk"]')).toHaveCount(0)
      await dlg.locator('[data-slot="noc-confirm-yes"]').click()
      const bulk = page.locator('[data-slot="dbq-bulk"]')
      await expect(bulk).toBeVisible()
      await expect(bulk.locator('[data-slot="dbq-bulk-connection"][data-id="40"]').getByRole('checkbox')).toHaveAttribute('data-state', 'checked')
      await bulk.locator('[data-slot="dbq-bulk-connection"][data-id="42"]').getByRole('checkbox').click()
      await expect(bulk.locator('[data-slot="dbq-bulk-plan-row"]')).toHaveCount(2)
      await checkLayout(page, `toplu oluşturma penceresi @${vp.name}`, '[role="dialog"]:has([data-slot="dbq-bulk"])')
      await page.locator('[data-slot="dbq-bulk-run"]').click()
      await expect.poll(() => calls.creates.length).toBe(2)
      expect(calls.creates.map((b) => b.profile_id).sort()).toEqual([40, 42])
      for (const b of calls.creates) expect(b.nocNotify).toBe(true)
      await expect(bulk.locator('[data-slot="dbq-bulk-result"][data-status="ok"]')).toHaveCount(2)
      // Evet "bir daha sorma" yazmaz
      expect(await noAsk(page)).toEqual([])
    })
  })
}
