/**
 * JKS (0xFEEDFEED) / JCEKS (0xCECECECE) okuyucu — tarayıcıda, PAROLASIZ (2026-10-08). Biçim düz bir ikili akıştır:
 * özel anahtar girdisinin korumalı anahtar baytları uzunluğuyla birlikte ATLANIR (hiç çözülmez, kopyalanmaz); ardından
 * gelen AÇIK sertifika zinciri okunur. Güvenilen sertifika girdileri doğrudan okunur. JCEKS gizli anahtar girdisi
 * (Java serileştirmesi) sınırlı bir dilbilgisi atlayıcısıyla geçilir; atlanamazsa okuma orada durur (`partial`).
 *
 * <p>Bütünlük özeti (SHA-1, "Mighty Aphrodite") yalnız parola VERİLDİYSE denetlenir; tutmazsa `integrityFailed` —
 * sertifikalar yine okunur (sunucunun eski davranışıyla aynı: anahtar deposu açık sertifikaları parolasız okunabilir).
 */
import { equalBytes, isX509 } from './asn1.js'
import { javaKeystoreDigest } from './crypto.js'

const MAX_ENTRIES = 10_000
const MAX_CHAIN = 64

class Reader {
  constructor(buf, end) { this.buf = buf; this.pos = 0; this.end = end; this.view = new DataView(buf.buffer, buf.byteOffset, buf.byteLength) }
  need(n) { if (n < 0 || this.pos + n > this.end) throw new Error('eof') }
  u8() { this.need(1); return this.buf[this.pos++] }
  u16() { this.need(2); const v = this.view.getUint16(this.pos); this.pos += 2; return v }
  u32() { this.need(4); const v = this.view.getUint32(this.pos); this.pos += 4; return v }
  i32() { this.need(4); const v = this.view.getInt32(this.pos); this.pos += 4; return v }
  skip(n) { this.need(n); this.pos += n }
  bytes(n) { this.need(n); const b = this.buf.subarray(this.pos, this.pos + n); this.pos += n; return b }
  /** DataOutput.writeUTF (değiştirilmiş UTF-8; takma adlar pratikte ASCII). */
  utf() {
    const n = this.u16()
    const b = this.bytes(n)
    try { return new TextDecoder('utf-8', { fatal: false }).decode(b) } catch { return '' }
  }
}

/**
 * @param {Uint8Array} bytes
 * @param {string} password '' = verilmedi
 * @returns {{ format: 'JKS'|'JCEKS', entries: Array<{alias, key_entry, certs: Uint8Array[]}>, keys: number,
 *   partial: boolean, integrityFailed: boolean, passwordUsed: boolean }}
 */
export function readJavaKeystore(bytes, password) {
  if (bytes.length < 32) throw new Error('short')
  const end = bytes.length - 20                    // sondaki 20 bayt bütünlük özeti
  const r = new Reader(bytes, end)
  const magic = r.u32()
  const format = magic === 0xfeedfeed ? 'JKS' : magic === 0xcececece ? 'JCEKS' : null
  if (!format) throw new Error('magic')
  const version = r.u32()
  if (version !== 1 && version !== 2) throw new Error('version')
  const count = r.u32()
  if (count > MAX_ENTRIES) throw new Error('count')

  const entries = []
  let keys = 0
  let partial = false
  const readCert = () => {
    const type = version === 2 ? r.utf() : 'X.509'
    const len = r.u32()
    const der = r.bytes(len)
    return type === 'X.509' && isX509(der) ? der.slice() : null
  }
  try {
    for (let i = 0; i < count; i++) {
      const tag = r.u32()
      const alias = r.utf().slice(0, 255)
      r.skip(8)                                        // oluşturma zamanı
      if (tag === 1) {
        const keyLen = r.u32()
        r.skip(keyLen)                                 // KORUMALI ÖZEL ANAHTAR — okunmaz, çözülmez, kopyalanmaz
        keys++
        const n = r.u32()
        if (n > MAX_CHAIN) throw new Error('chain')
        const certs = []
        for (let j = 0; j < n; j++) { const c = readCert(); if (c) certs.push(c) }
        if (certs.length) entries.push({ alias: alias || null, key_entry: true, certs })
      } else if (tag === 2) {
        const c = readCert()
        if (c) entries.push({ alias: alias || null, key_entry: false, certs: [c] })
      } else if (tag === 3 && format === 'JCEKS') {
        skipJavaObjectStream(r)                        // gizli (simetrik) anahtar — atlanır, sayılır
        keys++
      } else {
        throw new Error('tag')
      }
    }
  } catch {
    partial = true                                     // okunabilen kadarı kalır
  }

  let integrityFailed = false
  const given = typeof password === 'string' && password.length > 0
  if (given) {
    const digest = javaKeystoreDigest(password, bytes.subarray(0, end))
    integrityFailed = !equalBytes(digest, bytes.subarray(end, end + 20))
  }
  return { format, entries, keys, partial, integrityFailed, passwordUsed: given }
}

