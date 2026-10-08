import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, within, fireEvent, waitFor } from './test-utils.jsx'
import CertDetailsPanel from '../components/certmodal/CertDetailsPanel.jsx'

/**
 * Sertifika penceresi → "Sertifika Detayları" sekmesi (certmodal/CertDetailsPanel, 2026-09-28 yeniden tasarım).
 * Fixture GERÇEK tel biçiminde: `/api/history/{domain}` `data[0]` = CertificateDto (snake_case; zaman UTC ve `Z`siz;
 * anahtar kullanımı sunucunun yazdığı adlarla — "Digital Signature", "TLS Web Server"). Tarihler SABİT DEĞİL: `now`
 * test anından alınır, fixture tarihleri ona göre kurulur. Sorgular rol / ad / data-slot ile (SHADCN.md §8.2).
 */
const DAY = 86_400_000
const NOW = Date.now()
const iso = (ms) => new Date(ms).toISOString().slice(0, 19)
const at = (days) => iso(NOW + days * DAY)
const FP = 'AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12CD34EF56AB12'

function rich(over = {}) {
  return {
    domain: 'www.example.com', subject: 'www.example.com', issuer: 'Example Trust Ltd', issuer_cn: 'Example TLS RSA CA 2026',
    not_before: at(-200), not_after: at(180), days_remaining: 180, warning: false, status: 'valid', error: null, alert_level: null,
    san: ['api.example.com', 'www.example.com', '*.example.com', 'cdn.example.com', 'static.example.com',
      'odeme-servisleri-yedek-bolge-2.cok-uzun-bir-alt-alan-adi.example.com'],
    checked_at: iso(NOW - 20 * 60_000), fingerprint: FP, chain_status: 'VALID', deployment_status: 'OK',
    intermediate_expiry: at(1600), intermediate_days_remaining: 1600, revocation_status: 'VALID', trust_status: 'TRUSTED',
    security_flags: [], secure: true, serial_number: '0A1B2C3D4E5F60718293A4B5C6D7E8F9', signature_algorithm: 'SHA256withRSA',
    public_key_algorithm: 'RSA', public_key_size: 2048, subject_dn: 'CN=www.example.com,O=Example Ltd,L=London,C=GB',
    issuer_dn: 'CN=Example TLS RSA CA 2026,O=Example Trust Ltd,C=GB',
    key_usage: ['Digital Signature', 'Key Encipherment'], ext_key_usage: ['TLS Web Server', 'TLS Web Client', '1.2.3.4.5.6'],
    is_ca: false, ocsp_url: 'http://ocsp.example.com', crl_url: 'http://crl.example.com/example-tls-rsa-ca-2026.crl',
    via: null, tls_mode_used: null, tier: null, port: null, team_id: null, team_name: null,
    ...over,
  }
}

const section = (name) => screen.getByRole('region', { name })
const querySection = (name) => screen.queryByRole('region', { name })
const field = (slot) => document.querySelector(`[data-slot="cert-detail-field"][data-field="${slot}"]`)

afterEach(() => { vi.restoreAllMocks() })

describe('CertDetailsPanel — özet ve zaman çizelgesi', () => {
  it('durum rozeti, büyük kalan gün, bitiş, geçerlilik çubuğu (erişilebilir ad + aria-valuenow) ve son kontrol', () => {
    render(<CertDetailsPanel d={rich()} now={NOW} />)
    const sum = section('Certificate summary')
    expect(sum).toHaveAttribute('data-tone', 'valid')
    expect(within(sum).getByText('Valid')).toBeInTheDocument()
    expect(within(sum).getByText('180')).toHaveAttribute('data-slot', 'cert-details-days')
    expect(within(sum).getByText('days remaining')).toBeInTheDocument()
    expect(within(sum).getByText('in 6 months')).toBeInTheDocument()
    const bar = within(sum).getByRole('progressbar', { name: 'Validity period used' })
    // 380 günlük sertifikanın 200 günü geçti → %53
    expect(bar).toHaveAttribute('aria-valuenow', '53')
    expect(within(sum).getByText('380-day validity')).toBeInTheDocument()
    expect(within(sum).getByText(/Last checked 20 min ago/)).toBeInTheDocument()
    expect(sum.querySelector('[data-slot="cert-details-today"]')).not.toBeNull()
  })

  it('pencerenin verdiği ton kazanır (başlık rozetiyle aynı)', () => {
    render(<CertDetailsPanel d={rich()} tone="critical" now={NOW} />)
    expect(section('Certificate summary')).toHaveAttribute('data-tone', 'critical')
    expect(within(section('Certificate summary')).getByText('CRITICAL')).toBeInTheDocument()
  })

  it('süresi dolmuş: "Expired" rozeti, geçen gün, çubuk %100, Geçerlilik bölümünde "Expired 6 days ago"', () => {
    render(<CertDetailsPanel d={rich({ not_before: at(-90), not_after: at(-6), days_remaining: -6, warning: true })} now={NOW} />)
    const sum = section('Certificate summary')
    expect(sum).toHaveAttribute('data-tone', 'expired')
    expect(within(sum).getByText('Expired')).toBeInTheDocument()
    expect(within(sum).getByText('6')).toHaveAttribute('data-slot', 'cert-details-days')
    expect(within(sum).getByText('days since expiry')).toBeInTheDocument()
    expect(within(sum).getByText('Expired on')).toBeInTheDocument()
    expect(within(sum).getByRole('progressbar')).toHaveAttribute('aria-valuenow', '100')
    expect(within(field('days')).getByText('Expired 6 days ago')).toBeInTheDocument()
  })

  it('yükleniyor: d yokken iskelet + duyurulan "Loading..."', () => {
    render(<CertDetailsPanel d={null} />)
    expect(screen.getByRole('status')).toHaveTextContent(/Loading/)
    expect(document.querySelector('[data-slot="cert-details"]')).toBeNull()
  })
})

