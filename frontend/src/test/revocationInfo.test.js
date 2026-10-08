import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import {
  REVOCATION_REASONS, REVOCATION_FAIL_CODES, revocationReason, hasNoRevocationInfo, attemptText,
} from '../utils/revocationInfo.js'

/**
 * İptal NEDENİ (2026-10-08, kullanıcı: "Ham durum UNKNOWN; adres göremedim — boş da olsa gösterelim, yanıltıcı uyarıları
 * kaldıralım"). Neden kodları backend `RevocationReason`'da üretilir, arayüz `hlth.revReason.<KOD>` /
 * `hlth.revFail.<KOD>` ile çevirir. Anahtarlar DİNAMİK olduğu için kullanılan-anahtar kapısı onları göremez — bu test
 * hem sözlüğü hem de Java ↔ JS listesini kilitler (yeni kod eklenip metni unutulursa arayüzde ham anahtar görünürdü).
 */
const JAVA = path.resolve(__dirname, '../../../backend/src/main/java/com/sitemonitor/service/RevocationReason.java')

function javaConstants(prefixFilter) {
  const src = fs.readFileSync(JAVA, 'utf8')
  return [...src.matchAll(/public static final String ([A-Z_]+) = "([A-Z_]+)";/g)]
    .filter((m) => prefixFilter(m[1]))
    .map((m) => m[2])
}

describe('revocationReason — neden kodu (backend RevocationReason.effective ile aynı kural)', () => {
  it('saklanan kod kazanır (büyük harfe çevrilir)', () => {
    expect(revocationReason({ revocation_status: 'UNKNOWN', revocation_reason: 'unreachable' })).toBe('UNREACHABLE')
    expect(revocationReason({ revocation_status: 'VALID', revocation_reason: 'OCSP' })).toBe('OCSP')
  })

  it('eski kayıt: okunmuş sertifika + UNKNOWN + iki adres boş → NO_ENDPOINTS; aksi hâlde null', () => {
    const base = { revocation_status: 'UNKNOWN', fingerprint: 'AB', ocsp_url: null, crl_url: '' }
    expect(revocationReason(base)).toBe('NO_ENDPOINTS')
    expect(hasNoRevocationInfo(base)).toBe(true)
    expect(revocationReason({ ...base, crl_url: 'http://crl.example.test/ca.crl' })).toBeNull()
    expect(revocationReason({ ...base, fingerprint: null })).toBeNull()      // okunmamış (hata satırı)
    expect(revocationReason({ ...base, revocation_status: 'VALID' })).toBeNull()
    expect(revocationReason(null)).toBeNull()
  })

  it('deneme metni: kaynak · adres — çevrilmiş neden (HTTP kodu yer tutucuya)', () => {
    const t = (k, ...a) => (TR[k] || k).replace('{0}', a[0] ?? '')
    expect(attemptText({ via: 'CRL', url: 'http://crl.example.test/ca.crl', code: 'HTTP', status: 404 }, t))
      .toBe('CRL · http://crl.example.test/ca.crl — HTTP 404 döndü')
    expect(attemptText({ via: 'OCSP', url: 'http://ocsp.example.test', code: 'TIMEOUT' }, t))
      .toBe('OCSP · http://ocsp.example.test — zaman aşımı — yanıt gelmedi')
    expect(attemptText({ via: 'CRL', url: 'x', code: 'YENI_KOD' }, t)).toBe('CRL · x — YENI_KOD')   // tanınmayan kod ham
  })
})

describe('kod kataloğu ↔ backend ↔ sözlük (dinamik anahtar kapısı)', () => {
  it('neden kodları ve deneme kodları backend RevocationReason ile AYNI', () => {
    expect(new Set(javaConstants((n) => !n.startsWith('FAIL_')))).toEqual(new Set(REVOCATION_REASONS))
    expect(new Set(javaConstants((n) => n.startsWith('FAIL_')))).toEqual(new Set(REVOCATION_FAIL_CODES))
  })

  it('her kodun TR + EN metni var, boş değil', () => {
    const keys = [
      ...REVOCATION_REASONS.map((c) => `hlth.revReason.${c}`),
      ...REVOCATION_FAIL_CODES.map((c) => `hlth.revFail.${c}`),
      'hlth.ev.notInCert', 'hlth.ev.revocation_reason', 'hlth.ev.revocation_attempts', 'cdp.notInCert',
      'cdp.rev.NO_ENDPOINTS', 'cdp.rev.UNSUPPORTED_SCHEME',
      // Sağlık satırının sunucudan gelen değer / öneri anahtarları (CertificateHealthService.revocationRow)
      'hlth.val.revNoEndpoints', 'hlth.val.revLdapOnly', 'hlth.val.revPending', 'hlth.val.notChecked',
      'hlth.act.revLdapOnly', 'hlth.act.revNoIssuer', 'hlth.act.revFullCheck', 'hlth.act.revPending', 'hlth.act.checkNetworkAccess',
    ]
    const missing = keys.filter((k) => !TR[k] || !EN[k] || !String(TR[k]).trim() || !String(EN[k]).trim())
    expect(missing).toEqual([])
  })

  it('Sağlık satırının kullandığı değer/öneri anahtarları backend kaynağında da geçiyor (yanlış ad = ham anahtar)', () => {
    const health = fs.readFileSync(path.resolve(__dirname,
      '../../../backend/src/main/java/com/sitemonitor/service/CertificateHealthService.java'), 'utf8')
    for (const k of ['revNoEndpoints', 'revLdapOnly', 'revPending', 'revNoIssuer', 'revFullCheck', 'checkNetworkAccess']) {
      expect(health, k).toContain(`"${k}"`)
    }
  })
})
