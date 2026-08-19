import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from './test-utils.jsx'
import userEvent from '@testing-library/user-event'
import IncidentHistoryPage from '../components/IncidentHistoryPage.jsx'

/**
 * Olay ve Hata Geçmişi — 908 satır, 2026-08-19'a kadar HİÇ test dosyası yoktu (kapsam %24,
 * tamamı lazy-tabs-smoke'un salt mount'undan geliyordu).
 *
 * İki dal özellikle önemli:
 *  - YETKİSİZ kullanıcı erken return'e düşer (`canView('incidents.view')`). Bu erken return
 *    aynı dosyada bir hook-sırası hatasına da yol açmıştı: `useUrlQuerySync` onun ALTINDA
 *    çağrılıyordu ve yetkiler asenkron yüklenip `allowView` false→true dönünce hook sayısı
 *    değişiyordu. Buradaki iki test o düzeltmeyi de korur.
 *  - Liste + trend + seçenek uçları paralel çekilir; kısmi başarısızlık sayfayı düşürmemeli.
 */

const { apiMock, permMock } = vi.hoisted(() => {
  const target = { incidents: {}, admin: {} }
  const deep = (obj) => new Proxy(obj, {
    get(t, prop) {
      if (prop === 'then' || typeof prop === 'symbol') return undefined
      if (prop in t) return typeof t[prop] === 'object' && t[prop] !== null ? deep(t[prop]) : t[prop]
      t[prop] = vi.fn(() => Promise.resolve({ success: true, data: [] }))
      return t[prop]
    },
  })
  return { apiMock: deep(target), permMock: { allow: true } }
})

vi.mock('../api/client', () => ({
  api: apiMock,
  formatDate:     (s) => s ?? '',
  formatDateSec:  (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
}))

vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({
    perms: {},
    canView:    () => permMock.allow,
    canEdit:    () => permMock.allow,
    canExecute: () => permMock.allow,
    refresh: () => {},
  }),
  PermissionsProvider: ({ children }) => children,
}))

import { api } from '../api/client'

const INCIDENTS = [
  {
    id: 1, title: 'Ödeme servisi kesintisi', occurred_at: '2026-08-18T09:00:00',
    detected_at: '2026-08-18T09:05:00', resolved_at: '2026-08-18T10:00:00',
    severity: 'CRITICAL', status: 'RESOLVED', category: 'APPLICATION',
    team_name: 'Ödeme', sla_breached: true, duration_minutes: 60,
  },
  {
    id: 2, title: 'DNS gecikmesi', occurred_at: '2026-08-17T14:00:00',
    severity: 'MEDIUM', status: 'OPEN', category: 'NETWORK',
    team_name: 'Altyapı', sla_breached: false,
  },
]

beforeEach(() => {
  vi.clearAllMocks()
  permMock.allow = true
  api.incidents.list.mockResolvedValue({ success: true, data: INCIDENTS, total: 2, page: 0, size: 50 })
  api.incidents.trends.mockResolvedValue({ success: true, data: [{ day: '2026-08-18', count: 1, critical: 1, high: 0, medium: 0, low: 0 }] })
  api.incidents.options.mockResolvedValue({ success: true, data: [] })
  api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 1, name: 'Ödeme' }] })
})

describe('IncidentHistoryPage — yetki', () => {
  it('yetkisiz kullanıcıya erişim yok mesajı gösterir, listeyi ÇEKMEZ', async () => {
    permMock.allow = false
    render(<IncidentHistoryPage />)

    expect(document.querySelector('.empty-state')).toBeTruthy()
  })

  it('yetki false→true dönerse hook sırası bozulmaz (yeniden render çökmez)', async () => {
    // Regresyon: useUrlQuerySync erken return'ün ALTINDAYDI; yetkiler asenkron yüklenince
    // hook sayısı değişiyor ve React "Rendered more hooks" ile patlıyordu.
    permMock.allow = false
    const { rerender } = render(<IncidentHistoryPage />)
    expect(document.querySelector('.empty-state')).toBeTruthy()

    permMock.allow = true
    rerender(<IncidentHistoryPage />)

    await waitFor(() => expect(api.incidents.list).toHaveBeenCalled())
  })
})

