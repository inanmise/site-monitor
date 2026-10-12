// Postmortem şablonu + aksiyon takibi (2026-10-11). Gerçek tarayıcıda 390 (dokunmatik) / 768 (dokunmatik) / 1280:
// olay ayrıntısından postmortem penceresi açılır, ekrana sığar, uzun adlar sarar (kırpılmaz), dokunmatik hedefler ≥ 40 px;
// rapor kaydı ve aksiyon ekleme doğru gövdeyle gider; "Aksiyonlar" görünümü taşmaz ve süzgeçler istek parametresine yansır.
// API mock'lu (bildirim yok).
import { test, expect } from '@playwright/test'

const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'
const LONG_PERSON = 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin (Teknoloji Servis Yönetimi Bölüm Başkanı)'
const ROW = {
  id: 7, title: 'Kurumsal internet bankacılığı ödeme servisinde sertifika süresi dolduğu için yaşanan uzun kesinti',
  occurred_at: '2026-10-10T08:00:00', resolved_at: '2026-10-10T09:00:00', severity: 'HIGH', status: 'RESOLVED', category: 'CERTIFICATE',
  team_id: 5, team_name: LONG_TEAM, postmortem_status: 'DRAFT', actions_open: 1, actions_overdue: 1, actions_done: 0, actions_total: 1,
}
const ACTION = {
  id: 11, incident_id: 7, title: 'Sertifika bitişine 30 gün kala alarm kuralı ekle ve envanterde sahibini zorunlu yap', status: 'OPEN',
  kind: 'DETECT', priority: 'HIGH', owner_user_id: 'u-1', owner_name: LONG_PERSON, due_date: '2026-10-01', overdue: true, mine: true,
  incident_title: ROW.title, incident_team_name: LONG_TEAM,
}
const PM = {
  incident_id: 7, can_edit: true, today: '2026-10-11', actions: [ACTION],
  postmortem: { exists: true, status: 'DRAFT', version: 3, summary: 'Ödeme servisi 55 dakika yanıt vermedi.', impact: '', root_cause: 'Sertifika süresi doldu.',
    trigger: '', detection: '', resolution: '', went_well: '', went_wrong: '', lucky: '', lessons: '', updated_by: 'kullanici.a', updated_at: '2026-10-11T08:00:00' },
  timeline: [
    { at: '2026-10-10T08:00:00', kind: 'occurred' },
    { at: '2026-10-10T08:01:00', kind: 'alert_opened', domain: 'odeme-servisleri-uzun-alan-adi.kurumsal.example.com', alert_level: 'CRITICAL' },
    { at: '2026-10-10T08:20:00', kind: 'alert_comment', actor: LONG_PERSON, text: 'Sertifika zinciri yenilendi, CDN önbelleği temizleniyor.' },
  ],
}

let saved = null
let added = null
let actionQueries = []
async function mock(page) {
  saved = null; added = null; actionQueries = []
  await page.addInitScript(() => {
    try { localStorage.setItem('sm.tour', JSON.stringify({ status: 'dismissed', version: 99 })) } catch { /* yoksay */ }
  })
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const req = route.request()
    const url = new URL(req.url())
    const p = url.pathname
    let body = { success: true, data: [] }
    if (p === '/api/me') body = { success: true, username: 'demo', system_role: 'ADMIN', global_admin: true, team_id: 5, team_ids: [5], tour: { status: 'dismissed', version: 99 } }
    else if (p === '/api/me/permissions') body = { success: true, data: { 'incidents.view': { view: true }, 'incidents.manage': { edit: true }, 'incidents.delete': { execute: true } } }
    else if (p === '/api/incidents') body = { success: true, data: [ROW], total: 1, page: 0, size: 20 }
    else if (p === '/api/incidents/trends') body = { success: true, data: { summary: { total: 1, open: 0, resolved: 1 }, by_severity: { HIGH: 1 }, by_status: { RESOLVED: 1 }, daily: [] } }
    else if (p === '/api/incidents/7') body = { success: true, data: ROW }
    else if (p === '/api/incidents/7/postmortem' && req.method() === 'PUT') {
      saved = JSON.parse(req.postData() || '{}')
      body = { success: true, data: { ...PM.postmortem, ...saved, version: 4 } }
    } else if (p === '/api/incidents/7/postmortem') body = { success: true, data: PM }
    else if (p === '/api/incidents/7/actions' && req.method() === 'POST') {
      added = JSON.parse(req.postData() || '{}')
      body = { success: true, data: { id: 12, incident_id: 7, ...added, owner_name: null, overdue: false, mine: false } }
    } else if (p === '/api/incidents/actions') {
      actionQueries.push(Object.fromEntries(url.searchParams))
      body = { success: true, data: { items: [ACTION], total: 1, page: 0, size: 50, totals: { open: 1, overdue: 1, mine: 1 } } }
    } else if (p === '/api/users/directory') body = { success: true, data: [{ id: 'u-1', display_name: LONG_PERSON }, { id: 'u-2', username: 'kullanici.b' }] }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button, select, input, textarea')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0 || b.disabled) continue
    let h = r.height
    const after = getComputedStyle(b, '::after')
    if (after.position === 'absolute' && after.height && after.height !== 'auto') h = Math.max(h, parseFloat(after.height) || 0)
    if (h < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || b.getAttribute('placeholder') || '').trim().slice(0, 30)}" ${Math.round(h)}`)
  }
  return out
}

