import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, act } from './test-utils.jsx'
import { ShieldCheck } from 'lucide-react'
import MonitorStatsBar, { balancedColumns, MIN_TILE_PX } from '../components/MonitorStatsBar.jsx'

/**
 * MonitorStatsBar dengeli yerleşim (2026-09-27, kullanıcı isteği: Pano istatistik kartları alanı tam ve dinamik doldursun).
 * jsdom yerleşim yapmaz: sütun matematiği saf fonksiyonda sınanır, ölçüm yolu ResizeObserver + clientWidth taklidiyle.
 */
const items = (n) => Array.from({ length: n }, (_, i) => ({ key: `k${i}`, Icon: ShieldCheck, label: `L${i}`, value: i, cls: 'total' }))

describe('balancedColumns', () => {
  it('sığan sütun sayısını satırlara eşit dağıtır (11 kart 1100 px → 6 + 5, 7 + 4 değil)', () => {
    // 1100 px, 12 px boşluk: (1100+12)/(140+12) = 7 sığar → 2 satır → ceil(11/2) = 6 sütun
    expect(balancedColumns(1100, 12, 11)).toBe(6)
    // 8 kart aynı genişlikte: 7 sığar → 2 satır → 4 + 4
    expect(balancedColumns(1100, 12, 8)).toBe(4)
    // hepsi tek satıra sığıyorsa sütun = kart sayısı
    expect(balancedColumns(1100, 12, 6)).toBe(6)
    // telefon (358 px, 8 px): 2 sığar → 11 kart 6 satır → 2 sütun
    expect(balancedColumns(358, 8, 11)).toBe(2)
    // çok geniş ekranda bile satır dengesi korunur (1568 px: 10 sığar → 2 satır → 6)
    expect(balancedColumns(1568, 12, 11)).toBe(6)
  })

  it('ölçüm yokken 0 döner (sınıf tabanlı tabana düşülür) ve en az 1 sütun garantidir', () => {
    expect(balancedColumns(0, 12, 11)).toBe(0)
    expect(balancedColumns(undefined, 12, 11)).toBe(0)
    expect(balancedColumns(1100, 12, 0)).toBe(0)
    expect(balancedColumns(MIN_TILE_PX - 40, 8, 3)).toBe(1)
  })
})

describe('MonitorStatsBar ölçülmüş yerleşim', () => {
  const origRO = globalThis.ResizeObserver
  afterEach(() => {
    globalThis.ResizeObserver = origRO
    vi.restoreAllMocks()
  })

  it('ResizeObserver yokken (jsdom) sınıf tabanlı taban: data-cols yok, inline flex-basis yok', () => {
    delete globalThis.ResizeObserver
    render(<MonitorStatsBar items={items(11)} activeFilter={null} onStatClick={() => {}} />)
    const panel = document.querySelector('[data-slot="stats-panel"]')
    expect(panel).not.toHaveAttribute('data-cols')
    expect(panel.querySelector('[data-slot="stat-item"]').style.flexBasis).toBe('')
  })

  it('kap ölçülünce dengeli sütun sayısı ve kart tabanı yazılır; yeniden boyutta güncellenir', () => {
    let cb
    globalThis.ResizeObserver = class {
      constructor(fn) { cb = fn }
      observe() {}
      disconnect() {}
    }
    // Kap iç genişliği 1100 (padding 12+12 → clientWidth 1124), sütun boşluğu 12
    let clientWidth = 1124
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockImplementation(function () {
      return this.getAttribute('data-slot') === 'stats-panel' ? clientWidth : 0
    })
    vi.spyOn(window, 'getComputedStyle').mockImplementation(() => ({ paddingLeft: '12px', paddingRight: '12px', columnGap: '12px' }))

    render(<MonitorStatsBar items={items(11)} activeFilter={null} onStatClick={() => {}} />)
    const panel = document.querySelector('[data-slot="stats-panel"]')
    expect(panel).toHaveAttribute('data-cols', '6')
    // taban = floor((1100 - 5*12) / 6) = 173 px; kalan pikselleri flex-grow dağıtır
    const first = panel.querySelector('[data-slot="stat-item"]')
    expect(first.style.flexBasis).toBe('173px')
    expect(first.className).toContain('grow')

    // daraltınca (600 px iç genişlik: 4 sığar → 3 satır → 4 sütun) yeniden ölçülür
    clientWidth = 624
    act(() => { cb([]) })
    expect(panel).toHaveAttribute('data-cols', '4')
    expect(first.style.flexBasis).toBe(`${Math.floor((600 - 3 * 12) / 4)}px`)
  })
})
