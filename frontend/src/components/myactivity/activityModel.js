import { toUtc, localDayKey } from '../../utils/localDay.js'
import { agoText } from '../../utils/relativeTime.js'
import { eventLabel } from '../admin/audit/auditFormat.js'

/**
 * "Etkinliklerim" — SAF yardımcılar (React yok, DOM yok): aralık, olay türü → ikon türü, insan cümlesi,
 * konum/cihaz metni, gün gruplama, özet hesapları. Ekran bileşenleri yalnız çağırır; kurallar render
 * edilmeden test edilebilir.
 *
 * Veri kaynağı `/api/me/audit` (AuthController#myAudit): satırlar AuditLog varlığı (snake_case), zaman
 * damgaları UTC ve ekisiz ("2026-09-26T09:30:00"). Süzgeç sözleşmesi: `eventType`/`outcome` TAM eşleşme
 * (tek değer), `since`/`until` DİZE karşılaştırması → bu yüzden gönderilen tarih aynı biçimde UTC ISO olmalı.
 */

/** Hazır aralıklar (ms). `custom` = kullanıcının seçtiği Başlangıç/Bitiş. */
const RANGE_MS = { '24h': 24 * 3_600_000, '7d': 7 * 86_400_000, '30d': 30 * 86_400_000 }
export const RANGE_PRESETS = ['24h', '7d', '30d']
export const DEFAULT_RANGE = '30d'

/** Date/ms → proje UTC ISO (yyyy-MM-dd'T'HH:mm:ss, ek yok) — backend dize karşılaştırmasıyla uyumlu. */
export function toUtcIso(d) {
  return new Date(d).toISOString().slice(0, 19)
}

/** Hazır aralık → { preset, since, until }. Bilinmeyen ad → boş özel aralık. */
export function presetRange(preset, now = Date.now()) {
  const ms = RANGE_MS[preset]
  return ms ? { preset, since: toUtcIso(now - ms), until: '' } : { preset: 'custom', since: '', until: '' }
}

export const EV = {
  SIGN_IN: 'LOGIN',
  SIGN_IN_FAILED: 'LOGIN_FAILED',
  SIGN_OUT: 'LOGOUT',
  PASSWORD: 'SELF_PASSWORD_CHANGE',
}
export const OUTCOMES = ['SUCCESS', 'FAILURE', 'BLOCKED']

/** Hesap/oturum olayları — cümlede cihaz zaten söylenir, meta satırında tekrarlanmaz. */
const AUTH_TYPES = new Set([EV.SIGN_IN, EV.SIGN_IN_FAILED, EV.SIGN_OUT])

/**
 * Olay → ikon türü (zaman çizelgesi noktası). Sıralı: özel olan önce. Ton rozet/ikonda taşınır;
 * kartta sol renk şeridi YOK (kalıcı tasarım kararı).
 */
export function eventKind(row) {
  const type = String(row?.event_type || '').toUpperCase()
  if (type === EV.SIGN_IN) return 'signin'
  if (type === EV.SIGN_IN_FAILED) return row?.outcome === 'BLOCKED' ? 'blocked' : 'failed'
  if (type === EV.SIGN_OUT) return 'signout'
  if (type === EV.PASSWORD || type.includes('PASSWORD')) return 'password'
  if (type === 'ACCESS_DENIED' || type === 'AUTH_REQUIRED' || type.endsWith('_DENIED') || row?.outcome === 'BLOCKED') return 'denied'
  if (type.endsWith('_DELETE') || type.endsWith('_PURGE')) return 'delete'
  if (type.endsWith('_CREATE') || type.endsWith('_ADD')) return 'create'
  if (type.endsWith('_TEST') || type.endsWith('_TEST_EMAIL') || type.endsWith('_TRIGGER') || type.endsWith('_RUN')) return 'test'
  if (type.endsWith('_EXPORT')) return 'export'
  if (type.endsWith('_EDIT') || type.endsWith('_UPDATE') || type.endsWith('_SAVE') || type.endsWith('_SETTINGS')
      || type.endsWith('_OPT_OUT') || type.endsWith('_SCOPES') || type.endsWith('_TOGGLE')) return 'update'
  return 'other'
}

/** "Windows · Chrome" → "Chrome on Windows" (TR: "Windows üzerinde Chrome"); tek parça tanındıysa o; yoksa null. */
export function deviceText(uaSummary, t) {
  if (!uaSummary) return null
  const [os, browser] = String(uaSummary).split(' · ')
  if (os && browser) return t('myact.device', browser, os)
  return os || browser || null
}

