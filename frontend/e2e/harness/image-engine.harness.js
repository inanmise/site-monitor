/**
 * Görsel motoru izole harness'ı — YALNIZ Playwright içindir, ürüne girmez (bkz. image-engine.html).
 *
 * `window.__ie`:
 *   engine          motorun kamu arayüzü (index.js — gerçek Worker)
 *   meta            üstveri yardımcıları (test girdisi kurmak ve çıktıyı doğrulamak için)
 *   gen.*           sayfada üretilen girdiler (tuval → Blob): fotoğraf benzeri, saydam logo, dama, kenar, nesne
 *   pixels(blob)    Blob'u GERÇEK tuvalde çözer: { width, height, at(x, y) }
 *   pngInfo(blob)   PNG parçalarını okur: { colorType, bitDepth, chunks, paletteSize, trns }
 *   heartbeat()     ana iş parçacığının yanıt verebilirliğini ölçer (en uzun aralık)
 */
import * as metaMod from '../../src/utils/imageEngine/metadata.js'

const q = new URLSearchParams(location.search)
if (q.get('noworker') === '1') {
  // Ana iş parçacığı yedeği: Worker yokmuş gibi
  Object.defineProperty(window, 'Worker', { value: undefined, configurable: true, writable: true })
}
const engine = await import('../../src/utils/imageEngine/index.js')

function rng(seed) {
  let s = seed >>> 0
  return () => { s = (Math.imul(s, 1664525) + 1013904223) >>> 0; return s / 4294967296 }
}
const canvasOf = (w, h) => { const c = document.createElement('canvas'); c.width = w; c.height = h; return c }
const toBlob = (canvas, type, quality) => new Promise((resolve, reject) => canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('toBlob'))), type, quality))

