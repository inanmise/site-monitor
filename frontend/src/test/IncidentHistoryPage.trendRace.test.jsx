import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, act } from './test-utils.jsx'

/**
 * 2026-09-27 regresyon taraması (release-fixes.md FRONTEND A #9): Olay Geçmişi `loadTrends` (özet kartları, tarih
 * süzgecine bağlı) ve `loadTrendDaily` (günlük trend, 30/60/90 pencere) sıra korumasızdı — hızlı değişimde geç dönen
 * ESKİ yanıt yeni pencerenin kartlarını/grafiğini eziyordu (seçici "60g" derken grafik 30 günün verisi).
 * Denetimli promise'ler: eski istek YENİSİNDEN SONRA çözülür.
 */
const { apiMock } = vi.hoisted(() => {
  const deep = (obj) => new Proxy(obj, {
    get(t, prop) {
      if (prop === 'then' || typeof prop === 'symbol') return undefined
      if (prop in t) return typeof t[prop] === 'object' && t[prop] !== null ? deep(t[prop]) : t[prop]
      t[prop] = vi.fn(() => Promise.resolve({ success: true, data: [] }))
      return t[prop]
    },
  })
  return { apiMock: deep({ incidents: {}, admin: {} }) }
})
vi.mock('../api/client', () => ({ api: apiMock, formatDate: (s) => s ?? '', formatDateSec: (s) => s ?? '', formatDateOnly: (s) => s ?? '' }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ perms: {}, canView: () => true, canEdit: () => true, canExecute: () => true, refresh: () => {} }),
  PermissionsProvider: ({ children }) => children,
}))
// Tarih seçici ağır (Calendar + Popover); değer sözleşmesini (yyyy-MM-dd, onChange) koruyan ince taklit.
vi.mock('../components/ui/DateTimeField.jsx', () => ({
  default: ({ value, onChange, placeholder }) => <input aria-label={placeholder || 'date'} value={value || ''} onChange={(e) => onChange(e.target.value)} />,
}))
// Günlük trend grafiği jsdom'da çizilmez → çizilen VERİYİ gösteren sonda (toplam olay + gün sayısı).
vi.mock('@/components/shadcn/chart', async (importOriginal) => {
  const real = await importOriginal()
  return {
    ...real,
    ChartContainer: ({ children }) => <div data-slot="chart">{children}</div>,   // ResponsiveContainer jsdom'da 0 genişlikte çizmez
    BarChart: ({ data }) => <div data-slot="daily-probe" data-days={data.length} data-total={data.reduce((s, d) => s + d.count, 0)} />,
  }
})

import { api } from '../api/client'
import IncidentHistoryPage from '../components/IncidentHistoryPage.jsx'

function ymd(d) { return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` }
const deferred = () => { let resolve; const p = new Promise((r) => { resolve = r }); return { p, resolve } }
const flush = () => act(() => new Promise((r) => setTimeout(r, 0)))
const probe = () => document.querySelector('[data-slot="daily-probe"]')
const totalTile = () => document.querySelector('[data-slot="stat-item"][data-key="total"]')
const summary = (total) => ({ success: true, data: { summary: { total }, by_severity: {}, by_status: {} } })

describe('IncidentHistoryPage — trend yarışları', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState(null, '', '/')
    api.incidents.list.mockResolvedValue({ success: true, data: [], total: 0, page: 0, size: 50 })
    api.incidents.options.mockResolvedValue({ success: true, data: [] })
    api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
  })

  it('günlük trend: 30g yanıtı 60g seçildikten SONRA dönerse grafik 60g verisini gösterir', async () => {
    const d30 = deferred(), d60 = deferred()
    let dailyCalls = 0
    api.incidents.trends.mockImplementation((since, until) => {
      if (since && until) { dailyCalls += 1; return dailyCalls === 1 ? d30.p : d60.p }   // günlük (bugünle biten pencere)
      return Promise.resolve(summary(0))                                                   // özet (süzgeç aralığı)
    })
    render(<IncidentHistoryPage />)
    fireEvent.click(document.querySelector('[data-slot="stats-toggle"]'))
    await waitFor(() => expect(probe()).not.toBeNull())
    fireEvent.click(screen.getByRole('button', { name: /^60/ }))
    await waitFor(() => expect(dailyCalls).toBe(2))

    const today = ymd(new Date())
    await act(async () => { d60.resolve({ success: true, data: { daily: [{ day: today, count: 6 }] } }) })
    await waitFor(() => expect(probe().getAttribute('data-total')).toBe('6'))
    await act(async () => { d30.resolve({ success: true, data: { daily: [{ day: today, count: 3 }] } }) })
    await flush()
    expect(probe().getAttribute('data-days')).toBe('60')
    expect(probe().getAttribute('data-total')).toBe('6')
  })

  it('özet kartları: eski tarih aralığının yanıtı yeni aralıktan SONRA dönerse kartlar yeni sayıyı gösterir', async () => {
    const all = deferred(), ranged = deferred()
    api.incidents.trends.mockImplementation((since, until) => {
      if (since && until) return Promise.resolve({ success: true, data: { daily: [] } })   // günlük trend — bu testte önemsiz
      return since ? ranged.p : all.p
    })
    render(<IncidentHistoryPage />)
    fireEvent.click(document.querySelector('[data-slot="stats-toggle"]'))
    await waitFor(() => expect(totalTile()).not.toBeNull())
    fireEvent.change(screen.getAllByLabelText(/^(Since|Başlangıç|From|İtibaren)/i)[0], { target: { value: '2026-08-01' } })
    await waitFor(() => expect(api.incidents.trends).toHaveBeenCalledWith(expect.stringMatching(/^2026-0[78]-/), undefined))

    await act(async () => { ranged.resolve(summary(7)) })
    await waitFor(() => expect(totalTile().textContent).toMatch(/7/))
    await act(async () => { all.resolve(summary(42)) })
    await flush()
    expect(totalTile().textContent).toMatch(/7/)
    expect(totalTile().textContent).not.toMatch(/42/)
  })
})
