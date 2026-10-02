import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, act, waitFor } from './test-utils.jsx'

/**
 * İzleme türü sayfalarının yenileme geri sayımı (2026-10-01, onaylı öneri 21): dokuz sayfa geri sayım için
 * `secondsSince` state'ini saniyede bir artırıyor ve TÜM sayfayı (kart ızgarası, süzgeçler, açık detay penceresi)
 * yeniden çiziyordu. Sayaç artık başlığın çipinde (MonitorPageHeader `refreshEvery` + `refreshResetKey`); sayfa
 * yalnız veri gelince çizilir. Davranış aynı: çip saniyede bir azalır.
 *
 * Ölçüm: sayfa gövdesinin her çiziminde çağrılan `usePagination` kancasının çağrı sayısı.
 */
const calls = vi.hoisted(() => ({ n: 0 }))
vi.mock('../hooks/usePagination.js', async (importOriginal) => {
  const m = await importOriginal()
  return { ...m, usePagination: (...args) => { calls.n++; return m.usePagination(...args) } }
})
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({ monitoring: {} }),
}))
import HttpMonitorPage from '../components/HttpMonitorPage.jsx'
import PingMonitorPage from '../components/PingMonitorPage.jsx'
import PortMonitorPage from '../components/PortMonitorPage.jsx'
import KeywordMonitorPage from '../components/KeywordMonitorPage.jsx'

const chipSeconds = () => {
  const el = document.querySelector('[data-slot="monitor-refresh"]')
  const m = el && /(\d+)/.exec(el.textContent)
  return m ? Number(m[1]) : null
}

describe('İzleme sayfaları — geri sayım yalnız başlık çipini çizer', () => {
  beforeEach(() => {
    calls.n = 0
    window.history.replaceState(null, '', '/')
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => false })
  })
  afterEach(() => { delete document.hidden })

  for (const [name, Page] of [['HTTP', HttpMonitorPage], ['Ping', PingMonitorPage], ['Port', PortMonitorPage], ['Keyword', KeywordMonitorPage]]) {
    it(`${name}: çip saniyede bir azalır, sayfa gövdesi yeniden çizilmez`, async () => {
      render(<Page systemRole="ADMIN" teamId={5} teamName="Takım A" />)
      await waitFor(() => expect(chipSeconds()).not.toBeNull())
      await act(async () => { await new Promise((r) => setTimeout(r, 300)) })   // ilk yükleme otursun
      const start = chipSeconds()
      const before = calls.n
      await act(async () => { await new Promise((r) => setTimeout(r, 2300)) })
      expect(chipSeconds()).toBeLessThanOrEqual(start - 2)   // sayaç işliyor
      expect(calls.n).toBe(before)                           // sayfa gövdesi çizilmedi
      expect(screen.getByRole('button', { name: /refresh|yenile/i })).toBeInTheDocument()
    }, 15_000)
  }
})
