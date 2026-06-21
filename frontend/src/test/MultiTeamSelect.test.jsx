import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import MultiTeamSelect from '../components/ui/MultiTeamSelect.jsx'

const teams = [
  { value: 1, label: 'SY-Alpha' },
  { value: 2, label: 'SY-Beta' },
  { value: 3, label: 'SY-Gamma' },
  { value: 4, label: 'SY-Delta' },
]
function open() { fireEvent.mouseDown(screen.getByRole('button')) }

describe('MultiTeamSelect', () => {
  it('boşken placeholder gösterir', () => {
    render(<MultiTeamSelect value={[]} onChange={() => {}} options={teams} placeholder="Takım seçin" />)
    expect(screen.getByText('Takım seçin')).toBeDefined()
  })

  it('seçili id\'lerin etiketlerini virgülle tetikleyicide gösterir', () => {
    render(<MultiTeamSelect value={[1, 3]} onChange={() => {}} options={teams} />)
    expect(screen.getByText('SY-Alpha, SY-Gamma')).toBeDefined()
  })

  it('bir seçeneğe tıklayınca id\'yi mevcut seçime EKLER (kapanmaz)', () => {
    const onChange = vi.fn()
    render(<MultiTeamSelect value={[1]} onChange={onChange} options={teams} />)
    open()
    fireEvent.mouseDown(screen.getByText('SY-Beta'))
    expect(onChange).toHaveBeenCalledWith([1, 2])
  })

  it('zaten seçili seçeneğe tıklayınca id\'yi ÇIKARIR', () => {
    const onChange = vi.fn()
    render(<MultiTeamSelect value={[1, 2]} onChange={onChange} options={teams} />)
    open()
    fireEvent.mouseDown(screen.getByText('SY-Alpha'))
    expect(onChange).toHaveBeenCalledWith([2])
  })

  it('searchThreshold={2}: 4 seçenekte arama kutusu görünür ve filtreler', () => {
    render(<MultiTeamSelect value={[]} onChange={() => {}} options={teams} searchThreshold={2} />)
    open()
    fireEvent.change(screen.getByPlaceholderText(/search/i), { target: { value: 'beta' } })
    expect(screen.getByText('SY-Beta')).toBeDefined()
    expect(screen.queryByText('SY-Gamma')).toBeNull()
  })
})
