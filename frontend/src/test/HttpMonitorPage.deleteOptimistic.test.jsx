import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import HttpMonitorPage from '../components/HttpMonitorPage.jsx'
import { __resetDeletedMarks } from '../utils/recentlyDeleted.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate:    (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      listGroups:        vi.fn(() => Promise.resolve({ success: true, data: [] })),
      monitorDefaults:   vi.fn(() => Promise.resolve({ success: true, data: { http: {} } })),
      getHttpMonitors:   vi.fn(),
      getCheckHistory:   vi.fn(),
      getCheckHistoryCsvUrl: vi.fn(() => '#'),
      updateHttpMonitor: vi.fn(),
      deleteHttpMonitor: vi.fn(),
    },
    admin: { getTeams: vi.fn(), getAlerts: vi.fn() },
  }),
}))
import { api } from '../api/client'

/**
 * Silme ANINDA yansır (2026-10-07, kullanıcı: "kart aniden yok olmuyor, refresh olmayı bekliyor"). Başarılı silmede kart
 * tam liste yüklemesini BEKLEMEDEN düşer, açık detay kapanır, liste arka planda tazelenir; tazeleme silmeden ÖNCE
 * başlamış/bayat bir yanıt dönse bile kart geri gelmez (utils/recentlyDeleted işareti). Başarısız silmede kart kalır.
 */
const mk = (id, name, host) => ({
  id, name, url: `https://${host}/`, method: 'GET', expected_status: '200', group_name: 'Grup', tags: 'prod',
  team_id: 5, team_name: 'SY-A', status: 'up', http_status: 200, response_ms: 12, interval_seconds: 600, timeout_ms: 7000,
  active: true, checked_at: '2026-10-07T09:00:00',
})
const A = mk(1, 'Alpha', 'a.example.com')
const B = mk(2, 'Beta', 'b.example.com')
const C = mk(3, 'Gamma', 'c.example.com')
const ALL = [A, B, C]

const esc = (s) => s.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')
const titleRe = (url) => new RegExp(`^${esc(url)} — (open details|detayları aç)$`)
const cardTitle = (url) => [...document.querySelectorAll('[data-monitor-open]')].find((b) => titleRe(url).test(b.getAttribute('aria-label') || '')) || null
const cardOf = async (url) => (await waitFor(() => { const el = cardTitle(url); if (!el) throw new Error(`kart yok: ${url}`); return el })).closest('[data-slot="card"]')
const deferred = () => { let resolve; const p = new Promise((r) => { resolve = r }); return { p, resolve } }
const flush = () => act(() => new Promise((r) => setTimeout(r, 0)))

