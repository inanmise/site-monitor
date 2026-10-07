/**
 * Kontrol geçmişi HATA TEŞHİSİ modeli — saf fonksiyonlar, React yok (2026-10-05, kullanıcı isteği: "kontrol geçmişinde
 * alınan hatanın detayı olmayan izlemeler için … hata alındığında detaylıca ne hatası aldığını görelim").
 *
 * <p>Ping, Port, DNS, Sayfa Bütünlüğü, Sayfa Hızı, Durum (envanter erişilebilirliği), Alan Adı ve Sertifika geçmişinin
 * ORTAK modeli. Sunucu her başarısız kontrolde `failure_reason` (kod, `checkFailureCodes.js`) + `failure_detail` (kompakt
 * JSON: evre, hedef, yol, zaman aşımı, rcode, paket kaybı, istisna zinciri …) yazar. Arayüz metni KODDAN, kullanıcının
 * dilinde kurar: `chkfail.<KOD>.short|why|effect|fix` — adlı yer tutucular ({target}, {ms}, {status}, {expected}, {rcode},
 * {record}, {loss}, {proxy_status}, {allowed}, {got}, {broken}, {timeouts}, {mixed}) satırdan ve izlemeden doldurulur.
 *
 * <p>Eski satırlar (bu tarihten önce yazılmış, kod kolonu NULL) zarifçe çizilir: türün MEVCUT kart sınıflandırıcısı
 * (pingCardModel.failureReason, portCardModel.portResult, pageCardModel.pageFailure, domainCardModel.unknownReasonOf,
 * sertifikanın `error_class`'ı) EN YAKIN koda çevrilir ve `legacy: true` döner. Sertifika satırları kod kolonu taşımaz;
 * aşama (`error_stage`) kaydedilmişse ayrıntı kayıtlı sayılır.
 */
import { interpolate } from '../http/diagnose/httpDiagnoseModel.js'
import { formatPercent } from '../../i18n/dateLocale.js'
import { failureReason as pingFailureReason } from '../ping/pingCardModel.js'
import { portResult } from '../port/portCardModel.js'
import { pageFailure } from '../page/pageCardModel.js'
import { unknownReasonOf } from '../domain/domainCardModel.js'
import {
  CHECK_FAILURE_PHASE, CHECK_FAILURE_PHASES, CHECK_FAILURE_SET, CHECK_FAILURE_WARN_CODES,
} from './checkFailureCodes.js'

const num = (v) => {
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}
const str = (v) => (v == null ? '' : String(v).trim())
const firstLine = (s) => str(s).split(/\r?\n/)[0].trim()

/** `failure_detail` JSON'unu güvenle çözer (dize ya da nesne). Yoksa/bozuksa boş nesne. */
export function parseFailureDetail(row) {
  const raw = row?.failure_detail
  if (raw == null) return {}
  if (typeof raw === 'object') return raw
  if (typeof raw !== 'string' || !raw.trim()) return {}
  try {
    const d = JSON.parse(raw)
    return d && typeof d === 'object' && !Array.isArray(d) ? d : {}
  } catch {
    return {}
  }
}

/** Satır SAĞLIKLI mı — türün sunucu sözleşmesi (sınıflandırma bunu DEĞİŞTİRMEZ, yalnız okur). */
export function isHealthy(type, row) {
  if (!row) return true
  switch (type) {
    case 'ping': return row.up === true
    case 'port': return row.open === true
    case 'uptime': return str(row.status).toLowerCase() === 'up'
    case 'dns': return str(row.value) !== ''
    case 'page': {
      const s = str(row.status).toUpperCase()
      return (s === 'OK' || s === '') && row.ok !== false
    }
    case 'pagespeed': return row.ok !== false
    case 'domain': {
      const s = str(row.status).toUpperCase()
      return s === 'OK' || s === 'WARNING' || s === 'CRITICAL'
    }
    case 'cert': return str(row.status).toLowerCase() !== 'error'
    default: return row.ok !== false
  }
}

