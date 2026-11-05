// Yönetim → Bildirim & Alarmlar → Alarm iletimi (2026-11-05, kullanıcı isteği: "bir takım istediği bir izleme tipi için veya
// belirli bir izleme için oluşacak alarmların başka bir takıma da gitmesi için başka takımı veya o takımdan belirli üyeleri ek
// olarak ekleyebilmeli … zengin ekran, shadcn, mweb"). Üç boyutta (390 telefon + dokunmatik, 768 tablet + dokunmatik, 1280
// dizüstü) gerçek tarayıcıda ölçülür: sayfa / pencere yatay taşmaz; dar kapta kartlar, geniş kapta tablo; uzun takım / kişi /
// izleme adları KESİLMEZ (hap köşesi dahil); dokunma hedefleri ≥ 40 px (görünmez ::after vuruş alanı ve etiketli onay kutusu
// sayılır); KPI süzgeci; İşlemler menüsü ekrana sığar; yeni iletim (türler, izleme seçici, üye seçici) doğru gövdeyle gider;
// düzenleme yalnız değişen alanı gönderir; g_fnew derin bağı pencereyi o izleme seçili açar. API tamamen mock'lu (yalnız /api/).
import { test, expect } from '@playwright/test'

const OWNER = 'Ödeme Sistemleri ve Kart Operasyonları Uygulama Geliştirme Ekibi (Teknoloji / Ödeme Platformları)'
const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'
const LONG_PERSON = 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin (Teknoloji Servis Yönetimi Bölüm Başkanı)'
const PERSON2 = 'Çağrı Işıkoğlu-Büyükkaradağ (Ağ Operasyon Merkezi / Kıdemli Sistem Uzmanı)'
const NET_TEAM = 'Ağ ve Altyapı Operasyon Merkezi 7/24 Gözetim ve Olay Müdahale Ekibi'

