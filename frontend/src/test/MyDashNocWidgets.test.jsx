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

/**
 * 7/24 Komuta kutuları + renkli panel görünümü (2026-10-13, kullanıcı isteği: "7/24 ekibi önünde olsun; sorun varsa
 * kırmızı, uyarı turuncu, yoksa yeşil; Grafana tarzı"). Okunamayan / yetkisiz veri ASLA yeşil değil; konsol kutusu yalnız
 * 7/24 konsolunu açabilene; renkli panel kutu başına kaydedilir ve bayat / hatalı veride boyanmaz; hazır düzen onaylıdır.
 * Adlar sahte, gerçek uzunlukta.
 */
const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'
const PERMS = {
  'alerts.read': { view: true }, 'monitoring.read': { view: true }, 'oncall.read': { view: true },
  'maintenance.view': { view: true }, 'incidents.view': { view: true },
}

const setSpy = vi.fn()
const setterRef = { current: null }
function Shell({ initial = null, children }) {
  const [dashboard, setDashboard] = useState(initial)
  setterRef.current = setDashboard
  const ctl = { ready: true, status: 'ready', prefs: {}, dashboard, setDashboard: setSpy }
  return <UserPrefsContext.Provider value={ctl}>{children}</UserPrefsContext.Provider>
}
const layout = (...widgets) => ({ v: 1, widgets: widgets.map(([type, size = 'md', style]) => ({ id: type.replace(/_/g, '-'), type, size, ...(style ? { style } : {}) })) })
const widget = (type) => document.querySelector(`[data-slot="mydash-widget"][data-type="${type}"]`)
const ids = () => [...document.querySelectorAll('[data-slot="mydash-widget"]')].map((el) => el.getAttribute('data-type'))
const isoMinAgo = (min) => new Date(Date.now() - min * 60_000).toISOString()

const statusPage = (over = {}) => ({ success: true, data: {
  overall: { state: 'operational' }, services: [{ key: 'a', name: 'Ödeme', state: 'operational', maintenance: 2, monitors_visible: true },
    { key: 'b', name: 'Başka takımın hizmeti', state: 'maintenance', maintenance: 12, monitors_visible: false }],
  maintenance: { active: [], active_total: 0, active_hidden: 0, upcoming: [], upcoming_total: 0, upcoming_hidden: 0, days: 7 },
  incidents: { active: [], active_total: 0, active_visible: 0, active_hidden: 0 }, ...over } })

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
  api.me.openAlerts.mockResolvedValue({ success: true, data: { total: 3, oldest_open_at: isoMinAgo(300), oldest_severe_at: isoMinAgo(120), tabs: {
    http: { count: 3, unacked: 2, levels: { critical: 1, high: 1, warning: 1, other: 0 }, items: [] } } } })
  api.statusPage.get.mockResolvedValue(statusPage())
  api.noc.console.mockResolvedValue({ success: true, data: { total: 0, items: [] } })
  api.monitoring.getOverview.mockResolvedValue({ success: true, data: { totals: { active: 10, down: 0, stale: 0, unknown: 0 }, types: [] } })
  api.monitoring.storm.status.mockResolvedValue({ success: true, data: { settings: { enabled: true }, totals: { storming: 0, near: 0 }, teams: [] } })
  api.teams.directory.mockResolvedValue({ success: true, data: [] })
  api.oncall.current.mockResolvedValue({ success: true, data: [] })
})
afterEach(() => {
  window.removeEventListener('sm:navigate', onNav)
  window.history.replaceState({}, '', '/')
})

