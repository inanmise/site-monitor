import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import TeamManager from '../components/admin/TeamManager.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({
    admin: {
      getTeams:     vi.fn(),
      getUsers:     vi.fn(),
      getTeamUsers: vi.fn(),
      createTeam:   vi.fn(),
      updateTeam:   vi.fn(),
      updateTeamWeeklyNotifications: vi.fn(),
    },
  }),
}))

import { api } from '../api/client'

/**
 * Takım sessiz saati (2026-10-01, onaylı öneri 15): düzenleme penceresinde opt-in alanlar; dokunulmamış form BUGÜNKÜ
 * gövdeyi yollar (quiet_* anahtarı yok); hatalı pencere alanın altında hata verir ve kayıt GİTMEZ; geçerli pencere
 * normalize gövdeyle gider; kaldırma boş alanlar yollar.
 */
describe('TeamManager — sessiz saatler', () => {
  const plain = { id: 1, name: 'Payments', active: true, leader_id: 7, email: 't@ex.com' }
  const quiet = { id: 2, name: 'Nightly', active: true, leader_id: 7, email: 'n@ex.com',
    quiet_start: '22:00', quiet_end: '07:00', quiet_days: 'MON,TUE,WED,THU,FRI', quiet_min_level: 'CRITICAL' }

  beforeEach(() => {
    vi.clearAllMocks()
    api.admin.getTeams.mockResolvedValue({ success: true, data: [plain, quiet] })
    api.admin.getUsers.mockResolvedValue({ success: true, data: [] })
    api.admin.getTeamUsers.mockResolvedValue({ success: true, data: [] })
    api.admin.updateTeam.mockResolvedValue({ success: true, data: {} })
  })

  async function openEdit(name) {
    render(<TeamManager systemRole="ADMIN" ownTeamId={1} myTeamIds={[1]} onTeamsChange={() => {}} />)
    await waitFor(() => expect(screen.getByText(name)).toBeDefined())
    pressMenuTrigger(screen.getByRole('button', { name: `${name} — Actions` }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Edit' }))
    const dlg = await screen.findByRole('dialog')
    return { dlg, section: within(dlg.querySelector('[data-testid="tm-quiet-form"]')) }
  }

  const save = (dlg) => fireEvent.click(within(dlg).getByRole('button', { name: 'Save' }))
  /** Aynı çizimde ikinci kez düzenleme penceresi aç (render yok). */
  async function openEdit2(name) {
    pressMenuTrigger(screen.getByRole('button', { name: `${name} — Actions` }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Edit' }))
    return screen.findByRole('dialog')
  }

  // 20.166 (UIA-A11): düzenleme YALNIZ değişen alanları yollar — dokunulmamış form hiç istek atmaz (eskiden tam gövde
  // gidiyor, pencere açıkken satırdan çevrilen haftalık anahtarı eski değere döndürüyordu).
  it('dokunulmamış form: istek YOK, pencere kapanır; tek alan değişince gövde yalnız o alan (quiet_* yok)', async () => {
    const { dlg } = await openEdit('Payments')
    save(dlg)
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(api.admin.updateTeam).not.toHaveBeenCalled()

    const again = await openEdit2('Payments')
    fireEvent.change(within(again).getByLabelText('Description'), { target: { value: 'Ödeme ekibi' } })
    save(again)
    await waitFor(() => expect(api.admin.updateTeam).toHaveBeenCalledTimes(1))
    expect(api.admin.updateTeam.mock.calls[0][1]).toEqual({ description: 'Ödeme ekibi' })
  })

  it('kayıtlı pencere alanlara yüklenir; başka alan değişince gövdeye quiet_* EKLENMEZ', async () => {
    const { dlg, section } = await openEdit('Nightly')
    expect(section.getByLabelText('Start')).toHaveValue('22:00')
    expect(section.getByLabelText('End')).toHaveValue('07:00')
    expect(section.getByRole('button', { name: 'Saturday' })).toHaveAttribute('data-state', 'off')
    expect(section.getByRole('button', { name: 'Monday' })).toHaveAttribute('data-state', 'on')
    expect(section.getByRole('button', { name: 'CRITICAL only' })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.change(within(dlg).getByLabelText('Description'), { target: { value: 'Gece' } })
    save(dlg)
    await waitFor(() => expect(api.admin.updateTeam).toHaveBeenCalledTimes(1))
    expect(api.admin.updateTeam.mock.calls[0][1]).not.toHaveProperty('quiet_start')
    expect(api.admin.updateTeam.mock.calls[0][1]).not.toHaveProperty('weekly_reminder_enabled')
  })

  it('yalnız başlangıç girildi: hata BİTİŞ alanının altında, kayıt gitmez; düzeltilince normalize gövde gider', async () => {
    const { dlg, section } = await openEdit('Payments')
    fireEvent.change(section.getByLabelText('Start'), { target: { value: '22:00' } })
    save(dlg)
    await waitFor(() => expect(dlg.querySelector('[data-field="quiet_end"]')).toHaveAttribute('data-invalid', 'true'))
    expect(within(dlg.querySelector('[data-field="quiet_end"]')).getByText('Enter both a start and an end time.')).toBeInTheDocument()
    expect(api.admin.updateTeam).not.toHaveBeenCalled()

    fireEvent.change(section.getByLabelText('End'), { target: { value: '07:00' } })
    fireEvent.click(section.getByRole('button', { name: 'Sunday' }))   // Pazar'ı çıkar
    save(dlg)
    await waitFor(() => expect(api.admin.updateTeam).toHaveBeenCalledTimes(1))
    expect(api.admin.updateTeam.mock.calls[0][1]).toMatchObject({
      quiet_start: '22:00', quiet_end: '07:00', quiet_days: ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'], quiet_min_level: '',
    })
  })

  it('başlangıç = bitiş ve hiç gün seçilmemesi alan hatası verir', async () => {
    const { dlg, section } = await openEdit('Payments')
    fireEvent.change(section.getByLabelText('Start'), { target: { value: '10:00' } })
    fireEvent.change(section.getByLabelText('End'), { target: { value: '10:00' } })
    for (const d of ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']) {
      fireEvent.click(section.getByRole('button', { name: d }))
    }
    save(dlg)
    await waitFor(() => expect(within(dlg).getByText('Start and end can’t be the same.')).toBeInTheDocument())
    expect(within(dlg).getByText('Pick at least one day.')).toBeInTheDocument()
    expect(api.admin.updateTeam).not.toHaveBeenCalled()
  })

  it('"Sessiz saati kaldır" → boş alanlar gönderilir (sunucu ayarı siler)', async () => {
    const { dlg, section } = await openEdit('Nightly')
    fireEvent.click(section.getByRole('button', { name: 'Remove quiet hours' }))
    save(dlg)
    await waitFor(() => expect(api.admin.updateTeam).toHaveBeenCalledTimes(1))
    expect(api.admin.updateTeam.mock.calls[0][1]).toMatchObject({ quiet_start: '', quiet_end: '', quiet_days: [], quiet_min_level: '' })
  })
})
