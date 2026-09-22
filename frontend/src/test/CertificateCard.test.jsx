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

  it("Sil handlerı geçilince dördüncü buton çıkar ve KART DETAYINI açmaz", () => {
    const onDelete = vi.fn(), onClick = vi.fn()
    render(<CertificateCard cert={makeCert()} onClick={onClick}
      onCheckNow={() => {}} onEdit={() => {}} onDuplicate={() => {}} onDelete={onDelete} />)

    const btns = actionBtns()
    expect(btns).toHaveLength(4)
    fireEvent.click(btns[3])
    expect(onDelete).toHaveBeenCalledTimes(1)
    // Yıkıcı eylemde çift açılış EN pahalı hata: onay diyaloğuyla birlikte detay modali da açılırdı.
    expect(onClick).not.toHaveBeenCalled()
  })

  it('yetkisi olmayanda (onDelete yok) Sil butonu HİÇ çizilmez', () => {
    render(<CertificateCard cert={makeCert()} onClick={() => {}}
      onCheckNow={() => {}} onEdit={() => {}} onDuplicate={() => {}} />)
    expect(actionBtns()).toHaveLength(3)
  })

  it('deleting=true iken YALNIZ Sil kilitlenir (çift tık koruması)', () => {
    render(<CertificateCard cert={makeCert()} onClick={() => {}} deleting
      onCheckNow={() => {}} onEdit={() => {}} onDuplicate={() => {}} onDelete={() => {}} />)
    const [run, edit, dup, del] = actionBtns()
    expect(del.disabled).toBe(true)
    expect(run.disabled).toBe(false)
    expect(edit.disabled).toBe(false)
    expect(dup.disabled).toBe(false)
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

describe('CertificateCard — yakın zamanda yenilendi rozeti (2026-09-12, #6)', () => {
  const base = { domain: 'r.example.com', status: 'valid', alert_level: 'OK', days_remaining: 300, not_after: '2027-07-01T00:00:00', checked_at: '2026-09-12T10:00:00', public_key_algorithm: 'RSA', public_key_size: 2048, signature_algorithm: 'SHA256withRSA' }
  it('not_before 5 gün önce → "5 gün önce yenilendi"; 90 gün önce → rozet yok', () => {
    const ago = (d) => new Date(Date.now() - d * 86400000).toISOString()
    const { container, unmount } = render(<CertificateCard cert={{ ...base, not_before: ago(5) }} isWeak={false} onClick={() => {}} />)
    expect(container.querySelector('.cc-renewed-chip')).not.toBeNull()
    expect(container.querySelector('.cc-renewed-chip').textContent).toMatch(/5 gün önce yenilendi|Renewed 5 days ago/)
    unmount()
    const { container: c2 } = render(<CertificateCard cert={{ ...base, not_before: ago(90) }} isWeak={false} onClick={() => {}} />)
    expect(c2.querySelector('.cc-renewed-chip')).toBeNull()
  })
  it('algoritma çipi başlığı imza algoritmasını da taşır', () => {
    const { container } = render(<CertificateCard cert={{ ...base, not_before: '2026-01-01T00:00:00' }} isWeak={false} onClick={() => {}} />)
    expect(container.querySelector('.cc-algo-chip').getAttribute('title')).toMatch(/SHA256withRSA/)
  })
})

describe('CertificateCard — platform çipi (2026-09-22)', () => {
  const base = { domain: 'p.example.com', status: 'valid', alert_level: 'OK', days_remaining: 300, not_after: '2027-07-01T00:00:00', checked_at: '2026-09-12T10:00:00', team_id: 5, team_name: 'Takım A' }
  it('kompakt: katalog adı (yoksa kod) çizilir, ayrıntı yalnız tooltip; zengin (extra): ayrıntı metni de görünür; platform yoksa çip yok', () => {
    const cert = { ...base, platform: 'OPENSHIFT', platform_name: 'OpenShift', platform_detail: 'ocp-prod' }
    const { container, unmount } = render(<CertificateCard cert={cert} isWeak={false} onClick={() => {}} />)
    const chip = container.querySelector('.cc-platform-chip')
    expect(chip.querySelector('.cc-platform-name').textContent).toBe('OpenShift')
    expect(chip.querySelector('.cc-platform-detail')).toBeNull()
    expect(chip.getAttribute('title')).toMatch(/ocp-prod/)
    unmount()
    const { container: c2, unmount: u2 } = render(<CertificateCard cert={{ ...cert, platform_name: null }} extra={{}} isWeak={false} onClick={() => {}} />)
    expect(c2.querySelector('.cc-platform-name').textContent).toBe('OPENSHIFT')
    expect(c2.querySelector('.cc-platform-detail').textContent).toMatch(/ocp-prod/)
    u2()
    const { container: c3 } = render(<CertificateCard cert={base} isWeak={false} onClick={() => {}} />)
    expect(c3.querySelector('.cc-platform-chip')).toBeNull()
  })
})
