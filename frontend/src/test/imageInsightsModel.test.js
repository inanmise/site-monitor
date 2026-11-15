import { describe, it, expect } from 'vitest'
import {
  BANNER_MAX_WIDTH, HOW_STEPS, INSIGHT_RECS, analysisSummary, aspectText, bitsPerPixel, compressionRatio, formatGains, howWeDid,
  insightRecommendations, insightRows,
} from '../components/page/resourcescan/imagetool/imageInsightsModel.js'
import { resultSummary } from '../components/page/resourcescan/imagetool/imageToolModel.js'
import { TR as tr } from '../i18n/tr.js'
import { EN as en } from '../i18n/en.js'

const t = (k, ...a) => String(tr[k] ?? `!!${k}`).replace(/\{(\d+)\}/g, (_, i) => String(a[Number(i)]))

/** Motorun dönüşüne benzeyen sonuç: 2400×1600 JPEG foto → 1920 genişliğe küçültülmüş WebP. */
function engineResult(over = {}) {
  return {
    blob: null, bytes: 180_000, width: 1920, height: 1280, type: 'webp', savingPct: 80, kept: false, resized: true, cropped: false,
    quality: 74, colors: null, similarity: 0.986, notes: [],
    original: { bytes: 900_000, width: 2400, height: 1600, type: 'jpeg', hasAlpha: false, animated: false, metadata: {} },
    analysis: {
      input: { bitDepth: 8, colorModel: 'rgb', ppi: { x: 72, y: 72 }, jpeg: { quality: 92, subsampling: '4:2:0', progressive: false } },
      output: { bitDepth: 8, colorModel: 'rgb', ppi: null, ppiChecked: true, jpeg: null },
      colors: { count: 4096, atLeast: true },
      candidates: [
        { type: 'jpeg', bytes: 240_000, quality: 76, colors: null, similarity: 0.985 },
        { type: 'webp', bytes: 180_000, quality: 74, colors: null, similarity: 0.986 },
        { type: 'png', bytes: 2_100_000, quality: null, colors: null, similarity: 1 },
      ],
      svgCandidate: false, banding: null,
    },
    ...over,
  }
}