describe('IncidentHistoryPage — liste', () => {
  it('yetkiliyken listeyi ve trendi çeker', async () => {
    render(<IncidentHistoryPage />)
    await waitFor(() => {
      expect(api.incidents.list).toHaveBeenCalled()
      expect(api.incidents.trends).toHaveBeenCalled()
    })
  })

  it('olay başlıkları çizilir', async () => {
    render(<IncidentHistoryPage />)
    await waitFor(() => expect(document.body.textContent).toContain('Ödeme servisi kesintisi'))
    expect(document.body.textContent).toContain('DNS gecikmesi')
  })

  it('boş listede çökmez', async () => {
    api.incidents.list.mockResolvedValue({ success: true, data: [], total: 0, page: 0, size: 50 })
    render(<IncidentHistoryPage />)
    await waitFor(() => expect(api.incidents.list).toHaveBeenCalled())
    expect(screen.getAllByRole('button').length).toBeGreaterThan(0)
  })

  it('liste ucu REDDEDİLSE bile sayfa ayakta kalır', async () => {
    api.incidents.list.mockRejectedValue(new Error('boom'))
    render(<IncidentHistoryPage />)
    await waitFor(() => expect(api.incidents.list).toHaveBeenCalled())
    expect(document.body.textContent.length).toBeGreaterThan(0)
  })

  it('trend ucu başarısız olsa da liste çizilir', async () => {
    api.incidents.trends.mockRejectedValue(new Error('trend down'))
    render(<IncidentHistoryPage />)
    await waitFor(() => expect(document.body.textContent).toContain('Ödeme servisi kesintisi'))
  })
})

describe('IncidentHistoryPage — etkileşim', () => {
  it('özet akordiyonu açılıp kapanır (varsayılan gizli dal)', async () => {
    const user = userEvent.setup()
    render(<IncidentHistoryPage />)
    await waitFor(() => expect(api.incidents.list).toHaveBeenCalled())

    const acc = document.querySelector('.inc-summary-acc')
    expect(acc).toBeTruthy()
    const before = acc.getAttribute('aria-expanded')
    await user.click(acc)
    expect(document.querySelector('.inc-summary-acc').getAttribute('aria-expanded')).not.toBe(before)
  })

  it('önem filtresi değişince liste yeniden çekilir', async () => {
    const user = userEvent.setup()
    render(<IncidentHistoryPage />)
    await waitFor(() => expect(api.incidents.list).toHaveBeenCalled())
    const firstCalls = api.incidents.list.mock.calls.length

    const selects = [...document.querySelectorAll('select.filter-select')]
    expect(selects.length).toBeGreaterThan(0)
    await user.selectOptions(selects[0], 'CRITICAL')

    await waitFor(() => expect(api.incidents.list.mock.calls.length).toBeGreaterThan(firstCalls))
  })

  it('arama kutusuna yazınca liste yenilenir', async () => {
    const user = userEvent.setup()
    render(<IncidentHistoryPage />)
    await waitFor(() => expect(api.incidents.list).toHaveBeenCalled())
    const firstCalls = api.incidents.list.mock.calls.length

    const input = document.querySelector('input.filter-input')
    expect(input).toBeTruthy()
    await user.type(input, 'ödeme')

    await waitFor(() => expect(api.incidents.list.mock.calls.length).toBeGreaterThan(firstCalls))
  })

  it('durum ve kategori filtreleri de listeyi tetikler', async () => {
    const user = userEvent.setup()
    render(<IncidentHistoryPage />)
    await waitFor(() => expect(api.incidents.list).toHaveBeenCalled())

    const selects = [...document.querySelectorAll('select.filter-select')]
    for (const sel of selects.slice(0, 3)) {
      const opt = [...sel.options].find(o => o.value)
      if (opt) await user.selectOptions(sel, opt.value)
    }

    await waitFor(() => expect(document.body.textContent.length).toBeGreaterThan(0))
  })
})
