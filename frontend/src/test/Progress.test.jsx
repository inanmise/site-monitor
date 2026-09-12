import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Spinner, ProgressBar, ProgressRing, LoadingBlock } from '../components/ui/Progress.jsx'

/**
 * Progress ailesinin erişilebilirlik sözleşmesi. Projede bu bileşenlerden ÖNCE
 * role="progressbar" / aria-valuenow / aria-busy kullanımı SIFIRDI; bu test sözleşmeyi kilitler.
 */
describe('Spinner — belirsiz gösterge', () => {
  it('etiketliyse role="status" ile duyurulur', () => {
    render(<Spinner label="Yükleniyor" />)
    const status = screen.getByRole('status')
    expect(status).toHaveTextContent('Yükleniyor')
    expect(status.getAttribute('aria-live')).toBe('polite')
  })

  it('belirsiz olduğu için aria-valuenow TAŞIMAZ', () => {
    const { container } = render(<Spinner label="Yükleniyor" />)
    expect(container.querySelector('[aria-valuenow]')).toBeNull()
    expect(container.querySelector('[role="progressbar"]')).toBeNull()
  })

  it('decorative → ekran okuyucudan gizlenir (buton metni zaten anlatıyor)', () => {
    const { container } = render(<Spinner decorative />)
    expect(screen.queryByRole('status')).toBeNull()
    expect(container.querySelector('.pg-spinner').getAttribute('aria-hidden')).toBe('true')
  })

  it('etiketsiz kullanım da süs sayılır', () => {
    const { container } = render(<Spinner />)
    expect(container.querySelector('.pg-spinner').getAttribute('aria-hidden')).toBe('true')
  })
})

describe('ProgressBar — native <progress>', () => {
  it('rolü ve değeri tarayıcıdan gelir; value/max doğru yansır', () => {
    const { container } = render(<ProgressBar value={3} max={12} label="Kontrol" />)
    const el = container.querySelector('progress')
    expect(el.value).toBe(3)
    expect(el.max).toBe(12)
    expect(el.getAttribute('aria-label')).toBe('Kontrol')
  })

  it('görünen yüzde ile değer aynı hesaptan gelir', () => {
    const { container } = render(<ProgressBar value={3} max={12} showValue />)
    expect(container.querySelector('.pg-bar-value').textContent).toMatch(/^(%25|25%)$/)
    expect(container.querySelector('progress').value).toBe(3)
  })

  it('değer aralık dışındaysa kırpılır', () => {
    const { container } = render(<ProgressBar value={99} max={10} />)
    expect(container.querySelector('progress').value).toBe(10)
  })

  it('max geçersizse belirsiz <progress> (value attribute YOK)', () => {
    const { container } = render(<ProgressBar value={5} max={0} />)
    expect(container.querySelector('progress').hasAttribute('value')).toBe(false)
  })
})

describe('ProgressRing — belirli dairesel yay', () => {
  it('role="progressbar" + aria-valuenow/min/max taşır', () => {
    render(<ProgressRing value={45} max={90} label="Tamamlanma" />)
    const bar = screen.getByRole('progressbar')
    expect(bar.getAttribute('aria-valuenow')).toBe('50')
    expect(bar.getAttribute('aria-valuemin')).toBe('0')
    expect(bar.getAttribute('aria-valuemax')).toBe('100')
    expect(bar.getAttribute('aria-valuetext')).toMatch(/^(%50|50%)$/)
  })

  it('ekranda görünen yüzde ile aria-valuenow AYNI', () => {
    render(<ProgressRing value={7} max={9} />)
    const bar = screen.getByRole('progressbar')
    const shown = bar.querySelector('.pg-ring-value').textContent
    expect(shown).toBe(bar.getAttribute('aria-valuenow'))
  })

  it('değer bilinmiyorsa aria-valuenow verilmez (belirsiz)', () => {
    render(<ProgressRing value={undefined} label="Hesaplanıyor" />)
    const bar = screen.getByRole('progressbar')
    expect(bar.hasAttribute('aria-valuenow')).toBe(false)
    expect(bar.className).toContain('pg-ring--indeterminate')
  })
})

describe('LoadingBlock — bölüm yükleniyor', () => {
  it('role="status" ve etiketi duyurur', () => {
    render(<LoadingBlock label="Sertifikalar yükleniyor" />)
    expect(screen.getByRole('status')).toHaveTextContent('Sertifikalar yükleniyor')
  })

  it('fullWidth → grid satırını kaplayan sınıf', () => {
    const { container } = render(<LoadingBlock label="x" fullWidth />)
    expect(container.querySelector('.pg-block--full')).toBeTruthy()
  })

  it('belirsizdir: progressbar rolü ya da valuenow yok', () => {
    const { container } = render(<LoadingBlock label="x" />)
    expect(container.querySelector('[role="progressbar"]')).toBeNull()
    expect(container.querySelector('[aria-valuenow]')).toBeNull()
  })
})
