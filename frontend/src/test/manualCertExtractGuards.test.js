import { describe, it, expect, beforeAll } from 'vitest'
import { webcrypto } from 'node:crypto'
import forge from 'node-forge'
import { unzipSync, deflateSync } from 'fflate'
import { extractCore } from '../components/manualcert/extract/core.js'
import { countPrivateKeyBlocks, pemBlocks, base64Decode } from '../components/manualcert/extract/asn1.js'
import { readJavaKeystore } from '../components/manualcert/extract/javaKeystore.js'
import { pkcs12Kdf } from '../components/manualcert/extract/crypto.js'
import { zipDirectory, zipEntryData } from '../components/manualcert/extract/zip.js'
import { b64, keyPem, makeChain, pkcs8Pem, zipBytes } from './helpers/certFixtures.js'

/**
 * Sonsuz döngü / maliyet bombası sınırları (2026-10-09, kullanıcı isteği: "sonsuz döngüye yol açabilecek kodları tespit
 * edelim … riski bertaraf edelim"). Tarayıcıdaki ayıklayıcı Web Worker'da 60 sn zaman aşımıyla koşar ama Worker
 * kurulamazsa ANA iş parçacığında, zaman aşımı OLMADAN koşar — her patolojik girdi kendi sınırında, hızla bitmeli.
 * Her test dar bir zaman aşımıyla koşar: gerileme olursa test asılı kalmaz, düşer. Gerçek dosyaların davranışı
 * `manualCertExtract.test.js` (OpenSSL / keytool çıktıları dahil) ile aynı kalır.
 */

const FAST = { timeout: 5000 }
const run = (bytes, name, password = '') => extractCore({ bytes, name, password }, { subtle: webcrypto.subtle })
const enc = (s) => new Uint8Array(Buffer.from(s, 'latin1'))

let chain
beforeAll(() => { chain = makeChain('guard.example.test') })

// ── 1) JCEKS — Java serileştirmesinde döngüsel üst sınıf ──────────────────────────────────────────────────────

/** Tek gizli-anahtar (tag 3) girdili JCEKS: başlık + girdi başlığı + verilen nesne akışı + 20 bayt özet. */
function jceks(stream) {
  const head = [0xce, 0xce, 0xce, 0xce, 0, 0, 0, 2, 0, 0, 0, 1]
  const entry = [0, 0, 0, 3, 0, 0, ...new Array(8).fill(0)]       // tag 3, boş takma ad, oluşturma zamanı
  return Uint8Array.from([...head, ...entry, ...stream, ...new Array(20).fill(0)])
}
const CLASSDESC = (name) => [0x72, 0x00, name.length, ...Buffer.from(name), ...new Array(8).fill(0), 0x02, 0x00, 0x00, 0x78]
const REF = (h) => [0x71, 0x00, 0x7e, 0x00, h]

describe('JCEKS — döngüsel üst sınıf zinciri sonsuz döngü yapmaz', () => {
  it('kendine başvuran sınıf tanımı (72 bayt): hızla biter, kısmi okuma', FAST, async () => {
    const bytes = jceks([0xac, 0xed, 0x00, 0x05, 0x73, ...CLASSDESC('A'), ...REF(0)])
    expect(bytes.length).toBe(72)
    const ks = readJavaKeystore(bytes, '')
    expect(ks).toMatchObject({ format: 'JCEKS', entries: [], keys: 0, partial: true })
    const r = await run(bytes, 'dongu.jceks')
    expect(r.notes).toEqual([{ code: 'KEYSTORE_PARTIAL', severity: 'warn', params: { format: 'JCEKS' } }])
    expect(r.certificate_count).toBe(0)
  })

  it('A → B → A döngüsü (B okunurken A\'ya başvuru) de hızla reddedilir', FAST, () => {
    // A'nın üst sınıfı yeni B tanımı; B'nin üst sınıfı hâlâ okunan A (tanıtıcı 0) → döngü
    const bytes = jceks([0xac, 0xed, 0x00, 0x05, 0x73, ...CLASSDESC('A'), ...CLASSDESC('B'), ...REF(0)])
    expect(readJavaKeystore(bytes, '')).toMatchObject({ keys: 0, partial: true })
  })

  it('döngüsüz zincir (A → B → null) eskisi gibi atlanır — sonraki girdiler okunur', () => {
    const stream = [0xac, 0xed, 0x00, 0x05, 0x73, ...CLASSDESC('A'), ...CLASSDESC('B'), 0x70]
    const ks = readJavaKeystore(jceks(stream), '')
    expect(ks).toMatchObject({ format: 'JCEKS', keys: 1, partial: false })
  })
})

