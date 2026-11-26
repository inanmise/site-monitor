// H paketi (2026-11-26) — yönetim / oturum düzeltmelerinin ekranları, gerçek tarayıcıda üç boyutta (390 / 768 dokunmatik,
// 1280):
//  - Eskalasyon kişileri: silinmiş kullanıcıya bağlı kişi (`deactivated_reason: USER_DELETED` ya da eski veride
//    `user_deleted: true`) "Kullanıcı silindi" rozetiyle (sarar, kesilmez); kapsam panosunda etkin SAYILMAZ (takım boşlukta).
//  - Takım üyeleri penceresi → Eskalasyon kişileri: silinmiş kullanıcıya bağlı kişi "Kullanıcı silindi" rozeti (sarar).
//  - Denetim görüntüleyici: 409 AUDIT_VERIFY_INCOMPLETE / AUDIT_VERIFY_RUNNING → sunucu metni + uyarı; yeşil "sağlam" ya da
//    kırmızı "bozuk" asla; RUNNING sonrası yeniden doğrulama başarılıysa uyarı kalkar, rozet yeşil.
//  - Yönetim özeti: tek global yönetici uyarısı "global yönetici" metniyle (kapsamlı ADMIN sayılmaz), Kullanıcılar'a süzgeçli.
//  - Sistem Sağlığı: denetim yedek dosyasında bekleyen kayıt, yanıtlayan pod'un adıyla (satır + genel bant sebebi).
//  - Açılış sekmesi: seçenekler menü kuralıyla (Yönetici Özeti kapsamlı yöneticiye / AUDIT'e açık, USER'a kapalı; Kanal
//    Teknik Hata Raporları izin kapsamı `channel_reports: false` ise yok); kayıtlı açılış sekmesi aynı kuralla uygulanır;
//    izin okunamaz ya da asılı kalırsa en çok 5 sn sonra Pano'da kalınır, geç gelen izin kullanıcıyı taşımaz.
//  - Kodla giriş: 403 AD_ACCOUNT_DISABLED / AD_UNVERIFIED → sunucu metni formda; açılışta beni-hatırla 401 bu kodla →
//    giriş formu, `/?session=expired` döngüsü yok; "kullanıcıyı değiştir" → AD hatası temizlenir, yeni istek gider.
//  - Etkinliklerim: AD neden kodlarının kendi metinleri.
// API tümüyle mock — gerçek kişi / kurum adı yok.
import { test, expect } from '@playwright/test'
import { mockApi, PREFS } from './support/monitorMocks.js'
import { GATE_VIEWPORTS, expectLayout, expectInViewport, pageOverflow, json } from './support/gateChecks.js'
import { EN } from '../src/i18n/en.js'

const LONG_A = 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin (Teknoloji Servis Yönetimi Bölüm Başkanı)'
const LONG_B = 'Mehmet Emin Çelikkanat-Özdemiroğulları (Uygulama Geliştirme ve Entegrasyon Müdürü)'
const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi'

// ── Eskalasyon kişileri ──
const TEAMS = [
  { id: 1, name: LONG_TEAM, active: true, leader_id: 2, manager_id: 3, email: 'dijital@example.com' },
  { id: 2, name: 'Ödeme Sistemleri', active: true, leader_id: 2, manager_id: null, email: 'odeme@example.com' },
  { id: 3, name: 'Kurumsal İnternet Şubesi Altyapı Ekibi', active: true, leader_id: 3, manager_id: 3, email: 'kis@example.com' },
]
const USERS = [
  { id: 2, username: 'KULLANICI_A', display_name: LONG_A, email: 'a@example.com', system_role: 'USER', team_id: 1, team_ids: [1], active: true },
]
const STATS = {
  1: { members: 2, domains: 4, monitors: 6, contacts: 1, groups: 1 },
  2: { members: 1, domains: 2, monitors: 3, contacts: 1, groups: 1 },
  3: { members: 1, domains: 1, monitors: 1, contacts: 1, groups: 0 },
}
const CONTACTS = [
  // Bağlı kullanıcı silindi → sistem pasife aldı (yeni alan)
  { id: 14, user_id: 99, name: LONG_B, email: 'b@example.com', role: 'MANAGER', min_alert_level: 'CRITICAL', active: false, team_id: 1,
    deactivated_reason: 'USER_DELETED', user_deleted: true, user_active: false },
  // Eski veri: satır etkin görünüyor ama user_id artık kullanıcı değil → etkin SAYILMAZ
  { id: 15, user_id: 98, name: 'Eski Kayıt Kişisi', email: 'eski@example.com', role: 'PO', min_alert_level: 'CRITICAL', active: true, team_id: 3,
    user_deleted: true, user_active: false },
  // Sağlam kişi
  { id: 16, user_id: 2, name: LONG_A, email: 'a@example.com', role: 'PO', min_alert_level: 'CRITICAL', active: true, team_id: 2, user_active: true },
]

