// Gerçekçi izleme verisi — e2e taramaları için API mock'u (girişsiz, sunucusuz).
//
// Neden ayrı dosya: responsive.spec.js (kalıcı kapı) ve kart tasarım taramaları aynı veriyi kullanır. Veri
// dokuz izleme türünün kartında görünen HER durumu kapsar: çalışıyor, kapalı + onaylanmamış alarm, duraklatılmış,
// bilinmiyor, uzun alan adı, SLA hedefinin altı/üstü. Yalnız `/api/` yolları taklit edilir (bkz. mockApi).
// Gerçek kişi/kurum adı YOK (example.com, Takım A).

const NOW = Date.parse('2026-09-26T09:30:00Z')
const iso = (msAgo) => new Date(NOW - msAgo).toISOString().slice(0, 19)
const HOUR = 3_600_000

/** Ortak satır alanları — `i` karttaki durum senaryosunu seçer. */
function base(i, extra = {}) {
  const scenario = SCENARIOS[i % SCENARIOS.length]
  return {
    id: 100 + i,
    active: scenario.active,
    status: scenario.status,
    active_alarm: scenario.alarm,
    alarm_level: scenario.alarm ? 'CRITICAL' : null,
    alarm_acknowledged: false,
    team_id: 1,
    team_name: i % 2 ? 'Takım B' : 'Takım A',
    group_name: i % 3 === 0 ? 'Ödeme Sistemleri' : 'Kurumsal Web',
    tags: i % 2 ? 'prod,web' : 'prod',
    checked_at: iso((i + 1) * 60_000),
    interval_seconds: 300,
    ...extra,
  }
}

/** Kart senaryoları: sıra bilinçli — ilk ekranda her durum görünsün. */
const SCENARIOS = [
  { status: 'up', active: true, alarm: false },
  { status: 'down', active: true, alarm: true },
  { status: 'up', active: false, alarm: false },     // duraklatılmış
  { status: 'unknown', active: true, alarm: false },
  { status: 'up', active: true, alarm: false },
  { status: 'error', active: true, alarm: false },
]

const HOSTS = [
  'www.example.com',
  'api.example.com',
  'odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.com',
  'status.example.org',
  'cdn.example.net',
  'intranet.example.com',
]

const url = (i) => `https://${HOSTS[i % HOSTS.length]}${i === 4 ? '/giris/kullanici?lang=tr&utm=kampanya' : '/'}`

export const MONITORS = {
  http: HOSTS.map((_, i) => base(i, {
    url: url(i), name: HOSTS[i], method: i === 3 ? 'POST' : 'GET',
    http_status: [200, 503, 200, null, 301, null][i], response_ms: [182, null, 240, null, 95, null][i],
    proxy_effective: i === 0 ? 'proxy' : null, proxy_source: i === 0 ? 'inventory' : null,
    expected_status: '200-399',
  })),
  keyword: HOSTS.map((_, i) => base(i, {
    url: url(i), name: HOSTS[i], keyword: i === 2 ? 'Hoş geldiniz — oturum açma sayfası yüklendi ve form hazır' : 'Giriş',
    operator: 'GTE', match_count: 1, http_status: [200, 200, 200, null, 200, null][i],
    response_ms: [210, 1890, 305, null, 120, null][i], occurrences: [3, 0, 2, null, 1, null][i],
  })),
  page: HOSTS.map((_, i) => base(i, {
    url: url(i), name: HOSTS[i], mode: i % 2 ? 'SITE_CRAWL' : 'SINGLE',
    broken_resources: [0, 7, 0, null, 1, null][i], timeout_count: [0, 2, 0, null, 0, null][i],
    mixed_content_count: [0, 1, 0, null, 0, null][i], total_resources: [84, 132, 61, null, 45, null][i],
  })),
  pagespeed: HOSTS.map((_, i) => base(i, {
    url: url(i), name: HOSTS[i], response_ms: [1840, 7420, 2100, null, 980, null][i],
    ttfb_ms: [210, 1300, 330, null, 120, null][i], total_bytes: [2_450_000, 9_800_000, 1_200_000, null, 640_000, null][i],
    request_count: [64, 212, 40, null, 22, null][i], breached_metrics: i === 1 ? ['LOAD', 'TTFB', 'SIZE'] : [],
    last_check: iso((i + 1) * 60_000),
  })),
  domain: HOSTS.map((h, i) => base(i, {
    domain: h.replace(/^www\./, ''), status: ['OK', 'CRITICAL', 'OK', 'UNKNOWN', 'WARNING', 'OK'][i],
    source: i % 2 ? 'RDAP' : 'WHOIS', whois_provider: i === 0 ? 'isimtescil' : null,
    registrar: i === 2 ? 'Çok Uzun Adlı Alan Adı Tescil ve Barındırma Hizmetleri A.Ş.' : 'Örnek Tescil Ltd.',
    days_remaining: [212, -3, 64, null, 18, 400][i], expiry_date: '2027-04-26T00:00:00',
    registration_date: '2018-04-26T00:00:00', transfer_lock: ['BOTH', 'NONE', 'SERVER', null, 'CLIENT', 'BOTH'][i],
    dnssec: i === 0 ? 'signed' : 'unsigned', blacklist_status: i === 1 ? 'LISTED' : 'CLEAN', blacklist_detail: 'bl.example.org',
    nameservers: ['ns1.example.com', 'ns2.example.com'], status_codes: i === 1 ? ['clientHold', 'serverHold', 'redemptionPeriod', 'pendingDelete', 'inactive'] : ['clientTransferProhibited'],
  })),
  ping: HOSTS.map((h, i) => base(i, {
    host: h, ip_version: i === 3 ? 'V6' : 'V4', packet_count: 4,
    rtt_ms: [12, null, 38, null, 4, null][i], packet_loss: [0, 100, 0, null, 25, null][i],
  })),
  port: HOSTS.map((h, i) => base(i, {
    host: h, port: [443, 5432, 8443, 22, 25, 6379][i], protocol: 'TCP',
    status: ['open', 'closed', 'open', 'unknown', 'open', 'error'][i], response_ms: [14, null, 33, null, 8, null][i],
  })),
  dns: HOSTS.map((h, i) => base(i, {
    domain: h, record_type: ['A', 'AAAA', 'CNAME', 'MX', 'TXT', 'NS'][i],
    value: i === 4 ? 'v=spf1 include:_spf.example.com include:mail.example.net ~all' : '203.0.113.' + (10 + i),
    ttl: 300, response_ms: [11, 24, 9, null, 17, null][i], changed: i === 1, rotated: i === 5, standalone: i % 2 === 0,
  })),
  scripted: HOSTS.map((h, i) => base(i, {
    name: i === 2 ? 'Ödeme akışı — sepete ekle, adres, kart doğrulama ve onay adımları' : `Giriş akışı ${h}`,
    duration_ms: [2400, 18_000, 3100, null, 900, null][i],
    checks_passed: [12, 3, 12, null, 5, null][i], checks_total: [12, 12, 12, null, 5, null][i],
  })),
}

export const UPTIME = HOSTS.map((h, i) => ({
  domain: h, port: 443, status: ['up', 'down', 'up', 'unknown', 'up', 'up'][i],
  ssl_valid_days: [120, 3, 12, null, 25, 300][i], http_ok: [true, false, true, null, true, true][i],
  uptime_7d: [100, 92.4, 99.8, null, 99.95, 100][i], uptime_30d: [99.99, 97.1, 99.9, null, 99.9, 100][i],
  incidents_1d: [0, 3, 0, 0, 0, 0][i], incidents_7d: [0, 9, 1, 0, 0, 0][i], incidents_15d: [0, 12, 1, 0, 1, 0][i], incidents_30d: [0, 20, 2, 0, 1, 0][i],
  team_name: i % 2 ? 'Takım B' : 'Takım A', group_name: 'Kurumsal Web', tags: 'prod,web',
  uptime_checked_at: iso(120_000), ssl_checked_at: iso(3_600_000), response_ms: 120,
}))