// ── 2) PKCS#12 — maliyet bombaları ────────────────────────────────────────────────────────────────────────────

const A = forge.asn1
const U = A.Class.UNIVERSAL
const node = (type, constructed, value) => A.create(U, type, constructed, value)
const seq = (...v) => node(A.Type.SEQUENCE, true, v)
const oidN = (o) => node(A.Type.OID, false, A.oidToDer(o).getBytes())
const intN = (n) => node(A.Type.INTEGER, false, A.integerToDer(n).getBytes())
const octN = (bin) => node(A.Type.OCTETSTRING, false, bin)
const ctx0 = (v) => A.create(A.Class.CONTEXT_SPECIFIC, 0, true, v)
const OIDS = { data: '1.2.840.113549.1.7.1', enc: '1.2.840.113549.1.7.6', sha1: '1.3.14.3.2.26', pbe3des: '1.2.840.113549.1.12.1.3' }
const toU8 = (bin) => new Uint8Array(Buffer.from(bin, 'latin1'))

/** PFX: içerik bilgileri + isteğe bağlı MAC (`{ digest, salt, iterations }` ikili dize). */
function pfx(contents, mac = null) {
  const authSafe = A.toDer(seq(...contents)).getBytes()
  const top = [intN(3), seq(oidN(OIDS.data), ctx0([octN(authSafe)]))]
  if (mac) top.push(seq(seq(seq(oidN(OIDS.sha1), node(A.Type.NULL, false, '')), octN(mac.digest)), octN(mac.salt), intN(mac.iterations)))
  return toU8(A.toDer(seq(...top)).getBytes())
}
/** Eski PBE (SHA1 + 3DES) ile "şifrelenmiş" içerik — gövde rastgele (hiçbir parolayla çözülmez). */
const encrypted = (iterations, body = 'x'.repeat(16)) => seq(oidN(OIDS.enc), ctx0([seq(intN(0), seq(
  oidN(OIDS.data), seq(oidN(OIDS.pbe3des), seq(octN('saltsalt'), intN(iterations))),
  A.create(A.Class.CONTEXT_SPECIFIC, 0, false, body),
))]))

describe('PKCS#12 — dosyadan gelen boy / sayı türetmeyi uzatamaz', () => {
  it('MAC değeri özet boyunda değil (4 KB × 5 milyon yineleme): türetme yapılmadan biçim hatası', FAST, async () => {
    const r = await run(pfx([], { digest: 'm'.repeat(4096), salt: 'saltsalt', iterations: 5_000_000 }), 'mac.pfx')
    expect(r.unsupported).toEqual({ reason: 'PKCS12_FORMAT', detail: 'mac length' })
  })

  it('MAC tuzu 64 baytı aşıyor: biçim hatası', FAST, async () => {
    const r = await run(pfx([], { digest: 'm'.repeat(20), salt: 's'.repeat(65), iterations: 1 }), 'tuz.pfx')
    expect(r.unsupported).toEqual({ reason: 'PKCS12_FORMAT', detail: 'mac salt' })
  })

  it('16\'dan fazla şifreli içerik bloğu: biçim hatası (yalnız ilki denenir)', FAST, async () => {
    const r = await run(pfx(Array.from({ length: 17 }, () => encrypted(1))), 'cok.pfx')
    expect(r.unsupported).toEqual({ reason: 'PKCS12_FORMAT', detail: 'too many encrypted contents' })
  })

  it('dosya başına yineleme bütçesi: 5 milyon yinelemeli eski PBE × iki aday → iş yapılmadan algoritma hatası', FAST, async () => {
    const r = await run(pfx([encrypted(5_000_000)]), 'butce.pfx')
    expect(r.unsupported).toEqual({ reason: 'PKCS12_ALGORITHM', detail: 'iteration budget' })
  })

  it('parolasız: çözülemeyen ilk bloktan sonra diğer şifreli bloklar denenmez → parola istenir', FAST, async () => {
    // İkinci blok denenseydi bütçe aşılır, sonuç PKCS12_ALGORITHM olurdu
    const r = await run(pfx([encrypted(1000), encrypted(5_000_000)]), 'iki.pfx')
    expect(r.unsupported).toBeNull()
    expect(r.needs_password).toBe(true)
  })

  it('pkcs12Kdf savunma sınırları: çıktı boyu ve girdi boyları', () => {
    const pw = new Uint8Array(10)
    expect(pkcs12Kdf('SHA-1', pw, new Uint8Array(8), 3, 1, 20)).toHaveLength(20)
    expect(pkcs12Kdf('SHA-512', pw, new Uint8Array(64), 3, 1, 64)).toHaveLength(64)
    expect(() => pkcs12Kdf('SHA-1', pw, new Uint8Array(8), 3, 1, 129)).toThrow(/kdf length/)
    expect(() => pkcs12Kdf('SHA-1', pw, new Uint8Array(8), 3, 1, 0)).toThrow(/kdf length/)
    expect(() => pkcs12Kdf('SHA-1', pw, new Uint8Array(1025), 3, 1, 20)).toThrow(/kdf input/)
    expect(() => pkcs12Kdf('SHA-1', new Uint8Array(4097), new Uint8Array(8), 3, 1, 20)).toThrow(/kdf input/)
  })
})

