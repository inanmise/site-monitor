import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from './test-utils.jsx'

/**
 * Sertifika penceresi — sunucunun kapılarıyla (2026-10-22 hata avı):
 *  - UIM-14: çöp kutusu `canDelete` ile (yönetim kapsamı / DELETE paylaşımı; USER üyesi görmez) — verilmezse bugünkü kural;
 *  - UIM-9: Tanıla `canCheck` ile (7/24 operatörü başka takımda yok);
 *  - UIM-12: Notlar sekmesi silme / geri yükleme yalnız global yönetici (`globalAdmin` K3; yoksa izin anlık görüntüsü K1).
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const perm = vi.hoisted(() => ({ scope: null }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({
    canView: () => true, canEdit: () => true, canExecute: () => true, perms: {}, refresh: () => {}, scope: perm.scope,
  }),
}))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  formatDateSec: (s) => String(s ?? ''),
  formatDateOnly: (s) => String(s ?? ''),
  formatTime: (s) => String(s ?? ''),
  api: withApiFallback({
    getHistory: vi.fn().mockResolvedValue({ success: true, data: [{ domain: 'own.example.test', status: 'valid', days_remaining: 80, port: 443 }] }),
    checkDomainPreview: vi.fn().mockResolvedValue({ success: true, data: null }),
    getDomainAlerts: vi.fn().mockResolvedValue({ success: true, data: [] }),
    admin: {
      getNotes: vi.fn().mockResolvedValue({ success: true, data: [
        { id: 2, domain: 'own.example.test', author_username: 'kisi.b', author_name: 'Kişi B', note: 'Başkasının notu', created_at: '2026-10-20T08:00:00', category: 'NOTE' },
        { id: 3, domain: 'own.example.test', author_username: 'kisi.b', author_name: 'Kişi B', note: 'Silinmiş', created_at: '2026-10-19T08:00:00', category: 'NOTE', deleted_at: '2026-10-19T09:00:00' },
      ] }),
      getNoteRevisions: vi.fn().mockResolvedValue({ success: true, data: [] }),
    },
  }),
}))
import CertificateModal from '../components/CertificateModal.jsx'

const openModal = (extra = {}) => render(
  <CertificateModal domain="own.example.test" onClose={() => {}} currentUser="kisi.a" currentUserRole="ADMIN" {...extra} />,
)
const deleteBtn = () => screen.queryByRole('button', { name: 'Delete' })

describe('CertificateModal — sunucu kapıları', () => {
  beforeEach(() => { perm.scope = null })

  it('UIM-14: canDelete=false → çöp kutusu YOK; canDelete=true → var; verilmezse bugünkü kural (izin)', async () => {
    const r1 = openModal({ canDelete: false })
    await screen.findByRole('dialog')
    await waitFor(() => expect(document.querySelector('[data-slot="cert-modal-status"]')).not.toBeNull())
    expect(deleteBtn()).toBeNull()
    r1.unmount()
    const r2 = openModal({ canDelete: true })
    await waitFor(() => expect(deleteBtn()).not.toBeNull())
    r2.unmount()
    openModal()
    await waitFor(() => expect(deleteBtn()).not.toBeNull())
  })

  it('UIM-9: canCheck=false → Tanıla yok', async () => {
    openModal({ canCheck: false })
    await waitFor(() => expect(document.querySelector('[data-slot="cert-modal-status"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="cert-diagnose"]')).toBeNull()
  })

  it('UIM-12: kapsamlı müdür (ADMIN, globalAdmin=false) Notlar sekmesinde başkasının notunu silemez / geri yükleyemez', async () => {
    openModal({ globalAdmin: false, initialTab: 'notes' })
    await waitFor(() => expect(document.querySelectorAll('[data-slot="cert-note"]').length).toBe(2))
    expect(screen.queryByRole('button', { name: 'Delete note by Kişi B' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Restore note by Kişi B' })).toBeNull()
  })

  it('UIM-12: global yönetici siler ve geri yükler; prop yoksa izin anlık görüntüsünün `scope.global_admin`ı (K1)', async () => {
    const r1 = openModal({ globalAdmin: true, initialTab: 'notes' })
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Delete note by Kişi B' })).not.toBeNull())
    expect(screen.getByRole('button', { name: 'Restore note by Kişi B' })).toBeInTheDocument()
    r1.unmount()
    perm.scope = { global_admin: true }
    openModal({ initialTab: 'notes' })
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Delete note by Kişi B' })).not.toBeNull())
  })
})
