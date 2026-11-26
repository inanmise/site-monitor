// Nöbet Rutinleri (2026-11-26) — gerçek tarayıcıda 390 (dokunmatik) / 768 (dokunmatik) / 1280. Rutin uçları DURUMLU sahte
// sunucuyla (`support/oncallRoutineMocks.js`) yanıtlanır; her yazma isteğinin gövdesi sözleşmeyle (§4) birebir karşılaştırılır.
//  1) Bugün: başlık sayacı (1/5, 1 gecikmiş), önceki iş günü bandı, 6 bağlantılı görev + "Tümünü aç", gecikme rozeti, canlı
//     sayaç tonları; tıkla işaretle, (dizüstü) j / k / x kısayolları, hızlı not çipi (800 ms sonra kaydedilir, Esc kapatır),
//     "Atla…" gerekçesiyle; alt çubuk sayacı.
//  2) Sorun: görev satırından sorun kutusu, PNG YAPIŞTIRILIR, kayıt + görselin ayrı yüklenmesi, görev "yapıldı" olur.
//  3) Bölümü tamamla + Geri al (toplu istek iki yönde), yapışkan alt çubuk son görevi örtmez, günü tamamla penceresi
//     (bekleyen 4 görev → atlama gerekçesi zorunlu) → COMPLETED_PARTIAL.
//  4) Hafta şeridi (bugün / gelecek kilitli, önceki hafta, geçmiş gün), önceki nöbetin devri "Devraldım", günü üstlenme.
//  5) Hafta görünümü: tablo (≥ 760 px) / kart, haftalık + aylık bant, hafta gezintisi, günü aç.
//  6) Şablon: bölüm / görev ekle, taşı, geçersiz bağlantı alanın ALTINDA ve kayıt gitmez, kayıt gövdesi, 409 çakışma bandı.
//  7) Wiki'den içe aktar: HTML yapıştır (betik çalışmaz), 5 bölüm / 21 görev / 17 bağlantı, ekle kipi, kayıt kaynağı IMPORT.
//  8) Geçmiş: ısı haritası (kap ≥ 900 px yıl ızgarası, daha dar ay takvimi) → günü aç.
//  9) İstatistik: Excel dışa aktarımı (dosya adı + 6 sayfa).
// 10) Ayarlar: iş günü + hatırlatma saati, yalnız DEĞİŞEN alanlar gönderilir.
// Her testte: sayfa yatay kaymaz, metin kırpılmaz, dokunmatikte hedefler ≥ 40 px (layoutAudit). Tam sayfa görüntüsü EN SONDA
// alınır (fullPage görüntüsü Chromium'un dokunmatik öykünmesini sıfırlar).
import { test, expect } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { unzipSync, strFromU8 } from 'fflate'
import { EN } from '../src/i18n/en.js'
import { mockApi } from './support/monitorMocks.js'
import { VIEWPORTS, checkLayout, expectInViewport } from './support/layoutAudit.js'
import {
  mockOncallRoutines, orPng,
  OR_TEAM_ID, OR_TODAY, OR_NOW, OR_WEEK_KEY, OR_PREV_WEEK_KEY, OR_MONTH_KEY, OR_EXPORT_SLUG,
  OR_ME, OR_AYSE, OR_MEHMET, OR_SELIN, OR_TITLES,
} from './support/oncallRoutineMocks.js'

const PAGE = '[data-slot="or-page"]'
const API = '/api/oncall-routines'
const PNG_B64 = orPng(64, 48).toString('base64')
const WIKI_HTML = readFileSync(new URL('../src/test/fixtures/oncallRoutineWiki.html', import.meta.url), 'utf8')
const WIKI_MD = readFileSync(new URL('../src/test/fixtures/oncallRoutineWiki.md', import.meta.url), 'utf8')


/** EN sözlükten metin; `{0}` `{1}` … yer tutucuları sırayla doldurulur. Anahtar yoksa test hemen düşer. */
function tx(key, ...args) {
  const s = EN[key]
  if (typeof s !== 'string') throw new Error(`EN key missing: ${key}`)
  return args.reduce((acc, a, i) => acc.split(`{${i}}`).join(String(a)), s)
}

const dayKey = (task, date = OR_TODAY) => `DAY:${date}:${task}`
const row = (page, key) => page.locator(`[data-slot="or-task"][data-key="${key}"]`)
const reqs = (st, method, path) => st.requests.filter((r) => r.method === method && r.path === `${API}${path}`)
const team = (sub) => `/teams/${OR_TEAM_ID}${sub}`

/** Saat sabit (İstanbul 11:41), dil İngilizce, genel sahte API + rutin sahte sunucusu; sayfa açılır ve takım yüklenir. */
async function openRoutines(page, search = '') {
  await page.clock.setFixedTime(new Date(OR_NOW))
  await page.addInitScript(() => { try { localStorage.setItem('site-monitor-lang', 'en') } catch { /* yoksay */ } })
  await mockApi(page)
  const st = await mockOncallRoutines(page)
  await page.goto(`/?tab=oncallroutines${search}`)
  await expect(page.locator(`${PAGE}[data-team="${OR_TEAM_ID}"]`)).toBeVisible({ timeout: 30_000 })
  return st
}

/** Hedefe PNG panosu yapıştırır (tarayıcının kendi `paste` olayı; React kökü yakalar). */
async function pastePng(target, name = 'ekran-goruntusu.png') {
  await target.evaluate((node, [b64, fname]) => {
    const bin = atob(b64)
    const bytes = new Uint8Array(bin.length)
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
    const dt = new DataTransfer()
    dt.items.add(new File([bytes], fname, { type: 'image/png' }))
    node.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }))
  }, [PNG_B64, name])
}

/** Hedefe HTML + düz metin panosu yapıştırır (wiki sayfasından kopyalama). */
async function pasteHtml(target, html, text) {
  await target.evaluate((node, [h, tt]) => {
    const dt = new DataTransfer()
    dt.setData('text/html', h)
    dt.setData('text/plain', tt)
    node.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }))
  }, [html, text])
}

