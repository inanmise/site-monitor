import { describe, it, expect } from 'vitest'
import {
  DAY_MS, clean, detailTone, filterSan, formatRelative, hexGroups, hostCoverage, isBlank, issuerOf, keyInfo, lifetimeOf,
  relativeUnit, safeHttpUrl, sanCovers, sanEntries, statusChip, subjectOf, tlsInfo, trustCode, trustSummary, usageItems, KU_IDS, EKU_IDS,
  revocationChipCode,
} from '../components/certmodal/certDetailsModel.js'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'

/**
 * "Sertifika Detayları" sekmesinin saf modeli (certmodal/certDetailsModel.js). Tarihler SABİT DEĞİL: her test kendi `now`
 * değerini verir ve fixture tarihleri ona göre kurulur (UTC, `Z`siz — sunucunun biçimi) → kayan pencere tuzağı yok.
 */
const NOW = Date.UTC(2031, 4, 10, 12, 0, 0)   // yalnız referans saat; tarihler buna GÖRE kurulur
const iso = (ms) => new Date(ms).toISOString().slice(0, 19)
const at = (days) => iso(NOW + days * DAY_MS)

describe('lifetimeOf — geçerlilik zaman çizelgesi', () => {
  it('toplam gün, sunucunun kalan günü ve kullanılan yüzde tutarlı', () => {
    const l = lifetimeOf({ not_before: at(-200), not_after: at(200), days_remaining: 200 }, NOW)
    expect(l.totalDays).toBe(400)
    expect(l.remainingDays).toBe(200)
    expect(l.usedPct).toBe(50)
    expect(l.expired).toBe(false)
    expect(l.notYetValid).toBe(false)
  })

  it('kalan gün yoksa saatten hesaplanır', () => {
    const l = lifetimeOf({ not_before: at(-100), not_after: at(300) }, NOW)
    expect(l.remainingDays).toBe(300)
    expect(l.usedPct).toBe(25)
  })

  it('dolmuş: %100 ve expired; henüz geçerli değil: %0', () => {
    expect(lifetimeOf({ not_before: at(-90), not_after: at(-6), days_remaining: -6 }, NOW)).toMatchObject({ usedPct: 100, expired: true })
    expect(lifetimeOf({ not_before: at(3), not_after: at(90), days_remaining: 90 }, NOW)).toMatchObject({ usedPct: 0, notYetValid: true })
  })

  it('tarih yok / bozuk / ters sıralı → null (çubuk çizilmez)', () => {
    expect(lifetimeOf({ not_after: at(10) }, NOW)).toBeNull()
    expect(lifetimeOf({ not_before: 'bozuk', not_after: at(10) }, NOW)).toBeNull()
    expect(lifetimeOf({ not_before: at(10), not_after: at(-10) }, NOW)).toBeNull()
    expect(lifetimeOf(null, NOW)).toBeNull()
  })
})

describe('relativeUnit / formatRelative', () => {
  it('birim seçimi: < 1 gün bugün, < 45 gün gün, < 2 yıl ay, üstü yıl', () => {
    expect(relativeUnit(NOW + 3 * 3600_000, NOW)).toEqual({ value: 0, unit: 'day' })
    expect(relativeUnit(NOW - 6 * DAY_MS, NOW)).toEqual({ value: -6, unit: 'day' })
    expect(relativeUnit(NOW + 183 * DAY_MS, NOW)).toEqual({ value: 6, unit: 'month' })
    expect(relativeUnit(NOW + 1095 * DAY_MS, NOW)).toEqual({ value: 3, unit: 'year' })
  })

  it('İngilizce ve Türkçe doğal göreli metin; tarih yoksa null', () => {
    expect(formatRelative(at(-6), 'en-GB', NOW)).toBe('6 days ago')
    expect(formatRelative(at(183), 'en-GB', NOW)).toBe('in 6 months')
    expect(formatRelative(at(-6), 'tr-TR', NOW)).toBe('6 gün önce')
    expect(formatRelative(null, 'en-GB', NOW)).toBeNull()
  })
})

