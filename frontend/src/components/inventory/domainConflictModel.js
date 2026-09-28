/**
 * Mükerrer alan adı deneyimi (2026-09-28) — SAF model (React'siz, test edilebilir).
 *
 * <p>Sunucu (AdminController ekle / yeniden adlandır) alan adı envanterde zaten kayıtlıysa 409 döner:
 * `{ success:false, error, code:'DOMAIN_EXISTS', existing:{ domain, inventory_id, team_id, team_name, ug_team_id,
 * ug_team_name, deleted, deleted_at, same_team, can_view, can_restore, can_transfer } }`. `error` sahibi takımı adıyla
 * söyleyen, istek dilinde (X-Lang) hazır iletidir. `can_*` bayrakları ilgili uçların kapılarının AYNASIDIR — asıl kapı
 * yine sunucuda; arayüz yalnız 403'e gidecek ölü düğme çizmemek için okur.
 */

export const DOMAIN_EXISTS = 'DOMAIN_EXISTS'

/** Yanıt bir mükerrer alan adı 409'u mu? Değilse null; öyleyse `{ message, existing }`. */
export function domainConflictOf(res) {
  if (!res || res.success !== false || res.code !== DOMAIN_EXISTS) return null
  if (!res.existing || typeof res.existing !== 'object') return null
  return { message: typeof res.error === 'string' ? res.error : '', existing: res.existing }
}

/**
 * Bantta hangi eylemler çizilir.
 *
 * - `view`     : silinmemiş ve çağıran okuyabiliyor (kendi kapsamı ya da org geneli okuma).
 * - `restore`  : çöp kutusunda + geri yükleme kapısı (takım yönetimi + inventory.crud).
 * - `transfer` : başka takımın kaydı + global yönetici aktarım izni + formda hedef takım seçili.
 *                Çöp kutusundaysa "geri yükle ve aktar" (iki çağrı: aktar → geri yükle).
 * - `request`  : başka takımın kaydı, aktarım yetkisi yok → Sorun Bildir akışıyla aktarım talebi.
 * - `askManager`: kendi (seçili) takımının çöp kutusunda ama geri yükleme yetkisi yok → yöneticiye yönlendir.
 */
export function conflictActions(existing, targetTeamId) {
  const ex = existing || {}
  const deleted = !!ex.deleted
  const hasTarget = targetTeamId != null && String(targetTeamId) !== ''
  const sameTeam = ex.same_team === true
    || (hasTarget && ex.team_id != null && String(ex.team_id) === String(targetTeamId))
  const transfer = !sameTeam && hasTarget && ex.can_transfer === true && ex.inventory_id != null
  return {
    deleted,
    sameTeam,
    view: !deleted && ex.can_view === true,
    restore: deleted && ex.can_restore === true,
    transfer,
    request: !sameTeam && hasTarget && !transfer,
    askManager: deleted && sameTeam && ex.can_restore !== true,
  }
}

/** Talep gerekçesi üst sınırı — bileşik ileti sunucu sınırının (5000) altında kalsın. */
export const JUSTIFICATION_MAX = 2000

/**
 * Aktarım talebinin Sorun Bildirimleri'ne düşen İLETİSİ — yöneticinin tek bakışta işleyebileceği düz metin
 * (alan adı + kayıt no + mevcut / istenen ekip + gerekçe). Talep eden kişinin arayüz dilinde yazılır.
 *
 * @param {{domain:string, inventoryId?:number, fromTeam?:{id?:number,name?:string}, toTeam?:{id?:number,name?:string}, deleted?:boolean}} req
 */
export function composeTransferRequest(req, justification, t) {
  const team = (tm) => (tm?.name ? (tm.id != null ? `${tm.name} (#${tm.id})` : tm.name) : t('dupx.noTeam'))
  const record = req.inventoryId != null ? ` (${t('dupx.reqMsgRecord', req.inventoryId)})` : ''
  return [
    t('dupx.reqMsgHead'),
    `${t('dupx.reqDomain')}: ${req.domain}${record}`,
    `${t('dupx.reqMsgFrom')}: ${team(req.fromTeam)}`,
    `${t('dupx.reqMsgTo')}: ${team(req.toTeam)}`,
    ...(req.deleted ? [t('dupx.reqMsgBin')] : []),
    '',
    `${t('dupx.reqWhy')}:`,
    String(justification ?? '').trim(),
  ].join('\n')
}
