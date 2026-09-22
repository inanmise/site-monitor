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
  it('kesinti yok → yeşil özet; aralık yok → hiçbir şey', () => {
    const { container, unmount } = render(<OutageTimeline range={range} alerts={[]} />)
    expect(screen.getByText(/Bu aralıkta kesinti yok|No outages in this range/)).toBeInTheDocument()
    expect(container.querySelectorAll('.otl-seg').length).toBe(0)
    unmount()
    const { container: c2 } = render(<OutageTimeline range={null} alerts={[]} />)
    expect(c2.querySelector('.otl')).toBeNull()
  })

  /* Özet metni "kesinti yok" der — "alarm yok" DEMEZ: hemen yanında uyarı sayısı duruyor (2026-09-22 QA). */
  it('yalnız uyarı varken özet kesintiden söz eder ve uyarı sayısı ayrıca gösterilir', () => {
    render(<OutageTimeline range={range} alerts={[
      { id: 9, alert_type: 'HTTP_SSL', alert_level: 'WARNING', created_at: '2026-09-12T05:00:00', resolved: false },
    ]} />)
    expect(screen.getByText(/Bu aralıkta kesinti yok|No outages in this range/)).toBeInTheDocument()
    expect(screen.queryByText(/alarm yok|No alerts/)).toBeNull()
    expect(screen.getByText(/1 uyarı \(kesinti sayılmaz\)|Advisories: 1/)).toBeInTheDocument()
  })

  it('uyarı türleri (HTTP_SSL, PING_SLOW) segment DEĞİL çentik: erişilebilirliği düşürmez, sayım ayrı (2026-09-22)', () => {
    const { container } = render(<OutageTimeline range={range} alerts={[
      { id: 1, alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL', created_at: '2026-09-12T02:00:00', resolved: true, resolved_at: '2026-09-12T03:00:00' },
      { id: 5, alert_type: 'HTTP_SSL', alert_level: 'WARNING', created_at: '2026-09-12T05:00:00', resolved: false },
      { id: 6, alert_type: 'PING_SLOW', alert_level: 'WARNING', created_at: '2026-09-12T08:00:00', resolved: true, resolved_at: '2026-09-12T09:00:00' },
    ]} />)
    expect(container.querySelectorAll('.otl-seg').length).toBe(1)   // yalnız HTTP_DOWN
    const marks = container.querySelectorAll('.otl-mark')
    expect(marks.length).toBe(2)
    expect(marks[0].style.left).toBe('50%'); expect(marks[0].classList.contains('is-open')).toBe(true)
    expect(marks[0].getAttribute('title')).toMatch(/HTTP_SSL/)
    // Erişilebilirlik yalnız 1 saatlik kesintiden: %90 — SSL uyarısı (açık, aralık sonuna dek) hesaba GİRMEZ
    expect(screen.getByText(/1 alarm · toplam 1 sa · erişilebilirlik %90\.00|1 alerts · total 1 sa · availability 90\.00%/)).toBeInTheDocument()
    expect(screen.getByText(/2 uyarı \(kesinti sayılmaz\)|Advisories: 2/)).toBeInTheDocument()
  })
})
