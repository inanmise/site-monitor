import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from './test-utils.jsx'
import { restoreRealTimers } from './helpers/fakeTimers.js'

/**
 * Performans (2026-11-17): İstatistikler arama kutusu. Yazılan taslak eskiden sayfa durumundaydı — her tuş vuruşu bütün
 * grafik ve bölümleri (genel bakış, 12 haftalık çubuk, güvenlik, kompozisyon …) yeniden çiziyordu. Artık taslak süzgeç
 * çubuğunda; sayfa yalnız gecikme sonunda işlenen değerle bir kez çizilir. Sonuçlar (tablo) gecikmeden sonra yine güncellenir.
 */
const renders = vi.hoisted(() => ({ overview: 0 }))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => false }))
vi.mock('../api/client', async (orig) => {
  const real = await orig()
  return { ...real, api: withApiFallback({ getExecutiveStats: vi.fn() }) }
})
// Genel bakış kartı (grafikler) — çizim sayacı
vi.mock('../components/stats/StatsOverview.jsx', async () => {
  const React = await import('react')
  return { default: () => { renders.overview += 1; return React.createElement('div', { 'data-slot': 'test-overview' }) } }
})
import StatsView from '../components/StatsView.jsx'
import { SEARCH_DEBOUNCE_MS } from '../components/stats/StatsToolbar.jsx'

const DAY = 86_400_000
const iso = (ms) => new Date(Date.now() + ms).toISOString().slice(0, 19)
const cert = (domain, days) => ({
  domain, days_remaining: days, not_after: iso(days * DAY), checked_at: iso(-60_000), status: 'valid', warning: false,
  alert_level: 'valid', issuer_cn: 'R3', tier: 1, team_id: 1, team_name: 'Takım A',
})
const CERTS = [cert('alpha.example.com', 120), cert('beta.example.com', 90), cert('gamma.example.com', 60)]
const rows = () => [...document.querySelectorAll('[data-slot="stats-row"]')].map((r) => r.getAttribute('data-domain'))
const search = () => screen.getByRole('searchbox')

beforeEach(() => {
  renders.overview = 0
  window.history.replaceState(null, '', '/?tab=stats')
})
afterEach(() => { restoreRealTimers(); window.history.replaceState(null, '', '/') })

describe('İstatistikler — arama taslağı sayfayı yeniden çizmez', () => {
  it('yazarken grafikler yeniden çizilmez; gecikme sonunda tek kez süzülür', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    render(<StatsView certs={CERTS} teamStats={null} onRowClick={vi.fn()} />)
    expect(rows().sort()).toEqual(['alpha.example.com', 'beta.example.com', 'gamma.example.com'])
    // Sayfanın kendi kaynakları (yönetici özeti, eğilimler …) yerleşsin — sayım yalnız arama sırasındaki çizimleri ölçer
    await act(async () => { await vi.advanceTimersByTimeAsync(1000) })
    const before = renders.overview
    // "beta" — beş tuş vuruşu, aralarında gecikmeden kısa bekleme
    let text = ''
    for (const ch of 'beta.') {
      text += ch
      fireEvent.change(search(), { target: { value: text } })
      await act(async () => { await vi.advanceTimersByTimeAsync(40) })
    }
    expect(search()).toHaveValue('beta.')
    expect(renders.overview).toBe(before)                 // eskiden her tuşta +1 (5)
    expect(rows().sort()).toEqual(['alpha.example.com', 'beta.example.com', 'gamma.example.com'])
    await act(async () => { await vi.advanceTimersByTimeAsync(SEARCH_DEBOUNCE_MS + 20) })
    expect(rows()).toEqual(['beta.example.com'])
    expect(renders.overview - before).toBeLessThanOrEqual(1)
    // Adres eşitlemesi kendi gecikmesiyle (sahte saatte waitFor kullanılmaz — zamanlayıcısı sahte)
    await act(async () => { await vi.advanceTimersByTimeAsync(1000) })
    expect(new URLSearchParams(window.location.search).get('st_q')).toBe('beta.')
  })

  it('Esc ve "aramayı temizle" hemen işler; dışarıdan temizleme (çip) taslağı da siler', async () => {
    render(<StatsView certs={CERTS} teamStats={null} onRowClick={vi.fn()} />)
    fireEvent.change(search(), { target: { value: 'gamma' } })
    await waitFor(() => expect(rows()).toEqual(['gamma.example.com']))
    fireEvent.keyDown(search(), { key: 'Escape' })
    expect(search()).toHaveValue('')
    expect(rows()).toHaveLength(3)
    fireEvent.change(search(), { target: { value: 'alpha' } })
    await waitFor(() => expect(rows()).toEqual(['alpha.example.com']))
    // Arama çipi kaldırılınca (sayfa temizler) kutu da boşalır
    fireEvent.click(document.querySelector('[data-slot="stats-chip"][data-chip="q"]'))
    expect(search()).toHaveValue('')
    expect(rows()).toHaveLength(3)
  })
})
