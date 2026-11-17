import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, waitFor } from './test-utils.jsx'

/**
 * Performans (2026-11-17) — Kaynak analizi:
 *  • süren analizin 1,5 sn'lik yoklaması değişmeyen özeti YENİ başvuruyla yazıp sekmenin tamamını (pano, geçmiş) yeniden
 *    çiziyordu; artık değişmeyen özet eski başvurusunu korur, sekme çizilmez;
 *  • tarayıcı sekmesi gizliyken yoklama durur, görünür olunca beklemeden sürer;
 *  • nesne listesinin süzme + beş sayaç hesabı tek geçişte (`filterWithCounts`) — sonuç eski `filterItems` / `countsBy` ile
 *    birebir aynı.
 */
const renders = vi.hoisted(() => ({ dashboard: 0 }))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({ monitoring: { resourceScans: vi.fn(), resourceScan: vi.fn(), startResourceScan: vi.fn() } }),
}))
vi.mock('../components/page/resourcescan/ResourceScanDashboard.jsx', async () => {
  const React = await import('react')
  return { default: () => { renders.dashboard += 1; return React.createElement('div', { 'data-slot': 'test-dashboard' }) } }
})
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import ResourceScanTab from '../components/page/resourcescan/ResourceScanTab.jsx'
import {
  BUCKET_KEYS, CATEGORIES, DEFAULT_FILTERS, SCOPES, STATUS_FILTERS, countsBy, filterItems, filterWithCounts,
} from '../components/page/resourcescan/resourceScanModel.js'
import { scanDetail, scanHistory, scanItems, scanSummary } from './helpers/resourceScanFixtures.js'

const MONITOR = { id: 100, name: 'Kurumsal Ana Sayfa', url: 'https://www.example.com/' }
const ok = (data) => ({ success: true, data })
const RUNNING = scanSummary(41, { status: 'RUNNING', progress: { done: 3, total: 9 }, grade: null, finished_at: null })

let hidden = false
beforeEach(() => {
  vi.clearAllMocks()
  renders.dashboard = 0
  hidden = false
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden })
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  api.monitoring.resourceScans.mockResolvedValue(ok(scanHistory({ running: RUNNING })))
})
afterEach(() => { delete document.hidden })

function pollCounter() {
  const st = { polls: 0 }
  api.monitoring.resourceScan.mockImplementation((_id, scanId) => {
    if (scanId !== 41) return Promise.resolve(ok(scanDetail(scanId)))
    st.polls += 1
    // Her yoklamada AYNI içerikli yeni nesne (sunucu yanıtı gibi)
    return Promise.resolve(ok({ scan: { ...RUNNING, progress: { ...RUNNING.progress } }, analysis: null, items: null, items_kept: true }))
  })
  return st
}

describe('kaynak analizi — süren analizin yoklaması', () => {
  it('değişmeyen özet sekmeyi yeniden çizmez', async () => {
    const st = pollCounter()
    render(<ResourceScanTab monitor={MONITOR} pollMs={15} />)
    await waitFor(() => expect(document.querySelector('[data-slot="test-dashboard"]')).not.toBeNull())
    await waitFor(() => expect(st.polls).toBeGreaterThanOrEqual(2))
    const before = renders.dashboard
    const pollsBefore = st.polls
    await waitFor(() => expect(st.polls).toBeGreaterThanOrEqual(pollsBefore + 5))
    expect(renders.dashboard).toBe(before)       // eskiden her yoklamada +1
  })

  it('tarayıcı sekmesi gizliyken yoklanmaz; görünür olunca hemen yoklar', async () => {
    const st = pollCounter()
    render(<ResourceScanTab monitor={MONITOR} pollMs={15} />)
    await waitFor(() => expect(st.polls).toBeGreaterThanOrEqual(2))
    hidden = true
    document.dispatchEvent(new Event('visibilitychange'))
    await new Promise((r) => setTimeout(r, 60))   // uçuştaki tek yoklama biter
    const atHide = st.polls
    await new Promise((r) => setTimeout(r, 200))  // ≈ 13 yoklama aralığı
    expect(st.polls).toBe(atHide)
    hidden = false
    document.dispatchEvent(new Event('visibilitychange'))
    await waitFor(() => expect(st.polls).toBeGreaterThan(atHide))
  })
})

describe('kaynak analizi — süzme + sayaçlar tek geçişte', () => {
  // Bütün boyutların değerleri karışık süzgeçlerle denenir; sonuç eski çok geçişli hesapla birebir aynı olmalı
  const ITEMS = scanItems()
  const FILTER_SETS = [
    DEFAULT_FILTERS,
    { ...DEFAULT_FILTERS, scope: 'all' },
    { ...DEFAULT_FILTERS, scope: 'all', q: 'example' },
    { ...DEFAULT_FILTERS, scope: 'all', q: 'KAMPANYA' },
    { ...DEFAULT_FILTERS, types: [CATEGORIES[0], 'IMAGE'], buckets: [BUCKET_KEYS[1], BUCKET_KEYS[2]] },
    { ...DEFAULT_FILTERS, scope: 'documents', status: 'failed' },
    { ...DEFAULT_FILTERS, scope: 'links', status: 'unverified', q: 'x' },
    { ...DEFAULT_FILTERS, scope: 'all', findings: ['LARGE_IMAGES', 'NO_CACHE'], urls: [ITEMS[1].url, ITEMS[2].url] },
    { ...DEFAULT_FILTERS, scope: 'all', types: ['JS'], q: 'bundle', status: 'failed' },
    ...SCOPES.map((s) => ({ ...DEFAULT_FILTERS, scope: s })),
    ...STATUS_FILTERS.map((s) => ({ ...DEFAULT_FILTERS, scope: 'all', status: s })),
  ]
  it.each(FILTER_SETS.map((f, i) => [i, f]))('süzgeç seti %i', (_i, f) => {
    const { filtered, counts } = filterWithCounts(ITEMS, f)
    expect(filtered).toEqual(filterItems(ITEMS, f))
    for (const dim of ['types', 'buckets', 'findings', 'scope', 'status']) {
      expect(counts[dim], dim).toEqual(countsBy(ITEMS, f, dim))
    }
  })
})
