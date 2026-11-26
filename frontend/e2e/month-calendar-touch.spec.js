// Paylaşılan ay takvimi (`ui/MonthCalendar`) — dokunmatik telefon düzeltmesi (2026-11-26 inceleme #13) gerçek tarayıcıda.
//
// Kural: DAR takvimde (kart < 42rem = 672 px — `@container/mcal`; 390 telefon ve 768 tablette kart 342 / 416 px) çip
// çizilmez; olaylı her gün tek bir 40 px'lik hedef taşır (`month-calendar-more-touch`: ton noktaları + adet, adı
// "27 November — 3 events"), balon günün TÜM olaylarını tam adıyla listeler. (Tek çip bile 60+ karakterlik adla dar sütunda
// günü yüzlerce piksel uzatıyordu: 390 dokunmatikte kart 1463 / 1860 px, 768 dokunmatikte üç çiple 2315 / 2853 px ölçüldü.)
// Geniş takvimde (1280 dizüstü, kart ≈ 913 px) günde en çok 3 çip + "+N".
// `dense` çağıran (Sertifika bitiş takvimi / ExpiryForecast) telefonda çip yerine ton noktası + adet çizer.
//
// Üç çağıran, gerçekçi veriyle (bir günde 3, bir günde 4 olay; 60+ karakterlik alan adları / bakım penceresi adları; tire
// içermeyen uzun alan adları da var — kırılma noktası olmayan ad):
//   1) Sertifika Yenileme Önerileri — Takvim görünümü (`?tab=renewal`),
//   2) Bakım Pencereleri — Takvim sekmesi (`?tab=maintenance&view=calendar`, haftalık tekrarlayan pencere dâhil),
//   3) Sertifika Bitiş Takvimi (`?tab=forecast`, `dense`, gün paneli).
// 390×844 dokunmatik, 768×1024 dokunmatik ve 1280 (ince işaretçi, temel çizgi) genişliklerinde:
//   - ÖLÇÜLÜR ve eklenir (`<sayfa>-<genişlik>-calendar-metrics.json` + açıklama): takvim kartı yüksekliği, en uzun gün hücresi,
//     hafta satırı yükseklikleri, gün başına çip / görünür çip / "+N" tetiği / gün düğmesi boyutları, etiket taşmaları, "+N"
//     balonunun genişliği;
//   - sayfa yatay kaymaz, metin kırpılmaz, dokunmatikte çip / "+N" / gün düğmesi ≥ 40 px (layoutAudit + açık ölçü);
//   - olay etiketi kendi kutusundan taşmaz (`scrollWidth <= clientWidth`);
//   - dar takvim (dense değil): görünür çip yok, olaylı günde tek görünür hedef (adı günün olay sayısını söyler); balon
//     günün TÜM adlarını taşır; balon metni kendi kutusundan / ekrandan taşmaz;
//   - her genişlikte takvim kartı ≤ 1400 px (ölçü mesajda — düşerse sayı rapora yazılır);
//   - dense dar takvim: çip yok, gün düğmesinde nokta (≤ 4) + adet, adı "27 November — 3 events, open the day"; gün paneli o günün
//     sertifikalarını listeler.
import { test, expect } from '@playwright/test'
import { EN } from '../src/i18n/en.js'
import { mockApi } from './support/monitorMocks.js'
import { VIEWPORTS, checkLayout, expectInViewport, json, pathIs, settle } from './support/layoutAudit.js'

const NOW = '2026-11-26T09:00:00Z'   // Perşembe, İstanbul 12:00
const TODAY = '2026-11-26'
const MONTH = '2026-11'
const BUSY3 = '2026-11-27'           // Cuma — 3 olay
const BUSY4 = '2026-11-30'           // Pazartesi — 4 olay
const CAL = '[data-slot="month-calendar"]'
const CARD_MAX = 1400
const NARROW_MAX = 672   // `@2xl` = 42rem — altında dar takvim
const DAY_MS = 86_400_000

/** EN sözlükten metin; `{0}` `{1}` … yer tutucuları sırayla doldurulur. Anahtar yoksa test hemen düşer. */
function tx(key, ...args) {
  const s = EN[key]
  if (typeof s !== 'string') throw new Error(`EN key missing: ${key}`)
  return args.reduce((acc, a, i) => acc.split(`{${i}}`).join(String(a)), s)
}

