import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

/**
 * Genel Bakış süzgeç ANLAMI — gerçek App boru hattıyla (2026-09-28 araç çubuğu yeniden tasarımı).
 *
 * Arayüz tamamen değişti (etiket + SearchableSelect satırı → haplar / telefonda Sheet); bu dosya süzgeçlerin ANLAMININ
 * DEĞİŞMEDİĞİNİ pinler: her seçenek eski STATUS/EXPIRY_FILTER_FN, takım/grup/etiket kuralları ve sıralama
 * karşılaştırıcısıyla aynı kart kümesini/sırasını verir. Beklenen kümeler fixture'dan ELLE hesaplandı (kodu kopyalayan
 * bir referans işlevi değil). Tel biçimi GERÇEK (snake_case). Platform süzgeci ayrıca DashboardPlatformFilter.test.jsx'te.
 */
const fx = vi.hoisted(() => ({
  certs: [
    // a: geçerli, 200 gün, Takım A, Ödeme, prod
    { domain: 'a.example.com', status: 'valid', warning: false, days_remaining: 200, not_after: '2027-04-01T00:00:00', checked_at: '2026-09-28T08:00:00', team_name: 'Takım A', group_name: 'Ödeme', tags: 'prod' },
    // b: uyarılı, 5 gün (kritik), Takım B, Web, prod+web
    { domain: 'b.example.com', status: 'valid', warning: true, days_remaining: 5, not_after: '2026-10-03T00:00:00', checked_at: '2026-09-28T08:00:00', team_name: 'Takım B', group_name: 'Web', tags: 'prod,web' },
    // c: hatalı, gün yok, takımsız/grupsuz/etiketsiz
    { domain: 'c.example.com', status: 'error', warning: false, days_remaining: null, checked_at: '2026-09-28T08:00:00', team_name: null, group_name: null, tags: '' },
    // d: süresi geçmiş (-3), uyarılı, Takım A, Web, web
    { domain: 'd.example.com', status: 'valid', warning: true, days_remaining: -3, not_after: '2026-09-25T00:00:00', checked_at: '2026-09-28T08:00:00', team_name: 'Takım A', group_name: 'Web', tags: 'web' },
    // e: geçerli, 25 gün, Takım B, grupsuz, prod
    { domain: 'e.example.com', status: 'valid', warning: false, days_remaining: 25, not_after: '2026-10-23T00:00:00', checked_at: '2026-09-28T08:00:00', team_name: 'Takım B', group_name: null, tags: 'prod' },
  ],
}))

vi.mock('../api/client', () => {
  const overrides = {
    getMe: () => Promise.resolve({ success: true, username: 'tester', system_role: 'ADMIN', global_admin: true, tour: { status: 'dismissed' } }),
    getCertificates: () => Promise.resolve({ success: true, data: fx.certs, timestamp: '2026-09-28T08:00:00' }),
  }
  function deepMock(ov = {}) {
    const cache = new Map()
    return new Proxy(function () {}, {
      get(_t, key) {
        if (key === 'then') return undefined
        if (typeof key !== 'string') return undefined
        if (key in ov && typeof ov[key] === 'function') return ov[key]
        if (!cache.has(key)) cache.set(key, deepMock(key in ov ? ov[key] : {}))
        return cache.get(key)
      },
      apply() { return Promise.resolve({ success: true, data: [] }) },
    })
  }
  return { api: deepMock(overrides), formatDate: (v) => String(v ?? ''), formatDateSec: (v) => String(v ?? '') }
})

import App from '../App.jsx'
import './appLazyWarmup.js'   // App'in lazy CertificateModal'ı — soğuk dönüşüm testin dışında (öneri 22)

const ALL = ['a', 'b', 'c', 'd', 'e']
/** Kart ızgarasındaki alan adları, DOM SIRASIYLA (a…e kısaltması). */
const grid = () => [...(document.querySelector('[data-slot="cert-grid"]')?.children ?? [])]
  .map((card) => ALL.find((k) => card.textContent.includes(`${k}.example.com`)))
const shown = () => [...grid()].sort()

async function renderDashboard(url = '/') {
  window.history.replaceState({}, '', url)
  render(<App />)
  await waitFor(() => expect(grid().length).toBeGreaterThan(0), { timeout: 5000 })
}
const pick = (pill, option) => {
  fireEvent.click(screen.getByRole('button', { name: pill }))
  fireEvent.click(screen.getByRole('option', { name: option }))
}

