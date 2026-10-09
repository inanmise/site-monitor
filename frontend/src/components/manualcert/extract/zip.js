/**
 * ZIP okuma — SINIRLI (2026-10-09, sonsuz döngü / maliyet denetimi). fflate `unzipSync` iki yerde dosyaya güveniyordu:
 * (1) merkezi dizindeki kayıt SAYISI (zip64'te 4 milyara kadar) — kayıtlar dosyanın dışına taşsa da döngü sürer
 * (sıfır baytlarla ~2 milyon kayıt/sn → dakikalar; girdi başına bir kez daha); (2) bildirilen açılmış boy — açıcı verilen
 * tamponu aşınca yazmayı bırakır ama GERÇEK çıktının sonuna kadar çözmeyi sürdürür (200 KB sıkıştırılmış → 200 MB iş).
 *
 * <p>Burada merkezi dizin fflate'in okumasıyla AYNI kurallarla okunur (EOCD araması, zip64 bulucu / ek alan, ad
 * kodlaması — `strFromU8`), ama dosyanın dışındaki kayıtta durulur. Açma fflate'in akış açıcısıyla küçük parçalar hâlinde
 * yapılır ve bildirilen boya ulaşınca BIRAKILIR — sonuç eskisiyle aynıdır (fflate de bildirilen boyda keserdi).
 */
import { Inflate, strFromU8 } from 'fflate'

const SIG_EOCD = 0x06054b50
const SIG_Z64_LOCATOR = 0x07064b50
const SIG_Z64_EOCD = 0x06064b50
const CEN_MIN = 46
/** Akış açıcıya verilen parça: tek itmede en çok ~16 MB çıktı (DEFLATE ≤ ~1032:1) — bellek ve iş sınırlı kalır. */
const INFLATE_CHUNK = 16 * 1024

const b2 = (d, b) => d[b] | (d[b + 1] << 8)
const b4 = (d, b) => (d[b] | (d[b + 1] << 8) | (d[b + 2] << 16) | (d[b + 3] << 24)) >>> 0
const b8 = (d, b) => b4(d, b) + (b4(d, b + 4) * 4294967296)

export class ZipFormatError extends Error {
  constructor(detail) { super(`zip: ${detail}`); this.name = 'ZipFormatError' }
}

/** zip64 ek alanından boylar (fflate `z64hs` ile aynı kural; zip64 arşivinde ek alan yoksa hata). */
function zip64Sizes(d, b, l, z, sc, su, off) {
  const nsc = sc === 4294967295
  const nsu = su === 4294967295
  const noff = off === 4294967295
  const e = b + l
  if (z && (nsc || nsu || noff)) {
    for (; b + 4 < e; b += 4 + b2(d, b + 2)) {
      if (b2(d, b) === 1) {
        return [
          nsc ? b8(d, b + 4 + 8 * nsu) : sc,
          nsu ? b8(d, b + 4) : su,
          noff ? b8(d, b + 4 + 8 * (nsu + nsc)) : off,
        ]
      }
    }
    throw new ZipFormatError('zip64 extra')
  }
  return [sc, su, off]
}

/**
 * Merkezi dizin kayıtları, sırasıyla: `{ name, size, originalSize, compression, start, dataStart, dataEnd }`
 * (`start` yerel başlık, `dataStart..dataEnd` sıkıştırılmış veri — dosya sınırına kırpılmış). Kayıt sayısı dosya
 * boyuyla sınırlıdır (her kayıt ≥ 46 bayt ve dosyanın İÇİNDE olmalı). Okunamayan arşiv → {@link ZipFormatError}.
 */
export function zipDirectory(data) {
  let e = data.length - 22
  for (; b4(data, e) !== SIG_EOCD; --e) {
    if (!e || data.length - e > 65558) throw new ZipFormatError('eocd')
  }
  let c = b2(data, e + 8)
  if (!c) return []
  let o = b4(data, e + 16)
  let z = b4(data, e - 20) === SIG_Z64_LOCATOR
  if (z) {
    const ze = b4(data, e - 12)
    z = b4(data, ze) === SIG_Z64_EOCD
    if (z) {
      c = b4(data, ze + 32)
      o = b4(data, ze + 48)
    }
  }
  const out = []
  for (let i = 0; i < c; i++) {
    if (o + CEN_MIN > data.length) break                         // sayı yalan söylüyor — dosyanın dışında kayıt yok
    const fnl = b2(data, o + 28)
    const efl = b2(data, o + 30)
    const name = strFromU8(data.subarray(o + 46, o + 46 + fnl), !(b2(data, o + 8) & 2048))
    const es = o + 46 + fnl
    const [size, originalSize, start] = zip64Sizes(data, es, efl, z, b4(data, o + 20), b4(data, o + 24), b4(data, o + 42))
    const dataStart = start + 30 + b2(data, start + 26) + b2(data, start + 28)
    out.push({
      name, size, originalSize, compression: b2(data, o + 10), start,
      dataStart, dataEnd: Math.min(dataStart + size, data.length),
    })
    o = es + efl + b2(data, o + 32)
  }
  return out
}

/** İki kaydın baytları örtüşüyor mu (aynı yerel başlığı ya da verisi paylaşan kayıtlar — gerçek arşivde olmaz). */
export function zipEntriesOverlap(a, b) {
  const endA = Math.max(a.dataEnd, a.start + 30)
  const endB = Math.max(b.dataEnd, b.start + 30)
  return a.start < endB && b.start < endA
}

/**
 * Kaydın içeriği (fflate `unzipSync` ile aynı sonuç): saklı → kopya; DEFLATE → en çok `originalSize` bayt (bildirilen
 * boy). Bildirilen boya ulaşınca açma BIRAKILIR; bilinmeyen sıkıştırma ya da bozuk akış → hata.
 */
export function zipEntryData(data, rec) {
  if (!rec.compression) return data.slice(rec.dataStart, rec.dataStart + rec.size)
  if (rec.compression !== 8) throw new ZipFormatError(`compression ${rec.compression}`)
  const src = data.subarray(rec.dataStart, rec.dataStart + rec.size)
  const limit = rec.originalSize
  const out = new Uint8Array(limit)
  if (!src.length) return out                                    // fflate: boş girdi → bildirilen boyda sıfır tampon
  let n = 0
  let full = limit === 0
  const inflater = new Inflate((chunk) => {
    if (full) return
    const take = Math.min(chunk.length, limit - n)
    out.set(chunk.subarray(0, take), n)
    n += take
    if (n >= limit) full = true
  })
  for (let i = 0; i < src.length && !full; i += INFLATE_CHUNK) {
    const end = Math.min(i + INFLATE_CHUNK, src.length)
    inflater.push(src.subarray(i, end), end === src.length)
  }
  return n === limit ? out : out.subarray(0, n)
}
