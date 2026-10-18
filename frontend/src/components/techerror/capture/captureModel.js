/**
 * EKRANDAN GÖRSEL ALMA — saf model (2026-10-18, kullanıcı isteği: "görsel yükle butonuna ek olarak snipping benzeri bir
 * fonksiyon; ekrandan direkt alınabilsin; daha hızlı yöntem varsa onu da yapalım").
 *
 * <p>Kırpma dikdörtgeni HER ZAMAN kaynak piksellerinde tutulur (paylaşılan pencerenin gerçek çözünürlüğü); ekrandaki
 * çizim yalnız ölçekle çarpılır. Yüklenen bölge tam sayı piksellidir, en küçük kenar sunucunun alt sınırıdır (16 px,
 * `TechErrorImageValidator` = `imagePrep.SERVER_IMAGE.min`). DOM yok — tamamı birim testli (`captureModel.test.js`).
 */

import { SERVER_IMAGE } from '../imagePrep.js'

/** Sunucunun kabul ettiği en küçük kenar (piksel, 16) — daha küçük bölge seçilemez. Tek kaynak: imagePrep. */
export const CAPTURE_MIN = SERVER_IMAGE.minSide
/** Paylaşım bu kadar süre kullanılmazsa (yeni görüntü alınmazsa) kendiliğinden kapanır. */
export const CAPTURE_IDLE_MS = 10 * 60_000
/** Klavye adımı: ok 1 px, Shift + ok 10 px (kaynak pikseli). */
export const KEY_STEP = 1
export const KEY_STEP_BIG = 10
/** Sürükleme sayılmayan en küçük hareket (ekran pikseli): tıklama eski seçimi silmez. */
export const DRAG_THRESHOLD = 3
/** Sekiz boyutlandırma tutamağı (pusula yönleri). */
export const HANDLES = Object.freeze(['nw', 'n', 'ne', 'e', 'se', 's', 'sw', 'w'])

const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v))
const minSide = (min, size) => Math.max(1, Math.min(min, size))
const finite = (v, d = 0) => (Number.isFinite(v) ? v : d)

/** Kaynağın tamamı. */
export function fullRect(W, H) {
  return { x: 0, y: 0, w: Math.max(0, finite(W)), h: Math.max(0, finite(H)) }
}

/**
 * Dikdörtgeni kaynağın içine sığdırır: kenar en az `min` (kaynak daha küçükse kaynağın kendisi), kaynaktan büyük
 * olamaz, kaynağın dışına taşmaz. Yeniden çekimde ölçüsü değişen kareye eski seçim böyle uyar.
 */
export function clampRect(r, W, H, min = CAPTURE_MIN) {
  const mw = minSide(min, W)
  const mh = minSide(min, H)
  const w = clamp(finite(r?.w, W), mw, Math.max(mw, W))
  const h = clamp(finite(r?.h, H), mh, Math.max(mh, H))
  return { x: clamp(finite(r?.x), 0, Math.max(0, W - w)), y: clamp(finite(r?.y), 0, Math.max(0, H - h)), w, h }
}

/** Taşıma: ölçü korunur, kaynağın kenarında durur. */
export function moveRect(r, dx, dy, W, H, min = CAPTURE_MIN) {
  return clampRect({ ...r, x: r.x + finite(dx), y: r.y + finite(dy) }, W, H, min)
}

/**
 * Tutamakla boyutlandırma: tutulan kenar(lar) hareket eder, karşı kenar sabit kalır; kenar `min`'in altına inmez ve
 * kaynağın dışına çıkmaz (kenar karşı kenarı geçemez — dikdörtgen ters dönmez).
 */
export function resizeRect(r, handle, dx, dy, W, H, min = CAPTURE_MIN) {
  const mw = minSide(min, W)
  const mh = minSide(min, H)
  let x1 = r.x
  let y1 = r.y
  let x2 = r.x + r.w
  let y2 = r.y + r.h
  const h = String(handle || '')
  if (h.includes('w')) x1 = clamp(x1 + finite(dx), 0, x2 - mw)
  if (h.includes('e')) x2 = clamp(x2 + finite(dx), x1 + mw, W)
  if (h.includes('n')) y1 = clamp(y1 + finite(dy), 0, y2 - mh)
  if (h.includes('s')) y2 = clamp(y2 + finite(dy), y1 + mh, H)
  return clampRect({ x: x1, y: y1, w: x2 - x1, h: y2 - y1 }, W, H, min)
}

function drawAxis(a, p, size, min) {
  const m = minSide(min, size)
  const aa = clamp(finite(a), 0, size)
  const pp = clamp(finite(p), 0, size)
  let lo = Math.min(aa, pp)
  let hi = Math.max(aa, pp)
  if (hi - lo < m) {
    // Sürükleme yönünde en az `min`; kaynağın kenarına dayanırsa öbür yöne kayar
    if (pp >= aa) { lo = aa; hi = aa + m } else { hi = aa; lo = aa - m }
    if (hi > size) { hi = size; lo = size - m }
    if (lo < 0) { lo = 0; hi = m }
  }
  return [lo, hi]
}

