import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from './test-utils.jsx'
import ActivityLog, { activityTarget } from '../components/ActivityLog.jsx'

// Birleşik aktivite akışı — api mock'lanır. Durum/tür rozetleri dilden bağımsız CSS/enum ile doğrulanır.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({ getActivity: vi.fn(), getActivitySummary: vi.fn(), getActivityDetail: vi.fn() }),
}))

import { api } from '../api/client'

const row = (over) => ({
  id: 1, monitor_type: 'HTTP', monitor_name: 'web', target: 'https://x.com',
  action: 'SCHEDULED_CHECK', result_status: 'SUCCESS', result_summary: '200 · 340ms',
  activity_time: '2026-07-28T10:00:00', response_ms: 340, ...over,
})

describe('ActivityLog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.getActivitySummary.mockResolvedValue({ success: true, total: 2, success_count: 1, warning: 1, error: 0 })
    api.getActivityDetail.mockResolvedValue({ success: true, data: row(), recent: [] })
  })

  it('farklı türde aktiviteleri birleşik akışta gösterir (tür rozeti + hedef + özet)', async () => {
    api.getActivity.mockResolvedValue({
      success: true, page: 0, total: 2,
      data: [
        row({ id: 1, monitor_type: 'HTTP', monitor_name: 'web', result_status: 'SUCCESS', result_summary: '200 · 340ms' }),
        row({ id: 2, monitor_type: 'CERT', monitor_name: 'cert-a', target: 'a.com', result_status: 'WARNING', result_summary: '12d' }),
      ],
    })

    const { container } = render(<ActivityLog />)

    expect(await screen.findByText('web')).toBeInTheDocument()
    expect(screen.getByText('cert-a')).toBeInTheDocument()
    expect(screen.getByText('a.com')).toBeInTheDocument()
    expect(screen.getByText('https://x.com')).toBeInTheDocument()
    expect(screen.getByText('200 · 340ms')).toBeInTheDocument()
    // İki satır çizildi
    expect(container.querySelectorAll('.act-item')).toHaveLength(2)
  })

  it('boş veri → hiç satır yok (boş durum)', async () => {
    api.getActivity.mockResolvedValue({ success: true, page: 0, total: 0, data: [] })

    const { container } = render(<ActivityLog />)

    await waitFor(() => expect(api.getActivity).toHaveBeenCalled())
    await waitFor(() => expect(container.querySelectorAll('.act-item')).toHaveLength(0))
  })

  it('yükleme hatası → hata durumu gösterilir', async () => {
    api.getActivity.mockRejectedValue(new Error('network'))

    const { container } = render(<ActivityLog />)

    await waitFor(() => expect(container.querySelector('.act-error-state')).not.toBeNull())
  })

  it('tür filtresine tıklayınca getActivity o türle çağrılır', async () => {
    api.getActivity.mockResolvedValue({ success: true, page: 0, total: 0, data: [] })

    render(<ActivityLog />)
    await waitFor(() => expect(api.getActivity).toHaveBeenCalled())

    fireEvent.click(screen.getByRole('button', { name: /HTTP/ }))

    await waitFor(() => {
      const lastCall = api.getActivity.mock.calls.at(-1)[0]
      expect(lastCall.type).toContain('HTTP')
    })
  })
})

