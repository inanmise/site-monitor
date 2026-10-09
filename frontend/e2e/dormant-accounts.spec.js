// MOBİL WEB — Atıl hesaplar görünümü (2026-10-09 yeniden tasarım). jsdom yerleşim yapmaz; burada gerçek tarayıcıda
// ölçülür: Sistem Sağlığı → Kullanıcı / Oturum → "Atıl hesap" KPI kartı → pencere. Telefon (390×844), tablet (768×1024)
// ve dizüstü (1280×800): sayfa ve pencere düzeyinde yatay taşma YOK, pencere ekrana sığar, telefonda kart listesi +
// süzgeç Sheet'i, geniş ekranda tablo; telefonda dokunma hedefleri ≥ 40 px; kartlarda sol renk şeridi YOK.
// API tamamen mock'lu (yalnız /api/ yolu); adlar ve takımlar yer tutucu, uzun adlar taşmayı zorlamak için bilerek uzun.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'

const DAY = 86_400_000
const ago = (d) => new Date(Date.now() - d * DAY).toISOString().slice(0, 19)
const TEAMS = [
  [1, 'Takım A Mobil Servis ve Uzun Adlı Ekip'], [2, 'Takım B Ödeme Altyapısı'], [3, 'Takım C'], [4, 'Takım D Kurumsal Raporlama Platformu'],
  [5, 'Takım E'], [6, 'Takım F Çağrı Merkezi Uygulamaları'],
]
const ROLES = ['USER', 'USER', 'USER', 'AUDIT', 'TEAM_ADMIN', 'ADMIN']
const DAYS = [31, 45, 64, 95, 130, 185, 220, 300, 370, 420, 700, null, null]

function dormantRows(n = 42) {
  return Array.from({ length: n }, (_, i) => {
    const d = DAYS[i % DAYS.length]
    const team = i % 7 === 6 ? null : TEAMS[i % TEAMS.length]
    return {
      username: `KULLANICI_${String(i + 1).padStart(3, '0')}`,
      user_id: 100 + i,
      display_name: `Örnek Kişi ${i + 1} Uzunsoyadlıoğlu${i % 3 === 0 ? ' (Teknoloji Servis ve Altyapı Bölümü)' : ''}`,
      system_role: ROLES[i % ROLES.length],
      team_id: team ? team[0] : null, team_ids: team ? [team[0]] : [], team_name: team ? team[1] : null,
      auth_source: i % 4 === 0 ? 'LOCAL' : 'LDAP',
      last_login_at: d == null ? null : ago(d), inactive_days: d,
      created_at: ago(d == null ? (i % 2 ? 5 : 260) : d + 200), account_age_days: d == null ? (i % 2 ? 5 : 260) : d + 200,
      has_email: i % 5 !== 0, permanent_lock: i === 7, has_photo: false, last_login_method: d == null ? null : 'LDAP',
    }
  })
}

const DORMANT = dormantRows()
const USER_ACTIVITY = {
  generated_at: ago(0), window_days: 7, office_hours: { start: 8, end: 20 }, identity_masked: false,
  summary: { active_count: 1, logins_24h: 4, failed_24h: 1, anomalies_24h: 0, unique_users_24h: 2, logins_7d: 20, failed_7d: 3, anomalies_7d: 0,
    unique_users_7d: 5, total_users: 180, dormant_30d: DORMANT.length, dormant_90d: 30, never_logged_in: 6, tour: { completed: 3, dismissed: 1, none: 2 } },
  active_users: [], login_status: [], series: { day: [], hour: [] }, top_users: [], top_sources: [],
  anomalies: { total: 0, unacked_recent: 0, counts: {}, recent: [] },
  role_team: { by_role: [], by_team: [] }, heatmaps: [], usage: { days: 7, pages: [], users: [], teams: [] },
  // Yoklanan özet yalnız ilk satırları taşır (gerçekte 500; burada 20) — tam liste pencere açılınca ayrı uçtan gelir
  details: { logins: [], failed: [], anomalies: [], unique_users: [], dormant: DORMANT.slice(0, 20),
    dormant_meta: { total: DORMANT.length, cap: 20, truncated: true, threshold_days: 30 } },
}
const DORMANT_FULL = { rows: DORMANT, meta: { total: DORMANT.length, cap: 5000, truncated: false, threshold_days: 30 }, generated_at: ago(0) }

