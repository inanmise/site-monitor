// Sentetik izleme formu + ilk kurulum (2026-10-31, k6r fe1). Üç boyutta (390 telefon + dokunmatik, 768 tablet +
// dokunmatik, 1280 dizüstü) gerçek tarayıcıda: bölümlü düzen (Genel → Senaryo → Zamanlama ve alarm → Bildirimler),
// telefonda şablon seçiciye en çok bir ekran kaydırma, zamanlama bölümü kapalı + özetli, açılınca zamanlama düzenleyicisi
// ve sayısal alanlar sığar; şablon seçilince değişken açıklamaları + "Hedef: host" + __ENV denetim çipleri; düzenlemede
// kaynak değişimi onay ister ve env korunur; Test Çalıştır kayıtlı gizli değerle (keep) gider, sonuç paneli görünür
// alana kayar ve odak alır; "satıra git"; uzun ad / değişken adı sararak tam görünür; sayfa ve pencere gövdesi yatay
// taşmaz; bu özelliğin dokunma hedefleri ≥ 40 px. API tamamen mock'lu (yalnız /api/ yolu). Değerler uydurmadır.
import { test, expect } from '@playwright/test'
import { mockApi, MONITORS } from './support/monitorMocks.js'

const LONG_VAR = 'ODEME_SERVISI_ISTEMCI_GIZLI_ANAHTARI_URETIM_ORTAMI_BOLGE_2_PASSWORD'
const MON = {
  ...MONITORS.scripted[0],
  id: 100, name: 'Ödeme akışı — sepete ekle, adres doğrulama ve 3D Secure ile kart onayı (üretim)', active: true, status: 'FAIL',
  team_id: 1, team_name: 'Takım A', group_name: 'Ödeme', tags: 'prod',
  script: "import http from 'k6/http';\nimport { check } from 'k6';\nexport default function () {\n  const r = http.get(__ENV.BASE_URL)\n  check(r, { ok: (x) => x.status === 200 })\n}\n",
  can_check: true, can_edit: true, can_delete: true,
  env: [
    { name: 'BASE_URL', secret: false, value: 'https://odeme.example.com' },
    { name: LONG_VAR, secret: true, value_set: true },
  ],
  interval_seconds: 300, timeout_seconds: 60, confirm_attempts: 3, recovery_checks: 3,
  error: 'k6 check failed', exit_code: 99, checks_passed: 0, checks_failed: 1, checked_at: '2026-10-31T09:00:00',
}
const TEMPLATES = [
  { id: 201, select_token: '201', name: 'Kurumsal giriş (Keycloak OIDC) ve hesap özeti — uçtan uca kullanıcı yolculuğu',
    name_en: 'Corporate sign-in (Keycloak OIDC) and account summary — end-to-end user journey',
    description: 'Keycloak üzerinden kullanıcı adı ve parolayla oturum açar, hesap özetini çeker ve alanları doğrular. Ayrıntı.',
    description_en: 'Signs in through Keycloak with username and password, fetches the account summary and validates fields. More.',
    category: 'identity', scope: 'team', team_id: 1, team_name: 'Takım A', builtin: false, active: true, current_version: '1.2.0',
    env: [
      { name: 'BASE_URL', secret: false, desc: 'Uygulamanın kök adresi (ör. https://ibank.example.com)', desc_en: 'The app root address (e.g. https://ibank.example.com)' },
      { name: 'USERNAME', secret: false, desc: 'İzleme için açılmış servis hesabı', desc_en: 'The service account opened for monitoring' },
      { name: 'PASSWORD', secret: true, desc: 'Servis hesabının parolası', desc_en: 'The service account password' },
    ] },
]
const TEMPLATE_SCRIPT = "import http from 'k6/http';\nexport default function () {\n  http.post(`${__ENV.BASE_URL}/login`, { u: __ENV.USERNAME, p: __ENV.PASSWORD, extra: __ENV.MISSING_ONE })\n}\n"

