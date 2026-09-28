// Sertifika detay penceresi (Genel Bakış kartı → CertificateModal) için API mock'u — `mockApi(page)`'in ÜSTÜNE kaydedilir:
// Playwright'ta son kaydedilen rota önce koşar, eşleşmeyen istek `route.fallback()` ile mockApi'ye düşer. Tel biçimi
// GERÇEK (snake_case; zaman UTC ve `Z`siz): /api/certificates (CertificateDto — envanter birleşimli liste satırı),
// /api/history/{d} (GEÇMİŞ satırı — `historyRow`: envanter alanları yok, TLS üçlüsü var; zarfta 7/24 alanları),
// /api/noc/groups/options, /api/check-preview/{d}
// (CertificateCheckerService + CertificateController.putPreviewAssessment), /api/admin/notes/{d} (CertificateNote).
// Gerçek kişi/kurum adı YOK (example.com, Kişi A, Takım A).

const H = 3_600_000
const D = 24 * H
/** UTC, `Z`siz — sunucunun biçimi. Göreli zaman ("2 sa önce") gerçek saatle tutarlı olsun diye Date.now() tabanlı. */
const iso = (msAgo) => new Date(Date.now() - msAgo).toISOString().slice(0, 19)
const inDays = (d) => new Date(Date.now() + d * D).toISOString().slice(0, 19)

/** `/api/me/permissions` — envanter görme + silme (pencerede Envanter sekmesi ve Sil düğmesi de çizilsin: TÜM sekmeler). */
export const PERMS = { 'inventory.list': { view: true }, 'inventory.crud': { view: true, edit: true }, 'notes.read': { view: true }, 'notes.crud': { edit: true } }

export const CERT_DOMAINS = {
  healthy: 'www.example.com',
  problem: 'legacy.example.com',
  down: 'down.example.com',
  quiet: 'shop.example.com',
}

function certRow(domain, i, over = {}) {
  return {
    domain, port: 443, status: 'valid', warning: false, days_remaining: 180 - i * 40, not_before: iso(200 * D), not_after: inDays(180 - i * 40),
    issuer: 'Example Trust Ltd', issuer_cn: 'Example TLS RSA CA 2026', subject: domain, checked_at: iso((i + 1) * 20 * 60_000),
    san: [domain, `api.${domain.replace(/^www\./, '')}`], team_id: 1, team_name: 'Takım A', tier: 2, alert_level: null,
    serial_number: `0A1B2C3D${i}`, signature_algorithm: 'SHA256withRSA', public_key_algorithm: 'RSA', public_key_size: 2048,
    subject_dn: `CN=${domain},O=Example Ltd,C=GB`, issuer_dn: 'CN=Example TLS RSA CA 2026,O=Example Trust Ltd,C=GB',
    chain_status: 'VALID', revocation_status: 'VALID', trust_status: 'TRUSTED', fingerprint: 'AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12',
    security_flags: [], secure: true,
    // 7/24 (2026-09-28): envanterden — CertificateDto `noc_notify` + `noc_group_ids` (boş = varsayılan gruplar)
    noc_notify: i % 2 === 0, noc_group_ids: [],
    ...over,
  }
}

/** `/api/noc/groups/options` (NocController.groupOptions) — e-posta YOK; göstergenin "etkin" hükmü + alıcı grup adları. */
export const NOC_OPTIONS = {
  groups: [{ id: 1, name: 'NOC Ana', is_default: true, active: true }, { id: 2, name: 'NOC Gece', is_default: false, active: true }],
  disabled_types: [], has_active_group: true, min_level: 'CRITICAL',
}

export const CERTS = [
  certRow(CERT_DOMAINS.healthy, 0, {
    san: ['www.example.com', 'example.com', 'api.example.com', 'cdn.example.com', 'static.example.com', 'odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.com'],
  }),
  certRow(CERT_DOMAINS.problem, 1, { days_remaining: -6, not_after: inDays(-6), status: 'warning', warning: true, alert_level: 'critical',
    trust_status: 'UNTRUSTED', security_flags: ['HOSTNAME_MISMATCH', 'UNTRUSTED_CA'], secure: false }),
  certRow(CERT_DOMAINS.down, 2, { status: 'error', days_remaining: null, error: 'Connection timeout after 10s' }),
  certRow(CERT_DOMAINS.quiet, 3),
]

