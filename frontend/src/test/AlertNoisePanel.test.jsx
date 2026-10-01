import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', async () => {
  const actual = await vi.importActual('../api/client')
  return { ...actual, api: withApiFallback({ admin: { getAlertNoise: vi.fn(), getAlertNoiseSlot: vi.fn() } }) }
})
// Telefon kipi (jsdom medya sorgusu görmez → kanca mock'lanır)
const mobile = vi.hoisted(() => ({ on: false }))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.on }))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import AlertNoisePanel from '../components/admin/AlertNoisePanel.jsx'
import { buildTeamOptions, groupSuggestions, runSuggestionAction, scoreBand } from '../components/admin/alerts/noiseModel.js'

/**
 * Gürültü analizi (2026-09-12, #18): kapalı başlar, açınca yüklenir; top tablo, ısı haritası, flap önerisi; gün seçimi yeniden yükler.
 * Yeniden tasarım (2026-10-01): KPI şeridi, takım seçici → api parametresi, öneri kartları → `sm:navigate`, telefon düzeni, boş/hata durumu.
 */
describe('AlertNoisePanel', () => {
  // Panel tercihi 2026-09-17'den beri OTURUMLUK (sessionStorage): temizlenmezse bir önceki testte
  // açık bırakılan panel sonraki testte açık doğar ve başlığa tıklamak onu KAPATIR.
  // Dil TR'ye sabitlenir (varsayılan EN): metin iddiaları deterministik; eski iddialar TR|EN alternatifli kalır.
  beforeEach(() => { vi.clearAllMocks(); mobile.on = false; try { localStorage.clear(); sessionStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ } })
  const rows = Array.from({ length: 7 }, () => Array(24).fill(0)); rows[1][14] = 5
  const hours = Array.from({ length: 24 }, (_, h) => ({ hour: h, count: h === 14 ? 5 : 0, night: h >= 22 || h < 6 }))
  const DATA = { days: 7, total: 12, critical: 3, distinct_targets: 2, still_open: 1, resolved_pct: 91.7, resolved_total: 11, mttr_minutes: 12.4,
    off_hours: 2, off_hours_pct: 16.7, per_day_avg: 1.7, silenced_total: 0, noisy_targets: 1, flap_targets: 1, flap_alerts: 9, noise_score: 75, night_pct: 0,
    top: [
      { domain: 'flap.example.com', type: 'HTTP_DOWN', count: 9, resolved: 9, avg_minutes: 3.5, share_pct: 75, monitor_type: 'http', team_id: 1, team_name: 'Ödeme', median_minutes: 3.0, flap_count: 9, still_open: 1, last_opened_at: '2026-09-30T10:00:00', pattern: 'FLAPPING' },
      { domain: 'slow.example.com', type: 'PING_DOWN', count: 3, resolved: 2, avg_minutes: 42, share_pct: 25, monitor_type: 'ping', team_id: 2, team_name: 'Kart', median_minutes: 40, flap_count: 0, still_open: 0, last_opened_at: '2026-09-29T10:00:00', pattern: 'NORMAL' },
    ],
    flapping: [{ domain: 'flap.example.com', type: 'HTTP_DOWN', count: 9, avg_minutes: 3.5, suggestion: 'raise-confirm' }],
    heat: { rows, peak: 5, peak_day: 1, peak_hour: 14, by_hour: [], by_day: [] },
    hours,
    by_type: [{ type: 'HTTP_DOWN', count: 9, critical: 3, share_pct: 75, monitor_type: 'http' }, { type: 'PING_DOWN', count: 3, critical: 0, share_pct: 25, monitor_type: 'ping' }],
    teams: [
      { team_id: 1, team_name: 'Ödeme', alerts: 9, critical: 3, still_open: 1, noisy_targets: 1, flaps: 9, silent_closes: 0, storms: 2, noise_score: 100 },
      { team_id: 2, team_name: 'Kart', alerts: 3, critical: 0, still_open: 0, noisy_targets: 0, flaps: 0, silent_closes: 0, storms: 0, noise_score: 0 },
    ],
    team_options: [{ id: 1, name: 'Ödeme' }, { id: 2, name: 'Kart' }, { id: 3, name: 'Altyapı' }],
    my_team_ids: [2],
    suggestions: [
      { code: 'FLAPPING', severity: 'HIGH', title_key: 'noise.sug.FLAPPING', target: 'flap.example.com', type: 'HTTP_DOWN', monitor_type: 'http', team_id: 1, team_name: 'Ödeme', count: 9, params: [9, 3.5], action: { kind: 'open_monitor', tab: 'http', params: { q: 'flap.example.com' } } },
      { code: 'STORM_PRONE', severity: 'MEDIUM', title_key: 'noise.sug.STORM_PRONE', team_id: 1, team_name: 'Ödeme', count: 2, params: [2, 7], action: { kind: 'open_settings', tab: 'settings', params: { sec: 'storm' } } },
      { code: 'DUPLICATE_MONITORS', severity: 'MEDIUM', title_key: 'noise.sug.DUPLICATE_MONITORS', target: 'dup.example.com', params: [3, 'http, port, ping'], action: { kind: 'open_alerts', tab: 'alerthistory', params: { q: 'dup.example.com' } } },
    ] }
  const openPanel = () => fireEvent.click(screen.getByRole('button', { name: /Gürültü analizi|Noise analysis/ }))

  it('kapalı başlar (istek yok); açınca 7 gün yüklenir; hedef tıklanınca onPickDomain; 30 gün seçince yeniden ister', async () => {
    api.admin.getAlertNoise.mockResolvedValue({ success: true, data: DATA })
    const onPick = vi.fn()
    render(<AlertNoisePanel onPickDomain={onPick} />)
    expect(api.admin.getAlertNoise).not.toHaveBeenCalled()
    openPanel()
    await waitFor(() => expect(api.admin.getAlertNoise).toHaveBeenCalledWith(7))
    await screen.findByText(/12 alarm · 2 hedef · 1 flap adayı|12 alerts · 2 targets · 1 flapping/)
    expect(screen.getByText(/zirve: Sal 14:00 · 5 alarm|peak: Tue 14:00 · 5 alerts/)).toBeInTheDocument()
    expect(screen.getByText(/9 alarm, ortalama 3\.5 dk|9 alerts, open for 3\.5 min/)).toBeInTheDocument()
    expect(document.querySelectorAll('.noise-heat-cell').length).toBe(7 * 24)
    fireEvent.click(screen.getAllByRole('button', { name: 'flap.example.com' })[0])
    expect(onPick).toHaveBeenCalledWith('flap.example.com')
    fireEvent.click(screen.getByRole('button', { name: /Son 30 gün|Last 30 days/ }))
    await waitFor(() => expect(api.admin.getAlertNoise).toHaveBeenCalledWith(30))
  })

  it('alarm yok → boş mesaj', async () => {
    api.admin.getAlertNoise.mockResolvedValue({ success: true, data: { days: 7, total: 0, distinct_targets: 0, top: [], flapping: [], heat: { rows, peak: 0 } } })
    render(<AlertNoisePanel />)
    openPanel()
    expect(await screen.findByText(/Son 7 günde alarm yok|No alerts in the last 7 days/)).toBeInTheDocument()
    expect(document.querySelector('[data-slot="empty"][data-tone="success"]')).toBeTruthy()
  })

  it('KPI şeridi: eski beş kart + gürültülü hedef / flap / sessiz kapanış / skor; 14 gün seçeneği var; kaynak satırında desen rozeti + takım + Monitörü aç', async () => {
    api.admin.getAlertNoise.mockResolvedValue({ success: true, data: DATA })
    render(<AlertNoisePanel onPickDomain={vi.fn()} />)
    openPanel()
    await screen.findByText(/12 alarm · 2 hedef/)
    const keys = [...document.querySelectorAll('[data-slot="stat-item"]')].map((el) => el.getAttribute('data-key'))
    expect(keys).toEqual(['total', 'critical', 'noisy', 'flap', 'silent', 'resolved', 'mttr', 'offhours', 'score'])
    const score = document.querySelector('[data-slot="stat-item"][data-key="score"]')
    expect(score).toHaveAttribute('data-tone', 'critical')   // 75 → yüksek bant
    expect(within(score).getByText('75')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Son 14 gün|Last 14 days/ })).toBeInTheDocument()
    // Kaynak satırı
    const sources = document.querySelectorAll('[data-slot="noise-source"]')
    expect(sources.length).toBe(2)
    expect(sources[0]).toHaveAttribute('data-pattern', 'FLAPPING')
    expect(within(sources[0]).getByText(/Titreme|Flapping/)).toBeInTheDocument()
    expect(within(sources[0]).getByText('Ödeme')).toBeInTheDocument()
    expect(within(sources[0]).getByText(/1 açık|1 open/)).toBeInTheDocument()
    // "Monitörü aç" → sm:navigate(http, {q})
    const seen = []
    const onNav = (e) => seen.push(e.detail)
    window.addEventListener('sm:navigate', onNav)
    fireEvent.click(within(sources[0]).getByRole('button', { name: /Monitörü aç|Open monitor/ }))
    window.removeEventListener('sm:navigate', onNav)
    expect(seen).toEqual([{ tab: 'http', params: { q: 'flap.example.com' } }])
    // Takım tablosu (masaüstü) — fırtına sütunu ve skor rozeti
    const teamRows = document.querySelectorAll('[data-slot="noise-team-row"]')
    expect(teamRows.length).toBe(2)
    expect(within(teamRows[0]).getByText('2')).toBeInTheDocument()   // storms
    expect(within(teamRows[0]).getByText(/100 · yüksek|100 · high/)).toBeInTheDocument()
    expect(document.querySelector('[data-slot="noise-team-card"]')).toBeNull()
    // Saat grafiği kabı
    expect(document.querySelector('[data-slot="chart"]')).toBeTruthy()
  })

  it('takım seçici: "Takımlarım" önde; seçim api parametresine gider (days, teamId); tablo satırından takıma süzme', async () => {
    api.admin.getAlertNoise.mockResolvedValue({ success: true, data: DATA })
    render(<AlertNoisePanel onPickDomain={vi.fn()} />)
    openPanel()
    await screen.findByText(/12 alarm · 2 hedef/)
    const trigger = screen.getByRole('combobox', { name: /^Takım$|^Team$/ })
    fireEvent.mouseDown(trigger)
    const options = await screen.findAllByRole('option')
    // Tüm takımlar, Takımlarım: Kart, Diğer: Ödeme, Altyapı
    expect(options.map((o) => o.textContent.trim())).toEqual(['Tüm takımlar', 'Kart', 'Ödeme', 'Altyapı'])
    fireEvent.mouseDown(options.find((o) => o.textContent.trim() === 'Kart'))
    await waitFor(() => expect(api.admin.getAlertNoise).toHaveBeenLastCalledWith(7, 2))
    // Tablo satırındaki takım adı → süzgeç
    const row = document.querySelector('[data-slot="noise-team-row"][data-team-id="1"]')
    fireEvent.click(within(row).getByRole('button', { name: /Ödeme/ }))
    await waitFor(() => expect(api.admin.getAlertNoise).toHaveBeenLastCalledWith(7, 1))
  })

  it('çözüm önerileri: önem gruplu; eylem düğmeleri sunucu ipucuna göre sm:navigate (settings/storm) ya da onPickDomain (open_alerts)', async () => {
    api.admin.getAlertNoise.mockResolvedValue({ success: true, data: DATA })
    const onPick = vi.fn()
    render(<AlertNoisePanel onPickDomain={onPick} />)
    openPanel()
    await screen.findByText(/12 alarm · 2 hedef/)
    const groups = [...document.querySelectorAll('[data-slot="noise-suggestion-group"]')].map((g) => g.getAttribute('data-severity'))
    expect(groups).toEqual(['HIGH', 'MEDIUM'])
    const cards = document.querySelectorAll('[data-slot="noise-suggestion"]')
    expect([...cards].map((c) => c.getAttribute('data-code'))).toEqual(['FLAPPING', 'STORM_PRONE', 'DUPLICATE_MONITORS'])
    expect(within(cards[0]).getByText(/Titreme: onay sayısını artırın|Flapping: raise the confirmation count/)).toBeInTheDocument()
    expect(within(cards[0]).getByText(/9 alarm, ortalaması 3\.5 dk|9 alerts that stayed open for 3\.5 min/)).toBeInTheDocument()
    expect(within(cards[1]).getByText(/Son 7 günde 2 alarm fırtınası|2 alert storms opened in the last 7 days/)).toBeInTheDocument()
    expect(within(cards[2]).getByText(/3 farklı kaynak \(http, port, ping\)|3 sources \(http, port, ping\)/)).toBeInTheDocument()
    const seen = []
    const onNav = (e) => seen.push(e.detail)
    window.addEventListener('sm:navigate', onNav)
    fireEvent.click(within(cards[0]).getByRole('button', { name: /Monitörü aç|Open monitor/ }))
    fireEvent.click(within(cards[1]).getByRole('button', { name: /Ayarları aç|Open settings/ }))
    fireEvent.click(within(cards[2]).getByRole('button', { name: /Alarmları listele|List alerts/ }))
    window.removeEventListener('sm:navigate', onNav)
    expect(seen).toEqual([
      { tab: 'http', params: { q: 'flap.example.com' } },
      { tab: 'settings', params: { sec: 'storm' } },
    ])
    expect(onPick).toHaveBeenCalledWith('dup.example.com')   // aynı sayfada: liste süzülür, sekme geçişi yok
  })

  it('telefon: takım kırılımı kart listesi (tablo yok); "Takıma süz" düğmesi süzgeci uygular', async () => {
    mobile.on = true
    api.admin.getAlertNoise.mockResolvedValue({ success: true, data: DATA })
    render(<AlertNoisePanel onPickDomain={vi.fn()} />)
    openPanel()
    await screen.findByText(/12 alarm · 2 hedef/)
    const cards = document.querySelectorAll('[data-slot="noise-team-card"]')
    expect(cards.length).toBe(2)
    expect(document.querySelector('[data-slot="noise-team-row"]')).toBeNull()
    fireEvent.click(within(cards[1]).getByRole('button', { name: /Takıma süz|Filter to team/ }))
    await waitFor(() => expect(api.admin.getAlertNoise).toHaveBeenLastCalledWith(7, 2))
  })

  it('yükleme hatası → hata bloğu + Yenile yeniden ister', async () => {
    api.admin.getAlertNoise.mockRejectedValueOnce(new Error('boom')).mockResolvedValue({ success: true, data: DATA })
    render(<AlertNoisePanel />)
    openPanel()
    const retry = await screen.findByRole('button', { name: /^Yenile$|^Refresh$/ })
    expect(document.querySelector('[data-slot="empty"][data-tone="danger"]')).toBeTruthy()
    fireEvent.click(retry)
    await screen.findByText(/12 alarm · 2 hedef/)
    expect(api.admin.getAlertNoise).toHaveBeenCalledTimes(2)
  })

  it('Alerts per day: tarihsiz bozuk kayıt düşürülür, panel çökmez', async () => {
    api.admin.getAlertNoise.mockResolvedValue({ success: true, data: { ...DATA, series: [{ day: '2026-09-25', count: 3 }, null, { date: '2026-09-26', count: 2 }, { date: '2026-09-27', count: 1 }] } })
    render(<AlertNoisePanel onPickDay={() => {}} />)
    openPanel()
    await waitFor(() => expect(document.querySelectorAll('[data-slot="noise-day"]').length).toBe(2))
  })

  it('Alerts per day: alarmı olan gün düğme → onPickDay(gün); alarmsız gün tıklanamaz (2026-10-01)', async () => {
    const series = [{ date: '2026-09-24', count: 0 }, { date: '2026-09-25', count: 4 }, { date: '2026-09-26', count: 8 }]
    api.admin.getAlertNoise.mockResolvedValue({ success: true, data: { ...DATA, series } })
    const onPickDay = vi.fn()
    render(<AlertNoisePanel onPickDay={onPickDay} />)
    openPanel()
    const day = await screen.findByRole('button', { name: /2026-09-26 — 8 alarm/ })
    fireEvent.click(day)
    expect(onPickDay).toHaveBeenCalledWith('2026-09-26')
    expect(document.querySelectorAll('[data-slot="noise-day"]').length).toBe(2)   // 0 alarmlı gün düğme değil
    expect(screen.queryByRole('button', { name: /2026-09-24/ })).toBeNull()
  })

  it('Day × hour: dolu hücre düğme → dilim paneli sunucudan listeler; satır onOpenAlert; ok ile komşu saate geçer (2026-10-01)', async () => {
    api.admin.getAlertNoise.mockResolvedValue({ success: true, data: DATA })
    api.admin.getAlertNoiseSlot.mockResolvedValue({ success: true, data: { days: 7, dow: 1, hour: 14, total: 5, truncated: false,
      items: [{ id: 91, domain: 'flap.example.com', alert_type: 'HTTP_DOWN', alert_level: 'CRITICAL', created_at: '2026-09-29T11:10:00', resolved: true, team_id: 1, team_name: 'Ödeme' }] } })
    const onOpenAlert = vi.fn()
    render(<AlertNoisePanel onOpenAlert={onOpenAlert} />)
    openPanel()
    await waitFor(() => expect(document.querySelectorAll('.noise-heat-cell').length).toBe(7 * 24))
    const cells = document.querySelectorAll('[data-slot="noise-heat-cell"]')
    expect(cells.length).toBe(1)   // yalnız dolu hücre tıklanabilir
    fireEvent.click(cells[0])
    await waitFor(() => expect(api.admin.getAlertNoiseSlot).toHaveBeenCalledWith(7, 1, 14, null))
    const sheet = await screen.findByRole('dialog')
    await within(sheet).findByText('5 alarm')
    fireEvent.click(within(sheet).getByRole('button', { name: /flap\.example\.com — alarm ayrıntısını aç/ }))
    expect(onOpenAlert).toHaveBeenCalledWith(expect.objectContaining({ id: 91 }))
    // yeniden aç, sonraki saate geç
    fireEvent.click(cells[0])
    const sheet2 = await screen.findByRole('dialog')
    fireEvent.click(within(sheet2).getByRole('button', { name: /sonraki saat/i }))
    await waitFor(() => expect(api.admin.getAlertNoiseSlot).toHaveBeenLastCalledWith(7, 1, 15, null))
  })

})

