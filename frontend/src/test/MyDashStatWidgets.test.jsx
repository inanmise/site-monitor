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

const perm = vi.hoisted(() => ({ value: {}, scope: null }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({
    perms: perm.value, scope: perm.scope, canView: (r) => !!perm.value[r]?.view, canEdit: () => false, canExecute: () => false,
    refresh: () => {}, loaded: true, failed: false,
  }),
  PermissionsProvider: ({ children }) => children,
}))

import { api } from '../api/client'
import { EN } from '../i18n/en.js'
import { UserPrefsContext } from '../hooks/useUserPrefs.js'
import MyDashPage from '../components/mydash/MyDashPage.jsx'
import { STAT_TYPES } from '../components/mydash/statModel.js'

/**
 * Renkli panel kutuları (2026-10-13, kullanıcı isteği: "renkli panel adetlerini arttıralım"): her zaman renkli panel,
 * görünüm düğmesi yok; bilinmeyen veri boyanmaz ("—"); aynı kaynağı okuyan paneller TEK istek; "Kutu ekle"de ayrı grup ve
 * "Renkli panel duvarı" hazır düzeni.
 */
const PERMS = { 'alerts.read': { view: true }, 'monitoring.read': { view: true }, 'incidents.view': { view: true } }
const setSpy = vi.fn()
const setterRef = { current: null }
function Shell({ initial = null, children }) {
  const [dashboard, setDashboard] = useState(initial)
  setterRef.current = setDashboard
  return <UserPrefsContext.Provider value={{ ready: true, status: 'ready', prefs: {}, dashboard, setDashboard: setSpy }}>{children}</UserPrefsContext.Provider>
}
const layout = (...types) => ({ v: 1, widgets: types.map((type) => ({ id: type.replace(/_/g, '-'), type, size: 'sm' })) })
const widget = (type) => document.querySelector(`[data-slot="mydash-widget"][data-type="${type}"]`)
const ids = () => [...document.querySelectorAll('[data-slot="mydash-widget"]')].map((el) => el.getAttribute('data-type'))

let navs = []
const onNav = (e) => navs.push(e.detail)
beforeEach(() => {
  vi.clearAllMocks()
  navs = []
  window.addEventListener('sm:navigate', onNav)
  setSpy.mockImplementation((v) => { setterRef.current?.(v); return true })
  window.history.replaceState({}, '', '/?tab=mydash')
  perm.value = PERMS
  perm.scope = null
  api.me.openAlerts.mockResolvedValue({ success: true, data: { total: 3, tabs: { http: { count: 3, unacked: 2, levels: { critical: 2, high: 1, warning: 0, other: 0 }, items: [] } } } })
  api.getStats.mockResolvedValue({ success: true, data: { total_certificates: 40, valid_count: 33, expired: 1, expiring_in_7_days: 2, expiring_in_30_days: 4, error_count: 0 } })
  api.monitoring.getOverview.mockResolvedValue({ success: true, data: { totals: { active: 20, down: 0, stale: 0, success_rate_window: 99.4 }, types: [], monitors: [] } })
  api.statusPage.get.mockResolvedValue({ success: true, data: { services: [] } })
})
afterEach(() => {
  window.removeEventListener('sm:navigate', onNav)
  window.history.replaceState({}, '', '/')
})

