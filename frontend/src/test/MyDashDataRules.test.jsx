import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useState } from 'react'
import { render, waitFor, fireEvent, within } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({ monitoring: { getDbQueryMonitors: vi.fn() } }),
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
import { UserPrefsContext } from '../hooks/useUserPrefs.js'
import MyDashPage from '../components/mydash/MyDashPage.jsx'
import { WIDGET_CATALOG, addableTypes } from '../components/mydash/myDashModel.js'
import { WIDGETS } from '../components/mydash/widgets.jsx'

/**
 * Kişisel Panom "Veri kuralları" kutusu (2026-11-05, dbq2 C5): veritabanı sorgu izlemelerinin ihlal / hata / değerlendirilemeyen
 * sayıları; renkli panel (hatalı → kırmızı, ihlal → turuncu, hepsi temiz → yeşil, okunamadı / bilinmiyor → gri). Tıklama
 * Veritabanı Sorgusu sayfasını "Şu an ihlalde" süzgeciyle (`dq=breach`), satır izlemenin ayrıntısını açar. İzin
 * `monitoring.read`; tür adı rakamsız (sunucu `[a-z_]` kuralı).
 */
const LONG_NAME = 'Başarısız ödeme işlemleri — son 15 dakika (Kurumsal ve Bireysel Bankacılık Ödeme Altyapısı)'
const PERMS = { 'monitoring.read': { view: true } }

const setSpy = vi.fn()
const setterRef = { current: null }
function Shell({ initial = null, children }) {
  const [dashboard, setDashboard] = useState(initial)
  setterRef.current = setDashboard
  return <UserPrefsContext.Provider value={{ ready: true, status: 'ready', prefs: {}, dashboard, setDashboard: setSpy }}>{children}</UserPrefsContext.Provider>
}
const layout = (style) => ({ v: 1, widgets: [{ id: 'data-rules', type: 'data_rules', size: 'md', ...(style ? { style } : {}) }] })
const widget = () => document.querySelector('[data-slot="mydash-widget"][data-type="data_rules"]')
const m = (id, over = {}) => ({ id, name: `İzleme ${id}`, active: true, status: 'OK', team_name: 'SY-A', profile: { name: 'Sipariş DB' }, ...over })

let navs = []
const onNav = (e) => navs.push(e.detail)

beforeEach(() => {
  vi.clearAllMocks()
  navs = []
  window.addEventListener('sm:navigate', onNav)
  setSpy.mockImplementation((v) => { setterRef.current?.(v); return true })
  window.history.replaceState({}, '', '/?tab=mydash')
  perm.value = PERMS
})
afterEach(() => {
  window.removeEventListener('sm:navigate', onNav)
  window.history.replaceState({}, '', '/')
})