async function setup(page) {
  await mockApi(page, { monitors: { ...MONITORS, scripted: [MON] } })
  const tests = []
  await page.route((u) => new URL(u).pathname === '/api/monitoring/scripted/templates', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ success: true, data: { templates: TEMPLATES, writable_team_ids: [1], k6_version: 'v2.3.0' } }) }))
  await page.route((u) => /^\/api\/monitoring\/scripted\/templates\/\d+$/.test(new URL(u).pathname), (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: { ...TEMPLATES[0], script: TEMPLATE_SCRIPT } }) }))
  await page.route((u) => new URL(u).pathname === '/api/monitoring/scripted/schedule-preview', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json',
      body: JSON.stringify({ success: true, data: { valid: true, errors: [], effective_interval_seconds: 300,
        next: ['2026-11-02T06:00:00Z', '2026-11-02T06:05:00Z', '2026-11-02T06:10:00Z', '2026-11-02T06:15:00Z'] } }) }))
  await page.route((u) => new URL(u).pathname === '/api/monitoring/scripted/test', async (route) => {
    tests.push(JSON.parse(route.request().postData() || '{}'))
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: {
      status: 'ERROR', duration_ms: 4210, exit_code: 107, kept_secrets: [LONG_VAR],
      error: 'script: Line 4:3 ReferenceError: x is not defined\nsüreç bütçesi=60s · çıkış=doğrudan · kurumsal CA=verildi',
      failure_reason: 'DNS_TIMEOUT',
      checks_json: JSON.stringify([{ name: 'giriş sayfası 200', passed: true }, { name: 'oturum açıldı ve hesap özeti alanları doğrulandı', passed: false }]),
      run_context: { process_budget_s: 60, request_timeout_s: null, egress: 'direct', corporate_ca: true, inverted_budget: false },
    } }) })
  })
  return tests
}

async function openEdit(page) {
  const card = page.locator('.upt-grid [data-slot="card"]').filter({ hasText: MON.name }).first()
  await expect(card).toBeVisible({ timeout: 20_000 })
  const edit = card.getByRole('button', { name: /— (Düzenle|Edit)$/ })
  if (await edit.isVisible()) await edit.click()
  else {
    await card.locator('[data-slot="monitor-card-more"] button').first().click()
    await page.getByRole('menuitem', { name: /^(Düzenle|Edit)$/ }).click()
  }
  const dlg = page.getByRole('dialog').last()
  await expect(dlg).toBeVisible()
  return dlg
}

async function openNew(page) {
  await expect(page.locator('.upt-grid [data-slot="card"]').first()).toBeVisible({ timeout: 20_000 })
  const btn = page.getByRole('button', { name: /^(New Monitor|Yeni Monitör)$/ }).first()
  if (await btn.isVisible()) await btn.click()
  else await page.getByRole('button', { name: /new monitor|yeni monitör/i }).first().click()
  const dlg = page.getByRole('dialog').last()
  await expect(dlg).toBeVisible()
  return dlg
}

/** Pencerenin kaydırılan gövdesi + sayfa yatay taşıyor mu (kod düzenleyicisinin KENDİ kaydırması sayılmaz). */
function overflowReport() {
  const out = []
  const doc = document.documentElement
  if (doc.scrollWidth > doc.clientWidth + 1) out.push(`sayfa ${doc.scrollWidth}/${doc.clientWidth}`)
  const dlg = [...document.querySelectorAll('[role="dialog"]')].at(-1)
  if (!dlg) return ['NO_DIALOG']
  for (const el of dlg.querySelectorAll('*')) {
    const cs = getComputedStyle(el)
    if (cs.overflowY === 'auto' || cs.overflowY === 'scroll') {
      if (el.closest('.code-editor-wrap')) continue
      if (el.scrollWidth > el.clientWidth + 1) out.push(`kaydırılan ${el.className.slice(0, 40)} ${el.scrollWidth}/${el.clientWidth}`)
    }
  }
  const d = dlg.getBoundingClientRect()
  for (const sel of ['[data-slot="sc-sec-general"]', '[data-slot="sc-sec-scenario"]', '[data-slot="sc-timing"]', '[data-slot="sc-sec-notify"]',
    '[data-slot="env-lint"]', '[data-slot="env-row-notes"]', '[data-slot="test-run"]', '[data-slot="run-context"]']) {
    for (const el of dlg.querySelectorAll(sel)) {
      const r = el.getBoundingClientRect()
      if (r.width === 0) continue
      if (r.left < d.left - 1 || r.right > d.right + 1) out.push(`${sel} pencere dışına taşıyor ${Math.round(r.left)}–${Math.round(r.right)}`)
    }
  }
  return out
}

