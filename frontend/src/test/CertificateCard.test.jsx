import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import CertificateCard from '../components/CertificateCard.jsx'

function makeCert(overrides = {}) {
  return {
    domain: 'test.example.com',
    subject: 'CN=test.example.com',
    issuer_cn: 'Test CA',
    issuer: 'Test CA',
    not_after: '2027-01-01T00:00:00',
    checked_at: '2026-05-01T00:00:00',
    status: 'valid',
    warning: false,
    days_remaining: 60,
    ...overrides,
  }
}

describe('CertificateCard', () => {
  it('renders the domain name', () => {
    render(<CertificateCard cert={makeCert()} onClick={() => {}} />)
    expect(screen.getByText('test.example.com')).toBeDefined()
  })

  it('shows Valid badge when days=60 and status=valid', () => {
    render(<CertificateCard cert={makeCert({ days_remaining: 60, status: 'valid' })} onClick={() => {}} />)
    expect(screen.getByText('Valid')).toBeDefined()
  })

  it('shows Warning badge when warning=true and days=35', () => {
    render(<CertificateCard cert={makeCert({ days_remaining: 35, warning: true })} onClick={() => {}} />)
    expect(screen.getByText('Warning')).toBeDefined()
  })

  it('shows CRITICAL badge when days=5 (≤ critDays threshold)', () => {
    render(<CertificateCard cert={makeCert({ days_remaining: 5, status: 'valid' })} onClick={() => {}} />)
    expect(screen.getByText('CRITICAL')).toBeDefined()
  })

  it('shows Error badge when status=error', () => {
    render(<CertificateCard cert={makeCert({ status: 'error', days_remaining: null })} onClick={() => {}} />)
    expect(screen.getByText('Error')).toBeDefined()
  })

  it('shows CRITICAL badge when days<0 (expired)', () => {
    render(<CertificateCard cert={makeCert({ days_remaining: -5, status: 'valid' })} onClick={() => {}} />)
    expect(screen.getByText('CRITICAL')).toBeDefined()
  })

  it('calls onClick with domain string when card is clicked', () => {
    const onClick = vi.fn()
    render(<CertificateCard cert={makeCert()} onClick={onClick} />)
    const card = screen.getByText('test.example.com').closest('[data-domain]')
    fireEvent.click(card)
    expect(onClick).toHaveBeenCalledWith('test.example.com')
    expect(onClick).not.toHaveBeenCalledWith(expect.objectContaining({ domain: expect.anything() }))
  })

  it('renders issuer and subject', () => {
    render(<CertificateCard cert={makeCert()} onClick={() => {}} />)
    expect(screen.getByText('Test CA')).toBeDefined()
    expect(screen.getByText('CN=test.example.com')).toBeDefined()
  })

  it('renders silent alert badge when hasSilentAlert=true', () => {
    render(<CertificateCard cert={makeCert()} onClick={() => {}} hasSilentAlert={true} />)
    expect(screen.getByText('Alert fired — no notification sent')).toBeDefined()
  })
})
