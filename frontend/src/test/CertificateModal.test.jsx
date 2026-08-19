import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from './test-utils.jsx'

/**
 * CertificateModal does live API fetches in useEffect. We mock the entire
 * client module so the modal can render without hitting the network. The
 * goal is smoke coverage: a non-null domain renders the chrome (close
 * button, tab bar, status pill); a null domain renders nothing.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  // Kontrol Geçmişi sekmesi (paylaşılan CheckHistoryTab) bu iki biçimleyiciyi de import ediyor.
  formatDateSec:  (s) => String(s ?? ''),
  formatDateOnly: (s) => String(s ?? ''),
  api: withApiFallback({
    getHistory:           vi.fn().mockResolvedValue({ success: true, data: [] }),
    checkDomainPreview:   vi.fn().mockResolvedValue({ success: true, data: null }),
    getDomainAlerts:      vi.fn().mockResolvedValue({ success: true, data: [] }),
    monitoring: {
      getCheckHistory: vi.fn().mockResolvedValue({ success: true, data: {
        items: [{ checked_at: '2026-08-10T09:00:00', status: 'valid', days_remaining: 42, error: null }],
        page: 0, size: 50, total: 1, counts: { total: 1, fail: 0 },
        range: { from: '2026-08-01T00:00:00', to: '2026-08-15T00:00:00' },
        retention_days: 180, buckets: [], alerts: [],
      } }),
      getCheckHistoryCsvUrl: vi.fn(() => '/csv'),
      getSslResponseSeries:  vi.fn().mockResolvedValue({ success: true, data: { series: [], bucket: 'hour', unit: 'ms' } }),
    },
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
      getInventoryByDomain: vi.fn().mockResolvedValue({ success: true, data: {
        domain: 'example.com', port: 443, tier: 2, team_name: 'Team X', tls_mode: '',
        purchased_by: 'ACME-Buyer', external_vendor: true, action_required: false,
        openshift: false, ssl_pinning: false, internal_cert: false, jks_keystore: false,
        server_update: false, netscaler: false, waf_enabled: false, in_use: true,
        ev_certificate: false, transferred_to_sy: false, use_proxy: false,
        change_description: '', expected_fingerprint: '', expected_subject: '',
        created_at: '2026-01-01', updated_at: '2026-01-02',
      } }),
    },
  }),
}))

// canView('inventory.list') → true so the new Envanter Bilgileri tab is present.
// (Without a PermissionsProvider it is null-safe/false, so the other tests are unaffected.)
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ canView: () => true, canEdit: () => true, canExecute: () => true, perms: {} }),
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

  it('shows the Inventory Info tab and its content when the user can view inventory', async () => {
    render(
      <CertificateModal
        domain="example.com"
        onClose={() => {}}
        currentUser="admin"
        currentUserRole="ADMIN"
      />
    )
    const invTab = await screen.findByRole('button', { name: 'Inventory Info' })
    invTab.click()
    // A language-independent field value from the mocked inventory record.
    expect(await screen.findByText('ACME-Buyer')).toBeDefined()
  })

})

/**
 * Kontrol Geçmişi + Grafik sekmeleri — diğer sekiz izleme türüyle aynı paylaşılan bileşenler.
 * jsdom yerleşim hesaplamadığı için grafiğin ÇİZİMİ doğrulanmaz (ResponsiveContainer genişliği 0);
 * doğrulanan şey doğru uca doğru parametrelerle gidilmesi ve sekmelerin görünürlük kuralı.
 */
describe('CertificateModal — Kontrol Geçmişi + Grafik', () => {
  beforeEach(() => { vi.clearAllMocks() })

  const openModal = (extra = {}) => render(
    <CertificateModal domain="example.com" onClose={() => {}}
      currentUser="admin" currentUserRole="ADMIN" {...extra} />
  )

  it('Kontrol Geçmişi sekmesi uptime-ssl ucunu domain ile çağırır ve kalan günü satırda basar', async () => {
    const { api } = await import('../api/client')
    openModal()

    const histTab = await screen.findByRole('button', { name: 'Check History' })
    histTab.click()

    await waitFor(() => expect(api.monitoring.getCheckHistory).toHaveBeenCalled())
    const [kind, id] = api.monitoring.getCheckHistory.mock.calls[0]
    expect(kind).toBe('uptime-ssl')
    expect(id).toBe('example.com')            // cert domain-anahtarlı: monitorId = domain
    expect(await screen.findByText(/42/)).toBeDefined()   // days_remaining hücresi
  })

  it('Grafik sekmesi sertifika seri ucunu çağırır (keyword fallback\'ine düşmez)', async () => {
    const { api } = await import('../api/client')
    openModal()

    const chartTab = await screen.findByRole('button', { name: 'Certificate Chart' })
    chartTab.click()

    // Grafik lazy() ile yükleniyor ve recharts ağır: tam süit altında varsayılan 1 sn'lik
    // waitFor penceresi yetişmiyordu (tek dosya koşumunda geçiyordu). Bekleme buna göre.
    await waitFor(() => expect(api.monitoring.getSslResponseSeries).toHaveBeenCalled(), { timeout: 8000 })
    expect(api.monitoring.getSslResponseSeries.mock.calls[0][0]).toBe('example.com')
  })

  it('previewMode: iki sekme de gizli (önizlenen domain envanterde olmayabilir → uçlar 404 döner)', async () => {
    openModal({ previewMode: true, initialData: { domain: 'example.com', status: 'valid' } })

    // previewMode'da domain hem başlıkta hem SSL panelinde geçiyor → findAllByText.
    await screen.findAllByText('example.com')
    expect(screen.queryByRole('button', { name: 'Check History' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Certificate Chart' })).toBeNull()
  })
})
