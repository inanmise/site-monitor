/**
 * Sertifika hiyerarşisi test fixture'ı — GERÇEK tel biçimi: `GET /api/manual-certs/{id}/versions/{vid}/chain` →
 * ManualCertificateHierarchy düğümleri (snake_case, KÖK İLK). Adlar `example.test` (gerçek kurum verisi yok); PEM
 * gövdeleri sahte (yalnız biçim — testler içeriği karşılaştırır, çözmez).
 */
const pem = (tag) => `-----BEGIN CERTIFICATE-----\nTUlJQi${tag}FAKE\n-----END CERTIFICATE-----\n`

export function hierarchyNode(over = {}) {
  return {
    role: 'leaf', depth: 2, position: 0, head: true,
    subject_dn: 'CN=odeme-api.example.test,OU=Ops,O=Example Test,L=Istanbul,ST=Marmara,C=TR',
    subject: { cn: 'odeme-api.example.test', o: 'Example Test', ou: 'Ops', l: 'Istanbul', st: 'Marmara', c: 'TR' },
    issuer_dn: 'CN=Test Issuing CA,O=Example Test,C=TR',
    issuer: { cn: 'Test Issuing CA', o: 'Example Test', ou: null, l: null, st: null, c: 'TR' },
    serial_number: '5F3A01', not_before: '2026-09-17T00:00:00', not_after: '2027-10-19T00:00:00',
    days_remaining: 377, expired: false, not_yet_valid: false,
    signature_algorithm: 'SHA256withRSA', public_key_algorithm: 'RSA', public_key_size: 2048,
    san: ['odeme-api.example.test', 'odeme-api-internal.example.test'],
    key_usage: ['Digital Signature', 'Key Encipherment'], ext_key_usage: ['TLS Web Server'],
    is_ca: false, path_length: null, self_signed: false, issuer_missing: false,
    sha256_fingerprint: 'CD34EF56AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12CD34',
    sha1_fingerprint: 'AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12',
    pem: pem('LEAF'),
    ...over,
  }
}

/** Kök → ara → yaprak (kök dosyada). */
export function fullChainNodes() {
  return [
    hierarchyNode({
      role: 'root', depth: 0, position: 2, head: false,
      subject_dn: 'CN=Test Root CA,O=Example Test,C=TR',
      subject: { cn: 'Test Root CA', o: 'Example Test', ou: null, l: null, st: null, c: 'TR' },
      issuer_dn: 'CN=Test Root CA,O=Example Test,C=TR',
      issuer: { cn: 'Test Root CA', o: 'Example Test', ou: null, l: null, st: null, c: 'TR' },
      serial_number: '01', not_before: '2026-10-07T00:00:00', not_after: '2036-10-05T00:00:00', days_remaining: 3650,
      san: [], key_usage: ['Certificate Signing', 'CRL Signing'], ext_key_usage: [],
      is_ca: true, path_length: null, self_signed: true,
      sha256_fingerprint: '11'.repeat(32), sha1_fingerprint: '22'.repeat(20), pem: pem('ROOT'),
    }),
    hierarchyNode({
      role: 'intermediate', depth: 1, position: 1, head: false,
      subject_dn: 'CN=Test Issuing CA,O=Example Test,C=TR',
      subject: { cn: 'Test Issuing CA', o: 'Example Test', ou: null, l: null, st: null, c: 'TR' },
      issuer_dn: 'CN=Test Root CA,O=Example Test,C=TR',
      issuer: { cn: 'Test Root CA', o: 'Example Test', ou: null, l: null, st: null, c: 'TR' },
      serial_number: '1001', not_before: '2026-10-07T00:00:00', not_after: '2026-10-27T00:00:00', days_remaining: 20,
      san: [], key_usage: ['Certificate Signing', 'CRL Signing'], ext_key_usage: [],
      is_ca: true, path_length: 0,
      sha256_fingerprint: '33'.repeat(32), sha1_fingerprint: '44'.repeat(20), pem: pem('INTER'),
    }),
    hierarchyNode(),
  ]
}

/** Yalnız yaprak yüklenmiş sürüm — kök (ve ara) dosyada yok. */
export function leafOnlyNodes() {
  return [hierarchyNode({ depth: 0, issuer_missing: true })]
}

/** `versionChain` yanıtı. */
export function chainResponse(nodes = fullChainNodes(), over = {}) {
  const top = nodes[0]
  return {
    success: true,
    data: {
      inventory_id: 7, domain: 'odeme-api.example.test', version_id: 102, version: 2, current: true,
      certificate_count: nodes.length, issuer_missing: !!top?.issuer_missing,
      missing_issuer_dn: top?.issuer_missing ? top.issuer_dn : null, nodes, ...over,
    },
  }
}
