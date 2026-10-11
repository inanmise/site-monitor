import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    oncall: { get: vi.fn(), save: vi.fn(), addOverride: vi.fn(), deleteOverride: vi.fn(), current: vi.fn() },
    admin: { getTeamUsers: vi.fn() },
  }),
}))
import { api } from '../api/client'
import OnCallScheduleModal from '../components/admin/oncall/OnCallScheduleModal.jsx'

/**
 * Nöbet çizelgesi penceresi (2026-10-11): şu anki nöbetçi kartı, sıra (ekle / taşı / çıkar), kaydetme gövdesi,
 * kaydet ancak değişiklikte etkin, açıkken boş sıra alan hatası, salt okunur kip, yer değiştirme ekle. Adlar sahte.
 */
const LONG = 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin (Teknoloji Servis Yönetimi / Ödeme Sistemleri)'
const VIEW = {
  team_id: 5, configured: true, enabled: true, rotation: 'WEEKLY', anchor: '2026-10-05T09:00', notify_oncall: false, can_edit: true,
  members: [{ user_id: 11, name: LONG, active: true }, { user_id: 12, name: 'Mehmet Demir', active: true }],
  current: { user_id: 11, name: LONG, starts_at: '2026-10-05T06:00:00', ends_at: '2026-10-12T06:00:00', override: false, active: true },
  upcoming: [{ user_id: 11, name: LONG, starts_at: '2026-10-05T06:00:00', ends_at: '2026-10-12T06:00:00' },
    { user_id: 12, name: 'Mehmet Demir', starts_at: '2026-10-12T06:00:00', ends_at: '2026-10-19T06:00:00' }],
  overrides: [],
}

beforeEach(() => {
  vi.clearAllMocks()
  api.oncall.get.mockResolvedValue({ success: true, data: VIEW })
  api.admin.getTeamUsers.mockResolvedValue({ success: true, data: [
    { id: 11, display_name: LONG, active: true }, { id: 12, display_name: 'Mehmet Demir', active: true },
    { id: 13, display_name: 'Zeynep Kaya', active: true }] })
  api.oncall.save.mockImplementation(async (_id, body) => ({ success: true, data: { ...VIEW, notify_oncall: body.notify_oncall,
    members: body.members.map((id) => ({ user_id: id, name: id === 13 ? 'Zeynep Kaya' : id === 12 ? 'Mehmet Demir' : LONG, active: true })) } }))
})

const team = { id: 5, name: 'Ödeme Sistemleri' }

describe('OnCallScheduleModal', () => {
  it('şu anki nöbetçi kartı adı kesmeden; sıra ve yaklaşan nöbetler', async () => {
    render(<OnCallScheduleModal team={team} onClose={() => {}} />)
    const cur = await waitFor(() => {
      const el = document.querySelector('[data-slot="oncall-current"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(cur).toHaveTextContent(LONG)
    expect(document.querySelectorAll('[data-slot="oncall-member"]')).toHaveLength(2)
    expect(document.querySelector('[data-slot="oncall-upcoming"]')).toHaveTextContent('Mehmet Demir')
  })

  it('kaydet yalnız değişiklikte etkin; taşı + seçenek → doğru gövde', async () => {
    render(<OnCallScheduleModal team={team} onClose={() => {}} />)
    const save = await screen.findByRole('button', { name: /Save schedule|Çizelgeyi kaydet/ })
    expect(save).toBeDisabled()
    const second = document.querySelector('[data-slot="oncall-member"][data-index="1"]')
    fireEvent.click(within(second).getByRole('button', { name: /Move up|Yukarı taşı/ }))
    fireEvent.click(screen.getByRole('switch', { name: /HIGH and CRITICAL|YÜKSEK ve KRİTİK/ }))
    expect(save).toBeEnabled()
    fireEvent.click(save)
    await waitFor(() => expect(api.oncall.save).toHaveBeenCalledTimes(1))
    expect(api.oncall.save.mock.calls[0][1]).toEqual({ enabled: true, rotation: 'WEEKLY', anchor: '2026-10-05T09:00', notify_oncall: true, members: [12, 11] })
  })

  it('açıkken sıradaki herkes çıkarılırsa kaydetmede alan hatası, istek yok', async () => {
    render(<OnCallScheduleModal team={team} onClose={() => {}} />)
    await screen.findByRole('button', { name: /Save schedule|Çizelgeyi kaydet/ })
    for (const btn of screen.getAllByRole('button', { name: /Remove from rotation|Sıradan çıkar/ })) fireEvent.click(btn)
    fireEvent.click(screen.getByRole('button', { name: /Save schedule|Çizelgeyi kaydet/ }))
    expect(await screen.findByText(/Add at least one person|en az bir kişi/)).toBeInTheDocument()
    expect(api.oncall.save).not.toHaveBeenCalled()
  })

  it('yönetemeyen kişi salt okunur görür: kaydet / sıra düğmeleri yok, üye listesi istenmez', async () => {
    api.oncall.get.mockResolvedValue({ success: true, data: { ...VIEW, can_edit: false } })
    render(<OnCallScheduleModal team={team} onClose={() => {}} />)
    expect(await screen.findByText(/only people who manage the team|takımı yöneten kişilerden/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Save schedule|Çizelgeyi kaydet/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /Move up|Yukarı taşı/ })).toBeNull()
    expect(api.admin.getTeamUsers).not.toHaveBeenCalled()
  })

  it('yer değiştirme: alanlar boşken alan hataları; listede satır + sil onayı', async () => {
    api.oncall.get.mockResolvedValue({ success: true, data: { ...VIEW, overrides: [
      { id: 4, user_id: 12, name: 'Mehmet Demir', starts_at: '2026-10-20T06:00:00', ends_at: '2026-10-21T06:00:00', note: 'yıllık izin' }] } })
    api.oncall.deleteOverride.mockResolvedValue({ success: true, data: VIEW })
    render(<OnCallScheduleModal team={team} onClose={() => {}} />)
    const row = await waitFor(() => {
      const el = document.querySelector('[data-slot="oncall-override"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(row).toHaveTextContent('yıllık izin')
    fireEvent.click(screen.getByRole('button', { name: /^Add override$|^Yer değiştirme ekle$/ }))
    expect(await screen.findByText(/Pick the person taking over|Nöbeti devralacak kişiyi seçin/)).toBeInTheDocument()
    expect(api.oncall.addOverride).not.toHaveBeenCalled()
    fireEvent.click(within(row).getByRole('button', { name: /Delete override|Yer değiştirmeyi sil/ }))
    const dialog = await screen.findByRole('dialog', { name: /Delete the override|Yer değiştirme silinsin/ })
    fireEvent.click(within(dialog).getByRole('button', { name: /Delete override|Yer değiştirmeyi sil/ }))
    await waitFor(() => expect(api.oncall.deleteOverride).toHaveBeenCalledWith(4))
  })
})
