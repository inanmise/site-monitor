/**
 * Takım erişim paylaşımı — SAF model (2026-10-12, kullanıcı isteği: "bir ekip kendi izlemelerini başka bir takıma ya da
 * kişiye açabilsin; görüntüleme / çalıştırma / düzenleme / silme; takım yöneticisi yönetsin").
 *
 * Kural sunucuda (`TeamAccessGrantService`); burada yalnız sunucu satırları camelCase'e çevrilir, özet / süzgeç hesaplanır
 * ve kayıt gövdeleri kurulur. Düzeyler KÜMÜLATİF: VIEW < EXECUTE < EDIT < DELETE (Java `TeamAccessLevel` ile aynı sıra —
 * `teamAccessModel.test.js` Java kaynağını okur).
 */
import { changedFields } from '../../../utils/changedFields.js'

export const ACCESS_LEVELS = Object.freeze(['VIEW', 'EXECUTE', 'EDIT', 'DELETE'])
export const GRANTEE_TYPES = Object.freeze(['TEAM', 'USER'])
/** "Yakında bitiyor" eşiği (gün). */
export const EXPIRING_DAYS = 7

const DAY_MS = 86_400_000

function str(v) { return typeof v === 'string' && v.trim() ? v.trim() : null }

export function levelRank(level) {
  const i = ACCESS_LEVELS.indexOf(level)
  return i < 0 ? -1 : i
}

/** Düzey bu düzeyi içeriyor mu (kümülatif). Bilinmeyen düzey hiçbir şeyi içermez. */
export function levelIncludes(level, min) {
  const a = levelRank(level)
  return a >= 0 && a >= levelRank(min)
}

/** Sunucu satırı → görünüm satırı. */
export function normalizeGrant(raw) {
  const r = raw && typeof raw === 'object' ? raw : {}
  return {
    id: r.id ?? null,
    ownerTeamId: r.owner_team_id ?? null,
    ownerTeamName: str(r.owner_team_name),
    granteeType: GRANTEE_TYPES.includes(r.grantee_type) ? r.grantee_type : 'TEAM',
    granteeTeamId: r.grantee_team_id ?? null,
    granteeUserId: r.grantee_user_id ?? null,
    granteeName: str(r.grantee_name),
    // Alıcı (kişi / takım) ya da paylaşan takım pasifse paylaşım uygulanmaz (sunucu her satırda gönderir); alan yoksa null
    granteeActive: typeof r.grantee_active === 'boolean' ? r.grantee_active : null,
    ownerActive: typeof r.owner_active === 'boolean' ? r.owner_active : null,
    level: ACCESS_LEVELS.includes(r.level) ? r.level : null,
    expiresOn: str(r.expires_on),
    expiresAt: str(r.expires_at),
    expired: r.expired === true,
    note: str(r.note),
    createdAt: str(r.created_at),
    createdBy: str(r.created_by),
    updatedAt: str(r.updated_at),
    updatedBy: str(r.updated_by),
    canEdit: r.can_edit === true,
  }
}

/** Bitişe kalan gün (bitiş anı UTC ISO; yoksa null = süresiz). */
export function daysLeft(grant, now = Date.now()) {
  if (!grant?.expiresAt) return null
  const end = Date.parse(`${grant.expiresAt}Z`)
  if (!Number.isFinite(end)) return null
  return Math.ceil((end - now) / DAY_MS)
}

/** Durum: `expired` · `inactive` (alıcı ya da paylaşan takım pasif — uygulanmaz) · `expiring` (≤ 7 gün) · `active`. */
export function grantState(grant, now = Date.now()) {
  if (grant?.expired) return 'expired'
  const d = daysLeft(grant, now)
  if (d != null && d <= 0) return 'expired'
  if (grant?.granteeActive === false || grant?.ownerActive === false) return 'inactive'
  if (d != null && d <= EXPIRING_DAYS) return 'expiring'
  return 'active'
}

/** KPI özeti: toplam, takımlara, kişilere, yakında bitecek, geçersiz (süresi dolmuş + pasif kişi). */
export function summarize(grants, now = Date.now()) {
  const list = Array.isArray(grants) ? grants : []
  let teams = 0
  let people = 0
  let expiring = 0
  let inactive = 0
  for (const g of list) {
    if (g.granteeType === 'USER') people++
    else teams++
    const s = grantState(g, now)
    if (s === 'expiring') expiring++
    if (s === 'expired' || s === 'inactive') inactive++
  }
  return { total: list.length, teams, people, expiring, inactive }
}

