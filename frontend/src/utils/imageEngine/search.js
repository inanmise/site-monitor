// Görsel motoru — karar yardımcıları (saf): kalite araması, hedef boyut araması, "en küçük biçim" seçimi, biçim
// çözümü, "asla daha kötü" kuralı. Kodlayıcı dışarıdan verilir (testte sahte kodlayıcı) — her arama KODLAMA SAYISIYLA
// sınırlıdır.

/**
 * Otomatik kalitenin tutturması gereken benzerlik (ssim.js `similarity`): bunun üstü "görsel olarak eşdeğer".
 * Kalibrasyon (2026-11-14, Chromium kodlayıcıları, 1400 px'e indirilmiş 6 gerçek fotoğraf): tipik ayrıntılı fotoğrafta
 * JPEG kalite ≈ 73–77'ye, yumuşak fotoğrafta ≈ 60'a (taban), ince dokulu / düşük kontrastlı görselde aralığın üst
 * ucuna (90) denk gelir; WebP aynı benzerliğe ≈ 8–12 puan yüksek kalitede ulaşır.
 */
export const SIMILARITY_TARGET = 0.984
/**
 * Otomatik kalite arama aralığı (tarayıcı kodlayıcısının 1–100 ölçeği). Taban 60: altında düz geçişlerde blok /
 * bantlanma görünür olur ve SSIM düz bölgedeki bu bozulmaya az duyarlıdır. Tavan 90: üstünde dosya hızla büyür
 * (kalite 95 ≈ kalite 90'ın 1,5 katı), görünür kazanç yoktur.
 */
export const QUALITY_RANGE = Object.freeze({ jpeg: Object.freeze([60, 90]), webp: Object.freeze([60, 90]) })
/** Hedef boyut kipinde kalitenin inebileceği taban (altında ÖLÇÜ küçültülür: küçük ama temiz görsel, büyük ama bloklu olandan iyidir). */
export const TARGET_QUALITY_FLOOR = 50
/** Bir kalite aramasında en çok kodlama. */
export const MAX_SEARCH_ENCODES = 8
/** Hedef boyut kipinde en çok ölçü küçültme adımı. */
export const MAX_DIM_STEPS = 6
/**
 * Palet hatası bunun üstündeyse PNG gerçek renkte bırakılır (TRUECOLOR_KEPT). quantize.js `mse` birimi (titreşimden
 * önceki ortalama kare hata). Kalibrasyon: gerçek fotoğraflar 256 renkte 5e-5 … 3,5e-4 verir (palet kabul — TinyPNG'nin
 * yaptığı); 6e-4 ≈ bir fotoğrafın ~100 renge inmiş hâli: 256 renkle bile bu kadar bozulan görsel palete indirilmez.
 */
export const PALETTE_MAX_MSE = 6e-4
/** Hedef boyut kipinde PNG için denenen palet boyları (kalite merdiveni). */
export const PNG_COLOR_LADDER = Object.freeze([256, 128, 64, 32, 16])

/**
 * En düşük "geçen" kaliteyi arar (ikili arama; benzerliğin kaliteyle arttığı varsayılır).
 * @param {(q: number) => Promise<{ similarity: number }>} evalQ kodlar + ölçer; sonucu olduğu gibi geri verilir (+ `q`)
 * @returns {Promise<object>} seçilen değerlendirme (`q`, `similarity`, …) + `encodes`
 *   Hiçbir kalite eşiği geçmezse aralığın ÜST ucu döner (en iyi çaba).
 */
export async function searchQuality(evalQ, lo, hi, threshold, maxEncodes = MAX_SEARCH_ENCODES) {
  let a = lo
  let b = hi
  let best = null
  let encodes = 0
  let top = null
  const run = async (q) => { encodes += 1; const r = await evalQ(q); return { ...r, q } }
  // Geçen bir kalite bulunana dek üst uç için bir kodlama yedekte tutulur
  while (a < b && encodes < maxEncodes - (best ? 0 : 1)) {
    const mid = (a + b) >> 1
    const r = await run(mid)
    if (r.similarity >= threshold) { best = r; b = mid } else { a = mid + 1 }
  }
  if (!best) {
    top = await run(hi)
    best = top
  }
  return { ...best, encodes }
}

/**
 * Bayt hedefinin altında kalan EN YÜKSEK kaliteyi arar. @param {(q: number) => Promise<{ size: number }>} encodeQ
 * @returns {Promise<{ fit: object|null, low: object, encodes: number }>} `fit` yoksa taban kalite bile sığmadı
 *   (`low` = taban kalitedeki sonuç — bir sonraki ölçü küçültmesinin oranı ondan hesaplanır).
 */
export async function bestQualityUnder(encodeQ, lo, hi, targetBytes, maxSteps = 4) {
  let encodes = 0
  const run = async (q) => { encodes += 1; const r = await encodeQ(q); return { ...r, q } }
  const low = await run(lo)
  if (low.size > targetBytes) return { fit: null, low, encodes }
  if (hi <= lo) return { fit: low, low, encodes }
  const high = await run(hi)
  if (high.size <= targetBytes) return { fit: high, low, encodes }
  let a = lo                     // sığar
  let b = hi                     // sığmaz
  let fit = low
  for (let step = 0; step < maxSteps && b - a > 1; step++) {
    const mid = (a + b) >> 1
    const r = await run(mid)
    if (r.size <= targetBytes) { fit = r; a = mid } else { b = mid }
  }
  return { fit, low, encodes }
}

/**
 * İstenen biçimi, kaynağı ve tarayıcının WebP kodlayabilmesini çıktı adaylarına çevirir.
 * @returns {{ list: string[], notes: string[] }}
 */
