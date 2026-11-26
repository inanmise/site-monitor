// Nöbet Rutinleri — inceleme düzeltmeleri (2026-11-26) gerçek tarayıcıda: 390 (dokunmatik) / 768 (dokunmatik) / 1280.
// Rutin uçları DURUMLU sahte sunucuyla (`support/oncallRoutineMocks.js`); bu dosyanın ek kancaları: `transform` (yanıtı
// biçimlendir), `failures` (bir kez 500), `hooks` (yanıtı beklet), `orForceItem` (başkası işaretledi), `orDaySheet` (bayat
// yoklama yanıtı). Kapsam:
//  1) Yapılandırılmamış şablon (düzenleyen "Şablonu oluştur" → boş düzenleyici; düzenleyemeyen yalnız bilgi) + duraklatılmış
//     rutin (kilit PAUSED, sorun eklenmez, gün notu salt okunur).
//  2) Salt okunur görüntüleyenler (paylaşım, AUDIT, üye olmayan): bant, kilitli kutular, hiç yazma isteği yok.
//  3) Şablon sürümleri penceresi: liste, v14 ↔ v13 farkı, ilk sürüm, "Taslağa yükle".
//  4) Çakışmalar: NOTE_CHANGED (benimkini kaydet / onlarınkini al), ITEM_CHANGED (sunucunun durumu + kim), DAY_NOT_READY
//     (tamamla penceresi bekleyenleri gösterir).
//  5) Sorunlar: çöz / yeniden aç; olay kaydı bir sorundan YALNIZ BİR KEZ (sonra bağlı kayıt çipi).
//  6) Yükleme hatası (genel bakış ve gün sayfası 500) → hata bloğu → "Yeniden dene".
//  7) Nöbetçi yok (pasif uyarısı YOK) ve pasif nöbetçi + sunucu biçimli devir `{scope,key,note,created_by_name,created_at}`.
//  8) TOO_OLD / FUTURE gün kilitleri; tüm iş günleri pencere dışındaki eski hafta (`or-week-locked`).
//  9) Klavye: j j x k s Esc / n Esc → odak hep aynı satıra döner, j kaldığı yerden sürer.
// 10) İstatistik: okuma hatası → yeniden dene; göstergeler, çubuklar ve simgeler kırpılmadan.
// 11) Alt çubuk (#15): kısmen tamamlanmış gün amber, tam gün yeşil; güvenli alan dolgusu; çubuk ekrana sığar.
// 12) Yoklama (#2): yazım sürerken çıkan bayat yoklama yanıtı işaretlenen satırı geri almaz (başkasının işareti uygulanır).
// 13) Sunucu inceleme turunun sözleşmesi: yok sayılan devir (`ignored_assignment`), devir özetinde `open_days`, SiteMonitor'un
//     otomatik tamamladığı gün, atama notu 400 `note` alanın altında, gün notu 409 `NOT_WORKDAY`, şablon 512 KB 400 `template`
//     + kayıttan sonra "son kaydeden" yeniden yüklemeden, kısmi canlı sayaç 0 → gri.
// Her testte: sayfa yatay kaymaz, metin kırpılmaz, dokunmatikte hedefler ≥ 40 px (layoutAudit). Tam sayfa görüntüsü EN SONDA.
import { test, expect } from '@playwright/test'
import { EN } from '../src/i18n/en.js'
import { mockApi } from './support/monitorMocks.js'
import { VIEWPORTS, checkLayout, expectInViewport } from './support/layoutAudit.js'
import {
  mockOncallRoutines, orForceItem, orDaySheet,
  OR_TEAM_ID, OR_TODAY, OR_NOW, OR_PERMS, OR_SCOPE,
  OR_AYSE, OR_ME, OR_MEHMET, OR_SELIN, OR_TITLES,
} from './support/oncallRoutineMocks.js'

const PAGE = '[data-slot="or-page"]'
const API = '/api/oncall-routines'
const OLD_DAY = '2026-11-12'          // Perşembe; bugün − 7 = 19 Kasım'dan eski → TOO_OLD (gün durumu MISSED)
const FUTURE_DAY = '2026-11-27'       // Cuma, yarın → FUTURE
const PARTIAL_DAY = '2026-11-20'      // COMPLETED_PARTIAL, düzenleme penceresinde
const DONE_DAY = '2026-11-19'         // COMPLETED, düzenleme penceresinin ilk günü
const OLD_WEEK = '2026-11-09'         // iş günlerinin hepsi bugün − 7'den eski
const OLD_DISK_TITLE = 'Uygulama sunucularında disk doluluk oranlarının kontrol edilmesi'

/** EN sözlükten metin; `{0}` `{1}` … yer tutucuları sırayla doldurulur. Anahtar yoksa test hemen düşer. */
function tx(key, ...args) {
  const s = EN[key]
  if (typeof s !== 'string') throw new Error(`EN key missing: ${key}`)
  return args.reduce((acc, a, i) => acc.split(`{${i}}`).join(String(a)), s)
}

const dayKey = (task, date = OR_TODAY) => `DAY:${date}:${task}`
const row = (page, key) => page.locator(`[data-slot="or-task"][data-key="${key}"]`)
const reqs = (st, method, path) => st.requests.filter((r) => r.method === method && r.path === `${API}${path}`)
const writes = (st) => st.requests.filter((r) => r.method !== 'GET')
const team = (sub) => `/teams/${OR_TEAM_ID}${sub}`
const problemCard = (page, id) => page.locator(`[data-slot="or-problem"][data-id="${id}"]`)

/** Elle çözülen söz — sahte sunucu yanıtını testin istediği ana dek bekletmek için. */
function deferred() {
  let resolve
  const promise = new Promise((r) => { resolve = r })
  return { promise, resolve }
}

/**
 * Sunucu "çöktü" anahtarı: `down` açıkken verilen uca gelen HER istek 500 alır. Tek seferlik hata kuyruğu yerine bu
 * kullanılır: geliştirme sunucusunda React StrictMode açılış okumasını İKİ kez yapar (ilki iptal edilir) — kuyruktaki tek
 * hata iptal edilen isteğe gider ve ekran hiç hata göstermezdi.
 */
function outage(method, path) {
  const s = { down: true }
  s.hook = async ({ method: m, path: p, fail }) => {
    if (!s.down || m !== method || p !== path) return false
    await fail(500, 'INTERNAL_ERROR', 'Sunucu hatası (deneme).')
    return true
  }
  return s
}

/** Odağı bırakır (kısayollar yazı alanında / düğmede değil, sayfa gövdesinde başlasın). */
const blurAll = (page) => page.evaluate(() => { document.activeElement?.blur?.() })

/** Gün sayfasındaki madde (sahte sunucu biçimi: `key` alanı taşır). */
function itemIn(sheet, key) {
  for (const g of sheet?.groups || []) for (const s of g.sections || []) for (const it of s.items || []) if (it.key === key) return it
  return null
}

/** Genel bakışta 41. takımın satırını (ve isteğe bağlı bugün özetini) değiştiren dönüştürücü. */
const patchTeam = (patch, today = null) => (ov) => ({
  ...ov,
  teams: (ov.teams || []).map((x) => (x.team_id === OR_TEAM_ID
    ? { ...x, ...patch, today: today ? { ...x.today, ...today } : x.today }
    : x)),
})

