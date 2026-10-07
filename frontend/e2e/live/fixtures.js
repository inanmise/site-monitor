// CANLI e2e ortak parçaları (2026-10-07): kapı, gerçek giriş formu, oturumlu API çağrıları, yerleşim ölçümü, saatlik tarama
// koruması ve `e2e-live-*` kayıt temizliği. Route mock'u YOK — her istek Vite proxy'si üzerinden yerel backend'e gider.
import fs from 'node:fs'
import { test as base, expect } from '@playwright/test'
import { LIVE_BASE_URL, LIVE_STATE } from './state-path.js'

export { expect, LIVE_BASE_URL, LIVE_STATE }

export const LIVE = process.env.E2E_LIVE === '1'
/** Canlı koşunun oluşturduğu her kaydın öneki — temizlik YALNIZ bunlara dokunur. */
export const LIVE_PREFIX = 'e2e-live-'

export const test = base.extend({
  /** Yalnız playwright.live.config.js true yapar: varsayılan yapılandırmada (paralel işçiler) canlı testler koşmaz. */
  liveMode: [false, { option: true }],
  // İkinci argüman Playwright'ın fikstür sağlayıcısı (`use`); ad bilinçli farklı — react-hooks/rules-of-hooks onu React'in
  // use() kancası sanıp hata veriyordu.
  page: async ({ page }, provide, testInfo) => {
    // Ürün turu kapalı (ilk açılışta tıklamaları örtmesin); arayüz Türkçe (metin denetimleri TR sözlüğüne göre)
    await page.addInitScript(() => {
      try {
        localStorage.setItem('sm.tour', JSON.stringify({ status: 'dismissed', version: 99 }))
        localStorage.setItem('site-monitor-lang', 'tr')
      } catch { /* depolama yok */ }
    })
    // Entegrasyon hatası yakalayıcı: yakalanmamış istisna ve /api 5xx testi DÜŞÜRÜR; konsol hataları rapora yazılır
    const problems = []
    const consoleErrors = new Set()
    page.on('pageerror', (e) => problems.push(`pageerror: ${String(e?.message || e).slice(0, 300)}`))
    page.on('response', (r) => {
      const u = new URL(r.url())
      if (u.pathname.startsWith('/api/') && r.status() >= 500) problems.push(`HTTP ${r.status()} ${r.request().method()} ${u.pathname}`)
    })
    page.on('console', (m) => {
      if (m.type() !== 'error') return
      const text = m.text()
      if (/Failed to load resource: the server responded with a status of (4\d\d)/.test(text)) return   // beklenen 4xx (409/400 senaryoları)
      consoleErrors.add(text.slice(0, 300))
    })
    await provide(page)
    if (consoleErrors.size) console.log(`[live] konsol hataları — ${testInfo.title}:\n  ${[...consoleErrors].slice(0, 10).join('\n  ')}`)
    if (testInfo.status === testInfo.expectedStatus) expect(problems, 'çalışma zamanı istisnası / sunucu 5xx').toEqual([])
  },
})

/**
 * Görünür metinde sözleşme kırığı imzaları: çevrilmemiş i18n anahtarı, doldurulmamış yer tutucu, `undefined` / `NaN` /
 * `[object Object]`. Sunucu alan adı değişince arayüz çoğu zaman tam bunları basar.
 */
export async function expectCleanText(locator, label) {
  const text = await locator.innerText()
  const bad = []
  const key = text.match(/\b(ndx|httpdx|pgdx|psdx|kwdx|kwfail|kwhint|mcert|chkhist|chkfail|card|inv|hlth|modal)\.[a-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)*\b/g)
  if (key) bad.push(`i18n anahtarı: ${[...new Set(key)].slice(0, 5).join(', ')}`)
  const ph = text.match(/\{(\d+|[a-z_]+)\}/g)
  if (ph) bad.push(`yer tutucu: ${[...new Set(ph)].slice(0, 5).join(', ')}`)
  for (const w of ['undefined', 'NaN', '[object Object]', 'Invalid Date']) if (text.includes(w)) bad.push(w)
  expect(bad, `${label}: görünür metinde sözleşme kırığı`).toEqual([])
}

/** describe gövdesinin başında çağrılır: E2E_LIVE=1 + canlı yapılandırma yoksa grubun tamamı atlanır. */
export function liveGate() {
  test.skip(!LIVE, 'Canlı suite kapalı: E2E_LIVE=1 + yerel backend (http://localhost:8080) gerekir — playwright.live.config.js')
  test.skip(({ liveMode }) => !liveMode,
    'Canlı suite yalnız `-c playwright.live.config.js` ile koşar (tek aktif oturum: paralel işçiler birbirinin oturumunu düşürür)')
}

