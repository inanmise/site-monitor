/**
 * Küçük BER/DER okuyucu + sertifika biçimi yardımcıları (2026-10-08, kullanıcı isteği: "keystore yüklemesinde özel anahtar
 * için kesinlikle bir yükleme yapmayalım"). Tarayıcıda çalışır; bağımlılığı yok. Yalnız OKUR — hiçbir şey kodlamaz,
 * hiçbir anahtarı çözmez. Belirsiz uzunluk (BER, Windows / Java PFX'leri) ve parçalı OCTET STRING desteklenir.
 *
 * <p>Düğüm: `{ cls, tag, constructed, start, contentStart, contentEnd, end, depth }`; çocuklar {@link children} ile tembel
 * okunur. Her okuma sınır denetimlidir; bozuk girdi {@link Asn1Error} fırlatır (çağıran yakalar).
 */

export class Asn1Error extends Error {
  constructor(msg) { super(`asn1: ${msg}`); this.name = 'Asn1Error' }
}

const MAX_DEPTH = 48

export const UNIVERSAL = 0
export const CONTEXT = 2
export const T = Object.freeze({ BOOLEAN: 1, INTEGER: 2, BIT_STRING: 3, OCTET_STRING: 4, NULL: 5, OID: 6, UTF8: 12, SEQUENCE: 16, SET: 17, PRINTABLE: 19, IA5: 22, UTC_TIME: 23, GEN_TIME: 24, BMP: 30 })

/** Tek TLV okur (`pos`ta başlayan). Belirsiz uzunlukta çocuklar hemen okunur (bitişi bulmak için). */
export function readTlv(buf, pos = 0, end = buf.length, depth = 0) {
  if (depth > MAX_DEPTH) throw new Asn1Error('too deep')
  if (pos + 2 > end) throw new Asn1Error('eof')
  const start = pos
  let b = buf[pos++]
  const cls = b >> 6
  const constructed = (b & 0x20) !== 0
  let tag = b & 0x1f
  if (tag === 0x1f) {
    tag = 0
    let n = 0
    do {
      if (pos >= end || ++n > 4) throw new Asn1Error('tag')
      b = buf[pos++]
      tag = (tag * 128) + (b & 0x7f)
    } while (b & 0x80)
  }
  if (pos >= end) throw new Asn1Error('eof')
  let len = buf[pos++]
  if (len === 0x80) {
    if (!constructed) throw new Asn1Error('indefinite primitive')
    const kids = []
    let p = pos
    for (;;) {
      if (p + 2 > end) throw new Asn1Error('eoc')
      if (buf[p] === 0 && buf[p + 1] === 0) { p += 2; break }
      const child = readTlv(buf, p, end, depth + 1)
      kids.push(child)
      p = child.end
    }
    return { cls, tag, constructed, start, contentStart: pos, contentEnd: p - 2, end: p, depth, kids }
  }
  if (len & 0x80) {
    const n = len & 0x7f
    if (n === 0 || n > 4 || pos + n > end) throw new Asn1Error('length')
    len = 0
    for (let i = 0; i < n; i++) len = (len * 256) + buf[pos++]
  }
  const contentEnd = pos + len
  if (contentEnd > end) throw new Asn1Error('overrun')
  return { cls, tag, constructed, start, contentStart: pos, contentEnd, end: contentEnd, depth, kids: null }
}

/** Yapılı düğümün çocukları (tembel, önbellekli). */
export function children(buf, node) {
  if (!node || !node.constructed) throw new Asn1Error('not constructed')
  if (node.kids) return node.kids
  const out = []
  let p = node.contentStart
  while (p < node.contentEnd) {
    const c = readTlv(buf, p, node.contentEnd, node.depth + 1)
    out.push(c)
    p = c.end
  }
  node.kids = out
  return out
}

export const is = (n, cls, tag) => !!n && n.cls === cls && n.tag === tag
export const isSeq = (n) => is(n, UNIVERSAL, T.SEQUENCE) && n.constructed
export const isSet = (n) => is(n, UNIVERSAL, T.SET) && n.constructed
export const isCtx = (n, tag) => is(n, CONTEXT, tag)

