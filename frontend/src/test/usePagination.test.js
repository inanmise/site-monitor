import { describe, it, expect, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { StrictMode } from 'react'
import { usePagination, readPageSize, writePageSize } from '../hooks/usePagination.js'

const items = (n) => Array.from({ length: n }, (_, i) => ({ id: i + 1 }))

describe('usePagination', () => {
  beforeEach(() => localStorage.clear())

  it('120 eleman + varsayılan 50 → 50 eleman, 3 sayfa, 1–50 aralığı', () => {
    const { result } = renderHook(() => usePagination(items(120), { listKey: 'k1' }))
    expect(result.current.pageItems).toHaveLength(50)
    expect(result.current.totalPages).toBe(3)
    expect(result.current.totalItems).toBe(120)
    expect(result.current.rangeStart).toBe(1)
    expect(result.current.rangeEnd).toBe(50)
  })

  it('setPage(3) → son 20 eleman, 101–120 aralığı', () => {
    const { result } = renderHook(() => usePagination(items(120), { listKey: 'k2' }))
    act(() => result.current.setPage(3))
    expect(result.current.page).toBe(3)
    expect(result.current.pageItems).toHaveLength(20)
    expect(result.current.rangeStart).toBe(101)
    expect(result.current.rangeEnd).toBe(120)
    expect(result.current.pageItems[0].id).toBe(101)
  })

  it('setPageSize(100) → sayfa 1e döner, 100 eleman', () => {
    const { result } = renderHook(() => usePagination(items(120), { listKey: 'k3' }))
    act(() => result.current.setPage(3))
    act(() => result.current.setPageSize(100))
    expect(result.current.page).toBe(1)
    expect(result.current.pageItems).toHaveLength(100)
  })

  it('clamp: sayfa 3teyken veri 40a düşer → sayfa otomatik 1 (totalPages=1)', () => {
    const { result, rerender } = renderHook(({ data }) => usePagination(data, { listKey: 'k4' }),
      { initialProps: { data: items(120) } })
    act(() => result.current.setPage(3))
    rerender({ data: items(40) })
    expect(result.current.totalPages).toBe(1)
    expect(result.current.page).toBe(1)
    expect(result.current.pageItems).toHaveLength(40)
  })

  it('reset: resetDeps değişince sayfa 1e döner', () => {
    const { result, rerender } = renderHook(({ q }) => usePagination(items(120), { listKey: 'k5', resetDeps: [q] }),
      { initialProps: { q: '' } })
    act(() => result.current.setPage(2))
    expect(result.current.page).toBe(2)
    rerender({ q: 'example' })
    expect(result.current.page).toBe(1)
  })

  it('boş dizi → totalPages 1, pageItems boş, rangeStart 0', () => {
    const { result } = renderHook(() => usePagination([], { listKey: 'k6' }))
    expect(result.current.totalPages).toBe(1)
    expect(result.current.pageItems).toEqual([])
    expect(result.current.rangeStart).toBe(0)
    expect(result.current.rangeEnd).toBe(0)
  })

  it('persist: boyut localStorage a yazılır, yeni mountta okunur; saçma değer → varsayılan', () => {
    const { result } = renderHook(() => usePagination(items(120), { listKey: 'p1' }))
    act(() => result.current.setPageSize(100))
    expect(localStorage.getItem('sm.pageSize.p1')).toBe('100')

    const { result: r2 } = renderHook(() => usePagination(items(120), { listKey: 'p1' }))
    expect(r2.current.pageSize).toBe(100)

    localStorage.setItem('sm.pageSize.p2', '999')
    expect(readPageSize('p2')).toBe(50)
    localStorage.setItem('sm.pageSize.p3', 'abc')
    expect(readPageSize('p3')).toBe(50)
    writePageSize('p4', 200)
    expect(readPageSize('p4')).toBe(200)
  })

  it('polling kararlılığı: aynı içerikli YENİ dizi referansı → sayfa değişmez', () => {
    const { result, rerender } = renderHook(({ data }) => usePagination(data, { listKey: 'k7', resetDeps: ['sabit'] }),
      { initialProps: { data: items(120) } })
    act(() => result.current.setPage(2))
    rerender({ data: items(120) })   // yeni referans, aynı boyut (polling simülasyonu)
    expect(result.current.page).toBe(2)
    expect(result.current.rangeStart).toBe(51)
  })

  // ── E12: StrictMode cift-mount `initialPage`'i EZMEMELI ────────────────────
  //
  // Reset efekti "ilk kosum mu" bayragiyla (`firstRun` ref) korunuyordu. Ref'ler StrictMode'un
  // mount -> temizlik -> mount dongusunde KORUNUR ve efektin temizligi yoktu: ikinci kurulumda
  // bayrak zaten false oldugu icin setPageRaw(1) calisiyor ve `?page=3` derin baglantisi
  // gelistirme modunda sessizce 1'e dusuyordu (ardindan useUrlQuerySync param'i adresten de
  // siliyordu). Uretim derlemesinde efektler cift calismadigi icin GORUNMEZDI — ve mevcut
  // testler tek-mount render kullandigi icin de gorunmuyordu.

  it('E12: StrictMode altinda initialPage KORUNUR (cift-mount 1e dusurmez)', () => {
    const { result } = renderHook(
      () => usePagination(items(120), { listKey: 'strict1', initialPage: 3 }),
      { wrapper: StrictMode },
    )
    expect(result.current.page).toBe(3)
    expect(result.current.rangeStart).toBe(101)
  })

  it('E12: StrictMode altinda GERCEK filtre degisimi sayfayi YINE 1e dondurur', () => {
    // Duzeltmenin resetleme davranisini oldurmedigini pinler: bagimliligin DEGERI degisince
    // reset calismali, yalnizca cift-mount'ta calismamali.
    let filter = 'a'
    const { result, rerender } = renderHook(
      () => usePagination(items(120), { listKey: 'strict2', initialPage: 3, resetDeps: [filter] }),
      { wrapper: StrictMode },
    )
    expect(result.current.page).toBe(3)

    filter = 'b'
    act(() => rerender())
    expect(result.current.page).toBe(1)
  })
})
