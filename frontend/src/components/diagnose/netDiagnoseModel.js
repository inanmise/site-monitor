/**
 * Ping / Port / DNS UÇTAN UCA TANILAMA modeli — saf fonksiyonlar, React yok (2026-10-05, kullanıcı isteği: "tanılama ve
 * teşhisi eksik olan … izlemeler için tanılama ekleyelim; hata alındığında detaylıca ne hatası aldığını görelim").
 *
 * <p>Backend `POST /api/monitoring/{ping|port|dns}/{id}/diagnose` adım adım bir sonuç döner (sözleşme: `steps[]` sunucu
 * sırasında, `findings[]` en önemli ilk, `verdict` = en önemli bulgu; Port'ta vekil tanımlıysa `paths[]` = izlemenin yolu +
 * öteki yol; DNS'te `dns.resolvers[]` / `dns.authoritative[]`). Burada ham sonuç okunur parçalara çevrilir: bulgu metni
 * (ADLI yer tutucular, `params.reason` varyantları, yol değerleri yerelleşir), adım ayrıntısının satırları, DNS satır
 * durumları, izleme istemcisinin özeti ve paylaşılabilir rapor (Markdown / düz metin).
 *
 * <p>HTTP tanılamasının modeli (`httpDiagnoseModel.js`) YENİDEN KULLANILIR — `interpolate`, `stepState`, `verdictTone`,
 * `failureKind`, `historyItems`, `downloadJson`, rapor yazıcısı (`writer`), döküm satırı sınıflayıcısı. Burada yalnız
 * Ping/Port/DNS'e özgü olanlar var. `t` parametre olarak alınır; sözlük import EDİLMEZ (i18n'siz test edilebilsin).
 */
import {
  classifyLine, downloadJson, failureKind as httpFailureKind, historyItems, interpolate, isRateLimited as httpIsRateLimited,
  reportFileName as httpReportFileName, stepState, verdictTone, writer,
} from '../http/diagnose/httpDiagnoseModel.js'
import { formatPercent } from '../../i18n/dateLocale.js'

export { downloadJson, historyItems, interpolate, stepState, verdictTone }

/** Tanılaması olan türler (sayfa ↔ uç). */
export const NET_DIAG_TYPES = ['ping', 'port', 'dns']

/**
 * Sözleşmenin bulgu kodları — backend `NetDiagFindings.CODES` ile AYNI liste ve sıra (i18n `ndx.finding.<KOD>.title|body`;
 * backend kapısı her kodun TR + EN metnini arar, bu dosyadaki model testi de).
 */
export const FINDING_CODES = [
  // Ortak
  'POLICY_BLOCKED', 'DNS_FAILED', 'NO_ADDRESS_FOR_IP_VERSION', 'PROXY_NOT_APPLICABLE', 'CLIENT_MISMATCH', 'RUN_TIME_LIMIT',
  'INCONCLUSIVE',
  // Ping
  'PING_OK', 'ICMP_UNAVAILABLE_HERE', 'ICMP_FILTERED_HOST_ALIVE', 'HOST_UNREACHABLE', 'ALL_PACKETS_LOST', 'PARTIAL_LOSS',
  'HIGH_RTT', 'TRACEROUTE_UNAVAILABLE',
  // Port
  'PORT_OK', 'CONNECT_REFUSED', 'CONNECT_TIMEOUT_FILTERED', 'NETWORK_UNREACHABLE', 'PROXY_UNREACHABLE',
  'PROXY_PORT_NOT_ALLOWED', 'PROXY_REFUSED', 'TLS_HANDSHAKE_FAILED', 'TLS_UNTRUSTED', 'TLS_HOSTNAME_MISMATCH',
  'CERT_EXPIRED', 'CERT_EXPIRES_SOON', 'HTTP_STATUS_MISMATCH', 'HTTP_BAD_RESPONSE', 'BANNER_MISMATCH', 'BANNER_EMPTY',
  'UDP_NO_REPLY', 'UDP_PORT_UNREACHABLE', 'PATH_DIFFERS', 'SOME_IPS_DOWN', 'SLOW_RESPONSE',
  // DNS
  'DNS_OK', 'NXDOMAIN_AUTHORITATIVE', 'NXDOMAIN_RESOLVER_ONLY', 'SERVFAIL_DNSSEC', 'SERVFAIL', 'RESOLVER_REFUSED',
  'RESOLVER_TIMEOUT', 'RESOLVERS_DISAGREE', 'AUTH_RESOLVER_MISMATCH', 'LAME_DELEGATION', 'AUTH_UNREACHABLE',
  'ZONE_NOT_FOUND', 'NO_RECORD_OF_TYPE', 'EXPECTED_MISMATCH', 'TRUNCATED_UDP', 'SLOW_RESOLVER',
]
export const FINDING_SET = new Set(FINDING_CODES)

