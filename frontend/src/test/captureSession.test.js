import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  CaptureError, DISPLAY_OPTIONS, PERMISSION_QUERY_MS, SCROLL_PERMISSION, WHEEL_QUEUE_WAIT_MS, canCaptureScreen, captureSession,
  createCaptureSession,
} from '../components/techerror/capture/captureSession.js'
import { emitSessionReset } from '../utils/sessionResetBus.js'

// Ekran paylaşımı oturumu (2026-10-18): pencere başına tek akış; ilk "Ekrandan al" seçiciyi açar, sonrakiler aynı akıştan
// o anki kareyi alır. Durur: Durdur, tarayıcının "paylaşımı durdur"u (ended), son kapsamın bırakılması (rapordan
// ayrılma), çıkış (oturum sıfırlama), 10 dk kullanılmama.

function fakeStream(label = 'Qlik Sense — Kanal Raporu') {
  const listeners = {}
  const track = {
    label, stopped: false, kind: 'video',
    stop: vi.fn(() => { track.stopped = true }),
    addEventListener: (ev, fn) => { (listeners[ev] ||= []).push(fn) },
    getSettings: () => ({ displaySurface: 'browser', width: 1600, height: 900 }),
    fire: (ev) => (listeners[ev] || []).forEach((fn) => fn()),
  }
  return { track, getTracks: () => [track], getVideoTracks: () => [track] }
}

function setup(over = {}) {
  const stream = fakeStream()
  const timers = []
  const md = { getDisplayMedia: vi.fn(async () => stream) }
  const frame = { width: 1600, height: 900, canvas: {}, uniform: false }
  const session = createCaptureSession({
    media: () => md,
    attach: vi.fn(async () => ({ pause: vi.fn(), srcObject: null })),
    read: vi.fn(async () => frame),
    idleMs: 1000,
    setTimer: (fn, ms) => { const h = { fn, ms, on: true }; timers.push(h); return h },
    clearTimer: (h) => { if (h) h.on = false },
    makeController: () => null,
    ...over,
  })
  const fireIdle = () => { for (const h of timers.filter((x) => x.on)) { h.on = false; h.fn() } }
  return { session, md, stream, frame, fireIdle, timers }
}