async function mockContacts(page) {
  await mockApi(page)
  await page.route((u) => new URL(u).pathname.startsWith('/api/admin/') || new URL(u).pathname.startsWith('/api/notification-groups'), async (route) => {
    const p = new URL(route.request().url()).pathname
    if (p === '/api/admin/teams') return route.fulfill(json({ success: true, data: TEAMS }))
    if (p === '/api/admin/users') return route.fulfill(json({ success: true, data: USERS }))
    if (p === '/api/admin/teams/stats') return route.fulfill(json({ success: true, data: STATS }))
    if (p === '/api/admin/contacts/all') return route.fulfill(json({ success: true, data: CONTACTS }))
    if (p === '/api/admin/contacts/webhook-status') return route.fulfill(json({ success: true, data: {} }))
    if (p === '/api/notification-groups') return route.fulfill(json({ success: true, data: { groups: [], writable_team_ids: ['1', '2', '3'], team_emails: {} } }))
    return route.fallback()
  })
}

// ── Sistem Sağlığı yükü (temiz) ──
const POD = 'site-monitor-prod-7d9f8c6b5-xk2lm'
const HEALTH = {
  scheduler: { running: false, next_run: '2026-11-26T05:00:00', last_run: '2026-11-26T04:00:00', active_domains: 12, instance_id: POD },
  build: { version: '20.226.0', commit: 'a1b2c3d', environment: 'prod', uptime_seconds: 3600, started_at: '2026-11-26T03:00:00' },
  served_by_pod: POD,
  lock: { held: false },
  pool: { active: 2, idle: 8, total: 10, waiting: 0, max_size: 30 },
  executor_pool: { queue_size: 0, queue_capacity: 5000, active_count: 1, pool_size: 20, core_pool_size: 20, max_pool_size: 50, completed_tasks: 10, caller_runs: 0, saturated: false },
  memory: { used_mb: 600, free_mb: 1448, total_mb: 2048, max_mb: 2048, used_pct: 29 },
  scan: { last_run: '2026-11-26T04:00:00', duration_ms: 12000, total: 12, warnings: 0, errors: 0 },
  scan_alarm: false, db_ms: 9,
  heartbeat: { last_heartbeat: '2026-11-26T04:59:00', minutes_since: 1, alarm: false, recent: [] },
  network: { alarm: false }, domain_expiry: { source: 'RDAP', alarm: false },
  weekly_availability: { enabled: true, last_run_failed: 0 }, cleanup: { alarm: false, never_run: false, hold_active: false },
}

