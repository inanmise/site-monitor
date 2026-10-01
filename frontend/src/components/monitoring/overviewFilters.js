// İzleme Panosu — sütun süzgeçleri ve sıralama (2026-10-01, kullanıcı isteği: "kolonlar üzerinden filtreleme").
// Saf fonksiyonlar: tablo başlığı menüleri, telefon süzgeç paneli, çipler ve testler AYNI kuralı kullanır.
import { parseUtc } from '../../utils/incidentMeta.js'

/** Durum sırası (düşük en üstte) — sayfanın STATUS_META.rank'ı ile aynı. */
export const STATUS_RANK = { down: 0, stale: 1, unknown: 2, up: 3, paused: 4, deleted: 5 }
const LEVEL_RANK = { CRITICAL: 3, HIGH: 2, WARNING: 1 }

/** Takımı olmayan satırlar için takım süzgeci değeri. */
export const NO_TEAM = '__none'

/** Son kontrol süzgeci (tek seçim): yaş aralıkları + hiç kontrol edilmemiş. */
export const LAST_CHECK_OPTS = ['15m', '1h', '24h', 'older', 'never']
const LAST_LIMIT_MS = { '15m': 15 * 60_000, '1h': 3_600_000, '24h': 86_400_000 }

/** Koşum süzgeci (tek seçim): pencerede başarısız koşumu olan / hatasız / hiç koşum yok. */
export const CHECKS_OPTS = ['failed', 'clean', 'none']

/** Açık alarm süzgeci (tek seçim): herhangi / seviye / alarmsız. */
export const ALERT_OPTS = ['any', 'CRITICAL', 'HIGH', 'WARNING', 'none']

/**
 * Sıralanabilir sütunlar. Boş anahtar = varsayılan (durum → açık alarm → ad). `uptime` (pencere başarı oranı) ve
 * `response` (son yanıt süresi) 2026-10-01 yeniden tasarımında eklendi; `checks` URL geriye uyumu ve telefon paneli için kalır.
 */
export const SORT_KEYS = ['status', 'name', 'type', 'team', 'last', 'checks', 'uptime', 'response', 'alert']

export const EMPTY_FILTERS = Object.freeze({ types: [], statuses: [], teams: [], q: '', last: '', checks: '', alert: '' })

/** CSV ya da dizi → boş olmayan benzersiz dizgeler. */
export function toList(v) {
  if (v == null || v === '') return []
  const arr = Array.isArray(v) ? v : String(v).split(',')
  return [...new Set(arr.map((x) => String(x).trim()).filter(Boolean))]
}

/** `{ type, status, team }` (eski, tek değer) ya da `{ types, statuses, teams }` → tek biçim. */
export function normalizeFilters(f = {}) {
  return {
    types: toList(f.types ?? f.type),
    statuses: toList(f.statuses ?? f.status),
    teams: toList(f.teams ?? f.team),
    q: String(f.q ?? ''),
    last: LAST_CHECK_OPTS.includes(f.last) ? f.last : '',
    checks: CHECKS_OPTS.includes(f.checks) ? f.checks : '',
    alert: ALERT_OPTS.includes(f.alert) ? f.alert : '',
  }
}

export function teamKey(row) { return row?.team_id != null ? String(row.team_id) : NO_TEAM }

export function matchLastCheck(row, key, nowMs = Date.now()) {
  if (!key) return true
  const at = parseUtc(row?.last_checked_at)
  if (key === 'never') return !at
  if (!at) return false
  const age = nowMs - at.getTime()
  if (key === 'older') return age > LAST_LIMIT_MS['24h']
  return age <= LAST_LIMIT_MS[key]
}

export function matchChecks(row, key) {
  if (!key) return true
  const checks = Number(row?.checks_window || 0), failed = Number(row?.failed_window || 0)
  if (key === 'failed') return failed > 0
  if (key === 'clean') return checks > 0 && failed === 0
  return checks === 0   // none
}

export function matchAlert(row, key) {
  if (!key) return true
  const open = Number(row?.open_alerts || 0)
  if (key === 'any') return open > 0
  if (key === 'none') return open === 0
  return open > 0 && String(row?.open_alert_level || '').toUpperCase() === key
}

/**
 * Süzgeçleri uygular. {@code skip}: bu sütunun süzgeci atlanır — sütun menüsündeki sayılar "diğer süzgeçler etkinken bu
 * değeri seçersem kaç satır kalır" sorusunu cevaplar (faset sayımı).
 */
export function applyFilters(rows, filters, { skip = null, nowMs = Date.now() } = {}) {
  const f = normalizeFilters(filters)
  const needle = f.q.trim().toLocaleLowerCase('tr')
  return (rows || []).filter((r) => {
    if (skip !== 'type' && f.types.length && !f.types.includes(r.type)) return false
    if (skip !== 'status' && f.statuses.length && !f.statuses.includes(r.status)) return false
    if (skip !== 'team' && f.teams.length && !f.teams.includes(teamKey(r))) return false
    if (skip !== 'last' && !matchLastCheck(r, f.last, nowMs)) return false
    if (skip !== 'checks' && !matchChecks(r, f.checks)) return false
    if (skip !== 'alert' && !matchAlert(r, f.alert)) return false
    if (skip !== 'q' && needle) {
      const hay = `${r.name ?? ''} ${r.target ?? ''} ${r.team_name ?? ''}`.toLocaleLowerCase('tr')
      if (!hay.includes(needle)) return false
    }
    return true
  })
}