/** Düğümün TAM kodlaması (başlık dahil). */
export const raw = (buf, n) => buf.subarray(n.start, n.end)

/** OCTET STRING (ya da [n] IMPLICIT OCTET STRING) içeriği — parçalı (yapılı) gösterim birleştirilir. */
export function octets(buf, n) {
  if (!n.constructed) return buf.subarray(n.contentStart, n.contentEnd)
  const parts = children(buf, n).map((c) => octets(buf, c))
  return concat(parts)
}

export function oid(buf, n) {
  if (!is(n, UNIVERSAL, T.OID) || n.constructed) throw new Asn1Error('oid')
  const b = buf.subarray(n.contentStart, n.contentEnd)
  if (!b.length) throw new Asn1Error('oid')
  const out = []
  let v = 0
  for (let i = 0; i < b.length; i++) {
    v = (v * 128) + (b[i] & 0x7f)
    if (!(b[i] & 0x80)) {
      if (!out.length) {
        const first = v < 80 ? Math.floor(v / 40) : 2
        out.push(first, v - first * 40)
      } else out.push(v)
      v = 0
    }
  }
  return out.join('.')
}

/** Küçük, negatif olmayan INTEGER (yineleme sayısı, sürüm). 2^53 üstü / negatif → hata. */
export function int(buf, n) {
  if (!is(n, UNIVERSAL, T.INTEGER) || n.constructed) throw new Asn1Error('int')
  const b = buf.subarray(n.contentStart, n.contentEnd)
  if (!b.length || b.length > 7 || (b[0] & 0x80)) throw new Asn1Error('int range')
  let v = 0
  for (let i = 0; i < b.length; i++) v = (v * 256) + b[i]
  return v
}

/** BMPString (UTF-16BE) → metin. */
export function bmpString(buf, n) {
  const b = octets(buf, n)
  let s = ''
  for (let i = 0; i + 1 < b.length; i += 2) s += String.fromCharCode((b[i] << 8) | b[i + 1])
  return s
}

export function concat(parts) {
  let len = 0
  for (const p of parts) len += p.length
  const out = new Uint8Array(len)
  let o = 0
  for (const p of parts) { out.set(p, o); o += p.length }
  return out
}

export function equalBytes(a, b) {
  if (!a || !b || a.length !== b.length) return false
  let d = 0
  for (let i = 0; i < a.length; i++) d |= a[i] ^ b[i]
  return d === 0
}

export function toHex(b) {
  let s = ''
  for (let i = 0; i < b.length; i++) s += (b[i] < 16 ? '0' : '') + b[i].toString(16)
  return s.toUpperCase()
}

// ── Base64 / PEM ────────────────────────────────────────────────────────────────────────────────────────────────

const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
const B64_INDEX = (() => { const m = new Int16Array(128).fill(-1); for (let i = 0; i < 64; i++) m[B64.charCodeAt(i)] = i; return m })()

/** Standart Base64 (boşluklar yok sayılır). Geçersiz karakter → null. */
export function base64Decode(text) {
  const s = String(text ?? '')
  const out = new Uint8Array(Math.floor(s.length * 3 / 4) + 3)
  let o = 0
  let acc = 0
  let bits = 0
  let pad = false
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i)
    if (c === 0x3d) { pad = true; continue }                        // '='
    if (c === 0x20 || c === 0x0a || c === 0x0d || c === 0x09) continue
    if (pad) return null                                          // dolgudan sonra veri
    const v = c < 128 ? B64_INDEX[c] : -1
    if (v < 0) return null
    acc = (acc << 6) | v
    bits += 6
    if (bits >= 8) { bits -= 8; out[o++] = (acc >> bits) & 0xff }
  }
  return out.subarray(0, o)
}

