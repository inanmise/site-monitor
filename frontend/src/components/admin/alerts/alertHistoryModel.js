import { alertTypeLabel } from '../../../utils/alertTypeMeta.js'
import { parseUtc } from '../../../utils/incidentMeta.js'
import { MONITOR_ALERT_TYPES, alertTypesFor } from '../../../utils/monitorAlertTypes.js'

/**
 * Alarm Geçmişi'nin SAF modeli — bileşenlerden bağımsız yardımcılar (React'siz, test edilebilir).
 * Sunucu sözleşmesi (AdminController.listAlerts): `resolved | page | size | since | until | resolvedSince |
 * resolvedUntil | domain | alertType | alertTypes | q | level | acknowledged | teamId | range | sort | dir`.
 *
 * URL SÖZLEŞMESİ (anahtarlar uygulamanın PAGE_STATE_PARAMS listesinde; sekme değişince temizlenir):
 *   `view`  open (vars.) | closed | all — alt görünüm (`tab` DEĞİL, o uygulamanın sekmesi).
 *   `type`  alarm tipi · `src` izleme türü kategorisi · `level` CRITICAL|HIGH|WARNING · `ack` ack|unack · `team` takım id · `q` arama.
 *   `from`  dönem başlangıcı: HIZLI DÖNEM belirteci (`1h` `24h` `7d` `30d` — istek anında göreli hesaplanır, paylaşılan
 *           bağlantı "son 7 gün"ü hep BUGÜNE göre açar) ya da gün (yyyy-MM-dd) ya da tam UTC damga; `to` yalnız gün/damga.
 *   `range` tarih aralığının hangi alana uygulandığı: '' görünümün varsayılanı (açık/tümü → AÇILIŞ, kapalı → KAPANIŞ);
 *           `active` (yalnız Tümü) aralıkta AKTİF olanlar; `resolved` (Tümü) kapanış anı; `opened` (Kapalı) açılış anı.
 *   `sort`  `<anahtar>` (azalan) ya da `<anahtar>_asc`; anahtar opened|resolved|level|team|domain|type. '' = görünümün
 *           varsayılanı (açık/tümü: açılış en yeni önce; kapalı: kapanış en yeni önce) — yön tek parametrede taşınır
 *           (ayrı `dir` anahtarı yok; sunucuya `sort` + `dir` olarak açılır).
 *   `alert` açılacak kayıt (tüketilir) · `page` / `ps` sayfalama (useServerPagination).
 */

export const LEVELS = ['CRITICAL', 'HIGH', 'WARNING']

/** Seviye → ton anahtarı (rozet / kart `data-level`). */
export const levelClass = (lvl) => ({ WARNING: 'warning', HIGH: 'high', CRITICAL: 'critical' })[lvl] ?? 'unknown'

/**
 * Tarih aralığı kipi (URL `range`, 2026-09-28 — regresyon B3): varsayılan '' = görünümün varsayılan alanı; `'active'` = aralıkta
 * AKTİF olanlar (açılış ≤ bitiş VE (açık YA DA çözüm ≥ başlangıç) — daha önce açılıp aralığa DEVREDENLER dahil; yalnız
 * "Tümü"; haftalık erişilebilirlik e-postasının "Haftanın alarmları" bağlantısı bu kipi açar). 2026-10-01: `'resolved'` (Tümü'nde
 * kapanış anına göre) ve `'opened'` (Kapalı'da açılış anına göre) — "belirli tarihlerde açılan / kapanan alarmlar".
 */
export const RANGE_ACTIVE = 'active'
export const RANGE_RESOLVED = 'resolved'
export const RANGE_OPENED = 'opened'
const RANGE_VALUES = [RANGE_ACTIVE, RANGE_RESOLVED, RANGE_OPENED]

/** Hızlı dönem belirteçleri (URL `from`): saatlikler şimdiye göre damga, günlükler yerel gün sınırından (Europe/Istanbul). */
export const PRESETS = ['1h', '24h', '7d', '30d']
export const isPreset = (v) => PRESETS.includes(String(v || ''))
/** Bir üst dönem ("Dönemi genişlet"): 1h→24h→7d→30d→'' (tüm zamanlar); belirteç değilse null. */
export function widerPreset(p) {
  const i = PRESETS.indexOf(String(p || ''))
  return i < 0 ? null : (PRESETS[i + 1] ?? '')
}

