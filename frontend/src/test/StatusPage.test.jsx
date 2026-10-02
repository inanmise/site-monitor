import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import StatusPage, { STATUS_POLL_MS } from '../components/StatusPage.jsx'

// Kurum içi Durum Sayfası (2026-10-01): şerit, takım grupları (sorunlu önce + Türkçe A→Z), durum rozetleri (ikon + metin,
// data-state), izleme listesinin yalnız görülebilen takımda olması, olay/bakım bölümleri, boş durumlar, hata + yeniden
// deneme, görünürlük-farkında yoklama (useVisibleInterval, 60 sn).
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({ statusPage: { get: vi.fn() } }),
}))
vi.mock('../hooks/useVisibleInterval.js', () => ({ useVisibleInterval: vi.fn() }))
import { api } from '../api/client'
import { useVisibleInterval } from '../hooks/useVisibleInterval.js'

const iso = (min) => new Date(Date.now() + min * 60_000).toISOString().slice(0, 19)

const svc = (o) => ({ ungrouped: false, monitors_total: 2, monitors_active: 2, up: 2, degraded: 0, down: 0, maintenance: 0, unknown: 0,
  paused: 0, types: ['http'], since: null, maintenance_until: null, uptime_7d: null, monitors_visible: false, ...o })

const DATA = {
  generated_at: iso(-1),
  overall: { state: 'partial_outage', services_total: 6, by_state: { no_data: 0, operational: 3, maintenance: 1, degraded: 1, partial_outage: 1, major_outage: 0 },
    monitors_active: 14, monitors_down: 1, monitors_degraded: 1, monitors_maintenance: 2, active_incidents: 1, active_maintenance: 1, upcoming_maintenance: 1 },
  services: [
    svc({ key: '2|web', name: 'Kurumsal Web Sitesi', team_id: 2, team_name: 'Dijital Kanallar', state: 'operational', uptime_7d: 99.95 }),
    svc({ key: '3|ivr', name: 'IVR', team_id: 3, team_name: 'Çağrı Merkezi', state: 'operational' }),
    svc({ key: '5|ağ', name: 'Çekirdek Ağ', team_id: 5, team_name: 'Altyapı', state: 'maintenance', maintenance: 2, up: 0, maintenance_until: iso(45) }),
    svc({ key: '4|kart', name: 'Kart İşlemleri', team_id: 4, team_name: 'Bankacılık', state: 'degraded', degraded: 1, up: 1, since: iso(-30) }),
    svc({ key: '6|ödeme', name: 'Ödeme Geçidi ve Mobil Ödeme Platformu (Kurumsal + Bireysel)', team_id: 6, team_name: 'Ödeme Sistemleri',
      state: 'partial_outage', down: 1, up: 1, since: iso(-12), uptime_7d: 98.5, monitors_visible: true,
      monitors: [{ name: 'odeme-api health', type: 'http', status: 'down' }, { name: 'Ödeme ping', type: 'ping', status: 'up' }] }),
    svc({ key: '6|', name: null, ungrouped: true, team_id: 6, team_name: 'Ödeme Sistemleri', state: 'operational', monitors_visible: true,
      monitors: [{ name: 'eski-izleme', type: 'port', status: 'up' }] }),
  ],
  incidents: {
    active: [{ id: 11, title: 'Ödeme onaylarında gecikme', severity: 'HIGH', status: 'INVESTIGATING', started_at: iso(-50), services: ['Ödeme API', 'Mobil'], team_id: 6, team_name: 'Ödeme Sistemleri' }],
    active_total: 1, active_visible: 1, active_hidden: 0,
    resolved: [{ id: 9, title: 'DNS çözümleme hatası', severity: 'MEDIUM', started_at: iso(-3000), resolved_at: iso(-2910), duration_minutes: 90, services: [], team_id: 5, team_name: 'Altyapı' }],
    resolved_total: 1, resolved_visible: 1, resolved_hidden: 0, days: 7,
  },
  maintenance: {
    active: [{ id: 1, name: 'Çekirdek switch değişimi', state: 'active', starts_at: iso(-15), ends_at: iso(45), recurrence: 'NONE', all_monitors: false, monitor_count: 2,
      team_id: 5, team_name: 'Altyapı', services: [{ key: '5|ağ', name: 'Çekirdek Ağ', ungrouped: false, team_name: 'Altyapı' }], services_total: 1 }],
    active_total: 1, active_hidden: 0,
    upcoming: [{ id: 2, name: 'Gece DB yamaları', state: 'upcoming', starts_at: iso(600), ends_at: iso(720), recurrence: 'WEEKLY', all_monitors: true, monitor_count: null,
      team_id: null, team_name: null, services: [], services_total: 0 }],
    upcoming_total: 1, upcoming_hidden: 0, days: 7,
  },
  uptime: { available: true, days: 7, from: '2026-09-24', to: '2026-09-30', source: 'daily_rollup' },
}

