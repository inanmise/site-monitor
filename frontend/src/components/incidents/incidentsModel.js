import {
  Globe, Radio, EthernetPort, Waypoints, CalendarClock, TextSearch, FileCheck, Gauge, Workflow, ShieldCheck,
} from 'lucide-react'
import { parseUtc, formatIncidentTime } from '../../utils/incidentMeta.js'

/**
 * Olaylar konsolunun SAF modeli — bileşenlerden bağımsız yardımcılar (test edilebilir, React'siz).
 *
 * <p>Sunucu (IncidentsController) yalnız `status | rootCause | q | since | until | sort` süzer ve `total` +
 * `type_counts` döner. Konsolun özet kartları ve "hızlı süzgeçleri" (onaylı / kritik / takımsız / takımım)
 * sunucuda karşılığı olmayan boyutlardır: sayılar AÇIK olay kümesinden (ayrı, 200'e kadar satırlı bir istek)
 * türetilir, süzgeç ise yüklenen sayfaya istemci tarafında uygulanır — ekran bunu açıkça söyler
 * (`incov.pageFacetNote`). Uydurma sayı yok: sunucunun döndürmediği hiçbir şey gösterilmez.
 */

/** İzleme türü → kenar çubuğuyla AYNI ikon (Nav.jsx) — kullanıcı türü iki yerde de aynı simgeden tanır. */
export const MONITOR_TYPE_ICON = {
  http: Globe, ping: Radio, port: EthernetPort, dns: Waypoints, domain: CalendarClock,
  keyword: TextSearch, page: FileCheck, pagespeed: Gauge, scripted: Workflow, cert: ShieldCheck,
}
export const MONITOR_TYPE_LABEL_KEY = {
  http: 'nav.http', ping: 'nav.ping', port: 'nav.port', dns: 'nav.dns', domain: 'nav.domainmon',
  keyword: 'nav.keyword', page: 'nav.page', pagespeed: 'nav.pagespeed', scripted: 'nav.scripted', cert: 'incov.typeCert',
}

/** Önem seviyesi (AlertEvent.alertLevel) → sıra + i18n + ton (MonitorStatsBar / rozet renkleri). */
export const SEVERITY = {
  CRITICAL: { rank: 3, key: 'alh.level.critical', tone: 'critical' },
  HIGH:     { rank: 2, key: 'alh.level.high',     tone: 'high' },
  WARNING:  { rank: 1, key: 'alh.level.warning',  tone: 'warning' },
}
export const SEVERITY_ORDER = ['CRITICAL', 'HIGH', 'WARNING']
export function severityMeta(level) {
  return SEVERITY[String(level || '').toUpperCase()] || { rank: 0, key: null, tone: 'total' }
}

/** Özet kartı anahtarları (`stat` süzgeç değeri de bunlardır; `open`/`resolved` durum seçicisinden de gelir). */
export const TILE_KEYS = ['open', 'ack', 'resolved24', 'critical', 'unassigned', 'mine']
const OPEN_ONLY = new Set(['open', 'ack', 'critical', 'unassigned', 'mine'])
const CLIENT_FACET = new Set(['ack', 'critical', 'unassigned', 'mine'])

export const FILTER_DEFAULTS = Object.freeze({ stat: '', rootCause: '', q: '', since: '', until: '', level: '', team: '' })

/** Durum seçicisinin değeri `stat`tan türer: açık-altkümesi kartları "sürüyor", çözülmüş kartlar "çözüldü". */
export function statusOf(stat) {
  if (OPEN_ONLY.has(stat)) return 'ongoing'
  if (stat === 'resolved' || stat === 'resolved24') return 'resolved'
  return 'all'
}

/** Şu andan 24 saat önce, sunucunun beklediği zone'suz UTC biçiminde (`yyyy-MM-ddTHH:mm:ss`). */
export function iso24hAgo(nowMs) {
  return new Date(nowMs - 24 * 3600 * 1000).toISOString().slice(0, 19)
}

/** Sunucu istek parametreleri — istemci-taraflı boyutlar (level/team/ack…) BURADA YOK (sunucu tanımıyor). */
export function serverParams(filters, sort, nowMs) {
  const status = statusOf(filters.stat)
  return {
    status: status === 'all' ? '' : status,
    rootCause: filters.rootCause || '',
    q: filters.q || '',
    since: filters.stat === 'resolved24' ? iso24hAgo(nowMs) : (filters.since || ''),
    until: filters.until || '',
    sort: sort?.by || undefined,
    dir: sort?.dir || 'desc',
  }
}

