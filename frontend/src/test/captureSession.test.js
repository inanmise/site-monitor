import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import {
  CaptureError, DISPLAY_OPTIONS, canCaptureScreen, captureSession, createCaptureSession,
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
