import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'

/**
 * Manuel sertifikaya yeni sürüm yüklenince (2026-10-09, hata düzeltmesi) SSL sekmesi ESKİ sürümün çevrim-dışı önizlemesini
 * göstermeye devam ediyordu (veri doluyken yeniden probe edilmez) ve Pano kartı eski sürümde kalıyordu. Artık önizleme
 * boşaltılıp yeniden okunur ve çağıran (`onDataChanged`) haberdar edilir.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  formatDateSec: (s) => String(s ?? ''),
  formatDateOnly: (s) => String(s ?? ''),
  formatTime: (s) => String(s ?? ''),
  api: withApiFallback({
    getHistory: vi.fn().mockResolvedValue({ success: true, data: [{ domain: 'keystore.example.test', status: 'valid', days_remaining: 80, san: [] }] }),
    checkDomainPreview: vi.fn(),
    getDomainAlerts: vi.fn().mockResolvedValue({ success: true, data: [] }),
    admin: {
      getNotes: vi.fn().mockResolvedValue({ success: true, data: [] }),
      getInventoryByDomain: vi.fn().mockResolvedValue({ success: true, data: { id: 77, domain: 'keystore.example.test', cert_source: 'MANUAL', can_manage: true } }),
    },
  }),
}))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ canView: () => true, canEdit: () => true, canExecute: () => true, perms: {} }),
}))
// Sürümler sekmesi: sihirbazın "bitti" anını tek düğmeyle taklit eder
vi.mock('../components/manualcert/ManualCertVersions.jsx', () => ({
  default: ({ onRenewed }) => <button type="button" onClick={() => onRenewed?.()}>yeni-surum-bitti</button>,
}))

import { api } from '../api/client'
import CertificateModal from '../components/CertificateModal.jsx'
import { uploadPreview } from './helpers/sslPreviewFixture.js'

describe('CertificateModal — manuel sertifika yeni sürüm sonrası', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('SSL önizlemesi yeniden okunur ve onDataChanged çağrılır', async () => {
    api.checkDomainPreview
      .mockResolvedValueOnce({ success: true, data: uploadPreview({ domain: 'keystore.example.test' }) })
      .mockResolvedValueOnce({ success: true, data: uploadPreview({ domain: 'keystore.example.test' }) })
    const onDataChanged = vi.fn()
    render(<CertificateModal domain="keystore.example.test" manual manualMeta={{ version: 3, uploadedAt: '2026-09-30T08:00:00' }}
      onClose={() => {}} currentUser="admin" currentUserRole="ADMIN" onCheckNow={vi.fn()} onEdit={vi.fn()} onDataChanged={onDataChanged} />)
    const dlg = await screen.findByRole('dialog')
    await waitFor(() => expect(api.checkDomainPreview).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(dlg.querySelector('[data-slot="ssl-panel"]')).not.toBeNull())

    fireEvent.mouseDown(within(dlg).getByRole('tab', { name: /versions|sürümler/i }))
    fireEvent.click(within(dlg).getByRole('tab', { name: /versions|sürümler/i }))
    fireEvent.click(await within(dlg).findByRole('button', { name: 'yeni-surum-bitti' }))
    expect(onDataChanged).toHaveBeenCalledTimes(1)

    fireEvent.mouseDown(within(dlg).getByRole('tab', { name: /ssl/i }))
    fireEvent.click(within(dlg).getByRole('tab', { name: /ssl/i }))
    await waitFor(() => expect(api.checkDomainPreview).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(dlg.querySelector('[data-slot="ssl-panel"]')).not.toBeNull())
  })
})
