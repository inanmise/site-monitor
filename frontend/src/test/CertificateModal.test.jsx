import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from './test-utils.jsx'

/**
 * CertificateModal does live API fetches in useEffect. We mock the entire
 * client module so the modal can render without hitting the network. The
 * goal is smoke coverage: a non-null domain renders the chrome (close
 * button, tab bar, status pill); a null domain renders nothing.
 */
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  api: {
    getHistory:           vi.fn().mockResolvedValue({ success: true, data: [] }),
    checkDomainPreview:   vi.fn().mockResolvedValue({ success: true, data: null }),
    getDomainAlerts:      vi.fn().mockResolvedValue({ success: true, data: [] }),
    admin: {
      getNotes:     vi.fn().mockResolvedValue({ success: true, data: [] }),
      addNote:      vi.fn().mockResolvedValue({ success: true, data: {} }),
      updateNote:   vi.fn().mockResolvedValue({ success: true, data: {} }),
      deleteNote:   vi.fn().mockResolvedValue({ success: true }),
      getNoteRevisions: vi.fn().mockResolvedValue({ success: true, data: [] }),
      restoreNote:  vi.fn().mockResolvedValue({ success: true, data: {} }),
      getAlerts:    vi.fn().mockResolvedValue({ success: true, data: [], pagination: { totalPages: 0 } }),
      acknowledgeAlert: vi.fn().mockResolvedValue({ success: true }),
      resolveAlert: vi.fn().mockResolvedValue({ success: true }),
      reNotify:     vi.fn().mockResolvedValue({ success: true }),
    },
  },
}))

import CertificateModal from '../components/CertificateModal.jsx'

describe('CertificateModal', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders nothing when domain is null', () => {
    const { container } = render(
      <CertificateModal domain={null} onClose={() => {}} currentUser="admin" currentUserRole="ADMIN" />
    )
    expect(container.firstChild).toBeNull()
  })

  it('renders the modal chrome when a domain is provided', async () => {
    render(
      <CertificateModal
        domain="example.com"
        onClose={() => {}}
        currentUser="admin"
        currentUserRole="ADMIN"
      />
    )
    // Title shows the domain.
    expect(await screen.findByText('example.com')).toBeDefined()
  })

  it('invokes onClose when the close button is clicked', async () => {
    const onClose = vi.fn()
    render(
      <CertificateModal
        domain="example.com"
        onClose={onClose}
        currentUser="admin"
        currentUserRole="ADMIN"
      />
    )
    // The close button uses an X icon with aria-label="Close".
    const closeBtn = await screen.findByLabelText(/close/i)
    closeBtn.click()
    expect(onClose).toHaveBeenCalled()
  })

})
