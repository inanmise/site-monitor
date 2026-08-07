import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import SegmentedControl from '../components/ui/SegmentedControl.jsx'

const options = [
  { value: 1, label: 'Son 1 gün' },
  { value: 7, label: 'Son 7 gün' },
  { value: 'custom', label: 'Özel' },
]

describe('SegmentedControl', () => {
  it('aktif seçenek aria-pressed + .active taşır; diğerine tıklayınca onChange değeriyle çağrılır', () => {
    const onChange = vi.fn()
    render(<SegmentedControl value={1} onChange={onChange} options={options} ariaLabel="aralık" />)
    const active = screen.getByRole('button', { name: 'Son 1 gün' })
    expect(active).toHaveAttribute('aria-pressed', 'true')
    expect(active.className).toContain('active')

    fireEvent.click(screen.getByRole('button', { name: 'Son 7 gün' }))
    expect(onChange).toHaveBeenCalledWith(7)
  })

  it('zaten aktif seçeneğe tıklamak onChange TETİKLEMEZ (gereksiz yeniden yükleme yok)', () => {
    const onChange = vi.fn()
    render(<SegmentedControl value={1} onChange={onChange} options={options} ariaLabel="aralık" />)
    fireEvent.click(screen.getByRole('button', { name: 'Son 1 gün' }))
    expect(onChange).not.toHaveBeenCalled()
  })
})