/** Kırpılmış metin (yalnız bu özelliğin metinleri — sararak tam görünmeli). */
function clippedTexts() {
  const out = []
  const dlg = [...document.querySelectorAll('[role="dialog"]')].at(-1)
  const sel = '[data-slot="env-lint"] li span, [data-slot="env-row-notes"] span, [data-slot="sc-timing-summary"], [data-slot="run-context"] [data-ctx], '
    + '[data-slot="stop-point"], [data-slot="run-failure-reason"], [data-slot="kept-used"], [data-slot="env-saved-value"]'
  for (const el of dlg?.querySelectorAll(sel) || []) {
    const r = el.getBoundingClientRect()
    if (r.width === 0) continue
    if (el.scrollWidth > el.clientWidth + 1) out.push(`${el.getAttribute('data-slot') || el.getAttribute('data-ctx') || el.tagName} ${el.scrollWidth}/${el.clientWidth}`)
  }
  return out
}

/** 40 px'ten küçük görünür denetimler (bu özelliğin denetimleri). */
function smallTargets() {
  const out = []
  const dlg = [...document.querySelectorAll('[role="dialog"]')].at(-1)
  const sel = '[data-slot="sc-timing"] h3 button, [data-slot="env-lint"] button, [data-slot="goto-line"], [data-slot="sc-test-bar"] button, '
    + '[data-slot="env-row-notes"] [data-slot="hint-trigger"]'
  for (const b of dlg?.querySelectorAll(sel) || []) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0) continue
    if (r.height < 39.5 || r.width < 39.5) out.push(`${b.getAttribute('data-slot') || b.getAttribute('aria-label') || b.textContent.trim().slice(0, 30)} ${Math.round(r.width)}×${Math.round(r.height)}`)
  }
  return out
}

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

