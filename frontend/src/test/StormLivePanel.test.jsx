import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import StormLivePanel from '../components/admin/storm/StormLivePanel.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({ monitoring: { storm: { status: vi.fn() } } }),
}))
import { api } from '../api/client'

const iso = (min) => new Date(Date.now() + min * 60_000).toISOString().slice(0, 19)
const STATUS = { success: true, data: {
  settings: { enabled: true, threshold_unit: 'COUNT', threshold_value: 5, window_minutes: 5, quiet_minutes: 5 },
  totals: { teams: 2, storming: 1, near: 0, open_storms: 1 },
  teams: [
    { team_id: 1, team_name: 'Takım A', status: 'STORM', threshold: 5, window_minutes: 5, window_targets: 6, window_alerts: 7, storms: [
      { id: 7, team_id: 1, resolved: false, created_at: iso(-20), member_count: 7, active_down: 6, seal_at: iso(3), sealed: false, root_cause: 'HTTP_DOWN' }] },
    { team_id: 2, team_name: 'Takım B', status: 'CALM', threshold: 5, window_minutes: 5, window_targets: 0, window_alerts: 0, storms: [] },
  ], legacy_open: [] } }

describe('Ayarlar → Alarm Fırtınası → Canlı durum paneli (2026-09-30)', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('takım kartlarını kompakt çizer (ayrıntı düğmesi yok), toplam rozetleri; "Fırtına sayfasını aç" storms sekmesine gider', async () => {
    api.monitoring.storm.status.mockResolvedValue(STATUS)
    const events = []
    window.addEventListener('sm:navigate', (e) => events.push(e.detail))
    const { container } = render(<StormLivePanel />)
    await waitFor(() => expect(container.querySelectorAll('[data-slot="sf-team"]').length).toBe(2))
    expect(container.querySelector('[data-slot="sf-live-storming"]').textContent).toMatch(/1/)
    expect(container.querySelector('[data-slot="sf-storm"][data-storm-id="7"]')).not.toBeNull()
    expect(screen.queryByRole('button', { name: /^ayrıntı$|^details$/i })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /fırtına sayfasını aç|open storm page/i }))
    expect(events.at(-1)).toEqual({ tab: 'storms', params: undefined })
  })

  it('geri dönüş dizi verisi ({data: []}) → boş durum metni, uyarı bandı yok', async () => {
    api.monitoring.storm.status.mockResolvedValueOnce({ success: true, data: [] })
    const { container } = render(<StormLivePanel />)
    await waitFor(() => expect(container.querySelector('[data-slot="sf-live-empty"]')).not.toBeNull())
    expect(container.querySelector('[data-slot="alert"][data-tone="warning"]')).toBeNull()
  })

  // 2026-10-22 (L-S3 / UIA-C6): eski test "hata → boş durum" pinliyordu — okuma hatası "0 fırtına / takım yok" DEĞİLDİR.
  it('ilk okuma hatası → "okunamadı" + yeniden dene, sayaçlar "—" (0 değil), boş durum metni YOK', async () => {
    api.monitoring.storm.status.mockRejectedValueOnce(new Error('x'))
    const { container } = render(<StormLivePanel />)
    await waitFor(() => expect(container.querySelector('[data-slot="sf-live-error"]')).not.toBeNull())
    expect(container.querySelector('[data-slot="sf-live-empty"]')).toBeNull()
    expect(container.querySelector('[data-slot="sf-live-storming"]').textContent).toContain('—')
    expect(container.querySelector('[data-slot="sf-live-storming"]').textContent).not.toMatch(/\b0\b/)
    api.monitoring.storm.status.mockResolvedValueOnce({ success: true, data: { teams: [], totals: { storming: 0, near: 0, open_storms: 0 } } })
    fireEvent.click(within(container.querySelector('[data-slot="sf-live-error"]')).getByRole('button'))
    await waitFor(() => expect(container.querySelector('[data-slot="sf-live-empty"]')).not.toBeNull())
    expect(container.querySelector('[data-slot="sf-live-error"]')).toBeNull()
  })

  it('iyi veriden sonra başarısız yoklama eldeki veriyi SİLMEZ — uyarı şeridi çıkar', async () => {
    api.monitoring.storm.status.mockResolvedValueOnce({ success: true, data: { teams: [], totals: { storming: 2, near: 1, open_storms: 2 } } })
    const { container } = render(<StormLivePanel />)
    await waitFor(() => expect(container.querySelector('[data-slot="sf-live-storming"]').textContent).toContain('2'))
    api.monitoring.storm.status.mockResolvedValueOnce({ success: false, error: 'boom' })
    fireEvent.click(screen.getByRole('button', { name: /yenile|refresh/i }))
    await waitFor(() => expect(container.querySelector('[data-slot="sf-live-stale"]')).not.toBeNull())
    expect(container.querySelector('[data-slot="sf-live-storming"]').textContent).toContain('2')
    expect(container.querySelector('[data-slot="sf-live-error"]')).toBeNull()
  })
})
