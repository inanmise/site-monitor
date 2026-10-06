import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'

/**
 * Sertifika penceresi — manuel (dosyadan yüklenen) kayıt (2026-10-06): canlı SSL sekmesi YOK, açılış Detaylar, tanılama
 * yok, "Çalıştır" = "Yeniden değerlendir", "Sürümler" sekmesi (güncel + önceki sürümler, PEM indir, yeni sürüm yükle →
 * sihirbaz yenileme kipinde). Satır bilgisi olmadan açılınca canlı kontrolün 409 MANUAL_CERT yanıtından anlaşılır.
 * Ağ kaydı birebir aynı (SSL sekmesi ilk, canlı kontrol koşar).
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  formatDateSec: (s) => String(s ?? ''),
  formatDateOnly: (s) => String(s ?? ''),
  formatTime: (s) => String(s ?? ''),
  api: withApiFallback({
    getHistory: vi.fn().mockResolvedValue({ success: true, data: [{ domain: 'keystore.example.test', status: 'valid', days_remaining: 80, san: [] }] }),
    checkDomainPreview: vi.fn().mockResolvedValue({ success: true, data: null }),
    getDomainAlerts: vi.fn().mockResolvedValue({ success: true, data: [] }),
    manualCerts: {
      get: vi.fn(),
      pemUrl: vi.fn((id, v) => `/api/manual-certs/${id}/versions/${v}/pem`),
    },
    admin: {
      getNotes: vi.fn().mockResolvedValue({ success: true, data: [] }),
      getInventoryByDomain: vi.fn().mockResolvedValue({ success: true, data: { id: 77, domain: 'keystore.example.test', cert_source: 'MANUAL', manual_version: 3, can_manage: true } }),
    },
  }),
}))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ canView: () => true, canEdit: () => true, canExecute: () => true, perms: {} }),
}))

import { api } from '../api/client'
import CertificateModal from '../components/CertificateModal.jsx'

const VERSIONS = [
  { id: 13, version: 3, current: true, fingerprint: 'F3F3', subject: 'CN=keystore.example.test', subject_dn: 'CN=keystore.example.test,O=Example',
    issuer: 'Example CA', not_before: '2026-09-01T00:00:00', not_after: '2027-09-01T00:00:00', san: ['keystore.example.test', 'new.example.test'],
    key_alg: 'RSA', key_size: 3072, signature_algorithm: 'SHA256withRSA', chain_count: 3, file_name: 'store.jks', file_format: 'JKS',
    source_alias: 'server', uploaded_by_name: 'Kişi A', uploaded_at: '2026-09-30T08:00:00', note: 'TALEP-1', key_changed: true,
    san_added: ['new.example.test'], san_removed: ['old.example.test'] },
  { id: 12, version: 2, current: false, fingerprint: 'F2F2', subject: 'CN=keystore.example.test', issuer: 'Example CA',
    not_before: '2025-09-01T00:00:00', not_after: '2026-10-01T00:00:00', san: ['keystore.example.test', 'old.example.test'],
    key_alg: 'RSA', key_size: 2048, file_name: 'server.pem', file_format: 'PEM', uploaded_by_name: 'Kişi B', uploaded_at: '2025-09-02T08:00:00',
    superseded_at: '2026-09-30T08:00:00', superseded_by: 'kisia', key_changed: false, san_added: [], san_removed: [] },
]

const tabNames = (dlg) => within(dlg).getAllByRole('tab').map((t) => t.getAttribute('id').replace(/^.*-trigger-/, ''))

describe('CertificateModal — manuel kayıt', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.manualCerts.get.mockResolvedValue({ success: true, data: { inventory_id: 77, can_manage: true, versions: VERSIONS } })
  })

  it('SSL sekmesi yok, Detaylar açık, Sürümler var; canlı kontrol yok; tanılama yok; "Yeniden değerlendir"; başlıkta Manuel rozeti', async () => {
    render(<CertificateModal domain="keystore.example.test" manual manualMeta={{ version: 3, uploadedAt: '2026-09-30T08:00:00' }}
      onClose={() => {}} currentUser="admin" currentUserRole="ADMIN" onCheckNow={vi.fn()} onEdit={vi.fn()} />)
    const dlg = await screen.findByRole('dialog')
    const names = tabNames(dlg)
    expect(names).not.toContain('ssl')
    expect(names).toContain('versions')
    expect(names.indexOf('versions')).toBe(names.indexOf('details') + 1)
    expect(within(dlg).getByRole('tab', { name: /details|detay/i })).toHaveAttribute('aria-selected', 'true')
    expect(api.checkDomainPreview).not.toHaveBeenCalled()
    expect(dlg.querySelector('[data-slot="cert-diagnose"]')).toBeNull()
    expect(within(dlg).getByRole('button', { name: /^(Re-evaluate|Yeniden değerlendir)$/ })).toBeInTheDocument()
    expect(dlg.querySelector('[data-slot="cert-modal-title"] [data-slot="manual-cert-badge"]')).toHaveAttribute('data-version', '3')
  })

  it('Sürümler: güncel kart vurgulu + önceki sürüm (kim/ne zaman yenilendi), anahtar ve SAN farkı, sürüm başına PEM; yeni sürüm → sihirbaz', async () => {
    render(<CertificateModal domain="keystore.example.test" manual onClose={() => {}} currentUser="admin" currentUserRole="ADMIN" initialTab="versions" />)
    const dlg = await screen.findByRole('dialog')
    const list = await waitFor(() => { const el = dlg.querySelector('[data-slot="mcert-versions"]'); if (!el) throw new Error('yok'); return el }, { timeout: 8000 })
    expect(api.admin.getInventoryByDomain).toHaveBeenCalledWith('keystore.example.test')
    expect(api.manualCerts.get).toHaveBeenCalledWith(77)
    const cards = list.querySelectorAll('[data-slot="mcert-version"]')
    expect([...cards].map((c) => c.getAttribute('data-version'))).toEqual(['3', '2'])
    expect(cards[0]).toHaveAttribute('data-current', 'true')
    expect(cards[0].querySelector('[data-slot="mcert-key-changed"]')).toBeTruthy()
    expect(cards[0]).toHaveTextContent('+ new.example.test')
    expect(cards[0]).toHaveTextContent('− old.example.test')
    expect(cards[0]).toHaveTextContent('F3F3')
    expect(cards[1]).toHaveTextContent(/Replaced on .* by kisia|kisia tarafından yenilendi/)
    expect(cards[1].querySelector('[data-slot="mcert-key-same"]')).toBeTruthy()
    expect([...list.querySelectorAll('[data-slot="mcert-pem"]')].map((a) => a.getAttribute('href')))
      .toEqual(['/api/manual-certs/77/versions/13/pem', '/api/manual-certs/77/versions/12/pem'])
    fireEvent.click(within(list).getByRole('button', { name: /^(Upload new version|Yeni sürüm yükle)$/ }))
    expect(await screen.findByText(/Upload new version: keystore\.example\.test|Yeni sürüm yükle: keystore\.example\.test/)).toBeInTheDocument()
  })

  it('salt okunur pencerede "Yeni sürüm yükle" yok', async () => {
    render(<CertificateModal domain="keystore.example.test" manual readOnly readOnlyTeam={{ id: 2, name: 'Takım B' }}
      onClose={() => {}} currentUser="admin" currentUserRole="ADMIN" initialTab="versions" />)
    const dlg = await screen.findByRole('dialog')
    await waitFor(() => expect(dlg.querySelector('[data-slot="mcert-version"]')).toBeTruthy(), { timeout: 8000 })
    expect(dlg.querySelector('[data-slot="mcert-renew-btn"]')).toBeNull()
  })

  it('satır bilgisi yokken (derin bağlantı): canlı kontrol 409 MANUAL_CERT → manuel kipe geçer, hata çizilmez', async () => {
    api.checkDomainPreview.mockResolvedValueOnce({ success: false, code: 'MANUAL_CERT', error: 'manuel' })
    render(<CertificateModal domain="keystore.example.test" onClose={() => {}} currentUser="admin" currentUserRole="ADMIN" />)
    const dlg = await screen.findByRole('dialog')
    await waitFor(() => expect(tabNames(dlg)).toContain('versions'))
    expect(tabNames(dlg)).not.toContain('ssl')
    expect(within(dlg).getByRole('tab', { name: /details|detay/i })).toHaveAttribute('aria-selected', 'true')
    expect(within(dlg).queryByText('manuel')).toBeNull()
  })

  it('ağ kaydı DEĞİŞMEZ: SSL sekmesi ilk ve açık, canlı kontrol koşar, Sürümler yok', async () => {
    api.getHistory.mockResolvedValueOnce({ success: true, data: [{ domain: 'net.example.test', status: 'valid', days_remaining: 80, san: [] }] })
    render(<CertificateModal domain="net.example.test" onClose={() => {}} currentUser="admin" currentUserRole="ADMIN" onCheckNow={vi.fn()} />)
    const dlg = await screen.findByRole('dialog')
    expect(tabNames(dlg)[0]).toBe('ssl')
    expect(tabNames(dlg)).not.toContain('versions')
    await waitFor(() => expect(api.checkDomainPreview).toHaveBeenCalledWith('net.example.test'))
    expect(within(dlg).getByRole('button', { name: /^(Run|Çalıştır)$/ })).toBeInTheDocument()
    expect(dlg.querySelector('[data-slot="manual-cert-badge"]')).toBeNull()
  })
})
