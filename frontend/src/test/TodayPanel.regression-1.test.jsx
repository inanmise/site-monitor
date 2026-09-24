import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  api: withApiFallback({ me: { today: vi.fn() }, admin: { getTeams: vi.fn().mockResolvedValue({ success: true, data: [] }) } }),
}))
import { api } from '../api/client'
import TodayPanel from '../components/TodayPanel.jsx'

// Regression: ISSUE-003 — EN "Son 24 saat" şeridi "Last 24 hours: alerts opened: 3" diye iki iki nokta üst üste
// ile okunuyordu (etiketin kendi ":"i + sayaç metnindeki ":"). Artık sayı önde, tekil/çoğul ayrı anahtar.
// Found by /qa on 2026-09-24
// Report: .gstack/qa-reports/qa-report-localhost-5173-2026-09-24.md
const EMPTY = { certs: { count: 0, items: [] }, alerts: { count: 0, items: [] }, weekly: { count: 0, missing: 0, items: [] } }

describe('TodayPanel son 24 saat şeridi — doğal metin (ISSUE-003)', () => {
  afterEach(() => { localStorage.setItem('site-monitor-lang', 'en'); try { localStorage.removeItem('today-panel-open') } catch { /* yok */ } })

  const strip = async (recent, lang) => {
    localStorage.setItem('site-monitor-lang', lang)
    localStorage.setItem('today-panel-open', 'true')
    api.me.today.mockResolvedValue({ success: true, data: { ...EMPTY, recent: { hours: 24, ...recent } } })
    render(<TodayPanel />)
    await screen.findByText(/Nothing needs attention today|Bugün ilgilenilecek bir şey yok/)
    return document.querySelector('.today-recent').textContent
  }

  it('EN: tek iki nokta üst üste, sayı önde; 1 için tekil', async () => {
    const txt = await strip({ opened: 3, resolved: 1, renewed: 2 }, 'en')
    expect(txt).toBe('Last 24 hours:3 alerts opened · 1 alert resolved · 2 certificates renewed')
    expect(txt.match(/:/g)).toHaveLength(1)
  })

  it('EN: tümü tekil', async () => {
    expect(await strip({ opened: 1, resolved: 1, renewed: 1 }, 'en'))
      .toBe('Last 24 hours:1 alert opened · 1 alert resolved · 1 certificate renewed')
  })

  it('TR: doğal kalır (1 ve çok)', async () => {
    expect(await strip({ opened: 1, resolved: 4, renewed: 0 }, 'tr')).toBe('Son 24 saatte:1 alarm açıldı · 4 alarm çözüldü')
  })
})
