import { describe, it, expect } from 'vitest'
import {
  ST, parseDn, colonHex, prettyTls, hostnameStatus, buildSslGroups, buildVerdict, buildChain, buildConnectionError,
  entryChainData, isUploadResult,
} from '../components/certmodal/sslModel.js'
import { healthyPreview, uploadPreview } from './helpers/sslPreviewFixture.js'

/** SSL Kontrol sekmesinin saf modeli (2026-09-28) — fixture gerçek tel biçiminde (helpers/sslPreviewFixture.js). */

const rowOf = (groups, key) => groups.flatMap((g) => g.rows).find((r) => r.key === key)

describe('parseDn (RFC 2253)', () => {
  it('kaçışlı virgülü ve tırnaklı değeri bölmez; ilk değer kazanır', () => {
    expect(parseDn('CN=www.example.com,O=Example\\, Ltd,C=GB')).toEqual({ CN: 'www.example.com', O: 'Example, Ltd', C: 'GB' })
    expect(parseDn('CN="a, b",O=X').CN).toBe('a, b')
    expect(parseDn('OU=One,OU=Two').OU).toBe('One')
  })
  it('UTF-8 onaltılık kaçışları çözer; boş/geçersiz girdide boş nesne', () => {
    expect(parseDn('O=\\C3\\9Cnite A.S.,C=TR').O).toBe('Ünite A.S.')
    expect(parseDn(null)).toEqual({})
    expect(parseDn('')).toEqual({})
  })
})

describe('biçimleyiciler', () => {
  it('colonHex / prettyTls', () => {
    expect(colonHex('ab12CD')).toBe('AB:12:CD')
    expect(colonHex('ABC')).toBe('ABC')          // tek sayılı: dokunulmaz
    expect(prettyTls('TLSv1.3')).toBe('TLS 1.3')
    expect(prettyTls('TLSv1')).toBe('TLS 1.0')    // Java'nın TLS 1.0 adı
    expect(prettyTls(null)).toBeNull()
  })
})

describe('hostnameStatus — kural SUNUCUDA (istemci jokeri gevşek eşlemez)', () => {
  it('assessment.hostname varsa o kullanılır', () => {
    // *.example.com TEK etiketi karşılar: a.b.example.com'u KARŞILAMAZ — hükmü sunucu verir, istemci üstüne yazmaz.
    expect(hostnameStatus({ domain: 'a.b.example.com', san: ['*.example.com'], assessment: { hostname: 'FAIL' } })).toBe(ST.FAIL)
    expect(hostnameStatus({ domain: 'www.example.com', san: ['*.example.com'], assessment: { hostname: 'OK' } })).toBe(ST.OK)
  })
  it('assessment yoksa security_flags; o da yoksa BİLİNMİYOR (yerel joker kuralı yok)', () => {
    expect(hostnameStatus({ san: ['x.example.com'], security_flags: ['HOSTNAME_MISMATCH'] })).toBe(ST.FAIL)
    expect(hostnameStatus({ san: ['x.example.com'], security_flags: [] })).toBe(ST.OK)
    expect(hostnameStatus({ domain: 'a.b.example.com', san: ['*.example.com'] })).toBe(ST.UNKNOWN)
  })
})

