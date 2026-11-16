import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'

// İnceleme 2026-11-17: tüm site analizinde (2500+ sayfa satırı) ResourceScanTab'ın sekme rozeti için kurduğu site modeli
// HER çizimde yeniden hesaplanıyordu. Model artık seçili analizin `site` bölümüne bağlı bellekte tutulur: bölüm sekmeleri
// arasında gezinmek (her geçiş bir çizim) siteModel'i yeniden çağırmaz.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      resourceScans: vi.fn(), resourceScan: vi.fn(), startResourceScan: vi.fn(), cancelResourceScan: vi.fn(),
      resourceBudget: vi.fn(), saveResourceBudget: vi.fn(), resourceScanTrend: vi.fn(),
    },
  }),
}))
const counter = vi.hoisted(() => ({ n: 0 }))
vi.mock('../components/page/resourcescan/resourceScanReportModel.js', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, siteModel: (...a) => { counter.n += 1; return actual.siteModel(...a) } }
})

import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import ResourceScanTab from '../components/page/resourcescan/ResourceScanTab.jsx'
import { resetWatchers } from '../components/page/resourcescan/resourceScanWatcher.js'
import { budgetData, fullSiteDetail, fullSiteHistory, siteDetail, trendData } from './helpers/resourceScanFgFixtures.js'

const MONITOR = { id: 100, name: 'Kurumsal Ana Sayfa', url: 'https://www.example.com/', can_edit: true }
const ok = (data) => ({ success: true, data })
const slot = (name) => document.querySelector(`[data-slot="${name}"]`)
const openTab = (name) => fireEvent.mouseDown(screen.getByRole('tab', { name }), { button: 0 })

beforeEach(() => {
  vi.clearAllMocks()
  resetWatchers()
  counter.n = 0
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  window.history.replaceState(null, '', '/?tab=page&monitor=100&mtab=resources')
  api.monitoring.resourceScans.mockResolvedValue(ok(fullSiteHistory()))
  api.monitoring.resourceScan.mockImplementation((_id, scanId) => Promise.resolve(scanId === 31 ? ok(fullSiteDetail(31)) : ok(siteDetail(scanId))))
  api.monitoring.resourceBudget.mockResolvedValue(ok(budgetData({})))
  api.monitoring.resourceScanTrend.mockResolvedValue(ok(trendData()))
})
afterEach(() => { resetWatchers() })

describe('tüm site analizi — site modeli bellekte', () => {
  it('bölüm sekmeleri arasında gezinmek siteModel\'i yeniden hesaplatmaz', async () => {
    render(<ResourceScanTab monitor={MONITOR} pollMs={15} />)
    await waitFor(() => expect(slot('prs-hero')).not.toBeNull())
    await waitFor(() => expect(screen.getByRole('tab', { name: /^Sayfalar/ })).toBeTruthy())
    // Yükleme dalgaları otursun
    await new Promise((r) => setTimeout(r, 50))
    const before = counter.n
    expect(before).toBeGreaterThan(0)
    for (let i = 0; i < 4; i++) {
      openTab(/^Nesneler/)
      await waitFor(() => expect(screen.getByRole('tab', { name: /^Nesneler/ }).getAttribute('data-state')).toBe('active'))
      openTab(/^Pano/)
      await waitFor(() => expect(screen.getByRole('tab', { name: /^Pano/ }).getAttribute('data-state')).toBe('active'))
    }
    expect(counter.n).toBe(before)
  })
})
