import { toUtc } from '../../utils/localDay.js'
import { certTone, TONES, sigShort } from '../certcard/certCardModel.js'
import { parseDn, prettyTls } from './sslModel.js'

/**
 * Sertifika penceresi → "Sertifika Detayları" sekmesinin SAF modeli (2026-09-28 shadcn yeniden tasarım). React yok:
 * geçerlilik zaman çizelgesi, göreli süre, okunur onaltılık gruplar, anahtar kullanımı adları, SAN eşleşmesi, durum
 * etiketleri ve güvenli bağlantı kararı burada — panel ve testler aynı sözlüğü paylaşır.
 *
 * <p><b>Veri.</b> `d` = `/api/history/{domain}` `data[0]` (CertificateDto, snake_case; zamanlar UTC ve `Z`siz) ya da
 * önizlemede `/api/check-preview/{domain}` yanıtı. Sunucu eksik CN/O için `"Unknown"` yazar (CertificateCheckerService
 * `extractCn`/`extractField`) — bu yer tutucu değer DEĞİLDİR, boş sayılır.
 *
 * <p><b>Kural YAZMAZ.</b> Ton sunucunun `alert_level`'ından (certCardModel.certTone); güven/zincir/iptal/dağıtım
 * etiketleri sunucunun ölçtüğü durum kodlarının adıdır. Joker eşleşmesi yalnız VURGU içindir ve RFC 6125'e göre
 * dardır (`*.example.com` yalnız TEK etiketi kapsar) — kapsama hükmü sunucunun `security_flags`'ındadır.
 */

export const DAY_MS = 86400000
/** Bu sayının ÜSTÜNDE SAN listesi daraltılmış açılır ve arama kutusu çıkar. */
export const SAN_COLLAPSE = 12

const hasNum = (v) => typeof v === 'number' && Number.isFinite(v)

/** ISO (UTC, `Z`siz olabilir) → epoch ms; boş/bozuk → null. */
export function toMs(iso) {
  if (!iso) return null
  const v = Date.parse(toUtc(String(iso)))
  return Number.isFinite(v) ? v : null
}

/** Boş dize ve sunucunun `"Unknown"` yer tutucusu → null; diğerleri kırpılmış metin. */
export function clean(v) {
  if (v == null) return null
  const s = String(v).trim()
  return s && s !== 'Unknown' ? s : null
}

/** Pencerenin verdiği ton (başlık rozetiyle aynı) geçerliyse o; yoksa kartın kuralı. */
export function detailTone(d, tone) {
  return tone && TONES.includes(tone) ? tone : certTone(d)
}

/**
 * Geçerlilik zaman çizelgesi. Tarihler yoksa/bozuksa null (çubuk çizilmez).
 * Kalan gün SUNUCUNUN değeridir (başlık rozeti ve kartla aynı sayı); yoksa saatten hesaplanır. `usedPct` o sayıyla
 * tutarlı: toplam − kalan (0..toplam'a kırpılmış) / toplam.
 */
export function lifetimeOf(d, now = Date.now()) {
  const startMs = toMs(d?.not_before)
  const endMs = toMs(d?.not_after)
  if (startMs == null || endMs == null || endMs <= startMs) return null
  const totalDays = Math.max(1, Math.round((endMs - startMs) / DAY_MS))
  const remainingDays = hasNum(d?.days_remaining) ? d.days_remaining : Math.floor((endMs - now) / DAY_MS)
  const notYetValid = now < startMs
  const used = totalDays - Math.min(totalDays, Math.max(0, remainingDays))
  const usedPct = notYetValid ? 0 : Math.round(Math.max(0, Math.min(100, (used / totalDays) * 100)))
  return { startMs, endMs, totalDays, remainingDays, usedPct, notYetValid, expired: remainingDays < 0 }
}

/** Göreli süre birimi: < 1 gün "bugün", < 45 gün gün, < 2 yıl ay, üstü yıl (tek ondalık). */
export function relativeUnit(ms, now = Date.now()) {
  const days = (ms - now) / DAY_MS
  const abs = Math.abs(days)
  if (abs < 1) return { value: 0, unit: 'day' }
  if (abs < 45) return { value: Math.round(days), unit: 'day' }
  if (abs < 730) return { value: Math.round(days / 30.4375), unit: 'month' }
  return { value: Math.round((days / 365.25) * 10) / 10, unit: 'year' }
}