describe('CertDetailsPanel — hata ve az veri', () => {
  it('son kontrol hatası panelin EN ÜSTÜNDE (danger banner); özet "check failed", tarih yok açıklaması', () => {
    const d = { domain: 'down.example.com', status: 'error', error: 'Connection timeout after 10s', san: [], key_usage: [], ext_key_usage: [],
      security_flags: [], checked_at: iso(NOW - 5 * 60_000), days_remaining: null }
    render(<CertDetailsPanel d={d} now={NOW} />)
    const root = document.querySelector('[data-slot="cert-details"]')
    const first = root.firstElementChild
    expect(first).toHaveAttribute('data-slot', 'alert')
    expect(first).toHaveAttribute('data-tone', 'danger')
    expect(within(first).getByText('The last check failed')).toBeInTheDocument()
    expect(within(first).getByText('Connection timeout after 10s')).toBeInTheDocument()
    const sum = section('Certificate summary')
    expect(sum).toHaveAttribute('data-tone', 'error')
    expect(within(sum).getByText('—')).toBeInTheDocument()
    expect(within(sum).getByText('check failed')).toBeInTheDocument()
    expect(within(sum).getByText('Validity dates unavailable')).toBeInTheDocument()
    expect(within(sum).queryByRole('progressbar')).toBeNull()
  })

  it('boş bölümler hiç çizilmez; tek tek eksik değer soluk "—"; "N/A" ve sunucunun "Unknown" yer tutucusu yok', () => {
    const d = { domain: 'bare.example.com', subject: 'Unknown', issuer: 'Unknown', status: 'valid', days_remaining: null, san: [],
      key_usage: [], ext_key_usage: [], security_flags: [], checked_at: iso(NOW - 3600_000) }
    render(<CertDetailsPanel d={d} now={NOW} />)
    expect(section('Identity')).toBeInTheDocument()
    expect(section('Validity')).toBeInTheDocument()
    for (const name of ['Key Information', 'Security', 'Infrastructure', 'Alternative Names (SAN)']) {
      expect(querySection(name), name).toBeNull()
    }
    expect(document.querySelector('[data-slot="cert-details-tiles"]')).toBeNull()
    expect(within(field('subject')).getByText('—')).toHaveAttribute('data-slot', 'cert-detail-empty')
    expect(within(field('issuer')).getByText('—')).toBeInTheDocument()
    expect(within(field('domain')).getByText('bare.example.com')).toBeInTheDocument()
    expect(screen.queryByText('N/A')).toBeNull()
    expect(screen.queryByText('Unknown')).toBeNull()
  })
})

