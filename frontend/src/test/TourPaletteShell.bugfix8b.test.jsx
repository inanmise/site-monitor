import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from './test-utils.jsx'
import { EN } from '../i18n/en.js'

/**
 * PKG-8b (2026-10-22):
 *  UIA-H4  tur klavye dinleyicisi (pencere, yakalama) tur sırasında açılan pencerenin Esc / Enter / ok tuşlarını çalıyordu
 *  UIA-H8  komut paletinde düşen /api/search "'x' için sonuç yok" diye gösteriliyordu
 *  UIA-H9  palet satırı adı / alt satırı `truncate` ile kesiyordu
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({ search: vi.fn(), getCertificatesPaginated: vi.fn(), users: { directory: vi.fn() } }),
  getRecentFailures: () => [],
}))
import { api } from '../api/client'
import TourOverlay from '../components/tour/TourOverlay.jsx'
import CommandPalette from '../components/CommandPalette.jsx'

describe('TourOverlay — açılan pencerenin tuşları (UIA-H4)', () => {
  const active = { steps: [{ id: 'welcome', center: true }, { id: 'two', center: true }], index: 0, kind: 'main' }
  const setup = () => {
    const onNext = vi.fn(), onPrev = vi.fn(), onStop = vi.fn()
    render(
      <>
        <div role="dialog" aria-label="Yardım"><button type="button">Bölüme git</button><input aria-label="ara" /></div>
        <TourOverlay active={active} onNext={onNext} onPrev={onPrev} onStop={onStop} navigate={() => {}} />
      </>,
    )
    return { onNext, onPrev, onStop, dlgBtn: screen.getByRole('button', { name: 'Bölüme git' }) }
  }

  it('pencere içinde Esc / Enter / ok turu ÇEVİRMEZ ve olayı yutmaz', async () => {
    const { onNext, onPrev, onStop, dlgBtn } = setup()
    await waitFor(() => expect(document.querySelector('[data-slot="tour-tip"]')).not.toBeNull())
    for (const key of ['Escape', 'Enter', 'ArrowRight', 'ArrowLeft']) {
      const ev = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true })
      act(() => { dlgBtn.dispatchEvent(ev) })
      expect(ev.defaultPrevented).toBe(false)
    }
    expect(onNext).not.toHaveBeenCalled()
    expect(onPrev).not.toHaveBeenCalled()
    expect(onStop).not.toHaveBeenCalled()
  })

  it('pencere dışında / balonun kendisinde ok ve Esc turu yönetir (bugünkü)', async () => {
    const { onNext, onStop } = setup()
    const tip = await waitFor(() => { const el = document.querySelector('[data-slot="tour-tip"]'); expect(el).not.toBeNull(); return el })
    act(() => { tip.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true, cancelable: true })) })
    expect(onNext).toHaveBeenCalledTimes(1)
    // Balon odaktayken Enter de ileri (kısayol listesinde yazdığı gibi)
    act(() => { tip.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true })) })
    expect(onNext).toHaveBeenCalledTimes(2)
    act(() => { document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })) })
    expect(onStop).toHaveBeenCalled()
  })
})

describe('CommandPalette — arama hatası ve satır adları', () => {
  const TABS = [{ id: 'dashboard', label: 'Genel Bakış' }]
  const openWithKey = () => { fireEvent.keyDown(window, { key: 'k', ctrlKey: true }); return screen.getByRole('combobox') }
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    api.getCertificatesPaginated.mockResolvedValue({ success: true, data: [] })
    api.users.directory.mockResolvedValue({ success: true, data: [] })
  })

  it.each([
    ['başarısız yanıt', () => api.search.mockResolvedValue({ success: false, status: 500 })],
    ['ağ hatası', () => api.search.mockRejectedValue(new Error('net'))],
  ])('UIA-H8: %s → "arama yapılamadı", "sonuç yok" DENMEZ', async (_n, arrange) => {
    arrange()
    render(<CommandPalette tabs={TABS} onTabChange={() => {}} />)
    fireEvent.change(openWithKey(), { target: { value: 'zzz' } })
    await waitFor(() => expect(document.querySelector('[data-slot="palette-search-failed"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="palette-search-failed"]').textContent).toContain(EN['palette.searchFailed'])
    expect(document.querySelector('[data-slot="command-empty"]')).toBeNull()
  })

  it('UIA-H8: başarılı boş arama bugünkü gibi "sonuç yok"', async () => {
    api.search.mockResolvedValue({ success: true, data: [] })
    render(<CommandPalette tabs={TABS} onTabChange={() => {}} />)
    fireEvent.change(openWithKey(), { target: { value: 'zzz' } })
    await waitFor(() => expect(document.querySelector('[data-slot="command-empty"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="palette-search-failed"]')).toBeNull()
  })

  it('UIA-H9: uzun alan adı / AD adı satırda kesilmez (truncate yok)', async () => {
    const LONG = 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin (Teknoloji Servis Yönetimi Bölüm Başkanı)'
    api.search.mockResolvedValue({ success: true, data: [{ kind: 'http', id: '7', label: LONG, sub: 'https://cok-uzun-bir-alt-alan.ornek-kurum.com.tr/odeme', tab: 'http', params: { monitor: '7' } }] })
    render(<CommandPalette tabs={TABS} onTabChange={() => {}} />)
    fireEvent.change(openWithKey(), { target: { value: 'Ayşe' } })
    const label = await screen.findByText(LONG)
    expect(label.className).not.toMatch(/\btruncate\b/)
    expect(label.closest('[cmdk-item]').querySelector('.truncate')).toBeNull()
  })
})
