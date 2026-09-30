import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import CheckHistoryTab from '../components/history/CheckHistoryTab.jsx'

/**
 * CheckHistoryTab isteğe bağlı yuvaları (2026-09-28, sertifika geçmişi yeniden tasarımı): `renderSummary`, `renderCard`,
 * `renderEmpty`, genişletilmiş `renderAbove(ctx)` ve `renderRow(item, { index, older, items, filtered })`.
 * Sözleşme: yuva VERİLMEZSE çıktı eskisiyle aynı (dokuz izleme türü + envanter çekmecesi etkilenmez).
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const mobile = vi.hoisted(() => ({ on: false }))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.on }))
vi.mock('../api/client', () => ({
  formatDate:     (s) => s ?? '',
  formatDateSec:  (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      getCheckHistory: vi.fn(),
      getCheckHistoryCsvUrl: vi.fn(() => '/api/monitoring/ping/1/history?format=csv'),
    },
  }),
}))
import { api } from '../api/client'

const iso = (msAgo) => new Date(Date.now() - msAgo).toISOString().slice(0, 19)
const H = 3_600_000
const ITEMS = [
  { id: 3, checked_at: iso(1 * H), up: true },
  { id: 2, checked_at: iso(2 * H), up: false },
  { id: 1, checked_at: iso(3 * H), up: true },
]
const envelope = (over = {}) => ({ success: true, data: {
  items: ITEMS, counts: { total: 30, fail: 1 }, buckets: [], alerts: [],
  range: { from: iso(24 * H), to: iso(0) }, total: ITEMS.length, page: 0, size: 50, ...over,
} })

const base = (props = {}) => (
  <CheckHistoryTab kind="ping" monitorId={1} listKey="slots-hist" urlSync={false} live={false}
    columns={['Zaman', 'Durum']}
    renderRow={(c) => (<><span>{c.checked_at}</span><span>{c.up ? 'UP' : 'DOWN'}</span></>)} {...props} />
)

beforeEach(() => {
  vi.clearAllMocks()
  mobile.on = false
  api.monitoring.getCheckHistory.mockResolvedValue(envelope())
})

describe('CheckHistoryTab — isteğe bağlı yuvalar (geriye uyumlu)', () => {
  it('yuvasız: varsayılan 4 kutucuk + genel telefon kartı + "kayıt yok" bloğu (eski çıktı)', async () => {
    mobile.on = true
    const { unmount } = render(base())
    await screen.findByText(ITEMS[0].checked_at)
    expect(document.querySelectorAll('[data-slot="hist-tile"]')).toHaveLength(4)
    expect(document.querySelectorAll('[data-slot="hist-card"]')).toHaveLength(3)
    unmount()
    api.monitoring.getCheckHistory.mockResolvedValue(envelope({ items: [], total: 0, counts: { total: 0, fail: 0 } }))
    render(base())
    expect(await screen.findByText('No records in the selected range')).toBeInTheDocument()
  })

  it('renderRow ikinci argümanı: index + older (sayfadaki bir önceki, daha ESKİ kontrol) + filtered', async () => {
    const seen = []
    render(base({ renderRow: (c, rc) => { seen.push({ id: c.id, rc }); return (<><span>{c.checked_at}</span><span>x</span></>) } }))
    await screen.findByText(ITEMS[0].checked_at)
    const last = new Map(seen.map((s) => [s.id, s.rc]))
    expect(last.get(3)).toMatchObject({ index: 0, older: ITEMS[1], filtered: false })
    expect(last.get(2).older).toBe(ITEMS[2])
    expect(last.get(1).older).toBeNull()                    // sayfanın sonu: komşu uydurulmaz
    expect(last.get(3).items).toEqual(ITEMS)
  })

  it('renderSummary varsayılan kutucukların YERİNE çizilir; ctx sayaçları ve süzgeç eylemini taşır', async () => {
    const ctxs = []
    render(base({ renderSummary: (ctx) => { ctxs.push(ctx); return (
      <button type="button" data-testid="own-summary" onClick={() => ctx.setStatus('fail')}>{`${ctx.counts.total}/${ctx.counts.fail}/${ctx.preset}`}</button>
    ) } }))
    const own = await screen.findByTestId('own-summary')
    await waitFor(() => expect(own.textContent).toBe('30/1/1'))
    expect(document.querySelectorAll('[data-slot="hist-tile"]')).toHaveLength(0)
    fireEvent.click(own)
    await waitFor(() => {
      const calls = api.monitoring.getCheckHistory.mock.calls
      expect(calls[calls.length - 1][2].status).toBe('fail')
    })
    await waitFor(() => expect(ctxs.at(-1).status).toBe('fail'))
  })

  it('süzgeç açıkken satır bağlamı filtered: true (komşular ardışık değil — tür-özel değişim işareti uydurulmasın)', async () => {
    const seen = []
    render(base({ filterMode: 'fail', renderRow: (c, rc) => { seen.push(rc.filtered); return (<><span>{c.checked_at}</span><span>x</span></>) } }))
    await screen.findByText(ITEMS[0].checked_at)
    fireEvent.click(screen.getAllByRole('button', { pressed: false }).find((b) => b.getAttribute('data-slot') === 'hist-tile'))
    await waitFor(() => expect(seen.at(-1)).toBe(true))
  })

  it('renderAbove genişletilmiş ctx alır (eski { preset, range } alanları yerinde — Alan Adı eğilimi)', async () => {
    const got = []
    // Zarf BIR kez uretilir: envelope() her cagrida Date.now() okur; CI'da yavas render saniye sinirini asinca
    // ikinci cagrinin `from` degeri 1 sn kayip test titriyordu (2026-09-30).
    const env = envelope()
    api.monitoring.getCheckHistory.mockResolvedValue(env)
    render(base({ presets: [7, 30], defaultPreset: 30, renderAbove: (ctx) => { got.push(ctx); return <p>above-{String(ctx.preset)}</p> } }))
    await screen.findByText('above-30')
    await waitFor(() => expect(got.at(-1).range).toEqual(env.data.range))
    expect(typeof got.at(-1).setCustomRange).toBe('function')
    expect(got.at(-1).presets).toEqual([7, 30])
  })

  it('renderEmpty boş aralıkta varsayılan bloğun YERİNE; ctx.setPreset aralığı değiştirir', async () => {
    api.monitoring.getCheckHistory.mockResolvedValue(envelope({ items: [], total: 0, counts: { total: 0, fail: 0 } }))
    render(base({ presets: [1, 7], renderEmpty: (ctx) => (
      <button type="button" onClick={() => ctx.setPreset(7)}>widen-{String(ctx.preset)}</button>
    ) }))
    fireEvent.click(await screen.findByRole('button', { name: 'widen-1' }))
    expect(screen.queryByText('No records in the selected range')).toBeNull()
    await waitFor(() => {
      const calls = api.monitoring.getCheckHistory.mock.calls
      expect(calls[calls.length - 1][2].days).toBe(7)
    })
  })

  it('renderCard telefonda genel kartın YERİNE; masaüstünde tablo satırı renderRow ile kalır', async () => {
    mobile.on = true
    const card = (c, rc) => <div data-testid="own-card">{`${c.id}:${rc.index}:${rc.older?.id ?? '-'}`}</div>
    const { unmount } = render(base({ renderCard: card }))
    await waitFor(() => expect(screen.getAllByTestId('own-card')).toHaveLength(3))
    expect(screen.getAllByTestId('own-card').map((n) => n.textContent)).toEqual(['3:0:2', '2:1:1', '1:2:-'])
    expect(document.querySelectorAll('[data-slot="hist-card"]')).toHaveLength(0)
    unmount()
    mobile.on = false
    render(base({ renderCard: card }))
    await screen.findByText(ITEMS[0].checked_at)
    expect(screen.queryAllByTestId('own-card')).toHaveLength(0)
    expect(screen.getByRole('table')).toBeInTheDocument()
  })
})

/**
 * 2026-09-28 (sertifika geçmişi çekmece + Uptime'da): `cardsBelow` — kart/tablo seçimi KABA göre (Uptime'da iki geçmiş yan
 * yana ~430 px); kontrollü aralıkta (`range` + `onRangeChange`) `ctx.fixedRange` ve yuva eylemleri üstteki seçiciyi sürer.
 */
