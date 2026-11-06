import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from './test-utils.jsx'

/**
 * Ayrıntı penceresi açıkken liste ucu (2026-11-06, performans PF2 madde 8).
 *
 * Sayfa Bütünlüğü, Sayfa Hızı ve Veritabanı Sorgu sayfalarında pencerenin 30 sn'lik sessiz tazelemesi listeyi
 * yeniden çekiyordu; sayfanın kendi liste yoklaması da sürdüğü için açık pencereyle liste ucu dakikada 3 (DB sorgu: 4)
 * kez çağrılıyordu. Artık liste son 30 sn içinde yüklendiyse pencere ucu yeniden çağırmaz; load() açık pencerenin
 * kopyasını da aynı yanıttan tazeler. Liste yoklaması (60 / 30 sn) aynen sürer → liste hiç bayatlamaz, pencere
 * kopyası en çok 30 sn eski (eskisi gibi).
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
import DbQueryMonitorPage from '../components/DbQueryMonitorPage.jsx'

const base = { team_id: 5, team_name: 'Takım A', active: true, checked_at: '2026-09-26T09:00:00', can_edit: true, can_check: true, can_delete: true }
const PAGES = [
  // ad, sayfa, liste ucu, satır, "bozuk" durum
  ['Page', PageMonitorPage, 'getPageMonitors', { id: 1, name: 'p1', url: 'https://p.example.com/', mode: 'SINGLE', status: 'OK', ...base }, 'DOWN'],
  ['PageSpeed', PageSpeedMonitorPage, 'getPageSpeedMonitors', { id: 1, name: 's1', url: 'https://s.example.com/', status: 'OK', breached_metrics: [], ...base }, 'DOWN'],
  ['DbQuery', DbQueryMonitorPage, 'getDbQueryMonitors', { id: 1, name: 'q1', purpose: 'Sipariş sayısı', profile_id: 3, profile: { id: 3, name: 'Sipariş DB', db_type: 'POSTGRESQL', host: 'db.example.com', port: 5432, database_name: 'orders' }, sql_text: 'SELECT 1', rules: [], status: 'OK', breach_level: 'NONE', last_check: '2026-09-26T09:00:00', ...base }, 'FAIL'],
]

/** Penceredeki durum rozetleri (MonitorStatusBadge `data-status`) — kopya tazelenince değişir. */
const statuses = () => [...screen.getByRole('dialog').querySelectorAll('[data-slot="badge"][data-status]')].map((e) => e.getAttribute('data-status'))

describe.each(PAGES)('%s — ayrıntı penceresi açıkken liste ucu', (name, Page, listFn, row, badStatus) => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState(null, '', '/')
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => false })
    api.monitoring[listFn].mockResolvedValue({ success: true, data: [row] })
    vi.useFakeTimers({ shouldAdvanceTime: true })
  })
  afterEach(() => { vi.useRealTimers(); delete document.hidden })

  it('pencere açıkken liste ucu dakikada en çok 2 kez çağrılır; pencere kopyası liste yanıtından tazelenir', async () => {
    const { container } = render(<Page systemRole="ADMIN" teamId={5} teamName="Takım A" myTeams={[{ id: 5, name: 'Takım A' }]} />)
    await waitFor(() => expect(container.querySelectorAll('.upt-grid > [data-slot="card"]')).toHaveLength(1))
    fireEvent.click(container.querySelector('.upt-grid [data-monitor-open]'))
    await screen.findByRole('dialog')
    expect(statuses()).toContain('up')
    expect(statuses()).not.toContain('down')

    // Sunucuda durum bozuldu: bir sonraki liste yanıtı pencereye de yansımalı
    api.monitoring[listFn].mockResolvedValue({ success: true, data: [{ ...row, status: badStatus }] })
    const before = api.monitoring[listFn].mock.calls.length
    await act(async () => { await vi.advanceTimersByTimeAsync(120_000) })
    const calls = api.monitoring[listFn].mock.calls.length - before
    // 2 dakikada beklenen 4 (dakikada 2); eskiden Sayfa / Sayfa Hızı 6, DB sorgu 8
    expect(calls).toBeGreaterThanOrEqual(3)
    expect(calls).toBeLessThanOrEqual(5)
    await waitFor(() => expect(statuses()).toContain('down'))
  }, 30_000)
})
