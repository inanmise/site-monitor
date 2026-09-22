import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from './test-utils.jsx'
import DomainExpiryTrend from '../components/DomainExpiryTrend.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({ monitoring: { getDomainTrend: vi.fn() } }),
}))

import { api } from '../api/client'

/** Kalan-gün trendi (2026-09-22, alan adı denetimi madde I): çizgi, yenileme sıçraması, değişiklik işaretçisi, eşikler. */
describe('DomainExpiryTrend', () => {
  beforeEach(() => { api.monitoring.getDomainTrend.mockReset() })

  it('günlük seriyi çizer; kalan günün sıçradığı gün "yenileme", changed=true günü işaretçi olur', async () => {
    api.monitoring.getDomainTrend.mockResolvedValue({ success: true, data: {
      days: 90, warning_days: 30, critical_days: 7,
      points: [
        { day: '2026-09-01', checked_at: '2026-09-01T10:00:00', days_remaining: 20, changed: false },
        { day: '2026-09-02', checked_at: '2026-09-02T10:00:00', days_remaining: 19, changed: true, change_detail: 'registrar: A → B;' },
        { day: '2026-09-03', checked_at: '2026-09-03T10:00:00', days_remaining: 384, changed: false },   // yenilendi
        { day: '2026-09-04', checked_at: '2026-09-04T10:00:00', days_remaining: 383, changed: false },
      ],
    } })
    const { container } = render(<DomainExpiryTrend monitorId={7} days={90} />)
    await waitFor(() => expect(container.querySelector('.dom-trend-line')).not.toBeNull())
    expect(api.monitoring.getDomainTrend).toHaveBeenCalledWith(7, 90)
    expect(container.querySelector('.dom-trend-line').getAttribute('points').split(' ')).toHaveLength(4)
    expect(container.querySelectorAll('.dom-trend-renew')).toHaveLength(1)
    const marks = container.querySelectorAll('.dom-trend-mark')
    expect(marks).toHaveLength(1)
    expect(marks[0].getAttribute('title')).toContain('registrar: A → B;')
    expect(screen.getByText(/1 renewal/)).toBeInTheDocument()
    expect(screen.getByText(/1 registration change/)).toBeInTheDocument()
    // Veri 19..384; alt sınır 0'a iner (min<pad) → uyarı (30) ve kritik (7) eşik çizgileri görünür
    expect(container.querySelectorAll('.dom-trend-th--warn')).toHaveLength(1)
    expect(container.querySelectorAll('.dom-trend-th--crit')).toHaveLength(1)
  })

  it('reloadSignal değişince yeniden okur; veri yoksa boş durum, hata ise hata metni', async () => {
    api.monitoring.getDomainTrend.mockResolvedValue({ success: true, data: { days: 30, points: [] } })
    // Uzak vadeli alan adı (3000+ gün): eşikler görünür aralığın DIŞINDA → çizgi yok, yalnız açıklama
    api.monitoring.getDomainTrend.mockResolvedValueOnce({ success: true, data: { days: 30, warning_days: 30, critical_days: 7,
      points: [{ day: '2026-09-01', checked_at: 'x', days_remaining: 3050 }, { day: '2026-09-02', checked_at: 'y', days_remaining: 3049 }] } })
    const far = render(<DomainExpiryTrend monitorId={8} days={30} />)
    await waitFor(() => expect(far.container.querySelector('.dom-trend-line')).not.toBeNull())
    expect(far.container.querySelectorAll('.dom-trend-th')).toHaveLength(0)
    far.unmount()
    const { rerender } = render(<DomainExpiryTrend monitorId={7} days={30} reloadSignal={0} />)
    await waitFor(() => expect(screen.getByText(/No days-left data/)).toBeInTheDocument())
    api.monitoring.getDomainTrend.mockResolvedValue({ success: false, error: 'boom' })
    rerender(<DomainExpiryTrend monitorId={7} days={30} reloadSignal={1} />)
    await waitFor(() => expect(screen.getByText(/could not be loaded/)).toBeInTheDocument())
    expect(api.monitoring.getDomainTrend).toHaveBeenCalledTimes(3)
  })
})
