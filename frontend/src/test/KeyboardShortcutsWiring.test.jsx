import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, act, waitFor } from './test-utils.jsx'
import { withSidebar } from './helpers/sidebar.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({ search: vi.fn(), getCertificatesPaginated: vi.fn(), users: { directory: vi.fn() } }),
  getRecentFailures: () => [],
}))
import CommandPalette from '../components/CommandPalette.jsx'
import Nav from '../components/Nav.jsx'
import { SHORTCUTS_EVENT } from '../utils/keyboardShortcuts.js'

// Öneri 24 bağlantıları: komut paleti eylemi, kullanıcı menüsü öğesi ve Nav'daki `g`+harf (menünün görünürlük kuralı).
const TABS = [{ id: 'dashboard', label: 'Genel Bakış' }, { id: 'help', label: 'Yardım' }]
const NAV_PROPS = { activeTab: 'dashboard', username: 'testuser', onLogout: vi.fn() }

beforeEach(() => { localStorage.clear() })

describe('Klavye kısayolları — palet, kullanıcı menüsü, Nav', () => {
  it('komut paletinde "Klavye kısayolları" eylemi `?` ipucuyla durur ve `sm:shortcuts` yayınlar', () => {
    const onShortcuts = vi.fn()
    window.addEventListener(SHORTCUTS_EVENT, onShortcuts)
    render(<CommandPalette tabs={TABS} onTabChange={() => {}} />)
    act(() => { window.dispatchEvent(new CustomEvent('sm:palette')) })
    const row = screen.getByText(/^(Keyboard shortcuts|Klavye kısayolları)$/).closest('[cmdk-item]')
    expect(row).toHaveAttribute('data-value', 'action:shortcuts')
    expect([...row.querySelectorAll('[data-slot="kbd"]')].map((k) => k.textContent)).toContain('?')
    fireEvent.click(row)
    expect(onShortcuts).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('dialog')).toBeNull()
    window.removeEventListener(SHORTCUTS_EVENT, onShortcuts)
  })

  it('kullanıcı menüsünün Yardım grubunda "Klavye kısayolları" listeyi açar; mevcut Ctrl B ipucu tek kalır', async () => {
    const { container } = render(withSidebar(<Nav {...NAV_PROPS} onTabChange={vi.fn()} />))
    pressMenuTrigger(container.querySelector('[data-tour="nav-user"]'))
    const menu = screen.getAllByRole('menu')[0]
    const help = menu.querySelector('[data-group="help"]')
    const item = [...help.querySelectorAll('[role="menuitem"]')].find((el) => /Keyboard shortcuts|Klavye kısayolları/.test(el.textContent))
    expect(item).toBeTruthy()
    expect(menu.querySelectorAll('[data-slot="dropdown-menu-shortcut"]')).toHaveLength(1)
    fireEvent.click(item)
    await waitFor(() => expect(screen.getByRole('dialog', { name: /Keyboard shortcuts|Klavye kısayolları/ })).toBeInTheDocument())
  })

  it('Nav: `g m` İzleme Panosu, `g s` Durum Sayfası, `g a` Alarm Geçmişi (menünün sekme listesi); `?` listeyi açar, açıkken `g` çalışmaz', () => {
    const onTabChange = vi.fn()
    render(withSidebar(<Nav {...NAV_PROPS} onTabChange={onTabChange} />))
    fireEvent.keyDown(window, { key: 'g' }); fireEvent.keyDown(window, { key: 'm' })
    expect(onTabChange).toHaveBeenLastCalledWith('monitoring')
    fireEvent.keyDown(window, { key: 'g' }); fireEvent.keyDown(window, { key: 's' })
    expect(onTabChange).toHaveBeenLastCalledWith('status')
    fireEvent.keyDown(window, { key: 'g' }); fireEvent.keyDown(window, { key: 'a' })
    expect(onTabChange).toHaveBeenLastCalledWith('alerthistory')
    fireEvent.keyDown(window, { key: '?', shiftKey: true })
    expect(screen.getByRole('dialog', { name: /Keyboard shortcuts|Klavye kısayolları/ })).toBeInTheDocument()
    // Pencere açıkken `g` dizisi çalışmaz
    const calls = onTabChange.mock.calls.length
    fireEvent.keyDown(window, { key: 'g' }); fireEvent.keyDown(window, { key: 'd' })
    expect(onTabChange).toHaveBeenCalledTimes(calls)
  })
})