// ── Eski satırlar: metinden EN YAKIN kod (backend CheckFailureClassifier.fromMessage aynası) ──
const TEXT_RULES = [
  [/^çözümlenemeyen host|unknownhost|unknown host|name or service not known|no such host|nodename nor servname|could not resolve/i, 'DNS_RESOLVE'],
  [/^izin verilmeyen hedef|not allowed target/i, 'SSRF_BLOCKED'],
  [/^boş hedef host|^yapılandırma hatası/i, 'CONFIG_ERROR'],
  [/vekil tüneli reddetti/i, 'PROXY_REFUSED'],
  [/çok fazla yönlendirme|too many redirects/i, 'REDIRECT_LIMIT'],
  [/no name matching|subject alternative|hostname.*match/i, 'TLS_HOSTNAME'],
  [/pkix|unable to find valid certification path|certificate_unknown|self.signed/i, 'TLS_TRUST'],
  [/handshake|\bssl|\btls/i, 'TLS_HANDSHAKE'],
  [/proxy|vekil|tunnel/i, 'PROXY_ERROR'],
  [/connect timed out|connecttimeout/i, 'CONNECT_TIMEOUT'],
  [/timed? ?out|timeout|zaman aşımı/i, 'READ_TIMEOUT'],
  [/connection refused|connectexception|bağlantı reddedildi/i, 'CONNECT_REFUSED'],
  [/no route to host|unreachable/i, 'HOST_UNREACHABLE'],
  [/connection reset|broken pipe|received no bytes/i, 'CONNECTION_RESET'],
  [/^(?:ana sayfa |sayfa )?HTTP \d{3}/i, 'HTTP_STATUS'],
]

/** Hata metni → en yakın kod; eşleşme yoksa null. */
export function codeFromText(text) {
  const s = str(text)
  if (!s) return null
  for (const [re, code] of TEXT_RULES) if (re.test(s)) return code
  return null
}

const PING_KIND = { na: 'ICMP_UNAVAILABLE', dns: 'DNS_RESOLVE', unreachable: 'HOST_UNREACHABLE', timeout: 'ICMP_NO_REPLY' }
const ICMP_NA_RE = /ICMP bu ortamda kullanılamıyor|ICMP is not available/i

function legacyPing(row) {
  const err = str(row.error)
  const r = pingFailureReason({ status: ICMP_NA_RE.test(err) ? 'na' : 'down', error: err, packet_loss: row.packet_loss })
  return PING_KIND[r?.kind] || 'UNKNOWN'
}

function legacyTcp(row, protocol, expect) {
  const err = str(row.error)
  const r = portResult({ status: 'closed', error: err, protocol, expect, checked_at: row.checked_at })
  switch (r.kind) {
    case 'proxy': return 'PROXY_REFUSED'
    case 'blocked': return 'SSRF_BLOCKED'
    case 'dns': return 'DNS_RESOLVE'
    case 'http': return 'HTTP_STATUS'
    case 'banner': return 'BANNER_MISMATCH'
    case 'refused': return 'CONNECT_REFUSED'
    case 'filtered': return r.udp ? 'UDP_NO_REPLY' : 'CONNECT_TIMEOUT'
    case 'unreachable': return 'HOST_UNREACHABLE'
    case 'tls': {
      const c = codeFromText(err)
      return c === 'TLS_TRUST' || c === 'TLS_HOSTNAME' ? c : 'TLS_HANDSHAKE'
    }
    default: return codeFromText(err) || 'UNKNOWN'
  }
}

function legacyDns(row) {
  const e = str(row.error)
  // 2026-10-05 öncesi DNS satırlarında hata metni HİÇ saklanmazdı — metin yoksa neden bilinmez (tahmin yürütülmez).
  if (!e) return 'UNKNOWN'
  if (/NXDOMAIN/i.test(e)) return 'DNS_NXDOMAIN'
  if (/SERVFAIL/i.test(e)) return 'DNS_SERVFAIL'
  if (/REFUSED/i.test(e)) return 'DNS_REFUSED'
  if (/no answer/i.test(e)) return 'DNS_NO_ANSWER'
  if (/timed? ?out|timeout/i.test(e)) return 'DNS_TIMEOUT'
  return 'DNS_RESOLVE'
}

function pageResourcesCode(row) {
  if ((num(row.broken_resources) ?? 0) > 0) return 'RESOURCES_BROKEN'
  if ((num(row.timeout_count) ?? 0) > 0) return 'RESOURCES_TIMEOUT'
  if ((num(row.mixed_content_count) ?? 0) > 0) return 'MIXED_CONTENT'
  return 'RESOURCES_BROKEN'
}

