/**
 * 7/24 DURUM GÖSTERGESİ — SAF model (2026-09-28, kullanıcı isteği: "kartların üzerinde küçük bir tasarımla, izleme 7/24
 * ekibine iletiliyorsa ve açıksa veya kapalıysa her izleme için bu bilgi gözüksün").
 *
 * React yok: kart/pencere göstergesi (noc/NocStatus), paylaşılan durum kancası (noc/useNocState) ve birim testler aynı
 * kuralları buradan okur.
 *
 * <p><b>Tek doğruluk kaynağı = sunucunun kapsam kuralı</b> (`NocCoverageService.reason`, sözleşme `.migration/noc/CONTRACT.md`):
 * izleme `noc_notify` → tür Ayarlar'da açık → en az bir KULLANILABİLİR 7/24 grubu (aktif + adresli). İzlemenin seçtiği
 * grupların hepsi pasifse sunucu varsayılan gruplara (yoksa tüm aktiflere) DÜŞER (`NocGroupService.resolveTargets`) —
 * bu bir engel DEĞİLDİR; göstergede alıcı grup adları o çözümle yazılır. Grup hükmü istemcide tahmin edilmez: sunucu
 * `has_active_group` gönderir (aktif ama adressiz grup istemcinin göremeyeceği bir ayrıntı).
 *
 * <p>Üç görünür durum:
 * <ul>
 *   <li>`on`      — 7/24 açık ve etkin (ya da ayarlar doğrulanamadı: `verified: false` — "iletilmiyor" iddiası YOK);</li>
 *   <li>`blocked` — 7/24 açık ama iletilmiyor: `TYPE_DISABLED` (tür Ayarlar'dan kapalı) | `NO_ACTIVE_GROUP`;</li>
 *   <li>`off`     — 7/24 kapalı.</li>
 * </ul>
 * `noc_notify` boolean DEĞİLSE (alanı taşımayan yanıt — ör. Uyarılar'ın ham sertifika satırı) sonuç `null`: gösterge
 * çizilmez. "Bilinmiyor" hiçbir zaman "kapalı" gösterilmez.
 *
 * <p>Duraklatılmış izleme durumu DEĞİŞTİRMEZ (kartın kendi "Duraklatıldı" rozeti var; 7/24 Kapsamı da duraklatılmışı
 * açık saymaz): gösterge yönlendirme ayarını anlatır, açıklamaya "duraklatıldı" notu düşer (`paused`).
 */
import { effectiveSelection, fallbackIds, nocIdsFrom } from './forms/nocFormModel.js'

export const NOC_STATUS = Object.freeze({ ON: 'on', BLOCKED: 'blocked', OFF: 'off' })
export const NOC_BLOCK_REASONS = Object.freeze(['TYPE_DISABLED', 'NO_ACTIVE_GROUP'])
const LEVELS = ['CRITICAL', 'HIGH', 'WARNING']

/**
 * Paylaşılan 7/24 durumunun (useNocState) "kullanılabilir" hâli: `{ disabledTypes, hasActiveGroup, groups, minLevel }`
 * ya da (yüklenmedi / hata / eksik yanıt) `null`. Bir parça bilinmiyorsa (`disabled_types` ya da grup hükmü yok) o parça
 * `null` kalır ve ilgili engel İDDİA EDİLMEZ.
 */
export function usableNocState(noc) {
  const d = noc && noc.data
  if (!d) return null
  const groups = Array.isArray(d.groups) ? d.groups.filter(Boolean) : null
  const hasActiveGroup = typeof d.hasActiveGroup === 'boolean'
    ? d.hasActiveGroup
    // Eski sunucu has_active_group göndermezse: aktif grup bayrağından (adres ayrıntısı bilinmez — iyimser)
    : (groups ? groups.some((g) => g.active === true) : null)
  return {
    disabledTypes: Array.isArray(d.disabledTypes) ? d.disabledTypes : null,
    hasActiveGroup,
    groups,
    minLevel: LEVELS.includes(d.minLevel) ? d.minLevel : null,
  }
}

/**
 * İzlemenin e-postasının gideceği 7/24 grup ADLARI — sunucunun `resolveTargets` sırası: izlemenin seçtiği AKTİF gruplar;
 * hiçbiri yoksa (seçim yok / hepsi pasif ya da silinmiş) aktif varsayılanlar; hiç varsayılan yoksa tüm aktifler.
 * Gruplar bilinmiyorsa `[]` (satır çizilmez).
 */
export function targetGroupNames(groupIds, groups) {
  if (!Array.isArray(groups) || !groups.length) return []
  const byId = new Map(groups.filter(Boolean).map((g) => [Number(g.id), g]))
  const chosen = effectiveSelection(nocIdsFrom(groupIds), groups).filter((id) => byId.get(id)?.active === true)
  const ids = chosen.length ? chosen : fallbackIds(groups)
  return ids.map((id) => byId.get(id)?.name).filter((n) => n != null && String(n).trim() !== '').map(String)
}

