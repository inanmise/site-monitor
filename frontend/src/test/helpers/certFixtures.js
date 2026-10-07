/**
 * Manuel sertifika ayıklama testleri için TEST ANINDA üretilen fikstürler (2026-10-08). Hiçbir anahtar / anahtar deposu
 * depoya yazılmaz: anahtarlar bellekte üretilir (node-forge + Node'un yerleşik RSA'sı), adlar `example.test`.
 * Bağımsız yazıcılar (JKS ikili akışı, ZIP) üretim kodundan AYRI yazıldı — ayıklayıcıyı kendi çıktısıyla sınamayalım.
 */
import forge from 'node-forge'
import { createHash } from 'node:crypto'
import { zipSync } from 'fflate'

const toU8 = (bin) => Uint8Array.from(bin, (c) => c.charCodeAt(0) & 0xff)
const toBin = (u8) => String.fromCharCode(...u8)
let serial = 1

function newKey(bits = 2048) {
  return forge.pki.rsa.generateKeyPair({ bits, e: 0x10001 })
}

function makeCert({ cn, keys, issuer = null, ca = false, days = 365, san = [] }) {
  const cert = forge.pki.createCertificate()
  cert.publicKey = keys.publicKey
  cert.serialNumber = (serial++).toString(16).padStart(4, '0')
  cert.validity.notBefore = new Date(Date.now() - 86_400_000)
  cert.validity.notAfter = new Date(Date.now() + days * 86_400_000)
  const subject = [{ name: 'commonName', value: cn }, { name: 'organizationName', value: 'Example Test' }]
  cert.setSubject(subject)
  cert.setIssuer(issuer ? issuer.cert.subject.attributes : subject)
  const ext = [{ name: 'basicConstraints', cA: ca, critical: ca }]
  if (ca) ext.push({ name: 'keyUsage', keyCertSign: true, cRLSign: true, critical: true })
  if (san.length) ext.push({ name: 'subjectAltName', altNames: san.map((value) => ({ type: 2, value })) })
  cert.setExtensions(ext)
  cert.sign(issuer ? issuer.keys.privateKey : keys.privateKey, forge.md.sha256.create())
  return cert
}

/** Kök → ara → yaprak; her biri `{ cert, keys, der, pem }`. */
export function makeChain(leafCn = 'api.example.test') {
  const rootKeys = newKey()
  const root = { keys: rootKeys, cert: makeCert({ cn: 'Example Test Root CA', keys: rootKeys, ca: true, days: 3650 }) }
  const interKeys = newKey()
  const inter = { keys: interKeys, cert: makeCert({ cn: 'Example Test Issuing CA', keys: interKeys, issuer: root, ca: true, days: 1825 }) }
  const leafKeys = newKey()
  const leaf = { keys: leafKeys, cert: makeCert({ cn: leafCn, keys: leafKeys, issuer: inter, san: [leafCn] }) }
  for (const x of [root, inter, leaf]) { x.der = derOf(x.cert); x.pem = forge.pki.certificateToPem(x.cert) }
  return { root, inter, leaf }
}

/** Kendinden imzalı bağımsız kök. */
export function makeRoot(cn) {
  const keys = newKey()
  const cert = makeCert({ cn, keys, ca: true, days: 3650 })
  return { keys, cert, der: derOf(cert), pem: forge.pki.certificateToPem(cert) }
}

export const derOf = (cert) => toU8(forge.asn1.toDer(forge.pki.certificateToAsn1(cert)).getBytes())
export const keyPem = (keys) => forge.pki.privateKeyToPem(keys.privateKey)          // "RSA PRIVATE KEY"
export const pkcs8Pem = (keys) => forge.pki.privateKeyInfoToPem(forge.pki.wrapRsaPrivateKey(forge.pki.privateKeyToAsn1(keys.privateKey)))
export const b64 = (u8) => Buffer.from(u8).toString('base64')

/** Yalnız sertifikalı PKCS#7 SignedData (DER). */
export function pkcs7Der(certs) {
  const p7 = forge.pkcs7.createSignedData()
  for (const c of certs) p7.addCertificate(c)
  return toU8(forge.asn1.toDer(p7.toAsn1()).getBytes())
}

