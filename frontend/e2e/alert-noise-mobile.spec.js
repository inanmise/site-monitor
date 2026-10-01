// Alarm Geçmişi → Gürültü analizi (2026-09-30 yeniden tasarım) telefonda ve tablette: panel AÇIK ölçülür (varsayılan
// kapalı olduğu için responsive.spec.js onu hiç açmıyor). Kaynak listesi, öneri kartları, takım kartları/tablosu, saat
// grafiği ve 7×24 ısı haritası ekranın sağına taşmaz; sayfa yatay kaymaz.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

const rows = Array.from({ length: 7 }, () => Array(24).fill(0)); rows[1][14] = 5; rows[3][2] = 3
const NOISE = { days: 7, total: 12, critical: 3, distinct_targets: 3, still_open: 1, resolved_pct: 91.7, resolved_total: 11, mttr_minutes: 12.4,
  off_hours: 2, off_hours_pct: 16.7, per_day_avg: 1.7, silenced_total: 0, noisy_targets: 2, flap_targets: 1, flap_alerts: 9, noise_score: 75, night_pct: 25,
  series: Array.from({ length: 7 }, (_, i) => ({ date: `2026-09-${String(24 + i).padStart(2, '0')}`, count: i % 3 })),
  top: [
    { domain: 'https://cok-uzun-bir-alt-alan-adi.flap-ornegi.example.com/saglik/kontrol', type: 'HTTP_DOWN', count: 9, resolved: 9, avg_minutes: 3.5, share_pct: 75, monitor_type: 'http', team_id: 1, team_name: 'Takım A', median_minutes: 3.0, flap_count: 9, still_open: 1, last_opened_at: '2026-09-30T10:00:00', pattern: 'FLAPPING' },
    { domain: 'slow.example.com', type: 'PING_SLOW', count: 3, resolved: 2, avg_minutes: 42, share_pct: 25, monitor_type: 'ping', team_id: 2, team_name: 'Takım B', median_minutes: 40, flap_count: 0, still_open: 0, last_opened_at: '2026-09-29T10:00:00', pattern: 'SLOW_THRESHOLD_TIGHT' },
  ],
  flapping: [{ domain: 'https://cok-uzun-bir-alt-alan-adi.flap-ornegi.example.com/saglik/kontrol', type: 'HTTP_DOWN', count: 9, avg_minutes: 3.5, suggestion: 'raise-confirm' }],
  heat: { rows, peak: 5, peak_day: 1, peak_hour: 14, by_hour: [], by_day: [] },
  hours: Array.from({ length: 24 }, (_, h) => ({ hour: h, count: h === 14 ? 5 : h === 2 ? 3 : 0, night: h >= 22 || h < 6 })),
  by_type: [{ type: 'HTTP_DOWN', count: 9, critical: 3, share_pct: 75, monitor_type: 'http', silent: 0 }, { type: 'PING_SLOW', count: 3, critical: 0, share_pct: 25, monitor_type: 'ping', silent: 0 }],
  teams: [
    { team_id: 1, team_name: 'Takım A', alerts: 9, critical: 3, still_open: 1, noisy_targets: 1, flaps: 9, flap_targets: 1, silent_closes: 0, off_hours: 2, storms: 2, share_pct: 75, noise_score: 100 },
    { team_id: 2, team_name: 'Takım B', alerts: 3, critical: 0, still_open: 0, noisy_targets: 1, flaps: 0, flap_targets: 0, silent_closes: 0, off_hours: 0, storms: 0, share_pct: 25, noise_score: 20 },
  ],
  team_options: [{ id: 1, name: 'Takım A' }, { id: 2, name: 'Takım B' }],
  my_team_ids: [1], default_team_id: 1,
  suggestions: [
    { code: 'FLAPPING', severity: 'HIGH', title_key: 'noise.sug.FLAPPING', target: 'https://cok-uzun-bir-alt-alan-adi.flap-ornegi.example.com/saglik/kontrol', type: 'HTTP_DOWN', monitor_type: 'http', team_id: 1, team_name: 'Takım A', count: 9, params: [9, 3.5], action: { kind: 'open_monitor', tab: 'http', params: { q: 'flap' } } },
    { code: 'SLOW_THRESHOLD_TIGHT', severity: 'MEDIUM', title_key: 'noise.sug.SLOW_THRESHOLD_TIGHT', target: 'slow.example.com', type: 'PING_SLOW', monitor_type: 'ping', team_id: 2, team_name: 'Takım B', count: 3, params: [3], action: { kind: 'open_monitor', tab: 'ping', params: { q: 'slow.example.com' } } },
    { code: 'STORM_PRONE', severity: 'MEDIUM', title_key: 'noise.sug.STORM_PRONE', team_id: 1, team_name: 'Takım A', count: 2, params: [2, 7], action: { kind: 'open_settings', tab: 'settings', params: { sec: 'storm' } } },
  ] }

