import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import MultiTeamSelect from '../components/ui/MultiTeamSelect.jsx'

const teams = [
  { value: 1, label: 'SY-Alpha' },
  { value: 2, label: 'SY-Beta' },
  { value: 3, label: 'SY-Gamma' },
  { value: 4, label: 'SY-Delta' },
]
// Tetik role="combobox" (shadcn Combobox deseni); açılış onMouseDown ile
function open() { fireEvent.mouseDown(screen.getByRole('combobox')) }

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

  it('liste seçimde AÇIK kalır; basış + ardından gelen tık seçimi BİR kez çevirir', () => {
    const onChange = vi.fn()
    render(<MultiTeamSelect value={[1]} onChange={onChange} options={teams} />)
    open()
    const opt = screen.getByRole('option', { name: 'SY-Beta' })
    fireEvent.mouseDown(opt)
    fireEvent.click(opt)
    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange).toHaveBeenCalledWith([1, 2])
    expect(screen.getByRole('listbox')).toBeDefined()
  })

  it('klavye: ok tuşu + Enter vurgulanan seçeneği çevirir; seçili olan işaretli görünür', () => {
    const onChange = vi.fn()
    render(<MultiTeamSelect value={[1]} onChange={onChange} options={teams} />)
    open()
    expect(screen.getByRole('option', { name: 'SY-Alpha' })).toHaveAttribute('data-checked', 'true')
    const search = screen.getByPlaceholderText(/search/i)
    fireEvent.keyDown(search, { key: 'ArrowDown' })
    fireEvent.keyDown(search, { key: 'Enter' })
    expect(onChange).toHaveBeenCalledWith([1, 2])
  })

  /**
   * 2026-09-25 (R17): seçili durum DUYURULUR. Kutu aria-hidden (satırın kendisi seçenek) ve cmdk'nin
   * aria-selected'ı klavye vurgusu demek — seçimi söyleyen tek şey aria-checked. Eskiden yalnız
   * görsel data-checked vardı: ekran okuyucu hangi takımların seçili olduğunu duymuyordu.
   */
  it('seçenekler seçili durumu aria-checked ile duyurur (seçili true, diğerleri false)', () => {
    render(<MultiTeamSelect value={[1, 3]} onChange={() => {}} options={teams} ariaLabel="Takımlar" />)
    open()
    expect(screen.getByRole('option', { name: 'SY-Alpha' })).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByRole('option', { name: 'SY-Gamma' })).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByRole('option', { name: 'SY-Beta' })).toHaveAttribute('aria-checked', 'false')
  })

  it('tetik (role="combobox") adını ariaLabel\'dan ya da bağlı etiketten alır', () => {
    const { unmount } = render(<MultiTeamSelect value={[1]} onChange={() => {}} options={teams} ariaLabel="Takımlar" />)
    // İçerik ("SY-Alpha") ad DEĞİL — combobox adını içerikten almaz.
    expect(screen.getByRole('combobox', { name: 'Takımlar' })).toBeDefined()
    unmount()
    render(<><label htmlFor="mts-x">Üye olduğu takımlar</label>
      <MultiTeamSelect id="mts-x" value={[]} onChange={() => {}} options={teams} /></>)
    expect(screen.getByRole('combobox', { name: 'Üye olduğu takımlar' })).toBeDefined()
  })
})