export function base64Encode(bytes) {
  let s = ''
  let i = 0
  for (; i + 2 < bytes.length; i += 3) {
    const n = (bytes[i] << 16) | (bytes[i + 1] << 8) | bytes[i + 2]
    s += B64[n >> 18] + B64[(n >> 12) & 63] + B64[(n >> 6) & 63] + B64[n & 63]
  }
  if (i < bytes.length) {
    const n = (bytes[i] << 16) | ((i + 1 < bytes.length ? bytes[i + 1] : 0) << 8)
    s += B64[n >> 18] + B64[(n >> 12) & 63] + (i + 1 < bytes.length ? B64[(n >> 6) & 63] : '=') + '='
  }
  return s
}

/** DER → PEM (64 sütun). */
export function armor(label, der) {
  const b64 = base64Encode(der)
  const lines = []
  for (let i = 0; i < b64.length; i += 64) lines.push(b64.slice(i, i + 64))
  return `-----BEGIN ${label}-----\n${lines.join('\n')}\n-----END ${label}-----\n`
}

/**
 * PEM işaretleri (2026-10-09, sonsuz döngü / ReDoS denetimi): eski `BEGIN (…)-----([\s\S]*?)-----END \1-----` ifadesi
 * kapanışı olmayan HER BEGIN için metnin sonuna kadar tarıyordu (O(k·N); 5 MB'ta 290 000 BEGIN ≈ saatler) ve özel anahtar
 * sayacı uzun bir etiket dizisinde O(N²) geri izliyordu. Artık metin TEK geçişte işaretlere ayrılır, eşleştirme doğrusal.
 * Etiket ≤ 64 karakter (gerçek etiketler ≤ 25). İşaretler ortak tire paylaşabilir ("…-----BEGIN B-----" önceki işaretin
 * kapanış tirelerinden başlayabilir) — eski ifadenin bulduğu her konum burada da bulunur.
 */
const PEM_MARKER = /-----(BEGIN|END) ([A-Z0-9 .]{1,64})-----/g
const MARKER_DASHES = 5

/** `[{ begin: boolean, label, start, end }]` — konum sırasıyla, örtüşen (tire paylaşan) işaretler dahil. */
function pemMarkers(text) {
  const out = []
  PEM_MARKER.lastIndex = 0
  for (let m = PEM_MARKER.exec(text); m; m = PEM_MARKER.exec(text)) {
    const end = m.index + m[0].length
    out.push({ begin: m[1] === 'BEGIN', label: m[2], start: m.index, end })
    PEM_MARKER.lastIndex = end - MARKER_DASHES                     // kapanış tireleri sonraki işaretin başı olabilir
  }
  return out
}

/**
 * BEGIN ↔ END eşleştirmesi — eski tembel ifadeyle AYNI anlam: her BEGIN, gövdesinden SONRA gelen aynı etiketli İLK END
 * ile eşleşir (iç içe başka bloklar gövdede kalır); END'i olmayan BEGIN atlanır ve tarama sonraki işaretten sürer;
 * eşleşen bloktan sonra tarama END'in bitiminden sürer. Etiket başına END listesi + tek yönlü işaretçi → doğrusal.
 * @returns {Array<{ label: string, body: string }>} ham etiket ve gövde metni
 */
function pemRawBlocks(text) {
  const markers = pemMarkers(text)
  const ends = new Map()                                           // etiket → { list: [işaret], i: işaretçi }
  for (const mk of markers) {
    if (mk.begin) continue
    let e = ends.get(mk.label)
    if (!e) { e = { list: [], i: 0 }; ends.set(mk.label, e) }
    e.list.push(mk)
  }
  const out = []
  let cursor = 0
  for (const mk of markers) {
    if (!mk.begin || mk.start < cursor) continue
    const e = ends.get(mk.label)
    if (!e) continue
    while (e.i < e.list.length && e.list[e.i].start < mk.end) e.i++   // BEGIN'ler artan sırada → işaretçi geri gitmez
    if (e.i >= e.list.length) continue
    const close = e.list[e.i]
    out.push({ label: mk.label, body: text.slice(mk.end, close.start) })
    cursor = close.end
  }
  return out
}

