import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import SearchableSelect from '../components/ui/SearchableSelect.jsx'

function opts(n) {
  return Array.from({ length: n }, (_, i) => ({ value: String(i), label: `Option ${i}` }))
}
// Trigger açma — kapalıyken tek buton trigger'dır (onMouseDown ile toggle)
function open() {
  fireEvent.mouseDown(screen.getByRole('button'))
}

describe('SearchableSelect', () => {
  it('varsayılan eşik 4: 4 seçenekte arama kutusu görünür', () => {
    render(<SearchableSelect value="" onChange={() => {}} options={opts(4)} />)
    open()
    expect(screen.getByPlaceholderText(/search/i)).toBeDefined()
  })

  it('varsayılan eşik altında (3 seçenek) arama kutusu gizli', () => {
    render(<SearchableSelect value="" onChange={() => {}} options={opts(3)} />)
    open()
    expect(screen.queryByPlaceholderText(/search/i)).toBeNull()
  })

  it('searchThreshold={2}: büyüyen veri dropdown\'u 2 seçenekte bile aranabilir', () => {
    render(<SearchableSelect value="" onChange={() => {}} options={opts(2)} searchThreshold={2} />)
    open()
    expect(screen.getByPlaceholderText(/search/i)).toBeDefined()
  })

  it('yazılan sorguya göre label\'a göre filtreler', () => {
    render(<SearchableSelect value="" onChange={() => {}} options={[
      { value: 'a', label: 'Akbank' }, { value: 'b', label: 'Garanti' },
      { value: 'c', label: 'Yapi Kredi' }, { value: 'd', label: 'Ziraat' },
    ]} />)
    open()
    fireEvent.change(screen.getByPlaceholderText(/search/i), { target: { value: 'akb' } })
    expect(screen.getByText('Akbank')).toBeDefined()
    expect(screen.queryByText('Garanti')).toBeNull()
  })

  it('seçim onChange ile değeri döndürür', () => {
    const onChange = vi.fn()
    render(<SearchableSelect value="" onChange={onChange} options={opts(4)} />)
    open()
    fireEvent.mouseDown(screen.getByText('Option 2'))
    expect(onChange).toHaveBeenCalledWith('2')
  })

  it('sayısal label (örn. yıl) ile aramada patlamaz', () => {
    render(<SearchableSelect value="" onChange={() => {}} options={[
      { value: 2023, label: 2023 }, { value: 2024, label: 2024 },
      { value: 2025, label: 2025 }, { value: 2026, label: 2026 },
    ]} />)
    open()
    fireEvent.change(screen.getByPlaceholderText(/search/i), { target: { value: '2025' } })
    expect(screen.getByText('2025')).toBeDefined()
    expect(screen.queryByText('2023')).toBeNull()
  })
})