/** Yeni seçim: çapadan imlece (yönden bağımsız), kaynağın içinde, kenar en az `min`. */
export function drawRect(anchor, point, W, H, min = CAPTURE_MIN) {
  const [x1, x2] = drawAxis(anchor?.x, point?.x, W, min)
  const [y1, y2] = drawAxis(anchor?.y, point?.y, H, min)
  return { x: x1, y: y1, w: x2 - x1, h: y2 - y1 }
}

const ARROWS = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }

/**
 * Klavye: oklar seçimi taşır (1 px, Shift ile 10 px); Alt / Option + oklar boyutlandırır — sağ / aşağı büyütür, sol /
 * yukarı küçültür (sol üst köşe sabit). Ok değilse `null` (olay işlenmez).
 */
export function keyRect(r, key, { shift = false, alt = false } = {}, W, H, min = CAPTURE_MIN) {
  const dir = ARROWS[key]
  if (!dir) return null
  const step = shift ? KEY_STEP_BIG : KEY_STEP
  const [sx, sy] = dir
  if (alt) return resizeRect(r, sx ? 'e' : 's', sx * step, sy * step, W, H, min)
  return moveRect(r, sx * step, sy * step, W, H, min)
}

/** Kaynağı kutuya sığdıran ölçek (büyütme yok; geçersiz ölçüde 1). */
export function fitScale(srcW, srcH, boxW, boxH) {
  if (!(srcW > 0) || !(srcH > 0)) return 1
  const bw = boxW > 0 ? boxW : srcW
  const bh = boxH > 0 ? boxH : srcH
  return Math.min(1, bw / srcW, bh / srcH)
}

/** Ekran noktası (kutunun sol üstüne göre) → kaynak pikseli. */
export function toSourcePoint(pt, scale) {
  const s = scale > 0 ? scale : 1
  return { x: finite(pt?.x) / s, y: finite(pt?.y) / s }
}

/** Kaynak dikdörtgeni → ekrandaki dikdörtgen. */
export function toDisplayRect(r, scale) {
  const s = scale > 0 ? scale : 1
  return { x: r.x * s, y: r.y * s, w: r.w * s, h: r.h * s }
}

/**
 * Yüklenecek bölge: tam sayı kaynak pikselleri (kenarlar ayrı yuvarlanır — komşu bölgeler arasında boşluk / bindirme
 * yok), kaynağın içinde, kenar en az `min`.
 */
export function sourceRect(r, W, H, min = CAPTURE_MIN) {
  const c = clampRect(r, W, H, min)
  const mw = minSide(min, W)
  const mh = minSide(min, H)
  let x = Math.round(c.x)
  let y = Math.round(c.y)
  let w = Math.round(c.x + c.w) - x
  let h = Math.round(c.y + c.h) - y
  w = clamp(w, mw, Math.max(mw, Math.floor(W)))
  h = clamp(h, mh, Math.max(mh, Math.floor(H)))
  x = clamp(x, 0, Math.max(0, Math.floor(W) - w))
  y = clamp(y, 0, Math.max(0, Math.floor(H) - h))
  return { x, y, w, h }
}

/** Seçim kaynağın tamamı mı (yarım piksel pay)? Tamamı seçiliyken sürüklemek yeni seçim başlatır, taşımaz. */
export function isFullRect(r, W, H) {
  return !!r && r.x <= 0.5 && r.y <= 0.5 && r.w >= W - 0.5 && r.h >= H - 0.5
}

/** Nokta seçimin içinde mi (kaynak pikselleri)? */
export function pointInRect(pt, r) {
  return !!r && pt.x >= r.x && pt.x <= r.x + r.w && pt.y >= r.y && pt.y <= r.y + r.h
}

/**
 * Kare tek renk mi (RGBA örnekleri): küçültülmüş pencere, korumalı (DRM) içerik ya da izinsiz paylaşım çoğu zaman
 * siyah / tek renk gelir. `tolerance`: kanal başına fark (sıkıştırma gürültüsü sayılmaz).
 */
export function isUniformFrame(data, tolerance = 6) {
  if (!data || data.length < 8) return true
  const r0 = data[0]
  const g0 = data[1]
  const b0 = data[2]
  for (let i = 4; i + 2 < data.length; i += 4) {
    if (Math.abs(data[i] - r0) > tolerance || Math.abs(data[i + 1] - g0) > tolerance || Math.abs(data[i + 2] - b0) > tolerance) return false
  }
  return true
}

const STAMP = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Europe/Istanbul', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  second: '2-digit', hourCycle: 'h23',
})

