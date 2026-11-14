// Kaynak analizi → GÖRSEL ARACI (2026-11-14, kullanıcı isteği: "kaynak analizinin bulduğu görselleri özgün / optimize /
// yeniden boyutlandırılmış indir; görsel de yüklenebilsin; önce / sonra karşılaştır; not ne olurdu? — shadcn, mweb").
// Telefon (390, dokunmatik), tablet (768, dokunmatik) ve dizüstünde (1280): yeni "Görsel aracı" bölümü (boş, sırada
// bekleyen, işlenmiş, başarısız), tarama görselinin "İndir" menüsü, toplu indirme (ZIP), önce / sonra karşılaştırma
// penceresi, duyarlı set penceresi ve "not ne olurdu?" kartı. Her aşamada: ekran dışına taşan öğe yok, sayfa / pencere
// gövdesi yatay kaymaz, metin kırpılmaz, dokunmatikte hedefler ≥ 40 px.
//
// API tamamen taklit (sunucu paketi ayrı gelir — sözleşmedeki tel biçimi). GÖRSEL MOTORU da ayrı pakettir: yerleşim
// senaryoları motor modülünün yerine KÜÇÜK BİR TAKLİT motor koyar (Vite geliştirme sunucusunun modül isteği yakalanır) —
// böylece ekran her durumda (işlendi / başarısız) motordan bağımsız sınanır. Gerçek motora ihtiyaç duyan senaryolar
// (gerçek sıkıştırma kazancı, hedef boyut, WebP) dosyanın sonunda `test.fixme` ile işaretlidir: "engine not merged".
import { readFileSync } from 'node:fs'
import { deflateSync } from 'node:zlib'
import { unzipSync, strFromU8 } from 'fflate'
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'
import { scanDetail, scanHistory, scanItems } from '../src/test/helpers/resourceScanFixtures.js'

const VIEWPORTS = [
  { name: 'phone', width: 390, height: 844, touch: true },
  { name: 'tablet', width: 768, height: 1024, touch: true },
  { name: 'laptop', width: 1280, height: 800, touch: false },
]
const DIALOG = '[role="dialog"]:has([data-slot="prs-root"])'
const ROOT = '[data-slot="prs-root"]'
const CMP = '[role="dialog"]:has([data-slot="prs-it-compare-dialog"])'
const RS = '[role="dialog"]:has([data-slot="prs-it-rs"])'
const BULK = '[role="dialog"]:has([data-slot="prs-it-bulk-dialog"])'
const CUSTOM = '[role="dialog"]:has([data-slot="prs-it-custom"])'
const json = (body, status = 200, headers = {}) => (route) => route.fulfill({ status, contentType: 'application/json', headers, body: JSON.stringify(body) })
const PATH = (re) => (u) => re.test(new URL(u).pathname)
const BIG = scanItems()[1]

// ── Sınama görseli: 1200×800 PNG (8 pikselli renk blokları — PNG'si iri, JPEG'i küçük çıkar) ───────────────
const CRC = (() => { const t = new Uint32Array(256); for (let n = 0; n < 256; n += 1) { let c = n; for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0 } return t })()
function crc32(buf) { let c = 0xffffffff; for (let i = 0; i < buf.length; i += 1) c = CRC[(c ^ buf[i]) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0 }
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length)
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data])
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body))
  return Buffer.concat([len, body, crc])
}
function makePng(width, height, seed = 7) {
  let s = seed
  const rnd = () => { s = (s * 1103515245 + 12345) & 0x7fffffff; return s & 0xff }
  const bw = Math.ceil(width / 8)
  const blocks = Buffer.alloc(bw * Math.ceil(height / 8) * 3)
  for (let i = 0; i < blocks.length; i += 1) blocks[i] = rnd()
  const raw = Buffer.alloc(height * (1 + width * 3))
  for (let y = 0; y < height; y += 1) {
    const row = y * (1 + width * 3)
    for (let x = 0; x < width; x += 1) {
      const b = ((y >> 3) * bw + (x >> 3)) * 3
      raw[row + 1 + x * 3] = blocks[b]; raw[row + 2 + x * 3] = blocks[b + 1]; raw[row + 3 + x * 3] = blocks[b + 2]
    }
  }
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(width, 0); ihdr.writeUInt32BE(height, 4); ihdr[8] = 8; ihdr[9] = 2
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw, { level: 6 })), chunk('IEND', Buffer.alloc(0))])
}
const PNG = makePng(1200, 800)
const PNG_SMALL = makePng(640, 400, 11)

