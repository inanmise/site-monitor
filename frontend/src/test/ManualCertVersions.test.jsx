import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useState } from 'react'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

/**
 * Sertifika penceresi → "Sürümler" sekmesi (2026-10-06) — yeni sürüm kaydedildikten sonra sihirbaz AÇIK kalır ve sonuç
 * adımı görünür (canlı e2e bulgusu, 2026-10-07). Eskiden `onDone` → `load()` önce "yükleniyor" durumuna geçiyordu; bileşen
 * erken dönüşle sihirbazı söküyor, `wizard` açık kaldığı için sihirbaz sıfırdan (1. adım) yeniden açılıyordu — kullanıcı
 * sonucu hiç görmüyor, boş "Yeni sürüm yükle / Dosya" penceresiyle kalıyordu. Liste arkada SESSİZCE tazelenmeli.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const perm = vi.hoisted(() => ({ edit: true }))
vi.mock('../api/client', () => ({
  api: withApiFallback({
    admin: { getInventoryByDomain: vi.fn() },
    manualCerts: { get: vi.fn(), deleteVersion: vi.fn(), pemUrl: (id, v) => `/api/manual-certs/${id}/versions/${v}/pem` },
  }),
  formatDate: (s) => String(s ?? ''),
  formatDateOnly: (s) => String(s ?? ''),
  formatDateSec: (s) => String(s ?? ''),
}))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ perms: { 'inventory.crud': { edit: perm.edit } }, canView: () => true, canEdit: () => perm.edit, canExecute: () => true, refresh: () => {} }),
}))
// Sihirbaz: kendi iç durumu (adım) olan saplama — yeniden bağlanırsa adım "file"a döner, bu da hatanın imzası
vi.mock('../components/manualcert/UploadWizard.jsx', () => ({
  default: function StubWizard({ onDone, onClose }) {
    const [step, setStep] = useState('file')
    return (
      <div data-testid="wizard" data-step={step}>
        <button type="button" onClick={() => { setStep('result'); onDone?.({ kind: 'renewed' }) }}>kaydet</button>
        <button type="button" onClick={onClose}>kapat</button>
      </div>
    )
  },
}))

import { api } from '../api/client'
import ManualCertVersions from '../components/manualcert/ManualCertVersions.jsx'

const ver = (n, current) => ({
  id: 100 + n, version: n, current, fingerprint: `FP${n}`, subject: 'CN=keystore.example.test', issuer: 'CN=Example CA',
  not_before: '2026-10-01T00:00:00', not_after: n === 1 ? '2026-10-27T00:00:00' : '2027-11-08T00:00:00',
  file_name: n === 1 ? 'chain.pem' : 'leaf.pfx', file_format: n === 1 ? 'PEM' : 'PKCS12', uploaded_by_name: 'Operatör',
  uploaded_at: '2026-10-07T06:00:00', key_changed: n === 1 ? null : false, san_added: [], san_removed: [],
})

describe('ManualCertVersions — yeni sürüm sonrası sihirbaz', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getInventoryByDomain.mockResolvedValue({ success: true, data: { id: 7, domain: 'keystore.example.test', can_manage: true } })
    api.manualCerts.get
      .mockResolvedValueOnce({ success: true, data: { can_manage: true, versions: [ver(1, true)] } })
      .mockResolvedValue({ success: true, data: { can_manage: true, versions: [ver(2, true), ver(1, false)] } })
  })

  it('kaydet → liste sessizce 2 sürüme tazelenir, sihirbaz SÖKÜLMEZ (sonuç adımında kalır)', async () => {
    const onRenewed = vi.fn()
    render(<ManualCertVersions domain="keystore.example.test" onRenewed={onRenewed} />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="mcert-version"]')).toHaveLength(1))
    fireEvent.click(document.querySelector('[data-slot="mcert-renew-btn"]'))
    const wizard = await screen.findByTestId('wizard')
    expect(wizard).toHaveAttribute('data-step', 'file')

    fireEvent.click(screen.getByRole('button', { name: 'kaydet' }))
    await waitFor(() => expect(document.querySelectorAll('[data-slot="mcert-version"]')).toHaveLength(2))
    expect(api.manualCerts.get).toHaveBeenCalledTimes(2)
    expect(onRenewed).toHaveBeenCalledTimes(1)
    // aynı sihirbaz örneği: sonuç adımı korunur (yeniden bağlansaydı "file" olurdu)
    expect(screen.getByTestId('wizard')).toBe(wizard)
    expect(screen.getByTestId('wizard')).toHaveAttribute('data-step', 'result')
    expect(document.querySelector('[data-slot="mcert-version"][data-current="true"]')).toHaveAttribute('data-version', '2')
    // tazeleme sırasında "yükleniyor" bloğuna hiç geçilmedi → sürümler kaybolmadı
    expect(document.querySelector('[data-slot="mcert-versions"]')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'kapat' }))
    expect(screen.queryByTestId('wizard')).toBeNull()
  })

  it('sessiz tazeleme başarısızsa eldeki liste ve sihirbaz yerinde kalır', async () => {
    api.manualCerts.get.mockReset()
    api.manualCerts.get
      .mockResolvedValueOnce({ success: true, data: { can_manage: true, versions: [ver(1, true)] } })
      .mockResolvedValue({ success: false, error: 'geçici hata' })
    render(<ManualCertVersions domain="keystore.example.test" />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="mcert-version"]')).toHaveLength(1))
    fireEvent.click(document.querySelector('[data-slot="mcert-renew-btn"]'))
    fireEvent.click(await screen.findByRole('button', { name: 'kaydet' }))
    await waitFor(() => expect(api.manualCerts.get).toHaveBeenCalledTimes(2))
    expect(screen.getByTestId('wizard')).toHaveAttribute('data-step', 'result')
    expect(document.querySelectorAll('[data-slot="mcert-version"]')).toHaveLength(1)
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('ilk yükleme hatası: hata bloğu + "Yeniden dene" tam (sessiz olmayan) yükleme yapar', async () => {
    api.manualCerts.get.mockReset()
    api.manualCerts.get
      .mockResolvedValueOnce({ success: false, error: 'okunamadı' })
      .mockResolvedValue({ success: true, data: { can_manage: true, versions: [ver(1, true)] } })
    render(<ManualCertVersions domain="keystore.example.test" />)
    const retry = await screen.findByRole('button', { name: /Try again|Yeniden dene/i })
    fireEvent.click(retry)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="mcert-version"]')).toHaveLength(1))
  })
})

/**
 * Eski sürümü kalıcı silme (2026-10-07, kullanıcı isteği) — yalnız GÜNCEL OLMAYAN sürümde ve "Yeni sürüm yükle" ile aynı
 * koşulda; tehlike onayı → DELETE → bildirim + SESSİZ tazeleme (bileşen sökülmez). 409 CURRENT_VERSION açıklanır.
 * "Yine de yükle" ile yeniden yüklenen aynı sertifika sürümü notla işaretlenir.
 */
