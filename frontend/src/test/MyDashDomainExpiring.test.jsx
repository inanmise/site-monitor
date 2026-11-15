import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
await vi.hoisted(async () => (await import('./helpers/orgNoonClock.js')).freezeOrgNoon())   // kalan gün İstanbul günüyle (LOW 2): sabit 09:00Z
import { useState } from 'react'
import { render, waitFor, fireEvent, within } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({ monitoring: { getDomainMonitors: vi.fn() } }),
}))

const perm = vi.hoisted(() => ({ value: {} }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({
    perms: perm.value, canView: (r) => !!perm.value[r]?.view, canEdit: () => false, canExecute: () => false,
    refresh: () => {}, loaded: true, failed: false,
  }),
  PermissionsProvider: ({ children }) => children,
}))

import { api } from '../api/client'
import { EN } from '../i18n/en.js'
import { TR } from '../i18n/tr.js'
import { UserPrefsContext } from '../hooks/useUserPrefs.js'
import MyDashPage from '../components/mydash/MyDashPage.jsx'
import { DEFAULT_TYPES, NOC_PRESET, STAT_PRESET, WIDGET_CATALOG, addableTypes } from '../components/mydash/myDashModel.js'
import { WIDGETS } from '../components/mydash/widgets.jsx'

/**
 * Kişisel Panom "Yakında bitecek alan adları" (2026-11-15, C2): katalogda (yalnız "Kutu ekle"), izin `monitoring.read`,
 * en az gün önce, canlı gün; dolmuş / kritik kırmızı, uyarı turuncu; yeşil yalnız hepsinin bitişi bilinirken; bilinmeyen gri.
 */
const LONG = 'Kurumsal İnternet Bankacılığı Kampanya Alan Adı (Bireysel ve Ticari — Canlı Ortam)'
const setSpy = vi.fn()
const setterRef = { current: null }
function Shell({ initial = null, children }) {
  const [dashboard, setDashboard] = useState(initial)
  setterRef.current = setDashboard
  return <UserPrefsContext.Provider value={{ ready: true, status: 'ready', prefs: {}, dashboard, setDashboard: setSpy }}>{children}</UserPrefsContext.Provider>
}
const layout = (style) => ({ v: 1, widgets: [{ id: 'domain-expiring', type: 'domain_expiring', size: 'md', ...(style ? { style } : {}) }] })
const widget = () => document.querySelector('[data-slot="mydash-widget"][data-type="domain_expiring"]')
const day = (offset) => { const d = new Date(); d.setDate(d.getDate() + offset); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` }

let navs = []
const onNav = (e) => navs.push(e.detail)
beforeEach(() => {
  vi.clearAllMocks()
  navs = []
  window.addEventListener('sm:navigate', onNav)
  setSpy.mockImplementation((v) => { setterRef.current?.(v); return true })
  window.history.replaceState({}, '', '/?tab=mydash')
  perm.value = { 'monitoring.read': { view: true } }
})
afterEach(() => {
  window.removeEventListener('sm:navigate', onNav)
  window.history.replaceState({}, '', '/')
})

describe('Panom — Yakında bitecek alan adları', () => {
  it('katalogda (rakamsız tür, monitoring.read), çizim kaydında; varsayılanda / hazır düzenlerde YOK; TR + EN metin', () => {
    const e = WIDGET_CATALOG.find((x) => x.type === 'domain_expiring')
    expect(e).toMatchObject({ perm: 'monitoring.read', tab: 'domain' })
    expect('domain_expiring').toMatch(/^[a-z_]{1,40}$/)
    expect(WIDGETS.domain_expiring).toBeTruthy()
    expect(addableTypes([], (p) => p === 'monitoring.read')).toContain('domain_expiring')
    expect(addableTypes([], () => false)).not.toContain('domain_expiring')
    expect(DEFAULT_TYPES).not.toContain('domain_expiring')
    expect(NOC_PRESET.map((w) => w.type)).not.toContain('domain_expiring')
    expect(STAT_PRESET.map((w) => w.type)).not.toContain('domain_expiring')
    for (const d of [EN, TR]) {
      expect(d['mydash.w.domain_expiring']).toBeTruthy()
      expect(d['mydash.wd.domain_expiring']).toBeTruthy()
    }
  })

  it('en az gün önce; dolmuş kırmızı ve "N gün önce doldu"; satır izlemeyi açar; uzun ad tam görünür; duraklatılan sayılmaz', async () => {
    api.monitoring.getDomainMonitors.mockResolvedValue({ success: true, data: [
      { id: 1, domain: 'uzak.com', name: 'uzak.com', active: true, expiry_date: day(200), warning_days: 30, critical_days: 7 },
      { id: 2, domain: 'dolmus.com.tr', name: LONG, active: true, expiry_date: day(-3), days_remaining: 5 },
      { id: 3, domain: 'yakin.com', name: 'yakin.com', active: true, expiry_date: day(20) },
      { id: 4, domain: 'durdu.com', name: 'durdu.com', active: false, expiry_date: day(-30) },
    ] })
    render(<Shell initial={layout()}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget()?.querySelector('[data-slot="mydash-dexp-list"]')).not.toBeNull())
    const w = widget()
    expect(w).toHaveAttribute('data-tone', 'danger')
    const rows = w.querySelectorAll('[data-slot="mydash-dexp-list"] li')
    expect([...rows].map((r) => r.textContent.match(/[a-z]+\.com(\.tr)?/)[0])).toEqual(['dolmus.com.tr', 'yakin.com', 'uzak.com'])
    expect(rows[0]).toHaveTextContent('expired 3 days ago')
    expect(rows[0]).toHaveTextContent(LONG)
    expect(w.querySelector('[data-slot="mydash-dexp-expired"]')).toHaveTextContent('1')
    expect(w.querySelector('[data-slot="mydash-dexp-warning"]')).toHaveTextContent('1')
    fireEvent.click(within(rows[0]).getByRole('button'))
    expect(navs.at(-1)).toEqual({ tab: 'domain', params: { monitor: 2 } })
  })

  it('bitişi bilinmeyen varken asla yeşil değil; hepsi biliniyor ve eşikte değilse yeşil; yetki yoksa gri', async () => {
    api.monitoring.getDomainMonitors.mockResolvedValue({ success: true, data: [
      { id: 1, domain: 'a.com', active: true, expiry_date: day(300) },
      { id: 2, domain: 'b.com', active: true, expiry_date: null, days_remaining: null },
    ] })
    const r1 = render(<Shell initial={layout('stat')}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget()).toHaveAttribute('data-tone', 'muted'))
    r1.unmount()

    api.monitoring.getDomainMonitors.mockResolvedValue({ success: true, data: [{ id: 1, domain: 'a.com', active: true, expiry_date: day(300) }] })
    const r2 = render(<Shell initial={layout()}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget()).toHaveAttribute('data-tone', 'success'))
    r2.unmount()

    api.monitoring.getDomainMonitors.mockResolvedValue({ success: false, status: 403, error: 'forbidden' })
    render(<Shell initial={layout('stat')}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget()).toHaveAttribute('data-tone', 'muted'))
    expect(widget()).not.toHaveAttribute('data-stat', 'on')
  })
})