export const isOpen = (inc) => inc?.status === 'ongoing'
export const isAcked = (inc) => Boolean(inc?.acknowledged)
/** Pano şeridi: açık+onaysız → open, açık+onaylı → ack, kapalı → resolved. */
export function laneOf(inc) {
  if (!isOpen(inc)) return 'resolved'
  return isAcked(inc) ? 'ack' : 'open'
}
export const LANES = ['open', 'ack', 'resolved']

/** Yüklenen sayfaya istemci süzgeci: hızlı süzgeç kartı + önem + takım. `teamId` = oturum takımı ("takımım"). */
export function clientFacet(rows, filters, teamId) {
  const stat = filters.stat
  return (rows || []).filter((r) => {
    if (stat === 'ack' && !isAcked(r)) return false
    if (stat === 'critical' && String(r.alert_level || '').toUpperCase() !== 'CRITICAL') return false
    if (stat === 'unassigned' && r.team_id != null) return false
    if (stat === 'mine' && !(teamId != null && Number(r.team_id) === Number(teamId))) return false
    if (filters.level && String(r.alert_level || '').toUpperCase() !== filters.level) return false
    if (filters.team && String(r.team_id ?? '') !== String(filters.team)) return false
    return true
  })
}
export const hasClientFacet = (filters) => CLIENT_FACET.has(filters.stat) || Boolean(filters.level) || Boolean(filters.team)

/** Özet: AÇIK küme (sunucu toplamı + en fazla 200 satır) ve son 24 saatte çözülen toplamı → kart sayıları. */
export function summarize(openRows, openTotal, resolved24Total, teamId) {
  const rows = openRows || []
  const lvl = (r) => String(r.alert_level || '').toUpperCase()
  return {
    open: openTotal ?? rows.length,
    ack: rows.filter(isAcked).length,
    resolved24: resolved24Total ?? 0,
    critical: rows.filter((r) => lvl(r) === 'CRITICAL').length,
    unassigned: rows.filter((r) => r.team_id == null).length,
    mine: teamId == null ? 0 : rows.filter((r) => Number(r.team_id) === Number(teamId)).length,
    // Türetilmiş sayılar yalnız yüklenen açık satırlardan: toplam daha büyükse ekran "ilk N üzerinden" der.
    exact: openTotal == null || rows.length >= openTotal,
    sampled: rows.length,
  }
}

/** Olay → kaynağı: izleme alarmı ilgili sekme + odak; sertifika alarmı (monitor_id yok) pano + alan adı süzgeci. */
export function incidentHref(inc) {
  const m = inc?.monitor
  if (m?.monitor_id != null && m?.tab) return `?tab=${encodeURIComponent(m.tab)}&monitor=${encodeURIComponent(m.monitor_id)}`
  const dom = inc?.domain || m?.name || ''
  return `?tab=${encodeURIComponent(m?.tab || 'dashboard')}${dom ? `&domain=${encodeURIComponent(dom)}` : ''}`
}

/** Paylaşılabilir derin bağlantı — uygulama düzeyi `incident` anahtarı (App.jsx / e-postalarla aynı biçim). */
export function incidentLink(id) {
  let origin = ''
  try { origin = window.location.origin + window.location.pathname } catch { /* jsdom */ }
  return `${origin}?tab=incidents&incident=${encodeURIComponent(id)}`
}

/** Satırı ayırt eden ad (a11y.rowAction için): izleme + başlangıç. */
export function rowName(inc, dateLocale) {
  return `${inc?.monitor?.name || inc?.domain || '—'} · ${formatIncidentTime(inc?.started_at, dateLocale)}`
}

/** Sistem jetonu mu (kişi değil)? `resolved_by` bir SİCİL/ad ya da SİSTEM JETONU olabilir — ayrımı arayüz yapar. */
export const RESOLVED_BY_TOKEN_KEY = {
  system: 'incov.resolvedBy.system',
  inventory_delete: 'incov.resolvedBy.inventoryDelete',
}

const ts = (s) => { const d = parseUtc(s); return d ? d.getTime() : null }

/**
 * Zaman çizelgesi: açılış → bildirimler → onay → yorumlar → çözüm, zamana göre. Zamanı olmayan
 * olay (onay: DTO'da acknowledged_at yok) açılışın hemen ardına düşer ve "zaman kaydı yok" der.
 */