describe('buildSslGroups + buildVerdict', () => {
  it('sağlıklı sonuç: hüküm OK, tüm satırlar sorunsuz, öneri yok', () => {
    const groups = buildSslGroups(healthyPreview())
    expect(groups.map((g) => g.key)).toEqual(['cert', 'trust', 'conn'])
    const v = buildVerdict(groups)
    expect(v.tone).toBe(ST.OK)
    expect(v.problems).toEqual([])
    expect(v.passed).toBe(v.total)
    expect(rowOf(groups, 'expiry').args).toEqual([276])
    expect(rowOf(groups, 'chain').args).toEqual([2])
  })

  it('sorunlar önem sırasıyla: süresi dolmuş > hostname > güven; hüküm FAIL', () => {
    const v = buildVerdict(buildSslGroups(healthyPreview({
      days_remaining: -3, warning: true, trust_status: 'UNTRUSTED',
      assessment: { ...healthyPreview().assessment, hostname: 'FAIL' }, security_flags: ['HOSTNAME_MISMATCH', 'UNTRUSTED_CA'],
    })))
    expect(v.tone).toBe(ST.FAIL)
    expect(v.problems.map((r) => r.key)).toEqual(['expiry', 'hostname', 'trust'])
    expect(v.problems[0]).toMatchObject({ textKey: 'sslv.expiry.expired', args: [3] })
  })

  it('yalnız yakında bitiyor → WARN; HSTS yokluğu ÖNERİDİR, hükmü bozmaz', () => {
    const soon = buildVerdict(buildSslGroups(healthyPreview({ days_remaining: 12, warning: true })))
    expect(soon.tone).toBe(ST.WARN)
    expect(soon.attention.map((r) => r.key)).toEqual(['expiry'])
    const noHsts = buildVerdict(buildSslGroups(healthyPreview({ hsts: false })))
    expect(noHsts.tone).toBe(ST.OK)
    expect(noHsts.advice.map((r) => r.key)).toEqual(['hsts'])
  })

  it('OCSP ulaşılamadı (UNKNOWN) FAIL DEĞİL: satır gri, hüküm OK kalır ve bilinmeyen sayılır', () => {
    const groups = buildSslGroups(healthyPreview({ revocation_status: 'UNKNOWN', hsts: null }))
    expect(rowOf(groups, 'revocation').status).toBe(ST.UNKNOWN)
    const v = buildVerdict(groups)
    expect(v.tone).toBe(ST.OK)
    expect(v.unknown).toBe(2)
  })

  it('yalnız yaprak gönderilmiş (kendinden imzalı değil) → zincir WARN; ara sertifika dolmuş → FAIL', () => {
    const base = healthyPreview()
    expect(rowOf(buildSslGroups({ ...base, chain: [base.chain[0]] }), 'chain')).toMatchObject({ status: ST.WARN, textKey: 'sslv.chain.leafOnly' })
    expect(rowOf(buildSslGroups({ ...base, chain_status: 'BROKEN' }), 'chain').status).toBe(ST.FAIL)
  })

  it('zayıf protokol / şifre sunucu hükmünden: TLS 1.0 sorun, CBC öneri', () => {
    const groups = buildSslGroups(healthyPreview({
      tls_version: 'TLSv1', cipher_suite: 'TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA',
      assessment: { ...healthyPreview().assessment, protocol: 'FAIL', protocol_latest: false, cipher: 'WARN' },
    }))
    const v = buildVerdict(groups)
    expect(v.problems.map((r) => r.key)).toEqual(['protocol'])
    expect(rowOf(groups, 'protocol').value).toBe('TLS 1.0')
    expect(rowOf(groups, 'cipher')).toMatchObject({ status: ST.WARN, advice: true })
  })

  it('kalan gün bile yoksa "her şey yolunda" DENMEZ → UNKNOWN', () => {
    expect(buildVerdict(buildSslGroups({ domain: 'example.com', status: 'valid' })).tone).toBe(ST.UNKNOWN)
  })
})

describe('buildChain', () => {
  it('yaprak (üst düzey alanlardan) → ara; kök gönderilmemiş → rootSent false', () => {
    const { nodes, rootSent } = buildChain(healthyPreview())
    expect(nodes.map((n) => n.role)).toEqual(['leaf', 'intermediate'])
    expect(nodes[0]).toMatchObject({ cn: 'www.example.com', org: 'Example Ltd', issuerCn: 'Example TLS RSA CA 2026', days: 276 })
    expect(nodes[0].san).toEqual(['www.example.com', 'example.com'])
    expect(nodes[0].fingerprint).toMatch(/^AB12/)
    expect(nodes[1]).toMatchObject({ cn: 'Example TLS RSA CA 2026', issuerCn: 'Example Root CA', serial: '77AA' })
    expect(rootSent).toBe(false)
  })
  it('süresi dolmuş ara sertifika: expired true, gün yok (sunucu 0\'a kırpıyor); kendinden imzalı yaprak kök sayılır', () => {
    const base = healthyPreview()
    const expired = buildChain({ ...base, chain: [base.chain[0], { ...base.chain[1], expired: true, days_remaining: 0 }] })
    expect(expired.nodes[1]).toMatchObject({ expired: true, days: null })
    const self = buildChain({ ...base, chain: [{ ...base.chain[0], is_root: true }] })
    expect(self.nodes[0].selfSigned).toBe(true)
    expect(self.rootSent).toBe(true)
  })
})

