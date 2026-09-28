import { describe, it, expect, vi } from 'vitest'
import { render, screen } from './test-utils.jsx'
import CertificateCard from '../components/CertificateCard.jsx'
import SslCheckerPanel from '../components/SslCheckerPanel.jsx'
import { isInsecure, securityFlags, securityTitle } from '../utils/certSecurity.js'

/**
 * "Geçerli" rozeti artık YALAN SÖYLEYEMEZ.
 *
 * <p>Var olmayan bir alan adı NXDOMAIN-hijack ile bir ev modeminin paneline çözüldüğünde,
 * modemin kendinden imzalı sertifikası (1775 gün kalan) yeşil "Geçerli" görünüyordu — Chrome
 * aynı adresi reddederken. Süre rozeti KALIR (110 gün kaldı bilgisi doğrudur), yanına ayrı bir
 * kırmızı "Güvensiz" çipi eklenir; böylece süre süzgeçleri ve raporlar hiç etkilenmez.
 */
function cert(overrides = {}) {
  return {
    domain: 'test.example.com', subject: 'CN=test.example.com',
    issuer_cn: 'Test CA', issuer: 'Test CA',
    not_after: '2031-07-10T00:00:00', checked_at: '2026-08-29T00:00:00',
    status: 'valid', warning: false, days_remaining: 1775,
    ...overrides,
  }
}

const HIJACKED = cert({ security_flags: ['HOSTNAME_MISMATCH', 'UNTRUSTED_CA'], secure: false })

describe('certSecurity yardımcısı', () => {
  it('bayrak yoksa güvenli sayılır; alan hiç gelmemişse de çökmez', () => {
    expect(isInsecure(cert())).toBe(false)
    expect(isInsecure(cert({ security_flags: [] }))).toBe(false)
    expect(isInsecure(undefined)).toBe(false)
    expect(securityFlags(cert())).toEqual([])
  })

  it('bilinen bayraklar okunur metne çevrilir; bilinmeyen ham hâliyle görünür', () => {
    const t = (k) => (k === 'cert.sec.hostnameMismatch' ? 'kapsamıyor' : k)
    expect(securityTitle(HIJACKED, t)).toContain('kapsamıyor')
    expect(securityTitle(cert({ security_flags: ['YENI_BAYRAK'] }), t)).toBe('YENI_BAYRAK')
  })
})

describe('CertificateCard güvenlik çipi', () => {
  it('güvenlik kusuru varken "Güvensiz" çipi çizilir ve SÜRE rozeti yerinde kalır', () => {
    render(<CertificateCard cert={HIJACKED} onClick={() => {}} />)
    expect(screen.getByText(/Güvensiz|Insecure/i)).toBeDefined()
    expect(screen.getByText('1775')).toBeDefined()          // süre bilgisi kaybolmadı
  })

  it('sağlıklı sertifikada çip HİÇ çizilmez (yanlış pozitif yok)', () => {
    render(<CertificateCard cert={cert({ security_flags: [], secure: true })} onClick={() => {}} />)
    expect(screen.queryByText(/Güvensiz|Insecure/i)).toBeNull()
  })

  it('security_flags alanı hiç gelmeyen eski kayıtta da çip çizilmez', () => {
    render(<CertificateCard cert={cert()} onClick={() => {}} />)
    expect(screen.queryByText(/Güvensiz|Insecure/i)).toBeNull()
  })
})

describe('SslCheckerPanel çelişkili satırlar', () => {
  // GERÇEK tel biçimi (check-preview): tek parçalı kendinden imzalı zincir + sunucunun değerlendirmesi
  // (CertificateController.putPreviewAssessment → assessment / security_flags; kart rozetiyle AYNI kural).
  const hijacked = {
    status: 'valid', domain: 'olmayan.example.com', resolved_ip: '192.168.1.1',
    chain_status: 'VALID',          // tek parçalı kendinden imzalı zincir hiç BROKEN olmaz
    chain: [{ position: 0, subject: 'CN=192.0.2.1,O=ZTE', issuer: 'CN=ZTE-ROOT-CA,O=ZTE', not_before: '2016-07-13T00:00:00',
      not_after: '2031-07-10T00:00:00', days_remaining: 1775, is_root: true, is_leaf: true, serial_number: '01', signature_algorithm: 'SHA256withRSA', expired: false }],
    trust_status: 'UNTRUSTED',
    revocation_status: 'UNKNOWN',
    days_remaining: 1775, warning: false,
    not_before: '2016-07-13T00:00:00', not_after: '2031-07-10T00:00:00',
    san: ['192.0.2.1'],             // istenen alan adını kapsamıyor
    issuer: 'ZTE-ROOT-CA', hsts: false,
    assessment: { hostname: 'FAIL', protocol: 'UNKNOWN', protocol_latest: false, cipher: 'UNKNOWN', pfs: 'UNKNOWN', signature: 'OK', key_size: 'UNKNOWN' },
    security_flags: ['HOSTNAME_MISMATCH', 'UNTRUSTED_CA'],
  }
  const row = (key) => document.querySelector(`[data-slot="ssl-check"][data-check="${key}"]`)

  it('güven YOKKEN hiçbir satır "tüm büyük tarayıcılar tarafından güvenilir" DEMEZ', () => {
    render(<SslCheckerPanel data={hijacked} />)
    expect(screen.queryByText(/tüm büyük tarayıcılar|trusted by all major browsers/i)).toBeNull()
  })

  it('zincir bütünlüğü satırı hâlâ vardır — ölçtüğü şeyi söyler (süre geçerliliği, güven DEĞİL)', () => {
    render(<SslCheckerPanel data={hijacked} />)
    expect(row('chain')).toHaveAttribute('data-status', 'ok')
    expect(row('chain').textContent).toMatch(/hepsinin süresi geçerli|all in date/i)
    expect(row('chain').textContent).not.toMatch(/güvenilir|trusted/i)
  })

  it('düzenleyeni (ZTE-ROOT-CA) söyleyen güven satırı güvenilmeyen CA için ONAY almaz', () => {
    render(<SslCheckerPanel data={hijacked} />)
    const rows = Array.from(document.querySelectorAll('[data-slot="ssl-check"]')).filter((el) => /ZTE-ROOT-CA/.test(el.textContent))
    expect(rows.length).toBeGreaterThan(0)
    rows.forEach((el) => expect(el).not.toHaveAttribute('data-status', 'ok'))
    expect(row('trust')).toHaveAttribute('data-status', 'fail')
  })

  it('hostname uyuşmazlığı satırı kırmızı kalır ve hüküm "sorun var" olur (kart "Güvensiz" derken sekme yeşil DEMEZ)', () => {
    render(<SslCheckerPanel data={hijacked} />)
    expect(row('hostname')).toHaveAttribute('data-status', 'fail')
    expect(row('hostname').textContent).toMatch(/kapsamında değil|isn't covered by this certificate/i)
    expect(document.querySelector('[data-slot="ssl-verdict"]')).toHaveAttribute('data-tone', 'fail')
  })
})
