// Nöbet Rutinleri — gün sayfasının sağ rayı (nöbetçi + ilerleme kartları) her genişlikte düzgün dizilir (2026-11-26 hata
// bildirimi: geniş ekranda iki kart yan yana ~180 px'e sıkışıyor, "Devral" menüsü, "Bu haftayı ben devralıyorum" düğmesi ve
// ilerleme halkası taşıyor / üst üste biniyordu). Neden: Tailwind px cinsinden kapsayıcı sorgusunu (`@min-[900px]/page`)
// rem cinsinden adlandırılmıştan (`@md/page`) ÖNCE üretiyor; "≥ 900 px tek sütun" kuralı sonradan gelen iki sütun kuralına
// yeniliyordu. Kural artık aralık: yalnız 42rem ≤ sayfa < 900 px iken yan yana.
//
// Değişmez (sayfa kapsayıcısının ölçülen genişliğine göre):
//  - ≥ 900 px ve < 42rem: kartlar ALT ALTA (aynı sol kenar, ilerleme nöbetçinin altında);
//  - 42rem ≤ genişlik < 900 px: YAN YANA (aynı üst kenar);
//  - her durumda kartların hiçbir alt öğesi kartın dışına taşmaz, halka ile metin sütunu üst üste binmez, düğme metni taşmaz.
// İki görünüm: (a) nöbetçi yok + "Bu haftayı ben devralıyorum" + kısmen tamamlanmış gün (bildirimdeki ekran),
// (b) uzun AD adlı nöbetçi + not. Genişlikler: 390 / 768 dokunmatik, 1024, 1280, 1440, 1920, 2560.
import { test, expect } from '@playwright/test'
import { EN } from '../src/i18n/en.js'
import { mockApi } from './support/monitorMocks.js'
import { checkLayout } from './support/layoutAudit.js'
import { mockOncallRoutines, OR_TEAM_ID, OR_TODAY, OR_NOW, OR_SELIN } from './support/oncallRoutineMocks.js'

const PAGE = '[data-slot="or-page"]'
const LONG_NAME = 'Ayşe Gülşen Yılmazoğlu Karaarslan (Dijital Kanallar Operasyon Müdürlüğü / Kıdemli Uzman)'

const WIDTHS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'w1024', width: 1024, height: 900, touch: false },
  { name: 'w1280', width: 1280, height: 900, touch: false },
  { name: 'w1440', width: 1440, height: 900, touch: false },
  { name: 'w1920', width: 1920, height: 1080, touch: false },
  { name: 'w2560', width: 2560, height: 1440, touch: false },
]

function tx(key, ...args) {
  const s = EN[key]
  if (typeof s !== 'string') throw new Error(`EN key missing: ${key}`)
  return args.reduce((acc, a, i) => acc.split(`{${i}}`).join(String(a)), s)
}

const NONE_PARTIAL = {
  day: (d) => ({
    ...d,
    day: { ...d.day, status: 'COMPLETED_PARTIAL', completed_by_name: 'erdi', completed_at: `${OR_TODAY}T11:37:00Z` },
    can_reopen: true,
    responsible: {
      user_id: null, name: null, username: null, active: false, is_me: false, source: 'NONE',
      schedule_configured: false, schedule_enabled: false, assignment: null,
    },
  }),
}

const LONG_RESPONSIBLE = {
  day: (d) => ({
    ...d,
    responsible: {
      user_id: 9101, name: LONG_NAME, username: 'n91010', active: true, is_me: false, source: 'TAKEOVER_WEEK',
      schedule_configured: true, schedule_enabled: true,
      assignment: {
        scope: 'WEEK', key: d.date, note: 'Yıllık izin nedeniyle haftanın tamamı devralındı; acil durumda takım lideri aranır.',
        created_by_name: OR_SELIN.name, created_at: '2026-11-25T15:10:00Z',
      },
    },
  }),
}

async function open(page, transform) {
  await page.clock.setFixedTime(new Date(OR_NOW))
  await page.addInitScript(() => { try { localStorage.setItem('site-monitor-lang', 'en') } catch { /* yoksay */ } })
  await mockApi(page)
  const st = await mockOncallRoutines(page, { transform })
  await page.goto('/?tab=oncallroutines')
  await expect(page.locator(`${PAGE}[data-team="${OR_TEAM_ID}"]`)).toBeVisible({ timeout: 30_000 })
  await expect(page.locator('[data-slot="or-responsible"]')).toBeVisible()
  await expect(page.locator('[data-slot="or-progress"]')).toBeVisible()
  return st
}

