import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useState } from 'react'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    me: { openAlerts: vi.fn() },
    monitoring: { getOverview: vi.fn(), storm: { status: vi.fn() } },
    getStats: vi.fn(),
    statusPage: { get: vi.fn() },
    noc: { console: vi.fn() },
    teams: { directory: vi.fn() },
    oncall: { current: vi.fn() },
    getActivity: vi.fn(),
    admin: { getWatchdog: vi.fn() },
    dataQuality: { summary: vi.fn() },
  }),
}))

const perm = vi.hoisted(() => ({ value: {} }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({
    perms: perm.value, canView: (r) => !!perm.value[r]?.view, canEdit: () => false, canExecute: () => false,
    refresh: () => {}, loaded: true, failed: false,
  }),
  PermissionsProvider: ({ children }) => children,
}))

import { api } from '../api/client'
import { EN } from '../i18n/en.js'
import { UserPrefsContext } from '../hooks/useUserPrefs.js'
import MyDashPage from '../components/mydash/MyDashPage.jsx'

/**
 * Grafana tarzı ızgaralar (2026-10-13): hizmet durum ızgarası, izleme türü ısı haritası, takım × tür matrisi. Hücreler dolu
 * renkte ve durumu METİNLE de söyler; tıklama süzülmüş ekranı açar (düzenleme / TV'de değil); bilinmeyen gri, yeşil değil;
 * TV'de renkli panele dönmezler (ızgara kalır). Adlar sahte, gerçek uzunlukta.
 */
const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'
const PERMS = { 'monitoring.read': { view: true } }

const setSpy = vi.fn()
const setterRef = { current: null }
function Shell({ initial = null, children }) {
  const [dashboard, setDashboard] = useState(initial)
  setterRef.current = setDashboard
  return <UserPrefsContext.Provider value={{ ready: true, status: 'ready', prefs: {}, dashboard, setDashboard: setSpy }}>{children}</UserPrefsContext.Provider>
}
const layout = (...widgets) => ({ v: 1, widgets: widgets.map(([type, size = 'md']) => ({ id: type.replace(/_/g, '-'), type, size })) })
const widget = (type) => document.querySelector(`[data-slot="mydash-widget"][data-type="${type}"]`)

const mon = (team_id, team_name, type, status, extra = {}) => ({ id: Math.random(), team_id, team_name, type, status, active: status !== 'paused', ...extra })
const OVERVIEW = { totals: { active: 9 }, types: [
  { type: 'http', total: 4, active: 4, down: 1, stale: 0, unknown: 0 },
  { type: 'ping', total: 3, active: 3, down: 0, stale: 1, unknown: 0 },
  { type: 'dns', total: 2, active: 2, down: 0, stale: 0, unknown: 0 },
  { type: 'port', total: 0 }],
monitors: [
  mon(5, LONG_TEAM, 'http', 'down'), mon(5, LONG_TEAM, 'http', 'up'), mon(5, LONG_TEAM, 'ping', 'up'),
  mon(6, 'Ödeme Sistemleri', 'ping', 'stale'), mon(6, 'Ödeme Sistemleri', 'dns', 'up'),
  mon(null, null, 'http', 'up'), mon(7, 'Kart', 'dns', 'up'), mon(7, 'Kart', 'http', 'up'),
  mon(8, 'Silinen', 'ping', 'down', { deleted: true }), mon(42, null, 'dns', 'up')] }

let navs = []
const onNav = (e) => navs.push(e.detail)

beforeEach(() => {
  vi.clearAllMocks()
  navs = []
  window.addEventListener('sm:navigate', onNav)
  setSpy.mockImplementation((v) => { setterRef.current?.(v); return true })
  window.history.replaceState({}, '', '/?tab=mydash')
  perm.value = PERMS
  api.monitoring.getOverview.mockResolvedValue({ success: true, data: OVERVIEW })
  api.statusPage.get.mockResolvedValue({ success: true, data: { services: [
    { key: '6|ödeme', name: 'Ödeme Geçidi', team_id: 6, team_name: 'Ödeme Sistemleri', state: 'operational', monitors_visible: true },
    { key: '5|kanal', name: 'Kurumsal İnternet Bankacılığı Ödeme ve Fatura Tahsilat Hizmetleri', team_id: 5, team_name: LONG_TEAM, state: 'partial_outage', monitors_visible: true },
    { key: '9|başka', name: 'Başka takımın hizmeti', team_id: 9, team_name: 'Başka', state: 'degraded', monitors_visible: false },
    { key: '6|', name: null, ungrouped: true, team_id: 6, team_name: 'Ödeme Sistemleri', state: 'no_data', monitors_visible: true }] } })
})
afterEach(() => {
  window.removeEventListener('sm:navigate', onNav)
  window.history.replaceState({}, '', '/')
})