/** 24 saatlik kart trendi: { n, fail, up_pct, buckets, last } — kimlik başına. */
function spark(i) {
  const fail = [0, 9, 0, 0, 1, 4][i % 6]
  const buckets = Array.from({ length: 24 }, (_, h) => ({
    t: iso((24 - h) * HOUR).slice(0, 13), n: 12, fail: h === 20 && fail ? fail : 0,
    ms: Math.round(150 + 60 * Math.sin(h / 3) + (i === 1 && h > 18 ? 900 : 0)),
  }))
  const last = Array.from({ length: 5 }, (_, k) => ({ at: iso((5 - k) * 300_000), ok: !(fail && k === 3), ms: 150 + k * 10 }))
  return { n: 288, fail, up_pct: Math.round(((288 - fail) / 288) * 1000) / 10, buckets, last }
}

/** 30 günlük SLA + dönem pencereleri (1/7/15/30 gün) — "ok / warn / bad / none" hepsi görünsün. */
function sla(i) {
  const k = i % 6
  const slots = (n) => Array.from({ length: n }, (_, j) => ({ h: iso((j * 7 + 3) * HOUR).slice(0, 13), fail: j === 0 ? 5 : 1 }))
  const win = (days, n, fail, bad) => ({
    days, n, fail, up_pct: n ? Math.round(((n - fail) / n) * 10000) / 100 : null, bad_hours: bad, slots: slots(Math.min(bad, 8)),
  })
  const byScenario = [
    [win(1, 288, 0, 0), win(7, 2016, 0, 0), win(15, 4320, 0, 0), win(30, 8640, 0, 0)],
    [win(1, 288, 40, 6), win(7, 2016, 90, 14), win(15, 4320, 120, 20), win(30, 8640, 260, 31)],
    [win(1, 0, 0, 0), win(7, 1400, 0, 0), win(15, 3700, 2, 1), win(30, 8000, 3, 2)],
    [win(1, 0, 0, 0), win(7, 0, 0, 0), win(15, 0, 0, 0), win(30, 0, 0, 0)],
    [win(1, 288, 1, 1), win(7, 2016, 2, 2), win(15, 4320, 4, 3), win(30, 8640, 6, 4)],
    [win(1, 288, 4, 2), win(7, 2016, 4, 2), win(15, 4320, 9, 5), win(30, 8640, 12, 7)],
  ][k]
  const w30 = byScenario[3]
  return { n: w30.n, fail: w30.fail, up_pct: w30.up_pct, bad_hours: w30.bad_hours, windows: byScenario }
}

const TYPES = Object.keys(MONITORS)

// ── Sertifika Yenileme Önerileri (`/api/renewal-advice`) — 24 öneri: her neden kodu, üç öncelik, paylaşılan parmak izi,
// takımsız kayıt, uzun alan adı, 443 dışı port (responsive kapısı kartları/tabloyu dolu ölçsün; 2026-09-26)
const ADVICE_DAY = 86_400_000
const advNotAfter = (days) => (days == null ? null : new Date(NOW + days * ADVICE_DAY).toISOString().slice(0, 19))
const ADVICE_PRI = { REVOKED: 'critical', DEPLOYMENT_INCOMPLETE: 'critical', CHAIN_BROKEN: 'critical', UNREACHABLE: 'critical', EXPIRED: 'critical', EXPIRING_CRITICAL: 'critical', EXPIRING_WARNING: 'warning', EXPIRING_INFO: 'info' }
function advice(domain, code, days, extra = {}) {
  return {
    domain, code, priority: ADVICE_PRI[code], message: `Sunucu mesajı (${code})`, action: `Sunucu eylemi (${code})`,
    days_remaining: code === 'UNREACHABLE' ? null : days, not_after: advNotAfter(days),
    team_id: 1, team_name: 'Takım A', tier: 2, group_name: 'Kurumsal Web', tags: 'prod', port: 443,
    issuer_cn: 'Example Issuing CA G2', fingerprint: `FP-${domain}`, ...extra,
  }
}
export const RENEWAL_ADVICE = [
  advice('www.example.com', 'EXPIRED', -3, { tier: 1, fingerprint: 'FP-SHARED-WWW', tags: 'prod,web' }),
  advice('shop.example.com', 'EXPIRING_CRITICAL', 2, { tier: 1, fingerprint: 'FP-SHARED-WWW', group_name: 'Ödeme Sistemleri' }),
  advice(HOSTS[2], 'CHAIN_BROKEN', 44, { team_id: 2, team_name: 'Takım B', group_name: 'Ödeme Sistemleri', tags: 'prod,kritik', port: 8443 }),
  advice('api.example.com', 'DEPLOYMENT_INCOMPLETE', 380, { tier: 1, team_id: 2, team_name: 'Takım B', issuer_cn: 'Example Public CA R3' }),
  advice('legacy.example.org', 'REVOKED', 120, { tier: 3, team_id: 3, team_name: 'Takım C', group_name: null, tags: '' }),
  advice('vpn.example.net', 'UNREACHABLE', null, { team_id: 3, team_name: 'Takım C', group_name: 'Altyapı', tags: 'edge' }),
  advice('mail.example.com', 'EXPIRING_CRITICAL', 6, { group_name: 'Altyapı' }),
  advice('status.example.org', 'EXPIRING_WARNING', 9, { tier: 3, team_id: 2, team_name: 'Takım B', issuer_cn: 'Example Public CA R3' }),
  advice('cdn.example.net', 'EXPIRING_WARNING', 12, { fingerprint: 'FP-SHARED-CDN' }),
  advice('static.example.net', 'EXPIRING_WARNING', 12, { fingerprint: 'FP-SHARED-CDN' }),
  advice('img.example.net', 'EXPIRING_WARNING', 12, { fingerprint: 'FP-SHARED-CDN' }),
  advice('intranet.example.com', 'EXPIRING_WARNING', 18, { tier: 4, team_id: 3, team_name: 'Takım C', group_name: 'İç Uygulamalar' }),
  advice('portal.example.com', 'EXPIRING_WARNING', 21, { team_id: null, team_name: null }),
  advice('raporlama-ve-analitik-platformu.ic-servisler.example.com', 'EXPIRING_WARNING', 25, { tier: 3, team_id: 2, team_name: 'Takım B', group_name: 'İç Uygulamalar', tags: 'analitik' }),
  advice('auth.example.com', 'EXPIRING_WARNING', 28, { tier: 1, issuer_cn: 'Example Public CA R3' }),
  advice('docs.example.org', 'EXPIRING_WARNING', 30, { tier: 4, team_id: 3, team_name: 'Takım C' }),
  advice('m.example.com', 'EXPIRING_INFO', 33),
  advice('help.example.com', 'EXPIRING_INFO', 38, { tier: 3, team_id: 2, team_name: 'Takım B' }),
  advice('partner.example.net', 'EXPIRING_INFO', 41, { team_id: 3, team_name: 'Takım C', group_name: 'Altyapı' }),
  advice('kampanya.example.com', 'EXPIRING_INFO', 47, { tier: 4, group_name: 'Kampanya', tags: 'kampanya' }),
  advice('files.example.org', 'EXPIRING_INFO', 52, { tier: 3, team_id: 2, team_name: 'Takım B' }),
  advice('sso.example.com', 'EXPIRING_INFO', 55, { tier: 1, issuer_cn: 'Example Public CA R3' }),
  advice('test-ortami.example.com', 'EXPIRING_INFO', 58, { tier: 4, team_id: null, team_name: null, tags: 'test' }),
  advice('queue.example.net', 'EXPIRING_INFO', 60, { tier: 3, group_name: 'Altyapı' }),
]