describe('7/24 Komuta kutuları', () => {
  it('durum lambası: kritik/yüksek açıkken KIRMIZI + en eski kritik/yüksek alarmın süresi; tıklanınca açık alarmlar', async () => {
    render(<Shell initial={layout(['noc_light', 'sm'])}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('noc_light').querySelector('[data-slot="mydash-light"]')).toHaveAttribute('data-tone', 'danger'))
    expect(widget('noc_light')).toHaveTextContent(EN['mydash.light.state.danger'])
    expect(widget('noc_light').querySelector('[data-slot="mydash-light-oldest"]')).toHaveTextContent(/critical \/ high alarm: 2 h/)
    fireEvent.click(widget('noc_light').querySelector('[data-slot="mydash-light-link"]'))
    expect(navs.at(-1)).toEqual({ tab: 'alerthistory', params: undefined })
  })

  it('durum lambası: yalnız uyarı → turuncu; açık alarm yok → yeşil; okuma yetkisi yok → gri ve tıklanmaz', async () => {
    api.me.openAlerts.mockResolvedValue({ success: true, data: { total: 1, tabs: { http: { count: 1, levels: { critical: 0, high: 0, warning: 1, other: 0 }, items: [] } } } })
    const { unmount } = render(<Shell initial={layout(['noc_light', 'sm'])}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('noc_light').querySelector('[data-slot="mydash-light"]')).toHaveAttribute('data-tone', 'warning'))
    unmount()
    api.me.openAlerts.mockResolvedValue({ success: true, data: { total: 0, tabs: {} } })
    const r2 = render(<Shell initial={layout(['noc_light', 'sm'])}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('noc_light').querySelector('[data-slot="mydash-light"]')).toHaveAttribute('data-tone', 'success'))
    r2.unmount()
    api.me.openAlerts.mockResolvedValue({ success: true, data: { visible: false, total: 0, tabs: {} } })
    render(<Shell initial={layout(['noc_light', 'sm'])}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('noc_light').querySelector('[data-slot="mydash-light"]')).toHaveAttribute('data-tone', 'muted'))
    expect(widget('noc_light').querySelector('[data-slot="mydash-light-link"]')).toBeNull()
  })

  it('aranmayı bekleyenler: yalnız 7/24 konsolunu açabilene; 15 dk aşan kırmızı + "Gecikti"; satır konsolu süzülmüş açar', async () => {
    const { unmount } = render(<Shell initial={layout(['noc_uncalled'], ['noc_light', 'sm'])}><MyDashPage /></Shell>)
    await waitFor(() => expect(ids()).toEqual(['noc_light']))
    expect(api.noc.console).not.toHaveBeenCalled()
    unmount()
    api.noc.console.mockResolvedValue({ success: true, data: { total: 2, items: [
      { id: 7, domain: 'odeme.example.com', level: 'CRITICAL', team_name: LONG_TEAM, call_count: 0, noc: { sent_at: isoMinAgo(40) } },
      { id: 8, domain: 'kart.example.com', level: 'HIGH', team_name: 'Kartlar', call_count: 0, noc: { sent_at: isoMinAgo(3) } }] } })
    render(<Shell initial={layout(['noc_uncalled'])}><MyDashPage nocConsole /></Shell>)
    await waitFor(() => expect(widget('noc_uncalled')).toHaveAttribute('data-tone', 'danger'))
    expect(api.noc.console).toHaveBeenCalledWith({ window: '24h', noc: 'sent', called: 'no', state: 'open', size: 50, page: 0 })
    expect(api.noc.console).toHaveBeenCalledTimes(1)   // toplam ilk sayfaya sığdı → son sayfa okunmaz
    const list = widget('noc_uncalled').querySelector('[data-slot="mydash-noc-list"]')
    const rows = within(list).getAllByRole('button')
    expect(rows[0]).toHaveTextContent('odeme.example.com')
    expect(rows[0]).toHaveTextContent(EN['mydash.noc.late'])
    expect(rows[0]).toHaveTextContent(LONG_TEAM)
    expect(rows[1]).not.toHaveTextContent(EN['mydash.noc.late'])
    fireEvent.click(rows[0])
    expect(navs.at(-1)).toEqual({ tab: 'noc', params: { n_view: 'console', n_cnoc: 'sent', n_ccall: 'no', n_cst: 'open', n_cq: 'odeme.example.com' } })
  })

  it('inceleme: sunucu EN YENİYİ önce verir — toplam ilk sayfadan büyükse en eskiler son sayfadan okunur, karar onlardan', async () => {
    const newest = Array.from({ length: 50 }, (_, i) => ({ id: 100 + i, domain: `yeni-${i}.example.com`, level: 'WARNING', call_count: 0, noc: { sent_at: isoMinAgo(2) } }))
    const oldest = Array.from({ length: 10 }, (_, i) => ({ id: 10 + i, domain: `eski-${i}.example.com`, level: 'CRITICAL', call_count: 0, noc: { sent_at: isoMinAgo(120) } }))
    api.noc.console.mockImplementation(async (p) => ({ success: true, data: { total: 60, items: p.page === 0 ? newest : oldest } }))
    render(<Shell initial={layout(['noc_uncalled'])}><MyDashPage nocConsole /></Shell>)
    await waitFor(() => expect(widget('noc_uncalled')).toHaveAttribute('data-tone', 'danger'))
    expect(api.noc.console).toHaveBeenCalledWith(expect.objectContaining({ page: 1, size: 50 }))
    const list = widget('noc_uncalled').querySelector('[data-slot="mydash-noc-list"]')
    expect(within(list).getAllByRole('button')[0]).toHaveTextContent('eski-')
    expect(widget('noc_uncalled').querySelector('[data-slot="mydash-noc-overdue"]')).toHaveTextContent('10')
  })

  it('inceleme: son sayfa okunamazsa eksik veriyle renk verilmez (hata durumu); konsol listesi kesikse "bekleyen yok" yeşil değil', async () => {
    api.noc.console.mockImplementation(async (p) => (p.page === 0
      ? { success: true, data: { total: 60, items: [{ id: 1, domain: 'a.example.com', level: 'HIGH', call_count: 0, noc: { sent_at: isoMinAgo(2) } }] } }
      : { success: false, error: 'Sunucuya ulaşılamadı; bağlantınızı kontrol edip yeniden deneyin.' }))
    const { unmount } = render(<Shell initial={layout(['noc_uncalled'])}><MyDashPage nocConsole /></Shell>)
    await waitFor(() => expect(widget('noc_uncalled').querySelector('[data-slot="mydash-widget-failed"]')).not.toBeNull())
    expect(widget('noc_uncalled')).toHaveAttribute('data-tone', 'muted')
    unmount()
    api.noc.console.mockReset()
    api.noc.console.mockResolvedValue({ success: true, data: { total: 0, items: [], truncated: true } })
    render(<Shell initial={layout(['noc_uncalled'])}><MyDashPage nocConsole /></Shell>)
    await waitFor(() => expect(widget('noc_uncalled')).toHaveTextContent(EN['mydash.noc.partialBody']))
    expect(widget('noc_uncalled')).toHaveAttribute('data-tone', 'muted')
    expect(widget('noc_uncalled')).not.toHaveTextContent(EN['mydash.noc.none'])
  })

  it('inceleme tur 2: iki çağrı arasında konsol listesi değiştiyse (toplam / sayfa tutmuyor) sonuç kesik sayılır — "tam" görünmez', async () => {
    const newest = Array.from({ length: 50 }, (_, i) => ({ id: 100 + i, domain: `yeni-${i}.example.com`, level: 'WARNING', call_count: 0, noc: { sent_at: isoMinAgo(2) } }))
    api.noc.console.mockImplementation(async (p) => (p.page === 0
      ? { success: true, data: { total: 60, size: 50, page: 0, items: newest } }
      : { success: true, data: { total: 75, size: 50, page: 1, items: newest.slice(0, 10).map((x) => ({ ...x, id: x.id + 1000 })) } }))
    render(<Shell initial={layout(['noc_uncalled'])}><MyDashPage nocConsole /></Shell>)
    await waitFor(() => expect(widget('noc_uncalled').querySelector('[data-slot="mydash-noc-partial"]')).not.toBeNull())
    expect(widget('noc_uncalled')).toHaveAttribute('data-tone', 'warning')
  })

  it('inceleme tur 2: renkli panelde kesik liste uyarısı panelin alt satırında; olay / bakım başlığı "görebildiğiniz" der ve gizli sayıyı taşır', async () => {
    api.noc.console.mockResolvedValue({ success: true, data: { total: 1, truncated: true, items: [{ id: 1, domain: 'a.example.com', level: 'HIGH', call_count: 0, noc: { sent_at: isoMinAgo(30) } }] } })
    api.statusPage.get.mockResolvedValue(statusPage({
      maintenance: { active: [], active_total: 1, active_hidden: 1, upcoming: [], upcoming_total: 0, upcoming_hidden: 0 },
      incidents: { active: [], active_total: 2, active_visible: 0, active_hidden: 2 } }))
    render(<Shell initial={layout(['noc_uncalled', 'md', 'stat'], ['maintenance', 'sm', 'stat'], ['incidents_open', 'md', 'stat'])}><MyDashPage nocConsole /></Shell>)
    await waitFor(() => expect(widget('noc_uncalled')).toHaveAttribute('data-stat', 'on'))
    expect(widget('noc_uncalled')).toHaveTextContent(EN['mydash.noc.partialNote'])
    expect(widget('noc_uncalled').querySelector('[data-slot="mydash-widget-tone"]')).toHaveTextContent('1+')
    await waitFor(() => expect(widget('incidents_open')).toHaveAttribute('data-stat', 'on'))
    expect(widget('incidents_open').querySelector('[data-slot="mydash-widget-tone"]')).toHaveTextContent(EN['mydash.inc.noneVisibleShort'])
    expect(widget('incidents_open')).toHaveTextContent('+2')
    expect(widget('incidents_open')).not.toHaveTextContent(EN['sp.inc.none'])
    expect(widget('maintenance').querySelector('[data-slot="mydash-widget-tone"]')).toHaveTextContent(EN['mydash.maint.noneActiveVisible'])
    expect(widget('maintenance')).toHaveTextContent('+1')
  })

  it('inceleme: başlıktaki "sayfayı aç" konsolu açar (yönetici için varsayılan görünüm kapsam sayfası)', async () => {
    render(<Shell initial={layout(['noc_uncalled'])}><MyDashPage nocConsole /></Shell>)
    await waitFor(() => expect(widget('noc_uncalled')).toHaveAttribute('data-tone', 'success'))
    fireEvent.click(widget('noc_uncalled').querySelector('[data-slot="mydash-open"]'))
    expect(navs.at(-1)).toEqual({ tab: 'noc', params: { n_view: 'console', n_cnoc: 'sent', n_ccall: 'no', n_cst: 'open' } })
  })

  it('aranmayı bekleyen yoksa yeşil; konsol okunamazsa yeşil DEĞİL (hata)', async () => {
    const { unmount } = render(<Shell initial={layout(['noc_uncalled'])}><MyDashPage nocConsole /></Shell>)
    await waitFor(() => expect(widget('noc_uncalled')).toHaveAttribute('data-tone', 'success'))
    expect(widget('noc_uncalled')).toHaveTextContent(EN['mydash.noc.none'])
    unmount()
    api.noc.console.mockResolvedValue({ success: false, error: 'Sunucuya ulaşılamadı; bağlantınızı kontrol edip yeniden deneyin.' })
    render(<Shell initial={layout(['noc_uncalled'])}><MyDashPage nocConsole /></Shell>)
    await waitFor(() => expect(widget('noc_uncalled').querySelector('[data-slot="mydash-widget-failed"]')).not.toBeNull())
    expect(widget('noc_uncalled')).toHaveAttribute('data-tone', 'muted')
  })

  it('bakım: süren bakım turuncu + bakımdaki izleme; yoksa yeşil; pencereler OKUNAMADIYSA "bakım yok" denmez', async () => {
    api.statusPage.get.mockResolvedValue(statusPage({ maintenance: { active: [{ id: 1, name: 'Veri merkezi ağ anahtarlarının yazılım güncellemesi (gece bakımı)', ends_at: '2026-10-13T10:00:00Z', team_name: LONG_TEAM, monitor_count: 3 }],
      active_total: 1, active_hidden: 0, upcoming: [], upcoming_total: 0, upcoming_hidden: 2 } }))
    const { unmount } = render(<Shell initial={layout(['maintenance', 'md'])}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('maintenance')).toHaveAttribute('data-tone', 'warning'))
    expect(widget('maintenance')).toHaveTextContent('Veri merkezi ağ anahtarlarının yazılım güncellemesi (gece bakımı)')
    expect(widget('maintenance').querySelector('[data-slot="mydash-maint-monitors"]')).toHaveTextContent('2')
    expect(widget('maintenance')).toHaveTextContent('+2')
    unmount()
    api.statusPage.get.mockResolvedValue(statusPage())
    const r2 = render(<Shell initial={layout(['maintenance', 'md'])}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('maintenance')).toHaveAttribute('data-tone', 'success'))
    r2.unmount()
    api.statusPage.get.mockResolvedValue(statusPage({ maintenance: { active: [], active_total: 0, upcoming: [], upcoming_total: 0, unavailable: true } }))
    render(<Shell initial={layout(['maintenance', 'md'])}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('maintenance').querySelector('[data-slot="mydash-widget-failed"]')).toHaveTextContent(EN['mydash.maint.unavailable']))
    expect(widget('maintenance')).toHaveAttribute('data-tone', 'muted')
    expect(widget('maintenance')).not.toHaveTextContent(EN['sp.maint.none'])
  })

  it('inceleme: yalnız başka takımların bakım / olay kaydı varsa "yok" denmez — "görebildiğiniz … yok" + gizli sayısı', async () => {
    api.statusPage.get.mockResolvedValue(statusPage({
      maintenance: { active: [], active_total: 1, active_hidden: 1, upcoming: [], upcoming_total: 0, upcoming_hidden: 0 },
      incidents: { active: [], active_total: 2, active_visible: 0, active_hidden: 2 } }))
    render(<Shell initial={layout(['maintenance'], ['incidents_open'])}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('maintenance')).toHaveTextContent(EN['mydash.maint.noneVisible']))
    expect(widget('maintenance')).not.toHaveTextContent(EN['sp.maint.none'])
    expect(widget('maintenance').querySelector('[data-slot="mydash-maint-active"]')).toHaveTextContent('0')
    expect(widget('maintenance').querySelector('[data-slot="mydash-maint-monitors"]')).toHaveTextContent('2')
    expect(widget('maintenance').querySelector('[data-slot="mydash-maint-monitors"]')).not.toHaveTextContent('12')
    expect(widget('maintenance')).toHaveTextContent('+1')
    expect(widget('incidents_open')).toHaveTextContent(EN['mydash.inc.noneVisible'])
    expect(widget('incidents_open')).not.toHaveTextContent(EN['sp.inc.noneText'])
    expect(widget('incidents_open')).toHaveTextContent('+2')
  })

  it('olaylar: kritik/yüksek kırmızı, satır olayı açar, gizli yalnız sayı; okunamadıysa "olay yok" denmez', async () => {
    api.statusPage.get.mockResolvedValue(statusPage({ incidents: { active: [{ id: 41, title: 'Mobil uygulamada giriş yapılamıyor — kimlik doğrulama servisi zaman aşımına düşüyor', severity: 'CRITICAL',
      status: 'INVESTIGATING', started_at: '2026-10-13T07:10:00', team_name: LONG_TEAM }], active_total: 3, active_visible: 1, active_hidden: 2 } }))
    const { unmount } = render(<Shell initial={layout(['incidents_open'])}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('incidents_open')).toHaveAttribute('data-tone', 'danger'))
    expect(widget('incidents_open')).toHaveTextContent(EN['sp.sev.CRITICAL'])
    expect(widget('incidents_open')).toHaveTextContent(EN['sp.inc.status.INVESTIGATING'])
    expect(widget('incidents_open')).toHaveTextContent('+2')
    fireEvent.click(within(widget('incidents_open').querySelector('[data-slot="mydash-inc-list"]')).getByRole('button'))
    expect(navs.at(-1)).toEqual({ tab: 'incident-history', params: { ih_id: 41 } })
    fireEvent.click(widget('incidents_open').querySelector('[data-slot="mydash-inc-severe"]'))
    expect(navs.at(-1)).toEqual({ tab: 'incident-history', params: { ih_open: 'true' } })
    unmount()
    api.statusPage.get.mockResolvedValue(statusPage({ incidents: { active: [], active_total: 0, active_visible: 0, active_hidden: 0, active_unavailable: true } }))
    render(<Shell initial={layout(['incidents_open'])}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('incidents_open').querySelector('[data-slot="mydash-widget-failed"]')).toHaveTextContent(EN['mydash.inc.unavailable']))
    expect(widget('incidents_open')).not.toHaveTextContent(EN['sp.inc.noneText'])
  })
})

