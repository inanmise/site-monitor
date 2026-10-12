import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from './test-utils.jsx'
import { EN } from '../i18n/en.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({ getActivity: vi.fn(), getActivitySummary: vi.fn(), getActivityDetail: vi.fn() }),
}))

import { api } from '../api/client'
import ActivityLog, { istanbulDayStartMs, rangeToFromTo } from '../components/ActivityLog.jsx'

/**
 * 2026-10-12 inceleme bulguları: "bugün" UTC gece yarısından (İstanbul 03:00) başlıyordu ve 00:00–03:00 etkinliği
 * kaçıyordu; aralık süzgeç değişince bir kez hesaplanıp canlı tazelemede donuyordu; özet hatasında sayaçlar "0",
 * grafik sonsuz iskelet gösteriyordu.
 */
describe('Aktivite — zaman penceresi ve özet hatası', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/?tab=activity')
    api.getActivity.mockResolvedValue({ success: true, data: [], total: 0, page: 0, size: 20 })
  })

  it('İstanbul gün başı UTC 21:00 (önceki gün); "bugün" ondan başlar; 24 sa / 7 gün o anki zamandan', () => {
    const now = Date.parse('2026-10-12T01:30:00+03:00')   // İstanbul 01:30 = UTC 11 Ekim 22:30
    expect(new Date(istanbulDayStartMs(now)).toISOString()).toBe('2026-10-11T21:00:00.000Z')
    expect(rangeToFromTo('today', now)).toEqual({ from: '2026-10-11T21:00:00', to: null })
    expect(rangeToFromTo('24h', now).from).toBe('2026-10-10T22:30:00')
    expect(rangeToFromTo('7d', now).from).toBe('2026-10-04T22:30:00')
    expect(rangeToFromTo('all', now)).toEqual({ from: null, to: null })
  })

  it('inceleme 4: Intl biçimi beklenmedikse (format() "YYYY-MM-DD" değil) de gün başı doğru; Intl bozuksa sabit UTC+3', () => {
    const now = Date.parse('2026-10-12T01:30:00+03:00')
    const RealDTF = Intl.DateTimeFormat
    try {
      // format() başka bir biçim döndüren ICU — formatToParts doğru parçaları verir
      Intl.DateTimeFormat = function (...args) {
        const real = new RealDTF(...args)
        return { format: () => '10/12/2026', formatToParts: (d) => real.formatToParts(d), resolvedOptions: () => real.resolvedOptions() }
      }
      expect(new Date(istanbulDayStartMs(now)).toISOString()).toBe('2026-10-11T21:00:00.000Z')
      // Intl tamamen bozuk → sabit hesap
      Intl.DateTimeFormat = function () { throw new RangeError('timeZone desteklenmiyor') }
      expect(new Date(istanbulDayStartMs(now)).toISOString()).toBe('2026-10-11T21:00:00.000Z')
      expect(new Date(istanbulDayStartMs(Date.parse('2026-10-11T23:59:59+03:00'))).toISOString()).toBe('2026-10-10T21:00:00.000Z')
    } finally {
      Intl.DateTimeFormat = RealDTF
    }
  })

  it('özet alınamazsa sayaçlar "0" değil "—", grafik iskelet değil hata satırı', async () => {
    api.getActivitySummary.mockResolvedValue({ success: false, error: 'boom' })
    render(<ActivityLog />)
    await waitFor(() => expect(document.querySelector('[data-slot="act-timeline-error"]')).not.toBeNull())
    const error = document.querySelector('[data-slot="stat-item"][data-key="ERROR"]')
    expect(error.textContent).toContain('—')
    expect(screen.queryByText(/^0$/)).toBeNull()
  })

  it('2026-10-12: özet alınamazsa "Canlı" rozeti gösterilmez', async () => {
    api.getActivitySummary.mockResolvedValue({ success: false, error: 'boom' })
    render(<ActivityLog />)
    await waitFor(() => expect(document.querySelector('[data-slot="act-timeline-error"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="act-live"]')).toBeNull()
  })

  it('2026-10-12: sayfa değişimi özeti SİLMEZ (aynı süzgeç); süzgeç değişimi siler', async () => {
    const rows = Array.from({ length: 20 }, (_, k) => ({ id: k + 1, monitor_type: 'http', monitor_name: 'İzleme ' + (k + 1), target: 'a.example.com', result_status: 'SUCCESS', activity_time: '2026-10-12T08:00:00' }))
    api.getActivity.mockResolvedValue({ success: true, data: rows, total: 500, page: 0, size: 20 })
    api.getActivitySummary.mockResolvedValue({ success: true, total: 45, success_count: 40, warning: 3, error: 2, series: [], type_counts: {} })
    render(<ActivityLog />)
    const errorKpi = () => document.querySelector('[data-slot="stat-item"][data-key="ERROR"]')
    await waitFor(() => expect(errorKpi().textContent).toContain('2'))
    api.getActivitySummary.mockReturnValue(new Promise(() => {}))   // yeni özet hiç gelmese de eskisi kalır
    fireEvent.click((await screen.findAllByRole('button', { name: EN['pg.next'] }))[0])
    await waitFor(() => expect(api.getActivity.mock.calls.at(-1)[0].page).toBe(1))
    expect(errorKpi().textContent).toContain('2')
    expect(errorKpi().textContent).not.toContain('—')
    // süzgeç değişti → önceki süzgecin sayaçları gösterilmez
    fireEvent.click(document.querySelector('[data-slot="stat-item"][data-key="WARNING"]'))
    await waitFor(() => expect(errorKpi().textContent).toContain('—'))
  })
})