// ── Değişim Rehberi bağlantıları (`/api/guide-links`) — kategoriler, açıklamalı/açıklamasız, UNC yolu
export const GUIDE_LINKS = [
  { id: 1, category: 'NetScaler', title: 'SSL sertifika değişimi — adım adım', url: 'https://wiki.example.com/netscaler/ssl-cert-degisimi', description: 'certKey güncelleme, ara sertifika bağlama ve yapılandırmayı kaydetme.', sort_order: 1 },
  { id: 2, category: 'NetScaler', title: 'vServer bağlama kontrol listesi', url: 'https://wiki.example.com/netscaler/vserver-binding', description: '', sort_order: 2 },
  { id: 3, category: 'WAF', title: 'WAF sertifika yükleme prosedürü', url: 'https://wiki.example.com/waf/sertifika-yukleme', description: 'Yükleme sonrası politika yeniden yükleme adımları dâhil.', sort_order: 1 },
  { id: 4, category: 'CA portalı', title: 'Kurumsal CA sertifika talep portalı', url: 'https://ca.example.com/talep', description: 'CSR gönderimi ve onay akışı.', sort_order: 1 },
  { id: 5, category: 'Yardımcı Araçlar', title: 'Sertifika dosyaları paylaşım klasörü', url: '\\\\dosya-sunucusu.example.com\\sertifikalar\\2026', description: 'Yalnız yetkili ekip üyeleri erişebilir.', sort_order: 1 },
  { id: 6, category: 'Yardımcı Araçlar', title: 'OpenSSL komut özeti', url: 'https://wiki.example.com/araclar/openssl', description: '', sort_order: 2 },
]

// ── Detay penceresi sekmeleri (2026-09-27): Değişiklikler / Rehber & Notlar / Yanıt Süresi zarfları ──
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0.0.0 Safari/537.36'
const changeRow = (seq, event_type, msAgo, extra = {}) => ({
  seq, kind: 'HTTP', resource_id: 100, resource_name: 'www.example.com', event_type,
  team_id: 1, team_name: 'Takım A', actor: 'N10001', actor_name: 'Ayşe Örnek',
  ip_address: '10.20.30.40', user_agent: UA, changes: null, note: null, at: iso(msAgo), ...extra,
})
/** Sıra numarası = dizin (0 = oluşturma). Liste en yeni önce döner (`reverse`). */
export const MONITOR_CHANGES = [
  changeRow(0, 'CREATE', 40 * 24 * HOUR),
  changeRow(1, 'UPDATE', 9 * 24 * HOUR, { note: 'Alarm çok geç açılıyordu, kontrol sıklığı artırıldı',
    changes: JSON.stringify({ intervalSeconds: { from: 300, to: 60 }, timeoutMs: { from: 10000, to: 5000 }, verifySsl: { from: false, to: true } }) }),
  changeRow(2, 'UPDATE', 3 * HOUR, { actor: 'system', actor_name: null, ip_address: null, user_agent: null,
    changes: JSON.stringify({ teamId: { from: 2, to: 1 }, tags: { from: 'prod', to: 'prod,web' }, basicAuthPassEnc: { from: '***', to: '***' } }) }),
  changeRow(3, 'RESTORE', 25 * 60_000, { note: 'Yanlış eşik geri alındı',
    changes: JSON.stringify({ timeoutMs: { from: 5000, to: 10000 } }) }),
]
export const CHANGE_SNAPSHOT = JSON.stringify({
  name: 'www.example.com', url: 'https://www.example.com/', method: 'GET', expectedStatus: '200-399',
  intervalSeconds: 300, timeoutMs: 10000, verifySsl: false, followRedirects: true, teamId: 1, tags: 'prod', active: true,
})
/** İzleme Değişiklikleri konsolu (2026-09-28): `/changes/summary` zarfı — kartlar DOLU ölçülsün (boş kart taşmayı kanıtlamaz). */
export const CHANGE_SUMMARY = {
  total: 42,
  event_counts: { CREATE: 6, UPDATE: 31, DELETE: 3, RESTORE: 2, PAUSE: 4, RESUME: 3 },
  kind_counts: { HTTP: { CREATE: 2, UPDATE: 14, DELETE: 1 }, PING: { CREATE: 1, UPDATE: 9, RESTORE: 2 }, PORT: { CREATE: 3, UPDATE: 8, DELETE: 2 } },
  daily_since: iso(89 * 24 * HOUR).slice(0, 10),
  daily: Array.from({ length: 14 }, (_, i) => ({ day: iso((13 - i) * 24 * HOUR).slice(0, 10), count: (i * 7) % 5 })).filter((d) => d.count > 0),
  top_resources: [
    { kind: 'HTTP', resource_id: 100, resource_name: 'www.example.com', team_id: 1, team_name: 'Takım A', count: 17, deleted: false },
    { kind: 'PORT', resource_id: 7, resource_name: 'cok-uzun-bir-alt-alan-adi.hizmetler.example.com:8443', team_id: 1, team_name: 'Takım A', count: 6, deleted: true },
  ],
  actors: [
    { actor: 'N10001', actor_id: 1, actor_name: 'Ayşe Örnek', count: 29 },
    { actor: 'N10002', actor_id: 2, actor_name: 'Kişi B', count: 13 },
  ],
}
export const MONITOR_NOTES = {
  guide: { guide: '## Alarm gelince\n\n1. Önce **NetScaler** sağlık sayfasına bakın.\n2. Uygulama loglarında `502` arayın.\n3. Çözülmezse nöbetçiyi arayın.', updated_by: 'Ayşe Örnek', updated_at: iso(2 * 24 * HOUR) },
  notes: [
    { id: 1, problem: 'Sertifika zinciri eksikti (ara sertifika)', action_taken: 'Ara sertifika NetScaler\'a bağlandı', root_cause: 'Yenilemede zincir dosyası atlanmış', refs: 'NetScaler → Traffic Management → SSL', author_name: 'Ayşe Örnek', author_username: 'N10001', created_at: iso(5 * 24 * HOUR), updated_at: iso(4 * 24 * HOUR) },
    { id: 2, problem: 'Yavaşlık — TTFB 3 sn üzerine çıktı', action_taken: 'Uygulama havuzu yeniden başlatıldı', root_cause: '', refs: '', author_name: 'Mehmet Örnek', author_username: 'N10002', created_at: iso(30 * 60_000), updated_at: null },
  ],
}
export const RESPONSE_SERIES = {
  bucket: 'hour', unit: 'ms', from: iso(24 * HOUR), to: iso(0), total: 288, down_total: 3, capped: false,
  series: Array.from({ length: 24 }, (_, h) => {
    const base = 150 + Math.round(60 * Math.sin(h / 3))
    const spike = h === 20 ? 900 : 0
    return { ts: iso((24 - h) * HOUR), avg: base + spike, min: base - 40, max: base + 90 + spike, p95: base + 70 + spike,
      count: 12, down: h === 20 ? 3 : 0 }
  }),
}

