import { describe, it, expect, afterEach, beforeEach } from 'vitest'
import { createElement } from 'react'
import { render, act } from '@testing-library/react'
import { useElementHeight } from '../hooks/useElementHeight.js'

/**
 * `useElementHeight` (2026-10-17, inceleme A4): `useElementWidth()` ile aynı biçim — callback ref, ResizeObserver,
 * kenarlık dahil yüksekliğin tam sayıya yuvarlanmış hâli. İlk ölçüm mount'ta, gözlemci tetiği yeniden ölçer, öğe
 * sökülünce 0 ve gözlemci bırakılır; ResizeObserver yoksa bir kez ölçer. Rapor düzenleyicinin yapışkan eylem çubuğu
 * (üç satıra sarılabilir) bu ölçüyle kabın alt boşluğunu sürer — sabit pb-24 son bölüm kartını örtüyordu.
 */

const OrigRO = globalThis.ResizeObserver
let observers
function installRO() {
  observers = []
  globalThis.ResizeObserver = class {
    constructor(cb) { this.cb = cb; this.els = []; this.disconnected = 0; observers.push(this) }
    observe(el) { this.els.push(el) }
    unobserve() {}
    disconnect() { this.disconnected++ }
  }
}

// jsdom yerleşim hesaplamaz: ölçü prototipte stub'lanır, test yüksekliği `heightNow` ile ayarlar
let heightNow = 0
const origRect = HTMLElement.prototype.getBoundingClientRect
beforeEach(() => {
  installRO()
  HTMLElement.prototype.getBoundingClientRect = () => ({ height: heightNow, width: 320, top: 0, left: 0, right: 320, bottom: heightNow })
})
afterEach(() => {
  globalThis.ResizeObserver = OrigRO
  HTMLElement.prototype.getBoundingClientRect = origRect
})

let latestRef = null
function Probe() {
  const [ref, height] = useElementHeight()
  latestRef = ref
  return createElement('div', { ref, 'data-slot': 'probe', 'data-h': height })
}
const probe = (container) => container.querySelector('[data-slot="probe"]')

describe('useElementHeight', () => {
  it('ilk ölçüm mount’ta (yuvarlanmış), gözlemci tetiğiyle yeniden ölçer, sökülünce gözlemci bırakılır', () => {
    heightNow = 149.6
    const { container, unmount } = render(createElement(Probe))
    expect(probe(container).dataset.h).toBe('150')
    expect(observers).toHaveLength(1)
    expect(observers[0].els).toEqual([probe(container)])
    // Çubuk üçüncü satıra sarıldı → gözlemci tetikler → yeni yükseklik
    heightNow = 212
    act(() => observers[0].cb([]))
    expect(probe(container).dataset.h).toBe('212')
    unmount()
    expect(observers[0].disconnected).toBeGreaterThanOrEqual(1)
  })

  it('ref null (öğe söküldü) → 0 ve gözlemci bırakılır; ResizeObserver yoksa bir kez ölçer, gözlemci açılmaz', () => {
    heightNow = 96
    const { container } = render(createElement(Probe))
    expect(probe(container).dataset.h).toBe('96')
    act(() => latestRef(null))
    expect(probe(container).dataset.h).toBe('0')
    expect(observers[0].disconnected).toBeGreaterThanOrEqual(1)

    delete globalThis.ResizeObserver
    heightNow = 40.2
    const second = render(createElement(Probe))
    expect(probe(second.container).dataset.h).toBe('40')
    expect(observers).toHaveLength(1)
  })

  it('ölçü yoksa 0 (boş dikdörtgen — çağıran pb-24 tabanına düşer)', () => {
    HTMLElement.prototype.getBoundingClientRect = () => ({})
    const { container } = render(createElement(Probe))
    expect(probe(container).dataset.h).toBe('0')
  })
})
