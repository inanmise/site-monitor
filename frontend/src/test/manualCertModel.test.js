import { describe, it, expect } from 'vitest'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import {
  WARNING_CODES, compareWithCurrent, defaultRef, fillParams, filterManualRows, inventoryPayload, isManualCert,
  looksLikeTruststore, manualKpis, passwordLikely, sortManualRows, splitServerErrors, trackingKeyError, extractedFormData,
  warningText, withoutManualCerts, discouragedExtension, knownExtension, validitySpan, teamFilterOptions, wirePayload,
  extractionErrorKey, CLIENT_NOTE_CODES,
} from '../components/manualcert/manualCertModel.js'
import { eventLabel } from '../components/admin/audit/auditFormat.js'

/**
 * Manuel sertifika saf modeli (2026-10-06): takip adı doğrulaması (sunucu deseniyle aynı), uyarı metinleri (adlı yer
 * tutucular), sıralama/süzgeç/özet, yeni sürüm karşılaştırması, istek gövdeleri (şifre yalnız gövdede).
 */
const t = (key, ...args) => {
  let s = TR[key] ?? key
  args.forEach((a, i) => { s = s.split(`{${i}}`).join(String(a)) })
  return s
}

describe('manualCertModel — kaynak ve dosya', () => {
  it('isManualCert yalnız MANUAL satırı tanır; ağ satırı (null / alan yok) değişmez', () => {
    expect(isManualCert({ cert_source: 'MANUAL' })).toBe(true)
    expect(isManualCert({ cert_source: 'manual' })).toBe(true)
    expect(isManualCert({ cert_source: null })).toBe(false)
    expect(isManualCert({})).toBe(false)
    expect(isManualCert(null)).toBe(false)
    const net = { domain: 'a.example.test' }
    expect(withoutManualCerts([net, { domain: 'm', cert_source: 'MANUAL' }])).toEqual([net])
  })

  it('şifre alanı baştan: pfx/p12/jks/jceks/bks; CSR ve .key uyarılır; bilinen uzantılar', () => {
    for (const n of ['a.pfx', 'b.P12', 'c.jks', 'd.jceks', 'e.bks']) expect(passwordLikely(n)).toBe(true)
    for (const n of ['a.pem', 'b.crt', 'c.der', 'd.zip', 'noext']) expect(passwordLikely(n)).toBe(false)
    expect(discouragedExtension('req.csr')).toBe(true)
    expect(discouragedExtension('server.key')).toBe(true)
    expect(knownExtension('x.p7b')).toBe(true)
    expect(knownExtension('x.docx')).toBe(false)
  })
})

describe('manualCertModel — takip adı (sunucu deseni)', () => {
  it('geçerli adlar', () => {
    for (const k of ['api.example.test', 'api.example.test-manuel', '*.example.test', 'a', 'a_b-c.d9']) {
      expect(trackingKeyError(k), k).toBeNull()
    }
  })
  it('geçersiz adlar NEDENİYLE reddedilir', () => {
    expect(trackingKeyError('')).toBe('mcert.key.required')
    expect(trackingKeyError('a b')).toBe('mcert.key.spaces')
    expect(trackingKeyError('Api.example.test')).toBe('mcert.key.lowercase')
    expect(trackingKeyError('a:443')).toBe('mcert.key.chars')
    expect(trackingKeyError('a/b')).toBe('mcert.key.chars')
    expect(trackingKeyError('me@x')).toBe('mcert.key.chars')
    expect(trackingKeyError('-abc')).toBe('mcert.key.invalid')
    expect(trackingKeyError('abc.')).toBe('mcert.key.invalid')
    expect(trackingKeyError('a'.repeat(254))).toBe('mcert.key.length')
  })
  it('her hata anahtarı iki sözlükte de var', () => {
    for (const k of ['required', 'spaces', 'lowercase', 'chars', 'length', 'invalid']) {
      expect(TR[`mcert.key.${k}`]).toBeTruthy()
      expect(EN[`mcert.key.${k}`]).toBeTruthy()
    }
  })
})

