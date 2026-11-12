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

export const GROUP_FILTERS = ['all', 'unused', 'noEmails', 'inactive']

/** Durum süzgeci (KPI kartından). counts bilinmiyorken 'unused' hiçbir şey döndürmez (iddia yok). */
export function filterGroups(groups = [], filter, counts) {
  if (!filter || filter === 'all') return groups
  if (filter === 'unused') return groups.filter((g) => isUnused(g, counts))
  if (filter === 'noEmails') return groups.filter((g) => g.active !== false && emailCount(g) === 0)
  if (filter === 'inactive') return groups.filter((g) => g.active === false)
  return groups
}

/** KPI sayımları. unused yalnız counts bilinirken anlamlı (bilinmiyorsa null → kart "—"). */
export function groupKpis(groups = [], counts) {
  const active = groups.filter((g) => g.active !== false)
  const unused = counts == null ? null : active.filter((g) => usageOf(g, counts) === 0).length
  const noEmails = active.filter((g) => emailCount(g) === 0).length
  const inactive = groups.filter((g) => g.active === false).length
  return { total: active.length, unused, noEmails, inactive }
}