// ── Java nesne serileştirmesi (yalnız ATLAMA) ───────────────────────────────────────────────────────────────────
const TC = { NULL: 0x70, REFERENCE: 0x71, CLASSDESC: 0x72, OBJECT: 0x73, STRING: 0x74, ARRAY: 0x75, CLASS: 0x76, BLOCKDATA: 0x77,
  ENDBLOCKDATA: 0x78, RESET: 0x79, BLOCKDATALONG: 0x7a, LONGSTRING: 0x7c, PROXYCLASSDESC: 0x7d, ENUM: 0x7e }
const SC_WRITE_METHOD = 0x01
const SC_SERIALIZABLE = 0x02
const SC_EXTERNALIZABLE = 0x04
const SC_BLOCK_DATA = 0x08
const PRIM_SIZE = { B: 1, C: 2, D: 8, F: 4, I: 4, J: 8, S: 2, Z: 1 }
/** Üst sınıf zincirinin azami uzunluğu (gerçek Java sınıf hiyerarşileri birkaç düzeydir). */
const MAX_CLASS_CHAIN = 64

/**
 * Sınıf tanımı → üst sınıflar ÖNCE gelecek biçimde zincir. Bozuk / kötü niyetli akışta `super` döngüsel olabilir
 * (TC_REFERENCE kendi tanıtıcısına ya da A→B→A); döngü ya da aşırı uzun zincir → hata (çağıran kısmi okuma sayar),
 * sonsuz döngü OLMAZ (2026-10-09).
 */
function classChain(desc) {
  const chain = []
  const seen = new Set()
  for (let d = desc; d; d = d.super) {
    if (seen.has(d) || chain.length >= MAX_CLASS_CHAIN) throw new Error('cycle')
    seen.add(d)
    chain.push(d)
  }
  return chain.reverse()
}

