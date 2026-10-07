import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'

/**
 * Sertifika penceresi — manuel (dosyadan yüklenen) kayıt. 2026-10-06: tanılama yok, "Çalıştır" = "Yeniden değerlendir",
 * "Sürümler" sekmesi (güncel + önceki sürümler, PEM indir, yeni sürüm yükle → sihirbaz yenileme kipinde).
 * 2026-10-07: SSL sekmesi manuelde de VAR ve açılış sekmesidir — `/check-preview` çevrim-dışı sonucu (`via: 'upload'`)
 * aynı panelle (bağlantı grubu yok) ve aynı zincir kartlarıyla çizilir. Satır bilgisi olmadan açılınca önizlemenin
 * `via`'sından (ya da değerlendirilemezse 409 MANUAL_CERT'ten) anlaşılır. Ağ kaydı birebir aynı.
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
import { uploadPreview } from './helpers/sslPreviewFixture.js'

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

  it('SSL sekmesi İLK ve açık: çevrim-dışı önizleme, aynı zincir kartları (yaprak → ara → kök), bağlantı grubu yok; Sürümler var; tanılama yok', async () => {
    api.checkDomainPreview.mockResolvedValueOnce({ success: true, data: uploadPreview({ domain: 'keystore.example.test' }) })
    render(<CertificateModal domain="keystore.example.test" manual manualMeta={{ version: 3, uploadedAt: '2026-09-30T08:00:00' }}
      onClose={() => {}} currentUser="admin" currentUserRole="ADMIN" onCheckNow={vi.fn()} onEdit={vi.fn()} />)
    const dlg = await screen.findByRole('dialog')
    const names = tabNames(dlg)
    expect(names[0]).toBe('ssl')
    expect(names).toContain('versions')
    expect(names.indexOf('versions')).toBe(names.indexOf('details') + 1)
    // Yanıt süresi grafiği manuelde yok (ağ yok) → şerit 9 sekmede kalır (1200 px'e sığar)
    expect(names).not.toContain('chart')
    expect(names).toHaveLength(9)
    expect(within(dlg).getByRole('tab', { name: /ssl/i })).toHaveAttribute('aria-selected', 'true')
    await waitFor(() => expect(api.checkDomainPreview).toHaveBeenCalledWith('keystore.example.test'))
    const panel = await waitFor(() => { const el = dlg.querySelector('[data-slot="ssl-panel"]'); if (!el) throw new Error('yok'); return el })
    expect(panel).toHaveAttribute('data-source', 'upload')
    expect([...panel.querySelectorAll('[data-slot="ssl-chain-node"]')].map((n) => n.dataset.role)).toEqual(['leaf', 'intermediate', 'root'])
    expect([...panel.querySelectorAll('[data-slot="ssl-check-group"]')].map((g) => g.dataset.group)).toEqual(['cert', 'trust'])
    expect(panel.querySelector('[data-slot="ssl-check"][data-check="hostname"]')).toBeNull()
    expect(panel).not.toHaveTextContent(/Live check|Canlı kontrol/)
    expect(dlg.querySelector('[data-slot="cert-diagnose"]')).toBeNull()
    // Başlıktaki kalıcı değerlendirme + panelin önizleme düğmesi: ikisi de "Yeniden değerlendir"
    const actions = dlg.querySelector('[data-slot="cert-modal-actions"]')
    expect(within(actions).getByRole('button', { name: /^(Re-evaluate|Yeniden değerlendir)$/ })).toBeInTheDocument()
    expect(within(panel).getByRole('button', { name: /^(Re-evaluate|Yeniden değerlendir)$/ })).toBeInTheDocument()
    expect(dlg.querySelector('[data-slot="cert-modal-title"] [data-slot="manual-cert-badge"]')).toHaveAttribute('data-version', '3')
    // Panelin "Yeniden değerlendir"i önizlemeyi yeniden ister (kayıt yazmaz)
    api.checkDomainPreview.mockResolvedValueOnce({ success: true, data: uploadPreview({ domain: 'keystore.example.test' }) })
    fireEvent.click(within(panel).getByRole('button', { name: /^(Re-evaluate|Yeniden değerlendir)$/ }))
    await waitFor(() => expect(api.checkDomainPreview).toHaveBeenCalledTimes(2))
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

  it('satır bilgisi yokken (derin bağlantı): önizleme via=upload → manuel kip (Sürümler, rozet sürümü); SSL sekmesi açık kalır', async () => {
    api.getHistory.mockResolvedValueOnce({ success: true, data: [] })
    api.checkDomainPreview.mockResolvedValueOnce({ success: true, data: uploadPreview({ domain: 'keystore.example.test', manual_version: 5 }) })
    render(<CertificateModal domain="keystore.example.test" onClose={() => {}} currentUser="admin" currentUserRole="ADMIN" />)
    const dlg = await screen.findByRole('dialog')
    await waitFor(() => expect(tabNames(dlg)).toContain('versions'))
    expect(tabNames(dlg)[0]).toBe('ssl')
    expect(within(dlg).getByRole('tab', { name: /ssl/i })).toHaveAttribute('aria-selected', 'true')
    expect(dlg.querySelector('[data-slot="ssl-panel"]')).toHaveAttribute('data-source', 'upload')
    expect(dlg.querySelector('[data-slot="cert-modal-title"] [data-slot="manual-cert-badge"]')).toHaveAttribute('data-version', '5')
  })

  it('geçerli sürüm değerlendirilemezse (409 MANUAL_CERT): manuel kip + SSL sekmesinde sunucu iletisi ve "Yeniden dene"', async () => {
    api.getHistory.mockResolvedValueOnce({ success: true, data: [] })
    api.checkDomainPreview.mockResolvedValueOnce({ success: false, code: 'MANUAL_CERT', error: 'Geçerli sürüm değerlendirilemedi (sunucu)' })
    render(<CertificateModal domain="keystore.example.test" onClose={() => {}} currentUser="admin" currentUserRole="ADMIN" />)
    const dlg = await screen.findByRole('dialog')
    await waitFor(() => expect(tabNames(dlg)).toContain('versions'))
    expect(await within(dlg).findByText('Geçerli sürüm değerlendirilemedi (sunucu)')).toBeInTheDocument()
    expect(within(dlg).getByText(/The evaluation failed|Couldn't run the evaluation|Değerlendirme yapılamadı/)).toBeInTheDocument()
    api.checkDomainPreview.mockResolvedValueOnce({ success: true, data: uploadPreview({ domain: 'keystore.example.test' }) })
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Try again|Retry|Yeniden dene)$/ }))
    await waitFor(() => expect(dlg.querySelector('[data-slot="ssl-panel"]')).not.toBeNull())
  })

  it('ağ kaydı DEĞİŞMEZ: SSL sekmesi ilk ve açık, canlı kontrol koşar, Sürümler yok', async () => {
    api.getHistory.mockResolvedValueOnce({ success: true, data: [{ domain: 'net.example.test', status: 'valid', days_remaining: 80, san: [] }] })
    render(<CertificateModal domain="net.example.test" onClose={() => {}} currentUser="admin" currentUserRole="ADMIN" onCheckNow={vi.fn()} />)
    const dlg = await screen.findByRole('dialog')
    expect(tabNames(dlg)[0]).toBe('ssl')
    expect(tabNames(dlg)).not.toContain('versions')
    expect(tabNames(dlg)).toContain('chart')
    await waitFor(() => expect(api.checkDomainPreview).toHaveBeenCalledWith('net.example.test'))
    expect(within(dlg).getByRole('button', { name: /^(Run|Çalıştır)$/ })).toBeInTheDocument()
    expect(dlg.querySelector('[data-slot="manual-cert-badge"]')).toBeNull()
  })
})
