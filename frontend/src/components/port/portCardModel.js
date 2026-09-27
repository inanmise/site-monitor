/**
 * Port izleme KARTI modeli — saf yardımcılar (React yok; kart ve testleri paylaşır).
 *
 * <p>Satır alanları (GET /monitoring/port → MonitoringController.enrichPort): `host`, `port`, `protocol` TCP|TLS|HTTP|
 * BANNER|UDP, `send_data` (HTTP türünde denetlenen yol; BANNER/UDP'de gönderilen veri), `expect` (HTTP'de beklenen durum
 * kalıbı, BANNER'da beklenen alt dize), `ip_version` auto|v4|v6, `timeout_ms`, `interval_seconds`,
 * `slow_response_enabled` + `slow_threshold_ms`, `standalone` (true = Port sayfasından eklendi; null/false = envanterden
 * türetildi), `use_proxy` + `proxy_effective`; son kontrol: `status` open|closed|unknown, `response_ms` (başlangıçtan
 * sonuca geçen süre — ad çözümleme + bağlantı [+ el sıkışma / istek]), `error` (Java istisna metni ya da sunucunun TR
 * metni), `checked_at`.
 *
 * <p>Satırda OLMAYANLAR (kart uydurmaz — API boşlukları): kontrolün `detail`'i (TLS sürümü / HTTP kodu / banner; yalnız
 * test ucunda döner, `port_checks` tablosunda sütunu yok), çözümlenen IP, sertifika bitiş günü.
 */
import { WELL_KNOWN_PORTS } from '../ui/PortEndpoint.jsx'

const num = (v) => {
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/** Kontrol türü (büyük harf); tanımsızsa sunucunun varsayılanı TCP. */
export function protocolOf(m) {
  return String(m?.protocol || 'TCP').trim().toUpperCase() || 'TCP'
}

/**
 * Bilinen portun YAYGIN hizmet adı ("5432 → PostgreSQL") — yalnız ipucu: sunucudaki gerçek hizmeti iddia etmez (kart
 * "usually …" diye yazar). Eşleşme yoksa null (uydurulmaz). Harita tek kopya: ui/PortEndpoint (detay başlığı da kullanır).
 */
export function serviceName(port) {
  const n = num(port)
  return n != null && Object.prototype.hasOwnProperty.call(WELL_KNOWN_PORTS, n) ? WELL_KNOWN_PORTS[n] : null
}

/** Kopyalanacak/okunacak uç nokta: IPv6 değişmezi köşeli parantezle (`[2001:db8::1]:443`), diğerleri `host:port`. */
export function endpointText(host, port) {
  const h = String(host ?? '').trim()
  const bracket = h.includes(':') && !h.startsWith('[')
  return `${bracket ? `[${h}]` : h}:${port ?? ''}`
}

/** Kaynak: bağımsız (Port sayfasından eklendi) ya da envanterden türetilmiş (sunucu `standalone` null/false yazar). */
export function sourceOf(m) {
  return m?.standalone === true ? 'standalone' : 'inventory'
}

// PortCheckerService / SsrfGuard / NetworkResolver hata metinleri (Java istisnaları + sunucunun TR metinleri).
const PROXY_RE = /vekil tüneli reddetti|proxy (?:tunnel )?refused/i
const BLOCKED_RE = /izin verilmeyen hedef|boş hedef host|not allowed target/i
const DNS_RE = /çözümlenemeyen host|unresolved ?address|unknown ?host|name or service not known|nodename nor servname|no address associated|could not resolve|temporary failure in name resolution/i
const FAMILY_RE = /no ip ?v([46]) address/i
const HTTP_RE = /^HTTP (\d{3})(?:\s*\((?:beklenen|expected):\s*([^)]*)\))?/i
const BANNER_RE = /beklenen yan[ıi]t yok:\s*'([^']*)'\s*\(gelen:\s*([\s\S]*)\)\s*$/i
const BANNER_NONE_RE = /banner al[ıi]namad[ıi]|no banner/i
const UDP_UNREACHABLE_RE = /icmp unreachable|port eri[sş]ilemez/i
const TIMEOUT_RE = /timed? ?out|timeout|zaman aşımı|süre tavanını aştı/i
const REFUSED_RE = /connection refused|bağlantı reddedildi|connectexception/i
const UNREACHABLE_RE = /no route to host|network is unreachable|host is unreachable|unreachable/i
const TLS_RE = /\bssl|\btls|pkix|certificate|handshake|sertifika/i

const firstLine = (s) => String(s).split(/\r?\n/)[0].trim()