/** Adım anahtarları — backend `NetDiagFindings.STEP_KEYS` (i18n `ndx.step.<anahtar>`). Çizim SUNUCU sırasını korur. */
export const STEP_KEYS = [
  'policy', 'dns', 'icmp', 'tcp_alive', 'traceroute',
  'connect', 'proxy_tunnel', 'tls', 'http', 'banner', 'udp',
  'resolvers', 'zone', 'authoritative', 'dnssec', 'compare',
]

/** Atlanan adımın gerekçeleri (`detail.reason`, i18n `ndx.skip.<gerekçe>`). */
export const SKIP_REASONS = ['policy', 'dns', 'tls', 'not_needed', 'no_resolver', 'zone', 'no_ns', 'unavailable']

/** Türün başarı kodu — bilgi bulgusu olsa da yeşil tonda. */
export const OK_CODES = new Set(['PING_OK', 'PORT_OK', 'DNS_OK'])

/** Tanılama uçlarının yolu (429 tespiti son başarısız çağrı halkasında bu yola bakar). */
export const DIAG_PATH = {
  ping: /\/monitoring\/ping\/\d+\/diagnose/,
  port: /\/monitoring\/port\/\d+\/diagnose/,
  dns: /\/monitoring\/dns\/\d+\/diagnose/,
}

/** Sunucunun izin verdiği zaman aşımı aralığı (NetDiagRun.clampTimeout: 1–30 sn; yoksa 5 sn). */
export const TIMEOUT_MIN = 1000
export const TIMEOUT_MAX = 30000
export const TIMEOUT_DEFAULT = 5000

/** Değeri yol adı (proxy|direct) olan parametreler — metinde "Vekil"/"Doğrudan" diye yerelleşir. */
const ROUTE_PARAMS = new Set(['route', 'failing_route', 'working_route'])
/** Değeri sözlükteki bir değer adı olan parametreler (CLIENT_MISMATCH: up/down, open/closed, ok/fail). */
const VALUE_PARAMS = new Set(['diag', 'client'])

/** Ayrıntıda kod bloğu olarak çizilen anahtarlar (çok satırlı ham çıktı / önizleme). */
export const CODE_KEYS = new Set(['output', 'text_preview', 'hex_preview'])
/** Değeri sözlükten çevrilen ayrıntı anahtarları (`ndx.val.<değer>`; yoksa ham değer). */
const ENUM_KEYS = new Set(['result', 'outcome', 'note', 'auth_vs_resolver', 'label', 'error_kind'])
/** Tek biçimli (mono) yazılan ayrıntı anahtarları — host, IP, komut, durum satırı … */
const MONO_KEYS = new Set([
  'host', 'addresses', 'target_ip', 'ip', 'command', 'request_line', 'status_line', 'sni', 'cipher', 'subject', 'issuer',
  'san', 'proxy', 'servers', 'ns', 'zone', 'name', 'expected', 'unexpected', 'sent_preview', 'via', 'soa_serial',
  'allowed_ports', 'protocol', 'alpn', 'values', 'error', 'failure_reason',
])

/** Değeri `false` iken sorun anlatan / `true` iken sorun anlatan ayrıntı anahtarları (değer kırmızı yazılır). */
const FALSE_BAD = /(match|trusted|allowed|alive|agree|reached|available)/
const TRUE_BAD = new Set(['icmp_unreachable', 'timed_out', 'validation_failure', 'read_timeout'])