/** Bölgesiz UTC damgası (sunucu biçimi); saatler 05–16 UTC → İstanbul'da aynı gün. */
const at = (day, hh) => `${day}T${String(hh).padStart(2, '0')}:00:00`
const daysTo = (day) => Math.round((Date.parse(`${day}T00:00:00Z`) - Date.parse(`${TODAY}T00:00:00Z`)) / DAY_MS)
/** en-GB gün etiketi (takvimin `cal.dayOpen` adı): "27 November". Bütün olaylar Kasım'da. */
const dayLabel = (day) => `${Number(day.slice(8))} November`

/** [gün, ad] çiftlerinden gün → adlar. */
function byDay(pairs) {
  const m = {}
  for (const [day, label] of pairs) (m[day] ||= []).push(label)
  return m
}

// ── Gerçek uzunlukta adlar (60+ karakter; bir kısmında tire YOK — kırılma noktası olmayan alan adı) ──
const D = {
  kimlik: 'kurumsal-kimlik-dogrulama-servisi.yetkilendirme.ic-servisler.example.com',
  rapor: 'raporlama-ve-analitik-platformu.ic-servisler.kurumsal.example.com',
  odeme: 'odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.com',
  bildirim: 'musteri-bildirim-merkezi-api.dijital-kanallar.ic-servisler.example.com',
  ibank: 'internetbankaciligi.kurumsalportal.icservisler.example.com.tr',
  mobil: 'mobilbankacilikservisleri.dijitalkanallar.icservisler.example.com.tr',
  kampanya: 'kampanya-yonetim-paneli.pazarlama-otomasyonu.ic-servisler.example.com',
  sube: 'internetsubesi.kurumsalbankacilik.icservisler.ornekbanka.example.com.tr',
}

// ── 1) Sertifika Yenileme Önerileri ──
const RENEWAL_EVENTS = [
  ['2026-11-05', D.kimlik, 8], ['2026-11-12', 'www.example.com', 9], ['2026-11-18', D.rapor, 10], ['2026-11-24', 'shop.example.com', 11],
  [BUSY3, D.odeme, 6], [BUSY3, D.bildirim, 9], [BUSY3, 'api.example.com', 13],
  ['2026-11-28', D.ibank, 7],
  [BUSY4, D.mobil, 5], [BUSY4, D.kampanya, 8], [BUSY4, D.sube, 12], [BUSY4, 'status.example.org', 15],
]
const PRI = { EXPIRED: 'critical', EXPIRING_CRITICAL: 'critical', EXPIRING_WARNING: 'warning', EXPIRING_INFO: 'info' }
const RENEWAL_ROWS = RENEWAL_EVENTS.map(([day, domain, hh], i) => {
  const days = daysTo(day)
  const code = days < 0 ? 'EXPIRED' : days <= 7 ? 'EXPIRING_CRITICAL' : days <= 30 ? 'EXPIRING_WARNING' : 'EXPIRING_INFO'
  return {
    domain, code, priority: PRI[code], message: `Sunucu mesajı (${code})`, action: `Sunucu eylemi (${code})`,
    days_remaining: days, not_after: at(day, hh), team_id: 1, team_name: 'Takım A', tier: (i % 4) + 1,
    group_name: 'Kurumsal Web', tags: 'prod', port: 443, issuer_cn: 'Example Issuing CA G2', fingerprint: `FP-${i}-${domain}`,
  }
})