/** Sütun menüsü sayıları: {değer → satır sayısı}, diğer süzgeçler uygulanmış. */
export function facetCounts(rows, filters, column, keyOf, nowMs = Date.now()) {
  const base = applyFilters(rows, filters, { skip: column, nowMs })
  const out = {}
  for (const r of base) {
    const keys = keyOf(r)
    for (const k of Array.isArray(keys) ? keys : [keys]) if (k != null) out[k] = (out[k] || 0) + 1
  }
  return out
}

/** Tek seçimli sütunların (son kontrol / koşum / alarm) her seçeneği için sayım. */
export function optionCounts(rows, filters, column, options, matcher, nowMs = Date.now()) {
  const base = applyFilters(rows, filters, { skip: column, nowMs })
  const out = {}
  for (const o of options) out[o] = base.filter((r) => matcher(r, o, nowMs)).length
  return out
}

function defaultCompare(a, b) {
  return (STATUS_RANK[a.status] ?? 9) - (STATUS_RANK[b.status] ?? 9)
    || (b.open_alerts ?? 0) - (a.open_alerts ?? 0)
    || String(a.name ?? '').localeCompare(String(b.name ?? ''), 'tr')
}

const byName = (a, b) => String(a.name ?? '').localeCompare(String(b.name ?? ''), 'tr')

/** `mo_sort` değeri → {key, dir}. `last_desc`, `name`, `team_asc` … Bilinmeyen → varsayılan. */
export function parseSort(v) {
  const s = String(v || '')
  const m = /^([a-z]+)(?:_(asc|desc))?$/.exec(s)
  if (!m || !SORT_KEYS.includes(m[1])) return { key: '', dir: 'asc' }
  return { key: m[1], dir: m[2] || defaultDir(m[1]) }
}

/** Sütunun ilk tıklamadaki yönü: zaman / sayı / seviye / yanıt süresi azalan, metin artan; başarı oranı ARTAN (en kötü önce). */
export function defaultDir(key) { return ['last', 'checks', 'alert', 'response'].includes(key) ? 'desc' : 'asc' }

/** Başarı oranı (%) — sunucu alanı ya da koşum sayılarından; koşum yoksa null. */
function uptimeOf(r) {
  const v = r?.success_rate_window
  if (v != null && Number.isFinite(Number(v))) return Number(v)
  const checks = Number(r?.checks_window || 0)
  return checks > 0 ? ((checks - Number(r?.failed_window || 0)) * 100) / checks : null
}

/** Değeri olmayan satırlar her iki yönde de SONDA. */
function nullsLast(va, vb, sign) {
  if (va == null && vb == null) return 0
  if (va == null) return 1 * sign
  if (vb == null) return -1 * sign
  return va - vb
}

export function sortValue({ key, dir }) { return key ? `${key}_${dir}` : '' }

/** Başlık tıklaması: pasif → varsayılan yön; aynı sütun → yön çevir; ikinci çevirmeden sonra varsayılan sıralamaya dön. */
export function toggleSort(cur, key) {
  if (cur.key !== key) return { key, dir: defaultDir(key) }
  if (cur.dir === defaultDir(key)) return { key, dir: cur.dir === 'asc' ? 'desc' : 'asc' }
  return { key: '', dir: 'asc' }
}

export function sortRows(rows, sort, teamName = (r) => r.team_name) {
  const { key, dir } = sort || {}
  const arr = [...(rows || [])]
  if (!key) return arr.sort(defaultCompare)
  const sign = dir === 'desc' ? -1 : 1
  const cmp = {
    status: (a, b) => (STATUS_RANK[a.status] ?? 9) - (STATUS_RANK[b.status] ?? 9),
    name: byName,
    type: (a, b) => String(a.type ?? '').localeCompare(String(b.type ?? '')),
    team: (a, b) => {
      const ta = teamName(a), tb = teamName(b)
      if (!ta && !tb) return 0
      if (!ta) return 1 * sign   // takımsız satırlar her iki yönde de sonda
      if (!tb) return -1 * sign
      return String(ta).localeCompare(String(tb), 'tr')
    },
    last: (a, b) => {
      const ta = parseUtc(a.last_checked_at)?.getTime(), tb = parseUtc(b.last_checked_at)?.getTime()
      if (ta == null && tb == null) return 0
      if (ta == null) return 1 * sign   // hiç kontrol edilmemiş satırlar sonda
      if (tb == null) return -1 * sign
      return ta - tb
    },
    checks: (a, b) => Number(a.failed_window || 0) - Number(b.failed_window || 0)
      || Number(a.checks_window || 0) - Number(b.checks_window || 0),
    uptime: (a, b) => nullsLast(uptimeOf(a), uptimeOf(b), sign),
    response: (a, b) => nullsLast(a.response_ms == null ? null : Number(a.response_ms), b.response_ms == null ? null : Number(b.response_ms), sign),
    alert: (a, b) => (LEVEL_RANK[String(a.open_alert_level || '').toUpperCase()] ?? 0) - (LEVEL_RANK[String(b.open_alert_level || '').toUpperCase()] ?? 0)
      || Number(a.open_alerts || 0) - Number(b.open_alerts || 0),
  }[key]
  return arr.sort((a, b) => sign * cmp(a, b) || byName(a, b))
}

/** Etkin süzgeç sayısı (telefon düğmesi rozetı + "temizle" görünürlüğü). Arama hariç. */
export function activeColumnFilterCount(filters) {
  const f = normalizeFilters(filters)
  return (f.types.length ? 1 : 0) + (f.statuses.length ? 1 : 0) + (f.teams.length ? 1 : 0)
    + (f.last ? 1 : 0) + (f.checks ? 1 : 0) + (f.alert ? 1 : 0)
}
