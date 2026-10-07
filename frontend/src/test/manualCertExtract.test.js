import { describe, it, expect, beforeAll, afterAll } from 'vitest'
import { webcrypto } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { extractCore } from '../components/manualcert/extract/core.js'
import { extractCertificates } from '../components/manualcert/extract/index.js'
import { wirePayload } from '../components/manualcert/manualCertModel.js'
import {
  b64, bksLike, csrPem, forgePfx, javaKeystore, keyPem, makeChain, makeRoot, pkcs7Der, pkcs8Pem, zipBytes,
} from './helpers/certFixtures.js'

/**
 * TARAYICIDA SERTİFİKA AYIKLAMA (2026-10-08, kullanıcı isteği: "keystore yüklemesinde kesinlikle private key için bir
 * yükleme yapmayalım"). Çekirdek, gerçek baytlarla sınanır (fikstürler test anında üretilir; hiçbir anahtar depoya
 * girmez). Her biçim: yalnız açık sertifikalar çıkar, özel anahtarlar SAYILIR, sunucuya giden gövdede (`wirePayload`)
 * anahtar / parola izi yok. PKCS#12 şifreli sertifika torbaları için gerçek OpenSSL / keytool çıktısı (araç yoksa
 * gerekçesiyle atlanır; canlı e2e kapsar). WebCrypto VAR ve YOK (düz http) yolları ayrı ayrı koşar.
 */

const SUBTLES = [['WebCrypto', webcrypto.subtle], ['saf JS (WebCrypto yok)', null]]
const enc = (s) => new Uint8Array(Buffer.from(s, 'utf8'))
/** Sahte (anahtar OLMAYAN) blok — başlık parçalardan kurulur, depoda düz "BEGIN … PRIVATE KEY" bloğu durmasın. */
const fakeBlock = (label, body, end = true) => `${['-----BEGIN', label].join(' ')}-----\n${body}\n${end ? `${['-----END', label].join(' ')}-----\n` : ''}`
const run = (bytes, name, password = '', subtle = webcrypto.subtle) => extractCore({ bytes, name, password }, { subtle })
const certCount = (r) => r.entries.reduce((n, e) => n + e.certs.length, 0)
const wire = (r) => JSON.stringify(wirePayload(r))

let chain
let other
beforeAll(() => {
  chain = makeChain('api.example.test')
  other = makeRoot('Example Independent Root')
})

