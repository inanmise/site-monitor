import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'

/**
 * Envanter formu — manuel (dosyadan yüklenen) kayıt (2026-10-06): ağa özgü alanlar (port, vekil, TLS kipi, zaman aşımı,
 * sıklık) ve "Test et" yok; takip adı DÜZENLENEBİLİR (2026-10-07, kullanıcı isteği — takip adı kuralı + yeniden adlandırma
 * onayı); "Çalıştır" = "Yeniden değerlendir"; kaydedince canlı ilk kontrol KOŞMAZ.
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
const showConfirm = vi.hoisted(() => vi.fn(() => Promise.resolve(true)))
vi.mock('../components/ui/Dialog.jsx', () => ({
  useDialog: () => ({ showConfirm }),
  DialogProvider: ({ children }) => children,
}))
vi.mock('@uiw/react-md-editor', () => ({
  default: ({ value, textareaProps }) => <textarea readOnly value={value ?? ''} {...(textareaProps ?? {})} />,
  commands: { divider: {}, codeEdit: {}, codePreview: {}, fullscreen: {} },
}))

import { api } from '../api/client'
import InventoryFormModal from '../components/inventory/InventoryFormModal.jsx'
import { INVENTORY_RENAMED_EVENT } from '../utils/inventoryEvent.js'

const RECORD = {
  id: 7, domain: 'keystore.example.test', port: 443, active: true, team_id: 1, group_name: 'Prod', tags: 'prod', tier: 2,
  tls_mode: 'browser', timeout_seconds: 9, check_interval_hours: 6, use_proxy: false, owner: 'Ops', description: 'JKS',
}

describe('InventoryFormModal — manuel kayıt kipi', () => {
  beforeEach(() => vi.clearAllMocks())

  it('ağ alanları ve "Test et" yok; takip adı düzenlenebilir; bilgi bandı; "Yeniden değerlendir"', () => {
    render(<InventoryFormModal mode="edit" record={{ ...RECORD, cert_source: 'MANUAL' }} teams={[{ id: 1, name: 'Takım A' }]} canManage onClose={() => {}} />)
    expect(document.querySelector('[data-slot="inv-form-manual"]')).toBeTruthy()
    expect(screen.queryByLabelText(/^(Port)$/)).toBeNull()
    expect(screen.queryByText(/^(TLS Mode|TLS Modu)$/i)).toBeNull()
    expect(screen.queryByRole('switch', { name: /proxy|vekil/i })).toBeNull()
    expect(screen.queryByRole('button', { name: /^(Test|Test et)$/ })).toBeNull()
    const key = screen.getByLabelText(/^(Tracking name|Takip adı)/)
    expect(key).toHaveValue('keystore.example.test')
    expect(key).not.toHaveAttribute('readonly')
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

  it('takip adı değiştirilir: manuel onay metni, gövdede yeni ad, canlı kontrol yok', async () => {
    const onSaved = vi.fn()
    render(<InventoryFormModal mode="edit" record={{ ...RECORD, cert_source: 'MANUAL' }} teams={[{ id: 1, name: 'Takım A' }]} canManage onClose={() => {}} onSaved={onSaved} />)
    fireEvent.change(screen.getByLabelText(/^(Tracking name|Takip adı)/), { target: { value: '*.odeme-keystore.example.test' } })
    fireEvent.click(screen.getByRole('button', { name: /^(Save|Kaydet)$/ }))
    await waitFor(() => expect(onSaved).toHaveBeenCalled())
    expect(showConfirm).toHaveBeenCalledWith(expect.objectContaining({
      title: expect.stringMatching(/Change the tracking name|Takip adı değiştirilsin/),
      message: expect.stringContaining('*.odeme-keystore.example.test'),
    }))
    expect(api.admin.updateInventory.mock.calls[0][1]).toMatchObject({ domain: '*.odeme-keystore.example.test' })
    expect(api.refreshCertificateHealth).not.toHaveBeenCalled()
  })

  it('geçersiz takip adı alanın altında hata verir, kaydetmez (büyük harf / boşluk / "/")', async () => {
    render(<InventoryFormModal mode="edit" record={{ ...RECORD, cert_source: 'MANUAL' }} teams={[{ id: 1, name: 'Takım A' }]} canManage onClose={() => {}} />)
    const key = screen.getByLabelText(/^(Tracking name|Takip adı)/)
    for (const bad of ['Keystore.Example.Test', 'key store', 'a/b']) {
      fireEvent.change(key, { target: { value: bad } })
      fireEvent.click(screen.getByRole('button', { name: /^(Save|Kaydet)$/ }))
      await waitFor(() => expect(key).toHaveAttribute('aria-invalid', 'true'))
    }
    expect(api.admin.updateInventory).not.toHaveBeenCalled()
    expect(showConfirm).not.toHaveBeenCalled()
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

  // 2026-10-08 (kullanıcı: "takip adı değiştirince Sağlık / Kontrol geçmişi 404"): ad değişince eski adı tutan yüzeyler
  // (açık sertifika penceresi, Manuel Sertifikalar, Envanter) olayla yeni ada geçer. Ad SUNUCUNUN kaydettiği addır.
  describe('yeniden adlandırma olayı', () => {
    function listen() {
      const seen = []
      const on = (e) => seen.push(e.detail)
      window.addEventListener(INVENTORY_RENAMED_EVENT, on)
      return { seen, stop: () => window.removeEventListener(INVENTORY_RENAMED_EVENT, on) }
    }

    it('manuel takip adı değişince eski → yeni (sunucunun döndürdüğü ad) yayılır, onSaved ÖNCESİ', async () => {
      const ev = listen()
      let seenAtSave = null
      const onSaved = vi.fn(() => { seenAtSave = ev.seen.length })
      api.admin.updateInventory.mockResolvedValueOnce({ success: true, data: { id: 7, domain: 'odeme-keystore', cert_source: 'MANUAL' } })
      render(<InventoryFormModal mode="edit" record={{ ...RECORD, cert_source: 'MANUAL' }} teams={[{ id: 1, name: 'Takım A' }]} canManage onClose={() => {}} onSaved={onSaved} />)
      fireEvent.change(screen.getByLabelText(/^(Tracking name|Takip adı)/), { target: { value: 'odeme-keystore' } })
      fireEvent.click(screen.getByRole('button', { name: /^(Save|Kaydet)$/ }))
      await waitFor(() => expect(onSaved).toHaveBeenCalled())
      ev.stop()
      expect(ev.seen).toEqual([{ from: 'keystore.example.test', to: 'odeme-keystore' }])
      expect(seenAtSave).toBe(1)
      expect(onSaved.mock.calls[0][1]).toBe('odeme-keystore')
    })

    it('ağ kaydında alan adı değişince de yayılır; ilk kontrol YENİ adla koşar', async () => {
      const ev = listen()
      const onSaved = vi.fn()
      api.admin.updateInventory.mockResolvedValueOnce({ success: true, data: { id: 7, domain: 'yeni.example.test' } })
      render(<InventoryFormModal mode="edit" record={RECORD} teams={[{ id: 1, name: 'Takım A' }]} canManage onClose={() => {}} onSaved={onSaved} />)
      fireEvent.change(screen.getByLabelText(/^(Domain|Alan adı)/), { target: { value: 'Yeni.Example.Test' } })
      fireEvent.click(screen.getByRole('button', { name: /^(Save|Kaydet)$/ }))
      await waitFor(() => expect(onSaved).toHaveBeenCalled())
      ev.stop()
      expect(ev.seen).toEqual([{ from: 'keystore.example.test', to: 'yeni.example.test' }])
      expect(api.refreshCertificateHealth).toHaveBeenCalledWith('yeni.example.test')
    })

    it('ad değişmeden kaydedilince olay YOK', async () => {
      const ev = listen()
      const onSaved = vi.fn()
      api.admin.updateInventory.mockResolvedValueOnce({ success: true, data: { id: 7, domain: 'keystore.example.test', cert_source: 'MANUAL' } })
      render(<InventoryFormModal mode="edit" record={{ ...RECORD, cert_source: 'MANUAL' }} teams={[{ id: 1, name: 'Takım A' }]} canManage onClose={() => {}} onSaved={onSaved} />)
      fireEvent.click(screen.getByRole('button', { name: /^(Save|Kaydet)$/ }))
      await waitFor(() => expect(onSaved).toHaveBeenCalled())
      ev.stop()
      expect(ev.seen).toEqual([])
    })
  })
})
