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

  it('renders issuer in the meta line (subject moved to detail modal)', () => {
    render(<CertificateCard cert={makeCert()} onClick={() => {}} />)
    // Issuer remains visible on the card; subject is rendered in the modal only.
    expect(screen.getByText(/Test CA/)).toBeDefined()
  })

  it('renders silent alert badge when hasSilentAlert=true', () => {
    render(<CertificateCard cert={makeCert()} onClick={() => {}} hasSilentAlert={true} />)
    expect(screen.getByText('Alert fired — no notification sent')).toBeDefined()
  })

  it('shows the team name when team_name is present', () => {
    render(<CertificateCard cert={makeCert({ team_name: 'Dijital SY' })} onClick={() => {}} />)
    expect(screen.getByText('Dijital SY')).toBeDefined()
  })

  it('does not render a team line when team_name is absent', () => {
    render(<CertificateCard cert={makeCert()} onClick={() => {}} />)
    expect(screen.queryByText('Dijital SY')).toBeNull()
  })
})

/**
 * Kart aksiyonları (Çalıştır / Düzenle / Kopyala) — diğer izleme türlerindeki kanonik üçlü.
 * Butonlar handler VARLIĞINA bağlı: Bitiş Tahmini ekranı yalnız cert+onClick geçtiği için
 * orada görünmemeleri gerekiyor.
 */
describe('CertificateCard — aksiyon butonları', () => {
  const actionBtns = () => [...document.querySelectorAll('.cc-footer-actions button')]

  it('handler geçilmezse hiçbir aksiyon butonu render EDİLMEZ (ExpiryForecastPage sözleşmesi)', () => {
    render(<CertificateCard cert={makeCert()} onClick={() => {}} />)
    expect(actionBtns()).toHaveLength(0)
    expect(document.querySelector('.cc-footer')).toBeNull()   // footer da açılmaz
  })

  it('üç handler geçilince üç buton çıkar ve her biri KENDİ handler\'ını çağırır', () => {
    const onCheckNow = vi.fn(), onEdit = vi.fn(), onDuplicate = vi.fn(), onClick = vi.fn()
    render(<CertificateCard cert={makeCert()} onClick={onClick}
      onCheckNow={onCheckNow} onEdit={onEdit} onDuplicate={onDuplicate} />)

    const [run, edit, dup] = actionBtns()
    expect(actionBtns()).toHaveLength(3)
    fireEvent.click(run);  expect(onCheckNow).toHaveBeenCalledTimes(1)
    fireEvent.click(edit); expect(onEdit).toHaveBeenCalledTimes(1)
    fireEvent.click(dup);  expect(onDuplicate).toHaveBeenCalledTimes(1)
    // Kart detayı AÇILMAMALI — grup stopPropagation yapıyor.
    expect(onClick).not.toHaveBeenCalled()
  })

  it('checking=true iken Çalıştır devre dışı, diğerleri değil', () => {
    render(<CertificateCard cert={makeCert()} onClick={() => {}} checking
      onCheckNow={() => {}} onEdit={() => {}} onDuplicate={() => {}} />)
    const [run, edit, dup] = actionBtns()
    expect(run.disabled).toBe(true)
    expect(edit.disabled).toBe(false)
    expect(dup.disabled).toBe(false)
  })

  it('kartta Enter detayı açar; BUTON üzerinde Enter açmaz (çift eylem guard\'ı)', () => {
    const onClick = vi.fn(), onEdit = vi.fn()
    render(<CertificateCard cert={makeCert()} onClick={onClick} onEdit={onEdit} />)

    const card = document.querySelector('.cc-card')
    expect(card.getAttribute('role')).toBe('button')
    expect(card.getAttribute('tabindex')).toBe('0')
    fireEvent.keyDown(card, { key: 'Enter' })
    expect(onClick).toHaveBeenCalledWith('test.example.com')

    onClick.mockClear()
    fireEvent.keyDown(actionBtns()[0], { key: 'Enter' })
    expect(onClick).not.toHaveBeenCalled()
  })

  it('yalnız çipler varken ikisi de render edilir (space-between yerleşim bekçisi)', () => {
    render(<CertificateCard cert={makeCert()} onClick={() => {}} hasSilentAlert hasMailFailure />)
    expect(document.querySelectorAll('.cc-footer-chips .cc-chip')).toHaveLength(2)
    expect(actionBtns()).toHaveLength(0)
  })
})