// ── 3) PEM — doğrusal işaret taraması ─────────────────────────────────────────────────────────────────────────

/** ESKİ uygulama (kâhin): tembel ifade + özel anahtar başlığı ifadesi. */
const OLD_PEM = /-----BEGIN ([A-Z0-9 .]+)-----([\s\S]*?)-----END \1-----/g
const OLD_PRIVATE = /-----BEGIN [A-Z0-9 .]*PRIVATE KEY[A-Z0-9 .]*-----/g
function oldPemBlocks(text) {
  const out = []
  for (const m of String(text).matchAll(OLD_PEM)) {
    const label = m[1].trim().toUpperCase()
    if (label.includes('PRIVATE KEY')) continue
    const body = m[2].split(/\r?\n/).filter((l) => !l.includes(':')).join('')
    const der = base64Decode(body)
    if (der && der.length) out.push({ label, der })
  }
  return out
}
const oldCount = (text) => [...String(text).matchAll(OLD_PRIVATE)].length
const view = (blocks) => blocks.map((b) => [b.label, Buffer.from(b.der).toString('base64')])

function fuzz(tokens, count, maxLen, seed = 11) {
  let s = seed >>> 0
  const rnd = (n) => { s = (Math.imul(s, 1103515245) + 12345) >>> 0; return s % n }
  return Array.from({ length: count }, () => {
    let t = ''
    const len = rnd(maxLen) + 1
    for (let j = 0; j < len; j++) t += tokens[rnd(tokens.length)]
    return t
  })
}