export const APP_SHELL = '.app-main'

/**
 * GERÇEK giriş formu: kullanıcı adı + parola → Giriş. Başka yerde canlı oturum varsa sunucu 409 döner ve form "Diğer oturumu
 * kapat" onayını gösterir (role=alertdialog) — onaylanır. Kimlik bilgileri YALNIZ ortamdan (E2E_USER / E2E_PASS) okunur,
 * hiçbir yere yazılmaz.
 */
export async function loginViaForm(page) {
  const user = process.env.E2E_USER
  const pass = process.env.E2E_PASS
  if (!user || !pass) throw new Error('E2E_USER / E2E_PASS ortam değişkenleri tanımlı değil')
  await page.goto('/')
  const userBox = page.locator('#lp-user')
  const shell = page.locator(APP_SHELL)
  await expect(userBox.or(shell).first()).toBeVisible({ timeout: 30_000 })
  if (await shell.isVisible()) return { already: true, forced: false }
  await userBox.fill(user)
  await page.locator('#lp-pass').fill(pass)
  const isLogin = (r) => new URL(r.url()).pathname === '/api/login' && r.request().method() === 'POST'
  const login = page.waitForResponse(isLogin, { timeout: 30_000 })
  await page.locator('form.lp-form button[type="submit"]').click()
  const first = await login
  let forced = false
  if (first.status() === 409) {
    // "Başka yerde aktif oturum" onayı — diğer oturumu kapatıp gir
    const confirm = page.getByRole('alertdialog')
    await confirm.waitFor({ timeout: 15_000 })
    const again = page.waitForResponse(isLogin, { timeout: 30_000 })
    await confirm.getByRole('button').first().click()
    const second = await again
    forced = true
    expect(second.status(), 'zorla giriş (force_login) başarısız').toBe(200)
  } else if (first.status() !== 200) {
    const msg = await page.locator('form.lp-form [role="alert"]').first().textContent({ timeout: 2_000 }).catch(() => '')
    throw new Error(`Giriş başarısız: HTTP ${first.status()} ${msg || ''}`.trim())
  }
  await expect(shell).toBeVisible({ timeout: 30_000 })
  return { already: false, forced }
}

/** Uygulama içi bir adres: kabuk görünmeli; giriş formu çıkarsa oturum düşmüş demektir (net hata). */
export async function openApp(page, url) {
  await page.goto(url)
  const shell = page.locator(APP_SHELL)
  const userBox = page.locator('#lp-user')
  await expect(shell.or(userBox).first()).toBeVisible({ timeout: 30_000 })
  if (await userBox.isVisible()) throw new Error(`Oturum yok (giriş formu göründü): ${url} — live-setup koştu mu?`)
}

/** Oturumlu API çağrısı (sayfanın / bağlamın çerezleriyle). Gövde JSON değilse `json` null. */
export async function apiCall(request, method, path, data) {
  const res = await request.fetch(path, {
    method,
    headers: { 'X-Lang': 'tr', ...(data !== undefined ? { 'Content-Type': 'application/json' } : {}) },
    data: data !== undefined ? JSON.stringify(data) : undefined,
  })
  let json = null
  try { json = await res.json() } catch { json = null }
  return { status: res.status(), json }
}
export const apiGet = (request, path) => apiCall(request, 'GET', path)

/** Canlı oturumun çerezleriyle bağımsız bir istek bağlamı (beforeAll/afterAll — sayfa fikstürü yokken). */
export async function liveRequest(playwright) {
  if (!fs.existsSync(LIVE_STATE)) throw new Error(`Oturum dosyası yok: ${LIVE_STATE}`)
  return playwright.request.newContext({ baseURL: LIVE_BASE_URL, storageState: LIVE_STATE })
}

/**
 * Saatlik sertifika taraması (cron `0 0 * * * *`) 20 günlük test sertifikası için GERÇEK bir alarm açardı. Saat başına
 * `marginMin` dakikadan az kaldıysa tarama geçene dek (saat başı + 2 dk) beklenir; dönüş = beklenen ms.
 */
export async function waitPastHourlySweep(marginMin = 12) {
  const now = new Date()
  const left = (60 - now.getMinutes()) * 60_000 - now.getSeconds() * 1000 - now.getMilliseconds()
  if (left > marginMin * 60_000) return 0
  const wait = left + 2 * 60_000
  console.log(`[live] saatlik taramaya ${Math.round(left / 1000)} sn var — ${Math.round(wait / 1000)} sn bekleniyor`)
  await new Promise((r) => setTimeout(r, wait))
  return wait
}

