import { alertTypeLabel } from '../../../utils/alertTypeMeta.js'
import { parseUtc } from '../../../utils/incidentMeta.js'

/**
 * Alarm Geçmişi'nin SAF modeli — bileşenlerden bağımsız yardımcılar (React'siz, test edilebilir).
 * Sunucu sözleşmesi (AdminController.listAlerts): `resolved | page | size | since | until | resolvedSince |
 * resolvedUntil | domain | alertType | alertTypes | q | level | acknowledged | teamId`; sıralama sunucuda sabittir
 * (en yeni önce) — burada uydurma bir sıralama boyutu YOK.
 */

export const LEVELS = ['CRITICAL', 'HIGH', 'WARNING']

/** Seviye → ton anahtarı (rozet / kart `data-level`). */
export const levelClass = (lvl) => ({ WARNING: 'warning', HIGH: 'high', CRITICAL: 'critical' })[lvl] ?? 'unknown'

/** Süzgeç varsayılanları — URL'e yalnız varsayılan-dışı değer yazılır (anahtarlar uygulamanın PAGE_STATE_PARAMS listesinde). */
export const FILTER_DEFAULTS = Object.freeze({ type: '', q: '', level: '', team: '', ack: '', from: '', to: '' })
export const URL_KEYS = { type: 'type', q: 'q', level: 'level', team: 'team', ack: 'ack', from: 'from', to: 'to' }

export function filtersFromUrl(read) {
  const f = { ...FILTER_DEFAULTS }
  for (const [k, urlKey] of Object.entries(URL_KEYS)) {
    const v = read(urlKey, '')
    if (v) f[k] = String(v)
  }
  if (f.level && !LEVELS.includes(f.level)) f.level = ''
  if (f.ack && f.ack !== 'ack' && f.ack !== 'unack') f.ack = ''
  return f
}

/** Alt görünüm (URL `view`): açık (varsayılan) · kapalı · tümü. */
export const TABS = ['open', 'closed', 'all']
export const tabFromUrl = (v) => (v === 'closed' || v === 'all' ? v : 'open')

export function filtersToUrl(filters, tab) {
  const out = {}
  for (const [k, urlKey] of Object.entries(URL_KEYS)) out[urlKey] = filters[k] || null
  out.view = tab === 'open' ? null : tab   // `tab` DEĞİL — o anahtar uygulamanın sekmesi (ISSUE-002)
  return out
}

/**
 * Etkin süzgeç çipleri — [{ key, value, patch }] (etiket çağıranın t'siyle kurulur). Tarih aralığı AÇIK görünümde
 * uygulanmaz (açık alarm "şimdi"dir; açık kalma süresi kartta yazar) → çipi de çıkmaz: gizli süzgeç kalmaz.
 */
export function activeAlertFilters(filters, tab) {
  const out = []
  if (filters.type) out.push({ key: 'type', value: filters.type, patch: { type: '' } })
  if (filters.level) out.push({ key: 'level', value: filters.level, patch: { level: '' } })
  if (filters.ack) out.push({ key: 'ack', value: filters.ack, patch: { ack: '' } })
  if (filters.team) out.push({ key: 'team', value: filters.team, patch: { team: '' } })
  if (filters.q) out.push({ key: 'q', value: filters.q, patch: { q: '' } })
  if (tab !== 'open' && filters.from) out.push({ key: 'from', value: filters.from, patch: { from: '' } })
  if (tab !== 'open' && filters.to) out.push({ key: 'to', value: filters.to, patch: { to: '' } })
  return out
}

/**
 * Liste isteği parametreleri — sunucunun tanıdığı boyutlar. Tarih aralığı kapalı görünümde KAPANIŞ
 * (resolvedSince/Until), "tümü"nde AÇILIŞ (since/until) anına uygulanır; açık görünümde hiç gitmez.
 * Tarih-gün süzgeci (yyyy-MM-dd) gün sınırlarına açılır: sunucu damgaları 19 karakterlik ISO ile
 * SÖZLÜKSEL karşılaştırır; çıplak "2026-09-27" bitiş günü o günün tamamını dışarıda bırakırdı.
 */
