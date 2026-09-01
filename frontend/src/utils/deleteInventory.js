import { api } from '../api/client'

/**
 * Envanter kaydını ALAN ADIYLA siler — onay, kayıt çözümü, silme ve geri bildirim tek yerde.
 *
 * <p><b>Neden ortak.</b> Aynı akış iki yüzeyden başlıyor: sertifika detay modalinin başlığındaki
 * çöp kutusu ve Genel Bakış kartının yeni kısayolu. İki kopya olsaydı onay metni, "kayıt
 * bulunamadı" dalı ve toast'lar zamanla ayrışırdı — yıkıcı bir eylemde bu, kullanıcının ne
 * onayladığını sayfadan sayfaya değiştirir.
 *
 * <p>Dashboard kartında envanter ID'si YOKTUR (sertifikalar domain-anahtarlı), bu yüzden kayıt
 * önce domain ile çözülür. Bulunamazsa (silinmiş / görüş kapsamı dışı) SİLME denenmez.
 *
 * <p><b>Ağ hatası burada yutulur ama SESSİZ değildir.</b> {@code request()} ağ hatasında THROW
 * ediyor (bkz. api/client.js); istisna dışarı sızsaydı çağıranın "siliniyor" bayrağı temizlenmez
 * ve düğme yeniden çizilene kadar kilitli kalırdı — üstelik kullanıcı hiçbir geri bildirim
 * görmezdi. Arama hatası ile "kayıt yok" AYRI raporlanır: ağ koptuğunda "kayıt bulunamadı"
 * demek, var olan bir kaydı silinmiş sanmaya davettir.
 *
 * @returns {Promise<boolean>} gerçekten silindiyse true (çağıran listesini tazeler)
 */
export async function deleteInventoryByDomain({ domain, showConfirm, toast, t }) {
  const ok = await showConfirm({
    title: t('inv.deleteTitle'),
    message: t('inv.deleteMsg', domain),
    confirmText: t('inv.deleteConfirm'),
    cancelText: t('inv.deleteCancel'),
    variant: 'danger',
  })
  if (!ok) return false

  let found
  try {
    found = await api.admin.getInventoryByDomain(domain)
  } catch {
    toast.error(t('inv.deleteError'))
    return false
  }
  const id = found?.success ? found.data?.id : null
  if (!id) { toast.error(t('inv.deleteNotFound')); return false }

  try {
    const res = await api.admin.deleteInventory(id)
    if (res?.success) { toast.success(t('inv.deleted')); return true }
    toast.error(res?.error || t('inv.deleteError'))
  } catch {
    toast.error(t('inv.deleteError'))
  }
  return false
}
