import { describe, it, expect, vi, afterEach } from 'vitest'
import { followLayout, FOLLOW_LAYOUT_MS } from '../components/myactivity/PushPreferencesCard.jsx'

// 2026-11-17: Etkinliklerim → push tercihleri derin bağlantısı yavaş ağda kartı kaçırıyordu — üstteki içerik 3 sn'den geç
// yüklenince izleme bitmişti. İzleme artık FOLLOW_LAYOUT_MS (10 sn) sürer; kişinin girdisiyle hemen biter; sınırlıdır.

let observers = []
class FakeResizeObserver {
  constructor(cb) { this.cb = cb; this.targets = []; this.disconnected = false; observers.push(this) }
  observe(t) { this.targets.push(t) }
  disconnect() { this.disconnected = true }
  fire() { if (!this.disconnected) this.cb([]) }
}

function setup() {
  const outer = document.createElement('div')
  const inner = document.createElement('div')
  const el = document.createElement('section')
  inner.appendChild(el)
  outer.appendChild(inner)
  document.body.appendChild(outer)
  el.scrollIntoView = vi.fn()
  return { el, cleanup: () => outer.remove() }
}

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  observers = []
})

describe('followLayout', () => {
  it('izleme süresi 10 sn: 5. saniyedeki yerleşim değişimi yeniden hizalar, süre dolunca gözlemci kapanır', () => {
    expect(FOLLOW_LAYOUT_MS).toBe(10000)
    vi.useFakeTimers()
    vi.stubGlobal('ResizeObserver', FakeResizeObserver)
    const { el, cleanup } = setup()
    const stop = followLayout(el)
    const ro = observers[0]
    ro.fire()                                  // ilk geri çağırma gözlem başlarken: hizalama yok
    expect(el.scrollIntoView).not.toHaveBeenCalled()
    vi.advanceTimersByTime(5000)
    ro.fire()                                  // geç yüklenen içerik (3 sn'den sonra) → yeniden hizalanır
    expect(el.scrollIntoView).toHaveBeenCalledTimes(1)
    expect(el.scrollIntoView).toHaveBeenLastCalledWith({ block: 'start' })
    vi.advanceTimersByTime(5001)
    expect(ro.disconnected).toBe(true)         // sınırlı: süre dolunca biter
    ro.fire()
    expect(el.scrollIntoView).toHaveBeenCalledTimes(1)
    stop()
    cleanup()
  })

  it('kişinin girdisi (tekerlek) izlemeyi hemen bitirir', () => {
    vi.useFakeTimers()
    vi.stubGlobal('ResizeObserver', FakeResizeObserver)
    const { el, cleanup } = setup()
    followLayout(el)
    const ro = observers[0]
    ro.fire()
    window.dispatchEvent(new Event('wheel'))
    expect(ro.disconnected).toBe(true)
    ro.fire()
    expect(el.scrollIntoView).not.toHaveBeenCalled()
    cleanup()
  })

  it('ResizeObserver yoksa hiçbir şey yapmaz', () => {
    vi.stubGlobal('ResizeObserver', undefined)
    const { el, cleanup } = setup()
    expect(followLayout(el)).toBeUndefined()
    cleanup()
  })
})
