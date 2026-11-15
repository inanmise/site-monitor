// Alarm Geçmişi — alarm eylemleri uçtan uca (yayın öncesi kapı, 2026-11-15).
//
// Listeden alarm ayrıntısı açılır → Onayla (gerekçe penceresi, POST /acknowledge gövdesi `{ note }`, düğme kalkar) →
// Tekrar Bildir (önizleme → bir e-posta alıcısı çıkarılır → POST /re-notify gövdesi `excludeEmails`) → Çöz (POST /resolve).
// Bu arada kapanan alarm: 409 ALERT_ALREADY_RESOLVED metni gerekçe penceresinde, not korunur. Yalnız push kalan yeniden
// gönderim: "Push notification sent to N people"; izlemenin e-postası alarm anında kapalıysa (`email_block_reason:
// SKIPPED_MONITOR_OFF`) "alıcı yok" yerine nedeni. Kapsamlı yönetici: eylem düğmeleri satırın `act_scope`'una uyar (izin
// yoksa düğme yok, nedeni yazılır; seçim kutusu da yok). Telefon (390, dokunmatik), tablet (768, dokunmatik), dizüstü
// (1280): taşma yok, metin kesilmez, dokunma hedefleri ≥ 40 px. API mock — bildirim gönderilmez.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'
import { GATE_VIEWPORTS, expectLayout, expectInViewport, json } from './support/gateChecks.js'

const ago = (ms) => new Date(Date.now() - ms).toISOString().slice(0, 19)
const LONG_DOMAIN = 'odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.com'
const row = (id, over = {}) => ({
  id, domain: id === 501 ? LONG_DOMAIN : `api-${id}.example.com`, alert_type: 'HTTP_DOWN', alert_level: id === 501 ? 'CRITICAL' : 'HIGH',
  resolved: false, acknowledged: false, created_at: ago(40 * 60_000), team_id: 1, team_name: 'Takım A',
  message: 'HTTP 503 Service Unavailable — yük dengeleyici arka uç havuzu boş', act_scope: true, notify_scope: true, ...over,
})
// Bilinen ürün hataları (yayın öncesi kapı raporunda; ürün koduna dokunulmadı) — yalnız bu öğeler ölçümden düşülür:
//  - AlertDetail.jsx:288 — "Kimlik" olgusundaki CopyableRef kopya düğmesi `buttonClassName` almıyor: dokunmatikte 24×24 px;
//  - ActionNoteDialog.jsx:152 — gerekçe penceresinde alarm hedefi (`action-note-target`) `truncate` ile kesiliyor (title
//    dokunmatikte açılmaz);
//  - AlertLists.jsx:48 — alarm kartındaki takım adı TeamBadge `wrap` olmadan çiziliyor: uzun AD takım adı kesiliyor.
const KNOWN_SMALL = [/"Copy link" 24x24/]
const KNOWN_CLIP = [/\[action-note-target\]/, /Dijital Kanallar Mobil Bankacılık ve Uzu/]
const NOTE = 'Ödeme servisi ekibi yük dengeleyicide arka uç havuzunu inceliyor'
const CLOSED_TEXT = 'The alert was closed meanwhile — there is nothing to acknowledge. Refresh the list.'

/**
 * @param rows        liste satırları (açık görünüm)
 * @param preview     id → önizleme `data`
 * @param ackStatus   id → { status, body } (yoksa başarı)
 */
