import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ canView: () => true, canEdit: () => true, canExecute: () => true, perms: {} }),
}))
vi.mock('recharts', () => ({
  ResponsiveContainer: ({ children }) => <div>{children}</div>,
  BarChart: ({ children }) => <div>{children}</div>,
  Bar: () => null, XAxis: () => null, YAxis: () => null, CartesianGrid: () => null, Tooltip: () => null, Legend: () => null,
}))
const { pushLog } = vi.hoisted(() => ({
  pushLog: { search: vi.fn(), summary: vi.fn(), export: vi.fn(), detail: vi.fn(), requeue: vi.fn() },
}))
vi.mock('../api/client', () => ({
  formatDate: (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : ''),
  api: { admin: { pushLog } },
}))
import PushLogView from '../components/admin/PushLogView.jsx'

/**
 * Webhook push gönderim logu — 2026-10-04 etiketleri: yeni tetikler (saat tavanı özeti, eskalasyon adımı), atlanan /
 * engellenen satırın insan diliyle nedeni (SKIPPED_USER_*, RATE_LIMITED), EN rozeti, özetlenen satırın "Özet #id" bağı ve
 * özet detayında kapsadığı satırlar + "Özeti aç".
 */
const ROWS = [
  { id: 50, trigger: 'OVERFLOW_SUMMARY', monitor_name: 'Saat tavanı özeti · 2 bildirim', username: 'u1', display_name: 'Bir',
    status: 'SENT', kind: 'SENT', at: '2026-10-04T11:30:00', push_lang: 'en', alert_level: 'CRITICAL' },
  { id: 41, trigger: 'ESCALATION_STEP', monitor_type: 'http', monitor_name: 'shop.example.com', username: 'u2', display_name: 'İki',
    status: 'SKIPPED_USER_SNOOZE', kind: 'SKIPPED', at: '2026-10-04T11:20:00', alert_level: 'HIGH' },
  { id: 40, trigger: 'OPEN', monitor_type: 'http', monitor_name: 'api.example.com', username: 'u1', display_name: 'Bir',
    status: 'RATE_LIMITED', kind: 'BLOCKED', at: '2026-10-04T11:10:00', alert_level: 'WARNING', overflow_summary_id: 50 },
]

describe('Webhook push logu — yeni durum / neden / tetik etiketleri', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/?tab=health&view=push')
    pushLog.summary.mockResolvedValue({ success: true, data: { granularity: 'day', kpi: { total: 3, sent: 1, failed: 0, blocked: 1, success_rate: 100 }, timeline: [], teams: [], levels: [], top_users: [], top_monitors: [] } })
    pushLog.search.mockResolvedValue({ success: true, data: ROWS, total: 3 })
  })

  it('tabloda: tetik etiketleri, insan diliyle neden, EN rozeti ve "Summary #50" bağı', async () => {
    render(<PushLogView onBack={() => {}} />)
    const table = await screen.findByTestId('sml-table')
    expect(within(table).getByText('Push was snoozed')).toBeInTheDocument()
    expect(within(table).getByText('Hourly push limit reached')).toBeInTheDocument()
    expect(table.querySelector('[data-slot="pl-lang"]')).toHaveAttribute('aria-label', 'Message in English')
    expect(table.querySelector('[data-slot="pl-summary-link"]').textContent).toBe('Summary #50')
    expect(within(table).getByText('Hourly-limit summary')).toBeInTheDocument()
    expect(within(table).getByText('Escalation step')).toBeInTheDocument()
  })

  it('özet detayı kapsadığı satırları listeler; özetlenen satırın detayında "Open summary" özeti açar', async () => {
    pushLog.detail.mockImplementation(async (id) => id === 50
      ? { success: true, data: { ...ROWS[0], created_at: '2026-10-04T11:30:00', message: 'SiteMonitor: 2 notifications were held back…',
          batch: [], summarized: [{ ...ROWS[2], created_at: '2026-10-04T11:10:00' }, { id: 39, monitor_type: 'ping', monitor_name: 'gw', alert_level: 'WARNING', status: 'RATE_LIMITED', kind: 'BLOCKED', created_at: '2026-10-04T11:05:00', overflow_summary_id: 50 }] } }
      : { success: true, data: { ...ROWS[2], created_at: '2026-10-04T11:10:00', message: 'UYARI: api', batch: [] } })
    render(<PushLogView onBack={() => {}} />)
    const table = await screen.findByTestId('sml-table')
    fireEvent.click(within(table).getByText('api.example.com').closest('[data-slot="pl-row"]'))
    const openSummary = await screen.findByRole('button', { name: 'Open summary' })
    fireEvent.click(openSummary)
    await waitFor(() => expect(pushLog.detail).toHaveBeenLastCalledWith(50))
    const list = await waitFor(() => { const el = document.querySelector('[data-slot="pl-summarized"]'); expect(el).not.toBeNull(); return el })
    expect(within(list).getByText('Summarised notifications (2)')).toBeInTheDocument()
    expect(within(list).getByText('gw')).toBeInTheDocument()
  })
})