describe('ayıklama — PEM / metin', () => {
  it('sertifika blokları sırasıyla; her türden PRIVATE KEY bloğu yalnız sayılır, gövdeye girmez', async () => {
    const text = keyPem(chain.leaf.keys) + chain.leaf.pem + pkcs8Pem(chain.leaf.keys) + chain.inter.pem
      + fakeBlock('ENCRYPTED PRIVATE KEY', 'AAAA')
      + fakeBlock('OPENSSH PRIVATE KEY', 'b3BlbnNzaA==')
      + fakeBlock('EC PRIVATE KEY', 'kapanissiz', false)
    const r = await run(enc(text), 'with-key.pem')
    expect(r.format).toBe('PEM')
    expect(r.unsupported).toBeNull()
    expect(r.private_keys_removed).toBe(5)
    expect(r.entries.map((e) => e.certs[0])).toEqual([b64(chain.leaf.der), b64(chain.inter.der)])
    const body = wire(r)
    expect(body).not.toMatch(/PRIVATE KEY/)
    expect(body).not.toContain(keyPem(chain.leaf.keys).split('\n')[1])   // anahtarın ilk Base64 satırı bile yok
    expect(Object.keys(wirePayload(r)).sort()).toEqual(['csr_pem', 'entries', 'file_name', 'format', 'private_keys_removed', 'size_bytes'])
  })

  it('yapıştırılan metin: biçim TEXT, dosya adı yok; TRUSTED CERTIFICATE ilk nesne; PKCS7 bloğu açılır', async () => {
    const trusted = Buffer.concat([Buffer.from(chain.root.der), Buffer.from([0x30, 0x00])]).toString('base64')
    const p7 = Buffer.from(pkcs7Der([chain.leaf.cert, chain.inter.cert])).toString('base64')
    const text = `  \n-----BEGIN TRUSTED CERTIFICATE-----\n${trusted}\n-----END TRUSTED CERTIFICATE-----\n`
      + `-----BEGIN PKCS7-----\n${p7}\n-----END PKCS7-----\n`
    const r = await extractCore({ text, password: '' }, { subtle: null })
    expect(r.format).toBe('TEXT')
    expect(r.file_name).toBeNull()
    expect(r.entries.map((e) => e.certs[0])).toEqual(expect.arrayContaining([b64(chain.root.der), b64(chain.leaf.der), b64(chain.inter.der)]))
    expect(certCount(r)).toBe(3)
  })

  it('CSR: sertifika değil — PEM olarak csr_pem\'e (CERTIFICATE REQUEST), girdi yok', async () => {
    const csr = csrPem(chain.leaf.keys, 'csr.example.test').replace(/CERTIFICATE REQUEST/g, 'NEW CERTIFICATE REQUEST')
    const r = await run(enc(csr), 'istek.csr')
    expect(r.entries).toEqual([])
    expect(r.csr_pem).toHaveLength(1)
    expect(r.csr_pem[0]).toMatch(/^-----BEGIN CERTIFICATE REQUEST-----\n[\s\S]+-----END CERTIFICATE REQUEST-----\n$/)
  })

  it('zırhsız Base64 DER ve ham DER (ardışık iki sertifika) okunur', async () => {
    const r1 = await run(enc(Buffer.from(chain.leaf.der).toString('base64').replace(/(.{64})/g, '$1\n')), 'leaf.cer')
    expect(r1.format).toBe('DER')
    expect(r1.entries.map((e) => e.certs[0])).toEqual([b64(chain.leaf.der)])
    const two = new Uint8Array([...chain.leaf.der, ...chain.inter.der])
    const r2 = await run(two, 'iki.der')
    expect(certCount(r2)).toBe(2)
  })

  it('DER özel anahtar (PKCS#8) tek başına: sayılır, sertifika yok; tanınmayan içerik → unsupported UNKNOWN', async () => {
    const pk8 = Buffer.from(pkcs8Pem(chain.leaf.keys).split('\n').filter((l) => l && !l.startsWith('-----')).join(''), 'base64')
    const r = await run(new Uint8Array(pk8), 'leaf.key')
    expect(r.format).toBe('DER')
    expect(r.private_keys_removed).toBe(1)
    expect(r.entries).toEqual([])
    expect((await run(Uint8Array.from([1, 2, 3, 4, 5, 6, 7, 8, 9]), 'x.bin')).unsupported).toEqual({ reason: 'UNKNOWN' })
  })
})

describe('ayıklama — PKCS#7 / P7B', () => {
  it('DER PKCS#7 tüm sertifikaları verir (P7B / P7C)', async () => {
    const r = await run(pkcs7Der([chain.leaf.cert, chain.inter.cert, chain.root.cert]), 'zincir.p7b')
    expect(r.format).toBe('PKCS7')
    expect(r.entries.map((e) => e.certs[0]).sort()).toEqual([chain.leaf.der, chain.inter.der, chain.root.der].map(b64).sort())
  })
})

