import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import MaintenanceTargetPicker from '../components/monitoring/MaintenanceTargetPicker.jsx'

/**
 * Bakım hedef seçici (2026-09-17): önce izleme türü, sonra o türün monitörleri.
 * Değer sözleşmesi değişmedi (string[]), "türün tamamı" tek tıkla seçilir/kaldırılır.
 */
const OPTIONS = [
  { value: 'a.example.com', type: 'http', name: 'A' },
  { value: 'b.example.com', type: 'http', name: 'B' },
  { value: '10.0.0.1:443', type: 'port', name: 'Port A' },
  { value: 'dns.example.com', type: 'dns', name: 'DNS A' },
]
const typeLabel = (ty) => ({ http: 'HTTP', port: 'Port', dns: 'DNS' }[ty] || ty)
const setup = (value = [], onChange = vi.fn()) => {
  render(<MaintenanceTargetPicker options={OPTIONS} value={value} onChange={onChange} typeLabel={typeLabel} />)
  return onChange
}

describe('MaintenanceTargetPicker', () => {
  it('türler sayılarıyla listelenir; ilk tür seçili gelir ve YALNIZ onun monitörleri çizilir', () => {
    setup()
    const types = [...document.querySelectorAll('.mtp-type')].map((b) => b.textContent.replace(/\s+/g, ' ').trim())
    expect(types).toEqual(['DNS1', 'HTTP2', 'Port1'])   // alfabetik
    expect(document.querySelector('.mtp-type.is-active').textContent).toMatch(/DNS/)
    const items = [...document.querySelectorAll('.mtp-item-name')].map((x) => x.textContent)
    expect(items).toEqual(['DNS A'])
  })

  it('tür değişince liste o türe döner; arama o tür içinde süzer', () => {
    setup()
    fireEvent.click([...document.querySelectorAll('.mtp-type')].find((b) => /HTTP/.test(b.textContent)))
    expect([...document.querySelectorAll('.mtp-item-name')].map((x) => x.textContent)).toEqual(['A', 'B'])
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'b' } })
    expect([...document.querySelectorAll('.mtp-item-name')].map((x) => x.textContent)).toEqual(['B'])
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'yok' } })
    expect(screen.getByText(/Bu türde eşleşen monitör yok|No monitor in this type matches/)).toBeInTheDocument()
  })

  it('"türün tamamını seç" o türün HEPSİNİ ekler, ikinci tık kaldırır; diğer türün seçimi korunur', () => {
    const onChange = vi.fn()
    const { rerender } = render(
      <MaintenanceTargetPicker options={OPTIONS} value={['dns.example.com']} onChange={onChange} typeLabel={typeLabel} />)
    fireEvent.click([...document.querySelectorAll('.mtp-type')].find((b) => /HTTP/.test(b.textContent)))
    fireEvent.click(screen.getByRole('button', { name: /HTTP türünün tamamını seç|Select every HTTP monitor/ }))
    expect(onChange).toHaveBeenCalledWith(['dns.example.com', 'a.example.com', 'b.example.com'])

    rerender(<MaintenanceTargetPicker options={OPTIONS} value={['dns.example.com', 'a.example.com', 'b.example.com']}
      onChange={onChange} typeLabel={typeLabel} />)
    fireEvent.click([...document.querySelectorAll('.mtp-type')].find((b) => /HTTP/.test(b.textContent)))
    fireEvent.click(screen.getByRole('button', { name: /HTTP türünün seçimini kaldır|Clear the HTTP selection/ }))
    expect(onChange).toHaveBeenLastCalledWith(['dns.example.com'])   // DNS seçimi korunur
  })

  it('seçili çipler türüyle listelenir, çipten kaldırılır; sayaç türde "seçili/toplam" gösterir', () => {
    const onChange = setup(['a.example.com', 'dns.example.com'])
    expect(document.querySelector('.mtp-selected-label').textContent).toMatch(/2/)
    const chips = [...document.querySelectorAll('.mtp-chip')].map((c) => c.textContent.replace(/\s+/g, ' ').trim())
    expect(chips.some((c) => /HTTP A/.test(c))).toBe(true)
    expect([...document.querySelectorAll('.mtp-type')].map((b) => b.textContent.replace(/\s+/g, ' ').trim()))
      .toEqual(['DNS1/1', 'HTTP1/2', 'Port1'])
    fireEvent.click(document.querySelectorAll('.mtp-chip-x')[0])
    expect(onChange).toHaveBeenCalled()
    expect(onChange.mock.calls[0][0]).toHaveLength(1)
  })

  it('monitör yoksa açıklayıcı metin, seçim yoksa yönlendirme metni çıkar', () => {
    const { rerender } = render(<MaintenanceTargetPicker options={[]} value={[]} onChange={() => {}} typeLabel={typeLabel} />)
    expect(screen.getByText(/Seçilebilecek monitör bulunamadı|No monitors available to pick/)).toBeInTheDocument()
    rerender(<MaintenanceTargetPicker options={OPTIONS} value={[]} onChange={() => {}} typeLabel={typeLabel} />)
    expect(screen.getByText(/Henüz monitör seçilmedi|No monitors picked yet/)).toBeInTheDocument()
  })
})
