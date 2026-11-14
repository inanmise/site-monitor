import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import * as engine from '../utils/imageEngine/index.js'
import { FALLBACK_LIMITS } from '../components/page/resourcescan/imagetool/engineClient.js'
import {
  BULK_MAX_IMAGES, CLIENT_PROBLEMS, DEFAULT_SETTINGS, ENGINE_CODES, FORMATS, METHODS, NOTE_CODES, PHASES, PROJECTION_MAX_IMAGES,
  QUALITY_MODES, RESPONSIVE_WIDTHS, SERVER_CODES, SERVER_MAX_BYTES, TOOL_MAX_FILES, UNAVAILABLE_REASONS,
  baseNameOfUrl, buildOptions, bulkPlan, dimsText, engineProblemText, escapeAttr, imageUnavailable, isImageItem, isSvgItem, largeImages, mappingCsv,
  mappingRows, missingText, noteText, optimizeSettings, optionsKey, outputName, phaseText, pictureSnippet, problemLine, processedCells,
  projectionImages, projectionView, resizeSettings, responsiveWidths, resultLine, resultMeta, resultSummary, safeFileBase, savingPctOf,
  growthPctOf, nameKey, pctText, pngPaletteColors, resultTone,
  serverProblemCode, serverProblemText, settingsSummary, similarityText, slugFileBase, splitName, suggestedWidth, suggestionText,
  uniqueName, validateSettings, zipFileName,
} from '../components/page/resourcescan/imagetool/imageToolModel.js'
import { imageSheetRows } from '../components/page/resourcescan/resourceScanModel.js'

/** Çeviri taklidi: anahtar + bağımsız değişkenler görünür kalır (hangi metnin seçildiği sınanır). */
const t = (key, ...args) => (args.length ? `${key}(${args.join('|')})` : key)
const tr = (key, ...args) => String(TR[key] ?? key).replace(/\{(\d+)\}/g, (_, i) => String(args[Number(i)] ?? ''))
const img = (over = {}) => ({ category: 'IMAGE', url: 'https://ornek.example.com/img/hero.jpg', bytes: 600 * 1024, status: 200, ok: true, verdict: 'ok', width: 3000, height: 2000, ...over })
const summary = (over = {}) => resultSummary({
  blob: {}, type: 'webp', bytes: 150_000, width: 1200, height: 800, savingPct: 75, kept: false, resized: true, cropped: false, quality: 78,
  colors: null, similarity: 0.992, notes: ['NOT_UPSCALED'],
  original: { type: 'jpeg', bytes: 600_000, width: 3000, height: 2000, hasAlpha: false, animated: false, metadata: { copyright: true, creation: false, location: false } },
  ...over,
})

describe('görsel aracı — kod listeleri motorun ve sözleşmenin listeleriyle AYNI, her kodun TR + EN metni var', () => {
  it('motorun dışa aktardığı listeler = arayüzün listeleri (biçim, yöntem, hata, not, sınırlar)', () => {
    expect([...FORMATS]).toEqual([...engine.OUTPUT_FORMATS])
    expect([...METHODS]).toEqual([...engine.RESIZE_METHODS])
    expect([...ENGINE_CODES].sort()).toEqual([...engine.ERROR_CODES].sort())
    expect([...NOTE_CODES].sort()).toEqual([...engine.NOTE_CODES].sort())
    expect({ ...FALLBACK_LIMITS }).toEqual({ ...engine.ENGINE_LIMITS })
    expect(SERVER_MAX_BYTES).toBe(engine.ENGINE_LIMITS.maxInputBytes)
    expect(TOOL_MAX_FILES).toBe(30)
  })

  it('her motor hata / not kodu, aşama, biçim, yöntem, kalite kipi ve "indirilemez" nedeni için TR + EN metin var', () => {
    const need = [
      ...engine.ERROR_CODES.map((c) => `prs.it.eng.${c}`), 'prs.it.eng.other',
      ...engine.NOTE_CODES.map((c) => `prs.it.note.${c}`), 'prs.it.note.other',
      ...PHASES.map((p) => `prs.it.phase.${p}`), 'prs.it.phase.other',
      ...engine.OUTPUT_FORMATS.flatMap((f) => [`prs.it.set.format.${f}`, `prs.it.set.formatHint.${f}`]),
      ...engine.RESIZE_METHODS.flatMap((m) => [`prs.it.set.method.${m}`, `prs.it.set.methodHint.${m}`]),
      ...QUALITY_MODES.flatMap((m) => [`prs.it.set.qmode.${m}`, `prs.it.set.qualityHint.${m}`]),
      ...UNAVAILABLE_REASONS.map((r) => `prs.it.na.${r}`),
      ...['copyright', 'creation', 'location'].flatMap((k) => [`prs.it.set.meta.${k}`, `prs.it.set.metaHint.${k}`]),
      ...['waiting', 'processing', 'done', 'problem'].map((s) => `prs.it.q.${s}`),
      ...['idle', 'waiting', 'processing', 'done', 'problem'].map((s) => `prs.it.rs.state.${s}`),
      'prs.it.sum.none', 'prs.it.sum.scaleW', 'prs.it.sum.scaleH', 'prs.it.sum.fit', 'prs.it.sum.cover', 'prs.it.sum.thumb',
    ]
    for (const k of need) {
      expect(typeof TR[k] === 'string' && TR[k].trim().length > 0, `TR ${k}`).toBe(true)
      expect(typeof EN[k] === 'string' && EN[k].trim().length > 0, `EN ${k}`).toBe(true)
    }
  })

  it('sözleşme §1.1 / §1.2 sunucu kodlarının hepsi tanınır ve TR + EN metni var; bekleme önerili iki kodun ".wait" metni de', () => {
    // Sözleşmedeki kodlar (image-tool-contract.md §1): yeni uçların döndürebildiği hata kodları
    const CONTRACT = ['RESOURCE_SCAN_NOT_FOUND', 'RESOURCE_SCAN_ITEMS_GONE', 'RESOURCE_IMAGE_NOT_FOUND', 'RESOURCE_IMAGE_URL_MASKED',
      'RESOURCE_IMAGE_TOO_LARGE', 'RESOURCE_IMAGE_FETCH_FAILED', 'BLOCKED', 'RESOURCE_IMAGE_UNSUPPORTED', 'RATE_LIMITED', 'RESOURCE_IMAGE_BUSY',
      'RESOURCE_SCAN_NOT_DONE', 'RESOURCE_SCAN_NOT_PROJECTABLE', 'RESOURCE_IMAGE_HOST_LIMITED', 'RESOURCE_PROJECTION_BUSY']
    expect([...SERVER_CODES].sort()).toEqual([...CONTRACT].sort())
    const need = [...CONTRACT.map((c) => `prs.it.srv.${c}`), ...CLIENT_PROBLEMS.map((c) => `prs.it.srv.${c}`),
      'prs.it.srv.RATE_LIMITED.wait', 'prs.it.srv.RESOURCE_IMAGE_BUSY.wait', 'prs.it.srv.RESOURCE_IMAGE_FETCH_FAILED.http', 'prs.it.srv.RESOURCE_IMAGE_FETCH_FAILED.reason',
      'prs.it.srv.RESOURCE_IMAGE_TOO_LARGE.size', 'prs.it.srv.RESOURCE_SCAN_NOT_PROJECTABLE.algorithm', 'prs.it.srv.RESOURCE_SCAN_NOT_PROJECTABLE.baseline',
      'prs.it.srv.RESOURCE_IMAGE_HOST_LIMITED.wait', 'prs.it.srv.RESOURCE_PROJECTION_BUSY.wait', 'prs.it.srv.RESOURCE_IMAGE_FETCH_FAILED.TLS_DOWNGRADE',
      'prs.it.tls.unverified']
    for (const k of need) {
      expect(typeof TR[k] === 'string' && TR[k].trim().length >= 25, `TR ${k}`).toBe(true)
      expect(typeof EN[k] === 'string' && EN[k].trim().length >= 25, `EN ${k}`).toBe(true)
    }
  })

  it('sunucu kaynağı varsa (paketler birleşince): Java tarafının RESOURCE_IMAGE_* kodlarının hepsi arayüzde tanınır', () => {
    const root = path.resolve(__dirname, '../../../backend/src/main/java')
    const found = new Set()
    const walk = (dir, depth = 0) => {
      if (depth > 12 || !fs.existsSync(dir)) return
      for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
        const p = path.join(dir, e.name)
        if (e.isDirectory()) walk(p, depth + 1)
        else if (/(ResourceScan|ResourceImage|ImageProjection|PageResource).*\.java$/.test(e.name)) {
          for (const m of fs.readFileSync(p, 'utf8').matchAll(/"(RESOURCE_IMAGE_[A-Z_]+)"/g)) found.add(m[1])
        }
      }
    }
    walk(root)
    // Sunucu paketi henüz birleşmediyse `found` boştur — o zaman denetlenecek bir şey yok (sözleşme listesi üstteki testte)
    for (const code of found) expect(SERVER_CODES.includes(code), `sunucu kodu arayüzde yok: ${code}`).toBe(true)
  })
})