describe('CertDetailsPanel — hızlı bakış ve bölümler', () => {
  it('karolar: otorite + güven, anahtar + imza + uç sertifika, SAN sayısı + joker + kapsama, güven ve zincir', () => {
    render(<CertDetailsPanel d={rich()} now={NOW} />)
    const tiles = screen.getByRole('list', { name: 'At a glance' })
    const tile = (k) => tiles.querySelector(`[data-tile="${k}"]`)
    expect(within(tile('ca')).getByText('Example TLS RSA CA 2026')).toBeInTheDocument()
    expect(within(tile('ca')).getByText('Example Trust Ltd')).toBeInTheDocument()
    expect(within(tile('ca')).getByText('Trusted')).toBeInTheDocument()
    expect(within(tile('key')).getByText('RSA 2048')).toBeInTheDocument()
    expect(within(tile('key')).getByText('Signed with SHA-256')).toBeInTheDocument()
    expect(within(tile('key')).getByText('End-entity certificate')).toBeInTheDocument()
    expect(within(tile('san')).getByText('6 names')).toBeInTheDocument()
    expect(within(tile('san')).getByText('1 wildcard')).toBeInTheDocument()
    expect(within(tile('san')).getByText('Covers this domain')).toBeInTheDocument()
    expect(tile('trust')).toHaveAttribute('data-tone', 'ok')
    expect(within(tile('trust')).getByText('No problems found')).toBeInTheDocument()
  })

  it('sunucu HOSTNAME_MISMATCH + zincir kırık → karolar sorunlu tonda', () => {
    render(<CertDetailsPanel d={rich({ security_flags: ['HOSTNAME_MISMATCH'], chain_status: 'BROKEN' })} now={NOW} />)
    const tiles = screen.getByRole('list', { name: 'At a glance' })
    expect(within(tiles.querySelector('[data-tile="san"]')).getByText('Doesn’t cover this domain')).toBeInTheDocument()
    expect(tiles.querySelector('[data-tile="trust"]')).toHaveAttribute('data-tone', 'bad')
    expect(within(section('Security')).getByText('Hostname doesn’t match the certificate')).toBeInTheDocument()
  })

  it('anahtar kullanımı insan-okunur rozetler; tanınmayan OID ham; durum kodları adla (ham kod data-code)', () => {
    render(<CertDetailsPanel d={rich({ chain_status: 'SOMETHING_NEW' })} now={NOW} />)
    const key = section('Key Information')
    expect(within(key).getByText('Digital signature')).toBeInTheDocument()
    expect(within(key).getByText('Key encipherment')).toBeInTheDocument()
    expect(within(key).getByText('TLS server authentication')).toBeInTheDocument()
    expect(within(key).getByText('1.2.3.4.5.6')).toHaveAttribute('data-usage', 'raw')
    expect(within(key).getByText('RSA · 2048 bit')).toBeInTheDocument()
    const sec = section('Security')
    expect(within(field('deployment')).getByText('Serving the expected certificate')).toHaveAttribute('data-code', 'OK')
    expect(within(field('revocation')).getByText('Not revoked')).toBeInTheDocument()
    // Tanınmayan kod bilgi kaybetmeden ham yazılır
    expect(within(sec).getByText('SOMETHING_NEW')).toHaveAttribute('data-code', 'SOMETHING_NEW')
    expect(within(section('Validity')).getByText('Intermediate expiry')).toBeInTheDocument()
  })

  it('parmak izi okunur gruplarla görünür, KOPYALANAN değer ham; DN kopyası ham DN', async () => {
    const spy = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue()
    render(<CertDetailsPanel d={rich()} now={NOW} />)
    const fp = document.querySelector('[data-slot="cert-fingerprint"]')
    expect(fp.children).toHaveLength(8)
    expect(fp.children[0]).toHaveTextContent('AB:12:CD:34')
    expect(fp).toHaveAttribute('title', FP)
    fireEvent.click(screen.getByRole('button', { name: 'Copy Fingerprint (SHA-256)' }))
    await waitFor(() => expect(spy).toHaveBeenLastCalledWith(FP))
    await screen.findByRole('button', { name: 'Copied' })
    fireEvent.click(screen.getByRole('button', { name: 'Copy Subject DN' }))
    await waitFor(() => expect(spy).toHaveBeenLastCalledWith('CN=www.example.com,O=Example Ltd,L=London,C=GB'))
    fireEvent.click(screen.getByRole('button', { name: 'Copy Serial Number' }))
    await waitFor(() => expect(spy).toHaveBeenLastCalledWith('0A1B2C3D4E5F60718293A4B5C6D7E8F9'))
  })

  it('OCSP/CRL: yalnız http(s) dış bağlantı (yeni sekme, noopener noreferrer); javascript: adresi bağlantı OLMAZ', () => {
    render(<CertDetailsPanel d={rich({ crl_url: 'javascript:alert(1)' })} now={NOW} />)
    const infra = section('Infrastructure')
    const link = within(infra).getByRole('link', { name: /ocsp\.example\.com/ })
    expect(link).toHaveAttribute('href', 'http://ocsp.example.com/')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    expect(within(infra).getAllByRole('link')).toHaveLength(1)
    expect(within(field('crl')).getByText('javascript:alert(1)')).toBeInTheDocument()
    expect(document.querySelector('a[href^="javascript"]')).toBeNull()
    expect(within(infra).getByRole('button', { name: 'Copy CRL URL' })).toBeInTheDocument()
  })
})

