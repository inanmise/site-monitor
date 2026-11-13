// Bildirim grubu TEMİZLİK modeli (2026-11-12, zenginleştirme #7) — saf, test edilebilir. Kullanım sayısı (sunucu
// usage-counts), "kullanılmayan" tespiti, KPI, süzgeç. counts bilinmiyorsa (okunamadı) kullanım null → "—" (bilinmeyen
// ≠ kullanılmıyor): bir grubu YANLIŞÇA "kullanılmıyor" gösterip silmeye yönlendirmeyiz.

/** Grubun referans sayısı: counts yüklü değilse null (bilinmiyor); yüklüyse haritada yoksa 0 (kullanılmıyor). */
export function usageOf(group, counts) {
  if (!group || counts == null) return null
  const n = counts[String(group.id)]
  return Number.isFinite(Number(n)) ? Number(n) : 0
}

/** Yalnız AKTİF grup "kullanılmayan" sayılır (pasif/silinmiş grup zaten ayrı). counts bilinmiyorsa false (iddia etmeyiz). */
export function isUnused(group, counts) {
  if (!group || counts == null) return false
  return group.active !== false && usageOf(group, counts) === 0
}

/** Grup etkin adres sayısı (birleşik alıcı) — boşsa adressiz. */
export function emailCount(group) {
  return Array.isArray(group?.emails) ? group.emails.length : 0
}

const normEmail = (e) => String(e ?? '').trim().toLowerCase()

/**
 * Gönderimde DÜŞECEK adresler (yalnız pasif hesaplara ait — sunucunun InactiveRecipientGuard'ı bunları süzer).
 * Sunucu söylemediyse (eski sunucu / pasif listesi okunamadı) null: "pasif adres yok" DEĞİL, bilinmiyor.
 */
export function inactiveEmailsOf(group) {
  return Array.isArray(group?.inactive_emails) ? group.inactive_emails : null
}

/**
 * Birleşik alıcı durumu (2026-11-13): toplam adres, düşecek (pasif), gerçekten teslim edilecek (etkin) ve "hepsi pasif".
 * Bilinmiyorsa inactive/effective null, known false — hiçbir uyarı türetilmez.
 */
export function recipientState(group) {
  const emails = Array.isArray(group?.emails) ? group.emails : []
  const total = emails.length
  const list = inactiveEmailsOf(group)
  if (list == null) return { total, inactive: null, effective: null, allPassive: false, known: false, dropped: [], delivered: emails }
  const set = new Set(list.map(normEmail))
  const dropped = emails.filter((e) => set.has(normEmail(e)))
  const delivered = emails.filter((e) => !set.has(normEmail(e)))
  return {
    total,
    inactive: dropped.length,
    effective: delivered.length,
    allPassive: total > 0 && delivered.length === 0,
    known: true,
    dropped,
    delivered,
  }
}

/** Aktif grupta en az bir adres pasif hesaba ait (bilinirken). */
export function hasPassiveRecipients(group) {
  if (!group || group.active === false) return false
  const s = recipientState(group)
  return s.known && s.inactive > 0
}

export const GROUP_FILTERS = ['all', 'unused', 'noEmails', 'passive', 'inactive']

/** Durum süzgeci (KPI kartından). counts bilinmiyorken 'unused' hiçbir şey döndürmez (iddia yok). */
export function filterGroups(groups = [], filter, counts) {
  if (!filter || filter === 'all') return groups
  if (filter === 'unused') return groups.filter((g) => isUnused(g, counts))
  if (filter === 'noEmails') return groups.filter((g) => g.active !== false && emailCount(g) === 0)
  if (filter === 'passive') return groups.filter((g) => hasPassiveRecipients(g))
  if (filter === 'inactive') return groups.filter((g) => g.active === false)
  return groups
}

/**
 * KPI sayımları. unused yalnız counts bilinirken anlamlı (bilinmiyorsa null → kart "—"). passive: adresi olan aktif
 * gruplardan biri için pasif listesi bilinmiyorsa null (kısmi sayı "hepsi bu" gibi görünmesin).
 */
export function groupKpis(groups = [], counts) {
  const active = groups.filter((g) => g.active !== false)
  const unused = counts == null ? null : active.filter((g) => usageOf(g, counts) === 0).length
  const noEmails = active.filter((g) => emailCount(g) === 0).length
  const inactive = groups.filter((g) => g.active === false).length
  const withEmails = active.filter((g) => emailCount(g) > 0)
  const passive = withEmails.some((g) => !recipientState(g).known)
    ? null
    : withEmails.filter((g) => recipientState(g).inactive > 0).length
  return { total: active.length, unused, noEmails, passive, inactive }
}

/**
 * Alarmı KİMSEYE ulaştırmayan gruplar: aktif, adresi var ama hepsi pasif hesaplara ait VE devrede (takımın varsayılanı
 * ya da en az bir izlemece kullanılıyor). Sunucu böyle bir grubu yine uygular (adres listesi boş değil), posta süzgeçte
 * düşer ve takım adresine DÖNÜLMEZ — sessiz kayıp. Kullanım bilinmiyorsa yalnız varsayılan grup sayılır.
 */
export function deadGroups(groups = [], counts) {
  return groups.filter((g) => {
    if (g.active === false) return false
    if (!recipientState(g).allPassive) return false
    const used = usageOf(g, counts)
    return g.is_default === true || (used != null && used > 0)
  })
}
