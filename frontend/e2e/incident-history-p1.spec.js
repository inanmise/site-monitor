// Olay & Hata Geçmişi — "Sağlam Kayıt" paket 1 (2026-11-20). Gerçek tarayıcıda 390 (dokunmatik) / 768 (dokunmatik) / 1280:
//  1) düzenleme liste satırıyla açılır, kaydın son hâli arkada okunur ve (yazılmamışsa) formun yerine geçer + not,
//  2) kayıt yalnız DEĞİŞEN alanları + sürümü gönderir; arada başkası kaydettiyse 409 → çakışma bandı (kim / ne zaman),
//     "Son hâli yükle" yazılanları yerel taslağa koyar, taslak geri yüklenip yeni sürümle kaydedilir,
//  3) durum ↔ zaman önerisi (engellemez), yerel taslak sayfa yenilense de kalır ve kendiliğinden uygulanmaz.
// Bantlar ekrana sığar, metin kırpılmaz, dokunmatik hedefler ≥ 40 px, sayfa yatay kaymaz. API mock'lu (sunucu sürüm
// kuralını taklit eder), saat sabit.
import { test, expect } from '@playwright/test'

const NOW = new Date('2026-11-20T09:00:00Z')
const TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi'
const BASE = Object.freeze({
  id: 9, version: 1, title: 'Ödeme servisinde gecikme — kart işlemleri yavaş yanıt veriyor',
  occurred_at: '2026-11-19T07:00:00', detected_at: '2026-11-19T07:10:00', resolved_at: null,
  severity: 'HIGH', status: 'OPEN', category: 'APPLICATION', team_id: 5, team_name: TEAM,
  error_code: '', function_code: '', channel_code: '', service: '', channel: '', rca_summary: '', description: '',
  resolution_steps: '', business_impact: '', affected_services: '', problem_types: '', affected_app: '', affected_systems: '',
  affected_customers: null, affected_transactions: null, sla_breached: false, error_budget_burn_pct: null,
  duration_minutes: null, runbook_url: null, tags: '', tech_error_count: null, outage_related: null, outage_id: null,
  created_by: 'demo', created_at: '2026-11-19T07:20:00', updated_by: 'demo', updated_at: '2026-11-19T07:20:00',
  can_edit: true,
})

let server = null          // sunucudaki kaydın güncel hâli (GET /api/incidents/9)
let listRow = null         // listenin döndürdüğü satır (liste açıldıktan sonra kayıt değişebilir)
let puts = []

async function mock(page) {
  server = { ...BASE }
  listRow = { ...BASE }
  puts = []
  await page.addInitScript(() => {
    try {
      localStorage.setItem('sm.tour', JSON.stringify({ status: 'dismissed', version: 99 }))
      localStorage.setItem('site-monitor-lang', 'tr')
    } catch { /* yoksay */ }
  })
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const req = route.request()
    const p = new URL(req.url()).pathname
    let status = 200
    let body = { success: true, data: [] }
    if (p === '/api/me') body = { success: true, username: 'demo', system_role: 'ADMIN', global_admin: true, team_id: 5, team_ids: [5], tour: { status: 'dismissed', version: 99 } }
    else if (p === '/api/me/permissions') body = { success: true, data: { 'incidents.view': { view: true }, 'incidents.manage': { edit: true } } }
    else if (p === '/api/admin/teams') body = { success: true, data: [{ id: 5, name: TEAM }] }
    else if (p === '/api/incidents') body = { success: true, data: [listRow], total: 1, page: 0, size: 20 }
    else if (p === '/api/incidents/trends') body = { success: true, data: { summary: { total: 1, open: 1, resolved: 0 }, by_severity: {}, by_status: {}, daily: [] } }
    else if (p === '/api/incidents/9' && req.method() === 'GET') body = { success: true, data: server }
    else if (p === '/api/incidents/9' && req.method() === 'PUT') {
      const sent = JSON.parse(req.postData() || '{}')
      puts.push(sent)
      if (sent.version != null && sent.version !== server.version) {
        status = 409
        body = {
          success: false, code: 'CONCURRENT_MODIFICATION',
          error: `Kayıt siz düzenlerken ${server.updated_by} tarafından değiştirildi; değişikliğiniz kaydedilmedi.`,
          current_version: server.version, updated_by: server.updated_by, updated_at: server.updated_at,
        }
      } else {
        const { id, version, send_notification, ...fields } = sent   // eslint-disable-line no-unused-vars
        server = { ...server, ...fields, version: server.version + 1, updated_by: 'demo', updated_at: '2026-11-20T09:00:00' }
        listRow = { ...server }
        body = { success: true, data: server }
      }
    }
    await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

/** Görünür etkileşimli öğelerden 40 px'in altında kalanlar (dokunmatik): yükseklik, ya da ::after vuruş alanı. */
function smallTargets(root) {
  const out = []
  for (const b of root.querySelectorAll('button, select, input')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0 || b.disabled) continue
    let h = r.height
    const after = getComputedStyle(b, '::after')
    if (after.position === 'absolute' && after.height && after.height !== 'auto') h = Math.max(h, parseFloat(after.height) || 0)
    if (h < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || '').trim().slice(0, 30)}" ${Math.round(h)}`)
  }
  return out
}

