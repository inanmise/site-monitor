import { MASK, fieldLabel, formatValue, isDurationField, isTeamField, parseChanges } from '../../history/changeFields.js'
import { localDayKey, toUtc } from '../../../utils/localDay.js'
import { toApiTime, startOfLocalDay, startOfLastNDays } from '../../../utils/apiTime.js'
import { readUrlParam } from '../../../hooks/useUrlQuerySync.js'
import { toCsv } from '../../../utils/csvExport.js'

/**
 * İzleme Değişiklikleri — SAF model (React yok): sabitler, URL durumu, gün gruplaması, değer türü sınıflaması,
 * duraklatma/sürdürme türetimi ve CSV. Bileşenler (`ChangeKpis`, `ChangeToolbar`, `ChangeTimeline`, `ChangeDiff`,
 * `ChangeDetailSheet`) yalnız çizer; kural burada tek yerde durur ve birim testlenir.
 */

/** Konsolun tanıdığı kaynak türleri — `change-kinds-sync` kapısı backend listesiyle karşılaştırır. */
export const KINDS = ['port', 'dns', 'keyword', 'http', 'page', 'pagespeed', 'scripted', 'domain',
  'ping', 'inventory', 'group', 'maintenance']

/**
 * Olay süzgecinin değerleri. PAUSE / RESUME ayrı olay tipi DEĞİL: `active` alanını çeviren güncelleme — sunucu
 * (`/changes/recent?eventType=PAUSE`) bunu `changes` desenine çevirir (MonitorChangeLogRepository.ACTIVE_PAUSED).
 */
export const EVENTS = ['CREATE', 'UPDATE', 'DELETE', 'PAUSE', 'RESUME', 'RESTORE', 'GROUP_RENAME']
export const TOGGLE_EVENTS = ['PAUSE', 'RESUME']

/** Zaman pencereleri (kullanıcı isteği 2026-08-22: 7/15/30/45/60/90 + özel). Saklama 730 gün. */
export const RANGE_KEYS = ['all', 'today', '7', '15', '30', '45', '60', '90', 'custom']

/**
 * İzleme türü → uygulama sekmesi (satırdan izlemenin kendi sayfasına gitmek için).
 *
 * KINDS'teki her İZLEME türü burada olmak ZORUNDA: eksik olan tür için satırdaki izleme adı bağlantı olmaz
 * (`pagespeed` bir kez tam olarak böyle eksik kalmıştı). İzleme OLMAYAN üç tür bilinçli dışarıda (kapı testindeki
 * muafiyet listesiyle birebir): `inventory` (kendi izleme sayfası yok), `group` ve `maintenance` (tekil kayıt yok).
 */
export const TAB_BY_KIND = {
  port: 'port', dns: 'dns', keyword: 'keyword', http: 'http', page: 'page',
  pagespeed: 'pagespeed', scripted: 'scripted', domain: 'domain', ping: 'ping',
}

/**
 * Sayfanın URL anahtarları — `ch_` öneki `useUrlQuerySync.PAGE_STATE_PREFIXES`'te: sekme değişince temizlenir.
 * Uygulamanın `tab / domain / monitor / incident` anahtarlarına DOKUNULMAZ (derin bağlantı sözleşmesi).
 */
export const URL_KEYS = {
  q: 'ch_q', kind: 'ch_kind', event: 'ch_ev', actor: 'ch_actor', team: 'ch_team', res: 'ch_res',
  range: 'ch_range', from: 'ch_from', to: 'ch_to', page: 'ch_page', size: 'ch_ps', open: 'ch_id',
}

export const kindKey = (kind) => String(kind || '').toLowerCase()
export const rowId = (r) => `${r.kind}-${r.resource_id}-${r.seq}`
/** Açık ayrıntının URL kimliği: `tür:id:sıra` (`ch_id`). */
export const detailKey = (r) => (r ? `${kindKey(r.kind)}:${r.resource_id}:${r.seq}` : null)
export const resourceName = (r) => r?.resource_name || `#${r?.resource_id}`

