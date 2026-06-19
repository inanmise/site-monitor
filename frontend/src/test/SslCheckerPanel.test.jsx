import { describe, it, expect } from 'vitest'
import { render, screen } from './test-utils.jsx'
import SslCheckerPanel from '../components/SslCheckerPanel.jsx'

// Okunabilir ama public CA ile DOĞRULANMAYAN iç host (ucms.akbank.com senaryosu):
// artık "Could not reach" değil, sertifika + "güvenilmeyen zincir" rozeti gösterilmeli.
const base = {
  status: 'ok',
  domain: 'ucms.example.com',
  resolved_ip: '10.0.0.1',
  chain_status: 'VALID',
  revocation_status: 'VALID',
  days_remaining: 100,
  not_before: '2026-01-01T00:00:00',
  not_after: '2027-01-01T00:00:00',
  san: ['ucms.example.com'],
  issuer: 'Akbank Internal CA',
  hsts: true,
}

describe('SslCheckerPanel trust_status', () => {
  it('trust_status=UNTRUSTED → "güvenilmeyen zincir" satırı; "Could not reach" YOK', () => {
    render(<SslCheckerPanel data={{ ...base, trust_status: 'UNTRUSTED' }} />)
    expect(screen.getByText('Untrusted chain — CA not in truststore')).toBeDefined()
    expect(screen.queryByText('Could not reach the server')).toBeNull()
    // sertifika gövdesi okunabiliyor (domain birden çok yerde: DNS/SAN/CN)
    expect(screen.getAllByText(/ucms\.example\.com/).length).toBeGreaterThanOrEqual(1)
  })

  it('trust_status=TRUSTED → güvenilir-kök satırı', () => {
    render(<SslCheckerPanel data={{ ...base, trust_status: 'TRUSTED' }} />)
    expect(screen.getByText('Chain anchors to a trusted root (truststore)')).toBeDefined()
  })

  it('trust_status yoksa anchor satırı render edilmez', () => {
    render(<SslCheckerPanel data={base} />)
    expect(screen.queryByText('Untrusted chain — CA not in truststore')).toBeNull()
    expect(screen.queryByText('Chain anchors to a trusted root (truststore)')).toBeNull()
  })

  it('status=error → hâlâ "Could not reach the server"', () => {
    render(<SslCheckerPanel data={{ status: 'error', error: 'SSL handshake: ...' }} />)
    expect(screen.getByText('Could not reach the server')).toBeDefined()
  })
})