describe('noiseModel', () => {
  const t = (k) => ({ 'noise.teamAll': 'Tüm takımlar', 'noise.teamMine': 'Takımlarım', 'noise.teamOthers': 'Diğer takımlar' })[k] ?? k
  afterEach(() => vi.restoreAllMocks())

  it('scoreBand: ≥60 high, ≥30 mid, altı low; geçersiz → low', () => {
    expect(scoreBand(60)).toBe('high'); expect(scoreBand(59)).toBe('mid'); expect(scoreBand(30)).toBe('mid'); expect(scoreBand(29)).toBe('low'); expect(scoreBand(undefined)).toBe('low')
  })

  it('buildTeamOptions: tek grup varsa başlık yok; my_team_ids yoksa hepsi düz', () => {
    expect(buildTeamOptions({ team_options: [{ id: 1, name: 'A' }], my_team_ids: [1] }, t)).toEqual([{ value: 'all', label: 'Tüm takımlar' }, { value: '1', label: 'A', group: undefined, groupOpen: true }])
    expect(buildTeamOptions({ team_options: [{ id: 1, name: 'A' }, { id: 2, name: 'B' }] }, t).map((o) => o.group)).toEqual([undefined, undefined, undefined])
    expect(buildTeamOptions(null, t)).toEqual([{ value: 'all', label: 'Tüm takımlar' }])
  })

  it('groupSuggestions: HIGH → MEDIUM → INFO, boş grup atlanır; runSuggestionAction bilinmeyen tür → alerthistory', () => {
    expect(groupSuggestions([{ severity: 'INFO' }, { severity: 'HIGH' }]).map((g) => g.severity)).toEqual(['HIGH', 'INFO'])
    expect(groupSuggestions(undefined)).toEqual([])
    const seen = []
    const onNav = (e) => seen.push(e.detail)
    window.addEventListener('sm:navigate', onNav)
    expect(runSuggestionAction({ target: 'x.example.com', action: { kind: 'weird' } })).toEqual({ tab: 'alerthistory', params: { q: 'x.example.com' } })
    expect(runSuggestionAction({ target: 'x.example.com', action: { kind: 'open_maintenance', tab: 'maintenance', params: { q: 'x.example.com' } } })).toEqual({ tab: 'maintenance', params: { q: 'x.example.com' } })
    expect(runSuggestionAction({ action: { kind: 'open_settings', params: { sec: 'userpush' } } })).toEqual({ tab: 'settings', params: { sec: 'userpush' } })
    window.removeEventListener('sm:navigate', onNav)
    expect(seen.map((d) => d.tab)).toEqual(['alerthistory', 'maintenance', 'settings'])
  })
})