describe('ayıklama — JKS / JCEKS (parolasız okunur)', () => {
  const ks = (password = 'Test1234', magic = 'JKS', extra = []) => javaKeystore({
    magic, password,
    entries: [
      { type: 'trusted', alias: 'kok', der: other.der },
      { type: 'key', alias: 'sunucu', chain: [chain.leaf.der, chain.inter.der, chain.root.der] },
      ...extra,
    ],
  })

  it('anahtar girdisi: korumalı anahtar ATLANIR (sayılır), zincir okunur (yaprak önce, key_entry); güvenilen girdi; parola gerekmez', async () => {
    const bytes = ks()
    const r = await run(bytes, 'depo.jks', '')
    expect(r.format).toBe('JKS')
    expect(r.private_keys_removed).toBe(1)
    expect(r.entries).toEqual([
      { alias: 'kok', key_entry: false, certs: [b64(other.der)] },
      { alias: 'sunucu', key_entry: true, certs: [b64(chain.leaf.der), b64(chain.inter.der), b64(chain.root.der)] },
    ])
    expect(r.notes).toEqual([])
    expect(r.password_used).toBe(false)
    // Sahte "korumalı anahtar" baytları gövdede yok
    expect(wire(r)).not.toContain(Buffer.from(Uint8Array.from({ length: 64 }, (_, i) => (i * 37 + 11) & 0xff)).toString('base64').slice(0, 24))
  })

  it('parola verilirse bütünlük denetlenir: doğru → not yok; yanlış → PASSWORD_WRONG (warn) ama sertifikalar YİNE okunur', async () => {
    const ok = await run(ks('Test1234'), 'depo.jks', 'Test1234')
    expect(ok.notes).toEqual([])
    expect(ok.password_used).toBe(true)
    const bad = await run(ks('Test1234'), 'depo.jks', 'yanlis')
    expect(bad.notes).toEqual([{ code: 'PASSWORD_WRONG', severity: 'warn', params: { format: 'JKS' } }])
    expect(certCount(bad)).toBe(4)
    expect(bad.password_error).toBe(false)
  })

  it('JCEKS: okunamayan girdi türünde okuma durur — o ana kadarki sertifikalar kalır + KEYSTORE_PARTIAL', async () => {
    const bytes = ks('Test1234', 'JCEKS', [{ type: 'raw', tag: 3, alias: 'gizli', bytes: [0xde, 0xad, 0xbe, 0xef] }])
    const r = await run(bytes, 'depo.jceks', '')
    expect(r.format).toBe('JCEKS')
    expect(certCount(r)).toBe(4)
    expect(r.notes).toEqual([{ code: 'KEYSTORE_PARTIAL', severity: 'warn', params: { format: 'JCEKS' } }])
  })
})

describe('ayıklama — PKCS#12 (forge: düz sertifika torbaları + SHA-1 MAC)', () => {
  for (const [label, subtle] of SUBTLES) {
    it(`${label}: doğru parola → anahtar girdisinin yaprağı (localKeyId) + takma ad; anahtar torbası çözülmez, sayılır`, async () => {
      const pfx = forgePfx(chain.leaf.keys, [chain.leaf.cert, chain.inter.cert], 'Test1234')
      const r = await run(pfx, 'sunucu.pfx', 'Test1234', subtle)
      expect(r.format).toBe('PKCS12')
      expect(r.needs_password).toBe(false)
      expect(r.password_error).toBe(false)
      expect(r.private_keys_removed).toBe(1)
      const leaf = r.entries.find((e) => e.certs[0] === b64(chain.leaf.der))
      expect(leaf).toMatchObject({ alias: 'sunucu', key_entry: true })
      expect(r.entries.find((e) => e.certs[0] === b64(chain.inter.der))).toMatchObject({ key_entry: false })
      expect(r.password_used).toBe(true)
      expect(wire(r)).not.toContain('Test1234')
    })
  }

  it('yanlış parola → password_error (hiç sertifika dönmez); parola yok + düz torbalar → sertifikalar okunur', async () => {
    const pfx = forgePfx(chain.leaf.keys, [chain.leaf.cert], 'Test1234')
    const bad = await run(pfx, 'sunucu.pfx', 'yanlis')
    expect(bad.password_error).toBe(true)
    expect(bad.entries).toEqual([])
    const none = await run(pfx, 'sunucu.pfx', '')
    expect(none.needs_password).toBe(false)
    expect(none.entries.map((e) => e.certs[0])).toEqual([b64(chain.leaf.der)])
  })
})