describe('PEM — işaret taraması eskisiyle aynı ve doğrusal', () => {
  it('normal çok bloklu PEM (sertifikalar, anahtar, RFC 1421 başlıkları, CRLF) birebir aynı', () => {
    const lf = (s) => s.replace(/\r\n/g, '\n')                      // forge PEM'i CRLF yazar
    const headers = lf(chain.inter.pem).replace('-----\n', '-----\nProc-Type: 4,ENCRYPTED\nDEK-Info: AES-128-CBC,00\n\n')
    expect(headers).toContain('Proc-Type')
    const text = `başlık metni\n${lf(keyPem(chain.leaf.keys))}${lf(chain.leaf.pem)}${pkcs8Pem(chain.leaf.keys)}${headers}`
      + chain.root.pem + '-----BEGIN CERTIFICATE-----\nkapanissiz'
    expect(chain.root.pem).toContain('\r\n')
    expect(view(pemBlocks(text))).toEqual(view(oldPemBlocks(text)))
    expect(pemBlocks(text).map((b) => b.label)).toEqual(['CERTIFICATE', 'CERTIFICATE', 'CERTIFICATE'])
    expect(countPrivateKeyBlocks(text)).toBe(oldCount(text))
    expect(countPrivateKeyBlocks(text)).toBe(2)
  })

  it('rastgele işaret dizilerinde (iç içe, kapanışsız, tire paylaşan) eskisiyle aynı', () => {
    const tokens = ['-----BEGIN A-----', '-----END A-----', '-----BEGIN B C-----', '-----END B C-----', '-----BEGIN RSA PRIVATE KEY-----',
      '-----END RSA PRIVATE KEY-----', '-----', '--', 'BEGIN A', 'END A', ' ', '\n', '\r\n', 'QUJD', 'TUlJ', '=', 'X: y\n', '-']
    for (const t of fuzz(tokens, 1500, 12)) {
      expect(view(pemBlocks(t)), JSON.stringify(t)).toEqual(view(oldPemBlocks(t)))
      expect(countPrivateKeyBlocks(t), JSON.stringify(t)).toBe(oldCount(t))
    }
  })

  it('290 000 kapanışsız BEGIN ve 400 000 tekrarlı özel anahtar etiketi hızla biter', FAST, async () => {
    const many = '-----BEGIN A-----\n'.repeat(290_000)
    expect(pemBlocks(many)).toEqual([])
    expect(countPrivateKeyBlocks(many)).toBe(0)
    const label = `-----BEGIN ${'PRIVATE KEY '.repeat(400_000)}`
    expect(countPrivateKeyBlocks(label)).toBe(0)
    expect(pemBlocks(label)).toEqual([])
    // Uçtan uca (5 MB sınırı içinde)
    const r = await run(enc('-----BEGIN A-----\n'.repeat(270_000)), 'cok.pem')
    expect(r.format).toBe('PEM')
    expect(r.certificate_count).toBe(0)
  })
})

// ── 5) ZIP — sınırlı merkezi dizin ve açma ────────────────────────────────────────────────────────────────────

/** El yazımı DEFLATE (sabit Huffman): 1 sıfır bayt + `copies` × (uzunluk 258, uzaklık 1) → 1 + 258·copies bayt. */
function zeroBomb(copies) {
  const out = new Uint8Array(Math.ceil((3 + 8 + copies * 13 + 7) / 8) + 1)
  let bit = 0
  const put = (v, n) => { for (let i = 0; i < n; i++, bit++) if ((v >> i) & 1) out[bit >> 3] |= 1 << (bit & 7) }
  const huff = (code, len) => { for (let i = len - 1; i >= 0; i--, bit++) if ((code >> i) & 1) out[bit >> 3] |= 1 << (bit & 7) }
  put(1, 1); put(1, 2)                     // BFINAL=1, BTYPE=01 (sabit Huffman)
  huff(0x30, 8)                            // sabit 0 baytı
  for (let i = 0; i < copies; i++) { huff(0xc5, 8); huff(0, 5) }   // uzunluk kodu 285 (258) + uzaklık kodu 0 (1)
  huff(0, 7)                               // blok sonu (256)
  return out.subarray(0, (bit + 7) >> 3)
}

/** Elle ZIP: tek yerel başlık + DEFLATE veri; `records` merkezi dizin kaydı HEPSİ aynı yerel başlığa işaret eder. */
function handZip(data, declared, records = 1) {
  const name = Buffer.from('a.pem')
  const local = Buffer.alloc(30)
  local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(8, 8)
  local.writeUInt32LE(data.length, 18); local.writeUInt32LE(declared, 22); local.writeUInt16LE(name.length, 26)
  const cds = []
  for (let i = 0; i < records; i++) {
    const cd = Buffer.alloc(46)
    cd.writeUInt32LE(0x02014b50, 0); cd.writeUInt16LE(8, 10)
    cd.writeUInt32LE(data.length, 20); cd.writeUInt32LE(declared, 24); cd.writeUInt16LE(name.length, 28)
    cd.writeUInt32LE(0, 42)
    cds.push(cd, name)
  }
  const cdBuf = Buffer.concat(cds)
  const cdOff = 30 + name.length + data.length
  const eocd = Buffer.alloc(22)
  eocd.writeUInt32LE(0x06054b50, 0); eocd.writeUInt16LE(records, 8); eocd.writeUInt16LE(records, 10)
  eocd.writeUInt32LE(cdBuf.length, 12); eocd.writeUInt32LE(cdOff, 16)
  return new Uint8Array(Buffer.concat([local, name, Buffer.from(data), cdBuf, eocd]))
}

