import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook } from '@testing-library/react'
import { useUrlQuerySync, readUrlParam, readUrlInt, PAGE_STATE_PARAMS } from '../hooks/useUrlQuerySync.js'

function url() { return window.location.pathname + window.location.search }

describe('useUrlQuerySync', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    window.history.replaceState({}, '', '/?tab=keyword')
  })
  afterEach(() => {
    vi.useRealTimers()
    window.history.replaceState({}, '', '/')
  })

  it('dolu değerler yazılır, null paramı siler, eşleme-dışı paramlar (tab) korunur', () => {
    const { rerender } = renderHook(({ m }) => useUrlQuerySync(m, { debounceMs: 100 }),
      { initialProps: { m: { group: 'callcenterfacewebmon', q: null } } })
    vi.advanceTimersByTime(150)
    expect(url()).toBe('/?tab=keyword&group=callcenterfacewebmon')

    rerender({ m: { group: null, q: 'example' } })
    vi.advanceTimersByTime(150)
    expect(url()).toBe('/?tab=keyword&q=example')
  })

  it('tüm değerler varsayılan (null) → URL temiz kalır, replaceState gereksiz çağrılmaz', () => {
    const spy = vi.spyOn(window.history, 'replaceState')
    renderHook(() => useUrlQuerySync({ group: null, q: null, page: null }, { debounceMs: 50 }))
    vi.advanceTimersByTime(100)
    expect(url()).toBe('/?tab=keyword')
    expect(spy).not.toHaveBeenCalled()   // değişiklik yok → hiç yazma
    spy.mockRestore()
  })

  it('debounce: 3 hızlı değişimde tek yazma, SON değer kazanır', () => {
    const spy = vi.spyOn(window.history, 'replaceState')
    const { rerender } = renderHook(({ m }) => useUrlQuerySync(m, { debounceMs: 300 }),
      { initialProps: { m: { q: 'a' } } })
    rerender({ m: { q: 'ak' } })
    rerender({ m: { q: 'akb' } })
    vi.advanceTimersByTime(400)
    expect(spy).toHaveBeenCalledTimes(1)
    expect(url()).toBe('/?tab=keyword&q=akb')
    spy.mockRestore()
  })

  it('replaceState fırlatırsa (Safari rate-limit) hook patlamaz', () => {
    const spy = vi.spyOn(window.history, 'replaceState').mockImplementation(() => { throw new Error('rate limited') })
    renderHook(() => useUrlQuerySync({ q: 'x' }, { debounceMs: 10 }))
    expect(() => vi.advanceTimersByTime(50)).not.toThrow()
    spy.mockRestore()
  })

  it('enabled=false iken hiç yazmaz (gömülü bileşen guardı)', () => {
    const spy = vi.spyOn(window.history, 'replaceState')
    renderHook(() => useUrlQuerySync({ q: 'x' }, { debounceMs: 10, enabled: false }))
    vi.advanceTimersByTime(50)
    expect(spy).not.toHaveBeenCalled()
    spy.mockRestore()
  })
})

describe('readUrlParam / readUrlInt', () => {
  afterEach(() => window.history.replaceState({}, '', '/'))

  it('mevcut, eksik ve boş param', () => {
    window.history.replaceState({}, '', '/?group=X&empty=')
    expect(readUrlParam('group', 'all')).toBe('X')
    expect(readUrlParam('yok', 'all')).toBe('all')
    expect(readUrlParam('empty', 'all')).toBe('all')
  })

  it('int: geçerli, bozuk (abc), negatif, sıfır → fallback', () => {
    window.history.replaceState({}, '', '/?page=3&bad=abc&neg=-5&zero=0')
    expect(readUrlInt('page', 1)).toBe(3)
    expect(readUrlInt('bad', 1)).toBe(1)
    expect(readUrlInt('neg', 1)).toBe(1)
    expect(readUrlInt('zero', 1)).toBe(1)
    expect(readUrlInt('yok', 7)).toBe(7)
  })

  it('PAGE_STATE_PARAMS sözlüğü beklenen paramları içerir', () => {
    for (const p of ['group', 'tag', 'team', 'q', 'stat', 'sort', 'page', 'ps', 'monitor', 'range', 'mtab', 'domain', 'incident'])
      expect(PAGE_STATE_PARAMS).toContain(p)
  })
})
