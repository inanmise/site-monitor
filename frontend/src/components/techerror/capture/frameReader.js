import { isUniformFrame } from './captureModel.js'

/**
 * Ekran paylaşımından kare okuma ve kırpma (tarayıcıya özgü; testlerde taklit edilir).
 *
 * <p>Kare TAM piksel çözünürlüğünde okunur (`videoWidth × videoHeight`), tarayıcıdan çıkmaz; yalnız kullanıcının
 * onayladığı bölge PNG olarak kesilip yuvanın yükleme yoluna (imagePrep → sunucu) verilir.
 */

/** Paylaşımın ilk karesi gelene dek en çok bu kadar beklenir (seçim penceresi kapandıktan sonra). */
export const FIRST_FRAME_TIMEOUT_MS = 4000
/** Taze kare beklemesi: durağan pencere yeni kare üretmeyebilir — süre dolunca o anki kare alınır. */
export const FRESH_FRAME_TIMEOUT_MS = 400
/** Canlı önizleme: yeni kare bildirimi gelmezse (requestVideoFrameCallback yok / sessiz) bu aralıkla yoklanır. */
export const LIVE_PAINT_POLL_MS = 200
/** Tek renk denetiminin örnek ölçüsü. */
const SAMPLE_W = 48
const SAMPLE_H = 27

export class FrameError extends Error {
  constructor(kind, cause) {
    super(kind)
    this.name = 'FrameError'
    this.kind = kind
    this.cause = cause
  }
}

/** Paylaşım akışını gizli bir video öğesine bağlar (sessiz, satır içi) ve oynatır. */
export async function attachVideo(stream) {
  const video = document.createElement('video')
  video.muted = true
  video.playsInline = true
  video.autoplay = true
  video.srcObject = stream
  try { await video.play() } catch { /* bazı tarayıcılar ilk karede çözer; aşağıda beklenir */ }
  return video
}

function waitForData(video, timeoutMs) {
  if (video.readyState >= 2 && video.videoWidth > 0) return Promise.resolve(true)
  return new Promise((resolve) => {
    let done = false
    const finish = (ok) => {
      if (done) return
      done = true
      clearTimeout(timer)
      video.removeEventListener('loadeddata', onData)
      video.removeEventListener('resize', onData)
      resolve(ok)
    }
    const onData = () => { if (video.readyState >= 2 && video.videoWidth > 0) finish(true) }
    const timer = setTimeout(() => finish(video.readyState >= 2 && video.videoWidth > 0), timeoutMs)
    video.addEventListener('loadeddata', onData)
    video.addEventListener('resize', onData)
  })
}

function waitForFreshFrame(video, timeoutMs) {
  if (typeof video.requestVideoFrameCallback !== 'function') return new Promise((r) => setTimeout(r, Math.min(120, timeoutMs)))
  return new Promise((resolve) => {
    let done = false
    let handle = null
    const finish = () => {
      if (done) return
      done = true
      clearTimeout(timer)
      try { if (handle != null) video.cancelVideoFrameCallback?.(handle) } catch { /* yok */ }
      resolve()
    }
    const timer = setTimeout(finish, timeoutMs)
    try { handle = video.requestVideoFrameCallback(() => finish()) } catch { finish() }
  })
}

function uniformOf(canvas) {
  try {
    const s = document.createElement('canvas')
    s.width = SAMPLE_W
    s.height = SAMPLE_H
    const ctx = s.getContext('2d', { willReadFrequently: true })
    if (!ctx) return false
    ctx.drawImage(canvas, 0, 0, SAMPLE_W, SAMPLE_H)
    const data = ctx.getImageData(0, 0, SAMPLE_W, SAMPLE_H).data
    s.width = 0
    s.height = 0
    return isUniformFrame(data)
  } catch {
    return false
  }
}

function drawToCanvas(source, w, h) {
  const canvas = document.createElement('canvas')
  canvas.width = w
  canvas.height = h
  const ctx = canvas.getContext('2d')
  if (!ctx) {
    canvas.width = 0
    canvas.height = 0
    throw new FrameError('browser')
  }
  ctx.drawImage(source, 0, 0, w, h)
  return canvas
}

/**
 * Akışın O ANKİ karesini okur: önce ilk kareyi bekler, sonra yeni kare gelirse onu (durağan pencere yeni kare
 * üretmeyebilir — kısa süre sonra eldeki kare alınır). `ImageCapture.grabFrame` yalnız video hiç kare vermezse.
 * Döner: `{ canvas, width, height, uniform }`.
 */
export async function readVideoFrame(video, track = null) {
  let ok = await waitForData(video, FIRST_FRAME_TIMEOUT_MS)
  if (ok) {
    if (video.paused) { try { await video.play() } catch { /* yok */ } }
    await waitForFreshFrame(video, FRESH_FRAME_TIMEOUT_MS)
    const w = video.videoWidth
    const h = video.videoHeight
    if (w > 0 && h > 0) {
      const canvas = drawToCanvas(video, w, h)
      return { canvas, width: w, height: h, uniform: uniformOf(canvas) }
    }
    ok = false
  }
  if (track && typeof globalThis.ImageCapture === 'function') {
    try {
      const bitmap = await new globalThis.ImageCapture(track).grabFrame()
      const canvas = drawToCanvas(bitmap, bitmap.width, bitmap.height)
      try { bitmap.close?.() } catch { /* yok */ }
      return { canvas, width: canvas.width, height: canvas.height, uniform: uniformOf(canvas) }
    } catch (e) {
      throw new FrameError('noFrame', e)
    }
  }
  throw new FrameError('noFrame')
}

