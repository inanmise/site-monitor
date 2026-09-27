import { tagsOf } from '../../utils/monitorFilters.js'

/**
 * Yenileme Önerileri — saf model (2026-09-26 shadcn yeniden tasarımı). Sayım, süzme, sıralama ve öbekleme burada;
 * bileşenler yalnız çizer. `/api/renewal-advice` satırı: { domain, code, priority, message, action, days_remaining,
 * not_after, team_id, team_name, tier, group_name, tags, port, issuer_cn, fingerprint } (CertificateService.buildAdvice).
 */

/** Sunucunun öncelik sırası (critical → warning → info). */
export const PRIORITY_ORDER = { critical: 0, warning: 1, info: 2 }

/** Bilinen neden kodları — süzgeç listesi bu sırayla (önce süreden bağımsız sorunlar, sonra süre). */
export const CODES = ['REVOKED', 'DEPLOYMENT_INCOMPLETE', 'CHAIN_BROKEN', 'UNREACHABLE', 'EXPIRED', 'EXPIRING_CRITICAL', 'EXPIRING_WARNING', 'EXPIRING_INFO']

/** Süreye bağlı kodlar (gün çubuğu anlamlı). */
export const EXPIRY_CODES = new Set(['EXPIRED', 'EXPIRING_CRITICAL', 'EXPIRING_WARNING', 'EXPIRING_INFO'])

/** Süreden bağımsız sorunlar: iptal, zincir, eksik dağıtım, ulaşılamayan uç. */
export const PROBLEM_CODES = new Set(['REVOKED', 'DEPLOYMENT_INCOMPLETE', 'CHAIN_BROKEN', 'UNREACHABLE'])

/** Sunucu en geç 60 gün kala öneri üretir (EXPIRING_INFO ≤ 60) — gün çubuğunun ölçeği. */
export const ADVICE_WINDOW_DAYS = 60

/** Özet kartlarının anahtarları (tek etkin süzgeç, URL `r_stat`). */
export const STAT_KEYS = ['critical', 'week', 'warning', 'info', 'problems', 'shared']

/** "Atanmamış" seçeneği (takımsız / grupsuz / etiketsiz) — monitorFilters ile aynı sözleşme. */
export const NONE = '__none__'

/** Çoklu süzgeç değerlerinin URL ayırıcısı (takım/grup adında virgül olabilir; `|` olmaz). */
export const LIST_SEP = '|'

export const parseList = (raw) => (raw ? String(raw).split(LIST_SEP).map((s) => s.trim()).filter(Boolean) : [])
export const joinList = (list) => (list && list.length ? list.join(LIST_SEP) : null)

/** Parmak izi → kaç alan adı (aynı sertifika = tek yenileme işi). */
export function fingerprintCounts(advice) {
  const m = new Map()
  for (const a of advice || []) if (a.fingerprint) m.set(a.fingerprint, (m.get(a.fingerprint) || 0) + 1)
  return m
}

export const isShared = (a, fpCount) => !!a.fingerprint && (fpCount.get(a.fingerprint) || 0) > 1

export function matchesStat(a, stat, fpCount) {
  switch (stat) {
    case 'critical': case 'warning': case 'info': return a.priority === stat
    case 'week': return a.days_remaining != null && a.days_remaining >= 0 && a.days_remaining <= 7
    case 'problems': return PROBLEM_CODES.has(a.code)
    case 'shared': return isShared(a, fpCount)
    default: return true
  }
}

/** Özet şeridi sayıları; `batches` = birden çok alanı kapsayan sertifika sayısı, `sharedDomains` = o alanlar. */
export function summarise(advice, fpCount) {
  const s = { total: 0, critical: 0, week: 0, warning: 0, info: 0, problems: 0, shared: 0, batches: 0 }
  for (const a of advice || []) {
    s.total++
    for (const k of STAT_KEYS) if (matchesStat(a, k, fpCount)) s[k]++
  }
  for (const n of fpCount.values()) if (n > 1) s.batches++
  return s
}

function matchesAssigned(value, wanted) {
  if (!wanted.length) return true
  return wanted.some((w) => (w === NONE ? !value : value === w))
}

