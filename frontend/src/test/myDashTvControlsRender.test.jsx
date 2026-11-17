import { describe, it, expect, vi, afterEach } from 'vitest'
import { act, fireEvent, render } from './test-utils.jsx'

/**
 * Performans (2026-11-17): Panom TV kipi. Denetimlerin göster / gizle durumu (fare hareketi, 4 sn hareketsizlik) eskiden
 * MyDashTv'deydi — her geçiş bütün sayfaları ve kutuları yeniden çiziyordu. Artık durum TV çerçevesinde (TvFrame); kutular
 * çizilmez, yalnız çerçevenin öznitelikleri değişir.
 */
const renders = vi.hoisted(() => ({ widget: 0 }))
vi.mock('../components/mydash/widgets.jsx', async () => {
  const React = await import('react')
  const Counting = () => { renders.widget += 1; return React.createElement('div', { 'data-slot': 'test-widget' }) }
  return { WIDGETS: { activity: { Component: Counting }, oncall: { Component: Counting } } }
})
import MyDashTv from '../components/mydash/MyDashTv.jsx'

afterEach(() => { vi.useRealTimers(); renders.widget = 0 })

describe('Panom TV — denetimleri gösterip gizlemek kutuları yeniden çizmez', () => {
  it('4 sn sonra gizlenir, fare hareketiyle görünür; kutu çizimi değişmez', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const widgets = [{ id: 'a', type: 'activity', size: 'md' }, { id: 'b', type: 'oncall', size: 'md' }]
    render(<MyDashTv widgets={widgets} refreshKey={0} onExit={() => {}} />)
    const tv = document.querySelector('[data-slot="mydash-tv"]')
    expect(tv).toHaveAttribute('data-controls', 'shown')
    await act(async () => { await vi.advanceTimersByTimeAsync(100) })
    const before = renders.widget
    expect(before).toBeGreaterThan(0)
    await act(async () => { await vi.advanceTimersByTimeAsync(4_100) })
    expect(tv).toHaveAttribute('data-controls', 'hidden')
    expect(renders.widget).toBe(before)           // eskiden gizlenince bütün kutular yeniden çiziliyordu
    fireEvent.mouseMove(tv)
    expect(tv).toHaveAttribute('data-controls', 'shown')
    fireEvent.keyDown(document, { key: 'Shift' })
    await act(async () => { await vi.advanceTimersByTimeAsync(4_100) })
    expect(tv).toHaveAttribute('data-controls', 'hidden')
    fireEvent.keyDown(document, { key: 'Shift' })   // klavye de denetimleri gösterir (üst bileşenin işleyicisinden)
    expect(tv).toHaveAttribute('data-controls', 'shown')
    expect(renders.widget).toBe(before)
  })
})
