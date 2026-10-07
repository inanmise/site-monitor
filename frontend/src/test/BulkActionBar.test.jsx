import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'

const confirmMock = vi.fn(() => Promise.resolve(true))
vi.mock('../components/ui/Dialog.jsx', () => ({ useDialog: () => ({ showConfirm: confirmMock }), DialogProvider: ({ children }) => children }))
import BulkActionBar from '../components/ui/BulkActionBar.jsx'
import { __resetDeletedMarks, isRecentlyDeleted, monitorKind } from '../utils/recentlyDeleted.js'

/** Toplu işlem çubuğu (2026-09-12, #13): mevcut PUT/DELETE ile satır satır; duraklat yalnız aktifleri, sil yalnız yetkili satırları. */
describe('BulkActionBar', () => {
  const items = [{ id: 1, name: 'a', active: true }, { id: 2, name: 'b', active: false }, { id: 3, name: 'c', active: true }]
  let api
  beforeEach(() => { vi.clearAllMocks(); confirmMock.mockResolvedValue(true); api = { update: vi.fn().mockResolvedValue({ success: true }), remove: vi.fn().mockResolvedValue({ success: true }) } })

  it('seçim yoksa çizilmez; "Duraklat" yalnız aktif seçilileri {active:false} ile günceller; sonra seçim temizlenir + yeniden yükleme', async () => {
    const { rerender } = render(<BulkActionBar selected={new Set()} items={items} api={api} />)
    expect(screen.queryByRole('region', { name: /^(Toplu işlem|Bulk actions)$/ })).toBeNull()
    const onClear = vi.fn(), onDone = vi.fn()
    rerender(<BulkActionBar selected={new Set([1, 2])} items={items} api={api} onClear={onClear} onDone={onDone} />)
    expect(screen.getByRole('region', { name: /^(Toplu işlem|Bulk actions)$/ })).toBeInTheDocument()
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

  it('KALICI silme (2026-10-07): onay adları sayar + danger; başarılı silinenler ANINDA işaretlenir, başarısız olan işaretlenmez', async () => {
    __resetDeletedMarks()
    api.remove.mockImplementation(async (id) => (id === 2 ? { success: false } : { success: true, data: { permanent: true } }))
    render(<BulkActionBar selected={new Set([1, 2, 3])} items={items} api={api} canDelete={() => true} nocType="HTTP" onClear={() => {}} onDone={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: /^(Sil|Delete)$/ }))
    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    const arg = confirmMock.mock.calls[0][0]
    expect(arg.variant).toBe('danger')
    expect(arg.message).toMatch(/3/)
    expect(arg.message).toContain('a, b, c')
    expect(arg.confirmText).toMatch(/Kalıcı olarak sil|Delete permanently/)
    await waitFor(() => expect(api.remove).toHaveBeenCalledTimes(3))
    await waitFor(() => expect(isRecentlyDeleted(monitorKind('HTTP'), 3)).toBe(true))
    expect(isRecentlyDeleted(monitorKind('HTTP'), 1)).toBe(true)
    expect(isRecentlyDeleted(monitorKind('HTTP'), 2)).toBe(false)
  })

  it('Port/DNS: envanter türevi satır seçiliyse onay "duraklatılır" notunu ekler; türev yanıtı (permanent:false) işaretlenmez', async () => {
    __resetDeletedMarks()
    const ports = [{ id: 1, host: 'a.example.com', standalone: true }, { id: 2, host: 'b.example.com', standalone: false }]
    api.remove.mockImplementation(async (id) => ({ success: true, data: { deleted: true, permanent: id === 1 } }))
    render(<BulkActionBar selected={new Set([1, 2])} items={ports} api={api} canDelete={() => true} nocType="PORT" onClear={() => {}} onDone={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: /^(Sil|Delete)$/ }))
    await waitFor(() => expect(confirmMock).toHaveBeenCalled())
    expect(confirmMock.mock.calls[0][0].message).toMatch(/duraklatılır|paused/)
    await waitFor(() => expect(isRecentlyDeleted(monitorKind('PORT'), 1)).toBe(true))
    expect(isRecentlyDeleted(monitorKind('PORT'), 2)).toBe(false)
  })
})
