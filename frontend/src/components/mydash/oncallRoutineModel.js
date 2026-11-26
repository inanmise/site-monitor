/**
 * Kişisel Panom "Nöbet rutinim" kutusunun SAF modeli (2026-11-26). Kaynak `GET /api/oncall-routines/me` (menü rozetiyle aynı
 * uç ve getirici): üyesi / yöneticisi olduğu, rutini yapılandırılmış ve açık takımlar (≤ 20, sunucu süzer). Kutu en çok üç
 * takım gösterir — nöbetçisi olduğu takımlar önce.
 *
 * <p>Ton kuralı (bilinmeyen asla yeşil değildir):
 *  - `danger`  — gösterilen takımlardan birinde geciken zorunlu görev var ya da gün kaçırıldı (MISSED);
 *  - `warning` — bekleyen görev var / gün açık / eksik kapandı (INCOMPLETE);
 *  - `success` — gösterilen HER takımın günü tamamlandı (iş günü olmayan bir takım da listedeyse yeşil DEĞİL — kural "her
 *                takım tamamlandı");
 *  - `muted`   — bilinmiyor / okunamadı / takım yok / iş günü değil / duraklatıldı.
 */

export const ROUTINE_WIDGET_MAX_TEAMS = 3

/** Kutunun durum rozetinde adı olan gün durum kodları (sunucu §1.6). Bilinmeyen kod "Bilinmiyor" okunur. */
export const ROUTINE_STATUS_CODES = Object.freeze([
  'OPEN', 'COMPLETED', 'COMPLETED_PARTIAL', 'INCOMPLETE', 'MISSED', 'NONE', 'NOT_WORKDAY', 'FUTURE', 'PAUSED',
])

const num = (v) => {
  if (v == null || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) && n >= 0 ? Math.floor(n) : null
}

/** Gün tamamlandı mı (iki tamamlanma kodu). */
const completed = (status) => status === 'COMPLETED' || status === 'COMPLETED_PARTIAL'

/** Sunucu durum kodu → `mydash.or.status.<KOD>` anahtarının kodu (bilinmeyen / yok → `UNKNOWN`). */
export function statusCode(row) {
  const s = typeof row?.status === 'string' ? row.status.toUpperCase() : null
  if (s && ROUTINE_STATUS_CODES.includes(s)) return s
  return row?.workday === false ? 'NOT_WORKDAY' : 'UNKNOWN'
}

/** Takım satırının tonu. */
export function teamTone(row) {
  if (!row || typeof row !== 'object') return 'muted'
  const s = statusCode(row)
  if (completed(s)) return 'success'
  if (s === 'MISSED') return 'danger'
  if (row.workday !== true || s === 'NOT_WORKDAY' || s === 'PAUSED' || s === 'FUTURE') return 'muted'
  if ((num(row.overdue) ?? 0) > 0) return 'danger'
  if ((num(row.pending) ?? 0) > 0) return 'warning'
  if (s === 'INCOMPLETE') return 'warning'
  if (s === 'OPEN' || s === 'NONE') {
    // Bekleyen yok ama gün henüz tamamlanmadı: tamamlanmayı bekliyor (yeşil DEĞİL); zorunlu iş hiç yoksa nötr
    return num(row.required) === 0 ? 'muted' : 'warning'
  }
  return 'muted'
}

/** İlerleme yüzdesi (aşağı yuvarlanır; %100 yalnız hepsi çözülünce); bilinmiyorsa null. */
export function progressOf(row) {
  const req = num(row?.required)
  const res = num(row?.resolved)
  if (req == null || res == null || req <= 0) return null
  if (res >= req) return 100
  return Math.floor((res / req) * 100)
}

/**
 * Yanıt → kutu özeti. `known` false = yanıt okunamadı / biçimi bilinmiyor (kutu gri, asla "takımın yok" demez).
 * @returns {{ known: boolean, teams: object[], more: number, tone: string, total: number, overdue: number, pending: number,
 *            problems: number, responsibleTeams: number, handoverPending: number }}
 */
export function routineSummary(data, max = ROUTINE_WIDGET_MAX_TEAMS) {
  const rows = Array.isArray(data?.teams) ? data.teams.filter((r) => r && typeof r === 'object' && r.team_id != null) : null
  if (!rows) {
    return { known: false, teams: [], more: 0, tone: 'muted', total: 0, overdue: 0, pending: 0, problems: 0, responsibleTeams: 0, handoverPending: 0 }
  }
  // Nöbetçisi olduğum takımlar önce; sonra sunucu sırası (kararlı)
  const ordered = rows.map((r, i) => ({ r, i }))
    .sort((a, b) => (a.r.responsible_me === true ? 0 : 1) - (b.r.responsible_me === true ? 0 : 1) || a.i - b.i)
    .map((x) => x.r)
  const limit = Math.max(0, Number(max) || 0)
  const shown = ordered.slice(0, limit).map((r) => ({ ...r, tone: teamTone(r), pct: progressOf(r), code: statusCode(r) }))
  const tones = shown.map((r) => r.tone)
  let tone = 'muted'
  if (shown.length > 0) {
    if (tones.includes('danger')) tone = 'danger'
    else if (tones.includes('warning')) tone = 'warning'
    else if (tones.every((x) => x === 'success')) tone = 'success'
  }
  const sum = (key) => shown.reduce((acc, r) => acc + (num(r[key]) ?? 0), 0)
  return {
    known: true,
    teams: shown,
    more: Math.max(0, ordered.length - shown.length),
    tone,
    total: ordered.length,
    overdue: sum('overdue'),
    pending: sum('pending'),
    problems: sum('problems_open'),
    responsibleTeams: shown.filter((r) => r.responsible_me === true).length,
    handoverPending: shown.filter((r) => r.handover_pending_ack === true).length,
  }
}
