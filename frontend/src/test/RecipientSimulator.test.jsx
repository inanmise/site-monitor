import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import RecipientSimulator from '../components/admin/RecipientSimulator.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    admin: { simulateRecipients: vi.fn() },
    notificationGroups: { list: vi.fn().mockResolvedValue({ success: true, data: [{ id: 3, name: 'Ops Grubu' }] }) },
  }),
}))
import { api } from '../api/client'

const TEAMS = [{ id: 7, name: 'Takım A' }, { id: 9, name: 'Takım B' }]
const RESULT = {
  team_id: 7, team_name: 'Takım A', level: 'HIGH', managers_included: true, contacts_fallback_global: false, email_total: 3,
  team_emails: [{ email: 'takim-a@example.com', team: 'Takım A', source: 'Takım maili', kind: 'TEAM' }],
  contacts: [
    { id: 1, name: 'Ali PO', email: 'po@example.com', role: 'PO', min_level: 'WARNING', email_duplicate: false },
    { id: 2, name: 'Veli', email: 'takim-a@example.com', role: 'TECH', min_level: 'WARNING', email_duplicate: true },
  ],
  webhooks: [{ id: 1, name: 'Ali PO', type: 'SLACK', target: 'hooks.example.com/…abc123' }],
  push: [{ username: 'ali', display_name: 'Ali PO', group: 'PO', decision: 'RECIPIENT' }, { username: 'veli', display_name: 'Veli', decision: 'NO_GROUP' }],
}

/** "Kim bilgilendirilir?" (2026-09-20): kapalı başlar; takım+seviye+tür sunucuya gider; zincir üç blokta görünür. */
describe('RecipientSimulator', () => {
  beforeEach(() => { vi.clearAllMocks(); api.admin.simulateRecipients.mockResolvedValue({ success: true, data: RESULT }) })

  it('kapalı başlar; açılıp takım seçilince HIGH/CERT ile sorgular ve zinciri gösterir', async () => {
    render(<RecipientSimulator teams={TEAMS} isAdmin />)
    expect(api.admin.simulateRecipients).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: /Simülatörü aç|Open simulator/i }))
    // Takım seç (SearchableSelect: mousedown sözleşmesi)
    fireEvent.mouseDown(screen.getByLabelText(/^Takım$|^Team$/))
    fireEvent.mouseDown(await screen.findByText('Takım A'))
    await waitFor(() => expect(api.admin.simulateRecipients).toHaveBeenCalledWith({ teamId: 7, level: 'HIGH', kind: 'CERT', groupId: null }))
    expect((await screen.findAllByText('takim-a@example.com')).length).toBe(2)   // takım adresi + aynı adresli kişi (dedupe işaretli)
    expect(screen.getByText('po@example.com')).toBeInTheDocument()
    expect(screen.getByText(/takım adresiyle aynı|same as the team address/)).toBeInTheDocument()   // dedupe açıklaması
    expect(screen.getByText(/hooks\.example\.com/)).toBeInTheDocument()                          // maskeli hedef
    expect(screen.getByText(/3 e-posta|3 email/)).toBeInTheDocument()
    expect(screen.getByText(/1\/2 push/)).toBeInTheDocument()                                    // admin push ayağı
  })

  it('seviye değişince yeniden sorgular; UYARI seviyesinde "yalnız takım" notu', async () => {
    api.admin.simulateRecipients.mockResolvedValue({ success: true, data: { ...RESULT, managers_included: false, contacts: [], webhooks: [] } })
    render(<RecipientSimulator teams={TEAMS} isAdmin={false} defaultTeamId={7} />)
    fireEvent.click(screen.getByRole('button', { name: /Simülatörü aç|Open simulator/i }))
    await waitFor(() => expect(api.admin.simulateRecipients).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /^UYARI$|^WARNING$/ }))
    await waitFor(() => expect(api.admin.simulateRecipients).toHaveBeenLastCalledWith({ teamId: 7, level: 'WARNING', kind: 'CERT', groupId: null }))
    expect(await screen.findByText(/yalnız takım bilgilendirilir|only the team is notified/)).toBeInTheDocument()
    // admin değil → push bloğu yok
    expect(screen.queryByText(/Push alıcıları|Push recipients/)).toBeNull()
  })

  it('hiç alıcı yoksa açık uyarı', async () => {
    api.admin.simulateRecipients.mockResolvedValue({ success: true, data: { ...RESULT, team_emails: [], contacts: [], webhooks: [], push: [], email_total: 0 } })
    render(<RecipientSimulator teams={TEAMS} isAdmin defaultTeamId={9} />)
    fireEvent.click(screen.getByRole('button', { name: /Simülatörü aç|Open simulator/i }))
    expect(await screen.findByText(/HİÇ KİMSE|NOBODY/)).toBeInTheDocument()
  })
})