describe('görsel aracı — indirilebilirlik ve önerilen genişlik', () => {
  it('imageUnavailable: yetki → saklanmayan nesneler → adres yok → maskeli → SVG → açılmamış → sınırdan büyük → null', () => {
    expect(imageUnavailable(img(), { canRun: false, itemsKept: true })).toBe('no_permission')
    // Yalnız IMAGE kategorisi indirilebilir: favicon sunucuda 404 RESOURCE_IMAGE_NOT_FOUND alır → menü hiç sunulmaz
    expect(isImageItem(img())).toBe(true)
    expect(isImageItem(img({ category: 'FAVICON' }))).toBe(false)
    expect(imageUnavailable(img(), { canRun: true, itemsKept: false })).toBe('items_gone')
    expect(imageUnavailable(img({ url: '' }), { canRun: true })).toBe('no_url')
    expect(imageUnavailable(img({ url: 'https://ornek.example.com/a.jpg?token=*****' }), { canRun: true })).toBe('masked')
    expect(imageUnavailable(img({ url: 'https://ornek.example.com/logo.svg?v=3' }), { canRun: true })).toBe('svg')
    expect(imageUnavailable(img({ content_type: 'image/svg+xml' }), { canRun: true })).toBe('svg')
    expect(imageUnavailable(img({ ok: false, status: 404, verdict: 'broken' }), { canRun: true })).toBe('broken')
    expect(imageUnavailable(img({ bytes: SERVER_MAX_BYTES + 1 }), { canRun: true })).toBe('too_large')
    expect(imageUnavailable(img({ bytes: SERVER_MAX_BYTES }), { canRun: true })).toBe(null)
    expect(imageUnavailable(img({ bytes: null }), { canRun: true })).toBe(null)   // boyut bilinmiyor — sunucu karar verir
    expect(isSvgItem({ url: 'https://ornek.example.com/a.SVGZ#x' })).toBe(true)
    expect(isSvgItem({ url: 'https://ornek.example.com/svg/a.png' })).toBe(false)
  })

  it('suggestedWidth: sunucu alanı varsa o (gerekçesiyle); yoksa yalnız "2000 pikselden geniş → 1920"; alan yok / küçük görsel → null', () => {
    expect(suggestedWidth(img({ suggested_width: 1200, suggested_reason: 'declared', declared_width: 600 })))
      .toEqual({ width: 1200, reason: 'declared', natural: 3000, declared: 600, source: 'server' })
    expect(suggestedWidth(img({ suggested_width: 1920, suggested_reason: 'max_width' })))
      .toEqual({ width: 1920, reason: 'max_width', natural: 3000, declared: null, source: 'server' })
    // Eski analiz / eski pod: alan yok → istemci kuralı
    expect(suggestedWidth(img())).toEqual({ width: 1920, reason: 'max_width', natural: 3000, declared: null, source: 'client' })
    expect(suggestedWidth(img({ width: 2000 }))).toBe(null)        // tam 2000 = sınırın üstünde değil
    expect(suggestedWidth(img({ width: 2001 }))?.width).toBe(1920)
    expect(suggestedWidth(img({ width: null }))).toBe(null)        // doğal genişlik bilinmiyor
    expect(suggestedWidth({ category: 'IMAGE', url: 'x' })).toBe(null)
    // Öneri doğal genişlikten büyük / eşit olamaz; tanınmayan gerekçe yok sayılır (istemci kuralına düşer)
    expect(suggestedWidth(img({ width: 1000, suggested_width: 1200, suggested_reason: 'declared' }))).toBe(null)
    expect(suggestedWidth(img({ suggested_width: 900, suggested_reason: 'whatever' }))?.source).toBe('client')
    expect(suggestedWidth(img({ suggested_width: null, suggested_reason: null, width: 800 }))).toBe(null)
  })

  it('suggestionText: gerekçeye göre cümle; eksik sayıda kısa biçim', () => {
    expect(suggestionText(suggestedWidth(img({ suggested_width: 1200, suggested_reason: 'declared', declared_width: 600 })), t, 'tr-TR'))
      .toBe('prs.it.suggest.declared(3.000|600|1.200)')
    expect(suggestionText({ width: 1200, reason: 'declared', natural: null, declared: null }, t, 'tr-TR')).toBe('prs.it.suggest.declaredShort(1.200)')
    expect(suggestionText(suggestedWidth(img()), t, 'tr-TR')).toBe('prs.it.suggest.maxWidth(3.000|2.000|1.920)')
    expect(suggestionText({ width: 1920, reason: 'max_width', natural: null }, t, 'tr-TR')).toBe('prs.it.suggest.maxWidthShort(1.920)')
    expect(suggestionText(null, t, 'tr-TR')).toBe('')
  })

  it('largeImages / bulkPlan: bulgu listesi varsa LARGE_IMAGES, yoksa ≥ 200 KB; tekil, büyükten küçüğe; indirilemeyenler nedeniyle ayrı; 100 tavanı', () => {
    const items = [
      img({ url: 'https://o.example/a.jpg', bytes: 300 * 1024 }),
      img({ url: 'https://o.example/b.jpg', bytes: 900 * 1024 }),
      img({ url: 'https://o.example/b.jpg', bytes: 900 * 1024 }),            // aynı adres ikinci kez
      img({ url: 'https://o.example/small.jpg', bytes: 50 * 1024 }),
      { category: 'SCRIPT', url: 'https://o.example/app.js', bytes: 900 * 1024 },
      img({ url: 'https://o.example/c.svg', bytes: 400 * 1024 }),
      img({ url: 'https://o.example/d.jpg?sig=*****', bytes: 500 * 1024 }),
    ]
    expect(largeImages(items).map((i) => i.url)).toEqual(['https://o.example/b.jpg', 'https://o.example/d.jpg?sig=*****', 'https://o.example/c.svg', 'https://o.example/a.jpg'])
    // Sunucunun bulgu listesi varsa O belirler (boyut eşiği değil)
    const withFindings = [img({ url: 'https://o.example/x.jpg', bytes: 10, findings: ['LARGE_IMAGES'] }), img({ url: 'https://o.example/y.jpg', bytes: 900 * 1024, findings: [] })]
    expect(largeImages(withFindings).map((i) => i.url)).toEqual(['https://o.example/x.jpg'])
    const p = bulkPlan(items, { canRun: true, itemsKept: true })
    expect(p.plan.map((i) => i.url)).toEqual(['https://o.example/b.jpg', 'https://o.example/a.jpg'])
    expect(p.skipped.map((s) => s.reason).sort()).toEqual(['masked', 'svg'])
    expect(p.truncated).toBe(0)
    expect(bulkPlan(items, { canRun: false, itemsKept: true }).plan).toEqual([])
    const many = Array.from({ length: BULK_MAX_IMAGES + 7 }, (_, i) => img({ url: `https://o.example/m${i}.jpg`, bytes: (300 + i) * 1024 }))
    const big = bulkPlan(many, { canRun: true, itemsKept: true })
    expect(big.plan).toHaveLength(BULK_MAX_IMAGES)
    expect(big.truncated).toBe(7)
    expect(largeImages(null)).toEqual([])
  })
})

