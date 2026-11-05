import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'
import { CERT_DOMAINS, PERMS, mockCertApi } from './support/certMocks.js'

// 2026-11-05 (Alarm iletimi — diğer yüzeyler): izleme detay penceresi başlığındaki iletim rozeti + dokununca açılan liste,
// sertifika penceresi başlığındaki aynı rozet, okuma hatasında küçük "okunamadı" ve elle yeniden gönderim penceresinin
// "İletim · <takım>" satırları (+ bildirim geçmişindeki "İletim" rozeti) telefon (390, dokunmatik), tablet (768,
// dokunmatik) ve dizüstünde (1280): sayfa yatay kaymaz, öğe ekran dışına taşmaz, gerçek uzunlukta AD takım / kişi adları
// kırpılmaz, dokunmatikte hedefler ≥ 40 px. Arka uç taklit (page.route) — gerçek gezinme yalnız uygulama içi.
const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]
const LONG_TEAM = 'Dijital Kanallar Operasyon ve Uygulama Destek Ekibi (Ankara / Bölge Müdürlüğü)'
const LONG_TEAM_B = 'Kurumsal Ağ Altyapısı ve Güvenlik Operasyon Merkezi Yönetimi (İstanbul / Veri Merkezi)'
const LONG_PERSON = 'Ayşe Yılmaz Karadeniz (Ağ ve Güvenlik Operasyonları / Kıdemli Uzman Mühendis)'
const ROWS = [
  { id: 1, owner_team_id: 1, owner_team_name: 'Takım A', scope_kind: 'ALL', scope_types: [], scope_monitors: [],
    recipient_kind: 'TEAM', recipient_team_id: 9, recipient_team_name: LONG_TEAM, recipient_members: [], enabled: true,
    expires_on: null, state: 'active' },
  { id: 2, owner_team_id: 1, owner_team_name: 'Takım A', scope_kind: 'TYPES', scope_types: ['http', 'keyword', 'pagespeed', 'cert'],
    scope_monitors: [], recipient_kind: 'MEMBERS', recipient_team_id: 11, recipient_team_name: LONG_TEAM_B,
    recipient_members: [{ user_id: 'u1', display_name: LONG_PERSON, active: true, in_team: true }, { user_id: 'u2' }],
    enabled: true, expires_on: '2026-12-31', state: 'active' },
  { id: 3, owner_team_id: 1, owner_team_name: 'Takım A', scope_kind: 'MONITORS', scope_types: [], scope_monitors: [{ type: 'http', id: 100 }],
    recipient_kind: 'TEAM', recipient_team_id: 12, recipient_team_name: 'Güvenlik Ekibi', recipient_members: [], enabled: false,
    expires_on: null, state: 'paused' },
]
const ALERT = {
  id: 501, domain: 'odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.com', alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL',
  resolved: false, acknowledged: false, created_at: new Date(Date.now() - 600_000).toISOString().slice(0, 19), team_id: 1, team_name: 'Takım A',
  message: 'HTTP 503',
}

const json = (body, status = 200) => (route) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })

/** Dili Türkçe yap + iletim uçlarını taklit et. `forMonitor(query)` → gövde (ya da { status, body }). */
async function routes(page, { forMonitor, cert = false } = {}) {
  await page.addInitScript(() => { try { localStorage.setItem('site-monitor-lang', 'tr') } catch { /* yok */ } })
  await mockApi(page, cert ? { perms: PERMS } : {})
  if (cert) await mockCertApi(page)
  const calls = []
  await page.route((u) => new URL(u).pathname === '/api/alert-forwards/for-monitor', (route) => {
    const q = Object.fromEntries(new URL(route.request().url()).searchParams)
    calls.push(q)
    const out = forMonitor(q)
    return out?.status ? json(out.body, out.status)(route) : json(out)(route)
  })
  return calls
}