let dormantCalls = 0
async function mock(page) {
  dormantCalls = 0
  await mockApi(page)   // genel uygulama uçları (oturum, tercihler, menü rozetleri …); aşağıdaki yollar onu EZER
  await page.route((u) => new URL(u).pathname.startsWith('/api/admin/system/user-activity'), async (route) => {
    const p = new URL(route.request().url()).pathname
    let data = USER_ACTIVITY
    if (p.endsWith('/dormant')) {
      dormantCalls += 1
      await new Promise((r) => setTimeout(r, 300))   // ağ gecikmesi: önce özet satırları + "yükleniyor" görünsün
      data = DORMANT_FULL
    } else if (p.endsWith('/series')) data = { buckets: [], granularity: 'day' }
    else if (p.includes('/user/')) data = { username: 'x', logins: 0, failed: 0, distinct_ips: 0, events: [], identity_masked: false }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data }) })
  })
}

async function openDormant(page) {
  await page.goto('/?tab=health&sec=users')
  await page.locator('[data-kpi="dormant"]').click({ timeout: 30_000 })
  await page.locator('[data-slot="dormant-stats"]').waitFor()
  // tam liste gelene kadar özet satırları + "yükleniyor" satırı; gelince satır kalkar ve toplam tam listeden
  await page.locator('[data-slot="dormant-loading"]').waitFor({ state: 'detached', timeout: 10_000 })
  await expect(page.locator('[data-slot="dormant-stats"] [data-stat="total"]')).toContainText(String(DORMANT.length))
  await page.waitForTimeout(300)   // açılış animasyonu (opaklık) bitsin
}

/** Pencere içinde ekranın sağına taşan görünür öğeler (kendi kaydırma kabında kalanlar sayılmaz). */
function offendersIn(sel) {
  const vw = document.documentElement.clientWidth
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    if (r.width <= 1 || r.height <= 1 || r.right <= vw + 1) continue
    let contained = false
    for (let p = el.parentElement; p && p !== root; p = p.parentElement) {
      if (getComputedStyle(p).overflowX !== 'visible' && p.getBoundingClientRect().right <= vw + 1) { contained = true; break }
    }
    if (!contained) out.push(`${el.tagName.toLowerCase()} → ${Math.round(r.right)} "${(el.textContent || '').trim().slice(0, 30)}"`)
    if (out.length >= 6) break
  }
  return out
}

/** Pencere İÇİNDE yatay kaydırma (kasıtlı `truncate` kırpması ve sr-only metin sayılmaz). */
function sideScrollIn(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const el of [root, ...root.querySelectorAll('*')]) {
    const cs = getComputedStyle(el)
    if (cs.overflowX === 'visible' || cs.textOverflow === 'ellipsis') continue
    const r = el.getBoundingClientRect()
    if (r.width <= 1 || r.height <= 1) continue
    if (el.scrollWidth - el.clientWidth > 1) {
      out.push(`${el.tagName.toLowerCase()}[${el.getAttribute('data-slot') || el.getAttribute('data-testid') || ''}] ${el.scrollWidth}>${el.clientWidth}`)
    }
  }
  return out
}

/** Sayfa düzeyinde yatay taşma (belge ve uygulama kabı). */
function pageOverflow() {
  const els = [document.documentElement, document.body, document.querySelector('.app-main')].filter(Boolean)
  return Math.max(...els.map((el) => el.scrollWidth - el.clientWidth))
}

const DIALOG = '[role="dialog"]:has([data-slot="dormant-title"])'