// ── 2) Bakım Pencereleri (haftalık Çarşamba penceresi + tek seferlikler) ──
const WEEKLY_NAME = 'Haftalık ağ ve güvenlik duvarı kural güncellemesi (Tüm Şubeler ve Genel Müdürlük)'
const WEEKLY_DAYS = ['2026-11-04', '2026-11-11', '2026-11-18', '2026-11-25']   // Kasım Çarşambaları (22:00 İstanbul)
const MAINT_ONCE = [
  ['2026-11-05', 'Ödeme altyapısı gece yama penceresi (Kurumsal ve Bireysel Bankacılık Uçları)', 7],
  ['2026-11-12', 'Veritabanı kümesi sürüm yükseltmesi', 8],
  [BUSY3, 'Çağrı merkezi IVR entegrasyon testleri ve yük dengeleyici sertifika yenilemesi', 6],
  [BUSY3, 'Mobil bankacılık API ağ geçidi bakım penceresi (Kurumsal Müşteri Uçları Dahil)', 11],
  [BUSY3, 'DNS sunucusu yazılım güncellemesi', 15],
  [BUSY4, 'Kart ödeme sistemleri yedek veri merkezi geçiş tatbikatı (Planlı Kesinti Bildirildi)', 5],
  [BUSY4, 'Kurumsal e-posta ağ geçidi ve istenmeyen posta süzgeci kural güncellemesi', 9],
  [BUSY4, 'İnternet şubesi oturum yönetimi servis yeniden başlatma', 13],
  [BUSY4, 'Raporlama platformu disk genişletme', 16],
]
const MAINT_WINDOWS = [
  { id: 1, name: WEEKLY_NAME, description: 'Tekrarlayan bakım', all_monitors: true, targets: [], target_count: 0, recurrence: 'WEEKLY',
    days_of_week: '3', start_at: '2026-10-07T19:00:00', timezone: 'Europe/Istanbul', duration_minutes: 120, status: 'upcoming',
    team_id: 1, created_by: 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin', next_occurrence: '2026-12-02T19:00:00' },
  ...MAINT_ONCE.map(([day, name, hh], i) => ({
    id: 10 + i, name, description: null, all_monitors: false,
    targets: [{ type: 'http', target: `https://bakim-${i}.example.com/`, name: `Bakım hedefi ${i}` }], target_count: 1,
    recurrence: 'NONE', start_at: at(day, hh), timezone: 'Europe/Istanbul', duration_minutes: 90,
    status: day < TODAY ? 'completed' : 'upcoming', team_id: 1, created_by: 'demo', next_occurrence: day < TODAY ? null : at(day, hh),
  })),
]

// ── 3) Sertifika Bitiş Takvimi (dense) ──
const FORECAST_EVENTS = [
  ['2026-11-18', D.rapor, 10], [TODAY, 'shop.example.com', 14],
  [BUSY3, D.odeme, 6], [BUSY3, D.bildirim, 9], [BUSY3, 'api.example.com', 13],
  ['2026-11-28', D.ibank, 7],
  [BUSY4, D.mobil, 5], [BUSY4, D.kampanya, 8], [BUSY4, D.sube, 12], [BUSY4, 'status.example.org', 15],
  ['2026-12-15', 'sonraki-ay.example.com', 9],   // Aralık — Kasım ızgarasında yok
]
const FORECAST_DATA = {
  certs: FORECAST_EVENTS.map(([day, domain, hh], i) => {
    const days = daysTo(day)
    return {
      domain, not_after: at(day, hh), days_remaining: days, status: days < 0 ? 'expired' : 'valid', error: null,
      checked_at: '2026-11-26T08:00:00', team_id: 1, team_name: 'Takım A', ug_team_id: null, tier: (i % 4) + 1,
      group_name: 'Kurumsal Web', issuer_cn: 'Example Issuing CA G2', fingerprint: `FP-F${i}`, serial_number: `0${i}A1`,
      lead_days: 14, renew_by: at(new Date(Date.parse(`${day}T00:00:00Z`) - 14 * DAY_MS).toISOString().slice(0, 10), hh),
      renewal_planned_at: null, renewal_planned_by: null, renewal_planned_note: null, renewal_plan_state: 'none',
    }
  }),
  thresholds: { warning: 30, high: 15, critical: 7 }, lead_days: { default: 14 },
  data_as_of: '2026-11-26T08:00:00', generated_at: '2026-11-26T09:00:00',
  renewals: { on_time: 0, late: 0, months: [], events: [] }, domains: [],
}

const inMonth = (m) => Object.fromEntries(Object.entries(m).filter(([d]) => d.startsWith(MONTH)))
const PAGES = [
  {
    key: 'renewal', dense: false,
    labels: inMonth(byDay(RENEWAL_EVENTS.map(([d, l]) => [d, l]))),
    async mock(page) {
      await page.route(pathIs('/api/renewal-advice'),
        json({ success: true, data: RENEWAL_ROWS, count: RENEWAL_ROWS.length, timestamp: NOW }))
    },
    async open(page) {
      await page.goto('/?tab=renewal')
      const view = page.getByRole('group', { name: tx('renewal.viewLabel'), exact: true })
      await expect(view).toBeVisible({ timeout: 60_000 })
      await view.getByRole('button', { name: tx('renewal.viewCalendar'), exact: true }).click()
    },
  },
  {
    key: 'maintenance', dense: false,
    labels: inMonth(byDay([...WEEKLY_DAYS.map((d) => [d, WEEKLY_NAME]), ...MAINT_ONCE.map(([d, l]) => [d, l])])),
    async mock(page) {
      await page.route((u) => new URL(u).pathname.startsWith('/api/monitoring/maintenance'), (route) => {
        const p = new URL(route.request().url()).pathname
        if (route.request().method() !== 'GET') return route.fallback()
        if (p === '/api/monitoring/maintenance') return json({ success: true, data: MAINT_WINDOWS })(route)
        if (p === '/api/monitoring/maintenance/active') return json({ success: true, data: [] })(route)
        return route.fallback()
      })
    },
    async open(page) {
      await page.goto('/?tab=maintenance&view=calendar')
      await expect(page.getByRole('tab', { name: tx('mw.calendar'), exact: true })).toHaveAttribute('aria-selected', 'true', { timeout: 60_000 })
    },
  },
  {
    key: 'forecast', dense: true,
    labels: inMonth(byDay(FORECAST_EVENTS.map(([d, l]) => [d, l]))),
    async mock(page) {
      await page.route(pathIs('/api/forecast'), json({ success: true, data: FORECAST_DATA }))
    },
    async open(page) {
      await page.goto('/?tab=forecast')
      await expect(page.locator('[data-slot="fc-calendar"]')).toBeVisible({ timeout: 60_000 })
    },
  },
]

/**
 * Tarayıcıda takvim ölçüsü. Dokunma hedefi `layoutAudit` kuralıyla (kutu + konumlu `::after` vuruş alanı).
 * @returns {{ coarse, card, tallestDay, weeks, days, labelOverflow }}
 */
function measureCalendar(sel) {
  const card = [...document.querySelectorAll(sel)].pop()
  if (!card) return null
  const shown = (el) => {
    const r = el.getBoundingClientRect()
    if (r.width < 1 || r.height < 1) return false
    const s = getComputedStyle(el)
    return s.visibility !== 'hidden' && s.display !== 'none' && !el.closest('[hidden], [aria-hidden="true"]')
  }
  const round = (n) => Math.round(n * 10) / 10
  const hit = (el) => {
    const r = el.getBoundingClientRect()
    let h = r.height
    let w = r.width
    const af = getComputedStyle(el, '::after')
    if (af.position === 'absolute' && af.content !== 'none') {
      const ah = parseFloat(af.height); const aw = parseFloat(af.width)
      if (ah > 0) h = Math.max(h, ah)
      if (aw > 0) w = Math.max(w, aw)
      h = Math.max(h, r.height - Math.min(0, parseFloat(af.top) || 0) - Math.min(0, parseFloat(af.bottom) || 0))
    }
    return { w: round(w), h: round(h) }
  }
  const days = {}
  const labelOverflow = []
  let tallest = { day: null, height: 0 }
  const tops = new Map()
  for (const cell of card.querySelectorAll('[data-slot="month-calendar-day"]')) {
    const key = cell.getAttribute('data-day')
    const r = cell.getBoundingClientRect()
    if (r.height > tallest.height) tallest = { day: key, height: Math.round(r.height) }
    const top = Math.round(r.top)
    tops.set(top, Math.max(tops.get(top) || 0, Math.round(r.height)))
    const chips = [...cell.querySelectorAll('[data-slot="month-calendar-event"]')]
    const vis = chips.filter(shown)
    const labels = vis.map((c) => {
      const l = c.querySelector('[data-slot="month-calendar-event-label"]')
      const item = { text: (l?.textContent || '').trim(), sw: l?.scrollWidth ?? 0, cw: l?.clientWidth ?? 0, chip: hit(c) }
      if (l && l.scrollWidth > l.clientWidth + 1) labelOverflow.push({ day: key, ...item })
      return item
    })
    const triggers = [...cell.querySelectorAll('[data-slot="hint-trigger"]')].map((b) => ({
      text: b.textContent.trim(), name: (b.querySelector('.sr-only')?.textContent || '').trim(), visible: shown(b),
      moreTouch: !!b.closest('[data-slot="month-calendar-more-touch"]'), ...hit(b),
    }))
    const open = cell.querySelector('[data-slot="month-calendar-day-open"]')
    if (!chips.length && !triggers.length && !open) continue
    days[key] = {
      height: Math.round(r.height), width: Math.round(r.width), chips: chips.length, visibleChips: vis.length, labels, triggers,
      dayOpen: open ? { visible: shown(open), label: open.getAttribute('aria-label'), dots: open.querySelectorAll('span.rounded-full').length, ...hit(open) } : null,
    }
  }
  const cr = card.getBoundingClientRect()
  return {
    viewport: { w: innerWidth, h: innerHeight }, coarse: matchMedia('(pointer: coarse)').matches,
    card: { height: Math.round(cr.height), width: Math.round(cr.width) }, tallestDay: tallest,
    weeks: [...tops.entries()].sort((a, b) => a[0] - b[0]).map(([top, height]) => ({ top, height })),
    days, labelOverflow,
  }
}

/** Açık "+N" balonunun ölçüsü: kutu, metnin en sağ kenarı, satırlar. */
function measurePopover(el) {
  const r = el.getBoundingClientRect()
  let textRight = r.right
  const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT)
  for (let n = walker.nextNode(); n; n = walker.nextNode()) {
    if (!n.textContent.trim()) continue
    const range = document.createRange(); range.selectNodeContents(n)
    for (const q of range.getClientRects()) textRight = Math.max(textRight, q.right)
  }
  return {
    left: Math.round(r.left), right: Math.round(r.right), width: Math.round(r.width), sw: el.scrollWidth, cw: el.clientWidth,
    textRight: Math.round(textRight), lines: el.innerText.split('\n').map((s) => s.trim()).filter(Boolean),
  }
}