/** Türkçe duyarlı, büyük/küçük harf duyarsız arama. */
const fold = (s) => String(s ?? '').toLocaleLowerCase('tr')

/** Süzgeç: `q` (takım / alıcı / not), `team` (paylaşan), `type` (TEAM|USER), `level`, `state` (active|expiring|inactive). */
export function filterGrants(grants, { q = '', team = '', type = '', level = '', state = '' } = {}, now = Date.now()) {
  const needle = fold(q).trim()
  return (Array.isArray(grants) ? grants : []).filter((g) => {
    if (team && String(g.ownerTeamId) !== String(team)) return false
    if (type && g.granteeType !== type) return false
    if (level && g.level !== level) return false
    if (state) {
      const s = grantState(g, now)
      if (state === 'inactive' ? !(s === 'expired' || s === 'inactive') : s !== state) return false
    }
    if (needle && ![g.ownerTeamName, g.granteeName, g.note, g.createdBy].some((v) => fold(v).includes(needle))) return false
    return true
  })
}

/** Sıralama: paylaşan takım A→Z (Türkçe), sonra alıcı adı; aynıysa id. */
const collator = new Intl.Collator('tr', { sensitivity: 'base', numeric: true })
export function sortGrants(grants) {
  return [...(Array.isArray(grants) ? grants : [])].sort((a, b) =>
    collator.compare(a.ownerTeamName || '', b.ownerTeamName || '')
    || collator.compare(a.granteeName || '', b.granteeName || '')
    || (Number(a.id) || 0) - (Number(b.id) || 0))
}

/** Yeni paylaşım formu. */
export function emptyForm(ownerTeamId = null) {
  return { ownerTeamId: ownerTeamId ?? null, granteeType: 'TEAM', granteeTeamId: null, granteeUserId: null,
    level: 'VIEW', expiresOn: '', note: '' }
}

/** Düzenleme formu (yalnız düzey / bitiş / not değişir). */
export function formOf(grant) {
  return { ownerTeamId: grant.ownerTeamId, granteeType: grant.granteeType, granteeTeamId: grant.granteeTeamId,
    granteeUserId: grant.granteeUserId, level: grant.level || 'VIEW', expiresOn: grant.expiresOn || '', note: grant.note || '' }
}

/** POST gövdesi. */
export function createPayload(form) {
  const out = {
    owner_team_id: form.ownerTeamId,
    grantee_type: form.granteeType,
    level: form.level,
  }
  if (form.granteeType === 'TEAM') out.grantee_team_id = form.granteeTeamId
  else out.grantee_user_id = form.granteeUserId
  if (form.expiresOn) out.expires_on = form.expiresOn
  if (form.note && form.note.trim()) out.note = form.note.trim()
  return out
}

/** PUT gövdesi — YALNIZ değişen alanlar (eşzamanlı düzenleme diğerini ezmesin); boşsa istek atılmaz. */
export function updatePayload(original, form) {
  const before = { level: original.level || 'VIEW', expires_on: original.expiresOn || '', note: original.note || '' }
  const after = { level: form.level, expires_on: form.expiresOn || '', note: (form.note || '').trim() }
  return changedFields(after, before)
}

/** Form doğrulaması (istemci tarafı ilk süzgeç; asıl kural sunucuda) → alan → anahtar. */
export function formErrors(form, { isNew }) {
  const e = {}
  if (isNew) {
    if (form.ownerTeamId == null) e.ownerTeamId = 'access.err.owner'
    if (form.granteeType === 'TEAM' && form.granteeTeamId == null) e.granteeTeamId = 'access.err.granteeTeam'
    if (form.granteeType === 'TEAM' && form.granteeTeamId != null && String(form.granteeTeamId) === String(form.ownerTeamId)) {
      e.granteeTeamId = 'access.err.sameTeam'
    }
    if (form.granteeType === 'USER' && form.granteeUserId == null) e.granteeUserId = 'access.err.granteeUser'
  }
  if (!ACCESS_LEVELS.includes(form.level)) e.level = 'access.err.level'
  if ((form.note || '').trim().length > 500) e.note = 'access.err.note'
  return e
}
