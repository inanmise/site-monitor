import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from './test-utils.jsx'
import HttpMonitorPage from '../components/HttpMonitorPage.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate:    (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      listGroups:      vi.fn(() => Promise.resolve({ success: true, data: [] })),
      monitorDefaults: vi.fn(() => Promise.resolve({ success: true, data: { http: {} } })),
      getHttpMonitors: vi.fn(),
    },
    admin: { getTeams: vi.fn(() => Promise.resolve({ success: true, data: [] })) },
  }),
}))
import { api } from '../api/client'

/**
 * İzleme listesi yüklemesi sıra damgalı (2026-10-09): dokuz sayfanın `load()`'ı korumasızdı — kaydetmeden ÖNCE başlayan
 * 60 sn yoklaması kaydetmeden SONRA dönerse eski liste yenisini eziyordu (Düzenle artık listedeki en yeni satırı
 * kullandığı için bu, açık detaydan düzenlemeyi de etkiliyordu). HTTP temsilci; dokuz sayfa aynı kalıbı kullanır.
 */
const row = (name) => ({ id: 1, name, url: 'https://www.example.com/', method: 'GET', status: 'up', http_status: 200,
  response_ms: 10, interval_seconds: 600, timeout_ms: 7000, active: true, checked_at: '2026-10-09T08:00:00', team_id: 5 })
const deferred = () => { let resolve; const p = new Promise((r) => { resolve = r }); return { p, resolve } }

describe('HttpMonitorPage — liste yüklemesi yarışı', () => {
  beforeEach(() => { vi.clearAllMocks(); window.history.replaceState({}, '', '/') })
  afterEach(() => { window.history.replaceState({}, '', '/') })

  it('ESKİ yükleme yenisinden SONRA dönerse liste yeni veride kalır', async () => {
    const slow = deferred()
    api.monitoring.getHttpMonitors
      .mockResolvedValueOnce({ success: true, data: [row('Ilk-Ad')] })   // açılış
      .mockImplementationOnce(() => slow.p)                             // 1. yenileme: geç dönecek
      .mockResolvedValueOnce({ success: true, data: [row('Yeni-Ad')] }) // 2. yenileme: hemen döner
    render(<HttpMonitorPage />)
    await screen.findByText('Ilk-Ad')
    const refresh = screen.getByRole('button', { name: 'Refresh' })
    fireEvent.click(refresh)
    fireEvent.click(refresh)
    await screen.findByText('Yeni-Ad')
    await act(async () => { slow.resolve({ success: true, data: [row('Eski-Ad')] }) })
    expect(screen.getByText('Yeni-Ad')).toBeInTheDocument()
    expect(screen.queryByText('Eski-Ad')).toBeNull()
    expect(api.monitoring.getHttpMonitors).toHaveBeenCalledTimes(3)
  })
})
