import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within, act } from './test-utils.jsx'
import IncidentsPage from '../components/IncidentsPage.jsx'

/**
 * 2026-09-27 regresyon taraması (release-fixes.md FRONTEND A #7): `loadSummary` sıra korumasızdı — dakikalık
 * tazeleme / elle "Yenile" / teamId değişimi art arda özet isteği çıkarır; geç dönen ESKİ özet yeni sayıları eziyordu.
 * Denetimli promise'ler: eski özet YENİSİNDEN SONRA çözülür, kartlar yeni sayıyı gösterir.
 */
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => false }))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({ monitoring: { incidents: { list: vi.fn(), comments: vi.fn() } }, admin: { getAlertNotifications: vi.fn() } }),
}))
import { api } from '../api/client'

const inc = (id) => ({
  id, status: 'ongoing', monitor: { name: `m${id}.example.com`, type: 'http', tab: 'http', monitor_id: id },
  root_cause: { code: '500', category: 'server_error' }, comment_count: 0, alert_type: 'HTTP_DOWN', alert_level: 'HIGH',
  started_at: '2026-07-10T10:00:00', resolved_at: null, acknowledged: false, domain: `m${id}.example.com`, message: 'HTTP 500', team_id: 5, team_name: 'Takım A',
})
const deferred = () => { let resolve; const p = new Promise((r) => { resolve = r }); return { p, resolve } }
const flush = () => act(() => new Promise((r) => setTimeout(r, 0)))
const openTile = () => screen.getByRole('button', { name: /Filter: Open|Açık filtrele/ }).closest('[data-slot="stat-item"]')

describe('IncidentsPage — özet fetch yarışı', () => {
  beforeEach(() => {
    vi.clearAllMocks(); localStorage.clear()
    api.admin.getAlertNotifications.mockResolvedValue({ success: true, data: [] })
    api.monitoring.incidents.comments.mockResolvedValue({ success: true, data: [] })
  })

  it('geç dönen ESKİ özet, yeni özetin sayılarını EZMEZ', async () => {
    const first = deferred(), second = deferred()
    let openCalls = 0
    api.monitoring.incidents.list.mockImplementation((p = {}) => {
      if (p.status === 'ongoing' && Number(p.size) === 200) { openCalls += 1; return openCalls === 1 ? first.p : second.p }
      if (p.status === 'resolved' && Number(p.size) === 1) return Promise.resolve({ success: true, data: [], total: 0 })
      return Promise.resolve({ success: true, data: [], total: 0, type_counts: {} })
    })
    render(<IncidentsPage systemRole="ADMIN" teamId={5} />)
    await waitFor(() => expect(openCalls).toBe(1))
    fireEvent.click(screen.getByRole('button', { name: /^(Refresh|Yenile)$/ }))
    await waitFor(() => expect(openCalls).toBe(2))

    // Yeni özet (5 açık) önce, eski özet (1 açık) SONRA döner.
    const five = [1, 2, 3, 4, 5].map(inc)
    await act(async () => { second.resolve({ success: true, data: five, total: 5 }) })
    await waitFor(() => expect(within(openTile()).getByText('5')).toBeInTheDocument())
    await act(async () => { first.resolve({ success: true, data: [inc(9)], total: 1 }) })
    await flush()
    expect(within(openTile()).getByText('5')).toBeInTheDocument()
    expect(within(openTile()).queryByText('1')).toBeNull()
  })
})