/** PEM blokları: `[{ label, der }]` (gövdesi çözülemeyen blok atlanır). Özel anahtar blokları ÇÖZÜLMEZ, yalnız sayılır. */
export function pemBlocks(text) {
  const out = []
  for (const m of pemRawBlocks(String(text))) {
    const label = m.label.trim().toUpperCase()
    if (label.includes('PRIVATE KEY')) continue
    const body = m.body.split(/\r?\n/).filter((l) => !l.includes(':')).join('')   // RFC 1421 başlıkları (Proc-Type …)
    const der = base64Decode(body)
    if (der && der.length) out.push({ label, der })
  }
  return out
}

/** Her türden özel anahtar başlığı (kapanışı bozuk olsa da sayılır); birbiriyle örtüşen başlıklar bir kez sayılır. */
export function countPrivateKeyBlocks(text) {
  let n = 0
  let after = 0
  for (const mk of pemMarkers(String(text))) {
    if (!mk.begin || mk.start < after || !mk.label.includes('PRIVATE KEY')) continue
    n++
    after = mk.end
  }
  return n
}

// ── Sınıflandırma ──────────────────────────────────────────────────────────────────────────────────────────────

const OID_SIGNED_DATA = '1.2.840.113549.1.7.2'
const TIME_TAGS = new Set([T.UTC_TIME, T.GEN_TIME])

/** Bayt dizisinin başında X.509 sertifikası mı (yapısal denetim: tbs alanları, geçerlilik süresi, imza). */
export function x509Node(buf, pos = 0, end = buf.length) {
  try {
    const n = readTlv(buf, pos, end)
    if (!isSeq(n)) return null
    const k = children(buf, n)
    if (k.length !== 3 || !isSeq(k[0]) || !isSeq(k[1]) || !is(k[2], UNIVERSAL, T.BIT_STRING)) return null
    const tbs = children(buf, k[0])
    const i = isCtx(tbs[0], 0) && tbs[0].constructed ? 1 : 0
    if (tbs.length < i + 6) return null
    if (!is(tbs[i], UNIVERSAL, T.INTEGER)) return null
    if (!isSeq(tbs[i + 1]) || !isSeq(tbs[i + 2]) || !isSeq(tbs[i + 3]) || !isSeq(tbs[i + 4]) || !isSeq(tbs[i + 5])) return null
    const validity = children(buf, tbs[i + 3])
    if (validity.length !== 2 || !validity.every((v) => v.cls === UNIVERSAL && TIME_TAGS.has(v.tag))) return null
    return n
  } catch {
    return null
  }
}

export const isX509 = (der) => {
  const n = der && der.length ? x509Node(der) : null
  return !!n && n.end === der.length
}

/** PKCS#10 CSR mi: SEQ { SEQ { INTEGER 0, SEQ ad, SEQ açık anahtar, [0] öznitelikler }, SEQ alg, BIT STRING }. */
export function isCsr(der) {
  try {
    const n = readTlv(der)
    if (!isSeq(n) || n.end !== der.length) return false
    const k = children(der, n)
    if (k.length !== 3 || !isSeq(k[0]) || !isSeq(k[1]) || !is(k[2], UNIVERSAL, T.BIT_STRING)) return false
    const info = children(der, k[0])
    return info.length >= 3 && info.length <= 4 && is(info[0], UNIVERSAL, T.INTEGER) && int(der, info[0]) === 0
      && isSeq(info[1]) && isSeq(info[2]) && (info.length === 3 || isCtx(info[3], 0))
  } catch {
    return false
  }
}

/** PKCS#7 SignedData (yalnız sertifikalar) → sertifika DER'leri. Bozuk → null. */
export function pkcs7Certificates(der) {
  try {
    const n = readTlv(der)
    if (!isSeq(n)) return null
    const k = children(der, n)
    if (!is(k[0], UNIVERSAL, T.OID) || oid(der, k[0]) !== OID_SIGNED_DATA) return null
    if (!k[1] || !isCtx(k[1], 0)) return []
    const sd = children(der, children(der, k[1])[0])
    const out = []
    for (const part of sd) {
      if (!isCtx(part, 0) || !part.constructed) continue          // certificates [0] IMPLICIT SET OF
      for (const c of children(der, part)) {
        const bytes = raw(der, c)
        if (isX509(bytes)) out.push(bytes)
      }
    }
    return out
  } catch {
    return null
  }
}