describe('hexGroups — okunur onaltılık gruplar', () => {
  it('SHA-256 parmak izi 8 grup × 4 bayt, büyük harf, iki nokta', () => {
    const g = hexGroups('ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12cd34ef56ab12')
    expect(g).toHaveLength(8)
    expect(g[0]).toBe('AB:12:CD:34')
    expect(g.join('').replace(/:/g, '')).toBe('AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12')
  })

  it('tek hane sayılı seri no baştan sıfırla; iki noktalı girdi de gruplanır', () => {
    expect(hexGroups('A1B2C3D4E')).toEqual(['0A:1B:2C:3D', '4E'])
    expect(hexGroups('AB:CD:EF:01:23')).toEqual(['AB:CD:EF:01', '23'])
  })

  it('onaltılık olmayan ya da kısa değer TEK grup (olduğu gibi); boş → []', () => {
    expect(hexGroups('not-a-hex-value')).toEqual(['not-a-hex-value'])
    expect(hexGroups('ABC')).toEqual(['ABC'])
    expect(hexGroups('')).toEqual([])
  })
})

describe('usageItems — anahtar kullanımı adları', () => {
  it('sunucu adları, RFC 5280 adları ve OID tanınır; bilinmeyen ham kalır; yinelenen atılır', () => {
    expect(usageItems(['Digital Signature', 'keyEncipherment', 'Certificate Signing', 'Digital Signature'], 'ku'))
      .toEqual([{ raw: 'Digital Signature', id: 'digitalSignature' }, { raw: 'keyEncipherment', id: 'keyEncipherment' },
        { raw: 'Certificate Signing', id: 'keyCertSign' }])
    expect(usageItems(['TLS Web Server', 'clientAuth', '1.3.6.1.5.5.7.3.9', '1.2.3.4.5'], 'eku'))
      .toEqual([{ raw: 'TLS Web Server', id: 'serverAuth' }, { raw: 'clientAuth', id: 'clientAuth' },
        { raw: '1.3.6.1.5.5.7.3.9', id: 'ocspSigning' }, { raw: '1.2.3.4.5', id: null }])
    expect(usageItems(null)).toEqual([])
  })

  it('her bilinen kullanım kimliğinin TR ve EN etiketi var', () => {
    for (const id of KU_IDS) { expect(TR[`cdp.ku.${id}`]).toBeTruthy(); expect(EN[`cdp.ku.${id}`]).toBeTruthy() }
    for (const id of EKU_IDS) { expect(TR[`cdp.eku.${id}`]).toBeTruthy(); expect(EN[`cdp.eku.${id}`]).toBeTruthy() }
  })
})

describe('SAN — kapsama, sıralama, arama', () => {
  it('joker YALNIZ tek etiketi kapsar (RFC 6125); harf ve sondaki nokta duyarsız', () => {
    expect(sanCovers('WWW.Example.com.', 'www.example.com')).toBe('exact')
    expect(sanCovers('*.example.com', 'www.example.com')).toBe('wildcard')
    expect(sanCovers('*.example.com', 'a.b.example.com')).toBeNull()
    expect(sanCovers('*.example.com', 'example.com')).toBeNull()
    expect(sanCovers('api.example.com', 'www.example.com')).toBeNull()
  })

  it('kapsayan girdiler öne (birebir, sonra joker); diğerleri sunucu sırasında; boşlar atılır', () => {
    const e = sanEntries(['api.example.com', '', '*.example.com', 'www.example.com', 'cdn.example.com'], 'www.example.com')
    expect(e.map((x) => x.name)).toEqual(['www.example.com', '*.example.com', 'api.example.com', 'cdn.example.com'])
    expect(e.map((x) => x.match)).toEqual(['exact', 'wildcard', null, null])
    expect(e[1].wildcard).toBe(true)
  })

  it('arama harf duyarsız alt dize; boş sorgu hepsini döner', () => {
    const e = sanEntries(['a.example.com', 'API.example.com', 'b.test.com'], 'x.example.com')
    expect(filterSan(e, 'api').map((x) => x.name)).toEqual(['API.example.com'])
    expect(filterSan(e, '  ')).toHaveLength(3)
  })

  it('alan adı kapsaması: sunucu HOSTNAME_MISMATCH → fail, eşleşen SAN → ok, ikisi de yoksa null', () => {
    const entries = sanEntries(['www.example.com'], 'www.example.com')
    expect(hostCoverage({ security_flags: ['HOSTNAME_MISMATCH'] }, entries)).toBe('fail')
    expect(hostCoverage({ security_flags: [] }, entries)).toBe('ok')
    expect(hostCoverage({}, sanEntries(['api.example.com'], 'www.example.com'))).toBeNull()
  })
})