for (const vp of [
  { name: 'phone', width: 390, height: 844, cards: true },
  { name: 'tablet', width: 768, height: 1024, cards: true },
  { name: 'laptop', width: 1280, height: 800, cards: false },
]) {
  test.describe(`Atıl hesaplar — ${vp.name} ${vp.width}`, () => {
    test('pencere ekrana sığar, yatay taşma yok; liste biçimi boya uygun; sol renk şeridi yok', async ({ page }) => {
      await page.setViewportSize({ width: vp.width, height: vp.height })
      await mock(page)
      await openDormant(page)
      const dlg = page.locator(DIALOG)
      await expect(dlg).toBeVisible()

      expect(await page.evaluate(pageOverflow)).toBeLessThanOrEqual(1)
      expect(await page.evaluate(offendersIn, DIALOG)).toEqual([])
      expect(await page.evaluate(sideScrollIn, DIALOG)).toEqual([])
      const box = await dlg.boundingBox()
      expect(box.x).toBeGreaterThanOrEqual(0)
      expect(box.x + box.width).toBeLessThanOrEqual(vp.width + 1)
      expect(box.y + box.height).toBeLessThanOrEqual(vp.height + 1)

      // istatistik kutucukları + dağılım kartları görünür; toplam TAM listeden (özet yalnız 20 satır taşıyordu)
      await expect(dlg.locator('[data-stat="total"]')).toContainText(String(DORMANT.length))
      // tam liste açılışta bir kez (geliştirme sunucusunda React.StrictMode etkiyi iki kez bağlar → en çok 2; ilki sıra
      // korumasıyla yok sayılır); pencere YOKLAMAZ: açık beklerken istek sayısı artmaz
      const afterOpen = dormantCalls
      expect(afterOpen).toBeGreaterThanOrEqual(1)
      expect(afterOpen).toBeLessThanOrEqual(2)
      await page.waitForTimeout(1500)
      expect(dormantCalls).toBe(afterOpen)
      if (vp.width < 768) {
        // telefonda dağılımlar katlı başlar (liste ekranlarca aşağı itilmesin); açılınca da taşma yok
        await expect(dlg.locator('[data-breakdown]')).toHaveCount(0)
        await dlg.locator('[data-slot="stats-toggle"]').click()
        await page.waitForTimeout(300)
        expect(await page.evaluate(offendersIn, DIALOG)).toEqual([])
        expect(await page.evaluate(sideScrollIn, DIALOG)).toEqual([])
      }
      await expect(dlg.locator('[data-breakdown="bucket"]')).toBeVisible()

      if (vp.cards) {
        await expect(dlg.getByTestId('dormant-table')).toHaveCount(0)
        const card = dlg.locator('[data-slot="dormant-card"]').first()
        await card.scrollIntoViewIfNeeded()
        await expect(card).toBeVisible()
        // SOL RENK ŞERİDİ YOK: sol kenar diğer kenarlarla aynı; içe gölge yok
        const edge = await card.evaluate((el) => {
          const cs = getComputedStyle(el)
          const before = getComputedStyle(el, '::before')
          return {
            sameWidth: cs.borderLeftWidth === cs.borderTopWidth && cs.borderLeftWidth === cs.borderRightWidth,
            sameColor: cs.borderLeftColor === cs.borderTopColor && cs.borderLeftColor === cs.borderRightColor,
            inset: /inset/.test(cs.boxShadow),
            before: before.content !== 'none' && before.content !== 'normal' && before.backgroundColor !== 'rgba(0, 0, 0, 0)',
          }
        })
        expect(edge).toEqual({ sameWidth: true, sameColor: true, inset: false, before: false })
      } else {
        const table = dlg.getByTestId('dormant-table')
        await expect(table).toBeVisible()
        await expect(dlg.locator('[data-slot="dormant-card"]')).toHaveCount(0)
        // tablo kabı yatay kaymaz (table-fixed + truncate)
        const scroll = await table.evaluate((el) => { const c = el.closest('[data-slot="table-container"]') || el.parentElement; return c.scrollWidth - c.clientWidth })
        expect(scroll).toBeLessThanOrEqual(1)
      }
    })

    test('süzgeç: arama + faset; çipler sarar, taşma yok; satır kullanıcı detayını açar', async ({ page }) => {
      await page.setViewportSize({ width: vp.width, height: vp.height })
      await mock(page)
      await openDormant(page)
      const dlg = page.locator(DIALOG)
      await dlg.getByRole('searchbox').fill('Örnek Kişi 1')
      if (vp.width < 1024) {
        await dlg.getByRole('button', { name: /^(Süzgeçler|Filters)$/ }).click()
        const sheet = page.locator('[data-slot="dormant-filter-sheet"]')
        await sheet.waitFor()
        // Sheet kenardan kayarak açılır: ölçüm animasyon BİTİNCE (yoksa ara konum ekran dışı görünür)
        await sheet.evaluate((el) => Promise.all(el.getAnimations({ subtree: true }).map((a) => a.finished)))
        const sb = await sheet.boundingBox()
        expect(sb.x).toBeGreaterThanOrEqual(-1)
        expect(sb.x + sb.width).toBeLessThanOrEqual(vp.width + 1)
        await sheet.getByRole('button', { name: /^(LDAP) \(\d+\)$/ }).click()
        await sheet.getByRole('button', { name: /^(USER) \(\d+\)$/ }).click()
        await sheet.getByRole('button', { name: /(Sonuçları göster|Show results)/ }).click()
        await sheet.waitFor({ state: 'detached' })
      } else {
        await dlg.getByRole('group', { name: /^(Süzgeçler|Filters)$/ }).getByRole('button', { name: /(Kimlik kaynağı|Identity source)/ }).click()
        await page.getByRole('option', { name: /^LDAP/ }).click()
        await page.keyboard.press('Escape')
      }
      const chips = dlg.locator('[data-slot="dormant-chips"]')
      await expect(chips).toBeVisible()
      await page.waitForTimeout(300)
      expect(await page.evaluate(offendersIn, DIALOG)).toEqual([])
      expect(await page.evaluate(sideScrollIn, DIALOG)).toEqual([])
      expect(await page.evaluate(pageOverflow)).toBeLessThanOrEqual(1)

      // ilk satır / kart → mevcut oturum / kullanıcı detayı penceresi
      const first = vp.cards ? dlg.locator('[data-slot="dormant-card"]').first() : dlg.locator('tbody tr').first()
      const name = await first.getAttribute('data-user')
      await first.locator('[data-part="name"]').click()
      await expect(page.getByRole('dialog', { name })).toBeVisible()
    })

    if (vp.name === 'phone') {
      test('telefonda dokunma hedefleri ≥ 40 px (kutucuk, dağılım satırı, araç çubuğu, çip, sonraki adım, alt çubuk)', async ({ page }) => {
        await page.setViewportSize({ width: vp.width, height: vp.height })
        await mock(page)
        await openDormant(page)
        const dlg = page.locator(DIALOG)
        // telefonda dağılımlar kapalı başlar → aç, bir dağılım satırıyla süz (çip oluşsun)
        await expect(dlg.locator('[data-breakdown]')).toHaveCount(0)
        await dlg.locator('[data-slot="stats-toggle"]').click()
        await dlg.locator('[data-row="source-LDAP"]').click()
        const sizes = await page.evaluate((sel) => {
          const root = document.querySelector(sel)
          const pick = [
            '[data-stat]', '[data-row]', '[data-slot="stats-toggle"]', '[data-slot="dormant-toolbar"] button', '[data-slot="dormant-toolbar"] [data-slot="input-group"]',
            '[data-slot="dormant-chips"] button', '[data-slot="dormant-next"] button', '[data-slot="dialog-footer"] button',
            '[data-slot="dormant-card"]', '[data-slot="dormant-sort"]',
          ]
          const out = []
          for (const s of pick) {
            for (const el of root.querySelectorAll(s)) {
              const r = el.getBoundingClientRect()
              if (r.width <= 1 || r.height <= 1) continue
              if (r.height < 39.5) out.push(`${s} h=${Math.round(r.height)} "${(el.textContent || '').trim().slice(0, 20)}"`)
            }
          }
          return out
        }, DIALOG)
        expect(sizes).toEqual([])
      })
    }
  })
}