/** Ray kartlarının geometrisi + kart dışına taşan alt öğeler + halka / metin çakışması + düğme metni taşması. */
function measureRail(page) {
  return page.evaluate(() => {
    const r = (el) => el.getBoundingClientRect()
    const pageEl = document.querySelector('[data-slot="or-day"]')
    const resp = document.querySelector('[data-slot="or-responsible"]')
    const prog = document.querySelector('[data-slot="or-progress"]')
    const rootPx = parseFloat(getComputedStyle(document.documentElement).fontSize) || 16
    const outside = []
    for (const card of [resp, prog]) {
      const c = r(card)
      for (const el of card.querySelectorAll('*')) {
        const b = r(el)
        if (!b.width || !b.height) continue
        if (getComputedStyle(el).position === 'fixed') continue
        if (b.left < c.left - 1 || b.right > c.right + 1 || b.top < c.top - 1 || b.bottom > c.bottom + 1) {
          outside.push(`${card.getAttribute('data-slot')} > ${el.getAttribute('data-slot') || el.tagName.toLowerCase()}`
            + ` [${Math.round(b.left)}..${Math.round(b.right)} / card ${Math.round(c.left)}..${Math.round(c.right)}]`)
        }
      }
    }
    // Halka (göstergenin kutusu) ile metin sütunu yatayda ancak alt alta dizildiyse kesişebilir
    const ring = prog.querySelector('svg')?.closest('div')
    const text = prog.querySelector('[data-slot="or-progress-text"]')?.parentElement
    let ringTextOverlap = false
    if (ring && text) {
      const a = r(ring)
      const b = r(text)
      const horiz = a.left < b.right - 1 && b.left < a.right - 1
      const vert = a.top < b.bottom - 1 && b.top < a.bottom - 1
      ringTextOverlap = horiz && vert
    }
    const overflowingButtons = []
    for (const btn of [...resp.querySelectorAll('button'), ...prog.querySelectorAll('button')]) {
      if (btn.scrollWidth > btn.clientWidth + 1) overflowingButtons.push(btn.getAttribute('data-slot') || btn.textContent.trim().slice(0, 40))
    }
    return {
      pageWidth: pageEl.getBoundingClientRect().width,
      rem42: 42 * rootPx,
      resp: r(resp).toJSON(),
      prog: r(prog).toJSON(),
      outside,
      ringTextOverlap,
      overflowingButtons,
    }
  })
}

function expectArrangement(m, label) {
  const sideBySide = m.pageWidth >= m.rem42 && m.pageWidth < 900
  if (sideBySide) {
    expect(Math.abs(m.resp.top - m.prog.top), `${label}: cards side by side (page ${m.pageWidth}px)`).toBeLessThanOrEqual(1)
    expect(m.prog.left, `${label}: progress right of the on-call card`).toBeGreaterThanOrEqual(m.resp.right - 1)
  } else {
    expect(Math.abs(m.resp.left - m.prog.left), `${label}: cards stacked — same left edge (page ${m.pageWidth}px)`).toBeLessThanOrEqual(1)
    expect(m.prog.top, `${label}: progress below the on-call card`).toBeGreaterThanOrEqual(m.resp.bottom - 1)
    expect(Math.abs(m.resp.width - m.prog.width), `${label}: stacked cards share the rail width`).toBeLessThanOrEqual(1)
  }
  // Ray kartı hiçbir genişlikte bir menü + bir ad + bir düğmeyi taşıyamayacak kadar daralmaz
  expect(Math.min(m.resp.width, m.prog.width), `${label}: card wide enough`).toBeGreaterThanOrEqual(260)
  expect(m.outside, `${label}: element outside its card`).toEqual([])
  expect(m.ringTextOverlap, `${label}: gauge overlaps the progress text`).toBe(false)
  expect(m.overflowingButtons, `${label}: button text overflows`).toEqual([])
}

for (const vp of WIDTHS) {
  test.describe(`Nöbet rutinleri sağ ray — ${vp.name} (${vp.width})`, () => {
    test.use({
      viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch, timezoneId: 'Europe/Istanbul',
    })

    test('nöbetçi yok + kısmen tamamlanmış gün: kartlar düzgün dizilir, hiçbir şey taşmaz', async ({ page }) => {
      test.setTimeout(90_000)
      const st = await open(page, NONE_PARTIAL)
      const resp = page.locator('[data-slot="or-responsible"]')
      await expect(resp).toHaveAttribute('data-source', 'NONE')
      await expect(resp.locator('[data-slot="or-responsible-take-week"]')).toBeVisible()
      await expect(resp.locator('[data-slot="or-responsible-take-week"]')).toContainText(tx('or.resp.takeWeek'))
      await expect(page.locator('[data-slot="or-progress"]')).toHaveAttribute('data-state', 'partial')
      expectArrangement(await measureRail(page), `none ${vp.name}`)
      await checkLayout(page, `rail none ${vp.name}`, PAGE)
      expect(st.unknown).toEqual([])
      await page.screenshot({ path: test.info().outputPath(`or-rail-none-${vp.name}.png`), fullPage: true })
    })

    test('uzun AD adlı nöbetçi + devir notu: ad sararak tam, menü kartın içinde', async ({ page }) => {
      test.setTimeout(90_000)
      const st = await open(page, LONG_RESPONSIBLE)
      const resp = page.locator('[data-slot="or-responsible"]')
      await expect(resp).toContainText(LONG_NAME)
      await expect(resp.locator('[data-slot="or-responsible-takeover"]')).toBeVisible()
      expectArrangement(await measureRail(page), `long ${vp.name}`)
      await checkLayout(page, `rail long ${vp.name}`, PAGE)
      expect(st.unknown).toEqual([])
      await page.screenshot({ path: test.info().outputPath(`or-rail-long-${vp.name}.png`), fullPage: true })
    })
  })
}
