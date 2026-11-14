import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useState } from 'react'
import { render, screen, waitFor, fireEvent, within, act } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    me: { alertTrend: vi.fn() },
    monitoring: { getSparklines: vi.fn(), getAvailabilityWeek: vi.fn() },
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
import { addableTypes } from '../components/mydash/myDashModel.js'
import { clearSharedFetches } from '../components/mydash/useWidgetData.js'
import { hourKey } from '../components/mydash/seriesModel.js'
import { FavResponseWidget } from '../components/mydash/seriesWidgets.jsx'

/**
 * Kişisel Panom zaman serisi kutuları (2026-11-14): alarm eğilimi, favori izlemelerin yanıt süresi, 7 günlük erişilebilirlik.
 * Her kutu için: veri, boş durum, yetki yok, ilk yükleme hatası, tazeleme hatası (son veri kalır), eski sunucu (404 → "bu
 * sürümde yok"), düzenleme / TV kipinde tıklanmazlık. Bilinmeyen hiçbir zaman yeşil değildir.
 */
const LONG_NAME = 'Kurumsal İnternet Şubesi — Ödeme Onay Servisi (Dijital Kanallar / Bireysel ve Kurumsal Bankacılık Altyapısı)'
const PERMS = { 'alerts.read': { view: true }, 'monitoring.read': { view: true } }

const setSpy = vi.fn()
const dashRef = { current: null }
const favRef = { current: null }
function Shell({ initial = null, favorites = [], ready = true, status = 'ready', children }) {
  const [dashboard, setDashboard] = useState(initial)
  const [favs, setFavs] = useState(favorites)
  dashRef.current = setDashboard
  favRef.current = setFavs
  return (
    <UserPrefsContext.Provider value={{ ready, status, prefs: {}, favorites: favs, dashboard, setDashboard: setSpy,
      isFavorite: () => false, toggleFavorite: () => null }}>
      {children}
    </UserPrefsContext.Provider>
  )
}
const layout = (type, size = 'md', style) => ({ v: 1, widgets: [{ id: type.replace(/_/g, '-'), type, size, ...(style ? { style } : {}) }] })
const widget = (type) => document.querySelector(`[data-slot="mydash-widget"][data-type="${type}"]`)
const q = (type, slot) => widget(type)?.querySelector(`[data-slot="${slot}"]`)
const refresh = () => fireEvent.click(document.querySelector('[data-slot="mydash-refresh"]'))
const fail = (status) => ({ success: false, status, error: 'x' })
const deferred = () => { let resolve; const promise = new Promise((r) => { resolve = r }); return { promise, resolve } }

let navs = []
const onNav = (e) => navs.push(e.detail)

beforeEach(() => {
  vi.clearAllMocks()
  clearSharedFetches()
  localStorage.clear()
  navs = []
  window.addEventListener('sm:navigate', onNav)
  setSpy.mockImplementation((v) => { dashRef.current?.(v); return true })
  window.history.replaceState({}, '', '/?tab=mydash')
  perm.value = PERMS
})
afterEach(() => {
  window.removeEventListener('sm:navigate', onNav)
  window.history.replaceState({}, '', '/')
})

describe('Panom — zaman serisi kutuları "Kutu ekle"de', () => {
  it('izinle eklenebilir; izinsiz sunulmaz', () => {
    const all = addableTypes([], (p) => !!PERMS[p])
    expect(all).toEqual(expect.arrayContaining(['alert_trend', 'fav_response', 'availability_week']))
    expect(addableTypes([], (p) => p === 'alerts.read')).toContain('alert_trend')
    expect(addableTypes([], (p) => p === 'alerts.read')).not.toContain('fav_response')
    expect(addableTypes([], (p) => p === 'monitoring.read')).not.toContain('alert_trend')
    expect(addableTypes([], () => false)).not.toEqual(expect.arrayContaining(['availability_week']))
  })
})

// ── Alarm eğilimi ────────────────────────────────────────────────────────────────────────────────────────────────

const FROM_DAY = '2026-11-13T09:00:00'
function trendDay(over = {}) {
  const buckets = Array.from({ length: 24 }, (_, i) => {
    const t = new Date(Date.parse('2026-11-13T09:00:00Z') + i * 3_600_000).toISOString().slice(0, 19)
    return { t, opened: i === 5 ? 4 : i === 20 ? 2 : 0, closed: i === 6 ? 3 : 0 }
  })
  return { visible: true, range: '24h', bucket: 'hour', from: FROM_DAY, to: '2026-11-14T08:30:00', buckets,
    totals: { opened: 6, closed: 3, critical: 2, closed_silent: 1 }, ...over }
}
const FROM_WEEK = '2026-11-07T21:00:00'
function trendWeek() {
  const buckets = Array.from({ length: 7 }, (_, i) => ({
    t: new Date(Date.parse('2026-11-07T21:00:00Z') + i * 86_400_000).toISOString().slice(0, 19),
    day: `2026-11-${String(8 + i).padStart(2, '0')}`, opened: i, closed: i + 1,
  }))
  return { visible: true, range: '7d', bucket: 'day', from: FROM_WEEK, to: '2026-11-14T08:30:00', buckets,
    totals: { opened: 21, closed: 28, critical: 0, closed_silent: 0 } }
}