// ── Taklit motor (sözleşme §2 arayüzü; tarayıcının kendi tuvaliyle yeniden kodlar) ───────────────────────────
const fakeEngine = (mode) => `
export const ENGINE_LIMITS = Object.freeze({ maxInputBytes: 25 * 1024 * 1024, maxPixels: 50000000, maxSide: 30000 })
export const OUTPUT_FORMATS = Object.freeze(['keep', 'jpeg', 'png', 'webp', 'smallest'])
export const RESIZE_METHODS = Object.freeze(['none', 'scale', 'fit', 'cover', 'thumb'])
export const ERROR_CODES = Object.freeze(['UNSUPPORTED_FORMAT', 'TOO_LARGE', 'TOO_MANY_PIXELS', 'DECODE_FAILED', 'BROWSER_UNSUPPORTED', 'ENCODE_FAILED', 'CANCELLED', 'INVALID_OPTIONS'])
export const NOTE_CODES = Object.freeze(['KEPT_ORIGINAL', 'NOT_UPSCALED', 'ORIENTATION_APPLIED'])
export class EngineError extends Error { constructor(code, params = {}) { super(code); this.name = 'EngineError'; this.code = code; this.params = params } }
const MODE = ${JSON.stringify(mode)}
export function engineSupport() { return Promise.resolve({ worker: true, webp: MODE !== 'nowebp' }) }
export async function inspectImage(blob) {
  const b = await createImageBitmap(blob)
  const out = { type: 'png', width: b.width, height: b.height, bytes: blob.size, hasAlpha: false, animated: false }
  b.close()
  return out
}
export async function processImage(blob, options, extra = {}) {
  if (MODE === 'fail') throw new EngineError('ENCODE_FAILED')
  if (extra.onProgress) extra.onProgress('decode', 0.1)
  const b = await createImageBitmap(blob)
  const want = options.resize && options.resize.method === 'scale' && options.resize.width ? options.resize.width : b.width
  const w = Math.max(1, Math.min(b.width, want))
  const h = Math.max(1, Math.round((b.height * w) / b.width))
  const c = document.createElement('canvas')
  c.width = w; c.height = h
  c.getContext('2d').drawImage(b, 0, 0, w, h)
  const type = options.format === 'png' ? 'png' : options.format === 'webp' ? 'webp' : 'jpeg'
  if (extra.onProgress) extra.onProgress('encode', 0.8)
  const out = await new Promise((resolve) => c.toBlob(resolve, 'image/' + type, 0.7))
  if (extra.signal && extra.signal.aborted) { b.close(); throw new EngineError('CANCELLED') }
  const res = {
    blob: out, type, mime: 'image/' + type, width: w, height: h, bytes: out.size,
    original: { type: 'png', width: b.width, height: b.height, bytes: blob.size, hasAlpha: false, animated: false, metadata: { copyright: true, creation: false, location: false } },
    savingPct: blob.size > out.size ? Math.round((1 - out.size / blob.size) * 1000) / 10 : 0,
    quality: 70, colors: null, similarity: 0.987, resized: w !== b.width, cropped: false, kept: false,
    notes: want > b.width ? ['NOT_UPSCALED'] : [],
  }
  b.close()
  return res
}
export function decodeForPreview(blob) { return createImageBitmap(blob) }
`

/** Motor modülünün yerine taklit motoru koyar (Vite geliştirme sunucusunun `import()` isteği). */
async function useFakeEngine(page, mode = 'ok') {
  await page.route((u) => new URL(u).pathname.endsWith('/src/utils/imageEngine/index.js'),
    (route) => route.fulfill({ status: 200, contentType: 'application/javascript; charset=utf-8', body: fakeEngine(mode) }))
}

/** Sahte sunucu: geçmiş, ayrıntı, görsel baytları (ikili), not projeksiyonu. */
async function routes(page, { canRun = true, image = 'ok' } = {}) {
  await mockApi(page)
  const state = { images: [], projections: [] }
  await page.route(PATH(/^\/api\/monitoring\/page\/100\/resource-scans$/), json({ success: true, data: scanHistory({ canRun }) }))
  await page.route(PATH(/^\/api\/monitoring\/page\/100\/resource-scans\/\d+$/), (route) => {
    const id = Number(new URL(route.request().url()).pathname.split('/').pop())
    return json({ success: true, data: scanDetail(id) })(route)
  })
  await page.route(PATH(/^\/api\/monitoring\/page\/100\/resource-scans\/\d+\/image$/), (route) => {
    let body = null
    try { body = JSON.parse(route.request().postData() || 'null') } catch { body = null }
    state.images.push(body?.url ?? null)
    if (image === 'fail') return json({ success: false, code: 'RESOURCE_IMAGE_FETCH_FAILED', error: 'x', http_status: 404 }, 502)(route)
    if (image === 'limited') return json({ success: false, code: 'RATE_LIMITED', error: 'x', retry_after_seconds: 30 }, 429, { 'Retry-After': '30' })(route)
    const n = state.images.length
    return route.fulfill({ status: 200, contentType: 'image/png', body: PNG,
      headers: { 'Content-Disposition': `attachment; filename="kampanya-ana-sayfa-banner-${n}.png"`, 'X-Image-Type': 'png', 'X-Image-Width': '1200', 'X-Image-Height': '800', 'Cache-Control': 'no-store' } })
  })
  await page.route(PATH(/^\/api\/monitoring\/page\/100\/resource-scans\/\d+\/image-projection$/), (route) => {
    let body = null
    try { body = JSON.parse(route.request().postData() || 'null') } catch { body = null }
    state.projections.push(body)
    return json({ success: true, data: { algorithm_version: 3, capped: false, applied: 2, ignored: 0,
      before: { grade: 72, letter: 'C', page_bytes: 3_565_158, image_bytes: 2_202_009, load_ms: 2450, large_images: 2,
        scores: { images: { score: 40, lost: 9 }, page_weight: { score: 55, lost: 9 }, load_time: { score: 62, lost: 9.5 }, caching: { score: 75, lost: 2.5 } } },
      after: { grade: 84, letter: 'B', page_bytes: 2_202_009, image_bytes: 838_860, load_ms: 1900, large_images: 0,
        scores: { images: { score: 92, lost: 1.2 }, page_weight: { score: 78, lost: 4.4 }, load_time: { score: 74, lost: 6.5 }, caching: { score: 75, lost: 2.5 } } } } })(route)
  })
  return state
}

