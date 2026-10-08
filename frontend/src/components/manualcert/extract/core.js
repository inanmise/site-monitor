/**
 * TARAYICIDA SERTİFİKA AYIKLAMA — çekirdek (2026-10-08, kullanıcı isteği: "keystore yüklemesi yapılmaya çalışıldığında
 * kesinlikle private key için bir yükleme vs yapmayalım").
 *
 * <p>Kural: dosya bu tarayıcıda açılır; sunucuya YALNIZ açık (public) sertifikaların DER'i (Base64) ve CSR'lerin PEM'i
 * gider. Özel anahtarlar (PEM blokları, PKCS#12 anahtar torbaları, JKS/JCEKS anahtar girdileri, ZIP içindeki .key
 * dosyaları) yalnız SAYILIR; içerikleri okunmaz, çözülmez, gönderilmez. Parola yalnız burada kullanılır.
 *
 * <p>Biçim içerikten tanınır (sunucudaki eski ayrıştırıcıyla aynı kurallar): PEM (CERTIFICATE / TRUSTED CERTIFICATE /
 * X509 CERTIFICATE / PKCS7 / CMS / CERTIFICATE REQUEST), zırhsız Base64, DER X.509, PKCS#7 (DER/PEM), PKCS#12, JKS,
 * JCEKS, ZIP (50 girdi / 5 MB açılmış / oran ≤ 200, iç içe arşiv atlanır). BKS / UBER tarayıcıda okunamaz →
 * `unsupported` + keytool yönergesi.
 *
 * <p>Saf: DOM yok, ağ yok. Ana iş parçacığında ya da Web Worker'da aynı biçimde çalışır; hiçbir zaman fırlatmaz.
 */
import { unzipSync } from 'fflate'
import {
  armor, asciiText, base64Encode, classifyDer, countPrivateKeyBlocks, isCsr, looseBase64, pemBlocks, pkcs7Certificates, x509Node,
} from './asn1.js'
import { UnsupportedAlgorithmError } from './crypto.js'
import { readJavaKeystore } from './javaKeystore.js'
import { readPkcs12 } from './pkcs12.js'

export const MAX_BYTES = 5 * 1024 * 1024
export const MAX_MB = 5
export const MAX_CERTS = 200
export const MAX_CSR = 5
export const CSR_PEM_MAX = 16 * 1024
export const ZIP_MAX_ENTRIES = 50
export const ZIP_MAX_TOTAL = 5 * 1024 * 1024
export const ZIP_MAX_RATIO = 200

/** Sunucunun kabul ettiği biçim adları (beyaz liste — backend ile aynı). */
export const WIRE_FORMATS = Object.freeze(['PEM', 'DER', 'PKCS7', 'PKCS12', 'JKS', 'JCEKS', 'ZIP', 'TEXT'])

const ARCHIVE_EXT = /\.(zip|jar|war|ear|gz|tgz|tar|7z|rar|bz2|xz)$/i

