import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { LIVE_PAINT_POLL_MS, frameUniform, startLivePaint } from '../components/techerror/capture/frameReader.js'
import { restoreRealTimers } from './helpers/fakeTimers.js'

// Canlı önizleme çizimi (2026-11-19): paylaşılan sekmenin kareleri kırpma penceresinin tuvaline çizilir. Yeni kare
// requestVideoFrameCallback ile hemen; o olmayan / sessiz kalan tarayıcıda 200 ms'lik yoklama yalnız YENİ kare geldiyse
// çizer. Durdurma fonksiyonu çağrılınca hiçbir zamanlayıcı / geri çağrı kalmaz (sonsuz döngü yok).

function fakeVideo({ rvfc = true, quality = true } = {}) {
  const callbacks = new Map()
  let next = 1
  const v = { videoWidth: 1600, videoHeight: 900, frames: 0, cancelled: [] }
  if (rvfc) {
    v.requestVideoFrameCallback = vi.fn((fn) => { const h = next++; callbacks.set(h, fn); return h })
    v.cancelVideoFrameCallback = vi.fn((h) => { v.cancelled.push(h); callbacks.delete(h) })
  }
  if (quality) v.getVideoPlaybackQuality = () => ({ totalVideoFrames: v.frames })
  /** Tarayıcı yeni kare üretti: sayaç artar, bekleyen kare geri çağrıları çalışır. */
  v.newFrame = ({ notify = true } = {}) => {
    v.frames++
    if (!notify) return
    const pending = [...callbacks.values()]
    callbacks.clear()
    pending.forEach((fn) => fn(0, {}))
  }
  v.pendingCallbacks = () => callbacks.size
  return v
}

function fakeCanvas() {
  const ctx = { drawImage: vi.fn() }
  return { width: 300, height: 150, ctx, getContext: vi.fn(() => ctx) }
}

beforeEach(() => { vi.useFakeTimers() })
afterEach(() => { restoreRealTimers() })

