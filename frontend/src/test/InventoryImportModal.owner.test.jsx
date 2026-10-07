import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'

/**
 * CSV içe aktarma — mükerrer alan adı (2026-09-28): başka ekibin kaydına çarpan satır artık genel "kapsam dışı"
 * değil `duplicate_other_team`; sonuç tablosu nedeni YAZAR ve kaydın SAHİBİ ekibini rozetle (TeamBadge) gösterir.
 * Sahibi olmayan satırlarda rozet yok. (2026-10-07: silme kalıcı — "çöp kutusunda" nedeni kalktı.)
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({ admin: { importInventory: vi.fn() } }),
}))

import { api } from '../api/client'
import InventoryImportModal from '../components/inventory/InventoryImportModal.jsx'

describe('InventoryImportModal — sahibi ekip', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.importInventory.mockResolvedValue({ success: true, data: {
      dry_run: true, created: 1, updated: 0, skipped: 1, errors: 0, rows: [
        { line: 2, domain: 'new.example.com', action: 'create', reason: null, changes: ['domain', 'team'] },
        { line: 3, domain: 'shop.example.com', action: 'skip', reason: 'duplicate_other_team', changes: [], team_id: 9, team_name: 'Takım B' },
      ] } })
  })

  it('duplicate_other_team: neden etiketi + sahibi takım rozeti; yeni satırda rozet YOK', async () => {
    render(<InventoryImportModal onClose={() => {}} onDone={() => {}} />)
    fireEvent.change(screen.getByRole('textbox', { name: /Paste CSV text/ }), {
      target: { value: 'domain,team\nnew.example.com,5\nshop.example.com,5' } })
    fireEvent.click(screen.getByRole('button', { name: 'Preview (dry run)' }))
    await waitFor(() => expect(api.admin.importInventory).toHaveBeenCalled())

    const row = (domain) => [...document.querySelectorAll('tr[data-action]')].find((tr) => tr.textContent.includes(domain))
    await waitFor(() => expect(row('shop.example.com')).toBeTruthy())
    const dup = row('shop.example.com')
    expect(dup.textContent).toMatch(/registered to another team — no duplicate is created/)
    expect(dup.querySelector('[data-slot="inv-import-owner"] [data-slot="team-badge"]').textContent).toBe('Takım B')
    expect(dup.textContent).not.toContain('inv.importReason')   // ham anahtar ekrana düşmez

    expect(row('new.example.com').querySelector('[data-slot="team-badge"]')).toBeNull()
  })
})
