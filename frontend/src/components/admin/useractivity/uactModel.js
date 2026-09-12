import { csvRows } from '../../../utils/csv.js'
import { localDayKey } from '../../../utils/localDay.js'

/**
 * Kullanıcı / Oturum paneli saf modeli (2026-09-13 zenginleştirme) — React'siz, test edilebilir.
 * Sunucu tel biçimi snake_case; burada dönüştürülmez, yalnız türetilir.
 */

/** Süzgeç durumu (URL `u_*`). range: '24h' | '7d' — özet sayılarını seçer; team/role tabloları daraltır. */
export const EMPTY_FILTERS = Object.freeze({ team: '', role: '', range: '24h' })
export const FILTER_PARAMS = { team: 'u_team', role: 'u_role', range: 'u_range' }

export function filtersToParams(f) {
  return {
    u_team: f.team || null,
    u_role: f.role || null,
    u_range: f.range && f.range !== '24h' ? f.range : null,
  }
}
export function paramsToFilters(read) {
  const range = read('u_range', '24h')
  return { team: read('u_team', '') || '', role: read('u_role', '') || '', range: range === '7d' ? '7d' : '24h' }
}
export function hasActiveFilter(f) { return !!(f.team || f.role || (f.range && f.range !== '24h')) }

/** Satır süzgeci: team_name / team_id / system_role alanı olan her listeye uygulanır. */
export function rowMatches(row, f) {
  if (!row) return false
  if (f.team) {
    const tid = row.team_id != null ? String(row.team_id) : ''
    if (tid !== f.team && (row.team_name || '') !== f.team) return false
  }
  if (f.role && (row.system_role || row.role || '') !== f.role) return false
  return true
}

/** Sekme anahtarı → nav etiketi anahtarı (Nav.jsx ile aynı liste; kapı testi kaynağı okur). */
export const TAB_LABEL_KEYS = Object.freeze({
  dashboard: 'nav.dashboard', all: 'nav.all', domains: 'nav.domains', uptime: 'nav.uptime', forecast: 'nav.forecast',
  renewal: 'nav.renewal', 'renewal-guide': 'nav.renewalGuide', http: 'nav.http', domain: 'nav.domainmon', port: 'nav.port',
  dns: 'nav.dns', keyword: 'nav.keyword', ping: 'nav.ping', page: 'nav.page', pagespeed: 'nav.pagespeed', scripted: 'nav.scripted',
  warnings: 'nav.warnings', incidents: 'nav.incidents', maintenance: 'nav.maintenance', alerthistory: 'nav.alertHistory',
  stats: 'nav.stats', weakalgo: 'nav.weakAlgo', weeklyreports: 'nav.weeklyReports', 'incident-history': 'nav.incidentHistory',
  activity: 'nav.activity', myactivity: 'nav.myActivity', system: 'nav.system', monitorchanges: 'nav.monitorChanges',
  admin: 'nav.admin', health: 'nav.health', permissions: 'nav.permissions', sqlplayground: 'nav.sqlPlayground',
  'login-issues': 'nav.loginIssues', help: 'nav.help', settings: 'nav.settings',
})
export function tabLabel(tab, t) {
  const k = TAB_LABEL_KEYS[tab]
  return k ? t(k) : (tab || '—')
}
/** Hiç açılmayan sayfalar: nav'daki tüm sekmeler − kullanım verisinde görülenler. */
export function unusedTabs(usagePages = []) {
  const seen = new Set((usagePages || []).map((p) => p.tab))
  return Object.keys(TAB_LABEL_KEYS).filter((k) => !seen.has(k))
}

/** Boşta bandı: <5 dk canlı, <30 dk boşta, sonrası uzakta; -1 = bilinmiyor. */
export function idleBand(sec) {
  if (sec == null || sec < 0) return 'unknown'
  if (sec < 300) return 'live'
  if (sec < 1800) return 'idle'
  return 'away'
}

/** Giriş durumu pili: aktif oturum > bugün > bu hafta > bu ay > atıl (30+) > hiç. */
export function loginStatus(row, activeSet = new Set(), now = Date.now()) {
  if (activeSet.has(String(row.username || '').toLowerCase())) return 'active'
  const ll = row.last_login_at
  if (!ll) return 'never'
  const t = Date.parse(ll.endsWith('Z') ? ll : ll + 'Z')
  if (Number.isNaN(t)) return 'never'
  const days = (now - t) / 86_400_000
  if (days < 1) return 'today'
  if (days < 7) return 'week'
  if (days < 30) return 'month'
  return 'dormant'
}
export const STATUS_ORDER = ['active', 'today', 'week', 'month', 'dormant', 'never']

/** Göreli zaman: {unit, n} — çağıran t('uact.rel.<unit>', n) ile yazar. */
export function relTime(iso, now = Date.now()) {
  if (!iso) return null
  const t = Date.parse(iso.endsWith('Z') ? iso : iso + 'Z')
  if (Number.isNaN(t)) return null
  const s = Math.max(0, Math.round((now - t) / 1000))
  if (s < 60) return { unit: 'sec', n: s }
  const m = Math.round(s / 60); if (m < 60) return { unit: 'min', n: m }
  const h = Math.round(m / 60); if (h < 24) return { unit: 'hour', n: h }
  const d = Math.round(h / 24); if (d < 30) return { unit: 'day', n: d }
  const mo = Math.round(d / 30); if (mo < 12) return { unit: 'month', n: mo }
  return { unit: 'year', n: Math.round(mo / 12) }
}

