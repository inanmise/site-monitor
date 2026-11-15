// Yönetim paneli sekmeleri GERÇEK uygulama yolundan (2026-11-15, prod kapısı e2e paketi) — izole harness sayfaları değil,
// `?tab=admin&g_tab=…` ile AdminPanel içinden. API mock'lu. Üç boyutta (390 dokunmatik, 768 dokunmatik, 1280):
//   - thresholds: kartlar + matris; düzenleme penceresinde sıra hatası Kaydet'i kilitler, geçerli değer PUT gövdesinde;
//   - changes: değişiklik akışı alan çipi `areas=`, kişi süzgeci `actor=` ile yeniden ister; satır açılımı fark tablosu;
//   - whoNotified: varsayılan seviye HIGH ile simüle; nöbetçi zincirde; seviye KRİTİK `level=CRITICAL` ister; hariç kalan
//     gerekçesiyle; CSV `downloadBlob` ile gerçekten iner (BOM + adresler); seviye karşılaştırması üç seviye ister;
//   - users: derin bağlantıdaki gelişmiş süzgeçler aramaya gider, tarih süzgeci `loginFrom`; görünüm kaydedilir
//     (PUT /me/preferences savedViews.admin-users), süzgeç temizlenir, görünüm uygulanınca süzgeçler geri gelir.
// Yerleşim: sayfa yatay kaymaz, öğe ekran dışına çıkmaz, metin kırpılmaz, dokunmatikte hedefler ≥ 40 px (layoutAudit).
import fs from 'node:fs'
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'
import { VIEWPORTS, json, pathIs, checkLayout, recordRequests } from './support/layoutAudit.js'

const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi'
const TEAMS = [
  { id: 7, name: LONG_TEAM, active: true },
  { id: 8, name: 'Ödeme Sistemleri', active: true },
]
const PERMS = { 'thresholds.edit': { view: true, edit: true }, 'alerts.read': { view: true } }

// ── Eşikler ─────────────────────────────────────────────────────────────────────────────────────────────────────────
const THRESHOLDS = [
  { id: 1, name: 'default', tier: null, warning_days: 30, high_days: 14, critical_days: 7, re_alert_interval_hours: 24, active: true },
  { id: 2, name: 'tier-1', tier: 1, warning_days: 45, high_days: 21, critical_days: 10, re_alert_interval_hours: 48, active: true },
]
const SCOPE = { default: 40, 1: 18, 2: 14, 3: 9, 4: 6 }
const preview = (tier) => ({ success: true, data: {
  tier, scope_total: SCOPE[tier ?? 'default'], unchecked: tier === 1 ? 3 : 0,
  current: { critical: 1, high: 0, warning: 2, ok: 1 }, proposed: { critical: 1, high: 1, warning: 0, ok: 1 },
  samples: { CRITICAL: ['odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.com (3g)'], HIGH: [], WARNING: [] },
} })

// ── Değişiklik akışı ────────────────────────────────────────────────────────────────────────────────────────────────
const FEED_ITEMS = [
  { id: 1, at: '2026-11-12T10:00:00', actor: 'ayse.yilmaz', area: 'teams', action: 'UPDATE', event_type: 'TEAM_UPDATE',
    resource_type: 'TEAM', resource_id: '7', name: LONG_TEAM, team_id: 7, team_name: LONG_TEAM,
    changes: '{"email":{"from":"eski.adres.cok.uzun.ornek@example.com.tr","to":"yeni.adres.cok.uzun.ornek@example.com.tr"},"leaderId":{"from":3,"to":5}}', detail: null },
  { id: 2, at: '2026-11-12T09:00:00', actor: 'mehmet.demir', area: 'forwards', action: 'CREATE', event_type: 'ALERT_FORWARD_CREATE',
    resource_type: 'TEAM', resource_id: '8', name: 'Ödeme Sistemleri', team_id: 8, team_name: 'Ödeme Sistemleri',
    changes: null, detail: '{"scope_kind":"TYPES","scope_types":"http,keyword,ping","recipient_kind":"MEMBERS"}' },
  { id: 3, at: '2026-11-11T16:30:00', actor: 'system', area: 'groups', action: 'DEFAULT', event_type: 'NOTIFICATION_GROUP_DEFAULT',
    resource_type: 'NOTIFICATION_GROUP', resource_id: '14', name: 'UG Varsayılan Liste — Gece Nöbeti', team_id: 8, team_name: 'Ödeme Sistemleri',
    changes: '{"isDefault":{"from":false,"to":true}}', detail: null },
]
const FEED_AREAS = ['teams', 'users', 'contacts', 'thresholds', 'groups', 'forwards', 'sharing', 'oncall']