/** İzleme süzgeci değeri `tür:id` (kimlikler tür başına ayrı tablodan — kimlik tek başına yetmez). */
export const resKey = (kind, id) => `${kindKey(kind)}:${id}`
export function parseRes(v) {
  const m = /^([a-z]+):(\d+)$/.exec(String(v || ''))
  return m ? { kind: m[1], id: Number(m[2]) } : null
}

/**
 * Satırın izlemesine giden bağlantı — izleme türü değilse ya da izleme ŞU AN silinmişse (`isDeleted`) yok: silinmiş
 * izlemenin sayfası açılmaz, kullanıcıyı boş sayfaya götürmek yerine ad düz metin + "silinmiş" rozeti. Silinip geri
 * yüklenen izlemenin silme satırı da bağlantı taşır (izleme yaşıyor). `monitor` + `mtab=changes`: izlemenin detayı
 * Değişiklikler sekmesinde açılır.
 */
export function linkFor(r) {
  if (!r || r.resource_id == null || isDeleted(r)) return null
  const tab = TAB_BY_KIND[kindKey(r.kind)]
  if (!tab) return null
  return {
    tab,
    params: { monitor: r.resource_id, mtab: 'changes' },
    href: `?tab=${tab}&monitor=${r.resource_id}&mtab=changes`,
  }
}

/**
 * Satırın izlemesi ŞU AN yok mu. Hüküm SUNUCUNUN (`resource_deleted` — liste satırı ve tekil ayrıntı; `deleted` — özet
 * kartı öğesi): kaynağın EN SON geçmiş olayı silme mi (2026-09-28, regresyon B1). Sunucu alanı taşıyorsa ona uyulur —
 * silinip geri yüklenen izlemenin SİLME satırı da "silinmiş" değildir (zaman çizelgesi bağlantısı, CSV "Silinmiş"
 * sütunu). Alan yoksa (eski yanıt) yedek: olay silme ise silinmiş say — ölü bağlantı çizmektense bağlantısız kalsın.
 */
export function isDeleted(r) {
  if (!r) return false
  if (typeof r.resource_deleted === 'boolean') return r.resource_deleted
  if (typeof r.deleted === 'boolean') return r.deleted
  return r.event_type === 'DELETE'
}

// ── Duraklatma / sürdürme ──────────────────────────────────────────────────────────────────────────

/** Satırın `active` geçişi: 'PAUSE' (açık → kapalı), 'RESUME' (kapalı → açık) ya da null. */
export function activeToggle(changes) {
  const a = parseChanges(changes).find(d => d.key === 'active')
  if (!a) return null
  const on = (v) => v === true || v === 'true'
  const off = (v) => v === false || v === 'false'
  if (on(a.from) && off(a.to)) return 'PAUSE'
  if (off(a.from) && on(a.to)) return 'RESUME'
  return null
}

// ── Zaman ──────────────────────────────────────────────────────────────────────────────────────────

/** Sunucu UTC saklar ve Z'siz gönderir → epoch ms (bozuksa NaN). */
export function toMs(iso) {
  if (!iso) return NaN
  return new Date(toUtc(iso)).getTime()
}