describe('görsel aracı — ayarların doğrulanması ve motor seçenekleri', () => {
  const L = engine.ENGINE_LIMITS
  it('varsayılan ayar geçerlidir ve "aynı boyut, aynı biçim, otomatik kalite, üst veri yok" seçeneklerini üretir', () => {
    expect(validateSettings(DEFAULT_SETTINGS, L)).toBe(null)
    expect(buildOptions(DEFAULT_SETTINGS, L)).toEqual({
      resize: { method: 'none' }, format: 'keep', quality: 'auto', targetBytes: null, background: '#ffffff',
      preserve: { copyright: false, creation: false, location: false },
    })
  })

  it('ölçekle: tam bir kenar; sığdır / kapla / küçük resim: iki kenar; sınır dışı ve sayı olmayan değer reddedilir', () => {
    expect(validateSettings({ ...DEFAULT_SETTINGS, method: 'scale' }, L)).toEqual({ width: { key: 'prs.it.v.scaleOne', args: [] } })
    expect(validateSettings({ ...DEFAULT_SETTINGS, method: 'scale', width: '800', height: '600' }, L)).toEqual({ height: { key: 'prs.it.v.scaleOnlyOne', args: [] } })
    expect(validateSettings(resizeSettings(1200), L)).toBe(null)
    expect(validateSettings({ ...DEFAULT_SETTINGS, method: 'scale', height: '600' }, L)).toBe(null)
    for (const bad of ['0', '-5', '12.5', 'abc', '1e3', String(L.maxSide + 1), '1234567890']) {
      expect(validateSettings(resizeSettings(bad), L), bad).toEqual({ width: { key: 'prs.it.v.side', args: [L.maxSide] } })
    }
    expect(validateSettings(resizeSettings(String(L.maxSide)), L)).toBe(null)
    for (const m of ['fit', 'cover', 'thumb']) {
      expect(validateSettings({ ...DEFAULT_SETTINGS, method: m }, L)).toEqual({
        width: { key: 'prs.it.v.sideRequired', args: [L.maxSide] }, height: { key: 'prs.it.v.sideRequired', args: [L.maxSide] } })
      expect(validateSettings({ ...DEFAULT_SETTINGS, method: m, width: '400', height: 'x' }, L)).toEqual({ height: { key: 'prs.it.v.side', args: [L.maxSide] } })
      expect(buildOptions({ ...DEFAULT_SETTINGS, method: m, width: '400', height: '300' }, L).resize).toEqual({ method: m, width: 400, height: 300 })
    }
    // Gizli alan doğrulanmaz: yöntem "none" iken genişlikteki bozuk metin kaydı engellemez
    expect(validateSettings({ ...DEFAULT_SETTINGS, method: 'none', width: 'abc' }, L)).toBe(null)
  })

  it('kalite kipleri: elle 1–100 tam sayı; hedef boyut 1 KB – sınır; arka plan #rrggbb', () => {
    expect(buildOptions({ ...DEFAULT_SETTINGS, qualityMode: 'manual', quality: 60 }, L).quality).toBe(60)
    for (const q of [0, 101, 55.5, 'x']) expect(validateSettings({ ...DEFAULT_SETTINGS, qualityMode: 'manual', quality: q }, L)?.quality?.key).toBe('prs.it.v.quality')
    const maxKb = Math.floor(L.maxInputBytes / 1024)
    expect(buildOptions({ ...DEFAULT_SETTINGS, qualityMode: 'target', targetKb: '200' }, L).targetBytes).toBe(200 * 1024)
    for (const kb of ['', '0', '-1', '1,5', String(maxKb + 1)]) {
      expect(validateSettings({ ...DEFAULT_SETTINGS, qualityMode: 'target', targetKb: kb }, L), kb).toEqual({ targetKb: { key: 'prs.it.v.targetKb', args: [maxKb] } })
    }
    // Otomatik kipte hedef / kalite alanı doğrulanmaz
    expect(validateSettings({ ...DEFAULT_SETTINGS, qualityMode: 'auto', targetKb: 'x', quality: 500 }, L)).toBe(null)
    expect(validateSettings({ ...DEFAULT_SETTINGS, background: 'white' }, L)).toEqual({ background: { key: 'prs.it.v.background', args: [] } })
    expect(buildOptions({ ...DEFAULT_SETTINGS, background: '#FFAA00' }, L).background).toBe('#ffaa00')
    expect(buildOptions({ ...DEFAULT_SETTINGS, format: 'bmp' }, L)).toBe(null)
    expect(buildOptions({ ...DEFAULT_SETTINGS, method: 'scale' }, L)).toBe(null)
    expect(buildOptions(null, L)).toBe(null)
  })

  it('üst veri: yalnız açılan tür korunur; seçenek anahtarı ayara göre değişir, aynı ayarda aynıdır', () => {
    const o = buildOptions({ ...DEFAULT_SETTINGS, keepCopyright: true, keepLocation: true }, L)
    expect(o.preserve).toEqual({ copyright: true, creation: false, location: true })
    const a = optionsKey(buildOptions(resizeSettings(1200), L))
    expect(a).toBe(optionsKey(buildOptions(resizeSettings('1200'), L)))
    expect(a).not.toBe(optionsKey(buildOptions(resizeSettings(1201), L)))
    expect(a).not.toBe(optionsKey(buildOptions(resizeSettings(1200, { format: 'webp' }), L)))
    expect(optionsKey(buildOptions(optimizeSettings(), L))).not.toBe(optionsKey(buildOptions(optimizeSettings({ keepCreation: true }), L)))
    expect(optionsKey(null)).toBe('')
  })

  it('settingsSummary: yöntemin kısa özeti', () => {
    expect(settingsSummary(resizeSettings(1200), t, 'tr-TR')).toBe('prs.it.sum.scaleW(1.200)')
    expect(settingsSummary({ ...DEFAULT_SETTINGS, method: 'scale', height: '600' }, t, 'tr-TR')).toBe('prs.it.sum.scaleH(600)')
    expect(settingsSummary({ ...DEFAULT_SETTINGS, method: 'cover', width: '400', height: '300' }, t, 'tr-TR')).toBe('prs.it.sum.cover(400|300)')
    expect(settingsSummary(DEFAULT_SETTINGS, t, 'tr-TR')).toBe('prs.it.sum.none')
  })
})