/** Görünüm alanı dışına taşan öğeler + sayfa düzeyi yatay taşma. */
function measure(sel) {
  const vw = document.documentElement.clientWidth
  const root = (sel && document.querySelector(sel)) || document.body
  const pageOverflow = Math.max(document.documentElement.scrollWidth - document.documentElement.clientWidth,
    document.body.scrollWidth - document.body.clientWidth)
  const clips = (el) => getComputedStyle(el).overflowX !== 'visible'
  const out = []
  for (const el of root.querySelectorAll('*')) {
    const r = el.getBoundingClientRect()
    const cs = getComputedStyle(el)
    if (r.width <= 1 || r.height <= 1 || cs.visibility === 'hidden' || cs.display === 'none') continue
    if (r.right <= vw + 1 && r.left >= -1) continue
    let contained = false
    for (let p = el.parentElement; p && p !== root; p = p.parentElement) {
      if (clips(p) && p.getBoundingClientRect().right <= vw + 1) { contained = true; break }
    }
    if (!contained) out.push(`${el.tagName.toLowerCase()}[${el.getAttribute('data-slot') || ''}] ${Math.round(r.left)}..${Math.round(r.right)}`)
    if (out.length >= 8) break
  }
  return { pageOverflow, offenders: out }
}

function clipped(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  return [...root.querySelectorAll('span, p, li, h3, h4, h5, label, button, dt, dd, code, td, th, a, legend')]
    .filter((n) => !n.closest('.sr-only') && !n.closest('select') && !n.closest('svg') && !n.closest('.recharts-wrapper'))
    .filter((n) => n.childElementCount === 0 && n.textContent.trim().length > 12
      && (n.scrollWidth > n.clientWidth + 1 || getComputedStyle(n).textOverflow === 'ellipsis')).map((n) => n.textContent.slice(0, 40))
}

function smallTargets(sel) {
  const root = document.querySelector(sel)
  if (!root) return ['NO_ROOT']
  const out = []
  for (const b of root.querySelectorAll('button, select, input:not([type="hidden"]):not([type="file"]), [role="combobox"], [role="menuitem"], [role="slider"], [role="switch"]')) {
    const r = b.getBoundingClientRect()
    if (r.width === 0 || r.height === 0 || b.disabled) continue
    const cs = getComputedStyle(b)
    if (cs.visibility === 'hidden' || cs.display === 'none' || b.getAttribute('aria-hidden') === 'true') continue
    if (b.closest('.recharts-wrapper')) continue
    // Anahtar (switch) ve kaydırıcı başparmağının dokunma alanı görünmez ::after / dolgu ile büyür — kutusu değil alanı ölçülür
    let h = r.height
    const after = getComputedStyle(b, '::after')
    if (after && after.content !== 'none' && after.position === 'absolute') h = Math.max(h, parseFloat(after.height) || 0)
    if (h < 39.5) out.push(`"${(b.getAttribute('aria-label') || b.textContent || b.getAttribute('data-slot') || '').trim().slice(0, 30)}" ${Math.round(h)}`)
  }
  return out
}

/** Açık menünün (gövdeye taşınır) ekrana sığması ve dokunma hedefleri. */
function menuBox() {
  const m = document.querySelector('[data-slot="prs-it-menu-content"]')
  if (!m) return null
  const r = m.getBoundingClientRect()
  const items = [...m.querySelectorAll('[role="menuitem"]')].map((n) => Math.round(n.getBoundingClientRect().height))
  const clippedText = [...m.querySelectorAll('[role="menuitem"], span')].filter((n) => n.childElementCount === 0 && n.scrollWidth > n.clientWidth + 1).map((n) => n.textContent.slice(0, 30))
  return { left: r.left, right: r.right, top: r.top, bottom: r.bottom, vw: document.documentElement.clientWidth, vh: window.innerHeight, items, clippedText }
}