/** Sıralama anahtarları (sunucu beyaz listesiyle aynı); `resolved` yalnız kapalı/tümü görünümünde anlamlı. */
export const SORT_KEYS = ['opened', 'resolved', 'level', 'team', 'domain', 'type']
const SORT_RX = /^(opened|resolved|level|team|domain|type)(_asc)?$/
export const isSortValue = (v) => SORT_RX.test(String(v || ''))
/** Görünümün varsayılan sıralama anahtarı — sunucu varsayılanıyla aynı (kapalı: kapanış anı). */
export const defaultSortKey = (tab) => (tab === 'closed' ? 'resolved' : 'opened')
/** URL/süzgeç değeri → { key, dir }; boş/geçersiz/görünüme uymayan değer görünümün varsayılanı (en yeni önce). */
export function sortParts(sort, tab) {
  const m = SORT_RX.exec(String(sort || ''))
  if (!m || (m[1] === 'resolved' && tab === 'open')) return { key: defaultSortKey(tab), dir: 'desc' }
  return { key: m[1], dir: m[2] ? 'asc' : 'desc' }
}
/** { key, dir } → süzgeç değeri; görünümün varsayılanı '' (URL'e yazılmaz). */
export function sortValue(key, dir, tab) {
  if (!SORT_KEYS.includes(key)) return ''
  if (key === defaultSortKey(tab) && dir !== 'asc') return ''
  return dir === 'asc' ? `${key}_asc` : key
}
/** Başlığa tıklama: aynı sütun → yön değişir; başka sütun → tarihler en yeni önce (desc), metinler A→Z (asc). */
export function toggleSort(sort, key, tab) {
  const cur = sortParts(sort, tab)
  if (cur.key === key) return sortValue(key, cur.dir === 'desc' ? 'asc' : 'desc', tab)
  return sortValue(key, key === 'opened' || key === 'resolved' || key === 'level' ? 'desc' : 'asc', tab)
}

/** Süzgeç varsayılanları — URL'e yalnız varsayılan-dışı değer yazılır (anahtarlar uygulamanın PAGE_STATE_PARAMS listesinde). */
export const FILTER_DEFAULTS = Object.freeze({ type: '', q: '', level: '', team: '', ack: '', from: '', to: '', range: '', src: '', sort: '' })
export const URL_KEYS = { type: 'type', q: 'q', level: 'level', team: 'team', ack: 'ack', from: 'from', to: 'to', range: 'range', src: 'src', sort: 'sort' }

/** Kategori süzgeci (URL `src`, 2026-09-30 — İzleme menüsü rozetleri): izleme türü → o türün TÜM alarm tipleri. */
export const SRC_KEYS = Object.keys(MONITOR_ALERT_TYPES)
export const isSrcKey = (v) => SRC_KEYS.includes(String(v || ''))

export function filtersFromUrl(read) {
  const f = { ...FILTER_DEFAULTS }
  for (const [k, urlKey] of Object.entries(URL_KEYS)) {
    const v = read(urlKey, '')
    if (v) f[k] = String(v)
  }
  if (f.level && !LEVELS.includes(f.level)) f.level = ''
  if (f.ack && f.ack !== 'ack' && f.ack !== 'unack') f.ack = ''
  if (f.range && !RANGE_VALUES.includes(f.range)) f.range = ''   // `range` başka sayfalarda da kullanılıyor (7 / custom) — yalnız kipler
  if (f.src && !isSrcKey(f.src)) f.src = ''
  if (f.sort && !isSortValue(f.sort)) f.sort = ''               // `sort` başka sayfaların da anahtarı — yalnız beyaz liste
  if (isPreset(f.from)) f.to = ''                                // hızlı dönem "şimdiye kadar"dır; bitiş taşımaz
  return f
}

/**
 * Aralığın uygulandığı tarih alanı: `'opened'` (açılış — since/until) · `'resolved'` (kapanış — resolvedSince/Until) ·
 * `'active'` (Tümü: aralıkta aktif). Açık görünümde her zaman açılış (kapanış yok); kapalı görünümde varsayılan kapanış
 * (eski davranış: "Son 24 saatte çözülen" kartı), `range=opened` ile açılış; Tümü'nde varsayılan açılış.
 */