describe('Hizmet durum ızgarası', () => {
  it('en kötü önce, dolu renk + durum metni; sorunlu (görülebilen) hizmet takımın sorunlu izlemelerini, diğerleri Durum Sayfası\'nı açar', async () => {
    render(<Shell initial={layout(['service_grid'])}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('service_grid').querySelectorAll('[data-slot="mydash-grid-service"]')).toHaveLength(4))
    const tiles = [...widget('service_grid').querySelectorAll('[data-slot="mydash-grid-service"]')]
    expect(tiles.map((x) => x.getAttribute('data-state'))).toEqual(['partial_outage', 'degraded', 'operational', 'no_data'])
    expect(tiles[0]).toHaveTextContent('Kurumsal İnternet Bankacılığı Ödeme ve Fatura Tahsilat Hizmetleri')
    expect(tiles[0]).toHaveTextContent(LONG_TEAM)
    expect(tiles[0]).toHaveTextContent(EN['sp.state.partial_outage'])
    expect(tiles[3]).toHaveTextContent(EN['mydash.status.ungrouped'])
    expect(tiles[3].querySelector('[data-tone]')).toHaveAttribute('data-tone', 'muted')
    fireEvent.click(within(tiles[0]).getByRole('button'))
    expect(navs.at(-1)).toEqual({ tab: 'monitoring', params: { mo_team: '5', mo_status: 'down,stale' } })
    // Başka takımın hizmeti: izlemeleri görülemez → süzgeç boş çıkardı, Durum Sayfası açılır
    fireEvent.click(within(tiles[1]).getByRole('button'))
    expect(navs.at(-1)).toEqual({ tab: 'status', params: undefined })
    expect(widget('service_grid')).toHaveAttribute('data-tone', 'danger')
    // inceleme: üzerine gelince ton korunur (ghost düğmenin gri vurgusu yok)
    const btn = within(tiles[0]).getByRole('button')
    expect(btn.className).toMatch(/hover:bg-red-800/)
    expect(btn.className).not.toMatch(/hover:bg-accent/)
  })

  it('inceleme: izleme okuma izni yoksa sorunlu hizmet bile İzleme Panosu\'na değil Durum Sayfası\'na götürür (403 sayfası yok)', async () => {
    perm.value = {}
    render(<Shell initial={layout(['service_grid'])}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('service_grid').querySelectorAll('[data-slot="mydash-grid-service"]')).toHaveLength(4))
    fireEvent.click(within(widget('service_grid').querySelector('[data-slot="mydash-grid-service"]')).getByRole('button'))
    expect(navs.at(-1)).toEqual({ tab: 'status', params: undefined })
  })
})

describe('İzleme türü ısı haritası', () => {
  it('izlemesi olan türler, en kötü durumun renginde ve metniyle; tıklama türü (sorunluysa durumu) süzer', async () => {
    render(<Shell initial={layout(['type_heatmap', 'sm'])}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('type_heatmap').querySelectorAll('[data-slot="mydash-heat-cell"]')).toHaveLength(3))
    const cells = [...widget('type_heatmap').querySelectorAll('[data-slot="mydash-heat-cell"]')]
    expect(cells.map((c) => c.getAttribute('data-type'))).toEqual(['http', 'ping', 'dns'])
    expect(cells.map((c) => c.querySelector('[data-tone]').getAttribute('data-tone'))).toEqual(['danger', 'warning', 'success'])
    expect(cells[0]).toHaveTextContent('1 failing')
    expect(cells[2]).toHaveTextContent(EN['mydash.heat.allUp'])
    fireEvent.click(within(cells[0]).getByRole('button'))
    expect(navs.at(-1)).toEqual({ tab: 'monitoring', params: { mo_type: 'http', mo_status: 'down' } })
    fireEvent.click(within(cells[2]).getByRole('button'))
    expect(navs.at(-1)).toEqual({ tab: 'monitoring', params: { mo_type: 'dns' } })
  })

  it('izleme okuma yetkisi yoksa kutu çizilmez; izleme yoksa gri "izleme yok" (yeşil değil)', async () => {
    perm.value = {}
    const { unmount } = render(<Shell initial={layout(['type_heatmap', 'sm'], ['service_grid'])}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('service_grid')).not.toBeNull())
    expect(widget('type_heatmap')).toBeNull()
    unmount()
    perm.value = PERMS
    api.monitoring.getOverview.mockResolvedValue({ success: true, data: { types: [], monitors: [] } })
    render(<Shell initial={layout(['type_heatmap', 'sm'])}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('type_heatmap')).toHaveTextContent(EN['mydash.heat.none']))
    expect(widget('type_heatmap')).toHaveAttribute('data-tone', 'muted')
  })
})

