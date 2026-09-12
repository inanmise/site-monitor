import { describe, it, expect } from 'vitest'
import { render } from './test-utils.jsx'
import MiniChart from '../components/admin/MiniChart.jsx'

/** Grafik eşik bandı + ihlal rozeti (2026-09-12, #23). */
describe('MiniChart — eşikler', () => {
  const data = [{ ts: '2026-09-12T10:00:00', value: 40 }, { ts: '2026-09-12T10:01:00', value: 72 }, { ts: '2026-09-12T10:02:00', value: 90 }, { ts: '2026-09-12T10:03:00', value: 30 }]
  it('eşik yok → rozet/çizgi yok; eşik var → 2 kesikli çizgi, rozet "2 ihlal" kritik tonlu', () => {
    const { container, unmount } = render(<MiniChart label="cpu" unit="%" maxY={100} data={data} />)
    expect(container.querySelector('.mini-chart-breach')).toBeNull()
    expect(container.querySelectorAll('.mini-chart-thr').length).toBe(0)
    unmount()
    const { container: c2 } = render(<MiniChart label="cpu" unit="%" maxY={100} data={data} thresholds={{ warn: 70, crit: 85 }} breachLabel="ihlal" />)
    expect(c2.querySelectorAll('.mini-chart-thr').length).toBe(2)
    const badge = c2.querySelector('.mini-chart-breach')
    expect(badge.textContent).toBe('2 ihlal')
    expect(badge.classList.contains('mini-chart-breach--crit')).toBe(true)
  })
  it('tüm değerler eşik altı → yeşil ✓', () => {
    const { container } = render(<MiniChart label="cpu" unit="%" maxY={100} data={[{ ts: 'x', value: 10 }, { ts: 'y', value: 20 }]} thresholds={{ warn: 70, crit: 85 }} />)
    const badge = container.querySelector('.mini-chart-breach')
    expect(badge.textContent).toBe('✓')
    expect(badge.classList.contains('mini-chart-breach--ok')).toBe(true)
  })
})
