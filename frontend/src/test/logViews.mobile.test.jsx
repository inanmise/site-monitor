import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { useT } from '../i18n/index.jsx'

/**
 * Dar kap (telefon, < 720 / 760 px) dalı — jsdom yerleşim ölçmez, bu yüzden kap genişliği kancası 400 px'e sabitlenir.
 * Haftalık e-posta logu ve Webhook push logu (2026-10-01 yeniden tasarım) tablo yerine KART listesi çizer; kart
 * ayrıntıyı açar, push kartında yeniden kuyruğa alma ayrı düğme, sıralama başlık yerine seçiciden.
 */
vi.mock('../hooks/useElementWidth.js', () => ({ useElementWidth: () => [() => {}, 400] }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ canView: () => true, canEdit: () => true, canExecute: () => true, perms: {} }),
}))
vi.mock('recharts', () => ({
  ResponsiveContainer: ({ children }) => <div>{children}</div>,
  BarChart: ({ children }) => <div>{children}</div>,
  Bar: () => null, XAxis: () => null, YAxis: () => null, CartesianGrid: () => null, Tooltip: () => null, Legend: () => null,
}))
const { pushLog, weekly } = vi.hoisted(() => ({
  pushLog: { search: vi.fn(), summary: vi.fn(), export: vi.fn(), detail: vi.fn(), requeue: vi.fn() },
  weekly: { getWeeklyAvailHistory: vi.fn(), getWeeklyAvailHistoryItem: vi.fn() },
}))
vi.mock('../api/client', () => ({
  formatDate: (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : ''),
  api: { admin: { pushLog, ...weekly } },
}))
import PushLogView from '../components/admin/PushLogView.jsx'
import WeeklyAvailLogsModal from '../components/admin/health/WeeklyAvailLogsModal.jsx'

const ROWS = [
  { id: 2, alert_event_id: 10, trigger: 'INITIAL', monitor_type: 'HTTP', monitor_name: 'api', team_id: 1, team_name: 'Takım A', alert_level: 'CRITICAL', username: 'u2', display_name: 'İki', status: 'FAILED', http_status: 500, error: 'Internal error', attempts: 3, at: '2026-09-19T11:54:00', kind: 'FAILED' },
  { id: 6, alert_event_id: 12, trigger: 'ESCALATION', monitor_type: 'PING', monitor_name: 'gw', team_id: 2, team_name: 'Takım B', alert_level: 'HIGH', username: 'u1', display_name: 'Bir', status: 'RATE_LIMITED', at: '2026-09-19T11:59:00', kind: 'BLOCKED' },
  { id: 1, alert_event_id: 10, trigger: 'INITIAL', monitor_type: 'HTTP', monitor_name: 'api', team_id: 1, team_name: 'Takım A', alert_level: 'CRITICAL', username: 'u1', display_name: 'Bir', status: 'SENT', http_status: 200, at: '2026-09-19T11:55:00', kind: 'SENT' },
]

describe('Webhook push logu — dar kap: kart listesi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/?tab=health&view=push')
    pushLog.summary.mockResolvedValue({ success: true, data: { granularity: 'day', kpi: { total: 3, sent: 1, failed: 1, blocked: 1, success_rate: 99.5 }, timeline: [], teams: [], levels: [], top_users: [], top_monitors: [] } })
    pushLog.search.mockResolvedValue({ success: true, data: ROWS, total: 3 })
    pushLog.detail.mockResolvedValue({ success: true, data: { ...ROWS[0], created_at: '2026-09-19T11:54:00', message: 'KRİTİK api down' } })
  })

  it('kartlar (tablo yok); kart ayrıntıyı açar; yeniden kuyruk yalnız başarısız/engellenmiş kartta; sıralama seçicisi sunucuya gider', async () => {
    render(<PushLogView onBack={() => {}} />)
    const cards = await waitFor(() => { const el = document.querySelector('[data-testid="pl-cards"]'); expect(el).not.toBeNull(); return el })
    expect(screen.queryByTestId('sml-table')).toBeNull()
    expect(cards.querySelectorAll('[data-slot="pl-row"]')).toHaveLength(3)
    expect(cards.querySelectorAll('[data-action="resend"]')).toHaveLength(2)
    expect(document.querySelector('[data-slot="pl-health"]')).toHaveAttribute('data-tone', 'ok')
    fireEvent.change(document.querySelector('[data-slot="pl-sort"]'), { target: { value: 'monitor,asc' } })
    await waitFor(() => expect(pushLog.search).toHaveBeenLastCalledWith(expect.objectContaining({ sort: 'monitor,asc' })))
    fireEvent.click(within(cards).getAllByRole('button', { name: /api · 2026-09-19 11:54/ })[0])
    const dlg = await screen.findByRole('dialog')
    await within(dlg).findByText('KRİTİK api down')
  })
})

function WeeklyHarness() {
  const t = useT()
  return <WeeklyAvailLogsModal t={t} onClose={() => {}} />
}

describe('Haftalık e-posta logu — dar kap: kart listesi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    weekly.getWeeklyAvailHistory.mockResolvedValue({ success: true, data: [
      { id: 2, sent_at: '2026-09-28T07:30:00', team: 'Kart', to: 'kart@example.com', subject: 'Rapor', status: 'FAILED: relay', trigger: 'WEEKLY_AVAILABILITY' },
      { id: 1, sent_at: '2026-09-28T07:30:02', team: 'Ödeme', to: 'odeme@example.com', subject: 'Rapor', status: 'SENT', trigger: 'WEEKLY_AVAILABILITY' },
    ] })
    weekly.getWeeklyAvailHistoryItem.mockResolvedValue({ success: true, data: { id: 2, team: 'Kart', to: 'kart@example.com', subject: 'Rapor', sent_at: '2026-09-28T07:30:00', status: 'FAILED: relay', trigger: 'WEEKLY_AVAILABILITY', html: '<p>x</p>' } })
  })

  it('gün başlığı + kart düğmeleri (tablo yok); kart ayrıntıyı ikinci pencerede açar', async () => {
    render(<WeeklyHarness />)
    const list = await waitFor(() => { const el = document.querySelector('[data-testid="wa-logs"]'); expect(el).not.toBeNull(); return el })
    expect(list.tagName).toBe('DIV')   // tablo değil
    expect(list.querySelectorAll('[data-slot="wa-day"]')).toHaveLength(1)
    const rows = list.querySelectorAll('button[data-slot="wa-row"]')
    expect(rows).toHaveLength(2)
    expect(rows[0].textContent).toMatch(/relay/)
    fireEvent.click(rows[0])
    await waitFor(() => expect(document.querySelectorAll('[role="dialog"]').length).toBe(2))
    expect(weekly.getWeeklyAvailHistoryItem).toHaveBeenCalledWith(2)
  })
})
