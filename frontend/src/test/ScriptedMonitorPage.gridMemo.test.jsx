import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import ScriptedMonitorPage from '../components/ScriptedMonitorPage.jsx'

/**
 * Sentetik izleme sayfası — form / k6 editöründe yazmak kart ızgarasını ÇİZMEZ (2026-11-06, performans PF4 O2).
 * Ortak desen: monitorPagesGridMemo.test.jsx (diğer dokuz sayfa). Sayaç: kartların eylem çubuğu (MonitorCardActions).
 */
const renders = vi.hoisted(() => ({ n: 0 }))
vi.mock('../components/MonitorCardActions.jsx', async (importOriginal) => {
  const m = await importOriginal()
  const Real = m.default
  return { ...m, default: (props) => { renders.n++; return <Real {...props} /> } }
})
vi.mock('../components/ui/CodeEditor.jsx', () => ({
  default: ({ value, onChange }) => (
    <textarea data-testid="code-editor" value={value} onChange={(e) => onChange(e.target.value)} />
  ),
}))
vi.mock('../components/ResponseTimeChart.jsx', () => ({ default: () => <div data-testid="chart" /> }))
vi.mock('../components/MonitorNotes.jsx', () => ({ default: () => <div data-testid="monitor-notes" /> }))
vi.mock('../components/admin/AlertHistory.jsx', () => ({ default: () => <div data-testid="alert-history" /> }))
vi.mock('../api/client', async () => (await import('./helpers/scriptedHarness.jsx')).apiClientMock())

import { api } from '../api/client'
import { resetScriptedMocks } from './helpers/scriptedHarness.jsx'

const N = 10
const cards = (container) => [...container.querySelectorAll('.upt-grid > [data-slot="card"]')]

beforeEach(() => {
  resetScriptedMocks(api)
  renders.n = 0
  window.history.replaceState(null, '', '/')
  api.monitoring.getScriptedMonitors.mockResolvedValue({
    success: true,
    data: {
      k6_available: true, k6_version: 'v2.3.0', can_manage: true,
      monitors: Array.from({ length: N }, (_, k) => ({
        id: k + 1, name: `Senaryo ${String(k + 1).padStart(2, '0')}`, status: 'PASS', team_id: 5, team_name: 'SY-A',
        duration_ms: 800, checks_passed: 3, checks_failed: 0, checked_at: '2026-07-31T10:00:00', active: true,
        can_edit: true, can_check: true, can_delete: true, script: 'export default function(){}',
      })),
    },
  })
})

describe('ScriptedMonitorPage — form yazımı kart ızgarasını çizmez', () => {
  it('ad alanına ve script editörüne yazmak kartları yeniden çizmez; seçim kutusu çizer', async () => {
    const { container } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(cards(container)).toHaveLength(N))

    fireEvent.click(screen.getByRole('button', { name: /^(new monitor|yeni monitör)$/i }))
    const dlg = await screen.findByRole('dialog')
    await new Promise((r) => setTimeout(r, 50))
    const before = renders.n
    const name = within(dlg).getByRole('textbox', { name: /^(Ad|Name)\b/ })
    for (const v of ['S', 'Se', 'Sen']) fireEvent.change(name, { target: { value: v } })
    const editor = screen.queryByTestId('code-editor')
    if (editor) for (const v of ['e', 'ex', 'exp']) fireEvent.change(editor, { target: { value: v } })
    await waitFor(() => expect(name).toHaveValue('Sen'))
    expect(renders.n - before).toBe(0)
  }, 20_000)

  it('karttaki seçim kutusu (ızgara verisi) kartları çizer ve işaretli görünür', async () => {
    const { container } = render(<ScriptedMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
    await waitFor(() => expect(cards(container)).toHaveLength(N))
    const before = renders.n
    fireEvent.click(within(cards(container)[0]).getByRole('checkbox'))
    await waitFor(() => expect(within(cards(container)[0]).getByRole('checkbox')).toHaveAttribute('data-state', 'checked'))
    expect(renders.n).toBeGreaterThan(before)
  })
})