export function listParams({ tab, filters, page, pageSize, domain, typesParam }) {
  const params = { page, size: pageSize }
  if (tab !== 'all') params.resolved = tab === 'closed' ? 'true' : 'false'
  if (tab === 'closed') {
    if (filters.from) params.resolvedSince = dayStart(filters.from)
    if (filters.to) params.resolvedUntil = dayEnd(filters.to)
  } else if (tab === 'all') {
    if (filters.from) params.since = dayStart(filters.from)
    if (filters.to) params.until = dayEnd(filters.to)
  }
  if (domain) params.domain = domain
  if (typesParam) params.alertTypes = typesParam
  if (filters.type) params.alertType = filters.type
  if (filters.q.trim()) params.q = filters.q.trim()
  if (filters.level) params.level = filters.level
  if (filters.team) params.teamId = filters.team
  if (filters.ack) params.acknowledged = filters.ack === 'ack' ? 'true' : 'false'
  return params
}

/** CSV aynı süzgeçlerle, sayfalama hariç (dosya tüm sonucu içerir). */
export function csvParams(args) {
  const p = listParams({ ...args, page: 0, pageSize: 1 })
  delete p.page; delete p.size
  return p
}

/**
 * Gün süzgeci (yyyy-MM-dd, YEREL takvim günü) → sunucunun UTC damga biçimi (yyyy-MM-ddTHH:mm:ss). Yerel gün sınırı
 * UTC'ye çevrilir (Europe/Istanbul'da "27 Eylül" = 26 Eylül 21:00Z – 27 Eylül 20:59:59Z); tam damga olduğu gibi geçer.
 */
const localDayUtc = (v, h, m, s) => {
  const [y, mo, d] = v.split('-').map(Number)
  const dt = new Date(y, mo - 1, d, h, m, s)
  return isNaN(dt.getTime()) ? v : dt.toISOString().slice(0, 19)
}
export const dayStart = (v) => (v && v.length === 10 ? localDayUtc(v, 0, 0, 0) : v)
export const dayEnd = (v) => (v && v.length === 10 ? localDayUtc(v, 23, 59, 59) : v)

/** Şu andan 24 saat önce, sunucunun beklediği zone'suz UTC biçiminde. */
export function iso24hAgo(nowMs = Date.now()) {
  return new Date(nowMs - 24 * 3_600_000).toISOString().slice(0, 19)
}