function smallTargets(sel) {
  const out = []
  for (const root of document.querySelectorAll(sel)) {
    for (const b of root.querySelectorAll('button, a[href], [role="checkbox"]')) {
      const r = b.getBoundingClientRect()
      if (r.width === 0 || r.height === 0 || b.disabled) continue
      const cs = getComputedStyle(b)
      if (cs.visibility === 'hidden' || cs.display === 'none' || b.getAttribute('aria-hidden') === 'true') continue
      // shadcn Checkbox: 16 px kutu, dokunma alanı satırın tamamı (bağlı Label) — satır ölçülür
      let h = b.getAttribute('role') === 'checkbox' ? (b.parentElement?.getBoundingClientRect().height ?? r.height) : r.height
      const af = getComputedStyle(b, '::after')
      if (af.position === 'absolute' && af.content !== 'none') {
        const ah = parseFloat(af.height)
        if (Number.isFinite(ah)) h = Math.max(h, ah)
        const top = parseFloat(af.top) || 0
        const bottom = parseFloat(af.bottom) || 0
        h = Math.max(h, r.height - Math.min(0, top) - Math.min(0, bottom))
      }
      if (h < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 40)}" ${Math.round(h)}`)
    }
  }
  return out
}

function clipped(sel) {
  const out = []
  for (const root of document.querySelectorAll(sel)) {
    for (const n of root.querySelectorAll('span, p, li, strong, label, a, div')) {
      if (n.closest('.sr-only') || n.childElementCount !== 0 || n.textContent.trim().length <= 12) continue
      const cs = getComputedStyle(n)
      if (n.scrollWidth > n.clientWidth + 1 || cs.textOverflow === 'ellipsis') out.push(n.textContent.slice(0, 50))
    }
  }
  return out
}

function offenders(sel) {
  const vw = document.documentElement.clientWidth
  const out = []
  for (const root of document.querySelectorAll(sel)) {
    for (const el of root.querySelectorAll('*')) {
      const r = el.getBoundingClientRect()
      const cs = getComputedStyle(el)
      if (r.width <= 1 || r.height <= 1 || cs.visibility === 'hidden' || cs.display === 'none') continue
      if (r.right <= vw + 1 && r.left >= -1) continue
      let contained = false
      for (let p = el.parentElement; p && p !== root; p = p.parentElement) {
        if (getComputedStyle(p).overflowX !== 'visible' && p.getBoundingClientRect().right <= vw + 1) { contained = true; break }
      }
      if (!contained) out.push(`${el.tagName.toLowerCase()}[${el.getAttribute('data-slot') || ''}] ${Math.round(r.left)}..${Math.round(r.right)}`)
      if (out.length >= 8) return out
    }
  }
  return out
}

const overflowX = () => document.documentElement.scrollWidth - document.documentElement.clientWidth
const settleAnimations = (page) => page.waitForFunction(() => document.getAnimations().every((a) => a.playState !== 'running'))

