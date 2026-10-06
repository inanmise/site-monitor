// Manuel (dosyadan yüklenen) sertifika e2e mock'ları (2026-10-06) — analiz yanıtı, sürüm ayrıntısı, envanter kaydı.
// `mockApi(page)`'in ÜSTÜNE kaydedilir (Playwright'ta son kayıt önce koşar). Gerçek kişi/kurum adı yok (example.test).
import { MANUAL_CERTS } from './monitorMocks.js'

export const PERMS = { 'inventory.list': { view: true }, 'inventory.crud': { view: true, edit: true }, 'notes.read': { view: true } }
const json = (body) => (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
export const DOMAIN = MANUAL_CERTS[0].domain
export const PEM = '-----BEGIN CERTIFICATE-----\nMIIBszCCAVmgAwIBAgIUExampleOnlyNotARealCertificate0wCgYIKoZIzj0EAwIw\n-----END CERTIFICATE-----\n'
const iso = (days) => new Date(Date.now() + days * 86_400_000).toISOString().slice(0, 19)
const LONG_SAN = Array.from({ length: 9 }, (_, i) => `odeme-servisleri-yedek-bolge-${i}.cok-uzun-bir-alt-alan-adi.example.test`)

export const ANALYSIS = {
  format: 'PEM', file_name: 'server.pem', size_bytes: PEM.length, needs_password: false, password_error: false,
  warnings: [{ code: 'PRIVATE_KEY_IGNORED', severity: 'info', params: { count: 1 } },
    { code: 'CHAIN_INCOMPLETE', severity: 'warn', params: { missing_issuer: 'Example Trust Services RSA Organization Validation Secure Server CA 2026' } }],
  csr: null, default_ref: 'AB'.repeat(32),
  entries: [{
    ref: 'AB'.repeat(32), alias: 'odeme-servisleri-uretim-anahtari', is_key_entry: true, is_ca: false, self_signed: false,
    subject: 'CN=odeme.example.test', subject_dn: 'CN=odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.test,OU=Ödeme Sistemleri,O=Example Ltd,L=İstanbul,C=TR',
    cn: 'odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.test',
    issuer: 'Example Trust Services RSA Organization Validation Secure Server CA 2026', issuer_dn: 'CN=Example Trust Services RSA Organization Validation Secure Server CA 2026,O=Example Trust,C=GB',
    serial_number: '0A1B2C3D4E5F60718293A4B5C6D7E8F9', not_before: iso(-30), not_after: iso(335), days_remaining: 335, status: 'valid',
    san: LONG_SAN, key_alg: 'RSA', key_size: 2048, signature_algorithm: 'SHA256withRSA', key_usage: ['digitalSignature'], ext_key_usage: ['serverAuth', 'clientAuth'],
    cert_type: 'LEAF', chain: [{ subject: 'CN=Example Intermediate CA 2026', issuer: 'CN=Example Root CA', not_after: iso(900), fingerprint: 'CD'.repeat(32), is_ca: true, days_remaining: 900 }],
    chain_complete: false, trust_status: 'UNKNOWN', weak: { signature: 'OK', key_size: 'OK' }, suggested_key: 'odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.test',
    warnings: [{ code: 'NETWORK_MONITORED', severity: 'info', params: { domain: 'odeme.example.test' } }],
    matches: { already_tracked: null, same_subject: [], network_monitored: [{ domain: 'odeme.example.test' }] },
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
  await page.route((u) => new URL(u).pathname === '/api/admin/inventory/by-domain',
    json({ success: true, data: { id: 77, domain: DOMAIN, team_id: 1, team_name: 'Takım A', cert_source: 'MANUAL', manual_version: 3, can_manage: true } }))
}
