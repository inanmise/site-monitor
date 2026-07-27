import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook } from '@testing-library/react'
import { useVisibleInterval } from '../hooks/useVisibleInterval.js'

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

  it('unmount interval\'i temizler (sonrasında çağrı yok)', () => {
    const fn = vi.fn()
    const { unmount } = renderHook(() => useVisibleInterval(fn, 1000))
    fn.mockClear()
    unmount()
    vi.advanceTimersByTime(5000)
    expect(fn).not.toHaveBeenCalled()
  })
})