const gen = {
  /** Fotoğraf benzeri: yumuşak renk geçişleri + bulanık şekiller + ince gürültü. */
  async photo(w, h, type = 'image/jpeg', quality = 0.97, seed = 5) {
    const c = canvasOf(w, h)
    const cx = c.getContext('2d')
    const g = cx.createLinearGradient(0, 0, w, h)
    g.addColorStop(0, '#27496d'); g.addColorStop(0.45, '#d9a05b'); g.addColorStop(1, '#2f6f4f')
    cx.fillStyle = g
    cx.fillRect(0, 0, w, h)
    const rnd = rng(seed)
    cx.filter = `blur(${Math.max(2, Math.round(w / 200))}px)`
    for (let i = 0; i < 40; i++) {
      cx.fillStyle = `hsla(${Math.round(rnd() * 360)}, ${40 + Math.round(rnd() * 40)}%, ${30 + Math.round(rnd() * 40)}%, 0.55)`
      cx.beginPath()
      cx.ellipse(rnd() * w, rnd() * h, (0.03 + rnd() * 0.12) * w, (0.03 + rnd() * 0.1) * h, rnd() * 3, 0, Math.PI * 2)
      cx.fill()
    }
    cx.filter = 'none'
    // ince sensör gürültüsü: küçük bir gürültü karosu desen olarak döşenir (büyük görselde de hızlı)
    const n = canvasOf(256, 256)
    const nx = n.getContext('2d')
    const id = nx.createImageData(256, 256)
    // genlik ≈ ±2 düzey (hafif grenli fotoğraf): daha güçlü gürültüde JPEG / WebP greni siler ve otomatik kalite hep üst uca çıkar
    for (let i = 0; i < id.data.length; i += 4) { const v = 128 + (rnd() - 0.5) * 60; id.data[i] = v; id.data[i + 1] = v; id.data[i + 2] = v; id.data[i + 3] = 18 }
    nx.putImageData(id, 0, 0)
    cx.fillStyle = cx.createPattern(n, 'repeat')
    cx.fillRect(0, 0, w, h)
    // keskin ayrıntı: ince çizgiler ve yazı
    cx.strokeStyle = 'rgba(255,255,255,0.8)'
    cx.lineWidth = Math.max(1, w / 600)
    for (let i = 0; i < 12; i++) { cx.beginPath(); cx.moveTo(rnd() * w, rnd() * h); cx.lineTo(rnd() * w, rnd() * h); cx.stroke() }
    cx.fillStyle = '#101418'
    cx.font = `${Math.round(h / 12)}px sans-serif`
    cx.fillText('SiteMonitor 2026', w * 0.08, h * 0.9)
    return toBlob(c, type, quality)
  },
  /** Saydam zemin üstünde düz renkli logo (kenarları yumuşatılmış = yarı saydam pikseller). */
  async logo(w, h) {
    const c = canvasOf(w, h)
    const cx = c.getContext('2d')
    cx.fillStyle = '#813387'
    cx.beginPath(); cx.arc(w * 0.3, h * 0.5, Math.min(w, h) * 0.28, 0, Math.PI * 2); cx.fill()
    cx.fillStyle = '#64a64f'
    cx.beginPath(); cx.moveTo(w * 0.5, h * 0.2); cx.lineTo(w * 0.9, h * 0.35); cx.lineTo(w * 0.62, h * 0.8); cx.closePath(); cx.fill()
    cx.fillStyle = '#1d2733'
    cx.font = `bold ${Math.round(h * 0.16)}px sans-serif`
    cx.fillText('SiteMonitor', w * 0.12, h * 0.95)
    return toBlob(c, 'image/png')
  },
  /** 1 piksellik siyah / beyaz dama (kayıpsız PNG). */
  async checker(w, h) {
    const c = canvasOf(w, h)
    const cx = c.getContext('2d')
    const id = cx.createImageData(w, h)
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) { const v = ((x + y) & 1) ? 255 : 0; const i = (y * w + x) * 4; id.data[i] = v; id.data[i + 1] = v; id.data[i + 2] = v; id.data[i + 3] = 255 }
    cx.putImageData(id, 0, 0)
    return toBlob(c, 'image/png')
  },
  /** Sol yarı opak kırmızı, sağ yarı tam saydam (PNG). */
  async edge(w, h) {
    const c = canvasOf(w, h)
    const cx = c.getContext('2d')
    cx.fillStyle = '#ff0000'
    cx.fillRect(0, 0, Math.round(w * 0.53), h)
    return toBlob(c, 'image/png')
  },
  /** Düz zemin üstünde serbest duran nesne (yatay görsel). */
  async object(w, h, bg = '#f4f4f4', type = 'image/png') {
    const c = canvasOf(w, h)
    const cx = c.getContext('2d')
    cx.fillStyle = bg
    cx.fillRect(0, 0, w, h)
    cx.fillStyle = '#c8281e'
    cx.fillRect(Math.round(w * 0.15), Math.round(h * 0.2), Math.round(w * 0.7), Math.round(h * 0.6))
    cx.fillStyle = '#2255aa'
    cx.fillRect(Math.round(w * 0.4), Math.round(h * 0.35), Math.round(w * 0.2), Math.round(h * 0.3))
    return toBlob(c, type, 0.95)
  },
  /** Yanında ayrıntı olan geniş görsel (akıllı kırpma): `side` = 'left' | 'right'. */
  async detail(w, h, side) {
    const c = canvasOf(w, h)
    const cx = c.getContext('2d')
    cx.fillStyle = '#d0d4d8'
    cx.fillRect(0, 0, w, h)
    const rnd = rng(11)
    const x0 = side === 'left' ? 0 : w - h
    for (let i = 0; i < 400; i++) {
      cx.fillStyle = `hsl(${Math.round(rnd() * 360)}, 80%, ${30 + Math.round(rnd() * 40)}%)`
      cx.fillRect(x0 + rnd() * h * 0.9, rnd() * h * 0.9, h * 0.1, h * 0.1)
    }
    return toBlob(c, 'image/png')
  },
  /** Hazır baytlardan Blob. */
  bytes(arr, type = '') { return new Blob([Uint8Array.from(arr)], { type }) },
  /**
   * Mantıksal ekranı `sw × sh`, tek karesi (x, y)'de `w × h` olan GIF. `data = true` → geçerli LZW (4 renk; her iki
   * pikselde bir CLEAR — kod genişliği 3 bitte kalır); false → en küçük (geçersiz) veri (çözmeden reddedilecek dosya).
   */
  gifFramed(sw, sh, x, y, w, h, data = true) {
    const le = (v) => [v & 255, (v >> 8) & 255]
    let image = [2, 2, 0x44, 1, 0]
    if (data) {
      const codes = []
      for (let p = 0; p < w * h; p++) { if (p % 2 === 0) codes.push(4); codes.push(((p >> 3) & 1) ? 1 : 2) }
      codes.push(5)
      const bytes = []
      let acc = 0
      let bits = 0
      for (const c of codes) { acc |= c << bits; bits += 3; while (bits >= 8) { bytes.push(acc & 255); acc >>= 8; bits -= 8 } }
      if (bits > 0) bytes.push(acc & 255)
      image = [2]
      for (let i = 0; i < bytes.length; i += 255) { const part = bytes.slice(i, i + 255); image.push(part.length, ...part) }
      image.push(0)
    }
    const head = [0x47, 0x49, 0x46, 0x38, 0x39, 0x61, ...le(sw), ...le(sh), 0x81, 0, 0, 255, 0, 0, 0, 0, 255, 0, 255, 0, 255, 255, 255]
    return new Blob([Uint8Array.from([...head, 0x2c, ...le(x), ...le(y), ...le(w), ...le(h), 0, ...image, 0x3b])], { type: 'image/gif' })
  },
  /** En küçük iki kareli GIF (1 × 1). */
  animatedGif() {
    const frame = [0x21, 0xf9, 4, 0, 5, 0, 0, 0, 0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0]
    return new Blob([Uint8Array.from([0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 1, 0, 1, 0, 0x80, 0, 0, 255, 255, 255, 0, 0, 0, ...frame, ...frame, 0x3b])], { type: 'image/gif' })
  },
}

