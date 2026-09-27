// SAYFALAMA ÇUBUĞU YERLEŞİMİ — gerçek tarayıcıda (jsdom medya sorgusu/yerleşim yapmaz). API mock'lu, girişsiz.
//
// Standart (2026-09-26): tek çubuk `ui/PaginationBar` (shadcn Data Table sayfalaması). İki liste ölçülür:
//   - Bakım Pencereleri (istemci: usePagination, 120 satır)
//   - Aktivite Logu (sunucu: useServerPagination, 120 kayıt, sayfa başı 50)
// Her ikisi 390×844 (telefon) ve 1280×800 (masaüstü) boyunda: sayfa düzeyinde yatay taşma yok, çubuk görünüm
// alanında, gezinme görünür, "Sonraki" çalışır; telefonda ‹ › ve boyut seçici ≥ 40 px dokunma hedefi, sayfa
// numaraları gizli (yerine "1 / 3"); masaüstünde numaralar görünür.
// Yalnız `/api/` yolları taklit edilir (`**/api/**` DEĞİL — kaynak modülü /src/api/client.js'i de yakalar).
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

const NOW = Date.parse('2026-09-26T09:30:00Z')
const iso = (msAgo) => new Date(NOW - msAgo).toISOString().slice(0, 19)
const TYPES = ['HTTP', 'PING', 'DNS', 'PORT', 'CERT']

function activityRow(i) {
  return {
    id: i + 1, monitor_type: TYPES[i % TYPES.length], monitor_id: 100 + (i % 7), monitor_name: `izleme-${i + 1}.example.com`,
    target: `https://izleme-${i + 1}.example.com/`, action: 'SCHEDULED_CHECK', result_status: i % 9 === 0 ? 'ERROR' : 'SUCCESS',
    result_summary: i % 9 === 0 ? 'zaman aşımı' : `200 · ${120 + i}ms`, activity_time: iso(i * 7 * 60_000),
    team_id: 1, team_name: 'Takım A', response_ms: 120 + i,
  }
}
const ACTIVITY = Array.from({ length: 120 }, (_, i) => activityRow(i))

const WINDOWS = Array.from({ length: 120 }, (_, i) => ({
  id: i + 1, name: `Gece bakımı ${i + 1}`, description: i % 4 === 0 ? 'Veritabanı yama penceresi — tüm ödeme uçları' : null,
  all_monitors: i % 5 === 0, target_count: 3 + (i % 6), recurrence: ['DAILY', 'WEEKLY', 'MONTHLY', 'ONCE'][i % 4],
  days_of_week: 'MON,WED', day_of_month: 15, start_at: '2026-09-26T22:00:00', timezone: 'Europe/Istanbul',
  duration_minutes: 60, status: i % 3 === 0 ? 'active' : 'scheduled', next_occurrence: '2026-09-27T22:00:00',
}))

async function mockLists(page, seen) {
  await mockApi(page, { role: 'ADMIN' })
  // Sonra kaydedilen yönlendirme önce çalışır; bizimkiler dışındakiler mockApi'ye düşer.
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const u = new URL(route.request().url())
    if (u.pathname === '/api/activity') {
      const p = Number(u.searchParams.get('page') || 0)
      const size = Number(u.searchParams.get('size') || 50)
      seen.push({ page: p, size })
      return route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ success: true, page: p, total: ACTIVITY.length, data: ACTIVITY.slice(p * size, (p + 1) * size) }) })
    }
    if (u.pathname === '/api/activity/summary') {
      return route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify({ success: true, total: 120, success_count: 106, warning: 0, error: 14, last_activity: iso(0) }) })
    }
    if (u.pathname === '/api/monitoring/maintenance') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: WINDOWS }) })
    }
    return route.fallback()
  })
}

/** Çubuk + sayfa ölçümü (tarayıcıda). */
function measureBar() {
  const bar = document.querySelector('[data-slot="pagination-bar"]')
  if (!bar) return null   // yeniden çizim anı — çağıran toPass ile yeniden dener
  const vw = document.documentElement.clientWidth
  const main = document.querySelector('.app-main') || document.body
  const pageOverflow = Math.max(...[document.documentElement, document.body, main].map((el) => el.scrollWidth - el.clientWidth))
  const r = bar.getBoundingClientRect()
  const box = (el) => { if (!el) return null; const b = el.getBoundingClientRect(); return { w: Math.round(b.width), h: Math.round(b.height), visible: b.width > 0 && b.height > 0 } }
  const btn = (name) => [...bar.querySelectorAll('button')].find((b) => b.getAttribute('aria-label') === name)
  const overflowing = [...bar.querySelectorAll('*')].filter((el) => {
    const b = el.getBoundingClientRect()
    return b.width > 0 && b.height > 0 && (b.right > vw + 1 || b.left < -1)
  }).map((el) => el.tagName.toLowerCase() + (el.getAttribute('data-slot') ? `[${el.getAttribute('data-slot')}]` : ''))
  return {
    vw, pageOverflow, barLeft: Math.round(r.left), barRight: Math.round(r.right), overflowing,
    next: box(btn('Next') || btn('Sonraki')), prev: box(btn('Previous') || btn('Önceki')),
    first: box(btn('First page') || btn('İlk sayfa')),
    size: box(bar.querySelector('[data-slot="select-trigger"]')),
    pageTwo: box(btn('Page 2') || btn('Sayfa 2')),
    // preflight yok: <ul> madde imi / 40 px dolgu çizmemeli
    list: (() => { const ul = bar.querySelector('[data-slot="pagination-content"]'); if (!ul) return null; const cs = getComputedStyle(ul); return { style: cs.listStyleType, padLeft: cs.paddingLeft } })(),
  }
}

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, phone: true },
  { name: 'desktop', width: 1280, height: 800, phone: false },
]