const isNum = (v) => typeof v === 'number' && Number.isFinite(v)
const arr = (v) => (Array.isArray(v) ? v : [])
const isScalar = (v) => v == null || ['string', 'number', 'boolean'].includes(typeof v)
const isEmpty = (v) => v == null || v === '' || (Array.isArray(v) && v.length === 0)
  || (typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === 0)

/** Sözlükte gerçekten tanımlı mı? (`t` eksik anahtarda anahtarın kendisini döner.) */
export const has = (t, key) => t(key) !== key

/** İzlemenin zaman aşımı → tanılamanın adım başına süre sınırı (sunucuyla aynı kısma). */
export function clampTimeout(ms, fallback = TIMEOUT_DEFAULT) {
  const n = Number(ms)
  const v = !Number.isFinite(n) || n <= 0 ? fallback : Math.round(n)
  return Math.min(TIMEOUT_MAX, Math.max(TIMEOUT_MIN, v))
}

/** Yol değeri → yerel ad ("Vekil" / "Doğrudan"); bilinmeyen değer olduğu gibi. */
export function routeText(route, t) {
  if (route === 'proxy' || route === 'direct') return t(`httpdx.route.${route}`)
  return route == null || route === '' ? '—' : String(route)
}

/** Sözlük değer adı → yerel metin (`ndx.val.<değer>`); tanımsızsa ham değer. "n/a" → `na`. */
export function valueText(v, t) {
  if (v == null || v === '') return '—'
  const s = String(v)
  const k = s === 'n/a' ? 'na' : s
  if (/^[A-Za-z0-9_]+$/.test(k)) {
    const key = `ndx.val.${k}`
    if (has(t, key)) return t(key)
  }
  return s
}

/** Ayrıntı anahtarının etiketi (`ndx.kv.<anahtar>`); tanımsızsa ham anahtar (yeni alan düşmesin). */
export function kvLabel(key, t) {
  const k = `ndx.kv.${key}`
  return has(t, k) ? t(k) : String(key)
}

/** Adımın adı (`ndx.step.<anahtar>`); tanımsızsa ham anahtar. */
export function stepLabel(key, t) {
  const k = `ndx.step.${key}`
  return has(t, k) ? t(k) : String(key)
}

/** Atlanan adımın gerekçesi (`ndx.skip.<gerekçe>`); tanımsızsa ham değer. */
export function skipReasonText(reason, t) {
  if (!reason) return null
  const k = `ndx.skip.${reason}`
  return has(t, k) ? t(k) : String(reason)
}

/** Bulgu parametrelerini metne hazırlar: yol/değer adları yerelleşir, dizi birleşir, boş "—". */
export function localizeParams(params, t) {
  const out = {}
  for (const [k, v] of Object.entries(params || {})) {
    if (v == null || v === '') { out[k] = '—'; continue }
    if (ROUTE_PARAMS.has(k)) out[k] = routeText(v, t)
    else if (VALUE_PARAMS.has(k)) out[k] = valueText(v, t)
    else if (Array.isArray(v)) out[k] = v.length ? v.join(', ') : '—'
    else if (typeof v === 'object') out[k] = JSON.stringify(v)
    else out[k] = String(v)
  }
  return out
}

/**
 * Bulgunun yerel başlık + gövdesi. Başlıklar yer tutucusuz (geçmiş listesi parametresiz koddan başlık çizebilsin);
 * gövde ADLI parametrelerle dolar. `params.reason` varsa önce varyant metni (`…body.<reason>` / `…title.<reason>`),
 * yoksa genel metin — HTTP tanılamasının deseni. Bilinmeyen kod genel metinle düşer (ham anahtar yok).
 */
export function findingText(finding, t) {
  const code = finding?.code || 'UNKNOWN'
  const base = `ndx.finding.${code}`
  if (!has(t, `${base}.title`)) {
    return {
      known: false,
      title: interpolate(t('ndx.finding.UNKNOWN.title'), { code }),
      body: interpolate(t('ndx.finding.UNKNOWN.body'), { code }),
    }
  }
  const p = localizeParams(finding?.params, t)
  const reason = finding?.params?.reason
  const pick = (part) => {
    const v = reason ? `${base}.${part}.${reason}` : null
    return v && has(t, v) ? t(v) : t(`${base}.${part}`)
  }
  return { known: true, title: pick('title'), body: interpolate(pick('body'), p) }
}

