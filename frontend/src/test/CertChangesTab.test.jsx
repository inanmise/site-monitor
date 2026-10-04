import { render, screen, waitFor, fireEvent } from './test-utils.jsx'
import { describe, it, expect, vi, beforeEach } from 'vitest'

/**
 * Sertifika penceresi → Değişiklikler (2026-10-05, kullanıcı isteği: "sertifika izlemeyi kim ekledi, ne değiştirdi,
 * ne zaman güncellendi — dashboard kartına tıklayınca geçmiş göremiyorum"). Envanter kaydı alan adından bulunur, üstte
 * "Ekleyen / Son güncelleyen" özeti, altında envanter değişiklik günlüğü (ChangeHistoryTab, tür "inventory").
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({
    admin: { getInventoryByDomain: vi.fn() },
    teams: { directory: vi.fn() },
    monitoring: { getChanges: vi.fn(), getChangeDetail: vi.fn() },
  }),
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  formatDate: (s) => s ?? '',
}))
import { api } from '../api/client'
import CertChangesTab from '../components/certmodal/CertChangesTab.jsx'

const t = (k, ...a) => (a.length ? `${k}:${a.join('|')}` : k)
const RECORD = {
  id: 42, domain: 'portal.example.com', team_id: 5,
  created_at: '2026-09-01T08:00:00', created_by: 'U00001', created_by_name: 'Kullanıcı A',
  updated_at: '2026-10-04T12:30:00', updated_by: 'U00002', updated_by_name: 'Kullanıcı B',
}
const change = (seq, type, changes) => ({
  seq, kind: 'INVENTORY', resource_id: 42, resource_name: 'portal.example.com', event_type: type, team_id: 5,
  team_name: 'Takım A', actor: 'U00002', actor_name: 'Kullanıcı B', ip_address: '10.0.0.9', user_agent: 'Mozilla/5.0',
  changes, note: null, at: '2026-10-04T12:30:00',
})

describe('CertChangesTab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.teams.directory.mockResolvedValue({ success: true, data: [{ id: 5, name: 'Takım A' }, { id: 7, name: 'Takım B' }] })
  })

  it('envanter kaydı bulunur: Ekleyen / Son güncelleyen özeti + değişiklik günlüğü "inventory" türüyle kayıt kimliğinden', async () => {
    api.admin.getInventoryByDomain.mockResolvedValue({ success: true, data: RECORD })
    api.monitoring.getChanges.mockResolvedValue({ success: true, data: { changes: [
      change(2, 'UPDATE', [{ field: 'teamId', before: 7, after: 5 }]),
      change(1, 'CREATE', null),
    ], total: 2, page: 0, size: 25 } })
    const { container } = render(<CertChangesTab t={t} domain="portal.example.com" />)

    await waitFor(() => expect(container.querySelector('[data-slot="cert-changes-meta"]')).not.toBeNull())
    expect(api.admin.getInventoryByDomain).toHaveBeenCalledWith('portal.example.com')
    const created = container.querySelector('[data-slot="cert-changes-created"]')
    const updated = container.querySelector('[data-slot="cert-changes-updated"]')
    expect(created.textContent).toContain('certchg.created')
    expect(created.textContent).toContain('Kullanıcı A')
    expect(created.textContent).toContain('2026-09-01T08:00:00')
    expect(updated.textContent).toContain('Kullanıcı B')
    await waitFor(() => expect(api.monitoring.getChanges).toHaveBeenCalledWith('inventory', 42, expect.anything()))
    // Günlük satırları çizilir (aktör adı görünür)
    expect((await screen.findAllByText(/Kullanıcı B/)).length).toBeGreaterThan(1)
  })

  it('kaydı olmayan alan adı: açıklamalı boş durum, günlük istenmez', async () => {
    api.admin.getInventoryByDomain.mockResolvedValue({ success: true, data: null })
    render(<CertChangesTab t={t} domain="adhoc.example.com" />)
    expect(await screen.findByText('certchg.missingTitle')).toBeInTheDocument()
    expect(screen.getByText('certchg.missingBody')).toBeInTheDocument()
    expect(api.monitoring.getChanges).not.toHaveBeenCalled()
  })

  it('hata: "Yeniden dene" tekrar ister; ekleyen bilgisi yoksa "—"', async () => {
    api.admin.getInventoryByDomain.mockResolvedValueOnce({ success: false, error: 'boom' })
      .mockResolvedValueOnce({ success: true, data: { id: 9, domain: 'eski.example.com' } })
    api.monitoring.getChanges.mockResolvedValue({ success: true, data: { changes: [], total: 0, page: 0, size: 25 } })
    const { container } = render(<CertChangesTab t={t} domain="eski.example.com" />)
    fireEvent.click(await screen.findByRole('button', { name: 'certchg.retry' }))
    await waitFor(() => expect(container.querySelector('[data-slot="cert-changes-meta"]')).not.toBeNull())
    expect(api.admin.getInventoryByDomain).toHaveBeenCalledTimes(2)
    expect(container.querySelector('[data-slot="cert-changes-created"]').textContent).toContain('—')
  })
})