/**
 * Kontrolün TLS üçlüsü — `tls_assessment` sunucuda CertificateHealthRules'tan (protocolStatus / isLatestProtocol /
 * cipherStatus): TLSv1 → FAIL, 3DES → WEAK = FAIL; TLS 1.3 + AEAD → OK. Başarısız kontrolde (bağlantı hatası) TLS yok.
 */
const TLS = {
  [CERT_DOMAINS.healthy]: { tls_version: 'TLSv1.3', cipher_suite: 'TLS_AES_256_GCM_SHA384', tls_assessment: { protocol: 'OK', protocol_latest: true, cipher: 'OK' } },
  [CERT_DOMAINS.problem]: { tls_version: 'TLSv1', cipher_suite: 'TLS_RSA_WITH_3DES_EDE_CBC_SHA', tls_assessment: { protocol: 'FAIL', protocol_latest: false, cipher: 'FAIL' } },
  [CERT_DOMAINS.quiet]: { tls_version: 'TLSv1.3', cipher_suite: 'TLS_AES_256_GCM_SHA384', tls_assessment: { protocol: 'OK', protocol_latest: true, cipher: 'OK' } },
}

/**
 * `/api/history/{d}` satırı — GERÇEK geçmiş biçimi (CertificateService.toDtoFromCheck → CertificateDto.from + applyTls).
 * Liste satırından farkı (Ek 3/9, 2026-09-28 — mock eskiden LİSTE satırını dönüyordu, zayıf protokol rozeti ve durumun
 * `statusKey` düşüş yolu e2e'de hiç sınanmıyordu):
 * - envanter birleşimi YOK: `alert_level`, `tier`, `port`, `team_id`, `team_name`, `group_name`, `tags`, platform alanları
 *   `null` (sınıf düzeyinde NON_NULL yok → null yazılır); `noc_notify` / `noc_group_ids` / `can_manage` NON_NULL → HİÇ yazılmaz;
 * - `via` / `tls_mode_used` kontrolden kopyalanmaz (null);
 * - TLS üçlüsü (`tls_version`, `cipher_suite`, `tls_assessment`) YALNIZ burada (liste satırı taşımaz — NON_NULL).
 */
export function historyRow(row) {
  const out = { ...row }
  delete out.noc_notify
  delete out.noc_group_ids
  return {
    ...out,
    alert_level: null, tier: null, port: null, team_id: null, team_name: null, group_name: null, tags: null,
    platform: null, platformDetail: null, platformName: null, check_interval_hours: null, via: null, tls_mode_used: null,
    ...(row.status === 'error' ? {} : (TLS[row.domain] ?? {})),
  }
}

const CHAIN = (leafDomain) => [
  { position: 0, subject: `CN=${leafDomain},O=Example Ltd,L=London,C=GB`, issuer: 'CN=Example TLS RSA CA 2026,O=Example Trust Ltd,C=GB',
    not_after: inDays(180), days_remaining: 180, is_root: false, is_leaf: true, not_before: iso(185 * D),
    serial_number: '0A1B2C3D4E5F60718293A4B5C6D7E8F9', signature_algorithm: 'SHA256withRSA', expired: false },
  { position: 1, subject: 'CN=Example TLS RSA CA 2026,O=Example Trust Ltd,C=GB', issuer: 'CN=Example Root CA R1,O=Example Trust Ltd,C=GB',
    not_after: inDays(1600), days_remaining: 1600, is_root: false, is_leaf: false, not_before: iso(1000 * D),
    serial_number: '77AA0F3C19', signature_algorithm: 'SHA384withRSA', expired: false },
]

