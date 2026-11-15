// 7/24 konsolu → arama kartı → arama kaydı uçtan uca (yayın öncesi kapı, 2026-11-15).
//
// Konsol (`?tab=noc&n_view=console`): aranmayı bekleyen alarmın "Ara" eylemi → arama kartı penceresi (arama listesi
// telefonla — `tel:` bağlantısı; şu anki nöbetçi PASİF hesapsa "Pasif hesap — aranmaz" rozeti, telefon / `tel:` YOK) →
// arama kaydı formu: kişi seçilmeden ve sonuç seçilmeden kayıt gitmez (alan hataları), kişi + sonuç + not ile POST
// /api/alerts/{id}/noc-calls gövdesi (`contactedUserId`, `outcome`, `channel`, `note`) → zaman çizelgesinde kayıt; pencere
// kapanınca konsol satırı "son arama" gösterir. Telefon (390, dokunmatik), tablet (768, dokunmatik), dizüstü (1280): taşma
// yok, metin kesilmez, dokunma hedefleri ≥ 40 px. API mock — gerçek arama / bildirim yok.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'
import { GATE_VIEWPORTS, expectLayout, expectInViewport, json } from './support/gateChecks.js'

const ago = (min) => new Date(Date.now() - min * 60_000).toISOString().slice(0, 19)
const LONG_PERSON = 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin (Teknoloji Servis Yönetimi Bölüm Başkanı)'
const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi'
const ROW = {
  id: 610, alert_type: 'HTTP_DOWN', family: 'http', level: 'CRITICAL', domain: 'https://odeme-servisleri.example.com/api/health',
  created_at: ago(30), resolved: false, team_id: 1, team_name: LONG_TEAM, monitor: { type: 'HTTP', tab: 'http', id: 100, name: 'Ödeme API sağlık ucu' },
  channels: { email_sent: 3, email_failed: 0, webhook_sent: 1, push_sent: 4, push_failed: 0, noc: true },
  noc: { sent_at: ago(28), via_storm: false, groups: '7/24 Ana Grup' }, call_count: 0, last_call: null, can_call: true,
}
const SHEET = {
  alert_id: 610, team_id: 1, team_name: LONG_TEAM, call_list_defined: true,
  oncall: { name: 'Mehmet Emin Çelikkanat-Özdemiroğulları (Uygulama Geliştirme)', title: 'Nöbetçi', phone: null, active: false, until: ago(-600) },
  call_list: [
    { name: LONG_PERSON, title: 'Bölüm Başkanı', phone: '0500 000 00 00', position: 1, active: true },
    { name: 'Kişi B', title: null, phone: null, position: 2, active: true },
  ],
  manager: { name: 'Müdür A', title: 'Müdür', phone: '+90 500 000 00 01', active: true },
  escalation: [{ name: 'Kişi E', role: 'Teknik Sorumlu', email: 'eskalasyon-sorumlusu@example.com' }],
  call_instructions: 'Önce arama listesini sırayla arayın; ulaşılamazsa müdürü arayın.',
}
const CONTACTS = [
  { user_id: 'b5f0c3a2-1111-4c2d-9f00-000000000001', display_name: LONG_PERSON, title: 'Bölüm Başkanı', has_phone: true, source: 'CALL_LIST', is_manager: false },
  { user_id: 'b5f0c3a2-1111-4c2d-9f00-000000000002', display_name: 'Müdür A', title: 'Müdür', has_phone: true, source: 'MANAGER', is_manager: true },
]

async function mockNoc(page) {
  const sink = { calls: [] }
  const row = { ...ROW }
  await mockApi(page, { role: 'ADMIN', globalAdmin: true,
    perms: { 'alerts.read': { view: true }, 'noc_calls.write': { edit: true, view: true } } })
  await page.route((u) => /^\/api\/(noc|alerts)\//.test(new URL(u).pathname), async (route) => {
    const req = route.request()
    const p = new URL(req.url()).pathname
    const m = req.method()
    if (p === '/api/noc/console') {
      return route.fulfill(json({ success: true, data: { window: '24h', generated_at: ago(0),
        kpis: { open: 1, open_critical: 1, noc_sent: 1, not_called: row.call_count ? 0 : 1, called_last_hour: row.call_count ? 1 : 0 },
        facets: { teams: [{ id: 1, name: LONG_TEAM, count: 1 }], types: { http: 1 }, levels: { CRITICAL: 1, HIGH: 0, WARNING: 0 } },
        items: [row], total: 1, page: 0, size: 25, truncated: false, max_rows: 1000, can_write: true } }))
    }
    if (p === '/api/noc/console/alerts/610/call-sheet') return route.fulfill(json({ success: true, data: SHEET }))
    if (p === '/api/alerts/610/noc-contacts') return route.fulfill(json({ success: true, data: CONTACTS }))
    const nameOf = (c) => CONTACTS.find((x) => x.user_id === c.contactedUserId)?.display_name || c.contactedName || '—'
    if (p === '/api/alerts/610/noc-calls' && m === 'GET') {
      return route.fulfill(json({ success: true, data: sink.calls.map((c, i) => ({ id: 900 + i, contacted_user_id: c.contactedUserId,
        contacted_name: nameOf(c), contacted_at: ago(1), channel: c.channel, outcome: c.outcome, note: c.note,
        created_by_name: 'Demo Operatör', created_at: ago(1), can_delete: true, delete_until: ago(-14) })) }))
    }
    if (p === '/api/alerts/610/noc-calls' && m === 'POST') {
      const body = JSON.parse(req.postData() || '{}')
      sink.calls.push(body)
      row.call_count = sink.calls.length
      row.last_call = { contacted_name: nameOf(body), outcome: body.outcome, contacted_at: ago(0), channel: body.channel, created_by_name: 'Demo Operatör' }
      return route.fulfill(json({ success: true, data: { id: 900, contacted_name: nameOf(body), outcome: body.outcome, channel: body.channel,
        note: body.note, contacted_at: ago(0), created_by_name: 'Demo Operatör', created_at: ago(0), can_delete: true } }))
    }
    return route.fallback()
  })
  return sink
}