function legacyPage(row) {
  const s = str(row.status).toUpperCase()
  if (s === 'CONFIG_ERROR') return 'CONFIG_ERROR'
  if (s === 'DEGRADED' || (s === 'OK' && row.ok === false)) return pageResourcesCode(row)
  const pf = pageFailure({ ...row, status: 'DOWN' })
  if (pf?.detail && /^HTTP \d{3}$/.test(pf.detail)) return 'HTTP_STATUS'
  return codeFromText(row.error) || ((num(row.http_status) ?? 0) >= 400 ? 'HTTP_STATUS' : 'UNKNOWN')
}

function legacyPageSpeed(row) {
  const e = str(row.error_message)
  if (/^yapılandırma hatası/i.test(e)) return 'CONFIG_ERROR'
  if ((num(row.status_code) ?? 0) >= 400) return 'HTTP_STATUS'
  return codeFromText(e) || 'UNKNOWN'
}

/** Alan adı kaynağının hata metni → kod (backend CheckFailureClassifier.forDomain aynası, bayraksız). */
export function domainCodeFromText(text) {
  const e = str(text).toLowerCase()
  if (!e) return 'REGISTRY_NO_EXPIRY'
  if (/^(geçersiz\/çözümlenemeyen domain|invalid domain|bad url)/.test(e)) return 'CONFIG_ERROR'
  if (e.includes('no expiry parsed')) return 'REGISTRY_NO_EXPIRY'
  const http = /rdap http (\d{3})/.exec(e)
  if (http) return http[1] === '404' ? 'RDAP_NOT_FOUND' : http[1] === '429' ? 'RDAP_RATE_LIMITED' : 'RDAP_UNAVAILABLE'
  if (/^(rdap parse|no rdap)/.test(e)) return 'RDAP_UNAVAILABLE'
  if (/^(tr )?whois/.test(e)) return 'WHOIS_UNAVAILABLE'
  const net = codeFromText(text)
  return net && net !== 'HTTP_STATUS' ? net : 'RDAP_UNAVAILABLE'
}

function legacyDomain(row) {
  const r = unknownReasonOf({ checked_at: row.checked_at || 'x', error: row.error })
  return r?.kind === 'error' ? domainCodeFromText(row.error) : 'REGISTRY_NO_EXPIRY'
}

/** Sertifika satırı: sunucunun `error_class` sınıfı (DNS / NETWORK / SSL / BLOCKED) + ileti + aşama → kod. */
export function certCode(row) {
  const cls = str(row?.error_class).toUpperCase()
  const msg = str(row?.error)
  const stage = str(row?.error_stage)
  if (cls === 'DNS') return 'DNS_RESOLVE'
  if (cls === 'BLOCKED') return /^çözümlenemeyen host/i.test(msg) ? 'DNS_RESOLVE' : 'SSRF_BLOCKED'
  if (cls === 'SSL') {
    if (/no name matching|subject alternative/i.test(msg)) return 'TLS_HOSTNAME'
    if (/pkix|certification path|self.signed|certificate_unknown/i.test(msg)) return 'TLS_TRUST'
    return 'TLS_HANDSHAKE'
  }
  if (cls === 'NETWORK') {
    if (stage === 'proxy-connect' || /vekil|proxy|tunnel/i.test(msg)) return 'PROXY_ERROR'
    if (/^connection timeout/i.test(msg)) return stage === 'tls-handshake' ? 'TLS_HANDSHAKE' : 'CONNECT_TIMEOUT'
    if (/refused/i.test(msg)) return 'CONNECT_REFUSED'
    if (/no route|unreachable/i.test(msg)) return 'HOST_UNREACHABLE'
    if (/reset|broken pipe/i.test(msg)) return 'CONNECTION_RESET'
    return codeFromText(msg) || 'CONNECT_TIMEOUT'
  }
  return codeFromText(msg) || 'UNKNOWN'
}

function legacyCode(type, row, monitor) {
  switch (type) {
    case 'ping': return legacyPing(row)
    case 'port': return legacyTcp(row, monitor?.protocol || 'TCP', monitor?.expect)
    case 'uptime': return legacyTcp(row, 'TCP', null)
    case 'dns': return legacyDns(row)
    case 'page': return legacyPage(row)
    case 'pagespeed': return legacyPageSpeed(row)
    case 'domain': return legacyDomain(row)
    default: return codeFromText(row.error) || 'UNKNOWN'
  }
}

/**
 * Başarısız satırın nedeni: `{ code, legacy, phase, detail }` ya da null (sağlıklı satır).
 * Sunucu kodu varsa o (`legacy: false`); yoksa türün mevcut sınıflandırıcısından EN YAKIN kod (`legacy: true`).
 */
