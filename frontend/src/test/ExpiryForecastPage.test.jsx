import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from './test-utils.jsx'
import userEvent from '@testing-library/user-event'
import ExpiryForecastPage from '../pages/ExpiryForecastPage.jsx'

/**
 * Vade Takvimi — 578 satır, 2026-08-19'a kadar HİÇ test dosyası yoktu (kapsam %25, tamamı
 * lazy-tabs-smoke'un salt mount'undan geliyordu).
 *
 * Sayfa üç ucu paralel çeker (`Promise.allSettled`) ve KISMİ başarısızlığa dayanacak şekilde
 * yazılmış; bu dayanıklılık hiç test edilmemişti. Ayrıca grafik aralığı değiştirilebiliyor
 * ve takvimde bir güne tıklayınca o günün sertifikaları açılıyor — ikisi de ayrı dallar.
 */

const { apiMock } = vi.hoisted(() => {
  const target = {
    getCertificates: vi.fn(),
    getStats:        vi.fn(),
    getTeamStats:    vi.fn(),
  }
  return {
    apiMock: new Proxy(target, {
      get(t, prop) {
        if (prop in t || typeof prop === 'symbol') return t[prop]
        t[prop] = vi.fn(() => Promise.resolve({ success: true, data: [] }))
        return t[prop]
      },
    }),
  }
})

vi.mock('../api/client', () => ({
  api: apiMock,
  formatDate:     (s) => s ?? '',
  formatDateSec:  (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
}))

import { api } from '../api/client'

/** Bugünden N gün sonrası — yyyy-MM-dd. */
function inDays(n) {
  const d = new Date()
  d.setDate(d.getDate() + n)
  return d.toISOString().slice(0, 10)
}

const CERTS = [
  { domain: 'crit.example.com', tier: 1, days_remaining: 3,  not_after: `${inDays(3)}T00:00:00`,  alert_level: 'critical', team_name: 'Ödeme' },
  { domain: 'high.example.com', tier: 1, days_remaining: 10, not_after: `${inDays(10)}T00:00:00`, alert_level: 'high',     team_name: 'Ödeme' },
  { domain: 'warn.example.com', tier: 2, days_remaining: 25, not_after: `${inDays(25)}T00:00:00`, alert_level: 'warning',  team_name: 'Altyapı' },
  { domain: 'ok.example.com',   tier: 3, days_remaining: 80, not_after: `${inDays(80)}T00:00:00`, alert_level: 'valid',    team_name: 'Altyapı' },
]

const STATS = { total: 4, valid: 1, warning: 1, critical: 1, expired: 0 }
const TEAM_STATS = {
  mode: 'all_teams',
  teams: [
    { team_id: 1, team_name: 'Ödeme',   sy_stats: { critical_domains: ['crit.example.com'] }, ug_stats: {} },
    { team_id: 2, team_name: 'Altyapı', sy_stats: { valid_domains: ['ok.example.com'] },      ug_stats: {} },
  ],
}

beforeEach(() => {
  vi.clearAllMocks()
  api.getCertificates.mockResolvedValue(CERTS)
  api.getStats.mockResolvedValue({ success: true, data: STATS })
  api.getTeamStats.mockResolvedValue({ success: true, data: TEAM_STATS })
})

describe('ExpiryForecastPage', () => {
  it('üç ucu da çağırır ve yükleme bitince içerik çizilir', async () => {
    render(<ExpiryForecastPage onSelectDomain={() => {}} />)

    await waitFor(() => {
      expect(api.getCertificates).toHaveBeenCalled()
      expect(api.getStats).toHaveBeenCalled()
      expect(api.getTeamStats).toHaveBeenCalled()
    })
    await waitFor(() => expect(document.querySelector('.forecast-page')).toBeTruthy())
  })

  it('yaklaşan vadeler listesinde sertifika alan adları görünür', async () => {
    render(<ExpiryForecastPage onSelectDomain={() => {}} />)
    await waitFor(() => expect(document.body.textContent).toContain('crit.example.com'))
  })

  it('sertifika ucu REDDEDİLSE bile sayfa çökmez (allSettled dayanıklılığı)', async () => {
    api.getCertificates.mockRejectedValue(new Error('boom'))
    render(<ExpiryForecastPage onSelectDomain={() => {}} />)

    await waitFor(() => expect(api.getStats).toHaveBeenCalled())
    await waitFor(() => expect(document.querySelector('.forecast-page')).toBeTruthy())
  })

  it('üç uç birden reddedilse de sayfa ayakta kalır', async () => {
    api.getCertificates.mockRejectedValue(new Error('a'))
    api.getStats.mockRejectedValue(new Error('b'))
    api.getTeamStats.mockRejectedValue(new Error('c'))
    render(<ExpiryForecastPage onSelectDomain={() => {}} />)

    await waitFor(() => expect(document.querySelector('.forecast-page')).toBeTruthy())
  })

  it('grafik aralığı değiştirilebilir (30 gün dışı dal yeniden hesaplar)', async () => {
    const user = userEvent.setup()
    render(<ExpiryForecastPage onSelectDomain={() => {}} />)
    await waitFor(() => expect(document.querySelector('.forecast-page')).toBeTruthy())

    // Aralık düğmeleri (7/30/90 gün gibi) — bulunanların hepsine sırayla tıkla.
    const rangeBtns = screen.getAllByRole('button').filter((b) => /\d+\s*(gün|day)/i.test(b.textContent || ''))
    for (const b of rangeBtns.slice(0, 3)) await user.click(b)

    expect(document.querySelector('.forecast-page')).toBeTruthy()
  })

  it('sertifika listesi boşken de çizilir', async () => {
    api.getCertificates.mockResolvedValue([])
    api.getStats.mockResolvedValue({ success: true, data: { total: 0 } })
    api.getTeamStats.mockResolvedValue({ success: true, data: null })
    render(<ExpiryForecastPage onSelectDomain={() => {}} />)

    await waitFor(() => expect(document.querySelector('.forecast-page')).toBeTruthy())
  })

  it('sertifikalar {data:[...]} sarmalıyla gelse de okunur', async () => {
    // api.getCertificates iki biçim döndürebiliyor; ikisi de desteklenmeli.
    api.getCertificates.mockResolvedValue({ data: CERTS })
    render(<ExpiryForecastPage onSelectDomain={() => {}} />)

    await waitFor(() => expect(document.body.textContent).toContain('crit.example.com'))
  })
})