async function mockAlerts(page, { rows, preview = {}, ackStatus = {}, role = 'ADMIN', globalAdmin = true }) {
  const sink = { ack: [], resolve: [], renotify: [] }
  const state = new Map(rows.map((r) => [r.id, { ...r }]))
  await mockApi(page, { role, globalAdmin, teamIds: [1] })
  await page.route((u) => new URL(u).pathname.startsWith('/api/admin/alerts'), async (route) => {
    const req = route.request()
    const url = new URL(req.url())
    const p = url.pathname
    const m = req.method()
    let mt
    if (p === '/api/admin/alerts' && m === 'GET') {
      const resolved = url.searchParams.get('resolved')
      const list = [...state.values()].filter((r) => (resolved === 'true' ? r.resolved : !r.resolved))
      return route.fulfill(json({ success: true, data: list, total: list.length, type_counts: { HTTP_DOWN: list.length },
        level_counts: { CRITICAL: list.filter((r) => r.alert_level === 'CRITICAL').length }, unacked_total: list.filter((r) => !r.acknowledged).length,
        stale_total: 0, stale_hours: 24, can_act: true, noc_can_write: false }))
    }
    if ((mt = p.match(/^\/api\/admin\/alerts\/(\d+)$/)) && m === 'GET') {
      return route.fulfill(json({ success: true, data: state.get(Number(mt[1])) }))
    }
    if ((mt = p.match(/^\/api\/admin\/alerts\/(\d+)\/acknowledge$/)) && m === 'POST') {
      const id = Number(mt[1])
      sink.ack.push({ id, body: JSON.parse(req.postData() || '{}') })
      const forced = ackStatus[id]
      if (forced) return route.fulfill(json(forced.body, forced.status))
      const r = state.get(id)
      Object.assign(r, { acknowledged: true, acknowledged_by: 'demo', acknowledged_at: ago(0), acknowledged_note: sink.ack.at(-1).body.note })
      return route.fulfill(json({ success: true, data: r }))
    }
    if ((mt = p.match(/^\/api\/admin\/alerts\/(\d+)\/resolve$/)) && m === 'POST') {
      const id = Number(mt[1])
      sink.resolve.push({ id, body: JSON.parse(req.postData() || '{}') })
      const r = state.get(id)
      Object.assign(r, { resolved: true, resolved_by: 'demo', resolved_at: ago(0), resolved_note: sink.resolve.at(-1).body.note })
      return route.fulfill(json({ success: true, data: r }))
    }
    if ((mt = p.match(/^\/api\/admin\/alerts\/(\d+)\/re-notify\/preview$/))) {
      return route.fulfill(json({ success: true, data: preview[Number(mt[1])] }))
    }
    if ((mt = p.match(/^\/api\/admin\/alerts\/(\d+)\/re-notify$/)) && m === 'POST') {
      const body = req.postData() ? JSON.parse(req.postData()) : null
      sink.renotify.push({ id: Number(mt[1]), body })
      const pv = preview[Number(mt[1])] || {}
      const excluded = new Set(body?.excludeEmails || [])
      const mails = pv.email_block_reason ? 0 : (pv.recipients || []).filter((x) => !excluded.has(x.email)).length
      return route.fulfill(json({ success: true, data: { status: 'queued', recipients_queued: mails, contacts_queued: mails } }))
    }
    return route.fallback()
  })
  return sink
}

/** Listedeki kartın ayrıntısını açar; ayrıntı gövdesini döner (masaüstünde Sheet, dar ekranda pencere). */
async function openDetail(page, id) {
  const open = page.locator(`[data-alert-card][data-alert-id="${id}"] [data-alert-open]`)
  await open.waitFor({ timeout: 60_000 })
  await open.click()
  const body = page.locator(`[data-slot="alert-detail"][data-alert-id="${id}"] [data-slot="alert-detail-body"]`)
  await expect(body).toBeVisible({ timeout: 15_000 })
  return body
}

/** Gerekçe penceresi: not yaz, onayla. */
async function submitNote(page, note = NOTE) {
  const dlg = page.locator('[data-action-note]')
  await expect(dlg).toBeVisible()
  await dlg.getByRole('textbox', { name: /Reason/ }).fill(note)
  await dlg.locator('[data-action-submit]').click()
  return dlg
}

