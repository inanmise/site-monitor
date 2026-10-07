/**
 * PKCS#12 için gereken KRİPTO ilkelleri — YALNIZ TARAYICIDA (2026-10-08). Parola ve özel anahtar bu modülden dışarı
 * çıkmaz; sunucuya hiçbir şey göndermez.
 *
 * <p>Kütüphane: node-forge'un yalnız gereken alt modülleri (özetler, HMAC, PBKDF2, AES / 3DES / RC2). RSA, X.509, TLS
 * kodu PAKETE GİRMEZ (forge'un RSA imza doğrulamasındaki bilinen açık bu kodu hiç yüklemez). WebCrypto varsa (güvenli
 * bağlam) PBKDF2 / HMAC / AES-CBC yerleşik ve eşzamansız çalışır; yoksa (ör. düz http) forge'un saf JS karşılığı.
 * DES ailesi ve RC2 WebCrypto'da yok → her zaman forge.
 *
 * <p>Şifre çözme dolgusu SIKI denetlenir (PKCS#7: tüm dolgu baytları eşit) — yanlış parola {@link BadPasswordError}.
 */
import forge from 'node-forge/lib/forge.js'
import 'node-forge/lib/util.js'
import 'node-forge/lib/md.js'
import 'node-forge/lib/sha1.js'
import 'node-forge/lib/sha256.js'
import 'node-forge/lib/sha512.js'
import 'node-forge/lib/hmac.js'
import 'node-forge/lib/pbkdf2.js'
import 'node-forge/lib/cipher.js'
import 'node-forge/lib/cipherModes.js'
import 'node-forge/lib/aes.js'
import 'node-forge/lib/des.js'
import 'node-forge/lib/rc2.js'

export class BadPasswordError extends Error {
  constructor() { super('bad password'); this.name = 'BadPasswordError' }
}
export class UnsupportedAlgorithmError extends Error {
  constructor(detail) { super(`unsupported: ${detail}`); this.name = 'UnsupportedAlgorithmError'; this.detail = detail }
}

/** Özet adı → { forge oluşturucu, WebCrypto adı, çıktı/blok boyu }. */
export const HASHES = Object.freeze({
  'SHA-1': { create: () => forge.md.sha1.create(), web: 'SHA-1', size: 20, block: 64 },
  'SHA-256': { create: () => forge.md.sha256.create(), web: 'SHA-256', size: 32, block: 64 },
  'SHA-384': { create: () => forge.md.sha384.create(), web: 'SHA-384', size: 48, block: 128 },
  'SHA-512': { create: () => forge.md.sha512.create(), web: 'SHA-512', size: 64, block: 128 },
})

const toBin = (u8) => {
  let s = ''
  for (let i = 0; i < u8.length; i += 8192) s += String.fromCharCode.apply(null, u8.subarray(i, i + 8192))
  return s
}
const fromBin = (s) => {
  const out = new Uint8Array(s.length)
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i) & 0xff
  return out
}

/** Parola → BMPString (UTF-16BE + 2 bayt NUL sonlandırıcı); null → boş (hiç bayt yok) — RFC 7292 B.1. */
export function bmpPassword(str) {
  if (str == null) return new Uint8Array(0)
  const out = new Uint8Array(str.length * 2 + 2)
  for (let i = 0; i < str.length; i++) { const c = str.charCodeAt(i); out[i * 2] = c >> 8; out[i * 2 + 1] = c & 0xff }
  return out
}

/** Parola → UTF-8 (PBES2 / PBKDF2: OpenSSL 3 ve JDK böyle kodlar). */
export const utf8Password = (str) => new TextEncoder().encode(str ?? '')

function repeatTo(src, len) {
  const out = new Uint8Array(len)
  if (!src.length) return out
  for (let i = 0; i < len; i++) out[i] = src[i % src.length]
  return out
}

/**
 * RFC 7292 Ek B.2 anahtar türetme (MAC anahtarı id=3, eski PBE anahtarı id=1 / IV id=2). Eşzamanlı, saf JS (forge özeti);
 * yinelemeler kısa girdiyi özetler — 10 000 yineleme birkaç ms.
 */
export function pkcs12Kdf(hashName, pw, salt, id, iterations, n) {
  const h = HASHES[hashName]
  if (!h) throw new UnsupportedAlgorithmError(hashName)
  const u = h.size
  const v = h.block
  const D = toBin(new Uint8Array(v).fill(id))
  const S = repeatTo(salt, v * Math.ceil(salt.length / v))
  const P = repeatTo(pw, v * Math.ceil(pw.length / v))
  const I = new Uint8Array(S.length + P.length)
  I.set(S, 0)
  I.set(P, S.length)
  const c = Math.ceil(n / u)
  const out = new Uint8Array(c * u)
  const md = h.create()
  for (let i = 0; i < c; i++) {
    md.start()
    md.update(D)
    md.update(toBin(I))
    let A = md.digest().getBytes()
    for (let r = 1; r < iterations; r++) {
      md.start()
      md.update(A)
      A = md.digest().getBytes()
    }
    const Ab = fromBin(A)
    out.set(Ab, i * u)
    if (i + 1 < c) {
      const B = repeatTo(Ab, v)
      for (let j = 0; j < I.length; j += v) {           // I_j = (I_j + B + 1) mod 2^(8v)
        let carry = 1
        for (let k = v - 1; k >= 0; k--) {
          const s = I[j + k] + B[k] + carry
          I[j + k] = s & 0xff
          carry = s >> 8
        }
      }
    }
  }
  return out.subarray(0, n)
}