describe('CheckHistoryTab — kap genişliği ve kontrollü aralık', () => {
  /** Kabın ölçülen genişliği (jsdom yerleşim yapmaz): yalnız `data-slot="check-history"` kökü için. */
  function stubWidth(width) {
    const orig = HTMLElement.prototype.getBoundingClientRect
    HTMLElement.prototype.getBoundingClientRect = function () {
      if (this.getAttribute?.('data-slot') === 'check-history') return { width, height: 800, top: 0, left: 0, right: width, bottom: 800, x: 0, y: 0 }
      return orig.call(this)
    }
    return () => { HTMLElement.prototype.getBoundingClientRect = orig }
  }

  it('cardsBelow: dar kapta (430 < 600) masaüstünde bile kart listesi; geniş kapta tablo; verilmezse görünüm alanı kuralı', async () => {
    let restore = stubWidth(430)
    try {
      const { unmount } = render(base({ cardsBelow: 600 }))
      await screen.findByText(ITEMS[0].checked_at)
      expect(document.querySelector('[data-view="cards"]')).not.toBeNull()
      expect(screen.queryByRole('table')).toBeNull()
      unmount()
      // cardsBelow yok → ölçüm kullanılmaz, mobil değil → tablo (dokuz tür + eski çağıranlar değişmez)
      const { unmount: u2 } = render(base())
      await screen.findByText(ITEMS[0].checked_at)
      expect(screen.getByRole('table')).toBeInTheDocument()
      u2()
    } finally { restore() }
    restore = stubWidth(900)
    try {
      render(base({ cardsBelow: 600 }))
      await screen.findByText(ITEMS[0].checked_at)
      expect(screen.getByRole('table')).toBeInTheDocument()
    } finally { restore() }
  })

  it('cardsBelow: ölçüm yoksa (0) useIsMobile kuralına düşer', async () => {
    mobile.on = true
    render(base({ cardsBelow: 600 }))          // jsdom: genişlik 0
    await screen.findByText(ITEMS[0].checked_at)
    expect(document.querySelector('[data-view="cards"]')).not.toBeNull()
  })

  it('kontrollü aralık: ctx.fixedRange = üstün aralığı; setCustomRange / setPreset(n) onRangeChange\'e gider (iç ön ayar değil)', async () => {
    const from = new Date(Date.now() - 6 * H)
    const to = new Date(Date.now() - H)
    const onRangeChange = vi.fn()
    const ctxs = []
    api.monitoring.getCheckHistory.mockResolvedValue(envelope({ items: [], total: 0, counts: { total: 0, fail: 0 } }))
    render(base({ range: { from, to }, onRangeChange, renderEmpty: (ctx) => { ctxs.push(ctx); return (<>
      <button type="button" onClick={() => ctx.setPreset(90)}>widen</button>
      <button type="button" onClick={() => ctx.setCustomRange(new Date(from.getTime() + H), new Date(from.getTime() + 2 * H))}>zoom</button>
    </>) } }))
    fireEvent.click(await screen.findByRole('button', { name: 'widen' }))
    expect(ctxs.at(-1).fixedMode).toBe(true)
    expect(ctxs.at(-1).fixedRange).toEqual({ from, to })
    expect(onRangeChange).toHaveBeenCalledTimes(1)
    const [wf, wt] = onRangeChange.mock.calls[0]
    expect(wt.getTime() - wf.getTime()).toBe(90 * 24 * H)       // şimdi − 90 gün … şimdi
    expect(Math.abs(wt.getTime() - Date.now())).toBeLessThan(5000)
    fireEvent.click(screen.getByRole('button', { name: 'zoom' }))
    expect(onRangeChange).toHaveBeenLastCalledWith(new Date(from.getTime() + H), new Date(from.getTime() + 2 * H))
    // İstek HER ZAMAN kontrollü aralıkla (iç ön ayar sızmaz)
    for (const [, , p] of api.monitoring.getCheckHistory.mock.calls) {
      expect(p.days).toBeUndefined()
      expect(p.from).toBe(from.toISOString().slice(0, 19))
    }
  })

  it('kontrolsüz aralıkta ctx.fixedRange null (pencere / çekmece)', async () => {
    const ctxs = []
    render(base({ renderAbove: (ctx) => { ctxs.push(ctx); return null } }))
    await screen.findByText(ITEMS[0].checked_at)
    expect(ctxs.at(-1).fixedRange).toBeNull()
    expect(ctxs.at(-1).fixedMode).toBe(false)
  })
})
