import {
  Globe, Radio, EthernetPort, Waypoints, CalendarClock, TextSearch, FileCheck, Gauge, Workflow, ShieldCheck, DatabaseSearch,
} from 'lucide-react'
import { parseUtc, formatIncidentTime } from '../../utils/incidentMeta.js'

/**
 * Olaylar konsolunun SAF modeli — bileşenlerden bağımsız yardımcılar (test edilebilir, React'siz).
 *
 * <p>2026-11-13 (zenginleştirme): sunucu (IncidentsController) durum / kök neden / arama / tarih ile BİRLİKTE önem (CSV),
 * takım, onaylı, takımsız, takımım, tekrarlayan ve çözülme anı (`resolvedSince`) süzgeçlerini uygular; yanıt
 * `server_facets: true` taşır. Özet kartları ayrı uçtan (`/summary`) KESİN sayılardır. Eski sunucu (alan yok) için
 * önceki yol korunur: hızlı süzgeçler yüklenen sayfaya istemcide uygulanır ve ekran bunu söyler (`incov.pageFacetNote`),
 * özet açık kümenin ilk 200 satırından türetilir. Uydurma sayı yok: okunamayan sayı `null` (ekranda "—").
 */

/** İzleme türü → kenar çubuğuyla AYNI ikon (Nav.jsx) — kullanıcı türü iki yerde de aynı simgeden tanır. */
export const MONITOR_TYPE_ICON = {
  http: Globe, ping: Radio, port: EthernetPort, dns: Waypoints, domain: CalendarClock,
  keyword: TextSearch, page: FileCheck, pagespeed: Gauge, scripted: Workflow, dbquery: DatabaseSearch, cert: ShieldCheck,
}
export const MONITOR_TYPE_LABEL_KEY = {
  http: 'nav.http', ping: 'nav.ping', port: 'nav.port', dns: 'nav.dns', domain: 'nav.domainmon',
  keyword: 'nav.keyword', page: 'nav.page', pagespeed: 'nav.pagespeed', scripted: 'nav.scripted', dbquery: 'nav.dbquery', cert: 'incov.typeCert',
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

export const FILTER_DEFAULTS = Object.freeze({ stat: '', rootCause: '', q: '', since: '', until: '', level: '', team: '', recurring: '' })

/** Önem süzgecinin seçenekleri — tek seviye ya da "kritik ve yüksek" birleşimi (sunucu CSV kabul eder). */
export const LEVEL_OPTIONS = Object.freeze(['CRITICAL', 'HIGH', 'WARNING', 'CRITICAL,HIGH'])
/** CSV önem değeri → geçerli seviyeler (sıra SEVERITY_ORDER; geçersiz parça düşer). */
export function parseLevels(v) {
  const set = new Set(String(v || '').split(',').map((x) => x.trim().toUpperCase()).filter(Boolean))
  return SEVERITY_ORDER.filter((l) => set.has(l))
}

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

/**
 * Olay kapsamı (2026-09-28, org geneli salt okunur Olaylar) — sunucu sözleşmesiyle aynı (`scope=mine|others|all`).
 * "Takımımın olayları" varsayılandır ve BUGÜNKÜ görünümdür; diğer ekiplerin olayları salt okunur gelir.
 */
export const SCOPE_MINE = 'mine'
export const SCOPE_OTHERS = 'others'
export const SCOPE_ALL = 'all'
export const SCOPES = [SCOPE_MINE, SCOPE_OTHERS, SCOPE_ALL]
/** Bilinmeyen/boş değer → 'mine' (URL'den ya da sunucudan gelen her şey buradan geçer). */
export function normalizeIncidentScope(v) {
  return v === SCOPE_OTHERS || v === SCOPE_ALL ? v : SCOPE_MINE
}
/** İstek parçası — varsayılan (mine) GÖNDERİLMEZ: istek biçimi bugünküyle aynı kalır. */
export function scopeParam(scope) {
  const s = normalizeIncidentScope(scope)
  return s === SCOPE_MINE ? {} : { scope: s }
}

/**
 * Sunucu istek parametreleri (2026-11-13): temel anahtarlar HER ZAMAN (eski istek biçimi), sunucu süzgeçleri yalnız
 * seçiliyken — önem (`level` CSV; "Kritik" kartı seçili önemle KESİŞİR, kesişim boşsa eşleşmeyen `-`), takım (`teamId`),
 * onaylı / takımsız / takımım kartları, tekrarlayanlar ve "Çözüldü (24 sa)" (`resolvedSince` — ÇÖZÜLME anı; açılışa
 * bakmaz). Tarih gün olarak gider (`yyyy-MM-dd`) — sunucu İstanbul gün sınırına çevirir.
 */
export function serverParams(filters, sort, nowMs, scope = SCOPE_MINE) {
  const stat = filters.stat
  const status = statusOf(stat)
  const out = {
    status: status === 'all' ? '' : status,
    rootCause: filters.rootCause || '',
    q: filters.q || '',
    since: filters.since || '',
    until: filters.until || '',
    sort: sort?.by || undefined,
    dir: sort?.dir || 'desc',
    ...scopeParam(scope),
  }
  let levels = parseLevels(filters.level)
  if (stat === 'critical') levels = levels.length === 0 || levels.includes('CRITICAL') ? ['CRITICAL'] : ['-']
  if (levels.length) out.level = levels.join(',')
  if (filters.team) out.teamId = String(filters.team)
  if (stat === 'ack') out.acknowledged = 'true'
  if (stat === 'unassigned') out.teamless = 'true'
  if (stat === 'mine') out.mine = 'true'
  if (stat === 'resolved24') out.resolvedSince = iso24hAgo(nowMs)
  if (filters.recurring) out.recurring = 'true'
  return out
}

/**
 * Satırın sahipliği ve eylem hakları — SUNUCUDAN (`can_manage` / `can_act` / `can_delete`, IncidentsController).
 * Alan yoksa (eski yanıt) bugünkü davranış: kendi olayı sayılır, eylemler görünür, silme rol ipucuna bakar.
 */
export const isForeign = (inc) => inc?.can_manage === false
export const canActOn = (inc) => !isForeign(inc) && inc?.can_act !== false
export const canDeleteIncident = (inc, isAdmin = false) =>
  !isForeign(inc) && (inc?.can_delete != null ? inc.can_delete === true : Boolean(isAdmin))

export const isOpen = (inc) => inc?.status === 'ongoing'
export const isAcked = (inc) => Boolean(inc?.acknowledged)
/** Pano şeridi: açık+onaysız → open, açık+onaylı → ack, kapalı → resolved. */
export function laneOf(inc) {
  if (!isOpen(inc)) return 'resolved'
  return isAcked(inc) ? 'ack' : 'open'
}
export const LANES = ['open', 'ack', 'resolved']

/** Yüklenen sayfaya istemci süzgeci: hızlı süzgeç kartı + önem + takım. `teamId` = oturum takımı ("takımım"). */
/** ESKİ sunucu yedeği (yanıtta `server_facets` yok): hızlı süzgeç kartı + önem + takım YÜKLENEN sayfaya uygulanır. */
export function clientFacet(rows, filters, teamId) {
  const stat = filters.stat
  const levels = parseLevels(filters.level)
  return (rows || []).filter((r) => {
    if (stat === 'ack' && !isAcked(r)) return false
    if (stat === 'critical' && String(r.alert_level || '').toUpperCase() !== 'CRITICAL') return false
    if (stat === 'unassigned' && r.team_id != null) return false
    if (stat === 'mine' && !(teamId != null && String(r.team_id) === String(teamId))) return false
    if (levels.length && !levels.includes(String(r.alert_level || '').toUpperCase())) return false
    if (filters.team && String(r.team_id ?? '') !== String(filters.team)) return false
    if (filters.recurring && !(Number(r.recurrence_7d) >= 2)) return false
    return true
  })
}
export const hasClientFacet = (filters) => CLIENT_FACET.has(filters.stat) || Boolean(filters.level) || Boolean(filters.team)
  || Boolean(filters.recurring)

/** Toplu ONAYDA gönderilecek mi: seçilebilir ve henüz onaysız (onaylı satır yeniden onaylanmaz — ilk sahiplenme korunur). */
export const ackable = (inc) => isSelectable(inc) && !isAcked(inc)
/** Satır işlem için seçilebilir mi (toplu onay / çöz): açık + sunucu eylem bayrağı. Yabancı / izinsiz satır asla. */
export const isSelectable = (inc) => isOpen(inc) && canActOn(inc)
/** Yorum yazabilir mi — sunucu `can_comment` (7/24 operatörü başka ekibin olayına da yazar); alan yoksa eski kural. */
export const canCommentOn = (inc) => (inc?.can_comment != null ? inc.can_comment === true : canActOn(inc))
/** Yorumu silebilir mi — sunucu yorum başına `can_delete` (yazar ya da yönetim kapsamı); alan yoksa eski kural. */
export const canDeleteComment = (comment, inc) => (comment?.can_delete != null ? comment.can_delete === true : canActOn(inc))
/** Yeniden bildir — sunucu `can_notify` (kendi görüş kapsamı + alerts.actions; paylaşım kolu yok); alan yoksa yok. */
export const canNotifyOn = (inc) => isOpen(inc) && inc?.can_notify === true

/**
 * Yeni özet ucu (`/summary`) yanıtı → kart sayıları. Biçim tanınmazsa (eski sunucu: uç yok → boş dizi / 404) `null` —
 * çağıran eski yola (açık kümeden türetme) düşer. Okunamayan alan `null` kalır (ekranda "—").
 */
export function summaryFromServer(res) {
  const d = res?.success ? res.data : null
  if (!d || Array.isArray(d) || typeof d !== 'object' || typeof d.open !== 'number') return null
  const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : null)
  return {
    open: d.open,
    ack: num(d.acknowledged),
    resolved24: num(d.resolved_24h),
    critical: num(d.critical),
    unassigned: num(d.teamless),
    mine: num(d.mine),
    exact: true,
    sampled: null,
    mtta24: num(d.mtta_24h_minutes), mtta7: num(d.mtta_7d_minutes),
    mttr24: num(d.mttr_24h_minutes), mttr7: num(d.mttr_7d_minutes),
    mttaSample: num(d.mtta_sample_7d), mttrSample: num(d.mttr_sample_7d),
    timingCapped: d.timing_capped === true,
    timingKnown: typeof d.mttr_sample_7d === 'number',
    longestOpen: d.longest_open && typeof d.longest_open === 'object' ? d.longest_open : null,
    teamOptions: Array.isArray(d.team_options)
      ? d.team_options.filter((o) => o?.id != null && o.name).map((o) => ({ id: String(o.id), name: o.name, count: num(o.count) }))
      : null,
  }
}

