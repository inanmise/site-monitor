import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import MiniChart from '../components/admin/MiniChart.jsx'

/** Grafik eşik bandı + ihlal rozeti (2026-09-12, #23). */
describe('MiniChart — eşikler', () => {
  const data = [{ ts: '2026-09-12T10:00:00', value: 40 }, { ts: '2026-09-12T10:01:00', value: 72 }, { ts: '2026-09-12T10:02:00', value: 90 }, { ts: '2026-09-12T10:03:00', value: 30 }]
  it('eşik yok → rozet/çizgi yok; eşik var → 2 kesikli çizgi, rozet "2 ihlal" kritik tonlu', () => {
    const { container, unmount } = render(<MiniChart label="cpu" unit="%" maxY={100} data={data} />)
    expect(container.querySelector('[data-breach]')).toBeNull()
    expect(container.querySelectorAll('[data-threshold]').length).toBe(0)
    unmount()
    const { container: c2 } = render(<MiniChart label="cpu" unit="%" maxY={100} data={data} thresholds={{ warn: 70, crit: 85 }} breachLabel="ihlal" />)
    expect(c2.querySelectorAll('[data-threshold]').length).toBe(2)
    const badge = c2.querySelector('[data-breach]')
    expect(badge.textContent).toBe('2 ihlal')
    expect(badge).toHaveAttribute('data-breach', 'crit')
    expect(badge).toHaveAttribute('data-slot', 'badge')   // shadcn Badge
  })
  it('tüm değerler eşik altı → yeşil ✓', () => {
    const { container } = render(<MiniChart label="cpu" unit="%" maxY={100} data={[{ ts: 'x', value: 10 }, { ts: 'y', value: 20 }]} thresholds={{ warn: 70, crit: 85 }} />)
    const badge = container.querySelector('[data-breach]')
    expect(badge.textContent).toBe('✓')
    expect(badge).toHaveAttribute('data-breach', 'ok')
  })
  it('tıklanır grafik shadcn Button (erişilebilir adlı), tıklanmaz grafik Card; SVG 16 px ikon boyutuna ezilmez', () => {
    const onClick = vi.fn()
    const { container, unmount } = render(<MiniChart label="cpu" unit="%" maxY={100} data={data} onClick={onClick} />)
    const btn = screen.getByRole('button', { name: /cpu/ })
    expect(btn).toHaveAttribute('data-slot', 'mini-chart')
    fireEvent.click(btn)
    expect(onClick).toHaveBeenCalledTimes(1)
    expect(container.querySelector('svg').getAttribute('class')).toMatch(/size-/)
    unmount()
    render(<MiniChart label="cpu" unit="%" maxY={100} data={data} />)
    expect(screen.queryByRole('button')).toBeNull()
    expect(document.querySelector('[data-slot="mini-chart"]')).toHaveAttribute('data-slot', 'mini-chart')
  })
})
