// Tarayıcı gibi sertifika hiyerarşisi e2e mock'ları (2026-10-07) — `routeManualCerts(page)`'in ÜSTÜNE kaydedilir
// (Playwright'ta son kayıt önce koşar): SSL sekmesinin önizlemesine kayıt + güncel sürüm kimliği, sürüm zinciri ucu
// (`/api/manual-certs/77/versions/{id}/chain`, kök İLK). En geniş hâl: uzun DN'ler, 9 SAN, uzun parmak izleri.
// Gerçek kişi/kurum adı yok (example.test).
import { DOMAIN, uploadPreview } from './manualCertMocks.js'

const json = (body) => (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
const iso = (days) => new Date(Date.now() + days * 86_400_000).toISOString().slice(0, 19)
const LONG_SAN = Array.from({ length: 9 }, (_, i) => `odeme-servisleri-yedek-bolge-${i}.cok-uzun-bir-alt-alan-adi.example.test`)
const pem = (tag) => `-----BEGIN CERTIFICATE-----\nMIIB${tag}ExampleOnlyNotARealCertificate\n-----END CERTIFICATE-----\n`

const LEAF = {
  cn: 'odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.test', o: 'Example Ltd', ou: 'Ödeme Sistemleri',
  l: 'İstanbul', st: 'Marmara', c: 'TR',
}
const INTER = { cn: 'Example Trust Services RSA Organization Validation Secure Server CA 2026', o: 'Example Trust', ou: null, l: null, st: null, c: 'GB' }
const ROOT = { cn: 'Example Trust Services Root Certification Authority 2020', o: 'Example Trust', ou: null, l: null, st: null, c: 'GB' }
const dn = (p) => ['cn', 'ou', 'o', 'l', 'st', 'c'].filter((k) => p[k]).map((k) => `${k.toUpperCase()}=${p[k]}`).join(',')

function node(role, depth, subject, issuer, over = {}) {
  return {
    role, depth, position: 2 - depth, head: role === 'leaf', subject_dn: dn(subject), subject, issuer_dn: dn(issuer), issuer,
    serial_number: '0A1B2C3D4E5F60718293A4B5C6D7E8F9', not_before: iso(-30), not_after: iso(335), days_remaining: 335,
    expired: false, not_yet_valid: false, signature_algorithm: 'SHA256withRSA', public_key_algorithm: 'RSA', public_key_size: 4096,
    san: [], key_usage: ['Certificate Signing', 'CRL Signing'], ext_key_usage: [], is_ca: true, path_length: null,
    self_signed: false, issuer_missing: false, sha256_fingerprint: 'AB'.repeat(32), sha1_fingerprint: 'CD'.repeat(20),
    pem: pem(role.toUpperCase()), ...over,
  }
}

export const HIERARCHY_NODES = [
  node('root', 0, ROOT, ROOT, { self_signed: true, not_after: iso(3000), days_remaining: 3000, serial_number: '01' }),
  node('intermediate', 1, INTER, ROOT, { path_length: 0, not_after: iso(900), days_remaining: 900, serial_number: '77AA' }),
  node('leaf', 2, LEAF, INTER, {
    san: LONG_SAN, key_usage: ['Digital Signature', 'Key Encipherment'], ext_key_usage: ['TLS Web Server', 'TLS Web Client'],
    is_ca: false, public_key_size: 2048,
  }),
]

export const PREVIEW_IDS = { inventory_id: 77, manual_version_id: 903 }

export async function routeHierarchy(page, nodes = HIERARCHY_NODES) {
  await page.route((u) => decodeURIComponent(new URL(u).pathname) === `/api/check-preview/${DOMAIN}`,
    json({ success: true, data: { ...uploadPreview(DOMAIN), ...PREVIEW_IDS }, timestamp: iso(0) }))
  await page.route((u) => /^\/api\/manual-certs\/77\/versions\/\d+\/chain$/.test(new URL(u).pathname), (r) => {
    const vid = Number(new URL(r.request().url()).pathname.split('/')[5])
    return r.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ success: true, data: {
        inventory_id: 77, domain: DOMAIN, version_id: vid, version: vid === 903 ? 3 : 2, current: vid === 903,
        certificate_count: nodes.length, issuer_missing: false, missing_issuer_dn: null, nodes,
      } }),
    })
  })
}
