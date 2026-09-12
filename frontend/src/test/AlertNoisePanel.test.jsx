import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({ api: withApiFallback({ admin: { getAlertNoise: vi.fn() } }) }))
import { api } from '../api/client'
import AlertNoisePanel from '../components/admin/AlertNoisePanel.jsx'

/** Gürültü analizi (2026-09-12, #18): kapalı başlar, açınca yüklenir; top tablo, ısı haritası, flap önerisi; gün seçimi yeniden yükler. */
describe('AlertNoisePanel', () => {
  beforeEach(() => { vi.clearAllMocks(); try { localStorage.clear() } catch { /* yok */ } })
  const rows = Array.from({ length: 7 }, () => Array(24).fill(0)); rows[1][14] = 5
  const DATA = { days: 7, total: 12, critical: 3, distinct_targets: 2,
    top: [{ domain: 'flap.example.com', type: 'HTTP_DOWN', count: 9, resolved: 9, avg_minutes: 3.5, share_pct: 75 }, { domain: 'slow.example.com', type: 'PING_DOWN', count: 3, resolved: 2, avg_minutes: 42, share_pct: 25 }],
    flapping: [{ domain: 'flap.example.com', type: 'HTTP_DOWN', count: 9, avg_minutes: 3.5, suggestion: 'raise-confirm' }],
    heat: { rows, peak: 5, peak_day: 1, peak_hour: 14, by_hour: [], by_day: [] } }

  it('kapalı başlar (istek yok); açınca 7 gün yüklenir; hedef tıklanınca onPickDomain; 30 gün seçince yeniden ister', async () => {
    api.admin.getAlertNoise.mockResolvedValue({ success: true, data: DATA })
    const onPick = vi.fn()
    render(<AlertNoisePanel onPickDomain={onPick} />)
    expect(api.admin.getAlertNoise).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: /Gürültü analizi|Noise analysis/ }))
    await waitFor(() => expect(api.admin.getAlertNoise).toHaveBeenCalledWith(7))
    await screen.findByText(/12 alarm · 2 hedef · 1 flap adayı|12 alerts · 2 targets · 1 flapping/)
    expect(screen.getByText(/zirve: Sal 14:00 · 5 alarm|peak: Tue 14:00 · 5 alerts/)).toBeInTheDocument()
    expect(screen.getByText(/9 alarm, ortalama 3\.5 dk|9 alerts, open for 3\.5 min/)).toBeInTheDocument()
    expect(document.querySelectorAll('.noise-heat-cell').length).toBe(7 * 24)
    fireEvent.click(screen.getAllByRole('button', { name: 'flap.example.com' })[0])
    expect(onPick).toHaveBeenCalledWith('flap.example.com')
    fireEvent.click(screen.getByRole('button', { name: /Son 30 gün|Last 30 days/ }))
    await waitFor(() => expect(api.admin.getAlertNoise).toHaveBeenCalledWith(30))
  })

  it('alarm yok → boş mesaj', async () => {
    api.admin.getAlertNoise.mockResolvedValue({ success: true, data: { days: 7, total: 0, distinct_targets: 0, top: [], flapping: [], heat: { rows, peak: 0 } } })
    render(<AlertNoisePanel />)
    fireEvent.click(screen.getByRole('button', { name: /Gürültü analizi|Noise analysis/ }))
    expect(await screen.findByText(/Son 7 günde alarm yok|No alerts in the last 7 days/)).toBeInTheDocument()
  })
})