/** Yalnız koddan başlık (geçmiş satırı — parametre yok). */
export function findingTitle(code, t) {
  return findingText({ code, params: {} }, t).title
}

/** Bulgu önemi → ton. Türün OK kodu yeşil; diğer bilgi bulguları mavi. */
export function severityTone(severity, code) {
  if (severity === 'fail') return 'danger'
  if (severity === 'warn') return 'warning'
  return OK_CODES.has(code) ? 'success' : 'info'
}

/** Yol / satır sonucu → ton. */
export function outcomeTone(outcome) {
  if (outcome === 'ok') return 'success'
  if (outcome === 'warn') return 'warning'
  if (outcome === 'fail') return 'danger'
  return 'muted'
}

/** Bulgu → çizime hazır kayıt. */
export function decorate(f, t) {
  const { title, body } = findingText(f, t)
  return {
    code: f?.code || 'UNKNOWN', severity: f?.severity || 'info', path: f?.path || null, params: f?.params || {},
    title, body, tone: severityTone(f?.severity, f?.code),
  }
}

/**
 * Hüküm kartı + bulgu listesi: hüküm = sunucunun `verdict`'i (yoksa ilk bulgu); `others` = kalan bulgular SUNUCU
 * SIRASINDA (en önemli ilk). Hükümle AYNI kod + yol taşıyan ilk bulgu listede tekrar edilmez.
 */
export function buildVerdict(data, t) {
  const v = data?.verdict || null
  const findings = arr(data?.findings).filter(Boolean)
  const head = v ? { code: v.code, severity: v.status === 'ok' ? 'info' : v.status, params: v.params, path: v.path }
    : findings[0] || { code: 'INCONCLUSIVE', severity: 'warn', params: {} }
  const status = v?.status || (head.severity === 'fail' ? 'fail' : head.severity === 'warn' ? 'warn' : 'ok')
  const { title, body } = findingText(head, t)
  const same = (f) => f?.code === head.code && (f?.path || null) === (head.path || null)
  let skipped = false
  const others = []
  for (const f of findings) {
    if (!skipped && same(f)) { skipped = true; continue }
    others.push(decorate(f, t))
  }
  return { status, tone: verdictTone(status), code: head.code || 'UNKNOWN', title, body, path: head.path || null, others }
}

/**
 * Adımlar SUNUCU SIRASINDA (Ping: policy → dns → icmp → tcp_alive → traceroute; DNS: resolvers → dnssec → zone → …).
 * Durum normalleşir; atlanan adımın gerekçesi `reason`'a ayrılır.
 */
export function normalizeSteps(steps) {
  return arr(steps).filter((s) => s && s.key).map((s) => {
    const state = stepState(s.status)
    const detail = s.detail && typeof s.detail === 'object' ? s.detail : {}
    return {
      key: s.key, state, ms: isNum(s.ms) ? s.ms : null, detail,
      error: s.error && (s.error.message || s.error.class) ? s.error : null,
      reason: state === 'skip' ? (detail.reason ?? null) : null,
    }
  })
}

/** Ayrıntı değeri biçimi: süre "N ms", kayıp "%N", evet/hayır, sözlük değerleri, yol adları. */
export function formatValue(key, v, t) {
  if (v == null || v === '') return '—'
  if (typeof v === 'boolean') return t(v ? 'ndx.yes' : 'ndx.no')
  if (isNum(v)) {
    if (key === 'ms' || /_ms$/.test(key)) return `${v} ms`
    if (key === 'loss_pct' || key === 'packet_loss') return formatPercent(v)
    return String(v)
  }
  if (key === 'route') return routeText(v, t)
  if (ENUM_KEYS.has(key)) return valueText(v, t)
  return String(v)
}

