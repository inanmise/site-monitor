// İstatistikler zenginleştirmesi (2026-11-13). Üç boyutta (390 telefon + dokunmatik, 768 tablet + dokunmatik, 1280
// dizüstü) gerçek tarayıcıda ölçülür: sayfa yatay taşmaz; güvenlik / sağlayıcı / hafta / takım karnesi / izleme türleri /
// eğilim kartları çizilir; uzun takım adları (gerçek uzunlukta AD adları) KESİLMEZ; dokunma hedefleri ≥ 40 px; güvenlik
// çipi ve hafta çubuğu tabloyu süzer ve adrese yazar; "Açık alarm" kutucuğu Alarm Geçmişi'nin açık alarm görünümüne gider.
// API tamamen mock'lu (yalnız /api/ yolu); alan adları ve takım adları uydurmadır.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

const DAY = 86_400_000
const NOW = Date.now()
const iso = (ms) => new Date(NOW + ms).toISOString().slice(0, 19)
const LONG_A = 'Kurumsal Dijital Kanallar ve Ödeme Sistemleri Uygulama Geliştirme Müdürlüğü (Bölge 3 / Ankara)'
const LONG_B = 'Altyapı ve Ağ Operasyonları Takımı (Kurumsal Uygulamalar / Gece Vardiyası)'
const TEAM_NAMES = { 1: LONG_A, 2: LONG_B, 3: 'Takım C' }

function cert(i, domain, days, extra = {}) {
  const status = extra.status ?? 'valid'
  const team = extra.team_id === undefined ? 1 + (i % 3) : extra.team_id
  return {
    domain, port: 443, days_remaining: days, not_after: days == null ? null : iso(days * DAY), not_before: iso(-300 * DAY),
    checked_at: iso(-60_000), status,
    alert_level: status === 'error' ? 'error' : days == null ? null : days < 0 ? 'expired' : days <= 7 ? 'critical' : days <= 30 ? 'warning' : 'valid',
    issuer_cn: ['R3', 'DigiCert Global G2 TLS RSA SHA256 2020 CA1', 'Sectigo RSA Domain Validation Secure Server CA'][i % 3],
    tier: 1 + (i % 4), team_id: team, team_name: team ? TEAM_NAMES[team] : null, owner_team_ids: team ? [team] : [],
    tls_grade: ['A+', 'A', 'B', 'C', 'F', undefined][i % 6], chain_status: 'VALID', trust_status: 'TRUSTED',
    public_key_algorithm: 'RSA', public_key_size: 2048, ...extra,
  }
}

const CERTS = [
  ...Array.from({ length: 18 }, (_, i) => cert(i, `servis-${i + 1}.example.com`, [3, 9, 15, 24, 40, 55, 70, 90, 120, 200][i % 10])),
  cert(18, 'odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.com', 5, { trust_status: 'UNTRUSTED' }),
  cert(19, 'eski-anahtar.example.com', 30, { public_key_size: 1024, tls_grade: 'F' }),
  cert(20, 'dolmus.example.com', -4),
  cert(21, 'hatali.example.com', null, { status: 'error', error: 'Connection timed out after 10000 ms' }),
  cert(22, 'sahipsiz.example.com', 80, { team_id: null }),
]
const TEAMS = { mode: 'domains', teams: [1, 2, 3].map((id) => ({
  team_id: id, team_name: TEAM_NAMES[id],
  sy_domains: CERTS.filter((c) => c.team_id === id).map((c) => c.domain),
  ug_domains: id === 2 ? [CERTS[0].domain] : [],
})) }
const EXEC = {
  certs: { total: CERTS.length, ok: 12, evaluated: CERTS.length - 1, unchecked: 1, under30: 6, under7: 2, expired: 1, error: 1, health_pct: 54.5 },
  alerts: { visible: true, open: 7, critical: 2, opened_last30: 31, opened_prev30: 25, delta: 6 },
  sla: { target_pct: 99.9, monitors: 42, breaches: 3, worst: [
    { type: 'http', id: 101, name: 'Ödeme API — Üretim Kümesi (Bölge 1 / Ana Veri Merkezi, kurumsal ödeme altyapısı)', up_pct: 97.25, bad_hours: 9 },
    { type: 'ping', id: 102, name: 'core-router-1.example.com', up_pct: 99.5, bad_hours: 2 }],
    by_team: [{ team_id: 1, monitors: 20, breaches: 2 }, { team_id: 2, monitors: 22, breaches: 1 }] },
  teams: [], window_days: 30,
}
const NOISE = { days: 30, total: 31, critical: 4, resolved_pct: 87.1, mttr_minutes: 142,
  series: Array.from({ length: 30 }, (_, i) => ({ date: new Date(NOW - (29 - i) * DAY).toISOString().slice(0, 10), count: (i * 7) % 5 })) }