export function csrPem(keys, cn) {
  const csr = forge.pki.createCertificationRequest()
  csr.publicKey = keys.publicKey
  csr.setSubject([{ name: 'commonName', value: cn }])
  csr.sign(keys.privateKey, forge.md.sha256.create())
  return forge.pki.certificationRequestToPem(csr)
}

/**
 * forge PKCS#12 (sertifika torbaları DÜZ, anahtar torbası şifreli; MAC SHA-1) — bağımsız bir uygulamanın çıktısı.
 * @param {object} opts `{ algorithm: '3des'|'aes256', friendlyName, generateLocalKeyId, useMac }`
 */
export function forgePfx(keys, certs, password, opts = {}) {
  const asn = forge.pkcs12.toPkcs12Asn1(keys ? keys.privateKey : null, certs, password,
    { algorithm: '3des', generateLocalKeyId: true, friendlyName: 'sunucu', ...opts })
  return toU8(forge.asn1.toDer(asn).getBytes())
}

// ── JKS / JCEKS ikili yazıcı (Java KeyStore biçimi; test tarafı, bağımsız) ──────────────────────────────────────

function u32(n) { return [(n >>> 24) & 0xff, (n >>> 16) & 0xff, (n >>> 8) & 0xff, n & 0xff] }
function utf(s) { const b = Buffer.from(s, 'utf8'); return [(b.length >> 8) & 0xff, b.length & 0xff, ...b] }

/**
 * @param {object} p `{ magic: 'JKS'|'JCEKS', password, entries: [{ type: 'key', alias, chain: [der], keyBytes? } |
 *   { type: 'trusted', alias, der } | { type: 'raw', tag, alias, bytes }] }` — anahtar baytları RASTGELE (gerçek anahtar değil)
 */
export function javaKeystore({ magic = 'JKS', password = 'Test1234', entries = [] } = {}) {
  const out = [...(magic === 'JKS' ? [0xfe, 0xed, 0xfe, 0xed] : [0xce, 0xce, 0xce, 0xce]), ...u32(2), ...u32(entries.length)]
  for (const e of entries) {
    if (e.type === 'key') {
      const fakeProtectedKey = e.keyBytes || Uint8Array.from({ length: 64 }, (_, i) => (i * 37 + 11) & 0xff)
      out.push(...u32(1), ...utf(e.alias), ...new Array(8).fill(0), ...u32(fakeProtectedKey.length), ...fakeProtectedKey, ...u32(e.chain.length))
      for (const d of e.chain) out.push(...utf('X.509'), ...u32(d.length), ...d)
    } else if (e.type === 'trusted') {
      out.push(...u32(2), ...utf(e.alias), ...new Array(8).fill(0), ...utf('X.509'), ...u32(e.der.length), ...e.der)
    } else {
      out.push(...u32(e.tag), ...utf(e.alias), ...new Array(8).fill(0), ...e.bytes)
    }
  }
  const body = Uint8Array.from(out)
  const pw = Buffer.alloc(password.length * 2)
  for (let i = 0; i < password.length; i++) pw.writeUInt16BE(password.charCodeAt(i), i * 2)
  const digest = createHash('sha1').update(pw).update(Buffer.from('Mighty Aphrodite', 'utf8')).update(body).digest()
  const all = new Uint8Array(body.length + 20)
  all.set(body, 0)
  all.set(digest, body.length)
  return all
}

/** BouncyCastle BKS başlığı (sürüm 2, 20 bayt tuz, yineleme) + rastgele gövde — yalnız tanınması sınanır. */
export function bksLike() {
  const b = new Uint8Array(128)
  b[3] = 2
  b[7] = 20
  b[8 + 20 + 2] = 0x04       // yineleme sayısı 1024
  for (let i = 40; i < b.length; i++) b[i] = (i * 13) & 0xff
  return b
}

/** ZIP (fflate) — `{ ad: Uint8Array|string }`. */
export function zipBytes(files, level = 6) {
  const m = {}
  // Node alanının Uint8Array'i (fflate `instanceof` denetler; jsdom'un ayrı alanı karışmasın)
  for (const [k, v] of Object.entries(files)) m[k] = new Uint8Array(typeof v === 'string' ? Buffer.from(v, 'utf8') : Buffer.from(v))
  return zipSync(m, { level })
}

export const sha256Hex = (u8) => createHash('sha256').update(u8).digest('hex').toUpperCase()
export const binary = { toU8, toBin }