describe('CertDetailsPanel — alternatif adlar (SAN)', () => {
  const many = () => {
    const san = Array.from({ length: 38 }, (_, i) => `svc-${String(i).padStart(2, '0')}.example.com`)
    san.splice(25, 0, 'www.example.com')
    san.splice(30, 0, '*.example.com')
    return san
  }

  it('az girdide arama yok, hepsi görünür; alan adıyla eşleşen EN ÖNDE ve vurgulu, joker işaretli', () => {
    render(<CertDetailsPanel d={rich()} now={NOW} />)
    const sec = section('Alternative Names (SAN)')
    expect(within(sec).queryByRole('searchbox')).toBeNull()
    const items = within(sec).getAllByRole('listitem')
    expect(items).toHaveLength(6)
    const first = items[0].querySelector('[data-slot="cert-san"]')
    expect(first).toHaveTextContent('www.example.com')
    expect(first).toHaveAttribute('data-match', 'exact')
    const wild = items[1].querySelector('[data-slot="cert-san"]')
    expect(wild).toHaveAttribute('data-match', 'wildcard')
    expect(wild).toHaveAttribute('data-wildcard', 'true')
    expect(within(wild).getByText('Wildcard')).toBeInTheDocument()
  })

  it('40 girdi: sayaç, ilk 12 + "Show all (40)" / "Show fewer", arama süzer, sonuç sayısı duyurulur, sonuç yoksa boş durum', () => {
    render(<CertDetailsPanel d={rich({ san: many() })} now={NOW} />)
    const sec = section('Alternative Names (SAN)')
    expect(within(sec).getByText('40')).toHaveAttribute('data-slot', 'cert-san-count')
    const list = () => within(sec).getByRole('list', { name: 'Alternative Names (SAN)' })
    expect(within(list()).getAllByRole('listitem')).toHaveLength(12)
    expect(within(list()).getAllByRole('listitem')[0]).toHaveTextContent('www.example.com')
    const toggle = within(sec).getByRole('button', { name: 'Show all (40)' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(toggle)
    expect(within(list()).getAllByRole('listitem')).toHaveLength(40)
    fireEvent.click(within(sec).getByRole('button', { name: 'Show fewer' }))
    expect(within(list()).getAllByRole('listitem')).toHaveLength(12)

    const search = within(sec).getByRole('searchbox', { name: 'Search names' })
    fireEvent.change(search, { target: { value: 'SVC-3' } })
    expect(within(list()).getAllByRole('listitem').map((li) => li.textContent)).toEqual(
      Array.from({ length: 8 }, (_, i) => `svc-3${i}.example.com`))
    expect(within(sec).getByRole('status')).toHaveTextContent('8 of 40 names match')
    expect(within(sec).queryByRole('button', { name: /Show all/ })).toBeNull()

    fireEvent.change(search, { target: { value: 'nothing-here' } })
    expect(within(sec).queryByRole('list', { name: 'Alternative Names (SAN)' })).toBeNull()
    expect(within(sec).getByText('No names match “nothing-here”')).toBeInTheDocument()
    fireEvent.click(within(sec).getByRole('button', { name: 'Clear Filter' }))
    expect(within(list()).getAllByRole('listitem')).toHaveLength(12)
  })

  it('tümünü kopyala: daraltılmış görünümde de TÜM ham adlar, satır satır', async () => {
    const spy = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue()
    render(<CertDetailsPanel d={rich({ san: many() })} now={NOW} />)
    fireEvent.click(screen.getByRole('button', { name: 'Copy all 40 alternative names' }))
    await waitFor(() => expect(spy).toHaveBeenCalledTimes(1))
    const copied = spy.mock.calls[0][0].split('\n')
    expect(copied).toHaveLength(40)
    expect(new Set(copied)).toEqual(new Set(many()))
  })
})

// ── Bağlantı (TLS) — 2026-09-28: sürüm + şifre takımı veritabanındaydı, veri ucu göndermiyordu (CertificateDto) ──
describe('CertDetailsPanel — TLS sürümü ve şifre takımı', () => {
  it('güncel protokol + güçlü şifre: Güvenlik bölümünde okunur sürüm ve ham takım adı, uyarı rozeti YOK', () => {
    render(<CertDetailsPanel d={rich({ tls_version: 'TLSv1.3', cipher_suite: 'TLS_AES_256_GCM_SHA384',
      tls_assessment: { protocol: 'OK', protocol_latest: true, cipher: 'OK' } })} now={NOW} />)
    const sec = section('Security')
    expect(within(field('tls-version')).getByText('TLS version')).toBeInTheDocument()
    expect(within(field('tls-version')).getByText('TLS 1.3')).toBeInTheDocument()
    expect(within(field('cipher')).getByText('TLS_AES_256_GCM_SHA384')).toBeInTheDocument()
    expect(sec.contains(field('tls-version'))).toBe(true)
    expect(sec.querySelector('[data-slot="cert-weak-protocol"]')).toBeNull()
    expect(sec.querySelector('[data-slot="cert-weak-cipher"]')).toBeNull()
  })

  it('eski protokol (TLS 1.0 — Java adı "TLSv1") + zayıf şifre: sunucu hükmüne göre UYARI tonlu rozet + görünür açıklama', () => {
    render(<CertDetailsPanel d={rich({ tls_version: 'TLSv1', cipher_suite: 'TLS_RSA_WITH_3DES_EDE_CBC_SHA',
      tls_assessment: { protocol: 'FAIL', protocol_latest: false, cipher: 'FAIL' } })} now={NOW} />)
    const proto = field('tls-version')
    expect(within(proto).getByText('TLS 1.0')).toBeInTheDocument()
    const weakProto = proto.querySelector('[data-slot="cert-weak-protocol"]')
    expect(weakProto).toHaveAttribute('data-tone', 'warn')
    expect(weakProto).toHaveTextContent('Outdated protocol')
    expect(within(proto).getByText(/current browsers refuse this connection/)).toBeInTheDocument()
    const weakCipher = field('cipher').querySelector('[data-slot="cert-weak-cipher"]')
    expect(weakCipher).toHaveAttribute('data-tone', 'warn')
    expect(weakCipher).toHaveTextContent('Weak cipher')
  })

  it('kural istemcide YAZILMAZ: hüküm yoksa (eski yanıt) rozet de yok; önizlemenin `assessment` alanı da okunur', () => {
    const { unmount } = render(<CertDetailsPanel d={rich({ tls_version: 'TLSv1', cipher_suite: 'TLS_RSA_WITH_RC4_128_SHA' })} now={NOW} />)
    expect(within(field('tls-version')).getByText('TLS 1.0')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="cert-weak-protocol"]')).toBeNull()
    expect(document.querySelector('[data-slot="cert-weak-cipher"]')).toBeNull()
    unmount()
    render(<CertDetailsPanel d={rich({ tls_version: 'TLSv1.1', cipher_suite: 'TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA',
      assessment: { protocol: 'FAIL', cipher: 'WARN' } })} now={NOW} />)
    expect(document.querySelector('[data-slot="cert-weak-protocol"]')).not.toBeNull()
    expect(document.querySelector('[data-slot="cert-weak-cipher"]')).toBeNull()   // CBC = öneri (WARN), zayıf değil
  })

  it('TLS verisi yoksa iki satır da çizilmez (boş satır / "—" yok)', () => {
    render(<CertDetailsPanel d={rich()} now={NOW} />)
    expect(field('tls-version')).toBeNull()
    expect(field('cipher')).toBeNull()
  })
})

// 2026-10-08 (kullanıcı: "herhangi bir adres göremedim. O alanları boş da olsa ekleyelim, boş olduğunu bilelim")
describe('CertDetailsPanel — OCSP/CRL adresi yok', () => {
  it('okunmuş sertifikada adres yoksa Altyapı görünür, iki alan "Not defined in the certificate"; iptal çipi "No revocation address"', () => {
    render(<CertDetailsPanel d={rich({ ocsp_url: null, crl_url: '', revocation_status: 'UNKNOWN' })} now={NOW} />)
    expect(section('Infrastructure')).toBeInTheDocument()
    for (const slot of ['ocsp', 'crl']) {
      expect(within(field(slot)).getByText('Not defined in the certificate')).toHaveAttribute('data-slot', 'cert-detail-empty')
    }
    expect(within(field('revocation')).getByText('No revocation address')).toBeInTheDocument()
    expect(screen.queryByText('Revocation status unknown')).toBeNull()
  })

  it('adres varken bağlantı gösterilir; adres var + bilinmiyor → "Revocation status unknown" (ulaşılamadı) kalır', () => {
    render(<CertDetailsPanel d={rich({ revocation_status: 'UNKNOWN', revocation_reason: 'UNREACHABLE' })} now={NOW} />)
    expect(within(field('ocsp')).getByRole('link', { name: /ocsp\.example\.com/ })).toBeInTheDocument()
    expect(within(field('revocation')).getByText('Revocation status unknown')).toBeInTheDocument()
  })
})