export function resolveFormats(format, sourceType, hasAlpha, webpOk) {
  const fallback = hasAlpha ? 'png' : 'jpeg'
  const webp = () => (webpOk ? { list: ['webp'], notes: [] } : { list: [fallback], notes: ['WEBP_UNAVAILABLE'] })
  if (format === 'jpeg') return { list: ['jpeg'], notes: [] }
  if (format === 'png') return { list: ['png'], notes: [] }
  if (format === 'webp') return webp()
  if (format === 'smallest') {
    const list = hasAlpha ? ['png'] : ['jpeg', 'png']
    if (webpOk) list.push('webp')
    return { list, notes: webpOk ? [] : ['WEBP_UNAVAILABLE'] }
  }
  // keep: kaynağın biçimi; yazamadığımız biçimler en yakın eşdeğere
  if (sourceType === 'jpeg') return { list: ['jpeg'], notes: [] }
  if (sourceType === 'png' || sourceType === 'gif' || sourceType === 'bmp') return { list: ['png'], notes: [] }
  return webp()                  // webp, avif
}

/**
 * "En küçük biçim": yarışa giren adaylardan EN KÜÇÜK dosya. Kayıplı adaylar (JPEG / WebP) her zaman yarışır — her biri
 * kendi kalite aramasının (ya da istenen sabit kalitenin) sonucudur; arama aralığın üst ucunda bitse bile adaydır.
 * PNG adayı yalnız eşiği tutturuyorsa yarışır (birebir palet / gerçek renk = 1; titreşimli palet ölçülen benzerlikle):
 * rengi azaltılmış bir fotoğraf PNG'si, biraz küçük diye "eşdeğer" JPEG'in önüne geçmesin. Yarışan yoksa (yalnız
 * eşik altı PNG) benzerliği en yüksek olan döner. Eşitlikte liste sırası (jpeg, png, webp) korunur.
 * @param {{ type: string, size: number, similarity: number|null }[]} candidates
 */
export function pickSmallest(candidates, threshold = SIMILARITY_TARGET) {
  if (!candidates.length) return null
  const sim = (c) => (c.similarity == null ? 1 : c.similarity)
  const eligible = candidates.filter((c) => c.type !== 'png' || sim(c) >= threshold)
  if (eligible.length) return eligible.reduce((best, c) => (c.size < best.size ? c : best))
  return candidates.reduce((best, c) => (sim(c) > sim(best) ? c : best))
}

/**
 * Hedef boyut kipinde adaylar arasında seçim: hedefe sığan önce; sonra daha büyük ölçü (az küçültülmüş); sonra
 * benzerlik; sonra küçük dosya. Hiçbiri sığmıyorsa en küçük dosya.
 */
export function pickForTarget(candidates, targetBytes) {
  if (!candidates.length) return null
  const fits = candidates.filter((c) => c.size <= targetBytes)
  if (!fits.length) return candidates.reduce((best, c) => (c.size < best.size ? c : best))
  const sim = (c) => (c.similarity == null ? 1 : c.similarity)
  return fits.reduce((best, c) => {
    const ac = c.width * c.height
    const ab = best.width * best.height
    if (ac !== ab) return ac > ab ? c : best
    if (Math.abs(sim(c) - sim(best)) > 0.005) return sim(c) > sim(best) ? c : best
    return c.size < best.size ? c : best
  })
}

/** PNG için sayısal kalite → palet boyu (100 = kayıpsız → 0 döner). */
export function pngColorsForQuality(quality) {
  if (quality >= 100) return 0
  const q = Math.max(1, quality) / 100
  return Math.max(8, Math.min(256, Math.round(256 * q * q)))
}

/**
 * "Asla daha kötü": ölçü değişmediyse ve çıktı özgün dosyadan küçük değilse özgün dosya döner. Aynı biçimde her
 * zaman; biçim AÇIKÇA istenmediyse (keep / smallest) dönüştürülmüş çıktı için de (özgün zaten daha küçük).
 */
export function shouldKeepOriginal({ requestedFormat, sourceType, outType, sameDimensions, outBytes, sourceBytes }) {
  if (!sameDimensions || outBytes < sourceBytes) return false
  if (outType === sourceType) return true
  return requestedFormat === 'keep' || requestedFormat === 'smallest'
}

/** Tasarruf yüzdesi: 0..100, bir ondalık, aşağı yuvarlanır. */
export function savingPct(sourceBytes, outBytes) {
  if (!(sourceBytes > 0) || outBytes >= sourceBytes) return 0
  return Math.floor((1 - outBytes / sourceBytes) * 1000) / 10
}

/** Saydam pikselleri düz renge bindirir (gama kodlu — tarayıcının sayfada yaptığıyla aynı). Yeni tampon döner. */
export function flattenOnto(rgba, pixelCount, rgb) {
  const out = new Uint8ClampedArray(pixelCount * 4)
  const [br, bg, bb] = rgb
  for (let p = 0, i = 0; p < pixelCount; p++, i += 4) {
    const a = rgba[i + 3]
    if (a === 255) {
      out[i] = rgba[i]; out[i + 1] = rgba[i + 1]; out[i + 2] = rgba[i + 2]
    } else if (a === 0) {
      out[i] = br; out[i + 1] = bg; out[i + 2] = bb
    } else {
      const k = a / 255
      out[i] = rgba[i] * k + br * (1 - k)
      out[i + 1] = rgba[i + 1] * k + bg * (1 - k)
      out[i + 2] = rgba[i + 2] * k + bb * (1 - k)
    }
    out[i + 3] = 255
  }
  return out
}

/** Görselde opak olmayan piksel var mı. */
export function hasTransparency(rgba, pixelCount) {
  for (let i = 3, end = pixelCount * 4; i < end; i += 4) if (rgba[i] !== 255) return true
  return false
}
