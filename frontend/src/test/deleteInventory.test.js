import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../api/client', () => ({
  api: {
    admin: {
      getInventoryByDomain: vi.fn(),
      deleteInventory: vi.fn(),
    },
  },
}))

const { api } = await import('../api/client')
const { deleteInventoryByDomain } = await import('../utils/deleteInventory.js')

/**
 * Envanter silme ORTAK akışı — iki yüzey (Genel Bakış kartı + sertifika detay modali) aynı
 * onayı, aynı uçları ve aynı geri bildirimi kullanır.
 *
 * <p>Ağ dalları burada kilitleniyor: `request()` ağ hatasında THROW ediyor (api/client.js).
 * İstisna dışarı sızarsa çağıranın "siliniyor" bayrağı temizlenmez ve düğme yeniden çizilene
 * kadar kilitli kalır — üstelik kullanıcı hiçbir geri bildirim görmez.
 */
describe('deleteInventoryByDomain', () => {
  const t = (k) => k
  let toast

  beforeEach(() => {
    vi.clearAllMocks()
    toast = { success: vi.fn(), error: vi.fn() }
  })

  const confirmYes = vi.fn().mockResolvedValue(true)
  const confirmNo = vi.fn().mockResolvedValue(false)

  it('onay reddedilirse HİÇBİR uç çağrılmaz', async () => {
    const ok = await deleteInventoryByDomain({ domain: 'a.example.com', showConfirm: confirmNo, toast, t })
    expect(ok).toBe(false)
    expect(api.admin.getInventoryByDomain).not.toHaveBeenCalled()
    expect(api.admin.deleteInventory).not.toHaveBeenCalled()
  })

  it('kayıt bulunamazsa SİLME denenmez', async () => {
    api.admin.getInventoryByDomain.mockResolvedValue({ success: true, data: null })
    const ok = await deleteInventoryByDomain({ domain: 'a.example.com', showConfirm: confirmYes, toast, t })
    expect(ok).toBe(false)
    expect(api.admin.deleteInventory).not.toHaveBeenCalled()
    expect(toast.error).toHaveBeenCalledWith('inv.deleteNotFound')
  })

  it('ARAMA ağ hatasında "bulunamadı" DEMEZ ve silme denemez (var olan kayıt silinmiş sanılmasın)', async () => {
    api.admin.getInventoryByDomain.mockRejectedValue(new Error('network'))
    const ok = await deleteInventoryByDomain({ domain: 'a.example.com', showConfirm: confirmYes, toast, t })
    expect(ok).toBe(false)
    expect(api.admin.deleteInventory).not.toHaveBeenCalled()
    expect(toast.error).toHaveBeenCalledWith('inv.deleteError')
    expect(toast.error).not.toHaveBeenCalledWith('inv.deleteNotFound')
  })

  it('SİLME ağ hatasında istisna DIŞARI SIZMAZ, hata bildirilir', async () => {
    api.admin.getInventoryByDomain.mockResolvedValue({ success: true, data: { id: 42 } })
    api.admin.deleteInventory.mockRejectedValue(new Error('network'))

    const ok = await deleteInventoryByDomain({ domain: 'a.example.com', showConfirm: confirmYes, toast, t })

    expect(ok, 'istisna sızarsa çağıranın "siliniyor" bayrağı asılı kalır').toBe(false)
    expect(toast.error).toHaveBeenCalledWith('inv.deleteError')
  })

  it('uç success:false dönerse ucun hata metni gösterilir', async () => {
    api.admin.getInventoryByDomain.mockResolvedValue({ success: true, data: { id: 42 } })
    api.admin.deleteInventory.mockResolvedValue({ success: false, error: 'yetkiniz yok' })
    const ok = await deleteInventoryByDomain({ domain: 'a.example.com', showConfirm: confirmYes, toast, t })
    expect(ok).toBe(false)
    expect(toast.error).toHaveBeenCalledWith('yetkiniz yok')
  })

  it('başarılı silmede true döner (çağıran listesini tazeler)', async () => {
    api.admin.getInventoryByDomain.mockResolvedValue({ success: true, data: { id: 42 } })
    api.admin.deleteInventory.mockResolvedValue({ success: true })
    const ok = await deleteInventoryByDomain({ domain: 'a.example.com', showConfirm: confirmYes, toast, t })
    expect(ok).toBe(true)
    expect(api.admin.deleteInventory).toHaveBeenCalledWith(42)
    expect(toast.success).toHaveBeenCalledWith('inv.deleted')
  })
})
