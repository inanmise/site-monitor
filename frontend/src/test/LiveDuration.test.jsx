import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, act } from './test-utils.jsx'
import LiveDuration from '../components/incidents/LiveDuration.jsx'

/**
 * Canlı olay süresi yaprağı (2026-10-09): saat Olaylar sayfasında DEĞİL, her sürenin kendi `<span>`'ında —
 * sayfa/pano/tablo saniyede bir yeniden çizilmez. Değer eskisiyle aynı hesap: formatDuration(durationMs(...)).
 */
afterEach(() => { vi.useRealTimers() })

const T0 = Date.parse('2026-10-09T10:00:00Z')

describe('LiveDuration', () => {
  it('süren olayda saniyede bir tazelenir; ÜST bileşen yeniden çizilmez', () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'setTimeout', 'clearTimeout', 'Date'] })
    vi.setSystemTime(T0 + 5_000)
    let parentRenders = 0
    function Parent() {
      parentRenders++
      return <LiveDuration data-testid="dur" since="2026-10-09T10:00:00" live
        format={(d) => `sürüyor: ${d}`} />
    }
    render(<Parent />)
    const el = screen.getByTestId('dur')
    expect(el).toHaveClass('tabular-nums')
    const first = el.textContent
    expect(first).toMatch(/^sürüyor: 5 /)
    const before = parentRenders
    act(() => { vi.advanceTimersByTime(3_000) })
    expect(el.textContent).toMatch(/^sürüyor: 8 /)
    act(() => { vi.advanceTimersByTime(60_000) })
    expect(el.textContent).toMatch(/^sürüyor: 1 /)       // 68 sn → "1 dk"
    expect(parentRenders).toBe(before)
  })

  it('çözülmüş olayda süre başlangıç → bitişten hesaplanır (eski hesap), saat ilerlese de değişmez', () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] })
    vi.setSystemTime(T0 + 99_000_000)
    render(<LiveDuration data-testid="dur" since="2026-10-09T10:00:00" until="2026-10-09T11:30:00" />)
    const el = screen.getByTestId('dur')
    // 1 saat 30 dk — birim metni dile bağlı, sayılar eski hesapla birebir
    expect(el.textContent.replace(/[^\d ]/g, '').replace(/\s+/g, ' ').trim()).toBe('1 30')
    const text = el.textContent
    act(() => { vi.advanceTimersByTime(10_000) })
    expect(el.textContent).toBe(text)
  })
})