for (const vp of GATE_VIEWPORTS) {
  test.describe(`7/24 konsolu arama kaydı — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('arama kartı: pasif nöbetçi aranmaz, liste tel: bağlantılı; kayıt POST gövdesi → satır "son arama"', async ({ page }) => {
      test.setTimeout(150_000)
      const sink = await mockNoc(page)
      await page.goto('/?tab=noc&n_view=console')
      const consoleRoot = page.locator('[data-slot="noc-console"]')
      const conRow = consoleRoot.locator('[data-slot="noc-con-row"][data-alert-id="610"]')
      await expect(conRow).toBeVisible({ timeout: 60_000 })
      await expect(conRow.locator('[data-slot="noc-con-no-call"]')).toBeVisible()
      await expectLayout(page, '[data-slot="noc-console"]', { touch: vp.touch, label: `konsol @${vp.name}` })

      await conRow.locator('[data-action="noc-con-call"]').click()
      const dlg = page.getByRole('dialog', { name: /^Call · / })
      await expect(dlg.locator('[data-slot="noc-call-sheet-card"]')).toBeVisible()
      await expect(dlg.locator('[data-slot="noc-sheet-call-list"]')).toBeVisible({ timeout: 15_000 })
      await expectInViewport(dlg, vp.width, 'arama kartı penceresi')

      // Pasif nöbetçi: rozet var, telefon / tel: bağlantısı yok
      const oncall = dlg.locator('[data-slot="noc-sheet-oncall"]')
      await expect(oncall.locator('[data-slot="noc-sheet-passive"]')).toHaveText('Passive account — do not call')
      await expect(oncall.locator('a[href^="tel:"]')).toHaveCount(0)
      // Arama listesinin ilk kişisi tel: ile aranır, ikinci kişinin telefonu yok
      const first = dlg.locator('[data-slot="noc-sheet-call-list"] [data-slot="noc-sheet-person"]').first()
      await expect(first.locator('a[href="tel:05000000000"]')).toBeVisible()
      await expect(dlg.locator('[data-slot="noc-sheet-call-list"] [data-slot="noc-sheet-person"]').nth(1).locator('a[href^="tel:"]')).toHaveCount(0)
      await expect(dlg.locator('[data-slot="noc-sheet-manager"] a[href="tel:+905000000001"]')).toBeVisible()
      await expectLayout(page, '[data-slot="noc-call-dialog"]', { touch: vp.touch, label: `arama kartı @${vp.name}` })

      // Kayıt formu: kişi önseçili (arama listesinin ilki), sonuç seçilmeden istek gitmez — hata "Outcome" alanının altında
      const form = dlg.locator('[data-slot="noc-call-form"]')
      await form.scrollIntoViewIfNeeded()
      const personBox = form.getByRole('combobox', { name: /^Who did you call/ })
      await expect(personBox).toContainText('Ayşegül')
      await form.getByRole('button', { name: 'Save call', exact: true }).click()
      await expect(form.locator('[data-slot="field"][data-invalid="true"]').filter({ hasText: 'Outcome' })).toHaveCount(1)
      expect(sink.calls).toHaveLength(0)

      // Başka kişi (müdür) + sonuç + not
      await personBox.click()
      const picker = page.locator('[data-slot="popover-content"]').last()
      await expect(picker).toBeVisible()
      await expectLayout(page, '[data-slot="popover-content"]', { touch: vp.touch, label: `kişi seçici @${vp.name}` })
      await picker.getByRole('option', { name: /Müdür A/ }).click()
      await expect(personBox).toContainText('Müdür A')
      await form.getByRole('radio', { name: 'No answer' }).click()
      await form.getByRole('textbox', { name: /^Note/ }).fill('İki kez arandı, telefon meşgul; 10 dakika sonra yeniden aranacak.')
      await form.getByRole('button', { name: 'Save call', exact: true }).click()
      await expect.poll(() => sink.calls.length, { timeout: 15_000 }).toBe(1)
      expect(sink.calls[0]).toMatchObject({ contactedUserId: CONTACTS[1].user_id, outcome: 'NO_ANSWER', channel: 'PHONE',
        note: 'İki kez arandı, telefon meşgul; 10 dakika sonra yeniden aranacak.' })
      expect(sink.calls[0]).not.toHaveProperty('contactedName')
      expect(sink.calls[0]).not.toHaveProperty('contactedAt')
      await expect(page.getByText('Call logged', { exact: true })).toBeVisible()
      await expect(dlg.locator('[data-slot="noc-call-entry"]')).toHaveCount(1, { timeout: 15_000 })
      await expect(dlg.locator('[data-slot="noc-call-entry"]')).toContainText('Müdür A')
      await expectLayout(page, '[data-slot="noc-call-dialog"]', { touch: vp.touch, label: `arama kaydı sonrası @${vp.name}` })

      // Pencereyi kapat → konsol satırı "son arama" gösterir
      await page.keyboard.press('Escape')
      await expect(dlg).toHaveCount(0)
      await expect(conRow.locator('[data-slot="noc-con-last-call"]')).toContainText('Müdür A', { timeout: 15_000 })
      await expect(conRow.locator('[data-slot="noc-con-no-call"]')).toHaveCount(0)
      await expectLayout(page, '[data-slot="noc-console"]', { touch: vp.touch, label: `konsol arama sonrası @${vp.name}` })
    })
  })
}
