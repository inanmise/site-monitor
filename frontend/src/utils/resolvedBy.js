/**
 * Alarm "kim/ne kapattı" değerinin insan-okur karşılığı.
 *
 * <p>{@code AlertEvent.resolvedBy} iki farklı şey taşıyabiliyor: gerçek bir SİCİL ya da bir
 * SİSTEM JETONU ({@code inventory_delete}, {@code system} …). Arayüz bunu ayırmadığı için
 * jetonlar kişi rozeti olarak çiziliyordu — ekranda "inventory_delete" adlı bir kullanıcı
 * varmış gibi görünüyordu ve kullanıcı alarmın NEDEN kapandığını anlayamıyordu.
 *
 * <p>Bilinmeyen bir jeton eklenirse burada değil KİŞİ olarak çizilir; bu bilinçli — yeni bir
 * sistem jetonu tanıtılırsa listeye eklenmesi gerektiği ekranda hemen fark edilir.
 */
export const SYSTEM_RESOLVERS = {
  system:                'alh.resolvedBy.system',
  inventory_delete:      'alh.resolvedBy.inventoryDelete',
  inventory_deactivate:  'alh.resolvedBy.inventoryDeactivate',
}

/** Değer bir sistem jetonu mu (kişi değil)? */
export function isSystemResolver(v) {
  return typeof v === 'string' && Object.prototype.hasOwnProperty.call(SYSTEM_RESOLVERS, v)
}

/** Sistem jetonunun i18n anahtarı; kişi ise null. */
export function systemResolverKey(v) {
  return isSystemResolver(v) ? SYSTEM_RESOLVERS[v] : null
}
