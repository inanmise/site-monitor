import { describe, it, expect, vi, afterEach } from 'vitest'
import { useState } from 'react'
import { render, screen, fireEvent, within, act } from './test-utils.jsx'
import DashboardFilters, { DASH_FILTER_DEFAULTS } from '../components/dashboard/DashboardFilters.jsx'

/**
 * Genel Bakış kart araç çubuğu — sunum sözleşmesi (2026-09-28 yeniden tasarım). Bileşen saf sunum: değerleri çizer,
 * ayarlayıcıları çağırır. Burada gerçek durumlu küçük bir koşum (Harness) kullanılır; süzgeç ANLAMI App boru hattında
 * sınanır (DashboardFilters.app.test.jsx, DashboardPlatformFilter.test.jsx).
 *
 * Kırılma: `useIsMobile` (768) — jsdom varsayılanı 1024 → geniş ekran hapları; telefon için `window.innerWidth = 390`.
 */
const TEAM = [{ value: 'all', label: 'All Teams' }, { value: 'Takım A', label: 'Takım A' }, { value: 'Takım B', label: 'Takım B' }, { value: '__none__', label: 'No team' }]
const GROUP = [{ value: 'all', label: 'All groups' }, { value: 'Ödeme', label: 'Ödeme' }, { value: '__none__', label: 'No group' }]
const TAG = [{ value: 'all', label: 'All tags' }, { value: 'prod', label: 'prod' }, { value: 'web', label: 'web' }]
const PLATFORM = [
  { value: 'IIS', label: 'IIS', count: 1 }, { value: 'OPENSHIFT', label: 'OpenShift', count: 2 }, { value: '__none__', label: 'Not specified', count: 1 },
]
const ALL_OPTIONS = { team: TEAM, group: GROUP, tag: TAG, platform: PLATFORM }

function Harness({ initial = {}, options = ALL_OPTIONS, shown = 10, total = 40, onClearAll = () => {}, ssl }) {
  const [v, setV] = useState({ ...DASH_FILTER_DEFAULTS, platform: [], ...initial })
  const setters = Object.fromEntries(Object.keys(DASH_FILTER_DEFAULTS).map((k) => [k, (x) => setV((p) => ({ ...p, [k]: x }))]))
  return (
    <>
      <DashboardFilters values={v} setters={setters} options={options} shown={shown} total={total}
        onClearAll={() => { onClearAll(); setV({ ...DASH_FILTER_DEFAULTS, platform: [] }) }}
        densityToggle={<span data-testid="density">density</span>}
        sslChecker={ssl || { value: '', onChange: () => {}, onSubmit: () => {}, busy: false }} />
      <output data-testid="state">{JSON.stringify(v)}</output>
    </>
  )
}
const state = () => JSON.parse(screen.getByTestId('state').textContent)
const chipGroup = () => screen.queryByRole('group', { name: 'Active filters' })
const chipNames = () => (chipGroup() ? within(chipGroup()).getAllByRole('button').map((b) => b.getAttribute('aria-label') || b.textContent) : [])
const pick = (pillName, optionName) => {
  fireEvent.click(screen.getByRole('button', { name: pillName }))
  fireEvent.click(screen.getByRole('option', { name: optionName }))
}

