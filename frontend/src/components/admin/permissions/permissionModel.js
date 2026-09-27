import { Eye, Pencil, Zap } from 'lucide-react'

/**
 * Yetki Yönetimi — saf veri modeli (2026-09-27 yeniden tasarım). Bileşenler burada yalnız okur; sunucu sözleşmesi
 * DEĞİŞMEDİ: GET /admin/permissions → `{ catalog, grants, can_edit }`, yazma HÜCRE BAŞINA PUT
 * `{ role, resource_key, action, allowed }` (toplu uç yok — toplu kaydetme istemcide sıralı yapılır).
 */

/** Sistem rolleri — sütun sırası. ADMIN kilitli (arayüzden kısılamaz, sunucu kuralı). `dot` = kalıcı renk kimliği. */
export const ROLES = [
  { key: 'ADMIN', locked: true, dot: 'bg-red-600', hintKey: 'perm.roleModelAdmin' },
  { key: 'TEAM_ADMIN', dot: 'bg-amber-500', hintKey: 'perm.roleModelTeamAdmin' },
  { key: 'USER', dot: 'bg-blue-600', hintKey: 'perm.roleModelUser' },
  { key: 'AUDIT', dot: 'bg-violet-500', hintKey: 'perm.roleModelAudit' },
]

/** Yetki türleri — hücre sütunları (her rolün altında bu sırayla). */
export const ACTIONS = [
  { key: 'view', Icon: Eye, labelKey: 'perm.view', shortKey: 'perm.viewShort' },
  { key: 'edit', Icon: Pencil, labelKey: 'perm.edit', shortKey: 'perm.editShort' },
  { key: 'execute', Icon: Zap, labelKey: 'perm.execute', shortKey: 'perm.executeShort' },
]
export const ACTION_KEYS = ACTIONS.map((a) => a.key)
export const actionOf = (key) => ACTIONS.find((a) => a.key === key) ?? ACTIONS[0]

/** Grup sırası; listede olmayan grup sona düşer. */
export const GROUP_ORDER = [
  'certificates', 'communication', 'management', 'alerts', 'monitoring', 'maintenance',
  'incidents', 'reports', 'logs', 'issues', 'tools', 'settings',
]

/** Hücre anahtarı — rol, kaynak, eylem. Kaynak anahtarlarında `|` geçmez (backend kataloğu). */
export const cellKey = (role, resourceKey, action) => `${role}|${resourceKey}|${action}`
export function parseCellKey(key) {
  const [role, resource_key, action] = key.split('|')
  return { role, resource_key, action }
}

/**
 * Katalog satırlarını KAYNAK başına birleştirir. Backend aynı anahtarı eylem başına ayrı satır olarak döndürebilir
 * (`notification.groups` view + edit, `monitoring.group`, `issues.login-reports` …) — eski ekran bunları iki ayrı satır
 * (ve yinelenen React anahtarı) olarak çiziyordu. Sıra: ilk görülme sırası; eylemler ACTIONS sırasında.
 */
export function mergeCatalog(catalog) {
  const byKey = new Map()
  for (const row of catalog || []) {
    if (!row?.resource_key) continue
    const prev = byKey.get(row.resource_key)
    const actions = new Set([...(prev?.actions ?? []), ...(row.actions ?? [])])
    const sensitive = new Set([...(prev?.sensitive ?? []), ...(row.sensitive ?? [])])
    byKey.set(row.resource_key, {
      resource_key: row.resource_key,
      group: prev?.group ?? row.group,
      actions: ACTION_KEYS.filter((a) => actions.has(a)),
      sensitive: ACTION_KEYS.filter((a) => sensitive.has(a)),
    })
  }
  return [...byKey.values()]
}

/** [[grup, kaynaklar]] — GROUP_ORDER sırasında. */
export function groupResources(resources) {
  const map = new Map()
  for (const item of resources) {
    if (!map.has(item.group)) map.set(item.group, [])
    map.get(item.group).push(item)
  }
  const rank = (g) => { const i = GROUP_ORDER.indexOf(g); return i === -1 ? 99 : i }
  return [...map.entries()].sort((a, b) => rank(a[0]) - rank(b[0]))
}

/** Sunucu satırları → hücre anahtarı → izinli mi. */
export function indexGrants(grants) {
  const map = new Map()
  for (const g of grants || []) map.set(cellKey(g.role, g.resource_key, g.action), g.allowed === true)
  return map
}

/**
 * Son değişiklik (kim, ne zaman) — yalnız düzenleme yetkisi olana dönen `updated_by/updated_at` alanlarından.
 * Salt okuyana bu alanlar gelmez (sunucu kuralı) → null.
 */
export function lastChange(grants) {
  let best = null
  for (const g of grants || []) {
    if (!g?.updated_at) continue
    if (!best || String(g.updated_at) > String(best.at)) best = { by: g.updated_by || null, at: g.updated_at }
  }
  return best
}

/** Rol özeti: açık / toplam ve türe göre dağılım. Kilitli rol (ADMIN) her hücrede açık sayılır. */
export function roleSummary(resources, role, isOn) {
  const byKind = Object.fromEntries(ACTION_KEYS.map((k) => [k, { on: 0, total: 0 }]))
  let on = 0
  let total = 0
  for (const item of resources) {
    for (const a of item.actions) {
      const granted = role.locked || isOn(role.key, item.resource_key, a)
      byKind[a].total++
      total++
      if (granted) { byKind[a].on++; on++ }
    }
  }
  return { on, total, byKind }
}

/** Aramada Türkçe büyük/küçük harf farkını (İ/ı) eşitleyen katlama. */
export function fold(s) {
  return String(s ?? '').toLocaleLowerCase('tr-TR').replace(/ı/g, 'i').trim()
}

/**
 * Görünen kaynaklar. `kinds`: gösterilen türler (kaynakta en az biri olmalı); `query`: anahtar, açıklama ya da grup
 * adında geçer; `onlyPending`: kaydedilmemiş değişikliği olan; `onlySensitive`: gösterilen türlerden biri hassas.
 */
export function filterResources(resources, { query = '', kinds = ACTION_KEYS, onlyPending = false, onlySensitive = false, pending, t }) {
  const q = fold(query)
  const pendingResources = new Set()
  if (onlyPending && pending) for (const k of pending.keys()) pendingResources.add(parseCellKey(k).resource_key)
  return resources.filter((item) => {
    const shown = item.actions.filter((a) => kinds.includes(a))
    if (shown.length === 0) return false
    if (onlySensitive && !item.sensitive.some((a) => kinds.includes(a))) return false
    if (onlyPending && !pendingResources.has(item.resource_key)) return false
    if (!q) return true
    const hay = [item.resource_key, t?.(`perm.res.${item.resource_key}`), t?.(`perm.group.${item.group}`)]
    return hay.some((s) => fold(s).includes(q))
  })
}
