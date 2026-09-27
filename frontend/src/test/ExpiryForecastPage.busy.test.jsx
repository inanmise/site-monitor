import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, within, act, waitFor } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import ExpiryForecastPage from '../pages/ExpiryForecastPage.jsx'

/**
 * 2026-09-27 regresyon taraması (release-fixes.md FRONTEND A #8): Sertifika Takvimi "Şimdi kontrol et" tek yuvalı
 * meşgul bayrağı (`busyDomain`) tutuyordu — A sürerken B'ye basınca A'nın satır menüsünde "Şimdi kontrol et" yeniden
 * görünüyor (iş sürerken bitmiş gibi, ikinci kez basılabilir), önce biten B, A'nın kilidini de açıyordu.
 * Model `useRunningChecks`: alan adı KÜMESİ. Denetimli promise'lerle belirlenimci.
 */
const { apiMock } = vi.hoisted(() => {
  const target = { getForecast: vi.fn(), refreshCertificateHealth: vi.fn() }
  return { apiMock: new Proxy(target, { get(t, prop) { if (prop in t || typeof prop === 'symbol') return t[prop]; t[prop] = vi.fn(() => Promise.resolve({ success: true, data: [] })); return t[prop] } }) }
})
vi.mock('../api/client', async () => {
  const real = await vi.importActual('../api/client')
  return { api: apiMock, formatDate: (s) => s ?? '', formatDateSec: (s) => s ?? '', formatDateOnly: (s) => s ?? '', localDayKey: real.localDayKey }
})
vi.mock('../utils/ics.js', () => ({ buildIcs: vi.fn(() => 'ICS'), downloadIcs: vi.fn() }))
import { api } from '../api/client'

function inDays(n) { const d = new Date(); d.setDate(d.getDate() + n); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` }
const cert = (domain, days) => ({
  domain, days_remaining: days, status: 'valid', not_after: `${inDays(days)}T12:00:00`, renew_by: inDays(days - 14), lead_days: 14,
  tier: 1, team_id: 1, team_name: 'Takım A', issuer_cn: 'CA One', fingerprint: 'F' + domain, renewal_plan_state: 'none', checked_at: '2026-09-12T10:00:00',
})
const DATA = {
  certs: [cert('crit.example.com', 3), cert('high.example.com', 10)],
  thresholds: { warning: 30, high: 15, critical: 7 }, lead_days: { default: 14, t1: 30, t2: 14, t3: 14, t4: 14 },
  data_as_of: '2026-09-12T10:00:00', environment: 'test', renewals: { window_days: 90, on_time: 0, late: 0, months: [], events: [] }, domains: [],
}
const deferred = () => { let resolve; const p = new Promise((r) => { resolve = r }); return { p, resolve } }
const row = (domain) => document.querySelector(`[data-slot="fc-row"][data-domain="${domain}"]`)
const menuTrigger = (domain) => within(row(domain)).getByRole('button', { name: new RegExp(`${domain.replace(/\./g, '[.]')} — (Row actions|Satır işlemleri)`) })
async function openMenu(domain) {
  pressMenuTrigger(menuTrigger(domain))
  return screen.findByRole('menu')
}
async function closeMenu() {
  fireEvent.keyDown(document.activeElement || document.body, { key: 'Escape' })
  await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
}
const checkItem = (menu) => within(menu).queryByRole('menuitem', { name: /Check now|Şimdi kontrol et/ })

describe('ExpiryForecastPage — eşzamanlı "Şimdi kontrol et" (meşgul kümesi)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState(null, '', '/')
    api.getForecast.mockResolvedValue({ success: true, data: DATA })
  })

  it('A sürerken B başlarsa A meşgul KALIR; önce biten B, A\'nın kilidini açmaz', async () => {
    const calls = { 'crit.example.com': deferred(), 'high.example.com': deferred() }
    api.refreshCertificateHealth.mockImplementation((d) => calls[d].p)
    render(<ExpiryForecastPage />)
    await screen.findByText(/TEST/)
    fireEvent.click(screen.getByRole('button', { name: /^List$|^Liste$/ }))
    await waitFor(() => expect(row('crit.example.com')).not.toBeNull())

    fireEvent.click(checkItem(await openMenu('crit.example.com')))
    await waitFor(() => expect(api.refreshCertificateHealth).toHaveBeenCalledWith('crit.example.com'))
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
    fireEvent.click(checkItem(await openMenu('high.example.com')))
    await waitFor(() => expect(api.refreshCertificateHealth).toHaveBeenCalledWith('high.example.com'))
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())

    // A (crit) hâlâ sürüyor → menüsünde "Şimdi kontrol et" GİZLİ kalmalı.
    expect(checkItem(await openMenu('crit.example.com'))).toBeNull()
    await closeMenu()

    // Önce B biter → A'nın kilidi açılmamalı.
    await act(async () => { calls['high.example.com'].resolve({ success: true }) })
    expect(checkItem(await openMenu('crit.example.com'))).toBeNull()
    await closeMenu()
    expect(checkItem(await openMenu('high.example.com'))).not.toBeNull()
    await closeMenu()

    await act(async () => { calls['crit.example.com'].resolve({ success: true }) })
    expect(checkItem(await openMenu('crit.example.com'))).not.toBeNull()
  })
})