describe('DashboardFilters — geniş ekran (≥ 768)', () => {
  it('her süzgeç etiketli bir hap: ad "<etiket>: <değer>", seçim yokken kesikli, sıralama değeri hep görünür', () => {
    render(<Harness />)
    for (const name of ['Status: All', 'Expiry: All', 'Team: All Teams', 'Group: All groups', 'Tag: All tags', 'Sort: Default']) {
      expect(screen.getByRole('button', { name })).toBeInTheDocument()
    }
    const status = screen.getByRole('button', { name: 'Status: All' })
    expect(status).not.toHaveAttribute('data-active')
    expect(status.className).toMatch(/border-dashed/)
    // Sıralama hapı değeri etiketle birlikte çizer (yetim etiket yok — etiket hapın içinde)
    expect(screen.getByRole('button', { name: 'Sort: Default' })).toHaveTextContent(/Sort.*Default/)
    // Platform faset süzgeci aynı satırda; telefon düğmesi/Sheet yok
    expect(screen.getByRole('button', { name: /^Platform/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Filters/ })).toBeNull()
    expect(document.querySelector('[data-slot="dashboard-filters"]')).toHaveAttribute('data-layout', 'inline')
  })

  it('hap seçimi değeri ayarlar, hap birincil tona geçer, çip belirir; çipin × adı süzgeci söyler ve kaldırır', () => {
    render(<Harness />)
    pick('Status: All', 'Warning')
    expect(state().status).toBe('warning')
    const pill = screen.getByRole('button', { name: 'Status: Warning' })
    expect(pill).toHaveAttribute('data-active', 'true')
    expect(pill).toHaveTextContent(/Status.*Warning/)
    expect(chipNames()).toEqual(['Remove filter: Status: Warning', 'Clear filters'])
    fireEvent.click(screen.getByRole('button', { name: 'Remove filter: Status: Warning' }))
    expect(state().status).toBe('all')
    expect(chipGroup()).toBeNull()
  })

  it('uzun listede arama kutusu: takım hapı > 7 seçenekte süzülür', () => {
    const many = [{ value: 'all', label: 'All Teams' }, ...Array.from({ length: 9 }, (_, i) => ({ value: `Takım ${i}`, label: `Takım ${i}` }))]
    render(<Harness options={{ ...ALL_OPTIONS, team: many }} />)
    fireEvent.click(screen.getByRole('button', { name: 'Team: All Teams' }))
    fireEvent.change(screen.getByPlaceholderText('Search teams…'), { target: { value: 'Takım 7' } })
    expect(screen.getAllByRole('option').map((o) => o.textContent)).toEqual(['Takım 7'])
    fireEvent.click(screen.getByRole('option', { name: 'Takım 7' }))
    expect(state().team).toBe('Takım 7')
  })

  it('çipler: arama · durum · takım (Takımsız) · platform (her kod ayrı) · sıralama; tek platform çipi yalnız o kodu kaldırır', () => {
    render(<Harness initial={{ search: 'shop', status: 'error', team: '__none__', platform: ['IIS', '__none__'], sort: 'asc' }} />)
    expect(chipNames()).toEqual([
      'Remove filter: Search: shop', 'Remove filter: Status: Error', 'Remove filter: Team: No team',
      'Remove filter: Platform: IIS', 'Remove filter: Platform: Not specified',
      'Remove filter: Sort: Fewest Days → Most Days', 'Clear filters',
    ])
    fireEvent.click(screen.getByRole('button', { name: 'Remove filter: Platform: IIS' }))
    expect(state().platform).toEqual(['__none__'])
    fireEvent.click(screen.getByRole('button', { name: 'Remove filter: Sort: Fewest Days → Most Days' }))
    expect(state().sort).toBe('default')
    fireEvent.click(screen.getByRole('button', { name: 'Remove filter: Search: shop' }))
    expect(state().search).toBe('')
  })

  it('"Clear filters" App\'in onClearAll\'unu çağırır; çip satırı kaybolur', () => {
    const onClearAll = vi.fn()
    render(<Harness initial={{ status: 'valid', expiry: 'days30' }} onClearAll={onClearAll} />)
    fireEvent.click(within(chipGroup()).getByRole('button', { name: 'Clear filters' }))
    expect(onClearAll).toHaveBeenCalledTimes(1)
    expect(chipGroup()).toBeNull()
  })

  it('seçici gizliyken (seçenek listesi yok) etkin değer yine çipte görünür ve kaldırılabilir', () => {
    render(<Harness options={{ team: null, group: null, tag: null, platform: null }} initial={{ team: 'Takım A', group: '__none__' }} />)
    expect(screen.queryByRole('button', { name: /^Team:/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /^Platform/ })).toBeNull()
    expect(chipNames()).toEqual(['Remove filter: Team: Takım A', 'Remove filter: Group: No group', 'Clear filters'])
    fireEvent.click(screen.getByRole('button', { name: 'Remove filter: Team: Takım A' }))
    expect(state().team).toBe('all')
  })

  it('sonuç sayısı: süzülmemişken "N certificates", süzülmüşken "X of Y certificates" (canlı bölge)', () => {
    const { rerender } = render(<Harness shown={40} total={40} />)
    const count = () => document.querySelector('[data-slot="dashboard-result-count"]')
    expect(count()).toHaveTextContent('40 certificates')
    expect(count()).toHaveAttribute('role', 'status')
    rerender(<Harness shown={12} total={40} />)
    expect(count()).toHaveTextContent('12 of 40 certificates')
  })

  it('arama: yazı ayarlar, Esc ve × temizler; tur kancaları ve SSL Checker (Enter + düğme) yerinde', () => {
    const onSubmit = vi.fn()
    render(<Harness ssl={{ value: 'new.example.com', onChange: () => {}, onSubmit, busy: false }} />)
    const box = screen.getByRole('textbox', { name: 'Search domain...' })
    fireEvent.change(box, { target: { value: 'api' } })
    expect(state().search).toBe('api')
    fireEvent.keyDown(box, { key: 'Escape' })
    expect(state().search).toBe('')
    fireEvent.change(box, { target: { value: 'x' } })
    fireEvent.click(screen.getByRole('button', { name: 'Clear Filter' }))
    expect(state().search).toBe('')
    expect(document.querySelector('[data-tour="dash-filters"]')).toBe(document.querySelector('[data-slot="dashboard-filters"]'))
    const sslInput = document.querySelector('[data-tour="add-domain"] input')
    expect(sslInput).toHaveAttribute('placeholder', 'New domain (e.g. www.example.com)...')
    fireEvent.keyDown(sslInput, { key: 'Enter' })
    fireEvent.click(screen.getByRole('button', { name: 'SSL Checker' }))
    expect(onSubmit).toHaveBeenCalledTimes(2)
  })
})

