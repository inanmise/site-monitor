import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from './test-utils.jsx'

// Kaynak analizi prs3 G (fe2) — dar kapta (telefon / tablet) sayfalar KART olarak: her sayfa bir kart, not rozeti, puan
// çipleri, öne çıkan öneriler; açılamayan sayfa nedeniyle, taranmayan sayfa açıklamasıyla. Kap genişliği kancası 400 px.
vi.mock('../hooks/useElementWidth.js', () => ({ useElementWidth: () => [() => {}, 400], useElementWidthState: () => [400, () => {}] }))

import ResourceScanSite from '../components/page/resourcescan/ResourceScanSite.jsx'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { siteDetail } from './helpers/resourceScanFgFixtures.js'

beforeEach(() => { try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ } })

const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)
const slots = (name, root = document) => [...root.querySelectorAll(`[data-slot="${name}"]`)]

describe('Sayfalar görünümü — dar kap (kartlar)', () => {
  it('kart düzeni: tablo yok, her sayfa kart; adresler kesilmeden sarar; açılamayan / taranmayan sayfa söylenir', () => {
    const d = siteDetail(30)
    render(<ResourceScanSite scan={d.scan} analysis={d.analysis} />)
    expect(slot('prs-site-list').getAttribute('data-layout')).toBe('cards')
    expect(screen.queryByRole('table')).toBeNull()
    const cards = slots('prs-site-page')
    expect(cards).toHaveLength(5)
    expect(cards[0].tagName).toBe('LI')
    const url = cards[0].querySelector('[data-slot="prs-obj-url"]')
    expect(url.className).toMatch(/overflow-wrap:anywhere/)
    expect(cards.find((c) => c.getAttribute('data-status') === 'failed').textContent).toMatch(/HTTP 503/)
    expect(cards.find((c) => c.getAttribute('data-status') === 'skipped').textContent).toMatch(/taranmadı/)
    // taranmamış sayfada puan / öneri çizilmez (0 ya da "öneri yok" uydurulmaz)
    expect(slot('prs-site-scores', cards.find((c) => c.getAttribute('data-status') === 'failed'))).toBeNull()
  })

  it('sayfa bloğu yoksa "bu analizde yok" (boş tablo değil)', () => {
    render(<ResourceScanSite scan={{ id: 1, scope: 'site' }} analysis={{}} />)
    expect(slot('prs-site').getAttribute('data-state')).toBe('missing')
    expect(screen.getByText('Sayfa listesi bu analizde yok')).toBeInTheDocument()
  })
})
