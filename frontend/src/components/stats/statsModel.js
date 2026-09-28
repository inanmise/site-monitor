/**
 * İstatistikler ekranı — saf model (2026-09-28 shadcn + mobil web yeniden tasarımı).
 *
 * Bileşenden bağımsız: kalan süre kovaları ve KPI süzgeçleri, dağılım, yaklaşan bitişler, takım × katman
 * matrisi, tablo süzme/sıralama, URL eşlemesi (`st_*` öneki — PAGE_STATE_PREFIXES) ve CSV satırları.
 * Bileşenler yalnız durum + çizim tutar; testler burayı doğrudan da sınar.
 *
 * İki sözlük, iki iş:
 *  - KALAN SÜRE (KPI kutucukları, dağılım grafiği, kalan gün rozeti): nesnel gün pencereleri — 30/14/7 gün.
 *  - SEVİYE (durum rozeti, takım × katman matrisi): sunucunun tier eşiklerine göre verdiği `alert_level`
 *    (Pano kartlarıyla aynı hüküm; yoksa certTableModel.levelOf düşüşü).
 */
import { levelOf } from '../certtable/certTableModel.js'
import { toCsv } from '../../utils/csvExport.js'

/** Seviyeler — matris sütun sırası (iyi → kötü, hata en sonda). */
export const LEVELS = ['valid', 'warning', 'high', 'critical', 'expired', 'error']
/** Seviye → durum rozeti metni (Tüm Sertifikalar ile aynı sözlük). */
export const LEVEL_TEXT = {
  valid: 'tbl.statusValid', warning: 'tbl.statusWarning', high: 'tbl.statusHigh',
  critical: 'tbl.statusCritical', expired: 'tbl.statusExpired', error: 'tbl.statusError',
}
/** Dağılım grafiğinin kovaları (süresi bilinenler; sıra = aciliyet artışı). */
export const BUCKETS = ['ok', 'd30', 'd14', 'd7', 'expired']
/** KPI süzgeç anahtarları (Toplam = süzgeç yok → null). */
export const KPI_KEYS = ['valid', 'd30', 'd14', 'd7', 'expired', 'error']
/** Katmanlar — 0 = sınıflandırılmamış. Matris varsayılanı yalnız üretim katmanları (T1–T2, 2026-05 ürün kararı). */
export const TIERS = [1, 2, 3, 4, 0]
export const PROD_TIERS = [1, 2]
/** Sıralama anahtarları (`alan|yön`). */
export const SORT_KEYS = [
  'priority|asc', 'days_remaining|asc', 'days_remaining|desc', 'domain|asc', 'domain|desc',
  'issuer|asc', 'issuer|desc', 'checked_at|desc', 'checked_at|asc',
]
export const DEFAULT_SORT = 'priority|asc'
/** Yaklaşan bitişler şeridindeki satır sayısı (sabit önizleme — tamamı tabloda). */
export const UPCOMING_N = 6

/** URL anahtarları (`st_` öneki; sekme değişince App temizler). */
export const URL_KEYS = Object.freeze({
  kpi: 'st_k', q: 'st_q', issuers: 'st_iss', team: 'st_team', tier: 'st_tier', level: 'st_lvl',
  sort: 'st_sort', matrix: 'st_mx', allTiers: 'st_mxall',
})
export const PAGE_URL = Object.freeze({ pageKey: 'st_page', sizeKey: 'st_ps' })
/** Çoklu değer ayırıcısı — sağlayıcı adları virgül içerebilir. */
const LIST_SEP = '|'

// ── Satır türevleri ─────────────────────────────────────────────────────────────────────────────

export function isErrorCert(c) { return c?.status === 'error' || c?.alert_level === 'error' }

/** Seviye (sunucu hükmü; hata her zaman hata). */
export function levelOfCert(c) { return isErrorCert(c) ? 'error' : levelOf(c) }

/** Kalan süre kovası: error | unknown | expired | d7 | d14 | d30 | ok. */
export function bucketOf(c) {
  if (isErrorCert(c)) return 'error'
  const d = c?.days_remaining
  if (d == null) return 'unknown'
  if (d < 0) return 'expired'
  if (d <= 7) return 'd7'
  if (d <= 14) return 'd14'
  if (d <= 30) return 'd30'
  return 'ok'
}

/** KPI süzgeci — pencereler KÜMÜLATİF: "30 gün içinde" 14 ve 7 günlükleri de kapsar. */
export function matchesKpi(c, key) {
  if (!key) return true
  const b = bucketOf(c)
  switch (key) {
    case 'valid': return b === 'ok'
    case 'd30': return b === 'd30' || b === 'd14' || b === 'd7'
    case 'd14': return b === 'd14' || b === 'd7'
    case 'd7': return b === 'd7'
    case 'expired': return b === 'expired'
    case 'error': return b === 'error'
    default: return true
  }
}

