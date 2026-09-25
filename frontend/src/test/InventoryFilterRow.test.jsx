import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, act } from './test-utils.jsx'
import InventoryFilterRow from '../components/inventory/InventoryFilterRow.jsx'
import { EMPTY_FILTERS, defaultCols } from '../components/inventory/inventoryModel.js'

/** Kolon süzgeç satırı (2026-09-22): hücre sayısı başlıkla aynı; domain metni gecikmeli; combobox seçimi filtreye yazar. */
describe('InventoryFilterRow', () => {
  const rows = [
    { id: 1, domain: 'a.example.com', port: 443, team_id: 5, team_name: 'Takım A', tags: 'pci' },
    { id: 2, domain: 'b.example.com', port: 8443, team_id: 9, team_name: 'Takım B' },
  ]
  const renderRow = (props = {}) => {
    const onFilters = vi.fn()
    const cols = defaultCols()
    render(<table><thead><InventoryFilterRow filters={EMPTY_FILTERS} onFilters={onFilters} allRows={rows} cols={cols} canManage statusFilter="default" {...props} /></thead></table>)
    return { onFilters, cols }
  }

  it('hücre sayısı = seçim kutusu + görünen kolonlar + işlem; domain metni 250 ms sonra filtreye yazılır', async () => {
    vi.useFakeTimers()
    try {
      const { onFilters, cols } = renderRow()
      const row = screen.getByTestId('inv-filter-row')
      expect(row.children).toHaveLength(1 + cols.length + 1)
      const input = screen.getByPlaceholderText(/Domain ara|Search domain/)
      fireEvent.change(input, { target: { value: 'akb' } })
      expect(onFilters).not.toHaveBeenCalled()
      await act(async () => { vi.advanceTimersByTime(300) })
      expect(onFilters).toHaveBeenCalledWith({ ...EMPTY_FILTERS, domain: 'akb' })
    } finally { vi.useRealTimers() }
  })

  it('port combobox seçenekleri satırlardan türer; seçim filtreye yazar; takım seçeneği ad ile', () => {
    const { onFilters } = renderRow()
    const triggers = screen.getAllByText(/^Hepsi$|^Any$/)
    fireEvent.mouseDown(triggers[0].closest('button[role="combobox"]'))   // port
    fireEvent.mouseDown(screen.getByText('8443'))
    expect(onFilters).toHaveBeenCalledWith({ ...EMPTY_FILTERS, port: '8443' })
    fireEvent.mouseDown(screen.getAllByText(/^Hepsi$|^Any$/)[2].closest('button[role="combobox"]'))   // takım
    expect(screen.getByText('Takım B')).toBeInTheDocument()
  })

  it('silinmişler görünümünde Aktif kolonu süzgeçsiz; canManage yokken seçim hücresi yok', () => {
    const cols = defaultCols()
    render(<table><thead><InventoryFilterRow filters={EMPTY_FILTERS} onFilters={() => {}} allRows={rows} cols={cols} canManage={false} statusFilter="deleted" /></thead></table>)
    const row = screen.getByTestId('inv-filter-row')
    expect(row.children).toHaveLength(cols.length + 1)
    expect(row.lastElementChild.previousElementSibling.querySelector('button[role="combobox"]')).toBeNull()
  })
})
