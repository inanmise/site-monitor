import { test, expect } from '@playwright/test'

/**
 * Görsel motoru (src/utils/imageEngine) — GERÇEK tarayıcıda uçtan uca: gerçek Web Worker, OffscreenCanvas,
 * createImageBitmap ve tarayıcının JPEG / WebP kodlayıcıları. Girdiler sayfada üretilir (tuval → Blob); çıktı gerçek
 * tuvalde çözülüp pikselleri ölçülür. Saf hesaplar (süzgeç, nicemleyici, PNG yazıcı, SSIM, EXIF …) vitest'tedir
 * (src/test/imageEngine*.test.js) — burada yalnız tarayıcıya bağlı olanlar: kodlayıcılar, Worker, iptal, bellek / süre.
 */
const HARNESS = '/e2e/harness/image-engine.html'

// Ağır işler (24 MP, iptal süresi, ana iş parçacığı nabzı) birbirini etkilemesin: bu dosyanın testleri TEK işçide,
// sırayla koşar (yapılandırmadaki fullyParallel'dan çıkar). 'serial' değil: bir test düşünce kalanlar atlanmasın.
// Süre sınırı geniş: CI koşucusu (2 vCPU, yazılımla çizim) yerel makineden birkaç kat yavaştır.
test.describe.configure({ mode: 'default', timeout: 120_000 })

async function open(page, query = '') {
  await page.goto(HARNESS + query)
  await expect(page.locator('#status')).toHaveText('hazır', { timeout: 30000 })
}