describe('Panom — Veri kuralları', () => {
  it('katalogda (tür adı rakamsız, izin monitoring.read) ve çizim kaydında; izinsizse eklenemez', () => {
    const e = WIDGET_CATALOG.find((x) => x.type === 'data_rules')
    expect(e).toMatchObject({ perm: 'monitoring.read', tab: 'dbquery' })
    expect(e.type).toMatch(/^[a-z_]{1,40}$/)
    expect(WIDGETS.data_rules).toBeTruthy()
    expect(addableTypes([], (p) => p === 'monitoring.read')).toContain('data_rules')
    expect(addableTypes([], () => false)).not.toContain('data_rules')
    expect(EN['mydash.w.data_rules']).toBeTruthy()
    expect(EN['mydash.wd.data_rules']).toBeTruthy()
  })

  it('sayılar + en ağır izlemeler; hatalı varsa kırmızı; tıklama "Şu an ihlalde" süzgeciyle, satır ayrıntıyla', async () => {
    api.monitoring.getDbQueryMonitors.mockResolvedValue({ success: true, data: [
      m(1), m(2, { status: 'BREACH', breach_level: 'WARNING' }), m(3, { status: 'FAIL', name: LONG_NAME }), m(4, { status: 'UNKNOWN' }),
      m(5, { active: false, status: 'FAIL' }),
    ] })
    render(<Shell initial={layout()}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget()?.querySelector('[data-slot="mydash-rules-list"]')).not.toBeNull())
    expect(widget()).toHaveAttribute('data-tone', 'danger')
    expect(widget().querySelector('[data-slot="mydash-rules-total"]')).toHaveTextContent('2')
    expect(widget().querySelector('[data-slot="mydash-rules-fail"]')).toHaveTextContent('1')
    expect(widget().querySelector('[data-slot="mydash-rules-breach"]')).toHaveTextContent('1')
    expect(widget().querySelector('[data-slot="mydash-rules-unknown"]')).toHaveTextContent('1')
    const rows = widget().querySelectorAll('[data-slot="mydash-rules-list"] li')
    expect(rows).toHaveLength(3)
    expect(rows[0]).toHaveTextContent(LONG_NAME)
    expect(rows[0]).toHaveTextContent(EN['mydash.rules.p.fail'])
    fireEvent.click(widget().querySelector('[data-slot="mydash-rules-total"]'))
    expect(navs.at(-1)).toEqual({ tab: 'dbquery', params: { dq: 'breach' } })
    fireEvent.click(within(rows[0]).getByRole('button'))
    expect(navs.at(-1)).toEqual({ tab: 'dbquery', params: { monitor: 3 } })
  })

  it('renkli panel: ihlal turuncu zemin + sayı; hepsi temiz yeşil; değerlendirilemeyen varsa boyanmaz (yeşil değil)', async () => {
    api.monitoring.getDbQueryMonitors.mockResolvedValue({ success: true, data: [m(1), m(2, { status: 'BREACH', breach_level: 'CRITICAL' })] })
    const { unmount } = render(<Shell initial={layout('stat')}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget()).toHaveAttribute('data-stat', 'on'))
    expect(widget()).toHaveAttribute('data-tone', 'warning')
    expect(widget().querySelector('[data-slot="mydash-stat-value"]')).toHaveTextContent('1')
    unmount()
    api.monitoring.getDbQueryMonitors.mockResolvedValue({ success: true, data: [m(1), m(2)] })
    const r2 = render(<Shell initial={layout('stat')}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget()).toHaveAttribute('data-tone', 'success'))
    expect(widget()).toHaveAttribute('data-stat', 'on')
    r2.unmount()
    api.monitoring.getDbQueryMonitors.mockResolvedValue({ success: true, data: [m(1), m(2, { status: null })] })
    render(<Shell initial={layout('stat')}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget()).toHaveAttribute('data-tone', 'muted'))
    expect(widget()).not.toHaveAttribute('data-stat', 'on')
  })

  it('okunamadı → hata durumu (renk yok); 403 → "yetki yok" gri; izleme yok → gri', async () => {
    api.monitoring.getDbQueryMonitors.mockResolvedValue({ success: false, status: 500, error: 'x' })
    const { unmount } = render(<Shell initial={layout()}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget()).toHaveAttribute('data-failed', 'true'))
    expect(widget()).toHaveAttribute('data-tone', 'muted')
    unmount()
    api.monitoring.getDbQueryMonitors.mockResolvedValue({ success: false, status: 403, error: 'yok' })
    const r2 = render(<Shell initial={layout()}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget()?.querySelector('[data-slot="mydash-empty"]')).not.toBeNull())
    expect(widget()).toHaveAttribute('data-tone', 'muted')
    expect(widget()).toHaveTextContent(EN['mydash.noAccessBody'])
    r2.unmount()
    api.monitoring.getDbQueryMonitors.mockResolvedValue({ success: true, data: [] })
    render(<Shell initial={layout()}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget()).toHaveTextContent(EN['mydash.rules.noneBody']))
    expect(widget()).toHaveAttribute('data-tone', 'muted')
  })
})