export function failureOf(type, row, monitor) {
  if (!row || isHealthy(type, row)) return null
  const detail = parseFailureDetail(row)
  let code
  let legacy
  if (type === 'cert') {
    code = certCode(row)
    legacy = !str(row.error_stage)
  } else if (row.failure_reason && CHECK_FAILURE_SET.has(row.failure_reason)) {
    code = row.failure_reason
    legacy = false
  } else {
    code = legacyCode(type, row, monitor) || 'UNKNOWN'
    if (!CHECK_FAILURE_SET.has(code)) code = 'UNKNOWN'
    legacy = true
  }
  const phase = CHECK_FAILURE_PHASES.includes(detail.phase) ? detail.phase : CHECK_FAILURE_PHASE[code]
  // Eski DNS satırı + boş hata metni: neyin olduğu kayıtlı değil — panel tahmin yerine "bilinmiyor" anlatır.
  const noText = legacy && type === 'dns' && !str(row.error)
  return { code, legacy, noText, phase, detail }
}

/** Neden tonu (ToneBadge dili): ayar/politika/ortam kökenli → uyarı, diğerleri → tehlike. */
export function failureTone(code) {
  return CHECK_FAILURE_WARN_CODES.has(code) ? 'warning' : 'danger'
}

function hostOfUrl(url) {
  if (!url) return null
  try { return new URL(String(url).replace('{timestamp}', '0')).host } catch { return null }
}

/** Hedefin okunur adı: ayrıntıdaki hedef, yoksa izlemenin / satırın kendi alanları. */
export function targetOf(type, row, monitor, detail = {}) {
  if (detail.target) return String(detail.target)
  const m = monitor || {}
  switch (type) {
    case 'ping': return m.host || null
    case 'port': return m.host ? `${m.host}${m.port != null ? `:${m.port}` : ''}` : null
    case 'uptime': return row?.domain ? `${row.domain}${row.port != null ? `:${row.port}` : ''}` : (m.domain || null)
    case 'dns': return m.domain || null
    case 'page':
    case 'pagespeed': return hostOfUrl(m.url) || m.url || null
    case 'domain': return m.domain || null
    case 'cert': return row?.domain || m.domain || null
    default: return null
  }
}

/**
 * Ayrıntıda zaman aşımı yoksa (eski satır) türün GERÇEK varsayılanı — sunucu kodundaki sabitlerle aynı: Durum yoklaması
 * 10 sn sabit (SchedulerService.recheckUptime), Sayfa 4 sn, Sayfa Hızı 10 sn, Port/Ping 5 sn, Alan Adı RDAP 6 sn;
 * sertifika hata metninden ("Connection timeout after Ns"). DNS çözümleyici zaman aşımı ayardır — uydurulmaz.
 */
function timeoutOf(type, row, monitor) {
  const m = monitor || {}
  switch (type) {
    case 'uptime': return 10000
    case 'page': return num(m.timeout_ms) ?? 4000
    case 'pagespeed': return num(m.timeout_ms) ?? 10000
    case 'port':
    case 'ping': return num(m.timeout_ms) ?? 5000
    case 'domain': return num(m.check_timeout_ms) ?? 6000
    case 'cert': {
      const s = /timeout after (\d+)\s*s/i.exec(str(row?.error))
      return s ? Number(s[1]) * 1000 : null
    }
    default: return num(m.timeout_ms)
  }
}