describe('2026-10-13: 7/24 ekibi tüm kurumu görür — canlı kapsam', () => {
  it('sunucu kapsamı 7/24 konsolunu açıyorsa konsol kutusu görünür (giriş anındaki bilgiye bakılmaz) ve sayfa "tüm kurum (7/24)" der', async () => {
    perm.scope = { monitoring_all: true, noc_operator: true, noc_console: true }
    render(<Shell initial={layout(['noc_uncalled'], ['noc_light', 'sm'])}><MyDashPage /></Shell>)
    await waitFor(() => expect(ids()).toEqual(['noc_uncalled', 'noc_light']))
    const chip = document.querySelector('[data-slot="mydash-scope"]')
    expect(chip).toHaveAttribute('data-scope', 'all')
    expect(chip).toHaveTextContent(EN['mydash.scope.noc'])
  })

  it('7/24 takımından çıkarılınca (kapsam: konsol yok) kutu gizlenir — App giriş anında operatör dese bile; kapsam "takımlarınız"', async () => {
    perm.scope = { monitoring_all: false, noc_operator: false, noc_console: false }
    render(<Shell initial={layout(['noc_uncalled'], ['noc_light', 'sm'])}><MyDashPage nocConsole /></Shell>)
    await waitFor(() => expect(ids()).toEqual(['noc_light']))
    expect(api.noc.console).not.toHaveBeenCalled()
    expect(document.querySelector('[data-slot="mydash-scope"]')).toHaveAttribute('data-scope', 'teams')
  })

  it('eski sunucu kapsam göndermezse App\'in bilgisi kullanılır, kapsam rozeti çizilmez', async () => {
    render(<Shell initial={layout(['noc_uncalled'])}><MyDashPage nocConsole /></Shell>)
    await waitFor(() => expect(ids()).toEqual(['noc_uncalled']))
    expect(document.querySelector('[data-slot="mydash-scope"]')).toBeNull()
  })
})

