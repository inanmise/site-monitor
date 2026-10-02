import { describe, it, expect, afterEach } from 'vitest'
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { render, act } from '@testing-library/react'
import * as shared from '../hooks/useElementWidth.js'
import * as forecastUi from '../pages/forecast/forecastUi.jsx'

/**
 * KARAKTERİZASYON (2026-10-02, öneri 29): kap genişliği ölçümünün kopyaları tek kancada (hooks/useElementWidth.js)
 * toplanmadan ÖNCE davranış pinlenir. İki biçim var ve ikisi de korunur:
 *   A) `[ref, width]` — callback ref, `clientWidth || 0`, ResizeObserver yoksa bir kez ölçer (hooks/useElementWidth,
 *      MonitoringOverviewPage, ChartPanel, CertDaysTrend, HttpTrafficChart.useWidth kopyaları).
 *   B) `[width, setEl]` — öğe durumda, layout effect, `Math.round(getBoundingClientRect().width)` (weekly ve
 *      forecastUi kopyaları).
 * Taşıma öncesi gövdeler kâhin; aynı senaryoda (ilk ölçüm, gözlemci tetiği, öğe değişimi, söküm, gözlemci yok) aynı
 * değer dizisini üretmeliler.
 */

// ── Kâhinler (taşıma öncesi, değiştirme!) ──
function useOracleA() {   // ChartPanel / CertDaysTrend (gözlemci önce yerel değişkende)
  const [width, setWidth] = useState(0)
  const roRef = useRef(null)
  const ref = useCallback((el) => {
    roRef.current?.disconnect()
    roRef.current = null
    if (!el) return
    const measure = () => setWidth(el.clientWidth || 0)
    measure()
    if (typeof ResizeObserver !== 'undefined') {
      const ro = new ResizeObserver(measure)
      ro.observe(el)
      roRef.current = ro
    }
  }, [])
  useEffect(() => () => roRef.current?.disconnect(), [])
  return [ref, width]
}
function useOracleB() {   // weekly/useElementWidth.js ≡ forecastUi.jsx
  const [el, setEl] = useState(null)
  const [width, setWidth] = useState(0)
  useLayoutEffect(() => {
    if (!el) return undefined
    const measure = () => setWidth(Math.round(el.getBoundingClientRect().width))
    measure()
    if (typeof ResizeObserver === 'undefined') return undefined
    const ro = new ResizeObserver(measure)
    ro.observe(el)
    return () => ro.disconnect()
  }, [el])
  return [width, setEl]
}

// Denetlenebilir ResizeObserver: gözlenen öğeler ve geri çağrılar elde; disconnect sayılır.
const OrigRO = globalThis.ResizeObserver
let observers
function installRO() {
  observers = []
  globalThis.ResizeObserver = class {
    constructor(cb) { this.cb = cb; this.els = []; this.disconnected = false; observers.push(this) }
    observe(el) { this.els.push(el) }
    unobserve() {}
    disconnect() { this.disconnected = true }
  }
}
afterEach(() => { globalThis.ResizeObserver = OrigRO })
const fire = () => act(() => { for (const o of observers) if (!o.disconnected) o.cb([]) })

function sizeEl(el, w) {
  Object.defineProperty(el, 'clientWidth', { configurable: true, get: () => Math.floor(w) })
  el.getBoundingClientRect = () => ({ width: w, height: 10, top: 0, left: 0, right: w, bottom: 10, x: 0, y: 0 })
}

/** Senaryo: ilk ölçüm → yeniden boyut → öğe değişimi → söküm; görülen değerleri ve gözlemci durumunu yazar. */
function scenario(useHook, shape, withRO = true) {
  if (withRO) installRO(); else { observers = []; globalThis.ResizeObserver = undefined }
  const seen = []
  const elA = document.createElement('div'); sizeEl(elA, 300.6)
  const elB = document.createElement('div'); sizeEl(elB, 120.4)
  let api
  function Probe({ target }) {
    const r = useHook()
    api = r
    const [ref, width] = shape === 'A' ? r : [r[1], r[0]]
    seen.push(width)
    useLayoutEffect(() => { ref(target) }, [ref, target])
    return null
  }
  const { rerender, unmount } = render(<Probe target={elA} />)
  sizeEl(elA, 640.5); fire()
  rerender(<Probe target={elB} />)
  sizeEl(elB, 0); fire()
  const live = observers.filter((o) => !o.disconnected).length
  unmount()
  const afterUnmountLive = observers.filter((o) => !o.disconnected).length
  return { last: shape === 'A' ? api[1] : api[0], distinct: [...new Set(seen)], live, afterUnmountLive, created: observers.length }
}

describe('kap genişliği kancaları — taşıma öncesi davranış (kâhinle birebir)', () => {
  it('A biçimi: hooks/useElementWidth ≡ ChartPanel/CertDaysTrend kopyası (clientWidth, callback ref)', () => {
    const want = scenario(useOracleA, 'A')
    expect(scenario(shared.useElementWidth, 'A')).toEqual(want)
    expect(want.distinct).toEqual([0, 300, 640, 120])
    expect(want.afterUnmountLive).toBe(0)
    // Gözlemci yokken bir kez ölçer
    const noRo = scenario(shared.useElementWidth, 'A', false)
    expect(noRo).toEqual(scenario(useOracleA, 'A', false))
    expect(noRo.distinct).toEqual([0, 300, 120])
  })

  it('B biçimi: hooks/useElementWidthState ≡ eski weekly / forecastUi kopyası (yuvarlanmış getBoundingClientRect, [width, setEl])', () => {
    const want = scenario(useOracleB, 'B')
    expect(scenario(shared.useElementWidthState, 'B')).toEqual(want)
    expect(want.distinct).toEqual([0, 301, 641, 120])
    expect(scenario(shared.useElementWidthState, 'B', false)).toEqual(scenario(useOracleB, 'B', false))
  })

  it('tek kaynak: eski kopyalar kaldırıldı (forecastUi artık genişlik kancası dışa aktarmaz)', () => {
    expect(Object.keys(shared).sort()).toEqual(['useElementWidth', 'useElementWidthState'])
    expect(forecastUi.useElementWidth).toBeUndefined()
  })
})
