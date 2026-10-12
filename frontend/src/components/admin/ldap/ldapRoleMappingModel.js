/**
 * LDAP grup → rol eşlemesi — saf yardımcılar (2026-10-11). Sunucu kuralının (LdapRoleMapping.java) istemci aynası:
 * roller, "bugünkü kural" varsayılanı, DN / grup adı ayrımı, önizleme sonuç sırası. React yok; birim testi sınar.
 */

/** Eşlenebilen sistem rolleri — sunucudaki ROLES ile aynı sıra. */
export const ROLES = ['ADMIN', 'TEAM_ADMIN', 'AUDIT', 'USER']
/** Eşleşme yoksa bugünkü kural. */
export const FALLBACK_DERIVED = 'DERIVED'
/** Sunucudaki sınırlar (LdapRoleMapping.MAX_RULES / MAX_GROUP_LENGTH). */
export const MAX_RULES = 50
export const MAX_GROUP_LENGTH = 512
/** Yeni satırın rolü — en dar yetki (eskiden ADMIN geliyordu). */
export const NEW_ROW_ROLE = 'USER'

/** Önizleme sonuçları — gösterim sırası ve tonu. */
export const OUTCOMES = [
  { key: 'DEMOTE', tone: 'danger' },
  { key: 'PROMOTE', tone: 'warning' },
  { key: 'CHANGE', tone: 'warning' },
  { key: 'LAST_ADMIN', tone: 'info' },
  { key: 'LOCKED', tone: 'info' },
  { key: 'PROTECTED', tone: 'info' },
  { key: 'SAME', tone: 'muted' },
  { key: 'DERIVED', tone: 'muted' },
]

/** Tam DN mi (sunucu: isDnSpec) — "=" VE virgül; tek "CN=Ad" grup adıdır. */
export function isDnSpec(spec) {
  const s = String(spec || '')
  return s.includes('=') && s.includes(',')
}

/** Kaydedilecek / önizlenecek kurallar: boş grup atlanır, kırpılır, rol büyük harf; sıra korunur. */
export function cleanRules(rows) {
  const out = []
  for (const r of rows || []) {
    const group = String(r?.group ?? '').trim()
    if (!group) continue
    out.push({ group, role: String(r?.role || NEW_ROW_ROLE).toUpperCase() })
  }
  return out
}

/** Satır doğrulaması — sunucuyla aynı kurallar; hata anahtarı ya da null. */
export function ruleError(row) {
  const g = String(row?.group ?? '').trim()
  if (!g) return null
  if (g.length > MAX_GROUP_LENGTH) return 'ldap.rm.errTooLong'
  if (!ROLES.includes(String(row?.role || '').toUpperCase())) return 'ldap.rm.errRole'
  return null
}

/** Diziyi i ↔ i+dir yer değiştirerek döndürür (sınırda aynı dizi). Girdi değişmez. */
export function moveRow(rows, i, dir) {
  const list = [...(rows || [])]
  const j = i + dir
  if (i < 0 || i >= list.length || j < 0 || j >= list.length) return list
  ;[list[i], list[j]] = [list[j], list[i]]
  return list
}

/** Önizleme yanıtının değişiklik getirip getirmediği (yükseltme / düşürme / değişim). */
export function hasChanges(preview) {
  const c = preview?.counts || {}
  return ['PROMOTE', 'DEMOTE', 'CHANGE'].some((k) => Number(c[k] || 0) > 0)
}

/** Formdaki eşleme alanlarının kayıtlı hâlden farklı olup olmadığı (önizleme tazeliği için anahtar). */
export function mappingKey(form) {
  return JSON.stringify({ r: cleanRules(form?.role_mappings), f: form?.role_mapping_fallback || FALLBACK_DERIVED })
}