/** check-preview yanıtı — sağlıklı / sorunlu / bağlantı hatası. */
export function previewFor(domain) {
  const row = CERTS.find((c) => c.domain === domain) || certRow(domain, 0)
  if (domain === CERT_DOMAINS.down) {
    return {
      domain, status: 'error', warning: true, checked_at: iso(0), san: [], chain: [], chain_status: 'UNKNOWN', revocation_status: 'UNKNOWN',
      error: 'Connection timeout after 10s · 2 attempts, 21s total', error_class: 'NETWORK', error_stage: 'tcp-connect',
      resolved_ips: ['203.0.113.40', '203.0.113.41'], retry_attempted: true, attempts_total: 2, attempts_elapsed_ms: 21140,
      source_ip: null, source_port: null, peer_ip: null, peer_port: null, via: 'direct', tls_mode_used: 'default', elapsed_ms: 10012, port: 443,
    }
  }
  const problem = domain === CERT_DOMAINS.problem
  return {
    domain, subject: problem ? 'legacy-appliance.local' : domain, issuer: problem ? 'Legacy Appliance CA' : 'Example Trust Ltd',
    issuer_cn: problem ? 'Legacy Appliance CA' : 'Example TLS RSA CA 2026',
    not_before: row.not_before, not_after: row.not_after, days_remaining: row.days_remaining, warning: row.warning, status: row.status,
    san: problem ? ['legacy-appliance.local'] : row.san, checked_at: iso(0),
    serial_number: '0A1B2C3D4E5F60718293A4B5C6D7E8F9', signature_algorithm: problem ? 'SHA1withRSA' : 'SHA256withRSA',
    public_key_algorithm: 'RSA', public_key_size: problem ? 1024 : 2048,
    subject_dn: problem ? 'CN=legacy-appliance.local,O=Example Appliance' : `CN=${domain},O=Example Ltd,L=London,C=GB`,
    issuer_dn: problem ? 'CN=Legacy Appliance CA,O=Example Appliance' : 'CN=Example TLS RSA CA 2026,O=Example Trust Ltd,C=GB',
    key_usage: ['digitalSignature', 'keyEncipherment'], ext_key_usage: ['serverAuth', 'clientAuth'], is_ca: false,
    ocsp_url: problem ? null : 'http://ocsp.example.com', crl_url: problem ? null : 'http://crl.example.com/example-tls-rsa-ca-2026.crl',
    cert_type: problem ? 'Organization Validated (OV) — Single Domain' : 'Organization Validated (OV) — Multi-Domain (SAN)',
    source_ip: '10.20.30.40', source_port: 53122, peer_ip: '203.0.113.10', peer_port: 443,
    tls_version: problem ? 'TLSv1' : 'TLSv1.3',
    cipher_suite: problem ? 'TLS_RSA_WITH_3DES_EDE_CBC_SHA' : 'TLS_AES_256_GCM_SHA384', alpn: problem ? '' : 'h2',
    chain_status: 'VALID', intermediate_expiry: problem ? null : inDays(1600), intermediate_days_remaining: problem ? null : 1600,
    chain: problem
      ? [{ position: 0, subject: 'CN=legacy-appliance.local,O=Example Appliance', issuer: 'CN=Legacy Appliance CA,O=Example Appliance',
        not_after: row.not_after, days_remaining: 0, is_root: false, is_leaf: true, not_before: row.not_before, serial_number: '01',
        signature_algorithm: 'SHA1withRSA', expired: true }]
      : CHAIN(domain),
    fingerprint: 'AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12',
    revocation_status: problem ? 'UNKNOWN' : 'VALID', trust_status: problem ? 'UNTRUSTED' : 'TRUSTED',
    ...(problem ? { trust_error: 'PKIX path building failed: unable to find valid certification path to requested target' } : {}),
    deployment_status: 'UNKNOWN', resolved_ip: '203.0.113.10', hsts: problem ? false : true, http_status: problem ? 403 : 200,
    via: 'direct', tls_mode_used: 'default', elapsed_ms: problem ? 1840 : 412, port: 443,
    assessment: problem
      ? { hostname: 'FAIL', protocol: 'FAIL', protocol_latest: false, cipher: 'FAIL', pfs: 'FAIL', signature: 'FAIL', key_size: 'FAIL' }
      : { hostname: 'OK', protocol: 'OK', protocol_latest: true, cipher: 'OK', pfs: 'OK', signature: 'OK', key_size: 'OK' },
    security_flags: problem ? ['HOSTNAME_MISMATCH', 'UNTRUSTED_CA'] : [],
  }
}

function seedNotes(domain) {
  if (domain !== CERT_DOMAINS.healthy) return []
  const n = (id, over) => ({ id, domain, team_id: 1, author_username: 'demo', author_name: 'Kişi A', note: '', category: 'NOTE',
    created_at: iso(2 * H), updated_at: null, updated_by: null, deleted_at: null, deleted_by: null, ...over })
  return [
    n(4, { note: 'Yeni sertifika yük dengeleyiciye yüklendi; ara sertifika bağlandı ve zincir doğrulandı.', category: 'DEPLOYMENT', created_at: iso(2 * H) }),
    n(3, { author_username: 'kisi.b', author_name: 'Kişi B', note: 'Yenileme talebi CA portalında onaylandı (talep no. REQ-2026-0042). Teslim 2 iş günü.',
      category: 'RENEWAL', created_at: iso(26 * H), updated_at: iso(25 * H), updated_by: 'kisi.b' }),
    n(2, { author_username: 'kisi.c', author_name: 'Kişi C', note: 'Prod dağıtımı sonrası 5 dakika boyunca TLS el sıkışması hata verdi; önbellek temizlenince düzeldi.',
      category: 'INCIDENT', created_at: iso(3 * D) }),
    n(1, { note: 'Eski not — yanlış ortama yazılmıştı.', created_at: iso(9 * D), deleted_at: iso(8 * D), deleted_by: 'demo' }),
  ]
}

