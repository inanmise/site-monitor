import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook } from '@testing-library/react'
import { BUSY_MAX_MS, useVisibleInterval } from '../hooks/useVisibleInterval.js'

// Görünürlük-farkındalıklı polling hook'u: gizli sekmede durur, görünürde 1 tazeleme + devam.
// Sahte zamanlayıcı + document.hidden manipülasyonu ile deterministik.

function setHidden(hidden) {
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden })
  document.dispatchEvent(new Event('visibilitychange'))
}

describe('useVisibleInterval', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => false })
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('immediate=true (varsayılan): mount\'ta 1 kez + her interval\'de çağırır', () => {
    const fn = vi.fn()
    renderHook(() => useVisibleInterval(fn, 1000))
    expect(fn).toHaveBeenCalledTimes(1)      // mount'taki anlık çağrı
    vi.advanceTimersByTime(3000)
    expect(fn).toHaveBeenCalledTimes(4)       // + 3 tick
  })

  it('immediate=false: mount\'ta çağırmaz, yalnız interval\'de', () => {
    const fn = vi.fn()
    renderHook(() => useVisibleInterval(fn, 1000, false))
    expect(fn).toHaveBeenCalledTimes(0)
    vi.advanceTimersByTime(2000)
    expect(fn).toHaveBeenCalledTimes(2)
  })

  it('ms <= 0 veya null → hiç kurulmaz (çağrı yok)', () => {
    const fn = vi.fn()
    renderHook(() => useVisibleInterval(fn, 0))
    vi.advanceTimersByTime(5000)
    expect(fn).not.toHaveBeenCalled()

    const fn2 = vi.fn()
    renderHook(() => useVisibleInterval(fn2, null))
    vi.advanceTimersByTime(5000)
    expect(fn2).not.toHaveBeenCalled()
  })

  it('sekme gizlenince interval durur; görünür olunca 1 tazeleme + devam eder', () => {
    const fn = vi.fn()
    renderHook(() => useVisibleInterval(fn, 1000))
    fn.mockClear()                            // mount'taki anlık çağrıyı yok say

    vi.advanceTimersByTime(1000)
    expect(fn).toHaveBeenCalledTimes(1)       // 1 tick

    setHidden(true)                           // gizli → dur
    vi.advanceTimersByTime(5000)
    expect(fn).toHaveBeenCalledTimes(1)       // gizliyken tick yok

    setHidden(false)                          // görünür → anlık 1 tazeleme + yeniden başlat
    expect(fn).toHaveBeenCalledTimes(2)
    vi.advanceTimersByTime(1000)
    expect(fn).toHaveBeenCalledTimes(3)       // interval devam
  })

  // Performans (2026-11-06, PF4 O1): kısa bir sekme değişiminden dönüşte sayfadaki bütün yoklamalar aynı anda
  // istek atıyordu. Son çağrının üstünden `ms` geçmediyse dönüşte istek YOK; kalan süre dolunca çağrılır.
  it('ms dolmadan dönüş: anında çağrı YOK, kalan süre dolunca çağrılır ve tempo sürer', () => {
    const fn = vi.fn()
    renderHook(() => useVisibleInterval(fn, 1000))
    fn.mockClear()
    vi.advanceTimersByTime(300)
    setHidden(true)
    vi.advanceTimersByTime(200)                // son çağrıdan bu yana 500 ms
    setHidden(false)
    expect(fn).not.toHaveBeenCalled()          // eskisi: dönüşte anında 1 çağrı
    vi.advanceTimersByTime(499)
    expect(fn).not.toHaveBeenCalled()
    vi.advanceTimersByTime(1)                  // son çağrıdan tam 1000 ms
    expect(fn).toHaveBeenCalledTimes(1)
    vi.advanceTimersByTime(1000)
    expect(fn).toHaveBeenCalledTimes(2)        // interval yeniden kuruldu
  })

  it('kalan süre beklenirken yeniden gizlenirse bekleyen çağrı İPTAL olur', () => {
    const fn = vi.fn()
    renderHook(() => useVisibleInterval(fn, 1000))
    fn.mockClear()
    setHidden(true)
    vi.advanceTimersByTime(100)
    setHidden(false)                           // 900 ms kaldı → zamanlayıcı kuruldu
    setHidden(true)                            // tekrar gizli
    vi.advanceTimersByTime(5000)
    expect(fn).not.toHaveBeenCalled()
    setHidden(false)                           // süre çoktan doldu → anında
    expect(fn).toHaveBeenCalledTimes(1)
  })

  it('immediate=false: dönüş temposu kurulum anından sayılır', () => {
    const fn = vi.fn()
    renderHook(() => useVisibleInterval(fn, 1000, false))
    vi.advanceTimersByTime(400)
    setHidden(true)
    setHidden(false)
    expect(fn).not.toHaveBeenCalled()
    vi.advanceTimersByTime(600)
    expect(fn).toHaveBeenCalledTimes(1)
  })

  it('gizlenmeden gelen görünür olayı eskisi gibi anında 1 tur çalıştırır, ikinci interval KURMAZ', () => {
    const fn = vi.fn()
    renderHook(() => useVisibleInterval(fn, 1000))
    fn.mockClear()
    setHidden(false)
    setHidden(false)
    expect(fn).toHaveBeenCalledTimes(2)
    vi.advanceTimersByTime(1000)
    expect(fn).toHaveBeenCalledTimes(3)        // tek interval
  })

  it('kalan süre beklenirken gelen ikinci görünür olayı ek çağrı yapmaz', () => {
    const fn = vi.fn()
    renderHook(() => useVisibleInterval(fn, 1000))
    fn.mockClear()
    setHidden(true)
    vi.advanceTimersByTime(100)
    setHidden(false)
    setHidden(false)
    expect(fn).not.toHaveBeenCalled()
    vi.advanceTimersByTime(900)
    expect(fn).toHaveBeenCalledTimes(1)
    vi.advanceTimersByTime(1000)
    expect(fn).toHaveBeenCalledTimes(2)
  })

  it('unmount interval\'i temizler (sonrasında çağrı yok)', () => {
    const fn = vi.fn()
    const { unmount } = renderHook(() => useVisibleInterval(fn, 1000))
    fn.mockClear()
    unmount()
    vi.advanceTimersByTime(5000)
    expect(fn).not.toHaveBeenCalled()
  })

  // ── Yığılma yok (2026-10-09): söz dönen fn bitmeden sonraki tur atlanır ──
  it('söz dönen fn: önceki söz bitmeden turlar ATLANIR (üst üste istek yok); bitince sıradaki tur çalışır', async () => {
    let release
    const fn = vi.fn(() => new Promise((r) => { release = r }))
    renderHook(() => useVisibleInterval(fn, 1000))
    expect(fn).toHaveBeenCalledTimes(1)
    vi.advanceTimersByTime(5000)
    expect(fn).toHaveBeenCalledTimes(1)       // eskisi: 6 eşzamanlı çağrı
    release()
    await Promise.resolve(); await Promise.resolve()
    vi.advanceTimersByTime(1000)
    expect(fn).toHaveBeenCalledTimes(2)
  })

  it('reddedilen söz de turu serbest bırakır (ret kancada yutulmaz, yeniden fırlatılır)', () => {
    let fail
    const thenable = { then(_ok, err) { fail = () => { try { err(new Error('x')) } catch { /* kancanın yeniden fırlatması */ } } } }
    const fn = vi.fn(() => thenable)
    renderHook(() => useVisibleInterval(fn, 1000))
    vi.advanceTimersByTime(3000)
    expect(fn).toHaveBeenCalledTimes(1)
    fail()
    vi.advanceTimersByTime(1000)
    expect(fn).toHaveBeenCalledTimes(2)
  })

  it('hiç sonuçlanmayan söz yoklamayı sonsuza dek durdurmaz: BUSY_MAX_MS sonra yeni tur', () => {
    const fn = vi.fn(() => new Promise(() => {}))
    renderHook(() => useVisibleInterval(fn, 1000))
    vi.advanceTimersByTime(BUSY_MAX_MS - 1000)
    expect(fn).toHaveBeenCalledTimes(1)
    vi.advanceTimersByTime(1000)
    expect(fn).toHaveBeenCalledTimes(2)
  })

  it('görünürlük dönüşündeki tazeleme de sürmekte olan isteğin üstüne binmez', () => {
    const fn = vi.fn(() => new Promise(() => {}))
    renderHook(() => useVisibleInterval(fn, 1000))
    setHidden(true)
    setHidden(false)
    expect(fn).toHaveBeenCalledTimes(1)
  })
})