/** Dondurulan canlı karenin "tek renk" denetimi (canlıyken hesaplanmaz — her karede 4K örnekleme yapılmaz). */
export function frameUniform(frame) {
  return frame?.canvas ? uniformOf(frame.canvas) : false
}

/**
 * CANLI ÖNİZLEME (2026-11-19): videonun karelerini `canvas`a çizer — tuval karenin KENDİSİ kalır (kırpma aynı tuvalden
 * yapılır; dondurmak = çizimi durdurmak, eşzamanlı). Yeni kare `requestVideoFrameCallback` ile hemen çizilir; o olmayan
 * ya da sessiz kalan tarayıcıda {@link LIVE_PAINT_POLL_MS} aralıklı yoklama yeni kare geldiyse çizer (kare sayacı
 * yoksa her yoklamada). Video ölçüsü değişirse tuval yeniden boyutlanır ve `onResize(w, h)` çağrılır.
 *
 * <p>Sınırlı: yalnız dönen `stop()` çağrılana dek sürer; her tur tek kare çizer, bekleme yapmaz. Çağıran pencere
 * kapanınca / dondurulunca / paylaşım bitince durdurur.
 */
export function startLivePaint(video, canvas, { onResize } = {}) {
  let ctx = null
  try { ctx = canvas?.getContext?.('2d') || null } catch { ctx = null }
  if (!video || !ctx) return () => {}
  let stopped = false
  let handle = null
  let timer = null
  let lastCount = -1
  const hasRvfc = typeof video.requestVideoFrameCallback === 'function'

  const frameCount = () => {
    try {
      const n = video.getVideoPlaybackQuality?.().totalVideoFrames
      return typeof n === 'number' ? n : null
    } catch {
      return null
    }
  }

  const draw = () => {
    if (stopped) return
    try {
      const w = video.videoWidth
      const h = video.videoHeight
      if (!(w > 0 && h > 0)) return
      if (canvas.width !== w || canvas.height !== h) {
        canvas.width = w
        canvas.height = h
        try { onResize?.(w, h) } catch { /* çağıranın hatası çizimi durdurmaz */ }
      }
      ctx.drawImage(video, 0, 0, w, h)
      const n = frameCount()
      if (n != null) lastCount = n
    } catch { /* bu kare atlanır */ }
  }

  const onVideoFrame = () => {
    handle = null
    if (stopped) return
    draw()
    armRvfc()
  }
  function armRvfc() {
    if (stopped || !hasRvfc) return
    try { handle = video.requestVideoFrameCallback(onVideoFrame) } catch { handle = null }
  }
  const poll = () => {
    timer = null
    if (stopped) return
    const n = frameCount()
    // Kare sayacı varsa yalnız YENİ kare geldiyse çizilir (durağan sayfada 4K tuval boşuna yeniden boyanmaz)
    if (n == null || n !== lastCount || canvas.width !== video.videoWidth || canvas.height !== video.videoHeight) draw()
    timer = setTimeout(poll, LIVE_PAINT_POLL_MS)
  }

  draw()
  armRvfc()
  timer = setTimeout(poll, LIVE_PAINT_POLL_MS)
  return () => {
    stopped = true
    if (timer != null) { clearTimeout(timer); timer = null }
    try { if (handle != null) video.cancelVideoFrameCallback?.(handle) } catch { /* yok */ }
    handle = null
  }
}

/** Karenin piksellerini bırakır (büyük tuval bellekte kalmasın). */
export function releaseFrame(frame) {
  try {
    if (frame?.canvas) {
      frame.canvas.width = 0
      frame.canvas.height = 0
    }
  } catch { /* yok */ }
}

/** Karenin bir bölgesini (tam sayı kaynak pikselleri) PNG `File` olarak keser — ölçek yok, piksel piksel. */
export async function cropFrameToFile(frame, rect, name) {
  const { x, y, w, h } = rect
  const out = document.createElement('canvas')
  out.width = w
  out.height = h
  const ctx = out.getContext('2d')
  if (!ctx) throw new FrameError('browser')
  ctx.imageSmoothingEnabled = false
  ctx.drawImage(frame.canvas, x, y, w, h, 0, 0, w, h)
  const blob = await new Promise((resolve) => {
    try { out.toBlob((b) => resolve(b), 'image/png') } catch { resolve(null) }
  })
  out.width = 0
  out.height = 0
  if (!blob) throw new FrameError('browser')
  return new File([blob], name, { type: 'image/png', lastModified: Date.now() })
}

/**
 * Kareyi kırpma penceresinin sahnesine yerleştirir: karenin KENDİ tuvali gösterilir (4K bir karenin ikinci kopyası
 * bellekte tutulmaz); CSS kutuya sığdırır. Sahne React çocuğu taşımaz.
 */
export function mountFrame(frame, container) {
  if (!container) return
  try {
    const c = frame?.canvas
    if (!c || typeof c.nodeType !== 'number') { container.replaceChildren(); return }
    c.setAttribute('data-slot', 'te-cap-frame')
    c.setAttribute('aria-hidden', 'true')
    c.style.display = 'block'
    c.style.width = '100%'
    c.style.height = '100%'
    c.style.pointerEvents = 'none'
    if (c.parentNode !== container || container.childNodes.length !== 1) container.replaceChildren(c)
  } catch { /* jsdom: çerçeve boş görünür, seçim yine çalışır */ }
}
