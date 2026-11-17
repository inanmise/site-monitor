import { describe, it, expect, vi, afterEach } from 'vitest'
import { act, render } from './test-utils.jsx'

/**
 * Performans (2026-11-17) — Olaylar:
 *  • süren olay süresi (LiveDuration) satır başına AYRI 1 sn'lik zamanlayıcı kuruyordu; artık tek ortak saniye saati
 *    (yalnız abone varken ve sekme görünürken çalışır);
 *  • tablo satırları memo: seçim / "Yeni" işareti / tek satırın yaması yalnız o satırı yeniden çizer.
 */
const renders = vi.hoisted(() => ({ severity: new Map() }))
vi.mock('../components/incidents/IncidentBadges.jsx', async (orig) => {
  const real = await orig()
  return {
    ...real,
    // Her satırın önem rozeti — satır başına çizim sayacı (seviye = olay kimliği)
    SeverityBadge: (props) => { renders.severity.set(props.level, (renders.severity.get(props.level) || 0) + 1); return real.SeverityBadge(props) },
  }
})
import IncidentsTable from '../components/incidents/IncidentsTable.jsx'
import LiveDuration from '../components/incidents/LiveDuration.jsx'
import { secondTickerState } from '../utils/secondTicker.js'

const iso = (msAgo) => new Date(Date.now() - msAgo).toISOString().slice(0, 19)
const row = (id) => ({
  id, status: 'ongoing', alert_level: `L${id}`, started_at: iso(60_000 * id), resolved_at: null, domain: `h${id}.example.com`,
  monitor: { name: `Monitor ${id}`, type: 'HTTP' }, acknowledged: false, can_act: true, can_manage: true, comment_count: 0,
})
const ROWS = Array.from({ length: 20 }, (_, i) => row(i + 1))
const noop = () => {}
const PROPS = { sort: { by: 'started', dir: 'desc' }, onSort: noop, onOpen: noop, onAck: noop, onResolve: noop, onDelete: noop }

afterEach(() => { vi.restoreAllMocks(); renders.severity.clear() })

describe('Olaylar — ortak saniye saati', () => {
  it('20 süren olay tek zamanlayıcı kurar; son abone ayrılınca zamanlayıcı durur', () => {
    const spy = vi.spyOn(globalThis, 'setInterval')
    const { unmount } = render(<IncidentsTable rows={ROWS} selectedId={null} {...PROPS} />)
    const perSecond = spy.mock.calls.filter(([, ms]) => ms === 1000).length
    expect(perSecond).toBe(1)                                 // eskiden satır başına 1 (20)
    expect(secondTickerState()).toEqual({ running: true, subscribers: 20 })
    unmount()
    expect(secondTickerState()).toEqual({ running: false, subscribers: 0 })
  })

  it('süre her saniye tazelenir; sekme gizliyken zamanlayıcı durur, görünür olunca hemen tazelenir', () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] })
    let hidden = false
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden })
    try {
      vi.setSystemTime(Date.parse('2026-11-17T10:00:00Z'))
      const { container, unmount } = render(<LiveDuration since="2026-11-17T09:59:50" live />)
      const text = () => container.textContent
      const first = text()
      act(() => { vi.advanceTimersByTime(5_000) })
      expect(text()).not.toBe(first)
      hidden = true
      act(() => { document.dispatchEvent(new Event('visibilitychange')) })
      expect(secondTickerState().running).toBe(false)
      const atHide = text()
      vi.setSystemTime(Date.parse('2026-11-17T10:10:00Z'))
      act(() => { vi.advanceTimersByTime(5_000) })
      expect(text()).toBe(atHide)
      hidden = false
      act(() => { document.dispatchEvent(new Event('visibilitychange')) })
      expect(secondTickerState().running).toBe(true)
      expect(text()).not.toBe(atHide)
      unmount()
    } finally {
      vi.useRealTimers()
      delete document.hidden
    }
  })
})

describe('Olaylar — satırlar memo', () => {
  it('seçim değişince yalnız eski ve yeni seçili satır yeniden çizilir', () => {
    const { rerender } = render(<IncidentsTable rows={ROWS} selectedId={null} {...PROPS} />)
    renders.severity.clear()
    rerender(<IncidentsTable rows={ROWS} selectedId="3" {...PROPS} />)
    expect([...renders.severity.keys()]).toEqual(['L3'])
    renders.severity.clear()
    rerender(<IncidentsTable rows={ROWS} selectedId="7" {...PROPS} />)
    expect([...renders.severity.keys()].sort()).toEqual(['L3', 'L7'])
  })

  it('tek satırın yaması yalnız o satırı; toplu seçimde bir kutu yalnız o satırı yeniden çizer', () => {
    const ids = new Set()
    const selection = (set) => ({ ids: set, setMany: noop })
    const { rerender } = render(<IncidentsTable rows={ROWS} selectedId={null} selection={selection(ids)} {...PROPS} />)
    renders.severity.clear()
    const patched = ROWS.map((r) => (r.id === 5 ? { ...r, acknowledged: true } : r))
    rerender(<IncidentsTable rows={patched} selectedId={null} selection={selection(ids)} {...PROPS} />)
    expect([...renders.severity.keys()]).toEqual(['L5'])
    renders.severity.clear()
    rerender(<IncidentsTable rows={patched} selectedId={null} selection={selection(new Set(['9']))} {...PROPS} />)
    expect([...renders.severity.keys()]).toEqual(['L9'])
  })
})