describe('dosyadan yüklenen sertifika (via: upload, 2026-10-07)', () => {
  it('Bağlantı grubu ve alan adı eşleşmesi satırı YOK; zincir / iptal metinleri dosyaya göre', () => {
    const d = uploadPreview()
    expect(isUploadResult(d)).toBe(true)
    expect(isUploadResult(healthyPreview())).toBe(false)
    const groups = buildSslGroups(d)
    expect(groups.map((g) => g.key)).toEqual(['cert', 'trust'])
    const keys = groups.flatMap((g) => g.rows).map((r) => r.key)
    expect(keys).not.toContain('hostname')
    expect(keys).not.toContain('protocol')
    expect(rowOf(groups, 'chain')).toMatchObject({ status: ST.OK, textKey: 'sslv.m.chain.ok', value: 3 })
    expect(rowOf(groups, 'revocation')).toMatchObject({ status: ST.UNKNOWN, textKey: 'sslv.m.rev.unknown' })
    const unknown = buildSslGroups(uploadPreview({ chain_status: 'UNKNOWN', chain: [d.chain[0]] }))
    expect(rowOf(unknown, 'chain')).toMatchObject({ status: ST.UNKNOWN, textKey: 'sslv.m.chain.unknown' })
    // Ağ sonucunda metinler değişmez
    expect(rowOf(buildSslGroups(healthyPreview()), 'chain').textKey).toBe('sslv.chain.ok')
  })

  it('zincir: yaprak → ara → kök (kök dosyada → rootSent); CA başı kendi rolüyle; upload işareti', () => {
    const c = buildChain(uploadPreview())
    expect(c.nodes.map((n) => n.role)).toEqual(['leaf', 'intermediate', 'root'])
    expect(c.rootSent).toBe(true)
    expect(c.upload).toBe(true)
    const inter = uploadPreview({
      is_ca: true, subject: 'Test Issuing CA', subject_dn: 'CN=Test Issuing CA,O=Example Test', san: [],
      chain: [{ position: 0, subject: 'CN=Test Issuing CA,O=Example Test', issuer: 'CN=Test Root CA,O=Example Test', is_leaf: true, is_root: false },
        { position: 1, subject: 'CN=Test Root CA,O=Example Test', issuer: 'CN=Test Root CA,O=Example Test', is_leaf: false, is_root: true }],
    })
    expect(buildChain(inter).nodes.map((n) => n.role)).toEqual(['intermediate', 'root'])
    const root = uploadPreview({ is_ca: true, chain: [{ position: 0, subject: 'CN=R', issuer: 'CN=R', is_leaf: true, is_root: true }] })
    expect(buildChain(root).nodes.map((n) => n.role)).toEqual(['root'])
    // Ağ sonucunda CA yaprağı bile "leaf" kalır (davranış aynı)
    expect(buildChain(healthyPreview({ is_ca: true })).nodes[0].role).toBe('leaf')
  })

  it('entryChainData: önizleme varsa o; yoksa girdinin alanlarından aynı biçim (yaprak + zincir halkaları)', () => {
    const preview = uploadPreview()
    expect(entryChainData({ ref: 'X', preview })).toBe(preview)
    const fallback = entryChainData({
      ref: 'AB', cn: 'api.example.test', subject_dn: 'CN=api.example.test,O=Example', issuer_dn: 'CN=Example CA,O=Example',
      not_before: '2026-01-01T00:00:00', not_after: '2027-01-01T00:00:00', days_remaining: 87, serial_number: '01',
      san: ['api.example.test'], key_alg: 'RSA', key_size: 2048, signature_algorithm: 'SHA256withRSA', is_ca: false, self_signed: false,
      trust_status: 'UNKNOWN',
      chain: [{ subject: 'Example CA', issuer: 'Example Root', not_after: '2030-01-01T00:00:00', is_ca: true, days_remaining: 1200 },
        { subject: 'Example Root', issuer: 'Example Root', not_after: '2035-01-01T00:00:00', is_ca: true, days_remaining: 3000 }],
    })
    expect(isUploadResult(fallback)).toBe(true)
    const c = buildChain(fallback)
    expect(c.nodes.map((n) => n.role)).toEqual(['leaf', 'intermediate', 'root'])
    expect(c.nodes[0]).toMatchObject({ cn: 'api.example.test', fingerprint: 'AB', keyAlg: 'RSA', keySize: 2048 })
    expect(c.nodes[1]).toMatchObject({ cn: 'Example CA', days: 1200 })
  })
})

describe('buildConnectionError', () => {
  it('hata sınıfı, aşama, IP\'ler ve deneme sayısı', () => {
    const e = buildConnectionError({
      domain: 'down.example.com', status: 'error', error: 'Connection timeout after 10s · 2 attempts, 21s total',
      error_class: 'NETWORK', error_stage: 'tcp-connect', resolved_ips: ['203.0.113.7', '203.0.113.8'], attempts_total: 2,
    })
    expect(e).toMatchObject({ classKey: 'sslv.err.class.NETWORK', stage: 'tcp-connect', attempts: 2 })
    expect(e.ips).toEqual(['203.0.113.7', '203.0.113.8'])
    expect(buildConnectionError({ error_class: 'WEIRD' }).classKey).toBe('sslv.err.class.UNKNOWN')
  })
})