describe('ZIP — merkezi dizin ve açma sınırlı, sonuç eskisiyle aynı', () => {
  it('gerçek arşivlerde (saklı / sıkıştırılmış, klasörlü, boş girdi) fflate unzipSync ile birebir aynı içerik', () => {
    const rnd = Uint8Array.from({ length: 70_000 }, (_, i) => (i * 2654435761) >>> 24)
    for (const level of [0, 1, 6, 9]) {
      const zip = zipBytes({ 'certs/': '', 'certs/leaf.pem': chain.leaf.pem, 'bos.txt': '', 'rnd.bin': rnd, 'ç ğ ü.pem': chain.root.pem }, level)
      const expected = unzipSync(zip)
      const got = {}
      for (const rec of zipDirectory(zip)) got[rec.name] = zipEntryData(zip, rec)
      expect(Object.keys(got)).toEqual(Object.keys(expected))
      for (const k of Object.keys(expected)) expect(Buffer.from(got[k]).equals(Buffer.from(expected[k])), `${level} ${k}`).toBe(true)
    }
  })

  it('bildirilenden büyük gerçek çıktı (≈1 GB) bildirilen boyda BIRAKILIR — fflate de bu boyda keserdi', FAST, () => {
    const bomb = zeroBomb(4_000_000)                     // ≈ 6,5 MB DEFLATE → ≈ 1 GB çıktı
    const zip = handZip(bomb, 1000)
    const [rec] = zipDirectory(zip)
    const data = zipEntryData(zip, rec)
    expect(data.length).toBe(1000)
    expect(data.every((b) => b === 0)).toBe(true)
    // Küçük örnekte eski yolla birebir aynı sonuç
    const small = handZip(zeroBomb(100), 500)
    expect(Buffer.from(zipEntryData(small, zipDirectory(small)[0])).equals(Buffer.from(unzipSync(small)['a.pem']))).toBe(true)
  })

  it('aynı yerel başlığa işaret eden 50 kayıt tek kez açılır; diğerleri atlanır (ZIP_SKIPPED_ENTRY)', FAST, async () => {
    const zip = handZip(zeroBomb(2_700_000), 1000, 50)  // ≈ 4,4 MB; eskisi her kayıt için ≈ 700 MB çözerdi
    expect(zip.length).toBeLessThan(5 * 1024 * 1024)
    const r = await run(zip, 'ayni.zip')
    expect(r.format).toBe('ZIP')
    expect(r.certificate_count).toBe(0)
    expect(r.notes.filter((n) => n.code === 'ZIP_SKIPPED_ENTRY')).toHaveLength(50)
  })

  it('zip64 kayıt sayısı yalan (4 milyar) — dosyanın dışındaki kayıtlarda durulur, hızla biter', FAST, async () => {
    const local = Buffer.alloc(30); local.writeUInt32LE(0x04034b50, 0)
    const z64 = Buffer.alloc(56); z64.writeUInt32LE(0x06064b50, 0); z64.writeUInt32LE(0xffffffff, 32); z64.writeUInt32LE(0, 48)
    const loc = Buffer.alloc(20); loc.writeUInt32LE(0x07064b50, 0); loc.writeUInt32LE(30, 8)
    const eocd = Buffer.alloc(22); eocd.writeUInt32LE(0x06054b50, 0); eocd.writeUInt16LE(0xffff, 8)
    const zip = new Uint8Array(Buffer.concat([local, z64, loc, eocd]))
    expect(zipDirectory(zip).length).toBeLessThan(5)
    const r = await run(zip, 'zip64.zip')
    expect(r.format).toBe('ZIP')
    expect(r.certificate_count).toBe(0)
  })

  it('sıkıştırılmış gerçek sertifika girdisi eskisi gibi okunur (çok parçalı açma yukarıdaki rastgele girdiyle sınandı)', async () => {
    const pem = chain.leaf.pem.repeat(20)
    const zip = zipBytes({ 'x.pem': pem, 'y.bin': deflateSync(new Uint8Array(10)) }, 9)
    const r = await run(zip, 'paket.zip')
    expect(r.certificate_count).toBe(20)
    expect(r.entries[0].certs[0]).toBe(b64(chain.leaf.der))
  })
})