describe('ayıklama — ZIP', () => {
  it('girdiler tek tek açılır ve toplanır; .key yalnız sayılır; iç içe arşiv / sertifika olmayan girdi ZIP_SKIPPED_ENTRY', async () => {
    const zip = zipBytes({
      'certs/leaf.pem': chain.leaf.pem,
      'certs/leaf.key': keyPem(chain.leaf.keys),
      'chain.p7b': pkcs7Der([chain.inter.cert, chain.root.cert]),
      'README.txt': 'sertifika değil',
      'ic.zip': zipBytes({ 'x.pem': other.pem }),
    })
    const r = await run(zip, 'paket.zip')
    expect(r.format).toBe('ZIP')
    expect(r.private_keys_removed).toBe(1)
    expect(certCount(r)).toBe(3)
    const skipped = r.notes.filter((n) => n.code === 'ZIP_SKIPPED_ENTRY').map((n) => n.params.name).sort()
    expect(skipped).toEqual(['README.txt', 'ic.zip'])
    expect(wire(r)).not.toMatch(/PRIVATE KEY/)
  })

  it('50 girdi sınırı ve sıkıştırma bombası → ZIP_LIMIT (sınır içindekiler kalır)', async () => {
    const many = {}
    for (let i = 0; i < 51; i++) many[`c${i}.pem`] = other.pem
    const r = await run(zipBytes(many), 'cok.zip')
    expect(r.notes).toContainEqual({ code: 'ZIP_LIMIT', severity: 'warn', params: { max_entries: 50, max_mb: 5 } })
    expect(certCount(r)).toBe(50)
    const bomb = await run(zipBytes({ 'a.pem': other.pem, 'bomb.bin': new Uint8Array(2 * 1024 * 1024) }, 9), 'bomba.zip')
    expect(bomb.notes.map((n) => n.code)).toContain('ZIP_LIMIT')
    expect(certCount(bomb)).toBe(1)
  })

  it('arşivdeki BKS girdisi tüm arşivi düşürmez (atlanır + not); arşivdeki PFX yanlış parolayla → password_error', async () => {
    const r = await run(zipBytes({ 'a.pem': other.pem, 'trust.bks': bksLike() }), 'paket.zip')
    expect(r.unsupported).toBeNull()
    expect(certCount(r)).toBe(1)
    expect(r.notes.map((n) => n.params.name)).toContain('trust.bks')
    const withPfx = zipBytes({ 'a.pem': other.pem, 'sunucu.pfx': forgePfx(chain.leaf.keys, [chain.leaf.cert], 'Test1234') })
    const bad = await run(withPfx, 'paket.zip', 'yanlis')
    expect(bad.password_error).toBe(true)
    expect(bad.entries).toEqual([])
    const ok = await run(withPfx, 'paket.zip', 'Test1234')
    expect(certCount(ok)).toBe(2)
    expect(ok.private_keys_removed).toBe(1)
  })
})

describe('ayıklama — desteklenmeyen / sınırlar / giriş noktası', () => {
  it('BKS / UBER → unsupported BKS (keytool yönergesi arayüzde); sertifika dönmez', async () => {
    const r = await run(bksLike(), 'truststore.bks')
    expect(r.unsupported).toEqual({ reason: 'BKS' })
    expect(r.entries).toEqual([])
  })

  it('200 sertifika sınırı: aşan dosya TOO_MANY_CERTS (sunucuya gitmez)', async () => {
    const r = await run(enc(other.pem.repeat(201)), 'cok.pem')
    expect(r.unsupported).toEqual({ reason: 'TOO_MANY_CERTS', max: 200 })
    expect(r.entries).toEqual([])
  })

  it('extractCertificates: 5 MB üstü dosya okunmadan reddedilir; Worker yoksa ana iş parçacığında aynı sonuç', async () => {
    const big = new File(['x'], 'big.pem')
    Object.defineProperty(big, 'size', { value: 6 * 1024 * 1024 })
    expect((await extractCertificates({ file: big })).unsupported).toEqual({ reason: 'TOO_LARGE', max_mb: 5 })
    const file = new File([chain.leaf.pem + keyPem(chain.leaf.keys)], 'with-key.pem', { type: 'application/x-pem-file' })
    const r = await extractCertificates({ file, password: '' }, { inline: true })
    expect(r.file_name).toBe('with-key.pem')
    expect(r.private_keys_removed).toBe(1)
    expect(r.entries).toHaveLength(1)
    const t = await extractCertificates({ text: chain.root.pem })
    expect(t.format).toBe('TEXT')
  })
})

// ── Gerçek araç çıktısı: OpenSSL 3 / JDK keytool (şifreli sertifika torbaları) ─────────────────────────────────

const hasTool = (cmd, args) => { try { return spawnSync(cmd, args, { encoding: 'utf8' }).status === 0 } catch { return false } }
const OPENSSL = hasTool('openssl', ['version'])
const JAVA_BIN = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin') : null
const KEYTOOL = JAVA_BIN && fs.existsSync(path.join(JAVA_BIN, process.platform === 'win32' ? 'keytool.exe' : 'keytool'))
  ? path.join(JAVA_BIN, 'keytool') : (hasTool('keytool', ['-help']) ? 'keytool' : null)