describe('canlı önizleme çizimi', () => {
  it('hemen ilk kareyi çizer: tuval videonun ölçüsünü alır, onResize bir kez çağrılır', () => {
    const video = fakeVideo()
    const canvas = fakeCanvas()
    const onResize = vi.fn()
    const stop = startLivePaint(video, canvas, { onResize })
    expect(canvas.width).toBe(1600)
    expect(canvas.height).toBe(900)
    expect(onResize).toHaveBeenCalledTimes(1)
    expect(onResize).toHaveBeenCalledWith(1600, 900)
    expect(canvas.ctx.drawImage).toHaveBeenCalledTimes(1)
    expect(canvas.ctx.drawImage).toHaveBeenCalledWith(video, 0, 0, 1600, 900)
    stop()
  })

  it('yeni kare bildirimi (requestVideoFrameCallback) hemen çizilir ve yeniden kurulur; ölçü değişmediyse onResize yok', () => {
    const video = fakeVideo()
    const canvas = fakeCanvas()
    const onResize = vi.fn()
    const stop = startLivePaint(video, canvas, { onResize })
    expect(video.pendingCallbacks()).toBe(1)
    video.newFrame()
    video.newFrame()
    expect(canvas.ctx.drawImage).toHaveBeenCalledTimes(3)
    expect(video.pendingCallbacks()).toBe(1)
    expect(onResize).toHaveBeenCalledTimes(1)
    stop()
  })

  it('durağan sayfa: kare sayacı değişmedikçe yoklama tuvali yeniden boyamaz; bildirimsiz gelen yeni kare bir sonraki yoklamada çizilir', () => {
    const video = fakeVideo()
    const canvas = fakeCanvas()
    const stop = startLivePaint(video, canvas)
    vi.advanceTimersByTime(LIVE_PAINT_POLL_MS * 5)
    expect(canvas.ctx.drawImage).toHaveBeenCalledTimes(1)
    // Kare geldi ama geri çağrı sessiz kaldı (sekme arka planda kısılmış olabilir)
    video.newFrame({ notify: false })
    vi.advanceTimersByTime(LIVE_PAINT_POLL_MS)
    expect(canvas.ctx.drawImage).toHaveBeenCalledTimes(2)
    vi.advanceTimersByTime(LIVE_PAINT_POLL_MS * 3)
    expect(canvas.ctx.drawImage).toHaveBeenCalledTimes(2)
    stop()
  })

  it('requestVideoFrameCallback ve kare sayacı olmayan tarayıcıda her yoklamada çizer', () => {
    const video = fakeVideo({ rvfc: false, quality: false })
    const canvas = fakeCanvas()
    const stop = startLivePaint(video, canvas)
    expect(canvas.ctx.drawImage).toHaveBeenCalledTimes(1)
    vi.advanceTimersByTime(LIVE_PAINT_POLL_MS * 3)
    expect(canvas.ctx.drawImage).toHaveBeenCalledTimes(4)
    stop()
  })

  it('paylaşılan pencere boyutlanırsa tuval yeni ölçüyü alır ve onResize yeniden çağrılır; onResize hatası çizimi durdurmaz', () => {
    const video = fakeVideo()
    const canvas = fakeCanvas()
    const onResize = vi.fn(() => { throw new Error('çağıranın hatası') })
    const stop = startLivePaint(video, canvas, { onResize })
    expect(canvas.ctx.drawImage).toHaveBeenCalledTimes(1)
    video.videoWidth = 1280
    video.videoHeight = 720
    // Sayaç değişmese de ölçü farkı yoklamada yakalanır
    vi.advanceTimersByTime(LIVE_PAINT_POLL_MS)
    expect(canvas.width).toBe(1280)
    expect(canvas.height).toBe(720)
    expect(onResize).toHaveBeenLastCalledWith(1280, 720)
    expect(canvas.ctx.drawImage).toHaveBeenLastCalledWith(video, 0, 0, 1280, 720)
    stop()
  })

  it('henüz görüntü yokken (ölçü 0) çizmez, hata vermez; görüntü gelince çizer', () => {
    const video = fakeVideo()
    video.videoWidth = 0
    video.videoHeight = 0
    const canvas = fakeCanvas()
    const onResize = vi.fn()
    const stop = startLivePaint(video, canvas, { onResize })
    expect(canvas.ctx.drawImage).not.toHaveBeenCalled()
    expect(canvas.width).toBe(300)
    video.videoWidth = 800
    video.videoHeight = 600
    video.newFrame()
    expect(canvas.ctx.drawImage).toHaveBeenCalledWith(video, 0, 0, 800, 600)
    expect(onResize).toHaveBeenCalledWith(800, 600)
    stop()
  })

  it('durdurunca hiçbir şey kalmaz: yoklama zamanlayıcısı ve kare geri çağrısı iptal edilir, sonra gelen kare çizilmez', () => {
    const video = fakeVideo()
    const canvas = fakeCanvas()
    const stop = startLivePaint(video, canvas)
    expect(vi.getTimerCount()).toBe(1)
    stop()
    expect(vi.getTimerCount()).toBe(0)
    expect(video.cancelVideoFrameCallback).toHaveBeenCalledTimes(1)
    expect(video.pendingCallbacks()).toBe(0)
    video.newFrame()
    vi.advanceTimersByTime(LIVE_PAINT_POLL_MS * 10)
    expect(canvas.ctx.drawImage).toHaveBeenCalledTimes(1)
    // İkinci durdurma zararsız
    expect(() => stop()).not.toThrow()
  })

  it('çizim hatası (tuval bırakıldı) o kareyi atlar, döngüyü bozmaz', () => {
    const video = fakeVideo()
    const canvas = fakeCanvas()
    const stop = startLivePaint(video, canvas)
    canvas.ctx.drawImage.mockImplementationOnce(() => { throw new Error('tuval yok') })
    expect(() => video.newFrame()).not.toThrow()
    video.newFrame()
    expect(canvas.ctx.drawImage).toHaveBeenCalledTimes(3)
    stop()
  })

  it('video ya da 2B bağlam yoksa hiçbir şey kurmaz; dönen fonksiyon yine çağrılabilir', () => {
    const noCtx = { width: 1, height: 1, getContext: () => null }
    const a = startLivePaint(fakeVideo(), noCtx)
    const b = startLivePaint(null, fakeCanvas())
    const c = startLivePaint(fakeVideo(), { getContext: () => { throw new Error('x') } })
    expect(vi.getTimerCount()).toBe(0)
    expect(() => { a(); b(); c() }).not.toThrow()
  })
})

describe('donuk karenin tek renk denetimi', () => {
  it('kare / tuval yoksa false', () => {
    expect(frameUniform(null)).toBe(false)
    expect(frameUniform({})).toBe(false)
    expect(frameUniform({ canvas: null })).toBe(false)
  })
})