/**
 * Tek başına DER blob'un türü: `PKCS7` | `PKCS12` | `X509` | `CSR` | `PRIVATE_KEY` | null. Özel anahtar yalnız ŞEKLİNDEN
 * tanınır (PKCS#8, şifreli PKCS#8, PKCS#1 RSA, SEC1 EC, DSA) — içeriği okunmaz.
 */
export function classifyDer(der) {
  let n
  let k
  try {
    n = readTlv(der)
    if (!isSeq(n)) return null
    k = children(der, n)
  } catch {
    return null
  }
  if (!k.length) return null
  try {
    if (is(k[0], UNIVERSAL, T.OID)) return oid(der, k[0]) === OID_SIGNED_DATA ? 'PKCS7' : null
    if (is(k[0], UNIVERSAL, T.INTEGER) && k.length >= 2 && k.length <= 3 && isSeq(k[1]) && int(der, k[0]) === 3) {
      const ci = children(der, k[1])
      if (ci.length >= 1 && is(ci[0], UNIVERSAL, T.OID)) return 'PKCS12'
    }
  } catch { /* aşağıdaki denemelere geç */ }
  if (x509Node(der)) return 'X509'
  if (isCsr(der)) return 'CSR'
  if (looksLikePrivateKey(der, k)) return 'PRIVATE_KEY'
  return null
}

function looksLikePrivateKey(der, k) {
  try {
    // EncryptedPrivateKeyInfo: SEQ { AlgorithmIdentifier, OCTET STRING }
    if (k.length === 2 && isSeq(k[0]) && is(k[1], UNIVERSAL, T.OCTET_STRING)) {
      const alg = children(der, k[0])
      return alg.length >= 1 && is(alg[0], UNIVERSAL, T.OID)
    }
    if (!is(k[0], UNIVERSAL, T.INTEGER)) return false
    const v = int(der, k[0])
    // PKCS#8 PrivateKeyInfo / OneAsymmetricKey: SEQ { INTEGER 0|1, AlgorithmIdentifier, OCTET STRING, … }
    if ((v === 0 || v === 1) && k.length >= 3 && isSeq(k[1]) && is(k[2], UNIVERSAL, T.OCTET_STRING)) return true
    // SEC1 ECPrivateKey: SEQ { INTEGER 1, OCTET STRING, [0]?, [1]? }
    if (v === 1 && k.length >= 2 && is(k[1], UNIVERSAL, T.OCTET_STRING)) return true
    // PKCS#1 RSAPrivateKey (9 tamsayı) / DSA (6 tamsayı): hepsi INTEGER
    if (v === 0 && (k.length === 9 || k.length === 6) && k.every((c) => is(c, UNIVERSAL, T.INTEGER))) return true
  } catch { /* tanınmadı */ }
  return false
}

/** Metin gibi mi: ilk 4096 baytta NUL yok ve ≥ %95 yazdırılabilir (sunucudaki kuralla aynı). */
export function asciiText(bytes) {
  const limit = Math.min(bytes.length, 4096)
  let printable = 0
  for (let i = 0; i < limit; i++) {
    const b = bytes[i]
    if (b === 0) return null
    if (b === 10 || b === 13 || b === 9 || (b >= 0x20 && b < 0x7f)) printable++
  }
  if (limit > 0 && (printable * 100) / limit < 95) return null
  let s = ''
  for (let i = 0; i < bytes.length; i += 8192) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 8192))
  return s
}

const LOOSE_BASE64 = /^[A-Za-z0-9+/=\s]+$/
/** Zırhsız Base64 metin → bayt (en az 16 karakter); değilse null. */
export function looseBase64(text) {
  const t = String(text ?? '').trim()
  if (t.length < 16 || !LOOSE_BASE64.test(t)) return null
  return base64Decode(t)
}
