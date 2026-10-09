import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, act } from '@testing-library/react'
import InactivityCountdownText from '../components/ui/InactivityCountdownText.jsx'

/**
 * Hareketsizlik sayacı yaprağı (2026-10-09, performans): saniye sayacı App'in durumundan çıktı; yalnız bu yaprak çizilir.
 * Kalan saniye son andan (deadline) hesaplanır: sekme uyutulup geri gelse de sayaç gerçeği söyler, 0'ın altına inmez.
 */
describe('InactivityCountdownText', () => {
  afterEach(() => { vi.useRealTimers() })

  it('kalan saniyeyi son andan sayar, her saniye azalır, 0 da durur', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-09T10:00:00Z'))
    const deadline = Date.now() + 3000
    const { container } = render(<InactivityCountdownText deadline={deadline} format={(s) => `kalan <strong>${s}</strong>`} />)
    const el = () => container.querySelector('[data-slot="inactivity-countdown"]')
    expect(el().innerHTML).toBe('kalan <strong>3</strong>')
    act(() => { vi.advanceTimersByTime(1000) })
    expect(el().querySelector('strong').textContent).toBe('2')
    act(() => { vi.advanceTimersByTime(5000) })
    expect(el().querySelector('strong').textContent).toBe('0')
  })

  it('sekme uyuyup geri gelince sayaç atlanan süreyi yansıtır (aralık birikmesine güvenmez)', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-09T10:00:00Z'))
    const deadline = Date.now() + 60_000
    const { container } = render(<InactivityCountdownText deadline={deadline} format={(s) => String(s)} />)
    vi.setSystemTime(new Date('2026-10-09T10:00:40Z'))   // tarayıcı zamanlayıcıyı kıstı: 40 sn tek seferde geçti
    act(() => { vi.advanceTimersByTime(1000) })
    expect(Number(container.textContent)).toBeLessThanOrEqual(20)
  })
})