describe('manualCertModel — uyarı kodları', () => {
  it('sözleşmedeki 28 kodun hepsi TR ve EN sözlüğünde, adlı yer tutucular iki dilde AYNI', () => {
    expect(WARNING_CODES).toHaveLength(28)
    const names = (s) => [...String(s).matchAll(/\{([a-z_]+)\}/g)].map((m) => m[1]).sort()
    for (const code of WARNING_CODES) {
      const k = `mcert.warn.${code}`
      expect(TR[k], k).toBeTruthy()
      expect(EN[k], k).toBeTruthy()
      expect(names(TR[k]), k).toEqual(names(EN[k]))
      expect(TR[k], k).not.toMatch(/\*/)
    }
  })
  it('sözleşmenin parametre adları metinde geçer (gate: sunucu bunları doldurur)', () => {
    const PARAMS = {
      PRIVATE_KEY_KEPT_LOCAL: ['count'], CSR_NOT_CERTIFICATE: ['cn'], PASSWORD_REQUIRED: ['format'], PASSWORD_WRONG: ['format'],
      KEYSTORE_PARTIAL: ['format'],
      UNSUPPORTED_FORMAT: ['name'], FILE_TOO_LARGE: ['max_mb'], ZIP_LIMIT: ['max_entries', 'max_mb'], ZIP_SKIPPED_ENTRY: ['name'],
      EXPIRED: ['days'], NOT_YET_VALID: ['date'], EXPIRES_SOON: ['days'], CHAIN_INCOMPLETE: ['missing_issuer'],
      CHAIN_EXPIRED_INTERMEDIATE: ['subject', 'date'], WEAK_SIGNATURE: ['algorithm'], WEAK_KEY: ['algorithm', 'size'],
      MULTIPLE_LEAVES: ['count'], DUPLICATE_IN_FILE: ['count'], ALREADY_TRACKED: ['domain'], SAME_SUBJECT_TRACKED: ['domain'],
      NETWORK_MONITORED: ['domain'], SUBJECT_CHANGED: ['old', 'new'], OLDER_THAN_CURRENT: ['current_date', 'new_date'],
      SAN_CHANGED: ['added', 'removed'],
    }
    for (const [code, ps] of Object.entries(PARAMS)) {
      for (const p of ps) expect(TR[`mcert.warn.${code}`], `${code}.${p}`).toContain(`{${p}}`)
    }
  })
  it('warningText adlı yer tutucuları doldurur; boş değer "—"; bilinmeyen kod ham anahtar yerine genel metin', () => {
    // 2026-10-08: uyarı ne olduğunu VE yapılacak şeyi söyler (zayıf anahtar → yeni CSR)
    const weak = warningText(t, { code: 'WEAK_KEY', params: { algorithm: 'RSA', size: 1024 } })
    expect(weak.startsWith('Zayıf anahtar: RSA 1024 bit.')).toBe(true)
    expect(weak).toContain('RSA 2048')
    expect(weak).not.toMatch(/\{\w+\}/)
    expect(warningText(t, { code: 'SAN_CHANGED', params: { added: 'a.example.test', removed: '' } })).toContain('çıkarılan: —')
    expect(warningText(t, { code: 'NOPE' })).toBe('Uyarı: NOPE')
  })
  it('fillParams: tarih parametresi yerel tarihe çevrilir', () => {
    const out = fillParams('{date}', { date: '2027-01-15T00:00:00' })
    expect(out).not.toContain('T00:00')
    expect(out).toMatch(/2027/)
  })
})

describe('sunucu ekleri (sağlık NA satırı, denetim olayları)', () => {
  it('manuel kayıtta ağa bağlı sağlık satırı metni ve iki denetim olayı etiketi iki dilde', () => {
    for (const k of ['hlth.val.notApplicableManual', 'audit.ev.CERT_MANUAL_UPLOAD', 'audit.ev.CERT_MANUAL_RENEW']) {
      expect(TR[k], k).toBeTruthy()
      expect(EN[k], k).toBeTruthy()
    }
    expect(eventLabel('CERT_MANUAL_UPLOAD', t)).toBe(TR['audit.ev.CERT_MANUAL_UPLOAD'])
    expect(eventLabel('CERT_MANUAL_RENEW', t)).toBe(TR['audit.ev.CERT_MANUAL_RENEW'])
  })
})