// ── Domain Envanteri (`/api/admin/inventory`, 2026-09-27): her durum bir satırda — geçerli / 30 gün altı / kritik / dolmuş /
// bağlantı hatası / hiç kontrol edilmemiş, sorumlusuz, platformsuz, takımsız, pasif, silinmiş, başka takımın kaydı (can_manage
// false; yalnız scope=all), uzun alan adı, 443 dışı port. Gerçek kişi/kurum adı YOK (example.com, Takım A/B).
const INV_DAY = 86_400_000
const invAt = (days) => new Date(NOW + days * INV_DAY).toISOString().slice(0, 19)
const INV_FLAGS = { external_vendor: false, action_required: false, openshift: false, ssl_pinning: false, internal_cert: false, jks_keystore: false,
  server_update: false, netscaler: false, waf_enabled: false, in_use: true, ev_certificate: false, transferred_to_sy: false, use_proxy: false }
function invRow(i, extra = {}) {
  return {
    id: 500 + i, port: 443, active: true, team_id: 1, team_name: 'Takım A', ug_team_id: null, ug_team_name: null, tier: 2,
    group_name: 'Kurumsal Web', tags: 'prod,web', description: 'Kurumsal web uygulaması', owner: 'Ops', purchased_by: 'Satın Alma',
    cert_status: 'valid', cert_days_remaining: 120, cert_not_after: invAt(120), cert_checked_at: iso(2 * HOUR), cert_issuer: 'Example Issuing CA G2', cert_error: null,
    platform: 'IIS', platform_detail: null, notification_group_id: null, tls_mode: null, timeout_seconds: null, check_interval_hours: null,
    svc_mgmt_contact: 'Servis Yönetimi - servis@example.com', app_dev_contact: 'Uygulama Geliştirme - dev@example.com', iis_admin_contact: '', waf_admin_contact: '',
    ...INV_FLAGS, netscaler: true, waf_enabled: true, change_description: null, expected_fingerprint: null, expected_subject: null,
    domain_expiry: null, domain_registrar: null, created_at: iso(200 * 24 * HOUR), updated_at: iso(3 * 24 * HOUR), updated_by_name: 'Ayşe Örnek',
    deleted_at: null, can_manage: true, ...extra,
  }
}
export const INVENTORY = [
  invRow(0, { domain: 'www.example.com', tier: 1, change_description: '## Yenileme notu\n\nSertifika **NetScaler** üzerinde; ara sertifikayı bağlamayı unutmayın.', expected_subject: 'CN=www.example.com' }),
  invRow(1, { domain: 'api.example.com', tier: 1, group_name: 'Ödeme Sistemleri', tags: 'prod,api', platform: 'OpenShift', openshift: true, netscaler: false,
    cert_status: 'warning', cert_days_remaining: 25, cert_not_after: invAt(25), iis_admin_contact: 'IIS Ekibi - iis@example.com', waf_admin_contact: 'WAF Ekibi - waf@example.com', notification_group_id: 7 }),
  invRow(2, { domain: 'odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.com', port: 8443, group_name: 'Ödeme Sistemleri', tags: 'prod,kritik',
    platform: null, svc_mgmt_contact: '', app_dev_contact: '', cert_status: 'critical', cert_days_remaining: 3, cert_not_after: invAt(3), action_required: true }),
  invRow(3, { domain: 'legacy.example.net', tier: 4, platform: 'Legacy', tags: 'legacy', cert_status: 'warning', cert_days_remaining: -2, cert_not_after: invAt(-2), server_update: true }),
  invRow(4, { domain: 'vpn.example.net', tier: 3, platform: null, group_name: 'Altyapı', tags: 'edge', cert_status: 'error', cert_days_remaining: null, cert_not_after: null,
    cert_error: 'Connection timed out after 10000 ms', cert_issuer: null, external_vendor: true }),
  invRow(5, { domain: 'intranet.example.com', tier: 4, group_name: 'İç Uygulamalar', tags: 'internal', svc_mgmt_contact: '', app_dev_contact: '',
    cert_status: null, cert_days_remaining: null, cert_not_after: null, cert_checked_at: null, cert_issuer: null, internal_cert: true }),
  invRow(6, { domain: 'cdn.example.net', tier: null, tags: 'prod,cdn', platform: 'CDN', cert_days_remaining: 300, cert_not_after: invAt(300), ev_certificate: true }),
  invRow(7, { domain: 'status.example.org', tier: 3, team_id: 2, team_name: 'Takım B', can_manage: false, cert_days_remaining: 64, cert_not_after: invAt(64) }),
  invRow(8, { domain: 'portal.example.org', team_id: null, team_name: null, cert_status: 'high', cert_days_remaining: 12, cert_not_after: invAt(12), platform: 'IIS' }),
  invRow(9, { domain: 'shop.example.com', tier: 1, active: false, group_name: 'Ödeme Sistemleri', cert_days_remaining: 80, cert_not_after: invAt(80), ssl_pinning: true }),
  invRow(10, { domain: 'mail.example.com', tier: 3, deleted_at: iso(5 * 24 * HOUR), cert_days_remaining: 40, cert_not_after: invAt(40) }),
  invRow(11, { domain: 'raporlama.example.com', team_id: 2, team_name: 'Takım B', can_manage: false, cert_status: 'warning', cert_days_remaining: 28, cert_not_after: invAt(28), platform: 'OpenShift' }),
  invRow(12, { domain: 'auth.example.com', tier: 1, cert_days_remaining: 45, cert_not_after: invAt(45), domain_expiry: invAt(20).slice(0, 10), domain_registrar: 'Örnek Tescil Ltd.', jks_keystore: true }),
  invRow(13, { domain: 'docs.example.org', tier: 4, cert_days_remaining: 200, cert_not_after: invAt(200), use_proxy: true, check_interval_hours: 24, tls_mode: 'browser' }),
]
export const INVENTORY_HYGIENE = {
  total: 6, scanned: 13,
  groups: [
    { key: 'missing', title: 'Eksik bilgi', total: 4, findings: [
      { domain: 'portal.example.org', detail: 'team', codes: ['no_team'] }, { domain: 'cdn.example.net', detail: 'tier', codes: ['no_tier'] },
      { domain: 'odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.com', detail: 'contacts', codes: ['no_contacts'] },
      { domain: 'intranet.example.com', detail: 'contacts', codes: ['no_contacts', 'never_checked'] },
    ] },
    { key: 'cert', title: 'Sertifika', total: 2, findings: [
      { domain: 'vpn.example.net', detail: 'error', codes: ['error'] }, { domain: 'legacy.example.net', detail: 'expired', codes: ['expired'] },
    ] },
  ],
}

/**
 * Sayfaya API mock'u kurar. Yalnız `/api/` yolu (`**\/api/**` DEĞİL — kaynak modülü /src/api/client.js'i de yakalar).
 * @param {import('@playwright/test').Page} page
 * @param {{ role?: string, globalAdmin?: boolean, teamIds?: number[], monitors?: object }} [opts]
 */
/**
 * Sorun bildirimleri (2026-09-26): "Bildirimlerim" (kullanıcı görünümü) + yönetici listesi/ayrıntısı — konuşma dizisi,
 * zaman çizelgesi, iç not. Yalnız login-issues sekmesinin uçları; diğer sekmelere dokunmaz. Gerçek kişi adı YOK.
 */
