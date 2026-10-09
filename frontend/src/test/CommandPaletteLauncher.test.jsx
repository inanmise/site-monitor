import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, act, waitFor } from './test-utils.jsx'
import { withSidebar } from './helpers/sidebar.jsx'

/**
 * Komut paleti ilk kullanımda yüklenir (2026-10-09, açılış paketi küçültme) — Nav paleti değil küçük başlatıcıyı
 * (CommandPaletteLauncher) bağlar. Sözleşme: Ctrl/⌘+K İLK basışta da tarayıcı varsayılanını engeller ve paleti açar
 * (parça yüklenince); yükleme sürerken ikinci basış isteği geri alır, Esc bekleyen açılışı iptal eder; yüklendikten
 * sonra yalnız paletin kendi dinleyicisi çalışır (bir basış = bir aç-kapa).
 *
 * Parçanın yüklenme anı bir KAPI ile denetlenir: palet modülünün taklidi kapı açılana dek çözülmez.
 */
const gate = vi.hoisted(() => {
  const g = {}
  g.reset = () => { g.promise = new Promise((resolve) => { g.open = resolve }) }
  g.reset()
  return g
})
vi.mock('../components/CommandPalette.jsx', async (importOriginal) => {
  await gate.promise
  return importOriginal()
})
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({ search: vi.fn(), getCertificatesPaginated: vi.fn(), users: { directory: vi.fn() } }),
  getRecentFailures: () => [],
}))

const NAV_PROPS = { activeTab: 'dashboard', username: 'testuser', onLogout: vi.fn(), onTabChange: vi.fn() }
const TABS = [{ id: 'dashboard', label: 'Genel Bakış' }, { id: 'help', label: 'Yardım' }]
const ctrlK = () => fireEvent.keyDown(window, { key: 'k', ctrlKey: true })   // false = varsayılan engellendi
const palette = () => document.querySelector('[data-command-palette]')
const flush = () => act(async () => { for (let i = 0; i < 5; i++) await new Promise((r) => setTimeout(r, 0)) })

beforeEach(() => { localStorage.clear() })

describe('Komut paleti — ilk kullanımda yüklenir, ilk basış korunur', () => {
  it('Nav: parça YÜKLENMEDEN basılan ilk Ctrl+K varsayılanı engeller; parça gelince palet açılır; sonra bir basış kapatır', async () => {
    const { default: Nav } = await import('../components/Nav.jsx')
    render(withSidebar(<Nav {...NAV_PROPS} />))
    expect(palette()).toBeNull()
    expect(ctrlK()).toBe(false)
    await flush()
    expect(palette()).toBeNull()          // kapı kapalı: parça hâlâ yükleniyor
    await act(async () => { gate.open() })
    expect(await screen.findByRole('dialog')).toHaveAttribute('data-command-palette')
    expect(document.querySelectorAll('[data-command-palette]')).toHaveLength(1)
    // Yüklendikten sonra yalnız paletin dinleyicisi: bir basış kapatır (başlatıcı da işleseydi palet açık kalırdı)
    expect(ctrlK()).toBe(false)
    await waitFor(() => expect(palette()).toBeNull())
    ctrlK()
    expect(await screen.findByRole('dialog')).toHaveAttribute('data-command-palette')
  })

  it('yükleme sürerken ikinci Ctrl+K isteği geri alır, Esc bekleyen açılışı iptal eder; sonra Ctrl+K yine açar', async () => {
    vi.resetModules()
    gate.reset()
    const { default: CommandPaletteLauncher } = await import('../components/CommandPaletteLauncher.jsx')
    render(<CommandPaletteLauncher tabs={TABS} onTabChange={() => {}} />)
    expect(ctrlK()).toBe(false)
    expect(ctrlK()).toBe(false)           // aç → geri al
    expect(ctrlK()).toBe(false)           // yeniden aç…
    fireEvent.keyDown(window, { key: 'Escape' })   // …Esc iptal eder
    await act(async () => { gate.open() })
    await flush()
    expect(palette()).toBeNull()
    ctrlK()
    expect(await screen.findByRole('dialog')).toHaveAttribute('data-command-palette')
  })

  it('`sm:palette` olayı (kenar çubuğu / mobil üst çubuk düğmesi) paleti açar', async () => {
    await act(async () => { gate.open() })
    const { default: CommandPaletteLauncher } = await import('../components/CommandPaletteLauncher.jsx')
    render(<CommandPaletteLauncher tabs={TABS} onTabChange={() => {}} />)
    expect(palette()).toBeNull()
    act(() => { window.dispatchEvent(new CustomEvent('sm:palette')) })
    expect(await screen.findByRole('dialog')).toHaveAttribute('data-command-palette')
  })
})