const TEAM_ALERTS = { teams: [{ team_id: 1, team_name: LONG_A, open: 4, last30: 18 }, { team_id: 2, team_name: LONG_B, open: 3, last30: 13 }] }
const RENEWAL = { windows: [3, 6, 12].map((m, i) => ({ months: m, renewals: 10 + i * 8, before_expiry: 9 + i * 7, after_expiry: 1, last_minute: 2, lapses: i, on_time_pct: [90, 88.9, 84.6][i] })) }
const HEALTH = { scope: 'org', org_allowed: true, teams: [{ id: 1, name: LONG_A }, { id: 2, name: LONG_B }], today: iso(0).slice(0, 10),
  collecting_since: iso(-20 * DAY).slice(0, 10),
  points: Array.from({ length: 21 }, (_, i) => ({ day: iso(-(20 - i) * DAY).slice(0, 10), total: 23, evaluated: 22, ok: 12 + (i % 4), under30: 6,
    under7: 2, expired: 1, error: 1, health_pct: Math.round(((12 + (i % 4)) / 22) * 1000) / 10 })) }
const PERMS = { 'alerts.read': { view: true }, 'monitoring.read': { view: true }, 'data_quality.view': { view: true },
  'inventory.list': { view: true }, 'inventory.crud': { view: true, edit: true } }

async function mock(page) {
  await mockApi(page, { perms: PERMS })
  const json = (body) => ({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  const routes = {
    '/api/certificates': { success: true, data: CERTS, timestamp: iso(0) },
    '/api/certificates/card-extras': { success: true, data: {} },
    '/api/stats': { success: true, data: { total: CERTS.length, valid: 12, warning: 6, critical: 2, error: 1, expired: 1 } },
    '/api/stats/teams': { success: true, data: TEAMS },
    '/api/stats/executive': { success: true, data: EXEC },
    '/api/stats/renewal-timeliness': { success: true, data: RENEWAL },
    '/api/stats/health-series': { success: true, data: HEALTH },
    '/api/admin/alerts/noise': { success: true, data: NOISE },
    '/api/admin/alerts/team-stats': { success: true, data: TEAM_ALERTS },
  }
  await page.route((u) => Object.hasOwn(routes, new URL(u).pathname), (route) => route.fulfill(json(routes[new URL(route.request().url()).pathname])))
  await page.route((u) => new URL(u).pathname === '/api/stats/changes', (route) => {
    const days = Number(new URL(route.request().url()).searchParams.get('days') || 7)
    return route.fulfill(json({ success: true, data: { days, added: 2, removed: 1, renewed: days > 7 ? 9 : 3, alerts_opened: 5, alerts_resolved: 4 } }))
  })
}

async function pageOverflow(page) {
  return page.evaluate(() => {
    const main = document.querySelector('.app-main') || document.body
    return Math.max(document.documentElement.scrollWidth - document.documentElement.clientWidth, main.scrollWidth - main.clientWidth)
  })
}

/** Dokunma hedefi 40 px'ten kısa görünür denetimler (`::after` ile büyütülmüş hedef sayılır). */
function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button, a[href], select, input:not([type="hidden"]), [role="combobox"]')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0) continue
    const cs = getComputedStyle(b)
    if (cs.visibility === 'hidden' || cs.display === 'none') continue
    let h = (b.closest('[data-slot="input-group"]')?.getBoundingClientRect() ?? r).height
    const after = getComputedStyle(b, '::after')
    if (after.position === 'absolute') h = Math.max(h, r.height - (parseFloat(after.top) || 0) - (parseFloat(after.bottom) || 0))
    if (h < 39.5) out.push(`${b.tagName.toLowerCase()}[${b.getAttribute('data-slot') || b.getAttribute('role') || ''}] "${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)}" ${Math.round(h)}`)
  }
  return out
}