describe('Panom — Alarm eğilimi', () => {
  it('24 saat: sayılar, 24 kova, en yoğun saat, metin karşılığı; sayılar Alarm Geçmişi\'ni dönem başlangıcıyla açar', async () => {
    api.me.alertTrend.mockResolvedValue({ success: true, data: trendDay() })
    render(<Shell initial={layout('alert_trend')}><MyDashPage /></Shell>)
    await waitFor(() => expect(q('alert_trend', 'mydash-trend-chart')).not.toBeNull())
    expect(api.me.alertTrend).toHaveBeenCalledWith('24h')
    const w = widget('alert_trend')
    expect(w).toHaveAttribute('data-tone', 'warning')
    expect(w.querySelector('[data-slot="mydash-widget-tone"]')).toHaveTextContent('3 more opened')
    expect(q('alert_trend', 'mydash-trend-period')).toHaveTextContent(EN['mydash.trend.period.24h'])
    expect(q('alert_trend', 'mydash-trend-opened')).toHaveTextContent('6')
    expect(q('alert_trend', 'mydash-trend-closed')).toHaveTextContent('3')
    expect(q('alert_trend', 'mydash-trend-critical')).toHaveTextContent('2')
    expect(q('alert_trend', 'mydash-trend-critical')).toHaveAttribute('data-tone', 'danger')
    const cols = w.querySelectorAll('[data-slot="mydash-trend-col"]')
    expect(cols).toHaveLength(24)
    expect(cols[5]).toHaveAttribute('data-opened', '4')
    expect(cols[6]).toHaveAttribute('data-closed', '3')
    // seyrek eksen etiketleri (İstanbul saati: 09:00 UTC → 12:00)
    const ticks = [...w.querySelectorAll('[data-slot="mydash-trend-tick"]')].map((el) => el.textContent)
    expect(ticks).toEqual(['12:00', '18:00', '00:00', '06:00', '11:00'])
    expect(q('alert_trend', 'mydash-trend-peak')).toHaveTextContent('17:00')
    expect(q('alert_trend', 'mydash-trend-peak')).toHaveTextContent('4')
    // grafiğin metin karşılığı: adı + kova kova liste
    expect(q('alert_trend', 'mydash-trend-chart')).toHaveAttribute('aria-label', 'Last 24 hours: alarms opened 6, alarms closed 3')
    expect(w.querySelectorAll('[data-slot="mydash-trend-table"] li')).toHaveLength(24)
    expect(q('alert_trend', 'mydash-trend-silent')).toHaveTextContent('1')

    fireEvent.click(q('alert_trend', 'mydash-trend-opened'))
    expect(navs.at(-1)).toEqual({ tab: 'alerthistory', params: { view: 'all', from: FROM_DAY } })
    fireEvent.click(q('alert_trend', 'mydash-trend-closed'))
    expect(navs.at(-1)).toEqual({ tab: 'alerthistory', params: { view: 'closed', from: FROM_DAY } })
    fireEvent.click(q('alert_trend', 'mydash-trend-critical'))
    expect(navs.at(-1)).toEqual({ tab: 'alerthistory', params: { view: 'all', from: FROM_DAY, level: 'CRITICAL' } })
  })

  it('dönem değişimi: 7 gün istenir, yanıt gelene dek eski sayılar soluk ve tıklanmaz; seçim tarayıcıda saklanır', async () => {
    const week = deferred()
    api.me.alertTrend.mockImplementation((range) => (range === '7d' ? week.promise : Promise.resolve({ success: true, data: trendDay() })))
    render(<Shell initial={layout('alert_trend')}><MyDashPage /></Shell>)
    await waitFor(() => expect(q('alert_trend', 'mydash-trend-chart')).not.toBeNull())
    const group = q('alert_trend', 'mydash-trend-range')
    fireEvent.click(within(group).getByRole('radio', { name: EN['mydash.trend.range.7d'] }))
    await waitFor(() => expect(api.me.alertTrend).toHaveBeenCalledWith('7d'))
    expect(localStorage.getItem('sm.mydash.trend')).toBe('7d')
    expect(q('alert_trend', 'mydash-trend-body')).toHaveAttribute('aria-busy', 'true')
    expect(q('alert_trend', 'mydash-trend-opened').tagName).not.toBe('BUTTON')
    // başlık hâlâ GÖSTERİLEN verinin dönemini söyler
    expect(q('alert_trend', 'mydash-trend-period')).toHaveTextContent(EN['mydash.trend.period.24h'])
    await act(async () => { week.resolve({ success: true, data: trendWeek() }) })
    await waitFor(() => expect(widget('alert_trend').querySelectorAll('[data-slot="mydash-trend-col"]')).toHaveLength(7))
    expect(q('alert_trend', 'mydash-trend-body')).not.toHaveAttribute('aria-busy')
    expect(q('alert_trend', 'mydash-trend-period')).toHaveTextContent(EN['mydash.trend.period.7d'])
    expect([...widget('alert_trend').querySelectorAll('[data-slot="mydash-trend-tick"]')].map((el) => el.textContent))
      .toEqual(['8 Nov', '9 Nov', '10 Nov', '11 Nov', '12 Nov', '13 Nov', '14 Nov'])
    // kapanan fazla → nötr (yeşil değil — yeni alarm açıldı)
    expect(widget('alert_trend')).toHaveAttribute('data-tone', 'info')
    expect(widget('alert_trend').querySelector('[data-slot="mydash-widget-tone"]')).toHaveTextContent('7 more closed')
    fireEvent.click(q('alert_trend', 'mydash-trend-closed'))
    expect(navs.at(-1)).toEqual({ tab: 'alerthistory', params: { view: 'closed', from: FROM_WEEK } })
  })

  it('saklı dönemle açılır; dönemde alarm yok → yeşil "yeni alarm yok", grafik yerine açıklama', async () => {
    localStorage.setItem('sm.mydash.trend', '7d')
    const empty = trendWeek()
    empty.buckets = empty.buckets.map((b) => ({ ...b, opened: 0, closed: 0 }))
    api.me.alertTrend.mockResolvedValue({ success: true, data: empty })
    render(<Shell initial={layout('alert_trend')}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('alert_trend')).toHaveTextContent(EN['mydash.trend.none']))
    expect(api.me.alertTrend).toHaveBeenCalledWith('7d')
    expect(widget('alert_trend')).toHaveAttribute('data-tone', 'success')
    expect(q('alert_trend', 'mydash-trend-chart')).toBeNull()
    expect(q('alert_trend', 'mydash-trend-opened')).toHaveTextContent('0')
  })

  it('yetki yok (visible:false) → gri "yetki yok"; tanınmayan yanıt → "veri yok" (yeşil değil)', async () => {
    api.me.alertTrend.mockResolvedValue({ success: true, data: { visible: false, range: '24h' } })
    const r = render(<Shell initial={layout('alert_trend')}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('alert_trend')).toHaveTextContent(EN['mydash.noAccessBody']))
    expect(widget('alert_trend')).toHaveAttribute('data-tone', 'muted')
    expect(q('alert_trend', 'mydash-trend-range')).toBeNull()
    r.unmount()
    clearSharedFetches()
    api.me.alertTrend.mockResolvedValue({ success: true, data: { visible: true } })
    render(<Shell initial={layout('alert_trend')}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('alert_trend')).toHaveTextContent(EN['mydash.noData']))
    expect(widget('alert_trend')).toHaveAttribute('data-tone', 'muted')
  })

  it('ilk yükleme hatası → "veri alınamadı" (sayı / renk yok); eski sunucu (404) → "bu sürümde yok", hata DEĞİL', async () => {
    api.me.alertTrend.mockResolvedValue(fail(503))
    const r = render(<Shell initial={layout('alert_trend')}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('alert_trend')).toHaveAttribute('data-failed', 'true'))
    expect(widget('alert_trend')).toHaveAttribute('data-tone', 'muted')
    expect(q('alert_trend', 'mydash-trend-opened')).toBeNull()
    r.unmount()
    clearSharedFetches()
    api.me.alertTrend.mockResolvedValue(fail(404))
    render(<Shell initial={layout('alert_trend')}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('alert_trend')).toHaveTextContent(EN['mydash.series.unsupportedBody']))
    expect(widget('alert_trend')).not.toHaveAttribute('data-failed')
    expect(widget('alert_trend')).toHaveAttribute('data-tone', 'muted')
    expect(widget('alert_trend').querySelector('[data-slot="mydash-widget-tone"]')).toHaveTextContent(EN['mydash.series.unsupported'])
  })

  it('tazeleme hatası: son veri kalır, alt notta "güncellenemedi"', async () => {
    api.me.alertTrend.mockResolvedValueOnce({ success: true, data: trendDay() }).mockResolvedValue(fail(500))
    render(<Shell initial={layout('alert_trend')}><MyDashPage /></Shell>)
    await waitFor(() => expect(q('alert_trend', 'mydash-trend-chart')).not.toBeNull())
    refresh()
    await waitFor(() => expect(q('alert_trend', 'mydash-widget-error')).not.toBeNull())
    expect(q('alert_trend', 'mydash-trend-opened')).toHaveTextContent('6')
    expect(widget('alert_trend')).not.toHaveAttribute('data-failed')
  })

  it('düzenleme kipi: sayılar tıklanmaz (düz metin), dönem seçimi çalışır; TV: düğme yok, tıklanmaz, ayrıntılı kutu', async () => {
    api.me.alertTrend.mockResolvedValue({ success: true, data: trendDay() })
    const r = render(<Shell initial={layout('alert_trend')}><MyDashPage /></Shell>)
    await waitFor(() => expect(q('alert_trend', 'mydash-trend-chart')).not.toBeNull())
    fireEvent.click(screen.getByRole('button', { name: EN['mydash.edit'] }))
    await waitFor(() => expect(q('alert_trend', 'mydash-widget-tools')).not.toBeNull())
    expect(widget('alert_trend').querySelector('[data-link="true"]')).toBeNull()
    expect(q('alert_trend', 'mydash-trend-range')).not.toBeNull()
    // renkli panel görünümü yok (grafik tek sayıya inmez)
    expect(q('alert_trend', 'mydash-style')).toBeNull()
    r.unmount()
    clearSharedFetches()
    window.history.replaceState({}, '', '/?tab=mydash&md_tv=1')
    render(<Shell initial={layout('alert_trend')}><MyDashPage /></Shell>)
    const tv = await waitFor(() => { const el = document.querySelector('[data-slot="mydash-tv"]'); expect(el).not.toBeNull(); return el })
    await waitFor(() => expect(tv.querySelector('[data-slot="mydash-trend-chart"]')).not.toBeNull())
    expect(tv.querySelector('[data-slot="mydash-trend-range"]')).toBeNull()
    expect(tv.querySelector('[data-type="alert_trend"] [data-link="true"]')).toBeNull()
    expect(tv.querySelector('[data-type="alert_trend"]')).not.toHaveAttribute('data-stat')
    expect(tv.querySelector('[data-slot="mydash-trend-period"]')).toHaveTextContent(EN['mydash.trend.period.24h'])
    // TV: kutucuk yerine tek satır sayılar (yükseklik grafiğe kalır); rehber notu yok
    expect(tv.querySelector('[data-slot="mydash-trend-totals"] [data-slot="mydash-trend-opened"]')).toHaveTextContent('6')
    expect(tv.querySelector('[data-slot="mydash-trend-critical"]')).toHaveTextContent('2')
    expect(tv.querySelector('[data-slot="mydash-trend-silent"]')).toBeNull()
  })
})