describe('ekran paylaşımı oturumu', () => {
  it('ilk grab seçiciyi açar (yalnız görüntü, yüksek çözünürlük, düşük kare hızı, yüzey değiştirme); sonraki grab seçmeden kare alır', async () => {
    const { session, md, frame } = setup()
    expect(session.getState().status).toBe('off')
    const f1 = await session.grab()
    expect(f1).toBe(frame)
    expect(md.getDisplayMedia).toHaveBeenCalledTimes(1)
    const opts = md.getDisplayMedia.mock.calls[0][0]
    expect(opts.audio).toBe(false)
    expect(opts.surfaceSwitching).toBe('include')
    expect(opts.video.frameRate.max).toBeLessThanOrEqual(10)
    expect(opts.video.width.ideal).toBeGreaterThanOrEqual(3840)
    expect(DISPLAY_OPTIONS.audio).toBe(false)
    expect(session.getState()).toMatchObject({ status: 'live', label: 'Qlik Sense — Kanal Raporu', surface: 'browser' })
    await session.grab()
    expect(md.getDisplayMedia).toHaveBeenCalledTimes(1)
  })

  it('getDisplayMedia ilk çağrı (kullanıcı hareketi kaybolmaz): grab çağrıldığı anda, beklemeden', () => {
    const { session, md } = setup()
    session.grab().catch(() => {})
    expect(md.getDisplayMedia).toHaveBeenCalledTimes(1)
    expect(session.getState().status).toBe('starting')
  })

  it('"Durdur" tüm izleri kapatır; sonraki grab yeniden seçici açar', async () => {
    const { session, md, stream } = setup()
    await session.grab()
    session.stop('user')
    expect(stream.track.stop).toHaveBeenCalled()
    expect(session.getState()).toMatchObject({ status: 'off', stopReason: 'user' })
    await session.grab()
    expect(md.getDisplayMedia).toHaveBeenCalledTimes(2)
  })

  it('tarayıcıdan "Paylaşımı durdur" (ended) → kapanır, neden ended', async () => {
    const { session, stream } = setup()
    await session.grab()
    stream.track.fire('ended')
    expect(session.getState()).toMatchObject({ status: 'off', stopReason: 'ended' })
    session.clearStopReason()
    expect(session.getState().stopReason).toBeNull()
  })

  it('10 dk (burada 1 sn) kare alınmazsa durur; her kare sayacı yeniden kurar', async () => {
    const { session, stream, fireIdle, timers } = setup()
    await session.grab()
    expect(timers.at(-1).ms).toBe(1000)
    await session.grab()
    // Önceki zamanlayıcı iptal edildi, yalnız sonuncusu açık
    expect(timers.filter((h) => h.on)).toHaveLength(1)
    fireIdle()
    expect(stream.track.stop).toHaveBeenCalled()
    expect(session.getState()).toMatchObject({ status: 'off', stopReason: 'idle' })
  })

  it('kullanıcı seçiciden vazgeçer (NotAllowedError) → denied, durum kapalı; başka hata → failed', async () => {
    const denied = Object.assign(new Error('x'), { name: 'NotAllowedError' })
    const a = setup({ media: () => ({ getDisplayMedia: vi.fn(async () => { throw denied }) }) })
    await expect(a.session.grab()).rejects.toMatchObject({ kind: 'denied' })
    expect(a.session.getState().status).toBe('off')
    const other = Object.assign(new Error('x'), { name: 'NotReadableError' })
    const b = setup({ media: () => ({ getDisplayMedia: vi.fn(async () => { throw other }) }) })
    await expect(b.session.grab()).rejects.toMatchObject({ kind: 'failed' })
    const c = setup({ media: () => ({}) })
    await expect(c.session.grab()).rejects.toBeInstanceOf(CaptureError)
  })

  it('seçici açıkken ikinci grab → busy; seçici açıkken durdurulursa gelen akış hemen kapatılır', async () => {
    let resolve
    const stream = fakeStream()
    const { session } = setup({ media: () => ({ getDisplayMedia: () => new Promise((r) => { resolve = r }) }) })
    const p = session.grab()
    await expect(session.grab()).rejects.toMatchObject({ kind: 'busy' })
    session.stop('logout')
    resolve(stream)
    await expect(p).rejects.toMatchObject({ kind: 'ended' })
    expect(stream.track.stop).toHaveBeenCalled()
    expect(session.getState().status).toBe('off')
  })

  it('kapsam: son kapsam bırakılınca (bir tur sonra) paylaşım durur; aynı turda yeniden bağlanan kapsam kesmez', async () => {
    const { session, stream } = setup()
    const r1 = session.retain()
    await session.grab()
    r1()
    const r2 = session.retain()   // StrictMode / yeniden kurulum
    await new Promise((r) => setTimeout(r, 5))
    expect(session.getState().status).toBe('live')
    r2()
    r2()   // ikinci bırakma yok sayılır
    expect(session.scopeCount()).toBe(0)
    await new Promise((r) => setTimeout(r, 5))
    expect(stream.track.stop).toHaveBeenCalled()
    expect(session.getState()).toMatchObject({ status: 'off', stopReason: 'left' })
  })

  it('kare okunamazsa: akış bittiyse ended, değilse failed (neden korunur)', async () => {
    const noFrame = Object.assign(new Error('noFrame'), { kind: 'noFrame' })
    const { session } = setup({ read: vi.fn(async () => { throw noFrame }) })
    const e = await session.grab().catch((x) => x)
    expect(e).toMatchObject({ kind: 'failed' })
    expect(e.cause.kind).toBe('noFrame')
  })

  it('ilk kare okunamasa da 10 dk sayacı kuruludur: paylaşım açık kalmaz (inceleme 2026-10-18)', async () => {
    const noFrame = Object.assign(new Error('noFrame'), { kind: 'noFrame' })
    const { session, stream, fireIdle, timers } = setup({ read: vi.fn(async () => { throw noFrame }) })
    await session.grab().catch(() => {})
    expect(session.getState().status).toBe('live')
    expect(timers.filter((h) => h.on)).toHaveLength(1)
    fireIdle()
    expect(stream.track.stop).toHaveBeenCalled()
    expect(session.getState()).toMatchObject({ status: 'off', stopReason: 'idle' })
  })

  it('yüzey değiştirme (tarayıcıdan "bunun yerine bunu paylaş"): çipin adı sonraki karede ve configurationchange olayında güncellenir', async () => {
    const { session, stream } = setup()
    await session.grab()
    expect(session.getState()).toMatchObject({ label: 'Qlik Sense — Kanal Raporu', surface: 'browser' })
    stream.track.label = 'Grafana — Kanal Panosu'
    await session.grab()
    expect(session.getState()).toMatchObject({ status: 'live', label: 'Grafana — Kanal Panosu' })
    stream.track.label = 'screen:0:0'
    stream.track.getSettings = () => ({ displaySurface: 'monitor' })
    const seen = vi.fn()
    const off = session.subscribe(seen)
    stream.track.fire('configurationchange')
    expect(session.getState()).toMatchObject({ label: 'screen:0:0', surface: 'monitor' })
    expect(seen).toHaveBeenCalledTimes(1)
    // Değişiklik yoksa yayın yok
    stream.track.fire('configurationchange')
    expect(seen).toHaveBeenCalledTimes(1)
    off()
  })
})