describe('ManualCertVersions — eski sürümü sil + aynı sertifika notu', () => {
  const three = () => [ver(3, true), { ...ver(2, false), superseded_at: '2026-10-06T00:00:00', superseded_by: 'kisia' }, ver(1, false)]
  const delBtns = () => [...document.querySelectorAll('[data-slot="mcert-version-delete"]')]

  beforeEach(() => {
    vi.clearAllMocks()
    perm.edit = true
    api.admin.getInventoryByDomain.mockResolvedValue({ success: true, data: { id: 7, domain: 'keystore.example.test', can_manage: true } })
    api.manualCerts.get.mockReset()
    api.manualCerts.get.mockResolvedValue({ success: true, data: { can_manage: true, versions: three() } })
  })

  it('"Sil" yalnız eski sürümlerde; güncel sürümde yok; yazma izni / kayıt yönetimi / salt okunur yoksa hiç yok', async () => {
    const { unmount } = render(<ManualCertVersions domain="keystore.example.test" />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="mcert-version"]')).toHaveLength(3))
    expect(delBtns().map((b) => b.closest('[data-slot="mcert-version"]').dataset.version)).toEqual(['2', '1'])
    expect(document.querySelector('[data-slot="mcert-version"][data-current="true"] [data-slot="mcert-version-delete"]')).toBeNull()
    expect(screen.getByRole('button', { name: 'v2 — Delete' })).toBeInTheDocument()
    unmount()

    perm.edit = false
    const r2 = render(<ManualCertVersions domain="keystore.example.test" />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="mcert-version"]')).toHaveLength(3))
    expect(delBtns()).toHaveLength(0)
    r2.unmount()

    perm.edit = true
    api.manualCerts.get.mockResolvedValue({ success: true, data: { can_manage: false, versions: three() } })
    const r3 = render(<ManualCertVersions domain="keystore.example.test" />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="mcert-version"]')).toHaveLength(3))
    expect(delBtns()).toHaveLength(0)
    r3.unmount()

    api.manualCerts.get.mockResolvedValue({ success: true, data: { can_manage: true, versions: three() } })
    render(<ManualCertVersions domain="keystore.example.test" readOnly />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="mcert-version"]')).toHaveLength(3))
    expect(delBtns()).toHaveLength(0)
  })

  it('onay → DELETE (kayıt + sürüm id) → bildirim + liste sessizce tazelenir; bileşen sökülmez; iptal istek atmaz', async () => {
    render(<ManualCertVersions domain="keystore.example.test" />)
    await waitFor(() => expect(delBtns()).toHaveLength(2))
    const root = document.querySelector('[data-slot="mcert-versions"]')

    // İptal: istek yok
    fireEvent.click(screen.getByRole('button', { name: 'v1 — Delete' }))
    let dlg = await screen.findByRole('dialog')
    expect(dlg).toHaveTextContent("Version v1 will be deleted permanently; this can't be undone.")
    fireEvent.click(within(dlg).getByRole('button', { name: /Cancel/i }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(api.manualCerts.deleteVersion).not.toHaveBeenCalled()

    // Onay: DELETE → tazeleme
    api.manualCerts.deleteVersion.mockResolvedValueOnce({ success: true, data: { deleted_version: 1, versions_count: 2 } })
    api.manualCerts.get.mockResolvedValue({ success: true, data: { can_manage: true, versions: three().slice(0, 2) } })
    fireEvent.click(screen.getByRole('button', { name: 'v1 — Delete' }))
    dlg = await screen.findByRole('dialog')
    fireEvent.click(within(dlg).getByRole('button', { name: 'Delete permanently' }))
    await waitFor(() => expect(api.manualCerts.deleteVersion).toHaveBeenCalledWith(7, 101))
    await waitFor(() => expect(document.querySelectorAll('[data-slot="mcert-version"]')).toHaveLength(2))
    expect(await screen.findByText('Version v1 deleted')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="mcert-versions"]')).toBe(root)   // sökülmedi, yeniden kurulmadı
    expect(document.querySelector('[data-slot="mcert-version"][data-current="true"]')).toHaveAttribute('data-version', '3')
  })

  it('409 CURRENT_VERSION → açık ileti; liste tazelenir', async () => {
    render(<ManualCertVersions domain="keystore.example.test" />)
    await waitFor(() => expect(delBtns()).toHaveLength(2))
    api.manualCerts.deleteVersion.mockResolvedValueOnce({ success: false, status: 409, code: 'CURRENT_VERSION', error: 'x' })
    fireEvent.click(screen.getByRole('button', { name: 'v2 — Delete' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Delete permanently' }))
    expect(await screen.findByText(/The current version can't be deleted/)).toBeInTheDocument()
    await waitFor(() => expect(api.manualCerts.get).toHaveBeenCalledTimes(2))
  })

  it('"Yine de yükle" ile aynı sertifika: sürüm kartında "Aynı sertifika yeniden yüklendi" notu (aynı anahtar rozeti yerine)', async () => {
    api.manualCerts.get.mockResolvedValue({ success: true, data: { can_manage: true, versions: [
      { ...ver(3, true), fingerprint: 'FP2', same_as_previous: true, key_changed: false }, { ...ver(2, false), same_as_previous: false }, ver(1, false),
    ] } })
    render(<ManualCertVersions domain="keystore.example.test" />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="mcert-version"]')).toHaveLength(3))
    const cur = document.querySelector('[data-slot="mcert-version"][data-version="3"]')
    expect(cur.querySelector('[data-slot="mcert-same-reupload"]')).toHaveTextContent('Same certificate uploaded again')
    expect(cur.querySelector('[data-slot="mcert-key-same"]')).toBeNull()
    expect(document.querySelectorAll('[data-slot="mcert-same-reupload"]')).toHaveLength(1)
  })
})
