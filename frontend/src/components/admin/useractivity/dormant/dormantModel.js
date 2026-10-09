import { toCsv } from '../../../../utils/csvExport.js'
import { ROLES as BULK_ROLES, SOURCES as BULK_SOURCES } from '../../bulkDeactivateModel.js'
import { splitDisplayName } from '../directoryModel.js'

/**
 * "Atıl hesaplar" görünümünün saf modeli (2026-10-09, kullanıcı isteği: "Dormant accounts sayfasını shadcn ile yeniden
 * tasarlayalım … istatistiklerimizi sunalım"). Veri `details.dormant` (UserActivityService — aktif hesaplar arasında 30+
 * gündür girmeyen ya da hiç girmemiş; en eski önce) + `details.dormant_meta` + `summary.total_users`. Ek istek YOK.
 *
 * Kova sınırları, süzgeç, sıralama, istatistik, CSV ve toplu pasife alma / Kullanıcılar sayfası derin bağlantı
 * paramları burada — bileşen yalnız çizer. Gün sayısı SUNUCU saatinden (`inactive_days`, `account_age_days`); eski
 * yük (alan yok) için istemci `last_login_at` / `created_at`'ten hesaplar.
 */

/** Atıl eşiği (UserActivityService.DORMANT_DAYS ile aynı) ve "yeni hesap" penceresi (hiç girmemiş ama yeni açılmış). */
export const DORMANT_DAYS = 30
export const NEW_ACCOUNT_DAYS = 30

/** Hareketsizlik kovaları: 30–89 · 90–179 · 180–364 · 365+ · hiç girmemiş (sıra = gösterim sırası). */
export const BUCKETS = Object.freeze(['d30', 'd90', 'd180', 'd365', 'never'])
export const BUCKET_MIN_DAYS = Object.freeze({ d30: 30, d90: 90, d180: 180, d365: 365 })

export const FACETS = Object.freeze(['bucket', 'source', 'role', 'team'])
export const EMPTY_FILTERS = Object.freeze({ q: '', bucket: [], source: [], role: [], team: [] })

/** Sıralama seçenekleri; varsayılan "en uzun süredir girmeyen önce". */
export const SORTS = Object.freeze(['idle_desc', 'idle_asc', 'name_asc', 'team_asc'])
export const DEFAULT_SORT = 'idle_desc'

/** Takımsız hesapların süzgeç anahtarı. */
export const NO_TEAM = 'none'

const DAY_MS = 86_400_000
const collator = new Intl.Collator('tr', { sensitivity: 'base', numeric: true })
const fold = (s) => String(s ?? '').toLocaleLowerCase('tr')

function parseIso(iso) {
  if (!iso) return NaN
  const s = String(iso)
  return Date.parse(/[zZ]|[+-]\d\d:?\d\d$/.test(s) ? s : s + 'Z')
}

/** ISO damgasından bu yana tam gün; boş / bozuk → null, gelecek → 0. */
export function daysSince(iso, now = Date.now()) {
  const at = parseIso(iso)
  if (Number.isNaN(at)) return null
  return Math.max(0, Math.floor((now - at) / DAY_MS))
}

const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : null)

/** Son girişten beri gün (sunucu değeri önce); hiç girmemişse null. */
export function inactiveDaysOf(row, now = Date.now()) {
  if (!row?.last_login_at) return null
  return num(row.inactive_days) ?? daysSince(row.last_login_at, now)
}

/** Hesabın yaşı (gün); bilinmiyorsa null. */
export function accountAgeOf(row, now = Date.now()) {
  return num(row?.account_age_days) ?? daysSince(row?.created_at, now)
}

/** Hareketsizlik kovası. Atıl listesindeki satır en az 30 gün; saat kayması 30'un altına düşürse yine d30. */
export function bucketOf(days) {
  if (days == null) return 'never'
  if (days >= BUCKET_MIN_DAYS.d365) return 'd365'
  if (days >= BUCKET_MIN_DAYS.d180) return 'd180'
  if (days >= BUCKET_MIN_DAYS.d90) return 'd90'
  return 'd30'
}