export function dateKind(filters, tab) {
  if (tab === 'open') return 'opened'
  if (tab === 'closed') return filters.range === RANGE_OPENED ? 'opened' : 'resolved'
  if (filters.range === RANGE_ACTIVE) return 'active'
  if (filters.range === RANGE_RESOLVED) return 'resolved'
  return 'opened'
}
/** Görünümde seçilebilir tarih alanları (araç çubuğu seçicisi): açıkta seçici yok. */
export function dateKindOptions(tab) {
  if (tab === 'closed') return ['resolved', 'opened']
  if (tab === 'all') return ['opened', 'resolved', 'active']
  return []
}
/** Tarih alanı seçimi → `range` yaması (görünümün varsayılanı '' yazılır). */
export function rangeForKind(kind, tab) {
  if (tab === 'closed') return kind === 'opened' ? RANGE_OPENED : ''
  if (tab === 'all') return kind === 'active' ? RANGE_ACTIVE : kind === 'resolved' ? RANGE_RESOLVED : ''
  return ''
}
/** Görünümün varsayılanı dışında bir kip seçili mi → çip değeri ('active' | 'resolved' | 'opened') ya da null. */
export function rangeChip(filters, tab) {
  if (tab === 'closed') return filters.range === RANGE_OPENED ? RANGE_OPENED : null
  if (tab === 'all') return filters.range === RANGE_ACTIVE || filters.range === RANGE_RESOLVED ? filters.range : null
  return null
}