describe('Takım × izleme türü matrisi', () => {
  it('silinmiş izleme sayılmaz; en kötü takım önce, takımsız en sonda; hücre takım + tür (+ durum) süzer; lejant var', async () => {
    render(<Shell initial={layout(['team_matrix', 'lg'])}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('team_matrix').querySelectorAll('[data-slot="mydash-matrix-row"]')).toHaveLength(5))
    const rows = [...widget('team_matrix').querySelectorAll('[data-slot="mydash-matrix-row"]')]
    expect(rows.map((r) => r.getAttribute('data-team'))).toEqual(['5', '6', '42', '7', '__none'])
    expect(rows[0]).toHaveTextContent(LONG_TEAM)
    expect(rows[4]).toHaveTextContent(EN['mydash.matrix.noTeam'])
    // Adı okunamayan takım çıplak kimlikle değil açıklamayla
    expect(rows[2]).toHaveTextContent('Team #42 (name unavailable)')
    expect(widget('team_matrix')).not.toHaveTextContent('Silinen')
    const headers = within(widget('team_matrix').querySelector('[data-slot="mydash-matrix"]')).getAllByRole('columnheader')
    expect(headers).toHaveLength(4)   // takım + http + ping + dns
    const httpCell = within(rows[0]).getByRole('button', { name: new RegExp(`${EN['nav.http'].replace(/[/]/g, '\\/')}: 1 failing`) })
    expect(httpCell).toHaveTextContent('1')
    expect(httpCell).toHaveAttribute('data-tone', 'danger')
    fireEvent.click(httpCell)
    expect(navs.at(-1)).toEqual({ tab: 'monitoring', params: { mo_team: '5', mo_type: 'http', mo_status: 'down' } })
    // Takımın o türde izlemesi yoksa boş hücre (tıklanmaz)
    expect(within(rows[0]).getAllByRole('cell').some((c) => c.textContent === '—')).toBe(true)
    // Sağlıklı hücre: sayı = süzülmüş listedeki sağlıklı izleme sayısı (bağlantı mo_status=up)
    fireEvent.click(within(rows[0]).getByRole('button', { name: new RegExp(`${EN['nav.ping']}: ${EN['mydash.heat.allUp']}`) }))
    expect(navs.at(-1)).toEqual({ tab: 'monitoring', params: { mo_team: '5', mo_type: 'ping', mo_status: 'up' } })
    expect(widget('team_matrix').querySelector('[data-slot="mydash-matrix-legend"]')).not.toBeNull()
    // Dar kap kartları da aynı satırları taşır
    expect(widget('team_matrix').querySelectorAll('[data-slot="mydash-matrix-card"]')).toHaveLength(5)
  })

  it('düzenleme kipinde hücreler tıklanmaz; her bağlantı düğmesi data-slot taşır (kenarlık sıfırlaması)', async () => {
    render(<Shell initial={layout(['team_matrix', 'lg'], ['type_heatmap', 'sm'], ['service_grid'])}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('team_matrix').querySelectorAll('[data-slot="mydash-matrix-row"]')).toHaveLength(5))
    await waitFor(() => expect(widget('service_grid').querySelectorAll('[data-slot="mydash-grid-service"]')).toHaveLength(4))
    const links = [...document.querySelectorAll('[data-link="true"]')]
    expect(links.length).toBeGreaterThan(10)
    expect(links.filter((b) => !b.hasAttribute('data-slot')).map((b) => b.textContent)).toEqual([])
    fireEvent.click(screen.getByRole('button', { name: /^(Edit|Düzenle)$/ }))
    await waitFor(() => expect(widget('team_matrix').querySelectorAll('[data-slot="mydash-matrix-row"] button')).toHaveLength(0))
    // Izgaralar renkli panel görünümü sunmaz (zaten renkli)
    expect(widget('team_matrix').querySelector('[data-slot="mydash-style"]')).toBeNull()
    expect(widget('service_grid').querySelector('[data-slot="mydash-style"]')).toBeNull()
  })

  it('TV kipinde ızgaralar renkli panele dönmez — ızgara olarak kalır', async () => {
    window.history.replaceState({}, '', '/?tab=mydash&md_tv=1')
    render(<Shell initial={layout(['team_matrix', 'lg'], ['service_grid'])}><MyDashPage /></Shell>)
    const tv = await screen.findByRole('dialog')
    await waitFor(() => expect(tv.querySelectorAll('[data-slot="mydash-grid-service"]').length).toBeGreaterThan(0))
    expect(tv.querySelector('[data-slot="mydash-widget"][data-stat="on"]')).toBeNull()
  })
})
