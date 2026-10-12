import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useState } from 'react'
import { render, screen, waitFor, fireEvent, within, act } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    me: { openAlerts: vi.fn() },
    monitoring: { getOverview: vi.fn(), storm: { status: vi.fn() } },
    getStats: vi.fn(),
    statusPage: { get: vi.fn() },
    teams: { directory: vi.fn() },
    oncall: { current: vi.fn() },
    getActivity: vi.fn(),
    admin: { getWatchdog: vi.fn() },
    dataQuality: { summary: vi.fn() },
  }),
}))

const perm = vi.hoisted(() => ({ value: {}, loaded: true }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({
    perms: perm.value,
    canView: (r) => !!perm.value[r]?.view,
    canEdit: () => false, canExecute: () => false, refresh: () => {}, loaded: perm.loaded,
  }),
  PermissionsProvider: ({ children }) => children,
}))

import { api } from '../api/client'
import { EN } from '../i18n/en.js'
import { UserPrefsContext } from '../hooks/useUserPrefs.js'
import { isTvModeActive } from '../utils/tvMode.js'
import MyDashPage from '../components/mydash/MyDashPage.jsx'

/**
 * Panom (2026-10-11): varsayılan kutular ve verileri, yetkiye göre gizleme, düzenleme (taşı / boyut / kaldır / sürükle-bırak
 * / ekle / varsayılana dön) doğru tercih belgesiyle kaydedilir, tercih hazır değilken düzenleme kapalı, boş pano, kutu
 * hatası son veriyi gösterir, TV kipi (sayfa, duraklat, Esc, hareketsizlik bayrağı, URL). Adlar sahte, gerçek uzunlukta.
 */
const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'
const LONG_PERSON = 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin (Teknoloji Servis Yönetimi Bölüm Başkanı)'
const ALL_PERMS = { 'alerts.read': { view: true }, 'monitoring.read': { view: true }, 'oncall.read': { view: true }, 'system_health.read': { view: true }, 'data_quality.view': { view: true } }

const setSpy = vi.fn()
const setterRef = { current: null }
function Shell({ initial = null, ready = true, children }) {
  const [dashboard, setDashboard] = useState(initial)
  setterRef.current = setDashboard
  const ctl = { ready, status: ready ? 'ready' : 'failed', prefs: {}, dashboard, setDashboard: setSpy }
  return <UserPrefsContext.Provider value={ctl}>{children}</UserPrefsContext.Provider>
}

const ids = () => [...document.querySelectorAll('[data-slot="mydash-widget"]')].map((el) => el.getAttribute('data-type'))
const widget = (type) => document.querySelector(`[data-slot="mydash-widget"][data-type="${type}"]`)
const lastDoc = () => setSpy.mock.calls.at(-1)[0]