/** Saat sabit (İstanbul 11:41), dil İngilizce, genel sahte API + rutin sahte sunucusu; sayfa açılır. */
async function openRoutines(page, search = '', { api = {}, mock = {}, waitTeam = true, before = null } = {}) {
  await page.clock.setFixedTime(new Date(OR_NOW))
  await page.addInitScript(() => { try { localStorage.setItem('site-monitor-lang', 'en') } catch { /* yoksay */ } })
  await mockApi(page, api)
  const st = await mockOncallRoutines(page, mock)
  if (before) await before(st)
  await page.goto(`/?tab=oncallroutines${search}`)
  if (waitTeam) await expect(page.locator(`${PAGE}[data-team="${OR_TEAM_ID}"]`)).toBeVisible({ timeout: 30_000 })
  return st
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

/** Odaktaki öğenin `data-slot` + satır anahtarı (document.activeElement'ten). */
const focusedOf = (page) => page.evaluate(() => {
  const a = document.activeElement
  if (!a || a === document.body) return null
  return `${a.getAttribute('data-slot') || a.tagName.toLowerCase()}|${a.closest('[data-slot="or-task"]')?.getAttribute('data-key') || ''}`
})

/** Test sonu: tanınmayan rutin ucu yok + tam sayfa görüntüsü (EN SONDA). */
async function finish(page, st, name) {
  expect(st.unknown, 'mock: unrecognised on-call routine endpoints').toEqual([])
  await page.screenshot({ path: test.info().outputPath(`${name}.png`), fullPage: true })
}

// Salt okunur gün sayfası (sunucunun NO_PERMISSION biçimi)
const readOnlyDay = (d) => ({
  ...d, can_work: false, editable: false, locked_reason: 'NO_PERMISSION', can_reopen: false, can_edit_template: false,
  responsible: { ...d.responsible, is_me: false },
})
const AUDIT_PERMS = Object.freeze({
  ...OR_PERMS,
  'oncall_routines.work': { view: true },
  'oncall_routines.manage': { view: true },
  'incidents.manage': { view: true },
  'oncall.manage': { view: true },
})
const READ_ONLY_VARIANTS = [
  {
    name: 'grant share',
    api: {},
    mock: {
      transform: {
        overview: patchTeam({ member: false, can_manage: false, can_work: false, can_edit_template: false }),
        day: readOnlyDay,
      },
    },
  },
  {
    name: 'AUDIT viewer',
    api: { role: 'AUDIT' },
    mock: {
      perms: AUDIT_PERMS,
      scope: { ...OR_SCOPE, global_admin: false, monitoring_all: true },
      transform: {
        overview: patchTeam({ member: false, can_manage: false, can_work: false, can_edit_template: false }),
        day: readOnlyDay,
      },
    },
  },
  {
    name: 'non-member',
    api: { role: 'USER', teamIds: [7] },
    mock: {
      scope: { ...OR_SCOPE, global_admin: false },
      transform: {
        overview: patchTeam({ member: false, can_manage: false, can_work: false, can_edit_template: false }),
        day: readOnlyDay,
      },
    },
  },
]

for (const vp of VIEWPORTS) {
  test.describe(`Nöbet rutinleri inceleme — ${vp.name} (${vp.width})`, () => {
    test.use({
      viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch, timezoneId: 'Europe/Istanbul',
    })

    // ── 1) Yapılandırılmamış şablon + duraklatılmış rutin ───────────────────────────────────────────────────────────
    test('yapılandırılmamış şablon: düzenleyen "Şablonu oluştur" → boş düzenleyici → örnek şablon', async ({ page }) => {
      test.setTimeout(120_000)
      const st = await openRoutines(page, '', {
        mock: {
          transform: {
            overview: patchTeam({ configured: false }, { status: 'NONE', required: null, resolved: null, overdue: 0 }),
            day: (d) => ({ ...d, configured: false, locked_reason: 'NOT_CONFIGURED', editable: false, groups: [] }),
            template: (d) => ({
              ...d, configured: false, template: null, template_version: 0,
              template_updated_by_name: null, template_updated_at: null,
            }),
          },
        },
      })

      await expect(page.getByText(tx('or.day.notConfiguredTitle'), { exact: true })).toBeVisible()
      await expect(page.getByText(tx('or.day.notConfiguredEdit'), { exact: true })).toBeVisible()
      await expect(page.locator('[data-slot="or-header-today"]')).toHaveCount(0)
      await expect(page.locator('[data-slot="or-day"]')).toHaveCount(0)
      const create = page.locator('[data-slot="or-create-template"]')
      await expect(create).toHaveText(tx('or.day.createTemplate'))
      await checkLayout(page, `not configured (editor) ${vp.name}`, PAGE)

      await create.click()
      await expect(page.locator(PAGE)).toHaveAttribute('data-view', 'template')
      const editor = page.locator('[data-slot="or-tpl-editor"]')
      await expect(editor).toHaveAttribute('data-editable', 'true', { timeout: 20_000 })
      const empty = editor.locator('[data-slot="or-empty"]')
      await expect(empty).toContainText(tx('or.tpl.emptyTitle'))
      await expect(empty.locator('[data-slot="or-empty-sample"]')).toBeVisible()
      await expect(empty.locator('[data-slot="or-empty-wiki"]')).toBeVisible()
      await expect(empty.locator('[data-slot="or-empty-blank"]')).toBeVisible()
      // Yapılandırılmamış şablonun sürüm geçmişi yok; kayıt çubuğu da taslak yokken çizilmez
      await expect(editor.locator('[data-slot="or-tpl-versions"]')).toHaveCount(0)
      await expect(editor.locator('[data-slot="or-tpl-savebar"]')).toHaveCount(0)
      await checkLayout(page, `empty template editor ${vp.name}`, PAGE)

      await empty.locator('[data-slot="or-empty-sample"]').click()
      await expect(editor.locator('[data-slot="or-tpl-sections"]')).toBeVisible()
      await expect(editor.locator('[data-slot="or-tpl-savebar"]')).toHaveAttribute('data-dirty', 'true')
      expect(reqs(st, 'GET', '/sample-template')).toHaveLength(1)
      expect(reqs(st, 'GET', '/sample-template')[0].query).toMatchObject({ lang: 'en' })
      // Örnek şablon yalnız taslaktır: kaydedilmedikçe istek gitmez
      expect(reqs(st, 'PUT', team('/template'))).toHaveLength(0)
      await checkLayout(page, `sample template draft ${vp.name}`, PAGE)
      await finish(page, st, `or-rev-not-configured-editor-${vp.name}`)
    })

    test('yapılandırılmamış şablon: düzenleyemeyen yalnız bilgi görür (gün + şablon görünümü)', async ({ page }) => {
      const st = await openRoutines(page, '', {
        mock: {
          transform: {
            overview: patchTeam({ configured: false, can_edit_template: false, can_manage: false },
              { status: 'NONE', required: null, resolved: null, overdue: 0 }),
            day: (d) => ({ ...d, configured: false, locked_reason: 'NOT_CONFIGURED', editable: false, can_edit_template: false, groups: [] }),
            template: (d) => ({
              ...d, configured: false, template: null, template_version: 0, can_edit_template: false,
              template_updated_by_name: null, template_updated_at: null,
            }),
          },
        },
      })

      await expect(page.getByText(tx('or.day.notConfiguredTitle'), { exact: true })).toBeVisible()
      await expect(page.getByText(tx('or.day.notConfigured'), { exact: true })).toBeVisible()
      await expect(page.locator('[data-slot="or-create-template"]')).toHaveCount(0)
      await checkLayout(page, `not configured (viewer) ${vp.name}`, PAGE)

      await page.goto(`/?tab=oncallroutines&or_view=template`)
      const editor = page.locator('[data-slot="or-tpl-editor"]')
      await expect(editor).toHaveAttribute('data-editable', 'false', { timeout: 20_000 })
      await expect(editor.locator('[data-slot="or-tpl-readonly"]')).toContainText(tx('or.tpl.readOnly'))
      await expect(editor.locator('[data-slot="or-empty"]')).toContainText(tx('or.tpl.emptyReadOnly'))
      await expect(editor.locator('[data-slot="or-empty-sample"]')).toHaveCount(0)
      await expect(editor.locator('[data-slot="or-empty-blank"]')).toHaveCount(0)
      await expect(editor.locator('[data-slot="or-tpl-versions"]')).toHaveCount(0)
      expect(writes(st)).toEqual([])
      await checkLayout(page, `read-only empty template ${vp.name}`, PAGE)
      await finish(page, st, `or-rev-not-configured-viewer-${vp.name}`)
    })

    test('duraklatılmış rutin: kilit nedeni PAUSED, sorun eklenmez, gün notu salt okunur', async ({ page }) => {
      const st = await openRoutines(page, '', {
        mock: { transform: { day: (d) => ({ ...d, locked_reason: 'PAUSED', editable: false }) } },
      })
      const day = page.locator(`[data-slot="or-day"][data-date="${OR_TODAY}"]`)
      await expect(day).toBeVisible()
      const lock = page.locator('[data-slot="or-locked"]')
      await expect(lock).toHaveAttribute('data-reason', 'PAUSED')
      await expect(lock).toHaveText(tx('or.lock.PAUSED'))
      await expect(page.locator('[data-slot="or-readonly"]')).toHaveCount(0)      // takımda çalışabilir; yalnız gün kilitli

      await expect(page.locator('[data-slot="or-problem-add-general"]')).toHaveCount(0)
      await expect(page.locator('[data-slot="or-problems"]')).toBeVisible()
      await expect(page.locator('[data-slot="or-day-note"] textarea')).toHaveAttribute('readonly', '')
      const alarm = row(page, dayKey('t-alarm'))
      await expect(alarm.locator('[data-slot="or-task-check"]')).toBeDisabled()
      await expect(alarm.locator('[data-slot="or-task-problem"]')).toBeDisabled()
      await expect(alarm.locator('[data-slot="or-task-note-toggle"]')).toBeDisabled()   // not yok + yazılamaz
      await expect(page.locator('[data-slot="or-bottom-bar"]')).toHaveCount(0)
      await expect(page.locator('[data-slot="or-complete-rail"]')).toHaveCount(0)

      await checkLayout(page, `paused ${vp.name}`, PAGE)
      expect(writes(st)).toEqual([])
      await finish(page, st, `or-rev-paused-${vp.name}`)
    })

    // ── 2) Salt okunur görüntüleyenler ──────────────────────────────────────────────────────────────────────────────
    for (const v of READ_ONLY_VARIANTS) {
      test(`salt okunur (${v.name}): bant, kilitli kutular, hiç yazma isteği yok`, async ({ page }) => {
        const st = await openRoutines(page, '', { api: v.api, mock: v.mock })
        await expect(page.locator(`[data-slot="or-day"][data-date="${OR_TODAY}"]`)).toBeVisible()
        await expect(page.locator('[data-slot="or-readonly"]')).toHaveText(tx('or.page.readOnly'))
        // LockBanner NO_PERMISSION'ı ikinci kez söylemez (sayfa bandı yeter)
        await expect(page.locator('[data-slot="or-locked"]')).toHaveCount(0)

        const checks = page.locator('[data-slot="or-task-check"]')
        expect(await checks.count()).toBeGreaterThan(0)
        for (const c of await checks.all()) await expect(c).toBeDisabled()
        await expect(page.locator('[data-slot="or-task-problem"]').first()).toBeDisabled()
        await expect(page.locator('[data-slot="or-problem-add-general"]')).toHaveCount(0)
        await expect(page.locator('[data-slot="or-problem-resolve"]')).toHaveCount(0)
        await expect(page.locator('[data-slot="or-problem-incident"]')).toHaveCount(0)
        await expect(page.locator('[data-slot="or-bottom-bar"]')).toHaveCount(0)
        await expect(page.locator('[data-slot="or-day-note"] textarea')).toHaveAttribute('readonly', '')
        await expect(page.locator('[data-slot="or-handover-note"] textarea')).toHaveAttribute('readonly', '')
        await expect(page.locator('[data-slot="or-responsible-take-week"]')).toHaveCount(0)

        // Notu olan görev: not okunabilir ama yazılamaz
        const batch = row(page, dayKey('t-batch'))
        await batch.locator('[data-slot="or-task-note-toggle"]').click()
        await expect(batch.locator('[data-slot="or-task-note"] textarea')).toHaveAttribute('readonly', '')
        await expect(batch.locator('[data-slot="or-note-chip"]')).toHaveCount(0)
        await checkLayout(page, `read-only ${v.name} ${vp.name}`, PAGE)
        await batch.locator('[data-slot="or-task-note-toggle"]').click()
        await expect(batch.locator('[data-slot="or-task-note"]')).toHaveCount(0)

        // Klavye de bir şey yazmaz
        await blurAll(page)
        await page.keyboard.press('j')
        await page.keyboard.press('x')
        await page.keyboard.press('s')
        await expect(page.locator('[data-slot="or-skip"]')).toHaveCount(0)
        await expect(row(page, dayKey('t-alarm'))).toHaveAttribute('data-state', 'PENDING')

        expect(writes(st), 'read-only viewer must not write').toEqual([])
        await finish(page, st, `or-rev-readonly-${v.name.replace(/\s+/g, '-')}-${vp.name}`)
      })
    }

    // ── 3) Şablon sürümleri + fark ──────────────────────────────────────────────────────────────────────────────────
    test('şablon sürümleri: liste, v14 ↔ v13 farkı, ilk sürüm, taslağa yükle', async ({ page }) => {
      test.setTimeout(120_000)
      const st = await openRoutines(page, '&or_view=template', {
        mock: {
          transform: {
            // v13: sertifika görevi yoktu, disk görevinin adı kısaydı, posta görevi zorunluydu
            templateVersion: (d, { version }) => {
              if (version !== 13) return d
              const gunIci = d.template.sections.find((s) => s.id === 's-gun-ici')
              gunIci.tasks = gunIci.tasks.filter((t) => t.id !== 't-sertifika')
              gunIci.tasks.find((t) => t.id === 't-disk').title = OLD_DISK_TITLE
              d.template.sections.find((s) => s.id === 's-gun-basi').tasks.find((t) => t.id === 't-mail').required = true
              return d
            },
          },
        },
      })
      const editor = page.locator('[data-slot="or-tpl-editor"]')
      await expect(editor).toHaveAttribute('data-editable', 'true', { timeout: 20_000 })
      await expect(editor.locator('[data-slot="or-tpl-meta"]')).toContainText(tx('or.tpl.versionN', 14))

      await editor.locator('[data-slot="or-tpl-versions"]').click()
      const dlg = page.locator('[data-slot="or-tpl-ver-dialog"]')
      await expect(dlg).toBeVisible()
      await expect(page.getByRole('dialog').getByText(tx('or.tpl.ver.title'), { exact: true })).toBeVisible()
      const items = dlg.locator('[data-slot="or-tpl-ver-item"]')
      await expect(items).toHaveCount(2)
      await expect(items.nth(0)).toHaveAttribute('data-version', '14')
      await expect(items.nth(0)).toContainText(tx('or.tpl.ver.current'))
      await expect(items.nth(1)).toContainText(OR_SELIN.name)
      await expect(items.nth(1)).toContainText(tx('or.tpl.src.IMPORT'))

      const layout = await dlg.getAttribute('data-layout')
      if (vp.name === 'phone') expect(layout).toBe('list')
      if (layout === 'list') {
        await items.nth(0).click()
        await expect(dlg).toHaveAttribute('data-layout', 'detail')
        await expect(dlg.locator('[data-slot="or-tpl-ver-back"]')).toBeFocused()
      }

      const detail = dlg.locator('[data-slot="or-tpl-ver-detail"]')
      await expect(detail).toContainText(tx('or.tpl.ver.compareTitle', 14, 13))
      const diff = detail.locator('[data-slot="or-tpl-ver-diff"]')
      await expect(diff).toBeVisible({ timeout: 15_000 })
      const added = diff.locator('[data-slot="or-tpl-ver-change"][data-kind="added"]')
      await expect(added).toHaveCount(1)
      await expect(added).toContainText(tx('or.tpl.ver.taskAdded', OR_TITLES.cert, 'Gün içi'))
      const changed = diff.locator('[data-slot="or-tpl-ver-change"][data-kind="changed"]')
      await expect(changed).toHaveCount(2)
      await expect(changed.filter({ hasText: tx('or.tpl.ver.taskRenamed', OLD_DISK_TITLE, OR_TITLES.disk) })).toHaveCount(1)
      await expect(changed.filter({ hasText: tx('or.tpl.ver.taskRequiredOff', OR_TITLES.mail) })).toHaveCount(1)
      await expect(diff.locator('[data-slot="or-tpl-ver-change"][data-kind="removed"]')).toHaveCount(0)
      // Güncel sürüm taslağa yüklenmez
      await expect(detail.locator('[data-slot="or-tpl-ver-load"]')).toBeDisabled()
      await expect(detail).toContainText(tx('or.tpl.ver.isCurrent'))
      await checkLayout(page, `template versions diff ${vp.name}`, '[role="dialog"]')
      await expectInViewport(page.locator('[role="dialog"]').last(), vp, 'versions dialog')

      // v13: listedeki en eski → "ilk sürüm" (öncesi yok), tüm içerik "eklendi"
      if (layout === 'list') {
        await dlg.locator('[data-slot="or-tpl-ver-back"]').click()
        await expect(dlg).toHaveAttribute('data-layout', 'list')
        await expect(items.nth(0)).toBeFocused()
        await items.nth(1).click()
        await expect(dlg).toHaveAttribute('data-layout', 'detail')
      } else {
        await items.nth(1).click()
        await expect(items.nth(1)).toHaveAttribute('aria-pressed', 'true')   // dar ekranda ayrıntıdayken liste çizilmez
      }
      await expect(detail).toContainText(tx('or.tpl.versionN', 13))
      await expect(detail).toContainText(tx('or.tpl.ver.firstVersion'))
      await expect(detail.locator('[data-slot="or-tpl-ver-change"][data-kind="added"]').first()).toBeVisible()
      await expect(detail.locator('[data-slot="or-tpl-ver-change"][data-kind="removed"]')).toHaveCount(0)
      await checkLayout(page, `template versions first ${vp.name}`, '[role="dialog"]')

      // Okuma sayıları "en az": geliştirme sunucusunda StrictMode açılış etkisini iki kez koşar
      expect(reqs(st, 'GET', team('/template/versions')).length).toBeGreaterThanOrEqual(1)
      expect(reqs(st, 'GET', team('/template/versions/14')).length).toBeGreaterThanOrEqual(1)
      expect(reqs(st, 'GET', team('/template/versions/13')).length).toBeGreaterThanOrEqual(1)

      // "Taslağa yükle": pencere kapanır, taslak kirli, kayıt gitmez
      const load = detail.locator('[data-slot="or-tpl-ver-load"]')
      await expect(load).toBeEnabled()
      await load.click()
      await expect(dlg).toHaveCount(0)
      await expect(page.getByText(tx('or.tpl.versionLoaded', 13))).toBeVisible()
      await expect(editor.locator('[data-slot="or-tpl-savebar"]')).toHaveAttribute('data-dirty', 'true')
      await expect(editor).not.toContainText(OR_TITLES.cert)
      expect(reqs(st, 'PUT', team('/template'))).toHaveLength(0)
      await checkLayout(page, `template after loading v13 ${vp.name}`, PAGE)
      await finish(page, st, `or-rev-template-versions-${vp.name}`)
    })

    // ── 4) Çakışmalar ───────────────────────────────────────────────────────────────────────────────────────────────
    test('çakışma NOTE_CHANGED: çakışma paneli, benimkini kaydet / onlarınkini al', async ({ page }) => {
      test.setTimeout(120_000)
      const st = await openRoutines(page)
      const SELIN_NOTE = 'EOD işi 02:40\'ta yeniden koştu; mutabakat farkı yok, raporlama ekibine bilgi verildi.'
      const MY_NOTE = 'EOD ve mutabakat tamam; raporlama işi 06:10\'da gecikmeli bitti, izlemeye alındı.'

      // A) t-batch: sayfa notun 1. sürümünü biliyor, Selin bu arada 2. sürümü kaydetti
      const batch = row(page, dayKey('t-batch'))
      await batch.locator('[data-slot="or-task-note-toggle"]').click()
      const note = batch.locator('[data-slot="or-task-note"]')
      await expect(note.locator('textarea')).toHaveValue('EOD, mutabakat ve raporlama işleri sorunsuz tamamlandı.')
      orForceItem(st, dayKey('t-batch'), {
        note: SELIN_NOTE, note_version: 2, note_by_name: OR_SELIN.name, note_at: '2026-11-26T08:35:00Z',
      })
      await note.locator('textarea').fill(MY_NOTE)
      const conflict = note.locator('[data-slot="or-task-note-conflict"]')
      await expect(conflict).toBeVisible({ timeout: 10_000 })
      await expect(conflict).toContainText(tx('or.note.conflict', OR_SELIN.name, '11:35'))
      await expect(conflict).toContainText(SELIN_NOTE)
      await expect(note.locator('[data-slot="or-task-note-status"]')).toHaveAttribute('data-status', 'conflict')
      const puts = () => reqs(st, 'PUT', team('/items/note'))
      expect(puts()).toHaveLength(1)
      expect(puts()[0].body).toEqual({
        date: OR_TODAY, task_id: 't-batch', period_kind: 'DAY', period_key: OR_TODAY, note: MY_NOTE, note_version: 1,
      })
      // Benim metnim kutuda kalır (sessizce ezilmez)
      await expect(note.locator('textarea')).toHaveValue(MY_NOTE)
      await checkLayout(page, `note conflict ${vp.name}`, PAGE)

      await conflict.locator('[data-slot="or-task-note-keep-mine"]').click()
      await expect(note.locator('[data-slot="or-task-note-status"]')).toHaveAttribute('data-status', 'saved', { timeout: 10_000 })
      await expect(conflict).toHaveCount(0)
      expect(puts()).toHaveLength(2)
      expect(puts()[1].body).toMatchObject({ task_id: 't-batch', note: MY_NOTE, note_version: 2 })

      // B) t-alarm: notsuz sanılıyor, Selin bu arada not yazdı → "Onlarınkini al" metni alır, istek gitmez
      const THEIR = 'Kritik alarm #4471 sahiplenildi; CDN sağlayıcısıyla çağrı açık.'
      const alarm = row(page, dayKey('t-alarm'))
      await alarm.locator('[data-slot="or-task-note-toggle"]').click()
      const alarmNote = alarm.locator('[data-slot="or-task-note"]')
      await expect(alarmNote.locator('textarea')).toHaveValue('')
      orForceItem(st, dayKey('t-alarm'), {
        note: THEIR, note_version: 1, note_by_name: OR_SELIN.name, note_at: '2026-11-26T08:36:00Z',
      })
      await alarmNote.locator('textarea').fill('Alarmlar kontrol edildi, iki kritik alarm sahiplenildi.')
      const alarmConflict = alarmNote.locator('[data-slot="or-task-note-conflict"]')
      await expect(alarmConflict).toBeVisible({ timeout: 10_000 })
      expect(puts()).toHaveLength(3)
      expect(puts()[2].body).toMatchObject({ task_id: 't-alarm', note_version: 0 })
      await alarmConflict.locator('[data-slot="or-task-note-take-theirs"]').click()
      await expect(alarmConflict).toHaveCount(0)
      await expect(alarmNote.locator('textarea')).toHaveValue(THEIR)
      await page.waitForTimeout(1_500)   // debounce (800 ms) dolsun: "onlarınkini al" yeni kayıt göndermez
      expect(puts()).toHaveLength(3)
      await finish(page, st, `or-rev-note-conflict-${vp.name}`)
    })

    test('çakışma ITEM_CHANGED: satır sunucunun durumunu gösterir, bildirim kimin değiştirdiğini söyler', async ({ page }) => {
      const st = await openRoutines(page)
      const disk = row(page, dayKey('t-disk'))
      await expect(disk).toHaveAttribute('data-state', 'PENDING')
      const SKIP = 'Disk ve bağlantı havuzu ölçümleri izleme aracından otomatik alındı; elle kontrol gerekmedi.'
      orForceItem(st, dayKey('t-disk'), {
        state: 'SKIPPED', state_by_name: OR_SELIN.name, state_at: '2026-11-26T08:30:00Z', skip_reason: SKIP,
      })

      await disk.locator('[data-slot="or-task-check"]').click()
      await expect.poll(() => reqs(st, 'POST', team('/items/state')).length).toBe(1)
      expect(reqs(st, 'POST', team('/items/state'))[0].body).toMatchObject({ task_id: 't-disk', state: 'DONE', expected_state: 'PENDING' })
      // İyimser DONE geri alınır, sunucunun durumu (Selin atladı) yazılır
      await expect(disk).toHaveAttribute('data-state', 'SKIPPED')
      await expect(disk.locator('[data-slot="or-task-check"]')).toHaveAttribute('data-state', 'unchecked')
      const meta = disk.locator('[data-slot="or-task-meta"]')
      await expect(meta).toContainText(SKIP)
      await expect(meta).toContainText(OR_SELIN.name)
      await expect(page.getByText(tx('or.task.changedBy', OR_SELIN.name, '11:30'))).toBeVisible()
      // Çakışmada ikinci bir yazma denenmez
      await page.waitForTimeout(500)
      expect(reqs(st, 'POST', team('/items/state'))).toHaveLength(1)
      await checkLayout(page, `item changed ${vp.name}`, PAGE)
      await finish(page, st, `or-rev-item-changed-${vp.name}`)
    })

    test('çakışma DAY_NOT_READY: tamamla penceresi bekleyenleri listeler, atlama gerekçesiyle tamamlanır', async ({ page }) => {
      test.setTimeout(120_000)
      const st = await openRoutines(page, '', {
        before: (s) => {
          // Bütün zorunlu gün görevleri tamam (pencere bekleyensiz açılacak)
          for (const id of ['t-alarm', 't-disk', 't-sertifika', 't-yedek']) {
            orForceItem(s, dayKey(id), { state: 'DONE', state_by_name: OR_AYSE.name, state_at: '2026-11-26T08:20:00Z', skip_reason: null })
          }
        },
      })
      const bar = page.locator('[data-slot="or-bottom-bar"]')
      await expect(bar).toContainText(tx('or.bar.progress', 5, 5))
      // Bu arada başka biri yedek görevini geri aldı (sayfa bilmiyor)
      orForceItem(st, dayKey('t-yedek'), { state: 'PENDING', state_by_name: null, state_at: null, skip_reason: null })

      await scrollToEnd(page)
      await bar.locator('[data-slot="or-complete"]').click()
      const dlg = page.locator('[data-slot="or-complete-dialog"]')
      await expect(dlg).toBeVisible()
      await expect(dlg.locator('[data-slot="or-complete-pending"]')).toHaveCount(0)
      const confirm = page.locator('[data-slot="or-complete-confirm"]')
      await expect(confirm).toBeEnabled()
      await confirm.click()

      const completes = () => reqs(st, 'POST', team(`/days/${OR_TODAY}/complete`))
      await expect.poll(() => completes().length).toBe(1)
      expect(completes()[0].body).toEqual({ version: 3, skip_pending: null, note: null })
      // 409 DAY_NOT_READY: sunucunun bekleyen listesi pencerede, hata metni, atlama seçilmeden onay kapalı
      const pending = dlg.locator('[data-slot="or-complete-pending"]')
      await expect(pending.locator('li')).toHaveCount(1)
      await expect(pending).toContainText(OR_TITLES.backup)
      await expect(pending).toContainText('Gün içi')
      await expect(dlg.getByText(tx('or.complete.changedPending'), { exact: true })).toBeVisible()
      await expect(dlg.locator('[data-slot="or-complete-tile"][data-tile="pending"]')).toContainText('1')
      await expect(confirm).toBeDisabled()
      await checkLayout(page, `complete dialog not ready ${vp.name}`, '[role="dialog"]')
      await expectInViewport(page.locator('[role="dialog"]').last(), vp, 'complete dialog')
      // Pencere arkada yeniden okundu: satır da bekliyor
      await expect(row(page, dayKey('t-yedek'))).toHaveAttribute('data-state', 'PENDING')

      await dlg.locator('[data-slot="or-complete-skip"]').click()
      const reason = 'Yedek sunucusu bakımda; arşiv kopyası yarın sabah ikinci veri merkezine alınacak.'
      await dlg.locator('[data-slot="or-complete-skip-reason"]').fill(reason)
      await expect(confirm).toBeEnabled()
      await confirm.click()
      await expect.poll(() => completes().length).toBe(2)
      expect(completes()[1].body).toMatchObject({ version: 3, skip_pending: { reason } })
      await expect(dlg).toHaveCount(0)
      await expect(page.getByText(tx('or.complete.done'))).toBeVisible()
      await expect(page.locator('[data-slot="or-bar-state"]')).toHaveAttribute('data-state', 'partial')
      await checkLayout(page, `completed partial ${vp.name}`, PAGE)
      await finish(page, st, `or-rev-day-not-ready-${vp.name}`)
    })

    // ── 5) Sorunlar: olay kaydı bir kez, çöz / yeniden aç ───────────────────────────────────────────────────────────
    test('sorunlar: olay kaydı yalnız bir kez, çöz / yeniden aç', async ({ page }) => {
      test.setTimeout(150_000)
      const incidentPosts = []
      const DISK_NOTE = 'app-07 sunucusunda disk doluluğu %91; günlük temizliği için altyapı ekibine talep açıldı.'
      const st = await openRoutines(page, '', {
        before: async (s) => {
          s.problems.push({
            id: 902, team_id: OR_TEAM_ID, severity: 'MEDIUM', status: 'OPEN', day: OR_TODAY,
            task_id: 't-disk', period_kind: 'DAY', period_key: OR_TODAY, task_title: OR_TITLES.disk, section_title: 'Gün içi',
            note: DISK_NOTE, created_by_name: OR_MEHMET.name, created_at: '2026-11-26T07:15:00Z',
            updated_by_name: null, updated_at: null, resolved_by_name: null, resolved_at: null, resolution_note: null,
            age_days: 0, can_delete: false, can_edit: true, images: [], incident: null, version: 4,
          })
          // Olay & Hata Geçmişi'nin kendi kayıt ucu (mockApi'den sonra bağlanır → önce çalışır)
          await page.route((u) => new URL(u).pathname === '/api/incidents', async (route) => {
            if (route.request().method() !== 'POST') return route.fallback()
            let body = null
            try { body = JSON.parse(route.request().postData() || 'null') } catch { body = null }
            incidentPosts.push(body)
            return route.fulfill({
              status: 200, contentType: 'application/json',
              body: JSON.stringify({ success: true, data: { id: 3307, title: body?.title || 'Olay', status: 'OPEN' }, timestamp: OR_NOW }),
            })
          })
        },
      })

      const p901 = problemCard(page, 901)
      const p902 = problemCard(page, 902)
      await expect(page.locator('[data-slot="or-problems-count"]')).toHaveText('2')
      // Zaten bağlı sorun: olay çipi, "Olay kaydı oluştur" sunulmaz
      await expect(p901.locator('[data-slot="or-problem-incident-link"]')).toHaveAttribute('data-incident-id', '812')
      await expect(p901.locator('[data-slot="or-problem-incident"]')).toHaveCount(0)
      await expect(p902.locator('[data-slot="or-problem-incident"]')).toBeVisible()
      await checkLayout(page, `problems ${vp.name}`, PAGE)

      // Sorundan olay kaydı: Olay & Hata Geçmişi'nin formu sorundan ön dolu, "Kaydet" → kayıt → soruna bağlanır
      await p902.locator('[data-slot="or-problem-incident"]').click()
      const form = page.getByRole('dialog').last()
      await expect(form).toBeVisible()
      await form.locator('[data-action="save"]').click()
      await expect.poll(() => incidentPosts.length).toBe(1)
      expect(String(incidentPosts[0]?.title || '')).toContain(OR_TITLES.disk)
      expect(incidentPosts[0]).toMatchObject({ severity: 'MEDIUM', status: 'OPEN' })
      await expect(page.getByText(tx('or.prob.incidentCreated', 3307))).toBeVisible()
      await expect.poll(() => reqs(st, 'PUT', '/problems/902/incident').length).toBe(1)
      expect(reqs(st, 'PUT', '/problems/902/incident')[0].body).toEqual({ version: 4, incident_id: 3307 })
      const chip = p902.locator('[data-slot="or-problem-incident-link"]')
      await expect(chip).toHaveAttribute('data-incident-id', '3307')
      await expect(chip).toContainText(tx('or.prob.incidentChip', 3307, tx('inc.stOPEN')))
      // İkinci olay kaydı sunulmaz
      await expect(p902.locator('[data-slot="or-problem-incident"]')).toHaveCount(0)
      expect(incidentPosts).toHaveLength(1)

      // Çöz: not → onay → açık listeden düşer
      await p902.locator('[data-slot="or-problem-resolve"]').click()
      const resolveNote = 'Günlükler arşive taşındı; doluluk %54.'
      await p902.locator('[data-slot="or-problem-resolution-note"]').fill(resolveNote)
      await p902.locator('[data-slot="or-problem-resolve-confirm"]').click()
      await expect(page.getByText(tx('or.prob.resolvedToast'))).toBeVisible()
      await expect(page.locator('[data-slot="or-problems-open"]').locator('[data-slot="or-problem"][data-id="902"]')).toHaveCount(0)
      await expect(page.locator('[data-slot="or-problems-count"]')).toHaveText('1')
      const patches = () => reqs(st, 'PATCH', '/problems/902')
      expect(patches()).toHaveLength(1)
      // Olay bağı sürümü 4 → 5 yaptı (sunucu linkIncident version + 1); çöz isteği o yeni sürümle gider
      expect(patches()[0].body).toMatchObject({ status: 'RESOLVED', resolution_note: resolveNote, version: 5 })

      // Çözülenler → yeniden aç → açık listeye döner; olay çipi korunur, yine ikinci kayıt sunulmaz
      await page.locator('[data-slot="or-problems-resolved-toggle"]').click()
      const resolvedList = page.locator('[data-slot="or-problems-resolved"]')
      const resolved902 = resolvedList.locator('[data-slot="or-problem"][data-id="902"]')
      await expect(resolved902).toHaveAttribute('data-status', 'RESOLVED')
      expect(reqs(st, 'GET', team('/problems')).some((r) => r.query.status === 'resolved')).toBe(true)
      await checkLayout(page, `resolved problems ${vp.name}`, PAGE)
      await resolved902.locator('[data-slot="or-problem-reopen"]').click()
      await expect(page.getByText(tx('or.prob.reopenedToast'))).toBeVisible()
      await expect.poll(() => patches().length).toBe(2)
      expect(patches()[1].body).toMatchObject({ status: 'OPEN', version: 6 })
      const reopened = page.locator('[data-slot="or-problems-open"]').locator('[data-slot="or-problem"][data-id="902"]')
      await expect(reopened).toHaveAttribute('data-status', 'OPEN')
      await expect(reopened.locator('[data-slot="or-problem-incident-link"]')).toHaveAttribute('data-incident-id', '3307')
      await expect(reopened.locator('[data-slot="or-problem-incident"]')).toHaveCount(0)
      expect(incidentPosts).toHaveLength(1)
      await checkLayout(page, `reopened problem ${vp.name}`, PAGE)
      await finish(page, st, `or-rev-problems-${vp.name}`)
    })

    // ── 6) Yükleme hataları ─────────────────────────────────────────────────────────────────────────────────────────
    test('yükleme hatası: genel bakış 500 → hata bloğu → yeniden dene', async ({ page }) => {
      const out = outage('GET', '/overview')
      const st = await openRoutines(page, '', { waitTeam: false, mock: { hooks: [out.hook] } })
      const failed = page.locator(`${PAGE} [data-slot="or-load-failed"]`)
      await expect(failed).toBeVisible({ timeout: 30_000 })
      await expect(failed).toContainText(tx('or.page.loadFailedTitle'))
      await expect(page.locator('[data-slot="or-day"]')).toHaveCount(0)
      await expect(page.locator(`${PAGE}[data-team]`)).toHaveCount(0)
      await checkLayout(page, `overview failed ${vp.name}`, PAGE)

      // Sunucu düzeldi → "Yeniden dene" sayfayı kurar (kendiliğinden değil: düğme yeni istek atar)
      out.down = false
      const before = reqs(st, 'GET', '/overview').length
      await failed.locator('[data-slot="or-load-retry"]').click()
      await expect(page.locator(`${PAGE}[data-team="${OR_TEAM_ID}"]`)).toBeVisible()
      await expect(page.locator(`[data-slot="or-day"][data-date="${OR_TODAY}"]`)).toBeVisible()
      await expect(page.locator('[data-slot="or-load-failed"]')).toHaveCount(0)
      expect(reqs(st, 'GET', '/overview').length).toBeGreaterThan(before)
      await finish(page, st, `or-rev-overview-failed-${vp.name}`)
    })

    test('yükleme hatası: gün sayfası 500 → hata bloğu → yeniden dene', async ({ page }) => {
      const out = outage('GET', team('/day'))
      const st = await openRoutines(page, '', { mock: { hooks: [out.hook] } })
      const failed = page.locator(`${PAGE} [data-slot="or-load-failed"]`)
      await expect(failed).toBeVisible()
      await expect(failed).toContainText(tx('or.day.loadFailedTitle'))
      await expect(page.locator('[data-slot="or-day"]')).toHaveCount(0)
      await checkLayout(page, `day failed ${vp.name}`, PAGE)

      out.down = false
      const before = reqs(st, 'GET', team('/day')).length
      await failed.locator('[data-slot="or-load-retry"]').click()
      await expect(page.locator(`[data-slot="or-day"][data-date="${OR_TODAY}"]`)).toBeVisible()
      await expect(page.locator('[data-slot="or-load-failed"]')).toHaveCount(0)
      await expect(row(page, dayKey('t-batch'))).toHaveAttribute('data-state', 'DONE')
      expect(reqs(st, 'GET', team('/day')).length).toBeGreaterThan(before)
      await finish(page, st, `or-rev-day-failed-${vp.name}`)
    })

    // ── 7) Nöbetçi yok / pasif nöbetçi ──────────────────────────────────────────────────────────────────────────────
    test('nöbetçi yok: "nöbetçi yok" gösterilir, pasif uyarısı YOK', async ({ page }) => {
      const st = await openRoutines(page, '', {
        mock: {
          transform: {
            day: (d) => ({
              ...d,
              responsible: {
                user_id: null, name: null, username: null, active: false, is_me: false, source: 'NONE',
                schedule_configured: false, schedule_enabled: false, assignment: null,
              },
            }),
          },
        },
      })
      const card = page.locator('[data-slot="or-responsible"]')
      await expect(card).toHaveAttribute('data-source', 'NONE')
      await expect(card.locator('[data-slot="or-responsible-none"]')).toHaveText(tx('or.resp.none'))
      await expect(card.locator('[data-slot="or-responsible-source"]')).toHaveText(tx('or.resp.source.NONE'))
      // Kaynak NONE iken sunucu active=false gönderir — "pasif hesap" uyarısı çıkmamalı (#4)
      await expect(card.locator('[data-slot="or-responsible-passive"]')).toHaveCount(0)
      await expect(card.locator('[data-slot="or-responsible-assigned-by"]')).toHaveCount(0)
      await expect(card.locator('[data-slot="or-responsible-take-week"]')).toBeVisible()
      await checkLayout(page, `no on-call ${vp.name}`, PAGE)
      expect(writes(st)).toEqual([])
      await finish(page, st, `or-rev-no-oncall-${vp.name}`)
    })

    test('pasif nöbetçi: uyarı, sunucu biçimli devir (atayan), devri kaldır sunulmaz', async ({ page }) => {
      const NOTE = 'Mehmet yıllık izinde; gün devri takım lideri tarafından yapıldı, hesap henüz etkinleştirilmedi.'
      const st = await openRoutines(page, '', {
        mock: {
          transform: {
            // Yönetici değil: başkası adına devir kaldıramaz; ben nöbetçi değilim → "Devri kaldır" sunulmamalı (#5)
            overview: patchTeam({ can_manage: false }),
            day: (d) => ({
              ...d,
              responsible: {
                user_id: OR_MEHMET.user_id, name: OR_MEHMET.name, username: OR_MEHMET.username, active: false, is_me: false,
                source: 'TAKEOVER_DAY', schedule_configured: true, schedule_enabled: true,
                assignment: { scope: 'DAY', key: OR_TODAY, note: NOTE, created_by_name: OR_SELIN.name, created_at: '2026-11-25T15:10:00Z' },
              },
            }),
          },
        },
      })
      const card = page.locator('[data-slot="or-responsible"]')
      await expect(card).toHaveAttribute('data-source', 'TAKEOVER_DAY')
      await expect(card).toContainText(OR_MEHMET.name)
      await expect(card.locator('[data-slot="or-responsible-none"]')).toHaveCount(0)
      await expect(card.locator('[data-slot="or-responsible-source"]')).toHaveText(tx('or.resp.source.TAKEOVER_DAY'))
      await expect(card.locator('[data-slot="or-responsible-passive"]')).toHaveText(tx('or.resp.passive'))
      await expect(card.locator('[data-slot="or-responsible-assigned-by"]')).toHaveText(tx('or.resp.assignedBy', OR_SELIN.name))
      await expect(card).toContainText(NOTE)
      await checkLayout(page, `passive on-call ${vp.name}`, PAGE)

      await card.locator('[data-slot="or-responsible-takeover"] button').first().click()
      await expect(page.getByRole('menuitem', { name: tx('or.resp.takeDay') })).toBeVisible()
      await expect(page.getByRole('menuitem', { name: tx('or.resp.takeWeek') })).toBeVisible()
      await expect(page.getByRole('menuitem', { name: tx('or.resp.clear') })).toHaveCount(0)
      await expect(page.getByRole('menuitem', { name: tx('or.resp.openSchedule') })).toHaveCount(0)
      await checkLayout(page, `passive on-call menu ${vp.name}`, '[role="menu"]')
      await page.keyboard.press('Escape')
      await expect(page.getByRole('menu')).toHaveCount(0)
      expect(writes(st)).toEqual([])
      await finish(page, st, `or-rev-passive-oncall-${vp.name}`)
    })

    // ── 8) Kilitli günler / eski hafta ──────────────────────────────────────────────────────────────────────────────
    test('eski gün (TOO_OLD): kilit nedeni, sorun eklenmez, gün notu salt okunur, canlı sayaç istenmez', async ({ page }) => {
      const st = await openRoutines(page, `&or_day=${OLD_DAY}`)
      const day = page.locator(`[data-slot="or-day"][data-date="${OLD_DAY}"]`)
      await expect(day).toHaveAttribute('data-status', 'MISSED')
      const lock = page.locator('[data-slot="or-locked"]')
      await expect(lock).toHaveAttribute('data-reason', 'TOO_OLD')
      await expect(lock).toHaveText(tx('or.lock.TOO_OLD'))
      await expect(page.locator('[data-slot="or-problem-add-general"]')).toHaveCount(0)
      await expect(page.locator('[data-slot="or-day-note"] textarea')).toHaveAttribute('readonly', '')
      // Devir notu yalnız GEÇERLİ ya da ÖNCEKİ döneme yazılır; 12 Kasım iki dönem geride → salt okunur
      await expect(page.locator('[data-slot="or-handover-note"] textarea')).toHaveAttribute('readonly', '')
      const alarm = row(page, dayKey('t-alarm', OLD_DAY))
      await expect(alarm).toHaveAttribute('data-state', 'MISSED')
      await expect(alarm.locator('[data-slot="or-task-check"]')).toBeDisabled()
      await expect(alarm.locator('[data-slot="or-task-problem"]')).toBeDisabled()
      await expect(page.locator('[data-slot="or-bottom-bar"]')).toHaveCount(0)
      // Canlı sayaçlar ŞİMDİYİ söyler: geçmiş günde çizilmez, istenmez (#18)
      await expect(page.locator('[data-signal]')).toHaveCount(0)
      expect(reqs(st, 'GET', team('/signals'))).toHaveLength(0)
      await checkLayout(page, `too old day ${vp.name}`, PAGE)
      expect(writes(st)).toEqual([])
      await finish(page, st, `or-rev-too-old-${vp.name}`)
    })

    test('gelecek gün (FUTURE): kilit nedeni, sorun eklenmez, gün notu salt okunur', async ({ page }) => {
      const st = await openRoutines(page, `&or_day=${FUTURE_DAY}`)
      await expect(page.locator(`[data-slot="or-day"][data-date="${FUTURE_DAY}"]`)).toBeVisible()
      const lock = page.locator('[data-slot="or-locked"]')
      await expect(lock).toHaveAttribute('data-reason', 'FUTURE')
      await expect(lock).toHaveText(tx('or.lock.FUTURE'))
      await expect(page.locator('[data-slot="or-problem-add-general"]')).toHaveCount(0)
      await expect(page.locator('[data-slot="or-day-note"] textarea')).toHaveAttribute('readonly', '')
      // Yarın bu haftanın (GEÇERLİ dönemin) günü: devir notu gün kilidine bağlı değil, yazılabilir kalır
      await expect(page.locator('[data-slot="or-handover-note"] textarea')).not.toHaveAttribute('readonly', '')
      const alarm = row(page, dayKey('t-alarm', FUTURE_DAY))
      await expect(alarm.locator('[data-slot="or-task-check"]')).toBeDisabled()
      await expect(alarm.locator('[data-slot="or-task-problem"]')).toBeDisabled()
      await expect(page.locator('[data-slot="or-bottom-bar"]')).toHaveCount(0)
      expect(reqs(st, 'GET', team('/signals'))).toHaveLength(0)
      await checkLayout(page, `future day ${vp.name}`, PAGE)
      expect(writes(st)).toEqual([])
      await finish(page, st, `or-rev-future-${vp.name}`)
    })

    test('eski hafta: iş günlerinin hepsi pencere dışında → hafta kilit bandı, satırlar ve devir notu salt okunur', async ({ page }) => {
      const st = await openRoutines(page, `&or_view=week&or_week=${OLD_WEEK}`)
      const week = page.locator(`[data-slot="or-week"][data-week="${OLD_WEEK}"]`)
      await expect(week).toBeVisible()
      const locked = week.locator('[data-slot="or-week-locked"]')
      await expect(locked).toHaveAttribute('data-reason', 'TOO_OLD')
      await expect(locked).toHaveText(tx('or.lock.TOO_OLD'))
      const access = row(page, `WEEK:${OLD_WEEK}:t-erisim`)
      await expect(access).toHaveAttribute('data-state', 'DONE')
      await expect(access.locator('[data-slot="or-task-check"]')).toBeDisabled()
      await expect(access.locator('[data-slot="or-task-problem"]')).toBeDisabled()
      await expect(week.locator('[data-slot="or-handover-note"] textarea')).toHaveAttribute('readonly', '')
      // Hafta yanıtında bugünün satırı yok; pencere kuralı yanıt zarfının anına (timestamp) dayanır
      expect(reqs(st, 'GET', team('/week')).some((r) => r.query.week === OLD_WEEK)).toBe(true)
      await checkLayout(page, `old week ${vp.name}`, PAGE)
      expect(writes(st)).toEqual([])
      await finish(page, st, `or-rev-old-week-${vp.name}`)
    })

    // ── 9) Klavye + odak dönüşü ─────────────────────────────────────────────────────────────────────────────────────
    test('klavye: j j x k s Esc → odak aynı satırda; n Esc → odak aynı satırda; j kaldığı yerden', async ({ page }) => {
      test.setTimeout(120_000)
      const st = await openRoutines(page)
      await expect(page.locator(`[data-slot="or-day"][data-date="${OR_TODAY}"]`)).toBeVisible()
      const alarm = row(page, dayKey('t-alarm'))
      const batch = row(page, dayKey('t-batch'))
      const alarmCheck = alarm.locator('[data-slot="or-task-check"]')
      const batchCheck = batch.locator('[data-slot="or-task-check"]')

      await blurAll(page)
      await page.keyboard.press('j')
      await expect(alarmCheck).toBeFocused()
      await page.keyboard.press('j')
      await expect(batchCheck).toBeFocused()
      await page.keyboard.press('x')
      await expect(batch).toHaveAttribute('data-state', 'PENDING')
      await expect.poll(() => reqs(st, 'POST', team('/items/state')).length).toBe(1)
      expect(reqs(st, 'POST', team('/items/state'))[0].body).toMatchObject({ task_id: 't-batch', state: 'PENDING', expected_state: 'DONE' })
      await page.keyboard.press('k')
      await expect(alarmCheck).toBeFocused()

      // s → atlama kutusu (gerekçe alanına odak, "s" harfi yazılmaz); Esc → odak aynı satırın onay kutusuna
      await page.keyboard.press('s')
      const skipReason = alarm.locator('[data-slot="or-skip-reason"]')
      await expect(skipReason).toBeFocused()
      await expect(skipReason).toHaveValue('')
      await checkLayout(page, `keyboard skip panel ${vp.name}`, PAGE)
      await page.keyboard.press('Escape')
      await expect(alarm.locator('[data-slot="or-skip"]')).toHaveCount(0)
      await expect(alarmCheck).toBeFocused()
      await expect.poll(() => focusedOf(page)).toBe(`or-task-check|${dayKey('t-alarm')}`)

      // n → not kutusu; Esc → kayıt yok, odak yine aynı satırda
      await page.keyboard.press('n')
      const noteArea = alarm.locator('[data-slot="or-task-note"] textarea')
      await expect(noteArea).toBeFocused()
      await page.keyboard.press('Escape')
      await expect(alarm.locator('[data-slot="or-task-note"]')).toHaveCount(0)
      await expect(alarmCheck).toBeFocused()
      await expect.poll(() => focusedOf(page)).toBe(`or-task-check|${dayKey('t-alarm')}`)
      expect(reqs(st, 'PUT', team('/items/note'))).toHaveLength(0)

      // j kaldığı yerden: alarmın ardından batch
      await page.keyboard.press('j')
      await expect(batchCheck).toBeFocused()
      await expect.poll(() => focusedOf(page)).toBe(`or-task-check|${dayKey('t-batch')}`)
      expect(reqs(st, 'POST', team('/items/state'))).toHaveLength(1)
      await checkLayout(page, `keyboard end ${vp.name}`, PAGE)
      await finish(page, st, `or-rev-keyboard-${vp.name}`)
    })

    // ── 10) İstatistik ──────────────────────────────────────────────────────────────────────────────────────────────
    test('istatistik: okuma hatası → yeniden dene; göstergeler, çubuklar ve simgeler kırpılmadan', async ({ page }) => {
      const out = outage('GET', team('/stats'))
      const st = await openRoutines(page, '&or_view=stats', { mock: { hooks: [out.hook] } })
      const stats = page.locator('[data-slot="or-stats"]')
      await expect(stats).toHaveAttribute('data-state', 'error', { timeout: 20_000 })
      await expect(stats.locator('[data-slot="or-stats-failed"]')).toBeVisible()
      await expect(stats.locator('[data-slot="or-stats-kpis"]')).toHaveCount(0)
      await checkLayout(page, `stats failed ${vp.name}`, PAGE)

      out.down = false
      const before = reqs(st, 'GET', team('/stats')).length
      await stats.locator('[data-slot="or-stats-retry"]').click()
      await expect(stats).toHaveAttribute('data-state', 'ok', { timeout: 20_000 })
      expect(reqs(st, 'GET', team('/stats')).length).toBeGreaterThan(before)
      const kpis = stats.locator('[data-slot="or-stats-kpis"]')
      await expect(kpis).toBeVisible()
      expect(await kpis.locator('svg').count()).toBeGreaterThan(0)
      const tasks = stats.locator('[data-slot="or-stats-tasks"]')
      const layout = await tasks.getAttribute('data-layout')
      if (vp.name === 'phone') expect(layout).toBe('cards')
      await expect(tasks.locator('[data-slot="or-stats-task"]')).toHaveCount(8)
      // Görev başına tamamlanma çubuğu çizilir (göstergenin genişliği 0'dan büyük)
      const bars = tasks.locator('[data-slot="progress-indicator"]')
      expect(await bars.count()).toBeGreaterThan(0)
      const firstBar = await bars.first().evaluate((el) => {
        const track = el.parentElement.getBoundingClientRect()
        const ind = el.getBoundingClientRect()
        return { track: track.width, visible: Math.min(ind.right, track.right) - Math.max(ind.left, track.left) }
      })
      expect(firstBar.track).toBeGreaterThan(20)
      expect(firstBar.visible).toBeGreaterThan(0)
      await expect(stats.locator('[data-slot="or-stats-people"]')).toContainText(OR_AYSE.name)
      await expect(stats.locator('[data-slot="or-stats-problems"]')).toBeVisible()
      await checkLayout(page, `stats ${vp.name}`, PAGE)
      await finish(page, st, `or-rev-stats-${vp.name}`)
    })

    // ── 11) Alt çubuk (#15) ─────────────────────────────────────────────────────────────────────────────────────────
    test('alt çubuk: kısmen tamamlanmış gün amber "Kısmen tamamlandı", tam gün yeşil, güvenli alan dolgusu', async ({ page }) => {
      const st = await openRoutines(page, `&or_day=${PARTIAL_DAY}`)
      await expect(page.locator(`[data-slot="or-day"][data-date="${PARTIAL_DAY}"]`)).toHaveAttribute('data-status', 'COMPLETED_PARTIAL')
      const bar = page.locator('[data-slot="or-bottom-bar"]')
      await expect(bar).toHaveAttribute('data-state', 'partial')
      const state = bar.locator('[data-slot="or-bar-state"]')
      await expect(state).toHaveAttribute('data-state', 'partial')
      await expect(state).toHaveText(tx('or.status.COMPLETED_PARTIAL'))
      await expect(state).toHaveClass(/text-amber-700/)
      await expect(state).not.toHaveClass(/text-success/)
      await expect(bar).toContainText(tx('or.bar.completed', OR_MEHMET.name, '18:52'))
      // Güvenli alan: alt dolgu env(safe-area-inset-bottom) ile en az 0.625rem
      await expect(bar).toHaveClass(/pb-\[max\(0\.625rem,env\(safe-area-inset-bottom\)\)\]/)
      expect(await bar.evaluate((el) => parseFloat(getComputedStyle(el).paddingBottom))).toBeGreaterThanOrEqual(9.5)
      // İlerleme metni canlı bölge DEĞİL; durum ayrı sr-only bölgede
      expect(await bar.locator('p').first().getAttribute('aria-live')).toBeNull()
      await expect(bar.locator('[data-slot="or-bar-live"]')).toHaveAttribute('aria-live', 'polite')
      await expect(bar.locator('[data-slot="or-bar-live"]')).toHaveText(tx('or.status.COMPLETED_PARTIAL'))
      await expect(bar.locator('[data-slot="or-reopen"]')).toBeVisible()
      await scrollToEnd(page)
      await expectInViewport(bar, vp, 'partial day bottom bar')
      await checkLayout(page, `partial day bar ${vp.name}`, PAGE)
      const amber = await state.evaluate((el) => getComputedStyle(el).color)

      // Tam gün: yeşil "Tamam", amber değil
      await page.goto(`/?tab=oncallroutines&or_day=${DONE_DAY}`)
      await expect(page.locator(`[data-slot="or-day"][data-date="${DONE_DAY}"]`)).toHaveAttribute('data-status', 'COMPLETED')
      const doneState = page.locator('[data-slot="or-bar-state"]')
      await expect(doneState).toHaveAttribute('data-state', 'done')
      await expect(doneState).toHaveText(tx('or.bar.done'))
      await expect(doneState).toHaveClass(/text-success/)
      expect(await doneState.evaluate((el) => getComputedStyle(el).color)).not.toBe(amber)
      await scrollToEnd(page)
      await expectInViewport(page.locator('[data-slot="or-bottom-bar"]'), vp, 'done day bottom bar')
      expect(writes(st)).toEqual([])
      await finish(page, st, `or-rev-bar-${vp.name}`)
    })

    // ── 12) Yoklama: bayat yanıt yerleşen işareti geri almaz (#2) ───────────────────────────────────────────────────
    test('yoklama: yazım sürerken çıkan bayat yanıt işaretlenen satırı geri almaz', async ({ page }) => {
      test.setTimeout(120_000)
      const st = await openRoutines(page)
      await expect(page.locator(`[data-slot="or-day"][data-date="${OR_TODAY}"]`)).toBeVisible()
      // Bayat yoklama yanıtı: t-alarm henüz bekliyor (sürüm 1); ama Selin t-disk'i işaretlemiş (yoklamanın uygulandığı kanıt)
      const stale = orDaySheet(st, OR_TODAY)
      expect(itemIn(stale, dayKey('t-alarm'))).toMatchObject({ state: 'PENDING', version: 1 })
      Object.assign(itemIn(stale, dayKey('t-disk')), {
        state: 'DONE', state_by_name: OR_SELIN.name, state_by_me: false, state_at: '2026-11-26T08:38:00Z', version: 2,
      })

      const postGate = deferred()
      const pollGate = deferred()
      let postHeld = true
      let pollArmed = false
      st.hooks.push(async ({ method, path, ok }) => {
        if (method === 'POST' && path === team('/items/state') && postHeld) { await postGate.promise; return false }
        if (method === 'GET' && path === team('/day') && pollArmed) {
          pollArmed = false
          await pollGate.promise
          try { await ok(stale) } catch { /* istek sayfada iptal edildiyse yanıt düşer */ }
          return true
        }
        return false
      })

      const alarm = row(page, dayKey('t-alarm'))
      await alarm.locator('[data-slot="or-task-check"]').click()
      await expect(alarm).toHaveAttribute('data-state', 'DONE')              // iyimser
      await expect.poll(() => reqs(st, 'POST', team('/items/state')).length).toBe(1)

      // Yazım sürerken yoklama çıkar (gizlenmeden gelen "görünür" olayı yoklamayı hemen tetikler — useVisibleInterval)
      const dayReads = reqs(st, 'GET', team('/day')).length
      pollArmed = true
      await page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')))
      await expect.poll(() => reqs(st, 'GET', team('/day')).length, { timeout: 10_000 }).toBeGreaterThan(dayReads)

      // Önce yazım biter (yoklama ÇIKTIKTAN sonra yerleşti) …
      const postDone = page.waitForResponse((r) => new URL(r.url()).pathname === `${API}${team('/items/state')}`)
      postHeld = false
      postGate.resolve()
      await postDone
      await expect(alarm).toHaveAttribute('data-state', 'DONE')
      // … sonra bayat yoklama yanıtı gelir
      const pollDone = page.waitForResponse((r) => new URL(r.url()).pathname === `${API}${team('/day')}`)
      pollGate.resolve()
      await pollDone
      const disk = row(page, dayKey('t-disk'))
      await expect(disk).toHaveAttribute('data-state', 'DONE')               // yoklama uygulandı
      await expect(disk.locator('[data-slot="or-task-meta"]')).toContainText(OR_SELIN.name)
      await expect(alarm).toHaveAttribute('data-state', 'DONE')              // yerleşen işaret geri alınmadı
      await expect(alarm.locator('[data-slot="or-task-check"]')).toHaveAttribute('data-state', 'checked')
      await expect(page.locator('[data-slot="or-bottom-bar"]')).toContainText(tx('or.bar.progress', 3, 5))
      // Biraz sonra da öyle (gecikmeli bir yeniden çizim geri almasın)
      await page.waitForTimeout(400)
      await expect(alarm).toHaveAttribute('data-state', 'DONE')
      expect(reqs(st, 'POST', team('/items/state'))).toHaveLength(1)
      await checkLayout(page, `poll merge ${vp.name}`, PAGE)
      await finish(page, st, `or-rev-poll-${vp.name}`)
    })

    // ── 13) Sunucu inceleme turunun sözleşmesi (sapma dosyası "E2E additions") ─────────────────────────────────────────
    test('sözleşme: yok sayılan devir notu, devir özetinde kapanmamış gün, SiteMonitor\'un otomatik tamamladığı gün', async ({ page }) => {
      const st = await openRoutines(page, '', {
        mock: {
          transform: {
            day: (d, { date }) => ({
              ...d,
              responsible: date === OR_TODAY
                ? { ...d.responsible, ignored_assignment: { scope: 'DAY', key: OR_TODAY, name: OR_MEHMET.name, reason: 'not_member' } }
                : d.responsible,
              handover: d.handover?.show
                ? { ...d.handover, summary: { ...(d.handover.summary || {}), open_days: 1 } }
                : d.handover,
              day: date === DONE_DAY
                ? { ...d.day, completed_by_name: 'SiteMonitor', completed_at: `${DONE_DAY}T21:30:00Z` }
                : d.day,
            }),
          },
        },
      })
      const ignored = page.locator('[data-slot="or-responsible"] [data-slot="or-responsible-ignored"]')
      await expect(ignored).toHaveText(tx('or.resp.ignoredAssignment', OR_MEHMET.name))
      await expect(ignored).toHaveAttribute('data-scope', 'DAY')
      // Kapanmamış gün nötr rozet; kaçırılmış sayılmaz
      const openDays = page.locator('[data-slot="or-handover"] [data-stat="or-handover-open-days"]')
      await expect(openDays).toHaveText(tx('or.hand.openDays.one', 1))
      await checkLayout(page, `contract today ${vp.name}`, PAGE)

      // Sistemin otomatik tamamladığı gün: "SiteMonitor" düz metin (kişi bağlantısı yok)
      await page.goto(`/?tab=oncallroutines&or_day=${DONE_DAY}`)
      const done = page.locator(`[data-slot="or-day"][data-date="${DONE_DAY}"]`)
      await expect(done).toHaveAttribute('data-status', 'COMPLETED')
      const completed = page.locator('[data-slot="or-progress-completed"]')
      await expect(completed).toContainText('SiteMonitor')
      await expect(completed.locator('a')).toHaveCount(0)
      await checkLayout(page, `contract auto-completed ${vp.name}`, PAGE)
      expect(writes(st)).toEqual([])
      await finish(page, st, `or-rev-contract-${vp.name}`)
    })

    test('sözleşme: atama notu sunucu alan hatası (400 note) notun ALTINDA, pencere açık', async ({ page }) => {
      const MSG = 'Not en çok 200 karakter olabilir. Notu kısaltıp yeniden deneyin.'
      const st = await openRoutines(page, '', {
        mock: {
          hooks: [async ({ method, path, fail }) => {
            if (method !== 'POST' || path !== team('/assignments')) return false
            await fail(400, 'VALIDATION_FAILED', MSG, { field: 'note', fields: { note: MSG } })
            return true
          }],
        },
      })
      const card = page.locator('[data-slot="or-responsible"]')
      await card.locator('[data-slot="or-responsible-takeover"] button').first().click()
      await page.getByRole('menuitem', { name: tx('or.resp.assignOther'), exact: true }).click()
      const dlg = page.locator('[data-slot="or-assign-dialog"]')
      await expect(dlg).toBeVisible()
      await dlg.getByRole('combobox').click()
      await page.getByRole('option', { name: OR_MEHMET.name, exact: true }).click()
      const note = dlg.locator('[data-slot="or-assign-note"]')
      await expect(note).toHaveAttribute('maxlength', '200')
      await note.fill('Yıllık izin nedeniyle bu günü Mehmet üstleniyor.')
      await page.locator('[data-slot="or-assign-confirm"]').click()
      const err = dlg.locator('[data-slot="or-assign-note-error"]')
      await expect(err).toHaveText(MSG)
      await expect(note).toHaveAttribute('aria-invalid', 'true')
      await expect(dlg).toBeVisible()
      expect(reqs(st, 'POST', team('/assignments'))).toHaveLength(1)
      await checkLayout(page, `assign note error ${vp.name}`, '[role="dialog"]')
      await finish(page, st, `or-rev-assign-note-${vp.name}`)
    })

    test('sözleşme: gün notuna sunucu NOT_WORKDAY (409) → notun altında sunucu metni + yeniden dene', async ({ page }) => {
      const MSG = 'Bu gün takımın iş günü değil; günün notu yazılamaz.'
      const st = await openRoutines(page, '', {
        mock: {
          hooks: [async ({ method, path, fail }) => {
            if (method !== 'PUT' || path !== team(`/days/${OR_TODAY}/note`)) return false
            await fail(409, 'NOT_WORKDAY', MSG)
            return true
          }],
        },
      })
      const area = page.locator('[data-slot="or-day-note"] textarea')
      await area.fill('Gece bakım penceresi uzadı; sabah ilk iş kontrol edildi.')
      await area.press('Tab')
      const status = page.locator('[data-slot="or-day-note-status"]')
      await expect(status).toHaveAttribute('data-status', 'failed')
      await expect(status).toContainText(MSG)
      await expect(page.locator('[data-slot="or-day-note-retry"]')).toBeVisible()
      await expect(area).toHaveValue('Gece bakım penceresi uzadı; sabah ilk iş kontrol edildi.')
      await checkLayout(page, `day note not workday ${vp.name}`, PAGE)
      await finish(page, st, `or-rev-day-note-409-${vp.name}`)
    })

    test('sözleşme: şablon 512 KB reddi (400 template) düzenleyicide; sonraki kayıtta "son kaydeden" yeniden yüklemeden ben', async ({ page }) => {
      const MSG = 'Şablon çok büyük: en çok 512 KB olabilir. Bölüm ya da görev sayısını azaltın.'
      let refuse = true
      const st = await openRoutines(page, '&or_view=template', {
        mock: {
          hooks: [async ({ method, path, fail }) => {
            if (!refuse || method !== 'PUT' || path !== team('/template')) return false
            refuse = false
            await fail(400, 'VALIDATION_FAILED', MSG, { field: 'template', fields: { template: MSG } })
            return true
          }],
        },
      })
      const editor = page.locator('[data-slot="or-tpl-editor"][data-editable="true"]')
      await expect(editor).toBeVisible({ timeout: 30_000 })
      const meta = editor.locator('[data-slot="or-tpl-meta"]')
      await expect(meta).toContainText(OR_SELIN.name)
      const title = editor.locator('[data-field="template.s-gun-basi.t-alarm.title"] input')
      await title.fill(`${OR_TITLES.alarm} (güncel)`)
      await editor.locator('[data-slot="or-tpl-save"]').click()
      const err = editor.locator('[data-slot="or-tpl-error"][data-field="template"]')
      await expect(err).toContainText(MSG)
      await expect(page.getByText(/413|PAYLOAD_TOO_LARGE/)).toHaveCount(0)
      await expect(title).toHaveValue(`${OR_TITLES.alarm} (güncel)`)
      await checkLayout(page, `template too large ${vp.name}`, PAGE)

      await editor.locator('[data-slot="or-tpl-save"]').click()
      await expect.poll(() => reqs(st, 'PUT', team('/template')).length).toBe(2)
      await expect(page.getByText(tx('or.tpl.saved', 15))).toBeVisible()
      await expect(meta).toContainText(OR_ME.name)
      await expect(meta).not.toContainText(OR_SELIN.name)
      await expect(err).toHaveCount(0)
      await finish(page, st, `or-rev-template-too-large-${vp.name}`)
    })

    test('sözleşme: canlı sayaç kısmi 0 → gri (asla yeşil), kısmi > 0 → turuncu; metin "≥ n"', async ({ page }) => {
      const st = await openRoutines(page, '', {
        mock: {
          transform: {
            signals: (s) => ({
              ...s,
              signals: {
                ...s.signals,
                open_alerts: { ...s.signals.open_alerts, value: 0, partial: true, tone: 'neutral' },
                down_monitors: { ...s.signals.down_monitors, value: 2, partial: true, tone: 'warn' },
              },
            }),
          },
        },
      })
      const alarm = row(page, dayKey('t-alarm'))
      const zero = alarm.locator('[data-slot="or-task-signal"][data-signal="open_alerts"]')
      await expect(zero).toHaveAttribute('data-tone', 'neutral')
      await expect(zero).toContainText('≥ 0')
      const some = alarm.locator('[data-slot="or-task-signal"][data-signal="down_monitors"]')
      await expect(some).toHaveAttribute('data-tone', 'warn')
      await expect(some).toContainText('≥ 2')
      await checkLayout(page, `partial signals ${vp.name}`, PAGE)
      expect(writes(st)).toEqual([])
      await finish(page, st, `or-rev-partial-signals-${vp.name}`)
    })
  })
}