describe('manualCertModel — liste', () => {
  const rows = [
    { inventory_id: 1, domain: 'ok.example.test', days_remaining: 200, status: 'valid', team_id: 1, team_name: 'Takım A', versions_count: 1 },
    { inventory_id: 2, domain: 'soon.example.test', days_remaining: 10, alert_level: 'high', team_id: 2, team_name: 'Takım B', versions_count: 3 },
    { inventory_id: 3, domain: 'gone.example.test', days_remaining: -4, alert_level: 'expired', team_id: 1, team_name: 'Takım A' },
    { inventory_id: 4, domain: 'mid.example.test', days_remaining: 25, alert_level: 'warning', team_id: null, versions_count: 2 },
  ]
  it('varsayılan sıra: sorunlular önce (dolmuş → yüksek → uyarı), sonra en yakın bitiş', () => {
    expect(sortManualRows(rows).map((r) => r.inventory_id)).toEqual([3, 2, 4, 1])
  })
  it('süzgeçler: durum, takım (takımsız dahil), arama', () => {
    expect(filterManualRows(rows, { status: 'expiring' }).map((r) => r.inventory_id)).toEqual([2, 4])
    expect(filterManualRows(rows, { status: 'expired' }).map((r) => r.inventory_id)).toEqual([3])
    expect(filterManualRows(rows, { status: 'renewed' }).map((r) => r.inventory_id)).toEqual([2, 4])
    expect(filterManualRows(rows, { status: 'problem' }).map((r) => r.inventory_id)).toEqual([2, 3, 4])
    expect(filterManualRows(rows, { team: '__none__' }).map((r) => r.inventory_id)).toEqual([4])
    expect(filterManualRows(rows, { team: '1' }).map((r) => r.inventory_id)).toEqual([1, 3])
    expect(filterManualRows(rows, { q: 'SOON' }).map((r) => r.inventory_id)).toEqual([2])
  })
  it('özet kutuları ve takım seçenekleri', () => {
    expect(manualKpis(rows)).toEqual({ total: 4, healthy: 1, expiring: 2, expired: 1, versions: 7 })
    expect(teamFilterOptions(rows)).toEqual({ options: [{ value: '1', label: 'Takım A' }, { value: '2', label: 'Takım B' }], hasNone: true })
  })
})

describe('manualCertModel — analiz ve yenileme', () => {
  const e = (ref, over = {}) => ({ ref, san: [], ...over })
  it('varsayılan seçim: sunucu önerisi → ilk girdi; truststore tanıma', () => {
    expect(defaultRef({ entries: [e('A'), e('B')], default_ref: 'B' })).toBe('B')
    expect(defaultRef({ entries: [e('A'), e('B')], default_ref: 'Z' })).toBe('A')
    expect(defaultRef({ entries: [] })).toBeNull()
    expect(looksLikeTruststore({ entries: [e('A', { is_ca: true }), e('B', { is_ca: true })] })).toBe(true)
    expect(looksLikeTruststore({ entries: [e('A', { is_ca: true, is_key_entry: true }), e('B', { is_ca: true })] })).toBe(false)
  })
  it('kalan geçerlilik süresi', () => {
    expect(validitySpan({ not_before: '2026-01-01T00:00:00', not_after: '2026-01-11T00:00:00', days_remaining: 4 })).toEqual({ total: 10, remaining: 4 })
    expect(validitySpan({ not_after: '2026-01-11' })).toBeNull()
  })
  it('karşılaştırma: daha eski bitiş, aynı parmak izi, veren / anahtar / SAN farkı', () => {
    const cur = { not_after: '2027-01-01T00:00:00', fingerprint: 'AA11', issuer: 'CA 1', subject: 'x', key_alg: 'RSA', key_size: 2048, san: ['a.example.test', 'b.example.test'] }
    const cmp = compareWithCurrent(cur, { ref: 'BB22', not_after: '2026-06-01T00:00:00', issuer: 'CA 2', subject: 'x', key_alg: 'EC', key_size: 256, san: ['a.example.test', 'c.example.test'] })
    expect(cmp).toMatchObject({ older: true, same: false, issuerChanged: true, subjectChanged: false, keyAlgChanged: true, sanAdded: ['c.example.test'], sanRemoved: ['b.example.test'] })
    expect(compareWithCurrent(cur, { ref: 'aa11', not_after: '2028-01-01T00:00:00' })).toMatchObject({ same: true, older: false })
  })
})

