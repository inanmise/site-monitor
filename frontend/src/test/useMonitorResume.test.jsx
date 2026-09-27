import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, act } from './test-utils.jsx'
import { useMonitorResume } from '../hooks/useMonitorResume.js'

/**
 * 2026-09-27 regresyon taraması (release-fixes.md FRONTEND A #8): `useMonitorResume` tek yuvalı meşgul bayrağı
 * (`resumingId`) tutuyordu — A sürerken B'ye basınca A'nın "sürdürülüyor" göstergesi sönüyor (iş sürerken bitmiş
 * görünüyor, ikinci kez basılabiliyor), önce biten ise HÂLÂ süren diğerinin göstergesini de kapatıyordu.
 * Model `useRunningChecks`: kimlik KÜMESİ + çift tık koruması. Denetimli promise'lerle belirlenimci.
 */
const deferred = () => { let resolve; const p = new Promise((r) => { resolve = r }); return { p, resolve } }

function Harness({ update, onDone }) {
  const { resume, isResuming, resumingId } = useMonitorResume(update, onDone)
  return (
    <div data-resuming-id={resumingId ?? ''}>
      {[1, 2].map((id) => (
        <button key={id} type="button" data-busy={isResuming(id) ? 'true' : 'false'} onClick={() => resume({ id })}>{`resume ${id}`}</button>
      ))}
    </div>
  )
}
const busy = (id) => screen.getByRole('button', { name: `resume ${id}` }).getAttribute('data-busy')

describe('useMonitorResume — eşzamanlı sürdürme', () => {
  it('A sürerken B başlarsa ikisi de meşgul görünür; önce biten B, A\'nın göstergesini SÖNDÜRMEZ', async () => {
    const calls = { 1: deferred(), 2: deferred() }
    const update = vi.fn((id) => calls[id].p)
    render(<Harness update={update} />)

    fireEvent.click(screen.getByRole('button', { name: 'resume 1' }))
    fireEvent.click(screen.getByRole('button', { name: 'resume 2' }))
    expect(busy(1)).toBe('true')
    expect(busy(2)).toBe('true')

    await act(async () => { calls[2].resolve({ success: true }) })
    expect(busy(2)).toBe('false')
    expect(busy(1)).toBe('true')   // A hâlâ sürüyor

    await act(async () => { calls[1].resolve({ success: true }) })
    expect(busy(1)).toBe('false')
  })

  it('aynı izlemeye çift tık tek istek gönderir; geriye uyum `resumingId` süren kimliği verir', async () => {
    const d = deferred()
    const update = vi.fn(() => d.p)
    const { container } = render(<Harness update={update} />)
    fireEvent.click(screen.getByRole('button', { name: 'resume 1' }))
    fireEvent.click(screen.getByRole('button', { name: 'resume 1' }))
    expect(update).toHaveBeenCalledTimes(1)
    expect(container.firstChild.getAttribute('data-resuming-id')).toBe('1')
    await act(async () => { d.resolve({ success: true }) })
    expect(container.firstChild.getAttribute('data-resuming-id')).toBe('')
  })
})
