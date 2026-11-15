// Haftalık Raporlar (`?tab=weeklyreports`) — prod kapısı e2e paketi (2026-11-15). Gerçek uygulama rotası, API tamamen
// mock'lu (yalnız /api/). Telefon (390, dokunmatik), tablet (768, dokunmatik) ve dizüstünde (1280) İŞLEVSEL sonuç
// (istek gövdesi + yanıttan sonraki arayüz durumu) ve yerleşim (taşma yok, ad kırpılmaz, dokunma hedefi ≥ 40 px) ölçülür:
//   - Yeni rapor: global olmayan kullanıcı takım GÖNDERMEZ; sunucunun 400 VALIDATION_FAILED field=team_id metni Takım
//     adımının altında görünür, ekran açık kalır (K8 / WR-2).
//   - Kaydet: PUT gövdesi `version` taşır; kayıt uçuştayken yazılan kaybolmaz, ikinci kayıt yeni sürümle gider (UIA-3).
//   - Onaya gönder: otomatik kayıt uçuştayken onay penceresi doğrulanırsa önce o kayıt biter, sonra submit POST edilir;
//     ikinci bir kayıt yapılmaz (FE-8) — sahte saatle (60 sn aralık).
//   - Onay: mail_status → doğru ileti (SENT / QUEUED_RETRY / SKIPPED_DISABLED / SKIPPED pasif / FAILED — UIA-G2).
//   - 409 WEEKLY_REPORT_STATE_CHANGED sunucu metni; İade et notu zorunlu, POST gövdesinde not.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'
import { VIEWPORTS, json, pathIs, checkLayout } from './support/layoutAudit.js'

/** utils/isoWeek.js isoWeekInfo kopyası (tarayıcıyla aynı yerel saat). */
function isoWeekInfo(d = new Date()) {
  const date = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()))
  const dayNum = date.getUTCDay() || 7
  date.setUTCDate(date.getUTCDate() + 4 - dayNum)
  const yearStart = new Date(Date.UTC(date.getUTCFullYear(), 0, 1))
  return { year: date.getUTCFullYear(), week: Math.ceil(((date - yearStart) / 86400000 + 1) / 7) }
}
const NOW = isoWeekInfo()
const PREV = isoWeekInfo(new Date(Date.now() - 7 * 86400000))
const OLDER = isoWeekInfo(new Date(Date.now() - 21 * 86400000))

const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'
const CONTENT = JSON.stringify({
  version: 1,
  item1: { urgent: 1, high: 0, medium: 0, low: 0, total: 1, status_counts: { working: 1, planned: 0, on_hold: 0, done: 0 }, notes_md: '', tracking_url: '' },
  item2: {}, item3: { notes_md: '' }, item4: { channels: [] },
})
const report = (id, status, extra = {}) => ({
  id, team_id: 1, report_year: NOW.year, week_no: NOW.week, week_label: `${NOW.year}-W${String(NOW.week).padStart(2, '0')}`,
  status, version: 4, content_json: CONTENT, created_by: 'demo', updated_by: 'demo', updated_at: '2026-11-15T09:00:00', score: 60, ...extra,
})
const DRAFT = report(11, 'DRAFT')
const PENDING = report(12, 'PENDING_APPROVAL', { report_year: PREV.year, week_no: PREV.week, submitted_at: '2026-11-14T10:00:00' })

const TEAM_ID_ERROR = 'You can only open a weekly report for your own team; a member of that team or a global administrator opens another team\'s report.'
const STATE_CHANGED = 'This report has already been approved — no second e-mail was sent to the manager. Nothing else is needed.'

/**
 * Global OLMAYAN kullanıcı (USER, takım 1 — uzun AD adı). Haftalık uçlar mockApi'den SONRA kaydedilir (önce gelir).
 * @param opts.rows       liste / tekil okuma satırları
 * @param opts.onCreate   (body) => yanıt { status, body }
 * @param opts.onPut      async (body, n) => yanıt gövdesi (n = 1'den başlayan kayıt sırası)
 * @param opts.approve    sıradaki onay yanıtları [{ status, body }]
 */