describe('Renkli panel görünümü', () => {
  it('kutu başına: zemin durum renginde, büyük sayı; tıklanınca süzülmüş liste; düzenle kipinde aç/kapa kaydedilir', async () => {
    render(<Shell initial={layout(['alerts_open', 'md', 'stat'], ['noc_light', 'sm'])}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('alerts_open')).toHaveAttribute('data-stat', 'on'))
    expect(widget('alerts_open').querySelector('[data-slot="mydash-stat-value"]')).toHaveTextContent('3')
    expect(widget('alerts_open').querySelector('[data-slot="card"]').className).toMatch(/bg-red-700/)
    expect(widget('noc_light')).not.toHaveAttribute('data-stat')
    fireEvent.click(widget('alerts_open').querySelector('[data-slot="mydash-stat"]'))
    expect(navs.at(-1)).toEqual({ tab: 'alerthistory', params: undefined })
    fireEvent.click(screen.getByRole('button', { name: /^(Edit|Düzenle)$/ }))
    // Düzenle kipinde tıklanmaz (kazara gezinme yok)
    expect(widget('alerts_open').querySelector('[data-slot="mydash-stat"]').tagName).toBe('DIV')
    fireEvent.click(widget('alerts_open').querySelector('[data-slot="mydash-style"]'))
    expect(setSpy.mock.calls.at(-1)[0].widgets[0]).toEqual({ id: 'alerts-open', type: 'alerts_open', size: 'md' })
    fireEvent.click(widget('noc_light').querySelector('[data-slot="mydash-style"]'))
    expect(setSpy.mock.calls.at(-1)[0].widgets.find((w) => w.type === 'noc_light')).toMatchObject({ style: 'stat' })
  })

  it('inceleme: renkli panelde "güncellenemedi" notu zeminin metin rengiyle okunur (kehribar metin değil)', async () => {
    render(<Shell initial={layout(['alerts_open', 'md', 'stat'])}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('alerts_open')).toHaveAttribute('data-stat', 'on'))
    api.me.openAlerts.mockResolvedValue({ success: false, error: 'Sunucuya ulaşılamadı; bağlantınızı kontrol edip yeniden deneyin.' })
    fireEvent.click(screen.getByRole('button', { name: /^(Refresh|Yenile)$/ }))
    await waitFor(() => expect(widget('alerts_open').querySelector('[data-slot="mydash-widget-error"]')).not.toBeNull())
    const note = widget('alerts_open').querySelector('[data-slot="mydash-widget-error"]')
    expect(widget('alerts_open')).toHaveAttribute('data-stat', 'on')
    expect(note.className).toMatch(/text-current/)
    expect(note.className).not.toMatch(/text-amber-700/)
  })

  it('okunamayan veri renkli panelde de boyanmaz (yeşil zemin yok); büyük sayısı olmayan kutuda görünüm düğmesi yok', async () => {
    api.monitoring.getOverview.mockResolvedValue({ success: false, error: 'Sunucuya ulaşılamadı; bağlantınızı kontrol edip yeniden deneyin.' })
    render(<Shell initial={layout(['monitor_health', 'md', 'stat'], ['oncall', 'sm'])}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('monitor_health').querySelector('[data-slot="mydash-widget-failed"]')).not.toBeNull())
    expect(widget('monitor_health')).not.toHaveAttribute('data-stat')
    expect(widget('monitor_health').querySelector('[data-slot="card"]').className).not.toMatch(/bg-green-700|bg-red-700/)
    fireEvent.click(screen.getByRole('button', { name: /^(Edit|Düzenle)$/ }))
    expect(widget('oncall').querySelector('[data-slot="mydash-style"]')).toBeNull()
    expect(widget('monitor_health').querySelector('[data-slot="mydash-style"]')).not.toBeNull()
  })
})