for (const vp of VIEWPORTS) {
  test.describe(`Kaynak analizi → Görsel aracı — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    /** Ölçüm: `sel` içinde taşma / kaydırma, `rootSel` içinde kırpma ve dokunma hedefi. */
    const checker = (page, sel = DIALOG, rootSel = ROOT, bodySel = `${DIALOG} [data-slot="modal-shell-body"]`) => async (stage) => {
      await page.waitForTimeout(300)
      const m = await page.evaluate(measure, sel)
      expect(m.offenders, `${stage} @${vp.name}: ekran dışına taşan öğe`).toEqual([])
      expect(m.pageOverflow, `${stage} @${vp.name}: sayfa yatay kayıyor`).toBeLessThanOrEqual(1)
      const body = page.locator(bodySel).first()
      if (await body.count()) {
        const hScroll = await body.evaluate((el) => el.scrollWidth - el.clientWidth)
        expect(hScroll, `${stage} @${vp.name}: pencere gövdesi yatay kayıyor`).toBeLessThanOrEqual(1)
      }
      expect(await page.evaluate(clipped, rootSel), `${stage} @${vp.name}: kırpılan metin`).toEqual([])
      if (vp.touch) expect(await page.evaluate(smallTargets, rootSel), `${stage} @${vp.name}: küçük dokunma hedefi`).toEqual([])
    }
    const dialogChecker = (page, sel) => checker(page, sel, sel, `${sel} [data-slot="modal-shell-body"]`)

    async function open(page, search = '') {
      await page.goto(`/?tab=page&monitor=100&mtab=resources${search}`)
      const root = page.locator(DIALOG).locator(ROOT)
      return root
    }
    async function openTool(page, root) {
      await root.locator('[data-slot="prs-sections"]').getByRole('tab', { name: /^(Görsel aracı|Image tool)/ }).click()
      const view = root.locator('[data-slot="prs-it-view"]')
      await expect(view).toBeVisible({ timeout: 20_000 })
      return view
    }
    /** Ayar panelini görünür kılar (dar kapta katlanır) ve kabını döner. */
    async function settings(view) {
      const layout = await view.getAttribute('data-layout')
      if (layout === 'narrow') {
        const toggle = view.locator('[data-slot="prs-it-settings-toggle"]')
        if ((await toggle.getAttribute('aria-expanded')) !== 'true') await toggle.click()
      }
      const box = view.locator('[data-slot="prs-it-settings"]')
      await expect(box).toBeVisible()
      return box
    }
    const files = () => [
      { name: 'kapak.png', mimeType: 'image/png', buffer: PNG },
      { name: 'kampanya-ana-sayfa-banner-yuksek-cozunurluk-varyant-2026-sonbahar-indirimleri-masaustu.png', mimeType: 'image/png', buffer: PNG_SMALL },
      { name: 'logo.png', mimeType: 'image/png', buffer: PNG_SMALL },
    ]

    test('araç görünümü: boş → sırada (ayar bekliyor) → işlendi; indirme, ZIP, karşılaştırma, duyarlı set; sığar, kırpılmaz', async ({ page }) => {
      test.setTimeout(180_000)
      await useFakeEngine(page, 'ok')
      await routes(page)
      const root = await open(page)
      await expect(root.locator('[data-slot="prs-hero"]')).toBeVisible({ timeout: 20_000 })
      const check = checker(page)

      // Bölüm sekmeleri: "Geçmiş"in yanında "Görsel aracı" — kırpılmaz; lg+ tek satır, telefon / tablette 3 + 3
      const tabs = root.locator('[data-slot="prs-sections"] [role="tab"]')
      await expect(tabs).toHaveCount(6)
      const tops = await tabs.evaluateAll((els) => els.map((el) => Math.round(el.getBoundingClientRect().top)))
      if (vp.width >= 1024) expect(Math.max(...tops) - Math.min(...tops), `@${vp.name}: bölüm sekmeleri tek satır değil`).toBeLessThanOrEqual(1)
      else expect(new Set(tops).size, `@${vp.name}: bölüm sekmeleri iki satır olmalı`).toBe(2)
      expect(await tabs.evaluateAll((els) => els.filter((el) => el.scrollWidth > el.clientWidth + 1).map((el) => el.textContent)), `@${vp.name}: bölüm sekmesi kırpılıyor`).toEqual([])

      // 1) Boş durum
      const view = await openTool(page, root)
      await expect(page).toHaveURL(/prs_v=tool/)
      await expect(view.locator('[data-slot="prs-it-drop"]')).toBeVisible()
      await expect(view.locator('[data-slot="prs-it-privacy"]')).toBeVisible()
      await expect(view.locator('[data-slot="prs-it-entry"]')).toHaveCount(0)
      await check('araç — boş')

      // 2) Ayar: "ölçekle" (genişlik boş) → eklenen görseller BEKLER, hata alanın altında
      let box = await settings(view)
      await box.locator('[data-slot="prs-it-method"]').selectOption('scale')
      await expect(box.locator('[data-slot="prs-it-width"]')).toBeVisible()
      await check('araç — ayarlar açık')
      await view.locator('[data-slot="prs-it-file-input"]').setInputFiles(files())
      await expect(view.locator('[data-slot="prs-it-entry"]')).toHaveCount(3)
      await expect(view.locator('[data-slot="prs-it-blocked"]')).toBeVisible()
      await expect(view.locator('[data-slot="prs-it-entry"][data-state="waiting"]')).toHaveCount(3)
      box = await settings(view)
      await expect(box.locator('[data-field="width"] [data-slot="field-error"]')).toBeVisible()
      await check('araç — sırada (ayar bekliyor)')

      // 3) Genişlik verilince işlenir: önce → sonra boyut, ölçü, biçim
      await box.locator('[data-slot="prs-it-width"]').fill('600')
      await expect(view.locator('[data-slot="prs-it-entry"][data-state="done"]')).toHaveCount(3, { timeout: 30_000 })
      await expect(view.locator('[data-slot="prs-it-blocked"]')).toHaveCount(0)
      const first = view.locator('[data-slot="prs-it-entry"]').first()
      await expect(first.locator('[data-slot="prs-it-result"]')).toContainText('1200×800 → 600×400')
      await expect(view.locator('[data-slot="prs-it-summary"]')).toContainText('→')
      // üst veri: taklit motor "telif var" der → yalnız o anahtar açılabilir
      await expect(box.locator('[data-slot="prs-it-meta"] [data-meta="copyright"]')).toHaveAttribute('data-available', 'true')
      await expect(box.locator('[data-slot="prs-it-meta"] [data-meta="location"]')).toHaveAttribute('data-available', 'false')
      await check('araç — işlendi')

      // 4) Tek dosya indirme GERÇEKTEN başlar
      const dl1 = page.waitForEvent('download')
      await first.locator('[data-slot="prs-it-entry-download"]').click()
      expect((await dl1).suggestedFilename()).toBe('kapak-600w.jpg')

      // 5) Tümünü indir (ZIP): görseller + eşleşme listesi
      const dl2 = page.waitForEvent('download')
      await view.locator('[data-slot="prs-it-zip"]').click()
      const zip = await dl2
      expect(zip.suggestedFilename()).toMatch(/-\d{8}-\d{4}\.zip$/)
      const entries = unzipSync(new Uint8Array(readFileSync(await zip.path())))
      const names = Object.keys(entries).sort()
      expect(names).toHaveLength(4)
      expect(names.filter((n) => n.endsWith('-600w.jpg') || n.endsWith('-600w-2.jpg') || /-\d+w\.jpg$/.test(n))).toHaveLength(3)
      const mapName = names.find((n) => n.endsWith('.csv'))
      expect(mapName, 'eşleşme listesi').toBeTruthy()
      const csv = strFromU8(entries[mapName])
      expect(csv).toContain('kapak.png,kapak-600w.jpg,')
      // JPEG imzası: çıktı gerçekten yeniden kodlanmış bir görsel
      expect([...entries['kapak-600w.jpg'].slice(0, 3)]).toEqual([0xff, 0xd8, 0xff])

      // 6) Önce / sonra karşılaştırması: tuvaller çizilir, bölücü klavyeyle kayar, yakınlaştırma
      await first.locator('[data-slot="prs-it-entry-compare"]').click()
      const cmp = page.locator(CMP)
      await expect(cmp.locator('[data-slot="prs-it-compare-dialog"]')).toHaveAttribute('data-status', 'ready', { timeout: 20_000 })
      const drawn = await cmp.locator('canvas').evaluateAll((els) => els.map((c) => {
        const d = c.getContext('2d').getImageData(Math.floor(c.width / 2), Math.floor(c.height / 2), 1, 1).data
        return { w: c.width, h: c.height, alpha: d[3] }
      }))
      expect(drawn).toHaveLength(2)
      for (const c of drawn) { expect(c.w).toBeGreaterThan(100); expect(c.alpha, 'tuval boş değil').toBe(255) }
      const divider = cmp.getByRole('slider')
      await expect(divider).toHaveAttribute('aria-valuenow', '50')
      await divider.focus()
      await page.keyboard.press('ArrowRight')
      await expect(divider).toHaveAttribute('aria-valuenow', '52')
      await page.keyboard.press('Home')
      await expect(divider).toHaveAttribute('aria-valuenow', '0')
      await page.keyboard.press('End')
      await expect(divider).toHaveAttribute('aria-valuenow', '100')
      // bölücü sürüklenir (fare / dokunma — işaretçi olayları)
      const stage = cmp.locator('[data-slot="prs-it-cmp-stage"]')
      const sb = await stage.boundingBox()
      await divider.dispatchEvent('pointerdown', { pointerId: 1, pointerType: vp.touch ? 'touch' : 'mouse', button: 0, clientX: sb.x + sb.width - 2, clientY: sb.y + sb.height / 2, bubbles: true })
      await divider.dispatchEvent('pointermove', { pointerId: 1, pointerType: vp.touch ? 'touch' : 'mouse', clientX: sb.x + sb.width / 4, clientY: sb.y + sb.height / 2, bubbles: true })
      await divider.dispatchEvent('pointerup', { pointerId: 1, pointerType: vp.touch ? 'touch' : 'mouse', clientX: sb.x + sb.width / 4, clientY: sb.y + sb.height / 2, bubbles: true })
      const moved = Number(await divider.getAttribute('aria-valuenow'))
      expect(moved).toBeGreaterThan(15)
      expect(moved).toBeLessThan(35)
      await dialogChecker(page, CMP)('karşılaştırma (sığdır)')
      await cmp.locator('[data-slot="prs-it-cmp-zoom"]').getByRole('radio').nth(2).click()
      await expect(cmp.locator('[data-slot="prs-it-compare-dialog"]')).toHaveAttribute('data-zoom', '200')
      // %200: görsel görünüm alanından büyük → görünüm KENDİ içinde kayar (pencere gövdesi değil)
      const scrolls = await cmp.locator('[data-slot="prs-it-cmp-view"]').evaluate((el) => el.scrollWidth > el.clientWidth || el.scrollHeight > el.clientHeight)
      expect(scrolls, `@${vp.name}: %200'de görünüm kaymıyor`).toBe(true)
      await dialogChecker(page, CMP)('karşılaştırma (%200)')
      await cmp.getByRole('button', { name: /^(Kapat|Dismiss)$/ }).last().click()
      await expect(cmp).toHaveCount(0)

      // 7) Duyarlı set: 480 / 768 / 1200 (doğal genişliği aşmayanlar), hazır kod, ZIP
      await first.locator('[data-slot="prs-it-entry-responsive"]').click()
      const rs = page.locator(RS)
      await expect(rs.locator('[data-slot="prs-it-rs-row"]')).toHaveCount(2)
      await dialogChecker(page, RS)('duyarlı set (başlamadan)')
      await rs.locator('[data-slot="prs-it-rs-go"]').click()
      await expect(rs.locator('[data-slot="prs-it-rs-row"][data-state="done"]')).toHaveCount(2, { timeout: 30_000 })
      const snippet = rs.locator('[data-slot="prs-it-rs-snippet"]')
      await expect(snippet).toContainText('srcset="kapak-480w.jpg 480w, kapak-768w.jpg 768w"')
      await rs.locator('[data-slot="prs-it-rs-alt"]').fill('Kampanya "kapak" görseli <2026>')
      await expect(snippet).toContainText('alt="Kampanya &quot;kapak&quot; görseli &lt;2026&gt;"')
      await dialogChecker(page, RS)('duyarlı set (kod)')
      const dl3 = page.waitForEvent('download')
      await rs.locator('[data-slot="prs-it-rs-zip"]').click()
      const setZip = unzipSync(new Uint8Array(readFileSync(await (await dl3).path())))
      expect(Object.keys(setZip).sort()).toEqual(['kapak-480w.jpg', 'kapak-768w.jpg', 'snippet.html'])
      expect(strFromU8(setZip['snippet.html'])).toContain('kapak-768w.jpg')
      await rs.getByRole('button', { name: /^(Kapat|Dismiss)$/ }).last().click()
      await expect(rs).toHaveCount(0)

      // 8) Ayar değişince sonuçlar "bayat": yeniden uygula düğmesi; temizle onay ister
      box = await settings(view)
      await box.locator('[data-slot="prs-it-format"]').selectOption('webp')
      await expect(view.locator('[data-slot="prs-it-entry"][data-stale="true"]')).toHaveCount(3)
      await expect(view.locator('[data-slot="prs-it-reprocess"]')).toBeVisible()
      await check('araç — ayar değişti')
      await view.locator('[data-slot="prs-it-reprocess"]').click()
      await expect(view.locator('[data-slot="prs-it-entry"][data-stale="true"]')).toHaveCount(0, { timeout: 30_000 })
      await view.locator('[data-slot="prs-it-clear"]').click()
      await page.getByRole('button', { name: /^(Kuyruğu temizle|Clear the queue)$/ }).click()
      await expect(view.locator('[data-slot="prs-it-entry"]')).toHaveCount(0)
      await page.screenshot({ path: test.info().outputPath(`image-tool-${vp.name}.png`), fullPage: true })   // dokunmatiği sıfırlar — en sonda
    })

    test('araç görünümü: işlenemeyen görseller nedenini söyler (başarısız durum); yeniden dene / kaldır; sığar', async ({ page }) => {
      await useFakeEngine(page, 'fail')
      await routes(page)
      const root = await open(page, '&prs_v=tool')                       // paylaşılan bağlantı doğrudan aracı açar
      const view = root.locator('[data-slot="prs-it-view"]')
      await expect(view).toBeVisible({ timeout: 20_000 })
      await view.locator('[data-slot="prs-it-file-input"]').setInputFiles(files().slice(0, 2))
      await expect(view.locator('[data-slot="prs-it-entry"][data-state="failed"]')).toHaveCount(2, { timeout: 30_000 })
      const row = view.locator('[data-slot="prs-it-entry"]').nth(1)
      await expect(row.locator('[data-slot="prs-it-entry-problem"]')).toBeVisible()
      await expect(row.locator('[data-slot="prs-it-entry-retry"]')).toBeVisible()
      await expect(row.locator('[data-slot="prs-it-entry-download"]')).toHaveCount(0)
      await expect(view.locator('[data-slot="prs-it-zip"]')).toBeDisabled()
      await checker(page)('araç — başarısız')
      await row.locator('[data-slot="prs-it-entry-remove"]').click()
      await expect(view.locator('[data-slot="prs-it-entry"]')).toHaveCount(1)
    })

    test('tarama görseli: "İndir" menüsü, özgün / optimize indirme, özel boyut, not kartı, toplu ZIP; sığar, kırpılmaz', async ({ page }) => {
      test.setTimeout(180_000)
      await useFakeEngine(page, 'ok')
      const state = await routes(page)
      const root = await open(page)
      await expect(root.locator('[data-slot="prs-hero"]')).toBeVisible({ timeout: 20_000 })
      const check = checker(page)

      // 1) Görsel etkisi kartı: en büyük görsellerde menü + önerilen genişlik; toplu düğme
      const card = root.locator('[data-slot="prs-images"]')
      await card.scrollIntoViewIfNeeded()
      await expect(card.locator('[data-slot="prs-it-menu"]')).toHaveCount(3)
      await expect(card.locator('[data-slot="prs-it-suggest"]').first()).toBeVisible()
      await expect(card.locator('[data-slot="prs-it-bulk-open"]')).toBeVisible()
      await check('pano — görsel etkisi')

      // 2) Menü: ekrana sığar, seçenekler kırpılmaz, dokunmatikte ≥ 40 px
      const firstActions = card.locator('[data-slot="prs-it-actions"]').first()
      await firstActions.locator('[data-slot="prs-it-menu"]').click()
      await expect(page.locator('[data-slot="prs-it-menu-content"]')).toBeVisible()
      const mb = await page.evaluate(menuBox)
      expect(mb.left, `@${vp.name}: menü solda taşıyor`).toBeGreaterThanOrEqual(0)
      expect(mb.right, `@${vp.name}: menü sağda taşıyor`).toBeLessThanOrEqual(mb.vw + 1)
      expect(mb.bottom, `@${vp.name}: menü altta taşıyor`).toBeLessThanOrEqual(mb.vh + 1)
      expect(mb.items).toHaveLength(5)
      expect(mb.clippedText, `@${vp.name}: menüde kırpılan metin`).toEqual([])
      if (vp.touch) for (const h of mb.items) expect(h, `@${vp.name}: menü seçeneği`).toBeGreaterThanOrEqual(40)

      // 3) Özgün dosya: sunucunun verdiği adla iner
      const dl1 = page.waitForEvent('download')
      await page.locator('[data-slot="prs-it-menu-content"] [data-action="original"]').click()
      expect((await dl1).suggestedFilename()).toBe('kampanya-ana-sayfa-banner-1.png')
      expect(state.images).toEqual([BIG.url])

      // 4) Optimize edilmiş: gerçek önce → sonra satırı; ikinci tık sunucudan yeniden İSTEMEZ
      await firstActions.locator('[data-slot="prs-it-menu"]').click()
      const dl2 = page.waitForEvent('download')
      await page.locator('[data-slot="prs-it-menu-content"] [data-action="optimize"]').click()
      expect((await dl2).suggestedFilename()).toBe('kampanya-ana-sayfa-banner-1-opt.jpg')
      await expect(firstActions.locator('[data-slot="prs-it-result"]')).toBeVisible()
      await expect(firstActions.locator('[data-slot="prs-it-result"]')).toContainText('→')
      await expect(firstActions.locator('[data-slot="prs-it-compare"]')).toBeVisible()
      await firstActions.locator('[data-slot="prs-it-menu"]').click()
      const dl3 = page.waitForEvent('download')
      await page.locator('[data-slot="prs-it-menu-content"] [data-action="optimize"]').click()
      await dl3
      expect(state.images, 'ikinci tık önbellekten').toHaveLength(1)
      await check('pano — işlenmiş görsel satırı')

      // 5) Özel boyut penceresi: boş genişlikte hata alanın altında; geçerliyse iner
      await firstActions.locator('[data-slot="prs-it-menu"]').click()
      await page.locator('[data-slot="prs-it-menu-content"] [data-action="custom"]').click()
      const custom = page.locator(CUSTOM)
      await expect(custom).toBeVisible()
      await custom.locator('[data-slot="prs-it-width"]').fill('')
      await custom.locator('[data-slot="prs-it-custom-go"]').click()
      await expect(custom.locator('[data-field="width"] [data-slot="field-error"]')).toBeVisible()
      await dialogChecker(page, CUSTOM)('özel boyut (hata)')
      await custom.locator('[data-slot="prs-it-width"]').fill('640')
      const dl4 = page.waitForEvent('download')
      await custom.locator('[data-slot="prs-it-custom-go"]').click()
      expect((await dl4).suggestedFilename()).toBe('kampanya-ana-sayfa-banner-1-640w.jpg')
      await expect(custom).toHaveCount(0)

      // 6) "Not ne olurdu?": tek tıkla önce → sonra; bu sekmede işlenenle de hesaplanabilir
      const proj = root.locator('[data-slot="prs-it-projection"]')
      await proj.scrollIntoViewIfNeeded()
      await check('not kartı — başlamadan')
      await proj.locator('[data-slot="prs-it-proj-run"]').click()
      await expect(proj.locator('[data-slot="prs-it-proj-result"]')).toBeVisible()
      await expect(proj.locator('[data-slot="prs-it-proj-before"]')).toContainText('72')
      await expect(proj.locator('[data-slot="prs-it-proj-after"]')).toContainText('84')
      await expect(proj.locator('[data-slot="prs-it-proj-row"]')).toHaveCount(4)
      expect(state.projections[0]).toEqual({ use_measured: true })
      await check('not kartı — sonuç')
      await proj.locator('[data-slot="prs-it-proj-basis"]').getByRole('button').nth(1).click()
      await proj.locator('[data-slot="prs-it-proj-run"]').click()
      await expect.poll(() => state.projections.length).toBe(2)
      expect(state.projections[1].use_measured).toBe(false)
      expect(state.projections[1].images).toHaveLength(1)
      expect(state.projections[1].images[0].url).toBe(BIG.url)
      expect(state.projections[1].images[0].width).toBe(640)

      // 7) Toplu indirme: pencere → başlat → üstteki çubuk → ZIP (görseller + eşleşme listesi)
      await card.locator('[data-slot="prs-it-bulk-open"]').click()
      const bulk = page.locator(BULK)
      await expect(bulk.locator('[data-slot="prs-it-bulk-count"]')).toBeVisible()
      await dialogChecker(page, BULK)('toplu indirme penceresi')
      const dl5 = page.waitForEvent('download')
      await bulk.locator('[data-slot="prs-it-bulk-start"]').click()
      const zip = await dl5
      await expect(root.locator('[data-slot="prs-it-bulk-bar"]')).toHaveAttribute('data-state', 'finished', { timeout: 30_000 })
      await expect(root.locator('[data-slot="prs-it-bulk-bar"]')).toHaveAttribute('data-kind', 'done')
      const entries = unzipSync(new Uint8Array(readFileSync(await zip.path())))
      const names = Object.keys(entries)
      expect(names.filter((n) => /\.(jpg|png|webp)$/.test(n)).length).toBeGreaterThanOrEqual(2)
      expect(names.some((n) => n.endsWith('.csv'))).toBe(true)
      expect(names).not.toContain('missing.txt')
      await root.locator('[data-slot="prs-root"], [data-slot="prs-it-bulk-bar"]').first().scrollIntoViewIfNeeded()
      await check('toplu indirme — bitti')

      // 8) Nesne listesi ve nesne ayrıntısı: görsel satırında menü
      await root.locator('[data-slot="prs-sections"]').getByRole('tab', { name: /^(Nesneler|Objects)/ }).click()
      const imgRow = root.locator('[data-slot="prs-obj"][data-category="IMAGE"]').first()
      await expect(imgRow.locator('[data-slot="prs-it-menu"]')).toBeVisible()
      await expect(root.locator('[data-slot="prs-obj"][data-category="JS"] [data-slot="prs-it-menu"]')).toHaveCount(0)
      await check('nesneler — görsel satırı')
      await imgRow.locator('[data-slot="prs-obj-open"]').click()
      const od = page.locator('[role="dialog"]:has([data-slot="prs-od"])')
      await expect(od.locator('[data-slot="prs-od-image"] [data-slot="prs-it-menu"]')).toBeVisible()
      const odSel = '[role="dialog"]:has([data-slot="prs-od"])'
      await checker(page, odSel, odSel, `${odSel} [data-slot="modal-shell-body"]`)('nesne ayrıntısı — görsel grubu')
    })

    test('sunucu görseli vermezse / sınıra takılırsa satır nedenini söyler; yetkisiz görüntüleyicide menü açıklar', async ({ page }) => {
      await useFakeEngine(page, 'ok')
      const state = await routes(page, { image: 'limited' })
      const root = await open(page)
      await expect(root.locator('[data-slot="prs-hero"]')).toBeVisible({ timeout: 20_000 })
      const card = root.locator('[data-slot="prs-images"]')
      await card.scrollIntoViewIfNeeded()
      const actions = card.locator('[data-slot="prs-it-actions"]').first()
      await actions.locator('[data-slot="prs-it-menu"]').click()
      await page.locator('[data-slot="prs-it-menu-content"] [data-action="optimize"]').click()
      await expect(actions.locator('[data-slot="prs-it-problem"]')).toBeVisible()
      await expect(actions.locator('[data-slot="prs-it-problem"]')).toContainText('30')
      await expect(actions.locator('[data-slot="prs-it-result"]')).toHaveCount(0)
      expect(state.images).toHaveLength(1)
      await checker(page)('pano — sınır iletisi')
    })

    test('çalıştırma yetkisi olmayan görüntüleyici: menü nedenini söyler, araç yine açılır', async ({ page }) => {
      await useFakeEngine(page, 'ok')
      const state = await routes(page, { canRun: false })
      const root = await open(page)
      await expect(root.locator('[data-slot="prs-hero"]')).toBeVisible({ timeout: 20_000 })
      const card = root.locator('[data-slot="prs-images"]')
      await card.scrollIntoViewIfNeeded()
      await card.locator('[data-slot="prs-it-menu"]').first().click()
      const na = page.locator('[data-slot="prs-it-unavailable"]')
      await expect(na).toBeVisible()
      await expect(na).toHaveAttribute('data-reason', 'no_permission')
      const mb = await page.evaluate(menuBox)
      expect(mb.right).toBeLessThanOrEqual(mb.vw + 1)
      expect(mb.left).toBeGreaterThanOrEqual(0)
      expect(mb.items).toHaveLength(0)
      await page.keyboard.press('Escape')
      const view = await openTool(page, root)
      await view.locator('[data-slot="prs-it-file-input"]').setInputFiles(files().slice(0, 1))
      await expect(view.locator('[data-slot="prs-it-entry"][data-state="done"]')).toHaveCount(1, { timeout: 30_000 })
      expect(state.images).toHaveLength(0)                                // yüklenen dosya sunucuya gitmez
    })

    // ── GERÇEK MOTOR gerektiren senaryolar (taklit motor YOK) ─────────────────────────────────────────────
    // Motor paketi (utils/imageEngine) bu dalda yalnız sözleşme taslağıdır: her çağrı BROWSER_UNSUPPORTED ile reddedilir.
    // Paketler birleşince `fixme` kaldırılır.
    test('gerçek motor: PNG fark edilir kayıp olmadan küçülür; benzerlik ölçülür; karşılaştırma gerçek pikselleri çizer', async ({ page }) => {
      test.fixme(true, 'engine not merged')
      await routes(page)
      const root = await open(page, '&prs_v=tool')
      const view = root.locator('[data-slot="prs-it-view"]')
      await expect(view).toBeVisible({ timeout: 20_000 })
      await view.locator('[data-slot="prs-it-file-input"]').setInputFiles(files().slice(0, 1))
      const row = view.locator('[data-slot="prs-it-entry"]').first()
      await expect(row).toHaveAttribute('data-state', 'done', { timeout: 60_000 })
      await expect(row.locator('[data-slot="prs-it-result"]')).not.toHaveAttribute('data-kept', 'true')
      const dl = page.waitForEvent('download')
      await row.locator('[data-slot="prs-it-entry-download"]').click()
      const out = readFileSync(await (await dl).path())
      expect(out.length).toBeLessThan(PNG.length)
      expect([...out.slice(0, 4)]).toEqual([0x89, 0x50, 0x4e, 0x47])          // "aynı biçim": PNG kalır
      await row.locator('[data-slot="prs-it-entry-compare"]').click()
      const cmp = page.locator(CMP)
      await expect(cmp.locator('[data-slot="prs-it-compare-dialog"]')).toHaveAttribute('data-status', 'ready', { timeout: 20_000 })
      await expect(cmp.locator('[data-slot="prs-it-cmp-similarity"]')).toHaveAttribute('data-known', 'true')
      await dialogChecker(page, CMP)('gerçek motor — karşılaştırma')
    })

    test('gerçek motor: hedef boyut (200 KB) tutturulur ya da "hedefe inilemedi" notu düşer; WebP desteği seçeneği belirler', async ({ page }) => {
      test.fixme(true, 'engine not merged')
      await routes(page)
      const root = await open(page, '&prs_v=tool')
      const view = root.locator('[data-slot="prs-it-view"]')
      await expect(view).toBeVisible({ timeout: 20_000 })
      const box = await settings(view)
      await box.getByRole('button', { name: /^(Hedef boyut|Target size)$/ }).click()
      await box.locator('[data-slot="prs-it-target"]').fill('100')
      await view.locator('[data-slot="prs-it-file-input"]').setInputFiles(files().slice(0, 1))
      const row = view.locator('[data-slot="prs-it-entry"]').first()
      await expect(row).toHaveAttribute('data-state', 'done', { timeout: 90_000 })
      const dl = page.waitForEvent('download')
      await row.locator('[data-slot="prs-it-entry-download"]').click()
      const out = readFileSync(await (await dl).path())
      const missed = await row.locator('[data-note="TARGET_NOT_REACHED"]').count()
      if (!missed) expect(out.length).toBeLessThanOrEqual(100 * 1024)
      const webp = await box.locator('[data-slot="prs-it-format"] option[value="webp"]').evaluate((o) => o.disabled)
      expect(typeof webp).toBe('boolean')
    })

    test('gerçek motor: tarama görselinin "Küçült + optimize et" indirmesi önerilen genişlikte bir dosya üretir', async ({ page }) => {
      test.fixme(true, 'engine not merged')
      await routes(page)
      const root = await open(page)
      await expect(root.locator('[data-slot="prs-hero"]')).toBeVisible({ timeout: 20_000 })
      const actions = root.locator('[data-slot="prs-images"] [data-slot="prs-it-actions"]').first()
      await actions.scrollIntoViewIfNeeded()
      await actions.locator('[data-slot="prs-it-menu"]').click()
      const dl = page.waitForEvent('download')
      await page.locator('[data-slot="prs-it-menu-content"] [data-action="resize"]').click()
      await dl
      // Sınama görseli 1200 piksel: önerilen 1920'ye BÜYÜTÜLMEZ — motor notu düşer
      await expect(actions.locator('[data-note="NOT_UPSCALED"]')).toBeVisible({ timeout: 60_000 })
    })
  })
}