beforeEach(() => {
  vi.clearAllMocks()
  setSpy.mockImplementation((v) => { setterRef.current?.(v); return true })
  window.history.replaceState({}, '', '/?tab=mydash')
  perm.value = ALL_PERMS
  perm.loaded = true
  api.me.openAlerts.mockResolvedValue({ success: true, data: { total: 3, tabs: {
    http: { count: 3, unacked: 2, levels: { critical: 1, high: 1, warning: 1, other: 0 }, items: [
      { id: 7, domain: 'odeme.example.com', alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL', created_at: '2026-10-11T08:00:00', team_name: LONG_TEAM }] },
  } } })
  api.monitoring.getOverview.mockResolvedValue({ success: true, data: { totals: { active: 10, down: 1, stale: 1, unknown: 0, success_rate_window: 98.2 },
    types: [{ type: 'http', active: 6, down: 1, stale: 0 }, { type: 'ping', active: 4, down: 0, stale: 1 }] } })
  api.getStats.mockResolvedValue({ success: true, data: { total_certificates: 40, valid_count: 35, expired: 0, expiring_in_7_days: 2, expiring_in_30_days: 3, critical_domains: ['kart.example.com'] } })
  api.statusPage.get.mockResolvedValue({ success: true, data: { overall: { state: 'degraded' }, services: [
    { key: 'a', name: 'İnternet Bankacılığı', team_name: LONG_TEAM, state: 'degraded' }, { key: 'b', name: 'Mobil', state: 'operational' }] } })
  api.monitoring.storm.status.mockResolvedValue({ success: true, data: { settings: { enabled: true }, totals: { storming: 0, near: 0 }, teams: [] } })
  api.teams.directory.mockResolvedValue({ success: true, data: [{ id: 5, name: LONG_TEAM, active: true }] })
  api.oncall.current.mockResolvedValue({ success: true, data: [{ team_id: 5, user_id: 'u-1', name: LONG_PERSON, until: '2026-10-12T06:00:00', active: true, override: false }] })
  api.getActivity.mockResolvedValue({ success: true, data: [{ id: 1, monitor_type: 'http', monitor_name: 'Ödeme API', target: 'odeme.example.com', result_status: 'ERROR', activity_time: '2026-10-11T08:00:00' }] })
  api.admin.getWatchdog.mockResolvedValue({ success: true, data: { enabled: true, status: 'UP', stale: false, checks: [] } })
  api.dataQuality.summary.mockResolvedValue({ success: true, data: { org: { score: 82, band: 'GOOD', findings: 12, delta_7d: 3 }, teams: [] } })
})
afterEach(() => { window.history.replaceState({}, '', '/') })

describe('MyDashPage', () => {
  it('varsayılan kutular verileriyle; uzun takım / kişi adları tam görünür', async () => {
    render(<Shell><MyDashPage /></Shell>)
    await waitFor(() => expect(ids()).toEqual(['alerts_open', 'cert_expiring', 'monitor_health', 'oncall', 'status_services', 'storms', 'activity']))
    await waitFor(() => expect(widget('alerts_open').querySelector('[data-slot="mydash-alerts-total"]')).toHaveTextContent('3'))
    expect(widget('alerts_open')).toHaveAttribute('data-tone', 'danger')
    expect(widget('alerts_open')).toHaveTextContent(LONG_TEAM)
    await waitFor(() => expect(widget('oncall')).toHaveTextContent(LONG_PERSON))
    expect(widget('oncall')).toHaveTextContent(LONG_TEAM)
    expect(api.oncall.current).toHaveBeenCalledWith([5])
    await waitFor(() => expect(widget('monitor_health').querySelector('[data-slot="mydash-monitor-healthy"]')).toHaveTextContent('80'))
    await waitFor(() => expect(widget('status_services')).toHaveTextContent('İnternet Bankacılığı'))
    expect(widget('cert_expiring')).toHaveAttribute('data-tone', 'danger')
    expect(document.querySelector('[data-slot="badge"][class*="truncate"]')).toBeNull()
  })

  it('2026-10-12: kendi kendini izleme hiç değerlendirilmediyse (UNKNOWN, sorun yok) yeşil "hepsi sağlıklı" DENMEZ', async () => {
    api.admin.getWatchdog.mockResolvedValue({ success: true, data: { enabled: true, status: 'UNKNOWN', stale: false, checks: [] } })
    render(<Shell initial={{ v: 1, widgets: [{ id: 'watchdog', type: 'watchdog', size: 'sm' }] }}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('watchdog').querySelector('[data-slot="mydash-empty"]')).not.toBeNull())
    const empty = widget('watchdog').querySelector('[data-slot="mydash-empty"]')
    expect(empty).toHaveTextContent(EN['mydash.watchdog.notEvaluated'])
    expect(empty.className).not.toContain('text-success')
    expect(widget('watchdog')).not.toHaveTextContent(EN['mydash.watchdog.ok'])
  })

  it('2026-10-12: yalnız UP iken yeşil "hepsi sağlıklı"', async () => {
    render(<Shell initial={{ v: 1, widgets: [{ id: 'watchdog', type: 'watchdog', size: 'sm' }] }}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('watchdog')).toHaveTextContent(EN['mydash.watchdog.ok']))
    expect(widget('watchdog').querySelector('[data-slot="mydash-empty"]').className).toContain('text-success')
  })

  it('2026-10-12: yetkiler gelmeden düzenleme kapalı, kayıt yapılmaz, "pano boş" denmez', async () => {
    perm.value = {}
    perm.loaded = false
    render(<Shell><MyDashPage /></Shell>)
    expect(screen.queryByText(EN['mydash.emptyTitle'])).toBeNull()
    expect(screen.getByRole('button', { name: EN['mydash.edit'] })).toBeDisabled()
    expect(setSpy).not.toHaveBeenCalled()
  })

  it('yetkisi olmayan kutu kayıtlı olsa da çizilmez, verisi istenmez', async () => {
    perm.value = {}
    render(<Shell initial={{ v: 1, widgets: [{ id: 'watchdog', type: 'watchdog', size: 'sm' }, { id: 'activity', type: 'activity', size: 'md' }] }}><MyDashPage /></Shell>)
    await waitFor(() => expect(ids()).toEqual(['activity']))
    expect(api.admin.getWatchdog).not.toHaveBeenCalled()
  })

  it('düzenle: sonraya taşı, boyut, kaldır — gizli kutu korunur, duyuru yapılır', async () => {
    perm.value = {}
    const initial = { v: 1, widgets: [
      { id: 'activity', type: 'activity', size: 'md' }, { id: 'watchdog', type: 'watchdog', size: 'sm' }, { id: 'cert-expiring', type: 'cert_expiring', size: 'sm' }] }
    render(<Shell initial={initial}><MyDashPage /></Shell>)
    await waitFor(() => expect(ids()).toEqual(['activity', 'cert_expiring']))
    fireEvent.click(screen.getByRole('button', { name: /^(Edit|Düzenle)$/ }))
    expect(document.querySelectorAll('[data-slot="mydash-widget-tools"]')).toHaveLength(2)
    expect(within(widget('activity')).getByRole('button', { name: /one place earlier|bir önceye/ })).toBeDisabled()

    fireEvent.click(within(widget('activity')).getByRole('button', { name: /one place later|bir sonraya/ }))
    expect(lastDoc()).toEqual({ v: 1, widgets: [
      { id: 'cert-expiring', type: 'cert_expiring', size: 'sm' }, { id: 'watchdog', type: 'watchdog', size: 'sm' }, { id: 'activity', type: 'activity', size: 'md' }] })
    await waitFor(() => expect(ids()).toEqual(['cert_expiring', 'activity']))
    expect(document.querySelector('[data-slot="mydash-announce"]').textContent).toMatch(/2/)

    fireEvent.click(within(widget('activity')).getByRole('radio', { name: /^(Wide|Geniş)$/ }))
    expect(lastDoc().widgets.find((w) => w.id === 'activity').size).toBe('lg')
    await waitFor(() => expect(widget('activity')).toHaveAttribute('data-size', 'lg'))

    fireEvent.click(within(widget('cert_expiring')).getByRole('button', { name: /^(Remove|.*kaldır)/ }))
    expect(lastDoc().widgets.map((w) => w.id)).toEqual(['watchdog', 'activity'])
    await waitFor(() => expect(ids()).toEqual(['activity']))
  })

  it('sürükle-bırak: kutu hedefin yerine geçer', async () => {
    render(<Shell initial={{ v: 1, widgets: [{ id: 'a1', type: 'activity', size: 'sm' }, { id: 'c1', type: 'cert_expiring', size: 'sm' }, { id: 's1', type: 'storms', size: 'sm' }] }}><MyDashPage /></Shell>)
    await waitFor(() => expect(ids()).toEqual(['activity', 'cert_expiring', 'storms']))
    fireEvent.click(screen.getByRole('button', { name: /^(Edit|Düzenle)$/ }))
    const dt = { data: '', setData(_k, v) { this.data = v }, getData() { return this.data }, effectAllowed: '', dropEffect: '' }
    fireEvent.dragStart(widget('storms'), { dataTransfer: dt })
    fireEvent.dragOver(widget('activity'), { dataTransfer: dt })
    fireEvent.drop(widget('activity'), { dataTransfer: dt })
    expect(lastDoc().widgets.map((w) => w.id)).toEqual(['s1', 'a1', 'c1'])
    await waitFor(() => expect(ids()).toEqual(['storms', 'activity', 'cert_expiring']))
  })

  it('kutu ekle: katalog yalnız eklenebilir ve görülebilir türleri listeler; eklenen sona gelir', async () => {
    perm.value = { 'data_quality.view': { view: true } }
    render(<Shell initial={{ v: 1, widgets: [{ id: 'activity', type: 'activity', size: 'md' }] }}><MyDashPage /></Shell>)
    await waitFor(() => expect(ids()).toEqual(['activity']))
    fireEvent.click(screen.getByRole('button', { name: /^(Edit|Düzenle)$/ }))
    fireEvent.click(screen.getByRole('button', { name: /^(Add widget|Kutu ekle)$/ }))
    const cat = await waitFor(() => {
      const el = document.querySelector('[data-slot="mydash-catalog"]')
      expect(el).not.toBeNull()
      return el
    })
    const types = [...cat.querySelectorAll('[data-slot="mydash-catalog-item"]')].map((el) => el.getAttribute('data-type'))
    expect(types).toContain('data_quality')
    expect(types).not.toContain('activity')
    expect(types).not.toContain('watchdog')
    expect(types).not.toContain('storms')
    fireEvent.click(within(cat.querySelector('[data-type="data_quality"]')).getByRole('button'))
    expect(lastDoc().widgets).toEqual([{ id: 'activity', type: 'activity', size: 'md' }, { id: 'data-quality', type: 'data_quality', size: 'sm' }])
    await waitFor(() => expect(widget('data_quality')).not.toBeNull())
    await waitFor(() => expect(widget('data_quality').querySelector('[data-slot="mydash-quality-score"]')).toHaveTextContent('82'))
  })

  it('varsayılana dön: onaydan sonra tercih silinir (null)', async () => {
    render(<Shell initial={{ v: 1, widgets: [{ id: 'activity', type: 'activity', size: 'md' }] }}><MyDashPage /></Shell>)
    await waitFor(() => expect(ids()).toEqual(['activity']))
    fireEvent.click(screen.getByRole('button', { name: /^(Edit|Düzenle)$/ }))
    fireEvent.click(screen.getByRole('button', { name: /^(Reset to default|Varsayılana dön)$/ }))
    const dlg = await screen.findByRole('dialog', { name: /Reset the dashboard|varsayılana dönsün/ })
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Reset to default|Varsayılana dön)$/ }))
    await waitFor(() => expect(setSpy).toHaveBeenCalledWith(null))
    await waitFor(() => expect(ids()).toHaveLength(7))
  })

  it('tercih hazır değilken düzenleme kapalı; boş pano ekleme önerir', async () => {
    const { unmount } = render(<Shell ready={false}><MyDashPage /></Shell>)
    expect(await screen.findByRole('button', { name: /^(Edit|Düzenle)$/ })).toBeDisabled()
    unmount()
    render(<Shell initial={{ v: 1, widgets: [] }}><MyDashPage /></Shell>)
    expect(await screen.findByText(/Your dashboard is empty|Panonuz boş/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^(TV mode|TV modu)$/ })).toBeDisabled()
  })

  it('kutu hatası: önce veri vardıysa "son veriler" notu, yoksa gövdede "veriler alınamadı" + sunucu metni (yeşil özet yok)', async () => {
    api.getActivity.mockResolvedValue({ success: false, error: 'Sunucu şu an yanıt vermiyor; birkaç dakika sonra yeniden deneyin.' })
    render(<Shell initial={{ v: 1, widgets: [{ id: 'activity', type: 'activity', size: 'md' }, { id: 'cert', type: 'cert_expiring', size: 'sm' }] }}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('activity').querySelector('[data-slot="mydash-widget-failed"]')).toHaveTextContent(/yanıt vermiyor/))
    expect(widget('activity').getAttribute('data-tone')).toBe('muted')
    await waitFor(() => expect(widget('cert_expiring').querySelector('[data-slot="mydash-cert-7"]')).toHaveTextContent('2'))
    api.getStats.mockResolvedValue({ success: false, error: 'Sunucu şu an yanıt vermiyor; birkaç dakika sonra yeniden deneyin.' })
    fireEvent.click(screen.getByRole('button', { name: /^(Refresh|Yenile)$/ }))
    await waitFor(() => expect(widget('cert_expiring').querySelector('[data-slot="mydash-widget-error"]')).toHaveTextContent(/last data|son alınan/))
    expect(widget('cert_expiring').querySelector('[data-slot="mydash-cert-7"]')).toHaveTextContent('2')
  })

  it('TV kipi: katman açılır, sayfalar arası geçilir, duraklatılır; Esc kapatır ve hareketsizlik bayrağı iner', async () => {
    render(<Shell><MyDashPage /></Shell>)
    await waitFor(() => expect(ids()).toHaveLength(7))
    fireEvent.click(screen.getByRole('button', { name: /^(TV mode|TV modu)$/ }))
    const tv = await waitFor(() => {
      const el = document.querySelector('[data-slot="mydash-tv"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(isTvModeActive()).toBe(true)
    await waitFor(() => expect(window.location.search).toContain('md_tv=1'))
    // jsdom 1024×768 → 3 sütun × 1 satır → 7 varsayılan kutu (orta + küçük çiftleri) dört sayfa
    expect(tv).toHaveAttribute('data-pages', '4')
    expect(tv).toHaveAttribute('data-page', '1')
    fireEvent.click(within(tv).getByRole('button', { name: /^(Next page|Sonraki sayfa)$/ }))
    expect(tv).toHaveAttribute('data-page', '2')
    const visible = tv.querySelector('[data-slot="mydash-tv-page"]:not(.hidden)')
    expect(visible.getAttribute('data-index')).toBe('1')
    const pause = within(tv).getByRole('button', { name: /Pause page rotation|dönüşünü duraklat/ })
    fireEvent.click(pause)
    expect(within(tv).getByRole('button', { name: /Resume page rotation|dönüşünü sürdür/ })).toHaveAttribute('aria-pressed', 'true')
    act(() => { fireEvent.keyDown(document, { key: 'Escape' }) })
    await waitFor(() => expect(document.querySelector('[data-slot="mydash-tv"]')).toBeNull())
    expect(isTvModeActive()).toBe(false)
    await waitFor(() => expect(window.location.search).not.toContain('md_tv'))
  })

  it('TV kipi adresten açılır (md_tv=1) ve "Çık" ile kapanır', async () => {
    window.history.replaceState({}, '', '/?tab=mydash&md_tv=1')
    render(<Shell><MyDashPage /></Shell>)
    const exit = await screen.findByRole('button', { name: /^(Exit|Çık)$/ })
    expect(isTvModeActive()).toBe(true)
    fireEvent.click(exit)
    await waitFor(() => expect(document.querySelector('[data-slot="mydash-tv"]')).toBeNull())
    expect(isTvModeActive()).toBe(false)
  })
})