/** HMAC (WebCrypto varsa yerleşik; yoksa forge). */
export async function hmac(hashName, key, data, subtle) {
  const h = HASHES[hashName]
  if (!h) throw new UnsupportedAlgorithmError(hashName)
  if (subtle) {
    try {
      const k = await subtle.importKey('raw', key, { name: 'HMAC', hash: h.web }, false, ['sign'])
      return new Uint8Array(await subtle.sign('HMAC', k, data))
    } catch { /* forge'a düş */ }
  }
  const mac = forge.hmac.create()
  mac.start(h.create(), toBin(key))
  mac.update(toBin(data))
  return fromBin(mac.digest().getBytes())
}

/** PBKDF2 (WebCrypto varsa yerleşik; yoksa forge saf JS). */
export async function pbkdf2(hashName, pw, salt, iterations, len, subtle) {
  const h = HASHES[hashName]
  if (!h) throw new UnsupportedAlgorithmError(hashName)
  if (subtle) {
    try {
      const k = await subtle.importKey('raw', pw, 'PBKDF2', false, ['deriveBits'])
      return new Uint8Array(await subtle.deriveBits({ name: 'PBKDF2', salt, iterations, hash: h.web }, k, len * 8))
    } catch { /* boş parola vb. — forge'a düş */ }
  }
  return fromBin(forge.pkcs5.pbkdf2(toBin(pw), toBin(salt), iterations, len, h.create()))
}

/** Sıkı PKCS#7 dolgu kaldırma — bozuksa yanlış parola. */
function unpad(out, block) {
  const n = out.length
  if (!n || n % block) throw new BadPasswordError()
  const p = out[n - 1]
  if (p < 1 || p > block) throw new BadPasswordError()
  for (let i = n - p; i < n; i++) if (out[i] !== p) throw new BadPasswordError()
  return out.subarray(0, n - p)
}

function forgeDecrypt(name, key, iv, data, block) {
  if (!data.length || data.length % block) throw new BadPasswordError()
  const d = forge.cipher.createDecipher(name, toBin(key))
  d.start({ iv: toBin(iv) })
  d.update(forge.util.createBuffer(toBin(data)))
  if (!d.finish(() => true)) throw new BadPasswordError()   // dolgu kaldırma kapalı — aşağıda SIKI yapılır
  return unpad(fromBin(d.output.getBytes()), block)
}

/** AES-CBC çöz (WebCrypto varsa yerleşik — dolgu hatası yanlış parola sayılır). */
export async function aesCbcDecrypt(key, iv, data, subtle) {
  if (!data.length || data.length % 16) throw new BadPasswordError()
  if (subtle) {
    let k = null
    try { k = await subtle.importKey('raw', key, 'AES-CBC', false, ['decrypt']) } catch { k = null }
    if (k) {
      try {
        return new Uint8Array(await subtle.decrypt({ name: 'AES-CBC', iv }, k, data))
      } catch {
        throw new BadPasswordError()
      }
    }
  }
  return forgeDecrypt('AES-CBC', key, iv, data, 16)
}

/** 3DES-CBC (24 bayt; 16 bayt verilirse K1K2K1) ya da tek DES-CBC (8 bayt). */
export function desCbcDecrypt(key, iv, data) {
  let k = key
  if (k.length === 16) { k = new Uint8Array(24); k.set(key, 0); k.set(key.subarray(0, 8), 16) }
  return forgeDecrypt(k.length === 8 ? 'DES-CBC' : '3DES-CBC', k, iv, data, 8)
}

/** RC2-CBC (`bits` etkin anahtar biti: 40 / 128). */
export function rc2CbcDecrypt(key, bits, iv, data) {
  if (!data.length || data.length % 8) throw new BadPasswordError()
  const d = forge.rc2.createDecryptionCipher(toBin(key), bits)
  d.start(toBin(iv), null)
  d.update(forge.util.createBuffer(toBin(data)))
  if (!d.finish(() => true)) throw new BadPasswordError()
  return unpad(fromBin(d.output.getBytes()), 8)
}

/** JKS / JCEKS bütünlük özeti: SHA-1(parola UTF-16BE ‖ "Mighty Aphrodite" ‖ veri). Eşzamanlı. */
export function javaKeystoreDigest(password, data) {
  const md = forge.md.sha1.create()
  let pw = ''
  for (let i = 0; i < password.length; i++) { const c = password.charCodeAt(i); pw += String.fromCharCode(c >> 8, c & 0xff) }
  md.update(pw)
  md.update('Mighty Aphrodite')
  md.update(toBin(data))
  return fromBin(md.digest().getBytes())
}
