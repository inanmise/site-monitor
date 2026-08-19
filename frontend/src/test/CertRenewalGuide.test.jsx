import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from './test-utils.jsx'
import CertRenewalGuide from '../components/CertRenewalGuide.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({ guideLinks: { list: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn() } }),
}))

import { api } from '../api/client'

describe('CertRenewalGuide', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('renders the empty state when no links are returned', async () => {
    api.guideLinks.list.mockResolvedValueOnce({ success: true, data: [] })
    render(<CertRenewalGuide isAdmin={false} />)
    await waitFor(() => expect(api.guideLinks.list).toHaveBeenCalled())
    expect(document.body.textContent.length).toBeGreaterThan(0)
  })

  it('hides the add-link button for non-admin viewers', async () => {
    api.guideLinks.list.mockResolvedValueOnce({ success: true, data: [] })
    render(<CertRenewalGuide isAdmin={false} />)
    await waitFor(() => expect(api.guideLinks.list).toHaveBeenCalled())
    expect(screen.queryByRole('button', { name: /link ekle|add link/i })).toBeNull()
  })

  it('shows the add-link button for admins', async () => {
    api.guideLinks.list.mockResolvedValueOnce({ success: true, data: [] })
    render(<CertRenewalGuide isAdmin />)
    await waitFor(() => expect(screen.getByRole('button', { name: /link ekle|add link/i })).toBeDefined())
  })

  it('groups links by category and renders title + url', async () => {
    api.guideLinks.list.mockResolvedValueOnce({
      success: true,
      data: [
        { id: 1, category: 'Netscaler', title: 'Vserver',  url: 'https://wiki/ns-vserver' },
        { id: 2, category: 'Netscaler', title: 'Cert swap', url: 'https://wiki/ns-cert'   },
        { id: 3, category: 'WAF',       title: 'Cert swap', url: 'https://wiki/waf-cert'  },
      ],
    })
    render(<CertRenewalGuide isAdmin={false} />)
    await waitFor(() => expect(screen.getByText('Vserver')).toBeDefined())
    expect(screen.getByText('Netscaler')).toBeDefined()
    expect(screen.getByText('WAF')).toBeDefined()
    // URL is displayed openly under the title (this PR's fix)
    expect(screen.getByText('https://wiki/ns-vserver')).toBeDefined()
  })
})