describe('durum çipleri', () => {
  it('bilinen kodlar ton + etiket anahtarı; tanınmayan kod ham (labelKey null); boş → null', () => {
    expect(statusChip('chain', 'broken')).toEqual({ kind: 'chain', code: 'BROKEN', tone: 'bad', labelKey: 'cdp.chain.BROKEN' })
    expect(statusChip('dep', 'OK')).toMatchObject({ tone: 'ok', labelKey: 'cdp.dep.OK' })
    expect(statusChip('rev', 'PENDING')).toEqual({ kind: 'rev', code: 'PENDING', tone: 'muted', labelKey: null })
    expect(statusChip('trust', '')).toBeNull()
  })

  it('her etiket anahtarı TR ve EN sözlüğünde var', () => {
    const codes = { trust: ['TRUSTED', 'UNTRUSTED', 'UNKNOWN'], chain: ['VALID', 'BROKEN', 'REVOKED', 'INCOMPLETE', 'UNKNOWN'],
      rev: ['VALID', 'REVOKED', 'UNKNOWN'], dep: ['OK', 'INCOMPLETE', 'MISMATCH', 'UNKNOWN'] }
    for (const [kind, list] of Object.entries(codes)) {
      for (const c of list) {
        const k = statusChip(kind, c).labelKey
        expect(TR[k], k).toBeTruthy()
        expect(EN[k], k).toBeTruthy()
      }
    }
  })

  it('güven kodu trust_status, yoksa UNTRUSTED_CA bayrağı; özet ton bad > high > ok > muted', () => {
    expect(trustCode({ trust_status: 'trusted' })).toBe('TRUSTED')
    expect(trustCode({ security_flags: ['UNTRUSTED_CA'] })).toBe('UNTRUSTED')
    expect(trustCode({})).toBeNull()
    expect(trustSummary({ chain_status: 'VALID', deployment_status: 'INCOMPLETE' }).tone).toBe('high')
    expect(trustSummary({ chain_status: 'VALID', revocation_status: 'REVOKED' }).tone).toBe('bad')
    expect(trustSummary({ chain_status: 'VALID', revocation_status: 'UNKNOWN' }).tone).toBe('ok')
    expect(trustSummary({ chain_status: 'UNKNOWN' }).tone).toBe('muted')
    expect(trustSummary({}).chips).toEqual([])
  })

  // 2026-10-08: "İptal durumu bilinmiyor" yerine nedeni belliyse onu söyler (adres yok / yalnız LDAP)
  it('iptal çipi nedeni gösterir: adres yok → NO_ENDPOINTS, LDAP → UNSUPPORTED_SCHEME; ulaşılamadı → UNKNOWN; ton nötr', () => {
    const noAddr = { revocation_status: 'UNKNOWN', fingerprint: 'AB', ocsp_url: null, crl_url: null }
    expect(revocationChipCode(noAddr)).toBe('NO_ENDPOINTS')
    expect(revocationChipCode({ revocation_status: 'UNKNOWN', revocation_reason: 'UNSUPPORTED_SCHEME' })).toBe('UNSUPPORTED_SCHEME')
    expect(revocationChipCode({ revocation_status: 'UNKNOWN', revocation_reason: 'UNREACHABLE' })).toBe('UNKNOWN')
    expect(revocationChipCode({ revocation_status: 'VALID', revocation_reason: 'CRL' })).toBe('VALID')
    const chip = trustSummary({ chain_status: 'VALID', ...noAddr }).chips.find((c) => c.kind === 'rev')
    expect(chip).toMatchObject({ code: 'NO_ENDPOINTS', tone: 'muted', labelKey: 'cdp.rev.NO_ENDPOINTS' })
    expect(TR['cdp.rev.NO_ENDPOINTS']).toBe('İptal adresi yok')
    expect(EN['cdp.rev.UNSUPPORTED_SCHEME']).toBeTruthy()
  })
})