/** Kırpılan / taşan metin (tek düğümlü yaprak öğeler); görünmez öğeler (≤ 2 px) sayılmaz. */
function clipped(root) {
  return [...root.querySelectorAll('span, p, label, button, div')]
    .filter((n) => { const r = n.getBoundingClientRect(); return r.width > 2 && r.height > 2 })
    .filter((n) => n.childElementCount === 0 && n.textContent.trim().length > 3
      && (n.scrollWidth > n.clientWidth + 1 || getComputedStyle(n).textOverflow === 'ellipsis'))
    .map((n) => n.textContent.slice(0, 40))
}

/** Öğenin içinden sağa taşan alt öğeler. */
function overflow(el) {
  const lim = el.getBoundingClientRect().right + 1
  if (el.scrollWidth <= el.clientWidth + 1) return []
  return [...el.querySelectorAll('*')].filter((n) => n.getBoundingClientRect().right > lim)
    .map((n) => `${n.tagName}.${(n.getAttribute('data-slot') || n.className || '').toString().slice(0, 60)} ${Math.round(n.getBoundingClientRect().right)}>${Math.round(lim)}`).slice(0, 8)
}

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]

for (const vp of VIEWPORTS) {
  test.describe(`Olay kaydı sağlam kayıt — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch, timezoneId: 'Europe/Istanbul' })

    /** Bant / bölgenin yerleşimi: taşma yok, kırpılma yok, dokunmatikte ≥ 40 px, ekran içinde; sayfa yatay kaymaz. */
    async function checkLayout(page, loc, label) {
      await expect(loc, label).toBeVisible()
      expect(await loc.evaluate(overflow), label).toEqual([])
      expect(await loc.evaluate(clipped), label).toEqual([])
      if (vp.touch) expect(await loc.evaluate(smallTargets), label).toEqual([])
      const b = await loc.boundingBox()
      expect(b.x, label).toBeGreaterThanOrEqual(0)
      expect(b.x + b.width, label).toBeLessThanOrEqual(vp.width + 0.5)
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1), label).toBe(true)
    }

    // Satırın "<başlık> — Düzenle" düğmesi (tek satır; başlık kayıttan sonra değişir)
    const editFromList = (page) => page.getByRole('button', { name: / — Düzenle$/ }).first().click()

    test('taze kayıt formun yerine geçer; yalnız değişenler + sürüm; 409 çakışma → son hâl + taslak geri yükleme', async ({ page }) => {
      test.setTimeout(120_000)
      await page.clock.setFixedTime(NOW)
      await mock(page)
      await page.goto('/?tab=incident-history')
      await expect(page.getByText(BASE.title).first()).toBeVisible()

      // 1) Liste açıldıktan sonra başkası kaydetti (sürüm 2: başlık + önem) → form taze hâli gösterir, not "2 alan"
      server = { ...server, version: 2, title: `${BASE.title} (güncel)`, severity: 'CRITICAL', updated_by: 'Ayşe Yılmaz', updated_at: '2026-11-20T08:30:00' }
      await editFromList(page)
      const dlg = page.getByRole('dialog', { name: /Olayı Düzenle/ })
      const title = dlg.getByRole('textbox', { name: /^Başlık/ })
      await expect(title).toHaveValue(server.title)
      const updated = dlg.locator('[data-slot="ih-fresh-note"][data-kind="updated"]')
      await expect(updated).toContainText('en son hâli yüklendi (2 alan farklıydı)')
      await checkLayout(page, dlg.locator('[data-slot="ih-form-notes"]'), 'taze kayıt notu')

      // 2) Bu arada başkası yine kaydetti (sürüm 3); kullanıcı başlığı değiştirip kaydeder → 409, çakışma bandı
      server = { ...server, version: 3, description: 'Ayşe tarafından eklenen açıklama', updated_by: 'Ayşe Yılmaz', updated_at: '2026-11-20T08:45:00' }
      const MINE = 'Ödeme servisinde gecikme — benim düzeltmem (çok uzun bir başlık, satır sonuna kadar taşar mı diye)'
      await title.fill(MINE)
      await dlg.locator('[data-action="save"]').click()
      const conflict = dlg.locator('[data-slot="ih-conflict"]')
      await expect(conflict).toContainText('Ayşe Yılmaz')
      await expect(conflict).toContainText('değişikliğiniz kaydedilmedi')
      expect(puts).toHaveLength(1)
      expect(puts[0]).toEqual({ id: 9, version: 2, title: MINE })               // yalnız değişen alan + sürüm
      await expect(dlg.locator('[data-action="save"]')).toBeDisabled()
      await expect(title).toHaveValue(MINE)                                     // yazılan kaybolmaz
      await checkLayout(page, conflict, 'çakışma bandı')

      // 3) "Son hâli yükle": yazılan taslağa, son hâl açılır (sürüm 3), taslak önerilir → geri yükle → sürüm 3 ile kaydet
      await conflict.getByRole('button', { name: 'Son hâli yükle' }).click()
      await expect(conflict).toHaveCount(0)
      await expect(title).toHaveValue(server.title)
      const draft = dlg.locator('[data-slot="ih-draft"]')
      await expect(draft).toContainText('Kaydedilmemiş bir taslağınız var')
      await expect(draft.locator('[data-slot="ih-draft-older"]')).toBeVisible()
      await checkLayout(page, draft, 'taslak bandı')
      await draft.getByRole('button', { name: 'Taslağı geri yükle' }).click()
      await expect(title).toHaveValue(MINE)
      await dlg.locator('[data-action="save"]').click()
      await expect(page.getByRole('dialog', { name: /Olayı Düzenle/ })).toHaveCount(0)
      expect(puts).toHaveLength(2)
      expect(puts[1]).toEqual({ id: 9, version: 3, title: MINE })
      expect(server.version).toBe(4)
      expect(server.description).toBe('Ayşe tarafından eklenen açıklama')       // başkasının değişikliği ezilmedi

      // 4) Değişiklik yoksa istek gitmez
      await editFromList(page)
      const again = page.getByRole('dialog', { name: /Olayı Düzenle/ })
      await expect(again.getByRole('textbox', { name: /^Başlık/ })).toHaveValue(MINE)
      await again.locator('[data-action="save"]').click()
      await expect(page.getByRole('dialog', { name: /Olayı Düzenle/ })).toHaveCount(0)
      expect(puts).toHaveLength(2)
    })

    test('durum ↔ zaman önerisi engellemez; yerel taslak sayfa yenilense de kalır ve kendiliğinden uygulanmaz', async ({ page }) => {
      test.setTimeout(120_000)
      await page.clock.setFixedTime(NOW)
      await mock(page)
      await page.goto('/?tab=incident-history')
      await editFromList(page)
      const dlg = page.getByRole('dialog', { name: /Olayı Düzenle/ })
      await expect(dlg.getByRole('textbox', { name: /^Başlık/ })).toHaveValue(BASE.title)

      // 1) Durum "Çözüldü", çözülme zamanı boş → öneri; uygulanınca zaman yazılır
      await dlg.getByRole('combobox', { name: /^Durum/ }).selectOption('RESOLVED')
      const nudge = dlg.locator('[data-slot="ih-nudge"][data-kind="setResolvedNow"]')
      await expect(nudge).toContainText('Durum “Çözüldü” ama çözülme zamanı boş.')
      await checkLayout(page, nudge, 'öneri bandı')
      await nudge.getByRole('button', { name: 'Çözülme zamanını şimdi yap' }).click()
      await expect(nudge).toHaveCount(0)
      await expect(dlg.getByRole('textbox', { name: /^Çözülme Zamanı/ })).toHaveValue('20.11.2026 12:00')

      // 2) Taslak ~1 sn sonra yazılır; sayfa yenilenir → aynı kayıt açılınca öneri bandı (form kendiliğinden değişmez)
      await expect.poll(() => page.evaluate(() => Object.keys(localStorage).filter((k) => k.startsWith('sm.ih.draft.9')).length)).toBe(1)
      await page.reload()
      await editFromList(page)
      const dlg2 = page.getByRole('dialog', { name: /Olayı Düzenle/ })
      const draft = dlg2.locator('[data-slot="ih-draft"]')
      await expect(draft).toContainText('Kaydedilmemiş bir taslağınız var')
      await expect(dlg2.getByRole('combobox', { name: /^Durum/ })).toHaveValue('OPEN')
      await checkLayout(page, draft, 'taslak bandı (yenileme sonrası)')
      await draft.getByRole('button', { name: 'Taslağı geri yükle' }).click()
      await expect(dlg2.getByRole('combobox', { name: /^Durum/ })).toHaveValue('RESOLVED')
      await dlg2.locator('[data-action="save"]').click()
      await expect(page.getByRole('dialog', { name: /Olayı Düzenle/ })).toHaveCount(0)
      expect(puts).toHaveLength(1)
      expect(puts[0]).toMatchObject({ id: 9, version: 1, status: 'RESOLVED', resolved_at: '2026-11-20T09:00:00' })
      expect(puts[0]).not.toHaveProperty('duration_minutes')                    // iki zaman var → sunucu hesaplar
      expect(Object.keys(puts[0]).sort()).toEqual(['id', 'resolved_at', 'status', 'version'])
      expect(await page.evaluate(() => Object.keys(localStorage).filter((k) => k.startsWith('sm.ih.draft.9')).length)).toBe(0)
    })
  })
}
