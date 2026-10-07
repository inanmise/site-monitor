import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within, fireEvent, waitFor } from './test-utils.jsx'
import { healthyPreview, uploadPreview } from './helpers/sslPreviewFixture.js'
import { chainResponse, fullChainNodes, leafOnlyNodes } from './helpers/certHierarchyFixture.js'

/**
 * Tarayıcı gibi sertifika hiyerarşisinin GİRİŞ NOKTALARI (2026-10-07, yalnız manuel kayıtlar):
 *  • SSL sekmesi (SslCheckerPanel, `via: 'upload'`): "Chain" (varsayılan, SslChainView) | "Hierarchy (browser style)"
 *    seçici; hiyerarşi güncel sürümün zincirini önizlemenin kimlikleriyle tek istekte çeker (eksikse alan adından bulur).
 *    Ağ satırında seçici YOK.
 *  • Sürümler sekmesi: her sürüm kartında "View" → o sürümün hiyerarşisi iç içe pencerede.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({
    admin: { getInventoryByDomain: vi.fn() },
    manualCerts: {
      get: vi.fn(), versionChain: vi.fn(), deleteVersion: vi.fn(),
      pemUrl: (id, v) => `/api/manual-certs/${id}/versions/${v}/pem`,
    },
  }),
  formatDate: (s) => String(s ?? ''),
  formatDateOnly: (s) => String(s ?? ''),
  formatDateSec: (s) => String(s ?? ''),
}))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ perms: {}, canView: () => true, canEdit: () => false, canExecute: () => true, refresh: () => {} }),
}))

import { api } from '../api/client'
import SslCheckerPanel from '../components/SslCheckerPanel.jsx'
import ManualCertVersions from '../components/manualcert/ManualCertVersions.jsx'

const toggle = () => screen.queryByRole('group', { name: 'Chain view' })

describe('SSL sekmesi — Zincir | Hiyerarşi seçici', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.manualCerts.versionChain.mockResolvedValue(chainResponse())
  })

  it('ağ satırı: seçici YOK, yalnız zincir kartları; hiyerarşi isteği atılmaz', () => {
    render(<SslCheckerPanel data={healthyPreview()} />)
    expect(toggle()).toBeNull()
    expect(document.querySelector('[data-slot="ssl-chain"]')).not.toBeNull()
    expect(api.manualCerts.versionChain).not.toHaveBeenCalled()
  })

  it('manuel: varsayılan "Chain"; "Hierarchy" → güncel sürümün zinciri TEK istekle (kayıt + sürüm kimliği) → kök → ara → yaprak', async () => {
    render(<SslCheckerPanel data={uploadPreview({ inventory_id: 7, manual_version_id: 102 })} />)
    const group = toggle()
    expect(group).not.toBeNull()
    expect(within(group).getByRole('button', { name: 'Chain' })).toHaveAttribute('aria-pressed', 'true')
    expect(document.querySelector('[data-slot="ssl-chain"]')).not.toBeNull()
    expect(api.manualCerts.versionChain).not.toHaveBeenCalled()   // istem üzerine

    fireEvent.click(within(group).getByRole('button', { name: 'Hierarchy (browser style)' }))
    const tree = await screen.findByRole('tree')
    expect(within(tree).getAllByRole('treeitem').map((li) => li.dataset.role)).toEqual(['root', 'intermediate', 'leaf'])
    expect(api.manualCerts.versionChain).toHaveBeenCalledWith(7, 102)
    expect(api.admin.getInventoryByDomain).not.toHaveBeenCalled()
    expect(document.querySelector('[data-slot="ssl-chain"]')).toBeNull()
    expect(within(toggle()).getByRole('button', { name: 'Hierarchy (browser style)' })).toHaveAttribute('aria-pressed', 'true')
    // seçili yaprağın ayrıntısında SAN
    expect(within(document.querySelector('[data-slot="cert-hierarchy-details"]')).getByText('odeme-api-internal.example.test')).toBeInTheDocument()

    fireEvent.click(within(toggle()).getByRole('button', { name: 'Chain' }))
    expect(screen.queryByRole('tree')).toBeNull()
    expect(document.querySelector('[data-slot="ssl-chain"]')).not.toBeNull()
  })

  it('önizlemede kimlik yoksa kayıt alan adından bulunur, güncel sürüm ayrıntıdan okunur', async () => {
    api.admin.getInventoryByDomain.mockResolvedValue({ success: true, data: { id: 9, domain: 'odeme-api.example.test' } })
    api.manualCerts.get.mockResolvedValue({ success: true, data: { current_version: { id: 55, version: 4 } } })
    render(<SslCheckerPanel data={uploadPreview()} chainView="hierarchy" onChainViewChange={() => {}} />)
    await screen.findByRole('tree')
    expect(api.admin.getInventoryByDomain).toHaveBeenCalledWith('odeme-api.example.test')
    expect(api.manualCerts.get).toHaveBeenCalledWith(9)
    expect(api.manualCerts.versionChain).toHaveBeenCalledWith(9, 55)
  })

  it('denetimli kullanım: seçim dışarıda tutulur (pencere hatırlar) — tıklama onChainViewChange\'i çağırır', async () => {
    const onChange = vi.fn()
    render(<SslCheckerPanel data={uploadPreview({ inventory_id: 7, manual_version_id: 102 })} chainView="hierarchy" onChainViewChange={onChange} />)
    await screen.findByRole('tree')
    fireEvent.click(within(toggle()).getByRole('button', { name: 'Chain' }))
    expect(onChange).toHaveBeenCalledWith('chain')
  })

  it('yükleme hatası: uyarı + "Try again" yeniden ister', async () => {
    api.manualCerts.versionChain
      .mockResolvedValueOnce({ success: false, status: 422, code: 'CHAIN_UNREADABLE', error: 'Zincir okunamadı' })
      .mockResolvedValueOnce(chainResponse(leafOnlyNodes()))
    render(<SslCheckerPanel data={uploadPreview({ inventory_id: 7, manual_version_id: 102 })} chainView="hierarchy" onChainViewChange={() => {}} />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent("The certificate hierarchy couldn't be loaded")
    expect(alert).toHaveTextContent('Zincir okunamadı')
    fireEvent.click(within(alert).getByRole('button', { name: 'Try again' }))
    await screen.findByRole('tree')
    expect(api.manualCerts.versionChain).toHaveBeenCalledTimes(2)
    expect(document.querySelector('[data-slot="cert-hierarchy-missing-root"]')).not.toBeNull()
  })
})

const ver = (n, current) => ({
  id: 100 + n, version: n, current, fingerprint: `FP${n}`, subject: 'CN=odeme-api.example.test', issuer: 'CN=Test Issuing CA',
  not_before: '2026-10-01T00:00:00', not_after: '2027-11-08T00:00:00', file_name: 'chain.pem', file_format: 'PEM',
  uploaded_by_name: 'Operatör', uploaded_at: '2026-10-07T06:00:00', key_changed: null, san_added: [], san_removed: [],
})

describe('Sürümler sekmesi — "View"', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getInventoryByDomain.mockResolvedValue({ success: true, data: { id: 7, domain: 'odeme-api.example.test', can_manage: false } })
    api.manualCerts.get.mockResolvedValue({ success: true, data: { can_manage: false, versions: [ver(2, true), ver(1, false)] } })
    api.manualCerts.versionChain.mockResolvedValue(chainResponse(fullChainNodes(), { version_id: 101, version: 1, current: false }))
  })

  it('her sürümde "View" (salt okunurda da); eski sürüm → iç içe pencerede O sürümün hiyerarşisi, başlıkta sürüm + rozet', async () => {
    render(<ManualCertVersions domain="odeme-api.example.test" readOnly />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="mcert-version"]')).toHaveLength(2))
    expect(document.querySelectorAll('[data-slot="mcert-version-view"]')).toHaveLength(2)
    fireEvent.click(screen.getByRole('button', { name: 'v1 — View the certificate hierarchy' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByRole('heading', { level: 2 })).toHaveTextContent('Version v1 — certificate hierarchy')
    expect(within(dialog).getByRole('heading', { level: 2 })).toHaveTextContent('Previous version')
    const tree = await within(dialog).findByRole('tree')
    expect(within(tree).getAllByRole('treeitem')).toHaveLength(3)
    expect(api.manualCerts.versionChain).toHaveBeenCalledWith(7, 101)

    fireEvent.click(within(dialog).getByRole('button', { name: 'Close' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('güncel sürüm → başlıkta "Current" rozeti, istek güncel sürüm kimliğiyle', async () => {
    render(<ManualCertVersions domain="odeme-api.example.test" />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="mcert-version"]')).toHaveLength(2))
    fireEvent.click(screen.getByRole('button', { name: 'v2 — View the certificate hierarchy' }))
    const dialog = await screen.findByRole('dialog')
    expect(dialog.querySelector('[data-slot="mcert-view-badge"]')).toHaveAttribute('data-current', 'true')
    expect(dialog.querySelector('[data-slot="mcert-view-badge"]')).toHaveTextContent('Current')
    await within(dialog).findByRole('tree')
    expect(api.manualCerts.versionChain).toHaveBeenCalledWith(7, 102)
  })
})
