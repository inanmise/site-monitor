import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

// Giriş sonrası eksikler penceresi (2026-11-15, kullanıcı isteği: "girişten sonra profil, takım, izleme ve envanter
// eksiklerini bir pencerede göster; daha sonra hatırlat / bir daha gösterme; profil menüsünden her zaman açılsın").
// Üç boyutta (390 telefon dokunmatik, 768 tablet dokunmatik, 1280 dizüstü): pencere kendiliğinden açılır, ekrana sığar,
// yatay taşmaz, uzun takım / izleme adları kesilmez, dokunmatikte hedefler ≥ 40 px; "Düzelt" kaydın ekranına götürür;
// "Daha sonra hatırlat" kapatır, kullanıcı menüsündeki "Eksiklerim" yeniden açar.
const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

const DIALOG = '[data-slot="gaps-dialog"]'

/** Görünür düğme / sekme içinde 40 px'ten kısa olanlar (dokunmatik kuralı). */
async function smallTargets(root) {
  return root.evaluate((el) => {
    const out = []
    for (const b of el.querySelectorAll('button, a[href], [role="tab"]')) {
      const r = b.getBoundingClientRect()
      if (r.width === 0 || r.height === 0) continue
      const cs = getComputedStyle(b)
      if (cs.visibility === 'hidden' || cs.display === 'none') continue
      if (r.height < 39.5) out.push(`${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)} ${Math.round(r.height)}`)
    }
    return out
  })
}

/** Kesilen metin: kendi kutusundan taşan ya da üç nokta ile kırpılan öğe. */
async function clipped(root) {
  return root.evaluate((el) => {
    const out = []
    for (const n of el.querySelectorAll('span, p, h2, li')) {
      if (n.closest('.sr-only')) continue                     // ekran okuyucu metni (1 px kutu) ölçülmez
      if (n.scrollWidth > n.clientWidth + 1 && getComputedStyle(n).overflow !== 'visible') out.push((n.textContent || '').trim().slice(0, 40))
      if (getComputedStyle(n).textOverflow === 'ellipsis') out.push(`ellipsis:${(n.textContent || '').trim().slice(0, 40)}`)
    }
    return out
  })
}