describe('görsel aracı derin karşılaştırma — model', () => {
  it('analysisSummary: yalnız bilinen alanlar, aday ≤ 5, bozuk değer null; eski motor (alan yok) → null', () => {
    expect(analysisSummary(null)).toBeNull()
    expect(analysisSummary('x')).toBeNull()
    const s = analysisSummary({
      input: { bitDepth: 'x', colorModel: 'hsl', ppi: { x: 'a', y: 1 }, jpeg: { quality: 90, subsampling: '4:1:1', progressive: 'yes' } },
      candidates: [...Array(9)].map((_, i) => ({ type: i === 0 ? 'gif' : 'jpeg', bytes: i * 10 })),
      colors: { count: 12 }, svgCandidate: 'true', banding: 'other',
    })
    expect(s.input).toEqual({ bitDepth: null, colorModel: null, ppi: null, ppiChecked: false,
      jpeg: { quality: 90, subsampling: null, progressive: null }, paletteSize: null, lossless: null, subsampling: null })
    expect(s.qualityMode).toBeNull()
    expect(s.avifPossible).toBeNull()
    expect(s.resampled).toBeNull()
    expect(analysisSummary({ qualityMode: 'manual', resampled: true, sharpened: false }))
      .toMatchObject({ qualityMode: 'manual', resampled: true, sharpened: false })
    expect(analysisSummary({ qualityMode: 'x' }).qualityMode).toBeNull()
    // 'fixed' (inceleme 2026-11-15): AVIF benzerliği ölçülemedi — arama yapılmadı
    expect(analysisSummary({ qualityMode: 'fixed' }).qualityMode).toBe('fixed')
    expect(s.output).toBeNull()
    expect(s.candidates).toHaveLength(4)   // ilk 5'ten gif atıldı
    expect(s.colors).toEqual({ count: 12, atLeast: false })
    expect(s.svgCandidate).toBe(false)
    expect(s.banding).toBeNull()
    expect(resultSummary({ bytes: 1 }).analysis).toBeNull()
  })

  it('aspectText / bitsPerPixel / compressionRatio: basit oran, ondalıklı oran, bilinmeyen —', () => {
    expect(aspectText(1920, 1080, 'tr')).toBe('16:9')
    expect(aspectText(1000, 1000, 'tr')).toBe('1:1')
    expect(aspectText(1921, 1080, 'en')).toBe('1.78:1')
    expect(aspectText(0, 10, 'tr')).toBe('—')
    expect(aspectText(null, 10, 'tr')).toBe('—')
    expect(bitsPerPixel(1000, 100, 10)).toBe(8)
    expect(bitsPerPixel(null, 100, 10)).toBeNull()
    expect(bitsPerPixel(10, 0, 10)).toBeNull()
    expect(compressionRatio(300, 10, 10, false)).toBe(1)
    expect(compressionRatio(100, 10, 10, true)).toBe(4)
    expect(compressionRatio(0, 10, 10, false)).toBeNull()
  })

  it('insightRows: önce → sonra; değişen satır işaretli, PPI yoksa "Kayıtlı değil", kalite tahmini, alt örnekleme', () => {
    const rows = insightRows(resultSummary(engineResult()), t, 'tr')
    const by = Object.fromEntries(rows.map((r) => [r.key, r]))
    expect(rows.map((r) => r.key)).toEqual(['size', 'dims', 'aspect', 'pixels', 'ppi', 'bpp', 'compression', 'color', 'quality', 'subsampling'])
    expect(by.dims.before).toBe('2.400 × 1.600')
    expect(by.dims.after).toBe('1.920 × 1.280')
    expect(by.dims.changed).toBe(true)
    expect(by.aspect.before).toBe('3:2')
    expect(by.aspect.changed).toBe(false)
    expect(by.pixels.before).toBe('3,84 MP')
    expect(by.ppi.before).toBe('72 PPI')
    // WebP çıktısında yoğunluk alanı YOK (motor yazamaz) — kaynakta yoğunluk varken "kayıtlı değil" denmez (2026-11-15)
    expect(by.ppi.after).toBe('Bu biçimde yoğunluk alanı yok')
    // JPEG çıktısında başlık okunduysa ve yoğunluk yoksa "Kayıtlı değil"; başlık okunmadıysa (ppiChecked yok) "—"
    const jpegOut = engineResult({ type: 'jpeg' })
    expect(Object.fromEntries(insightRows(resultSummary(jpegOut), t, 'tr').map((r) => [r.key, r])).ppi.after).toBe('Kayıtlı değil')
    const unchecked = engineResult({ type: 'jpeg' })
    unchecked.analysis = { ...unchecked.analysis, output: { ...unchecked.analysis.output, ppiChecked: false } }
    expect(Object.fromEntries(insightRows(resultSummary(unchecked), t, 'tr').map((r) => [r.key, r])).ppi.after).toBe('—')
    // korunan JPEG çıktısı yoğunluğu taşır → değer aynen
    const kept300 = engineResult({ type: 'jpeg' })
    kept300.analysis = { ...kept300.analysis, input: { ...kept300.analysis.input, ppi: { x: 300, y: 300 } },
      output: { ...kept300.analysis.output, ppi: { x: 300, y: 300 } } }
    const k300 = Object.fromEntries(insightRows(resultSummary(kept300), t, 'tr').map((r) => [r.key, r])).ppi
    expect([k300.before, k300.after, k300.changed]).toEqual(['300 PPI', '300 PPI', false])
    // kaynakta yoğunluk yoksa WebP çıktısı için "alan yok" denmez (söylenecek bir kayıp yok)
    const noDensity = engineResult()
    noDensity.analysis = { ...noDensity.analysis, input: { ...noDensity.analysis.input, ppi: null } }
    expect(Object.fromEntries(insightRows(resultSummary(noDensity), t, 'tr').map((r) => [r.key, r])).ppi.after).not.toBe('Bu biçimde yoğunluk alanı yok')
    // sıkıştırma oranı iki tarafta tek taban (kaynak saydam değil → 3 kanal)
    expect(by.compression.after).toBe('1:41')
    // boyut bilinmiyorsa "—"
    const noBytes = resultSummary(engineResult())
    noBytes.bytes = null
    expect(Object.fromEntries(insightRows(noBytes, t, 'tr').map((r) => [r.key, r])).size.after).toBe('—')
    expect(by.bpp.before).toBe('1,88')
    expect(by.compression.before).toBe('1:12,8')
    expect(by.color.before).toBe('RGB · 8 bit')
    expect(by.quality.before).toBe('≈ 92 (tahmini)')
    expect(by.quality.after).toBe('74')
    expect(by.subsampling.before).toBe('4:2:0')
    expect(by.subsampling.after).toBe('—')
    expect(JSON.stringify(rows)).not.toMatch(/!!|undefined|NaN/)
  })

  it('insightRows: analiz yoksa (eski motor) analiz satırları "—", değişim null; korunan özgünde sonra = önce', () => {
    const sum = resultSummary(engineResult({ analysis: undefined }))
    const by = Object.fromEntries(insightRows(sum, t, 'tr').map((r) => [r.key, r]))
    expect(by.ppi.before).toBe('—')
    expect(by.ppi.changed).toBeNull()
    expect(by.color.before).toBe('—')
    expect(by.quality.before).toBe('—')                     // girdi kalitesi bilinmiyor
    expect(by.quality.after).toBe('74')                     // çıktı kalitesi motorun kendi seçimi
    const kept = resultSummary(engineResult({ kept: true, type: 'jpeg', bytes: 900_000, width: 2400, height: 1600, resized: false, quality: null }))
    const k = Object.fromEntries(insightRows(kept, t, 'tr').map((r) => [r.key, r]))
    expect(k.quality.after).toBe(k.quality.before)
    expect(k.subsampling.after).toBe('4:2:0')
    expect(insightRows(null, t, 'tr')).toEqual([])
  })

  it('formatGains: boyuta göre artan, seçilen işaretli, özgünden büyük "larger"; tek aday → boş', () => {
    const g = formatGains(resultSummary(engineResult()))
    expect(g.map((x) => x.type)).toEqual(['webp', 'jpeg', 'png'])
    expect(g[0]).toMatchObject({ chosen: true, pct: 80, larger: false })
    expect(g[1]).toMatchObject({ chosen: false, pct: 73.3 })
    expect(g[2]).toMatchObject({ larger: true, pct: 0 })
    const one = engineResult()
    one.analysis.candidates = one.analysis.candidates.slice(0, 1)
    expect(formatGains(resultSummary(one))).toEqual([])
    const kept = resultSummary(engineResult({ kept: true }))
    expect(formatGains(kept).some((x) => x.chosen)).toBe(false)
    // seçilen satır SONUCUN boyutuyla (hedef boyut / eklenen üstveri sonrası); hedefin altındaki aday işaretli
    const fitted = engineResult({ bytes: 150_000, quality: 60 })
    fitted.analysis.candidates[0].belowTarget = true
    const fg = formatGains(resultSummary(fitted))
    expect(fg.find((x) => x.chosen)).toMatchObject({ type: 'webp', bytes: 150_000, quality: 60 })
    expect(fg.find((x) => x.type === 'jpeg').belowTarget).toBe(true)
    expect(fg.find((x) => x.chosen).belowTarget).toBe(false)
  })

  it('insightRecommendations: afiş genişliği, yeniden kayıt, yeni nesil, SVG, bantlaşma, yüksek kalite', () => {
    const wide = resultSummary(engineResult({ width: 2400, height: 1600, resized: false, type: 'jpeg', quality: 88 }))
    wide.analysis.candidates = [{ type: 'jpeg', bytes: 240_000, quality: 88 }]
    // otomatik kipte 85'in üstü benzerlik hedefi için seçildi → "yüksek kalite" önerisi yok
    expect(insightRecommendations(wide).map((r) => r.code)).not.toContain('QUALITY_HIGH')
    wide.analysis.qualityMode = 'manual'
    const codes = insightRecommendations(wide).map((r) => r.code)
    expect(codes).toEqual(['NEXT_GEN', 'BANNER_WIDTH', 'QUALITY_HIGH', 'JPEG_RESAVE'])
    expect(insightRecommendations(wide).find((r) => r.code === 'BANNER_WIDTH').params).toEqual([2400, BANNER_MAX_WIDTH])
    expect(insightRecommendations(wide).find((r) => r.code === 'JPEG_RESAVE').params).toEqual([92])

    const content = resultSummary(engineResult({ width: 1500, height: 1000 }))
    expect(insightRecommendations(content).map((r) => r.code)).toEqual(['CONTENT_WIDTH'])   // WebP denendi → NEXT_GEN yok

    const logo = engineResult({ type: 'png', width: 200, height: 80, bytes: 4000, quality: null, colors: 12, resized: false })
    logo.original = { ...logo.original, type: 'png', width: 200, height: 80, bytes: 9000 }
    logo.analysis = { ...logo.analysis, candidates: [{ type: 'png', bytes: 4000, colors: 12 }], svgCandidate: true, banding: 'palette' }
    expect(insightRecommendations(resultSummary(logo)).map((r) => r.code)).toEqual(['SVG', 'BANDING'])
    expect(insightRecommendations(null)).toEqual([])
    // tarayıcı WebP kodlayamıyorsa "yeni nesil biçim" önerilmez
    const noWebp = resultSummary(engineResult({ type: 'jpeg', notes: ['WEBP_UNAVAILABLE'] }))
    noWebp.analysis.candidates = [{ type: 'jpeg', bytes: 240_000, quality: 76 }]
    expect(insightRecommendations(noWebp).map((r) => r.code)).not.toContain('NEXT_GEN')
    for (const r of [...insightRecommendations(wide), ...insightRecommendations(resultSummary(logo))]) expect(INSIGHT_RECS).toContain(r.code)
  })

  it('AVIF (2026-11-15): özet AVIF adayını ve av1C alt örneklemesini okur; öneri / adım / kalite satırı AVIF ölçeğinde', () => {
    const avif = engineResult({ type: 'avif', bytes: 120_000, quality: 62 })
    avif.analysis = {
      ...avif.analysis,
      output: { bitDepth: 8, colorModel: 'rgb', ppi: null, ppiChecked: false, jpeg: null, subsampling: '4:2:0' },
      candidates: [...avif.analysis.candidates, { type: 'avif', bytes: 120_000, quality: 62, colors: null, similarity: 0.985 }],
      avifPossible: true, qualityMode: 'auto',
    }
    const sum = resultSummary(avif)
    expect(sum.analysis.output.subsampling).toBe('4:2:0')
    expect(sum.analysis.avifPossible).toBe(true)
    expect(sum.analysis.candidates.map((c) => c.type)).toEqual(['jpeg', 'webp', 'png', 'avif'])
    const steps = howWeDid(sum)
    expect(steps.map((s) => s.code)).toEqual(['LOCAL', 'RESIZED', 'QUALITY_SEARCH', 'AVIF_ENCODER', 'METADATA'])
    expect(steps.find((s) => s.code === 'QUALITY_SEARCH').params).toEqual([62, 'auto', 'avif'])
    expect(steps.find((s) => s.code === 'AVIF_ENCODER').params).toEqual(['4:2:0'])
    for (const s of steps) expect(HOW_STEPS).toContain(s.code)
    const by = Object.fromEntries(insightRows(sum, t, 'tr').map((r) => [r.key, r]))
    expect(by.quality.after).toBe('62 (AVIF ölçeği)')
    expect(by.subsampling.after).toBe('4:2:0')
    expect(by.ppi.after).toBe('Bu biçimde yoğunluk alanı yok')
    expect(insightRecommendations(sum).map((r) => r.code)).not.toContain('NEXT_GEN')

    // JPEG çıktı: AVIF bu görselde mümkündü ama denenmedi → öneri WebP + AVIF; denendiyse ya da not varsa AVIF önerilmez
    const jpeg = engineResult({ type: 'jpeg', quality: 76 })
    jpeg.analysis = { ...jpeg.analysis, candidates: [{ type: 'jpeg', bytes: 240_000, quality: 76 }], avifPossible: true }
    expect(insightRecommendations(resultSummary(jpeg)).find((r) => r.code === 'NEXT_GEN').params).toEqual(['webp', 'avif'])
    jpeg.analysis.avifPossible = false
    expect(insightRecommendations(resultSummary(jpeg)).find((r) => r.code === 'NEXT_GEN').params).toEqual(['webp'])
    const webpOut = engineResult()
    webpOut.analysis = { ...webpOut.analysis, avifPossible: true }
    expect(insightRecommendations(resultSummary(webpOut)).find((r) => r.code === 'NEXT_GEN').params).toEqual(['avif'])
    for (const n of ['AVIF_UNAVAILABLE', 'AVIF_TOO_LARGE', 'AVIF_NOT_MEASURED', 'AVIF_TIME_LIMIT']) {
      const x = engineResult({ notes: [n] })
      x.analysis = { ...x.analysis, avifPossible: true }
      expect(insightRecommendations(resultSummary(x)).map((r) => r.code), n).not.toContain('NEXT_GEN')
    }
    // AVIF platformu yok (eski motor / ana iş parçacığı): alan null → AVIF hiç önerilmez
    expect(insightRecommendations(resultSummary(engineResult())).map((r) => r.code)).not.toContain('NEXT_GEN')
  })

  it('howWeDid: yalnız geçilen adımlar; korunan özgünde silinmeyen üstveri için cümle yok', () => {
    const steps = howWeDid(resultSummary(engineResult()))
    expect(steps.map((s) => s.code)).toEqual(['LOCAL', 'RESIZED', 'QUALITY_SEARCH', 'BROWSER_ENCODER', 'METADATA'])
    expect(steps.find((s) => s.code === 'BROWSER_ENCODER').params).toEqual([null, 'webp'])
    expect(steps.find((s) => s.code === 'METADATA').params).toEqual(['stripped'])

    const kept = howWeDid(resultSummary(engineResult({ kept: true })))
    expect(kept.map((s) => s.code)).toEqual(['LOCAL', 'KEPT'])
    const keptStripped = howWeDid(resultSummary(engineResult({ kept: true, notes: ['METADATA_STRIPPED', 'KEPT_ORIGINAL'] })))
    expect(keptStripped.at(-1)).toEqual({ code: 'METADATA', params: ['removed'] })

    const png = howWeDid(resultSummary(engineResult({ type: 'png', colors: 64, resized: false, notes: ['METADATA_PRESERVED', 'COLOR_PROFILE_CONVERTED'] })))
    expect(png.map((s) => s.code)).toEqual(['LOCAL', 'PNG_PALETTE', 'METADATA', 'COLOR_PROFILE'])
    expect(png.find((s) => s.code === 'METADATA').params).toEqual(['kept'])
    const lossless = howWeDid(resultSummary(engineResult({ type: 'png', colors: null, resized: false })))
    expect(lossless.find((s) => s.code === 'PNG_LOSSLESS').params).toEqual([true])
    const losslessResized = howWeDid(resultSummary(engineResult({ type: 'png', colors: null, resized: true })))
    expect(losslessResized.find((s) => s.code === 'PNG_LOSSLESS').params).toEqual([false])
    const rounded = howWeDid(resultSummary(engineResult({ type: 'png', colors: null, resized: false, notes: ['ALPHA_COLORS_ROUNDED'] })))
    expect(rounded.find((s) => s.code === 'PNG_LOSSLESS').params).toEqual([false])
    // motor bayrakları: netleştirme, kalite kipi, kırpma
    const sharp = engineResult({ cropped: true })
    sharp.analysis = { ...sharp.analysis, resampled: true, sharpened: true, qualityMode: 'target' }
    const ss = howWeDid(resultSummary(sharp))
    expect(ss.map((s) => s.code).slice(0, 3)).toEqual(['LOCAL', 'CROPPED', 'RESIZED'])
    expect(ss.find((s) => s.code === 'RESIZED').params).toEqual([1920, 1280, true])
    expect(ss.find((s) => s.code === 'QUALITY_SEARCH').params).toEqual([74, 'target', 'webp'])   // 3. = biçim (AVIF metni ayrılır)
    // AVIF sabit kalite (tarayıcı AVIF açamadı): adımın kipi 'fixed' — "ikili aramayla bulundu" denmesin
    const fixed = engineResult({ type: 'avif', quality: 68, notes: ['AVIF_NOT_MEASURED'] })
    fixed.analysis = { ...fixed.analysis, qualityMode: 'fixed' }
    expect(howWeDid(resultSummary(fixed)).find((s) => s.code === 'QUALITY_SEARCH').params).toEqual([68, 'fixed', 'avif'])
    const sameSize = engineResult({ resized: false })
    sameSize.analysis = { ...sameSize.analysis, resampled: false }
    expect(howWeDid(resultSummary(sameSize)).map((s) => s.code)).not.toContain('RESIZED')
    for (const s of [...steps, ...png, ...keptStripped]) expect(HOW_STEPS).toContain(s.code)
  })

  it('her öneri / adım / satır metni iki dilde var', () => {
    const keys = [
      ...INSIGHT_RECS.filter((c) => !['JPEG_RESAVE', 'BANDING'].includes(c)).map((c) => `prs.it.ins.rec.${c}`),
      'prs.it.ins.rec.JPEG_RESAVE', 'prs.it.ins.rec.JPEG_RESAVE_Q', 'prs.it.ins.rec.BANDING_BITS', 'prs.it.ins.rec.BANDING_PALETTE',
      ...HOW_STEPS.map((c) => `prs.it.ins.how.${c}`),
      'prs.it.ins.how.QUALITY_SEARCH_NONE', 'prs.it.ins.how.BROWSER_ENCODER_SS', 'prs.it.ins.how.BROWSER_ENCODER_WEBP',
      'prs.it.ins.how.METADATA_KEPT', 'prs.it.ins.how.METADATA_REMOVED', 'prs.it.ins.how.RESIZED_PLAIN', 'prs.it.ins.how.QUALITY_MANUAL',
      'prs.it.ins.how.QUALITY_TARGET', 'prs.it.ins.how.PNG_LOSSLESS_PROCESSED', 'prs.it.ins.belowTarget',
      'prs.it.ins.rec.NEXT_GEN_WEBP', 'prs.it.ins.rec.NEXT_GEN_AVIF', 'prs.it.ins.how.AVIF_ENCODER_SS', 'prs.it.ins.how.QUALITY_TARGET_AVIF',
      'prs.it.ins.how.QUALITY_FIXED_AVIF',
      'prs.it.ins.qualityAvif', 'prs.it.ins.ppiNoField',
      ...['size', 'dims', 'aspect', 'pixels', 'ppi', 'bpp', 'compression', 'color', 'quality', 'subsampling'].map((k) => `prs.it.ins.row.${k}`),
      ...['gray', 'gray-alpha', 'rgb', 'rgba', 'palette', 'cmyk'].map((k) => `prs.it.ins.model.${k}`),
    ]
    for (const k of keys) {
      expect(tr[k], k).toBeTruthy()
      expect(en[k], k).toBeTruthy()
    }
  })
})
