/**
 * Haftalık Raporlar — saf model (2026-09-13 zenginleştirme): durum facet'leri, onay kuyruğu, sıralama,
 * geri sayım, Δ hesabı, yıl özeti CSV ve URL eşlemesi. Bileşen yalnız durum + çizim tutar.
 */
import { csvRows } from '../../utils/csv.js'

export const STATUS_CHIPS = ['DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'SENT', 'REJECTED']

/** Satırın etkin durumu: APPROVED + sent_at → SENT (liste rozeti ile aynı dil). */
export function effectiveStatus(r) {
  if (!r) return ''
  return r.status === 'APPROVED' && r.sent_at ? 'SENT' : r.status
}

/** Durum → sayı (chip sayaçları). */
export function statusFacets(reports) {
  const f = { all: 0, DRAFT: 0, PENDING_APPROVAL: 0, APPROVED: 0, SENT: 0, REJECTED: 0 }
  for (const r of reports || []) { f.all++; const s = effectiveStatus(r); if (s in f) f[s]++ }
  return f
}

/** Onayımı bekleyenler: PENDING_APPROVAL + (admin → hepsi; takım yöneticisi/PO → kendi takımı). */
export function approvalQueue(reports, { isAdmin, isAudit, teamId }) {
  if (isAudit) return []
  return (reports || []).filter((r) => r.status === 'PENDING_APPROVAL' && (isAdmin || r.team_id === teamId))
}

export function filterByStatus(reports, chip, ctx) {
  if (!chip) return reports || []
  if (chip === 'MINE') { const q = new Set(approvalQueue(reports, ctx).map((r) => r.id)); return (reports || []).filter((r) => q.has(r.id)) }
  return (reports || []).filter((r) => effectiveStatus(r) === chip)
}

/** Sıralama: 'week|desc' (varsayılan), 'team|asc', 'status|asc', 'score|desc', 'updated|desc'. */
const STATUS_ORDER = { REJECTED: 0, DRAFT: 1, PENDING_APPROVAL: 2, APPROVED: 3, SENT: 4 }
export function sortReports(reports, sortKey, teamNameOf = () => '') {
  const [k, d] = String(sortKey || 'week|desc').split('|')
  const dir = d === 'asc' ? 1 : -1
  const val = (r) => {
    switch (k) {
      case 'team': return String(teamNameOf(r.team_id) || '').toLocaleLowerCase('tr')
      case 'status': return STATUS_ORDER[effectiveStatus(r)] ?? 9
      case 'score': return r.score == null ? -1 : r.score
      case 'updated': return r.updated_at || ''
      default: return (r.report_year || 0) * 100 + (r.week_no || 0)
    }
  }
  return [...(reports || [])].sort((a, b) => { const x = val(a), y = val(b); return x < y ? -dir : x > y ? dir : 0 })
}

/** Skor bandı (WeeklyScoreCalculator ile aynı eşikler). */
export function scoreBand(score) {
  if (score == null) return null
  return score >= 80 ? 'green' : score >= 60 ? 'amber' : 'red'
}

/** due_at (UTC ISO, saat dilimi eksiz) → {ms, past, d, h, m}. */
export function countdown(dueAtIso, now = Date.now()) {
  if (!dueAtIso) return null
  const t = Date.parse(/[zZ]|[+-]\d\d:\d\d$/.test(dueAtIso) ? dueAtIso : dueAtIso + 'Z')
  if (Number.isNaN(t)) return null
  const ms = t - now
  const abs = Math.abs(ms)
  return { ms, past: ms < 0, d: Math.floor(abs / 86400000), h: Math.floor((abs % 86400000) / 3600000), m: Math.floor((abs % 3600000) / 60000) }
}

/** Önceki haftaya göre fark; ikisi de sayı değilse null. */
export function delta(cur, prev) {
  if (cur == null || prev == null || cur === '' || prev === '') return null
  const a = Number(cur), b = Number(prev)
  if (!Number.isFinite(a) || !Number.isFinite(b)) return null
  return a - b
}

/** Önceki raporun content_json'undan sayılar + notlar (bozuksa null). */
export function parsePrevContent(json) {
  if (!json) return null
  try {
    const c = JSON.parse(json)
    return {
      item1: c.item1 || {}, item2: c.item2 || {}, item3: c.item3 || {},
      channels: Array.isArray(c.item4?.channels) ? c.item4.channels : [],
    }
  } catch { return null }
}

/** Yıl özeti CSV: takım × hafta × durum × skor × kişiler. */
export function buildYearCsv(reports, teamNameOf, t) {
  const heads = [t('wr.colWeek'), t('wr.team'), t('wr.statusCol'), t('wr.colScore'), t('wr.colCreated'), t('wr.colUpdated'), t('wr.colApproved'), t('wr.colSent')]
  const rows = (reports || []).map((r) => [
    `${r.report_year}-W${String(r.week_no).padStart(2, '0')}`, teamNameOf(r.team_id) || r.team_id, effectiveStatus(r), r.score ?? '',
    r.created_by || '', r.updated_at || '', r.approved_by || '', r.sent_at || '',
  ])
  return csvRows([heads, ...rows])
}

/** URL eşlemesi (`w_` öneki). */
export function toUrlMapping({ selectedId, selTeamId, year, weekFilter, statusChip, sort, currentYear }) {
  return {
    w_id: selectedId || null,
    w_team: selTeamId || null,
    w_year: year && year !== currentYear ? year : null,
    w_week: weekFilter || null,
    w_st: statusChip || null,
    w_sort: sort && sort !== 'week|desc' ? sort : null,
  }
}