/** Konum: özel ağ (GeoIpService "Private") → "Kurum ağı"; yoksa "Şehir, Ülke"; hiçbiri yoksa null. */
export function locationText(row, t) {
  if (!row) return null
  if (row.ip_country === 'Private') return t('dev.corporateNetwork')
  const parts = [row.ip_city, row.ip_country].filter((p) => p && p !== 'LAN')
  return parts.length ? parts.join(', ') : null
}

/**
 * Başarısızlık gerekçesi → okunur metin. Sunucu "<KOD>: <insan metni>" yazar (AuditService#recordLogin);
 * bilinen kodlar sözlükten, hız sınırı (BLOCKED giriş) kendi metniyle, gerisi olduğu gibi.
 * `t()` çağrıları LİTERAL (i18n-used-keys kapısı).
 */
export function reasonText(row, t) {
  const raw = row?.failure_reason
  if (row?.event_type === EV.SIGN_IN_FAILED && row?.outcome === 'BLOCKED') return t('myact.reason.rateLimited')
  if (!raw) return null
  const code = String(raw).split(':')[0].trim()
  if (code === 'BAD_PASSWORD') return t('lastLogin.reasonBadPassword')
  if (code === 'TEMP_PASSWORD_EXPIRED') return t('lastLogin.reasonTempExpired')
  return String(raw)
}

/** Virgüllü anomali bayrakları → dizi (yalnız bilinenler; bilinmeyen bayrak ham anahtar basmasın). */
const KNOWN_FLAGS = ['UNUSUAL_IP', 'GEO_VELOCITY', 'BRUTE_FORCE', 'RATE_LIMITED', 'OFF_HOURS']
export function parseFlags(csv) {
  if (!csv) return []
  return String(csv).split(',').map((s) => s.trim()).filter((f) => KNOWN_FLAGS.includes(f))
}
export function flagLabel(flag, t) {
  if (flag === 'UNUSUAL_IP') return t('dev.flag.UNUSUAL_IP')
  if (flag === 'GEO_VELOCITY') return t('dev.flag.GEO_VELOCITY')
  if (flag === 'BRUTE_FORCE') return t('dev.flag.BRUTE_FORCE')
  if (flag === 'RATE_LIMITED') return t('dev.flag.RATE_LIMITED')
  if (flag === 'OFF_HOURS') return t('dev.flag.OFF_HOURS')
  return flag
}

/** Yeni ağ / olanaksız yolculuk işaretli BAŞARILI giriş — "bu ben miydim" sorusunun asıl adayı. */
export function isUnusualSignIn(row) {
  if (row?.event_type !== EV.SIGN_IN) return false
  const f = parseFlags(row.anomaly_flags)
  return f.includes('UNUSUAL_IP') || f.includes('GEO_VELOCITY')
}

/** JSON mu düz metin mi — ayrıntıda "ad" olarak gösterilecek kısa düz metin. */
function plainText(s) {
  if (!s) return null
  const v = String(s).trim()
  if (!v || v.startsWith('{') || v.startsWith('[') || v.length > 120) return null
  return v
}

/**
 * Satırın "neye" kısmı: izleme adı (detail düz metin) ya da kaynak kimliği (alan adı, API yolu).
 * Kişinin kendisi (USER kaynağı) hedef olarak yazılmaz — "demo" kelimesi bilgi taşımaz.
 */
export function targetText(row) {
  if (!row || AUTH_TYPES.has(row.event_type)) return null
  const rt = String(row.resource_type || '')
  if (rt === 'USER') return null
  if (rt.endsWith('_MONITOR')) return plainText(row.detail) || row.resource_id || null
  return row.resource_id || plainText(row.detail) || null
}

/** İnsan cümlesi: { text, target }. Giriş/çıkış cümleleri cihazı içerir ("Chrome on Windows ile giriş"). */
export function sentence(row, t) {
  const type = String(row?.event_type || '').toUpperCase()
  const dev = deviceText(row?.ua_summary, t)
  if (type === EV.SIGN_IN) return { text: dev ? t('myact.s.signedInFrom', dev) : t('audit.ev.LOGIN'), target: null }
  if (type === EV.SIGN_IN_FAILED) {
    if (row?.outcome === 'BLOCKED') return { text: t('myact.s.blocked'), target: null }
    return { text: dev ? t('myact.s.failedFrom', dev) : t('myact.s.failed'), target: null }
  }
  if (type === EV.SIGN_OUT) return { text: t('audit.ev.LOGOUT'), target: null }
  if (type === EV.PASSWORD) return { text: t('myact.s.password'), target: null }
  return { text: eventLabel(type, t), target: targetText(row) }
}