describe('kimlik / anahtar / bağlantı güvenliği', () => {
  it('sunucunun "Unknown" yer tutucusu boş sayılır; veren CN + kuruluş DN yedekli', () => {
    expect(clean('Unknown')).toBeNull()
    expect(clean('  x ')).toBe('x')
    expect(issuerOf({ issuer: 'Unknown', issuer_cn: null, issuer_dn: 'CN=Example CA,O=Example Trust Ltd,C=GB' }))
      .toEqual({ cn: 'Example CA', org: 'Example Trust Ltd' })
    expect(subjectOf({ subject: 'Unknown', subject_dn: 'CN=www.example.com,O=Example Ltd' })).toEqual({ cn: 'www.example.com', org: 'Example Ltd' })
  })

  it('anahtar etiketi boyut > 0 ise; imzanın kısa adı', () => {
    expect(keyInfo({ public_key_algorithm: 'RSA', public_key_size: 2048, signature_algorithm: 'SHA256withRSA' }))
      .toEqual({ alg: 'RSA', size: 2048, label: 'RSA 2048', sig: 'SHA256withRSA', sigShort: 'SHA-256' })
    expect(keyInfo({ public_key_algorithm: 'EC', public_key_size: -1 }).label).toBe('EC')
  })

  it('yalnız http(s) bağlantı olur — javascript:, data:, göreli ve bozuk adres null', () => {
    expect(safeHttpUrl('http://ocsp.example.com')).toBe('http://ocsp.example.com/')
    expect(safeHttpUrl('https://crl.example.com/a.crl')).toBe('https://crl.example.com/a.crl')
    expect(safeHttpUrl('javascript:alert(1)')).toBeNull()
    expect(safeHttpUrl('JaVaScRiPt:alert(1)')).toBeNull()
    expect(safeHttpUrl('data:text/html,x')).toBeNull()
    expect(safeHttpUrl('ldap://dir.example.com/cn=crl')).toBeNull()
    expect(safeHttpUrl('/relative/path')).toBeNull()
    expect(safeHttpUrl('')).toBeNull()
  })

  it('isBlank: null, boş, "Unknown", boş dizi boş; false / 0 / nesne dolu', () => {
    for (const v of [null, undefined, '', '  ', 'Unknown', []]) expect(isBlank(v)).toBe(true)
    for (const v of [false, 0, 'x', ['a'], { k: 1 }]) expect(isBlank(v)).toBe(false)
  })

  it('ton: pencerenin verdiği geçerli ton kazanır; yoksa kartın kuralı', () => {
    expect(detailTone({ days_remaining: 100 }, 'critical')).toBe('critical')
    expect(detailTone({ days_remaining: -2 }, 'nonsense')).toBe('expired')
    expect(detailTone({ status: 'error' })).toBe('error')
  })
})

describe('tlsInfo — TLS sürümü / şifre takımı (hüküm SUNUCUDAN)', () => {
  it('okunur sürüm; zayıflık yalnız sunucunun FAIL hükmünden (/history tls_assessment ya da önizleme assessment)', () => {
    expect(tlsInfo({ tls_version: 'TLSv1.3', cipher_suite: 'TLS_AES_128_GCM_SHA256', tls_assessment: { protocol: 'OK', cipher: 'OK' } }))
      .toEqual({ version: 'TLSv1.3', versionLabel: 'TLS 1.3', cipher: 'TLS_AES_128_GCM_SHA256', weakProtocol: false, weakCipher: false })
    expect(tlsInfo({ tls_version: 'TLSv1', cipher_suite: 'TLS_RSA_WITH_RC4_128_SHA', tls_assessment: { protocol: 'FAIL', cipher: 'FAIL' } }))
      .toMatchObject({ versionLabel: 'TLS 1.0', weakProtocol: true, weakCipher: true })
    expect(tlsInfo({ tls_version: 'TLSv1.1', assessment: { protocol: 'fail' } }).weakProtocol).toBe(true)
    // CBC = öneri (WARN), zayıf değil; hüküm yoksa rozet yok (istemci kural yazmaz)
    expect(tlsInfo({ cipher_suite: 'TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA', tls_assessment: { cipher: 'WARN' } }).weakCipher).toBe(false)
    expect(tlsInfo({ tls_version: 'SSLv3' }).weakProtocol).toBe(false)
  })

  it('boş / "Unknown" değer yok sayılır; değer yoksa hüküm tek başına rozet üretmez', () => {
    expect(tlsInfo({})).toEqual({ version: null, versionLabel: null, cipher: null, weakProtocol: false, weakCipher: false })
    expect(tlsInfo({ tls_version: ' ', cipher_suite: 'Unknown', tls_assessment: { protocol: 'FAIL', cipher: 'FAIL' } }))
      .toMatchObject({ version: null, cipher: null, weakProtocol: false, weakCipher: false })
  })
})