/** Kesilen takım adı / izleme adı / alan adı (üç nokta, taşma ya da hap rozetinin köşesi). */
function clippedText(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const rootR = root.getBoundingClientRect()
  const out = []
  for (const el of root.querySelectorAll('[data-slot="team-badge"] span, [data-slot="exs-sla-row"] span, [data-slot="stats-matrix-teamless"], [data-slot="stats-row"] td, [data-slot="stats-score-row"] span')) {
    const r = el.getBoundingClientRect()
    if (r.width === 0) continue
    const cs = getComputedStyle(el)
    const box = el.closest('[data-slot="team-badge"]')
    const pillClip = box && getComputedStyle(box).overflow !== 'visible' && box.getBoundingClientRect().height > 30
      && parseFloat(getComputedStyle(box).borderTopLeftRadius) > 8
    if (pillClip || cs.textOverflow === 'ellipsis' || el.scrollWidth > el.clientWidth + 1 || r.right > rootR.right + 1) {
      out.push(`${el.getAttribute('data-slot') || el.tagName.toLowerCase()} "${el.textContent.trim().slice(0, 30)}" ${el.scrollWidth}>${el.clientWidth}`)
    }
  }
  return out
}

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true, score: 'cards' },
  { name: 'tablet', width: 768, height: 1024, touch: true, score: 'cards' },
  { name: 'laptop', width: 1280, height: 900, touch: false, score: null },
]

for (const vp of VIEWPORTS) {
  test.describe(`İstatistikler — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('bölümler çizilir, taşma / kırpma yok, dokunma hedefleri ≥ 40 px; süzgeçler adrese yazar; kutucuk hedefi', async ({ page }) => {
      test.setTimeout(150_000)
      await mock(page)
      await page.goto('/?tab=stats&st_mx=1')
      const view = page.locator('[data-slot="stats-view"]')
      await expect(view).toBeVisible({ timeout: 30_000 })
      for (const slot of ['stats-weeks', 'stats-security', 'stats-composition', 'stats-mtypes', 'stats-alarm-trend', 'stats-renewal', 'stats-health', 'stats-scorecard']) {
        await expect(view.locator(`[data-slot="${slot}"]`), slot).toBeVisible({ timeout: 30_000 })
      }
      await expect(view.locator('[data-slot="exs-kpi"]')).toHaveCount(3, { timeout: 30_000 })
      await expect(view.locator('[data-slot="exs-sla-row"]')).toHaveCount(2)
      await expect(view.locator('[data-slot="stats-week"]')).toHaveCount(12)
      await expect(view.locator('[data-slot="stats-renewal-win"]')).toHaveCount(3)
      await expect(view.locator('[data-slot="stats-trend-stat"][data-stat="mttr"]')).toContainText(/2,4|2\.4/)
      if (vp.score) await expect(view.locator('[data-slot="stats-scorecard"]')).toHaveAttribute('data-layout', vp.score)
      await expect(view.locator('[data-slot="stats-score-row"][data-team="none"]'), 'takımsız satır').toHaveCount(1)
      await expect(view.locator('[data-slot="stats-matrix-teamless"]').first()).toBeVisible()
      await expect(view.getByText(LONG_A).first()).toBeVisible()

      expect(await pageOverflow(page), 'yatay taşma').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clippedText, '[data-slot="stats-view"]'), 'kırpılan metin').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="stats-view"]'), 'küçük dokunma hedefi').toEqual([])

      // Güvenlik çipi → tablo süzülür, adres st_sec; aynı çip kaldırır
      const chip = view.locator('[data-slot="stats-sec-chip"][data-sec="weakkey"]')
      await chip.click()
      await expect(page).toHaveURL(/st_sec=weakkey/)
      // Satırlar genişte tablo (`stats-row`), dar ekranda kart (`stats-card`) — ikisi de `data-domain` taşır
      const rows = view.locator('[data-slot="stats-row"], [data-slot="stats-card"]')
      await expect(rows).toHaveCount(1)
      await expect(rows.first()).toHaveAttribute('data-domain', 'eski-anahtar.example.com')
      await chip.click()
      await expect(page).not.toHaveURL(/st_sec=/)

      // Hafta çubuğu → yalnız o hafta; adres st_wk
      const week = view.locator('[data-slot="stats-week"]:not([disabled])').first()
      await week.click()
      await expect(page).toHaveURL(/st_wk=\d+/)
      expect(await rows.count()).toBeGreaterThan(0)
      await week.click()
      await expect(page).not.toHaveURL(/st_wk=/)
      expect(await pageOverflow(page), 'süzgeçten sonra yatay taşma').toBeLessThanOrEqual(1)

      // fullPage görüntüsü dokunmatik öykünmeyi sıfırlar — ölçümlerden SONRA
      await page.screenshot({ path: `test-results/stats-${vp.name}.png`, fullPage: true })

      // "Açık alarm" kutucuğu → Alarm Geçmişi, açık alarm görünümü
      await view.locator('[data-slot="exs-kpi"][data-kpi="alerts"]').click()
      await expect(page).toHaveURL(/tab=alerthistory/)
      await expect(page).toHaveURL(/view=open/)
    })
  })
}