/** `yyyyMMdd-HHmmss` (Türkiye saati). */
export function captureStamp(date = new Date()) {
  const parts = Object.fromEntries(STAMP.formatToParts(date).map((p) => [p.type, p.value]))
  return `${parts.year}${parts.month}${parts.day}-${parts.hour}${parts.minute}${parts.second}`
}

/**
 * Dosya adı: `<yuva>-ekran-<yyyyMMdd-HHmmss>.png` (ekrandan) / `<yuva>-pano-…` (panodan). Yuva kimliği şablonda
 * `[a-z0-9-]`; başka karakter atılır, boş kalırsa "gorsel".
 */
export function captureFileName(slotId, kind = 'ekran', date = new Date(), ext = 'png') {
  const slot = String(slotId || '').toLowerCase().replace(/[^a-z0-9-]+/g, '').slice(0, 60) || 'gorsel'
  const e = String(ext || 'png').toLowerCase().replace(/[^a-z0-9]/g, '') || 'png'
  return `${slot}-${kind}-${captureStamp(date)}.${e}`
}

const EXT = { 'image/png': 'png', 'image/jpeg': 'jpg', 'image/webp': 'webp', 'image/gif': 'gif', 'image/bmp': 'bmp', 'image/avif': 'avif' }

/** Görsel türünün dosya uzantısı (bilinmeyen → png; imagePrep türü baytlardan okur). */
export function extensionOf(type) {
  return EXT[String(type || '').toLowerCase()] || 'png'
}

/** Pano türleri içinden yüklenecek görsel türü: PNG önce (kayıpsız ekran görüntüsü), sonra ilk image/*. */
export function pickImageType(types) {
  const list = Array.from(types || []).map(String)
  if (list.includes('image/png')) return 'image/png'
  return list.find((t) => t.startsWith('image/')) || null
}

/**
 * Yapıştırılan içerikten ilk görsel dosyası (`ClipboardEvent.clipboardData` / `DataTransfer`) — `image: true`. Görsel
 * yoksa ilk DOSYA (dosya yöneticisinden kopyalanan PDF, belge…) `image: false` ile döner: yuvanın yükleme yolu
 * (imagePrep) türünü söyleyen hatayı yazar ("PDF dosyası görsel yuvasına yüklenemez…" — inceleme 2026-10-18; eskiden
 * sessizce yok sayılıyordu). `hasText`: dosya yok ama metin var (metin yapıştırması yok sayılır, kısa bir ipucu).
 */
export function imageFromDataTransfer(dt) {
  if (!dt) return { file: null, hasText: false, image: false }
  const items = Array.from(dt.items || [])
  const asFile = (item) => (typeof item?.getAsFile === 'function' ? item.getAsFile() : null)
  for (const item of items) {
    if (item?.kind === 'file' && String(item.type || '').startsWith('image/')) {
      const f = asFile(item)
      if (f) return { file: f, hasText: false, image: true }
    }
  }
  const files = Array.from(dt.files || []).filter(Boolean)
  for (const f of files) {
    if (String(f.type || '').startsWith('image/')) return { file: f, hasText: false, image: true }
  }
  for (const item of items) {
    if (item?.kind !== 'file') continue
    const f = asFile(item)
    if (f) return { file: f, hasText: false, image: false }
  }
  if (files.length) return { file: files[0], hasText: false, image: false }
  const types = Array.from(dt.types || []).map(String)
  const hasText = types.some((t) => t.startsWith('text/')) || items.some((i) => i?.kind === 'string')
  return { file: null, hasText, image: false }
}

/**
 * Paylaşım izinin etiketi okunur bir ad mı? Chrome çoğu zaman makine kimliği verir (`screen:0:0`, `window:5:0`,
 * `web-contents-media-stream://…`, uzun boşluksuz belirteç) — o zaman boş döner, arayüz yüzey türünü (sekme / pencere /
 * ekran) yazar. Okunur ad (pencere başlığı) 120 karaktere kısaltılır.
 */
export function friendlyTrackLabel(label) {
  const s = String(label || '').trim()
  if (!s) return ''
  if (/^(screen|window|tab|web-contents-media-stream|display|monitor)[:-]/i.test(s) || /:\/\//.test(s)) return ''
  if (!/\s/.test(s) && s.length > 24) return ''
  return s.length > 120 ? `${s.slice(0, 119)}…` : s
}

/** Kısayol ipucunun işletim sistemi ailesi (Windows / macOS / diğer). */
export function shortcutFamily(nav = globalThis.navigator) {
  const p = String(nav?.userAgentData?.platform || nav?.platform || nav?.userAgent || '')
  if (/mac|iphone|ipad/i.test(p)) return 'mac'
  if (/win/i.test(p)) return 'win'
  return 'other'
}