test.describe('görsel motoru — gerçek tarayıcı', () => {
  test('destek: iş Web Worker da koşar, WebP kodlanabilir; başlık okuma türü ve ölçüyü verir', async ({ page }) => {
    await open(page)
    const r = await page.evaluate(async () => {
      const { engine, gen } = window.__ie
      const support = await engine.engineSupport()
      const out = { support, infos: {} }
      out.infos.jpeg = await engine.inspectImage(await gen.photo(640, 480))
      out.infos.png = await engine.inspectImage(await gen.logo(300, 200))
      out.infos.webp = await engine.inspectImage(await gen.photo(320, 240, 'image/webp', 0.8))
      out.infos.gif = await engine.inspectImage(gen.animatedGif())
      return out
    })
    expect(r.support).toEqual({ worker: true, webp: true })
    expect(r.infos.jpeg).toMatchObject({ type: 'jpeg', width: 640, height: 480, hasAlpha: false, animated: false })
    expect(r.infos.png).toMatchObject({ type: 'png', width: 300, height: 200, hasAlpha: true, animated: false })
    expect(r.infos.webp).toMatchObject({ type: 'webp', width: 320, height: 240 })
    expect(r.infos.gif).toMatchObject({ type: 'gif', width: 1, height: 1, animated: true })
    expect(r.infos.jpeg.bytes).toBeGreaterThan(1000)
  })

  test('sınırlar ÇÖZMEDEN önce: dev ölçülü başlık, 25 MB üstü dosya, desteklenmeyen tür', async ({ page }) => {
    await open(page)
    const r = await page.evaluate(async () => {
      const { engine, gen, failure } = window.__ie
      // 20000 × 20000 "PNG": yalnız başlık (çözülmeye kalkılsa DECODE_FAILED olurdu)
      const bomb = gen.bytes([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52, 0, 0, 0x4e, 0x20, 0, 0, 0x4e, 0x20, 8, 6, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0x49, 0x44, 0x41, 0x54, 0, 0, 0, 0], 'image/png')
      const t0 = performance.now()
      const out = {
        bombInspect: await failure(engine.inspectImage(bomb)),
        bombProcess: await failure(engine.processImage(bomb, {})),
        bombPreview: await failure(engine.decodeForPreview(bomb)),
        tooLarge: await failure(engine.processImage(new Blob([new Uint8Array(26 * 1024 * 1024)]), {})),
        svg: await failure(engine.processImage(new Blob(['<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"/>'], { type: 'image/svg+xml' }), {})),
        pdf: await failure(engine.inspectImage(new Blob(['%PDF-1.7 ...']))),
        broken: await failure(engine.processImage(gen.bytes([0xff, 0xd8, 0xff, 0xe0, 0, 16, 0x4a, 0x46, 0x49, 0x46, 0, 1, 1, 0, 0, 1, 0, 1, 0, 0,
          0xff, 0xc0, 0, 11, 8, 0, 20, 0, 30, 1, 1, 0x11, 0, 0xff, 0xd9]), {})),
        options: await failure(engine.processImage(bomb, { resize: { method: 'scale', width: 10, height: 10 } })),
      }
      out.ms = performance.now() - t0
      // motor bozuk dosyalardan sonra da çalışır
      const ok = await engine.processImage(await gen.logo(64, 64), {})
      out.okType = ok.type
      return out
    })
    expect(r.bombInspect).toMatchObject({ name: 'EngineError', code: 'TOO_MANY_PIXELS', params: { width: 20000, height: 20000 } })
    expect(r.bombProcess).toMatchObject({ code: 'TOO_MANY_PIXELS' })
    expect(r.bombPreview).toMatchObject({ code: 'TOO_MANY_PIXELS' })
    expect(r.tooLarge).toMatchObject({ code: 'TOO_LARGE', params: { maxBytes: 25 * 1024 * 1024 } })
    expect(r.svg).toMatchObject({ code: 'UNSUPPORTED_FORMAT', params: { detected: 'svg' } })
    expect(r.pdf).toMatchObject({ code: 'UNSUPPORTED_FORMAT', params: { detected: 'pdf' } })
    expect(r.broken).toMatchObject({ code: 'DECODE_FAILED' })               // başlığı geçerli, gövdesi olmayan JPEG
    expect(r.options).toMatchObject({ code: 'INVALID_OPTIONS' })
    expect(r.okType).toBe('png')
    expect(r.ms).toBeLessThan(15000)
  })

  test('yöntemler: scale / fit / cover / thumb ölçüleri; çıktı tarayıcıda açılır; HİÇBİRİ büyütmez', async ({ page }) => {
    await open(page)
    const r = await page.evaluate(async () => {
      const { engine, gen, pixels, sum } = window.__ie
      const src = await gen.photo(1200, 800)
      const go = async (resize, format = 'jpeg') => {
        const res = await engine.processImage(src, { resize, format, quality: 80 })
        const px = await pixels(res.blob)
        return { ...sum(res), decoded: [px.width, px.height] }
      }
      return {
        scaleW: await go({ method: 'scale', width: 600 }),
        scaleH: await go({ method: 'scale', height: 200 }, 'webp'),
        fit: await go({ method: 'fit', width: 500, height: 500 }),
        cover: await go({ method: 'cover', width: 400, height: 400 }, 'png'),
        coverWide: await go({ method: 'cover', width: 900, height: 200 }),
        thumb: await go({ method: 'thumb', width: 150, height: 150 }),
        up: await go({ method: 'scale', width: 5000 }),
        upFit: await go({ method: 'fit', width: 4000, height: 4000 }),
        upCover: await go({ method: 'cover', width: 2000, height: 2000 }),
      }
    })
    const dims = (x) => [x.width, x.height]
    expect(dims(r.scaleW)).toEqual([600, 400])
    expect(dims(r.scaleH)).toEqual([300, 200])
    expect(dims(r.fit)).toEqual([500, 333])
    expect(dims(r.cover)).toEqual([400, 400])
    expect(dims(r.coverWide)).toEqual([900, 200])
    expect(dims(r.thumb)).toEqual([150, 150])
    for (const k of Object.keys(r)) expect(r[k].decoded, k).toEqual(dims(r[k]))            // dosyanın gerçek ölçüsü = bildirilen
    expect(r.scaleW).toMatchObject({ resized: true, cropped: false, type: 'jpeg', blobType: 'image/jpeg' })
    expect(r.scaleH).toMatchObject({ type: 'webp', blobType: 'image/webp' })
    expect(r.cover).toMatchObject({ resized: true, cropped: true, type: 'png', blobType: 'image/png' })
    expect(r.cover.notes).toContain('SMART_CROP')
    // büyütme yok
    expect(dims(r.up)).toEqual([1200, 800])
    expect(r.up.notes).toContain('NOT_UPSCALED')
    expect(r.up.resized).toBe(false)
    expect(dims(r.upFit)).toEqual([1200, 800])
    expect(r.upFit.notes).toContain('NOT_UPSCALED')
    expect(dims(r.upCover)).toEqual([800, 800])                                            // oran tutulur, büyütülmez
    expect(r.upCover.notes).toEqual(expect.arrayContaining(['NOT_UPSCALED', 'SMART_CROP']))
    for (const k of Object.keys(r)) { expect(r[k].original).toMatchObject({ type: 'jpeg', width: 1200, height: 800 }); expect(r[k].bytes).toBe(r[k].blobSize) }
  })

  test('PNG logo → PALETLİ PNG: çok daha küçük, saydamlık korunur, renkler yerinde', async ({ page }) => {
    await open(page)
    const r = await page.evaluate(async () => {
      const { engine, gen, pixels, pngInfo, sum } = window.__ie
      const src = await gen.logo(600, 400)
      const res = await engine.processImage(src, { format: 'keep' })
      const a = await pixels(src)
      const b = await pixels(res.blob)
      let worstAlpha = 0
      let worstColor = 0
      let transparentKept = true
      for (let i = 0; i < a.data.length; i += 4) {
        worstAlpha = Math.max(worstAlpha, Math.abs(a.data[i + 3] - b.data[i + 3]))
        if (a.data[i + 3] === 0 && b.data[i + 3] !== 0) transparentKept = false
        if (a.data[i + 3] === 255) for (let c = 0; c < 3; c++) worstColor = Math.max(worstColor, Math.abs(a.data[i + c] - b.data[i + c]))
      }
      return { res: sum(res), src: src.size, info: await pngInfo(res.blob), srcInfo: await pngInfo(src), worstAlpha, worstColor, transparentKept,
        corner: b.at(2, 2), body: b.at(180, 200) }
    })
    console.log(`logo 600×400: tarayıcı PNG si ${r.src} B → paletli ${r.res.bytes} B (${r.res.colors} renk, %${r.res.savingPct} tasarruf); en kötü saydamlık farkı ${r.worstAlpha}, renk farkı ${r.worstColor}`)
    expect(r.srcInfo.colorType).toBe(6)                                                     // tarayıcının PNG si: gerçek renk + saydamlık
    expect(r.info.colorType).toBe(3)                                                        // motorun çıktısı: paletli
    expect(r.info.chunks).toEqual(['IHDR', 'PLTE', 'tRNS', 'IDAT', 'IEND'])
    expect(r.info.paletteSize).toBeLessThanOrEqual(256)
    expect(r.res.colors).toBe(r.info.paletteSize)
    expect(r.res.bytes).toBeLessThan(r.src * 0.6)
    expect(r.res.savingPct).toBeGreaterThan(40)
    expect(r.res.kept).toBe(false)
    expect(r.transparentKept).toBe(true)
    expect(r.corner).toEqual([0, 0, 0, 0])
    expect(r.body[3]).toBe(255)
    expect(r.worstAlpha).toBeLessThan(48)
    expect(r.worstColor).toBeLessThan(40)
    expect(r.res.original).toMatchObject({ type: 'png', hasAlpha: true })
  })

  test('fotoğraf JPEG, otomatik kalite: daha küçük, benzerlik eşiğin üstünde; görsel gerçekten benziyor', async ({ page }) => {
    await open(page)
    const r = await page.evaluate(async () => {
      const { engine, gen, pixels, sum } = window.__ie
      const src = await gen.photo(1280, 853, 'image/jpeg', 0.97)
      const t0 = performance.now()
      const res = await engine.processImage(src, { format: 'keep', quality: 'auto' })
      const ms = performance.now() - t0
      const a = await pixels(src)
      const b = await pixels(res.blob)
      let sq = 0
      for (let i = 0; i < a.data.length; i += 4) for (let c = 0; c < 3; c++) { const d = a.data[i + c] - b.data[i + c]; sq += d * d }
      // benzerlik kaliteyle TEK YÖNLÜ artmalı (ikili arama buna dayanır) — gerçek kodlayıcıda ölçülür
      const ladder = []
      for (const q of [30, 50, 70, 90]) { const x = await engine.processImage(src, { format: 'jpeg', quality: q }); ladder.push({ q, bytes: x.bytes, similarity: x.similarity, quality: x.quality }) }
      const k = await import('/src/utils/imageEngine/search.js')
      return { res: sum(res), src: src.size, rmse: Math.sqrt(sq / (a.width * a.height * 3)), ms, ladder, target: k.SIMILARITY_TARGET, range: k.QUALITY_RANGE.jpeg }
    })
    console.log(`fotoğraf 1280×853 JPEG (${Math.round(r.src / 1024)} KB) → otomatik q${r.res.quality} ${Math.round(r.res.bytes / 1024)} KB, benzerlik ${r.res.similarity}, `
      + `RMSE ${r.rmse.toFixed(2)}, ${Math.round(r.ms)} ms · merdiven ${r.ladder.map((x) => `q${x.q}=${Math.round(x.bytes / 1024)}KB/${x.similarity}`).join(' ')}`)
    const [lo, hi] = r.range
    expect(r.res.type).toBe('jpeg')
    expect(r.res.kept).toBe(false)
    expect(r.res.bytes).toBeLessThan(r.src * 0.75)
    expect(r.res.quality).toBeGreaterThanOrEqual(lo)
    expect(r.res.quality).toBeLessThanOrEqual(hi)
    // eşik tutturuldu — ya da arama aralığın üst ucunda bitti (en iyi çaba)
    expect(r.res.similarity >= r.target || r.res.quality === hi).toBe(true)
    expect(r.res.similarity).toBeGreaterThan(0.95)
    expect(r.rmse).toBeLessThan(6)                                                          // gerçek piksellerde küçük fark
    expect(r.res.notes).toEqual([])
    expect(r.ms).toBeLessThan(30000)
    // sabit kalite merdiveni: kalite arttıkça dosya büyür ve benzerlik artar (ölçüm kaliteyle tutarlı)
    expect(r.ladder.map((x) => x.quality)).toEqual([30, 50, 70, 90])
    for (let i = 1; i < r.ladder.length; i++) {
      expect(r.ladder[i].bytes).toBeGreaterThan(r.ladder[i - 1].bytes)
      expect(r.ladder[i].similarity).toBeGreaterThan(r.ladder[i - 1].similarity)
    }
    expect(r.ladder[0].similarity).toBeLessThan(r.res.similarity)
  })

  test('GAMA DOĞRU küçültme: 1 piksellik dama 2× küçülünce gerçek tuvalde ≈ 188 (128 değil)', async ({ page }) => {
    await open(page)
    const r = await page.evaluate(async () => {
      const { engine, gen, pixels } = window.__ie
      const res = await engine.processImage(await gen.checker(256, 256), { resize: { method: 'scale', width: 128 }, format: 'png', quality: 100 })
      const px = await pixels(res.blob)
      let min = 255
      let max = 0
      for (let y = 8; y < 120; y++) for (let x = 8; x < 120; x++) { const v = px.at(x, y)[0]; min = Math.min(min, v); max = Math.max(max, v) }
      // tarayıcının kendi (gama kodlu) küçültmesi karşılaştırma için
      const naive = window.__ie.canvasOf(128, 128)
      const bm = await createImageBitmap(await gen.checker(256, 256))
      const nx = naive.getContext('2d')
      nx.imageSmoothingQuality = 'high'
      nx.drawImage(bm, 0, 0, 128, 128)
      return { dims: [px.width, px.height], min, max, center: px.at(64, 64), browser: Array.from(nx.getImageData(64, 64, 1, 1).data) }
    })
    expect(r.dims).toEqual([128, 128])
    expect(r.min).toBeGreaterThanOrEqual(186)
    expect(r.max).toBeLessThanOrEqual(190)
    expect(r.center).toEqual([188, 188, 188, 255])
    expect(r.browser[0]).toBeLessThan(140)                                                   // tarayıcı gama kodlu ortalar (≈ 128)
  })

  test('saydam kenar: küçültmede hale YOK (kenar pikseli saf kırmızı, yalnız saydamlığı kısmi)', async ({ page }) => {
    await open(page)
    const r = await page.evaluate(async () => {
      const { engine, gen, pixels, sum } = window.__ie
      const out = {}
      for (const format of ['png', 'webp']) {
        const res = await engine.processImage(await gen.edge(400, 120), { resize: { method: 'scale', width: 100 }, format, quality: format === 'png' ? 100 : 95 })
        const px = await pixels(res.blob)
        let partial = 0
        let worst = 0
        let opaqueRed = 0
        let clear = 0
        for (let y = 0; y < px.height; y++) for (let x = 0; x < px.width; x++) {
          const [rr, g, b, a] = px.at(x, y)
          if (a === 0) { clear += 1; continue }
          if (a < 255) partial += 1
          if (a === 255 && rr > 250) opaqueRed += 1
          if (a >= 48) worst = Math.max(worst, 255 - rr, g, b)       // çok düşük saydamlıkta tuvalin önçarpım yuvarlaması oynar
        }
        out[format] = { res: sum(res), partial, worst, opaqueRed, clear, total: px.width * px.height }
      }
      return out
    })
    expect(r.png.res.width).toBe(100)
    expect(r.png.partial).toBeGreaterThan(0)                                                 // saydamlık kenarda ortalandı
    expect(r.png.worst).toBeLessThanOrEqual(6)                                               // renk kararmadı / kaymadı
    expect(r.png.opaqueRed).toBeGreaterThan(r.png.total * 0.45)
    expect(r.png.clear).toBeGreaterThan(r.png.total * 0.4)
    expect(r.webp.partial).toBeGreaterThan(0)
    expect(r.webp.worst).toBeLessThanOrEqual(24)                                             // kayıplı kodlama payı
    expect(r.webp.res.type).toBe('webp')
  })

  test('saydam PNG → JPEG: seçilen zemin rengine bindirilir', async ({ page }) => {
    await open(page)
    const r = await page.evaluate(async () => {
      const { engine, gen, pixels, sum } = window.__ie
      const res = await engine.processImage(await gen.logo(300, 200), { format: 'jpeg', quality: 92, background: '#102080' })
      const px = await pixels(res.blob)
      return { res: sum(res), corner: px.at(3, 3) }
    })
    expect(r.res.type).toBe('jpeg')
    expect(r.res.notes).toEqual(['ALPHA_FLATTENED'])
    expect(Math.abs(r.corner[0] - 0x10)).toBeLessThan(8)
    expect(Math.abs(r.corner[1] - 0x20)).toBeLessThan(8)
    expect(Math.abs(r.corner[2] - 0x80)).toBeLessThan(8)
    expect(r.corner[3]).toBe(255)
  })

  test('hedef boyut: önce kalite, yetmezse ölçü; ulaşılamayan hedef bildirilir', async ({ page }) => {
    await open(page)
    const r = await page.evaluate(async () => {
      const { engine, gen, pixels, sum } = window.__ie
      const src = await gen.photo(1600, 1066, 'image/jpeg', 0.95)
      // hedef = kalite 65'in boyutu: kalite 90'dan başlayan iş yalnız kaliteyi düşürerek sığar (taban 50'ye inmeden)
      const normal = await engine.processImage(src, { format: 'jpeg', quality: 90 })
      const at65 = await engine.processImage(src, { format: 'jpeg', quality: 65 })
      const byQuality = await engine.processImage(src, { format: 'jpeg', quality: 90, targetBytes: at65.bytes })
      const byDims = await engine.processImage(src, { format: 'jpeg', targetBytes: 12 * 1024 })
      const dimsPx = await pixels(byDims.blob)
      const webp = await engine.processImage(src, { format: 'webp', targetBytes: 20 * 1024 })
      const png = await engine.processImage(await gen.logo(600, 400), { format: 'png', targetBytes: 2500 })
      const impossible = await engine.processImage(src, { format: 'jpeg', targetBytes: 150 })
      return { normal: sum(normal), at65: sum(at65), byQuality: sum(byQuality), byDims: sum(byDims), dimsPx: [dimsPx.width, dimsPx.height], webp: sum(webp), png: sum(png),
        impossible: sum(impossible) }
    })
    console.log(`hedef boyut: q90 ${Math.round(r.normal.bytes / 1024)} KB → hedef ${Math.round(r.at65.bytes / 1024)} KB: q${r.byQuality.quality} ${Math.round(r.byQuality.bytes / 1024)} KB · `
      + `12 KB hedefi: ${r.byDims.width}×${r.byDims.height} q${r.byDims.quality} ${r.byDims.bytes} B · WebP 20 KB: ${r.webp.width}×${r.webp.height} q${r.webp.quality} ${r.webp.bytes} B · `
      + `PNG 2500 B: ${r.png.width}×${r.png.height} ${r.png.colors} renk ${r.png.bytes} B · 150 B: ${r.impossible.width}×${r.impossible.height} ${r.impossible.bytes} B`)
    expect(r.byQuality.bytes).toBeLessThanOrEqual(r.at65.bytes)
    expect([r.byQuality.width, r.byQuality.height]).toEqual([1600, 1066])                   // ölçü aynı: yalnız kalite düştü
    expect(r.byQuality.quality).toBeLessThan(90)
    expect(r.byQuality.quality).toBeGreaterThanOrEqual(60)                                  // sığan EN YÜKSEK kalite (taban değil)
    expect(r.byQuality.notes).toEqual([])
    expect(r.byDims.bytes).toBeLessThanOrEqual(12 * 1024)
    expect(r.byDims.width).toBeLessThan(1600)
    expect(r.byDims.resized).toBe(true)
    expect(r.dimsPx).toEqual([r.byDims.width, r.byDims.height])
    expect(Math.abs(r.byDims.width / r.byDims.height - 1600 / 1066)).toBeLessThan(0.03)
    expect(r.byDims.notes).toEqual([])
    expect(r.webp.bytes).toBeLessThanOrEqual(20 * 1024)
    expect(r.webp.type).toBe('webp')
    expect(r.png.bytes).toBeLessThanOrEqual(2500)
    expect(r.png.type).toBe('png')
    expect(r.impossible.notes).toContain('TARGET_NOT_REACHED')
    expect(r.impossible.bytes).toBeGreaterThan(150)
    expect(Math.min(r.impossible.width, r.impossible.height)).toBeGreaterThanOrEqual(16)
  })

  test('WebP ve "en küçük biçim": fotoğrafta kayıplı biçim, saydam logoda saydamlığı koruyan biçim', async ({ page }) => {
    await open(page)
    const r = await page.evaluate(async () => {
      const { engine, gen, pixels, sum } = window.__ie
      const photo = await gen.photo(1000, 700, 'image/png')
      const webp = await engine.processImage(photo, { format: 'webp' })
      const jpeg = await engine.processImage(photo, { format: 'jpeg' })
      const png = await engine.processImage(photo, { format: 'png' })
      const smallest = await engine.processImage(photo, { format: 'smallest' })
      const logo = await gen.logo(500, 300)
      const logoSmallest = await engine.processImage(logo, { format: 'smallest' })
      const lp = await pixels(logoSmallest.blob)
      const wp = await pixels(webp.blob)
      return { webp: sum(webp), jpeg: sum(jpeg), png: sum(png), smallest: sum(smallest), logoSmallest: sum(logoSmallest), logoCorner: lp.at(1, 1), logoBytes: logo.size,
        webpDims: [wp.width, wp.height], photoBytes: photo.size }
    })
    console.log(`fotoğraf 1000×700 (PNG ${Math.round(r.photoBytes / 1024)} KB): JPEG q${r.jpeg.quality} ${Math.round(r.jpeg.bytes / 1024)} KB (${r.jpeg.similarity}) · `
      + `WebP q${r.webp.quality} ${Math.round(r.webp.bytes / 1024)} KB (${r.webp.similarity}) · PNG ${r.png.colors ?? 'gerçek renk'} ${Math.round(r.png.bytes / 1024)} KB (${r.png.similarity}) · `
      + `en küçük → ${r.smallest.type} ${Math.round(r.smallest.bytes / 1024)} KB · logo 500×300 (${r.logoBytes} B) → ${r.logoSmallest.type} ${r.logoSmallest.bytes} B`)
    expect(r.webp).toMatchObject({ type: 'webp', mime: 'image/webp', blobType: 'image/webp', kept: false })
    expect(r.webpDims).toEqual([1000, 700])
    expect(r.webp.similarity).toBeGreaterThan(0.95)
    expect(r.webp.quality).toBeGreaterThanOrEqual(60)
    expect(r.webp.quality).toBeLessThanOrEqual(90)
    expect(r.webp.bytes).toBeLessThan(r.photoBytes / 3)
    expect(['jpeg', 'webp']).toContain(r.smallest.type)
    expect(r.smallest.bytes).toBeLessThanOrEqual(Math.min(r.webp.bytes, r.jpeg.bytes) + 16)
    expect(r.smallest.bytes).toBeLessThan(r.png.bytes)
    expect(['png', 'webp']).toContain(r.logoSmallest.type)                                  // saydam: JPEG aday değil
    expect(r.logoCorner[3]).toBe(0)
    expect(r.logoSmallest.bytes).toBeLessThan(r.logoBytes)
    expect(r.logoSmallest.original.hasAlpha).toBe(true)
  })

  test('PNG: fotoğraf titreşimli palete iner ve ortalama rengi korur; yumuşak geçiş kayıpsız kalabilir', async ({ page }) => {
    await open(page)
    const r = await page.evaluate(async () => {
      const { engine, gen, pixels, pngInfo, sum, canvasOf, toBlob } = window.__ie
      const photo = await gen.photo(800, 600, 'image/png')
      const forced = await engine.processImage(photo, { format: 'png', quality: 99 })
      const a = await pixels(photo)
      const b = await pixels(forced.blob)
      // 16 × 16 blok ortalamaları (titreşim hatayı komşulara dağıtır: bloklar kaynağa yakın kalmalı)
      let worstBlock = 0
      let sumBlock = 0
      let blocks = 0
      for (let by = 0; by + 16 <= 600; by += 16) for (let bx = 0; bx + 16 <= 800; bx += 16) for (let c = 0; c < 3; c++) {
        let sa = 0
        let sb = 0
        for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) { const i = ((by + y) * 800 + bx + x) * 4 + c; sa += a.data[i]; sb += b.data[i] }
        const d = Math.abs(sa - sb) / 256
        worstBlock = Math.max(worstBlock, d)
        sumBlock += d
        blocks += 1
      }
      const meanBlock = sumBlock / blocks
      // düz doğrusal geçiş: gerçek renk (kayıpsız) paletten küçük çıkar
      const c = canvasOf(512, 256)
      const cx = c.getContext('2d')
      const g = cx.createLinearGradient(0, 0, 512, 256)
      g.addColorStop(0, '#102030'); g.addColorStop(1, '#f0d0a0')
      cx.fillStyle = g
      cx.fillRect(0, 0, 512, 256)
      const grad = await toBlob(c, 'image/jpeg', 1)
      const gradPng = await engine.processImage(grad, { format: 'png' })
      return { forced: sum(forced), info: await pngInfo(forced.blob), worstBlock, meanBlock, photoBytes: photo.size, gradPng: sum(gradPng), gradInfo: await pngInfo(gradPng.blob) }
    })
    console.log(`fotoğraf 800×600 PNG ${Math.round(r.photoBytes / 1024)} KB → ${r.forced.colors} renkli palet ${Math.round(r.forced.bytes / 1024)} KB, benzerlik ${r.forced.similarity}, `
      + `16×16 blok ortalaması farkı: en kötü ${r.worstBlock.toFixed(2)}, ortalama ${r.meanBlock.toFixed(2)} · geçiş 512×256 → PNG renk türü ${r.gradInfo.colorType} ${r.gradPng.bytes} B [${r.gradPng.notes}]`)
    expect(r.info.colorType).toBe(3)
    expect(r.forced.colors).toBeGreaterThan(200)
    expect(r.forced.bytes).toBeLessThan(r.photoBytes * 0.6)
    expect(r.worstBlock).toBeLessThan(16)                                     // titreşim yerel ortalamayı korur (en kötü blok; ölçülen 7,6)
    expect(r.meanBlock).toBeLessThan(1.5)                                     // bloklar ortalamada ~1 düzey içinde
    expect(r.forced.similarity).toBeGreaterThan(0.7)
    expect(r.forced.similarity).toBeLessThan(1)
    expect([2, 3]).toContain(r.gradInfo.colorType)
    if (r.gradInfo.colorType === 2) expect(r.gradPng.notes).toContain('TRUECOLOR_KEPT')
  })

  test('üstveri: varsayılan atılır; istenirse JPEG / WebP ye en küçük EXIF, PNG ye metin; EXIF yönü uygulanır', async ({ page }) => {
    await open(page)
    const r = await page.evaluate(async () => {
      const { engine, gen, meta, pixels, pngInfo, sum } = window.__ie
      const enc = (s) => new TextEncoder().encode(s)
      const rational = (...pairs) => { const b = new Uint8Array(pairs.length * 8); const dv = new DataView(b.buffer); pairs.forEach(([n, d], i) => { dv.setUint32(i * 8, n, true); dv.setUint32(i * 8 + 4, d, true) }); return b }
      const gps = [{ tag: 1, type: 2, count: 2, value: enc('N\u0000') }, { tag: 2, type: 5, count: 3, value: rational([41, 1], [0, 1], [4932, 100]) },
        { tag: 3, type: 2, count: 2, value: enc('E\u0000') }, { tag: 4, type: 5, count: 3, value: rational([28, 1], [58, 1], [4212, 100]) }]
      const tiff = meta.buildExif({ copyrightBytes: enc('(c) 2026 SiteMonitor'), creation: '2026:11:14 09:30:00', gps })
      const plain = new Uint8Array(await (await gen.photo(900, 600)).arrayBuffer())
      const src = new Blob([meta.insertJpegExif(plain, tiff)], { type: 'image/jpeg' })
      const read = async (blob, type) => meta.readMetadata(new Uint8Array(await blob.arrayBuffer()), type)
      const all = { copyright: true, creation: true, location: true }
      const resize = { method: 'scale', width: 450 }
      const stripped = await engine.processImage(src, { resize })
      const keptJ = await engine.processImage(src, { resize, preserve: all })
      const onlyC = await engine.processImage(src, { resize, preserve: { copyright: true, creation: false, location: false } })
      const keptW = await engine.processImage(src, { resize, format: 'webp', preserve: all })
      const keptP = await engine.processImage(src, { resize, format: 'png', preserve: all })
      const mj = await read(keptJ.blob, 'jpeg')
      const mw = await read(keptW.blob, 'webp')
      const wpx = await pixels(keptW.blob)                                    // EXIF eklenmiş WebP hâlâ açılıyor
      const jpx = await pixels(keptJ.blob)
      // EXIF yönü 6: tarayıcı 90° döndürür
      const orient = Uint8Array.from([0x49, 0x49, 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0])
      const rotated = new Blob([meta.insertJpegExif(new Uint8Array(await (await gen.photo(300, 200)).arrayBuffer()), orient)], { type: 'image/jpeg' })
      const rotInfo = await engine.inspectImage(rotated)
      const rot = await engine.processImage(rotated, { quality: 80 })
      const rpx = await pixels(rot.blob)
      // Renk profili: tuvalin yazdığı sRGB profilli JPEG "dönüştürüldü" sayılmaz; Display P3 profilli olan sayılır
      const p3c = document.createElement('canvas')
      p3c.width = 64
      p3c.height = 64
      const p3x = p3c.getContext('2d', { colorSpace: 'display-p3' })
      let p3 = null
      if (p3x.getContextAttributes?.().colorSpace === 'display-p3') {
        p3x.fillStyle = 'color(display-p3 0.9 0.2 0.1)'
        p3x.fillRect(0, 0, 64, 64)
        const p3blob = await window.__ie.toBlob(p3c, 'image/jpeg', 0.92)
        p3 = sum(await engine.processImage(p3blob, { format: 'png', quality: 100 }))
      }
      // tarayıcının yazdığı sRGB profilli WebP (VP8X + ICCP) de "dönüştürüldü" sayılmaz
      const srgbWebp = sum(await engine.processImage(await gen.photo(200, 150, 'image/webp', 0.9), { format: 'png', quality: 100 }))
      return {
        p3,
        srgbWebp,
        srcMeta: (await read(src, 'jpeg')).present,
        stripped: sum(stripped), strippedMeta: (await read(stripped.blob, 'jpeg')).present,
        keptJ: sum(keptJ), mj: { ...mj.present, copyright: mj.copyright, creation: mj.creation, gpsTags: mj.gps?.map((e) => e.tag) },
        onlyC: sum(onlyC), onlyCMeta: (await read(onlyC.blob, 'jpeg')).present,
        keptW: sum(keptW), mw: mw.present, webpDims: [wpx.width, wpx.height], jpegDims: [jpx.width, jpx.height],
        keptP: sum(keptP), pngText: (await pngInfo(keptP.blob)).text,
        rotInfo, rot: sum(rot), rotDims: [rpx.width, rpx.height],
      }
    })
    expect(r.srcMeta).toEqual({ copyright: true, creation: true, location: true })
    expect(r.stripped.original.metadata).toEqual({ copyright: true, creation: true, location: true })
    expect(r.strippedMeta).toEqual({ copyright: false, creation: false, location: false })
    expect(r.stripped.notes).toEqual([])                                                     // sRGB profili: COLOR_PROFILE_CONVERTED yok
    if (r.p3) expect(r.p3.notes).toContain('COLOR_PROFILE_CONVERTED')                        // "Display P3 Gamut with sRGB Transfer"
    expect(r.srgbWebp.notes).not.toContain('COLOR_PROFILE_CONVERTED')
    expect(r.srgbWebp.original.type).toBe('webp')
    expect(r.keptJ.notes).toEqual(['METADATA_PRESERVED'])
    expect(r.mj).toEqual({ copyright: '(c) 2026 SiteMonitor', creation: '2026:11:14 09:30:00', location: true, gpsTags: [1, 2, 3, 4] })
    expect(r.keptJ.bytes).toBe(r.keptJ.blobSize)
    expect(r.jpegDims).toEqual([450, 300])
    expect(r.onlyCMeta).toEqual({ copyright: true, creation: false, location: false })
    expect(r.keptW.type).toBe('webp')
    expect(r.keptW.notes).toEqual(['METADATA_PRESERVED'])
    expect(r.mw).toEqual({ copyright: true, creation: true, location: true })
    expect(r.webpDims).toEqual([450, 300])
    expect(r.keptP.notes).toEqual(['METADATA_PRESERVED', 'METADATA_NOT_PRESERVED'])           // PNG de konum taşınmaz
    expect(r.pngText).toEqual(['Copyright\u0000(c) 2026 SiteMonitor', 'Creation Time\u00002026-11-14T09:30:00'])
    // yön
    expect(r.rotInfo).toMatchObject({ width: 200, height: 300 })                             // başlık okuma dönmüş ölçüyü verir
    expect(r.rot.notes).toContain('ORIENTATION_APPLIED')
    expect([r.rot.width, r.rot.height]).toEqual([200, 300])
    expect(r.rotDims).toEqual([200, 300])
    expect(r.rot.original).toMatchObject({ width: 200, height: 300 })
  })

  test('İPTAL: çalışan iş anında kesilir (CANCELLED) ve sonraki iş çalışır; bekleyen iş de iptal edilebilir', async ({ page }) => {
    await open(page)
    const r = await page.evaluate(async () => {
      const { engine, gen, failure, sum } = window.__ie
      const big = await gen.photo(4000, 3000, 'image/jpeg', 0.9)             // 12 MP: PNG e çevirmek saniyeler sürer
      const small = await gen.logo(200, 120)
      const ac = new AbortController()
      const phases = []
      let onStarted
      const started = new Promise((res) => { onStarted = res })
      const t0 = performance.now()
      const running = failure(engine.processImage(big, { format: 'png', quality: 99 }, { signal: ac.signal, onProgress: (p) => { phases.push(p); onStarted() } }))
      const acQueued = new AbortController()
      const queued = failure(engine.processImage(small, {}, { signal: acQueued.signal }))
      const next = engine.processImage(small, {})
      // İş GERÇEKTEN başlasın (Worker açıldı, ilk ilerleme geldi), sonra ortasında iptal edilir — sabit bekleme yavaş
      // makinede Worker daha açılmadan dolabilirdi. 12 MP → paletli PNG işi ilk ilerlemeden sonra saniyeler sürer.
      await Promise.race([started, new Promise((res) => setTimeout(res, 20000))])
      await new Promise((res) => setTimeout(res, 300))
      acQueued.abort()
      ac.abort()
      const tAbort = performance.now()
      const first = await running
      const cancelMs = performance.now() - tAbort
      const second = await queued
      const third = sum(await next)
      // art arda iptal + yeni iş: motor ayakta
      for (let i = 0; i < 3; i++) {
        const c = new AbortController()
        const p = failure(engine.processImage(big, { format: 'webp' }, { signal: c.signal }))
        await new Promise((res) => setTimeout(res, 60))
        c.abort()
        if ((await p)?.code !== 'CANCELLED') return { loop: i }
      }
      const after = sum(await engine.processImage(big, { resize: { method: 'scale', width: 800 }, quality: 80 }))
      return { first, second, third, cancelMs, startedMs: tAbort - t0, phases: [...new Set(phases)], after }
    })
    console.log(`iptal: 12 MP iş ${Math.round(r.startedMs)} ms sonra iptal edildi, söz ${r.cancelMs.toFixed(1)} ms içinde reddedildi; görülen aşamalar ${r.phases.join(', ')}`)
    expect(r.loop).toBeUndefined()
    expect(r.first).toMatchObject({ name: 'EngineError', code: 'CANCELLED' })
    expect(r.second).toMatchObject({ code: 'CANCELLED' })
    expect(r.cancelMs).toBeLessThan(1000)                                    // Worker sonlandırılır: işin bitmesi beklenmez
    expect(r.phases.length).toBeGreaterThan(0)                               // iş gerçekten başlamıştı
    expect(r.third).toMatchObject({ type: 'png', kept: false })
    expect(r.after).toMatchObject({ type: 'jpeg', width: 800, height: 600 })
  })

  test('24 MP kaynak: makul sürede biter, sayfa yanıt vermeye devam eder (iş Worker da)', async ({ page }) => {
    test.setTimeout(240000)
    await open(page)
    const r = await page.evaluate(async () => {
      const { engine, gen, pixels, heartbeat, sum } = window.__ie
      const src = await gen.photo(6000, 4000, 'image/jpeg', 0.9)
      const info = await engine.inspectImage(src)
      const hb = heartbeat()
      const t0 = performance.now()
      const resized = await engine.processImage(src, { resize: { method: 'fit', width: 1920, height: 1920 }, format: 'jpeg' })
      const resizeMs = performance.now() - t0
      const t1 = performance.now()
      const full = await engine.processImage(src, { format: 'jpeg' })
      const fullMs = performance.now() - t1
      const t2 = performance.now()
      const thumb = await engine.processImage(src, { resize: { method: 'cover', width: 320, height: 320 }, format: 'webp' })
      const thumbMs = performance.now() - t2
      // "en küçük biçim": fotoğrafta umutsuz paletli PNG adayı bant kestirimiyle elenir (tamamı kodlanmaz)
      const t3 = performance.now()
      const smallest = await engine.processImage(src, { format: 'smallest' })
      const smallestMs = performance.now() - t3
      const beat = hb.stop()
      const px = await pixels(resized.blob)
      return { info, src: src.size, resized: sum(resized), resizeMs, full: sum(full), fullMs, thumb: sum(thumb), thumbMs, smallest: sum(smallest), smallestMs, beat,
        decoded: [px.width, px.height] }
    })
    console.log(`24 MP (${Math.round(r.src / 1024)} KB): fit 1920 → ${Math.round(r.resized.bytes / 1024)} KB q${r.resized.quality} ${Math.round(r.resizeMs)} ms · `
      + `tam boy otomatik → ${Math.round(r.full.bytes / 1024)} KB q${r.full.quality} kept=${r.full.kept} ${Math.round(r.fullMs)} ms · `
      + `cover 320 → ${Math.round(r.thumb.bytes / 1024)} KB ${Math.round(r.thumbMs)} ms · en küçük biçim → ${r.smallest.type} ${Math.round(r.smallest.bytes / 1024)} KB ${Math.round(r.smallestMs)} ms · `
      + `en uzun ana iş parçacığı aralığı ${Math.round(r.beat.worst)} ms`)
    expect(r.info).toMatchObject({ width: 6000, height: 4000 })
    expect([r.resized.width, r.resized.height]).toEqual([1920, 1280])
    expect(r.decoded).toEqual([1920, 1280])
    expect(r.resized.similarity).toBeGreaterThan(0.9)
    expect(r.resizeMs).toBeLessThan(60000)
    expect(r.fullMs).toBeLessThan(120000)
    expect(r.thumbMs).toBeLessThan(60000)
    expect([r.full.width, r.full.height]).toEqual([6000, 4000])
    expect([r.thumb.width, r.thumb.height]).toEqual([320, 320])
    expect(['jpeg', 'webp']).toContain(r.smallest.type)
    expect(r.smallest.bytes).toBeLessThanOrEqual(r.full.bytes)
    expect(r.smallestMs).toBeLessThan(120000)
    expect(r.beat.worst).toBeLessThan(1500)                                  // sayfa donmadı
    expect(r.beat.beats).toBeGreaterThan(20)
  })

  test('"asla daha kötü": motorun kendi çıktısı yeniden işlenince ÖZGÜN dosya (aynı Blob) döner', async ({ page }) => {
    await open(page)
    const r = await page.evaluate(async () => {
      const { engine, gen, sum } = window.__ie
      const first = await engine.processImage(await gen.logo(400, 260), {})
      const again = await engine.processImage(first.blob, {})
      const photo = await engine.processImage(await gen.photo(800, 600, 'image/jpeg', 0.95), { quality: 60 })
      const again2 = await engine.processImage(photo.blob, { quality: 90 })     // daha yüksek kalite büyütürdü
      const converted = await engine.processImage(first.blob, { format: 'jpeg', quality: 95 })
      return { first: sum(first), again: sum(again), same: again.blob === first.blob, again2: sum(again2), same2: again2.blob === photo.blob, converted: sum(converted) }
    })
    expect(r.again.kept).toBe(true)
    expect(r.same).toBe(true)
    expect(r.again.notes).toEqual(['KEPT_ORIGINAL'])
    expect(r.again.savingPct).toBe(0)
    expect(r.again.bytes).toBe(r.first.bytes)
    expect(r.again.type).toBe('png')
    expect(r.again2.kept).toBe(true)
    expect(r.same2).toBe(true)
    expect(r.converted.kept).toBe(false)                                     // AÇIKÇA başka biçim: dönüştürülür
    expect(r.converted.type).toBe('jpeg')
  })

  test('thumb: düz zeminde nesne tümüyle kalır, zemin rengiyle dolgu; animasyonlu GIF in ilk karesi alınır', async ({ page }) => {
    await open(page)
    const r = await page.evaluate(async () => {
      const { engine, gen, pixels, sum } = window.__ie
      const obj = await gen.object(900, 300)
      const thumb = await engine.processImage(obj, { resize: { method: 'thumb', width: 200, height: 200 }, format: 'png', quality: 100 })
      const cover = await engine.processImage(obj, { resize: { method: 'cover', width: 200, height: 200 }, format: 'png', quality: 100 })
      const tp = await pixels(thumb.blob)
      const isRed = (p) => p[0] > 170 && p[1] < 80 && p[2] < 80
      let redRow = 0
      // 80. satır: nesnenin (kırmızı) içinde, ortadaki mavi kutunun üstünde
      for (let x = 0; x < 200; x++) if (isRed(tp.at(x, 80))) redRow += 1
      const cp = await pixels(cover.blob)
      let coverRed = 0
      for (let x = 0; x < 200; x++) if (isRed(cp.at(x, 60))) coverRed += 1
      const left = await engine.processImage(await gen.detail(1200, 300, 'left'), { resize: { method: 'cover', width: 150, height: 150 }, format: 'png', quality: 100 })
      const right = await engine.processImage(await gen.detail(1200, 300, 'right'), { resize: { method: 'cover', width: 150, height: 150 }, format: 'png', quality: 100 })
      const busy = async (res) => { const p = await pixels(res.blob); let n = 0; for (let i = 0; i < p.data.length; i += 4) if (Math.abs(p.data[i] - 0xd0) > 12 || Math.abs(p.data[i + 2] - 0xd8) > 12) n += 1; return n / (p.width * p.height) }
      const gif = await engine.processImage(gen.animatedGif(), { format: 'png' })
      const gifKeep = await engine.processImage(gen.animatedGif(), {})
      return { thumb: sum(thumb), corner: tp.at(3, 3), bottom: tp.at(100, 196), redRow, cover: sum(cover), coverRed, leftBusy: await busy(left), rightBusy: await busy(right),
        gif: sum(gif), gifKeep: sum(gifKeep) }
    })
    expect([r.thumb.width, r.thumb.height]).toEqual([200, 200])
    expect(r.thumb.notes).toContain('THUMB_PADDED')
    expect(r.corner).toEqual([0xf4, 0xf4, 0xf4, 255])                         // dolgu görselin kendi zemin rengi
    expect(r.bottom).toEqual([0xf4, 0xf4, 0xf4, 255])
    expect(r.redRow).toBeGreaterThan(150)                                     // nesnenin tamamı (630 px) 200 px e sığdı
    expect(r.redRow).toBeLessThan(195)                                        // iki yanında zemin payı var
    expect(r.cover.notes).toContain('SMART_CROP')
    expect(r.cover.notes).not.toContain('THUMB_PADDED')
    // cover nesneyi KESER (300 px lik pencere 630 px lik nesnenin içinde): satır neredeyse boydan boya kırmızı —
    // akıllı kırpma nesnenin bir kenarını (en çok ayrıntı) çerçevede tutabilir, o yüzden tam 200 şart değil
    expect(r.coverRed).toBeGreaterThanOrEqual(185)
    expect(r.coverRed).toBeGreaterThan(r.redRow)
    expect(r.leftBusy).toBeGreaterThan(0.6)                                   // akıllı kırpma ayrıntılı tarafı seçti
    expect(r.rightBusy).toBeGreaterThan(0.6)
    expect(r.gif).toMatchObject({ type: 'png', width: 1, height: 1, kept: false })
    expect(r.gif.notes).toContain('ANIMATION_DROPPED')
    expect(r.gif.original).toMatchObject({ type: 'gif', animated: true })
    expect(r.gifKeep).toMatchObject({ kept: true, type: 'gif', mime: 'image/gif' })   // küçülmüyor: animasyonuyla özgün kalır
  })

  test('önizleme çözmesi: ImageBitmap (yön uygulanmış), maxSide ile küçük; ilerleme aşamaları sözleşmedeki adlar', async ({ page }) => {
    await open(page)
    const r = await page.evaluate(async () => {
      const { engine, gen } = window.__ie
      const src = await gen.photo(1600, 1200)
      const a = await engine.decodeForPreview(src)
      const b = await engine.decodeForPreview(src, { maxSide: 400 })
      const out = { a: [a.width, a.height, a instanceof ImageBitmap], b: [b.width, b.height] }
      a.close()
      b.close()
      const calls = []
      await engine.processImage(src, { resize: { method: 'scale', width: 400 }, format: 'jpeg' }, { onProgress: (phase, f) => calls.push([phase, f]) })
      out.phases = [...new Set(calls.map((c) => c[0]))]
      out.range = calls.every((c) => c[1] >= 0 && c[1] <= 1)
      out.count = calls.length
      return out
    })
    expect(r.a).toEqual([1600, 1200, true])
    expect(r.b).toEqual([400, 300])
    expect(r.phases).toEqual(['decode', 'resize', 'search'])
    expect(r.range).toBe(true)
    expect(r.count).toBeGreaterThan(5)
  })

  test('Worker yoksa ana iş parçacığı yedeği aynı sonucu verir; iptal araya girer', async ({ page }) => {
    await open(page, '?noworker=1')
    const r = await page.evaluate(async () => {
      const { engine, gen, pixels, pngInfo, failure, sum, heartbeat } = window.__ie
      const support = await engine.engineSupport()
      const logo = await engine.processImage(await gen.logo(300, 200), {})
      const big = await gen.photo(2000, 1500)                                  // nabız ölçümünden ÖNCE üretilir (tuval çizimi ana iş parçacığını tutar)
      const hb = heartbeat()
      const photo = await engine.processImage(big, { resize: { method: 'cover', width: 500, height: 500 }, format: 'webp' })
      const beat = hb.stop()
      const px = await pixels(photo.blob)
      const ac = new AbortController()
      const p = failure(engine.processImage(await gen.photo(3000, 2000), { format: 'png', quality: 99 }, { signal: ac.signal }))
      setTimeout(() => ac.abort(), 150)
      const cancelled = await p
      const after = await engine.processImage(await gen.logo(100, 60), {})
      return { support, logo: sum(logo), logoInfo: await pngInfo(logo.blob), photo: sum(photo), dims: [px.width, px.height], beat, cancelled, after: sum(after) }
    })
    console.log(`Worker yok: 2000×1500 → cover 500 WebP ${r.photo.bytes} B; ana iş parçacığının en uzun aralığı ${Math.round(r.beat.worst)} ms`)
    expect(r.support.worker).toBe(false)
    expect(r.support.webp).toBe(true)
    expect(r.logo.type).toBe('png')
    expect(r.logoInfo.colorType).toBe(3)
    expect(r.photo).toMatchObject({ type: 'webp', width: 500, height: 500, cropped: true })
    expect(r.dims).toEqual([500, 500])
    expect(r.beat.worst).toBeLessThan(1500)                                   // ana iş parçacığı araya nefes koyar (şeritler küçük)
    expect(r.beat.beats).toBeGreaterThan(5)
    expect(r.cancelled).toMatchObject({ code: 'CANCELLED' })
    expect(r.after.type).toBe('png')
  })
})
