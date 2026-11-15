import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useState } from 'react'
import { render, waitFor, fireEvent, within } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({ monitoring: { getPageSpeedMonitors: vi.fn() } }),
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
import { DEFAULT_TYPES, DETAIL_ONLY_TYPES, NOC_PRESET, STAT_PRESET, WIDGET_CATALOG, addableTypes, tvDetail } from '../components/mydash/myDashModel.js'
import { WIDGETS } from '../components/mydash/widgets.jsx'
import { pageSpeedOverBudget, pageSpeedSlowest } from '../components/mydash/pageSpeedWidgetModel.js'

/**
 * Kişisel Panom Sayfa Hızı kutuları (2026-11-14, madde 21): en yavaş sayfalar (sıralama, TV'de liste) ve eşik / bütçe aşan
 * sayfalar (renkli panel). Kaynak mevcut sayfa hızı listesi; izin `monitoring.read`; varsayılan düzende ve hazır düzenlerde
 * YOK; bilinmeyen asla yeşil değil; tıklama düzenleme / TV kipinde etkisiz.
 */
const LONG_NAME = 'Kurumsal İnternet Bankacılığı Ödeme Onay Sayfası (Bireysel ve Ticari Müşteriler — Canlı Ortam)'
const PERMS = { 'monitoring.read': { view: true } }
const setSpy = vi.fn()
const setterRef = { current: null }
function Shell({ initial = null, children }) {
  const [dashboard, setDashboard] = useState(initial)
  setterRef.current = setDashboard
  return <UserPrefsContext.Provider value={{ ready: true, status: 'ready', prefs: {}, dashboard, setDashboard: setSpy }}>{children}</UserPrefsContext.Provider>
}
const layout = (type, style) => ({ v: 1, widgets: [{ id: type.replace(/_/g, '-'), type, size: 'md', ...(style ? { style } : {}) }] })
const widget = (type) => document.querySelector(`[data-slot="mydash-widget"][data-type="${type}"]`)
const m = (id, over = {}) => ({ id, name: `Sayfa ${id}`, url: `https://x.com/${id}`, active: true, status: 'OK', ok: true,
  response_ms: 1000 + id, max_load_ms: null, breached_metrics: [], team_name: 'SY-A', ...over })

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

describe('Panom — Sayfa Hızı kutuları: katalog ve modeller', () => {
  it('katalogda (rakamsız tür adı, monitoring.read), çizim kaydında; varsayılanda / hazır düzenlerde YOK; TV kuralı', () => {
    for (const type of ['pagespeed_slowest', 'pagespeed_over_budget']) {
      const e = WIDGET_CATALOG.find((x) => x.type === type)
      expect(e).toMatchObject({ perm: 'monitoring.read', tab: 'pagespeed' })
      expect(type).toMatch(/^[a-z_]{1,40}$/)
      expect(WIDGETS[type]).toBeTruthy()
      expect(addableTypes([], (p) => p === 'monitoring.read')).toContain(type)
      expect(addableTypes([], () => false)).not.toContain(type)
      expect(DEFAULT_TYPES).not.toContain(type)
      expect(NOC_PRESET.map((w) => w.type)).not.toContain(type)
      expect(STAT_PRESET.map((w) => w.type)).not.toContain(type)
      for (const d of [EN, TR]) {
        expect(d[`mydash.w.${type}`]).toBeTruthy()
        expect(d[`mydash.wd.${type}`]).toBeTruthy()
      }
    }
    expect(DETAIL_ONLY_TYPES.has('pagespeed_slowest')).toBe(true)
    expect(DETAIL_ONLY_TYPES.has('pagespeed_over_budget')).toBe(false)
    expect(tvDetail({ type: 'pagespeed_over_budget' }, 'stat')).toBe(false)
  })

  it('pageSpeedSlowest: ölçülenler yükleme süresine göre azalan; alınamayan / ölçülmeyen ayrı; bilinmeyen varken yeşil değil', () => {
    const s = pageSpeedSlowest([m(1, { response_ms: 900 }), m(2, { response_ms: 4000, max_load_ms: 3000 }),
      m(3, { status: 'DOWN', ok: false, response_ms: null }), m(4, { status: null, response_ms: null }), m(5, { active: false })], 5)
    expect(s.rows.map((r) => r.id)).toEqual([2, 1])
    expect(s.rows[0].over).toBe(true)
    expect(s).toMatchObject({ active: 4, measured: 2, down: 1, unknown: 1, tone: 'danger' })
    expect(pageSpeedSlowest([m(1), m(2, { status: null, response_ms: null })]).tone).toBe('muted')
    expect(pageSpeedSlowest([m(1), m(2)]).tone).toBe('success')
    expect(pageSpeedSlowest({ visible: false }).hidden).toBe(true)
    expect(pageSpeedSlowest([m(1, { time_truncated: true })]).rows[0].lowerBound).toBe(true)
  })

  it('pageSpeedOverBudget: aşan sayfa turuncu; hepsi eşik içinde yeşil; alınamayan varsa gri; satırda aşılan ölçüler', () => {
    const s = pageSpeedOverBudget([m(1), m(2, { status: 'SLOW', breached_metrics: ['LOAD', 'JS'] }), m(3, { status: 'SLOW', breached_metrics: 'MINSIZE' })])
    expect(s).toMatchObject({ over: 2, ok: 1, tone: 'warning' })
    expect(s.rows[0]).toMatchObject({ id: 2, metrics: ['LOAD', 'JS'] })
    expect(pageSpeedOverBudget([m(1), m(2)]).tone).toBe('success')
    expect(pageSpeedOverBudget([m(1), m(2, { status: 'DOWN', ok: false })]).tone).toBe('muted')
    expect(pageSpeedOverBudget([]).tone).toBe('muted')
  })
})