/** Kimlik kaynağı anahtarı: LDAP / LOCAL (boş → LOCAL, sunucudaki varsayılan); başka bir değer büyük harfle aynen. */
export function sourceOf(row) {
  const s = String(row?.auth_source ?? '').trim().toUpperCase()
  return s || 'LOCAL'
}

/** Sistem rolü anahtarı (boş → USER, AppUser varsayılanı). */
export const roleOf = (row) => String(row?.system_role || 'USER').toUpperCase()

/** Takım süzgeç anahtarı: kimlik varsa kimlik, yoksa ad (eski yük), hiçbiri yoksa NO_TEAM. */
export function teamKeyOf(row) {
  if (row?.team_id != null && row.team_id !== '') return String(row.team_id)
  if (row?.team_name) return `name:${row.team_name}`
  return NO_TEAM
}

/** Ad (bölüm eki ayıklanmış) — arama, sıralama ve kart başlığı aynı adı kullanır. */
export const nameOfRow = (row) => splitDisplayName(row?.display_name, row?.department).name || row?.username || ''

/**
 * Satırları görünüm alanlarıyla zenginleştirir (asıl alanlar korunur): `days` (son girişten beri), `age` (hesap yaşı),
 * `idle` (sıralama ölçütü — hiç girmemişte hesap yaşı; ikisi de yoksa sonsuz → en başta, sunucu sırasıyla aynı),
 * `bucket`, `source`, `role`, `team_key`, `is_new` (hiç girmemiş + 30 günden yeni hesap: atıl değil, henüz başlamamış).
 */
export function enrichDormant(rows, now = Date.now()) {
  return (rows || []).filter(Boolean).map((r) => {
    const days = inactiveDaysOf(r, now)
    const age = accountAgeOf(r, now)
    const never = days == null
    return {
      ...r,
      days,
      age,
      idle: never ? (age ?? Number.POSITIVE_INFINITY) : days,
      bucket: bucketOf(days),
      source: sourceOf(r),
      role: roleOf(r),
      team_key: teamKeyOf(r),
      is_new: never && age != null && age < NEW_ACCOUNT_DAYS,
    }
  })
}

function median(sorted) {
  if (!sorted.length) return null
  const mid = Math.floor(sorted.length / 2)
  return sorted.length % 2 ? sorted[mid] : Math.round((sorted[mid - 1] + sorted[mid]) / 2)
}

const byCountThenLabel = (a, b) => b.count - a.count || collator.compare(String(a.label ?? a.key), String(b.label ?? b.key))

/**
 * İstatistikler (zenginleştirilmiş satırlar üzerinden). `totalUsers` = aktif hesap sayısı (summary.total_users);
 * pay bu sayıya göre (yoksa null). Takımlar sayıya göre azalan, takımsız ayrı (`noTeam`).
 */
export function dormantStats(items, totalUsers) {
  const list = items || []
  const buckets = Object.fromEntries(BUCKETS.map((b) => [b, 0]))
  const src = new Map(), roles = new Map(), teams = new Map()
  let never = 0, neverNew = 0, noEmail = 0, locked = 0
  const days = []
  for (const r of list) {
    buckets[r.bucket] = (buckets[r.bucket] || 0) + 1
    if (r.days == null) { never++; if (r.is_new) neverNew++ } else days.push(r.days)
    if (r.has_email === false) noEmail++
    if (r.permanent_lock) locked++
    src.set(r.source, (src.get(r.source) || 0) + 1)
    roles.set(r.role, (roles.get(r.role) || 0) + 1)
    const tk = r.team_key
    const cur = teams.get(tk) || { key: tk, id: r.team_id ?? null, label: r.team_name || null, count: 0 }
    cur.count++
    teams.set(tk, cur)
  }
  days.sort((a, b) => a - b)
  const total = list.length
  const tu = Number(totalUsers)
  const allTeams = [...teams.values()]
  return {
    total,
    never,
    neverNew,
    noEmail,
    locked,
    longTerm: buckets.d180 + buckets.d365,
    share: tu > 0 ? pctOf(total, tu) : null,
    totalUsers: tu > 0 ? tu : null,
    buckets,
    bySource: [...src.entries()].map(([key, count]) => ({ key, count })).sort(byCountThenLabel),
    byRole: [...roles.entries()].map(([key, count]) => ({ key, count })).sort(byCountThenLabel),
    teams: allTeams.filter((x) => x.key !== NO_TEAM).sort(byCountThenLabel),
    noTeam: teams.get(NO_TEAM)?.count || 0,
    medianDays: median(days),
    maxDays: days.length ? days[days.length - 1] : null,
  }
}

