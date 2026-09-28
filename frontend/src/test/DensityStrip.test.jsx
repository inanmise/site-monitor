import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, within, waitFor } from './test-utils.jsx'
import DensityStrip, { bucketBounds, failRuns } from '../components/history/DensityStrip.jsx'

// Biçimlendiriciler öngörülebilir: gün "YYYY-MM-DD", tarih-saat "YYYY-MM-DD HH:MM" (kurum saati dönüşümü burada konu değil).
vi.mock('../api/client', () => ({
  formatDateSec: (s) => s ?? '',
  formatDate: (s) => (s ? `${s.slice(0, 10)} ${s.slice(11, 16)}` : ''),
  formatDateOnly: (s) => (s ? s.slice(0, 10) : ''),
}))

describe('DensityStrip', () => {
  it('bucketBounds: anahtar uzunluğu kovayı belirler (dakika/saat/gün)', () => {
    expect(bucketBounds('2026-08-07T10:35')).toEqual(['2026-08-07T10:35:00', '2026-08-07T10:35:59'])
    expect(bucketBounds('2026-08-07T10')).toEqual(['2026-08-07T10:00:00', '2026-08-07T10:59:59'])
    expect(bucketBounds('2026-08-07')).toEqual(['2026-08-07T00:00:00', '2026-08-07T23:59:59'])
  })

  it('dilime tıklayınca onZoom kovayı [from,to] olarak alır; hatasız kova fail sınıfı taşımaz', () => {
    const onZoom = vi.fn()
    const { container } = render(<DensityStrip onZoom={onZoom} buckets={[
      { key: '2026-08-07T09', total: 60, fail: 0 },
      { key: '2026-08-07T10', total: 60, fail: 12 },
    ]} />)
    const cells = container.querySelectorAll('[data-cell]')
    expect(cells.length).toBe(2)
    expect(cells[0].getAttribute('data-variant')).toBe('ghost')   // shadcn Button (data-slot'u Tooltip tetiği ezer)
    expect(cells[0].hasAttribute('data-fail')).toBe(false)
    expect(cells[1].getAttribute('data-fail')).toBe('true')
    // adı kovayı ve hata sayısını taşır (hücreler birbirinden ayırt edilir)
    expect(cells[1].getAttribute('aria-label')).toMatch(/2026-08-07T10:00:00 — 60 \/ 12/)

    fireEvent.click(cells[1])
    expect(onZoom).toHaveBeenCalledWith('2026-08-07T10:00:00', '2026-08-07T10:59:59')
  })

  it('zoomed iken "aralığa dön" chip\'i görünür ve onReset çağrılır; dokunmatikte 40 px', () => {
    const onReset = vi.fn()
    render(<DensityStrip zoomed onReset={onReset}
      buckets={[{ key: '2026-08-07T10', total: 5, fail: 0 }]} />)
    const reset = screen.getByRole('button', { name: /aralığa dön|back to range|reset/i })
    expect(reset).not.toBeNull()
    expect(reset).toHaveClass('pointer-coarse:h-10')
    fireEvent.click(reset)
    expect(onReset).toHaveBeenCalled()
  })
})

/**
 * DOKUNMATİK (2026-09-28): hücreler 3–20 px — dokunmatikte 40 px hedef olamaz, genişletilmiş alanlar komşuyla çakışır.
 * Şeridin üstünü `pointer: coarse`'da 40 px'lik TEK katman kaplar; dokununca hatalı dilimler 40 px'lik satırlar olarak
 * listelenir. jsdom medya sorgusu uygulamaz → katmanın görünürlük sözleşmesi SINIF düzeyinde pinlenir (gerçek ölçüm:
 * Playwright, hasTouch + isMobile).
 */
