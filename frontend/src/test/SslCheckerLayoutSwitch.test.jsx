import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'

// Telefon ↔ geniş yerleşim değişimi elle (matchMedia jsdom'da olay üretmez)
let mobile = false
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile }))

import DashboardFilters, { DASH_FILTER_DEFAULTS } from '../components/dashboard/DashboardFilters.jsx'

/**
 * Son inceleme (2026-11-06, performans): SSL Checker metni artık App durumunda değil, alanın kendi durumunda. Yerleşim
 * değişiminde (telefon ↔ geniş) `DashboardFilters` alanı başka bir ağaçta yeniden kurar; `initialValue` App'in son
 * çizimindeki (bayat) değerdir — `readInitial` bağlanma ANINDAKİ metni okur, yazılan metin kaybolmaz.
 */
const OPTIONS = {
  team: [{ value: 'all', label: 'All Teams' }],
  group: [{ value: 'all', label: 'All groups' }],
  tag: [{ value: 'all', label: 'All tags' }],
  platform: [],
}

function filters(ssl) {
  const values = { ...DASH_FILTER_DEFAULTS, platform: [] }
  const setters = Object.fromEntries(Object.keys(DASH_FILTER_DEFAULTS).map((k) => [k, () => {}]))
  return <DashboardFilters values={values} setters={setters} options={OPTIONS} shown={1} total={1} onClearAll={() => {}} sslChecker={ssl} />
}

const box = () => screen.getByRole('textbox', { name: /New domain/ })

describe('SSL Checker — yerleşim değişiminde metin korunur', () => {
  it('geniş → telefon → geniş: yazılan metin yeniden kurulan alanda kalır (App yeniden çizilmeden)', () => {
    mobile = false
    const store = { v: '' }
    // App'in kurduğu nesne: initialValue App'in son çizimindeki değer (burada hep ''), readInitial ref'i okur
    const ssl = { initialValue: '', readInitial: () => store.v, onValueChange: (v) => { store.v = v }, onSubmit: async () => false, busy: false }
    const { rerender } = render(filters(ssl))
    fireEvent.change(box(), { target: { value: 'yeni.example.com' } })
    expect(store.v).toBe('yeni.example.com')

    mobile = true
    rerender(filters(ssl))
    expect(box()).toHaveValue('yeni.example.com')

    mobile = false
    rerender(filters(ssl))
    expect(box()).toHaveValue('yeni.example.com')
  })

  it('readInitial yoksa eski davranış: initialValue', () => {
    mobile = false
    render(filters({ initialValue: 'eski.example.com', onValueChange: () => {}, onSubmit: async () => false, busy: false }))
    expect(box()).toHaveValue('eski.example.com')
  })
})