export function issuerOf(c) { return c?.issuer_cn || c?.issuer || '' }

/** Tier anahtarı: 1–4, sınıflandırılmamış 0. */
export function tierOf(c) { return c?.tier == null ? 0 : Number(c.tier) }

// ── Özet ────────────────────────────────────────────────────────────────────────────────────────

/** KPI sayıları + ortalama kalan gün (süresi dolmamış, hatasız sertifikalar üzerinden; yoksa null). */
export function kpiCounts(certs) {
  const out = { total: 0, valid: 0, d30: 0, d14: 0, d7: 0, expired: 0, error: 0, unknown: 0, avgDays: null }
  let sum = 0, n = 0
  for (const c of certs || []) {
    out.total++
    const b = bucketOf(c)
    if (b === 'ok') out.valid++
    else if (b === 'unknown') out.unknown++
    else if (b === 'expired') out.expired++
    else if (b === 'error') out.error++
    for (const k of ['d30', 'd14', 'd7']) if (matchesKpi(c, k)) out[k]++
    if (b !== 'error' && b !== 'unknown' && b !== 'expired') { sum += c.days_remaining; n++ }
  }
  if (n > 0) out.avgDays = Math.round(sum / n)
  return out
}

/**
 * Kalan süre dağılımı — YALNIZ süresi bilinenler (hata/bekleyen sertifikanın bitişi bilinmez; ayrı not).
 * @returns {{ rows: {key,count,pct}[], known: number, error: number, unknown: number }}
 */
export function distribution(certs) {
  const counts = Object.fromEntries(BUCKETS.map((k) => [k, 0]))
  let error = 0, unknown = 0
  for (const c of certs || []) {
    const b = bucketOf(c)
    if (b === 'error') error++
    else if (b === 'unknown') unknown++
    else counts[b]++
  }
  const known = BUCKETS.reduce((s, k) => s + counts[k], 0)
  const rows = BUCKETS.map((k) => ({ key: k, count: counts[k], pct: known > 0 ? Math.round((counts[k] * 1000) / known) / 10 : 0 }))
  return { rows, known, error, unknown }
}

/** Yaklaşan bitişler: süresi dolmamış, hatasız; en yakın önce (eşitlikte alan adı). */
export function upcoming(certs, n = UPCOMING_N) {
  return (certs || [])
    .filter((c) => !isErrorCert(c) && c?.days_remaining != null && c.days_remaining >= 0)
    .sort((a, b) => a.days_remaining - b.days_remaining || String(a.domain).localeCompare(String(b.domain)))
    .slice(0, n)
}

/** Sağlayıcı faset seçenekleri — `pool` (öteki süzgeçler uygulanmış küme) üzerinden sayı; sayı çoktan aza. */
export function issuerOptions(certs, pool = certs) {
  const all = new Set()
  for (const c of certs || []) { const i = issuerOf(c); if (i) all.add(i) }
  const counts = new Map()
  for (const c of pool || []) { const i = issuerOf(c); if (i) counts.set(i, (counts.get(i) || 0) + 1) }
  return [...all]
    .map((i) => ({ value: i, label: i, count: counts.get(i) || 0 }))
    .sort((a, b) => b.count - a.count || a.label.localeCompare(b.label))
}

// ── Takımlar (/api/stats/teams) ─────────────────────────────────────────────────────────────────

const DOMAIN_KEYS = ['valid_domains', 'warning_domains', 'high_domains', 'critical_domains', 'expired_domains', 'error_domains']

function collect(stats) {
  const out = new Set()
  for (const k of DOMAIN_KEYS) for (const d of stats?.[k] ?? []) out.add(d)
  return out
}

/**
 * Takım listesi: `{ id, name, sy:Set, ug:Set, domains:Set }` — `domains` = SY ∪ UG (takımın dahil olduğu her alan).
 * Biçimler: `all_teams` (yönetici / çok takımlı) ve `personal` (tek takım); başka biçim → boş.
 */
export function buildTeams(teamStats) {
  if (!teamStats) return []
  const one = (tm, fallbackId) => {
    const sy = collect(tm?.sy_stats), ug = collect(tm?.ug_stats)
    return { id: tm?.team_id ?? fallbackId, name: tm?.team_name || '—', sy, ug, domains: new Set([...sy, ...ug]) }
  }
  if (teamStats.mode === 'all_teams') return (teamStats.teams ?? []).map((tm, i) => one(tm, -(i + 1)))
  if (teamStats.mode === 'personal') return [one(teamStats, 0)]
  return []
}