export function buildTimeline({ incident, comments = [], notifications = [], ackAt = null, ackBy = null }) {
  if (!incident) return []
  const openedAt = ts(incident.started_at) ?? 0
  const items = [{ id: 'opened', kind: 'opened', at: openedAt, when: incident.started_at, level: incident.alert_level, message: incident.message }]
  for (const n of notifications || []) {
    items.push({
      id: `n-${n.id}`, kind: 'notified', at: ts(n.sent_at) ?? openedAt + 1, when: n.sent_at,
      recipient: n.recipient_name || n.recipient_email || '', trigger: n.trigger || '',
      email: n.email_status || null, webhook: n.webhook_status || null,
    })
  }
  const ackWhen = ackAt || incident.acknowledged_at || null
  if (isAcked(incident) || ackWhen) {
    items.push({ id: 'ack', kind: 'acknowledged', at: ts(ackWhen) ?? openedAt + 2, when: ackWhen, by: ackBy || incident.acknowledged_by || null })
  }
  for (const c of comments || []) {
    items.push({ id: `c-${c.id}`, kind: 'comment', at: ts(c.created_at) ?? openedAt + 3, when: c.created_at,
      author: c.author_name || c.author_username || '—', authorUsername: c.author_username || null, body: c.body, commentId: c.id })
  }
  if (!isOpen(incident) && incident.resolved_at) {
    items.push({ id: 'resolved', kind: 'resolved', at: ts(incident.resolved_at) ?? Number.MAX_SAFE_INTEGER, when: incident.resolved_at, by: incident.resolved_by || null })
  }
  // Kararlı sıralama: aynı ana düşenler ekleme sırasını korur (açılış → bildirim → onay → yorum → çözüm).
  return items.map((it, i) => [it, i]).sort((a, b) => a[0].at - b[0].at || a[1] - b[1]).map(([it]) => it)
}

/** Etkin süzgeç çipleri — [{ key, patch, label }] (label çağıranın t'siyle kurulur; burada yalnız ham parçalar). */
export function activeFilters(filters) {
  const out = []
  if (filters.stat) out.push({ key: 'stat', value: filters.stat, patch: { stat: '' } })
  if (filters.rootCause) out.push({ key: 'rootCause', value: filters.rootCause, patch: { rootCause: '' } })
  if (filters.q) out.push({ key: 'q', value: filters.q, patch: { q: '' } })
  if (filters.since) out.push({ key: 'since', value: filters.since, patch: { since: '' } })
  if (filters.until) out.push({ key: 'until', value: filters.until, patch: { until: '' } })
  if (filters.level) out.push({ key: 'level', value: filters.level, patch: { level: '' } })
  if (filters.team) out.push({ key: 'team', value: filters.team, patch: { team: '' } })
  return out
}

/** Satırlardan takım seçenekleri (sunucu takım süzgeci sunmadığı için dizin sorgusu gerekmez). */
export function teamOptions(...rowLists) {
  const map = new Map()
  for (const rows of rowLists) for (const r of rows || []) if (r?.team_id != null && r.team_name) map.set(String(r.team_id), r.team_name)
  return [...map.entries()].map(([id, name]) => ({ id, name })).sort((a, b) => a.name.localeCompare(b.name))
}

/** Süzgeç durumu ↔ URL (sayfa-durumu anahtarları; uygulama düzeyi tab/domain/monitor/incident'a DOKUNULMAZ). */
export const URL_KEYS = { stat: 'stat', rootCause: 'type', q: 'q', since: 'from', until: 'to', level: 'level', team: 'team' }
export function filtersFromUrl(read) {
  const f = { ...FILTER_DEFAULTS }
  for (const [k, urlKey] of Object.entries(URL_KEYS)) {
    const v = read(urlKey)
    if (v) f[k] = v
  }
  if (f.stat && !TILE_KEYS.includes(f.stat) && f.stat !== 'resolved') f.stat = ''
  if (f.level && !SEVERITY_ORDER.includes(f.level)) f.level = ''
  return f
}
export function filtersToUrl(filters, view, sort) {
  const out = {}
  for (const [k, urlKey] of Object.entries(URL_KEYS)) out[urlKey] = filters[k] || null
  out.view = view === 'list' ? 'list' : null
  out.sort = sort?.by ? `${sort.by}:${sort.dir}` : null
  return out
}
export function sortFromUrl(read) {
  const raw = read('sort')
  if (!raw) return { by: '', dir: 'desc' }
  const [by, dir] = String(raw).split(':')
  return ['started', 'status', 'severity', 'type'].includes(by) ? { by, dir: dir === 'asc' ? 'asc' : 'desc' } : { by: '', dir: 'desc' }
}