describe('Renkli panel kutuları', () => {
  it('her zaman renkli panel: kritik alarm kırmızı zeminde büyük sayıyla; tıklama süzülmüş listeyi açar; görünüm düğmesi yok', async () => {
    render(<Shell initial={layout('stat_alarms_critical', 'stat_success_rate')}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('stat_alarms_critical')).toHaveAttribute('data-stat', 'on'))
    expect(widget('stat_alarms_critical').querySelector('[data-slot="mydash-stat-value"]')).toHaveTextContent('2')
    expect(widget('stat_alarms_critical')).toHaveTextContent(EN['mydash.st.stat_alarms_critical'])
    expect(widget('stat_alarms_critical')).toHaveTextContent('+1 high')
    expect(widget('stat_alarms_critical').querySelector('[data-slot="card"]').className).toMatch(/bg-red-700/)
    // Durum metinle de (yalnız renk değil)
    expect(widget('stat_alarms_critical').querySelector('[data-slot="mydash-widget-tone"]')).toHaveTextContent(EN['mydash.st.tone.danger'])
    await waitFor(() => expect(widget('stat_success_rate').querySelector('[data-slot="mydash-stat-value"]')).toHaveTextContent('99.4%'))
    expect(widget('stat_success_rate').querySelector('[data-slot="card"]').className).toMatch(/bg-green-700/)
    fireEvent.click(widget('stat_alarms_critical').querySelector('[data-slot="mydash-stat"]'))
    expect(navs.at(-1)).toEqual({ tab: 'alerthistory', params: { level: 'CRITICAL' } })
    fireEvent.click(screen.getByRole('button', { name: /^(Edit|Düzenle)$/ }))
    expect(widget('stat_alarms_critical').querySelector('[data-slot="mydash-style"]')).toBeNull()
  })

  it('inceleme: 7/24 geciken arama kutusunun "sayfayı aç" bağlantısı konsolu açar (yönetici için varsayılan Kapsam)', async () => {
    perm.scope = { monitoring_all: true, noc_operator: false, noc_console: true }
    api.noc.console.mockResolvedValue({ success: true, data: { total: 0, items: [] } })
    render(<Shell initial={layout('stat_noc_overdue')}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('stat_noc_overdue')).toHaveAttribute('data-tone', 'success'))
    fireEvent.click(widget('stat_noc_overdue').querySelector('[data-slot="mydash-open"]'))
    expect(navs.at(-1)).toEqual({ tab: 'noc', params: { n_view: 'console', n_cnoc: 'sent', n_ccall: 'no', n_cst: 'open' } })
  })

  it('verisi olmayan gösterge boyanmaz: "—" + "veri yok" (yeşil değil)', async () => {
    render(<Shell initial={layout('stat_service_outages')}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('stat_service_outages').querySelector('[data-slot="mydash-stat-value"]')).toHaveTextContent('—'))
    expect(widget('stat_service_outages')).not.toHaveAttribute('data-stat')
    expect(widget('stat_service_outages')).toHaveTextContent(EN['mydash.st.noData'])
    expect(widget('stat_service_outages').querySelector('[data-slot="card"]').className).not.toMatch(/bg-green-700/)
  })

  it('aynı kaynağı okuyan dört sertifika paneli TEK istek paylaşır', async () => {
    render(<Shell initial={layout('stat_certs_expired', 'stat_certs_week', 'stat_certs_month', 'stat_cert_errors')}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('stat_certs_month').querySelector('[data-slot="mydash-stat-value"]')).toHaveTextContent('4'))
    expect(widget('stat_certs_expired')).toHaveAttribute('data-tone', 'danger')
    expect(widget('stat_cert_errors')).toHaveAttribute('data-tone', 'success')
    expect(api.getStats).toHaveBeenCalledTimes(1)
  })

  it('kanal raporu paneli (R14): dönem özeti ucunu okur (genel bakışı DEĞİL); başlanmamış sayısı turuncu zeminde, alt satır gönderilen / kanal; tıklama kanal listesini "başlanmamış" süzgeciyle açar; "sayfayı aç" da', async () => {
    perm.value = { ...PERMS, 'tech_error_reports.read': { view: true } }
    api.techErrors.periodSummary.mockResolvedValue({ success: true, data: { period: { year: 2026, month: 9 },
      active: 3, sent: 1, open: 0, missing: 2, inactive: 1 } })
    render(<Shell initial={layout('stat_channel_reports_missing')}><MyDashPage /></Shell>)
    const w = () => widget('stat_channel_reports_missing')
    await waitFor(() => expect(w()).toHaveAttribute('data-stat', 'on'))
    expect(w().querySelector('[data-slot="mydash-stat-value"]')).toHaveTextContent('2')
    expect(w()).toHaveAttribute('data-tone', 'warning')
    expect(w().querySelector('[data-slot="card"]').className).toMatch(/bg-amber-/)
    expect(w()).toHaveTextContent(EN['mydash.st.stat_channel_reports_missing'])
    expect(w()).toHaveTextContent('1 of 3 channels sent')
    expect(api.techErrors.periodSummary).toHaveBeenCalledTimes(1)
    expect(api.techErrors.overview).not.toHaveBeenCalled()
    fireEvent.click(w().querySelector('[data-slot="mydash-stat"]'))
    expect(navs.at(-1)).toEqual({ tab: 'channelreports', params: { te_st: 'missing' } })
    fireEvent.click(w().querySelector('[data-slot="mydash-open"]'))
    expect(navs.at(-1)).toEqual({ tab: 'channelreports', params: { te_st: 'missing' } })
  })

  it('kanal raporu paneli: eski pod (özet ucu 404) → genel bakış listesinden sayılır; 403 → "yetki yok", boyanmaz', async () => {
    perm.value = { ...PERMS, 'tech_error_reports.read': { view: true } }
    const ch = (id, name, extra = {}) => ({ id, name, team_id: 1, team_name: 'Dijital Kanallar', active: true, period_report: null, ...extra })
    api.techErrors.periodSummary.mockResolvedValue({ success: false, status: 404, code: 'NOT_FOUND', error: 'yok' })
    api.techErrors.overview.mockResolvedValue({ success: true, data: { period: { year: 2026, month: 9 }, teams: [],
      channels: [ch(1, 'Mobil', { period_report: { id: 100, status: 'sent' } }), ch(2, 'POS'), ch(3, 'ATM'), ch(4, 'Şube', { active: false })] } })
    const { unmount } = render(<Shell initial={layout('stat_channel_reports_missing')}><MyDashPage /></Shell>)
    const w = () => widget('stat_channel_reports_missing')
    await waitFor(() => expect(w()).toHaveAttribute('data-stat', 'on'))
    expect(w().querySelector('[data-slot="mydash-stat-value"]')).toHaveTextContent('2')
    expect(w()).toHaveTextContent('1 of 3 channels sent')
    expect(api.techErrors.overview).toHaveBeenCalledWith(null)
    unmount()
    api.techErrors.periodSummary.mockResolvedValue({ success: false, status: 403, error: 'kapalı' })
    render(<Shell initial={layout('stat_channel_reports_missing')}><MyDashPage /></Shell>)
    await waitFor(() => expect(w().querySelector('[data-slot="mydash-stat-value"]')).toHaveTextContent('—'))
    expect(w()).not.toHaveAttribute('data-stat')
    expect(w()).toHaveTextContent(EN['mydash.noAccess'])
    expect(w().querySelector('[data-slot="card"]').className).not.toMatch(/bg-green-700/)
  })

  it('kanal raporu paneli: hepsi gönderilmişse yeşil; okuma izni yoksa ya da modül takımlarına kapalıysa (scope.channel_reports false) kutu çizilmez ve katalogda yok; scope yok (eski sunucu) → sunulur', async () => {
    perm.value = { ...PERMS, 'tech_error_reports.read': { view: true } }
    api.techErrors.periodSummary.mockResolvedValue({ success: true, data: { active: 1, sent: 1, open: 0, missing: 0, inactive: 0 } })
    const r1 = render(<Shell initial={layout('stat_channel_reports_missing')}><MyDashPage /></Shell>)
    const w = () => widget('stat_channel_reports_missing')
    await waitFor(() => expect(w()).toHaveAttribute('data-tone', 'success'))
    expect(w().querySelector('[data-slot="mydash-stat-value"]')).toHaveTextContent('0')
    r1.unmount()
    perm.value = PERMS      // tech_error_reports.read yok → kutu saklanır ama çizilmez
    const r2 = render(<Shell initial={layout('stat_channel_reports_missing', 'stat_certs_week')}><MyDashPage /></Shell>)
    await waitFor(() => expect(ids()).toEqual(['stat_certs_week']))
    r2.unmount()
    // Modül kapalı (menü de gizli): izin olsa da panel çizilmez, "Kutu ekle"de sunulmaz
    perm.value = { ...PERMS, 'tech_error_reports.read': { view: true } }
    perm.scope = { monitoring_all: false, noc_operator: false, noc_console: false, channel_reports: false }
    const r3 = render(<Shell initial={layout('stat_channel_reports_missing', 'stat_certs_week')}><MyDashPage /></Shell>)
    await waitFor(() => expect(ids()).toEqual(['stat_certs_week']))
    r3.unmount()
    render(<Shell initial={layout('stat_certs_week')}><MyDashPage /></Shell>)        // panel düzende yok: katalog sunar mı?
    await waitFor(() => expect(ids()).toEqual(['stat_certs_week']))
    fireEvent.click(screen.getByRole('button', { name: /^(Edit|Düzenle)$/ }))
    fireEvent.click(screen.getByRole('button', { name: /^(Add widget|Kutu ekle)$/ }))
    const cat = await waitFor(() => {
      const el = document.querySelector('[data-slot="mydash-catalog"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(cat.querySelector('[data-type="stat_channel_reports_missing"]')).toBeNull()
    expect(cat.querySelector('[data-type="stat_monitors_down"]')).not.toBeNull()
  })

  it('"Kutu ekle": kutular ve renkli paneller ayrı grupta; "Renkli panel duvarı" onaylı uygulanır, yetkisiz panel düşer', async () => {
    perm.value = { 'monitoring.read': { view: true } }   // alarm / olay izni yok
    render(<Shell initial={layout('stat_certs_week')}><MyDashPage /></Shell>)
    await waitFor(() => expect(ids()).toEqual(['stat_certs_week']))
    fireEvent.click(screen.getByRole('button', { name: /^(Edit|Düzenle)$/ }))
    fireEvent.click(screen.getByRole('button', { name: /^(Add widget|Kutu ekle)$/ }))
    const cat = await waitFor(() => {
      const el = document.querySelector('[data-slot="mydash-catalog"]')
      expect(el).not.toBeNull()
      return el
    })
    const groups = [...cat.querySelectorAll('[data-slot="mydash-catalog-group"]')].map((g) => g.getAttribute('data-group'))
    expect(groups).toEqual(['widgets', 'stats'])
    const statGroup = cat.querySelector('[data-slot="mydash-catalog-group"][data-group="stats"]')
    expect(within(statGroup).getByText(EN['mydash.w.stat_monitors_down'])).toBeInTheDocument()
    expect(statGroup.querySelector('[data-type="stat_alarms_critical"]')).toBeNull()   // izin yok
    fireEvent.click(cat.querySelector('[data-slot="mydash-preset-stat-apply"]'))
    const dlg = await screen.findByRole('dialog', { name: EN['mydash.statWall.confirmTitle'] })
    fireEvent.click(within(dlg).getByRole('button', { name: EN['mydash.preset.apply'] }))
    await waitFor(() => expect(setSpy).toHaveBeenCalled())
    const types = setSpy.mock.calls.at(-1)[0].widgets.map((w) => w.type)
    expect(types).toEqual(STAT_TYPES.filter((t) => ['stat_monitors_down', 'stat_monitors_stale', 'stat_success_rate', 'stat_teams_failing',
      'stat_service_outages', 'stat_certs_expired', 'stat_certs_week', 'stat_certs_month', 'stat_cert_errors'].includes(t)))
    expect(setSpy.mock.calls.at(-1)[0].widgets.every((w) => w.size === 'sm')).toBe(true)
  })
})
