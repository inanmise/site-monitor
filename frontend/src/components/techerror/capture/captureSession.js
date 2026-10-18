import { useSyncExternalStore } from 'react'
import { CAPTURE_IDLE_MS } from './captureModel.js'
import { attachVideo, readVideoFrame } from './frameReader.js'
import { onSessionReset } from '../../../utils/sessionResetBus.js'

/**
 * EKRAN PAYLAŞIMI OTURUMU — pencere başına TEK (2026-10-18). İlk "Ekrandan al" tarayıcının paylaşım seçicisini açar
 * (sekme / pencere / ekran); akış AÇIK kalır, sonraki her yuva yeniden seçmeden paylaşılan yüzeyin O ANKİ karesini alır
 * (kullanıcı Qlik sekmesine geçer, grafiği açar, döner, yuvanın "Ekrandan al"ına basar).
 *
 * <p>Akış (bütün izler) şu durumlarda durur: "Durdur", tarayıcının "Paylaşımı durdur"u (`ended`), rapordan / panelden
 * ayrılma (son kapsam bırakılınca), çıkış / oturum sıfırlama (`App.resetSessionData`), sayfa kapanışı ve
 * `CAPTURE_IDLE_MS` (10 dk) boyunca hiç kare alınmaması. Kareler tarayıcıdan ÇIKMAZ; yalnız kullanıcının onayladığı
 * bölge yüklenir.
 *
 * <p>Durum: `off` | `starting` (seçici açık) | `live`. `stopReason`: son durmanın nedeni (`user` | `ended` | `idle` |
 * `left` | `logout` | `pagehide`) — arayüz yalnız `ended` / `idle`'ı söyler.
 */

/** getDisplayMedia seçenekleri: yalnız görüntü, yüksek çözünürlük (8K'ya kadar), düşük kare hızı, yüzey değiştirme açık. */
export const DISPLAY_OPTIONS = Object.freeze({
  video: { width: { ideal: 3840, max: 7680 }, height: { ideal: 2160, max: 4320 }, frameRate: { ideal: 5, max: 10 } },
  audio: false,
  surfaceSwitching: 'include',
  selfBrowserSurface: 'exclude',
  systemAudio: 'exclude',
})

export class CaptureError extends Error {
  /** `kind`: denied (kullanıcı vazgeçti / izin yok — sessiz not) | failed | ended | busy | unsupported */
  constructor(kind, cause) {
    super(kind)
    this.name = 'CaptureError'
    this.kind = kind
    this.cause = cause
  }
}

/** Bu pencerede ekran paylaşımı var mı (güvenli bağlam + getDisplayMedia)? */
export function canCaptureScreen() {
  try {
    if (globalThis.isSecureContext === false) return false
    return typeof globalThis.navigator?.mediaDevices?.getDisplayMedia === 'function'
  } catch {
    return false
  }
}

const OFF = Object.freeze({ status: 'off', label: '', surface: '', stopReason: null, stoppedAt: 0 })

/**
 * Oturum fabrikası (testler sahte bağımlılıkla kurar). `media()`: mediaDevices; `attach(stream)` → video;
 * `read(video, track)` → kare; zamanlayıcılar enjekte edilebilir.
 */
