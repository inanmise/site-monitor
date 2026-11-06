import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, act } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({ getActivity: vi.fn(), getActivitySummary: vi.fn() }),
}))
import { api } from '../api/client'
import ActivityLog from '../components/ActivityLog.jsx'

/**
 * Aktivite canlı tazelemesi ve "Tümü" aralığı (2026-11-06, performans PF1 madde 7).
 *
 * Özet ucu "Tümü" aralığında bütün aktivite tablosunu tarıyor (yerel gerçekçi veride ≈ 1,5 sn); liste 30 sn'de bir
 * kendiliğinden yenilenince bu sorgu da 30 sn'de bir koşuyordu. "Tümü"nde otomatik yenileme kapalı, zaman çizelgesi
 * "Canlı" yerine görünür bir not gösterir; Yenile düğmesi çalışır. Varsayılan 24 sa davranışı aynı.
 */
const row = { id: 1, monitor_type: 'HTTP', monitor_name: 'Ödeme sağlık', target: 'https://odeme.example.com/health',
  action: 'SCHEDULED_CHECK', result_status: 'SUCCESS', result_summary: '200', activity_time: '2026-11-06T08:00:00' }
const SERIES = { bucket: 'hour', points: [{ t: '2026-11-06T08:00:00Z', success: 1, warning: 0, error: 0, unknown: 0 }] }

beforeEach(() => {
  vi.clearAllMocks()
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => false })
  api.getActivity.mockResolvedValue({ success: true, page: 0, total: 1, data: [row] })
  api.getActivitySummary.mockResolvedValue({ success: true, total: 1, success_count: 1, warning: 0, error: 0, type_counts: {}, series: SERIES })
  vi.useFakeTimers({ shouldAdvanceTime: true })
})
afterEach(() => { vi.useRealTimers(); delete document.hidden })

const settle = async (ms) => { await act(async () => { await vi.advanceTimersByTimeAsync(ms) }) }

describe('Aktivite — canlı tazeleme', () => {
  it('varsayılan 24 sa: 30 sn\'de bir sessizce yenilenir, "Canlı" rozeti görünür', async () => {
    window.history.replaceState(null, '', '/?tab=activity')
    const { container } = render(<ActivityLog />)
    await screen.findByText('Ödeme sağlık')
    await settle(100)
    const lists = api.getActivity.mock.calls.length
    const sums = api.getActivitySummary.mock.calls.length
    await settle(30_500)
    expect(api.getActivity.mock.calls.length).toBe(lists + 1)
    expect(api.getActivitySummary.mock.calls.length).toBe(sums + 1)
    expect(container.querySelector('[data-slot="act-live"]')).not.toBeNull()
    expect(container.querySelector('[data-slot="act-live-off"]')).toBeNull()
  })

  it('"Tümü" aralığı: otomatik yenileme yok, görünür not var, Yenile düğmesi yine okur', async () => {
    window.history.replaceState(null, '', '/?tab=activity&arange=all')
    const { container } = render(<ActivityLog />)
    await screen.findByText('Ödeme sağlık')
    await settle(100)
    // "Tümü" = aralıksız istek (from / to yok)
    expect(api.getActivitySummary.mock.calls.at(-1)[0].from ?? null).toBeNull()
    const lists = api.getActivity.mock.calls.length
    const sums = api.getActivitySummary.mock.calls.length
    await settle(95_000)
    expect(api.getActivity.mock.calls.length).toBe(lists)
    expect(api.getActivitySummary.mock.calls.length).toBe(sums)
    expect(container.querySelector('[data-slot="act-live"]')).toBeNull()
    const note = container.querySelector('[data-slot="act-live-off"]')
    expect(note).not.toBeNull()
    expect(note.textContent).toMatch(/otomatik yenileme kapalı|auto refresh off/i)

    fireEvent.click(screen.getByRole('button', { name: /^(Yenile|Refresh)$/ }))
    await settle(50)
    expect(api.getActivitySummary.mock.calls.length).toBe(sums + 1)
  })
})
