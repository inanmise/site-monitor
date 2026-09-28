import { describe, it, expect, vi } from 'vitest'
import { render, screen, within, fireEvent } from './test-utils.jsx'
import SslCheckerPanel from '../components/SslCheckerPanel.jsx'
import { healthyPreview } from './helpers/sslPreviewFixture.js'

/**
 * SSL Kontrol sekmesi (2026-09-28 shadcn yeniden tasarım): hüküm kartı + gruplu kontroller + zincir kartları.
 * Fixture GERÇEK tel biçiminde (check-preview → snake_case, `assessment` / `security_flags` sunucudan).
 * Sorgular rol / ad / data-slot ile (SHADCN.md §8.2).
 */
const check = (key) => document.querySelector(`[data-slot="ssl-check"][data-check="${key}"]`)

// Okunabilir ama public CA ile DOĞRULANMAYAN iç host (ucms.example.com senaryosu):
// "Could not reach" DEĞİL, sertifika + "güvenilmeyen zincir" satırı gösterilmeli.
const internal = (over = {}) => healthyPreview({
  domain: 'ucms.example.com', subject: 'ucms.example.com', san: ['ucms.example.com'],
  issuer: 'Example Internal CA', issuer_cn: 'Example Internal CA', resolved_ip: '10.0.0.1', ...over,
})