/** Süre (sn) → "1 sa 05 dk" parçaları. */
export function splitDuration(sec) {
  const s = Math.max(0, Math.round(Number(sec) || 0))
  return { h: Math.floor(s / 3600), m: Math.floor((s % 3600) / 60), s: s % 60 }
}

/** Başarısız oranı → ton: %20+ tehlike, %5+ uyarı, altı nötr; hiç istek yoksa nötr. */
export function failedTone(failed, logins) {
  const tot = (Number(failed) || 0) + (Number(logins) || 0)
  if (tot === 0 || !failed) return 'neutral'
  const r = failed / tot
  return r >= 0.2 ? 'danger' : r >= 0.05 ? 'warn' : 'neutral'
}
export function failedRatio(failed, logins) {
  const tot = (Number(failed) || 0) + (Number(logins) || 0)
  return tot === 0 ? 0 : Math.round((failed / tot) * 100)
}

/** Günlük seriden sparkline dizisi (7 gün). */
export function sparkFrom(series, field) {
  return ((series && series.day) || []).map((b) => Number(b[field] ?? 0))
}
/** Bugün vs önceki gün ortalaması (6 gün) — yüzde; veri yoksa null. */
export function deltaVsAvg(series, field) {
  const arr = sparkFrom(series, field)
  if (arr.length < 3) return null
  const today = arr[arr.length - 1]
  const prev = arr.slice(0, -1)
  const avg = prev.reduce((a, b) => a + b, 0) / prev.length
  if (avg === 0) return today === 0 ? 0 : null
  return Math.round(((today - avg) / avg) * 100)
}

/** Isı haritası mesai-dışı hücresi (AuditService.isOffHours ile aynı kural). */
export function isOffHourCell(dow, hour, office = { start: 8, end: 20 }) {
  if (dow >= 5) return true                      // Cmt/Paz tamamı
  return hour < (office?.start ?? 8) || hour >= (office?.end ?? 20)
}

/** Anomali bayrağı sözlüğü — anahtar listesi (i18n `uact.flagHelp.<KEY>`). */
export const FLAG_KEYS = ['OFF_HOURS', 'UNUSUAL_IP', 'GEO_VELOCITY', 'BRUTE_FORCE', 'RATE_LIMITED']
export function splitFlags(flags) {
  return String(flags || '').split(',').map((s) => s.trim()).filter(Boolean)
}

/** Kullanıcı satırlarını sıralar: col sayıysa sayısal, değilse metin; asc/desc. */
export function sortRows(rows, col, dir) {
  const arr = [...(rows || [])]
  arr.sort((a, b) => {
    const av = a?.[col], bv = b?.[col]
    const cmp = typeof av === 'number' && typeof bv === 'number' ? av - bv : String(av ?? '').localeCompare(String(bv ?? ''))
    return dir === 'asc' ? cmp : -cmp
  })
  return arr
}

/** CSV'ler — hücre kaçışı utils/csv.js'te (csvRows). BOM'u çağıran ekler. */
export function sessionsCsv(rows, t) {
  const head = [t('uact.colUser'), t('uact.colRole'), t('uact.colTeam'), t('uact.colLoginAt'), t('uact.colDuration'), t('uact.colIdle'), t('uact.colLastTab'), t('uact.colIp'), t('uact.colLocation'), t('uact.colBrowser')]
  return csvRows([head, ...(rows || []).map((u) => [u.username, u.system_role, u.team_name, u.login_at, u.duration_min, u.idle_sec, u.last_tab, u.ip, [u.city, u.country].filter(Boolean).join(', '), u.user_agent])])
}
export function loginStatusCsv(rows, t) {
  const head = [t('uact.colUser'), t('uact.colRole'), t('uact.colTeam'), t('uact.colLastLogin'), t('uact.colPrevLogin'), t('uact.colLastFailed'), t('uact.colFailedCount'), t('uact.detailLoginMethod')]
  return csvRows([head, ...(rows || []).map((r) => [r.username, r.system_role, r.team_name, r.last_login_at, r.prev_login_at, r.last_failed_at, r.failed_since_login ?? 0, r.last_login_method])])
}
export function anomaliesCsv(rows, t) {
  const head = [t('uact.colTime'), t('uact.colUser'), t('uact.colIp'), t('uact.colLocation'), t('uact.colFlags'), t('uact.colOutcome'), t('uact.colReason'), t('uact.ackCol')]
  return csvRows([head, ...(rows || []).map((r) => [r.time, r.actor, r.ip, [r.city, r.country].filter(Boolean).join(', '), r.flags, r.outcome, r.reason, r.ack ? `${r.ack.by} ${r.ack.at}` : ''])])
}
export function usageCsv(pages, t) {
  const head = [t('uact.colPage'), t('uact.colMinutes'), t('uact.colUsers'), t('uact.colShare'), t('uact.colLastSeen')]
  return csvRows([head, ...(pages || []).map((p) => [tabLabel(p.tab, t), p.minutes, p.users, p.share, p.last_seen])])
}

/** Takım tablosu: en çok girişten aza; oran çubuğu için max. */
export function teamBars(byTeam = []) {
  const max = Math.max(1, ...byTeam.map((r) => Number(r.count) || 0))
  return byTeam.map((r) => ({ ...r, pct: Math.round(((Number(r.count) || 0) / max) * 100) }))
}

/** Yerel gün etiketi (sekme kullanım trendi vb.). */
export function dayKey(iso) { return localDayKey(iso) }
