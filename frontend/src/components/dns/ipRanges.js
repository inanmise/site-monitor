/**
 * Sayısal IP adresi ve CIDR aralığı yardımcıları — backend `service/dns/IpRanges` + `DnsExpectedValues` ikizi (2026-11-18).
 *
 * <p>Saf, DNS sorgusu yok, doğrusal, girdi ≤ {@link MAX_TEXT} karakter. Kurallar Java ile birebir; tek doğruluk tablosu
 * `IpRangesTest.MATCH_TABLE` (JS testi Java kaynağından okur):
 * - IPv4 yalnız dört bölümlü ondalık (baştaki sıfır yok), IPv6 standart yazımlar (`::`, gömülü IPv4), kapsam kimliği yok;
 * - IPv4'e eşlenmiş IPv6 (`::ffff:a.b.c.d`) IPv4 sayılır, IPv4 ↔ IPv6 eşleşmez;
 * - aralık `adres/önek`, önek 0–32 / 0–128; ağ adresinin önek sonrası bitleri yok sayılır.
 */

export const MAX_TEXT = 64
export const MIN_PREFIX_V4 = 8
export const MIN_PREFIX_V6 = 16

export const CODE_INVALID = 'CIDR_INVALID'
export const CODE_FAMILY = 'CIDR_FAMILY'
export const CODE_TOO_BROAD = 'CIDR_TOO_BROAD'
export const CODE_RECORD_TYPE = 'CIDR_RECORD_TYPE'

const HEX = /^[0-9a-fA-F]{1,4}$/

/** Dört bölümlü ondalık IPv4 → 4 bayt; değilse null. */
function v4Bytes(t) {
  const parts = t.split('.')
  if (parts.length !== 4) return null
  const out = []
  for (const p of parts) {
    if (p.length < 1 || p.length > 3 || !/^[0-9]+$/.test(p)) return null
    if (p.length > 1 && p[0] === '0') return null
    const n = Number(p)
    if (n > 255) return null
    out.push(n)
  }
  return out
}

/** IPv6 metni → 16 bayt; değilse null (`::` en çok bir kez, gömülü IPv4 yalnız sonda). */
function v6Bytes(t) {
  if (t.indexOf('%') >= 0) return null
  const dbl = t.indexOf('::')
  if (dbl >= 0 && t.indexOf('::', dbl + 1) >= 0) return null
  const head = dbl >= 0 ? t.slice(0, dbl) : t
  const tail = dbl >= 0 ? t.slice(dbl + 2) : ''
  const parse = (s) => {
    if (s === '') return []
    const groups = s.split(':')
    const words = []
    for (let i = 0; i < groups.length; i++) {
      const g = groups[i]
      if (i === groups.length - 1 && g.indexOf('.') >= 0) {
        const b = v4Bytes(g)
        if (!b) return null
        words.push((b[0] << 8) | b[1], (b[2] << 8) | b[3])
      } else {
        if (!HEX.test(g)) return null
        words.push(parseInt(g, 16))
      }
    }
    return words
  }
  const h = parse(head)
  const tl = parse(tail)
  if (!h || !tl) return null
  // Gömülü IPv4 yalnız son grupta: baş kısımda '.' varsa ve ardından '::' geliyorsa geçersiz
  if (dbl >= 0 && head.indexOf('.') >= 0) return null
  const total = h.length + tl.length
  if (dbl >= 0 ? total > 7 : total !== 8) return null
  const words = dbl >= 0 ? [...h, ...new Array(8 - total).fill(0), ...tl] : h
  const out = []
  for (const w of words) out.push((w >> 8) & 0xff, w & 0xff)
  return out
}

/** Sayısal adres → bayt dizisi (IPv4 4, IPv6 16; IPv4'e eşlenmiş IPv6 → 4). Geçersiz / ad → null. */
export function ipAddress(s) {
  if (s == null) return null
  let t = String(s).trim()
  if (!t || t.length > MAX_TEXT) return null
  if (t.startsWith('[') && t.endsWith(']')) t = t.slice(1, -1)
  if (!/^[0-9a-fA-F.:]+$/.test(t)) return null
  if (t.indexOf(':') < 0) return v4Bytes(t)
  const b = v6Bytes(t)
  if (!b) return null
  const mapped = b.slice(0, 10).every((x) => x === 0) && b[10] === 0xff && b[11] === 0xff
  return mapped ? b.slice(12) : b
}

