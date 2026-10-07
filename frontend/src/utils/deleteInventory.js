import { api } from '../api/client'
import { markDeleted } from './recentlyDeleted.js'

/**
 * Onay penceresinde gösterilecek ad listesi: ilk {@code max} ad virgülle, kalanı "+N kayıt daha". Toplu silmede kullanıcı
 * NEYİ onayladığını görür (kalıcı silme — geri dönüş yok).
 */
export function namesPreview(names, t, max = 5) {
  const list = (names || []).filter((n) => n != null && String(n).trim() !== '').map(String)
  if (list.length <= max) return list.join(', ')
  return `${list.slice(0, max).join(', ')} ${t('del.moreNames', list.length - max)}`
}

/**
 * Tekil envanter silme onayının metni (2026-10-07, silme KALICI): kayıt ADIYLA, neyin birlikte gittiği ve geri alınamaz
 * olduğu; açık alarm varsa sayısı ve kapanacağı. Envanter, form, sertifika penceresi ve Genel Bakış kartı AYNI metni gösterir.
 */
export function deleteConfirmMessage(t, domain, openAlertCount = 0) {
  const base = t('inv.deleteMsg', domain)
  return openAlertCount > 0
    ? `${base}\n\n${t('inv.deleteHasAlerts', openAlertCount)} ${t('inv.deleteAlertWarning')}`
    : base
}

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
 * <p><b>Silme KALICI (2026-10-07).</b> Onay bunu açıkça söyler (danger). Başarıda kayıt {@code recentlyDeleted}'a
 * işaretlenir: çağıran kartı hemen kaldırır ve arka plan tazelemesi (ya da başka pod'un bayat önbelleği) onu geri
 * getiremez.
 *
 * <p><b>Ağ hatası burada yutulur ama SESSİZ değildir.</b> {@code request()} ağ hatasında THROW
 * ediyor (bkz. api/client.js); istisna dışarı sızsaydı çağıranın "siliniyor" bayrağı temizlenmez
 * ve düğme yeniden çizilene kadar kilitli kalırdı — üstelik kullanıcı hiçbir geri bildirim
 * görmezdi. Arama hatası ile "kayıt yok" AYRI raporlanır: ağ koptuğunda "kayıt bulunamadı"
 * demek, var olan bir kaydı silinmiş sanmaya davettir.
 *
 * @returns {Promise<boolean>} gerçekten silindiyse true (çağıran kartı hemen kaldırır, listesini arka planda tazeler)
 */
export async function deleteInventoryByDomain({ domain, showConfirm, toast, t }) {
  const ok = await showConfirm({
    title: t('inv.deleteTitle'),
    message: deleteConfirmMessage(t, domain),
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
    if (res?.success) {
      markDeleted('cert', domain)
      toast.success(t('inv.deleted', domain))
      return true
    }
    toast.error(res?.error || t('inv.deleteError'))
  } catch {
    toast.error(t('inv.deleteError'))
  }
  return false
}