export function createCaptureSession({
  media = () => globalThis.navigator?.mediaDevices,
  attach = attachVideo,
  read = readVideoFrame,
  idleMs = CAPTURE_IDLE_MS,
  setTimer = (fn, ms) => setTimeout(fn, ms),
  clearTimer = (h) => clearTimeout(h),
  now = () => Date.now(),
  makeController = () => (typeof globalThis.CaptureController === 'function' ? new globalThis.CaptureController() : null),
} = {}) {
  let state = OFF
  let stream = null
  let video = null
  let track = null
  let idleTimer = null
  let gen = 0
  let scopes = 0
  let releaseTimer = null
  const listeners = new Set()

  const emit = (next) => {
    state = Object.freeze({ ...state, ...next })
    for (const fn of [...listeners]) {
      try { fn() } catch { /* dinleyici hatası diğerlerini durdurmaz */ }
    }
  }

  function teardown() {
    if (idleTimer != null) { clearTimer(idleTimer); idleTimer = null }
    const s = stream
    stream = null
    track = null
    if (s) {
      try { for (const tr of s.getTracks?.() || []) tr.stop() } catch { /* yok */ }
    }
    if (video) {
      try { video.pause?.() } catch { /* yok */ }
      try { video.srcObject = null } catch { /* yok */ }
      video = null
    }
  }

  function stop(reason = 'user') {
    const wasOn = state.status !== 'off'
    gen++
    teardown()
    if (wasOn) emit({ status: 'off', label: '', surface: '', stopReason: reason, stoppedAt: now() })
  }

  function touch() {
    if (idleTimer != null) clearTimer(idleTimer)
    const my = gen
    idleTimer = setTimer(() => { if (my === gen && state.status === 'live') stop('idle') }, idleMs)
  }

  async function start() {
    const md = media()
    if (typeof md?.getDisplayMedia !== 'function') throw new CaptureError('unsupported')
    const my = ++gen
    emit({ status: 'starting', stopReason: null })
    let controller = null
    try {
      controller = makeController()
      // Paylaşılan sekmeye odak GEÇMESİN: kullanıcı kırpma penceresini burada görür (Chrome 109+)
      controller?.setFocusBehavior?.('no-focus-change')
    } catch { controller = null }
    let s
    try {
      // Kullanıcı hareketi kaybolmasın: getDisplayMedia bu fonksiyonda İLK beklenen çağrıdır
      s = await md.getDisplayMedia(controller ? { ...DISPLAY_OPTIONS, controller } : DISPLAY_OPTIONS)
    } catch (e) {
      if (my === gen) emit({ status: 'off' })
      const name = e?.name
      throw new CaptureError(name === 'NotAllowedError' || name === 'AbortError' ? 'denied' : 'failed', e)
    }
    if (my !== gen) {
      // Seçici açıkken durduruldu (çıkış, ayrılma): gelen akış hemen kapatılır
      try { for (const tr of s?.getTracks?.() || []) tr.stop() } catch { /* yok */ }
      throw new CaptureError('ended')
    }
    const t = s?.getVideoTracks?.()[0]
    if (!t) {
      try { for (const tr of s?.getTracks?.() || []) tr.stop() } catch { /* yok */ }
      emit({ status: 'off' })
      throw new CaptureError('failed')
    }
    stream = s
    track = t
    t.addEventListener?.('ended', () => { if (my === gen) stop('ended') })
    let surface = ''
    try { surface = String(t.getSettings?.().displaySurface || '') } catch { /* yok */ }
    try {
      video = await attach(s)
    } catch (e) {
      stop('failed')
      throw new CaptureError('failed', e)
    }
    if (my !== gen) throw new CaptureError('ended')
    emit({ status: 'live', label: String(t.label || ''), surface, stopReason: null })
  }

  /**
   * Paylaşılan yüzeyin o anki karesi. Paylaşım kapalıysa önce başlatır (seçici açılır — tıklama olayında
   * ÇAĞRILMALI). Seçici zaten açıksa `busy`.
   */
  async function grab() {
    if (state.status === 'starting') throw new CaptureError('busy')
    if (state.status !== 'live' || !video) await start()
    const my = gen
    let frame
    try {
      frame = await read(video, track)
    } catch (e) {
      if (my !== gen || state.status !== 'live') throw new CaptureError('ended', e)
      throw new CaptureError('failed', e)
    }
    if (my !== gen || state.status !== 'live') throw new CaptureError('ended')
    touch()
    return frame
  }

  /** Son durma notunu kaldırır (gösterildi). */
  function clearStopReason() {
    if (state.stopReason) emit({ stopReason: null })
  }

  /**
   * Kapsam: paylaşımı kullanan görünüm (rapordaki görsel bölümleri, görsel paneli) bağlıyken tutar; son kapsam
   * bırakılınca paylaşım durur ("rapordan ayrılma"). Bırakma bir tur ertelenir: aynı karede yeniden bağlanan görünüm
   * (StrictMode, yeniden kurulum) paylaşımı kesmez.
   */
  function retain() {
    scopes++
    if (releaseTimer != null) { clearTimeout(releaseTimer); releaseTimer = null }
    let released = false
    return () => {
      if (released) return
      released = true
      scopes = Math.max(0, scopes - 1)
      if (scopes > 0) return
      if (releaseTimer != null) clearTimeout(releaseTimer)
      releaseTimer = setTimeout(() => {
        releaseTimer = null
        if (scopes === 0) stop('left')
      }, 0)
    }
  }

  return {
    getState: () => state,
    subscribe: (fn) => { listeners.add(fn); return () => listeners.delete(fn) },
    grab,
    stop,
    clearStopReason,
    retain,
    /** Testler: kapsam sayısı. */
    scopeCount: () => scopes,
  }
}

/** Bu pencerenin oturumu. */
export const captureSession = createCaptureSession()

/** Çıkış / oturum sıfırlama: paylaşım varsa kapatılır (App.resetSessionData). */
export function stopCaptureSession(reason = 'logout') {
  captureSession.stop(reason)
}

/** Paylaşım durumu (React). */
export function useCaptureState(session = captureSession) {
  return useSyncExternalStore(session.subscribe, session.getState, session.getState)
}

// Çıkış / oturum düşmesi / kullanıcı değişimi (App.resetSessionData): paylaşım kapanır. App bu modülü içe aktarmaz —
// açılış paketi büyümez; modül yüklenmediyse kapatılacak paylaşım da yoktur
onSessionReset(() => captureSession.stop('logout'))

// Sayfa kapanırken akış açık kalmasın (sekme kapanınca tarayıcı da durdurur; panel penceresinde açıkça)
try {
  globalThis.addEventListener?.('pagehide', () => captureSession.stop('pagehide'))
} catch { /* yok */ }