const LISTS = [
  { name: 'maintenance (istemci)', tab: 'maintenance', firstRow: 'Gece bakımı 1', secondPageRow: 'Gece bakımı 51' },
  { name: 'activity (sunucu)', tab: 'activity', firstRow: 'izleme-1.example.com', secondPageRow: 'izleme-51.example.com' },
]

for (const vp of VIEWPORTS) {
  for (const list of LISTS) {
    test(`sayfalama çubuğu ${list.name} @${vp.name} ${vp.width}×${vp.height}`, async ({ page }) => {
      await page.setViewportSize({ width: vp.width, height: vp.height })
      await page.addInitScript(() => { try { localStorage.clear(); localStorage.setItem('sm.tour', JSON.stringify({ status: 'dismissed', version: 99 })) } catch { /* yoksay */ } })
      const seen = []
      await mockLists(page, seen)
      await page.goto(`/?tab=${list.tab}`)
      await expect(page.getByText(list.firstRow, { exact: true }).first()).toBeVisible({ timeout: 20_000 })
      const bar = page.locator('[data-slot="pagination-bar"]')
      await bar.scrollIntoViewIfNeeded()
      await expect(bar).toBeVisible()
      await expect(bar.getByRole('navigation', { name: /^(Sayfalama|Pagination)$/ })).toBeVisible()
      await expect(bar.getByText(/1–50 (\/|of) 120/)).toBeVisible()

      // Ölçüm toPass içinde: geliştirme sunucusunun HMR yeniden yüklemesi çubuğu bir anlığına yeniden kurabilir.
      await expect(async () => {
        const m = await page.evaluate(measureBar)
        expect(m, 'çubuk DOM\'da').not.toBeNull()
        expect(m.pageOverflow, `sayfa yatay taşıyor (${JSON.stringify(m)})`).toBeLessThanOrEqual(1)
        expect(m.overflowing, 'çubukta görünüm alanı dışına taşan öğe').toEqual([])
        expect(m.barRight).toBeLessThanOrEqual(m.vw + 1)
        expect(m.next.visible && m.prev.visible).toBe(true)
        expect(m.list, 'gezinme listesi madde imsiz ve dolgusuz').toEqual({ style: 'none', padLeft: '0px' })
        if (vp.phone) {
          // ‹ 1 / 3 ›: numaralar ve «» gizli, konum göstergesi görünür; dokunma hedefleri ≥ 40 px
          expect(m.next.w).toBeGreaterThanOrEqual(40); expect(m.next.h).toBeGreaterThanOrEqual(40)
          expect(m.prev.w).toBeGreaterThanOrEqual(40); expect(m.prev.h).toBeGreaterThanOrEqual(40)
          expect(m.size.h, 'boyut seçici dokunma yüksekliği').toBeGreaterThanOrEqual(40)
          expect(m.first.visible).toBe(false)
          expect(m.pageTwo.visible).toBe(false)
        } else {
          expect(m.pageTwo.visible).toBe(true)
          expect(m.first.visible).toBe(true)
        }
      }).toPass({ timeout: 15_000 })
      if (vp.phone) await expect(bar.getByText('1 / 3', { exact: true })).toBeVisible()
      else await expect(bar.getByText(/^(Page 1 of 3|Sayfa 1 \/ 3)$/)).toBeVisible()

      // Sonraki sayfa çalışır (sunucu listesinde istek page=1 ile gider)
      await bar.getByRole('button', { name: /^(Sonraki|Next)$/ }).click()
      await expect(bar.getByText(/51–100 (\/|of) 120/)).toBeVisible()
      await expect(page.getByText(list.secondPageRow, { exact: true }).first()).toBeAttached()
      if (list.tab === 'activity') expect(seen.some((s) => s.page === 1 && s.size === 50)).toBe(true)
    })
  }
}