describe('Panom — Sayfa Hızı kutuları: çizim', () => {
  it('en yavaş sayfalar: satır izlemeyi açar, uzun ad tam görünür; sayı en yavaş süre', async () => {
    api.monitoring.getPageSpeedMonitors.mockResolvedValue({ success: true, data: [
      m(1, { response_ms: 900 }), m(2, { response_ms: 4200, max_load_ms: 3000, name: LONG_NAME }), m(3, { status: 'DOWN', ok: false }),
    ] })
    render(<Shell initial={layout('pagespeed_slowest')}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('pagespeed_slowest')?.querySelector('[data-slot="mydash-psp-list"]')).not.toBeNull())
    const w = widget('pagespeed_slowest')
    expect(w).toHaveAttribute('data-tone', 'danger')
    expect(w.querySelector('[data-slot="mydash-psp-slowest"]')).toHaveTextContent('4.2 s')
    const rows = w.querySelectorAll('[data-slot="mydash-psp-list"] li')
    expect(rows[0]).toHaveTextContent(LONG_NAME)
    fireEvent.click(within(rows[0]).getByRole('button'))
    expect(navs.at(-1)).toEqual({ tab: 'pagespeed', params: { monitor: 2 } })
  })

  it('bütçeyi aşan sayfalar: renkli panelde sayı; tıklama "Eşik aşıldı" süzgeciyle; yetki yoksa gri', async () => {
    api.monitoring.getPageSpeedMonitors.mockResolvedValue({ success: true, data: [m(1), m(2, { status: 'SLOW', breached_metrics: ['IMG'] })] })
    const { unmount } = render(<Shell initial={layout('pagespeed_over_budget', 'stat')}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('pagespeed_over_budget')).toHaveAttribute('data-stat', 'on'))
    expect(widget('pagespeed_over_budget')).toHaveAttribute('data-tone', 'warning')
    expect(widget('pagespeed_over_budget').querySelector('[data-slot="mydash-stat-value"]')).toHaveTextContent('1')
    unmount()

    api.monitoring.getPageSpeedMonitors.mockResolvedValue({ success: true, data: [m(1), m(2, { status: 'SLOW', breached_metrics: ['IMG'] })] })
    const r2 = render(<Shell initial={layout('pagespeed_over_budget')}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('pagespeed_over_budget')?.querySelector('[data-slot="mydash-psp-over-list"]')).not.toBeNull())
    expect(widget('pagespeed_over_budget').querySelector('[data-slot="mydash-psp-over-list"] li')).toHaveTextContent(EN['pspd.type.img'])
    fireEvent.click(widget('pagespeed_over_budget').querySelector('[data-slot="mydash-psp-over"]'))
    expect(navs.at(-1)).toEqual({ tab: 'pagespeed', params: { stat: 'slow' } })
    r2.unmount()

    api.monitoring.getPageSpeedMonitors.mockResolvedValue({ success: false, status: 403, error: 'forbidden' })
    render(<Shell initial={layout('pagespeed_over_budget', 'stat')}><MyDashPage /></Shell>)
    await waitFor(() => expect(widget('pagespeed_over_budget')).toHaveAttribute('data-tone', 'muted'))
    expect(widget('pagespeed_over_budget')).not.toHaveAttribute('data-stat', 'on')
  })
})
