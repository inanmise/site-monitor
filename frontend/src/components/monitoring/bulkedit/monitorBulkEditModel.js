/**
 * İzleme sayfası GENİŞLETİLMİŞ toplu düzenleme (2026-11-19, kullanıcı kararı: kontrol aralığı, alarm seviyesi, bildirim
 * kanalları + grup, etiketler; sunucuda tek istek, önizleme + satır satır sonuç) — SAF model (React'siz, test edilebilir).
 *
 * Sunucu sözleşmesi `POST /api/monitoring/bulk-edit` (MonitorBulkEditController): `{type, action, ids, …}` → `{type, action,
 * done, skipped, failed, results:[{id, name, team_id, team_name, status: done|skipped|failed, reason, message?}]}`. Kurallar
 * Java `MonitorBulkEdit` ile aynı (TYPES / ACTIONS / aralık sınırları / etiket birleştirme — test Java kaynağını okur).
 *
 * Önizleme İSTEMCİDE liste satırlarından hesaplanır (yönetimdeki toplu kişi deseni): değişecek / atlanacak (neden) / sunucu
 * denetleyecek (satırda değer yoksa — iddia yok). Sunucu her satırı yeniden okur ve kendi kararını verir.
 */

/** Çubuğun `nocType` değeri → toplu düzenleme türü (sunucunun `type` anahtarı). */
export const TYPE_OF_NOC = Object.freeze({
  HTTP: 'http', KEYWORD: 'keyword', PAGE: 'page', PAGESPEED: 'pagespeed', PING: 'ping', PORT: 'port', DNS: 'dns',
  DOMAIN: 'domain', SCRIPTED: 'scripted', DBQUERY: 'dbquery',
})
export const TYPES = Object.freeze(['http', 'keyword', 'page', 'pagespeed', 'ping', 'port', 'dns', 'domain', 'scripted', 'dbquery'])
export const ACTIONS = Object.freeze(['interval', 'alert_level', 'channels', 'tags'])
export const LEVELS = Object.freeze(['WARNING', 'HIGH', 'CRITICAL'])
export const MAX_ITEMS = 200
export const MAX_TAGS = 20
export const TAG_MAX_CHARS = 50

/**
 * Tür başına aralık seçenekleri (sn) — TÜRÜN KENDİ FORMUNDAKİ seçeneklerin AYNISI (bağımsız inceleme 2026-11-19: birleşim listesi
 * formda olmayan 6 sa / 10 dk değerleri yazdırıyordu; form kaydırıcısı saklı değere en yakın seçeneği gösterdiği için kayıtlı
 * aralığı yanlış söylerdi). Sayfa formlarının `INTERVALS` dizileri ve zamanlama düzenleyicisinin `INTERVAL_PRESETS`'i
 * (sentetik / sorgu ≥ 60 sn, Sayfa Hızı ≥ 300 sn) kaynaktır — test sayfa kaynaklarını okuyup karşılaştırır.
 */
const WEB = Object.freeze([30, 60, 300, 600, 900, 1800, 3600, 43200, 86400])
const NET = Object.freeze([30, 60, 300, 900, 1800, 3600, 43200, 86400])
const SCHEDULED = Object.freeze([60, 300, 600, 900, 1800, 3600, 7200, 21600, 43200, 86400])
export const INTERVALS_BY_TYPE = Object.freeze({
  http: WEB, keyword: WEB, port: WEB, ping: NET, dns: NET,
  page: Object.freeze([60, 300, 600, 900, 1800, 3600, 43200, 86400]),
  domain: Object.freeze([3600, 21600, 43200, 86400]),
  scripted: SCHEDULED, dbquery: SCHEDULED,
  pagespeed: Object.freeze(SCHEDULED.filter((s) => s >= 300)),
})

export function minInterval(type) {
  if (type === 'page' || type === 'scripted' || type === 'dbquery') return 60
  if (type === 'pagespeed') return 300
  if (type === 'domain') return 3600
  return 30
}
export const maxInterval = (type) => (type === 'dbquery' ? 7 * 86400 : 86400)