/** Dakika (ondalıklı) → "1 sa 12 dk" gibi okunur süre; bilinmiyorsa null. */
export function minutesText(min, t) {
  if (min == null || !Number.isFinite(min)) return null
  const total = Math.max(0, Math.round(min))
  if (total < 1) return `<1 ${t('incov.unit.min')}`
  const d = Math.floor(total / 1440), h = Math.floor((total % 1440) / 60), m = total % 60
  const parts = []
  if (d) parts.push(`${d} ${t('incov.unit.day')}`)
  if (h) parts.push(`${h} ${t('incov.unit.hour')}`)
  if (m && !d) parts.push(`${m} ${t('incov.unit.min')}`)
  return parts.join(' ')
}

/**
 * ESKİ sunucu yedeği — özet: AÇIK küme (sunucu toplamı + en fazla 200 satır) ve son 24 saatte çözülen toplamı → kart
 * sayıları. Okunamayan 24 saat toplamı `null` (eskiden 0 — "hiç çözülmedi" yalanı); takımı olmayan oturumda "Takımım" null.
 */
export function summarize(openRows, openTotal, resolved24Total, teamId) {
  const rows = openRows || []
  const lvl = (r) => String(r.alert_level || '').toUpperCase()
  return {
    open: openTotal ?? rows.length,
    ack: rows.filter(isAcked).length,
    resolved24: resolved24Total ?? null,
    critical: rows.filter((r) => lvl(r) === 'CRITICAL').length,
    unassigned: rows.filter((r) => r.team_id == null).length,
    mine: teamId == null ? null : rows.filter((r) => String(r.team_id) === String(teamId)).length,
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

/**
 * Olay satırı → Alarm Geçmişi parçalarının beklediği alarm biçimi (AlertOnCall, AlertIncidentLink): olay = alarm (aynı
 * kimlik); `created_at` = başlangıç, `resolved` = durumdan.
 */
export function asAlert(inc) {
  if (!inc) return null
  return { ...inc, created_at: inc.started_at, resolved: !isOpen(inc) }
}

/** Kontrol kanıtı satır sayısı (detay penceresinde; tamamı Aktivite zaman çizelgesinde). */
export const EVIDENCE_LIMIT = 10
/** İzleme ailesi → Aktivite izleme türü (`monitor=TYPE:id`); sertifika / bilinmeyen tür kanıt sorgulamaz. */
export const ACTIVITY_TYPE = Object.freeze({
  http: 'HTTP', ping: 'PING', port: 'PORT', dns: 'DNS', domain: 'DOMAIN', keyword: 'KEYWORD',
  page: 'PAGE', pagespeed: 'PAGESPEED', scripted: 'SCRIPTED', dbquery: 'DBQUERY',
})
/**
 * Kanıt olarak istenen Aktivite durumları (uyarı veren kontroller dahil). UNKNOWN (2026-11-13 hata taraması): ölçülemeyen
 * kontrol (WHOIS yanıt vermedi, ICMP yok …) — DOMAINMON_UNKNOWN gibi olayların asıl kanıtı; rozeti nötr, yeşil değil.
 */
export const EVIDENCE_STATUSES = 'ERROR,TIMEOUT,WARNING,UNKNOWN'
/**
 * Kanıt yalnız KONTROL satırlarıdır (`group=check` — Aktivite ucunun olay grubu): alarm satırları (ALERT_TRIGGERED /
 * ALERT_ESCALATED) aynı izleme kimliği ve ERROR / WARNING durumuyla yazılır, süzgeçsiz sorgu alarmın açılışını "başarısız
 * kontrol" diye listeliyordu (2026-11-13 hata taraması).
 */
export const EVIDENCE_GROUP = 'check'
/** Kanıt penceresinin açılıştan önceki payı: teyit kontrolleri olaydan önce çalışır. */
export const EVIDENCE_LEAD_MS = 15 * 60 * 1000

/**
 * Olayın kontrol kanıtı sorgusu — `{ monitor: 'HTTP:42', from, to?, status, group: 'check' }` (UTC, zone'suz —
 * Aktivite ucu zone'suz değeri UTC okur). WARNING DAHİL (2026-11-13 inceleme): Aktivite kaydı HTTP hata kodunu, içerik
 * koşulunu, k6 FAIL'i, sorgu ihlalini ve yavaş yanıtı WARNING yazar — yalnız ERROR / TIMEOUT sormak HTTP 500'lü HTTP_DOWN
 * olayında "başarısız kontrol yok" diyordu. İzleme kimliği yoksa (sertifika olayı, silinmiş izleme) `null`.
 */
export function evidenceQuery(inc) {
  const m = inc?.monitor
  const type = ACTIVITY_TYPE[m?.type]
  if (!type || m?.monitor_id == null) return null
  const start = parseUtc(inc.started_at)
  if (!start) return null
  const from = new Date(start.getTime() - EVIDENCE_LEAD_MS).toISOString().slice(0, 19)
  const end = !isOpen(inc) && inc.resolved_at ? parseUtc(inc.resolved_at) : null
  const out = { monitor: `${type}:${m.monitor_id}`, from, status: EVIDENCE_STATUSES, group: EVIDENCE_GROUP }
  if (end) out.to = new Date(end.getTime() + 60 * 1000).toISOString().slice(0, 19)
  return out
}

/**
 * Kontrol kayıtları izleyiciden gizli mi — sunucu `check_records_visible:false` (izlemenin Aktivite kayıtları başka takımın
 * kapsamında yazılıyor; ör. envanter türevi Port / DNS'te UG üyesi). Alan yoksa (eski sunucu) bugünkü davranış: sorulur.
 */
export const checkRecordsHidden = (inc) => inc?.check_records_visible === false

/**
 * "Alarm Geçmişi'nde aç" çizilir mi — sunucu `can_open_alert` (Alarm Geçmişi tekil okuma kuralı: görüş + paylaşım kapsamı,
 * envanter SY / UG kolu, 7/24 / global). Alan yoksa (eski sunucu) bugünkü kural: kendi olayı ya da 7/24 konsolu.
 */
export function canOpenAlertHistory(inc, scope) {
  if (typeof inc?.can_open_alert === 'boolean') return inc.can_open_alert
  return !isForeign(inc) || scope?.noc_console === true
}

/**
 * İstek anında "Çözüldü (24 sa)" penceresi (2026-11-13 hata taraması): `params` süzgeç değişince bir kez kurulur; dakikalık
 * sessiz yenileme aynı nesneyle gittiği için pencere saatlerce açık sayfada 24 saati aşıyordu. Her istek (liste, dışa
 * aktarım) gönderilmeden önce sınır ŞİMDİYE göre yeniden hesaplanır; `params` — dolayısıyla istek anahtarı ve "yeni olay"
 * tespiti — değişmez. `resolvedSince` yoksa aynı nesne döner.
 */
export function withFreshWindow(params, nowMs) {
  if (!params?.resolvedSince) return params
  return { ...params, resolvedSince: iso24hAgo(nowMs) }
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
  if (filters.recurring) out.push({ key: 'recurring', value: filters.recurring, patch: { recurring: '' } })
  return out
}

/** Satırlardan takım seçenekleri (sunucu takım süzgeci sunmadığı için dizin sorgusu gerekmez). */
export function teamOptions(...rowLists) {
  const map = new Map()
  for (const rows of rowLists) for (const r of rows || []) if (r?.team_id != null && r.team_name) map.set(String(r.team_id), r.team_name)
  return [...map.entries()].map(([id, name]) => ({ id, name })).sort((a, b) => a.name.localeCompare(b.name))
}

/** Süzgeç durumu ↔ URL (sayfa-durumu anahtarları; uygulama düzeyi tab/domain/monitor/incident'a DOKUNULMAZ). */
export const URL_KEYS = { stat: 'stat', rootCause: 'type', q: 'q', since: 'from', until: 'to', level: 'level', team: 'team', recurring: 'inc_rec' }
export function filtersFromUrl(read) {
  const f = { ...FILTER_DEFAULTS }
  for (const [k, urlKey] of Object.entries(URL_KEYS)) {
    const v = read(urlKey)
    if (v) f[k] = v
  }
  if (f.stat && !TILE_KEYS.includes(f.stat) && f.stat !== 'resolved') f.stat = ''
  f.level = parseLevels(f.level).join(',')
  if (f.team && !/^\d{1,18}$/.test(f.team)) f.team = ''
  f.recurring = f.recurring === '1' ? '1' : ''
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