/**
 * Göstergenin durumu.
 *
 * @param {{ notify: boolean|undefined, type: string, groupIds?: Array|string|null, active?: boolean }} monitor
 *        `notify` = satırın `noc_notify`; `type` = 7/24 tür anahtarı (HTTP, PING, …, SSL); `groupIds` = satırın
 *        `noc_group_ids` (`undefined` = satır taşımıyor → alıcı grup yazılmaz); `active === false` = duraklatılmış
 * @param {object|null} noc  paylaşılan durum (useNocState): `{ status, data }`
 * @returns {null | { state: 'on'|'blocked'|'off', reason: string|null, verified: boolean, groups: string[],
 *                    minLevel: string|null, paused: boolean }}
 */
export function nocStatusOf({ notify, type, groupIds, active } = {}, noc = null) {
  if (typeof notify !== 'boolean') return null
  const paused = active === false
  const base = { reason: null, verified: false, groups: [], minLevel: null, paused }
  if (!notify) return { ...base, state: NOC_STATUS.OFF }
  const s = usableNocState(noc)
  if (!s) return { ...base, state: NOC_STATUS.ON }
  const minLevel = s.minLevel
  if (s.disabledTypes && type && s.disabledTypes.includes(type)) {
    return { ...base, state: NOC_STATUS.BLOCKED, reason: 'TYPE_DISABLED', verified: true, minLevel }
  }
  if (s.hasActiveGroup === false) {
    return { ...base, state: NOC_STATUS.BLOCKED, reason: 'NO_ACTIVE_GROUP', verified: true, minLevel }
  }
  // Tür listesi ya da grup hükmü bilinmiyorsa "etkin" de iddia edilmez (doğrulanamadı)
  const verified = s.disabledTypes != null && s.hasActiveGroup === true
  // Alıcı gruplar yalnız satır `noc_group_ids` TAŞIYORSA (izleme listeleri hep taşır; `[]` = varsayılan gruplar).
  // Taşımayan satırda (sertifika kartı) varsayılanları yazmak, açık seçimi olan kayıtta yanlış olurdu → satır yok.
  const groups = verified && groupIds !== undefined ? targetGroupNames(groupIds, s.groups) : []
  return { ...base, state: NOC_STATUS.ON, verified, minLevel, groups }
}

/** Görünür etiket anahtarları (i18n) — durum başına TAM etiket (erişilebilir ad + açıklama başlığı). */
export const NOC_STATUS_LABEL = Object.freeze({
  on: 'nocs.label.on',
  blocked: 'nocs.label.blocked',
  off: 'nocs.label.off',
})

/** Zengin görünüm hapındaki durum sözcüğü ("7/24" yanında). */
export const NOC_STATUS_WORD = Object.freeze({
  on: 'nocs.pill.on',
  blocked: 'nocs.pill.blocked',
  off: 'nocs.pill.off',
})

/** Açıklamadaki seviye ifadesi ("Kritik uyarılar …"). */
export const NOC_LEVEL_PHRASE = Object.freeze({
  CRITICAL: 'nocs.level.CRITICAL',
  HIGH: 'nocs.level.HIGH',
  WARNING: 'nocs.level.WARNING',
})

/** Tür anahtarı → izleme sekmesi (derin bağlantı). SSL = Pano (sertifika kartı kendi düzenleme yolunu verir). */
export const NOC_STATUS_TAB = Object.freeze({
  PING: 'ping', HTTP: 'http', KEYWORD: 'keyword', PAGE: 'page', PAGESPEED: 'pagespeed',
  SCRIPTED: 'scripted', DNS: 'dns', PORT: 'port', DOMAIN: 'domain',
})

/**
 * "7/24 ayarını düzenle" hedefi — `{ tab, params }` ya da `null`. İzleme türleri: `?tab=<tür>&monitor=<id>&open=noc`
 * (hooks/useMonitorDeepLink: düzenleyebilene form, 7/24 alanına kaydırılmış; aynı sekmede de çalışır). SSL için null —
 * Pano'daki `domain` parametresi aramayı da süzdüğünden sertifika kartı kendi düzenleme işleyicisini verir.
 */
export function nocEditTarget(type, id) {
  const tab = NOC_STATUS_TAB[type]
  if (!tab || id == null || id === '') return null
  return { tab, params: { monitor: id, open: 'noc' } }
}

/** "7/24 Kapsamı'nda gör" hedefi — kapsam sayfası, tür + arama süzgeciyle (sekme `noc`, `n_type` / `n_q`). */
export function nocCoverageTarget(type, query) {
  const params = {}
  if (type) params.n_type = type
  const q = query == null ? '' : String(query).trim()
  if (q) params.n_q = q
  return { tab: 'noc', params }
}
