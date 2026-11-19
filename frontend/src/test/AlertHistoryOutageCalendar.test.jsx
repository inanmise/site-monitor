import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import AlertHistory from '../components/admin/AlertHistory.jsx'
import { currentMonth, shiftMonth, monthGrid } from '../components/admin/alerts/outageCalendarModel.js'
import { dayStart, dayEnd } from '../components/admin/alerts/alertHistoryModel.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => false }))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      getAlerts: vi.fn(), getTeams: vi.fn(), getAlertNotifications: vi.fn(), getAlertPushDeliveries: vi.fn(),
      getAlertsCsvUrl: vi.fn(() => '/api/admin/alerts/export'), getOutageCalendar: vi.fn(),
    },
  }),
}))

import { api } from '../api/client'

/**
 * Alarm Geçmişi "Liste | Takvim" anahtarı (2026-11-19, takım kesinti takvimi): takvim URL'de `cal=1` (+ `calm`), takım
 * listeyle ortak `team`; güne basınca liste o takım + o gün (Tümü, aralıkta aktif) ile açılır; gömülü kullanımda anahtar yok.
 */
const PREV = shiftMonth(currentMonth(), -1)   // tüm günleri geçmiş bir ay (saatten bağımsız)
const listCalls = () => api.admin.getAlerts.mock.calls.map(([p]) => p).filter((p) => p?.size !== 1)
const lastList = () => listCalls().at(-1)

function calFor(month, teamId = 5) {
  const days = monthGrid(month).flat().filter(Boolean).map((day, i) => (
    i === 2 ? { day, outage_minutes: 90, alarms: 2, worst_level: 'HIGH' } : { day, outage_minutes: 0, alarms: 0, worst_level: null }))
  return { success: true, data: { month, team_id: teamId, team_name: teamId === 5 ? 'Ops' : 'Net', days, total_minutes: 90, truncated: false } }
}

beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState({}, '', '/')
  api.admin.getAlertNotifications.mockResolvedValue({ success: true, data: [] })
  api.admin.getAlertPushDeliveries.mockResolvedValue({ success: true, data: [] })
  api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'Ops' }, { id: 6, name: 'Net' }] })
  api.admin.getAlerts.mockResolvedValue({ success: true, data: [], total: 0, page: 0, size: 20 })
  api.admin.getOutageCalendar.mockImplementation(({ team, month }) => Promise.resolve(calFor(month, Number(team))))
})
afterEach(() => { window.history.replaceState({}, '', '/') })

describe('Alarm Geçmişi — Liste | Takvim', () => {
  it('varsayılan liste; Takvim seçilince takvim (birincil takım) çizilir, URL cal=1, liste sekmeleri ve CSV gizlenir', async () => {
    render(<AlertHistory urlSync primaryTeamId={6} />)
    const sw = await screen.findByRole('group', { name: 'Alert History view' })
    expect(screen.getByRole('button', { name: 'List' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('tablist', { name: 'Alert view' })).toBeInTheDocument()
    expect(sw).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Calendar' }))
    await waitFor(() => expect(document.querySelector('[data-slot="outage-calendar"]')).toBeTruthy())
    await waitFor(() => expect(api.admin.getOutageCalendar).toHaveBeenCalledWith({ team: '6', month: currentMonth() }))
    expect(screen.queryByRole('tablist', { name: 'Alert view' })).toBeNull()
    expect(screen.queryByRole('link', { name: /CSV/ })).toBeNull()
    await waitFor(() => expect(window.location.search).toContain('cal=1'), { timeout: 2000 })
    expect(window.location.search).not.toContain('calm=')   // bu ay yazılmaz
  })

  it('URL cal=1&calm=<geçen ay>&team=5 doğrudan takvimi açar; güne basma listeyi o takım + o günle açar', async () => {
    window.history.replaceState({}, '', `/?cal=1&calm=${PREV}&team=5`)
    render(<AlertHistory urlSync primaryTeamId={6} />)
    await waitFor(() => expect(api.admin.getOutageCalendar).toHaveBeenCalledWith({ team: '5', month: PREV }))
    const day = monthGrid(PREV).flat().filter(Boolean)[2]
    const cell = await waitFor(() => {
      const c = document.querySelector(`[data-slot="outage-cal-day"][data-day="${day}"]`)
      expect(c).toBeTruthy()
      return c
    })
    expect(cell).toHaveAttribute('data-bucket', '3')

    fireEvent.click(cell)
    await waitFor(() => expect(document.querySelector('[data-slot="outage-calendar"]')).toBeNull())
    await waitFor(() => {
      const p = lastList()
      expect(p.teamId).toBe('5')
      expect(p.since).toBe(dayStart(day))
      expect(p.until).toBe(dayEnd(day))
      expect(p.range).toBe('active')
      expect(p.resolved).toBeUndefined()   // Tümü — kapananlar da
    })
    expect(screen.getByRole('tab', { name: 'All' })).toHaveAttribute('aria-selected', 'true')
    await waitFor(() => {
      const q = new URLSearchParams(window.location.search)
      expect(q.get('cal')).toBeNull()
      expect(q.get('team')).toBe('5')
      expect(q.get('from')).toBe(day)
      expect(q.get('to')).toBe(day)
      expect(q.get('range')).toBe('active')
      expect(q.get('view')).toBe('all')
    }, { timeout: 2000 })
  })

  it('aralık dışı / bozuk calm → bu ay', async () => {
    window.history.replaceState({}, '', '/?cal=1&calm=1999-01&team=5')
    render(<AlertHistory urlSync />)
    await waitFor(() => expect(api.admin.getOutageCalendar).toHaveBeenCalledWith({ team: '5', month: currentMonth() }))
  })

  it('gömülü kullanımda anahtar ve takvim yok', async () => {
    render(<AlertHistory domain="a.example.com" />)
    await waitFor(() => expect(api.admin.getAlerts).toHaveBeenCalled())
    expect(screen.queryByRole('group', { name: 'Alert History view' })).toBeNull()
    expect(api.admin.getOutageCalendar).not.toHaveBeenCalled()
  })
})