/** Nesne dizisinin tek satırı ("IP: 192.0.2.1 · Sonuç: açık · 12 ms") — tcp_alive `probes`, connect `tried`. */
export function objectLine(o, t) {
  if (!o || typeof o !== 'object') return String(o ?? '')
  return Object.entries(o).filter(([, v]) => !isEmpty(v)).map(([k, v]) => {
    if (k === 'ms' && isNum(v)) return `${v} ms`
    const val = Array.isArray(v) ? v.join(', ') : typeof v === 'object' ? JSON.stringify(v) : formatValue(k, v, t)
    return `${kvLabel(k, t)}: ${val}`
  }).join(' · ')
}

/**
 * Adım ayrıntısı → satırlar: `{ key, label, kind: 'text'|'code'|'list', value, items, mono, tone }`. Boş/null değer
 * atlanır; kod anahtarları (`output`, `text_preview`, `hex_preview`) kod bloğu; nesne dizileri satır listesi; sayı dizileri
 * virgülle; bilinmeyen anahtar ham adıyla etiketlenir. Atlanan adımın `reason`'ı satır değil, adım başlığında yazılır.
 */
export function detailRows(detail, t, { skip = false } = {}) {
  const rows = []
  for (const [key, v] of Object.entries(detail || {})) {
    if (isEmpty(v)) continue
    if (skip && key === 'reason') continue
    const label = kvLabel(key, t)
    if (CODE_KEYS.has(key)) {
      rows.push({ key, label, kind: 'code', value: String(v) })
    } else if (Array.isArray(v)) {
      if (v.every(isScalar)) rows.push({ key, label, kind: 'text', value: v.map((x) => formatValue(key, x, t)).join(', '), mono: MONO_KEYS.has(key) })
      else rows.push({ key, label, kind: 'list', items: v.map((o) => objectLine(o, t)) })
    } else if (typeof v === 'object') {
      rows.push({ key, label, kind: 'text', value: objectLine(v, t) })
    } else {
      rows.push({ key, label, kind: 'text', value: formatValue(key, v, t), mono: MONO_KEYS.has(key) && typeof v === 'string',
        tone: (v === false && FALSE_BAD.test(key)) || (v === true && TRUE_BAD.has(key)) ? 'bad' : null })
    }
  }
  return rows
}

/** Adım hatası tek satır ("Sınıf: ileti"). */
export function errorText(error) {
  if (!error) return ''
  return [error.class, error.message].filter(Boolean).join(': ')
}

// ── Port: yollar ─────────────────────────────────────────────────────────────

/** Vekil adresi ("host:port") — üst bilgiden. */
export function proxyAddress(data) {
  return data?.proxy?.configured && data.proxy.address ? String(data.proxy.address) : null
}

/** Yolun tam etiketi: "Vekil · proxy.example.test:8080" / "Doğrudan". */
export function routeLabel(route, data, t) {
  const r = routeText(route, t)
  if (route !== 'proxy') return r
  const addr = proxyAddress(data)
  return addr ? `${r} · ${addr}` : r
}

/** Yol başlığı ("İzlemenin yolu" / "Öteki yol"). */
export function pathTitle(key, t) {
  return t(key === 'alternate' ? 'httpdx.path.alternate' : 'httpdx.path.monitor')
}

/** Port yolları (yalnız vekil tanımlı + UDP değilse) → kart kayıtları. */
export function normalizePaths(data, t) {
  return arr(data?.paths).filter((p) => p && p.key).map((p) => {
    const v = p.verdict || null
    const verdict = v ? decorate({ code: v.code, severity: v.status === 'ok' ? 'info' : v.status, params: v.params, path: v.path }, t) : null
    return {
      key: p.key, route: p.route || null, outcome: p.outcome || 'fail', ms: isNum(p.ms) ? p.ms : null, ip: p.ip || null,
      steps: normalizeSteps(p.steps), verdict, verdictStatus: v?.status || (p.outcome === 'ok' ? 'ok' : 'fail'),
      findings: arr(p.findings).map((f) => decorate(f, t)),
    }
  })
}

// ── DNS: çözücü / yetkili sunucu satırları ───────────────────────────────────