/** Giriş/çıkış satırlarında cihaz cümlede; diğer satırlarda meta satırına yazılır. */
export function isAuthEvent(row) {
  return AUTH_TYPES.has(row?.event_type)
}

/**
 * Kayıt → ilgili ekran (ActivityLog#activityTarget ile aynı kural). Silinen kayda bağlantı yok.
 * Bilinmeyen tür → null (bağlantı çizilmez).
 */
const MONITOR_TABS = { HTTP: 'http', PORT: 'port', DNS: 'dns', KEYWORD: 'keyword', PING: 'ping', PAGE: 'page', PAGESPEED: 'pagespeed', SCRIPTED: 'scripted', DOMAIN: 'domain' }
export function recordLink(row) {
  const type = String(row?.event_type || '').toUpperCase()
  if (!row?.resource_id || type.endsWith('_DELETE') || type.endsWith('_PURGE')) return null
  const m = /^([A-Z]+)_MONITOR$/.exec(String(row.resource_type || ''))
  if (m && MONITOR_TABS[m[1]]) {
    return { tab: MONITOR_TABS[m[1]], params: m[1] === 'SCRIPTED' ? undefined : { monitor: row.resource_id } }
  }
  if (row.resource_type === 'CERTIFICATE') return { tab: 'dashboard', params: { domain: row.resource_id } }
  return null
}

/**
 * Yapısal fark: `changes` ({"alan":{"from","to"}}) — yoksa `detail` aynı biçimdeyse o (envanter düzenlemesi
 * farkı `detail`'e yazıyor, AdminController DOMAIN_EDIT). [[alan, eski, yeni]] ya da null.
 */
function tryJson(s) {
  if (!s) return null
  try { const v = JSON.parse(s); return v && typeof v === 'object' && !Array.isArray(v) ? v : null } catch { return null }
}
const isChange = (v) => v && typeof v === 'object' && !Array.isArray(v) && ('from' in v || 'to' in v)
export function diffOf(row) {
  const c = tryJson(row?.changes)
  if (c && Object.keys(c).length) return Object.entries(c).map(([k, v]) => [k, v?.from, v?.to])
  const d = tryJson(row?.detail)
  if (d && Object.keys(d).length && Object.values(d).every(isChange)) return Object.entries(d).map(([k, v]) => [k, v.from, v.to])
  return null
}
/** Fark değilse ayrıntı: { obj } (anahtar/değer) ya da { text } (düz metin) ya da null. */
export function detailOf(row) {
  const d = tryJson(row?.detail)
  if (d) return Object.values(d).every(isChange) ? null : { obj: d }
  const s = row?.detail && String(row.detail).trim()
  return s ? { text: s } : null
}
export function fmtValue(v) {
  if (v === null || v === undefined || v === '') return '—'
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v)
}

/** "3 sa önce" — ActivityLog `rel()` ile aynı kural ve anahtarlar (çekirdek ortak: utils/relativeTime.agoText). */
export function relTime(iso, t, now = Date.now()) {
  if (!iso) return null
  const then = new Date(toUtc(iso)).getTime()
  if (Number.isNaN(then)) return null
  return agoText(then, t, now)
}

/** Yerel saat "14:04" (gün başlığı tarihi zaten söyler). */
export function clockTime(iso, locale) {
  const d = new Date(toUtc(iso))
  if (Number.isNaN(d.getTime())) return iso || ''
  return d.toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' })
}

/** Satırları YEREL güne göre gruplar (sıra korunur — sunucu yeniden eskiye döner). */
export function groupByDay(rows) {
  const out = []
  let cur = null
  for (const row of rows || []) {
    const key = localDayKey(row.event_time) || '—'
    if (!cur || cur.key !== key) { cur = { key, rows: [] }; out.push(cur) }
    cur.rows.push(row)
  }
  return out
}

