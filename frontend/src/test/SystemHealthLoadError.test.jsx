import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from './test-utils.jsx'
import SystemHealth from '../components/admin/SystemHealth.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      getSystemHealth: vi.fn(),
      getMetrics:      vi.fn(),
      getHttpMetrics:  vi.fn(),
      getDbStats:      vi.fn(),
      // user/session observability — varsayılan başarılı (clearAllMocks impl'i korur)
      getUserActivity:      vi.fn().mockResolvedValue({ success: true, data: {} }),
      terminateUserSession: vi.fn().mockResolvedValue({ success: true }),
      // unused but referenced in the file
      releaseSchedulerLock:   vi.fn(),
      triggerSchedulerRun:    vi.fn(),
      getCertPoolHealth:      vi.fn().mockResolvedValue({ success: true, data: {} }),
      getSchedulerHistory:    vi.fn().mockResolvedValue({ success: true, data: [] }),
      getMailLogs:            vi.fn().mockResolvedValue({ success: true, data: [] }),
    },
  }),
}))

import { api } from '../api/client'

describe('SystemHealth load errors', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows the load-error banner when all four data endpoints reject', async () => {
    api.admin.getSystemHealth.mockRejectedValue(new Error('network down'))
    api.admin.getMetrics.mockRejectedValue(new Error('network down'))
    api.admin.getHttpMetrics.mockRejectedValue(new Error('network down'))
    api.admin.getDbStats.mockRejectedValue(new Error('network down'))

    render(<SystemHealth systemRole="ADMIN" />)

    // health.loadErrorTitle = "Failed to load" (EN default in tests)
    await waitFor(() => {
      expect(screen.getByText(/Failed to load/i)).toBeDefined()
    })

    // Reload button rendered (uses err.reload key)
    expect(screen.getByRole('button', { name: /Reload/i })).toBeDefined()
  })

  it('does not show the banner when all four endpoints succeed', async () => {
    api.admin.getSystemHealth.mockResolvedValue({ success: true, data: { scheduler: {}, network: {} } })
    api.admin.getMetrics.mockResolvedValue({ success: true, data: [] })
    api.admin.getHttpMetrics.mockResolvedValue({ success: true, data: { summary: {}, history: [] } })
    api.admin.getDbStats.mockResolvedValue({ success: true, data: [] })

    render(<SystemHealth systemRole="ADMIN" />)

    await waitFor(() => {
      expect(api.admin.getSystemHealth).toHaveBeenCalled()
    })
    // Banner absent
    expect(screen.queryByText(/Failed to load/i)).toBeNull()
  })

  it('shows the banner for a single failed endpoint', async () => {
    api.admin.getSystemHealth.mockResolvedValue({ success: true, data: { scheduler: {}, network: {} } })
    api.admin.getMetrics.mockResolvedValue({ success: true, data: [] })
    api.admin.getHttpMetrics.mockRejectedValue(new Error('5xx'))
    api.admin.getDbStats.mockResolvedValue({ success: true, data: [] })

    render(<SystemHealth systemRole="ADMIN" />)

    await waitFor(() => {
      expect(screen.getByText(/Failed to load/i)).toBeDefined()
    })
    // HTTP section name should appear in the failed list
    expect(screen.getByText(/HTTP/i)).toBeDefined()
  })
})