for (const vp of GATE_VIEWPORTS) {
  test.describe(`alarm eylemleri — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('ayrıntı → Onayla (not gövdesi) → Tekrar Bildir (bir e-posta çıkarılır) → Çöz; 409 kapalı alarm metni pencerede', async ({ page }) => {
      test.setTimeout(150_000)
      const sink = await mockAlerts(page, {
        rows: [row(501), row(502)],
        preview: { 501: {
          alert_id: 501,
          recipients: [
            { email: 'takim-a@example.com', name: 'Takım A', role: 'Takım maili', kind: 'TEAM' },
            { email: 'kritik-eskalasyon-sorumlusu.uzun-adres@example.com', name: 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin (Teknoloji Servis Yönetimi)', role: 'Müdür', kind: 'CONTACT' },
          ],
          webhook: { channel_enabled: true, block_reason: null, recipients: [{ username: 'N00001', display_name: 'Örnek Kişi', status: 'PENDING' }] },
        } },
        ackStatus: { 502: { status: 409, body: { success: false, code: 'ALERT_ALREADY_RESOLVED', error: CLOSED_TEXT } } },
      })
      await page.goto('/?tab=alerthistory')
      let body = await openDetail(page, 501)
      await expectLayout(page, '[data-slot="alert-detail"]', { touch: vp.touch, label: `alarm ayrıntısı @${vp.name}`, allowSmall: KNOWN_SMALL, allowClip: KNOWN_CLIP })

      // ── Onayla ──
      await body.getByRole('button', { name: 'Acknowledge', exact: true }).click()
      const note = page.locator('[data-action-note]')
      await expect(note).toBeVisible()
      await expectInViewport(note, vp.width, 'gerekçe penceresi')
      await expectLayout(page, '[data-action-note]', { touch: vp.touch, label: `gerekçe penceresi @${vp.name}`, allowClip: KNOWN_CLIP })
      // Kısa gerekçe: kural uyarısı, istek gitmez
      await note.getByRole('textbox', { name: /Reason/ }).fill('bakıyoruz')
      await note.locator('[data-action-submit]').click()
      await expect(note.locator('[data-slot="action-note-rule"][data-state="error"]').first()).toBeVisible()
      expect(sink.ack).toHaveLength(0)
      await submitNote(page)
      await expect.poll(() => sink.ack.length).toBe(1)
      expect(sink.ack[0]).toEqual({ id: 501, body: { note: NOTE } })
      await expect(page.locator('[data-action-note]')).toHaveCount(0)
      await expect(page.getByText('Alert acknowledged')).toBeVisible()
      body = page.locator('[data-slot="alert-detail"][data-alert-id="501"] [data-slot="alert-detail-body"]')
      await expect(body.getByRole('button', { name: 'Acknowledge', exact: true })).toHaveCount(0)

      // ── Tekrar Bildir: önizleme → bir e-posta çıkar → gönder ──
      await body.getByRole('button', { name: 'Re-Notify', exact: true }).click()
      const modal = page.getByRole('dialog', { name: 'Re-notify — confirm recipients' })
      await expect(modal).toBeVisible()
      await expect(modal).toContainText('3 recipients selected (2 e-mail, 1 webhook)')
      await expectInViewport(modal, vp.width, 'tekrar bildir penceresi')
      await expectLayout(page, '[data-slot="dialog-content"]', { touch: vp.touch, label: `tekrar bildir @${vp.name}` })
      await modal.getByRole('checkbox', { name: /kritik-eskalasyon-sorumlusu/ }).click()
      await expect(modal).toContainText('2 recipients selected (1 e-mail, 1 webhook)')
      await modal.getByRole('button', { name: 'Send', exact: true }).click()
      await expect.poll(() => sink.renotify.length).toBe(1)
      expect(sink.renotify[0]).toEqual({ id: 501, body: { excludeEmails: ['kritik-eskalasyon-sorumlusu.uzun-adres@example.com'] } })
      await expect(page.getByText('Notification sent to 1 recipients')).toBeVisible()
      await expect(modal).toHaveCount(0)

      // ── Çöz ──
      await body.getByRole('button', { name: 'Resolve', exact: true }).click()
      await submitNote(page, 'Arka uç havuzu yeniden dolduruldu ve servis normal yanıt veriyor')
      await expect.poll(() => sink.resolve.length).toBe(1)
      expect(sink.resolve[0]).toEqual({ id: 501, body: { note: 'Arka uç havuzu yeniden dolduruldu ve servis normal yanıt veriyor' } })
      await expect(page.getByText('Alert resolved, notification queued')).toBeVisible()
      // Çözülen alarm açık listeden düşer (yenileme)
      await expect(page.locator('[data-alert-card][data-alert-id="501"]')).toHaveCount(0, { timeout: 15_000 })

      // ── Bu arada kapanmış alarm: 409 metni pencerede, not korunur ──
      const close = page.locator('[data-slot="alert-detail"]').getByRole('button', { name: /^Close$/ }).first()
      if (await close.isVisible().catch(() => false)) await close.click()
      else await page.keyboard.press('Escape')
      body = await openDetail(page, 502)
      await body.getByRole('button', { name: 'Acknowledge', exact: true }).click()
      const dlg = await submitNote(page)
      await expect.poll(() => sink.ack.length).toBe(2)
      await expect(dlg).toContainText(CLOSED_TEXT)
      await expect(dlg).toContainText('Your note is still here')
      await expect(dlg.getByRole('textbox', { name: /Reason/ })).toHaveValue(NOTE)
      await expectLayout(page, '[data-action-note]', { touch: vp.touch, label: `409 metni @${vp.name}`, allowClip: KNOWN_CLIP })
    })

    test('yalnız push kalan yeniden gönderim + e-posta alarm anında kapalı (SKIPPED_MONITOR_OFF)', async ({ page }) => {
      test.setTimeout(120_000)
      const sink = await mockAlerts(page, {
        rows: [row(501), row(503, { alert_type: 'KEYWORD', domain: 'https://www.example.com/giris' })],
        preview: {
          501: { alert_id: 501, recipients: [{ email: 'takim-a@example.com', name: 'Takım A', role: 'Takım maili', kind: 'TEAM' }],
            webhook: { channel_enabled: true, block_reason: null, recipients: [
              { username: 'N00001', display_name: 'Örnek Kişi', status: 'PENDING' },
              { username: 'N00002', display_name: 'Kişi B', status: 'PENDING' }] } },
          503: { alert_id: 503, recipients: [{ email: 'takim-a@example.com', name: 'Takım A', role: 'Takım maili', kind: 'TEAM' }],
            email_block_reason: 'SKIPPED_MONITOR_OFF',
            webhook: { channel_enabled: true, block_reason: null, recipients: [{ username: 'N00003', display_name: 'Kişi C', status: 'PENDING' }] } },
        },
      })
      await page.goto('/?tab=alerthistory')
      // Tüm e-postalar çıkarılır, push kalır → "Push notification sent to 2 people"
      let body = await openDetail(page, 501)
      await body.getByRole('button', { name: 'Re-Notify', exact: true }).click()
      let modal = page.getByRole('dialog', { name: 'Re-notify — confirm recipients' })
      await modal.getByRole('checkbox', { name: /takim-a@example\.com/ }).click()
      await expect(modal).toContainText('2 recipients selected (0 e-mail, 2 webhook)')
      await modal.getByRole('button', { name: 'Send', exact: true }).click()
      await expect.poll(() => sink.renotify.length).toBe(1)
      expect(sink.renotify[0].body).toEqual({ excludeEmails: ['takim-a@example.com'] })
      await expect(page.getByText('Push notification sent to 2 people')).toBeVisible()

      // E-posta kanalı alarm anında kapalı: "alıcı yok" değil nedeni; e-posta satırı sayılmaz
      const close = page.locator('[data-slot="alert-detail"]').getByRole('button', { name: /^Close$/ }).first()
      if (await close.isVisible().catch(() => false)) await close.click()
      else await page.keyboard.press('Escape')
      body = await openDetail(page, 503)
      await body.getByRole('button', { name: 'Re-Notify', exact: true }).click()
      modal = page.getByRole('dialog', { name: 'Re-notify — confirm recipients' })
      await expect(modal.locator('[data-slot="renotify-email-monitor-off"]')).toHaveText(/E-mail was switched off for this monitor when the alert opened/)
      await expect(modal).not.toContainText('takim-a@example.com')
      await expect(modal).toContainText('1 recipients selected (0 e-mail, 1 webhook)')
      await expectLayout(page, '[data-slot="dialog-content"]', { touch: vp.touch, label: `e-posta kapalı @${vp.name}` })
      await modal.getByRole('button', { name: 'Send', exact: true }).click()
      await expect.poll(() => sink.renotify.length).toBe(2)
      expect(sink.renotify[1]).toEqual({ id: 503, body: null })
      await expect(page.getByText('Push notification sent to 1 people')).toBeVisible()
    })

    test('kapsamlı yönetici: eylem düğmeleri satırın act_scope\'una uyar', async ({ page }) => {
      test.setTimeout(120_000)
      await mockAlerts(page, {
        role: 'ADMIN', globalAdmin: false,
        rows: [row(501), row(504, { act_scope: false, team_id: 2, team_name: 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi' })],
      })
      await page.goto('/?tab=alerthistory')
      await page.locator('[data-alert-card][data-alert-id="504"]').waitFor({ timeout: 60_000 })
      // Seçim kutusu yalnız eylem yapılabilen satırda
      await expect(page.locator('[data-alert-card][data-alert-id="501"]').getByRole('checkbox')).toHaveCount(1)
      await expect(page.locator('[data-alert-card][data-alert-id="504"]').getByRole('checkbox')).toHaveCount(0)
      await expectLayout(page, '.app-main', { touch: vp.touch, label: `alarm listesi @${vp.name}`, allowClip: KNOWN_CLIP })

      const blocked = await openDetail(page, 504)
      for (const name of ['Acknowledge', 'Resolve', 'Re-Notify']) {
        await expect(blocked.getByRole('button', { name, exact: true }), `${name} kapalı satırda yok`).toHaveCount(0)
      }
      await expect(blocked.locator('[data-slot="alert-act-blocked"]')).toBeVisible()
      await expect(blocked.locator('[data-slot="alert-act-blocked"]')).toContainText(/only that team|isn't your team's/)
      await expectLayout(page, '[data-slot="alert-detail"]', { touch: vp.touch, label: `kapalı satır ayrıntısı @${vp.name}`, allowSmall: KNOWN_SMALL, allowClip: KNOWN_CLIP })
      const close = page.locator('[data-slot="alert-detail"]').getByRole('button', { name: /^Close$/ }).first()
      if (await close.isVisible().catch(() => false)) await close.click()
      else await page.keyboard.press('Escape')

      const own = await openDetail(page, 501)
      for (const name of ['Acknowledge', 'Resolve', 'Re-Notify']) {
        await expect(own.getByRole('button', { name, exact: true }), `${name} kendi satırında var`).toBeVisible()
      }
      await expect(own.locator('[data-slot="alert-act-blocked"]')).toHaveCount(0)
    })
  })
}
