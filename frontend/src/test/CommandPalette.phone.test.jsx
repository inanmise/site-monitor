import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, act } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({ api: withApiFallback({ search: vi.fn() }) }))
// Telefon (<768): davranış farkı useIsMobile ile — jsdom medya sorgusu görmez, hook taklit edilir.
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => true }))
import { api } from '../api/client'
import CommandPalette from '../components/CommandPalette.jsx'

/** Komut paleti — telefon düzeni (2026-09-26): tam ekran, üstte kutu + Vazgeç, klavye ipucu şeridi yok. */
describe('CommandPalette (telefon)', () => {
  const TABS = [{ id: 'dashboard', label: 'Genel Bakış' }, { id: 'help', label: 'Yardım' }]
  beforeEach(() => { vi.clearAllMocks(); localStorage.clear(); api.search.mockResolvedValue({ success: true, data: [] }) })

  it('telefon düzeni: data-layout=phone, Vazgeç düğmesi kapatır, klavye ipucu şeridi çizilmez', () => {
    render(<CommandPalette tabs={TABS} onTabChange={() => {}} />)
    act(() => { window.dispatchEvent(new CustomEvent('sm:palette')) })
    const dlg = screen.getByRole('dialog')
    expect(dlg).toHaveAttribute('data-layout', 'phone')
    expect(dlg).toHaveAttribute('data-command-palette')
    expect(document.querySelector('[data-slot="palette-footer"]')).toBeNull()
    expect(screen.queryByText('Esc', { selector: '[data-slot="kbd"]' })).toBeNull()
    const cancel = screen.getByRole('button', { name: /Cancel|Vazgeç/ })
    expect(cancel).toHaveAttribute('data-variant', 'ghost')          // shadcn Button (DialogClose asChild data-slot'u "dialog-close" yapar)
    // Sonuç satırları cmdk seçeneği olarak duruyor; dokunma = tıklama
    expect(screen.getByText('Yardım').closest('[cmdk-item]')).toHaveAttribute('role', 'option')
    fireEvent.click(cancel)
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('satıra dokununca sekmeye gider ve palet kapanır', () => {
    const onTab = vi.fn()
    render(<CommandPalette tabs={TABS} onTabChange={onTab} />)
    act(() => { window.dispatchEvent(new CustomEvent('sm:palette')) })
    fireEvent.click(screen.getByText('Yardım').closest('[cmdk-item]'))
    expect(onTab).toHaveBeenCalledWith('help')
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})