/** Blob'u gerçek tuvalde çözer. */
async function pixels(blob) {
  const bitmap = await createImageBitmap(blob)
  const c = canvasOf(bitmap.width, bitmap.height)
  const cx = c.getContext('2d', { willReadFrequently: true })
  cx.drawImage(bitmap, 0, 0)
  bitmap.close()
  const data = cx.getImageData(0, 0, c.width, c.height).data
  return { width: c.width, height: c.height, data, at: (x, y) => Array.from(data.subarray((y * c.width + x) * 4, (y * c.width + x) * 4 + 4)) }
}

/** PNG parçaları (motorun yazdığı dosyanın yapısı). */
async function pngInfo(blob) {
  const b = new Uint8Array(await blob.arrayBuffer())
  const u32 = (o) => b[o] * 16777216 + (b[o + 1] << 16) + (b[o + 2] << 8) + b[o + 3]
  const out = { colorType: b[25], bitDepth: b[24], chunks: [], paletteSize: 0, trns: 0, text: [] }
  let o = 8
  for (let guard = 0; guard < 10000 && o + 12 <= b.length; guard++) {
    const len = u32(o)
    const type = String.fromCharCode(b[o + 4], b[o + 5], b[o + 6], b[o + 7])
    out.chunks.push(type)
    if (type === 'PLTE') out.paletteSize = len / 3
    if (type === 'tRNS') out.trns = len
    if (type === 'tEXt' || type === 'iTXt') out.text.push(new TextDecoder('latin1').decode(b.subarray(o + 8, o + 8 + len)))
    o += 12 + len
  }
  return out
}

/** Ana iş parçacığı nabzı: `stop()` en uzun aralığı (ms) ve vuruş sayısını döner. */
function heartbeat() {
  let last = performance.now()
  let worst = 0
  let beats = 0
  const timer = setInterval(() => { const t = performance.now(); worst = Math.max(worst, t - last); last = t; beats += 1 }, 20)
  return { stop() { clearInterval(timer); worst = Math.max(worst, performance.now() - last); return { worst, beats } } }
}

/** Sonucun Playwright'a taşınabilir özeti (Blob taşınamaz). */
const sum = (r) => ({ ...r, blob: undefined, blobType: r.blob?.type, blobSize: r.blob?.size })
/** Reddedilen sözün hata kodu: { code, params } (reddedilmezse null). */
const failure = (p) => p.then(() => null, (e) => ({ code: e?.code, params: e?.params, name: e?.name }))

window.__ie = { engine, meta: metaMod, gen, pixels, pngInfo, heartbeat, toBlob, canvasOf, sum, failure }
document.getElementById('status').textContent = 'hazır'