for (const vp of VIEWPORTS) {
  test.describe(`Ay takvimi dokunmatik — ${vp.name} (${vp.width})`, () => {
    test.use({
      viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch, timezoneId: 'Europe/Istanbul',
    })

    for (const pg of PAGES) {
      test(`${pg.key}: ${pg.dense ? 'dense' : 'çipli'} takvim — ölçü, sığma, dokunma hedefi, telefonda gün hedefi + balon`, async ({ page }, testInfo) => {
        test.setTimeout(150_000)
        await page.clock.setFixedTime(new Date(NOW))
        await page.addInitScript(() => { try { localStorage.setItem('site-monitor-lang', 'en') } catch { /* depolama yok */ } })
        await mockApi(page)
        await pg.mock(page)   // mockApi'den SONRA → önce denenir
        await pg.open(page)

        const cal = page.locator(CAL)
        await expect(cal).toBeVisible({ timeout: 60_000 })
        await expect(cal.locator('[data-slot="month-calendar-jump"]')).toHaveValue(MONTH)
        const total = Object.values(pg.labels).reduce((n, l) => n + l.length, 0)
        await expect(cal.locator('small').first()).toHaveText(tx('cal.count', total))
        await settle(page)

        // ── Ölçü — önce eklenir, sonra doğrulanır (doğrulama düşse de sayı raporda kalır)
        const m = await page.evaluate(measureCalendar, CAL)
        expect(m, 'calendar measured').not.toBeNull()
        const name = `${pg.key}-${vp.name}-calendar-metrics.json`
        await testInfo.attach(name, { body: JSON.stringify(m, null, 2), contentType: 'application/json' })
        testInfo.annotations.push({
          type: 'calendar-height',
          description: `${pg.key} @${vp.name}: card ${m.card.height}px, tallest day ${m.tallestDay.day} ${m.tallestDay.height}px, `
            + `weeks [${m.weeks.map((w) => w.height).join(', ')}]px`,
        })

        // Dar / geniş: ekrana değil takvim kartının genişliğine bağlı (kap sorgusu)
        const narrow = m.card.width < NARROW_MAX
        // ── Sığma / kırpılma / dokunma (ortak kapı)
        await checkLayout(page, `${pg.key} calendar ${vp.name}`, CAL)
        // Olay etiketi kendi kutusundan taşmaz (sarar)
        expect(m.labelOverflow, `${pg.key} @${vp.name}: event label wider than its box`).toEqual([])

        // ── Gün başına görünüm kuralı (beklenen olay sayısı verinin kendisinden)
        const issues = []
        for (const [day, labels] of Object.entries(pg.labels)) {
          const n = labels.length
          const d = m.days[day]
          if (!d) { issues.push(`${day}: day with ${n} events not rendered`); continue }
          const vis = d.triggers.filter((tg) => tg.visible)
          if (pg.dense && narrow) {
            if (d.visibleChips !== 0) issues.push(`${day}: dense narrow shows ${d.visibleChips} chips (expected dots only)`)
            if (!d.dayOpen?.visible) issues.push(`${day}: dense narrow day button not visible`)
            else {
              if (d.dayOpen.dots !== Math.min(n, 4)) issues.push(`${day}: ${d.dayOpen.dots} dots, expected ${Math.min(n, 4)}`)
              const want = tx('cal.dayOpen', dayLabel(day), n)
              if (d.dayOpen.label !== want) issues.push(`${day}: day button name "${d.dayOpen.label}", expected "${want}"`)
            }
            if (vis.length) issues.push(`${day}: dense narrow shows a "+N" trigger (${vis.map((x) => x.text).join(', ')})`)
          } else if (narrow) {
            if (d.visibleChips !== 0) issues.push(`${day}: narrow calendar shows ${d.visibleChips} chips (expected none)`)
            const want = n === 1 ? tx('cal.dayEvents.one', dayLabel(day), 1) : tx('cal.dayEvents', dayLabel(day), n)
            if (vis.length !== 1 || !vis[0].moreTouch || vis[0].name !== want) {
              issues.push(`${day}: expected one day target "${want}", got ${JSON.stringify(vis)}`)
            }
          } else {
            if (d.visibleChips !== Math.min(n, 3)) issues.push(`${day}: shows ${d.visibleChips} chips (expected ${Math.min(n, 3)})`)
            if (n > 3) {
              if (vis.length !== 1 || vis[0].moreTouch || vis[0].text !== `+${n - 3}`) {
                issues.push(`${day}: expected one "+${n - 3}" overflow hint, got ${JSON.stringify(vis)}`)
              }
            } else if (vis.length) issues.push(`${day}: day with ${n} events shows "+N" (${vis.map((x) => x.text).join(', ')})`)
          }
          // Dokunmatik: görünür her çip / "+N" / gün düğmesi ≥ 40 px (metinli hedefte yükseklik; "+N" genişlik de)
          if (vp.touch) {
            for (const l of d.labels) if (l.chip.h < 39.5) issues.push(`${day}: chip "${l.text.slice(0, 30)}" ${l.chip.w}×${l.chip.h}px`)
            for (const tg of vis) if (tg.h < 39.5 || tg.w < 39.5) issues.push(`${day}: "${tg.text}" trigger ${tg.w}×${tg.h}px`)
            if (d.dayOpen?.visible && d.dayOpen.h < 39.5) issues.push(`${day}: day button ${d.dayOpen.w}×${d.dayOpen.h}px`)
          }
        }
        expect(issues, `${pg.key} @${vp.name}: per-day calendar rule`).toEqual([])

        // Açık konumlarla aynı kural (hata mesajı insan okur)
        const busy = (day) => cal.locator(`[data-slot="month-calendar-day"][data-day="${day}"]`)
        const visibleChips = (day) => busy(day).locator('[data-slot="month-calendar-event"]').filter({ visible: true })
        if (pg.dense && narrow) {
          await expect(visibleChips(BUSY4)).toHaveCount(0)
          await expect(busy(BUSY4).locator('[data-slot="month-calendar-day-open"]'))
            .toHaveAttribute('aria-label', tx('cal.dayOpen', dayLabel(BUSY4), pg.labels[BUSY4].length))
        } else if (narrow) {
          await expect(visibleChips(BUSY3)).toHaveCount(0)
          await expect(visibleChips(BUSY4)).toHaveCount(0)
          const day3 = busy(BUSY3).locator('[data-slot="month-calendar-more-touch"] [data-slot="hint-trigger"]')
          await expect(day3).toBeVisible()
          await expect(day3).toHaveAccessibleName(tx('cal.dayEvents', dayLabel(BUSY3), pg.labels[BUSY3].length))
          await expect(busy(BUSY4).locator('[data-slot="month-calendar-more-touch"] [data-slot="hint-trigger"]'))
            .toHaveAccessibleName(tx('cal.dayEvents', dayLabel(BUSY4), pg.labels[BUSY4].length))
        } else {
          await expect(visibleChips(BUSY3)).toHaveCount(3)
          await expect(visibleChips(BUSY4)).toHaveCount(3)
          await expect(busy(BUSY4).locator('[data-slot="month-calendar-more-touch"] [data-slot="hint-trigger"]')).toBeHidden()
        }

        // ── Kart yüksekliği sınırı — her genişlikte (ölçü mesajda)
        expect(m.card.height,
          `${pg.key} @${vp.name}: calendar card ${m.card.height}px (limit ${CARD_MAX}); tallest day ${m.tallestDay.day} `
          + `${m.tallestDay.height}px; weeks [${m.weeks.map((w) => w.height).join(', ')}]px`).toBeLessThanOrEqual(CARD_MAX)
        // Beklenen kip gerçekten seçildi: 390 / 768'de dar, 1280'de geniş (eşik değişirse ölçü burada söyler)
        expect(narrow, `${pg.key} @${vp.name}: card ${m.card.width}px wide`).toBe(vp.width < 1000)

        // ── "+N" balonu (telefonda gün balonu): görünen çiplerle birlikte günün TÜM adları; metin kutusundan / ekrandan taşmaz
        if (!(pg.dense && narrow)) {
          const trigger = busy(BUSY4).locator('[data-slot="hint-trigger"]').filter({ visible: true })
          await expect(trigger).toHaveCount(1)
          await trigger.click()
          const pop = page.locator('[data-slot="hint-popover"]')
          await expect(pop).toBeVisible()
          await settle(page)
          const pm = await pop.evaluate(measurePopover)
          await testInfo.attach(`${pg.key}-${vp.name}-more-popover.json`, { body: JSON.stringify(pm, null, 2), contentType: 'application/json' })
          const shownLabels = m.days[BUSY4].labels.map((l) => l.text)
          expect([...shownLabels, ...pm.lines].sort(), `${pg.key} @${vp.name}: chips + "+N" balloon = every event of ${BUSY4}`)
            .toEqual([...pg.labels[BUSY4]].sort())
          expect(pm.sw, `${pg.key} @${vp.name}: "+N" balloon text wider than the balloon (${pm.sw} > ${pm.cw}px) — a long name without break points overflows`)
            .toBeLessThanOrEqual(pm.cw + 1)
          expect(pm.textRight, `${pg.key} @${vp.name}: "+N" balloon text runs past the screen edge`).toBeLessThanOrEqual(vp.width + 1)
          await expectInViewport(pop, vp, `${pg.key} "+N" balloon`)
          await page.keyboard.press('Escape')
          await expect(pop).toBeHidden()
        }

        // ── dense: gün düğmesi gün panelini açar, o günün sertifikaları listelenir
        if (pg.dense) {
          await busy(BUSY3).locator('[data-slot="month-calendar-day-open"]').click()
          const sheet = page.locator('[data-slot="fc-day-sheet"]')
          await expect(sheet).toBeVisible()
          const rows = sheet.locator('[data-slot="fc-day-row"]')
          await expect(rows).toHaveCount(pg.labels[BUSY3].length)
          for (const domain of pg.labels[BUSY3]) await expect(sheet).toContainText(domain)
          await settle(page)   // açılış kayması bitsin (kutu animasyon sırasında ekran dışında ölçülürdü)
          await expectInViewport(sheet, vp, `${pg.key} day sheet`)
          await page.keyboard.press('Escape')
          await expect(sheet).toBeHidden()
        }

        // Takvimin görüntüsü (EN SONDA; tam sayfa görüntüsü dokunmatik öykünmesini sıfırlar — burada yalnız öğe)
        await testInfo.attach(`${pg.key}-${vp.name}-calendar.png`, { body: await cal.screenshot(), contentType: 'image/png' })
      })
    }
  })
}