for (const vp of VIEWPORTS) {
  test.describe(`Sentetik izleme formu — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('yeni izleme: bölüm sırası, şablon seçiciye ≤ 1 ekran, şablon sihirbazı + __ENV çipleri, zamanlama bölümü', async ({ page }) => {
      test.setTimeout(120_000)
      await setup(page)
      await page.goto('/?tab=scripted')
      const dlg = await openNew(page)

      // Bölüm sırası
      const order = await dlg.evaluate((el) => [...el.querySelectorAll('[data-slot="sc-sec-general"], [data-slot="sc-sec-scenario"], [data-slot="sc-timing"], [data-slot="sc-sec-notify"]')]
        .map((x) => x.getAttribute('data-slot')))
      expect(order).toEqual(['sc-sec-general', 'sc-sec-scenario', 'sc-timing', 'sc-sec-notify'])

      // Şablon seçiciye en çok 1 ekran kaydırma: seçicinin pencere gövdesi içindeki konumu ≤ gövde yüksekliği × 2
      const pos = await dlg.evaluate((el) => {
        const trigger = el.querySelector('.sc-source-select [role="combobox"]')
        let body = trigger.parentElement
        while (body && !(getComputedStyle(body).overflowY === 'auto' || getComputedStyle(body).overflowY === 'scroll')) body = body.parentElement
        const tb = trigger.getBoundingClientRect(), bb = body.getBoundingClientRect()
        return { top: tb.top - bb.top + body.scrollTop, height: body.clientHeight }
      })
      expect(pos.top, `seçici gövdenin ${pos.top}. pikselinde, gövde ${pos.height}`).toBeLessThan(pos.height * 2)

      // Zamanlama kapalı ve özetli
      await expect(dlg.locator('[data-slot="sc-timing-summary"]')).toContainText(/5/)
      await expect(dlg.getByLabel(/process timeout|süreç timeout/i)).toHaveCount(0)

      // Şablon seç → ad önerisi, değişken açıklamaları, "Hedef: host", __ENV çipleri
      await dlg.locator('.sc-source-select [role="combobox"]').click()
      const opt = page.getByRole('option', { name: /Keycloak/ })
      await expect(opt).toBeVisible()
      await expect(opt).toContainText(/Signs in through Keycloak|Keycloak üzerinden/)
      await opt.click()
      await expect(dlg.getByTestId ? dlg.locator('textarea#k6-script-editor') : dlg.locator('textarea')).toHaveValue(/MISSING_ONE/)
      await expect(dlg.getByRole('textbox', { name: /^(Name|Ad)\b/ })).toHaveValue(/Keycloak/)
      await expect(dlg.locator('[data-slot="env-desc"]')).toHaveCount(3)
      await dlg.getByLabel(/^(value|değer)$/).first().fill('https://ibank.example.com/app')
      await expect(dlg.locator('[data-slot="env-target"]')).toContainText('ibank.example.com')
      await expect(dlg.locator('[data-slot="env-lint"] [data-lint="undefined"]')).toContainText('MISSING_ONE', { timeout: 5000 })

      // Kod düzenleyicinin erişilebilir adı (E1)
      await expect(dlg.locator('textarea#k6-script-editor')).toHaveAttribute('aria-label', /k6/)

      // Zamanlama bölümünü aç: düzenleyici + önizleme sığar
      await dlg.locator('[data-slot="sc-timing"] h3 button').click()
      await expect(dlg.getByLabel(/process timeout|süreç timeout/i)).toBeVisible()
      await expect(dlg.locator('[data-slot="dbq-next-runs"] li')).toHaveCount(4, { timeout: 5000 })

      expect(await page.evaluate(overflowReport)).toEqual([])
      expect(await page.evaluate(clippedTexts)).toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets)).toEqual([])
      await page.screenshot({ path: test.info().outputPath(`new-${vp.name}.png`), fullPage: false })
    })

    test('düzenleme: kaynak onayı env korur; test kayıtlı değerle gider; sonuç paneli odak + satıra git; taşma yok', async ({ page }) => {
      test.setTimeout(120_000)
      const tests = await setup(page)
      await page.goto('/?tab=scripted')
      const dlg = await openEdit(page)

      await expect(dlg.locator('[data-slot="env-saved-value"]')).toBeVisible()
      // Uzun gizli değişken adı tam görünür (kutuda) — satır pencereye sığar
      await expect(dlg.getByRole('textbox', { name: /^(NAME|AD)$/ }).nth(1)).toHaveValue(LONG_VAR)

      // Kaynak değişimi onayı → Vazgeç → hiçbir şey değişmez
      await dlg.locator('.sc-source-select [role="combobox"]').click()
      await page.getByRole('option', { name: /Keycloak/ }).click()
      const confirm = page.getByRole('button', { name: /^(Change source|Kaynağı değiştir)$/ })
      await expect(confirm).toBeVisible()
      await page.getByRole('dialog').last().getByRole('button', { name: /^(Cancel|İptal)$/ }).click()
      await expect(confirm).toHaveCount(0)
      await expect(dlg.locator('textarea#k6-script-editor')).toHaveValue(MON.script)

      // Test Çalıştır → keep:true + monitorId; panel görünür alana kayar ve odak alır
      await dlg.getByRole('button', { name: /^(Test Run|Test Çalıştır)$/ }).click()
      const panel = dlg.locator('[data-slot="test-run"]')
      await expect(panel).toContainText(/ReferenceError/)
      expect(tests).toHaveLength(1)
      expect(tests[0].monitorId).toBe(100)
      expect(tests[0].env).toContainEqual({ name: LONG_VAR, value: '', secret: true, keep: true })
      await expect(panel).toBeFocused()
      await expect(panel).toBeInViewport()
      await expect(panel.locator('[data-slot="kept-used"]')).toContainText(LONG_VAR)
      await expect(panel.locator('[data-slot="run-context"] [data-ctx="budget"]')).toBeVisible()
      await expect(panel.locator('[data-slot="error-text"]')).not.toContainText('süreç bütçesi')

      // Satıra git → düzenleyicide 4. satır seçilir
      await panel.locator('[data-slot="goto-line"]').click()
      const sel = await dlg.locator('textarea#k6-script-editor').evaluate((ta) => ta.value.slice(ta.selectionStart, ta.selectionEnd))
      expect(sel).toContain('const r = http.get')

      expect(await page.evaluate(overflowReport)).toEqual([])
      expect(await page.evaluate(clippedTexts)).toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets)).toEqual([])
      await page.screenshot({ path: test.info().outputPath(`edit-${vp.name}.png`), fullPage: false })
    })
  })
}
