import { describe, it, expect, beforeEach, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { StrictMode, useEffect, useState } from 'react'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { useServerPagination, fromEnvelope } from '../hooks/useServerPagination.js'
import PaginationBar from '../components/ui/PaginationBar.jsx'

/**
 * Sunucu sayfalama standardı (2026-09-26). Her iddia yaşanmış bir hatanın karşılığı:
 * mount'ta derin-link sayfası kaybı (IncidentHistoryPage / RetentionRunsPanel / DeploymentHistoryPanel),
 * `page > totalPages` "101–100" / `p_page=99`, 0-tabanlı state'in 1-tabanlı çubuğa kayması, süzgeç
 * değişiminde (eski sayfa + yeni süzgeç) için boşa istek.
 */
describe('fromEnvelope', () => {
  it('üç zarf biçimi + iç içe data', () => {
    expect(fromEnvelope({ success: true, data: [], total: 120, total_pages: 3, page: 0 }).total).toBe(120)
    expect(fromEnvelope({ pagination: { current_page: 1, total: 45, total_pages: 2 } }).total).toBe(45)
    expect(fromEnvelope({ success: true, data: { items: [], total: 7 } }).total).toBe(7)
    expect(fromEnvelope({ success: true, data: { pagination: { total: 9 } } }).total).toBe(9)
    expect(fromEnvelope({ success: false, error: 'x' }).total).toBeNull()
    expect(fromEnvelope(null).total).toBeNull()
  })
})

describe('useServerPagination', () => {
  beforeEach(() => {
    localStorage.clear()
    window.history.replaceState({}, '', '/')
  })

  it('page 1-tabanlı; apiPage apiBase\'e göre (0 → page-1, 1 → page); bar çubuk prop\'larını taşır', () => {
    const { result } = renderHook(() => useServerPagination({ preset: 'panel', apiBase: 0 }))
    expect(result.current.page).toBe(1)
    expect(result.current.apiPage).toBe(0)
    expect(result.current.pageSize).toBe(25)
    act(() => result.current.setTotal(60))
    act(() => result.current.setPage(2))
    expect(result.current.apiPage).toBe(1)
    expect(result.current.bar).toMatchObject({ page: 2, totalPages: 3, totalItems: 60, rangeStart: 26, rangeEnd: 50, pageSize: 25 })
    const { result: one } = renderHook(() => useServerPagination({ apiBase: 1 }))
    expect(one.current.apiPage).toBe(1)
  })

  it('bind(res) zarftan toplamı alır ve yanıtı geri verir; hata yanıtı toplamı SİLMEZ', () => {
    const { result } = renderHook(() => useServerPagination({ preset: 'page' }))
    const res = { success: true, data: [], total: 240 }
    let back
    act(() => { back = result.current.bind(res) })
    expect(back).toBe(res)
    expect(result.current.total).toBe(240)
    expect(result.current.totalPages).toBe(5)
    act(() => { result.current.bind({ success: false, error: 'boom' }) })
    expect(result.current.total).toBe(240)
  })

  it('derin bağlantı: URL sayfası mount\'ta korunur (StrictMode çift-mount dâhil) — resetDeps mount\'ta tetiklenmez', () => {
    window.history.replaceState({}, '', '/?tab=admin&r_page=3')
    const { result } = renderHook(
      () => useServerPagination({ preset: 'panel', resetDeps: ['all', ''], url: { pageKey: 'r_page', sizeKey: 'r_ps' } }),
      { wrapper: StrictMode })
    expect(result.current.page).toBe(3)
    expect(result.current.apiPage).toBe(2)
  })

  it('clamp: URL p_page=99 → toplam gelince son sayfa; 0 kayıt → 1; çubuk "101–100" üretmez', () => {
    window.history.replaceState({}, '', '/?p_page=99')
    const { result } = renderHook(() => useServerPagination({ preset: 'page', url: { pageKey: 'p_page', sizeKey: 'p_ps' } }))
    expect(result.current.page).toBe(99)          // toplam bilinmiyor → dokunulmaz
    act(() => result.current.setTotal(120))
    expect(result.current.page).toBe(3)
    expect(result.current.bar.rangeStart).toBe(101)
    expect(result.current.bar.rangeEnd).toBe(120)
    act(() => result.current.setTotal(0))
    expect(result.current.page).toBe(1)
    expect(result.current.bar.rangeStart).toBe(0)
  })

  it('boyut değişince sayfa 1 + kalıcılık (sm.pageSize.<listKey>); listede olmayan boyut reddedilir', () => {
    const { result } = renderHook(() => useServerPagination({ listKey: 'srv-a', preset: 'page' }))
    act(() => result.current.setTotal(500))
    act(() => result.current.setPage(4))
    act(() => result.current.setPageSize(100))
    expect(result.current.page).toBe(1)
    expect(result.current.pageSize).toBe(100)
    expect(localStorage.getItem('sm.pageSize.srv-a')).toBe('100')
    act(() => result.current.setPageSize(20))
    expect(result.current.pageSize).toBe(100)
    const { result: again } = renderHook(() => useServerPagination({ listKey: 'srv-a', preset: 'page' }))
    expect(again.current.pageSize).toBe(100)
  })

  it('URL yazımı: sayfa > 1 → pageKey; boyut varsayılan değilse YA DA sayfa > 1 ise sizeKey', async () => {
    window.history.replaceState({}, '', '/?tab=admin')
    const { result } = renderHook(() => useServerPagination({ preset: 'panel', url: { pageKey: 'd_page', sizeKey: 'd_ps' } }))
    act(() => result.current.setTotal(300))
    await new Promise(r => setTimeout(r, 350))
    expect(window.location.search).toBe('?tab=admin')
    act(() => result.current.setPage(2))
    await new Promise(r => setTimeout(r, 350))
    let q = new URLSearchParams(window.location.search)
    expect(q.get('d_page')).toBe('2')
    expect(q.get('d_ps')).toBe('25')
    act(() => result.current.setPageSize(50))
    await new Promise(r => setTimeout(r, 350))
    q = new URLSearchParams(window.location.search)
    expect(q.get('d_page')).toBeNull()
    expect(q.get('d_ps')).toBe('50')
  })

  it('modal ön ayarı compact çubuk ister', () => {
    const { result } = renderHook(() => useServerPagination({ preset: 'modal' }))
    expect(result.current.pageSize).toBe(10)
    expect(result.current.bar.compact).toBe(true)
    const { result: page } = renderHook(() => useServerPagination({ preset: 'page' }))
    expect('compact' in page.current.bar).toBe(false)
  })
})

describe('useServerPagination + PaginationBar uçtan uca (liste bileşeni gibi)', () => {
  beforeEach(() => { localStorage.clear(); window.history.replaceState({}, '', '/') })

  function List({ fetcher }) {
    const [status, setStatus] = useState('all')
    const [rows, setRows] = useState([])
    const sp = useServerPagination({ listKey: 'srv-list', preset: 'panel', resetDeps: [status], apiBase: 0 })
    useEffect(() => {
      let alive = true
      fetcher({ status, page: sp.apiPage, size: sp.pageSize }).then((res) => { if (alive) { setRows(res.data); sp.bind(res) } })
      return () => { alive = false }
    }, [status, sp.apiPage, sp.pageSize])   // eslint-disable-line react-hooks/exhaustive-deps
    return (
      <div>
        <button type="button" onClick={() => setStatus('open')}>only-open</button>
        <ul>{rows.map(r => <li key={r}>{r}</li>)}</ul>
        <PaginationBar {...sp.bar} />
      </div>
    )
  }

  it('sayfa 3\'te süzgeç değişince TEK istek gider ve o istek page=0 taşır (eski sayfa + yeni süzgeç yok)', async () => {
    const fetcher = vi.fn(async ({ page, size }) => ({ success: true, data: [`row-${page}-${size}`], total: 100 }))
    render(<List fetcher={fetcher} />)
    await screen.findByText('row-0-25')
    fireEvent.click(screen.getByRole('button', { name: 'Page 3' }))
    await screen.findByText('row-2-25')
    expect(screen.getByRole('button', { name: 'Page 3' })).toHaveAttribute('aria-current', 'page')
    fetcher.mockClear()
    fireEvent.click(screen.getByRole('button', { name: 'only-open' }))
    await screen.findByText('row-0-25')
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(fetcher).toHaveBeenCalledWith({ status: 'open', page: 0, size: 25 })
    expect(screen.getByRole('button', { name: 'Page 1' })).toHaveAttribute('aria-current', 'page')
  })

  it('çubuktan boyut seçimi: istek size=50, page=0', async () => {
    const fetcher = vi.fn(async ({ page, size }) => ({ success: true, data: [`row-${page}-${size}`], total: 100 }))
    render(<List fetcher={fetcher} />)
    await screen.findByText('row-0-25')
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByText('row-1-25')
    fireEvent.click(screen.getByRole('combobox', { name: 'Per page' }))
    fireEvent.click(screen.getByRole('option', { name: '50' }))
    await waitFor(() => expect(fetcher).toHaveBeenLastCalledWith({ status: 'all', page: 0, size: 50 }))
    expect(screen.getByText('1–50 of 100 records')).toBeInTheDocument()
  })
})
