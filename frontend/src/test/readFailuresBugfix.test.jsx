import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

/**
 * Okuma hatası "yükleniyor" / "yok" değildir (2026-10-22):
 *  - UIM-37: Aktivite satırının mini geçmişi okunamazsa sonsuza dek "Yükleniyor…" diyordu → hata + yeniden dene.
 *  - UIM-45: Sayfa Hızı kaynak kırılımı okunamazsa "kaynak yok" gösteriliyordu → hata + yeniden dene.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    getActivity: vi.fn(), getActivitySummary: vi.fn(), getActivityDetail: vi.fn(),
    monitoring: {
      listGroups: vi.fn(() => Promise.resolve({ success: true, data: [] })),
      monitorDefaults: vi.fn(() => Promise.resolve({ success: true, data: {} })),
      getPageSpeedMonitors: vi.fn(), getPageSpeedResources: vi.fn(),
    },
    admin: { getTeams: vi.fn(() => Promise.resolve({ success: true, data: [] })) },
  }),
}))
import { api } from '../api/client'
import ActivityLog from '../components/ActivityLog.jsx'
import PageSpeedMonitorPage from '../components/PageSpeedMonitorPage.jsx'

beforeEach(() => { vi.clearAllMocks(); window.history.replaceState({}, '', '/') })

describe('UIM-37 — Aktivite mini geçmişi (2026-11-05: ayrıntı sayfasına taşındı)', () => {
  it('liste okunamazsa "Aktivite yok" değil hata bloğu + Yenile; satır içi mini geçmiş artık yok, satır ayrıntı sayfasına gider', async () => {
    api.getActivitySummary.mockResolvedValue({ success: true, total: 1, success_count: 1, warning: 0, error: 0 })
    api.getActivity.mockResolvedValueOnce({ success: false, error: 'Aktivite listesi okunamadı — veritabanı yanıt vermedi.' })
    render(<ActivityLog />)
    await waitFor(() => expect(document.querySelector('[data-slot="empty"][data-tone="danger"]')).not.toBeNull())
    expect(screen.getByText('Aktivite listesi okunamadı — veritabanı yanıt vermedi.')).toBeInTheDocument()
    api.getActivity.mockResolvedValue({ success: true, page: 0, total: 1, data: [{ id: 7, monitor_type: 'HTTP', monitor_name: 'web',
      target: 'https://x.com', action: 'SCHEDULED_CHECK', result_status: 'SUCCESS', result_summary: '200', activity_time: '2026-10-22T08:00:00' }] })
    fireEvent.click(within(document.querySelector('[data-slot="empty"][data-tone="danger"]')).getByRole('button', { name: /Yenile|Refresh/ }))
    await screen.findByText('web')
    fireEvent.keyDown(document.querySelector('[data-slot="act-item"]'), { key: 'Enter' })
    await waitFor(() => expect(new URLSearchParams(window.location.search).get('act')).toBe('7'))
    expect(document.querySelector('[data-slot="act-detail-error"]')).toBeNull()
  })
})

describe('UIM-45 — Sayfa Hızı kaynak kırılımı', () => {
  it('okunamazsa "kaynak yok" değil hata bloğu', async () => {
    api.monitoring.getPageSpeedMonitors.mockResolvedValue({ success: true, data: [{ id: 1, name: 'Ödeme', url: 'https://x.com/odeme',
      team_id: 5, team_name: 'SY-A', group_name: 'G', tags: 'prod', active: true, interval_seconds: 1800, timeout_ms: 10000, max_load_ms: 3000,
      status: 'OK', ok: true, response_ms: 900, ttfb_ms: 120, total_bytes: 1024, request_count: 4, failed_count: 0, breached_metrics: [],
      last_check: '2026-10-22T08:00:00', can_edit: true }] })
    api.monitoring.getPageSpeedResources.mockResolvedValue({ success: false, error: 'boom' })
    render(<PageSpeedMonitorPage systemRole="USER" teamId={5} teamName="SY-A" />)
    fireEvent.click(await screen.findByRole('button', { name: /^https:\/\/x\.com\/odeme — (open details|detayları aç)$/ }))
    await waitFor(() => expect(api.monitoring.getPageSpeedResources).toHaveBeenCalled())
    // Kaynak sekmesi açılınca hata bloğu (varsayılan sekme farklıysa sekmeye geçilir)
    const resTab = screen.queryByRole('tab', { name: /resources|kaynaklar/i })
    if (resTab) pressMenuTrigger(resTab)
    await waitFor(() => expect(document.querySelector('[data-slot="pspd-res-error"]')).not.toBeNull())
  })
})