for (const vp of GATE_VIEWPORTS) {
  test.describe(`H paketi yönetim — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('eskalasyon kişileri: "Kullanıcı silindi" rozeti sarar; silinmiş kullanıcının kişisi kapsamda etkin sayılmaz', async ({ page }) => {
      test.setTimeout(90_000)
      await mockContacts(page)
      await page.goto('/?tab=admin&g_tab=contacts')
      const row = (id) => page.locator(`[data-contact-id="${id}"]`).first()
      await expect(row(14)).toBeVisible({ timeout: 30_000 })
      const badge14 = row(14).locator('[data-slot="contact-user-deleted"]')
      await expect(badge14).toHaveText(EN['ec.userDeleted'])
      await expect(badge14).toHaveAttribute('title', EN['ec.userDeletedTip'])
      await expect(row(15).locator('[data-slot="contact-user-deleted"]')).toHaveCount(1)   // yalnız user_deleted
      await expect(row(16).locator('[data-slot="contact-user-deleted"]')).toHaveCount(0)
      await expect(row(14).locator('[data-slot="contact-user-inactive"]')).toHaveCount(0)   // "Pasif" değil
      // Rozet kesilmez: metin kutusuna sığar, hücresinden taşmaz (tablo kendi kabında kayar — hücre içi ölçüm)
      const fit = await badge14.evaluate((el) => {
        const cell = el.closest('td').getBoundingClientRect()
        const r = el.getBoundingClientRect()
        const cs = getComputedStyle(el)
        return { clipped: el.scrollWidth > el.clientWidth + 1, nowrap: cs.whiteSpace === 'nowrap', inside: r.right <= cell.right + 1 && r.left >= cell.left - 1 }
      })
      expect(fit, `silinmiş kişi rozeti @${vp.name}`).toEqual({ clipped: false, nowrap: false, inside: true })

      // Kapsam: takım 1 (kişisi silindi) ve takım 3 (eski veri, satır "etkin") BOŞLUKTA; takım 2 kapsanıyor
      const cov = page.locator('[data-slot="contacts-coverage"]')
      await expect(cov).toBeVisible()
      await expect(cov.locator('[data-slot="cov-row"][data-team="1"]').first()).toHaveAttribute('data-tone', 'gap')
      await expect(cov.locator('[data-slot="cov-row"][data-team="3"]').first()).toHaveAttribute('data-tone', 'gap')
      await expect(cov.locator('[data-slot="cov-row"][data-team="2"][data-tone="gap"]')).toHaveCount(0)
      expect(await pageOverflow(page)).toBeLessThanOrEqual(1)
    })

    for (const code of ['AUDIT_VERIFY_INCOMPLETE', 'AUDIT_VERIFY_RUNNING']) {
      test(`denetim doğrulaması 409 ${code}: sunucu metni + uyarı; yeşil / kırmızı yok`, async ({ page }) => {
        test.setTimeout(90_000)
        await mockApi(page)
        const body = code === 'AUDIT_VERIFY_INCOMPLETE'
          ? { success: false, code, error: 'Zincir doğrulaması 75 sn sınırına takıldı; kalan kayıtlar bu turda doğrulanmadı — zincir bozuk değil.',
            checked: 125000, checked_to_seq: 125400, unverified: 8800 }
          : { success: false, code, error: 'Bu sunucuda başka bir doğrulama sürüyor. Birkaç saniye sonra yeniden deneyin.' }
        await page.route((u) => new URL(u).pathname === '/api/admin/audit/integrity', (r) => r.fulfill(json(body, 409)))
        await page.goto('/?tab=system')
        const verify = page.getByRole('button', { name: /Bütünlüğü doğrula|Verify integrity/ })
        await verify.click()
        const notice = page.locator('[data-slot="audit-integrity-notice"]')
        await expect(notice).toBeVisible({ timeout: 15_000 })
        await expect(notice).toHaveAttribute('data-code', code)
        await expect(notice).toContainText(body.error)
        if (code === 'AUDIT_VERIFY_INCOMPLETE') await expect(notice.locator('[data-slot="audit-integrity-checked"]')).toContainText(/#125400/)
        await expect(page.locator('[data-integrity="error"]')).toHaveAttribute('data-tone', 'warning')
        await expect(page.getByText(/Chain intact|Zincir sağlam|Chain BROKEN|Zincir BOZUK/)).toHaveCount(0)
        await expectInViewport(notice, vp.width, 'doğrulama şeridi')
        await expectLayout(page, '[data-slot="audit-integrity-notice"]', { touch: vp.touch, label: `doğrulama şeridi @${vp.name}` })
      })
    }

    test('takım üyeleri penceresi: silinmiş kullanıcıya bağlı eskalasyon kişisi "Kullanıcı silindi" rozetiyle (sarar)', async ({ page }) => {
      test.setTimeout(90_000)
      await mockContacts(page)
      await page.route((u) => new URL(u).pathname === '/api/teams/1/members', (r) => r.fulfill(json({ success: true, data: {
        team: { id: 1, name: LONG_TEAM, email: 'dijital@example.com', active: true, leader_id: 2, leader_display_name: LONG_A },
        members: USERS, escalation_contacts: CONTACTS.filter((c) => c.id !== 15).map((c) => ({ ...c, team_id: 1 })),
      } })))
      await page.goto('/?tab=admin&g_tab=teams')
      await page.locator('[data-slot="team-badge"]').filter({ hasText: LONG_TEAM }).first().click({ timeout: 30_000 })
      const dlg = page.getByRole('dialog').filter({ hasText: LONG_TEAM }).last()
      await expect(dlg).toBeVisible({ timeout: 15_000 })
      await dlg.getByRole('tab', { name: /Escalation|Eskalasyon/ }).click()
      const deleted = dlg.locator('[data-slot="team-escalation-deleted"]')
      await expect(deleted).toHaveCount(1)
      await expect(deleted).toContainText(EN['ec.userDeleted'])
      await expect(deleted).toHaveAttribute('title', EN['ec.userDeletedTip'])
      const card = deleted.locator('xpath=ancestor::*[@data-slot="team-escalation-card"]')
      await expect(card).toHaveAttribute('data-inactive', 'true')
      await expect(card).toContainText(EN['ec.userDeletedTip'])
      await expect(card).toContainText(LONG_B)
      // Sağlam kişide rozet yok
      await expect(dlg.locator('[data-slot="team-escalation-card"]').filter({ hasText: 'a@example.com' }).locator('[data-slot="team-escalation-deleted"]')).toHaveCount(0)
      const fit = await deleted.evaluate((el) => {
        const c = el.closest('[data-slot="team-escalation-card"]').getBoundingClientRect()
        const r = el.getBoundingClientRect()
        return { clipped: el.scrollWidth > el.clientWidth + 1, nowrap: getComputedStyle(el).whiteSpace === 'nowrap', inside: r.left >= c.left - 1 && r.right <= c.right + 1 }
      })
      expect(fit, `silinmiş kişi rozeti (takım penceresi) @${vp.name}`).toEqual({ clipped: false, nowrap: false, inside: true })
      await expectInViewport(dlg, vp.width, 'takım üyeleri penceresi')
      await expectLayout(page, '[role="dialog"]', { touch: vp.touch, label: `takım üyeleri eskalasyon @${vp.name}` })
    })

    test('denetim doğrulaması: 409 AUDIT_VERIFY_RUNNING → yeniden doğrula → başarı: uyarı kalkar, rozet yeşil', async ({ page }) => {
      test.setTimeout(90_000)
      await mockApi(page)
      let calls = 0
      await page.route((u) => new URL(u).pathname === '/api/admin/audit/integrity', (r) => {
        calls++
        if (calls === 1) return r.fulfill(json({ success: false, code: 'AUDIT_VERIFY_RUNNING', error: 'Bu sunucuda başka bir doğrulama sürüyor. Birkaç saniye sonra yeniden deneyin.' }, 409))
        return r.fulfill(json({ success: true, data: { ok: true, checked: 12450 } }))
      })
      await page.goto('/?tab=system')
      const verify = page.getByRole('button', { name: /Bütünlüğü doğrula|Verify integrity/ })
      await verify.click()
      const notice = page.locator('[data-slot="audit-integrity-notice"]')
      await expect(notice).toBeVisible({ timeout: 15_000 })
      await expect(notice).toHaveAttribute('data-code', 'AUDIT_VERIFY_RUNNING')
      await expect(page.locator('[data-integrity="error"]')).toHaveAttribute('data-tone', 'warning')
      if (vp.touch) expect((await verify.boundingBox()).height, 'doğrula düğmesi dokunma yüksekliği').toBeGreaterThanOrEqual(39.5)
      // Yeniden dene → başarı
      await verify.click()
      await expect.poll(() => calls, { timeout: 15_000 }).toBe(2)
      await expect(notice).toHaveCount(0)
      await expect(page.locator('[data-integrity="error"]')).toHaveCount(0)
      const ok = page.locator('[data-integrity="ok"]')
      await expect(ok).toBeVisible()
      await expect(ok).toHaveAttribute('data-tone', 'success')
      await expect(ok).toContainText(EN['audit.integrityOk'])
      await expect(ok).toContainText('12450')
      expect(await pageOverflow(page)).toBeLessThanOrEqual(1)
    })

    test('yönetim özeti: tek global yönetici uyarısı "global yönetici" metniyle; tıklayınca Kullanıcılar ADMIN süzgeçli', async ({ page }) => {
      test.setTimeout(90_000)
      await mockApi(page)
      await page.route((u) => new URL(u).pathname === '/api/admin/overview', (r) => r.fulfill(json({ success: true, data: {
        dormant_days: 90,
        counts: { teams: 3, teams_active: 3, users: 12, users_active: 10, admins: 1, contacts: 4, contacts_webhook: 1, groups: 2,
          threshold_tiers: 1, threshold_default: { warning: 30, high: 15, critical: 7 } },
        warnings: [{ code: 'SINGLE_ADMIN', count: 1, tab: 'users' }, { code: 'TEAM_NO_LEADER', count: 1, tab: 'teams' }],
      } })))
      await page.goto('/?tab=admin')
      const tile = page.locator('[data-warning="SINGLE_ADMIN"]')
      await expect(tile).toBeVisible({ timeout: 30_000 })
      await expect(tile).toContainText(EN['aov.w.SINGLE_ADMIN'])
      await expect(tile).toHaveAttribute('data-tone', 'danger')
      await expect(tile).not.toContainText(/Only one active ADMIN/)
      // Uzun metin sarar, kutucuktan taşmaz
      const fit = await tile.evaluate((el) => {
        const text = el.querySelector('span span')
        const r = el.getBoundingClientRect()
        const tr = text.getBoundingClientRect()
        return { clipped: text.scrollWidth > text.clientWidth + 1, inside: tr.right <= r.right + 1 && tr.left >= r.left - 1 }
      })
      expect(fit, `tek yönetici uyarısı @${vp.name}`).toEqual({ clipped: false, inside: true })
      await expectLayout(page, '[data-slot="overview-health"]', { touch: vp.touch, label: `yönetim özeti uyarısı @${vp.name}` })
      await tile.click()
      await expect.poll(() => new URL(page.url()).searchParams.get('g_tab'), { timeout: 10_000 }).toBe('users')
      expect(new URL(page.url()).searchParams.get('g_role')).toBe('ADMIN')
    })

    test('Sistem Sağlığı: denetim yedek dosyasında bekleyen kayıt pod adıyla (satır + genel bant)', async ({ page }) => {
      test.setTimeout(90_000)
      await mockApi(page)
      await page.route((u) => new URL(u).pathname === '/api/admin/system', (r) => r.fulfill(json({ success: true,
        data: { ...HEALTH, audit_fallback_pending: 3, audit_fallback_pending_pod: POD } })))
      await page.goto('/?tab=health&sec=sys')
      const row = page.locator('[data-slot="audit-fallback"]')
      await expect(row).toBeVisible({ timeout: 30_000 })
      await expect(row).toHaveAttribute('data-pending', '3')
      await expect(row.locator('[data-slot="audit-fallback-pod"]')).toContainText(POD)
      const reason = EN['health.reasonAuditFallback'].replace('{0}', '3')
      await expect(page.getByText(`${reason} (pod: ${POD})`).first()).toBeVisible()
      await expectLayout(page, '[data-slot="audit-fallback"]', { touch: vp.touch, label: `yedek dosya satırı @${vp.name}` })
      expect(await pageOverflow(page)).toBeLessThanOrEqual(1)
    })

    test('Sistem Sağlığı: sayılamadı (-1) → uyarı "sayılamadı", asla temiz', async ({ page }) => {
      test.setTimeout(90_000)
      await mockApi(page)
      await page.route((u) => new URL(u).pathname === '/api/admin/system', (r) => r.fulfill(json({ success: true,
        data: { ...HEALTH, audit_fallback_pending: -1, audit_fallback_pending_pod: POD } })))
      await page.goto('/?tab=health&sec=sys')
      const row = page.locator('[data-slot="audit-fallback"]')
      await expect(row).toBeVisible({ timeout: 30_000 })
      await expect(row).toHaveAttribute('data-pending', 'unknown')
      await expect(row).toContainText(EN['health.auditFallbackUnknown'])
      await expect(page.getByText(`${EN['health.reasonAuditFallbackUnknown']} (pod: ${POD})`).first()).toBeVisible()
    })
  })
}

// ── Açılış sekmesi ──
const permsRoute = (scope) => (route) => route.fulfill(json({ success: true, data: {}, scope }))

for (const vp of GATE_VIEWPORTS) {
  test.describe(`H paketi açılış sekmesi — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('kapsamlı yönetici: kayıtlı "Yönetici Özeti" açılır; Etkinliklerim seçeneklerinde var', async ({ page }) => {
      test.setTimeout(90_000)
      await mockApi(page, { role: 'ADMIN', globalAdmin: false, prefs: { ...PREFS, landingTab: 'executive' } })
      await page.goto('/')
      await expect(page).toHaveURL(/tab=executive/, { timeout: 30_000 })
      await page.goto('/?tab=myactivity')
      const card = page.locator('[data-slot="landing-tab"]')
      await expect(card).toBeVisible({ timeout: 30_000 })
      await expect(card.locator('option[value="executive"]')).toHaveCount(1)
      await expectLayout(page, '[data-slot="landing-tab"]', { touch: vp.touch, label: `açılış sekmesi kartı @${vp.name}` })
    })

    test('USER: "Yönetici Özeti" seçeneği yok, kayıtlı değer uygulanmaz (Pano)', async ({ page }) => {
      test.setTimeout(90_000)
      await mockApi(page, { role: 'USER', globalAdmin: false, prefs: { ...PREFS, landingTab: 'executive' } })
      await page.goto('/')
      await page.locator('.app-main').waitFor({ timeout: 30_000 })
      await page.waitForTimeout(1500)
      await expect(page).not.toHaveURL(/tab=executive/)
      await page.goto('/?tab=myactivity')
      const card = page.locator('[data-slot="landing-tab"]')
      await expect(card).toBeVisible({ timeout: 30_000 })
      await expect(card.locator('option[value="executive"]')).toHaveCount(0)
    })

    for (const visible of [false, true]) {
      test(`Kanal Teknik Hata Raporları: kapsam ${visible ? 'açık → açılır' : 'kapalı → Pano, seçenek yok'}`, async ({ page }) => {
        test.setTimeout(90_000)
        await mockApi(page, { prefs: { ...PREFS, landingTab: 'channelreports' } })
        await page.route((u) => new URL(u).pathname === '/api/me/permissions', permsRoute({ channel_reports: visible }))
        await page.goto('/')
        await page.locator('.app-main').waitFor({ timeout: 30_000 })
        if (visible) await expect(page).toHaveURL(/tab=channelreports/, { timeout: 30_000 })
        else {
          await page.waitForTimeout(1500)
          await expect(page).not.toHaveURL(/tab=channelreports/)
        }
        await page.goto('/?tab=myactivity')
        const card = page.locator('[data-slot="landing-tab"]')
        await expect(card).toBeVisible({ timeout: 30_000 })
        await expect(card.locator('option[value="channelreports"]')).toHaveCount(visible ? 1 : 0)
      })
    }

    for (const mode of ['fail', 'slow']) {
      test(`Kanal Teknik Hata Raporları: izin ${mode === 'fail' ? 'okunamadı (500)' : 'asılı kaldı'} → en çok 5 sn sonra Pano, geç izin taşımaz`, async ({ page }) => {
        test.setTimeout(90_000)
        await mockApi(page, { prefs: { ...PREFS, landingTab: 'channelreports' } })
        let release = null
        await page.route((u) => new URL(u).pathname === '/api/me/permissions', async (route) => {
          if (mode === 'fail') return route.fulfill(json({ success: false, error: 'İzinler okunamadı' }, 500))
          // İlk istek asılı kalır (sunucu yanıt vermiyor); sonradan "görünür" der — karar zaten verilmiş olmalı
          await new Promise((r) => { release = r })
          return route.fulfill(json({ success: true, data: {}, scope: { channel_reports: true } }))
        })
        const started = Date.now()
        await page.goto('/')
        await page.locator('.app-main').waitFor({ timeout: 30_000 })
        await page.waitForTimeout(6_500)
        expect(Date.now() - started, 'bekleme sınırı').toBeGreaterThan(5_000)
        await expect(page).not.toHaveURL(/tab=channelreports/)
        // Pano gerçekten çizildi (karar verildi, sonsuz bekleme yok)
        await expect(page.locator('[data-slot="dash-add-domain"]')).toBeVisible({ timeout: 15_000 })
        if (release) {
          release()
          await page.waitForTimeout(1_500)
          await expect(page).not.toHaveURL(/tab=channelreports/)
        }
        expect(await pageOverflow(page)).toBeLessThanOrEqual(1)
      })
    }
  })
}