/**
 * DNS satırının durumu: yanıt yok (hata/zaman aşımı) ya da NOERROR dışı rcode → fail; NOERROR ama cevap yok → warn;
 * yetkili sunucu `aa` bayrağı olmadan yanıt verdiyse (lame) → warn; aksi ok.
 */
export function dnsRowStatus(row, kind = 'resolver') {
  if (!row) return 'fail'
  if (row.error_kind || (row.error && !row.rcode)) return 'fail'
  if (row.rcode && row.rcode !== 'NOERROR') return 'fail'
  if (kind === 'authoritative' && row.aa === false) return 'warn'
  if (!arr(row.answers).length) return 'warn'
  return 'ok'
}

/** DNS satırının durum rozeti metni: rcode, yoksa hata türü (zaman aşımı / hata / politika). */
export function dnsRowBadge(row, t) {
  if (row?.rcode) return String(row.rcode)
  if (row?.error_kind) return valueText(row.error_kind, t)
  return t('ndx.dns.noResponse')
}

/** Satırın bayrakları (AA / AD / TC / TCP) — yalnız true olanlar. */
export function dnsFlags(row) {
  return ['aa', 'ad', 'tc', 'tcp'].filter((f) => row?.[f] === true)
}

/** DNS bloğu → çizime hazır { name, recordType, zone, ns, resolvers, authoritative }. */
export function dnsData(data) {
  const d = data?.dns || {}
  const map = (rows, kind) => arr(rows).filter(Boolean).map((r, i) => ({
    ...r, i, kind, status: dnsRowStatus(r, kind), answers: arr(r.answers), cname: arr(r.cname), cd_answers: arr(r.cd_answers),
  }))
  return {
    name: d.name || data?.target?.host || null,
    recordType: d.record_type || data?.target?.record_type || null,
    zone: d.zone || null,
    ns: arr(d.ns),
    timeoutMs: isNum(d.timeout_ms) ? d.timeout_ms : null,
    resolvers: map(d.resolvers, 'resolver'),
    authoritative: map(d.authoritative, 'authoritative'),
  }
}

// ── İzleme istemcisi ─────────────────────────────────────────────────────────

/** İzlemenin GERÇEK denetleyicisinin (client_check) özeti: durum + gösterilecek alanlar (boşlar atlanır). */
export function clientSummary(type, c, t) {
  if (!c || typeof c !== 'object') return null
  if (c.skipped) return { state: 'skipped', reason: c.reason || null, rows: [] }
  const keys = type === 'ping' ? ['rtt_ms', 'packet_loss', 'failure_reason', 'error']
    : type === 'port' ? ['response_ms', 'via', 'detail', 'proxy_refused', 'failure_reason', 'error']
      : ['values', 'ttl', 'response_ms', 'failure_reason', 'error']
  const rows = []
  for (const k of keys) {
    const v = c[k]
    if (isEmpty(v) || (k === 'proxy_refused' && v !== true)) continue
    let value
    if (k === 'packet_loss' && isNum(v)) value = formatPercent(v)
    else if (k === 'ttl' && isNum(v)) value = `${v} s`
    else if (k === 'via') value = routeText(v, t)
    else if (Array.isArray(v)) value = v.join(', ')
    else value = formatValue(k, v, t)
    rows.push({ key: k, label: kvLabel(k, t), value, mono: MONO_KEYS.has(k), tone: k === 'error' ? 'bad' : null })
  }
  const state = type === 'ping' && c.na === true ? 'na' : c.ok === true ? 'ok' : 'fail'
  return { state, rows }
}

// ── Hedef ────────────────────────────────────────────────────────────────────

/** Türün hedef metni: ping host; port host:port; dns ad. */
export function targetText(type, m) {
  const x = m || {}
  if (type === 'port') return x.host ? `${x.host}${x.port != null ? `:${x.port}` : ''}` : '—'
  if (type === 'dns') return x.domain || x.name || x.host || '—'
  return x.host || '—'
}

/** Hedefin ek etiketi (rozet): port protokolü, DNS kayıt türü, ping IP sürümü. */
export function targetTag(type, m) {
  const x = m || {}
  if (type === 'port') return x.protocol ? String(x.protocol).toUpperCase() : 'TCP'
  if (type === 'dns') return x.record_type ? String(x.record_type).toUpperCase() : null
  return 'ICMP'
}