/** Sayfayı (ve çubuğun kaydırılan atasını) sonuna kaydırır. */
async function scrollToEnd(page) {
  await page.evaluate(() => {
    window.scrollTo(0, document.documentElement.scrollHeight)
    const bar = document.querySelector('[data-slot="or-bottom-bar"]')
    for (let p = bar?.parentElement; p && p !== document.body; p = p.parentElement) {
      const oy = getComputedStyle(p).overflowY
      if ((oy === 'auto' || oy === 'scroll') && p.scrollHeight > p.clientHeight) p.scrollTop = p.scrollHeight
    }
  })
}

/** Test sonu: tanınmayan rutin ucu yok + tam sayfa görüntüsü (EN SONDA). */
async function finish(page, st, name) {
  expect(st.unknown, 'mock: unrecognised on-call routine endpoints').toEqual([])
  await page.screenshot({ path: test.info().outputPath(`${name}.png`), fullPage: true })
}

for (const vp of VIEWPORTS) {
  test.describe(`Nöbet rutinleri — ${vp.name} (${vp.width})`, () => {
    test.use({
      viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch, timezoneId: 'Europe/Istanbul',
    })

    test('bugün: sayaçlar, bağlantılar, işaretleme, kısayol, not çipi, atlama', async ({ page }) => {
      test.setTimeout(120_000)
      const st = await openRoutines(page)

      const day = page.locator(`[data-slot="or-day"][data-date="${OR_TODAY}"]`)
      await expect(day).toHaveAttribute('data-status', 'OPEN')
      const head = page.locator('[data-slot="or-header-today"]')
      await expect(head).toContainText(tx('or.page.todayCount', 1, 5))
      await expect(head).toContainText(tx('or.bar.overdue', 1))

      // Önceki iş günü (25 Kasım, tamamlanmamış): bekleyen iki zorunlu görev adıyla
      const yest = page.locator('[data-slot="or-yesterday"]')
      await expect(yest).toContainText(OR_TITLES.disk)
      await expect(yest).toContainText(OR_TITLES.backup)
      await expect(page.locator('[data-slot="or-yesterday-open"]')).toBeVisible()

      // Altı bağlantılı görev: altısı da gerçek bağlantı, "Tümünü aç (6)"; boş etiket adresin kendisi olur
      const alarm = row(page, dayKey('t-alarm'))
      await expect(alarm).toHaveAttribute('data-state', 'PENDING')
      await expect(alarm.locator('[data-slot="or-task-link"]')).toHaveCount(6)
      await expect(alarm.locator('[data-slot="or-task-link"]').nth(5)).toContainText('status.example.internal/incidents/open')
      await expect(alarm.locator('[data-slot="or-task-open-links"]')).toHaveText(tx('or.task.openAll', 6))
      // 10:30 penceresi geçti (İstanbul 11:41) → gecikmiş
      await expect(alarm).toHaveAttribute('data-overdue', 'true')
      await expect(alarm.locator('[data-slot="or-task-overdue"]')).toContainText(tx('or.task.overdueAt', '10:30'))
      await expect(page.locator('[data-slot="or-section"][data-section="s-gun-basi"][data-kind="DAY"] [data-slot="or-section-deadline"]'))
        .toHaveAttribute('data-overdue', 'true')
      // Canlı sayaçlar: sunucunun tonu (açık alarm uyarı, düşen izleme kötü); sertifika satırında süresi dolan sertifika
      await expect(alarm.locator('[data-signal="open_alerts"]')).toHaveAttribute('data-tone', 'warn')
      await expect(alarm.locator('[data-signal="open_alerts"]')).toContainText('3')
      await expect(alarm.locator('[data-signal="down_monitors"]')).toHaveAttribute('data-tone', 'bad')
      await expect(row(page, dayKey('t-sertifika')).locator('[data-signal="expiring_certs"]')).toHaveAttribute('data-tone', 'warn')
      // Ayşe gece batch'i işaretlemiş + not yazmış
      await expect(row(page, dayKey('t-batch'))).toHaveAttribute('data-state', 'DONE')
      await expect(row(page, dayKey('t-batch')).locator('[data-slot="or-task-note-text"]')).toContainText('EOD, mutabakat')

      const bar = page.locator('[data-slot="or-bottom-bar"]')
      await expect(bar).toContainText(tx('or.bar.progress', 1, 5))
      await expect(bar).toContainText(tx('or.bar.overdue', 1))

      await checkLayout(page, `today ${vp.name}`, PAGE)

      // Tıkla işaretle → iyimser DONE + gövde sözleşmesi
      await alarm.locator('[data-slot="or-task-check"]').click()
      await expect(alarm).toHaveAttribute('data-state', 'DONE')
      await expect.poll(() => reqs(st, 'POST', team('/items/state')).length).toBe(1)
      expect(reqs(st, 'POST', team('/items/state'))[0].body).toEqual({
        date: OR_TODAY, task_id: 't-alarm', period_kind: 'DAY', period_key: OR_TODAY,
        state: 'DONE', expected_state: 'PENDING', skip_reason: null,
      })
      await expect(alarm.locator('[data-slot="or-task-overdue"]')).toHaveCount(0)

      // Klavye (dizüstü): j / k odak, x işaretle — isteğe bağlı görev (posta kutusu) zorunlu sayacı değiştirmez
      if (!vp.touch) {
        await alarm.locator('[data-slot="or-task-check"]').focus()
        await page.keyboard.press('j')
        await expect(row(page, dayKey('t-batch')).locator('[data-slot="or-task-check"]')).toBeFocused()
        await page.keyboard.press('j')
        await page.keyboard.press('k')
        await page.keyboard.press('j')
        const mail = row(page, dayKey('t-mail'))
        await expect(mail.locator('[data-slot="or-task-check"]')).toBeFocused()
        await expect(mail).toHaveAttribute('data-required', 'false')
        await page.keyboard.press('x')
        await expect(mail).toHaveAttribute('data-state', 'DONE')
        await expect.poll(() => reqs(st, 'POST', team('/items/state')).length).toBe(2)
        expect(reqs(st, 'POST', team('/items/state'))[1].body).toMatchObject({ task_id: 't-mail', state: 'DONE', expected_state: 'PENDING' })
      }

      // Hızlı not çipi: 800 ms bekleme sonrası tek kayıt (sürüm 0), Esc kutuyu kapatır, not satırda görünür
      const disk = row(page, dayKey('t-disk'))
      await disk.locator('[data-slot="or-task-note-toggle"]').click()
      const note = disk.locator('[data-slot="or-task-note"]')
      await expect(note).toBeVisible()
      await note.locator('[data-slot="or-note-chip"]').filter({ hasText: tx('or.note.chip.ok') }).click()
      await expect(note.locator('[data-slot="or-task-note-status"]')).toHaveAttribute('data-status', 'saved', { timeout: 10_000 })
      const notes = reqs(st, 'PUT', team('/items/note'))
      expect(notes).toHaveLength(1)
      expect(notes[0].body).toEqual({
        date: OR_TODAY, task_id: 't-disk', period_kind: 'DAY', period_key: OR_TODAY,
        note: tx('or.note.chip.ok'), note_version: 0,
      })
      await note.locator('textarea').press('Escape')
      await expect(disk.locator('[data-slot="or-task-note"]')).toHaveCount(0)
      await expect(disk.locator('[data-slot="or-task-note-text"]')).toContainText(tx('or.note.chip.ok'))
      expect(reqs(st, 'PUT', team('/items/note'))).toHaveLength(1)

      // Atla…: satır menüsü → gerekçe → SKIPPED + gerekçe gövdede
      const backup = row(page, dayKey('t-yedek'))
      await page.getByRole('button', { name: `${OR_TITLES.backup} — ${tx('or.task.more')}`, exact: true }).click()
      await page.getByRole('menuitem', { name: tx('or.task.skip'), exact: true }).click()
      const reason = 'Yedek sunucusu bakımda; arşiv kopyası yarın sabah ikinci veri merkezine alınacak.'
      await backup.locator('[data-slot="or-skip-reason"]').fill(reason)
      await backup.locator('[data-slot="or-skip-save"]').click()
      await expect(backup).toHaveAttribute('data-state', 'SKIPPED')
      await expect(backup.locator('[data-slot="or-task-meta"]')).toContainText(reason)
      await expect.poll(() => reqs(st, 'POST', team('/items/state')).find((r) => r.body?.task_id === 't-yedek')?.body ?? null).toEqual({
        date: OR_TODAY, task_id: 't-yedek', period_kind: 'DAY', period_key: OR_TODAY,
        state: 'SKIPPED', expected_state: 'PENDING', skip_reason: reason,
      })

      // Alarm (DONE) + batch (DONE) + yedek (SKIPPED) = 3 / 5, gecikme yok
      await expect(bar).toContainText(tx('or.bar.progress', 3, 5))
      await expect(bar).not.toContainText(tx('or.bar.overdue', 1))

      await finish(page, st, `or-today-${vp.name}`)
    })

    test('sorun: satırdan aç, görsel yapıştır, kaydet + görseli yükle', async ({ page }) => {
      test.setTimeout(120_000)
      const st = await openRoutines(page)
      const cert = row(page, dayKey('t-sertifika'))
      await expect(cert).toHaveAttribute('data-state', 'PENDING')

      await cert.locator('[data-slot="or-task-problem"]').click()
      const composer = cert.locator('[data-slot="or-problem-composer"]')
      await expect(composer).toBeVisible()
      const text = composer.locator('[data-slot="or-problem-note"]')
      await expect(text).toBeFocused()
      await pastePng(text)
      await expect(composer.locator('[data-slot="or-problem-queued-image"]')).toHaveCount(1, { timeout: 15_000 })
      const noteText = 'Ödeme API sertifikası 12 gün sonra doluyor; yenileme kaydı açıldı, sağlayıcı yanıtı bekleniyor.'
      await text.fill(noteText)

      await checkLayout(page, `problem composer ${vp.name}`, PAGE)

      await composer.locator('[data-slot="or-problem-save"]').click()
      await expect.poll(() => reqs(st, 'POST', team('/problems')).length).toBe(1)
      expect(reqs(st, 'POST', team('/problems'))[0].body).toEqual({
        date: OR_TODAY, severity: 'MEDIUM', note: noteText, mark_done: true,
        task_id: 't-sertifika', period_kind: 'DAY', period_key: OR_TODAY,
      })
      // Görsel sorun kaydedildikten SONRA, tek başına yüklenir (çok parçalı gövdede gerçek PNG)
      await expect.poll(() => reqs(st, 'POST', '/problems/950/images').length, { timeout: 15_000 }).toBe(1)
      expect(reqs(st, 'POST', '/problems/950/images')[0].png).toMatchObject({ signature: true, ihdr: true, width: 64, height: 48 })

      // Kutu kapanır; yeni sorun kartı görselle; görev "yapıldı" ve sorun sayısını taşır
      await expect(cert.locator('[data-slot="or-problem-composer"]')).toHaveCount(0, { timeout: 15_000 })
      const card = page.locator('[data-slot="or-problem"][data-id="950"]')
      await expect(card).toBeVisible()
      await expect(card).toContainText(noteText)
      await expect(card.locator('[data-slot="or-problem-images"]')).toHaveAttribute('data-count', '1')
      await expect(cert).toHaveAttribute('data-state', 'DONE')
      await expect(cert.locator('[data-slot="or-task-problem"]')).toHaveAttribute('aria-label', tx('or.task.problemWithCount', 1))
      // Önceki günün açık sorunu (yüksek, görselli) listede kalır
      await expect(page.locator('[data-slot="or-problem"][data-id="901"]')).toBeVisible()

      await checkLayout(page, `problem saved ${vp.name}`, PAGE)
      await finish(page, st, `or-problem-${vp.name}`)
    })

    test('bölümü tamamla + geri al, alt çubuk, günü tamamla (atlama gerekçesi)', async ({ page }) => {
      test.setTimeout(120_000)
      const st = await openRoutines(page)
      const section = page.locator('[data-slot="or-section"][data-section="s-gun-ici"][data-kind="DAY"]')
      const keys = ['t-disk', 't-sertifika', 't-yedek']

      await section.locator('[data-slot="or-section-complete"]').click()
      await expect.poll(() => reqs(st, 'POST', team('/items/bulk-state')).length).toBe(1)
      expect(reqs(st, 'POST', team('/items/bulk-state'))[0].body).toEqual({
        date: OR_TODAY, state: 'DONE',
        items: keys.map((k) => ({ task_id: k, period_kind: 'DAY', period_key: OR_TODAY, expected_state: 'PENDING' })),
      })
      for (const k of keys) await expect(row(page, dayKey(k))).toHaveAttribute('data-state', 'DONE')
      await expect(page.getByText(tx('or.sec.doneToast', 3, 'Gün içi'))).toBeVisible()

      // Geri al (10 sn içinde): aynı üç görev DONE → PENDING
      await page.locator('[data-slot="toast-action"]').filter({ hasText: tx('or.undo') }).click()
      await expect.poll(() => reqs(st, 'POST', team('/items/bulk-state')).length).toBe(2)
      expect(reqs(st, 'POST', team('/items/bulk-state'))[1].body).toEqual({
        date: OR_TODAY, state: 'PENDING',
        items: keys.map((k) => ({ task_id: k, period_kind: 'DAY', period_key: OR_TODAY, expected_state: 'DONE' })),
      })
      for (const k of keys) await expect(row(page, dayKey(k))).toHaveAttribute('data-state', 'PENDING')
      await expect(page.getByText(tx('or.sec.undone', 3))).toBeVisible()

      const bar = page.locator('[data-slot="or-bottom-bar"]')
      await expect(bar).toContainText(tx('or.bar.progress', 1, 5))

      // Yapışkan alt çubuk, sayfa sonuna kaydırılınca son görev satırını örtmez
      await scrollToEnd(page)
      const lastBox = await page.locator('[data-slot="or-task"]').last().boundingBox()
      const barBox = await bar.boundingBox()
      expect(lastBox && barBox, 'last task row / bottom bar boxes').toBeTruthy()
      expect(lastBox.y + lastBox.height, 'last task row ends above the sticky bottom bar').toBeLessThanOrEqual(barBox.y + 1)
      await expectInViewport(bar, vp, 'bottom bar after scrolling to the end')

      // Günü tamamla: 4 zorunlu görev bekliyor → atlama işareti + gerekçe olmadan onay kapalı
      await page.locator('[data-slot="or-complete"]').click()
      const dlg = page.locator('[data-slot="or-complete-dialog"]')
      await expect(dlg).toBeVisible()
      await expect(dlg.locator('[data-slot="or-complete-tile"][data-tile="pending"]')).toContainText('4')
      await expect(dlg.locator('[data-slot="or-complete-tile"][data-tile="done"]')).toContainText('1/5')
      await expect(dlg.locator('[data-slot="or-complete-pending"] li')).toHaveCount(4)
      await expect(dlg).toContainText(tx('or.complete.onBehalf', OR_AYSE.name))
      await expect(dlg.locator('[data-slot="or-complete-notice"]')).toContainText(tx('or.complete.noticeBoth'))
      const confirm = page.locator('[data-slot="or-complete-confirm"]')
      await expect(confirm).toBeDisabled()
      await dlg.locator('[data-slot="or-complete-skip"]').click()
      const skipReason = dlg.locator('[data-slot="or-complete-skip-reason"]')
      await expect(skipReason).toBeFocused()
      const why = 'Gün içi kontroller vardiya sonunda yetişmedi; yarın sabah ilk iş tamamlanacak.'
      await skipReason.fill(why)
      await expect(confirm).toBeEnabled()

      await checkLayout(page, `complete dialog ${vp.name}`, '[role="dialog"]')
      await expectInViewport(page.locator('[role="dialog"]').last(), vp, 'complete dialog')

      await confirm.click()
      await expect.poll(() => reqs(st, 'POST', team(`/days/${OR_TODAY}/complete`)).length).toBe(1)
      expect(reqs(st, 'POST', team(`/days/${OR_TODAY}/complete`))[0].body).toEqual({ version: 3, skip_pending: { reason: why }, note: null })
      await expect(dlg).toHaveCount(0)
      await expect(page.locator(`[data-slot="or-day"][data-date="${OR_TODAY}"]`)).toHaveAttribute('data-status', 'COMPLETED_PARTIAL')
      await expect(page.getByText(tx('or.complete.done'))).toBeVisible()
      await expect(bar).toContainText(OR_ME.name)
      // Atlama gerekçesiyle tamamlanan gün KISMEN tamamlandı: çubuk amber "Kısmen tamamlandı" der (inceleme #15), "Tamamlandı" değil
      await expect(bar).toHaveAttribute('data-state', 'partial')
      await expect(bar.locator('[data-slot="or-bar-state"]')).toHaveText(tx('or.status.COMPLETED_PARTIAL'))
      await expect(page.locator('[data-slot="or-reopen"]')).toBeVisible()
      for (const k of ['t-alarm', 't-disk', 't-sertifika', 't-yedek']) {
        await expect(row(page, dayKey(k))).toHaveAttribute('data-state', 'SKIPPED')
      }

      await checkLayout(page, `completed day ${vp.name}`, PAGE)
      await finish(page, st, `or-complete-${vp.name}`)
    })

    test('hafta şeridi, devir onayı, günü üstlenme, geçmiş güne gitme', async ({ page }) => {
      test.setTimeout(120_000)
      const st = await openRoutines(page)

      const strip = page.locator('[data-slot="or-week-strip"]')
      await expect(strip.locator('[data-slot="or-week-day"]')).toHaveCount(7)
      await expect(strip.locator(`[data-slot="or-week-day"][data-date="${OR_TODAY}"]`)).toHaveAttribute('aria-current', 'date')
      await expect(strip.locator('[data-slot="or-week-day"][data-date="2026-11-27"]')).toBeDisabled()   // gelecek gün
      await expect(strip.locator('[data-slot="or-week-today"]')).toBeDisabled()
      await expect(strip.locator('[data-slot="or-week-next"]')).toBeDisabled()                          // gelecek hafta yok
      await expect(strip.locator('[data-slot="or-week-day"][data-date="2026-11-25"]')).toHaveAttribute('data-status', 'INCOMPLETE')

      // Önceki nöbetin (Mehmet, 16–22 Kasım) devri: "Devraldım"
      const hand = page.locator('[data-slot="or-handover"]')
      await expect(hand).toHaveAttribute('data-acked', 'false')
      await expect(hand).toContainText(OR_MEHMET.name)
      await hand.locator('[data-slot="or-handover-ack"]').click()
      await expect(hand).toHaveAttribute('data-acked', 'true')
      await expect(hand.locator('[data-slot="or-handover-acked"]')).toContainText(OR_ME.name)
      expect(reqs(st, 'POST', team(`/handovers/${OR_PREV_WEEK_KEY}/ack`))).toHaveLength(1)
      await expect(page.getByText(tx('or.hand.acked'))).toBeVisible()

      // Günü üstlen (çizelgede Ayşe): kişi listesinden "ben" → atama gövdesi, kart "üstlenildi (gün)" + ben
      const resp = page.locator('[data-slot="or-responsible"]')
      await expect(resp).toHaveAttribute('data-source', 'SCHEDULE')
      await expect(resp).toContainText(OR_AYSE.name)
      await page.getByRole('button', { name: `${tx('or.resp.title')} — ${tx('or.resp.menu')}`, exact: true }).click()
      await page.getByRole('menuitem', { name: tx('or.resp.takeDay'), exact: true }).click()
      await expect.poll(() => reqs(st, 'POST', team('/assignments')).length).toBe(1)
      expect(reqs(st, 'GET', team('/people')).length).toBeGreaterThanOrEqual(1)
      expect(reqs(st, 'POST', team('/assignments'))[0].body).toEqual({ scope: 'DAY', key: OR_TODAY, user_id: OR_ME.user_id })
      await expect(resp).toHaveAttribute('data-source', 'TAKEOVER_DAY')
      await expect(resp).toHaveAttribute('data-me', 'true')
      // Devri yapan (sunucu biçimi `assignment.created_by_name`) kartta yazar
      await expect(resp.locator('[data-slot="or-responsible-assigned-by"]')).toContainText(OR_ME.name)
      await expect(page.getByText(tx('or.resp.tookDay'))).toBeVisible()

      await checkLayout(page, `week strip + handover ${vp.name}`, PAGE)

      // Önceki hafta: aynı gün-7 (19 Kasım, tamamlanmış)
      await strip.locator('[data-slot="or-week-prev"]').click()
      await expect(page).toHaveURL(/or_day=2026-11-19/)
      await expect(page.locator('[data-slot="or-day"][data-date="2026-11-19"]')).toHaveAttribute('data-status', 'COMPLETED')
      // 20 Kasım: kısmen tamamlanmış (Mehmet, İstanbul 18:52), yeniden açılabilir (7 günlük pencere)
      await strip.locator('[data-slot="or-week-day"][data-date="2026-11-20"]').click()
      const past = page.locator('[data-slot="or-day"][data-date="2026-11-20"]')
      await expect(past).toHaveAttribute('data-status', 'COMPLETED_PARTIAL')
      const bar = page.locator('[data-slot="or-bottom-bar"]')
      await expect(bar).toContainText(OR_MEHMET.name)
      await expect(bar).toContainText('18:52')
      await expect(row(page, dayKey('t-yedek', '2026-11-20'))).toHaveAttribute('data-state', 'SKIPPED')
      await expect(page.locator('[data-slot="or-yesterday"]')).toHaveCount(0)   // yalnız bugünde
      // Sonraki hafta düğmesi bugünü (ya da sonrasını) gösterecekse adres temizlenir → bugün
      await strip.locator('[data-slot="or-week-next"]').click()
      await expect(page.locator(`[data-slot="or-day"][data-date="${OR_TODAY}"]`)).toBeVisible()
      await expect(page).not.toHaveURL(/or_day=/)

      await finish(page, st, `or-weekstrip-${vp.name}`)
    })

    test('hafta görünümü: günler, haftalık / aylık bant, gezinme, günü aç', async ({ page }) => {
      test.setTimeout(120_000)
      const st = await openRoutines(page, '&or_view=week')
      await expect(page.locator(PAGE)).toHaveAttribute('data-view', 'week')
      const week = page.locator('[data-slot="or-week"]')
      await expect(week).toHaveAttribute('data-week', OR_WEEK_KEY)
      await expect(week.locator('[data-slot="or-week-row"]')).toHaveCount(7)
      await expect(week.locator(`[data-slot="or-week-row"][data-date="${OR_TODAY}"]`)).toHaveAttribute('data-today', 'true')
      await expect(week.locator('[data-slot="or-week-row"][data-date="2026-11-25"]')).toHaveAttribute('data-status', 'INCOMPLETE')
      // Tablo kap ≥ 760 px; daha dar kapta kart (telefon kesin kart, dizüstü kesin tablo)
      const table = week.locator('[data-slot="or-week-table"]')
      const cards = week.locator('[data-slot="or-week-cards"]')
      if (vp.name === 'phone') await expect(cards).toBeVisible()
      if (vp.name === 'laptop') await expect(table).toBeVisible()
      expect((await table.count()) + (await cards.count())).toBe(1)
      // Gelecek gün ve hafta sonu "günü aç" taşımaz
      await expect(week.locator('[data-slot="or-week-row"][data-date="2026-11-27"] [data-slot="or-week-open-day"]')).toHaveCount(0)
      await expect(week.locator('[data-slot="or-week-row"][data-date="2026-11-28"] [data-slot="or-week-open-day"]')).toHaveCount(0)
      // Haftalık (Cuma) ve aylık (ayın son iş günü) görevler bu haftada
      await expect(row(page, `WEEK:${OR_WEEK_KEY}:t-erisim`)).toBeVisible()
      await expect(row(page, `MONTH:${OR_MONTH_KEY}:t-dr`)).toBeVisible()
      await expect(row(page, `WEEK:${OR_WEEK_KEY}:t-erisim`).locator('[data-slot="or-task-due"]')).toBeVisible()
      await expect(page.locator('[data-slot="or-week-nav-current"]')).toBeDisabled()

      await checkLayout(page, `week view ${vp.name}`, PAGE)

      await page.locator('[data-slot="or-week-nav-prev"]').click()
      await expect(week).toHaveAttribute('data-week', OR_PREV_WEEK_KEY)
      await expect(page).toHaveURL(new RegExp(`or_week=${OR_PREV_WEEK_KEY}`))
      await expect(row(page, `WEEK:${OR_PREV_WEEK_KEY}:t-erisim`)).toHaveAttribute('data-state', 'DONE')
      await expect(page.locator('[data-slot="or-week-nav-current"]')).toBeEnabled()
      await page.locator('[data-slot="or-week-nav-current"]').click()
      await expect(week).toHaveAttribute('data-week', OR_WEEK_KEY)

      await week.locator('[data-slot="or-week-row"][data-date="2026-11-24"] [data-slot="or-week-open-day"]').click()
      await expect(page.locator(PAGE)).toHaveAttribute('data-view', 'today')
      await expect(page.locator('[data-slot="or-day"][data-date="2026-11-24"]')).toHaveAttribute('data-status', 'COMPLETED')
      await expect(page).toHaveURL(/or_day=2026-11-24/)
      await expect(page).not.toHaveURL(/or_view=/)

      await finish(page, st, `or-week-${vp.name}`)
    })

    test('şablon: ekle, taşı, alan hatası kaydı durdurur, kayıt gövdesi, çakışma', async ({ page }) => {
      test.setTimeout(120_000)
      const st = await openRoutines(page, '&or_view=template')
      const editor = page.locator('[data-slot="or-tpl-editor"][data-editable="true"]')
      await expect(editor).toBeVisible({ timeout: 30_000 })
      const sections = editor.locator('[data-slot="or-tpl-section"]')
      await expect(sections).toHaveCount(4)

      // Yeni bölüm sona eklenir ve başlığı odaklanır; içine bir görev
      const NEW_SEC = 'Gece kontrolleri ve vardiya devri'
      const NEW_TASK = 'Gece vardiyasından devralınan açık olayların ve bekleyen değişikliklerin gözden geçirilmesi'
      await editor.locator('[data-slot="or-tpl-add-section"]').click()
      await expect(sections).toHaveCount(5)
      const fresh = sections.last()
      const secTitle = fresh.getByLabel(tx('or.tpl.sectionTitle'), { exact: true })
      await expect(secTitle).toBeFocused()
      await secTitle.fill(NEW_SEC)
      await fresh.locator('[data-slot="or-tpl-add-task"]').click()
      const taskTitle = fresh.getByLabel(tx('or.tpl.taskTitleN', 1), { exact: true })
      await expect(taskTitle).toBeFocused()
      await taskTitle.fill(NEW_TASK)
      // Bölümü bir yukarı: aylık bölümün önüne
      await page.getByRole('button', { name: tx('or.tpl.sectionUp', NEW_SEC), exact: true }).click()
      await expect(editor.locator('[data-slot="or-tpl-section-toggle"]')).toHaveText(
        ['Gün başlangıcı', 'Gün içi', 'Haftalık periyotta', NEW_SEC, 'Aylık Geçiş'])
      // Görevi bir aşağı: alarm görevi batch'in arkasına
      await page.getByRole('button', { name: tx('or.tpl.taskDown', OR_TITLES.alarm), exact: true }).click()
      const firstTasks = editor.locator('[data-slot="or-tpl-section"][data-section="s-gun-basi"] [data-slot="or-tpl-task"]')
      await expect(firstTasks.nth(0)).toHaveAttribute('data-task', 't-batch')
      await expect(firstTasks.nth(1)).toHaveAttribute('data-task', 't-alarm')

      // Geçersiz bağlantı (ftp://): hata alanın ALTINDA, kaydet → "1 alanı düzeltin", istek gitmez
      const alarmTask = editor.locator('[data-slot="or-tpl-task"][data-task="t-alarm"]')
      await alarmTask.locator('[data-slot="or-tpl-task-details"]').click()
      const url1 = alarmTask.getByLabel(tx('or.tpl.linkUrlN', 1), { exact: true })
      await url1.fill('ftp://files.example.internal/gece-raporu')
      const urlField = editor.locator('[data-field="template.s-gun-basi.t-alarm.links.0.url"]')
      await expect(urlField).toContainText(tx('or.tpl.v.urlScheme'))
      await expect(url1).toHaveAttribute('aria-invalid', 'true')
      await editor.locator('[data-slot="or-tpl-save"]').click()
      await expect(editor.locator('[data-slot="or-tpl-invalid"]')).toHaveText(tx('or.tpl.fixFields.one', 1))
      expect(reqs(st, 'PUT', team('/template'))).toHaveLength(0)

      await url1.fill('https://files.example.internal/gece-raporu')
      await expect(urlField).not.toContainText(tx('or.tpl.v.urlScheme'))
      await expect(editor.locator('[data-slot="or-tpl-invalid"]')).toHaveCount(0)
      await editor.locator('[data-slot="or-tpl-save"]').click()
      await expect.poll(() => reqs(st, 'PUT', team('/template')).length).toBe(1)
      const body = reqs(st, 'PUT', team('/template'))[0].body
      expect(body.version).toBe(14)
      expect(body.source).toBe('EDITOR')
      expect(body.template.v).toBe(1)
      expect(body.template.sections.map((s) => s.title)).toEqual(['Gün başlangıcı', 'Gün içi', 'Haftalık periyotta', NEW_SEC, 'Aylık Geçiş'])
      expect(body.template.sections[0].tasks.map((x) => x.id)).toEqual(['t-batch', 't-alarm', 't-mail'])
      const sentAlarm = body.template.sections[0].tasks[1]
      expect(sentAlarm.links).toHaveLength(6)
      expect(sentAlarm.links[0].url).toBe('https://files.example.internal/gece-raporu')
      expect(sentAlarm.signals).toEqual(['open_alerts', 'down_monitors'])
      expect(body.template.sections[3].tasks).toHaveLength(1)
      expect(body.template.sections[3].tasks[0]).toMatchObject({ title: NEW_TASK, required: true, active: true, frequency: 'DAILY', links: [] })
      expect(body.template.sections[4].tasks[0]).toMatchObject({ id: 't-dr', frequency: 'MONTHLY', monthly_rule: 'LAST_WORKDAY' })
      await expect(page.getByText(tx('or.tpl.saved', 15))).toBeVisible()
      await expect(editor.locator('[data-slot="or-tpl-savebar"]')).toHaveAttribute('data-dirty', 'false')

      // Başkası bu arada kaydetti → 409: çakışma bandı kimin / ne zaman, kaydet kilitli
      st.templateConflictNext = true
      await page.getByRole('button', { name: tx('or.tpl.taskUp', OR_TITLES.alarm), exact: true }).click()
      await editor.locator('[data-slot="or-tpl-save"]').click()
      const conflict = editor.locator('[data-slot="or-tpl-conflict"]')
      await expect(conflict).toBeVisible()
      await expect(conflict).toContainText(OR_SELIN.name)
      await expect(editor.locator('[data-slot="or-tpl-save"]')).toBeDisabled()
      expect(reqs(st, 'PUT', team('/template'))).toHaveLength(2)

      await checkLayout(page, `template ${vp.name}`, PAGE)
      await finish(page, st, `or-template-${vp.name}`)
    })

    test('wiki içe aktarma: HTML yapıştır, önizleme, ekle, kaydet (IMPORT)', async ({ page }) => {
      test.setTimeout(120_000)
      const st = await openRoutines(page, '&or_view=template')
      const editor = page.locator('[data-slot="or-tpl-editor"][data-editable="true"]')
      await expect(editor).toBeVisible({ timeout: 30_000 })

      await editor.locator('[data-slot="or-tpl-import"]').click()
      const imp = page.locator('[data-slot="or-imp-dialog"]')
      await expect(imp).toBeVisible()
      await pasteHtml(imp.locator('[data-slot="or-imp-input"]'), WIKI_HTML, WIKI_MD)
      await expect(imp.locator('[data-slot="or-imp-format"]')).toHaveAttribute('data-format', 'html')
      const stats = imp.locator('[data-slot="or-imp-stats"]')
      await expect(stats).toContainText(tx('or.imp.statSections', 5))
      await expect(stats).toContainText(tx('or.imp.statTasks', 21))
      await expect(stats).toContainText(tx('or.imp.statLinks', 17))
      await expect(imp.locator('[data-slot="or-imp-mode"]')).toHaveAttribute('data-mode', 'append')
      const confirm = page.locator('[data-slot="or-imp-confirm"]')
      await expect(confirm).toHaveText(tx('or.imp.importN', 21))
      await expect(confirm).toBeEnabled()

      await checkLayout(page, `wiki import ${vp.name}`, '[role="dialog"]')
      await expectInViewport(page.locator('[role="dialog"]').last(), vp, 'wiki import dialog')

      await confirm.click()
      await expect(imp).toHaveCount(0)
      await expect(page.getByText(tx('or.tpl.imported', 21))).toBeVisible()
      // Ekle kipi: aynı adlı bölümler birleşir, yeni bölüm sona eklenir
      const sections = editor.locator('[data-slot="or-tpl-section"]')
      await expect(sections).toHaveCount(5)
      await expect(editor.locator('[data-slot="or-tpl-section"][data-section="s-gun-basi"] [data-slot="or-tpl-section-count"]'))
        .toContainText(tx('or.tpl.taskCount', 11))
      await expect(sections.last().locator('[data-slot="or-tpl-section-toggle"]')).toHaveText('Düzenli Gelen Mail İçerik kontrolleri')
      // Yapıştırılan HTML'deki betik hiçbir zaman çalışmaz
      expect(await page.evaluate(() => window.__wikiImportScriptRan)).toBeUndefined()

      await editor.locator('[data-slot="or-tpl-save"]').click()
      await expect.poll(() => reqs(st, 'PUT', team('/template')).length).toBe(1)
      const body = reqs(st, 'PUT', team('/template'))[0].body
      expect(body.source).toBe('IMPORT')
      expect(body.version).toBe(14)
      expect(body.template.sections).toHaveLength(5)
      expect(body.template.sections.reduce((n, s) => n + s.tasks.length, 0)).toBe(29)
      expect(body.template.sections.map((s) => s.title)).toEqual(
        ['Gün başlangıcı', 'Gün içi', 'Haftalık periyotta', 'Aylık Geçiş', 'Düzenli Gelen Mail İçerik kontrolleri'])
      const cluster = body.template.sections[0].tasks.find((x) => x.title === 'Cluster Monitoring')
      expect(cluster.links).toHaveLength(6)
      expect(body.template.sections[3].tasks.map((x) => [x.frequency, x.monthly_rule])).toEqual(
        [['MONTHLY', 'LAST_WORKDAY'], ['MONTHLY', 'FIRST_WORKDAY']])
      await expect(page.getByText(tx('or.tpl.saved', 15))).toBeVisible()

      await checkLayout(page, `template after import ${vp.name}`, PAGE)
      await finish(page, st, `or-wiki-import-${vp.name}`)
    })

    test('geçmiş: ısı haritası ve günü açma', async ({ page }) => {
      test.setTimeout(120_000)
      const st = await openRoutines(page, '&or_view=history')
      const hist = page.locator('[data-slot="or-history"]')
      await expect(hist).toHaveAttribute('data-state', 'ok', { timeout: 20_000 })
      expect(reqs(st, 'GET', team('/calendar')).length).toBeGreaterThanOrEqual(1)
      const heat = page.locator('[data-slot="or-heatmap"]')
      await expect(heat).toBeVisible()
      // Kip kabın genişliğine göre: ≥ 900 px yıl ızgarası, daha dar ay takvimi (telefon / tablet kesin ay; dizüstünde
      // kap ≈ 936 px — eşiğe yakın, bileşenin kendi ölçüsü karar verir)
      await expect(heat).toHaveAttribute('data-mode', /^(grid|month)$/)
      const mode = await heat.getAttribute('data-mode')
      if (vp.touch) expect(mode).toBe('month')

      // Ay takvimi (ısı haritasının dar kipi) da ölçülür: dokunmatikte ≥ 40 px, olay etiketleri sarar (muafiyet yok)
      await checkLayout(page, `history ${vp.name}`, PAGE)

      if (mode === 'grid') {
        const cell = heat.locator('[data-slot="or-heat-cell"][data-date="2026-11-20"]')
        await expect(cell).toHaveAttribute('data-status', 'COMPLETED_PARTIAL')
        await cell.click()
      } else {
        await heat.locator('[data-slot="month-calendar-day"][data-day="2026-11-20"] [data-slot="month-calendar-day-open"]').click()
      }
      await expect(page.locator(PAGE)).toHaveAttribute('data-view', 'today')
      await expect(page.locator('[data-slot="or-day"][data-date="2026-11-20"]')).toHaveAttribute('data-status', 'COMPLETED_PARTIAL')
      await expect(page).toHaveURL(/or_day=2026-11-20/)

      await finish(page, st, `or-history-${vp.name}`)
    })

    test('istatistik: Excel dışa aktarımı', async ({ page }) => {
      test.setTimeout(120_000)
      const st = await openRoutines(page, '&or_view=stats')
      await expect(page.locator('[data-slot="or-stats"]')).toHaveAttribute('data-state', 'ok', { timeout: 20_000 })
      await checkLayout(page, `stats ${vp.name}`, PAGE)

      const [dl] = await Promise.all([
        page.waitForEvent('download'),
        page.locator('[data-slot="or-export"]').click(),
      ])
      expect(st.lastExport, 'export rows requested').not.toBeNull()
      expect(dl.suggestedFilename()).toBe(`nobet-rutini-${OR_EXPORT_SLUG}-${st.lastExport.from}-${st.lastExport.to}.xlsx`)
      const files = unzipSync(new Uint8Array(readFileSync(await dl.path())))
      const workbook = strFromU8(files['xl/workbook.xml'])
      for (const k of ['summary', 'days', 'items', 'problems', 'taskStats', 'peopleStats']) {
        expect(workbook).toContain(`name="${tx(`or.xl.sheet.${k}`)}"`)
      }
      await expect(page.getByText(tx('or.stats.exportDone', dl.suggestedFilename()))).toBeVisible()

      await finish(page, st, `or-stats-${vp.name}`)
    })

    test('ayarlar: cumartesi iş günü + öğlen hatırlatması, yalnız değişenler', async ({ page }) => {
      test.setTimeout(120_000)
      const st = await openRoutines(page, '&or_view=settings')
      const settings = page.locator('[data-slot="or-settings"][data-editable="true"]')
      await expect(settings).toBeVisible({ timeout: 20_000 })

      await settings.getByRole('checkbox', { name: tx('or.weekday.6'), exact: true }).click()
      await expect(settings.locator('[data-slot="or-set-workday"][data-day="6"]')).toHaveAttribute('data-selected', 'true')
      await settings.locator('[data-slot="or-set-reminder-add"]').click()
      const third = settings.locator('[data-slot="or-set-reminder-time"][data-index="2"]')
      await expect(third).toBeFocused()
      await third.fill('12:00')

      await checkLayout(page, `settings ${vp.name}`, PAGE)

      const saveBar = page.locator('[data-slot="settings-save-bar"]')
      await expect(saveBar).toHaveAttribute('data-dirty', 'true')
      await saveBar.getByRole('button', { name: EN['settings.save'], exact: true }).click()
      await expect.poll(() => reqs(st, 'PUT', team('/settings')).length).toBe(1)
      const body = reqs(st, 'PUT', team('/settings'))[0].body
      expect(body.version).toBe(5)
      expect(Object.keys(body.settings).sort()).toEqual(['reminders', 'workdays'])
      expect(body.settings.workdays).toEqual([1, 2, 3, 4, 5, 6])
      expect(Object.keys(body.settings.reminders)).toEqual(['times'])
      expect([...body.settings.reminders.times].sort()).toEqual(['12:00', '16:30', '17:30'])
      await expect(page.getByText(tx('or.set.saved'))).toBeVisible()
      await expect(saveBar).not.toHaveAttribute('data-dirty', 'true')

      await finish(page, st, `or-settings-${vp.name}`)
    })
  })
}
