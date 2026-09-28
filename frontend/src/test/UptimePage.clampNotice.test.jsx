import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, within, waitFor } from './test-utils.jsx'

/**
 * E1 (2026-09-28e): Uptime detayının iki geçmiş sütunu KONTROLLÜ aralıkla (pencere her açılışta "bugün 00:00 → şimdi")
 * ister. "Saklama süresi nedeniyle kırpıldı" bandı istenen ucu iç ön ayardan (şimdi − N gün) türetiyordu: SSL sütununda
 * (ön ayar 7) HER açılışta, HTTP sütununda (ön ayar 1) 22:00'ye dek sahte bant — hiçbir şey kırpılmamışken. Sunucu istenen
 * aralığı aynen döndürür (CheckHistoryService.resolve — saklama kırpması yoksa); mock da isteği yansıtır.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', async (importOriginal) => {
  const real = await importOriginal()
  return {
    ...real,
    api: withApiFallback({
      monitoring: {
        getUptimeOverview: vi.fn(),
        getCheckHistory: vi.fn(),
        getCheckHistoryCsvUrl: vi.fn(() => '/csv'),
        getSslResponseSeries: vi.fn(),
      },
    }),
  }
})
import { api } from '../api/client'
import UptimePage from '../components/UptimePage.jsx'

const iso = (msAgo) => new Date(Date.now() - msAgo).toISOString().slice(0, 19)
const ROW = {
  domain: 'www.example.com', port: 443, status: 'up', http_ok: true, uptime_7d: 100, uptime_30d: 100, ssl_valid_days: 120,
  incidents_1d: 0, incidents_7d: 0, incidents_15d: 0, incidents_30d: 0, team_id: 5, team_name: 'Takım A', can_manage: true,
  uptime_checked_at: iso(60_000),
}
const NOTICE = /Due to the retention window|Saklama süresi nedeniyle/

describe('UptimePage — kontrollü aralıkta sahte "kırpıldı" bandı yok (E1)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState(null, '', '/')
    api.monitoring.getUptimeOverview.mockResolvedValue({ success: true, data: [ROW], scope: 'mine' })
    // Sunucu gibi: istenen from/to aynen döner (saklama kırpması yok)
    api.monitoring.getCheckHistory.mockImplementation(async (kind, id, p = {}) => ({ success: true, data: {
      items: [], page: 0, size: 50, total: 0, counts: { total: 0, fail: 0 }, buckets: [], alerts: [],
      range: { from: p.from, to: p.to }, retention_days: 180,
    } }))
    api.monitoring.getSslResponseSeries.mockResolvedValue({ success: true, data: { series: [], bucket: 'hour', unit: 'ms' } })
  })

  it('pencere açılışında (bugün 00:00 → şimdi) ne SSL ne HTTP sütununda saklama bandı çıkar', async () => {
    render(<UptimePage systemRole="ADMIN" />)
    await screen.findByText('www.example.com')
    fireEvent.click(screen.getAllByRole('button', { name: /www\.example\.com/ }).find((b) => b.hasAttribute('data-monitor-open')))
    const cols = await waitFor(() => {
      const el = document.querySelector('[data-slot="uptime-history-cols"]')
      expect(el).not.toBeNull()
      return el
    })
    // İki sütunun istekleri kontrollü uçla gitti ve yanıtlar geldi (boş aralık blokları çizildi)
    await waitFor(() => expect(api.monitoring.getCheckHistory.mock.calls.filter(([, , p]) => p?.from).length).toBeGreaterThanOrEqual(2))
    await within(cols).findByText(/No certificate checks in this range|sertifika kontrolü yok/i)
    expect(within(cols).queryByText(NOTICE)).toBeNull()
  })
})
