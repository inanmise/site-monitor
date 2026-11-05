/**
 * Küçük TrueType `cmap` okuyucu (test yardımcısı): yazı tipinin karşıladığı Unicode kod noktaları. Yalnız Windows Unicode
 * alt tabloları (platform 3, kodlama 1 → biçim 4; kodlama 10 → biçim 12) okunur — PDF'e gömülen Roboto'nun tablosu
 * budur. Sınırlı: segment / grup sayısı tablonun kendi uzunluğuyla bağlıdır (sonsuz döngü yok).
 */
export function ttfCodePoints(buf) {
  const dv = new DataView(buf.buffer, buf.byteOffset, buf.byteLength)
  const numTables = dv.getUint16(4)
  let cmapOff = -1
  for (let i = 0; i < Math.min(numTables, 64); i += 1) {
    const rec = 12 + i * 16
    const tag = String.fromCharCode(dv.getUint8(rec), dv.getUint8(rec + 1), dv.getUint8(rec + 2), dv.getUint8(rec + 3))
    if (tag === 'cmap') { cmapOff = dv.getUint32(rec + 8); break }
  }
  if (cmapOff < 0) throw new Error('cmap tablosu yok')
  const n = dv.getUint16(cmapOff + 2)
  const subs = []
  for (let i = 0; i < Math.min(n, 32); i += 1) {
    const r = cmapOff + 4 + i * 8
    subs.push({ platform: dv.getUint16(r), encoding: dv.getUint16(r + 2), offset: cmapOff + dv.getUint32(r + 4) })
  }
  const out = new Set()
  const f12 = subs.find((s) => s.platform === 3 && s.encoding === 10 && dv.getUint16(s.offset) === 12)
  if (f12) {
    const groups = dv.getUint32(f12.offset + 12)
    for (let g = 0; g < Math.min(groups, 100_000); g += 1) {
      const base = f12.offset + 16 + g * 12
      const start = dv.getUint32(base)
      const end = Math.min(dv.getUint32(base + 4), start + 0x10000)
      for (let c = start; c <= end; c += 1) out.add(c)
    }
    return out
  }
  const f4 = subs.find((s) => s.platform === 3 && s.encoding === 1 && dv.getUint16(s.offset) === 4)
  if (!f4) throw new Error('biçim 4 / 12 alt tablosu yok')
  const o = f4.offset
  const segX2 = dv.getUint16(o + 6)
  const seg = segX2 / 2
  const ends = o + 14
  const starts = ends + segX2 + 2
  const deltas = starts + segX2
  const ranges = deltas + segX2
  for (let i = 0; i < seg; i += 1) {
    const end = dv.getUint16(ends + i * 2)
    const start = dv.getUint16(starts + i * 2)
    const delta = dv.getInt16(deltas + i * 2)
    const roff = dv.getUint16(ranges + i * 2)
    for (let c = start; c <= end && c !== 0xffff; c += 1) {
      let glyph
      if (roff === 0) glyph = (c + delta) & 0xffff
      else {
        const gi = ranges + i * 2 + roff + (c - start) * 2
        glyph = gi + 1 < dv.byteLength ? dv.getUint16(gi) : 0
        if (glyph !== 0) glyph = (glyph + delta) & 0xffff
      }
      if (glyph !== 0) out.add(c)
    }
  }
  return out
}

/** Metindeki, yazı tipinde OLMAYAN karakterler (tekrarsız; boşluk / satır sonu sayılmaz). */
export function missingGlyphs(text, cps) {
  const miss = new Set()
  for (const ch of String(text)) {
    const c = ch.codePointAt(0)
    if (c === 0x0a || c === 0x0d || c === 0x09) continue
    if (!cps.has(c)) miss.add(ch)
  }
  return [...miss]
}
