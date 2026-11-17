import { describe, it, expect, vi, beforeEach } from 'vitest'
import { act, render, waitFor, fireEvent } from './test-utils.jsx'

/**
 * Performans (2026-11-17): izleme zaman çizelgesinin erişilebilirlik şeridi. Ok tuşuyla seçili dönem değişince 500'e kadar
 * bölümün HEPSİ yeniden çiziliyordu. Bölümler memo: yalnız eski ve yeni seçili bölüm (ve sekme durağı) çizilir.
 */
const renders = vi.hoisted(() => ({ seg: 0 }))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({ formatDateSec: (s) => s ?? '', api: withApiFallback({ getActivityTimeline: vi.fn() }) }))
vi.mock('../contexts/PermissionsProvider.jsx', async (orig) => ({
  ...(await orig()),
  usePermissions: () => ({ perms: {}, scope: null, canView: () => true, canEdit: () => false, canExecute: () => false, refresh: () => {}, loaded: true }),
}))
vi.mock('@/components/shadcn/button', async (orig) => {
  const real = await orig()
  const React = await import('react')
  const Button = React.forwardRef(function CountingButton(props, ref) {
    if (props['data-slot'] === 'act-tl-seg') renders.seg += 1
    return React.createElement(real.Button, { ...props, ref })
  })
  return { ...real, Button }
})
import { api } from '../api/client'
import MonitorTimelineView from '../components/activity/timeline/MonitorTimelineView.jsx'

const N = 120
const START = Date.parse('2026-11-04T08:00:00Z')
const at = (i) => new Date(START + i * 12 * 60_000).toISOString().slice(0, 19)
const PERIODS = Array.from({ length: N }, (_, i) => ({ from: at(i), to: at(i + 1), state: i % 2 ? 'down' : 'up', checks: 3 }))
const BODY = {
  monitor: { type: 'HTTP', id: 4, name: 'Ödeme', target: 'https://odeme.example.com/health', team_id: 1, team_name: 'Ödeme Ekibi' },
  from: at(0), to: at(N), checks: N * 3, scheduled_checks: N * 3, availability_pct: 50, mttr_s: 600, longest_outage_s: 720,
  outage_count: N / 2, periods: PERIODS, outages: [], series: [], truncated: false,
}

beforeEach(() => {
  vi.clearAllMocks()
  renders.seg = 0
  localStorage.setItem('site-monitor-lang', 'en')
  window.history.replaceState(null, '', '/?tab=activity&amon=HTTP:4')
  api.getActivityTimeline.mockResolvedValue({ success: true, ...BODY })
})

describe('İzleme zaman çizelgesi — şerit bölümleri memo', () => {
  it('ok tuşuyla gezinmek yalnız değişen bölümleri yeniden çizer', async () => {
    const { container } = render(<MonitorTimelineView type="HTTP" id={4} onBack={() => {}} onOpenActivity={() => {}} />)
    await waitFor(() => expect(container.querySelectorAll('[data-slot="act-tl-seg"]')).toHaveLength(N))
    const selected = () => container.querySelector('[data-slot="act-tl-seg"][aria-pressed="true"]')
    const start = Number(selected().getAttribute('data-index'))
    renders.seg = 0
    await act(async () => { fireEvent.keyDown(selected(), { key: 'ArrowLeft' }) })
    expect(Number(selected().getAttribute('data-index'))).toBe(start - 1)
    expect(renders.seg).toBeLessThanOrEqual(4)          // eskiden N (120)
    renders.seg = 0
    await act(async () => { fireEvent.keyDown(selected(), { key: 'Home' }) })
    expect(selected().getAttribute('data-index')).toBe('0')
    expect(renders.seg).toBeLessThanOrEqual(4)
    // Tıklama da yalnız iki bölümü çizer; ayrıntı paneli seçili dönemi gösterir
    renders.seg = 0
    fireEvent.click(container.querySelector('[data-slot="act-tl-seg"][data-index="5"]'))
    expect(selected().getAttribute('data-index')).toBe('5')
    expect(container.querySelector('[data-slot="act-tl-period"]').getAttribute('data-state')).toBe('down')
    expect(renders.seg).toBeLessThanOrEqual(4)
  })
})