const ISSUE_MINE = [
  { id: 41, refCode: 'LIR-2026-000041', status: 'IN_PROGRESS', source: 'USER_REPORT', category: 'BLOCKER', reportedAt: iso(3 * 24 * HOUR),
    resolvedAt: null, messageSummary: 'Rapor dışa aktarımı PDF\'te Türkçe karakterleri (ğüşıöç) bozuk gösteriyor; ek ekran görüntülerine bakabilirsiniz.',
    imageCount: 1, lastActivityAt: iso(2 * HOUR), commentCount: 2, unread: true },
  { id: 38, refCode: 'LIR-2026-000038', status: 'OPEN', source: 'CLIENT_ERROR', category: null, reportedAt: iso(26 * HOUR), resolvedAt: null,
    messageSummary: 'TypeError: Cannot read properties of undefined (reading \'map\') — Sertifika Envanteri', imageCount: 0,
    lastActivityAt: iso(26 * HOUR), commentCount: 0, unread: false },
  { id: 27, refCode: 'LIR-2026-000027', status: 'RESOLVED', source: 'USER_REPORT', category: 'ANNOYANCE', reportedAt: iso(9 * 24 * HOUR),
    resolvedAt: iso(6 * 24 * HOUR), messageSummary: 'Haftalık rapor ekranında süzgeçler sayfa yenilenince sıfırlanıyor.', imageCount: 0,
    lastActivityAt: iso(6 * 24 * HOUR), commentCount: 1, unread: false },
]
const ISSUE_TIMELINE = {
  41: [{ status: 'OPEN', at: iso(3 * 24 * HOUR), by: 'demo', byReporter: true }, { status: 'IN_PROGRESS', at: iso(20 * HOUR), by: 'someadmin', byReporter: false }],
  38: [{ status: 'OPEN', at: iso(26 * HOUR), by: 'demo', byReporter: true }],
  27: [{ status: 'OPEN', at: iso(9 * 24 * HOUR), by: 'demo', byReporter: true }, { status: 'RESOLVED', at: iso(6 * 24 * HOUR), by: 'someadmin', byReporter: false }],
}
const ISSUE_COMMENTS = {
  41: [
    { id: 1, author: 'someadmin', byReporter: false, body: 'Merhaba, kaydınızı inceledik. PDF üretiminde yazı tipi gömme kapalıydı; düzeltmeyi hazırlıyoruz. Hangi tarayıcıyı kullanıyorsunuz?', createdAt: iso(20 * HOUR), internal: false, authorRole: 'ADMIN' },
    { id: 2, author: 'someadmin', byReporter: false, body: 'İç not: PDFBox sürümünü 3.0.2\'ye çekince düzeliyor — yayın 20.87.0.', createdAt: iso(19 * HOUR), internal: true, authorRole: 'ADMIN' },
    { id: 3, author: 'demo', byReporter: true, body: 'Edge 129 kullanıyorum; Chrome\'da da aynı. Teşekkürler.', createdAt: iso(2 * HOUR), internal: false, authorRole: 'USER' },
  ],
  38: [],
  27: [{ id: 4, author: 'someadmin', byReporter: false, body: 'Süzgeçler artık adres çubuğunda taşınıyor; sayfa yenilense de kalır.', createdAt: iso(6 * 24 * HOUR), internal: false, authorRole: 'ADMIN' }],
}
/** Sahte "ekran görüntüsü" (SVG data-URL) — galeri/ışık kutusu gerçek boyutta ölçülsün. */
function issueShot(n, hue) {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="800" viewBox="0 0 1280 800">`
    + `<rect width="1280" height="800" fill="hsl(${hue} 30% 96%)"/><rect width="1280" height="64" fill="hsl(${hue} 45% 40%)"/>`
    + `<rect x="32" y="104" width="360" height="24" rx="6" fill="hsl(${hue} 20% 70%)"/>`
    + [0, 1, 2, 3, 4].map((i) => `<rect x="32" y="${168 + i * 96}" width="1216" height="72" rx="10" fill="#fff" stroke="hsl(${hue} 20% 82%)"/>`).join('')
    + `<text x="640" y="760" font-family="Arial" font-size="36" text-anchor="middle" fill="hsl(${hue} 30% 45%)">Ekran görüntüsü ${n}</text></svg>`
  return 'data:image/svg+xml;base64,' + Buffer.from(svg).toString('base64')
}
const ISSUE_IMAGES = { 41: [issueShot(1, 215), issueShot(2, 150), issueShot(3, 20)] }
function issueDetail(id, admin) {
  const row = ISSUE_MINE.find((r) => r.id === id) || ISSUE_MINE[0]
  const comments = (ISSUE_COMMENTS[row.id] || []).filter((c) => admin || !c.internal).map((c) => (admin ? c : { id: c.id, author: c.author, byReporter: c.byReporter, body: c.body, createdAt: c.createdAt }))
  const images = ISSUE_IMAGES[row.id] || []
  return {
    id: row.id, refCode: row.refCode, status: row.status, source: row.source, category: row.category, reportedAt: row.reportedAt,
    username: 'demo', reporterEmail: 'demo@example.com', message: row.messageSummary, errorText: row.source === 'CLIENT_ERROR' ? row.messageSummary + '\n    at CertificateTable (index-8f3a9c2d.js:1:48213)' : 'Error: export failed (500)',
    appVersion: '20.86.0', tabKey: 'weeklyreports', screenSize: '1440x900', images, imageCount: images.length,
    resolvedAt: row.resolvedAt, resolvedBy: row.status === 'RESOLVED' ? 'someadmin' : null,
    resolutionNote: row.status === 'RESOLVED' ? 'Süzgeç durumu URL\'e taşındı (20.86.0).' : null,
    lastActivityAt: row.lastActivityAt, timeline: ISSUE_TIMELINE[row.id] || [], comments,
    ...(admin ? { ipAddress: '10.20.30.40', userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Edg/129.0.0.0', mailHistory: [
      { mailType: 'REPORTER_ACK', from: 'noreply@example.com', to: 'demo@example.com', cc: null, subject: '[Site Monitor] Sorun bildiriminiz alındı — ' + row.refCode, body: '<p>onay</p>', status: 'SENT', error: null, forced: false, sentAt: row.reportedAt },
      { mailType: 'ADMIN_REPLY', from: 'noreply@example.com', to: 'demo@example.com', cc: null, subject: '[Site Monitor] Bildiriminize yanıt verildi — ' + row.refCode, body: '<p>yanıt</p>', status: 'SENT', error: null, forced: false, sentAt: iso(20 * HOUR) },
    ] } : {}),
  }
}
// Yönetici listesi: sunucunun toListItem alanları (commentCount/unread YÖNETİCİ satırında YOK) + başka kullanıcıların kayıtları.
const ISSUE_ADMIN = [
  ...ISSUE_MINE.map((m) => {
    const r = { ...m }
    delete r.commentCount; delete r.unread
    return { ...r, username: 'demo', ipAddress: '10.20.30.40', signature: r.messageSummary.toLowerCase().slice(0, 40), linkedReference: null }
  }),
  { id: 44, refCode: 'LIR-2026-000044', status: 'OPEN', source: 'LOGIN', category: null, reportedAt: iso(40 * 60_000), resolvedAt: null,
    username: 'kullanici.b', ipAddress: '10.20.30.41', messageSummary: 'Şifremi doğru girdiğim hâlde giriş yapamıyorum; hesabım kilitlendi uyarısı çıkıyor.',
    imageCount: 0, lastActivityAt: iso(40 * 60_000), signature: 'http <n> locked', linkedReference: null },
  { id: 43, refCode: 'LIR-2026-000043', status: 'OPEN', source: 'CLIENT_ERROR', category: null, reportedAt: iso(5 * HOUR), resolvedAt: null,
    username: 'kullanici.c', ipAddress: '10.20.30.42', messageSummary: 'TypeError: Cannot read properties of undefined (reading \'map\') — Sertifika Envanteri',
    imageCount: 0, lastActivityAt: iso(5 * HOUR), signature: 'typeerror: cannot read properties', linkedReference: null },
]
const ISSUE_COUNTS = { OPEN: 1, IN_PROGRESS: 1, RESOLVED: 1 }
const ISSUE_ADMIN_COUNTS = { OPEN: 3, IN_PROGRESS: 1, RESOLVED: 1 }

// ── SQL Playground (`/api/admin/sql/*`, 2026-09-27): 26 tablo — gerçek FK (certificate_notes), *_id çıkarımları,
// kendine başvuru (app_users.manager_id), döngü (teams ↔ app_users), ilişkisiz tablolar; sorgu sonucu 120 satır
// (NULL, uzun metin, sayı, tarih, JSON), hata ("fail" içeren sorgu), boş ("empty"), 1000 satır tavanı ("big").
// Gerçek kişi/kurum adı YOK (example.com, Takım A).
const SQL_EDGES = [
  ['teams', 'manager_id', 'app_users'], ['app_users', 'team_id', 'teams'], ['app_users', 'manager_id', 'app_users'],
  ['app_user_team_sources', 'user_id', 'app_users'], ['app_user_team_sources', 'team_id', 'teams'],
  ['notification_groups', 'team_id', 'teams'], ['monitoring_groups', 'team_id', 'teams'],
  ['http_monitors', 'team_id', 'teams'], ['http_monitors', 'notification_group_id', 'notification_groups'],
  ['http_checks', 'monitor_id', 'http_monitors'], ['ping_monitors', 'team_id', 'teams'], ['ping_checks', 'monitor_id', 'ping_monitors'],
  ['dns_monitors', 'team_id', 'teams'], ['dns_records', 'monitor_id', 'dns_monitors'], ['incident_records', 'team_id', 'teams'],
  ['incident_images', 'incident_id', 'incident_records'], ['alerts', 'team_id', 'teams'], ['alerts', 'incident_id', 'incident_records'],
  ['alert_comments', 'alert_id', 'alerts'], ['alert_comments', 'user_id', 'app_users'], ['certificate_inventory', 'team_id', 'teams'],
  ['certificate_inventory', 'ug_team_id', 'teams'], ['certificate_notes', 'certificate_inventory_id', 'certificate_inventory', false],
  ['audit_log', 'user_id', 'app_users'], ['weekly_reports', 'team_id', 'teams'], ['weekly_report_comments', 'weekly_report_id', 'weekly_reports'],
].map(([from, column, to, inferred = true]) => ({ from, column, to, inferred }))
const SQL_ISOLATED = ['smtp_settings', 'ldap_settings', 'system_heartbeat', 'retention_run', 'idx_platform_code']
const SQL_TABLES = [...new Set([...SQL_EDGES.flatMap((e) => [e.from, e.to]), ...SQL_ISOLATED])].sort()
const SQL_EXTRA_COLS = {
  teams: [['name', 'character varying', 'NO'], ['description', 'text', 'YES']],
  app_users: [['username', 'character varying', 'NO'], ['display_name', 'character varying', 'YES'], ['email', 'character varying', 'YES'], ['system_role', 'character varying', 'NO'], ['last_login_at', 'timestamp without time zone', 'YES']],
  http_monitors: [['name', 'character varying', 'NO'], ['url', 'text', 'NO'], ['method', 'character varying', 'NO'], ['interval_seconds', 'integer', 'NO'], ['timeout_ms', 'integer', 'NO'], ['expected_status', 'character varying', 'YES'], ['active', 'boolean', 'NO'], ['tags', 'text', 'YES'], ['last_status', 'character varying', 'YES'], ['last_response_ms', 'integer', 'YES'], ['verify_ssl', 'boolean', 'NO']],
  http_checks: [['checked_at', 'timestamp without time zone', 'NO'], ['http_status', 'integer', 'YES'], ['response_ms', 'integer', 'YES'], ['error', 'text', 'YES']],
  certificate_inventory: [['domain', 'character varying', 'NO'], ['port', 'integer', 'NO'], ['tier', 'integer', 'YES'], ['cert_not_after', 'timestamp without time zone', 'YES']],
}
const sqlCols = (t) => [
  { column_name: 'id', data_type: 'bigint', is_nullable: 'NO' },
  ...SQL_EDGES.filter((e) => e.from === t).map((e) => ({ column_name: e.column, data_type: 'bigint', is_nullable: 'YES' })),
  ...(SQL_EXTRA_COLS[t] || [['created_at', 'timestamp without time zone', 'NO'], ['updated_at', 'timestamp without time zone', 'YES']])
    .map(([column_name, data_type, is_nullable]) => ({ column_name, data_type, is_nullable })),
]
const SQL_TABLE_LIST = SQL_TABLES.map((t, i) => ({
  table_name: t, live_rows: [0, 12, 480, 3_250, 12_400, 1_284_000][i % 6], first_seen_at: iso((200 + i) * 24 * HOUR),
  first_seen_approx: i % 4 === 0, last_change_at: i % 5 === 3 ? null : iso((i + 1) * HOUR), tup_ins: 1000 + i, tup_upd: i * 3, tup_del: i,
  last_maintenance_at: iso(6 * HOUR),
}))
const SQL_ROWS = Array.from({ length: 120 }, (_, i) => ({
  id: i + 1, name: HOSTS[i % HOSTS.length], url: url(i), status: ['up', 'down', 'up', 'unknown'][i % 4],
  response_ms: i % 7 === 3 ? null : 90 + ((i * 37) % 900), active: i % 5 !== 2, team_id: i % 3 === 0 ? null : 1 + (i % 2),
  checked_at: iso(i * 300_000), note: i % 4 === 1 ? null : i % 9 === 0
    ? 'Uzun açıklama: yük dengeleyici arkasındaki iki düğümden biri bakımdayken yanıtlar gecikiyor; ayrıntılar olay kaydında (INC-0042) ve takım rehberinde.'
    : `kısa not ${i + 1}`,
  meta: i % 6 === 0 ? { region: 'eu-west', retries: i % 3 } : null,
}))
const SQL_SAMPLES = [
  { label: 'Açık alarmlar', sql: 'SELECT id, team_id, alert_type, created_at\nFROM alerts\nWHERE resolved = false\nORDER BY created_at DESC\nLIMIT 50;' },
  { label: 'En yavaş HTTP izlemeleri', sql: 'SELECT name, url, last_response_ms\nFROM http_monitors\nWHERE active\nORDER BY last_response_ms DESC NULLS LAST\nLIMIT 20;' },
  { label: 'Takım başına izleme sayısı', sql: 'SELECT t.name, count(m.id) AS monitors\nFROM teams t\nLEFT JOIN http_monitors m ON m.team_id = t.id\nGROUP BY t.name\nORDER BY monitors DESC;' },
  { label: 'Son 24 saatte başarısız kontroller', sql: "SELECT monitor_id, count(*) AS failures\nFROM http_checks\nWHERE checked_at > now() - interval '24 hours' AND http_status >= 500\nGROUP BY monitor_id;" },
]
const SQL_HISTORY = [
  { id: 9, sql_text: 'SELECT * FROM http_monitors WHERE active LIMIT 100', executed_by: 'demo', row_count: 120, duration_ms: 34, success: true, executed_at: iso(20 * 60_000) },
  { id: 8, sql_text: 'SELECT * FROM missing_table', executed_by: 'demo', row_count: null, duration_ms: 3, success: false, error_message: 'relation "missing_table" does not exist', executed_at: iso(2 * HOUR) },
  { id: 7, sql_text: 'SELECT t.name, count(m.id) AS monitors FROM teams t LEFT JOIN http_monitors m ON m.team_id = t.id GROUP BY t.name', executed_by: 'demo', row_count: 4, duration_ms: 12, success: true, executed_at: iso(26 * HOUR) },
]
function sqlDetails(t) {
  const cols = sqlCols(t)
  const out = SQL_EDGES.filter((e) => e.from === t)
  const inn = SQL_EDGES.filter((e) => e.to === t)
  const row = SQL_TABLE_LIST.find((r) => r.table_name === t) || {}
  return {
    table: t, comment: t === 'teams' ? 'Takımlar (Takım A, Takım B …)' : null,
    columns: cols.map((c, i) => ({ ...c, udt_name: c.data_type === 'bigint' ? 'int8' : c.data_type === 'integer' ? 'int4' : c.data_type === 'boolean' ? 'bool' : c.data_type.startsWith('timestamp') ? 'timestamp' : c.data_type === 'text' ? 'text' : 'varchar',
      column_default: c.column_name === 'id' ? `nextval('${t}_id_seq'::regclass)` : null, bounds: c.data_type === 'bigint' ? '≈ ±9,22×10¹⁸ (8 bayt)' : '',
      is_pk: c.column_name === 'id', is_fk: out.some((e) => e.column === c.column_name && !e.inferred), is_unique: false, is_indexed: c.column_name === 'id' || /_id$/.test(c.column_name),
      null_frac: i === 0 ? 0 : 0.1 * (i % 4), n_distinct: i === 0 ? -1 : 12 + i, avg_width: 8 })),
    constraints: [
      { name: `${t}_pkey`, type: 'PRIMARY KEY', definition: 'PRIMARY KEY (id)', columns: ['id'] },
      ...out.filter((e) => !e.inferred).map((e) => ({ name: `${t}_${e.column}_fk`, type: 'FOREIGN KEY', definition: `FOREIGN KEY (${e.column}) REFERENCES ${e.to}(id) ON DELETE CASCADE`, columns: [e.column], ref_table: e.to, ref_columns: ['id'], on_delete: 'CASCADE', on_update: 'NO ACTION' })),
    ],
    indexes: [{ name: `${t}_pkey`, definition: `CREATE UNIQUE INDEX ${t}_pkey ON public.${t} USING btree (id)`, is_unique: true, is_primary: true, columns: ['id'], scans: 1520, size: '64 kB' }],
    triggers: [],
    referenced_by: inn.filter((e) => !e.inferred).map((e) => ({ name: `${e.from}_${e.column}_fk`, from_table: e.from, from_columns: [e.column], on_delete: 'CASCADE' })),
    inferred_relations: [...out, ...inn].filter((e) => e.inferred),
    activity: { live_rows: row.live_rows, size_total: '1.2 MB', size_data: '840 kB', last_change_at: row.last_change_at, first_seen_at: row.first_seen_at, tup_ins: row.tup_ins, tup_upd: row.tup_upd, tup_del: row.tup_del, last_maintenance_at: row.last_maintenance_at },
    stats: { seq_scan: 12, idx_scan: 340, dead_rows: 3 },
  }
}
function sqlMock(p, request) {
  if (p === '/api/admin/sql/tables') return { success: true, data: SQL_TABLE_LIST }
  if (p === '/api/admin/sql/relationships') return { success: true, data: { tables: SQL_TABLES.filter((t) => t !== 'idx_platform_code'), edges: SQL_EDGES } }
  if (p === '/api/admin/sql/samples') return { success: true, data: SQL_SAMPLES }
  if (p === '/api/admin/sql/history') return { success: true, data: SQL_HISTORY }
  let m = p.match(/^\/api\/admin\/sql\/tables\/([^/]+)\/columns$/)
  if (m) return { success: true, data: sqlCols(decodeURIComponent(m[1])) }
  m = p.match(/^\/api\/admin\/sql\/tables\/([^/]+)\/details$/)
  if (m) return { success: true, data: sqlDetails(decodeURIComponent(m[1])) }
  if (p === '/api/admin/sql/execute') {
    let sql = ''
    try { sql = String(request.postDataJSON()?.sql || '') } catch { /* gövde yok */ }
    const inner = sql.trim().replace(/;$/, '')
    const executedSql = `SELECT * FROM (${inner}) AS _capped LIMIT 1000`
    if (!/^\s*(select|with)\b/i.test(inner)) return { success: false, error: 'Sadece SELECT veya WITH ile başlayan sorgular çalıştırılabilir' }
    if (/fail/i.test(sql)) {
      return { success: true, ok: false, rows: [], rowCount: 0, durationMs: 5, executedSql,
        error: 'StatementCallback; bad SQL grammar [' + executedSql + ']; ERROR: column "fail" does not exist\n  Hint: Perhaps you meant to reference the column "m.name".\n  Position: ' + (executedSql.toLowerCase().indexOf('fail') + 1) }
    }
    if (/empty/i.test(sql)) return { success: true, ok: true, rows: [], rowCount: 0, durationMs: 2, executedSql }
    const rows = /big/i.test(sql) ? Array.from({ length: 1000 }, (_, i) => ({ ...SQL_ROWS[i % SQL_ROWS.length], id: i + 1 })) : SQL_ROWS
    return { success: true, ok: true, rows, rowCount: rows.length, durationMs: 34, executedSql }
  }
  return { success: true, data: [] }
}