describe('manualCertModel — istek gövdeleri', () => {
  it('extractedFormData (2026-10-08): yalnız `extracted` (JSON dosya parçası) + ek alanlar; dosya / metin / şifre / notlar GİRMEZ', async () => {
    const extraction = {
      format: 'PKCS12', file_name: 'a.pfx', size_bytes: 10, entries: [{ alias: 'srv', key_entry: true, certs: ['QUJD'], extra: 'x' }],
      csr_pem: [], private_keys_removed: 1, password_used: true, notes: [{ code: 'ZIP_LIMIT' }], unsupported: null, password: 's3cret',
    }
    const fd = extractedFormData(extraction, { ref: 'AB', inventory: { team_id: 1 }, note: undefined })
    for (const k of ['file', 'text', 'password', 'note']) expect(fd.has(k), k).toBe(false)
    expect(fd.get('ref')).toBe('AB')
    expect(JSON.parse(fd.get('inventory'))).toEqual({ team_id: 1 })
    const part = fd.get('extracted')
    expect(part.name).toBe('extracted.json')
    expect(part.type).toMatch(/application\/json/)
    const body = JSON.parse(await part.text())
    expect(body).toEqual({ format: 'PKCS12', file_name: 'a.pfx', size_bytes: 10,
      entries: [{ alias: 'srv', key_entry: true, certs: ['QUJD'] }], csr_pem: [], private_keys_removed: 1 })
    expect(wirePayload(null)).toEqual({ format: null, file_name: null, size_bytes: 0, entries: [], csr_pem: [], private_keys_removed: 0 })
  })
  it('extractionErrorKey: her ayıklama hatası TR + EN sözlükte; tanınmayan neden → UNKNOWN; tarayıcı not kodları sözlükte', () => {
    for (const reason of ['TOO_LARGE', 'BKS', 'PKCS12_ALGORITHM', 'PKCS12_FORMAT', 'TOO_MANY_CERTS', 'TIMEOUT', 'ZIP_UNREADABLE', 'UNREADABLE', 'UNKNOWN', 'NOPE']) {
      const [key] = extractionErrorKey({ reason })
      expect(TR[key], key).toBeTruthy()
      expect(EN[key], key).toBeTruthy()
    }
    expect(extractionErrorKey({ reason: 'NOPE' })[0]).toBe('mcert.extract.UNKNOWN')
    expect(extractionErrorKey({ reason: 'TOO_MANY_CERTS', max: 200 })).toEqual(['mcert.extract.TOO_MANY_CERTS', 200])
    for (const code of CLIENT_NOTE_CODES) {
      expect(TR[`mcert.warn.${code}`], code).toBeTruthy()
      expect(EN[`mcert.warn.${code}`], code).toBeTruthy()
    }
  })
  it('inventoryPayload: envanter ekleme gövdesiyle aynı snake_case; ağ alanları varsayılan', () => {
    const p = inventoryPayload({ team_id: '3', group_name: ' G ', tags: 't1,t2', tier: '2', description: ' d ', owner: 'o',
      platform: 'OPENSHIFT', platform_detail: '', notification_group_id: '', noc_notify: true, noc_group_ids: [] })
    expect(p).toMatchObject({ team_id: 3, group_name: 'G', tags: 't1,t2', tier: 2, description: 'd', owner: 'o', platform: 'OPENSHIFT',
      platform_detail: null, notification_group_id: null, noc_notify: true, noc_group_ids: null, port: 443, use_proxy: false,
      tls_mode: null, timeout_seconds: null, check_interval_hours: null, expected_fingerprint: null, ug_team_id: null, active: true })
    expect(p).not.toHaveProperty('domain')
    expect(p.openshift).toBe(false)
  })
  it('splitServerErrors: alanlar, toplu satırlar, kalan', () => {
    expect(splitServerErrors({ domain: 'D', 'inventory.team_id': 'T', 'items[1].domain': 'R1', 'items.2.domain': 'R2', other: 'X' }))
      .toEqual({ fields: { domain: 'D', team_id: 'T' }, rows: { 1: 'R1', 2: 'R2' }, rest: ['X'] })
  })
})