export const intervalOptions = (type) => INTERVALS_BY_TYPE[type] ?? []

/** Saklı etiket metni → liste (virgül, kırpma, büyük/küçük harf duyarsız tekrar bir kez) — Java `tagsOf`. */
export function tagsOf(raw) {
  const out = []
  const seen = new Set()
  for (const p of String(raw ?? '').split(',')) {
    const t = p.trim()
    if (t && !seen.has(t.toLocaleLowerCase('en'))) { seen.add(t.toLocaleLowerCase('en')); out.push(t) }
  }
  return out
}

const key = (s) => String(s).toLocaleLowerCase('en')

/** Java `mergeTags`: mevcut sıra korunur, yeni sona; büyük/küçük harf duyarsız. */
export function mergeTags(current, add, remove) {
  const drop = new Set(remove.map(key))
  const seen = new Set()
  const out = []
  for (const t of [...current, ...add]) {
    const k = key(t)
    if (!drop.has(k) && !seen.has(k)) { seen.add(k); out.push(t) }
  }
  return out
}

export function sameTags(a, b) {
  const x = new Set(a.map(key)), y = new Set(b.map(key))
  return x.size === y.size && [...x].every((k) => y.has(k))
}

/** Etiket alanı hata kodu (`mbe.err.<kod>`) ya da null — sunucunun kuralı. */
export function tagProblem(list) {
  if (list.length > MAX_TAGS) return 'tagsTooMany'
  if (list.some((t) => t.includes(',') || t.length > TAG_MAX_CHARS)) return 'tagInvalid'
  return null
}

const hasNum = (v) => v != null && v !== '' && Number.isFinite(Number(v))
const effLevel = (v) => (LEVELS.includes(String(v || '').toUpperCase()) ? String(v).toUpperCase() : 'WARNING')
const on = (v) => v !== false   // null / true → açık (sunucu varsayılanı)
const isCron = (m) => String(m?.schedule_mode || '').toUpperCase() === 'CRON'
const routingLocked = (m) => m?.routing_locked === true || m?.shared_access === true

/**
 * İstemci önizlemesi. `form`: `{action, interval, level, email: keep|on|off, webhook: keep|on|off, groupMode: keep|default|set,
 * groupId, groupTeamId, tagsAdd[], tagsRemove[]}`. Satır durumu: `change` (from → to), `skip` + `reason`, `check` (satır değeri
 * bilinmiyor — sunucu karar verir).
 */
export function planRows(rows, form) {
  const out = []
  for (const m of rows) {
    const base = { id: m.id, name: m.name || m.url || m.host || m.domain || `#${m.id}`, teamId: m.team_id ?? null }
    const r = planRow(m, form)
    out.push({ ...base, ...r })
  }
  return {
    rows: out,
    change: out.filter((r) => r.state === 'change').length,
    skip: out.filter((r) => r.state === 'skip').length,
    check: out.filter((r) => r.state === 'check').length,
  }
}

