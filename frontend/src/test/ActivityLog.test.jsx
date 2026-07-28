import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from './test-utils.jsx'
import ActivityLog from '../components/ActivityLog.jsx'

// Birleşik aktivite akışı — api mock'lanır. Durum/tür rozetleri dilden bağımsız CSS/enum ile doğrulanır.
vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: { getActivity: vi.fn(), getActivitySummary: vi.fn(), getActivityDetail: vi.fn() },
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
