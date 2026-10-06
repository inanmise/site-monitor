import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'

/**
 * Envanter formu — manuel (dosyadan yüklenen) kayıt (2026-10-06): ağa özgü alanlar (port, vekil, TLS kipi, zaman aşımı,
 * sıklık) ve "Test et" yok; takip adı salt okunur; "Çalıştır" = "Yeniden değerlendir"; kaydedince canlı ilk kontrol KOŞMAZ.
 * Gövde ağ alanlarını kayıttaki gibi taşır. Ağ kaydında form birebir aynı (aynı dosyadaki ikinci senaryo).
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({
    admin: {
      updateInventory: vi.fn().mockResolvedValue({ success: true }),
      getTeams: vi.fn().mockResolvedValue({ success: true, data: [{ id: 1, name: 'Takım A' }] }),
    },
    refreshCertificateHealth: vi.fn().mockResolvedValue({ success: true }),
    monitoring: { listGroups: vi.fn().mockResolvedValue({ success: true, data: [] }) },
  }),
  formatDateOnly: (s) => String(s ?? ''),
}))
vi.mock('../components/ui/Dialog.jsx', () => ({
  useDialog: () => ({ showConfirm: vi.fn(() => Promise.resolve(true)) }),
  DialogProvider: ({ children }) => children,
}))
vi.mock('@uiw/react-md-editor', () => ({
  default: ({ value, textareaProps }) => <textarea readOnly value={value ?? ''} {...(textareaProps ?? {})} />,
  commands: { divider: {}, codeEdit: {}, codePreview: {}, fullscreen: {} },
}))

import { api } from '../api/client'
import InventoryFormModal from '../components/inventory/InventoryFormModal.jsx'

const RECORD = {
  id: 7, domain: 'keystore.example.test', port: 443, active: true, team_id: 1, group_name: 'Prod', tags: 'prod', tier: 2,
  tls_mode: 'browser', timeout_seconds: 9, check_interval_hours: 6, use_proxy: false, owner: 'Ops', description: 'JKS',
}

describe('InventoryFormModal — manuel kayıt kipi', () => {
  beforeEach(() => vi.clearAllMocks())

  it('ağ alanları ve "Test et" yok; takip adı salt okunur; bilgi bandı; "Yeniden değerlendir"', () => {
    render(<InventoryFormModal mode="edit" record={{ ...RECORD, cert_source: 'MANUAL' }} teams={[{ id: 1, name: 'Takım A' }]} canManage onClose={() => {}} />)
    expect(document.querySelector('[data-slot="inv-form-manual"]')).toBeTruthy()
    expect(screen.queryByLabelText(/^(Port)$/)).toBeNull()
    expect(screen.queryByText(/^(TLS Mode|TLS Modu)$/i)).toBeNull()
    expect(screen.queryByRole('switch', { name: /proxy|vekil/i })).toBeNull()
    expect(screen.queryByRole('button', { name: /^(Test|Test et)$/ })).toBeNull()
    const key = screen.getByLabelText(/^(Tracking name|Takip adı)$/)
    expect(key).toHaveValue('keystore.example.test')
    expect(key).toHaveAttribute('readonly')
    expect(screen.getByRole('button', { name: /Re-evaluate|Yeniden değerlendir/ })).toBeInTheDocument()
  })

  it('kaydedince canlı ilk kontrol KOŞMAZ; ağ alanları kayıttaki gibi gider', async () => {
    const onSaved = vi.fn()
    render(<InventoryFormModal mode="edit" record={{ ...RECORD, cert_source: 'MANUAL' }} teams={[{ id: 1, name: 'Takım A' }]} canManage onClose={() => {}} onSaved={onSaved} />)
    fireEvent.click(screen.getByRole('button', { name: /^(Save|Kaydet)$/ }))
    await waitFor(() => expect(onSaved).toHaveBeenCalled())
    expect(api.refreshCertificateHealth).not.toHaveBeenCalled()
    const [, payload] = api.admin.updateInventory.mock.calls[0]
    expect(payload).toMatchObject({ domain: 'keystore.example.test', port: 443, tls_mode: 'browser', timeout_seconds: 9, check_interval_hours: 6 })
  })

  it('ağ kaydı DEĞİŞMEZ: port, TLS kipi, Test et var; kaydedince ilk kontrol koşar', async () => {
    const onSaved = vi.fn()
    render(<InventoryFormModal mode="edit" record={RECORD} teams={[{ id: 1, name: 'Takım A' }]} canManage onClose={() => {}} onSaved={onSaved} />)
    expect(document.querySelector('[data-slot="inv-form-manual"]')).toBeNull()
    expect(screen.getByRole('button', { name: /^(Test|Test et)$/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^(Run|Çalıştır)$/ })).toBeInTheDocument()
    expect(screen.getByText(/^(TLS Mode|TLS Modu)$/i)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /^(Save|Kaydet)$/ }))
    await waitFor(() => expect(onSaved).toHaveBeenCalled())
    expect(api.refreshCertificateHealth).toHaveBeenCalledWith('keystore.example.test')
  })
})
