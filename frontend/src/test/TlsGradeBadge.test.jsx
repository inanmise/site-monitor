import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import TlsGradeBadge from '../components/tlsgrade/TlsGradeBadge.jsx'

vi.mock('../api/client', () => ({ formatDate: (s) => s || '' }))

/**
 * TLS notu rozeti (2026-10-10): not yoksa çizilmez; harf + (Zengin) "TLS"; dokununca "Neden B?" — belirleyici nedenler
 * tavanı nota eşit olanlar; düşüş oku + "A → B"; ayrıntı düğmesi; tetik tıklaması karta/satıra taşınmaz.
 */
const cert = (over = {}) => ({ domain: 'shop.example.com', tls_grade: 'B', tls_grade_reasons: ['TLS10_ENABLED', 'NO_TLS13'], ...over })

describe('TlsGradeBadge', () => {
  it('not yoksa hiçbir şey çizmez (elle yüklenen / veri yok)', () => {
    const { container } = render(<TlsGradeBadge cert={{ domain: 'x' }} />)
    expect(container.querySelector('[data-slot="tls-grade"]')).toBeNull()
  })

  it('harf + erişilebilir ad; Kompakt’ta "TLS" etiketi yok', () => {
    const { rerender } = render(<TlsGradeBadge cert={cert()} />)
    const trigger = screen.getByRole('button', { name: 'shop.example.com — TLS grade B; show the reasons' })
    expect(trigger.querySelector('[data-slot="tls-grade"]')).toHaveAttribute('data-grade', 'B')
    expect(trigger).toHaveTextContent('TLSB')
    rerender(<TlsGradeBadge cert={cert()} compact />)
    expect(screen.getByRole('button', { name: /TLS grade B/ })).not.toHaveTextContent('TLS')
  })

  it('dokununca "Neden B?": belirleyici neden ayrı, diğerleri "ayrıca" altında; ayrıntı düğmesi', () => {
    const onOpenDetail = vi.fn()
    const onParent = vi.fn()
    render(<div onClick={onParent}><TlsGradeBadge cert={cert()} onOpenDetail={onOpenDetail} /></div>)
    fireEvent.click(screen.getByRole('button', { name: /TLS grade B/ }))
    expect(onParent).not.toHaveBeenCalled()
    const detail = document.querySelector('[data-slot="tls-grade-detail"]')
    expect(detail).toHaveTextContent('Why B?')
    const decisive = detail.querySelector('[data-slot="tls-grade-reason"][data-decisive="true"]')
    expect(decisive).toHaveAttribute('data-code', 'TLS10_ENABLED')
    expect(decisive).toHaveTextContent('TLS 1.0 is on')
    expect(detail.querySelector('[data-code="NO_TLS13"]')).not.toHaveAttribute('data-decisive')
    fireEvent.click(screen.getByRole('button', { name: /TLS grade details/ }))
    expect(onOpenDetail).toHaveBeenCalledTimes(1)
    expect(onParent).not.toHaveBeenCalled()
  })

  it('not düştüyse ok simgesi, ad ve açıklamada "A → B"', () => {
    render(<TlsGradeBadge cert={cert({ tls_grade_drop: { from: 'A', to: 'B', at: '2026-10-09T10:00:00' } })} />)
    const trigger = screen.getByRole('button', { name: /grade dropped from A to B/ })
    expect(trigger.querySelector('[data-slot="tls-grade"]')).toHaveAttribute('data-dropped', 'true')
    fireEvent.click(trigger)
    expect(document.querySelector('[data-slot="tls-grade-drop"]')).toHaveTextContent('Grade dropped A → B')
  })

  it('A+ ve neden yok: "sorun yok" metni', () => {
    render(<TlsGradeBadge cert={cert({ tls_grade: 'A+', tls_grade_reasons: undefined })} />)
    fireEvent.click(screen.getByRole('button', { name: /TLS grade A\+/ }))
    expect(document.querySelector('[data-slot="tls-grade-detail"]')).toHaveTextContent('Nothing to fix')
  })

  it('dokunmatik hedef: tetik pointer-coarse / telefon için min 40 px sınıfı taşır', () => {
    render(<TlsGradeBadge cert={cert()} />)
    expect(screen.getByRole('button', { name: /TLS grade B/ }).className).toMatch(/pointer-coarse:min-h-10/)
  })
})
