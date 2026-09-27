import { fold, groupResources, indexGrants, mergeCatalog, cellKey } from '../permissions/permissionModel.js'
import { relTime } from '../useractivity/uactModel.js'

/**
 * Kullanıcı ayrıntısı — saf veri modeli (2026-09-27 yeniden tasarım). Ağ yok, DOM yok; bileşenler yalnız okur.
 * Veri kaynakları DEĞİŞMEDİ: kullanıcı satırı (`/admin/users/search`), üyelik izi, eskalasyon kayıtları, yetki matrisi,
 * push açıklaması ve `USER` denetim geçmişi.
 */

/** "Uzun süredir girmemiş" eşiği (gün) — UserManager / AdminOverviewService.DORMANT_DAYS ile aynı. */
export const DORMANT_DAYS = 90

/** Görünen ad: display_name → ad + soyad → kullanıcı adı. */
export function fullNameOf(user) {
  const joined = [user?.first_name, user?.last_name].filter(Boolean).join(' ')
  return (user?.display_name || joined || user?.username || '').trim() || String(user?.id ?? '')
}

/** Sunucu zamanı (UTC, çoğu kez sonek yok) → ms; çözülemezse null. */
export function parseAt(iso) {
  if (!iso) return null
  const s = /[zZ]|[+-]\d\d:?\d\d$/.test(iso) ? iso : `${iso}Z`
  const ms = Date.parse(s)
  return Number.isFinite(ms) ? ms : null
}

/** Son giriş durumu: hiç girmemiş / uyuyan (90+ gün) / göreli süre. */
export function signInState(user, now = Date.now()) {
  if (!user?.last_login_at) return { never: true, dormant: false, rel: null }
  const at = parseAt(user.last_login_at)
  const dormant = at != null && now - at >= DORMANT_DAYS * 86_400_000
  return { never: false, dormant, rel: relTime(user.last_login_at, now) }
}

/**
 * Etkin kilitler — satırın taşıdığı bayraklar, önem sırasıyla. `unlockKey`: UserManager'daki kilit açma
 * işleyicisinin anahtarı (perm → unlock, role → roleUnlock, org → orgRoleUnlock, team → teamUnlock).
 */
export function locksOf(user) {
  return [
    user?.permanent_lock && { key: 'perm', severe: true },
    user?.role_locked && { key: 'role', severe: false },
    user?.org_role_locked && { key: 'org', severe: false },
    user?.team_locked && { key: 'team', severe: false },
  ].filter(Boolean)
}

/** Kullanıcı satırındaki takım kimlikleri (birincil dâhil, tekil). */
export function teamIdsOf(user) {
  const ids = user?.team_ids ?? user?.teamIds ?? []
  const set = new Set(ids.map(Number))
  if (user?.team_id != null) set.add(Number(user.team_id))
  return Array.from(set)
}

/**
 * Etkin yetkiler — rolün (resource, action) çiftleri, yetki matrisinin kendi modeliyle (aynı birleştirme ve grup sırası).
 * ADMIN rolü kilitlidir: sunucu kuralı gereği her hücre açıktır (Yetki Yönetimi ekranıyla aynı okuma).
 * Dönen: `{ groups: [[grup, [{ resource_key, group, actions: [{ key, granted }] }]]], granted, total }`.
 */
export function effectivePermissions(matrix, role) {
  if (!matrix) return null
  const resources = mergeCatalog(matrix.catalog)
  const grants = indexGrants(matrix.grants)
  const locked = role === 'ADMIN'
  let granted = 0
  let total = 0
  const rows = resources.map((r) => {
    const actions = r.actions.map((a) => {
      const on = locked || grants.get(cellKey(role, r.resource_key, a)) === true
      total++
      if (on) granted++
      return { key: a, granted: on }
    })
    return { resource_key: r.resource_key, group: r.group, actions }
  })
  return { groups: groupResources(rows), granted, total, locked }
}

/** Arama + "yalnız verilenler" süzgeci; boş kalan gruplar düşer. */
export function filterPermissions(perms, { query = '', onlyGranted = true, t } = {}) {
  if (!perms) return []
  const q = fold(query)
  const out = []
  for (const [group, rows] of perms.groups) {
    const kept = rows.filter((r) => {
      if (onlyGranted && !r.actions.some((a) => a.granted)) return false
      if (!q) return true
      const hay = [r.resource_key, t?.(`perm.res.${r.resource_key}`), t?.(`perm.group.${group}`)]
      return hay.some((s) => fold(s).includes(q))
    })
    if (kept.length) out.push([group, kept])
  }
  return out
}

/** Denetim olay türü → rozet tonu (`ev-*`; Değişiklik Geçmişi ile aynı sınıflama). */
export function eventKind(action) {
  const a = String(action || '')
  if (a.endsWith('CREATE') || a.endsWith('MEMBER_ADD')) return 'ev-create'
  if (a.endsWith('DELETE') || a.endsWith('MEMBER_REMOVE') || a === 'ACCOUNT_LOCKED') return 'ev-delete'
  if (a.endsWith('UPDATE') || a.includes('UNLOCK') || a.includes('RESET') || a.includes('SYNC') || a.includes('PASSWORD')) return 'ev-edit'
  return 'ev-other'
}

function parseChanges(raw) {
  if (!raw) return null
  try { const o = JSON.parse(raw); return o && typeof o === 'object' && !Array.isArray(o) ? o : null } catch { return null }
}
function isDiffShape(obj) {
  const vals = Object.values(obj)
  return vals.length > 0 && vals.every((v) => v && typeof v === 'object' && !Array.isArray(v) && ('from' in v || 'to' in v))
}

/** Değer → metin (evet/hayır/boş dâhil). */
export function formatValue(t, v) {
  if (v === true) return t('ng.histYes')
  if (v === false) return t('ng.histNo')
  if (v === null || v === undefined || v === '') return t('ng.histEmptyValue')
  if (Array.isArray(v)) return v.join(', ')
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v)
}

/** Alan adı → etiket (`hist.f.*`); bilinmeyen alan ham adıyla (kaybolmaz). */
export function fieldLabel(t, f) {
  const k = `hist.f.${f}`
  const s = t(k)
  return s === k ? f : s
}

/** Eylem etiketi (`hist.act.*`); yeni bir olay türü sessizce kaybolmasın diye ham adın okunur hâli. */
export function actionLabel(t, a) {
  const k = `hist.act.${a}`
  const s = t(k)
  return s === k ? String(a || '').toLowerCase().replace(/_/g, ' ') : s
}

/** Geçmiş satırı modeli: özet çipleri (en çok `limit`) + tam açılım verisi (fark ya da anlık görüntü). */
export function changeModel(row, t, limit = 4) {
  const parsed = parseChanges(row?.changes)
  const diff = parsed && isDiffShape(parsed) ? parsed : null
  const all = diff
    ? Object.entries(diff).map(([f, c]) => {
      const from = formatValue(t, c?.from)
      const to = formatValue(t, c?.to)
      return { key: f, label: fieldLabel(t, f), diff: true, from, to, full: `${fieldLabel(t, f)}: ${from} → ${to}` }
    })
    : parsed ? Object.keys(parsed).map((f) => ({ key: f, label: fieldLabel(t, f), diff: false, full: `${fieldLabel(t, f)}: ${formatValue(t, parsed[f])}` })) : []
  return { parsed, diff, all, chips: all.slice(0, limit), more: Math.max(0, all.length - limit) }
}
