// Görsel motoru — hedef ölçü hesabı (saf). Hiçbir yöntem BÜYÜTMEZ; en-boy oranı `scale` / `fit`te korunur, `cover` /
// `thumb` kutunun oranına kırpar. Pencere (`window`) kaynak pikselleridir; `thumb`ın nesne kipinde görselin DIŞINA
// taşabilir (taşan yer zemin rengiyle doldurulur).

const clampInt = (v, lo, hi) => Math.max(lo, Math.min(hi, Math.round(v)))

/**
 * @param {number} srcW @param {number} srcH çözülmüş görselin ölçüsü
 * @param {object} resize doğrulanmış seçenek (options.js)
 * @returns {{ outW: number, outH: number, window: {x:number,y:number,w:number,h:number}, resized: boolean,
 *   cropped: boolean, notUpscaled: boolean, cropAxis: 'x'|'y'|null }}
 *   `cropAxis` — kırpma penceresinin kaydırılabileceği eksen (akıllı kırpma konumu seçer); pencere ortada başlar.
 */
export function planResize(srcW, srcH, resize) {
  const full = { x: 0, y: 0, w: srcW, h: srcH }
  const base = { outW: srcW, outH: srcH, window: full, resized: false, cropped: false, notUpscaled: false, cropAxis: null }
  const method = resize?.method || 'none'
  if (method === 'none') return base

  if (method === 'scale') {
    if (resize.width != null) {
      if (resize.width >= srcW) return { ...base, notUpscaled: resize.width > srcW }
      const outW = resize.width
      return { ...base, outW, outH: clampInt(srcH * outW / srcW, 1, srcH), resized: true }
    }
    if (resize.height >= srcH) return { ...base, notUpscaled: resize.height > srcH }
    const outH = resize.height
    return { ...base, outH, outW: clampInt(srcW * outH / srcH, 1, srcW), resized: true }
  }

  const boxW = resize.width
  const boxH = resize.height
  if (method === 'fit') {
    const f = Math.min(boxW / srcW, boxH / srcH)
    if (f >= 1) return { ...base, notUpscaled: f > 1 }
    const outW = Math.min(boxW, clampInt(srcW * f, 1, srcW))
    const outH = Math.min(boxH, clampInt(srcH * f, 1, srcH))
    return { ...base, outW, outH, resized: outW !== srcW || outH !== srcH }
  }

  // cover / thumb: kutunun oranındaki EN BÜYÜK pencere; yalnız bir eksende kırpar
  let cw
  let ch
  let cropAxis = null
  if (srcW * boxH > srcH * boxW) {            // kaynak kutudan daha yatay → yanlardan kırp
    ch = srcH
    cw = clampInt(srcH * boxW / boxH, 1, srcW)
    if (cw < srcW) cropAxis = 'x'
  } else {
    cw = srcW
    ch = clampInt(srcW * boxH / boxW, 1, srcH)
    if (ch < srcH) cropAxis = 'y'
  }
  const fits = cw >= boxW && ch >= boxH
  const outW = fits ? boxW : Math.min(cw, boxW)
  const outH = fits ? boxH : Math.min(ch, boxH)
  const window = { x: Math.floor((srcW - cw) / 2), y: Math.floor((srcH - ch) / 2), w: cw, h: ch }
  return {
    outW, outH, window, cropAxis,
    resized: outW !== srcW || outH !== srcH,
    cropped: cw !== srcW || ch !== srcH,
    notUpscaled: !fits && (boxW > cw || boxH > ch),
  }
}

/** Kırpma penceresini eksen boyunca `offset`e taşır (görselin içinde kalır). */
export function placeWindow(plan, srcW, srcH, offset) {
  if (!plan.cropAxis) return plan
  const window = { ...plan.window }
  if (plan.cropAxis === 'x') window.x = clampInt(offset, 0, srcW - window.w)
  else window.y = clampInt(offset, 0, srcH - window.h)
  return { ...plan, window }
}

/**
 * `thumb` — düz zemin üstünde serbest duran NESNE bulunduğunda: nesnenin tamamını (küçük bir payla) içeren, kutunun
 * oranındaki pencere. Pencere görselin dışına taşarsa `padded` (taşan yer zemin rengiyle dolar). Nesne kutudan küçükse
 * büyütülmez: pencere kutu kadar kaynak pikseli alır (1:1).
 * @param {{x0:number,y0:number,x1:number,y1:number}} bbox nesnenin kaynak pikselindeki kutusu (x1 / y1 hariç uç)
 */
export function planThumbObject(srcW, srcH, boxW, boxH, bbox) {
  const bw = Math.max(1, bbox.x1 - bbox.x0)
  const bh = Math.max(1, bbox.y1 - bbox.y0)
  const margin = Math.max(1, Math.round(Math.max(bw, bh) * 0.06))
  const ew = bw + margin * 2
  const eh = bh + margin * 2
  let ww
  let wh
  if (ew * boxH > eh * boxW) { ww = ew; wh = Math.ceil(ew * boxH / boxW) } else { wh = eh; ww = Math.ceil(eh * boxW / boxH) }
  if (ww < boxW || wh < boxH) { ww = boxW; wh = boxH }       // büyütme yok: kutu kadar kaynak pikseli
  const cx = (bbox.x0 + bbox.x1) / 2
  const cy = (bbox.y0 + bbox.y1) / 2
  const place = (c, len, srcLen) => {
    const start = Math.round(c - len / 2)
    // Pencere görsele sığıyorsa içeride tutulur (gereksiz dolgu olmasın); sığmıyorsa nesne ortalanır
    return len <= srcLen ? Math.max(0, Math.min(srcLen - len, start)) : start
  }
  const window = { x: place(cx, ww, srcW), y: place(cy, wh, srcH), w: ww, h: wh }
  const padded = window.x < 0 || window.y < 0 || window.x + ww > srcW || window.y + wh > srcH
  return {
    outW: boxW, outH: boxH, window, cropAxis: null,
    resized: boxW !== srcW || boxH !== srcH,
    cropped: !padded && (ww !== srcW || wh !== srcH),
    padded,
    notUpscaled: false,
  }
}

/** Hedef boyut kipinde bir sonraki (küçültülmüş) ölçü: oran korunur, hiçbir kenar `minSide`in altına inmez. */
export function shrinkDims(w, h, factor, minSide) {
  const least = Math.min(w, h)
  if (least <= minSide) return null
  const f = Math.max(factor, minSide / least)
  const nw = Math.max(minSide, Math.min(w, Math.round(w * f)))
  const nh = Math.max(minSide, Math.min(h, Math.round(h * f)))
  if (nw === w && nh === h) return null
  return { width: nw, height: nh }
}
