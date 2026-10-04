import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({ admin: { clearUserPushSnooze: vi.fn() } }),
}))
import { api } from '../api/client'
import NotificationsTab from '../components/admin/userdetail/NotificationsTab.jsx'

/**
 * Kullanıcı detayı → Bildirimler (2026-10-04, onaylı öneri 4): kişinin push tercihleri SALT OKUNUR (seviye, türler, dil,
 * susturma); yönetici yalnız etkin susturmayı kaldırabilir (PUSH_SNOOZE_CLEAR). Yönetici olmayan görünümde kart yok.
 */
const base = { id: 7, username: 'N00007', team_id: null, push_opt_out: false }
const props = (user, extra = {}) => ({
  user, isAdmin: true, push: { data: null, status: 'idle', loading: false, reload: vi.fn() },
  contacts: { data: [], status: 'ok', loading: false, reload: vi.fn() }, teamMap: {}, onChanged: vi.fn(), ...extra,
})
const prefs = () => within(document.querySelector('[data-slot="ud-push-prefs"]'))

describe('NotificationsTab — kişisel push tercihleri', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('tercih yok: "No preferences" + "Not snoozed"; kaldır düğmesi yok', () => {
    render(<NotificationsTab {...props(base)} />)
    expect(prefs().getByText('No preferences — every push follows the default rules.')).toBeInTheDocument()
    expect(prefs().getByText('Not snoozed')).toBeInTheDocument()
    expect(prefs().queryByRole('button', { name: 'Clear snooze' })).toBeNull()
  })

  it('tercihler salt okunur görünür (seviye, türler, dil); etkin susturma kaldırılır → API + onChanged', async () => {
    api.admin.clearUserPushSnooze.mockResolvedValue({ success: true, had_snooze: true })
    const user = {
      ...base, push_min_level: 'CRITICAL', push_families: 'cert,http', push_lang: 'en',
      push_snooze_until: new Date(Date.now() + 3600_000).toISOString().slice(0, 19), push_snooze_critical: true,
    }
    const p = props(user)
    render(<NotificationsTab {...p} />)
    expect(prefs().getByText(/^Lowest level: /)).toBeInTheDocument()
    expect(prefs().getByText('Types: Certificate, HTTP')).toBeInTheDocument()
    expect(prefs().getByText('Push language: English')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="ud-push-snooze"]')).toHaveAttribute('data-active', 'true')
    expect(prefs().getByText(/critical alerts still arrive/)).toBeInTheDocument()
    fireEvent.click(prefs().getByRole('button', { name: 'Clear snooze' }))
    await waitFor(() => expect(api.admin.clearUserPushSnooze).toHaveBeenCalledWith(7))
    await waitFor(() => expect(p.onChanged).toHaveBeenCalled())
    expect(await screen.findByText('Snooze cleared.')).toBeInTheDocument()
  })

  it('süresi geçmiş susturma etkin sayılmaz; yönetici değilse kart çizilmez', () => {
    const user = { ...base, push_snooze_until: '2020-01-01T00:00:00' }
    const { unmount } = render(<NotificationsTab {...props(user)} />)
    expect(document.querySelector('[data-slot="ud-push-snooze"]')).toHaveAttribute('data-active', 'false')
    unmount()
    render(<NotificationsTab {...props(user, { isAdmin: false })} />)
    expect(document.querySelector('[data-slot="ud-push-prefs"]')).toBeNull()
  })
})