describe('Genel Bakış süzgeç anlamı (App boru hattı) — yeni araç çubuğuyla değişmedi', () => {
  const realWidth = window.innerWidth
  beforeEach(() => {
    localStorage.clear()
    try { sessionStorage.clear() } catch { /* jsdom */ }
  })
  afterEach(() => { window.innerWidth = realWidth; window.history.replaceState({}, '', '/') })

  it('varsayılan sıra = öncelik (hata → süresi geçmiş/kritik → … ), sonra kalan gün; sayaç "5 certificates"', async () => {
    await renderDashboard()
    expect(grid()).toEqual(['c', 'd', 'b', 'e', 'a'])
    expect(document.querySelector('[data-slot="dashboard-result-count"]')).toHaveTextContent('5 certificates')
  })

  it('Sıralama: artan (null en sonda) / azalan (null -1 sayılır)', async () => {
    await renderDashboard()
    pick('Sort: Default', 'Fewest Days → Most Days')
    expect(grid()).toEqual(['d', 'b', 'e', 'a', 'c'])
    pick('Sort: Fewest Days → Most Days', 'Most Days → Fewest Days')
    expect(grid()).toEqual(['a', 'e', 'b', 'c', 'd'])
  })

  it('Durum: Geçerli / Uyarılı / Hatalı', async () => {
    await renderDashboard()
    pick('Status: All', 'Valid')
    expect(shown()).toEqual(['a', 'e'])
    pick('Status: Valid', 'Warning')
    expect(shown()).toEqual(['b', 'd'])
    pick('Status: Warning', 'Error')
    expect(shown()).toEqual(['c'])
    expect(document.querySelector('[data-slot="dashboard-result-count"]')).toHaveTextContent('1 of 5 certificates')
  })

  it('Kalan süre: süresi geçmiş / 7 / 30 / 90 gün (negatif ve null dışarıda)', async () => {
    await renderDashboard()
    pick('Expiry: All', 'Expired')
    expect(shown()).toEqual(['d'])
    pick('Expiry: Expired', 'Within 7 Days')
    expect(shown()).toEqual(['b'])
    pick('Expiry: Within 7 Days', 'Within 30 Days')
    expect(shown()).toEqual(['b', 'e'])
    pick('Expiry: Within 30 Days', 'Within 90 Days')
    expect(shown()).toEqual(['b', 'e'])
  })

  it('Takım / Grup / Etiket (Takımsız · Grupsuz · Etiketsiz dâhil) ve VE birleşimi; çipten kaldırma', async () => {
    await renderDashboard()
    pick('Team: All Teams', 'Takım A')
    expect(shown()).toEqual(['a', 'd'])
    pick('Group: All groups', 'Web')
    expect(shown()).toEqual(['d'])   // Takım A VE Web
    fireEvent.click(screen.getByRole('button', { name: 'Remove filter: Team: Takım A' }))
    expect(shown()).toEqual(['b', 'd'])
    fireEvent.click(screen.getByRole('button', { name: 'Remove filter: Group: Web' }))
    pick('Team: All Teams', 'No team')
    expect(shown()).toEqual(['c'])
    fireEvent.click(screen.getByRole('button', { name: 'Remove filter: Team: No team' }))
    pick('Group: All groups', 'No group')
    expect(shown()).toEqual(['c', 'e'])
    fireEvent.click(screen.getByRole('button', { name: 'Remove filter: Group: No group' }))
    pick('Tag: All tags', 'web')
    expect(shown()).toEqual(['b', 'd'])
    pick('Tag: web', 'Untagged')
    expect(shown()).toEqual(['c'])
  })

  it('"Clear filters" arama dâhil hepsini sıfırlar; ?domain= derin bağlantısı arama çipi olarak görünür', async () => {
    await renderDashboard('/?domain=example.com&tab=dashboard')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Remove filter: Search: example.com' })).toBeInTheDocument())
    pick('Status: All', 'Error')
    pick('Sort: Default', 'Most Days → Fewest Days')
    expect(shown()).toEqual(['c'])
    fireEvent.click(within(screen.getByRole('group', { name: 'Active filters' })).getByRole('button', { name: 'Clear filters' }))
    expect(grid()).toEqual(['c', 'd', 'b', 'e', 'a'])
    expect(screen.getByRole('textbox', { name: 'Search domain...' })).toHaveValue('')
    expect(screen.queryByRole('group', { name: 'Active filters' })).toBeNull()
  })

  it('telefon: Sheet\'teki yerel seçiciler AYNI anlamı uygular; "Apply" sayısı canlı', async () => {
    window.innerWidth = 390
    await renderDashboard()
    fireEvent.click(screen.getByRole('button', { name: 'Filters' }))
    const sheet = screen.getByRole('dialog', { name: 'Filters' })
    fireEvent.change(within(sheet).getByRole('combobox', { name: 'Status' }), { target: { value: 'warning' } })
    expect(within(sheet).getByRole('button', { name: 'Apply (2 certificates)' })).toBeInTheDocument()
    fireEvent.change(within(sheet).getByRole('combobox', { name: 'Team' }), { target: { value: 'Takım A' } })
    expect(within(sheet).getByRole('button', { name: 'Apply (1 certificate)' })).toBeInTheDocument()
    fireEvent.click(within(sheet).getByRole('button', { name: 'Apply (1 certificate)' }))
    expect(shown()).toEqual(['d'])
    expect(screen.getByRole('button', { name: 'Filters (2)' })).toBeInTheDocument()
  })
})
