import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within, fireEvent, waitFor } from './test-utils.jsx'
import { EN } from '../i18n/en.js'
import { healthyPreview } from './helpers/sslPreviewFixture.js'
import { hierarchyNode, networkHierarchyResponse } from './helpers/certHierarchyFixture.js'

/**
 * Ağdan kontrol edilen sertifikada tarayıcı gibi hiyerarşi (2026-10-12, kullanıcı isteği: "otomatik çekilen sertifikalarda
 * da Root / Intermediate / Leaf net görünsün, her birinin bitiş tarihi ayrı ayrı izlensin; mevcut işlevler bozulmasın"):
 *  • seçici yalnız `networkHierarchyDomain` verildiğinde (pencere, envanter kaydı); varsayılan yine zincir kartları;
 *  • hiyerarşi tek istekle gelir, görünümler arasında gidip gelmek yeniden bağlanmaz; yeni önizleme yeniler;
 *  • her satırda rol rozeti + bitiş tarihi; kök güven deposundan rozeti; özet kartı kök kaynağını söyler;
 *  • kök bulunamadı / fazladan sertifika / yanlış sıra / hata + yeniden dene; adlar kırpılmaz.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({ certHierarchy: vi.fn(), manualCerts: { versionChain: vi.fn() } }),
  formatDate: (s) => `F(${s ?? ''})`,
  formatDateOnly: (s) => String(s ?? ''),
  formatDateSec: (s) => String(s ?? ''),
}))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ perms: {}, canView: () => true, canEdit: () => false, canExecute: () => true, refresh: () => {} }),
}))

import { api } from '../api/client'
import SslCheckerPanel from '../components/SslCheckerPanel.jsx'

const DOMAIN = 'odeme-api.example.test'
const toggle = () => screen.queryByRole('group', { name: EN['chier.viewToggle'] })
const items = () => [...document.querySelectorAll('[data-slot="cert-hierarchy-node"]')]
const openHierarchy = () => fireEvent.click(within(toggle()).getByText(EN['chier.view.hierarchy']))

describe('Ağ sertifikası — Zincir | Hiyerarşi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.certHierarchy.mockResolvedValue(networkHierarchyResponse())
  })

  it('pencere alan adını verince seçici çıkar; varsayılan zincir kartları, istek ATILMAZ', () => {
    render(<SslCheckerPanel data={healthyPreview()} networkHierarchyDomain={DOMAIN} />)
    expect(toggle()).not.toBeNull()
    expect(document.querySelector('[data-slot="ssl-chain"]')).not.toBeNull()
    expect(api.certHierarchy).not.toHaveBeenCalled()
  })

  it('hiyerarşi: kök → ara → yaprak; her satırda rol rozeti ve BİTİŞ TARİHİ; kök "güven deposundan"', async () => {
    render(<SslCheckerPanel data={healthyPreview()} networkHierarchyDomain={DOMAIN} />)
    openHierarchy()
    await waitFor(() => expect(items()).toHaveLength(3))
    // inceleme: önizlemenin başarılı yolu iletilir (fixture: direct + default)
    expect(api.certHierarchy).toHaveBeenCalledWith(DOMAIN, { tlsMode: 'default', via: 'direct' })
    expect(items().map((li) => li.dataset.role)).toEqual(['root', 'intermediate', 'leaf'])
    const roles = items().map((li) => li.querySelector('[data-slot="cert-hierarchy-role"]').textContent)
    expect(roles).toEqual([EN['chier.role.root'], EN['chier.role.intermediate'], EN['chier.role.leaf']])
    const expiries = items().map((li) => li.querySelector('[data-slot="cert-hierarchy-expiry"]').textContent)
    expect(expiries).toEqual([
      EN['chier.expiresOn'].replace('{0}', 'F(2036-10-05T00:00:00)'),
      EN['chier.expiresOn'].replace('{0}', 'F(2026-10-27T00:00:00)'),
      EN['chier.expiresOn'].replace('{0}', 'F(2027-10-19T00:00:00)'),
    ])
    expect(items()[0].querySelector('[data-slot="cert-hierarchy-source"]')).toHaveTextContent(EN['chier.src.trustStoreShort'])
    expect(items()[2].querySelector('[data-slot="cert-hierarchy-source"]')).toBeNull()
    const summary = document.querySelector('[data-slot="cert-hierarchy-summary"]')
    expect(summary).toHaveAttribute('data-root-source', 'trust_store')
    expect(summary).toHaveTextContent(EN['chier.net.why.system'])
    // ayrıntıda kaynak satırı (kök seçilince)
    fireEvent.click(items()[0])
    expect(document.querySelector('[data-slot="cert-hierarchy-details-source"]')).toHaveTextContent(EN['chier.src.store.system'])
    // adlar kırpılmaz
    for (const li of items()) expect(li.innerHTML).not.toContain('truncate')
  })

  it('görünümler arasında gidip gelmek yeniden bağlanmaz; yeni önizleme (Yeniden kontrol) hiyerarşiyi yeniler', async () => {
    const first = healthyPreview()
    const { rerender } = render(<SslCheckerPanel data={first} networkHierarchyDomain={DOMAIN} />)
    openHierarchy()
    await waitFor(() => expect(items()).toHaveLength(3))
    fireEvent.click(within(toggle()).getByText(EN['chier.view.chain']))
    openHierarchy()
    await waitFor(() => expect(items()).toHaveLength(3))
    expect(api.certHierarchy).toHaveBeenCalledTimes(1)
    rerender(<SslCheckerPanel data={healthyPreview()} networkHierarchyDomain={DOMAIN} />)
    await waitFor(() => expect(api.certHierarchy).toHaveBeenCalledTimes(2))
  })

  it('kök bulunamadı: özet "kök bulunamadı", ağacın üstünde sunucu diliyle yer tutucu', async () => {
    const nodes = [
      hierarchyNode({ role: 'intermediate', depth: 0, position: 1, head: false, issuer_missing: true, source: 'server',
        subject: { cn: 'Test Issuing CA' }, subject_dn: 'CN=Test Issuing CA', is_ca: true }),
      hierarchyNode({ depth: 1, source: 'server' }),
    ]
    api.certHierarchy.mockResolvedValue(networkHierarchyResponse({ root_source: null, trust_store: null, root_trusted: false,
      missing_kind: 'root', missing_issuer_dn: 'CN=Test Root CA,O=Example Test' }, nodes))
    render(<SslCheckerPanel data={healthyPreview()} networkHierarchyDomain={DOMAIN} />)
    openHierarchy()
    await waitFor(() => expect(items()).toHaveLength(2))
    expect(document.querySelector('[data-slot="cert-hierarchy-summary"]')).toHaveAttribute('data-root-source', 'missing')
    expect(document.querySelector('[data-slot="cert-hierarchy-root-source"]')).toHaveAttribute('data-tone', 'destructive')
    expect(document.querySelector('[data-slot="cert-hierarchy-missing-root"]')).toHaveTextContent(EN['chier.net.rootMissing'])
    expect(document.querySelector('[data-slot="cert-hierarchy-missing-issuer"]')).toHaveTextContent(EN['chier.expectedIssuer'].replace('{0}', 'Test Root CA'))
  })

  it('inceleme: sunucu yalnız yaprağı gönderdi → "ara sertifika eksik" (kök değil), beklenen veren adıyla', async () => {
    const nodes = [hierarchyNode({ depth: 0, issuer_missing: true, source: 'server' })]
    api.certHierarchy.mockResolvedValue(networkHierarchyResponse({ root_source: null, trust_store: null, root_trusted: false,
      missing_kind: 'intermediate', presented_count: 1, missing_issuer_dn: 'CN=Test Issuing CA,O=Example Test' }, nodes))
    render(<SslCheckerPanel data={healthyPreview()} networkHierarchyDomain={DOMAIN} />)
    openHierarchy()
    await waitFor(() => expect(items()).toHaveLength(1))
    expect(document.querySelector('[data-slot="cert-hierarchy-root-source"]')).toHaveTextContent(EN['chier.net.root.missing_intermediate'])
    expect(document.querySelector('[data-slot="cert-hierarchy-summary"]')).toHaveTextContent(EN['chier.net.why.missingIntermediate'])
    expect(document.querySelector('[data-slot="cert-hierarchy-missing-root"]')).toHaveTextContent(EN['chier.net.intermediateMissing'])
    expect(document.querySelector('[data-slot="cert-hierarchy-missing-issuer"]')).toHaveTextContent('Test Issuing CA')
  })

  it('inceleme: sunucunun gönderdiği ama depoda olmayan kök YEŞİL değil', async () => {
    const nodes = networkHierarchyResponse().data.nodes.map((n) => ({ ...n, source: 'server', trust_store: null }))
    api.certHierarchy.mockResolvedValue(networkHierarchyResponse({ root_source: 'server', trust_store: null, root_trusted: false }, nodes))
    render(<SslCheckerPanel data={healthyPreview()} networkHierarchyDomain={DOMAIN} />)
    openHierarchy()
    await waitFor(() => expect(items()).toHaveLength(3))
    const badge = document.querySelector('[data-slot="cert-hierarchy-root-source"]')
    expect(badge).toHaveAttribute('data-tone', 'warning')
    expect(badge).toHaveTextContent(EN['chier.net.root.server_untrusted'])
  })

  it('inceleme: pencere sekmesi değişip panel yeniden kurulunca aynı önizleme için yeniden bağlanılmaz', async () => {
    const data = healthyPreview()
    const first = render(<SslCheckerPanel data={data} networkHierarchyDomain={DOMAIN} chainView="hierarchy" onChainViewChange={() => {}} />)
    await waitFor(() => expect(items()).toHaveLength(3))
    first.unmount()
    render(<SslCheckerPanel data={data} networkHierarchyDomain={DOMAIN} chainView="hierarchy" onChainViewChange={() => {}} />)
    await waitFor(() => expect(items()).toHaveLength(3))
    expect(api.certHierarchy).toHaveBeenCalledTimes(1)
  })

  it('yanlış sıra uyarısı ve hiyerarşiye girmeyen sertifikalar (katlanır, adlar + bitiş)', async () => {
    api.certHierarchy.mockResolvedValue(networkHierarchyResponse({
      order_ok: false, via: 'proxy',
      extra_certificates: [{ subject_dn: 'CN=Eski Çapraz İmzalı Kök Sertifika Yetkilisi (Test),O=Example Test', not_after: '2025-01-01T00:00:00', days_remaining: -400, expired: true }],
    }))
    render(<SslCheckerPanel data={healthyPreview()} networkHierarchyDomain={DOMAIN} />)
    openHierarchy()
    await waitFor(() => expect(items()).toHaveLength(3))
    expect(document.querySelector('[data-slot="cert-hierarchy-order"]')).toHaveTextContent(EN['chier.net.orderBad'])
    expect(screen.getByText(EN['chier.net.viaProxy'])).toBeInTheDocument()
    const extras = document.querySelector('[data-slot="cert-hierarchy-extras"]')
    fireEvent.click(within(extras).getByRole('button'))
    expect(extras).toHaveTextContent('Eski Çapraz İmzalı Kök Sertifika Yetkilisi (Test)')
    expect(extras).toHaveTextContent(EN['chier.expiresOn'].replace('{0}', 'F(2025-01-01T00:00:00)'))
  })

  it('hata: sunucunun iletisi + "Yeniden dene" yeniden ister', async () => {
    api.certHierarchy.mockResolvedValueOnce({ success: false, code: 'HIERARCHY_UNREACHABLE', error: 'Sunucuya bağlanılamadı (test).', status: 502 })
    render(<SslCheckerPanel data={healthyPreview()} networkHierarchyDomain={DOMAIN} />)
    openHierarchy()
    await screen.findByText('Sunucuya bağlanılamadı (test).')
    expect(screen.getByText(EN['chier.net.loadFailed'])).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: EN['sslv.retry'] }))
    await waitFor(() => expect(items()).toHaveLength(3))
    expect(api.certHierarchy).toHaveBeenCalledTimes(2)
  })

  it('bağlantı hatası olan önizlemede (status=error) seçici yok', () => {
    render(<SslCheckerPanel data={healthyPreview({ status: 'error', error: 'timeout' })} networkHierarchyDomain={DOMAIN} />)
    expect(toggle()).toBeNull()
    expect(api.certHierarchy).not.toHaveBeenCalled()
  })
})