/**
 * Son kontrolün SONUCU — kartın kalbi. `{ kind, tone, proto, ...ayrıntı }`; `tone` ok | bad | neutral.
 * `kind`:
 * - `open` — kontrol geçti (TCP bağlandı / TLS el sıkıştı / HTTP beklenen kod / banner eşleşti / UDP yanıt verdi).
 * - `refused` — host yanıt verdi ama portta dinleyen yok (TCP RST; UDP'de ICMP port unreachable → `udp: true`).
 * - `filtered` — zaman aşımı: yanıt yok, büyük olasılıkla güvenlik duvarı düşürüyor (UDP'de açık/filtreli ayırt edilemez).
 * - `dns` — ad çözümlenemedi (`family`: 'v4'|'v6' → host'un o ailede adresi yok).
 * - `unreachable` — yol yok · `blocked` — izleme politikası hedefi engelledi (SSRF kalkanı) · `proxy` — vekil tünel açmadı.
 * - `tls` — el sıkışma hatası (`detail`) · `http` — beklenmeyen durum kodu (`code`, `expected`) ·
 *   `banner` — beklenen yanıt yok (`expected`, `got`; hiç yanıt yoksa ikisi de null).
 * - `error` — sınıflanamayan sunucu metni (`detail` ilk satır) · `closed` — metinsiz kapalı.
 * - `pending` — hiç kontrol yok · `unknown` — kontrol var ama sonuç bilinmiyor.
 * Sağlıklı mı kararı SUNUCUNUNDUR (`status`); model yalnız adlandırır.
 */
export function portResult(m) {
  const proto = protocolOf(m)
  const status = m?.status
  if (status === 'open') return { kind: 'open', tone: 'ok', proto }
  if (status !== 'closed') return { kind: m?.checked_at ? 'unknown' : 'pending', tone: 'neutral', proto }
  const bad = (kind, extra = {}) => ({ kind, tone: 'bad', proto, ...extra })
  const err = String(m?.error || '').trim()
  if (!err) return bad('closed')
  if (PROXY_RE.test(err)) return bad('proxy')
  if (BLOCKED_RE.test(err)) return bad('blocked')
  if (DNS_RE.test(err)) return bad('dns', { family: null })
  const fam = FAMILY_RE.exec(err)
  if (fam) return bad('dns', { family: `v${fam[1]}` })
  const http = HTTP_RE.exec(err)
  if (http) return bad('http', { code: Number(http[1]), expected: (http[2] || '').trim() || m?.expect || null })
  const banner = BANNER_RE.exec(err)
  if (banner) return bad('banner', { expected: banner[1], got: banner[2].trim() === 'bos' ? '' : banner[2].trim() })
  if (BANNER_NONE_RE.test(err)) return bad('banner', { expected: null, got: null })
  if (UDP_UNREACHABLE_RE.test(err)) return bad('refused', { udp: true })
  if (TIMEOUT_RE.test(err)) return bad('filtered', { udp: proto === 'UDP' })
  if (REFUSED_RE.test(err)) return bad('refused', { udp: false })
  if (UNREACHABLE_RE.test(err)) return bad('unreachable')
  if (TLS_RE.test(err)) return bad('tls', { detail: firstLine(err) })
  return bad('error', { detail: firstLine(err) })
}

/**
 * Süre kutusunun değerlendirmesi: `{ tone, limit, timedOut, noConnection, delta }`.
 * - zaman aşımında (filtreli) `bad` + `timedOut`; bağlantı hiç kurulamadıysa (süre yok, kapalı) `bad` + `noConnection`.
 * - yavaşlık alarmı açıksa eşik izlemenin kendi `slow_threshold_ms`'i: üstü `warn`, altı `ok` (sunucu kuralıyla aynı).
 * - kapalıysa `neutral` (eşik uydurulmaz) — alt satır 24 sa ortalamasına göre sapmayı (`delta`, %) söyler.
 * `baseline` = pingCardModel.latencyBaseline(spark) (saatlik ortalamalar; port trendi de `response_ms`'ten).
 */
export function connectAssessment(m, result, baseline) {
  const ms = num(m?.response_ms)
  const limit = m?.slow_response_enabled && num(m?.slow_threshold_ms) > 0 ? num(m.slow_threshold_ms) : null
  const delta = ms != null && baseline && baseline.avg > 0 ? Math.round(((ms - baseline.avg) / baseline.avg) * 100) : null
  if (result?.kind === 'filtered') return { tone: 'bad', limit: null, timedOut: true, noConnection: false, delta: null }
  if (ms == null) {
    const closed = result?.tone === 'bad'
    return { tone: closed ? 'bad' : 'neutral', limit, timedOut: false, noConnection: closed, delta: null }
  }
  if (limit == null) return { tone: 'neutral', limit, timedOut: false, noConnection: false, delta }
  return { tone: ms > limit ? 'warn' : 'ok', limit, timedOut: false, noConnection: false, delta }
}

/** HTTP türünde beklenen kod kalıbı boşsa sunucunun varsayılanı (2xx/3xx) — kart böyle yazar, uydurmaz. */
export const HTTP_DEFAULT_EXPECT = '2xx/3xx'

/** Uzun banner/hata parçasını kart satırına sığacak boya kırpar (tamamı detay penceresinin Kontrol Geçmişi'nde). */
export function clip(s, max = 40) {
  const v = String(s ?? '')
  return v.length > max ? `${v.slice(0, max - 1)}…` : v
}
