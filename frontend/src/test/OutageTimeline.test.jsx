import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
vi.mock('../api/client', () => ({ formatDateSec: (s) => String(s ?? '') }))
import OutageTimeline from '../components/history/OutageTimeline.jsx'

/** Kesinti zaman çizelgesi (2026-09-12, #12): segment konumu/genişliği aralığa oranlı; açık alarm aralık sonuna dek; tıklama Alarm Geçmişi'ne. */
describe('OutageTimeline', () => {
  const range = { from: '2026-09-12T00:00:00', to: '2026-09-12T10:00:00' }   // 10 saat
  it('çözülmüş 1 saatlik alarm %10 genişlik, %20 sol; açık alarm aralık sonuna kadar + çizgili; özet süre + erişilebilirlik', () => {
    const nav = vi.fn(); window.addEventListener('sm:navigate', nav)
    render(<OutageTimeline range={range} alerts={[
      { id: 1, alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL', created_at: '2026-09-12T02:00:00', resolved: true, resolved_at: '2026-09-12T03:00:00' },
      { id: 2, alert_type: 'PING_DOWN', alert_level: 'WARNING', created_at: '2026-09-12T09:00:00', resolved: false },
    ]} />)
    const segs = document.querySelectorAll('.otl-seg')
    expect(segs.length).toBe(2)
    expect(segs[0].style.left).toBe('20%'); expect(segs[0].style.width).toBe('10%')
    expect(segs[1].style.left).toBe('90%'); expect(segs[1].classList.contains('is-open')).toBe(true)
    expect(screen.getByText(/2 alarm · toplam 2 sa · erişilebilirlik %80\.00|2 alerts · total 2 sa · availability 80\.00%/)).toBeInTheDocument()
    fireEvent.click(segs[0])
    expect(nav.mock.calls[0][0].detail).toEqual({ tab: 'alerthistory', params: { incident: 1 } })
    window.removeEventListener('sm:navigate', nav)
  })
  it('alarm yok → yeşil özet; aralık yok → hiçbir şey', () => {
    const { container, unmount } = render(<OutageTimeline range={range} alerts={[]} />)
    expect(screen.getByText(/Bu aralıkta alarm yok|No alerts in this range/)).toBeInTheDocument()
    expect(container.querySelectorAll('.otl-seg').length).toBe(0)
    unmount()
    const { container: c2 } = render(<OutageTimeline range={null} alerts={[]} />)
    expect(c2.querySelector('.otl')).toBeNull()
  })
})