describe('görsel aracı — dosya adları (ZIP)', () => {
  it('splitName / safeFileBase / slugFileBase / baseNameOfUrl', () => {
    expect(splitName('hero.banner.JPG')).toEqual({ base: 'hero.banner', ext: 'jpg' })
    expect(splitName('.htaccess')).toEqual({ base: '.htaccess', ext: '' })
    expect(splitName('adsiz')).toEqual({ base: 'adsiz', ext: '' })
    expect(safeFileBase('a/b\\c:d*e?"f<g>h|i')).toBe('a-b-c-d-e--f-g-h-i')
    expect(safeFileBase('..\u0000 gizli . ')).toBe('gizli')
    expect(safeFileBase('', 'yedek')).toBe('yedek')
    expect(safeFileBase('x'.repeat(300))).toHaveLength(100)
    expect(slugFileBase('Ürün Görseli (Şubat) İÇ.png')).toBe('urun-gorseli-subat-ic-png')
    expect(slugFileBase('***')).toBe('image')
    expect(baseNameOfUrl('https://ornek.example.com/img/kapak%20g%C3%B6rseli.jpg?v=3#x')).toBe('kapak görseli')
    expect(baseNameOfUrl('https://ornek.example.com/')).toBe('ornek.example')
    expect(baseNameOfUrl('https://ornek.example.com/a/%E0%A4%A.png')).toBe('%E0%A4%A')   // bozuk kaçış — ham parça
    expect(baseNameOfUrl('adres değil')).toBe('image')
  })

  it('outputName: yerine koymaya hazır ad / "-opt" / "-1200w"; uzantı ÇIKTI türünden', () => {
    expect(outputName('hero', 'jpeg')).toBe('hero.jpg')
    expect(outputName('hero', 'webp', { mode: 'optimized' })).toBe('hero-opt.webp')
    expect(outputName('hero', 'png', { mode: 'width', width: 1200 })).toBe('hero-1200w.png')
    // Genişlik yoksa (doğal genişlikte, büyütülmedi) özgünle AYNI ad DEĞİL — inceleme 13
    expect(outputName('hero', 'png', { mode: 'width', width: null })).toBe('hero-opt.png')
    expect(outputName('hero', 'tuhaf')).toBe('hero.img')
  })

  it('uniqueName: aynı ad ikinci kez gelirse -2, -3 … (büyük / küçük harf duyarsız, Türkçe İ / ı dahil)', () => {
    const used = new Set()
    expect(uniqueName('hero.jpg', used)).toBe('hero.jpg')
    expect(uniqueName('hero.jpg', used)).toBe('hero-2.jpg')
    expect(uniqueName('HERO.jpg', used)).toBe('HERO-3.jpg')
    expect(uniqueName('hero-2.jpg', used)).toBe('hero-2-2.jpg')
    expect(uniqueName('IŞIK.png', used)).toBe('IŞIK.png')
    expect(uniqueName('ışık.png', used)).toBe('ışık-2.png')
    expect(uniqueName('adsiz', used)).toBe('adsiz')
    expect(uniqueName('adsiz', used)).toBe('adsiz-2')
    // Ayrılmış adlar (eşleşme listesi, missing.txt) görsel adıyla çakışmaz
    const reserved = new Set([nameKey('missing.txt'), nameKey('eslesme.csv')])
    expect(uniqueName('missing.txt', reserved)).toBe('missing-2.txt')
  })

  it('zipFileName: yalın ad + son ek + tarih damgası', () => {
    expect(zipFileName('Kurumsal Site — Ana Sayfa', 'optimize-gorseller', new Date(2026, 10, 14, 9, 5))).toBe('kurumsal-site-ana-sayfa-optimize-gorseller-20261114-0905.zip')
    expect(zipFileName('', '', new Date(2026, 0, 2, 3, 4))).toBe('gorseller-images-20260102-0304.zip')
  })
})