function matchesTags(a, wanted) {
  if (!wanted.length) return true
  const tags = tagsOf(a).map((x) => x.toLowerCase())
  return wanted.some((w) => (w === NONE ? tags.length === 0 : tags.includes(w.toLowerCase())))
}

/** Serbest arama: alan adı, veren, takım, grup, etiket. */
export function matchesQuery(a, q) {
  const s = String(q || '').trim().toLowerCase()
  if (!s) return true
  return [a.domain, a.issuer_cn, a.team_name, a.group_name].some((v) => String(v || '').toLowerCase().includes(s))
    || tagsOf(a).some((x) => x.toLowerCase().includes(s))
}

/**
 * @param {object[]} advice
 * @param {{ q?: string, stat?: string|null, codes?: string[], teams?: string[], groups?: string[], tags?: string[] }} f
 */
export function filterAdvice(advice, f, fpCount) {
  const codes = f.codes || [], teams = f.teams || [], groups = f.groups || [], tags = f.tags || []
  return (advice || []).filter((a) =>
    (!f.stat || matchesStat(a, f.stat, fpCount))
    && (!codes.length || codes.includes(a.code))
    && matchesAssigned(a.team_name, teams)
    && matchesAssigned(a.group_name, groups)
    && matchesTags(a, tags)
    && matchesQuery(a, f.q))
}

const daysOf = (a) => (a.days_remaining == null ? -99999 : a.days_remaining)

/** Sıralama anahtarları: priority (öncelik → gün), days, domain, team. Yeni dizi döner. */
export function sortAdvice(list, sortKey) {
  const out = [...(list || [])]
  out.sort((x, y) => {
    if (sortKey === 'domain') return (x.domain || '').localeCompare(y.domain || '')
    if (sortKey === 'team') return (x.team_name || '').localeCompare(y.team_name || '') || daysOf(x) - daysOf(y)
    if (sortKey === 'days') return daysOf(x) - daysOf(y)
    return (PRIORITY_ORDER[x.priority] ?? 9) - (PRIORITY_ORDER[y.priority] ?? 9) || daysOf(x) - daysOf(y)
  })
  return out
}

export const SORT_KEYS = ['priority', 'days', 'domain', 'team']

/**
 * Gün çubuğu: yalnız süreye bağlı kodlarda ve gün biliniyorsa. Değer = pencerede KALAN gün (0…60), ton öncelikten.
 * Süreden bağımsız sorunlarda (iptal, zincir…) çubuk anlamsız — kalan gün sorun değildir.
 */
export function daysMeter(a) {
  if (!EXPIRY_CODES.has(a.code) || a.days_remaining == null) return null
  const value = Math.max(0, Math.min(a.days_remaining, ADVICE_WINDOW_DAYS))
  const tone = a.priority === 'critical' ? 'crit' : a.priority === 'warning' ? 'warn' : 'ok'
  return { value, max: ADVICE_WINDOW_DAYS, tone }
}

/** Seçenek listesi: sayılı, alfabetik; atanmamış varsa sonda NONE. */
export function facetOptions(advice, pick, noneLabel) {
  const counts = new Map(); let none = 0
  for (const a of advice || []) {
    const vals = pick(a)
    if (!vals.length) none++
    for (const v of vals) counts.set(v, (counts.get(v) || 0) + 1)
  }
  const opts = [...counts.entries()].sort((x, y) => x[0].localeCompare(y[0])).map(([value, count]) => ({ value, label: value, count }))
  if (none && noneLabel) opts.push({ value: NONE, label: noneLabel, count: none })
  return opts
}

/** Etiket seçenekleri harf-duyarsız birleşir (monitorFilters.tagNamesOf ile aynı), sayılı. */
export function tagFacetOptions(advice, noneLabel) {
  const seen = new Map(); let none = 0
  for (const a of advice || []) {
    const tags = tagsOf(a)
    if (!tags.length) none++
    const mine = new Set()
    for (const tg of tags) {
      const k = tg.toLowerCase()
      if (!seen.has(k)) seen.set(k, { value: tg, label: tg, count: 0 })
      if (!mine.has(k)) { seen.get(k).count++; mine.add(k) }
    }
  }
  const opts = [...seen.values()].sort((x, y) => x.label.localeCompare(y.label))
  if (none && noneLabel) opts.push({ value: NONE, label: noneLabel, count: none })
  return opts
}
