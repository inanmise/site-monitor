/**
 * SSL Kontrol sekmesi test fixture'ı — GERÇEK tel biçimi: `GET /api/check-preview/{domain}` →
 * CertificateCheckerService.parseLeafCert + tryCheckOnce + ChainValidationService.analyzeChain anahtarları ve
 * CertificateController.putPreviewAssessment'in eklediği `assessment` / `security_flags` (snake_case, UTC `Z`siz).
 */
export function healthyPreview(over = {}) {
  return {
    domain: 'www.example.com', subject: 'www.example.com', issuer: 'Example Trust Ltd', issuer_cn: 'Example TLS RSA CA 2026',
    not_before: '2026-07-01T00:00:00', not_after: '2027-07-01T23:59:59', days_remaining: 276, warning: false, status: 'valid',
    san: ['www.example.com', 'example.com'], checked_at: '2026-09-28T09:15:00',
    serial_number: '0A1B2C3D4E5F', signature_algorithm: 'SHA256withRSA', public_key_algorithm: 'RSA', public_key_size: 2048,
    subject_dn: 'CN=www.example.com,O=Example Ltd,L=London,C=GB',
    issuer_dn: 'CN=Example TLS RSA CA 2026,O=Example Trust Ltd,C=GB',
    key_usage: ['digitalSignature', 'keyEncipherment'], ext_key_usage: ['serverAuth'], is_ca: false,
    ocsp_url: 'http://ocsp.example.com', crl_url: 'http://crl.example.com/ca.crl',
    cert_type: 'Organization Validated (OV) — Multi-Domain (SAN)',
    source_ip: '10.0.0.5', source_port: 51234, peer_ip: '203.0.113.10', peer_port: 443,
    tls_version: 'TLSv1.3', cipher_suite: 'TLS_AES_256_GCM_SHA384', alpn: 'h2',
    chain_status: 'VALID', intermediate_expiry: '2031-01-01T00:00:00', intermediate_days_remaining: 1556,
    chain: [
      { position: 0, subject: 'CN=www.example.com,O=Example Ltd,L=London,C=GB', issuer: 'CN=Example TLS RSA CA 2026,O=Example Trust Ltd,C=GB',
        not_after: '2027-07-01T23:59:59', days_remaining: 276, is_root: false, is_leaf: true, not_before: '2026-07-01T00:00:00',
        serial_number: '0A1B2C3D4E5F', signature_algorithm: 'SHA256withRSA', expired: false },
      { position: 1, subject: 'CN=Example TLS RSA CA 2026,O=Example Trust Ltd,C=GB', issuer: 'CN=Example Root CA,O=Example Trust Ltd,C=GB',
        not_after: '2031-01-01T00:00:00', days_remaining: 1556, is_root: false, is_leaf: false, not_before: '2021-01-01T00:00:00',
        serial_number: '77AA', signature_algorithm: 'SHA384withRSA', expired: false },
    ],
    fingerprint: 'AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12',
    revocation_status: 'VALID', trust_status: 'TRUSTED', deployment_status: 'UNKNOWN',
    resolved_ip: '203.0.113.10', hsts: true, http_status: 200, via: 'direct', tls_mode_used: 'default', elapsed_ms: 412, port: 443,
    assessment: { hostname: 'OK', protocol: 'OK', protocol_latest: true, cipher: 'OK', pfs: 'OK', signature: 'OK', key_size: 'OK' },
    security_flags: [],
    ...over,
  }
}

/**
 * Dosyadan yüklenen (manuel) sertifikanın `/check-preview` yanıtı (2026-10-07) — ManualCertificateEvaluationService'in
 * çevrim-dışı sonuç haritası + CertificateController.putManualPreviewAssessment: `via: 'upload'`, ağa özgü alanlar null,
 * ağa özgü hükümler NA; dosyadaki zincir yaprak → ara → kök (kök dosyada → rootSent).
 */
export function uploadPreview(over = {}) {
  return {
    domain: 'odeme-api.example.test', subject: 'odeme-api.example.test', issuer: 'Example Test', issuer_cn: 'Test Issuing CA',
    not_before: '2026-09-17T00:00:00', not_after: '2027-10-19T00:00:00', days_remaining: 377, warning: false, status: 'valid',
    san: ['odeme-api.example.test', 'odeme-api-internal.example.test'], checked_at: '2026-10-07T09:15:00',
    serial_number: '5F3A', signature_algorithm: 'SHA256withRSA', public_key_algorithm: 'RSA', public_key_size: 2048,
    subject_dn: 'CN=odeme-api.example.test,O=Example Test,L=Istanbul,C=TR', issuer_dn: 'CN=Test Issuing CA,O=Example Test',
    key_usage: ['digitalSignature'], ext_key_usage: ['serverAuth', 'clientAuth'], is_ca: false,
    ocsp_url: null, crl_url: null, cert_type: 'Domain Validated (DV) — Multi-Domain (SAN)',
    source_ip: null, source_port: null, peer_ip: null, peer_port: null, tls_version: null, cipher_suite: null, alpn: null,
    chain_status: 'VALID', intermediate_expiry: '2031-10-06T00:00:00', intermediate_days_remaining: 1825,
    chain: [
      { position: 0, subject: 'CN=odeme-api.example.test,O=Example Test,L=Istanbul,C=TR', issuer: 'CN=Test Issuing CA,O=Example Test',
        not_after: '2027-10-19T00:00:00', days_remaining: 377, is_root: false, is_leaf: true, not_before: '2026-09-17T00:00:00',
        serial_number: '5F3A', signature_algorithm: 'SHA256withRSA', expired: false },
      { position: 1, subject: 'CN=Test Issuing CA,O=Example Test', issuer: 'CN=Test Root CA,O=Example Test',
        not_after: '2031-10-06T00:00:00', days_remaining: 1825, is_root: false, is_leaf: false, not_before: '2026-10-07T00:00:00',
        serial_number: '1001', signature_algorithm: 'SHA256withRSA', expired: false },
      { position: 2, subject: 'CN=Test Root CA,O=Example Test', issuer: 'CN=Test Root CA,O=Example Test',
        not_after: '2036-10-05T00:00:00', days_remaining: 3650, is_root: true, is_leaf: false, not_before: '2026-10-07T00:00:00',
        serial_number: '01', signature_algorithm: 'SHA256withRSA', expired: false },
    ],
    fingerprint: 'CD34EF56AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12CD34',
    revocation_status: 'UNKNOWN', trust_status: 'UNTRUSTED', deployment_status: 'UNKNOWN',
    resolved_ip: null, hsts: null, http_status: null, via: 'upload', tls_mode_used: null, elapsed_ms: 6,
    manual: true, manual_version: 2,
    assessment: { hostname: 'NA', protocol: 'NA', protocol_latest: false, cipher: 'NA', pfs: 'NA', signature: 'OK', key_size: 'OK' },
    security_flags: ['UNTRUSTED_CA'],
    ...over,
  }
}