// ── Yetki Yönetimi (`GET /api/admin/permissions`, 2026-09-27): düzenleyici görünümü (can_edit), eylem başına AYRI katalog
// satırı (notification.groups — arayüz tek satırda birleştirir), hassas eylem, uzun açıklamalı kaynak. Responsive kapısı
// sayfayı boş katalog yerine dolu matris / kartlarla ölçsün. Gerçek kişi adı YOK.
const PERM_CATALOG = [
  { resource_key: 'inventory.list', group: 'certificates', actions: ['view'], sensitive: [] },
  { resource_key: 'inventory.crud', group: 'certificates', actions: ['edit'], sensitive: [] },
  { resource_key: 'inventory.purge', group: 'certificates', actions: ['execute'], sensitive: ['execute'] },
  { resource_key: 'notification.groups', group: 'communication', actions: ['view'], sensitive: [] },
  { resource_key: 'notification.groups', group: 'communication', actions: ['edit'], sensitive: [] },
  { resource_key: 'monitoring.scripted', group: 'monitoring', actions: ['edit', 'execute'], sensitive: ['edit', 'execute'] },
  { resource_key: 'settings.smtp', group: 'settings', actions: ['edit'], sensitive: ['edit'] },
]
export const PERMISSION_MATRIX = {
  success: true, can_edit: true, catalog: PERM_CATALOG,
  grants: PERM_CATALOG.flatMap((r) => r.actions.flatMap((action) => ['ADMIN', 'TEAM_ADMIN', 'USER', 'AUDIT'].map((role) => ({
    role, resource_key: r.resource_key, action, updated_by: 'demo', updated_at: '2026-09-25T14:12:00',
    allowed: role === 'ADMIN' || (role !== 'AUDIT' && action === 'view') || (role === 'TEAM_ADMIN' && !r.sensitive.includes(action)),
  })))),
}

