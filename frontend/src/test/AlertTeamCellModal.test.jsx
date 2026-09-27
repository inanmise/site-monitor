import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import AlertTeamCellModal from '../components/admin/alerts/AlertTeamCellModal.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({ admin: { getAlerts: vi.fn() } }),
}))
import { api } from '../api/client'

/**
 * 2026-09-27 regresyon taraması, FRONTEND B/3 — useServerPagination kuralı: "Hata yanıtında setTotal ÇAĞIRMAYIN".
 * Hücre penceresi hata yolunda `setTotal(0)` diyordu → toplam sayfa 1'e iner, kanca sayfayı 1'e çeker, yeni bir
 * istek gider; kullanıcı 3. sayfada geçici bir hata görünce kendini 1. sayfada buluyordu.
 */
const rows = (page) => Array.from({ length: 10 }, (_, i) => ({
  id: page * 100 + i, alert_level: 'HIGH', domain: `d${page}-${i}.example.com`, alert_type: 'SSL',
  created_at: '2026-09-20T10:00:00', resolved: false,
}))
const cell = { team: { team_id: 5, team_name: 'Takım A' }, bucket: 'open', windowDays: 30, count: 30 }

describe('AlertTeamCellModal — hata yanıtı sayfalamayı bozmaz', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    try { localStorage.clear() } catch { /* yoksay */ }
  })

  it('3. sayfa hata dönerse sayfa 3 kalır, 1. sayfaya atılmaz, yeni istek gitmez; hata bandı görünür', async () => {
    // Hata YALNIZ ilk 3. sayfa isteğinde (geçici): eski kodda sayfa 1↔3 arasında gidip gelen istek dizisi böylece
    // sonlu kalır ve test asılmak yerine istek listesiyle kırmızıya düşer.
    let failed = false
    api.admin.getAlerts.mockImplementation(({ page }) => {
      if (page === 2 && !failed) { failed = true; return Promise.resolve({ success: false, error: 'geçici hata' }) }
      return Promise.resolve({ success: true, data: rows(page), total: 30 })
    })
    render(<AlertTeamCellModal cell={cell} onClose={() => {}} />)
    await screen.findByText('d0-0.example.com')
    expect(screen.getByText('1 / 3')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Last page' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('geçici hata')
    await new Promise((r) => setTimeout(r, 50))

    expect(api.admin.getAlerts.mock.calls.map(([p]) => p.page)).toEqual([0, 2])
    expect(screen.getByText('3 / 3')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('geçici hata')
  })
})
