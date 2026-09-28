import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, act } from './test-utils.jsx'

/**
 * Ek 3/10 (2026-09-28): Uptime sayfasının yenileme geri sayımı saniyede bir SAYFAYI yeniden çiziyordu (`secondsSince`
 * sayfa state'indeydi); açık detay penceresindeki geçmiş ağacı (CertCheckHistory → CertHistoryInsights → recharts
 * CertDaysTrend, memo'suz) da her saniye çiziliyordu. Sayaç artık başlığın çipinde (MonitorPageHeader `refreshEvery` → RefreshCountdown) yaşar.
 * Davranış aynı: çip her saniye azalır, başarılı yüklemede sıfırlanır. Ölçüm: geçmiş bileşenlerinin çizim sayısı.
 */
const renders = vi.hoisted(() => ({ cert: 0, http: 0 }))
vi.mock('../components/certmodal/CertCheckHistory.jsx', () => ({
  default: () => { renders.cert++; return <div data-testid="cert-history">cert history</div> },
}))
vi.mock('../components/history/CheckHistoryTab.jsx', () => ({
  default: () => { renders.http++; return <div data-testid="http-history">http history</div> },
}))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({ monitoring: { getUptimeOverview: vi.fn() } }),
}))
import { api } from '../api/client'
import UptimePage from '../components/UptimePage.jsx'

const ROW = {
  domain: 'www.example.com', port: 443, status: 'up', http_ok: true, uptime_7d: 100, uptime_30d: 100, ssl_valid_days: 120,
  incidents_1d: 0, incidents_7d: 0, incidents_15d: 0, incidents_30d: 0, team_id: 5, team_name: 'Takım A', can_manage: true,
  uptime_checked_at: new Date(Date.now() - 60_000).toISOString().slice(0, 19),
}
const chipSeconds = () => {
  const m = /Refreshes in (\d+) s/.exec(document.body.textContent)
  return m ? Number(m[1]) : null
}

describe('UptimePage — geri sayım yalnız başlığı çizer', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    renders.cert = 0
    renders.http = 0
    window.history.replaceState(null, '', '/')
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => false })   // sekme görünür (sayaç işler)
    api.monitoring.getUptimeOverview.mockResolvedValue({ success: true, data: [ROW], scope: 'mine' })
  })
  afterEach(() => { delete document.hidden })

  it('detay penceresi açıkken çip saniyede bir azalır ama geçmiş ağacı YENİDEN ÇİZİLMEZ', async () => {
    render(<UptimePage systemRole="ADMIN" />)
    await screen.findByText('www.example.com')
    fireEvent.click(screen.getAllByRole('button', { name: /www\.example\.com/ }).find((b) => b.hasAttribute('data-monitor-open')))
    await screen.findByTestId('cert-history')
    const start = chipSeconds()
    expect(start).not.toBeNull()
    const cert0 = renders.cert
    const http0 = renders.http
    await act(async () => { await new Promise((r) => setTimeout(r, 2300)) })
    expect(chipSeconds()).toBeLessThanOrEqual(start - 2)   // sayaç işliyor (davranış aynı)
    expect(renders.cert).toBe(cert0)                       // SSL geçmişi (recharts eğilimi) her saniye çizilmiyor
    expect(renders.http).toBe(http0)
  }, 10_000)

  it('başarılı yükleme ("Yenile") sayacı yeniden 60 sn\'ye kurar', async () => {
    render(<UptimePage systemRole="ADMIN" />)
    await screen.findByText('www.example.com')
    await act(async () => { await new Promise((r) => setTimeout(r, 1200)) })
    expect(chipSeconds()).toBeLessThan(60)
    fireEvent.click(screen.getByRole('button', { name: /^(Refresh|Yenile)$/ }))
    await act(async () => { await new Promise((r) => setTimeout(r, 50)) })
    expect(chipSeconds()).toBe(60)
  }, 10_000)
})