describe('görsel aracı — sonuç özeti ve metinleri', () => {
  it('resultSummary yalnız bilinen alanları alır; bilinmeyen değer null kalır (0 uydurulmaz)', () => {
    const s = summary()
    expect(s).toMatchObject({ bytes: 150_000, width: 1200, height: 800, type: 'webp', savingPct: 75, kept: false, resized: true, quality: 78, colors: null, similarity: 0.992, notes: ['NOT_UPSCALED'] })
    expect(s.original).toMatchObject({ bytes: 600_000, width: 3000, height: 2000, type: 'jpeg', metadata: { copyright: true, creation: false, location: false } })
    expect(resultSummary(null)).toBe(null)
    const bare = resultSummary({ blob: {} })
    expect(bare.bytes).toBe(null)
    expect(bare.savingPct).toBe(null)
    expect(savingPctOf(bare)).toBe(null)
    expect(savingPctOf(summary({ savingPct: undefined }))).toBe(75)                    // baytlardan
    expect(savingPctOf(summary({ savingPct: undefined, bytes: 700_000 }))).toBe(0)      // büyüdüyse 0 (kazanç yok)
    expect(savingPctOf(null)).toBe(null)
  })

  it('pctText: küçük yüzde sahte 0 olmaz (1 altı 2 ondalık, 10 altı 1 ondalık, 0,01 altı "<"); bilinmeyen boş', () => {
    expect(pctText(75, 'tr-TR')).toBe('%75')
    expect(pctText(7.25, 'tr-TR')).toBe('%7,3')
    expect(pctText(0.4, 'tr-TR')).toBe('%0,4')
    expect(pctText(0.004, 'tr-TR')).toBe('<%0,01')
    expect(pctText(0, 'tr-TR')).toBe('%0')
    expect(pctText(null, 'tr-TR')).toBe('')
    expect(pctText(12.5, 'en-GB')).toBe('13%')
    expect(pctText(99.64, 'tr-TR')).toBe('%99,6')          // "%100" yazılmaz — bir şey kaldı
    expect(pctText(99.97, 'en-GB')).toBe('99.9%')
    expect(pctText(100, 'en-GB')).toBe('100%')
  })

  it('pngPaletteColors: motor kuralı — 100 kayıpsız (null), altı round(256·(q/100)²), en az 8', () => {
    expect(pngPaletteColors(100)).toBe(null)
    expect(pngPaletteColors(90)).toBe(207)
    expect(pngPaletteColors(50)).toBe(64)
    expect(pngPaletteColors(10)).toBe(8)
    expect(pngPaletteColors(1)).toBe(8)
    expect(pngPaletteColors('')).toBe(null)
    expect(pngPaletteColors(0)).toBe(null)
  })

  it('resultLine / resultMeta / similarityText', () => {
    expect(resultLine(summary(), tr, 'tr-TR')).toMatch(/→/)
    expect(resultLine(summary({ kept: true, bytes: 600_000, savingPct: 0 }), t, 'tr-TR')).toMatch(/^prs\.it\.res\.kept\(/)
    expect(resultLine(resultSummary({ blob: {} }), t, 'tr-TR')).toBe('')
    // Gerçek motor: açıkça istenen biçime dönüşüm özgünden BÜYÜK çıkabilir (savingPct 0, kept false) — "korundu" denmez
    const larger = summary({ bytes: 720_000, savingPct: 0, kept: false })
    expect(resultTone(larger)).toBe('larger')
    expect(growthPctOf(larger)).toBe(20)
    expect(resultLine(larger, t, 'tr-TR')).toBe('prs.it.res.larger(586 KB|703 KB|%20)')
    const same = summary({ bytes: 600_000, savingPct: 0, kept: false })
    expect(resultTone(same)).toBe('same')
    expect(resultLine(same, t, 'tr-TR')).toBe('prs.it.res.noSaving(586 KB|586 KB)')
    // Motor yüzdesi 0 ama bayt azaldı (yuvarlama): baytlardan hesaplanır, sahte "kazanç yok" yok
    const tiny = summary({ bytes: 599_700, savingPct: 0 })
    expect(resultTone(tiny)).toBe('smaller')
    expect(resultLine(tiny, t, 'tr-TR')).toBe('prs.img.optimizedTo(586 KB|586 KB|%0,05)')
    expect(resultTone(summary({ kept: true, bytes: 600_000 }))).toBe('kept')
    expect(resultTone(summary({ original: { bytes: null } }))).toBe('unknown')
    expect(resultTone(null)).toBe('unknown')
    expect(growthPctOf(summary())).toBe(null)
    expect(resultMeta(summary(), t, 'tr-TR')).toBe('3000×2000 → 1200×800 · JPEG → WebP · prs.it.res.quality(78)')
    expect(resultMeta(summary({ type: 'jpeg', width: 3000, height: 2000, quality: null }), t, 'tr-TR')).toBe('3000×2000 · JPEG')
    expect(resultMeta(summary({ type: 'png', colors: 256, quality: null }), t, 'tr-TR')).toContain('prs.it.res.colors(256)')
    expect(similarityText(summary(), 'tr-TR')).toBe('%99,2')
    expect(similarityText(summary({ similarity: null }), 'tr-TR')).toBe(null)
    expect(similarityText(summary({ similarity: 1.7 }), 'tr-TR')).toBe(null)
    expect(dimsText(10, null)).toBe('')
    expect(noteText('KEPT_ORIGINAL', t)).toBe('prs.it.note.KEPT_ORIGINAL')
    expect(noteText('YENI_KOD', t)).toBe('prs.it.note.other')
    expect(phaseText('search', t)).toBe('prs.it.phase.search')
    expect(phaseText('baska', t)).toBe('prs.it.phase.other')
  })
})

describe('görsel aracı — sorun metinleri (sunucu / motor)', () => {
  it('serverProblemCode: sözleşme kodu → kendisi; kodsuz durumlar → oturum / yetki / sınır / ağ / eski sunucu / diğer', () => {
    for (const c of SERVER_CODES) expect(serverProblemCode({ success: false, status: 409, code: c })).toBe(c)
    expect(serverProblemCode({ success: false, status: 401 })).toBe('session')
    expect(serverProblemCode({ success: false, status: 403, code: 'FORBIDDEN' })).toBe('forbidden')
    expect(serverProblemCode({ success: false, status: 429 })).toBe('RATE_LIMITED')
    expect(serverProblemCode({ success: false, status: 0 })).toBe('network')
    // Kodlu 404'ler eski sunucu DEĞİL: NOT_FOUND = izleme artık sana görünmüyor (inceleme 13); yalnız kodsuz 404 / 405 "bu sürümde yok"
    expect(serverProblemCode({ success: false, status: 404, code: 'NOT_FOUND' })).toBe('monitor_gone')
    expect(serverProblemCode({ success: false, status: 404 })).toBe('unsupported')
    expect(serverProblemCode({ success: false, status: 405 })).toBe('unsupported')
    expect(serverProblemCode({ success: false, status: 404, code: 'RESOURCE_SCAN_NOT_FOUND' })).toBe('RESOURCE_SCAN_NOT_FOUND')
    // İstemcinin süre sınırı kendi metnidir ("sunucuya ulaşılamadı" değil — inceleme 11)
    expect(serverProblemCode({ success: false, status: 0, code: 'REQUEST_TIMEOUT', timedOut: true })).toBe('timeout')
    expect(serverProblemCode({ success: false, status: 500, code: 'INTERNAL_ERROR' })).toBe('other')
    expect(serverProblemCode({ success: false, status: 200, code: 'DOWNLOAD_FAILED' })).toBe('download')
    // 404 RESOURCE_IMAGE_NOT_FOUND "eski sunucu" SAYILMAZ (kendi kodu var)
    expect(serverProblemCode({ success: false, status: 404, code: 'RESOURCE_IMAGE_NOT_FOUND' })).toBe('RESOURCE_IMAGE_NOT_FOUND')
  })

  it('serverProblemText: bekleme süresi, sınır boyutu, HTTP kodu / neden; tanınmayan kodda sunucunun kendi iletisi', () => {
    expect(serverProblemText({ status: 429, code: 'RATE_LIMITED', retryAfter: 37.2 }, t, 'tr-TR')).toBe('prs.it.srv.RATE_LIMITED.wait(38)')
    expect(serverProblemText({ status: 429, code: 'RESOURCE_IMAGE_BUSY', retry_after_seconds: 2 }, t, 'tr-TR')).toBe('prs.it.srv.RESOURCE_IMAGE_BUSY.wait(2)')
    expect(serverProblemText({ status: 429, code: 'RATE_LIMITED' }, t, 'tr-TR')).toBe('prs.it.srv.RATE_LIMITED')
    expect(serverProblemText({ status: 413, code: 'RESOURCE_IMAGE_TOO_LARGE', max_bytes: 25 * 1024 * 1024 }, t, 'tr-TR')).toBe('prs.it.srv.RESOURCE_IMAGE_TOO_LARGE(25 MB)')
    // Analizin kendi ölçtüğü boyut zaten sınırın üstündeyse sunucu `bytes` da yollar → ileti iki değeri de söyler
    expect(serverProblemText({ status: 413, code: 'RESOURCE_IMAGE_TOO_LARGE', max_bytes: 26214400, bytes: 31457280 }, t, 'tr-TR')).toBe('prs.it.srv.RESOURCE_IMAGE_TOO_LARGE.size(30 MB|25 MB)')
    // Projeksiyon yapılamıyor: nedenine göre metin (bilinmeyen neden → genel metin, hata kodu sızmaz)
    expect(serverProblemText({ status: 409, code: 'RESOURCE_SCAN_NOT_PROJECTABLE', reason: 'algorithm' }, t, 'tr-TR')).toBe('prs.it.srv.RESOURCE_SCAN_NOT_PROJECTABLE.algorithm')
    expect(serverProblemText({ status: 409, code: 'RESOURCE_SCAN_NOT_PROJECTABLE', reason: 'baseline' }, t, 'tr-TR')).toBe('prs.it.srv.RESOURCE_SCAN_NOT_PROJECTABLE.baseline')
    expect(serverProblemText({ status: 409, code: 'RESOURCE_SCAN_NOT_PROJECTABLE', reason: 'yeni' }, t, 'tr-TR')).toBe('prs.it.srv.RESOURCE_SCAN_NOT_PROJECTABLE')
    expect(serverProblemCode({ status: 409, code: 'RESOURCE_SCAN_NOT_PROJECTABLE' })).toBe('RESOURCE_SCAN_NOT_PROJECTABLE')
    expect(serverProblemText({ status: 502, code: 'RESOURCE_IMAGE_FETCH_FAILED', http_status: 404 }, t, 'tr-TR')).toBe('prs.it.srv.RESOURCE_IMAGE_FETCH_FAILED.http(404)')
    expect(serverProblemText({ status: 502, code: 'RESOURCE_IMAGE_FETCH_FAILED' }, t, 'tr-TR')).toBe('prs.it.srv.RESOURCE_IMAGE_FETCH_FAILED')
    expect(serverProblemText({ status: 502, code: 'RESOURCE_IMAGE_FETCH_FAILED', reason: 'CONNECT_TIMEOUT' }, t, 'tr-TR')).toMatch(/^prs\.it\.srv\.RESOURCE_IMAGE_FETCH_FAILED\.reason\(/)
    expect(serverProblemText({ status: 409, code: 'RESOURCE_IMAGE_URL_MASKED' }, t, 'tr-TR')).toBe('prs.it.srv.RESOURCE_IMAGE_URL_MASKED')
    expect(serverProblemText({ status: 500, code: 'X', error: 'Sunucunun kendi açıklayıcı iletisi.' }, t, 'tr-TR')).toBe('Sunucunun kendi açıklayıcı iletisi.')
    expect(serverProblemText({ status: 500 }, t, 'tr-TR')).toBe('prs.it.srv.other')
    expect(serverProblemText({ status: 0 }, t, 'tr-TR')).toBe('prs.it.srv.network')
    expect(serverProblemText({ status: 404 }, t, 'tr-TR')).toBe('prs.it.srv.unsupported')
  })

  it('motor parametre metinleri ve yeni sonuç / ayar metinleri TR + EN dolu (≥ 25 karakter iletiler)', () => {
    const need = ['prs.it.eng.UNSUPPORTED_FORMAT.svg', 'prs.it.eng.UNSUPPORTED_FORMAT.pdf', 'prs.it.eng.UNSUPPORTED_FORMAT.tiff',
      'prs.it.eng.UNSUPPORTED_FORMAT.heic', 'prs.it.eng.UNSUPPORTED_FORMAT.empty', 'prs.it.eng.TOO_LARGE.size', 'prs.it.eng.TOO_MANY_PIXELS.dims',
      'prs.it.eng.DECODE_FAILED.timeout', 'prs.it.eng.BROWSER_UNSUPPORTED.memory', 'prs.it.eng.ENCODE_FAILED.timeout', 'prs.it.eng.ENCODE_FAILED.type',
      'prs.it.set.qualityPngLossless', 'prs.it.set.qualityPngPalette', 'prs.it.set.formatConvertNote']
    for (const k of need) {
      expect(typeof TR[k] === 'string' && TR[k].trim().length >= 25, `TR ${k}`).toBe(true)
      expect(typeof EN[k] === 'string' && EN[k].trim().length >= 25, `EN ${k}`).toBe(true)
    }
    for (const k of ['prs.it.res.larger', 'prs.it.res.noSaving', 'prs.it.cmp.larger']) {
      expect(TR[k] && EN[k], k).toBeTruthy()
    }
    // Küçük resim dolgusu görselin KENDİ arka planıdır; arka plan rengi yalnız saydam → JPEG içindir (motor davranışı)
    expect(EN['prs.it.note.THUMB_PADDED']).toMatch(/own background/)
    expect(EN['prs.it.set.methodHint.thumb']).toMatch(/own background/)
    expect(EN['prs.it.set.backgroundHint']).toMatch(/only/)
  })

  it('engineProblemText / problemLine: her motor kodunun kendi metni; sınır değerleri iletide; bilinmeyen → genel', () => {
    for (const c of engine.ERROR_CODES) expect(engineProblemText(c, {}, t, 'tr-TR')).toMatch(new RegExp(`^prs\\.it\\.eng\\.${c}`))
    expect(engineProblemText('TOO_LARGE', { maxBytes: 25 * 1024 * 1024 }, t, 'tr-TR')).toBe('prs.it.eng.TOO_LARGE(25 MB)')
    expect(engineProblemText('TOO_MANY_PIXELS', { maxPixels: 50_000_000 }, t, 'tr-TR')).toBe('prs.it.eng.TOO_MANY_PIXELS(50)')
    // Motorun gerçek parametreleri (utils/imageEngine/errors.js): algılanan tür, boyut, ölçü, neden — ham kod yazılmaz
    expect(engineProblemText('UNSUPPORTED_FORMAT', { detected: 'svg' }, t, 'tr-TR')).toBe('prs.it.eng.UNSUPPORTED_FORMAT.svg')
    for (const d of ['pdf', 'tiff', 'heic', 'empty']) expect(engineProblemText('UNSUPPORTED_FORMAT', { detected: d }, t, 'tr-TR')).toBe(`prs.it.eng.UNSUPPORTED_FORMAT.${d}`)
    expect(engineProblemText('UNSUPPORTED_FORMAT', { detected: 'unknown' }, t, 'tr-TR')).toBe('prs.it.eng.UNSUPPORTED_FORMAT')
    expect(engineProblemText('TOO_LARGE', { bytes: 30 * 1024 * 1024, maxBytes: 25 * 1024 * 1024 }, t, 'tr-TR')).toBe('prs.it.eng.TOO_LARGE.size(30 MB|25 MB)')
    expect(engineProblemText('TOO_MANY_PIXELS', { width: 12000, height: 9000, maxPixels: 50_000_000, maxSide: 30000 }, t, 'tr-TR'))
      .toBe('prs.it.eng.TOO_MANY_PIXELS.dims(12.000|9.000|50|30.000)')
    expect(engineProblemText('DECODE_FAILED', { reason: 'timeout' }, t, 'tr-TR')).toBe('prs.it.eng.DECODE_FAILED.timeout')
    expect(engineProblemText('DECODE_FAILED', { reason: 'header' }, t, 'tr-TR')).toBe('prs.it.eng.DECODE_FAILED')
    expect(engineProblemText('BROWSER_UNSUPPORTED', { reason: 'memory' }, t, 'tr-TR')).toBe('prs.it.eng.BROWSER_UNSUPPORTED.memory')
    expect(engineProblemText('BROWSER_UNSUPPORTED', { reason: 'worker' }, t, 'tr-TR')).toBe('prs.it.eng.BROWSER_UNSUPPORTED')
    expect(engineProblemText('ENCODE_FAILED', { reason: 'timeout' }, t, 'tr-TR')).toBe('prs.it.eng.ENCODE_FAILED.timeout')
    expect(engineProblemText('ENCODE_FAILED', { reason: 'unsupported_type' }, t, 'tr-TR')).toBe('prs.it.eng.ENCODE_FAILED.type')
    expect(engineProblemText('ENCODE_FAILED', { reason: 'internal', name: 'TypeError' }, t, 'tr-TR')).toBe('prs.it.eng.ENCODE_FAILED')
    expect(engineProblemText('INVALID_OPTIONS', { field: 'resize.width', reason: 'range' }, t, 'tr-TR')).toBe('prs.it.eng.INVALID_OPTIONS')
    expect(engineProblemText('YENI', {}, t, 'tr-TR')).toBe('prs.it.eng.other')
    expect(problemLine({ kind: 'engine', code: 'DECODE_FAILED', params: {} }, t, 'tr-TR')).toBe('prs.it.eng.DECODE_FAILED')
    expect(problemLine({ kind: 'server', res: { status: 401 } }, t, 'tr-TR')).toBe('prs.it.srv.session')
    expect(problemLine({ kind: 'unavailable', reason: 'svg' }, t, 'tr-TR')).toBe('prs.it.na.svg')
    expect(problemLine({ kind: 'unavailable', reason: 'tuhaf' }, t, 'tr-TR')).toBe('prs.it.na.broken')
    expect(problemLine({ kind: 'client', code: 'download' }, t, 'tr-TR')).toBe('prs.it.srv.download')
    expect(problemLine({ kind: 'client', code: 'tooLargeFile', params: { maxBytes: 1024 * 1024 } }, t, 'tr-TR')).toBe('prs.it.eng.TOO_LARGE(1 MB)')
    expect(problemLine(null, t, 'tr-TR')).toBe('')
  })
})

describe('görsel aracı — eşleşme listesi (CSV) ve missing.txt', () => {
  const files = [
    { source: 'https://ornek.example.com/img/hero.jpg', name: 'hero.webp', summary: summary() },
    { source: '=HYPERLINK("http://kotu.example")', name: 'a,b "c".png', summary: summary({ notes: ['KEPT_ORIGINAL', 'SMART_CROP'], savingPct: 0, bytes: 600_000 }) },
    { source: 'adsiz.png', name: 'adsiz.png', summary: resultSummary({ blob: {} }) },
  ]
  it('mappingRows: başlık + satırlar; sayılar gerçek sayı, bilinmeyen boş (0 değil)', () => {
    const rows = mappingRows(files, t)
    expect(rows[0]).toEqual(['prs.it.map.source', 'prs.it.map.file', 'prs.it.map.oldBytes', 'prs.it.map.newBytes', 'prs.it.map.oldDims', 'prs.it.map.newDims',
      'prs.it.map.format', 'prs.it.map.saving', 'prs.it.map.notes', 'prs.it.map.tlsVerified'])
    expect(rows).toHaveLength(4)
    expect(rows[1][1]).toBe('hero.webp')
    expect(rows[1][4]).toBe('3000×2000')
    expect(rows[1][5]).toBe('1200×800')
    expect(rows[1][6]).toBe('WebP')
    expect(rows[2][8]).toBe('prs.it.note.KEPT_ORIGINAL | prs.it.note.SMART_CROP')
    expect(rows[3][4]).toBe('')
    expect(rows[3][6]).toBe('')
  })

  it('mappingCsv: BOM + CRLF; virgül / tırnak kaçışlı; formül başlangıcı etkisizleştirilir; sayı hücresi ham sayı', () => {
    const csv = mappingCsv(files, t)
    expect(csv.charCodeAt(0)).toBe(0xFEFF)
    expect(csv.endsWith('\r\n')).toBe(true)
    const lines = csv.slice(1).trimEnd().split('\r\n')
    expect(lines).toHaveLength(4)
    expect(lines[1]).toContain('hero.webp')
    expect(lines[1]).toMatch(/,600000,150000,/)
    expect(lines[1]).toMatch(/,75,/)
    expect(lines[2]).toContain('"a,b ""c"".png"')
    expect(lines[2].startsWith('=')).toBe(false)            // formül koruması
    expect(lines[2]).not.toMatch(/^"=HYPERLINK/)
    expect(lines[3]).toMatch(/^adsiz\.png,adsiz\.png,,,,,,,,$/)   // bilinmeyenler (aktarım doğrulaması dahil) boş hücre
  })

  it('missingText: BOM + başlık + satırlar + öneri, CRLF', () => {
    const txt = missingText([{ source: 'https://o.example/a.jpg', text: 'Site görseli vermedi.' }], t)
    expect(txt.charCodeAt(0)).toBe(0xFEFF)
    expect(txt.slice(1).split('\r\n')).toEqual(['prs.it.zip.missingHeading', '', '- https://o.example/a.jpg — Site görseli vermedi.', '', 'prs.it.zip.missingAdvice', ''])
  })
})

describe('görsel aracı — duyarlı set ve hazır kod', () => {
  it('responsiveWidths: 480 / 768 / 1280 / 1920\'den doğal genişliği aşmayanlar; hepsi büyükse yalnız doğal genişlik', () => {
    expect([...RESPONSIVE_WIDTHS]).toEqual([480, 768, 1280, 1920])
    expect(responsiveWidths(3000)).toEqual([480, 768, 1280, 1920])
    expect(responsiveWidths(1280)).toEqual([480, 768, 1280])
    expect(responsiveWidths(1000)).toEqual([480, 768, 1000])          // doğal genişlik iki adımın arasında → sete girer
    expect(responsiveWidths(300)).toEqual([300])
    expect(responsiveWidths(null)).toEqual([])
    expect(responsiveWidths('abc')).toEqual([])
  })

  const files = [
    { name: 'hero-1280w.webp', width: 1280, height: 853, type: 'webp' },
    { name: 'hero-480w.webp', width: 480, height: 320, type: 'webp' },
    { name: 'hero-768w.webp', width: 768, height: 512, type: 'webp' },
  ]
  it('pictureSnippet (img): srcset genişliğe göre sıralı, en büyük dosya src, ölçüler, lazy', () => {
    expect(pictureSnippet({ files, alt: 'Kapak', sizes: '(max-width: 768px) 100vw, 50vw' })).toBe(
      '<img src="hero-1280w.webp" srcset="hero-480w.webp 480w, hero-768w.webp 768w, hero-1280w.webp 1280w" sizes="(max-width: 768px) 100vw, 50vw" alt="Kapak" width="1280" height="853" loading="lazy" decoding="async">')
  })

  it('pictureSnippet (picture): source type + yedek img özgün dosya; önek uygulanır', () => {
    expect(pictureSnippet({ files, alt: '', sizes: '', kind: 'picture', prefix: '/img/', fallbackName: 'hero.jpg' })).toBe([
      '<picture>',
      '  <source type="image/webp" srcset="/img/hero-480w.webp 480w, /img/hero-768w.webp 768w, /img/hero-1280w.webp 1280w" sizes="100vw">',
      '  <img src="/img/hero.jpg" alt="" width="1280" height="853" loading="lazy" decoding="async">',
      '</picture>',
    ].join('\n'))
  })

  it('kaçış: alt / sizes / önek içindeki tırnak ve açılı ayraç etiketi kıramaz; boşluklu dosya adı URL-kodlanır', () => {
    const html = pictureSnippet({
      files: [{ name: 'kapak görseli-480w.jpg', width: 480, height: 320, type: 'jpeg' }],
      alt: '"><script>alert(1)</script>', sizes: '100vw" onload="x', prefix: '/a"b/',
    })
    expect(html).not.toContain('<script>')
    expect(html).not.toMatch(/alt=""><script/)
    expect(html).toContain('alt="&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;"')
    expect(html).toContain('sizes="100vw&quot; onload=&quot;x"')
    expect(html).toContain('src="/a%22b/kapak%20g%C3%B6rseli-480w.jpg"')      // önek yol parçası parçası kodlanır (inceleme 7)
    expect(html.match(/"/g).length % 2).toBe(0)
    expect(escapeAttr('a&b<c>"d')).toBe('a&amp;b&lt;c&gt;&quot;d')
    expect(pictureSnippet({ files: [], alt: 'x' })).toBe('')
    expect(pictureSnippet({ files: [{ name: 'x.jpg', width: 'abc' }] })).toBe('')
  })
})

describe('görsel aracı — "not ne olurdu?" görünüm modeli', () => {
  const data = (over = {}) => ({
    algorithm_version: 3, capped: false, applied: 4, ignored: 1,
    before: { grade: 61, letter: 'D', page_bytes: 5_000_000, image_bytes: 3_800_000, load_ms: 4200, large_images: 6,
      scores: { images: { score: 20, lost: 12 }, page_weight: { score: 55, lost: 9 }, load_time: { score: 70, lost: 6 }, caching: { score: 80, lost: 1 } } },
    after: { grade: 78, letter: 'C', page_bytes: 2_600_000, image_bytes: 1_400_000, load_ms: 3100, large_images: 0,
      scores: { images: { score: 92, lost: 1.2 }, page_weight: { score: 81, lost: 3.8 }, load_time: { score: 82, lost: 3.6 }, caching: { score: 80, lost: 1 } } },
    ...over,
  })

  it('önce → sonra, farklar, bileşen satırları (değişenler önce, kazanılan puana göre)', () => {
    const v = projectionView(data())
    expect(v.capped).toBe(false)
    expect(v.before.grade).toBe(61)
    expect(v.after.grade).toBe(78)
    expect(v.after.letter).toBe('C')
    expect(v.gradeDiff).toBe(17)
    expect(v.pageBytesDiff).toBe(-2_400_000)
    expect(v.imageBytesDiff).toBe(-2_400_000)
    expect(v.loadMsDiff).toBe(-1100)
    expect(v.applied).toBe(4)
    expect(v.ignored).toBe(1)
    expect(v.rows.map((r) => r.key)).toEqual(['images', 'page_weight', 'load_time', 'caching'])
    expect(v.rows[0]).toMatchObject({ before: 20, after: 92, gained: 10.8, changed: true, fixed: false })
    // Yükleme tahmini ölçülen sürelerden gelir: sunucu farklı dönse bile "değişti" / "kazanıldı" SAYILMAZ (sunucu kuralı)
    expect(v.rows[2]).toMatchObject({ key: 'load_time', fixed: true, changed: false, gained: null })
    expect(v.rows[3]).toMatchObject({ changed: false, gained: 0, fixed: false })
    expect(v.changedCount).toBe(2)
    expect(v.reused).toEqual([])
    expect(v.reusedLabelKeys).toEqual([])
  })

  it('reused_components: iki tarafta saklı puan kullanılan bileşen "yeniden hesaplanmadı" sayılır; geçersiz adlar atılır', () => {
    const v = projectionView(data({
      reused_components: ['render_blocking', 'render_blocking', 'Bad Key!', 42, 'x'.repeat(41)],
      before: { ...data().before, scores: { ...data().before.scores, render_blocking: { score: 60, lost: 4 } } },
      after: { ...data().after, scores: { ...data().after.scores, render_blocking: { score: 60, lost: 4 } } },
    }))
    expect(v.reused).toEqual(['render_blocking'])
    expect(v.reusedLabelKeys).toEqual(['prs.score.render_blocking'])
    expect(v.rows.find((r) => r.key === 'render_blocking')).toMatchObject({ fixed: true, reused: true, changed: false, gained: null })
    expect(projectionView(data({ reused_components: 'render_blocking' })).reused).toEqual([])
  })

  it('sınırlı (kısmi) analiz işaretlenir; bilinmeyen not null kalır (fark da null — 0 uydurulmaz)', () => {
    expect(projectionView(data({ capped: true })).capped).toBe(true)
    const unknown = projectionView(data({ after: { grade: null, page_bytes: null, scores: {} } }))
    expect(unknown.after.grade).toBe(null)
    expect(unknown.gradeDiff).toBe(null)
    expect(unknown.pageBytesDiff).toBe(null)
    expect(unknown.rows.find((r) => r.key === 'images')).toMatchObject({ after: null, gained: null, changed: false })
    const noCounts = projectionView(data({ applied: undefined, ignored: undefined }))
    expect(noCounts.applied).toBe(null)
    expect(noCounts.ignored).toBe(null)
  })

  it('okunamayan yanıt → null (ekran "hesaplanamadı" der)', () => {
    expect(projectionView(null)).toBe(null)
    expect(projectionView({})).toBe(null)
    expect(projectionView({ before: {} })).toBe(null)
    expect(projectionView('metin')).toBe(null)
  })

  it('projectionImages: bu sekmede işlenenlerin gerçek baytı / ölçüsü / türü; geçersizler atlanır; ≤ 200', () => {
    expect(projectionImages([
      { url: 'https://o.example/a.jpg', summary: summary() },
      { url: 'https://o.example/b.jpg', summary: resultSummary({ blob: {} }) },   // bayt bilinmiyor → gönderilmez
      { url: null, summary: summary() },
    ])).toEqual([{ url: 'https://o.example/a.jpg', bytes: 150_000, width: 1200, height: 800, type: 'webp' }])
    const many = Array.from({ length: PROJECTION_MAX_IMAGES + 20 }, (_, i) => ({ url: `https://o.example/${i}.jpg`, summary: summary() }))
    expect(projectionImages(many)).toHaveLength(PROJECTION_MAX_IMAGES)
    expect(projectionImages(null)).toEqual([])
  })
})

describe('görsel aracı — dışa aktarım sütunları (Excel / PDF görsel sayfası)', () => {
  const tt = (key, ...args) => (args.length ? `${key}(${args.join('|')})` : key)
  const images = { count: 3, bytes: 2_000_000, share_pct: 50, large_count: 2, large_bytes: 1_800_000, potential_saving_bytes: 900_000,
    top: [
      { url: 'https://o.example/a.jpg', bytes: 1_000_000, width: 3000, height: 2000, content_type: 'image/jpeg' },
      { url: 'https://o.example/b.png', bytes: 800_000, width: 1600, height: 900, content_type: 'image/png' },
    ] }

  it('processedCells: işlenen görselde [bayt, kazanç, ölçü, biçim]; işlenmeyende dört BOŞ hücre (asla 0)', () => {
    expect(processedCells(summary(), 'tr-TR')).toEqual([150_000, '%75', '1200×800', 'WebP'])
    expect(processedCells(null, 'tr-TR')).toEqual(['', '', '', ''])
    expect(processedCells(resultSummary({ blob: {} }), 'tr-TR')).toEqual(['', '', '', ''])
  })

  it('işlenen görsel yoksa sayfa DEĞİŞMEZ; varsa dört sütun eklenir, işlenmeyen satırın hücreleri boş kalır', () => {
    const plain = imageSheetRows(images, 4_000_000, tt, 'tr-TR')
    expect(imageSheetRows(images, 4_000_000, tt, 'tr-TR', [])).toEqual(plain)
    expect(imageSheetRows(images, 4_000_000, tt, 'tr-TR', null)).toEqual(plain)
    const processed = [{ url: 'https://o.example/a.jpg', cells: processedCells(summary(), 'tr-TR') }]
    const rows = imageSheetRows(images, 4_000_000, tt, 'tr-TR', processed)
    const head = rows.find((r) => r.includes('prs.it.export.bytes'))
    expect(head).toBeTruthy()
    expect(head.slice(-4)).toEqual(['prs.it.export.bytes', 'prs.it.export.saving', 'prs.it.export.dims', 'prs.it.export.format'])
    const a = rows.find((r) => r.includes('https://o.example/a.jpg'))
    const b = rows.find((r) => r.includes('https://o.example/b.png'))
    expect(a.slice(-4)).toEqual([150_000, '%75', '1200×800', 'WebP'])
    expect(b.slice(-4)).toEqual(['', '', '', ''])
    expect(a.length).toBe(head.length)
    expect(b.length).toBe(head.length)
  })

  it('"en büyük görseller" listesinde olmayan ama bu sekmede işlenen görsel de sayfaya eklenir', () => {
    const processed = [{ url: 'https://o.example/zz.jpg', cells: [40_000, '%60', '800×600', 'JPEG'] }]
    const items = [{ category: 'IMAGE', url: 'https://o.example/zz.jpg', bytes: 100_000, width: 1600, height: 1200, content_type: 'image/jpeg' }]
    const rows = imageSheetRows(images, 4_000_000, tt, 'tr-TR', processed, items)
    const z = rows.find((r) => r.includes('https://o.example/zz.jpg'))
    expect(z).toBeTruthy()
    expect(z.slice(-4)).toEqual([40_000, '%60', '800×600', 'JPEG'])
  })
})