/** "7 ay önce" / "in 6 months" — Intl.RelativeTimeFormat (yerel `dateLocale()`'den); tarih yoksa null. */
export function formatRelative(iso, locale, now = Date.now()) {
  const ms = toMs(iso)
  if (ms == null) return null
  const { value, unit } = relativeUnit(ms, now)
  try {
    return new Intl.RelativeTimeFormat(locale, { numeric: 'auto' }).format(value, unit)
  } catch {
    return null
  }
}

/**
 * Onaltılık değer (parmak izi, seri no) → okunur gruplar: "AB:12:CD:34", "EF:56:78:9A", … (grup başına `perGroup`
 * bayt). Tek hane sayılı değer baştan sıfırla tamamlanır (bayt gösterimi). Onaltılık değilse ya da çok kısaysa değer
 * TEK grup olarak döner. Yalnız GÖSTERİM içindir — kopyalanan değer her zaman sunucunun ham metnidir.
 */
export function hexGroups(value, perGroup = 4) {
  const s = String(value ?? '').trim()
  if (!s) return []
  const hex = s.replace(/[\s:.-]/g, '')
  if (hex.length < 8 || !/^[0-9a-fA-F]+$/.test(hex)) return [s]
  const pairs = (hex.length % 2 ? `0${hex}` : hex).toUpperCase().match(/.{2}/g)
  const out = []
  for (let i = 0; i < pairs.length; i += perGroup) out.push(pairs.slice(i, i + perGroup).join(':'))
  return out
}

// ── Anahtar kullanımı ────────────────────────────────────────────────────────────────────────────────────────────
// Sunucu KeyUsage bitlerini İngilizce adla ("Digital Signature"), EKU'yu bilinen OID'ler için adla ("TLS Web Server"),
// bilinmeyen için ham OID ile yazar. RFC 5280 adları ve OID'ler de tanınır (eski kayıt / önizleme / başka kaynak).
const KU = {
  digitalsignature: 'digitalSignature', nonrepudiation: 'nonRepudiation', contentcommitment: 'nonRepudiation',
  keyencipherment: 'keyEncipherment', dataencipherment: 'dataEncipherment', keyagreement: 'keyAgreement',
  certificatesigning: 'keyCertSign', keycertsign: 'keyCertSign', certsign: 'keyCertSign',
  crlsigning: 'cRLSign', crlsign: 'cRLSign', encipheronly: 'encipherOnly', decipheronly: 'decipherOnly',
}
const EKU = {
  tlswebserver: 'serverAuth', tlswebserverauthentication: 'serverAuth', serverauth: 'serverAuth', '1.3.6.1.5.5.7.3.1': 'serverAuth',
  tlswebclient: 'clientAuth', tlswebclientauthentication: 'clientAuth', clientauth: 'clientAuth', '1.3.6.1.5.5.7.3.2': 'clientAuth',
  codesigning: 'codeSigning', '1.3.6.1.5.5.7.3.3': 'codeSigning',
  emailprotection: 'emailProtection', '1.3.6.1.5.5.7.3.4': 'emailProtection',
  timestamping: 'timeStamping', '1.3.6.1.5.5.7.3.8': 'timeStamping',
  ocspsigning: 'ocspSigning', '1.3.6.1.5.5.7.3.9': 'ocspSigning',
  anyextendedkeyusage: 'anyEku', '2.5.29.37.0': 'anyEku',
}
/** Bilinen kullanım kimlikleri (i18n: `cdp.ku.<id>` / `cdp.eku.<id>`). */
export const KU_IDS = ['digitalSignature', 'nonRepudiation', 'keyEncipherment', 'dataEncipherment', 'keyAgreement', 'keyCertSign', 'cRLSign', 'encipherOnly', 'decipherOnly']
export const EKU_IDS = ['serverAuth', 'clientAuth', 'codeSigning', 'emailProtection', 'timeStamping', 'ocspSigning', 'anyEku']

function usageKey(raw) {
  const s = String(raw).trim()
  return /^\d+(\.\d+)+$/.test(s) ? s : s.toLowerCase().replace(/[^a-z0-9]/g, '')
}