const MONITORS = [
  { type: 'http', id: 101, name: 'Ödeme ağ geçidi sağlık ucu — üretim ortamı (birincil veri merkezi)', target: 'https://odeme-gateway.prod.example.com/api/v2/saglik/derin-kontrol', active: true },
  { type: 'http', id: 102, name: 'Kart yetkilendirme servisi', target: 'https://kart-auth.example.com/health', active: true },
  { type: 'ping', id: 103, name: 'Çekirdek yönlendirici', target: '10.20.30.1', active: false },
  { type: 'cert', id: 40, name: 'odeme.example.com', target: 'odeme.example.com', active: true },
  { type: 'dbquery', id: 7, name: 'Bekleyen ödeme kuyruğu derinliği', target: 'PG · odeme-db', active: true },
]
const ROWS = [
  { id: 1, version: 2, owner_team_id: 10, owner_team_name: OWNER, owner_team_active: true, scope_kind: 'TYPES', scope_types: ['http', 'ping', 'cert'],
    scope_monitors: [], recipient_kind: 'TEAM', recipient_team_id: 20, recipient_team_name: LONG_TEAM, recipient_team_active: true,
    recipient_members: [], recipient_address_count: 3, enabled: true, expires_on: null, state: 'active',
    note: 'Gece vardiyası boyunca ödeme alarmlarını mobil ekip de görsün; sabah devir toplantısında gözden geçirilir.',
    created_at: '2026-11-01T08:00:00', created_by: 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin', updated_at: '2026-11-01T08:00:00', can_edit: true },
  { id: 2, version: 5, owner_team_id: 10, owner_team_name: OWNER, owner_team_active: true, scope_kind: 'MONITORS', scope_types: [],
    scope_monitors: [...MONITORS.map((m) => ({ ...m, missing: false })), { type: 'port', id: 99, name: null, target: null, active: null, missing: true }],
    recipient_kind: 'MEMBERS', recipient_team_id: 30, recipient_team_name: NET_TEAM, recipient_team_active: true,
    recipient_members: [
      { user_id: 'u-1', display_name: LONG_PERSON, active: true, in_team: true },
      { user_id: 'u-2', display_name: PERSON2, active: false, in_team: true },
      { user_id: 'u-3', display_name: 'Can Demir', active: true, in_team: false },
    ], recipient_address_count: 0, enabled: true, expires_on: '2099-12-31', state: 'active', note: null,
    created_at: '2026-11-02T08:00:00', created_by: 'yonetici', can_edit: true },
  { id: 3, version: 1, owner_team_id: 40, owner_team_name: 'Kart Operasyon', owner_team_active: true, scope_kind: 'ALL', scope_types: [],
    scope_monitors: [], recipient_kind: 'TEAM', recipient_team_id: 50, recipient_team_name: 'Arşiv Takımı', recipient_team_active: false,
    recipient_members: [], recipient_address_count: 1, enabled: true, expires_on: null, state: 'recipient_inactive', note: null,
    created_at: '2026-10-01T08:00:00', created_by: 'baska', can_edit: false },
]
const INCOMING = [
  { id: 9, owner_team_id: 60, owner_team_name: 'Hazine ve Fon Yönetimi Platform Ekibi (Finans Teknolojileri)', scope_kind: 'TYPES', scope_types: ['dbquery', 'scripted'],
    scope_monitors: [], recipient_kind: 'TEAM', recipient_team_id: 10, recipient_team_name: OWNER, recipient_team_active: true,
    recipient_members: [], recipient_address_count: 2, enabled: true, state: 'active', note: 'Ay sonu kapanışı', can_edit: false },
]
const DIR = [
  { id: 10, name: OWNER, active: true, email: 'odeme@example.com' },
  { id: 20, name: LONG_TEAM, active: true, email: 'mobil@example.com' },
  { id: 30, name: NET_TEAM, active: true, email: '' },
  { id: 50, name: 'Arşiv Takımı', active: false, email: 'a@example.com' },
]
const MEMBERS = [
  { id: 'op-1', username: 'KULLANICI_A', display_name: LONG_PERSON, title: 'Bölüm Başkanı', department: 'Teknoloji Servis Yönetimi', email: 'a@example.com', active: true },
  { id: 'op-2', username: 'KULLANICI_B', display_name: PERSON2, title: 'Kıdemli Sistem Uzmanı', department: 'Ağ Operasyon Merkezi', email: '', active: true },
  { id: 'op-3', username: 'KULLANICI_C', display_name: 'Pasif Kişi', email: 'p@example.com', active: false },
]

/**
 * Sunucu taklidi. PUT sürüm denetler (eşleşmezse 409 CONCURRENT_MODIFICATION — gerçek sunucu gibi), başarılı PUT sürümü
 * artırıp satırı döner (`data`). `opts.slowListMs`: ilk okumadan SONRAKİ liste okumaları bu kadar geciktirilir — duraklat →
 * sürdür art arda yapılınca ikinci işlemin sürümü arka plan yenilemesinden değil işlemin yanıtından gelmeli.
 */
async function mock(page, calls, opts = {}) {
  const server = new Map(ROWS.map((r) => [String(r.id), { ...r }]))
  let listReads = 0
  await page.addInitScript(() => {
    try { localStorage.setItem('sm.tour', JSON.stringify({ status: 'dismissed', version: 99 })) } catch { /* yoksay */ }
  })
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const req = route.request()
    const p = new URL(req.url()).pathname
    let body = { success: true, data: [] }
    if (p === '/api/me') {
      body = { success: true, username: 'demo', system_role: 'ADMIN', global_admin: true, team_id: 10, team_name: OWNER,
        team_ids: [10], team_names: [OWNER], tour: { status: 'dismissed', version: 99 } }
    } else if (p === '/api/me/permissions') {
      body = { success: true, data: { 'alerts.forwards': { view: true, edit: true }, 'teams.sharing': { view: true, edit: true } } }
    } else if (p === '/api/alert-forwards' && req.method() === 'POST') {
      calls.posted.push(JSON.parse(req.postData() || '{}'))
      body = { success: true, data: { ...ROWS[0], id: 77 }, grant: null, info: { requested: true, emails: 2, push: 1, muted: false } }
    } else if (p.startsWith('/api/alert-forwards/') && req.method() === 'PUT') {
      const id = p.split('/').pop()
      const put = JSON.parse(req.postData() || '{}')
      calls.put.push({ id, body: put })
      const cur = server.get(id)
      if (cur && put.version != null && put.version !== cur.version) {
        await route.fulfill({ status: 409, contentType: 'application/json',
          body: JSON.stringify({ success: false, code: 'CONCURRENT_MODIFICATION', error: 'Kayıt başka biri tarafından değiştirildi (test).' }) })
        return
      }
      const resumed = cur && cur.enabled === false && put.enabled === true
      const next = { ...cur, ...(put.note != null ? { note: put.note } : {}),
        ...(typeof put.enabled === 'boolean' ? { enabled: put.enabled, state: put.enabled ? 'active' : 'paused' } : {}),
        version: (cur?.version ?? 0) + 1 }
      server.set(id, next)
      // Bilgi yalnız etkinleştirmede istenir (yeni sözleşme: requested / muted); not değişikliğinde istenmez
      body = { success: true, data: next, grant: null,
        info: { requested: resumed, emails: resumed ? 1 : 0, push: resumed ? 2 : 0, muted: false } }
    } else if (p === '/api/alert-forwards/monitors') {
      body = { success: true, data: { items: MONITORS } }
    } else if (p === '/api/alert-forwards') {
      listReads += 1
      if (opts.slowListMs && listReads > 1) await new Promise((r) => setTimeout(r, opts.slowListMs))
      body = { success: true, data: { items: [...server.values()], incoming: INCOMING, manageable_teams: [{ id: 10, name: OWNER }],
        can_grant: true, limits: { per_team: 100, members: 50, monitors: 200, note: 500 } } }
    } else if (p === '/api/teams/directory') {
      body = { success: true, data: DIR }
    } else if (/^\/api\/teams\/\d+\/members$/.test(p)) {
      body = { success: true, data: { team: { id: 30 }, members: MEMBERS, escalation_contacts: [] } }
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

async function pageOverflow(page) {
  return page.evaluate(() => {
    const main = document.querySelector('.app-main') || document.body
    return Math.max(document.documentElement.scrollWidth - document.documentElement.clientWidth, main.scrollWidth - main.clientWidth)
  })
}

/** 40 px'ten kısa görünür denetimler. Etiketli onay kutusu / radyo için etiket, görünmez ::after vuruş alanı da sayılır. */
function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button, a[href], select, input:not([type="hidden"]), textarea')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0) continue
    const cs = getComputedStyle(b)
    if (cs.visibility === 'hidden' || cs.display === 'none') continue
    let h = (b.closest('[data-slot="input-group"]')?.getBoundingClientRect() ?? r).height
    const role = b.getAttribute('role')
    if (role === 'radio' || role === 'checkbox') h = Math.max(h, b.closest('label')?.getBoundingClientRect().height ?? 0)
    const af = getComputedStyle(b, '::after')
    if (af.position === 'absolute' && af.content !== 'none') {
      const top = parseFloat(af.top) || 0
      const bottom = parseFloat(af.bottom) || 0
      h = Math.max(h, r.height - Math.min(0, top) - Math.min(0, bottom))
    }
    if (h < 39.5) out.push(`${b.tagName.toLowerCase()}[${b.getAttribute('data-slot') || role || ''}] "${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)}" ${Math.round(h)}`)
  }
  return out
}

/** Kesilen / kabından taşan adlar (takım rozeti, üye / kapsam / izleme çipleri, seçici satırları, özet). */
function clippedText(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const rootR = root.getBoundingClientRect()
  const out = []
  const q = [
    '[data-slot="team-badge"] span', '[data-slot="fwd-member"] span', '[data-slot="fwd-scope-chip"] span', '[data-slot="fwd-note"]',
    '[data-slot="fwd-meta"] span', '[data-slot="fwd-monitor-item"] span', '[data-slot="fwd-member-item"] span',
    '[data-slot="fwd-monitor-chip"] span', '[data-slot="fwd-member-chip"] span', '[data-slot="fwd-summary"] span',
    '[data-slot="fwd-state"]', '[role="combobox"] span', '[data-slot="fwd-attention"]',
  ].join(', ')
  for (const el of root.querySelectorAll(q)) {
    const r = el.getBoundingClientRect()
    if (r.width === 0) continue
    const cs = getComputedStyle(el)
    // Hap köşeleri çok satırlı metnin uçlarını kırpar (rozet `rounded-full` + taşma gizli)
    const box = el.closest('[data-slot="team-badge"], [data-slot="badge"], [data-slot="fwd-state"], [data-slot="fwd-member"], '
      + '[data-slot="fwd-scope-chip"], [data-slot="fwd-monitor-chip"], [data-slot="fwd-member-chip"]')
    const pillClip = box && getComputedStyle(box).overflow !== 'visible' && box.getBoundingClientRect().height > 30
      && parseFloat(getComputedStyle(box).borderTopLeftRadius) > 8
    if (pillClip || cs.textOverflow === 'ellipsis' || el.scrollWidth > el.clientWidth + 1 || r.right > rootR.right + 1) {
      out.push(`${el.getAttribute('data-slot') || el.tagName.toLowerCase()} "${el.textContent.trim().slice(0, 30)}" ${el.scrollWidth}>${el.clientWidth}`)
    }
  }
  return out
}

async function inViewport(locator, vw) {
  const box = await locator.boundingBox()
  expect(box.x).toBeGreaterThanOrEqual(-1)
  expect(box.x + box.width).toBeLessThanOrEqual(vw + 1)
}

/** Açılış animasyonları bitsin (ölçüm hareketli öğede yapılmasın). */
async function settle(page) {
  await page.waitForFunction(() => document.getAnimations().every((a) => a.playState !== 'running'), null, { timeout: 5_000 }).catch(() => {})
}

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

for (const vp of VIEWPORTS) {
  test.describe(`Alarm iletimi — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('liste: taşma yok, ad kesilmez, dokunma hedefleri, KPI süzgeci, kapsam açılır, İşlemler menüsü', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page, { posted: [], put: [] })
      await page.goto('/?tab=admin&g_tab=forwards')
      const root = page.locator('[data-slot="alert-forwards"]')
      const rows = root.locator('[data-slot="fwd-list"] [data-slot="fwd-row"]')
      await expect(rows).toHaveCount(3, { timeout: 20_000 })
      expect(await page.evaluate(() => matchMedia('(pointer: coarse)').matches)).toBe(vp.touch)
      await expect(root.locator('[data-slot="fwd-list"]')).toHaveAttribute('data-layout', vp.width < 1000 ? 'cards' : 'table')
      await expect(root.locator('[data-slot="fwd-incoming"]')).toContainText('Hazine ve Fon Yönetimi Platform Ekibi')
      // Kapsam: 6 öğeden 4'ü görünür → "+2" açılınca hepsi (silinmiş izleme dahil)
      const scope = rows.filter({ hasText: NET_TEAM }).locator('[data-slot="fwd-scope"]')
      await expect(scope.locator('[data-slot="fwd-scope-chip"]')).toHaveCount(4)
      await scope.locator('[data-slot="fwd-scope-more"]').click()
      await expect(scope.locator('[data-slot="fwd-scope-chip"]')).toHaveCount(6)
      await settle(page)
      expect(await pageOverflow(page), 'sayfa yatay taşması').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clippedText, '[data-slot="alert-forwards"]'), 'kesilen ad').toEqual([])
      if (vp.touch) {
        const bad = await page.evaluate(smallTargets, '[data-slot="alert-forwards"]')
        expect(bad, `40 px altı hedefler: ${bad.join(', ')}`).toEqual([])
      }

      // KPI: "Dikkat" → alıcı takımı pasif + adressiz satır; "Toplam" temizler
      await root.locator('[data-slot="stat-item"][data-key="attention"]').click()
      await expect(rows).toHaveCount(2)
      await expect(page).toHaveURL(/g_fstate=attention/)
      await root.locator('[data-slot="stat-item"][data-key="total"]').click()
      await expect(rows).toHaveCount(3)

      // Salt okunur satırda menü yok; düzenlenebilir satırın etiketli İşlemler menüsü ekrana sığar
      await expect(rows.filter({ hasText: 'Arşiv Takımı' }).locator('button[data-kebab="labeled"]')).toHaveCount(0)
      const trigger = rows.filter({ hasText: NET_TEAM }).locator('button[data-kebab="labeled"]')
      if (vp.touch) expect((await trigger.boundingBox()).height).toBeGreaterThanOrEqual(39.5)
      await trigger.click()
      const menu = page.getByRole('menu')
      await expect(menu).toBeVisible()
      await expect(menu.getByRole('menuitem')).toHaveCount(3)
      await inViewport(menu, vp.width)
      await page.keyboard.press('Escape')
      await page.screenshot({ path: `test-results/alert-forwards-${vp.name}.png`, fullPage: true })
    })

    test('yeni iletim: türler + izleme seçici + üye seçici; pencere sığar, ad kesilmez, doğru gövde', async ({ page }) => {
      test.setTimeout(150_000)
      const calls = { posted: [], put: [] }
      await mock(page, calls)
      await page.goto('/?tab=admin&g_tab=forwards')
      const newBtn = page.locator('[data-slot="fwd-new"]')
      await expect(newBtn).toBeVisible({ timeout: 20_000 })
      await newBtn.click()
      const form = page.locator('[data-slot="fwd-form"]')
      await expect(form).toBeVisible()
      const dialog = page.getByRole('dialog')
      await inViewport(dialog, vp.width)

      // Türler: 11 tür çipi, iki seç
      await form.getByRole('radio', { name: /Seçilen türler|Selected types/ }).click()
      const chips = form.locator('[data-slot="fwd-type-chips"]')
      await expect(chips.locator('[data-type]')).toHaveCount(11)
      await chips.locator('[data-type="http"]').click()
      await chips.locator('[data-type="dbquery"]').click()
      await settle(page)
      if (vp.touch) {
        const bad = await page.evaluate(smallTargets, '[role="dialog"]')
        expect(bad, `türlerde 40 px altı hedefler: ${bad.join(', ')}`).toEqual([])
      }

      // İzleme seçici: sahip takımın izlemeleri, uzun ad / hedef sarar; iki izleme seç
      await form.getByRole('radio', { name: /Seçilen izlemeler|Selected monitors/ }).click()
      const items = form.locator('[data-slot="fwd-monitor-item"]')
      await expect(items).toHaveCount(5)
      // Seçici bir GRUP — adı alanın etiketi (ekran okuyucu "İzlemeler, grup" der); listede aria-invalid yok
      await expect(form.getByRole('group', { name: /^(İzlemeler|Monitors)/ })).toHaveAttribute('data-slot', 'fwd-monitor-picker')
      await expect(form.locator('[data-slot="fwd-monitor-list"][aria-invalid]')).toHaveCount(0)
      await items.filter({ hasText: 'Ödeme ağ geçidi sağlık ucu' }).click()
      await items.filter({ hasText: 'Bekleyen ödeme kuyruğu' }).click()
      await expect(form.locator('[data-slot="fwd-monitor-chip"]')).toHaveCount(2)

      // Alıcı: seçilen üyeler — takım seç, üye listesi (pasif yok), iki üye
      await form.getByRole('button', { name: /Seçilen üyeler|Selected members/ }).click()
      const teamPicker = form.getByRole('combobox', { name: /^(Alıcı takım|Recipient team)/ })
      await expect(teamPicker).toBeEnabled()
      await teamPicker.click()
      await expect(page.getByRole('option', { name: 'Arşiv Takımı' })).toHaveCount(0)
      await page.getByRole('option', { name: NET_TEAM }).click()
      const memberItems = form.locator('[data-slot="fwd-member-item"]')
      await expect(memberItems).toHaveCount(2)
      await expect(form.getByRole('group', { name: /^(Üyeler|Members)/ })).toHaveAttribute('data-slot', 'fwd-member-picker')
      await memberItems.nth(0).click()
      await memberItems.nth(1).click()
      await expect(form.locator('[data-slot="fwd-member-chip"]')).toHaveCount(2)
      await expect(form.locator('[data-slot="fwd-member-no-email-note"]')).toBeVisible()
      await form.locator('[data-slot="fwd-grant"]').click()
      await settle(page)
      expect(await page.evaluate(clippedText, '[data-slot="fwd-form"]'), 'pencerede kesilen ad').toEqual([])
      if (vp.touch) {
        const bad = await page.evaluate(smallTargets, '[role="dialog"]')
        expect(bad, `pencerede 40 px altı hedefler: ${bad.join(', ')}`).toEqual([])
      }
      const scroll = await dialog.evaluate((el) => el.scrollWidth - el.clientWidth)
      expect(scroll, 'pencere yatay taşması').toBeLessThanOrEqual(1)
      await expect(form.locator('[data-slot="fwd-sum-recipient"]')).toContainText(NET_TEAM)
      await page.screenshot({ path: `test-results/alert-forwards-dialog-${vp.name}.png` })

      await page.locator('[data-slot="fwd-submit"]').click()
      await expect(form).toHaveCount(0)
      expect(calls.posted).toEqual([{ owner_team_id: 10, scope_kind: 'MONITORS', scope_types: [],
        scope_monitors: [{ type: 'dbquery', id: 7 }, { type: 'http', id: 101 }], recipient_kind: 'MEMBERS', recipient_team_id: 30,
        recipient_user_ids: ['op-1', 'op-2'], grant_view: true }])
    })

    test('düzenleme yalnız değişen alanı gönderir; g_fnew derin bağı pencereyi o izleme seçili açar', async ({ page }) => {
      test.setTimeout(120_000)
      const calls = { posted: [], put: [] }
      await mock(page, calls)
      await page.goto('/?tab=admin&g_tab=forwards')
      const rows = page.locator('[data-slot="fwd-list"] [data-slot="fwd-row"]')
      await expect(rows).toHaveCount(3, { timeout: 20_000 })
      await rows.filter({ hasText: LONG_TEAM }).locator('button[data-kebab="labeled"]').click()
      await page.getByRole('menuitem', { name: /^(Düzenle|Edit)$/ }).click()
      const form = page.locator('[data-slot="fwd-form"]')
      await expect(form).toBeVisible()
      await expect(form.locator('[data-slot="fwd-owner-fixed"]')).toContainText(OWNER)
      await form.getByRole('textbox', { name: /^(Not|Note)$/ }).fill('Yeni not')
      await page.locator('[data-slot="fwd-submit"]').click()
      await expect(form).toHaveCount(0)
      expect(calls.put).toEqual([{ id: '1', body: { note: 'Yeni not', version: 2 } }])
      // Yalnız not değişti: bilgi istenmedi (requested:false) → "bilgi gitmedi" uyarısı ÇIKMAZ (eski hata)
      await expect(page.getByText(/Alarm forwarding updated|Alarm iletimi güncellendi/)).toBeVisible()
      await expect(page.getByText(/No notice reached the recipients|Alıcılara bilgi bildirimi gitmedi/)).toHaveCount(0)

      await page.goto('/?tab=admin&g_tab=forwards&g_fnew=ping:103&g_fteam=10')
      const deep = page.locator('[data-slot="fwd-form"]')
      await expect(deep).toBeVisible({ timeout: 20_000 })
      await expect(deep.getByRole('radio', { name: /Seçilen izlemeler|Selected monitors/ })).toBeChecked()
      await expect(deep.locator('[data-slot="fwd-monitor-chip"][data-key="ping:103"]')).toBeVisible()
      await expect(page).not.toHaveURL(/g_fnew=/)
      await inViewport(page.getByRole('dialog'), vp.width)
    })

    test('duraklat → hemen sürdür: ikinci işlem yanıttaki yeni sürümle gider (409 yok); sürdürmede bilgi sonucu söylenir', async ({ page }) => {
      test.setTimeout(120_000)
      const calls = { posted: [], put: [] }
      // İlk okumadan sonraki liste okumaları 4 sn gecikir — ikinci işlem arka plan yenilemesini BEKLEMEDEN yapılır
      await mock(page, calls, { slowListMs: 4_000 })
      await page.goto('/?tab=admin&g_tab=forwards')
      const row = page.locator('[data-slot="fwd-list"] [data-slot="fwd-row"]').filter({ hasText: LONG_TEAM }).first()
      await expect(row).toHaveAttribute('data-state', 'active', { timeout: 20_000 })
      await row.locator('button[data-kebab="labeled"]').click()
      await page.getByRole('menuitem', { name: /^(Duraklat|Pause)$/ }).click()
      await expect(row).toHaveAttribute('data-state', 'paused')
      // İlk menünün kapanış animasyonu bitsin (kapanan menüye düşen tıklama yeni menüyü hemen kapatırdı — kullanıcı değil test hızı)
      await expect(page.getByRole('menu')).toHaveCount(0)
      await row.locator('button[data-kebab="labeled"]').click()
      await page.getByRole('menuitem', { name: /^(Sürdür|Resume)$/ }).click()
      await expect(row).toHaveAttribute('data-state', 'active')
      expect(calls.put).toEqual([{ id: '1', body: { enabled: false, version: 2 } }, { id: '1', body: { enabled: true, version: 3 } }])
      await expect(page.getByText(/e-mail: 1, push: 2|e-posta: 1, push: 2/)).toBeVisible()
      await expect(page.getByText(/Kayıt başka biri tarafından değiştirildi/)).toHaveCount(0)
    })
  })
}

test.describe('Alarm iletimi — bitiş günü saat dilimine kaymaz', () => {
  // UTC'nin gerisindeki bir saat diliminde "2099-12-31" UTC gece yarısı sayılırsa 30 Aralık görünürdü
  test.use({ viewport: { width: 1280, height: 800 }, timezoneId: 'America/New_York' })

  test('bitiş "31 Dec 2099" (saf gün), "30" değil', async ({ page }) => {
    await mock(page, { posted: [], put: [] })
    await page.goto('/?tab=admin&g_tab=forwards')
    const row = page.locator('[data-slot="fwd-list"] [data-slot="fwd-row"]').filter({ hasText: NET_TEAM }).first()
    const expiry = row.locator('[data-slot="fwd-expiry"]')
    await expect(expiry).toContainText(/31 (Dec|Ara) 2099/, { timeout: 20_000 })
    await expect(expiry).not.toContainText('30')
  })
})