describe('SslCheckerPanel — güven durumu (trust_status)', () => {
  it('UNTRUSTED → güven satırı SORUN, hüküm FAIL; "Could not reach" YOK; sertifika gövdesi okunur', () => {
    render(<SslCheckerPanel data={internal({ trust_status: 'UNTRUSTED', security_flags: ['UNTRUSTED_CA'] })} />)
    expect(check('trust')).toHaveAttribute('data-status', 'fail')
    expect(within(check('trust')).getByText(/Example Internal CA isn't a trusted root/)).toBeInTheDocument()
    expect(screen.queryByText('Could not reach the server')).toBeNull()
    expect(document.querySelector('[data-slot="ssl-verdict"]')).toHaveAttribute('data-tone', 'fail')
    expect(screen.getAllByText(/ucms\.example\.com/).length).toBeGreaterThanOrEqual(1)
  })

  it('TRUSTED → güven satırı sorunsuz ve düzenleyeni söyler', () => {
    render(<SslCheckerPanel data={internal({ trust_status: 'TRUSTED' })} />)
    expect(check('trust')).toHaveAttribute('data-status', 'ok')
    expect(within(check('trust')).getByText(/Issued by Example Internal CA and chains to a trusted root/)).toBeInTheDocument()
  })

  it('trust_status yoksa güven satırı "denetlenemedi" (gri) — ne onay ne sorun', () => {
    render(<SslCheckerPanel data={internal({ trust_status: undefined })} />)
    expect(check('trust')).toHaveAttribute('data-status', 'unknown')
  })

  it('status=error → "Could not reach the server" + hata sınıfına göre neden + ham ileti + IP\'ler', () => {
    const onRecheck = vi.fn()
    render(<SslCheckerPanel onRecheck={onRecheck} data={{
      domain: 'down.example.com', status: 'error', error: 'SSL handshake: Received fatal alert: handshake_failure',
      error_class: 'SSL', error_stage: 'tls-handshake', resolved_ips: ['203.0.113.7'], warning: true,
      checked_at: '2026-09-28T09:00:00', san: [], chain: [], port: 8443,
    }} />)
    const box = document.querySelector('[data-slot="ssl-error"]')
    expect(within(box).getByText('Could not reach the server')).toBeInTheDocument()
    expect(within(box).getByText(/TLS handshake failed/)).toBeInTheDocument()
    expect(within(box).getByText(/handshake_failure/)).toBeInTheDocument()
    expect(within(box).getByText('203.0.113.7')).toBeInTheDocument()
    fireEvent.click(within(box).getByRole('button', { name: 'Try again' }))
    expect(onRecheck).toHaveBeenCalledTimes(1)
    expect(document.querySelector('[data-slot="ssl-verdict"]')).toBeNull()
  })
})

describe('SslCheckerPanel — hüküm, gruplar, zincir', () => {
  it('sağlıklı: "All good", üç grup, her satır sorunsuz; yeniden kontrol düğmesi', () => {
    const onRecheck = vi.fn()
    render(<SslCheckerPanel data={healthyPreview()} onRecheck={onRecheck} />)
    const verdict = document.querySelector('[data-slot="ssl-verdict"]')
    expect(verdict).toHaveAttribute('data-tone', 'ok')
    expect(within(verdict).getByRole('heading', { name: 'All good' })).toBeInTheDocument()
    expect(within(verdict).getByText(/12 of 12 checks passed/)).toBeInTheDocument()
    expect([...document.querySelectorAll('[data-slot="ssl-check-group"]')].map((g) => g.dataset.group)).toEqual(['cert', 'trust', 'conn'])
    expect([...document.querySelectorAll('[data-slot="ssl-check"]')].every((r) => r.dataset.status === 'ok')).toBe(true)
    fireEvent.click(within(verdict).getByRole('button', { name: 'Check again' }))
    expect(onRecheck).toHaveBeenCalledTimes(1)
  })

  it('sorunlu: hüküm gerekçeleri düz dille ve önem sırasıyla listelenir', () => {
    render(<SslCheckerPanel data={healthyPreview({
      domain: 'a.b.example.com', days_remaining: -4, warning: true,
      assessment: { ...healthyPreview().assessment, hostname: 'FAIL' }, security_flags: ['HOSTNAME_MISMATCH'],
    })} />)
    const verdict = document.querySelector('[data-slot="ssl-verdict"]')
    expect(verdict).toHaveAttribute('data-tone', 'fail')
    const reasons = within(verdict).getAllByRole('listitem').map((li) => li.textContent)
    expect(reasons).toEqual(['Expired 4 days ago', "a.b.example.com isn't covered by this certificate — browsers will show a warning"])
  })

  it('zincir: sunucu sertifikası → ara sertifika → (kök gönderilmedi notu); SAN sayılı ve katlanır; parmak izi kopyalanır', () => {
    const san = ['www.example.com', 'example.com', 'api.example.com', 'cdn.example.com', 'mail.example.com']
    render(<SslCheckerPanel data={healthyPreview({ san })} />)
    const chain = document.querySelector('[data-slot="ssl-chain"]')
    const nodes = [...chain.querySelectorAll('[data-slot="ssl-chain-node"]')].map((n) => n.dataset.role)
    expect(nodes).toEqual(['leaf', 'intermediate', 'root-store'])
    // 4'ten fazla ad: liste KAPALI başlar, tetik sayıyı söyler
    const sanBtn = within(chain).getByRole('button', { name: /Alternative names \(SAN\)\s*5/ })
    expect(sanBtn).toHaveAttribute('aria-expanded', 'false')
    expect(chain.querySelector('[data-slot="ssl-san-list"]')).toBeNull()
    fireEvent.click(sanBtn)
    expect(within(chain.querySelector('[data-slot="ssl-san-list"]')).getAllByRole('listitem')).toHaveLength(5)
    // Parmak izi iki nokta ile okunur, kopya düğmesi hangi sertifikanın olduğunu adında taşır
    expect(within(chain).getByText(/^AB:12:CD:34/)).toBeInTheDocument()
    expect(within(chain).getByRole('button', { name: 'Copy the SHA-256 fingerprint of www.example.com' })).toBeInTheDocument()
    expect(within(chain).getByRole('button', { name: 'Copy the serial number of Example TLS RSA CA 2026' })).toBeInTheDocument()
  })

  it('yeniden kontrol başarısız olunca eski sonuç ekranda kalır, üstte uyarı bandı', () => {
    render(<SslCheckerPanel data={healthyPreview()} onRecheck={() => {}} recheckError="Failed to fetch" />)
    const banner = document.querySelector('[data-slot="alert"][data-tone="warning"]')
    expect(within(banner).getByText('Failed to fetch')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="ssl-verdict"]')).not.toBeNull()
  })

  it('yeniden kontrol sürerken düğme meşgul ve devre dışı', () => {
    render(<SslCheckerPanel data={healthyPreview()} onRecheck={() => {}} rechecking />)
    const btn = screen.getByRole('button', { name: /Checking/ })
    expect(btn).toBeDisabled()
    expect(btn).toHaveAttribute('aria-busy', 'true')
  })
})