function measure() {
  const vw = document.documentElement.clientWidth
  const root = document.querySelector('.app-main') || document.body
  const pageOverflow = Math.max(...[document.documentElement, document.body, root].map((el) => el.scrollWidth - el.clientWidth))
  const offenders = []
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    if (r.right <= vw + 1 || r.width <= 1 || r.height <= 1) continue
    const cs = getComputedStyle(el)
    if (cs.visibility === 'hidden' || cs.display === 'none') continue
    let contained = false
    for (let p = el.parentElement; p && p !== root; p = p.parentElement) {
      if (getComputedStyle(p).overflowX !== 'visible' && p.getBoundingClientRect().right <= vw + 1) { contained = true; break }
    }
    if (!contained) offenders.push(`${el.tagName.toLowerCase()}.${String(el.className).slice(0, 50)} → ${Math.round(r.right)}`)
    if (offenders.length >= 6) break
  }
  return { pageOverflow, offenders }
}

for (const vp of [{ name: 'phone', width: 390, height: 844 }, { name: 'tablet', width: 768, height: 1024 }]) {
  test(`gürültü analizi açık — ${vp.name}`, async ({ page }) => {
    const pageErrors = []
    page.on('pageerror', (e) => pageErrors.push(`${e.message} @ ${(e.stack || '').split('\n').slice(1, 4).join(' | ')}`))
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await page.addInitScript(() => { try { sessionStorage.setItem('alh-noise-open', 'true') } catch { /* yok say */ } })
    await mockApi(page)
    await page.route((u) => new URL(u).pathname === '/api/admin/alerts/noise',
      (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: NOISE }) }))
    await page.goto('/?tab=alerthistory')
    await page.waitForTimeout(2500)
    expect(pageErrors, 'sayfa hatası').toEqual([])
    await page.getByText(/Takım A/).first().waitFor({ timeout: 20_000 })
    await page.waitForTimeout(800)
    const m = await page.evaluate(measure)
    expect(m.offenders, `${vp.name}: gürültü paneli ekran dışına taşıyor`).toEqual([])
    expect(m.pageOverflow).toBeLessThanOrEqual(1)
  })
}

// Tıklanabilir gün çubuğu ve ısı hücresi (2026-10-01): gün → Alarm Geçmişi "Tümü" görünümü o güne süzülür (URL);
// hücre → dilim paneli sunucudan listeler (telefonda panel ekrana sığar).
for (const vp of [{ name: 'desktop', width: 1280, height: 900 }, { name: 'phone', width: 390, height: 844 }]) {
  test(`gürültü: gün ve hücre tıklaması — ${vp.name}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height })
    await page.addInitScript(() => { try { sessionStorage.setItem('alh-noise-open', 'true') } catch { /* yok say */ } })
    await mockApi(page)
    await page.route((u) => new URL(u).pathname === '/api/admin/alerts/noise',
      (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: NOISE }) }))
    await page.route((u) => new URL(u).pathname === '/api/admin/alerts/noise/slot',
      (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: {
        days: 7, dow: 1, hour: 14, total: 1, truncated: false,
        items: [{ id: 91, domain: 'flap.example.com', alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL', created_at: '2026-09-29T11:10:00', resolved: true, team_id: 1, team_name: 'Takım A' }] } }) }))
    await page.goto('/?tab=alerthistory')
    const day = page.locator('[data-slot="noise-day"]').first()
    await day.waitFor({ timeout: 20_000 })
    const date = await day.getAttribute('data-day')
    await day.click()
    await expect(page).toHaveURL(new RegExp(`view=all.*from=${date}|from=${date}.*view=all`))
    const cell = page.locator('[data-slot="noise-heat-cell"]').first()
    await cell.scrollIntoViewIfNeeded()
    await cell.click()
    const sheet = page.locator('[data-slot="noise-slot-sheet"]')
    await sheet.waitFor()
    await page.locator('[data-slot="noise-slot-item"]').first().waitFor()
    await page.waitForTimeout(700)   // Sheet sağdan kayarak açılır — ölçüm animasyon bitince
    const box = await sheet.boundingBox()
    expect(box.x).toBeGreaterThanOrEqual(0)
    expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
  })
}