describe('ActivityLog — zaman grupları + katlama (2026-09-12, #22)', () => {
  it('Bugün / Dün başlıkları; aynı hedefin 3 ardışık kontrolü tek satıra katlanır, tıklayınca açılır', async () => {
    const now = Date.now()
    const iso = (ms) => new Date(ms).toISOString().slice(0, 19)
    api.getActivity.mockResolvedValue({
      success: true, page: 0, total: 5,
      data: [
        row({ id: 1, monitor_type: 'HTTP', monitor_name: 'web', target: 'https://w.example.com', result_status: 'SUCCESS', activity_time: iso(now - 60_000) }),
        row({ id: 2, monitor_type: 'HTTP', monitor_name: 'web', target: 'https://w.example.com', result_status: 'ERROR', activity_time: iso(now - 120_000) }),
        row({ id: 3, monitor_type: 'HTTP', monitor_name: 'web', target: 'https://w.example.com', result_status: 'SUCCESS', activity_time: iso(now - 180_000) }),
        row({ id: 4, monitor_type: 'CERT', monitor_name: 'cert-a', target: 'a.com', result_status: 'WARNING', activity_time: iso(now - 240_000) }),
        row({ id: 5, monitor_type: 'PING', monitor_name: 'gw', target: '10.0.0.1', result_status: 'SUCCESS', activity_time: iso(now - 30 * 3600_000) }),
      ],
    })
    render(<ActivityLog />)
    await screen.findByText('cert-a')
    const heads = [...document.querySelectorAll('.act-group-head')].map((h) => h.textContent)
    expect(heads[0]).toMatch(/Bugün|Today/)
    expect(heads.some((h) => /Dün|Yesterday|Bu hafta|This week/.test(h))).toBe(true)
    const fold = document.querySelector('.act-fold')
    expect(fold).not.toBeNull()
    expect(fold.textContent).toMatch(/3 ardışık kontrol|3 consecutive checks/)
    expect(fold.textContent).toMatch(/1 hata|1 errors/)
    fireEvent.click(fold.querySelector('.act-item-row'))
    expect(document.querySelector('.act-fold')).toBeNull()
    expect(document.querySelectorAll('.act-item').length).toBe(5)
  })

  it('2026-09-20: activityTarget haritası — sertifika → dashboard ?q=alan, uptime → uptime, ping → ping ?monitor=id, scripted param taşımaz, bilinmeyen null', () => {
    expect(activityTarget({ monitor_type: 'CERT', target: 'https://a.example.com:443/x' })).toEqual({ tab: 'dashboard', params: { domain: 'a.example.com' } })
    expect(activityTarget({ monitor_type: 'UPTIME', target: 'b.example.com' })).toEqual({ tab: 'uptime', params: { q: 'b.example.com' } })
    expect(activityTarget({ monitor_type: 'PING', monitor_id: 7, target: '10.0.0.1' })).toEqual({ tab: 'ping', params: { monitor: 7 } })
    expect(activityTarget({ monitor_type: 'SCRIPTED', monitor_id: 3 })).toEqual({ tab: 'scripted', params: undefined })
    expect(activityTarget({ monitor_type: 'WEIRD' })).toBeNull()
  })

  it('2026-09-20: satırda takım rozeti; "İzlemeye git" düğmesi ve ad tıklaması izleme sekmesine gider (detay açılmaz); özet kalemi duruma süzer', async () => {
    api.getActivity.mockResolvedValue({ success: true, page: 0, total: 1, data: [row({ id: 5, monitor_type: 'PING', monitor_id: 9, monitor_name: 'gw', target: '10.0.0.1', team_id: 5, team_name: 'Takim A' })] })
    const nav = vi.fn(); window.addEventListener('sm:navigate', nav)
    const { container } = render(<ActivityLog />)
    expect(await screen.findByText('gw')).toBeInTheDocument()
    expect(container.querySelector('.act-item-team').textContent).toContain('Takim A')
    // Ad artık satırı ayırt ediyor: "gw — izlemeye git" (eskiden her satırda aynıydı).
    fireEvent.click(screen.getByRole('button', { name: /izlemeye git|go to the monitor/i }))
    expect(nav.mock.calls.at(-1)[0].detail).toEqual({ tab: 'ping', params: { monitor: 9 } })
    fireEvent.click(screen.getByText('gw'))
    expect(nav).toHaveBeenCalledTimes(2)
    expect(container.querySelector('.act-item.open')).toBeNull()
    expect(api.getActivityDetail).not.toHaveBeenCalled()
    // özet: Hata kalemi → status=ERROR süzgeci
    fireEvent.click(screen.getByRole('button', { name: /Hata|Error/ }))
    await waitFor(() => expect(api.getActivity).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'ERROR' })))
    expect(new URLSearchParams(window.location.search).get('astatus')).toBe('ERROR')
    fireEvent.click(screen.getByRole('button', { name: /Hata|Error/ }))
    await waitFor(() => expect(api.getActivity).toHaveBeenLastCalledWith(expect.objectContaining({ status: '' })))
    window.removeEventListener('sm:navigate', nav)
  })
})