describe('DensityStrip — dokunmatik katman ve hatalı dilim listesi', () => {
  it('failRuns: zamanda bitişik hatalı kovalar tek koşu; hatasız kova ya da BOŞLUK (gönderilmeyen kova) koşuyu kırar', () => {
    const runs = failRuns([
      { key: '2026-08-07T08', total: 60, fail: 0 },
      { key: '2026-08-07T09', total: 60, fail: 2 },
      { key: '2026-08-07T10', total: 60, fail: 3 },   // 09 ile bitişik → aynı koşu
      { key: '2026-08-07T11', total: 60, fail: 0 },   // hatasız → kırar
      { key: '2026-08-07T12', total: 60, fail: 1 },
      { key: '2026-08-07T14', total: 60, fail: 4 },   // 13 hiç yok (kontrol yok) → yeni koşu
    ])
    expect(runs).toEqual([
      { from: '2026-08-07T09:00:00', to: '2026-08-07T10:59:59', fail: 5, total: 120, buckets: 2, day: false },
      { from: '2026-08-07T12:00:00', to: '2026-08-07T12:59:59', fail: 1, total: 60, buckets: 1, day: false },
      { from: '2026-08-07T14:00:00', to: '2026-08-07T14:59:59', fail: 4, total: 60, buckets: 1, day: false },
    ])
    // Gün ve dakika kovaları da aynı kural
    expect(failRuns([{ key: '2026-08-06', total: 9, fail: 1 }, { key: '2026-08-07', total: 9, fail: 2 }]))
      .toEqual([{ from: '2026-08-06T00:00:00', to: '2026-08-07T23:59:59', fail: 3, total: 18, buckets: 2, day: true }])
    expect(failRuns([{ key: '2026-08-07T10:00', total: 1, fail: 1 }, { key: '2026-08-07T10:02', total: 1, fail: 1 }])).toHaveLength(2)
    expect(failRuns([])).toEqual([])
    expect(failRuns(null)).toEqual([])
  })

  it('katman: yalnız dokunmatikte görünür (fare: display:none), şeridi 40 px yükseklikte kaplar, i18n adlı düğme', () => {
    render(<DensityStrip onZoom={vi.fn()} buckets={[{ key: '2026-08-07T10', total: 60, fail: 12 }]} />)
    const layer = screen.getByRole('button', { name: 'Choose a slice to zoom into' })
    expect(layer).toHaveAttribute('data-slot', 'density-strip-touch')
    expect(layer).toHaveClass('hidden', 'pointer-coarse:block')               // fareli ekranda yok, dokunmatikte var
    expect(layer).toHaveClass('absolute', 'inset-x-0', '-inset-y-2.5')        // 20 px şerit + 2 × 10 px = 40 px
    expect(layer).toHaveAttribute('aria-haspopup', 'dialog')
    // Hücreler fare / klavye için aynı; dokunmatikte hedef ADAYI bile değil (katman tek hedef — dokunma ayarlaması hücre seçemez)
    const cells = document.querySelectorAll('[data-cell]')
    expect(cells).toHaveLength(1)
    expect(cells[0]).toHaveClass('pointer-coarse:pointer-events-none')
  })

  it('dokununca hatalı dilimler 40 px\'lik satırlar; satır o aralığa iner ve liste kapanır', async () => {
    const onZoom = vi.fn()
    render(<DensityStrip onZoom={onZoom} buckets={[
      { key: '2026-08-07T09', total: 60, fail: 0 },
      { key: '2026-08-07T10', total: 60, fail: 12 },
      { key: '2026-08-07T11', total: 60, fail: 3 },
      { key: '2026-08-07T12', total: 60, fail: 0 },
      { key: '2026-08-08T01', total: 60, fail: 1 },
    ]} />)
    fireEvent.click(screen.getByRole('button', { name: 'Choose a slice to zoom into' }))
    const list = await screen.findByRole('dialog', { name: 'Failing slices' })
    expect(within(list).getByText('Tap a row to zoom the history into that slice.')).toBeInTheDocument()
    const rows = within(list).getAllByRole('button')
    expect(rows).toHaveLength(2)
    for (const r of rows) expect(r).toHaveClass('min-h-10', 'w-full')         // 40 px dokunma hedefi, tam genişlik
    // Aynı gün: bitişte yalnız saat; hata / kontrol sayısı rozetle (renk tek başına değil)
    expect(rows[0]).toHaveTextContent('2026-08-07 10:00 – 11:59')
    expect(rows[0]).toHaveTextContent('15 of 120 failed')
    expect(rows[1]).toHaveTextContent('2026-08-08 01:00 – 01:59')
    fireEvent.click(rows[0])
    expect(onZoom).toHaveBeenCalledWith('2026-08-07T10:00:00', '2026-08-07T11:59:59')
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Failing slices' })).toBeNull())
  })

  it('hatasız aralıkta liste "başarısız kontrol yok" der, satır çizmez', async () => {
    render(<DensityStrip onZoom={vi.fn()} buckets={[{ key: '2026-08-07T09', total: 60, fail: 0 }]} />)
    fireEvent.click(screen.getByRole('button', { name: 'Choose a slice to zoom into' }))
    const list = await screen.findByRole('dialog', { name: 'Failing slices' })
    expect(within(list).getByText('No failed checks in this range.')).toBeInTheDocument()
    expect(within(list).queryAllByRole('button')).toHaveLength(0)
  })
})