/** Alan adı → { sy: [ad], ug: [ad] } (takım adları sıralı). */
export function domainTeamMap(teams) {
  const map = new Map()
  for (const tm of teams || []) {
    for (const [kind, set] of [['sy', tm.sy], ['ug', tm.ug]]) {
      for (const d of set) {
        if (!map.has(d)) map.set(d, { sy: [], ug: [] })
        const e = map.get(d)
        if (!e[kind].includes(tm.name)) e[kind].push(tm.name)
      }
    }
  }
  for (const e of map.values()) { e.sy.sort(); e.ug.sort() }
  return map
}

/**
 * Takım süzgecinin alan kümesi + adı. Takım istatistiklerinde varsa SY ∪ UG; yoksa (ör. yönetici özetinden gelen,
 * matriste olmayan takım) sertifikanın sorumlu takımından (`team_id`).
 */
export function resolveTeam(id, teams, certs) {
  if (id == null) return null
  const tm = (teams || []).find((x) => String(x.id) === String(id))
  if (tm) return { id: tm.id, name: tm.name, domains: tm.domains }
  const own = (certs || []).filter((c) => c.team_id != null && String(c.team_id) === String(id))
  if (own.length === 0) return { id, name: String(id), domains: new Set() }
  return { id, name: own[0].team_name || String(id), domains: new Set(own.map((c) => c.domain)) }
}

// ── Takım × katman matrisi ──────────────────────────────────────────────────────────────────────

const emptyCounts = () => Object.fromEntries(LEVELS.map((l) => [l, 0]))

/**
 * Matris satırları. Takım satırı TÜM katmanları sayar (takım süzgeciyle aynı küme); katman satırları yalnız
 * `tiers`'daki katmanlar (varsayılan üretim: T1–T2). Sertifikası olmayan takım düşer. Sıra: dikkat isteyen
 * (yüksek + kritik + dolmuş + hata) çoktan aza, sonra toplam, sonra ad.
 */
export function matrixRows(certs, teams, tiers = PROD_TIERS) {
  const byDomain = new Map((certs || []).map((c) => [c.domain, c]))
  const out = []
  for (const tm of teams || []) {
    const counts = emptyCounts()
    const tierMap = new Map()
    let total = 0
    for (const d of tm.domains) {
      const c = byDomain.get(d)
      if (!c) continue
      const lvl = levelOfCert(c)
      total++
      counts[lvl]++
      const tr = tierOf(c)
      if (!tiers.includes(tr)) continue
      if (!tierMap.has(tr)) tierMap.set(tr, { tier: tr, total: 0, counts: emptyCounts() })
      const row = tierMap.get(tr)
      row.total++
      row.counts[lvl]++
    }
    if (total === 0) continue
    const tierRows = tiers.filter((k) => tierMap.has(k)).map((k) => tierMap.get(k))
    const attention = counts.high + counts.critical + counts.expired + counts.error
    out.push({ id: tm.id, name: tm.name, domains: tm.domains, total, counts, tiers: tierRows, attention })
  }
  return out.sort((a, b) => b.attention - a.attention || b.total - a.total || a.name.localeCompare(b.name))
}

/** Isı tonu adımı (1–3): sayının o sütundaki en büyük değere oranı. 0 → 0. */
export function heatStep(count, max) {
  if (!count || !max) return 0
  const r = count / max
  return r > 2 / 3 ? 3 : r > 1 / 3 ? 2 : 1
}

/** Sütun başına en büyük değerler — `rows` üzerinde (takım satırları ya da katman satırları ayrı ayrı). */
export function columnMax(rows) {
  const max = emptyCounts()
  for (const r of rows || []) for (const l of LEVELS) max[l] = Math.max(max[l], r.counts[l] || 0)
  return max
}

// ── Süzme + sıralama ────────────────────────────────────────────────────────────────────────────

/**
 * @param f { kpi, q, issuers:string[], team:{domains}|null, tier:number|null, level:string|null }
 * @param dtm domainTeamMap (aramada takım adları)
 */
export function applyFilters(certs, f, dtm) {
  const q = String(f?.q || '').trim().toLocaleLowerCase('tr')
  const iss = f?.issuers?.length ? new Set(f.issuers) : null
  return (certs || []).filter((c) => {
    if (f?.kpi && !matchesKpi(c, f.kpi)) return false
    if (f?.team && !f.team.domains.has(c.domain)) return false
    if (f?.tier != null && tierOf(c) !== f.tier) return false
    if (f?.level && levelOfCert(c) !== f.level) return false
    if (iss && !iss.has(issuerOf(c))) return false
    if (q) {
      const tm = dtm?.get(c.domain)
      const hay = [c.domain, issuerOf(c), c.team_name, c.error, ...(tm?.sy ?? []), ...(tm?.ug ?? [])]
        .filter(Boolean).join(' ').toLocaleLowerCase('tr')
      if (!hay.includes(q)) return false
    }
    return true
  })
}

