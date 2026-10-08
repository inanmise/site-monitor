import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within, fireEvent } from './test-utils.jsx'

/**
 * Sertifika penceresi — kayıt AÇIKKEN yeniden adlandırılır (2026-10-08, kullanıcı: "takip adı değiştirince Sağlık bilgisi
 * yüklenemedi … 404" ve "Kontrol geçmişi yüklenemedi / Domain envanterde bulunamadı … 404"). Pencere eski adla istek
 * atmayı sürdürüyordu; artık App olayla `domain`'i yeni ada taşır (`renamedFrom` = eski ad) ve pencere:
 * - Sağlık / Kontrol geçmişini YENİ adla okur, hata göstermez;
 * - kullanıcının bulunduğu sekmede kalır (yeni bir kayıt açılmış gibi SSL'e atmaz);
 * - başka bir kayda geçişte (renamedFrom yok) eskisi gibi açılış sekmesine döner.
 * Sunucu taklidi: yeniden adlandırmadan sonra eski ad 404 döner (canlı sistemde ölçülen davranış).
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

const server = vi.hoisted(() => ({ renamed: false }))
const notFound = (msg) => ({ success: false, status: 404, error: msg })

vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  formatDateSec: (s) => String(s ?? ''),
  formatDateOnly: (s) => String(s ?? ''),
  formatTime: (s) => String(s ?? ''),
  api: withApiFallback({
    getHistory: vi.fn(async (d) => (server.renamed && d === 'eski-ad' ? notFound('Domain envanterde bulunamadı')
      : { success: true, data: [{ domain: d, status: 'valid', days_remaining: 80, san: [] }] })),
    getCertificateHealth: vi.fn(async (d) => (server.renamed && d === 'eski-ad' ? notFound('Kayıt bulunamadı')
      : { success: true, data: { domain: d, checks: [], summary: {} } })),
    checkDomainPreview: vi.fn().mockResolvedValue({ success: true, data: null }),
    getDomainAlerts: vi.fn().mockResolvedValue({ success: true, data: [] }),
    manualCerts: { get: vi.fn().mockResolvedValue({ success: true, data: { inventory_id: 77, can_manage: true, versions: [] } }) },
    monitoring: {
      getCheckHistory: vi.fn(async (_k, id) => (server.renamed && id === 'eski-ad' ? notFound('Domain envanterde bulunamadı')
        : { success: true, data: [], total: 0 })),
    },
    admin: {
      getNotes: vi.fn().mockResolvedValue({ success: true, data: [] }),
      getInventoryByDomain: vi.fn(async (d) => ({ success: true, data: { id: 77, domain: d, cert_source: 'MANUAL', can_manage: true } })),
    },
  }),
}))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ canView: () => true, canEdit: () => true, canExecute: () => true, perms: {} }),
}))

import { api } from '../api/client'
import CertificateModal from '../components/CertificateModal.jsx'

const props = { manual: true, onClose: () => {}, currentUser: 'admin', currentUserRole: 'ADMIN', onCheckNow: vi.fn(), onEdit: vi.fn() }
const tab = (dlg, re) => within(dlg).getByRole('tab', { name: re })
const HEALTH = /^(Health|Sağlık)/
const HISTORY = /^(Check history|Kontrol geçmişi)/i
const SSL = /SSL/i
const ERRORS = /(Could not load health|Sağlık bilgisi yüklenemedi|Could not load check history|Kontrol geçmişi yüklenemedi|bulunamadı)/

describe('CertificateModal — açıkken yeniden adlandırma', () => {
  beforeEach(() => { vi.clearAllMocks(); server.renamed = false })

  it('Sağlık sekmesindeyken ad değişir: sekme korunur, Sağlık ve Kontrol geçmişi YENİ adla okunur, 404 yok', async () => {
    const { rerender } = render(<CertificateModal domain="eski-ad" {...props} />)
    const dlg = await screen.findByRole('dialog')
    fireEvent.mouseDown(tab(dlg, HEALTH)); fireEvent.click(tab(dlg, HEALTH))
    await waitFor(() => expect(api.getCertificateHealth).toHaveBeenCalledWith('eski-ad'))
    await waitFor(() => expect(tab(dlg, HEALTH)).toHaveAttribute('aria-selected', 'true'))

    // Sunucuda ad değişti; App olayı → pencere yeni adla yeniden çizilir
    server.renamed = true
    vi.clearAllMocks()
    rerender(<CertificateModal domain="yeni-ad" renamedFrom="eski-ad" {...props} />)

    await waitFor(() => expect(api.getCertificateHealth).toHaveBeenCalledWith('yeni-ad'))
    expect(tab(dlg, HEALTH)).toHaveAttribute('aria-selected', 'true')
    expect(within(dlg).getAllByText('yeni-ad').length).toBeGreaterThan(0)

    fireEvent.mouseDown(tab(dlg, HISTORY)); fireEvent.click(tab(dlg, HISTORY))
    await waitFor(() => expect(api.monitoring.getCheckHistory).toHaveBeenCalled())
    await waitFor(() => expect(api.getHistory).toHaveBeenCalledWith('yeni-ad'))
    // Yeniden adlandırmadan sonra eski adla HİÇBİR istek yok ve hata yüzeye çıkmaz
    for (const fn of [api.getCertificateHealth, api.getHistory]) expect(fn.mock.calls.map((c) => c[0])).not.toContain('eski-ad')
    expect(api.monitoring.getCheckHistory.mock.calls.map((c) => c[1])).not.toContain('eski-ad')
    expect(api.monitoring.getCheckHistory.mock.calls.map((c) => c[1])).toContain('yeni-ad')
    expect(dlg).not.toHaveTextContent(ERRORS)
  })

  it('başka bir kayda geçiş (renamedFrom yok) eskisi gibi açılış sekmesine döner', async () => {
    const { rerender } = render(<CertificateModal domain="a-kaydi" {...props} />)
    const dlg = await screen.findByRole('dialog')
    fireEvent.mouseDown(tab(dlg, HEALTH)); fireEvent.click(tab(dlg, HEALTH))
    await waitFor(() => expect(tab(dlg, HEALTH)).toHaveAttribute('aria-selected', 'true'))
    rerender(<CertificateModal domain="b-kaydi" {...props} />)
    await waitFor(() => expect(tab(dlg, SSL)).toHaveAttribute('aria-selected', 'true'))
  })

  it('bayat renamedFrom (önceki yeniden adlandırma) yeni bir kayda geçişi yeniden adlandırma saymaz', async () => {
    const { rerender } = render(<CertificateModal domain="b-ad" renamedFrom="a-ad" {...props} />)
    const dlg = await screen.findByRole('dialog')
    fireEvent.mouseDown(tab(dlg, HEALTH)); fireEvent.click(tab(dlg, HEALTH))
    await waitFor(() => expect(tab(dlg, HEALTH)).toHaveAttribute('aria-selected', 'true'))
    rerender(<CertificateModal domain="c-ad" renamedFrom="a-ad" {...props} />)
    await waitFor(() => expect(tab(dlg, SSL)).toHaveAttribute('aria-selected', 'true'))
  })
})