/** Gün başlığı: Bugün / Dün / "Çarşamba 24 Eylül" (başka yıl ise yıl da). */
export function dayLabel(key, t, locale, now = new Date()) {
  const pad = (n) => String(n).padStart(2, '0')
  const k = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
  const today = new Date(now)
  const yesterday = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 1)
  if (key === k(today)) return t('act.group.today')
  if (key === k(yesterday)) return t('act.group.yesterday')
  const [y, m, d] = String(key).split('-').map(Number)
  if (!y || !m || !d) return key
  const date = new Date(y, m - 1, d)
  return date.toLocaleDateString(locale, {
    weekday: 'long', day: 'numeric', month: 'long', ...(y !== today.getFullYear() ? { year: 'numeric' } : {}),
  })
}

/** Aksan/nokta katlama: "İstanbul" → "istanbul", "Türkiye" → "turkiye" — kullanıcı ne yazarsa yazsın eşleşsin
 *  (yerel-duyarlı küçük harf TÜRKÇE'de "IP" → "ıp" yapar; bu yüzden yerel-bağımsız küçük harf + NFD ayrıştırma). */
const fold = (s) => String(s).toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '')

/** Sayfa içi arama: IP, cihaz, konum, cümle, hedef, ayrıntı ve ham tür üzerinde "içerir". */
export function matchesQuery(row, q, t) {
  const needle = fold(String(q || '').trim())
  if (!needle) return true
  const { text, target } = sentence(row, t)
  const hay = fold([text, target, row.ua_summary, row.user_agent, row.ip_address, row.ip_reverse_host, row.ip_city,
    row.ip_country, row.ip_org, row.resource_type, row.resource_id, row.detail, row.failure_reason, row.event_type,
    locationText(row, t)].filter(Boolean).join(' '))
  return hay.includes(needle)
}

/**
 * Giriş örnekleminden cihaz dökümü: ua_summary başına giriş sayısı, son giriş, farklı IP'ler; toplam farklı IP.
 * Sıra: en son kullanılan önce.
 */
export function deviceStats(signInRows) {
  const map = new Map()
  const ips = new Set()
  for (const r of signInRows || []) {
    const key = r.ua_summary || ''
    if (r.ip_address) ips.add(r.ip_address)
    let d = map.get(key)
    if (!d) {
      d = { key, label: r.ua_summary || null, count: 0, last: r, ips: new Set(), mobile: /\b(iOS|Android)\b/.test(r.ua_summary || '') }
      map.set(key, d)
    }
    d.count++
    if (String(r.event_time) > String(d.last.event_time)) d.last = r
    if (r.ip_address) d.ips.add(r.ip_address)
  }
  const devices = [...map.values()].sort((a, b) => String(b.last.event_time).localeCompare(String(a.last.event_time)))
  return { devices, ipCount: ips.size }
}

/** Olay türü dağılımı (örneklemden): [[tür, adet]] çoktan aza. */
export function typeBreakdown(rows) {
  const m = new Map()
  for (const r of rows || []) m.set(r.event_type, (m.get(r.event_type) || 0) + 1)
  return [...m.entries()].sort((a, b) => b[1] - a[1] || String(a[0]).localeCompare(String(b[0])))
}

/**
 * Hafta günü × saat ısı haritası (YEREL saat, Pazartesi = 0): girişler matriste, başarısız denemeler işaret.
 * LoginHeatmap sözleşmesi: matrix/marks [7][24], rowTotals [7], colTotals [24], max, total.
 */
export function heatmapOf(signInRows, failedRows) {
  const blank = () => Array.from({ length: 7 }, () => Array(24).fill(0))
  const matrix = blank()
  const marks = blank()
  const cell = (iso) => {
    const d = new Date(toUtc(iso))
    if (Number.isNaN(d.getTime())) return null
    return [(d.getDay() + 6) % 7, d.getHours()]
  }
  for (const r of signInRows || []) { const c = cell(r.event_time); if (c) matrix[c[0]][c[1]]++ }
  for (const r of failedRows || []) { const c = cell(r.event_time); if (c) marks[c[0]][c[1]] = 1 }
  const rowTotals = matrix.map((row) => row.reduce((a, b) => a + b, 0))
  const colTotals = Array.from({ length: 24 }, (_, h) => matrix.reduce((a, row) => a + row[h], 0))
  const max = Math.max(0, ...matrix.flat())
  return { matrix, marks, rowTotals, colTotals, max, total: rowTotals.reduce((a, b) => a + b, 0) }
}