// CANLI ÖNİZLEME + KAYDIRMA (2026-11-19, kullanıcı isteği: "paylaşılan ekranda aşağı yukarı gidebilmeliyim, istediğim
// noktayı seçip alabilmeliyim"). Gerçek Chromium 159 deneyi: izin verilmişken forwardWheel hareketsiz de çalışır ve
// tekerlek paylaşılan sekmeyi kaydırır; izin sorulmamışken çağrı hata vermez, izin sorusu yanıtlanana dek BEKLER.
describe('canlı önizleme ve paylaşılan sekmeyi kaydırma', () => {
  const controllerOf = (forwardWheel = vi.fn(async () => {})) => ({ setFocusBehavior: vi.fn(), forwardWheel })
  const tick = () => new Promise((r) => setTimeout(r, 0))
  const idleTimers = (timers) => timers.filter((h) => h.on && h.ms === 1000)

  it('seçici sekme bölmesiyle açılır; scroll yalnız sekme + forwardWheel olan tarayıcıda açık (pencere / ekran / eski tarayıcı kapalı)', async () => {
    expect(DISPLAY_OPTIONS.video.displaySurface).toBe('browser')
    const ctrl = controllerOf()
    const a = setup({ makeController: () => ctrl })
    expect(a.session.getState().scroll).toBe(false)
    await a.session.grab()
    expect(a.md.getDisplayMedia.mock.calls[0][0].controller).toBe(ctrl)
    expect(a.md.getDisplayMedia.mock.calls[0][0].video.displaySurface).toBe('browser')
    expect(ctrl.setFocusBehavior).toHaveBeenCalledWith('no-focus-change')
    expect(a.session.getState()).toMatchObject({ status: 'live', surface: 'browser', scroll: true })
    // Tarayıcıdan "bunun yerine bu pencereyi paylaş": kaydırma kapanır (yalnız sekme kaydırılabilir)
    a.stream.track.getSettings = () => ({ displaySurface: 'window' })
    a.stream.track.fire('configurationchange')
    expect(a.session.getState()).toMatchObject({ surface: 'window', scroll: false })
    a.session.stop('user')
    expect(a.session.getState().scroll).toBe(false)
    // Denetleyici yok (CaptureController'sız tarayıcı)
    const b = setup()
    await b.session.grab()
    expect(b.session.getState()).toMatchObject({ surface: 'browser', scroll: false })
    // forwardWheel'i olmayan denetleyici (Chrome < 136)
    const c = setup({ makeController: () => ({ setFocusBehavior: vi.fn() }) })
    await c.session.grab()
    expect(c.session.getState().scroll).toBe(false)
  })

  it('paint: paylaşım açıkken videoyu tuvale çizdirir, durdurma fonksiyonunu döndürür; kapalıyken / hata olursa null', async () => {
    const stopPaint = vi.fn()
    const paintLive = vi.fn(() => stopPaint)
    const video = { pause: vi.fn(), srcObject: null }
    const { session } = setup({ paintLive, attach: vi.fn(async () => video) })
    const canvas = {}
    expect(session.paint(canvas)).toBeNull()
    expect(paintLive).not.toHaveBeenCalled()
    await session.grab()
    const opts = { onResize: vi.fn() }
    expect(session.paint(canvas, opts)).toBe(stopPaint)
    expect(paintLive).toHaveBeenCalledWith(video, canvas, opts)
    expect(session.paint(null)).toBeNull()
    paintLive.mockImplementationOnce(() => { throw new Error('x') })
    expect(session.paint(canvas)).toBeNull()
    paintLive.mockImplementationOnce(() => undefined)
    expect(session.paint(canvas)).toBeNull()
    session.stop('user')
    expect(session.paint(canvas)).toBeNull()
  })

  it('forwardWheel: öğeyi denetleyiciye verir ve boşta sayacını yeniler; null iletimi durdurur, hiç reddetmez', async () => {
    const fw = vi.fn(async () => {})
    const { session, timers } = setup({ makeController: () => controllerOf(fw) })
    await session.grab()
    const before = idleTimers(timers)
    expect(before).toHaveLength(1)
    const el = { id: 'pad' }
    await session.forwardWheel(el)
    expect(fw).toHaveBeenCalledWith(el)
    expect(before[0].on).toBe(false)
    expect(idleTimers(timers)).toHaveLength(1)
    await expect(session.forwardWheel(null)).resolves.toBeUndefined()
    expect(fw).toHaveBeenLastCalledWith(null)
    // Durdurma hatası söylenmez (paylaşım bittiyse denetleyici zaten geçersizdir)
    fw.mockRejectedValueOnce(Object.assign(new Error('x'), { name: 'InvalidStateError' }))
    await expect(session.forwardWheel(null)).resolves.toBeUndefined()
    session.stop('user')
    await expect(session.forwardWheel(null)).resolves.toBeUndefined()
    await expect(session.forwardWheel(el)).rejects.toMatchObject({ kind: 'ended' })
    // Sıra zamanlayıcısı açık kalmadı
    expect(timers.filter((h) => h.on && h.ms === WHEEL_QUEUE_WAIT_MS)).toHaveLength(0)
  })

  it('forwardWheel hataları türe çevrilir (neden korunur): NotAllowedError, InvalidStateError, NotSupportedError, diğer', async () => {
    const fw = vi.fn()
    const { session } = setup({ makeController: () => controllerOf(fw) })
    await session.grab()
    const table = [['NotAllowedError', 'scrollDenied'], ['InvalidStateError', 'scrollNeedsGesture'],
      ['NotSupportedError', 'scrollUnsupported'], ['AbortError', 'scrollFailed']]
    for (const [name, kind] of table) {
      const cause = Object.assign(new Error('x'), { name })
      fw.mockRejectedValueOnce(cause)
      const e = await session.forwardWheel({}).catch((x) => x)
      expect(e).toBeInstanceOf(CaptureError)
      expect(e).toMatchObject({ kind, cause })
    }
    // Çağrı sürerken paylaşım biterse sonuç "ended"dir (başarı sayılmaz)
    let finish
    fw.mockImplementationOnce(() => new Promise((r) => { finish = r }))
    const p = session.forwardWheel({})
    await tick()
    session.stop('ended')
    finish()
    await expect(p).rejects.toMatchObject({ kind: 'ended' })
  })

  it('pencere / ekran paylaşımında forwardWheel scrollUnsupported; denetleyici hiç çağrılmaz', async () => {
    const fw = vi.fn(async () => {})
    const { session, stream } = setup({ makeController: () => controllerOf(fw) })
    stream.track.getSettings = () => ({ displaySurface: 'monitor' })
    await session.grab()
    expect(session.getState().scroll).toBe(false)
    await expect(session.forwardWheel({})).rejects.toMatchObject({ kind: 'scrollUnsupported' })
    expect(fw).not.toHaveBeenCalled()
  })

  it('çağrılar sırayla işlenir; yanıtsız kalan çağrı (açık izin sorusu) sıradakini en çok 10 sn tutar', async () => {
    let allow
    const calls = []
    const fw = vi.fn((el) => { calls.push(el); return el === 'pad1' ? new Promise((r) => { allow = r }) : Promise.resolve() })
    const { session, timers } = setup({ makeController: () => controllerOf(fw) })
    await session.grab()
    const p1 = session.forwardWheel('pad1')
    const stopped = session.forwardWheel(null)
    await tick()
    // İzin sorusu yanıt bekliyor: durdurma sırada, denetleyiciye gitmedi
    expect(calls).toEqual(['pad1'])
    const waiting = timers.filter((h) => h.on && h.ms === WHEEL_QUEUE_WAIT_MS)
    expect(waiting).toHaveLength(1)
    // 10 sn doldu: durdurma beklemeden yapılır
    waiting[0].on = false
    waiting[0].fn()
    await stopped
    expect(calls).toEqual(['pad1', null])
    // Kullanıcı sonunda izin verdi: ilk çağrı biter, sıra boşalır, sonraki çağrı hemen çalışır
    allow()
    await p1
    await session.forwardWheel('pad2')
    expect(calls).toEqual(['pad1', null, 'pad2'])
    expect(timers.filter((h) => h.on && h.ms === WHEEL_QUEUE_WAIT_MS)).toHaveLength(0)
  })

  it('sıra: önceki çağrı bitince sıradaki çalışır (durdur → yeni öğe yarışmaz)', async () => {
    const order = []
    let release
    const fw = vi.fn((el) => {
      order.push(`başla:${el}`)
      if (el !== 'a') { order.push(`bitti:${el}`); return Promise.resolve() }
      return new Promise((r) => { release = () => { order.push('bitti:a'); r() } })
    })
    const { session } = setup({ makeController: () => controllerOf(fw) })
    await session.grab()
    const pa = session.forwardWheel('a')
    const pn = session.forwardWheel(null)
    const pb = session.forwardWheel('b')
    await tick()
    expect(order).toEqual(['başla:a'])
    release()
    await Promise.all([pa, pn, pb])
    expect(order).toEqual(['başla:a', 'bitti:a', 'başla:null', 'bitti:null', 'başla:b', 'bitti:b'])
  })

  it('scrollPermission: granted / prompt / denied; sorgu yoksa, hata verirse ya da 2 sn yanıt gelmezse unknown — hiç reddetmez', async () => {
    const query = vi.fn(async () => ({ state: 'prompt' }))
    const a = setup({ permissions: () => ({ query }) })
    await expect(a.session.scrollPermission()).resolves.toBe('prompt')
    expect(query).toHaveBeenCalledWith({ name: SCROLL_PERMISSION })
    expect(SCROLL_PERMISSION).toBe('captured-surface-control')
    query.mockResolvedValueOnce({ state: 'granted' })
    await expect(a.session.scrollPermission()).resolves.toBe('granted')
    query.mockResolvedValueOnce({ state: 'denied' })
    await expect(a.session.scrollPermission()).resolves.toBe('denied')
    query.mockResolvedValueOnce({ state: 'başka' })
    await expect(a.session.scrollPermission()).resolves.toBe('unknown')
    // Tarayıcı bu izin adını tanımıyor (TypeError)
    query.mockRejectedValueOnce(new TypeError('name'))
    await expect(a.session.scrollPermission()).resolves.toBe('unknown')
    query.mockImplementationOnce(() => { throw new TypeError('x') })
    await expect(a.session.scrollPermission()).resolves.toBe('unknown')
    expect(a.timers.filter((h) => h.on)).toHaveLength(0)
    const b = setup({ permissions: () => undefined })
    await expect(b.session.scrollPermission()).resolves.toBe('unknown')
    // Sorgu hiç yanıt vermez: süre dolunca bilinmiyor
    const c = setup({ permissions: () => ({ query: () => new Promise(() => {}) }) })
    const p = c.session.scrollPermission()
    const limit = c.timers.filter((h) => h.on && h.ms === PERMISSION_QUERY_MS)
    expect(limit).toHaveLength(1)
    limit[0].on = false
    limit[0].fn()
    await expect(p).resolves.toBe('unknown')
  })

  it('keepAlive: paylaşım açıkken boşta sayacını yeniler; kapalıyken sayaç kurmaz', async () => {
    const { session, stream, timers, fireIdle } = setup()
    session.keepAlive()
    expect(timers).toHaveLength(0)
    await session.grab()
    const first = idleTimers(timers)
    session.keepAlive()
    expect(first[0].on).toBe(false)
    expect(idleTimers(timers)).toHaveLength(1)
    fireIdle()
    expect(stream.track.stop).toHaveBeenCalled()
    expect(session.getState()).toMatchObject({ status: 'off', stopReason: 'idle', scroll: false })
  })
})