// ── Döküm ────────────────────────────────────────────────────────────────────

/** Döküm metni → satırlar `{ i, text, kind }` (`*` bilgi, `>` gönderilen, `<` alınan, `──` bölüm). */
export function transcriptLines(transcript) {
  const raw = Array.isArray(transcript) ? transcript.map(String) : String(transcript ?? '').split(/\r?\n/)
  while (raw.length && raw[raw.length - 1] === '') raw.pop()
  return raw.map((text, i) => ({ i, text, kind: /^\s*──/.test(text) ? 'section' : classifyLine(text) }))
}

// ── Hata sınıfı / dosya adı ──────────────────────────────────────────────────

/** Başarısız yanıtın sınıfı (rateLimited | forbidden | notFound | network | other) — türün uç yoluyla. */
export function failureKind(res, recentFailures = [], type = 'ping') {
  return httpFailureKind(res, recentFailures, DIAG_PATH[type] || DIAG_PATH.ping)
}

/** 429 mu? (gövdede durum yoksa son başarısız çağrı halkasına bakılır). */
export function isRateLimited(res, recentFailures = [], type = 'ping') {
  return httpIsRateLimited(res, recentFailures, DIAG_PATH[type] || DIAG_PATH.ping)
}

/** JSON dosya adı: <tür>-diagnose-<izleme>-run<no>.json. */
export function reportFileName(data, now = new Date(), type = 'ping') {
  return httpReportFileName(data, now, `${type}-diagnose`)
}

// ── Rapor ────────────────────────────────────────────────────────────────────

const msText = (v) => (isNum(v) ? `${v} ms` : '—')

/** Adımın tek satır özeti (rapor + kompakt hat): "Ad durum N ms (gerekçe)". */
export function stepSummary(s, t) {
  return `${stepLabel(s.key, t)} ${t(`httpdx.stepState.${s.state}`)}${s.ms != null ? ` ${s.ms} ms` : ''}${s.reason ? ` (${skipReasonText(s.reason, t)})` : ''}`
}

/** DNS satırının rapor satırı. */
function dnsRowLine(r, t) {
  const who = [r.server, r.ns ? `(${r.ns})` : null, r.label ? `[${valueText(r.label, t)}]` : null].filter(Boolean).join(' ')
  const bits = [
    dnsRowBadge(r, t),
    r.answers.length ? r.answers.join(', ') : t('ndx.dns.noAnswers'),
    isNum(r.ttl) ? `TTL ${r.ttl}` : null,
    msText(r.ms),
    dnsFlags(r).map((f) => f.toUpperCase()).join(' ') || null,
    r.error ? r.error : null,
  ].filter(Boolean)
  return `${who}: ${bits.join(' · ')}`
}

/**
 * Paylaşılabilir rapor (bilet/sohbet) — ekranda ne varsa: künye, hüküm + bulgular, adımlar (ayrıntılarıyla), Port'ta
 * yollar, DNS'te çözücü / yetkili satırları, izleme istemcisi ve döküm. Vekil parolası / başlıklar sunucuda maskeli.
 *
 * @param {{data, t, formatDate?, format?: 'markdown'|'text', type?: string}} o
 */