const OK = { success: true, data: DATA }

describe('Durum Sayfası (2026-10-01)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/?tab=status')
    try { localStorage.clear() } catch { /* yok say */ }
    api.statusPage.get.mockResolvedValue(OK)
  })

  it('şerit: kurum durumu başlığı + ikon + özet; durumlara göre sayılar rozet metniyle (yalnız renk değil)', async () => {
    const { container } = render(<StatusPage />)
    await waitFor(() => expect(container.querySelector('[data-slot="sp-banner"]')).not.toBeNull())
    const banner = container.querySelector('[data-slot="sp-banner"]')
    expect(banner.getAttribute('data-state')).toBe('partial_outage')
    expect(banner.querySelector('[data-slot="sp-headline"]').textContent).toBe('Some services are experiencing an outage')
    expect(banner.textContent).toMatch(/Services: 6/)
    expect(banner.textContent).toMatch(/With issues: 2/)
    expect(banner.textContent).toMatch(/Open incidents: 1/)
    expect(banner.textContent).toMatch(/Maintenance in progress: 1/)
    const chips = [...banner.querySelectorAll('[data-slot="sp-by-state"] [data-slot="sp-state"]')]
    expect(chips.map((c) => c.getAttribute('data-state'))).toEqual(['partial_outage', 'degraded', 'maintenance', 'operational'])
    expect(chips[0].textContent).toBe('Partial outage · 1')
    for (const c of chips) expect(c.querySelector('svg')).not.toBeNull()   // ikon + metin
    expect(document.body.textContent).not.toContain('{0}')
    expect(api.statusPage.get).toHaveBeenCalledWith(false)
  })

  it('takım grupları: sorunlu gruplar önce (en kötüsü önce), sonra Türkçe A→Z; sorunlu gruplar açık, diğerleri kapalı', async () => {
    const { container } = render(<StatusPage />)
    await waitFor(() => expect(container.querySelectorAll('[data-slot="sp-team"]').length).toBe(5))
    const teams = [...container.querySelectorAll('[data-slot="sp-team"]')]
    expect(teams.map((g) => g.querySelector('[data-slot="sp-team-name"]').textContent))
      .toEqual(['Ödeme Sistemleri', 'Bankacılık', 'Altyapı', 'Çağrı Merkezi', 'Dijital Kanallar'])
    expect(teams.map((g) => g.getAttribute('data-worst'))).toEqual(['partial_outage', 'degraded', 'maintenance', 'operational', 'operational'])
    expect(teams.map((g) => g.getAttribute('data-state'))).toEqual(['open', 'open', 'closed', 'closed', 'closed'])
    // Açık grupta hizmet satırları: sorunlu önce, grupsuz "Other monitors" en sonda; rozet metni + data-state
    const pay = within(teams[0])
    const rows = [...teams[0].querySelectorAll('[data-slot="sp-service"]')]
    expect(rows.map((r) => r.getAttribute('data-state'))).toEqual(['partial_outage', 'operational'])
    expect(rows[1].querySelector('[data-slot="sp-service-name"]').textContent).toBe('Other monitors')
    expect(within(rows[0]).getByText('Partial outage')).toBeInTheDocument()
    expect(within(rows[0]).getByText('1 failing')).toBeInTheDocument()
    expect(rows[0].querySelector('[data-slot="sp-uptime"]').textContent).toBe('7 days: 98.50%')
    expect(pay.getByText(/Started/)).toBeInTheDocument()
    // Kapalı grubu açmak
    fireEvent.click(within(teams[3]).getByRole('button', { name: /Çağrı Merkezi/ }))
    await waitFor(() => expect(teams[3].getAttribute('data-state')).toBe('open'))
    expect(teams[3].querySelector('[data-slot="sp-service"][data-key="3|ivr"]')).not.toBeNull()
  })

  it('izleme listesi yalnız görülebilen takımın hizmetinde (ad + durum); başka takımın hizmetinde düğme yok', async () => {
    const { container } = render(<StatusPage />)
    await waitFor(() => expect(container.querySelector('[data-slot="sp-service"][data-key="6|ödeme"]')).not.toBeNull())
    const pay = container.querySelector('[data-slot="sp-service"][data-key="6|ödeme"]')
    const toggle = within(pay).getByRole('button', { name: /Monitors \(2\) — Ödeme Geçidi/ })
    expect(pay.querySelector('[data-slot="sp-monitors"]')).toBeNull()
    fireEvent.click(toggle)
    await waitFor(() => expect(pay.querySelector('[data-slot="sp-monitors"]')).not.toBeNull())
    const mons = [...pay.querySelectorAll('[data-slot="sp-monitor"]')]
    expect(mons.map((m) => m.getAttribute('data-status'))).toEqual(['down', 'up'])
    expect(mons[0].textContent).toMatch(/odeme-api health/)
    expect(mons[0].textContent).toMatch(/Failing/)
    // Bankacılık (açık grup) — monitors_visible=false: liste düğmesi yok
    const card = container.querySelector('[data-slot="sp-service"][data-key="4|kart"]')
    expect(within(card).queryByRole('button', { name: /Monitors/ })).toBeNull()
  })

  it('olaylar ve bakım: açık olay (önem rozeti metinli), son 7 günde çözülen (süre), süren + yaklaşan bakım; lejant 6 durum', async () => {
    const { container } = render(<StatusPage />)
    await waitFor(() => expect(container.querySelector('[data-slot="sp-incident"]')).not.toBeNull())
    const inc = container.querySelector('[data-slot="sp-incident"]')
    expect(inc.getAttribute('data-severity')).toBe('HIGH')
    expect(inc.textContent).toMatch(/Ödeme onaylarında gecikme/)
    expect(inc.textContent).toMatch(/High/)
    expect(inc.textContent).toMatch(/Investigating/)
    expect(inc.textContent).toMatch(/Ödeme API/)
    expect(container.querySelector('[data-slot="sp-resolved-item"]').textContent).toMatch(/Duration: 1 .*30/)
    const windows = [...container.querySelectorAll('[data-slot="sp-window"]')]
    expect(windows.map((w) => w.getAttribute('data-state'))).toEqual(['active', 'upcoming'])
    expect(windows[0].textContent).toMatch(/Çekirdek switch değişimi/)
    expect(windows[0].textContent).toMatch(/Çekirdek Ağ/)
    expect(windows[1].textContent).toMatch(/All monitors/)
    expect(windows[1].textContent).toMatch(/Weekly/)
    const legend = [...container.querySelectorAll('[data-slot="sp-legend-item"]')]
    expect(legend.map((l) => l.getAttribute('data-state'))).toEqual(['major_outage', 'partial_outage', 'degraded', 'maintenance', 'operational', 'no_data'])
    // Gizli kayıt yoksa "+N başka takım" satırı da yok
    expect(container.querySelector('[data-slot="sp-incidents-hidden"]')).toBeNull()
    expect(container.querySelector('[data-slot="sp-maint-active-hidden"]')).toBeNull()
    // Hiçbir yerde adres/hedef yok (sunucu göndermez; ekran da uydurmaz)
    expect(document.body.textContent).not.toMatch(/https?:\/\//)
  })

  it('başka takımın olay/bakım kayıtları yalnız SAYI: "+N" satırı, başlık yok; görünen satırlar ve "Açık olay yok" karışmaz', async () => {
    api.statusPage.get.mockResolvedValue({ success: true, data: { ...DATA,
      incidents: { active: [DATA.incidents.active[0]], active_total: 4, active_visible: 1, active_hidden: 3,
        resolved: [], resolved_total: 2, resolved_visible: 0, resolved_hidden: 2, days: 7 },
      maintenance: { active: [], active_total: 1, active_hidden: 1, upcoming: [DATA.maintenance.upcoming[0]], upcoming_total: 3, upcoming_hidden: 2, days: 7 } } })
    const { container } = render(<StatusPage />)
    await waitFor(() => expect(container.querySelector('[data-slot="sp-incidents-hidden"]')).not.toBeNull())
    const inc = container.querySelector('[data-slot="sp-incidents"]')
    expect(inc.querySelectorAll('[data-slot="sp-incident"]').length).toBe(1)
    expect(container.querySelector('[data-slot="sp-incidents-hidden"]').textContent).toBe('+3 incident(s) of other teams')
    expect(within(inc).queryByText('No open incidents')).toBeNull()
    expect(within(inc).getByText('4')).toBeInTheDocument()                     // başlık sayısı kurum geneli toplam
    // Çözülen: görünen yok ama 2 başka takım → "none" metni değil, gizli sayı satırı
    const resolved = container.querySelector('[data-slot="sp-resolved"]')
    expect(resolved.querySelector('[data-slot="sp-resolved-hidden"]').textContent).toBe('+2 incident(s) of other teams')
    expect(within(resolved).queryByText('No incidents were resolved in the last 7 days.')).toBeNull()
    // Bakım: süren bölüm yalnız gizli sayı; yaklaşan bölümde 1 satır + 2 gizli
    expect(container.querySelector('[data-slot="sp-maint-active-hidden"]').textContent).toBe('+1 maintenance window(s) of other teams')
    expect(container.querySelector('[data-slot="sp-maint-upcoming-hidden"]').textContent).toBe('+2 maintenance window(s) of other teams')
    expect([...container.querySelectorAll('[data-slot="sp-window"]')].map((w) => w.getAttribute('data-state'))).toEqual(['upcoming'])
    expect(document.body.textContent).not.toContain('{0}')
  })

  it('kapsamlı görüntüleyicinin kendi olayları kurum geneli listenin dışında kaldıysa "ilk N / görünen toplam" notu', async () => {
    api.statusPage.get.mockResolvedValue({ success: true, data: { ...DATA,
      incidents: { ...DATA.incidents, active_total: 70, active_visible: 60, active_hidden: 10 } } })
    const { container } = render(<StatusPage />)
    await waitFor(() => expect(container.querySelector('[data-slot="sp-incidents-hidden"]')).not.toBeNull())
    expect(container.querySelector('[data-slot="sp-incidents"]').textContent).toMatch(/Showing the first 1 of 60 incidents\./)
    expect(container.querySelector('[data-slot="sp-incidents-hidden"]').textContent).toBe('+10 incident(s) of other teams')
  })

  it('boş durumlar: hizmet yok, açık olay yok, bakım yok, çözülen yok — çökmez', async () => {
    api.statusPage.get.mockResolvedValue({ success: true, data: { ...DATA, services: [],
      overall: { state: 'no_data', services_total: 0, by_state: {}, active_incidents: 0, active_maintenance: 0 },
      incidents: { active: [], active_total: 0, resolved: [], resolved_total: 0, days: 7 },
      maintenance: { active: [], upcoming: [], active_total: 0, upcoming_total: 0, days: 7 } } })
    const { container } = render(<StatusPage />)
    await waitFor(() => expect(container.querySelector('[data-slot="sp-empty"]')).not.toBeNull())
    expect(container.querySelector('[data-slot="sp-banner"]').getAttribute('data-state')).toBe('no_data')
    expect(screen.getByText('No open incidents')).toBeInTheDocument()
    expect(screen.getByText('No maintenance in progress or scheduled for the next 7 days.')).toBeInTheDocument()
    expect(screen.getByText('No incidents were resolved in the last 7 days.')).toBeInTheDocument()
    expect(container.querySelector('[data-slot="sp-teams"]')).toBeNull()
    expect(container.querySelector('[data-slot="sp-by-state"]')).toBeNull()
  })

  it('hata: ilk yükleme başarısız → hata bloğu + Yeniden dene; yeniden deneme taze istekle yükler', async () => {
    api.statusPage.get.mockResolvedValueOnce({ success: false, error: 'Sunucu hatası' })
    const { container } = render(<StatusPage />)
    await waitFor(() => expect(container.querySelector('[data-slot="sp-error"]')).not.toBeNull())
    expect(container.querySelector('[data-slot="sp-error"]').textContent).toMatch(/Sunucu hatası/)
    fireEvent.click(within(container.querySelector('[data-slot="sp-error"]')).getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(container.querySelector('[data-slot="sp-banner"]')).not.toBeNull())
    expect(api.statusPage.get).toHaveBeenLastCalledWith(true)
    expect(container.querySelector('[data-slot="sp-error"]')).toBeNull()
  })

  it('yükleniyor iskeleti; ağ hatası (reddedilen istek) da hata bloğuna düşer', async () => {
    let reject
    api.statusPage.get.mockReturnValueOnce(new Promise((_, r) => { reject = r }))
    const { container } = render(<StatusPage />)
    expect(container.querySelector('[data-slot="sp-skeleton"]')).not.toBeNull()
    await act(async () => { reject(new Error('Failed to fetch')) })
    await waitFor(() => expect(container.querySelector('[data-slot="sp-error"]')).not.toBeNull())
    expect(container.querySelector('[data-slot="sp-skeleton"]')).toBeNull()
  })

  it('yoklama görünürlük-farkında: useVisibleInterval(fn, 60 sn, false); tik bellekten okur, başarısız tik son veriyi korur + uyarı', async () => {
    const { container } = render(<StatusPage />)
    await waitFor(() => expect(container.querySelector('[data-slot="sp-banner"]')).not.toBeNull())
    expect(STATUS_POLL_MS).toBe(60_000)
    expect(useVisibleInterval).toHaveBeenCalledWith(expect.any(Function), 60_000, false)
    const tick = useVisibleInterval.mock.calls.at(-1)[0]
    api.statusPage.get.mockResolvedValueOnce({ success: false, error: 'Zaman aşımı' })
    await act(async () => { await tick() })
    expect(api.statusPage.get).toHaveBeenLastCalledWith(false)
    await waitFor(() => expect(container.querySelector('[data-slot="sp-stale-data"]')).not.toBeNull())
    expect(container.querySelector('[data-slot="sp-stale-data"]').textContent).toMatch(/Zaman aşımı/)
    expect(container.querySelector('[data-slot="sp-banner"]')).not.toBeNull()   // son veri korunur
    // Yenile düğmesi taze istek atar ve uyarıyı kaldırır
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    await waitFor(() => expect(container.querySelector('[data-slot="sp-stale-data"]')).toBeNull())
    expect(api.statusPage.get).toHaveBeenLastCalledWith(true)
  })
})