describe('DashboardFilters — telefon (< 768): "Filters (N)" + alt Sheet', () => {
  const realWidth = window.innerWidth
  afterEach(() => { window.innerWidth = realWidth })
  const phone = (ui) => { window.innerWidth = 390; return render(ui) }

  it('haplar yok; tam genişlik arama, "Filters (N)" (arama sayılmaz), çipler, SSL Checker en sonda', () => {
    phone(<Harness initial={{ search: 'shop', status: 'warning', platform: ['IIS'] }} />)
    expect(document.querySelector('[data-slot="dashboard-filters"]')).toHaveAttribute('data-layout', 'phone')
    expect(screen.queryByRole('button', { name: /^Status:/ })).toBeNull()
    const trigger = screen.getByRole('button', { name: 'Filters (2)' })
    expect(trigger).toHaveAttribute('data-slot', 'dashboard-filters-trigger')
    expect(chipNames()).toEqual(['Remove filter: Search: shop', 'Remove filter: Status: Warning', 'Remove filter: Platform: IIS', 'Clear filters'])
    // Sıra: arama → [Filters + kart görünümü] → çipler → SSL Checker
    const order = [
      document.querySelector('[data-slot="dashboard-domain-search"]'), trigger, screen.getByTestId('density'), chipGroup(),
      document.querySelector('[data-tour="add-domain"]'),
    ]
    for (let i = 1; i < order.length; i++) {
      expect(order[i - 1].compareDocumentPosition(order[i]) & Node.DOCUMENT_POSITION_FOLLOWING, `sıra ${i}`).toBeTruthy()
    }
  })

  it('Sheet: etiketli yerel seçiciler canlı uygular, "Apply (N certificates)" sayıyı taşır ve kapatır', () => {
    phone(<Harness shown={12} />)
    fireEvent.click(screen.getByRole('button', { name: 'Filters' }))
    const sheet = screen.getByRole('dialog', { name: 'Filters' })
    for (const name of ['Sort', 'Status', 'Expiry', 'Team', 'Group', 'Tag']) {
      expect(within(sheet).getByRole('combobox', { name })).toBeInTheDocument()
    }
    // Platform (çoklu) Sheet'te etiketli grup; tetik o anki seçimi yazar
    expect(within(within(sheet).getByRole('group', { name: 'Platform' })).getByRole('button', { name: /^All/ })).toBeInTheDocument()
    fireEvent.change(within(sheet).getByRole('combobox', { name: 'Status' }), { target: { value: 'error' } })
    fireEvent.change(within(sheet).getByRole('combobox', { name: 'Expiry' }), { target: { value: 'days7' } })
    fireEvent.change(within(sheet).getByRole('combobox', { name: 'Sort' }), { target: { value: 'desc' } })
    expect(state()).toMatchObject({ status: 'error', expiry: 'days7', sort: 'desc' })
    const apply = within(sheet).getByRole('button', { name: 'Apply (12 certificates)' })
    fireEvent.click(apply)
    expect(screen.queryByRole('dialog')).toBeNull()
    // Sheet kapanınca (modal arka planı aria-hidden'dan çıkar) tetik etkin sayıyı taşır
    expect(screen.getByRole('button', { name: 'Filters (3)' })).toBeInTheDocument()
  })

  it('Sheet "Clear all" yalnız Sheet süzgeçlerini sıfırlar (arama kalır); etkin süzgeç yokken devre dışı; tekil sayı', () => {
    phone(<Harness shown={1} initial={{ search: 'shop', tag: 'web', platform: ['OPENSHIFT'], sort: 'asc' }} />)
    fireEvent.click(screen.getByRole('button', { name: 'Filters (3)' }))
    const sheet = screen.getByRole('dialog', { name: 'Filters' })
    expect(within(sheet).getByRole('button', { name: 'Apply (1 certificate)' })).toBeInTheDocument()
    fireEvent.click(within(sheet).getByRole('button', { name: 'Clear all' }))
    expect(state()).toMatchObject({ search: 'shop', tag: 'all', platform: [], sort: 'default' })
    expect(within(sheet).getByRole('button', { name: 'Clear all' })).toBeDisabled()
  })

  it('kapat düğmesi i18n adıyla Sheet\'i kapatır', async () => {
    phone(<Harness />)
    fireEvent.click(screen.getByRole('button', { name: 'Filters' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Close' }))
    await act(async () => {})
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})
