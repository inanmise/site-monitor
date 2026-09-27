import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'

/**
 * 2026-09-27 regresyon taraması (release-fixes.md FRONTEND A #9): Sertifika penceresi → Notlar → "Geçmiş"
 * (`toggleHistory`) try/finally'siz: `getNoteRevisions` ağ hatasında REDDEDİLİNCE (request() THROW eder) not geçmişi
 * spinner'ı kalıcı dönüyor ve işlenmemiş ret oluşuyordu. Düzeltme: bayrak finally'de söner, panel kapanır ("geçmiş yok"
 * denmez — bilinmiyor ≠ yok), hata bandı görünür; yeniden açmak yeniden dener.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  formatDateSec: (s) => String(s ?? ''),
  formatDateOnly: (s) => String(s ?? ''),
  api: withApiFallback({
    getHistory: vi.fn(),
    admin: { getNotes: vi.fn(), getNoteRevisions: vi.fn() },
    monitoring: {
      getCheckHistory: vi.fn().mockResolvedValue({ success: true, data: { items: [], page: 0, size: 50, total: 0, counts: { total: 0, fail: 0 }, range: { from: '', to: '' }, buckets: [], alerts: [] } }),
      getSslResponseSeries: vi.fn().mockResolvedValue({ success: true, data: { series: [], bucket: 'hour', unit: 'ms' } }),
    },
  }),
}))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ canView: () => true, canEdit: () => true, canExecute: () => true, perms: {} }),
}))
import { api } from '../api/client'
import CertificateModal from '../components/CertificateModal.jsx'

describe('CertificateModal — not geçmişi ağ hatası', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.getHistory.mockResolvedValue({ success: true, data: [{ domain: 'example.com', status: 'valid', days_remaining: 90 }] })
    api.admin.getNotes.mockResolvedValue({ success: true, data: [
      { id: 1, domain: 'example.com', note: 'ilk not', category: 'NOTE', author_username: 'admin', author_name: 'Yönetici', created_at: '2026-09-01T10:00:00' },
    ] })
  })

  it('getNoteRevisions REDDEDİLİRSE spinner kalıcı dönmez; panel kapanır, hata görünür, yeniden açmak yeniden dener', async () => {
    api.admin.getNoteRevisions.mockRejectedValueOnce(new Error('Failed to fetch'))
    render(<CertificateModal domain="example.com" onClose={() => {}} currentUser="admin" currentUserRole="ADMIN" initialTab="notes" />)
    const dlg = await screen.findByRole('dialog')
    await within(dlg).findByText('ilk not')

    fireEvent.click(within(dlg).getByRole('button', { name: /^(History|Geçmiş)$/ }))
    await waitFor(() => expect(api.admin.getNoteRevisions).toHaveBeenCalledWith('example.com', 1))
    expect(await within(dlg).findByText('Failed to fetch')).toBeInTheDocument()
    expect(dlg.querySelector('[data-slot="cert-note-history"]')).toBeNull()   // "geçmiş yok" iddiası YOK, spinner YOK

    api.admin.getNoteRevisions.mockResolvedValueOnce({ success: true, data: [] })
    fireEvent.click(within(dlg).getByRole('button', { name: /^(History|Geçmiş)$/ }))
    await waitFor(() => expect(api.admin.getNoteRevisions).toHaveBeenCalledTimes(2))
    expect(await within(dlg).findByText(/No history recorded for this note|Bu not için kayıtlı bir geçmiş yok/)).toBeInTheDocument()
  })
})