/** Süzgeç tarihinin okunur hâli: gün (yyyy-MM-dd) yerel tarih, tam damga (UTC) yerel tarih-saat. */
export function fmtFilterDate(v, locale) {
  if (!v) return ''
  try {
    if (v.length === 10) {
      const [y, m, d] = v.split('-').map(Number)
      return new Date(y, m - 1, d).toLocaleDateString(locale, { day: '2-digit', month: '2-digit', year: 'numeric' })
    }
    const d = parseUtc(v)
    return d ? d.toLocaleString(locale, { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) : v
  } catch { return v }
}

/**
 * Sayfadaki alarmları GÜNE göre bölümler (Bugün / Dün / tarih) — sunucu sırası (en yeni önce) korunur, günler bitişik.
 * Anahtar damga: kapalı görünümde kapanış, diğerlerinde açılış anı. `now` yerel gün hesabı için.
 */
export function groupByDay(alerts, tab, now = new Date()) {
  const keyOf = (a) => {
    const d = parseUtc(tab === 'closed' ? (a.resolved_at || a.created_at) : a.created_at)
    if (!d) return { key: 'unknown', date: null }
    const k = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    return { key: k, date: d }
  }
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const yesterday = new Date(today.getTime() - 86_400_000)
  const dayKey = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  const groups = []
  for (const a of alerts) {
    const { key, date } = keyOf(a)
    let g = groups.find((x) => x.key === key)
    if (!g) {
      const kind = key === dayKey(today) ? 'today' : key === dayKey(yesterday) ? 'yesterday' : (date ? 'date' : 'unknown')
      g = { key, kind, date, items: [] }
      groups.push(g)
    }
    g.items.push(a)
  }
  return groups
}

/** "Son N gün" — yerel takvim günü olarak from/to (gün seçiciyle aynı biçim). */
export function quickRange(days, now = new Date()) {
  const pad = (n) => String(n).padStart(2, '0')
  const day = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
  const from = new Date(now.getTime() - days * 86_400_000)
  return { from: day(from), to: day(now) }
}

/**
 * Kapalı alarmdaki "Son Geçerlilik": SUNUCUNUN damgaladığı gerçek not_after (alarm anı). Yaklaşık hesap
 * (created_at + days_remaining) yalnız son çare — eskiden hep bu hesaplanıyor ve tarih açık kaldığı gün kadar
 * erken çıkıyordu (2026-09-10).
 */
export function alertExpiryIso(a) {
  if (a?.not_after) return a.not_after
  if (!a?.created_at || a.days_remaining == null) return null
  const created = new Date(a.created_at.endsWith('Z') ? a.created_at : a.created_at + 'Z')
  if (isNaN(created)) return null
  return new Date(created.getTime() + a.days_remaining * 86_400_000).toISOString()
}

/** Kademe kontakları (`notified_contacts` JSON dizesi) → dizi; bozuksa boş. */
export function parseContacts(json) {
  if (!json) return []
  try { const v = JSON.parse(json); return Array.isArray(v) ? v : [] } catch { return [] }
}

/**
 * Aynı mesajı aynı tetikte alan alıcıları TEK satırda toplar (anahtar: tetik + durum + gönderim partisi +
 * mesaj); en yeniden eskiye. `dedupe_key` = gönderim partisi kimliği — RESEND her tıkta yeni parti.
 */
export function groupPushRows(rows) {
  const by = new Map()
  for (const p of rows ?? []) {
    const key = `${p.trigger}|${p.status}|${p.dedupe_key ?? ''}|${p.message ?? ''}`
    if (!by.has(key)) by.set(key, [])
    by.get(key).push(p)
  }
  const stamp = (g) => g.reduce((mx, r) => { const v = r.sent_at || r.created_at || ''; return v > mx ? v : mx }, '')
  return [...by.values()].sort((a, b) => stamp(b).localeCompare(stamp(a)))
}

/** Bilinen push teslimat kodları — bilinmeyen kod HAM hâliyle gösterilir (t() ham anahtar basmasın). */
export const PUSH_STATUS_KEYS = new Set([
  'SENT', 'FAILED', 'PENDING', 'CIRCUIT_OPEN',
  'SKIPPED_DISABLED', 'SKIPPED_MONITOR_OFF', 'SKIPPED_NO_CONTACT', 'SKIPPED_NO_ID',
  'SKIPPED_NO_PRIOR', 'SKIPPED_NO_RECIPIENT', 'SKIPPED_NO_RECIPIENTS', 'SKIPPED_QUIET_HOURS',
  'SKIPPED_REALERT_OFF', 'SKIPPED_TEAM_OFF', 'SKIPPED_TYPE_OFF', 'SKIPPED_USER_OPT_OUT',
])
export function statusLabel(t, status) {
  return PUSH_STATUS_KEYS.has(status) ? t('alh.push.status.' + status) : (status || '—')
}

/** Zone'suz damga = UTC (UserPushService.ISO) → yerel okunur tarih-saat. */
export function fmtStamp(iso, locale) {
  if (!iso) return '—'
  try {
    const s = iso.endsWith('Z') || iso.includes('+') ? iso : iso + 'Z'
    return new Date(s).toLocaleString(locale, { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })
  } catch { return iso }
}

/** Alarm tipi → izleme sekmesi; sertifika tipleri (EXPIRY, CHAIN_BROKEN…) null döner. */
const TYPE_TAB = [
  [/^HTTP_/, 'http'], [/^DNS_/, 'dns'], [/^PORT_/, 'port'], [/^PING_/, 'ping'], [/^KEYWORD/, 'keyword'],
  [/^PAGESPEED_/, 'pagespeed'], [/^PAGE_/, 'page'], [/^SCRIPTED_/, 'scripted'], [/^DOMAINMON_/, 'domain'],
]
export function alertSourceTab(type) {
  const s = String(type || '')
  for (const [rx, tab] of TYPE_TAB) if (rx.test(s)) return tab
  return null
}

/**
 * Kaynağa git: izleme alarmı ilgili sekme + arama (`q` — izleme sayfaları bu anahtarı okur; alarm satırında
 * izleme kimliği YOK), sertifika alarmı Tüm Sertifikalar + alan adı süzgeci.
 */
export function alertHref(a) {
  const dom = a?.domain || ''
  const tab = alertSourceTab(a?.alert_type)
  if (tab) return `?tab=${tab}${dom ? `&q=${encodeURIComponent(dom)}` : ''}`
  return `?tab=all${dom ? `&domain=${encodeURIComponent(dom)}` : ''}`
}

/** Paylaşılabilir derin bağlantı — bildirim kutusuyla AYNI biçim (alert + tip + arama, kapalıysa view=closed). */
export function alertLink(a) {
  let origin = ''
  try { origin = window.location.origin + window.location.pathname } catch { /* jsdom */ }
  const q = new URLSearchParams({ tab: 'alerthistory', alert: String(a.id) })
  if (a.alert_type) q.set('type', a.alert_type)
  if (a.domain) q.set('q', a.domain)
  if (a.resolved) q.set('view', 'closed')
  return `${origin}?${q.toString()}`
}

/** Satırı ayırt eden ad (a11y.rowAction / KebabMenu rowLabel): alan adı + tür. */
export function alertRowName(a, t) {
  return `${a?.domain || '—'} · ${alertTypeLabel(t, a?.alert_type)}`
}

const ts = (s) => { const d = parseUtc(s); return d ? d.getTime() : null }

/**
 * Zaman çizelgesi: açılış → e-posta bildirimleri → push gönderimleri (parti başına tek olay) → onay → çözüm.
 * Zamanı olmayan olay (eski onaylar) açılışın hemen ardına düşer ve "zaman kaydı yok" der.
 */
export function buildAlertTimeline({ alert: a, notifications = [], pushGroups = [] }) {
  if (!a) return []
  const openedAt = ts(a.created_at) ?? 0
  const items = [{ id: 'opened', kind: 'opened', at: openedAt, when: a.created_at, level: a.alert_level, message: a.message }]
  for (const n of notifications) {
    items.push({ id: `n-${n.id}`, kind: 'mail', at: ts(n.sent_at) ?? openedAt + 1, when: n.sent_at,
      recipient: n.recipient_name || n.recipient_email || '', trigger: n.trigger || '', status: n.email_status || null })
  }
  for (const g of pushGroups) {
    const head = g[0]
    if (!head) continue
    const people = new Set(g.filter((r) => r.username !== '-').map((r) => r.username)).size
    items.push({ id: `p-${head.id}`, kind: 'push', at: ts(head.sent_at || head.created_at) ?? openedAt + 1, when: head.sent_at || head.created_at,
      trigger: head.trigger || '', status: head.status || null, people })
  }
  if (a.acknowledged || a.acknowledged_at) {
    items.push({ id: 'ack', kind: 'acknowledged', at: ts(a.acknowledged_at) ?? openedAt + 2, when: a.acknowledged_at || null, by: a.acknowledged_by || null, note: a.acknowledged_note || null })
  }
  if (a.resolved) {
    items.push({ id: 'resolved', kind: 'resolved', at: ts(a.resolved_at) ?? Number.MAX_SAFE_INTEGER, when: a.resolved_at || null, by: a.resolved_by || null, note: a.resolved_note || null })
  }
  return items.map((it, i) => [it, i]).sort((x, y) => x[0].at - y[0].at || x[1] - y[1]).map(([it]) => it)
}

/**
 * Bu uyarıda Sahiplen / Çöz / Tekrar bildir sunucuda REDDEDİLİR mi? (2026-09-27, 7/24 arama kaydı)
 *
 * 7/24 operatörü (`noc_calls.write`, sunucunun `noc_can_write`'ı) TÜM takımların uyarılarını GÖRÜR, ama yazma eylemlerinin
 * kapsamı değişmedi: sunucu `requireAlertScope` = global görüntüleyici (global yönetici / AUDIT) ya da uyarının
 * takımlarından biri (`team_id`, envanterin SY `sy_team_id` ve UG `ug_team_id`'si) kullanıcının takımlarında. Dışındaki
 * uyarıda düğmeler 403'e gider → gizlenir, yerine "yalnız arama kaydı" notu. Kapı YALNIZ operatörde ve takım listesi
 * biliniyorsa çalışır: operatör olmayan zaten yalnız kendi kapsamını görür (kapsamlı müdürün görüş listesi istemcide
 * yok — onu yanlışlıkla kısıtlamasın), gömülü kullanımda (takımlar verilmez) davranış değişmez.
 */
export function outsideActScope(a, { nocCanWrite = false, globalViewer = false, myTeamIds = null } = {}) {
  if (!a || !nocCanWrite || globalViewer || !Array.isArray(myTeamIds)) return false
  const mine = new Set(myTeamIds.map(String))
  return ![a.team_id, a.sy_team_id, a.ug_team_id].some((x) => x != null && mine.has(String(x)))
}
