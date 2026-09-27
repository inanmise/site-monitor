import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import DateTimeRangePicker from '../components/ui/DateTimeRangePicker.jsx'
import CheckHistoryTab from '../components/history/CheckHistoryTab.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate:     (s) => s ?? '',
  formatDateSec:  (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      getCheckHistory: vi.fn(),
      getCheckHistoryCsvUrl: vi.fn(() => '/api/x'),
    },
  }),
}))
import { api } from '../api/client'

/**
 * 2026-09-27 regresyon taraması, FRONTEND B/1 — "Özel aralık" seçicisinin taslağı her yeniden çizimde sıfırlanıyordu.
 *
 * <p>`DateTimeRangePicker` prop → taslak senkronunu Date REFERANSINA bağlamıştı; çağıranlar her çizimde yeni bir
 * Date geçiyor (`new Date(since)`, `new Date()`), izleme sayfaları da saniyede bir yeniden çiziliyor. Sonuç: kullanıcı
 * Başlangıç'ı seçer, "Uygula"ya basmadan 1 sn içinde seçim eski değere döner. İki katman: seçici DEĞERE bakar,
 * "şimdi" gibi değeri de değişen varsayılanlar çağıranda sabitlenir (CheckHistoryTab).
 *
 * <p>Saat alanı üzerinden seçilir (gün hücresi ay sınırında başka aya düşebilir — zaman bombası olmasın).
 */
const fromTrigger = () => screen.getByRole('button', { name: /^From / })
const pickFromTime = async (hhmm) => {
  fireEvent.click(fromTrigger())
  fireEvent.change(await screen.findByLabelText('Time'), { target: { value: hhmm } })
  fireEvent.click(screen.getByRole('button', { name: 'Done' }))
  await waitFor(() => expect(screen.queryByRole('grid')).toBeNull())
}
/** Varsayılan saatle çakışmayan bir hedef saat (30 dk adımlı). */
const otherThan = (d) => (d.getHours() === 3 ? '04:30' : '03:30')

describe('DateTimeRangePicker — taslak, üst bileşen yeniden çizilince SIFIRLANMAZ', () => {
  const FROM = new Date(2026, 8, 1, 10, 0).getTime()
  const TO = new Date(2026, 8, 5, 18, 0).getTime()
  // Her çizimde YENİ Date nesneleri (aynı değer) — `new Date(since)` geçen çağıranların birebir kalıbı.
  const Parent = ({ fromMs = FROM }) => <DateTimeRangePicker from={new Date(fromMs)} to={new Date(TO)} onApply={() => {}} />

  it('aynı değerli yeni Date nesneleriyle yeniden çizim, seçilmiş ama uygulanmamış Başlangıç\'ı korur', async () => {
    const { rerender } = render(<Parent />)
    expect(fromTrigger()).toHaveAccessibleName(/01\.09\.2026 10:00/)
    await pickFromTime('08:30')
    expect(fromTrigger()).toHaveAccessibleName(/01\.09\.2026 08:30/)

    rerender(<Parent />)   // izleme sayfasının saniyelik çizimi
    rerender(<Parent />)
    expect(fromTrigger()).toHaveAccessibleName(/01\.09\.2026 08:30/)
  })

  it('sözleşme korunur: değer GERÇEKTEN değişince taslak yeni değere eşitlenir', async () => {
    const { rerender } = render(<Parent />)
    await pickFromTime('08:30')
    rerender(<Parent fromMs={new Date(2026, 8, 2, 12, 0).getTime()} />)
    expect(fromTrigger()).toHaveAccessibleName(/02\.09\.2026 12:00/)
  })
})

describe('CheckHistoryTab — Özel aralık varsayılanları kararlı (izleme sayfası saniyede bir çizer)', () => {
  let nowSpy
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
      items: [], counts: { total: 0, fail: 0 }, buckets: [], alerts: [], range: null, total: 0, page: 0, size: 50,
    } })
  })
  afterEach(() => { nowSpy?.mockRestore(); nowSpy = null })

  const tab = () => (
    <CheckHistoryTab kind="ping" monitorId={1} listKey="resync-hist" columns={['Zaman', 'Durum']} urlSync={false} live={false}
      renderRow={(c) => (<><span>{c.checked_at}</span><span>{c.up ? 'UP' : 'DOWN'}</span></>)} />
  )

  it('Özel aralıkta seçilen Başlangıç, sonraki saniyelerin yeniden çizimlerinde korunur', async () => {
    const { rerender } = render(tab())
    await waitFor(() => expect(api.monitoring.getCheckHistory).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: /özel aralık|custom range/i }))
    const target = otherThan(new Date(Date.now() - 86400000))
    await pickFromTime(target)
    expect(fromTrigger()).toHaveAccessibleName(new RegExp(` ${target}$`))

    // Saat ilerler (izleme sayfasının 1 sn'lik çizimi) — "şimdi"ye bağlı varsayılan her çizimde başka DEĞER üretirdi.
    const base = Date.now()
    let tick = 0
    nowSpy = vi.spyOn(Date, 'now').mockImplementation(() => base + (++tick) * 1500)
    rerender(tab())
    rerender(tab())
    expect(fromTrigger()).toHaveAccessibleName(new RegExp(` ${target}$`))
  })
})