export function buildReport({ data, t, formatDate, format = 'markdown', type }) {
  const d = data || {}
  const kind = type || d.kind || d.type || 'ping'
  const w = writer(format)
  const fd = (iso) => (iso ? (formatDate ? formatDate(iso) : String(iso)) : '—')
  const mon = d.monitor || {}
  w.h1(t(`ndx.report.title.${kind}`))
  w.kv(t('httpdx.report.monitor'), `${mon.name || targetText(kind, mon)}${mon.id != null ? ` (#${mon.id})` : ''}`)
  const tag = targetTag(kind, mon)
  w.kv(t('httpdx.report.target'), `${targetText(kind, { ...(d.target || {}), ...mon })}${tag ? ` (${tag})` : ''}`)
  w.kv(t('httpdx.report.run'), [d.run_id != null ? `#${d.run_id}` : null, fd(d.started_at), msText(d.duration_ms),
    d.executed_by || null].filter(Boolean).join(' · '))
  const src = d.source || {}
  w.kv(t('httpdx.report.source'), [src.pod && `pod ${src.pod}`, src.node && `node ${src.node}`, src.pod_ip && `IP ${src.pod_ip}`].filter(Boolean).join(' · ') || '—')
  if (d.route?.own) w.kv(t('ndx.report.route'), routeLabel(d.route.own, d, t))
  if (proxyAddress(d)) w.kv(t('httpdx.report.proxy'), proxyAddress(d))
  if (d.options?.traceroute) w.kv(t('ndx.step.traceroute'), t('ndx.yes'))

  const v = buildVerdict(d, t)
  w.h2(`${t('httpdx.report.verdict')}: ${t(`httpdx.status.${v.status}`)} — ${v.title}`)
  w.p(v.body)
  if (v.others.length) {
    w.h3(t('ndx.findings.title'))
    for (const f of v.others) {
      w.li(`[${t(`httpdx.sev.${f.severity}`)}] ${f.title}${f.path ? ` (${pathTitle(f.path, t)})` : ''} — ${f.body}`)
    }
  }

  const paths = normalizePaths(d, t)
  if (paths.length) {
    w.h2(t('httpdx.compare.title'))
    if (d.comparison?.available) w.p(t(d.comparison.differs ? 'httpdx.compare.differs' : 'httpdx.compare.same'))
    for (const p of paths) {
      w.h3(`${pathTitle(p.key, t)} — ${routeLabel(p.route, d, t)}`)
      w.kv(t('httpdx.report.outcome'), [t(`httpdx.outcome.${p.outcome}`), msText(p.ms), p.ip ? `IP ${p.ip}` : null,
        p.verdict ? p.verdict.title : null].filter(Boolean).join(' · '))
      if (p.steps.length) w.kv(t('ndx.steps.title'), p.steps.map((s) => stepSummary(s, t)).join(' → '))
    }
  }

  const steps = normalizeSteps(d.steps)
  if (steps.length) {
    w.h2(t('ndx.steps.title'))
    for (const s of steps) {
      w.h3(stepSummary(s, t))
      for (const r of detailRows(s.detail, t, { skip: s.state === 'skip' })) {
        if (r.kind === 'code') {
          w.p(`${r.label}:`)
          w.code(r.value.split(/\r?\n/), 'text')
        } else if (r.kind === 'list') {
          w.kv(r.label, r.items.join(' | '))
        } else {
          w.kv(r.label, r.value)
        }
      }
      if (s.error) w.kv(t('ndx.steps.error'), errorText(s.error))
    }
  }

  if (kind === 'dns' && d.dns) {
    const dd = dnsData(d)
    w.h2(t('ndx.dns.title'))
    if (dd.zone) w.kv(t('ndx.kv.zone'), dd.zone)
    if (dd.ns.length) w.kv(t('ndx.kv.ns'), dd.ns.join(', '))
    if (dd.resolvers.length) {
      w.h3(t('ndx.dns.resolvers'))
      dd.resolvers.forEach((r) => w.li(dnsRowLine(r, t)))
    }
    if (dd.authoritative.length) {
      w.h3(t('ndx.dns.authoritative'))
      dd.authoritative.forEach((r) => w.li(dnsRowLine(r, t)))
    }
  }

  const c = clientSummary(kind, d.client_check, t)
  if (c) {
    w.h2(t('httpdx.client.title'))
    if (c.state === 'skipped') w.p(t('ndx.client.skipped'))
    else {
      w.p(t(c.state === 'ok' ? 'httpdx.client.ok' : c.state === 'na' ? 'ndx.client.na' : 'httpdx.client.fail'))
      c.rows.forEach((r) => w.kv(r.label, r.value))
    }
  }

  const lines = transcriptLines(d.transcript)
  if (lines.length) {
    w.h2(t('ndx.transcript.title'))
    w.code(lines.map((l) => l.text), 'text')
  }
  w.blank()
  w.p(t('httpdx.source.egress'))
  return w.lines.join('\n').replace(/\n{3,}/g, '\n\n').trim() + '\n'
}
