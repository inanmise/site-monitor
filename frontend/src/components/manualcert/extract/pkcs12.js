/**
 * PKCS#12 (.pfx / .p12) — tarayıcıda YALNIZ SERTİFİKA torbaları okunur (2026-10-08). Özel anahtar torbaları
 * (`keyBag`, `pkcs8ShroudedKeyBag`) ÇÖZÜLMEZ, okunmaz; yalnız sayılır ve `localKeyId` ile hangi sertifikanın anahtar
 * girdisinin yaprağı olduğu işaretlenir. Parola bu modülden çıkmaz.
 *
 * <p>Kapsam: bütünlük MAC'i (SHA-1 / SHA-256 / SHA-384 / SHA-512, RFC 7292 KDF) doğrulanır — yanlış parola böyle
 * anlaşılır. Şifreli içerik: PBES2 (PBKDF2 + HMAC-SHA1/256/384/512, AES-128/192/256-CBC, 3DES-CBC, DES-CBC — OpenSSL 3
 * ve JDK keytool varsayılanları) ve eski PKCS#12 PBE (SHA1 + 3DES, 2-anahtarlı 3DES, RC2-40, RC2-128 — `openssl -legacy`,
 * eski JDK / Windows). Desteklenmeyen (RC4, açık anahtarlı bütünlük / zarflı içerik) → {@link UnsupportedAlgorithmError}.
 */
import { Asn1Error, T, UNIVERSAL, children, equalBytes, int, is, isCtx, isSeq, isX509, octets, oid, readTlv, toHex, bmpString } from './asn1.js'
import {
  BadPasswordError, UnsupportedAlgorithmError, aesCbcDecrypt, bmpPassword, desCbcDecrypt, hmac, pbkdf2, pkcs12Kdf, rc2CbcDecrypt,
  utf8Password,
} from './crypto.js'

const OID = {
  data: '1.2.840.113549.1.7.1',
  encryptedData: '1.2.840.113549.1.7.6',
  keyBag: '1.2.840.113549.1.12.10.1.1',
  shroudedKeyBag: '1.2.840.113549.1.12.10.1.2',
  certBag: '1.2.840.113549.1.12.10.1.3',
  safeContentsBag: '1.2.840.113549.1.12.10.1.6',
  x509Certificate: '1.2.840.113549.1.9.22.1',
  friendlyName: '1.2.840.113549.1.9.20',
  localKeyId: '1.2.840.113549.1.9.21',
  pbes2: '1.2.840.113549.1.5.13',
  pbkdf2: '1.2.840.113549.1.5.12',
}
const DIGEST_BY_OID = {
  '1.3.14.3.2.26': 'SHA-1',
  '2.16.840.1.101.3.4.2.1': 'SHA-256',
  '2.16.840.1.101.3.4.2.2': 'SHA-384',
  '2.16.840.1.101.3.4.2.3': 'SHA-512',
}
const PRF_BY_OID = {
  '1.2.840.113549.2.7': 'SHA-1',
  '1.2.840.113549.2.9': 'SHA-256',
  '1.2.840.113549.2.10': 'SHA-384',
  '1.2.840.113549.2.11': 'SHA-512',
}
const PBES2_CIPHERS = {
  '2.16.840.1.101.3.4.1.2': { kind: 'aes', len: 16 },
  '2.16.840.1.101.3.4.1.22': { kind: 'aes', len: 24 },
  '2.16.840.1.101.3.4.1.42': { kind: 'aes', len: 32 },
  '1.2.840.113549.3.7': { kind: 'des', len: 24 },
  '1.3.14.3.2.7': { kind: 'des', len: 8 },
}
/** Eski PKCS#12 PBE (RFC 7292 Ek C) — anahtar ve IV SHA-1 KDF ile. */
const LEGACY_PBE = {
  '1.2.840.113549.1.12.1.3': { kind: 'des', len: 24 },          // pbeWithSHAAnd3-KeyTripleDES-CBC
  '1.2.840.113549.1.12.1.4': { kind: 'des', len: 16 },          // pbeWithSHAAnd2-KeyTripleDES-CBC
  '1.2.840.113549.1.12.1.5': { kind: 'rc2', len: 16, bits: 128 }, // pbeWithSHAAnd128BitRC2-CBC
  '1.2.840.113549.1.12.1.6': { kind: 'rc2', len: 5, bits: 40 },   // pbeWithSHAAnd40BitRC2-CBC
}
/** Kötü niyetli / bozuk dosyada sonsuz bekleme olmasın (yerleşik PBKDF2 1 milyonu ~1 sn'de bitirir). */
const MAX_ITERATIONS = 5_000_000
const MAX_BAGS = 2000

export class Pkcs12FormatError extends Error {
  constructor(detail) { super(`pkcs12: ${detail}`); this.name = 'Pkcs12FormatError'; this.detail = detail }
}

