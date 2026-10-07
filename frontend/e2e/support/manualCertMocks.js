// Manuel (dosyadan yüklenen) sertifika e2e mock'ları (2026-10-06) — analiz yanıtı, sürüm ayrıntısı, envanter kaydı.
// `mockApi(page)`'in ÜSTÜNE kaydedilir (Playwright'ta son kayıt önce koşar). Gerçek kişi/kurum adı yok (example.test).
import { MANUAL_CERTS } from './monitorMocks.js'

export const PERMS = { 'inventory.list': { view: true }, 'inventory.crud': { view: true, edit: true }, 'notes.read': { view: true } }
const json = (body) => (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
export const DOMAIN = MANUAL_CERTS[0].domain
/**
 * GERÇEK ama yalnız AÇIK bir test sertifikası (2026-10-08: dosya artık tarayıcıda ayıklanır — sahte gövde okunamazdı).
 * Kendinden imzalı, odeme.example.test, 2046'ya kadar geçerli; anahtarı üretimde atıldı (depoda anahtar YOK).
 */
export const PEM = `-----BEGIN CERTIFICATE-----
MIIB3DCCAYKgAwIBAgIUbJVmUZhE7oE0u4L8iJj6pD8J3JUwCgYIKoZIzj0EAwIw
NDEbMBkGA1UEAwwSb2RlbWUuZXhhbXBsZS50ZXN0MRUwEwYDVQQKDAxFeGFtcGxl
IFRlc3QwHhcNMjYxMDA3MjEzNTQ5WhcNNDYxMDAyMjEzNTQ5WjA0MRswGQYDVQQD
DBJvZGVtZS5leGFtcGxlLnRlc3QxFTATBgNVBAoMDEV4YW1wbGUgVGVzdDBZMBMG
ByqGSM49AgEGCCqGSM49AwEHA0IABA8AuIw2aMo16eCJczBkO1iPj6pjg9NiHdfc
2SlfJuj7XlWcfej06OOKMZ67rOvI7wF6M0kPMGnhbpfjM3h6rC6jcjBwMB0GA1Ud
DgQWBBSAB+At8cpjeIoVzecPK11279woIDAfBgNVHSMEGDAWgBSAB+At8cpjeIoV
zecPK11279woIDAPBgNVHRMBAf8EBTADAQH/MB0GA1UdEQQWMBSCEm9kZW1lLmV4
YW1wbGUudGVzdDAKBggqhkjOPQQDAgNIADBFAiEAwrdVmS6b3Mg54B6DjITTORNg
4XIIxln5dYmpyabMUZgCIAnVBODXHLFprUhY2cbG5dT61BwoJR1/PS+9HSTLPiym
-----END CERTIFICATE-----
`
/** Sahte "özel anahtar" bloğu (anahtar DEĞİL — tanınabilir bir dolgu metni); tarayıcı bunu ayıklayıp saymalı, göndermemeli. */
export const FAKE_KEY_BODY = 'U0FIVEUtQU5BSFRBUi1ZQUxOSVotVEVTVC1ET0xHVVNVLTAwMDE='
export const PEM_WITH_FAKE_KEY = `${PEM}${['-----BEGIN', 'PRIVATE KEY-----'].join(' ')}\n${FAKE_KEY_BODY}\n${['-----END', 'PRIVATE KEY-----'].join(' ')}\n`
const iso = (days) => new Date(Date.now() + days * 86_400_000).toISOString().slice(0, 19)
const LONG_SAN = Array.from({ length: 9 }, (_, i) => `odeme-servisleri-yedek-bolge-${i}.cok-uzun-bir-alt-alan-adi.example.test`)

const LEAF_DN = 'CN=odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.test,OU=Ödeme Sistemleri,O=Example Ltd,L=İstanbul,C=TR'
const INTER_DN = 'CN=Example Trust Services RSA Organization Validation Secure Server CA 2026,O=Example Trust,C=GB'
const ROOT_DN = 'CN=Example Trust Services Root Certification Authority 2020,O=Example Trust,C=GB'

/**
 * Çevrim-dışı değerlendirme sonucu (2026-10-07) — `/check-preview` (manuel kayıt) ve analiz girdisinin `preview`'ı: ağ
 * sertifikasının SSL sekmesiyle AYNI biçim, `via: 'upload'`; zincir yaprak → ara → kök (en geniş hâl: uzun DN'ler, 9 SAN).
 */
export function uploadPreview(domain) {
  return {
    domain, subject: 'odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.test', issuer: 'Example Trust',
    issuer_cn: 'Example Trust Services RSA Organization Validation Secure Server CA 2026',
    not_before: iso(-30), not_after: iso(335), days_remaining: 335, warning: false, status: 'valid', san: LONG_SAN, checked_at: iso(0),
    serial_number: '0A1B2C3D4E5F60718293A4B5C6D7E8F9', signature_algorithm: 'SHA256withRSA', public_key_algorithm: 'RSA', public_key_size: 2048,
    subject_dn: LEAF_DN, issuer_dn: INTER_DN, key_usage: ['digitalSignature'], ext_key_usage: ['serverAuth', 'clientAuth'], is_ca: false,
    ocsp_url: null, crl_url: null, cert_type: 'Organization Validated (OV) — Multi-Domain (SAN)',
    source_ip: null, source_port: null, peer_ip: null, peer_port: null, tls_version: null, cipher_suite: null, alpn: null,
    chain_status: 'VALID', intermediate_expiry: iso(900), intermediate_days_remaining: 900,
    chain: [
      { position: 0, subject: LEAF_DN, issuer: INTER_DN, not_after: iso(335), days_remaining: 335, is_root: false, is_leaf: true,
        not_before: iso(-30), serial_number: '0A1B2C3D4E5F60718293A4B5C6D7E8F9', signature_algorithm: 'SHA256withRSA', expired: false },
      { position: 1, subject: INTER_DN, issuer: ROOT_DN, not_after: iso(900), days_remaining: 900, is_root: false, is_leaf: false,
        not_before: iso(-900), serial_number: '77AA', signature_algorithm: 'SHA384withRSA', expired: false },
      { position: 2, subject: ROOT_DN, issuer: ROOT_DN, not_after: iso(3000), days_remaining: 3000, is_root: true, is_leaf: false,
        not_before: iso(-2000), serial_number: '01', signature_algorithm: 'SHA384withRSA', expired: false },
    ],
    fingerprint: 'AB'.repeat(32), revocation_status: 'UNKNOWN', trust_status: 'TRUSTED', deployment_status: 'UNKNOWN',
    resolved_ip: null, hsts: null, http_status: null, via: 'upload', tls_mode_used: null, elapsed_ms: 7, manual: true, manual_version: 3,
    assessment: { hostname: 'NA', protocol: 'NA', protocol_latest: false, cipher: 'NA', pfs: 'NA', signature: 'OK', key_size: 'OK' },
    security_flags: [],
  }
}

export const ANALYSIS = {
  format: 'PEM', file_name: 'server.pem', size_bytes: PEM.length, needs_password: false, password_error: false,
  warnings: [{ code: 'PRIVATE_KEY_KEPT_LOCAL', severity: 'info', params: { count: 1 } }],
  // 2026-10-07: dosyadaki yaprak + ara + kök TEK girdidir; zincir girdinin `preview`'ında (SSL sekmesiyle aynı biçim)
  csr: null, default_ref: 'AB'.repeat(32), certificate_count: 3,
  entries: [{
    ref: 'AB'.repeat(32), alias: 'odeme-servisleri-uretim-anahtari', is_key_entry: true, is_ca: false, self_signed: false,
    subject: 'CN=odeme.example.test', subject_dn: 'CN=odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.test,OU=Ödeme Sistemleri,O=Example Ltd,L=İstanbul,C=TR',
    cn: 'odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.test',
    issuer: 'Example Trust Services RSA Organization Validation Secure Server CA 2026', issuer_dn: 'CN=Example Trust Services RSA Organization Validation Secure Server CA 2026,O=Example Trust,C=GB',
    serial_number: '0A1B2C3D4E5F60718293A4B5C6D7E8F9', not_before: iso(-30), not_after: iso(335), days_remaining: 335, status: 'valid',
    san: LONG_SAN, key_alg: 'RSA', key_size: 2048, signature_algorithm: 'SHA256withRSA', key_usage: ['digitalSignature'], ext_key_usage: ['serverAuth', 'clientAuth'],
    cert_type: 'LEAF',
    chain: [
      { subject: 'Example Trust Services RSA Organization Validation Secure Server CA 2026', issuer: 'Example Trust Services Root Certification Authority 2020', not_after: iso(900), fingerprint: 'CD'.repeat(32), is_ca: true, days_remaining: 900 },
      { subject: 'Example Trust Services Root Certification Authority 2020', issuer: 'Example Trust Services Root Certification Authority 2020', not_after: iso(3000), fingerprint: 'EF'.repeat(32), is_ca: true, days_remaining: 3000 },
    ],
    chain_complete: true, trust_status: 'TRUSTED', weak: { signature: 'OK', key_size: 'OK' }, suggested_key: 'odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.test',
    warnings: [{ code: 'NETWORK_MONITORED', severity: 'info', params: { domain: 'odeme.example.test' } }],
    matches: { already_tracked: null, same_subject: [], network_monitored: [{ domain: 'odeme.example.test' }] },
    preview: uploadPreview('odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.test'),
  }],
}
export const DETAIL = {
  inventory_id: 77, domain: DOMAIN, can_manage: true,
  versions: [
    { id: 903, version: 3, current: true, fingerprint: 'AB'.repeat(32), subject: 'CN=odeme.example.test', subject_dn: ANALYSIS.entries[0].subject_dn,
      issuer: ANALYSIS.entries[0].issuer, issuer_dn: ANALYSIS.entries[0].issuer_dn, serial_number: '0A1B', not_before: iso(-30), not_after: iso(-4),
      san: LONG_SAN, key_alg: 'RSA', key_size: 2048, signature_algorithm: 'SHA256withRSA', chain_count: 3,
      file_name: 'odeme-servisleri-keystore-2026-uretim-ortami-tam-zincir-yedek-kopya.jks', file_format: 'JKS', source_alias: 'odeme-servisleri-uretim-anahtari',
      uploaded_by_name: 'Kişi A', uploaded_at: iso(-3), note: 'TALEP-1234 ile yenilendi — zincir tamamlandı, OpenShift keystore güncellendi.', key_changed: true,
      san_added: LONG_SAN.slice(0, 2), san_removed: ['eski-bolge.cok-uzun-bir-alt-alan-adi.example.test'] },
    { id: 902, version: 2, current: false, fingerprint: 'CD'.repeat(32), subject: 'CN=odeme.example.test', issuer: 'Example CA', not_before: iso(-400), not_after: iso(-35),
      san: ['odeme.example.test'], key_alg: 'RSA', key_size: 2048, file_name: 'server.pem', file_format: 'PEM', uploaded_by_name: 'Kişi B', uploaded_at: iso(-400),
      superseded_at: iso(-3), superseded_by: 'kisia', key_changed: false, san_added: [], san_removed: [] },
  ],
}

export async function routeManualCerts(page) {
  await page.route((u) => new URL(u).pathname === '/api/manual-certs/analyze', json({ success: true, data: ANALYSIS }))
  await page.route((u) => new URL(u).pathname === '/api/manual-certs/77', json({ success: true, data: DETAIL }))
  // Sertifika penceresinin SSL sekmesi (manuelde de açılış sekmesi, 2026-10-07): çevrim-dışı önizleme
  await page.route((u) => decodeURIComponent(new URL(u).pathname) === `/api/check-preview/${DOMAIN}`,
    json({ success: true, data: uploadPreview(DOMAIN), timestamp: iso(0) }))
  await page.route((u) => new URL(u).pathname === '/api/admin/inventory/by-domain',
    json({ success: true, data: { id: 77, domain: DOMAIN, team_id: 1, team_name: 'Takım A', cert_source: 'MANUAL', manual_version: 3, can_manage: true } }))
}
