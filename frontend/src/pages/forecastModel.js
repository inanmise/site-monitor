/**
 * Vade Takvimi saf modeli (2026-09-12, takvim zenginleştirme). React yok; /api/forecast gövdesinden:
 * kovalama (alarm eşikleriyle), süzgeç, renew-by, gecikme, gruplama (aynı gün / aynı sertifika),
 * veren dağılımı, takım kırılımı, tatil/hafta sonu, ICS/CSV satırları.
 */
import { localDayKey } from '../api/client'

export const EMPTY_FILTERS = Object.freeze({ team: '', ugTeam: '', tier: '', group: '' })
export function filtersToParams(f) {
  return { f_team: f.team || null, f_ug: f.ugTeam || null, f_tier: f.tier || null, f_group: f.group || null }
}
export function paramsToFilters(read) {
  return { team: read('f_team', ''), ugTeam: read('f_ug', ''), tier: read('f_tier', ''), group: read('f_group', '') }
}

export function todayKey() { return localDayKey(new Date().toISOString()) }
function ymd(d) { return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` }
export function addDays(key, n) { const d = new Date(key + 'T00:00:00'); d.setDate(d.getDate() + n); return ymd(d) }   // yerel gün — toISOString UTC'ye kayardı
export function dayDiff(fromKey, toKey) { return Math.round((new Date(toKey + 'T00:00:00') - new Date(fromKey + 'T00:00:00')) / 86400000) }

/** Sunucu damgası → yerel gün anahtarı (bitiş 23:59Z = ertesi gün 02:59 İstanbul). */
export function expiryKey(c) { return c?.not_after ? localDayKey(c.not_after) : null }

/**
 * Sertifikayı sınıflandır: overdue (dolmuş) | unreachable (hata) | critical | high | warning | later.
 * Eşikler /api/forecast.thresholds'tan — alarm ile aynı (varsayılan 30/15/7).
 */
export function classify(c, th) {
  const t = th || { warning: 30, high: 15, critical: 7 }
  if ((c.status || '').toLowerCase() === 'error' && c.days_remaining == null) return 'unreachable'
  const d = c.days_remaining
  if (d == null) return (c.status || '').toLowerCase() === 'error' ? 'unreachable' : 'later'
  if (d < 0) return 'overdue'
  if (d <= t.critical) return 'critical'
  if (d <= t.high) return 'high'
  if (d <= t.warning) return 'warning'
  return 'later'
}

/** Yenileme penceresi: bugün ≥ renew_by → açık; bitiş geçmediyse ve renew_by geçtiyse "late". */
export function windowState(c, today = todayKey()) {
  if (!c.renew_by || c.days_remaining == null) return 'none'
  if (c.days_remaining < 0) return 'expired'
  if (c.renewal_plan_state === 'done') return 'done'
  if (today >= c.renew_by) return 'late'
  return 'ahead'
}

export function applyFilters(certs, f) {
  return certs.filter((c) => {
    if (f.team && String(c.team_id ?? '') !== String(f.team)) return false
    if (f.ugTeam && String(c.ug_team_id ?? '') !== String(f.ugTeam)) return false
    if (f.tier === 'none' ? c.tier != null : (f.tier && String(c.tier ?? '') !== String(f.tier))) return false
    if (f.group && (c.group_name || '') !== f.group) return false
    return true
  })
}

/** KPI sayaçları. */
export function computeKpis(certs, th, today = todayKey()) {
  const k = { overdue: 0, unreachable: 0, critical: 0, high: 0, warning: 0, total: certs.length, windowOpen: 0, late: 0, planned: 0 }
  for (const c of certs) {
    const cls = classify(c, th)
    if (cls in k) k[cls]++
    const w = windowState(c, today)
    if (w === 'late') { k.late++; k.windowOpen++ }
    if (c.renewal_plan_state === 'planned') k.planned++
  }
  return k
}

/** Gün başına yığın (bar grafik / ısı haritası) — N gün; sınıf renkleri eşiklerden. */
export function dailySeries(certs, th, days, today = todayKey(), locale = 'en-GB') {
  const byDay = {}
  for (const c of certs) {
    const key = expiryKey(c); if (!key) continue
    const diff = dayDiff(today, key)
    if (diff < 0 || diff >= days) continue
    const cls = classify(c, th)
    if (!['critical', 'high', 'warning', 'later'].includes(cls)) continue
    const sev = cls === 'later' ? 'warning' : cls
    ;(byDay[key] ||= { critical: [], high: [], warning: [] })[sev].push(c)
  }
  let cumulative = 0
  const out = []
  for (let i = 0; i < days; i++) {
    const key = addDays(today, i)
    const slot = byDay[key] || { critical: [], high: [], warning: [] }
    const total = slot.critical.length + slot.high.length + slot.warning.length
    cumulative += total
    out.push({ date: key, label: new Date(key + 'T00:00:00').toLocaleDateString(locale, { day: '2-digit', month: 'short' }),
      critical: slot.critical.length, high: slot.high.length, warning: slot.warning.length, total, cumulative, domains: slot })
  }
  return out
}

/** Takım kırılımı: seçili aralıkta dolacaklar — takım · ≤crit · ≤high · ≤warn · late · toplam. */
export function byTeam(certs, th, days, today = todayKey()) {
  const m = new Map()
  for (const c of certs) {
    const key = expiryKey(c); const diff = key ? dayDiff(today, key) : null
    const cls = classify(c, th)
    const inRange = cls === 'overdue' || cls === 'unreachable' || (diff != null && diff >= 0 && diff < days)
    if (!inRange) continue
    const id = c.team_id == null ? 'none' : String(c.team_id)
    const row = m.get(id) || { id, name: c.team_name || null, total: 0, overdue: 0, unreachable: 0, critical: 0, high: 0, warning: 0, later: 0, late: 0 }
    row.total++
    if (cls in row) row[cls]++
    if (windowState(c, today) === 'late') row.late++
    m.set(id, row)
  }
  return [...m.values()].sort((a, b) => b.total - a.total)
}

/**
 * Takım tablosu HÜCRESİNİN sertifikaları (2026-09-18): byTeam ile AYNI aralık kuralı — hücredeki sayı ile
 * açılan listenin uzunluğu her zaman eşit olsun. bucket: 'overdue' (süresi dolmuş + ulaşılamayan),
 * 'critical' | 'high' | 'warning' | 'later' (sınıf), 'late' (yenileme penceresi geçmiş), 'total'.
 */
export function teamBucketCerts(certs, th, days, teamId, bucket, today = todayKey()) {
  const out = []
  for (const c of certs) {
    const id = c.team_id == null ? 'none' : String(c.team_id)
    if (id !== String(teamId)) continue
    const key = expiryKey(c); const diff = key ? dayDiff(today, key) : null
    const cls = classify(c, th)
    const inRange = cls === 'overdue' || cls === 'unreachable' || (diff != null && diff >= 0 && diff < days)
    if (!inRange) continue
    const hit = bucket === 'total' ? true
      : bucket === 'overdue' ? (cls === 'overdue' || cls === 'unreachable')
      : bucket === 'late' ? windowState(c, today) === 'late'
      : cls === bucket
    if (hit) out.push(c)
  }
  return out.sort((a, b) => (a.days_remaining ?? 9999) - (b.days_remaining ?? 9999))
}

/** Aynı güne 3+ yenileme = toplu iş; aynı parmak izini paylaşan alanlar = tek sertifika. */
export function batches(certs, th, days, today = todayKey(), min = 3) {
  const byDay = new Map()
  for (const c of certs) {
    const key = expiryKey(c); if (!key) continue
    const diff = dayDiff(today, key); if (diff < 0 || diff >= days) continue
    ;(byDay.get(key) || byDay.set(key, []).get(key)).push(c)
  }
  return [...byDay.entries()].filter(([, l]) => l.length >= min).map(([date, list]) => {
    const issuers = [...new Set(list.map((c) => c.issuer_cn).filter(Boolean))]
    return { date, count: list.length, issuers, domains: list.map((c) => c.domain) }
  }).sort((a, b) => a.date.localeCompare(b.date))
}
export function coverage(certs) {
  const byFp = new Map()
  for (const c of certs) { if (!c.fingerprint) continue; (byFp.get(c.fingerprint) || byFp.set(c.fingerprint, []).get(c.fingerprint)).push(c.domain) }
  return [...byFp.values()].filter((l) => l.length > 1).map((domains) => ({ domains, count: domains.length })).sort((a, b) => b.count - a.count)
}
export function byIssuer(certs, th, days, today = todayKey()) {
  const m = new Map()
  for (const c of certs) {
    const key = expiryKey(c); const diff = key ? dayDiff(today, key) : null
    if (diff == null || diff < 0 || diff >= days) continue
    const name = c.issuer_cn || '—'
    m.set(name, (m.get(name) || 0) + 1)
  }
  return [...m.entries()].map(([issuer, count]) => ({ issuer, count })).sort((a, b) => b.count - a.count)
}

/** Yaklaşan liste: overdue/unreachable en üstte, sonra renew_by'a göre; aralık gün. */
export function upcoming(certs, th, days, today = todayKey()) {
  const rows = []
  for (const c of certs) {
    const cls = classify(c, th)
    const key = expiryKey(c); const diff = key ? dayDiff(today, key) : null
    if (cls === 'overdue' || cls === 'unreachable' || (diff != null && diff >= 0 && diff < days)) {
      rows.push({ ...c, cls, expiry_key: key, window: windowState(c, today), renew_by_key: c.renew_by ? localDayKey(c.renew_by) : null })
    }
  }
  const rank = { overdue: 0, unreachable: 1, critical: 2, high: 3, warning: 4, later: 5 }
  return rows.sort((a, b) => (rank[a.cls] - rank[b.cls]) || ((a.renew_by_key || '9') > (b.renew_by_key || '9') ? 1 : -1))
}

/** Sonraki bitiş (boş durum ipucu): bugünden sonraki en yakın gün + kalan gün. */
export function nextExpiry(certs, today = todayKey()) {
  let best = null
  for (const c of certs) {
    const key = expiryKey(c); if (!key) continue
    const diff = dayDiff(today, key); if (diff < 0) continue
    if (!best || diff < best.days) best = { domain: c.domain, date: key, days: diff }
  }
  return best
}

// ── Tatil / hafta sonu (#11) — TR resmî tatilleri; dini bayramlar yıl bazlı tablo ──────────────────
export const TR_HOLIDAYS = {
  fixed: ['01-01', '04-23', '05-01', '05-19', '07-15', '08-30', '10-29'],
  byYear: {
    2026: ['03-19', '03-20', '03-21', '03-22', '05-26', '05-27', '05-28', '05-29', '05-30'],
    2027: ['03-08', '03-09', '03-10', '03-11', '05-15', '05-16', '05-17', '05-18', '05-19'],
    2028: ['02-25', '02-26', '02-27', '02-28', '05-04', '05-05', '05-06', '05-07', '05-08'],
  },
}
export function isHoliday(key, holidays = TR_HOLIDAYS) {
  if (!key) return false
  const y = key.slice(0, 4), md = key.slice(5)
  return holidays.fixed.includes(md) || (holidays.byYear[y] || []).includes(md)
}
export function isWeekend(key) { const d = new Date(key + 'T00:00:00').getDay(); return d === 0 || d === 6 }
/** Tarih iş günü değilse önceki iş günü. */
export function lastBusinessDay(key, holidays = TR_HOLIDAYS) {
  let k = key; let guard = 0
  while ((isWeekend(k) || isHoliday(k, holidays)) && guard++ < 10) k = addDays(k, -1)
  return k
}

/** ICS olayları (renew_by esas, bitiş açıklamada). */
export function icsEvents(rows, t) {
  return rows.filter((r) => r.renew_by_key || r.expiry_key).map((r) => ({
    uid: `renew-${r.domain}-${r.renew_by_key || r.expiry_key}`, date: r.renew_by_key || r.expiry_key,
    summary: `${t('forecast.icsPrefix')} ${r.domain}`,
    description: `${t('forecast.icsExpires')}: ${r.expiry_key || '—'} · ${t('forecast.icsLead', r.lead_days ?? '')}${r.renewal_planned_at ? ` · ${t('forecast.icsPlanned')}: ${r.renewal_planned_at}` : ''}`,
  }))
}
/** CSV satırları (başlık + satırlar). */
export function csvRows(rows, t) {
  const head = [t('forecast.csvDomain'), t('forecast.csvTeam'), t('forecast.csvTier'), t('forecast.csvExpiry'), t('forecast.csvDays'), t('forecast.csvRenewBy'), t('forecast.csvState'), t('forecast.csvPlanned'), t('forecast.csvIssuer')]
  const body = rows.map((r) => [r.domain, r.team_name || '', r.tier ? `T${r.tier}` : '', r.expiry_key || '', r.days_remaining ?? '', r.renew_by_key || '', r.cls, r.renewal_planned_at || '', r.issuer_cn || ''])
  return [head, ...body]
}
