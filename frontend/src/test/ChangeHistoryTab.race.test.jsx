import { render, screen, act, waitFor } from './test-utils.jsx'
import { describe, it, expect, vi, beforeEach } from 'vitest'

/**
 * 2026-09-27 regresyon taraması (release-fixes.md FRONTEND A #6): ChangeHistoryTab `load` sıra korumasızdı.
 * Envanter çekmecesi Alt+←/→ ile kayıttan kayda sekme REMOUNT OLMADAN geçer; A'nın geç dönen geçmişi B'nin
 * altına yazılıyor (ya da B'nin isteği sürerken A'nın yanıtı yükleniyor bayrağını söndürüp A'yı gösteriyordu).
 * Denetimli promise'ler: eski istek YENİSİNDEN SONRA / ÖNCE çözülür, ekranda hep B kalır.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({ monitoring: { getChanges: vi.fn(), getChangeDetail: vi.fn() } }),
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  formatDate: (s) => s ?? '',
}))
import { api } from '../api/client'
import ChangeHistoryTab from '../components/history/ChangeHistoryTab.jsx'

const t = (k, ...a) => (k === 'a11y.rowAction' ? `${a[0]} — ${a[1]}` : a.length ? `${k}:${a.join('|')}` : k)
const row = (seq, ip) => ({
  seq, kind: 'PORT', resource_id: 4, resource_name: 'x', event_type: 'UPDATE', team_id: 5, team_name: 'Kanal',
  actor: 'N23456', actor_name: 'Ada Lovelace', ip_address: ip, user_agent: 'Mozilla/5.0', changes: null, note: null,
  at: '2026-08-22T10:00:00',
})
const deferred = () => { let resolve; const p = new Promise((r) => { resolve = r }); return { p, resolve } }
const flush = () => act(() => new Promise((r) => setTimeout(r, 0)))
const page = (rows) => ({ success: true, data: { changes: rows, total: rows.length, page: 0, size: 25 } })

describe('ChangeHistoryTab — kayıt değişince fetch yarışı', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('A\'nın geçmişi, B\'ye geçildikten sonra dönerse (önce ya da sonra) B\'nin altına YAZILMAZ', async () => {
    const a = deferred(), b = deferred()
    api.monitoring.getChanges.mockImplementation((kind, id) => (id === 4 ? a.p : b.p))
    const { rerender } = render(<ChangeHistoryTab t={t} kind="port" monitorId={4} />)
    await waitFor(() => expect(api.monitoring.getChanges).toHaveBeenCalledWith('port', 4, expect.anything()))
    rerender(<ChangeHistoryTab t={t} kind="port" monitorId={7} />)   // çekmece Alt+→ : remount YOK
    await waitFor(() => expect(api.monitoring.getChanges).toHaveBeenLastCalledWith('port', 7, expect.anything()))

    // ESKİ (A) yanıt önce döner: B hâlâ uçuşta → A'nın satırları çizilmemeli.
    await act(async () => { a.resolve(page([row(1, '10.0.0.1')])) })
    await flush()
    expect(screen.queryAllByText('10.0.0.1')).toHaveLength(0)

    await act(async () => { b.resolve(page([row(2, '10.0.0.2')])) })
    expect((await screen.findAllByText('10.0.0.2')).length).toBeGreaterThan(0)
    expect(screen.queryAllByText('10.0.0.1')).toHaveLength(0)
  })

  it('B önce, A SONRA dönerse ekranda B kalır', async () => {
    const a = deferred(), b = deferred()
    api.monitoring.getChanges.mockImplementation((kind, id) => (id === 4 ? a.p : b.p))
    const { rerender } = render(<ChangeHistoryTab t={t} kind="port" monitorId={4} />)
    await waitFor(() => expect(api.monitoring.getChanges).toHaveBeenCalledWith('port', 4, expect.anything()))
    rerender(<ChangeHistoryTab t={t} kind="port" monitorId={7} />)
    await waitFor(() => expect(api.monitoring.getChanges).toHaveBeenLastCalledWith('port', 7, expect.anything()))

    await act(async () => { b.resolve(page([row(2, '10.0.0.2')])) })
    expect((await screen.findAllByText('10.0.0.2')).length).toBeGreaterThan(0)
    await act(async () => { a.resolve(page([row(1, '10.0.0.1')])) })
    await flush()
    expect(screen.queryAllByText('10.0.0.1')).toHaveLength(0)
    expect(screen.getAllByText('10.0.0.2').length).toBeGreaterThan(0)
  })
})