// ── Kodla giriş + beni-hatırla (oturumsuz giriş sayfası) ──
const METHODS = { success: true, ldap: true, otp_push: true, otp_email: false, push_ttl: 45, resend_cooldown: 30 }
const CHALLENGE = { success: true, challenge_id: '11111111-2222-3333-4444-555555555555', channel: 'push', expires_in: 45, resend_in: 30 }

async function mockLogin(page, { meStatus = 401, meCode = null, verify }) {
  await page.addInitScript(() => { try { localStorage.setItem('site-monitor-lang', 'en') } catch { /* yoksay */ } })
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const p = new URL(route.request().url()).pathname
    if (p === '/api/me') return route.fulfill(json({ success: false, ...(meCode ? { code: meCode, error_code: meCode } : {}), error: 'Unauthorized' }, meStatus))
    if (p === '/api/public/login-methods') return route.fulfill(json(METHODS))
    if (p === '/api/public-stats') return route.fulfill(json({ success: true, data: { monitored_targets: 12, availability_pct: 99.9 } }))
    if (p === '/api/public/system-maintenance') return route.fulfill(json({ success: true, data: { state: 'none' } }))
    if (p === '/api/login/otp/request') return route.fulfill(json(CHALLENGE))
    if (p === '/api/login/otp/verify') return route.fulfill(verify)
    return route.fulfill(json({ success: true, data: [] }))
  })
}