for (const vp of VIEWPORTS) {
  test.describe(`alarm iletimi yüzeyleri — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('izleme detay penceresi: "İletim: 2" rozeti 7/24 göstergesinin ardında; liste uzun adları sarar; ekleme bağlantısı Yönetim\'e', async ({ page }) => {
      test.setTimeout(120_000)
      const calls = await routes(page, { forMonitor: () => ({ success: true, data: { items: ROWS, can_add: true,
        owner_teams: [{ id: 1, name: 'Takım A' }], monitor: { type: 'http', id: 100, name: 'www.example.com' } } }) })
      await page.goto('/?tab=http')
      await page.locator('.upt-grid [data-slot="card"] [data-monitor-open]').first().click({ timeout: 20_000 })
      const dlg = page.locator('[data-slot="dialog-content"]').first()
      await expect(dlg).toBeVisible()
      const pill = dlg.locator('[data-slot="monitor-forwards"]')
      await expect(pill).toHaveAttribute('data-state', 'active', { timeout: 15_000 })
      await expect(pill).toHaveText('İletim: 2')
      expect(calls[0]).toEqual({ type: 'http', id: '100' })
      // 7/24 göstergesinden SONRA
      const order = await dlg.evaluate((d) => {
        const noc = d.querySelector('[data-slot="noc-status"]')
        const fwd = d.querySelector('[data-slot="monitor-forwards"]')
        return noc && fwd ? Boolean(noc.compareDocumentPosition(fwd) & Node.DOCUMENT_POSITION_FOLLOWING) : null
      })
      expect(order).toBe(true)
      await settleAnimations(page)
      expect(await page.evaluate(overflowX), `@${vp.name}: yatay taşma`).toBeLessThanOrEqual(1)
      expect(await page.evaluate(offenders, '[data-slot="dialog-content"]'), `@${vp.name}: ekran dışı`).toEqual([])
      const trigger = pill.locator('xpath=ancestor::button[1]')
      if (vp.touch) {
        const h = await trigger.evaluate((b) => b.getBoundingClientRect().height)
        expect(h, `@${vp.name}: rozet dokunma hedefi`).toBeGreaterThanOrEqual(39.5)
      }

      // Dokun / tıkla → açılır liste
      await trigger.click()
      const detail = page.locator('[data-slot="monitor-forwards-detail"]')
      await expect(detail).toBeVisible()
      await settleAnimations(page)
      const items = detail.locator('[data-slot="monitor-forwards-item"]')
      await expect(items).toHaveCount(3)
      await expect(items.nth(0)).toContainText(LONG_TEAM)
      await expect(items.nth(0)).toContainText('Tüm takım · Tüm izlemeler')
      await expect(items.nth(1)).toContainText(LONG_TEAM_B)
      await expect(items.nth(1)).toContainText('2 üye')
      await expect(items.nth(1)).toContainText('Türler: HTTP, Kelime, Sayfa Hızı, Sertifika')
      await expect(items.nth(2)).toContainText('Duraklatıldı')
      expect(await page.evaluate(clipped, '[data-slot="monitor-forwards-detail"]'), `@${vp.name}: kırpılan metin`).toEqual([])
      expect(await page.evaluate(offenders, '[data-slot="hint-popover"]'), `@${vp.name}: liste ekran dışı`).toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="monitor-forwards-detail"]'), `@${vp.name}: küçük hedef`).toEqual([])
      const add = detail.locator('[data-action="monitor-forwards-add"]')
      const href = new URL(await add.getAttribute('href'), 'http://x')
      expect(href.searchParams.get('tab')).toBe('admin')
      expect(href.searchParams.get('g_tab')).toBe('forwards')
      expect(href.searchParams.get('g_fnew')).toBe('http:100')
      expect(href.searchParams.get('g_fteam')).toBe('1')
      await page.screenshot({ path: `test-results/fwd-monitor-pill-${vp.name}.png` })
      await add.click()
      await expect.poll(() => new URL(page.url()).searchParams.get('tab')).toBe('admin')
      await expect(page.locator('[data-slot="monitor-forwards-detail"]')).toHaveCount(0)
    })

    test('okuma hatası: küçük "İletim: okunamadı" — dokununca yeniden dener; iletim yok + eklenemez → rozet yok', async ({ page }) => {
      test.setTimeout(120_000)
      let n = 0
      await routes(page, { forMonitor: (q) => {
        if (q.type === 'ping') { n++; return n === 1 ? { status: 500, body: { success: false, error: 'Sunucu yanıt veremedi' } }
          : { success: true, data: { items: [ROWS[0]], can_add: false, owner_teams: [{ id: 1, name: 'Takım A' }] } } }
        return { success: true, data: { items: [], can_add: false, owner_teams: [{ id: 1, name: 'Takım A' }] } }
      } })
      await page.goto('/?tab=ping')
      await page.locator('.upt-grid [data-slot="card"] [data-monitor-open]').first().click({ timeout: 20_000 })
      const dlg = page.locator('[data-slot="dialog-content"]').first()
      const pill = dlg.locator('[data-slot="monitor-forwards"]')
      await expect(pill).toHaveAttribute('data-state', 'error', { timeout: 15_000 })
      await expect(pill).toHaveText('İletim: okunamadı')
      const retry = dlg.locator('[data-action="monitor-forwards-retry"]')
      if (vp.touch) {
        const h = await retry.evaluate((b) => b.getBoundingClientRect().height)
        expect(h, `@${vp.name}: yeniden dene hedefi`).toBeGreaterThanOrEqual(39.5)
      }
      await retry.click()
      await expect(pill).toHaveAttribute('data-state', 'active')
      await expect(pill).toHaveText('İletim: 1')
      // Başka tür (HTTP): iletim yok + eklenemez → hiçbir şey çizilmez
      await page.goto('/?tab=http')
      await page.locator('.upt-grid [data-slot="card"] [data-monitor-open]').first().click({ timeout: 20_000 })
      await expect(page.locator('[data-slot="dialog-content"]').first()).toBeVisible()
      await page.waitForTimeout(800)
      await expect(page.locator('[data-slot="monitor-forwards"]')).toHaveCount(0)
    })

    test('sertifika penceresi: rozet alan adıyla sorulur, "İletim ekle" (sessiz) → "cert:<alanadı>"', async ({ page }) => {
      test.setTimeout(120_000)
      const calls = await routes(page, { cert: true, forMonitor: () => ({ success: true, data: { items: [], can_add: true,
        owner_teams: [{ id: 1, name: 'Takım A' }], monitor: { type: 'cert', id: 77, name: CERT_DOMAINS.healthy } } }) })
      await page.goto('/?tab=dashboard')
      await page.locator(`[data-slot="card"][data-domain="${CERT_DOMAINS.healthy}"] [data-cert-open]`).first().click({ timeout: 20_000 })
      const dlg = page.locator('[data-slot="dialog-content"]').first()
      await expect(dlg).toBeVisible()
      const pill = dlg.locator('[data-slot="monitor-forwards"]')
      await expect(pill).toHaveAttribute('data-state', 'add', { timeout: 15_000 })
      await expect(pill).toHaveText('İletim ekle')
      expect(calls.at(-1)).toEqual({ type: 'cert', domain: CERT_DOMAINS.healthy })
      await settleAnimations(page)
      expect(await page.evaluate(overflowX), `@${vp.name}: yatay taşma`).toBeLessThanOrEqual(1)
      expect(await page.evaluate(offenders, '[data-slot="dialog-content"]'), `@${vp.name}: ekran dışı`).toEqual([])
      const trigger = pill.locator('xpath=ancestor::button[1]')
      if (vp.touch) {
        const h = await trigger.evaluate((b) => b.getBoundingClientRect().height)
        expect(h, `@${vp.name}: rozet dokunma hedefi`).toBeGreaterThanOrEqual(39.5)
      }
      await trigger.click()
      const detail = page.locator('[data-slot="monitor-forwards-detail"]')
      await expect(detail).toBeVisible()
      await expect(detail).toContainText('başka bir takıma iletilmiyor')
      const href = new URL(await detail.locator('[data-action="monitor-forwards-add"]').getAttribute('href'), 'http://x')
      expect(href.searchParams.get('g_fnew')).toBe(`cert:${CERT_DOMAINS.healthy}`)
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="monitor-forwards-detail"]'), `@${vp.name}: küçük hedef`).toEqual([])
      await page.screenshot({ path: `test-results/fwd-cert-pill-${vp.name}.png` })
    })

    test('yeniden gönderim penceresi: "İletim · <takım>" satırları sarar; bildirim geçmişinde "İletim" rozeti + "İletime de gitti"', async ({ page }) => {
      test.setTimeout(120_000)
      await routes(page, { forMonitor: () => ({ success: true, data: { hidden: true } }) })
      await page.route((u) => new URL(u).pathname === '/api/admin/alerts/501', json({ success: true, data: ALERT }))
      await page.route((u) => new URL(u).pathname === '/api/admin/alerts/501/notifications', json({ success: true, data: [
        { id: 1, trigger: 'INITIAL', recipient_name: 'Takım A', recipient_email: 'takim-a@example.com', recipient_role: 'TEAM',
          email_status: 'SENT', sent_at: ALERT.created_at, subject: 'Alarm', message: 'gövde' },
        { id: 2, trigger: 'INITIAL', recipient_name: LONG_TEAM, recipient_email: 'dijital-kanallar-operasyon-destek@example.com',
          recipient_role: 'FORWARD', email_status: 'SENT', sent_at: ALERT.created_at, subject: 'Alarm', message: 'gövde' },
      ] }))
      await page.route((u) => new URL(u).pathname === '/api/admin/alerts/501/re-notify/preview', json({ success: true, data: {
        alert_id: 501,
        recipients: [
          { email: 'takim-a@example.com', name: 'Takım A', role: 'Takım maili', kind: 'TEAM' },
          { email: 'dijital-kanallar-operasyon-destek@example.com', name: LONG_TEAM, role: `İletim: ${LONG_TEAM}`, kind: 'FORWARD', team_name: LONG_TEAM },
          { email: 'ayse.yilmaz.karadeniz@example.com', name: LONG_TEAM_B, role: `İletim: ${LONG_TEAM_B}`, kind: 'FORWARD' },
        ],
        webhook: { channel_enabled: true, block_reason: null, recipients: [
          { username: 'N00001', display_name: 'Örnek Kişi', status: 'PENDING' },
          { username: 'N60146', display_name: LONG_PERSON, status: 'PENDING', kind: 'FORWARD', team_name: LONG_TEAM_B },
        ] },
      } }))
      await page.goto('/?tab=alerthistory&alert=501')
      const body = page.locator('[data-slot="alert-detail-body"]')
      await expect(body).toBeVisible({ timeout: 20_000 })

      // Bildirim geçmişi: başlık özeti + iletim satırı rozeti
      await expect(page.locator('[data-slot="notif-forward-summary"]')).toHaveText('İletime de gitti', { timeout: 15_000 })
      await page.locator('[data-slot="alert-notifications"] [data-slot="accordion-trigger"]').first().click()
      await expect(page.locator('[data-slot="notif-forward"]')).toHaveText('İletim')
      await settleAnimations(page)
      expect(await page.evaluate(overflowX), `@${vp.name}: yatay taşma`).toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="alert-notifications"]'), `@${vp.name}: geçmiş kırpılan`).toEqual([])

      // Yeniden gönderim penceresi
      await body.getByRole('button', { name: 'Tekrar Bildir' }).click()
      const modal = page.getByRole('dialog', { name: 'Tekrar bildir — alıcıları onayla' })
      await expect(modal).toBeVisible()
      await settleAnimations(page)
      const badges = modal.locator('[data-renotify="forward"]')
      await expect(badges).toHaveCount(3)
      await expect(badges.nth(0)).toHaveText(`İletim · ${LONG_TEAM}`)
      await expect(badges.nth(1)).toHaveText(`İletim · ${LONG_TEAM_B}`)
      await expect(modal).not.toContainText(`İletim: ${LONG_TEAM}`)
      expect(await page.evaluate(overflowX), `@${vp.name}: yatay taşma`).toBeLessThanOrEqual(1)
      expect(await page.evaluate(offenders, '[role="dialog"]'), `@${vp.name}: pencere ekran dışı`).toEqual([])
      expect(await page.evaluate(clipped, '[role="dialog"]'), `@${vp.name}: pencere kırpılan`).toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="dialog-content"]'), `@${vp.name}: küçük hedef`).toEqual([])
      // İletim alıcısı tek tek çıkarılabilir — seçim sayısı düşer
      await modal.getByRole('checkbox', { name: /dijital-kanallar-operasyon-destek@example\.com/ }).click()
      await expect(modal).toContainText('4 alıcı seçili (2 e-posta, 2 webhook)')
      await page.screenshot({ path: `test-results/fwd-renotify-${vp.name}.png` })
    })
  })
}