// ── Favori izlemelerin yanıt süresi ──────────────────────────────────────────────────────────────────────────────

const NOW = Date.now()
const bucket = (hoursAgo, ms, n = 4, failN = 0) => ({ t: hourKey(NOW - hoursAgo * 3_600_000), n, fail: failN, ms })
const spark = (over = {}) => ({ n: 12, fail: 0, up_pct: 100, buckets: [bucket(3, 110), bucket(2, 130), bucket(0, 150)], last: [{ at: 'x', ok: true, ms: 180 }], ...over })
const FAVS = [
  { type: 'http', id: 1, name: LONG_NAME }, { type: 'ping', id: 3, name: 'Çekirdek yönlendirici' }, { type: 'domain', id: 9, name: 'ornek.com' },
]
function sparkApi(byType) {
  api.monitoring.getSparklines.mockImplementation((type) => Promise.resolve(byType[type] ?? { success: true, data: {} }))
}

describe('Panom — Favorilerin yanıt süresi', () => {
  it('tür başına tek istek (yalnız favoriler, alan adı kaydı istenmez); hata veren önde; satır izlemenin ayrıntısını açar', async () => {
    sparkApi({
      http: { success: true, data: { 1: spark() } },
      ping: { success: true, data: { 3: spark({ n: 6, fail: 2, up_pct: 66.7, buckets: [bucket(1, 40), bucket(0, null, 2, 2)], last: [{ ok: true, ms: 41 }, { ok: false, ms: null }] }) } },
    })
    render(<Shell initial={layout('fav_response')} favorites={FAVS}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('fav_response')?.querySelectorAll('[data-slot="mydash-fav-row"]')).toHaveLength(2))
    expect(api.monitoring.getSparklines).toHaveBeenCalledTimes(2)
    expect(api.monitoring.getSparklines).toHaveBeenCalledWith('http', 24, [1])
    expect(api.monitoring.getSparklines).toHaveBeenCalledWith('ping', 24, [3])
    const rows = widget('fav_response').querySelectorAll('[data-slot="mydash-fav-row"]')
    // hata veren önde
    expect(rows[0]).toHaveTextContent('Çekirdek yönlendirici')
    expect(rows[0]).toHaveTextContent(EN['mydash.fav.failing'])
    expect(rows[0].querySelector('[data-slot="mydash-fav-last"]')).toBeNull()
    expect(rows[1]).toHaveTextContent(LONG_NAME)
    expect(rows[1].querySelector('[data-slot="mydash-fav-last"]')).toHaveTextContent(/180\s*ms/)
    expect(rows[1]).toHaveTextContent('24 h avg')
    expect(rows[1]).toHaveTextContent('success 100%')
    // mini grafik: 3 ölçüm → iki parça (2 ardışık saat çizgi, tek saat nokta); boş saatler çizilmez
    const sp = rows[1].querySelector('[data-slot="mydash-spark"]')
    expect(sp).toHaveAttribute('data-points', '3')
    // boyut satır içi stilde — satır bir düğme; düğmenin `svg { size-4 }` kuralı grafiği 16 px'e indirmesin
    expect(sp.style.width).toBe('96px')
    expect(sp.style.height).toBe('28px')
    expect(sp.querySelectorAll('[data-slot="mydash-spark-line"]')).toHaveLength(1)
    expect(sp.querySelectorAll('[data-slot="mydash-spark-dot"]')).toHaveLength(1)
    expect(rows[0].querySelector('[data-slot="mydash-spark-fail"]')).not.toBeNull()
    expect(widget('fav_response')).toHaveAttribute('data-tone', 'danger')
    expect(widget('fav_response').querySelector('[data-slot="mydash-widget-tone"]')).toHaveTextContent('1 failing')
    // süre ölçmeyen favori sessizce düşmez
    expect(q('fav_response', 'mydash-fav-skipped')).toHaveTextContent('1')
    fireEvent.click(within(rows[0]).getByRole('button'))
    expect(navs.at(-1)).toEqual({ tab: 'ping', params: { monitor: 3 } })
    fireEvent.click(within(rows[1]).getByRole('button'))
    expect(navs.at(-1)).toEqual({ tab: 'http', params: { monitor: 1 } })
  })

  it('hepsi yanıt veriyor → yeşil; kontrolü olmayan / bulunamayan favori varsa gri (bilinmeyen yeşil değil), bulunamayan tıklanmaz', async () => {
    sparkApi({ http: { success: true, data: { 1: spark() } }, ping: { success: true, data: { 3: spark() } } })
    const r = render(<Shell initial={layout('fav_response')} favorites={FAVS.slice(0, 2)}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('fav_response')).toHaveAttribute('data-tone', 'success'))
    expect(widget('fav_response').querySelector('[data-slot="mydash-widget-tone"]')).toHaveTextContent(EN['mydash.fav.tone.ok'])
    expect(q('fav_response', 'mydash-fav-skipped')).toBeNull()
    r.unmount()
    clearSharedFetches()
    sparkApi({ http: { success: true, data: { 1: spark({ n: 0, buckets: [], last: [] }) } }, ping: { success: true, data: {} } })
    render(<Shell initial={layout('fav_response')} favorites={FAVS.slice(0, 2)}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('fav_response')?.querySelectorAll('[data-slot="mydash-fav-row"]')).toHaveLength(2))
    expect(widget('fav_response')).toHaveAttribute('data-tone', 'muted')
    expect(widget('fav_response').querySelector('[data-slot="mydash-widget-tone"]')).toHaveTextContent('2 not measured')
    const rows = widget('fav_response').querySelectorAll('[data-slot="mydash-fav-row"]')
    expect(rows[0]).toHaveTextContent(EN['mydash.fav.nodata'])
    expect(rows[0].querySelector('[data-slot="mydash-spark-empty"]')).not.toBeNull()
    expect(rows[1]).toHaveTextContent(EN['mydash.fav.missing'])
    expect(within(rows[1]).queryByRole('button')).toBeNull()
  })

  it('favori yok → nasıl ekleneceğini söyler, istek atılmaz; yalnız alan adı kaydı favorisi → ayrı açıklama', async () => {
    const r = render(<Shell initial={layout('fav_response')} favorites={[]}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('fav_response')).toHaveTextContent(EN['mydash.fav.empty']))
    expect(widget('fav_response')).toHaveAttribute('data-tone', 'muted')
    expect(widget('fav_response').querySelector('[data-slot="mydash-widget-tone"]')).toHaveTextContent(EN['mydash.fav.tone.none'])
    expect(api.monitoring.getSparklines).not.toHaveBeenCalled()
    expect(widget('fav_response')).not.toHaveAttribute('data-failed')
    r.unmount()
    render(<Shell initial={layout('fav_response')} favorites={[FAVS[2]]}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('fav_response')).toHaveTextContent(EN['mydash.fav.emptyNoChart']))
    expect(api.monitoring.getSparklines).not.toHaveBeenCalled()
  })

  it('yetki yok (403) → gri "yetki yok"; bir tür okunamadı → kutu hata durumunda (yarım veriden yeşil çıkmaz)', async () => {
    sparkApi({ http: fail(403), ping: { success: true, data: { 3: spark() } } })
    const r = render(<Shell initial={layout('fav_response')} favorites={FAVS}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('fav_response')).toHaveTextContent(EN['mydash.noAccessBody']))
    expect(widget('fav_response')).toHaveAttribute('data-tone', 'muted')
    r.unmount()
    clearSharedFetches()
    sparkApi({ http: { success: true, data: { 1: spark() } }, ping: fail(500) })
    render(<Shell initial={layout('fav_response')} favorites={FAVS}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('fav_response')).toHaveAttribute('data-failed', 'true'))
    expect(widget('fav_response')).toHaveAttribute('data-tone', 'muted')
    expect(q('fav_response', 'mydash-fav-list')).toBeNull()
  })

  it('tazeleme hatası: satırlar kalır, alt notta "güncellenemedi"', async () => {
    api.monitoring.getSparklines
      .mockResolvedValueOnce({ success: true, data: { 1: spark() } })
      .mockResolvedValue(fail(500))
    render(<Shell initial={layout('fav_response')} favorites={[FAVS[0]]}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('fav_response')?.querySelectorAll('[data-slot="mydash-fav-row"]')).toHaveLength(1))
    refresh()
    await waitFor(() => expect(q('fav_response', 'mydash-widget-error')).not.toBeNull())
    expect(widget('fav_response').querySelectorAll('[data-slot="mydash-fav-row"]')).toHaveLength(1)
  })

  it('favori listesi değişince yeni kimliklerle hemen yeniden istenir; yeni favori yanıt gelene dek "bulunamadı" görünmez', async () => {
    const next = deferred()
    api.monitoring.getSparklines.mockImplementation((type, hours, ids) => (
      ids.includes(5) ? next.promise : Promise.resolve({ success: true, data: { 1: spark() } })))
    render(<Shell initial={layout('fav_response')} favorites={[FAVS[0]]}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('fav_response')).toHaveAttribute('data-tone', 'success'))
    act(() => favRef.current([FAVS[0], { type: 'http', id: 5, name: 'Yeni favori' }]))
    await waitFor(() => expect(api.monitoring.getSparklines).toHaveBeenCalledWith('http', 24, [1, 5]))
    // yanıt yolda: eski satır durur, yeni favori "bulunamadı" DEĞİL, ton yeşil değil
    expect(widget('fav_response')).not.toHaveTextContent(EN['mydash.fav.missing'])
    expect(widget('fav_response')).toHaveAttribute('data-tone', 'muted')
    await act(async () => { next.resolve({ success: true, data: { 1: spark(), 5: spark() } }) })
    await waitFor(() => expect(widget('fav_response').querySelectorAll('[data-slot="mydash-fav-row"]')).toHaveLength(2))
    expect(widget('fav_response')).toHaveAttribute('data-tone', 'success')
  })

  it('tercihler okunmadan "favori yok" denmez (yükleniyor); tercihler okunamadıysa hata durumu', async () => {
    const r = render(<Shell initial={layout('fav_response')} favorites={[]} ready={false} status="loading"><FavOnly /></Shell>)
    expect(widget('fav_response')).not.toHaveTextContent(EN['mydash.fav.empty'])
    expect(widget('fav_response')).toHaveTextContent(EN['mydash.loading'])
    r.unmount()
    render(<Shell initial={layout('fav_response')} favorites={[]} ready={false} status="failed"><FavOnly /></Shell>)
    expect(widget('fav_response')).toHaveAttribute('data-failed', 'true')
    expect(widget('fav_response')).not.toHaveTextContent(EN['mydash.fav.empty'])
  })

  it('TV: kısa liste (orta kutu 3 satır) + "daha fazla" sayısı; rehber notu yok; satırlar tıklanmaz; ayrıntılı kutu (panel değil)', async () => {
    const favs = [1, 2, 3, 4, 5].map((id) => ({ type: 'http', id, name: `İzleme ${id}` })).concat([FAVS[2]])
    sparkApi({ http: { success: true, data: Object.fromEntries([1, 2, 3, 4, 5].map((id) => [id, spark()])) } })
    window.history.replaceState({}, '', '/?tab=mydash&md_tv=1')
    render(<Shell initial={layout('fav_response')} favorites={favs}><MyDashPage /></Shell>)
    const tv = await waitFor(() => { const el = document.querySelector('[data-slot="mydash-tv"]'); expect(el).not.toBeNull(); return el })
    await waitFor(() => expect(tv.querySelectorAll('[data-slot="mydash-fav-row"]')).toHaveLength(3))
    expect(tv.querySelector('[data-type="fav_response"]')).toHaveTextContent(EN['mydash.more'].replace('{0}', '2'))
    expect(tv.querySelector('[data-slot="mydash-fav-skipped"]')).toBeNull()
    expect(tv.querySelector('[data-type="fav_response"] [data-link="true"]')).toBeNull()
    expect(tv.querySelector('[data-type="fav_response"]')).not.toHaveAttribute('data-stat')
    // ton TÜM favorilerden (gösterilmeyenler dahil)
    expect(tv.querySelector('[data-type="fav_response"]')).toHaveAttribute('data-tone', 'success')
  })

  it('düzenleme kipinde satırlar tıklanmaz; renkli panel görünümü yok', async () => {
    sparkApi({ http: { success: true, data: { 1: spark() } } })
    render(<Shell initial={layout('fav_response')} favorites={[FAVS[0]]}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('fav_response')?.querySelectorAll('[data-slot="mydash-fav-row"]')).toHaveLength(1))
    fireEvent.click(screen.getByRole('button', { name: EN['mydash.edit'] }))
    await waitFor(() => expect(q('fav_response', 'mydash-widget-tools')).not.toBeNull())
    expect(widget('fav_response').querySelector('[data-link="true"]')).toBeNull()
    expect(q('fav_response', 'mydash-style')).toBeNull()
    expect(widget('fav_response').querySelectorAll('[data-slot="mydash-fav-row"]')).toHaveLength(1)
  })
})

