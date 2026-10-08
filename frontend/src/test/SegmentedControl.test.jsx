import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import SegmentedControl from '../components/ui/SegmentedControl.jsx'

const options = [
  { value: 1, label: 'Son 1 gün' },
  { value: 7, label: 'Son 7 gün' },
  { value: 'custom', label: 'Özel' },
]

describe('SegmentedControl', () => {
  it('aktif seçenek aria-pressed + "on" durumu taşır; diğerine tıklayınca onChange değeriyle çağrılır', () => {
    const onChange = vi.fn()
    render(<SegmentedControl value={1} onChange={onChange} options={options} ariaLabel="aralık" />)
    const active = screen.getByRole('button', { name: 'Son 1 gün' })
    expect(active).toHaveAttribute('aria-pressed', 'true')
    // Görsel "etkin" işareti: shadcn Toggle'ın data-state="on" durumu (eski .active sınıfının karşılığı)
    expect(active).toHaveAttribute('data-state', 'on')
    expect(screen.getByRole('button', { name: 'Son 7 gün' })).toHaveAttribute('aria-pressed', 'false')

    fireEvent.click(screen.getByRole('button', { name: 'Son 7 gün' }))
    expect(onChange).toHaveBeenCalledWith(7)   // orijinal (sayı) değer — dizeye çevrilmemiş
  })

  it('zaten aktif seçeneğe tıklamak onChange TETİKLEMEZ (gereksiz yeniden yükleme yok)', () => {
    const onChange = vi.fn()
    render(<SegmentedControl value={1} onChange={onChange} options={options} ariaLabel="aralık" />)
    fireEvent.click(screen.getByRole('button', { name: 'Son 1 gün' }))
    expect(onChange).not.toHaveBeenCalled()
    // Seçim boşaltılamaz: öğe basılı kalır
    expect(screen.getByRole('button', { name: 'Son 1 gün' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('grup adı ariaLabel\'dan gelir; boş-dize değerli seçenek ("Tümü") seçilebilir ve etkin görünür', () => {
    const onChange = vi.fn()
    const opts = [{ value: '', label: 'Tümü' }, { value: 'A', label: 'A' }]
    const { rerender } = render(<SegmentedControl value="A" onChange={onChange} options={opts} ariaLabel="süzgeç" />)
    expect(screen.getByRole('group', { name: 'süzgeç' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Tümü' }))
    expect(onChange).toHaveBeenCalledWith('')

    rerender(<SegmentedControl value="" onChange={onChange} options={opts} ariaLabel="süzgeç" />)
    expect(screen.getByRole('button', { name: 'Tümü' })).toHaveAttribute('aria-pressed', 'true')
  })

  // 2026-10-09: öğe 28 px (h-7) dokunmatikte 40 px hedef kuralının altındaydı; fare görünümü aynı kalır.
  it('dokunmatikte öğe 40 px (pointer-coarse:h-10); fare görünümü h-7; itemClassName yine ekler', () => {
    const { rerender } = render(<SegmentedControl value={1} onChange={() => {}} options={options} ariaLabel="aralık" />)
    for (const b of screen.getAllByRole('button')) expect(b).toHaveClass('h-7', 'pointer-coarse:h-10')
    rerender(<SegmentedControl value={1} onChange={() => {}} options={options} ariaLabel="aralık" itemClassName="h-9 flex-1" />)
    const b = screen.getByRole('button', { name: 'Son 7 gün' })
    expect(b).toHaveClass('h-9', 'flex-1', 'pointer-coarse:h-10')
    expect(b).not.toHaveClass('h-7')
  })
})