/** Kullanım listesi → `[{ raw, id }]` (`id` null = tanınmadı, ham gösterilir); boş/yinelenen atılır. */
export function usageItems(list, kind = 'ku') {
  if (!Array.isArray(list)) return []
  const map = kind === 'eku' ? EKU : KU
  const seen = new Set()
  const out = []
  for (const v of list) {
    const raw = clean(v)
    if (!raw || seen.has(raw)) continue
    seen.add(raw)
    out.push({ raw, id: map[usageKey(raw)] ?? null })
  }
  return out
}

// ── SAN ──────────────────────────────────────────────────────────────────────────────────────────────────────────
const host = (v) => String(v ?? '').trim().toLowerCase().replace(/\.$/, '')

/** Ad bu alan adını kapsıyor mu: 'exact' | 'wildcard' | null. Joker yalnız en soldaki TEK etiketi kapsar. */
export function sanCovers(name, domain) {
  const n = host(name)
  const h = host(domain)
  if (!n || !h) return null
  if (n === h) return 'exact'
  if (n.startsWith('*.')) {
    const suffix = n.slice(1)
    if (h.length > suffix.length && h.endsWith(suffix)) {
      const label = h.slice(0, h.length - suffix.length)
      if (label && !label.includes('.')) return 'wildcard'
    }
  }
  return null
}

/**
 * SAN girdileri: `[{ name, wildcard, match }]`. Alan adını kapsayan girdi(ler) EN ÖNE alınır (birebir önce, sonra
 * joker) — daraltılmış listede de görünsün; diğerleri sunucunun sırasında. Boş girdiler atılır.
 */
export function sanEntries(san, domain) {
  if (!Array.isArray(san)) return []
  const rows = san.map(clean).filter(Boolean).map((name) => ({ name, wildcard: name.startsWith('*.'), match: sanCovers(name, domain) }))
  const rank = (r) => (r.match === 'exact' ? 0 : r.match === 'wildcard' ? 1 : 2)
  return rows.map((r, i) => ({ r, i })).sort((a, b) => rank(a.r) - rank(b.r) || a.i - b.i).map((x) => x.r)
}

/** Harf duyarsız arama; boş sorgu = hepsi. */
export function filterSan(entries, query) {
  const q = String(query ?? '').trim().toLowerCase()
  if (!q) return entries
  return entries.filter((e) => e.name.toLowerCase().includes(q))
}

// ── Durum kodları ────────────────────────────────────────────────────────────────────────────────────────────────
// Kod → ton (certcard CHIP_TONE anahtarları). Sunucu değerleri: trust TRUSTED|UNTRUSTED|UNKNOWN, chain VALID|BROKEN|
// REVOKED|UNKNOWN (+ eski INCOMPLETE), revocation VALID|REVOKED|UNKNOWN, deployment OK|INCOMPLETE|UNKNOWN (+ eski MISMATCH).
const STATUS_TONES = {
  trust: { TRUSTED: 'ok', UNTRUSTED: 'bad', UNKNOWN: 'muted' },
  chain: { VALID: 'ok', BROKEN: 'bad', REVOKED: 'bad', INCOMPLETE: 'high', UNKNOWN: 'muted' },
  rev: { VALID: 'ok', REVOKED: 'bad', UNKNOWN: 'muted' },
  dep: { OK: 'ok', INCOMPLETE: 'high', MISMATCH: 'high', UNKNOWN: 'muted' },
}

/** Güven kodu: sunucunun `trust_status`'u; yoksa `security_flags` içindeki UNTRUSTED_CA. */
export function trustCode(d) {
  const ts = clean(d?.trust_status)
  if (ts) return ts.toUpperCase()
  return Array.isArray(d?.security_flags) && d.security_flags.includes('UNTRUSTED_CA') ? 'UNTRUSTED' : null
}

/**
 * Durum çipi: `{ kind, code, tone, labelKey }`; değer yoksa null. Tanınmayan kod `labelKey: null` ile döner (ham
 * gösterilir — bilgi kaybolmaz).
 */
export function statusChip(kind, value) {
  const code = clean(value)?.toUpperCase()
  if (!code) return null
  const tone = STATUS_TONES[kind]?.[code]
  return { kind, code, tone: tone ?? 'muted', labelKey: tone ? `cdp.${kind}.${code}` : null }
}

