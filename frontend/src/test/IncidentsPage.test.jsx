import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from './test-utils.jsx'
import IncidentsPage from '../components/IncidentsPage.jsx'

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: {
    monitoring: {
      incidents: {
        list:          vi.fn(),
        comments:      vi.fn(),
        addComment:    vi.fn(),
        deleteComment: vi.fn(),
        remove:        vi.fn(),
      },
    },
  },
}))
import { api } from '../api/client'

const incident = {
  id: 1, status: 'ongoing',
  monitor: { name: 'https://x.example.com', type: 'http', tab: 'http', monitor_id: 9 },
  root_cause: { code: '500', category: 'server_error' }, comment_count: 2,
  alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL', started_at: '2026-07-10T10:00:00',
  resolved_at: null, acknowledged: false, domain: 'https://x.example.com',
}

describe('IncidentsPage', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('boş durumda dostane boş ekranı gösterir', async () => {
    api.monitoring.incidents.list.mockResolvedValue({ success: true, data: [], total: 0, type_counts: {} })
    render(<IncidentsPage systemRole="ADMIN" />)
    await waitFor(() => expect(api.monitoring.incidents.list).toHaveBeenCalled())
    expect(await screen.findByText(/incidents overview on the way|genel bakışınız yolda/i)).toBeInTheDocument()
  })

  it('olay satırını root-cause kodu + monitör + yorum sayısı ile listeler', async () => {
    api.monitoring.incidents.list.mockResolvedValue({ success: true, data: [incident], total: 1, type_counts: { HTTP_DOWN: 1 } })
    render(<IncidentsPage systemRole="ADMIN" />)
    await waitFor(() => expect(api.monitoring.incidents.list).toHaveBeenCalled())
    expect(await screen.findByText('500')).toBeInTheDocument()
    expect(screen.getByText('https://x.example.com')).toBeInTheDocument()
    expect(screen.getByText(/2 comments|2 yorum/i)).toBeInTheDocument()
  })

  it('ADMIN için silme butonu görünür', async () => {
    api.monitoring.incidents.list.mockResolvedValue({ success: true, data: [incident], total: 1, type_counts: {} })
    render(<IncidentsPage systemRole="ADMIN" />)
    await waitFor(() => expect(api.monitoring.incidents.list).toHaveBeenCalled())
    expect(await screen.findByTitle(/delete|sil/i)).toBeInTheDocument()
  })
})
