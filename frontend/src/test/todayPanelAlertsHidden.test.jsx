import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'

/**
 * L-H8 (2026-10-22, PKG-5 isteği): `/api/me/today` alarm okuma izni olmayana `alerts.visible === false` döner — "Bugün"
 * paneli açık alarm kartını çizmez, toplama ve "Sorun yok" şeridine katmaz. Alan yoksa (eski sunucu) bugünkü davranış.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  api: withApiFallback({ me: { today: vi.fn() }, admin: { getTeams: vi.fn().mockResolvedValue({ success: true, data: [] }) } }),
}))
import { api } from '../api/client'
import TodayPanel from '../components/TodayPanel.jsx'

const MON = { flapping: { count: 0, items: [] }, slow: { count: 0, items: [] }, stale: { count: 0, paused: 0, items: [] },
  domains: { count: 0, expired: 0, items: [] }, notifications: { count: 0, email: 0, webhook: 0, push: 0, items: [] },
  health: { count: 0, critical: 0, items: [] }, weekly: { count: 0, missing: 0, items: [] } }
const CERTS = { count: 1, expired: 0, items: [{ domain: 'soon.example.com', days: 12 }] }
const ALERTS = { count: 2, critical: 1, items: [{ id: 9, domain: 'down.example.com', type: 'HTTP_DOWN', level: 'CRITICAL', acknowledged: false }] }

describe('TodayPanel — alarm görünmezliği (alerts.visible=false)', () => {
  beforeEach(() => { vi.clearAllMocks(); try { localStorage.clear() } catch { /* yok */ } })

  it('visible=false → alarm kartı yok, toplam yalnız diğer kartlar, "Sorun yok" şeridinde alarm adı yok', async () => {
    api.me.today.mockResolvedValue({ success: true, data: { certs: CERTS, alerts: { ...ALERTS, visible: false }, ...MON } })
    render(<TodayPanel onOpenDomain={() => {}} />)
    await screen.findByText(/1 konu ilgi bekliyor|1 items? needs? attention/)
    fireEvent.click(screen.getByRole('button', { name: /Sizin için|For you/ }))
    expect(screen.queryByText('down.example.com')).toBeNull()
    const ok = document.querySelector('[data-slot="today-ok"]')
    expect(ok).not.toBeNull()
    expect(ok.textContent).not.toMatch(/açık alarm|open alerts/i)
  })

  it('alan yoksa bugünkü davranış: alarm kartı ve toplam ona göre', async () => {
    api.me.today.mockResolvedValue({ success: true, data: { certs: CERTS, alerts: ALERTS, ...MON } })
    render(<TodayPanel onOpenDomain={() => {}} />)
    await screen.findByText(/3 konu ilgi bekliyor|3 items need attention/)
    fireEvent.click(screen.getByRole('button', { name: /Sizin için|For you/ }))
    expect(screen.getByText('down.example.com')).toBeInTheDocument()
  })
})