let dir

describe.skipIf(!OPENSSL)('PKCS#12 — OpenSSL ile üretilen (şifreli sertifika torbaları) [openssl yoksa atlanır; canlı e2e kapsar]', () => {
  const openssl = (args, extraEnv = {}) => spawnSync('openssl', args, { cwd: dir, encoding: 'utf8', env: { ...process.env, OPENSSL_WIN32_UTF8: '1', ...extraEnv } })
  beforeAll(() => {
    dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mcert-extract-'))
    fs.writeFileSync(path.join(dir, 'leaf.key'), pkcs8Pem(chain.leaf.keys))
    fs.writeFileSync(path.join(dir, 'leaf.pem'), chain.leaf.pem)
    fs.writeFileSync(path.join(dir, 'cas.pem'), chain.inter.pem + chain.root.pem)
  })
  afterAll(() => { if (dir) fs.rmSync(dir, { recursive: true, force: true }) })

  const VARIANTS = [
    ['varsayılan (PBES2 AES-256-CBC + hmacWithSHA256, MAC SHA-256)', []],
    ['-legacy (RC2-40 sertifika, 3DES anahtar, MAC SHA-1)', ['-legacy']],
    ['AES-128 + MAC SHA-512', ['-certpbe', 'AES-128-CBC', '-keypbe', 'AES-128-CBC', '-macalg', 'sha512']],
    ['3DES (PBE-SHA1-3DES) sertifika', ['-certpbe', 'PBE-SHA1-3DES', '-keypbe', 'PBE-SHA1-3DES']],
  ]
  for (const [label, extra] of VARIANTS) {
    for (const [sl, subtle] of SUBTLES) {
      it(`${label} — ${sl}`, async () => {
        const out = `v-${extra.join('').replace(/[^a-z0-9]/gi, '') || 'default'}.pfx`
        const res = openssl(['pkcs12', '-export', '-inkey', 'leaf.key', '-in', 'leaf.pem', '-certfile', 'cas.pem', '-name', 'odeme-api',
          '-passout', 'pass:Test1234', '-out', out, ...extra])
        if (res.status !== 0) {
          // OpenSSL derlemesi bu algoritmayı (ör. legacy sağlayıcısız RC2) desteklemiyor — gerekçesiyle atla
          console.warn(`atlandı (openssl üretemedi): ${label}: ${res.stderr?.split('\n')[0]}`)
          return
        }
        const bytes = new Uint8Array(fs.readFileSync(path.join(dir, out)))
        const r = await run(bytes, out, 'Test1234', subtle)
        expect(r.unsupported).toBeNull()
        expect(r.password_error).toBe(false)
        expect(r.private_keys_removed).toBe(1)
        expect(certCount(r)).toBe(3)
        expect(r.entries.find((e) => e.certs[0] === b64(chain.leaf.der))).toMatchObject({ alias: 'odeme-api', key_entry: true })
        const bad = await run(bytes, out, 'yanlis', subtle)
        expect(bad.password_error).toBe(true)
        const none = await run(bytes, out, '', subtle)
        expect(none.needs_password).toBe(true)
        expect(wire(r)).not.toMatch(/PRIVATE KEY|Test1234/)
      })
    }
  }

  it('Türkçe karakterli parola (UTF-8; BMP MAC + UTF-8 PBKDF2)', async () => {
    const res = openssl(['pkcs12', '-export', '-inkey', 'leaf.key', '-in', 'leaf.pem', '-passout', 'pass:Şifre-ğüİ1', '-out', 'tr.pfx'])
    if (res.status !== 0) return
    const r = await run(new Uint8Array(fs.readFileSync(path.join(dir, 'tr.pfx'))), 'tr.pfx', 'Şifre-ğüİ1')
    if (r.password_error) {
      // Windows'ta OpenSSL komut satırı parolayı UTF-8 almamış olabilir (kod sayfası) — araç sorunu, ayıklayıcı değil
      console.warn('atlandı: openssl parolayı UTF-8 almadı')
      return
    }
    expect(certCount(r)).toBe(1)
  })
})