const LEVEL_RANK = { expired: 0, critical: 1, error: 2, high: 3, warning: 4, valid: 5 }
/** Boş sağlayıcı sona düşsün diye karşılaştırma dolgusu (U+FFFF). */
const LAST = String.fromCharCode(0xffff)

export function sortCerts(list, sortBy = DEFAULT_SORT) {
  const [key, dir] = String(sortBy || DEFAULT_SORT).split('|')
  const sign = dir === 'desc' ? -1 : 1
  const byDomain = (a, b) => String(a.domain).localeCompare(String(b.domain))
  const cmp = {
    priority: (a, b) => (LEVEL_RANK[levelOfCert(a)] - LEVEL_RANK[levelOfCert(b)])
      || ((a.tier ?? 9) - (b.tier ?? 9))
      || ((a.days_remaining ?? 99999) - (b.days_remaining ?? 99999)),
    days_remaining: (a, b) => (a.days_remaining ?? 99999) - (b.days_remaining ?? 99999),
    domain: byDomain,
    issuer: (a, b) => (issuerOf(a) || LAST).localeCompare(issuerOf(b) || LAST),
    checked_at: (a, b) => String(a.checked_at || '').localeCompare(String(b.checked_at || '')),
  }[key]
  if (!cmp) return [...(list || [])]
  return [...(list || [])].sort((a, b) => sign * cmp(a, b) || byDomain(a, b))
}

// ── URL ─────────────────────────────────────────────────────────────────────────────────────────

export function parseList(raw) {
  return raw ? String(raw).split(LIST_SEP).map((s) => s.trim()).filter(Boolean) : []
}
export function serializeList(list) { return list?.length ? list.join(LIST_SEP) : null }

/** Mount'ta URL → durum (bilinmeyen değerler yok sayılır). */
export function stateFromUrl(read) {
  const kpi = read(URL_KEYS.kpi, null)
  const tierRaw = read(URL_KEYS.tier, null)
  const tier = tierRaw != null && /^[0-4]$/.test(tierRaw) ? Number(tierRaw) : null
  const level = read(URL_KEYS.level, null)
  const sort = read(URL_KEYS.sort, null)
  const team = read(URL_KEYS.team, null)
  return {
    kpi: KPI_KEYS.includes(kpi) ? kpi : null,
    q: read(URL_KEYS.q, '') || '',
    issuers: parseList(read(URL_KEYS.issuers, '')),
    team: team != null && /^-?\d+$/.test(team) ? team : null,
    tier,
    level: LEVELS.includes(level) ? level : null,
    sort: SORT_KEYS.includes(sort) ? sort : DEFAULT_SORT,
    matrixOpen: read(URL_KEYS.matrix, null) === '1',
    allTiers: read(URL_KEYS.allTiers, null) === '1',
  }
}

/** Durum → URL eşlemesi (varsayılanlar yazılmaz). */
export function toUrlMapping(s) {
  return {
    [URL_KEYS.kpi]: s.kpi || null,
    [URL_KEYS.q]: String(s.q || '').trim() || null,
    [URL_KEYS.issuers]: serializeList(s.issuers),
    [URL_KEYS.team]: s.team != null ? String(s.team) : null,
    [URL_KEYS.tier]: s.tier != null ? String(s.tier) : null,
    [URL_KEYS.level]: s.level || null,
    [URL_KEYS.sort]: s.sort && s.sort !== DEFAULT_SORT ? s.sort : null,
    [URL_KEYS.matrix]: s.matrixOpen ? '1' : null,
    [URL_KEYS.allTiers]: s.allTiers ? '1' : null,
  }
}

// ── CSV ─────────────────────────────────────────────────────────────────────────────────────────

/** Süzülmüş tablonun CSV'si (tüm sayfalar; formül nötrlemesi utils/csv üzerinden). */
export function statsCsv(rows, t, dtm) {
  const headers = [
    t('tbl.colDomain'), t('tbl.colStatus'), t('tbl.colDays'), t('tbl.colExpiry'), t('tbl.colIssuer'),
    t('sv.colSyTeam'), t('sv.colUgTeam'), t('sv.colTier'), t('tbl.colChecked'),
  ]
  const body = (rows || []).map((c) => {
    const tm = dtm?.get(c.domain)
    return [
      c.domain, t(LEVEL_TEXT[levelOfCert(c)]), c.days_remaining ?? '', c.not_after || '', issuerOf(c),
      tm?.sy?.length ? tm.sy : (c.team_name || ''), tm?.ug ?? [], c.tier != null ? `T${c.tier}` : '', c.checked_at || '',
    ]
  })
  return toCsv(headers, body)
}