/**
 * @param {Uint8Array} der      PFX baytları
 * @param {string}     password kullanıcının girdiği parola ('' = girilmedi)
 * @param {SubtleCrypto|null} subtle WebCrypto (yoksa saf JS)
 * @param {Function} [onProgress] ilerleme (2026-10-08): `{ phase: 'pkcs12', step: 'kdf' }` bütünlük anahtarı türetilirken,
 *   `{ phase: 'pkcs12', step: 'decrypt' }` şifreli sertifika torbası çözülürken — parola / içerik TAŞIMAZ
 * @returns {Promise<{ entries: Array<{alias, key_entry, certs: Uint8Array[]}>, keys: number, needsPassword: boolean,
 *   passwordError: boolean, passwordUsed: boolean }>}
 */
export async function readPkcs12(der, password, subtle, onProgress) {
  const report = (step) => { if (typeof onProgress === 'function') { try { onProgress({ phase: 'pkcs12', step }) } catch { /* yok say */ } } }
  const pfx = readTlv(der)
  const top = children(der, pfx)
  if (!is(top[0], UNIVERSAL, T.INTEGER) || int(der, top[0]) !== 3 || !isSeq(top[1])) throw new Pkcs12FormatError('version')
  const authSafeCi = children(der, top[1])
  if (oid(der, authSafeCi[0]) !== OID.data || !isCtx(authSafeCi[1], 0)) throw new Pkcs12FormatError('integrity mode')
  const authSafe = octets(der, children(der, authSafeCi[1])[0])
  const macData = top[2] && isSeq(top[2]) ? top[2] : null

  const given = typeof password === 'string' && password.length > 0
  // Parola girilmediyse boş parolanın iki yaygın kodlaması denenir (NUL sonlandırıcılı / hiç bayt yok)
  const candidates = given ? [password] : ['', null]

  // 1) Bütünlük MAC'i — hangi parola adayının doğru olduğunu söyler
  let verified
  let macChecked = false
  if (macData) {
    const m = children(der, macData)
    const digestInfo = children(der, m[0])
    const hashName = DIGEST_BY_OID[oid(der, children(der, digestInfo[0])[0])]
    if (hashName) {
      macChecked = true
      const expected = octets(der, digestInfo[1])
      const salt = octets(der, m[1])
      const iterations = m[2] ? int(der, m[2]) : 1
      if (iterations < 1 || iterations > MAX_ITERATIONS) throw new UnsupportedAlgorithmError('mac iterations')
      report('kdf')
      for (const cand of candidates) {
        const key = pkcs12Kdf(hashName, bmpPassword(cand), salt, 3, iterations, expected.length)
        if (equalBytes(await hmac(hashName, key, authSafe, subtle), expected)) { verified = cand; break }
      }
    }
  }
  if (macChecked && verified === undefined && given) return { entries: [], keys: 0, needsPassword: false, passwordError: true, passwordUsed: true }

  // 2) İçerikler: düz (data) olanlar doğrudan; şifreli (encryptedData) olanlar YALNIZ sertifika torbaları için çözülür
  const certs = []
  const keyIds = []
  let keys = 0
  let skippedEncrypted = false
  let bags = 0
  const walk = (bytes, depth) => {
    const seq = readTlv(bytes)
    if (!isSeq(seq) || depth > 4) throw new Asn1Error('safe contents')
    for (const bag of children(bytes, seq)) {
      if (++bags > MAX_BAGS) throw new Pkcs12FormatError('too many bags')
      const b = children(bytes, bag)
      const bagId = oid(bytes, b[0])
      const attrs = b[2] ? readAttributes(bytes, b[2]) : {}
      if (bagId === OID.keyBag || bagId === OID.shroudedKeyBag) {
        keys++                                                    // anahtar ÇÖZÜLMEZ / OKUNMAZ — yalnız sayılır
        if (attrs.localKeyId) keyIds.push({ id: attrs.localKeyId, name: attrs.friendlyName || null })
        continue
      }
      if (!isCtx(b[1], 0)) continue
      const value = children(bytes, b[1])[0]
      if (bagId === OID.certBag) {
        const cb = children(bytes, value)
        if (oid(bytes, cb[0]) !== OID.x509Certificate || !isCtx(cb[1], 0)) continue
        const certDer = octets(bytes, children(bytes, cb[1])[0])
        if (isX509(certDer)) certs.push({ der: certDer.slice(), attrs })
      } else if (bagId === OID.safeContentsBag) {
        walk(bytes.subarray(value.start, value.end), depth + 1)
      }
    }
  }
  let usedPassword = verified
  let decryptReported = false
  const contents = readTlv(authSafe)
  for (const ci of children(authSafe, contents)) {
    const c = children(authSafe, ci)
    const type = oid(authSafe, c[0])
    if (type === OID.data) {
      if (isCtx(c[1], 0)) walk(octets(authSafe, children(authSafe, c[1])[0]), 0)
      continue
    }
    if (type !== OID.encryptedData) { skippedEncrypted = true; continue }   // zarflı içerik vb. — desteklenmez
    if (macChecked && verified === undefined) { skippedEncrypted = true; continue }
    const ed = children(authSafe, children(authSafe, c[1])[0])
    const eci = children(authSafe, ed[1])
    if (!eci[2] || !isCtx(eci[2], 0)) continue
    const encrypted = octets(authSafe, eci[2])
    if (!decryptReported) { decryptReported = true; report('decrypt') }
    const tries = verified !== undefined ? [verified] : candidates
    let plain = null
    for (const cand of tries) {
      try {
        plain = await decryptContent(authSafe, eci[1], cand, encrypted, subtle)
        const probe = readTlv(plain)
        if (!isSeq(probe) || probe.end !== plain.length) throw new BadPasswordError()
        usedPassword = cand
        break
      } catch (e) {
        if (e instanceof UnsupportedAlgorithmError) throw e
        plain = null                                              // yanlış parola / bozuk — sonraki aday
      }
    }
    if (!plain) {
      if (given) return { entries: [], keys, needsPassword: false, passwordError: true, passwordUsed: true }
      skippedEncrypted = true
      continue
    }
    walk(plain, 0)
  }

  if (!certs.length && skippedEncrypted) {
    return { entries: [], keys, needsPassword: !given, passwordError: given, passwordUsed: given }
  }

  // 3) Girdiler: anahtarla aynı localKeyId'yi taşıyan sertifika = anahtar girdisinin yaprağı (takma adı anahtarınki)
  const entries = certs.map(({ der: d, attrs }) => {
    const key = attrs.localKeyId ? keyIds.find((k) => k.id === attrs.localKeyId) : null
    return { alias: (key?.name || attrs.friendlyName || null), key_entry: !!key, certs: [d] }
  })
  return { entries, keys, needsPassword: false, passwordError: false, passwordUsed: given && usedPassword === password }
}