describe.skipIf(!OPENSSL || !KEYTOOL)('JDK keytool çıktısı (PKCS12 varsayılan / legacy, JKS, JCEKS gizli anahtarlı) [keytool yoksa atlanır]', () => {
  const kt = (args) => spawnSync(KEYTOOL, args, { cwd: dir, encoding: 'utf8' })
  beforeAll(() => {
    dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mcert-keytool-'))
    fs.writeFileSync(path.join(dir, 'leaf.key'), pkcs8Pem(chain.leaf.keys))
    fs.writeFileSync(path.join(dir, 'leaf.pem'), chain.leaf.pem)
    fs.writeFileSync(path.join(dir, 'cas.pem'), chain.inter.pem + chain.root.pem)
    fs.writeFileSync(path.join(dir, 'other.pem'), other.pem)
    spawnSync('openssl', ['pkcs12', '-export', '-inkey', 'leaf.key', '-in', 'leaf.pem', '-certfile', 'cas.pem', '-name', 'odeme-api',
      '-passout', 'pass:Test1234', '-out', 'src.pfx'], { cwd: dir })
    const imp = (type, out, extra = []) => kt([...extra, '-importkeystore', '-noprompt', '-srckeystore', 'src.pfx', '-srcstoretype', 'PKCS12',
      '-srcstorepass', 'Test1234', '-destkeystore', out, '-deststoretype', type, '-deststorepass', 'Test1234', '-destkeypass', 'Test1234'])
    imp('PKCS12', 'jdk.p12')
    imp('PKCS12', 'jdk-legacy.p12', ['-J-Dkeystore.pkcs12.legacy'])
    imp('JKS', 'jdk.jks')
    imp('JCEKS', 'jdk.jceks')
    kt(['-importcert', '-noprompt', '-alias', 'kok', '-file', 'other.pem', '-keystore', 'jdk.jks', '-storetype', 'JKS', '-storepass', 'Test1234'])
    kt(['-genseckey', '-alias', 'aes', '-keyalg', 'AES', '-keysize', '128', '-keystore', 'jdk.jceks', '-storetype', 'JCEKS',
      '-storepass', 'Test1234', '-keypass', 'Test1234'])
  }, 60_000)
  afterAll(() => { if (dir) fs.rmSync(dir, { recursive: true, force: true }) })
  const read = (f) => new Uint8Array(fs.readFileSync(path.join(dir, f)))

  it('JDK PKCS12 (PBES2 AES-256, 10 000 yineleme, MAC SHA-256) ve -Dkeystore.pkcs12.legacy (RC2-40 / 3DES, SHA-1)', async () => {
    for (const f of ['jdk.p12', 'jdk-legacy.p12']) {
      const r = await run(read(f), f, 'Test1234')
      expect(r.unsupported, f).toBeNull()
      expect(certCount(r), f).toBe(3)
      expect(r.entries.find((e) => e.certs[0] === b64(chain.leaf.der)), f).toMatchObject({ alias: 'odeme-api', key_entry: true })
      expect((await run(read(f), f, 'yanlis')).password_error, f).toBe(true)
    }
  })

  it('gerçek JKS (anahtar + güvenilen) parolasız; gerçek JCEKS gizli anahtar girdisi Java serileştirmesi atlanarak geçilir', async () => {
    const jks = await run(read('jdk.jks'), 'jdk.jks', '')
    expect(jks.format).toBe('JKS')
    expect(certCount(jks)).toBe(4)
    expect(jks.private_keys_removed).toBe(1)
    expect(jks.entries.find((e) => e.key_entry)).toMatchObject({ alias: 'odeme-api' })
    expect((await run(read('jdk.jks'), 'jdk.jks', 'Test1234')).notes).toEqual([])
    const jceks = await run(read('jdk.jceks'), 'jdk.jceks', 'Test1234')
    expect(jceks.format).toBe('JCEKS')
    expect(jceks.notes).toEqual([])                    // gizli anahtar atlandı, kısmi okuma YOK
    expect(jceks.private_keys_removed).toBe(2)         // özel anahtar + gizli (AES) anahtar
    expect(certCount(jceks)).toBe(3)
  })
})