/**
 * @param opts.perms  `/api/me/permissions` yanıtı ({ 'issues.login-reports': { view: true, edit: true } } gibi); varsayılan boş —
 *                    yönetici sekmeleri izin matrisi olmadan çizilir (mevcut davranış korunur).
 */
export async function mockApi(page, opts = {}) {
  const { role = 'ADMIN', globalAdmin = role === 'ADMIN', teamIds = [1], monitors = MONITORS, perms = null } = opts
  await page.addInitScript(() => {
    try { localStorage.setItem('sm.tour', JSON.stringify({ status: 'dismissed', version: 99 })) } catch { /* yoksay */ }
  })
  await page.route((u) => new URL(u).pathname.startsWith('/api/'), async (route) => {
    const u = new URL(route.request().url())
    const p = u.pathname
    let body = { success: true, data: [] }
    if (p === '/api/me') {
      body = {
        success: true, username: 'demo', system_role: role, global_admin: globalAdmin, weekly_reports_visible: true,
        team_id: teamIds[0] ?? null, team_name: teamIds.length ? 'Takım A' : null,
        team_ids: teamIds, team_names: teamIds.map((id) => (id === 1 ? 'Takım A' : `Takım ${id}`)),
        tour: { status: 'dismissed', version: 99 },
      }
    } else if (p === '/api/monitoring/uptime/overview') {
      body = { success: true, data: UPTIME }
    } else if (p === '/api/renewal-advice') {
      body = { success: true, data: RENEWAL_ADVICE, count: RENEWAL_ADVICE.length, timestamp: iso(0) }
    } else if (p === '/api/guide-links') {
      body = { success: true, data: GUIDE_LINKS }
    } else if (p === '/api/me/permissions') {
      body = { success: true, data: perms || {} }
    } else if (p === '/api/admin/permissions' && route.request().method() === 'GET') {
      body = PERMISSION_MATRIX
    } else if (p === '/api/issue-reports/mine') {
      body = { success: true, data: ISSUE_MINE, total: ISSUE_MINE.length, page: 0, size: 20, counts: ISSUE_COUNTS }
    } else if (/^\/api\/issue-reports\/mine\/\d+$/.test(p)) {
      body = { success: true, data: issueDetail(Number(p.split('/').pop()), false) }
    } else if (p === '/api/admin/login-issues') {
      body = { success: true, data: ISSUE_ADMIN, total: ISSUE_ADMIN.length, page: 0, size: 20, counts: ISSUE_ADMIN_COUNTS }
    } else if (/^\/api\/admin\/login-issues\/\d+$/.test(p)) {
      body = { success: true, data: issueDetail(Number(p.split('/').pop()), true) }
    } else if (p.startsWith('/api/admin/sql/')) {
      // SQL Playground — `/history$` dalından ÖNCE (oradaki kontrol geçmişi zarfı sorgu geçmişini ezerdi)
      body = sqlMock(p, route.request())
    } else if (p === '/api/monitoring/sparklines') {
      const type = u.searchParams.get('type')
      const list = monitors[type] || []
      body = { success: true, data: Object.fromEntries(list.map((m, i) => [String(m.id), spark(i)])) }
    } else if (p === '/api/monitoring/sla') {
      const type = u.searchParams.get('type')
      const list = monitors[type] || []
      body = { success: true, target_pct: 99.9, days: Number(u.searchParams.get('days') || 30),
        data: Object.fromEntries(list.map((m, i) => [String(m.id), sla(i)])) }
    } else if (p === '/api/monitoring/changes/recent') {
      // İzleme Değişiklikleri konsolu — zaman çizelgesi dolu ölçülsün (2026-09-28)
      body = { success: true, data: { changes: [...MONITOR_CHANGES].reverse(), total: MONITOR_CHANGES.length, page: 0, size: 25 } }
    } else if (p === '/api/monitoring/changes/summary') {
      body = { success: true, data: CHANGE_SUMMARY }
    } else if (/^\/api\/monitoring\/changes\/[a-z]+\/\d+\/\d+$/.test(p)) {
      // Tek değişikliğin ayrıntısı (snapshot dâhil) — detay penceresi Değişiklikler sekmesi
      const seq = Number(p.split('/').pop())
      body = { success: true, data: { ...MONITOR_CHANGES[seq] ?? MONITOR_CHANGES[0], snapshot: CHANGE_SNAPSHOT } }
    } else if (/^\/api\/monitoring\/changes\/[a-z]+\/\d+$/.test(p)) {
      // İzlemenin yapılandırma geçmişi (ChangeHistoryTab zarfı) — oluşturma + iki düzenleme + geri alma
      body = { success: true, data: { changes: [...MONITOR_CHANGES].reverse(), total: MONITOR_CHANGES.length, page: 0, size: 25 } }
    } else if (p === '/api/monitoring/notes') {
      // Rehber & Notlar (MonitorNotes zarfı) — takım rehberi + iki not (biri düzenlenmiş)
      body = { success: true, data: MONITOR_NOTES }
    } else if (/\/response-series$/.test(p)) {
      // Yanıt süresi serisi (ResponseTimeChart zarfı) — 24 saatlik kova, bir kesinti, p95 tepesi
      body = { success: true, data: RESPONSE_SERIES }
    } else if (p === '/api/admin/inventory') {
      // Domain Envanteri (2026-09-27): scope=all başka takımın (salt okunur) kayıtlarını da döner; showDeleted silinmişleri
      const all = u.searchParams.get('scope') === 'all'
      const showDeleted = u.searchParams.get('showDeleted') === 'true'
      const rows = INVENTORY.filter((r) => (all || r.team_id !== 2) && (showDeleted || !r.deleted_at))
      body = { success: true, data: rows, scope: all ? 'all' : 'mine', visible_to_all: true }
    } else if (p === '/api/admin/inventory/hygiene') {
      body = { success: true, data: INVENTORY_HYGIENE }
    } else if (p === '/api/admin/inventory/by-domain') {
      body = { success: true, data: INVENTORY.find((r) => r.domain === u.searchParams.get('domain')) || null }
    } else if (/^\/api\/monitoring\/(?:[a-z]+\/\d+|uptime\/[^/]+\/(?:http|ssl))-?\/?history$/.test(p) || /\/history$/.test(p)) {
      // Kontrol geçmişi (CheckHistoryTab zarfı) — geçmiş ızgarası dar ekranda da ölçülsün diye dolu, uzun hata metinli
      const items = Array.from({ length: 12 }, (_, k) => ({
        id: k + 1, checked_at: iso((k + 1) * 300_000), ok: k % 4 !== 1, up: k % 4 !== 1, status: k % 4 === 1 ? 'down' : 'up',
        http_status: k % 4 === 1 ? 503 : 200, response_ms: 120 + k * 7, rtt_ms: 12 + k, ttl: 300, value: '203.0.113.10',
        error: k % 4 === 1 ? 'Connection timed out after 10000 ms while waiting for the upstream proxy to respond' : null,
        days_remaining: 90 - k, duration_ms: 2400 + k * 10, load_ms: 1800 + k * 20,
      }))
      body = { success: true, data: {
        items, counts: { total: 288, fail: 9 },
        buckets: Array.from({ length: 24 }, (_, h) => ({ key: iso((24 - h) * HOUR).slice(0, 13), total: 12, fail: h === 20 ? 3 : 0 })),
        alerts: [{ id: 7, alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL', message: 'HTTP 503', created_at: iso(600_000), resolved: false }],
        range: { from: iso(24 * HOUR), to: iso(0) }, total: 12, page: 0, size: 50,
      } }
    } else {
      const type = TYPES.find((ty) => p === `/api/monitoring/${ty}`)
      // Sentetik liste zarfı farklı: { monitors, k6_available, can_manage } (ScriptedMonitorPage.load).
      if (type === 'scripted') body = { success: true, data: { monitors: monitors.scripted, k6_available: true, k6_version: 'v0.49.0', can_manage: true } }
      else if (type) body = { success: true, data: monitors[type] }
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

/** Kart ızgarası olan sekmeler (Uptime dâhil) — kart tasarım taraması bunları gezer. */
export const CARD_TABS = ['http', 'keyword', 'page', 'pagespeed', 'domain', 'ping', 'port', 'dns', 'scripted', 'uptime']