/** `adres/önek` → { network, prefix }; önek yoksa ya da geçersizse null. */
export function parseRange(s) {
  if (s == null) return null
  const t = String(s).trim()
  if (!t || t.length > MAX_TEXT) return null
  const slash = t.indexOf('/')
  if (slash <= 0 || slash !== t.lastIndexOf('/')) return null
  const p = t.slice(slash + 1)
  if (!/^[0-9]{1,3}$/.test(p)) return null
  const network = ipAddress(t.slice(0, slash))
  if (!network) return null
  const prefix = Number(p)
  if (prefix > network.length * 8) return null
  return { network, prefix }
}

/** `address` (bayt dizisi) `range` içinde mi? Aile farklıysa false. */
export function rangeContains(range, address) {
  if (!range || !address || address.length !== range.network.length) return false
  const full = Math.floor(range.prefix / 8)
  for (let i = 0; i < full; i++) if (range.network[i] !== address[i]) return false
  const rest = range.prefix % 8
  if (rest === 0) return true
  const mask = (0xff << (8 - rest)) & 0xff
  return (range.network[full] & mask) === (address[full] & mask)
}

/** Metin aralığı + metin adres (Java `IpRanges.contains`). */
export function contains(range, value) {
  return rangeContains(parseRange(range), ipAddress(value))
}

/** İki metin aynı adresi mi gösteriyor (farklı yazım dahil)? */
export function sameAddress(a, b) {
  const x = ipAddress(a)
  const y = ipAddress(b)
  return !!x && !!y && x.length === y.length && x.every((v, i) => v === y[i])
}

function addressType(recordType) {
  const t = String(recordType || '').trim().toUpperCase()
  return t === 'A' || t === 'AAAA'
}

/**
 * Beklenen-değer listesinin ilk aralık sorunu (Java `DnsExpectedValues.check` ikizi) → { code, line } | null.
 * Değer satırları doğrulanmaz; A / AAAA'da `/` içeren satır geçerli + doğru aileden + yeterince dar aralık olmalı;
 * başka kayıt tipinde aralık gibi okunan satır reddedilir.
 */
export function expectedValueProblem(joined, recordType) {
  if (joined == null || !String(joined).trim()) return null
  const type = String(recordType || '').trim().toUpperCase()
  const address = addressType(type)
  for (const raw of String(joined).split('\n')) {
    const line = raw.trim()
    if (!line) continue
    if (!address) {
      if (parseRange(line)) return { code: CODE_RECORD_TYPE, line }
      continue
    }
    if (line.indexOf('/') < 0) continue
    const r = parseRange(line)
    if (!r) return { code: CODE_INVALID, line }
    const v4 = r.network.length === 4
    if (v4 !== (type === 'A')) return { code: CODE_FAMILY, line }
    if (r.prefix < (v4 ? MIN_PREFIX_V4 : MIN_PREFIX_V6)) return { code: CODE_TOO_BROAD, line }
  }
  return null
}

/** Sorun → çevrilmiş metin (sunucunun iletisiyle aynı anlam; satır 80 karakterde kısaltılır). */
export function expectedValueProblemText(problem, t) {
  if (!problem) return null
  const line = problem.line.length > 80 ? `${problem.line.slice(0, 80)}…` : problem.line
  switch (problem.code) {
    case CODE_FAMILY: return t('dns.cidrFamily', line)
    case CODE_TOO_BROAD: return t('dns.cidrTooBroad', line, MIN_PREFIX_V4, MIN_PREFIX_V6)
    case CODE_RECORD_TYPE: return t('dns.cidrRecordType', line)
    default: return t('dns.cidrInvalid', line)
  }
}
