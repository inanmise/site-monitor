/**
 * 7/24 izleme ekibi TAKIMLARI (2026-10-04) — Ayarlar → 7/24 İzleme Ekibi bölümünün SAF modeli (React'siz, test edilebilir).
 *
 * Sunucu sözleşmesi (`/api/admin/noc/operator-teams`): GET → `{ team_ids, teams: [{id, name, active}], operator_count,
 * updated_at, updated_by_name, max_teams }`; önizleme → `{ teams: [{id, name, active, member_count}], user_count,
 * users: [{user_id, display_name, team_ids, team_names, system_role}], truncated }`; PUT gövdesi `{ teamIds: [...] }`.
 */

/** Sayı kimlik listesi — tekil, sıralı (seçim sırası korunur), geçersizler atılır. */
export function normalizeIds(ids) {
  const out = []
  for (const v of Array.isArray(ids) ? ids : []) {
    const n = Number(v)
    if (Number.isFinite(n) && n > 0 && !out.includes(n)) out.push(n)
  }
  return out
}

/** Ayar görüntüsü → { ids, updatedAt, updatedByName, operatorCount, maxTeams }. */
export function normalizeSettings(data) {
  const d = data && typeof data === 'object' ? data : {}
  return {
    ids: normalizeIds(d.team_ids),
    updatedAt: d.updated_at ?? null,
    updatedByName: d.updated_by_name ?? null,
    operatorCount: Number(d.operator_count) || 0,
    maxTeams: Number(d.max_teams) || 50,
  }
}

/** Kayıtlı ve düzenlenen seçim farklı mı (sıradan bağımsız). */
export function isDirty(saved, draft) {
  const a = [...normalizeIds(saved)].sort((x, y) => x - y)
  const b = [...normalizeIds(draft)].sort((x, y) => x - y)
  return a.length !== b.length || a.some((v, i) => v !== b[i])
}

/** Eklenen / çıkarılan takımlar (onay penceresi ve denetim özeti). */
export function diffIds(saved, draft) {
  const s = normalizeIds(saved)
  const d = normalizeIds(draft)
  return { added: d.filter((id) => !s.includes(id)), removed: s.filter((id) => !d.includes(id)) }
}

/** PUT gövdesi. */
export const savePayload = (draft) => normalizeIds(draft)

/**
 * Takım rehberi satırları → seçici seçenekleri: ada göre (Türkçe, büyük/küçük harf duyarsız) sıralı; pasif takım etiketi
 * işaretlenir; rehberde olmayan ama SEÇİLİ kimlik (silinmiş takım) adı olmadan da görünür (kaybolmasın).
 */
export function teamOptions(directory, selected, inactiveSuffix = '') {
  const rows = Array.isArray(directory) ? directory : []
  const seen = new Set()
  const out = []
  for (const tm of rows) {
    const id = Number(tm?.id)
    if (!Number.isFinite(id) || seen.has(id)) continue
    seen.add(id)
    const inactive = tm.active === false
    out.push({ value: id, label: `${tm.name ?? id}${inactive && inactiveSuffix ? ` (${inactiveSuffix})` : ''}`, name: tm.name ?? String(id), inactive })
  }
  for (const id of normalizeIds(selected)) {
    if (!seen.has(id)) out.push({ value: id, label: `#${id}`, name: `#${id}`, inactive: false })
  }
  const coll = new Intl.Collator('tr', { sensitivity: 'base', numeric: true })
  return out.sort((a, b) => coll.compare(a.name, b.name))
}

/** Önizleme yanıtı → { teams: Map(id → {member_count, active, name}), userCount, users, truncated }. */
export function normalizePreview(data) {
  const d = data && typeof data === 'object' ? data : {}
  const teams = new Map()
  for (const tm of Array.isArray(d.teams) ? d.teams : []) {
    const id = Number(tm?.id)
    if (Number.isFinite(id)) teams.set(id, { name: tm.name, active: tm.active !== false, memberCount: Number(tm.member_count) || 0 })
  }
  return {
    teams,
    userCount: Number(d.user_count) || 0,
    users: Array.isArray(d.users) ? d.users : [],
    truncated: d.truncated === true,
  }
}