describe('HttpMonitorPage — silme anında yansır', () => {
  let reloads
  beforeEach(() => {
    vi.clearAllMocks()
    __resetDeletedMarks()
    reloads = []
    // İlk yükleme hemen döner; sonraki her yükleme (silme sonrası tazeleme) testin elinde bekler.
    api.monitoring.getHttpMonitors.mockReset()
    api.monitoring.getHttpMonitors.mockResolvedValueOnce({ success: true, data: ALL })
      .mockImplementation(() => { const d = deferred(); reloads.push(d); return d.p })
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [], counts: { total: 0, fail: 0 }, buckets: [], alerts: [],
      range: { from: '2026-10-06T00:00:00', to: '2026-10-07T00:00:00' }, total: 0, page: 0, size: 50 } })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'SY-A' }] })
    api.monitoring.deleteHttpMonitor.mockResolvedValue({ success: true, data: { id: 2 } })
  })

  const mount = () => render(<HttpMonitorPage systemRole="ADMIN" teamId={5} teamName="SY-A" />)
  const resolveStaleReloads = async () => {
    for (const d of reloads) await act(async () => { d.resolve({ success: true, data: ALL }) })
    await flush()
  }

  it('karttan tekil silme: KALICI onay (ad + danger), kart tazeleme beklenmeden düşer, bayat yanıt geri getirmez', async () => {
    mount()
    const card = await cardOf(B.url)
    fireEvent.click(within(card).getByRole('button', { name: `${B.url} — Delete` }))
    const dlg = await screen.findByRole('dialog', { name: 'Delete monitor permanently' })
    expect(dlg).toHaveTextContent(/Beta will be permanently deleted/)
    expect(dlg).toHaveTextContent(/can't be undone/)
    fireEvent.click(within(dlg).getByRole('button', { name: 'Delete' }))

    await waitFor(() => expect(api.monitoring.deleteHttpMonitor).toHaveBeenCalledWith(2))
    await waitFor(() => expect(cardTitle(B.url)).toBeNull())
    // Tazeleme başladı ama HENÜZ dönmedi — kart yine de yok
    expect(reloads.length).toBeGreaterThan(0)
    expect(cardTitle(A.url)).not.toBeNull()
    expect(cardTitle(C.url)).not.toBeNull()

    await resolveStaleReloads()   // silinmiş satırı hâlâ taşıyan (bayat) liste
    expect(cardTitle(B.url)).toBeNull()
    expect(cardTitle(A.url)).not.toBeNull()
  })

  it('açık detaydan silme: detay kapanır, kart anında düşer', async () => {
    mount()
    fireEvent.click((await cardOf(B.url)).querySelector('[data-monitor-open]'))
    const detail = await screen.findByRole('dialog')
    fireEvent.click(within(detail).getByRole('button', { name: 'Delete' }))
    const dlg = await screen.findByRole('dialog', { name: 'Delete monitor permanently' })
    fireEvent.click(within(dlg).getByRole('button', { name: 'Delete' }))

    await waitFor(() => expect(api.monitoring.deleteHttpMonitor).toHaveBeenCalledWith(2))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(cardTitle(B.url)).toBeNull()
    await resolveStaleReloads()
    expect(cardTitle(B.url)).toBeNull()
  })

  it('başarısız silme: kart kalır, işaretlenmez', async () => {
    api.monitoring.deleteHttpMonitor.mockResolvedValue({ success: false, error: 'Yetki yok' })
    mount()
    const card = await cardOf(B.url)
    fireEvent.click(within(card).getByRole('button', { name: `${B.url} — Delete` }))
    fireEvent.click(within(await screen.findByRole('dialog', { name: 'Delete monitor permanently' })).getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(api.monitoring.deleteHttpMonitor).toHaveBeenCalledWith(2))
    await flush()
    expect(cardTitle(B.url)).not.toBeNull()
    expect(reloads.length).toBe(0)
  })

  it('toplu silme: onay adları sayar, başarılı silinenler tazeleme beklenmeden düşer; bayat yanıt geri getirmez', async () => {
    mount()
    await cardOf(A.url)
    fireEvent.click(screen.getByRole('checkbox', { name: `Select ${A.url} for bulk action` }))
    fireEvent.click(screen.getByRole('checkbox', { name: `Select ${C.url} for bulk action` }))
    const bar = await screen.findByRole('region', { name: 'Bulk actions' })
    fireEvent.click(within(bar).getByRole('button', { name: 'Delete' }))
    const dlg = await screen.findByRole('dialog', { name: 'Permanently delete the selected monitors' })
    expect(dlg).toHaveTextContent(/2 monitor\(s\) will be permanently deleted: Alpha, Gamma/)
    fireEvent.click(within(dlg).getByRole('button', { name: 'Delete permanently' }))

    await waitFor(() => expect(api.monitoring.deleteHttpMonitor).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(cardTitle(A.url)).toBeNull())
    expect(cardTitle(C.url)).toBeNull()
    expect(cardTitle(B.url)).not.toBeNull()

    await resolveStaleReloads()
    expect(cardTitle(A.url)).toBeNull()
    expect(cardTitle(C.url)).toBeNull()
    expect(cardTitle(B.url)).not.toBeNull()
  })
})
