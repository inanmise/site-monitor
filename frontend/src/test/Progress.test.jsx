import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Spinner, ProgressBar, ProgressRing, LoadingBlock } from '../components/ui/Progress.jsx'

/**
 * Progress ailesinin erişilebilirlik sözleşmesi. Projede bu bileşenlerden ÖNCE
 * role="progressbar" / aria-valuenow / aria-busy kullanımı SIFIRDI; bu test sözleşmeyi kilitler.
 * İç çizim shadcn (Spinner / Progress); kancalar sınıf değil data-slot + rol.
 */
describe('Spinner — belirsiz gösterge', () => {
  it('etiketliyse role="status" ile duyurulur', () => {
    render(<Spinner label="Yükleniyor" />)
    const status = screen.getByRole('status')
    expect(status).toHaveTextContent('Yükleniyor')
    expect(status.getAttribute('aria-live')).toBe('polite')
  })

  it('etiketliyken TEK status bölgesi: ikonun kendi (shadcn) rolü kaldırılmış', () => {
    const { container } = render(<Spinner label="Yükleniyor" />)
    expect(screen.getAllByRole('status')).toHaveLength(1)
    const icon = container.querySelector('[data-slot="spinner"]')
    expect(icon.getAttribute('aria-hidden')).toBe('true')
    expect(icon.hasAttribute('role')).toBe(false)
  })

  it('belirsiz olduğu için aria-valuenow TAŞIMAZ', () => {
    const { container } = render(<Spinner label="Yükleniyor" />)
    expect(container.querySelector('[aria-valuenow]')).toBeNull()
    expect(container.querySelector('[role="progressbar"]')).toBeNull()
  })

  it('decorative → ekran okuyucudan gizlenir (buton metni zaten anlatıyor)', () => {
    const { container } = render(<Spinner decorative />)
    expect(screen.queryByRole('status')).toBeNull()
    expect(container.querySelector('[data-slot="spinner"]').getAttribute('aria-hidden')).toBe('true')
  })

  it('etiketsiz kullanım da süs sayılır', () => {
    const { container } = render(<Spinner />)
    expect(screen.queryByRole('status')).toBeNull()
    expect(container.querySelector('[data-slot="spinner"]').getAttribute('aria-hidden')).toBe('true')
  })

  it('size prop\'u çapı piksel olarak belirler (eski API)', () => {
    const { container } = render(<Spinner size={12} decorative />)
    const icon = container.querySelector('[data-slot="spinner"]')
    expect(icon.style.width).toBe('12px')
    expect(icon.style.height).toBe('12px')
  })
})

describe('ProgressBar — shadcn Progress (Radix)', () => {
  it('rol ve değer semantiği taşır; value/max doğru yansır', () => {
    render(<ProgressBar value={3} max={12} label="Kontrol" />)
    const el = screen.getByRole('progressbar', { name: 'Kontrol' })
    expect(el.getAttribute('aria-valuenow')).toBe('3')
    expect(el.getAttribute('aria-valuemin')).toBe('0')
    expect(el.getAttribute('aria-valuemax')).toBe('12')
  })

  it('görünen yüzde ile okunan değer aynı hesaptan gelir', () => {
    render(<ProgressBar value={3} max={12} showValue />)
    const el = screen.getByRole('progressbar')
    expect(el.getAttribute('aria-valuetext')).toMatch(/^(%25|25%)$/)
    // görünen metin = aria-valuetext (ikisi de formatPercent(pct))
    expect(screen.getByText(el.getAttribute('aria-valuetext'))).toBeInTheDocument()
    expect(el.getAttribute('aria-valuenow')).toBe('3')
  })

  it('dolgu oranı max\'a göre çizilir (3/12 → %25, %3 değil)', () => {
    const { container } = render(<ProgressBar value={3} max={12} />)
    const fill = container.querySelector('[data-slot="progress-indicator"]')
    expect(fill.style.transform).toBe('translateX(-75%)')
  })

  it('değer aralık dışındaysa kırpılır', () => {
    render(<ProgressBar value={99} max={10} />)
    expect(screen.getByRole('progressbar').getAttribute('aria-valuenow')).toBe('10')
  })

  it('max geçersizse belirsiz çubuk (aria-valuenow YOK)', () => {
    render(<ProgressBar value={5} max={0} />)
    const el = screen.getByRole('progressbar')
    expect(el.hasAttribute('aria-valuenow')).toBe(false)
    expect(el.getAttribute('data-state')).toBe('indeterminate')
  })

  it('decorative → çubuk ekran okuyucudan gizlenir, görsel yine çizilir', () => {
    const { container } = render(<ProgressBar value={4} max={8} label="x" decorative />)
    expect(screen.queryByRole('progressbar')).toBeNull()
    expect(container.querySelector('[data-slot="progress"]').getAttribute('aria-hidden')).toBe('true')
  })

  it('eski eşik sınıfı (pg-bar--warn) tona çevrilir, köke legacy sınıf olarak SIZMAZ', () => {
    const { container } = render(<ProgressBar value={70} className="pg-bar--warn my-bar" />)
    const root = container.querySelector('[data-slot="progress"]')
    expect(root.className).not.toMatch(/pg-bar--/)
    expect(root.className).toContain('my-bar')   // çağıranın kendi sınıfı korunur
    expect(container.querySelector('[data-slot="progress-indicator"]').className).toContain('bg-warning')
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
    expect(bar).toHaveTextContent(bar.getAttribute('aria-valuenow'))
    expect(bar.textContent).toBe(bar.getAttribute('aria-valuenow'))
  })

  it('değer bilinmiyorsa aria-valuenow verilmez (belirsiz)', () => {
    render(<ProgressRing value={undefined} label="Hesaplanıyor" />)
    const bar = screen.getByRole('progressbar')
    expect(bar.hasAttribute('aria-valuenow')).toBe(false)
    expect(bar.getAttribute('data-state')).toBe('indeterminate')
  })
})

describe('LoadingBlock — bölüm yükleniyor', () => {
  it('role="status" ve etiketi duyurur', () => {
    render(<LoadingBlock label="Sertifikalar yükleniyor" />)
    expect(screen.getByRole('status')).toHaveTextContent('Sertifikalar yükleniyor')
  })

  it('fullWidth → grid satırını kaplar', () => {
    const { container } = render(<LoadingBlock label="x" fullWidth />)
    expect(container.querySelector('[data-slot="loading-block"]').className).toContain('col-span-full')
  })

  it('belirsizdir: progressbar rolü ya da valuenow yok', () => {
    const { container } = render(<LoadingBlock label="x" />)
    expect(container.querySelector('[role="progressbar"]')).toBeNull()
    expect(container.querySelector('[aria-valuenow]')).toBeNull()
  })
})