/** "Aralıkta aktif" kipi bu görünümde GEÇERLİ mi? (yalnız "Tümü") */
export const activeRangeOn = (filters, tab) => dateKind(filters, tab) === 'active'

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
 * Etkin süzgeç çipleri — [{ key, value, patch }] (etiket çağıranın t'siyle kurulur). Tarih aralığı HER görünümde
 * uygulanır (2026-10-01: açık görünümde "son 1 saatte açılanlar"); hızlı dönem TEK çip (`preset`), gün/damga aralığı
 * `from` / `to` çipleri. Görünümün varsayılanı dışındaki tarih kipi (`range`) çip olur; × varsayılana döner.
 */
export function activeAlertFilters(filters, tab) {
  const out = []
  if (filters.src) out.push({ key: 'src', value: filters.src, patch: { src: '' } })
  if (filters.type) out.push({ key: 'type', value: filters.type, patch: { type: '' } })
  if (filters.level) out.push({ key: 'level', value: filters.level, patch: { level: '' } })
  if (filters.ack) out.push({ key: 'ack', value: filters.ack, patch: { ack: '' } })
  if (filters.team) out.push({ key: 'team', value: filters.team, patch: { team: '' } })
  if (filters.q) out.push({ key: 'q', value: filters.q, patch: { q: '' } })
  const mode = rangeChip(filters, tab)
  if (mode) out.push({ key: 'range', value: mode, patch: { range: '' } })
  if (isPreset(filters.from)) out.push({ key: 'preset', value: filters.from, patch: { from: '', to: '' } })
  else {
    if (filters.from) out.push({ key: 'from', value: filters.from, patch: { from: '' } })
    if (filters.to) out.push({ key: 'to', value: filters.to, patch: { to: '' } })
  }
  return out
}

/**
 * Süzgecin tarih aralığı → sunucu damgaları { since, until } (zone'suz UTC, 19 karakter). Hızlı dönem belirteci: saatlikler
 * `now - N saat`, günlükler N gün önceki YEREL günün başından (gün seçiciyle aynı sınır) — bitiş yok ("şimdiye kadar").
 * Gün (yyyy-MM-dd) gün sınırlarına açılır: sunucu SÖZLÜKSEL karşılaştırır; çıplak "2026-09-27" bitiş günü o günün tamamını
 * dışarıda bırakırdı. Tam damga olduğu gibi geçer.
 */
export function resolveRange(filters, nowMs = Date.now()) {
  const from = filters.from || ''
  if (isPreset(from)) {
    if (from === '1h') return { since: new Date(nowMs - 3_600_000).toISOString().slice(0, 19), until: '' }
    if (from === '24h') return { since: iso24hAgo(nowMs), until: '' }
    return { since: dayStart(quickRange(from === '7d' ? 7 : 30, new Date(nowMs)).from), until: '' }
  }
  return { since: from ? dayStart(from) : '', until: filters.to ? dayEnd(filters.to) : '' }
}

/**
 * Liste isteği parametreleri — sunucunun tanıdığı boyutlar. Tarih aralığı {@link dateKind} alanına gider: açılış →
 * since/until (HER görünümde; açık görünümde "son 1 saatte açılanlar"), kapanış → resolvedSince/Until, "Tümü"nde
 * `range=active` kipinde sunucu since'ı aktiflik alt sınırı sayar (aralıkta AKTİF olanlar, devredenler dahil).
 * Sıralama görünümün varsayılanıysa (sunucununkiyle aynı) gönderilmez; aksi hâlde `sort` + `dir`.
 */
export function listParams({ tab, filters, page, pageSize, domain, typesParam, nowMs = Date.now() }) {
  const params = { page, size: pageSize }
  if (tab !== 'all') params.resolved = tab === 'closed' ? 'true' : 'false'
  const kind = dateKind(filters, tab)
  const { since, until } = resolveRange(filters, nowMs)
  if (kind === 'resolved') {
    if (since) params.resolvedSince = since
    if (until) params.resolvedUntil = until
  } else {
    if (since) params.since = since
    if (until) params.until = until
    if (kind === 'active') params.range = RANGE_ACTIVE
  }
  const { key, dir } = sortParts(filters.sort, tab)
  if (!(key === defaultSortKey(tab) && dir === 'desc')) { params.sort = key; params.dir = dir }
  if (domain) params.domain = domain
  if (typesParam) params.alertTypes = typesParam
  // Kategori süzgeci (2026-09-30): gömülü `types` verilmediyse izleme türünün alarm tipleri sunucuya gider.
  else if (filters.src && isSrcKey(filters.src)) params.alertTypes = alertTypesFor(filters.src).join(',')
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
 * Sayfadaki alarmları GÜNE göre bölümler (Bugün / Dün / tarih) — sunucu sırası korunur, günler bitişik.
 * Anahtar damga SIRALAMA alanıdır (`sortKey`, vars. görünümünki: kapalıda kapanış, diğerlerinde açılış); seviye / takım /
 * alan adı / tür sıralamasında gün bölümü anlamsız → tek başlıksız bölüm (`kind: 'none'`). `now` yerel gün hesabı için.
 */
export function groupByDay(alerts, tab, now = new Date(), sortKey = defaultSortKey(tab)) {
  if (sortKey !== 'opened' && sortKey !== 'resolved') {
    return alerts.length ? [{ key: 'all', kind: 'none', date: null, items: [...alerts] }] : []
  }
  const keyOf = (a) => {
    const d = parseUtc(sortKey === 'resolved' ? (a.resolved_at || a.created_at) : a.created_at)
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
  'SKIPPED_STORM', 'SKIPPED_NO_TEAM',   // 2026-09-30: açılışta hiçbir kanal koşmadan verilen kararlar
  'SKIPPED_TEAM_QUIET', 'SKIPPED_USER_QUIET_HOURS',   // 2026-10-01: takım / kişisel sessiz saat
  'SKIPPED_SYSTEM_MAINTENANCE',   // 2026-10-02: sistem bakımı — bildirimler bakım boyunca susturuldu
  // 2026-10-04: saat tavanı (ham görünüyordu) + kişisel tercihler + eskalasyon adımı kişi eşlemesi
  'RATE_LIMITED', 'SKIPPED_USER_LEVEL', 'SKIPPED_USER_TYPE', 'SKIPPED_USER_SNOOZE', 'SKIPPED_USER_INACTIVE',
  'SKIPPED_NO_USER_MATCH', 'SKIPPED_AMBIGUOUS_USER',
])

/** Bildirim günlüğü tetiği "bildirim fırtınaya devredildi" kararı mı (e-posta değil, karar satırı — backend STORM). */
export const STORM_SUPPRESSED_TRIGGER = 'STORM'
/** Bildirim günlüğü tetiği "bildirim sessiz saat özetine devredildi" (karar satırı — backend QUIET_HOURS, 2026-10-01). */
export const QUIET_HOURS_TRIGGER = 'QUIET_HOURS'
/** Sessiz saat karar satırı ÇÖZÜMÜN özete katlanması mı ("SKIPPED: sessiz saat (çözüm özete eklendi)")? */
export function isQuietResolutionFold(status) {
  return /çözüm|cozum|resolution/i.test(String(status || ''))
}
/** "SKIPPED: fırtına #17 — …" → 17; eşleşmezse null. */
export function stormIdFromStatus(status) {
  const m = /f[ıi]rt[ıi]na\s*#(\d+)/i.exec(String(status || ''))
  return m ? Number(m[1]) : null
}
/**
 * Fırtına devri satırı YALNIZ e-postayı mı devretti (2026-10-03, `site.monitor.storm.push-individual` — varsayılan)?
 * Backend o kipte durumun sonuna "(push tek tek)" yazar (EscalationService.STATUS_STORM_MAIL_ONLY_SUFFIX); push bu alarm için
 * bireysel gitti ve kendi satırında görünür. İşaretsiz (eski / ayar kapalı) satır: e-posta + push fırtınaya devredildi.
 */
export function isStormMailOnly(status) {
  return /push tek tek|push individually/i.test(String(status || ''))
}
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

/**
 * Kendiliğinden KAPANMAYAN alarm türleri (değişiklik alarmları — backend: DNS_CHANGED / DOMAINMON_CHANGED otomatik
 * kapanış hattına girmez). Geri kalan her tür kaynağı art arda sağlıklı kontrol verince kapanır; "Neden hâlâ açık?"
 * paneli bu ayrımı gösterir (2026-09-29: sağlıklı izlemede asılı kalan alarm, onay/bildirim çiplerinden okunamıyordu).
 */
export const MANUAL_CLOSE_TYPES = new Set(['DNS_CHANGED', 'DOMAINMON_CHANGED'])
export const closesAutomatically = (type) => !!type && !MANUAL_CLOSE_TYPES.has(String(type))

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

/**
 * Alarm Geçmişi derin bağlantısının parametreleri — TEK kaynak (bildirim kutusu, paylaşılan bağlantı ve Kontrol Geçmişi'nin
 * kesinti çizelgesi / alarm olay kartı; `navigateTo('alerthistory', alertNavParams(a))`). Sözleşme AlertHistory'nin okuduğu
 * anahtarlar: `alert` (açılacak kayıt), `type` + `q` (süzgeç — kayıt ilk sayfada bulunsun), `view=closed` (çözülmüş kayıt
 * yalnız Kapalı görünümde listelenir). Ek 2026-09-28e/E2: Kontrol Geçmişi `{ incident: id }` gönderiyordu — `incident`
 * UYGULAMANIN anahtarı (Olaylar), Alarm Geçmişi onu okumaz → hedef alarm hiç açılmıyordu.
 */
export function alertNavParams(a) {
  const p = { alert: String(a.id) }
  if (a.alert_type) p.type = a.alert_type
  if (a.domain) p.q = a.domain
  if (a.resolved) p.view = 'closed'
  return p
}

/** Paylaşılabilir derin bağlantı — bildirim kutusuyla AYNI biçim ({@link alertNavParams}). */
export function alertLink(a) {
  let origin = ''
  try { origin = window.location.origin + window.location.pathname } catch { /* jsdom */ }
  const q = new URLSearchParams({ tab: 'alerthistory', ...alertNavParams(a) })
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
    if (n.trigger === STORM_SUPPRESSED_TRIGGER) {
      // 2026-09-30: e-posta değil KARAR — "bireysel bildirim fırtınaya devredildi" (neden ekranda okunsun).
      items.push({ id: `n-${n.id}`, kind: 'storm', at: ts(n.sent_at) ?? openedAt + 1, when: n.sent_at,
        stormId: stormIdFromStatus(n.email_status) ?? a.storm_id ?? null, status: n.email_status || null,
        backfilled: /geriye d[öo]n[üu]k|backfill/i.test(String(n.email_status || '')),
        mailOnly: isStormMailOnly(n.email_status) })
      continue
    }
    if (n.trigger === QUIET_HOURS_TRIGGER) {
      // 2026-10-01: e-posta değil KARAR — "bildirim takımın sessiz saat özetine devredildi" (ya da çözümü özete katlandı).
      items.push({ id: `n-${n.id}`, kind: 'quiet', at: ts(n.sent_at) ?? openedAt + 1, when: n.sent_at,
        recipient: n.recipient_name || '', status: n.email_status || null, resolution: isQuietResolutionFold(n.email_status) })
      continue
    }
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

/**
 * Sahiplen / Çöz / Tekrar bildir neden kapalı? (2026-09-28) — `null` = açık; aksi halde neden anahtarı:
 * `'perm'` rolün `alerts.actions` izni yok (sunucunun `can_act`'ı; ör. AUDIT denetçi), `'permNoc'` aynı durumda 7/24
 * operatörü (AUDIT + `noc_calls.write`: yalnız arama kaydı girer), `'team'` operatör başka takımın uyarısında
 * ({@link outsideActScope}). `canAct` yanıtta yoksa (eski sunucu / gömülü kullanım) `true` sayılır — davranış değişmez.
 */
export function actBlockReason(a, { canAct = true, nocCanWrite = false, globalViewer = false, myTeamIds = null } = {}) {
  if (!a) return null
  if (canAct === false) return nocCanWrite ? 'permNoc' : 'perm'
  return outsideActScope(a, { nocCanWrite, globalViewer, myTeamIds }) ? 'team' : null
}
