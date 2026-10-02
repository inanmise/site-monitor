import { describe, it, expect, vi, beforeEach } from 'vitest'
import { act, render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

/**
 * Favori izlemeler (2026-10-02, öneri 23):
 *  - kart: yıldız paylaşılan MonitorCardTop'tan (noc bağlamı) — shadcn ghost Button, aria-pressed, ad "Favorilere ekle: <ad>"
 *    / "Favorilerden çıkar: <ad>"; tıklama detayı AÇMAZ; seçim toplu PUT ile sunucuya gider; tercih sağlayıcısı yoksa
 *    (giriş öncesi / yalıtılmış ekran) kart BUGÜNKÜ gibi — yıldız yok
 *  - detay penceresi başlığında aynı anahtar
 *  - İzleme Panosu: "Favoriler" hızlı görünümü yalnız favori varken; sayı rozeti; tıklayınca liste favorilere süzülür (mo_fav)
 *  - komut paleti: boş sorguda "Favoriler" grubu; seçilince türün sayfasına adıyla aranmış gider
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const server = { doc: {} }

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  getRecentFailures: () => [],
  api: withApiFallback({
    me: { getPreferences: vi.fn(), savePreferences: vi.fn() },
    monitoring: {
      getOverview: vi.fn(),
      maintenance: { active: vi.fn(() => Promise.resolve({ success: true, data: { all: false, targets: [] } })) },
    },
    search: vi.fn(() => Promise.resolve({ success: true, data: [] })),
  }),
}))

import { api } from '../api/client'
import { useUserPrefsController, UserPrefsContext } from '../hooks/useUserPrefs.js'
import HttpMonitorCard from '../components/http/HttpMonitorCard.jsx'
import { MonitorDetailModal } from '../components/monitoring/MonitorDetail.jsx'
import MonitoringOverviewPage from '../components/MonitoringOverviewPage.jsx'
import CommandPalette from '../components/CommandPalette.jsx'
import { sortMonitorsDefault } from '../utils/monitorSort.js'

function PrefsShell({ children }) {
  const ctl = useUserPrefsController('ali', { debounceMs: 20 })
  return <UserPrefsContext.Provider value={ctl}>{children}</UserPrefsContext.Provider>
}

const URL_ = 'https://www.example.com/'
const MON = {
  id: 7, name: 'Corporate site', url: URL_, method: 'GET', status: 'up', ok: true, http_status: 200, response_ms: 212,
  active: true, team_id: 1, team_name: 'Takım A', interval_seconds: 600, checked_at: '2026-10-02T08:00:00',
}

const OVERVIEW = {
  success: true,
  data: {
    generated_at: '2026-10-02T08:00:00', window_hours: 24,
    totals: { total: 3, active: 3, paused: 0, deleted: 0, down: 1, stale: 0, unknown: 0, checks_window: 10, failed_window: 1, open_alerts: 0 },
    types: [],
    monitors: [
      { type: 'http', id: 1, name: 'API', target: 'https://api.example.com', team_id: 14, team_name: 'SY', active: true, status: 'down', checks_window: 5, failed_window: 1 },
      { type: 'http', id: 2, name: 'Portal', target: 'https://portal.example.com', team_id: 14, team_name: 'SY', active: true, status: 'up', checks_window: 5, failed_window: 0 },
      { type: 'ping', id: 3, name: 'GW', target: '10.0.0.1', team_id: 14, team_name: 'SY', active: true, status: 'up', checks_window: 5, failed_window: 0 },
    ],
  },
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  server.doc = {}
  window.history.replaceState({}, '', '/')
  api.me.getPreferences.mockImplementation(() => Promise.resolve({ success: true, prefs: server.doc }))
  api.me.savePreferences.mockImplementation((patch) => {
    server.doc = { ...server.doc, ...patch }
    return Promise.resolve({ success: true, prefs: server.doc })
  })
})

describe('favori yıldızı — izleme kartı ve detay penceresi', () => {
  it('kartta yıldız: aria-pressed + eylem adı; tıklama detayı açmaz; seçim sunucuya TEK PUT ile gider', async () => {
    const onOpen = vi.fn()
    const { container } = render(<PrefsShell><HttpMonitorCard monitor={MON} status="up" onOpen={onOpen} /></PrefsShell>)
    const star = await screen.findByRole('button', { name: /^(Add to favourites|Favorilere ekle): https:\/\/www\.example\.com\/$/ })
    expect(star).toHaveAttribute('aria-pressed', 'false')
    expect(star).toHaveAttribute('data-slot', 'favorite-toggle')
    // Kartın sağ üst grubunda (örtünün üstünde) — kart dosyası elle koymadı, MonitorCardTop çizdi
    expect(container.querySelector('[data-slot="card"]').contains(star)).toBe(true)
    fireEvent.click(star)
    expect(onOpen).not.toHaveBeenCalled()
    const on = screen.getByRole('button', { name: /^(Remove from favourites|Favorilerden çıkar): / })
    expect(on).toHaveAttribute('aria-pressed', 'true')
    await waitFor(() => expect(api.me.savePreferences).toHaveBeenCalledTimes(1))
    expect(api.me.savePreferences.mock.calls[0][0]).toEqual({ favorites: [{ type: 'http', id: 7, name: URL_ }] })
    // Geri alma da aynı yoldan
    fireEvent.click(on)
    await waitFor(() => expect(api.me.savePreferences).toHaveBeenCalledTimes(2))
    expect(api.me.savePreferences.mock.calls[1][0]).toEqual({ favorites: null })
  })

  it('tercih sağlayıcısı yoksa (giriş öncesi / yalıtılmış ekran) kart bugünkü gibi — yıldız YOK', () => {
    const { container } = render(<HttpMonitorCard monitor={MON} status="up" onOpen={() => {}} />)
    expect(container.querySelector('[data-slot="favorite-toggle"]')).toBeNull()
  })

  it('tercihler yüklenemezse (GET hatası) yıldız çizilmez', async () => {
    api.me.getPreferences.mockRejectedValueOnce(new Error('ağ'))
    const { container } = render(<PrefsShell><HttpMonitorCard monitor={MON} status="up" onOpen={() => {}} /></PrefsShell>)
    await waitFor(() => expect(api.me.getPreferences).toHaveBeenCalled())
    await new Promise((r) => setTimeout(r, 30))
    expect(container.querySelector('[data-slot="favorite-toggle"]')).toBeNull()
  })

  it('detay penceresi başlığında aynı anahtar (favori olan izleme basılı gelir)', async () => {
    server.doc = { favorites: [{ type: 'ping', id: 3, name: '10.0.0.1' }] }
    render(<PrefsShell>
      <MonitorDetailModal onClose={() => {}} title="10.0.0.1" noc={{ type: 'PING', monitor: { id: 3, host: '10.0.0.1', active: true }, canEdit: false }}>
        <p>gövde</p>
      </MonitorDetailModal>
    </PrefsShell>)
    const star = await screen.findByRole('button', { name: /^(Remove from favourites|Favorilerden çıkar): 10\.0\.0\.1$/ })
    expect(star).toHaveAttribute('aria-pressed', 'true')
  })

  it('favori, varsayılan kart sırasını DEĞİŞTİRMEZ (ürün kararı — süzgeç/kısayol, sıralama değil)', () => {
    // monitorSort yalnız durum / grup / ad okur; favori alanı sırayı etkilemez
    const list = [{ id: 1, name: 'b', status: 'up' }, { id: 2, name: 'a', status: 'up', favorite: true }]
    expect(sortMonitorsDefault(list, 'http').map((m) => m.id)).toEqual([2, 1])
    expect(sortMonitorsDefault([{ ...list[0], favorite: true }, { ...list[1], favorite: false }], 'http').map((m) => m.id)).toEqual([2, 1])
  })
})

describe('İzleme Panosu — "Favoriler" hızlı görünümü', () => {
  const rowsOf = (c) => [...c.querySelectorAll('[data-slot="mo-row"], [data-slot="mo-card"]')]

  it('favori yoksa hızlı görünümler bugünkü gibi (5 öğe, Favoriler yok)', async () => {
    api.monitoring.getOverview.mockResolvedValue(OVERVIEW)
    const { container } = render(<PrefsShell><MonitoringOverviewPage /></PrefsShell>)
    await waitFor(() => expect(container.querySelector('[data-slot="mo-views"]')).not.toBeNull())
    await waitFor(() => expect(api.me.getPreferences).toHaveBeenCalled())
    await new Promise((r) => setTimeout(r, 30))
    const views = container.querySelectorAll('[data-slot="mo-views"] [data-view]')
    expect([...views].map((v) => v.getAttribute('data-view'))).toEqual(['all', 'problems', 'alerts', 'failing', 'paused'])
  })

  it('favori varken "Favoriler" sayı rozetiyle görünür; seçilince liste favorilere süzülür ve URL mo_fav=1', async () => {
    server.doc = { favorites: [{ type: 'http', id: 2, name: 'Portal' }, { type: 'dns', id: 99, name: 'yok' }] }
    api.monitoring.getOverview.mockResolvedValue(OVERVIEW)
    const { container } = render(<PrefsShell><MonitoringOverviewPage /></PrefsShell>)
    const fav = await waitFor(() => {
      const el = container.querySelector('[data-slot="mo-views"] [data-view="favorites"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(fav.textContent).toMatch(/(Favourites|Favoriler)\s*1$/)   // yalnız listede bulunan favori sayılır
    const before = container.querySelector('[data-slot="mo-count"]').textContent
    fireEvent.click(fav)
    await waitFor(() => expect(container.querySelector('[data-slot="mo-count"]').textContent).not.toBe(before))
    expect(fav).toHaveAttribute('data-state', 'on')
    const shown = rowsOf(container)
    expect(shown.length).toBeGreaterThan(0)
    for (const r of shown) expect(r.textContent).toMatch(/Portal/)
    await waitFor(() => expect(new URLSearchParams(window.location.search).get('mo_fav')).toBe('1'))
    // "Tümü" favori süzgecini kapatır
    fireEvent.click(container.querySelector('[data-slot="mo-views"] [data-view="all"]'))
    await waitFor(() => expect(rowsOf(container).length).toBe(3))
  })
})

describe('komut paleti — Favoriler grubu', () => {
  const heading = (rx) => screen.queryByText(rx, { selector: '[cmdk-group-heading]' })

  it('boş sorguda Favoriler grubu en üstte; seçilince türün sayfasına adıyla aranmış gider; sorgu yazılınca gizlenir', async () => {
    server.doc = { favorites: [{ type: 'http', id: 2, name: 'Portal' }, { type: 'ping', id: 3 }] }
    const nav = vi.fn()
    window.addEventListener('sm:navigate', nav)
    render(<PrefsShell><CommandPalette tabs={[{ id: 'help', label: 'Help' }]} onTabChange={() => {}} /></PrefsShell>)
    await waitFor(() => expect(api.me.getPreferences).toHaveBeenCalled())
    await new Promise((r) => setTimeout(r, 30))
    act(() => { window.dispatchEvent(new CustomEvent('sm:palette')) })
    const h = await waitFor(() => { const el = heading(/^(Favourites|Favoriler)$/); expect(el).not.toBeNull(); return el })
    const group = h.closest('[cmdk-group]')
    expect(group).toHaveAttribute('data-group', 'favorites')
    const items = within(group).getAllByRole('option')
    expect(items).toHaveLength(2)
    expect(items[0].textContent).toMatch(/Portal/)
    expect(items[1].textContent).toMatch(/#3/)          // adı bilinmeyen favori tür + kimlikle
    // İlk grup Favoriler (Hızlı eylemlerden önce)
    const headings = [...document.querySelectorAll('[cmdk-group-heading]')].map((x) => x.textContent)
    expect(headings[0]).toMatch(/^(Favourites|Favoriler)$/)
    fireEvent.click(items[0])
    await waitFor(() => expect(nav).toHaveBeenCalled())
    expect(nav.mock.calls[0][0].detail).toEqual({ tab: 'http', params: { q: 'Portal' } })
    // Yeniden aç + sorgu: favoriler gizlenir
    act(() => { window.dispatchEvent(new CustomEvent('sm:palette')) })
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'he' } })
    await waitFor(() => expect(heading(/^(Favourites|Favoriler)$/)).toBeNull())
    window.removeEventListener('sm:navigate', nav)
  })

  it('favori yoksa grup yok (palet bugünkü gibi)', async () => {
    render(<PrefsShell><CommandPalette tabs={[{ id: 'help', label: 'Help' }]} onTabChange={() => {}} /></PrefsShell>)
    await waitFor(() => expect(api.me.getPreferences).toHaveBeenCalled())
    act(() => { window.dispatchEvent(new CustomEvent('sm:palette')) })
    expect(heading(/^(Favourites|Favoriler)$/)).toBeNull()
  })
})