/** Pay (%) — tam sayıya yuvarlanmış, 0 < pay < 1 ise 1 (çubuk görünsün); toplam 0 ise 0. */
export function pctOf(count, total) {
  const c = Number(count) || 0, n = Number(total) || 0
  if (n <= 0 || c <= 0) return 0
  return Math.max(1, Math.min(100, Math.round((c / n) * 100)))
}

const has = (arr, v) => Array.isArray(arr) && arr.includes(v)
const set = (arr) => Array.isArray(arr) && arr.length > 0

/** Satır süzgece uyuyor mu? Arama: ad, kullanıcı adı, takım, bölüm (Türkçe büyük/küçük harf duyarsız). */
export function dormantMatches(r, f = EMPTY_FILTERS) {
  const q = fold(f.q).trim()
  if (q) {
    const hay = [r.display_name, r.username, r.team_name, r.department].map(fold)
    if (!hay.some((v) => v.includes(q))) return false
  }
  if (set(f.bucket) && !has(f.bucket, r.bucket)) return false
  if (set(f.source) && !has(f.source, r.source)) return false
  if (set(f.role) && !has(f.role, r.role)) return false
  if (set(f.team) && !has(f.team, r.team_key)) return false
  return true
}

/** Etkin faset sayısı (arama hariç). */
export const facetCount = (f) => FACETS.reduce((n, k) => n + (f?.[k]?.length || 0), 0)
export const hasAnyFilter = (f) => facetCount(f) > 0 || !!String(f?.q || '').trim()

/** Seçili değeri aç/kapa (çoklu seçim). */
export function toggleValue(arr, v) {
  const cur = Array.isArray(arr) ? arr : []
  return cur.includes(v) ? cur.filter((x) => x !== v) : [...cur, v]
}

/**
 * Faset seçenekleri + sayılar. Sayı, DİĞER fasetler (ve arama) uygulanmışken o değeri seçince kaç satır kalacağıdır —
 * "bu seçenek kaç sonuç verir" sorusunun dürüst cevabı. Kovalar sabit sırada (boş olanlar da: 0 bilgi taşır), diğerleri
 * sayıya göre azalan; takımsız en sonda.
 */
export function facetOptions(items, f = EMPTY_FILTERS) {
  const out = {}
  for (const facet of FACETS) {
    const others = { ...f, [facet]: [] }
    const pool = (items || []).filter((r) => dormantMatches(r, others))
    const counts = new Map()
    const field = facet === 'team' ? 'team_key' : facet
    for (const r of pool) counts.set(r[field], (counts.get(r[field]) || 0) + 1)
    if (facet === 'bucket') {
      out.bucket = BUCKETS.map((b) => ({ value: b, count: counts.get(b) || 0 }))
      continue
    }
    // Hiç satırı olmayan ama SEÇİLİ değer listede kalsın (seçim kaldırılabilsin)
    for (const v of f[facet] || []) if (!counts.has(v)) counts.set(v, 0)
    const names = new Map()
    if (facet === 'team') for (const r of items || []) if (r.team_name) names.set(r.team_key, r.team_name)
    out[facet] = [...counts.entries()]
      .map(([value, count]) => ({ value, count, name: facet === 'team' ? (names.get(value) || null) : null }))
      .sort((a, b) => (a.value === NO_TEAM) - (b.value === NO_TEAM) || b.count - a.count
        || collator.compare(String(a.name ?? a.value), String(b.name ?? b.value)))
  }
  return out
}

