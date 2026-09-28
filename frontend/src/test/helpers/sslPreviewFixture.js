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