for (const vp of VIEWPORTS) {
  test(`eksikler penceresi (${vp.name} ${vp.width}): kendiliğinden açılır, sığar, kesilmez, düzelt / sonra hatırlat / menüden aç`, async ({ browser }) => {
    test.setTimeout(120_000)
    const context = await browser.newContext({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })
    const page = await context.newPage()
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await mockApi(page, { role: 'USER', globalAdmin: false, teamIds: [1], perms: { 'data_quality.view': { view: true } }, gaps: true })
    // Profilde e-posta ve telefon YOK (sonradan kaydedilen yol önce eşleşir)
    await page.route((u) => new URL(u).pathname === '/api/me', (route) => route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({
        success: true, username: 'demo', system_role: 'USER', global_admin: false, weekly_reports_visible: true,
        team_id: 1, team_name: 'Takım A', team_ids: [1], team_names: ['Takım A'], manage_team_ids: [],
        email: null, phone: '', tour: { status: 'dismissed', version: 99 },
      }),
    }))
    await page.goto('/?tab=dashboard')

    const dialog = page.locator(DIALOG)
    await expect(dialog).toHaveAttribute('data-status', 'ready', { timeout: 20_000 })
    const counts = Object.fromEntries(await page.locator('[data-slot="gaps-tab"]').evaluateAll(
      (els) => els.map((e) => [e.dataset.category, e.dataset.count])))
    expect(counts.profile).toBe('2')
    expect(Number(counts.team)).toBeGreaterThanOrEqual(1)
    expect(Number(counts.monitors)).toBeGreaterThanOrEqual(1)
    expect(Number(counts.inventory)).toBeGreaterThanOrEqual(1)
    await expect(dialog).not.toContainText(/gaps\.[a-zA-Z]|dq\.rule\.|undefined|NaN/)

    // Ekrana sığar (telefonda tam ekran), yatay kaymaz
    const box = await dialog.boundingBox()
    expect(box.x).toBeGreaterThanOrEqual(-1)
    expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
    expect(box.y + box.height).toBeLessThanOrEqual(vp.height + 1)
    const overflow = await dialog.evaluate((el) => {
      let worst = 0
      for (const n of el.querySelectorAll('*')) {
        const r = n.getBoundingClientRect()
        if (r.width > 0) worst = Math.max(worst, r.right - window.innerWidth)
      }
      return worst
    })
    expect(overflow, 'pencere içeriği görünüm alanını aşıyor').toBeLessThanOrEqual(1)

    // Sekme satırı sarar ve içeriğin ÜSTÜNE binmez (shadcn listesi sabit 36 px idi): liste paneli örtmez
    // (liste kutusu değil SEKMELERİN alt kenarı: sabit yükseklikli listede ikinci satır kutudan taşıp paneli örterdi)
    const tabsBottom = Math.max(...await page.locator('[data-slot="gaps-tab"]').evaluateAll((els) => els.map((e) => e.getBoundingClientRect().bottom)))
    const panelBox = await page.locator('[data-slot="gaps-panel"][data-state="active"]').boundingBox()
    expect(panelBox.y, 'sekmeler paneli örtüyor').toBeGreaterThanOrEqual(tabsBottom - 1)
    // (scrollHeight kullanılmaz: shadcn sekmesinin görünmez alt çizgisi ::after kutunun altına taşar)
    const tabOverflow = await page.locator('[data-slot="gaps-tab"]').evaluateAll((els) => els.filter((e) => {
      const r = e.getBoundingClientRect()
      return [...e.children].some((k) => { const c = k.getBoundingClientRect(); return c.top < r.top - 0.5 || c.bottom > r.bottom + 0.5 || c.right > r.right + 0.5 })
    }).length)
    expect(tabOverflow, 'sekme metni kendi kutusundan taşıyor').toBe(0)

    // İzlemeler sekmesi: uzun izleme adı sarar, "Düzelt" düğmeleri var
    await page.locator('[data-slot="gaps-tab"][data-category="monitors"]').click()
    const panel = page.locator('[data-slot="gaps-panel"][data-category="monitors"]')
    await expect(panel.locator('[data-slot="gaps-subitem"]').first()).toBeVisible()
    await expect(panel).toContainText('çok uzun izleme adı örneği — taşmamalı')
    for (const cat of ['profile', 'team', 'monitors', 'inventory']) {
      await page.locator(`[data-slot="gaps-tab"][data-category="${cat}"]`).click()
      const bad = await clipped(dialog)
      expect(bad, `${cat}: kesilen metin ${bad.join(' | ')}`).toEqual([])
      if (vp.touch) {
        const small = await smallTargets(dialog)
        expect(small, `${cat}: 40 px altı hedefler: ${small.join(', ')}`).toEqual([])
      }
    }
    await page.screenshot({ path: `test-results/login-gaps-${vp.name}.png` })

    // "Daha sonra hatırlat": kapanır, 1 gün sonrası saklanır
    await page.locator('[data-slot="gaps-snooze"]').click()
    await expect(dialog).toHaveCount(0)
    const stored = await page.evaluate(() => JSON.parse(localStorage.getItem('sm.gaps.state') || 'null'))
    expect(stored?.snoozeUntil).toBeGreaterThan(Date.now() + 23 * 3600_000)

    // Kullanıcı menüsü → "Eksiklerim": her zaman açar (telefonda menü çekmecenin içinde, alt sayfa olarak açılır)
    if (vp.width < 768) await page.locator('[data-sidebar="trigger"]').first().click()
    // (seçici dizesine görünürlük sözde sınıfı yazılmaz: Tailwind onu sınıf adayı sanıp bozuk CSS üretir — filter kullanılır)
    await page.locator('[data-slot="user-menu-trigger"]').filter({ visible: true }).first().click()
    await page.locator('[data-slot="user-menu-gaps"]').filter({ visible: true }).first().click()
    await expect(dialog).toHaveAttribute('data-status', 'ready', { timeout: 20_000 })

    // "Düzelt": izlemenin ekranına gider, pencere kapanır
    await page.locator('[data-slot="gaps-tab"][data-category="monitors"]').click()
    await panel.locator('[data-slot="gaps-fix"]').first().click()
    await expect(dialog).toHaveCount(0)
    await expect(page).toHaveURL(/tab=http/)
    await context.close()
  })
}

// Zenginleştirme (2026-11-15, A–F paketleri): veri kalitesi dışı takım eksikleri, bekleyen işler, takıma göre görünüm,
// tek eksiği gizleme, "1 hafta sonra hatırlat" ve yerinde düzeltme düğmesi — gerçek uzunlukta AD adlarıyla, üç boyutta.
const LONG_TEAM = 'Dijital Kanallar ve Mobil Bankacılık Altyapı Yönetimi (Kurumsal Teknoloji / Uygulama Destek)'
const LONG_PERSON = 'Ayşegül Karaosmanoğlu-Yıldırım (Bilgi Teknolojileri / Kıdemli Sistem Uzmanı)'
const json = (body) => ({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })

for (const vp of VIEWPORTS) {
  test(`eksikler penceresi zenginleştirme (${vp.name} ${vp.width}): işler, takıma göre, gizle, 1 hafta, yerinde düzelt`, async ({ browser }) => {
    test.setTimeout(120_000)
    const context = await browser.newContext({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })
    const page = await context.newPage()
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await mockApi(page, {
      role: 'USER', globalAdmin: false, teamIds: [1], gaps: true,
      perms: {
        'data_quality.view': { view: true }, 'notification.groups': { view: true, edit: true }, 'oncall.read': { view: true },
        'oncall.manage': { view: true, edit: true }, 'alerts.forwards': { view: true }, 'tech_error_reports.read': { view: true },
        'incidents.view': { view: true }, 'contacts.crud': { view: true, edit: true },
      },
    })
    const path = (u) => new URL(u).pathname
    await page.route((u) => path(u) === '/api/me', (route) => route.fulfill(json({
      success: true, username: 'demo', user_id: 'u-demo', system_role: 'USER', global_admin: false, weekly_reports_visible: true,
      team_id: 1, team_name: LONG_TEAM, team_ids: [1], team_names: [LONG_TEAM], manage_team_ids: [1], noc_operator: false,
      email: 'demo@kurum.com.tr', phone: '0532 123', tour: { status: 'dismissed', version: 99 },
    })))
    await page.route((u) => path(u) === '/api/public/login-methods', (route) => route.fulfill(json({ success: true, otp_push: true, push_requires_phone: true })))
    await page.route((u) => path(u) === '/api/notification-groups', (route) => route.fulfill(json({ success: true, data: { groups: [
      { id: 31, team_id: 1, name: 'Nöbetçi operasyon ve uygulama destek ekibi bildirim listesi', active: true, is_default: true,
        emails: ['a@kurum.com.tr', 'b@kurum.com.tr'], inactive_emails: ['a@kurum.com.tr', 'b@kurum.com.tr'] }] } })))
    await page.route((u) => path(u) === '/api/oncall/teams/1', (route) => route.fulfill(json({ success: true, data: { enabled: true,
      members: [{ user_id: 'u-x', username: 'aysegul', name: LONG_PERSON, active: false, has_email: true },
        { user_id: 'u-demo', username: 'demo', name: 'Demo Kullanıcı', active: true, has_email: true }],
      current: { user_id: 'u-x', active: false, has_email: true } } })))
    await page.route((u) => path(u) === '/api/alert-forwards', (route) => route.fulfill(json({ success: true, data: { items: [
      { id: 5, owner_team_id: 1, recipient_team_name: 'Ağ ve Güvenlik Operasyonları Merkezi (Kurumsal Altyapı)', recipient_team_active: false,
        enabled: true, state: 'recipient_inactive', recipient_address_count: 1, scope_kind: 'ALL', recipient_kind: 'TEAM' }] } })))
    await page.route((u) => path(u) === '/api/tech-error-reports/overview', (route) => route.fulfill(json({ success: true, data: {
      teams: [{ id: 1, name: LONG_TEAM, approver_count: 0, can_approve: false }],
      channels: [
        { id: 10, team_id: 1, name: 'Mobil Bankacılık Uygulaması (iOS ve Android) Teknik Hata Raporu', active: true, can_report: true,
          approval_required: true, period_report: null },
        { id: 11, team_id: 1, name: 'İnternet Şubesi', active: true, can_report: true, approval_required: false,
          period_report: { id: 40, status: 'draft' } }] } })))
    await page.route((u) => path(u) === '/api/incidents/actions', (route) => route.fulfill(json({ success: true, data: { total: 7, items: [
      { id: 1, title: 'Yedekleme betiğinin zaman aşımı değerini artır ve izleme ekle', due_date: '2026-11-01', incident_title: 'Gece yedeklemesi yarıda kaldı' }] } })))
    await page.route((u) => path(u) === '/api/teams/directory', (route) => route.fulfill(json({ success: true, data: [
      { id: 1, name: LONG_TEAM, leader_id: 'u-lead', leader_display_name: 'Mehmet Ali Demirtaş (Dijital Kanallar / Takım Lideri)' }] })))
    await page.goto('/?tab=dashboard')

    const dialog = page.locator(DIALOG)
    await expect(dialog).toHaveAttribute('data-status', 'ready', { timeout: 20_000 })
    await expect(dialog).not.toContainText(/gaps\.[a-zA-Z]|dq\.rule\.|undefined|NaN/)
    const counts = Object.fromEntries(await page.locator('[data-slot="gaps-tab"]').evaluateAll(
      (els) => els.map((e) => [e.dataset.category, e.dataset.count])))
    expect(Object.keys(counts)).toEqual(['profile', 'team', 'monitors', 'inventory', 'tasks'])
    expect(Number(counts.tasks)).toBe(2)                       // kanal raporu + gecikmiş aksiyonlar
    // Telefon geçersiz + nöbetçi + kodla girişte telefon zorunlu → YÜKSEK; lidere sorma notu
    await page.locator('[data-slot="gaps-tab"][data-category="profile"]').click()
    const phone = dialog.locator('[data-slot="gaps-item"][data-code="PROFILE_PHONE_INVALID"]')
    await expect(phone.locator('[data-severity]').first()).toHaveAttribute('data-severity', 'HIGH')
    await expect(phone.locator('[data-slot="gaps-ask"]')).toContainText('Mehmet Ali Demirtaş')

    for (const cat of ['profile', 'team', 'tasks']) {
      await page.locator(`[data-slot="gaps-tab"][data-category="${cat}"]`).click()
      const bad = await clipped(dialog)
      expect(bad, `${cat}: kesilen metin ${bad.join(' | ')}`).toEqual([])
      if (vp.touch) {
        const small = await smallTargets(dialog)
        expect(small, `${cat}: 40 px altı hedefler: ${small.join(', ')}`).toEqual([])
      }
    }
    // Takım sekmesi: veri kalitesi dışı eksikler ve yerinde düzeltme düğmeleri (takımı yönetiyor + yetkili)
    await page.locator('[data-slot="gaps-tab"][data-category="team"]').click()
    for (const code of ['TEAM_GROUP_ALL_PASSIVE', 'TEAM_ONCALL_MEMBERS', 'TEAM_FORWARD_BROKEN', 'TEAM_CHANNEL_NO_APPROVER']) {
      await expect(dialog.locator(`[data-slot="gaps-item"][data-code="${code}"]`)).toBeVisible()
    }
    await expect(dialog.locator('[data-slot="gaps-item"][data-code="TEAM_ONCALL_MEMBERS"]')).toContainText(LONG_PERSON)
    await expect(dialog.locator('[data-slot="gaps-item"][data-code="TEAM_GROUP_ALL_PASSIVE"] [data-slot="gaps-inline-fix"]')).toBeVisible()

    // Takıma göre görünüm: takım başlığı (uzun ad sarar), "Size atanmış işler" grubu
    await dialog.getByRole('button', { name: /^(By team|Takıma göre)$/ }).click()
    await expect(dialog.locator('[data-slot="gaps-team-view"]')).toBeVisible()
    await expect(dialog.locator('[data-slot="gaps-team-group"][data-team="mine"]')).toContainText('Yedekleme betiğinin')
    const badTeam = await clipped(dialog)
    expect(badTeam, `takıma göre: kesilen metin ${badTeam.join(' | ')}`).toEqual([])
    const overflow = await dialog.evaluate((el) => {
      let worst = 0
      for (const n of el.querySelectorAll('*')) { const r = n.getBoundingClientRect(); if (r.width > 0) worst = Math.max(worst, r.right - window.innerWidth) }
      return worst
    })
    expect(overflow, 'pencere içeriği görünüm alanını aşıyor').toBeLessThanOrEqual(1)
    if (vp.touch) {
      const small = await smallTargets(dialog)
      expect(small, `takıma göre: 40 px altı hedefler: ${small.join(', ')}`).toEqual([])
    }
    await page.screenshot({ path: `test-results/login-gaps-enriched-${vp.name}.png` })

    // Tek eksiği gizle → toplamdan düşer, "Gizlenen eksikler" listesi çıkar; geri al
    const before = Number(await dialog.locator('[data-slot="gaps-total"]').getAttribute('data-total'))
    await dialog.locator('[data-slot="gaps-item"][data-code="TEAM_FORWARD_BROKEN"] [data-slot="gaps-hide"]').click()
    await expect(dialog.locator('[data-slot="gaps-total"]')).toHaveAttribute('data-total', String(before - 1))
    await dialog.locator('[data-slot="gaps-hidden-toggle"]').click()
    await dialog.locator('[data-slot="gaps-unhide"]').click()
    await expect(dialog.locator('[data-slot="gaps-total"]')).toHaveAttribute('data-total', String(before))

    // "1 hafta sonra hatırlat" menüsü (dokunmatikte ≥ 40 px öğe)
    await dialog.locator('[data-slot="gaps-snooze-more"]').click()
    const week = page.locator('[data-slot="gaps-snooze-week"]')
    await expect(week).toBeVisible()
    // offsetHeight: menünün açılış animasyonundaki ölçek dönüşümü ölçümü küçültmesin
    if (vp.touch) expect(await week.evaluate((el) => el.offsetHeight)).toBeGreaterThanOrEqual(40)
    await week.click()
    await expect(dialog).toHaveCount(0)
    const stored = await page.evaluate(() => JSON.parse(localStorage.getItem('sm.gaps.state') || 'null'))
    expect(stored?.snoozeUntil).toBeGreaterThan(Date.now() + 6 * 24 * 3600_000)
    await context.close()
  })
}