/** Sıralama. Eşitlikte ad (Türkçe harmanlama), en son kullanıcı adı — kararlı. */
export function sortDormant(items, sort = DEFAULT_SORT) {
  const byName = (a, b) => collator.compare(nameOfRow(a), nameOfRow(b)) || collator.compare(String(a.username), String(b.username))
  const list = [...(items || [])]
  switch (sort) {
    case 'idle_asc': return list.sort((a, b) => a.idle - b.idle || byName(a, b))
    case 'name_asc': return list.sort(byName)
    case 'team_asc': return list.sort((a, b) => {
      const ta = a.team_name || '', tb = b.team_name || ''
      if (!ta !== !tb) return ta ? -1 : 1                  // takımsızlar sonda
      return collator.compare(ta, tb) || b.idle - a.idle || byName(a, b)
    })
    default: return list.sort((a, b) => b.idle - a.idle || byName(a, b))
  }
}

/** Görünen (süzülmüş + sıralanmış) listenin CSV'si — BOM'lu UTF-8, formül nötrlemeli (utils/csvExport → utils/csv). */
export function dormantCsv(items, t) {
  const head = [
    t('uact.colUser'), t('uact.detailDisplayName'), t('uact.colRole'), t('uact.detailOrgRole'), t('uact.colTeam'),
    t('uact.colAuthSource'), t('dorm.colBucket'), t('dorm.colInactiveDays'), t('uact.colLastLogin'), t('uact.colCreated'),
    t('dorm.colAccountAge'), t('dorm.colHasEmail'), t('usr.permLocked'),
  ]
  return toCsv(head, (items || []).map((r) => [
    r.username, nameOfRow(r) || '', r.system_role || '', r.org_role || '', r.team_name || '', r.source,
    t(`dorm.bucket.${r.bucket}`), r.days ?? '', r.last_login_at || '', r.created_at || '', r.age ?? '',
    r.has_email === false ? 0 : r.has_email === true ? 1 : '', r.permanent_lock ? 1 : 0,
  ]))
}

/** Seçili kovaların en küçük gün eşiği (hiç girmemiş hariç); kova yoksa 30. */
export function minDaysOf(buckets) {
  const mins = (buckets || []).filter((b) => b !== 'never').map((b) => BUCKET_MIN_DAYS[b]).filter(Boolean)
  return mins.length ? Math.min(...mins) : DORMANT_DAYS
}

/** URL param anahtarları — Kullanıcılar sayfası (UserManager) okur ve tüketince siler. */
export const BULK_PARAM = 'g_bd'

/**
 * Toplu pasife alma sihirbazını süzgece göre ÖN DOLDURARAK açan derin bağlantı paramları (yalnız global yönetici;
 * sihirbaz yine önizleme + sayıyı yazarak onay ister — bu görünüm HİÇBİR hesabı kendisi değiştirmez).
 * Sihirbaz ölçütü: "N gündür girmeyen" (+ "N günden eski hiç girmemiş"), kaynak, rol (ADMIN seçilemez), takım kapsamı.
 * Eşlenemeyen süzgeç (ADMIN rolü, takımsız / adı bilinen ama kimliksiz takım, çoklu kaynak) ölçüte GİRMEZ → sihirbaz
 * daha GENİŞ bir ön liste gösterir; yönetici önizlemede daraltır.
 */
export function bulkWizardParams(f = EMPTY_FILTERS) {
  const buckets = f.bucket || []
  const p = {
    g_tab: 'users',
    [BULK_PARAM]: '1',
    g_bd_days: String(minDaysOf(buckets)),
    g_bd_never: !buckets.length || buckets.includes('never') ? '1' : '0',
  }
  if ((f.source || []).length === 1 && BULK_SOURCES.includes(f.source[0])) p.g_bd_src = f.source[0]
  if ((f.role || []).length === 1 && BULK_ROLES.includes(f.role[0])) p.g_bd_role = f.role[0]
  const teams = (f.team || []).filter((k) => /^\d+$/.test(k))
  if (teams.length && teams.length === (f.team || []).length) p.g_bd_teams = teams.join(',')
  return p
}

/** Kullanıcılar sayfasına "girmeyen" süzgeciyle git (30 / 90 / 180 / hiç — sayfanın sunduğu değerler). */
export function usersPageParams(f = EMPTY_FILTERS) {
  const buckets = f.bucket || []
  if (buckets.length === 1 && buckets[0] === 'never') return { g_tab: 'users', g_dormant: 'never' }
  const min = minDaysOf(buckets)
  return { g_tab: 'users', g_dormant: String(min >= 180 ? 180 : min >= 90 ? 90 : 30) }
}
