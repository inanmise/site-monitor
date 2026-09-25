import { describe, it, expect, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { StrictMode, createElement } from 'react'
import { usePagination, readPageSize, writePageSize, PAGE_SIZE_OPTIONS } from '../hooks/usePagination.js'
import { render, screen, fireEvent } from './test-utils.jsx'
import PaginationBar from '../components/ui/PaginationBar.jsx'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const items = (n) => Array.from({ length: n }, (_, i) => ({ id: i + 1 }))

const SRC = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
function sourceFiles(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      if (entry.name === 'test' || entry.name === 'node_modules') continue
      sourceFiles(full, out)
    } else if (/[.]jsx?$/.test(entry.name)) out.push(full)
  }
  return out
}

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

  // ── R13 (2026-09-25): görünümün KENDİ boyut listesi ─────────────────────────
  // TodayListModal / ExpiryForecastPage çubuğa [10,25,50] veriyordu ama hook yalnız [25,50,100,200]
  // kabul ediyordu: 25'e geçen kullanıcı "10"a dönemiyordu, tık sessizce yutuluyordu.
  it('R13: sizeOptions verilince o liste kabul edilir (25 → 10 dönüşü çalışır), döndürülür ve kalıcılaşır', () => {
    const opts = { listKey: 'r13a', defaultSize: 10, sizeOptions: [10, 25, 50] }
    const { result } = renderHook(() => usePagination(items(120), opts))
    expect(result.current.sizeOptions).toEqual([10, 25, 50])
    act(() => result.current.setPageSize(25))
    expect(result.current.pageSize).toBe(25)
    act(() => result.current.setPageSize(10))
    expect(result.current.pageSize).toBe(10)
    expect(result.current.pageItems).toHaveLength(10)
    expect(localStorage.getItem('sm.pageSize.r13a')).toBe('10')
    // Yeni mount kayıtlı 10'u OKUR (eskiden readPageSize 10'u geçersiz sayıp varsayılana düşerdi)
    localStorage.setItem('sm.pageSize.r13b', '10')
    const { result: r2 } = renderHook(() => usePagination(items(120), { listKey: 'r13b', defaultSize: 25, sizeOptions: [10, 25, 50] }))
    expect(r2.current.pageSize).toBe(10)
  })

  it('R13: listede OLMAYAN boyut yine reddedilir; sizeOptions verilmezse varsayılan liste döner', () => {
    const { result } = renderHook(() => usePagination(items(120), { listKey: 'r13c', defaultSize: 10, sizeOptions: [10, 25, 50] }))
    act(() => result.current.setPageSize(200))
    expect(result.current.pageSize).toBe(10)
    const { result: def } = renderHook(() => usePagination(items(120), { listKey: 'r13d' }))
    expect(def.current.sizeOptions).toEqual(PAGE_SIZE_OPTIONS)
    act(() => def.current.setPageSize(10))
    expect(def.current.pageSize).toBe(50)
  })

  it('R13 uçtan uca: <PaginationBar {...pager} /> hook\'un listesini çizer ve "10" düğmesi 25\'ten geri döndürür', () => {
    function Harness() {
      const pager = usePagination(items(120), { listKey: 'r13e', defaultSize: 10, sizeOptions: [10, 25, 50] })
      return createElement(PaginationBar, pager)   // = <PaginationBar {...pager} /> (.js dosyası: JSX yok)
    }
    render(createElement(Harness))
    expect(screen.queryByRole('button', { name: '200' })).toBeNull()   // çubuk hook'un listesini gösterir
    fireEvent.click(screen.getByRole('button', { name: '25' }))
    expect(screen.getByRole('button', { name: '25' })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(screen.getByRole('button', { name: '10' }))
    expect(screen.getByRole('button', { name: '10' })).toHaveAttribute('aria-pressed', 'true')
  })

  // Sınıf kapısı: hook'lu bir pager'ı yayan çubuğa AYRICA sizeOptions verilirse iki liste yine
  // ayrışır (R13'ün kökü). Liste hook'a verilir; çubuk onu {...pager} ile alır.
  it('R13 kapısı: <PaginationBar {...pager} sizeOptions=…> YOK — liste usePagination seçeneğine verilir', () => {
    const offenders = []
    for (const f of sourceFiles(SRC)) {
      const src = fs.readFileSync(f, 'utf8')
      for (const m of src.matchAll(/<PaginationBar\s+\{[.]{3}(\w+)\}[^>]*\bsizeOptions=/g)) {
        if (new RegExp('\\b' + m[1] + '\\s*=\\s*usePagination[(]').test(src)) {
          offenders.push(path.relative(SRC, f) + ' → ' + m[1])
        }
      }
    }
    expect(offenders).toEqual([])
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
