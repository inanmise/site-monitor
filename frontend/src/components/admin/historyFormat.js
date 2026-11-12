// Yönetim değişiklik geçmişi — ortak saf yardımcılar (2026-11-13). Sekme geçmişi (AdminChangeHistory) ve çapraz
// değişiklik akışı (AdminChangeFeed) aynı rozet tonunu, fark/anlık görüntü ayrıştırmasını ve değer biçimini kullanır.

/** Olay türü / işlem → rozet tonu (`ev-*`, Denetim Kaydı ile aynı dağarcık → ToneBadge.EventBadge). */
export function eventKind(action) {
  const a = String(action || '')
  if (a.endsWith('CREATE') || a.endsWith('_ADD') || a === 'ADD') return 'ev-create'
  if (a.endsWith('DELETE') || a.endsWith('MEMBER_REMOVE') || a === 'ACCOUNT_LOCKED') return 'ev-delete'
  if (a.endsWith('UPDATE') || a.includes('UNLOCK') || a.includes('RESET') || a.includes('NOTIFICATIONS') || a.includes('ACCESS')
    || a.endsWith('SAVE') || a.endsWith('DEFAULT') || a.endsWith('REASSIGN')) return 'ev-edit'
  return 'ev-other'
}

/** JSON metni → düz nesne (dizi / ayrıştırılamayan → null). */
export function parseChanges(raw) {
  if (!raw) return null
  try { const o = JSON.parse(raw); return o && typeof o === 'object' && !Array.isArray(o) ? o : null } catch { return null }
}

/** Fark biçimi mi ({"alan":{"from":…,"to":…}}) — içerikten karar (eski kayıtlar da doğru okunsun). */
export function isDiffShape(obj) {
  const vals = Object.values(obj || {})
  return vals.length > 0 && vals.every(v => v && typeof v === 'object' && !Array.isArray(v) && ('from' in v || 'to' in v))
}

/** Değer → okunur metin (evet/hayır, boş, dizi, nesne). */
export function fmtValue(t, v) {
  if (v === true) return t('ng.histYes')
  if (v === false) return t('ng.histNo')
  if (v === null || v === undefined || v === '') return t('ng.histEmptyValue')
  if (Array.isArray(v)) return v.join(', ')
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v)
}
