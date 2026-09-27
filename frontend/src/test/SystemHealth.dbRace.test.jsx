import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, waitFor, act, fireEvent, within } from './test-utils.jsx'
import SystemHealth from '../components/admin/SystemHealth.jsx'
import DbAnalyticsPanel from '../components/admin/DbAnalyticsPanel.jsx'

/**
 * 2026-09-27 regresyon taraması (BUG_REGRESYON BF2 / release-fixes.md FRONTEND A #10): Veritabanı Analitiği
 * `loadDbAnalytics` sıra korumasızdı ve pencere seçici yüklenirken açık: 7 → 30 → 1 hızlı tıklanınca ağır 30 günlük
 * yanıt EN SON gelip "1 gün" seçiliyken 30 günlük veriyi çiziyordu (yoklama yok → bir sonraki tıklamaya kadar kalır);
 * ilk dönen bastırılmış yanıt da yükleniyor bayrağını erken söndürüyordu. Ayrıca panel etiket/kova biçimini seçiciden
 * alıyordu → yeni pencerenin isteği uçuştayken ekrandaki eski veri yanlış etiketle çiziliyordu. Denetimli promise'ler.
 */
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ canView: () => true, canEdit: () => true, canExecute: () => true, perms: {} }),
}))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => false }))
const { adminProxy } = vi.hoisted(() => {
  const target = { getSystemHealth: vi.fn(), getDbAnalytics: vi.fn(), pushLog: { summary: vi.fn() } }
  return {
    adminProxy: new Proxy(target, {
      get(t, prop) {
        if (prop in t || typeof prop === 'symbol') return t[prop]
        t[prop] = vi.fn(() => Promise.resolve({ success: true, data: [] }))
        return t[prop]
      },
    }),
  }
})
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: { admin: adminProxy, runScheduler: vi.fn(() => Promise.resolve({ success: true })) },
}))
import { api } from '../api/client'

const deferred = () => { let resolve; const p = new Promise((r) => { resolve = r }); return { p, resolve } }
const flush = () => act(() => new Promise((r) => setTimeout(r, 0)))
const payload = (days, queries) => ({ success: true, data: { summary: { queries, db_size: '10 MB', pgss: true, days }, connections: { active: 2, max: 50 }, series: [] } })
const queriesKpi = () => document.querySelector('[data-kpi="queries"]')
const panel = () => document.querySelector('[data-testid="db-analytics"]')
const refreshBtn = () => within(panel()).getByRole('button', { name: /refresh|yenile|refreshing|yenileniyor/i })

describe('SystemHealth — Veritabanı Analitiği pencere yarışı (BF2)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState(null, '', '/')
    api.admin.getSystemHealth.mockResolvedValue({ success: true, data: { scheduler: {}, build: {}, lock: { held: false } } })
    api.admin.pushLog.summary.mockResolvedValue({ success: true, data: { kpi: {} } })
  })

  it('7 → 30 → 1 hızlı geçişte geç dönen 30/7 günlük yanıtlar 1 günlük veriyi EZMEZ; bayrak son isteği yansıtır', async () => {
    const q = { 7: deferred(), 30: deferred(), 1: deferred() }
    api.admin.getDbAnalytics.mockImplementation((days) => q[days].p)
    render(<SystemHealth systemRole="ADMIN" globalAdmin />)
    await waitFor(() => expect(document.querySelector('[data-section="db"] [data-slot="stats-toggle"]')).not.toBeNull())
    fireEvent.click(document.querySelector('[data-section="db"] [data-slot="stats-toggle"]'))
    await waitFor(() => expect(api.admin.getDbAnalytics).toHaveBeenCalledWith(7))

    fireEvent.click(within(panel()).getByRole('button', { name: /^30 (gün|days)$/i }))
    await waitFor(() => expect(api.admin.getDbAnalytics).toHaveBeenCalledWith(30))
    fireEvent.click(within(panel()).getByRole('button', { name: /^1 (gün|day)$/i }))
    await waitFor(() => expect(api.admin.getDbAnalytics).toHaveBeenLastCalledWith(1))

    // Eski (7 g) yanıt önce döner: 1 g hâlâ uçuşta → bayrak SÖNMEZ, 7 g verisi çizilmez.
    await act(async () => { q[7].resolve(payload(7, 7777)) })
    await flush()
    expect(refreshBtn()).toHaveAttribute('aria-busy', 'true')
    expect(queriesKpi()?.textContent ?? '').not.toMatch(/7[.,]?777/)

    await act(async () => { q[1].resolve(payload(1, 1111)) })
    await waitFor(() => expect(queriesKpi().textContent).toMatch(/1[.,]?111/))
    // Ağır 30 g yanıtı EN SON döner → ekranda 1 g kalır.
    await act(async () => { q[30].resolve(payload(30, 3030)) })
    await flush()
    expect(queriesKpi().textContent).toMatch(/1[.,]?111/)
    expect(queriesKpi().textContent).not.toMatch(/3[.,]?030/)
    expect(refreshBtn()).not.toHaveAttribute('aria-busy')
  })
})

describe('DbAnalyticsPanel — pencere etiketi çizilen VERİDEN', () => {
  it('seçici 1 gündeyken ekrandaki veri 30 günlükse etiketler 30 günü söyler (saatlik "1 gün" DEĞİL)', () => {
    render(<DbAnalyticsPanel data={payload(30, 3030).data} days={1} onDaysChange={() => {}} onRefresh={() => {}} loading />)
    expect(queriesKpi().textContent).toMatch(/30 (Days|Gün)/)
    expect(queriesKpi().textContent).not.toMatch(/1 (Day|Gün)\b/)
  })
})
