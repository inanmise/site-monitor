import { describe, it, expect } from 'vitest'
import { useState } from 'react'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import InventoryToolbar from '../components/inventory/InventoryToolbar.jsx'
import { EMPTY_FILTERS } from '../components/inventory/inventoryModel.js'

/**
 * Envanter araması gecikmeli uygulanır (250 ms). 2026-10-09 hata düzeltmesi: zamanlayıcı yazma ANINDAKİ süzgeçleri
 * kopyalıyordu → yazarken 250 ms içinde seçilen takım, gecikmeli arama tarafından eski süzgeçle EZİLİYORDU.
 */
function Harness() {
  const [filters, setFilters] = useState({ ...EMPTY_FILTERS })
  return (
    <>
      <InventoryToolbar filters={filters} onFilters={setFilters} shown={0} total={0} cols={[]} onCols={() => {}}
        sort={{}} onSort={() => {}} density="comfortable" onDensity={() => {}} view="table" onView={() => {}} />
      <button type="button" onClick={() => setFilters((f) => ({ ...f, team: '5' }))}>takım seç</button>
      <output data-testid="state">{JSON.stringify({ q: filters.q, team: filters.team })}</output>
    </>
  )
}

describe('InventoryToolbar — gecikmeli arama güncel süzgeçleri korur', () => {
  it('yazarken seçilen takım, 250 ms sonra uygulanan aramayla kaybolmaz', async () => {
    render(<Harness />)
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'abc' } })
    fireEvent.click(screen.getByRole('button', { name: 'takım seç' }))   // arama zamanlayıcısı henüz dolmadı
    await waitFor(() => expect(JSON.parse(screen.getByTestId('state').textContent)).toEqual({ q: 'abc', team: '5' }), { timeout: 2000 })
  })
})