function planRow(m, form) {
  if (form.action !== 'interval' && form.action !== 'tags' && routingLocked(m)) return { state: 'skip', reason: 'routing_locked' }
  switch (form.action) {
    case 'interval': {
      if (isCron(m)) return { state: 'skip', reason: 'schedule_cron' }
      if (!hasNum(m.interval_seconds)) return { state: 'check' }
      if (Number(m.interval_seconds) === Number(form.interval)) return { state: 'skip', reason: 'unchanged' }
      return { state: 'change', from: Number(m.interval_seconds), to: Number(form.interval) }
    }
    case 'alert_level': {
      const cur = effLevel(m.alert_level)
      if (cur === form.level) return { state: 'skip', reason: 'unchanged' }
      return { state: 'change', from: cur, to: form.level }
    }
    case 'channels': {
      if (form.groupMode === 'set' && (m.team_id == null || String(m.team_id) !== String(form.groupTeamId))) {
        return { state: 'skip', reason: 'group_other_team' }
      }
      const parts = []
      if (form.email !== 'keep' && on(m.notify_email) !== (form.email === 'on')) parts.push('email')
      if (form.webhook !== 'keep' && on(m.notify_webhook) !== (form.webhook === 'on')) parts.push('webhook')
      const curGroup = m.notification_group_id ?? null
      if (form.groupMode === 'default' && curGroup != null) parts.push('group')
      if (form.groupMode === 'set' && String(curGroup) !== String(form.groupId)) parts.push('group')
      if (parts.length === 0) return { state: 'skip', reason: 'unchanged' }
      return { state: 'change', parts }
    }
    case 'tags': {
      const cur = tagsOf(m.tags)
      const next = mergeTags(cur, form.tagsAdd, form.tagsRemove)
      if (sameTags(cur, next)) return { state: 'skip', reason: 'unchanged' }
      if (next.length === 0) return { state: 'skip', reason: 'last_tag' }
      return { state: 'change', from: cur, to: next }
    }
    default:
      return { state: 'check' }
  }
}

/** Formun eksik / hatalı alanı (`mbe.err.<kod>`) ya da null — onay düğmesi ve alan altı hata. */
export function formProblem(type, form) {
  switch (form.action) {
    case 'interval':
      return intervalOptions(type).includes(Number(form.interval)) ? null : 'interval'
    case 'alert_level':
      return LEVELS.includes(form.level) ? null : 'level'
    case 'channels':
      if (form.groupMode === 'set' && !form.groupId) return 'group'
      return form.email === 'keep' && form.webhook === 'keep' && form.groupMode === 'keep' ? 'channelsNone' : null
    case 'tags': {
      if (form.tagsAdd.length === 0 && form.tagsRemove.length === 0) return 'tagsNone'
      const p = tagProblem(form.tagsAdd) || tagProblem(form.tagsRemove)
      if (p) return p
      const rm = new Set(form.tagsRemove.map(key))
      return form.tagsAdd.some((t) => rm.has(key(t))) ? 'tagsBoth' : null
    }
    default:
      return 'action'
  }
}

/** İstek gövdesi (sunucu sözleşmesi). */
export function buildBody(type, ids, form) {
  const body = { type, action: form.action, ids: ids.slice(0, MAX_ITEMS) }
  if (form.action === 'interval') body.interval_seconds = Number(form.interval)
  if (form.action === 'alert_level') body.alert_level = form.level
  if (form.action === 'channels') {
    if (form.email !== 'keep') body.notify_email = form.email === 'on'
    if (form.webhook !== 'keep') body.notify_webhook = form.webhook === 'on'
    body.group_mode = form.groupMode
    if (form.groupMode === 'set') body.notification_group_id = Number(form.groupId)
  }
  if (form.action === 'tags') {
    body.tags_add = form.tagsAdd
    body.tags_remove = form.tagsRemove
  }
  return body
}

/** Seçili satırların ortak takımı (hepsi aynı takımdaysa kimlik, değilse null) — "belirli grup" yalnız tek takımda. */
export function commonTeam(rows) {
  let team
  for (const m of rows) {
    const t = m.team_id == null ? null : String(m.team_id)
    if (team === undefined) team = t
    else if (team !== t) return null
  }
  return team ?? null
}

/** Eski sunucu (uç yok): 405 ya da kendi kodu olmayan / genel `NOT_FOUND` kodlu 404. */
export function isUnsupportedAnswer(res) {
  const info = res?.errorInfo || {}
  const st = Number(info.status ?? res?.status)
  const code = String(info.code ?? res?.code ?? '')
  if (st === 405) return true
  if (st === 404) return code === '' || code === 'NOT_FOUND'
  return false
}

export const EMPTY_FORM = Object.freeze({
  action: 'interval', interval: '', level: '', email: 'keep', webhook: 'keep', groupMode: 'keep', groupId: '',
  groupTeamId: null, tagsAdd: [], tagsRemove: [],
})