async function weeklyMock(page, opts = {}) {
  await mockApi(page, { role: 'USER', globalAdmin: false })
  await page.route(pathIs('/api/me'), json({
    success: true, username: 'demo', display_name: 'Demo Kullanıcı', system_role: 'USER', global_admin: false,
    weekly_reports_visible: true, team_id: 1, team_name: LONG_TEAM, team_ids: [1], team_names: [LONG_TEAM],
    tour: { status: 'dismissed', version: 99 },
  }))
  const state = { rows: (opts.rows || []).map((r) => ({ ...r })), creates: [], puts: [], submits: [], approves: [], rejects: [], log: [] }
  const approveQueue = [...(opts.approve || [])]
  await page.route((u) => new URL(u).pathname.startsWith('/api/weekly-reports'), async (route) => {
    const req = route.request()
    const p = new URL(req.url()).pathname
    const m = req.method()
    const body = () => { try { return JSON.parse(req.postData() || '{}') } catch { return {} } }
    const send = (b, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(b) })
    state.log.push(`${m} ${p}`)
    const one = p.match(/^\/api\/weekly-reports\/(\d+)(\/[a-z-]+)?$/)
    const row = one ? state.rows.find((r) => r.id === Number(one[1])) : null
    if (p === '/api/weekly-reports' && m === 'GET') return send({ success: true, data: state.rows })
    if (p === '/api/weekly-reports' && m === 'POST') {
      const b = body()
      state.creates.push(b)
      const r = opts.onCreate ? opts.onCreate(b) : { status: 200, body: { success: true, data: { id: 99 } } }
      return send(r.body, r.status)
    }
    if (p === '/api/weekly-reports/years') return send({ success: true, data: [NOW.year] })
    if (!one) return send({ success: true, data: null })
    const sub = one[2] || ''
    if (sub === '' && m === 'GET') {
      if (!row) return send({ success: false, error: 'Rapor bulunamadı' }, 404)
      return send({ success: true, data: { report: row, images: [], manager_contact_missing: false, team_channels: [], lock_holder: null } })
    }
    if (sub === '' && m === 'PUT') {
      const b = body()
      state.puts.push(b)
      const res = opts.onPut ? await opts.onPut(b, state.puts.length) : { success: true, data: { version: b.version + 1, status: 'DRAFT' } }
      state.log.push(`PUT-done ${p}`)
      if (res?.success && row) { row.version = res.data.version; row.content_json = b.content_json }
      return send(res)
    }
    if (sub === '/lock') return send({ success: true, data: { acquired: true } })
    if (sub === '/unlock') return send({ success: true })
    if (sub === '/submit') {
      state.submits.push(Number(one[1]))
      if (row) row.status = 'PENDING_APPROVAL'
      return send({ success: true, po_mail: 'SENT' })
    }
    if (sub === '/approve') {
      state.approves.push(Number(one[1]))
      const r = approveQueue.shift() || { status: 200, body: { success: true, mail_status: 'SENT' } }
      return send(r.body, r.status)
    }
    if (sub === '/reject') {
      const b = body()
      state.rejects.push(b)
      if (row) { row.status = 'REJECTED'; row.reject_note = b.note }
      return send({ success: true })
    }
    if (sub === '/comments' || sub === '/mails') return send({ success: true, data: [] })
    return send({ success: true, data: null })   // kpis, monitoring-stats, previous, suggestions
  })
  return state
}

/**
 * BİLİNEN ÜRÜN HATALARI (prod kapısı raporunda; düzeltilince buradan çıkarılır — CLAUDE.md "Names and long text are never clipped"):
 *  - Takım rozeti `wrap` almadan kullanılıyor: düzenleyici başlığında (WeeklyReportsPage.jsx:1334) uzun AD takım adı 390 / 768'de
 *    üç noktayla kesiliyor; oluşturma ekranının Takım adımında (WeeklyReportCreate.jsx:197, `inline-flex w-fit`) ad kartın ve
 *    ekranın dışına taşıp kenar çubuğu içeriğinde kırpılıyor.
 *  - Sayı alanı etiketi `truncate` (weekly/WeeklyMdField.jsx:304): 768 dokunmatikte "In progress", "Postmortem Records" kesiliyor.
 *  - Dokunma hedefi (CLAUDE.md "Mobile web is a requirement", ≥ 40 px): bağlantı alanının "Bağlantı ekle" düğmesi
 *    (weekly/WeeklyLinkField.jsx:167, `size="icon-xs"`) dokunmatikte 24×24; başlıktaki takım rozeti (ui/TeamBadge.jsx:25,
 *    `after:-inset-y-2.5` → 18 + 20 px) 38 px.
 */