/** Metinlerin adlı parametreleri — ayrıntı + satır + izleme. Boş değer "—" olur (interpolate). */
export function failureParams(type, row, monitor, t, f) {
  const r = row || {}
  const m = monitor || {}
  const d = f?.detail || {}
  // Eski satırda ayrıntı yok: sunucunun kendi metninden ("HTTP 503 (beklenen: 2xx)", "Beklenen yanit yok: '220'
  // (gelen: …)") kod / beklenen / gelen okunur — "HTTP — döndü" gibi boş yer tutucu kalmasın.
  const errText = str(type === 'pagespeed' ? r.error_message : r.error)
  const textStatus = /\bHTTP (\d{3})\b/.exec(errText)
  const textExpected = /\((?:beklenen|expected):\s*([^)]*)\)/i.exec(errText)
  const textBanner = /beklenen yan[ıi]t yok:\s*'([^']*)'\s*\(gelen:\s*([\s\S]*)\)\s*$/i.exec(errText)
  const status = num(d.http_status) ?? num(r.http_status) ?? num(r.status_code) ?? (textStatus ? Number(textStatus[1]) : null)
  const loss = num(d.packet_loss) ?? num(r.packet_loss)
  const expected = d.expected ?? textBanner?.[1] ?? textExpected?.[1]?.trim() ?? (type === 'port' ? m.expect : null)
  const got = d.got ?? (textBanner && textBanner[2].trim() !== 'bos' ? textBanner[2].trim() : null)
  const textProxy = /vekil tüneli reddetti:\s*HTTP\/1\.[01]\s+(\d{3})/i.exec(errText)
  const textAllowed = /izinli:\s*([\d,\s]+)\)/i.exec(errText)
  return {
    target: targetOf(type, r, m, d),
    ms: num(d.timeout_ms) ?? timeoutOf(type, r, m),
    status,
    expected: f?.code === 'BANNER_MISMATCH'
      ? (expected || t('chkhist.anyResponse'))
      : (expected || (f?.code === 'HTTP_STATUS' ? '2xx/3xx' : null)),
    rcode: d.rcode ?? (type === 'dns' ? (str(r.error) || null) : null),
    record: d.record_type ?? r.record_type ?? m.record_type ?? null,
    loss: loss != null ? formatPercent(loss) : null,
    proxy_status: num(d.proxy_status) ?? (textProxy ? Number(textProxy[1]) : null),
    allowed: Array.isArray(d.allowed_ports) ? d.allowed_ports.join(', ') : (textAllowed ? textAllowed[1].trim() : null),
    got: got ? String(got) : (f?.code === 'BANNER_MISMATCH' ? t('chkhist.nothingReceived') : null),
    broken: num(d.broken) ?? num(r.broken_resources) ?? 0,
    timeouts: num(d.timeouts) ?? num(r.timeout_count) ?? 0,
    mixed: num(d.mixed) ?? num(r.mixed_content_count) ?? 0,
  }
}

/** Başarısız satırın kısa etiketi + neden / etkisi / ne yapmalı (kullanıcının dilinde). Sağlıklı satırda null. */
export function failureTexts(type, row, monitor, t) {
  const f = failureOf(type, row, monitor)
  if (!f) return null
  const p = failureParams(type, row, monitor, t, f)
  const k = `chkfail.${f.code}`
  const n = f.noText ? 'chkhist.noText' : k   // metinsiz eski satır: neden/etki/öneri "bilinmiyor"a göre
  return {
    code: f.code,
    legacy: f.legacy,
    noText: !!f.noText,
    phase: f.phase,
    detail: f.detail,
    tone: failureTone(f.code),
    short: interpolate(t(`${k}.short`), p),
    why: interpolate(t(`${n}.why`), p),
    effect: interpolate(t(`${n}.effect`), p),
    fix: interpolate(t(`${n}.fix`), p),
  }
}

const ms = (v) => (num(v) != null ? `${num(v)} ms` : null)
/** Sertifika kontrolünün aşamaları (`CertificateCheckerService` sözleşmesi) — `chkhist.stage.<aşama>` etiketleri. */
export const CERT_STAGES = ['dns', 'tcp-connect', 'proxy-connect', 'tls-handshake', 'cert-ok']
const listOf = (v) => {
  if (Array.isArray(v)) return v.map((x) => str(x)).filter(Boolean)
  if (typeof v === 'string') return v.split(',').map((x) => x.trim()).filter(Boolean)
  return []
}

/**
 * Ayrıntı satırları (Kv listesi) — yalnız KAYITTA olan değerler; eski satırda ayrıntı yoksa liste kısalır (uydurma yok).
 * @returns {Array<{key: string, value: string, mono?: boolean, tone?: 'bad'|'warn'}>}
 */