/** `e2e-live-*` envanter kayıtları (silinmişler dahil). */
export async function liveInventoryRows(request) {
  const r = await apiGet(request, '/api/admin/inventory?showDeleted=true&scope=all')
  if (r.status !== 200) throw new Error(`envanter listesi okunamadı: HTTP ${r.status}`)
  const rows = Array.isArray(r.json?.data) ? r.json.data : []
  return rows.filter((x) => String(x.domain || '').startsWith(LIVE_PREFIX))
}

/**
 * Canlı koşunun bıraktığı HER `e2e-live-*` kaydı: önce yumuşak silme (silinmemişse), sonra kalıcı silme. Kalan sayısı döner.
 * Ürünün kendi uçları (envanter çöp kutusu akışı) — veritabanına doğrudan dokunulmaz.
 */
export async function purgeLiveRecords(request, log = () => {}) {
  const rows = await liveInventoryRows(request)
  for (const row of rows) {
    if (!(row.deleted_at || row.deletedAt)) {
      const d = await apiCall(request, 'DELETE', `/api/admin/inventory/${row.id}`)
      log(`sil #${row.id} ${row.domain} → ${d.status}`)
    }
    const p = await apiCall(request, 'DELETE', `/api/admin/inventory/${row.id}/permanent`)
    log(`kalıcı sil #${row.id} ${row.domain} → ${p.status}`)
  }
  return (await liveInventoryRows(request)).length
}

/** Tarayıcıda: `sel` kökü içinde görünüm alanının sağına taşan en dıştaki görünür öğeler (kendi kaydırma kabı dışında). */
export function measure(sel) {
  const vw = document.documentElement.clientWidth
  const root = (sel && document.querySelector(sel)) || document.body
  const pageOverflow = Math.max(document.documentElement.scrollWidth - document.documentElement.clientWidth,
    document.body.scrollWidth - document.body.clientWidth)
  const clips = (el) => getComputedStyle(el).overflowX !== 'visible'
  const hidden = (el, r) => {
    if (r.width <= 1 || r.height <= 1) return true
    const cs = getComputedStyle(el)
    return cs.visibility === 'hidden' || cs.display === 'none' || Number(cs.opacity) === 0
  }
  const offenders = []
  const flagged = new Set()
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    if (r.right <= vw + 1 || hidden(el, r)) continue
    let contained = false
    for (let p = el.parentElement; p && p !== root; p = p.parentElement) {
      if (clips(p) && p.getBoundingClientRect().right <= vw + 1) { contained = true; break }
    }
    if (contained) continue
    flagged.add(el)
    if (el.parentElement && flagged.has(el.parentElement)) continue
    const slot = el.getAttribute('data-slot')
    offenders.push({ el: `${el.tagName.toLowerCase()}${slot ? `[data-slot=${slot}]` : ''}`, right: Math.round(r.right),
      text: (el.textContent || '').trim().slice(0, 40) })
    if (offenders.length >= 8) break
  }
  return { vw, pageOverflow, offenders }
}

/** Sayfa (ya da `sel` kökü) yatay kaymaz ve görünür hiçbir öğe ekran dışına taşmaz. */
export async function expectFits(page, sel, label) {
  await page.waitForTimeout(350)
  const m = await page.evaluate(measure, sel)
  expect(m.offenders, `${label}: ekran dışına taşan öğe`).toEqual([])
  expect(m.pageOverflow, `${label}: yatay kayma (px)`).toBeLessThanOrEqual(1)
}

/** Pencere görünüm alanına sığar, gövdesi yatay kaymaz, içinde taşan öğe yok. */
export async function expectDialogFits(page, dialog, vp, label) {
  await dialog.evaluate((el) => el.setAttribute('data-e2e-measure', '1'))
  try {
    await expectFits(page, '[data-e2e-measure="1"]', label)
  } finally {
    await dialog.evaluate((el) => el.removeAttribute('data-e2e-measure')).catch(() => {})
  }
  const box = await dialog.boundingBox()
  expect(box.x, `${label}: solda taşıyor`).toBeGreaterThanOrEqual(-1)
  expect(box.x + box.width, `${label}: sağda taşıyor`).toBeLessThanOrEqual(vp.width + 1)
  expect(box.y + box.height, `${label}: altta taşıyor`).toBeLessThanOrEqual(vp.height + 1)
  const body = dialog.locator('[data-slot="modal-shell-body"]').first()
  if (await body.count()) {
    const h = await body.evaluate((el) => el.scrollWidth - el.clientWidth)
    expect(h, `${label}: pencere gövdesi yatay kayıyor (px)`).toBeLessThanOrEqual(1)
  }
}

/** Basit süre ölçer — rapor için her senaryonun süresi konsola. */
export function stopwatch(label) {
  const t0 = Date.now()
  return () => console.log(`[live] ${label}: ${((Date.now() - t0) / 1000).toFixed(1)} sn`)
}