describe('pencerenin oturumu', () => {
  const md = { getDisplayMedia: vi.fn() }
  beforeEach(() => {
    Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: md })
  })
  afterEach(() => {
    captureSession.stop('test')
    delete navigator.mediaDevices
  })

  it('canCaptureScreen: getDisplayMedia varsa ve bağlam güvenliyse; yoksa false', () => {
    expect(canCaptureScreen()).toBe(true)
    Object.defineProperty(window, 'isSecureContext', { configurable: true, value: false })
    expect(canCaptureScreen()).toBe(false)
    delete window.isSecureContext
    delete navigator.mediaDevices
    expect(canCaptureScreen()).toBe(false)
  })

  it('oturum sıfırlama (çıkış / kullanıcı değişimi) açık paylaşımı kapatır', async () => {
    const s = fakeStream()
    md.getDisplayMedia.mockResolvedValue(s)
    // jsdom: video oynatma yok — attach/read gerçek, ama durum "starting" geçişi yeter: oturum kapanınca gelen akış kapanır
    const p = captureSession.grab().catch((e) => e)
    expect(captureSession.getState().status).toBe('starting')
    emitSessionReset()
    const e = await p
    expect(e).toMatchObject({ kind: 'ended' })
    expect(s.track.stop).toHaveBeenCalled()
    expect(captureSession.getState().status).toBe('off')
  })
})