/** Yol parçalarını ve denetim karakterlerini atar; ≤ 255; boşsa null (sunucudaki `sanitizeFileName` ile aynı). */
export function sanitizeName(name) {
  if (name == null) return null
  let n = String(name).replace(/\\/g, '/')
  n = n.slice(n.lastIndexOf('/') + 1)
  // eslint-disable-next-line no-control-regex
  n = n.replace(/[\u0000-\u001f\u007f-\u009f<>:"|?*]/g, '').trim()
  if (n.length > 255) n = n.slice(n.length - 255)
  return n || null
}

function newAcc() {
  return { entries: [], csr: [], keys: 0, notes: [], needsPassword: false, passwordError: false, passwordUsed: false, unsupported: null }
}

const note = (acc, code, severity, params = {}) => acc.notes.push({ code, severity, params })

/** PEM metni. */
function fromPem(text, acc) {
  acc.keys += countPrivateKeyBlocks(text)
  for (const { label, der } of pemBlocks(text)) {
    switch (label) {
      case 'CERTIFICATE': case 'X509 CERTIFICATE': case 'X.509 CERTIFICATE':
        addDerCerts(der, acc)
        break
      case 'TRUSTED CERTIFICATE': {                       // OpenSSL: DER sertifika + güven ek bilgisi → ilk nesne
        const n = x509Node(der)
        if (n) acc.entries.push({ alias: null, key_entry: false, certs: [der.slice(n.start, n.end)] })
        break
      }
      case 'PKCS7': case 'CMS': {
        const certs = pkcs7Certificates(der)
        for (const c of certs || []) acc.entries.push({ alias: null, key_entry: false, certs: [c.slice()] })
        break
      }
      case 'CERTIFICATE REQUEST': case 'NEW CERTIFICATE REQUEST':
        addCsr(der, acc)
        break
      default: /* PUBLIC KEY, X509 CRL, DH PARAMETERS … — sertifika değil */
    }
  }
}

/** Ardışık DER X.509 sertifikaları (tek dosyada birden çok olabilir). */
function addDerCerts(der, acc) {
  let pos = 0
  while (pos < der.length) {
    const n = x509Node(der, pos)
    if (!n) break
    acc.entries.push({ alias: null, key_entry: false, certs: [der.slice(n.start, n.end)] })
    pos = n.end
  }
  return pos > 0
}

function addCsr(der, acc) {
  if (!isCsr(der) || acc.csr.length >= MAX_CSR) return
  const pem = armor('CERTIFICATE REQUEST', der)
  if (pem.length <= CSR_PEM_MAX) acc.csr.push(pem)
}

/**
 * Tek blob (dosya ya da ZIP girdisi) → biçim adı (tanınmazsa null). ZIP yalnız üst düzeyde açılır.
 * @param {boolean} allowBase64 zırhsız Base64 denemesi (özyinelemede bir kez)
 */
async function fromBlob(bytes, acc, ctx, allowBase64 = true) {
  if (!bytes.length) return null
  const text = asciiText(bytes)
  if (text != null && text.includes('-----BEGIN ')) { fromPem(text, acc); return 'PEM' }
  const b0 = bytes[0]; const b1 = bytes[1]; const b2 = bytes[2]; const b3 = bytes[3]
  if ((b0 === 0xfe && b1 === 0xed && b2 === 0xfe && b3 === 0xed) || (b0 === 0xce && b1 === 0xce && b2 === 0xce && b3 === 0xce)) {
    progress(ctx, { phase: 'keystore', format: b0 === 0xfe ? 'JKS' : 'JCEKS' })
    const ks = readJavaKeystore(bytes, ctx.password)
    acc.entries.push(...ks.entries)
    acc.keys += ks.keys
    if (ks.passwordUsed) acc.passwordUsed = true
    if (ks.integrityFailed) note(acc, 'PASSWORD_WRONG', 'warn', { format: ks.format })
    if (ks.partial) note(acc, 'KEYSTORE_PARTIAL', 'warn', { format: ks.format })
    return ks.format
  }
  if (b0 === 0x30) {
    const kind = classifyDer(bytes)
    if (kind === 'PKCS7') { for (const c of pkcs7Certificates(bytes) || []) acc.entries.push({ alias: null, key_entry: false, certs: [c.slice()] }); return 'PKCS7' }
    if (kind === 'PKCS12') { await fromPkcs12(bytes, acc, ctx); return 'PKCS12' }
    if (kind === 'X509') { addDerCerts(bytes, acc); return 'DER' }
    if (kind === 'CSR') { addCsr(bytes, acc); return 'DER' }
    if (kind === 'PRIVATE_KEY') { acc.keys++; return 'DER' }
  }
  if (looksLikeBks(bytes)) {
    acc.unsupported = acc.unsupported || { reason: 'BKS' }
    return 'BKS'
  }
  if (allowBase64 && text != null) {
    const decoded = looseBase64(text)
    if (decoded && decoded.length) return fromBlob(decoded, acc, ctx, false)
  }
  return null
}

/** BouncyCastle BKS / UBER: sürüm (1|2) + tuz uzunluğu (1..1024) + tuz + yineleme sayısı. */
function looksLikeBks(b) {
  if (b.length < 16 || b[0] || b[1] || b[2] || (b[3] !== 1 && b[3] !== 2)) return false
  const saltLen = (b[4] << 24) | (b[5] << 16) | (b[6] << 8) | b[7]
  if (saltLen < 1 || saltLen > 1024 || 8 + saltLen + 4 > b.length) return false
  const o = 8 + saltLen
  const iter = ((b[o] << 24) | (b[o + 1] << 16) | (b[o + 2] << 8) | b[o + 3]) >>> 0
  return iter > 0 && iter < 10_000_000
}

async function fromPkcs12(bytes, acc, ctx) {
  try {
    // İlerleme: parola tabanlı anahtar türetme (KDF) başında / şifre çözme başında (pkcs12.js) ve sonunda
    const r = await readPkcs12(bytes, ctx.password, ctx.subtle, (p) => progress(ctx, p))
    progress(ctx, { phase: 'pkcs12', step: 'done' })
    acc.entries.push(...r.entries)
    acc.keys += r.keys
    if (r.passwordUsed) acc.passwordUsed = true
    if (r.needsPassword) acc.needsPassword = true
    if (r.passwordError) acc.passwordError = true
  } catch (e) {
    acc.unsupported = acc.unsupported || {
      reason: e instanceof UnsupportedAlgorithmError ? 'PKCS12_ALGORITHM' : 'PKCS12_FORMAT',
      detail: e?.detail ? String(e.detail).slice(0, 80) : undefined,
    }
  }
}

/** ZIP: sınırlar sunucudakiyle aynı; her girdi ayrı ayrı açılır (bozuk bir girdi arşivin geri kalanını düşürmez). */
async function fromZip(bytes, acc, ctx) {
  const plan = []
  let count = 0
  let total = 0
  let limited = false
  try {
    unzipSync(bytes, {
      filter: (f) => {
        if (limited || /\/$/.test(f.name)) return false
        const name = sanitizeName(f.name) || ''
        if (++count > ZIP_MAX_ENTRIES) { limited = true; return false }
        if (ARCHIVE_EXT.test(name)) { note(acc, 'ZIP_SKIPPED_ENTRY', 'info', { name }); return false }
        if (total + f.originalSize > ZIP_MAX_TOTAL || (f.size > 0 && f.originalSize / f.size > ZIP_MAX_RATIO)) { limited = true; return false }
        total += f.originalSize
        plan.push({ index: count - 1, name })
        return false                                   // ilk tur yalnız plan — açma ikinci turda girdi başına
      },
    })
  } catch {
    acc.unsupported = acc.unsupported || { reason: 'ZIP_UNREADABLE' }
    return
  }
  // İlerleme: "n / N dosya" — açılacak girdi sayısı bilinir (plan), her girdiden SONRA bir adım
  progress(ctx, { phase: 'zip', done: 0, total: plan.length })
  for (const [pos, { index, name }] of plan.entries()) {
    if (pos > 0) progress(ctx, { phase: 'zip', done: pos, total: plan.length })
    let data = null
    try {
      let i = -1
      const out = unzipSync(bytes, { filter: (f) => { if (/\/$/.test(f.name)) return false; i++; return i === index } })
      data = Object.values(out)[0] || null
    } catch { data = null }
    if (!data || !data.length || (data[0] === 0x50 && data[1] === 0x4b) || (data[0] === 0x1f && data[1] === 0x8b)) {
      note(acc, 'ZIP_SKIPPED_ENTRY', 'info', { name })
      continue
    }
    const before = { certs: acc.entries.length, keys: acc.keys, csr: acc.csr.length, np: acc.needsPassword, pe: acc.passwordError, un: acc.unsupported }
    let fmt = null
    try { fmt = await fromBlob(data, acc, ctx) } catch { fmt = null }
    // Arşivdeki okunamayan tek bir girdi (BKS, desteklenmeyen PKCS#12 …) tüm arşivi düşürmez: atlanır + not
    if (acc.unsupported !== before.un) { acc.unsupported = before.un; note(acc, 'ZIP_SKIPPED_ENTRY', 'info', { name }); continue }
    const contributed = acc.entries.length > before.certs || acc.keys > before.keys || acc.csr.length > before.csr
      || acc.needsPassword !== before.np || acc.passwordError !== before.pe
    if (!fmt || !contributed) note(acc, 'ZIP_SKIPPED_ENTRY', 'info', { name })
  }
  progress(ctx, { phase: 'zip', done: plan.length, total: plan.length })
  if (limited) note(acc, 'ZIP_LIMIT', 'warn', { max_entries: ZIP_MAX_ENTRIES, max_mb: MAX_MB })
}

/**
 * İlerleme bildirimi (2026-10-08, yükleme durumu): `{ phase: 'zip', done, total }` (girdi başına), `{ phase: 'pkcs12', step:
 * 'kdf' | 'decrypt' | 'done' }` (parola tabanlı anahtar türetme / şifre çözme), `{ phase: 'keystore', format }`. Yalnız
 * sayaç ve biçim adı taşır — parola, anahtar ya da sertifika içeriği ASLA. Dinleyici hatası ayıklamayı durdurmaz.
 */
function progress(ctx, p) {
  if (typeof ctx?.onProgress !== 'function') return
  try { ctx.onProgress(p) } catch { /* dinleyici hatası yok sayılır */ }
}

/**
 * @param {{ bytes?: Uint8Array|null, name?: string|null, text?: string|null, password?: string }} input
 * @param {{ subtle?: SubtleCrypto|null, onProgress?: Function }} [opts] WebCrypto (verilmezse ortamınki; null = saf JS);
 *   `onProgress` — ilerleme bildirimleri ({@link progress})
 * @returns {Promise<object>} ayıklama sonucu — sunucuya gidecek kısmı `manualCertModel.wirePayload` seçer
 */
export async function extractCore(input = {}, opts = {}) {
  const subtle = opts.subtle !== undefined ? opts.subtle : (globalThis.crypto?.subtle || null)
  const pasted = input.bytes == null
  const password = typeof input.password === 'string' ? input.password : ''
  const bytes = pasted ? new TextEncoder().encode(String(input.text ?? '')) : input.bytes
  const acc = newAcc()
  const result = (format) => finish(acc, {
    format, file_name: pasted ? null : sanitizeName(input.name), size_bytes: bytes.length,
  })
  // Boş dosya / metin: sunucuya boş gövde gönderilmez — açıklamalı alan hatası (EMPTY)
  if (!bytes.length) { acc.unsupported = { reason: 'EMPTY' }; return result(pasted ? 'TEXT' : null) }
  if (bytes.length > MAX_BYTES) { acc.unsupported = { reason: 'TOO_LARGE', max_mb: MAX_MB }; return result(pasted ? 'TEXT' : null) }
  const ctx = { password, subtle, onProgress: opts.onProgress }
  let format = null
  try {
    if (!pasted && bytes[0] === 0x50 && bytes[1] === 0x4b && (bytes[2] === 3 || bytes[2] === 5)) {
      await fromZip(bytes, acc, ctx)
      format = 'ZIP'
    } else {
      format = await fromBlob(bytes, acc, ctx)
    }
  } catch {
    format = null
  }
  if (format === 'BKS') format = null
  if (!format && !acc.unsupported) acc.unsupported = { reason: 'UNKNOWN' }
  return result(pasted && format ? 'TEXT' : format)
}

function finish(acc, head) {
  const certificateCount = acc.entries.reduce((n, e) => n + e.certs.length, 0)
  if (!acc.unsupported && certificateCount > MAX_CERTS) acc.unsupported = { reason: 'TOO_MANY_CERTS', max: MAX_CERTS }
  const failed = !!acc.unsupported || acc.needsPassword || acc.passwordError
  return {
    ...head,
    entries: failed ? [] : acc.entries.map((e) => ({ alias: e.alias ?? null, key_entry: !!e.key_entry, certs: e.certs.map(base64Encode) })),
    csr_pem: failed ? [] : acc.csr,
    private_keys_removed: acc.keys,
    certificate_count: failed ? 0 : certificateCount,
    needs_password: acc.needsPassword && !acc.passwordError,
    password_error: acc.passwordError,
    password_used: acc.passwordUsed,
    unsupported: acc.unsupported,
    notes: acc.notes,
  }
}