describe('Bağlantı düğmeleri', () => {
  it('her bağlantı düğmesi data-slot taşır (kenarlık sıfırlaması [data-slot] üzerinden; yoksa tarayıcı kenarlığı çizilir)', async () => {
    render(<Shell initial={layout(['monitor_health'], ['alerts_open'], ['noc_light', 'sm'], ['maintenance'], ['incidents_open'])}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('monitor_health').querySelector('[data-slot="mydash-monitor-down"]')).not.toBeNull())
    await waitFor(() => expect(widget('maintenance').querySelector('[data-slot="mydash-maint-active"]')).not.toBeNull())
    const links = [...document.querySelectorAll('[data-link="true"]')]
    expect(links.length).toBeGreaterThan(8)
    expect(links.filter((b) => !b.hasAttribute('data-slot')).map((b) => b.textContent)).toEqual([])
  })
})

describe('7/24 komuta düzeni', () => {
  it('katalogdan onayla uygulanır; yetkisi olmayan kutu düşer, çizilemeyen kutu korunur', async () => {
    render(<Shell initial={layout(['gelecek_kutu', 'sm'], ['activity'])}><MyDashPage nocConsole /></Shell>)
    await waitFor(() => expect(ids()).toEqual(['activity']))
    fireEvent.click(screen.getByRole('button', { name: /^(Edit|Düzenle)$/ }))
    fireEvent.click(screen.getByRole('button', { name: /^(Add widget|Kutu ekle)$/ }))
    fireEvent.click(await screen.findByRole('button', { name: `${EN['mydash.preset.apply']}: ${EN['mydash.preset.title']}` }))
    const dlg = await screen.findByRole('dialog', { name: EN['mydash.preset.confirmTitle'] })
    fireEvent.click(within(dlg).getByRole('button', { name: EN['mydash.preset.apply'] }))
    await waitFor(() => expect(setSpy).toHaveBeenCalled())
    // storms / status_services: izin gerekmeyen hizmet durumu var; storms alerts.read ile görünür
    expect(setSpy.mock.calls.at(-1)[0].widgets.map((w) => w.type)).toEqual(['noc_light', 'noc_uncalled', 'alerts_open', 'maintenance',
      'monitor_health', 'storms', 'incidents_open', 'oncall', 'status_services', 'gelecek_kutu'])
    await waitFor(() => expect(widget('noc_uncalled')).not.toBeNull())
  })

  it('iptal edilirse düzen değişmez; alarm okuma yetkisi yoksa hazır düzen önerilmez', async () => {
    const { unmount } = render(<Shell initial={layout(['activity'])}><MyDashPage /></Shell>)
    await waitFor(() => expect(ids()).toEqual(['activity']))
    fireEvent.click(screen.getByRole('button', { name: /^(Edit|Düzenle)$/ }))
    fireEvent.click(screen.getByRole('button', { name: /^(Add widget|Kutu ekle)$/ }))
    fireEvent.click(await screen.findByRole('button', { name: `${EN['mydash.preset.apply']}: ${EN['mydash.preset.title']}` }))
    const dlg = await screen.findByRole('dialog', { name: EN['mydash.preset.confirmTitle'] })
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Cancel|İptal)$/ }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: EN['mydash.preset.confirmTitle'] })).toBeNull())
    expect(setSpy).not.toHaveBeenCalled()
    unmount()
    perm.value = { 'monitoring.read': { view: true } }
    render(<Shell initial={layout(['activity'])}><MyDashPage /></Shell>)
    await waitFor(() => expect(ids()).toEqual(['activity']))
    fireEvent.click(screen.getByRole('button', { name: /^(Edit|Düzenle)$/ }))
    fireEvent.click(screen.getByRole('button', { name: /^(Add widget|Kutu ekle)$/ }))
    await waitFor(() => expect(document.querySelector('[data-slot="mydash-catalog"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="mydash-preset"]')).toBeNull()
  })
})
