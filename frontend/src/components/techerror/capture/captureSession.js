import { useSyncExternalStore } from 'react'
import { CAPTURE_IDLE_MS } from './captureModel.js'
import { attachVideo, readVideoFrame, startLivePaint } from './frameReader.js'
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
 *
 * <p>CANLI ÖNİZLEME + KAYDIRMA (2026-11-19, kullanıcı isteği: "paylaşılan ekranda aşağı yukarı gidebilmeliyim, istediğim
 * noktayı seçip alabilmeliyim"): `paint(tuval)` paylaşılan yüzeyi kırpma penceresinin tuvaline canlı çizer; paylaşılan
 * yüzey bir tarayıcı SEKMESİYSE ve tarayıcı destekliyorsa (Captured Surface Control — Chrome / Edge 136+, masaüstü)
 * `forwardWheel(öğe)` o öğedeki fare tekerleğini paylaşılan sekmeye iletir (durumda `scroll: true`). Pencere / tüm ekran
 * paylaşımında tarayıcılar buna izin vermez (`scroll: false`). İlk kullanımda tarayıcı kullanıcıdan izin ister
 * (`captured-surface-control`); `scrollPermission()` izin durumunu söyler. İzin sorusu yalnız kullanıcının TIKLAMASIYLA
 * açtırılır: belgelere göre hareketsiz çağrı reddedilir (`scrollNeedsGesture`), Chromium 159 ise hareketsiz çağrıda soruyu
 * açıp yanıtı bekler (gerçek tarayıcı deneyi, 2026-11-19) — çağıran iki davranışa da dayanmaz, önce izin durumuna bakar.
 */

/** Sıradaki tekerlek çağrısı bir öncekini en çok bu kadar bekler (yanıtlanmayan izin sorusu sırayı sonsuza dek tutamaz). */
export const WHEEL_QUEUE_WAIT_MS = 10_000
/** İzin durumu sorgusu en çok bu kadar beklenir; yanıt gelmezse durum "bilinmiyor" sayılır. */
export const PERMISSION_QUERY_MS = 2000
/** Paylaşılan sekmeyi kaydırma izninin adı (Permissions API + Permissions-Policy). */
export const SCROLL_PERMISSION = 'captured-surface-control'

/**
 * getDisplayMedia seçenekleri: yalnız görüntü, yüksek çözünürlük (8K'ya kadar), düşük kare hızı (canlı önizlemede kaydırma
 * akıcı görünsün diye 10), yüzey değiştirme açık. `displaySurface: 'browser'` yalnız bir TERCİHTİR: seçici "sekme"
 * bölmesiyle açılır (açılan pencerede yalnız sekme paylaşımı buradan kaydırılabilir); kullanıcı pencere / ekran da seçebilir.
 */
export const DISPLAY_OPTIONS = Object.freeze({
  video: {
    width: { ideal: 3840, max: 7680 }, height: { ideal: 2160, max: 4320 }, frameRate: { ideal: 10, max: 10 },
    displaySurface: 'browser',
  },
  audio: false,
  surfaceSwitching: 'include',
  selfBrowserSurface: 'exclude',
  systemAudio: 'exclude',
})

export class CaptureError extends Error {
  /**
   * `kind`: denied (kullanıcı vazgeçti / izin yok — sessiz not) | failed | ended | busy | unsupported; kaydırma için
   * scrollUnsupported (sekme değil / tarayıcı desteklemiyor) | scrollNeedsGesture (izin yok — tıklama içinde çağrılmalı) |
   * scrollDenied (NotAllowedError: kullanıcı / izin politikası engelledi YA DA izin sorusu yanıtlanmadan kapatıldı —
   * hangisi olduğunu `scrollPermission()` söyler) | scrollFailed
   */
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

const OFF = Object.freeze({ status: 'off', label: '', surface: '', scroll: false, stopReason: null, stoppedAt: 0 })

/**
 * Oturum fabrikası (testler sahte bağımlılıkla kurar). `media()`: mediaDevices; `attach(stream)` → video;
 * `read(video, track)` → kare; `paintLive(video, tuval, seçenekler)` → durdurma fonksiyonu; `permissions()` → Permissions
 * API (izin durumu sorgusu); zamanlayıcılar enjekte edilebilir.
 */
export function createCaptureSession({
  media = () => globalThis.navigator?.mediaDevices,
  attach = attachVideo,
  read = readVideoFrame,
  paintLive = startLivePaint,
  permissions = () => globalThis.navigator?.permissions,
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
  // Paylaşımın denetleyicisi (CaptureController): odak davranışı + paylaşılan sekmeyi kaydırma (forwardWheel)
  let controller = null
  // forwardWheel çağrıları SIRAYLA işlenir: "durdur" ile "yeni öğe" çağrıları yarışmasın (son çağrı kazanır)
  let wheelQueue = Promise.resolve()
  // Sırada bekleyen çağrıların süre sınırı zamanlayıcıları: paylaşım durunca hepsi iptal edilir (zamanlayıcı kalmaz)
  const wheelWaits = new Set()
  // Bu paylaşımda tarayıcıya bir ÖĞE için forwardWheel çağrısı yapıldı mı — yapılmadıysa "durdur" çağrısı da yapılmaz
  // (izin sorulmamışken, kullanıcı hiçbir şeye basmadan tarayıcının kaydırma arayüzüne dokunulmaz)
  let wheelIssued = false
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
    controller = null
    wheelIssued = false
    wheelQueue = Promise.resolve()
    // Sırada bekleyenler hemen serbest kalır: zamanlayıcı kalmaz; çalışınca kuşak değiştiği için tarayıcıya çağrı yapmazlar
    for (const w of [...wheelWaits]) w.release()
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
    if (wasOn) emit({ status: 'off', label: '', surface: '', scroll: false, stopReason: reason, stoppedAt: now() })
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
    let ctrl = null
    try {
      ctrl = makeController()
      // Paylaşılan sekmeye odak GEÇMESİN: kullanıcı kırpma penceresini burada görür (Chrome 109+)
      ctrl?.setFocusBehavior?.('no-focus-change')
    } catch { ctrl = null }
    let s
    try {
      // Kullanıcı hareketi kaybolmasın: getDisplayMedia bu fonksiyonda İLK beklenen çağrıdır
      s = await md.getDisplayMedia(ctrl ? { ...DISPLAY_OPTIONS, controller: ctrl } : DISPLAY_OPTIONS)
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
    controller = ctrl
    t.addEventListener?.('ended', () => { if (my === gen) stop('ended') })
    // Tarayıcının "bunun yerine bu sekmeyi paylaş"ı (surfaceSwitching) kaynağı aynı izde değiştirir: çipin adı güncellenir
    t.addEventListener?.('configurationchange', () => { if (my === gen) refreshSource() })
    try {
      video = await attach(s)
    } catch (e) {
      stop('failed')
      throw new CaptureError('failed', e)
    }
    if (my !== gen) throw new CaptureError('ended')
    emit({ status: 'live', ...sourceOf(t), stopReason: null })
    // 10 dk sayacı paylaşım açılır açılmaz kurulur: ilk kare okunamasa da akış sonsuza dek açık kalmaz (inceleme 2026-10-18)
    touch()
  }

  /**
   * İzin o anki kaynağı: etiket (pencere / sekme başlığı), yüzey türü ve `scroll` = paylaşılan yüzey buradan
   * kaydırılabilir mi (yalnız tarayıcı SEKMESİ + `CaptureController.forwardWheel` olan tarayıcı).
   */
  function sourceOf(t) {
    let surface = ''
    try { surface = String(t?.getSettings?.().displaySurface || '') } catch { /* yok */ }
    let label = ''
    try { label = String(t?.label || '') } catch { /* yok */ }
    return { label, surface, scroll: surface === 'browser' && typeof controller?.forwardWheel === 'function' }
  }

  /** Paylaşılan kaynak değiştiyse (yüzey değiştirme) çip yeni adı gösterir; değişmediyse yayın yok. */
  function refreshSource() {
    if (state.status !== 'live' || !track) return
    const next = sourceOf(track)
    if (next.label !== state.label || next.surface !== state.surface || next.scroll !== state.scroll) emit(next)
  }

  /**
   * CANLI ÖNİZLEME: paylaşılan yüzeyin karelerini verilen tuvale çizer (kırpma penceresinin sahnesi). Döner: durdurma
   * fonksiyonu; paylaşım açık değilse `null`. Çizim yalnız durdurulana dek sürer — pencere kapanınca / dondurulunca
   * çağıran durdurur; paylaşım biterse video kare vermez.
   */
  function paint(canvas, opts) {
    if (state.status !== 'live' || !video || !canvas) return null
    try {
      const stopPaint = paintLive(video, canvas, opts)
      return typeof stopPaint === 'function' ? stopPaint : null
    } catch {
      return null
    }
  }

  /**
   * Kaydırma izninin durumu: `granted` | `prompt` (henüz sorulmadı / soru kapatıldı) | `denied` | `unknown` (tarayıcı
   * sorgulatmıyor ya da {@link PERMISSION_QUERY_MS} içinde yanıt vermedi). Hiç reddetmez; bekleme sınırlıdır.
   */
  function scrollPermission() {
    return new Promise((resolve) => {
      let done = false
      let timer = null
      const finish = (value) => {
        if (done) return
        done = true
        if (timer != null) { clearTimer(timer); timer = null }
        resolve(value)
      }
      timer = setTimer(() => { timer = null; finish('unknown') }, PERMISSION_QUERY_MS)
      try {
        const perms = permissions()
        if (typeof perms?.query !== 'function') { finish('unknown'); return }
        Promise.resolve(perms.query({ name: SCROLL_PERMISSION })).then(
          (st) => finish(st?.state === 'granted' || st?.state === 'prompt' || st?.state === 'denied' ? st.state : 'unknown'),
          () => finish('unknown'),
        )
      } catch {
        finish('unknown')
      }
    })
  }

  /**
   * Önceki tekerlek çağrısının bitmesini EN ÇOK {@link WHEEL_QUEUE_WAIT_MS} bekler (hiç reddetmez). Paylaşım durursa
   * bekleme hemen biter ({@link teardown}) — zamanlayıcı geride kalmaz.
   */
  function afterPrevious(prev) {
    return new Promise((resolve) => {
      const wait = { release: null }
      let timer = null
      wait.release = () => {
        if (!wheelWaits.delete(wait)) return
        if (timer != null) { clearTimer(timer); timer = null }
        resolve()
      }
      wheelWaits.add(wait)
      timer = setTimer(() => { timer = null; wait.release() }, WHEEL_QUEUE_WAIT_MS)
      prev.then(wait.release)
    })
  }

  /**
   * Paylaşılan SEKMEYİ kaydırma: `element` üzerindeki fare tekerleği / dokunmatik yüzey hareketi paylaşılan sekmeye
   * iletilir (`null` = iletimi durdur). Yalnız `scroll` durumu açıkken. İzin verilmemişse tarayıcı kullanıcıya sorar ve
   * söz yanıt gelene dek bekler — bu yüzden izin `prompt` iken çağrı yalnız kullanıcının tıklamasıyla yapılır (çağıran
   * önce {@link scrollPermission}'a bakar). Çağrılar sıraya konur ("durdur" ile "yeni öğe" yarışmasın); sıradaki çağrı
   * öncekini en çok {@link WHEEL_QUEUE_WAIT_MS} bekler, sonra beklemeden yapılır (yanıtsız kalan izin sorusu durdurmayı
   * tutamaz — bağlı olmayan eski öğeye iletim zararsızdır: o öğe tekerlek olayı almaz). "Durdur" yalnız bu paylaşımda bir
   * öğe için çağrı yapıldıysa tarayıcıya gider (hiç açılmamış iletim için tarayıcı arayüzüne dokunulmaz); paylaşım
   * bittiyse / değiştiyse hiç gitmez.
   */
  function forwardWheel(element) {
    const c = controller
    const my = gen
    const run = async () => {
      if (element == null) {
        if (my !== gen || !wheelIssued) return
        wheelIssued = false
        // Paylaşım bu arada bittiyse denetleyici geçersizdir; durdurma hatası söylenmez
        try { await c?.forwardWheel?.(null) } catch { /* yok */ }
        return
      }
      if (my !== gen || state.status !== 'live') throw new CaptureError('ended')
      if (!state.scroll || typeof c?.forwardWheel !== 'function') throw new CaptureError('scrollUnsupported')
      try {
        wheelIssued = true
        await c.forwardWheel(element)
      } catch (e) {
        const name = e?.name
        throw new CaptureError(name === 'NotAllowedError' ? 'scrollDenied' : name === 'InvalidStateError' ? 'scrollNeedsGesture'
          : name === 'NotSupportedError' ? 'scrollUnsupported' : 'scrollFailed', e)
      }
      if (my !== gen || state.status !== 'live') throw new CaptureError('ended')
      // İletim şimdi açık: araya giren (süre sınırıyla öne geçen) bir "durdur" bayrağı indirmiş olabilir
      wheelIssued = true
      touch()
    }
    const p = afterPrevious(wheelQueue).then(run)
    wheelQueue = p.catch(() => {})
    return p
  }

  /** Kullanıcı canlı önizlemeyle uğraşıyor (kaydırma, seçim): 10 dakikalık boşta sayacı yeniden başlar. */
  function keepAlive() {
    if (state.status === 'live') touch()
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
    // Kullanıcı tarayıcıdan paylaşılan sekmeyi değiştirmiş olabilir (olay her tarayıcıda yok): her karede ad yeniden okunur
    refreshSource()
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
    paint,
    forwardWheel,
    scrollPermission,
    keepAlive,
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