/** SET OF Attribute → { friendlyName, localKeyId (onaltılık) }. */
function readAttributes(buf, setNode) {
  const out = {}
  try {
    for (const a of children(buf, setNode)) {
      const k = children(buf, a)
      const id = oid(buf, k[0])
      const vals = children(buf, k[1])
      if (!vals.length) continue
      if (id === OID.friendlyName && is(vals[0], UNIVERSAL, T.BMP)) out.friendlyName = bmpString(buf, vals[0]).slice(0, 255)
      else if (id === OID.localKeyId && is(vals[0], UNIVERSAL, T.OCTET_STRING)) out.localKeyId = toHex(octets(buf, vals[0]))
    }
  } catch { /* öznitelik okunamadı — yok say */ }
  return out
}

async function decryptContent(buf, algNode, password, data, subtle) {
  const alg = children(buf, algNode)
  const algOid = oid(buf, alg[0])
  if (algOid === OID.pbes2) {
    const params = children(buf, alg[1])
    const kdf = children(buf, params[0])
    if (oid(buf, kdf[0]) !== OID.pbkdf2) throw new UnsupportedAlgorithmError('kdf')
    const kp = children(buf, kdf[1])
    if (!is(kp[0], UNIVERSAL, T.OCTET_STRING)) throw new UnsupportedAlgorithmError('salt source')
    const salt = octets(buf, kp[0])
    const iterations = int(buf, kp[1])
    if (iterations < 1 || iterations > MAX_ITERATIONS) throw new UnsupportedAlgorithmError('iterations')
    let prf = 'SHA-1'
    for (const extra of kp.slice(2)) {
      if (isSeq(extra)) {
        prf = PRF_BY_OID[oid(buf, children(buf, extra)[0])]
        if (!prf) throw new UnsupportedAlgorithmError('prf')
      }
    }
    const enc = children(buf, params[1])
    const cipher = PBES2_CIPHERS[oid(buf, enc[0])]
    if (!cipher || !enc[1] || !is(enc[1], UNIVERSAL, T.OCTET_STRING)) throw new UnsupportedAlgorithmError('cipher')
    const iv = octets(buf, enc[1])
    const key = await pbkdf2(prf, utf8Password(password), salt, iterations, cipher.len, subtle)
    return cipher.kind === 'aes' ? aesCbcDecrypt(key, iv, data, subtle) : desCbcDecrypt(key, iv, data)
  }
  const legacy = LEGACY_PBE[algOid]
  if (!legacy) throw new UnsupportedAlgorithmError(algOid)
  const p = children(buf, alg[1])
  const salt = octets(buf, p[0])
  const iterations = int(buf, p[1])
  if (iterations < 1 || iterations > MAX_ITERATIONS) throw new UnsupportedAlgorithmError('iterations')
  const pw = bmpPassword(password)
  const key = pkcs12Kdf('SHA-1', pw, salt, 1, iterations, legacy.len)
  const iv = pkcs12Kdf('SHA-1', pw, salt, 2, iterations, 8)
  return legacy.kind === 'des' ? desCbcDecrypt(key, iv, data) : rc2CbcDecrypt(key, legacy.bits, iv, data)
}