// ── Kim bilgilendirilir ─────────────────────────────────────────────────────────────────────────────────────────────
const sim = (level, extra = {}) => ({
  team_id: 7, team_name: LONG_TEAM, level, standalone_monitor: false, managers_included: true,
  team_contacts_missing: false, team_contacts_defined: true, email_total: 1,
  team_emails: [{ email: 'dijital-kanallar-mobil-bankacilik-uygulama-gelistirme-ekibi@example.com', team: LONG_TEAM, source: 'Takım maili', kind: 'TEAM' }],
  contacts: [], webhooks: [], push: [], push_access: 'NONE',
  excluded_contacts: [{ id: 3, name: 'Mehmet Emin Çelikkanat-Özdemiroğulları (Uygulama Geliştirme ve Entegrasyon Müdürü)', email: 'kritik.mudur@example.com',
    role: 'MANAGER', min_level: 'CRITICAL', reason: 'LEVEL_THRESHOLD' }],
  ...extra,
})
const ONCALL = [{ team_id: 7, name: 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin (Nöbetçi)', email: 'aysegul.nobetci@example.com', duplicate: false }]
const SIM = {
  WARNING: sim('WARNING'),
  HIGH: sim('HIGH', { oncall: ONCALL }),
  CRITICAL: sim('CRITICAL', {
    contacts: [{ id: 3, name: 'Mehmet Emin Çelikkanat-Özdemiroğulları (Uygulama Geliştirme ve Entegrasyon Müdürü)', email: 'kritik.mudur@example.com',
      role: 'MANAGER', min_level: 'CRITICAL', team_id: 7, email_duplicate: false }],
    excluded_contacts: [], oncall: ONCALL,
  }),
}

// ── Kullanıcılar ────────────────────────────────────────────────────────────────────────────────────────────────────
const USERS = [
  { id: 2, username: 'KULLANICI_A', display_name: 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin (Teknoloji Servis Yönetimi Bölüm Başkanı)',
    employee_id: '100002', email: 'aysegul.karaosmanoglu@example.com', system_role: 'USER', org_role: 'TECH', team_id: 7, team_ids: [7],
    active: false, auth_source: 'LDAP', last_login_at: '2026-10-01T09:00:00', created_at: '2025-01-01T09:00:00' },
  { id: 3, username: 'KULLANICI_B', display_name: 'Mehmet Emin Çelikkanat-Özdemiroğulları', employee_id: '100003', email: 'mehmet@example.com',
    system_role: 'TEAM_ADMIN', org_role: 'MANAGER', team_id: 8, team_ids: [8, 7], active: false, auth_source: 'LDAP',
    last_login_at: '2026-10-20T09:00:00', created_at: '2025-03-01T09:00:00' },
]

async function mock(page) {
  await mockApi(page, { role: 'ADMIN', globalAdmin: true, teamIds: [7], perms: PERMS })
  const calls = { thresholdPuts: [], prefsPuts: [] }
  await page.route(pathIs('/api/admin/teams'), json({ success: true, data: TEAMS }))
  // Eşikler
  await page.route(pathIs('/api/admin/thresholds'), json({ success: true, data: THRESHOLDS }))
  await page.route(pathIs('/api/admin/thresholds/preview'), (route) => {
    const tier = new URL(route.request().url()).searchParams.get('tier')
    return json(preview(tier == null ? null : Number(tier)))(route)
  })
  await page.route((u) => /^\/api\/admin\/thresholds\/\d+$/.test(new URL(u).pathname), (route) => {
    if (route.request().method() === 'PUT') calls.thresholdPuts.push(JSON.parse(route.request().postData() || '{}'))
    return json({ success: true, data: THRESHOLDS[0] })(route)
  })
  // Değişiklik akışı
  await page.route(pathIs('/api/admin/history/feed'), (route) => {
    const sp = new URL(route.request().url()).searchParams
    const areas = (sp.get('areas') || '').split(',').filter(Boolean)
    const actor = sp.get('actor') || ''
    const items = FEED_ITEMS.filter((i) => (!areas.length || areas.includes(i.area)) && (!actor || i.actor.includes(actor)))
    return json({ success: true, items, total: items.length, page: 0, size: 25, total_pages: 1, truncated: false, hidden: 0, days: 30, areas: FEED_AREAS })(route)
  })
  // Kim bilgilendirilir
  await page.route(pathIs('/api/notification-groups'), json({ success: true, data: { groups: [], writable_team_ids: [7], team_names: {}, team_emails: {} } }))
  await page.route(pathIs('/api/noc/coverage'), json({ success: true, data: { summary: { total: 0, covered: 0, not_covered: 0, paused: 0, by_type: {},
    active_groups: 0, disabled_types: [], min_level: 'CRITICAL' }, items: [] } }))
  await page.route(pathIs('/api/admin/recipients/simulate'), (route) => {
    const level = new URL(route.request().url()).searchParams.get('level')
    return json({ success: true, data: SIM[level] })(route)
  })
  // Kullanıcılar
  await page.route(pathIs('/api/admin/users/search'), json({ success: true, data: USERS, total: USERS.length, page: 0, total_pages: 1, active_admin_count: 1 }))
  page.on('request', (req) => {
    if (new URL(req.url()).pathname === '/api/me/preferences' && req.method() === 'PUT') {
      try { calls.prefsPuts.push(JSON.parse(req.postData() || '{}')) } catch { /* yok */ }
    }
  })
  return calls
}

const searchOf = (log, path) => log.filter((r) => r.path === path).map((r) => new URLSearchParams(r.search))

for (const vp of VIEWPORTS) {
  test.describe(`Admin tabs (real route) — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('thresholds: cards + matrix; order error locks Save; valid edit sends PUT with the values; layout', async ({ page }) => {
      test.setTimeout(120_000)
      const calls = await mock(page)
      await page.goto('/?tab=admin&g_tab=thresholds')
      const cards = page.locator('[data-slot="threshold-card"]')
      await expect(cards).toHaveCount(2, { timeout: 30_000 })
      await expect(page.locator('[data-slot="threshold-matrix"]')).toBeVisible()
      await expect(cards.first().locator('[data-slot="threshold-scope-count"]')).toContainText('40')
      await page.waitForTimeout(400)
      await checkLayout(page, `thresholds@${vp.name}`)

      await cards.first().locator('[data-slot="threshold-actions"] button').first().click()
      const dlg = page.getByRole('dialog')
      await expect(dlg).toBeVisible()
      const critical = dlg.locator('[data-field="critical"]')
      const save = dlg.getByRole('button', { name: /^(Save|Kaydet)$/ })
      await critical.fill('20')   // kritik > yüksek (14) → sıra hatası
      await expect(dlg.getByText(/Out of order: critical ≤ high ≤ warning is required|Sıra hatalı/)).toBeVisible()
      await expect(save).toBeDisabled()
      await critical.fill('5')
      await expect(save).toBeEnabled()
      await page.waitForTimeout(500)   // önizleme debounce + açılış geçişi
      await checkLayout(page, `threshold editor@${vp.name}`, '[role="dialog"]')
      await save.click()
      await expect.poll(() => calls.thresholdPuts.length).toBe(1)
      expect(calls.thresholdPuts[0]).toMatchObject({ tier: null, warning_days: 30, high_days: 14, critical_days: 5, re_alert_interval_hours: 24, name: 'default', active: true })
      await expect(page.getByText(/Thresholds updated|Eşikler güncellendi/)).toBeVisible()
    })

    test('changes: area chip asks areas=, actor filter asks actor=; row expands to the diff; layout', async ({ page }) => {
      test.setTimeout(120_000)
      const log = recordRequests(page)
      await mock(page)
      await page.goto('/?tab=admin&g_tab=changes')
      const feed = page.locator('[data-slot="change-feed"]')
      await expect(feed.locator('[data-slot="cf-row"]')).toHaveCount(3, { timeout: 30_000 })
      await expect(feed.locator('[data-slot="cf-area"]')).toHaveCount(FEED_AREAS.length)
      await page.waitForTimeout(300)
      // Bilinçli tek satır: değişiklik özet çipleri uzun değeri kısaltır (tamamı satır açılımındaki fark tablosunda)
      await checkLayout(page, `changes@${vp.name}`, '[data-slot="change-feed"]', { allowClip: [/change-chips/] })

      // Satır açılımı: fark tablosunda tam e-posta
      const first = feed.locator('[data-slot="cf-row"]').first()
      if (await feed.locator('table').count()) await first.click()
      else await first.getByRole('button').last().click()
      await expect(feed.locator('[data-slot="cf-detail"]')).toContainText('yeni.adres.cok.uzun.ornek@example.com.tr')

      await feed.locator('[data-slot="cf-area"][data-area="forwards"]').click()
      await expect.poll(() => searchOf(log, '/api/admin/history/feed').map((s) => s.get('areas'))).toContain('forwards')
      await expect(feed.locator('[data-slot="cf-row"]')).toHaveCount(1)
      await expect(feed.locator('[data-slot="cf-row"]').first()).toHaveAttribute('data-area', 'forwards')

      await feed.locator('[data-slot="cf-area"][data-area="forwards"]').click()   // çip kapanır
      await feed.locator('[data-slot="cf-actor"]').fill('ayse')
      await expect.poll(() => searchOf(log, '/api/admin/history/feed').map((s) => s.get('actor'))).toContain('ayse')
      await expect(feed.locator('[data-slot="cf-row"]')).toHaveCount(1)
      await expect(page).toHaveURL(/g_cu=ayse/)
    })

    test('whoNotified: chain with on-call, level change asks level=CRITICAL, excluded reason, CSV download, level compare; layout', async ({ page }) => {
      test.setTimeout(120_000)
      const log = recordRequests(page)
      await mock(page)
      await page.goto('/?tab=admin&g_tab=whoNotified&g_team=7')
      const sec = page.locator('[data-slot="who-notified"]')
      await expect(sec.locator('[data-slot="wn-result"]')).toBeVisible({ timeout: 30_000 })
      await expect(sec.locator('[data-slot="wn-recipient"][data-source="oncall"]')).toContainText('aysegul.nobetci@example.com')
      const first = searchOf(log, '/api/admin/recipients/simulate')[0]
      expect(first.get('teamId')).toBe('7')
      expect(first.get('level')).toBe('HIGH')
      await page.waitForTimeout(300)
      await checkLayout(page, `whoNotified@${vp.name}`, '[data-slot="who-notified"]')

      // Hariç kalan, gerekçesiyle (bölüm kapalı başlar; kutucuk açar)
      await sec.locator('[data-slot="stat-item"][data-key="excluded"]').click()
      const excluded = sec.locator('[data-slot="wn-recipient"][data-source="excluded-email"]')
      await expect(excluded).toContainText('kritik.mudur@example.com')
      await expect(excluded).toContainText(/threshold is above this level|eşiği bu seviyenin üstünde/)

      // CSV: gerçek indirme (downloadBlob), BOM + zincirdeki adresler
      const [download] = await Promise.all([page.waitForEvent('download'), sec.locator('[data-slot="wn-export"]').click()])
      expect(download.suggestedFilename()).toMatch(/^kim-bilgilendirilir-.+-high\.csv$/)
      const bytes = fs.readFileSync(await download.path())
      expect([...bytes.subarray(0, 3)]).toEqual([0xef, 0xbb, 0xbf])
      const text = bytes.toString('utf8')
      expect(text).toContain('aysegul.nobetci@example.com')
      expect(text).toContain('kritik.mudur@example.com')

      // Seviye KRİTİK → yeni simülasyon; müdür artık alıcı
      await sec.locator('[data-slot="wn-scenario"]').getByRole('button', { name: /^(CRITICAL|KRİTİK)$/ }).click()
      await expect.poll(() => searchOf(log, '/api/admin/recipients/simulate').map((s) => s.get('level'))).toContain('CRITICAL')
      await expect(sec.locator('[data-slot="wn-recipient"][data-source="contact"]').first()).toContainText('kritik.mudur@example.com', { timeout: 10_000 })

      // Seviyelere göre karşılaştırma: üç seviye ayrı ayrı simüle edilir, değişen satırlar işaretli
      const before = searchOf(log, '/api/admin/recipients/simulate').length
      await sec.getByRole('button', { name: /Compare by level|Seviyelere göre karşılaştır/ }).click()
      const cmp = sec.locator('[data-slot="wn-compare"]')
      await expect(cmp.locator('[data-slot="wn-compare-row"]')).toHaveCount(3, { timeout: 15_000 })
      const levels = searchOf(log, '/api/admin/recipients/simulate').slice(before).map((s) => s.get('level'))
      expect(levels).toEqual(expect.arrayContaining(['WARNING', 'HIGH', 'CRITICAL']))
      await expect(cmp.locator('[data-slot="wn-compare-row"][data-varies="true"]')).toHaveCount(2)
      await page.waitForTimeout(300)
      await checkLayout(page, `whoNotified compare@${vp.name}`, '[data-slot="who-notified"]')
    })

    test('users: deep-link advanced filters reach the search; date filter; save view, clear, apply view; layout', async ({ page }) => {
      test.setTimeout(120_000)
      const log = recordRequests(page)
      const calls = await mock(page)
      await page.goto('/?tab=admin&g_tab=users&g_status=passive&g_src=LDAP')
      const tab = page.locator('[data-testid="users-tab"]')
      await expect(tab.getByText(/KULLANICI_A/).first()).toBeVisible({ timeout: 30_000 })
      await expect.poll(() => searchOf(log, '/api/admin/users/search').some((s) => s.get('status') === 'passive' && s.get('source') === 'LDAP')).toBe(true)
      await expect(tab.locator('[data-testid="um-chips"] [data-chip="status"]')).toBeVisible()
      await expect(tab.locator('[data-testid="um-chips"] [data-chip="source"]')).toBeVisible()
      // Gelişmiş süzgeç paneli derin bağlantıda açık; tarih süzgeci aramaya gider
      await tab.locator('[data-slot="um-login-from"]').fill('2026-10-01')
      await expect.poll(() => searchOf(log, '/api/admin/users/search').some((s) => s.get('loginFrom') === '2026-10-01' && s.get('status') === 'passive')).toBe(true)
      await page.waitForTimeout(300)
      await checkLayout(page, `users@${vp.name}`, '[data-testid="users-tab"]')

      // Görünümü kaydet → kişisel tercihlerde savedViews.admin-users
      await tab.locator('[data-slot="saved-views-trigger"]').click()
      await page.locator('[data-slot="saved-views-save"]').click()
      const prompt = page.getByRole('dialog')
      await prompt.getByRole('textbox').fill('Pasif LDAP hesaplar')
      await prompt.getByRole('button', { name: /^(Save|Kaydet)$/ }).click()
      await expect.poll(() => calls.prefsPuts.some((b) => (b.savedViews?.['admin-users'] || []).some((v) => v.name === 'Pasif LDAP hesaplar'
        && v.params?.status === 'passive' && v.params?.source === 'LDAP' && v.params?.loginFrom === '2026-10-01')), { timeout: 10_000 }).toBe(true)

      // Süzgeçleri temizle → durum süzgeçsiz arama
      const before = searchOf(log, '/api/admin/users/search').length
      await tab.locator('[data-testid="um-chips"]').getByRole('button', { name: /^(Clear filters|Süzgeçleri temizle)$/ }).click()
      await expect.poll(() => searchOf(log, '/api/admin/users/search').slice(before).some((s) => !s.get('status') && !s.get('source'))).toBe(true)
      await expect(tab.locator('[data-testid="um-chips"]')).toHaveCount(0)

      // Kayıtlı görünümü uygula → süzgeçler geri gelir
      const mid = searchOf(log, '/api/admin/users/search').length
      await tab.locator('[data-slot="saved-views-trigger"]').click()
      await page.locator('[data-slot="saved-view"]', { hasText: 'Pasif LDAP hesaplar' }).click()
      await expect.poll(() => searchOf(log, '/api/admin/users/search').slice(mid)
        .some((s) => s.get('status') === 'passive' && s.get('source') === 'LDAP' && s.get('loginFrom') === '2026-10-01')).toBe(true)
      await expect(tab.locator('[data-testid="um-chips"] [data-chip="status"]')).toBeVisible()
    })
  })
}
