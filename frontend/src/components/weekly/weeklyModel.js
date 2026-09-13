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

/** Yönetici yıl özeti (2026-09-13, ikinci tur): tamamlama panosundaki takım × hafta matrisinin
 *  CSV'si — hücre = durum, ikinci blok = skor. Yazdırılabilir HTML'i buildYearSummaryHtml üretir. */
export function buildYearSummaryCsv(data, t) {
  const weeks = Array.from({ length: data?.weeks || 0 }, (_, i) => i + 1)
  const st = (k) => t(`wrc.status.${k}`)
  const head = [t('wrc.team'), ...weeks.map((w) => `W${String(w).padStart(2, '0')}`), t('wrc.status.APPROVED'), t('wrc.missingShort')]
  const rows = (data?.teams || []).map((tm) => {
    const byWeek = new Map((tm.cells || []).map((c) => [c.week, c]))
    return [tm.team_name, ...weeks.map((w) => { const c = byWeek.get(w); return c ? (c.score != null ? `${st(c.status)} (${c.score})` : st(c.status)) : '' }), tm.approved ?? '', tm.missing ?? '']
  })
  return csvRows([head, ...rows])
}

const YS_COLORS = { MISSING: '#e5e7eb', DRAFT: '#fde68a', PENDING_APPROVAL: '#bfdbfe', APPROVED: '#bbf7d0', REJECTED: '#fecaca' }
const escHtml = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))

/** Yıl özetinin kendi başına yazdırılabilir HTML'i (gizli iframe → print → "PDF olarak kaydet").
 *  Uygulama CSS'inden bağımsızdır: A4 yatay, takım × hafta ızgarası, hücrede skor, altta lejant. */
export function buildYearSummaryHtml(data, t, { generatedAt = new Date() } = {}) {
  const weeks = Array.from({ length: data?.weeks || 0 }, (_, i) => i + 1)
  const st = (k) => t(`wrc.status.${k}`)
  const cur = data?.current_week
  const head = weeks.map((w) => `<th class="w${w === cur ? ' cur' : ''}">${w}</th>`).join('')
  const rows = (data?.teams || []).map((tm) => {
    const byWeek = new Map((tm.cells || []).map((c) => [c.week, c]))
    const cells = weeks.map((w) => {
      const c = byWeek.get(w)
      const k = c?.status || 'MISSING'
      const txt = c?.score != null ? c.score : (k === 'MISSING' ? '' : '·')
      return `<td class="c" style="background:${YS_COLORS[k] || YS_COLORS.MISSING}" title="${escHtml(st(k))}">${escHtml(txt)}</td>`
    }).join('')
    return `<tr><td class="tm">${escHtml(tm.team_name)}${tm.reminder === false ? ' <span class="mute">⏸</span>' : ''}</td>${cells}<td class="sum">${escHtml(tm.approved ?? 0)} / ${escHtml(tm.missing ?? 0)}</td></tr>`
  }).join('')
  const legend = Object.keys(YS_COLORS).map((k) => `<span class="lg"><i style="background:${YS_COLORS[k]}"></i>${escHtml(st(k))}</span>`).join('')
  const title = `${escHtml(t('wr.yearSummary'))} · ${escHtml(data?.year ?? '')}`
  const stamp = escHtml(generatedAt.toISOString().slice(0, 16).replace('T', ' '))
  return `<!doctype html><html><head><meta charset="utf-8"><title>${title}</title><style>
@page{size:A4 landscape;margin:12mm}
body{font:11px/1.35 -apple-system,"Segoe UI",Roboto,Arial,sans-serif;color:#111;margin:0}
h1{font-size:16px;margin:0 0 2px}.sub{color:#555;font-size:10px;margin:0 0 10px}
table{border-collapse:collapse;width:100%}th,td{border:1px solid #d1d5db;padding:2px 3px;text-align:center}
th.w{font-size:9px;width:16px}th.cur,td.cur{outline:2px solid #2563eb}
td.tm{text-align:left;font-weight:600;white-space:nowrap}td.c{font-size:9px;font-variant-numeric:tabular-nums}
td.sum{white-space:nowrap;font-weight:600}.mute{color:#999}
.legend{margin-top:8px;font-size:10px;color:#333;display:flex;gap:12px;flex-wrap:wrap}
.lg i{display:inline-block;width:10px;height:10px;border:1px solid #9ca3af;margin-right:3px;vertical-align:-1px}
.note{margin-top:6px;font-size:9px;color:#666}
</style></head><body>
<h1>${title}</h1><p class="sub">${escHtml(t('wr.yearSummaryGenerated'))}: ${stamp} · ${escHtml(t('wrc.missing', data?.total_missing ?? 0))}</p>
<table><thead><tr><th>${escHtml(t('wrc.team'))}</th>${head}<th>${escHtml(t('wrc.sum'))}</th></tr></thead><tbody>${rows}</tbody></table>
<div class="legend">${legend}</div>
<p class="note">${escHtml(t('wr.yearSummaryNote'))}</p>
</body></html>`
}
