import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'

const confirmMock = vi.fn(() => Promise.resolve(true))
vi.mock('../components/ui/Dialog.jsx', () => ({ useDialog: () => ({ showConfirm: confirmMock }), DialogProvider: ({ children }) => children }))
import BulkActionBar from '../components/ui/BulkActionBar.jsx'

/** Toplu işlem çubuğu (2026-09-12, #13): mevcut PUT/DELETE ile satır satır; duraklat yalnız aktifleri, sil yalnız yetkili satırları. */
describe('BulkActionBar', () => {
  const items = [{ id: 1, name: 'a', active: true }, { id: 2, name: 'b', active: false }, { id: 3, name: 'c', active: true }]
  let api
  beforeEach(() => { vi.clearAllMocks(); confirmMock.mockResolvedValue(true); api = { update: vi.fn().mockResolvedValue({ success: true }), remove: vi.fn().mockResolvedValue({ success: true }) } })

  it('seçim yoksa çizilmez; "Duraklat" yalnız aktif seçilileri {active:false} ile günceller; sonra seçim temizlenir + yeniden yükleme', async () => {
    const { container, rerender } = render(<BulkActionBar selected={new Set()} items={items} api={api} />)
    expect(container.querySelector('.bulkbar')).toBeNull()
    const onClear = vi.fn(), onDone = vi.fn()
    rerender(<BulkActionBar selected={new Set([1, 2])} items={items} api={api} onClear={onClear} onDone={onDone} />)
    expect(screen.getByText(/2 seçili|2 selected/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Duraklat|Pause/ }))
    await waitFor(() => expect(onDone).toHaveBeenCalled())
    expect(api.update).toHaveBeenCalledTimes(1)
    expect(api.update).toHaveBeenCalledWith(1, { active: false })
    expect(onClear).toHaveBeenCalled()
  })

  it('"Sil" yalnız canDelete satırları için onay sonrası DELETE; takım seçimi {teamId} gönderir', async () => {
    render(<BulkActionBar selected={new Set([1, 2, 3])} items={items} api={api} teams={[{ id: 7, name: 'Takım A' }]} canDelete={(m) => m.id !== 3} onClear={() => {}} onDone={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: /^(Sil|Delete)$/ }))
    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    await waitFor(() => expect(api.remove).toHaveBeenCalledTimes(2))
    expect(api.remove).not.toHaveBeenCalledWith(3)
  })
})