function clipped(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  return [...root.querySelectorAll('span, p, li, h2, label')].filter((n) => !n.closest('[role="combobox"]'))
    .filter((n) => n.childElementCount === 0 && n.textContent.trim().length > 20
      && (n.scrollWidth > n.clientWidth + 1 || getComputedStyle(n).textOverflow === 'ellipsis')).map((n) => n.textContent.slice(0, 30))
}

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

for (const vp of VIEWPORTS) {
  test.describe(`Postmortem — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('ayrıntıdan pencere: sığar, adlar sarar; rapor kaydı + aksiyon ekleme gövdesi', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page)
      await page.goto('/?tab=incident-history&ih_id=7')
      const open = page.locator('[data-slot="ih-open-postmortem"]')
      await expect(open).toBeVisible({ timeout: 20_000 })
      await expect(page.locator('[data-slot="ih-postmortem"] [data-slot="pm-badge-status"]')).toBeVisible()
      await open.click()
      const modal = page.locator('[data-slot="pm-modal"]')
      await expect(modal.locator('[data-slot="pm-section"]')).toHaveCount(10)
      await page.waitForTimeout(300)
      const dialog = page.getByRole('dialog').last()
      const box = await dialog.boundingBox()
      expect(box.x).toBeGreaterThanOrEqual(-1)
      expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
      expect(await page.evaluate(clipped, '[data-slot="pm-modal"]'), 'kırpılan metin (rapor)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[role="dialog"]:last-of-type [data-slot="pm-modal"]'), 'küçük hedef (rapor)').toEqual([])

      await modal.locator('[data-slot="pm-section"][data-key="lessons"] textarea').fill('Sertifika sahipleri envanterde zorunlu olmalı.')
      await page.locator('[data-slot="pm-save"]').click()
      await expect.poll(() => saved).toEqual({ lessons: 'Sertifika sahipleri envanterde zorunlu olmalı.', version: 3 })

      await page.locator('[data-slot="pm-tab-actions"]').click()
      const row = modal.locator('[data-slot="pm-action"]').first()
      await expect(row).toContainText(LONG_PERSON)
      expect(await page.evaluate(clipped, '[data-slot="pm-modal"]'), 'kırpılan metin (aksiyon)').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="pm-actions"]'), 'küçük hedef (aksiyon)').toEqual([])
      await page.locator('[data-slot="pm-add-action"]').click()
      const form = modal.locator('[data-slot="pm-action-form"]')
      await form.getByLabel(/^(Action item|Aksiyon)/).fill('CDN önbelleği temizleme adımını runbook\'a ekle')
      await form.getByLabel(/^(Priority|Öncelik)$/).selectOption('HIGH')
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="pm-action-form"]'), 'küçük hedef (form)').toEqual([])
      await page.locator('[data-slot="pm-action-submit"]').click()
      await expect.poll(() => added?.title).toBe('CDN önbelleği temizleme adımını runbook\'a ekle')
      expect(added).toMatchObject({ priority: 'HIGH', kind: 'PREVENT', status: 'OPEN', owner_user_id: null, due_date: null })
      await expect(modal.locator('[data-slot="pm-action"]')).toHaveCount(2)

      await page.locator('[data-slot="pm-tab-timeline"]').click()
      await expect(modal.locator('[data-slot="pm-timeline-item"]')).toHaveCount(3)
      expect(await page.evaluate(clipped, '[data-slot="pm-modal"]'), 'kırpılan metin (çizelge)').toEqual([])
      await dialog.screenshot({ path: `test-results/postmortem-${vp.name}.png` })
    })

    test('Aksiyonlar görünümü: taşmaz, adlar sarar, süzgeçler isteğe gider', async ({ page }) => {
      test.setTimeout(120_000)
      await mock(page)
      await page.goto('/?tab=incident-history&ih_view=actions')
      const view = page.locator('[data-slot="pm-actions-view"]')
      await expect(view.locator('[data-slot="pm-action"]')).toHaveCount(1, { timeout: 20_000 })
      await expect(view.locator('[data-slot="pm-action"]')).toContainText(LONG_TEAM)
      await page.waitForTimeout(300)
      expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth), 'yatay taşma').toBeLessThanOrEqual(1)
      expect(await page.evaluate(clipped, '[data-slot="pm-actions-view"]'), 'kırpılan metin').toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, '[data-slot="pm-actions-view"]'), 'küçük hedef').toEqual([])
      await page.locator('[data-slot="pm-filter-mine"]').click()
      await expect.poll(() => actionQueries.at(-1)?.mine).toBe('true')
      await page.locator('[data-slot="pm-kpi"][data-kind="overdue"]').click()
      await expect.poll(() => actionQueries.at(-1)?.overdue).toBe('true')
      await page.screenshot({ path: `test-results/postmortem-actions-${vp.name}.png` })
    })
  })
}
