import { describe, it, expect, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen, within } from './test-utils.jsx'

vi.mock('../api/client', () => ({
  api: { systemMaintenance: { endNow: vi.fn(), extend: vi.fn() } },
}))
import { api } from '../api/client'
import SystemMaintenanceLayer from '../components/maintenance/SystemMaintenanceLayer.jsx'

/** UIA-H11 (2026-10-22): "Hemen bitir" ağda düşerse ileti gösterilir — eskiden yakalanmamış söz reddi, sessiz kalıyordu. */
describe('SystemMaintenanceLayer — Hemen bitir ağ hatası', () => {
  beforeEach(() => { localStorage.clear(); vi.clearAllMocks() })

  it('istek reddedilirse hata bildirimi çıkar (ApiError metni)', async () => {
    api.systemMaintenance.endNow.mockRejectedValue(new Error('Sunucuya ulaşılamadı. Bağlantınızı kontrol edin.'))
    const active = { state: 'active', id: 7, revision: 1, warn_minutes: 10, announce_hours: 24,
      start_at: new Date(Date.now() - 60_000).toISOString(), end_at: new Date(Date.now() + 3_600_000).toISOString() }
    render(<SystemMaintenanceLayer globalAdmin block={active} offset={0} onExpire={vi.fn()} onChanged={vi.fn()} onOpenSettings={vi.fn()} />)
    fireEvent.click(document.querySelector('[data-slot="maint-end-now"]'))
    const confirm = await screen.findByRole('dialog', { name: /End maintenance now|Bakım şimdi bitirilsin/ })
    fireEvent.click(within(confirm).getByRole('button', { name: /^(End now|Hemen bitir)$/ }))
    expect(await screen.findByText('Sunucuya ulaşılamadı. Bağlantınızı kontrol edin.')).toBeInTheDocument()
  })
})
