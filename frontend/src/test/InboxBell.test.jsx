import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { withSidebar } from './helpers/sidebar.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({ formatDateSec: (s) => String(s ?? ''), api: withApiFallback({ me: { inbox: vi.fn() } }) }))
import { api } from '../api/client'
import InboxBell from '../components/InboxBell.jsx'

/** Bildirim kutusu (2026-09-12, #2): okunmamış rozeti, tıklayınca gezinme + okundu, tümünü okundu say (localStorage). */
describe('InboxBell', () => {
  beforeEach(() => { vi.clearAllMocks(); try { localStorage.clear() } catch { /* yok */ } })
  const ITEMS = [
    { key: 'alert:9', kind: 'alert_open', level: 'CRITICAL', title: 'down.example.com', sub: 'HTTP_DOWN', at: '2026-09-12T10:00:00', tab: 'warnings', params: { incident: 9 } },
    { key: 'maint:3:x', kind: 'maintenance_soon', level: 'INFO', title: 'Gece bakımı', at: '2026-09-12T22:00:00', tab: 'maintenance', params: { window: 3 } },
  ]

  it('2 okunmamış rozet; öğe tıklanınca sm:navigate + okundu (rozet 1); "tümünü okundu say" rozeti sıfırlar ve saklanır', async () => {
    api.me.inbox.mockResolvedValue({ success: true, data: ITEMS })
    const nav = vi.fn(); window.addEventListener('sm:navigate', nav)
    render(withSidebar(<InboxBell username="u1" />))
    await waitFor(() => expect(document.querySelector('[data-sidebar="menu-badge"]')?.textContent).toBe('2'))
    fireEvent.click(screen.getByRole('button', { name: /Bildirimler|Notifications/ }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    fireEvent.click(screen.getByText('down.example.com').closest('button'))
    expect(nav.mock.calls[0][0].detail).toEqual({ tab: 'warnings', params: { incident: 9 } })
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(document.querySelector('[data-sidebar="menu-badge"]').textContent).toBe('1')
    fireEvent.click(screen.getByRole('button', { name: /Bildirimler|Notifications/ }))
    fireEvent.click(screen.getByRole('button', { name: /Tümünü okundu say|Mark all as read/ }))
    expect(document.querySelector('[data-sidebar="menu-badge"]')).toBeNull()
    expect(JSON.parse(localStorage.getItem('inbox-seen:u1'))).toEqual(expect.arrayContaining(['alert:9', 'maint:3:x']))
    window.removeEventListener('sm:navigate', nav)
  })

  it('boş kutu → rozet yok, boş mesaj', async () => {
    api.me.inbox.mockResolvedValue({ success: true, data: [] })
    render(withSidebar(<InboxBell username="u1" />))
    await waitFor(() => expect(api.me.inbox).toHaveBeenCalled())
    expect(document.querySelector('[data-sidebar="menu-badge"]')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /Bildirimler|Notifications/ }))
    expect(screen.getByText(/Bildirim yok|No notifications/)).toBeInTheDocument()
  })
})
