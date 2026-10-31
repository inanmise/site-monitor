import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, fireEvent, waitFor, screen } from './test-utils.jsx'

// Kaynak analizi — dar kap (telefon / 768 px tablet, kenar çubuğu açık): nesne listesi KART olur; URL kesilmez, bayraklar
// görünür, sayfalama çalışır. Kap genişliği kancası 400 px'e sabitlenir (jsdom yerleşim yapmaz).
vi.mock('../hooks/useElementWidth.js', () => ({
  useElementWidth: () => [() => {}, 400],
  useElementWidthState: () => [400, () => {}],
}))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: withApiFallback({
    monitoring: { resourceScans: vi.fn(), resourceScan: vi.fn(), startResourceScan: vi.fn() },
  }),
}))

import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import ResourceScanTab from '../components/page/resourcescan/ResourceScanTab.jsx'
import { scanDetail, scanHistory } from './helpers/resourceScanFixtures.js'

const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)
const slots = (name, root = document) => [...root.querySelectorAll(`[data-slot="${name}"]`)]

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  api.monitoring.resourceScans.mockResolvedValue({ success: true, data: scanHistory() })
  api.monitoring.resourceScan.mockImplementation((_id, scanId) => Promise.resolve({ success: true, data: scanDetail(scanId) }))
})

describe('ResourceScanTab — dar kap', () => {
  it('nesneler kart görünümünde; ilk kart en büyük nesne, tam URL ve bayraklar', async () => {
    render(<ResourceScanTab monitor={{ id: 100, name: 'X', url: 'https://www.example.com/' }} pollMs={15} />)
    await waitFor(() => expect(slot('prs-hero')).not.toBeNull())
    fireEvent.mouseDown(screen.getByRole('tab', { name: /Nesneler/ }), { button: 0 })
    await waitFor(() => expect(slot('prs-objects-list')).not.toBeNull())
    expect(slot('prs-objects-list').getAttribute('data-layout')).toBe('cards')
    expect(slot('prs-objects-table')).toBeNull()
    let cards = slots('prs-obj')
    expect(cards).toHaveLength(25)
    expect(cards[0].tagName).toBe('LI')
    expect(cards[0].getAttribute('data-category')).toBe('IMAGE')
    expect(cards[0].querySelector('[data-flag="third"]')).not.toBeNull()
    const lazy = cards.find((c) => /alt-banner\.webp/.test(c.textContent))
    expect(lazy.querySelector('[data-flag="lazy"]')).not.toBeNull()
    // Dar kapta süzgeç alanı katlı (r177): arama ve sıralama görünür, süzgeçler "Süzgeçler (n)" düğmesinin arkasında
    expect(slot('prs-search')).not.toBeNull()
    expect(slot('prs-filters-toggle').getAttribute('aria-expanded')).toBe('false')
    expect(slot('prs-type-chips')).toBeNull()
    fireEvent.click(slot('prs-filters-toggle'))
    await waitFor(() => expect(slot('prs-type-chips')).not.toBeNull())
    fireEvent.click(screen.getByRole('button', { name: 'Tümü (40)' }))
    await waitFor(() => expect(slots('prs-obj')[0].getAttribute('data-category')).toBe('DOCUMENT'))
    expect(slot('prs-filters-toggle').textContent).toMatch(/Süzgeçler \(1\)/)
    cards = slots('prs-obj')
    expect(cards[0].textContent).toMatch(/yillik-faaliyet-raporu-2025-tam-surum\.pdf/)
    expect(cards[0].textContent).toMatch(/≥/)   // kırpılmış boyut alt sınır
    const flags = [...cards[0].querySelectorAll('[data-flag]')].map((f) => f.getAttribute('data-flag'))
    expect(flags).toContain('notCounted')
  })
})