export function detailRows(type, row, monitor, t, f) {
  const r = row || {}
  const d = f?.detail || {}
  const out = []
  const push = (key, value, extra = {}) => {
    if (value == null || value === '' || (Array.isArray(value) && value.length === 0)) return
    out.push({ key, value: Array.isArray(value) ? value.join(', ') : String(value), ...extra })
  }
  if (f?.phase) push('phase', t(`chkfail.phase.${f.phase}`))
  push('target', targetOf(type, r, monitor, d), { mono: true })
  const via = d.via || r.via
  if (via === 'direct' || via === 'proxy') push('via', t(`chkhist.via.${via}`))
  push('resolvedIps', listOf(d.resolved_ips).length ? listOf(d.resolved_ips) : listOf(r.resolved_ips), { mono: true })
  if (type === 'port') push('protocol', d.protocol || monitor?.protocol)
  push('ipVersion', d.ip_version)
  push('family', d.family)
  const status = num(d.http_status) ?? num(r.http_status) ?? num(r.status_code)
  if (status != null) push('httpStatus', `HTTP ${status}`, { tone: status >= 400 ? 'bad' : null })
  if (d.expected) push('expected', d.expected)
  if (d.got) push('got', d.got, { mono: true })
  if (type === 'dns') {
    push('recordType', d.record_type || r.record_type)
    push('rcode', d.rcode || (str(r.error) && !/\s/.test(str(r.error)) ? str(r.error) : null), { mono: true })
  }
  if (type === 'ping') {
    const loss = num(d.packet_loss) ?? num(r.packet_loss)
    if (loss != null) push('packetLoss', formatPercent(loss), { tone: loss >= 100 ? 'bad' : loss > 0 ? 'warn' : null })
    push('packets', num(d.packets))
  }
  if (num(d.proxy_status) != null) push('proxyStatus', `HTTP ${num(d.proxy_status)}`, { tone: 'bad' })
  if (Array.isArray(d.allowed_ports)) push('allowedPorts', d.allowed_ports)
  if (type === 'page') {
    const b = num(d.broken) ?? num(r.broken_resources)
    const to = num(d.timeouts) ?? num(r.timeout_count)
    const mx = num(d.mixed) ?? num(r.mixed_content_count)
    if (b) push('broken', b, { tone: 'bad' })
    if (to) push('timeouts', to, { tone: 'warn' })
    if (mx) push('mixed', mx, { tone: 'warn' })
    push('totalResources', num(d.total_resources) ?? num(r.total_resources))
    if ((num(d.pages_crawled) ?? 0) > 1) push('pages', num(d.pages_crawled))
  }
  if (type === 'pagespeed') {
    push('dnsMs', ms(r.dns_ms))
    push('connectMs', ms(r.connect_ms))
    push('tlsMs', ms(r.tls_ms))
    if (num(r.ttfb_ms) > 0) push('ttfbMs', ms(r.ttfb_ms))
  }
  const resp = type === 'ping' ? num(r.rtt_ms) : num(r.response_ms)
  if (resp != null && resp > 0) push('responseMs', `${resp} ms`)
  push('timeoutMs', ms(d.timeout_ms))
  if (num(d.max_redirects) != null) push('maxRedirects', num(d.max_redirects))
  if (type === 'domain') {
    push('source', d.source || r.source)
    if (typeof d.registry_rdap === 'boolean') push('registryRdap', t(d.registry_rdap ? 'chkhist.registryYes' : 'chkhist.registryNo'))
    push('whoisError', d.whois_error, { mono: true })
  }
  if (type === 'cert') {
    // Hata sınıfı (`error_class`) sertifika ayrıntısının kendi alan listesinde zaten var — burada tekrarlanmaz.
    const stage = str(r.error_stage)
    if (stage) push('errorStage', CERT_STAGES.includes(stage) ? t(`chkhist.stage.${stage}`) : stage)
  }
  push('exception', d.exception, { mono: true })
  return out
}

/** Teknik ayrıntı metni (kopyalanabilir): ham hata + ayrıntıdaki ileti / ping çıktısı / istisna zinciri. Yoksa ''. */
export function technicalText(type, row, f) {
  const r = row || {}
  const d = f?.detail || {}
  const lines = []
  const add = (s) => { const v = str(s); if (v && !lines.includes(v)) lines.push(v) }
  add(type === 'pagespeed' ? r.error_message : r.error)
  add(d.message)
  add(d.output)
  if (Array.isArray(d.cause_chain)) d.cause_chain.forEach(add)
  if (d.whois_error) add(`WHOIS: ${d.whois_error}`)
  return lines.join('\n')
}

/** Kartta / hücrede tek satırlık özet için: kısa etiket + ilk satır neden. */
export function summaryOf(type, row, monitor, t) {
  const x = failureTexts(type, row, monitor, t)
  return x ? { code: x.code, tone: x.tone, short: x.short, line: firstLine(x.why) } : null
}
