import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from './test-utils.jsx'

/**
 * Performans (2026-11-17): Sayfa Hızı `/resources` ve Sayfa Bütünlüğü `/issues` ayrıntı pencerelerinin 30 sn'lik sessiz
 * tazelemesi bu uçları sekme açık olsun olmasın, yeni ölçüm olsun olmasın yeniden okuyordu. Artık yalnız sekme
 * GÖRÜNÜRKEN ve izlemenin son ölçüm zamanı değiştiyse okunur; aksi hâlde eldeki veri kalır. Sekmeye dönüldüğünde yeni ölçüm
 * geldiyse hemen okunur.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({ monitoring: {}, admin: {} }),
}))
import { api } from '../api/client'
import PageMonitorPage from '../components/PageMonitorPage.jsx'
import PageSpeedMonitorPage from '../components/PageSpeedMonitorPage.jsx'

const base = { team_id: 5, team_name: 'Takım A', active: true, can_edit: true, can_check: true, can_delete: true }
const CASES = [
  // ad, sayfa, liste ucu, sekme ucu, satır, son ölçüm alanı, veri sekmesi adı
  ['PageSpeed', PageSpeedMonitorPage, 'getPageSpeedMonitors', 'getPageSpeedResources',
    { id: 1, name: 's1', url: 'https://s.example.com/', status: 'OK', breached_metrics: [], last_check: '2026-09-26T09:00:00', ...base },
    'last_check', /^(Resources|Kaynaklar)/],
  ['Page', PageMonitorPage, 'getPageMonitors', 'getPageIssues',
    { id: 1, name: 'p1', url: 'https://p.example.com/', mode: 'SINGLE', status: 'OK', checked_at: '2026-09-26T09:00:00', ...base },
    'checked_at', /^(Issues|Sorunlar)/],
]
const tab = (name) => fireEvent.mouseDown(screen.getByRole('tab', { name }), { button: 0 })

describe.each(CASES)('%s — ayrıntı sekmesi yalnız görünürken ve yeni ölçümde tazelenir', (name, Page, listFn, tabFn, row, lastKey, dataTab) => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState(null, '', '/')
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => false })
    api.monitoring[listFn].mockResolvedValue({ success: true, data: [row] })
    api.monitoring[tabFn].mockResolvedValue({ success: true, data: tabFn === 'getPageIssues' ? [] : { resources: [], total: 0, breaches: [] } })
    vi.useFakeTimers({ shouldAdvanceTime: true })
  })
  afterEach(() => { vi.useRealTimers(); delete document.hidden })

  async function openDetail() {
    const r = render(<Page systemRole="ADMIN" teamId={5} teamName="Takım A" myTeams={[{ id: 5, name: 'Takım A' }]} />)
    await waitFor(() => expect(r.container.querySelectorAll('.upt-grid > [data-slot="card"]')).toHaveLength(1))
    fireEvent.click(r.container.querySelector('.upt-grid [data-monitor-open]'))
    await screen.findByRole('dialog')
    await waitFor(() => expect(api.monitoring[tabFn]).toHaveBeenCalledTimes(1))
    return r
  }

  it('aynı ölçüm: sekme görünürken de 2 dakika boyunca yeniden okunmaz', async () => {
    await openDetail()
    await act(async () => { await vi.advanceTimersByTimeAsync(120_000) })
    expect(api.monitoring[tabFn]).toHaveBeenCalledTimes(1)        // eskiden 30 sn'de bir (+4)
  }, 30_000)

  it('başka sekmedeyken hiç okunmaz; yeni ölçüm geldiyse sekmeye dönünce hemen bir kez okunur', async () => {
    await openDetail()
    tab(/^(Notes|Notlar)/)
    api.monitoring[listFn].mockResolvedValue({ success: true, data: [{ ...row, [lastKey]: '2026-09-26T09:30:00' }] })
    await act(async () => { await vi.advanceTimersByTimeAsync(120_000) })
    expect(api.monitoring[tabFn]).toHaveBeenCalledTimes(1)
    tab(dataTab)
    await waitFor(() => expect(api.monitoring[tabFn]).toHaveBeenCalledTimes(2))
    await act(async () => { await vi.advanceTimersByTimeAsync(60_000) })
    expect(api.monitoring[tabFn]).toHaveBeenCalledTimes(2)
  }, 30_000)

  it('sekme görünürken yeni ölçüm gelince bir sonraki tazelemede okunur; okuma başarısızsa yeniden denenir', async () => {
    await openDetail()
    api.monitoring[listFn].mockResolvedValue({ success: true, data: [{ ...row, [lastKey]: '2026-09-26T09:30:00' }] })
    api.monitoring[tabFn].mockResolvedValue({ success: false, error: 'x' })
    await act(async () => { await vi.advanceTimersByTimeAsync(65_000) })
    const afterFail = api.monitoring[tabFn].mock.calls.length
    expect(afterFail).toBeGreaterThanOrEqual(2)
    api.monitoring[tabFn].mockResolvedValue({ success: true, data: tabFn === 'getPageIssues' ? [] : { resources: [], total: 0, breaches: [] } })
    await act(async () => { await vi.advanceTimersByTimeAsync(35_000) })
    const afterOk = api.monitoring[tabFn].mock.calls.length
    expect(afterOk).toBe(afterFail + 1)                              // başarısız okuma bir kez daha denendi
    await act(async () => { await vi.advanceTimersByTimeAsync(65_000) })
    expect(api.monitoring[tabFn].mock.calls.length).toBe(afterOk)    // başarılı okumadan sonra aynı ölçümde durur
  }, 30_000)
})