const KNOWN_CLIP = [/span\.truncate @(page-meta>)?team-badge /, /^label\[label\].*\[own box\]/]
const KNOWN_SMALL = [/@page-meta>team-badge \d+×38 "Show members of team/, /@wr-link-field>wr-link-input>input-group-addon>button 24×24 "Add link/]
const KNOWN = { allowClip: KNOWN_CLIP, allowSmall: KNOWN_SMALL }

const deferred = () => { let release; const gate = new Promise((r) => { release = r }); return { gate, release } }
const toast = (page, re) => page.locator('[data-sonner-toast]').filter({ hasText: re })

for (const vp of VIEWPORTS) {
  test.describe(`Haftalık raporlar — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('yeni rapor: global olmayan kullanıcı takım göndermez; 400 field=team_id metni Takım adımının altında', async ({ page }) => {
      test.setTimeout(120_000)
      const state = await weeklyMock(page, {
        rows: [report(5, 'APPROVED', { report_year: OLDER.year, week_no: OLDER.week, sent_at: '2026-10-30T10:00:00', approved_by: 'po' })],
        onCreate: () => ({ status: 400, body: { success: false, code: 'VALIDATION_FAILED', error: TEAM_ID_ERROR, field: 'team_id', fields: { team_id: TEAM_ID_ERROR } } }),
      })
      await page.goto('/?tab=weeklyreports')
      await page.locator('[data-tour="wr-new"]').click({ timeout: 20_000 })
      const screen = page.locator('[data-slot="wr-create"]')
      await expect(screen).toBeVisible()
      // Global olmayan: takım seçici yok, kendi takımı (uzun AD adı) rozet olarak
      await expect(screen.getByRole('combobox', { name: /^Team$/ })).toHaveCount(0)
      await expect(screen.getByText('The report is created for your team.')).toBeVisible()
      await screen.getByRole('button', { name: /Last week/ }).click()
      await screen.getByText('Start from last week\'s notes').click()
      const cta = screen.locator('[data-slot="wr-create-submit"]')
      await expect(cta).toBeEnabled({ timeout: 10_000 })
      await checkLayout(page, `create@${vp.name}`, undefined, KNOWN)
      await cta.click()
      await expect.poll(() => state.creates.length).toBe(1)
      expect(state.creates[0]).toEqual({ year: PREV.year, week_no: PREV.week, carry_notes: true })
      expect('team_id' in state.creates[0], 'team_id gönderilmez').toBe(false)
      // Sunucunun metni TAKIM adımının altında; ekran açık, istek tekrarlanmaz
      const err = screen.locator('[data-field="team_id"] [data-slot="wr-create-team-error"]')
      await expect(err).toHaveText(TEAM_ID_ERROR)
      await expect(screen.locator('[data-field="team_id"]')).toHaveAttribute('data-invalid', 'true')
      await expect(screen).toBeVisible()
      await checkLayout(page, `create error@${vp.name}`, undefined, KNOWN)
    })

    test('kaydet: PUT version taşır; kayıt uçuştayken yazılan kaybolmaz ve yeni sürümle gönderilir', async ({ page }) => {
      test.setTimeout(120_000)
      const first = deferred()
      const state = await weeklyMock(page, {
        rows: [DRAFT],
        onPut: async (b, n) => { if (n === 1) await first.gate; return { success: true, data: { version: b.version + 1, status: 'DRAFT' } } },
      })
      await page.goto('/?tab=weeklyreports&w_id=11')
      const bar = page.locator('[data-slot="wr-actions"]')
      await expect(bar).toBeVisible({ timeout: 20_000 })
      const urgent = page.getByLabel('Urgent', { exact: true })
      const high = page.getByLabel('High', { exact: true })
      await expect(urgent).toBeEnabled({ timeout: 10_000 })   // kilit alındı → düzenlenebilir
      await checkLayout(page, `editor@${vp.name}`, undefined, KNOWN)
      const saveState = bar.locator('[data-slot="wr-save-state"]')
      const saveBtn = bar.getByRole('button', { name: 'Save draft', exact: true })
      await expect(saveBtn).toBeDisabled()

      await urgent.fill('3')
      await expect(saveState).toHaveAttribute('data-state', 'dirty')
      await saveBtn.click()
      await expect.poll(() => state.puts.length).toBe(1)
      await expect(saveState).toHaveAttribute('data-state', 'saving')
      // Kayıt uçuştayken yazılan
      await high.fill('5')
      first.release()
      await expect(toast(page, /Report saved/)).toBeVisible()
      expect(state.puts[0].version).toBe(4)
      const sent1 = JSON.parse(state.puts[0].content_json)
      expect(sent1.item1).toMatchObject({ urgent: 3, high: 0, total: 3 })
      // Yazılan yerinde; içerik kirli kalır (ikinci kayıt gerekiyor)
      await expect(high).toHaveValue('5')
      await expect(saveState).toHaveAttribute('data-state', 'dirty')
      await expect(saveBtn).toBeEnabled()
      await saveBtn.click()
      await expect.poll(() => state.puts.length).toBe(2)
      expect(state.puts[1].version, 'ikinci kayıt ilk kaydın döndürdüğü sürümle').toBe(5)
      expect(JSON.parse(state.puts[1].content_json).item1).toMatchObject({ urgent: 3, high: 5, total: 8 })
      await expect(saveState).toHaveAttribute('data-state', 'saved')
      await expect(saveBtn).toBeDisabled()
      await expect(urgent).toHaveValue('3')
      await expect(high).toHaveValue('5')
    })

    test('onaya gönder: uçuştaki otomatik kaydı bekler, sonra submit POST eder (sahte saat)', async ({ page }) => {
      test.setTimeout(120_000)
      const auto = deferred()
      const state = await weeklyMock(page, {
        rows: [DRAFT],
        onPut: async (b) => { await auto.gate; return { success: true, data: { version: b.version + 1, status: 'DRAFT' } } },
      })
      await page.clock.install()
      await page.goto('/?tab=weeklyreports&w_id=11')
      const bar = page.locator('[data-slot="wr-actions"]')
      const urgent = page.getByLabel('Urgent', { exact: true })
      await expect(urgent).toBeEnabled({ timeout: 20_000 })
      await urgent.fill('2')
      await expect(bar.locator('[data-slot="wr-save-state"]')).toHaveAttribute('data-state', 'dirty')

      // Onay penceresi açık…
      await bar.getByRole('button', { name: 'Submit for Approval', exact: true }).click()
      const dlg = page.getByRole('dialog').filter({ hasText: 'Submit for approval?' })
      await expect(dlg).toBeVisible()
      await checkLayout(page, `submit dialog@${vp.name}`, '[role="dialog"]')
      // …otomatik kayıt (60 sn aralık) başlar ve uçuşta kalır
      await page.clock.runFor(61_000)
      await expect.poll(() => state.puts.length).toBe(1)
      await dlg.getByRole('button', { name: 'Submit for Approval', exact: true }).click()
      await expect(dlg).toHaveCount(0)
      // Kayıt bitmeden gönderim yok
      await page.waitForTimeout(400)
      expect(state.submits).toHaveLength(0)
      auto.release()
      await expect.poll(() => state.submits.length).toBe(1)
      expect(state.submits[0]).toBe(11)
      expect(state.puts, 'ikinci bir kayıt yapılmaz').toHaveLength(1)
      expect(JSON.parse(state.puts[0].content_json).item1).toMatchObject({ urgent: 2, total: 2 })
      expect(state.log.indexOf('POST /api/weekly-reports/11/submit')).toBeGreaterThan(state.log.indexOf('PUT-done /api/weekly-reports/11'))
      await expect(toast(page, /Report submitted for PO approval/)).toBeVisible()
      // Rapor onay bekliyor: düzenleme / gönder düğmeleri gider, Onayla görünür
      await expect(bar.getByRole('button', { name: 'Submit for Approval', exact: true })).toHaveCount(0)
      await expect(bar.getByRole('button', { name: 'Approve', exact: true })).toBeVisible()
    })

    test('onayla: mail_status her sonuç için doğru ileti (SENT / QUEUED / kapalı / pasif / FAILED)', async ({ page }) => {
      test.setTimeout(150_000)
      const statuses = ['SENT', 'QUEUED_RETRY: 421 4.7.0 try again later', 'SKIPPED_DISABLED', 'SKIPPED: pasif kullanıcı', 'FAILED: 550 5.1.1 mailbox unavailable']
      const state = await weeklyMock(page, {
        rows: [PENDING],
        approve: statuses.map((s) => ({ status: 200, body: { success: true, mail_status: s } })),
      })
      await page.goto('/?tab=weeklyreports&w_id=12')
      const bar = page.locator('[data-slot="wr-actions"]')
      const approveBtn = bar.getByRole('button', { name: 'Approve', exact: true })
      await expect(approveBtn).toBeEnabled({ timeout: 20_000 })
      await checkLayout(page, `pending editor@${vp.name}`, undefined, KNOWN)
      const expected = [
        /Report approved — mail sent to the manager/,
        /Report approved; the e-mail to the manager could not be delivered yet and the system will retry shortly/,
        /Report approved, but NO e-mail went to the manager: e-mail sending is turned off in the system/,
        /Report approved, but NO e-mail went to the manager: every recipient is an inactive account/,
        /Report approved, but the e-mail to the manager could not be sent \(the mail server refused it\)/,
      ]
      for (let i = 0; i < statuses.length; i++) {
        await expect(approveBtn).toBeEnabled()
        await approveBtn.click()
        const dlg = page.getByRole('dialog').filter({ hasText: 'Approve Report' })
        await expect(dlg).toBeVisible()
        if (i === 0) await checkLayout(page, `approve dialog@${vp.name}`, '[role="dialog"]')
        await dlg.getByRole('button', { name: 'Approve', exact: true }).click()
        await expect.poll(() => state.approves.length).toBe(i + 1)
        await expect(toast(page, expected[i]), `mail_status ${statuses[i]}`).toBeVisible()
        // Başarı iletisi asla posta sorunu olan sonuçta görünmez
        if (i > 0) await expect(toast(page, /Report approved — mail sent to the manager/)).toHaveCount(0)
        await page.locator('[data-sonner-toast]').evaluateAll((els) => els.forEach((e) => e.querySelector('[data-close-button]')?.click()))
        await expect(page.locator('[data-sonner-toast]')).toHaveCount(0)
      }
      expect(state.approves).toEqual([12, 12, 12, 12, 12])
    })

    test('409 WEEKLY_REPORT_STATE_CHANGED sunucu metni; iade: not zorunlu, POST gövdesinde not', async ({ page }) => {
      test.setTimeout(120_000)
      const state = await weeklyMock(page, {
        rows: [PENDING],
        approve: [{ status: 409, body: { success: false, code: 'WEEKLY_REPORT_STATE_CHANGED', error: STATE_CHANGED, current_status: 'APPROVED' } }],
      })
      await page.goto('/?tab=weeklyreports&w_id=12')
      const bar = page.locator('[data-slot="wr-actions"]')
      const approveBtn = bar.getByRole('button', { name: 'Approve', exact: true })
      await expect(approveBtn).toBeEnabled({ timeout: 20_000 })
      await approveBtn.click()
      await page.getByRole('dialog').filter({ hasText: 'Approve Report' }).getByRole('button', { name: 'Approve', exact: true }).click()
      await expect.poll(() => state.approves.length).toBe(1)
      await expect(toast(page, STATE_CHANGED)).toBeVisible()
      await expect(toast(page, /mail sent to the manager/)).toHaveCount(0)

      // İade et: boş not gönderilmez
      await bar.getByRole('button', { name: 'Return', exact: true }).click()
      const modal = page.getByRole('dialog').filter({ hasText: 'Return Report' })
      await expect(modal).toBeVisible()
      await checkLayout(page, `reject modal@${vp.name}`, '[role="dialog"]')
      await modal.getByRole('button', { name: 'Return', exact: true }).click()
      await expect(toast(page, 'A correction note is required')).toBeVisible()
      expect(state.rejects).toHaveLength(0)
      const note = 'Bölüm 2\'deki olay kayıtları eksik — lütfen açık postmortem bağlantılarını da ekleyin.'
      await modal.getByRole('textbox').fill(note)
      await modal.getByRole('button', { name: 'Return', exact: true }).click()
      await expect.poll(() => state.rejects.length).toBe(1)
      expect(state.rejects[0]).toEqual({ note })
      await expect(toast(page, 'Report returned — the team has been notified')).toBeVisible()
      await expect(modal).toHaveCount(0)
    })
  })
}