/**
 * @param opts.notesError   true → not listesi 500 döner (hata durumu ekran görüntüsü)
 */
export async function mockCertApi(page, opts = {}) {
  const { notesError = false } = opts
  const notes = new Map()
  const listFor = (d) => { if (!notes.has(d)) notes.set(d, seedNotes(d)); return notes.get(d) }
  let nextId = 100
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const req = route.request()
    const u = new URL(req.url())
    const p = u.pathname
    const method = req.method()
    let m
    let body
    let status = 200
    if (p === '/api/certificates') body = { success: true, data: CERTS, timestamp: iso(0) }
    else if (p === '/api/certificates/card-extras') body = { success: true, data: {} }
    else if (p === '/api/stats') body = { success: true, data: { total: CERTS.length, valid: 2, warning: 0, critical: 1, error: 1, expired: 1 } }
    else if ((m = p.match(/^\/api\/history\/([^/]+)\/alerts$/))) body = { success: true, data: [] }
    else if (p === '/api/noc/groups/options') body = { success: true, data: NOC_OPTIONS }
    else if ((m = p.match(/^\/api\/history\/([^/]+)$/))) {
      const d = decodeURIComponent(m[1])
      const row = CERTS.find((c) => c.domain === d) || certRow(d, 0)
      // Zarf kaydın GÜNCEL 7/24 alanlarını taşır (CertificateController.getHistory, 2026-09-28) — pencere başlığındaki gösterge
      body = { success: true, domain: d, data: [historyRow(row)], timestamp: iso(0), noc_notify: row.noc_notify, noc_group_ids: row.noc_group_ids }
    } else if ((m = p.match(/^\/api\/check-preview\/([^/]+)$/))) {
      body = { success: true, data: previewFor(decodeURIComponent(m[1])), timestamp: iso(0) }
    } else if ((m = p.match(/^\/api\/certificates\/([^/]+)\/health$/))) {
      const c = CERTS.find((x) => x.domain === decodeURIComponent(m[1])) || CERTS[0]
      body = { success: true, data: { domain: c.domain, port: 443, not_before: c.not_before, not_after: c.not_after, days_remaining: c.days_remaining,
        checked_at: c.checked_at, next_check_at: inDays(0.04), ok_count: 0, evaluated_count: 0, rows: [] } }
    } else if ((m = p.match(/^\/api\/admin\/notes\/([^/]+)\/(\d+)\/revisions$/))) {
      const d = decodeURIComponent(m[1])
      const id = Number(m[2])
      const n = listFor(d).find((x) => x.id === id)
      const revs = n ? [{ id: id * 10, note_id: id, sequence_no: 0, event_type: 'CREATE', body: n.note, category: n.category,
        edited_at: n.created_at, edited_by: n.author_username, edited_by_name: n.author_name, reason: null }] : []
      if (n?.updated_at) revs.push({ id: id * 10 + 1, note_id: id, sequence_no: 1, event_type: 'EDIT', body: 'Yenileme talebi CA portalında onaylandı.',
        category: n.category, edited_at: n.updated_at, edited_by: n.updated_by, edited_by_name: n.author_name, reason: null })
      body = { success: true, data: revs }
    } else if ((m = p.match(/^\/api\/admin\/notes\/([^/]+)$/))) {
      const d = decodeURIComponent(m[1])
      if (method === 'POST') {
        const b = JSON.parse(req.postData() || '{}')
        const n = { id: nextId++, domain: d, team_id: 1, author_username: 'demo', author_name: 'demo', note: b.note, category: b.category || 'NOTE',
          created_at: iso(0), updated_at: null, updated_by: null, deleted_at: null, deleted_by: null }
        listFor(d).unshift(n)
        body = { success: true, data: n, message: 'Note added' }
      } else if (notesError) {
        status = 500
        body = { success: false, error: 'Notlar okunamadı: veritabanı zaman aşımı' }
      } else {
        body = { success: true, data: listFor(d) }
      }
    } else {
      return route.fallback()
    }
    await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
  })
}