/** Güven + zincir + iptal + dağıtım çipleri ve özet ton: bad > high > ok; bilinen hiç yoksa muted. */
export function trustSummary(d) {
  const chips = [
    statusChip('trust', trustCode(d)),
    statusChip('chain', d?.chain_status),
    statusChip('rev', d?.revocation_status),
    statusChip('dep', d?.deployment_status),
  ].filter(Boolean)
  let tone = 'muted'
  if (chips.some((c) => c.tone === 'bad')) tone = 'bad'
  else if (chips.some((c) => c.tone === 'high')) tone = 'high'
  else if (chips.some((c) => c.tone === 'ok')) tone = 'ok'
  return { tone, chips }
}

/** Alan adı kapsaması rozeti: sunucu HOSTNAME_MISMATCH dediyse fail; SAN'da kapsayan ad varsa ok; yoksa null. */
export function hostCoverage(d, entries) {
  if (Array.isArray(d?.security_flags) && d.security_flags.includes('HOSTNAME_MISMATCH')) return 'fail'
  return entries.some((e) => e.match) ? 'ok' : null
}

// ── Kimlik / anahtar ─────────────────────────────────────────────────────────────────────────────────────────────
/** Veren: CN (issuer_cn → DN CN → issuer) + kuruluş (issuer = sunucunun O'su → DN O). */
export function issuerOf(d) {
  const dn = parseDn(d?.issuer_dn)
  return { cn: clean(d?.issuer_cn) || clean(dn.CN) || clean(d?.issuer), org: clean(d?.issuer) || clean(dn.O) }
}

/** Konu: CN (subject → DN CN) + kuruluş (DN O). */
export function subjectOf(d) {
  const dn = parseDn(d?.subject_dn)
  return { cn: clean(d?.subject) || clean(dn.CN), org: clean(dn.O) }
}

/** Anahtar: algoritma, boyut (> 0), "RSA 2048" etiketi, imza algoritması ve kısa adı ("SHA-256"). */
export function keyInfo(d) {
  const alg = clean(d?.public_key_algorithm)
  const size = hasNum(d?.public_key_size) && d.public_key_size > 0 ? d.public_key_size : null
  const sig = clean(d?.signature_algorithm)
  return { alg, size, label: alg ? `${alg}${size ? ` ${size}` : ''}` : null, sig, sigShort: sig ? sigShort(sig) : null }
}

/**
 * Bağlantı (TLS) bilgisi (2026-09-28): el sıkışmasındaki sürüm (okunur "TLS 1.2") + şifre takımı + zayıflık hükmü.
 * Hüküm SUNUCUDAN — /history'de `tls_assessment`, önizlemede `assessment` (aynı anahtarlar; CertificateHealthRules: protokol
 * FAIL = TLS 1.1 ve altı ya da SSL, şifre FAIL = 3DES/RC4/NULL/EXPORT…). Burada kural YAZILMAZ: hüküm yoksa rozet de yok.
 */
export function tlsInfo(d) {
  const version = clean(d?.tls_version)
  const cipher = clean(d?.cipher_suite)
  const a = (d?.tls_assessment && typeof d.tls_assessment === 'object' ? d.tls_assessment : d?.assessment) || {}
  const fail = (v) => String(v ?? '').trim().toUpperCase() === 'FAIL'
  return {
    version,
    versionLabel: version ? prettyTls(version) : null,
    cipher,
    weakProtocol: !!version && fail(a.protocol),
    weakCipher: !!cipher && fail(a.cipher),
  }
}

/** Yalnız http(s) adres bağlantı olur (javascript:, data:, göreli yol … düz metin kalır). */
export function safeHttpUrl(v) {
  const s = clean(v)
  if (!s) return null
  try {
    const u = new URL(s)
    return u.protocol === 'http:' || u.protocol === 'https:' ? u.href : null
  } catch {
    return null
  }
}

/** Değer boş mu (null, boş dize, "Unknown", boş dizi); boolean / sayı / nesne (ör. durum çipi) dolu sayılır. */
export function isBlank(v) {
  if (v == null) return true
  if (Array.isArray(v)) return v.length === 0
  if (typeof v === 'boolean' || typeof v === 'number' || typeof v === 'object') return false
  return clean(v) == null
}
