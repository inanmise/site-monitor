import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { Profiler } from 'react'
import { act, render } from './test-utils.jsx'
import TourOverlay from '../components/tour/TourOverlay.jsx'

/**
 * Tur balonu konum tazelemesi (2026-10-09): hedef yerinde dururken 200 ms'lik tazeleme her seferinde YENİ bir DOMRect
 * yazıp balonu ve karartmayı yeniden çiziyordu. Artık yalnız konum/boyut değişince yazılır.
 */

describe('TourOverlay — konum tazeleme yalnız değişimde çizer', () => {
  let target
  let box
  beforeEach(() => {
    vi.useFakeTimers()
    box = { top: 100, left: 50, width: 200, height: 40 }
    target = document.createElement('div')
    target.setAttribute('data-tour', 'hedef')
    target.scrollIntoView = () => {}
    // Her çağrıda YENİ nesne (tarayıcı gibi) — aynı değerler
    target.getBoundingClientRect = () => ({ ...box, right: box.left + box.width, bottom: box.top + box.height, x: box.left, y: box.top })
    document.body.appendChild(target)
  })
  afterEach(() => {
    target.remove()
    vi.useRealTimers()
  })

  it('hedef sabitken 2 sn boyunca yeniden çizim yok; hedef kayınca bir kez çizilir', () => {
    let commits = 0
    const active = { steps: [{ id: 'dash', target: 'hedef' }], index: 0, kind: 'main' }
    render(
      <Profiler id="tour" onRender={() => { commits++ }}>
        <TourOverlay active={active} onNext={() => {}} onPrev={() => {}} onStop={() => {}} navigate={() => {}} />
      </Profiler>,
    )
    act(() => { vi.advanceTimersByTime(500) })          // hedef bulundu, balon yerleşti, odak
    expect(document.querySelector('[data-slot="tour-tip"]')).not.toBeNull()
    const settled = commits
    act(() => { vi.advanceTimersByTime(2000) })         // eskisi: ~10 gereksiz çizim
    expect(commits).toBe(settled)

    box = { ...box, top: 300 }
    act(() => { vi.advanceTimersByTime(200) })
    expect(commits).toBeGreaterThan(settled)
    expect(document.querySelector('.tour-ring').style.top).toBe('294px')   // yeni konum çizildi (300 − 6 dolgu)
    // React bir güncellemeden sonra aynı değerli ilk setState'te tek bir "kurtarma" çizimi yapabilir — sürekli değil
    act(() => { vi.advanceTimersByTime(1500) })
    const after = commits
    act(() => { vi.advanceTimersByTime(2000) })
    expect(commits).toBe(after)
  })
})
