import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from './test-utils.jsx'
import UptimePage from '../components/UptimePage.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({ monitoring: { getUptimeOverview: vi.fn() } }),
}))
import { api } from '../api/client'

/**
 * 2026-09-27 regresyon taraması (release-fixes.md FRONTEND A #2): `fetchOverview(scope)` sıra korumasızdı —
 * "Tüm takımlar" → "Takımlarım" hızlı geçişinde GEÇ dönen "all" yanıtı "mine" listesini eziyor, anahtar "Takımlarım"
 * derken başka takımların kartları görünüyordu. Denetimli promise'ler: eski istek YENİSİNDEN SONRA çözülür.
 */
const base = { status: 'up', http_ok: true, uptime_7d: 100, uptime_30d: 100, incidents_1d: 0, incidents_7d: 0, incidents_30d: 0, checked_at: '2026-06-24T00:00:00' }
const own = { ...base, domain: 'own.example.com', team_id: 5, team_name: 'Takım A', can_manage: true }
const foreign = { ...base, domain: 'foreign.example.com', team_id: 9, team_name: 'Takım B', can_manage: false }
const deferred = () => { let resolve; const p = new Promise((r) => { resolve = r }); return { p, resolve } }
const flush = () => act(() => new Promise((r) => setTimeout(r, 0)))

describe('UptimePage — kapsam geçişi fetch yarışı', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    window.history.replaceState(null, '', '/')
  })

  it('"Tüm takımlar" yoklama yanıtı "Takımlarım"a geçildikten SONRA gelirse listeyi ezmez', async () => {
    localStorage.setItem('uptime-scope', 'all')
    const allPoll = deferred(), mine = deferred()
    let allCalls = 0
    api.monitoring.getUptimeOverview.mockImplementation((scope) => {
      if (scope === 'mine') return mine.p
      allCalls += 1
      return allCalls === 1 ? Promise.resolve({ success: true, data: [own, foreign], scope: 'all', visible_to_all: true }) : allPoll.p
    })
    render(<UptimePage systemRole="ADMIN" teamId={5} teamName="Takım A" />)
    await screen.findByText('foreign.example.com')

    // Görünürlük tazelemesi (60 sn yoklamayla aynı yol) "all" isteğini uçurur — sessiz, ekran etkileşimli kalır.
    act(() => { document.dispatchEvent(new Event('visibilitychange')) })
    await waitFor(() => expect(allCalls).toBe(2))
    // Kullanıcı o sırada "Takımlarım"a geçer.
    fireEvent.click(screen.getByRole('button', { name: /takımlarım|my teams/i }))
    await waitFor(() => expect(api.monitoring.getUptimeOverview).toHaveBeenLastCalledWith('mine'))

    // Yeni ("mine") yanıt önce, eski ("all") yanıt SONRA döner.
    await act(async () => { mine.resolve({ success: true, data: [own], scope: 'mine', visible_to_all: true }) })
    await screen.findByText('own.example.com')
    await flush()
    await act(async () => { allPoll.resolve({ success: true, data: [own, foreign], scope: 'all', visible_to_all: true }) })
    await flush()

    expect(screen.getByText('own.example.com')).toBeInTheDocument()
    expect(screen.queryByText('foreign.example.com')).toBeNull()
    expect(screen.getByRole('button', { name: /takımlarım|my teams/i })).toHaveAttribute('aria-pressed', 'true')
  })
})