const pad = (n) => String(n).padStart(2, '0')
/** Yerel 'YYYY-MM-DD'. */
export function localKeyOf(date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}
/** 'YYYY-MM-DD' → yerel gece yarısı Date. */
export function dateOfKey(key) {
  const [y, m, d] = String(key).split('-').map(Number)
  return new Date(y, (m || 1) - 1, d || 1)
}
/** Yerel saat "14:03". */
export function clockOf(iso) {
  const ms = toMs(iso)
  if (Number.isNaN(ms)) return ''
  const d = new Date(ms)
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/**
 * Satırları KULLANICININ yerel gününe göre gruplar (sunucu damgası UTC — `.slice(0,10)` İstanbul'da gece yarısı–03:00
 * arasını önceki güne yazardı, `localDayKey` bunu düzeltir). Sıra korunur (liste zaten en yeni üstte).
 */
export function groupByDay(rows) {
  const out = []
  const byKey = new Map()
  for (const r of rows || []) {
    const key = localDayKey(r.at) || '—'
    let g = byKey.get(key)
    if (!g) { g = { key, rows: [] }; byKey.set(key, g); out.push(g) }
    g.rows.push(r)
  }
  return out
}

export const intlLocale = (lang) => (lang === 'en' ? 'en-GB' : 'tr-TR')

/**
 * Gün başlığı: "Bugün" / "Dün" / "Cuma, 25 Eylül" (yıl farklıysa yıl da). `now` ms — test ve çizim aynı "şimdi"yi
 * kullansın diye dışarıdan verilir. Dönen `{ label, sub }`: `sub` bugün/dünde tam tarih (başlığın yanında soluk).
 */
export function dayLabel(key, now, lang, t) {
  const today = localKeyOf(new Date(now))
  const y = new Date(now); y.setDate(y.getDate() - 1)
  const yesterday = localKeyOf(y)
  const date = dateOfKey(key)
  if (Number.isNaN(date.getTime())) return { label: key, sub: '' }
  const sameYear = date.getFullYear() === new Date(now).getFullYear()
  const full = new Intl.DateTimeFormat(intlLocale(lang), {
    weekday: 'long', day: 'numeric', month: 'long', ...(sameYear ? {} : { year: 'numeric' }),
  }).format(date)
  if (key === today) return { label: t('chg.dayToday'), sub: full }
  if (key === yesterday) return { label: t('chg.dayYesterday'), sub: full }
  return { label: full, sub: '' }
}

/** Kısa gün "12 Eyl" (KPI'lar). */
export function shortDay(key, lang) {
  const d = dateOfKey(key)
  return new Intl.DateTimeFormat(intlLocale(lang), { day: 'numeric', month: 'short' }).format(d)
}

/** Zaman penceresi → API uçları (`from`, `to`: UTC, saniye hassasiyeti). Pencere YEREL takvimden kurulur. */
export function windowFor(rangeKey, now = new Date()) {
  if (rangeKey === 'all' || rangeKey === 'custom' || !RANGE_KEYS.includes(rangeKey)) return { from: '', to: '' }
  if (rangeKey === 'today') return { from: toApiTime(startOfLocalDay(now)), to: '' }
  return { from: toApiTime(startOfLastNDays(Number(rangeKey), now)), to: '' }
}

/**
 * Günlük eğri serisi: pencerenin (ya da sunucunun izin verdiği son 90 günün — `daily_since`) her yerel günü, boş
 * günler 0. `daily` sunucudan yerel güne toplanmış gelir (`tz`). En çok `maxDays` (≤ 90) nokta — son günler.
 */
export function dailySeries(summary, { from, to, now = Date.now(), maxDays = 90 } = {}) {
  const counts = new Map((summary?.daily || []).map(d => [d.day, Number(d.count) || 0]))
  const startCandidates = [summary?.daily_since, from ? localDayKey(from) : null].filter(Boolean)
  let start = startCandidates.sort().at(-1) || null
  const end = to ? localDayKey(to) : localKeyOf(new Date(now))
  if (!start) {
    const keys = [...counts.keys()].sort()
    start = keys[0] || end
  }
  const out = []
  const d = dateOfKey(start)
  const last = dateOfKey(end)
  for (let i = 0; i < 400 && d <= last; i++) {
    const key = localKeyOf(d)
    out.push({ day: key, count: counts.get(key) || 0 })
    d.setDate(d.getDate() + 1)
  }
  return out.slice(-Math.max(1, Math.min(90, maxDays)))
}

// ── URL durumu ────────────────────────────────────────────────────────────────────────────────────

const API_TIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/

/**
 * Açılışta URL'den süzgeç durumu (useState ilklendiricisinde — titreme yok). Geçersiz değer sessizce varsayılana
 * düşer (elle bozulmuş bağlantı ekranı kırmasın). Hazır pencere (`ch_range=7`) açılışta YENİDEN hesaplanır:
 * paylaşılan "son 7 gün" bağlantısı alındığı güne göre kayar, özel aralık (`ch_from/ch_to`) sabittir.
 */
export function readUrlState(now = new Date()) {
  const kind = readUrlParam(URL_KEYS.kind, '')
  const event = readUrlParam(URL_KEYS.event, '')
  const team = readUrlParam(URL_KEYS.team, '')
  const res = readUrlParam(URL_KEYS.res, '')
  let range = readUrlParam(URL_KEYS.range, 'all')
  if (!RANGE_KEYS.includes(range)) range = 'all'
  let from = ''
  let to = ''
  if (range === 'custom') {
    const f = readUrlParam(URL_KEYS.from, '')
    const tt = readUrlParam(URL_KEYS.to, '')
    if (API_TIME.test(f)) from = f
    if (API_TIME.test(tt)) to = tt
    if (!from) range = 'all'
  } else {
    ({ from, to } = windowFor(range, now))
  }
  return {
    q: String(readUrlParam(URL_KEYS.q, '')).slice(0, 200),
    kind: KINDS.includes(kind) ? kind : '',
    eventType: EVENTS.includes(event) ? event : '',
    actor: String(readUrlParam(URL_KEYS.actor, '')).slice(0, 100),
    teamId: /^\d+$/.test(team) ? team : '',
    res: parseRes(res) ? res : '',
    rangeKey: range,
    from,
    to: range === 'custom' ? to : '',
    openId: /^[a-z]+:\d+:\d+$/.test(String(readUrlParam(URL_KEYS.open, ''))) ? readUrlParam(URL_KEYS.open, '') : '',
  }
}

/** Süzgeç durumu → URL eşlemesi (varsayılan değer = null → anahtar silinir, URL temiz kalır). */
export function urlMapping(s) {
  return {
    [URL_KEYS.q]: s.q || null,
    [URL_KEYS.kind]: s.kind || null,
    [URL_KEYS.event]: s.eventType || null,
    [URL_KEYS.actor]: s.actor || null,
    [URL_KEYS.team]: s.teamId || null,
    [URL_KEYS.res]: s.res || null,
    [URL_KEYS.range]: s.rangeKey && s.rangeKey !== 'all' ? s.rangeKey : null,
    [URL_KEYS.from]: s.rangeKey === 'custom' ? (s.from || null) : null,
    [URL_KEYS.to]: s.rangeKey === 'custom' ? (s.to || null) : null,
    [URL_KEYS.open]: s.openId || null,
  }
}

// ── Değer türleri (alan farkı çizimi) ─────────────────────────────────────────────────────────────

/** Bu uzunluğun üstündeki tek satırlık metin katlanır ("Tümünü göster"). */
export const LONG_TEXT = 160

/**
 * Bir diff değerinin sunum türü. Sunucu değerleri `AuditDiff` JSON'u: boolean/sayı çıplak, koleksiyon dizi, harita
 * nesne, ikili içerik `{bytes:N}`, hassas alan `***`, uzun metin 200 karakter + `…(+N)` kırpılmış.
 */
export function valueKind(key, v) {
  if (v === null || v === undefined || v === '') return 'empty'
  if (v === MASK) return 'masked'
  if (typeof v === 'boolean' || v === 'true' || v === 'false') return 'boolean'
  if (isTeamField(key) && /^\d+$/.test(String(v))) return 'team'   // teamId + ugTeamId → takım rozeti
  if (isDurationField(key) && Number.isFinite(Number(v)) && Number(v) > 0) return 'duration'
  if (Array.isArray(v)) return 'list'
  if (typeof v === 'object') {
    const keys = Object.keys(v)
    return keys.length === 1 && keys[0] === 'bytes' && typeof v.bytes === 'number' ? 'binary' : 'object'
  }
  if (typeof v === 'number') return 'number'
  const s = String(v)
  if (/\r|\n/.test(s)) return 'multiline'
  if (s.length > LONG_TEXT) return 'long'
  return 'text'
}

/**
 * Satırın alan farkı → çizim modeli: `{ key, label, from, to, fromKind, toKind, mode }`. `mode`:
 * 'lines' (iki taraftan biri çok satırlı → satır farkı), 'list' (iki taraf da liste → eklenen/çıkan öğeler),
 * 'pair' (önce → sonra kutuları).
 */
export function diffModel(changes, t) {
  return parseChanges(changes).map(d => {
    const fromKind = valueKind(d.key, d.from)
    const toKind = valueKind(d.key, d.to)
    const lines = fromKind === 'multiline' || toKind === 'multiline'
    const list = (fromKind === 'list' || fromKind === 'empty') && (toKind === 'list' || toKind === 'empty')
      && (fromKind === 'list' || toKind === 'list')
    return {
      key: d.key, label: fieldLabel(t, d.key), from: d.from, to: d.to, fromKind, toKind,
      mode: lines ? 'lines' : list ? 'list' : 'pair',
    }
  })
}

/** İki listenin öğe farkı (metin karşılaştırması): yalnız öncede olanlar / yalnız sonrada olanlar. */
export function listDelta(from, to) {
  const a = (Array.isArray(from) ? from : []).map(String)
  const b = (Array.isArray(to) ? to : []).map(String)
  const sa = new Set(a)
  const sb = new Set(b)
  return { removed: a.filter(x => !sb.has(x)), added: b.filter(x => !sa.has(x)), kept: b.filter(x => sa.has(x)) }
}

/** Bir değerin TAM metni (kırpmasız) — CSV, ham kopya, uzun metin kutusu. */
export function fullText(key, v, t) {
  const kind = valueKind(key, v)
  if (kind === 'list') return v.map(String).join(', ')
  if (kind === 'object') return JSON.stringify(v, null, 2)
  if (kind === 'binary') return t ? t('chg.valueBytes', v.bytes) : `${v.bytes} B`
  if (kind === 'multiline' || kind === 'long' || kind === 'text') return String(v)
  return formatValue(key, v, { t })
}

// ── CSV ───────────────────────────────────────────────────────────────────────────────────────────

/**
 * Süzülmüş listenin CSV'si — satır düzeyinde TAM detay (alan farkı kırpılmadan, not, IP, tarayıcı). Kaçış ve formül
 * nötrlemesi projenin ortak yardımcısından (utils/csv.js — CWE-1236); BOM'lu UTF-8.
 *
 * @param fmtTime  yerel zaman biçimleyici (api/client formatDateSec) — testte taklit edilebilsin diye dışarıdan
 */
export function changesCsv(rows, t, fmtTime) {
  const headers = [
    t('chg.colTime'), t('chg.csvTimeUtc'), t('chg.colAction'), t('chg.csvToggle'), t('chg.filterKind'),
    t('chg.colMonitor'), t('chg.csvMonitorId'), t('chg.csvDeleted'), t('chg.filterTeam'), t('chg.colUser'),
    t('chg.csvUsername'), t('chg.colChanges'), t('chg.noteTitle'), t('chg.colIp'), t('chg.detailBrowser'),
    t('chg.detailRecord'),
  ]
  const body = (rows || []).map(r => {
    const toggle = activeToggle(r.changes)
    const diff = parseChanges(r.changes)
      .map(d => `${fieldLabel(t, d.key)}: ${fullText(d.key, d.from, t)} → ${fullText(d.key, d.to, t)}`)
      .join('; ')
    return [
      fmtTime(r.at), r.at ? toUtc(r.at) : '', eventLabel(t, r.event_type), toggle ? eventLabel(t, toggle) : '',
      t('chg.kind.' + kindKey(r.kind)), resourceName(r), r.resource_id ?? '', isDeleted(r) ? t('chg.csvYes') : '',
      r.team_name || '', !r.actor || r.actor === 'system' ? t('audit.systemActor') : (r.actor_name || r.actor),
      // gizlenen iz (2026-09-28c) boş hücre değil "Gizli" — CSV'de de "kayıt yok" ile karışmasın
      r.actor || '', diff, r.note || '', r.identity_masked === true ? t('uact.idMasked') : (r.ip_address || ''),
      r.identity_masked === true ? t('uact.idMasked') : (r.user_agent || ''), r.seq ?? '',
    ]
  })
  return toCsv(headers, body)
}

/** "12 değişiklik" / "1 change" — İngilizcede tekil ayrı anahtar (sözlük çoğul kuralı taşımaz). `shown`: biçimli sayı. */
export const countChanges = (t, n, shown = n) => t(Number(n) === 1 ? 'chg.nChangesOne' : 'chg.nChanges', shown)

/** Olay adı (i18n `chg.event<TİP>`); sözlükte yoksa ham tip. */
export function eventLabel(t, ev) {
  const key = `chg.event${ev}`
  const label = t(key)
  return label === key ? String(ev ?? '') : label
}