/** `ObjectOutputStream` ile yazılmış TEK nesneyi (akış başlığı dahil) atlar; tanınmayan yapı → hata. */
function skipJavaObjectStream(r) {
  if (r.u16() !== 0xaced || r.u16() !== 5) throw new Error('stream header')
  const handles = []
  /** Okunmakta olan (üst sınıfı henüz bağlanmamış) sınıf tanımları — üst sınıf bunlardan biri olamaz (döngü). */
  const reading = new Set()
  let budget = 100_000
  const tick = () => { if (--budget < 0) throw new Error('budget') }
  const linkSuper = (desc, depth) => {
    const sup = readClassDesc(depth + 1)
    if (sup && reading.has(sup)) throw new Error('cycle')        // kendisi ya da henüz okunan bir ata
    desc.super = sup
    reading.delete(desc)
  }

  const readClassDesc = (depth) => {
    tick()
    const tc = r.u8()
    if (tc === TC.NULL) return null
    if (tc === TC.REFERENCE) { const h = handles[r.i32() - 0x7e0000]; if (!h || h.kind !== 'desc') throw new Error('ref'); return h }
    if (tc === TC.CLASSDESC) {
      const desc = { kind: 'desc', name: r.utf(), fields: [] }
      r.skip(8)                                                   // serialVersionUID
      handles.push(desc)
      reading.add(desc)
      desc.flags = r.u8()
      const n = r.u16()
      for (let i = 0; i < n; i++) {
        const type = String.fromCharCode(r.u8())
        r.utf()                                                   // alan adı
        if (type === 'L' || type === '[') readContent(depth + 1)  // alan sınıf adı (TC_STRING / TC_REFERENCE)
        desc.fields.push(type)
      }
      skipAnnotation(depth + 1)
      linkSuper(desc, depth)
      return desc
    }
    if (tc === TC.PROXYCLASSDESC) {
      const desc = { kind: 'desc', name: '$proxy', fields: [], flags: SC_SERIALIZABLE }
      handles.push(desc)
      reading.add(desc)
      const n = r.i32()
      if (n < 0 || n > 64) throw new Error('proxy')
      for (let i = 0; i < n; i++) r.utf()
      skipAnnotation(depth + 1)
      linkSuper(desc, depth)
      return desc
    }
    throw new Error('classdesc')
  }

  const skipAnnotation = (depth) => {
    for (;;) {
      tick()
      const tc = r.buf[r.pos]
      if (tc === TC.ENDBLOCKDATA) { r.pos++; return }
      readContent(depth)
    }
  }

  const readContent = (depth) => {
    tick()
    if (depth > 32) throw new Error('depth')
    const tc = r.u8()
    switch (tc) {
      case TC.NULL: return null
      case TC.REFERENCE: return handles[r.i32() - 0x7e0000] || null
      case TC.STRING: { const s = { kind: 'str', v: r.utf() }; handles.push(s); return s }
      case TC.LONGSTRING: { const hi = r.u32(); const lo = r.u32(); if (hi) throw new Error('long'); r.skip(lo); const s = { kind: 'str' }; handles.push(s); return s }
      case TC.BLOCKDATA: r.skip(r.u8()); return null
      case TC.BLOCKDATALONG: r.skip(r.u32()); return null
      case TC.RESET: return null
      case TC.CLASSDESC: case TC.PROXYCLASSDESC: r.pos--; return readClassDesc(depth)
      case TC.CLASS: { const d = readClassDesc(depth + 1); handles.push({ kind: 'class', d }); return null }
      case TC.ENUM: { readClassDesc(depth + 1); handles.push({ kind: 'enum' }); readContent(depth + 1); return null }
      case TC.ARRAY: {
        const desc = readClassDesc(depth + 1)
        handles.push({ kind: 'array' })
        const n = r.i32()
        if (!desc || n < 0) throw new Error('array')
        const el = desc.name.charAt(1)
        if (PRIM_SIZE[el]) r.skip(n * PRIM_SIZE[el])
        else for (let i = 0; i < n; i++) readContent(depth + 1)
        return null
      }
      case TC.OBJECT: {
        const desc = readClassDesc(depth + 1)
        handles.push({ kind: 'obj' })
        for (const d of classChain(desc)) {
          if (d.flags & SC_EXTERNALIZABLE) {
            if (!(d.flags & SC_BLOCK_DATA)) throw new Error('externalizable')
            skipAnnotation(depth + 1)
            continue
          }
          if (!(d.flags & SC_SERIALIZABLE)) continue
          for (const type of d.fields) {
            if (PRIM_SIZE[type]) r.skip(PRIM_SIZE[type])
            else readContent(depth + 1)
          }
          if (d.flags & SC_WRITE_METHOD) skipAnnotation(depth + 1)
        }
        return null
      }
      default: throw new Error(`tc ${tc}`)
    }
  }

  readContent(0)
}
