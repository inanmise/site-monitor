import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, waitFor, act } from './test-utils.jsx'

/**
 * ALARM İLETİMİ ROZETİ — izin kapısı (hata avı 2026-11-06, H3#1): yetki snapshot'ı yüklü ve `alerts.forwards` görüntüleme izni
 * YOKSA `for-monitor` İSTENMEZ, hiçbir şey çizilmez (sunucu her pencere açılışında 403 + ACCESS_DENIED güvenlik denetimi
 * yazıyordu). Snapshot yüklenmemişken ya da kaynağı hiç taşımıyorken (eski sunucu / sağlayıcısız çizim) bugünkü gibi sorulur.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const { perm } = vi.hoisted(() => ({ perm: { current: null } }))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({ alertForwards: { forMonitor: vi.fn() } }),
}))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => perm.current,
  PermissionsProvider: ({ children }) => children,
}))

import { api } from '../api/client'
import MonitorForwardsPill from '../components/forwards/MonitorForwardsPill.jsx'

const snapshot = ({ loaded = true, perms = {} } = {}) => ({
  loaded, failed: false, scope: null, perms, refresh: () => {},
  canView: (r) => Boolean(perms?.[r]?.view), canEdit: (r) => Boolean(perms?.[r]?.edit), canExecute: (r) => Boolean(perms?.[r]?.execute),
})
const pill = () => document.querySelector('[data-slot="monitor-forwards"]')
const respondAdd = () => api.alertForwards.forMonitor.mockResolvedValue({ success: true, data: { items: [], can_add: true,
  owner_teams: [{ id: 3, name: 'Takım A' }], monitor: { type: 'http', id: 7, name: 'www.example.com' } } })

beforeEach(() => {
  vi.clearAllMocks()
  api.alertForwards.forMonitor.mockReset()
  window.history.replaceState({}, '', '/?tab=http')
})

describe('MonitorForwardsPill — izin kapısı', () => {
  it('snapshot yüklü + alerts.forwards görüntüleme izni YOK → istek yok, rozet yok', async () => {
    perm.current = snapshot({ perms: { 'alerts.forwards': { view: false, edit: false }, 'monitoring.read': { view: true } } })
    respondAdd()
    render(<MonitorForwardsPill type="http" id={7} rowLabel="www.example.com" />)
    await act(async () => {})
    expect(api.alertForwards.forMonitor).not.toHaveBeenCalled()
    expect(pill()).toBeNull()
  })

  it('izin VAR → sorulur ve çizilir', async () => {
    perm.current = snapshot({ perms: { 'alerts.forwards': { view: true } } })
    respondAdd()
    render(<MonitorForwardsPill type="http" id={7} rowLabel="www.example.com" />)
    await waitFor(() => expect(pill()).toHaveAttribute('data-state', 'add'))
    expect(api.alertForwards.forMonitor).toHaveBeenCalledTimes(1)
  })

  it('snapshot henüz yüklenmedi → bugünkü gibi sorulur (403 gelirse sunucu kararı rozeti gizler)', async () => {
    perm.current = snapshot({ loaded: false, perms: {} })
    respondAdd()
    render(<MonitorForwardsPill type="http" id={7} rowLabel="www.example.com" />)
    await waitFor(() => expect(api.alertForwards.forMonitor).toHaveBeenCalledTimes(1))
  })

  it('snapshot kaynağı hiç taşımıyor (eski sunucu) → sorulur', async () => {
    perm.current = snapshot({ perms: { 'monitoring.read': { view: true } } })
    respondAdd()
    render(<MonitorForwardsPill type="http" id={7} rowLabel="www.example.com" />)
    await waitFor(() => expect(pill()).toBeTruthy())
  })

  it('yükleme sonrası izin yok çıkarsa önceki rozet kalkar, yeni istek atılmaz', async () => {
    perm.current = snapshot({ loaded: false, perms: {} })
    respondAdd()
    const { rerender } = render(<MonitorForwardsPill type="http" id={7} rowLabel="www.example.com" />)
    await waitFor(() => expect(pill()).toBeTruthy())
    perm.current = snapshot({ perms: { 'alerts.forwards': { view: false } } })
    rerender(<MonitorForwardsPill type="http" id={7} rowLabel="www.example.com" />)
    await act(async () => {})
    expect(pill()).toBeNull()
    expect(api.alertForwards.forMonitor).toHaveBeenCalledTimes(1)
  })
})