/** Tercihler hazır değilken sayfa kutuları çizmez — kutu doğrudan (aynı çerçeveyle) denenir. */
function FavOnly() {
  return <FavResponseWidget frame={{ widget: { id: 'fav-response', type: 'fav_response', size: 'md' }, edit: false, tv: false }} refreshKey={0} />
}

// ── 7 günlük erişilebilirlik ─────────────────────────────────────────────────────────────────────────────────────

const week = (over = {}) => ({ days: 7, target_pct: 99.9, monitors: 12, unmeasured: 1, checks: 40000, failed: 12, availability_pct: 99.97,
  meets_target: true, below_target: 0, by_type: [], worst: [], generated_at: '2026-11-14T08:30:00', ...over })
const WORST = [
  { type: 'http', id: 4, name: LONG_NAME, up_pct: 97.5, checks: 2000, failed: 50, bad_hours: 6 },
  { type: 'ping', id: 9, name: 'Şube yönlendiricisi', up_pct: 99.4, checks: 1000, failed: 6, bad_hours: 2 },
]
const AVAIL_NAV = { tab: 'monitoring', params: { mo_ck: 'failed', mo_win: '168' } }

describe('Panom — 7 günlük erişilebilirlik', () => {
  it('hedefte: yeşil gösterge, değer + hedef; sayı İzleme Panosu\'nu 7 günlük "hatalı kontrol" süzgeciyle açar', async () => {
    api.monitoring.getAvailabilityWeek.mockResolvedValue({ success: true, data: week() })
    render(<Shell initial={layout('availability_week', 'sm')}><MyDashPage /></Shell>)
    await waitFor(() => expect(q('availability_week', 'mydash-gauge')).not.toBeNull())
    const w = widget('availability_week')
    expect(w).toHaveAttribute('data-tone', 'success')
    expect(w.querySelector('[data-slot="mydash-widget-tone"]')).toHaveTextContent(EN['mydash.avail.tone.ok'])
    expect(q('availability_week', 'mydash-avail-value')).toHaveTextContent('99.97%')
    expect(q('availability_week', 'mydash-avail-value')).toHaveTextContent('Target 99.9%')
    expect(q('availability_week', 'mydash-gauge')).toHaveAttribute('data-tone', 'success')
    expect(q('availability_week', 'mydash-gauge-value')).not.toBeNull()
    expect(q('availability_week', 'mydash-gauge-target')).not.toBeNull()
    expect(w.querySelector('[data-slot="mydash-gauge"] svg')).toHaveAttribute('aria-label', 'Availability 99.97%, target 99.9%')
    expect(q('availability_week', 'mydash-avail-verdict')).toHaveTextContent(EN['mydash.avail.meets'])
    expect(q('availability_week', 'mydash-avail-measured')).toHaveTextContent('Monitors measured: 12 · checks: 40,000, failed: 12')
    expect(q('availability_week', 'mydash-avail-unmeasured')).toHaveTextContent('1')
    fireEvent.click(q('availability_week', 'mydash-avail-value'))
    expect(navs.at(-1)).toEqual(AVAIL_NAV)
  })

  it('hedefin altında: 1 puana kadar turuncu, daha düşük kırmızı; en kötü izlemeler ayrıntıyı açar; küçük kutuda liste yok', async () => {
    api.monitoring.getAvailabilityWeek.mockResolvedValue({ success: true, data: week({ availability_pct: 99.45, meets_target: false, below_target: 7, worst: WORST }) })
    const r = render(<Shell initial={layout('availability_week', 'md')}><MyDashPage /></Shell>)
    await waitFor(() => expect(q('availability_week', 'mydash-avail-worst')).not.toBeNull())
    expect(widget('availability_week')).toHaveAttribute('data-tone', 'warning')
    expect(widget('availability_week').querySelector('[data-slot="mydash-widget-tone"]')).toHaveTextContent(EN['mydash.avail.tone.below'])
    expect(q('availability_week', 'mydash-avail-verdict')).toHaveTextContent('0.45 points below target.')
    const rows = widget('availability_week').querySelectorAll('[data-slot="mydash-avail-worst"] li')
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent(LONG_NAME)
    expect(rows[0]).toHaveTextContent('97.5%')
    expect(rows[0].querySelector('[data-slot="badge"]')).toHaveAttribute('data-tone', 'danger')    // hedefin 1 puandan fazla altı
    expect(rows[1].querySelector('[data-slot="badge"]')).toHaveAttribute('data-tone', 'warning')
    // listede olmayan hedef altı izlemeler sayıyla söylenir
    expect(widget('availability_week')).toHaveTextContent(EN['mydash.more'].replace('{0}', '5'))
    fireEvent.click(within(rows[0]).getByRole('button'))
    expect(navs.at(-1)).toEqual({ tab: 'http', params: { monitor: 4 } })
    r.unmount()
    clearSharedFetches()
    api.monitoring.getAvailabilityWeek.mockResolvedValue({ success: true, data: week({ availability_pct: 97.2, meets_target: false, below_target: 2, worst: WORST }) })
    render(<Shell initial={layout('availability_week', 'sm')}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('availability_week')).toHaveAttribute('data-tone', 'danger'))
    expect(q('availability_week', 'mydash-avail-worst')).toBeNull()
    // liste yokken "sayfada N kayıt daha" denmez — sayı zaten "hedefin altındaki izleme" satırında
    expect(widget('availability_week')).not.toHaveTextContent('More on the page')
    expect(q('availability_week', 'mydash-avail-measured')).toHaveTextContent('Monitors below target: 2')
    expect(q('availability_week', 'mydash-gauge-value')).toBeNull()   // değer ölçeğin altında → yay boş, sayı ve renk söyler
    expect(q('availability_week', 'mydash-avail-value')).toHaveTextContent('97.2%')
  })

  it('ölçüm yok → gri "ölçüm yok" (yeşil değil), yay ve hedef çentiği çizilmez; izleme yoksa ayrı metin', async () => {
    api.monitoring.getAvailabilityWeek.mockResolvedValue({ success: true, data: week({ availability_pct: null, meets_target: null, monitors: 0, unmeasured: 3, checks: 0, failed: 0 }) })
    const r = render(<Shell initial={layout('availability_week', 'sm')}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('availability_week')).toHaveTextContent(EN['mydash.avail.noData']))
    expect(widget('availability_week')).toHaveAttribute('data-tone', 'muted')
    expect(widget('availability_week').querySelector('[data-slot="mydash-widget-tone"]')).toHaveTextContent(EN['mydash.avail.tone.noData'])
    expect(q('availability_week', 'mydash-gauge-value')).toBeNull()
    expect(q('availability_week', 'mydash-gauge-target')).toBeNull()
    expect(q('availability_week', 'mydash-avail-value')).toHaveTextContent('—')
    expect(q('availability_week', 'mydash-avail-value').tagName).not.toBe('BUTTON')
    r.unmount()
    clearSharedFetches()
    api.monitoring.getAvailabilityWeek.mockResolvedValue({ success: true, data: week({ availability_pct: null, meets_target: null, monitors: 0, unmeasured: 0, checks: 0, failed: 0 }) })
    render(<Shell initial={layout('availability_week', 'sm')}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('availability_week')).toHaveTextContent(EN['mydash.avail.noMonitors']))
  })

  it('renkli panel: değer + hedef, ton zemini; ölçüm yoksa boyanmaz', async () => {
    api.monitoring.getAvailabilityWeek.mockResolvedValue({ success: true, data: week({ availability_pct: 99.45, meets_target: false, below_target: 3 }) })
    const r = render(<Shell initial={layout('availability_week', 'sm', 'stat')}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('availability_week')).toHaveAttribute('data-stat', 'on'))
    expect(widget('availability_week')).toHaveAttribute('data-tone', 'warning')
    expect(q('availability_week', 'mydash-stat-value')).toHaveTextContent('99.45%')
    expect(q('availability_week', 'mydash-stat')).toHaveTextContent('Target 99.9%')
    expect(q('availability_week', 'mydash-stat')).toHaveTextContent('Monitors below target: 3')
    fireEvent.click(q('availability_week', 'mydash-stat'))
    expect(navs.at(-1)).toEqual(AVAIL_NAV)
    r.unmount()
    clearSharedFetches()
    api.monitoring.getAvailabilityWeek.mockResolvedValue({ success: true, data: week({ availability_pct: null, meets_target: null, monitors: 0 }) })
    render(<Shell initial={layout('availability_week', 'sm', 'stat')}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('availability_week')).toHaveTextContent(EN['mydash.avail.noData']))
    expect(widget('availability_week')).not.toHaveAttribute('data-stat', 'on')
  })

  it('yetki yok (403) → gri; eski sunucu (404 / 405) → "bu sürümde yok"; okunamadı (503) → hata durumu', async () => {
    api.monitoring.getAvailabilityWeek.mockResolvedValue(fail(403))
    const r = render(<Shell initial={layout('availability_week', 'sm')}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('availability_week')).toHaveTextContent(EN['mydash.noAccessBody']))
    expect(widget('availability_week')).toHaveAttribute('data-tone', 'muted')
    r.unmount()
    clearSharedFetches()
    api.monitoring.getAvailabilityWeek.mockResolvedValue(fail(405))
    const r2 = render(<Shell initial={layout('availability_week', 'sm')}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('availability_week')).toHaveTextContent(EN['mydash.series.unsupportedBody']))
    expect(widget('availability_week')).not.toHaveAttribute('data-failed')
    expect(widget('availability_week')).toHaveAttribute('data-tone', 'muted')
    expect(q('availability_week', 'mydash-gauge')).toBeNull()
    r2.unmount()
    clearSharedFetches()
    api.monitoring.getAvailabilityWeek.mockResolvedValue(fail(503))
    render(<Shell initial={layout('availability_week', 'sm')}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('availability_week')).toHaveAttribute('data-failed', 'true'))
    expect(q('availability_week', 'mydash-gauge')).toBeNull()
  })

  it('tazeleme hatası: gösterge kalır, alt notta "güncellenemedi"', async () => {
    api.monitoring.getAvailabilityWeek.mockResolvedValueOnce({ success: true, data: week() }).mockResolvedValue(fail(500))
    render(<Shell initial={layout('availability_week', 'sm')}><MyDashPage /></Shell>)
    await waitFor(() => expect(q('availability_week', 'mydash-gauge')).not.toBeNull())
    refresh()
    await waitFor(() => expect(q('availability_week', 'mydash-widget-error')).not.toBeNull())
    expect(q('availability_week', 'mydash-avail-value')).toHaveTextContent('99.97%')
  })

  it('düzenleme kipi: sayı tıklanmaz, renkli panel düğmesi var; TV (renkli paneller): tek hücrelik panel, tıklanmaz', async () => {
    api.monitoring.getAvailabilityWeek.mockResolvedValue({ success: true, data: week({ worst: WORST, availability_pct: 99.45, meets_target: false, below_target: 2 }) })
    const r = render(<Shell initial={layout('availability_week', 'md')}><MyDashPage /></Shell>)
    await waitFor(() => expect(q('availability_week', 'mydash-gauge')).not.toBeNull())
    fireEvent.click(screen.getByRole('button', { name: EN['mydash.edit'] }))
    await waitFor(() => expect(q('availability_week', 'mydash-widget-tools')).not.toBeNull())
    expect(widget('availability_week').querySelector('[data-link="true"]')).toBeNull()
    expect(q('availability_week', 'mydash-avail-value').tagName).not.toBe('BUTTON')
    expect(q('availability_week', 'mydash-style')).not.toBeNull()
    r.unmount()
    clearSharedFetches()
    window.history.replaceState({}, '', '/?tab=mydash&md_tv=1')
    render(<Shell initial={layout('availability_week', 'md')}><MyDashPage /></Shell>)
    const tv = await waitFor(() => { const el = document.querySelector('[data-slot="mydash-tv"]'); expect(el).not.toBeNull(); return el })
    await waitFor(() => expect(tv.querySelector('[data-type="availability_week"]')).toHaveAttribute('data-stat', 'on'))
    expect(tv.querySelector('[data-slot="mydash-stat-value"]')).toHaveTextContent('99.45%')
    expect(tv.querySelector('[data-type="availability_week"] [data-link="true"]')).toBeNull()
    expect(tv.querySelector('[data-slot="mydash-avail-worst"]')).toBeNull()
  })

  it('TV ayrıntılı görünüm (renkli paneller kapalı): gösterge + hüküm; liste ve ölçüm satırları yok (hücreye sığar), tıklanmaz', async () => {
    localStorage.setItem('sm.mydash.tv', JSON.stringify({ interval: 0, panels: 'detail', density: 'compact' }))
    api.monitoring.getAvailabilityWeek.mockResolvedValue({ success: true, data: week({ worst: WORST, availability_pct: 99.45, meets_target: false, below_target: 2 }) })
    window.history.replaceState({}, '', '/?tab=mydash&md_tv=1')
    render(<Shell initial={layout('availability_week', 'lg')}><MyDashPage /></Shell>)
    const tv = await waitFor(() => { const el = document.querySelector('[data-slot="mydash-tv"]'); expect(el).not.toBeNull(); return el })
    await waitFor(() => expect(tv.querySelector('[data-slot="mydash-gauge"]')).not.toBeNull())
    expect(tv.querySelector('[data-type="availability_week"]')).not.toHaveAttribute('data-stat')
    expect(tv.querySelector('[data-slot="mydash-avail-verdict"]')).toHaveTextContent('0.45 points below target. Monitors below target: 2')
    expect(tv.querySelector('[data-slot="mydash-avail-worst"]')).toBeNull()
    expect(tv.querySelector('[data-slot="mydash-avail-measured"]')).toBeNull()
    expect(tv.querySelector('[data-slot="mydash-avail-value"]').tagName).not.toBe('BUTTON')
  })
})