for (const vp of GATE_VIEWPORTS) {
  test.describe(`H paketi AD reddi — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    for (const code of ['AD_ACCOUNT_DISABLED', 'AD_UNVERIFIED']) {
      test(`kodla giriş 403 ${code}: sunucu metni formda, kod kutusu kapalı, parolayla giriş önerilir`, async ({ page }) => {
        test.setTimeout(90_000)
        const text = code === 'AD_ACCOUNT_DISABLED'
          ? 'Your account is disabled in Active Directory. Contact your administrator.'
          : 'Active Directory could not be reached; sign in with your username and password.'
        await mockLogin(page, { meCode: code, verify: json({ success: false, code, error_code: code, error: text }, 403) })
        await page.goto('/')
        await expect(page.locator('#lp-pass')).toBeVisible({ timeout: 30_000 })
        // Beni-hatırla 401 (AD kodlu) → giriş formu; oturum süresi doldu döngüsü YOK
        await page.waitForTimeout(800)
        expect(new URL(page.url()).searchParams.get('session')).toBeNull()

        await page.getByRole('button', { name: /Get a code by push/ }).click()
        await page.getByRole('textbox', { name: /^Username$/ }).fill('ldapuser')
        await page.locator('[data-slot="otp-send"]').click()
        await expect(page.locator('[data-slot="otp-flow"]')).toHaveAttribute('data-step', 'code', { timeout: 15_000 })
        await page.getByRole('textbox', { name: /6-digit code/ }).fill('123456')
        const err = page.locator('[data-slot="otp-error"]')
        await expect(err).toBeVisible({ timeout: 15_000 })
        await expect(err).toHaveAttribute('data-kind', code === 'AD_ACCOUNT_DISABLED' ? 'adDisabled' : 'adUnverified')
        await expect(err).toContainText(text)
        await expect(page.locator('[data-slot="otp-resend"]')).toHaveCount(0)
        await expectLayout(page, '[data-slot="otp-flow"]', { touch: vp.touch, label: `AD reddi @${vp.name}` })
        expect(await pageOverflow(page)).toBeLessThanOrEqual(1)
        await page.locator('[data-slot="otp-back"]').first().click()
        await expect(page.locator('#lp-pass')).toBeVisible()
      })
    }

    test('kodla giriş AD reddi → "kullanıcıyı değiştir": hata temizlenir, yeni kullanıcı adıyla YENİ istek gider', async ({ page }) => {
      test.setTimeout(90_000)
      const text = 'Your account is disabled in Active Directory. Contact your administrator.'
      const requests = []
      await mockLogin(page, { verify: json({ success: false, code: 'AD_ACCOUNT_DISABLED', error_code: 'AD_ACCOUNT_DISABLED', error: text }, 403) })
      page.on('request', (req) => {
        if (new URL(req.url()).pathname === '/api/login/otp/request') {
          try { requests.push(JSON.parse(req.postData() || '{}')) } catch { requests.push({}) }
        }
      })
      await page.goto('/')
      await expect(page.locator('#lp-pass')).toBeVisible({ timeout: 30_000 })
      await page.getByRole('button', { name: /Get a code by push/ }).click()
      await page.getByRole('textbox', { name: /^Username$/ }).fill('ldapuser')
      await page.locator('[data-slot="otp-send"]').click()
      const flow = page.locator('[data-slot="otp-flow"]')
      await expect(flow).toHaveAttribute('data-step', 'code', { timeout: 15_000 })
      await page.getByRole('textbox', { name: /6-digit code/ }).fill('123456')
      await expect(page.locator('[data-slot="otp-error"]')).toContainText(text, { timeout: 15_000 })

      const change = page.locator('[data-slot="otp-change-user"]')
      if (vp.touch) expect((await change.boundingBox()).height, '"kullanıcıyı değiştir" dokunma yüksekliği').toBeGreaterThanOrEqual(39.5)
      await change.click()
      await expect(flow).toHaveAttribute('data-step', 'request')
      await expect(page.locator('[data-slot="otp-error"]')).toHaveCount(0)
      const user = page.getByRole('textbox', { name: /^Username$/ })
      await user.fill('baskakullanici')
      await page.locator('[data-slot="otp-send"]').click()
      await expect.poll(() => requests.length, { timeout: 15_000 }).toBe(2)
      expect(requests[1]).toMatchObject({ username: 'baskakullanici' })
      await expect(flow).toHaveAttribute('data-step', 'code', { timeout: 15_000 })
      await expect(page.locator('[data-slot="otp-error"]')).toHaveCount(0)
      await expect(page.locator('[data-slot="otp-resend"]')).toBeVisible()
      await expectLayout(page, '[data-slot="otp-flow"]', { touch: vp.touch, label: `AD reddi sonrası yeni istek @${vp.name}` })
      expect(await pageOverflow(page)).toBeLessThanOrEqual(1)
    })

    test('Etkinliklerim: AD neden kodlarının kendi metinleri (genel "reddedildi" değil)', async ({ page }) => {
      test.setTimeout(90_000)
      await mockApi(page, { role: 'USER', globalAdmin: false })
      const now = Date.now()
      const at = (ms) => new Date(now - ms).toISOString().slice(0, 19)
      const rows = [
        { id: 1, event_type: 'LOGIN_FAILED', outcome: 'BLOCKED', event_time: at(60_000), actor: 'demo', ip_address: '203.0.113.24',
          failure_reason: "AD_ACCOUNT_DISABLED: hesap AD'de devre dışı ya da dizinde yok (REMEMBER_ME)", detail: '{"method":"REMEMBER_ME"}' },
        { id: 2, event_type: 'LOGIN_FAILED', outcome: 'BLOCKED', event_time: at(120_000), actor: 'demo', ip_address: '203.0.113.24',
          failure_reason: "AD_UNVERIFIED: AD'ye ulaşılamadı (OTP_PUSH)", detail: '{"method":"OTP_PUSH"}' },
      ]
      await page.route((u) => new URL(u).pathname === '/api/me/audit', (r) => r.fulfill(json({ success: true, data: rows, total: rows.length, page: 0, total_pages: 1 })))
      await page.goto('/?tab=myactivity')
      const entries = page.locator('[data-slot="activity-entry"][data-event="LOGIN_FAILED"]')
      await expect(entries).toHaveCount(2, { timeout: 30_000 })
      for (const [i, key] of [[0, 'myact.reason.adDisabled'], [1, 'myact.reason.adUnverified']]) {
        await entries.nth(i).getByRole('button').first().click()
        const detail = entries.nth(i).locator('[data-slot="activity-detail"]')
        await expect(detail).toContainText(EN[key])
        await expectLayout(page, '[data-slot="activity-detail"]', { touch: vp.touch, label: `AD nedeni ${key} @${vp.name}` })
        await entries.nth(i).getByRole('button').first().click()
      }
      expect(await pageOverflow(page)).toBeLessThanOrEqual(1)
    })
  })
}
